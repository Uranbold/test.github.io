package mn.navmn.app.background.restore

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.route.ParsedRoute
import mn.navmn.app.route.RouteClient
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.TravelMode
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A record ready to be restored: the parsed stored route and the trip it belongs to. */
class LoadedRecord(val meta: RestoreMeta, val route: ParsedRoute, val trip: Trip, val lang: Lang)

/**
 * ADR-0013 §3.2–3.5 glue around [RestoreStore]: writes the record at «Эхлэх» and after every new route (within 2 s,
 * on one serial IO dispatcher, never the engine or main thread), updates the heartbeat every 30 s, deletes it
 * synchronously on a normal end (before the service stops), and answers the restore decision at app start and on the
 * service's null-intent restart. Writes that are still queued when guidance ends are dropped ([active] under [lock]),
 * so a deleted record is never resurrected (AC 17).
 */
@Singleton
class RestoreManager internal constructor(
    private val store: RestoreStore,
    private val routeClient: RouteClient?,
    private val wallNow: () -> Long,
    private val scope: CoroutineScope,
) {
    @Inject constructor(@ApplicationContext context: Context, routeClient: RouteClient) : this(
        RestoreStore(File(context.noBackupFilesDir, RestoreStore.DIR)),
        routeClient,
        { System.currentTimeMillis() },
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
    )

    private val lock = Any()
    private var active = false
    private var current: RestoreMeta? = null
    private var heartbeat: Job? = null

    /** «Эхлэх» (AC 16): route bytes and metadata within 2 s. [restored] keeps the restore history of a restored record. */
    fun onGuidanceStarted(trip: Trip, route: ParsedRoute, lang: Lang, restored: RestoreMeta? = null) {
        val now = wallNow()
        val bytes = route.source
        val meta = RestoreMeta(
            schema = RestoreMeta.SCHEMA,
            destination = RestoreDestination(trip.destination.lat, trip.destination.lon, trip.destinationName),
            costing = trip.mode.costing,
            avoidUnpaved = trip.avoidUnpaved,
            language = lang.tag,
            startedAtWallMs = restored?.startedAtWallMs ?: now,
            heartbeatWallMs = now,
            routeSha256 = bytes?.let { RestoreCodec.sha256(it) },
            restoresWallMs = restored?.restoresWallMs ?: emptyList(),
            routeSource = RouteSource.of(route.onDevice), // AC 16, 54 (change 7c)
        )
        synchronized(lock) {
            active = true
            current = meta
        }
        scope.launch { synchronized(lock) { if (active && current === meta) store.writeAll(meta, bytes) } }
        startHeartbeat()
    }

    /** A new route became active (reroute, ADR-0009 §4 P11): both files rewritten within 2 s (AC 16). */
    fun onNewRoute(route: ParsedRoute, lang: Lang) {
        val bytes = route.source
        val meta = synchronized(lock) {
            val m = current ?: return
            if (!active) return
            m.copy(
                language = lang.tag,
                heartbeatWallMs = wallNow(),
                routeSha256 = bytes?.let { RestoreCodec.sha256(it) },
                routeSource = RouteSource.of(route.onDevice), // AC 54: follows the new route's source
            ).also { current = it }
        }
        scope.launch { synchronized(lock) { if (active && current === meta) store.writeAll(meta, bytes) } }
    }

    /** Normal end («Дуусгах» anywhere, arrival; AC 17): the record is deleted now, synchronously. */
    fun onNormalEnd() {
        synchronized(lock) {
            active = false
            current = null
            heartbeat?.cancel()
            heartbeat = null
            store.delete()
        }
    }

    private fun startHeartbeat() {
        synchronized(lock) {
            heartbeat?.cancel()
            heartbeat = scope.launch {
                while (isActive) {
                    delay(RestoreRules.HEARTBEAT_MS)
                    beat()
                }
            }
        }
    }

    /** Heartbeat (ADR-0013 §3.2): `meta.json` only. Public for fake-clock tests. */
    fun beat() {
        synchronized(lock) {
            val m = current ?: return
            if (!active) return
            val next = m.copy(heartbeatWallMs = wallNow())
            current = next
            store.writeMeta(next)
        }
    }

    /** The ADR-0013 §3.5 decision at app start or on the null-intent restart. Reads two small files. */
    fun decide(sessionAlive: Boolean, locationOk: Boolean): RestoreRules.Decision =
        RestoreRules.decide(store.read(), wallNow(), sessionAlive || synchronized(lock) { active }, locationOk)

    /** The stored record without a decision (AC 22 notification texts and window). */
    fun peek(): RestoreMeta? = (store.read() as? StoredRecord.Present)?.meta

    /**
     * Reads, checks and parses the stored route through the unchanged NAV-005 pipeline (generation 0 of this process).
     * A missing or mismatched route, a parse failure or a step-count mismatch deletes the record silently (AC 24).
     * NAV-012 AC 54 (change 7c): a route the record marks `device` is restored as an on-device route, so the guidance
     * screen shows the OF24 indicator again once trip progress shows.
     */
    fun load(meta: RestoreMeta): LoadedRecord? {
        val processor = routeClient?.processor
        val bytes = store.readRoute(meta)
        val parsed = if (bytes != null && processor != null) (processor.process(bytes, 0) as? RouteOutcome.Ok)?.route else null
        val route = if (parsed != null && meta.routeFromDevice) parsed.asOnDevice() else parsed
        val mode = TravelMode.entries.firstOrNull { it.costing == meta.costing }
        val lang = Lang.fromTag(meta.language)
        if (route == null || mode == null || lang == null) {
            delete()
            return null
        }
        val d = meta.destination
        val trip = Trip(LatLon(d.lat, d.lon), d.text, mode, meta.avoidUnpaved && mode == TravelMode.CAR)
        return LoadedRecord(meta, route, trip, lang)
    }

    fun delete() {
        synchronized(lock) {
            if (active) return // a live session owns the record
            store.delete()
        }
    }

    fun exists(): Boolean = store.exists()
}

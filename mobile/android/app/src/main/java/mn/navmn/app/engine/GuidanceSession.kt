package mn.navmn.app.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mn.navmn.app.BuildConfig
import mn.navmn.app.audio.calls.AudioModeCallSignals
import mn.navmn.app.background.restore.LoadedRecord
import mn.navmn.app.background.restore.RestoreManager
import mn.navmn.app.background.restore.RestoreRules
import mn.navmn.app.location.Fix
import mn.navmn.app.location.LocationSource
import mn.navmn.app.log.DebugLog
import mn.navmn.app.net.NetworkMonitor
import mn.navmn.app.route.ParsedRoute
import mn.navmn.app.route.RouteRequester
import mn.navmn.app.routing.OnDeviceRouting
import mn.navmn.app.service.GuidanceForegroundService
import mn.navmn.app.settings.SettingsRepository
import mn.navmn.app.variant.ReplayVariant
import mn.navmn.app.voice.GuidanceVoice
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NAV-005 AC 101 (screen spec › Interactions, navigation-ux §14.3): the in-memory copy of a session ended by the
 * on-screen «Дуусгах», kept for the undo window. Memory only (AC 66–67): never written, logged or restored after a
 * process death. [route] is the route being followed at the end (after any reroute), [state] its last rendered state.
 */
class EndedSession(val route: ParsedRoute, val trip: Trip, val state: GuidanceState)

/** Holds the one guidance engine of the process (or none) and starts/stops the foreground service with it. */
@Singleton
class GuidanceSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val navigators: NavigatorFactory,
    private val requester: RouteRequester,
    private val location: LocationSource,
    private val network: NetworkMonitor,
    private val voice: GuidanceVoice,
    private val settings: SettingsRepository,
    /** NAV-012 (ADR-0013 §3): the restore record is written at «Эхлэх», on new routes and deleted on a normal end. */
    private val restoreRecords: RestoreManager,
    /** NAV-012 AC 35: audio mode and transient focus loss, collected by the engine while guidance runs. */
    private val calls: AudioModeCallSignals,
    /** ADR-0016 §3, §6: a replay build: its clock drives the engine and no restore record is ever written. */
    replayVariant: Optional<ReplayVariant>,
    /** NAV-021: the routing-file version is pinned per session (AC 17) and `:routing` bound important while guiding. */
    private val onDeviceRouting: OnDeviceRouting,
) {
    private val replay: ReplayVariant? = replayVariant.orElse(null)

    private val _engine = MutableStateFlow<GuidanceEngine?>(null)
    val engine: StateFlow<GuidanceEngine?> = _engine.asStateFlow()

    /** The route the live engine follows (the initial one, then every new route from a reroute); null without a session. */
    @Volatile private var currentRoute: ParsedRoute? = null

    private val log: DebugLog = if (BuildConfig.DEBUG_LOGS) DebugLog { android.util.Log.d("navmn.guidance", it) } else DebugLog.NONE

    /** «Эхлэх» (AC 15): the previewed route is navigated with 0 additional requests. */
    fun start(route: ParsedRoute, trip: Trip, firstFix: Fix) {
        _engine.value?.end()
        onDeviceRouting.onGuidanceStarted()
        val e = newEngine(route, trip, firstFix, showResumedNotice = false)
        currentRoute = route
        _engine.value = e
        if (replay == null) restoreRecords.onGuidanceStarted(trip, route, settings.lang.value) // AC 16: written within 2 s
        ContextCompat.startForegroundService(context, Intent(context, GuidanceForegroundService::class.java))
    }

    /**
     * NAV-005 AC 101 «Буцаах»: guidance restarts on the kept route with 0 requests through the NAV-012 restore path
     * (no depart prompt; the first good fix places the session ≤ 50 m / starts an off-route episode > 50 m / GPS lost
     * after 10 s; one catch-up prompt). The live session gets its normal restore record again (AC 16). False when a
     * session is already alive.
     */
    fun resume(ended: EndedSession): Boolean {
        if (_engine.value != null) return false
        onDeviceRouting.onGuidanceStarted()
        val e = newEngine(ended.route, ended.trip, null, showResumedNotice = false, kept = ended.state)
        currentRoute = ended.route
        _engine.value = e
        if (replay == null) restoreRecords.onGuidanceStarted(ended.trip, ended.route, settings.lang.value)
        ContextCompat.startForegroundService(context, Intent(context, GuidanceForegroundService::class.java))
        return true
    }

    /**
     * NAV-012 restore (AC 18–22, ADR-0013 §3.4): guidance to the stored destination on the stored route, without
     * «Эхлэх», no depart prompt; the first good fix places it on the route (0 requests) or starts an off-route episode.
     * [showNotice] = opened by the user («Замчлал сэргэлээ»); false on the silent system restart. False when a session
     * is already alive (nothing restored).
     */
    fun restore(loaded: LoadedRecord, showNotice: Boolean): Boolean {
        if (_engine.value != null) return false
        onDeviceRouting.onGuidanceStarted()
        val e = newEngine(loaded.route, loaded.trip, null, showNotice)
        currentRoute = loaded.route
        _engine.value = e
        val now = System.currentTimeMillis()
        restoreRecords.onGuidanceStarted(loaded.trip, loaded.route, settings.lang.value, restored = RestoreRules.afterRestore(loaded.meta, now))
        ContextCompat.startForegroundService(context, Intent(context, GuidanceForegroundService::class.java))
        return true
    }

    private fun newEngine(route: ParsedRoute, trip: Trip, firstFix: Fix?, showResumedNotice: Boolean, kept: GuidanceState? = null): GuidanceEngine =
        GuidanceEngine(
            context, route, trip, firstFix, navigators, requester, location, network, voice, settings, log,
            onFinished = { event ->
                when (event) {
                    GuidanceEvent.Arrived -> {
                        if (replay == null) restoreRecords.onNormalEnd() // AC 17: deleted before the service stops
                        stopService()
                    }
                    GuidanceEvent.Ended -> {
                        if (replay == null) restoreRecords.onNormalEnd()
                        stopService()
                        currentRoute = null
                        _engine.value = null
                        onDeviceRouting.onGuidanceEnded()
                    }
                }
            },
            callSignals = calls.inCall(),
            showResumedNotice = showResumedNotice,
            onNewRoute = { r ->
                currentRoute = r
                if (replay == null) restoreRecords.onNewRoute(r, settings.lang.value)
            },
            clock = replay?.clock ?: SystemClocks,
            onDeviceAvailable = onDeviceRouting::available,
            kept = kept,
        )

    /** «Дуусгах» on screen or in the notification, «Хаах» on the arrival panel (AC 19, 55; swipe-away no longer ends, NAV-012 AC 13). */
    fun end() {
        val e = _engine.value
        if (e == null) {
            stopService()
            return
        }
        e.end()
    }

    /**
     * The on-screen «Дуусгах» (NAV-005 AC 101): everything [end] does (AC 19 teardown unchanged), and the caller gets
     * the in-memory copy for the undo window. Null when nothing is navigating (the arrival panel and a session already
     * finished cannot be resumed).
     */
    fun endForUndo(): EndedSession? {
        val e = _engine.value
        val route = currentRoute
        val state = e?.state?.value
        val copy = if (e != null && route != null && state != null && state.phase != GuidancePhase.ARRIVED && state.phase != GuidancePhase.ENDED) {
            EndedSession(route, state.trip, state)
        } else null
        end()
        return copy
    }

    private fun stopService() {
        context.stopService(Intent(context, GuidanceForegroundService::class.java))
    }
}

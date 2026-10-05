package mn.navmn.app.search.offline

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import mn.navmn.app.BuildConfig
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.log.DebugLog
import mn.navmn.app.net.NetworkMonitor
import mn.navmn.app.net.NetworkStateSource
import mn.navmn.app.route.Cancelable
import mn.navmn.app.route.RouteClassifier
import mn.navmn.app.routing.OnlineFirstPolicy
import mn.navmn.app.routing.PackFiles
import mn.navmn.app.routing.RoutingClock
import mn.navmn.app.routing.SystemRoutingClock
import mn.navmn.app.search.PhotonListener
import mn.navmn.app.search.SearchClient
import mn.navmn.app.search.SearchOutcome
import mn.navmn.app.search.reverse.ReverseClient
import mn.navmn.app.variant.ReplayVariant
import java.io.File
import java.util.Optional
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * NAV-023 section C (D163, D199; ADR-0017 §5 "Fallback rule", the same as NAV-021): which source answers each
 * `search` and `reverse` request.
 *
 * - **No installed search file** → the request goes to the gateway exactly as before this story (AC 26): the NAV-011
 *   clients are called unchanged.
 * - **No validated network** → the on-device engine at once, 0 requests (AC 11).
 * - **Online first** otherwise: the request is sent and, **before response headers**, a connection failure, 502 / 503 /
 *   504, 429 or **no headers within 3.0 s** cancels it and the device answers in the same attempt (AC 12). Once headers
 *   arrived, the NAV-011 rules apply (8 s call timeout; a 200 with 0 results and a 400 are authoritative, 0 on-device
 *   queries; AC 13).
 * - **Stickiness** (AC 14): after a fallback caused by the budget or a connection failure, searches **and** reverses go
 *   straight to the device for 60 s or until a newly validated network is reported.
 * - **429** (AC 15): the device answers during `Retry-After` (per operation, as the NAV-011 cooldowns), then online first
 *   again; the NAV-011 «Түр хүлээгээд дахин оролдоно уу» never shows while the device answers.
 *
 * The policy is NAV-021's [OnlineFirstPolicy] (one per operation, so a `search` 429 does not hold back `reverse`) with
 * the time and timers of a [RoutingClock] (a fake clock in JVM tests). An answer from the device is marked
 * `onDevice` (indicator OF24); a device failure is [SearchOutcome.Unavailable] (AC 25).
 */
class SearchSources(
    private val searchClient: SearchClient,
    private val reverseClient: ReverseClient,
    private val device: OnDeviceSearch,
    private val validated: () -> Boolean,
    private val clock: RoutingClock,
    private val log: DebugLog = DebugLog.NONE,
) {
    val searchPolicy = OnlineFirstPolicy()
    val reversePolicy = OnlineFirstPolicy()

    /** With an installed search file the search screen never needs the network (AC 11). */
    fun onDeviceAvailable(): Boolean = device.available()

    /** The network callback reported a newly validated network (AC 14). */
    fun onNewlyValidated() {
        searchPolicy.onNewlyValidated()
        reversePolicy.onNewlyValidated()
    }

    suspend fun search(q: String, lang: Lang, bias: LatLon): SearchOutcome {
        if (!device.available()) return searchClient.search(q, lang, bias)
        // AC 16: the device ranks around the same point the gateway gets (the bias rounded to 3 decimals, D30 / D174).
        val rounded = LatLon(SearchClient.bias(bias.lat).toDouble(), SearchClient.bias(bias.lon).toDouble())
        return answer("search", searchPolicy, { searchClient.start(q, lang, bias, it) }) { device.search(q, lang, rounded) }
    }

    suspend fun reverse(p: LatLon, lang: Lang): SearchOutcome {
        if (!device.available()) return reverseClient.reverse(p, lang)
        return answer("reverse", reversePolicy, { reverseClient.start(p, lang, it) }) { device.reverse(p, lang) }
    }

    private suspend fun answer(
        what: String,
        policy: OnlineFirstPolicy,
        start: (PhotonListener) -> okhttp3.Call,
        local: suspend () -> SearchOutcome,
    ): SearchOutcome {
        if (policy.decide(clock.now(), validated()) == OnlineFirstPolicy.Source.ON_DEVICE) {
            log.d("$what source: on-device")
            return local()
        }
        return when (val r = online(what, policy, start)) {
            is Online.Answer -> r.outcome
            Online.Fallback -> local()
        }
    }

    private sealed interface Online {
        data class Answer(val outcome: SearchOutcome) : Online
        data object Fallback : Online
    }

    /** One online try with the 3.0 s header budget. Resumes once: the gateway's answer, or [Online.Fallback]. */
    private suspend fun online(what: String, policy: OnlineFirstPolicy, start: (PhotonListener) -> okhttp3.Call): Online =
        suspendCancellableCoroutine { cont ->
            val lock = Any()
            val sentAt = clock.now()
            var waitingHeaders = true
            var done = false
            var call: okhttp3.Call? = null
            var timer: Cancelable? = null

            fun finish(r: Online) {
                val first = synchronized(lock) {
                    if (done) false else {
                        done = true
                        true
                    }
                }
                if (first && cont.isActive) cont.resume(r)
            }

            /** Under [lock]: still before headers → the device answers. */
            fun toLocal(reason: String, sticky: Boolean): Boolean {
                if (!waitingHeaders || done) return false
                waitingHeaders = false
                timer?.cancel()
                if (sticky) {
                    // AC 14: searches and reverses both go to the device for 60 s.
                    searchPolicy.onTimeoutOrOffline(clock.now())
                    reversePolicy.onTimeoutOrOffline(clock.now())
                }
                log.d("$what source: on-device after $reason (${clock.now() - sentAt} ms)")
                return true
            }

            val listener = object : PhotonListener {
                override fun onHeaders(status: Int, retryAfter: String?): Boolean {
                    val local = synchronized(lock) {
                        // The budget (or a cancel) already ended this try: drop the late response.
                        if (!waitingHeaders || done) return false
                        when (status) {
                            502, 503, 504 -> toLocal("gateway status $status", sticky = false)
                            429 -> {
                                policy.onRateLimited(clock.now(), RouteClassifier.retryAfter(retryAfter))
                                toLocal("gateway status 429", sticky = false)
                            }
                            else -> {
                                waitingHeaders = false
                                timer?.cancel()
                                false
                            }
                        }
                    }
                    if (local) finish(Online.Fallback)
                    return !local
                }

                override fun onFailureBeforeHeaders() {
                    val local = synchronized(lock) { toLocal("a connection failure", sticky = true) }
                    if (local) finish(Online.Fallback)
                }

                override fun onResult(outcome: SearchOutcome) = finish(Online.Answer(outcome))
            }

            val t = clock.schedule(policy.headerBudgetMs) {
                val c = synchronized(lock) {
                    if (!toLocal("the header budget", sticky = true)) return@schedule
                    call
                }
                c?.cancel()
                finish(Online.Fallback)
            }
            synchronized(lock) { timer = t }
            val c = start(listener)
            // The budget (or a cancel) may already have ended the try before the call existed.
            val cancelNow = synchronized(lock) {
                call = c
                done
            }
            if (cancelNow) c.cancel()
            cont.invokeOnCancellation {
                synchronized(lock) {
                    done = true
                    timer?.cancel()
                }
                c.cancel()
            }
        }
}

/** NAV-023: the on-device search host and the online-first transport for both search instances and the card. */
@Module
@InstallIn(SingletonComponent::class)
object OfflineSearchModule {
    @Provides @Singleton
    fun offlineSearch(@ApplicationContext context: Context, replay: Optional<ReplayVariant>): OfflineSearch = OfflineSearch(
        source = ActiveJsonSearchSource(File(context.noBackupFilesDir, PackFiles.PACKS_DIR)),
        // A replay (demo) build has no offline pack (NAV-022 UX B7).
        enabled = !replay.isPresent,
        log = searchLog(),
    )

    @Provides @Singleton
    fun searchSources(search: SearchClient, reverse: ReverseClient, offline: OfflineSearch, network: NetworkMonitor): SearchSources =
        sources(search, reverse, offline, network)

    fun sources(search: SearchClient, reverse: ReverseClient, offline: OnDeviceSearch, network: NetworkStateSource): SearchSources {
        val s = SearchSources(search, reverse, offline, { network.validated.value }, SystemRoutingClock, searchLog())
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { network.newlyValidated.collect { s.onNewlyValidated() } }
        return s
    }

    private fun searchLog(): DebugLog = if (BuildConfig.DEBUG_LOGS) DebugLog { android.util.Log.d("navmn.search", it) } else DebugLog.NONE
}

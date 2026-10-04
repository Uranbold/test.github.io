package mn.navmn.app.routing

import kotlinx.coroutines.suspendCancellableCoroutine
import mn.navmn.app.log.DebugLog
import mn.navmn.app.route.Cancelable
import mn.navmn.app.route.OnlineListener
import mn.navmn.app.route.RouteClassifier
import mn.navmn.app.route.RouteClient
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.RouteRequester
import kotlin.coroutines.resume

/**
 * NAV-021 R7 (ADR-0017 §5, D163, D199): the route transport for the preview, every reroute and the NAV-012 restore.
 *
 * Without a usable routing file ([onDeviceAvailable] false: none installed, refused by the allow-list, or on-device
 * routing disabled after 3 deaths) every request goes to [online] exactly as before this story (AC 13, 30), and the
 * `:routing` process is never started.
 *
 * With one, [policy] picks the source. Online first: the gateway request is sent and, **before response headers
 * arrive**, a connection failure, 502/503/504, 429 or no headers within 3.0 s cancels it and the on-device engine
 * answers the same request in the same attempt (AC 9). Once headers arrived, the answer is handled as today
 * (12 s client timeouts, authoritative answers shown, 0 on-device requests; AC 10). One [start] = one attempt with
 * exactly one [onResult], so `ReroutePolicy` counts the online try plus its fallback once (AC 14). The attempt fails
 * (Unavailable, or RateLimited after a 429) only when both sources failed, so the NAV-005 back-off applies only then.
 */
class FallbackRouteRequester(
    private val online: RouteClient,
    private val onDevice: RouteRequester,
    private val policy: OnlineFirstPolicy,
    private val clock: RoutingClock,
    private val validated: () -> Boolean,
    private val onDeviceAvailable: () -> Boolean,
    private val log: DebugLog = DebugLog.NONE,
) : RouteRequester {

    override fun start(request: RouteRequest, generation: Int, onResult: (RouteOutcome) -> Unit): Cancelable {
        if (!onDeviceAvailable()) return online.start(request, generation, onResult)
        return when (policy.decide(clock.now(), validated())) {
            OnlineFirstPolicy.Source.ON_DEVICE -> {
                log.d("route source: on-device")
                onDevice.start(request, generation, onResult)
            }
            OnlineFirstPolicy.Source.ONLINE_FIRST -> Attempt(request, generation, onResult).begin()
        }
    }

    suspend fun fetch(request: RouteRequest, generation: Int): RouteOutcome = suspendCancellableCoroutine { cont ->
        val c = start(request, generation) { if (cont.isActive) cont.resume(it) }
        cont.invokeOnCancellation { c.cancel() }
    }

    private enum class State { WAITING_HEADERS, ONLINE_BODY, LOCAL, DONE }

    private inner class Attempt(
        private val request: RouteRequest,
        private val generation: Int,
        private val onResult: (RouteOutcome) -> Unit,
    ) : OnlineListener {
        private val lock = Any()
        private val sentAt = clock.now()
        private var state = State.WAITING_HEADERS
        private var onlineCall: Cancelable? = null
        private var localCall: Cancelable? = null
        private var timer: Cancelable? = null
        /** What the attempt reports when the on-device engine fails too. */
        private var failure: RouteOutcome = RouteOutcome.Unavailable

        fun begin(): Cancelable {
            val t = clock.schedule(policy.headerBudgetMs) { onBudget() }
            synchronized(lock) { timer = t }
            val c = online.startOnline(request, generation, this)
            val cancelNow = synchronized(lock) {
                onlineCall = c
                state == State.LOCAL || state == State.DONE
            }
            if (cancelNow) c.cancel()
            return Cancelable { cancel() }
        }

        override fun onHeaders(status: Int, retryAfter: String?): Boolean {
            val goLocal = synchronized(lock) {
                if (state != State.WAITING_HEADERS) return false
                timer?.cancel()
                when (status) {
                    502, 503, 504 -> toLocal(RouteOutcome.Unavailable)
                    429 -> {
                        val s = RouteClassifier.retryAfter(retryAfter)
                        policy.onRateLimited(clock.now(), s)
                        toLocal(RouteOutcome.RateLimited(s))
                    }
                    else -> {
                        state = State.ONLINE_BODY
                        false
                    }
                }
            }
            if (goLocal) {
                log.d("route source: on-device after gateway status $status (${clock.now() - sentAt} ms)")
                startLocal()
            }
            return !goLocal
        }

        override fun onFailureBeforeHeaders() {
            synchronized(lock) {
                if (state != State.WAITING_HEADERS) return
                timer?.cancel()
                policy.onTimeoutOrOffline(clock.now())
                toLocal(RouteOutcome.Unavailable)
            }
            log.d("route source: on-device after a connection failure (${clock.now() - sentAt} ms)")
            startLocal()
        }

        private fun onBudget() {
            val call = synchronized(lock) {
                if (state != State.WAITING_HEADERS) return
                policy.onTimeoutOrOffline(clock.now())
                toLocal(RouteOutcome.Unavailable)
                onlineCall
            }
            call?.cancel() // the late Cancelled callback is ignored (state is LOCAL)
            log.d("route source: on-device after the header budget (${clock.now() - sentAt} ms)")
            startLocal()
        }

        override fun onResult(outcome: RouteOutcome) {
            synchronized(lock) {
                if (state != State.ONLINE_BODY) return
                state = State.DONE
            }
            onResult.invoke(outcome)
        }

        /** Under [lock]. */
        private fun toLocal(failureOutcome: RouteOutcome): Boolean {
            state = State.LOCAL
            failure = failureOutcome
            return true
        }

        private fun startLocal() {
            val c = onDevice.start(request, generation) { local -> onLocal(local) }
            val cancelNow = synchronized(lock) {
                localCall = c
                state == State.DONE
            }
            if (cancelNow) c.cancel()
        }

        private fun onLocal(local: RouteOutcome) {
            val out = synchronized(lock) {
                if (state != State.LOCAL) return
                state = State.DONE
                when (local) {
                    RouteOutcome.Cancelled -> return
                    RouteOutcome.Unavailable, RouteOutcome.BadResponse, RouteOutcome.BadRequest, RouteOutcome.Offline,
                    is RouteOutcome.RateLimited -> failure
                    else -> local
                }
            }
            onResult.invoke(out)
        }

        private fun cancel() {
            val (t, o, l) = synchronized(lock) {
                if (state == State.DONE) return
                state = State.DONE
                Triple(timer, onlineCall, localCall)
            }
            t?.cancel()
            o?.cancel()
            l?.cancel()
            onResult.invoke(RouteOutcome.Cancelled)
        }
    }
}

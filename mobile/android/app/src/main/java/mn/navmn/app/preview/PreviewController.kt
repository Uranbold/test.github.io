package mn.navmn.app.preview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import mn.navmn.app.route.ParsedRoute
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.TravelMode

/** Location problems shown in the preview's result region (S4 variants; AC 8–12). */
enum class LocationProblem { NEEDS_PERMISSION, DENIED, APPROXIMATE, SERVICES_OFF, UNAVAILABLE }

/** One state of the S3 result region (screen spec › States › S3). */
sealed interface PreviewResult {
    data object WaitingForLocation : PreviewResult
    data class Location(val problem: LocationProblem) : PreviewResult
    data object Pending : PreviewResult
    data object Loading : PreviewResult
    data class Route(val route: ParsedRoute, val receivedWallMs: Long) : PreviewResult
    data object SamePoint : PreviewResult
    data class NoRoute(val avoidHint: Boolean) : PreviewResult
    data object OutOfArea : PreviewResult
    data object TooFar : PreviewResult
    data object Unavailable : PreviewResult
    data object Offline : PreviewResult
    data class RateLimited(val retryEnabled: Boolean) : PreviewResult
    data object Error : PreviewResult
}

data class Destination(val point: LatLon, val name: String?)

data class PreviewState(
    val destination: Destination,
    val mode: TravelMode = TravelMode.CAR,
    val avoidUnpaved: Boolean = false,
    val origin: Fix? = null,
    val result: PreviewResult = PreviewResult.WaitingForLocation,
) {
    val canStart: Boolean get() = result is PreviewResult.Route
}

/**
 * Route preview requests (AC 5–7, NAV-004 state rules): exactly one POST per origin / mode / avoid change (mode tabs
 * settle 300 ms), loading after 300 ms, same point within 10 m → 0 requests, offline → 0 requests and one request by
 * itself when the network returns, 429 → 0 requests for Retry-After then retry on user action, 12 s timeout handled by
 * the route client. «Эхлэх» only with a route.
 */
class PreviewController(
    private val scope: CoroutineScope,
    private val fetch: suspend (RouteRequest) -> RouteOutcome,
    private val lang: () -> Lang,
    private val isOnline: () -> Boolean,
    private val elapsedNow: () -> Long,
    private val wallNow: () -> Long,
) {
    companion object {
        const val LOADING_DELAY_MS = 300L
        const val MODE_SETTLE_MS = 300L
        const val SAME_POINT_M = 10.0
    }

    private val _state = MutableStateFlow<PreviewState?>(null)
    val state: StateFlow<PreviewState?> = _state.asStateFlow()
    private var job: Job? = null
    private var cooldownUntil = 0L
    /** Number of route requests started (QA visibility, AC 5/7). */
    var requestsStarted = 0
        private set

    fun open(destination: Destination) {
        job?.cancel()
        _state.value = PreviewState(destination)
    }

    fun close() {
        job?.cancel()
        _state.value = null
    }

    fun locationProblem(p: LocationProblem) {
        val s = _state.value ?: return
        job?.cancel()
        _state.value = s.copy(result = PreviewResult.Location(p))
    }

    fun waitingForLocation() {
        val s = _state.value ?: return
        _state.value = s.copy(result = PreviewResult.WaitingForLocation)
    }

    fun setOrigin(fix: Fix) {
        val s = _state.value ?: return
        _state.value = s.copy(origin = fix)
        request(0)
    }

    fun setMode(mode: TravelMode) {
        val s = _state.value ?: return
        if (s.mode == mode) return
        _state.value = s.copy(mode = mode)
        request(MODE_SETTLE_MS)
    }

    fun setAvoidUnpaved(value: Boolean) {
        val s = _state.value ?: return
        if (s.avoidUnpaved == value) return
        _state.value = s.copy(avoidUnpaved = value)
        request(0)
    }

    fun retry() {
        if (elapsedNow() < cooldownUntil) return
        request(0)
    }

    /** AC 7 offline row: one request by itself when the network returns. */
    fun onNetworkRestored() {
        if (_state.value?.result == PreviewResult.Offline) request(0)
    }

    private fun update(result: PreviewResult) {
        _state.value = _state.value?.copy(result = result)
    }

    private fun request(settleMs: Long) {
        val s = _state.value ?: return
        val origin = s.origin ?: return
        job?.cancel()
        if (Geo.distance(origin.latLon, s.destination.point) <= SAME_POINT_M) {
            update(PreviewResult.SamePoint)
            return
        }
        if (!isOnline()) {
            update(PreviewResult.Offline)
            return
        }
        if (elapsedNow() < cooldownUntil) {
            update(PreviewResult.RateLimited(retryEnabled = false))
            return
        }
        update(PreviewResult.Pending)
        job = scope.launch {
            if (settleMs > 0) delay(settleMs)
            val cur = _state.value ?: return@launch
            val loading = launch {
                delay(LOADING_DELAY_MS)
                update(PreviewResult.Loading)
            }
            val req = RouteRequest(origin.latLon, cur.destination.point, cur.mode, cur.avoidUnpaved && cur.mode == TravelMode.CAR, lang())
            requestsStarted++
            val outcome = fetch(req)
            loading.cancel()
            update(
                when (outcome) {
                    is RouteOutcome.Ok -> PreviewResult.Route(outcome.route, wallNow())
                    RouteOutcome.NoRoute -> PreviewResult.NoRoute(avoidHint = cur.avoidUnpaved && cur.mode == TravelMode.CAR)
                    RouteOutcome.OutOfCoverage -> PreviewResult.OutOfArea
                    RouteOutcome.TooFar -> if (cur.mode == TravelMode.WALK) PreviewResult.TooFar else PreviewResult.NoRoute(cur.avoidUnpaved)
                    RouteOutcome.Unavailable -> PreviewResult.Unavailable
                    RouteOutcome.Offline -> PreviewResult.Offline
                    is RouteOutcome.RateLimited -> {
                        cooldownUntil = elapsedNow() + outcome.retryAfterS * 1000L
                        scope.launch {
                            delay(outcome.retryAfterS * 1000L)
                            if (_state.value?.result is PreviewResult.RateLimited) update(PreviewResult.RateLimited(retryEnabled = true))
                        }
                        PreviewResult.RateLimited(retryEnabled = false)
                    }
                    RouteOutcome.BadRequest, RouteOutcome.BadResponse -> PreviewResult.Error
                    RouteOutcome.Cancelled -> return@launch
                },
            )
        }
    }
}

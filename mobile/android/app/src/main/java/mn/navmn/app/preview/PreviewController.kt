package mn.navmn.app.preview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import mn.navmn.app.preview.points.PointRules
import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.route.ParsedRoute
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RoutePurpose
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
    /**
     * NAV-011 AC 15–21: [routes] are the response's routes (1–3, Valhalla order), [selected] the chosen one (route 1
     * after every new response, AC 21). [route] is the selected route, the one «Эхлэх» hands to guidance (AC 19).
     */
    data class Route(val routes: List<ParsedRoute>, val receivedWallMs: Long, val selected: Int = 0) : PreviewResult {
        constructor(route: ParsedRoute, receivedWallMs: Long) : this(listOf(route), receivedWallMs, 0)

        init {
            require(routes.isNotEmpty() && selected in routes.indices)
        }

        val route: ParsedRoute get() = routes[selected]
        val k: Int get() = routes.size
    }
    data object SamePoint : PreviewResult
    data class NoRoute(val avoidHint: Boolean) : PreviewResult
    data object OutOfArea : PreviewResult
    data object TooFar : PreviewResult
    data object Unavailable : PreviewResult
    data object Offline : PreviewResult
    data class RateLimited(val retryEnabled: Boolean) : PreviewResult
    data object Error : PreviewResult
}

/**
 * The preview destination from a search result ([name]) or a map point (null → «Сонгосон цэг»). NAV-018 (ADR-0015 §2):
 * both points are [RoutePoint]s; this factory keeps the NAV-005 / NAV-011 call sites unchanged.
 */
@Suppress("FunctionName")
fun Destination(point: LatLon, name: String?): RoutePoint = if (name != null) RoutePoint.Place(point, name) else RoutePoint.MapPoint(point)

data class PreviewState(
    /** NAV-018: never null (AC 7). */
    val destination: RoutePoint,
    val mode: TravelMode = TravelMode.CAR,
    val avoidUnpaved: Boolean = false,
    /** NAV-018: null while «Миний байршил» is being resolved on open, or when it could not be (AC 2: empty start field). */
    val origin: RoutePoint? = null,
    val result: PreviewResult = PreviewResult.WaitingForLocation,
) {
    /** NAV-018 AC 15 (ADR-0015 §3): a route and no chosen start. */
    val canStart: Boolean get() = PointRules.canStart(result is PreviewResult.Route, origin)

    /** NAV-018 AC 15: O1 next to the disabled «Эхлэх» when a route renders with a chosen start. */
    val showStartHint: Boolean get() = PointRules.showStartHint(result is PreviewResult.Route, origin)

    /** NAV-018 AC 2: the start field is empty (placeholder) only when the device start failed and nothing was chosen. */
    val originEmpty: Boolean get() = origin == null && result is PreviewResult.Location
}

/**
 * Route preview requests (AC 5–7, NAV-004 state rules; NAV-011 AC 14, 17, 21–24: `alternates: 2`, selection without a
 * request, «Дугуй», the mode kept for the app session, an older response never replacing a newer one): exactly one POST per origin / mode / avoid change (mode tabs
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
    private var generation = 0
    /** NAV-011 AC 22: the selected mode is kept for the rest of the app session (memory only, never stored). */
    var sessionMode: TravelMode = TravelMode.CAR
        private set
    /** Number of route requests started (QA visibility, AC 5/7). */
    var requestsStarted = 0
        private set

    fun open(destination: RoutePoint) {
        job?.cancel()
        generation++
        _state.value = PreviewState(destination, mode = sessionMode)
    }

    fun close() {
        job?.cancel()
        generation++
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

    /** NAV-005 / NAV-011 call sites: the start is «Миний байршил» from [fix]. */
    fun setOrigin(fix: Fix) = setOrigin(RoutePoint.MyLocation(fix))

    /** NAV-018 AC 2–6, 10: sets the start (frozen when set) and sends one request when both points are set. */
    fun setOrigin(point: RoutePoint) {
        val s = _state.value ?: return
        _state.value = s.copy(origin = point)
        request(0)
    }

    /** NAV-018 AC 4, 6: sets the destination; one request when the start is set. */
    fun setDestination(point: RoutePoint) {
        val s = _state.value ?: return
        _state.value = s.copy(destination = point)
        request(0)
    }

    /**
     * NAV-018 AC 11: exchanges the two points (texts and markers follow the state) and sends exactly one request,
     * subject to the same-point, offline and 429 rules. False (no change) while the start is empty.
     */
    fun swap(): Boolean {
        val s = _state.value ?: return false
        val origin = s.origin ?: return false
        _state.value = s.copy(origin = s.destination, destination = origin)
        request(0)
        return true
    }

    fun setMode(mode: TravelMode) {
        val s = _state.value ?: return
        if (s.mode == mode) return
        sessionMode = mode
        _state.value = s.copy(mode = mode)
        request(MODE_SETTLE_MS)
    }

    /**
     * NAV-011 AC 17: select route [index] (line tap or «Маршрут сонгох»). Pure state change: 0 requests, the camera is
     * not touched. Ignored for the selected route or an index outside the response.
     */
    fun select(index: Int) {
        val s = _state.value ?: return
        val r = s.result as? PreviewResult.Route ?: return
        if (index == r.selected || index !in r.routes.indices) return
        _state.value = s.copy(result = r.copy(selected = index))
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
        val g = ++generation
        if (PointRules.samePoint(origin, s.destination)) {
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
            // NAV-018 AC 13: the start as set when the request was planned (never refreshed), the current destination.
            val req = RouteRequest(
                (cur.origin ?: origin).point, cur.destination.point, cur.mode, cur.avoidUnpaved && cur.mode == TravelMode.CAR, lang(),
                purpose = RoutePurpose.PREVIEW,
            )
            requestsStarted++
            val outcome = fetch(req)
            loading.cancel()
            if (g != generation) return@launch
            update(
                when (outcome) {
                    is RouteOutcome.Ok -> PreviewResult.Route(outcome.routes, wallNow(), selected = 0)
                    RouteOutcome.NoRoute -> PreviewResult.NoRoute(avoidHint = cur.avoidUnpaved && cur.mode == TravelMode.CAR)
                    RouteOutcome.OutOfCoverage -> PreviewResult.OutOfArea
                    // AC 24: N11 on «Явган» and «Дугуй»; on «Машин» the same code is «Маршрут олдсонгүй».
                    RouteOutcome.TooFar -> if (cur.mode != TravelMode.CAR) PreviewResult.TooFar else PreviewResult.NoRoute(cur.avoidUnpaved)
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

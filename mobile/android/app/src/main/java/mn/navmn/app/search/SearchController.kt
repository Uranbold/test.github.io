package mn.navmn.app.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.search.assist.CombineOutcomes
import mn.navmn.app.search.assist.PlanMode
import mn.navmn.app.search.assist.QueryPlan
import mn.navmn.app.search.assist.QueryPlanner
import mn.navmn.app.search.assist.Settle

/** List states of S2 (screen spec › Search results; NAV-003). */
sealed interface SearchView {
    data object Closed : SearchView
    data object Loading : SearchView
    data class Results(val items: List<PlaceDisplay.Info>) : SearchView
    data object NoResults : SearchView
    data object Unavailable : SearchView
    data class RateLimited(val retryEnabled: Boolean) : SearchView
    data object Offline : SearchView
    data object Error : SearchView

    /**
     * NAV-011 D140 (ADR-0012 Amendment A1): the settled query is an ADR-0006 §2.2 coordinate pair. One option
     * «Сонгосон цэг», 0 requests. [point] is the parsed value, not rounded; the state carries no strings.
     */
    data class Coordinate(val point: LatLon) : SearchView
}

/**
 * Search as you type (NAV-005 AC 3 profile): debounce 250 ms, settled query ([Settle]: NFC, JS whitespace collapsed,
 * trimmed, ≤ 200 code units), ≥ 2 characters, loading row after 300 ms, 429 cooldown for `Retry-After`, offline → no
 * request. A response for a superseded query is discarded (generation counter).
 *
 * NAV-011 AC 2–4, 7 (ADR-0012 §3): each settled query is planned by [QueryPlanner] (ADR-0006 rules A–D) into 1 or 2
 * requests: `parallel` sends both at once and merges them ([CombineOutcomes.parallel]); `ifEmpty` sends the second only
 * after an empty 200 for the first. The field always shows what the user typed; planned variants never reach the UI.
 *
 * NAV-011 D140 (ADR-0012 §3, Amendment A2; web parity with `searchController.ts` `settle()`): a recognised coordinate
 * pair becomes [SearchView.Coordinate] with 0 `search` requests. It is planned before the offline and 429-cooldown
 * checks, so the option also shows offline and during a cooldown; retry sends 0 requests for it. Pairs the rule
 * rejects (out of range, decimal comma, …) are text and are searched as typed.
 */
class SearchController(
    private val scope: CoroutineScope,
    private val search: suspend (String, Lang, LatLon) -> SearchOutcome,
    private val lang: () -> Lang,
    private val bias: () -> LatLon,
    private val isOnline: () -> Boolean,
    private val now: () -> Long,
    /** NAV-018 (ADR-0015 §5): the per-operation 429 cooldown, shared by the map-screen and the preview-field instances. */
    private val cooldown: SearchCooldown = SearchCooldown(),
) {
    private val _view = MutableStateFlow<SearchView>(SearchView.Closed)
    val view: StateFlow<SearchView> = _view.asStateFlow()
    private var job: Job? = null
    private var lastSettled: String? = null
    private var generation = 0

    companion object {
        const val DEBOUNCE_MS = 250L
        const val LOADING_DELAY_MS = 300L
        const val MIN_LENGTH = Settle.MIN_LENGTH
        const val MAX_LENGTH = Settle.MAX_LENGTH

        /** NAV-011 / ADR-0012 §1: the ported settle (JS whitespace set, surrogate-safe cap) replaces the NAV-005 one. */
        fun settle(raw: String): String = Settle.settle(raw)

        /**
         * What a settled query becomes (ADR-0012 Amendment A2): [QueryPlan.Coordinate] (0 requests) or the
         * [QueryPlan.Text] requests. A query too short to plan (never reached after [MIN_LENGTH]) is sent as typed.
         */
        fun planFor(q: String): QueryPlan = when (val p = QueryPlanner.plan(q)) {
            is QueryPlan.Coordinate, is QueryPlan.Text -> p
            QueryPlan.Skip -> QueryPlan.Text(q, null, PlanMode.NONE, 'D')
        }
    }

    fun onQuery(raw: String) {
        val q = settle(raw)
        if (q.length < MIN_LENGTH) {
            job?.cancel()
            // Supersedes a query whose request is in flight, so nothing it started can still show a state.
            generation++
            lastSettled = null
            _view.value = SearchView.Closed
            return
        }
        if (q == lastSettled && _view.value !is SearchView.Closed) return
        job?.cancel()
        job = scope.launch {
            delay(DEBOUNCE_MS)
            run(q)
        }
    }

    fun retry() {
        val q = lastSettled ?: return
        if (cooldown.active(now())) return
        job?.cancel()
        job = scope.launch { run(q, force = true) }
    }

    fun close() {
        job?.cancel()
        generation++
        _view.value = SearchView.Closed
        lastSettled = null
    }

    private suspend fun run(q: String, force: Boolean = false) {
        if (!force && q == lastSettled && (_view.value is SearchView.Results || _view.value is SearchView.Coordinate)) return
        lastSettled = q
        // D140 (Amendment A2 step 3): a coordinate needs no request, so it is decided before the offline and cooldown
        // checks. The generation bump discards any older response still on its way.
        val plan = when (val p = planFor(q)) {
            is QueryPlan.Coordinate -> {
                generation++
                _view.value = SearchView.Coordinate(p.point)
                return
            }
            is QueryPlan.Text -> p
            QueryPlan.Skip -> return
        }
        if (!isOnline()) {
            _view.value = SearchView.Offline
            return
        }
        if (cooldown.active(now())) {
            _view.value = SearchView.RateLimited(retryEnabled = false)
            // NAV-018: the wait may come from the other instance (shared cooldown); «Дахин оролдох» enables when it ends.
            val wait = cooldown.until - now()
            scope.launch {
                delay(wait)
                if (_view.value == SearchView.RateLimited(retryEnabled = false)) _view.value = SearchView.RateLimited(retryEnabled = true)
            }
            return
        }
        val g = ++generation
        // The loading row timer is a child of this run: cancelling the run (new query, cleared field, close) cancels it,
        // so «Ачаалж байна…» never outlives its query (NAV-011 minor).
        val outcome = coroutineScope {
            val loading = launch {
                delay(LOADING_DELAY_MS)
                if (g == generation) _view.value = SearchView.Loading
            }
            try {
                fetch(plan, g)
            } finally {
                loading.cancel()
            }
        }
        if (outcome == null || g != generation || lastSettled != q) return
        _view.value = when (outcome) {
            is SearchOutcome.Ok -> {
                val items = outcome.features.map { PlaceDisplay.info(it) }
                if (items.isEmpty()) SearchView.NoResults else SearchView.Results(items)
            }
            SearchOutcome.BadRequest -> SearchView.Error
            SearchOutcome.Offline -> SearchView.Offline
            SearchOutcome.Unavailable -> SearchView.Unavailable
            is SearchOutcome.RateLimited -> {
                cooldown.start(now() + outcome.retryAfterS * 1000L)
                scope.launch {
                    delay(outcome.retryAfterS * 1000L)
                    if (_view.value is SearchView.RateLimited) _view.value = SearchView.RateLimited(retryEnabled = true)
                }
                SearchView.RateLimited(retryEnabled = false)
            }
        }
    }

    /** Sends the planned request(s) of [plan]; `null` when superseded between the two `ifEmpty` requests. */
    private suspend fun fetch(plan: QueryPlan.Text, g: Int): SearchOutcome? {
        val l = lang()
        val b = bias()
        return coroutineScope {
            when {
                plan.secondary != null && plan.mode == PlanMode.PARALLEL -> {
                    val first = async { search(plan.primary, l, b) }
                    val second = async { search(plan.secondary, l, b) }
                    CombineOutcomes.parallel(first.await(), second.await())
                }
                plan.secondary != null && plan.mode == PlanMode.IF_EMPTY -> {
                    val first = search(plan.primary, l, b)
                    if (g != generation) return@coroutineScope null
                    CombineOutcomes.single(if (first is SearchOutcome.Ok && first.features.isEmpty()) search(plan.secondary, l, b) else first)
                }
                else -> CombineOutcomes.single(search(plan.primary, l, b))
            }
        }
    }
}

package mn.navmn.app.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import java.text.Normalizer

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
}

/**
 * Search as you type (AC 3): debounce 250 ms, settled query = NFC, trimmed, spaces collapsed, ≥ 2 characters, one
 * request per settled query (the query as typed; ADR-0006 variants come with NAV-011), loading row after 300 ms,
 * 429 cooldown for `Retry-After`, offline → no request. A response for a superseded query is discarded.
 */
class SearchController(
    private val scope: CoroutineScope,
    private val search: suspend (String, Lang, LatLon) -> SearchOutcome,
    private val lang: () -> Lang,
    private val bias: () -> LatLon,
    private val isOnline: () -> Boolean,
    private val now: () -> Long,
) {
    private val _view = MutableStateFlow<SearchView>(SearchView.Closed)
    val view: StateFlow<SearchView> = _view.asStateFlow()
    private var job: Job? = null
    private var lastSettled: String? = null
    private var cooldownUntil = 0L

    companion object {
        const val DEBOUNCE_MS = 250L
        const val LOADING_DELAY_MS = 300L
        const val MIN_LENGTH = 2
        const val MAX_LENGTH = 200

        fun settle(raw: String): String {
            val s = Normalizer.normalize(raw, Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim()
            return if (s.length <= MAX_LENGTH) s else s.take(MAX_LENGTH).trimEnd()
        }
    }

    fun onQuery(raw: String) {
        val q = settle(raw)
        if (q.length < MIN_LENGTH) {
            job?.cancel()
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
        if (now() < cooldownUntil) return
        job?.cancel()
        job = scope.launch { run(q, force = true) }
    }

    fun close() {
        job?.cancel()
        _view.value = SearchView.Closed
        lastSettled = null
    }

    private suspend fun run(q: String, force: Boolean = false) {
        if (!force && q == lastSettled && _view.value is SearchView.Results) return
        lastSettled = q
        if (!isOnline()) {
            _view.value = SearchView.Offline
            return
        }
        if (now() < cooldownUntil) {
            _view.value = SearchView.RateLimited(retryEnabled = false)
            return
        }
        val loading = scope.launch {
            delay(LOADING_DELAY_MS)
            _view.value = SearchView.Loading
        }
        val outcome = search(q, lang(), bias())
        loading.cancel()
        if (lastSettled != q) return
        _view.value = when (outcome) {
            is SearchOutcome.Ok -> {
                val items = outcome.features.map { PlaceDisplay.info(it) }
                if (items.isEmpty()) SearchView.NoResults else SearchView.Results(items)
            }
            SearchOutcome.BadRequest -> SearchView.Error
            SearchOutcome.Offline -> SearchView.Offline
            SearchOutcome.Unavailable -> SearchView.Unavailable
            is SearchOutcome.RateLimited -> {
                cooldownUntil = now() + outcome.retryAfterS * 1000L
                scope.launch {
                    delay(outcome.retryAfterS * 1000L)
                    if (_view.value is SearchView.RateLimited) _view.value = SearchView.RateLimited(retryEnabled = true)
                }
                SearchView.RateLimited(retryEnabled = false)
            }
        }
    }
}

package mn.navmn.app.search.reverse

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mn.navmn.app.config.AppConfig
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.net.NetworkMonitor
import mn.navmn.app.search.PhotonFeature
import mn.navmn.app.search.SearchClient
import mn.navmn.app.search.SearchOutcome
import mn.navmn.app.search.photonGet
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * `GET {gateway}/v1/reverse` for the coordinate card (NAV-011 AC 8; ADR-0012 §4; openapi `reverse`): the user-chosen
 * point with 6 decimals (not rounded to the bias grid), `lang` from the UI, `limit=1`, `radius=0.5`. Same HTTP stack
 * and outcome classes as search (8 s call timeout, no cache, nothing logged or stored).
 */
class ReverseClient(private val baseUrl: String, http: OkHttpClient, private val isOnline: () -> Boolean) {
    private val client = http.newBuilder().callTimeout(SearchClient.TIMEOUT_S, TimeUnit.SECONDS).cache(null).build()

    companion object {
        const val PATH = "/v1/reverse"
        const val LIMIT = 1
        const val RADIUS_KM = "0.5"

        /** 6 decimals, `Locale.ROOT`, never "-0.000000". */
        fun coord(v: Double): String {
            val s = String.format(Locale.ROOT, "%.6f", v)
            return if (s == "-0.000000") "0.000000" else s
        }
    }

    fun url(p: LatLon, lang: Lang): HttpUrl = (baseUrl.trimEnd('/') + PATH).toHttpUrl().newBuilder()
        .addQueryParameter("lat", coord(p.lat))
        .addQueryParameter("lon", coord(p.lon))
        .addQueryParameter("lang", lang.searchLang)
        .addQueryParameter("limit", LIMIT.toString())
        .addQueryParameter("radius", RADIUS_KM)
        .build()

    suspend fun reverse(p: LatLon, lang: Lang): SearchOutcome {
        if (!isOnline()) return SearchOutcome.Offline
        return photonGet(client, url(p, lang), isOnline)
    }
}

/** The nearest-place area of the coordinate card (screen spec › States › S2 coordinate card; QA hook `reverseState`). */
sealed interface ReverseView {
    val id: String

    data object Pending : ReverseView { override val id = "pending" }
    data object Loading : ReverseView { override val id = "loading" }
    /** The feature is kept, so a language switch only re-renders labels (AC 13, 0 requests). */
    data class Place(val feature: PhotonFeature) : ReverseView { override val id = "place" }
    data object Empty : ReverseView { override val id = "empty" }
    data object Offline : ReverseView { override val id = "offline" }
    data object Unavailable : ReverseView { override val id = "unavailable" }
    data class RateLimited(val retryEnabled: Boolean) : ReverseView { override val id = "rate-limited" }
    data object Error : ReverseView { override val id = "error" }
}

/**
 * One `reverse` request per coordinate card (NAV-011 AC 8–13; ADR-0012 §4; port of web reverseController.ts):
 *  - [open] sends exactly one request; a new [open] or [close] cancels the previous one and bumps the generation, so
 *    an old response never fills a newer card and at most 1 request is in flight (AC 12);
 *  - pending ≤ 300 ms shows nothing, then «Ачаалж байна…» (AC 8);
 *  - offline → 0 requests and the offline state at once; [onNetworkRestored] sends once if the card shows it (AC 11);
 *  - 5xx / network / 8 s → unavailable with one manual retry; 400 → error, no retry; 429 → its own cooldown, retry
 *    disabled for Retry-After and nothing sent automatically afterwards (AC 11).
 * Nothing else calls `reverse` (map pans, zooms, device position and search results never do, AC 12).
 */
class ReverseController(
    private val scope: CoroutineScope,
    private val reverse: suspend (LatLon, Lang) -> SearchOutcome,
    private val lang: () -> Lang,
    private val isOnline: () -> Boolean,
    private val now: () -> Long,
) {
    companion object {
        const val LOADING_DELAY_MS = 300L
    }

    private val _view = MutableStateFlow<ReverseView?>(null)
    /** null = no card open. */
    val view: StateFlow<ReverseView?> = _view.asStateFlow()
    private var point: LatLon? = null
    private var job: Job? = null
    private var generation = 0
    private var cooldownUntil = 0L
    private var cooldownJob: Job? = null

    /** Number of `reverse` requests started (QA visibility, AC 8, 12). */
    var requestsStarted = 0
        private set

    fun open(p: LatLon) {
        point = p
        send()
    }

    fun close() {
        cancel()
        point = null
        _view.value = null
    }

    fun retry() {
        val v = _view.value
        val enabled = v == ReverseView.Unavailable || (v is ReverseView.RateLimited && v.retryEnabled)
        if (!enabled || now() < cooldownUntil) return
        send()
    }

    fun onNetworkRestored() {
        if (_view.value == ReverseView.Offline) send()
    }

    private fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    private fun send() {
        val p = point ?: return
        cancel()
        val g = generation
        if (!isOnline()) {
            _view.value = ReverseView.Offline
            return
        }
        if (now() < cooldownUntil) {
            _view.value = ReverseView.RateLimited(retryEnabled = false)
            return
        }
        _view.value = ReverseView.Pending
        job = scope.launch {
            val loading = launch {
                delay(LOADING_DELAY_MS)
                if (g == generation) _view.value = ReverseView.Loading
            }
            requestsStarted++
            val outcome = reverse(p, lang())
            loading.cancel()
            if (g != generation) return@launch
            _view.value = when (outcome) {
                is SearchOutcome.Ok -> outcome.features.firstOrNull()?.let { ReverseView.Place(it) } ?: ReverseView.Empty
                SearchOutcome.BadRequest -> ReverseView.Error
                SearchOutcome.Offline -> ReverseView.Offline
                SearchOutcome.Unavailable -> ReverseView.Unavailable
                is SearchOutcome.RateLimited -> {
                    val ms = outcome.retryAfterS * 1000L
                    cooldownUntil = now() + ms
                    cooldownJob?.cancel()
                    cooldownJob = scope.launch {
                        delay(ms)
                        val v = _view.value
                        if (v is ReverseView.RateLimited) _view.value = ReverseView.RateLimited(retryEnabled = true)
                    }
                    ReverseView.RateLimited(retryEnabled = false)
                }
            }
        }
    }
}

/** NAV-011: the reverse client on the gateway's OkHttp client (no other host, AC 39). Not part of the NAV-005 modules. */
@Module
@InstallIn(SingletonComponent::class)
object ReverseModule {
    @Provides @Singleton
    fun reverseClient(http: OkHttpClient, network: NetworkMonitor): ReverseClient =
        ReverseClient(AppConfig.gatewayBaseUrl, http) { network.isOnline() }
}

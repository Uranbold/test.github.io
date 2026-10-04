package mn.navmn.app.search

import kotlinx.coroutines.suspendCancellableCoroutine
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.route.RouteClassifier
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed interface SearchOutcome {
    data class Ok(val features: List<PhotonFeature>) : SearchOutcome
    data object BadRequest : SearchOutcome
    data class RateLimited(val retryAfterS: Int) : SearchOutcome
    data object Unavailable : SearchOutcome
    data object Offline : SearchOutcome
}

/**
 * `GET /v1/search` with the NAV-003 request profile (openapi 0.5.1 `search`, NAV-005 Android notes, AC 3): the query
 * as typed, `lang` from the UI, `limit` 8, bias point rounded to 3 decimals (≈ 100 m, privacy), 8 s timeout. Nothing
 * is logged or stored (AC 67).
 */
class SearchClient(private val baseUrl: String, http: OkHttpClient, private val isOnline: () -> Boolean) {
    private val client = http.newBuilder().callTimeout(TIMEOUT_S, TimeUnit.SECONDS).cache(null).build()

    companion object {
        const val PATH = "/v1/search"
        const val LIMIT = 8
        const val TIMEOUT_S = 8L
        fun bias(v: Double): String {
            val s = String.format(Locale.ROOT, "%.3f", v)
            return if (s == "-0.000") "0.000" else s
        }
    }

    fun url(q: String, lang: Lang, bias: LatLon) = (baseUrl.trimEnd('/') + PATH).toHttpUrl().newBuilder()
        .addQueryParameter("q", q)
        .addQueryParameter("lang", lang.searchLang)
        .addQueryParameter("limit", LIMIT.toString())
        .addQueryParameter("lat", bias(bias.lat))
        .addQueryParameter("lon", bias(bias.lon))
        .build()

    suspend fun search(q: String, lang: Lang, bias: LatLon): SearchOutcome {
        if (!isOnline()) return SearchOutcome.Offline
        return photonGet(client, url(q, lang, bias), isOnline)
    }
}

/**
 * One Photon `GET` (search or reverse) with the NAV-003 outcome classes: 200 FeatureCollection → `Ok`, 429 →
 * `RateLimited(Retry-After, 5 s default)`, other 4xx → `BadRequest`, 5xx / unparsable / I/O → `Unavailable`
 * (`Offline` when the network is gone). Cancelling the coroutine cancels the call. Shared by [SearchClient] and the
 * NAV-011 reverse client (ADR-0012 §4).
 */
suspend fun photonGet(client: OkHttpClient, url: HttpUrl, isOnline: () -> Boolean): SearchOutcome {
    val call = client.newCall(Request.Builder().url(url).get().build())
    return suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(if (isOnline()) SearchOutcome.Unavailable else SearchOutcome.Offline)
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = runCatching {
                        response.use { r ->
                            when {
                                r.code == 200 -> PhotonParser.parse(r.body.string())?.let { SearchOutcome.Ok(it) } ?: SearchOutcome.Unavailable
                                r.code == 429 -> SearchOutcome.RateLimited(RouteClassifier.retryAfter(r.header("Retry-After")))
                                r.code in 400..499 -> SearchOutcome.BadRequest
                                else -> SearchOutcome.Unavailable
                            }
                        }
                    }.getOrDefault(SearchOutcome.Unavailable)
                    if (cont.isActive) cont.resume(outcome)
                }
            },
        )
    }
}

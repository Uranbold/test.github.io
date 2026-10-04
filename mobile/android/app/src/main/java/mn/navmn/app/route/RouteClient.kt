package mn.navmn.app.route

import kotlinx.coroutines.suspendCancellableCoroutine
import mn.navmn.app.route.alternatives.PreviewRoutes
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** A started request that can be cancelled (ADR-0009 P10: `Call.cancel()`). */
fun interface Cancelable {
    fun cancel()
}

/** Starts route requests; the reroute policy and the preview only see this. */
interface RouteRequester {
    /** [onResult] is called exactly once (also with [RouteOutcome.Cancelled]), on an OkHttp thread. */
    fun start(request: RouteRequest, generation: Int, onResult: (RouteOutcome) -> Unit): Cancelable
}

/**
 * One OkHttp POST to `{gateway}/v1/route` for the preview and every reroute (ADR-0009 §2). Coordinates only in the
 * body (AC 68); no logging interceptor, no cache (AC 65–67). Call timeout 12 s, connect timeout 5 s.
 */
class RouteClient(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val isOnline: () -> Boolean,
    val processor: RouteProcessor,
) : RouteRequester {
    companion object {
        const val PATH = "/v1/route"
        const val CALL_TIMEOUT_S = 12L
        const val CONNECT_TIMEOUT_S = 5L
        private val JSON = "application/json".toMediaType()

        fun httpClient(base: OkHttpClient = OkHttpClient()): OkHttpClient = base.newBuilder()
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .cache(null)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun buildCall(request: RouteRequest): Call = http.newCall(
        Request.Builder()
            .url(baseUrl.trimEnd('/') + PATH)
            .post(RouteBody.json(request).toRequestBody(JSON))
            .build(),
    )

    override fun start(request: RouteRequest, generation: Int, onResult: (RouteOutcome) -> Unit): Cancelable {
        if (!isOnline()) {
            onResult(RouteOutcome.Offline)
            return Cancelable { }
        }
        val call = buildCall(request)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onResult(if (call.isCanceled()) RouteOutcome.Cancelled else RouteOutcome.Unavailable)
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = try {
                        response.use { handle(it, generation, request.purpose) }
                    } catch (e: IOException) {
                        if (call.isCanceled()) RouteOutcome.Cancelled else RouteOutcome.Unavailable
                    }
                    onResult(if (call.isCanceled()) RouteOutcome.Cancelled else outcome)
                }
            },
        )
        return Cancelable { call.cancel() }
    }

    suspend fun fetch(request: RouteRequest, generation: Int): RouteOutcome = suspendCancellableCoroutine { cont ->
        val c = start(request, generation) { if (cont.isActive) cont.resume(it) }
        cont.invokeOnCancellation { c.cancel() }
    }

    private fun handle(response: Response, generation: Int, purpose: RoutePurpose): RouteOutcome {
        val bytes = response.body.bytes()
        return if (response.code == 200) {
            // NAV-011 (ADR-0012 §5.2–5.3): a preview response may hold up to 3 routes; each is parsed on its own slice.
            if (purpose == RoutePurpose.PREVIEW) PreviewRoutes.process(processor, bytes, generation) else processor.process(bytes, generation)
        } else {
            RouteClassifier.classify(response.code, response.header("Retry-After"), bytes)
        }
    }
}

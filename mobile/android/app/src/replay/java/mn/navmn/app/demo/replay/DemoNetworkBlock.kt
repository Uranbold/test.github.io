package mn.navmn.app.demo.replay

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** The demo build sends nothing (ADR-0016 §7). No URL or query in the message (NAV-005 AC 67). */
class DemoNoNetworkException : IOException("network requests are disabled in the demo build")

/**
 * ADR-0016 §7 (DM-3): the first application interceptor of the app's single OkHttpClient in the demo build. It never
 * calls `chain.proceed()`, so no DNS lookup and no socket happen; the existing outcome code turns the [IOException] into
 * the existing states («Маршрутын үйлчилгээ түр ажиллахгүй байна», «Хайлт түр ажиллахгүй байна», or «Интернэт холболт
 * алга» before any call when there is no validated network). Counts the blocked attempts (in memory only).
 */
class DemoNetworkBlock : Interceptor {
    private val count = AtomicInteger(0)

    val blocked: Int get() = count.get()

    override fun intercept(chain: Interceptor.Chain): Response {
        count.incrementAndGet()
        throw DemoNoNetworkException()
    }
}

/**
 * ADR-0016 §7: MapLibre's HTTP client policy. With bundled tiles ([allowedUrl] null) every request is refused; with
 * `nav.demoTilesUrl` only `GET` to exactly that archive (scheme, host, port, path: the HTTP Range requests) passes.
 */
class TileRequestPolicy(allowedUrl: String?) : Interceptor {
    private val allowed: HttpUrl? = allowedUrl?.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
    private val count = AtomicInteger(0)

    val blocked: Int get() = count.get()

    fun allows(method: String, url: HttpUrl): Boolean {
        val a = allowed ?: return false
        return method == "GET" && url.scheme == "https" && url.scheme == a.scheme && url.host == a.host &&
            url.port == a.port && url.encodedPath == a.encodedPath
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (allows(req.method, req.url)) return chain.proceed(req)
        count.incrementAndGet()
        throw DemoNoNetworkException()
    }
}

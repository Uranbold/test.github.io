package mn.navmn.app.android

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import mn.navmn.app.di.AppModule
import mn.navmn.app.engine.Deviation
import mn.navmn.app.engine.NavSnapshot
import mn.navmn.app.engine.Navigator
import mn.navmn.app.engine.NavigatorFactory
import mn.navmn.app.location.Fix
import mn.navmn.app.net.NetworkMonitor
import mn.navmn.app.route.Cancelable
import mn.navmn.app.route.RouteClient
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.RouteRequester
import mn.navmn.app.search.SearchClient
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Singleton

/**
 * The gateway for Robolectric Activity tests: `POST /v1/route` answers the recorded P1 → P3 response (the preview),
 * everything else 404. Counts route requests (AC 64: 0 during a configuration change).
 */
object FakeGateway : Interceptor {
    val routeRequests = AtomicInteger(0)
    val paths: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** Full request URLs (NAV-011 D140: the typed point appears only in `reverse`, never in a `search` q). */
    val urls: MutableList<okhttp3.HttpUrl> = Collections.synchronizedList(ArrayList())

    fun reset() {
        routeRequests.set(0)
        paths.clear()
        urls.clear()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        paths += req.url.encodedPath
        urls += req.url
        val ok = req.method == "POST" && req.url.encodedPath == "/v1/route"
        if (ok) routeRequests.incrementAndGet()
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(if (ok) 200 else 404)
            .message(if (ok) "OK" else "Not Found")
            .body((if (ok) Fixtures.route("p1-p3-car-mn.json") else ByteArray(0)).toResponseBody("application/json".toMediaType()))
            .build()
    }
}

/** Records route requests (there must be 0 during on-route guidance, AC 15, 19). */
object RecordingRequester : RouteRequester {
    val requests = ArrayList<RouteRequest>()
    override fun start(request: RouteRequest, generation: Int, onResult: (RouteOutcome) -> Unit): Cancelable {
        requests += request
        onResult(RouteOutcome.Unavailable)
        return Cancelable { }
    }
}

/** Robolectric cannot load the native Ferrostar core inside its sandbox: a fixed snapshot stands in. */
object FakeNavigators : NavigatorFactory {
    override fun create(route: mn.navmn.app.route.ParsedRoute): Navigator = object : Navigator {
        private fun snap(f: Fix) = NavSnapshot(0, 304.0, 4_000.0, 270.0, Deviation.NONE, f.latLon, f.bearingDeg, false)
        override fun initial(fix: Fix) = snap(fix)
        override fun update(fix: Fix) = snap(fix)
        override fun close() = Unit
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AppModule::class])
object TestAppModule {
    @Provides @Singleton fun okHttp(): OkHttpClient = OkHttpClient.Builder().addInterceptor(FakeGateway).build()

    @Provides @Singleton
    fun routeClient(http: OkHttpClient, network: NetworkMonitor): RouteClient =
        RouteClient("http://127.0.0.1:9", http, { network.isOnline() }, RouteProcessor(FakeRouteParser()))

    @Provides @Singleton fun requester(): RouteRequester = RecordingRequester

    @Provides @Singleton
    fun search(http: OkHttpClient, network: NetworkMonitor): SearchClient = SearchClient("http://127.0.0.1:9", http, { network.isOnline() })

    @Provides @Singleton fun navigators(): NavigatorFactory = FakeNavigators
}

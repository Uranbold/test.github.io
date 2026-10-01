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
import okhttp3.OkHttpClient
import javax.inject.Singleton

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
    @Provides @Singleton fun okHttp(): OkHttpClient = OkHttpClient()

    @Provides @Singleton
    fun routeClient(http: OkHttpClient, network: NetworkMonitor): RouteClient =
        RouteClient("http://127.0.0.1:9", http, { network.isOnline() }, RouteProcessor(FakeRouteParser()))

    @Provides @Singleton fun requester(): RouteRequester = RecordingRequester

    @Provides @Singleton
    fun search(http: OkHttpClient, network: NetworkMonitor): SearchClient = SearchClient("http://127.0.0.1:9", http, { network.isOnline() })

    @Provides @Singleton fun navigators(): NavigatorFactory = FakeNavigators
}

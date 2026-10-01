package mn.navmn.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import mn.navmn.app.config.AppConfig
import mn.navmn.app.engine.FerrostarNavigatorFactory
import mn.navmn.app.engine.FerrostarRouteParser
import mn.navmn.app.engine.NavigatorFactory
import mn.navmn.app.net.NetworkMonitor
import mn.navmn.app.route.RouteClient
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequester
import mn.navmn.app.search.SearchClient
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** One OkHttp client for the gateway only (AC 65): no logging interceptor, no cache, no other host. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder().cache(null).build()

    @Provides @Singleton
    fun routeClient(http: OkHttpClient, network: NetworkMonitor): RouteClient =
        RouteClient(AppConfig.gatewayBaseUrl, RouteClient.httpClient(http), { network.isOnline() }, RouteProcessor(FerrostarRouteParser()))

    @Provides @Singleton
    fun routeRequester(client: RouteClient): RouteRequester = client

    @Provides @Singleton
    fun searchClient(http: OkHttpClient, network: NetworkMonitor): SearchClient =
        SearchClient(AppConfig.gatewayBaseUrl, http, { network.isOnline() })

    @Provides @Singleton
    fun navigators(): NavigatorFactory = FerrostarNavigatorFactory()
}

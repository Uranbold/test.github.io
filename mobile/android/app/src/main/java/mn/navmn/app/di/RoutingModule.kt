package mn.navmn.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import mn.navmn.app.route.RouteClient
import mn.navmn.app.routing.FallbackRouteRequester
import mn.navmn.app.routing.OnDeviceRouting
import javax.inject.Singleton

/**
 * NAV-021 R7 (ADR-0017 §5): the online-first route transport. Kept apart from [AppModule] so the Robolectric tests,
 * which replace [AppModule] with a fake gateway, still get the same composition around their [RouteClient]. Without an
 * installed routing file it is a pass-through to [RouteClient] (AC 13, 30).
 */
@Module
@InstallIn(SingletonComponent::class)
object RoutingModule {
    @Provides @Singleton
    fun fallbackRequester(client: RouteClient, routing: OnDeviceRouting): FallbackRouteRequester = routing.requester(client)
}

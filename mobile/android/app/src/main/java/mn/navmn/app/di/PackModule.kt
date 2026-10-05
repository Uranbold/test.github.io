package mn.navmn.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import mn.navmn.app.pack.PackClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * NAV-022: the HTTP client for the two `packs` operations only (its own client: long transfers, no shared timeouts,
 * no cache; requests carry no identifiers, AC 42). Robolectric tests replace this module with a local fake so no test
 * ever reaches a real host.
 */
@Module
@InstallIn(SingletonComponent::class)
object PackModule {
    @Provides @Singleton @PackClient
    fun packHttp(): OkHttpClient = OkHttpClient.Builder()
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()
}

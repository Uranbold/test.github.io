package mn.navmn.app.android

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import mn.navmn.app.di.PackModule
import mn.navmn.app.pack.PackClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Collections
import javax.inject.Singleton

/**
 * NAV-022 in Robolectric Activity tests: the pack client never reaches a host (the debug default would be the shared dev
 * stack at 127.0.0.1:8080). Every pack request is recorded and answered 404, so the feature stays inert ("no pack
 * published") and the gateway request counts of the other stories' tests are unchanged.
 */
object FakePackHost {
    val paths: MutableList<String> = Collections.synchronizedList(ArrayList())

    fun reset() = paths.clear()
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [PackModule::class])
object TestPackModule {
    @Provides @Singleton @PackClient
    fun packHttp(): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        FakePackHost.paths += req.url.encodedPath
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").body(ByteArray(0).toResponseBody()).build()
    }.build()
}

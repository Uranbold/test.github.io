package mn.navmn.app.config

import mn.navmn.app.BuildConfig

/** Gateway URLs (ADR-0009 §11, A7): base URL from the build, paths from openapi.yaml. */
object AppConfig {
    val gatewayBaseUrl: String get() = BuildConfig.GATEWAY_BASE_URL.trimEnd('/')
    const val TILES_PATH = "/tiles/basemap.pmtiles"

    fun pmtilesUrl(base: String = gatewayBaseUrl): String = "pmtiles://" + base.trimEnd('/') + TILES_PATH
}

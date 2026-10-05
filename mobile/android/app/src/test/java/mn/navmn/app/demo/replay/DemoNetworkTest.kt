package mn.navmn.app.demo.replay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.map.MapStyle
import mn.navmn.app.preview.PreviewController
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.qa.repoFile
import mn.navmn.app.route.RouteClient
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RoutePurpose
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.search.SearchClient
import mn.navmn.app.search.SearchOutcome
import mn.navmn.app.search.reverse.ReverseClient
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * NAV-019 AC 11, 31, 33 (ADR-0016 §7): the demo gateway. The app's real clients behind [DemoNetworkBlock] send 0
 * requests (0 DNS lookups, 0 connects) and produce the existing states: «Маршрутын үйлчилгээ түр ажиллахгүй байна» /
 * «Хайлт түр ажиллахгүй байна» with a validated network, «Интернэт холболт алга» without; «Дахин оролдох» again sends 0.
 * MapLibre's policy refuses everything with bundled tiles and allows only the archive URL in URL mode. The resolved
 * demo style names no web host.
 */
class DemoNetworkTest {
    private lateinit var server: MockWebServer
    private val block = DemoNetworkBlock()
    private val dns = AtomicInteger(0)
    private val connects = AtomicInteger(0)
    private lateinit var http: OkHttpClient
    private val p1 = LatLon(47.9189, 106.9176)
    private val p3 = LatLon(47.8858, 106.9173)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder()
            .addInterceptor(block)
            .eventListener(object : EventListener() {
                override fun dnsStart(call: Call, domainName: String) {
                    dns.incrementAndGet()
                }

                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    connects.incrementAndGet()
                }
            })
            .build()
    }

    @After
    fun tearDown() = server.close()

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun assertNothingLeft() {
        assertEquals("requests at the server", 0, server.requestCount)
        assertEquals("DNS lookups", 0, dns.get())
        assertEquals("connects", 0, connects.get())
    }

    @Test
    fun routeSearchAndReverseShowTheExistingStatesWithZeroRequests() = runBlocking {
        var online = true
        val route = RouteClient(base(), RouteClient.httpClient(http), { online }, RouteProcessor(FakeRouteParser()))
        val search = SearchClient(base(), http) { online }
        val reverse = ReverseClient(base(), http) { online }
        val req = RouteRequest(p1, p3, TravelMode.CAR, false, Lang.MN, purpose = RoutePurpose.PREVIEW)
        assertEquals(RouteOutcome.Unavailable, route.fetch(req, 0))
        assertEquals(RouteOutcome.Unavailable, route.fetch(req.copy(purpose = RoutePurpose.REROUTE, heading = 90), 1))
        assertEquals(SearchOutcome.Unavailable, search.search("Хаан банк", Lang.MN, p1))
        assertEquals(SearchOutcome.Unavailable, reverse.reverse(p3, Lang.MN))
        assertEquals("every attempt was blocked in-process", 4, block.blocked)
        online = false
        assertEquals(RouteOutcome.Offline, route.fetch(req, 0))
        assertEquals(SearchOutcome.Offline, search.search("Хаан банк", Lang.MN, p1))
        assertEquals(SearchOutcome.Offline, reverse.reverse(p3, Lang.MN))
        assertEquals("offline: not even an attempt", 4, block.blocked)
        assertNothingLeft()
    }

    /** AC 9–11: the recorded preview opens with 0 requests; a control that needs a new route shows the existing state. */
    @Test
    fun recordedPreviewThenModeChangeAndRetryStayAtZeroRequests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val route = RouteClient(base(), RouteClient.httpClient(http), { true }, RouteProcessor(FakeRouteParser()))
            val preview = PreviewController(scope, { route.fetch(it, 0) }, { Lang.MN }, { true }, { System.nanoTime() / 1_000_000 }, { System.currentTimeMillis() })
            val ok = RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok
            val origin = mn.navmn.app.preview.points.RoutePoint.Place(ok.route.plan.geometry.first(), "Сүхбаатарын талбай")
            val destination = mn.navmn.app.preview.points.RoutePoint.Place(ok.route.plan.end, "Зайсан Голден Вилл")
            preview.showRoute(ok, origin, destination, TravelMode.CAR)
            val s = preview.state.value!!
            assertTrue(s.result is PreviewResult.Route)
            assertTrue("AC 10: «Эхлэх» enabled from the chosen start", s.canStart)
            assertFalse("AC 10: no O1 hint", s.showStartHint)
            assertEquals("AC 9: 0 route requests", 0, preview.requestsStarted)
            preview.setMode(TravelMode.WALK)
            repeat(50) { if (preview.state.value?.result != PreviewResult.Unavailable) delay(50) }
            assertEquals("AC 11/31: «Маршрутын үйлчилгээ түр ажиллахгүй байна»", PreviewResult.Unavailable, preview.state.value?.result)
            assertFalse(preview.state.value!!.canStart)
            preview.retry()
            repeat(50) { if (preview.requestsStarted < 2 || preview.state.value?.result != PreviewResult.Unavailable) delay(50) }
            assertEquals(PreviewResult.Unavailable, preview.state.value?.result)
            assertNothingLeft()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun mapLibrePolicyRefusesEverythingWithBundledTilesAndAllowsOnlyTheArchiveUrl() {
        val file = TileRequestPolicy(null)
        assertFalse(file.allows("GET", "https://127.0.0.1/a.pmtiles".toHttpUrl()))
        val url = TileRequestPolicy("https://127.0.0.1/tiles/ub.pmtiles")
        assertTrue(url.allows("GET", "https://127.0.0.1/tiles/ub.pmtiles".toHttpUrl()))
        assertFalse(url.allows("POST", "https://127.0.0.1/tiles/ub.pmtiles".toHttpUrl()))
        assertFalse(url.allows("GET", "https://127.0.0.1/tiles/other.pmtiles".toHttpUrl()))
        assertFalse(url.allows("GET", "https://localhost/tiles/ub.pmtiles".toHttpUrl()))
        assertFalse(url.allows("GET", "http://127.0.0.1/tiles/ub.pmtiles".toHttpUrl()))
        // Through a real client: refused in-process, nothing reaches the server.
        val client = OkHttpClient.Builder().addInterceptor(file).build()
        assertTrue(runCatching { client.newCall(Request.Builder().url(server.url("/x.pmtiles")).build()).execute() }.exceptionOrNull() is DemoNoNetworkException)
        assertEquals(1, file.blocked)
        assertEquals(0, server.requestCount)
    }

    /** AC 33, 35: the style with the bundled archive has no web URL at all (glyphs and sprites are asset://). */
    @Test
    fun resolvedDemoStyleNamesNoWebHost() {
        val local = TileArchiveCopier.pmtilesUrl(java.io.File("/data/user/0/mn.navmn.app.demo/no_backup/demo-tiles/basemap-1.pmtiles"))
        assertEquals("pmtiles://file:///data/user/0/mn.navmn.app.demo/no_backup/demo-tiles/basemap-1.pmtiles", local)
        val web = Regex("https?://")
        for (theme in listOf("day", "night")) {
            val style = repoFile("mobile/android/app/src/main/assets/style/basemap-$theme.json").readText()
            assertFalse("$theme: web URL in the file-mode style", web.containsMatchIn(MapStyle.resolve(style, local)))
            val urlMode = MapStyle.resolve(style, "pmtiles://https://127.0.0.1/ub.pmtiles")
            assertEquals("$theme: only the archive URL in URL mode", listOf("https://127.0.0.1/ub.pmtiles"), Regex("https?://[^\"]+").findAll(urlMode).map { it.value }.toList())
        }
    }
}

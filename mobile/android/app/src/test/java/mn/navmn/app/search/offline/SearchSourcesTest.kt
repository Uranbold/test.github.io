package mn.navmn.app.search.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.routing.FakeRoutingClock
import mn.navmn.app.search.PhotonFeature
import mn.navmn.app.search.SearchClient
import mn.navmn.app.search.SearchOutcome
import mn.navmn.app.search.reverse.ReverseClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * NAV-023 section C (AC 11–15, 26; D163, D199) with a fake clock, a mock gateway and a fake on-device engine.
 * "0 requests" is the mock server's request count; "0 on-device queries" is the fake engine's count. The 3.0 s budget
 * is measured on the fake clock, as NAV-021 AC 9.
 */
class SearchSourcesTest {
    private lateinit var server: MockWebServer
    private val clock = FakeRoutingClock()
    private var validated = true
    private val logLines = Collections.synchronizedList(ArrayList<String>())
    private val p1 = LatLon(47.9189, 106.9176)
    private val emptyOk = """{"type":"FeatureCollection","features":[]}"""
    private val oneOk = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[106.9176,47.9189]},"properties":{"osm_type":"W","osm_id":30,"osm_key":"place","osm_value":"square","name":"Сүхбаатарын талбай","countrycode":"MN"}}]}"""

    private class FakeDevice : OnDeviceSearch {
        var installed = true
        var fail = false
        val searches = AtomicInteger()
        @Volatile var lastBias: LatLon? = null
        val reverses = AtomicInteger()
        val feature = PhotonFeature(LatLon(47.9189, 106.9176), mapOf("osm_type" to "N", "osm_id" to "31", "osm_key" to "historic", "osm_value" to "memorial", "name" to "Сүхбаатарын хөшөө"))
        override fun available() = installed
        override suspend fun search(q: String, lang: Lang, bias: LatLon): SearchOutcome {
            searches.incrementAndGet()
            lastBias = bias
            return if (fail) SearchOutcome.Unavailable else SearchOutcome.Ok(listOf(feature), onDevice = true)
        }
        override suspend fun reverse(p: LatLon, lang: Lang): SearchOutcome {
            reverses.incrementAndGet()
            return if (fail) SearchOutcome.Unavailable else SearchOutcome.Ok(listOf(feature), onDevice = true)
        }
    }

    private val device = FakeDevice()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun sources(base: String = server.url("/").toString()): SearchSources {
        val http = OkHttpClient()
        return SearchSources(SearchClient(base, http) { validated }, ReverseClient(base, http) { validated }, device, { validated }, clock, { logLines += it })
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun SearchSources.searchAsync(q: String = "Сүхбаатар"): Deferred<SearchOutcome> = scope.async { search(q, Lang.MN, p1) }
    private fun SearchSources.reverseAsync(): Deferred<SearchOutcome> = scope.async { reverse(p1, Lang.MN) }
    private fun <T> Deferred<T>.get(): T = runBlocking { withTimeout(15_000) { await() } }

    /** Waits until the request reached the mock gateway (real time; the fake clock does not move). */
    private fun awaitRequest() = requireNotNull(server.takeRequest(10, TimeUnit.SECONDS)) { "no request reached the gateway" }

    @Test
    fun withoutASearchFileEverythingIsAsBefore() = runBlocking {
        device.installed = false
        server.enqueue(MockResponse.Builder().code(200).body(oneOk).build())
        val s = sources()
        assertFalse(s.onDeviceAvailable())
        val out = s.search("Сүхбаатар", Lang.MN, p1)
        assertEquals(SearchOutcome.Ok::class, out::class)
        assertFalse((out as SearchOutcome.Ok).onDevice)
        // AC 26: without network the NAV-011 offline state, 0 requests, 0 on-device queries.
        validated = false
        assertEquals(SearchOutcome.Offline, s.search("Сүхбаатар", Lang.MN, p1))
        assertEquals(SearchOutcome.Offline, s.reverse(p1, Lang.MN))
        assertEquals(1, server.requestCount)
        assertEquals(0, device.searches.get() + device.reverses.get())
    }

    @Test
    fun noValidatedNetworkAnswersOnTheDeviceAtOnceWithZeroRequests() = runBlocking {
        validated = false
        val s = sources()
        val a = s.search("Сүхбаатар", Lang.MN, p1) as SearchOutcome.Ok
        assertTrue(a.onDevice)
        val r = s.reverse(p1, Lang.MN) as SearchOutcome.Ok
        assertTrue(r.onDevice)
        assertEquals(0, server.requestCount)
        assertEquals(1, device.searches.get())
        assertEquals(1, device.reverses.get())
    }

    @Test
    fun noHeadersWithinExactlyThreeSecondsFallsBackThenSticksFor60s() {
        server.enqueue(MockResponse.Builder().code(200).body(oneOk).headersDelay(10, TimeUnit.SECONDS).build())
        val s = sources()
        val d = s.searchAsync()
        awaitRequest()
        clock.advanceTo(2_999)
        Thread.sleep(150)
        assertFalse("answered before 3.0 s", d.isCompleted)
        assertEquals(0, device.searches.get())
        clock.advanceTo(3_000)
        val out = d.get() as SearchOutcome.Ok
        assertTrue(out.onDevice)
        assertEquals(1, device.searches.get())
        assertTrue(logLines.any { it.contains("header budget (3000 ms)") })
        // AC 14: for 60 s searches AND reverses go straight to the device, 0 requests.
        clock.advanceTo(30_000)
        assertTrue((s.searchAsync().get() as SearchOutcome.Ok).onDevice)
        assertTrue((s.reverseAsync().get() as SearchOutcome.Ok).onDevice)
        assertEquals(1, server.requestCount)
        // After 60 s online first again.
        clock.advanceTo(63_001)
        server.enqueue(MockResponse.Builder().code(200).body(oneOk).build())
        val again = s.searchAsync().get() as SearchOutcome.Ok
        assertFalse(again.onDevice)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aNewlyValidatedNetworkEndsTheStickiness() {
        server.enqueue(MockResponse.Builder().code(200).body(oneOk).headersDelay(10, TimeUnit.SECONDS).build())
        val s = sources()
        val d = s.searchAsync()
        awaitRequest()
        clock.advanceTo(3_000)
        assertTrue((d.get() as SearchOutcome.Ok).onDevice)
        s.onNewlyValidated()
        server.enqueue(MockResponse.Builder().code(200).body(oneOk).build())
        assertFalse((s.searchAsync().get() as SearchOutcome.Ok).onDevice)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun connectionFailureBeforeHeadersFallsBackAndSticks() {
        // Nothing listens there: the connection fails before any header.
        val dead = MockWebServer().apply { start() }
        val base = dead.url("/").toString()
        dead.close()
        val s = sources(base)
        val out = s.searchAsync().get() as SearchOutcome.Ok
        assertTrue(out.onDevice)
        assertTrue(logLines.any { it.contains("connection failure") })
        assertTrue((s.reverseAsync().get() as SearchOutcome.Ok).onDevice)
        assertEquals(1, device.reverses.get())
    }

    @Test
    fun gateway5xxBeforeHeadersFallsBackWithoutStickiness() {
        for (code in listOf(502, 503, 504)) {
            server.enqueue(MockResponse.Builder().code(code).build())
            assertTrue("$code", (sources().searchAsync().get() as SearchOutcome.Ok).onDevice)
        }
        val s = sources()
        server.enqueue(MockResponse.Builder().code(503).build())
        assertTrue((s.searchAsync().get() as SearchOutcome.Ok).onDevice)
        server.enqueue(MockResponse.Builder().code(200).body(oneOk).build())
        assertFalse("503 is not sticky", (s.searchAsync().get() as SearchOutcome.Ok).onDevice)
    }

    @Test
    fun rateLimitedAnswersOnTheDeviceDuringRetryAfterPerOperation() {
        val s = sources()
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "7").build())
        assertTrue((s.searchAsync().get() as SearchOutcome.Ok).onDevice)
        assertEquals(1, server.requestCount)
        // AC 15: within the 7 s, 0 online `search` requests; «Түр хүлээгээд…» is never the outcome.
        clock.advanceTo(6_999)
        val during = s.searchAsync().get()
        assertTrue(during is SearchOutcome.Ok && during.onDevice)
        assertEquals(1, server.requestCount)
        // The `reverse` operation has its own wait (ADR-0006 §3): still online first.
        server.enqueue(MockResponse.Builder().code(200).body(oneOk).build())
        assertFalse((s.reverseAsync().get() as SearchOutcome.Ok).onDevice)
        assertEquals(2, server.requestCount)
        clock.advanceTo(7_000)
        server.enqueue(MockResponse.Builder().code(200).body(oneOk).build())
        assertFalse((s.searchAsync().get() as SearchOutcome.Ok).onDevice)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun authoritativeAnswersAreShownWithZeroOnDeviceQueries() {
        val s = sources()
        // AC 13: a 200 with 0 results is «Илэрц олдсонгүй» from the gateway; a 400 is the NAV-011 error.
        server.enqueue(MockResponse.Builder().code(200).body(emptyOk).build())
        assertEquals(SearchOutcome.Ok(emptyList()), s.searchAsync().get())
        server.enqueue(MockResponse.Builder().code(400).body("""{"code":"bad_request","message":"q"}""").build())
        assertEquals(SearchOutcome.BadRequest, s.searchAsync().get())
        // Headers in time and a slow body: still the gateway's answer (NAV-011 8 s call timeout).
        server.enqueue(MockResponse.Builder().code(200).body(oneOk).bodyDelay(500, TimeUnit.MILLISECONDS).build())
        val slow = s.searchAsync()
        repeat(3) { awaitRequest() }
        Thread.sleep(250) // headers are on their way at once; only the body waits
        clock.advanceTo(5_000) // the budget timer was cancelled when headers arrived
        assertFalse((slow.get() as SearchOutcome.Ok).onDevice)
        assertEquals(0, device.searches.get())
    }

    /** AC 16: the device gets the bias point the gateway would get (3 decimals). */
    @Test
    fun theDeviceUsesTheOnlineBiasPoint() = runBlocking {
        validated = false
        sources().search("Гандан", Lang.MN, LatLon(47.918912, 106.917634))
        assertEquals(LatLon(47.919, 106.918), device.lastBias)
    }

    @Test
    fun aFailingSearchFileIsTheUnavailableStateNotACrash() {
        validated = false
        device.fail = true
        val s = sources()
        assertEquals(SearchOutcome.Unavailable, s.searchAsync().get())
        assertEquals(SearchOutcome.Unavailable, s.reverseAsync().get())
    }

    @Test
    fun logLinesCarryNoQueryTextAndNoCoordinates() {
        server.enqueue(MockResponse.Builder().code(503).build())
        val s = sources()
        s.searchAsync("Улсын их дэлгүүр").get()
        validated = false
        s.reverseAsync().get()
        val coord = Regex("-?\\d{1,3}\\.\\d{4,}")
        assertTrue(logLines.isNotEmpty())
        for (l in logLines) {
            assertFalse(l, l.contains("Улсын") || l.contains("дэлгүүр"))
            assertFalse(l, coord.containsMatchIn(l))
        }
    }
}

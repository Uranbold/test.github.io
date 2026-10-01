package mn.navmn.app.search

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-005 AC 3 search profile and states (NAV-003 rules, query as typed). */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchTest {
    private val ok = """{"type":"FeatureCollection","features":[
        {"type":"Feature","geometry":{"type":"Point","coordinates":[106.9176,47.9189]},"properties":{"osm_key":"place","osm_value":"square","name":"Сүхбаатарын талбай","district":"Сүхбаатар дүүрэг","city":"Улаанбаатар"}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[106.9,47.9]},"properties":{"osm_key":"amenity","osm_value":"fuel","street":"Энхтайваны өргөн чөлөө","housenumber":"5"}}]}"""

    @Test
    fun requestProfileQueryAsTypedBiasThreeDecimals() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse.Builder().code(200).body(ok).build())
        val client = SearchClient(server.url("/").toString(), OkHttpClient(), { true })
        val out = client.search("Sukhbaatar ", Lang.MN, LatLon(47.918912, 106.917634))
        assertTrue(out is SearchOutcome.Ok)
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/v1/search", r.url.encodedPath)
        assertEquals("Sukhbaatar ", r.url.queryParameter("q"))
        assertEquals("mn", r.url.queryParameter("lang"))
        assertEquals("8", r.url.queryParameter("limit"))
        assertEquals("47.919", r.url.queryParameter("lat"))
        assertEquals("106.918", r.url.queryParameter("lon"))
        assertEquals(setOf("q", "lang", "limit", "lat", "lon"), r.url.queryParameterNames)
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "7").build())
        assertEquals(SearchOutcome.RateLimited(7), client.search("ab", Lang.EN, LatLon(0.0, 0.0)))
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(SearchOutcome.Unavailable, client.search("ab", Lang.EN, LatLon(0.0, 0.0)))
        server.close()
    }

    @Test
    fun displayRules() {
        val features = PhotonParser.parse(ok)!!
        val a = PlaceDisplay.info(features[0])
        assertEquals("Сүхбаатарын талбай", a.name)
        assertEquals(StringKey.PLACE_TYPE_SQUARE, a.type)
        assertEquals("Сүхбаатар дүүрэг, Улаанбаатар", a.context)
        val b = PlaceDisplay.info(features[1])
        assertEquals("Энхтайваны өргөн чөлөө 5", b.name)
        assertEquals(StringKey.PLACE_TYPE_FUEL, b.type)
        assertEquals(null, PhotonParser.parse("""{"type":"Nope"}"""))
    }

    private fun TestScope.controller(online: Boolean = true, outcome: SearchOutcome = SearchOutcome.Ok(PhotonParser.parse(ok)!!)): Pair<SearchController, MutableList<String>> {
        val calls = ArrayList<String>()
        val c = SearchController(this, { q, _, _ -> calls += q; outcome }, { Lang.MN }, { LatLon(47.9, 106.9) }, { online }, { testScheduler.currentTime })
        return c to calls
    }

    @Test
    fun debounce250AndOneRequestPerSettledQuery() = runTest {
        val (c, calls) = controller()
        c.onQuery("Сү")
        advanceTimeBy(100)
        c.onQuery("Сүх")
        advanceTimeBy(100)
        c.onQuery("Сүхб  ")
        advanceTimeBy(249)
        assertEquals(0, calls.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("Сүхб"), calls)
        assertTrue(c.view.value is SearchView.Results)
        c.onQuery("Сүхб")
        advanceTimeBy(500)
        assertEquals(1, calls.size)
        c.onQuery("С")
        advanceTimeBy(500)
        assertEquals(SearchView.Closed, c.view.value)
    }

    @Test
    fun offlineNoResultsAndRateLimit() = runTest {
        val (off, offCalls) = controller(online = false)
        off.onQuery("Зайсан")
        advanceTimeBy(300)
        assertEquals(SearchView.Offline, off.view.value)
        assertEquals(0, offCalls.size)
        val (none, _) = controller(outcome = SearchOutcome.Ok(emptyList()))
        none.onQuery("Зайсан")
        advanceTimeBy(300)
        assertEquals(SearchView.NoResults, none.view.value)
        val (limited, calls) = controller(outcome = SearchOutcome.RateLimited(3))
        limited.onQuery("Зайсан")
        advanceTimeBy(300)
        assertEquals(SearchView.RateLimited(false), limited.view.value)
        limited.retry()
        advanceTimeBy(100)
        assertEquals(1, calls.size)
        advanceTimeBy(3_000)
        assertEquals(SearchView.RateLimited(true), limited.view.value)
    }
}

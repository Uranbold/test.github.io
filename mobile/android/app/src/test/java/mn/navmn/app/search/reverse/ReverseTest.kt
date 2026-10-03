package mn.navmn.app.search.reverse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.search.PhotonFeature
import mn.navmn.app.search.SearchOutcome
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-011 AC 8–13 (ADR-0012 §4): one `reverse` per coordinate card. Replaces NAV-005 AC 4 "0 reverse requests". */
@OptIn(ExperimentalCoroutinesApi::class)
class ReverseTest {
    private val p1 = LatLon(47.9189123, 106.9176456)
    private val place = PhotonFeature(LatLon(47.919, 106.918), mapOf("osm_key" to "place", "osm_value" to "square", "name" to "Square"))

    @Test
    fun requestProfile() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse.Builder().code(200).body("""{"type":"FeatureCollection","features":[]}""").build())
        val client = ReverseClient(server.url("/").toString(), OkHttpClient(), { true })
        assertEquals(SearchOutcome.Ok(emptyList()), client.reverse(p1, Lang.EN))
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/v1/reverse", r.url.encodedPath)
        assertEquals("47.918912", r.url.queryParameter("lat"))
        assertEquals("106.917646", r.url.queryParameter("lon"))
        assertEquals("en", r.url.queryParameter("lang"))
        assertEquals("1", r.url.queryParameter("limit"))
        assertEquals("0.5", r.url.queryParameter("radius"))
        assertEquals(setOf("lat", "lon", "lang", "limit", "radius"), r.url.queryParameterNames)
        server.enqueue(MockResponse.Builder().code(429).build())
        assertEquals(SearchOutcome.RateLimited(5), client.reverse(p1, Lang.MN))
        server.enqueue(MockResponse.Builder().code(400).build())
        assertEquals(SearchOutcome.BadRequest, client.reverse(p1, Lang.MN))
        server.close()
        assertEquals("0.000000", ReverseClient.coord(-0.0000001))
    }

    private class Env(val scope: TestScope, var online: Boolean = true, var answer: suspend (LatLon) -> SearchOutcome) {
        val calls = ArrayList<LatLon>()
        val c = ReverseController(scope, { p, _ -> calls += p; answer(p) }, { Lang.MN }, { online }, { scope.testScheduler.currentTime })
    }

    @Test
    fun oneRequestPerCardLoadingAfter300msPlaceAndEmpty() = runTest {
        val gate = CompletableDeferred<SearchOutcome>()
        val e = Env(this, answer = { gate.await() })
        e.c.open(p1)
        runCurrent()
        assertEquals(1, e.calls.size)
        assertEquals(ReverseView.Pending, e.c.view.value)
        advanceTimeBy(301)
        assertEquals(ReverseView.Loading, e.c.view.value)
        gate.complete(SearchOutcome.Ok(listOf(place)))
        runCurrent()
        assertEquals(ReverseView.Place(place), e.c.view.value)
        advanceTimeBy(60_000)
        assertEquals("nothing else sends reverse", 1, e.calls.size)

        val empty = Env(this, answer = { SearchOutcome.Ok(emptyList()) })
        empty.c.open(p1)
        runCurrent()
        assertEquals(ReverseView.Empty, empty.c.view.value)
    }

    @Test
    fun offlineSendsNothingThenOnceWhenTheNetworkReturns() = runTest {
        val e = Env(this, online = false, answer = { SearchOutcome.Ok(listOf(place)) })
        e.c.open(p1)
        runCurrent()
        assertEquals(ReverseView.Offline, e.c.view.value)
        assertEquals(0, e.calls.size)
        e.online = true
        e.c.onNetworkRestored()
        e.c.onNetworkRestored()
        runCurrent()
        assertEquals(1, e.calls.size)
        assertEquals(ReverseView.Place(place), e.c.view.value)
    }

    @Test
    fun failuresRetryRules() = runTest {
        var n = 0
        val e = Env(this, answer = { if (n++ == 0) SearchOutcome.Unavailable else SearchOutcome.Ok(listOf(place)) })
        e.c.open(p1)
        runCurrent()
        assertEquals(ReverseView.Unavailable, e.c.view.value)
        advanceTimeBy(30_000)
        assertEquals("nothing retries automatically", 1, e.calls.size)
        e.c.retry()
        runCurrent()
        assertEquals(2, e.calls.size)
        assertEquals(ReverseView.Place(place), e.c.view.value)

        val bad = Env(this, answer = { SearchOutcome.BadRequest })
        bad.c.open(p1)
        runCurrent()
        assertEquals(ReverseView.Error, bad.c.view.value)
        bad.c.retry()
        runCurrent()
        assertEquals("400: no retry", 1, bad.calls.size)

        val limited = Env(this, answer = { SearchOutcome.RateLimited(6) })
        limited.c.open(p1)
        runCurrent()
        assertEquals(ReverseView.RateLimited(false), limited.c.view.value)
        limited.c.retry()
        limited.c.open(LatLon(47.92, 106.92)) // another card during Retry-After: 0 requests
        advanceTimeBy(5_900)
        assertEquals(1, limited.calls.size)
        assertEquals(ReverseView.RateLimited(false), limited.c.view.value)
        advanceTimeBy(200)
        assertEquals(ReverseView.RateLimited(true), limited.c.view.value)
        advanceTimeBy(60_000)
        assertEquals("nothing is sent after N s until the user acts", 1, limited.calls.size)
    }

    @Test
    fun aNewCardCancelsTheOldRequestAndCloseIgnoresItsResponse() = runTest {
        val first = CompletableDeferred<SearchOutcome>()
        val second = CompletableDeferred<SearchOutcome>()
        var n = 0
        val e = Env(this, answer = { if (n++ == 0) first.await() else second.await() })
        e.c.open(p1)
        runCurrent()
        val p2 = LatLon(47.93, 106.93)
        e.c.open(p2)
        runCurrent()
        assertEquals(2, e.calls.size)
        first.complete(SearchOutcome.Ok(listOf(place)))
        runCurrent()
        assertTrue("the old response never fills the new card", e.c.view.value !is ReverseView.Place)
        e.c.close()
        second.complete(SearchOutcome.Ok(listOf(place)))
        runCurrent()
        assertEquals(null, e.c.view.value)
    }
}

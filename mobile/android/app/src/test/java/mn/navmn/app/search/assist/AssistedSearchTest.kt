package mn.navmn.app.search.assist

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
import mn.navmn.app.search.SearchClient
import mn.navmn.app.search.SearchController
import mn.navmn.app.search.SearchCooldown
import mn.navmn.app.search.SearchOutcome
import mn.navmn.app.search.SearchView
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * NAV-011 AC 2–4, 7 (ADR-0012 §3): the NAV-005 SearchController with the ported plan. Replaces the NAV-005 AC 3 "query
 * as typed" behaviour for Latin, Russian-layout and abbreviation queries; everything else of the AC 3 profile is
 * unchanged (debounce, lang, bias, 8 s timeout, 429, offline, stale responses).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistedSearchTest {
    private fun f(key: String, cc: String = "MN") = PhotonFeature(LatLon(47.9, 106.9), mapOf("osm_type" to key.take(1), "osm_id" to key.drop(1), "countrycode" to cc, "name" to key))

    private fun TestScope.controller(
        online: () -> Boolean = { true },
        cooldown: SearchCooldown = SearchCooldown(),
        answer: suspend (String) -> SearchOutcome,
    ): Pair<SearchController, MutableList<String>> {
        val calls = Collections.synchronizedList(ArrayList<String>())
        val c = SearchController(this, { q, _, _ -> calls += q; answer(q) }, { Lang.MN }, { LatLon(47.9, 106.9) }, online, { testScheduler.currentTime }, cooldown)
        return c to calls
    }

    private fun TestScope.settle(c: SearchController, q: String) {
        c.onQuery(q)
        advanceTimeBy(SearchController.DEBOUNCE_MS + 1)
        runCurrent()
    }

    @Test
    fun latinQuerySendsTypedAndTransliteratedInParallelAndMerges() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (c, calls) = controller { q ->
            gate.await()
            if (q == "Sukhbaatar") SearchOutcome.Ok(listOf(f("N1", "RU"), f("W2"))) else SearchOutcome.Ok(listOf(f("W2"), f("R3")))
        }
        settle(c, "Sukhbaatar")
        assertEquals("both requests are in flight together (parallel)", listOf("Sukhbaatar", "сухбаатар"), calls.toList())
        gate.complete(Unit)
        runCurrent()
        val v = c.view.value as SearchView.Results
        // interleave N1, W2, W2, R3 → dedupe → MN first: W2, R3, N1
        assertEquals(listOf("W2", "R3", "N1"), v.items.map { it.name })
    }

    @Test
    fun russianLayoutSendsTheVowelVariantOnlyAfterAnEmpty200() = runTest {
        val (c, calls) = controller { q -> if (q == "Сухбаатар") SearchOutcome.Ok(emptyList()) else SearchOutcome.Ok(listOf(f("N9"))) }
        settle(c, "Сухбаатар")
        assertEquals(listOf("Сухбаатар", "Сүхбаатар"), calls.toList())
        assertEquals(listOf("N9"), (c.view.value as SearchView.Results).items.map { it.name })

        val (c2, calls2) = controller { SearchOutcome.Ok(listOf(f("N1"))) }
        settle(c2, "Сухбаатар")
        assertEquals("a non-empty first response sends nothing more", listOf("Сухбаатар"), calls2.toList())

        val (c3, calls3) = controller { SearchOutcome.Ok(listOf(f("N1"))) }
        settle(c3, "Улаанбаатар")
        assertEquals(listOf("Улаанбаатар"), calls3.toList())

        val (c4, calls4) = controller { SearchOutcome.Unavailable }
        settle(c4, "Сухбаатар")
        assertEquals("a failed first request is not followed by the variant", listOf("Сухбаатар"), calls4.toList())
        assertEquals(SearchView.Unavailable, c4.view.value)
    }

    @Test
    fun abbreviationSendsExpandedAndTypedHyphenJoinedIsNotExpanded() = runTest {
        val (c, calls) = controller { SearchOutcome.Ok(listOf(f("N1"))) }
        settle(c, "БЗД 4-р хороо")
        assertEquals(listOf("Баянзүрх дүүрэг 4-р хороо", "БЗД 4-р хороо"), calls.toList())
        calls.clear()
        settle(c, "БЗД-ийн")
        assertEquals(listOf("БЗД-ийн"), calls.toList())
    }

    @Test
    fun pairFailureRulesAndRateLimit() = runTest {
        val (one, _) = controller { q -> if (q == "Gandan") SearchOutcome.Unavailable else SearchOutcome.Ok(listOf(f("N5"))) }
        settle(one, "Gandan")
        assertEquals("one failed request → the other's results, no error", listOf("N5"), (one.view.value as SearchView.Results).items.map { it.name })

        val (both, _) = controller { SearchOutcome.Unavailable }
        settle(both, "Gandan")
        assertEquals(SearchView.Unavailable, both.view.value)

        val (limited, calls) = controller { q -> if (q == "Gandan") SearchOutcome.RateLimited(4) else SearchOutcome.Ok(listOf(f("N5"))) }
        settle(limited, "Gandan")
        assertEquals(SearchView.RateLimited(false), limited.view.value)
        calls.clear()
        settle(limited, "Zaisan")
        assertEquals("nothing is sent during Retry-After", 0, calls.size)
        advanceTimeBy(4_001)
        assertEquals(SearchView.RateLimited(true), limited.view.value)
    }

    @Test
    fun anOlderResponseNeverReplacesTheListOfANewerQuery() = runTest {
        val slow = CompletableDeferred<SearchOutcome>()
        val (c, _) = controller { q -> if (q.startsWith("Зайс")) slow.await() else SearchOutcome.Ok(listOf(f("N2"))) }
        settle(c, "Зайсан")
        settle(c, "Гандан")
        slow.complete(SearchOutcome.Ok(listOf(f("N1"))))
        runCurrent()
        assertEquals(listOf("N2"), (c.view.value as SearchView.Results).items.map { it.name })
    }

    /**
     * NAV-011 AC 7 (D140, supersedes the D115 "sent as typed" part; ADR-0012 Amendment A2/A5). Replaces
     * `typedCoordinateIsStillSentAsTyped`: a recognised pair is the coordinate option with 0 `search` requests, never `q`.
     */
    @Test
    fun typedCoordinateBecomesTheOptionWithNoRequest() = runTest {
        for (q in listOf("47.9189, 106.9176", "47.9189,106.9176", "47.9189 106.9176")) {
            val (c, calls) = controller { SearchOutcome.Ok(listOf(f("N1"))) }
            settle(c, q)
            advanceTimeBy(SearchController.LOADING_DELAY_MS + 1)
            runCurrent()
            assertEquals("«$q»: 0 search requests", emptyList<String>(), calls.toList())
            assertEquals("«$q»", SearchView.Coordinate(LatLon(47.9189, 106.9176)), c.view.value)
        }
        // The shared ADR-0006 fixture row and the integer pair (D143: kept for web parity).
        val (c, calls) = controller { SearchOutcome.Ok(emptyList()) }
        settle(c, "-45.5 -170")
        assertEquals(SearchView.Coordinate(LatLon(-45.5, -170.0)), c.view.value)
        settle(c, "47 106")
        assertEquals(SearchView.Coordinate(LatLon(47.0, 106.0)), c.view.value)
        assertEquals(0, calls.size)
    }

    /** AC 7, 39: pairs the rule rejects are ordinary text and are searched as typed (1 request each). */
    @Test
    fun rejectedPairsAreStillSearchedAsTyped() = runTest {
        for (q in listOf("106.9176, 47.9189", "47,9189, 106,9176")) {
            val (c, calls) = controller { SearchOutcome.Ok(emptyList()) }
            settle(c, q)
            assertEquals(listOf(q), calls.toList())
            assertEquals(SearchView.NoResults, c.view.value)
        }
    }

    /** Web order (searchController.ts settle()): the coordinate is decided before the offline check. */
    @Test
    fun offlineStillShowsTheCoordinateOption() = runTest {
        val (c, calls) = controller(online = { false }) { SearchOutcome.Ok(emptyList()) }
        settle(c, "47.9189, 106.9176")
        assertEquals(SearchView.Coordinate(LatLon(47.9189, 106.9176)), c.view.value)
        settle(c, "Зайсан")
        assertEquals("text offline: «Интернэт холболт алга»", SearchView.Offline, c.view.value)
        assertEquals(0, calls.size)
    }

    /** …and before the 429 cooldown, also one started by the other instance through the shared [SearchCooldown]. */
    @Test
    fun cooldownStillShowsTheCoordinateOption() = runTest {
        val shared = SearchCooldown()
        shared.start(testScheduler.currentTime + 10_000)
        val (c, calls) = controller(cooldown = shared) { SearchOutcome.Ok(emptyList()) }
        settle(c, "47.9189 106.9176")
        assertEquals(SearchView.Coordinate(LatLon(47.9189, 106.9176)), c.view.value)
        settle(c, "Зайсан")
        assertEquals(SearchView.RateLimited(false), c.view.value)
        settle(c, "47.9189,106.9176")
        assertEquals(SearchView.Coordinate(LatLon(47.9189, 106.9176)), c.view.value)
        assertEquals(0, calls.size)
    }

    /** «Дахин оролдох» (or a repeated settle of the same text) on a coordinate sends nothing. */
    @Test
    fun retryAndRepeatOnACoordinateSendNothing() = runTest {
        val (c, calls) = controller { SearchOutcome.Ok(emptyList()) }
        settle(c, "47.9189, 106.9176")
        c.retry()
        advanceTimeBy(SearchController.LOADING_DELAY_MS + 1)
        runCurrent()
        settle(c, "47.9189, 106.9176 ")
        assertEquals(SearchView.Coordinate(LatLon(47.9189, 106.9176)), c.view.value)
        assertEquals(0, calls.size)
    }

    /** Generation: a text response still on its way never replaces the option; a newer text query does. */
    @Test
    fun aCoordinateSupersedesAnOlderTextResponseAndNewerTextReplacesIt() = runTest {
        val slow = CompletableDeferred<SearchOutcome>()
        val (c, calls) = controller { q -> if (q.startsWith("Зайс")) slow.await() else SearchOutcome.Ok(listOf(f("N2"))) }
        settle(c, "Зайсан")
        settle(c, "47.9189, 106.9176")
        slow.complete(SearchOutcome.Ok(listOf(f("N1"))))
        runCurrent()
        assertEquals(SearchView.Coordinate(LatLon(47.9189, 106.9176)), c.view.value)
        settle(c, "Гандан")
        assertEquals(listOf("N2"), (c.view.value as SearchView.Results).items.map { it.name })
        assertEquals("no coordinate ever reaches q", listOf("Зайсан", "Гандан"), calls.toList())
    }

    /** AC 2 / AC 7 on the wire: 2 requests, each with the NAV-005 profile (lang, limit 8, bias to 3 decimals). */
    @Test
    fun pairOnTheWireKeepsTheRequestProfile() = runBlocking {
        val server = MockWebServer()
        val seen = Collections.synchronizedList(ArrayList<RecordedRequest>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return MockResponse.Builder().code(200).body("""{"type":"FeatureCollection","features":[]}""").build()
            }
        }
        server.start()
        val client = SearchClient(server.url("/").toString(), OkHttpClient(), { true })
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val c = SearchController(scope, { q, l, b -> client.search(q, l, b) }, { Lang.EN }, { LatLon(47.918912, 106.917634) }, { true }, { System.nanoTime() / 1_000_000 })
        c.onQuery("Ikh delguur")
        val deadline = System.currentTimeMillis() + 5_000
        while (c.view.value !is SearchView.NoResults && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(SearchView.NoResults, c.view.value)
        assertEquals(setOf("Ikh delguur", "их дэлгүүр"), seen.map { it.url.queryParameter("q") }.toSet())
        for (r in seen) {
            assertEquals("/v1/search", r.url.encodedPath)
            assertEquals("en", r.url.queryParameter("lang"))
            assertEquals("8", r.url.queryParameter("limit"))
            assertEquals("47.919", r.url.queryParameter("lat"))
            assertEquals("106.918", r.url.queryParameter("lon"))
            assertTrue((r.url.queryParameter("q") ?: "").length <= 200)
        }
        server.close()
    }
}

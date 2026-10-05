package mn.navmn.app.qa

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mn.navmn.app.format.Formatters
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.map.CoordinateCamera
import mn.navmn.app.search.PhotonFeature
import mn.navmn.app.search.SearchClient
import mn.navmn.app.search.SearchController
import mn.navmn.app.search.SearchCooldown
import mn.navmn.app.search.SearchOutcome
import mn.navmn.app.search.SearchView
import mn.navmn.app.search.reverse.ReverseClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import java.util.Collections

/*
 * NAV-011 QA, D140 typed-coordinate change (owner: qa-engineer). Test plan docs/qa/test-plans/NAV-011.md §4 rows 7, 7a,
 * 8, 39 (TC-C01…C08, TC-P39j). Oracles come from the story text (AC 7, 7a, 8, 39, Edge cases) only:
 *  - "recognised pair": <lat>, <lon> / <lat>,<lon> / <lat> <lon>, point decimals, lat −90…90, lon −180…180, after
 *    settle (NFC, whitespace collapsed incl. U+00A0 / U+202F, trimmed). QA's own regex below, not the app's parser.
 *  - option line 2: 5 decimals, point separator; reverse: 6 decimals, the typed values, not rounded to 3.
 * The mobile engineer's AssistedSearchTest covers the three separator forms with one point; these cases add the range
 * boundaries, the messenger-paste spaces, a 429 started by a real response of the same controller, network resume,
 * the "no loading row ever" history, the wire-level q scan, and the typed-value → reverse URL chain.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QaNav011CoordinateTest {

    // ------------------------------------------------------------------------------------------------ QA oracle

    /** AC 7 / AC 39 oracle, written from the story text (independent of `CoordinateInput`). Returns the pair or null. */
    private fun recognisedPair(q: String): Pair<Double, Double>? {
        val s = Normalizer.normalize(q, Normalizer.Form.NFC).replace(Regex("[\\s\\u00A0\\u202F\\uFEFF]+"), " ").trim()
        val m = Regex("^(-?[0-9]+(?:\\.[0-9]+)?)(?: ?, ?| )(-?[0-9]+(?:\\.[0-9]+)?)$").matchEntire(s) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    private fun f(key: String) = PhotonFeature(LatLon(47.9, 106.9), mapOf("osm_type" to key.take(1), "osm_id" to key.drop(1), "countrycode" to "MN", "name" to key))

    private class Env(val c: SearchController, val calls: MutableList<String>, val history: MutableList<SearchView>)

    private fun TestScope.env(
        online: () -> Boolean = { true },
        cooldown: SearchCooldown = SearchCooldown(),
        answer: suspend (String) -> SearchOutcome = { SearchOutcome.Ok(listOf(f("N1"))) },
    ): Env {
        val calls = Collections.synchronizedList(ArrayList<String>())
        val c = SearchController(this, { q, _, _ -> calls += q; answer(q) }, { Lang.MN }, { LatLon(47.9, 106.9) }, online, { testScheduler.currentTime }, cooldown)
        val history = Collections.synchronizedList(ArrayList<SearchView>())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.view.collect { history += it } }
        return Env(c, calls, history)
    }

    /** Types [q], lets it settle and waits past the 300 ms loading-row delay. */
    private fun TestScope.settle(e: Env, q: String) {
        e.c.onQuery(q)
        advanceTimeBy(SearchController.DEBOUNCE_MS + SearchController.LOADING_DELAY_MS + 50)
        runCurrent()
    }

    // --------------------------------------------------------------------------------------------- AC 7 (JVM)

    /**
     * TC-C01, AC 7 + Edge cases: every recognised form (three separators, negative, integers D143, range boundaries,
     * messenger paste with U+00A0 / U+202F, leading/trailing spaces) → exactly one option at the typed value, 0 `search`
     * requests, and the loading row «Ачаалж байна…» is never shown.
     */
    @Test
    fun tcC01_everyRecognisedFormIsOneOptionWithNoRequestAndNoLoadingRow() = runTest {
        val cases = linkedMapOf(
            "47.9189, 106.9176" to LatLon(47.9189, 106.9176),
            "47.9189,106.9176" to LatLon(47.9189, 106.9176),
            "47.9189 106.9176" to LatLon(47.9189, 106.9176),
            "-45.5 -170" to LatLon(-45.5, -170.0),
            "47 106" to LatLon(47.0, 106.0),
            "90 180" to LatLon(90.0, 180.0),
            "-90, -180" to LatLon(-90.0, -180.0),
            "47.9189, 106.9176" to LatLon(47.9189, 106.9176),
            "47.9189, 106.9176" to LatLon(47.9189, 106.9176),
            "  47.886123, 106.905467  " to LatLon(47.886123, 106.905467),
        )
        for ((q, p) in cases) {
            assertTrue("QA oracle recognises «$q»", recognisedPair(q) != null)
            val e = env()
            settle(e, q)
            assertEquals("«$q»: 0 search requests", emptyList<String>(), e.calls.toList())
            assertEquals("«$q»: one option at the typed value", SearchView.Coordinate(p), e.c.view.value)
            assertFalse("«$q»: no loading row at any time (${e.history})", e.history.any { it == SearchView.Loading })
        }
    }

    /**
     * TC-C02, AC 7 last bullet: an out-of-range pair and a decimal-comma pair send exactly 1 request as typed (no
     * assistance variant); the range just outside the boundaries is text too. Forms the rule does not recognise
     * (full-width comma, a pair with other words) are never the option and are searched (section A).
     */
    @Test
    fun tcC02_rejectedPairsAreSearchedAsText() = runTest {
        for (q in listOf("106.9176, 47.9189", "47,9189, 106,9176", "90.0001 10", "45 180.5")) {
            assertEquals("QA oracle rejects «$q»", null, recognisedPair(q))
            val e = env(answer = { SearchOutcome.Ok(emptyList()) })
            settle(e, q)
            assertEquals("«$q»: exactly 1 request, as typed", listOf(q), e.calls.toList())
            assertEquals(SearchView.NoResults, e.c.view.value)
        }
        for (q in listOf("47.9189，106.9176", "47.9189, 106.9176 гэр")) {
            assertEquals("QA oracle rejects «$q»", null, recognisedPair(q))
            val e = env(answer = { SearchOutcome.Ok(emptyList()) })
            settle(e, q)
            assertFalse("«$q» is not the option", e.c.view.value is SearchView.Coordinate)
            assertTrue("«$q» is searched as text (${e.calls})", e.calls.isNotEmpty() && e.calls.size <= 2)
        }
    }

    /**
     * TC-C03, AC 7 offline + network resume: offline the option still shows with 0 requests (text offline shows
     * «Интернэт холболт алга»); when the network comes back, «Дахин оролдох» / resume on the option sends 0 requests.
     */
    @Test
    fun tcC03_offlineOptionAndResumeSendNothing() = runTest {
        var online = false
        val e = env(online = { online })
        settle(e, "47.9189, 106.9176")
        assertEquals(SearchView.Coordinate(LatLon(47.9189, 106.9176)), e.c.view.value)
        online = true
        e.c.retry()
        advanceTimeBy(SearchController.LOADING_DELAY_MS + 50)
        runCurrent()
        assertEquals("resume / retry on the option keeps it", SearchView.Coordinate(LatLon(47.9189, 106.9176)), e.c.view.value)
        assertEquals("0 requests offline and on resume", 0, e.calls.size)
        assertFalse(e.history.any { it == SearchView.Offline || it == SearchView.Loading })
    }

    /**
     * TC-C04, AC 7 429: a `Retry-After: 30` received by THIS controller for a text query starts the cooldown; a pair
     * typed during it still shows the option with 0 requests; retry on it sends nothing; a text query typed after it
     * shows «Түр хүлээгээд дахин оролдоно уу» (RateLimited) with 0 requests; after the cooldown, text is searched again.
     */
    @Test
    fun tcC04_429CooldownFromARealResponseStillShowsTheOption() = runTest {
        val e = env(answer = { q -> if (q == "Зайсан") SearchOutcome.RateLimited(30) else SearchOutcome.Ok(listOf(f("N2"))) })
        settle(e, "Зайсан")
        assertEquals(SearchView.RateLimited(false), e.c.view.value)
        assertEquals(listOf("Зайсан"), e.calls.toList())
        e.calls.clear()
        settle(e, "47.9189 106.9176")
        assertEquals(SearchView.Coordinate(LatLon(47.9189, 106.9176)), e.c.view.value)
        e.c.retry()
        runCurrent()
        settle(e, "Гандан")
        assertEquals(SearchView.RateLimited(false), e.c.view.value)
        assertEquals("0 requests during Retry-After", 0, e.calls.size)
        settle(e, "47.9189,106.9176")
        assertEquals(SearchView.Coordinate(LatLon(47.9189, 106.9176)), e.c.view.value)
        advanceTimeBy(30_000)
        settle(e, "Гандан")
        assertEquals("after the cooldown text is searched again", listOf("Гандан"), e.calls.toList())
    }

    /** TC-C05, AC 7 generation: a newer settled text query replaces the option; a newer pair replaces the text list. */
    @Test
    fun tcC05_optionAndTextListReplaceEachOther() = runTest {
        val e = env()
        settle(e, "47.9189, 106.9176")
        settle(e, "Сүхбаатар")
        assertTrue(e.c.view.value is SearchView.Results)
        settle(e, "47.886123, 106.905467")
        assertEquals(SearchView.Coordinate(LatLon(47.886123, 106.905467)), e.c.view.value)
        settle(e, "4")
        assertEquals("one character closes the list", SearchView.Closed, e.c.view.value)
        assertEquals(listOf("Сүхбаатар"), e.calls.toList())
    }

    /** TC-C06, AC 7 line 2: the normalised coordinates, 5 decimals, point separator (also where the default Locale uses a comma). */
    @Test
    fun tcC06_optionLine2HasFiveDecimalsAndAPointSeparator() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("47.91890, 106.91760", Formatters.coordinates(47.9189, 106.9176))
            assertEquals("-45.50000, -170.00000", Formatters.coordinates(-45.5, -170.0))
            assertEquals("47.88612, 106.90547", Formatters.coordinates(47.886123, 106.905467))
            assertEquals("47.00000, 106.00000", Formatters.coordinates(47.0, 106.0))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    // --------------------------------------------------------------------------------------------- AC 7a / 8 (JVM)

    /**
     * TC-C07, AC 8: the typed values reach `reverse` unrounded. The chain is the controller's option point → the reverse
     * URL the card sends: lat/lon at 6 decimals equal to the typed text (not 3), `limit=1`, `radius=0.5`, `lang`.
     */
    @Test
    fun tcC07_typedValuesReachReverseUnrounded() = runTest {
        val expected = mapOf(
            "47.9189, 106.9176" to ("47.918900" to "106.917600"),
            "47.886123, 106.905467" to ("47.886123" to "106.905467"),
            "-45.5 -170" to ("-45.500000" to "-170.000000"),
        )
        // URL building only (no request is sent); the base comes from a local MockWebServer so no host is written here.
        val server = MockWebServer().apply { start() }
        val rc = ReverseClient(server.url("/").toString(), OkHttpClient()) { true }
        for ((q, ll) in expected) {
            val e = env()
            settle(e, q)
            val p = (e.c.view.value as SearchView.Coordinate).point
            val url = rc.url(p, Lang.MN)
            assertEquals("/v1/reverse", url.encodedPath)
            assertEquals("«$q» lat", ll.first, url.queryParameter("lat"))
            assertEquals("«$q» lon", ll.second, url.queryParameter("lon"))
            assertEquals("1", url.queryParameter("limit"))
            assertEquals("0.5", url.queryParameter("radius"))
            assertEquals("mn", url.queryParameter("lang"))
        }
        server.close()
    }

    /**
     * TC-C08, AC 7a camera (D142): zoom max(current, 16), and the camera centre (the middle of the padded area) lands
     * clear of the top bar with room for the 40 dp pin and above the card (narrow), or between the card column and the
     * control lane (wide). The Activity test cannot vary the fake camera's zoom (fixed 12), so the "higher" branch is here.
     */
    @Test
    fun tcC08_cameraZoomAndCentreAreClearOfTheTopBarAndTheCard() {
        assertEquals(16.0, CoordinateCamera.zoom(12.0), 0.0)
        assertEquals(16.0, CoordinateCamera.zoom(16.0), 0.0)
        assertEquals("current zoom higher → kept", 17.5, CoordinateCamera.zoom(17.5), 0.0)

        data class Screen(val w: Int, val h: Int, val density: Float, val topBar: Int, val cardTop: Int)
        for (s in listOf(Screen(945, 1680, 2.625f, 210, 1150), Screen(720, 1280, 2f, 160, 900), Screen(1080, 2400, 3f, 260, 1700))) {
            val card = CoordinateCamera.Box(0, s.cardTop, s.w, s.h)
            val p = CoordinateCamera.padding(s.w, s.h, s.topBar, card, null, wide = false, density = s.density)
            val cy = p.top + (s.h - p.top - p.bottom) / 2.0
            val cx = p.left + (s.w - p.left - p.right) / 2.0
            assertTrue("$s: pin top (centre − 40 dp = ${cy - 40 * s.density}) below the top bar", cy - 40 * s.density >= s.topBar)
            assertTrue("$s: point above the card (cy=$cy)", cy < s.cardTop)
            assertEquals("$s: horizontally centred", s.w / 2.0, cx, 1.0)
        }
        // Wide (P8): the card is a start-edge column [0, 1000] px; the control lane starts at 2200 px of 2400.
        val wide = CoordinateCamera.padding(2400, 1080, 200, CoordinateCamera.Box(0, 200, 1000, 1080), 2200, wide = true, density = 3f)
        val cx = wide.left + (2400 - wide.left - wide.right) / 2.0
        val cy = wide.top + (1080 - wide.top - wide.bottom) / 2.0
        assertTrue("wide: centre right of the card column (cx=$cx)", cx > 1000)
        assertTrue("wide: centre left of the control lane (cx=$cx)", cx < 2200)
        assertTrue("wide: pin below the top bar (cy=$cy)", cy - 40 * 3 >= 200)
    }

    // ------------------------------------------------------------------------------------------- AC 39 (wire)

    /**
     * TC-P39j, AC 39 (D140, D115 exception removed), on the wire with the real [SearchClient]: a session of recognised
     * pairs (all forms of TC-C01) and text. No `search` `q` is a pair the QA oracle recognises; the rejected pairs reach
     * `q` exactly as typed (user text, allowed); every request keeps the D30 bias at 3 decimals.
     */
    @Test
    fun tcP39j_noRecognisedPairEverReachesQOnTheWire() = runBlocking {
        val server = MockWebServer()
        val seen = Collections.synchronizedList(ArrayList<RecordedRequest>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return MockResponse.Builder().code(200).body("""{"type":"FeatureCollection","features":[]}""").build()
            }
        }
        server.start()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val client = SearchClient(server.url("/").toString(), OkHttpClient(), { true })
            val c = SearchController(scope, { q, l, b -> client.search(q, l, b) }, { Lang.MN }, { LatLon(47.918912, 106.917634) }, { true }, { System.nanoTime() / 1_000_000 })
            val recognised = listOf(
                "47.9189, 106.9176", "47.9189,106.9176", "47.9189 106.9176", "-45.5 -170", "47 106",
                "47.9189, 106.9176", "47.9189, 106.9176", " 47.886123, 106.905467 ",
            )
            val text = listOf("Зайсан", "106.9176, 47.9189", "47,9189, 106,9176")
            fun await(cond: () -> Boolean) {
                val deadline = System.currentTimeMillis() + 5_000
                while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(20)
                assertTrue("state not reached: ${c.view.value}", cond())
            }
            for (q in recognised + text + recognised.reversed()) {
                val before = seen.size
                c.onQuery(q)
                if (recognisedPair(q) != null) {
                    await { c.view.value is SearchView.Coordinate }
                    Thread.sleep(SearchController.DEBOUNCE_MS + SearchController.LOADING_DELAY_MS)
                    assertEquals("«$q»: 0 requests", before, seen.size)
                } else {
                    await { c.view.value is SearchView.NoResults }
                }
                // A text query typed between two equal pairs makes the next pair settle again.
                c.onQuery("")
            }
            val qs = seen.map { it.url.queryParameter("q") ?: "" }
            val leaks = qs.filter { recognisedPair(it) != null }
            assertTrue("AC 39: recognised pairs in q: $leaks", leaks.isEmpty())
            assertTrue("rejected pairs are user text, sent as typed ($qs)", qs.containsAll(listOf("106.9176, 47.9189", "47,9189, 106,9176")))
            for (r in seen) {
                assertEquals("/v1/search", r.url.encodedPath)
                assertEquals("D30 bias 3 decimals", "47.919", r.url.queryParameter("lat"))
                assertEquals("106.918", r.url.queryParameter("lon"))
            }
        } finally {
            scope.cancel()
            server.close()
        }
    }
}

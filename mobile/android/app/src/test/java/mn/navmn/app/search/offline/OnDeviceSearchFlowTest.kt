package mn.navmn.app.search.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.routing.FakeRoutingClock
import mn.navmn.app.search.PhotonFeature
import mn.navmn.app.search.SearchClient
import mn.navmn.app.search.SearchController
import mn.navmn.app.search.SearchOutcome
import mn.navmn.app.search.SearchView
import mn.navmn.app.search.reverse.ReverseClient
import mn.navmn.app.search.reverse.ReverseController
import mn.navmn.app.search.reverse.ReverseView
import mn.navmn.app.support.Fixtures
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * NAV-023 AC 6–9, 11, 21, 23, 25 at the controller level: the ADR-0012 plan reaches the device unchanged (every planned
 * query that the gateway would get, in the same mode), typed coordinates touch neither the gateway nor the file, a
 * search without network shows on-device results instead of «Интернэт холболт алга», «Илэрц олдсонгүй» from the device
 * and the coordinate card carry the on-device mark, a failing file is the NAV-011 unavailable state, and an older
 * answer never replaces a newer one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceSearchFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val clock = FakeRoutingClock()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun sources(device: OnDeviceSearch, validated: Boolean = false): SearchSources {
        val base = server.url("/").toString()
        return SearchSources(SearchClient(base, OkHttpClient()) { validated }, ReverseClient(base, OkHttpClient()) { validated }, device, { validated }, clock)
    }

    private class Recording(private val answer: suspend (String) -> SearchOutcome = { SearchOutcome.Ok(emptyList(), onDevice = true) }) : OnDeviceSearch {
        val queries = ArrayList<String>()
        override fun available() = true
        override suspend fun search(q: String, lang: Lang, bias: LatLon): SearchOutcome {
            queries += q
            return answer(q)
        }
        override suspend fun reverse(p: LatLon, lang: Lang): SearchOutcome = SearchOutcome.Ok(emptyList(), onDevice = true)
    }

    /** AC 6: for every text row of the shared ADR-0006 vectors, the device receives exactly what the gateway would. */
    @Test
    fun theQueryPlanReachesTheDeviceUnchanged() = runTest {
        val rows = Json.parseToJsonElement(Fixtures.shared("queryPlan.vectors.json")).jsonObject.getValue("plan").jsonArray.map { it.jsonObject }
            .filter { (it["kind"] as? JsonPrimitive)?.contentOrNull == "text" }
        assertTrue(rows.size >= 10)
        for (r in rows) {
            val input = (r["input"] as JsonPrimitive).content
            val online = ArrayList<String>()
            val onlineController = SearchController(this, { q, _, _ -> online += q; SearchOutcome.Ok(emptyList()) }, { Lang.MN }, { SearchFixture.P1 }, { true }, { testScheduler.currentTime })
            val device = Recording()
            val s = sources(device)
            val deviceController = SearchController(this, { q, l, b -> s.search(q, l, b) }, { Lang.MN }, { SearchFixture.P1 }, { s.onDeviceAvailable() }, { testScheduler.currentTime })
            onlineController.onQuery(input)
            deviceController.onQuery(input)
            advanceUntilIdle()
            assertEquals("«$input»", online.sorted(), device.queries.sorted())
            assertEquals("«$input»", SearchView.NoResultsOnDevice, deviceController.view.value)
        }
        assertEquals(0, server.requestCount)
    }

    private fun <T> eventually(block: () -> T?): T = runBlocking {
        withTimeout(15_000) {
            var v = block()
            while (v == null) {
                delay(20)
                v = block()
            }
            v
        }
    }

    @Test
    fun withoutNetworkTheInstalledFileAnswersAndCoordinatesTouchNothing() {
        SearchFixture.install(tmp.root)
        val offline = OfflineSearch(ActiveJsonSearchSource(tmp.root))
        val s = sources(offline)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = SearchController(scope, { q, l, b -> s.search(q, l, b) }, { Lang.MN }, { SearchFixture.P4 }, { false || s.onDeviceAvailable() }, { System.nanoTime() / 1_000_000 })
            // AC 8: typed coordinates → «Сонгосон цэг», 0 requests, 0 search-file queries.
            c.onQuery("47.9189, 106.9176")
            val coord = eventually { c.view.value as? SearchView.Coordinate }
            assertEquals(LatLon(47.9189, 106.9176), coord.point)
            assertEquals(0, offline.queries.get())
            // AC 11, 21: results from the device, not «Интернэт холболт алга».
            c.onQuery("Гандан хийд")
            val res = eventually { c.view.value as? SearchView.Results }
            assertTrue(res.onDevice)
            assertEquals("Гандан хийд", res.items.first().name)
            // AC 21: «Илэрц олдсонгүй» from the device keeps the mark.
            c.onQuery("xqzjwvk")
            assertEquals(SearchView.NoResultsOnDevice, eventually { c.view.value.takeIf { it == SearchView.NoResultsOnDevice } })
            assertEquals(0, server.requestCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theCoordinateCardsNearestPlaceComesFromTheDevice() {
        SearchFixture.install(tmp.root)
        val s = sources(OfflineSearch(ActiveJsonSearchSource(tmp.root)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = ReverseController(scope, { p, l -> s.reverse(p, l) }, { Lang.MN }, { s.onDeviceAvailable() }, { System.nanoTime() / 1_000_000 })
            r.open(SearchFixture.P1)
            val place = eventually { r.view.value as? ReverseView.Place }
            assertTrue("AC 23: OF24 next to «Ойролцоох газар»", place.onDevice)
            assertEquals("Сүхбаатарын талбай", place.feature["name"])
            r.open(LatLon(47.80, 106.70))
            assertEquals(ReverseView.EmptyOnDevice, eventually { r.view.value.takeIf { it == ReverseView.EmptyOnDevice } })
            assertEquals(0, server.requestCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFailingFileShowsSearchUnavailableWithRetry() = runTest {
        val s = sources(Recording { SearchOutcome.Unavailable })
        val c = SearchController(this, { q, l, b -> s.search(q, l, b) }, { Lang.MN }, { SearchFixture.P1 }, { s.onDeviceAvailable() }, { testScheduler.currentTime })
        c.onQuery("Гандан")
        advanceUntilIdle()
        assertEquals(SearchView.Unavailable, c.view.value)
    }

    /** AC 9: the NAV-011 debounce and generation rule hold for on-device answers too. */
    @Test
    fun anOlderOnDeviceAnswerNeverReplacesANewerOne() = runTest {
        val feature = PhotonFeature(SearchFixture.P1, mapOf("name" to "new", "osm_key" to "place", "osm_value" to "square"))
        val device = Recording { q ->
            if (q == "Гандан") delay(2_000)
            SearchOutcome.Ok(listOf(feature.copy(props = feature.props + ("name" to q))), onDevice = true)
        }
        val s = sources(device)
        val c = SearchController(this, { q, l, b -> s.search(q, l, b) }, { Lang.MN }, { SearchFixture.P1 }, { s.onDeviceAvailable() }, { testScheduler.currentTime })
        c.onQuery("Гандан")
        advanceTimeBy(300) // debounced, the slow answer is running
        c.onQuery("Зайсан")
        advanceUntilIdle()
        val v = c.view.value as SearchView.Results
        assertEquals("Зайсан", v.items.single().name)
        assertEquals(listOf("Гандан", "Зайсан"), device.queries)
    }
}

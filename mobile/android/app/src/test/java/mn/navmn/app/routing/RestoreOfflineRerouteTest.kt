package mn.navmn.app.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.engine.Banner
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import mn.navmn.app.reroute.RerouteSecondary
import mn.navmn.app.route.OsrmPlanParser
import mn.navmn.app.route.RouteClient
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.Fixtures
import mn.navmn.app.support.HostFerrostar
import mn.navmn.app.support.Replay
import mn.navmn.app.support.Tracks
import mn.navmn.app.support.distanceToLine
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * NAV-012 section K (change 7c), AC 53–55, through the guidance core and the real Ferrostar session (host library) on a
 * virtual clock. A restored session ([Replay.run] with `restoredAt`) whose first good fix is more than 50 m from the
 * stored route:
 *  - with a usable routing file and no validated network, reroutes on the device through the production transport
 *    ([FallbackRouteRequester] → [OnDeviceRouteRequester], fake engine): 0 HTTP requests, no «Интернэт холболт алга»
 *    line, the stored `costing` and language, the new route marked on-device (AC 53) and handed to the restore record;
 *  - a restored route that came from the device shows the OF24 indicator once trip progress shows, a gateway route none
 *    (AC 54; the skeleton itself is checked in [OfflineIndicatorTest]);
 *  - without a routing file, AC 20 / NAV-005 AC 50 is unchanged (0 requests, the offline secondary line).
 */
class RestoreOfflineRerouteTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val p3 = LatLon(47.8858, 106.9173)

    /** Runs submitted work inline, so the on-device answer is delivered within the request like the replay expects. */
    private object Direct : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    @Before
    fun setUp() {
        HostFerrostar.require()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.close()
    }

    /** Fixes along the start of the recorded G2 reroute: the position the G2 wrong turn leads to (> 50 m off the stored route). */
    private fun atRerouteOrigin(): List<Fix> {
        val reroute = OsrmPlanParser.plan(OsrmPlanParser.parseJson(Fixtures.route("g2-reroute-car-mn.json"))!!, 1)!!
        return Tracks.along(reroute.geometry, 6.0, t0 = 1_000, toM = 60.0)
    }

    private fun g2Origin(): LatLon {
        val meta = Json.parseToJsonElement(Fixtures.route("g2-offroute-point.json").decodeToString()).jsonObject
        return meta["rerouteOrigin"]!!.jsonArray.let { LatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
    }

    private fun onDeviceReplay(engine: FakeEngine, initialOnDevice: Boolean): Replay {
        val routing = testRouting(tmp.root)
        lateinit var replay: Replay
        val transport = lazy {
            val online = RouteClient(server.url("/").toString().trimEnd('/'), RouteClient.httpClient(OkHttpClient()), { false }, replay.processor)
            FallbackRouteRequester(
                online = online,
                onDevice = OnDeviceRouteRequester(engine, replay.processor, Direct, { routing }),
                policy = OnlineFirstPolicy(),
                clock = FakeRoutingClock(),
                validated = { false },
                onDeviceAvailable = { true },
            )
        }
        val proxy = object : mn.navmn.app.route.RouteRequester {
            override fun start(request: mn.navmn.app.route.RouteRequest, generation: Int, onResult: (mn.navmn.app.route.RouteOutcome) -> Unit) =
                transport.value.start(request, generation, onResult)
        }
        replay = Replay(
            Fixtures.route("p1-p3-car-mn.json"), TravelMode.CAR, Lang.MN, online = false, liveRequester = proxy,
            onDeviceAvailable = { true }, destination = p3, initialOnDevice = initialOnDevice,
        )
        return replay
    }

    @Test
    fun restoreWithoutNetworkReroutesOnTheDeviceWithZeroHttpRequests() {
        val engine = FakeEngine().apply { answer = { EngineAnswer.Osrm(Fixtures.route("g2-reroute-car-mn.json")) } }
        val r = onDeviceReplay(engine, initialOnDevice = false)
        val fixes = atRerouteOrigin()
        assertTrue("the restored position is off the stored route", distanceToLine(fixes.first().latLon, r.initial.plan.geometry) > 50.0)
        assertTrue(Geo.distance(fixes.first().latLon, g2Origin()) < 30.0)
        r.run(fixes, tailMs = 5_000, restoredAt = 0)

        assertTrue(r.log.any { it == "restore off the stored route" })
        // AC 53: the device answers, 0 HTTP route requests, the stored costing and language.
        assertEquals(0, server.requestCount)
        assertTrue(engine.calls.isNotEmpty())
        val req = r.requests.first().second
        assertEquals(TravelMode.CAR, req.mode)
        assertEquals(Lang.MN, req.lang)
        assertEquals(p3, req.destination)
        // The first request starts within 1 s of the episode start (the first good fix of the restore).
        assertTrue(r.requests.first().first - fixes.first().elapsedMs <= 1_000)
        // No «Интернэт холболт алга» secondary line. (The fake engine answers inside the request, so the episode resolves
        // in the same engine step and the «Маршрутыг дахин тооцоолж байна» banner may never be emitted here; the
        // banner timing is NAV-005 AC 42 / NAV-021 AC 15, covered by OnDeviceRerouteReplayTest and the device check.)
        assertFalse(r.states.any { (it.banner as? Banner.Rerouting)?.secondary == RerouteSecondary.OFFLINE })
        // The new route replaces the stored one, is marked on-device (OF24) and goes to the restore record (AC 16, 54).
        val active = r.states.first { it.generation == 1 }
        assertTrue(active.onDeviceRoute)
        assertTrue(r.newRoutes.first().onDevice)
        val expected = OsrmPlanParser.plan(OsrmPlanParser.parseJson(Fixtures.route("g2-reroute-car-mn.json"))!!, 1)!!.geometry
        assertEquals(expected, active.route)
    }

    @Test
    fun restoredDeviceRouteShowsTheIndicatorOnceProgressShowsAndAGatewayRouteNone() {
        for (fromDevice in listOf(true, false)) {
            val r = Replay(Fixtures.route("p1-p3-car-mn.json"), TravelMode.CAR, Lang.MN, destination = p3, initialOnDevice = fromDevice)
            val line = r.initial.plan.geometry
            val fixes = Tracks.along(line, 10.0, t0 = 2_000, fromM = Geo.length(line) * 0.87, toM = Geo.length(line) * 0.9)
            r.run(fixes, tailMs = 1_000, restoredAt = 0)
            assertTrue(r.states.first().restoring)
            val placed = r.states.first { !it.restoring }
            assertEquals("restored ${if (fromDevice) "device" else "gateway"} route", fromDevice, placed.onDeviceRoute)
            assertTrue(r.states.filter { !it.restoring }.all { it.onDeviceRoute == fromDevice })
            assertEquals(0, r.requests.size) // on the stored route: 0 requests (AC 19)
        }
    }

    @Test
    fun restoreWithoutNetworkAndWithoutARoutingFileKeepsTheAc20Behaviour() {
        val r = Replay(Fixtures.route("p1-p3-car-mn.json"), TravelMode.CAR, Lang.MN, online = false, destination = p3)
        r.run(atRerouteOrigin(), tailMs = 3_000, restoredAt = 0)
        assertTrue(r.log.any { it == "restore off the stored route" })
        assertEquals(0, r.requests.size)
        assertTrue(r.states.any { (it.banner as? Banner.Rerouting)?.secondary == RerouteSecondary.OFFLINE })
        assertFalse(r.states.any { it.onDeviceRoute })
        assertTrue(r.newRoutes.isEmpty())
    }
}

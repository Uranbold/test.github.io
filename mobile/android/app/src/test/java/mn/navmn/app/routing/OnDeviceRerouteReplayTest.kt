package mn.navmn.app.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.engine.Banner
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
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
 * NAV-021 AC 14, 15, 18, 19, 37 at the guidance level (host Ferrostar, virtual clock): the NAV-005 G2 wrong turn with
 * an installed routing file and no validated network. The reroute goes through the production transport
 * ([FallbackRouteRequester] → [OnDeviceRouteRequester]) to a fake engine that answers the recorded G2 reroute.
 * The device timing (≤ 2.0 s / 4.0 s, AC 15) is a device check (README procedure).
 */
class OnDeviceRerouteReplayTest {
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

    private fun g2Track(r: Replay, speed: Double = 14.0): Pair<List<Fix>, Long> {
        val geo = r.initial.plan.geometry
        val first = Tracks.along(geo, speed, 0, 0.0, r.initial.plan.steps[0].distance)
        val meta = Json.parseToJsonElement(Fixtures.route("g2-offroute-point.json").decodeToString()).jsonObject
        val turn = meta["turn"]!!.jsonArray.let { LatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
        val bearing = meta["bearing"]!!.jsonPrimitive.double
        val origin = meta["rerouteOrigin"]!!.jsonArray.let { LatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
        val wrong = Tracks.straight(turn, bearing, Geo.distance(turn, origin), speed, first.last().elapsedMs + 1_000)
        val reroute = OsrmPlanParser.plan(OsrmPlanParser.parseJson(Fixtures.route("g2-reroute-car-mn.json"))!!, 1)!!
        val rest = Tracks.along(reroute.geometry, speed, wrong.last().elapsedMs + 1_000)
        val t50 = wrong.first {
            distanceToLine(it.latLon, geo.drop(1).let { g -> g.subList(g.indexOfFirst { p -> Geo.distance(p, turn) < 1.0 }.coerceAtLeast(0), g.size) }) > 50.0
        }.elapsedMs
        return (first + wrong + rest) to t50
    }

    @Test
    fun g2OfflineWithAnInstalledRoutingFileReroutesOnTheDeviceWithZeroHttpRequests() {
        val engine = FakeEngine().apply { answer = { EngineAnswer.Osrm(Fixtures.route("g2-reroute-car-mn.json")) } }
        val routing = testRouting(tmp.root)
        val validated = false
        lateinit var replay: Replay
        val transport = lazy {
            val online = RouteClient(server.url("/").toString().trimEnd('/'), RouteClient.httpClient(OkHttpClient()), { validated }, replay.processor)
            FallbackRouteRequester(
                online = online,
                onDevice = OnDeviceRouteRequester(engine, replay.processor, Direct, { routing }),
                policy = OnlineFirstPolicy(),
                clock = FakeRoutingClock(),
                validated = { validated },
                onDeviceAvailable = { true },
            )
        }
        val proxy = object : mn.navmn.app.route.RouteRequester {
            override fun start(request: mn.navmn.app.route.RouteRequest, generation: Int, onResult: (mn.navmn.app.route.RouteOutcome) -> Unit) =
                transport.value.start(request, generation, onResult)
        }
        replay = Replay(Fixtures.route("p1-p3-car-mn.json"), TravelMode.CAR, online = false, liveRequester = proxy, onDeviceAvailable = { true }, destination = p3)
        val (track, t50) = g2Track(replay)
        val backOnline = track.last().elapsedMs - 5_000
        replay.run(track, tailMs = 10_000, networkChanges = mapOf(backOnline / 500 * 500 to true))

        // The fake engine answers inside the request, so the off-route episode resolves in the same engine step; the
        // NAV-005 detection rule (≤ 8 s after the first fix > 50 m away) plus the AC 42 1 s request rule bound it.
        assertEquals(1, replay.requests.size)
        val reqAt = replay.requests.single().first
        assertTrue("reroute requested ${reqAt - t50} ms after the first fix > 50 m away", reqAt - t50 in 0..9_000)
        assertTrue(replay.log.contains("off-route episode started"))
        // AC 15: the secondary line «Интернэт холболт алга» never shows.
        assertFalse(replay.states.any { (it.banner as? Banner.Rerouting)?.secondary == RerouteSecondary.OFFLINE })
        // AC 37: 0 HTTP requests by routing without a validated network; one on-device request with the exact body.
        assertEquals(0, server.requestCount)
        assertEquals(1, engine.calls.size)
        assertTrue(engine.calls.single().json.contains("\"heading\":"))
        // AC 18: the whole remaining route is replaced by the on-device route (generation 1, its geometry).
        val newRoute = replay.states.first { it.generation == 1 }
        assertTrue(newRoute.onDeviceRoute)
        val expected = OsrmPlanParser.plan(OsrmPlanParser.parseJson(Fixtures.route("g2-reroute-car-mn.json"))!!, 1)!!.geometry
        assertEquals(expected, newRoute.route)
        // AC 19: the network returned while on the on-device route: 0 further route requests.
        assertEquals(1, replay.requests.size)
        assertTrue(replay.last!!.onDeviceRoute) // AC 28: the indicator stays until a gateway route replaces it
    }

    @Test
    fun withoutAnInstalledRoutingFileTheNav005OfflineBehaviourIsUnchanged() {
        val replay = Replay(Fixtures.route("p1-p3-car-mn.json"), TravelMode.CAR, online = false, destination = p3)
        val (track, _) = g2Track(replay, speed = 6.0)
        replay.run(track.take(track.size - 30), tailMs = 2_000)
        assertEquals(0, replay.requests.size)
        assertTrue(replay.states.any { (it.banner as? Banner.Rerouting)?.secondary == RerouteSecondary.OFFLINE })
        assertFalse(replay.states.any { it.onDeviceRoute })
    }
}

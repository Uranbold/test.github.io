package mn.navmn.app.engine

import kotlinx.coroutines.runBlocking
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.search.SearchClient
import mn.navmn.app.search.SearchOutcome
import mn.navmn.app.i18n.Lang
import mn.navmn.app.route.Cancelable
import mn.navmn.app.route.RouteClient
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.RouteRequester
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.Fixtures
import mn.navmn.app.support.HostFerrostar
import mn.navmn.app.support.Replay
import mn.navmn.app.support.Tracks
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Opt-in checks against the live dev gateway (story "Reference environment"): skipped unless
 * -Pnav.liveGateway=http://127.0.0.1:8080 is given. /health first; at most 1 route request per second.
 */
class LiveGatewayTest {
    private val base = System.getProperty("nav.liveGateway").orEmpty()
    private val p1 = LatLon(47.9189, 106.9176)
    private val p3 = LatLon(47.8858, 106.9173)

    @Before
    fun need() {
        Assume.assumeTrue("set -Pnav.liveGateway to run", base.isNotEmpty())
        HostFerrostar.require()
        val health = OkHttpClient().newCall(Request.Builder().url("$base/health").build()).execute().use { it.code }
        Assume.assumeTrue("gateway /health = $health", health == 200)
    }

    private fun client() = RouteClient(base, RouteClient.httpClient(OkHttpClient()), { true }, RouteProcessor(FerrostarRouteParser()))

    @Test
    fun previewRequestsParseWithTheRealFerrostarCore() = runBlocking {
        val c = client()
        for ((mode, lang) in listOf(TravelMode.CAR to Lang.MN, TravelMode.WALK to Lang.EN, TravelMode.CAR to Lang.EN)) {
            Thread.sleep(1_000)
            val out = c.fetch(RouteRequest(p1, p3, mode, mode == TravelMode.CAR, lang), 0)
            assertTrue("$mode $lang: $out", out is RouteOutcome.Ok)
            val r = (out as RouteOutcome.Ok).route
            assertEquals(r.plan.steps.size, r.native.stepCount)
            assertTrue(r.plan.steps.last().key.key.isArrive)
        }
    }

    /** AC 3 live check: «Сүхбаатарын талбай» within 300 m of P1 in the top 5; "Sukhbaatar" is a list or no results. */
    @Test
    fun searchAsTypedAgainstTheLiveGateway() = runBlocking {
        val search = SearchClient(base, OkHttpClient(), { true })
        val cyr = search.search("Сүхбаатарын талбай", Lang.MN, p1)
        assertTrue("$cyr", cyr is SearchOutcome.Ok)
        val top5 = (cyr as SearchOutcome.Ok).features.take(5)
        val nearest: Double = top5.minOf { f -> Geo.distance(f.point, p1) }
        assertTrue("nearest of top 5: $nearest m", nearest <= 300.0)
        Thread.sleep(1_000)
        val latin = search.search("Sukhbaatar", Lang.MN, p1)
        assertTrue("$latin", latin is SearchOutcome.Ok)
        println("live search: top-5 nearest ${nearest.toInt()} m; 'Sukhbaatar' → ${(latin as SearchOutcome.Ok).features.size} results")
    }

    /** AC 45 (shape): detection → new route on screen, with the real gateway latency, ≤ 3 s. */
    @Test
    fun g2RerouteAgainstTheLiveGateway() {
        val c = client()
        lateinit var replay: Replay
        val latencies = ArrayList<Long>()
        val live = object : RouteRequester {
            override fun start(request: RouteRequest, generation: Int, onResult: (RouteOutcome) -> Unit): Cancelable {
                Thread.sleep(1_000)
                val t0 = System.nanoTime()
                val out = runBlocking { c.fetch(request, generation) }
                latencies += (System.nanoTime() - t0) / 1_000_000
                onResult(out)
                return Cancelable { }
            }
        }
        replay = Replay(Fixtures.route("p1-p3-car-mn.json"), TravelMode.CAR, Lang.MN, destination = p3, liveRequester = live)
        val geo = replay.initial.plan.geometry
        val turnAt = replay.initial.plan.steps[0].distance
        val first = Tracks.along(geo, 14.0, 0, 0.0, turnAt)
        val wrong = Tracks.straight(geo.let { mn.navmn.app.geo.Geo.along(it, turnAt) }, 165.0, 400.0, 14.0, first.last().elapsedMs + 1_000)
        replay.run(first + wrong, tailMs = 3_000)
        // The synchronous live requester answers inside the detecting fix, so the request time marks detection.
        assertTrue("requests ${replay.requests.size}", replay.requests.isNotEmpty())
        val detected = replay.requests.first().first
        assertTrue("a new route became active", replay.last!!.generation >= 1)
        assertTrue("gateway latency ${latencies.first()} ms", latencies.first() <= 3_000)
        val req = replay.requests.first().second
        assertTrue("heading ${req.heading}", req.heading != null)
        println("live G2: first request at ${detected / 1000} s (virtual), gateway latency ${latencies.first()} ms, generation ${replay.last!!.generation}, requests ${replay.requests.size}")
    }
}

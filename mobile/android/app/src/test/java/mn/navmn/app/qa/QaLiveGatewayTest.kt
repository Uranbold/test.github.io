package mn.navmn.app.qa

import kotlinx.coroutines.runBlocking
import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.FerrostarRouteParser
import mn.navmn.app.geo.Geo
import mn.navmn.app.i18n.Lang
import mn.navmn.app.route.Cancelable
import mn.navmn.app.route.RouteClient
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.RouteRequester
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.HostFerrostar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * NAV-005 QA live checks against the dev gateway (opt-in: -Pnav.liveGateway=http://127.0.0.1:8080). /health first;
 * at most 1 route request per second for the whole class (story limit 2/s). Never starts or restarts anything.
 * Test plan ids TC-L*.
 */
class QaLiveGatewayTest {
    private val base = System.getProperty("nav.liveGateway").orEmpty()
    private var lastRequestNs = 0L

    @Before
    fun need() {
        Assume.assumeTrue("set -Pnav.liveGateway to run", base.isNotEmpty())
        HostFerrostar.require()
        var health = 0
        for (attempt in 1..10) {
            health = runCatching { OkHttpClient().newCall(Request.Builder().url("$base/health").build()).execute().use { it.code } }.getOrDefault(0)
            if (health == 200) break
            Thread.sleep(30_000) // wait and retry; never restart the shared stack
        }
        Assume.assumeTrue("gateway /health = $health", health == 200)
    }

    private fun pace() {
        val wait = 1_000L - (System.nanoTime() - lastRequestNs) / 1_000_000
        if (wait > 0) Thread.sleep(wait)
        lastRequestNs = System.nanoTime()
    }

    /** AC 45: detection → new route on screen ≤ 3 s for ≥ 9 of 10 live G2 replays. */
    @Test
    fun tcL01_ac45TenLiveG2Replays() {
        val client = RouteClient(base, RouteClient.httpClient(OkHttpClient()), { true }, RouteProcessor(FerrostarRouteParser()))
        val g2 = QaGpx.fixes("G2")
        val firstFar = g2.indexOfFirst { mn.navmn.app.support.distanceToLine(it.latLon, planOf(QaGpx.routeBytes("G2")).geometry) > 50.0 }
        val track = g2.take(firstFar + 25)
        val results = ArrayList<String>()
        var ok = 0
        repeat(10) { run ->
            val wallMs = ArrayList<Long>()
            val live = object : RouteRequester {
                override fun start(request: RouteRequest, generation: Int, onResult: (RouteOutcome) -> Unit): Cancelable {
                    pace()
                    val t0 = System.nanoTime()
                    val out = runBlocking { client.fetch(request, generation) }
                    onResult(out) // applies the route and emits the new state (engine thread in the app)
                    wallMs += (System.nanoTime() - t0) / 1_000_000
                    return Cancelable { }
                }
            }
            val r = QaRun(QaGpx.routeBytes("G2"), TravelMode.CAR, Lang.MN, P3, live = live)
            r.run(track, tailMs = 0)
            val det = r.states.firstOrNull { it.second.banner is Banner.Rerouting }?.first
            val req = r.requests.firstOrNull()?.first
            val applied = r.states.any { it.second.generation >= 1 && it.second.banner is Banner.Maneuver }
            // The live requester answers synchronously inside the detecting fix, so the recalculating state is never
            // emitted on its own: detection = the request time (AC 42's ≤ 1 s detection→request is checked in TC-R04).
            val detT = det ?: req
            if (detT == null || req == null || wallMs.isEmpty() || !applied) {
                results += "run ${run + 1}: det=$det req=$req applied=$applied outcome=${r.responsesAt.firstOrNull()?.second?.javaClass?.simpleName}"
            } else {
                val total = (req - detT) + wallMs[0]
                if (total <= 3_000) ok++
                results += "run ${run + 1}: detection→request ${req - detT} ms (virtual) + gateway+parse+apply ${wallMs[0]} ms = $total ms; requests ${r.requests.size}"
            }
        }
        println("AC 45 live G2 series:\n" + results.joinToString("\n"))
        assertTrue("AC 45: only $ok of 10 ≤ 3 s\n" + results.joinToString("\n"), ok >= 9)
    }

    /** AC 7 / ADR-0009 §2 live: what the reroute body returns from an out-of-coverage point, a walk route, en-US. */
    @Test
    fun tcL02_liveGuidanceProfiles() {
        val client = RouteClient(base, RouteClient.httpClient(OkHttpClient()), { true }, RouteProcessor(FerrostarRouteParser()))
        val p = ArrayList<String>()
        val cases = listOf(
            Triple("P1→P3 car mn", RouteRequest(P1, P3, TravelMode.CAR, false, Lang.MN), RouteOutcome.Ok::class),
            Triple("P1→P3 car avoid en", RouteRequest(P1, P3, TravelMode.CAR, true, Lang.EN, heading = 165), RouteOutcome.Ok::class),
            Triple("P1→P2 walk mn", RouteRequest(P1, P2, TravelMode.WALK, false, Lang.MN), RouteOutcome.Ok::class),
            Triple("Beijing (out of coverage)", RouteRequest(mn.navmn.app.geo.LatLon(39.9042, 116.4074), P3, TravelMode.CAR, false, Lang.MN), RouteOutcome.OutOfCoverage::class),
        )
        for ((name, req, want) in cases) {
            pace()
            val out = runBlocking { client.fetch(req, 1) }
            if (!want.isInstance(out)) p += "$name: $out (expected ${want.simpleName})"
            if (out is RouteOutcome.Ok) {
                val plan = out.route.plan
                if (plan.steps.size != out.route.native.stepCount) p += "$name: step count mismatch"
                if (Geo.distance(plan.geometry.last(), req.destination) > 300) p += "$name: route end ${Geo.distance(plan.geometry.last(), req.destination).toInt()} m from destination"
            }
            println("live $name → ${out.javaClass.simpleName}")
        }
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }
}

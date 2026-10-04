package mn.navmn.app.route.alternatives

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mn.navmn.app.engine.FerrostarRouteParser
import mn.navmn.app.i18n.Lang
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.Fixtures
import mn.navmn.app.support.HostFerrostar
import mn.navmn.app.support.Replay
import mn.navmn.app.support.Tracks
import mn.navmn.app.voiceplan.PromptClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * With the REAL Ferrostar core (host library): NAV-011 AC 19 (the selected route's single-route slice drives guidance
 * with its own banner sequence, 0 extra requests) and AC 26 («Дугуй» prompts from the navigation-ux §4.2 bike column).
 */
class AlternativesReplayTest {
    @Before
    fun need() = HostFerrostar.require()

    private val processor = RouteProcessor(FerrostarRouteParser())

    @Test
    fun everySliceParsesInFerrostarWithMatchingStepCounts() {
        val out = PreviewRoutes.process(processor, ThreeRoutes.bytes(), 0) as RouteOutcome.Ok
        assertEquals(3, out.routes.size)
        for (r in out.routes) assertEquals(r.plan.steps.size, r.native.stepCount)
    }

    /** AC 19 replay: select route 2 of the 3-route response, start guidance: route 2's banners, 0 requests. */
    @Test
    fun selectingRoute2AndStartingGivesTheRoute2BannerSequence() {
        val root = Json.parseToJsonElement(ThreeRoutes.bytes().decodeToString()).jsonObject
        val slice = PreviewRoutes.slice(root, 1)
        val selected = Replay(slice, TravelMode.CAR, Lang.MN)
        val reference = Replay(Fixtures.route("g2-reroute-car-mn.json"), TravelMode.CAR, Lang.MN)
        assertEquals(reference.initial.plan.geometry, selected.initial.plan.geometry)
        val fixes = Tracks.along(selected.initial.plan.geometry, 12.0)
        selected.run(fixes, tailMs = 8_000)
        reference.run(fixes, tailMs = 8_000)
        assertTrue(selected.banners.size >= 2)
        assertEquals(reference.banners.map { it.second }, selected.banners.map { it.second })
        assertEquals("0 route requests on the selected route", 0, selected.requests.size)
        // Not route 1's sequence.
        val route1 = Replay(PreviewRoutes.slice(root, 0), TravelMode.CAR, Lang.MN)
        route1.run(Tracks.along(route1.initial.plan.geometry, 12.0), tailMs = 8_000)
        assertTrue(route1.banners.map { it.second } != selected.banners.map { it.second })
    }

    /**
     * AC 26 (stand-in for G10 until QA records the bicycle response): «Дугуй» guidance at 4.5 m/s along the recorded
     * P1 → P3 geometry. Every manoeuvre except `depart` (and the D68 `arrive` exemption) gets a prompt that starts while
     * it is 15–150 m ahead; no main prompt beyond 150 m; stated distances within max(30 m, 20 %).
     */
    @Test
    fun bikeColumnPromptBand() {
        val v = 4.5
        val r = Replay(Fixtures.route("p1-p3-car-mn.json"), TravelMode.BICYCLE, Lang.MN)
        val plan = r.initial.plan
        val fixes = Tracks.along(plan.geometry, v)
        r.run(fixes, tailMs = 10_000)
        val t0 = fixes.first().elapsedMs
        // Manoeuvre m sits at the end of steps 0..m-1 (OSRM step distances, legs flattened).
        val maneuverAlong = plan.steps.indices.map { m -> plan.steps.take(m).sumOf { it.distance } }
        val covered = HashSet<Int>()
        val problems = ArrayList<String>()
        val metres = Regex("^(\\d+) метрт")
        for ((t, sp) in r.spoken) {
            val m = sp.maneuver?.second ?: continue
            if (sp.cls == PromptClass.ARRIVAL || m == 0) continue
            val along = v * (t - t0) / 1000.0
            val d = maneuverAlong[m] - along
            if (d in 15.0..150.0) covered += m
            metres.find(sp.text)?.groupValues?.get(1)?.toDouble()?.let { s ->
                if (Math.abs(s - d) > maxOf(30.0, 0.2 * d)) problems += "«${sp.text}» states ${s.toInt()} m, true ${d.toInt()} m"
                if (s > 150.0 + 30.0) problems += "«${sp.text}» beyond the bike main distance"
            }
        }
        val last = plan.steps.lastIndex
        for (m in 1..last) {
            val exempt = m == last && plan.steps[m].key.key.isArrive && plan.steps[m - 1].distance < 30.0
            if (!exempt && m !in covered) {
                problems += "manoeuvre $m (${plan.steps[m].maneuver.type}) had no prompt while 15–150 m ahead"
            }
        }
        assertTrue(problems.joinToString("\n") + "\nspoken: " + r.spoken.map { "${it.first / 1000}s ${it.second.text}" }, problems.isEmpty())
        assertEquals(0, r.requests.size)
    }
}

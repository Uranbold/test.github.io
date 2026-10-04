package mn.navmn.app.engine

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.qa.QaGpx
import mn.navmn.app.qa.RouteOracle
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.HostFerrostar
import mn.navmn.app.support.Replay
import mn.navmn.app.support.Tracks
import mn.navmn.app.voiceplan.PromptClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ADR-0013 Amendment 2 (QA D1) through the guidance core and the REAL Ferrostar session, on QA's G10 out-and-back route
 * (depart, U-turn, arrive; the return carriageway passes within 50 m of the outbound one):
 *  - step 3: the nearest step wins (tie margin max(10 m, accuracy)), the earliest inside the margin;
 *  - step 4: when the start step was chosen without a usable bearing, the first fix with a usable bearing within 30 s
 *    re-anchors once, forward ([Navigator.initialAt] = `advanceToNextStep`) or backward (a new navigator from the same
 *    stored route), with 0 route requests and at most one extra manoeuvre prompt.
 */
class RestoreBearingRecheckTest {
    @Before
    fun need() = HostFerrostar.require()

    private class G10(val r: Replay) {
        val line: List<LatLon> = r.initial.plan.geometry
        val length = Geo.length(line)
        val uturnAlong = RouteOracle(r.initial.plan).maneuverAlong[1]
        val restoreAt = uturnAlong + (length - uturnAlong) * 0.6
        val onReturn: LatLon = Geo.along(line, restoreAt)

        /** The nearest point of the outbound carriageway (before the U-turn) and its distance along the route. */
        val outboundAlong: Double
        val onOutbound: LatLon
        init {
            var best = 0.0
            var bestD = Double.MAX_VALUE
            var d = 0.0
            while (d < uturnAlong - 40.0) {
                val g = Geo.distance(Geo.along(line, d), onReturn)
                if (g < bestD) {
                    bestD = g
                    best = d
                }
                d += 1.0
            }
            outboundAlong = best
            onOutbound = Geo.along(line, best)
        }
        val gap = Geo.distance(onReturn, onOutbound)

        /** The point between the carriageways at [fromReturnM] metres from the return carriageway. */
        fun between(fromReturnM: Double): LatLon {
            val f = fromReturnM / gap
            return LatLon(onReturn.lat + (onOutbound.lat - onReturn.lat) * f, onReturn.lon + (onOutbound.lon - onReturn.lon) * f)
        }
    }

    private fun g10(): G10 {
        val g = G10(Replay(QaGpx.routeBytes("G10"), TravelMode.CAR, Lang.MN))
        assertEquals("fixture: G10 has depart, U-turn, arrive", 3, g.r.initial.plan.steps.size)
        assertTrue("fixture: carriageways ${g.gap} m apart (needs 15–50 m)", g.gap in 15.0..50.0)
        return g
    }

    private fun maneuverPromptsIn(r: Replay, from: Long, to: Long) =
        r.spoken.filter { it.first in from..to && it.second.cls == PromptClass.MANEUVER }

    private fun startStep(r: Replay) = r.log.firstNotNullOfOrNull { Regex("restore start step (\\d+)").find(it)?.groupValues?.get(1)?.toInt() }

    @Test
    fun standingBetweenTheCarriagewaysThenDrivingTheReturnMovesForwardOnce() {
        val g = g10()
        val standing = listOf(Tracks.fix(g.between(g.gap / 2), 1_000, null, 0.0))
        val driving = Tracks.along(g.line, 8.0, t0 = 2_000, fromM = g.restoreAt)
        g.r.run(standing + driving, tailMs = 10_000, restoredAt = 0)
        assertEquals("tie between the carriageways → the earliest step", 0, startStep(g.r))
        assertTrue(g.r.log.toString(), g.r.log.contains("restore bearing re-check: step 0 -> 1 (forward, 0 requests)"))
        assertEquals(1, g.r.log.count { it.startsWith("restore bearing re-check") })
        assertEquals("0 route requests", 0, g.r.requests.size)
        val at = driving.first().elapsedMs
        assertTrue("at most one prompt after the re-anchor", maneuverPromptsIn(g.r, at, at + 1_000).size <= 1)
        val uturnAfter = g.r.spoken.filter { it.first >= at && it.second.maneuver?.second == 1 }
        assertTrue("no U-turn prompt after the re-anchor: ${uturnAfter.map { it.second.text }}", uturnAfter.isEmpty())
        assertEquals(1, g.r.events.count { it.second == GuidanceEvent.Arrived })
    }

    @Test
    fun standingNearTheReturnSideThenDrivingTheOutboundMovesBackwardWithZeroRequests() {
        val g = g10()
        // GPS error puts the standing car 2 m from the return carriageway; it is really on the outbound one.
        val standing = listOf(Tracks.fix(g.between(2.0), 1_000, null, 0.0))
        val driving = Tracks.along(g.line, 8.0, t0 = 2_000, fromM = g.outboundAlong)
        g.r.run(standing + driving, tailMs = 10_000, restoredAt = 0)
        assertEquals("nearest carriageway without a bearing → the return step", 1, startStep(g.r))
        assertTrue(g.r.log.toString(), g.r.log.contains("restore bearing re-check: step 1 -> 0 (backward, 0 requests)"))
        assertEquals("0 route requests (rebuilt from the stored route)", 0, g.r.requests.size)
        val at = driving.first().elapsedMs
        assertTrue("at most one prompt after the re-anchor", maneuverPromptsIn(g.r, at, at + 1_000).size <= 1)
        // The U-turn is ahead again, so it is announced, and the trip still ends with one arrival.
        assertTrue("U-turn announced after the re-anchor", g.r.spoken.any { it.first >= at && it.second.maneuver?.second == 1 })
        assertEquals(1, g.r.events.count { it.second == GuidanceEvent.Arrived })
    }

    @Test
    fun aStartStepChosenWithABearingIsNotRechecked() {
        val g = g10()
        val driving = Tracks.along(g.line, 8.0, t0 = 1_000, fromM = g.restoreAt)
        g.r.run(driving, tailMs = 5_000, restoredAt = 0)
        assertEquals(1, startStep(g.r))
        assertFalse(g.r.log.toString(), g.r.log.any { it.startsWith("restore bearing re-check") })
    }

    @Test
    fun theRecheckIsDroppedAfter30Seconds() {
        val g = g10()
        val p = g.between(g.gap / 2)
        val standing = (1..32).map { Tracks.fix(p, it * 1_000L, null, 0.0) }
        val driving = Tracks.along(g.line, 8.0, t0 = 33_000, fromM = g.restoreAt, toM = g.restoreAt + 40.0)
        g.r.run(standing + driving, tailMs = 1_000, restoredAt = 0)
        assertEquals(0, startStep(g.r))
        assertTrue(g.r.log.toString(), g.r.log.contains("restore bearing re-check dropped"))
        assertFalse(g.r.log.toString(), g.r.log.any { it.contains(" -> ") && it.startsWith("restore bearing re-check") })
    }
}

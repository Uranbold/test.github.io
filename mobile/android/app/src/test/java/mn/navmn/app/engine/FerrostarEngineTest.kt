package mn.navmn.app.engine

import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.support.Fixtures
import mn.navmn.app.support.HostFerrostar
import mn.navmn.app.support.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M-1 acceptance: the real Ferrostar core (host build) parses a recorded P1 → P3 response and is stepped by fixes. */
class FerrostarEngineTest {
    @Before
    fun need() = HostFerrostar.require()

    @Test
    fun parsesRecordedRouteAndStepsThroughTenFixes() {
        val outcome = RouteProcessor(FerrostarRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0)
        assertTrue(outcome is RouteOutcome.Ok)
        val route = (outcome as RouteOutcome.Ok).route
        assertEquals(4, route.native.stepCount)
        val ferrostar = (route.native as FerrostarRoute).route
        // Valhalla text never reaches Ferrostar's model (ADR-0009 §3.1).
        ferrostar.steps.forEachIndexed { i, s ->
            assertEquals("nav:0:m:$i", s.instruction)
            assertTrue(s.spokenInstructions.isEmpty())
            s.visualInstructions.forEach { v -> assertTrue(v.primaryContent.text.startsWith("nav:0:b:$i:")) }
        }
        val nav = FerrostarNavigator(route.native as FerrostarRoute)
        val fixes = Tracks.along(route.plan.geometry, 14.0).take(10)
        var snap = nav.initial(fixes.first())
        assertEquals(0, snap.stepIndex)
        var prevRemaining = snap.distanceRemaining
        for (f in fixes.drop(1)) {
            snap = nav.update(f)
            assertEquals(Deviation.NONE, snap.deviation)
            assertTrue(snap.distanceRemaining < prevRemaining)
            prevRemaining = snap.distanceRemaining
        }
        assertTrue(snap.distanceRemaining in 4_271.6 - 140.0..4_271.6 - 110.0)
        nav.close()
    }

    @Test
    fun stepAdvanceAndDeviationOnTheRealEngine() {
        val route = (RouteProcessor(FerrostarRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route
        val nav = FerrostarNavigator(route.native as FerrostarRoute)
        val fixes = Tracks.along(route.plan.geometry, 14.0)
        var snap = nav.initial(fixes.first())
        val steps = HashSet<Int>()
        for (f in fixes.drop(1)) {
            snap = nav.update(f)
            steps += snap.stepIndex
        }
        assertTrue("steps seen: $steps", steps.containsAll(listOf(0, 1, 2)))
        // 120 m to the side of the route with good accuracy → completely off route
        val g = route.plan.geometry
        val side = mn.navmn.app.geo.Geo.bearing(g[5], g[6]) + 90.0
        val off = Tracks.fix(mn.navmn.app.geo.Geo.offset(g[5], side, 120.0), fixes.first().elapsedMs + 1_000, 0.0, 10.0)
        val nav2 = FerrostarNavigator(route.native as FerrostarRoute)
        nav2.initial(fixes.first())
        // Ferrostar 0.57.0 reports the deviation of the previous trip state: one fix of lag (GuidanceCore pairs it).
        assertEquals(Deviation.NONE, nav2.update(off).deviation)
        assertEquals(Deviation.COMPLETELY_OFF_ROUTE, nav2.update(off.copy(elapsedMs = off.elapsedMs + 1_000)).deviation)
        // with 60 m accuracy → no deviation (G7, F5), again one update later
        nav2.update(off.copy(accuracyM = 60.0, elapsedMs = off.elapsedMs + 2_000))
        assertEquals(Deviation.NONE, nav2.update(off.copy(accuracyM = 60.0, elapsedMs = off.elapsedMs + 3_000)).deviation)
        nav.close()
        nav2.close()
    }

    /**
     * NAV-005-D1 / D10: a GPS gap over the left turn resumes 150 m past it. The first fix after the gap is held (not
     * trusted, no catch-up: it could be an outlier); the second agreeing fix 1 s later catches up (AC 53: within 2 s).
     */
    @Test
    fun jumpPastAJunctionCatchesUpOnTheRealEngine() {
        val route = (RouteProcessor(FerrostarRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route
        val nav = FerrostarNavigator(route.native as FerrostarRoute)
        val g = route.plan.geometry
        val turnAt = route.plan.steps[0].distance
        val before = Tracks.along(g, 14.0, fromM = 0.0, toM = turnAt - 250.0)
        var snap = nav.initial(before.first())
        for (f in before.drop(1)) snap = nav.update(f)
        assertEquals(0, snap.stepIndex)
        val resumeM = turnAt + 150.0
        val first = Tracks.fix(mn.navmn.app.geo.Geo.along(g, resumeM), before.last().elapsedMs + 30_000, null, 14.0)
        snap = nav.update(first)
        assertEquals("first fix after the gap: no catch-up yet (D10)", 0, snap.stepIndex)
        assertEquals("first fix after the gap is off the current step: not trusted (D9)", false, snap.fixOnCurrentStep)
        assertEquals(0, nav.stepCatchUps)
        val resumeM2 = resumeM + 14.0
        val resume = Tracks.fix(mn.navmn.app.geo.Geo.along(g, resumeM2), first.elapsedMs + 1_000, null, 14.0)
        snap = nav.update(resume)
        assertEquals("upcoming manoeuvre after the jump is the right turn", 1, snap.stepIndex)
        assertEquals(true, snap.fixOnCurrentStep)
        assertEquals(1, nav.stepCatchUps)
        val total = mn.navmn.app.geo.Geo.length(g)
        assertTrue("remaining ${snap.distanceRemaining}, true ${total - resumeM2}", Math.abs(snap.distanceRemaining - (total - resumeM2)) < 30.0)
        // The next update is on route (no OffStepOnRoute lag left behind) and Ferrostar advances normally afterwards.
        val after = Tracks.along(g, 14.0, t0 = resume.elapsedMs + 1_000, fromM = resumeM2 + 14.0)
        val seen = HashSet<Int>()
        for (f in after) {
            snap = nav.update(f)
            seen += snap.stepIndex
            if (!snap.complete && snap.stepIndex <= 1) assertEquals(Deviation.NONE, snap.deviation)
        }
        assertTrue("steps seen after the jump: $seen", seen.contains(2) && !seen.contains(0))
        assertEquals("catch-up only once; normal turns use Ferrostar's condition", 1, nav.stepCatchUps)
        nav.close()
    }

    /** NAV-005-D9 / D10: one good outlier on the next step, during tracking or right after a gap, never skips a step. */
    @Test
    fun singleOutlierOnALaterStepIsFlaggedAndNeverSkipsOnTheRealEngine() {
        val route = (RouteProcessor(FerrostarRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route
        val g = route.plan.geometry
        val turnAt = route.plan.steps[0].distance
        for (gapMs in listOf(0L, 12_000L)) {
            val nav = FerrostarNavigator(route.native as FerrostarRoute)
            val before = Tracks.along(g, 14.0, fromM = 0.0, toM = turnAt - 570.0)
            var snap = nav.initial(before.first())
            for (f in before.drop(1)) snap = nav.update(f)
            val trueM = turnAt - 556.0
            val outlier = Tracks.fix(mn.navmn.app.geo.Geo.along(g, turnAt + 200.0), before.last().elapsedMs + 1_000 + gapMs, null, 14.0)
            snap = nav.update(outlier)
            assertEquals("gap $gapMs: outlier must not skip the left turn", 0, snap.stepIndex)
            assertEquals("gap $gapMs: outlier is not trusted", false, snap.fixOnCurrentStep)
            val after = Tracks.along(g, 14.0, t0 = outlier.elapsedMs + 1_000, fromM = trueM, toM = trueM + 140.0)
            for (f in after) {
                snap = nav.update(f)
                assertEquals(0, snap.stepIndex)
                assertEquals(true, snap.fixOnCurrentStep)
            }
            assertTrue("distance to the left turn ${snap.distanceToNextManeuver}", snap.distanceToNextManeuver in 380.0..440.0)
            assertEquals(0, nav.stepCatchUps)
            nav.close()
        }
    }
}

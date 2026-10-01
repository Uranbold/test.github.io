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
}

package mn.navmn.app.engine

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-005-D1 step catch-up rule (pure; the Ferrostar-backed check is in FerrostarEngineTest). */
class StepCatchUpTest {
    // An L-shaped route in UB: 600 m east, 500 m north, 400 m east, then arrive.
    private val a = LatLon(47.9150, 106.9000)
    private val b = Geo.offset(a, 90.0, 600.0)
    private val c = Geo.offset(b, 0.0, 500.0)
    private val d = Geo.offset(c, 90.0, 400.0)
    private val steps = listOf(listOf(a, b), listOf(b, c), listOf(c, d), listOf(d, d))

    @Test
    fun distanceToLineIsPerpendicularOrToTheNearestEnd() {
        val p = Geo.offset(Geo.offset(a, 90.0, 300.0), 0.0, 40.0)
        assertEquals(40.0, Geo.distanceToLine(p, listOf(a, b)), 0.5)
        val q = Geo.offset(a, 270.0, 30.0)
        assertEquals(30.0, Geo.distanceToLine(q, listOf(a, b)), 0.5)
        assertEquals(Double.POSITIVE_INFINITY, Geo.distanceToLine(p, emptyList()), 0.0)
    }

    @Test
    fun onTheCurrentStepNothingHappens() {
        assertEquals(0, StepCatchUp.stepsToAdvance(Geo.offset(a, 90.0, 590.0), 5.0, steps))
        // just past the junction (within 50 m of the current step): Ferrostar's entry/exit condition owns it
        assertEquals(0, StepCatchUp.stepsToAdvance(Geo.offset(b, 0.0, 40.0), 5.0, steps))
    }

    @Test
    fun aJumpPastTheJunctionAdvancesToTheStepTheFixIsOn() {
        assertEquals(1, StepCatchUp.stepsToAdvance(Geo.offset(b, 0.0, 150.0), 5.0, steps))
        assertEquals(2, StepCatchUp.stepsToAdvance(Geo.offset(c, 90.0, 200.0), 5.0, steps))
    }

    @Test
    fun poorAccuracyOrNoLaterStepNearbyNeverAdvances() {
        assertEquals(0, StepCatchUp.stepsToAdvance(Geo.offset(b, 0.0, 150.0), 26.0, steps))
        assertEquals(0, StepCatchUp.stepsToAdvance(Geo.offset(b, 0.0, 150.0), Double.NaN, steps))
        // completely off route (south of the first street): off-route detection owns it, no step skipping
        assertEquals(0, StepCatchUp.stepsToAdvance(Geo.offset(Geo.offset(a, 90.0, 300.0), 180.0, 120.0), 5.0, steps))
    }

    @Test
    fun theArriveStepIsNeverATargetAndTheLastLegIsLeftToArrival() {
        // near the destination while the current step is the first one: only steps 1 and 2 may be targets
        val nearEnd = Geo.offset(d, 270.0, 10.0)
        val n = StepCatchUp.stepsToAdvance(nearEnd, 5.0, steps)
        assertTrue(n in 1..2)
        assertEquals(2, n)
        // only the current step and arrive are left: never applied
        assertEquals(0, StepCatchUp.stepsToAdvance(Geo.offset(d, 0.0, 200.0), 5.0, steps.takeLast(2)))
    }

    @Test
    fun theFirstMatchingLaterStepWins() {
        // the corner c is on steps 1 and 2: the earlier one is chosen (never skip more than needed)
        assertEquals(1, StepCatchUp.stepsToAdvance(c, 5.0, steps))
    }

    @Test
    fun gateNeedsTwoAgreeingGoodFixesAlsoAfterAGap() {
        val g = StepCatchUp.Gate()
        assertEquals(false, g.decide(null, true, 0))
        assertEquals(false, g.decide(null, true, 1_000))
        // continuous tracking: a single outlier is not enough …
        assertEquals(false, g.decide(2, true, 2_000))
        assertEquals(false, g.decide(null, true, 3_000))
        // … two consecutive fixes on the same later step are
        assertEquals(false, g.decide(2, true, 4_000))
        assertEquals(true, g.decide(2, true, 5_000))
        // disagreeing targets restart the confirmation
        assertEquals(false, g.decide(3, true, 6_000))
        assertEquals(false, g.decide(4, true, 7_000))
        assertEquals(true, g.decide(4, true, 8_000))
        // NAV-005-D10: after a gap (tunnel) the first good fix is NOT applied at once; the next agreeing one is
        assertEquals(false, g.decide(5, true, 20_000))
        assertEquals(true, g.decide(5, true, 21_000))
        // G6c: the first fix after a gap is an outlier, the next true fix is on the current step → never applied
        assertEquals(false, g.decide(6, true, 40_000))
        assertEquals(false, g.decide(null, true, 41_000))
        assertEquals(false, g.decide(6, true, 42_000))
        assertEquals(false, g.decide(null, true, 43_000))
    }

    @Test
    fun gatePoorFixesAreNeutralAndTheConfirmationExpires() {
        val g = StepCatchUp.Gate()
        // a poor fix between two agreeing good fixes neither confirms nor resets
        assertEquals(false, g.decide(2, true, 0))
        assertEquals(false, g.decide(null, false, 1_000))
        assertEquals(true, g.decide(2, true, 2_000))
        // more than 3 s between the two good fixes (GPS lost in between) restarts the confirmation
        assertEquals(false, g.decide(3, true, 10_000))
        assertEquals(false, g.decide(3, true, 14_000))
        assertEquals(true, g.decide(3, true, 15_000))
        // reset() forgets a pending target
        assertEquals(false, g.decide(4, true, 20_000))
        g.reset()
        assertEquals(false, g.decide(4, true, 21_000))
    }

    @Test
    fun offCurrentStepFlagsOnlyGoodFixesFarFromTheCurrentStep() {
        val onStep = Geo.offset(Geo.offset(a, 90.0, 300.0), 0.0, 40.0)
        assertEquals(false, StepCatchUp.offCurrentStep(onStep, 5.0, steps[0]))
        // the G6b outlier: on a later step, far from the current one
        val outlier = Geo.offset(b, 0.0, 200.0)
        assertEquals(true, StepCatchUp.offCurrentStep(outlier, 5.0, steps[0]))
        // poor accuracy is never flagged (Ferrostar's accuracy rule owns it)
        assertEquals(false, StepCatchUp.offCurrentStep(outlier, 26.0, steps[0]))
        assertEquals(false, StepCatchUp.offCurrentStep(outlier, Double.NaN, steps[0]))
    }
}

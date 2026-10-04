package mn.navmn.app.background.restore

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ADR-0013 §3.4 step 3 with Amendment 2: the start step of a restored session from the first good fix (pure, AC 19). */
class RestoreStartStepTest {
    private val a = LatLon(47.9189, 106.9176)
    private val b = Geo.offset(a, 90.0, 400.0) // east
    private val c = Geo.offset(b, 0.0, 400.0) // then north
    private val d = Geo.offset(c, 270.0, 400.0) // then west (parallel to a→b, 400 m north)
    private val steps = listOf(listOf(a, b), listOf(b, c), listOf(c, d), listOf(d, d)) // last = arrive

    /** Divided avenue with a U-turn: east on the south carriageway, back west on the north one, 30 m apart. */
    private val a2 = Geo.offset(a, 0.0, 30.0)
    private val b2 = Geo.offset(b, 0.0, 30.0)
    private val uturn = listOf(listOf(a, b), listOf(b, b2, a2), listOf(a2, a2))
    private val southMid = Geo.offset(a, 90.0, 200.0)
    private val northMid = Geo.offset(a2, 90.0, 200.0)

    private fun choose(p: LatLon, bearing: Double?, steps: List<List<LatLon>>, accuracy: Double = 5.0) =
        RestoreStartStep.choose(p, bearing, accuracy, steps)

    @Test
    fun theOnlyStepWithin50mWins() {
        assertEquals(0, choose(Geo.offset(a, 90.0, 200.0), null, steps))
        assertEquals(1, choose(Geo.offset(b, 0.0, 200.0), null, steps))
        assertEquals(2, choose(Geo.offset(c, 270.0, 200.0), null, steps))
    }

    @Test
    fun aStepBoundaryTieGoesToTheEarlierStep() {
        // At the corner b both step 0 and step 1 are at 0 m: the earliest wins, no manoeuvre is skipped.
        assertEquals(0, choose(b, null, steps))
        // 5 m into step 1: step 0 is 5 m away, inside the 10 m margin → still the earlier step.
        assertEquals(0, choose(Geo.offset(b, 0.0, 5.0), null, steps))
        // 15 m into step 1: outside the margin → step 1.
        assertEquals(1, choose(Geo.offset(b, 0.0, 15.0), null, steps))
    }

    @Test
    fun moreThan50mFromEveryStepIsOffRoute() {
        assertNull(choose(Geo.offset(Geo.offset(a, 90.0, 200.0), 180.0, 60.0), null, steps))
        assertEquals(0, choose(Geo.offset(Geo.offset(a, 90.0, 200.0), 180.0, 40.0), null, steps))
    }

    @Test
    fun theArriveStepIsNeverAStartStep() {
        assertNull(choose(Geo.offset(d, 0.0, 300.0), null, listOf(listOf(a, b), listOf(Geo.offset(d, 0.0, 300.0)))))
    }

    @Test
    fun usableBearingSelectsTheDirectionOnOutAndBackRoutes() {
        // With a usable bearing the direction decides, whichever carriageway is nearer.
        assertEquals(0, choose(northMid, 90.0, uturn))
        assertEquals(1, choose(southMid, 270.0, uturn))
        // QA D1 / TC-D07: standing (no bearing) on the return carriageway → the return step, not the U-turn again.
        assertEquals(1, choose(northMid, null, uturn))
        assertEquals(0, choose(southMid, null, uturn))
        // Exactly between the two carriageways: a tie → the earliest.
        assertEquals(0, choose(Geo.offset(southMid, 0.0, 15.0), null, uturn))
        // Heading gate (ADR-0009 §2): a slow fix or a poor bearing has no usable bearing.
        val fast = Fix(southMid.lat, southMid.lon, 5.0, 270.0, 10.0, 8.0, 0, 0)
        assertEquals(270.0, RestoreStartStep.usableBearing(fast)!!, 0.0)
        assertNull(RestoreStartStep.usableBearing(fast.copy(speedMps = 1.0)))
        assertNull(RestoreStartStep.usableBearing(fast.copy(bearingAccuracyDeg = 60.0)))
    }

    @Test
    fun aPoorerAccuracyWidensTheTieMargin() {
        // 8 m from the north carriageway, 22 m from the south one.
        val p = Geo.offset(northMid, 180.0, 8.0)
        assertEquals(1, choose(p, null, uturn, accuracy = 5.0)) // margin 10 m: 22 > 8 + 10
        assertEquals(0, choose(p, null, uturn, accuracy = 20.0)) // margin 20 m: both → earliest
        assertEquals(listOf(0, 1), RestoreStartStep.candidates(p, null, 20.0, uturn))
        assertEquals(listOf(1), RestoreStartStep.candidates(p, null, 5.0, uturn))
        // An unknown accuracy counts as the 10 m minimum.
        assertEquals(1, choose(p, null, uturn, accuracy = Double.NaN))
        assertEquals(10.0, RestoreStartStep.tieMargin(3.0), 0.0)
        assertEquals(20.0, RestoreStartStep.tieMargin(20.0), 0.0)
    }

    @Test
    fun angleDifference() {
        assertEquals(20.0, RestoreStartStep.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, RestoreStartStep.angleDiff(0.0, 180.0), 1e-9)
    }
}

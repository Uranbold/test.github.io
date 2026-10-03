package mn.navmn.app.background.restore

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ADR-0013 §3.4 step 3: the start step of a restored session from the first good fix (pure, AC 19). */
class RestoreStartStepTest {
    private val a = LatLon(47.9189, 106.9176)
    private val b = Geo.offset(a, 90.0, 400.0) // east
    private val c = Geo.offset(b, 0.0, 400.0) // then north
    private val d = Geo.offset(c, 270.0, 400.0) // then west (parallel to a→b, 400 m north)
    private val steps = listOf(listOf(a, b), listOf(b, c), listOf(c, d), listOf(d, d)) // last = arrive

    @Test
    fun earliestStepWithin50mWins() {
        assertEquals(0, RestoreStartStep.choose(Geo.offset(a, 90.0, 200.0), null, steps))
        assertEquals(1, RestoreStartStep.choose(Geo.offset(b, 0.0, 200.0), null, steps))
        // At the corner b both step 0 and step 1 are within 50 m: the earliest wins, no manoeuvre is skipped.
        assertEquals(0, RestoreStartStep.choose(b, null, steps))
        assertEquals(2, RestoreStartStep.choose(Geo.offset(c, 270.0, 200.0), null, steps))
    }

    @Test
    fun moreThan50mFromEveryStepIsOffRoute() {
        assertNull(RestoreStartStep.choose(Geo.offset(Geo.offset(a, 90.0, 200.0), 180.0, 60.0), null, steps))
        assertEquals(0, RestoreStartStep.choose(Geo.offset(Geo.offset(a, 90.0, 200.0), 180.0, 40.0), null, steps))
    }

    @Test
    fun theArriveStepIsNeverAStartStep() {
        assertNull(RestoreStartStep.choose(Geo.offset(d, 0.0, 300.0), null, listOf(listOf(a, b), listOf(Geo.offset(d, 0.0, 300.0)))))
    }

    @Test
    fun usableBearingSelectsTheDirectionOnOutAndBackRoutes() {
        val out = listOf(listOf(a, b), listOf(b, a), listOf(a, a)) // east, then back west on the same road
        val mid = Geo.offset(a, 90.0, 200.0)
        assertEquals(0, RestoreStartStep.choose(mid, 90.0, out))
        assertEquals(1, RestoreStartStep.choose(mid, 270.0, out))
        assertEquals(0, RestoreStartStep.choose(mid, null, out))
        // Heading gate (ADR-0009 §2): a slow fix or a poor bearing has no usable bearing.
        val fast = Fix(mid.lat, mid.lon, 5.0, 270.0, 10.0, 8.0, 0, 0)
        assertEquals(270.0, RestoreStartStep.usableBearing(fast)!!, 0.0)
        assertNull(RestoreStartStep.usableBearing(fast.copy(speedMps = 1.0)))
        assertNull(RestoreStartStep.usableBearing(fast.copy(bearingAccuracyDeg = 60.0)))
    }

    @Test
    fun angleDifference() {
        assertEquals(20.0, RestoreStartStep.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, RestoreStartStep.angleDiff(0.0, 180.0), 1e-9)
    }
}

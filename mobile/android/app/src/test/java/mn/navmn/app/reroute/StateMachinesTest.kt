package mn.navmn.app.reroute

import mn.navmn.app.arrival.ArrivalDetector
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.gps.GpsMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0009 §4 detection and §5 GPS / arrival state machines (AC 41, 51–53, 55–56). */
class StateMachinesTest {
    @Test
    fun offRouteNeedsThreeGoodFixesOverTwoSeconds() {
        val d = OffRouteDetector()
        assertNull(d.onFix(true, true, 0))
        assertNull(d.onFix(true, true, 1_000))
        assertEquals(OffRouteDetector.Event.STARTED, d.onFix(true, true, 2_000))
        assertTrue(d.inEpisode)
        assertNull(d.onFix(true, false, 3_000))
        assertEquals(OffRouteDetector.Event.ENDED, d.onFix(true, false, 4_000))
    }

    @Test
    fun singleOutlierNeverStartsAnEpisode() {
        val d = OffRouteDetector()
        for (t in 0L..60_000L step 1_000) {
            val outlier = t == 30_000L
            assertNull(d.onFix(true, outlier, t))
        }
        assertFalse(d.inEpisode)
    }

    @Test
    fun poorFixesNeitherCountNorReset() {
        val d = OffRouteDetector()
        d.onFix(true, true, 0)
        d.onFix(false, false, 500)
        d.onFix(true, true, 1_000)
        assertEquals(OffRouteDetector.Event.STARTED, d.onFix(true, true, 2_000))
    }

    @Test
    fun threeFixesTooCloseInTimeWaitForTheSpan() {
        val d = OffRouteDetector()
        d.onFix(true, true, 0)
        d.onFix(true, true, 500)
        assertNull(d.onFix(true, true, 1_000))
        assertNull(d.onFix(true, true, 1_500))
        assertEquals(OffRouteDetector.Event.STARTED, d.onFix(true, true, 2_500))
    }

    @Test
    fun gpsLostAfterTenSecondsAndRestoredByAGoodFix() {
        val g = GpsMonitor()
        g.start(0)
        assertNull(g.onTick(9_999))
        assertTrue(g.ok(9_999))
        assertEquals(GpsMonitor.Event.LOST, g.onTick(10_000))
        assertNull(g.onTick(40_000))
        assertFalse(g.ok(40_000))
        assertEquals(GpsMonitor.Event.RESTORED, g.onGoodFix(40_500))
        assertNull(g.onGoodFix(41_500))
        assertTrue(g.ok(41_500))
    }

    /** Last leg of a 5-step route (steps 0..4, 4 = arrive): current step 3. */
    private fun ArrivalDetector.at(complete: Boolean, offRoute: Boolean, good: Boolean, remaining: Double, p: LatLon, end: LatLon, step: Int = 3, trusted: Boolean = true) =
        check(complete, offRoute, good, trusted, remaining, p, end, step, 4)

    @Test
    fun arrivalFiresOnceIncludingStationaryBesideTheEnd() {
        val end = LatLon(47.8858, 106.9173)
        val a = ArrivalDetector()
        val far = Geo.offset(end, 0.0, 200.0)
        assertFalse(a.at(false, false, true, 200.0, far, end))
        val beside = Geo.offset(end, 90.0, 10.0)
        assertTrue(a.at(false, true, true, 80.0, beside, end))
        repeat(30) { assertFalse(a.at(false, false, true, 0.0, beside, end)) }
    }

    @Test
    fun arrivalByRemainingDistanceOrComplete() {
        val end = LatLon(47.8858, 106.9173)
        val p = Geo.offset(end, 0.0, 100.0)
        assertTrue(ArrivalDetector().at(false, false, true, 29.0, p, end))
        assertFalse(ArrivalDetector().at(false, true, true, 29.0, p, end))
        assertFalse(ArrivalDetector().at(false, false, false, 29.0, p, end))
        assertTrue(ArrivalDetector().at(true, false, false, 500.0, p, end))
    }

    /** ADR-0009 Amendment 1 / Amendment 3 §5, NAV-005 review probe: rule (c) only while the next manoeuvre is `arrive`. */
    @Test
    fun straightLineRuleOnlyOnTheLastLeg() {
        val end = LatLon(47.8858, 106.9173)
        val near = Geo.offset(end, 180.0, 20.0)
        // a good fix 20 m from the end while still on step 1 of 4 (route passes close to its end, or one outlier)
        val a = ArrivalDetector()
        assertFalse(a.at(false, false, true, 3_965.0, near, end, step = 1))
        assertFalse(a.at(false, false, true, 3_965.0, near, end, step = 2))
        assertFalse(a.arrived)
        // the upcoming manoeuvre is arrive (current step = last − 1): fires
        assertTrue(a.at(false, false, true, 60.0, near, end, step = 3))
        // Ferrostar's Complete state reports the arrive step itself: still the last leg
        assertTrue(ArrivalDetector().at(false, false, true, 60.0, near, end, step = 4))
        // rule (a) is not gated
        assertTrue(ArrivalDetector().at(true, false, true, 3_965.0, Geo.offset(end, 0.0, 2_000.0), end, step = 1))
    }

    /** ADR-0009 Amendment 3 §5: rule (b) uses only a trusted fix (an untrusted fix is snapped to the step end). */
    @Test
    fun remainingDistanceRuleNeedsATrustedFix() {
        val end = LatLon(47.8858, 106.9173)
        val p = Geo.offset(end, 0.0, 300.0)
        val a = ArrivalDetector()
        assertFalse(a.at(false, false, true, 5.0, p, end, trusted = false))
        assertFalse(a.arrived)
        assertTrue(a.at(false, false, true, 5.0, p, end, trusted = true))
    }
}

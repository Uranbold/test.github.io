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

    @Test
    fun arrivalFiresOnceIncludingStationaryBesideTheEnd() {
        val end = LatLon(47.8858, 106.9173)
        val a = ArrivalDetector()
        val far = Geo.offset(end, 0.0, 200.0)
        assertFalse(a.check(false, false, true, 200.0, far, end))
        val beside = Geo.offset(end, 90.0, 10.0)
        assertTrue(a.check(false, true, true, 80.0, beside, end))
        repeat(30) { assertFalse(a.check(false, false, true, 0.0, beside, end)) }
    }

    @Test
    fun arrivalByRemainingDistanceOrComplete() {
        val end = LatLon(47.8858, 106.9173)
        val p = Geo.offset(end, 0.0, 100.0)
        assertTrue(ArrivalDetector().check(false, false, true, 29.0, p, end))
        assertFalse(ArrivalDetector().check(false, true, true, 29.0, p, end))
        assertFalse(ArrivalDetector().check(false, false, false, 29.0, p, end))
        assertTrue(ArrivalDetector().check(true, false, false, 500.0, p, end))
    }
}

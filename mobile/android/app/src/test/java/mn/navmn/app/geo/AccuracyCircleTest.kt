package mn.navmn.app.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-005 AC 74 / AC 71: the accuracy polygon radius within ±10 % at UB latitude (mirrors web `circle.test.ts`). */
class AccuracyCircleTest {
    private val ub = LatLon(47.9189, 106.9176)

    @Test
    fun ac74_radiusWithinTenPercentAtThirtyAndHundredMetres() {
        for (r in listOf(30.0, 100.0)) {
            val ring = AccuracyCircle.ring(ub, r)
            assertEquals("64 vertices + closing point", AccuracyCircle.VERTICES + 1, ring.size)
            assertEquals("ring is closed", ring.first(), ring.last())
            for (p in ring) {
                val d = Geo.distance(ub, p)
                assertTrue("radius $d m for $r m", d >= r * 0.9 && d <= r * 1.1)
            }
        }
    }

    /** map-style §7.8: the circle appears from z15.75 for 10 m and z12.43 for 100 m at UB (dot outer radius 10.5 dp). */
    @Test
    fun ac74_minZoomHidesTheCircleWhileItIsSmallerThanTheDot() {
        assertEquals(16.75, AccuracyCircle.minZoom(10.5, 47.92, 5.0), 0.01)
        assertEquals(15.75, AccuracyCircle.minZoom(10.5, 47.92, 10.0), 0.01)
        assertEquals(14.43, AccuracyCircle.minZoom(10.5, 47.92, 25.0), 0.01)
        assertEquals(12.43, AccuracyCircle.minZoom(10.5, 47.92, 100.0), 0.01)
        assertEquals("no accuracy: never drawn", 24.0, AccuracyCircle.minZoom(10.5, 47.92, 0.0), 0.0)
    }
}

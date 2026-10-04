package mn.navmn.app.location

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAV-005 section N, AC 71: the browse display filter with a fake clock (1 Hz synthetic fixes; the clock is the fix
 * time unless a test advances it on its own). Every AC 75 and AC 77 example is one test; plus the AC 76 gate, stale and
 * recovery timing, reset, and AC 79 (no coordinates in toString).
 */
class LocationDisplayFilterTest {
    private val base = LatLon(47.9189, 106.9176)

    private fun at(bearing: Double, m: Double, from: LatLon = base) = Geo.offset(from, bearing, m)

    private fun fix(p: LatLon, t: Long, acc: Double = 10.0, speed: Double? = null) =
        Fix(p.lat, p.lon, acc, speedMps = speed, elapsedMs = t, wallTimeMs = 0L)

    private class Run(val f: LocationDisplayFilter = LocationDisplayFilter()) {
        var out: DisplayLocation? = null
        fun feed(x: Fix, now: Long = x.elapsedMs): DisplayLocation? = f.onFix(x, now).also { out = it }
    }

    private fun dist(a: LatLon, b: LatLon) = Geo.distance(a, b)

    // ------------------------------------------------------------------------------------------- AC 76 gate

    @Test
    fun ac76_noShowableFixYetMeansNoDot() {
        val r = Run()
        assertNull(r.feed(fix(base, 0, acc = 150.0)))
        assertNull("unknown accuracy is not shown", r.feed(fix(base, 1_000, acc = Double.NaN)))
        assertNull(r.feed(fix(base, 2_000, acc = Double.POSITIVE_INFINITY)))
        assertNull(r.f.tick(30_000))
        val first = r.feed(fix(base, 3_000, acc = 100.0))
        assertNotNull("100 m is showable", first)
        assertEquals(base, first!!.latLon)
        assertEquals(100.0, first.accuracyM, 0.0)
        assertFalse(first.stale)
    }

    @Test
    fun ac76_poorFixesMoveNothingAndDoNotResetTheStaleTimer() {
        val r = Run()
        r.feed(fix(base, 0, acc = 20.0))
        for (t in 1..9) {
            val d = r.feed(fix(at(90.0, 300.0), t * 1_000L, acc = 120.0))!!
            assertEquals("AC 76: dot stays", base, d.latLon)
            assertEquals("circle keeps the shown fix's accuracy", 20.0, d.accuracyM, 0.0)
            assertFalse(d.stale)
        }
        // 10 s after the last showable fix (t = 0) → stale within 1 s, although poor fixes kept arriving.
        assertFalse(r.feed(fix(at(90.0, 300.0), 10_000, acc = 120.0))!!.stale)
        assertTrue("stale after 10 s without a showable fix", r.feed(fix(at(90.0, 300.0), 10_001, acc = Double.NaN))!!.stale)
        assertEquals(base, r.out!!.latLon)
    }

    @Test
    fun ac76_staleAfterTenSecondsByTheTimerAndBackOnTheNextShowableFix() {
        val r = Run()
        r.feed(fix(base, 1_000, acc = 15.0, speed = 0.0))
        assertFalse(r.f.tick(11_000)!!.stale)
        val stale = r.f.tick(11_001)!!
        assertTrue(stale.stale)
        assertEquals("stale at the last shown position", base, stale.latLon)
        // The next showable fix (held: standing, within r) ends the stale state at once; the dot moves 0 m.
        val back = r.feed(fix(at(0.0, 5.0), 30_000, acc = 15.0, speed = 0.0))!!
        assertFalse("AC 76: normal again within 2 s", back.stale)
        assertEquals(base, back.latLon)
        assertEquals(30_000, back.lastShowableElapsedMs)
    }

    @Test
    fun resetGoesBackToNoShownPosition() {
        val r = Run()
        r.feed(fix(base, 0))
        r.f.reset()
        assertNull(r.f.current(1_000))
        assertNull(r.f.tick(20_000))
        val far = at(45.0, 2_000.0)
        assertEquals("after a reset the next showable fix is a first fix (no jump test)", far, r.feed(fix(far, 1_000))!!.latLon)
    }

    // ------------------------------------------------------------------------------------------- AC 75 examples

    /** (a) 120 s standing, speed 0 or absent, accuracy 15–35 m, every fix within its own r of the first fix → 0 m. */
    @Test
    fun ac75a_standingTwoMinutesMovesTheDotZero() {
        val r = Run()
        r.feed(fix(base, 0, acc = 25.0, speed = 0.0))
        for (i in 1..120) {
            val acc = 15.0 + (i * 7 % 21) // 15..35
            val p = at((i * 47.0) % 360.0, acc * 0.9) // scatter inside its own r
            val d = r.feed(fix(p, i * 1_000L, acc = acc, speed = if (i % 3 == 0) null else 0.0))!!
            assertEquals("fix $i: dot moved", 0.0, dist(base, d.latLon), 0.0)
            assertFalse(d.stale)
        }
    }

    /** (b) as (a) with one isolated fix at d > r → 0 m. */
    @Test
    fun ac75b_oneIsolatedOutlierInsideTheJumpLimitMovesTheDotZero() {
        val r = Run()
        r.feed(fix(base, 0, acc = 20.0, speed = 0.0))
        for (i in 1..60) {
            val p = if (i == 30) at(120.0, 45.0) else at((i * 31.0) % 360.0, 12.0)
            val d = r.feed(fix(p, i * 1_000L, acc = 20.0, speed = if (i % 2 == 0) null else 0.0))!!
            assertEquals("fix $i", base, d.latLon)
        }
    }

    /** (c) from a hold, a 1.4 m/s walk with speed reported and accuracy 20–30 m → moves within 3 s, then on every fix. */
    @Test
    fun ac75c_walkingWithReportedSpeedStartsMovingWithinThreeSeconds() {
        val r = Run()
        r.feed(fix(base, 0, acc = 25.0, speed = 0.0))
        for (i in 1..10) r.feed(fix(at(10.0 * i, 5.0), i * 1_000L, acc = 25.0, speed = 0.0))
        assertEquals(base, r.out!!.latLon)
        val walkStart = 10_000L
        var firstMove: Long? = null
        var prev = r.out!!.latLon
        for (k in 1..30) {
            val t = walkStart + k * 1_000L
            val p = at(90.0, 1.4 * k)
            val d = r.feed(fix(p, t, acc = 20.0 + (k % 11), speed = 1.4))!!
            if (firstMove == null && d.latLon != base) firstMove = t
            if (firstMove != null) {
                assertEquals("moves on every walking fix ($k)", p, d.latLon)
                assertTrue(d.latLon != prev)
            }
            prev = d.latLon
        }
        assertNotNull(firstMove)
        assertTrue("first move ${firstMove!! - walkStart} ms after the walk starts", firstMove - walkStart <= 3_000)
    }

    /** (d) a 1.4 m/s walk with no speed reported, accuracy 20 m → never more than 25 m behind the latest fix. */
    @Test
    fun ac75d_walkingWithoutSpeedLagsAtMostTwentyFiveMetres() {
        val r = Run()
        var worst = 0.0
        for (k in 0..300) {
            val p = at(90.0, 1.4 * k)
            val d = r.feed(fix(p, k * 1_000L, acc = 20.0, speed = null))!!
            worst = maxOf(worst, dist(d.latLon, p))
        }
        assertTrue("worst lag $worst m", worst <= 25.0)
        assertTrue("the dot is not glued to every fix (it holds inside r)", worst > 0.0)
    }

    /** (e) standing on a 90 m fix, then 5 m fixes ~40 m away → the dot moves on the second such fix (≤ 2 s). */
    @Test
    fun ac75e_movesOffAPoorFirstFixOnTheSecondGoodFix() {
        val r = Run()
        r.feed(fix(base, 0, acc = 90.0, speed = 0.0))
        r.feed(fix(at(200.0, 30.0), 1_000, acc = 90.0, speed = 0.0)) // holding
        val good = at(0.0, 40.0)
        val g1 = r.feed(fix(good, 2_000, acc = 5.0, speed = 0.0))!!
        assertEquals("first good fix: 0 m", base, g1.latLon)
        val g2p = at(10.0, 1.0, good)
        val g2 = r.feed(fix(g2p, 3_000, acc = 5.0, speed = 0.0))!!
        assertEquals("second good fix: moved", g2p, g2.latLon)
        assertEquals(5.0, g2.accuracyM, 0.0)
    }

    /** (e) variant (interpretation ii): the hold starts on the first good fix itself; still moves on the second. */
    @Test
    fun ac75e_alsoWhenTheHoldStartsOnTheFirstGoodFix() {
        val r = Run()
        r.feed(fix(base, 0, acc = 90.0, speed = 0.0))
        val good = at(0.0, 40.0)
        assertEquals(base, r.feed(fix(good, 1_000, acc = 5.0, speed = 0.0))!!.latLon)
        assertEquals(good, r.feed(fix(good, 2_000, acc = 5.0, speed = 0.0))!!.latLon)
    }

    @Test
    fun ac75_slowSpeedBetweenHoldAndReleaseKeepsTheHold() {
        val r = Run()
        r.feed(fix(base, 0, acc = 10.0, speed = 0.0))
        r.feed(fix(at(0.0, 3.0), 1_000, acc = 10.0, speed = 0.2)) // enters the hold
        for (i in 2..10) assertEquals(base, r.feed(fix(at(0.0, 3.0), i * 1_000L, acc = 10.0, speed = 0.8))!!.latLon)
        val p = at(0.0, 4.0)
        assertEquals("one fix ≥ 1.0 m/s releases", p, r.feed(fix(p, 11_000, acc = 10.0, speed = 1.0))!!.latLon)
    }

    @Test
    fun ac75_poorFixesDoNotCountAsConsecutiveOutsideFixes() {
        val r = Run()
        r.feed(fix(base, 0, acc = 10.0, speed = 0.0))
        r.feed(fix(base, 1_000, acc = 10.0, speed = 0.0)) // hold
        val out1 = at(90.0, 30.0)
        assertEquals(base, r.feed(fix(out1, 2_000, acc = 10.0, speed = 0.0))!!.latLon)
        r.feed(fix(at(90.0, 31.0), 3_000, acc = 150.0, speed = 0.0)) // not showable: does not count, does not break
        val out2 = at(90.0, 31.0)
        assertEquals("second consecutive showable outside fix releases", out2, r.feed(fix(out2, 4_000, acc = 10.0, speed = 0.0))!!.latLon)
    }

    // ------------------------------------------------------------------------------------------- AC 77 examples

    /** (a) standing (speed 0, accuracy 10 m), one fix 300 m away, then fixes back at the old place → 0 m. */
    @Test
    fun ac77a_singleFarOutlierMovesTheDotZero() {
        val r = Run()
        r.feed(fix(base, 0, acc = 10.0, speed = 0.0))
        for (i in 1..5) r.feed(fix(at(i * 60.0, 3.0), i * 1_000L, acc = 10.0, speed = 0.0))
        assertEquals(base, r.feed(fix(at(45.0, 300.0), 6_000, acc = 10.0, speed = 0.0))!!.latLon)
        for (i in 7..20) assertEquals("fix $i", base, r.feed(fix(at(i * 25.0, 4.0), i * 1_000L, acc = 10.0, speed = 0.0))!!.latLon)
    }

    /** (b) standing, one fix 80 m off with accuracy 20 m between normal fixes → 0 m. */
    @Test
    fun ac77b_eightyMetreOutlierMovesTheDotZero() {
        val r = Run()
        r.feed(fix(base, 0, acc = 20.0, speed = 0.0))
        for (i in 1..20) {
            val p = if (i == 10) at(300.0, 80.0) else at(i * 17.0, 6.0)
            assertEquals("fix $i", base, r.feed(fix(p, i * 1_000L, acc = 20.0, speed = 0.0))!!.latLon)
        }
    }

    /** (c) a real 300 m relocation confirmed by the next fix → the dot moves to the confirming fix ≤ 2 s after J. */
    @Test
    fun ac77c_confirmedRelocationIsShown() {
        val r = Run()
        r.feed(fix(base, 0, acc = 10.0, speed = 0.0))
        r.feed(fix(base, 1_000, acc = 10.0, speed = 0.0))
        val j = at(45.0, 300.0)
        assertEquals("J pending: 0 m", base, r.feed(fix(j, 2_000, acc = 10.0, speed = 0.0))!!.latLon)
        val k = at(45.0, 8.0, j)
        val d = r.feed(fix(k, 3_000, acc = 10.0, speed = 0.0))!!
        assertEquals("moved to the confirming fix 1 s after J", k, d.latLon)
        // AC 75 continues from there: standing next to K holds.
        assertEquals(k, r.feed(fix(at(0.0, 3.0, k), 4_000, acc = 10.0, speed = 0.0))!!.latLon)
    }

    @Test
    fun ac77_confirmationOutsideTheFiveSecondWindowDiscardsTheJump() {
        val r = Run()
        r.feed(fix(base, 0, acc = 10.0, speed = 0.0))
        val j = at(45.0, 300.0)
        r.feed(fix(j, 1_000, acc = 10.0, speed = 0.0))
        // K 6 s after J, near J: J is discarded; K is tested against the shown position (about 300 m in 7 s since the
        // previous non-jump fix = 43 m/s, not a jump).
        val k = at(0.0, 2.0, j)
        val d = r.feed(fix(k, 7_000, acc = 10.0, speed = 0.0))!!
        assertEquals("handled by AC 75 (standing → hold, first outside fix)", base, d.latLon)
    }

    /** (d) after 60 s without any fix, a fix 500 m away (8.3 m/s) is not a jump; it moves on the third far fix (iii). */
    @Test
    fun ac77d_farFixAfterALongGapIsNotAJump() {
        val r = Run()
        r.feed(fix(base, 0, acc = 10.0, speed = 0.0))
        r.feed(fix(base, 1_000, acc = 10.0, speed = 0.0))
        val far = at(90.0, 500.0)
        val t0 = 61_000L
        assertEquals("first far fix: AC 75 hold, 0 m", base, r.feed(fix(far, t0, acc = 10.0, speed = 0.0))!!.latLon)
        val far2 = at(0.0, 3.0, far)
        assertEquals("second far fix: pending under AC 77 (500 m in 1 s from the first far fix)", base, r.feed(fix(far2, t0 + 1_000, acc = 10.0, speed = 0.0))!!.latLon)
        val far3 = at(180.0, 2.0, far)
        val d = r.feed(fix(far3, t0 + 2_000, acc = 10.0, speed = 0.0))!!
        assertEquals("third far fix confirms: moved 2 s after the first far fix", far3, d.latLon)
    }

    @Test
    fun ac77_duplicateTimestampsFromTwoProvidersCountAsInfiniteSpeed() {
        val r = Run()
        r.feed(fix(base, 0, acc = 8.0, speed = 2.0))
        r.feed(fix(at(90.0, 2.0), 1_000, acc = 8.0, speed = 2.0))
        val wild = at(90.0, 70.0)
        val d = r.feed(fix(wild, 1_000, acc = 30.0, speed = 2.0))!! // same fix time, 70 m off: 70 > max(50, 60)
        assertEquals(at(90.0, 2.0), d.latLon)
    }

    @Test
    fun ac77_movingFixesAreNotJumps() {
        val r = Run()
        // A car at 20 m/s with speed reported: every fix is shown (20 m/s < 50 m/s, and 20 m < 50 m anyway).
        for (k in 0..30) {
            val p = at(0.0, 20.0 * k)
            assertEquals(p, r.feed(fix(p, k * 1_000L, acc = 8.0, speed = 20.0))!!.latLon)
        }
    }

    // ------------------------------------------------------------------------------------------- AC 79

    @Test
    fun ac79_toStringHasNoCoordinates() {
        val d = DisplayLocation(LatLon(47.918912, 106.917634), 12.5, false, 1_000)
        assertFalse(Regex("-?\\d{1,3}\\.\\d{4,}").containsMatchIn(d.toString()))
    }
}

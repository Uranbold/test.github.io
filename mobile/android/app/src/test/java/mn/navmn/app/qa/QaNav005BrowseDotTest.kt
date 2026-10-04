package mn.navmn.app.qa

import mn.navmn.app.geo.AccuracyCircle
import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.DisplayLocation
import mn.navmn.app.location.Fix
import mn.navmn.app.location.LocationDisplayFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * QA, NAV-005 section N (D171–D178), test plan `docs/qa/test-plans/NAV-005.md` §5a, cases TC-N01…TC-N20.
 *
 * Written from the AC 74–77 text, not from the implementation: QA's own haversine and local offset (not
 * `mn.navmn.app.geo.Geo`), its own fix generator and the PO starting values of D176 typed in from the story (not read
 * from `BrowseDotRules`), so a changed constant fails here as well. The filter is driven with a fake clock (fix times).
 * Boundaries of every threshold are probed on both sides where the AC text is unambiguous. The architect's readings
 * (ADR-0009 Amendment 7 §9.2 (i)–(iii)) are only relied on where marked.
 */
class QaNav005BrowseDotTest {
    // UB, Sükhbaatar Square area (P1). Distinct from the mobile tests' base on purpose.
    private val p0 = LatLon(47.9185, 106.9170)

    // ------------------------------------------------------------------------------------------- QA geometry

    private val earthR = 6_371_008.8

    private fun hav(a: LatLon, b: LatLon): Double {
        val dLat = (b.lat - a.lat) * PI / 180
        val dLon = (b.lon - a.lon) * PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthR * asin(sqrt(h))
    }

    /** [east] / [north] metres from [from] (local tangent plane; exact enough below a few km). */
    private fun en(east: Double, north: Double, from: LatLon = p0) = LatLon(
        from.lat + north / earthR * 180 / PI,
        from.lon + east / (earthR * cos(from.lat * PI / 180)) * 180 / PI,
    )

    private fun fix(p: LatLon, tMs: Long, acc: Double, speed: Double? = null) =
        Fix(p.lat, p.lon, acc, speedMps = speed, elapsedMs = tMs, wallTimeMs = 0L)

    /** Deterministic pseudo-random sequence (LCG), so failures are reproducible. */
    private class Lcg(var s: Long) {
        fun next(): Double {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            return ((s ushr 11).toDouble() / (1L shl 53).toDouble())
        }
    }

    private class Drive(val f: LocationDisplayFilter = LocationDisplayFilter()) {
        var out: DisplayLocation? = null
        val shownTrail = ArrayList<LatLon?>()
        fun feed(x: Fix, now: Long = x.elapsedMs): DisplayLocation? = f.onFix(x, now).also {
            out = it
            shownTrail += it?.latLon
        }
    }

    private fun assertAt(msg: String, expected: LatLon, actual: DisplayLocation?, tolM: Double = 0.01) {
        assertNotNull("$msg: no shown position", actual)
        val d = hav(expected, actual!!.latLon)
        assertTrue("$msg: shown position is ${"%.2f".format(d)} m from the expected one", d <= tolM)
    }

    /** A held start: the dot shown at [p0] (accuracy [acc]) and standing for 3 s (speed 0), ending at t = 0. */
    private fun standingAtP0(acc: Double = 10.0): Drive {
        val d = Drive()
        for (t in -3..0) d.feed(fix(p0, t * 1_000L, acc, 0.0))
        assertAt("setup", p0, d.out)
        return d
    }

    // ------------------------------------------------------------------------------------------- AC 76 gate

    /** TC-N01, AC 76: accuracy ≤ 100 m is showable, worse than 100 m or not reported is not; no dot before the first. */
    @Test
    fun tcN01_accuracyGateBoundariesAndNoDotBeforeTheFirstShowableFix() {
        val d = Drive()
        assertNull("100.01 m: not shown", d.feed(fix(p0, 0, 100.01)))
        assertNull("NaN (not reported): not shown", d.feed(fix(p0, 1_000, Double.NaN)))
        assertNull("150 m: not shown", d.feed(fix(p0, 2_000, 150.0)))
        assertNull("no dot, no circle before the first showable fix (also 30 s later)", d.f.tick(32_000))
        val first = d.feed(fix(en(5.0, 5.0), 3_000, 100.0))
        assertAt("100.0 m exactly is showable (\"worse than 100 m\" is not)", en(5.0, 5.0), first)
        assertEquals("AC 74: circle radius = the shown fix's accuracy", 100.0, first!!.accuracyM, 0.0)
        assertFalse(first.stale)
    }

    /** TC-N02, AC 76: a non-showable fix moves dot and circle 0 m and does not reset the stale timer. */
    @Test
    fun tcN02_poorFixesChangeNothingAndDoNotKeepTheDotFresh() {
        val d = standingAtP0(acc = 15.0)
        for (t in 1..12) {
            val o = d.feed(fix(en(200.0, -80.0), t * 1_000L, if (t % 2 == 0) 101.0 else Double.NaN, 3.0))
            assertAt("t=$t s: poor fix moves the dot 0 m", p0, o)
            assertEquals("t=$t s: circle keeps the shown fix's accuracy", 15.0, o!!.accuracyM, 0.0)
            if (t <= 9) assertFalse("t=$t s: not stale before 10 s", o.stale)
            if (t >= 11) assertTrue("t=$t s: stale within 1 s after 10 s without a showable fix, poor fixes notwithstanding", o.stale)
        }
    }

    /** TC-N03, AC 76: stale by 11 s after the last showable fix, at the last shown position; normal again on the next one. */
    @Test
    fun tcN03_staleAfterTenSecondsAndNormalAgainWithoutMovingAHeldDot() {
        val d = standingAtP0()
        assertFalse("9.9 s: not stale", d.f.tick(9_900)!!.stale)
        val s = d.f.tick(11_000)!!
        assertTrue("11 s: stale (≤ 1 s after the 10 s mark)", s.stale)
        assertAt("stale variant stays at the last shown position", p0, s)
        val back = d.feed(fix(en(3.0, 0.0), 11_500, 10.0, 0.0))
        assertFalse("AC 76: normal variant again on the next showable fix (≤ 2 s)", back!!.stale)
        assertAt("the recovery fix is within r while standing: the dot moves 0 m (AC 75)", p0, back)
    }

    /** TC-N04, AC 76 ("no showable fix (held or not)"): a held fix and a pending jump both keep the dot fresh. */
    @Test
    fun tcN04_heldFixesAndPendingJumpsKeepTheDotFresh() {
        val d = standingAtP0()
        for (t in 1..8) assertFalse(d.feed(fix(en(2.0, 0.0), t * 1_000L, 10.0, 0.0))!!.stale)
        // t = 9 s: a jump (300 m in 1 s) is pending; it is a showable fix.
        assertAt("jump held", p0, d.feed(fix(en(300.0, 0.0), 9_000, 10.0, 0.0)))
        assertFalse("18.5 s: 9.5 s after the pending jump, not stale", d.f.tick(18_500)!!.stale)
    }

    // ------------------------------------------------------------------------------------------- AC 75 hold

    /** TC-N05, AC 75 example (a): 120 s standing, accuracy 15–35 m, each fix within its own r of the first → 0 m. */
    @Test
    fun tcN05_example75a_standingTwoMinutesMovesTheDotZero() {
        val rnd = Lcg(75_001)
        val d = Drive()
        d.feed(fix(p0, 0, 25.0, 0.0))
        for (t in 1..120) {
            val acc = 15.0 + 20.0 * rnd.next()
            val dist = acc * 0.95 * rnd.next() // within r = max(acc, 10) of the first shown fix
            val brg = 2 * PI * rnd.next()
            val speed = if (t % 3 == 0) null else 0.0 // speed 0 or absent
            d.feed(fix(en(dist * sin(brg), dist * cos(brg)), t * 1_000L, acc, speed))
            assertAt("t=$t s", p0, d.out)
        }
    }

    /** TC-N06, AC 75 example (b): as (a) with one isolated fix at d > r (not a jump) → 0 m. */
    @Test
    fun tcN06_example75b_oneIsolatedOutsideFixMovesTheDotZero() {
        val d = Drive()
        d.feed(fix(p0, 0, 20.0, 0.0))
        for (t in 1..60) {
            // t = 30: 45 m away with accuracy 18 m: d > r (18), d < 50 m so not a jump under AC 77.
            val p = if (t == 30) en(0.0, 45.0) else en(4.0 * sin(t.toDouble()), 4.0 * cos(t.toDouble()))
            d.feed(fix(p, t * 1_000L, if (t == 30) 18.0 else 20.0, if (t % 2 == 0) 0.0 else null))
            assertAt("t=$t s", p0, d.out)
        }
    }

    /** TC-N07, AC 75 example (c): from a hold, a 1.4 m/s walk with speed reported → moving ≤ 3 s, then on every fix. */
    @Test
    fun tcN07_example75c_walkWithSpeedStartsMovingWithinThreeSeconds() {
        val rnd = Lcg(75_003)
        val d = standingAtP0(acc = 25.0)
        for (t in 1..20) d.feed(fix(en(3.0 * rnd.next(), 3.0 * rnd.next()), t * 1_000L, 25.0, 0.0))
        assertAt("held before the walk", p0, d.out)
        val walkStart = 20_000L
        var firstMove: Long? = null
        var prev = d.out!!.latLon
        for (k in 1..60) {
            val t = walkStart + k * 1_000L
            val truePos = en(1.4 * k, 0.0)
            val acc = 20.0 + 10.0 * rnd.next()
            val o = d.feed(fix(truePos, t, acc, 1.4))!!
            val moved = hav(prev, o.latLon) > 0.01
            if (moved && firstMove == null) firstMove = t
            if (firstMove != null) {
                assertTrue("k=$k: after the start the dot moves on every fix", moved)
                assertAt("k=$k: the dot is on the latest fix", truePos, o)
            }
            prev = o.latLon
        }
        assertNotNull("the dot never moved", firstMove)
        assertTrue("AC 75(c): first move ${firstMove!! - walkStart} ms after the walk start (≤ 3 s)", firstMove - walkStart <= 3_000)
    }

    /** TC-N08, AC 75 example (d): 1.4 m/s walk, no speed, accuracy 20 m → never more than 25 m behind the latest fix. */
    @Test
    fun tcN08_example75d_walkWithoutSpeedLagsAtMostTwentyFiveMetres() {
        for (startHeld in listOf(false, true)) {
            val d = if (startHeld) standingAtP0(acc = 20.0) else Drive().also { it.feed(fix(p0, 0, 20.0)) }
            var worst = 0.0
            for (k in 1..300) {
                val p = en(1.4 * k * cos(0.3), 1.4 * k * sin(0.3))
                val o = d.feed(fix(p, k * 1_000L, 20.0, null))!!
                worst = maxOf(worst, hav(p, o.latLon))
            }
            assertTrue("AC 75(d) (start held=$startHeld): worst lag ${"%.1f".format(worst)} m (≤ 25 m)", worst <= 25.0)
            assertTrue("the dot actually moved (lag > 0 shows holding steps in between)", worst > 0.0)
        }
    }

    /** TC-N09, AC 75 example (e): standing on a 90 m fix, then 5 m fixes ≈ 40 m away → moves on the second one (≤ 2 s). */
    @Test
    fun tcN09_example75e_movesOffAPoorFixOnTheSecondGoodFix() {
        // (e1) literally: the 90 m fix is the first shown fix.
        val a = Drive()
        a.feed(fix(p0, 0, 90.0, 0.0))
        val good = en(28.0, 28.0) // ≈ 39.6 m
        assertAt("first 5 m fix: 0 m", p0, a.feed(fix(good, 1_000, 5.0, 0.0)))
        assertAt("second 5 m fix (≤ 2 s): the dot moves to it", good, a.feed(fix(good, 2_000, 5.0, 0.0)))
        // (e2) the dot was already held on repeated 90 m fixes before the good fixes come.
        val b = Drive()
        for (t in 0..5) b.feed(fix(p0, t * 1_000L, 90.0, 0.0))
        assertAt("first 5 m fix: 0 m", p0, b.feed(fix(good, 6_000, 5.0, 0.0)))
        assertAt("second 5 m fix: moves", good, b.feed(fix(en(29.0, 27.0), 7_000, 5.0, 0.0)), tolM = 3.0)
    }

    /** TC-N10, AC 75 speed thresholds: hold entry < 0.5 m/s (0.5 does not enter), release ≥ 1.0 m/s (0.99 does not). */
    @Test
    fun tcN10_speedThresholdsOnBothSides() {
        // Moving (dot follows): a reported 0.5 m/s at d ≤ r does not enter the hold → the dot moves to F.
        val m = Drive()
        m.feed(fix(p0, 0, 10.0, 2.0))
        assertAt("0.5 m/s does not enter the hold", en(3.0, 0.0), m.feed(fix(en(3.0, 0.0), 1_000, 10.0, 0.5)))
        assertAt("0.49 m/s enters the hold: 0 m", en(3.0, 0.0), m.feed(fix(en(6.0, 0.0), 2_000, 10.0, 0.49)))
        // Holding: 0.99 m/s inside r keeps the hold; 1.0 m/s inside r releases it.
        val h = standingAtP0()
        assertAt("0.99 m/s keeps the hold", p0, h.feed(fix(en(4.0, 0.0), 1_000, 10.0, 0.99)))
        assertAt("1.0 m/s releases: the dot moves to F", en(5.0, 0.0), h.feed(fix(en(5.0, 0.0), 2_000, 10.0, 1.0)))
    }

    /** TC-N11, AC 75: only the **second consecutive** fix outside r releases; a fix inside r in between restarts the count. */
    @Test
    fun tcN11_consecutiveOutsideFixesRelease() {
        val d = standingAtP0(acc = 10.0)
        val out1 = en(0.0, 20.0)
        assertAt("1st outside: 0 m", p0, d.feed(fix(out1, 1_000, 10.0, 0.0)))
        assertAt("inside r: hold kept", p0, d.feed(fix(en(0.0, 2.0), 2_000, 10.0, 0.0)))
        assertAt("outside again = 1st of a new run: 0 m", p0, d.feed(fix(out1, 3_000, 10.0, 0.0)))
        val out2 = en(1.0, 21.0)
        assertAt("2nd consecutive outside: moves to F", out2, d.feed(fix(out2, 4_000, 10.0, 0.0)))
    }

    /** TC-N12, Edge cases (D176): interleaved GPS 8 m and platform FUSED 45 m fixes while standing are held by AC 75. */
    @Test
    fun tcN12_interleavedGpsAndFusedWhileStandingAreHeld() {
        val d = Drive()
        d.feed(fix(p0, 0, 8.0, 0.0))
        for (t in 1..120) {
            val fused = t % 2 == 1
            val o = if (fused) {
                d.feed(fix(en(-30.0, 25.0), t * 1_000L, 45.0, null)) // ≈ 39 m off, inside its r = 45 m
            } else {
                d.feed(fix(en(2.0, -2.0), t * 1_000L, 8.0, 0.0))
            }
            assertAt("t=$t s", p0, o)
        }
    }

    // ------------------------------------------------------------------------------------------- AC 77 jumps

    /** TC-N13, AC 77 example (a): standing (speed 0, 10 m), one fix 300 m away, then fixes back at the old place → 0 m. */
    @Test
    fun tcN13_example77a_singleFarOutlierMovesTheDotZero() {
        val d = standingAtP0()
        assertAt("jump: 0 m", p0, d.feed(fix(en(0.0, 300.0), 1_000, 10.0, 0.0)))
        for (t in 2..10) assertAt("t=$t s", p0, d.feed(fix(en(1.0, 1.0), t * 1_000L, 10.0, 0.0)))
    }

    /** TC-N14, AC 77 example (b): standing, one fix 80 m off (accuracy 20 m) between normal fixes → 0 m. */
    @Test
    fun tcN14_example77b_eightyMetreOutlierMovesTheDotZero() {
        val d = standingAtP0(acc = 20.0)
        for (t in 1..5) d.feed(fix(p0, t * 1_000L, 20.0, 0.0))
        assertAt("80 m / 1 s, accuracy 20 m: 0 m", p0, d.feed(fix(en(80.0, 0.0), 6_000, 20.0, 0.0)))
        for (t in 7..15) assertAt("t=$t s", p0, d.feed(fix(p0, t * 1_000L, 20.0, 0.0)))
    }

    /** TC-N15, AC 77 example (c): a 300 m relocation confirmed by the next fix → the dot moves to K ≤ 2 s after J. */
    @Test
    fun tcN15_example77c_confirmedRelocationIsShownWithinTwoSeconds() {
        val d = standingAtP0()
        val j = en(0.0, -300.0)
        assertAt("J pending", p0, d.feed(fix(j, 1_000, 10.0, 0.0)))
        val k = en(5.0, -302.0)
        assertAt("K confirms J (≤ 5 s, within max(10, 25) m): dot at K", k, d.feed(fix(k, 2_000, 10.0, 0.0)))
        // AC 75 continues from K: standing there is held.
        assertAt("held at the new place", k, d.feed(fix(en(6.0, -301.0), 3_000, 10.0, 0.0)))
    }

    /**
     * TC-N16, AC 77 example (d): after 60 s without any fix, a fix 500 m away (8.3 m/s) is **not** a jump; AC 75 handles
     * it. (d1) moving before the gap → the dot moves to it at once. (d2) holding before the gap → AC 75 holds the first
     * fix outside r; per ADR-0009 Amendment 7 interpretation (iii) the dot reaches the new place on the third far fix
     * (the second far fix is then a jump from the held dot, confirmed by the third): ≤ 2 s after the first far fix.
     */
    @Test
    fun tcN16_example77d_farFixAfterALongGapIsNotAJump() {
        val far = en(400.0, 300.0) // 500 m
        val a = Drive()
        a.feed(fix(p0, 0, 10.0, 2.0))
        a.feed(fix(en(2.0, 0.0), 1_000, 10.0, 2.0))
        assertAt("(d1) moving before the gap: shown at once", far, a.feed(fix(far, 61_000, 10.0, null)))

        val b = standingAtP0()
        assertAt("(d2) first far fix: held by AC 75 (1st outside), not pending", p0, b.feed(fix(far, 60_000, 10.0, 0.0)))
        b.feed(fix(en(401.0, 300.0), 61_000, 10.0, 0.0))
        val third = en(401.0, 301.0)
        assertAt("(d2) by the third far fix (2 s after the first) the dot is at the new place", third, b.feed(fix(third, 62_000, 10.0, 0.0)))
    }

    /** TC-N17, AC 77 confirmation window: K exactly 5 s after J confirms; 5.001 s does not (J discarded, K under AC 75). */
    @Test
    fun tcN17_confirmationWindowIsFiveSecondsInclusive() {
        val j = en(0.0, 300.0)
        val k = en(3.0, 299.0)
        val a = standingAtP0()
        a.feed(fix(j, 1_000, 10.0, 0.0))
        assertAt("K at +5.000 s confirms", k, a.feed(fix(k, 6_000, 10.0, 0.0)))
        val b = standingAtP0()
        b.feed(fix(j, 1_000, 10.0, 0.0))
        // K at +5.001 s: J discarded; K is 299 m in 6.001 s from the last non-jump fix (< 50 m/s) → not a jump; holding,
        // speed 0 → first fix outside r → 0 m.
        assertAt("K at +5.001 s does not confirm", p0, b.feed(fix(k, 6_001, 10.0, 0.0)))
    }

    /** TC-N18, AC 77 confirmation radius: within max(K's accuracy, 25 m) of J. */
    @Test
    fun tcN18_confirmationRadiusIsMaxOfAccuracyAndTwentyFiveMetres() {
        val j = en(0.0, 300.0)
        fun run(k: LatLon, kAcc: Double): DisplayLocation? {
            val d = standingAtP0()
            d.feed(fix(j, 1_000, 10.0, 0.0))
            return d.feed(fix(k, 2_000, kAcc, 0.0))
        }
        assertAt("24 m from J, accuracy 10 m: confirms", en(24.0, 300.0), run(en(24.0, 300.0), 10.0), tolM = 0.05)
        assertAt("26 m from J, accuracy 10 m: does not confirm", p0, run(en(26.0, 300.0), 10.0))
        assertAt("35 m from J, accuracy 40 m: confirms (max(40, 25) = 40)", en(35.0, 300.0), run(en(35.0, 300.0), 40.0), tolM = 0.05)
    }

    /** TC-N19, AC 77 jump distance max(50 m, 2 × accuracy) and implied speed > 50 m/s, probed while moving (speed 2 m/s). */
    @Test
    fun tcN19_jumpDistanceAndSpeedThresholds() {
        fun moving(): Drive = Drive().also {
            it.feed(fix(p0, 0, 10.0, 2.0))
            it.feed(fix(p0, 1_000, 10.0, 2.0))
        }
        // 70 m in 1 s, accuracy 40 m: limit max(50, 80) = 80 m → not a jump → moving → shown.
        assertAt("70 m, accuracy 40 m: not a jump", en(70.0, 0.0), moving().feed(fix(en(70.0, 0.0), 2_000, 40.0, 2.0)), tolM = 0.05)
        // 70 m in 1 s, accuracy 20 m: limit 50 m, 70 m/s → jump → 0 m.
        assertAt("70 m, accuracy 20 m, 70 m/s: jump", p0, moving().feed(fix(en(70.0, 0.0), 2_000, 20.0, 2.0)))
        // 60 m in 2 s (30 m/s), accuracy 10 m: far but not fast → not a jump → shown.
        assertAt("60 m in 2 s: not a jump", en(60.0, 0.0), moving().feed(fix(en(60.0, 0.0), 3_000, 10.0, 2.0)), tolM = 0.05)
        // 60 m in 1 s (60 m/s): jump.
        assertAt("60 m in 1 s: jump", p0, moving().feed(fix(en(60.0, 0.0), 2_000, 10.0, 2.0)))
    }

    // ------------------------------------------------------------------------------------------- AC 74 circle, AC 79

    /**
     * TC-N20, AC 74: ring radius within ±10 % at UB latitude (30 m and 100 m, plus 5 m) by QA's haversine; 64 vertices and
     * closed. map-style §7.8 minZoom (circle hidden while smaller than the 10.5 dp dot): screen spec values at UB
     * 15.75 (10 m) and 12.43 (100 m).
     */
    @Test
    fun tcN20_circleRadiusAndMinZoom() {
        val c = LatLon(47.9185, 106.9170)
        for (r in listOf(5.0, 30.0, 100.0)) {
            val ring = AccuracyCircle.ring(c, r)
            assertEquals("64 vertices + closing point", 65, ring.size)
            assertEquals("closed ring", ring.first(), ring.last())
            for (p in ring) {
                val err = (hav(c, p) - r) / r
                assertTrue("AC 74: vertex at ${"%.3f".format(hav(c, p))} m for $r m (±10 %)", kotlin.math.abs(err) <= 0.10)
            }
        }
        assertEquals("minZoom 10 m at UB", 15.75, AccuracyCircle.minZoom(10.5, 47.92, 10.0), 0.01)
        assertEquals("minZoom 100 m at UB", 12.43, AccuracyCircle.minZoom(10.5, 47.92, 100.0), 0.01)
    }

    /** TC-N21, AC 79: the filter output's text form holds no coordinates (an accidental log line cannot leak them). */
    @Test
    fun tcN21_displayLocationTextHasNoCoordinates() {
        val d = standingAtP0()
        val txt = listOf(d.out.toString(), d.f.tick(20_000).toString(), d.f.toString())
        val hits = txt.filter { Regex("-?\\d{1,3}\\.\\d{4,}").containsMatchIn(it) }
        assertTrue("AC 79: coordinates in toString(): $hits", hits.isEmpty())
    }

    /** TC-N22, Amendment 7 §9.3 (architect request): reset() → no shown position until the next showable fix. */
    @Test
    fun tcN22_resetGoesBackToNoDot() {
        val d = standingAtP0()
        d.f.reset()
        assertNull("after reset: no dot", d.f.tick(1_000))
        assertNull("a poor fix after reset shows nothing", d.feed(fix(p0, 2_000, 150.0)))
        assertAt("the next showable fix shows the dot", en(500.0, 0.0), d.feed(fix(en(500.0, 0.0), 3_000, 20.0, 0.0)))
    }
}

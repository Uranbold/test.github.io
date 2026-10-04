package mn.navmn.app.typinglock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-011 AC 29–36 (ADR-0012 §7): the pure fix-speed and lock rules on synthetic 1 Hz sequences with a fake clock. */
class TypingLockRuleTest {
    private val start = LatLon(47.9189, 106.9176)

    @After
    fun reset() = PassengerOverride.resetForProcessRestart()

    /** 1 Hz fixes along a straight line: [kmh] each second, accuracy [acc], reported speed unless [noSpeed]. */
    private fun run(kmh: List<Double>, t0: Long = 1_000, acc: Double = 5.0, noSpeed: Boolean = false, from: LatLon = start): List<Fix> {
        var p = from
        return kmh.mapIndexed { i, v ->
            val mps = v / 3.6
            if (i > 0) p = Geo.offset(p, 90.0, mps)
            Fix(p.lat, p.lon, acc, 90.0, 10.0, if (noSpeed) null else mps, t0 + i * 1_000L, 0)
        }
    }

    private fun TypingLockRule.feed(fixes: List<Fix>): List<Boolean> = fixes.map { onFix(it, it.elapsedMs) }

    @Test
    fun fixSpeedUsesReportedSpeedElseDistanceOverTimeOnlyFor1To10s() {
        val a = Fix(start.lat, start.lon, 5.0, null, null, null, 0, 0)
        val b50 = Geo.offset(start, 0.0, 50.0).let { Fix(it.lat, it.lon, 5.0, null, null, null, 1_000, 0) }
        assertEquals(50.0, FixSpeed.of(b50, a)!!, 0.5)
        assertEquals(3.0, FixSpeed.of(b50.copy(speedMps = 3.0), a)!!, 1e-9)
        assertNull(FixSpeed.of(b50.copy(elapsedMs = 500), a))
        assertNull(FixSpeed.of(b50.copy(elapsedMs = 10_001), a))
        assertNull(FixSpeed.of(b50, null))
    }

    @Test
    fun i_14kmhFor120sNeverEngages() = assertFalse(TypingLockRule().feed(run(List(120) { 14.0 })).any { it })

    @Test
    fun ii_16kmhEngagesAfterFix3() {
        val states = TypingLockRule().feed(run(List(3) { 16.0 }))
        assertEquals(listOf(false, false, true), states)
    }

    @Test
    fun iii_walkingFor600sNeverEngages() = assertFalse(TypingLockRule().feed(run(List(600) { 1.4 * 3.6 })).any { it })

    @Test
    fun iv_20kmhWith40mAccuracyNeverEngages() = assertFalse(TypingLockRule().feed(run(List(60) { 20.0 }, acc = 40.0)).any { it })

    @Test
    fun v_engagedThen4kmhFor10sReleases() {
        val r = TypingLockRule()
        val fast = run(List(5) { 20.0 })
        assertTrue(r.feed(fast).last())
        val slow = run(List(11) { 4.0 }, t0 = fast.last().elapsedMs + 1_000, from = fast.last().latLon)
        val states = r.feed(slow)
        assertFalse("released after 10 s below 5 km/h with ≥ 5 good fixes", states.last())
        assertTrue("not before the 10 s window is all slow", states[5])
    }

    @Test
    fun vi_engagedThen8kmhFor120sStaysEngaged() {
        val r = TypingLockRule()
        val fast = run(List(5) { 20.0 })
        r.feed(fast)
        val mid = run(List(120) { 8.0 }, t0 = fast.last().elapsedMs + 1_000, from = fast.last().latLon)
        assertTrue(r.feed(mid).all { it })
    }

    @Test
    fun vii_engagedThenNoFixFor30sReleases() {
        val r = TypingLockRule()
        val fast = run(List(5) { 20.0 })
        r.feed(fast)
        val last = fast.last().elapsedMs
        assertTrue(r.onTick(last + 29_000))
        assertFalse(r.onTick(last + 30_000))
    }

    @Test
    fun viii_oneOutlierAt60kmhBetweenStillFixesNeverEngages() {
        val still = run(List(10) { 0.0 })
        val outlier = still[5].copy(speedMps = 60 / 3.6)
        val seq = still.toMutableList().apply { this[5] = outlier }
        assertFalse(TypingLockRule().feed(seq).any { it })
        // Without reported speeds, a jumped position gives two fast derived speeds (there and back): still not 3.
        val jumped = run(List(10) { 0.0 }, noSpeed = true).toMutableList()
        val far = Geo.offset(jumped[5].latLon, 0.0, 17.0)
        jumped[5] = jumped[5].copy(lat = far.lat, lon = far.lon)
        assertFalse(TypingLockRule().feed(jumped).any { it })
    }

    /**
     * (ix) fixes without `hasSpeed()`, 50 m apart at 1 s: the first fix has no previous fix to derive a speed from, so
     * three fast speeds need fixes 2–4 (open question in the handoff: the story says "after 3 fixes").
     */
    @Test
    fun ix_noReportedSpeed50mApartEngages() {
        val states = TypingLockRule().feed(run(List(6) { 180.0 }, noSpeed = true))
        assertEquals(listOf(false, false, false, true, true, true), states)
    }

    @Test
    fun releasesWhenLocationBecomesUnavailableAndNeverEngagesWithoutFixes() {
        val r = TypingLockRule()
        r.feed(run(List(5) { 20.0 }))
        assertFalse(r.onUnavailable())
        val none = TypingLockRule()
        assertFalse(none.onTick(1_000_000))
    }

    /** AC 30–34 at the controller: the card only after a tap, the override in process memory, release hides the card. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun controllerCardOverrideAndListenerLifetime() = runTest {
        val signals = MutableSharedFlow<LockSignal>(extraBufferCapacity = 64)
        var listeners = 0
        val source = object : LockFixSource {
            override fun signals(): Flow<LockSignal> = signals.onStart { listeners++ }.onCompletion { listeners-- }
        }
        val c = TypingLockController(source) { testScheduler.currentTime }
        val job = launch { c.collect() }
        runCurrent()
        assertEquals(1, listeners)
        assertTrue("no lock → typing allowed", c.onTextFieldTap())
        for (f in run(List(3) { 20.0 }, t0 = testScheduler.currentTime)) signals.emit(LockSignal.Location(f))
        runCurrent()
        assertTrue(c.state.value.locked)
        assertFalse("AC 30: nothing changes on screen before a tap", c.state.value.cardVisible)
        assertEquals(1, c.state.value.engagement)
        assertFalse("AC 31: the keyboard does not open", c.onTextFieldTap())
        assertTrue(c.state.value.cardVisible)
        c.dismissCard()
        assertFalse(c.state.value.cardVisible)
        c.onTextFieldTap()
        signals.emit(LockSignal.Unavailable) // AC 32: services off → released, card removed
        runCurrent()
        assertFalse(c.state.value.locked)
        assertFalse(c.state.value.cardVisible)
        // AC 34: «Би зорчигч» overrides for the session.
        for (f in run(List(3) { 20.0 }, t0 = testScheduler.currentTime)) signals.emit(LockSignal.Location(f))
        runCurrent()
        assertEquals(2, c.state.value.engagement)
        c.onTextFieldTap()
        c.passenger()
        assertTrue(PassengerOverride.active)
        assertFalse(c.state.value.locked)
        assertTrue(c.onTextFieldTap())
        // AC 27: leaving S1/S3 cancels the collection and removes the listener.
        job.cancel()
        runCurrent()
        assertEquals(0, listeners)
        // A new process has no override (process memory only).
        PassengerOverride.resetForProcessRestart()
        assertFalse(TypingLockController(source) { 0 }.state.value.overridden)
    }

    /** AC 32 at the controller: the 30 s no-fix release comes from the 1 s ticker. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun controllerReleasesAfter30sWithoutAFix() = runTest {
        val signals = MutableSharedFlow<LockSignal>(extraBufferCapacity = 64)
        val c = TypingLockController(object : LockFixSource { override fun signals() = signals }) { testScheduler.currentTime }
        val job = launch { c.collect() }
        runCurrent()
        for (f in run(List(3) { 20.0 }, t0 = testScheduler.currentTime - 2_000)) signals.emit(LockSignal.Location(f))
        runCurrent()
        assertTrue(c.state.value.engaged)
        advanceTimeBy(29_000)
        assertTrue(c.state.value.engaged)
        advanceTimeBy(2_100)
        assertFalse(c.state.value.engaged)
        job.cancel()
    }
}

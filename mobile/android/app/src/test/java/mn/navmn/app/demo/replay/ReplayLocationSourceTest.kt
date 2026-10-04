package mn.navmn.app.demo.replay

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import mn.navmn.app.qa.QaGpx
import mn.navmn.app.qa.repoFile
import mn.navmn.app.voiceplan.PromptClass
import mn.navmn.app.voiceplan.SpokenPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * NAV-019 AC 12, 22, 25 (ADR-0016 §4, §11): the simulated location provider on a virtual clock. Emits the recorded
 * track at its own times (1×, and 2× / 4× as supported speeds), pauses and resumes without gaps or repeats, ends the
 * track 2 s after the last point when no arrival stopped it, and stops at once when the engine cancels it (arrival).
 */
class ReplayLocationSourceTest {
    private val g1 = ReplayTrack.parse(repoFile("tests/gpx/nav005/G1.gpx").readBytes())

    private class Rig(scope: TestScope, val track: ReplayTrack, jump: () -> Long = { 0L }) {
        val clock = ReplayClock(realNow = { scope.testScheduler.currentTime + jump() }, wallNow = { 1_790_000_000_000L + scope.testScheduler.currentTime })
        val wake = RecordingWakeLock()
        val source = ReplayLocationSource(clock, servicesEnabled = { true }, wakeLock = wake, wallNow = { 1_790_000_000_000L + scope.testScheduler.currentTime })
        var ended = 0
        var endedAt = -1L
        val got = ArrayList<Pair<Long, Fix>>()

        init {
            source.arm(track)
            source.onTrackEnd = {
                ended++
                endedAt = scope.testScheduler.currentTime
            }
        }
    }

    /** Index of [f] in the track (positions are unique on the recorded tracks except stationary ends). */
    private fun indexOf(track: ReplayTrack, f: Fix, anchor: Long): Int = track.offsets.indexOfFirst { anchor + it == f.elapsedMs }

    @Test
    fun emitsEveryTrackPointAtItsTrackTimeAt1x2xAnd4x() {
        for (speed in listOf(1, 2, 4)) runTest {
            val r = Rig(this, g1)
            r.source.speed = speed
            val t0 = testScheduler.currentTime
            val fix0 = r.source.freshGoodFix()!!
            // AC 12: the first simulated fix is the first track point, on the replay clock's time base.
            assertEquals(g1.points[0].lat, fix0.lat, 0.0)
            assertEquals(g1.points[0].lon, fix0.lon, 0.0)
            assertEquals(r.clock.elapsedMs(), fix0.elapsedMs)
            assertTrue("fix 0 is a good fix", fix0.isGood(fix0.elapsedMs))
            backgroundScope.launch { r.source.guidanceUpdates().collect { r.got += (testScheduler.currentTime - t0) to it } }
            advanceTimeBy((g1.durationMs + ReplayLocationSource.END_GRACE_MS) / speed + 1_000)
            runCurrent()
            assertEquals("${speed}×: every point after fix 0 delivered once", g1.size - 1, r.got.size)
            r.got.forEachIndexed { k, (t, f) ->
                val i = k + 1
                val due = g1.offsets[i] / speed
                assertTrue("${speed}×: point $i at $t ms, due $due ms (± 200 ms)", abs(t - due) <= 200)
                assertEquals(fix0.elapsedMs + g1.offsets[i], f.elapsedMs)
                assertEquals(g1.points[i].lat, f.lat, 0.0)
                assertEquals(g1.points[i].lon, f.lon, 0.0)
            }
            // AC 25: no arrival in this test (nothing consumes the fixes) → the track ends 2 s (replay time) after its last point.
            assertEquals(1, r.ended)
            val expectedEnd = t0 + (g1.durationMs + ReplayLocationSource.END_GRACE_MS) / speed
            assertTrue("${speed}×: track end at ${r.endedAt}, expected $expectedEnd", abs(r.endedAt - expectedEnd) <= 200)
            assertTrue("wake lock released at the end", !r.wake.held)
        }
    }

    @Test
    fun pauseFreezesTheClockAndTheEmissionAndResumeContinuesWithoutGapsOrRepeats() = runTest {
        val r = Rig(this, g1)
        val t0 = testScheduler.currentTime
        val fix0 = r.source.freshGoodFix()!!
        backgroundScope.launch { r.source.guidanceUpdates().collect { r.got += (testScheduler.currentTime - t0) to it } }
        advanceTimeBy(30_050)
        runCurrent()
        assertTrue("wake lock held while replaying", r.wake.held)
        val beforePause = r.got.size
        assertTrue(r.clock.pause())
        val frozen = r.clock.elapsedMs()
        runCurrent()
        assertTrue("wake lock released while paused", !r.wake.held)
        advanceTimeBy(60_000)
        runCurrent()
        // AC 22: nothing is delivered and the replay clock stands still however long the pause lasts.
        assertEquals("fixes during the pause", beforePause, r.got.size)
        assertEquals("replay clock frozen", frozen, r.clock.elapsedMs())
        assertTrue(r.clock.resume())
        runCurrent()
        assertTrue("wake lock acquired again", r.wake.held)
        advanceTimeBy(g1.durationMs + 5_000)
        runCurrent()
        val indices = r.got.map { indexOf(g1, it.second, fix0.elapsedMs) }
        assertEquals("every point exactly once, in order", (1 until g1.size).toList(), indices)
        // The next point after the pause comes at its track time on the replay clock (+ 60 s of real time).
        val (tNext, fNext) = r.got[beforePause]
        val iNext = indexOf(g1, fNext, fix0.elapsedMs)
        assertTrue("resumed point $iNext at $tNext", abs(tNext - (g1.offsets[iNext] + 60_000)) <= 200)
        assertEquals(1, r.ended)
        assertEquals(0, r.source.skipped)
    }

    @Test
    fun aLateWakeUpDeliversOnlyTheLatestDuePoint() = runTest {
        var jump = 0L
        val r = Rig(this, g1) { jump }
        val t0 = testScheduler.currentTime
        val fix0 = r.source.freshGoodFix()!!
        backgroundScope.launch { r.source.guidanceUpdates().collect { r.got += (testScheduler.currentTime - t0) to it } }
        advanceTimeBy(10_050)
        runCurrent()
        // The device slept for 5 s: the monotonic clock jumped while no wake-up happened.
        jump = 5_000
        advanceTimeBy(1_000)
        runCurrent()
        val indices = r.got.map { indexOf(g1, it.second, fix0.elapsedMs) }
        assertTrue("strictly increasing, no burst of old points: $indices", indices.zipWithNext().all { (a, b) -> b > a })
        assertTrue("a gap after the late wake-up: $indices", indices.zipWithNext().any { (a, b) -> b - a > 1 })
        assertTrue("skipped points counted", r.source.skipped >= 4)
    }

    @Test
    fun cancellingTheCollectionAtArrivalStopsEverythingAndDoesNotEndTheTrack() = runTest {
        val r = Rig(this, g1)
        r.source.freshGoodFix()
        val job = backgroundScope.launch { r.source.guidanceUpdates().collect { r.got += testScheduler.currentTime to it } }
        advanceTimeBy(20_050)
        runCurrent()
        val n = r.got.size
        job.cancel() // GuidanceEngine: arrival cancels the location job (AC 24)
        advanceTimeBy(g1.durationMs + 10_000)
        runCurrent()
        assertEquals(n, r.got.size)
        assertEquals("no «Дуусгах» path after arrival", 0, r.ended)
        assertTrue("wake lock released", !r.wake.held)
    }

    @Test
    fun notArmedOrNotStartedEmitsNothingAndStartIsIdempotentUntilRearmed() = runTest {
        val clock = ReplayClock({ testScheduler.currentTime })
        val src = ReplayLocationSource(clock, servicesEnabled = { false })
        assertNull("no track: no fix", src.freshGoodFix())
        assertTrue(src.guidanceUpdates().toList().isEmpty())
        assertTrue("no device position, ever (AC 13)", src.mapUpdates().toList().isEmpty())
        assertTrue(!src.servicesEnabled())
        src.arm(g1)
        assertTrue("armed but not started", src.guidanceUpdates().toList().isEmpty())
        val a = src.freshGoodFix()
        advanceTimeBy(3_000)
        // NAV-019 edge case: a double tap on «Эхлэх» starts one replay only (same fix 0, clock not restarted).
        assertEquals(a, src.freshGoodFix())
        src.arm(g1)
        val b = src.freshGoodFix()!!
        assertTrue("re-armed: a new replay starts", b.elapsedMs > a!!.elapsedMs)
    }

    @Test
    fun replayClockSpeedsAndPauseRules() {
        var now = 1_000_000L
        val c = ReplayClock({ now })
        assertEquals("before start: real time", now, c.elapsedMs())
        assertTrue("no pause before start", !c.pause())
        c.start(2)
        now += 1_000
        assertEquals(1_002_000L, c.elapsedMs())
        c.pause()
        now += 10_000
        assertEquals(1_002_000L, c.elapsedMs())
        c.resume()
        now += 500
        assertEquals(1_003_000L, c.elapsedMs())
        c.start(1)
        assertEquals("a new start resets to real time", now, c.elapsedMs())
        assertTrue(!c.paused.value)
        assertTrue(runCatching { c.start(3) }.isFailure)
    }

    /** ADR-0016 §4.2: the replay builds exactly the QA harness's fixes from the same bytes (golden parity). */
    @Test
    fun trackFixesAreIdenticalToTheQaHarnessFixes() {
        for (id in listOf("G1", "G5", "G8", "G4", "G2")) {
            val t = ReplayTrack.parse(repoFile("tests/gpx/nav005/$id.gpx").readBytes())
            assertEquals(id, QaGpx.fixes(id), t.gpxTimelineFixes())
        }
        // Story › Demo routes: replay length at 1× (R1 308 s, R2 932 s, R3 787 s).
        assertEquals(308_000L, g1.durationMs)
        assertEquals(932_000L, ReplayTrack.parse(repoFile("tests/gpx/nav005/G5.gpx").readBytes()).durationMs)
        assertEquals(787_000L, ReplayTrack.parse(repoFile("tests/gpx/nav005/G8.gpx").readBytes()).durationMs)
    }

    @Test
    fun brokenTracksAreRejected() {
        assertTrue(runCatching { ReplayTrack.parse("<gpx/>".encodeToByteArray()) }.isFailure)
        assertTrue(runCatching { ReplayTrack.parse("<gpx><trkpt lat=\"1\" lon=\"2\"></trkpt><trkpt lat=\"1\" lon=\"2\"></trkpt></gpx>".encodeToByteArray()) }.isFailure)
        assertTrue(runCatching { ReplayTrack.parse(repoFile("tests/gpx/nav005/G1.gpx").readBytes().copyOf(200)) }.isFailure)
    }

    /** ADR-0016 §11: while paused a prompt is answered at once without sound; pausing stops the current utterance. */
    @Test
    fun pauseGatedVoiceSilencesPromptsWhilePaused() {
        var paused = false
        val played = ArrayList<Long>()
        val done = ArrayList<Long>()
        var stops = 0
        val real = object : mn.navmn.app.voice.GuidanceVoice {
            override var onDone: ((Long) -> Unit)? = null
            override var onFallback: (() -> Unit)? = null
            override fun play(prompt: SpokenPrompt) {
                played += prompt.id
            }
            override fun stop() {
                stops++
            }
            override fun prepare() = Unit
            override fun newSession() = Unit
            override fun onLanguageChanged() = Unit
        }
        val gate = PauseGatedVoice(real) { paused }
        gate.onDone = { done += it }
        assertTrue("onDone reaches the real voice", real.onDone != null)
        fun prompt(id: Long) = SpokenPrompt(id, "Баруун тийш эргэнэ үү", Lang.MN, PromptClass.entries.first(), 1 to 1, 0L)
        gate.play(prompt(1))
        assertEquals(listOf(1L), played)
        paused = true
        gate.onPaused()
        assertEquals(1, stops)
        gate.play(prompt(2))
        assertEquals("not played while paused", listOf(1L), played)
        assertEquals("answered at once, so the queue drains", listOf(2L), done)
        assertEquals(1, gate.silenced)
    }
}

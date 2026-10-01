package mn.navmn.app.voiceplan

import mn.navmn.app.i18n.Lang
import mn.navmn.app.instructions.VoiceContent
import mn.navmn.app.instructions.VoiceText
import mn.navmn.app.route.GuidancePlan
import mn.navmn.app.support.Plans
import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** navigation-ux §4.2–§4.4 schedule (NAV-005 AC 32 chaining, AC 34, 35, 40). Fake clock, 1 Hz snapshots. */
class VoiceSchedulerTest {
    private val mn = TestStrings.of(Lang.MN)

    data class Heard(val t: Long, val d: Double, val text: String, val kind: PromptKind?)

    /** Drives step [step] (k) at constant [speed] from its full length down to 0, then into the next step. */
    private fun approach(s: VoiceScheduler, plan: GuidancePlan, step: Int, speed: Double, t0: Long = 0, sampleMs: Long = 1_000): List<Heard> {
        val out = ArrayList<Heard>()
        var d = plan.steps[step].distance
        var t = t0
        while (d >= 0) {
            s.evaluate(VoiceScheduler.Input(t, plan, step, d, speed))?.let {
                out += Heard(t, d, VoiceText.render(it.content, Lang.MN, mn), it.kind)
            }
            d -= speed * sampleMs / 1000.0
            t += sampleMs
        }
        return out
    }

    @Test
    fun citySpeedGivesEarlyMainNowEachOnce() {
        val plan = Plans.plan(Plans.depart to 1_500.0, Plans.right to 500.0, Plans.arrive to 0.0)
        val s = VoiceScheduler(walk = false)
        s.start(plan, 0)
        val heard = approach(s, plan, 0, 14.0, t0 = 10_000)
        assertEquals(listOf(PromptKind.EARLY, PromptKind.MAIN, PromptKind.NOW), heard.map { it.kind })
        assertEquals("1 километрт баруун тийш эргэнэ үү", heard[0].text)
        assertTrue(heard[1].d in 155.0..168.0)
        assertTrue(heard[2].d in 28.0..42.0)
    }

    @Test
    fun atLeastOnePromptTwentyToTwoHundredFiftyMetresAheadAtEverySpeed() {
        for (kmh in listOf(3, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 130)) {
            for (gap in listOf(60.0, 160.0, 400.0, 900.0, 3_000.0)) {
                val plan = Plans.plan(Plans.depart to gap, Plans.left to 800.0, Plans.arrive to 0.0)
                val s = VoiceScheduler(walk = false)
                val depart = s.start(plan, 0)
                // A chained depart prompt names the first manoeuvre while it is `gap` ahead (§4.3, §4.4).
                val chained = (depart.content as VoiceContent.Depart).then != null
                val heard = approach(s, plan, 0, kmh / 3.6, t0 = 20_000) +
                    if (chained) listOf(Heard(0, gap, "depart+then", PromptKind.DEPART)) else emptyList()
                assertTrue("$kmh km/h gap $gap: $heard", heard.any { it.d in 20.0..250.0 })
            }
        }
    }

    @Test
    fun eachKindAtMostOnceAndNothingForPassedManeuvers() {
        val plan = Plans.plan(Plans.depart to 1_500.0, Plans.right to 500.0, Plans.left to 700.0, Plans.arrive to 0.0)
        val s = VoiceScheduler(walk = false)
        s.start(plan, 0)
        val first = approach(s, plan, 0, 12.0, t0 = 10_000)
        // repeated snapshots at the same distance do not repeat
        assertNull(s.evaluate(VoiceScheduler.Input(500_000, plan, 0, 1.0, 12.0)))
        val second = approach(s, plan, 1, 12.0, t0 = 600_000)
        assertTrue(second.none { it.text.contains("баруун тийш") })
        assertEquals(first.size, first.map { it.kind }.toSet().size)
    }

    @Test
    fun sameManeuverEightSecondRule() {
        val plan = Plans.plan(Plans.depart to 300.0, Plans.right to 500.0, Plans.arrive to 0.0)
        val s = VoiceScheduler(walk = false)
        s.start(plan, 0)
        assertEquals(PromptKind.MAIN, s.evaluate(VoiceScheduler.Input(20_000, plan, 0, 110.0, 10.0))!!.kind)
        // the "now" threshold is reached 5 s later: skipped (another prompt for it < 8 s earlier)
        assertNull(s.evaluate(VoiceScheduler.Input(25_000, plan, 0, 25.0, 10.0)))
        assertNull(s.evaluate(VoiceScheduler.Input(30_000, plan, 0, 10.0, 10.0)))
    }

    /**
     * NAV-005-D2 (G9): rule 2 counts from the playback START. The main prompt waits 1.4 s behind the depart prompt;
     * the "now" prompt 7.6 s after that start (9 s after the trigger) is skipped.
     */
    @Test
    fun sameManeuverEightSecondRuleCountsFromThePlaybackStart() {
        val plan = Plans.plan(Plans.depart to 300.0, Plans.right to 500.0, Plans.arrive to 0.0)
        val s = VoiceScheduler(walk = false)
        s.start(plan, 0)
        val main = s.evaluate(VoiceScheduler.Input(0, plan, 0, 200.0, 25.0))!!
        assertEquals(PromptKind.MAIN, main.kind)
        s.onPromptStarted(main.maneuver!!, 1_400)
        assertNull(s.evaluate(VoiceScheduler.Input(9_000, plan, 0, 40.0, 25.0)))
    }

    /** A prompt that was dropped unplayed (queue 3 s rule) does not block the next prompt for the same manoeuvre. */
    @Test
    fun droppedPromptDoesNotCountForTheEightSecondRule() {
        val plan = Plans.plan(Plans.depart to 300.0, Plans.right to 500.0, Plans.arrive to 0.0)
        val s = VoiceScheduler(walk = false)
        s.start(plan, 0)
        val main = s.evaluate(VoiceScheduler.Input(20_000, plan, 0, 110.0, 10.0))!!
        s.onPromptDropped(main.maneuver!!, main.triggerAtMs)
        assertEquals(PromptKind.NOW, s.evaluate(VoiceScheduler.Input(25_000, plan, 0, 25.0, 10.0))!!.kind)
    }

    @Test
    fun chainingWithinOneHundredFiftyMetresByCar() {
        val plan = Plans.plan(Plans.depart to 600.0, Plans.right to 100.0, Plans.left to 900.0, Plans.arrive to 0.0)
        val s = VoiceScheduler(walk = false)
        s.start(plan, 0)
        val heard = approach(s, plan, 0, 12.0, t0 = 10_000)
        val main = heard.first { it.kind == PromptKind.MAIN }
        assertTrue(main.text, main.text.endsWith("баруун тийш эргэнэ үү, дараа нь зүүн тийш эргэнэ үү"))
        assertTrue(s.thenVisible(plan, 0) != null)
        // the chained second manoeuvre only gets its "now" prompt
        val second = approach(s, plan, 1, 12.0, t0 = 100_000)
        assertEquals(listOf(PromptKind.NOW), second.map { it.kind })
    }

    @Test
    fun noChainBeforeArriveAndWalkChainsWithinFortyMetres() {
        val car = Plans.plan(Plans.depart to 600.0, Plans.right to 100.0, Plans.arrive to 0.0)
        assertNull(VoiceScheduler(walk = false).chainTarget(car, 1))
        val walk = Plans.plan(Plans.depart to 200.0, Plans.right to 35.0, Plans.left to 300.0, Plans.arrive to 0.0)
        assertTrue(VoiceScheduler(walk = true).chainTarget(walk, 1) != null)
        val walkFar = Plans.plan(Plans.depart to 200.0, Plans.right to 45.0, Plans.left to 300.0, Plans.arrive to 0.0)
        assertNull(VoiceScheduler(walk = true).chainTarget(walkFar, 1))
    }

    @Test
    fun departAtStartIsChainedWhenTheFirstManeuverIsClose() {
        val plan = Plans.plan(Plans.depart to 120.0, Plans.left to 900.0, Plans.arrive to 0.0)
        val p = VoiceScheduler(walk = false).start(plan, 0)
        assertEquals("Хойд зүг рүү явна уу, дараа нь зүүн тийш эргэнэ үү", VoiceText.render(p.content, Lang.MN, mn))
        assertEquals(PromptKind.DEPART, p.kind)
    }

    @Test
    fun arriveGetsApproachingInsteadOfMainAndNoNow() {
        val plan = Plans.plan(Plans.depart to 900.0, Plans.arrive to 0.0)
        val s = VoiceScheduler(walk = false)
        s.start(plan, 0)
        val heard = approach(s, plan, 0, 12.0, t0 = 10_000)
        assertEquals(1, heard.size)
        assertTrue(heard[0].text, heard[0].text.endsWith("метрт очих газартаа хүрнэ"))
    }

    @Test
    fun exitRoundaboutOnlyNow() {
        val plan = Plans.plan(Plans.depart to 400.0, Plans.exitRb to 300.0, Plans.arrive to 0.0)
        val s = VoiceScheduler(walk = false)
        s.start(plan, 0)
        assertEquals(listOf(PromptKind.NOW), approach(s, plan, 0, 10.0, t0 = 10_000).map { it.kind })
    }

    @Test
    fun walkMainFiftyNowFifteen() {
        val plan = Plans.plan(Plans.depart to 300.0, Plans.right to 300.0, Plans.arrive to 0.0)
        val s = VoiceScheduler(walk = true)
        s.start(plan, 0)
        val heard = approach(s, plan, 0, 1.4, t0 = 10_000)
        assertEquals(listOf(PromptKind.MAIN, PromptKind.NOW), heard.map { it.kind })
        assertTrue(heard[0].d in 48.6..50.0)
        assertTrue(heard[1].d in 13.6..15.0)
    }

    @Test
    fun continueOnAfterAManeuverWhenTheNextIsFar() {
        val plan = Plans.plan(Plans.depart to 300.0, Plans.right to 12_000.0, Plans.left to 500.0, Plans.arrive to 0.0)
        val s = VoiceScheduler(walk = false)
        s.start(plan, 0)
        approach(s, plan, 0, 20.0, t0 = 10_000)
        val p = s.evaluate(VoiceScheduler.Input(100_000, plan, 1, 11_990.0, 20.0))!!
        assertEquals(PromptKind.CONTINUE_ON, p.kind)
        assertEquals("12 километр үргэлжлүүлэн явна уу", VoiceText.render(p.content, Lang.MN, mn))
    }

    @Test
    fun catchUpAfterRerouteNoDepart() {
        val plan = Plans.plan(Plans.depart to 400.0, Plans.left to 600.0, Plans.arrive to 0.0, generation = 1)
        val s = VoiceScheduler(walk = false)
        s.onRouteActive()
        val p = s.evaluate(VoiceScheduler.Input(1_000, plan, 0, 380.0, 12.0))!!
        assertEquals(PromptKind.CATCH_UP, p.kind)
        assertTrue(p.content is VoiceContent.Maneuver)
        assertEquals("400 метрт зүүн тийш эргэнэ үү", VoiceText.render(p.content, Lang.MN, mn))
        assertNull(s.evaluate(VoiceScheduler.Input(2_000, plan, 0, 370.0, 12.0)))
    }

    @Test
    fun catchUpAfterGpsRestoreOnlyIfNotYetAnnounced() {
        val plan = Plans.plan(Plans.depart to 400.0, Plans.left to 600.0, Plans.arrive to 0.0)
        val s = VoiceScheduler(walk = false)
        s.start(plan, 0)
        assertEquals(PromptKind.MAIN, s.evaluate(VoiceScheduler.Input(10_000, plan, 0, 160.0, 14.0))!!.kind)
        s.onGpsRestored()
        assertNull(s.evaluate(VoiceScheduler.Input(40_000, plan, 0, 120.0, 14.0)))
    }

    // navigation-ux §4.3 `arrive` sub-rule (NAV-005-D12, AC 32 / D67): the catch-up path gives no approaching prompt
    // for an `arrive` more than 500 m ahead; the normal approaching prompt then fires at its main trigger.
    // Last turn → `arrive` 2,990 m (like G9); 22 m/s is fast and the gap is ≥ 700 m, so the arrive main trigger is 500 m.
    private val toArrive = Plans.plan(Plans.depart to 300.0, Plans.right to 2_990.0, Plans.arrive to 0.0)
    private val en = TestStrings.of(Lang.EN)

    /** Catch-up at [catchUpD] on the final step, then the normal schedule at [later]; returns every prompt with its d. */
    private fun arriveCatchUp(restore: Boolean, catchUpD: Double, later: List<Double>): List<Pair<Double, ScheduledPrompt>> {
        val s = VoiceScheduler(walk = false)
        s.start(toArrive, 0)
        if (restore) s.onGpsRestored() else s.onRouteActive()
        val out = ArrayList<Pair<Double, ScheduledPrompt>>()
        var t = 60_000L
        var prev = catchUpD
        for (d in listOf(catchUpD) + later) {
            t += ((prev - d) / 22.0 * 1_000).toLong() // the clock follows the 22 m/s drive (rule 2 uses it)
            prev = d
            s.evaluate(VoiceScheduler.Input(t, toArrive, 1, d, 22.0))?.let { out += d to it }
        }
        return out
    }

    @Test
    fun d12NoCatchUpApproachingForArriveMoreThan500mAheadAfterGpsRestore() {
        for (catchUpD in listOf(980.0, 1_500.0)) {
            val heard = arriveCatchUp(restore = true, catchUpD, later = listOf(900.0, 700.0, 520.0, 500.0, 480.0, 300.0, 100.0, 40.0))
            assertTrue("catch-up at $catchUpD m: ${heard.map { it.first }}", heard.none { it.first == catchUpD })
            // The normal approaching prompt fires once, at its usual threshold (500 m), with the usual text.
            assertEquals("catch-up at $catchUpD m", listOf(500.0), heard.map { it.first })
            assertEquals(PromptKind.MAIN, heard[0].second.kind)
            assertEquals("500 метрт очих газартаа хүрнэ", VoiceText.render(heard[0].second.content, Lang.MN, mn))
            assertEquals("In 500 meters, you will arrive", VoiceText.render(heard[0].second.content, Lang.EN, en))
        }
    }

    @Test
    fun d12NoCatchUpApproachingForArriveMoreThan500mAheadAfterReroute() {
        for (catchUpD in listOf(980.0, 1_000.0, 1_500.0, 1_999.5, 500.5)) {
            val heard = arriveCatchUp(restore = false, catchUpD, later = listOf(500.0, 300.0))
            assertEquals("catch-up at $catchUpD m", listOf(500.0), heard.map { it.first })
            assertEquals(PromptKind.MAIN, heard[0].second.kind)
        }
    }

    @Test
    fun d12CatchUpApproachingForArriveWithin500mIsUnchanged() {
        for (restore in listOf(true, false)) {
            val heard = arriveCatchUp(restore, 400.0, later = listOf(380.0, 300.0, 100.0, 40.0))
            assertEquals("restore=$restore", listOf(400.0), heard.map { it.first }) // main prompt handled by the catch-up
            val p = heard[0].second
            assertEquals(PromptKind.CATCH_UP, p.kind)
            assertEquals("400 метрт очих газартаа хүрнэ", VoiceText.render(p.content, Lang.MN, mn))
            assertEquals("In 400 meters, you will arrive", VoiceText.render(p.content, Lang.EN, en))
        }
        val atLimit = arriveCatchUp(restore = true, 500.0, later = emptyList())
        assertEquals(PromptKind.CATCH_UP, atLimit.single().second.kind)
        assertEquals("500 метрт очих газартаа хүрнэ", VoiceText.render(atLimit.single().second.content, Lang.MN, mn))
    }

    @Test
    fun d12CatchUpForArriveTwoKilometresOrMoreAheadIsContinueOn() {
        val heard = arriveCatchUp(restore = false, 2_400.0, later = listOf(1_000.0, 500.0))
        assertEquals(listOf(2_400.0, 500.0), heard.map { it.first })
        assertEquals(PromptKind.CATCH_UP, heard[0].second.kind)
        assertTrue(heard[0].second.content is VoiceContent.ContinueOn)
        assertEquals("2,4 километр үргэлжлүүлэн явна уу", VoiceText.render(heard[0].second.content, Lang.MN, mn))
        assertEquals("500 метрт очих газартаа хүрнэ", VoiceText.render(heard[1].second.content, Lang.MN, mn))
    }
}

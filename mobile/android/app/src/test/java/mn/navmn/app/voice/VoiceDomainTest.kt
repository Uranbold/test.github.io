package mn.navmn.app.voice

import mn.navmn.app.i18n.Lang
import mn.navmn.app.instructions.VoiceContent
import mn.navmn.app.instructions.VoiceText
import mn.navmn.app.support.Plans
import mn.navmn.app.support.TestStrings
import mn.navmn.app.support.TestVoice
import mn.navmn.app.voice.pack.C3Table
import mn.navmn.app.voice.pack.Segment
import mn.navmn.app.voice.pack.Take
import mn.navmn.app.voiceplan.PromptKind
import mn.navmn.app.voiceplan.VoiceScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAV-016 M4 (AC 25, 27, 28, D102): every prompt of the generator domain ([VoiceDomain]) maps to inventory entries,
 * every distance key is a TSV key, the spoken text keeps the AC 24 join, contains no digit and no Latin letter, and
 * every non-distance inventory entry is produced at least once. Failures name the input.
 */
class VoiceDomainTest {
    private val inv = TestVoice.inventory

    @Test
    fun ac27EveryProducedSegmentIsInTheInventory() {
        val bad = ArrayList<String>()
        var prompts = 0
        for (item in VoiceDomain.items) {
            val p = item.prompt ?: continue
            prompts++
            for (s in p.segments) {
                if (s !in inv) bad += "${item.input}: ${s.token} is not in the inventory"
                if ((s.key.startsWith("dist.") || s.key.startsWith("cont.")) && s.key !in C3Table.byKey) bad += "${item.input}: ${s.key} is not a TSV key"
            }
            if (p.segments.isEmpty() || p.segments.size > 4) bad += "${item.input}: ${p.segments.size} segments"
            val joined = VoiceText.spokenText(p.segments, inv)
            if (joined != p.spoken) bad += "${item.input}: spoken «${p.spoken}» breaks the AC 24 join («$joined»)"
            // a {first} instruction uses its nonFinal take; every other position final (ADR-0010 decision 2)
            for ((i, s) in p.segments.withIndex()) {
                val beforeThen = p.segments.getOrNull(i + 1)?.key == VoiceText.JOIN_THEN
                if ((s.take == Take.NON_FINAL) != beforeThen) bad += "${item.input}: take of ${s.token}"
            }
            if (bad.size > 30) break
        }
        assertEquals(bad.take(30).joinToString("\n"), 0, bad.size)
        assertTrue("domain size $prompts", prompts > 200_000)
    }

    @Test
    fun ac25SpokenTextsHaveNoDigitsNoLatinAndOnlyTableDistances() {
        val bad = ArrayList<String>()
        val digitOrLatin = Regex("[0-9A-Za-z]")
        for (item in VoiceDomain.items) {
            val p = item.prompt ?: continue
            if (digitOrLatin.containsMatchIn(p.spoken)) bad += "${item.input}: «${p.spoken}»"
            val dist = p.segments.firstOrNull { it.key.startsWith("dist.") || it.key.startsWith("cont.") }
            if (dist != null && !p.spoken.startsWith(C3Table.byKey[dist.key]?.spoken ?: "\u0000")) bad += "${item.input}: «${p.spoken}» does not start with the TSV value of ${dist.key}"
            if (bad.size > 30) break
        }
        assertEquals(bad.take(30).joinToString("\n"), 0, bad.size)
    }

    @Test
    fun ac28EveryNonDistanceEntryIsProduced() {
        val produced = HashSet<Segment>()
        for (item in VoiceDomain.items) item.prompt?.segments?.let { produced += it }
        val orphans = inv.entries
            .filter { (key, _, _) -> !key.startsWith("dist.") && !key.startsWith("cont.") }
            .map { (key, take, _) -> Segment(key, take) }
            .filter { it !in produced }
        assertEquals("AC 28: orphan inventory entries ${orphans.map { it.token }}", 0, orphans.size)
    }

    @Test
    fun d102NoContinueOnAt995KmButTheNextManeuverStillPlays() {
        val mn = TestStrings.of(Lang.MN)
        assertNull(VoiceText.prompt(VoiceContent.ContinueOn(995_000.0), Lang.MN, mn, inv))
        assertNotNull("English keeps it (D96)", VoiceText.prompt(VoiceContent.ContinueOn(995_000.0), Lang.EN, TestStrings.of(Lang.EN), inv))
        // A schedule with a 1,000 km stretch: the continue-on prompt is triggered but has no Mongolian text; the
        // GuidanceCore reports it suppressed, and the next manoeuvre's own prompts still play.
        val plan = Plans.plan(Plans.depart to 300.0, Plans.right to 1_000_000.0, Plans.left to 500.0, Plans.arrive to 0.0)
        val s = VoiceScheduler(walk = false)
        s.start(plan, 0)
        s.evaluate(VoiceScheduler.Input(10_000, plan, 0, 100.0, 20.0))
        val cont = s.evaluate(VoiceScheduler.Input(20_000, plan, 1, 999_990.0, 20.0))!!
        assertEquals(PromptKind.CONTINUE_ON, cont.kind)
        assertNull(VoiceText.prompt(cont.content, Lang.MN, mn, inv))
        s.onPromptSuppressed(cont.maneuver!!, cont.triggerAtMs)
        // GPS restore after the suppressed prompt: the manoeuvre counts as not announced, so a catch-up follows.
        s.onGpsRestored()
        val catchUp = s.evaluate(VoiceScheduler.Input(30_000, plan, 1, 999_000.0, 20.0))
        assertEquals(PromptKind.CATCH_UP, catchUp?.kind)
        assertNull("a catch-up continue-on ≥ 995 km is also silent in Mongolian", VoiceText.prompt(catchUp!!.content, Lang.MN, mn, inv))
        s.onPromptSuppressed(catchUp.maneuver!!, catchUp.triggerAtMs)
        val heard = ArrayList<PromptKind>()
        var t = 40_000L
        var d = 2_100.0
        while (d > 0) {
            s.evaluate(VoiceScheduler.Input(t, plan, 1, d, 20.0))?.let { p ->
                heard += p.kind!!
                assertNotNull("the next manoeuvre's prompts have text", VoiceText.prompt(p.content, Lang.MN, mn, inv))
            }
            d -= 20.0
            t += 1_000
        }
        assertEquals(listOf(PromptKind.EARLY, PromptKind.MAIN, PromptKind.NOW), heard)
    }
}

package mn.navmn.app.qa

import mn.navmn.app.i18n.Lang
import mn.navmn.app.instructions.VoiceText
import mn.navmn.app.support.TestStrings
import mn.navmn.app.instructions.VoiceContent
import mn.navmn.app.voiceplan.PromptKind
import mn.navmn.app.voiceplan.VoiceScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAV-005 QA, AC 32 as changed by PO decision D67 (2026-10-01): "No generated voice text contains «1000 метрт»
 * (Mongolian) or "1000 meters" (English)". Test plan id TC-C01 (defect NAV-005-D12).
 *
 * The replays (TC-R13 golden, TC-R23) never reach it, so this drives the production [VoiceScheduler] and [VoiceText]
 * directly on the recorded G9 route, whose final step (last turn → `arrive`) is 2,990 m long, over every catch-up
 * distance from 30 m to 2 km in 0.5 m steps. The two §4.3 catch-up paths that GuidanceCore calls are used exactly as
 * it calls them:
 *  - GPS restored (GuidanceCore.onGpsRestored → [VoiceScheduler.onGpsRestored]): the signal was lost before the last
 *    turn and comes back on the final step (e.g. the destination 980 m ahead);
 *  - route active (GuidanceCore.applyRoute / endEpisodeOnOldRoute → [VoiceScheduler.onRouteActive]): a reroute or a
 *    return to the old route with `arrive` as the upcoming manoeuvre (e.g. 980 m ahead).
 * For `arrive` the catch-up renders the approaching prompt (glossary A11), whose distance uses its own metre rounding
 * (975–1,024 m → «1000 метрт …»).
 */
class QaVoiceCatchUpTest {
    private val g9 = planOf(QaGpx.routeBytes("G9"))
    private val last = g9.steps.lastIndex
    private val banned = Regex("1000 метрт|1000 meter")

    private fun catchUpTexts(restore: Boolean, d: Double, lang: Lang): String? {
        val s = VoiceScheduler(walk = false)
        s.start(g9, 0)
        if (restore) s.onGpsRestored() else s.onRouteActive()
        val prompt = s.evaluate(VoiceScheduler.Input(60_000, g9, last - 1, d, 22.0)) ?: return null
        return VoiceText.render(prompt.content, lang, TestStrings.of(lang))
    }

    @Test
    fun tcC01_d67NoThousandMetresInCatchUpApproachingPrompt() {
        check(g9.steps[last].maneuver.type == "arrive" && g9.steps[last - 1].distance >= 1_000.0) { "G9 fixture changed: final step ${g9.steps[last - 1].distance} m" }
        val hits = LinkedHashSet<String>()
        var produced = 0
        for (restore in listOf(true, false)) {
            for (lang in listOf(Lang.MN, Lang.EN)) {
                var d = 30.0
                while (d < 2_000.0) { // arrive has no early threshold and no continue-on, so every d < 2 km takes this path
                    val text = catchUpTexts(restore, d, lang)
                    if (text != null) produced++
                    if (text != null && banned.containsMatchIn(text)) hits += "${if (restore) "GPS restored" else "route active"} catch-up, $lang, arrive ${d} m ahead: «$text»"
                    d += 0.5
                }
            }
        }
        // Observation only (not asserted here; AC 32's prefix rule is written for manoeuvre prompts): the same path
        // states metres from 995 m on, e.g. 1,500 m ahead.
        println("TC-C01 observation, 1,500 m: «${catchUpTexts(true, 1_500.0, Lang.MN)}» / «${catchUpTexts(false, 1_500.0, Lang.EN)}»")
        assertTrue("scan would be vacuous: no catch-up prompt produced", produced > 0)
        val sample = hits.filter { it.contains(" 975.0 m") || it.contains(" 980.0 m") || it.contains(" 1024.5 m") }
        assertTrue(
            "AC 32 (D67): ${hits.size} catch-up voice texts contain «1000 метрт» / \"1000 meters\", e.g.\n" + sample.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /**
     * TC-C02, navigation-ux v0.3 §4.3 `arrive` sub-rule (NAV-005-D12 fix, orchestrator option b; AC 32 / D67, AC 45,
     * AC 52). On the recorded G9 plan, over the whole final step (30 m to 2,990 m, 0.5 m steps), both catch-up paths,
     * mn and en, the catch-up for an upcoming `arrive` at distance d is:
     *  - d <= 500 m: the approaching prompt A11 with the current d prefix («{n} метрт очих газартаа ирнэ» /
     *    "In {n} meters, you will arrive"), n <= 500;
     *  - 500 m < d < 2 km: no prompt;
     *  - d >= 2 km: "continue on" A12 («{n} километр үргэлжлүүлэн явна уу» / "Continue for {n} kilometers"), as in the
     *    §4.3 example 2,400 m -> «2,4 километр үргэлжлүүлэн явна уу».
     * Then, after a suppressed catch-up, the normal approaching prompt fires exactly once at its main trigger (<= 500 m).
     */
    @Test
    fun tcC02_d12ArriveCatchUpBandsAndLaterApproachingPrompt() {
        val finalStep = g9.steps[last - 1].distance
        check(g9.steps[last].maneuver.type == "arrive" && finalStep >= 2_500.0) { "G9 fixture changed: final step $finalStep m" }
        val a11 = mapOf(Lang.MN to Regex("^(\\d+) метрт очих газартаа ирнэ$"), Lang.EN to Regex("^In (\\d+) meters?, you will arrive$"))
        val a12 = mapOf(Lang.MN to Regex("^\\d+(,\\d)? километр үргэлжлүүлэн явна уу$"), Lang.EN to Regex("^Continue for \\d+(\\.\\d)? kilometers?$"))
        val problems = ArrayList<String>()
        val counts = IntArray(3)
        for (restore in listOf(true, false)) {
            val path = if (restore) "GPS restored" else "route active"
            for (lang in listOf(Lang.MN, Lang.EN)) {
                var d = 30.0
                while (d <= finalStep) {
                    val s = VoiceScheduler(walk = false)
                    s.start(g9, 0)
                    if (restore) s.onGpsRestored() else s.onRouteActive()
                    val p = s.evaluate(VoiceScheduler.Input(60_000, g9, last - 1, d, 22.0))
                    val text = p?.let { VoiceText.render(it.content, lang, TestStrings.of(lang)) }
                    val where = "$path, $lang, arrive $d m ahead"
                    when {
                        d <= 500.0 -> {
                            counts[0]++
                            val n = text?.let { a11.getValue(lang).find(it)?.groupValues?.get(1)?.toInt() }
                            if (p?.kind != PromptKind.CATCH_UP || p.content !is VoiceContent.Approaching || n == null || n > 500) problems += "$where: expected A11 catch-up, got ${p?.kind} «$text»"
                        }
                        d < 2_000.0 -> {
                            counts[1]++
                            if (p != null) problems += "$where: expected no catch-up prompt, got ${p.kind} «$text»"
                        }
                        else -> {
                            counts[2]++
                            if (p?.kind != PromptKind.CATCH_UP || p.content !is VoiceContent.ContinueOn || !a12.getValue(lang).matches(text!!)) problems += "$where: expected A12 continue-on catch-up, got ${p?.kind} «$text»"
                        }
                    }
                    d += 0.5
                }
            }
        }
        // §4.3 example, word for word.
        val at2400 = VoiceScheduler(walk = false).also { it.start(g9, 0); it.onRouteActive() }.evaluate(VoiceScheduler.Input(60_000, g9, last - 1, 2_400.0, 22.0))
        assertEquals("§4.3 example 2,400 m", "2,4 километр үргэлжлүүлэн явна уу", at2400?.let { VoiceText.render(it.content, Lang.MN, TestStrings.of(Lang.MN)) })

        // After a suppressed catch-up (980 m, 1,000 m, 1,500 m, 1,999.5 m), drive on at 22 m/s with the clock following the
        // drive, 1 Hz: exactly one approaching prompt, at d <= 500 m, the normal (non-catch-up) prompt.
        for (restore in listOf(true, false)) for (start in listOf(980.0, 1_000.0, 1_500.0, 1_999.5)) {
            val s = VoiceScheduler(walk = false)
            s.start(g9, 0)
            if (restore) s.onGpsRestored() else s.onRouteActive()
            val heard = ArrayList<Pair<Double, String>>()
            var t = 60_000L
            var d = start
            while (d > 30.0) {
                s.evaluate(VoiceScheduler.Input(t, g9, last - 1, d, 22.0))?.let {
                    heard += d to "${it.kind} «${VoiceText.render(it.content, Lang.MN, TestStrings.of(Lang.MN))}»"
                }
                d -= 22.0
                t += 1_000
            }
            val where = "${if (restore) "GPS restored" else "route active"} at $start m"
            if (heard.size != 1 || heard[0].first > 500.0 || !heard[0].second.startsWith("${PromptKind.MAIN} «") ||
                !a11.getValue(Lang.MN).matches(heard[0].second.substringAfter("«").removeSuffix("»"))
            ) problems += "$where: expected one MAIN approaching prompt at <= 500 m, heard $heard"
        }
        println("TC-C02 scanned: ${counts[0]} at <= 500 m, ${counts[1]} at 500 m-2 km, ${counts[2]} at >= 2 km")
        assertTrue("scan would be vacuous", counts.all { it > 0 })
        assertTrue("navigation-ux §4.3 v0.3 (D12): ${problems.size} problems, first:\n" + problems.take(10).joinToString("\n"), problems.isEmpty())
    }
}

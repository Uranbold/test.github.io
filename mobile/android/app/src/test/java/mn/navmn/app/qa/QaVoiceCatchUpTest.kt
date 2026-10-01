package mn.navmn.app.qa

import mn.navmn.app.i18n.Lang
import mn.navmn.app.instructions.VoiceText
import mn.navmn.app.support.TestStrings
import mn.navmn.app.voiceplan.VoiceScheduler
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
}

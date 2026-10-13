package mn.navmn.app.voice

import mn.navmn.app.i18n.Lang
import mn.navmn.app.instructions.KeyResult
import mn.navmn.app.instructions.ManeuverKey
import mn.navmn.app.instructions.VoiceContent
import mn.navmn.app.instructions.VoicePrompt
import mn.navmn.app.instructions.VoiceText
import mn.navmn.app.support.TestStrings
import mn.navmn.app.support.TestVoice
import mn.navmn.app.voiceplan.VoiceProfile

/**
 * NAV-016 AC 27: the Mongolian voice generator domain, enumerated analytically. The prompt text depends on the costing
 * and the prompt kind only through which contents they can produce, so the enumeration covers the union of:
 *  - every manoeuvre row (all [ManeuverKey]s except `arrive*`, roundabout `exit` 1–10, > 10 (11) and missing), for the
 *    kinds early (no chain), main, now and catch-up of `auto`, `pedestrian` and `bicycle`: d = 0…2,000 m in 1 m steps
 *    plus the AC 27 boundary values;
 *  - every chainable `{first}` (manoeuvre and depart) × every non-`arrive` `{second}`, at one d per prefix key (the first
 *    d of 0…2,000 m that gives that key, plus "no prefix");
 *  - approaching at 30–500 m (1 m steps), the arrival prompts, continue-on from 2,000 to 1,000,000 m in 10 m steps plus
 *    99,499 / 99,500 / 994,999 / 995,000 m, and the fixed prompts.
 * Shared by VoiceDomainTest and VoiceDurationCheck (computed once per test JVM).
 */
object VoiceDomain {
    data class Item(val input: String, val content: VoiceContent, val prompt: VoicePrompt?)

    /** The costings and the kinds each one can produce (navigation-ux §4.2 table; documentation of the coverage). */
    val COSTINGS: Map<VoiceProfile, List<String>> = mapOf(
        VoiceProfile.CAR to listOf("early", "main", "now", "catch-up"),
        VoiceProfile.WALK to listOf("main", "now", "catch-up"),
        VoiceProfile.BIKE to listOf("main", "now", "catch-up"),
    )

    val BOUNDARIES = listOf(29.99, 30.0, 94.99, 95.0, 974.99, 975.0, 994.99, 995.0, 1_249.99, 1_250.0, 1_749.99, 1_750.0)

    /** Every non-`arrive` manoeuvre row (the AC 27 rows, roundabout exit 1–10, 11 and missing). */
    val maneuvers: List<KeyResult> by lazy {
        ManeuverKey.entries.filter { !it.isArrive && it != ManeuverKey.ROUNDABOUT_EXIT }.map { KeyResult(it) } +
            (1..11).map { KeyResult(ManeuverKey.ROUNDABOUT_EXIT, it) } + KeyResult(ManeuverKey.ROUNDABOUT_EXIT, null)
    }

    val arrivals: List<KeyResult> = listOf(ManeuverKey.ARRIVE, ManeuverKey.ARRIVE_LEFT, ManeuverKey.ARRIVE_RIGHT).map { KeyResult(it) }

    private val mn = TestStrings.of(Lang.MN)

    private fun item(input: String, c: VoiceContent) = Item(input, c, VoiceText.prompt(c, Lang.MN, mn, TestVoice.inventory))

    /** One d per distance prefix key in 0…2,000 m (and 20 m for "no prefix"). */
    val prefixDistances: List<Double> by lazy {
        val seen = LinkedHashMap<String, Double>()
        seen["none"] = 20.0
        var d = 30.0
        while (d <= 2_000.0) {
            VoiceText.prefixDistance(d, Lang.MN)?.let { seen.putIfAbsent(VoiceText.distanceKey(it), d) }
            d += 1.0
        }
        seen.values.toList()
    }

    val items: List<Item> by lazy {
        val out = ArrayList<Item>(300_000)
        val ds = (0..2_000).map { it.toDouble() } + BOUNDARIES
        for (key in maneuvers) for (d in ds) out += item("maneuver ${VoiceText.instructionKey(key)} d=$d", VoiceContent.Maneuver(key, d))
        for (first in maneuvers) for (second in maneuvers) for (d in prefixDistances) {
            out += item("chained ${VoiceText.instructionKey(first)} + ${VoiceText.instructionKey(second)} d=$d", VoiceContent.Maneuver(first, d, second))
        }
        for (first in maneuvers.filter { it.key.isDepart }) out += item("depart ${first.key.id}", VoiceContent.Depart(first))
        for (first in maneuvers.filter { it.key.isDepart }) for (second in maneuvers) {
            out += item("depart ${first.key.id} + ${VoiceText.instructionKey(second)}", VoiceContent.Depart(first, second))
        }
        for (d in (30..500).map { it.toDouble() }) out += item("approaching d=$d", VoiceContent.Approaching(d))
        for (key in arrivals) out += item("arrival ${key.key.id}", VoiceContent.Arrival(key))
        var c = 2_000.0
        while (c <= 1_000_000.0) {
            out += item("continue-on d=$c", VoiceContent.ContinueOn(c))
            c += 10.0
        }
        for (d in listOf(99_499.0, 99_500.0, 994_999.0, 995_000.0)) out += item("continue-on d=$d", VoiceContent.ContinueOn(d))
        for (f in listOf(VoiceContent.OffRoute, VoiceContent.GpsLost, VoiceContent.GpsRestored)) out += item("fixed $f", f)
        out
    }
}

package mn.navmn.app.instructions

import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.PluralKey
import mn.navmn.app.i18n.Plurals
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Strings
import mn.navmn.app.i18n.Templates
import mn.navmn.app.voice.pack.Segment
import mn.navmn.app.voice.pack.Take
import mn.navmn.app.voice.pack.VoiceInventory

/** What a voice prompt says (navigation-ux §4.1). Language-neutral; rendered by [VoiceText]. */
sealed interface VoiceContent {
    /** A manoeuvre prompt with the distance prefix from [distanceM] (none below 30 m), optionally chained (A13). */
    data class Maneuver(val key: KeyResult, val distanceM: Double, val then: KeyResult? = null) : VoiceContent

    /** The depart text at the start (§4.3), optionally chained with the first manoeuvre. */
    data class Depart(val key: KeyResult, val then: KeyResult? = null) : VoiceContent

    /** «{n} метрт очих газартаа хүрнэ» (A11). */
    data class Approaching(val distanceM: Double) : VoiceContent

    /** «Та очих газартаа ирлээ» or the side variant (AC 55). */
    data class Arrival(val key: KeyResult) : VoiceContent

    /** «{n} километр үргэлжлүүлэн явна уу» (A12). */
    data class ContinueOn(val distanceM: Double) : VoiceContent

    data object OffRoute : VoiceContent
    data object GpsLost : VoiceContent
    data object GpsRestored : VoiceContent
}

/**
 * The three outputs of one prompt (NAV-016 AC 22, ADR-0010 decision 2): the display text (digits; the golden-set
 * text), the spoken text (glossary C3a, what the clip pack says) and the ordered segment list. English: [spoken] =
 * [display], [segments] empty (D96).
 */
data class VoicePrompt(val display: String, val spoken: String, val segments: List<Segment>)

/**
 * Voice text generator (navigation-ux §4.1, NAV-005 AC 32–33, glossary C3, C3a, C4, A8–A13). Pure: no device, no TTS
 * (AC 40). Units are spelled out («метрт», «километрт»), numbers stay digits, Mongolian decimal comma, English point.
 * English distance templates have singular and plural forms (NAV-005-D4): the singular only when the formatted
 * number is exactly "1" ([Plurals.isOne]); Mongolian items are identical.
 *
 * NAV-016 (the D95 coordinated change, AC 22–25): Mongolian km rounding is D92 for prefixes and D94 for continue-on,
 * with no continue-on prompt at 995 km or more (D102); [prompt] adds the spoken text and the segment list. The spoken
 * text comes only from the [VoiceInventory] (C3a: no number is composed in code). English is unchanged (D96).
 */
object VoiceText {
    /** Distance prefix number and unit for [d] metres; null below 30 m (no prefix). */
    data class Distance(val number: String, val kilometres: Boolean)

    fun prefixDistance(d: Double, lang: Lang): Distance? {
        if (!d.isFinite() || d < 30.0) return null
        if (d < 95.0) return Distance((Math.round(d / 10.0) * 10).toString(), false)
        if (d < 995.0) {
            val m = Math.round(d / 50.0) * 50
            // D67 (NAV-005 AC 32): a metre value that rounds to 1000 is spoken «1 километрт» / "In 1 kilometer",
            // never «1000 метрт» / "1000 meters" (975 ≤ d < 995 m).
            return if (m >= 1000L) Distance("1", true) else Distance(m.toString(), false)
        }
        return Distance(if (lang == Lang.MN) mongolianPrefixKilometres(d) else kilometres(d, lang), true)
    }

    /** D92 (Mongolian prefix, d ≥ 995 m): «1» below 1,250 m, «1,5» below 1,750 m, then whole km half up. */
    fun mongolianPrefixKilometres(d: Double): String = when {
        d < 1_250.0 -> "1"
        d < 1_750.0 -> "1,5"
        else -> Math.round(d / 1000.0).toString()
    }

    /** D94 (Mongolian continue-on): whole km below 99,500 m, then the nearest 10 km; null at ≥ 995 km (D102). */
    fun mongolianContinueKilometres(d: Double): String? = when {
        !d.isFinite() || d >= 995_000.0 -> null
        d < 99_500.0 -> Math.round(d / 1000.0).toString()
        else -> (Math.round(d / 10_000.0) * 10).toString()
    }

    /** One decimal below 9,950 m («1,5», «,0» dropped), whole kilometres from 9,950 m. */
    fun kilometres(d: Double, lang: Lang): String {
        if (d >= 9950.0) return Math.round(d / 1000.0).toString()
        val tenths = Math.round(d / 100.0)
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0L) whole.toString() else whole.toString() + (if (lang == Lang.MN) "," else ".") + frac
    }

    /** Metres for the approaching prompt (A11): the prefix rule in metres (main and catch-up prompts are ≤ 500 m, §4.3). */
    private fun metres(d: Double): String =
        if (d < 95.0) (Math.round(d / 10.0) * 10).toString() else (Math.round(d / 50.0) * 50).toString()

    /** The instruction part of a manoeuvre (voice forms: A10 roundabout with the C4 ordinal). */
    fun instruction(r: KeyResult, strings: Strings): String {
        if (r.key == ManeuverKey.ROUNDABOUT_EXIT) {
            val n = r.n
            return if (n != null && n in 1..10) {
                Templates.fill(strings[StringKey.VOICE_ROUNDABOUT_EXIT], "ordinal" to strings[ORDINALS[n - 1]])
            } else {
                strings[StringKey.MANEUVER_ROUNDABOUT_ENTER]
            }
        }
        var t = strings[r.key.text]
        if (r.n != null) t = Templates.fill(t, "n" to r.n.toString())
        return t
    }

    private fun prefixed(d: Double, instruction: String, lang: Lang, strings: Strings): String {
        val dist = prefixDistance(d, lang) ?: return BannerText.capitalizeFirst(instruction, lang)
        val prefix = Plurals.fill(strings, if (dist.kilometres) PluralKey.VOICE_PREFIX_KM else PluralKey.VOICE_PREFIX_M, dist.number)
        val rest = BannerText.lowercaseFirst(instruction, lang)
        return if (lang == Lang.MN) "$prefix $rest" else "$prefix, $rest"
    }

    private fun chained(first: String, then: KeyResult?, lang: Lang, strings: Strings): String {
        if (then == null) return first
        val second = BannerText.lowercaseFirst(instruction(then, strings), lang)
        return BannerText.capitalizeFirst(
            Templates.fill(strings[StringKey.VOICE_THEN], "first" to first, "second" to second),
            lang,
        )
    }

    /** The display text of [c] in [lang]; "" where no prompt is spoken (D102). [prompt] returns null for that case. */
    fun render(c: VoiceContent, lang: Lang, strings: Strings): String = display(c, lang, strings).orEmpty()

    /** The display text of [c] in [lang]; null where no prompt is spoken (D102: Mongolian continue-on ≥ 995 km). */
    fun display(c: VoiceContent, lang: Lang, strings: Strings): String? = when (c) {
        is VoiceContent.Maneuver -> chained(prefixed(c.distanceM, instruction(c.key, strings), lang, strings), c.then, lang, strings)
        is VoiceContent.Depart -> chained(BannerText.capitalizeFirst(instruction(c.key, strings), lang), c.then, lang, strings)
        is VoiceContent.Approaching -> BannerText.capitalizeFirst(
            Plurals.fill(strings, PluralKey.VOICE_APPROACHING, metres(c.distanceM)),
            lang,
        )
        is VoiceContent.Arrival -> BannerText.capitalizeFirst(instruction(c.key, strings), lang)
        is VoiceContent.ContinueOn -> (if (lang == Lang.MN) mongolianContinueKilometres(c.distanceM) else kilometres(c.distanceM, lang))
            ?.let { BannerText.capitalizeFirst(Plurals.fill(strings, PluralKey.VOICE_CONTINUE_ON, it), lang) }
        VoiceContent.OffRoute -> strings[StringKey.VOICE_OFF_ROUTE]
        VoiceContent.GpsLost -> strings[StringKey.NAV_GPS_LOST]
        VoiceContent.GpsRestored -> strings[StringKey.NAV_GPS_RESTORED]
    }

    /** ADR-0010 decision 2: the segment key of an instruction (`continue` → `instr.straight`, roundabout exit 1–10). */
    fun instructionKey(r: KeyResult): String = when (r.key) {
        ManeuverKey.ROUNDABOUT_EXIT -> r.n?.takeIf { it in 1..10 }?.let { "instr.roundabout.exit.$it" } ?: "instr.roundabout.enter"
        ManeuverKey.CONTINUE -> "instr.straight"
        else -> "instr." + r.key.id
    }

    /** The glossary-c3-spoken.tsv key of a distance prefix: `dist.m.300`, `dist.km.1`, `dist.km.1_5`, `dist.km.2`. */
    fun distanceKey(dist: Distance): String =
        if (dist.kilometres) "dist.km." + dist.number.replace(',', '_') else "dist.m." + dist.number

    private fun chainSegments(first: List<Segment>, then: KeyResult?): List<Segment> {
        if (then == null) return first
        val last = first.last()
        return first.dropLast(1) + Segment(last.key, Take.NON_FINAL) + Segment(JOIN_THEN, Take.FINAL) +
            Segment(instructionKey(then), Take.FINAL)
    }

    /** The Mongolian segment list of [c] (ADR-0010 decision 2); null where no prompt is spoken (D102). */
    fun segments(c: VoiceContent): List<Segment>? = when (c) {
        is VoiceContent.Maneuver -> {
            val instr = Segment(instructionKey(c.key), Take.FINAL)
            val dist = prefixDistance(c.distanceM, Lang.MN)
            chainSegments(if (dist == null) listOf(instr) else listOf(Segment(distanceKey(dist), Take.FINAL), instr), c.then)
        }
        is VoiceContent.Depart -> chainSegments(listOf(Segment(instructionKey(c.key), Take.FINAL)), c.then)
        is VoiceContent.Approaching -> listOf(Segment("dist.m." + metres(c.distanceM), Take.FINAL), Segment("tail.approach", Take.FINAL))
        is VoiceContent.Arrival -> listOf(Segment(instructionKey(c.key), Take.FINAL))
        is VoiceContent.ContinueOn -> mongolianContinueKilometres(c.distanceM)?.let {
            listOf(Segment("cont.km.$it", Take.FINAL), Segment("tail.continue", Take.FINAL))
        }
        VoiceContent.OffRoute -> listOf(Segment("fixed.offRoute", Take.FINAL))
        VoiceContent.GpsLost -> listOf(Segment("fixed.gpsLost", Take.FINAL))
        VoiceContent.GpsRestored -> listOf(Segment("fixed.gpsRestored", Take.FINAL))
    }

    /**
     * pack-format §6 (AC 24): the segments' spoken texts joined with one space, except «, » directly before `join.then`.
     * null if a segment has no inventory entry (a defect the domain test rules out).
     */
    fun spokenText(segments: List<Segment>, inventory: VoiceInventory): String? {
        val sb = StringBuilder()
        for ((i, s) in segments.withIndex()) {
            val text = inventory.spoken(s.key, s.take) ?: return null
            if (i > 0) sb.append(if (s.key == JOIN_THEN) ", " else " ")
            sb.append(text)
        }
        return sb.toString()
    }

    /**
     * NAV-016 AC 22: display text, spoken text and segments of [c]; null = no prompt (D102). A segment without an inventory
     * entry (impossible inside the AC 27 domain) leaves the display text, first letter lower case, as the spoken text;
     * the voice output then plays the chime for that prompt (never a partial prompt, AC 36).
     */
    fun prompt(c: VoiceContent, lang: Lang, strings: Strings, inventory: VoiceInventory): VoicePrompt? {
        val display = display(c, lang, strings) ?: return null
        if (lang != Lang.MN) return VoicePrompt(display, display, emptyList())
        val segments = segments(c).orEmpty()
        return VoicePrompt(display, spokenText(segments, inventory) ?: BannerText.lowercaseFirst(display, lang), segments)
    }

    const val JOIN_THEN = "join.then"

    private val ORDINALS = listOf(
        StringKey.VOICE_ORDINAL_1, StringKey.VOICE_ORDINAL_2, StringKey.VOICE_ORDINAL_3, StringKey.VOICE_ORDINAL_4,
        StringKey.VOICE_ORDINAL_5, StringKey.VOICE_ORDINAL_6, StringKey.VOICE_ORDINAL_7, StringKey.VOICE_ORDINAL_8,
        StringKey.VOICE_ORDINAL_9, StringKey.VOICE_ORDINAL_10,
    )
}

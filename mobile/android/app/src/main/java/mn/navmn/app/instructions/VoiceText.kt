package mn.navmn.app.instructions

import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.PluralKey
import mn.navmn.app.i18n.Plurals
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Strings
import mn.navmn.app.i18n.Templates

/** What a voice prompt says (navigation-ux §4.1). Language-neutral; rendered by [VoiceText]. */
sealed interface VoiceContent {
    /** A manoeuvre prompt with the distance prefix from [distanceM] (none below 30 m), optionally chained (A13). */
    data class Maneuver(val key: KeyResult, val distanceM: Double, val then: KeyResult? = null) : VoiceContent

    /** The depart text at the start (§4.3), optionally chained with the first manoeuvre. */
    data class Depart(val key: KeyResult, val then: KeyResult? = null) : VoiceContent

    /** «{n} метрт очих газартаа ирнэ» (A11). */
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
 * Voice text generator (navigation-ux §4.1, NAV-005 AC 32–33, glossary C3, C4, A8–A13). Pure: no device, no TTS
 * (AC 40). Units are spelled out («метрт», «километрт»), numbers stay digits, Mongolian decimal comma, English point.
 * English distance templates have singular and plural forms (NAV-005-D4): the singular only when the formatted
 * number is exactly "1" ([Plurals.isOne]); Mongolian items are identical.
 */
object VoiceText {
    /** Distance prefix number and unit for [d] metres; null below 30 m (no prefix). */
    data class Distance(val number: String, val kilometres: Boolean)

    fun prefixDistance(d: Double, lang: Lang): Distance? {
        if (!d.isFinite() || d < 30.0) return null
        if (d < 95.0) return Distance((Math.round(d / 10.0) * 10).toString(), false)
        if (d < 995.0) return Distance((Math.round(d / 50.0) * 50).toString(), false)
        return Distance(kilometres(d, lang), true)
    }

    /** One decimal below 9,950 m («1,5», «,0» dropped), whole kilometres from 9,950 m. */
    fun kilometres(d: Double, lang: Lang): String {
        if (d >= 9950.0) return Math.round(d / 1000.0).toString()
        val tenths = Math.round(d / 100.0)
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0L) whole.toString() else whole.toString() + (if (lang == Lang.MN) "," else ".") + frac
    }

    /** Metres for the approaching prompt (A11): the prefix rule in metres (the main prompt is ≤ 500 m). */
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

    fun render(c: VoiceContent, lang: Lang, strings: Strings): String = when (c) {
        is VoiceContent.Maneuver -> chained(prefixed(c.distanceM, instruction(c.key, strings), lang, strings), c.then, lang, strings)
        is VoiceContent.Depart -> chained(BannerText.capitalizeFirst(instruction(c.key, strings), lang), c.then, lang, strings)
        is VoiceContent.Approaching -> BannerText.capitalizeFirst(
            Plurals.fill(strings, PluralKey.VOICE_APPROACHING, metres(c.distanceM)),
            lang,
        )
        is VoiceContent.Arrival -> BannerText.capitalizeFirst(instruction(c.key, strings), lang)
        is VoiceContent.ContinueOn -> BannerText.capitalizeFirst(
            Plurals.fill(strings, PluralKey.VOICE_CONTINUE_ON, kilometres(c.distanceM, lang)),
            lang,
        )
        VoiceContent.OffRoute -> strings[StringKey.VOICE_OFF_ROUTE]
        VoiceContent.GpsLost -> strings[StringKey.NAV_GPS_LOST]
        VoiceContent.GpsRestored -> strings[StringKey.NAV_GPS_RESTORED]
    }

    private val ORDINALS = listOf(
        StringKey.VOICE_ORDINAL_1, StringKey.VOICE_ORDINAL_2, StringKey.VOICE_ORDINAL_3, StringKey.VOICE_ORDINAL_4,
        StringKey.VOICE_ORDINAL_5, StringKey.VOICE_ORDINAL_6, StringKey.VOICE_ORDINAL_7, StringKey.VOICE_ORDINAL_8,
        StringKey.VOICE_ORDINAL_9, StringKey.VOICE_ORDINAL_10,
    )
}

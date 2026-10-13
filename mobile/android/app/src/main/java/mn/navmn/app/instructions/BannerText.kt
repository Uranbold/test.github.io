package mn.navmn.app.instructions

import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.Strings
import mn.navmn.app.i18n.Templates

/**
 * On-screen instruction text (banner, notification title, TalkBack): the NAV-004 AC 27 text for the key, first letter
 * upper case (ADR-0008 §2 step 2). Valhalla's narrative is never an input (AC 26).
 */
object BannerText {
    fun text(r: KeyResult, lang: Lang, strings: Strings): String {
        var t = strings[r.key.text]
        if (r.n != null) t = Templates.fill(t, "n" to r.n.toString())
        return capitalizeFirst(t, lang)
    }

    /**
     * UX audit 2026-10-13 UX-04 (navigation-ux §2.1): in the **displayed** banner and turn-row text the politeness
     * particle («үү» / «уу») is joined to its verb with a no-break space, so it never starts a line alone. Display only:
     * resources, voice text, the notification, TalkBack text and the QA golden texts keep the plain space.
     */
    fun displayText(s: String): String = PARTICLE.replace(s, " $1")

    /** «үү» = U+04AF U+04AF, «уу» = U+0443 U+0443 (escaped: no Cyrillic literal in Kotlin sources, ResourcesTest). */
    private val PARTICLE = Regex(" (\u04AF\u04AF|\u0443\u0443)(?!\\p{L})")

    /** Upper-cases the first letter; idempotent (glossary forms are often lower case). */
    fun capitalizeFirst(s: String, lang: Lang): String =
        if (s.isEmpty()) s else s.substring(0, s.offsetByCodePoints(0, 1)).uppercase(lang.locale) + s.substring(s.offsetByCodePoints(0, 1))

    fun lowercaseFirst(s: String, lang: Lang): String =
        if (s.isEmpty()) s else s.substring(0, s.offsetByCodePoints(0, 1)).lowercase(lang.locale) + s.substring(s.offsetByCodePoints(0, 1))
}

/** `step.name` for display (AC 21; screen spec › Content rules › Street names). "" means the line is omitted. */
object StreetName {
    private val ZERO_WIDTH = Regex("[\u200B\u200C\u200D\uFEFF]")
    private val TRADITIONAL = Regex("[\\s\u202F\u200C\u200D]*[᠀-᢯][᠀-᢯\u202F\u200C\u200D\\s]*")
    private val SPACES = Regex("\\s+")

    fun clean(name: String?): String {
        val noZw = (name ?: "").replace(ZERO_WIDTH, "")
        val noTrad = if (noZw.any { it in '᠀'..'᢯' }) noZw.replace(TRADITIONAL, " ") else noZw
        return noTrad.replace(SPACES, " ").trim()
    }
}

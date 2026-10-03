package mn.navmn.app.search.assist

import java.text.Normalizer
import java.util.Locale

/**
 * ADR-0006 §2.4 Latin → Cyrillic transliteration for search help (port of web/src/search/transliterate.ts with the
 * tables of web/src/search/lexicon.json; ADR-0012 §1). Output is lower case. These tables only rewrite what is SENT to
 * `GET /v1/search`; nothing here is displayed (lines marked scan:data hold OSM/search data, not UI text).
 */
object LatinToCyrillic {
    private val PRECOMPOSED = mapOf('ö' to "ө", 'ő' to "ө", 'ü' to "ү", 'ű' to "ү") // scan:data
    private val FRONT_VOWELS = setOf("ө", "ү") // scan:data
    private val MULTI = listOf(
        "shch" to "щ", "kh" to "х", "ts" to "ц", "ch" to "ч", "sh" to "ш", "zh" to "ж", // scan:data
        "ya" to "я", "yu" to "ю", "yo" to "ё", "ye" to "е", "ii" to "ий", // scan:data
    )
    private const val SHORT_I = "й" // scan:data
    private const val Y_OTHER = "ы" // scan:data
    private const val U_BACK = "у" // scan:data
    private const val U_FRONT = "ү" // scan:data
    private const val O_BACK = "о" // scan:data
    private const val O_FRONT = "ө" // scan:data
    private val SINGLE = mapOf(
        'a' to "а", 'b' to "б", 'c' to "ц", 'd' to "д", 'e' to "э", 'f' to "ф", 'g' to "г", 'h' to "х", 'i' to "и", // scan:data
        'j' to "ж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'p' to "п", 'q' to "к", 'r' to "р", 's' to "с", // scan:data
        't' to "т", 'v' to "в", 'w' to "в", 'x' to "х", 'z' to "з", // scan:data
    )
    private val VOWELS = setOf("a", "e", "i", "o", "u") + FRONT_VOWELS
    private val COMBINING = Regex("[\\u0300-\\u036f]")

    /** Step 1: lower-case (Locale.ROOT), ö ő → ө and ü ű → ү, strip every other combining diacritic. */
    private fun prepare(input: String): String {
        val lower = input.lowercase(Locale.ROOT)
        val sb = StringBuilder(lower.length)
        for (ch in lower) sb.append(PRECOMPOSED[ch] ?: ch)
        return Normalizer.normalize(COMBINING.replace(Normalizer.normalize(sb, Normalizer.Form.NFD), ""), Normalizer.Form.NFC)
    }

    /** Step 3: a word is front if it contains e, ü (ү) or ö (ө) and no a. */
    private fun isFrontWord(w: String): Boolean {
        if (w.contains('a')) return false
        return w.any { it == 'e' || it.toString() in FRONT_VOWELS }
    }

    /** Step 4 for one word (code units, as the web's `w[i]`). */
    private fun word(w: String): String {
        val front = isFrontWord(w)
        val out = StringBuilder()
        var i = 0
        while (i < w.length) {
            val multi = MULTI.firstOrNull { (from, _) -> w.startsWith(from, i) }
            if (multi != null) {
                out.append(multi.second)
                i += multi.first.length
                continue
            }
            val ch = w[i]
            val prev = if (i > 0) w[i - 1].toString() else ""
            val next = if (i + 1 < w.length) w[i + 1].toString() else ""
            when {
                (ch == 'y' || ch == 'i') && prev in VOWELS && next !in VOWELS -> out.append(SHORT_I)
                ch == 'u' -> out.append(if (front) U_FRONT else U_BACK)
                ch == 'o' -> out.append(if (front) O_FRONT else O_BACK)
                ch == 'y' -> out.append(Y_OTHER)
                else -> out.append(SINGLE[ch] ?: ch.toString())
            }
            i += 1
        }
        return out.toString()
    }

    /** Words are split on whitespace and hyphens; the separators are kept as they are. */
    fun convert(input: String): String {
        val s = prepare(input)
        val out = StringBuilder()
        var last = 0
        for (m in Settle.WORD_SEPARATORS.findAll(s)) {
            out.append(word(s.substring(last, m.range.first)))
            out.append(m.value)
            last = m.range.last + 1
        }
        out.append(word(s.substring(last)))
        return out.toString()
    }
}

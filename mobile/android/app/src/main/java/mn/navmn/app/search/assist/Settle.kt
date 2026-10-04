package mn.navmn.app.search.assist

import java.text.Normalizer

/**
 * Settled query (NAV-003 Terms, ADR-0006 §2.1; port of web/src/search/text.ts normalizeQuery / capQueryLength,
 * ADR-0012 §1): Unicode NFC, whitespace runs collapsed to one space, trimmed, cut at 200 UTF-16 code units without
 * splitting a surrogate pair. Pure.
 */
object Settle {
    const val MAX_LENGTH = 200
    const val MIN_LENGTH = 2

    /**
     * The JavaScript `\s` set (ECMAScript WhiteSpace + LineTerminator). The JVM's `\s` is ASCII only and `(?U)\s`
     * differs at U+0085 and U+FEFF (ADR-0012 §1), so the class is explicit.
     */
    private const val WS_CLASS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    val WHITESPACE = Regex("[$WS_CLASS]+")
    /** A run of JS whitespace or hyphens (the word separators of latinToCyrillic). */
    val WORD_SEPARATORS = Regex("[$WS_CLASS-]+")

    /** JS `String.prototype.trim` (same whitespace set). */
    fun jsTrim(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isJsSpace(s[start])) start++
        while (end > start && isJsSpace(s[end - 1])) end--
        return s.substring(start, end)
    }

    fun isJsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
        else -> c in '\u2000'..'\u200A'
    }

    fun settle(raw: String): String = cap(jsTrim(Normalizer.normalize(raw, Normalizer.Form.NFC).replace(WHITESPACE, " ")))

    /** ≤ 200 code units (openapi `q` maxLength) without splitting a surrogate pair, then trimmed. */
    fun cap(s: String): String {
        if (s.length <= MAX_LENGTH) return s
        var out = s.substring(0, MAX_LENGTH)
        if (Character.isHighSurrogate(out.last())) out = out.substring(0, out.length - 1)
        return jsTrim(out)
    }

    fun isSearchable(settled: String): Boolean = settled.length >= MIN_LENGTH
}

/** Letter scripts per code point (ADR-0012 §1: Java equivalents of `\p{L}`, `\p{Script=Latin}`, `\p{Script=Cyrillic}`). */
object Scripts {
    private fun letters(s: String): List<Int> = s.codePoints().toArray().filter { Character.isLetter(it) }

    /** Every letter is Latin script (diacritics included) and there are at least 2 letters (rule B). */
    fun isLatinOnly(s: String): Boolean {
        val l = letters(s)
        return l.size >= 2 && l.all { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN }
    }

    /** Every letter is Cyrillic and there is at least one letter (rule C precondition). */
    fun isCyrillicOnly(s: String): Boolean {
        val l = letters(s)
        return l.isNotEmpty() && l.all { Character.UnicodeScript.of(it) == Character.UnicodeScript.CYRILLIC }
    }
}

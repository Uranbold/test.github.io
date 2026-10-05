package mn.navmn.app.search.offline

import java.text.Normalizer
import java.util.Locale

/**
 * NAV-023 Terms "Fold" and AC 2 "Skeleton": the normalisation the search DB builder (`backend/pack/search_builder.py`,
 * NAV-020 task file §3.6) applies to every stored key, so a query meets its own index (R5). Pure. The tables below are
 * search keys, never displayed (scan:data lines are search data, not UI text).
 *
 * Kept byte-for-byte equivalent to the builder; the shared vector file (AC 3,
 * `backend/pack/vectors/search-normalisation.v1.json`) checks it in `SearchNormalisationVectorsTest`.
 */
object SearchText {
    /** AC 2 step 2: Cyrillic → Latin; «ь» and «ъ» are dropped («ү», «ө», «ё» are folded before). */
    private val CYR2LAT: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "j", 'з' to "z", // scan:data
        'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", // scan:data
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", // scan:data
        'ш' to "sh", 'щ' to "sh", 'ы' to "i", 'э' to "e", 'ю' to "yu", 'я' to "ya", 'ь' to "", 'ъ' to "", // scan:data
    )
    /** Traditional Mongolian script U+1800–U+18AF and U+202F (NAV-003 AC 18; the builder's `TRAD_MONGOLIAN`). */
    private val TRAD = Regex("[\\u1800-\\u18AF\\u202F]+")
    /** Python's `\\s` / `str.isspace()` set (the builder's `WS`): the JVM's `\\s` is ASCII only. */
    private const val PY_WS = "\\t\\n\\u000B\\f\\r\\u001C-\\u001F\\u0085\\p{Z}"
    private val PY_SPACES = Regex("[$PY_WS]+")
    private val RUNS = Regex("([a-z])\\1+")
    private val NON_ALNUM = Regex("[^0-9a-z ]+")
    private val SPACES = Regex("\\s+")
    /** The builder's `words()` separator (Python `[^\w]+`): anything but letters, digits and `_`. */
    private val SEP = Regex("[^\\p{L}\\p{N}_]+")

    /** The builder's `clean`: Traditional script → one space, whitespace runs → one space, trimmed. */
    fun clean(s: String): String = PY_SPACES.replace(TRAD.replace(s, " "), " ").trim(' ')

    /** NAV-023 Terms: lower case, then «ү» → «у», «ө» → «о», «ё» → «е». */
    fun fold(s: String): String = s.lowercase(Locale.ROOT).replace('ү', 'у').replace('ө', 'о').replace('ё', 'е') // scan:data

    /** NAV-023 AC 2 steps 1–4, exactly (the builder's `skeleton`). */
    fun skeleton(s: String): String {
        // 1. fold, then remove Latin diacritics: NFD and drop the combining marks (also turns «й» into «и», which
        //    step 2 maps to "i" either way).
        val nfd = Normalizer.normalize(fold(s), Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for (ch in nfd) if (!isCombining(ch)) sb.append(ch)
        // 2. Cyrillic → Latin.
        val lat = StringBuilder(sb.length + 8)
        for (ch in sb) lat.append(CYR2LAT[ch] ?: ch)
        // 3. kh → h, ts → c, zh → j, then y → i.
        var t = lat.toString().replace("kh", "h").replace("ts", "c").replace("zh", "j").replace('y', 'i')
        // 4. collapse runs of one letter, drop everything but a–z, 0–9 and spaces (in this order, as the builder).
        t = RUNS.replace(t, "$1")
        t = NON_ALNUM.replace(t, "")
        return SPACES.replace(t, " ").trim()
    }

    /** The builder's `words()`: split on non-word characters, empty parts dropped. */
    fun words(s: String): List<String> = s.split(SEP).filter { it.isNotEmpty() }

    /**
     * NAV-020 task file §3.6 note / NAV-023 task SM3 step 3: a Latin spelling writes «ө» and «ү» as "u" ("Khuvsgul",
     * "Ulgii"), whose skeleton (`huvsgul`, `ulgi`) differs from the Cyrillic one (`hovsgol`, `olgi`). Each "u" of a
     * skeleton token of a Latin query may stand for "o": every combination of the first [MAX_U] u's (≤ 8 variants,
     * the token itself first).
     */
    fun latinVowelVariants(token: String): List<String> {
        val positions = token.indices.filter { token[it] == 'u' }.take(MAX_U)
        if (positions.isEmpty()) return listOf(token)
        val out = LinkedHashSet<String>()
        for (mask in 0 until (1 shl positions.size)) {
            val chars = token.toCharArray()
            positions.forEachIndexed { i, p -> if (mask and (1 shl i) != 0) chars[p] = 'o' }
            out += RUNS.replace(String(chars), "$1")
        }
        return out.toList()
    }

    const val MAX_U = 3

    /** Python `unicodedata.combining(ch) != 0`, approximated by general category Mn (equal for every Latin diacritic). */
    private fun isCombining(ch: Char): Boolean = Character.getType(ch) == Character.NON_SPACING_MARK.toInt()
}

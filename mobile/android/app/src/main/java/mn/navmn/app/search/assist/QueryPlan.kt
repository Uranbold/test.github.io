package mn.navmn.app.search.assist

import mn.navmn.app.geo.LatLon
import java.util.Locale

/** How the secondary query of a plan is sent (ADR-0006 §2.3). */
enum class PlanMode(val id: String) { NONE("none"), PARALLEL("parallel"), IF_EMPTY("ifEmpty") }

/** Which `search` requests a settled query produces: at most 2, always (ADR-0006 §2.2–§2.3, ADR-0012 §1). */
sealed interface QueryPlan {
    data object Skip : QueryPlan
    data class Coordinate(val point: LatLon) : QueryPlan
    data class Text(val primary: String, val secondary: String?, val mode: PlanMode, val rule: Char) : QueryPlan {
        /** Every planned query (1 or 2), each ≤ 200 code units. */
        val queries: List<String> get() = listOfNotNull(primary, secondary)
    }
}

/** ADR-0006 §2.2 typed coordinates (port of web/src/search/coords.ts parseCoordinate). */
object CoordinateInput {
    private val COORDINATE = Regex("^(-?\\d{1,2}(?:\\.\\d+)?)(?:\\s*,\\s*|\\s+)(-?\\d{1,3}(?:\\.\\d+)?)$")

    fun parse(settled: String): LatLon? {
        val m = COORDINATE.matchEntire(settled) ?: return null
        // The regex only admits ASCII digits (\d is ASCII on the JVM, as the web's \d without the u flag).
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        return LatLon(lat, lon)
    }
}

/**
 * ADR-0006 §2.3 rules A–D (port of web/src/search/queryPlan.ts). Pure. The district abbreviations and the Russian-layout
 * vowel table come from web/src/search/lexicon.json; they only rewrite what is sent (scan:data lines are search data).
 */
object QueryPlanner {
    /** ADR-0006 §2.3 rule A: an abbreviation also sends the query as typed (default true, as the web). */
    const val ABBREVIATION_SENDS_AS_TYPED = true

    private val ABBREVIATIONS = mapOf(
        "СБД" to "Сүхбаатар дүүрэг", // scan:data
        "БЗД" to "Баянзүрх дүүрэг", // scan:data
        "ХУД" to "Хан-Уул дүүрэг", // scan:data
        "БГД" to "Баянгол дүүрэг", // scan:data
        "ЧД" to "Чингэлтэй дүүрэг", // scan:data
        "СХД" to "Сонгинохайрхан дүүрэг", // scan:data
    )
    private val ABBR_BY_LOWER = ABBREVIATIONS.mapKeys { it.key.lowercase(Locale.ROOT) }
    private val VOWEL_FALLBACK = mapOf('у' to 'ү', 'У' to 'Ү', 'о' to 'ө', 'О' to 'Ө') // scan:data

    /** Rule A: replaces every space-delimited abbreviation token; null when there is none. */
    private fun expandAbbreviations(q: String): String? {
        var found = false
        val out = q.split(" ").joinToString(" ") { tok ->
            val full = ABBR_BY_LOWER[tok.lowercase(Locale.ROOT)]
            if (full == null) tok else {
                found = true
                full
            }
        }
        return if (found) out else null
    }

    private fun hasRussianVowel(q: String) = q.any { it in VOWEL_FALLBACK }

    private fun vowelVariant(q: String): String = buildString(q.length) { for (ch in q) append(VOWEL_FALLBACK[ch] ?: ch) }

    /** Every planned `q` ≤ 200 code units; a duplicate secondary (after settling) is dropped. Cap first, then compare. */
    private fun text(rawPrimary: String, rawSecondary: String?, mode: PlanMode, rule: Char): QueryPlan.Text {
        val primary = Settle.cap(rawPrimary)
        val secondary = rawSecondary?.let { Settle.cap(it) }
        if (secondary == null || Settle.settle(secondary) == Settle.settle(primary)) return QueryPlan.Text(primary, null, PlanMode.NONE, rule)
        return QueryPlan.Text(primary, secondary, mode, rule)
    }

    fun plan(input: String): QueryPlan {
        val q = Settle.settle(input)
        if (!Settle.isSearchable(q)) return QueryPlan.Skip
        CoordinateInput.parse(q)?.let { return QueryPlan.Coordinate(it) }
        val expanded = expandAbbreviations(q)
        if (expanded != null) {
            return if (ABBREVIATION_SENDS_AS_TYPED) text(expanded, q, PlanMode.PARALLEL, 'A') else text(expanded, null, PlanMode.NONE, 'A')
        }
        if (Scripts.isLatinOnly(q)) return text(q, LatinToCyrillic.convert(q), PlanMode.PARALLEL, 'B')
        if (Scripts.isCyrillicOnly(q) && hasRussianVowel(q)) return text(q, vowelVariant(q), PlanMode.IF_EMPTY, 'C')
        return text(q, null, PlanMode.NONE, 'D')
    }
}

package mn.navmn.app.search.offline

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.search.PhotonFeature
import mn.navmn.app.search.SearchClient
import mn.navmn.app.search.assist.Scripts
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * NAV-023 section B (ADR-0017 §3, task file SM3–SM5): ONE planned query (or one reverse point) answered from the
 * installed `search.sqlite` (schema 1, NAV-020 task file §3.4). The ADR-0012 plan itself (coordinates, abbreviations,
 * the Latin → Cyrillic variant, the Russian-layout vowel variant, the merge) stays in
 * [mn.navmn.app.search.assist.QueryPlanner] and [mn.navmn.app.search.SearchController], exactly as for the gateway:
 * every planned query that would be one `search` request is one call of [search].
 *
 * Port of the backend reference engine `backend/pack/search_engine.py`, **RANKING_VERSION [RANKING_VERSION]** (frozen
 * on the golden set before QA's held-out set was written, D198), so the reference measurement and the app agree (R1):
 *  1. **match**: the folded words of the query on `{names skel ctx}` (an AND, prefix on the last word) OR its skeleton
 *     words on `skel` (likewise); bm25 weights names 10, skel 5, ctx 1; the best [CANDIDATES] rows.
 *  1a. (app only, task SM3 step 3) with 0 hits, a Latin query also tries every `u` of its skeleton as `o` (`Khuvsgul` →
 *     `hovsgol`, `Ulgii` → `olgi`: Latin writes «ө»/«ү» as `u`, which the skeleton alone does not join).
 *  2. with 0 hits: **edit-distance** expansion of the skeleton words over `vocab` (1 edit for 5–8 letters, 2 for ≥ 9;
 *     a word already in `vocab`, or the last word when it starts a `vocab` term, is kept; ≤ [FUZZY_MAX_CANDIDATES] terms
 *     per word, closest then most frequent).
 *  3. still 0 hits: **trigrams** on `place_tri`: an OR of the query's 3-grams, a row kept when ≥ [TRIGRAM_MIN_SHARE]
 *     of them occur in its folded display name.
 *  4. **rank** ([rank]): 3·exact + 1.5·prefix + bm25 relative to the best + 2·importance + 4·proximity, proximity =
 *     1 / (1 + d_km / 2) to the bias point; ties by `place.id`. At most [SearchClient.LIMIT] features (a request's limit).
 *
 * Works on any androidx [SQLiteConnection]: on the phone the `sqlite-bundled` driver; in JVM tests the same driver class
 * with the host build of the same native library. The caller ([OfflineSearch]) serialises calls on one connection.
 * Nothing here logs, and nothing is stored.
 */
class OfflineSearchEngine(private val db: SQLiteConnection) {

    /**
     * `vocab` for the edit-distance step, one per connection (= per file version). [OfflineSearch] preloads it on a
     * second connection right after the file opens ([preload]), so no query waits for it; otherwise the first
     * edit-distance step loads it here.
     */
    @Volatile private var vocabCache: Vocab? = null

    private fun vocab(): Vocab = vocabCache ?: Vocab.load(db).also {
        fileQueries++
        vocabCache = it
    }

    /** Hands over a vocabulary read on another connection to the same file. */
    fun preload(v: Vocab) {
        if (vocabCache == null) vocabCache = v
    }

    /** `vocab` (term → n) with the lookups the edit-distance step needs. */
    class Vocab private constructor(val terms: Map<String, Long>) {
        val sorted: List<String> = terms.keys.sorted()
        val byLength: Map<Int, List<Map.Entry<String, Long>>> = terms.entries.groupBy { it.key.length }

        companion object {
            fun load(db: SQLiteConnection): Vocab {
                val out = HashMap<String, Long>()
                db.prepare("SELECT term, n FROM vocab").use { st -> while (st.step()) out[st.getText(0)] = st.getLong(1) }
                return Vocab(out)
            }
        }
    }

    /** Builder v2 added `place.locality` (still schema 1); a v1 file has none. */
    private val hasLocality: Boolean by lazy {
        db.prepare("SELECT 1 FROM pragma_table_info('place') WHERE name = 'locality'").use { it.step() }
    }

    /** Statements run against the file (tests: typed coordinates cause 0, AC 8). */
    var fileQueries = 0
        private set

    /** `meta.search_schema` of the file (null when missing). */
    fun schema(): Int? = db.prepare("SELECT value FROM meta WHERE key = 'search_schema'").use { st ->
        if (st.step()) st.getText(0).toIntOrNull() else null
    }

    // ------------------------------------------------------------------------------------------------ search

    /** Which step produced the hits (for tests and the gate harness). */
    enum class Stage { MATCH, VOWEL, FUZZY, TRIGRAM, NONE }

    class Answer(val features: List<PhotonFeature>, val stage: Stage)

    fun search(q: String, lang: Lang, bias: LatLon?, limit: Int = SearchClient.LIMIT): List<PhotonFeature> = query(q, lang, bias, limit).features

    fun query(q: String, lang: Lang, bias: LatLon?, limit: Int = SearchClient.LIMIT): Answer {
        val qc = SearchText.clean(q)
        val ftoks = SearchText.words(SearchText.fold(qc))
        val stoks = SearchText.skeleton(qc).split(' ').filter { it.isNotEmpty() }
        if (ftoks.isEmpty() && stoks.isEmpty()) return Answer(emptyList(), Stage.NONE)
        var stage = Stage.MATCH
        var hits = match(ftoks, stoks)
        if (hits.isEmpty() && stoks.isNotEmpty() && Scripts.isLatinOnly(qc)) {
            stage = Stage.VOWEL
            hits = vowelMatch(stoks)
        }
        if (hits.isEmpty() && stoks.isNotEmpty()) {
            stage = Stage.FUZZY
            hits = fuzzy(stoks)
        }
        if (hits.isEmpty()) {
            stage = Stage.TRIGRAM
            hits = trigram(ftoks)
        }
        if (hits.isEmpty()) return Answer(emptyList(), Stage.NONE)
        val rows = rows(hits.map { it.id })
        val ranked = rank(hits, rows, ftoks, stoks, bias).take(limit)
        return Answer(ranked.map { it.toFeature(lang) }, stage)
    }

    /** One FTS5 hit: `rowid` and its bm25 (negative; smaller is better). */
    private class Hit(val id: Long, val bm25: Double)

    private fun phrase(t: String, prefix: Boolean = false) = "\"" + t.replace("\"", "\"\"") + "\"" + if (prefix) "*" else ""

    private fun conj(toks: List<String>) = toks.mapIndexed { i, t -> phrase(t, i == toks.lastIndex) }.joinToString(" AND ")

    private fun fts(expr: String): List<Hit> {
        fileQueries++
        val out = ArrayList<Hit>()
        db.prepare(FTS_SQL).use { st ->
            st.bindText(1, expr)
            st.bindLong(2, CANDIDATES.toLong())
            while (st.step()) out += Hit(st.getLong(0), st.getDouble(1))
        }
        return out
    }

    private fun match(ftoks: List<String>, stoks: List<String>): List<Hit> {
        val parts = ArrayList<String>()
        if (ftoks.isNotEmpty()) parts += "{names skel ctx} : (" + conj(ftoks) + ")"
        if (stoks.isNotEmpty()) parts += "skel : (" + conj(stoks) + ")"
        return fts(parts.joinToString(" OR "))
    }

    /** Step 1a: the skeleton conjunction with "u" → "o" in every combination (the base spelling excluded). */
    private fun vowelMatch(stoks: List<String>): List<Hit> {
        var combos = listOf(emptyList<String>())
        for (t in stoks) combos = combos.flatMap { c -> SearchText.latinVowelVariants(t).map { c + it } }.take(MAX_COMBOS)
        val variants = combos.filter { it != stoks }
        if (variants.isEmpty()) return emptyList()
        return fts(variants.joinToString(" OR ") { "skel : (" + conj(it) + ")" })
    }

    private fun fuzzy(stoks: List<String>): List<Hit> {
        val vocab = vocab()
        val groups = ArrayList<String>()
        var changed = false
        stoks.forEachIndexed { i, t ->
            val last = i == stoks.lastIndex
            if (t in vocab.terms || (last && prefixesVocab(vocab.sorted, t))) {
                groups += phrase(t, last)
                return@forEachIndexed
            }
            val k = when {
                t.length in 5..8 -> 1
                t.length >= 9 -> 2
                else -> 0
            }
            val cands = if (k == 0) emptyList() else ((t.length - k)..(t.length + k)).flatMap { vocab.byLength[it].orEmpty() }.mapNotNull { (v, n) ->
                Levenshtein.distance(t, v, k)?.let { d -> Triple(d, -n, v) }
            }.sortedWith(compareBy<Triple<Int, Long, String>> { it.first }.thenBy { it.second }.thenBy { it.third })
                .take(FUZZY_MAX_CANDIDATES).map { it.third }
            if (cands.isEmpty()) {
                groups += phrase(t, last)
                return@forEachIndexed
            }
            changed = true
            groups += "(" + cands.joinToString(" OR ") { phrase(it) } + ")"
        }
        if (!changed) return emptyList()
        return fts("skel : (" + groups.joinToString(" AND ") + ")")
    }

    private fun prefixesVocab(list: List<String>, t: String): Boolean {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo < list.size && list[lo].startsWith(t)
    }

    private fun trigram(ftoks: List<String>): List<Hit> {
        val grams = Trigrams.of(ftoks.joinToString("")).sorted()
        if (grams.isEmpty()) return emptyList()
        fileQueries++
        val hits = ArrayList<Hit>()
        db.prepare(TRIGRAM_SQL).use { st ->
            st.bindText(1, grams.joinToString(" OR ") { phrase(it) })
            st.bindLong(2, CANDIDATES.toLong())
            while (st.step()) hits += Hit(st.getLong(0), st.getDouble(1))
        }
        if (hits.isEmpty()) return emptyList()
        val rows = rows(hits.map { it.id })
        val need = ceil(TRIGRAM_MIN_SHARE * grams.size).toInt()
        val gramSet = grams.toSet()
        return hits.filter { h ->
            val r = rows[h.id] ?: return@filter false
            Trigrams.of(SearchText.fold(r.name ?: "")).count { it in gramSet } >= need
        }
    }

    /** RANKING_VERSION 1 (`search_engine.py` `rank()`), unchanged. */
    private fun rank(hits: List<Hit>, rows: Map<Long, Place>, ftoks: List<String>, stoks: List<String>, bias: LatLon?): List<Place> {
        val qf = ftoks.joinToString(" ")
        val qs = stoks.joinToString(" ")
        val best = hits.minOf { it.bm25 }.let { if (it == 0.0) -1.0 else it }
        val scored = ArrayList<Pair<Double, Place>>(hits.size)
        for (h in hits) {
            val r = rows[h.id] ?: continue
            val nm = r.name ?: listOfNotNull(r.street, r.housenumber).joinToString(" ")
            val nf = SearchText.words(SearchText.fold(nm)).joinToString(" ")
            val ns = SearchText.skeleton(nm)
            val es = r.nameEn?.let { SearchText.skeleton(it) } ?: ""
            val exact = qf == nf || (qs.isNotEmpty() && (qs == ns || qs == es || qs.replace(" ", "") == ns.replace(" ", "")))
            val prefix = (qf.isNotEmpty() && nf.startsWith(qf)) ||
                (qs.isNotEmpty() && (ns.startsWith(qs) || (es.isNotEmpty() && es.startsWith(qs))))
            val rel = if (best < 0) h.bm25 / best else 0.0
            val prox = if (bias == null) 0.0 else 1.0 / (1.0 + Geo.distance(bias, LatLon(r.lat, r.lon)) / 1000.0 / 2.0)
            val score = 3.0 * b(exact) + 1.5 * b(prefix) + 1.0 * rel + 2.0 * r.importance + 4.0 * prox
            scored += score to r
        }
        return scored.sortedWith(compareByDescending<Pair<Double, Place>> { it.first }.thenBy { it.second.id }).map { it.second }
    }

    private fun b(v: Boolean) = if (v) 1.0 else 0.0

    // ------------------------------------------------------------------------------------------------ reverse

    /**
     * Task SM5 / NAV-023 AC 20 (`search_engine.py` `reverse()`): the nearest place with a display name (a name, or
     * street + house number) within [radiusM] (the gateway request's `radius` 0.5 km), through `place_geo` with an
     * expanding box; at each step only a place inside the step's radius counts. null when there is none.
     */
    fun reverse(p: LatLon, lang: Lang, radiusM: Double = REVERSE_RADIUS_M): PhotonFeature? {
        for (step in REVERSE_STEPS_M + radiusM) {
            val r = min(step, radiusM)
            val dLat = r / M_PER_DEG
            val dLon = r / (M_PER_DEG * max(0.01, cos(p.lat * PI / 180)))
            fileQueries++
            var best: Pair<Double, Place>? = null
            db.prepare("$REVERSE_SQL_HEAD${placeCols("p.")}$REVERSE_SQL_TAIL").use { st ->
                st.bindDouble(1, p.lon + dLon)
                st.bindDouble(2, p.lon - dLon)
                st.bindDouble(3, p.lat + dLat)
                st.bindDouble(4, p.lat - dLat)
                while (st.step()) {
                    val row = readPlace(st)
                    val d = Geo.distance(p, LatLon(row.lat, row.lon))
                    val cur = best
                    if (d <= r && (cur == null || d < cur.first || (d == cur.first && row.id < cur.second.id))) best = d to row
                }
            }
            best?.let { return it.second.toFeature(lang) }
            if (r >= radiusM) break
        }
        return null
    }

    // ------------------------------------------------------------------------------------------------ rows

    private fun placeCols(prefix: String = ""): String =
        (BASE_COLS + if (hasLocality) listOf("locality") else emptyList()).joinToString(", ") { prefix + it }

    private fun rows(ids: Collection<Long>): Map<Long, Place> {
        if (ids.isEmpty()) return emptyMap()
        fileQueries++
        val out = HashMap<Long, Place>(ids.size)
        for (chunk in ids.distinct().chunked(CHUNK)) {
            db.prepare("SELECT ${placeCols()} FROM place WHERE id IN (" + chunk.joinToString(",") { "?" } + ")").use { st ->
                chunk.forEachIndexed { i, id -> st.bindLong(i + 1, id) }
                while (st.step()) readPlace(st).let { out[it.id] = it }
            }
        }
        return out
    }

    private fun readPlace(st: SQLiteStatement): Place {
        fun s(i: Int) = if (st.isNull(i)) null else st.getText(i)
        return Place(
            id = st.getLong(0), osmType = s(1), osmId = if (st.isNull(2)) null else st.getLong(2),
            osmKey = s(3).orEmpty(), osmValue = s(4).orEmpty(), type = s(5).orEmpty(), name = s(6), nameEn = s(7),
            housenumber = s(8), street = s(9), postcode = s(10), suburb = s(11), district = s(12), city = s(13),
            county = s(14), state = s(15), countryCode = s(16), lat = st.getDouble(17), lon = st.getDouble(18),
            importance = st.getDouble(19), locality = if (hasLocality) s(20) else null,
        )
    }

    /** One `place` row (schema 1). */
    internal class Place(
        val id: Long, val osmType: String?, val osmId: Long?, val osmKey: String, val osmValue: String, val type: String,
        val name: String?, val nameEn: String?, val housenumber: String?, val street: String?, val postcode: String?,
        val suburb: String?, val district: String?, val city: String?, val county: String?, val state: String?,
        val countryCode: String?, val lat: Double, val lon: Double, val importance: Double, val locality: String?,
    ) {
        /**
         * The gateway's `PhotonFeature` properties (ADR-0012, task SM4), so the list, the type labels and the preview are
         * those of an online answer. As `search_engine.py` `to_feature`: `name` follows the request language (en:
         * `name:en` when present), Photon's `district` is the suburb (else the district); plus Photon's `locality`.
         */
        fun toFeature(lang: Lang): PhotonFeature {
            val props = HashMap<String, String>()
            fun put(k: String, v: String?) {
                if (!v.isNullOrEmpty()) props[k] = v
            }
            put("osm_key", osmKey)
            put("osm_value", osmValue)
            put("type", type)
            put("name", if (lang == Lang.EN && !nameEn.isNullOrEmpty()) nameEn else name)
            put("housenumber", housenumber)
            put("street", street)
            put("postcode", postcode)
            put("district", suburb ?: district)
            put("locality", locality)
            put("city", city)
            put("county", county)
            put("state", state)
            put("countrycode", countryCode?.uppercase(Locale.ROOT))
            put("osm_type", osmType)
            osmId?.let { props["osm_id"] = it.toString() }
            return PhotonFeature(LatLon(lat, lon), props)
        }
    }

    companion object {
        /** `search_engine.py` RANKING_VERSION this port follows. */
        const val RANKING_VERSION = 1
        const val CANDIDATES = 300
        const val FUZZY_MAX_CANDIDATES = 8
        const val TRIGRAM_MIN_SHARE = 0.5
        private const val MAX_COMBOS = 16
        private const val CHUNK = 500

        /** The gateway reverse request's `radius` (0.5 km, NAV-011 ReverseClient). */
        const val REVERSE_RADIUS_M = 500.0
        private val REVERSE_STEPS_M = listOf(25.0, 50.0, 100.0, 200.0, 350.0)
        private const val M_PER_DEG = 111_320.0

        private val BASE_COLS = listOf(
            "id", "osm_type", "osm_id", "osm_key", "osm_value", "type", "name", "name_en", "housenumber", "street", "postcode",
            "suburb", "district", "city", "county", "state", "country_code", "lat", "lon", "importance",
        )
        private const val FTS_SQL =
            "SELECT rowid, bm25(place_fts, 10.0, 5.0, 1.0) AS s FROM place_fts WHERE place_fts MATCH ? ORDER BY s LIMIT ?"
        private const val TRIGRAM_SQL = "SELECT rowid, bm25(place_tri) AS s FROM place_tri WHERE place_tri MATCH ? ORDER BY s LIMIT ?"
        private const val REVERSE_SQL_HEAD = "SELECT "
        private const val REVERSE_SQL_TAIL = " FROM place_geo g JOIN place p ON p.id = g.id " +
            "WHERE g.min_lon <= ? AND g.max_lon >= ? AND g.min_lat <= ? AND g.max_lat >= ? " +
            "AND (p.name IS NOT NULL OR (p.housenumber IS NOT NULL AND p.street IS NOT NULL))"
    }
}

/** Banded Levenshtein: the distance when ≤ k, else null (`search_engine.py` `levenshtein_within`). */
internal object Levenshtein {
    fun distance(a: String, b: String, k: Int): Int? {
        if (kotlin.math.abs(a.length - b.length) > k) return null
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1) { k + 1 }
            cur[0] = i
            val lo = max(1, i - k)
            val hi = min(b.length, i + k)
            for (j in lo..hi) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
            }
            if (cur.min() > k) return null
            prev = cur
        }
        return prev[b.length].takeIf { it <= k }
    }
}

/** 3-grams of a string with its spaces removed (`search_engine.py` `trigrams`). */
internal object Trigrams {
    fun of(s: String): Set<String> {
        val t = s.replace(" ", "")
        if (t.length < 3) return emptySet()
        return (0..t.length - 3).mapTo(LinkedHashSet()) { t.substring(it, it + 3) }
    }
}

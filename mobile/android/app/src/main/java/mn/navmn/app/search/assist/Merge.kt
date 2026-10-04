package mn.navmn.app.search.assist

import mn.navmn.app.search.PhotonFeature
import mn.navmn.app.search.SearchOutcome
import java.util.Locale

/** ADR-0006 §2.5 merge of the responses of one settled query (port of web/src/search/merge.ts). Pure. */
object Merge {
    /** The results list shows at most 10 options. */
    const val MAX_OPTIONS = 10

    /** Identity for de-duplication: osm_type + osm_id; features without them fall back to name + point. */
    fun key(f: PhotonFeature): String {
        val type = f["osm_type"]
        val id = f["osm_id"]
        if (type != null && type in OSM_TYPES && id != null) return "$type$id"
        return "?${f["name"] ?: ""}@${f.point.lon},${f.point.lat}"
    }

    private val OSM_TYPES = setOf("N", "W", "R")

    /** Stable partition: every `countrycode == "MN"` (any case) before every other result. */
    fun mnFirst(features: List<PhotonFeature>): List<PhotonFeature> {
        val (mn, other) = features.partition { it["countrycode"]?.uppercase(Locale.ROOT) == "MN" }
        return mn + other
    }

    private fun dedupe(features: List<PhotonFeature>): List<PhotonFeature> {
        val seen = HashSet<String>()
        return features.filter { seen.add(key(it)) }
    }

    private fun interleave(a: List<PhotonFeature>, b: List<PhotonFeature>): List<PhotonFeature> {
        val out = ArrayList<PhotonFeature>(a.size + b.size)
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let { out += it }
            b.getOrNull(i)?.let { out += it }
        }
        return out
    }

    /** Final options. [secondary] is null for a single response (also the `ifEmpty` second response, passed as [primary]). */
    fun merge(primary: List<PhotonFeature>, secondary: List<PhotonFeature>?): List<PhotonFeature> {
        val all = if (secondary == null) primary else interleave(primary, secondary)
        return mnFirst(dedupe(all)).take(MAX_OPTIONS)
    }
}

/**
 * ADR-0006 §3 pair combination for `parallel` plans (port of the web's combineParallel; NAV-011 AC 7): a 429 on either
 * request → rate-limited (the longer Retry-After); otherwise any `Ok` → the merged `Ok` results (one failed request does
 * not hide the other's results); otherwise the primary's failure class.
 */
object CombineOutcomes {
    fun parallel(a: SearchOutcome, b: SearchOutcome): SearchOutcome {
        if (a is SearchOutcome.RateLimited || b is SearchOutcome.RateLimited) {
            val s = maxOf((a as? SearchOutcome.RateLimited)?.retryAfterS ?: 0, (b as? SearchOutcome.RateLimited)?.retryAfterS ?: 0)
            return SearchOutcome.RateLimited(s)
        }
        if (a is SearchOutcome.Ok || b is SearchOutcome.Ok) {
            return SearchOutcome.Ok(Merge.merge((a as? SearchOutcome.Ok)?.features.orEmpty(), (b as? SearchOutcome.Ok)?.features.orEmpty()))
        }
        return a
    }

    /** A single response (`none`, or the response an `ifEmpty` plan ends with): merged on its own. */
    fun single(a: SearchOutcome): SearchOutcome = if (a is SearchOutcome.Ok) SearchOutcome.Ok(Merge.merge(a.features, null)) else a
}

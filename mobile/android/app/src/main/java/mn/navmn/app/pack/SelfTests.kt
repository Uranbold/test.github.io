package mn.navmn.app.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.log.DebugLog
import mn.navmn.app.route.RouteBody
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.TravelMode
import mn.navmn.app.routing.EngineAnswer
import mn.navmn.app.routing.GraphBuilderAllowList
import mn.navmn.app.search.offline.SearchText
import java.io.File

/** NAV-022 AC 16 self-tests, run on the staged (verified, decompressed) files before anything is activated. */
interface SelfTests {
    suspend fun tiles(f: PackFile, file: File): Boolean
    suspend fun routing(f: PackFile, file: File, manifest: PackManifest): Boolean
    suspend fun search(f: PackFile, file: File, manifest: PackManifest): Boolean
}

/** Runs one `self_test.route` on a routing tar in the NAV-021 `:routing` process (a bad file cannot crash this one). */
fun interface RoutingSelfTest {
    suspend fun route(version: String, tar: File, requestJson: String): EngineAnswer
}

/** `PRAGMA quick_check` and the `self_test.search` query on the search DB (needs FTS5: `sqlite-bundled`, ADR-0017 §3). */
fun interface SearchSelfTest {
    /** Returns null when the check passed, else a short reason (no query text, no coordinates). */
    suspend fun check(file: File, searchSchema: Int, query: String?): String?
}

class DefaultSelfTests(
    private val routingTest: RoutingSelfTest,
    private val searchTest: SearchSelfTest,
    private val log: DebugLog = DebugLog.NONE,
    private val allowList: (String?) -> Boolean = GraphBuilderAllowList::allows,
) : SelfTests {
    /** AC 16 `tiles`: PMTiles v3 magic; header minzoom 0, maxzoom 14; bounds contain P1–P6. */
    override suspend fun tiles(f: PackFile, file: File): Boolean {
        val h = PmtilesHeader.read(file) ?: return fail("tiles: not a PMTiles file")
        if (h.version != 3) return fail("tiles: PMTiles version ${h.version}")
        if (h.minZoom != 0 || h.maxZoom != 14) return fail("tiles: zoom range ${h.minZoom}-${h.maxZoom}")
        if (!TEST_POINTS.all { h.contains(it.lat, it.lon) }) return fail("tiles: bounds miss a test point")
        return true
    }

    /** AC 16 `routing`: allow-listed `graph_builder`, and `self_test.route` answers OSRM `code` `Ok` in `:routing`. */
    override suspend fun routing(f: PackFile, file: File, manifest: PackManifest): Boolean {
        if (!allowList(f.graphBuilder)) return fail("routing: graph_builder not on the allow-list")
        val st = manifest.selfTest?.route
        if (st == null) {
            log.d("routing self-test: manifest has no self_test.route (allow-list only)")
            return true
        }
        val body = selfTestBody(st) ?: return fail("routing: unknown self-test costing")
        return when (val a = routingTest.route(f.version, file, body)) {
            is EngineAnswer.Osrm -> osrmOk(a.bytes) || fail("routing: self-test route not Ok")
            is EngineAnswer.Failed -> fail("routing: self-test ${a.error}")
        }
    }

    override suspend fun search(f: PackFile, file: File, manifest: PackManifest): Boolean {
        val schema = f.searchSchema ?: return fail("search: no search_schema")
        val reason = searchTest.check(file, schema, manifest.selfTest?.search?.q)
        return reason == null || fail("search: $reason")
    }

    private fun fail(reason: String): Boolean {
        log.d("pack self-test failed: $reason")
        return false
    }

    companion object {
        /** NAV-001 P1–P6 (Ulaanbaatar test points; the golden-set coordinates, not user data). */
        val TEST_POINTS: List<LatLon> = listOf(
            LatLon(47.9189, 106.9176), LatLon(47.9139, 106.9044), LatLon(47.8858, 106.9173),
            LatLon(47.9215, 106.8950), LatLon(47.9095, 106.8835), LatLon(47.9600, 106.9000),
        )

        /** The exact ADR-0009 §2 body (a reroute-shaped request: no alternates, no heading) for `self_test.route`. */
        fun selfTestBody(st: SelfTestRoute): String? {
            val mode = TravelMode.entries.firstOrNull { it.costing == st.costing } ?: return null
            return RouteBody.json(
                RouteRequest(LatLon(st.from.lat, st.from.lon), LatLon(st.to.lat, st.to.lon), mode, avoidUnpaved = false, lang = Lang.MN),
            )
        }

        fun osrmOk(bytes: ByteArray): Boolean = runCatching {
            ((Json.parseToJsonElement(bytes.decodeToString()) as JsonObject)["code"] as JsonPrimitive).contentOrNull == "Ok"
        }.getOrDefault(false)
    }
}

/**
 * The server self-test query of the NAV-020 task file §3.6 (ADR-0017 A3 item 3: since NAV-023 the app runs the same
 * expression as the server): the NAV-023 AC 2 skeleton of `self_test.search.q`, split into words, every word quoted,
 * `*` on the last, on the `skel` column.
 */
object SearchSelfTestQuery {
    /** null when the query has no word (the check then needs ≥ 1 `place` row only). */
    fun match(q: String): String? {
        val tokens = SearchText.skeleton(q).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val quoted = tokens.mapIndexed { i, t -> "\"" + t + "\"" + if (i == tokens.lastIndex) "*" else "" }
        return "skel : (" + quoted.joinToString(" ") + ")"
    }

    const val COUNT_SQL = "SELECT count(*) FROM place_fts WHERE place_fts MATCH ?"
}

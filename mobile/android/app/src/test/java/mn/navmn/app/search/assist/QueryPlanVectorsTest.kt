package mn.navmn.app.search.assist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.geo.LatLon
import mn.navmn.app.search.PhotonFeature
import mn.navmn.app.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAV-011 AC 2–5 (ADR-0012 §1–§2): the Kotlin port against the shared ADR-0006 vector fixture
 * `web/src/search/queryPlan.vectors.json` (copied by Gradle; the web suite reads the same file). 100 % of the rows must
 * pass; a missing or empty fixture fails.
 */
class QueryPlanVectorsTest {
    private val root: JsonObject = Json.parseToJsonElement(Fixtures.shared("queryPlan.vectors.json")).jsonObject

    private fun section(name: String): List<JsonObject> = root.getValue(name).jsonArray.map { it.jsonObject }
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    @Test
    fun fixtureIsVersion1WithRowsInEverySection() {
        assertEquals(1, root.getValue("version").jsonPrimitive.int)
        for (s in listOf("settle", "plan", "latinToCyrillic", "merge")) assertTrue("$s is empty", section(s).isNotEmpty())
    }

    @Test
    fun settleRows() {
        val bad = section("settle").filter { Settle.settle(it.str("raw")!!) != it.str("settled") }
            .map { "«${it.str("raw")}» → «${Settle.settle(it.str("raw")!!)}», expected «${it.str("settled")}»" }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun planRows() {
        val bad = ArrayList<String>()
        for (r in section("plan")) {
            val input = r.str("input")!!
            val p = QueryPlanner.plan(input)
            val ok = when (r.str("kind")) {
                "skip" -> p == QueryPlan.Skip
                "coordinate" -> p == QueryPlan.Coordinate(LatLon(r.getValue("lat").jsonPrimitive.double, r.getValue("lon").jsonPrimitive.double))
                "text" -> p is QueryPlan.Text && p.primary == r.str("primary") && p.secondary == r.str("secondary") && p.mode.id == r.str("mode")
                else -> false
            }
            if (!ok) bad += "«${input.take(40)}» → $p, expected ${r.str("kind")} «${r.str("primary")}» «${r.str("secondary")}» ${r.str("mode")}"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun latinToCyrillicRows() {
        val bad = section("latinToCyrillic").filter { LatinToCyrillic.convert(it.str("latin")!!) != it.str("cyrillic") }
            .map { "«${it.str("latin")}» → «${LatinToCyrillic.convert(it.str("latin")!!)}», expected «${it.str("cyrillic")}»" }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    private fun feature(o: JsonObject): PhotonFeature {
        val props = HashMap<String, String>()
        o.str("osm_type")?.let { props["osm_type"] = it }
        o.str("osm_id")?.let { props["osm_id"] = it }
        o.str("countrycode")?.let { props["countrycode"] = it }
        return PhotonFeature(LatLon(47.9, 106.9), props)
    }

    @Test
    fun mergeRows() {
        val bad = ArrayList<String>()
        for (r in section("merge")) {
            val primary = r.getValue("primary").jsonArray.map { feature(it.jsonObject) }
            val secondary = (r["secondary"] as? JsonArray)?.map { feature(it.jsonObject) }
            val out = when (r.str("mode")) {
                "parallel" -> Merge.merge(primary, secondary)
                "ifEmpty" -> Merge.merge(if (primary.isEmpty()) secondary.orEmpty() else primary, null)
                else -> Merge.merge(primary, null)
            }.map { Merge.key(it) }
            val expected = r.getValue("expected").jsonArray.map { it.jsonPrimitive.content }
            if (out != expected) bad += "${r.str("name")}: $out, expected $expected"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /** AC 4 / D33 F4: the 196-character «СХД» row is cut to exactly 200; no planned q is ever longer than 200. */
    @Test
    fun noPlannedQueryIsLongerThan200() {
        val typed = "а".repeat(192) + " СХД" // scan:data
        assertEquals(196, typed.length)
        val p = QueryPlanner.plan(typed) as QueryPlan.Text
        assertEquals(200, p.primary.length)
        assertEquals(typed, p.secondary)
        for (r in section("plan")) {
            val plan = QueryPlanner.plan(r.str("input")!!)
            if (plan is QueryPlan.Text) for (q in plan.queries) assertTrue("${q.length} > 200", q.length <= Settle.MAX_LENGTH)
        }
        assertEquals("x".repeat(199), Settle.cap("x".repeat(199) + "😀"))
    }
}

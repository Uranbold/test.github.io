package mn.navmn.app.search.offline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.search.PlaceDisplay
import mn.navmn.app.search.SearchController
import mn.navmn.app.search.SearchOutcome
import mn.navmn.app.search.SearchView
import mn.navmn.app.support.Fixtures
import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * NAV-023 task SM10 / AC 17 (engine side): the NAV-003 golden set, rows A1–A14, A16, B1–B15 (30 applicable rows; A15
 * never reaches the file), run through the **app's** search path, the [SearchController] with the ADR-0012 plan and
 * merge, answering every planned query with the Kotlin engine on a real `search.sqlite`. Type-label conditions are
 * evaluated with the shipped resources. Opt-in, because the file is not in the repository:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*OfflineGoldenHarnessTest*' -Pnav.searchDb=/path/to/search.sqlite
 *
 * Without online comparison every row counts (QA's gate run removes rows that also fail online on the same slot). The
 * result per row is written to `app/build/nav023-golden.json` for comparison with `backend/pack/search_engine.py`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineGoldenHarnessTest {
    private val dbPath = System.getProperty("nav.searchDb").orEmpty()

    private class Row(val id: String, val q: String, val lang: Lang, val bias: LatLon, val expect: JsonObject)

    private fun rows(): List<Row> {
        val g = Json.parseToJsonElement(Fixtures.repoFile("tests/e2e/nav003/fixtures/golden-set.json").readText()).jsonObject
        val refs = g.getValue("refs").jsonObject.mapValues { (_, v) -> v.jsonArray.let { LatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) } }
        return g.getValue("rows").jsonArray.map { it.jsonObject }
            .filter { it.str("tier") in setOf("A", "B") && it.str("id") != "A15" }
            .map { Row(it.str("id")!!, it.str("q")!!, if (it.str("ui") == "en") Lang.EN else Lang.MN, refs.getValue(it.str("bias")!!), it.getValue("expect").jsonObject) }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    @Test
    fun goldenRowsOnTheDevice() = runTest {
        assumeTrue("set -Pnav.searchDb=<search.sqlite> to run the harness", dbPath.isNotEmpty())
        val db = BundledSearchDbOpener.open(File(dbPath))
        val engine = OfflineSearchEngine(db)
        val rows = rows()
        assertTrue("expected 30 applicable rows, got ${rows.size}", rows.size == 30)
        var bias = rows.first().bias
        var lang = Lang.MN
        var rowNanos = 0L
        val c = SearchController(
            this,
            { q, l, b ->
                val t0 = System.nanoTime()
                val f = engine.search(q, l, b)
                rowNanos += System.nanoTime() - t0
                SearchOutcome.Ok(f, onDevice = true)
            },
            { lang }, { bias }, { true }, { testScheduler.currentTime },
        )
        var passed = 0
        val report = StringBuilder()
        val millis = ArrayList<Double>()
        // Warm-up (class loading, page cache, the lazy vocabulary), as a phone after the first query.
        engine.search("Гандан", Lang.MN, bias)
        engine.search("Энхтайвны", Lang.MN, bias)
        val out = buildJsonArray {
            for (r in rows) {
                bias = r.bias
                lang = r.lang
                c.close()
                rowNanos = 0L
                c.onQuery(r.q)
                advanceUntilIdle()
                millis += rowNanos / 1e6
                val items = when (val v = c.view.value) {
                    is SearchView.Results -> v.items
                    else -> emptyList()
                }
                val ok = passes(r, items)
                if (ok) passed++
                report.append(if (ok) "PASS " else "FAIL ").append(r.id).append(" «").append(r.q).append("» ")
                    .append("%.1f ms ".format(millis.last()))
                    .append(items.take(5).joinToString(" | ") { "${it.name}·${TestStrings.of(r.lang)[it.type]}·${Geo.distance(it.point, r.bias).toInt()}m" })
                    .append('\n')
                add(
                    buildJsonObject {
                        put("id", r.id)
                        put("pass", ok)
                        put("top", buildJsonArray { items.take(10).forEach { add(JsonPrimitive("${it.name}@${"%.5f".format(it.point.lat)},${"%.5f".format(it.point.lon)}")) } })
                    },
                )
            }
        }
        db.close()
        File(System.getProperty("user.dir"), "build/nav023-golden.json").writeText(out.toString())
        println(report)
        println("NAV-023 golden (engine): passed $passed of ${rows.size}; gate passed × 10 ≥ counted × 9: ${passed * 10 >= rows.size * 9}")
        val sorted = millis.sorted()
        // Host JVM time of the planned queries of a row (not AC 9, which is measured on the benchmark phones).
        println("host engine time per row: p50 %.1f ms, p95 %.1f ms, max %.1f ms".format(sorted[sorted.size / 2], sorted[(sorted.size * 95 + 99) / 100 - 1], sorted.last()))
        assertTrue("gate failed: $passed / ${rows.size}\n$report", passed * 10 >= rows.size * 9)
    }

    /** One option in the top N satisfies every condition of the row (NAV-003 golden semantics). */
    private fun passes(r: Row, items: List<PlaceDisplay.Info>): Boolean {
        val e = r.expect
        if ((e["noResults"] as? JsonPrimitive)?.contentOrNull == "true") return items.isEmpty()
        val top = (e["top"] as? JsonPrimitive)?.int ?: 5
        val refs = Json.parseToJsonElement(Fixtures.repoFile("tests/e2e/nav003/fixtures/golden-set.json").readText()).jsonObject.getValue("refs").jsonObject
        val near = e.str("near")?.let { refs.getValue(it).jsonArray.let { a -> LatLon(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double) } }
        val m = (e["m"] as? JsonPrimitive)?.double
        val ignoreCase = e.str("flags")?.contains('i') == true
        val opts = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val nameRe = e.str("nameContains")?.let { Regex(it, opts) }
        val nameCtxRe = e.str("nameOrContextContains")?.let { Regex(it, opts) }
        val type = e.str("type")
        return items.take(top).any { o ->
            (near == null || Geo.distance(o.point, near) <= m!!) &&
                (nameRe == null || nameRe.containsMatchIn(o.name.orEmpty())) &&
                (nameCtxRe == null || nameCtxRe.containsMatchIn(o.name.orEmpty()) || nameCtxRe.containsMatchIn(o.context.orEmpty())) &&
                (type == null || TestStrings.of(r.lang)[o.type] == type)
        }
    }
}

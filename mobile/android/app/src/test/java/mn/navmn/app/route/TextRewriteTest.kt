package mn.navmn.app.route

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0009 §3.1 rewrite; NAV-005 AC 26–27 (no Valhalla text reaches the navigation model). */
class TextRewriteTest {
    /** Replaces every Valhalla text field with the sentinel (the AC 27 recorded-route variant). */
    private fun sentinel(el: JsonElement, key: String? = null): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.mapValues { (k, v) -> sentinel(v, k) })
        is JsonArray -> JsonArray(el.map { sentinel(it, key) })
        is JsonPrimitive -> if (el.isString && key in setOf("instruction", "text", "announcement", "ssmlAnnouncement")) JsonPrimitive("VALHALLA_TEXT_SENTINEL") else el
    }

    @Test
    fun sentinelNeverReachesTheParser() {
        for (name in listOf("p1-p3-car-mn.json", "g8-roundabout-car-mn.json", "p1-p2-walk-en.json", "g2-reroute-car-mn.json")) {
            val raw = Json.parseToJsonElement(Fixtures.route(name).decodeToString())
            val withSentinel = sentinel(raw).toString()
            assertTrue(withSentinel.contains("VALHALLA_TEXT_SENTINEL"))
            val parser = FakeRouteParser()
            val outcome = RouteProcessor(parser).process(withSentinel.encodeToByteArray(), 3)
            assertTrue(outcome is RouteOutcome.Ok)
            val given = parser.parsed.single()
            assertFalse("$name: sentinel leaked into the parser input", given.contains("VALHALLA_TEXT_SENTINEL"))
            assertTrue(given.contains("nav:3:m:0"))
            assertTrue(given.contains("nav:3:b:0:0"))
        }
    }

    @Test
    fun voiceInstructionsAreEmptiedAndTokensAreGenerationBound() {
        val root = OsrmPlanParser.parseJson(Fixtures.route("p1-p3-car-mn.json"))!!
        val out = TextRewrite.rewrite(root, 7)
        val steps = out["routes"]!!.jsonArray[0].jsonObject["legs"]!!.jsonArray[0].jsonObject["steps"]!!.jsonArray
        steps.forEachIndexed { i, s ->
            val step = s.jsonObject
            assertEquals(0, step["voiceInstructions"]!!.jsonArray.size)
            assertEquals("nav:7:m:$i", (step["maneuver"]!!.jsonObject["instruction"] as JsonPrimitive).content)
            step["bannerInstructions"]?.jsonArray?.forEachIndexed { b, banner ->
                val primary = banner.jsonObject["primary"]!!.jsonObject
                assertEquals("nav:7:b:$i:$b", (primary["text"] as JsonPrimitive).content)
                primary["components"]?.jsonArray?.forEach { c ->
                    (c.jsonObject["text"] as? JsonPrimitive)?.let { assertEquals("nav:7:b:$i:$b", it.content) }
                }
            }
        }
        // Street names stay (they are data, not narrative).
        assertTrue(out.toString().contains("Дүнжингаравын гудамж"))
    }

    @Test
    fun planReadsStepsAndSnapDistances() {
        val plan = OsrmPlanParser.plan(OsrmPlanParser.parseJson(Fixtures.route("p1-p3-car-mn.json"))!!, 0)!!
        assertEquals(listOf("depart", "turn", "turn", "arrive"), plan.steps.map { it.maneuver.type })
        assertEquals("turn.left", plan.steps[1].key.key.id)
        assertEquals("Дүнжингаравын гудамж", plan.steps[1].street)
        assertEquals(2, plan.snapDistances.size)
        assertTrue(plan.geometry.size > 10)
        assertEquals(4271.63, plan.distance, 0.01)
    }

    @Test
    fun stepCountMismatchIsBadResponse() {
        val parser = RouteParser { object : NativeRoute { override val stepCount = 2 } }
        assertEquals(RouteOutcome.BadResponse, RouteProcessor(parser).process(Fixtures.route("p1-p3-car-mn.json"), 0))
    }
}

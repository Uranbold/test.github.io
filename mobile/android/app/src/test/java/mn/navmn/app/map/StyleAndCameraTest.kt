package mn.navmn.app.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0009 §6 label-rule test on both committed native style files (AC 1, 65; ADR-0004 §3 for the web) and the
 * navigation-ux §8 camera rules.
 */
class StyleAndCameraTest {
    private val labelRule = Json.parseToJsonElement("""["coalesce",["get","name:mn"],["get","name"],["get","name:en"]]""")
    private fun style(theme: String) = Fixtures.repoFile("mobile/android/app/src/main/assets/style/basemap-$theme.json").readText()

    private fun keys(e: JsonElement, out: MutableList<String> = ArrayList()): List<String> {
        when (e) {
            is JsonArray -> {
                val op = (e.firstOrNull() as? JsonPrimitive)?.content
                if ((op == "get" || op == "has" || op == "!has") && e.size > 1 && (e[1] as? JsonPrimitive)?.isString == true) out += e[1].jsonPrimitive.content
                e.forEach { keys(it, out) }
            }
            is JsonObject -> e.values.forEach { keys(it, out) }
            else -> Unit
        }
        return out
    }

    private fun fonts(e: JsonElement, out: MutableSet<String> = HashSet()): Set<String> {
        if (e is JsonArray) {
            val ops = setOf("case", "match", "step", "interpolate", "literal", "coalesce", "get", "has", "concat", "format")
            if (e.isNotEmpty() && e.all { it is JsonPrimitive && it.isString } && e[0].jsonPrimitive.content !in ops) out += e.map { it.jsonPrimitive.content }
            else e.forEach { fonts(it, out) }
        }
        return out
    }

    private fun isNameKey(k: String) = k == "name" || k.startsWith("name:") || k == "name2" || k == "name3" || k.startsWith("pgf:")

    @Test
    fun labelRuleFontsAndTilesPlaceholderInBothFlavors() {
        for (theme in listOf("day", "night")) {
            val text = style(theme)
            val s = Json.parseToJsonElement(text).jsonObject
            assertEquals("tiles placeholder once ($theme)", 1, Regex(Regex.escape(MapStyle.TILES_PLACEHOLDER)).findAll(text).count())
            assertFalse(text.contains("name:ru"))
            assertFalse(text.contains("pgf:"))
            assertTrue(s["glyphs"]!!.jsonPrimitive.content.startsWith("asset://fonts/"))
            assertTrue(s["sprite"]!!.jsonPrimitive.content.startsWith("asset://sprites/"))
            assertFalse("no remote URL", Regex("https?://").containsMatchIn(text))
            var nameLayers = 0
            for (l in s["layers"]!!.jsonArray.map { it.jsonObject }) {
                assertFalse("web overlay layer ${l["id"]} must not be in the native style", l["id"]!!.jsonPrimitive.content.startsWith("nav-"))
                if (l["type"]?.jsonPrimitive?.content != "symbol") continue
                val layout = l["layout"]?.jsonObject ?: continue
                val field = layout["text-field"] ?: continue
                val k = keys(field)
                if (k.any(::isNameKey)) {
                    nameLayers++
                    assertEquals("label rule on ${l["id"]} ($theme)", labelRule, field)
                }
                layout["text-font"]?.let { f ->
                    val used = fonts(f)
                    assertTrue("${l["id"]} fonts $used", setOf("Noto Sans Regular", "Noto Sans Medium", "Noto Sans Italic").containsAll(used))
                }
            }
            assertTrue(nameLayers > 10)
        }
    }

    @Test
    fun resolvedStyleUsesTheGatewayPmtilesUrl() {
        val url = "pmtiles://http://127.0.0.1:8080/tiles/basemap.pmtiles"
        val s = Json.parseToJsonElement(MapStyle.resolve(style("day"), url)).jsonObject
        assertEquals(url, s["sources"]!!.jsonObject["protomaps"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun zoomBySpeedWithHysteresis() {
        assertEquals(17.5, CameraRules.zoom(10.0, false, null), 0.0)
        assertEquals(17.0, CameraRules.zoom(30.0, false, null), 0.0)
        assertEquals(16.0, CameraRules.zoom(60.0, false, null), 0.0)
        assertEquals(15.0, CameraRules.zoom(90.0, false, null), 0.0)
        assertEquals(17.5, CameraRules.zoom(90.0, true, null), 0.0)
        // 21 km/h from the 17.5 band stays (dead band), 23 km/h moves
        assertEquals(17.5, CameraRules.zoom(21.0, false, 17.5), 0.0)
        assertEquals(17.0, CameraRules.zoom(23.0, false, 17.5), 0.0)
        assertEquals(17.0, CameraRules.zoom(18.5, false, 17.0), 0.0)
        assertEquals(17.5, CameraRules.zoom(17.0, false, 17.0), 0.0)
    }

    @Test
    fun puckAtSeventyPercentOfTheUncoveredMap() {
        val h = 1000.0
        val p = CameraRules.padding(h, coveredTop = 300.0, coveredBottom = 200.0, coveredLeft = 0.0, coveredRight = 0.0, headingUp = true)
        val center = (p.top + (h - p.bottom)) / 2
        assertEquals(300 + 0.7 * (800 - 300), center, 0.001)
        val n = CameraRules.padding(h, 300.0, 200.0, 0.0, 0.0, headingUp = false)
        assertEquals((300 + 800) / 2.0, (n.top + (h - n.bottom)) / 2, 0.001)
    }
}

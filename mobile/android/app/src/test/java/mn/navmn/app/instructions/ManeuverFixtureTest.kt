package mn.navmn.app.instructions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.i18n.Lang
import mn.navmn.app.support.Fixtures
import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-005 AC 29 / ADR-0009 §7: 100 % of web/src/route/maneuvers.fixture.json (copied by Gradle, not moved). */
class ManeuverFixtureTest {
    private val cases = Json.parseToJsonElement(Fixtures.shared("maneuvers.fixture.json")).jsonObject["cases"]!!.jsonArray.map { it.jsonObject }

    private fun input(m: JsonObject) = ManeuverInput(
        type = m["type"]!!.jsonPrimitive.content,
        modifier = m["modifier"]?.jsonPrimitive?.contentOrNull,
        exit = m["exit"]?.jsonPrimitive?.doubleOrNull,
        bearingAfter = m["bearing_after"]?.jsonPrimitive?.doubleOrNull,
    )

    @Test
    fun fixtureIsPresentAndNotEmpty() {
        assertTrue("fixture has no cases", cases.size >= 50)
    }

    @Test
    fun everyCaseGivesTheExpectedKeyParamsAndTextInBothLanguages() {
        val failures = ArrayList<String>()
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val r = ManeuverRules.key(input(c["maneuver"]!!.jsonObject))
            val expectedKey = c["key"]!!.jsonPrimitive.content
            val expectedN = c["params"]?.jsonObject?.get("n")?.jsonPrimitive?.intOrNull
            if (r.key.id != expectedKey) failures += "$name: key ${r.key.id} != $expectedKey"
            if (r.n != expectedN) failures += "$name: n ${r.n} != $expectedN"
            val mn = BannerText.text(r, Lang.MN, TestStrings.of(Lang.MN))
            val en = BannerText.text(r, Lang.EN, TestStrings.of(Lang.EN))
            if (mn != c["mn"]!!.jsonPrimitive.content) failures += "$name: mn «$mn» != «${c["mn"]!!.jsonPrimitive.content}»"
            if (en != c["en"]!!.jsonPrimitive.content) failures += "$name: en \"$en\" != \"${c["en"]!!.jsonPrimitive.content}\""
        }
        assertEquals("fixture mismatches:\n" + failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun departSectorBoundaries() {
        assertEquals(ManeuverKey.DEPART_N, ManeuverRules.departSector(22.4))
        assertEquals(ManeuverKey.DEPART_NE, ManeuverRules.departSector(22.5))
        assertEquals(ManeuverKey.DEPART_N, ManeuverRules.departSector(337.5))
        assertEquals(ManeuverKey.DEPART_N, ManeuverRules.departSector(360.0))
        assertEquals(ManeuverKey.DEPART_NW, ManeuverRules.departSector(-22.6))
    }

    @Test
    fun streetNamesDropZeroWidthAndTraditionalScript() {
        assertEquals("Энхтайваны өргөн чөлөө", StreetName.clean("Энхтайваны\u200B өргөн\uFEFF чөлөө\u200D"))
        assertEquals("", StreetName.clean("\u200B \u200C"))
        assertEquals("", StreetName.clean(null))
        assertEquals("Сүхбаатарын талбай", StreetName.clean("Сүхбаатарын талбай ᠰᠦᠬᠡᠪᠠᠭᠠᠲᠤᠷ"))
    }
}

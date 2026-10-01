package mn.navmn.app.route

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-005 AC 5 and AC 43: exact field sets (ADR-0009 §2). */
class RouteBodyTest {
    private val p1 = LatLon(47.9189, 106.9176)
    private val p3 = LatLon(47.8858, 106.9173)

    private fun parse(req: RouteRequest) = Json.parseToJsonElement(RouteBody.json(req)).jsonObject

    @Test
    fun carMongolianWithoutAvoid() {
        val body = parse(RouteRequest(p1, p3, TravelMode.CAR, avoidUnpaved = false, lang = Lang.MN))
        assertEquals(
            setOf("locations", "costing", "alternates", "format", "banner_instructions", "voice_instructions", "units", "language"),
            body.keys,
        )
        assertEquals("auto", body["costing"]!!.jsonPrimitive.content)
        assertEquals("0", body["alternates"]!!.jsonPrimitive.content)
        assertEquals("osrm", body["format"]!!.jsonPrimitive.content)
        assertEquals("true", body["banner_instructions"]!!.jsonPrimitive.content)
        assertEquals("true", body["voice_instructions"]!!.jsonPrimitive.content)
        assertEquals("kilometers", body["units"]!!.jsonPrimitive.content)
        assertEquals("mn-MN", body["language"]!!.jsonPrimitive.content)
        val locs = body["locations"]!!.jsonArray
        assertEquals(2, locs.size)
        assertEquals(setOf("lat", "lon"), locs[0].jsonObject.keys)
        assertEquals(setOf("lat", "lon"), locs[1].jsonObject.keys)
    }

    @Test
    fun coordinatesHaveAtLeastFiveDecimals() {
        val text = RouteBody.json(RouteRequest(p1, p3, TravelMode.CAR, false, Lang.MN))
        assertTrue(text, text.contains("\"lat\":47.918900") && text.contains("\"lon\":106.917600"))
        assertTrue(text.contains("\"lat\":47.885800"))
    }

    @Test
    fun avoidOnlyForCar() {
        val car = parse(RouteRequest(p1, p3, TravelMode.CAR, avoidUnpaved = true, lang = Lang.EN))
        assertEquals("""{"auto":{"exclude_unpaved":true}}""", car["costing_options"].toString())
        assertEquals("en-US", car["language"]!!.jsonPrimitive.content)
        val walk = parse(RouteRequest(p1, p3, TravelMode.WALK, avoidUnpaved = true, lang = Lang.EN))
        assertFalse(walk.containsKey("costing_options"))
        assertEquals("pedestrian", walk["costing"]!!.jsonPrimitive.content)
    }

    @Test
    fun rerouteHeadingOnlyOnOrigin() {
        val body = parse(RouteRequest(p1, p3, TravelMode.CAR, false, Lang.MN, heading = 165))
        val locs = body["locations"]!!.jsonArray
        assertEquals(setOf("lat", "lon", "heading"), locs[0].jsonObject.keys)
        assertEquals("165", locs[0].jsonObject["heading"]!!.jsonPrimitive.content)
        assertEquals(setOf("lat", "lon"), locs[1].jsonObject.keys)
        for (forbidden in listOf("filters", "street_side_tolerance", "type", "heading_tolerance", "radius", "id")) {
            assertFalse(RouteBody.json(RouteRequest(p1, p3, TravelMode.CAR, true, Lang.MN, 10)).contains("\"$forbidden\""))
        }
    }

    private fun fix(bearing: Double?, speed: Double?, bearingAcc: Double? = null) =
        Fix(47.9, 106.9, 5.0, bearing, bearingAcc, speed, 0, 0)

    @Test
    fun headingRule() {
        assertEquals(165, RouteBody.headingFor(fix(164.6, 2.0)))
        assertEquals(0, RouteBody.headingFor(fix(359.6, 10.0)))
        assertNull(RouteBody.headingFor(fix(164.6, 1.99)))
        assertNull(RouteBody.headingFor(fix(null, 10.0)))
        assertNull(RouteBody.headingFor(fix(164.6, null)))
        assertNull(RouteBody.headingFor(fix(164.6, 10.0, bearingAcc = 46.0)))
        assertEquals(10, RouteBody.headingFor(fix(10.2, 10.0, bearingAcc = 45.0)))
    }
}

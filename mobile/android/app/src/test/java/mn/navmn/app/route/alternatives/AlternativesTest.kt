package mn.navmn.app.route.alternatives

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import mn.navmn.app.preview.Destination
import mn.navmn.app.preview.PreviewController
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.route.RouteBody
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RoutePurpose
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A synthetic 3-route preview response built from recorded single-route responses (the dev stack was down, so no live
 * 3-route response could be recorded; QA records one with the `androidPreviewCarAlternates` body when `/health` is 200):
 * route 1 = P1 → P3 car, route 2 = the G2 reroute route, route 3 = P1 → P2 walk. `waypoints` and `code` are route 1's.
 */
object ThreeRoutes {
    fun bytes(): ByteArray {
        fun root(name: String) = Json.parseToJsonElement(Fixtures.route(name).decodeToString()).jsonObject
        val base = root("p1-p3-car-mn.json")
        val routes = JsonArray(
            listOf("p1-p3-car-mn.json", "g2-reroute-car-mn.json", "p1-p2-walk-mn.json").map { root(it).getValue("routes").jsonArray[0] },
        )
        return JsonObject(base + ("routes" to routes)).toString().encodeToByteArray()
    }
}

/** NAV-011 AC 14–15, 17, 19, 21–24 (ADR-0012 §5–§6): preview request, k routes, selection, «Дугуй». */
@OptIn(ExperimentalCoroutinesApi::class)
class AlternativesTest {
    private val p1 = LatLon(47.9189, 106.9176)
    private val p3 = LatLon(47.8858, 106.9173)
    private val fix = Fix(p1.lat, p1.lon, 5.0, null, null, null, 0, 0)
    private val processor = RouteProcessor(FakeRouteParser())

    @Test
    fun previewBodyAsksForTwoAlternativesRerouteForNone() {
        val preview = RouteBody.json(RouteRequest(p1, p3, TravelMode.CAR, true, Lang.MN, purpose = RoutePurpose.PREVIEW))
        assertTrue(preview, preview.contains("\"alternates\":2,"))
        assertTrue(preview.contains("\"costing_options\":{\"auto\":{\"exclude_unpaved\":true}}"))
        val reroute = RouteBody.json(RouteRequest(p1, p3, TravelMode.BICYCLE, false, Lang.MN, heading = 90))
        assertTrue(reroute, reroute.contains("\"alternates\":0,"))
        assertTrue(reroute.contains("\"costing\":\"bicycle\""))
        assertFalse("no costing options for bicycle", reroute.contains("costing_options"))
        // Bicycle and walk never carry costing options, even with the (car-only) toggle on.
        for (m in listOf(TravelMode.WALK, TravelMode.BICYCLE)) {
            assertFalse(RouteBody.json(RouteRequest(p1, p3, m, true, Lang.EN, purpose = RoutePurpose.PREVIEW)).contains("costing_options"))
        }
        assertEquals(
            "{\"locations\":[{\"lat\":47.918900,\"lon\":106.917600},{\"lat\":47.885800,\"lon\":106.917300}],\"costing\":\"bicycle\",\"alternates\":2," +
                "\"format\":\"osrm\",\"banner_instructions\":true,\"voice_instructions\":true,\"units\":\"kilometers\",\"language\":\"en-US\"}",
            RouteBody.json(RouteRequest(p1, p3, TravelMode.BICYCLE, false, Lang.EN, purpose = RoutePurpose.PREVIEW)),
        )
    }

    @Test
    fun threeRoutesParseFromSlicesSingleRouteResponsesUnchanged() {
        val out = PreviewRoutes.process(processor, ThreeRoutes.bytes(), 0) as RouteOutcome.Ok
        assertEquals(3, out.routes.size)
        assertEquals(out.route, out.routes[0])
        val single = (processor.process(Fixtures.route("g2-reroute-car-mn.json"), 0) as RouteOutcome.Ok).route
        assertEquals(single.plan.steps.map { it.maneuver }, out.routes[1].plan.steps.map { it.maneuver })
        assertEquals(single.plan.geometry, out.routes[1].plan.geometry)
        // Each slice has exactly one route and the other top-level fields of the response.
        val root = Json.parseToJsonElement(ThreeRoutes.bytes().decodeToString()).jsonObject
        val slice = Json.parseToJsonElement(PreviewRoutes.slice(root, 2).decodeToString()).jsonObject
        assertEquals(1, slice.getValue("routes").jsonArray.size)
        assertEquals(root.getValue("waypoints"), slice.getValue("waypoints"))
        assertEquals(root.getValue("code"), slice.getValue("code"))
        // A one-route preview response is the NAV-005 path.
        val one = PreviewRoutes.process(processor, Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok
        assertEquals(1, one.routes.size)
    }

    @Test
    fun aBrokenAlternativeIsLeftOutABrokenRoute1IsBadResponse() {
        val root = Json.parseToJsonElement(ThreeRoutes.bytes().decodeToString()).jsonObject
        val routes = root.getValue("routes").jsonArray
        val broken = JsonObject(routes[1].jsonObject + ("legs" to JsonArray(emptyList())))
        val altBroken = JsonObject(root + ("routes" to JsonArray(listOf(routes[0], broken, routes[2])))).toString().encodeToByteArray()
        val out = PreviewRoutes.process(processor, altBroken, 0) as RouteOutcome.Ok
        assertEquals(2, out.routes.size)
        val firstBroken = JsonObject(root + ("routes" to JsonArray(listOf(broken, routes[1])))).toString().encodeToByteArray()
        assertEquals(RouteOutcome.BadResponse, PreviewRoutes.process(processor, firstBroken, 0))
    }

    private fun TestScope.ctl(answer: suspend (RouteRequest) -> RouteOutcome): Pair<PreviewController, MutableList<RouteRequest>> {
        val sent = ArrayList<RouteRequest>()
        val c = PreviewController(this, { r -> sent += r; answer(r) }, { Lang.MN }, { true }, { testScheduler.currentTime }, { 0L })
        return c to sent
    }

    private val three = PreviewRoutes.process(processor, ThreeRoutes.bytes(), 0)

    @Test
    fun selectionChangesTheSummaryWithNoRequestAndANewResponseSelectsRoute1() = runTest {
        val (c, sent) = ctl { three }
        c.open(Destination(p3, "Зайсан"))
        c.setOrigin(fix)
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals(RoutePurpose.PREVIEW, sent[0].purpose)
        val r = c.state.value!!.result as PreviewResult.Route
        assertEquals(3, r.k)
        assertEquals(0, r.selected)
        c.select(1)
        val r2 = c.state.value!!.result as PreviewResult.Route
        assertEquals(1, r2.selected)
        assertEquals(r2.routes[1], r2.route)
        assertEquals("AC 17: 0 requests", 1, sent.size)
        c.select(7)
        c.select(1)
        assertEquals(1, (c.state.value!!.result as PreviewResult.Route).selected)
        // AC 21: a mode / toggle change renders route 1 of the new response.
        c.setAvoidUnpaved(true)
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals(0, (c.state.value!!.result as PreviewResult.Route).selected)
    }

    @Test
    fun bicycleTabSettlesThreeHundredMsModeKeptForTheSessionTooFarIsN11() = runTest {
        val (c, sent) = ctl { r -> if (r.mode == TravelMode.CAR) three else RouteOutcome.TooFar }
        c.open(Destination(p3, null))
        c.setOrigin(fix)
        runCurrent()
        assertEquals(1, sent.size)
        c.setMode(TravelMode.WALK)
        advanceTimeBy(100)
        c.setMode(TravelMode.BICYCLE)
        advanceTimeBy(299)
        assertEquals(1, sent.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("two tab changes within 300 ms → 1 request for the final tab", 2, sent.size)
        assertEquals(TravelMode.BICYCLE, sent[1].mode)
        assertFalse(sent[1].avoidUnpaved)
        assertEquals(PreviewResult.TooFar, c.state.value!!.result)
        // AC 22: the mode is kept for the session (memory only): the next preview opens on «Дугуй».
        c.close()
        c.open(Destination(p1, null))
        assertEquals(TravelMode.BICYCLE, c.state.value!!.mode)
        // AC 24: on «Машин» the same code is «Маршрут олдсонгүй».
        val (car, _) = ctl { RouteOutcome.TooFar }
        car.open(Destination(p3, null))
        car.setOrigin(fix)
        runCurrent()
        assertEquals(PreviewResult.NoRoute(false), car.state.value!!.result)
    }

    @Test
    fun anOlderResponseNeverReplacesANewerOne() = runTest {
        val (c, sent) = ctl { r ->
            if (r.mode == TravelMode.CAR) {
                kotlinx.coroutines.delay(1_000)
                three
            } else {
                RouteOutcome.TooFar
            }
        }
        c.open(Destination(p3, null))
        c.setOrigin(fix)
        runCurrent()
        c.setMode(TravelMode.WALK)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals(PreviewResult.TooFar, c.state.value!!.result)
    }

    @Test
    fun hitTestPrefersTheUnselectedRouteThenTheNearest() {
        val a = listOf(LatLon(47.90, 106.90), LatLon(47.90, 106.91))
        val b = listOf(LatLon(47.9001, 106.90), LatLon(47.9001, 106.91))
        val c = listOf(LatLon(47.9003, 106.90), LatLon(47.9003, 106.91))
        val tap = LatLon(47.90005, 106.905)
        assertEquals("overlap: the unselected line wins", 1, AlternativeHitTest.pick(tap, listOf(0, 1), 0, listOf(a, b, c)))
        assertEquals("two unselected: the nearer one", 1, AlternativeHitTest.pick(Geo.offset(tap, 0.0, 3.0), listOf(1, 2), 0, listOf(a, b, c)))
        assertEquals("only the selected line → no change", null, AlternativeHitTest.pick(tap, listOf(0), 0, listOf(a, b, c)))
        assertEquals(null, AlternativeHitTest.pick(tap, emptyList(), 0, listOf(a, b, c)))
    }
}

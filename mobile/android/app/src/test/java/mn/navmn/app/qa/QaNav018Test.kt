package mn.navmn.app.qa

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import mn.navmn.app.preview.Destination
import mn.navmn.app.preview.PreviewController
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.preview.turnlist.TurnListModel
import mn.navmn.app.preview.turnlist.TurnRowText
import mn.navmn.app.route.RouteBody
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RoutePurpose
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.TravelMode
import mn.navmn.app.route.alternatives.PreviewRoutes
import mn.navmn.app.route.alternatives.ThreeRoutes
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA, NAV-018 (light-QA, test plan `docs/qa/test-plans/NAV-018.md` §4). Independent checks on the JVM that the mobile
 * engineer's suites do not make:
 * - TC-R01 AC 13: the body of a chosen start (unrounded, 6 decimals, preview profile, no heading);
 * - TC-R02 AC 11/12: swap and point changes during a 429 wait send 0 requests, the retry sends the latest points;
 * - TC-R03 AC 17: the NAV-005 / NAV-011 failure states with a chosen start (N9, N11, offline + one request on return);
 * - TC-T01 AC 21/26: selecting another route switches the list to that route's steps with 0 requests;
 * - TC-T02 AC 18/19 + ADR-0015 §8 risk: row i is step i's **own** manoeuvre (an independent text oracle, so a
 *   banner-style "next step" shift fails);
 * - TC-T03 AC 23: arrive row description has no distance and no trailing separator; empty street names are omitted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QaNav018Test {
    private val zaisan = LatLon(47.8858, 106.9173)
    /** A chosen start with 8 decimals, so rounding to fewer than 6 is visible in the body. */
    private val pickup = LatLon(47.92131234, 106.89481276)
    private val fix = Fix(P1.lat, P1.lon, 5.0, 90.0, 5.0, 10.0, 0, 0)
    private val ok = RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0)
    private val three = PreviewRoutes.process(RouteProcessor(FakeRouteParser()), ThreeRoutes.bytes(), 0) as RouteOutcome.Ok
    private val mn = TestStrings.of(Lang.MN)
    private val en = TestStrings.of(Lang.EN)

    private fun TestScope.ctl(online: () -> Boolean = { true }, answer: suspend (RouteRequest) -> RouteOutcome): Pair<PreviewController, MutableList<RouteRequest>> {
        val sent = ArrayList<RouteRequest>()
        val c = PreviewController(this, { r -> sent += r; answer(r) }, { Lang.MN }, online, { testScheduler.currentTime }, { 0L })
        return c to sent
    }

    /** TC-R01, AC 13: one POST body with both points, ≥ 5 (here 6) decimals, not rounded further, alternates 2, no heading. */
    @Test
    fun tcR01_chosenStartBodyIsUnroundedPreviewProfileWithoutHeading() = runTest {
        val (c, sent) = ctl { ok }
        c.open(Destination(zaisan, "Зайсан толгой"))
        c.setOrigin(RoutePoint.Place(pickup, "Гандантэгчинлэн хийд"))
        runCurrent()
        assertEquals(1, sent.size)
        val req = sent.single()
        assertEquals("AC 13: the start is sent as chosen (not rounded in the model)", pickup, req.origin)
        assertEquals(zaisan, req.destination)
        assertEquals(RoutePurpose.PREVIEW, req.purpose)
        assertNull("ADR-0015 §7: preview requests never carry a heading", req.heading)
        val body = RouteBody.json(req)
        val locs = Json.parseToJsonElement(body).jsonObject["locations"]!!.jsonArray
        assertEquals(2, locs.size)
        assertEquals("47.921312", locs[0].jsonObject["lat"].toString())
        assertEquals("106.894813", locs[0].jsonObject["lon"].toString())
        assertEquals("47.885800", locs[1].jsonObject["lat"].toString())
        for (l in locs) assertEquals("only lat/lon per location: $l", setOf("lat", "lon"), l.jsonObject.keys)
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals(2, root["alternates"]!!.jsonPrimitive.intOrNull)
        assertEquals("auto", root["costing"]!!.jsonPrimitive.content)
        assertFalse("no costing_options without the avoid toggle", root.containsKey("costing_options"))
    }

    /** TC-R02, AC 11 + 12: during Retry-After the points and swap update with 0 requests; afterwards the latest points go out. */
    @Test
    fun tcR02_swapAndPointChangesDuringA429WaitSendNothingThenTheLatestPoints() = runTest {
        var n = 0
        val (c, sent) = ctl { if (n++ == 0) RouteOutcome.RateLimited(5) else ok }
        c.open(Destination(zaisan, "Зайсан толгой"))
        c.setOrigin(fix)
        runCurrent()
        assertEquals(PreviewResult.RateLimited(false), c.state.value!!.result)
        assertEquals(1, sent.size)
        assertTrue(c.swap())
        c.setOrigin(RoutePoint.MapPoint(pickup)) // start replaced by a map point while waiting
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals("AC 12: 0 requests during the Retry-After wait", 1, sent.size)
        assertEquals(RoutePoint.MapPoint(pickup), c.state.value!!.origin)
        assertEquals("the swapped device point stays the destination", RoutePoint.MyLocation(fix), c.state.value!!.destination)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(PreviewResult.RateLimited(true), c.state.value!!.result)
        assertEquals("NAV-005 AC 7: no automatic request after the wait", 1, sent.size)
        c.retry()
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals("the retry uses the latest points", pickup, sent[1].origin)
        assertEquals(P1, sent[1].destination)
        assertTrue(c.state.value!!.result is PreviewResult.Route)
    }

    /** TC-R03, AC 17: with a chosen start every failure state applies unchanged. */
    @Test
    fun tcR03_failureStatesWithAChosenStart() = runTest {
        var online = true
        var answer: RouteOutcome = RouteOutcome.OutOfCoverage
        val (c, sent) = ctl({ online }) { answer }
        c.open(Destination(zaisan, "Зайсан толгой"))
        c.setOrigin(RoutePoint.MapPoint(X1)) // X1 outside the service area as the start (story Edge cases)
        runCurrent()
        assertEquals("N9 for a start outside the area", PreviewResult.OutOfArea, c.state.value!!.result)
        answer = RouteOutcome.TooFar
        c.setMode(TravelMode.WALK)
        advanceTimeBy(400)
        runCurrent()
        assertEquals("N11 on «Явган»", PreviewResult.TooFar, c.state.value!!.result)
        answer = RouteOutcome.NoRoute
        c.setMode(TravelMode.CAR)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(PreviewResult.NoRoute(false), c.state.value!!.result)
        assertEquals(3, sent.size)
        online = false
        c.setOrigin(RoutePoint.MapPoint(pickup))
        runCurrent()
        assertEquals(PreviewResult.Offline, c.state.value!!.result)
        assertEquals("offline: 0 requests", 3, sent.size)
        answer = ok
        online = true
        c.onNetworkRestored()
        runCurrent()
        assertEquals("one request by itself when the network returns", 4, sent.size)
        assertEquals(pickup, sent[3].origin)
        assertTrue(c.state.value!!.result is PreviewResult.Route)
        assertFalse("AC 15: chosen start", c.state.value!!.canStart)
        answer = RouteOutcome.Unavailable
        c.setAvoidUnpaved(true)
        runCurrent()
        assertEquals(PreviewResult.Unavailable, c.state.value!!.result)
    }

    /** TC-T01, AC 21 + 26: another route → the list shows that route's steps, 0 requests; a new response → route 1. */
    @Test
    fun tcT01_selectingAnotherRouteSwitchesTheListWithNoRequest() = runTest {
        val (c, sent) = ctl { three }
        c.open(Destination(zaisan, "Зайсан толгой"))
        c.setOrigin(RoutePoint.Place(pickup, "Гандантэгчинлэн хийд"))
        runCurrent()
        val r0 = c.state.value!!.result as PreviewResult.Route
        assertEquals(3, r0.k)
        val rows0 = TurnListModel.build(r0.route.plan)
        c.select(2)
        val r2 = c.state.value!!.result as PreviewResult.Route
        val rows2 = TurnListModel.build(r2.route.plan)
        assertEquals("AC 21: 0 requests on selection", 1, sent.size)
        assertEquals(three.routes[2].plan.steps.size, rows2.size)
        assertNotEquals("the list changed with the selection", rows0.map { it.location }, rows2.map { it.location })
        // A new response (point change) selects route 1 again and the list follows it.
        c.setDestination(RoutePoint.MapPoint(P3))
        runCurrent()
        val again = c.state.value!!.result as PreviewResult.Route
        assertEquals(0, again.selected)
        assertEquals(2, sent.size)
        assertEquals(rows0.size, TurnListModel.build(again.route.plan).size)
    }

    /** Independent AC 27 oracle for the manoeuvre of a raw step (subset of the table: what the recorded routes contain). */
    private fun expectedMn(type: String, modifier: String?, exit: Int?): String? {
        val turn = mapOf(
            "left" to "Зүүн тийш эргэнэ үү", "right" to "Баруун тийш эргэнэ үү",
            "slight left" to "Бага зэрэг зүүн тийш эргэнэ үү", "slight right" to "Бага зэрэг баруун тийш эргэнэ үү",
            "sharp left" to "Огцом зүүн тийш эргэнэ үү", "sharp right" to "Огцом баруун тийш эргэнэ үү",
            "uturn" to "Буцаж эргэнэ үү", "straight" to "Чигээрээ явна уу",
        )
        return when (type) {
            "turn", "end of road", "continue", "new name" -> turn[modifier ?: "straight"]
            "fork" -> when {
                modifier == null || modifier == "straight" -> "Чигээрээ явна уу"
                modifier.endsWith("left") -> "Зүүн талаа барина уу"
                else -> "Баруун талаа барина уу"
            }
            "rotary", "roundabout" -> exit?.let { "Тойрог: $it-р гарц" }
            "exit rotary", "exit roundabout" -> "Тойргоос гарна уу"
            "arrive" -> when {
                modifier == null || modifier == "straight" -> "Та очих газартаа ирлээ"
                modifier.endsWith("left") -> "Таны очих газар зүүн талд байна"
                else -> "Таны очих газар баруун талд байна"
            }
            else -> null
        }
    }

    /** TC-T02, AC 18/19 + ADR-0015 §8: row i shows step i's own manoeuvre (no banner shift), in leg order, depart … arrive. */
    @Test
    fun tcT02_rowIShowsStepIsOwnManoeuvre() {
        var checked = 0
        val problems = ArrayList<String>()
        for (name in listOf("p1-p3-car-mn.json", "p1-p2-walk-mn.json", "g8-roundabout-car-mn.json")) {
            val raw = Json.parseToJsonElement(Fixtures.route(name).decodeToString()).jsonObject["routes"]!!.jsonArray[0]
                .jsonObject["legs"]!!.jsonArray[0].jsonObject["steps"]!!.jsonArray.map { it.jsonObject }
            val plan = (RouteProcessor(FakeRouteParser()).process(Fixtures.route(name), 0) as RouteOutcome.Ok).route.plan
            val rows = TurnListModel.build(plan)
            assertEquals(raw.size, rows.size)
            rows.forEachIndexed { i, row ->
                val m = raw[i]["maneuver"]!!.jsonObject
                val type = m["type"]!!.jsonPrimitive.content
                val text = TurnRowText.instruction(row, Lang.MN, mn)
                if (type == "depart") {
                    if (!text.endsWith("зүг рүү явна уу")) problems += "$name row $i depart: «$text»"
                    checked++
                    return@forEachIndexed
                }
                val want = expectedMn(type, m["modifier"]?.jsonPrimitive?.contentOrNull, m["exit"]?.jsonPrimitive?.intOrNull) ?: return@forEachIndexed
                checked++
                if (text != want) problems += "$name row $i ($type ${m["modifier"]}): «$text», expected «$want»"
                // Street line: step.name as returned (zero-width removed), D11.
                val rawName = raw[i]["name"]?.jsonPrimitive?.contentOrNull.orEmpty().replace(Regex("[​‌‍﻿]"), "")
                if (row.street != rawName.trim() && row.street != rawName) problems += "$name row $i street «${row.street}» vs «$rawName»"
            }
        }
        assertTrue("oracle covered the recorded steps ($checked)", checked >= 25)
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    /** TC-T03, AC 23 + AC 24: description joins with ", ", arrive has no distance, empty street omitted; en has no Cyrillic text part. */
    @Test
    fun tcT03_rowDescriptionsInBothLanguages() {
        val plan = (RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p2-walk-mn.json"), 0) as RouteOutcome.Ok).route.plan
        for (row in TurnListModel.build(plan)) {
            for ((lang, s) in listOf(Lang.MN to mn, Lang.EN to en)) {
                val d = TurnRowText.contentDescription(row, lang, s)
                assertFalse("no empty part: «$d»", d.contains(", ,") || d.endsWith(", ") || d.startsWith(", "))
                val parts = d.split(", ")
                assertEquals(TurnRowText.instruction(row, lang, s), parts.first())
                if (row.distanceM == null) assertFalse("arrive row has no distance: «$d»", Regex("\\d+(,\\d)?\\s?(м|км|m|km)$").containsMatchIn(d))
                else assertEquals(TurnRowText.distance(row, lang, s), parts.last())
                if (row.street.isEmpty()) assertEquals(if (row.distanceM == null) 1 else 2, parts.size)
                if (lang == Lang.EN) assertFalse(Regex("[Ѐ-ӿ]").containsMatchIn(parts.first()))
            }
        }
    }
}

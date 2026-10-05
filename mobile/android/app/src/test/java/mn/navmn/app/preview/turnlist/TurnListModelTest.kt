package mn.navmn.app.preview.turnlist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.instructions.ManeuverInput
import mn.navmn.app.qa.Ac27
import mn.navmn.app.route.GuidancePlan
import mn.navmn.app.route.PlanStep
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-018 AC 18–20, 23, 25 (ADR-0015 §8): the turn-list model and its texts, on the JVM. */
class TurnListModelTest {
    private val mn = TestStrings.of(Lang.MN)
    private val en = TestStrings.of(Lang.EN)

    private fun plan(name: String): GuidancePlan = (RouteProcessor(FakeRouteParser()).process(Fixtures.route(name), 0) as RouteOutcome.Ok).route.plan

    private fun rawSteps(name: String) = Json.parseToJsonElement(Fixtures.route(name).decodeToString()).jsonObject["routes"]!!.jsonArray[0]
        .jsonObject["legs"]!!.jsonArray[0].jsonObject["steps"]!!.jsonArray.map { it.jsonObject }

    private val recorded = listOf("p1-p3-car-mn.json", "p1-p2-walk-mn.json", "g8-roundabout-car-mn.json", "p1-p3-car-en.json", "p1-p2-walk-en.json")

    @Test
    fun oneRowPerStepOfLeg0InOrderWithItsOwnManeuverAndLocation() {
        for (name in recorded) {
            val rows = TurnListModel.build(plan(name))
            val raw = rawSteps(name)
            assertEquals("$name: one row per legs[0].steps", raw.size, rows.size)
            assertTrue("$name: first row depart", rows.first().key.key.isDepart)
            assertTrue("$name: last row arrive", rows.last().key.key.isArrive)
            rows.forEachIndexed { i, row ->
                val m = raw[i]["maneuver"]!!.jsonObject
                val loc = m["location"]!!.jsonArray
                // ADR-0015 §8: row i = step i's own manoeuvre at its maneuver.location (no banner shift).
                assertEquals("$name row $i lon", loc[0].jsonPrimitive.double, row.location.lon, 1e-9)
                assertEquals("$name row $i lat", loc[1].jsonPrimitive.double, row.location.lat, 1e-9)
                assertEquals("$name row $i type", m["type"]!!.jsonPrimitive.content == "arrive", row.key.key.isArrive)
                if (row.key.key.isArrive) assertNull("AC 18: no distance on arrive", row.distanceM)
                else assertEquals(raw[i]["distance"]!!.jsonPrimitive.double, row.distanceM!!, 1e-9)
                assertTrue("$name row $i: no zero-width in the street", row.street.none { it in "​‌‍﻿" })
            }
        }
    }

    @Test
    fun everySharedFixtureCaseRendersTheExpectedRowTextInBothLanguages() {
        val cases = Json.parseToJsonElement(Fixtures.shared("maneuvers.fixture.json")).jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 50)
        val failures = ArrayList<String>()
        for (c in cases) {
            val m = c["maneuver"]!!.jsonObject
            val input = ManeuverInput(
                m["type"]!!.jsonPrimitive.content,
                m["modifier"]?.jsonPrimitive?.contentOrNull,
                m["exit"]?.jsonPrimitive?.doubleOrNull,
                m["bearing_after"]?.jsonPrimitive?.doubleOrNull,
            )
            val step = PlanStep.of(input, LatLon(47.9, 106.9), "Энхтайвны өргөн чөлөө", 120.0, 10.0)
            val row = TurnListModel.build(GuidancePlan(0, listOf(step), 120.0, 10.0, listOf(LatLon(47.9, 106.9), LatLon(47.91, 106.9)), listOf(0.0, 0.0))).single()
            val textMn = TurnRowText.instruction(row, Lang.MN, mn)
            val textEn = TurnRowText.instruction(row, Lang.EN, en)
            if (textMn != c["mn"]!!.jsonPrimitive.content) failures += "${c["name"]}: mn «$textMn»"
            if (textEn != c["en"]!!.jsonPrimitive.content) failures += "${c["name"]}: en \"$textEn\""
        }
        assertEquals("AC 19: 100 % of the fixture\n" + failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun recordedMongolianInstructionTextsPassTheAc20Scans() {
        val problems = ArrayList<String>()
        for (name in listOf("p1-p3-car-mn.json", "p1-p2-walk-mn.json", "g8-roundabout-car-mn.json")) {
            for (row in TurnListModel.build(plan(name))) {
                val text = TurnRowText.instruction(row, Lang.MN, mn)
                Ac27.ac28Problems(text).forEach { problems += "$name row ${row.index} «$text»: $it" }
            }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test
    fun englishInstructionTextsHaveNoCyrillicOutsideTheStreetLine() {
        val problems = ArrayList<String>()
        for (name in listOf("p1-p3-car-en.json", "p1-p2-walk-en.json")) {
            for (row in TurnListModel.build(plan(name))) {
                val text = TurnRowText.instruction(row, Lang.EN, en)
                if (Regex("[Ѐ-ӿ]").containsMatchIn(text)) problems += "$name row ${row.index}: \"$text\""
                if (text !in Ac27.en) problems += "$name row ${row.index}: \"$text\" is not an AC 27 text"
            }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test
    fun rowDescriptionJoinsInstructionStreetAndDistance() {
        val rows = TurnListModel.build(plan("p1-p3-car-mn.json"))
        val first = rows.first()
        val d = TurnRowText.contentDescription(first, Lang.MN, mn)
        val parts = listOfNotNull(TurnRowText.instruction(first, Lang.MN, mn), first.street.ifEmpty { null }, TurnRowText.distance(first, Lang.MN, mn))
        assertEquals(parts.joinToString(", "), d)
        assertNotNull(TurnRowText.distance(first, Lang.MN, mn))
        val last = rows.last()
        assertNull("AC 23: no distance on arrive", TurnRowText.distance(last, Lang.MN, mn))
        assertTrue(TurnRowText.contentDescription(last, Lang.MN, mn).startsWith(TurnRowText.instruction(last, Lang.MN, mn)))
        // AC 24: a language switch is a re-render of the same row (0 requests, street unchanged).
        val dEn = TurnRowText.contentDescription(first, Lang.EN, en)
        if (first.street.isNotEmpty()) assertTrue(dEn.contains(first.street))
    }

    @Test
    fun a500StepModelBuildsWithin200ms() {
        val inputs = listOf(ManeuverInput("turn", "left"), ManeuverInput("turn", "right"), ManeuverInput("continue"), ManeuverInput("roundabout", "right", 2.0))
        val steps = ArrayList<PlanStep>(500)
        steps += PlanStep.of(ManeuverInput("depart", bearingAfter = 10.0), LatLon(47.9, 106.9), "Эхний гудамж", 100.0, 10.0)
        for (i in 1 until 499) steps += PlanStep.of(inputs[i % inputs.size], LatLon(47.9 + i * 1e-4, 106.9), if (i % 3 == 0) "" else "Гудамж $i", 100.0, 10.0)
        steps += PlanStep.of(ManeuverInput("arrive"), LatLon(47.95, 106.9), "", 0.0, 0.0)
        val plan = GuidancePlan(0, steps, 50_000.0, 5_000.0, listOf(LatLon(47.9, 106.9), LatLon(47.95, 106.9)), listOf(0.0, 0.0))
        TurnListModel.build(plan) // warm-up (class loading)
        val t0 = System.nanoTime()
        val rows = TurnListModel.build(plan)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        assertEquals(500, rows.size)
        assertTrue("AC 25: built in $ms ms", ms <= 200.0)
    }

    @Test
    fun stepCameraUsesZoom17OrHigherAnd40dpPlusInsets() {
        assertEquals(17.0, StepCamera.zoom(12.0), 0.0)
        assertEquals(18.5, StepCamera.zoom(18.5), 0.0)
        assertEquals(StepCamera.Padding(140, 190, 40, 340), StepCamera.padding(40, 100, 150, 300))
    }

    @Test
    fun stepsOfLaterLegsAreNotListed() {
        val a = ManeuverInput("arrive")
        val p = GuidancePlan(
            0,
            listOf(
                PlanStep.of(ManeuverInput("depart", bearingAfter = 0.0), LatLon(47.9, 106.9), "", 10.0, 1.0),
                PlanStep.of(a, LatLon(47.91, 106.9), "", 0.0, 0.0),
                PlanStep.of(ManeuverInput("depart", bearingAfter = 0.0), LatLon(47.91, 106.9), "", 10.0, 1.0),
                PlanStep.of(a, LatLon(47.92, 106.9), "", 0.0, 0.0),
            ),
            20.0, 2.0, listOf(LatLon(47.9, 106.9), LatLon(47.92, 106.9)), listOf(0.0, 0.0),
        )
        assertEquals(2, TurnListModel.build(p).size)
    }
}

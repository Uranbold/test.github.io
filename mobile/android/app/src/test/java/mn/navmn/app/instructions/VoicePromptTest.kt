package mn.navmn.app.instructions

import mn.navmn.app.format.Formatters
import mn.navmn.app.i18n.Lang
import mn.navmn.app.support.TestStrings
import mn.navmn.app.support.TestVoice
import mn.navmn.app.voice.pack.C3Table
import mn.navmn.app.voice.pack.Segment
import mn.navmn.app.voice.pack.Take
import mn.navmn.app.voice.pack.VoiceInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAV-016 M2 (AC 22–25): the D95 coordinated change in VoiceText. Mongolian km rounding (D92, D94, D102), the spoken
 * form from the inventory (C3a, D93, «жи пи эс» D231) and the segment list (ADR-0010 decision 2). English unchanged (D96).
 */
class VoicePromptTest {
    private val mn = TestStrings.of(Lang.MN)
    private val en = TestStrings.of(Lang.EN)
    private val inv = TestVoice.inventory
    private fun m(c: VoiceContent) = VoiceText.prompt(c, Lang.MN, mn, inv)
    private fun e(c: VoiceContent) = VoiceText.prompt(c, Lang.EN, en, inv)
    private fun k(type: String, modifier: String? = null, exit: Double? = null, bearing: Double? = null) =
        ManeuverRules.key(ManeuverInput(type, modifier, exit, bearing))
    private fun man(key: KeyResult, d: Double, then: KeyResult? = null) = VoiceContent.Maneuver(key, d, then)

    @Test
    fun ac23Table() {
        val rows: List<Triple<VoiceContent, Pair<String, String>, String>> = listOf(
            Triple(man(k("turn", "right"), 300.0), "300 метрт баруун тийш эргэнэ үү" to "гурван зуун метрт баруун тийш эргэнэ үү", "dist.m.300:final instr.turn.right:final"),
            Triple(man(k("turn", "right"), 980.0), "1 километрт баруун тийш эргэнэ үү" to "нэг километрт баруун тийш эргэнэ үү", "dist.km.1:final instr.turn.right:final"),
            Triple(man(k("fork", "left"), 1_249.0), "1 километрт зүүн талаа барина уу" to "нэг километрт зүүн талаа барина уу", "dist.km.1:final instr.keep.left:final"),
            Triple(man(k("fork", "left"), 1_250.0), "1,5 километрт зүүн талаа барина уу" to "нэг хагас километрт зүүн талаа барина уу", "dist.km.1_5:final instr.keep.left:final"),
            Triple(man(k("turn", "left"), 1_750.0), "2 километрт зүүн тийш эргэнэ үү" to "хоёр километрт зүүн тийш эргэнэ үү", "dist.km.2:final instr.turn.left:final"),
            Triple(man(k("turn", "left"), 20.0), "Зүүн тийш эргэнэ үү" to "зүүн тийш эргэнэ үү", "instr.turn.left:final"),
            Triple(
                man(k("roundabout", "right", 2.0), 200.0, k("turn", "slight right")),
                "200 метрт тойрогт ороод хоёрдугаар гарцаар гарна уу, дараа нь бага зэрэг баруун тийш эргэнэ үү" to
                    "хоёр зуун метрт тойрогт ороод хоёрдугаар гарцаар гарна уу, дараа нь бага зэрэг баруун тийш эргэнэ үү",
                "dist.m.200:final instr.roundabout.exit.2:nonFinal join.then:final instr.turn.slightRight:final",
            ),
            Triple(
                VoiceContent.Depart(k("depart", bearing = 0.0), k("turn", "right")),
                "Хойд зүг рүү явна уу, дараа нь баруун тийш эргэнэ үү" to "хойд зүг рүү явна уу, дараа нь баруун тийш эргэнэ үү",
                "instr.depart.n:nonFinal join.then:final instr.turn.right:final",
            ),
            Triple(VoiceContent.Approaching(200.0), "200 метрт очих газартаа хүрнэ" to "хоёр зуун метрт очих газартаа хүрнэ", "dist.m.200:final tail.approach:final"),
            Triple(VoiceContent.ContinueOn(2_480.0), "2 километр үргэлжлүүлэн явна уу" to "хоёр километр үргэлжлүүлэн явна уу", "cont.km.2:final tail.continue:final"),
            Triple(VoiceContent.ContinueOn(99_499.0), "99 километр үргэлжлүүлэн явна уу" to "ерэн есөн километр үргэлжлүүлэн явна уу", "cont.km.99:final tail.continue:final"),
            Triple(VoiceContent.ContinueOn(99_500.0), "100 километр үргэлжлүүлэн явна уу" to "зуун километр үргэлжлүүлэн явна уу", "cont.km.100:final tail.continue:final"),
            Triple(VoiceContent.ContinueOn(123_000.0), "120 километр үргэлжлүүлэн явна уу" to "зуун хорин километр үргэлжлүүлэн явна уу", "cont.km.120:final tail.continue:final"),
            Triple(VoiceContent.ContinueOn(994_999.0), "990 километр үргэлжлүүлэн явна уу" to "есөн зуун ерэн километр үргэлжлүүлэн явна уу", "cont.km.990:final tail.continue:final"),
            Triple(VoiceContent.Arrival(k("arrive")), "Та очих газартаа ирлээ" to "та очих газартаа ирлээ", "instr.arrive:final"),
            Triple(VoiceContent.OffRoute, "Та маршрутаас гарлаа" to "та маршрутаас гарлаа", "fixed.offRoute:final"),
            Triple(VoiceContent.GpsLost, "GPS дохио тасарлаа" to "жи пи эс дохио тасарлаа", "fixed.gpsLost:final"),
            Triple(VoiceContent.GpsRestored, "GPS дохио сэргэлээ" to "жи пи эс дохио сэргэлээ", "fixed.gpsRestored:final"),
        )
        for ((input, texts, segs) in rows) {
            val p = m(input)!!
            assertEquals("$input display", texts.first, p.display)
            assertEquals("$input spoken", texts.second, p.spoken)
            assertEquals("$input segments", segs, Segment.tokens(p.segments))
        }
        assertNull("continue on at 995,000 m: no prompt (D102)", m(VoiceContent.ContinueOn(995_000.0)))
        assertNull(m(VoiceContent.ContinueOn(1_000_000.0)))
    }

    @Test
    fun ac22cBannerKeepsOneDecimalVoiceSaysHalf() {
        val key = k("fork", "left")
        // The banner distance keeps one decimal (C3, unchanged): «1,3 км».
        assertEquals("1,3" + Formatters.NBSP + "км", Formatters.distance(1_300.0, Lang.MN, mn))
        assertEquals("нэг хагас километрт зүүн талаа барина уу", m(man(key, 1_300.0))!!.spoken)
    }

    @Test
    fun ac22aAnd22bBoundaries() {
        val turn = k("turn", "right")
        val prefix = listOf(
            994.99 to "dist.km.1", 995.0 to "dist.km.1", 1_249.99 to "dist.km.1", 1_250.0 to "dist.km.1_5",
            1_749.99 to "dist.km.1_5", 1_750.0 to "dist.km.2", 974.99 to "dist.m.950", 975.0 to "dist.km.1",
            29.99 to "instr.turn.right", 30.0 to "dist.m.30", 94.99 to "dist.m.90", 95.0 to "dist.m.100",
        )
        for ((d, key) in prefix) assertEquals("$d m", key, m(man(turn, d))!!.segments.first().key)
        val cont = listOf(99_499.0 to "cont.km.99", 99_500.0 to "cont.km.100", 994_999.0 to "cont.km.990")
        for ((d, key) in cont) assertEquals("$d m", key, m(VoiceContent.ContinueOn(d))!!.segments.first().key)
        assertNull(m(VoiceContent.ContinueOn(995_000.0)))
    }

    @Test
    fun ac24SpokenEqualsTheJoinedSegmentTexts() {
        val p = m(man(k("roundabout", "right", 2.0), 200.0, k("turn", "slight right")))!!
        val texts = p.segments.map { inv.spoken(it.key, it.take)!! }
        assertEquals("${texts[0]} ${texts[1]}, ${texts[2]} ${texts[3]}", p.spoken)
        assertEquals(p.spoken, VoiceText.spokenText(p.segments, inv))
        assertEquals(Take.NON_FINAL, p.segments[1].take)
    }

    @Test
    fun englishIsUnchanged() {
        val cases = listOf(
            man(k("turn", "right"), 1_300.0) to "In 1.3 kilometers, turn right",
            VoiceContent.ContinueOn(2_480.0) to "Continue for 2.5 kilometers",
            VoiceContent.ContinueOn(995_000.0) to "Continue for 995 kilometers",
            man(k("roundabout", "right", 2.0), 200.0, k("turn", "left")) to "In 200 meters, enter the roundabout and take the second exit, then turn left",
            VoiceContent.GpsLost to "GPS signal lost",
        )
        for ((c, text) in cases) {
            val p = e(c)!!
            assertEquals(text, p.display)
            assertEquals("D96: English speaks its display text", p.display, p.spoken)
            assertTrue("English has no segments", p.segments.isEmpty())
            assertEquals(VoiceText.render(c, Lang.EN, en), p.display)
        }
    }

    @Test
    fun aSegmentMissingFromTheInventoryNeverYieldsAComposedNumber() {
        // Outside the AC 27 domain (a walking catch-up 3 km ahead): dist.km.3 has no entry, so the prompt keeps the
        // segment (the pack voice then plays the chime) and the spoken text falls back to the display text.
        val p = m(man(k("turn", "right"), 3_000.0))!!
        assertEquals("dist.km.3", p.segments.first().key)
        assertEquals("3 километрт баруун тийш эргэнэ үү", p.spoken)
        val empty = VoiceText.prompt(VoiceContent.OffRoute, Lang.MN, mn, VoiceInventory.EMPTY)!!
        assertEquals(listOf(Segment("fixed.offRoute", Take.FINAL)), empty.segments)
        assertTrue(C3Table.byKey.containsKey("dist.km.2") && !C3Table.byKey.containsKey("dist.km.3"))
    }
}

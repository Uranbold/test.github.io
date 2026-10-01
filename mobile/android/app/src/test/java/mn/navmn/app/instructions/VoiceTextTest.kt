package mn.navmn.app.instructions

import mn.navmn.app.i18n.Lang
import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-005 AC 32 (examples word for word), AC 33 (voice scans), navigation-ux §4.1. */
class VoiceTextTest {
    private val mn = TestStrings.of(Lang.MN)
    private val en = TestStrings.of(Lang.EN)
    private fun m(c: VoiceContent) = VoiceText.render(c, Lang.MN, mn)
    private fun e(c: VoiceContent) = VoiceText.render(c, Lang.EN, en)
    private fun k(type: String, modifier: String? = null, exit: Double? = null, bearing: Double? = null) =
        ManeuverRules.key(ManeuverInput(type, modifier, exit, bearing))

    @Test
    fun ac32Examples() {
        assertEquals("300 метрт баруун тийш эргэнэ үү", m(VoiceContent.Maneuver(k("turn", "right"), 300.0)))
        assertEquals("1,5 километрт зүүн талаа барина уу", m(VoiceContent.Maneuver(k("fork", "slight left"), 1500.0)))
        assertEquals("200 метрт тойрогт ороод хоёрдугаар гарцаар гарна уу", m(VoiceContent.Maneuver(k("roundabout", "right", 2.0), 200.0)))
        assertEquals("Зүүн тийш эргэнэ үү", m(VoiceContent.Maneuver(k("turn", "left"), 20.0)))
        assertEquals("Та очих газартаа ирлээ", m(VoiceContent.Arrival(k("arrive"))))
    }

    @Test
    fun distancePrefixRounding() {
        val turn = k("turn", "right")
        assertEquals("Баруун тийш эргэнэ үү", m(VoiceContent.Maneuver(turn, 29.9)))
        assertEquals("30 метрт баруун тийш эргэнэ үү", m(VoiceContent.Maneuver(turn, 30.0)))
        assertEquals("60 метрт баруун тийш эргэнэ үү", m(VoiceContent.Maneuver(turn, 64.0)))
        assertEquals("90 метрт баруун тийш эргэнэ үү", m(VoiceContent.Maneuver(turn, 94.9)))
        assertEquals("100 метрт баруун тийш эргэнэ үү", m(VoiceContent.Maneuver(turn, 95.0)))
        assertEquals("250 метрт баруун тийш эргэнэ үү", m(VoiceContent.Maneuver(turn, 260.0)))
        assertEquals("1 километрт баруун тийш эргэнэ үү", m(VoiceContent.Maneuver(turn, 995.0)))
        assertEquals("2 километрт баруун тийш эргэнэ үү", m(VoiceContent.Maneuver(turn, 2010.0)))
        assertEquals("9,9 километрт баруун тийш эргэнэ үү", m(VoiceContent.Maneuver(turn, 9940.0)))
        assertEquals("10 километрт баруун тийш эргэнэ үү", m(VoiceContent.Maneuver(turn, 9950.0)))
        assertEquals("12 километрт баруун тийш эргэнэ үү", m(VoiceContent.Maneuver(turn, 12_400.0)))
    }

    @Test
    fun roundaboutOrdinalsAndFallback() {
        assertEquals("Тойрогт ороод нэгдүгээр гарцаар гарна уу", m(VoiceContent.Maneuver(k("roundabout", null, 1.0), 10.0)))
        assertEquals("Тойрогт ороод аравдугаар гарцаар гарна уу", m(VoiceContent.Maneuver(k("rotary", null, 10.0), 10.0)))
        assertEquals("Тойрогт орно уу", m(VoiceContent.Maneuver(k("roundabout", null, 11.0), 10.0)))
        assertEquals("Тойрогт орно уу", m(VoiceContent.Maneuver(k("roundabout"), 10.0)))
        assertEquals("Тойргоос гарна уу", m(VoiceContent.Maneuver(k("exit roundabout", "right"), 10.0)))
    }

    @Test
    fun departApproachingContinueOnChained() {
        assertEquals("Хойд зүг рүү явна уу", m(VoiceContent.Depart(k("depart", bearing = 0.0))))
        assertEquals("300 метрт очих газартаа ирнэ", m(VoiceContent.Approaching(310.0)))
        assertEquals("12 километр үргэлжлүүлэн явна уу", m(VoiceContent.ContinueOn(12_300.0)))
        assertEquals("2,5 километр үргэлжлүүлэн явна уу", m(VoiceContent.ContinueOn(2_480.0)))
        assertEquals(
            "200 метрт тойрогт ороод хоёрдугаар гарцаар гарна уу, дараа нь бага зэрэг баруун тийш эргэнэ үү",
            m(VoiceContent.Maneuver(k("roundabout", null, 2.0), 200.0, k("turn", "slight right"))),
        )
        assertEquals(
            "Хойд зүг рүү явна уу, дараа нь зүүн тийш эргэнэ үү",
            m(VoiceContent.Depart(k("depart", bearing = 5.0), k("turn", "left"))),
        )
        assertEquals("Та маршрутаас гарлаа", m(VoiceContent.OffRoute))
        assertEquals("GPS дохио тасарлаа", m(VoiceContent.GpsLost))
        assertEquals("GPS дохио сэргэлээ", m(VoiceContent.GpsRestored))
        assertEquals("Таны очих газар баруун талд байна", m(VoiceContent.Arrival(k("arrive", "right"))))
    }

    @Test
    fun english() {
        assertEquals("In 300 meters, turn right", e(VoiceContent.Maneuver(k("turn", "right"), 300.0)))
        assertEquals("In 1.5 kilometers, keep left", e(VoiceContent.Maneuver(k("fork", "left"), 1500.0)))
        assertEquals("In 200 meters, enter the roundabout and take the second exit", e(VoiceContent.Maneuver(k("roundabout", null, 2.0), 200.0)))
        assertEquals("Turn left", e(VoiceContent.Maneuver(k("turn", "left"), 20.0)))
        assertEquals("In 300 meters, you will arrive", e(VoiceContent.Approaching(300.0)))
        assertEquals("Continue for 12 kilometers", e(VoiceContent.ContinueOn(12_000.0)))
        assertEquals("In 100 meters, turn right, then turn left", e(VoiceContent.Maneuver(k("turn", "right"), 100.0, k("turn", "left"))))
        assertEquals("You have arrived", e(VoiceContent.Arrival(k("arrive"))))
    }

    /** Every voice text the generator can produce for the fixture keys and the schedule's distance range. */
    /** NAV-005-D4, navigation-ux §4.1 "English singular / plural": singular only when the formatted {n} is exactly "1". */
    @Test
    fun englishSingularAndPluralFromTheFormattedNumber() {
        assertEquals("In 1 kilometer, turn slightly left", e(VoiceContent.Maneuver(k("turn", "slight left"), 1_040.0)))
        assertEquals("In 1.5 kilometers, keep left", e(VoiceContent.Maneuver(k("fork", "slight left"), 1_500.0)))
        assertEquals("Continue for 2 kilometers", e(VoiceContent.ContinueOn(2_000.0)))
        // 995 m rounds to "1" km (singular); 1,050 m formats as "1.1" (plural, never an int cast to 1)
        assertEquals("In 1 kilometer, turn right", e(VoiceContent.Maneuver(k("turn", "right"), 995.0)))
        assertEquals("In 1.1 kilometers, turn right", e(VoiceContent.Maneuver(k("turn", "right"), 1_050.0)))
        assertEquals("Continue for 1 kilometer", e(VoiceContent.ContinueOn(1_000.0)))
        assertEquals("In 300 meters, you will arrive", e(VoiceContent.Approaching(310.0)))
        assertEquals("In 30 meters, turn right", e(VoiceContent.Maneuver(k("turn", "right"), 30.0)))
        // Mongolian is unchanged (no plural change)
        assertEquals("1 километрт бага зэрэг зүүн тийш эргэнэ үү", m(VoiceContent.Maneuver(k("turn", "slight left"), 1_040.0)))
        assertEquals("1 километр үргэлжлүүлэн явна уу", m(VoiceContent.ContinueOn(1_000.0)))
        // across the whole prefix range: singular exactly when the number is "1" (never "1 kilometers", never "1.5 kilometer")
        val unit = Regex("(\\d+(?:\\.\\d)?) (kilometers?|meters?)\\b")
        var d = 30.0
        while (d < 20_000.0) {
            for (t in listOf(e(VoiceContent.Maneuver(k("turn", "right"), d)), e(VoiceContent.ContinueOn(d)), e(VoiceContent.Approaching(d)))) {
                val u = unit.find(t) ?: error("$d m: no distance in «$t»")
                assertEquals("$d m: «$t»", u.groupValues[1] == "1", !u.groupValues[2].endsWith("s"))
            }
            d += 7.0
        }
    }

    private fun mongolianVoiceSet(): List<String> {
        val keys = ManeuverKey.entries.map { if (it == ManeuverKey.ROUNDABOUT_EXIT) KeyResult(it, 2) else KeyResult(it) } +
            (1..12).map { KeyResult(ManeuverKey.ROUNDABOUT_EXIT, it) }
        val distances = listOf(5.0, 15.0, 29.0, 31.0, 47.0, 80.0, 96.0, 150.0, 249.0, 500.0, 994.0, 1000.0, 1499.0, 2000.0, 9949.0, 9950.0, 25_000.0)
        val out = ArrayList<String>()
        for (key in keys) {
            for (d in distances) out += m(VoiceContent.Maneuver(key, d))
            out += m(VoiceContent.Maneuver(key, 120.0, KeyResult(ManeuverKey.TURN_SLIGHT_RIGHT)))
            out += m(VoiceContent.Depart(KeyResult(ManeuverKey.DEPART_SE), key))
            if (key.key.isArrive) out += m(VoiceContent.Arrival(key))
        }
        for (d in distances.filter { it >= 30 && it <= 500 }) out += m(VoiceContent.Approaching(d))
        for (d in distances.filter { it >= 2000 }) out += m(VoiceContent.ContinueOn(d))
        out += listOf(m(VoiceContent.OffRoute), m(VoiceContent.GpsLost), m(VoiceContent.GpsRestored))
        return out
    }

    @Test
    fun ac33VoiceScans() {
        val all = mongolianVoiceSet()
        assertTrue(all.size > 500)
        val bad = ArrayList<String>()
        val rules = listOf(
            Regex("\\d\\s?(м|км)(\\s|-|/|$)"),
            Regex("\\d+-р"),
            Regex("(\\d+|нэг|хоёр|гурав|дөрөв|тав|зургаа|долоо|найм|ес|арав)\\s(дахь|дэх)"),
            Regex("ШТС"),
            Regex("[<>{}]"),
            Regex("[\u200B\u200C\u200D\uFEFF]"),
        )
        for (t in all) {
            rules.filter { it.containsMatchIn(t) }.forEach { bad += "«$t» matches ${it.pattern}" }
            if (Regex("[A-Za-z]").containsMatchIn(t.replace("GPS", ""))) bad += "«$t» has Latin letters"
            // C2: every relative зүүн/баруун is followed by a C2 form (тийш, талаа, талд, талын, талаас) or зүг (cardinal).
            for (mm in Regex("(зүүн|баруун)\\s+(\\S+)", RegexOption.IGNORE_CASE).findAll(t)) {
                val next = mm.groupValues[2]
                if (next !in setOf("тийш", "талаа", "талд", "талын", "талаас", "зүг", "хойд", "өмнө")) bad += "«$t»: bare ${mm.value}"
            }
        }
        assertEquals(bad.take(20).joinToString("\n"), 0, bad.size)
    }
}

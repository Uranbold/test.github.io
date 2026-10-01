package mn.navmn.app.format

import mn.navmn.app.i18n.Lang
import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** NAV-004 AC 23–25 formats reused by NAV-005 (AC 6, 21, 22). Number and unit joined by U+00A0. */
class FormattersTest {
    private val mn = TestStrings.of(Lang.MN)
    private val en = TestStrings.of(Lang.EN)
    private val nb = Formatters.NBSP

    @Test
    fun distance() {
        assertEquals("10${nb}м", Formatters.distance(0.0, Lang.MN, mn))
        assertEquals("10${nb}м", Formatters.distance(4.0, Lang.MN, mn))
        assertEquals("300${nb}м", Formatters.distance(304.0, Lang.MN, mn))
        assertEquals("990${nb}м", Formatters.distance(994.9, Lang.MN, mn))
        assertEquals("1${nb}км", Formatters.distance(995.0, Lang.MN, mn))
        assertEquals("1,4${nb}км", Formatters.distance(1_440.0, Lang.MN, mn))
        assertEquals("1.4${nb}km", Formatters.distance(1_440.0, Lang.EN, en))
        assertEquals("12,4${nb}км", Formatters.distance(12_449.0, Lang.MN, mn))
        assertEquals("100${nb}км", Formatters.distance(99_950.0, Lang.MN, mn))
    }

    @Test
    fun duration() {
        assertEquals("1${nb}мин", Formatters.duration(10.0, mn))
        assertEquals("25${nb}мин", Formatters.duration(25 * 60.0, mn))
        assertEquals("1${nb}ц 25${nb}мин", Formatters.duration(85 * 60.0, mn))
        assertEquals("2${nb}ц", Formatters.duration(120 * 60.0, mn))
        assertEquals("1${nb}h 5${nb}min", Formatters.duration(65 * 60.0, en))
    }

    @Test
    fun eta() {
        val zone = ZoneId.of("Asia/Ulaanbaatar")
        val base = LocalDateTime.of(2026, 10, 1, 14, 10, 20).atZone(zone).toInstant().toEpochMilli()
        val e = Formatters.eta(base, 25 * 60.0, zone)
        assertEquals(Formatters.Eta("14:35", 0), e)
        assertEquals("Хүрэх цаг 14:35", Formatters.etaText(e, mn))
        val late = LocalDateTime.of(2026, 10, 1, 23, 50, 0).atZone(zone).toInstant().toEpochMilli()
        val e2 = Formatters.eta(late, 40 * 60.0, zone)
        assertEquals(Formatters.Eta("00:30", 1), e2)
        assertEquals("Хүрэх цаг 00:30 +1 өдөр", Formatters.etaText(e2, mn))
        assertEquals("Arrive at 00:30 +1 day", Formatters.etaText(e2, en))
        assertEquals("+2 days", Formatters.nextDay(2, en))
    }

    @Test
    fun coordinates() {
        assertEquals("47.91890, 106.91760", Formatters.coordinates(47.9189, 106.9176))
    }
}

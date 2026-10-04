package mn.navmn.app.theme.sun

import mn.navmn.app.geo.LatLon
import mn.navmn.app.settings.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * NAV-012 AC 40–44: the on-device sun function and the «Автомат» theme switching (fake clocks, 0 network).
 *
 * Reference values: computed 2026-10-03 with the independent `astral` 3.2 Python package (an implementation of the
 * NOAA solar calculator, sun centre at −0.833°, UTC). QA records the NOAA Solar Calculator values themselves in the
 * AC 40 fixture (test plan); this table is the mobile engineer's cross-check, not that fixture.
 */
class SunThemeTest {
    private data class Ref(val point: String, val lat: Double, val lon: Double, val date: String, val rise: String, val set: String)

    private val refs = listOf(
        Ref("P1", 47.9189, 106.9176, "2026-03-20", "-22:54:18", "11:04:13"),
        Ref("P1", 47.9189, 106.9176, "2026-06-21", "-20:53:34", "12:54:49"),
        Ref("P1", 47.9189, 106.9176, "2026-09-23", "-22:41:28", "10:48:40"),
        Ref("P1", 47.9189, 106.9176, "2026-12-21", "00:39:06", "09:01:23"),
        Ref("X1", 49.0270, 104.0440, "2026-03-20", "-23:05:39", "11:15:49"),
        Ref("X1", 49.0270, 104.0440, "2026-06-21", "-20:59:50", "13:11:32"),
        Ref("X1", 49.0270, 104.0440, "2026-09-23", "-22:52:56", "11:00:13"),
        Ref("X1", 49.0270, 104.0440, "2026-12-21", "00:55:25", "09:08:04"),
        Ref("K1", 48.0056, 91.6419, "2026-03-20", "-23:55:19", "12:05:23"),
        Ref("K1", 48.0056, 91.6419, "2026-06-21", "-21:54:17", "13:56:20"),
        Ref("K1", 48.0056, 91.6419, "2026-09-23", "-23:42:37", "11:49:41"),
        Ref("K1", 48.0056, 91.6419, "2026-12-21", "01:40:36", "10:02:08"),
        Ref("Z1", 43.7167, 111.9000, "2026-03-20", "-22:34:49", "10:43:58"),
        Ref("Z1", 43.7167, 111.9000, "2026-06-21", "-20:51:05", "12:17:26"),
        Ref("Z1", 43.7167, 111.9000, "2026-09-23", "-22:21:38", "10:28:32"),
        Ref("Z1", 43.7167, 111.9000, "2026-12-21", "00:02:58", "08:57:39"),
    )

    /** "-HH:MM:SS" = that UTC time on the previous UTC day (local morning in Mongolia, UTC+7/+8). */
    private fun instant(date: String, t: String): Instant {
        val d = LocalDate.parse(date)
        val day = if (t.startsWith("-")) d.minusDays(1) else d
        return day.atTime(LocalTime.parse(t.removePrefix("-"))).toInstant(ZoneOffset.UTC)
    }

    @Test
    fun fourPointsFourDatesWithinTwoMinutes() {
        for (r in refs) {
            val s = SunCalc.sunTimes(LocalDate.parse(r.date), r.lat, r.lon) as SunTimes.Rise
            val dr = SunCalc.minutesBetween(s.sunrise, instant(r.date, r.rise))
            val ds = SunCalc.minutesBetween(s.sunset, instant(r.date, r.set))
            assertTrue("${r.point} ${r.date} sunrise ${s.sunrise} off by $dr min", dr <= 2.0)
            assertTrue("${r.point} ${r.date} sunset ${s.sunset} off by $ds min", ds <= 2.0)
        }
    }

    @Test
    fun storyIllustrationP1Winter() {
        // AC 40 illustration: at P1 on 2026-12-21 sunrise about 08:40 and sunset about 17:00 (UTC+8).
        val s = SunCalc.sunTimes(LocalDate.parse("2026-12-21"), 47.9189, 106.9176) as SunTimes.Rise
        val local = ZoneOffset.ofHours(8)
        assertEquals(8, s.sunrise.atOffset(local).hour)
        assertTrue(s.sunrise.atOffset(local).minute in 35..45)
        assertEquals(17, s.sunset.atOffset(local).hour)
        assertTrue(s.sunset.atOffset(local).minute in 0..5)
    }

    @Test
    fun dayBetweenSunriseAndSunsetInUtcRegardlessOfTimeZone() {
        val s = SunCalc.sunTimes(LocalDate.parse("2026-12-21"), 47.9189, 106.9176) as SunTimes.Rise
        assertFalse(SunCalc.isDay(s.sunrise.minusSeconds(120), 47.9189, 106.9176))
        assertTrue(SunCalc.isDay(s.sunrise.plusSeconds(120), 47.9189, 106.9176))
        assertTrue(SunCalc.isDay(s.sunset.minusSeconds(120), 47.9189, 106.9176))
        assertFalse(SunCalc.isDay(s.sunset.plusSeconds(120), 47.9189, 106.9176))
        // K1 (UTC+7 zone) uses the same UTC computation: noon UTC+7 is day, 22:00 UTC+7 is night.
        assertTrue(SunCalc.isDay(Instant.parse("2026-12-21T05:00:00Z"), 48.0056, 91.6419))
        assertFalse(SunCalc.isDay(Instant.parse("2026-12-21T15:00:00Z"), 48.0056, 91.6419))
    }

    @Test
    fun polarDaysAndNightsAndAnyLatitudeNeverThrow() {
        assertEquals(SunTimes.PolarDay, SunCalc.sunTimes(LocalDate.parse("2026-06-21"), 78.2, 15.6))
        assertEquals(SunTimes.PolarNight, SunCalc.sunTimes(LocalDate.parse("2026-12-21"), 78.2, 15.6))
        assertTrue(SunCalc.isDay(Instant.parse("2026-06-21T23:00:00Z"), 78.2, 15.6))
        assertFalse(SunCalc.isDay(Instant.parse("2026-12-21T12:00:00Z"), 78.2, 15.6))
        var d = LocalDate.parse("2026-01-01")
        while (d.year == 2026) {
            var lat = -90.0
            while (lat <= 90.0) {
                SunCalc.sunTimes(d, lat, 106.9)
                SunCalc.isDay(d.atStartOfDay().toInstant(ZoneOffset.UTC), lat, -179.9)
                lat += 7.5
            }
            d = d.plusDays(13)
        }
        SunCalc.sunTimes(LocalDate.parse("2026-03-20"), Double.NaN, 0.0)
        SunCalc.isDay(Instant.EPOCH, 90.0, 0.0)
        SunCalc.isDay(Instant.EPOCH, -90.0, 0.0)
    }

    @Test
    fun atMostOneAutomaticChangePerTenMinutes() {
        val h = AutoThemeHold()
        assertFalse(h.update(false, 0)) // first evaluation: no change counted
        assertTrue(h.update(true, 1_000)) // sunset
        assertTrue("flicker back within 10 min is held", h.update(false, 5 * 60_000L))
        assertTrue(h.update(false, 10 * 60_000L)) // 10 min after 1 s: still 1 ms short
        assertFalse("applied when the hold-off ends", h.update(false, 10 * 60_000L + 1_000))
    }

    @Test
    fun positionSessionFixThenLastKnownWithin24hThenP1() {
        val fix = LatLon(49.0, 104.0)
        val last = LatLon(48.0, 91.6)
        assertEquals(fix, SunPosition.choose(fix, last, 1_000))
        assertEquals(last, SunPosition.choose(null, last, 23 * 3_600_000L))
        assertEquals(SunPosition.P1, SunPosition.choose(null, last, 25 * 3_600_000L))
        assertEquals(SunPosition.P1, SunPosition.choose(null, null, null))
    }

    @Test
    fun autoFollowsTheSunDayAndNightAreFixed() {
        assertFalse(ThemeResolver.night(ThemeChoice.DAY, sunNight = true))
        assertTrue(ThemeResolver.night(ThemeChoice.NIGHT, sunNight = false))
        assertTrue(ThemeResolver.night(ThemeChoice.AUTO, sunNight = true))
        assertFalse(ThemeResolver.night(ThemeChoice.AUTO, sunNight = false))
    }

    @Test
    fun sunThemeSwitchesWithin60sOfSunsetAndHolds() {
        val sunset = (SunCalc.sunTimes(LocalDate.parse("2026-12-21"), 47.9189, 106.9176) as SunTimes.Rise).sunset.toEpochMilli()
        var wall = sunset - 20 * 60_000L
        var elapsed = 0L
        var lastKnownReads = 0
        val theme = SunTheme({ lastKnownReads++; null }, { wall }, { elapsed })
        assertFalse(theme.night.value) // P1 before sunset
        assertEquals(1, lastKnownReads)
        // 30 s polling (AC 42: ≤ 60 s after the computed time).
        while (wall < sunset + 60_000L) {
            wall += SunTheme.POLL_MS
            elapsed += SunTheme.POLL_MS
            theme.evaluate()
            if (wall >= sunset + SunTheme.POLL_MS) break
        }
        assertTrue("night within 60 s after sunset", theme.night.value)
        // A session fix keeps the platform's last known location unread (memory only, never stored).
        theme.onFix(LatLon(47.9, 106.9))
        val reads = lastKnownReads
        theme.evaluate()
        assertEquals(reads, lastKnownReads)
    }
}

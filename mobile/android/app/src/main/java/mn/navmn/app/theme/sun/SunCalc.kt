package mn.navmn.app.theme.sun

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Sunrise and sunset of one UTC date, or a polar day / night without them (AC 44). */
sealed interface SunTimes {
    data class Rise(val sunrise: Instant, val sunset: Instant) : SunTimes
    data object PolarDay : SunTimes
    data object PolarNight : SunTimes
}

/**
 * NAV-012 AC 40–44 / ADR-0013 §7: the NOAA solar calculator algorithm (Julian century, geometric mean longitude and
 * anomaly, equation of centre, apparent longitude, corrected obliquity, declination, equation of time, hour angle for
 * a solar zenith of 90.833°, i.e. the sun's centre at −0.833°: standard refraction plus the solar radius). Pure Kotlin,
 * no dependency, all in **UTC**, so the device time zone never changes the result (Mongolia: UTC+8 and UTC+7, no DST).
 * Never throws for any latitude −90…90 and any date; polar days and nights are returned as such.
 */
object SunCalc {
    /** Sun centre altitude at sunrise and sunset. */
    const val HORIZON_DEG = -0.833
    private const val ZENITH_DEG = 90.833
    private const val J2000 = 2_451_545.0
    private const val UNIX_EPOCH_JD = 2_440_587.5

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    private fun julianDay(epochMs: Long): Double = epochMs / 86_400_000.0 + UNIX_EPOCH_JD

    /** Declination (degrees) and equation of time (minutes) at a Julian day (NOAA spreadsheet formulas). */
    private fun solar(jd: Double): Pair<Double, Double> {
        val t = (jd - J2000) / 36_525.0
        val l0 = ((280.46646 + t * (36_000.76983 + t * 0.0003032)) % 360.0 + 360.0) % 360.0
        val m = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(rad(2 * m)) * (0.019993 - 0.000101 * t) +
            sin(rad(3 * m)) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1_934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sin(rad(omega))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(rad(omega))
        val decl = deg(asin(sin(rad(eps)) * sin(rad(lambda))))
        val y = tan(rad(eps / 2)).let { it * it }
        val eqTime = 4.0 * deg(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m)),
        )
        return decl to eqTime
    }

    /** Cosine of the sunrise hour angle; outside [−1, 1] → no sunrise or sunset that day. */
    private fun cosHourAngle(latDeg: Double, declDeg: Double): Double {
        val lat = rad(latDeg.coerceIn(-89.9999, 89.9999))
        val d = rad(declDeg)
        return cos(rad(ZENITH_DEG)) / (cos(lat) * cos(d)) - tan(lat) * tan(d)
    }

    /**
     * Sunrise and sunset around the solar noon of the UTC date [date] at ([latDeg], [lonDeg], east positive). Each
     * event is refined twice with the declination and equation of time at the event itself (as the NOAA calculator
     * does), which keeps the result within seconds of the NOAA values.
     */
    fun sunTimes(date: LocalDate, latDeg: Double, lonDeg: Double): SunTimes {
        if (!latDeg.isFinite() || !lonDeg.isFinite()) return SunTimes.PolarNight
        val midnightMs = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val lon = ((lonDeg + 540.0) % 360.0) - 180.0
        val noonGuess = midnightMs + ((720.0 - 4.0 * lon) * 60_000.0).toLong()
        val (declNoon, _) = solar(julianDay(noonGuess))
        val c = cosHourAngle(latDeg, declNoon)
        if (c > 1) return SunTimes.PolarNight
        if (c < -1) return SunTimes.PolarDay
        fun event(sign: Int): Instant? {
            var minutes = 720.0 - 4.0 * lon // first guess: solar noon
            repeat(3) {
                val at = midnightMs + (minutes * 60_000.0).toLong()
                val (decl, eq) = solar(julianDay(at))
                val ch = cosHourAngle(latDeg, decl)
                if (ch !in -1.0..1.0) return null
                val ha = deg(acos(ch))
                minutes = 720.0 - 4.0 * (lon + sign * ha) - eq // rise: +HA, set: −HA (NOAA)
            }
            return Instant.ofEpochMilli(midnightMs + (minutes * 60_000.0).toLong())
        }
        val rise = event(+1)
        val set = event(-1)
        if (rise == null || set == null) return if (elevationDeg(Instant.ofEpochMilli(noonGuess), latDeg, lonDeg) > HORIZON_DEG) SunTimes.PolarDay else SunTimes.PolarNight
        return SunTimes.Rise(rise, set)
    }

    /** Geometric elevation of the sun's centre (degrees, no refraction) at [instant]; compare with [HORIZON_DEG]. */
    fun elevationDeg(instant: Instant, latDeg: Double, lonDeg: Double): Double {
        if (!latDeg.isFinite() || !lonDeg.isFinite()) return -90.0
        val ms = instant.toEpochMilli()
        val (decl, eq) = solar(julianDay(ms))
        val utcMinutes = ((ms % 86_400_000L + 86_400_000L) % 86_400_000L) / 60_000.0
        val trueSolar = ((utcMinutes + eq + 4.0 * lonDeg) % 1_440.0 + 1_440.0) % 1_440.0
        val hourAngle = trueSolar / 4.0 - 180.0
        val lat = rad(latDeg.coerceIn(-90.0, 90.0))
        val cosZenith = (sin(lat) * sin(rad(decl)) + cos(lat) * cos(rad(decl)) * cos(rad(hourAngle))).coerceIn(-1.0, 1.0)
        return 90.0 - deg(acos(cosZenith))
    }

    /** AC 41 / AC 44: day = the sun's centre above −0.833° at that moment (equals "between sunrise and sunset"). */
    fun isDay(instant: Instant, latDeg: Double, lonDeg: Double): Boolean = elevationDeg(instant, latDeg, lonDeg) > HORIZON_DEG

    /** Minutes between two instants (tests). */
    fun minutesBetween(a: Instant, b: Instant): Double = abs(a.toEpochMilli() - b.toEpochMilli()) / 60_000.0
}

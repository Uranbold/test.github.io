package mn.navmn.app.format

import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Strings
import mn.navmn.app.i18n.Templates
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Distance, duration and «Хүрэх цаг» formatting, ported from web/src/route/format.ts (NAV-004 AC 23–25; glossary C3,
 * C6, N16, N21, N22). Used by the preview, banner, progress panel and notification. Number and unit are joined by a
 * no-break space (screen spec › Content rules).
 */
object Formatters {
    const val NBSP = " "

    private fun decimal(value: String, lang: Lang) = if (lang == Lang.MN) value.replace('.', ',') else value

    /** d < 995 m → nearest 10 m (minimum 10); < 99,950 m → km with one decimal, ".0" dropped; else whole km. */
    fun distance(meters: Double, lang: Lang, strings: Strings): String {
        val d = if (meters.isFinite() && meters > 0) meters else 0.0
        if (d < 995) return "${maxOf(10L, Math.round(d / 10) * 10)}$NBSP${strings[StringKey.UNIT_M]}"
        if (d < 99_950) {
            val tenths = Math.round(d / 100)
            val km = if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
            return "${decimal(km, lang)}$NBSP${strings[StringKey.UNIT_KM]}"
        }
        return "${Math.round(d / 1000)}$NBSP${strings[StringKey.UNIT_KM]}"
    }

    /** Nearest minute (minimum 1); «25 мин», «1 ц 25 мин», «2 ц». */
    fun duration(seconds: Double, strings: Strings): String {
        val s = if (seconds.isFinite() && seconds > 0) seconds else 0.0
        val minutes = maxOf(1L, Math.round(s / 60))
        val min = strings[StringKey.UNIT_MIN]
        val h = strings[StringKey.UNIT_H]
        if (minutes < 60) return "$minutes$NBSP$min"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0L) "$hours$NBSP$h" else "$hours$NBSP$h $rest$NBSP$min"
    }

    data class Eta(val time: String, val days: Int)

    /** Arrival = base + duration rounded to the nearest minute, local 24-hour time; days after the base's day. */
    fun eta(baseMs: Long, durationS: Double, zone: ZoneId): Eta {
        val arrivalMs = Math.round((baseMs + maxOf(0.0, durationS) * 1000.0) / 60_000.0) * 60_000L
        val arrival = Instant.ofEpochMilli(arrivalMs).atZone(zone)
        val base = Instant.ofEpochMilli(baseMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(base.toLocalDate(), arrival.toLocalDate()).toInt().coerceAtLeast(0)
        return Eta("%02d:%02d".format(arrival.hour, arrival.minute), days)
    }

    /** «Хүрэх цаг 14:35» / «Хүрэх цаг 00:30 +1 өдөр». */
    fun etaText(eta: Eta, strings: Strings): String {
        val head = "${strings[StringKey.ROUTE_ETA]} ${eta.time}"
        return if (eta.days > 0) "$head ${nextDay(eta.days, strings)}" else head
    }

    /** «+{days} өдөр» (CLDR: "one" only for 1 in both mn and en). */
    fun nextDay(days: Int, strings: Strings): String =
        Templates.fill(strings[if (days == 1) StringKey.ROUTE_NEXT_DAY_ONE else StringKey.ROUTE_NEXT_DAY_OTHER], "days" to days.toString())

    /** Coordinates on the coordinate card: 5 decimals, point separator in both languages (NAV-003). */
    fun coordinates(lat: Double, lon: Double): String =
        String.format(java.util.Locale.ROOT, "%.5f, %.5f", lat, lon)
}

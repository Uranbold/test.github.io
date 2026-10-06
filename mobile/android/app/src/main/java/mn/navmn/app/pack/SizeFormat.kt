package mn.navmn.app.pack

import mn.navmn.app.i18n.Lang

/**
 * NAV-022 Terms "Size display" (AC 5): decimal units (1 MB = 1,000,000 bytes), rounded **up**; below 1,000 MB whole
 * megabytes, from 1,000 MB gigabytes with one decimal. The decimal separator follows the app's number rule (comma in
 * Mongolian, glossary C3; point in English, as `Formatters`). A no-break space joins the number and the unit (OF26 /
 * OF27 from the resources), so «120 МБ» never breaks.
 */
object SizeFormat {
    private const val NBSP = ' '
    private const val MB = 1_000_000L
    private const val GB_TENTH = 100_000_000L

    fun format(bytes: Long, lang: Lang, unitMb: String, unitGb: String): String {
        val b = maxOf(0L, bytes)
        val mb = ceilDiv(b, MB)
        if (mb < 1000) return "$mb$NBSP$unitMb"
        val tenths = ceilDiv(b, GB_TENTH)
        val text = "${tenths / 10}${if (lang == Lang.MN) ',' else '.'}${tenths % 10}"
        return "$text$NBSP$unitGb"
    }

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b
}

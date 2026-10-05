package mn.navmn.app.preview.turnlist

import mn.navmn.app.format.Formatters
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.Strings
import mn.navmn.app.instructions.BannerText
import mn.navmn.app.instructions.KeyResult
import mn.navmn.app.route.GuidancePlan

/**
 * One row of «Маршрутын заавар» (NAV-018 AC 18, ADR-0015 §8). Row i is step i's **own** manoeuvre at its
 * `maneuver.location` (the NAV-004 list semantics), not the banners' next-step shift. The row holds keys and numbers
 * only, so the text follows the UI language at composition with 0 requests (AC 24).
 */
data class TurnRow(
    val index: Int,
    val key: KeyResult,
    /** `step.name` with zero-width characters removed (PlanStep.street); "" → no street line. Never translated (D11). */
    val street: String,
    /** `step.distance`; null on the `arrive` row (AC 18). */
    val distanceM: Double?,
    val location: LatLon,
)

/** Builds the turn list from the already parsed plan of the selected route (ADR-0015 §8: no re-parse, no request). */
object TurnListModel {
    /**
     * One row per step of `legs[0].steps`. The plan flattens legs; with exactly two `locations` there is one leg, and
     * if more legs ever appear the list stops at the first `arrive` (the end of leg 0). O(steps).
     */
    fun build(plan: GuidancePlan): List<TurnRow> {
        val steps = plan.steps
        val end = steps.indexOfFirst { it.maneuver.type == "arrive" }.let { if (it < 0) steps.lastIndex else it }
        val rows = ArrayList<TurnRow>(end + 1)
        for (i in 0..end) {
            val s = steps[i]
            val arrive = s.maneuver.type == "arrive"
            rows += TurnRow(i, s.key, s.street, if (arrive) null else s.distance, s.location)
        }
        return rows
    }
}

/** Row texts from the existing ADR-0008 Android rules and resources (AC 19: no second mapping, no new strings). */
object TurnRowText {
    fun instruction(row: TurnRow, lang: Lang, strings: Strings): String = BannerText.text(row.key, lang, strings)

    /** NAV-004 AC 23 format («350 м», «2,1 км»); null on `arrive`. */
    fun distance(row: TurnRow, lang: Lang, strings: Strings): String? = row.distanceM?.let { Formatters.distance(it, lang, strings) }

    /** AC 23: instruction, street name and distance joined with ", " (no distance on `arrive`, no empty street). */
    fun contentDescription(row: TurnRow, lang: Lang, strings: Strings): String =
        listOfNotNull(instruction(row, lang, strings), row.street.takeIf { it.isNotEmpty() }, distance(row, lang, strings)).joinToString(", ")
}

/**
 * AC 22 / screen spec Q8 (ADR-0015 §8 row-tap camera rule): centre `maneuver.location` at zoom max(17, current) inside
 * the map area not covered by the sheet or the top bar, with ≥ 40 dp padding plus those insets.
 */
object StepCamera {
    const val MIN_ZOOM = 17.0
    const val PADDING_DP = 40
    const val EASE_MS = 700

    fun zoom(current: Double): Double = maxOf(MIN_ZOOM, current)

    data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Padding in px: 40 dp on every side plus the side sheet ([startPx]), the top bar and the (collapsed) bottom sheet. */
    fun padding(padPx: Int, startPx: Int, topBarPx: Int, sheetPx: Int): Padding =
        Padding(padPx + startPx, padPx + topBarPx, padPx, padPx + sheetPx)
}

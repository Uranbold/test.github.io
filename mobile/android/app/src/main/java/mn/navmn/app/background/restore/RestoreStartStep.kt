package mn.navmn.app.background.restore

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import mn.navmn.app.route.RouteBody
import kotlin.math.abs
import kotlin.math.max

/**
 * ADR-0013 §3.4 step 3 as changed by Amendment 2: the step a restored session starts on, from the first good fix and
 * the stored route's step geometries (pure). The restore stores no step index or position (AC 16), so progress is
 * re-established from the geometry:
 *  1. candidates are the steps whose geometry is within [MAX_DISTANCE_M] of the fix, excluding the final `arrive` step;
 *  2. with a usable bearing (ADR-0009 §2 heading gate: speed ≥ 2 m/s, bearing accuracy ≤ 45°), only steps with a
 *     segment within [MAX_DISTANCE_M] whose direction is within [MAX_BEARING_DIFF_DEG] of it are kept;
 *  3. nearest with a tie margin: only the candidates within d_min + max([TIE_MARGIN_MIN_M], fix accuracy) of the fix
 *     are kept, and the **earliest** of them wins. At a step boundary consecutive steps tie, so the restore never skips
 *     a manoeuvre; an opposite carriageway or a parallel street further away than the margin goes to the nearer
 *     geometry (QA D1 / TC-D07: standing on the return side of a U-turn route).
 * null → more than 50 m from every remaining step: the restored session starts an off-route episode (AC 19).
 *
 * Step 4 of the amendment (the one bearing re-check within 30 s) lives in [mn.navmn.app.engine.GuidanceCore]; it uses
 * [candidates] to decide whether the current step is still one of the best ones.
 */
object RestoreStartStep {
    const val MAX_DISTANCE_M = 50.0
    const val MAX_BEARING_DIFF_DEG = 60.0
    const val TIE_MARGIN_MIN_M = 10.0

    fun usableBearing(fix: Fix): Double? = RouteBody.headingFor(fix)?.toDouble()

    /** max(10 m, horizontal accuracy); an unknown accuracy (NaN) counts as 10 m. */
    fun tieMargin(accuracyM: Double): Double = if (accuracyM.isFinite()) max(TIE_MARGIN_MIN_M, accuracyM) else TIE_MARGIN_MIN_M

    /**
     * The equally good start steps in route order (rules 1–3): empty when no step qualifies.
     * @param steps every step's geometry in route order; the last one is `arrive`.
     */
    fun candidates(position: LatLon, bearingDeg: Double?, accuracyM: Double, steps: List<List<LatLon>>): List<Int> {
        if (steps.isEmpty()) return emptyList()
        val range = if (steps.size == 1) steps.indices else 0 until steps.size - 1
        val near = ArrayList<Pair<Int, Double>>()
        for (i in range) {
            val line = steps[i]
            val d = Geo.distanceToLine(position, line)
            if (d > MAX_DISTANCE_M) continue
            if (bearingDeg != null && line.size >= 2 && !matchesBearing(position, bearingDeg, line)) continue
            near += i to d
        }
        if (near.isEmpty()) return emptyList()
        val limit = near.minOf { it.second } + tieMargin(accuracyM)
        return near.filter { it.second <= limit }.map { it.first }
    }

    /** The start step: the earliest of [candidates], or null (off the stored route). */
    fun choose(position: LatLon, bearingDeg: Double?, accuracyM: Double, steps: List<List<LatLon>>): Int? =
        candidates(position, bearingDeg, accuracyM, steps).firstOrNull()

    private fun matchesBearing(p: LatLon, bearing: Double, line: List<LatLon>): Boolean {
        for (j in 0 until line.size - 1) {
            val seg = listOf(line[j], line[j + 1])
            if (Geo.distance(line[j], line[j + 1]) < 0.5) continue
            if (Geo.distanceToLine(p, seg) > MAX_DISTANCE_M) continue
            if (angleDiff(Geo.bearing(line[j], line[j + 1]), bearing) <= MAX_BEARING_DIFF_DEG) return true
        }
        return false
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(((a - b) % 360 + 360) % 360)
        return if (d > 180) 360 - d else d
    }
}

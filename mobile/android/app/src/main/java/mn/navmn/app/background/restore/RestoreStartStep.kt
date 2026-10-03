package mn.navmn.app.background.restore

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import mn.navmn.app.route.RouteBody
import kotlin.math.abs

/**
 * ADR-0013 §3.4 step 3: the step a restored session starts on, from the first good fix and the stored route's step
 * geometries (pure). The restore stores no step index or position (AC 16), so progress is re-established from the
 * geometry:
 *  - candidates are the steps whose geometry is within [MAX_DISTANCE_M] of the fix, excluding the final `arrive` step;
 *  - with a usable bearing (ADR-0009 §2 heading gate: speed ≥ 2 m/s, bearing accuracy ≤ 45°), only steps with a
 *    segment within [MAX_DISTANCE_M] whose direction is within [MAX_BEARING_DIFF_DEG] of it are kept (out-and-back
 *    routes);
 *  - the **earliest** remaining candidate wins, so the restore itself never skips a manoeuvre. From then on the
 *    existing [mn.navmn.app.engine.StepCatchUp] corrects the step as usual.
 * null → more than 50 m from every remaining step: the restored session starts an off-route episode (AC 19).
 */
object RestoreStartStep {
    const val MAX_DISTANCE_M = 50.0
    const val MAX_BEARING_DIFF_DEG = 60.0

    fun usableBearing(fix: Fix): Double? = RouteBody.headingFor(fix)?.toDouble()

    /** @param steps every step's geometry in route order; the last one is `arrive`. */
    fun choose(position: LatLon, bearingDeg: Double?, steps: List<List<LatLon>>): Int? {
        if (steps.isEmpty()) return null
        val candidates = if (steps.size == 1) steps.indices else 0 until steps.size - 1
        for (i in candidates) {
            val line = steps[i]
            if (Geo.distanceToLine(position, line) > MAX_DISTANCE_M) continue
            if (bearingDeg == null || line.size < 2 || matchesBearing(position, bearingDeg, line)) return i
        }
        return null
    }

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

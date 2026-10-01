package mn.navmn.app.route

import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import java.util.Locale

/** «Машин» / «Явган» (AC 5). */
enum class TravelMode(val costing: String) {
    CAR("auto"),
    WALK("pedestrian"),
}

/** One `POST /v1/route` (openapi 0.5.1 Android guidance profile, ADR-0009 §2). */
data class RouteRequest(
    val origin: LatLon,
    val destination: LatLon,
    val mode: TravelMode,
    val avoidUnpaved: Boolean,
    val lang: Lang,
    /** Reroute only (AC 43): integer 0–359, see [RouteBody.headingFor]. */
    val heading: Int? = null,
)

/**
 * The exact AC 5 / AC 43 request body. The set of fields is fixed; nothing else is ever sent (no `filters`,
 * `street_side_tolerance`, `type`, `heading_tolerance`, `radius`, `id` or toll options). Coordinates are written with
 * 6 decimals (≥ 5, AC 5). Built by hand so the field set and number format are exact.
 */
object RouteBody {
    const val MIN_HEADING_SPEED_MPS = 2.0
    const val MAX_BEARING_ACCURACY_DEG = 45.0

    fun json(req: RouteRequest): String {
        val sb = StringBuilder(256)
        sb.append("{\"locations\":[")
        location(sb, req.origin, req.heading)
        sb.append(',')
        location(sb, req.destination, null)
        sb.append("],\"costing\":\"").append(req.mode.costing).append('"')
        if (req.mode == TravelMode.CAR && req.avoidUnpaved) {
            sb.append(",\"costing_options\":{\"auto\":{\"exclude_unpaved\":true}}")
        }
        sb.append(",\"alternates\":0,\"format\":\"osrm\",\"banner_instructions\":true,\"voice_instructions\":true")
        sb.append(",\"units\":\"kilometers\",\"language\":\"").append(req.lang.routeLanguage).append("\"}")
        return sb.toString()
    }

    private fun location(sb: StringBuilder, p: LatLon, heading: Int?) {
        sb.append("{\"lat\":").append(coord(p.lat)).append(",\"lon\":").append(coord(p.lon))
        if (heading != null) sb.append(",\"heading\":").append(heading)
        sb.append('}')
    }

    private fun coord(v: Double): String = String.format(Locale.ROOT, "%.6f", v)

    /**
     * AC 43 / ADR-0009 §2: heading only when the fix has a bearing, speed ≥ 2.0 m/s and (when reported) bearing
     * accuracy ≤ 45°. Value `round(bearing) mod 360`. No `heading_tolerance` (Valhalla default 60°).
     */
    fun headingFor(fix: Fix): Int? {
        val bearing = fix.bearingDeg ?: return null
        val speed = fix.speedMps ?: return null
        if (!bearing.isFinite() || !speed.isFinite() || speed < MIN_HEADING_SPEED_MPS) return null
        val acc = fix.bearingAccuracyDeg
        if (acc != null && (!acc.isFinite() || acc > MAX_BEARING_ACCURACY_DEG)) return null
        return ((Math.round(bearing) % 360 + 360) % 360).toInt()
    }
}

package mn.navmn.app.geo

import kotlin.math.cos
import kotlin.math.ln

/**
 * NAV-005 AC 74 accuracy circle (map-style §7.8): a ground-true polygon, a port of `web/src/geo/circle.ts` (same
 * spherical destination formula and Earth radius as [Geo.offset]), so the radius matches NAV-002 AC 19 (±10 %).
 */
object AccuracyCircle {
    const val VERTICES = 64

    /** Meters per dp at zoom 0 on the equator for MapLibre's 512 dp tiles (map-style §7.8). */
    private const val M_PER_DP_Z0 = 78_271.517

    /** Closed ring of [vertices] points [radiusM] from [center] (first point repeated last). */
    fun ring(center: LatLon, radiusM: Double, vertices: Int = VERTICES): List<LatLon> {
        val out = ArrayList<LatLon>(vertices + 1)
        for (i in 0 until vertices) {
            val p = Geo.offset(center, i * 360.0 / vertices, radiusM)
            out += LatLon(p.lat, ((p.lon + 540.0) % 360.0) - 180.0)
        }
        out += out[0]
        return out
    }

    /**
     * map-style §7.8 "circle smaller than the dot": the zoom from which a circle of [accuracyM] at [latDeg] has a larger
     * on-screen radius than the dot's outer radius [dotRadiusDp] (`circle-radius` + `circle-stroke-width`):
     * log2(R_dot × 78 271.517 × cos(lat) / accuracy), clamped to MapLibre's 0–24.
     */
    fun minZoom(dotRadiusDp: Double, latDeg: Double, accuracyM: Double): Double {
        if (!(accuracyM > 0.0)) return MAX_ZOOM
        val z = ln(dotRadiusDp * M_PER_DP_Z0 * cos(Math.toRadians(latDeg)) / accuracyM) / ln(2.0)
        return z.coerceIn(0.0, MAX_ZOOM)
    }

    private const val MAX_ZOOM = 24.0
}

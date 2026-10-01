package mn.navmn.app.arrival

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon

/**
 * ADR-0009 §5 arrival (AC 55–57): fires exactly once when (a) Ferrostar reports the trip complete, (b) not off-route,
 * a good fix and ≤ 30 m remaining, or (c) a good fix within 30 m straight-line of the route's last coordinate (G4:
 * stationary 10 m beside the end).
 */
class ArrivalDetector(private val radiusM: Double = 30.0) {
    var arrived: Boolean = false
        private set

    fun check(complete: Boolean, offRoute: Boolean, goodFix: Boolean, distanceRemaining: Double, position: LatLon, routeEnd: LatLon): Boolean {
        if (arrived) return false
        val hit = complete ||
            (!offRoute && goodFix && distanceRemaining <= radiusM) ||
            (goodFix && Geo.distance(position, routeEnd) <= radiusM)
        if (hit) arrived = true
        return hit
    }
}

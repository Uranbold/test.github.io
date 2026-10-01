package mn.navmn.app.arrival

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon

/**
 * ADR-0009 §5 arrival (AC 55–57) with the Amendment 1 / Amendment 3 §5 gating. Fires exactly once when
 *  (a) Ferrostar reports the trip complete;
 *  (b) not off-route, a good AND trusted fix (on the current step or caught up, NAV-005-D9) and ≤ 30 m remaining.
 *      An untrusted fix's remaining distance comes from the snap to the current step's end and can be near 0 far
 *      before the destination;
 *  (c) a good fix within 30 m straight-line of the route's last coordinate (G4: stationary 10 m beside the end), but
 *      only while the upcoming manoeuvre is the `arrive` step (current step ≥ last step − 1). A route that passes close
 *      to its own end earlier (loop, U-turn on a divided avenue), or one outlier at the end coordinate, must not end
 *      guidance early.
 */
class ArrivalDetector(private val radiusM: Double = 30.0) {
    var arrived: Boolean = false
        private set

    /**
     * @param stepIndex current step k of the navigator (the upcoming manoeuvre is k + 1).
     * @param lastStepIndex index of the route's `arrive` step (steps.size − 1).
     */
    fun check(
        complete: Boolean,
        offRoute: Boolean,
        goodFix: Boolean,
        trustedFix: Boolean,
        distanceRemaining: Double,
        position: LatLon,
        routeEnd: LatLon,
        stepIndex: Int,
        lastStepIndex: Int,
    ): Boolean {
        if (arrived) return false
        val onLastLeg = stepIndex >= lastStepIndex - 1
        val hit = complete ||
            (!offRoute && goodFix && trustedFix && distanceRemaining <= radiusM) ||
            (goodFix && onLastLeg && Geo.distance(position, routeEnd) <= radiusM)
        if (hit) arrived = true
        return hit
    }
}

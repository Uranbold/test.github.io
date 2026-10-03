package mn.navmn.app.route

import mn.navmn.app.geo.LatLon
import mn.navmn.app.instructions.KeyResult
import mn.navmn.app.instructions.ManeuverInput
import mn.navmn.app.instructions.ManeuverRules
import mn.navmn.app.instructions.StreetName

/**
 * One OSRM step as the app reads it from the raw response (ADR-0009 §2 "Parsing"). Step i of the plan is step i of
 * the Ferrostar route (legs flattened, F4). [key] is computed once with the ADR-0008 rules.
 */
data class PlanStep(
    val maneuver: ManeuverInput,
    val location: LatLon,
    /** `step.name`, cleaned (zero-width and traditional script removed); "" when empty. */
    val street: String,
    val distance: Double,
    val duration: Double,
) {
    val key: KeyResult = ManeuverRules.key(maneuver)

    companion object {
        fun of(maneuver: ManeuverInput, location: LatLon, name: String?, distance: Double, duration: Double) =
            PlanStep(maneuver, location, StreetName.clean(name), distance, duration)
    }
}

/** Our text and geometry model of a route (generation 0 = previewed route, +1 per reroute; ADR-0009 §3.1). */
data class GuidancePlan(
    val generation: Int,
    val steps: List<PlanStep>,
    val distance: Double,
    val duration: Double,
    val geometry: List<LatLon>,
    /** `waypoints[].distance` (snap distance per location, D51 notice). */
    val snapDistances: List<Double>,
) {
    val end: LatLon get() = geometry.last()
}

/**
 * A parsed route: our plan plus the navigation engine's own route object (Ferrostar `Route` on the device).
 * NAV-012 (ADR-0013 §3.1): [source] is the exact OSRM body that entered the pipeline (before the token rewrite), kept
 * in memory so the restore record can store it; null where no body exists (tests that build routes by hand).
 */
class ParsedRoute(val plan: GuidancePlan, val native: NativeRoute, val source: ByteArray? = null)

/** Marker for the navigation engine's route object; keeps pure code free of Ferrostar types. */
interface NativeRoute {
    val stepCount: Int
}

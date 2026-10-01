package mn.navmn.app.engine

import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import mn.navmn.app.route.ParsedRoute

/** Injected time (ADR-0009 §4–5): monotonic for every rule, wall clock only for «Хүрэх цаг». */
interface Clock {
    fun elapsedMs(): Long
    fun wallMs(): Long
}

enum class Deviation { NONE, OFF_STEP_ON_ROUTE, COMPLETELY_OFF_ROUTE }

/**
 * ADR-0009 §10: the only Ferrostar-derived data the app uses (TripState → snapshot). Pure, so the core and its tests
 * have no Ferrostar types.
 */
data class NavSnapshot(
    /** Current step k = route.steps.size − remainingSteps.size; the upcoming manoeuvre is step k + 1. */
    val stepIndex: Int,
    val distanceToNextManeuver: Double,
    val distanceRemaining: Double,
    val durationRemaining: Double,
    val deviation: Deviation,
    val snapped: LatLon,
    val snappedCourseDeg: Double?,
    val complete: Boolean,
    /**
     * NAV-005-D9: false when this (good) fix is more than 50 m from the current step and was not caught up
     * ([StepCatchUp.offCurrentStep]), or (D8) it matches a catch-up target that is still pending the second fix. Its
     * distance, progress and snapped position are then not trustworthy: the core holds the last trusted snapshot, does
     * not evaluate the voice schedule and does not use it for arrival rule (b). [deviation] is still used.
     */
    val fixOnCurrentStep: Boolean = true,
)

/** One navigation session over one route (Ferrostar `NavigationSession` on the device). Single-threaded. */
interface Navigator : AutoCloseable {
    fun initial(fix: Fix): NavSnapshot
    fun update(fix: Fix): NavSnapshot
}

fun interface NavigatorFactory {
    fun create(route: ParsedRoute): Navigator
}

package mn.navmn.app.engine

import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import mn.navmn.app.route.NativeRoute
import mn.navmn.app.route.ParsedRoute
import mn.navmn.app.route.RouteParser
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.CourseOverGround
import uniffi.ferrostar.DeviationKind
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.NavState
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.NavigationSession
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.Speed
import uniffi.ferrostar.TripState
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.createNavigationSession
import uniffi.ferrostar.createOsrmResponseParser
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep
import java.time.Instant

/** Ferrostar's `Route` behind the pure [NativeRoute] marker. */
class FerrostarRoute(val route: Route) : NativeRoute {
    override val stepCount: Int get() = route.steps.size
}

/** Ferrostar's exported OSRM parser (polyline6), applied to the token-rewritten response (ADR-0009 §2, §3.1). */
class FerrostarRouteParser : RouteParser {
    private val parser by lazy { createOsrmResponseParser(6u) }

    override fun parse(rewrittenOsrmJson: ByteArray): NativeRoute {
        val routes = parser.parseResponse(rewrittenOsrmJson)
        return FerrostarRoute(routes.first())
    }
}

/**
 * ADR-0009 §1 `NavigationControllerConfig`. The deviation values are the story's (AC 41) and change only through the
 * BA; the step-advance distances may be tuned if a replay shows a late banner change (recorded in README). A jump
 * past a step end (GPS gap) is caught up by [StepCatchUp] (NAV-005-D1).
 */
object FerrostarConfig {
    const val WAYPOINT_RANGE_M = 30.0
    const val STEP_ENTRY_M = 30
    const val STEP_EXIT_M = 5
    const val ARRIVAL_STEP_M = 30
    const val MIN_ACCURACY_M = 25
    const val MAX_DEVIATION_M = 50.0

    fun create(): NavigationControllerConfig = NavigationControllerConfig(
        WaypointAdvanceMode.WaypointWithinRange(WAYPOINT_RANGE_M),
        stepAdvanceDistanceEntryAndExit(STEP_ENTRY_M.toUShort(), STEP_EXIT_M.toUShort(), MIN_ACCURACY_M.toUShort()),
        stepAdvanceDistanceToEndOfStep(ARRIVAL_STEP_M.toUShort(), MIN_ACCURACY_M.toUShort()),
        RouteDeviationTracking.StaticThreshold(MIN_ACCURACY_M.toUShort(), MAX_DEVIATION_M),
        CourseFiltering.SNAP_TO_ROUTE,
    )
}

/**
 * Drives Ferrostar's public `NavigationSession` synchronously (ADR-0009 §1): no `FerrostarCore`, no caching, no
 * recorder, no Ferrostar logger. Snapping, step advance, deviation and trip progress are Ferrostar's, unchanged.
 */
class FerrostarNavigator(route: FerrostarRoute) : Navigator {
    private val ferrostarRoute = route.route
    private val session: NavigationSession = createNavigationSession(ferrostarRoute, FerrostarConfig.create(), emptyList())
    private var state: NavState? = null

    override fun initial(fix: Fix): NavSnapshot {
        val s = session.getInitialState(userLocation(fix))
        catchUpGate.reset()
        lastFixOffStep = false
        state = s
        return snapshot(s, fix)
    }

    override fun update(fix: Fix): NavSnapshot {
        val prev = state ?: return initial(fix)
        val s = catchUp(session.updateUserLocation(userLocation(fix), prev), fix)
        state = s
        return snapshot(s, fix)
    }

    private val catchUpGate = StepCatchUp.Gate()

    /**
     * NAV-005-D9 / D8: the last fix was good and not caught up, but either > 50 m from the current step or a pending
     * catch-up target (past-the-end branch); its snapped position and distances are not trustworthy.
     */
    private var lastFixOffStep = false

    /**
     * NAV-005-D1: after a jump past the end of the current step (GPS gap over a junction), advance to the step the
     * fix is clearly on, through Ferrostar's public `advanceToNextStep` (which re-snaps the fix and resets the
     * step-advance condition). The rule is [StepCatchUp]; [StepCatchUp.Gate] keeps single outliers out (D10). A good
     * fix off the current step that is not caught up sets [lastFixOffStep] (D9).
     */
    private fun catchUp(s: NavState, fix: Fix): NavState {
        lastFixOffStep = false
        val good = fix.accuracyM <= StepCatchUp.MIN_ACCURACY_M
        val t = s.tripState as? TripState.Navigating
        if (t == null) {
            catchUpGate.decide(null, good, fix.elapsedMs)
            return s
        }
        // Lazy view: usually only the current step's geometry is converted.
        val remaining = object : AbstractList<List<LatLon>>() {
            override val size: Int get() = t.remainingSteps.size
            override fun get(index: Int): List<LatLon> = t.remainingSteps[index].geometry.map { LatLon(it.lat, it.lng) }
        }
        val n = StepCatchUp.stepsToAdvance(fix.latLon, fix.accuracyM, remaining)
        val current = ferrostarRoute.steps.size - t.remainingSteps.size
        if (!catchUpGate.decide(if (n == 0) null else current + n, good, fix.elapsedMs)) {
            // D9: off the current step; D8: a pending past-the-end match (branch b) is untrusted as well.
            lastFixOffStep = n > 0 || (remaining.isNotEmpty() && StepCatchUp.offCurrentStep(fix.latLon, fix.accuracyM, remaining[0]))
            return s
        }
        var next = s
        repeat(n) { next = session.advanceToNextStep(next) }
        stepCatchUps += n
        return next
    }

    /** Steps advanced by [catchUp] in this session (tests and the debug log only). */
    var stepCatchUps: Int = 0
        private set

    override fun close() {
        session.close()
    }

    private fun userLocation(fix: Fix): UserLocation {
        val course = fix.bearingDeg?.takeIf { it.isFinite() }?.let { b ->
            val deg = (((Math.round(b) % 360) + 360) % 360).toInt().toUShort()
            CourseOverGround(deg, fix.bearingAccuracyDeg?.takeIf { it.isFinite() && it >= 0 }?.let { Math.round(it).toInt().coerceAtMost(65535).toUShort() })
        }
        return UserLocation(
            GeographicCoordinate(fix.lat, fix.lon),
            if (fix.accuracyM.isFinite()) fix.accuracyM else 9_999.0,
            course,
            Instant.ofEpochMilli(fix.wallTimeMs),
            fix.speedMps?.takeIf { it.isFinite() && it >= 0 }?.let { Speed(it, null) },
        )
    }

    private fun snapshot(s: NavState, fix: Fix): NavSnapshot = when (val t = s.tripState) {
        is TripState.Navigating -> NavSnapshot(
            stepIndex = (ferrostarRoute.steps.size - t.remainingSteps.size).coerceAtLeast(0),
            distanceToNextManeuver = t.progress.distanceToNextManeuver,
            distanceRemaining = t.progress.distanceRemaining,
            durationRemaining = t.progress.durationRemaining,
            deviation = when (val d = t.deviation) {
                is RouteDeviation.NoDeviation -> Deviation.NONE
                is RouteDeviation.Deviation -> when (d.kind) {
                    is DeviationKind.CompletelyOffRoute -> Deviation.COMPLETELY_OFF_ROUTE
                    is DeviationKind.OffStepOnRoute -> Deviation.OFF_STEP_ON_ROUTE
                }
            },
            snapped = LatLon(t.snappedUserLocation.coordinates.lat, t.snappedUserLocation.coordinates.lng),
            snappedCourseDeg = t.snappedUserLocation.courseOverGround?.degrees?.toDouble(),
            complete = false,
            fixOnCurrentStep = !lastFixOffStep,
        )
        is TripState.Complete -> NavSnapshot(
            stepIndex = (ferrostarRoute.steps.size - 1).coerceAtLeast(0),
            distanceToNextManeuver = 0.0,
            distanceRemaining = 0.0,
            durationRemaining = 0.0,
            deviation = Deviation.NONE,
            snapped = LatLon(t.userLocation.coordinates.lat, t.userLocation.coordinates.lng),
            snappedCourseDeg = fix.bearingDeg,
            complete = true,
        )
        is TripState.Idle -> NavSnapshot(
            stepIndex = 0,
            distanceToNextManeuver = ferrostarRoute.steps.firstOrNull()?.distance ?: 0.0,
            distanceRemaining = ferrostarRoute.distance,
            durationRemaining = ferrostarRoute.steps.sumOf { it.duration },
            deviation = Deviation.NONE,
            snapped = LatLon(fix.lat, fix.lon),
            snappedCourseDeg = fix.bearingDeg,
            complete = false,
        )
    }
}

/** Creates [FerrostarNavigator]s for parsed routes. */
class FerrostarNavigatorFactory : NavigatorFactory {
    override fun create(route: ParsedRoute): Navigator = FerrostarNavigator(route.native as FerrostarRoute)
}

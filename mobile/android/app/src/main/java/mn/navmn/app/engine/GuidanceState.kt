package mn.navmn.app.engine

import mn.navmn.app.geo.LatLon
import mn.navmn.app.instructions.KeyResult
import mn.navmn.app.reroute.RerouteSecondary
import mn.navmn.app.route.TravelMode

enum class GuidancePhase { NAVIGATING, OFF_ROUTE, ARRIVED, ENDED }

/** Banner content as language-neutral keys, so a language switch re-renders without any request (AC 60). */
sealed interface Banner {
    /** Manoeuvre variant (AC 21): the upcoming manoeuvre, its distance, the street after it, the Then strip. */
    data class Maneuver(
        val key: KeyResult,
        val distanceM: Double,
        val street: String,
        val then: KeyResult?,
        /** GPS lost: the distance is frozen and drawn dimmed (navigation-ux §2.2). */
        val stale: Boolean,
    ) : Banner

    /** Recalculating variant (AC 42, 47–50), never blank. */
    data class Rerouting(val secondary: RerouteSecondary?) : Banner

    /** Arrival variant (AC 55). */
    data class Arrival(val key: KeyResult, val street: String) : Banner
}

data class Puck(val position: LatLon, val bearingDeg: Double?, val stale: Boolean, val snapped: Boolean)

/** Trip progress (AC 22); frozen during GPS loss (AC 51). */
data class Progress(val distanceRemaining: Double, val durationRemaining: Double, val etaBaseWallMs: Long)

/** The trip being guided: the original destination and request options, reused for every reroute (AC 43). */
data class Trip(
    val destination: LatLon,
    val destinationName: String?,
    val mode: TravelMode,
    val avoidUnpaved: Boolean,
)

/** Everything the guidance screen, the map and the notification render. No coordinates leave the device. */
data class GuidanceState(
    val phase: GuidancePhase,
    val generation: Int,
    val banner: Banner,
    val progress: Progress,
    val puck: Puck?,
    val route: List<LatLon>,
    val trip: Trip,
    val gpsLost: Boolean,
    val gpsRestoredVisible: Boolean,
    val offline: Boolean,
    val voiceNoticeVisible: Boolean,
    val muted: Boolean,
    val speedMps: Double,
)

sealed interface GuidanceEvent {
    /** Arrival detected: stop location updates and the foreground service now (AC 55). */
    data object Arrived : GuidanceEvent

    /** Guidance ended («Дуусгах», swipe-away, «Хаах» after arrival). */
    data object Ended : GuidanceEvent
}

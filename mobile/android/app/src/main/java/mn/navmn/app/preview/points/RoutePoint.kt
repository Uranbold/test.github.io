package mn.navmn.app.preview.points

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Strings
import mn.navmn.app.location.Fix

/**
 * NAV-018 (ADR-0015 §2): one point type for the start and the destination of the Android route preview, so a swap is a
 * pure exchange of two values. [point] goes into the route body unrounded (6 decimals, `RouteBody`).
 */
sealed interface RoutePoint {
    val point: LatLon

    /**
     * «Миний байршил»: the device position, frozen when the point is set (AC 10) and never refreshed, also after a swap
     * (AC 11). Created only from a good fix ≤ 60 s old ([PointRules.myLocation]).
     */
    data class MyLocation(val fix: Fix) : RoutePoint {
        override val point: LatLon get() = fix.latLon
    }

    /** A search result; [name] is the text shown in the list, stored once and not re-localised (AC 33, D11). */
    data class Place(override val point: LatLon, val name: String) : RoutePoint

    /** A map point from the coordinate card («Сонгосон цэг»); never takes the `reverse` name (NAV-011 AC 9). */
    data class MapPoint(override val point: LatLon) : RoutePoint

    /**
     * A typed coordinate, used only once the D137 change gives the search code a coordinate outcome (AC 5, second
     * bullet). The fields have no coordinate parser of their own.
     */
    data class TypedCoordinate(override val point: LatLon) : RoutePoint
}

/** Which of the two points a field, an editor or a card button acts on. */
enum class PointSide { ORIGIN, DESTINATION }

/** Pure NAV-018 point rules (ADR-0015 §2–3). */
object PointRules {
    /** AC 9 / NAV-005 AC 7: start and destination within 10 m (haversine) → «Эхлэх цэг, очих газар ижил байна», 0 requests. */
    const val SAME_POINT_M = 10.0

    /**
     * «Миний байршил» from [fix] when it is a good fix (≤ 25 m) and ≤ 60 s old at [nowElapsedMs] (story Terms
     * "Device start"); otherwise null (the NAV-005 failure flow runs, AC 3).
     */
    fun myLocation(fix: Fix, nowElapsedMs: Long): RoutePoint.MyLocation? =
        if (fix.isGood(nowElapsedMs, Fix.PREVIEW_FRESH_MS)) RoutePoint.MyLocation(fix) else null

    /** A start that is set and is not «Миний байршил» (search result, map point, typed coordinate, or a swapped point). */
    fun isChosenStart(origin: RoutePoint?): Boolean = origin != null && origin !is RoutePoint.MyLocation

    /**
     * AC 15 start gate (Open question 1, default (a)): «Эхлэх» only with a route and without a chosen start. In the app a
     * route only exists after a request, which needs a set start, so this is "route and a device start". A start that
     * is still being resolved (null) is the pending device start of AC 1.
     */
    fun canStart(hasRoute: Boolean, origin: RoutePoint?): Boolean = hasRoute && !isChosenStart(origin)

    /** AC 15: O1 «Замчлал зөвхөн таны байршлаас эхэлнэ» next to the disabled «Эхлэх» once a route renders. */
    fun showStartHint(hasRoute: Boolean, origin: RoutePoint?): Boolean = hasRoute && isChosenStart(origin)

    fun samePoint(a: RoutePoint, b: RoutePoint): Boolean = Geo.distance(a.point, b.point) <= SAME_POINT_M

    /**
     * NAV-018 edge case (PO 2026-10-04, answer 5): «Миний байршил» was swapped to the destination, the user moved
     * more than [SAME_POINT_M] and then picks «Миний байршил» as the start. The destination keeps the old fix but turns
     * into a map point («Сонгосон цэг»), so the two fields never both read «Миний байршил». Within 10 m it stays as it
     * is (AC 9 then shows «Эхлэх цэг, очих газар ижил байна»). Any other combination returns [destination] unchanged.
     */
    fun destinationForStart(origin: RoutePoint, destination: RoutePoint): RoutePoint =
        if (origin is RoutePoint.MyLocation && destination is RoutePoint.MyLocation && !samePoint(origin, destination)) {
            RoutePoint.MapPoint(destination.point)
        } else {
            destination
        }

    /**
     * NAV-011 AC 16 / NAV-018 AC 8 camera fit markers (PO 2026-10-04, answer 7): the destination, the start and, with a
     * device start (or none yet), the live device position [me]. With a chosen start only the start and destination
     * markers are fitted; the live position is left out.
     */
    fun fitMarkers(origin: RoutePoint?, destination: RoutePoint, me: LatLon?): List<LatLon> =
        listOfNotNull(destination.point, origin?.point, me.takeUnless { isChosenStart(origin) })

    /**
     * The field text in the current UI language (AC 1, 4, 6, 33): resource-backed kinds are rendered at composition,
     * so a language switch changes them with 0 requests; a result name stays as returned.
     */
    fun label(p: RoutePoint, strings: Strings): String = when (p) {
        is RoutePoint.MyLocation -> strings[StringKey.MARKER_MY_LOCATION]
        is RoutePoint.Place -> p.name
        is RoutePoint.MapPoint, is RoutePoint.TypedCoordinate -> strings[StringKey.PLACE_SELECTED_POINT]
    }

    /** The stored display name (guidance `Trip`, NAV-012 restore): the result name, or null for «Сонгосон цэг». */
    fun storedName(p: RoutePoint): String? = (p as? RoutePoint.Place)?.name
}

/**
 * ADR-0015 §4: a location fix may set the start only for the attempt that asked for it. Each attempt (preview open, or
 * the «Миний байршил» option) takes a token; setting the start any other way ([cancel]) or a newer attempt makes every
 * older token stale, so a late fix (10 s wait, `onResume` from Settings) never overwrites a start the user chose.
 */
class OriginAttempts {
    private var current = 0
    private var active = false

    /** Starts a new attempt and returns its token; any older attempt becomes stale. */
    fun begin(): Int {
        active = true
        return ++current
    }

    /** True while [token] is the newest attempt and it was neither cancelled nor finished. */
    fun isCurrent(token: Int): Boolean = active && token == current

    /** Ends [token]'s attempt (fix applied or failed). Returns false (and does nothing) for a stale token. */
    fun finish(token: Int): Boolean {
        if (!isCurrent(token)) return false
        active = false
        return true
    }

    /** The start was set by the user (search, map point, swap) or the preview closed: every running attempt is stale. */
    fun cancel() {
        current++
        active = false
    }

    val running: Boolean get() = active
}

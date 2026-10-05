package mn.navmn.app.preview.points

import mn.navmn.app.geo.LatLon
import mn.navmn.app.preview.LocationProblem
import mn.navmn.app.route.ParsedRoute

/** The «Миний байршил» option card in the start editor (screen spec › Option card; AC 3). */
sealed interface MyLocationOption {
    data object Idle : MyLocationOption
    /** A fix is awaited (≤ 10 s, NAV-005 F3): progress, «Ачаалж байна…» after 300 ms. */
    data object Waiting : MyLocationOption
    /** The NAV-005 message for the case, inside the card; the start is unchanged, 0 route requests (Design note 6). */
    data class Failed(val problem: LocationProblem) : MyLocationOption
}

/** The point editor (screen spec Q5): which point is edited; [session] changes on every open (editor text reset). */
data class PointEditorState(
    val side: PointSide,
    val session: Int,
    val myLocation: MyLocationOption = MyLocationOption.Idle,
)

/**
 * The activated turn-list row (AC 22): [route] identifies the selected route it belongs to, so a new response or
 * another selection clears it without extra state (screen spec › Turn list: "activated row and manoeuvre point
 * cleared"). [seq] changes on every tap (one camera move per tap).
 */
data class StepFocus(val route: ParsedRoute, val index: Int, val location: LatLon, val seq: Int, val collapse: Boolean)

/** NAV-018 UI state held by the ViewModel (memory only, ADR-0015 §10: never saved, never logged). */
data class PointsUi(
    val editor: PointEditorState? = null,
    val step: StepFocus? = null,
)

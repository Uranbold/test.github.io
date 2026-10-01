package mn.navmn.app.permission

import mn.navmn.app.preview.LocationProblem

/** Platform permission state, read by the Activity (ContextCompat / shouldShowRequestPermissionRationale). */
enum class PermissionStatus { NOT_ASKED, PRECISE, APPROXIMATE, DENIED, DENIED_PERMANENT }

/** What needs the location (AC 8): the origin «Миний байршил», «Эхлэх», the my-location control. */
enum class LocationAction { PREVIEW_ORIGIN, START_GUIDANCE, MY_LOCATION }

/**
 * NAV-005 AC 8–12 decision (pure): rationale before the OS dialog; precise needed for routing and guidance
 * (approximate → «Нарийвчилсан байршлыг зөвшөөрнө үү»); denied → message with «Тохиргоо нээх»; after a permanent
 * denial the OS dialog is not requested again; location services off → their message.
 */
object LocationAccess {
    sealed interface Decision {
        data object Proceed : Decision
        data object ShowRationale : Decision
        data class Problem(val problem: LocationProblem) : Decision
    }

    fun decide(action: LocationAction, status: PermissionStatus, servicesOn: Boolean): Decision = when (status) {
        PermissionStatus.PRECISE -> if (servicesOn) Decision.Proceed else Decision.Problem(LocationProblem.SERVICES_OFF)
        PermissionStatus.APPROXIMATE -> when {
            action != LocationAction.MY_LOCATION -> Decision.Problem(LocationProblem.APPROXIMATE)
            servicesOn -> Decision.Proceed
            else -> Decision.Problem(LocationProblem.SERVICES_OFF)
        }
        PermissionStatus.NOT_ASKED, PermissionStatus.DENIED -> Decision.ShowRationale
        PermissionStatus.DENIED_PERMANENT -> Decision.Problem(LocationProblem.DENIED)
    }

    /** After the OS dialog returns. */
    fun afterRequest(action: LocationAction, status: PermissionStatus, servicesOn: Boolean): Decision = when (status) {
        PermissionStatus.PRECISE, PermissionStatus.APPROXIMATE -> decide(action, status, servicesOn)
        else -> Decision.Problem(LocationProblem.DENIED)
    }
}

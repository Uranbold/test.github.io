package mn.navmn.app.support

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.instructions.ManeuverInput
import mn.navmn.app.route.GuidancePlan
import mn.navmn.app.route.PlanStep

/** Synthetic plans for schedule tests: a straight line north with manoeuvres at the given step lengths. */
object Plans {
    fun plan(vararg steps: Pair<ManeuverInput, Double>, generation: Int = 0): GuidancePlan {
        val start = LatLon(47.9, 106.9)
        var along = 0.0
        val out = steps.map { (m, len) ->
            val s = PlanStep.of(m, Geo.offset(start, 0.0, along), "Гудамж", len, len / 14.0)
            along += len
            s
        }
        val geometry = listOf(start, Geo.offset(start, 0.0, maxOf(along, 1.0)))
        return GuidancePlan(generation, out, along, along / 14.0, geometry, listOf(0.0, 0.0))
    }

    val depart = ManeuverInput("depart", bearingAfter = 0.0)
    val left = ManeuverInput("turn", "left")
    val right = ManeuverInput("turn", "right")
    val arrive = ManeuverInput("arrive")
    val exitRb = ManeuverInput("exit roundabout", "right")
    val rb2 = ManeuverInput("roundabout", "right", 2.0)
}

package mn.navmn.app.route.alternatives

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.route.OsrmPlanParser
import mn.navmn.app.route.ParsedRoute
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor

/**
 * NAV-011 AC 15, 19 (ADR-0012 §5.2–5.3): a preview response with k routes (1–3) becomes k [ParsedRoute]s, each built
 * from a **single-route slice** of the response (identical JSON except `routes = [routes[s]]`) through the unchanged
 * NAV-005 pipeline (GuidancePlan → token rewrite → Ferrostar OSRM parser). Ferrostar never sees more than one route,
 * tokens and the step mapping are unchanged, and «Эхлэх» hands the selected slice to guidance with 0 requests.
 *
 * Every slice is parsed when the response arrives (not on selection), so selecting a route is a pure state change
 * (AC 17: ≤ 200 ms, 0 requests) and a parse failure is known up front: route 1 failing → `BadResponse` (as NAV-005);
 * an alternative failing → that alternative is left out and the others stay (ADR-0012 §5.3 fallback).
 */
object PreviewRoutes {
    const val MAX_ROUTES = 3

    /** The response with `routes` = [routes[index]]; `code`, `waypoints` and every other top-level field copied. */
    fun slice(root: JsonObject, index: Int): ByteArray {
        val routes = root["routes"] as JsonArray
        return JsonObject(root + ("routes" to JsonArray(listOf(routes[index])))).toString().encodeToByteArray()
    }

    fun process(processor: RouteProcessor, body: ByteArray, generation: Int): RouteOutcome {
        val root = OsrmPlanParser.parseJson(body) ?: return RouteOutcome.BadResponse
        val routes = root["routes"] as? JsonArray ?: return RouteOutcome.BadResponse
        if (routes.size <= 1) return processor.process(body, generation)
        val parsed = ArrayList<ParsedRoute>()
        for (i in 0 until minOf(routes.size, MAX_ROUTES)) {
            val out = processor.process(slice(root, i), generation)
            if (out is RouteOutcome.Ok) parsed += out.route else if (i == 0) return out
        }
        return RouteOutcome.Ok(parsed[0], parsed)
    }
}

/**
 * map-style §7.6 / NAV-011 AC 17 hit test (pure): the routes `queryRenderedFeatures` found in the 48 × 48 dp box
 * around a tap. Any unselected route wins (overlap rule); with two unselected routes, the one whose geometry is nearest
 * to the tap. Only the selected route, or nothing → null (no change, the tap falls through).
 */
object AlternativeHitTest {
    fun pick(tap: LatLon, hitIndices: Collection<Int>, selected: Int, geometries: List<List<LatLon>>): Int? =
        hitIndices.asSequence()
            .distinct()
            .filter { it != selected && it in geometries.indices }
            .minByOrNull { Geo.distanceToLine(tap, geometries[it]) }
}

package mn.navmn.app.route

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.instructions.ManeuverInput

/** Reads the subset of the OSRM response the app needs (openapi OsrmRouteResponse) into a [GuidancePlan]. */
object OsrmPlanParser {
    val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parseJson(bytes: ByteArray): JsonObject? = runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** null when the body is not a usable OSRM `Ok` response with one route (→ `BadResponse`). */
    fun plan(root: JsonObject, generation: Int): GuidancePlan? {
        if (root["code"].str() != "Ok") return null
        val route = root["routes"].arr()?.firstOrNull().obj() ?: return null
        val geometry = Geo.decodePolyline(route["geometry"].str() ?: return null)
        if (geometry.size < 2) return null
        val steps = ArrayList<PlanStep>()
        for (leg in route["legs"].arr() ?: return null) {
            for (s in leg.obj()?.get("steps").arr() ?: return null) {
                val step = s.obj() ?: return null
                val m = step["maneuver"].obj() ?: return null
                val type = m["type"].str() ?: return null
                val loc = m["location"].arr()
                val location = if (loc != null && loc.size >= 2) LatLon(loc[1].num() ?: return null, loc[0].num() ?: return null) else return null
                steps += PlanStep.of(
                    maneuver = ManeuverInput(type, m["modifier"].str(), m["exit"].num(), m["bearing_after"].num()),
                    location = location,
                    name = step["name"].str(),
                    distance = step["distance"].num() ?: 0.0,
                    duration = step["duration"].num() ?: 0.0,
                )
            }
        }
        if (steps.isEmpty()) return null
        val snaps = root["waypoints"].arr()?.map { it.obj()?.get("distance").num() ?: 0.0 } ?: emptyList()
        return GuidancePlan(
            generation = generation,
            steps = steps,
            distance = route["distance"].num() ?: Geo.length(geometry),
            duration = route["duration"].num() ?: 0.0,
            geometry = geometry,
            snapDistances = snaps,
        )
    }

    /** 400 body classification: OsrmError `code` or ValhallaError `error_code` (openapi components). */
    fun errorCode(bytes: ByteArray): Pair<String?, Int?> {
        val root = parseJson(bytes) ?: return null to null
        return root["code"].str() to (root["error_code"] as? JsonPrimitive)?.intOrNull
    }
}

/**
 * ADR-0009 §3.1: before Ferrostar parses the response, every Valhalla text field becomes an opaque token tied to the
 * route generation, and `voiceInstructions` are emptied. Valhalla text therefore never enters the navigation model
 * and cannot reach the screen, the notification or the voice (AC 26–27).
 *  - `maneuver.instruction` → `nav:<gen>:m:<step>`
 *  - `bannerInstructions[b].primary|secondary|sub.text` and their `components[].text` → `nav:<gen>:b:<step>:<b>`
 *  - `voiceInstructions` → `[]`
 *  - top-level `warnings` (Valhalla developer text, unused) → removed
 */
object TextRewrite {
    fun token(generation: Int, step: Int) = "nav:$generation:m:$step"
    fun bannerToken(generation: Int, step: Int, banner: Int) = "nav:$generation:b:$step:$banner"

    fun rewrite(root: JsonObject, generation: Int): JsonObject {
        var stepIndex = 0
        val routes = root["routes"]?.jsonArray?.map { r ->
            stepIndex = 0
            val route = r.jsonObject
            val legs = route["legs"]?.jsonArray?.map { l ->
                val leg = l.jsonObject
                val steps = leg["steps"]?.jsonArray?.map { s ->
                    val out = rewriteStep(s.jsonObject, generation, stepIndex)
                    stepIndex++
                    out
                }
                if (steps == null) leg else JsonObject(leg + ("steps" to JsonArray(steps)))
            }
            if (legs == null) route else JsonObject(route + ("legs" to JsonArray(legs)))
        }
        val out = if (routes == null) root.toMutableMap() else (root + ("routes" to JsonArray(routes))).toMutableMap()
        out.remove("warnings")
        return JsonObject(out)
    }

    private fun rewriteStep(step: JsonObject, gen: Int, i: Int): JsonObject {
        val out = step.toMutableMap()
        step["maneuver"]?.jsonObject?.let { m ->
            out["maneuver"] = if (m.containsKey("instruction")) JsonObject(m + ("instruction" to JsonPrimitive(token(gen, i)))) else m
        }
        step["bannerInstructions"]?.jsonArray?.let { banners ->
            out["bannerInstructions"] = JsonArray(
                banners.mapIndexed { b, el ->
                    val banner = el.jsonObject.toMutableMap()
                    for (part in listOf("primary", "secondary", "sub")) {
                        val content = (banner[part] as? JsonObject) ?: continue
                        banner[part] = rewriteContent(content, bannerToken(gen, i, b))
                    }
                    JsonObject(banner)
                },
            )
        }
        if (step.containsKey("voiceInstructions")) out["voiceInstructions"] = JsonArray(emptyList())
        return JsonObject(out)
    }

    private fun rewriteContent(content: JsonObject, token: String): JsonObject {
        val out = content.toMutableMap()
        if (content.containsKey("text")) out["text"] = JsonPrimitive(token)
        content["components"]?.let { comps ->
            out["components"] = JsonArray(
                comps.jsonArray.map { c ->
                    val comp = c.jsonObject
                    if (comp.containsKey("text")) JsonObject(comp + ("text" to JsonPrimitive(token))) else comp
                },
            )
        }
        return JsonObject(out)
    }
}

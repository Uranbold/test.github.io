package mn.navmn.app.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mn.navmn.app.route.NativeRoute
import mn.navmn.app.route.RouteParser

/** Counts OSRM steps like Ferrostar's parser (legs flattened) and keeps the bytes it was given. */
class FakeRouteParser : RouteParser {
    val parsed = ArrayList<String>()

    override fun parse(rewrittenOsrmJson: ByteArray): NativeRoute {
        val text = rewrittenOsrmJson.decodeToString()
        parsed += text
        val route = Json.parseToJsonElement(text).jsonObject["routes"]!!.jsonArray[0].jsonObject
        val n = route["legs"]!!.jsonArray.sumOf { it.jsonObject["steps"]!!.jsonArray.size }
        return object : NativeRoute {
            override val stepCount = n
        }
    }
}

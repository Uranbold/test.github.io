package mn.navmn.app.routing.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * NAV-021 R3 (ADR-0017 Amendment A1 item 5): the on-device engine config = the pinned AAR's `default.json` with
 * exactly these overrides, nothing else (the same values backend Gate 2 asserts, `nav_pack.py engine_config`):
 * `mjolnir.tile_extract` = the installed tar; `mjolnir.max_cache_size` = 33554432; `mjolnir.tile_dir`,
 * `traffic_extract`, `admin`, `timezone`, `landmarks` = "". `service_limits` is not overridden (it already equals the
 * server's values, A1 F1). A changed override is an ADR note.
 */
object OnDeviceConfig {
    /** The resource inside the pinned `valhalla-mobile` AAR's classes.jar. */
    const val DEFAULT_RESOURCE = "/com/valhalla/valhalla/default.json"
    const val MAX_CACHE_SIZE = 33_554_432L
    val EMPTIED_KEYS = listOf("tile_dir", "traffic_extract", "admin", "timezone", "landmarks")

    fun defaultJson(): String =
        requireNotNull(OnDeviceConfig::class.java.getResourceAsStream(DEFAULT_RESOURCE)) { "valhalla-mobile default.json missing" }
            .use { it.readBytes().decodeToString() }

    fun build(defaultJson: String, tarPath: String): String {
        val root = Json.parseToJsonElement(defaultJson).jsonObject
        val mjolnir = root.getValue("mjolnir").jsonObject.toMutableMap()
        mjolnir["tile_extract"] = JsonPrimitive(tarPath)
        mjolnir["max_cache_size"] = JsonPrimitive(MAX_CACHE_SIZE)
        for (k in EMPTIED_KEYS) mjolnir[k] = JsonPrimitive("")
        val out = root.toMutableMap()
        out["mjolnir"] = JsonObject(mjolnir)
        return JsonObject(out).toString()
    }
}

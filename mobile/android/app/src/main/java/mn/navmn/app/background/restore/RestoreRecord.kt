package mn.navmn.app.background.restore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * NAV-012 AC 16 / ADR-0013 §3.1: the only trip data the app ever stores, and only between «Эхлэх» and the end of the
 * restore window. `meta.json` holds exactly these fields; the route bytes live next to it in `route.bin`. There is no
 * origin, no position, no step index, no search text and no request body.
 */
@Serializable
data class RestoreDestination(
    val lat: Double,
    val lon: Double,
    /** The text the arrival panel shows (null = the «Сонгосон цэг» resource). */
    val text: String? = null,
)

@Serializable
data class RestoreMeta(
    val schema: Int,
    val destination: RestoreDestination,
    /** Valhalla costing of the route being followed (`auto`, `pedestrian`, `bicycle`). */
    val costing: String,
    val avoidUnpaved: Boolean,
    /** Route language tag of the route being followed (`mn` / `en`, [mn.navmn.app.i18n.Lang.tag]). */
    val language: String,
    val startedAtWallMs: Long,
    val heartbeatWallMs: Long,
    /** SHA-256 (hex) of `route.bin`; null when no route is stored (story Open question 2 (b)). */
    val routeSha256: String? = null,
    /** Wall times of earlier restores of this record (AC 24 loop limit). */
    val restoresWallMs: List<Long> = emptyList(),
) {
    companion object {
        const val SCHEMA = 1
    }
}

/** The JSON codec of `meta.json`. Unknown fields or an unknown schema make the record unreadable (AC 24). */
object RestoreCodec {
    private val json = Json { encodeDefaults = true }

    fun encode(meta: RestoreMeta): ByteArray = json.encodeToString(RestoreMeta.serializer(), meta).encodeToByteArray()

    /** null when the bytes are not a schema-[RestoreMeta.SCHEMA] record. Never throws. */
    fun decode(bytes: ByteArray): RestoreMeta? = runCatching {
        val root = json.parseToJsonElement(bytes.decodeToString()) as JsonObject
        if (root["schema"]?.jsonPrimitive?.int != RestoreMeta.SCHEMA) return null
        json.decodeFromJsonElement(RestoreMeta.serializer(), root)
    }.getOrNull()

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

package mn.navmn.app.pack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** The three kinds of the `mn` pack (ADR-0017 §1). The order is the download order (small weekly files first). */
enum class PackKind(val wire: String, val installedName: String) {
    ROUTING("routing", "routing.tar"),
    SEARCH("search", "search.sqlite"),
    TILES("tiles", "basemap.pmtiles"),
    ;

    companion object {
        fun ofWire(s: String?): PackKind? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * `OfflinePackManifest` (openapi 0.6.1). Strictly typed against the schema's required fields; unknown fields are ignored
 * (the schema allows additional properties). Never stored on disk: `self_test.route` holds coordinates (AC 43).
 */
@Serializable
data class PackManifest(
    @SerialName("pack_schema") val packSchema: Int,
    val region: String,
    @SerialName("pack_version") val packVersion: String,
    @SerialName("published_at") val publishedAt: String,
    val attribution: String,
    val licence: PackLicence,
    val files: List<PackFile>,
    @SerialName("total_bytes") val totalBytes: Long,
    @SerialName("total_download_bytes") val totalDownloadBytes: Long,
    @SerialName("self_test") val selfTest: PackSelfTest? = null,
) {
    fun file(kind: PackKind): PackFile? = files.firstOrNull { it.kind == kind.wire }
}

@Serializable
data class PackLicence(val name: String, val url: String, @SerialName("method_url") val methodUrl: String)

/** `OfflinePackFile`: one file of the pack. [format] is kept as raw JSON (kind-specific, additional properties). */
@Serializable
data class PackFile(
    val kind: String,
    val version: String,
    val path: String,
    val encoding: String,
    @SerialName("download_bytes") val downloadBytes: Long,
    @SerialName("download_sha256") val downloadSha256: String,
    val bytes: Long,
    val sha256: String,
    @SerialName("data_timestamp") val dataTimestamp: String,
    val format: JsonObject? = null,
) {
    val packKind: PackKind? get() = PackKind.ofWire(kind)

    /** `routing.format.graph_builder` (checked against the NAV-021 allow-list). */
    val graphBuilder: String? get() = (format?.get("graph_builder") as? JsonPrimitive)?.contentOrNull
    val searchSchema: Int? get() = (format?.get("search_schema") as? JsonPrimitive)?.intOrNull
    val pmtilesVersion: Int? get() = (format?.get("pmtiles") as? JsonPrimitive)?.intOrNull

    /** The installed file name inside `packs/<version>/` (the path without `.gz`). */
    val installedName: String get() = path.substringAfterLast('/').removeSuffix(".gz")
}

@Serializable
data class PackSelfTest(val route: SelfTestRoute? = null, val search: SelfTestSearch? = null)

@Serializable
data class SelfTestRoute(val from: PackLatLon, val to: PackLatLon, val costing: String)

@Serializable
data class PackLatLon(val lat: Double, val lon: Double)

@Serializable
data class SelfTestSearch(val q: String)

object ManifestParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** Throws [ManifestException] for anything that is not a schema-valid manifest of region `mn`. */
    fun parse(text: String): PackManifest {
        val m = try {
            json.decodeFromString(PackManifest.serializer(), text)
        } catch (e: Exception) {
            throw ManifestException("manifest does not parse")
        }
        if (m.region != PackRules.REGION) throw ManifestException("unexpected region")
        if (!PackRules.SLOT_ID.matches(m.packVersion)) throw ManifestException("bad pack_version")
        for (f in m.files) {
            if (!PackRules.SLOT_ID.matches(f.version)) throw ManifestException("bad file version")
            if (!PackRules.FILE_PATH.matches(f.path) || !f.path.startsWith(f.version + "/")) throw ManifestException("bad file path")
            if (!PackRules.SHA256.matches(f.sha256) || !PackRules.SHA256.matches(f.downloadSha256)) throw ManifestException("bad checksum")
            if (f.bytes < 1 || f.downloadBytes < 1) throw ManifestException("bad size")
        }
        return m
    }
}

class ManifestException(message: String) : Exception(message)

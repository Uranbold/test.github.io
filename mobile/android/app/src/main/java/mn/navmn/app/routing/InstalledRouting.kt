package mn.navmn.app.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import mn.navmn.app.log.DebugLog
import java.io.File

/**
 * One installed routing file (NAV-021 Terms "Routing file"): the `routing` entry of `noBackupFilesDir/packs/active.json`
 * (NAV-022 task file §1). [version] is the file's slot ID, [tar] the absolute path of `routing.tar`.
 */
data class InstalledRouting(val version: String, val tar: File, val graphBuilder: String) {
    /** One engine per routing-file version (ADR-0017 A1 item 5, NAV-021 R3). */
    val key: String get() = "$version|${tar.absolutePath}"
}

/** Where the on-device engine finds its graph. NAV-022 installs packs; until then only debug provisioning writes. */
fun interface InstalledRoutingSource {
    /** The active, compatible routing file, or null (no pack, not compatible, file missing). Cheap; called per request. */
    fun current(): InstalledRouting?
}

/**
 * NAV-021 AC 29 / R11 (ADR-0017 §4): the compiled-in `graph_builder` values that Gate 1 verified with the shipped
 * `valhalla-mobile`. A value enters this list only through a Gate 1 pass (AC 33). NAV-022 uses the same list.
 */
object GraphBuilderAllowList {
    val VALUES: Set<String> = setOf("valhalla 3.9.0")

    fun allows(graphBuilder: String?): Boolean = graphBuilder != null && graphBuilder in VALUES
}

/**
 * Reads the `routing` kind of `packs/active.json` in the NAV-022 format (R4). Inert when the file is missing: returns
 * null, and the app behaves exactly as without offline routing (AC 13, 30). Parsed again only when the file's size or
 * modification time changed. A routing file outside the allow-list is refused with one log line without coordinates
 * (AC 29). Paths must stay inside `packs/`.
 */
class PackFiles(
    private val packsDir: File,
    private val log: DebugLog = DebugLog.NONE,
    private val allowList: (String?) -> Boolean = GraphBuilderAllowList::allows,
) : InstalledRoutingSource {
    private val active = File(packsDir, ACTIVE_JSON)
    private var stamp: Pair<Long, Long>? = null
    private var cached: InstalledRouting? = null

    @Synchronized
    override fun current(): InstalledRouting? {
        if (!active.isFile) {
            stamp = null
            cached = null
            return null
        }
        val s = active.lastModified() to active.length()
        if (s != stamp) {
            stamp = s
            cached = runCatching { parse(active.readText()) }.getOrNull()
        }
        // The tar can disappear under a cached entry (NAV-022 deletes old versions after the last consumer released them).
        return cached?.takeIf { it.tar.isFile }
    }

    /** Pure parse of the `routing` entry (null when missing, malformed, refused or outside `packs/`). */
    fun parse(json: String): InstalledRouting? {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: return null
        if ((root["schema"] as? JsonPrimitive)?.intOrNull != SCHEMA) return null
        val files = root["files"] as? JsonObject ?: return null
        val routing = files["routing"] as? JsonObject ?: return null
        val version = (routing["version"] as? JsonPrimitive)?.contentOrNull ?: return null
        val path = (routing["path"] as? JsonPrimitive)?.contentOrNull ?: return null
        val builder = ((routing["format"] as? JsonObject)?.get("graph_builder") as? JsonPrimitive)?.contentOrNull
        if (!VERSION.matches(version)) return null
        if (!allowList(builder)) {
            log.d("routing file refused: graph_builder not on the allow-list")
            return null
        }
        val tar = safeResolve(path) ?: return null
        return InstalledRouting(version, tar, builder!!)
    }

    private fun safeResolve(relative: String): File? {
        if (relative.isEmpty() || relative.startsWith("/") || relative.split('/').any { it == ".." || it.isEmpty() }) return null
        val base = packsDir.canonicalFile
        val f = File(base, relative).canonicalFile
        return f.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    companion object {
        const val ACTIVE_JSON = "active.json"
        const val SCHEMA = 1
        const val PACKS_DIR = "packs"
        val VERSION = Regex("^[0-9]{8}T[0-9]{6}Z$")
    }
}

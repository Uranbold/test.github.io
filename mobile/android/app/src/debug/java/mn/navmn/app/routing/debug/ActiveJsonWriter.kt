package mn.navmn.app.routing.debug

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mn.navmn.app.routing.GraphBuilderAllowList
import mn.navmn.app.routing.PackFiles
import java.io.File

/**
 * NAV-021 R4 (debug only): writes the `routing` entry of `packs/active.json` in the NAV-022 task file §1 format for a
 * routing file the developer copied to `packs/<version>/routing.tar`. Other kinds already in the file are kept. Written
 * to a temp file, then renamed (the NAV-022 commit point). NAV-022's `PackManager` replaces this writer; the format
 * stays. The file is not hashed here (`sha256` is left empty: a debug file is never "verified").
 */
class ActiveJsonWriter(private val packsDir: File) {
    sealed interface Result {
        data class Ok(val version: String) : Result
        data class Refused(val reason: String) : Result
    }

    fun provision(version: String, graphBuilder: String): Result {
        if (!PackFiles.VERSION.matches(version)) return Result.Refused("version must look like 20261004T193412Z")
        if (!GraphBuilderAllowList.allows(graphBuilder)) return Result.Refused("graph_builder not on the allow-list")
        val tar = File(packsDir, "$version/$TAR")
        if (!tar.isFile) return Result.Refused("missing packs/$version/$TAR")
        val routing = JsonObject(
            mapOf(
                "version" to JsonPrimitive(version),
                "path" to JsonPrimitive("$version/$TAR"),
                "bytes" to JsonPrimitive(tar.length()),
                "sha256" to JsonPrimitive(""),
                "data_timestamp" to JsonPrimitive(""),
                "format" to JsonObject(mapOf("graph_builder" to JsonPrimitive(graphBuilder))),
            ),
        )
        write(files() + ("routing" to routing))
        return Result.Ok(version)
    }

    fun removeRouting() {
        val f = files()
        if ("routing" in f) write(f - "routing")
    }

    private fun files(): Map<String, JsonObject> {
        val active = File(packsDir, PackFiles.ACTIVE_JSON)
        if (!active.isFile) return emptyMap()
        val root = runCatching { Json.parseToJsonElement(active.readText()) as? JsonObject }.getOrNull() ?: return emptyMap()
        val files = root["files"] as? JsonObject ?: return emptyMap()
        return files.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.toMap()
    }

    private fun write(files: Map<String, JsonObject>) {
        packsDir.mkdirs()
        val root = JsonObject(
            mapOf(
                "schema" to JsonPrimitive(PackFiles.SCHEMA),
                "region" to JsonPrimitive("mn"),
                "manifest_etag" to JsonPrimitive(""),
                "files" to JsonObject(files),
            ),
        )
        val tmp = File(packsDir, PackFiles.ACTIVE_JSON + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(File(packsDir, PackFiles.ACTIVE_JSON))) error("rename failed")
    }

    companion object {
        const val TAR = "routing.tar"
    }
}

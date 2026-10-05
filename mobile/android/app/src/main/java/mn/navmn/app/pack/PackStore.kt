package mn.navmn.app.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import mn.navmn.app.log.DebugLog
import mn.navmn.app.routing.PackFiles
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

/** A verified, decompressed file waiting in the staging area for the atomic install. */
data class StagedFile(val manifestFile: PackFile, val raw: File)

/**
 * NAV-022 P2 (task file §1): the on-device pack store under `noBackupFilesDir/packs/` (no backup, no storage
 * permission, AC 20).
 *
 * Layout:
 * - `active.json`: the commit point; the installed version of each kind in the format NAV-021's [PackFiles] reads.
 * - `<fileVersion>/<file>`: installed files (`basemap.pmtiles`, `routing.tar`, `search.sqlite`).
 * - `<fileVersion>.partial/`: the install step only (verified files moved in, then renamed); deleted at every start.
 * - `.dl/<fileVersion>/`: downloads (`<file>.gz`, `<file>.gz.validator`) and verified raw files waiting for the
 *   install. Kept across process deaths so a download resumes (AC 14); deleted on cancel and delete.
 *
 * Files not referenced by `active.json` are garbage: deleted at the next start, or when no consumer holds them.
 */
class PackStore(
    val packsDir: File,
    private val log: DebugLog = DebugLog.NONE,
    /** NAV-022 review M3: flushes `active.json.tmp` to storage before the rename (tests record the order). */
    private val fsync: (FileDescriptor) -> Unit = { it.sync() },
) {
    private val active = File(packsDir, PackFiles.ACTIVE_JSON)
    private val activeTmp = File(packsDir, PackFiles.ACTIVE_JSON + ".tmp")
    val stagingRoot = File(packsDir, STAGING)

    @Synchronized
    fun installed(): InstalledPack {
        if (!active.isFile) return InstalledPack.NONE
        val parsed = runCatching { parse(active.readText()) }.getOrNull() ?: return InstalledPack.NONE
        // A referenced file that is gone (manual deletion, storage cleaner) is not installed.
        return parsed.copy(files = parsed.files.filterValues { resolve(it.path)?.isFile == true })
    }

    /** The absolute file of an installed entry (null for a path outside `packs/`). */
    fun fileOf(f: InstalledFile): File? = resolve(f.path)

    fun stagingDir(version: String): File = File(stagingRoot, version)

    /**
     * The atomic install (AC 18, task file §1 install rule). Verified files move into `<version>.partial/`, which is
     * renamed to `<version>/`; when that directory already exists, each file is renamed into it. `active.json` (temp
     * file + rename) is the commit point; kinds not in [staged] keep their entries.
     */
    @Synchronized
    fun install(staged: List<StagedFile>, manifestEtag: String?, licenceUrl: String?): InstalledPack {
        val before = installed()
        val entries = before.files.toMutableMap()
        for ((version, group) in staged.groupBy { it.manifestFile.version }) {
            val target = File(packsDir, version)
            val partial = File(packsDir, "$version$PARTIAL")
            partial.deleteRecursively()
            if (!partial.mkdirs()) throw IOException("cannot create the partial directory")
            for (s in group) move(s.raw, File(partial, s.manifestFile.installedName))
            if (!target.exists()) {
                if (!partial.renameTo(target)) throw IOException("rename of the partial directory failed")
            } else {
                for (s in group) move(File(partial, s.manifestFile.installedName), File(target, s.manifestFile.installedName))
                partial.deleteRecursively()
            }
            for (s in group) {
                val f = s.manifestFile
                val kind = f.packKind ?: continue
                entries[kind] = InstalledFile(
                    kind = kind,
                    version = f.version,
                    path = "$version/${f.installedName}",
                    bytes = f.bytes,
                    sha256 = f.sha256,
                    dataTimestamp = f.dataTimestamp,
                    graphBuilder = f.graphBuilder,
                    searchSchema = f.searchSchema,
                    pmtiles = f.pmtilesVersion,
                    minzoom = (f.format?.get("minzoom") as? JsonPrimitive)?.intOrNull,
                    maxzoom = (f.format?.get("maxzoom") as? JsonPrimitive)?.intOrNull,
                )
            }
        }
        val after = InstalledPack(entries, manifestEtag ?: before.manifestEtag, licenceUrl ?: before.licenceUrl)
        writeActive(after)
        return after
    }

    /** A manifest with nothing to fetch: remember its ETag for the next `If-None-Match` (AC 21). */
    @Synchronized
    fun rememberEtag(etag: String?, licenceUrl: String?) {
        val now = installed()
        if (now.isEmpty) return
        if (etag == now.manifestEtag && (licenceUrl == null || licenceUrl == now.licenceUrl)) return
        writeActive(now.copy(manifestEtag = etag ?: now.manifestEtag, licenceUrl = licenceUrl ?: now.licenceUrl))
    }

    /** AC 36: `active.json` goes at once; files follow through [collectGarbage] (held files stay until released). */
    @Synchronized
    fun deleteAll() {
        active.delete()
        activeTmp.delete()
        stagingRoot.deleteRecursively()
    }

    /** AC 13 / cancel: all downloads and partial installs of a running job. */
    @Synchronized
    fun clearStaging() {
        stagingRoot.deleteRecursively()
        partialDirs().forEach { it.deleteRecursively() }
    }

    /** Staging entries that are not part of [keep] (`<version>/<file>` of the current files to fetch). */
    @Synchronized
    fun pruneStaging(keep: Set<String>) {
        val dirs = stagingRoot.listFiles() ?: return
        for (d in dirs) {
            if (!d.isDirectory) {
                d.delete()
                continue
            }
            for (f in d.listFiles().orEmpty()) {
                val base = f.name.removeSuffix(VALIDATOR).removeSuffix(TMP).removeSuffix(".gz")
                if ("${d.name}/$base" !in keep) f.delete()
            }
            if (d.listFiles().isNullOrEmpty()) d.delete()
        }
        if (stagingRoot.listFiles().isNullOrEmpty()) stagingRoot.delete()
    }

    /**
     * P2 reconcile and garbage collection: deletes `*.partial` directories (when [deletePartials]), a stale
     * `active.json.tmp`, every file under a version directory that `active.json` does not reference and [holds] does
     * not hold, and empty version directories. Never touches the staging area.
     */
    @Synchronized
    fun collectGarbage(holds: Set<String>, deletePartials: Boolean): Int {
        var deleted = 0
        if (activeTmp.exists() && activeTmp.delete()) deleted++
        if (deletePartials) partialDirs().forEach { if (it.deleteRecursively()) deleted++ }
        val referenced = installed().files.values.map { it.path }.toSet() + holds
        for (dir in packsDir.listFiles().orEmpty()) {
            if (!dir.isDirectory || !PackRules.SLOT_ID.matches(dir.name)) continue
            for (f in dir.listFiles().orEmpty()) {
                if ("${dir.name}/${f.name}" !in referenced && f.deleteRecursively()) deleted++
            }
            if (dir.listFiles().isNullOrEmpty()) dir.delete()
        }
        if (deleted > 0) log.d("pack store: $deleted stale entries deleted")
        return deleted
    }

    /** Bytes of the installed files (OF28 uses the manifest sizes recorded in active.json). */
    fun installedBytes(): Long = installed().totalBytes

    private fun partialDirs(): List<File> =
        packsDir.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith(PARTIAL) }

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (to.exists() && !to.delete()) throw IOException("cannot replace an installed file")
        if (!from.renameTo(to)) throw IOException("rename failed")
    }

    /**
     * The commit point. NAV-022 review M3: the temp file is written and fsynced before the rename, so a power loss
     * right after the rename never leaves an empty or truncated `active.json` (the old one, or the new one, complete).
     */
    private fun writeActive(p: InstalledPack) {
        packsDir.mkdirs()
        FileOutputStream(activeTmp).use { out ->
            out.write(toJson(p).toString().toByteArray(Charsets.UTF_8))
            out.flush()
            fsync(out.fd)
        }
        if (!activeTmp.renameTo(active)) throw IOException("rename of active.json failed")
    }

    private fun resolve(relative: String): File? {
        if (relative.isEmpty() || relative.startsWith("/") || relative.split('/').any { it == ".." || it.isEmpty() }) return null
        val base = packsDir.canonicalFile
        val f = File(base, relative).canonicalFile
        return f.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    companion object {
        const val STAGING = ".dl"
        const val PARTIAL = ".partial"
        const val VALIDATOR = ".validator"
        const val TMP = ".tmp"

        fun toJson(p: InstalledPack): JsonObject {
            val files = p.files.values.sortedBy { it.kind.ordinal }.associate { f ->
                val format = buildMap<String, JsonElement> {
                    f.graphBuilder?.let { put("graph_builder", JsonPrimitive(it)) }
                    f.searchSchema?.let { put("search_schema", JsonPrimitive(it)) }
                    f.pmtiles?.let { put("pmtiles", JsonPrimitive(it)) }
                    f.minzoom?.let { put("minzoom", JsonPrimitive(it)) }
                    f.maxzoom?.let { put("maxzoom", JsonPrimitive(it)) }
                }
                f.kind.wire to JsonObject(
                    mapOf(
                        "version" to JsonPrimitive(f.version),
                        "path" to JsonPrimitive(f.path),
                        "bytes" to JsonPrimitive(f.bytes),
                        "sha256" to JsonPrimitive(f.sha256),
                        "data_timestamp" to JsonPrimitive(f.dataTimestamp),
                        "format" to JsonObject(format),
                    ),
                )
            }
            return JsonObject(
                mapOf(
                    "schema" to JsonPrimitive(PackFiles.SCHEMA),
                    "region" to JsonPrimitive(PackRules.REGION),
                    "manifest_etag" to (p.manifestEtag?.let { JsonPrimitive(it) } ?: JsonNull),
                    "licence_url" to (p.licenceUrl?.let { JsonPrimitive(it) } ?: JsonNull),
                    "files" to JsonObject(files),
                ),
            )
        }

        /** Tolerant parse: entries with a bad version or path are skipped (never half-trusted). */
        fun parse(text: String): InstalledPack? {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
            if ((root["schema"] as? JsonPrimitive)?.intOrNull != PackFiles.SCHEMA) return null
            val files = root["files"] as? JsonObject ?: return InstalledPack.NONE
            val out = LinkedHashMap<PackKind, InstalledFile>()
            for ((k, v) in files) {
                val kind = PackKind.ofWire(k) ?: continue
                val o = v as? JsonObject ?: continue
                fun s(name: String) = (o[name] as? JsonPrimitive)?.contentOrNull
                val version = s("version") ?: continue
                val path = s("path") ?: continue
                if (!PackRules.SLOT_ID.matches(version) || path != "$version/${kind.installedName}") continue
                val format = o["format"] as? JsonObject
                fun fi(name: String) = (format?.get(name) as? JsonPrimitive)?.intOrNull
                out[kind] = InstalledFile(
                    kind = kind,
                    version = version,
                    path = path,
                    bytes = (o["bytes"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    sha256 = s("sha256").orEmpty(),
                    dataTimestamp = s("data_timestamp").orEmpty(),
                    graphBuilder = (format?.get("graph_builder") as? JsonPrimitive)?.contentOrNull,
                    searchSchema = fi("search_schema"),
                    pmtiles = fi("pmtiles"),
                    minzoom = fi("minzoom"),
                    maxzoom = fi("maxzoom"),
                )
            }
            return InstalledPack(
                files = out,
                manifestEtag = (root["manifest_etag"] as? JsonPrimitive)?.contentOrNull,
                licenceUrl = (root["licence_url"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
}

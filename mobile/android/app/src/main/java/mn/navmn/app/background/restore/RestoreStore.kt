package mn.navmn.app.background.restore

import java.io.File

/** What [RestoreStore.read] found. */
sealed interface StoredRecord {
    data object Absent : StoredRecord

    /** A record directory exists but `meta.json` is missing, torn or of an unknown schema (AC 24). */
    data object Unreadable : StoredRecord

    data class Present(val meta: RestoreMeta) : StoredRecord
}

/**
 * ADR-0013 §3.1: `route.bin` and `meta.json` in an app-private directory (on the device `noBackupFilesDir/restore/`,
 * excluded from Auto Backup by the platform and by `data_extraction_rules.xml`; no other app can read it). Every
 * write goes to a temporary file in the same directory and is then renamed over the target, so a kill during a write
 * leaves the old file or nothing, never a torn file (the atomic-rename guarantee `AtomicFile` relies on). Plain
 * `java.io` so the store runs in JVM tests. Callers run it off the main and engine threads.
 */
class RestoreStore(private val dir: File) {
    private val meta get() = File(dir, META)
    private val route get() = File(dir, ROUTE)

    @Synchronized
    fun read(): StoredRecord {
        if (!dir.exists()) return StoredRecord.Absent
        val f = meta
        if (!f.isFile) return if (dir.listFiles().isNullOrEmpty()) StoredRecord.Absent else StoredRecord.Unreadable
        val m = runCatching { RestoreCodec.decode(f.readBytes()) }.getOrNull() ?: return StoredRecord.Unreadable
        return StoredRecord.Present(m)
    }

    /** The stored route bytes when they match [RestoreMeta.routeSha256]; null when absent or mismatched. */
    @Synchronized
    fun readRoute(m: RestoreMeta): ByteArray? {
        val sha = m.routeSha256 ?: return null
        val bytes = runCatching { route.readBytes() }.getOrNull() ?: return null
        return if (RestoreCodec.sha256(bytes) == sha) bytes else null
    }

    /** «Эхлэх» and every new route: the route first, then the metadata that names its hash (AC 16). */
    @Synchronized
    fun writeAll(m: RestoreMeta, routeBytes: ByteArray?) {
        dir.mkdirs()
        if (routeBytes != null) atomicWrite(route, routeBytes) else route.delete()
        atomicWrite(meta, RestoreCodec.encode(m))
    }

    /** Heartbeat and restore bookkeeping: the metadata only. */
    @Synchronized
    fun writeMeta(m: RestoreMeta) {
        if (!dir.exists()) return // ended meanwhile: never resurrect a deleted record
        atomicWrite(meta, RestoreCodec.encode(m))
    }

    /** Normal end, expiry, loop limit, unreadable (AC 17, 21, 24). Synchronous; two small deletes. */
    @Synchronized
    fun delete() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    fun exists(): Boolean = dir.exists()

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(dir, target.name + ".new")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "restore record write failed" }
        }
    }

    companion object {
        const val DIR = "restore"
        const val META = "meta.json"
        const val ROUTE = "route.bin"
    }
}

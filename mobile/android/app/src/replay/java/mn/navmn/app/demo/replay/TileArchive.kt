package mn.navmn.app.demo.replay

import java.io.File
import java.io.IOException
import java.io.InputStream

/** PMTiles v3 header check (ADR-0016 §9; avoids the native crash on invalid headers, MapLibre #3304). */
object PmtilesHeader {
    private val MAGIC = "PMTiles".encodeToByteArray()
    const val VERSION: Byte = 3
    const val LENGTH = 8

    fun isV3(head: ByteArray): Boolean =
        head.size >= LENGTH && (0 until MAGIC.size).all { head[it] == MAGIC[it] } && head[MAGIC.size] == VERSION

    fun isV3(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(LENGTH)
            var n = 0
            while (n < LENGTH) {
                val r = input.read(head, n, LENGTH - n)
                if (r < 0) break
                n += r
            }
            n == LENGTH && isV3(head)
        }
    }.getOrDefault(false)
}

/**
 * ADR-0016 §9 (DM-5): MapLibre Native 13.6.1 cannot range-read APK assets (`pmtiles://asset://` fails, upstream #4360),
 * so the bundled archive is copied once per installed version into [dir] (no-backup storage) and opened as
 * `pmtiles://file://<absolute path>`. Streams to a `.tmp` file, renames it, deletes older copies, and checks the header
 * before the archive is published. Pure java.io (unit-tested).
 */
class TileArchiveCopier(private val dir: File) {
    /** The archive for [version]; copies [open] unless a copy of [length] bytes with a valid header exists. */
    @Throws(IOException::class)
    fun prepare(version: String, length: Long, open: () -> InputStream): File {
        val target = File(dir, "$PREFIX$version$SUFFIX")
        if (target.isFile && (length < 0 || target.length() == length) && PmtilesHeader.isV3(target)) {
            cleanOthers(target)
            return target
        }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create the tiles directory")
        val tmp = File(dir, target.name + ".tmp")
        try {
            open().use { input -> tmp.outputStream().use { out -> input.copyTo(out, BUFFER) } }
            if (length >= 0 && tmp.length() != length) throw IOException("short copy")
            if (!PmtilesHeader.isV3(tmp)) throw IOException("not a PMTiles v3 archive")
            if (target.exists() && !target.delete()) throw IOException("cannot replace the old copy")
            if (!tmp.renameTo(target)) throw IOException("rename failed")
        } finally {
            tmp.delete()
        }
        cleanOthers(target)
        return target
    }

    private fun cleanOthers(keep: File) {
        dir.listFiles()?.filter { it.name != keep.name }?.forEach { it.delete() }
    }

    companion object {
        const val PREFIX = "basemap-"
        const val SUFFIX = ".pmtiles"
        private const val BUFFER = 256 * 1024

        /** The MapLibre URL form for a local archive (ADR-0016 §9). */
        fun pmtilesUrl(file: File): String = "pmtiles://file://" + file.absolutePath
    }
}

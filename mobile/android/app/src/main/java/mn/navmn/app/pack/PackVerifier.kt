package mn.navmn.app.pack

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * NAV-022 AC 16 (P4), in this order: SHA-256 of the downloaded bytes = `download_sha256`; streaming gunzip to exactly
 * `bytes` bytes with SHA-256 `sha256`. The decompressed file is written as `<name>.tmp` and renamed only when both
 * checks pass; on any failure nothing of it remains.
 */
object PackVerifier {
    sealed interface Result {
        data class Ok(val raw: File) : Result
        data object DownloadChecksum : Result
        data object Decompressed : Result
        data object NoSpace : Result
    }

    suspend fun verifyAndInflate(f: PackFile, gz: File, raw: File): Result {
        if (!gz.isFile || gz.length() != f.downloadBytes) return Result.DownloadChecksum
        if (sha256(gz) != f.downloadSha256) return Result.DownloadChecksum
        val tmp = File(raw.path + PackStore.TMP)
        tmp.delete()
        val ok = try {
            inflate(gz, tmp, f.bytes) == f.sha256
        } catch (e: IOException) {
            tmp.delete()
            return if (PackHttp.isNoSpace(e)) Result.NoSpace else Result.Decompressed
        }
        if (!ok) {
            tmp.delete()
            return Result.Decompressed
        }
        raw.delete()
        if (!tmp.renameTo(raw)) {
            tmp.delete()
            return Result.Decompressed
        }
        return Result.Ok(raw)
    }

    /** A staged raw file from an earlier run is reused only if it still has the expected size and checksum. */
    suspend fun rawStillValid(f: PackFile, raw: File): Boolean = raw.isFile && raw.length() == f.bytes && sha256(raw) == f.sha256

    suspend fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    suspend fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER)
        val ctx = currentCoroutineContext()
        while (true) {
            ctx.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    /** Returns the SHA-256 of the decompressed bytes, or "" when the stream is longer or shorter than [expectedBytes]. */
    private suspend fun inflate(gz: File, out: File, expectedBytes: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val ctx = currentCoroutineContext()
        GZIPInputStream(gz.inputStream().buffered(BUFFER), BUFFER).use { input ->
            FileOutputStream(out).use { o ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    ctx.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > expectedBytes) return "" // never inflate past the manifest size
                    md.update(buf, 0, n)
                    try {
                        o.write(buf, 0, n)
                    } catch (e: IOException) {
                        if (PackHttp.isNoSpace(e)) throw NoSpaceException()
                        throw e
                    }
                }
                o.fd.sync()
            }
        }
        return if (total == expectedBytes) hex(md.digest()) else ""
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private const val BUFFER = 64 * 1024
}

/**
 * The PMTiles v3 header (first 127 bytes, spec v3 §3): magic `PMTiles`, version 3, min/max zoom at bytes 100/101,
 * bounds as little-endian int32 E7 at 102–117. The `tiles` self-test of AC 16.
 */
data class PmtilesHeader(
    val version: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
) {
    fun contains(lat: Double, lon: Double): Boolean = lat in minLat..maxLat && lon in minLon..maxLon

    companion object {
        const val SIZE = 127
        private val MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)

        fun read(file: File): PmtilesHeader? {
            val b = ByteArray(SIZE)
            val n = runCatching { file.inputStream().use { readFully(it, b) } }.getOrDefault(0)
            return if (n < SIZE) null else parse(b)
        }

        fun parse(b: ByteArray): PmtilesHeader? {
            if (b.size < SIZE) return null
            for (i in MAGIC.indices) if (b[i] != MAGIC[i]) return null
            fun i32(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
                ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
            return PmtilesHeader(
                version = b[7].toInt() and 0xff,
                minZoom = b[100].toInt() and 0xff,
                maxZoom = b[101].toInt() and 0xff,
                minLon = i32(102) / 1e7,
                minLat = i32(106) / 1e7,
                maxLon = i32(110) / 1e7,
                maxLat = i32(114) / 1e7,
            )
        }

        private fun readFully(input: InputStream, b: ByteArray): Int {
            var off = 0
            while (off < b.size) {
                val n = input.read(b, off, b.size - off)
                if (n < 0) break
                off += n
            }
            return off
        }
    }
}

package mn.navmn.app.pack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed interface ManifestResult {
    data class Ok(val manifest: PackManifest, val etag: String?) : ManifestResult
    data object NotModified : ManifestResult
    data object NotFound : ManifestResult
    data class RateLimited(val retryAfterMs: Long) : ManifestResult
    /** Network error, 5xx, a body that is not a valid manifest. */
    data object Failed : ManifestResult
}

sealed interface DownloadResult {
    /** The compressed file is complete on disk (`download_bytes` bytes; the checksum is verified afterwards). */
    data object Done : DownloadResult
    /** 404: the file was retired after a newer publication (AC 15: re-read the manifest). */
    data object Retired : DownloadResult
    data class RateLimited(val retryAfterMs: Long) : DownloadResult
    /** A network error or 5xx; the partial file is kept for the resume (AC 14, 15). [progressed]: bytes arrived. */
    data class Failed(val progressed: Boolean) : DownloadResult
    /** A write failed with no space left (AC 7). */
    data object NoSpace : DownloadResult
}

/**
 * NAV-022 P3: the two `packs` operations of openapi 0.6.1 against the configured base URL (`<base>/mn/manifest.json`,
 * `<base>/mn/<path>`). The base is either the gateway's `/packs` or a static host (build property `nav.packBaseUrl`).
 *
 * Static-host rules, checked here instead of assumed: a 200 to a `Range` request restarts the file; a 206 must start
 * at the requested byte (`Content-Range`); the resume validator is a **strong** `ETag`, else `Last-Modified`, else none
 * (then `Range` is sent without `If-Range`: the URL is immutable and the checksum catches anything else). Pack files are
 * requested with `Accept-Encoding: identity`, so no HTTP content coding can touch the gzip bytes. Requests carry no
 * coordinates, identifiers or auth; the `User-Agent` is the app name and version only (AC 42).
 */
open class PackHttp(
    baseUrl: String,
    private val client: OkHttpClient,
    private val userAgent: String,
) {
    private val base: HttpUrl = (baseUrl.trimEnd('/') + "/").toHttpUrlOrNull() ?: throw IllegalArgumentException("bad pack base URL")
    val manifestUrl: HttpUrl = base.resolve("${PackRules.REGION}/manifest.json")!!

    fun fileUrl(f: PackFile): HttpUrl = base.resolve("${PackRules.REGION}/${f.path}")!!

    suspend fun fetchManifest(ifNoneMatch: String? = null): ManifestResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(manifestUrl).header("User-Agent", userAgent).header("Cache-Control", "no-cache")
            .apply { if (!ifNoneMatch.isNullOrEmpty()) header("If-None-Match", ifNoneMatch) }
            .build()
        try {
            execute(client.newCall(req)).use { r ->
                when {
                    r.code == 304 -> ManifestResult.NotModified
                    r.code == 404 -> ManifestResult.NotFound
                    r.code == 429 -> ManifestResult.RateLimited(retryAfterMs(r))
                    r.code != 200 -> ManifestResult.Failed
                    else -> {
                        val body = r.body.source().apply { request(MAX_MANIFEST_BYTES + 1) }
                        if (body.buffer.size > MAX_MANIFEST_BYTES) return@use ManifestResult.Failed
                        val text = r.body.string()
                        runCatching { ManifestResult.Ok(ManifestParser.parse(text), r.header("ETag")) }.getOrDefault(ManifestResult.Failed)
                    }
                }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            ManifestResult.Failed
        }
    }

    /**
     * One transfer of [f] into [gz] with resume (AC 14): `Range: bytes=<have>-` plus `If-Range: <validator>`. A 200
     * restarts from byte 0; 416 means the partial file is complete (verify) or wrong (restart). [onBytes] receives the
     * bytes now on disk for this file.
     */
    open suspend fun download(f: PackFile, gz: File, validatorFile: File, onBytes: (Long) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            gz.parentFile?.mkdirs()
            var restarts = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                var have = if (gz.isFile) gz.length() else 0L
                if (have > f.downloadBytes) {
                    reset(gz, validatorFile)
                    have = 0
                }
                if (have == f.downloadBytes) return@withContext DownloadResult.Done
                val validator = validatorFile.takeIf { it.isFile && have > 0 }?.readText()?.takeIf { it.isNotBlank() }
                val req = Request.Builder().url(fileUrl(f))
                    .header("User-Agent", userAgent)
                    .header("Accept-Encoding", "identity")
                    .apply {
                        if (have > 0) {
                            header("Range", "bytes=$have-")
                            if (validator != null) header("If-Range", validator)
                        }
                    }
                    .build()
                val outcome: Step = try {
                    execute(client.newCall(req)).use { r -> transfer(r, f, gz, validatorFile, have, onBytes) }
                } catch (e: NoSpaceException) {
                    Step.Result(DownloadResult.NoSpace)
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (isNoSpace(e)) Step.Result(DownloadResult.NoSpace) else Step.Result(DownloadResult.Failed(gz.length() > have))
                }
                when (outcome) {
                    is Step.Result -> return@withContext outcome.result
                    Step.Restart -> {
                        if (++restarts > MAX_RESTARTS) return@withContext DownloadResult.Failed(false)
                        reset(gz, validatorFile)
                    }
                    Step.Continue -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE")
            DownloadResult.Failed(false)
        }

    private sealed interface Step {
        data class Result(val result: DownloadResult) : Step
        /** Delete the partial file and request it again from byte 0. */
        data object Restart : Step
        /** Request again from the bytes on disk. */
        data object Continue : Step
    }

    private suspend fun transfer(r: Response, f: PackFile, gz: File, validatorFile: File, have: Long, onBytes: (Long) -> Unit): Step {
        when (r.code) {
            404 -> return Step.Result(DownloadResult.Retired)
            429 -> return Step.Result(DownloadResult.RateLimited(retryAfterMs(r)))
            416 -> return if (gz.length() == f.downloadBytes) Step.Result(DownloadResult.Done) else Step.Restart
            200, 206 -> Unit
            else -> return Step.Result(DownloadResult.Failed(false))
        }
        val append: Boolean
        if (r.code == 206) {
            val start = CONTENT_RANGE.find(r.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
            if (start != have) return Step.Restart // a range we did not ask for: start over
            append = true
        } else {
            append = false // 200: no Range, or If-Range did not match (validator changed): restart the file (AC 14)
            validatorOf(r)?.let { validatorFile.writeText(it) } ?: validatorFile.delete()
        }
        var onDisk = if (append) have else 0L
        val ctx = currentCoroutineContext()
        FileOutputStream(gz, append).use { out ->
            val src = r.body.byteStream()
            val buf = ByteArray(BUFFER)
            while (true) {
                ctx.ensureActive()
                val n = src.read(buf)
                if (n < 0) break
                if (onDisk + n > f.downloadBytes) return Step.Restart // longer than the manifest says: not our file
                writeOrNoSpace(out, buf, n)
                onDisk += n
                onBytes(onDisk)
            }
            out.flush()
        }
        return if (onDisk == f.downloadBytes) Step.Result(DownloadResult.Done) else Step.Result(DownloadResult.Failed(onDisk > have))
    }

    private fun writeOrNoSpace(out: FileOutputStream, buf: ByteArray, n: Int) {
        try {
            out.write(buf, 0, n)
        } catch (e: IOException) {
            if (isNoSpace(e)) throw NoSpaceException()
            throw e
        }
    }

    private fun reset(gz: File, validatorFile: File) {
        gz.delete()
        validatorFile.delete()
    }

    /** Cancelling the coroutine cancels the call, so a blocking read ends at once (AC 13: stop within 5 s). */
    private suspend fun execute(call: Call): Response {
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            return call.execute()
        } finally {
            handle.dispose()
        }
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 1_000_000L
        const val BUFFER = 64 * 1024
        const val MAX_RESTARTS = 2
        const val DEFAULT_RETRY_AFTER_MS = 60_000L
        private val CONTENT_RANGE = Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$")

        /** A strong ETag, else Last-Modified (both are valid `If-Range` values); a weak ETag never matches If-Range. */
        fun validatorOf(r: Response): String? {
            val etag = r.header("ETag")?.trim()
            if (!etag.isNullOrEmpty() && !etag.startsWith("W/")) return etag
            return r.header("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }
        }

        fun retryAfterMs(r: Response): Long {
            val v = r.header("Retry-After")?.trim() ?: return DEFAULT_RETRY_AFTER_MS
            v.toLongOrNull()?.let { return maxOf(0L, it) * 1000 }
            return runCatching {
                maxOf(0L, ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis())
            }.getOrDefault(DEFAULT_RETRY_AFTER_MS)
        }

        /** ENOSPC from the platform ("write failed: ENOSPC (No space left on device)") or a test double. */
        fun isNoSpace(t: Throwable?): Boolean {
            var e = t
            while (e != null) {
                if (e is NoSpaceException) return true
                val m = e.message.orEmpty()
                if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) return true
                e = e.cause
            }
            return false
        }
    }
}

class NoSpaceException : IOException("ENOSPC (No space left on device)")

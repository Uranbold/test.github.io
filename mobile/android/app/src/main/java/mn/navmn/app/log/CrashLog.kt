package mn.navmn.app.log

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Bug B-NAV019-01 diagnostic (debug and demo builds only, `BuildConfig.CRASH_DIAGNOSTICS`): the last uncaught exception
 * is written to app-private storage so the next launch can show it (copy / share) without adb.
 *
 * Privacy (NAV-005 AC 67, NAV-019 AC 40): the file holds the exception chain and stack frames, the app version, the
 * Android API level and the phone model only. Every number that could be a coordinate (`-?\d{1,3}\.\d{3,}`, wider than
 * the AC 40 audit regex) is replaced before the text is stored, so an exception message can never leak a position.
 * Nothing leaves the phone unless the user shares it. One file, overwritten by the next crash, deleted on «Хаах».
 */
class CrashLog(private val dir: File) {
    private val file = File(dir, FILE_NAME)

    /** The stored report, or null when there is none (or it cannot be read). */
    fun read(): String? = runCatching { file.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() } }.getOrNull()

    fun clear() {
        file.delete()
    }

    /** Writes the report for [error]; never throws (it runs inside the dying process). */
    fun record(thread: Thread, error: Throwable, header: String) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(format(thread, error, header))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    /**
     * Installs a default uncaught-exception handler that records the report, then hands over to the previous handler
     * (the platform's: the process still dies and the system crash dialog / log behave as before).
     */
    fun install(header: String) {
        // Re-installing (a new Application in the same JVM, e.g. Robolectric) replaces ours instead of chaining it.
        val previous = Thread.getDefaultUncaughtExceptionHandler().let { if (it is Handler) it.previous else it }
        Thread.setDefaultUncaughtExceptionHandler(Handler(this, header, previous))
    }

    internal class Handler(
        val log: CrashLog,
        private val header: String,
        val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            log.record(t, e, header)
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        const val FILE_NAME = "last-crash.txt"
        private const val MAX_CHARS = 64 * 1024
        private val COORDINATE_LIKE = Regex("-?\\d{1,3}\\.\\d{3,}")
        const val REDACTED = "[redacted]"

        /** App-private: `files/crash/` (no external storage, no backup of coordinates possible: none are stored). */
        fun of(context: Context): CrashLog = CrashLog(File(context.filesDir, "crash"))

        /** Version and device class only: no IDs, no account, no location. */
        fun header(versionName: String, versionCode: Int): String =
            "app $versionName ($versionCode) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}"

        fun redact(text: String): String = text.replace(COORDINATE_LIKE, REDACTED)

        fun format(thread: Thread, error: Throwable, header: String): String {
            val trace = StringWriter().also { w -> PrintWriter(w).use { error.printStackTrace(it) } }.toString()
            val text = redact("$header\nthread: ${thread.name}\n\n$trace")
            return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + "\n…"
        }
    }
}

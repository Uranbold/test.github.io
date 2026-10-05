package mn.navmn.app.routing

import android.app.Application
import android.os.Build
import java.io.File

/**
 * NAV-021 R2 / AC 4 (ADR-0017 §2): `Application.onCreate` runs in every process of the app. In the `:routing` process
 * the application class must skip the main-process initialisation (Hilt graph, crash diagnostics, the replay variant;
 * MapLibre, notification channels and the foreground service are created lazily by main-process components only).
 */
object ProcessRole {
    const val ROUTING_SUFFIX = ":routing"

    /** AC 4 test hook (log-free): incremented by every main-process initialisation; read in `:routing` it must be 0. */
    @Volatile
    var mainInitCount: Int = 0
        private set

    fun onMainInit() {
        mainInitCount++
    }

    fun isRoutingProcessName(processName: String?): Boolean = processName != null && processName.endsWith(ROUTING_SUFFIX)

    /** `Application.getProcessName()` on API 28+, `/proc/self/cmdline` on API 26–27. */
    fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().decodeToString() }.getOrNull()
        }

    fun isRoutingProcess(): Boolean = isRoutingProcessName(currentProcessName())
}

package mn.navmn.app.log

/**
 * Debug-only diagnostics (ADR-0009 §11, AC 67): messages never contain coordinates, route bodies or search text.
 * Release builds use [NONE]. The privacy test scans everything written here during a replay.
 */
fun interface DebugLog {
    fun d(message: String)

    companion object {
        val NONE = DebugLog { }
    }
}

package mn.navmn.app.gps

/**
 * ADR-0009 §5 GPS state (AC 51–53): lost after [lostAfterMs] without a good fix, checked by a ticker because no fix
 * may arrive at all; restored by the first good fix. Times are monotonic (elapsedRealtime).
 */
class GpsMonitor(private val lostAfterMs: Long = 10_000) {
    enum class Event { LOST, RESTORED }

    var lost: Boolean = false
        private set
    var lastGoodMs: Long = Long.MIN_VALUE
        private set

    fun start(nowMs: Long) {
        lastGoodMs = nowMs
        lost = false
    }

    fun onGoodFix(fixElapsedMs: Long): Event? {
        lastGoodMs = maxOf(lastGoodMs, fixElapsedMs)
        if (lost) {
            lost = false
            return Event.RESTORED
        }
        return null
    }

    fun onTick(nowMs: Long): Event? {
        if (!lost && nowMs - lastGoodMs >= lostAfterMs) {
            lost = true
            return Event.LOST
        }
        return null
    }

    /** P9: a good fix in the last 10 s and not lost. */
    fun ok(nowMs: Long): Boolean = !lost && nowMs - lastGoodMs < lostAfterMs
}

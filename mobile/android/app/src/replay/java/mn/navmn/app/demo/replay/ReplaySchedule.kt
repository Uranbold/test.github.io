package mn.navmn.app.demo.replay

/**
 * ADR-0016 §4.3 (pure): which track point is due at a replay time, and how long to wait for the next one. Times are
 * replay-time offsets from «Эхлэх» (ms). A late wake-up delivers only the latest due point (a GPS gap, which the
 * NAV-005 catch-up rules handle), never a burst.
 */
class ReplaySchedule(private val offsets: LongArray) {
    init {
        require(offsets.isNotEmpty())
    }

    val last: Int get() = offsets.size - 1

    /** The latest index whose offset is ≤ [sinceStartMs], or -1 before the first point. */
    fun latestDue(sinceStartMs: Long): Int {
        var lo = 0
        var hi = offsets.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] <= sinceStartMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** Real milliseconds until replay time reaches [targetMs] at [speed] (0 when already reached). */
    fun realWaitMs(sinceStartMs: Long, targetMs: Long, speed: Int): Long {
        val remaining = targetMs - sinceStartMs
        if (remaining <= 0) return 0
        return (remaining + speed - 1) / speed
    }

    fun offset(i: Int): Long = offsets[i]
}

package mn.navmn.app.reroute

/**
 * ADR-0009 §4 "Detection": an off-route episode starts after [required] consecutive good fixes (≤ 25 m, ≤ 10 s old)
 * that Ferrostar reports `CompletelyOffRoute` (> 50 m from every remaining step), the run spanning ≥ [minSpanMs] of fix
 * time.
 * It ends after [exitRequired] consecutive good fixes that are not `CompletelyOffRoute`, or when a new route becomes
 * active. Poor fixes neither count nor reset. G6 (one outlier) → no episode; G7 (60 m accuracy) → Ferrostar already
 * reports no deviation. `OffStepOnRoute` never starts an episode.
 */
class OffRouteDetector(
    private val required: Int = 3,
    private val minSpanMs: Long = 2_000,
    private val exitRequired: Int = 2,
) {
    enum class Event { STARTED, ENDED }

    var inEpisode: Boolean = false
        private set
    private var runStart = 0L
    private var runCount = 0
    private var onCount = 0

    fun onFix(good: Boolean, completelyOffRoute: Boolean, fixElapsedMs: Long): Event? {
        if (!good) return null
        if (!inEpisode) {
            if (!completelyOffRoute) {
                runCount = 0
                return null
            }
            if (runCount == 0) runStart = fixElapsedMs
            runCount++
            if (runCount >= required && fixElapsedMs - runStart >= minSpanMs) {
                inEpisode = true
                onCount = 0
                runCount = 0
                return Event.STARTED
            }
            return null
        }
        if (completelyOffRoute) {
            onCount = 0
            return null
        }
        onCount++
        if (onCount >= exitRequired) {
            inEpisode = false
            onCount = 0
            return Event.ENDED
        }
        return null
    }

    /** A new route became active (ends the episode) or GPS was lost (resets the debounce). */
    fun reset() {
        inEpisode = false
        runCount = 0
        onCount = 0
    }

    /** GPS lost: the debounce restarts, an active episode stays. */
    fun resetDebounce() {
        runCount = 0
        onCount = 0
    }
}

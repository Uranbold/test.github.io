package mn.navmn.app.typinglock

import mn.navmn.app.geo.Geo
import mn.navmn.app.location.Fix

/**
 * NAV-011 story Terms "Fix speed" (AC 29), pure: `Location.getSpeed()` when the fix has a speed; otherwise the distance
 * between this good fix and the previous good fix divided by the time between them, only when they are 1–10 s apart.
 */
object FixSpeed {
    const val MIN_GAP_MS = 1_000L
    const val MAX_GAP_MS = 10_000L

    fun of(fix: Fix, previousGood: Fix?): Double? {
        fix.speedMps?.let { if (it.isFinite() && it >= 0) return it }
        val prev = previousGood ?: return null
        val dt = fix.elapsedMs - prev.elapsedMs
        if (dt < MIN_GAP_MS || dt > MAX_GAP_MS) return null
        return Geo.distance(prev.latLon, fix.latLon) / (dt / 1000.0)
    }
}

/**
 * The typing-lock rule (NAV-011 AC 30, 32, 35, 36; ADR-0012 §7), pure, every time injected (fake-clock tested).
 *  - **Engage** (released → engaged): the last 3 consecutive good fixes (accuracy ≤ 25 m), spanning ≥ 2 s, each have a
 *    speed ≥ 4.2 m/s (15 km/h). Poor fixes are ignored: they neither count nor break the run.
 *  - **Release** (engaged → released): every good fix in the last 10 s is < 1.4 m/s (5 km/h) and there are ≥ 5 of them;
 *    or no good fix for 30 s; or [onUnavailable] (permission revoked, location services off). Between 5 and 15 km/h
 *    nothing changes (hysteresis).
 * No fixes at all (no permission, approximate only, services off, no fix yet) → never engaged (AC 35).
 * Nothing is logged or stored (AC 28).
 */
class TypingLockRule {
    companion object {
        const val ENGAGE_MPS = 4.2
        const val ENGAGE_FIXES = 3
        const val ENGAGE_MIN_SPAN_MS = 2_000L
        const val RELEASE_MPS = 1.4
        const val RELEASE_WINDOW_MS = 10_000L
        const val RELEASE_MIN_FIXES = 5
        const val NO_FIX_RELEASE_MS = 30_000L
    }

    private data class Sample(val atMs: Long, val speed: Double?)

    var engaged: Boolean = false
        private set
    private var previousGood: Fix? = null
    private var lastGoodAtMs: Long? = null
    private val recent = ArrayDeque<Sample>()

    /** A platform fix at [nowMs] (monotonic). Returns [engaged]. */
    fun onFix(fix: Fix, nowMs: Long): Boolean {
        if (!(fix.accuracyM.isFinite() && fix.accuracyM <= Fix.GOOD_ACCURACY_M)) return onTick(nowMs)
        val speed = FixSpeed.of(fix, previousGood)
        previousGood = fix
        lastGoodAtMs = fix.elapsedMs
        recent.addLast(Sample(fix.elapsedMs, speed))
        while (recent.size > 64 || (recent.isNotEmpty() && fix.elapsedMs - recent.first().atMs > RELEASE_WINDOW_MS + 1_000)) recent.removeFirst()
        if (!engaged) {
            val last = recent.toList().takeLast(ENGAGE_FIXES)
            if (last.size == ENGAGE_FIXES &&
                last.all { it.speed != null && it.speed >= ENGAGE_MPS } &&
                last.last().atMs - last.first().atMs >= ENGAGE_MIN_SPAN_MS
            ) {
                engaged = true
            }
        } else {
            val window = recent.filter { fix.elapsedMs - it.atMs < RELEASE_WINDOW_MS }
            if (window.size >= RELEASE_MIN_FIXES && window.all { it.speed != null && it.speed < RELEASE_MPS }) release()
        }
        return engaged
    }

    /** The 1 s check without a fix: release after 30 s with no good fix (tunnel, garage, GPS loss). */
    fun onTick(nowMs: Long): Boolean {
        val last = lastGoodAtMs
        if (engaged && (last == null || nowMs - last >= NO_FIX_RELEASE_MS)) release()
        return engaged
    }

    /** Permission revoked or location services turned off (AC 32): release at once and forget the history. */
    fun onUnavailable(): Boolean {
        release()
        return engaged
    }

    private fun release() {
        engaged = false
        recent.clear()
        previousGood = null
        lastGoodAtMs = null
    }
}

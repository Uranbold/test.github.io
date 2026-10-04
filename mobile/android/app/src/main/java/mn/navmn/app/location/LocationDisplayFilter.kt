package mn.navmn.app.location

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import kotlin.math.max

/**
 * NAV-005 section N thresholds (PO starting defaults, [D176](docs/requirements/decisions.md)). They are tuned after the
 * AC 73 real-phone checks through a change on NAV-005: a tuning edits this object and its tests only (ADR-0009
 * Amendment 7 §9.1).
 */
object BrowseDotRules {
    /** AC 76: fixes worse than this (or with no accuracy) are not shown. */
    const val MAX_ACCURACY_M = 100.0

    /** AC 76: no showable fix for this long → stale variant. */
    const val STALE_MS = 10_000L

    /** AC 75: a reported speed below this enters the hold. */
    const val HOLD_SPEED_MPS = 0.5

    /** AC 75: a reported speed of at least this releases the hold. */
    const val RELEASE_SPEED_MPS = 1.0

    /** AC 75: hold radius r = max(accuracy of the new fix, this). */
    const val HOLD_MIN_RADIUS_M = 10.0

    /** AC 75: consecutive showable fixes outside r that release the hold. */
    const val RELEASE_OUTSIDE_FIXES = 2

    /** AC 77: jump distance = more than max(this, [JUMP_ACCURACY_FACTOR] × accuracy) from the shown position … */
    const val JUMP_MIN_M = 50.0
    const val JUMP_ACCURACY_FACTOR = 2.0

    /** … and an implied speed above this. */
    const val JUMP_SPEED_MPS = 50.0

    /** AC 77: the next showable fix confirms a pending jump only within this time … */
    const val CONFIRM_WINDOW_MS = 5_000L

    /** … and within max(its accuracy, this) of the pending fix. */
    const val CONFIRM_MIN_RADIUS_M = 25.0

    /** AC 76 / D172: a my-location press with no showable fix shows «Байршил тодорхойлж чадсангүй» after this. */
    const val LOCATE_TIMEOUT_MS = 10_000L
}

/**
 * The position the browse map shows (NAV-005 Terms "shown position"). [latLon] and [accuracyM] come from the same fix,
 * the one the dot shows, so a held dot keeps its circle (AC 74). [lastShowableElapsedMs] is the fix time of the last
 * showable fix, held or not (AC 76 stale rule, D30 60 s condition). In memory only; [toString] has no coordinates, so
 * an accidental log line cannot leak them (AC 79).
 */
data class DisplayLocation(
    val latLon: LatLon,
    val accuracyM: Double,
    val stale: Boolean,
    val lastShowableElapsedMs: Long,
) {
    override fun toString(): String = "DisplayLocation(stale=$stale)"
}

/**
 * NAV-005 AC 75–77 browse display filter (ADR-0009 Amendment 7 §9.2). Pure Kotlin: no Android, MapLibre or Ferrostar
 * types; all fix timing uses [Fix.elapsedMs] (monotonic), and the stale rule compares the caller's clock (same time base,
 * `SystemClock.elapsedRealtime()`) with the last showable fix time. Never logs; state is memory only (AC 79).
 *
 * Display only (AC 78): the route-preview origin, guidance, the typing lock, the sun theme and the NAV-018 device start
 * keep reading raw fixes.
 */
class LocationDisplayFilter {
    private var shown: Fix? = null
    private var holding = false
    private var outside = 0
    private var pending: Fix? = null
    /** The previous showable fix that was not a pending or discarded jump (implied speed, AC 77). */
    private var ref: Fix? = null
    private var lastShowableElapsedMs = 0L

    /** The current output for [nowElapsedMs] (null = no shown position, AC 76 "no showable fix yet"). */
    fun current(nowElapsedMs: Long): DisplayLocation? {
        val s = shown ?: return null
        return DisplayLocation(s.latLon, s.accuracyM, nowElapsedMs - lastShowableElapsedMs > BrowseDotRules.STALE_MS, lastShowableElapsedMs)
    }

    /** Re-evaluates the stale flag only (AC 76: called by the stale timer; no fix). */
    fun tick(nowElapsedMs: Long): DisplayLocation? = current(nowElapsedMs)

    /** Back to "no shown position" (guidance start, location permission or services lost; Amendment 7 §9.3). */
    fun reset() {
        shown = null
        holding = false
        outside = 0
        pending = null
        ref = null
        lastShowableElapsedMs = 0L
    }

    /** Feeds one raw `mapUpdates()` fix; returns the output after it. */
    fun onFix(f: Fix, nowElapsedMs: Long): DisplayLocation? {
        // 1. Gate (AC 76): not showable → nothing changes, the stale timer is not reset, nothing counts.
        if (!showable(f)) return current(nowElapsedMs)
        // 2. A showable fix (held or not) keeps the dot current.
        lastShowableElapsedMs = f.elapsedMs
        val s = shown
        // 3. First fix.
        if (s == null) {
            show(f)
            return current(nowElapsedMs)
        }
        // 4. Pending jump (AC 77): confirmed by the next showable fix within 5 s and max(its accuracy, 25 m).
        val j = pending
        if (j != null) {
            pending = null
            val withinWindow = f.elapsedMs - j.elapsedMs <= BrowseDotRules.CONFIRM_WINDOW_MS
            if (withinWindow && Geo.distance(f.latLon, j.latLon) <= max(f.accuracyM, BrowseDotRules.CONFIRM_MIN_RADIUS_M)) {
                show(f)
                return current(nowElapsedMs)
            }
            // J is discarded; F goes on against the shown position (interpretation (i): F is jump-tested too).
        }
        // 5. Jump test (AC 77). A non-positive dt (duplicate / out-of-order provider times) is an infinite speed.
        val d = Geo.distance(f.latLon, s.latLon)
        val dtMs = f.elapsedMs - (ref ?: s).elapsedMs
        val far = d > max(BrowseDotRules.JUMP_MIN_M, BrowseDotRules.JUMP_ACCURACY_FACTOR * f.accuracyM)
        if (far && (dtMs <= 0 || d / (dtMs / 1_000.0) > BrowseDotRules.JUMP_SPEED_MPS)) {
            pending = f
            return current(nowElapsedMs)
        }
        ref = f
        // 6. Stationary hold (AC 75).
        val r = max(f.accuracyM, BrowseDotRules.HOLD_MIN_RADIUS_M)
        val speed = f.speedMps?.takeIf { it.isFinite() && it >= 0.0 }
        if (!holding) {
            val enter = if (speed != null) speed < BrowseDotRules.HOLD_SPEED_MPS else d <= r
            if (enter) {
                holding = true
                // Interpretation (ii): the fix that enters the hold counts as the first "outside" fix when d > r.
                outside = if (d > r) 1 else 0
            } else {
                shown = f
            }
        } else if (speed != null && speed >= BrowseDotRules.RELEASE_SPEED_MPS) {
            move(f)
        } else if (d > r) {
            outside++
            if (outside >= BrowseDotRules.RELEASE_OUTSIDE_FIXES) move(f)
        } else {
            outside = 0
        }
        return current(nowElapsedMs)
    }

    private fun show(f: Fix) {
        shown = f
        ref = f
        holding = false
        outside = 0
        pending = null
    }

    private fun move(f: Fix) {
        shown = f
        holding = false
        outside = 0
    }

    companion object {
        /** AC 76: accuracy reported, finite and ≤ 100 m. */
        fun showable(f: Fix): Boolean = f.accuracyM.isFinite() && f.accuracyM >= 0.0 && f.accuracyM <= BrowseDotRules.MAX_ACCURACY_M
    }
}

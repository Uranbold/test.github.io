package mn.navmn.app.engine

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon

/**
 * Step catch-up after a jump (NAV-005-D1, AC 31 / AC 52). Ferrostar's `stepAdvanceDistanceEntryAndExit(30, 5, 25)`
 * only advances after a fix within 30 m of the end of the current step. A GPS gap (tunnel, G3b) that covers a
 * junction resumes with fixes already past it, so the step would never advance and guidance would stay on the passed
 * turn until arrival.
 *
 * The rule mirrors Ferrostar's own `StaticThreshold` classification `OffStepOnRoute` (ADR-0009 §1: 25 m accuracy,
 * 50 m deviation), but applied to the CURRENT fix instead of the previous one (F13): when a good fix is more than
 * 50 m from the current step's geometry and within 50 m of a later step, the user is clearly on that later step.
 * [FerrostarNavigator] then calls Ferrostar's public `NavigationSession.advanceToNextStep` that many times, so
 * snapping, progress and the step-advance condition reset stay Ferrostar's.
 *
 * Outliers: a single good-accuracy outlier must not skip steps (G6b, G6c). [Gate] applies the catch-up only when two
 * consecutive good fixes agree on the same target step, also right after a GPS gap (NAV-005-D10: the first fix after a
 * tunnel may itself be the outlier). At 1 Hz the second fix comes 1 s later, inside AC 53's 2 s. A fix that is off the
 * current step but not (yet) caught up is reported by [offCurrentStep]; the core then holds the last trusted position
 * (NAV-005-D9).
 *
 * Walking dead band (NAV-005-D8, ADR-0009 Amendment 3 §1): fixes that resume 30–50 m past the END of the current
 * step are caught neither by Ferrostar (its entry condition needs a fix within [STEP_ENTRY_M] of the step end) nor by
 * the lateral 50 m rule. Branch (b) covers them: the fix's nearest point on the current step is its last coordinate,
 * the fix is more than [STEP_ENTRY_M] from it, and it is within [PAST_END_MAX_M] of a later step (not `arrive`) and
 * closer to that step than to the current one. Same target choice and the same two-fix [Gate]; a fix that matches
 * either branch but is still pending is untrusted (the core holds the last trusted position).
 *
 * Not applied when only the `arrive` step is left after the current one (Ferrostar's arrival condition and the
 * app's [mn.navmn.app.arrival.ArrivalDetector] own that), and the `arrive` step is never a target. The first later
 * step that matches wins, so the rule never skips more steps than the geometry demands.
 */
object StepCatchUp {
    val MIN_ACCURACY_M: Double = FerrostarConfig.MIN_ACCURACY_M.toDouble()
    const val MAX_DEVIATION_M = FerrostarConfig.MAX_DEVIATION_M

    /** Branch (b): a fix more than this past the end of the current step is beyond Ferrostar's step-advance entry. */
    val STEP_ENTRY_M: Double = FerrostarConfig.STEP_ENTRY_M.toDouble()

    /** Branch (b): the fix must be this close to the later step (the accuracy limit, ADR-0009 Amendment 3 §1). */
    val PAST_END_MAX_M: Double = FerrostarConfig.MIN_ACCURACY_M.toDouble()

    /**
     * @param remaining geometries of the remaining steps; index 0 is the current step, the last is `arrive`.
     * @return the number of steps to advance (0 = leave it to Ferrostar).
     */
    fun stepsToAdvance(fix: LatLon, accuracyM: Double, remaining: List<List<LatLon>>): Int {
        if (!(accuracyM <= MIN_ACCURACY_M)) return 0
        if (remaining.size < 3) return 0
        val current = remaining[0]
        val dCurrent = Geo.distanceToLine(fix, current)
        if (dCurrent > MAX_DEVIATION_M) {
            // (a) lateral: off the current step and within 50 m of a later step
            for (i in 1..remaining.size - 2) {
                if (Geo.distanceToLine(fix, remaining[i]) <= MAX_DEVIATION_M) return i
            }
            return 0
        }
        // (b) past the end (D8): projection clamped at the step's last coordinate, more than STEP_ENTRY_M beyond it
        if (current.isEmpty() || !Geo.nearestIsLast(fix, current) || Geo.distance(fix, current.last()) <= STEP_ENTRY_M) return 0
        for (i in 1..remaining.size - 2) {
            val d = Geo.distanceToLine(fix, remaining[i])
            if (d <= PAST_END_MAX_M && d < dCurrent) return i
        }
        return 0
    }

    /**
     * True when a good fix is more than [MAX_DEVIATION_M] from the current step's geometry (NAV-005-D9). Ferrostar
     * snaps such a fix to the nearest point of the current step (often its end), so the distance to the manoeuvre,
     * the progress and the snapped puck derived from it are not trustworthy for that fix. Poor fixes are never flagged
     * (Ferrostar's own accuracy rule owns them).
     */
    fun offCurrentStep(fix: LatLon, accuracyM: Double, currentStep: List<LatLon>): Boolean =
        accuracyM <= MIN_ACCURACY_M && Geo.distanceToLine(fix, currentStep) > MAX_DEVIATION_M

    /**
     * Applies the catch-up only after two consecutive good fixes agree on the same target step, within
     * [CONFIRM_WINDOW_MS] of each other. There is no shortcut after a GPS gap (NAV-005-D10). Poor fixes neither
     * confirm nor reset a pending target; a good fix on the current step resets it.
     */
    class Gate {
        private var pendingTarget: Int? = null
        private var pendingAt = 0L

        /**
         * @param target absolute index of the step the fix is on, or null when [stepsToAdvance] returned 0.
         * @param good the fix accuracy is ≤ [MIN_ACCURACY_M] (only good fixes can produce a target).
         * @return true when the catch-up to [target] is applied with this fix.
         */
        fun decide(target: Int?, good: Boolean, elapsedMs: Long): Boolean {
            if (!good) return false
            if (target == null) {
                pendingTarget = null
                return false
            }
            if (pendingTarget == target && elapsedMs - pendingAt <= CONFIRM_WINDOW_MS) {
                pendingTarget = null
                return true
            }
            pendingTarget = target
            pendingAt = elapsedMs
            return false
        }

        /** Forgets a pending target (new session state, e.g. after a reroute). */
        fun reset() {
            pendingTarget = null
        }

        companion object {
            /** The confirming good fix must come at most 3 s after the first (a GPS gap in between restarts it). */
            const val CONFIRM_WINDOW_MS = 3_000L
        }
    }
}

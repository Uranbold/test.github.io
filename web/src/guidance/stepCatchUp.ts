// Step catch-up after a jump (ADR-0009 Amendments 2–4, NAV-005-D1/D8/D9/D10). 1:1 port of the Android
// engine/StepCatchUp.kt. Ferrostar's DistanceEntryExit(30, 5, 25) only advances after a fix within 30 m of the end of
// the current step; a track that starts or resumes past a junction (G4 starts 500 m before the end) would otherwise
// stay on the passed step. When a good fix is clearly on a later step, the navigator calls Ferrostar's public
// `advanceToNextStep` that many times, so snapping and progress stay Ferrostar's. In a replay of the recorded tracks
// only G4 needs it; the rules are kept whole so both platforms behave the same (ADR-0011 §6).
import { distance, distanceToLine, nearestIsLast, type LatLon } from "./geo";
import { FerrostarConfig } from "./ferrostarConfig";

export const StepCatchUp = {
  MIN_ACCURACY_M: FerrostarConfig.MIN_ACCURACY_M,
  MAX_DEVIATION_M: FerrostarConfig.MAX_DEVIATION_M,
  /** Branch (b): a fix more than this past the end of the current step is beyond Ferrostar's step-advance entry. */
  STEP_ENTRY_M: FerrostarConfig.STEP_ENTRY_M,
  /** Branch (b): the fix must be this close to the later step (the accuracy limit). */
  PAST_END_MAX_M: FerrostarConfig.MIN_ACCURACY_M,

  /**
   * @param remaining geometries of the remaining steps; index 0 is the current step, the last is `arrive`.
   * @returns the number of steps to advance (0 = leave it to Ferrostar).
   */
  stepsToAdvance(fix: LatLon, accuracyM: number, remaining: { length: number; get(i: number): readonly LatLon[] }): number {
    if (!(accuracyM <= StepCatchUp.MIN_ACCURACY_M)) return 0;
    if (remaining.length < 3) return 0;
    const current = remaining.get(0);
    const dCurrent = distanceToLine(fix, current);
    if (dCurrent > StepCatchUp.MAX_DEVIATION_M) {
      // (a) lateral: off the current step and within 50 m of a later step
      for (let i = 1; i <= remaining.length - 2; i++) {
        if (distanceToLine(fix, remaining.get(i)) <= StepCatchUp.MAX_DEVIATION_M) return i;
      }
      return 0;
    }
    // (b) past the end (D8): projection clamped at the step's last coordinate, more than STEP_ENTRY_M beyond it
    if (current.length === 0 || !nearestIsLast(fix, current) || distance(fix, current[current.length - 1]!) <= StepCatchUp.STEP_ENTRY_M) return 0;
    for (let i = 1; i <= remaining.length - 2; i++) {
      const d = distanceToLine(fix, remaining.get(i));
      if (d <= StepCatchUp.PAST_END_MAX_M && d < dCurrent) return i;
    }
    return 0;
  },

  /** True when a good fix is more than MAX_DEVIATION_M from the current step's geometry (NAV-005-D9). */
  offCurrentStep(fix: LatLon, accuracyM: number, currentStep: readonly LatLon[]): boolean {
    return accuracyM <= StepCatchUp.MIN_ACCURACY_M && distanceToLine(fix, currentStep) > StepCatchUp.MAX_DEVIATION_M;
  },
} as const;

/**
 * Applies the catch-up only after two consecutive good fixes agree on the same target step, within 3 s of each other
 * (NAV-005-D10). Poor fixes neither confirm nor reset a pending target; a good fix on the current step resets it.
 */
export class CatchUpGate {
  static readonly CONFIRM_WINDOW_MS = 3_000;
  private pendingTarget: number | null = null;
  private pendingAt = 0;

  decide(target: number | null, good: boolean, elapsedMs: number): boolean {
    if (!good) return false;
    if (target === null) {
      this.pendingTarget = null;
      return false;
    }
    if (this.pendingTarget === target && elapsedMs - this.pendingAt <= CatchUpGate.CONFIRM_WINDOW_MS) {
      this.pendingTarget = null;
      return true;
    }
    this.pendingTarget = target;
    this.pendingAt = elapsedMs;
    return false;
  }

  reset(): void {
    this.pendingTarget = null;
  }
}

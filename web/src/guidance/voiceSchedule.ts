// Device-side voice schedule (navigation-ux §4.2–§4.4; ADR-0009 §3.3 and the Amendment 4 D2 playback-start rule).
// 1:1 port of the Android voiceplan/VoiceSchedule.kt: same named constants, rule order and tie-breaks, so the web
// replay produces the NAV-005 golden prompt sequence (NAV-017 AC 26). Pure: no DOM, injected times.
import type { KeyResult } from "../route/instructions";
import { isArrive, type GuidancePlan } from "./plan";
import type { VoiceContent } from "./voiceText";

/** navigation-ux §4.2–§4.5 as named constants. Change only through UX and the BA. */
export const VoiceConstants = {
  /** Fast = v ≥ 70 km/h. */
  FAST_MPS: 70 / 3.6,
  EARLY_SLOW_M: 1_000,
  EARLY_SLOW_MIN_GAP_M: 1_300,
  EARLY_FAST_M: 2_000,
  EARLY_FAST_MIN_GAP_M: 2_500,
  MAIN_FAST_M: 500,
  MAIN_FAST_MIN_GAP_M: 700,
  MAIN_SECONDS: 12,
  MAIN_MIN_M: 100,
  MAIN_MAX_M: 250,
  NOW_SECONDS: 3,
  NOW_MIN_M: 15,
  NOW_MAX_M: 80,
  WALK_MAIN_M: 50,
  WALK_NOW_M: 15,
  CONTINUE_ON_MIN_M: 2_000,
  SAME_MANEUVER_GAP_MS: 8_000,
  ARRIVE_MIN_M: 30,
  /** §4.3 (NAV-005-D12, D67): no catch-up approaching prompt for an `arrive` more than this far ahead. */
  ARRIVE_CATCH_UP_MAX_M: 500,
  CHAIN_CAR_M: 150,
  CHAIN_WALK_M: 40,
  RESTORE_MIN_AHEAD_M: 20,
  SPEED_WINDOW_MS: 5_000,
  /** §4.5: a waiting prompt that cannot start within 3 s of its trigger is dropped. */
  MAX_WAIT_MS: 3_000,
} as const;

export type PromptKind = "continueOn" | "early" | "main" | "now" | "depart" | "catchUp";

/** (generation, plan step index) of the manoeuvre a prompt is about. */
export interface ManeuverRef {
  gen: number;
  step: number;
}

export interface ScheduledPrompt {
  content: VoiceContent;
  maneuver: ManeuverRef | null;
  kind: PromptKind | null;
  triggerAtMs: number;
}

const ref = (gen: number, step: number): string => `${gen}:${step}`;
const firedKey = (gen: number, step: number, kind: PromptKind): string => `${gen}:${step}:${kind}`;
const clamp = (v: number, lo: number, hi: number): number => Math.min(hi, Math.max(lo, v));

/** Mean speed over the last 5 s of good fixes (reported speed when present). */
export class SpeedTracker {
  private readonly samples: { at: number; v: number }[] = [];
  constructor(private readonly windowMs: number = VoiceConstants.SPEED_WINDOW_MS) {}

  add(atMs: number, speedMps: number | null | undefined): void {
    if (speedMps === null || speedMps === undefined || !Number.isFinite(speedMps) || speedMps < 0) return;
    this.samples.push({ at: atMs, v: speedMps });
    while (this.samples.length > 0 && atMs - this.samples[0]!.at > this.windowMs) this.samples.shift();
  }

  mean(): number {
    if (this.samples.length === 0) return 0;
    let s = 0;
    for (const x of this.samples) s += x.v;
    return s / this.samples.length;
  }

  clear(): void {
    this.samples.length = 0;
  }
}

export interface ScheduleInput {
  nowMs: number;
  plan: GuidancePlan;
  /** Current step k (Ferrostar); the upcoming manoeuvre is m = k + 1. */
  stepIndex: number;
  distanceToNext: number;
  speedMps: number;
}

/**
 * Each kind fires once when d first falls to or below its distance; only the most urgent unfired kind fires and the
 * less urgent ones are then marked as handled (VoiceScheduler.kt).
 */
export class VoiceScheduler {
  private readonly fired = new Set<string>();
  /** Rule 2 reference per manoeuvre: the playback start of its latest prompt (NAV-005-D2). */
  private readonly lastPromptAt = new Map<string, number>();
  private readonly lastStartedAt = new Map<string, number>();
  private readonly spoken = new Set<string>();
  private readonly chainedAnnounced = new Set<string>();
  private lastStep: string | null = null;
  private pendingCatchUp: "reroute" | "restore" | null = null;

  constructor(private readonly walk: boolean) {}

  private get chainM(): number {
    return this.walk ? VoiceConstants.CHAIN_WALK_M : VoiceConstants.CHAIN_CAR_M;
  }

  /** Manoeuvre m+1 is chained to m: ≤ 150 m (car) / ≤ 40 m (walk) after it, and not `arrive` (§4.4). */
  chainTarget(plan: GuidancePlan, m: number): KeyResult | null {
    const next = plan.steps[m + 1];
    if (!next || isArrive(next.key)) return null;
    return plan.steps[m]!.distance <= this.chainM ? next.key : null;
  }

  private markChained(gen: number, m: number): void {
    this.chainedAnnounced.add(ref(gen, m));
    for (const k of ["early", "main", "continueOn", "catchUp"] as const) this.fired.add(firedKey(gen, m + 1, k));
  }

  /** §4.3 start: the depart text, chained with the first manoeuvre when it is within the chaining distance. */
  start(plan: GuidancePlan, nowMs: number): ScheduledPrompt {
    const gen = plan.generation;
    this.lastStep = ref(gen, 0);
    const then = plan.steps.length > 1 ? this.chainTarget(plan, 0) : null;
    this.spoken.add(ref(gen, 0));
    this.lastPromptAt.set(ref(gen, 0), nowMs);
    if (then) this.markChained(gen, 0);
    return { content: { kind: "depart", key: plan.steps[0]!.key, then }, maneuver: { gen, step: 0 }, kind: "depart", triggerAtMs: nowMs };
  }

  /** The playback queue started a prompt for `maneuver` at `atMs` (navigation-ux §4.2 rule 2). */
  onPromptStarted(maneuver: ManeuverRef, atMs: number): void {
    const id = ref(maneuver.gen, maneuver.step);
    this.lastStartedAt.set(id, atMs);
    this.lastPromptAt.set(id, atMs);
  }

  /** A prompt triggered at `triggerAtMs` was dropped without playing: it does not count for rule 2. */
  onPromptDropped(maneuver: ManeuverRef, triggerAtMs: number): void {
    const id = ref(maneuver.gen, maneuver.step);
    if (this.lastPromptAt.get(id) !== triggerAtMs) return;
    const started = this.lastStartedAt.get(id);
    if (started === undefined) this.lastPromptAt.delete(id);
    else this.lastPromptAt.set(id, started);
  }

  private earlyThreshold(fast: boolean, gap: number, key: KeyResult): number | null {
    if (this.walk || isArrive(key) || key.key === "roundabout.leave") return null;
    if (fast && gap >= VoiceConstants.EARLY_FAST_MIN_GAP_M) return VoiceConstants.EARLY_FAST_M;
    if (!fast && gap >= VoiceConstants.EARLY_SLOW_MIN_GAP_M) return VoiceConstants.EARLY_SLOW_M;
    return null;
  }

  private mainThreshold(fast: boolean, gap: number, v: number, key: KeyResult): number | null {
    if (key.key === "roundabout.leave") return null;
    if (this.walk) return VoiceConstants.WALK_MAIN_M;
    if (fast && gap >= VoiceConstants.MAIN_FAST_MIN_GAP_M) return VoiceConstants.MAIN_FAST_M;
    return clamp(v * VoiceConstants.MAIN_SECONDS, VoiceConstants.MAIN_MIN_M, VoiceConstants.MAIN_MAX_M);
  }

  private nowThreshold(v: number, key: KeyResult): number | null {
    if (isArrive(key)) return null; // the arrival prompt comes from the arrival detector (§7)
    if (this.walk) return VoiceConstants.WALK_NOW_M;
    return clamp(v * VoiceConstants.NOW_SECONDS, VoiceConstants.NOW_MIN_M, VoiceConstants.NOW_MAX_M);
  }

  private maneuverContent(plan: GuidancePlan, m: number, d: number, chain: boolean): VoiceContent | null {
    const key = plan.steps[m]!.key;
    if (isArrive(key)) return d >= VoiceConstants.ARRIVE_MIN_M ? { kind: "approaching", distanceM: d } : null;
    const then = chain ? this.chainTarget(plan, m) : null;
    return { kind: "maneuver", key, distanceM: d, then };
  }

  private emit(gen: number, m: number, kind: PromptKind, content: VoiceContent, now: number): ScheduledPrompt {
    this.lastPromptAt.set(ref(gen, m), now);
    this.spoken.add(ref(gen, m));
    if (content.kind === "maneuver" && content.then) this.markChained(gen, m);
    return { content, maneuver: { gen, step: m }, kind, triggerAtMs: now };
  }

  /**
   * One evaluation (every snapshot and ticker step); at most one prompt. Precondition (NAV-005-D9): the input comes
   * from a trusted position.
   */
  evaluate(input: ScheduleInput): ScheduledPrompt | null {
    const { plan, nowMs: now, distanceToNext: d, speedMps: v } = input;
    const gen = plan.generation;
    const k = input.stepIndex;
    const m = k + 1;
    if (m >= plan.steps.length) return null;
    const id = ref(gen, m);
    const fast = v >= VoiceConstants.FAST_MPS;
    const gap = plan.steps[k]!.distance;
    const key = plan.steps[m]!.key;
    const stepChanged = this.lastStep !== ref(gen, k);
    const firstObservation = this.lastStep === null;
    this.lastStep = ref(gen, k);
    const early = this.earlyThreshold(fast, gap, key);
    const main = this.mainThreshold(fast, gap, v, key);
    const nowThr = this.nowThreshold(v, key);

    const catchUp = this.pendingCatchUp;
    if (catchUp !== null) {
      this.pendingCatchUp = null;
      const skip = catchUp === "restore" && (this.spoken.has(id) || d <= VoiceConstants.RESTORE_MIN_AHEAD_M);
      if (!skip) {
        if (!this.walk && d >= VoiceConstants.CONTINUE_ON_MIN_M) {
          this.fired.add(firedKey(gen, m, "continueOn"));
          return this.emit(gen, m, "catchUp", { kind: "continueOn", distanceM: d }, now);
        }
        const arriveTooFar = isArrive(key) && d > VoiceConstants.ARRIVE_CATCH_UP_MAX_M;
        if (!arriveTooFar && (early === null || d <= early)) {
          this.fired.add(firedKey(gen, m, "early"));
          this.fired.add(firedKey(gen, m, "main"));
          if (nowThr !== null && d <= nowThr) this.fired.add(firedKey(gen, m, "now"));
          const content = this.maneuverContent(plan, m, d, true);
          if (!content) return null;
          return this.emit(gen, m, "catchUp", content, now);
        }
      }
    }

    // Continue on (A12): right after passing a manoeuvre, if the next one is ≥ 2 km away (car only).
    if (stepChanged && !firstObservation && !this.walk && d >= VoiceConstants.CONTINUE_ON_MIN_M && !this.fired.has(firedKey(gen, m, "continueOn"))) {
      this.fired.add(firedKey(gen, m, "continueOn"));
      return this.emit(gen, m, "continueOn", { kind: "continueOn", distanceM: d }, now);
    }

    const order: [PromptKind, number | null][] = [
      ["now", nowThr],
      ["main", main],
      ["early", early],
    ];
    for (let i = 0; i < order.length; i++) {
      const [kind, thr] = order[i]!;
      if (thr === null || d > thr || this.fired.has(firedKey(gen, m, kind))) continue;
      for (let j = i; j < order.length; j++) this.fired.add(firedKey(gen, m, order[j]![0]));
      const last = this.lastPromptAt.get(id);
      if (last !== undefined && now - last < VoiceConstants.SAME_MANEUVER_GAP_MS) return null; // rule 2
      const content = this.maneuverContent(plan, m, d, kind !== "early");
      if (!content) return null; // rule 3
      return this.emit(gen, m, kind, content, now);
    }
    return null;
  }
}

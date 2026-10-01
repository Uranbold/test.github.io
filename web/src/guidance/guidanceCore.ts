// The guidance logic of a replay (NAV-017, ADR-0011 §6): a port of the Android engine/GuidanceCore.kt without the
// parts that cannot occur in a replay of the recorded tracks (reroute policy, off-route episode, GPS loss, network
// state): the position is simulated and no backend exists. Single-threaded and pure: every call comes from the replay
// engine (or a test with a fake clock). Drives the Ferrostar navigator with each fix and owns arrival (ADR-0009 §5),
// the voice schedule (§3.3) and the playback queue (navigation-ux §4.5).
import type { Lang } from "../i18n/i18n";
import type { KeyResult } from "../route/instructions";
import { ArrivalDetector } from "./arrivalDetector";
import type { NavSnapshot, Navigator } from "./ferrostarCore";
import { isGood, latLonOf, type Fix } from "./fix";
import type { LatLon } from "./geo";
import { PlaybackQueue, type PromptClass, type Speaker } from "./playbackQueue";
import type { GuidancePlan } from "./plan";
import { renderVoice, type T } from "./voiceText";
import { SpeedTracker, VoiceScheduler, type ScheduledPrompt } from "./voiceSchedule";

export type GuidancePhase = "navigating" | "arrived" | "ended";

export type Banner =
  | { variant: "maneuver"; step: number; key: KeyResult; distanceM: number; street: string }
  | { variant: "arrival"; step: number; key: KeyResult; street: string };

export interface Progress {
  /** Metres left along the route (Ferrostar, snapped). */
  distanceRemaining: number;
  /** Seconds left: the not-yet-driven share of the current step's recorded duration + later steps (NAV-017 AC 21). */
  durationRemaining: number;
}

export interface GuidanceState {
  phase: GuidancePhase;
  banner: Banner;
  progress: Progress;
  /** Snapped position and course of the latest trusted fix. */
  puck: { position: LatLon; courseDeg: number | null } | null;
  /** Current step k (the upcoming manoeuvre is k + 1). */
  stepIndex: number;
  voiceNoticeVisible: boolean;
  muted: boolean;
  speedMps: number;
}

export interface Clock {
  /** Replay-clock milliseconds (excludes paused time). */
  elapsedMs(): number;
}

export interface CoreDeps {
  clock: Clock;
  plan: GuidancePlan;
  navigator: Navigator;
  mode: "car" | "walk";
  speaker: Speaker;
  t: (lang: Lang) => T;
  lang: Lang;
  muted: boolean;
  onState?: (s: GuidanceState) => void;
  onArrived?: () => void;
}

export const VOICE_NOTICE_MS = 8_000;

/**
 * NAV-017 AC 21: remaining duration from the plan, not Ferrostar's estimate. `distanceToNext` is the distance left on
 * the current step k.
 */
export function remainingDuration(plan: GuidancePlan, k: number, distanceToNext: number): number {
  const step = plan.steps[k];
  if (!step) return 0;
  const share = step.distance > 0 ? Math.min(1, Math.max(0, distanceToNext / step.distance)) : 0;
  let s = share * step.duration;
  for (let i = k + 1; i < plan.steps.length; i++) s += plan.steps[i]!.duration;
  return s;
}

export class GuidanceCore {
  private readonly plan: GuidancePlan;
  private snapshot: NavSnapshot | null = null;
  private positionTrusted = true;
  private readonly arrival = new ArrivalDetector();
  private readonly scheduler: VoiceScheduler;
  private readonly speed = new SpeedTracker();
  readonly queue: PlaybackQueue;
  private phase: GuidancePhase = "navigating";
  private lang: Lang;
  private muted: boolean;
  private progress: Progress;
  private voiceNoticeUntil = Number.NEGATIVE_INFINITY;
  private voiceNoticeShown = false;
  private promptIds = 0;
  private started = false;

  constructor(private readonly deps: CoreDeps) {
    this.plan = deps.plan;
    this.lang = deps.lang;
    this.muted = deps.muted;
    this.scheduler = new VoiceScheduler(deps.mode === "walk");
    this.progress = { distanceRemaining: deps.plan.distance, durationRemaining: deps.plan.duration };
    this.queue = new PlaybackQueue(deps.speaker, {
      onStarted: (p, at) => {
        if (p.maneuver) this.scheduler.onPromptStarted(p.maneuver, at);
      },
      onDropped: (p) => {
        if (p.maneuver) this.scheduler.onPromptDropped(p.maneuver, p.triggerAtMs);
      },
    });
  }

  get finished(): boolean {
    return this.phase !== "navigating";
  }

  get currentPhase(): GuidancePhase {
    return this.phase;
  }

  get currentLang(): Lang {
    return this.lang;
  }

  /** The depart prompt's text in the current language, without enqueuing it (for the «Эхлэх» audio unlock). */
  departText(): string {
    const s = new VoiceScheduler(this.deps.mode === "walk").start(this.plan, 0);
    return renderVoice(s.content, this.lang, this.deps.t(this.lang));
  }

  /** «Эхлэх» with the first simulated fix: the depart prompt at once (NAV-017 AC 27). */
  start(fix: Fix): void {
    if (this.started) throw new Error("already started");
    this.started = true;
    const now = this.deps.clock.elapsedMs();
    if (isGood(fix, now)) this.speed.add(fix.elapsedMs, fix.speedMps);
    const snap = this.deps.navigator.initial(fix);
    this.snapshot = snap;
    this.positionTrusted = true;
    this.updateProgress(snap);
    const depart = this.scheduler.start(this.plan, now);
    this.speak(depart, "maneuver", now);
    this.emit();
  }

  onFix(fix: Fix): void {
    if (!this.started || this.finished) return;
    const now = this.deps.clock.elapsedMs();
    const good = isGood(fix, now);
    const snap = this.deps.navigator.update(fix);
    this.positionTrusted = snap.fixOnCurrentStep;
    if (good) this.speed.add(fix.elapsedMs, fix.speedMps);
    if (this.positionTrusted) {
      this.snapshot = snap;
      this.updateProgress(snap);
    }
    // ADR-0009 Amendment 3 §5: rule (b) only with a trusted fix, rule (c) only on the last leg.
    if (
      this.arrival.check({
        complete: snap.complete,
        offRoute: false,
        goodFix: good,
        trustedFix: snap.fixOnCurrentStep,
        distanceRemaining: snap.distanceRemaining,
        position: latLonOf(fix),
        routeEnd: this.plan.end,
        stepIndex: snap.stepIndex,
        lastStepIndex: this.plan.steps.length - 1,
      })
    ) {
      this.arrive(now);
      this.emit();
      return;
    }
    this.evaluateVoice(now);
    this.emit();
  }

  /** 500 ms ticker: queue timeouts and the expiring A1 notice. */
  onTick(): void {
    if (!this.started || this.phase === "ended") return;
    const now = this.deps.clock.elapsedMs();
    this.queue.tick(now);
    if (this.phase === "arrived") {
      this.emit();
      return;
    }
    this.evaluateVoice(now);
    this.emit();
  }

  onSpeakerDone(promptId: number): void {
    this.queue.onDone(promptId, this.deps.clock.elapsedMs());
  }

  /** The voice output uses the chime instead of a voice (D23, D73): A1 once per replay, Mongolian UI only. */
  onVoiceFallback(): void {
    if (this.voiceNoticeShown || this.lang !== "mn" || this.finished) return;
    this.voiceNoticeShown = true;
    this.voiceNoticeUntil = this.deps.clock.elapsedMs() + VOICE_NOTICE_MS;
    this.emit();
  }

  dismissVoiceNotice(): void {
    this.voiceNoticeUntil = Number.NEGATIVE_INFINITY;
    this.emit();
  }

  setMuted(value: boolean): void {
    this.muted = value;
    if (value) this.queue.clear(); // AC 30: the current utterance or chime stops
    this.emit();
  }

  /** AC 31: the current utterance stops; the next prompt uses the new language. */
  setLanguage(value: Lang): void {
    if (value === this.lang) return;
    this.lang = value;
    this.queue.clear();
    this.emit();
  }

  /** Page hidden (AC 15): any utterance or chime stops; nothing is produced while the clock is paused. */
  silence(): void {
    this.queue.clear();
  }

  /** «Дуусгах», «Хаах», track end: everything stops. */
  end(): void {
    if (this.phase === "ended") return;
    this.phase = "ended";
    this.queue.close();
    try {
      this.deps.navigator.close();
    } catch {
      // already freed
    }
    this.emit();
  }

  state(): GuidanceState {
    const now = this.deps.clock.elapsedMs();
    const snap = this.snapshot;
    const steps = this.plan.steps;
    const last = steps.length - 1;
    let banner: Banner;
    const k = snap?.stepIndex ?? 0;
    if (this.phase !== "navigating" || k + 1 > last) {
      banner = { variant: "arrival", step: last, key: steps[last]!.key, street: steps[last]!.street };
    } else {
      const m = k + 1;
      banner = { variant: "maneuver", step: m, key: steps[m]!.key, distanceM: snap?.distanceToNextManeuver ?? steps[0]!.distance, street: steps[m]!.street };
    }
    return {
      phase: this.phase,
      banner,
      progress: this.progress,
      puck: snap ? { position: snap.snapped, courseDeg: snap.snappedCourseDeg } : null,
      stepIndex: k,
      voiceNoticeVisible: now < this.voiceNoticeUntil && !this.finished,
      muted: this.muted,
      speedMps: this.speed.mean(),
    };
  }

  private updateProgress(snap: NavSnapshot): void {
    this.progress = {
      distanceRemaining: snap.distanceRemaining,
      durationRemaining: snap.complete ? 0 : remainingDuration(this.plan, snap.stepIndex, snap.distanceToNextManeuver),
    };
  }

  private arrive(now: number): void {
    this.phase = "arrived";
    const lastIndex = this.plan.steps.length - 1;
    const last = this.plan.steps[lastIndex]!;
    this.speak({ content: { kind: "arrival", key: last.key }, maneuver: { gen: this.plan.generation, step: lastIndex }, kind: null, triggerAtMs: now }, "arrival", now);
    this.deps.onArrived?.();
  }

  private evaluateVoice(now: number): void {
    if (this.phase !== "navigating" || !this.positionTrusted) return;
    const snap = this.snapshot;
    if (!snap) return;
    const p = this.scheduler.evaluate({ nowMs: now, plan: this.plan, stepIndex: snap.stepIndex, distanceToNext: snap.distanceToNextManeuver, speedMps: this.speed.mean() });
    if (p) this.speak(p, "maneuver", now);
  }

  private speak(p: ScheduledPrompt, cls: PromptClass, now: number): void {
    if (this.muted) return; // muted prompts count as handled (ADR-0009 §3.3)
    const text = renderVoice(p.content, this.lang, this.deps.t(this.lang));
    this.queue.enqueue({ id: ++this.promptIds, text, lang: this.lang, cls, maneuver: p.maneuver, triggerAtMs: now }, now);
  }

  private emit(): void {
    this.deps.onState?.(this.state());
  }
}

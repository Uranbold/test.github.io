// Replay engine and replay clock (NAV-017 AC 12, 15, 16; ADR-0011 §6; navigation-ux §11.2, §11.6). Pure: the time
// source and the timer are injected, so Vitest runs replays on a fake clock.
//  - The replay clock is derived from timestamps (`performance.now()` in production), never from counting timer
//    ticks, so a throttled timer (Low Power Mode) cannot shift positions. It excludes paused time.
//  - A 100 ms ticker applies, in order, every simulated fix whose track time is ≤ the clock (± 100 ms at 1×), then the
//    guidance ticker every 500 ms of replay time. After a late timer several fixes may be due; each goes through the
//    core with its own timestamp, and the scheduler's rules drop prompts for passed manoeuvres.
//  - When the track ends without arrival, the replay ends as after «Дуусгах» (AC 16).
import type { Fix } from "./fix";

export interface TimeSource {
  now(): number;
}

export class ReplayClock {
  private base = 0;
  private startedAt: number | null = null;

  constructor(private readonly time: TimeSource) {}

  get running(): boolean {
    return this.startedAt !== null;
  }

  start(): void {
    this.base = 0;
    this.startedAt = this.time.now();
  }

  pause(): void {
    if (this.startedAt === null) return;
    this.base += this.time.now() - this.startedAt;
    this.startedAt = null;
  }

  resume(): void {
    if (this.startedAt !== null) return;
    this.startedAt = this.time.now();
  }

  /** Replay time in ms since «Эхлэх», without paused time. */
  elapsedMs(): number {
    return this.base + (this.startedAt === null ? 0 : this.time.now() - this.startedAt);
  }
}

export interface Timer {
  setInterval(fn: () => void, ms: number): unknown;
  clearInterval(handle: unknown): void;
}

/** What the engine drives (GuidanceCore implements it). */
export interface ReplayTarget {
  start(fix: Fix): void;
  onFix(fix: Fix): void;
  onTick(): void;
  readonly finished: boolean;
}

export interface ReplayCallbacks {
  /** A fix was applied (after the core processed it). */
  onFix?(fix: Fix, index: number): void;
  /** The last fix was applied and no arrival was detected (AC 16). */
  onTrackEnd?(): void;
  /** Called after every tick (the UI renders from the core's state). */
  onTick?(): void;
}

export const TICK_MS = 100;
export const CORE_TICK_MS = 500;

export class ReplayEngine {
  private next = 1;
  private handle: unknown = null;
  private lastCoreTick = 0;
  private ended = false;
  private paused = false;

  constructor(
    private readonly fixes: readonly Fix[],
    private readonly target: ReplayTarget,
    readonly clock: ReplayClock,
    private readonly timer: Timer,
    private readonly cb: ReplayCallbacks = {},
  ) {
    if (fixes.length === 0) throw new Error("empty track");
  }

  get isPaused(): boolean {
    return this.paused;
  }

  get isEnded(): boolean {
    return this.ended;
  }

  /** Index of the last applied fix. */
  get appliedIndex(): number {
    return this.next - 1;
  }

  /** Starts at the first track point (call inside the «Эхлэх» handler, after the audio unlock). */
  start(): void {
    this.clock.start();
    this.target.start(this.fixes[0]!);
    this.cb.onFix?.(this.fixes[0]!, 0);
    this.handle = this.timer.setInterval(() => this.tick(), TICK_MS);
  }

  /** One ticker step; public for tests. */
  tick(): void {
    if (this.ended || this.paused) return;
    const t = this.clock.elapsedMs();
    while (this.next < this.fixes.length && this.fixes[this.next]!.elapsedMs <= t && !this.target.finished) {
      const f = this.fixes[this.next]!;
      this.target.onFix(f);
      this.cb.onFix?.(f, this.next);
      this.next++;
    }
    while (t - this.lastCoreTick >= CORE_TICK_MS) {
      this.lastCoreTick += CORE_TICK_MS;
      this.target.onTick();
    }
    this.cb.onTick?.();
    if (this.next >= this.fixes.length && !this.target.finished) {
      this.stop();
      this.cb.onTrackEnd?.();
    }
  }

  /** Page hidden (AC 15): the clock pauses; nothing is applied until `resume`. */
  pause(): void {
    if (this.ended || this.paused) return;
    this.paused = true;
    this.clock.pause();
  }

  resume(): void {
    if (this.ended || !this.paused) return;
    this.paused = false;
    this.clock.resume();
  }

  /** Stops the ticker for good («Дуусгах», arrival panel closed, track end). Arrival keeps ticking for the queue. */
  stop(): void {
    this.ended = true;
    if (this.handle !== null) this.timer.clearInterval(this.handle);
    this.handle = null;
    this.clock.pause();
  }
}

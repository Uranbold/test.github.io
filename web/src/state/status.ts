// Map status: loading / tiles unavailable / offline (NAV-002 AC 37–45, ADR-0004 §5, flows F1, F2, F5).
// Pure logic with injectable timers so it can be unit-tested without a browser.

export const LOADING_DELAY_MS = 300; // tokens: motion.loading-delay
/**
 * First-load visual reveal of the pre-module pill, ms after navigation start (src/boot/bootLoading.ts). NAV-002 screen
 * spec › States › Loading (follow-up 1, 2026-09-30) allows 250–300 ms to absorb 1–2 frames of paint latency, so the
 * pill is on screen by 350 ms. AC 37 itself is unchanged.
 */
export const LOADING_REVEAL_MS = 270;
export const START_WATCHDOG_MS = 10_000;
export const TILE_ERROR_THRESHOLD = 3;

export type StatusReason = "tiles" | "offline" | "generic";

export type StatusView =
  | { kind: "none" }
  | { kind: "loading" }
  | { kind: "card"; reason: StatusReason; busy: boolean }
  | { kind: "banner"; reason: "tiles" | "offline" };

export interface StatusState {
  online: boolean;
  /** The first `idle` with tiles has happened for the current attempt (or earlier). */
  ready: boolean;
  loadingDelayElapsed: boolean;
  /** A start-up attempt failed (header/TileJSON error before ready, or the 10 s watchdog). Tile errors never set it. */
  startFailed: boolean;
  /** A retry from the blocking card is in progress (the card stays, its button is busy). */
  retrying: boolean;
  genericError: boolean;
  consecutiveTileErrors: number;
}

/** Precedence: offline > tiles unavailable > loading (AC 45). Blocking card before the first render, banner after. */
export function viewOf(s: StatusState): StatusView {
  if (s.genericError) return { kind: "card", reason: "generic", busy: false };
  if (!s.ready) {
    if (!s.online) return { kind: "card", reason: "offline", busy: false };
    if (s.startFailed) return { kind: "card", reason: "tiles", busy: s.retrying };
    if (s.loadingDelayElapsed) return { kind: "loading" };
    return { kind: "none" };
  }
  if (!s.online) return { kind: "banner", reason: "offline" };
  if (s.consecutiveTileErrors >= TILE_ERROR_THRESHOLD) return { kind: "banner", reason: "tiles" };
  return { kind: "none" };
}

export interface Timers {
  setTimeout(fn: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
}

const browserTimers: Timers = {
  setTimeout: (fn, ms) => globalThis.setTimeout(fn, ms),
  clearTimeout: (h) => globalThis.clearTimeout(h as ReturnType<typeof setTimeout>),
};

type Listener = (view: StatusView, state: Readonly<StatusState>) => void;

export class StatusMachine {
  private s: StatusState;
  private loadingTimer: unknown = null;
  private watchdog: unknown = null;
  private readonly listeners = new Set<Listener>();
  private lastViewKey = "";

  constructor(online: boolean, private readonly timers: Timers = browserTimers) {
    this.s = {
      online,
      ready: false,
      loadingDelayElapsed: false,
      startFailed: false,
      retrying: false,
      genericError: false,
      consecutiveTileErrors: 0,
    };
  }

  get state(): Readonly<StatusState> {
    return this.s;
  }

  get view(): StatusView {
    return viewOf(this.s);
  }

  onChange(l: Listener): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  /**
   * Start (or restart) loading before the first render.
   * @param elapsedMs time already spent since the page was opened (performance.now() on first load),
   *   so the indicator appears 300 ms after navigation start, not after script start (AC 37).
   */
  start(elapsedMs = 0): void {
    this.clearTimers();
    const delay = Math.max(0, LOADING_DELAY_MS - Math.max(0, elapsedMs));
    // Already past 300 ms (e.g. the pre-module indicator from index.html is showing): loading at once, so the
    // pill does not blink off and on when the app takes over.
    this.update({ ready: false, startFailed: false, retrying: false, loadingDelayElapsed: delay === 0, consecutiveTileErrors: 0 });
    if (delay > 0) this.loadingTimer = this.timers.setTimeout(() => this.update({ loadingDelayElapsed: true }), delay);
    this.armWatchdog();
  }

  /**
   * A new load attempt before the first render (card «Дахин оролдох», or back online).
   * The card stays with a busy button while a failed attempt is retried.
   */
  retryStart(): void {
    if (this.s.ready) return;
    this.update({ retrying: this.s.startFailed, consecutiveTileErrors: 0 });
    if (!this.s.startFailed) {
      this.update({ loadingDelayElapsed: false });
      this.timers.clearTimeout(this.loadingTimer);
      this.loadingTimer = this.timers.setTimeout(() => this.update({ loadingDelayElapsed: true }), LOADING_DELAY_MS);
    }
    this.armWatchdog();
  }

  /**
   * MapLibre `idle` with the basemap source loaded and at least one tile received.
   * Consecutive tile errors are kept: if the last tiles before the first render failed and none has loaded
   * since, the tiles banner shows at once once the map is ready (AC 41).
   */
  markReady(): void {
    this.clearTimers();
    this.update({ ready: true, startFailed: false, retrying: false });
  }

  /**
   * An `error` event for the basemap source itself, without `e.tile` (archive header / TileJSON).
   * Before the first render the start-up attempt has failed: blocking card (AC 38–39).
   * After it, it counts like a tile error (e.g. the source rebuild of a banner retry fails).
   */
  sourceError(): void {
    if (!this.s.ready) {
      this.timers.clearTimeout(this.watchdog);
      this.watchdog = null;
      this.update({ startFailed: true, retrying: false });
      return;
    }
    this.tileError();
  }

  /**
   * An `error` event for one basemap tile (`e.tile` set). Never fails the start-up attempt on its own, so one
   * transient tile failure cannot raise the blocking card: it counts towards the 3-error tiles banner (AC 41).
   * If no tile loads at all, the 10 s watchdog ends the attempt with the card (AC 38).
   */
  tileError(): void {
    this.update({ consecutiveTileErrors: this.s.consecutiveTileErrors + 1 });
  }

  /** A basemap tile loaded successfully. Clears the mid-session banner (AC 41). */
  tileLoaded(): void {
    if (this.s.consecutiveTileErrors !== 0) this.update({ consecutiveTileErrors: 0 });
  }

  setOnline(online: boolean): void {
    this.update({ online });
  }

  fail(): void {
    this.clearTimers();
    this.update({ genericError: true });
  }

  dispose(): void {
    this.clearTimers();
    this.listeners.clear();
  }

  private armWatchdog(): void {
    this.timers.clearTimeout(this.watchdog);
    this.watchdog = this.timers.setTimeout(() => {
      this.watchdog = null;
      if (!this.s.ready) this.update({ startFailed: true, retrying: false });
    }, START_WATCHDOG_MS);
  }

  private clearTimers(): void {
    this.timers.clearTimeout(this.loadingTimer);
    this.timers.clearTimeout(this.watchdog);
    this.loadingTimer = null;
    this.watchdog = null;
  }

  private update(patch: Partial<StatusState>): void {
    this.s = { ...this.s, ...patch };
    const view = viewOf(this.s);
    const key = JSON.stringify(view);
    if (key === this.lastViewKey) return;
    this.lastViewKey = key;
    for (const l of this.listeners) l(view, this.s);
  }
}

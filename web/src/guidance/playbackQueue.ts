// navigation-ux §4.5 playback rules 1, 3, 4, 6 (NAV-017 AC 26, 29–32). 1:1 port of the Android
// voiceplan/PlaybackQueue.kt. Pure; times come from the caller.
//  1. one utterance at a time; at most one waits; a waiting prompt that cannot start within 3 s of its trigger is
//     dropped; a newer prompt replaces a waiting older one;
//  3. arrival waits for the current utterance (max 3 s), then plays; nothing plays after it;
//  4./6. mute, «Дуусгах», language switch, page hidden: `clear()`.
import type { Lang } from "../i18n/i18n";
import { VoiceConstants, type ManeuverRef } from "./voiceSchedule";

export type PromptClass = "maneuver" | "arrival";

/** A rendered prompt ready to be spoken (text already in the UI language at trigger time). */
export interface SpokenPrompt {
  id: number;
  text: string;
  lang: Lang;
  cls: PromptClass;
  maneuver: ManeuverRef | null;
  triggerAtMs: number;
}

/** Plays one prompt (speech or the chime). Reports the end through `PlaybackQueue.onDone`. */
export interface Speaker {
  play(prompt: SpokenPrompt): void;
  stop(): void;
}

export interface PlaybackListener {
  onStarted?(prompt: SpokenPrompt, atMs: number): void;
  onDropped?(prompt: SpokenPrompt): void;
}

export class PlaybackQueue {
  current: SpokenPrompt | null = null;
  private currentStartedAt = 0;
  waiting: SpokenPrompt | null = null;
  /** After the arrival prompt nothing plays. */
  closed = false;
  /** Everything that was started, in order (tests only; never persisted). */
  readonly started: SpokenPrompt[] = [];

  static readonly STUCK_MS = 15_000;

  constructor(
    private readonly speaker: Speaker,
    private readonly listener: PlaybackListener = {},
  ) {}

  enqueue(p: SpokenPrompt, nowMs: number): void {
    if (this.closed || this.waiting?.cls === "arrival") {
      this.listener.onDropped?.(p);
      return;
    }
    if (this.current === null) {
      this.start(p, nowMs);
    } else {
      if (this.waiting) this.listener.onDropped?.(this.waiting);
      this.waiting = p;
    }
  }

  onDone(id: number, nowMs: number): void {
    if (this.current?.id !== id) return;
    const wasArrival = this.current.cls === "arrival";
    this.current = null;
    if (wasArrival) {
      this.closed = true;
      if (this.waiting) this.listener.onDropped?.(this.waiting);
      this.waiting = null;
      return;
    }
    this.promote(nowMs);
  }

  private promote(nowMs: number): void {
    const w = this.waiting;
    if (!w) return;
    this.waiting = null;
    if (w.cls === "arrival" || nowMs - w.triggerAtMs <= VoiceConstants.MAX_WAIT_MS) this.start(w, nowMs);
    else this.listener.onDropped?.(w);
  }

  tick(nowMs: number): void {
    const w = this.waiting;
    if (w) {
      if (w.cls === "arrival" && nowMs - w.triggerAtMs >= VoiceConstants.MAX_WAIT_MS) {
        this.speaker.stop();
        this.current = null;
        this.waiting = null;
        this.start(w, nowMs);
      } else if (w.cls !== "arrival" && nowMs - w.triggerAtMs > VoiceConstants.MAX_WAIT_MS) {
        this.waiting = null;
        this.listener.onDropped?.(w);
      }
    }
    // Safety net: a speaker that never reports the end must not block the queue for good.
    const c = this.current;
    if (c && nowMs - this.currentStartedAt > PlaybackQueue.STUCK_MS) this.onDone(c.id, nowMs);
  }

  clear(): void {
    if (this.current) this.speaker.stop();
    this.current = null;
    if (this.waiting) this.listener.onDropped?.(this.waiting);
    this.waiting = null;
  }

  /** Ends playback for good (replay ended). */
  close(): void {
    this.clear();
    this.closed = true;
  }

  private start(p: SpokenPrompt, nowMs: number): void {
    this.current = p;
    this.currentStartedAt = nowMs;
    this.started.push(p);
    this.listener.onStarted?.(p, nowMs);
    this.speaker.play(p);
  }
}

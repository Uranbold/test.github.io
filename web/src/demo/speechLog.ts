// NAV-017 change F1 (hidden voice diagnostics): the last speech and chime events of the demo replay, kept in memory
// only (never stored, logged to the console or sent; AC 42/43). AudioOut writes it, the diagnostics panel reads it.

export const SPEECH_LOG_SIZE = 10;
/** Only the start of a prompt is kept: enough to recognise it, never a whole instruction sequence. */
export const SPEECH_LOG_TEXT_CHARS = 30;

export type SpeechEventKind =
  /** speechSynthesis.speak() was called (`prime`: the empty zero-volume unlock utterance). */
  | "speak"
  | "start"
  | "end"
  | "error"
  /** The prompt had no onstart within 3 s and was cancelled (AudioOut START_TIMEOUT_MS). */
  | "no-start"
  /** The watchdog ended an utterance that never fired end/error. */
  | "watchdog"
  /** One chime attempt (one per chimed prompt): path, AudioContext state before/after and the result. */
  | "chime"
  /** The chime element failed (priming, or play() rejected after it was started). */
  | "chime-error"
  /** AudioContext statechange. */
  | "ctx-state"
  /** The silent keep-alive loop of a replay started, stopped or failed. */
  | "keep-alive";

export interface SpeechEvent {
  /** Milliseconds on the page clock (performance.now()). */
  atMs: number;
  kind: SpeechEventKind;
  /** speak: first SPEECH_LOG_TEXT_CHARS characters of the text. */
  text?: string;
  /** speak: the utterance lang ("" when not set). */
  lang?: string;
  /** speak: the voice name, or null when no voice was set. */
  voice?: string | null;
  /** error: SpeechSynthesisErrorEvent.error. */
  code?: string;
  /** speak/start/end/error of the unlock utterance. */
  prime?: boolean;
  /** chime, chime-error: which output was used ("none" = no path could play it). */
  path?: "webaudio" | "element" | "none";
  /** chime: AudioContext state before the attempt and when the result was known ("none" = no context). */
  ctxBefore?: string;
  ctxAfter?: string;
  /** chime, chime-error, keep-alive: what happened. */
  result?: string;
  /** ctx-state: the new AudioContext state. */
  state?: string;
}

export class SpeechEventLog {
  private readonly events: SpeechEvent[] = [];
  private readonly listeners = new Set<() => void>();

  constructor(
    private readonly now: () => number,
    private readonly size = SPEECH_LOG_SIZE,
  ) {}

  add(e: Omit<SpeechEvent, "atMs">): void {
    const ev: SpeechEvent = { ...e, atMs: this.now() };
    if (ev.text !== undefined) ev.text = Array.from(ev.text).slice(0, SPEECH_LOG_TEXT_CHARS).join("");
    this.events.push(ev);
    if (this.events.length > this.size) this.events.splice(0, this.events.length - this.size);
    for (const l of this.listeners) l();
  }

  /** Oldest first. */
  list(): readonly SpeechEvent[] {
    return this.events.slice();
  }

  /** Returns the unsubscribe function. */
  subscribe(fn: () => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }
}

/** One line of the panel and the copied text. */
export function formatSpeechEvent(e: SpeechEvent): string {
  const at = `+${(e.atMs / 1000).toFixed(1)} s`;
  const prime = e.prime ? " (unlock utterance)" : "";
  switch (e.kind) {
    case "speak":
      return `${at} speak called${prime}: "${e.text ?? ""}" lang=${e.lang || "(not set)"} voice=${e.voice ?? "(none)"}`;
    case "error":
      return `${at} onerror${prime}: ${e.code ?? "(no code)"}`;
    case "start":
      return `${at} onstart${prime}`;
    case "end":
      return `${at} onend${prime}`;
    case "no-start":
      return `${at} no onstart within 3 s: prompt cancelled`;
    case "watchdog":
      return `${at} watchdog: utterance ended without end/error`;
    case "chime":
      return e.path ? `${at} chime via ${e.path}: ${e.result ?? ""} (AudioContext ${e.ctxBefore ?? "?"} → ${e.ctxAfter ?? "?"})` : `${at} chime played`;
    case "chime-error":
      return `${at} chime ${e.path ?? ""} error: ${e.result ?? ""}`;
    case "ctx-state":
      return `${at} AudioContext state: ${e.state ?? "?"}`;
    case "keep-alive":
      return `${at} silent keep-alive ${e.result ?? ""}`;
  }
}

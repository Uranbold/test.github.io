// NAV-017 change F1 (PO-confirmed P1): hidden voice diagnostics panel, demo-mode build only. It exists to get
// on-device evidence when "speech is not working" on a real phone (NAV-017 AC 48, NAV-007 AC 9 voice lists).
//  - Opened only by a gesture nobody triggers by accident: 5 quick taps on the «Туршилтын горим» picker heading or the
//    replay badge, or a ~1.5 s press on the badge. No URL parameter, no storage key, no request (AC 42/43): the panel
//    is part of the demo chunk (no lazy import, which would be a request) and keeps everything in memory.
//  - Internal, English-only screen (triage item F1, point 4): its labels are the constants in DIAG_TEXT below, not
//    resource keys, and need no glossary terms. It shows no Mongolian text of its own; Mongolian appears only inside
//    data it reports (prompt text from the resources, voice names from the browser).
//  - Read-only towards the replay: it observes AudioOut (event log, decision, AudioContext state) and never changes a
//    decision. Its three test buttons play outside the replay, each inside its own tap handler.
//  - The copied text never contains the page URL (the folder name of the protected demo, D35).
import type { Lang } from "../i18n/i18n";
import { chooseVoice, type AudioOut, type Timers, type VoiceDecision } from "./audio";
import { h } from "./dom";
import { formatSpeechEvent, type SpeechEvent } from "./speechLog";

export const TAP_COUNT = 5;
/** Longest gap between two taps of the 5-tap burst. */
export const TAP_GAP_MS = 600;
export const HOLD_MS = 1_500;
export const HOLD_TOLERANCE_PX = 10;
export const TEST_START_TIMEOUT_MS = 3_000;

/** Every label of the internal English-only screen. */
export const DIAG_TEXT = {
  title: "Voice diagnostics",
  close: "Close",
  note: "If nothing is heard: check the ring/silent switch and the volume.",
  testEn: "Test English speech",
  testDefault: "Test default speech (no voice set)",
  testChime: "Test chime",
  copy: "Copy",
  copied: "Copied. Paste it into your message to the team.",
  copyFailed: "Copy failed: select the text below and copy it by hand.",
  enUtterance: "Turn right in 300 meters",
  defaultUtterance: "Test",
  notRun: "not run",
} as const;

/** 5 taps, each within TAP_GAP_MS of the previous one. */
export class TapBurst {
  private count = 0;
  private last = -Infinity;

  /** Returns true on the tap that completes the burst (and starts counting again). */
  tap(nowMs: number): boolean {
    this.count = nowMs - this.last <= TAP_GAP_MS ? this.count + 1 : 1;
    this.last = nowMs;
    if (this.count < TAP_COUNT) return false;
    this.reset();
    return true;
  }

  reset(): void {
    this.count = 0;
    this.last = -Infinity;
  }
}

/** A press held HOLD_MS without moving more than HOLD_TOLERANCE_PX. */
export class HoldPress {
  private timer: unknown = null;
  private start: { x: number; y: number } | null = null;

  constructor(
    private readonly onHold: () => void,
    private readonly timers: Timers,
  ) {}

  down(x: number, y: number): void {
    this.cancel();
    this.start = { x, y };
    this.timer = this.timers.setTimeout(() => {
      this.timer = null;
      this.start = null;
      this.onHold();
    }, HOLD_MS);
  }

  move(x: number, y: number): void {
    if (this.start && Math.hypot(x - this.start.x, y - this.start.y) > HOLD_TOLERANCE_PX) this.cancel();
  }

  cancel(): void {
    if (this.timer !== null) this.timers.clearTimeout(this.timer);
    this.timer = null;
    this.start = null;
  }
}

export interface VoiceRow {
  name: string;
  lang: string;
  localService: boolean;
  default: boolean;
}

export interface DiagSnapshot {
  userAgent: string;
  hasSpeechSynthesis: boolean;
  hasUtterance: boolean;
  /** "AudioContext", "webkitAudioContext" or null. */
  audioContext: string | null;
  synthState: { speaking: boolean; pending: boolean; paused: boolean } | null;
  uiLang: Lang;
  muted: boolean;
  replayRunning: boolean;
  voices: VoiceRow[];
  voicesChanged: number;
  pick: Record<Lang, VoiceRow | null>;
  decisions: Record<Lang, VoiceDecision>;
  audio: ReturnType<AudioOut["diagnostics"]>;
  events: readonly SpeechEvent[];
  tests: { en: string; default: string; chime: string };
}

const yesNo = (b: boolean): string => (b ? "yes" : "no");

export function formatDecision(d: VoiceDecision): string {
  if (d.state === "pending") return `pending (${d.reason})`;
  if (d.state === "voice") return `${d.lang} voice (${d.reason})`;
  return `chime fallback (${d.reason})`;
}

/** The panel text, which is also what "Copy" copies. It has no input for the page URL, by design. */
export function buildReport(s: DiagSnapshot): string {
  const local = s.voices.filter((v) => v.localService).length;
  const pick = (l: Lang) => {
    const v = s.pick[l];
    return v ? `"${v.name}" (${v.lang})` : "(none)";
  };
  const lines = [
    "NAV-017 demo mode: voice diagnostics",
    "",
    "[Browser]",
    `userAgent: ${s.userAgent}`,
    `speechSynthesis: ${yesNo(s.hasSpeechSynthesis)}; SpeechSynthesisUtterance: ${yesNo(s.hasUtterance)}; AudioContext: ${s.audioContext ?? "no"}`,
    s.synthState ? `speechSynthesis now: speaking=${yesNo(s.synthState.speaking)} pending=${yesNo(s.synthState.pending)} paused=${yesNo(s.synthState.paused)}` : "speechSynthesis now: (missing)",
    "",
    `[Voices] count: ${s.voices.length} (local: ${local}, online: ${s.voices.length - local}); voiceschanged seen while open: ${s.voicesChanged}`,
    ...s.voices.map((v, i) => `${i + 1}. ${v.name} | ${v.lang} | ${v.localService ? "local" : "online"}${v.default ? " | default" : ""}`),
    `Demo would pick for mn: ${pick("mn")}`,
    `Demo would pick for en: ${pick("en")}`,
    `Voice decision mn${s.uiLang === "mn" ? " (UI language)" : ""}: ${formatDecision(s.decisions.mn)}`,
    `Voice decision en${s.uiLang === "en" ? " (UI language)" : ""}: ${formatDecision(s.decisions.en)}`,
    `Voice button: ${s.muted ? "muted" : "on"}; replay running: ${yesNo(s.replayRunning)}`,
    "",
    "[Audio]",
    `AudioContext state: ${s.audio.context}; silent-buffer unlock ran: ${yesNo(s.audio.unlockRan)}${s.audio.unlockError ? ` (error ${s.audio.unlockError})` : ""}; speech primed: ${yesNo(s.audio.speechPrimed)}`,
    "",
    `[Speech events] last ${s.events.length}, oldest first`,
    ...(s.events.length ? s.events.map(formatSpeechEvent) : ["(none yet)"]),
    "",
    "[Tests]",
    `${DIAG_TEXT.testEn}: ${s.tests.en}`,
    `${DIAG_TEXT.testDefault}: ${s.tests.default}`,
    `${DIAG_TEXT.testChime}: ${s.tests.chime}`,
    "",
    DIAG_TEXT.note,
  ];
  return lines.join("\n");
}

export interface DiagEnv {
  userAgent: string;
  speechSynthesis?: SpeechSynthesis | undefined;
  SpeechSynthesisUtterance?: typeof SpeechSynthesisUtterance | undefined;
  audioContextName: string | null;
  timers: Timers;
  now: () => number;
  clipboard?: { writeText(text: string): Promise<void> } | undefined;
}

export interface DiagState {
  lang: Lang;
  muted: boolean;
  replayRunning: boolean;
}

const toRow = (v: SpeechSynthesisVoice): VoiceRow => ({ name: v.name, lang: v.lang, localService: v.localService === true, default: v.default === true });

/** The panel. Built once per page (hidden), opened by the gestures from attach(). */
export class VoiceDiagnostics {
  readonly root: HTMLElement;
  private readonly title: HTMLHeadingElement;
  private readonly report: HTMLPreElement;
  private readonly copyStatus: HTMLElement;
  private readonly results: Record<keyof DiagSnapshot["tests"], HTMLElement>;
  private readonly tests: DiagSnapshot["tests"] = { en: DIAG_TEXT.notRun, default: DIAG_TEXT.notRun, chime: DIAG_TEXT.notRun };
  /** Strong references: some engines drop the events of an utterance that was garbage-collected. */
  private readonly keep: SpeechSynthesisUtterance[] = [];
  private voicesChanged = 0;
  private unsubscribe: (() => void) | null = null;
  private refreshTimer: unknown = null;
  private returnFocus: HTMLElement | null = null;
  private readonly taps = new TapBurst();
  private readonly onVoicesChanged = () => {
    this.voicesChanged++;
    this.render();
  };
  private readonly onKey = (e: KeyboardEvent) => {
    if (e.key === "Escape") this.close();
  };

  constructor(
    private readonly env: DiagEnv,
    private readonly audio: AudioOut,
    private readonly state: () => DiagState,
  ) {
    this.title = h("h2", { id: "demo-diag-title", class: "demo-diag-title", tabindex: "-1" }, DIAG_TEXT.title);
    const close = h("button", { type: "button", class: "demo-diag-btn", "data-testid": "demo-diag-close" }, DIAG_TEXT.close);
    close.addEventListener("click", () => this.close());
    const test = (label: string, id: string, run: () => void) => {
      const btn = h("button", { type: "button", class: "demo-diag-btn", "data-testid": `demo-diag-test-${id}` }, label);
      btn.addEventListener("click", run);
      const out = h("output", { class: "demo-diag-result", "data-testid": `demo-diag-result-${id}` }, DIAG_TEXT.notRun);
      return { row: h("div", { class: "demo-diag-test" }, btn, out), out };
    };
    const en = test(DIAG_TEXT.testEn, "en", () => this.testSpeech("en"));
    const def = test(DIAG_TEXT.testDefault, "default", () => this.testSpeech("default"));
    const chime = test(DIAG_TEXT.testChime, "chime", () => this.testChime());
    this.results = { en: en.out, default: def.out, chime: chime.out };
    const copy = h("button", { type: "button", class: "demo-diag-btn primary", "data-testid": "demo-diag-copy" }, DIAG_TEXT.copy);
    copy.addEventListener("click", () => this.copy());
    this.copyStatus = h("span", { class: "demo-diag-copy-status", role: "status", "data-testid": "demo-diag-copy-status" });
    this.report = h("pre", { class: "demo-diag-report", "data-testid": "demo-diag-report" });
    this.root = h(
      "div",
      { class: "demo-diag", role: "dialog", "aria-modal": "true", "aria-labelledby": "demo-diag-title", lang: "en", "data-testid": "demo-diag", hidden: "" },
      h(
        "div",
        { class: "demo-diag-inner" },
        h("div", { class: "demo-diag-head" }, this.title, close),
        h("p", { class: "demo-diag-note", "data-testid": "demo-diag-note" }, DIAG_TEXT.note),
        h("div", { class: "demo-diag-tests" }, en.row, def.row, chime.row),
        h("div", { class: "demo-diag-copy" }, copy, this.copyStatus),
        this.report,
      ),
    );
  }

  get isOpen(): boolean {
    return !this.root.hidden;
  }

  /** Wires the opening gestures: 5 quick taps on any of `tapTargets`, or a 1.5 s press on `holdTarget`. */
  attach(tapTargets: readonly HTMLElement[], holdTarget: HTMLElement): void {
    for (const t of tapTargets) {
      t.classList.add("demo-diag-trigger");
      t.addEventListener("pointerdown", (e) => {
        if (e.isPrimary && this.taps.tap(this.env.now())) this.open();
      });
    }
    const hold = new HoldPress(() => {
      this.taps.reset();
      this.open();
    }, this.env.timers);
    holdTarget.addEventListener("pointerdown", (e) => {
      if (e.isPrimary) hold.down(e.clientX, e.clientY);
    });
    holdTarget.addEventListener("pointermove", (e) => hold.move(e.clientX, e.clientY));
    for (const type of ["pointerup", "pointercancel", "pointerleave"] as const) holdTarget.addEventListener(type, () => hold.cancel());
    // Android Chrome fires contextmenu during a long touch; it would end the press.
    holdTarget.addEventListener("contextmenu", (e) => e.preventDefault());
  }

  open(): void {
    if (this.isOpen) return;
    if (!this.root.isConnected) document.body.append(this.root);
    this.returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.voicesChanged = 0;
    this.env.speechSynthesis?.addEventListener?.("voiceschanged", this.onVoicesChanged);
    this.unsubscribe = this.audio.log.subscribe(() => this.render());
    document.addEventListener("keydown", this.onKey);
    this.root.hidden = false;
    this.render();
    this.scheduleRefresh();
    this.title.focus({ preventScroll: true });
  }

  close(): void {
    if (!this.isOpen) return;
    this.root.hidden = true;
    this.env.speechSynthesis?.removeEventListener?.("voiceschanged", this.onVoicesChanged);
    this.unsubscribe?.();
    this.unsubscribe = null;
    document.removeEventListener("keydown", this.onKey);
    if (this.refreshTimer !== null) this.env.timers.clearTimeout(this.refreshTimer);
    this.refreshTimer = null;
    this.copyStatus.textContent = "";
    if (this.returnFocus?.isConnected) this.returnFocus.focus({ preventScroll: true });
    this.returnFocus = null;
  }

  /** AudioContext and speechSynthesis states have no reliable events on every browser: re-read once a second. */
  private scheduleRefresh(): void {
    this.refreshTimer = this.env.timers.setTimeout(() => {
      this.refreshTimer = null;
      if (!this.isOpen) return;
      this.render();
      this.scheduleRefresh();
    }, 1_000);
  }

  snapshot(): DiagSnapshot {
    const synth = this.env.speechSynthesis;
    const list = synth ? synth.getVoices() : [];
    const st = this.state();
    return {
      userAgent: this.env.userAgent,
      hasSpeechSynthesis: !!synth,
      hasUtterance: !!this.env.SpeechSynthesisUtterance,
      audioContext: this.env.audioContextName,
      synthState: synth ? { speaking: synth.speaking, pending: synth.pending, paused: synth.paused } : null,
      uiLang: st.lang,
      muted: st.muted,
      replayRunning: st.replayRunning,
      voices: list.map(toRow),
      voicesChanged: this.voicesChanged,
      pick: { mn: nullableRow(chooseVoice(list, "mn")), en: nullableRow(chooseVoice(list, "en")) },
      decisions: { mn: this.audio.decision("mn", list), en: this.audio.decision("en", list) },
      audio: this.audio.diagnostics(),
      events: this.audio.log.list(),
      tests: { ...this.tests },
    };
  }

  private render(): void {
    if (!this.isOpen) return;
    for (const k of ["en", "default", "chime"] as const) {
      if (this.results[k].textContent !== this.tests[k]) this.results[k].textContent = this.tests[k];
    }
    const text = buildReport(this.snapshot());
    // Unchanged text is not re-set, so a manual selection survives the 1 s refresh.
    if (this.report.textContent !== text) this.report.textContent = text;
  }

  private setResult(k: keyof DiagSnapshot["tests"], text: string): void {
    this.tests[k] = text;
    this.render();
  }

  /** "Test English speech" / "Test default speech (no voice set)": synchronous inside the tap handler. */
  private testSpeech(which: "en" | "default"): void {
    const synth = this.env.speechSynthesis;
    const Utterance = this.env.SpeechSynthesisUtterance;
    if (!synth || !Utterance) {
      this.setResult(which, "speechSynthesis is missing in this browser");
      return;
    }
    const t0 = this.env.now();
    const rel = () => `+${((this.env.now() - t0) / 1000).toFixed(1)} s`;
    const steps: string[] = [];
    const push = (s: string) => {
      steps.push(s);
      this.setResult(which, steps.join("; "));
    };
    if (synth.speaking || synth.pending) {
      synth.cancel();
      steps.push("cancelled the utterance that was playing or queued");
    }
    const u = new Utterance(which === "en" ? DIAG_TEXT.enUtterance : DIAG_TEXT.defaultUtterance);
    let voiceText = "no voice, no lang";
    if (which === "en") {
      const v = chooseVoice(synth.getVoices(), "en");
      if (v) {
        u.voice = v;
        u.lang = v.lang;
        voiceText = `voice "${v.name}" (${v.lang})`;
      } else {
        voiceText = "no voice (no local English voice)";
      }
    }
    let started = false;
    let ended = false;
    u.onstart = () => {
      started = true;
      push(`onstart ${rel()}`);
    };
    u.onend = () => {
      ended = true;
      push(`onend ${rel()}`);
    };
    u.onerror = (e: SpeechSynthesisErrorEvent) => {
      ended = true;
      push(`onerror ${e.error} ${rel()}`);
    };
    this.keep.push(u);
    if (this.keep.length > 4) this.keep.shift();
    this.env.timers.setTimeout(() => {
      if (!started && !ended) push("no onstart within 3 s");
    }, TEST_START_TIMEOUT_MS);
    push(`speak called (${voiceText})`);
    synth.speak(u);
  }

  /** "Test chime": AudioOut creates or resumes the AudioContext inside this tap handler and plays the §4.8 chime. */
  private testChime(): void {
    const before = this.audio.diagnostics().context;
    const r = this.audio.testChime();
    if (!r.played) {
      this.setResult("chime", "no AudioContext: Web Audio is missing, nothing played");
      return;
    }
    this.setResult("chime", `chime played; AudioContext before: ${before}`);
    void r.resumed.then(() => this.setResult("chime", `chime played; AudioContext before: ${before}, after resume: ${this.audio.diagnostics().context}`));
  }

  /** "Copy": the report text to the clipboard (Clipboard API, else a hidden textarea and execCommand). */
  private copy(): void {
    const text = buildReport(this.snapshot());
    const ok = () => (this.copyStatus.textContent = DIAG_TEXT.copied);
    const fallback = () => (copyWithTextarea(text, this.root) ? ok() : (this.copyStatus.textContent = DIAG_TEXT.copyFailed));
    const cb = this.env.clipboard;
    if (!cb) {
      fallback();
      return;
    }
    cb.writeText(text).then(ok, fallback);
  }

  /** The UI language changes nothing on this English-only screen except the decision line it marks. */
  langChanged(): void {
    this.render();
  }
}

function nullableRow(v: SpeechSynthesisVoice | null): VoiceRow | null {
  return v ? toRow(v) : null;
}

function copyWithTextarea(text: string, host: HTMLElement): boolean {
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.setAttribute("readonly", "");
  ta.className = "demo-diag-copy-buffer";
  host.append(ta);
  ta.select();
  ta.setSelectionRange(0, text.length);
  try {
    return document.execCommand("copy");
  } catch {
    return false;
  } finally {
    ta.remove();
  }
}

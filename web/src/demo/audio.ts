// Voice, chime and the iOS audio unlock for the demo replay (NAV-017 AC 27–31, 15, 39; ADR-0011 §7; navigation-ux
// §11.4–11.5). Browser objects are injected, so Vitest runs this with stubbed speechSynthesis and Web Audio.
//  - Voice decision: a voice is usable only if speechSynthesis exists and, within 3 s (voiceschanged and a 250 ms poll,
//    because older iOS does not fire voiceschanged reliably), getVoices() has a local voice (localService === true,
//    D78) whose lang matches ^mn([-_]|$) for the Mongolian UI or ^en([-_]|$) for English (en-US preferred). Online
//    voices are ignored. Mongolian text is never given to another voice. The voice found is never stored or sent.
//  - Speech: one utterance at a time with a strong reference; voice, lang, rate, pitch and volume set explicitly; a
//    prompt not started within 3 s is cancelled and dropped; a watchdog ends an utterance that never fires end/error.
//    An error that we did not cause switches to the chime for the rest of the replay.
//  - Chime: navigation-ux §4.8 synthesised with Web Audio (880 Hz 120 ms, 20 ms gap, 1,320 Hz 180 ms, 10 ms ramps,
//    peak gain 0.71 ≈ −3 dBFS, ≈ 330 ms). No sound file, no licence.
//  - iOS Safari (NAV-017 AC 29/48 fix loop, PO iPhone test): a chime scheduled outside a gesture on a suspended or
//    "interrupted" AudioContext is silent. So every chime checks ctx.state and resumes first; a silent zero-gain
//    keep-alive loop runs from «Эхлэх» until the replay ends; and when Web Audio still cannot run, the chime plays
//    through an HTMLAudioElement primed inside «Эхлэх» with the same sound as a WAV generated at runtime (data URI,
//    still no sound file). navigator.audioSession is never set (iOS default category, ring/silent switch respected).
import type { Lang } from "../i18n/i18n";
import type { Speaker, SpokenPrompt } from "../guidance/playbackQueue";
import { SpeechEventLog } from "./speechLog";

export const VOICE_WAIT_MS = 3_000;
export const VOICE_POLL_MS = 250;
export const START_TIMEOUT_MS = 3_000;
export const WATCHDOG_EXTRA_MS = 3_000;
export const WATCHDOG_CAP_MS = 10_000;
export const CHIME_MS = 330;
/** A chime waits at most this long for ctx.resume() (iOS may leave it pending without a gesture), then uses the element. */
export const RESUME_WAIT_MS = 400;
/** localStorage key of the voice choice (AC 30, web/README.md). "1" = muted. */
export const MUTE_KEY = "navmn.voiceMuted";

const LANG_RE: Record<Lang, RegExp> = { mn: /^mn([-_]|$)/i, en: /^en([-_]|$)/i };

type VoiceInfo = Pick<SpeechSynthesisVoice, "lang" | "localService">;

/** The AC 28 language rule alone (the diagnostics panel counts online voices that match it). */
export function matchesLang(v: Pick<SpeechSynthesisVoice, "lang">, lang: Lang): boolean {
  return LANG_RE[lang].test(v.lang ?? "");
}

/** AC 28 in one function, with PO decision D78 (triage item F1): only local voices (`localService === true`) count. */
export function isUsableVoice(v: VoiceInfo, lang: Lang): boolean {
  return matchesLang(v, lang) && v.localService === true;
}

/** The voice for a UI language, or null. English prefers en-US. */
export function chooseVoice<V extends VoiceInfo>(voices: readonly V[], lang: Lang): V | null {
  const usable = voices.filter((v) => isUsableVoice(v, lang));
  if (lang === "en") return usable.find((v) => /^en[-_]us$/i.test(v.lang)) ?? usable[0] ?? null;
  return usable[0] ?? null;
}

export interface Timers {
  setTimeout(fn: () => void, ms: number): unknown;
  clearTimeout(h: unknown): void;
}

/** The part of HTMLAudioElement the chime fallback uses (injected, so tests can fake it). */
export type ChimeElement = Pick<HTMLAudioElement, "play" | "pause" | "muted" | "currentTime" | "preload">;

export interface AudioEnv {
  speechSynthesis?: SpeechSynthesis | undefined;
  SpeechSynthesisUtterance?: typeof SpeechSynthesisUtterance | undefined;
  AudioContext?: typeof AudioContext | undefined;
  /** `new Audio(src)`; missing = no element fallback. */
  createAudio?: ((src: string) => ChimeElement) | undefined;
  timers: Timers;
  storage?: Pick<Storage, "getItem" | "setItem"> | undefined;
  /** Page clock for the diagnostics event log (default performance.now()). */
  now?: () => number;
}

/** Waits up to 3 s for a non-empty voice list. Resolves with whatever is there then (maybe empty). */
export function waitForVoices(env: AudioEnv): Promise<SpeechSynthesisVoice[]> {
  const synth = env.speechSynthesis;
  if (!synth) return Promise.resolve([]);
  const now = synth.getVoices();
  if (now.length > 0) return Promise.resolve(now);
  return new Promise((resolve) => {
    let done = false;
    let poll: unknown = null;
    let limit: unknown = null;
    const finish = () => {
      if (done) return;
      done = true;
      synth.removeEventListener?.("voiceschanged", check);
      if (poll !== null) env.timers.clearTimeout(poll);
      if (limit !== null) env.timers.clearTimeout(limit);
      resolve(synth.getVoices());
    };
    const check = () => {
      if (synth.getVoices().length > 0) finish();
    };
    const tick = () => {
      check();
      if (!done) poll = env.timers.setTimeout(tick, VOICE_POLL_MS);
    };
    synth.addEventListener?.("voiceschanged", check);
    poll = env.timers.setTimeout(tick, VOICE_POLL_MS);
    limit = env.timers.setTimeout(finish, VOICE_WAIT_MS);
  });
}

export function loadMuted(storage: AudioEnv["storage"]): boolean {
  try {
    return storage?.getItem(MUTE_KEY) === "1";
  } catch {
    return false; // storage unavailable: voice on
  }
}

export function saveMuted(storage: AudioEnv["storage"], muted: boolean): void {
  try {
    storage?.setItem(MUTE_KEY, muted ? "1" : "0");
  } catch {
    // storage unavailable: the choice holds for this page only
  }
}

/** Speech duration model for the watchdog (the Android harness model). */
export const modelledMs = (text: string): number => Math.min(5_000, 300 + 55 * text.length);

export interface SpeakerCallbacks {
  /** The prompt ended (or was dropped by the start timeout or the watchdog). */
  done(id: number): void;
  /** Speech failed (or the decision says chime): the core shows A1 once (Mongolian UI). */
  fallback(): void;
}

export type VoiceDecision =
  | { state: "pending"; reason: string }
  | { state: "voice"; lang: Lang; voiceName: string; reason: string }
  | { state: "chime"; reason: string };

/**
 * AC 28/29 decision of one UI language as the diagnostics panel shows it (pending / mn voice / en voice / chime
 * fallback, with the reason). Same conditions as AudioOut.speaks(); the counts explain an empty result.
 */
export function describeVoiceDecision(i: {
  lang: Lang;
  hasSynth: boolean;
  hasUtterance: boolean;
  /** AudioOut.voiceFor(lang): undefined = still deciding. */
  voice: (VoiceInfo & { name?: string }) | null | undefined;
  failed: boolean;
  failCode: string | null;
  /** The current getVoices() list. */
  voices: readonly VoiceInfo[];
}): VoiceDecision {
  if (!i.hasSynth) return { state: "chime", reason: "speechSynthesis is missing in this browser" };
  if (!i.hasUtterance) return { state: "chime", reason: "SpeechSynthesisUtterance is missing in this browser" };
  if (i.voice === undefined) return { state: "pending", reason: "voice decision not finished (waits up to 3 s for the voice list)" };
  if (i.failed) return { state: "chime", reason: `speech error in this replay (${i.failCode ?? "unknown"}): chime for the rest of the replay` };
  if (i.voice) return { state: "voice", lang: i.lang, voiceName: i.voice.name ?? "", reason: `local voice "${i.voice.name ?? ""}" (${i.voice.lang})` };
  const matching = i.voices.filter((v) => matchesLang(v, i.lang));
  const online = matching.filter((v) => v.localService !== true).length;
  const pattern = i.lang === "mn" ? "^mn([-_]|$)" : "^en([-_]|$)";
  if (i.voices.length === 0) return { state: "chime", reason: "the voice list was empty when the decision ended" };
  return {
    state: "chime",
    reason: `no local voice with lang ${pattern}` + (online > 0 ? `; ${online} online voice(s) ignored (D78: local voices only)` : ""),
  };
}

/**
 * Audio output of one page: the voice decisions, one AudioContext and the playback of one prompt at a time.
 * `speaker(cb)` gives the Speaker the guidance core uses for one replay.
 */
export class AudioOut {
  private voices: Partial<Record<Lang, SpeechSynthesisVoice | null>> = {};
  private deciding: Promise<void> | null = null;
  private ctx: AudioContext | null = null;
  private speechPrimed = false;
  /** Chime for the rest of this replay after a speech error (AC 29). */
  private failed = false;
  private current: { id: number; utterance: SpeechSynthesisUtterance | null; cancelled: boolean; timers: unknown[]; chime: (() => void) | null } | null =
    null;
  private cb: SpeakerCallbacks | null = null;
  /** Counters for tests and the real-iPhone checklist (never persisted). */
  readonly stats = { utterances: 0, chimes: 0 };
  /** Last speech and chime events, memory only (diagnostics panel, triage item F1). */
  readonly log: SpeechEventLog;
  /** Read-only facts for the diagnostics panel. Observing only: nothing here changes what is played. */
  private readonly diag = { unlockRan: false, unlockError: null as string | null, failCode: null as string | null };
  /** Silent loop that keeps the AudioContext running during a replay (iOS), or null. */
  private keepAlive: (() => void) | null = null;
  /** The chime fallback element, primed inside «Эхлэх». */
  private element: { el: ChimeElement; state: "priming" | "primed" | "failed" } | null = null;

  constructor(private readonly env: AudioEnv) {
    this.log = new SpeechEventLog(env.now ?? (() => (typeof performance === "undefined" ? 0 : performance.now())));
  }

  /** Diagnostics: AudioContext state, whether the silent-buffer unlock ran, speech priming and the replay failure. */
  diagnostics(): {
    context: AudioContextState | "interrupted" | "not created" | "unavailable";
    unlockRan: boolean;
    unlockError: string | null;
    speechPrimed: boolean;
    failed: boolean;
    failCode: string | null;
    keepAlive: boolean;
    element: "priming" | "primed" | "failed" | "not primed" | "unavailable";
  } {
    return {
      context: this.ctx ? this.ctx.state : this.env.AudioContext ? "not created" : "unavailable",
      unlockRan: this.diag.unlockRan,
      unlockError: this.diag.unlockError,
      speechPrimed: this.speechPrimed,
      failed: this.failed,
      failCode: this.diag.failCode,
      keepAlive: this.keepAlive !== null,
      element: this.element ? this.element.state : this.env.createAudio ? "not primed" : "unavailable",
    };
  }

  /** AC 28 state of `lang` for the diagnostics panel; mirrors speaks(). */
  decision(lang: Lang, voices: readonly VoiceInfo[]): VoiceDecision {
    return describeVoiceDecision({
      lang,
      hasSynth: !!this.env.speechSynthesis,
      hasUtterance: !!this.env.SpeechSynthesisUtterance,
      voice: this.voiceFor(lang),
      failed: this.failed,
      failCode: this.diag.failCode,
      voices,
    });
  }

  /**
   * Diagnostics "Test chime" (inside its own tap handler): create or resume the AudioContext and play the §4.8 chime
   * once. Not counted in `stats`, not part of a replay. Resolves when resume() settles.
   */
  testChime(): { played: boolean; resumed: Promise<void> } {
    const ctx = this.context();
    let resumed: Promise<void> = Promise.resolve();
    try {
      resumed = ctx?.resume ? ctx.resume().catch(() => undefined) : Promise.resolve();
    } catch {
      // resume() unavailable: play anyway
    }
    playChime(ctx);
    return { played: !!ctx, resumed };
  }

  /** AC 28: decide for both UI languages (cheap: one voice list). Re-run on a language change. */
  decide(): Promise<void> {
    this.deciding ??= waitForVoices(this.env).then((list) => {
      this.voices = { mn: chooseVoice(list, "mn"), en: chooseVoice(list, "en") };
      this.deciding = null;
    });
    return this.deciding;
  }

  /** undefined = still deciding. */
  voiceFor(lang: Lang): SpeechSynthesisVoice | null | undefined {
    return lang in this.voices ? (this.voices[lang] ?? null) : undefined;
  }

  /** True when prompts in `lang` are spoken (not chimed) right now. */
  speaks(lang: Lang): boolean {
    return !this.failed && !!this.voiceFor(lang) && !!this.env.speechSynthesis && !!this.env.SpeechSynthesisUtterance;
  }

  /** New replay: a speech error of an earlier replay no longer applies. */
  resetReplay(): void {
    this.failed = false;
    this.diag.failCode = null;
  }

  get fallbackActive(): boolean {
    return this.failed;
  }

  /**
   * «Эхлэх» handler, synchronously before any await (AC 27, ADR-0011 §7 step 1): create or resume the AudioContext and
   * start a one-sample silent buffer. When the voice decision for `lang` is done and the language speaks, the depart
   * prompt's own speak() in the same handler unlocks speech. When the decision is still pending (a voice list that
   * loads late), the depart prompt is a chime, which unlocks nothing; speech is then primed here, inside the gesture,
   * with one empty, zero-volume utterance and no voice set (no Mongolian text), so that iOS allows the later prompts
   * once the list names a usable voice (ADR-0011 §7 Amendment 1, NAV-017-D5).
   */
  unlockForStart(lang: Lang): void {
    const ctx = this.context();
    if (ctx) {
      try {
        void ctx.resume?.().catch(() => undefined);
        const buf = ctx.createBuffer(1, 1, 22_050);
        const src = ctx.createBufferSource();
        src.buffer = buf;
        src.connect(ctx.destination);
        src.start(0);
        this.diag.unlockRan = true;
      } catch (e) {
        // no Web Audio: speech or nothing
        this.diag.unlockError = e instanceof Error ? e.name : "error";
      }
      this.startKeepAlive(ctx);
    }
    this.primeElement();
    if (this.env.speechSynthesis) this.env.speechSynthesis.cancel();
    if (this.voiceFor(lang) === undefined) this.primeSpeech();
  }

  /** «Дуусгах», «Хаах», track end, or the replay clock stopping after arrival: stop the keep-alive. Idempotent. */
  endReplay(): void {
    const stop = this.keepAlive;
    if (!stop) return;
    this.keepAlive = null;
    stop();
    this.log.add({ kind: "keep-alive", result: "stopped" });
  }

  /** A looping one-second silent buffer through a zero-gain node: output is silence, the context stays active. */
  private startKeepAlive(ctx: AudioContext): void {
    if (this.keepAlive) return;
    try {
      const rate = ctx.sampleRate || 22_050;
      const buf = ctx.createBuffer(1, rate, rate);
      const src = ctx.createBufferSource();
      src.buffer = buf;
      src.loop = true;
      const gain = ctx.createGain();
      gain.gain.value = 0;
      src.connect(gain);
      gain.connect(ctx.destination);
      src.start(0);
      this.keepAlive = () => {
        try {
          src.stop();
          src.disconnect();
          gain.disconnect();
        } catch {
          // already stopped
        }
      };
      this.log.add({ kind: "keep-alive", result: "started" });
    } catch (e) {
      this.log.add({ kind: "keep-alive", result: `failed (${errName(e)})` });
    }
  }

  /**
   * Inside «Эхлэх»: play() the chime element muted, then pause and rewind it, so that iOS lets it play later without a
   * gesture. `muted` is used because iOS ignores `volume`. Once per page.
   */
  private primeElement(): void {
    if (this.element || !this.env.createAudio) return;
    let el: ChimeElement;
    try {
      el = this.env.createAudio(chimeWavDataUri());
      el.preload = "auto";
    } catch (e) {
      this.log.add({ kind: "chime-error", path: "element", result: `element not created (${errName(e)})` });
      return;
    }
    const entry: { el: ChimeElement; state: "priming" | "primed" | "failed" } = { el, state: "priming" };
    this.element = entry;
    el.muted = true;
    const settle = (ok: boolean, e?: unknown) => {
      if (entry.state !== "priming") return; // a fallback chime took the element over meanwhile
      entry.state = ok ? "primed" : "failed";
      el.pause();
      el.currentTime = 0;
      el.muted = false;
      if (!ok) this.log.add({ kind: "chime-error", path: "element", result: `element priming failed (${errName(e)})` });
    };
    try {
      Promise.resolve(el.play()).then(
        () => settle(true),
        (e: unknown) => settle(false, e),
      );
    } catch (e) {
      settle(false, e);
    }
  }

  /**
   * Any later user activation (language, «Дууг нээх», «Байршил руу буцах», a tap anywhere): resume a suspended
   * context, and prime speech once for a language that speaks or whose decision is still pending (an empty utterance
   * carries no Mongolian text).
   */
  ensureUnlocked(lang: Lang): void {
    const ctx = this.ctx;
    if (ctx && ctx.state !== "running") void ctx.resume?.().catch(() => undefined);
    if (this.speaks(lang) || this.voiceFor(lang) === undefined) this.primeSpeech();
  }

  /** One empty, zero-volume utterance with no voice set, once per page, never while a prompt plays. */
  private primeSpeech(): void {
    if (this.speechPrimed || !this.env.SpeechSynthesisUtterance || !this.env.speechSynthesis || this.current) return;
    const u = new this.env.SpeechSynthesisUtterance("");
    u.volume = 0;
    u.onstart = () => this.log.add({ kind: "start", prime: true });
    u.onend = () => this.log.add({ kind: "end", prime: true });
    u.onerror = (e: SpeechSynthesisErrorEvent) => this.log.add({ kind: "error", code: e.error, prime: true });
    this.log.add({ kind: "speak", text: "", lang: "", voice: null, prime: true });
    this.env.speechSynthesis.speak(u);
    this.speechPrimed = true;
  }

  /** Page hidden (AC 15): stop and suspend. */
  suspend(): void {
    this.stop();
    void this.ctx?.suspend?.().catch(() => undefined);
  }

  /** Page visible again. */
  resume(): void {
    void this.ctx?.resume?.().catch(() => undefined);
  }

  speaker(cb: SpeakerCallbacks): Speaker {
    this.cb = cb;
    return { play: (p) => this.play(p), stop: () => this.stop() };
  }

  stop(): void {
    const c = this.current;
    this.current = null;
    if (!c) return;
    c.cancelled = true;
    for (const t of c.timers) this.env.timers.clearTimeout(t);
    if (c.utterance) this.env.speechSynthesis?.cancel();
    c.chime?.();
  }

  private context(): AudioContext | null {
    if (this.ctx) return this.ctx;
    const Ctor = this.env.AudioContext;
    if (!Ctor) return null;
    try {
      const ctx = new Ctor();
      this.ctx = ctx;
      ctx.addEventListener?.("statechange", () => this.log.add({ kind: "ctx-state", state: ctx.state }));
    } catch {
      this.ctx = null;
    }
    return this.ctx;
  }

  private finish(id: number): void {
    const c = this.current;
    if (!c || c.id !== id) return;
    this.current = null;
    for (const t of c.timers) this.env.timers.clearTimeout(t);
    this.cb?.done(id);
  }

  private play(p: SpokenPrompt): void {
    this.stop();
    if (this.speaks(p.lang)) this.speak(p);
    else this.chime(p);
  }

  private speak(p: SpokenPrompt): void {
    const synth = this.env.speechSynthesis!;
    const voice = this.voiceFor(p.lang)!;
    const u = new this.env.SpeechSynthesisUtterance!(p.text);
    u.voice = voice;
    u.lang = voice.lang;
    u.rate = 1;
    u.pitch = 1;
    u.volume = 1;
    const c = { id: p.id, utterance: u, cancelled: false, timers: [] as unknown[], chime: null };
    this.current = c;
    let started = false;
    u.onstart = () => {
      started = true;
      this.log.add({ kind: "start" });
    };
    u.onend = () => {
      this.log.add({ kind: "end" });
      if (!c.cancelled) this.finish(p.id);
    };
    u.onerror = (e: SpeechSynthesisErrorEvent) => {
      this.log.add({ kind: "error", code: e.error });
      if (c.cancelled || e.error === "interrupted" || e.error === "canceled") {
        if (!c.cancelled) this.finish(p.id);
        return;
      }
      // AC 29: the rest of the replay uses the chime; this prompt becomes a chime too.
      this.failed = true;
      this.diag.failCode = e.error ?? "error";
      this.cb?.fallback();
      if (this.current === c) {
        this.current = null;
        for (const t of c.timers) this.env.timers.clearTimeout(t);
        this.chime(p);
      }
    };
    c.timers.push(
      this.env.timers.setTimeout(() => {
        if (!started && this.current === c) {
          this.log.add({ kind: "no-start" });
          c.cancelled = true;
          synth.cancel();
          this.finish(p.id);
        }
      }, START_TIMEOUT_MS),
      this.env.timers.setTimeout(() => {
        if (this.current === c) {
          this.log.add({ kind: "watchdog" });
          c.cancelled = true;
          synth.cancel();
          this.finish(p.id);
        }
      }, Math.min(WATCHDOG_CAP_MS, modelledMs(p.text) + WATCHDOG_EXTRA_MS)),
    );
    this.speechPrimed = true;
    this.stats.utterances++;
    this.log.add({ kind: "speak", text: p.text, lang: u.lang, voice: voice.name ?? null });
    synth.speak(u);
  }

  /** One chime per prompt (AC 29). The prompt ends CHIME_MS after the chime starts (or after the attempt failed). */
  private chime(p: SpokenPrompt): void {
    const c = { id: p.id, utterance: null, cancelled: false, timers: [] as unknown[], chime: null as (() => void) | null };
    this.current = c;
    this.stats.chimes++;
    c.chime = this.chimeAttempt(() => {
      if (this.current === c) c.timers.push(this.env.timers.setTimeout(() => this.finish(p.id), CHIME_MS));
    });
  }

  /**
   * Plays the §4.8 chime by the first path that works and logs the attempt once (path, ctx.state before and after,
   * result): Web Audio when the context runs; otherwise resume() first and play when it resolves with "running";
   * otherwise (still not running, rejected, or no answer within RESUME_WAIT_MS) the primed element. Never silently
   * dropped: a chime that no path can play is logged as such. `settled` runs exactly once. Returns a stop function.
   */
  private chimeAttempt(settled: () => void): () => void {
    let stopped = false;
    let stopSound: () => void = () => undefined;
    let done = false;
    const settle = () => {
      if (done) return;
      done = true;
      settled();
    };
    const ctx = this.context();
    const before = ctx ? ctx.state : "none";
    const log = (path: ChimePath, result: string) => this.log.add({ kind: "chime", path, ctxBefore: before, ctxAfter: ctx ? ctx.state : "none", result });
    const viaElement = (why: string) => {
      if (stopped) return settle();
      const r = this.playElement();
      if (r.ok) {
        stopSound = r.stop;
        log("element", `played (${why})`);
      } else log("none", `not played (${why}; element: ${r.reason})`);
      settle();
    };
    if (!ctx) {
      viaElement("no AudioContext");
    } else if (ctx.state === "running") {
      stopSound = playChime(ctx);
      log("webaudio", "played");
      settle();
    } else {
      let waited = false;
      const timer = this.env.timers.setTimeout(() => {
        if (waited) return;
        waited = true;
        viaElement(`resume() no answer in ${RESUME_WAIT_MS} ms`);
      }, RESUME_WAIT_MS);
      const after = (ok: boolean, error?: unknown) => {
        if (waited) return;
        waited = true;
        this.env.timers.clearTimeout(timer);
        if (stopped) return settle();
        if (ok && ctx.state === "running") {
          stopSound = playChime(ctx);
          log("webaudio", "played after resume()");
          settle();
        } else viaElement(ok ? `still ${ctx.state} after resume()` : `resume() rejected: ${errName(error)}`);
      };
      try {
        ctx.resume().then(
          () => after(true),
          (e: unknown) => after(false, e),
        );
      } catch (e) {
        after(false, e);
      }
    }
    return () => {
      stopped = true;
      stopSound();
    };
  }

  /** The chime through the primed element (from the start, unmuted). */
  private playElement(): { ok: true; stop: () => void } | { ok: false; reason: string } {
    const entry = this.element;
    if (!entry) return { ok: false, reason: this.env.createAudio ? "not primed" : "unavailable" };
    if (entry.state === "failed") return { ok: false, reason: "priming failed" };
    const el = entry.el;
    entry.state = "primed"; // a still pending priming must not pause this playback
    try {
      el.muted = false;
      el.currentTime = 0;
      Promise.resolve(el.play()).catch((e: unknown) => this.log.add({ kind: "chime-error", path: "element", result: `play() rejected (${errName(e)})` }));
    } catch (e) {
      return { ok: false, reason: `play() threw (${errName(e)})` };
    }
    return {
      ok: true,
      stop: () => {
        try {
          el.pause();
          el.currentTime = 0;
        } catch {
          // nothing to stop
        }
      },
    };
  }
}

type ChimePath = "webaudio" | "element" | "none";

const errName = (e: unknown): string => (e instanceof Error ? e.name || e.message : typeof e === "string" ? e : "error");

/** navigation-ux §4.8 chime on `ctx`. Returns a stop function. A missing context plays nothing (still one "chime"). */
export function playChime(ctx: AudioContext | null): () => void {
  if (!ctx) return () => undefined;
  try {
    const t0 = ctx.currentTime + 0.01;
    const gain = ctx.createGain();
    gain.connect(ctx.destination);
    const g = gain.gain;
    const PEAK = 0.71;
    const RAMP = 0.01;
    g.setValueAtTime(0, t0);
    // tone 1: 880 Hz, 120 ms
    g.linearRampToValueAtTime(PEAK, t0 + RAMP);
    g.setValueAtTime(PEAK, t0 + 0.12 - RAMP);
    g.linearRampToValueAtTime(0, t0 + 0.12);
    // 20 ms gap, tone 2: 1,320 Hz, 180 ms
    const t1 = t0 + 0.14;
    g.setValueAtTime(0, t1);
    g.linearRampToValueAtTime(PEAK, t1 + RAMP);
    g.setValueAtTime(PEAK, t1 + 0.18 - RAMP);
    g.linearRampToValueAtTime(0, t1 + 0.18);
    const o1 = ctx.createOscillator();
    o1.type = "sine";
    o1.frequency.value = 880;
    o1.connect(gain);
    o1.start(t0);
    o1.stop(t0 + 0.12);
    const o2 = ctx.createOscillator();
    o2.type = "sine";
    o2.frequency.value = 1_320;
    o2.connect(gain);
    o2.start(t1);
    o2.stop(t1 + 0.18);
    return () => {
      try {
        o1.stop();
        o2.stop();
        gain.disconnect();
      } catch {
        // already stopped
      }
    };
  } catch {
    return () => undefined;
  }
}

const CHIME_PEAK = 0.71;
const CHIME_RAMP_S = 0.01;
/** [start s, end s, Hz] of the two tones, after the 10 ms lead the Web Audio path also has. */
const CHIME_TONES: readonly (readonly [number, number, number])[] = [
  [0.01, 0.13, 880],
  [0.15, 0.33, 1_320],
];

/** The §4.8 chime as samples (−1…1): the same tones, 10 ms linear ramps and peak as playChime(). */
export function chimeSamples(sampleRate = 44_100): Float32Array {
  const n = Math.round((CHIME_MS / 1000) * sampleRate);
  const out = new Float32Array(n);
  for (const [a, b, hz] of CHIME_TONES) {
    for (let i = Math.ceil(a * sampleRate); i < Math.min(n, Math.floor(b * sampleRate)); i++) {
      const t = i / sampleRate;
      const env = Math.min(1, (t - a) / CHIME_RAMP_S, (b - t) / CHIME_RAMP_S);
      out[i] = CHIME_PEAK * Math.max(0, env) * Math.sin(2 * Math.PI * hz * (t - a));
    }
  }
  return out;
}

/** Mono 16-bit PCM WAV bytes. */
export function encodeWav(samples: Float32Array, sampleRate: number): Uint8Array {
  const bytes = new Uint8Array(44 + samples.length * 2);
  const v = new DataView(bytes.buffer);
  const ascii = (o: number, t: string) => {
    for (let i = 0; i < t.length; i++) v.setUint8(o + i, t.charCodeAt(i));
  };
  ascii(0, "RIFF");
  v.setUint32(4, 36 + samples.length * 2, true);
  ascii(8, "WAVE");
  ascii(12, "fmt ");
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true); // PCM
  v.setUint16(22, 1, true); // mono
  v.setUint32(24, sampleRate, true);
  v.setUint32(28, sampleRate * 2, true);
  v.setUint16(32, 2, true);
  v.setUint16(34, 16, true);
  ascii(36, "data");
  v.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) v.setInt16(44 + i * 2, Math.round(Math.max(-1, Math.min(1, samples[i]!)) * 32_767), true);
  return bytes;
}

let wavUri: string | null = null;

/** The chime as a `data:audio/wav;base64,…` URI (generated once, ≈ 39 KB of text). */
export function chimeWavDataUri(): string {
  if (wavUri) return wavUri;
  const bytes = encodeWav(chimeSamples(44_100), 44_100);
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  wavUri = `data:audio/wav;base64,${btoa(bin)}`;
  return wavUri;
}

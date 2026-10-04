// NAV-017 AC 27–31: voice decision, speech output and the D23 chime fallback with stubbed speechSynthesis and Web Audio.
import { describe, expect, test } from "vitest";
import type { SpokenPrompt } from "../guidance/playbackQueue";
import { AudioOut, chimeSamples, chimeWavDataUri, chooseVoice, CHIME_MS, describeVoiceDecision, encodeWav, isUsableVoice, loadMuted, MUTE_KEY, RESUME_WAIT_MS, saveMuted, waitForVoices, type AudioEnv, type ChimeElement } from "./audio";

function timers() {
  let now = 0;
  let seq = 0;
  const pending = new Map<number, { at: number; fn: () => void }>();
  return {
    t: {
      setTimeout: (fn: () => void, ms: number) => {
        pending.set(++seq, { at: now + ms, fn });
        return seq;
      },
      clearTimeout: (h: unknown) => void pending.delete(h as number),
    },
    advance(ms: number) {
      const end = now + ms;
      for (;;) {
        const next = [...pending.entries()].filter(([, v]) => v.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
        if (!next) break;
        pending.delete(next[0]);
        now = next[1].at;
        next[1].fn();
      }
      now = end;
    },
  };
}

class Utt {
  voice: { lang: string } | null = null;
  lang = "";
  rate = 0;
  pitch = 0;
  volume = 0;
  onstart: ((e: unknown) => void) | null = null;
  onend: ((e: unknown) => void) | null = null;
  onerror: ((e: { error: string }) => void) | null = null;
  constructor(public text: string) {}
}

function env(voiceLangs: string[] | (() => string[]), opts: { fireStart?: boolean; online?: string[] } = {}) {
  const tm = timers();
  const spoken: Utt[] = [];
  let cancels = 0;
  const listeners: (() => void)[] = [];
  let oscillators = 0;
  const list = () => [
    ...(typeof voiceLangs === "function" ? voiceLangs() : voiceLangs).map((lang) => ({ lang, name: lang, localService: true })),
    ...(opts.online ?? []).map((lang) => ({ lang, name: `${lang} Online`, localService: false })),
  ];
  const synth = {
    getVoices: list,
    speak: (u: Utt) => {
      spoken.push(u);
      if (opts.fireStart !== false && u.text) tm.t.setTimeout(() => u.onstart?.({}), 5);
    },
    cancel: () => void cancels++,
    addEventListener: (_: string, f: () => void) => listeners.push(f),
    removeEventListener: () => undefined,
  };
  class Param {
    value = 0;
    setValueAtTime() {}
    linearRampToValueAtTime() {}
  }
  class N {
    connect() {}
    disconnect() {}
  }
  class Ctx {
    state = "running";
    currentTime = 0;
    destination = {};
    resume() {
      return Promise.resolve();
    }
    suspend() {
      return Promise.resolve();
    }
    createGain() {
      return Object.assign(new N(), { gain: new Param() });
    }
    createOscillator() {
      return Object.assign(new N(), { frequency: new Param(), type: "sine", start: () => void oscillators++, stop: () => undefined });
    }
    createBuffer() {
      return {};
    }
    createBufferSource() {
      return Object.assign(new N(), { start: () => undefined, stop: () => undefined, buffer: null, loop: false });
    }
  }
  const store = new Map<string, string>();
  const e: AudioEnv = {
    speechSynthesis: synth as unknown as SpeechSynthesis,
    SpeechSynthesisUtterance: Utt as unknown as typeof SpeechSynthesisUtterance,
    AudioContext: Ctx as unknown as typeof AudioContext,
    timers: tm.t,
    storage: { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => void store.set(k, v) },
  };
  return { e, tm, spoken, get cancels() { return cancels; }, fireVoicesChanged: () => listeners.forEach((f) => f()), get oscillators() { return oscillators; }, store };
}

const prompt = (id: number, text: string, lang: "mn" | "en" = "mn"): SpokenPrompt => ({ id, text, lang, cls: "maneuver", maneuver: { gen: 0, step: id }, triggerAtMs: 0 });
const CYRILLIC = /[Ѐ-ӿ]/;

describe("NAV-017 AC 28 voice decision", () => {
  test("usable voice regex and en-US preference", () => {
    const L = (lang: string) => ({ lang, localService: true });
    expect(isUsableVoice(L("mn-MN"), "mn")).toBe(true);
    expect(isUsableVoice(L("mn"), "mn")).toBe(true);
    expect(isUsableVoice(L("MN_mn"), "mn")).toBe(true);
    expect(isUsableVoice(L("mni-IN"), "mn")).toBe(false);
    expect(isUsableVoice(L("ru-RU"), "mn")).toBe(false);
    expect(chooseVoice([L("en-GB"), L("en-US")], "en")?.lang).toBe("en-US");
    expect(chooseVoice([L("en-GB")], "en")?.lang).toBe("en-GB");
    expect(chooseVoice([L("en-US"), L("ru-RU")], "mn")).toBeNull();
  });

  test("an empty list waits for voiceschanged or the 250 ms poll, at most 3 s", async () => {
    let langs: string[] = [];
    const x = env(() => langs);
    const p = waitForVoices(x.e);
    x.tm.advance(1_000);
    langs = ["mn-MN"];
    x.tm.advance(300);
    expect((await p).map((v) => v.lang)).toEqual(["mn-MN"]);
    const y = env([]);
    const q = waitForVoices(y.e);
    y.tm.advance(3_100);
    expect(await q).toEqual([]);
  });
});

describe("NAV-017 AC 27–30 speech and the D23 chime", () => {
  test("Mongolian UI without a Mongolian voice: 0 speak calls with Mongolian text, one chime per prompt", async () => {
    const x = env(["en-US", "ru-RU"]);
    const a = new AudioOut(x.e);
    const decided = a.decide();
    await decided;
    const done: number[] = [];
    let fallbacks = 0;
    const sp = a.speaker({ done: (id) => done.push(id), fallback: () => fallbacks++ });
    a.unlockForStart("mn");
    sp.play(prompt(1, "Өмнө зүг рүү явна уу"));
    x.tm.advance(400);
    sp.play(prompt(2, "300 метрт баруун тийш эргэнэ үү"));
    x.tm.advance(400);
    expect(x.spoken.filter((u) => CYRILLIC.test(u.text))).toEqual([]);
    expect(a.stats.chimes).toBe(2);
    expect(x.oscillators).toBe(4); // two tones per chime
    expect(done).toEqual([1, 2]);
    expect(a.speaks("mn")).toBe(false);
    expect(a.speaks("en")).toBe(true);
  });

  test("with a Mongolian voice: voice and lang set explicitly, rate/pitch/volume 1, done on end", async () => {
    const x = env(["mn-MN"]);
    const a = new AudioOut(x.e);
    await a.decide();
    const done: number[] = [];
    const sp = a.speaker({ done: (id) => done.push(id), fallback: () => undefined });
    sp.play(prompt(7, "Өмнө зүг рүү явна уу"));
    const u = x.spoken[0]!;
    expect(u.voice?.lang).toBe("mn-MN");
    expect(u.lang).toBe("mn-MN");
    expect([u.rate, u.pitch, u.volume]).toEqual([1, 1, 1]);
    x.tm.advance(10);
    u.onend?.({});
    expect(done).toEqual([7]);
  });

  test("a speech error switches to the chime for the rest of the replay and reports the fallback once per error", async () => {
    const x = env(["mn-MN"]);
    const a = new AudioOut(x.e);
    await a.decide();
    const done: number[] = [];
    let fallbacks = 0;
    const sp = a.speaker({ done: (id) => done.push(id), fallback: () => fallbacks++ });
    sp.play(prompt(1, "Өмнө зүг рүү явна уу"));
    x.spoken[0]!.onerror?.({ error: "synthesis-failed" });
    expect(fallbacks).toBe(1);
    expect(a.stats.chimes).toBe(1);
    x.tm.advance(400);
    expect(done).toEqual([1]);
    sp.play(prompt(2, "Зүүн тийш эргэнэ үү"));
    expect(x.spoken.length).toBe(1);
    expect(a.stats.chimes).toBe(2);
    a.resetReplay();
    expect(a.speaks("mn")).toBe(true);
  });

  test("our own cancel() is not an error; stop() ends speech and chime", async () => {
    const x = env(["mn-MN"]);
    const a = new AudioOut(x.e);
    await a.decide();
    let fallbacks = 0;
    const done: number[] = [];
    const sp = a.speaker({ done: (id) => done.push(id), fallback: () => fallbacks++ });
    sp.play(prompt(1, "Өмнө зүг рүү явна уу"));
    sp.stop();
    x.spoken[0]!.onerror?.({ error: "interrupted" });
    expect(fallbacks).toBe(0);
    expect(x.cancels).toBeGreaterThan(0);
    expect(done).toEqual([]); // the queue cleared itself; no done for a stopped prompt
  });

  test("watchdog: an utterance without end/error is finished after its modelled duration + 3 s; no start in 3 s drops it", async () => {
    const x = env(["mn-MN"]);
    const a = new AudioOut(x.e);
    await a.decide();
    const done: number[] = [];
    const sp = a.speaker({ done: (id) => done.push(id), fallback: () => undefined });
    sp.play(prompt(1, "Зүүн тийш эргэнэ үү")); // 300 + 55 × 19 = 1,345 ms → watchdog at 4,345 ms
    x.tm.advance(4_000);
    expect(done).toEqual([]);
    x.tm.advance(500);
    expect(done).toEqual([1]);
    const y = env(["mn-MN"], { fireStart: false });
    const b = new AudioOut(y.e);
    await b.decide();
    const done2: number[] = [];
    const sp2 = b.speaker({ done: (id) => done2.push(id), fallback: () => undefined });
    sp2.play(prompt(2, "Зүүн тийш эргэнэ үү"));
    y.tm.advance(3_050);
    expect(done2).toEqual([2]);
  });

  test("English UI without an English voice: chime (the core shows no notice for English)", async () => {
    const x = env(["mn-MN"]);
    const a = new AudioOut(x.e);
    await a.decide();
    const sp = a.speaker({ done: () => undefined, fallback: () => undefined });
    sp.play(prompt(1, "Head south", "en"));
    expect(x.spoken.length).toBe(0);
    expect(a.stats.chimes).toBe(1);
  });

  test("AC 30: the mute choice is stored and read back; unavailable storage means voice on", () => {
    const x = env([]);
    expect(loadMuted(x.e.storage)).toBe(false);
    saveMuted(x.e.storage, true);
    expect(x.store.get(MUTE_KEY)).toBe("1");
    expect(loadMuted(x.e.storage)).toBe(true);
    const broken = { getItem: () => { throw new Error("blocked"); }, setItem: () => { throw new Error("blocked"); } };
    expect(loadMuted(broken)).toBe(false);
    expect(() => saveMuted(broken, true)).not.toThrow();
  });

  test("ADR-0011 §7 Amendment 1 (NAV-017-D5): «Эхлэх» while the voice decision is pending → one empty, zero-volume priming utterance with no voice, inside the handler; the depart prompt is a chime; once the late list names a Mongolian voice the next prompt is spoken with it", async () => {
    let langs: string[] = [];
    const x = env(() => langs);
    const a = new AudioOut(x.e);
    const decided = a.decide();
    expect(a.voiceFor("mn")).toBeUndefined();
    const sp = a.speaker({ done: () => undefined, fallback: () => undefined });
    a.unlockForStart("mn");
    expect(x.spoken.map((u) => ({ text: u.text, volume: u.volume, voice: u.voice }))).toEqual([{ text: "", volume: 0, voice: null }]);
    sp.play(prompt(1, "Өмнө зүг рүү явна уу"));
    expect(a.stats.chimes).toBe(1);
    expect(x.spoken.filter((u) => CYRILLIC.test(u.text))).toEqual([]);
    // the list fills within the 3 s window
    x.tm.advance(400);
    langs = ["mn-MN"];
    x.tm.advance(300);
    await decided;
    expect(a.speaks("mn")).toBe(true);
    sp.play(prompt(2, "1 километрт зүүн тийш эргэнэ үү"));
    x.tm.advance(10);
    expect(x.spoken.at(-1)?.text).toBe("1 километрт зүүн тийш эргэнэ үү");
    expect(x.spoken.at(-1)?.voice?.lang).toBe("mn-MN");
    expect(a.stats.chimes).toBe(1);
    // primed once per page: a later tap adds no second priming utterance
    a.ensureUnlocked("mn");
    expect(x.spoken.filter((u) => u.text === "").length).toBe(1);
  });

  test("decision done, language speaks: «Эхлэх» adds no priming utterance (the depart prompt's own speak() unlocks); decision done, no voice: none either", async () => {
    const x = env(["mn-MN"]);
    const a = new AudioOut(x.e);
    await a.decide();
    a.unlockForStart("mn");
    expect(x.spoken).toEqual([]);
    const y = env(["en-US"]);
    const b = new AudioOut(y.e);
    await b.decide();
    b.unlockForStart("mn");
    expect(y.spoken).toEqual([]);
  });

  test("AC 27: the unlock creates/resumes the AudioContext synchronously; no speechSynthesis means chime only", () => {
    const x = env([]);
    const a = new AudioOut({ ...x.e, speechSynthesis: undefined, SpeechSynthesisUtterance: undefined });
    a.unlockForStart("mn");
    const sp = a.speaker({ done: () => undefined, fallback: () => undefined });
    sp.play(prompt(1, "Өмнө зүг рүү явна уу"));
    expect(a.stats.chimes).toBe(1);
    expect(x.oscillators).toBe(2);
  });
});

describe("NAV-017 AC 28 with D78: only local voices count (triage item F1)", () => {
  test("an online voice is never usable, in both UI languages", () => {
    expect(isUsableVoice({ lang: "mn-MN", localService: false }, "mn")).toBe(false);
    expect(isUsableVoice({ lang: "en-US", localService: false }, "en")).toBe(false);
    expect(isUsableVoice({ lang: "mn-MN", localService: true }, "mn")).toBe(true);
    // a missing flag is not "local"
    expect(isUsableVoice({ lang: "mn-MN" } as SpeechSynthesisVoice, "mn")).toBe(false);
  });

  test("chooseVoice skips online voices: a local en-GB wins over an online en-US; online mn gives null", () => {
    const voices = [
      { lang: "mn-MN", name: "Microsoft Yesui Online (Natural)", localService: false },
      { lang: "en-US", name: "Google US English", localService: false },
      { lang: "en-GB", name: "Daniel", localService: true },
    ];
    expect(chooseVoice(voices, "mn")).toBeNull();
    expect(chooseVoice(voices, "en")?.name).toBe("Daniel");
  });

  test("Edge desktop case: only online mn voices → Mongolian prompts chime, 0 Mongolian speak calls", async () => {
    const x = env(["en-US"], { online: ["mn-MN"] });
    const a = new AudioOut(x.e);
    await a.decide();
    expect(a.voiceFor("mn")).toBeNull();
    expect(a.speaks("mn")).toBe(false);
    expect(a.speaks("en")).toBe(true);
    a.speaker({ done: () => undefined, fallback: () => undefined }).play(prompt(1, "300 метрт баруун тийш эргэнэ үү"));
    expect(x.spoken.filter((u) => CYRILLIC.test(u.text))).toEqual([]);
    expect(a.stats.chimes).toBe(1);
    const list = x.e.speechSynthesis!.getVoices();
    expect(a.decision("mn", list)).toEqual({ state: "chime", reason: "no local voice with lang ^mn([-_]|$); 1 online voice(s) ignored (D78: local voices only)" });
    expect(a.decision("en", list)).toMatchObject({ state: "voice", lang: "en", voiceName: "en-US" });
  });

  test("describeVoiceDecision: pending, missing API, speech error, empty list", () => {
    const base = { lang: "mn" as const, hasSynth: true, hasUtterance: true, voice: null, failed: false, failCode: null, voices: [] };
    expect(describeVoiceDecision({ ...base, voice: undefined }).state).toBe("pending");
    expect(describeVoiceDecision({ ...base, hasSynth: false })).toEqual({ state: "chime", reason: "speechSynthesis is missing in this browser" });
    expect(describeVoiceDecision({ ...base, voices: [] }).reason).toBe("the voice list was empty when the decision ended");
    const v = { lang: "mn-MN", name: "Local MN", localService: true };
    expect(describeVoiceDecision({ ...base, voice: v, voices: [v], failed: true, failCode: "synthesis-failed" })).toMatchObject({ state: "chime", reason: expect.stringContaining("synthesis-failed") });
    expect(describeVoiceDecision({ ...base, voice: v, voices: [v] })).toMatchObject({ state: "voice", lang: "mn", voiceName: "Local MN" });
  });
});

describe("Triage item F1: AudioOut feeds the diagnostics event log (memory only)", () => {
  test("speak, onstart, onend, an error and the chime fallback are logged; the unlock is reported", async () => {
    const x = env(["mn-MN"]);
    const a = new AudioOut({ ...x.e, now: () => 1234 });
    await a.decide();
    expect(a.diagnostics()).toMatchObject({ context: "not created", unlockRan: false, failed: false });
    a.unlockForStart("mn");
    expect(a.diagnostics()).toMatchObject({ context: "running", unlockRan: true });
    const sp = a.speaker({ done: () => undefined, fallback: () => undefined });
    const long = "300 метрт баруун тийш эргэнэ үү, дараа нь шууд явна уу";
    sp.play(prompt(1, long));
    x.tm.advance(10); // onstart after 5 ms
    x.spoken[0]!.onend?.({});
    sp.play(prompt(2, "Тойрог"));
    x.spoken[1]!.onerror?.({ error: "synthesis-failed" });
    const log = a.log.list();
    expect(log.map((e) => e.kind)).toEqual(["keep-alive", "speak", "start", "end", "speak", "error", "chime"]);
    expect(log[1]).toMatchObject({ atMs: 1234, text: long.slice(0, 30), lang: "mn-MN", voice: "mn-MN" });
    expect(log[1]!.text).toHaveLength(30);
    expect(log[5]).toMatchObject({ kind: "error", code: "synthesis-failed" });
    expect(log[6]).toMatchObject({ kind: "chime", path: "webaudio", ctxBefore: "running", ctxAfter: "running", result: "played" });
    expect(a.diagnostics()).toMatchObject({ failed: true, failCode: "synthesis-failed" });
    a.resetReplay();
    expect(a.diagnostics()).toMatchObject({ failed: false, failCode: null });
  });

  test("the unlock utterance and a prompt with no onstart within 3 s are logged", async () => {
    const x = env(["mn-MN"], { fireStart: false });
    const a = new AudioOut(x.e);
    a.unlockForStart("mn"); // decision pending → priming utterance
    expect(a.log.list()).toEqual([expect.objectContaining({ kind: "keep-alive", result: "started" }), expect.objectContaining({ kind: "speak", prime: true, text: "", voice: null })]);
    await a.decide();
    a.speaker({ done: () => undefined, fallback: () => undefined }).play(prompt(1, "Тойрог"));
    x.tm.advance(3_000);
    expect(a.log.list().map((e) => e.kind)).toEqual(["keep-alive", "speak", "speak", "no-start"]);
  });

  test("testChime creates the context and plays one chime outside the replay counters", async () => {
    const x = env([]);
    const a = new AudioOut(x.e);
    const r = a.testChime();
    await r.resumed;
    expect(r.played).toBe(true);
    expect(x.oscillators).toBe(2);
    expect(a.stats.chimes).toBe(0);
    expect(a.log.list()).toEqual([]);
    const none = new AudioOut({ ...x.e, AudioContext: undefined });
    expect(none.testChime().played).toBe(false);
    expect(none.diagnostics().context).toBe("unavailable");
  });
});

/**
 * iOS Safari model (NAV-017 AC 29/48 fix loop, PO iPhone test): the AudioContext runs inside «Эхлэх» and later drops
 * to "suspended" or "interrupted" outside a gesture. `resumeMode` says what resume() does then.
 */
function iosEnv(resumeMode: "running" | "stays" | "reject" | "pending") {
  const tm = timers();
  const nodes = { oscillators: 0, sources: [] as { loop: boolean; started: boolean; stopped: boolean; gain: number | null }[] };
  let ctxRef: FakeCtx | null = null;
  let inGesture = false;
  class Param {
    value = 1;
    setValueAtTime() {}
    linearRampToValueAtTime() {}
  }
  class N {
    out: unknown = null;
    connect(n: unknown) {
      this.out = n;
    }
    disconnect() {}
  }
  class FakeCtx {
    state: string = "suspended";
    sampleRate = 44_100;
    currentTime = 0;
    destination = {};
    resumeCalls = 0;
    private listeners: (() => void)[] = [];
    constructor() {
      // eslint-disable-next-line @typescript-eslint/no-this-alias
      ctxRef = this;
    }
    addEventListener(type: string, f: () => void) {
      if (type === "statechange") this.listeners.push(f);
    }
    setState(s: string) {
      if (this.state === s) return; // browsers fire statechange on a change only
      this.state = s;
      this.listeners.forEach((f) => f());
    }
    resume() {
      this.resumeCalls++;
      if (inGesture) {
        this.setState("running");
        return Promise.resolve();
      }
      if (resumeMode === "running") {
        this.setState("running");
        return Promise.resolve();
      }
      if (resumeMode === "stays") return Promise.resolve();
      if (resumeMode === "reject") return Promise.reject(new DOMException("not allowed", "NotAllowedError"));
      return new Promise<void>(() => undefined);
    }
    suspend() {
      return Promise.resolve();
    }
    createGain() {
      return Object.assign(new N(), { gain: new Param() });
    }
    createOscillator() {
      return Object.assign(new N(), { frequency: new Param(), type: "sine", start: () => void nodes.oscillators++, stop: () => undefined });
    }
    createBuffer() {
      return {};
    }
    createBufferSource() {
      const rec = { loop: false, started: false, stopped: false, gain: null as number | null };
      nodes.sources.push(rec);
      const src = Object.assign(new N(), {
        buffer: null,
        start: () => void (rec.started = true),
        stop: () => void (rec.stopped = true),
      });
      Object.defineProperty(src, "loop", { get: () => rec.loop, set: (v: boolean) => void (rec.loop = v) });
      const connect = src.connect.bind(src);
      src.connect = (n: unknown) => {
        connect(n);
        const g = (n as { gain?: Param }).gain;
        if (g) rec.gain = g.value;
      };
      return src;
    }
  }
  const elements: FakeElement[] = [];
  class FakeElement implements ChimeElement {
    muted = false;
    currentTime = 0;
    preload: ChimeElement["preload"] = "";
    plays: { muted: boolean }[] = [];
    pauses = 0;
    constructor(public src: string) {
      elements.push(this);
    }
    play() {
      this.plays.push({ muted: this.muted });
      return Promise.resolve();
    }
    pause() {
      this.pauses++;
    }
  }
  const e: AudioEnv = {
    speechSynthesis: undefined,
    SpeechSynthesisUtterance: undefined,
    AudioContext: FakeCtx as unknown as typeof AudioContext,
    createAudio: (src) => new FakeElement(src),
    timers: tm.t,
  };
  return {
    e,
    tm,
    nodes,
    elements,
    get ctx() {
      return ctxRef!;
    },
    /** Runs `fn` as if inside a user gesture (resume() succeeds). */
    gesture(fn: () => void) {
      inGesture = true;
      try {
        fn();
      } finally {
        inGesture = false;
      }
    },
  };
}

const flush = () => new Promise<void>((r) => setTimeout(r, 0));

/** «Эхлэх» + depart chime, then the context leaves "running" (suspended, then interrupted), then 7 more prompts. */
async function replayR1(mode: Parameters<typeof iosEnv>[0]) {
  const x = iosEnv(mode);
  const a = new AudioOut(x.e);
  await a.decide();
  const done: number[] = [];
  const sp = a.speaker({ done: (id) => done.push(id), fallback: () => undefined });
  x.gesture(() => {
    a.unlockForStart("mn");
    sp.play(prompt(1, "Өмнө зүг рүү явна уу"));
  });
  await flush();
  x.tm.advance(CHIME_MS + 10);
  for (let id = 2; id <= 8; id++) {
    x.ctx.setState(id % 2 === 0 ? "suspended" : "interrupted");
    sp.play(prompt(id, "Баруун тийш эргэнэ үү"));
    await flush();
    x.tm.advance(RESUME_WAIT_MS + CHIME_MS + 10);
    await flush();
  }
  return { x, a, done };
}

describe("NAV-017 AC 29 on iOS Safari: one chime per prompt when the AudioContext is suspended or interrupted", () => {
  test("resume() works outside the gesture: 8 prompts → 8 Web Audio chimes, resume() called before each later chime", async () => {
    const { x, a, done } = await replayR1("running");
    const chimes = a.log.list().filter((e) => e.kind === "chime");
    expect(a.stats.chimes).toBe(8);
    expect(done).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
    expect(x.nodes.oscillators).toBe(16);
    expect(x.ctx.resumeCalls).toBeGreaterThanOrEqual(8); // unlock + one per later chime
    // the log keeps the last 10 events: the last chimes show the resume path and the state before
    expect(chimes.at(-1)).toMatchObject({ path: "webaudio", ctxBefore: "suspended", ctxAfter: "running", result: "played after resume()" });
    expect(x.elements[0]!.plays.filter((p) => !p.muted)).toEqual([]);
  });

  test("resume() rejects: every later prompt plays through the primed element, none is dropped", async () => {
    const { x, a, done } = await replayR1("reject");
    expect(a.stats.chimes).toBe(8);
    expect(done).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
    expect(x.nodes.oscillators).toBe(2); // only the depart chime ran on Web Audio
    const el = x.elements[0]!;
    expect(el.plays[0]).toEqual({ muted: true }); // priming inside «Эхлэх»
    expect(el.plays.slice(1)).toEqual(Array.from({ length: 7 }, () => ({ muted: false })));
    expect(el.src.startsWith("data:audio/wav;base64,")).toBe(true);
    expect(a.log.list().filter((e) => e.kind === "chime").at(-1)).toMatchObject({ path: "element", ctxAfter: "suspended", result: "played (resume() rejected: NotAllowedError)" });
  });

  test("resume() resolves but the context stays suspended, or never answers: the element plays (the latter after RESUME_WAIT_MS)", async () => {
    const stays = await replayR1("stays");
    expect(stays.x.elements[0]!.plays.filter((p) => !p.muted)).toHaveLength(7);
    expect(stays.a.log.list().filter((e) => e.kind === "chime").at(-1)?.result).toBe("played (still suspended after resume())");
    const pending = await replayR1("pending");
    expect(pending.done).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
    expect(pending.x.elements[0]!.plays.filter((p) => !p.muted)).toHaveLength(7);
    expect(pending.a.log.list().filter((e) => e.kind === "chime").at(-1)?.result).toBe(`played (resume() no answer in ${RESUME_WAIT_MS} ms)`);
  });

  test("no element fallback available and resume fails: the attempt is still counted and logged as not played", async () => {
    const x = iosEnv("reject");
    const a = new AudioOut({ ...x.e, createAudio: undefined });
    await a.decide();
    const done: number[] = [];
    const sp = a.speaker({ done: (id) => done.push(id), fallback: () => undefined });
    x.gesture(() => a.unlockForStart("mn"));
    x.ctx.setState("interrupted");
    sp.play(prompt(1, "Тойрог"));
    await flush();
    x.tm.advance(CHIME_MS + 10);
    expect(done).toEqual([1]);
    expect(a.log.list().at(-1)).toMatchObject({ kind: "chime", path: "none", ctxBefore: "interrupted", result: "not played (resume() rejected: NotAllowedError; element: unavailable)" });
  });

  test("stop() while waiting for resume(): nothing plays afterwards and no done is reported", async () => {
    const x = iosEnv("pending");
    const a = new AudioOut(x.e);
    await a.decide();
    const done: number[] = [];
    const sp = a.speaker({ done: (id) => done.push(id), fallback: () => undefined });
    x.gesture(() => a.unlockForStart("mn"));
    await flush();
    x.ctx.setState("interrupted");
    sp.play(prompt(1, "Тойрог"));
    sp.stop();
    x.tm.advance(RESUME_WAIT_MS + CHIME_MS + 10);
    await flush();
    expect(done).toEqual([]);
    expect(x.elements[0]!.plays.filter((p) => !p.muted)).toEqual([]);
    expect(x.nodes.oscillators).toBe(0);
  });

  test("the silent keep-alive starts in «Эхлэх» (looping, zero gain) and stops with the replay; statechange is logged", async () => {
    const x = iosEnv("running");
    const a = new AudioOut(x.e);
    await a.decide();
    expect(a.diagnostics()).toMatchObject({ keepAlive: false, element: "not primed" });
    x.gesture(() => a.unlockForStart("mn"));
    await flush();
    const loops = x.nodes.sources.filter((s) => s.loop);
    expect(loops).toHaveLength(1);
    expect(loops[0]).toMatchObject({ started: true, stopped: false, gain: 0 });
    expect(a.diagnostics()).toMatchObject({ keepAlive: true, element: "primed" });
    // a second «Эхлэх» in the same replay does not start a second loop
    x.gesture(() => a.unlockForStart("mn"));
    expect(x.nodes.sources.filter((s) => s.loop)).toHaveLength(1);
    x.ctx.setState("interrupted");
    a.endReplay();
    a.endReplay(); // idempotent
    expect(loops[0]!.stopped).toBe(true);
    expect(a.diagnostics().keepAlive).toBe(false);
    const kinds = a.log.list().map((e) => (e.kind === "keep-alive" ? `keep-alive ${e.result}` : e.kind === "ctx-state" ? `state ${e.state}` : e.kind));
    expect(kinds).toEqual(["state running", "keep-alive started", "state interrupted", "keep-alive stopped"]);
    // the next replay starts a new loop
    x.gesture(() => a.unlockForStart("mn"));
    expect(x.nodes.sources.filter((s) => s.loop)).toHaveLength(2);
  });

  test("element priming: muted play() inside «Эхлэх», then paused, rewound and unmuted; once per page", async () => {
    const x = iosEnv("running");
    const a = new AudioOut(x.e);
    x.gesture(() => a.unlockForStart("mn"));
    await flush();
    const el = x.elements[0]!;
    expect(el.plays).toEqual([{ muted: true }]);
    expect(el.pauses).toBe(1);
    expect([el.muted, el.currentTime]).toEqual([false, 0]);
    x.gesture(() => a.unlockForStart("mn"));
    expect(x.elements).toHaveLength(1);
  });
});

describe("NAV-017 §4.8 chime as WAV for the element fallback", () => {
  test("44.1 kHz mono 16-bit, ≈ 330 ms, peak ≈ −3 dBFS, 880 Hz then 1,320 Hz, silent gap and lead", () => {
    const s = chimeSamples(44_100);
    expect(s.length).toBe(Math.round(0.33 * 44_100));
    const peak = s.reduce((m, v) => Math.max(m, Math.abs(v)), 0);
    expect(peak).toBeGreaterThan(0.7);
    expect(peak).toBeLessThanOrEqual(0.71);
    const zeroCrossings = (a: number, b: number) => {
      let n = 0;
      for (let i = Math.round(a * 44_100) + 1; i < Math.round(b * 44_100); i++) if (Math.sign(s[i]!) !== Math.sign(s[i - 1]!) && s[i] !== 0) n++;
      return n / 2 / (b - a);
    };
    expect(Math.abs(zeroCrossings(0.03, 0.11) - 880)).toBeLessThan(20);
    expect(Math.abs(zeroCrossings(0.17, 0.31) - 1_320)).toBeLessThan(20);
    expect(s.slice(0, 441).every((v) => v === 0)).toBe(true); // 10 ms lead
    expect(s.slice(Math.ceil(0.13 * 44_100) + 1, Math.floor(0.15 * 44_100)).every((v) => v === 0)).toBe(true); // 20 ms gap
    const wav = encodeWav(s, 44_100);
    const v = new DataView(wav.buffer);
    expect(String.fromCharCode(...wav.subarray(0, 4), ...wav.subarray(8, 12))).toBe("RIFFWAVE");
    expect([v.getUint16(22, true), v.getUint32(24, true), v.getUint16(34, true), v.getUint32(40, true)]).toEqual([1, 44_100, 16, s.length * 2]);
    expect(chimeWavDataUri()).toBe(chimeWavDataUri());
    expect(atob(chimeWavDataUri().split(",")[1]!).length).toBe(wav.length);
  });
});

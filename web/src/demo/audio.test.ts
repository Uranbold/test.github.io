// NAV-017 AC 27–31: voice decision, speech output and the D23 chime fallback with stubbed speechSynthesis and Web Audio.
import { describe, expect, test } from "vitest";
import type { SpokenPrompt } from "../guidance/playbackQueue";
import { AudioOut, chooseVoice, describeVoiceDecision, isUsableVoice, loadMuted, MUTE_KEY, saveMuted, waitForVoices, type AudioEnv } from "./audio";

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
      return Object.assign(new N(), { start: () => undefined, buffer: null });
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
    expect(log.map((e) => e.kind)).toEqual(["speak", "start", "end", "speak", "error", "chime"]);
    expect(log[0]).toMatchObject({ atMs: 1234, text: long.slice(0, 30), lang: "mn-MN", voice: "mn-MN" });
    expect(log[0]!.text).toHaveLength(30);
    expect(log[4]).toMatchObject({ kind: "error", code: "synthesis-failed" });
    expect(a.diagnostics()).toMatchObject({ failed: true, failCode: "synthesis-failed" });
    a.resetReplay();
    expect(a.diagnostics()).toMatchObject({ failed: false, failCode: null });
  });

  test("the unlock utterance and a prompt with no onstart within 3 s are logged", async () => {
    const x = env(["mn-MN"], { fireStart: false });
    const a = new AudioOut(x.e);
    a.unlockForStart("mn"); // decision pending → priming utterance
    expect(a.log.list()).toEqual([expect.objectContaining({ kind: "speak", prime: true, text: "", voice: null })]);
    await a.decide();
    a.speaker({ done: () => undefined, fallback: () => undefined }).play(prompt(1, "Тойрог"));
    x.tm.advance(3_000);
    expect(a.log.list().map((e) => e.kind)).toEqual(["speak", "speak", "no-start"]);
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

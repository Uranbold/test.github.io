// NAV-017 AC 27–31: voice decision, speech output and the D23 chime fallback with stubbed speechSynthesis and Web Audio.
import { describe, expect, test } from "vitest";
import type { SpokenPrompt } from "../guidance/playbackQueue";
import { AudioOut, chooseVoice, isUsableVoice, loadMuted, MUTE_KEY, saveMuted, waitForVoices, type AudioEnv } from "./audio";

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

function env(voiceLangs: string[] | (() => string[]), opts: { fireStart?: boolean } = {}) {
  const tm = timers();
  const spoken: Utt[] = [];
  let cancels = 0;
  const listeners: (() => void)[] = [];
  let oscillators = 0;
  const list = () => (typeof voiceLangs === "function" ? voiceLangs() : voiceLangs).map((lang) => ({ lang, name: lang, localService: true }));
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
    expect(isUsableVoice({ lang: "mn-MN" }, "mn")).toBe(true);
    expect(isUsableVoice({ lang: "mn" }, "mn")).toBe(true);
    expect(isUsableVoice({ lang: "MN_mn" }, "mn")).toBe(true);
    expect(isUsableVoice({ lang: "mni-IN" }, "mn")).toBe(false);
    expect(isUsableVoice({ lang: "ru-RU" }, "mn")).toBe(false);
    expect(chooseVoice([{ lang: "en-GB" }, { lang: "en-US" }], "en")?.lang).toBe("en-US");
    expect(chooseVoice([{ lang: "en-GB" }], "en")?.lang).toBe("en-GB");
    expect(chooseVoice([{ lang: "en-US" }, { lang: "ru-RU" }], "mn")).toBeNull();
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
    a.unlockForStart();
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

  test("AC 27: the unlock creates/resumes the AudioContext synchronously; no speechSynthesis means chime only", () => {
    const x = env([]);
    const a = new AudioOut({ ...x.e, speechSynthesis: undefined, SpeechSynthesisUtterance: undefined });
    a.unlockForStart();
    const sp = a.speaker({ done: () => undefined, fallback: () => undefined });
    sp.play(prompt(1, "Өмнө зүг рүү явна уу"));
    expect(a.stats.chimes).toBe(1);
    expect(x.oscillators).toBe(2);
  });
});

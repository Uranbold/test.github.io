// @vitest-environment jsdom
// Triage item F1: hidden voice diagnostics panel (opening gestures, report text, test buttons, copy).
import { afterEach, describe, expect, test } from "vitest";
import { AudioOut, type AudioEnv } from "./audio";
import { buildReport, DIAG_TEXT, HOLD_MS, HoldPress, TAP_GAP_MS, TapBurst, VoiceDiagnostics, type DiagEnv } from "./diagnostics";

function fakeTimers() {
  let now = 0;
  let seq = 0;
  const pending = new Map<number, { at: number; fn: () => void }>();
  return {
    now: () => now,
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
  voice: { name: string; lang: string } | null = null;
  lang = "";
  volume = 1;
  onstart: (() => void) | null = null;
  onend: (() => void) | null = null;
  onerror: ((e: { error: string }) => void) | null = null;
  constructor(public text: string) {}
}

const VOICES = [
  { name: "Samantha", lang: "en-US", localService: true, default: true },
  { name: "Microsoft Yesui Online (Natural)", lang: "mn-MN", localService: false, default: false },
];

function setup(opts: { clipboard?: DiagEnv["clipboard"] } = {}) {
  const tm = fakeTimers();
  const spoken: Utt[] = [];
  const listeners = new Set<() => void>();
  let voices = VOICES.slice();
  const synth = {
    speaking: false,
    pending: false,
    paused: false,
    getVoices: () => voices,
    speak: (u: Utt) => void spoken.push(u),
    cancel: () => undefined,
    addEventListener: (_: string, f: () => void) => void listeners.add(f),
    removeEventListener: (_: string, f: () => void) => void listeners.delete(f),
  };
  const audioEnv: AudioEnv = {
    speechSynthesis: synth as unknown as SpeechSynthesis,
    SpeechSynthesisUtterance: Utt as unknown as typeof SpeechSynthesisUtterance,
    timers: tm.t,
    now: tm.now,
  };
  const audio = new AudioOut(audioEnv);
  const env: DiagEnv = {
    userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 Version/18.6 Mobile/15E148 Safari/604.1",
    speechSynthesis: audioEnv.speechSynthesis,
    SpeechSynthesisUtterance: audioEnv.SpeechSynthesisUtterance,
    audioContextName: null,
    timers: tm.t,
    now: tm.now,
    clipboard: opts.clipboard,
  };
  const panel = new VoiceDiagnostics(env, audio, () => ({ lang: "mn", muted: false, replayRunning: false }));
  const heading = document.createElement("h2");
  const badge = document.createElement("span");
  document.body.append(heading, badge);
  panel.attach([heading, badge], badge);
  return {
    tm,
    spoken,
    panel,
    heading,
    badge,
    audio,
    setVoices: (v: typeof VOICES) => (voices = v),
    fireVoicesChanged: () => listeners.forEach((f) => f()),
    get listenerCount() {
      return listeners.size;
    },
  };
}

const pointer = (type: string, x = 0, y = 0) => Object.assign(new Event(type), { isPrimary: true, clientX: x, clientY: y });
const tid = (id: string) => document.querySelector<HTMLElement>(`[data-testid="${id}"]`)!;

afterEach(() => {
  document.body.replaceChildren();
});

describe("opening gestures", () => {
  test("TapBurst: 5 taps each ≤ 600 ms apart open; a longer pause restarts the count", () => {
    const b = new TapBurst();
    expect([0, 500, 1000, 1500].map((t) => b.tap(t))).toEqual([false, false, false, false]);
    expect(b.tap(1500 + TAP_GAP_MS)).toBe(true);
    // 4 taps, a pause, then 4 more: never 5 in a row
    const c = new TapBurst();
    const r = [0, 100, 200, 300, 1300, 1400, 1500, 1600].map((t) => c.tap(t));
    expect(r.includes(true)).toBe(false);
  });

  test("HoldPress: 1.5 s without moving fires; lifting or moving > 10 px cancels", () => {
    const tm = fakeTimers();
    let fired = 0;
    const h = new HoldPress(() => fired++, tm.t);
    h.down(10, 10);
    tm.advance(HOLD_MS - 1);
    h.cancel();
    tm.advance(10);
    h.down(10, 10);
    h.move(25, 10);
    tm.advance(HOLD_MS);
    expect(fired).toBe(0);
    h.down(10, 10);
    h.move(15, 14);
    tm.advance(HOLD_MS);
    expect(fired).toBe(1);
  });

  test("5 quick taps on the heading open the panel; fewer do not; no storage and no URL change", () => {
    const s = setup();
    const href = location.href;
    for (let i = 0; i < 4; i++) s.heading.dispatchEvent(pointer("pointerdown"));
    expect(s.panel.isOpen).toBe(false);
    s.heading.dispatchEvent(pointer("pointerdown"));
    expect(s.panel.isOpen).toBe(true);
    expect(tid("demo-diag").getAttribute("role")).toBe("dialog");
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(location.href).toBe(href);
    tid("demo-diag-close").click();
    expect(s.panel.isOpen).toBe(false);
  });

  test("a 1.5 s press on the badge opens the panel; a short press does not", () => {
    const s = setup();
    s.badge.dispatchEvent(pointer("pointerdown"));
    s.tm.advance(1000);
    s.badge.dispatchEvent(pointer("pointerup"));
    s.tm.advance(1000);
    expect(s.panel.isOpen).toBe(false);
    s.badge.dispatchEvent(pointer("pointerdown"));
    s.tm.advance(HOLD_MS);
    expect(s.panel.isOpen).toBe(true);
  });
});

describe("panel contents", () => {
  test("voice list with local/online, picks, D78 decision reason, refresh on voiceschanged", async () => {
    const s = setup();
    s.panel.open();
    const report = () => tid("demo-diag-report").textContent ?? "";
    expect(report()).toContain("[Voices] count: 2 (local: 1, online: 1)");
    expect(report()).toContain("1. Samantha | en-US | local | default");
    expect(report()).toContain("2. Microsoft Yesui Online (Natural) | mn-MN | online");
    expect(report()).toContain('Demo would pick for mn: (none)');
    expect(report()).toContain('Demo would pick for en: "Samantha" (en-US)');
    expect(report()).toContain("Voice decision mn (UI language): pending");
    await s.audio.decide();
    s.tm.advance(1_000); // 1 s refresh
    expect(report()).toContain("Voice decision mn (UI language): chime fallback (no local voice with lang ^mn([-_]|$); 1 online voice(s) ignored (D78: local voices only))");
    expect(report()).toContain('Voice decision en: en voice (local voice "Samantha" (en-US))');
    s.setVoices([...VOICES, { name: "Daniel", lang: "en-GB", localService: true, default: false }]);
    s.fireVoicesChanged();
    expect(report()).toContain("count: 3 (local: 2, online: 1); voiceschanged seen while open: 1");
    expect(report()).toContain(DIAG_TEXT.note);
    expect(tid("demo-diag-note").textContent).toBe("If nothing is heard: check the ring/silent switch and the volume.");
    s.panel.close();
    expect(s.listenerCount).toBe(0);
  });

  test("Test English speech: chosen local en voice, start/end shown", () => {
    const s = setup();
    s.panel.open();
    tid("demo-diag-test-en").click();
    const u = s.spoken[0]!;
    expect(u.text).toBe("Turn right in 300 meters");
    expect(u.voice?.name).toBe("Samantha");
    expect(u.lang).toBe("en-US");
    s.tm.advance(200);
    u.onstart?.();
    s.tm.advance(1500);
    u.onend?.();
    expect(tid("demo-diag-result-en").textContent).toBe('speak called (voice "Samantha" (en-US)); onstart +0.2 s; onend +1.7 s');
  });

  test("Test English speech with no local English voice sets no voice; no onstart within 3 s is reported", () => {
    const s = setup();
    s.setVoices([{ name: "Google US English", lang: "en-US", localService: false, default: false }]);
    s.panel.open();
    tid("demo-diag-test-en").click();
    expect(s.spoken[0]!.voice).toBeNull();
    s.tm.advance(3_000);
    expect(tid("demo-diag-result-en").textContent).toBe("speak called (no voice (no local English voice)); no onstart within 3 s");
  });

  test("Test default speech sets neither voice nor lang; an error code is shown", () => {
    const s = setup();
    s.panel.open();
    tid("demo-diag-test-default").click();
    const u = s.spoken[0]!;
    expect(u.text).toBe("Test");
    expect(u.voice).toBeNull();
    expect(u.lang).toBe("");
    u.onerror?.({ error: "not-allowed" });
    expect(tid("demo-diag-result-default").textContent).toBe("speak called (no voice, no lang); onerror not-allowed +0.0 s");
  });

  test("Test chime without Web Audio says so", () => {
    const s = setup();
    s.panel.open();
    tid("demo-diag-test-chime").click();
    expect(tid("demo-diag-result-chime").textContent).toBe("no AudioContext: Web Audio is missing, nothing played");
  });

  test("Copy puts the report on the clipboard; it never contains the page URL", async () => {
    const copied: string[] = [];
    const s = setup({ clipboard: { writeText: (t) => (copied.push(t), Promise.resolve()) } });
    history.replaceState(null, "", "/secret-demo-folder/");
    s.panel.open();
    tid("demo-diag-copy").click();
    await Promise.resolve();
    await Promise.resolve();
    expect(copied).toHaveLength(1);
    expect(copied[0]).toBe(tid("demo-diag-report").textContent);
    expect(copied[0]).toContain("userAgent: Mozilla/5.0 (iPhone;");
    expect(copied[0]).not.toContain("secret-demo-folder");
    expect(copied[0]).not.toContain(location.host || "localhost");
    expect(copied[0]).not.toMatch(/https?:\/\//);
    expect(tid("demo-diag-copy-status").textContent).toBe(DIAG_TEXT.copied);
  });

  test("buildReport lists the speech events and the AudioContext facts", () => {
    const text = buildReport({
      userAgent: "UA",
      hasSpeechSynthesis: true,
      hasUtterance: true,
      audioContext: "AudioContext",
      synthState: { speaking: false, pending: false, paused: false },
      uiLang: "en",
      muted: true,
      replayRunning: true,
      voices: [],
      voicesChanged: 0,
      pick: { mn: null, en: null },
      decisions: { mn: { state: "chime", reason: "r" }, en: { state: "pending", reason: "p" } },
      audio: { context: "suspended", unlockRan: true, unlockError: null, speechPrimed: true, failed: false, failCode: null, keepAlive: true, element: "primed" },
      events: [{ atMs: 1500, kind: "error", code: "interrupted" }],
      tests: { en: "not run", default: "not run", chime: "not run" },
    });
    expect(text).toContain("AudioContext state: suspended; silent-buffer unlock ran: yes; speech primed: yes");
    expect(text).toContain("Chime keep-alive running: yes; chime element fallback: primed");
    expect(text).toContain("+1.5 s onerror: interrupted");
    expect(text).toContain("Voice decision en (UI language): pending (p)");
    expect(text).toContain("Voice button: muted; replay running: yes");
  });
});

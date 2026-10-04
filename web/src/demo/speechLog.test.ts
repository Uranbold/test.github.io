// Triage item F1: the diagnostics speech event log (last 10, memory only, prompt text cut to 30 characters).
import { describe, expect, test } from "vitest";
import { formatSpeechEvent, SPEECH_LOG_SIZE, SPEECH_LOG_TEXT_CHARS, SpeechEventLog } from "./speechLog";

describe("SpeechEventLog", () => {
  test("keeps only the last 10 events, oldest first, with the page-clock time", () => {
    let t = 0;
    const log = new SpeechEventLog(() => (t += 100));
    for (let i = 0; i < 13; i++) log.add({ kind: "error", code: `e${i}` });
    const list = log.list();
    expect(SPEECH_LOG_SIZE).toBe(10);
    expect(list).toHaveLength(10);
    expect(list.map((e) => e.code)).toEqual(["e3", "e4", "e5", "e6", "e7", "e8", "e9", "e10", "e11", "e12"]);
    expect(list[0]!.atMs).toBe(400);
  });

  test("speak text is cut to 30 characters (code points, Cyrillic safe); list() is a copy", () => {
    const log = new SpeechEventLog(() => 0);
    const text = "Тойрогт орж, 2-р гарцаар гарна уу. Дараа нь 300 метр шууд";
    log.add({ kind: "speak", text, lang: "mn-MN", voice: "Local" });
    const e = log.list()[0]!;
    expect(SPEECH_LOG_TEXT_CHARS).toBe(30);
    expect(e.text).toBe(Array.from(text).slice(0, 30).join(""));
    (log.list() as unknown[]).length = 0;
    expect(log.list()).toHaveLength(1);
  });

  test("subscribers are told about every event and can unsubscribe", () => {
    const log = new SpeechEventLog(() => 0);
    let n = 0;
    const off = log.subscribe(() => n++);
    log.add({ kind: "start" });
    off();
    log.add({ kind: "end" });
    expect(n).toBe(1);
  });

  test("formatting of each event kind", () => {
    expect(formatSpeechEvent({ atMs: 12_340, kind: "speak", text: "Turn", lang: "en-US", voice: "Samantha" })).toBe('+12.3 s speak called: "Turn" lang=en-US voice=Samantha');
    expect(formatSpeechEvent({ atMs: 0, kind: "speak", text: "", lang: "", voice: null, prime: true })).toBe('+0.0 s speak called (unlock utterance): "" lang=(not set) voice=(none)');
    expect(formatSpeechEvent({ atMs: 1000, kind: "error", code: "not-allowed" })).toBe("+1.0 s onerror: not-allowed");
    expect(formatSpeechEvent({ atMs: 1000, kind: "start" })).toBe("+1.0 s onstart");
    expect(formatSpeechEvent({ atMs: 1000, kind: "end" })).toBe("+1.0 s onend");
    expect(formatSpeechEvent({ atMs: 1000, kind: "no-start" })).toBe("+1.0 s no onstart within 3 s: prompt cancelled");
    expect(formatSpeechEvent({ atMs: 1000, kind: "chime" })).toBe("+1.0 s chime played");
  });
});

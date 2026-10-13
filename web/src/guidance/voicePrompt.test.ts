// NAV-016 section I (AC 47) on the web: the D95 coordinated change in the voice text port. Mongolian km rounding (D92,
// D94, D102), the spoken-form layer (C3a, D93) and the segment list (ADR-0010 decision 2), byte for byte with the
// Android VoiceText (AC 23 table, AC 22a/22b boundaries, AC 24 join rule, AC 25 scans). English unchanged (D96).
import { describe, expect, test } from "vitest";
import { readFileSync } from "node:fs";
import { I18n, type Lang } from "../i18n/i18n";
import { maneuverKey, MANEUVER_KEYS, type KeyResult } from "../route/instructions";
import { renderVoice, spokenText, VOICE_INVENTORY, voicePrompt, type Segment, type VoiceContent } from "./voiceText";
import { REPO } from "./testing/harness";

const t = (lang: Lang) => {
  const i = new I18n(lang);
  return (k: Parameters<I18n["t"]>[0]) => i.t(k, lang);
};
const k = (type: string, modifier?: string, exit?: number, bearing?: number): KeyResult => maneuverKey({ type, modifier, exit, bearing_after: bearing });
const man = (key: KeyResult, d: number, then: KeyResult | null = null): VoiceContent => ({ kind: "maneuver", key, distanceM: d, then });
const mn = (c: VoiceContent) => voicePrompt(c, "mn", t("mn"));
const en = (c: VoiceContent) => voicePrompt(c, "en", t("en"));
const tokens = (s: Segment[]) => s.map((x) => `${x.key}:${x.take}`).join(" ");

/** glossary-c3-spoken.tsv: key → spoken_mn (the BA table, read directly, not through the generated JSON). */
const TSV = new Map(
  readFileSync(REPO + "docs/requirements/glossary-c3-spoken.tsv", "utf8")
    .split("\n")
    .slice(1)
    .filter((l) => l)
    .map((l) => l.split("\t"))
    .map((c) => [c[0]!, c[5]!] as const),
);

describe("NAV-016 AC 23 (web port): display, spoken and segments", () => {
  // [input, display, spoken, segment tokens]
  const rows: [VoiceContent, string, string, string][] = [
    [man(k("turn", "right"), 300), "300 метрт баруун тийш эргэнэ үү", "гурван зуун метрт баруун тийш эргэнэ үү", "dist.m.300:final instr.turn.right:final"],
    [man(k("turn", "right"), 980), "1 километрт баруун тийш эргэнэ үү", "нэг километрт баруун тийш эргэнэ үү", "dist.km.1:final instr.turn.right:final"],
    [man(k("fork", "left"), 1249), "1 километрт зүүн талаа барина уу", "нэг километрт зүүн талаа барина уу", "dist.km.1:final instr.keep.left:final"],
    [man(k("fork", "left"), 1250), "1,5 километрт зүүн талаа барина уу", "нэг хагас километрт зүүн талаа барина уу", "dist.km.1_5:final instr.keep.left:final"],
    [man(k("turn", "left"), 1750), "2 километрт зүүн тийш эргэнэ үү", "хоёр километрт зүүн тийш эргэнэ үү", "dist.km.2:final instr.turn.left:final"],
    [man(k("turn", "left"), 20), "Зүүн тийш эргэнэ үү", "зүүн тийш эргэнэ үү", "instr.turn.left:final"],
    [
      man(k("roundabout", "right", 2), 200, k("turn", "slight right")),
      "200 метрт тойрогт ороод хоёрдугаар гарцаар гарна уу, дараа нь бага зэрэг баруун тийш эргэнэ үү",
      "хоёр зуун метрт тойрогт ороод хоёрдугаар гарцаар гарна уу, дараа нь бага зэрэг баруун тийш эргэнэ үү",
      "dist.m.200:final instr.roundabout.exit.2:nonFinal join.then:final instr.turn.slightRight:final",
    ],
    [
      { kind: "depart", key: k("depart", undefined, undefined, 0), then: k("turn", "right") },
      "Хойд зүг рүү явна уу, дараа нь баруун тийш эргэнэ үү",
      "хойд зүг рүү явна уу, дараа нь баруун тийш эргэнэ үү",
      "instr.depart.n:nonFinal join.then:final instr.turn.right:final",
    ],
    [{ kind: "approaching", distanceM: 200 }, "200 метрт очих газартаа хүрнэ", "хоёр зуун метрт очих газартаа хүрнэ", "dist.m.200:final tail.approach:final"],
    [{ kind: "continueOn", distanceM: 2_480 }, "2 километр үргэлжлүүлэн явна уу", "хоёр километр үргэлжлүүлэн явна уу", "cont.km.2:final tail.continue:final"],
    [{ kind: "continueOn", distanceM: 99_499 }, "99 километр үргэлжлүүлэн явна уу", "ерэн есөн километр үргэлжлүүлэн явна уу", "cont.km.99:final tail.continue:final"],
    [{ kind: "continueOn", distanceM: 99_500 }, "100 километр үргэлжлүүлэн явна уу", "зуун километр үргэлжлүүлэн явна уу", "cont.km.100:final tail.continue:final"],
    [{ kind: "continueOn", distanceM: 123_000 }, "120 километр үргэлжлүүлэн явна уу", "зуун хорин километр үргэлжлүүлэн явна уу", "cont.km.120:final tail.continue:final"],
    [{ kind: "continueOn", distanceM: 994_999 }, "990 километр үргэлжлүүлэн явна уу", "есөн зуун ерэн километр үргэлжлүүлэн явна уу", "cont.km.990:final tail.continue:final"],
    [{ kind: "arrival", key: k("arrive") }, "Та очих газартаа ирлээ", "та очих газартаа ирлээ", "instr.arrive:final"],
  ];
  test.each(rows.map((r, i) => [i, ...r] as const))("row %i", (_i, c, display, spoken, segs) => {
    const p = mn(c)!;
    expect(p.display).toBe(display);
    expect(p.spoken).toBe(spoken);
    expect(tokens(p.segments)).toBe(segs);
  });

  test("continue on at 995,000 m: no Mongolian prompt (D102); English unchanged (D96)", () => {
    expect(mn({ kind: "continueOn", distanceM: 995_000 })).toBeNull();
    expect(mn({ kind: "continueOn", distanceM: 1_000_000 })).toBeNull();
    expect(en({ kind: "continueOn", distanceM: 995_000 })!.display).toBe("Continue for 995 kilometers");
  });

  test("AC 22c: the voice says «1,5 километрт» at 1,300 m (the banner keeps «1,3 км-т»)", () => {
    expect(mn(man(k("fork", "left"), 1300))!.spoken).toBe("нэг хагас километрт зүүн талаа барина уу");
  });
});

describe("NAV-016 AC 22a / 22b boundaries", () => {
  const turn = k("turn", "right");
  test("prefix km rounding (D92)", () => {
    const cases: [number, string][] = [
      [994.99, "dist.km.1"],
      [995, "dist.km.1"],
      [1249.99, "dist.km.1"],
      [1250, "dist.km.1_5"],
      [1749.99, "dist.km.1_5"],
      [1750, "dist.km.2"],
      [974.99, "dist.m.950"],
      [975, "dist.km.1"],
      [29.99, "instr.turn.right"],
      [30, "dist.m.30"],
      [94.99, "dist.m.90"],
      [95, "dist.m.100"],
    ];
    for (const [d, key] of cases) expect(mn(man(turn, d))!.segments[0]!.key, `${d} m`).toBe(key);
  });
  test("continue-on rounding (D94)", () => {
    const cases: [number, string | null][] = [
      [99_499, "cont.km.99"],
      [99_500, "cont.km.100"],
      [994_999, "cont.km.990"],
      [995_000, null],
    ];
    for (const [d, key] of cases) expect(mn({ kind: "continueOn", distanceM: d })?.segments[0]!.key ?? null, `${d} m`).toBe(key);
  });
});

/** Every key the generator can put in a prompt (single, chained as {first} and {second}). */
function allKeys(): KeyResult[] {
  return [
    ...MANEUVER_KEYS.filter((key) => key !== "roundabout.exit").map((key) => ({ key })),
    ...Array.from({ length: 12 }, (_, i) => ({ key: "roundabout.exit" as const, params: { n: i + 1 } })),
    { key: "roundabout.exit" as const },
  ];
}

describe("NAV-016 AC 24 / AC 25 / AC 47 over the web domain", () => {
  test("spoken = joined segment texts; 0 digits, 0 Latin; every distance phrase is a TSV value; every segment is in the inventory", () => {
    const keys = allKeys();
    const contents: VoiceContent[] = [];
    for (const key of keys) {
      if (key.key.startsWith("arrive")) {
        contents.push({ kind: "arrival", key });
        continue;
      }
      for (let d = 0; d <= 2000; d += 7) contents.push(man(key, d));
      for (const second of keys) if (!second.key.startsWith("arrive")) contents.push(man(key, 200, second));
      if (key.key.startsWith("depart")) for (const second of keys) if (!second.key.startsWith("arrive")) contents.push({ kind: "depart", key, then: second });
    }
    for (let d = 30; d <= 500; d++) contents.push({ kind: "approaching", distanceM: d });
    for (let d = 2000; d < 1_000_000; d += 997) contents.push({ kind: "continueOn", distanceM: d });
    const bad: string[] = [];
    for (const c of contents) {
      const p = mn(c);
      if (!p) continue;
      for (const s of p.segments) if (VOICE_INVENTORY.spoken(s.key, s.take) === undefined) bad.push(`${JSON.stringify(c)}: ${s.key}:${s.take} not in the inventory`);
      if (spokenText(p.segments, VOICE_INVENTORY) !== p.spoken) bad.push(`${JSON.stringify(c)}: spoken «${p.spoken}» breaks the AC 24 join`);
      if (/[0-9A-Za-z]/.test(p.spoken)) bad.push(`«${p.spoken}» has a digit or Latin letter`);
      for (const s of p.segments) if (/^(dist|cont)\./.test(s.key) && !TSV.has(s.key)) bad.push(`${s.key} is not a TSV key`);
      const dist = p.segments.find((s) => /^(dist|cont)\./.test(s.key));
      if (dist && !p.spoken.startsWith(TSV.get(dist.key)!)) bad.push(`«${p.spoken}» does not start with the TSV value of ${dist.key}`);
    }
    expect(contents.length).toBeGreaterThan(5000);
    expect(bad.slice(0, 20)).toEqual([]);
  });

  test("English: spoken = display, no segments (D96); renderVoice equals the display", () => {
    for (const c of [man(k("turn", "right"), 1300), { kind: "continueOn", distanceM: 2_480 } as VoiceContent, man(k("roundabout", "right", 2), 200, k("turn", "left"))]) {
      const p = en(c)!;
      expect(p.spoken).toBe(p.display);
      expect(p.segments).toEqual([]);
      expect(renderVoice(c, "en", t("en"))).toBe(p.display);
    }
    expect(en(man(k("turn", "right"), 1300))!.display).toBe("In 1.3 kilometers, turn right");
    expect(en({ kind: "continueOn", distanceM: 2_480 })!.display).toBe("Continue for 2.5 kilometers");
  });
});

describe("NAV-016 D102 in the web schedule", () => {
  test("a continue-on of 995 km or more is silent in Mongolian; the next manoeuvre's prompts still play", async () => {
    const { VoiceScheduler } = await import("./voiceSchedule");
    const step = (key: KeyResult, distance: number) => ({ maneuver: { type: "turn" }, key, location: { lat: 47.9, lon: 106.9 }, street: "", distance, duration: distance / 14 });
    const plan = {
      generation: 0,
      steps: [step(k("depart", undefined, undefined, 0), 300), step(k("turn", "right"), 1_000_000), step(k("turn", "left"), 500), step(k("arrive"), 0)],
      distance: 1_000_800,
      duration: 70_000,
      geometry: [],
      end: { lat: 47.9, lon: 106.9 },
    };
    const s = new VoiceScheduler(false);
    s.start(plan, 0);
    s.evaluate({ nowMs: 10_000, plan, stepIndex: 0, distanceToNext: 100, speedMps: 20 });
    const cont = s.evaluate({ nowMs: 20_000, plan, stepIndex: 1, distanceToNext: 999_990, speedMps: 20 })!;
    expect(cont.kind).toBe("continueOn");
    expect(mn(cont.content)).toBeNull();
    expect(en(cont.content)!.display).toBe("Continue for 1000 kilometers");
    s.onPromptSuppressed(cont.maneuver!, cont.triggerAtMs);
    const heard: string[] = [];
    for (let d = 2_100, t = 40_000; d > 0; d -= 20, t += 1_000) {
      const p = s.evaluate({ nowMs: t, plan, stepIndex: 1, distanceToNext: d, speedMps: 20 });
      if (p) {
        heard.push(p.kind!);
        expect(mn(p.content)).not.toBeNull();
      }
    }
    expect(heard).toEqual(["early", "main", "now"]);
  });
});

// NAV-017 AC 24–25: the voice text generator (port of the Android VoiceText.kt) against the NAV-005 AC 32 examples
// word for word, D67, the English singular/plural rule, and the NAV-005 AC 33 scans over every text it can produce.
import { describe, expect, test } from "vitest";
import { I18n, type Lang } from "../i18n/i18n";
import fixture from "../route/maneuvers.fixture.json";
import { maneuverKey, MANEUVER_KEYS, type KeyResult, type ManeuverInput } from "../route/instructions";
import { renderVoice, type VoiceContent } from "./voiceText";

const t = (lang: Lang) => {
  const i = new I18n(lang);
  return (k: Parameters<I18n["t"]>[0]) => i.t(k, lang);
};
const m = (c: VoiceContent) => renderVoice(c, "mn", t("mn"));
const e = (c: VoiceContent) => renderVoice(c, "en", t("en"));
const k = (type: string, modifier?: string, exit?: number, bearing?: number): KeyResult => maneuverKey({ type, modifier, exit, bearing_after: bearing });
const man = (key: KeyResult, d: number, then: KeyResult | null = null): VoiceContent => ({ kind: "maneuver", key, distanceM: d, then });

describe("NAV-017 AC 24 voice text (NAV-005 AC 32 examples)", () => {
  test("AC 32 examples", () => {
    expect(m(man(k("turn", "right"), 300))).toBe("300 метрт баруун тийш эргэнэ үү");
    expect(m(man(k("fork", "slight left"), 1500))).toBe("1,5 километрт зүүн талаа барина уу");
    expect(m(man(k("roundabout", "right", 2), 200))).toBe("200 метрт тойрогт ороод хоёрдугаар гарцаар гарна уу");
    expect(m(man(k("turn", "left"), 20))).toBe("Зүүн тийш эргэнэ үү");
    expect(m({ kind: "arrival", key: k("arrive") })).toBe("Та очих газартаа ирлээ");
  });

  test("distance prefix rounding", () => {
    const turn = k("turn", "right");
    const cases: [number, string][] = [
      [29.9, "Баруун тийш эргэнэ үү"],
      [30, "30 метрт баруун тийш эргэнэ үү"],
      [64, "60 метрт баруун тийш эргэнэ үү"],
      [94.9, "90 метрт баруун тийш эргэнэ үү"],
      [95, "100 метрт баруун тийш эргэнэ үү"],
      [260, "250 метрт баруун тийш эргэнэ үү"],
      [995, "1 километрт баруун тийш эргэнэ үү"],
      [2010, "2 километрт баруун тийш эргэнэ үү"],
      [9940, "9,9 километрт баруун тийш эргэнэ үү"],
      [9950, "10 километрт баруун тийш эргэнэ үү"],
      [12_400, "12 километрт баруун тийш эргэнэ үү"],
    ];
    for (const [d, want] of cases) expect(m(man(turn, d)), `${d} m`).toBe(want);
  });

  test("D67: a metre value that rounds to 1000 is «1 километрт»; never «1000 метрт» / 1000 meters", () => {
    const turn = k("turn", "right");
    expect(m(man(turn, 960))).toBe("950 метрт баруун тийш эргэнэ үү");
    expect(m(man(turn, 974.9))).toBe("950 метрт баруун тийш эргэнэ үү");
    for (const d of [975, 980, 994.9, 995, 1000]) expect(m(man(turn, d))).toBe("1 километрт баруун тийш эргэнэ үү");
    expect(e(man(turn, 960))).toBe("In 950 meters, turn right");
    expect(e(man(turn, 980))).toBe("In 1 kilometer, turn right");
    expect(m(man(turn, 985, k("turn", "left")))).toBe("1 километрт баруун тийш эргэнэ үү, дараа нь зүүн тийш эргэнэ үү");
    for (let d = 30; d < 3000; d += 0.5) {
      expect(m(man(turn, d))).not.toContain("1000 метрт");
      expect(e(man(turn, d))).not.toContain("1000 meter");
    }
  });

  test("roundabout ordinals (C4) and fallback", () => {
    expect(m(man(k("roundabout", undefined, 1), 10))).toBe("Тойрогт ороод нэгдүгээр гарцаар гарна уу");
    expect(m(man(k("rotary", undefined, 10), 10))).toBe("Тойрогт ороод аравдугаар гарцаар гарна уу");
    expect(m(man(k("roundabout", undefined, 11), 10))).toBe("Тойрогт орно уу");
    expect(m(man(k("roundabout"), 10))).toBe("Тойрогт орно уу");
    expect(m(man(k("exit roundabout", "right"), 10))).toBe("Тойргоос гарна уу");
  });

  test("depart, approaching (A11), continue on (A12), chained (A13), arrival variants", () => {
    expect(m({ kind: "depart", key: k("depart", undefined, undefined, 0), then: null })).toBe("Хойд зүг рүү явна уу");
    expect(m({ kind: "approaching", distanceM: 310 })).toBe("300 метрт очих газартаа хүрнэ");
    expect(m({ kind: "continueOn", distanceM: 12_300 })).toBe("12 километр үргэлжлүүлэн явна уу");
    expect(m({ kind: "continueOn", distanceM: 2_480 })).toBe("2,5 километр үргэлжлүүлэн явна уу");
    expect(m(man(k("roundabout", undefined, 2), 200, k("turn", "slight right")))).toBe(
      "200 метрт тойрогт ороод хоёрдугаар гарцаар гарна уу, дараа нь бага зэрэг баруун тийш эргэнэ үү",
    );
    expect(m({ kind: "depart", key: k("depart", undefined, undefined, 5), then: k("turn", "left") })).toBe("Хойд зүг рүү явна уу, дараа нь зүүн тийш эргэнэ үү");
    expect(m({ kind: "arrival", key: k("arrive", "right") })).toBe("Таны очих газар баруун талд байна");
    expect(m({ kind: "arrival", key: k("arrive", "left") })).toBe("Таны очих газар зүүн талд байна");
  });

  test("English (navigation-ux §4.1) and the singular/plural rule on the formatted number", () => {
    expect(e(man(k("turn", "right"), 300))).toBe("In 300 meters, turn right");
    expect(e(man(k("fork", "left"), 1500))).toBe("In 1.5 kilometers, keep left");
    expect(e(man(k("roundabout", undefined, 2), 200))).toBe("In 200 meters, enter the roundabout and take the second exit");
    expect(e(man(k("turn", "left"), 20))).toBe("Turn left");
    expect(e({ kind: "approaching", distanceM: 300 })).toBe("In 300 meters, you will arrive");
    expect(e({ kind: "continueOn", distanceM: 12_000 })).toBe("Continue for 12 kilometers");
    expect(e(man(k("turn", "right"), 100, k("turn", "left")))).toBe("In 100 meters, turn right, then turn left");
    expect(e({ kind: "arrival", key: k("arrive") })).toBe("You have arrived");
    expect(e(man(k("turn", "slight left"), 1_040))).toBe("In 1 kilometer, turn slightly left");
    expect(e(man(k("turn", "right"), 1_050))).toBe("In 1.1 kilometers, turn right");
    expect(e({ kind: "continueOn", distanceM: 1_000 })).toBe("Continue for 1 kilometer");
    const unit = /(\d+(?:\.\d)?) (kilometers?|meters?)\b/;
    for (let d = 30; d < 20_000; d += 7) {
      for (const text of [e(man(k("turn", "right"), d)), e({ kind: "continueOn", distanceM: d }), e({ kind: "approaching", distanceM: d })]) {
        const u = unit.exec(text)!;
        expect(u[1] === "1", `${d} m «${text}»`).toBe(!u[2]!.endsWith("s"));
      }
    }
  });
});

/** Every Mongolian voice text for every manoeuvre key, the shared fixture and the schedule's distance range. */
function mongolianVoiceSet(): string[] {
  const keys: KeyResult[] = [
    ...MANEUVER_KEYS.map((key) => (key === "roundabout.exit" ? { key, params: { n: 2 } } : { key })),
    ...Array.from({ length: 12 }, (_, i) => ({ key: "roundabout.exit" as const, params: { n: i + 1 } })),
    ...(fixture as unknown as { cases: { maneuver: ManeuverInput }[] }).cases.map((c) => maneuverKey(c.maneuver)),
  ];
  const distances = [5, 15, 29, 31, 47, 80, 96, 150, 249, 500, 994, 1000, 1499, 2000, 9949, 9950, 25_000];
  const out: string[] = [];
  for (const key of keys) {
    for (const d of distances) out.push(m(man(key, d)));
    out.push(m(man(key, 120, { key: "turn.slightRight" })));
    out.push(m({ kind: "depart", key: { key: "depart.se" }, then: key }));
    if (key.key.startsWith("arrive")) out.push(m({ kind: "arrival", key }));
  }
  for (const d of distances.filter((x) => x >= 30 && x <= 500)) out.push(m({ kind: "approaching", distanceM: d }));
  for (const d of distances.filter((x) => x >= 2000)) out.push(m({ kind: "continueOn", distanceM: d }));
  return out;
}

describe("NAV-017 AC 25 voice scans (NAV-005 AC 33 rules)", () => {
  test("0 matches of every forbidden pattern; every relative зүүн/баруун has a C2 form", () => {
    const all = mongolianVoiceSet();
    expect(all.length).toBeGreaterThan(500);
    const rules = [/\d\s?(м|км)(\s|-|\/|$)/, /\d+-р/, /(\d+|нэг|хоёр|гурав|дөрөв|тав|зургаа|долоо|найм|ес|арав)\s(дахь|дэх)/, /ШТС/, /[<>{}]/, /\u200B|\u200C|\u200D|\uFEFF/];
    const bad: string[] = [];
    for (const text of all) {
      for (const r of rules) if (r.test(text)) bad.push(`«${text}» matches ${r}`);
      if (/[A-Za-z]/.test(text.replace(/GPS/g, ""))) bad.push(`«${text}» has Latin letters`);
      for (const mm of text.matchAll(/(зүүн|баруун)\s+(\S+)/gi)) {
        if (!["тийш", "талаа", "талд", "талын", "талаас", "зүг", "хойд", "өмнө"].includes(mm[2]!)) bad.push(`«${text}»: bare ${mm[0]}`);
      }
    }
    expect(bad.slice(0, 20)).toEqual([]);
  });
});

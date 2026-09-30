// ADR-0008 §2 rule table and NAV-004 AC 26–28, 31, 48: every fixture case (every AC 27 row, the depart sector
// boundaries 22.4°, 22.5°, 337.5°, 360°, unknown types) against maneuverKey and the resource files in both languages.
import { describe, expect, it } from "vitest";
import { resources, type Lang, type MessageKey } from "../i18n/i18n";
import fixture from "./maneuvers.fixture.json";
import {
  capitalizeFirst,
  departSector,
  instructionText,
  MANEUVER_KEYS,
  maneuverKey,
  messageKey,
  streetName,
  type ManeuverInput,
} from "./instructions";

interface Case {
  name: string;
  maneuver: ManeuverInput;
  key: string;
  params?: { n: number };
  mn: string;
  en: string;
}
const cases = (fixture as { cases: Case[] }).cases;
const t = (lang: Lang) => (k: MessageKey) => resources()[lang][k];

describe("maneuverKey (ADR-0008 §2, language-neutral)", () => {
  it.each(cases.map((c) => [c.name, c] as const))("%s", (_name, c) => {
    const r = maneuverKey(c.maneuver);
    expect(r.key).toBe(c.key);
    expect(r.params).toEqual(c.params);
  });

  it("covers every key of the table with at least one fixture case", () => {
    const used = new Set(cases.map((c) => c.key));
    for (const k of MANEUVER_KEYS) expect(used.has(k), k).toBe(true);
  });

  it("depart sectors are half-open and normalise modulo 360", () => {
    expect(departSector(22.4)).toBe("n");
    expect(departSector(22.5)).toBe("ne");
    expect(departSector(337.4)).toBe("nw");
    expect(departSector(337.5)).toBe("n");
    expect(departSector(360)).toBe("n");
    expect(departSector(-90)).toBe("w");
    expect(departSector(720 + 180)).toBe("s");
  });
});

describe("instructionText (AC 27 exact texts)", () => {
  it.each(cases.map((c) => [c.name, c] as const))("%s", (_name, c) => {
    expect(instructionText(c.maneuver, "mn", t("mn"))).toBe(c.mn);
    expect(instructionText(c.maneuver, "en", t("en"))).toBe(c.en);
  });

  it("every maneuver.* resource key exists in both languages", () => {
    for (const k of MANEUVER_KEYS) {
      expect(resources().mn[messageKey(k)], k).toBeTruthy();
      expect(resources().en[messageKey(k)], k).toBeTruthy();
    }
  });
});

describe("AC 28 text quality scan over every Mongolian instruction", () => {
  const texts = cases.map((c) => instructionText(c.maneuver, "mn", t("mn")));
  const allowedAfter = /^(тийш|талаа|талаас|талын|талд|зүг|хойд|өмнө|эгнээ)/;

  it("has 0 Latin letters, 0 placeholders, 0 zero-width characters", () => {
    for (const s of texts) {
      expect(s, s).not.toMatch(/[A-Za-z]/);
      expect(s, s).not.toMatch(/[<>{}]/);
      expect(s, s).not.toMatch(/\u200B|\u200C|\u200D|\uFEFF/);
    }
  });

  it("has no bare «зүүн»/«баруун» (glossary C2)", () => {
    for (const s of texts) {
      const words = s.toLowerCase().split(/\s+/);
      words.forEach((w, i) => {
        if (w === "зүүн" || w === "баруун") expect(words[i + 1] ?? "", s).toMatch(allowedAfter);
      });
    }
  });

  it("has none of the glossary Avoid terms", () => {
    for (const s of texts) {
      expect(s).not.toMatch(/навигаци/);
      expect(s).not.toMatch(/км\/ц(?!аг)/);
      expect(s).not.toMatch(/хоёр дахь|\d+ (дахь|дэх)/);
      expect(s).not.toMatch(/зорьсон газар/);
      expect(s).not.toMatch(/налуу зам/);
    }
  });

  it("English texts have 0 Cyrillic letters (AC 29)", () => {
    for (const c of cases) expect(instructionText(c.maneuver, "en", t("en"))).not.toMatch(/[Ѐ-ӿ]/);
  });
});

describe("helpers", () => {
  it("capitalizeFirst is idempotent", () => {
    expect(capitalizeFirst("зүүн тийш эргэнэ үү", "mn")).toBe("Зүүн тийш эргэнэ үү");
    expect(capitalizeFirst("Зүүн тийш эргэнэ үү", "mn")).toBe("Зүүн тийш эргэнэ үү");
    expect(capitalizeFirst("", "en")).toBe("");
  });

  it("streetName removes zero-width characters and traditional script (AC 26)", () => {
    expect(streetName("Энхтайвны\u200B өргөн чөлөө\uFEFF")).toBe("Энхтайвны өргөн чөлөө");
    expect(streetName("\u200B")).toBe("");
    expect(streetName(undefined)).toBe("");
    expect(streetName("Erdenet-Selenge sum")).toBe("Erdenet-Selenge sum");
    expect(streetName("Чингисийн өргөн чөлөө ᠴᠢᠩᠭᠢᠰ")).toBe("Чингисийн өргөн чөлөө");
  });
});

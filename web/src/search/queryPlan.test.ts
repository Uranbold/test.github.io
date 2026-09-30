// ADR-0006 §6 test vectors (normative), plus §2.1–2.4 details. Story AC 3, 4, 15, 16, 26.
import { describe, expect, it } from "vitest";
import { ABBREVIATION_SENDS_AS_TYPED, planQuery } from "./queryPlan";
import { MAX_QUERY_LENGTH, capQueryLength } from "./text";
import { latinToCyrillic } from "./transliterate";

type Row = [input: string, primary: string | null, secondary: string | null, mode: "none" | "parallel" | "ifEmpty" | "skip" | "coordinate"];

/** ADR-0006 §6, row by row. */
const VECTORS: Row[] = [
  ["С", null, null, "skip"],
  ["  ", null, null, "skip"],
  ["  Сүхбаатар  ", "Сүхбаатар", null, "none"],
  ["Сухбаатар", "Сухбаатар", "Сүхбаатар", "ifEmpty"],
  ["Сухбаатар дуурэг", "Сухбаатар дуурэг", "Сүхбаатар дүүрэг", "ifEmpty"],
  ["Sukhbaatar", "Sukhbaatar", "сухбаатар", "parallel"],
  ["suhbaatar", "suhbaatar", "сухбаатар", "parallel"],
  ["Sükhbaatar", "Sükhbaatar", "сүхбаатар", "parallel"],
  ["Ikh delguur", "Ikh delguur", "их дэлгүүр", "parallel"],
  ["Zaisan", "Zaisan", "зайсан", "parallel"],
  ["Zaysan", "Zaysan", "зайсан", "parallel"],
  ["Gandan", "Gandan", "гандан", "parallel"],
  ["Erdenet", "Erdenet", "эрдэнэт", "parallel"],
  ["Chingeltei", "Chingeltei", "чингэлтэй", "parallel"],
  ["Enkhtaivan", "Enkhtaivan", "энхтайван", "parallel"],
  ["Khan-Uul", "Khan-Uul", "хан-уул", "parallel"],
  ["Bayanzurkh", "Bayanzurkh", "баянзурх", "parallel"],
  ["БЗД", "Баянзүрх дүүрэг", "БЗД", "parallel"],
  ["худ", "Хан-Уул дүүрэг", "худ", "parallel"],
  ["БЗД 4-р хороо", "Баянзүрх дүүрэг 4-р хороо", "БЗД 4-р хороо", "parallel"],
  ["БЗД-ийн 4-р хороо", "БЗД-ийн 4-р хороо", "БЗД-ийн 4-р хөрөө", "ifEmpty"],
  ["Улаанбаатар Sukhbaatar", "Улаанбаатар Sukhbaatar", null, "none"],
  ["47.9189, 106.9176", null, null, "coordinate"],
  ["47.9189 106.9176", null, null, "coordinate"],
  ["106.9176, 47.9189", "106.9176, 47.9189", null, "none"],
  ["47,9189, 106,9176", "47,9189, 106,9176", null, "none"],
];

describe("ADR-0006 §6 query-plan vectors", () => {
  it("ABBREVIATION_SENDS_AS_TYPED is on (ADR-0006 §2.3 default)", () => {
    expect(ABBREVIATION_SENDS_AS_TYPED).toBe(true);
  });

  for (const [input, primary, secondary, mode] of VECTORS) {
    it(`${JSON.stringify(input)} → ${mode}${primary ? ` «${primary}»` : ""}${secondary ? ` + «${secondary}»` : ""}`, () => {
      const p = planQuery(input);
      if (mode === "skip") return expect(p.kind).toBe("skip");
      if (mode === "coordinate") return expect(p.kind).toBe("coordinate");
      expect(p).toMatchObject({ kind: "text", primary, secondary, mode });
    });
  }

  it("coordinate plans carry the parsed point (AC 26)", () => {
    expect(planQuery("47.9189,106.9176")).toEqual({ kind: "coordinate", point: { lat: 47.9189, lon: 106.9176 } });
    expect(planQuery("-45.5 -170")).toEqual({ kind: "coordinate", point: { lat: -45.5, lon: -170 } });
  });

  it("every plan sends at most 2 requests; «Сүхбаатар» sends exactly 1 (AC 3)", () => {
    for (const [input] of VECTORS) {
      const p = planQuery(input);
      if (p.kind === "text") expect(p.secondary === null ? 1 : 2).toBeLessThanOrEqual(2);
    }
    expect(planQuery("Сүхбаатар")).toMatchObject({ mode: "none", secondary: null });
    expect(planQuery("Сүхб")).toMatchObject({ mode: "none", secondary: null });
  });

  it("abbreviations are case-insensitive whole words only (AC 16)", () => {
    expect(planQuery("бзд")).toMatchObject({ primary: "Баянзүрх дүүрэг", mode: "parallel", rule: "A" });
    expect(planQuery("СБД ЧД")).toMatchObject({ primary: "Сүхбаатар дүүрэг Чингэлтэй дүүрэг", secondary: "СБД ЧД" });
    expect(planQuery("БГД 1-р хороо")).toMatchObject({ primary: "Баянгол дүүрэг 1-р хороо" });
    expect(planQuery("СХД")).toMatchObject({ primary: "Сонгинохайрхан дүүрэг" });
    expect(planQuery("БЗДД")).toMatchObject({ rule: "D", mode: "none" });
  });

  it("no planned q is longer than 200 characters, even when the expansion would exceed it (AC 16, F4)", () => {
    // 196 characters as typed; «СХД» → «Сонгинохайрхан дүүрэг» would make it 214.
    const typed = `${"а".repeat(192)} СХД`;
    expect(typed).toHaveLength(196);
    const p = planQuery(typed);
    expect(p).toMatchObject({ kind: "text", rule: "A", mode: "parallel", secondary: typed });
    if (p.kind !== "text") throw new Error("text plan expected");
    expect(p.primary.length).toBeLessThanOrEqual(MAX_QUERY_LENGTH);
    expect(p.primary).toBe(`${"а".repeat(192)} Сонгино`);
    expect(p.primary).toHaveLength(MAX_QUERY_LENGTH);
    // Abbreviation first: the expansion is kept, the tail is cut.
    const first = planQuery(`СБД ${"б".repeat(196)}`);
    if (first.kind !== "text") throw new Error("text plan expected");
    expect(first.primary).toHaveLength(MAX_QUERY_LENGTH);
    expect(first.primary.startsWith("Сүхбаатар дүүрэг ")).toBe(true);
    expect(first.secondary).toHaveLength(200);
    // Many abbreviations in a 200-character query.
    const many = planQuery(Array.from({ length: 50 }, () => "СХД").join(" ").slice(0, 200));
    if (many.kind !== "text") throw new Error("text plan expected");
    expect(many.primary.length).toBeLessThanOrEqual(MAX_QUERY_LENGTH);
    expect(many.secondary?.length ?? 0).toBeLessThanOrEqual(MAX_QUERY_LENGTH);
    // Over-long pasted input: every rule stays within 200.
    for (const input of ["Sukhbaatar ".repeat(30), "Сухбаатар ".repeat(30), "БЗД ".repeat(80), "Сүхбаатар ".repeat(30)]) {
      const q = planQuery(input);
      if (q.kind !== "text") throw new Error("text plan expected");
      expect(q.primary.length, input.slice(0, 12)).toBeLessThanOrEqual(MAX_QUERY_LENGTH);
      expect(q.secondary?.length ?? 0, input.slice(0, 12)).toBeLessThanOrEqual(MAX_QUERY_LENGTH);
    }
  });

  it("capQueryLength keeps ≤ 200 code units without splitting a surrogate pair", () => {
    expect(capQueryLength("x".repeat(250))).toHaveLength(200);
    expect(capQueryLength(`${"x".repeat(199)}😀`)).toBe("x".repeat(199));
    expect(capQueryLength("short")).toBe("short");
  });

  it("settled query: NFC, trim, collapsed whitespace (story Terms)", () => {
    expect(planQuery("  Их \t  дэлгүүр ")).toMatchObject({ primary: "Их дэлгүүр", mode: "none" });
    // NFD input (е + combining breve) normalises to NFC (й).
    expect(planQuery("Зайсан".normalize("NFD"))).toMatchObject({ primary: "Зайсан" });
  });

  it("a Latin query needs ≥ 2 letters; digits only are rule D", () => {
    expect(planQuery("a1")).toMatchObject({ rule: "D", mode: "none" });
    expect(planQuery("12")).toMatchObject({ rule: "D", mode: "none" });
  });
});

describe("latinToCyrillic (ADR-0006 §2.4)", () => {
  it.each([
    ["shchuka", "щука"],
    ["Tsagaan", "цагаан"],
    ["Zhargal", "жаргал"],
    ["yurt", "юрт"],
    ["Yolo", "ёло"],
    ["yesun", "есүн"], // front word (e, no a): u → ү,
    ["Darkhan", "дархан"],
    ["Tov", "тов"],
    ["Öndörkhaan", "өндөрхаан"],
    ["Tuul gol", "туул гол"],
    ["Ulgii", "улгий"],
    ["12-r khoroo", "12-р хороо"],
    ["jack", "жацк"],
    ["Château", "чатэау"], // other diacritics stripped,
  ])("%s → %s", (input, expected) => {
    expect(latinToCyrillic(input)).toBe(expected);
  });

  it("front words use ү/ө, back words у/о (vowel harmony)", () => {
    expect(latinToCyrillic("delguur")).toBe("дэлгүүр");
    expect(latinToCyrillic("Selenge")).toBe("сэлэнгэ");
    expect(latinToCyrillic("Khovd")).toBe("ховд");
    expect(latinToCyrillic("Ger")).toBe("гэр");
  });
});

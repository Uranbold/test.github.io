// Story Terms (settled query), AC 2, 4, 18, 21, 26, 27, 46; ADR-0006 §2.1, §2.2, §2.6.
import { describe, expect, it } from "vitest";
import { biasParams, formatCoordinate, parseCoordinate, reverseParams, wrapLon } from "./coords";
import { hasCyrillic, isCyrillicOnly, isLatinOnly, MAX_QUERY_LENGTH, normalizeQuery, stripTraditionalScript } from "./text";

describe("normalizeQuery", () => {
  it("trims, collapses whitespace and applies NFC", () => {
    expect(normalizeQuery("  Сүхбаатар \n талбай  ")).toBe("Сүхбаатар талбай");
    expect(normalizeQuery("Sükhbaatar".normalize("NFD"))).toBe("Sükhbaatar");
  });
  it("cuts at 200 code units without splitting a surrogate pair (AC 2)", () => {
    expect(normalizeQuery("a".repeat(250))).toHaveLength(MAX_QUERY_LENGTH);
    const s = "a".repeat(199) + "😀";
    expect(normalizeQuery(s)).toBe("a".repeat(199));
  });
});

describe("script detection", () => {
  it("Latin only, with diacritics, ≥ 2 letters", () => {
    expect(isLatinOnly("Sükhbaatar")).toBe(true);
    expect(isLatinOnly("Ikh delguur 2")).toBe(true);
    expect(isLatinOnly("a")).toBe(false);
    expect(isLatinOnly("Улаанбаатар Sukhbaatar")).toBe(false);
  });
  it("Cyrillic only", () => {
    expect(isCyrillicOnly("БЗД-ийн 4-р хороо")).toBe(true);
    expect(isCyrillicOnly("4-р")).toBe(true);
    expect(isCyrillicOnly("123")).toBe(false);
    expect(hasCyrillic("Бага Тойрог")).toBe(true);
    expect(hasCyrillic("Gandan")).toBe(false);
  });
});

describe("stripTraditionalScript (AC 18)", () => {
  it("removes the traditional-script part and the whitespace around it", () => {
    expect(stripTraditionalScript("Монгол улс ᠮᠤᠩᠭᠤᠯ ᠤᠯᠤᠰ")).toBe("Монгол улс");
    expect(stripTraditionalScript("Сүхбаатар ᠰᠦᠬᠡ ᠪᠠᠭᠠᠲᠤᠷ")).toBe("Сүхбаатар");
    expect(stripTraditionalScript("ᠮᠤᠩᠭᠤᠯ ᠤᠯᠤᠰ Монгол")).toBe("Монгол");
    expect(stripTraditionalScript("ᠮᠤᠩᠭᠤᠯ")).toBe("");
    expect(stripTraditionalScript("  Бага  Тойрог ")).toBe("Бага Тойрог");
  });
});

describe("coordinates", () => {
  it("parses the three AC 26 forms, in range only", () => {
    expect(parseCoordinate("47.9189, 106.9176")).toEqual({ lat: 47.9189, lon: 106.9176 });
    expect(parseCoordinate("47.9189,106.9176")).toEqual({ lat: 47.9189, lon: 106.9176 });
    expect(parseCoordinate("47.9189 106.9176")).toEqual({ lat: 47.9189, lon: 106.9176 });
    expect(parseCoordinate("-89.5, -179.99")).toEqual({ lat: -89.5, lon: -179.99 });
    expect(parseCoordinate("106.9176, 47.9189")).toBeNull(); // lat out of range (3 digits)
    expect(parseCoordinate("91, 10")).toBeNull();
    expect(parseCoordinate("47, 181")).toBeNull();
    expect(parseCoordinate("47,9189, 106,9176")).toBeNull(); // decimal commas: text
    expect(parseCoordinate("47.9189")).toBeNull();
  });
  it("formats «lat, lon» with 5 decimals and a point in both languages (AC 21)", () => {
    expect(formatCoordinate({ lat: 47.918812, lon: 106.9169 })).toBe("47.91881, 106.91690");
  });
  it("bias has 3 decimals (AC 9, 46), reverse 6 (AC 27)", () => {
    expect(biasParams({ lat: 47.91891234, lon: 106.91764 })).toEqual({ lat: "47.919", lon: "106.918" });
    expect(biasParams({ lat: -0.0001, lon: 0 })).toEqual({ lat: "0.000", lon: "0.000" });
    expect(reverseParams({ lat: 47.9189, lon: 106.9176 })).toEqual({ lat: "47.918900", lon: "106.917600" });
  });
  it("wraps longitudes of world copies", () => {
    expect(wrapLon(466.9176)).toBeCloseTo(106.9176, 6);
    expect(wrapLon(-253.0824)).toBeCloseTo(106.9176, 6);
    expect(wrapLon(180)).toBe(180);
  });
});

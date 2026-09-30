import { describe, expect, it } from "vitest";
import en from "./en.json";
import { I18n, resources } from "./i18n";
import mn from "./mn.json";

describe("resource files (AC 30–32)", () => {
  it("have identical key sets and no empty values", () => {
    expect(Object.keys(en).sort()).toEqual(Object.keys(mn).sort());
    for (const [lang, r] of Object.entries(resources())) for (const [k, v] of Object.entries(r)) expect(v.trim(), `${lang}.${k}`).not.toBe("");
  });

  it("defaults to Mongolian and switches live", () => {
    const i = new I18n();
    expect(i.lang).toBe("mn");
    expect(i.t("control.zoomIn")).toBe("Томруулах");
    const seen: string[] = [];
    i.onChange((l) => seen.push(l));
    i.setLang("en");
    expect(i.t("control.zoomIn")).toBe("Zoom in");
    expect(seen).toEqual(["en"]);
  });

  it("formats decimals with a comma in mn (glossary C3) and a point in en", () => {
    const i = new I18n("mn");
    expect(i.formatNumber(0.5)).toBe("0,5");
    i.setLang("en");
    expect(i.formatNumber(0.5)).toBe("0.5");
  });

  it("keeps language names in their own language and the attribution untranslated", () => {
    expect(mn["language.en"]).toBe(en["language.en"]);
    expect(mn["language.mn"]).toBe(en["language.mn"]);
    expect(mn["attribution.osm"]).toBe("© OpenStreetMap contributors");
    expect(en["attribution.osm"]).toBe("© OpenStreetMap contributors");
  });

  it("en.json has Mongolian (Cyrillic) text only where a decision asks for it", () => {
    // language.mn: a language name in its own language (above). placeType.soum: PO decision D34 (NAV-003, 2026-09-30),
    // «Сум» is shown untranslated in the English UI; the glossary "Soum" row records it. Anything else is a mistake.
    const INTENTIONAL: Record<string, string> = { "language.mn": mn["language.mn"], "placeType.soum": mn["placeType.soum"] };
    const cyrillic = Object.fromEntries(Object.entries(en).filter(([, v]) => /[\u0400-\u04FF]/.test(v)));
    expect(cyrillic).toEqual(INTENTIONAL);
    expect(en["placeType.soum"]).toBe("Сум");
  });
});

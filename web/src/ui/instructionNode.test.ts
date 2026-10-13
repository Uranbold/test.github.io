// @vitest-environment jsdom
// UX audit 2026-10-13 UX-04 (navigation-ux §2.1): the politeness particle never starts a line alone in rendered
// instruction text; `textContent` stays byte-identical to the resource text (NAV-004 / NAV-017 literal-text tests).
import { describe, expect, it } from "vitest";
import mn from "../i18n/mn.json";
import { setInstruction, splitParticle } from "./instructionNode";

describe("instruction particle (UX-04)", () => {
  it("splits every Mongolian instruction that ends in a verb + particle before that pair", () => {
    const keys = Object.keys(mn).filter((k) => k.startsWith("maneuver."));
    let withParticle = 0;
    for (const k of keys) {
      const text = (mn as Record<string, string>)[k]!;
      const parts = splitParticle(text);
      if (!/ (үү|уу)$/u.test(text)) {
        expect(parts, k).toBeNull();
        continue;
      }
      withParticle++;
      expect(parts, k).not.toBeNull();
      expect(parts!.head + parts!.tail, k).toBe(text);
      expect(parts!.tail.split(" "), k).toHaveLength(2);
      expect(parts!.head.endsWith(" ") || parts!.head === "", k).toBe(true);
    }
    expect(withParticle).toBeGreaterThan(20);
    expect(splitParticle("Зүүн тийш эргэнэ үү")).toEqual({ head: "Зүүн тийш ", tail: "эргэнэ үү" });
    expect(splitParticle("Тойрог: 2-р гарц")).toBeNull();
    expect(splitParticle("Turn left")).toBeNull();
  });

  it("renders the pair in one no-wrap span and keeps textContent identical", () => {
    const el = document.createElement("p");
    const text = "Баруун талын гарах зам руу эргэнэ үү";
    setInstruction(el, text);
    expect(el.textContent).toBe(text);
    const nb = el.querySelector("span.nb");
    expect(nb?.textContent).toBe("эргэнэ үү");
    expect(el.childNodes).toHaveLength(2);
    setInstruction(el, "Take the exit on the right");
    expect(el.textContent).toBe("Take the exit on the right");
    expect(el.querySelector("span")).toBeNull();
  });
});

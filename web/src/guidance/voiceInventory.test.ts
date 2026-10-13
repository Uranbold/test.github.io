// NAV-016 M9: src/guidance/voiceInventory.json is generated from docs/requirements/glossary-c3-spoken.tsv and
// docs/architecture/voice-pack/inventory.tsv (scripts/gen-voice-inventory.mjs). This test regenerates it in memory and
// fails on any drift, so a glossary or inventory change cannot reach the web voice text without a regeneration.
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { describe, expect, test } from "vitest";
// @ts-expect-error: plain ESM build script without type declarations
import { buildInventory, DISTANCE_TSV, generate, INVENTORY_TSV, OUTPUT } from "../../scripts/gen-voice-inventory.mjs";
import inventoryJson from "./voiceInventory.json";
import { VOICE_INVENTORY } from "./voiceText";

describe("NAV-016 voice inventory (pack-format §2.3)", () => {
  test("the committed JSON equals a fresh generation (no drift)", () => {
    expect(readFileSync(OUTPUT, "utf8"), "run: node scripts/gen-voice-inventory.mjs").toBe(generate());
  });

  test("304 entries (265 final + 39 nonFinal), canonical order, hash of the canonical bytes", () => {
    const inv = buildInventory(readFileSync(DISTANCE_TSV, "utf8"), readFileSync(INVENTORY_TSV, "utf8"));
    const entries = inv.entries as string[][];
    expect(entries.length).toBe(304);
    expect(entries.filter((e) => e[1] === "final").length).toBe(265);
    expect(entries.filter((e) => e[1] === "nonFinal").length).toBe(39);
    expect(createHash("sha256").update(inv.canonical, "utf8").digest("hex")).toBe(inventoryJson.inventorySha256);
    expect(VOICE_INVENTORY.inventorySha256).toBe(inventoryJson.inventorySha256);
    const lines = (inv.canonical as string).split("\n");
    expect(lines.pop()).toBe("");
    const sortKeys = lines.map((l) => l.split("\t").slice(0, 2).join("\u0000"));
    expect([...sortKeys].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))).toEqual(sortKeys);
  });

  test("spoken texts follow the pack-format §2.2 character rule; GPS is «жи пи эс» (D231)", () => {
    for (const [key, , text] of inventoryJson.entries) expect(text, key).toMatch(/^[а-яёөү]+( [а-яёөү]+)*$/);
    expect(VOICE_INVENTORY.spoken("fixed.gpsLost", "final")).toBe("жи пи эс дохио тасарлаа");
    expect(VOICE_INVENTORY.spoken("fixed.gpsRestored", "final")).toBe("жи пи эс дохио сэргэлээ");
  });

  test("a changed character in either input changes the hash; a status-only TSV change does not", () => {
    const d = readFileSync(DISTANCE_TSV, "utf8");
    const i = readFileSync(INVENTORY_TSV, "utf8");
    const base = buildInventory(d, i).inventorySha256;
    expect(buildInventory(d.replace("гурван зуун метрт", "гурван зуун метр"), i).inventorySha256).not.toBe(base);
    expect(buildInventory(d, i.replace("дараа нь", "дараа ни")).inventorySha256).not.toBe(base);
    expect(buildInventory(d.replace(/needs native review/g, "approved 2026-10-13"), i).inventorySha256).toBe(base);
  });
});

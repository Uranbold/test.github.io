#!/usr/bin/env node
// NAV-016 M9 (AC 47): the canonical voice inventory for the web voice text, generated from the same two files the
// Android build and the clip-pack generator read (docs/architecture/voice-pack/pack-format.md §1.1, §2.3):
//   docs/requirements/glossary-c3-spoken.tsv   (BA: the 217 distance entries, column spoken_mn)
//   docs/architecture/voice-pack/inventory.tsv (architect: the 48 non-distance keys, plus nonFinal takes)
// Output: src/guidance/voiceInventory.json = { inventorySha256, entries: [[key, take, spokenText], ...] } in canonical
// order. JSON (not TS) so `check:i18n` stays strict about Cyrillic in source files. The web speaks the spoken text only;
// it bundles no audio (AC 49). voiceInventory.test.ts regenerates it in memory and fails on any drift.
// Run: node scripts/gen-voice-inventory.mjs
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const WEB = fileURLToPath(new URL("..", import.meta.url));
export const DISTANCE_TSV = join(WEB, "../docs/requirements/glossary-c3-spoken.tsv");
export const INVENTORY_TSV = join(WEB, "../docs/architecture/voice-pack/inventory.tsv");
export const OUTPUT = join(WEB, "src/guidance/voiceInventory.json");

/** Rows of a header-first TSV as objects keyed by the header names. */
function rows(text, file) {
  const lines = text.split("\n").filter((l) => l !== "");
  const header = lines[0].split("\t");
  return lines.slice(1).map((line, i) => {
    const cells = line.split("\t");
    if (cells.length < header.length) throw new Error(`${file}:${i + 2}: ${cells.length} columns, expected ${header.length}`);
    return Object.fromEntries(header.map((h, j) => [h, cells[j]]));
  });
}

/** ASCII byte order (keys and takes are ASCII): key first, then take. */
const byKeyThenTake = (a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0);

/** pack-format §2.3: the canonical entries, bytes and inventorySha256 from the two input texts. */
export function buildInventory(distanceTsv, inventoryTsv) {
  const entries = [];
  for (const r of rows(distanceTsv, "glossary-c3-spoken.tsv")) entries.push([r.key, "final", r.spoken_mn]);
  for (const r of rows(inventoryTsv, "inventory.tsv")) {
    entries.push([r.key, "final", r.spoken_mn]);
    if (r.nonfinal === "yes") entries.push([r.key, "nonFinal", r.spoken_mn]);
    else if (r.nonfinal !== "no") throw new Error(`inventory.tsv: ${r.key}: nonfinal must be yes or no`);
  }
  entries.sort(byKeyThenTake);
  for (let i = 1; i < entries.length; i++) {
    if (byKeyThenTake(entries[i - 1], entries[i]) === 0) throw new Error(`duplicate entry ${entries[i][0]} ${entries[i][1]}`);
  }
  const canonical = entries.map(([k, t, s]) => `${k}\t${t}\t${s}\n`).join("");
  const inventorySha256 = createHash("sha256").update(canonical, "utf8").digest("hex");
  return { inventorySha256, entries, canonical };
}

/** The JSON file text (stable: 2-space indentation, one entry per line, final LF). */
export function inventoryJson(inv) {
  const lines = inv.entries.map((e) => "    " + JSON.stringify(e));
  return `{\n  "inventorySha256": ${JSON.stringify(inv.inventorySha256)},\n  "entries": [\n${lines.join(",\n")}\n  ]\n}\n`;
}

export function generate() {
  return inventoryJson(buildInventory(readFileSync(DISTANCE_TSV, "utf8"), readFileSync(INVENTORY_TSV, "utf8")));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const text = generate();
  writeFileSync(OUTPUT, text);
  const inv = JSON.parse(text);
  console.log(`wrote ${OUTPUT}: ${inv.entries.length} entries, inventorySha256 ${inv.inventorySha256}`);
}

#!/usr/bin/env node
// Design-token checker (owner: ux-designer). No dependencies.
// Usage: node docs/design/prototypes/check-contrast.mjs
// 1. Every pair in tokens.json "contrastPairs" meets its minimum WCAG contrast ratio.
// 2. Both map flavors define exactly the same keys (light and night stay in sync).
// 3. Every hex value quoted in map-style.md tables matches tokens.json (no drift).
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const tokens = JSON.parse(readFileSync(join(here, "..", "tokens.json"), "utf8"));
let failures = 0;

function lookup(path) {
  let node = tokens.color;
  for (const part of path.split(".")) {
    node = node?.[part];
    if (node === undefined) throw new Error(`unknown token ${path}`);
  }
  return node.$value;
}
function rgb(hex) {
  const m = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!m) throw new Error(`not an opaque hex colour: ${hex}`);
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
function luminance(hex) {
  const [r, g, b] = rgb(hex).map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function ratio(a, b) {
  const [l1, l2] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (l1 + 0.05) / (l2 + 0.05);
}

console.log("1) contrast pairs");
for (const [fg, bg, min] of tokens.contrastPairs) {
  const r = ratio(lookup(fg), lookup(bg));
  const ok = r >= min;
  if (!ok) failures++;
  console.log(`${ok ? "PASS" : "FAIL"}  ${r.toFixed(2).padStart(5)} >= ${min}  ${fg} on ${bg}`);
}

console.log("\n2) flavor key parity");
function keys(obj, prefix = "") {
  return Object.entries(obj).flatMap(([k, v]) =>
    v && typeof v === "object" && !("$value" in v) ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`]);
}
for (const group of ["flavor", "custom"]) {
  const l = keys(tokens.color.light.map[group]).sort();
  const n = keys(tokens.color.night.map[group]).sort();
  const missing = [...l.filter((k) => !n.includes(k)).map((k) => `night lacks ${k}`),
                   ...n.filter((k) => !l.includes(k)).map((k) => `light lacks ${k}`)];
  if (missing.length) { failures++; console.log(`FAIL  ${group}: ${missing.join(", ")}`); }
  else console.log(`PASS  ${group}: ${l.length} keys in both modes`);
}

console.log("\n3) map-style.md table values match tokens.json");
const md = readFileSync(join(here, "..", "map-style.md"), "utf8");
// Rows like: | `highway` | #FFC65C | #8C7443 | ...
const row = /^\|\s*`([a-z_.]+)`\s*\|\s*(#[0-9A-Fa-f]{6})\s*\|\s*(#[0-9A-Fa-f]{6})\s*\|/gm;
let checked = 0;
for (const m of md.matchAll(row)) {
  const [, key, day, night] = m;
  const base = key.startsWith("custom.") ? "map" : "map.flavor";
  for (const [mode, val] of [["light", day], ["night", night]]) {
    const want = lookup(`${mode}.${base}.${key}`);
    checked++;
    if (want.toUpperCase() !== val.toUpperCase()) {
      failures++;
      console.log(`FAIL  ${key} (${mode}): map-style.md ${val} vs tokens.json ${want}`);
    }
  }
}
console.log(`${failures ? "" : "PASS  "}${checked} values checked`);

console.log(failures ? `\n${failures} failure(s)` : "\nall checks passed");
process.exit(failures ? 1 : 0);

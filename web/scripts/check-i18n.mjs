#!/usr/bin/env node
// NAV-002 AC 32 / NAV-003 AC 44: no user-facing text literal outside src/i18n/{mn,en}.json; identical key sets; no empty
// values. (src/search/lexicon.json holds query-assistance data that is sent to the gateway, never displayed; it is JSON,
// so it is not scanned here.)
// Run: npm run check:i18n (also part of npm run lint). Exit code 1 on any problem.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const read = (p) => readFileSync(join(ROOT, p), "utf8");
const problems = [];

// 1. Resource files: identical keys, no empty values.
const mn = JSON.parse(read("src/i18n/mn.json"));
const en = JSON.parse(read("src/i18n/en.json"));
const mnKeys = Object.keys(mn).sort();
const enKeys = Object.keys(en).sort();
for (const k of mnKeys) if (!(k in en)) problems.push(`en.json is missing key "${k}"`);
for (const k of enKeys) if (!(k in mn)) problems.push(`mn.json is missing key "${k}"`);
for (const [lang, r] of [["mn", mn], ["en", en]]) {
  for (const [k, v] of Object.entries(r)) if (typeof v !== "string" || v.trim() === "") problems.push(`${lang}.json "${k}" is empty`);
}

// 2. Source files to scan.
function walk(dir) {
  return readdirSync(join(ROOT, dir)).flatMap((name) => {
    const p = join(dir, name);
    return statSync(join(ROOT, p)).isDirectory() ? walk(p) : [p];
  });
}
const tsFiles = [...walk("src"), ...walk("fixtures")].filter((f) => /\.ts$/.test(f) && !/\.test\.ts$/.test(f));
const htmlFiles = ["index.html", ...walk("fixtures").filter((f) => f.endsWith(".html"))];

const CYRILLIC = /[\u0400-\u04FF]/;
const usedKeys = new Set();

/** Blanks out comments (keeps line numbers). Good enough for this codebase: no "//" inside strings except URLs. */
function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "))
    .replace(/(^|[^:"'`])\/\/.*$/gm, "$1");
}
/** Literal text of a string/template after removing ${...} interpolations. */
const literalText = (q, body) => (q === "`" ? body.replace(/\$\{[^}]*\}/g, "") : body);

for (const f of tsFiles) {
  const src = read(f);
  const lines = stripComments(src).split("\n");
  // fixtures/ hold map-feature test data (AC 8 feature names), not UI text: exempt from the Cyrillic rule only.
  const cyrillicRule = !f.startsWith("fixtures");
  lines.forEach((line, i) => {
    const at = `${f}:${i + 1}`;
    if (cyrillicRule && CYRILLIC.test(line)) problems.push(`${at}: Cyrillic text literal in source (move it to src/i18n)`);
    const assign = line.match(/\.(textContent|innerText|title|placeholder|alt)\s*=\s*(["'`])(.*?)\2/);
    if (assign && /[A-Za-z\u0400-\u04FF]/.test(literalText(assign[2], assign[3]))) problems.push(`${at}: text literal assigned to a text property`);
    if (/setAttribute\(\s*["'](aria-label|aria-description|title|alt|placeholder)["']\s*,\s*["'`]/.test(line)) problems.push(`${at}: literal accessible name`);
    const inner = line.match(/innerHTML\s*=\s*(["'`])(.*)\1/);
    if (inner && />[^<]*[A-Za-z\u0400-\u04FF][^<]*</.test(inner[2])) problems.push(`${at}: text inside an innerHTML literal`);
  });
  for (const m of src.matchAll(/\bt\(\s*["']([a-zA-Z0-9.]+)["']/g)) usedKeys.add(m[1]);
  for (const m of src.matchAll(/["']((?:app|map|control|marker|theme|language|status|action|location|unit|attribution|search|place|placeType)\.[a-zA-Z.]+)["']/g)) usedKeys.add(m[1]);
}

for (const f of htmlFiles) {
  const html = read(f)
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/<script[\s\S]*?<\/script>/g, "")
    .replace(/<style[\s\S]*?<\/style>/g, "");
  for (const m of html.matchAll(/>([^<]+)</g)) {
    if (/[A-Za-z\u0400-\u04FF©]/.test(m[1])) problems.push(`${f}: hard-coded text node "${m[1].trim().slice(0, 40)}"`);
  }
  for (const m of html.matchAll(/\s(aria-label|title|alt|placeholder)="([^"]*)"/g)) {
    if (m[2].trim() !== "") problems.push(`${f}: hard-coded ${m[1]}="${m[2]}"`);
  }
  for (const m of html.matchAll(/data-(?:i18n|i18n-aria-label|i18n-placeholder|tooltip)="([^"]+)"/g)) usedKeys.add(m[1]);
}

// 3. Every referenced key exists; report unused keys as information.
for (const k of usedKeys) if (!(k in mn)) problems.push(`key "${k}" is used but missing from the resource files`);
const unused = mnKeys.filter((k) => !usedKeys.has(k));

if (problems.length) {
  console.error(`check-i18n: ${problems.length} problem(s)\n` + problems.map((p) => "  - " + p).join("\n"));
  process.exit(1);
}
console.log(
  `check-i18n: OK. ${mnKeys.length} keys in mn/en (identical, none empty); scanned ${tsFiles.length} TS and ${htmlFiles.length} HTML files` +
    (unused.length ? `; keys not referenced directly: ${unused.join(", ")}` : ""),
);
void relative;

#!/usr/bin/env node
// NAV-002 AC 33: every value in src/i18n/mn.json must match a term in docs/requirements/glossary.md exactly
// (the first letter may differ in case: stand-alone labels start with a capital, glossary rows are often lower case).
//
//   node scripts/check-glossary.mjs                      strict: glossary only (the AC 33 check)
//   node scripts/check-glossary.mjs --with-story-proposals
//                                                        also accept the NAV-002 story's proposed rows G1–G7,
//                                                        reported as "pending BA" (until the BA adds them)
// Exit code 1 if any value has no match.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../..", import.meta.url));
const GLOSSARY = "docs/requirements/glossary.md";
const STORY = "docs/requirements/stories/NAV-002-web-demo-map.md";
const withStory = process.argv.includes("--with-story-proposals");

const read = (p) => readFileSync(ROOT + p, "utf8");
const norm = (s) => s.replace(/\s+/g, " ").trim();
const cells = (line) => line.split("|").slice(1, -1).map((c) => c.trim());
const quoted = (text) => [...text.matchAll(/«([^»]+)»/g)].map((m) => norm(m[1]));

/** Terms from the glossary: every «quoted» string, plus the unquoted pieces of each table row's Mongolian column. */
function glossaryTerms(md) {
  const terms = new Map();
  const add = (t, where) => {
    const n = norm(t.replace(/\*\*/g, "").replace(/`/g, ""));
    if (n && !terms.has(n)) terms.set(n, where);
  };
  for (const t of quoted(md)) add(t, "glossary «»");
  for (const line of md.split("\n")) {
    if (!line.startsWith("|") || /^\|\s*-/.test(line)) continue;
    const c = cells(line);
    if (c.length < 3) continue;
    const mnCol = c[1].replace(/«[^»]*»/g, "|").replace(/\([^)]*\)/g, "|");
    for (const piece of mnCol.split(/[|/,;:.]/)) add(piece, `glossary row "${norm(c[0]).slice(0, 40)}"`);
  }
  return terms;
}

/** Proposed rows G1–G7 from the story ("Proposed glossary additions" table). */
function storyProposals(md) {
  const terms = new Map();
  for (const line of md.split("\n")) {
    const c = line.startsWith("| G") ? cells(line) : [];
    if (c.length < 4 || !/^G\d+$/.test(c[0])) continue;
    for (const t of quoted(c[2])) terms.set(t, `story ${c[0]} (pending BA)`);
  }
  return terms;
}

const firstLetterInsensitive = (a, b) => a.length > 0 && a.length === b.length && a.slice(1) === b.slice(1) && a[0].toLowerCase() === b[0].toLowerCase();

function findMatch(value, terms) {
  const v = norm(value);
  if (terms.has(v)) return terms.get(v);
  for (const [t, where] of terms) if (firstLetterInsensitive(v, t)) return `${where} (first letter case)`;
  return null;
}

const mn = JSON.parse(readFileSync(new URL("../src/i18n/mn.json", import.meta.url), "utf8"));
const glossary = glossaryTerms(read(GLOSSARY));
const story = withStory ? storyProposals(read(STORY)) : new Map();

const rows = [];
let missing = 0;
let pending = 0;
for (const [key, value] of Object.entries(mn)) {
  let where = findMatch(value, glossary);
  if (!where && withStory) {
    where = findMatch(value, story);
    if (where) pending++;
  }
  if (!where) missing++;
  rows.push(`  ${where ? "ok     " : "MISSING"} ${key.padEnd(24)} «${value}»${where ? "  <- " + where : ""}`);
}
console.log(rows.join("\n"));
console.log(
  `check-glossary: ${Object.keys(mn).length - missing}/${Object.keys(mn).length} mn values match` +
    (withStory ? ` (${pending} only via the story's proposed rows, pending BA)` : "") +
    (missing ? `; ${missing} have no glossary term` : ""),
);
process.exit(missing ? 1 : 0);

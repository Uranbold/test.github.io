#!/usr/bin/env node
// NAV-002 AC 33: every value in src/i18n/mn.json must match an approved term in docs/requirements/glossary.md exactly
// (the first letter may differ in case: stand-alone labels start with a capital, glossary rows are often lower case).
//
// Only approved wording counts. A term is taken from:
//   1. the "Approved Mongolian" column of the term tables (sections 2–8): quoted strings and unquoted pieces;
//   2. the "Definition" column of those rows: «quoted» usage forms (e.g. "UI: «…»", "Others: «…»");
//   3. the "Rule" column of the language-conventions table (C1–C8): «quoted» examples.
// Never from the Notes or Status columns, the change log, the header prose or any other table, and in 2–3 never
// from a sentence that names rejected or avoided wording (Avoid, Rejected, Alternative, Previous proposal, not):
// those are exactly the places where banned terms such as «навигаци» are quoted. ("never" is not a marker: in the
// convention rules it scopes screen vs voice, e.g. C3 "Voice text never contains «м», «км»", which are the approved
// screen abbreviations.)
//
//   node scripts/check-glossary.mjs [--mn <file>] [--glossary <file>]
// Exit code 1 if any value has no match.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../..", import.meta.url));

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  return i > 0 && process.argv[i + 1] ? resolve(process.argv[i + 1]) : fallback;
}
const GLOSSARY = arg("--glossary", ROOT + "docs/requirements/glossary.md");
const MN = arg("--mn", fileURLToPath(new URL("../src/i18n/mn.json", import.meta.url)));

const norm = (s) => s.replace(/\*\*/g, "").replace(/`/g, "").replace(/\s+/g, " ").trim();
const cells = (line) => line.split("|").slice(1, -1).map((c) => c.trim());
const quoted = (text) => [...text.matchAll(/«([^»]+)»/g)].map((m) => norm(m[1]));
const isSeparator = (line) => /^\|\s*:?-/.test(line);

/** Sentences that quote wording we must not use. */
const NEGATIVE = /\b(avoid|avoided|rejected|alternative|previous proposal|not)\b/i;
/** Splits a cell into sentences; «…» contents are kept whole (they may contain full stops). */
function sentences(text) {
  const out = [];
  let cur = "";
  let depth = 0;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    cur += ch;
    if (ch === "«") depth++;
    else if (ch === "»") depth = Math.max(0, depth - 1);
    else if (depth === 0 && /[.;]/.test(ch) && (i + 1 >= text.length || /\s/.test(text[i + 1]))) {
      out.push(cur);
      cur = "";
    }
  }
  if (cur.trim()) out.push(cur);
  return out;
}
const positiveQuoted = (text) => sentences(text).filter((s) => !NEGATIVE.test(s)).flatMap(quoted);

/** Approved terms with where they come from. */
function glossaryTerms(md) {
  const terms = new Map();
  const add = (t, where) => {
    const n = norm(t);
    if (n && !terms.has(n)) terms.set(n, where);
  };
  let table = null; // { kind: "terms" | "conventions", col: {...} }
  for (const line of md.split("\n")) {
    if (!line.startsWith("|")) {
      table = null;
      continue;
    }
    if (isSeparator(line)) continue;
    const c = cells(line);
    if (table === null) {
      // Header row: decide by column names, not positions.
      const h = c.map((x) => norm(x).toLowerCase());
      const mnCol = h.findIndex((x) => x.startsWith("approved mongolian"));
      const defCol = h.findIndex((x) => x.startsWith("definition"));
      const ruleCol = h.findIndex((x) => x === "rule");
      if (mnCol >= 0) table = { kind: "terms", mnCol, defCol };
      else if (ruleCol >= 0 && h.includes("convention")) table = { kind: "conventions", ruleCol };
      else table = { kind: "other" };
      continue;
    }
    const row = norm(c[0] ?? "").slice(0, 40);
    if (table.kind === "terms") {
      const mnCell = c[table.mnCol] ?? "";
      for (const t of quoted(mnCell)) add(t, `glossary "${row}" (Mongolian column)`);
      const unquoted = mnCell.replace(/«[^»]*»/g, "|").replace(/\([^)]*\)/g, "|");
      for (const piece of unquoted.split(/[|/,;:.]/)) add(piece, `glossary "${row}" (Mongolian column)`);
      if (table.defCol >= 0) for (const t of positiveQuoted(c[table.defCol] ?? "")) add(t, `glossary "${row}" (Definition)`);
    } else if (table.kind === "conventions") {
      for (const t of positiveQuoted(c[table.ruleCol] ?? "")) add(t, `glossary ${row} (convention rule)`);
    }
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

const mn = JSON.parse(readFileSync(MN, "utf8"));
const glossary = glossaryTerms(readFileSync(GLOSSARY, "utf8"));

const rows = [];
let missing = 0;
for (const [key, value] of Object.entries(mn)) {
  const where = findMatch(value, glossary);
  if (!where) missing++;
  rows.push(`  ${where ? "ok     " : "MISSING"} ${key.padEnd(24)} «${value}»${where ? "  <- " + where : ""}`);
}
console.log(rows.join("\n"));
console.log(
  `check-glossary: ${Object.keys(mn).length - missing}/${Object.keys(mn).length} mn values match an approved glossary term` +
    (missing ? `; ${missing} ${missing === 1 ? "has" : "have"} none` : ""),
);
process.exit(missing ? 1 : 0);

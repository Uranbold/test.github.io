// Query plan: which `search` requests a settled query produces (story AC 3, 15, 16, 26; ADR-0006 §2.2, §2.3).
// At most 2 requests per settled query, always.
import { parseCoordinate, type LatLon } from "./coords";
import lexicon from "./lexicon.json";
import { capQueryLength, isCyrillicOnly, isLatinOnly, isSearchable, normalizeQuery } from "./text";
import { latinToCyrillic } from "./transliterate";

/**
 * ADR-0006 §2.3 rule A: an abbreviation also sends the query as typed (OSM names contain «БЗД-ийн …», F4/F5).
 * Set to false if the PO rejects it: rule A then sends the expanded query only (AC 16 as literally written).
 */
export const ABBREVIATION_SENDS_AS_TYPED = true;

export type PlanMode = "none" | "parallel" | "ifEmpty";

export type QueryPlan =
  | { kind: "skip" }
  | { kind: "coordinate"; point: LatLon }
  | { kind: "text"; primary: string; secondary: string | null; mode: PlanMode; rule: "A" | "B" | "C" | "D" };

const ABBREVIATIONS: Record<string, string> = lexicon.districtAbbreviations;
const ABBR_BY_LOWER = new Map(Object.entries(ABBREVIATIONS).map(([k, v]) => [k.toLowerCase(), v]));
const VOWEL_FALLBACK: Record<string, string> = lexicon.vowelFallback;
const RUSSIAN_VOWELS = new RegExp(`[${Object.keys(VOWEL_FALLBACK).join("")}]`, "u");

/** Rule A: replaces every whitespace-delimited abbreviation token; null when there is none. */
function expandAbbreviations(q: string): string | null {
  let found = false;
  const out = q
    .split(" ")
    .map((tok) => {
      const full = ABBR_BY_LOWER.get(tok.toLowerCase());
      if (full === undefined) return tok;
      found = true;
      return full;
    })
    .join(" ");
  return found ? out : null;
}

function vowelVariant(q: string): string {
  let out = "";
  for (const ch of q) out += VOWEL_FALLBACK[ch] ?? ch;
  return out;
}

/**
 * Every planned `q` is at most 200 code units (openapi `q` maxLength; story AC 16, PO approval F4). The settled query is
 * already capped; only rule A can grow it (СХД → «Сонгинохайрхан дүүрэг», +18). Such an expansion is cut to 200, not
 * skipped: the parallel request with the query as typed still carries the full text.
 */
function text(rawPrimary: string, rawSecondary: string | null, mode: PlanMode, rule: "A" | "B" | "C" | "D"): QueryPlan {
  const primary = capQueryLength(rawPrimary);
  const secondary = rawSecondary === null ? null : capQueryLength(rawSecondary);
  if (secondary === null || normalizeQuery(secondary) === normalizeQuery(primary)) {
    return { kind: "text", primary, secondary: null, mode: "none", rule };
  }
  return { kind: "text", primary, secondary, mode, rule };
}

/** The plan for a raw or settled query. */
export function planQuery(input: string): QueryPlan {
  const q = normalizeQuery(input);
  if (!isSearchable(q)) return { kind: "skip" };
  const point = parseCoordinate(q);
  if (point) return { kind: "coordinate", point };

  const expanded = expandAbbreviations(q);
  if (expanded !== null) {
    return ABBREVIATION_SENDS_AS_TYPED ? text(expanded, q, "parallel", "A") : text(expanded, null, "none", "A");
  }
  if (isLatinOnly(q)) return text(q, latinToCyrillic(q), "parallel", "B");
  if (isCyrillicOnly(q) && RUSSIAN_VOWELS.test(q)) return text(q, vowelVariant(q), "ifEmpty", "C");
  return text(q, null, "none", "D");
}

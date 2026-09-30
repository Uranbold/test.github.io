// Text helpers for NAV-003 search (story "Terms: Settled query", AC 2, 4, 18; ADR-0006 §2.1, §2.6).
// Pure functions: no DOM, no fetch.

/** `q` maxLength in openapi.yaml (search) and the input's maxlength (AC 2). */
export const MAX_QUERY_LENGTH = 200;
/** A settled query needs at least this many characters to be searched (AC 4). */
export const MIN_QUERY_LENGTH = 2;

/**
 * Settled query: Unicode NFC, trim, collapse whitespace runs to one space, cut at 200 code units
 * (without splitting a surrogate pair).
 */
export function normalizeQuery(raw: string): string {
  let s = raw.normalize("NFC").replace(/\s+/gu, " ").trim();
  if (s.length > MAX_QUERY_LENGTH) {
    s = s.slice(0, MAX_QUERY_LENGTH);
    if (/[\uD800-\uDBFF]$/.test(s)) s = s.slice(0, -1);
    s = s.trim();
  }
  return s;
}

export const isSearchable = (settled: string): boolean => settled.length >= MIN_QUERY_LENGTH;

const LETTER = /\p{L}/gu;
const LATIN = /^\p{Script=Latin}$/u;
const CYRILLIC_LETTER = /^\p{Script=Cyrillic}$/u;
const CYRILLIC_ANY = /[\u0400-\u04FF]/;

function letters(s: string): string[] {
  return s.match(LETTER) ?? [];
}

/** Every letter is Latin script (diacritics included) and there are at least 2 letters (ADR-0006 rule B). */
export function isLatinOnly(s: string): boolean {
  const l = letters(s);
  return l.length >= 2 && l.every((c) => LATIN.test(c));
}

/** Every letter is Cyrillic and there is at least one letter (ADR-0006 rule C precondition). */
export function isCyrillicOnly(s: string): boolean {
  const l = letters(s);
  return l.length >= 1 && l.every((c) => CYRILLIC_LETTER.test(c));
}

/** Any character in U+0400–U+04FF (screen spec › Result content rules › Language of parts). */
export function hasCyrillic(s: string): boolean {
  return CYRILLIC_ANY.test(s);
}

/**
 * AC 18 / ADR-0006 §2.6: removes traditional Mongolian script (U+1800–U+18AF) together with the adjacent
 * U+202F (narrow no-break space), U+200C/U+200D and whitespace, then trims and collapses spaces.
 */
export function stripTraditionalScript(value: string): string {
  if (!/[\u1800-\u18AF]/.test(value)) return value.replace(/\s+/gu, " ").trim();
  return value
    .replace(/[\s\u202F\u200C\u200D]*[\u1800-\u18AF][\u1800-\u18AF\u202F\u200C\u200D\s]*/gu, " ")
    .replace(/\s+/gu, " ")
    .trim();
}

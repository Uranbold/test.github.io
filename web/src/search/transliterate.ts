// Latin → Cyrillic transliteration for search help (story AC 15, ADR-0006 §2.4, normative).
// Output is lower case (Photon is case-insensitive). The tables live in lexicon.json.
import lexicon from "./lexicon.json";

const L = lexicon.latin;
const PRECOMPOSED: Record<string, string> = L.precomposed;
const SINGLE: Record<string, string> = L.single;
const MULTI: ReadonlyArray<readonly [string, string]> = L.multi.map(([a, b]) => [a as string, b as string] as const);
const FRONT_VOWELS = new Set<string>(L.frontVowels);
/** Vowels for the "y/i after a vowel" rule: Latin a e i o u plus the already mapped ө ү. */
const VOWELS = new Set<string>(["a", "e", "i", "o", "u", ...L.frontVowels]);

/** Step 1: lower-case, ö ő → ө and ü ű → ү, strip every other combining diacritic. */
function prepare(input: string): string {
  const lower = input.toLowerCase();
  let out = "";
  for (const ch of lower) out += PRECOMPOSED[ch] ?? ch;
  return out.normalize("NFD").replace(/[\u0300-\u036f]/g, "").normalize("NFC");
}

/** Step 3: a word is front if it contains e, ü (ү) or ö (ө) and no a. */
function isFrontWord(word: string): boolean {
  if (word.includes("a")) return false;
  for (const ch of word) if (ch === "e" || FRONT_VOWELS.has(ch)) return true;
  return false;
}

/** Step 4 for one word (no separators inside). */
function word(w: string): string {
  const front = isFrontWord(w);
  let out = "";
  let i = 0;
  while (i < w.length) {
    const rest = w.slice(i);
    const multi = MULTI.find(([from]) => rest.startsWith(from));
    if (multi) {
      out += multi[1];
      i += multi[0].length;
      continue;
    }
    const ch = w[i]!;
    const prev = i > 0 ? w[i - 1]! : "";
    const next = i + 1 < w.length ? w[i + 1]! : "";
    if ((ch === "y" || ch === "i") && VOWELS.has(prev) && !VOWELS.has(next)) {
      out += L.shortI;
    } else if (ch === "u") {
      out += front ? L.uFront : L.uBack;
    } else if (ch === "o") {
      out += front ? L.oFront : L.oBack;
    } else if (ch === "y") {
      out += L.yOther;
    } else {
      out += SINGLE[ch] ?? ch; // digits, punctuation and the already mapped front vowels stay as they are
    }
    i += 1;
  }
  return out;
}

/** ADR-0006 §2.4 latinToCyrillic. Words are split on spaces and hyphens; the separators are kept. */
export function latinToCyrillic(input: string): string {
  return prepare(input)
    .split(/([\s-]+)/u)
    .map((part, idx) => (idx % 2 === 1 ? part : word(part)))
    .join("");
}

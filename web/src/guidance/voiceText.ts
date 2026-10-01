// Voice text generator (navigation-ux §4.1; NAV-005 AC 32–33, NAV-017 AC 24–25; glossary C3, C4, A8–A13). 1:1 port
// of the Android instructions/VoiceText.kt. Pure: the words come from the resource files through `t`, so the
// Mongolian and English texts share one code path. Units are spelled out («метрт», «километрт»), numbers stay digits,
// Mongolian decimal comma, English point. English distance templates have singular and plural forms: the singular only
// when the formatted number is exactly "1" (NAV-005-D4); the Mongolian items are identical. Valhalla text is never an
// input (ADR-0008).
import type { Lang, MessageKey } from "../i18n/i18n";
import { capitalizeFirst, messageKey, type KeyResult } from "../route/instructions";

export type T = (key: MessageKey) => string;

/** What a voice prompt says. Language-neutral; rendered by `renderVoice`. */
export type VoiceContent =
  /** A manoeuvre prompt with the distance prefix from `distanceM` (none below 30 m), optionally chained (A13). */
  | { kind: "maneuver"; key: KeyResult; distanceM: number; then: KeyResult | null }
  /** The depart text at the start (§4.3), optionally chained with the first manoeuvre. */
  | { kind: "depart"; key: KeyResult; then: KeyResult | null }
  /** «{n} метрт очих газартаа хүрнэ» (A11). */
  | { kind: "approaching"; distanceM: number }
  /** «Та очих газартаа ирлээ» or the side variant. */
  | { kind: "arrival"; key: KeyResult }
  /** «{n} километр үргэлжлүүлэн явна уу» (A12). */
  | { kind: "continueOn"; distanceM: number };

export interface PrefixDistance {
  number: string;
  kilometres: boolean;
}

/** One decimal below 9,950 m («1,5», «,0» dropped), whole kilometres from 9,950 m. */
export function kilometres(d: number, lang: Lang): string {
  if (d >= 9950) return String(Math.round(d / 1000));
  const tenths = Math.round(d / 100);
  const whole = Math.trunc(tenths / 10);
  const frac = tenths % 10;
  return frac === 0 ? String(whole) : `${whole}${lang === "mn" ? "," : "."}${frac}`;
}

/** Distance prefix number and unit for `d` metres; null below 30 m (no prefix). */
export function prefixDistance(d: number, lang: Lang): PrefixDistance | null {
  if (!Number.isFinite(d) || d < 30) return null;
  if (d < 95) return { number: String(Math.round(d / 10) * 10), kilometres: false };
  if (d < 995) {
    const m = Math.round(d / 50) * 50;
    // D67: a metre value that rounds to 1000 is spoken «1 километрт» / "In 1 kilometer", never «1000 метрт».
    return m >= 1000 ? { number: "1", kilometres: true } : { number: String(m), kilometres: false };
  }
  return { number: kilometres(d, lang), kilometres: true };
}

/** Metres for the approaching prompt (A11): the prefix rule in metres (≤ 500 m by the schedule, §4.3). */
function metres(d: number): string {
  return d < 95 ? String(Math.round(d / 10) * 10) : String(Math.round(d / 50) * 50);
}

/** CLDR `one` applied to the formatted number string (NAV-005-D4): only exactly "1". */
const isOne = (formatted: string): boolean => formatted === "1";

type PluralBase = "voice.prefixM" | "voice.prefixKm" | "voice.approaching" | "voice.continueOn";
function plural(t: T, base: PluralBase, n: string): string {
  return t(`${base}.${isOne(n) ? "one" : "other"}` as MessageKey).replace("{n}", n);
}

export function lowercaseFirst(s: string, lang: Lang): string {
  if (s === "") return s;
  const first = String.fromCodePoint(s.codePointAt(0)!);
  return first.toLocaleLowerCase(lang === "mn" ? "mn-MN" : "en-US") + s.slice(first.length);
}

const ORDINALS: readonly MessageKey[] = [
  "voice.ordinal.1",
  "voice.ordinal.2",
  "voice.ordinal.3",
  "voice.ordinal.4",
  "voice.ordinal.5",
  "voice.ordinal.6",
  "voice.ordinal.7",
  "voice.ordinal.8",
  "voice.ordinal.9",
  "voice.ordinal.10",
];

/** The instruction part of a manoeuvre (voice forms: A10 roundabout with the C4 ordinal). */
export function voiceInstruction(r: KeyResult, t: T): string {
  if (r.key === "roundabout.exit") {
    const n = r.params?.n;
    return n !== undefined && n >= 1 && n <= 10
      ? t("voice.roundaboutExit").replace("{ordinal}", () => t(ORDINALS[n - 1]!))
      : t("maneuver.roundabout.enter");
  }
  let text = t(messageKey(r.key));
  if (r.params) text = text.replace("{n}", String(r.params.n));
  return text;
}

function prefixed(d: number, instruction: string, lang: Lang, t: T): string {
  const dist = prefixDistance(d, lang);
  if (!dist) return capitalizeFirst(instruction, lang);
  const prefix = plural(t, dist.kilometres ? "voice.prefixKm" : "voice.prefixM", dist.number);
  const rest = lowercaseFirst(instruction, lang);
  return lang === "mn" ? `${prefix} ${rest}` : `${prefix}, ${rest}`;
}

function chained(first: string, then: KeyResult | null, lang: Lang, t: T): string {
  if (!then) return first;
  const second = lowercaseFirst(voiceInstruction(then, t), lang);
  // Function replacers: the inserted texts are used literally (no "$&"-style patterns).
  return capitalizeFirst(t("voice.then").replace("{first}", () => first).replace("{second}", () => second), lang);
}

/** The spoken text of one prompt in `lang` (VoiceText.render). */
export function renderVoice(c: VoiceContent, lang: Lang, t: T): string {
  switch (c.kind) {
    case "maneuver":
      return chained(prefixed(c.distanceM, voiceInstruction(c.key, t), lang, t), c.then, lang, t);
    case "depart":
      return chained(capitalizeFirst(voiceInstruction(c.key, t), lang), c.then, lang, t);
    case "approaching":
      return capitalizeFirst(plural(t, "voice.approaching", metres(c.distanceM)), lang);
    case "arrival":
      return capitalizeFirst(voiceInstruction(c.key, t), lang);
    case "continueOn":
      return capitalizeFirst(plural(t, "voice.continueOn", kilometres(c.distanceM, lang)), lang);
  }
}

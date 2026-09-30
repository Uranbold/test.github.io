// On-screen turn instruction text from OSRM manoeuvre fields (ADR-0008, accepted 2026-09-30; NAV-004 AC 26–29, 31).
// Step 1, `maneuverKey`, is language-neutral and has no DOM, MapLibre or i18n dependency, so NAV-005 (Android, iOS,
// Ferrostar banners) can port it and test against the same fixture file (maneuvers.fixture.json). Step 2,
// `instructionText`, looks the key up in the resource files (`maneuver.<key>`) and capitalises the first letter.
// Valhalla's own narrative (maneuver.instruction, banner and voice text) is never used, in either language.
import type { Lang, MessageKey } from "../i18n/i18n";
import { stripTraditionalScript } from "../search/text";

/** Every key the rule table can return (resource key = "maneuver." + key). */
export const MANEUVER_KEYS = [
  "depart.n",
  "depart.ne",
  "depart.e",
  "depart.se",
  "depart.s",
  "depart.sw",
  "depart.w",
  "depart.nw",
  "arrive",
  "arrive.left",
  "arrive.right",
  "roundabout.exit",
  "roundabout.enter",
  "roundabout.leave",
  "uturn",
  "keep.left",
  "keep.right",
  "merge",
  "merge.left",
  "merge.right",
  "onRamp",
  "onRamp.left",
  "onRamp.right",
  "offRamp",
  "offRamp.left",
  "offRamp.right",
  "turn.left",
  "turn.right",
  "turn.slightLeft",
  "turn.slightRight",
  "turn.sharpLeft",
  "turn.sharpRight",
  "continue",
] as const;

export type ManeuverKey = (typeof MANEUVER_KEYS)[number];

export interface ManeuverInput {
  type: string;
  modifier?: string | undefined;
  exit?: number | undefined;
  bearing_after?: number | undefined;
}

export interface KeyResult {
  key: ManeuverKey;
  params?: { n: number };
}

const SECTORS = ["n", "ne", "e", "se", "s", "sw", "w", "nw"] as const;

/**
 * ADR-0008 §2 rule 1: 8 half-open sectors of 45° centred on 0°, 45°, …; `floor(((b mod 360) + 22.5) / 45) mod 8`.
 * 22.4° → n, 22.5° → ne, 337.5° → n, 360° → n. Negative bearings are normalised too.
 */
export function departSector(bearing: number): (typeof SECTORS)[number] {
  const b = ((bearing % 360) + 360) % 360;
  return SECTORS[Math.floor((b + 22.5) / 45) % 8]!;
}

/** `left` for left / slight left / sharp left, `right` likewise, otherwise none (ADR-0008 §2). */
export function side(modifier: string | undefined): "left" | "right" | null {
  if (modifier === "left" || modifier === "slight left" || modifier === "sharp left") return "left";
  if (modifier === "right" || modifier === "slight right" || modifier === "sharp right") return "right";
  return null;
}

const TURN_KEYS: Readonly<Record<string, ManeuverKey>> = {
  left: "turn.left",
  right: "turn.right",
  "slight left": "turn.slightLeft",
  "slight right": "turn.slightRight",
  "sharp left": "turn.sharpLeft",
  "sharp right": "turn.sharpRight",
};

const bySide = <K extends ManeuverKey>(modifier: string | undefined, none: K, left: K, right: K): K => {
  const s = side(modifier);
  return s === "left" ? left : s === "right" ? right : none;
};

/** ADR-0008 §2: the first matching rule wins. Unknown types and modifiers fall through to rules 11 and 12. */
export function maneuverKey(m: ManeuverInput): KeyResult {
  const { type, modifier } = m;
  // 1. depart with a bearing
  if (type === "depart" && typeof m.bearing_after === "number" && Number.isFinite(m.bearing_after)) {
    return { key: `depart.${departSector(m.bearing_after)}` };
  }
  // 2. arrive
  if (type === "arrive") return { key: bySide(modifier, "arrive", "arrive.left", "arrive.right") };
  // 3–4. roundabout / rotary, with or without an exit number
  if (type === "roundabout" || type === "rotary") {
    if (typeof m.exit === "number" && Number.isInteger(m.exit) && m.exit >= 1) return { key: "roundabout.exit", params: { n: m.exit } };
    return { key: "roundabout.enter" };
  }
  // 5. leaving the roundabout (the modifier is ignored)
  if (type === "exit roundabout" || type === "exit rotary") return { key: "roundabout.leave" };
  // 6. U-turn, any remaining type
  if (modifier === "uturn") return { key: "uturn" };
  // 7–10. fork, merge, ramps
  if (type === "fork") return { key: bySide(modifier, "continue", "keep.left", "keep.right") };
  if (type === "merge") return { key: bySide(modifier, "merge", "merge.left", "merge.right") };
  if (type === "on ramp") return { key: bySide(modifier, "onRamp", "onRamp.left", "onRamp.right") };
  if (type === "off ramp") return { key: bySide(modifier, "offRamp", "offRamp.left", "offRamp.right") };
  // 11. any turning modifier (turn, end of road, continue, new name, notification, unknown types)
  const turn = modifier !== undefined ? TURN_KEYS[modifier] : undefined;
  if (turn) return { key: turn };
  // 12. everything else: straight, no modifier, unknown, depart without a bearing
  return { key: "continue" };
}

/** Resource key of a manoeuvre key. */
export function messageKey(key: ManeuverKey): MessageKey {
  return `maneuver.${key}` as MessageKey;
}

/** Upper-cases the first letter; idempotent (AC 48 allows lower-case glossary forms in the resource files). */
export function capitalizeFirst(s: string, lang: Lang): string {
  if (s === "") return s;
  const first = String.fromCodePoint(s.codePointAt(0)!);
  return first.toLocaleUpperCase(lang === "mn" ? "mn-MN" : "en-US") + s.slice(first.length);
}

/** ADR-0008 §2 step 2: localised text for one manoeuvre (AC 27). */
export function instructionText(m: ManeuverInput, lang: Lang, t: (key: MessageKey) => string): string {
  const r = maneuverKey(m);
  let text = t(messageKey(r.key));
  if (r.params) text = text.replace("{n}", String(r.params.n));
  return capitalizeFirst(text, lang);
}

/** Zero-width characters removed from street names (AC 26, NAV-007 F4). */
const ZERO_WIDTH = /\u200B|\u200C|\u200D|\uFEFF/g;

/**
 * `step.name` for display: zero-width characters removed, traditional Mongolian script removed as in NAV-003 AC 18
 * (screen spec › Content rules › Street names), whitespace trimmed. "" means the name line is omitted.
 */
export function streetName(name: string | undefined): string {
  return stripTraditionalScript((name ?? "").replace(ZERO_WIDTH, ""));
}

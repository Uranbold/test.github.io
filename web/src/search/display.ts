// Display rules for a result (story AC 17–20, 45; screen spec › Result content rules; ADR-0006 §2.6).
// Pure: returns resource keys and data values, never localised text.
import lexicon from "./lexicon.json";
import type { PhotonFeature, PhotonProperties } from "./photon";
import { stripTraditionalScript } from "./text";

/** Resource keys of the type-label rules 1–32 (src/i18n/{mn,en}.json); rule 4a reuses "placeType.district". */
export type PlaceTypeKey =
  | "placeType.district"
  | "placeType.khoroo"
  | "placeType.aimag"
  | "placeType.soum"
  | "placeType.city"
  | "placeType.settlement"
  | "placeType.neighbourhood"
  | "placeType.square"
  | "placeType.fuel"
  | "placeType.hospital"
  | "placeType.pharmacy"
  | "placeType.school"
  | "placeType.university"
  | "placeType.restaurant"
  | "placeType.hotel"
  | "placeType.mall"
  | "placeType.shop"
  | "placeType.market"
  | "placeType.bank"
  | "placeType.busStop"
  | "placeType.railwayStation"
  | "placeType.airport"
  | "placeType.parking"
  | "placeType.museum"
  | "placeType.historic"
  | "placeType.worship"
  | "placeType.park"
  | "placeType.government"
  | "placeType.embassy"
  | "placeType.road"
  | "placeType.address"
  | "placeType.place";

/**
 * Name endings of rules 1–4: Cyrillic plus the common Latin forms that Photon returns with `lang=en` (`name:en`, for
 * example "Chingeltei duureg, 5-g khoroo"). Every ending starts with a space, so it must follow a space ("Khoroo 5" and
 * a bare "Хороо" do not match). PO approval F2 (story D33).
 */
const SUFFIX: Readonly<Record<"district" | "khoroo" | "aimag" | "soum", readonly string[]>> = lexicon.typeLabelNameSuffixes;

const is = (p: PhotonProperties, key: string, values?: readonly string[]): boolean =>
  p.osm_key === key && (values === undefined || (p.osm_value !== undefined && values.includes(p.osm_value)));
/** Case-insensitive, NFC on both sides, so a decomposed "düüreg" matches too. */
const fold = (s: string): string => s.normalize("NFC").toLowerCase();
const endsWithAny = (name: string, endings: readonly string[]): boolean => {
  const n = fold(name);
  return endings.some((e) => n.endsWith(fold(e)));
};

type Rule = readonly [PlaceTypeKey, (p: PhotonProperties, name: string) => boolean];

/**
 * Story section D, table "Type labels" (rows 1, 2, 3, 4, 4a, 5–31; row 32 is the fallback in typeLabelKey): applied
 * top to bottom, the first match wins. Rules 1–4 read the name ending (it differs between `lang=mn` and `lang=en`);
 * rules 4a–31 read Photon's `osm_key` / `osm_value` / `type`, which are the same in both languages (AC 19).
 */
export const TYPE_LABEL_RULES: readonly Rule[] = [
  ["placeType.district", (_, n) => endsWithAny(n, SUFFIX.district)],
  ["placeType.khoroo", (_, n) => endsWithAny(n, SUFFIX.khoroo)],
  ["placeType.aimag", (_, n) => endsWithAny(n, SUFFIX.aimag)],
  ["placeType.soum", (p, n) => endsWithAny(n, SUFFIX.soum) && (p.osm_key === "boundary" || p.osm_key === "place")],
  // Rule 4a: a düüreg boundary by Photon's place type, whatever its name ("Chingeltei" with lang=en). Story R13.
  ["placeType.district", (p) => is(p, "boundary", ["administrative"]) && p.type === "district"],
  ["placeType.city", (p) => is(p, "place", ["city", "town"])],
  ["placeType.settlement", (p) => is(p, "place", ["village", "hamlet", "isolated_dwelling", "locality"])],
  ["placeType.neighbourhood", (p) => is(p, "place", ["suburb", "neighbourhood", "quarter"])],
  ["placeType.square", (p) => is(p, "place", ["square"])],
  ["placeType.fuel", (p) => is(p, "amenity", ["fuel"])],
  ["placeType.hospital", (p) => is(p, "amenity", ["hospital", "clinic", "doctors"])],
  ["placeType.pharmacy", (p) => is(p, "amenity", ["pharmacy"])],
  ["placeType.school", (p) => is(p, "amenity", ["school"])],
  ["placeType.university", (p) => is(p, "amenity", ["university", "college"])],
  ["placeType.restaurant", (p) => is(p, "amenity", ["restaurant", "cafe", "fast_food"])],
  ["placeType.hotel", (p) => is(p, "tourism", ["hotel", "hostel", "guest_house", "motel"])],
  ["placeType.mall", (p) => is(p, "shop", ["mall", "department_store"])],
  ["placeType.shop", (p) => is(p, "shop")],
  ["placeType.market", (p) => is(p, "amenity", ["marketplace"])],
  ["placeType.bank", (p) => is(p, "amenity", ["bank", "atm"])],
  ["placeType.busStop", (p) => is(p, "highway", ["bus_stop"]) || is(p, "public_transport", ["platform", "stop_position"])],
  ["placeType.railwayStation", (p) => is(p, "railway", ["station", "halt"])],
  ["placeType.airport", (p) => is(p, "aeroway", ["aerodrome", "terminal"])],
  ["placeType.parking", (p) => is(p, "amenity", ["parking"])],
  ["placeType.museum", (p) => is(p, "tourism", ["museum"])],
  ["placeType.historic", (p) => is(p, "historic") || is(p, "tourism", ["attraction", "viewpoint"])],
  ["placeType.worship", (p) => is(p, "amenity", ["place_of_worship"])],
  ["placeType.park", (p) => is(p, "leisure", ["park", "garden"])],
  ["placeType.government", (p) => is(p, "office", ["government"]) || is(p, "amenity", ["townhall"])],
  ["placeType.embassy", (p) => is(p, "office", ["diplomatic"]) || is(p, "amenity", ["embassy"])],
  ["placeType.road", (p) => is(p, "highway")],
  ["placeType.address", (p) => clean(p.housenumber) !== ""],
];

const clean = (v: string | undefined): string => (v === undefined ? "" : stripTraditionalScript(v));

/** Type label key (rules 1–32; rule 32 «Газар» / "Place" when nothing matches). */
export function typeLabelKey(p: PhotonProperties): PlaceTypeKey {
  const name = clean(p.name);
  for (const [key, test] of TYPE_LABEL_RULES) if (test(p, name)) return key;
  return "placeType.place";
}

/**
 * Type labels whose point results are shown at zoom 13; all others at 16 (AC 20). The zoom follows the type-label row,
 * which rules 4a–31 derive from language-independent properties, so both UI languages get the same zoom.
 */
const AREA_TYPES = new Set<PlaceTypeKey>([
  "placeType.city",
  "placeType.settlement",
  "placeType.district",
  "placeType.khoroo",
  "placeType.neighbourhood",
  "placeType.aimag",
  "placeType.soum",
]);
export const AREA_ZOOM = 13;
export const POINT_ZOOM = 16;
export const EXTENT_MAX_ZOOM = 17;

export function zoomForType(key: PlaceTypeKey): number {
  return AREA_TYPES.has(key) ? AREA_ZOOM : POINT_ZOOM;
}

/**
 * Name from the data (AC 17): `name`; else `street` + " " + `housenumber`; null when both are absent (the caller shows
 * the type label). Traditional script removed (AC 18).
 */
export function dataName(p: PhotonProperties): string | null {
  const name = clean(p.name);
  if (name) return name;
  const addr = [clean(p.street), clean(p.housenumber)].filter((s) => s !== "").join(" ");
  return addr || null;
}

const CONTEXT_FIELDS = ["district", "locality", "city", "county", "state"] as const;

/** Context line (AC 17): first 2 distinct non-empty values, skipping values equal to the name; null if none. */
export function contextLine(p: PhotonProperties, name: string | null): string | null {
  const out: string[] = [];
  for (const f of CONTEXT_FIELDS) {
    const v = clean(p[f]);
    if (!v || v === name || out.includes(v)) continue;
    out.push(v);
    if (out.length === 2) break;
  }
  return out.length ? out.join(", ") : null;
}

export interface ResultInfo {
  /** Data name, or null when the type label stands in for it. */
  name: string | null;
  typeKey: PlaceTypeKey;
  context: string | null;
  lat: number;
  lon: number;
  /** fitBounds box [[west, south], [east, north]] from Photon's [minLon, maxLat, maxLon, minLat]; null without extent. */
  bounds: [[number, number], [number, number]] | null;
}

export function resultInfo(f: PhotonFeature): ResultInfo {
  const p = f.properties;
  const name = dataName(p);
  const e = p.extent;
  let bounds: ResultInfo["bounds"] = null;
  if (e) {
    const [minLon, maxLat, maxLon, minLat] = e;
    // Degenerate or inverted boxes are treated as points.
    if (maxLon > minLon && maxLat > minLat) bounds = [[minLon, minLat], [maxLon, maxLat]];
  }
  return {
    name,
    typeKey: typeLabelKey(p),
    context: contextLine(p, name),
    lon: f.geometry.coordinates[0],
    lat: f.geometry.coordinates[1],
    bounds,
  };
}

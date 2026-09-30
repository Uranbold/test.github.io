// Types strictly following docs/architecture/api/openapi.yaml 0.4.1 › components.schemas
// PhotonFeatureCollection, PhotonFeature, PhotonProperties (responses of operations `search` and `reverse`).
// Checked against the contract text by photon.test.ts.

export type LonLat = [number, number];

/** openapi PhotonProperties. Every field is optional; `additionalProperties: true`. */
export interface PhotonProperties {
  osm_type?: "N" | "W" | "R";
  osm_id?: number;
  osm_key?: string;
  osm_value?: string;
  type?: string;
  name?: string;
  housenumber?: string;
  street?: string;
  postcode?: string;
  locality?: string;
  district?: string;
  city?: string;
  county?: string;
  state?: string;
  country?: string;
  countrycode?: string;
  /** [minLon, maxLat, maxLon, minLat] as emitted by Photon (ADR-0006 F9). */
  extent?: [number, number, number, number];
  extra?: Record<string, string>;
  [k: string]: unknown;
}

/** openapi PhotonFeature (geometry is always a Point). */
export interface PhotonFeature {
  type: "Feature";
  geometry: { type: "Point"; coordinates: LonLat };
  properties: PhotonProperties;
}

/** openapi PhotonFeatureCollection. */
export interface PhotonFeatureCollection {
  type: "FeatureCollection";
  features: PhotonFeature[];
}

const STRING_FIELDS = [
  "osm_key", "osm_value", "type", "name", "housenumber", "street", "postcode", "locality",
  "district", "city", "county", "state", "country", "countrycode",
] as const;

const isNum = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v);
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

/** Validates one feature; string fields of the wrong type are dropped, a bad geometry drops the feature. */
function parseFeature(raw: unknown): PhotonFeature | null {
  if (!isObj(raw) || !isObj(raw.geometry) || !isObj(raw.properties)) return null;
  const c = raw.geometry.coordinates;
  if (raw.geometry.type !== "Point" || !Array.isArray(c) || c.length < 2 || !isNum(c[0]) || !isNum(c[1])) return null;
  const p = raw.properties;
  const props: PhotonProperties = { ...p };
  for (const k of STRING_FIELDS) if (k in props && typeof props[k] !== "string") delete props[k];
  if (!(p.osm_type === "N" || p.osm_type === "W" || p.osm_type === "R")) delete props.osm_type;
  if (!isNum(p.osm_id)) delete props.osm_id;
  const e = p.extent;
  if (!(Array.isArray(e) && e.length === 4 && e.every(isNum))) delete props.extent;
  return { type: "Feature", geometry: { type: "Point", coordinates: [c[0], c[1]] }, properties: props };
}

/** A parsed FeatureCollection, or null when the body is not one (then the request counts as unavailable). */
export function parseFeatureCollection(body: unknown): PhotonFeatureCollection | null {
  if (!isObj(body) || body.type !== "FeatureCollection" || !Array.isArray(body.features)) return null;
  const features: PhotonFeature[] = [];
  for (const f of body.features) {
    const pf = parseFeature(f);
    if (pf) features.push(pf);
  }
  return { type: "FeatureCollection", features };
}

/** Identity for de-duplication (AC 15): osm_type + osm_id; features without them fall back to name + point. */
export function featureKey(f: PhotonFeature): string {
  const p = f.properties;
  if (p.osm_type && p.osm_id !== undefined) return `${p.osm_type}${p.osm_id}`;
  return `?${p.name ?? ""}@${f.geometry.coordinates[0]},${f.geometry.coordinates[1]}`;
}

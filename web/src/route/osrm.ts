// OSRM-compatible route response (Valhalla 3.9.0 `format=osrm`), the subset the NAV-004 preview reads.
// Strictly typed against docs/architecture/api/openapi.yaml 0.5.0 (OsrmRouteResponse, OsrmRoute, OsrmLeg, OsrmStep,
// OsrmManeuver, OsrmWaypoint). Narrative fields (maneuver.instruction, bannerInstructions, voiceInstructions) are
// deliberately not read: ADR-0008 forbids showing them.

/** openapi LonLat: [lon, lat]. */
export type LonLat = [number, number];

export interface OsrmManeuver {
  type: string;
  modifier?: string;
  location: LonLat;
  bearing_after?: number;
  exit?: number;
}

export interface OsrmStep {
  distance: number;
  duration: number;
  name: string;
  maneuver: OsrmManeuver;
}

export interface OsrmRoute {
  distance: number;
  duration: number;
  /** polyline6 */
  geometry: string;
  steps: OsrmStep[];
}

export interface OsrmRouteResponse {
  routes: OsrmRoute[];
  /** Snap distances in metres, one per location (AC 21). Missing values are null. */
  snapDistances: (number | null)[];
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const isNum = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v);

function lonLat(v: unknown): LonLat | null {
  return Array.isArray(v) && v.length >= 2 && isNum(v[0]) && isNum(v[1]) ? [v[0], v[1]] : null;
}

function parseManeuver(v: unknown): OsrmManeuver | null {
  if (!isObj(v) || typeof v.type !== "string") return null;
  const location = lonLat(v.location);
  if (!location) return null;
  const m: OsrmManeuver = { type: v.type, location };
  if (typeof v.modifier === "string") m.modifier = v.modifier;
  if (isNum(v.bearing_after)) m.bearing_after = v.bearing_after;
  if (isNum(v.exit)) m.exit = v.exit;
  return m;
}

function parseStep(v: unknown): OsrmStep | null {
  if (!isObj(v)) return null;
  const maneuver = parseManeuver(v.maneuver);
  if (!maneuver || !isNum(v.distance) || !isNum(v.duration)) return null;
  return { distance: v.distance, duration: v.duration, name: typeof v.name === "string" ? v.name : "", maneuver };
}

function parseRoute(v: unknown): OsrmRoute | null {
  if (!isObj(v) || !isNum(v.distance) || !isNum(v.duration) || typeof v.geometry !== "string" || v.geometry === "") return null;
  if (!Array.isArray(v.legs) || v.legs.length === 0) return null;
  // Two locations = one leg (the preview never sends via points). Steps of every leg are joined defensively.
  const steps: OsrmStep[] = [];
  for (const leg of v.legs) {
    if (!isObj(leg) || !Array.isArray(leg.steps)) return null;
    for (const s of leg.steps) {
      const step = parseStep(s);
      if (!step) return null;
      steps.push(step);
    }
  }
  if (steps.length === 0) return null;
  return { distance: v.distance, duration: v.duration, geometry: v.geometry, steps };
}

/**
 * Validates a 200 body. `null` when it is not an OSRM route response the preview can show (AC 39: an unparsable
 * 200 is a generic error). At most 3 routes are kept (contract maxItems 3).
 */
export function parseRouteResponse(body: unknown): OsrmRouteResponse | null {
  if (!isObj(body) || body.code !== "Ok" || !Array.isArray(body.routes) || body.routes.length === 0) return null;
  const routes: OsrmRoute[] = [];
  for (const r of body.routes.slice(0, 3)) {
    const route = parseRoute(r);
    if (!route) return null;
    routes.push(route);
  }
  const snapDistances = Array.isArray(body.waypoints)
    ? body.waypoints.map((w) => (isObj(w) && isNum(w.distance) ? w.distance : null))
    : [];
  return { routes, snapDistances };
}

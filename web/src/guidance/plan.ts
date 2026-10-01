// Guidance plan: our text and geometry model of one recorded route (NAV-017, ADR-0011 §6; port of the Android
// route/GuidancePlan.kt, route/OsrmResponse.kt › OsrmPlanParser and TextRewrite). Step i of the plan is step i of the
// Ferrostar route (legs flattened). Banner and voice text come from the manoeuvre fields only (ADR-0008); Valhalla's
// narrative is replaced by opaque tokens before Ferrostar parses the response, so it can never reach the screen or the
// voice (NAV-017 AC 18). Pure: no DOM.
import { maneuverKey, streetName, type KeyResult, type ManeuverInput } from "../route/instructions";
import { decodePolyline } from "../route/polyline";
import { fromLngLat, length, type LatLon } from "./geo";

export interface PlanStep {
  maneuver: ManeuverInput;
  key: KeyResult;
  location: LatLon;
  /** `step.name`, cleaned (zero-width and traditional script removed); "" when empty. */
  street: string;
  distance: number;
  duration: number;
}

export interface GuidancePlan {
  /** 0 = the recorded route (no reroute in a replay). */
  generation: number;
  steps: PlanStep[];
  distance: number;
  duration: number;
  geometry: LatLon[];
  end: LatLon;
}

export const isArrive = (k: KeyResult): boolean => k.key.startsWith("arrive");

type Json = Record<string, unknown>;
const isObj = (v: unknown): v is Json => typeof v === "object" && v !== null && !Array.isArray(v);
const num = (v: unknown): number | undefined => (typeof v === "number" && Number.isFinite(v) ? v : undefined);
const str = (v: unknown): string | undefined => (typeof v === "string" ? v : undefined);

/** `null` when the body is not a usable OSRM `Ok` response with one route (OsrmPlanParser.plan). */
export function buildPlan(root: unknown, generation = 0): GuidancePlan | null {
  if (!isObj(root) || root.code !== "Ok" || !Array.isArray(root.routes)) return null;
  const route = root.routes[0];
  if (!isObj(route)) return null;
  const encoded = str(route.geometry);
  if (encoded === undefined) return null;
  const geometry = fromLngLat(decodePolyline(encoded, 6));
  if (geometry.length < 2) return null;
  if (!Array.isArray(route.legs)) return null;
  const steps: PlanStep[] = [];
  for (const leg of route.legs) {
    if (!isObj(leg) || !Array.isArray(leg.steps)) return null;
    for (const s of leg.steps) {
      if (!isObj(s) || !isObj(s.maneuver)) return null;
      const m = s.maneuver;
      const type = str(m.type);
      if (type === undefined) return null;
      const loc = m.location;
      if (!Array.isArray(loc) || loc.length < 2) return null;
      const lon = num(loc[0]);
      const lat = num(loc[1]);
      if (lon === undefined || lat === undefined) return null;
      const maneuver: ManeuverInput = { type, modifier: str(m.modifier), exit: num(m.exit), bearing_after: num(m.bearing_after) };
      steps.push({
        maneuver,
        key: maneuverKey(maneuver),
        location: { lat, lon },
        street: streetName(str(s.name)),
        distance: num(s.distance) ?? 0,
        duration: num(s.duration) ?? 0,
      });
    }
  }
  if (steps.length === 0) return null;
  return {
    generation,
    steps,
    distance: num(route.distance) ?? length(geometry),
    duration: num(route.duration) ?? 0,
    geometry,
    end: geometry[geometry.length - 1]!,
  };
}

/** Opaque token for a step's `maneuver.instruction` (ADR-0009 §3.1). */
export const textToken = (generation: number, step: number): string => `nav:${generation}:m:${step}`;
/** Opaque token for the banner texts of a step. */
export const bannerToken = (generation: number, step: number, banner: number): string => `nav:${generation}:b:${step}:${banner}`;

function rewriteContent(content: Json, token: string): Json {
  const out: Json = { ...content };
  if ("text" in content) out.text = token;
  if (Array.isArray(content.components)) {
    out.components = content.components.map((c) => (isObj(c) && "text" in c ? { ...c, text: token } : c));
  }
  return out;
}

function rewriteStep(step: Json, gen: number, i: number): Json {
  const out: Json = { ...step };
  if (isObj(step.maneuver) && "instruction" in step.maneuver) out.maneuver = { ...step.maneuver, instruction: textToken(gen, i) };
  if (Array.isArray(step.bannerInstructions)) {
    out.bannerInstructions = step.bannerInstructions.map((b, bi) => {
      if (!isObj(b)) return b;
      const banner: Json = { ...b };
      for (const part of ["primary", "secondary", "sub"]) {
        const c = b[part];
        if (isObj(c)) banner[part] = rewriteContent(c, bannerToken(gen, i, bi));
      }
      return banner;
    });
  }
  if ("voiceInstructions" in step) out.voiceInstructions = [];
  return out;
}

/**
 * ADR-0009 §3.1 text rewrite (TextRewrite.kt): every `maneuver.instruction` and banner text becomes an opaque token,
 * `voiceInstructions` become `[]` and the top-level `warnings` are removed. The input is not modified.
 */
export function rewriteValhallaText(root: unknown, generation = 0): unknown {
  if (!isObj(root)) return root;
  const out: Json = { ...root };
  if (Array.isArray(root.routes)) {
    out.routes = root.routes.map((r) => {
      if (!isObj(r) || !Array.isArray(r.legs)) return r;
      let stepIndex = 0;
      return {
        ...r,
        legs: r.legs.map((l) => {
          if (!isObj(l) || !Array.isArray(l.steps)) return l;
          return { ...l, steps: l.steps.map((s) => (isObj(s) ? rewriteStep(s, generation, stepIndex++) : (stepIndex++, s))) };
        }),
      };
    });
  }
  delete out.warnings;
  return out;
}

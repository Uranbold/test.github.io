// Demo route data at runtime (NAV-017 AC 8–10, 42; ADR-0011 §5): one GET of demo-routes/<id>.json relative to the page
// (the demo folder), parsed in try/catch and shape-checked. Any non-200 status, a non-JSON body or a failed check is a
// data error (AC 10). Results are cached for the page session only (never in storage). The prepared route adds the
// guidance plan, Ferrostar's parsed route and the simulated fixes, so «Эхлэх» can start synchronously (ADR-0011 §7).
import type { DemoRoutePayload, DemoRouteSummary, ManifestEnd } from "../../buildtools/demoMode";
import { initFerrostar, parseFerrostarRoute, type FerrostarBindings } from "../guidance/ferrostarCore";
import { fixesFromTrack, type Fix } from "../guidance/fix";
import type { LatLon } from "../guidance/geo";
import { buildPlan, type GuidancePlan } from "../guidance/plan";

export type { DemoRouteSummary, ManifestEnd };

export interface PreparedRoute {
  summary: DemoRouteSummary;
  plan: GuidancePlan;
  /** Ferrostar's route object (reused by every replay of this entry). */
  ferrostarRoute: unknown;
  core: FerrostarBindings;
  fixes: Fix[];
  /** Route line in [lng, lat] (map order). */
  line: [number, number][];
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

/** Shape check of a fetched payload (the build validated the content; this guards against a wrong file). */
export function checkPayload(v: unknown, id: string): DemoRoutePayload | null {
  if (!isObj(v) || v.schema !== 1 || v.id !== id || !isObj(v.route) || !isObj(v.track)) return null;
  const t = v.track as Record<string, unknown>;
  if (!Array.isArray(t.t_ms) || !Array.isArray(t.lonlat) || t.t_ms.length !== t.lonlat.length || t.t_ms.length < 2) return null;
  if (!t.t_ms.every((x) => typeof x === "number" && Number.isFinite(x))) return null;
  if (!t.lonlat.every((p) => Array.isArray(p) && p.length === 2 && p.every((x) => typeof x === "number" && Number.isFinite(x)))) return null;
  return v as unknown as DemoRoutePayload;
}

export interface LoaderDeps {
  fetch: typeof fetch;
  baseUri: () => string;
  /** WASM bytes of the Ferrostar core (a Vite ?url asset next to the chunk). */
  wasmUrl: string;
}

export class DataError extends Error {}

export class RouteDataLoader {
  private readonly cache = new Map<string, PreparedRoute>();

  constructor(private readonly deps: LoaderDeps) {}

  cached(id: string): PreparedRoute | undefined {
    return this.cache.get(id);
  }

  /** Loads and prepares an entry. Rejects with DataError (AC 10) or an AbortError. */
  async load(summary: DemoRouteSummary, signal: AbortSignal): Promise<PreparedRoute> {
    const hit = this.cache.get(summary.id);
    if (hit) return hit;
    const url = new URL(summary.url, this.deps.baseUri()).href;
    const res = await this.deps.fetch(url, { cache: "no-cache", signal });
    if (res.status !== 200) throw new DataError(`status ${res.status}`);
    let body: unknown;
    try {
      body = JSON.parse(await res.text());
    } catch {
      throw new DataError("not JSON");
    }
    const payload = checkPayload(body, summary.id);
    if (!payload) throw new DataError("shape");
    const plan = buildPlan(payload.route);
    if (!plan || plan.steps.length < 2) throw new DataError("plan");
    let core: FerrostarBindings;
    let ferrostarRoute: unknown;
    try {
      core = await initFerrostar(async () => {
        // Not tied to this selection's signal: the one core load is shared by every entry.
        const r = await this.deps.fetch(new URL(this.deps.wasmUrl, this.deps.baseUri()).href);
        if (r.status !== 200) throw new DataError(`wasm status ${r.status}`);
        return r.arrayBuffer();
      });
      ferrostarRoute = parseFerrostarRoute(core, payload.route, plan, payload.mode);
    } catch (err) {
      if (signal.aborted) throw err;
      throw new DataError(String(err));
    }
    if (signal.aborted) throw new DOMException("aborted", "AbortError");
    const points: LatLon[] = payload.track.lonlat.map(([lon, lat]) => ({ lat, lon }));
    const prepared: PreparedRoute = {
      summary,
      plan,
      ferrostarRoute,
      core,
      fixes: fixesFromTrack(points, payload.track.t_ms),
      line: plan.geometry.map((p) => [p.lon, p.lat]),
    };
    this.cache.set(summary.id, prepared);
    return prepared;
  }
}

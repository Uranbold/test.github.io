// Typed client for the gateway operation `postRoute` (POST /v1/route, Valhalla 3.9.0 pass-through), as the NAV-004
// web route preview uses it: docs/architecture/api/openapi.yaml 0.5.0 › postRoute › "Web route-preview client
// profile", ADR-0008 §4. Story NAV-004 AC 10, 12, 13, 33–39, 51–54.
// Never logs, stores or forwards coordinates or request bodies (AC 52). POST only: no coordinates in URLs (AC 10).
import type { Lang } from "../i18n/i18n";
import { browserTimers, parseRetryAfter, type Timers } from "../search/gateway";
import { parseRouteResponse, type OsrmRouteResponse } from "./osrm";

export const ROUTE_PATH = "/v1/route";
/** AC 36: client timeout (the gateway's route read timeout is 10 s). */
export const ROUTE_TIMEOUT_MS = 12_000;
/** ADR-0008 §4: up to 2 alternatives for every costing (contract maximum). */
export const ROUTE_ALTERNATES = 2;
/** Coordinates are sent with 6 decimals (AC 10 asks for ≥ 5). */
export const COORD_DECIMALS = 6;

/** Contracted costings (0.5.0), one per mode tab (AC 11). */
export type Costing = "auto" | "pedestrian" | "bicycle";

export interface RoutePoint {
  lat: number;
  lon: number;
}

export interface RouteParams {
  origin: RoutePoint;
  destination: RoutePoint;
  costing: Costing;
  /** «Шороон замаас зайлсхийх». Only sent for `auto` (0.5.0 contracts exclude_unpaved for auto only, AC 12). */
  avoidUnpaved: boolean;
  lang: Lang;
}

/** openapi ValhallaRouteRequest, the subset this client sends. */
export interface ValhallaRouteRequest {
  locations: [RoutePoint, RoutePoint];
  costing: Costing;
  costing_options?: { auto: { exclude_unpaved: true } };
  alternates: number;
  format: "osrm";
  banner_instructions: true;
  units: "kilometers";
  language: "mn-MN" | "en-US";
}

export type RouteOutcome =
  | { kind: "ok"; response: OsrmRouteResponse }
  /** 400 NoRoute (AC 33). */
  | { kind: "noRoute" }
  /** 400 NoSegment or ValhallaError 171 (AC 34). */
  | { kind: "outOfArea" }
  /** 400 DistanceExceeded (AC 35: "too far" on walk/bike, "no route" on car). */
  | { kind: "distanceExceeded" }
  /** Other 400, 413, other 4xx, unparsable 200 (AC 39). Never retried. */
  | { kind: "error" }
  /** Network error, CORS failure, 5xx, client timeout while online (AC 36). */
  | { kind: "unavailable" }
  /** 429 (AC 37). */
  | { kind: "rateLimited"; retryAfterS: number }
  /** navigator.onLine is false (AC 38). */
  | { kind: "offline" }
  /** Superseded by a newer triggering action or the panel closed (AC 13, 41). */
  | { kind: "aborted" }
  /** Static public build: routing is off, nothing was sent (AC 53–54). */
  | { kind: "disabled" };

/** AC 10, 12; ADR-0008 §4: request body for the preview. `voice_instructions` is omitted; no toll option. */
export function buildRouteRequest(p: RouteParams): ValhallaRouteRequest {
  const body: ValhallaRouteRequest = {
    locations: [
      { lat: p.origin.lat, lon: p.origin.lon },
      { lat: p.destination.lat, lon: p.destination.lon },
    ],
    costing: p.costing,
    alternates: ROUTE_ALTERNATES,
    format: "osrm",
    banner_instructions: true,
    units: "kilometers",
    language: p.lang === "mn" ? "mn-MN" : "en-US",
  };
  if (p.costing === "auto" && p.avoidUnpaved) body.costing_options = { auto: { exclude_unpaved: true } };
  return body;
}

/** A JSON number with exactly `COORD_DECIMALS` decimals (valid JSON, e.g. 47.918900), so AC 10's "≥ 5 decimals" holds. */
function coord(v: number): string {
  const s = v.toFixed(COORD_DECIMALS);
  return /^-0\.0+$/.test(s) ? s.slice(1) : s;
}

/** Serialises the body; the coordinates keep their trailing zeros (JSON.stringify would drop them). */
export function serializeRouteRequest(body: ValhallaRouteRequest): string {
  const { locations, ...rest } = body;
  const locs = locations.map((l) => `{"lat":${coord(l.lat)},"lon":${coord(l.lon)}}`).join(",");
  const tail = JSON.stringify(rest).slice(1); // `"costing":…}` without the opening brace
  return `{"locations":[${locs}],${tail}`;
}

/** Class of a 400 body (openapi OsrmError / ValhallaError). */
export function classifyBadRequest(body: unknown): "noRoute" | "outOfArea" | "distanceExceeded" | "error" {
  if (typeof body !== "object" || body === null) return "error";
  const b = body as { code?: unknown; error_code?: unknown };
  if (b.code === "NoRoute") return "noRoute";
  if (b.code === "NoSegment") return "outOfArea";
  if (b.code === "DistanceExceeded") return "distanceExceeded";
  if (b.error_code === 171) return "outOfArea";
  return "error";
}

export interface RouteClientDeps {
  baseUrl: string;
  fetch: typeof fetch;
  isOnline: () => boolean;
  /** `config.features.routing` (false in the static public build, AC 53–54). */
  enabled: boolean;
  timers?: Timers;
  timeoutMs?: number;
}

export class RouteClient {
  private readonly timers: Timers;
  private readonly timeoutMs: number;

  constructor(private readonly deps: RouteClientDeps) {
    this.timers = deps.timers ?? browserTimers;
    this.timeoutMs = deps.timeoutMs ?? ROUTE_TIMEOUT_MS;
  }

  get enabled(): boolean {
    return this.deps.enabled;
  }

  get url(): string {
    return this.deps.baseUrl + ROUTE_PATH;
  }

  /** One POST with a 12 s timeout that is distinguishable from supersession (the caller's signal). */
  async route(p: RouteParams, signal: AbortSignal): Promise<RouteOutcome> {
    // AC 54: with routing off, no request is ever built or sent.
    if (!this.deps.enabled) return { kind: "disabled" };
    if (signal.aborted) return { kind: "aborted" };
    if (!this.deps.isOnline()) return { kind: "offline" };
    const body = serializeRouteRequest(buildRouteRequest(p));
    const ctrl = new AbortController();
    let timedOut = false;
    const onAbort = (): void => ctrl.abort();
    signal.addEventListener("abort", onAbort, { once: true });
    const timer = this.timers.setTimeout(() => {
      timedOut = true;
      ctrl.abort();
    }, this.timeoutMs);
    try {
      const res = await this.deps.fetch(this.url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
        signal: ctrl.signal,
        credentials: "omit",
        mode: "cors",
        cache: "no-store",
      });
      if (res.status === 200) {
        let json: unknown;
        try {
          json = await res.json();
        } catch {
          if (signal.aborted && !timedOut) return { kind: "aborted" };
          if (timedOut) return { kind: "unavailable" };
          return { kind: "error" }; // AC 39: a 200 body that cannot be parsed
        }
        if (signal.aborted && !timedOut) return { kind: "aborted" };
        const parsed = parseRouteResponse(json);
        return parsed ? { kind: "ok", response: parsed } : { kind: "error" };
      }
      if (res.status === 429) return { kind: "rateLimited", retryAfterS: parseRetryAfter(res.headers.get("Retry-After")) };
      if (res.status === 400) {
        let json: unknown = null;
        try {
          json = await res.json();
        } catch {
          json = null;
        }
        if (signal.aborted && !timedOut) return { kind: "aborted" };
        return { kind: classifyBadRequest(json) };
      }
      if (res.status >= 500) return { kind: "unavailable" };
      return { kind: "error" }; // 413 and any other 4xx
    } catch {
      if (signal.aborted && !timedOut) return { kind: "aborted" };
      if (timedOut) return { kind: "unavailable" };
      // Network TypeError or CORS failure: offline if the browser says so, otherwise routing is unavailable.
      return this.deps.isOnline() ? { kind: "unavailable" } : { kind: "offline" };
    } finally {
      this.timers.clearTimeout(timer);
      signal.removeEventListener("abort", onAbort);
    }
  }
}

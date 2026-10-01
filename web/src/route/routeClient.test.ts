// postRoute client (openapi.yaml 0.5.0 web route-preview client profile; NAV-004 AC 10, 12, 33–39, 51–54).
import { describe, expect, it, vi } from "vitest";
import { STATIC_DEMO_FEATURES, ALL_FEATURES } from "../config";
import { decodePolyline } from "./polyline";
import { parseRouteResponse } from "./osrm";
import {
  buildRouteRequest,
  classifyBadRequest,
  ROUTE_TIMEOUT_MS,
  RouteClient,
  serializeRouteRequest,
  type RouteParams,
} from "./routeClient";

const P1 = { lat: 47.9189, lon: 106.9176 };
const P3 = { lat: 47.8858, lon: 106.9173 };
const params = (over: Partial<RouteParams> = {}): RouteParams => ({ origin: P1, destination: P3, costing: "auto", avoidUnpaved: false, lang: "mn", ...over });
const signal = () => new AbortController().signal;

/** "_p~iF~ps|U_ulLnnqC_mqNvxq`@" is the reference polyline5 of the Google docs; the polyline6 test encodes [[1,2]]. */
const OK_BODY = {
  code: "Ok",
  routes: [
    {
      distance: 4600,
      duration: 780,
      geometry: "_c`|@_gayB",
      legs: [
        {
          steps: [
            { distance: 4600, duration: 780, name: "Чингисийн өргөн чөлөө", geometry: "x", maneuver: { type: "depart", location: [106.9176, 47.9189], bearing_after: 180, instruction: "Өмнөд руу чиглүүл." } },
            { distance: 0, duration: 0, name: "", geometry: "x", maneuver: { type: "arrive", location: [106.9173, 47.8858], instruction: "Таны зорьсон газарт очлоо." } },
          ],
        },
      ],
    },
  ],
  waypoints: [{ distance: 106 }, { distance: 12.5 }],
};

function client(f: (url: string, init: RequestInit) => Promise<Response>, opts: { online?: boolean; enabled?: boolean } = {}): RouteClient {
  return new RouteClient({ baseUrl: "http://localhost:8080", fetch: f as unknown as typeof fetch, isOnline: () => opts.online ?? true, enabled: opts.enabled ?? true });
}
const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(typeof body === "string" ? body : JSON.stringify(body), { status, headers });

describe("request body (AC 10, 12; ADR-0008 §4)", () => {
  it("has the preview profile fields and no voice_instructions or toll option", () => {
    const b = buildRouteRequest(params());
    expect(b).toEqual({
      locations: [P1, P3],
      costing: "auto",
      alternates: 2,
      format: "osrm",
      banner_instructions: true,
      units: "kilometers",
      language: "mn-MN",
    });
    expect(JSON.stringify(b)).not.toMatch(/voice|toll|highway|ferry/);
    expect(buildRouteRequest(params({ lang: "en" })).language).toBe("en-US");
  });

  it("maps costing per tab and sends exclude_unpaved only for auto", () => {
    expect(buildRouteRequest(params({ avoidUnpaved: true })).costing_options).toEqual({ auto: { exclude_unpaved: true } });
    expect(buildRouteRequest(params({ costing: "pedestrian", avoidUnpaved: true })).costing_options).toBeUndefined();
    expect(buildRouteRequest(params({ costing: "bicycle", avoidUnpaved: true })).costing_options).toBeUndefined();
  });

  it("serialises coordinates with 6 decimals (≥ 5, AC 10) as valid JSON", () => {
    const s = serializeRouteRequest(buildRouteRequest(params({ origin: { lat: 47.9, lon: 106.9 }, destination: { lat: -0.0000001, lon: 107 } })));
    expect(s).toContain('"locations":[{"lat":47.900000,"lon":106.900000},{"lat":0.000000,"lon":107.000000}]');
    const parsed = JSON.parse(s);
    expect(parsed.costing).toBe("auto");
    expect(parsed.alternates).toBe(2);
    for (const m of s.matchAll(/"(lat|lon)":(-?\d+\.(\d+))/g)) expect(m[3]!.length).toBeGreaterThanOrEqual(5);
  });
});

describe("RouteClient.route", () => {
  it("POSTs JSON to {gateway}/v1/route only (AC 10, 51)", async () => {
    const f = vi.fn(async () => json(200, OK_BODY));
    const out = await client(f).route(params(), signal());
    expect(out.kind).toBe("ok");
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/v1/route");
    expect(url).not.toContain("?");
    expect(init).toMatchObject({ method: "POST", credentials: "omit", mode: "cors" });
    expect(JSON.parse(init.body as string).locations).toHaveLength(2);
  });

  it.each([
    [400, { code: "NoRoute", message: "x" }, "noRoute"],
    [400, { code: "NoSegment", message: "x" }, "outOfArea"],
    [400, { error_code: 171, error: "No suitable edges near location" }, "outOfArea"],
    [400, { code: "DistanceExceeded", message: "x" }, "distanceExceeded"],
    [400, { code: "InvalidOptions", message: "x" }, "error"],
    [400, { code: "InvalidValue", message: "x" }, "error"],
    [400, { code: "TooBig", message: "x" }, "error"],
    [400, { code: "SomethingNew", message: "x" }, "error"],
    [400, { error_code: 442, error: "x" }, "error"],
    [400, "not json", "error"],
    [413, { code: "PayloadTooLarge" }, "error"],
    [404, "", "error"],
    [200, "<html>proxy</html>", "error"],
    [200, { code: "Ok", routes: [] }, "error"],
    [200, { code: "NoRoute" }, "error"],
    [500, "", "unavailable"],
    [502, { code: "UpstreamUnavailable" }, "unavailable"],
    [503, { code: "UpstreamUnavailable" }, "unavailable"],
    [504, { code: "UpstreamTimeout" }, "unavailable"],
  ])("HTTP %d %j → %s (AC 33–36, 39)", async (status, body, kind) => {
    const out = await client(async () => json(status, body)).route(params(), signal());
    expect(out.kind).toBe(kind);
  });

  it("429 honours Retry-After, 5 s when missing or invalid (AC 37)", async () => {
    expect(await client(async () => json(429, {}, { "Retry-After": "3" })).route(params(), signal())).toEqual({ kind: "rateLimited", retryAfterS: 3 });
    expect(await client(async () => json(429, {})).route(params(), signal())).toEqual({ kind: "rateLimited", retryAfterS: 5 });
    expect(await client(async () => json(429, {}, { "Retry-After": "soon" })).route(params(), signal())).toEqual({ kind: "rateLimited", retryAfterS: 5 });
    expect(await client(async () => json(429, {}, { "Retry-After": "0" })).route(params(), signal())).toEqual({ kind: "rateLimited", retryAfterS: 5 });
  });

  it("network or CORS failure → unavailable online, offline otherwise (AC 36, 38)", async () => {
    const fail = async () => {
      throw new TypeError("Failed to fetch");
    };
    expect((await client(fail).route(params(), signal())).kind).toBe("unavailable");
    let online = true;
    const c = new RouteClient({
      baseUrl: "http://localhost:8080",
      fetch: (async () => {
        online = false;
        throw new TypeError("Failed to fetch");
      }) as unknown as typeof fetch,
      isOnline: () => online,
      enabled: true,
    });
    expect((await c.route(params(), signal())).kind).toBe("offline");
  });

  it("sends nothing while offline (AC 38)", async () => {
    const f = vi.fn();
    expect((await client(f, { online: false }).route(params(), signal())).kind).toBe("offline");
    expect(f).not.toHaveBeenCalled();
  });

  it("times out after 12 s → unavailable (AC 36)", async () => {
    vi.useFakeTimers();
    try {
      const f = (_u: string, init: RequestInit) =>
        new Promise<Response>((_res, rej) => init.signal!.addEventListener("abort", () => rej(new DOMException("aborted", "AbortError"))));
      const p = client(f).route(params(), signal());
      await vi.advanceTimersByTimeAsync(ROUTE_TIMEOUT_MS - 1);
      let done = false;
      void p.then(() => (done = true));
      await vi.advanceTimersByTimeAsync(0);
      expect(done).toBe(false);
      await vi.advanceTimersByTimeAsync(1);
      expect((await p).kind).toBe("unavailable");
    } finally {
      vi.useRealTimers();
    }
  });

  it("a superseded request reports aborted, not an error (AC 13)", async () => {
    const ctrl = new AbortController();
    const f = (_u: string, init: RequestInit) =>
      new Promise<Response>((_res, rej) => init.signal!.addEventListener("abort", () => rej(new DOMException("aborted", "AbortError"))));
    const p = client(f).route(params(), ctrl.signal);
    ctrl.abort();
    expect((await p).kind).toBe("aborted");
  });
});

describe("static public build (AC 53–54)", () => {
  it("with features.routing off the client never builds or sends a request", async () => {
    expect(STATIC_DEMO_FEATURES.routing).toBe(false);
    expect(ALL_FEATURES.routing).toBe(true);
    const f = vi.fn();
    const c = client(f, { enabled: STATIC_DEMO_FEATURES.routing });
    const spy = vi.spyOn(JSON, "stringify");
    try {
      for (const costing of ["auto", "pedestrian", "bicycle"] as const) {
        expect(await c.route(params({ costing, avoidUnpaved: true }), signal())).toEqual({ kind: "disabled" });
      }
      expect(spy).not.toHaveBeenCalled();
    } finally {
      spy.mockRestore();
    }
    expect(f).not.toHaveBeenCalled();
  });
});

describe("classifyBadRequest", () => {
  it("handles non-object bodies", () => {
    expect(classifyBadRequest(null)).toBe("error");
    expect(classifyBadRequest("NoRoute")).toBe("error");
  });
});

describe("OSRM response parsing", () => {
  it("keeps the fields the preview needs and never the narrative", () => {
    const r = parseRouteResponse(OK_BODY)!;
    expect(r.routes).toHaveLength(1);
    expect(r.snapDistances).toEqual([106, 12.5]);
    expect(r.routes[0]!.steps.map((s) => s.maneuver.type)).toEqual(["depart", "arrive"]);
    expect(JSON.stringify(r)).not.toContain("instruction");
    expect(JSON.stringify(r)).not.toContain("зорьсон");
  });

  it("rejects malformed routes and keeps at most 3", () => {
    const route = OK_BODY.routes[0]!;
    expect(parseRouteResponse({ ...OK_BODY, routes: [{ ...route, geometry: 5 }] })).toBeNull();
    expect(parseRouteResponse({ ...OK_BODY, routes: [{ ...route, legs: [] }] })).toBeNull();
    expect(parseRouteResponse({ ...OK_BODY, routes: [route, route, route, route] })!.routes).toHaveLength(3);
    expect(parseRouteResponse({ code: "Ok", routes: [route] })!.snapDistances).toEqual([]);
  });

  it("decodes polyline6 geometries into [lng, lat]", () => {
    expect(decodePolyline("_c`|@_gayB")).toEqual([[2, 1]]);
    // Google's reference polyline (precision 5)
    expect(decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)).toEqual([
      [-120.2, 38.5],
      [-120.95, 40.7],
      [-126.453, 43.252],
    ]);
    expect(decodePolyline("_p~iF~ps|U_", 5)).toEqual([]);
  });
});

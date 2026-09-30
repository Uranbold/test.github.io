// RouteController: NAV-004 AC 3–5, 8, 11–14, 18, 32–38, 47, 50, 53 with fake timers and a fake client.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Lang } from "../i18n/i18n";
import type { OsrmRouteResponse } from "./osrm";
import type { RouteOutcome, RouteParams } from "./routeClient";
import {
  MODE_SETTLE_MS,
  ROUTE_LOADING_DELAY_MS,
  RouteController,
  snapNoticeDistance,
  type RouteAnnouncement,
  type RouteEnd,
  type RouteView,
} from "./routeController";

interface Call {
  p: RouteParams;
  signal: AbortSignal;
  resolve: (o: RouteOutcome) => void;
}

class FakeClient {
  calls: Call[] = [];
  enabled = true;
  route = (p: RouteParams, signal: AbortSignal): Promise<RouteOutcome> =>
    this.enabled
      ? new Promise((resolve) => this.calls.push({ p, signal, resolve }))
      : Promise.resolve({ kind: "disabled" } as RouteOutcome);
}

const resp = (n = 1, snap: number[] = [10, 20]): OsrmRouteResponse => ({
  routes: Array.from({ length: n }, (_, i) => ({ distance: 1000 * (i + 1), duration: 600 * (i + 1), geometry: "_c`|@_gayB", steps: [] })),
  snapDistances: snap,
});
const ok = (n = 1): RouteOutcome => ({ kind: "ok", response: resp(n) });

const P1: RouteEnd = { point: { lat: 47.9189, lon: 106.9176 }, label: { kind: "myLocation" } };
const P3: RouteEnd = { point: { lat: 47.8858, lon: 106.9173 }, label: { kind: "name", text: "Зайсан" } };
const P3near: RouteEnd = { point: { lat: 47.88585, lon: 106.9173 }, label: { kind: "selected" } }; // ≈ 5.6 m from P3

let client: FakeClient;
let online: boolean;
let lang: Lang;
let c: RouteController;
let views: RouteView[];
let said: RouteAnnouncement[];

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date(2026, 8, 30, 13, 50, 0));
  client = new FakeClient();
  online = true;
  lang = "mn";
  c = new RouteController({ client, lang: () => lang, isOnline: () => online, now: () => Date.now() });
  views = [];
  said = [];
  c.onChange((v) => views.push(v));
  c.onAnnounce((a) => said.push(a));
});
afterEach(() => {
  c.dispose();
  vi.useRealTimers();
});

const flush = () => vi.advanceTimersByTimeAsync(0);

describe("opening (AC 3, 4)", () => {
  it("sends one request at once when the origin is known", async () => {
    c.openWith(P3, P1);
    expect(client.calls).toHaveLength(1);
    expect(client.calls[0]!.p).toMatchObject({ origin: P1.point, destination: P3.point, costing: "auto", avoidUnpaved: false, lang: "mn" });
    expect(c.view.state).toBe("pending");
    client.calls[0]!.resolve(ok(2));
    await flush();
    expect(c.view.state).toBe("route");
    expect(c.view.selected).toBe(0);
    expect(c.view.receivedAt).toBe(new Date(2026, 8, 30, 13, 50, 0).getTime());
    expect(said).toEqual([{ kind: "result" }]);
  });

  it("sends nothing until an origin is set", async () => {
    c.openWith(P3, null);
    expect(client.calls).toHaveLength(0);
    expect(c.view.state).toBe("empty");
    c.setOrigin(P1);
    expect(client.calls).toHaveLength(1);
  });
});

describe("loading (AC 32)", () => {
  it("shows loading after 300 ms and removes the old route at the start of every request", async () => {
    c.openWith(P3, P1);
    client.calls[0]!.resolve(ok());
    await flush();
    expect(c.view.state).toBe("route");
    c.swap();
    expect(c.view.state).toBe("pending");
    expect(c.view.response).toBeNull();
    await vi.advanceTimersByTimeAsync(ROUTE_LOADING_DELAY_MS - 1);
    expect(c.view.state).toBe("pending");
    await vi.advanceTimersByTimeAsync(1);
    expect(c.view.state).toBe("loading");
    expect(said.filter((a) => a.kind === "state")).toEqual([]); // loading is not announced
  });
});

describe("mode tabs (AC 11, 12)", () => {
  it("sends one request 300 ms after the last tab change, for the final tab", async () => {
    c.openWith(P3, P1);
    client.calls[0]!.resolve(ok());
    await flush();
    c.setMode("walk");
    expect(c.view.state).toBe("empty"); // old route removed at once
    await vi.advanceTimersByTimeAsync(100);
    c.setMode("bike");
    await vi.advanceTimersByTimeAsync(MODE_SETTLE_MS - 1);
    expect(client.calls).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(client.calls).toHaveLength(2);
    expect(client.calls[1]!.p.costing).toBe("bicycle");
  });

  it("selecting the current tab does nothing", async () => {
    c.openWith(P3, P1);
    c.setMode("car");
    await vi.advanceTimersByTimeAsync(1000);
    expect(client.calls).toHaveLength(1);
  });

  it("avoid unpaved: one request per toggle on car, none on walk; the value is kept across tabs", async () => {
    c.openWith(P3, P1);
    c.setAvoid(true);
    expect(client.calls).toHaveLength(2);
    expect(client.calls[0]!.signal.aborted).toBe(true);
    expect(client.calls[1]!.p.avoidUnpaved).toBe(true);
    c.setMode("walk");
    await vi.advanceTimersByTimeAsync(MODE_SETTLE_MS);
    expect(client.calls).toHaveLength(3);
    c.setAvoid(false); // hidden on walk; defensive: no request
    expect(client.calls).toHaveLength(3);
    c.setAvoid(true);
    c.setMode("car");
    await vi.advanceTimersByTimeAsync(MODE_SETTLE_MS);
    expect(client.calls[3]!.p).toMatchObject({ costing: "auto", avoidUnpaved: true });
  });
});

describe("ordering (AC 13)", () => {
  it("an older response never replaces a newer result, and only one request is in flight", async () => {
    c.openWith(P3, P1);
    const first = client.calls[0]!;
    c.swap();
    expect(first.signal.aborted).toBe(true);
    const second = client.calls[1]!;
    second.resolve(ok(3));
    await flush();
    first.resolve(ok(1)); // arrives late
    await flush();
    expect(c.view.response!.routes).toHaveLength(3);
    expect(client.calls).toHaveLength(2);
    expect(client.calls.filter((x) => !x.signal.aborted)).toHaveLength(1); // at most 1 in flight
  });
});

describe("same point (AC 14)", () => {
  it("sends no request when start and destination are ≤ 10 m apart", () => {
    c.openWith(P3, P3near);
    expect(client.calls).toHaveLength(0);
    expect(c.view.state).toBe("same-point");
    expect(said).toEqual([{ kind: "state", state: "same-point" }]);
  });

  it("wins over offline (precedence)", () => {
    online = false;
    c.openWith(P3, P3near);
    expect(c.view.state).toBe("same-point");
  });
});

describe("swap (AC 8)", () => {
  it("swaps and sends one request; nothing with a missing point", () => {
    c.openWith(P3, null);
    expect(c.swap()).toBe(false);
    c.setOrigin(P1);
    expect(c.swap()).toBe(true);
    expect(c.origin).toBe(P3);
    expect(c.destination).toBe(P1);
    expect(client.calls).toHaveLength(2);
    expect(client.calls[1]!.p.origin).toEqual(P3.point);
  });
});

describe("errors (AC 33–36, 39)", () => {
  const run = async (o: RouteOutcome, mode: "car" | "walk" = "car", avoid = false) => {
    if (mode !== "car") c.setMode(mode);
    c.setAvoid(avoid);
    c.openWith(P3, P1);
    client.calls.at(-1)!.resolve(o);
    await flush();
    return c.view;
  };

  it("NoRoute → no route, no retry; the avoid hint only with avoid on", async () => {
    expect(await run({ kind: "noRoute" })).toMatchObject({ state: "no-route", retry: "none", avoidHint: false });
  });
  it("NoRoute with avoid on → hint", async () => {
    expect(await run({ kind: "noRoute" }, "car", true)).toMatchObject({ state: "no-route", avoidHint: true });
  });
  it("NoSegment → outside the service area", async () => {
    expect(await run({ kind: "outOfArea" })).toMatchObject({ state: "out-of-area", retry: "none" });
  });
  it("DistanceExceeded → too far on walk/bike, no route on car", async () => {
    expect((await run({ kind: "distanceExceeded" }, "walk")).state).toBe("too-far");
  });
  it("DistanceExceeded on car → no route", async () => {
    expect((await run({ kind: "distanceExceeded" })).state).toBe("no-route");
  });
  it("generic error → no retry", async () => {
    expect(await run({ kind: "error" })).toMatchObject({ state: "error", retry: "none" });
  });

  it("unavailable → retry re-sends once, nothing automatic", async () => {
    expect(await run({ kind: "unavailable" })).toMatchObject({ state: "unavailable", retry: "enabled" });
    await vi.advanceTimersByTimeAsync(60_000);
    expect(client.calls).toHaveLength(1);
    c.retry();
    expect(client.calls).toHaveLength(2);
    c.retry(); // pending now: no retry button
    expect(client.calls).toHaveLength(2);
  });
});

describe("429 (AC 37)", () => {
  it("no request for Retry-After seconds whatever the user does, then one on «Дахин оролдох»", async () => {
    c.openWith(P3, P1);
    client.calls[0]!.resolve({ kind: "rateLimited", retryAfterS: 3 });
    await flush();
    expect(c.view).toMatchObject({ state: "rate-limited", retry: "disabled" });
    c.retry();
    c.setMode("walk");
    await vi.advanceTimersByTimeAsync(MODE_SETTLE_MS);
    c.setMode("bike");
    await vi.advanceTimersByTimeAsync(MODE_SETTLE_MS);
    c.swap();
    expect(client.calls).toHaveLength(1);
    expect(c.view).toMatchObject({ state: "rate-limited", retry: "disabled" });
    expect(c.mode).toBe("bike"); // inputs still update
    await vi.advanceTimersByTimeAsync(3000 - 2 * MODE_SETTLE_MS);
    expect(c.view.retry).toBe("enabled");
    expect(client.calls).toHaveLength(1); // nothing sent automatically
    c.retry();
    expect(client.calls).toHaveLength(2);
    expect(client.calls[1]!.p.costing).toBe("bicycle");
  });
});

describe("offline (AC 38)", () => {
  it("sends nothing offline, then exactly one request when back online", async () => {
    online = false;
    c.openWith(P3, P1);
    expect(c.view.state).toBe("offline");
    expect(client.calls).toHaveLength(0);
    c.setMode("walk");
    await vi.advanceTimersByTimeAsync(MODE_SETTLE_MS);
    expect(client.calls).toHaveLength(0);
    online = true;
    c.online();
    c.online();
    expect(client.calls).toHaveLength(1);
  });

  it("does not request on online when a route is shown", async () => {
    c.openWith(P3, P1);
    client.calls[0]!.resolve(ok());
    await flush();
    c.online();
    expect(client.calls).toHaveLength(1);
  });
});

describe("route selection (AC 18) and closing (AC 41)", () => {
  it("selecting another route sends 0 requests and announces once", async () => {
    c.openWith(P3, P1);
    client.calls[0]!.resolve(ok(3));
    await flush();
    c.selectRoute(2);
    c.selectRoute(2);
    c.selectRoute(5);
    expect(c.view.selected).toBe(2);
    expect(client.calls).toHaveLength(1);
    expect(said.filter((a) => a.kind === "selection")).toHaveLength(1);
  });

  it("close aborts the in-flight request and sends nothing", async () => {
    c.openWith(P3, P1);
    c.setMode("walk");
    c.close();
    await vi.advanceTimersByTimeAsync(5000);
    expect(client.calls).toHaveLength(1);
    expect(client.calls[0]!.signal.aborted).toBe(true);
    expect(c.view.state).toBe("empty");
    expect(c.open).toBe(false);
    expect(c.mode).toBe("walk"); // kept for the session
  });

  it("a language change needs no request (ADR-0008: text is built on the client)", async () => {
    c.openWith(P3, P1);
    client.calls[0]!.resolve(ok());
    await flush();
    lang = "en";
    await vi.advanceTimersByTimeAsync(5000);
    expect(client.calls).toHaveLength(1);
    c.setMode("walk");
    await vi.advanceTimersByTimeAsync(MODE_SETTLE_MS);
    expect(client.calls[1]!.p.lang).toBe("en");
  });
});

describe("static public build (AC 53)", () => {
  it("shows the unavailable (static) row at opening whatever the origin, and on every action; 0 requests", async () => {
    client.enabled = false;
    c.openWith(P3, null);
    expect(c.view).toMatchObject({ state: "static", retry: "enabled" });
    c.setOrigin(P1);
    await flush();
    c.retry();
    await flush();
    c.setMode("walk");
    await vi.advanceTimersByTimeAsync(MODE_SETTLE_MS);
    c.swap();
    await flush();
    c.setMode("car");
    await vi.advanceTimersByTimeAsync(MODE_SETTLE_MS);
    c.setAvoid(true);
    await flush();
    expect(c.view.state).toBe("static");
    expect(client.calls).toHaveLength(0);
    expect(said.filter((a) => a.kind === "state" && a.state === "static").length).toBeGreaterThanOrEqual(6);
  });

  it("offline still wins over static", () => {
    client.enabled = false;
    online = false;
    c.openWith(P3, P1);
    expect(c.view.state).toBe("offline");
  });
});

describe("snap notice (AC 21)", () => {
  it("returns the larger snap distance above 500 m", () => {
    expect(snapNoticeDistance(resp(1, [106, 1416]))).toBe(1416);
    expect(snapNoticeDistance(resp(1, [500, 20]))).toBeNull();
    expect(snapNoticeDistance(resp(1, []))).toBeNull();
  });
});

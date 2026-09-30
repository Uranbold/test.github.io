// ADR-0006 §3 outcome classification; story AC 11, 27, 33–37, 46.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { classifyStatus, Cooldown, DEFAULT_RETRY_AFTER_S, GatewayClient, parseRetryAfter } from "./gateway";

const FC = { type: "FeatureCollection", features: [{ type: "Feature", geometry: { type: "Point", coordinates: [106.9, 47.9] }, properties: { name: "a" } }] };

function client(fetchImpl: typeof fetch, online = true): GatewayClient {
  return new GatewayClient({ baseUrl: "http://localhost:8080", fetch: fetchImpl, isOnline: () => online });
}
const q = { q: "Сүхбаатар талбай", lang: "mn" as const, limit: 8, lat: "47.919", lon: "106.918" };
const signal = () => new AbortController().signal;

describe("GatewayClient", () => {
  it("builds the contract URLs on the gateway base URL only (AC 11, 27)", () => {
    const c = client(vi.fn());
    const u = new URL(c.searchUrl(q));
    expect(u.origin + u.pathname).toBe("http://localhost:8080/v1/search");
    expect(Object.fromEntries(u.searchParams)).toEqual({ q: "Сүхбаатар талбай", lang: "mn", limit: "8", lat: "47.919", lon: "106.918" });
    const r = new URL(c.reverseUrl({ lat: "47.918900", lon: "106.917600", lang: "en", limit: 1, radius: 0.5 }));
    expect(r.pathname).toBe("/v1/reverse");
    expect(Object.fromEntries(r.searchParams)).toEqual({ lat: "47.918900", lon: "106.917600", lang: "en", limit: "1", radius: "0.5" });
  });

  it("sends a simple CORS GET without credentials", async () => {
    const f = vi.fn(async () => new Response(JSON.stringify(FC), { status: 200 }));
    await client(f as unknown as typeof fetch).search(q, signal());
    const init = (f.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect(init).toMatchObject({ method: "GET", credentials: "omit", mode: "cors" });
    expect(init.headers).toBeUndefined();
  });

  it.each([
    [200, JSON.stringify(FC), "ok"],
    [200, JSON.stringify({ type: "FeatureCollection", features: [] }), "ok"],
    [200, "<html>proxy</html>", "unavailable"],
    [200, JSON.stringify({ hello: 1 }), "unavailable"],
    [400, "Language is not supported", "badRequest"],
    [404, "", "badRequest"],
    [502, "", "unavailable"],
    [503, "", "unavailable"],
    [504, "", "unavailable"],
  ])("HTTP %i %s → %s", async (status, body, kind) => {
    const r = await client((async () => new Response(body, { status })) as unknown as typeof fetch).search(q, signal());
    expect(r.kind).toBe(kind);
  });

  it("429 reads Retry-After (AC 35); missing or invalid → 5 s", async () => {
    const withHeader = (h: string | null) =>
      client((async () => new Response("", { status: 429, headers: h === null ? {} : { "Retry-After": h } })) as unknown as typeof fetch).search(q, signal());
    expect(await withHeader("3")).toEqual({ kind: "rateLimited", retryAfterS: 3 });
    expect(await withHeader(null)).toEqual({ kind: "rateLimited", retryAfterS: DEFAULT_RETRY_AFTER_S });
    expect(await withHeader("0")).toEqual({ kind: "rateLimited", retryAfterS: 5 });
    expect(parseRetryAfter("1.5")).toBe(5);
    expect(parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).toBe(5);
    expect(parseRetryAfter(" 12 ")).toBe(12);
    expect(classifyStatus(429)).toBe("rateLimited");
  });

  it("network or CORS failure → unavailable while online, offline when the browser is offline", async () => {
    const boom = (async () => {
      throw new TypeError("Failed to fetch");
    }) as unknown as typeof fetch;
    expect((await client(boom).search(q, signal())).kind).toBe("unavailable");
    let online = true;
    const c = new GatewayClient({
      baseUrl: "http://x",
      fetch: (async () => {
        online = false;
        throw new TypeError("Failed to fetch");
      }) as unknown as typeof fetch,
      isOnline: () => online,
    });
    expect((await c.search(q, signal())).kind).toBe("offline");
  });

  it("offline sends nothing (AC 34)", async () => {
    const f = vi.fn();
    expect((await client(f as unknown as typeof fetch, false).search(q, signal())).kind).toBe("offline");
    expect(f).not.toHaveBeenCalled();
  });

  describe("timeouts", () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());
    const hanging = ((_: string, init: RequestInit) =>
      new Promise((_res, rej) => init.signal?.addEventListener("abort", () => rej(new DOMException("aborted", "AbortError"))))) as unknown as typeof fetch;

    it("no response within 8 s → unavailable (AC 33)", async () => {
      const p = client(hanging).search(q, signal());
      await vi.advanceTimersByTimeAsync(7999);
      let done = false;
      void p.then(() => (done = true));
      await Promise.resolve();
      expect(done).toBe(false);
      await vi.advanceTimersByTimeAsync(1);
      expect((await p).kind).toBe("unavailable");
    });

    it("the caller's abort is supersession, not a failure (AC 5)", async () => {
      const ctrl = new AbortController();
      const p = client(hanging).search(q, ctrl.signal);
      ctrl.abort();
      expect((await p).kind).toBe("aborted");
      expect((await client(hanging).search(q, ctrl.signal)).kind).toBe("aborted");
    });
  });
});

describe("Cooldown", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());
  it("is active for N s and calls onEnd once, sending nothing itself", () => {
    const c = new Cooldown(() => Date.now());
    const end = vi.fn();
    c.start(3, end);
    expect(c.active).toBe(true);
    vi.advanceTimersByTime(2999);
    expect(c.active).toBe(true);
    expect(end).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(c.active).toBe(false);
    expect(end).toHaveBeenCalledTimes(1);
  });
});

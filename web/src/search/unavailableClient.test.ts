// Static public demo (NAV-002 AC 53, D44): search and reverse off, the unavailable state within 1 s, 0 requests.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GatewayClient } from "./gateway";
import { ReverseController } from "./reverseController";
import { DEBOUNCE_MS, SearchController, type SearchView } from "./searchController";
import { refusingFetch, UNAVAILABLE_CLIENT } from "./unavailableClient";

let fetchSpy: ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.useFakeTimers();
  fetchSpy = vi.fn(() => Promise.reject(new Error("network must not be used")));
  vi.stubGlobal("fetch", fetchSpy);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("static demo: search off (AC 53)", () => {
  it("a settled query of >= 2 characters shows «unavailable» with an enabled retry within 1 s, without a request", async () => {
    const c = new SearchController({ client: UNAVAILABLE_CLIENT, lang: () => "mn", bias: () => ({ lat: 47.9, lon: 106.9 }), isOnline: () => true });
    const views: SearchView[] = [];
    c.onChange((v) => views.push(v));
    for (const q of ["Су", "Сүхбаатар", "Sukhbaatar", "БЗД"]) {
      c.input(q);
      await vi.advanceTimersByTimeAsync(DEBOUNCE_MS); // well under 1 s
      expect(c.view.state).toBe("unavailable");
      expect(c.view.retry).toBe("enabled");
      expect(c.view.busy).toBe(false);
    }
    // «Дахин оролдох» shows the same state again at once (no endless spinner)
    c.retry();
    await vi.advanceTimersByTimeAsync(0);
    expect(c.view.state).toBe("unavailable");
    await vi.advanceTimersByTimeAsync(10_000);
    expect(views.some((v) => v.state === "loading")).toBe(false);
    expect(fetchSpy).not.toHaveBeenCalled();
    c.dispose();
  });

  it("a coordinate card shows «unavailable» in the nearest-place area, without a request", async () => {
    const r = new ReverseController({ client: UNAVAILABLE_CLIENT, lang: () => "en", isOnline: () => true });
    r.open({ lat: 47.918873, lon: 106.917701 });
    await vi.advanceTimersByTimeAsync(0);
    expect(r.view).toEqual({ state: "unavailable", feature: null, retry: "enabled" });
    r.retry();
    await vi.advanceTimersByTimeAsync(0);
    expect(r.view?.state).toBe("unavailable");
    expect(fetchSpy).not.toHaveBeenCalled();
    r.dispose();
  });

  it("the guard fetch refuses without a request, so even a bypassed GatewayClient sends nothing", async () => {
    const g = new GatewayClient({ baseUrl: "https://demo-host.example", fetch: refusingFetch, isOnline: () => true });
    const out = await g.search({ q: "Сүхбаатар", lang: "mn", limit: 8, lat: "47.919", lon: "106.918" }, new AbortController().signal);
    expect(out).toEqual({ kind: "unavailable" });
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});

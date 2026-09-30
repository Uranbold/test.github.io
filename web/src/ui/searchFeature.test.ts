// @vitest-environment jsdom
// Static public demo (NAV-002 AC 53, D44; NAV-003 screen spec › States › Static public demo): the SearchFeature wiring
// sends nothing, shows «Хайлт түр ажиллахгүй байна» with «Дахин оролдох», and describes the enabled input as "search off".
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { loadConfig } from "../config";
import { I18n } from "../i18n/i18n";
import { DEBOUNCE_MS } from "../search/searchController";
import { SearchFeature } from "./searchFeature";
import type { Tooltip } from "./tooltip";

const html = readFileSync(resolve(process.cwd(), "index.html"), "utf8");

function build(env: { VITE_STATIC_DEMO?: string }, fetchSpy: typeof fetch): SearchFeature {
  document.documentElement.innerHTML = html.replace(/^[\s\S]*?<html[^>]*>/, "").replace(/<\/html>\s*$/, "");
  const tooltip = { attach: vi.fn(), hide: vi.fn() } as unknown as Tooltip;
  return new SearchFeature({
    cfg: loadConfig({ ...env, VITE_GATEWAY_BASE_URL: "same-origin", BASE_URL: "/" }, "https://demo-host.example"),
    i18n: new I18n("mn"),
    tooltip,
    map: () => null,
    bias: () => ({ lat: 47.9, lon: 106.9 }),
    mapReady: () => true,
    stopFollowing: () => undefined,
    fetch: fetchSpy,
  });
}

let fetchSpy: ReturnType<typeof vi.fn>;
beforeEach(() => {
  vi.useFakeTimers();
  fetchSpy = vi.fn(() => Promise.reject(new TypeError("network")));
  vi.stubGlobal("fetch", fetchSpy);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("SearchFeature in the static demo build", () => {
  it("typing and retry show the unavailable row with «Дахин оролдох» within 1 s and send 0 requests", async () => {
    const f = build({ VITE_STATIC_DEMO: "true" }, fetchSpy as unknown as typeof fetch);
    const input = document.getElementById("search-input") as HTMLInputElement;
    input.value = "Сүхбаатар";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS);
    const row = document.querySelector<HTMLElement>('[data-testid="search-state"]')!;
    expect(row.dataset.state).toBe("unavailable");
    expect(row.textContent).toContain("Хайлт түр ажиллахгүй байна");
    const retry = document.querySelector<HTMLButtonElement>('[data-testid="search-retry"]')!;
    // The label comes from data-i18n (App.applyI18n fills it; not running in this unit test).
    expect(retry.closest("[hidden]")).toBeNull();
    expect(retry.querySelector("[data-i18n]")?.getAttribute("data-i18n") ?? retry.dataset.i18n).toBe("action.retry");
    retry.click();
    await vi.advanceTimersByTimeAsync(0);
    expect(f.controller.view.state).toBe("unavailable");
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("the input stays enabled and is described by a visually hidden «Хайлт түр ажиллахгүй байна»", () => {
    build({ VITE_STATIC_DEMO: "true" }, fetchSpy as unknown as typeof fetch);
    const input = document.getElementById("search-input") as HTMLInputElement;
    expect(input.disabled).toBe(false);
    const ids = (input.getAttribute("aria-describedby") ?? "").split(" ");
    const note = document.getElementById(ids.at(-1)!)!;
    expect(note.textContent).toBe("Хайлт түр ажиллахгүй байна");
    expect(note.classList.contains("sr-only")).toBe(true);
    expect(note.dataset.i18n).toBe("search.unavailable");
  });

  it("a normal build sends the request through the gateway client and adds no description", async () => {
    build({}, fetchSpy as unknown as typeof fetch);
    const input = document.getElementById("search-input") as HTMLInputElement;
    expect(input.hasAttribute("aria-describedby")).toBe(false);
    input.value = "Сүхбаатар";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(String(fetchSpy.mock.calls[0]![0])).toMatch(/^https:\/\/demo-host\.example\/v1\/search\?/);
  });
});

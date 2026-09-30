// @vitest-environment jsdom
// NAV-003 AC 41 (amended by PO approval F5, D33), screen spec › Interactions › Tab, Components › State row,
// Accessibility › Focus management: Tab closes the popup except for a state row with «Дахин оролдох»; Esc on the
// retry button closes the row and returns focus to the input; the retry button is described by the message; a
// focused retry button that disappears hands focus to the input, never to <body>.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { I18n } from "../i18n/i18n";
import type { PhotonFeature } from "../search/photon";
import { CLOSED, type SearchController, type SearchView } from "../search/searchController";
import type { Tooltip } from "./tooltip";
import { SearchBox } from "./searchBox";

// Vitest runs with web/ as the root; the jsdom environment gives import.meta.url a non-file scheme.
const html = readFileSync(resolve(process.cwd(), "index.html"), "utf8");

/** Minimal controller: the box only reads `view` and forwards events; tests drive the view directly. */
class FakeController {
  view: SearchView = CLOSED;
  private listener: ((v: SearchView) => void) | null = null;
  readonly close = vi.fn(() => this.set(CLOSED));
  readonly retry = vi.fn();
  readonly clear = vi.fn(() => this.set(CLOSED));
  readonly input = vi.fn();
  readonly submit = vi.fn();
  readonly reopen = vi.fn(() => false);
  readonly accept = vi.fn();
  onChange(l: (v: SearchView) => void): () => void {
    this.listener = l;
    return () => (this.listener = null);
  }
  onAnnounce(): () => void {
    return () => undefined;
  }
  set(v: SearchView): void {
    this.view = v;
    this.listener?.(v);
  }
}

const feature = {
  type: "Feature",
  geometry: { type: "Point", coordinates: [106.9177, 47.9186] },
  properties: { name: "Сүхбаатарын талбай", osm_key: "place", osm_value: "square", type: "other", city: "Улаанбаатар" },
} as unknown as PhotonFeature;

const VIEWS: Record<string, SearchView> = {
  results: { state: "results", options: [{ kind: "place", feature }], retry: "none", busy: false, query: "сүхбаатар" },
  coordinate: { state: "coordinate", options: [{ kind: "coordinate", point: { lat: 47.9, lon: 106.9 } }], retry: "none", busy: false, query: "47.9 106.9" },
  loading: { state: "loading", options: [], retry: "none", busy: true, query: "сүх" },
  "no-results": { state: "no-results", options: [], retry: "none", busy: false, query: "qqqq" },
  offline: { state: "offline", options: [], retry: "none", busy: false, query: "сүх" },
  error: { state: "error", options: [], retry: "none", busy: false, query: "сүх" },
  unavailable: { state: "unavailable", options: [], retry: "enabled", busy: false, query: "сүх" },
  "rate-limited": { state: "rate-limited", options: [], retry: "disabled", busy: false, query: "сүх" },
  "rate-limited-ready": { state: "rate-limited", options: [], retry: "enabled", busy: false, query: "сүх" },
};

function key(target: HTMLElement, k: string, init: KeyboardEventInit = {}): KeyboardEvent {
  const e = new KeyboardEvent("keydown", { key: k, bubbles: true, cancelable: true, ...init });
  target.dispatchEvent(e);
  return e;
}

describe("SearchBox Tab / Esc / retry focus (AC 41, F5, D33)", () => {
  let c: FakeController;
  let input: HTMLInputElement;
  let retry: HTMLButtonElement;
  let popup: HTMLElement;
  let stateRow: HTMLElement;

  beforeEach(() => {
    document.documentElement.innerHTML = html.replace(/^[\s\S]*?<html[^>]*>/, "").replace(/<\/html>\s*$/, "");
    c = new FakeController();
    const tooltip = { attach: vi.fn(), hide: vi.fn() } as unknown as Tooltip;
    new SearchBox({ i18n: new I18n("mn"), controller: c as unknown as SearchController, tooltip, onSelect: vi.fn(), onOpenChange: vi.fn() });
    input = document.getElementById("search-input") as HTMLInputElement;
    retry = document.getElementById("search-retry") as HTMLButtonElement;
    popup = document.getElementById("search-popup")!;
    stateRow = document.getElementById("search-state")!;
    input.value = "сүх";
    input.focus();
  });

  describe("Tab from the input", () => {
    it.each(["results", "coordinate", "loading", "no-results", "offline", "error"])("closes the popup in state %s", (name) => {
      c.set(VIEWS[name]!);
      expect(popup.hidden).toBe(false);
      const e = key(input, "Tab");
      expect(c.close).toHaveBeenCalledTimes(1);
      expect(popup.hidden).toBe(true);
      expect(e.defaultPrevented).toBe(false); // focus still moves on to the next stop
      expect(input.value).toBe("сүх"); // no selection, text unchanged
    });

    it("Shift+Tab closes a list of options as well", () => {
      c.set(VIEWS.results!);
      key(input, "Tab", { shiftKey: true });
      expect(c.close).toHaveBeenCalledTimes(1);
    });

    it.each(["unavailable", "rate-limited", "rate-limited-ready"])("keeps a state row with «Дахин оролдох» open in state %s", (name) => {
      c.set(VIEWS[name]!);
      const e = key(input, "Tab");
      expect(c.close).not.toHaveBeenCalled();
      expect(e.defaultPrevented).toBe(false);
      expect(popup.hidden).toBe(false);
      expect(stateRow.hidden).toBe(false);
      expect(retry.hidden).toBe(false);
      // The button is a reachable Tab stop (jsdom has no Tab navigation, so focus it as the browser would).
      retry.focus();
      expect(document.activeElement).toBe(retry);
    });

    it("the disabled retry (rate-limited) stays focusable and does nothing on click", () => {
      c.set(VIEWS["rate-limited"]!);
      expect(retry.getAttribute("aria-disabled")).toBe("true");
      expect(retry.disabled).toBe(false);
      retry.focus();
      retry.click();
      expect(c.retry).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(retry);
    });
  });

  describe("Esc", () => {
    it.each(["unavailable", "rate-limited"])("on the focused retry button closes the %s row, focus goes to the input, text stays", (name) => {
      c.set(VIEWS[name]!);
      retry.focus();
      const e = key(retry, "Escape");
      expect(e.defaultPrevented).toBe(true);
      expect(c.close).toHaveBeenCalledTimes(1);
      expect(popup.hidden).toBe(true);
      expect(document.activeElement).toBe(input);
      expect(input.value).toBe("сүх");
      // A second Esc in the input clears the text (screen spec › Interactions › Tab).
      key(input, "Escape");
      expect(input.value).toBe("");
      expect(c.clear).toHaveBeenCalledTimes(1);
    });

    it("during IME composition on the retry button does nothing", () => {
      c.set(VIEWS.unavailable!);
      retry.focus();
      key(retry, "Escape", { isComposing: true });
      expect(c.close).not.toHaveBeenCalled();
    });

    it("from the input closes a state row with «Дахин оролдох»", () => {
      c.set(VIEWS.unavailable!);
      key(input, "Escape");
      expect(c.close).toHaveBeenCalledTimes(1);
      expect(input.value).toBe("сүх");
    });
  });

  it("the retry button is described by the state message (screen spec › State row)", () => {
    c.set(VIEWS.unavailable!);
    expect(retry.getAttribute("aria-describedby")).toBe("search-state-text");
    const desc = document.getElementById("search-state-text")!;
    expect(stateRow.contains(desc)).toBe(true);
    expect(desc.textContent).toBe("Хайлт түр ажиллахгүй байна");
  });

  describe("a focused retry button that is removed hands focus to the input, never to <body>", () => {
    it.each([
      ["closed (map click, new query cleared)", CLOSED],
      ["results", VIEWS.results!],
      ["coordinate option", VIEWS.coordinate!],
      ["loading", VIEWS.loading!],
      ["no-results", VIEWS["no-results"]!],
      ["offline", VIEWS.offline!],
      ["error", VIEWS.error!],
    ])("next view: %s", (_label, next) => {
      c.set(VIEWS.unavailable!);
      retry.focus();
      expect(document.activeElement).toBe(retry);
      c.set(next);
      expect(document.activeElement).toBe(input);
      expect(document.activeElement).not.toBe(document.body);
    });

    it("stays on the retry button when the row only changes between retry states (rate-limited wait ends)", () => {
      c.set(VIEWS["rate-limited"]!);
      retry.focus();
      c.set(VIEWS["rate-limited-ready"]!);
      expect(document.activeElement).toBe(retry);
      expect(retry.hasAttribute("aria-disabled")).toBe(false);
    });

    it("does not steal focus when the retry button was not focused", () => {
      c.set(VIEWS.unavailable!);
      const lang = document.getElementById("lang-btn") as HTMLButtonElement;
      lang.focus();
      c.set(VIEWS.results!);
      expect(document.activeElement).toBe(lang);
    });
  });
});

// @vitest-environment jsdom
// NAV-002 AC 26 / 55 as amended 2026-10-13 (UX audit UX-07 (b), PO): the first-visit theme is the system colour
// scheme and follows it while nothing is stored; a stored choice (the toggle) wins.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { loadTheme, onSystemThemeChange, saveTheme, storedTheme, systemTheme, THEME_KEY } from "./prefs";

type Listener = (e: { matches: boolean }) => void;

function fakeMatchMedia(dark: boolean) {
  const listeners = new Set<Listener>();
  const mql = {
    matches: dark,
    media: "(prefers-color-scheme: dark)",
    addEventListener: (_: string, l: Listener) => listeners.add(l),
    removeEventListener: (_: string, l: Listener) => listeners.delete(l),
  };
  window.matchMedia = vi.fn(() => mql as unknown as MediaQueryList);
  return {
    change(matches: boolean) {
      mql.matches = matches;
      for (const l of listeners) l({ matches });
    },
    get listeners() {
      return listeners.size;
    },
  };
}

describe("first-visit theme (UX-07)", () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => window.localStorage.clear());

  it("is the system scheme when nothing is stored, day without a preference or matchMedia", () => {
    fakeMatchMedia(true);
    expect(storedTheme()).toBeNull();
    expect(systemTheme()).toBe("night");
    expect(loadTheme()).toBe("night");
    fakeMatchMedia(false);
    expect(loadTheme()).toBe("day");
    (window as unknown as { matchMedia: unknown }).matchMedia = undefined;
    expect(loadTheme()).toBe("day");
  });

  it("lets the stored choice win over the system scheme", () => {
    fakeMatchMedia(true);
    saveTheme("day");
    expect(storedTheme()).toBe("day");
    expect(loadTheme()).toBe("day");
    window.localStorage.setItem(THEME_KEY, "garbage");
    expect(loadTheme()).toBe("night"); // an unknown value is "nothing stored"
  });

  it("follows a system change only while nothing is stored (the toggle stops it), and unsubscribes", () => {
    const mm = fakeMatchMedia(false);
    const seen: string[] = [];
    const off = onSystemThemeChange((t) => seen.push(t));
    expect(mm.listeners).toBe(1);
    mm.change(true);
    expect(seen).toEqual(["night"]);
    saveTheme("night"); // the user pressed the toggle
    mm.change(false);
    expect(seen).toEqual(["night"]);
    off();
    expect(mm.listeners).toBe(0);
  });
});

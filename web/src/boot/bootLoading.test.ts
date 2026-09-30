// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import en from "../i18n/en.json";
import mn from "../i18n/mn.json";
import { LANG_KEY, THEME_KEY } from "../prefs";
import { LOADING_DELAY_MS } from "../state/status";
import { bootLoading, cancelBootLoading, type BootConfig } from "./bootLoading";

const cfg: BootConfig = {
  delayMs: LOADING_DELAY_MS,
  defaultLang: "mn",
  langKey: LANG_KEY,
  themeKey: THEME_KEY,
  strings: {
    mn: { loading: mn["status.loading"], title: mn["app.title"] },
    en: { loading: en["status.loading"], title: en["app.title"] },
  },
};

/** The function as vite.config.ts inlines it: serialised and run without any module scope. */
const inlined = (c: BootConfig): void => {
  new Function(`(${bootLoading.toString()})(${JSON.stringify(c)});`)();
};

const pill = () => document.getElementById("loading") as HTMLElement;

describe("pre-module loading indicator (AC 37)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(performance, "now").mockReturnValue(120);
    localStorage.clear();
    document.documentElement.lang = "mn";
    document.documentElement.dataset.theme = "day";
    document.title = "";
    document.body.innerHTML =
      '<div id="loading" class="pill idle" aria-hidden="true"><span class="spin"></span><span id="loading-text"></span></div>';
  });
  afterEach(() => {
    cancelBootLoading();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("lays the pill out at once with a compositor reveal at navigation start + 300 ms, aria after 300 ms", () => {
    inlined(cfg);
    expect(document.title).toBe(mn["app.title"]);
    // Laid out (not display:none) but pending: transparent until the CSS animation delay has passed.
    expect(pill().classList.contains("idle")).toBe(false);
    expect(pill().classList.contains("pending")).toBe(true);
    expect(pill().style.getPropertyValue("--pill-delay")).toBe(`${LOADING_DELAY_MS - 120}ms`);
    expect(pill().textContent).toBe(mn["status.loading"]);
    expect(pill().getAttribute("aria-hidden")).toBe("true");
    vi.advanceTimersByTime(LOADING_DELAY_MS - 120 - 1);
    expect(pill().getAttribute("aria-hidden")).toBe("true");
    vi.advanceTimersByTime(1);
    expect(pill().getAttribute("aria-hidden")).toBe("false");
    expect(pill().classList.contains("pending")).toBe(false);
    expect(pill().textContent).toBe(mn["status.loading"]);
  });

  it("shows it at once, without the pending reveal, when the script runs after 300 ms", () => {
    vi.spyOn(performance, "now").mockReturnValue(700);
    inlined(cfg);
    expect(pill().classList.contains("idle")).toBe(false);
    expect(pill().classList.contains("pending")).toBe(false);
    vi.advanceTimersByTime(0);
    expect(pill().getAttribute("aria-hidden")).toBe("false");
  });

  it("uses the saved language and night mode (D12)", () => {
    localStorage.setItem(LANG_KEY, "en");
    localStorage.setItem(THEME_KEY, "night");
    inlined(cfg);
    vi.advanceTimersByTime(LOADING_DELAY_MS);
    expect(pill().textContent).toBe(en["status.loading"]);
    expect(document.documentElement.lang).toBe("en");
    expect(document.documentElement.dataset.theme).toBe("night");
    expect(document.title).toBe(en["app.title"]);
  });

  it("ignores an unknown saved language", () => {
    localStorage.setItem(LANG_KEY, "de");
    inlined(cfg);
    vi.advanceTimersByTime(LOADING_DELAY_MS);
    expect(pill().textContent).toBe(mn["status.loading"]);
    expect(document.documentElement.dataset.theme).toBe("day");
  });

  it("leaves the pill to the app once it has taken over (cancelBootLoading)", () => {
    inlined(cfg);
    cancelBootLoading();
    vi.advanceTimersByTime(LOADING_DELAY_MS);
    expect(pill().getAttribute("aria-hidden")).toBe("true");
    expect(pill().classList.contains("pending")).toBe(true);
  });
});

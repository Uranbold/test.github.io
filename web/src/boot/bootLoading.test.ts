// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import en from "../i18n/en.json";
import mn from "../i18n/mn.json";
import { LANG_KEY, THEME_KEY } from "../prefs";
import { LOADING_DELAY_MS, LOADING_REVEAL_MS } from "../state/status";
import { bootLoading, cancelBootLoading, cancelBootReveal, hasBootReveal, type BootConfig } from "./bootLoading";

const cfg: BootConfig = {
  delayMs: LOADING_DELAY_MS,
  revealMs: LOADING_REVEAL_MS,
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

interface FakeAnimation {
  keyframes: Keyframe[];
  options: KeyframeAnimationOptions;
  startTime: number | null;
  cancelled: boolean;
  cancel(): void;
}

describe("pre-module loading indicator (AC 37)", () => {
  let animations: FakeAnimation[];
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(performance, "now").mockReturnValue(120);
    localStorage.clear();
    document.documentElement.lang = "mn";
    document.documentElement.dataset.theme = "day";
    document.title = "";
    // As served: vite.config.ts puts the default-language text into the pending pill.
    document.body.innerHTML =
      `<div id="loading" class="pill pending" aria-hidden="true"><span class="spin"></span><span id="loading-text">${mn["status.loading"]}</span></div>`;
    animations = [];
    // jsdom has no Web Animations API: record what the boot script asks for.
    (HTMLElement.prototype as unknown as { animate: unknown }).animate = function (k: Keyframe[], o: KeyframeAnimationOptions) {
      const a: FakeAnimation = { keyframes: k, options: o, startTime: null, cancelled: false, cancel() { this.cancelled = true; } };
      animations.push(a);
      return a;
    };
  });
  afterEach(() => {
    cancelBootLoading();
    cancelBootReveal();
    delete (HTMLElement.prototype as unknown as { animate?: unknown }).animate;
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("reveals the laid-out pill with an opacity animation pinned to navigation start + 270 ms; aria at 300 ms", () => {
    inlined(cfg);
    expect(document.title).toBe(mn["app.title"]);
    // Laid out (not display:none) but pending: transparent until the animation reveals it.
    expect(pill().classList.contains("idle")).toBe(false);
    expect(pill().classList.contains("pending")).toBe(true);
    expect(pill().textContent).toBe(mn["status.loading"]);
    expect(animations).toHaveLength(1);
    const a = animations[0]!;
    expect(a.keyframes).toEqual([{ opacity: 0 }, { opacity: 1 }]);
    expect(a.options).toMatchObject({ delay: LOADING_REVEAL_MS, fill: "both" });
    // Document timeline origin = navigation start, whatever performance.now() is when the script runs.
    expect(a.startTime).toBe(0);
    expect(LOADING_REVEAL_MS).toBeGreaterThanOrEqual(250); // NAV-002 screen spec tolerance 250–300 ms
    expect(LOADING_REVEAL_MS).toBeLessThanOrEqual(300);
    expect(hasBootReveal()).toBe(true);
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
    expect(pill().getAttribute("aria-hidden")).toBe("false");
    expect(animations).toHaveLength(0);
  });

  it("falls back to the 300 ms timer when Web Animations are missing", () => {
    delete (HTMLElement.prototype as unknown as { animate?: unknown }).animate;
    inlined(cfg);
    expect(hasBootReveal()).toBe(false);
    expect(pill().classList.contains("pending")).toBe(true);
    vi.advanceTimersByTime(LOADING_DELAY_MS);
    expect(pill().classList.contains("pending")).toBe(false);
  });

  it("uses the saved language and night mode (D12)", () => {
    localStorage.setItem(LANG_KEY, "en");
    localStorage.setItem(THEME_KEY, "night");
    inlined(cfg);
    expect(pill().textContent).toBe(en["status.loading"]);
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

  it("leaves the pill to the app once it has taken over (cancelBootLoading), and the app can stop the reveal", () => {
    inlined(cfg);
    cancelBootLoading();
    vi.advanceTimersByTime(LOADING_DELAY_MS);
    expect(pill().getAttribute("aria-hidden")).toBe("true");
    expect(pill().classList.contains("pending")).toBe(true);
    cancelBootReveal();
    expect(animations[0]!.cancelled).toBe(true);
    expect(hasBootReveal()).toBe(false);
  });
});

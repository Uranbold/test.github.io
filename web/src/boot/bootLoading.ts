// Pre-module start-up (NAV-002 AC 37, D12, D15). vite.config.ts inlines bootLoading() into index.html as a tiny
// classic script, so the loading pill appears 270 ms after navigation start (due at 300 ms) even while the module graph
// (MapLibre, ~1 MB) is still downloading or MapLibre's start-up keeps the main thread busy. The strings are injected from src/i18n/{mn,en}.json at transform time;
// nothing here is hard-coded UI text.
//
// bootLoading() is serialised with Function.prototype.toString(): it must stay self-contained (no imports,
// no references to module-level values, only its argument and browser globals).

export interface BootStrings {
  loading: string;
  title: string;
}

export interface BootConfig {
  /** tokens: motion.loading-delay (status.ts LOADING_DELAY_MS): the pill is due (and announced) at this time. */
  delayMs: number;
  /**
   * Visual reveal, ms after navigation start (status.ts LOADING_REVEAL_MS). NAV-002 screen spec › States › Loading allows
   * scheduling it up to 50 ms early (250–300 ms) to absorb the 1–2 frames between the scheduled time and the painted frame.
   */
  revealMs: number;
  defaultLang: string;
  /** localStorage keys from src/prefs.ts. */
  langKey: string;
  themeKey: string;
  strings: Record<string, BootStrings>;
}

/** Global that holds the boot timer, so the app can cancel it once it has taken over (cancelBootLoading). */
export const BOOT_TIMER_GLOBAL = "__navmnBootTimer";
/** Global that holds the reveal animation (an Animation), so the app can cancel it when the pill is no longer needed. */
export const BOOT_ANIMATION_GLOBAL = "__navmnBootPill";

export function bootLoading(c: BootConfig): void {
  const root = document.documentElement;
  const read = (key: string): string | null => {
    try {
      return window.localStorage.getItem(key);
    } catch {
      return null;
    }
  };
  const saved = read(c.langKey);
  const lang = saved !== null && Object.prototype.hasOwnProperty.call(c.strings, saved) ? saved : c.defaultLang;
  const s = c.strings[lang];
  if (!s) return;
  root.lang = lang;
  // Saved night mode before the first paint: the map area shows the night earth colour, no white flash.
  if (read(c.themeKey) === "night") root.dataset.theme = "night";
  document.title = s.title;
  const pill = document.getElementById("loading");
  const text = document.getElementById("loading-text");
  if (!pill || !text) return;
  // The initial HTML already has the pill laid out, transparent (class "pending"), with the default-language text.
  text.textContent = s.loading;
  pill.classList.remove("idle");
  const g = window as unknown as Record<string, unknown>;
  const wait = Math.max(0, c.delayMs - performance.now());
  if (wait === 0) {
    pill.classList.remove("pending");
    pill.setAttribute("aria-hidden", "false");
    return;
  }
  // Compositor-driven reveal pinned to the document timeline: startTime 0 = navigation start, so the pill appears at
  // revealMs after navigation start however late the first style pass or this script runs. (A CSS animation-delay
  // counts from the first style pass that sees the class, which lands 1–2 frames late.) An opacity animation keeps
  // running on the compositor while MapLibre's start-up blocks the main thread.
  if (typeof pill.animate === "function") {
    try {
      const anim = pill.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 1, delay: c.revealMs, fill: "both" });
      anim.startTime = 0;
      g["__navmnBootPill"] = anim;
    } catch {
      // No Web Animations: the timer below reveals it (main thread, may be late).
    }
  }
  // Main-thread part: expose it to assistive technology (and announce it) once it is due.
  const due = (): void => {
    pill.classList.remove("pending");
    pill.setAttribute("aria-hidden", "false");
    text.textContent = s.loading;
  };
  g["__navmnBootTimer"] = window.setTimeout(due, wait);
}

/** Called by the app at start: from then on the StatusMachine and App.renderStatus own the loading pill. */
export function cancelBootLoading(): void {
  const w = window as unknown as Record<string, unknown>;
  const timer = w[BOOT_TIMER_GLOBAL];
  if (timer !== undefined) window.clearTimeout(timer as number);
  delete w[BOOT_TIMER_GLOBAL];
}

/** The pill is no longer needed (ready, card, banner): stop the boot reveal so it cannot show it again. */
export function cancelBootReveal(): void {
  const w = window as unknown as Record<string, unknown>;
  const anim = w[BOOT_ANIMATION_GLOBAL] as { cancel?: () => void } | undefined;
  anim?.cancel?.();
  delete w[BOOT_ANIMATION_GLOBAL];
}

/** True while the boot reveal animation exists (it shows the pill at revealMs after navigation start). */
export function hasBootReveal(): boolean {
  return (window as unknown as Record<string, unknown>)[BOOT_ANIMATION_GLOBAL] !== undefined;
}

// Pre-module start-up (NAV-002 AC 37, D12, D15). vite.config.ts inlines bootLoading() into index.html as a tiny
// classic script, so the loading pill appears 300 ms after navigation start even while the module graph
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
  /** tokens: motion.loading-delay (status.ts LOADING_DELAY_MS). */
  delayMs: number;
  defaultLang: string;
  /** localStorage keys from src/prefs.ts. */
  langKey: string;
  themeKey: string;
  strings: Record<string, BootStrings>;
}

/** Global that holds the boot timer, so the app can cancel it once it has taken over (cancelBootLoading). */
export const BOOT_TIMER_GLOBAL = "__navmnBootTimer";

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
  const wait = Math.max(0, c.delayMs - performance.now());
  // Lay the pill out now, transparent, with a compositor-driven reveal at navigation start + 300 ms
  // (styles.css › .pill.pending). A timer alone would fire late: MapLibre's start-up blocks the main thread.
  text.textContent = s.loading;
  if (wait > 0) {
    pill.style.setProperty("--pill-delay", `${Math.round(wait)}ms`);
    pill.classList.add("pending");
  }
  pill.classList.remove("idle");
  // Main-thread part: expose it to assistive technology (and announce it) once it is due.
  const due = (): void => {
    pill.classList.remove("pending");
    pill.setAttribute("aria-hidden", "false");
    text.textContent = s.loading;
  };
  (window as unknown as Record<string, unknown>)["__navmnBootTimer"] = window.setTimeout(due, wait);
}

/** Called by the app at start: from then on the StatusMachine and App.renderStatus own the loading pill. */
export function cancelBootLoading(): void {
  const w = window as unknown as Record<string, unknown>;
  const timer = w[BOOT_TIMER_GLOBAL];
  if (timer !== undefined) window.clearTimeout(timer as number);
  delete w[BOOT_TIMER_GLOBAL];
}

// Theme and language persistence (NAV-002 AC 28, 31). localStorage may be unavailable
// (private mode, blocked storage): then the toggles work for the session and defaults apply next time.
// First-visit theme (PO 2026-10-13, UX audit UX-07 (b), NAV-002 › States › Night): with nothing stored the theme is the
// system colour scheme and follows it live; a toggle press stores the choice and the page stops following the system.
import { DEFAULT_LANG, isLang, type Lang } from "./i18n/i18n";
import type { Theme } from "./style/tokens";

export const THEME_KEY = "navmn.theme";
export const LANG_KEY = "navmn.lang";
const DARK_QUERY = "(prefers-color-scheme: dark)";

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // Storage unavailable: keep the choice for this session only.
  }
}

/** The stored theme choice (a toggle press), or null when the user never toggled (or storage is unavailable). */
export function storedTheme(): Theme | null {
  const v = read(THEME_KEY);
  return v === "night" || v === "day" ? v : null;
}

/** `prefers-color-scheme: dark` → night, otherwise (light or no preference, no matchMedia) day. */
export function systemTheme(): Theme {
  return window.matchMedia?.(DARK_QUERY).matches ? "night" : "day";
}

export function loadTheme(): Theme {
  return storedTheme() ?? systemTheme();
}

export function saveTheme(theme: Theme): void {
  write(THEME_KEY, theme);
}

/**
 * Calls `cb` with the new system theme on every system change for as long as nothing is stored (the manual toggle
 * wins once pressed; the listener checks storage at each change). Returns the unsubscribe function.
 */
export function onSystemThemeChange(cb: (theme: Theme) => void): () => void {
  const mq = window.matchMedia?.(DARK_QUERY);
  if (!mq || typeof mq.addEventListener !== "function") return () => {};
  const handler = (e: MediaQueryListEvent) => {
    if (storedTheme() === null) cb(e.matches ? "night" : "day");
  };
  mq.addEventListener("change", handler);
  return () => mq.removeEventListener("change", handler);
}

export function loadLang(): Lang {
  const v = read(LANG_KEY);
  return isLang(v) ? v : DEFAULT_LANG;
}

export function saveLang(lang: Lang): void {
  write(LANG_KEY, lang);
}

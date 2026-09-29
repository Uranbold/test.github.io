// Theme and language persistence (NAV-002 AC 28, 31). localStorage may be unavailable
// (private mode, blocked storage): then the toggles work for the session and defaults apply next time.
import { DEFAULT_LANG, isLang, type Lang } from "./i18n/i18n";
import type { Theme } from "./style/tokens";

const THEME_KEY = "navmn.theme";
const LANG_KEY = "navmn.lang";

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

export function loadTheme(): Theme {
  return read(THEME_KEY) === "night" ? "night" : "day";
}

export function saveTheme(theme: Theme): void {
  write(THEME_KEY, theme);
}

export function loadLang(): Lang {
  const v = read(LANG_KEY);
  return isLang(v) ? v : DEFAULT_LANG;
}

export function saveLang(lang: Lang): void {
  write(LANG_KEY, lang);
}

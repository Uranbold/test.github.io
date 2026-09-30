// UI strings (NAV-002 AC 30–33). Mongolian is the default regardless of the browser language.
// Every mn value is an approved term in docs/requirements/glossary.md, including the NAV-002 rows G1–G7
// (added to the glossary on 2026-09-29). Checked by scripts/check-glossary.mjs (AC 33).
import en from "./en.json";
import mn from "./mn.json";

export type Lang = "mn" | "en";
export type MessageKey = keyof typeof mn;

export const LANGS: readonly Lang[] = ["mn", "en"];
export const DEFAULT_LANG: Lang = "mn";

const RESOURCES: Record<Lang, Record<MessageKey, string>> = { mn, en };

type Listener = (lang: Lang) => void;

export class I18n {
  private current: Lang;
  private readonly listeners = new Set<Listener>();

  constructor(lang: Lang = DEFAULT_LANG) {
    this.current = lang;
  }

  get lang(): Lang {
    return this.current;
  }

  t(key: MessageKey, lang: Lang = this.current): string {
    return RESOURCES[lang][key];
  }

  setLang(lang: Lang): void {
    if (lang === this.current) return;
    this.current = lang;
    for (const l of this.listeners) l(lang);
  }

  onChange(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /**
   * Results count (glossary T3, NAV-003 AC 42): `search.resultCount.one|other` chosen with Intl.PluralRules; the
   * resource value keeps `{count}` literally and the number is filled in here.
   */
  resultCount(count: number): string {
    const form = new Intl.PluralRules(this.current).select(count) === "one" ? "search.resultCount.one" : "search.resultCount.other";
    return this.t(form).replace("{count}", String(count));
  }

  /** Formats a number for the UI language: comma decimal separator in mn (glossary C3), point in en. */
  formatNumber(value: number): string {
    const s = String(value);
    return this.current === "mn" ? s.replace(".", ",") : s;
  }
}

export function isLang(value: unknown): value is Lang {
  return value === "mn" || value === "en";
}

export function resources(): Readonly<Record<Lang, Readonly<Record<MessageKey, string>>>> {
  return RESOURCES;
}

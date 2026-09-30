// On-screen distance, duration and «Хүрэх цаг» formatting for the route preview (NAV-004 AC 21–25; screen spec ›
// Content rules › Distances, Durations, «Хүрэх цаг»; glossary C3, C6, N16, N21, N22). Pure functions: units and
// words come from the resource files through `t`, so the Mongolian and English UIs share one code path.
import type { Lang, MessageKey } from "../i18n/i18n";

type T = (key: MessageKey) => string;

/** Decimal separator per UI language: comma in Mongolian (glossary C3), point in English. */
function decimal(value: string, lang: Lang): string {
  return lang === "mn" ? value.replace(".", ",") : value;
}

/**
 * AC 23: d < 995 m → nearest 10 m (minimum 10); 995 m ≤ d < 99,950 m → km with one decimal, trailing ",0"/".0"
 * dropped; d ≥ 99,950 m → whole km.
 */
export function formatDistance(meters: number, lang: Lang, t: T): string {
  const d = Number.isFinite(meters) && meters > 0 ? meters : 0;
  if (d < 995) return `${Math.max(10, Math.round(d / 10) * 10)} ${t("unit.m")}`;
  if (d < 99_950) {
    const km = (Math.round(d / 100) / 10).toFixed(1).replace(/\.0$/, "");
    return `${decimal(km, lang)} ${t("unit.km")}`;
  }
  return `${Math.round(d / 1000)} ${t("unit.km")}`;
}

/** AC 24: nearest minute (minimum 1); under 60 min «25 мин», otherwise «1 ц 25 мин» with «0 мин» omitted. */
export function formatDuration(seconds: number, t: T): string {
  const s = Number.isFinite(seconds) && seconds > 0 ? seconds : 0;
  const minutes = Math.max(1, Math.round(s / 60));
  if (minutes < 60) return `${minutes} ${t("unit.min")}`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m === 0 ? `${h} ${t("unit.h")}` : `${h} ${t("unit.h")} ${m} ${t("unit.min")}`;
}

export interface Eta {
  /** 24-hour local clock time "HH:MM" (C6). */
  time: string;
  /** Local calendar days after the base time's day (0 = same day). */
  days: number;
}

const MINUTE_MS = 60_000;

/** Local midnight of the day that contains `ms`, as epoch milliseconds. */
function localMidnight(ms: number): number {
  const d = new Date(ms);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

/**
 * AC 25: arrival = `baseMs` (the device clock when the response arrived, or now on the 60 s refresh) + duration,
 * rounded to the nearest minute, in the device's local time.
 */
export function computeEta(baseMs: number, durationS: number): Eta {
  const arrival = Math.round((baseMs + Math.max(0, durationS) * 1000) / MINUTE_MS) * MINUTE_MS;
  const a = new Date(arrival);
  const time = `${String(a.getHours()).padStart(2, "0")}:${String(a.getMinutes()).padStart(2, "0")}`;
  // Round, so a DST change (23 or 25 h day) still counts as one calendar day.
  const days = Math.max(0, Math.round((localMidnight(arrival) - localMidnight(baseMs)) / 86_400_000));
  return { time, days };
}

/** «Хүрэх цаг 14:35» / «Хүрэх цаг 00:30 +1 өдөр» ("Arrive at 00:30 +1 day"). */
export function formatEta(eta: Eta, lang: Lang, t: T): string {
  const head = `${t("route.eta")} ${eta.time}`;
  return eta.days > 0 ? `${head} ${formatNextDay(eta.days, lang, t)}` : head;
}

/** «+{days} өдөр» with the plural form picked by Intl.PluralRules (screen spec › Copy › Plural keys). */
export function formatNextDay(days: number, lang: Lang, t: T): string {
  const key: MessageKey = new Intl.PluralRules(lang).select(days) === "one" ? "route.nextDay.one" : "route.nextDay.other";
  return t(key).replace("{days}", String(days));
}

/** «{count} маршрут олдлоо» (N7). */
export function formatRouteCount(count: number, lang: Lang, t: T): string {
  const key: MessageKey = new Intl.PluralRules(lang).select(count) === "one" ? "route.count.one" : "route.count.other";
  return t(key).replace("{count}", String(count));
}

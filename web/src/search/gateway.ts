// Typed client for the gateway operations `search` (GET /v1/search) and `reverse` (GET /v1/reverse),
// docs/architecture/api/openapi.yaml 0.4.1. Outcome classification: ADR-0006 §3 (story AC 32–37).
// Never logs, stores or forwards query text or coordinates (AC 46).
import type { Lang } from "../i18n/i18n";
import { parseFeatureCollection, type PhotonFeature } from "./photon";

export const SEARCH_PATH = "/v1/search";
export const REVERSE_PATH = "/v1/reverse";
/** Client timeout per request (AC 33). The gateway's own timeouts are 2 s connect and 5 s read. */
export const CLIENT_TIMEOUT_MS = 8000;
/** Cooldown when `Retry-After` is missing, not a positive integer, or not readable (CORS) (AC 35, ADR-0006 F13). */
export const DEFAULT_RETRY_AFTER_S = 5;

/** Query of operation `search` as the NAV-003 web client sends it (ADR-0006 §3). */
export interface SearchQuery {
  q: string;
  lang: Lang;
  limit: number;
  /** Bias point, already formatted with 3 decimals. */
  lat: string;
  lon: string;
}

/** Query of operation `reverse` (ADR-0006 §4). */
export interface ReverseQuery {
  /** 6 decimals. */
  lat: string;
  lon: string;
  lang: Lang;
  limit: 1;
  radius: 0.5;
}

export type Outcome =
  | { kind: "ok"; features: PhotonFeature[] }
  | { kind: "badRequest" }
  | { kind: "rateLimited"; retryAfterS: number }
  | { kind: "unavailable" }
  | { kind: "offline" }
  | { kind: "aborted" };

/** `Retry-After` per AC 35: integer seconds ≥ 1, otherwise 5 s. */
export function parseRetryAfter(header: string | null | undefined): number {
  const v = (header ?? "").trim();
  return /^[1-9][0-9]*$/.test(v) ? Number(v) : DEFAULT_RETRY_AFTER_S;
}

/** Class of a non-200 HTTP status (ADR-0006 §3 table). */
export function classifyStatus(status: number): "badRequest" | "rateLimited" | "unavailable" {
  if (status === 429) return "rateLimited";
  if (status >= 400 && status < 500) return "badRequest";
  return "unavailable";
}

export interface Timers {
  setTimeout(fn: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
}

export const browserTimers: Timers = {
  setTimeout: (fn, ms) => globalThis.setTimeout(fn, ms),
  clearTimeout: (h) => globalThis.clearTimeout(h as ReturnType<typeof setTimeout>),
};

export interface GatewayDeps {
  baseUrl: string;
  fetch: typeof fetch;
  isOnline: () => boolean;
  timers?: Timers;
  timeoutMs?: number;
}

export class GatewayClient {
  private readonly timers: Timers;
  private readonly timeoutMs: number;

  constructor(private readonly deps: GatewayDeps) {
    this.timers = deps.timers ?? browserTimers;
    this.timeoutMs = deps.timeoutMs ?? CLIENT_TIMEOUT_MS;
  }

  searchUrl(q: SearchQuery): string {
    return this.url(SEARCH_PATH, { q: q.q, lang: q.lang, limit: String(q.limit), lat: q.lat, lon: q.lon });
  }

  reverseUrl(q: ReverseQuery): string {
    return this.url(REVERSE_PATH, { lat: q.lat, lon: q.lon, lang: q.lang, limit: String(q.limit), radius: String(q.radius) });
  }

  search(q: SearchQuery, signal: AbortSignal): Promise<Outcome> {
    return this.request(this.searchUrl(q), signal);
  }

  reverse(q: ReverseQuery, signal: AbortSignal): Promise<Outcome> {
    return this.request(this.reverseUrl(q), signal);
  }

  private url(path: string, params: Record<string, string>): string {
    return `${this.deps.baseUrl}${path}?${new URLSearchParams(params).toString()}`;
  }

  /** One GET with an 8 s timeout that is distinguishable from supersession (the caller's signal). */
  private async request(url: string, signal: AbortSignal): Promise<Outcome> {
    if (signal.aborted) return { kind: "aborted" };
    if (!this.deps.isOnline()) return { kind: "offline" };
    const ctrl = new AbortController();
    let timedOut = false;
    const onAbort = (): void => ctrl.abort();
    signal.addEventListener("abort", onAbort, { once: true });
    const timer = this.timers.setTimeout(() => {
      timedOut = true;
      ctrl.abort();
    }, this.timeoutMs);
    try {
      // Simple CORS GET: no custom headers (no preflight), no cookies.
      const res = await this.deps.fetch(url, { method: "GET", signal: ctrl.signal, credentials: "omit", mode: "cors" });
      if (res.status === 200) {
        let body: unknown;
        try {
          body = await res.json();
        } catch {
          if (signal.aborted && !timedOut) return { kind: "aborted" };
          return { kind: "unavailable" }; // not JSON (for example a proxy HTML page) or cut off
        }
        const fc = parseFeatureCollection(body);
        return fc ? { kind: "ok", features: fc.features } : { kind: "unavailable" };
      }
      const cls = classifyStatus(res.status);
      if (cls === "rateLimited") return { kind: "rateLimited", retryAfterS: parseRetryAfter(res.headers.get("Retry-After")) };
      return { kind: cls };
    } catch {
      if (signal.aborted && !timedOut) return { kind: "aborted" };
      if (timedOut) return { kind: "unavailable" };
      // Network TypeError or CORS failure: offline if the browser says so, otherwise the service is unavailable.
      return this.deps.isOnline() ? { kind: "unavailable" } : { kind: "offline" };
    } finally {
      this.timers.clearTimeout(timer);
      signal.removeEventListener("abort", onAbort);
    }
  }
}

/** Per-operation 429 cooldown (AC 35, 36): nothing of that operation is sent while it runs; nothing is sent when it ends. */
export class Cooldown {
  private until = 0;
  private timer: unknown = null;

  constructor(
    private readonly now: () => number,
    private readonly timers: Timers = browserTimers,
  ) {}

  get active(): boolean {
    return this.now() < this.until;
  }

  /** Starts (or extends) the cooldown; `onEnd` runs once when it is over. */
  start(seconds: number, onEnd: () => void): void {
    const ms = Math.min(seconds * 1000, 2 ** 31 - 1);
    this.until = Math.max(this.until, this.now() + ms);
    this.timers.clearTimeout(this.timer);
    this.timer = this.timers.setTimeout(() => {
      this.timer = null;
      onEnd();
    }, Math.max(0, this.until - this.now()));
  }

  dispose(): void {
    this.timers.clearTimeout(this.timer);
    this.timer = null;
  }
}

// Runtime configuration (NAV-002 AC 2–3, AC 51–54; ADR-0004 §5).

export const DEFAULT_GATEWAY_BASE_URL = "http://localhost:8080";
export const TILES_PATH = "/tiles/basemap.pmtiles"; // openapi.yaml operation getBasemapPmtiles

/** Trims whitespace and trailing slashes; falls back to the default when unset or empty. */
export function normalizeGatewayBaseUrl(raw: string | undefined | null): string {
  const trimmed = (raw ?? "").trim().replace(/\/+$/, "");
  return trimmed === "" ? DEFAULT_GATEWAY_BASE_URL : trimmed;
}

/** HTTP URL of the basemap archive. Never has a query string (the contract defines none). */
export function tilesUrl(gatewayBaseUrl: string): string {
  return normalizeGatewayBaseUrl(gatewayBaseUrl) + TILES_PATH;
}

/** MapLibre source URL, read through the pmtiles:// protocol. */
export function pmtilesSourceUrl(gatewayBaseUrl: string): string {
  return "pmtiles://" + tilesUrl(gatewayBaseUrl);
}

/**
 * Absolute base URL of the bundled static assets (fonts, sprites), ending with "/".
 * Built by string concatenation: new URL() would percent-encode the {fontstack}/{range} tokens.
 */
export function assetBaseUrl(origin: string, viteBase: string, documentBaseUri?: string): string {
  // NAV-017 demo-mode build (ADR-0011 §3): a relative base ("./") resolves against the page, so the same output works
  // from any sub-folder. Public builds have an absolute base and keep the result below unchanged.
  if (documentBaseUri && (viteBase === "" || viteBase.startsWith("."))) {
    const href = new URL(viteBase || "./", documentBaseUri).href.replace(/[?#].*$/, "");
    return href.endsWith("/") ? href : href.slice(0, href.lastIndexOf("/") + 1);
  }
  const base = viteBase.startsWith("/") ? viteBase : "/" + viteBase;
  return origin.replace(/\/+$/, "") + (base.endsWith("/") ? base : base + "/");
}

/**
 * Value of VITE_GATEWAY_BASE_URL that means "the page's own origin at runtime" (window.location.origin), so one static
 * build works on any host without a rebuild (NAV-002 section M, D44). A path that starts with "/" (for example "/" or
 * "/gw") means the page origin plus that path.
 */
export const SAME_ORIGIN = "same-origin";

const stripTrailingSlashes = (s: string): string => s.replace(/\/+$/, "");

/**
 * Where VITE_GATEWAY_BASE_URL points, before the page origin is known. The one rule shared by the runtime
 * (resolveGatewayBaseUrl) and the build-time Content-Security-Policy (gatewayConnectOrigin, SEC-4B / ADR-0018 §2), so the
 * policy and the requests can never drift apart:
 *  - "same-origin" (any case) or "/"          → the page origin (so is "//" etc.)
 *  - "/path" (one leading slash)              → the page origin + "/path"
 *  - an absolute URL                          → that URL (trailing slashes trimmed)
 *  - unset or empty                           → `fallback` (http://localhost:8080 unless the static demo is on)
 */
export type GatewayTarget = { kind: "page"; path: string } | { kind: "absolute"; url: string };

export function gatewayTarget(raw: string | undefined | null, fallback: string = DEFAULT_GATEWAY_BASE_URL): GatewayTarget {
  const trimmed = (raw ?? "").trim();
  if (trimmed === "") return fallback === SAME_ORIGIN ? { kind: "page", path: "" } : { kind: "absolute", url: normalizeGatewayBaseUrl(fallback) };
  if (trimmed.toLowerCase() === SAME_ORIGIN || /^\/+$/.test(trimmed)) return { kind: "page", path: "" };
  if (trimmed.startsWith("/") && !trimmed.startsWith("//")) return { kind: "page", path: stripTrailingSlashes(trimmed) };
  return { kind: "absolute", url: normalizeGatewayBaseUrl(trimmed) };
}

/** Resolves VITE_GATEWAY_BASE_URL against the page origin (rules: gatewayTarget). */
export function resolveGatewayBaseUrl(
  raw: string | undefined | null,
  origin: string,
  fallback: string = DEFAULT_GATEWAY_BASE_URL,
): string {
  const t = gatewayTarget(raw, fallback);
  return t.kind === "page" ? stripTrailingSlashes(origin) + t.path : t.url;
}

/** The gateway fallback for an unset VITE_GATEWAY_BASE_URL: the static demo is served next to its basemap archive. */
export function gatewayFallback(staticDemo: boolean): string {
  return staticDemo ? SAME_ORIGIN : DEFAULT_GATEWAY_BASE_URL;
}

/**
 * SEC-4B (ADR-0018 §2): the origin a build connects to for the gateway, for the CSP `connect-src`; `null` when the
 * gateway is the page origin (`'self'`). Same inputs and fallback as loadConfig. Throws when an absolute value is not an
 * http(s) URL, so a value the policy cannot express fails the build instead of shipping a page that cannot load tiles.
 */
export function gatewayConnectOrigin(env: Pick<ConfigEnv, "VITE_GATEWAY_BASE_URL" | "VITE_STATIC_DEMO">): string | null {
  const staticDemo = parseStaticDemo(env.VITE_STATIC_DEMO) ?? true;
  const t = gatewayTarget(env.VITE_GATEWAY_BASE_URL, gatewayFallback(staticDemo));
  if (t.kind === "page") return null;
  const u = URL.canParse(t.url) ? new URL(t.url) : null;
  if (!u || (u.protocol !== "http:" && u.protocol !== "https:")) {
    throw new Error(`VITE_GATEWAY_BASE_URL=${JSON.stringify(env.VITE_GATEWAY_BASE_URL)} is not an http(s) URL, "same-origin" or a path starting with "/" (web/.env.example)`);
  }
  return u.origin;
}

// ---------------------------------------------------------------- static demo and feature switches (NAV-002 AC 53–54)

/**
 * Backend features the client may use. The static public demo (D44) turns all of them off: nothing is sent to
 * `search`, `reverse` or (NAV-004) `postRoute`, and the UI shows the unavailable state instead. Reuse `features.routing`
 * for route preview rather than adding another switch.
 */
export interface Features {
  search: boolean;
  reverse: boolean;
  routing: boolean;
}

export const ALL_FEATURES: Readonly<Features> = Object.freeze({ search: true, reverse: true, routing: true });
export const STATIC_DEMO_FEATURES: Readonly<Features> = Object.freeze({ search: false, reverse: false, routing: false });

/**
 * VITE_STATIC_DEMO: "true"/"1" → on, "false"/"0"/unset/empty → off (case-insensitive, trimmed). Any other value is
 * `null` (invalid): the build refuses it (vite.config.ts), and at runtime it counts as on, so a typo can never switch
 * search on for the public site (fail closed, AC 54).
 */
export function parseStaticDemo(raw: string | boolean | undefined | null): boolean | null {
  if (typeof raw === "boolean") return raw;
  const v = (raw ?? "").trim().toLowerCase();
  if (v === "" || v === "false" || v === "0") return false;
  if (v === "true" || v === "1") return true;
  return null;
}

export interface AppConfig {
  gatewayBaseUrl: string;
  tilesUrl: string;
  sourceUrl: string;
  assetBaseUrl: string;
  /** Static public demo build (VITE_STATIC_DEMO): map only, no backend calls. */
  staticDemo: boolean;
  features: Readonly<Features>;
}

export interface ConfigEnv {
  VITE_GATEWAY_BASE_URL?: string;
  VITE_STATIC_DEMO?: string;
  BASE_URL: string;
}

/** `documentBaseUri` (document.baseURI) is only used when BASE_URL is relative (NAV-017 demo-mode build). */
export function loadConfig(env: ConfigEnv, origin: string, documentBaseUri?: string): AppConfig {
  const staticDemo = parseStaticDemo(env.VITE_STATIC_DEMO) ?? true;
  // The static demo is served next to its basemap archive, so an unset gateway URL means the page origin there.
  const gatewayBaseUrl = resolveGatewayBaseUrl(env.VITE_GATEWAY_BASE_URL, origin, gatewayFallback(staticDemo));
  return {
    gatewayBaseUrl,
    tilesUrl: tilesUrl(gatewayBaseUrl),
    sourceUrl: pmtilesSourceUrl(gatewayBaseUrl),
    assetBaseUrl: assetBaseUrl(origin, env.BASE_URL, documentBaseUri),
    staticDemo,
    features: staticDemo ? STATIC_DEMO_FEATURES : ALL_FEATURES,
  };
}

// Runtime configuration (NAV-002 AC 2–3, ADR-0004 §5).

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
export function assetBaseUrl(origin: string, viteBase: string): string {
  const base = viteBase.startsWith("/") ? viteBase : "/" + viteBase;
  return origin.replace(/\/+$/, "") + (base.endsWith("/") ? base : base + "/");
}

export interface AppConfig {
  gatewayBaseUrl: string;
  tilesUrl: string;
  sourceUrl: string;
  assetBaseUrl: string;
}

export function loadConfig(env: { VITE_GATEWAY_BASE_URL?: string; BASE_URL: string }, origin: string): AppConfig {
  const gatewayBaseUrl = normalizeGatewayBaseUrl(env.VITE_GATEWAY_BASE_URL);
  return {
    gatewayBaseUrl,
    tilesUrl: tilesUrl(gatewayBaseUrl),
    sourceUrl: pmtilesSourceUrl(gatewayBaseUrl),
    assetBaseUrl: assetBaseUrl(origin, env.BASE_URL),
  };
}

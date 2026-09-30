import { describe, expect, it } from "vitest";
import {
  ALL_FEATURES,
  assetBaseUrl,
  DEFAULT_GATEWAY_BASE_URL,
  loadConfig,
  normalizeGatewayBaseUrl,
  parseStaticDemo,
  pmtilesSourceUrl,
  resolveGatewayBaseUrl,
  STATIC_DEMO_FEATURES,
  tilesUrl,
} from "./config";

describe("gateway configuration (AC 2–3)", () => {
  it("defaults to http://localhost:8080", () => {
    expect(DEFAULT_GATEWAY_BASE_URL).toBe("http://localhost:8080");
    expect(normalizeGatewayBaseUrl(undefined)).toBe("http://localhost:8080");
    expect(normalizeGatewayBaseUrl("  ")).toBe("http://localhost:8080");
    expect(pmtilesSourceUrl(normalizeGatewayBaseUrl(undefined))).toBe("pmtiles://http://localhost:8080/tiles/basemap.pmtiles");
  });

  it.each(["http://localhost:8081", "http://localhost:8081/", "http://localhost:8081///", " http://localhost:8081/ "])(
    "normalises %j to one tiles URL without a double slash",
    (raw) => {
      expect(tilesUrl(raw)).toBe("http://localhost:8081/tiles/basemap.pmtiles");
    },
  );

  it("never adds a query string", () => {
    expect(tilesUrl("http://gw.example:9000/")).not.toContain("?");
  });

  it("builds absolute asset URLs by concatenation (keeps {fontstack}/{range} tokens)", () => {
    expect(assetBaseUrl("http://localhost:5173", "/")).toBe("http://localhost:5173/");
    expect(assetBaseUrl("http://localhost:5173/", "/demo")).toBe("http://localhost:5173/demo/");
    const cfg = loadConfig({ VITE_GATEWAY_BASE_URL: "http://localhost:8081/", BASE_URL: "/" }, "http://localhost:5173");
    expect(cfg).toEqual({
      gatewayBaseUrl: "http://localhost:8081",
      tilesUrl: "http://localhost:8081/tiles/basemap.pmtiles",
      sourceUrl: "pmtiles://http://localhost:8081/tiles/basemap.pmtiles",
      assetBaseUrl: "http://localhost:5173/",
      staticDemo: false,
      features: ALL_FEATURES,
    });
  });
});

// Placeholder origins only: real public hostnames are never committed (D35).
const DEMO = "https://demo-host.example";

describe("same-origin gateway (NAV-002 section M, D44)", () => {
  it.each(["same-origin", "SAME-ORIGIN", " same-origin ", "/", "///"])("%j resolves to the page origin at runtime", (raw) => {
    expect(resolveGatewayBaseUrl(raw, DEMO)).toBe(DEMO);
    expect(resolveGatewayBaseUrl(raw, DEMO + "/")).toBe(DEMO);
  });

  it("the same value follows whatever host serves the page (no rebuild)", () => {
    const env = { VITE_GATEWAY_BASE_URL: "same-origin", BASE_URL: "/" };
    for (const origin of ["https://a.demo-host.example", "https://b.other-host.example:8443", "http://localhost:4173"]) {
      const cfg = loadConfig(env, origin);
      expect(cfg.gatewayBaseUrl).toBe(origin);
      expect(cfg.tilesUrl).toBe(origin + "/tiles/basemap.pmtiles");
      expect(cfg.sourceUrl).toBe("pmtiles://" + origin + "/tiles/basemap.pmtiles");
    }
  });

  it("a path with one leading slash is appended to the page origin", () => {
    expect(resolveGatewayBaseUrl("/gw/", DEMO)).toBe(DEMO + "/gw");
    expect(tilesUrl(resolveGatewayBaseUrl("/gw", DEMO))).toBe(DEMO + "/gw/tiles/basemap.pmtiles");
  });

  it("keeps the default and absolute URLs unchanged", () => {
    expect(resolveGatewayBaseUrl(undefined, DEMO)).toBe("http://localhost:8080");
    expect(resolveGatewayBaseUrl("", DEMO)).toBe("http://localhost:8080");
    expect(resolveGatewayBaseUrl("http://localhost:8081/", DEMO)).toBe("http://localhost:8081");
    expect(resolveGatewayBaseUrl("https://gw.example/api/", DEMO)).toBe("https://gw.example/api");
    expect(loadConfig({ BASE_URL: "/" }, DEMO).tilesUrl).toBe("http://localhost:8080/tiles/basemap.pmtiles");
  });
});

describe("static demo build setting (NAV-002 AC 53–54)", () => {
  it.each([
    [undefined, false],
    ["", false],
    ["false", false],
    ["0", false],
    ["true", true],
    [" TRUE ", true],
    ["1", true],
    ["yes", null],
    ["on", null],
  ] as const)("VITE_STATIC_DEMO=%j parses to %j", (raw, expected) => {
    expect(parseStaticDemo(raw)).toBe(expected);
  });

  it("turns search, reverse and routing off", () => {
    const cfg = loadConfig({ VITE_STATIC_DEMO: "true", VITE_GATEWAY_BASE_URL: "same-origin", BASE_URL: "/" }, DEMO);
    expect(cfg.staticDemo).toBe(true);
    expect(cfg.features).toEqual({ search: false, reverse: false, routing: false });
    expect(cfg.features).toBe(STATIC_DEMO_FEATURES);
    expect(cfg.tilesUrl).toBe(DEMO + "/tiles/basemap.pmtiles");
  });

  it("uses the page origin for tiles when the gateway URL is unset", () => {
    expect(loadConfig({ VITE_STATIC_DEMO: "true", BASE_URL: "/" }, DEMO).tilesUrl).toBe(DEMO + "/tiles/basemap.pmtiles");
  });

  it("an invalid value fails closed at runtime (backend features stay off)", () => {
    const cfg = loadConfig({ VITE_STATIC_DEMO: "yes", BASE_URL: "/" }, DEMO);
    expect(cfg.staticDemo).toBe(true);
    expect(cfg.features).toEqual({ search: false, reverse: false, routing: false });
  });

  it("normal builds keep every feature on", () => {
    expect(loadConfig({ BASE_URL: "/" }, DEMO).features).toEqual({ search: true, reverse: true, routing: true });
  });
});

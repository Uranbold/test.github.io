import { describe, expect, it } from "vitest";
import { assetBaseUrl, DEFAULT_GATEWAY_BASE_URL, loadConfig, normalizeGatewayBaseUrl, pmtilesSourceUrl, tilesUrl } from "./config";

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
    });
  });
});

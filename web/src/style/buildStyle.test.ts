// ADR-0004 §3 label-rule test (makes a @protomaps/basemaps bump safe) + map-style.md post-processing.
import { validateStyleMin } from "@maplibre/maplibre-gl-style-spec";
import type { LayerSpecification, StyleSpecification } from "maplibre-gl";
import { describe, expect, it } from "vitest";
import tokens from "@design/tokens.json";
import {
  BUNDLED_FONTSTACKS,
  buildStyle,
  clampTextSize,
  fontsIn,
  isLabelRule,
  LABEL_EXPRESSION,
  LOCATION_FILL_LAYER_ID,
  LOCATION_LINE_LAYER_ID,
  MIN_NAME_TEXT_SIZE,
  MIN_TEXT_SIZE,
  propertyKeys,
  type Theme,
} from "./buildStyle";

const CFG = {
  sourceUrl: "pmtiles://http://localhost:8080/tiles/basemap.pmtiles",
  assetBaseUrl: "http://localhost:5173/",
};
const THEMES: Theme[] = ["day", "night"];
const MODE = { day: "light", night: "night" } as const;

const style = (t: Theme): StyleSpecification => buildStyle(t, CFG);
const layer = (s: StyleSpecification, id: string): LayerSpecification => {
  const l = s.layers.find((x) => x.id === id);
  if (!l) throw new Error(`layer ${id} missing`);
  return l;
};
// Protomaps layers that show a feature name (ADR-0004 Context table).
const NAME_LAYERS = [
  "water_waterway_label",
  "roads_labels_minor",
  "roads_labels_major",
  "water_label_ocean",
  "water_label_lakes",
  "earth_label_islands",
  "pois",
  "places_subplace",
  "places_region",
  "places_locality",
  "places_country",
];
const flavorToken = (t: Theme, key: string): string =>
  (tokens.color[MODE[t]].map.flavor as unknown as Record<string, { $value: string }>)[key]!.$value;
const customToken = (t: Theme, key: string): string =>
  (tokens.color[MODE[t]].map.custom as unknown as Record<string, { $value: string }>)[key]!.$value;

describe.each(THEMES)("buildStyle(%s)", (theme) => {
  const s = style(theme);

  it("passes the MapLibre style validator with 0 errors", () => {
    expect(validateStyleMin(s)).toEqual([]);
  });

  it("uses the gateway PMTiles source and absolute bundled glyph/sprite URLs (AC 2, 46)", () => {
    expect(s.sources.protomaps).toEqual({ type: "vector", url: CFG.sourceUrl });
    expect(s.glyphs).toBe("http://localhost:5173/fonts/{fontstack}/{range}.pbf");
    expect(s.sprite).toBe(`http://localhost:5173/sprites/v4/${theme === "day" ? "light" : "dark"}`);
    expect(JSON.stringify(s)).not.toMatch(/protomaps\.github\.io|fonts\.googleapis|unpkg|jsdelivr/);
  });

  it("applies the label rule to every name-bearing symbol layer (AC 7)", () => {
    for (const id of NAME_LAYERS) {
      const l = layer(s, id);
      expect(l.type).toBe("symbol");
      expect((l as { layout: Record<string, unknown> }).layout["text-field"], id).toEqual(LABEL_EXPRESSION);
    }
    const symbolWithNames = s.layers.filter(
      (l) => l.type === "symbol" && propertyKeys(l.layout?.["text-field"]).some((k) => k.startsWith("name") || k.startsWith("pgf")),
    );
    for (const l of symbolWithNames) expect(isLabelRule((l as { layout: Record<string, unknown> }).layout["text-field"]), l.id).toBe(true);
  });

  it("reads no name:ru, pgf:*, name2, name3 or other name:<lang> anywhere", () => {
    const keys = s.layers.flatMap((l) => propertyKeys(l));
    const forbidden = keys.filter(
      (k) => k === "name2" || k === "name3" || k.startsWith("pgf:") || (k.startsWith("name:") && k !== "name:mn" && k !== "name:en"),
    );
    expect(forbidden).toEqual([]);
    expect(JSON.stringify(s.layers)).not.toContain("name:ru");
  });

  it("keeps house numbers and road shields unchanged", () => {
    expect((layer(s, "address_label") as { layout: Record<string, unknown> }).layout["text-field"]).toEqual(["get", "addr_housenumber"]);
    expect((layer(s, "roads_shields") as { layout: Record<string, unknown> }).layout["text-field"]).toEqual(["get", "shield_text"]);
  });

  it("uses only the bundled font stacks", () => {
    const fonts = new Set(s.layers.flatMap((l) => (l.type === "symbol" ? fontsIn(l.layout?.["text-font"]) : [])));
    expect(fonts.size).toBeGreaterThan(0);
    for (const f of fonts) expect(BUNDLED_FONTSTACKS as readonly string[]).toContain(f);
  });

  it("uses the map-style.md colours from tokens.json (AC 27)", () => {
    expect((layer(s, "background") as { paint: Record<string, unknown> }).paint["background-color"]).toBe(flavorToken(theme, "background"));
    expect((layer(s, "earth") as { paint: Record<string, unknown> }).paint["fill-color"]).toBe(flavorToken(theme, "earth"));
    expect((layer(s, "water") as { paint: Record<string, unknown> }).paint["fill-color"]).toBe(flavorToken(theme, "water"));
    expect(JSON.stringify((layer(s, "roads_major") as { paint: Record<string, unknown> }).paint["line-color"])).toContain(
      JSON.stringify(flavorToken(theme, "major")),
    );
    expect(JSON.stringify((layer(s, "roads_labels_major") as { paint: Record<string, unknown> }).paint)).toContain(
      flavorToken(theme, "roads_label_major"),
    );
  });

  it("colours trunk roads separately (§3.2) and darkens roads below z10 (§3.3)", () => {
    const major = (layer(s, "roads_major") as { paint: Record<string, unknown> }).paint["line-color"];
    expect(major).toEqual([
      "step",
      ["zoom"],
      ["match", ["get", "kind_detail"], ["trunk", "trunk_link"], customToken(theme, "lowzoom_trunk"), customToken(theme, "lowzoom_major")],
      10,
      ["match", ["get", "kind_detail"], ["trunk", "trunk_link"], customToken(theme, "trunk"), flavorToken(theme, "major")],
    ]);
    for (const id of ["roads_major_casing_early", "roads_major_casing_late", "roads_bridges_major_casing"]) {
      const c = (layer(s, id) as { paint: Record<string, unknown> }).paint["line-color"] as unknown[];
      expect(c.slice(0, 4), id).toEqual(["match", ["get", "kind_detail"], ["trunk", "trunk_link"], customToken(theme, "trunk_casing")]);
    }
    const hw = (layer(s, "roads_highway") as { paint: Record<string, unknown> }).paint["line-color"];
    expect(hw).toEqual(["step", ["zoom"], customToken(theme, "lowzoom_highway"), 10, flavorToken(theme, "highway")]);
    const tunnel = (layer(s, "roads_tunnels_major") as { paint: Record<string, unknown> }).paint["line-color"];
    expect(tunnel).toBe(flavorToken(theme, "tunnel_major"));
  });

  it("applies the §4.1 text sizes and minimum label sizes", () => {
    const size = (id: string) => (layer(s, id) as { layout: Record<string, unknown> }).layout["text-size"];
    expect(size("roads_labels_major")).toBe(13);
    expect(size("roads_labels_minor")).toEqual(["interpolate", ["linear"], ["zoom"], 14, 12, 18, 14]);
    expect(size("water_label_ocean")).toEqual(["interpolate", ["linear"], ["zoom"], 3, 11, 10, 12]);
    expect(size("earth_label_islands")).toBe(11);
    expect(size("roads_shields")).toBe(10);
    const numbers = (v: unknown): number[] =>
      typeof v === "number" ? [v] : Array.isArray(v) && v[0] !== "zoom" && v[0] !== "get" ? v.flatMap(numbers) : [];
    for (const l of s.layers) {
      if (l.type !== "symbol" || l.layout?.["text-field"] === undefined) continue;
      const v = l.layout["text-size"];
      const min = isLabelRule(l.layout["text-field"]) ? MIN_NAME_TEXT_SIZE : MIN_TEXT_SIZE;
      // Outputs of interpolate/step/case are ≥ min; zoom stops and comparison operands are excluded.
      if (typeof v === "number") expect(v, l.id).toBeGreaterThanOrEqual(min);
      else if (Array.isArray(v) && v[0] === "interpolate") {
        for (let i = 4; i < v.length; i += 2) for (const n of outputs(v[i])) expect(n, l.id).toBeGreaterThanOrEqual(min);
      }
      expect(numbers(v).length).toBeGreaterThan(0);
    }
  });

  it("places the location layers below the first symbol layer (map-style.md §7)", () => {
    const ids = s.layers.map((l) => l.id);
    const firstSymbol = s.layers.findIndex((l) => l.type === "symbol");
    expect(ids.indexOf(LOCATION_FILL_LAYER_ID)).toBe(firstSymbol - 2);
    expect(ids.indexOf(LOCATION_LINE_LAYER_ID)).toBe(firstSymbol - 1);
    expect(s.layers[firstSymbol]!.id).toBe("address_label");
  });
});

/** Size outputs of a case/match/number (not the conditions). */
function outputs(v: unknown): number[] {
  if (typeof v === "number") return [v];
  if (!Array.isArray(v)) return [];
  if (v[0] === "case") {
    const out: number[] = [];
    for (let i = 2; i < v.length; i += 2) out.push(...outputs(v[i]));
    out.push(...outputs(v[v.length - 1]));
    return out;
  }
  return [];
}

describe("clampTextSize", () => {
  it("raises numbers and every output of zoom and data expressions", () => {
    expect(clampTextSize(8, 11)).toBe(11);
    expect(clampTextSize(14, 11)).toBe(14);
    expect(clampTextSize(["interpolate", ["linear"], ["zoom"], 11, 8, 14, 14], 11)).toEqual(["interpolate", ["linear"], ["zoom"], 11, 11, 14, 14]);
    expect(clampTextSize(["step", ["zoom"], 8, 10, 12], 11)).toEqual(["step", ["zoom"], 11, 10, 12]);
    expect(clampTextSize(["case", ["<", ["get", "r"], 5], 8, 20], 11)).toEqual(["case", ["<", ["get", "r"], 5], 11, 20]);
  });
});

describe("day and night differ only where the spec says", () => {
  it("has the same layer ids and order in both themes (theme switch keeps labels, AC 27)", () => {
    expect(style("day").layers.map((l) => l.id)).toEqual(style("night").layers.map((l) => l.id));
  });
});

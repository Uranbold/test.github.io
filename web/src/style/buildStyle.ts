// Map style for the web demo (ADR-0004 §3, docs/design/map-style.md).
// The layer list comes from @protomaps/basemaps; we only post-process it:
//   §3.2 trunk colour, §3.3 low-zoom road colour, §4 label rule + sizes, §5/§6 bundled asset URLs,
//   §7 our own location layers.
import { layers as protomapsLayers, namedFlavor, type Flavor } from "@protomaps/basemaps";
import type {
  ExpressionSpecification,
  LayerSpecification,
  StyleSpecification,
  SymbolLayerSpecification,
} from "maplibre-gl";
import { customRoadColours, locationColours, tokenFlavor, type Theme } from "./tokens";

export type { Theme };

export const SOURCE_ID = "protomaps";
export const LOCATION_SOURCE_ID = "nav-location";
export const LOCATION_FILL_LAYER_ID = "nav-location-accuracy-fill";
export const LOCATION_LINE_LAYER_ID = "nav-location-accuracy-line";

/** The label rule for every web map in this product (CLAUDE.md rule 7, glossary C8, NAV-002 AC 7–8). */
export const LABEL_EXPRESSION: ExpressionSpecification = [
  "coalesce",
  ["get", "name:mn"],
  ["get", "name"],
  ["get", "name:en"],
];

/** Glyph fontstacks bundled under web/public/fonts (map-style.md §5). */
export const BUNDLED_FONTSTACKS = ["Noto Sans Regular", "Noto Sans Medium", "Noto Sans Italic"] as const;
const FALLBACK_FONTSTACK = "Noto Sans Regular";

/** Keys whose presence in a text-field means "this layer shows a feature name" (ADR-0004 §3). */
export function isNameKey(key: string): boolean {
  return (
    key === "name" ||
    key.startsWith("name:") ||
    key === "name2" ||
    key === "name3" ||
    key.startsWith("pgf:") ||
    key === "ref:en"
  );
}

/** Every property key read with get/has anywhere in an expression. */
export function propertyKeys(expr: unknown): string[] {
  const keys: string[] = [];
  const walk = (node: unknown): void => {
    if (!Array.isArray(node)) {
      if (node && typeof node === "object") Object.values(node).forEach(walk);
      return;
    }
    const [op, arg] = node as unknown[];
    if ((op === "get" || op === "has" || op === "!has") && typeof arg === "string") keys.push(arg);
    node.forEach(walk);
  };
  walk(expr);
  return keys;
}

/** True when a text-field is exactly the label rule. */
export function isLabelRule(field: unknown): boolean {
  return JSON.stringify(field) === JSON.stringify(LABEL_EXPRESSION);
}

function showsName(layer: SymbolLayerSpecification): boolean {
  const field = layer.layout?.["text-field"];
  return field !== undefined && propertyKeys(field).some(isNameKey);
}

/** Replaces every font stack that is not bundled (e.g. Devanagari) with Noto Sans Regular. */
function bundledFonts(textFont: unknown): unknown {
  const isFontList = (n: unknown): n is string[] =>
    Array.isArray(n) && n.length > 0 && n.every((s) => typeof s === "string") && !isOperator(n[0]);
  const fix = (list: string[]): string[] => {
    const out = list.map((f) => ((BUNDLED_FONTSTACKS as readonly string[]).includes(f) ? f : FALLBACK_FONTSTACK));
    return [...new Set(out)];
  };
  const walk = (node: unknown): unknown => {
    if (isFontList(node)) return fix(node);
    if (Array.isArray(node)) {
      if (node[0] === "literal" && isFontList(node[1])) return ["literal", fix(node[1])];
      return node.map(walk);
    }
    return node;
  };
  return walk(textFont);
}

const OPERATORS = new Set(["case", "match", "step", "interpolate", "literal", "coalesce", "get", "has", "concat", "format"]);
function isOperator(s: unknown): boolean {
  return typeof s === "string" && OPERATORS.has(s);
}

/** All font stack names a text-font value can resolve to. */
export function fontsIn(textFont: unknown): string[] {
  const found = new Set<string>();
  const walk = (node: unknown): void => {
    if (!Array.isArray(node)) return;
    if (node.length > 0 && node.every((s) => typeof s === "string") && !isOperator(node[0])) {
      node.forEach((f) => found.add(f as string));
      return;
    }
    node.forEach(walk);
  };
  walk(textFont);
  return [...found];
}

/**
 * Applies the label rule: every name-bearing symbol layer gets exactly LABEL_EXPRESSION;
 * house numbers (addr_housenumber) and road shields (shield_text) keep their field.
 * Fonts are restricted to the bundled stacks.
 */
export function applyLabelRule(input: LayerSpecification[]): LayerSpecification[] {
  return input.map((layer) => {
    if (layer.type !== "symbol") return layer;
    const layout = { ...layer.layout };
    if (showsName(layer)) layout["text-field"] = LABEL_EXPRESSION;
    if (layout["text-font"] !== undefined) {
      (layout as Record<string, unknown>)["text-font"] = bundledFonts(layout["text-font"]);
    }
    return { ...layer, layout } as SymbolLayerSpecification;
  });
}

// map-style.md §4.1 table: sizes that differ from the Protomaps defaults.
const TEXT_SIZE_OVERRIDES: Record<string, unknown> = {
  roads_labels_major: 13,
  roads_labels_minor: ["interpolate", ["linear"], ["zoom"], 14, 12, 18, 14],
  water_label_ocean: ["interpolate", ["linear"], ["zoom"], 3, 11, 10, 12],
  water_waterway_label: 12,
  earth_label_islands: 11,
  roads_shields: 10,
  address_label: 12,
};
export const MIN_NAME_TEXT_SIZE = 11;
export const MIN_TEXT_SIZE = 10;

/** Raises every output of a (possibly zoom- or data-driven) size expression to at least `min`. */
export function clampTextSize(value: unknown, min: number): unknown {
  if (typeof value === "number") return Math.max(min, value);
  if (!Array.isArray(value)) return value;
  const [op] = value;
  const v = [...value];
  if (op === "interpolate" || op === "interpolate-hcl" || op === "interpolate-lab") {
    for (let i = 4; i < v.length; i += 2) v[i] = clampTextSize(v[i], min);
    return v;
  }
  if (op === "step") {
    v[2] = clampTextSize(v[2], min);
    for (let i = 4; i < v.length; i += 2) v[i] = clampTextSize(v[i], min);
    return v;
  }
  if (op === "case") {
    for (let i = 2; i < v.length; i += 2) v[i] = clampTextSize(v[i], min);
    v[v.length - 1] = clampTextSize(v[v.length - 1], min);
    return v;
  }
  if (op === "match") {
    for (let i = 3; i < v.length; i += 2) v[i] = clampTextSize(v[i], min);
    v[v.length - 1] = clampTextSize(v[v.length - 1], min);
    return v;
  }
  return ["max", min, value];
}

function applyTextSizes(input: LayerSpecification[]): LayerSpecification[] {
  return input.map((layer) => {
    if (layer.type !== "symbol" || layer.layout?.["text-field"] === undefined) return layer;
    const layout = { ...layer.layout } as Record<string, unknown>;
    const override = TEXT_SIZE_OVERRIDES[layer.id];
    const size = override ?? layout["text-size"] ?? 16; // 16 = MapLibre default text-size
    const min = isLabelRule(layout["text-field"]) ? MIN_NAME_TEXT_SIZE : MIN_TEXT_SIZE;
    layout["text-size"] = clampTextSize(size, min);
    return { ...layer, layout } as SymbolLayerSpecification;
  });
}

const TRUNK_KINDS = ["trunk", "trunk_link"];
const trunkMatch = (trunkColour: string, otherwise: unknown): ExpressionSpecification =>
  ["match", ["get", "kind_detail"], TRUNK_KINDS, trunkColour, otherwise] as unknown as ExpressionSpecification;

const TRUNK_FILL_LAYERS = ["roads_major", "roads_bridges_major"];
const TRUNK_CASING_LAYERS = ["roads_major_casing_early", "roads_major_casing_late", "roads_bridges_major_casing"];
const HIGHWAY_FILL_LAYERS = ["roads_highway", "roads_bridges_highway"];
export const LOW_ZOOM_BREAK = 10;

/** map-style.md §3.2 (trunk colour) and §3.3 (low-zoom road colour). */
export function applyRoadColours(input: LayerSpecification[], theme: Theme): LayerSpecification[] {
  const c = customRoadColours(theme);
  return input.map((layer) => {
    if (layer.type !== "line") return layer;
    const original = layer.paint?.["line-color"];
    let colour: unknown;
    if (TRUNK_FILL_LAYERS.includes(layer.id)) {
      const normal = trunkMatch(c.trunk, original);
      const low = trunkMatch(c.lowzoom_trunk, c.lowzoom_major);
      colour = ["step", ["zoom"], low, LOW_ZOOM_BREAK, normal];
    } else if (TRUNK_CASING_LAYERS.includes(layer.id)) {
      colour = trunkMatch(c.trunk_casing, original);
    } else if (HIGHWAY_FILL_LAYERS.includes(layer.id)) {
      colour = ["step", ["zoom"], c.lowzoom_highway, LOW_ZOOM_BREAK, original];
    } else {
      return layer;
    }
    return { ...layer, paint: { ...layer.paint, "line-color": colour } } as LayerSpecification;
  });
}

/** Day = stock `light` + our day tokens; night = stock `dark` + our night tokens (ADR-0004 §3, map-style.md §1). */
export function flavorFor(theme: Theme): Flavor {
  const base = namedFlavor(theme === "day" ? "light" : "dark");
  const ours = tokenFlavor(theme);
  return {
    ...base,
    ...ours,
    pois: { ...base.pois, ...ours.pois } as Flavor["pois"],
    landcover: { ...base.landcover, ...ours.landcover } as Flavor["landcover"],
  };
}

export type LocationData = GeoJSON.FeatureCollection<GeoJSON.Polygon, { stale: boolean }>;
export const EMPTY_LOCATION: LocationData = { type: "FeatureCollection", features: [] };

/** map-style.md §7: accuracy circle (fill + line), placed below the first symbol layer. */
function locationLayers(theme: Theme): LayerSpecification[] {
  const c = locationColours(theme);
  const stale: ExpressionSpecification = ["boolean", ["get", "stale"], false];
  return [
    {
      id: LOCATION_FILL_LAYER_ID,
      type: "fill",
      source: LOCATION_SOURCE_ID,
      paint: { "fill-color": ["case", stale, c["stale-accuracy-fill"], c["accuracy-fill"]] as ExpressionSpecification },
    },
    {
      id: LOCATION_LINE_LAYER_ID,
      type: "line",
      source: LOCATION_SOURCE_ID,
      paint: {
        "line-color": ["case", stale, c["stale-accuracy-stroke"], c["accuracy-stroke"]] as ExpressionSpecification,
        "line-width": 1,
      },
    },
  ];
}

export interface StyleConfig {
  /** MapLibre source URL, e.g. pmtiles://http://localhost:8080/tiles/basemap.pmtiles */
  sourceUrl: string;
  /** Absolute URL of the bundled assets, ending with "/" (e.g. http://localhost:5173/). */
  assetBaseUrl: string;
}

export function buildStyle(theme: Theme, cfg: StyleConfig, location: LocationData = EMPTY_LOCATION): StyleSpecification {
  let ls = protomapsLayers(SOURCE_ID, flavorFor(theme), { lang: "en" }) as LayerSpecification[];
  ls = applyLabelRule(ls);
  ls = applyTextSizes(ls);
  ls = applyRoadColours(ls, theme);
  const firstSymbol = ls.findIndex((l) => l.type === "symbol");
  const at = firstSymbol === -1 ? ls.length : firstSymbol;
  ls = [...ls.slice(0, at), ...locationLayers(theme), ...ls.slice(at)];

  return {
    version: 8,
    name: `navmn-${theme}`,
    glyphs: cfg.assetBaseUrl + "fonts/{fontstack}/{range}.pbf",
    sprite: cfg.assetBaseUrl + "sprites/v4/" + (theme === "day" ? "light" : "dark"),
    sources: {
      [SOURCE_ID]: { type: "vector", url: cfg.sourceUrl },
      [LOCATION_SOURCE_ID]: { type: "geojson", data: location },
    },
    layers: ls,
  };
}

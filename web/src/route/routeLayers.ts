// Route lines, alternatives and the manoeuvre point as MapLibre sources and layers (docs/design/map-style.md §7.2–7.3;
// NAV-004 AC 16–18, 20, 30, 32). Colours come from tokens.json (route.*) through src/style/tokens.ts.
// The layers are part of the style that buildStyle() returns, so a day/night switch (setStyle) keeps them with their
// current data and the new theme's colours (AC 20), exactly like the NAV-002 location layers.
import type { ExpressionSpecification, LayerSpecification } from "maplibre-gl";
import { routeColours, type Theme } from "../style/tokens";
import type { OsrmRouteResponse } from "./osrm";
import { decodePolyline } from "./polyline";

export const ROUTE_SOURCE_ID = "nav-route";
export const ROUTE_STEP_SOURCE_ID = "nav-route-step";
export const ROUTE_ALT_CASING_LAYER_ID = "nav-route-alt-casing";
export const ROUTE_ALT_LAYER_ID = "nav-route-alt";
export const ROUTE_SEL_CASING_LAYER_ID = "nav-route-sel-casing";
export const ROUTE_SEL_LAYER_ID = "nav-route-sel";
/** Invisible, wider line over the alternatives: the click/hover target (≥ 10 px each side, AC 18). */
export const ROUTE_HIT_LAYER_ID = "nav-route-hit";
export const ROUTE_STEP_LAYER_ID = "nav-route-step";

export type RouteLineData = GeoJSON.FeatureCollection<GeoJSON.LineString, { index: number; selected: boolean }>;
export type RouteStepData = GeoJSON.FeatureCollection<GeoJSON.Point>;

export interface RouteStyleData {
  lines: RouteLineData;
  step: RouteStepData;
}

export const EMPTY_ROUTE_LINES: RouteLineData = { type: "FeatureCollection", features: [] };
export const EMPTY_ROUTE_STEP: RouteStepData = { type: "FeatureCollection", features: [] };
export const EMPTY_ROUTE_STYLE: RouteStyleData = { lines: EMPTY_ROUTE_LINES, step: EMPTY_ROUTE_STEP };

/** map-style.md §7.2 widths (px) at z5 / z10 / z14 / z18. */
const WIDTHS = {
  altCasing: [4, 6, 8, 11],
  alt: [2, 4, 6, 9],
  selCasing: [8, 10, 12, 16],
  sel: [4, 6, 8, 12],
  hit: [24, 26, 28, 31],
} as const;
const ZOOMS = [5, 10, 14, 18] as const;

function width(w: readonly number[]): ExpressionSpecification {
  const stops: number[] = [];
  ZOOMS.forEach((z, i) => stops.push(z, w[i]!));
  return ["interpolate", ["linear"], ["zoom"], ...stops] as unknown as ExpressionSpecification;
}

const isSelected: ExpressionSpecification = ["==", ["get", "selected"], true];
const isAlternative: ExpressionSpecification = ["==", ["get", "selected"], false];
const ROUND = { "line-cap": "round", "line-join": "round" } as const;

/** Layers bottom → top (map-style.md §7.2): alternatives, selected line above them, hit area, manoeuvre point. */
export function routeLayers(theme: Theme): LayerSpecification[] {
  const c = routeColours(theme);
  return [
    { id: ROUTE_ALT_CASING_LAYER_ID, type: "line", source: ROUTE_SOURCE_ID, filter: isAlternative, layout: ROUND, paint: { "line-color": c["alternative-casing"], "line-width": width(WIDTHS.altCasing) } },
    { id: ROUTE_ALT_LAYER_ID, type: "line", source: ROUTE_SOURCE_ID, filter: isAlternative, layout: ROUND, paint: { "line-color": c.alternative, "line-width": width(WIDTHS.alt) } },
    { id: ROUTE_SEL_CASING_LAYER_ID, type: "line", source: ROUTE_SOURCE_ID, filter: isSelected, layout: ROUND, paint: { "line-color": c["selected-casing"], "line-width": width(WIDTHS.selCasing) } },
    { id: ROUTE_SEL_LAYER_ID, type: "line", source: ROUTE_SOURCE_ID, filter: isSelected, layout: ROUND, paint: { "line-color": c.selected, "line-width": width(WIDTHS.sel) } },
    { id: ROUTE_HIT_LAYER_ID, type: "line", source: ROUTE_SOURCE_ID, filter: isAlternative, layout: ROUND, paint: { "line-color": c.alternative, "line-opacity": 0, "line-width": width(WIDTHS.hit) } },
    {
      id: ROUTE_STEP_LAYER_ID,
      type: "circle",
      source: ROUTE_STEP_SOURCE_ID,
      paint: { "circle-radius": 6, "circle-color": c["step-fill"], "circle-stroke-color": c["step-stroke"], "circle-stroke-width": 3 },
    },
  ];
}

/** The two GeoJSON sources with their current data (so a style switch keeps the route, AC 20). */
export function routeSources(data: RouteStyleData): Record<string, { type: "geojson"; data: GeoJSON.FeatureCollection }> {
  return {
    [ROUTE_SOURCE_ID]: { type: "geojson", data: data.lines },
    [ROUTE_STEP_SOURCE_ID]: { type: "geojson", data: data.step },
  };
}

/** Decoded geometries of every route in the response, in Valhalla order. */
export function routeGeometries(r: OsrmRouteResponse): [number, number][][] {
  return r.routes.map((route) => decodePolyline(route.geometry, 6));
}

/** One LineString per route; only `selected` changes when another route is chosen (AC 18). */
export function routeLineData(geometries: [number, number][][], selected: number): RouteLineData {
  return {
    type: "FeatureCollection",
    features: geometries
      .map((coordinates, index) => ({
        type: "Feature" as const,
        properties: { index, selected: index === selected },
        geometry: { type: "LineString" as const, coordinates },
      }))
      .filter((f) => f.geometry.coordinates.length >= 2),
  };
}

export function stepPointData(lngLat: [number, number] | null): RouteStepData {
  if (!lngLat) return EMPTY_ROUTE_STEP;
  return { type: "FeatureCollection", features: [{ type: "Feature", properties: {}, geometry: { type: "Point", coordinates: lngLat } }] };
}

/** [west, south, east, north] of every coordinate given, or null. */
export function boundsOf(points: Iterable<[number, number]>): [number, number, number, number] | null {
  let w = Infinity;
  let s = Infinity;
  let e = -Infinity;
  let n = -Infinity;
  for (const [lng, lat] of points) {
    if (lng < w) w = lng;
    if (lng > e) e = lng;
    if (lat < s) s = lat;
    if (lat > n) n = lat;
  }
  return Number.isFinite(w) ? [w, s, e, n] : null;
}

// NAV-002 AC 8 fixture: (a) name:mn + name + name:en, (b) name + name:en, (c) name:en only, (d) no name keys.
import "maplibre-gl/dist/maplibre-gl.css";
import { Map as MapLibreMap, setWorkerUrl } from "maplibre-gl";
import maplibreWorkerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import { assetBaseUrl } from "../src/config";
import { LABEL_EXPRESSION } from "../src/style/buildStyle";

setWorkerUrl(maplibreWorkerUrl);

// Test data only (AC 8 fixture values from the story); not user-facing UI text.
const FEATURES: GeoJSON.FeatureCollection<GeoJSON.Point> = {
  type: "FeatureCollection",
  features: [
    { type: "Feature", id: 1, properties: { case: "a", "name:mn": "Тест А", name: "Тест Б", "name:en": "Test C" }, geometry: { type: "Point", coordinates: [-60, 0] } },
    { type: "Feature", id: 2, properties: { case: "b", name: "Тест Б", "name:en": "Test C" }, geometry: { type: "Point", coordinates: [-20, 0] } },
    { type: "Feature", id: 3, properties: { case: "c", "name:en": "Test C" }, geometry: { type: "Point", coordinates: [20, 0] } },
    { type: "Feature", id: 4, properties: { case: "d" }, geometry: { type: "Point", coordinates: [60, 0] } },
  ],
};

const base = assetBaseUrl(window.location.origin, import.meta.env.BASE_URL);
const map = new MapLibreMap({
  container: "map",
  center: [0, 0],
  zoom: 1,
  attributionControl: false,
  style: {
    version: 8,
    glyphs: base + "fonts/{fontstack}/{range}.pbf",
    sources: { fixture: { type: "geojson", data: FEATURES } },
    layers: [
      { id: "bg", type: "background", paint: { "background-color": "#ffffff" } },
      {
        id: "fixture-labels",
        type: "symbol",
        source: "fixture",
        layout: { "text-field": LABEL_EXPRESSION, "text-font": ["Noto Sans Regular"], "text-size": 16, "text-allow-overlap": true },
      },
    ],
  },
});

declare global {
  interface Window {
    __labelRule?: { map: MapLibreMap; LABEL_EXPRESSION: typeof LABEL_EXPRESSION; ready: Promise<void> };
  }
}
window.__labelRule = { map, LABEL_EXPRESSION, ready: new Promise((resolve) => map.once("idle", () => resolve())) };

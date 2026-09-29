// NAV-002 web demo map: entry point.
// Spec: docs/design/screens/NAV-002-web-map.md, flows/NAV-002-web-demo-map.md, map-style.md; ADR-0004.
import "maplibre-gl/dist/maplibre-gl.css";
import "./styles.css";
import { addProtocol, setWorkerUrl, type Map as MapLibreMap } from "maplibre-gl";
import maplibreWorkerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import { PMTiles, Protocol } from "pmtiles";
import { loadConfig } from "./config";
import { I18n } from "./i18n/i18n";
import { LocationController } from "./location/locationController";
import { createMap } from "./map/createMap";
import { loadLang, loadTheme } from "./prefs";
import { LABEL_EXPRESSION } from "./style/buildStyle";
import { tokensCss } from "./style/tokens";
import { StatusMachine } from "./state/status";
import { App } from "./ui/app";

declare global {
  interface Window {
    /** Test hook, dev builds only (ADR-0004 §7). */
    __nav002?: { map: MapLibreMap; LABEL_EXPRESSION: typeof LABEL_EXPRESSION; app: App };
  }
}

function injectTokens(): void {
  const style = document.createElement("style");
  style.id = "design-tokens";
  style.textContent = tokensCss();
  document.head.prepend(style);
}

function main(): void {
  injectTokens();
  setWorkerUrl(maplibreWorkerUrl);

  const cfg = loadConfig(import.meta.env, window.location.origin);
  const i18n = new I18n(loadLang());
  const status = new StatusMachine(navigator.onLine);

  const protocol = new Protocol({ metadata: false, errorOnMissingTile: false });
  addProtocol("pmtiles", protocol.tile);
  protocol.add(new PMTiles(cfg.tilesUrl));

  const app = new App({
    cfg,
    i18n,
    status,
    theme: loadTheme(),
    // Retry must use a fresh PMTiles instance: pmtiles caches a failed header load (ADR-0004 §5).
    freshArchive: () => protocol.add(new PMTiles(cfg.tilesUrl)),
    createMap,
    createLocation: (camera) =>
      new LocationController(
        "geolocation" in navigator ? navigator.geolocation : undefined,
        window.isSecureContext,
        camera,
      ),
  });
  app.start();

  if (import.meta.env.DEV && app.map) {
    window.__nav002 = { map: app.map, LABEL_EXPRESSION, app };
  }
}

main();

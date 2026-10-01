// NAV-002 web demo map + NAV-003 search: entry point.
// Spec: docs/design/screens/NAV-002-web-map.md, flows/NAV-002-web-demo-map.md, map-style.md; ADR-0004.
import "maplibre-gl/dist/maplibre-gl.css";
// styles.css is linked from index.html (render-blocking), so the pre-module loading pill is styled (AC 37).
import { addProtocol, setWorkerUrl, type Map as MapLibreMap } from "maplibre-gl";
import maplibreWorkerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import { PMTiles, Protocol } from "pmtiles";
import { cancelBootLoading } from "./boot/bootLoading";
import { loadConfig } from "./config";
import { I18n } from "./i18n/i18n";
import { LocationController } from "./location/locationController";
import { createMap } from "./map/createMap";
import { loadLang, loadTheme } from "./prefs";
import { LABEL_EXPRESSION } from "./style/buildStyle";
import { tokensCss } from "./style/tokens";
import { StatusMachine } from "./state/status";
import { App, type AppDeps } from "./ui/app";

declare global {
  interface Window {
    /** Test hook, dev builds only (ADR-0004 §7). */
    __nav002?: { map: MapLibreMap; LABEL_EXPRESSION: typeof LABEL_EXPRESSION; app: App };
    /** NAV-003 test hook, dev builds only (ADR-0006, web/README.md › Test hooks). */
    __nav003?: { search: NonNullable<App["search"]>; map: MapLibreMap | null };
    /** NAV-004 test hook, dev builds only (web/README.md › Test hooks). */
    __nav004?: { route: NonNullable<App["route"]>; map: MapLibreMap | null };
  }
}

/** Design-token CSS. index.html normally has it already (vite.config.ts › bootIndicator); this is the fallback. */
function injectTokens(): void {
  if (document.getElementById("design-tokens")) return;
  const style = document.createElement("style");
  style.id = "design-tokens";
  style.textContent = tokensCss();
  document.head.prepend(style);
}

function main(): void {
  // From here on the StatusMachine owns the loading pill (it counts the time since navigation start).
  cancelBootLoading();
  injectTokens();
  setWorkerUrl(maplibreWorkerUrl);

  // Only the named keys: passing import.meta.env whole would inline every VITE_* build variable into the public bundle.
  const cfg = loadConfig(
    {
      VITE_GATEWAY_BASE_URL: import.meta.env.VITE_GATEWAY_BASE_URL,
      VITE_STATIC_DEMO: import.meta.env.VITE_STATIC_DEMO,
      BASE_URL: import.meta.env.BASE_URL,
    },
    window.location.origin,
    document.baseURI,
  );
  const i18n = new I18n(loadLang());
  const status = new StatusMachine(navigator.onLine);

  const protocol = new Protocol({ metadata: false, errorOnMissingTile: false });
  addProtocol("pmtiles", protocol.tile);
  protocol.add(new PMTiles(cfg.tilesUrl));

  const deps: AppDeps = {
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
  };

  // NAV-017 demo-mode build only (ADR-0011 §2): the one branch into the demo code. In every other build the constant is
  // false, so the branch, the demo chunk, the WASM and the route data are not in the output (AC 4).
  if (__NAVMN_DEMO_MODE__) {
    void import("./demo/demoMain")
      .then((m) => m.startDemoMode(deps))
      .catch((err: unknown) => {
        // The demo chunk did not load: the map shell with the NAV-002 generic error card («Дахин оролдох» reloads).
        console.error(err);
        const shell = new App({ ...deps, searchAndRoute: false, createLocation: (camera) => new LocationController(undefined, false, camera) });
        shell.start();
        status.fail();
      });
    return;
  }

  const app = new App(deps);
  app.start();

  if (import.meta.env.DEV && app.map) {
    window.__nav002 = { map: app.map, LABEL_EXPRESSION, app };
  }
  if (import.meta.env.DEV && app.search) {
    window.__nav003 = { search: app.search, map: app.map };
  }
  if (import.meta.env.DEV && app.route) {
    window.__nav004 = { route: app.route, map: app.map };
  }
}

main();

#!/usr/bin/env node
// ADR-0009 §6 (NAV-005 A3): exports the web basemap style for native clients. Runs the web's own buildStyle(theme, cfg)
// (no second, hand-written style) with
//   - assetBaseUrl "asset://"            → glyphs asset://fonts/{fontstack}/{range}.pbf, sprites asset://sprites/v4/…
//   - sourceUrl "{GATEWAY_TILES_URL}"    → replaced at runtime by pmtiles://<gateway>/tiles/basemap.pmtiles
// and removes the web-only overlay sources and layers (ids "nav-…": NAV-002 location circle, NAV-004 route preview);
// the Android app adds its own guidance layers (map-style.md §7.4) at runtime. Writes the committed files
//   mobile/android/app/src/main/assets/style/basemap-day.json and basemap-night.json.
// The web runtime is unchanged: it still builds the style in the browser.
//
//   node web/scripts/export-native-style.mjs           # write
//   node web/scripts/export-native-style.mjs --check   # exit 1 if the committed files are stale
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { runnerImport } from "vite";

const WEB = fileURLToPath(new URL("..", import.meta.url));
const ROOT = resolve(WEB, "..");
const OUT_DIR = resolve(ROOT, "mobile/android/app/src/main/assets/style");
export const TILES_PLACEHOLDER = "{GATEWAY_TILES_URL}";
const alias = { "@design": resolve(ROOT, "docs/design") };

const { module: style } = await runnerImport(resolve(WEB, "src/style/buildStyle.ts"), {
  configFile: false,
  logLevel: "warn",
  resolve: { alias },
});

/** The native style JSON for one theme (stable key order from buildStyle, 2-space indent, trailing newline). */
function nativeStyle(theme) {
  const s = style.buildStyle(theme, { sourceUrl: TILES_PLACEHOLDER, assetBaseUrl: "asset://" });
  const sources = Object.fromEntries(Object.entries(s.sources).filter(([id]) => !id.startsWith("nav-")));
  const layers = s.layers.filter((l) => !l.id.startsWith("nav-"));
  return JSON.stringify({ ...s, name: `navmn-native-${theme}`, sources, layers }, null, 2) + "\n";
}

const check = process.argv.includes("--check");
let stale = 0;
for (const theme of ["day", "night"]) {
  const file = resolve(OUT_DIR, `basemap-${theme}.json`);
  const text = nativeStyle(theme);
  if (check) {
    const current = existsSync(file) ? readFileSync(file, "utf8") : "";
    if (current !== text) {
      stale++;
      console.error(`stale: ${file} (run node web/scripts/export-native-style.mjs)`);
    } else {
      console.log(`up to date: basemap-${theme}.json`);
    }
  } else {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, text);
    console.log(`wrote ${file} (${text.length} bytes)`);
  }
}
process.exit(stale ? 1 : 0);

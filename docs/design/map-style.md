# Map style spec (MapLibre)

- **Owner:** ux-designer
- **Stories:** NAV-002 (AC 5–10, 15, 26–29, 35, 46), NAV-003 (§7.1 selected-place pin; AC 21, 23, 25), NAV-004 (§7.2 route lines and alternatives, §7.3 route markers; AC 9, 16–18, 20, 30), NAV-005 (§7.4 Android guidance route line, puck and native style; §8 automatic theme; AC 1, 2, 23, 51, 58, 59), NAV-017 (§7.5 web demo-mode picker route, replay route line and puck; AC 9, 13, 22, 41, 44), NAV-011 (§7.6 Android route preview with alternatives; AC 15–17, 19–21), NAV-012 (§8 automatic theme by sunrise/sunset; AC 41–43), NAV-018 (§7.7 Android route markers for a chosen start, candidate pin and manoeuvre point; AC 6, 8, 22). Later stories add sections (traffic: Phase 3).
- **Colour values:** `docs/design/tokens.json` is the single source of truth. The tables below quote it for readability. `node docs/design/prototypes/check-contrast.mjs` fails if a table value here drifts from `tokens.json`, or if a listed text/background pair drops below WCAG AA.
- **Status:** v0.9, 2026-10-04 (§7.7 NAV-018: Android start marker for a chosen start, candidate pin on the preview-time coordinate card, `nav-route-step` manoeuvre point; §7.6 points to it; no new colours). v0.8, 2026-10-03 (§8 NAV-012: Android «Автомат» = sunrise/sunset on the device; §9 trimming and arrows no longer labelled NAV-012; no new colours). v0.7, 2026-10-03 (§7.6 NAV-011 Android alternatives: §7.2 layers in dp, 48 dp tap box, no new colours). v0.6, 2026-10-01 (§7.5 NAV-017 web demo mode: picker route and markers, replay route line, web puck; no new colours). v0.5, 2026-10-01 (§7.4 NAV-005 Android guidance: route line, off-route display, puck, native style rules; §8 «Автомат» theme on Android). v0.4, 2026-09-30 (§7.2–7.3 NAV-004 route lines, origin marker, destination = NAV-003 pin, candidate point, manoeuvre point). v0.3, 2026-09-30 (§1 zoom range: D1 max zoom 14 read from the archive header; §7.1 NAV-003 pin). v0.2, 2026-09-30 (§1 post-processing moved to runtime per ADR-0004 §3; PO decisions D11–D13, D16 recorded). v0.1, 2026-09-29. Colours are a first proposal. The PO judges them on real tiles in the NAV-002 demo (story goal: "is the open basemap good enough for Mongolian users").
- **Checked on real tiles (2026-09-29, design preview only, not app code):** both styles were generated from `tokens.json` with `@protomaps/basemaps` 5.7.2 plus the §3.2, §3.3 and §4 post-processing (the same steps `buildStyle` now runs at runtime, §1), validated with the MapLibre style validator (0 errors, 0 layers left reading `name:ru` / `pgf:*` / `name2` / `name3`), and rendered against the NAV-001 gateway archive at P1 z12, z14, z16.5 and Mongolia z6/z7, day and night. Result: Cyrillic labels incl. ө/ү render, Энх тайваны өргөн чөлөө shows as trunk, and the night map has no bright areas. Two things to watch in the demo: the low-zoom road *widths* are thin Protomaps defaults (colours fixed in §3.3; widths: PO decision D16, 2026-09-30, the PO judges them in the demo and a later widening comes back to this spec as a change), and some POI names are Latin because that is the OSM `name` (story R2).

## 1. Base and build approach

| Item | Decision |
|---|---|
| Tile schema | Protomaps basemap, tiles 4.15.2, commit `42ffaaa4` (ADR-0002). One vector source named `protomaps`. |
| Style base | `@protomaps/basemaps` style generator **5.7.2** (the `styles/` package at the same commit). Its `layers("protomaps", flavor, …)` output is the layer list. We do **not** hand-write layers. |
| Our flavors | `Day` = `tokens.json › color.light.map.flavor`, `Night` = `tokens.json › color.night.map.flavor`. Both are complete Protomaps `Flavor` objects (every key, checked by the checker script). We do not use the stock `light` / `dark` flavors: their label colours are too low-contrast (for example stock dark minor-road labels are 2.3:1), and the stock light map is grey without a road hierarchy. |
| Post-processing | **At runtime in the client, not a build step** (ADR-0004 §3). `buildStyle(theme, cfg)` in `web/` (mobile-engineer) calls the generator with our flavor, applies §3.2 (trunk colour), §3.3 (low-zoom road colour), §4 (label expression, sizes) and §5–6 (absolute glyph/sprite URLs from `location.origin`), and sets the tile URL from the gateway base URL (NAV-002 AC 2–3). The flavor colours are read from `docs/design/tokens.json`, which the web build imports read-only through the `@design` alias (ADR-0004 Amendment 1); no colour is copied into `web/`. No generated style JSON is written or committed (no `day.json` / `night.json`), so a `@protomaps/basemaps` upgrade is a version bump checked by the ADR-0004 §3 unit test. |
| Source URL | `pmtiles://<GATEWAY_BASE_URL>/tiles/basemap.pmtiles` (default `http://localhost:8080`). |
| Opening view | P1, Sükhbaatar Square (47.9189, 106.9176), z12, bearing 0 (NAV-002 AC 5; PO decision D13, 2026-09-30). |
| Source attribution | Not shown from the style or the PMTiles metadata. The UI renders the credit lines from resource files (screen spec §Attribution; ADR-0002 Consequences). MapLibre's own `AttributionControl` is off. |
| Zoom range | Camera `minZoom` 3, `maxZoom` 19 (NAV-002 AC 12). The default dev archive's max zoom is exactly **14** (`TILES_MAXZOOM=14`, PO decision D1, 2026-09-30). Clients **read `maxzoom` from the PMTiles header** (the `pmtiles` protocol passes it to the MapLibre source) and never hard-code 14 or 15, so a rebuilt archive with another max zoom needs no client change. Above the archive's max zoom MapLibre over-zooms, so roads and labels stay visible to z19 (AC 9). |
| Pitch | Default `maxPitch`. Tilt is not styled or tested in NAV-002. No 3D buildings. |
| World copies | On. No `maxBounds`: panning outside Mongolia is allowed and shows whatever low-zoom data exists, with no error (AC 10). |

## 2. Land, water and landcover

| Key | Day | Night | Notes |
|---|---|---|---|
| `background` | #DCD8D0 | #0E141C | Visible only where no earth or water polygon exists |
| `earth` | #F3F0EA | #18202B | Main land colour. QA compares it (AC 27) |
| `water` | #A8CDEE | #0B2238 | Lakes, rivers, Tuul river. QA compares it (AC 27) |
| `park_a` | #DCEBD5 | #1A2A26 | |
| `park_b` | #C5E3BD | #1D3029 | |
| `wood_a` | #D5E7CE | #1A2B25 | |
| `wood_b` | #BEDDB4 | #1C3128 | |
| `buildings` | #E3DED5 | #222B37 | Flat, no extrusion |
| `landcover.grassland` | #E3EDD2 | #1B2622 | Most of Mongolia at z3–7 |
| `landcover.barren` | #EFE9DC | #211F1B | Gobi |
| `landcover.forest` | #C9E0BE | #1A2A24 | Khentii, Khövsgöl |

Landcover (Daylight / ESA WorldCover, CC BY 4.0) fades from full opacity at z5 to 0 at z7 (Protomaps default, unchanged). The ESA credit rule is in the screen spec (§Attribution, shown while zoom < 8).

## 3. Roads

### 3.1 Hierarchy (Protomaps `roads` layer)

| Our class | Protomaps `kind` / `kind_detail` (OSM `highway=`) | Fill key | Casing key | Day fill / casing | Night fill / casing |
|---|---|---|---|---|---|
| Motorway | `highway` (motorway, motorway_link) | `highway` | `highway_casing_early/late` | #FFC65C / #D18F2E | #8C7443 / #10151C |
| Trunk (e.g. AH3, city exits) | `major_road` + `kind_detail` trunk, trunk_link | `custom.trunk` (§3.2) | `custom.trunk_casing` | #FFD98A / #D9A441 | #7C6B48 / #10151C |
| Major (e.g. Энхтайвны өргөн чөлөө) | `major_road` (primary, secondary, tertiary and links) | `major` | `major_casing_early/late` | #FFFFFF / #C9C2B5 | #4A5566 / #10151C |
| Minor | `minor_road` (residential, unclassified, road) | `minor_a`, `minor_b` | `minor_casing` | #FFFFFF / #D5D0C6 | #36404E / #141A22 |
| Service | `minor_road` + `kind_detail` service | `minor_service` | `minor_service_casing` | #FFFFFF / #DDD8CF | #2F3845 / #141A22 |
| Path / track / footway | `path` (footway, path, cycleway, steps, pedestrian, track) | `other` | none | #CBC2B2 | #3A4452 |
| Link roads | `is_link` | `link` | `link_casing` | #FFE3A8 / #D9A24A | #6B5A3A / #141A22 |
| Rail | `rail` | `railway` | none | #A5A9B0 | #56606E |

Table rows for the checker (key, day, night):

| Key | Day | Night |
|---|---|---|
| `highway` | #FFC65C | #8C7443 |
| `highway_casing_early` | #D18F2E | #10151C |
| `major` | #FFFFFF | #4A5566 |
| `major_casing_early` | #C9C2B5 | #10151C |
| `minor_a` | #FFFFFF | #36404E |
| `minor_casing` | #D5D0C6 | #141A22 |
| `minor_service` | #FFFFFF | #2F3845 |
| `other` | #CBC2B2 | #3A4452 |
| `link` | #FFE3A8 | #6B5A3A |
| `railway` | #A5A9B0 | #56606E |
| `boundaries` | #9A8FA8 | #6E6A86 |
| `custom.trunk` | #FFD98A | #7C6B48 |
| `custom.trunk_casing` | #D9A441 | #10151C |
| `custom.lowzoom_highway` | #CC7F1A | #8C7443 |
| `custom.lowzoom_trunk` | #D4952B | #7C6B48 |
| `custom.lowzoom_major` | #B3AA99 | #4A5566 |

- Bridges use the same colours as the matching road (`bridges_*` keys). Tunnels use a muted fill (`tunnel_*`: day #EEEBE5, motorway #F6DDB0; night #2A3340, motorway #4A3F2A) so they read as "below".
- Widths and zoom ramps: Protomaps defaults, unchanged in NAV-002 (PO decision D16: judged in the demo, widened later through this spec if needed).
- **"Major road" colour for QA (AC 27)** means the `major` key (`roads_major` layer): day #FFFFFF, night #4A5566.
- **Unpaved roads (NAV-002 R6):** confirmed from the tiles source (`Roads.java` at `42ffaaa4`). The `roads` layer carries **no `surface` attribute**, so paved and unpaved roads cannot be styled differently. `highway=track` is `kind=path`, `kind_detail=track`, drawn with the `other` colour from the path minimum zoom. That is weak for the intercity and countryside persona (in the countryside, tracks are often the only road between soums). A later story needs a schema change (upstream or our own layer). Recorded as an open item, not a NAV-002 change.

### 3.2 Post-processing: trunk colour
Protomaps puts trunk, primary, secondary and tertiary into one `major_road` kind with one colour. In Mongolia, trunk roads (city exits, the AH3 / Millennium road) are the main intercity roads, so they get their own colour. For the layers `roads_major`, `roads_bridges_major`, `roads_major_casing_early`, `roads_major_casing_late` and `roads_bridges_major_casing`, replace `line-color` with:

```json
["match", ["get", "kind_detail"], ["trunk", "trunk_link"], "<custom.trunk or custom.trunk_casing>", "<original flavor value>"]
```

Tunnel layers keep the tunnel colours.

### 3.3 Post-processing: low-zoom road colour
Below z10 Protomaps draws motorway, trunk and major roads as thin fills without casing, so white or pale-yellow roads vanish on the light earth colour (seen on real tiles at z7: the UB–Darkhan trunk road was barely visible). For the fill layers `roads_highway` and `roads_major` (and their bridge layers) wrap the `line-color` in a zoom step:

```json
["step", ["zoom"], "<low-zoom colour>", 10, "<normal colour expression from §3.2>"]
```

Low-zoom colours: `custom.lowzoom_highway` (motorway), `custom.lowzoom_trunk` (trunk, same `kind_detail` match as §3.2), `custom.lowzoom_major` (other major roads). At night they equal the normal fills, so the night style does not change.

## 4. Labels

### 4.1 Name expression (CLAUDE.md rule 7, glossary C8, NAV-002 AC 7–8)
Every symbol layer that shows a feature **name** uses exactly this `text-field`, in the day **and** night styles, in **both** UI languages (PO decision D11, 2026-09-30: the English UI keeps the Cyrillic labels):

```json
["coalesce", ["get", "name:mn"], ["get", "name"], ["get", "name:en"]]
```

- The generated Protomaps expressions (`get_multiline_name`, `get_country_name`) are **replaced**, not wrapped. They read `pgf:name`, `name2`, `name3`, `script` and, for countries, `name:<lang>` → `name:en`. After post-processing no layer reads `name:ru`, `pgf:*`, `name2`, `name3` or any other `name:*` key.
- `name:mn` is not in the current tiles (ADR-0002), so labels start at `name`, which is Cyrillic Mongolian for most of Mongolia. The rule stays complete so it works as soon as upstream adds `mn`.
- A feature with none of the three keys gets an empty label (MapLibre draws nothing, no error; AC 8).
- Labels are single-line names. Protomaps' two-line "local name + second script" format is dropped.

| Layer id | Shows | Font (fontstack) | `text-field` | Size (px) |
|---|---|---|---|---|
| `places_country` | Country | Noto Sans Medium | name rule | Protomaps default |
| `places_region` | Aimag (state) | Noto Sans Regular | name rule | Protomaps default (11 → 16) |
| `places_locality` | City, soum centre, town | Noto Sans Medium (capital/large), Regular | name rule | Protomaps default |
| `places_subplace` | Düüreg, khoroo, micro-district (neighbourhood) | Noto Sans Regular | name rule | Protomaps default |
| `pois` | Points of interest | Noto Sans Regular | name rule | Protomaps default |
| `roads_labels_major` | Major road names | Noto Sans Regular | name rule | **13** (was 12) |
| `roads_labels_minor` | Minor road names | Noto Sans Regular | name rule | **12 at z14 → 14 at z18** (was 12) |
| `water_label_lakes` | Lakes (Khövsgöl, Uvs) | Noto Sans Italic | name rule | Protomaps default |
| `water_label_ocean` | Seas | Noto Sans Italic | name rule | **11 at z3 → 12 at z10** (was 10 → 12) |
| `water_waterway_label` | Rivers (Tuul, Selenge) | Noto Sans Italic | name rule | 12 |
| `earth_label_islands` | Islands | Noto Sans Italic | name rule | **11** (was 10) |
| `roads_shields` | Road reference (`shield_text`, e.g. «А0501») | Noto Sans Medium | unchanged (`shield_text`, not a name) | **10** (was 8) |
| `address_label` | House numbers (`addr_housenumber`) | Noto Sans Italic | unchanged (not a name) | 12 |

- Minimum text size for any label is **10 px**, and **11 px** for any name. Cyrillic at 8 to 10 px loses ө/ү detail.
- Halo: Protomaps default widths, colours from the flavor (`*_label_halo`). The listed label/halo pairs are ≥ 4.5:1 in both modes (checker).
- Label priority: the Protomaps layer order is kept (MapLibre places later layers first): country > locality > region > subplace > POI > major road > shields > lakes/ocean > minor road > waterway > house number.
- Labels stay upright during rotation (`text-rotation-alignment` defaults, AC 15). Road names follow the line (`symbol-placement: line`, Protomaps default).

### 4.2 Scripts and glyph coverage
| Script in `name` | How it renders |
|---|---|
| Latin, Cyrillic incl. Ө ө Ү ү (U+04E8/9, U+04AE/F) | Bundled Noto Sans glyph PBFs (§5). Must return 200 for range `1024-1279` for every fontstack in use (AC 6). |
| CJK ideographs (Chinese border area, Beijing at X2) | Drawn locally by MapLibre (`localIdeographFontFamily`, default `sans-serif`). No glyph request, no network request. Keep the default. |
| Traditional Mongolian script (U+1800–U+18AF) | Not covered. The label may be missing. This must not raise the tiles-unavailable state (story edge case). Recorded as information by QA. |

## 5. Glyphs (bundled, no CDN)
- Source: `protomaps/basemaps-assets` repository, `fonts/` directory, fontstacks **Noto Sans Regular**, **Noto Sans Medium**, **Noto Sans Italic** (SIL OFL 1.1). Record the commit and licence in the third-party notices (AC 36).
- Bundle **all 256 ranges** of each of the three fontstacks (`0-255.pbf` … `65280-65535.pbf`). That is simpler than guessing ranges and it covers «» (U+00AB/BB), № (U+2116), – and … (U+2013, U+2026). Empty ranges are small.
- Served from the web origin, e.g. `/fonts/{fontstack}/{range}.pbf`. The style's `glyphs` URL is made absolute at runtime from `location.origin`.
- The Devanagari fontstack that Protomaps references is not needed after §4.1 and is not bundled.

## 6. Sprites and POI icons
- Source: `protomaps/basemaps-assets` `sprites/v4/`. Licence per the basemaps `LICENSE.md` at `42ffaaa4`: code BSD-3-Clause, visual design CC0, and some icons are derivatives of Mapzen/Tangram icons under MIT. All three go into the third-party notices (AC 36); the architect confirms in review. Day uses the `light` sprite, night uses the `dark` sprite (`@1x` and `@2x`).
- Served from the web origin, e.g. `/sprites/v4/light(.json|.png|@2x.json|@2x.png)`, absolute URL at runtime.
- POI selection, icons and zoom filters: Protomaps defaults. Icon/label colours by category come from `flavor.pois` (8 colours per mode, all ≥ 4.5:1 on `earth`).
- No custom Mongolia icons in NAV-002. Petrol stations show whatever `name` the data has (the glossary «ШТС» rule applies to our own UI strings and category chips, not to OSM `name` values).

## 7. Our own map layers (NAV-002)
| Layer / element | Type | Placement | Day | Night |
|---|---|---|---|---|
| `nav-location-accuracy-fill` | `fill` (GeoJSON circle polygon, 64 vertices, radius = reported accuracy in metres) | Below the first symbol layer (`address_label`), above roads | `location.accuracy-fill` rgba(26,115,232,0.15) | rgba(102,157,246,0.18) |
| `nav-location-accuracy-line` | `line`, width 1 | Same | `location.accuracy-stroke` | same key, night |
| Location dot | HTML marker (so it has an accessible name, AC 19), 18 px incl. 3 px stroke, shadow elevation 1 | Above all layers | dot #1A73E8, stroke #FFFFFF | dot #669DF6, stroke #E8EAED |
| Stale variant (AC 23) | Same elements | Same | dot #80868B, grey accuracy | dot #9AA0A6, grey accuracy |

A GeoJSON polygon keeps the accuracy radius true to ground distance at every zoom and latitude (AC 19: 30 m ± 10 %). These layers are re-added after every style switch (day/night).

### 7.1 Selected-place pin (NAV-003)
One pin marks the selected search result or the selected point of a coordinate card (NAV-003 AC 21, 22, 25). At most one pin exists.

| Element | Type | Placement | Day | Night |
|---|---|---|---|---|
| Pin body | HTML marker (MapLibre `Marker`, custom element, so it has an accessible name), 28×40 px teardrop (`size.pin-width` × `size.pin-height`), anchor `bottom`: the tip is on the point (±2 px) | Above all map layers and above the location dot; below the UI overlay (it never covers the attribution or a control) | fill `pin.fill` #C5221F | #F28B82 |
| Pin outline | 2 px stroke on the body | Same | `pin.stroke` #FFFFFF | #10151C (a white ring would glare at night) |
| Pin head dot | Circle, radius 4.5 px, centred in the head | Same | `pin.center` #FFFFFF | #10151C |
| Shadow | `drop-shadow(0 1px 2px rgba(0,0,0,.3))` | Same | same | same |

- Contrast (tokens `contrastPairs`, checker): pin fill ≥ 3:1 on `earth`, on `major` roads and on `water` in both modes; head dot ≥ 3:1 on the fill. Red is used only for this pin, so it never competes with the blue location dot or the NAV-004 route line.
- Accessible name: the result name, or «Сонгосон цэг» for a coordinate card (`role="img"`, not focusable). Screen spec: `screens/NAV-003-search.md` › Components › Pin.
- The pin is not a style layer: it survives `setStyle` without re-adding, but its colours switch with the theme through the UI custom properties (like the location dot).
- No POI highlighting or label changes in the basemap: the selected feature's own label stays as the style draws it (Cyrillic, §4.1).

### 7.2 Route lines and alternatives (NAV-004)
Story NAV-004 AC 16–18, 20, 32. Screen spec: [`screens/NAV-004-route-preview.md`](screens/NAV-004-route-preview.md) › Map.

**Source.** One GeoJSON source `nav-route` with one `LineString` feature per route of the current response (1–3), decoded from the OSRM `geometry` (the request asks for `format: osrm`; polyline6 unless the architect's ADR says otherwise). Properties: `index` (0-based, Valhalla order) and `selected` (boolean). Selecting another route only rewrites the `selected` property (`setData`), so the camera and the request state are untouched (AC 18: 0 requests, no camera move). At the start of every request the source is emptied (AC 32).

**Layers** (bottom → top). All are inserted **below the first symbol layer** (`address_label`), so road names, place labels and POIs stay readable on top of the line (Google Maps order), and **below** the NAV-002 location accuracy layers (§7), so the accuracy circle is not hidden by the line. Everything is inside the map canvas, which is below the UI grid, so a route line can never cover the attribution, the scale bar or a control (AC 16).

| Layer id | Type | Filter | Colour token | Width (px, linear by zoom z5 / z10 / z14 / z18) | Notes |
|---|---|---|---|---|---|
| `nav-route-alt-casing` | `line` | `selected == false` | `route.alternative-casing` | 4 / 6 / 8 / 11 | round cap and join |
| `nav-route-alt` | `line` | `selected == false` | `route.alternative` | 2 / 4 / 6 / 9 | |
| `nav-route-sel-casing` | `line` | `selected == true` | `route.selected-casing` | 8 / 10 / 12 / 16 | drawn above every alternative (AC 16) |
| `nav-route-sel` | `line` | `selected == true` | `route.selected` | 4 / 6 / 8 / 12 | |
| `nav-route-hit` | `line` | `selected == false` | any, `line-opacity: 0` | alternative casing width + 20 (24 / 26 / 28 / 31) | invisible hit area: ≥ 10 px on each side of an alternative line (AC 18). Only this layer is queried for clicks and hover (`cursor: pointer`) |
| `nav-route-step` | `circle` | one `Point` in source `nav-route-step` | fill `route.step-fill`, stroke `route.step-stroke` 3 px | radius 6 | manoeuvre point after a turn-list row is activated (AC 30), see §7.3 |

- **Width rule (AC 16):** at every zoom stop the alternative is **≥ 2 px narrower** than the selected line, both in fill (2 px less) and in total width with casing (4–5 px less).
- **Colour rule (AC 16):** the selected fill is ≥ 3:1 against `earth`, `major` and `minor_a` in both modes (checker, `tokens.json › contrastPairs`). Where the fill alone is lower (night trunk and motorway fills, day motorway), the casing is ≥ 3:1 against that road fill, so the line edge stays visible. Alternatives use a different token (`route.alternative`), are muted blue-grey, and differ from the selected fill by ≥ 2:1.
- **Colour values** (quoted from `tokens.json`; checked by `check-contrast.mjs`):

| Key | Day | Night |
|---|---|---|
| `route.selected` | #1967D2 | #8AB4F8 |
| `route.selected-casing` | #0B3D91 | #10151C |
| `route.alternative` | #8FA7CC | #5A7396 |
| `route.alternative-casing` | #5F7CAB | #10151C |
| `route.step-fill` | #FFFFFF | #10151C |
| `route.step-stroke` | #0B3D91 | #8AB4F8 |

- **No labels on the route** in NAV-004 (no duration callouts on the lines, no road shields added). Route options in the panel carry a colour swatch that matches their line (screen spec › Route options).
- **Unpaved segments** are not styled differently (story Out of scope; the tiles have no `surface`, §3.1).
- **Day / night (AC 20):** `setStyle` drops our layers. After every style switch, re-add the `nav-route` and `nav-route-step` sources with their **current data** and the layers above with the new mode's colours, in the same frame as the §7 location layers. The selection is the `selected` property, so it survives; 0 route requests.
- **Blue next to blue:** the selected line and the NAV-002 location dot (#1A73E8 / #669DF6) are both blue, as in Google Maps. The dot keeps its 3 px light stroke and elevation-1 shadow, and the selected line's dark casing separates the two; the dot is an HTML marker, so it is always drawn on top of the line.

### 7.3 Route markers (NAV-004)
Story NAV-004 AC 3, 7, 9, 30. At most **one origin marker and one destination marker** exist while the route panel is open (AC 9).

| Marker | Type | Look (day / night tokens) | Accessible name |
|---|---|---|---|
| **Origin = «Миний байршил»** | The NAV-002 location dot (§7). **No second marker** (AC 3, 9) | Unchanged, including its stale grey variant (NAV-002 AC 23) | NAV-002 name «Миний байршил» (equals the field text) |
| **Origin = any other point** (search result, «Сонгосон цэг») | HTML marker (MapLibre `Marker`, custom element), circle `size.origin-marker` 18 px incl. a 4 px ring, anchor `center`, shadow elevation 1 | fill `route.origin-fill`, ring `route.origin-stroke` | `role="img"`, `aria-label` = origin field text (AC 9). Test id `route-origin-marker` |
| **Destination** | **The NAV-003 pin (§7.1), moved to the destination.** The one-pin rule of NAV-003 stays: the pin *is* the destination marker while the panel is open | §7.1 | `aria-label` = destination field text (AC 9). Test id stays `place-pin` |
| **Candidate point** (coordinate card opened during the preview, AC 7) | HTML marker, the §7.1 pin shape, 28×40 px, anchor `bottom` | **Outline variant:** body fill `ui.surface`, 2 px stroke `pin.fill`, head dot `pin.fill`. It reads as "not yet chosen", next to the filled destination pin | «Сонгосон цэг». Removed when that card closes (either button, «Хаах», Esc). Test id `route-candidate-pin` |
| **Manoeuvre point** | Style layer `nav-route-step` (§7.2), not an HTML marker, so it never counts as a marker | radius 6, fill `route.step-fill`, 3 px ring `route.step-stroke` | none (decorative: the focused turn-list row carries the name). Shown at `maneuver.location` of the last activated turn-list row (AC 30); removed when the route changes or the panel closes |

Origin marker colour values (checked):

| Key | Day | Night |
|---|---|---|
| `route.origin-fill` | #FFFFFF | #10151C |
| `route.origin-stroke` | #1F1F1F | #E3E6EA |

- Marker stacking (all HTML markers, above every style layer and below the UI grid): origin marker < destination pin < candidate pin < location dot. The location dot stays on top because it is the live position.
- When the panel closes (AC 41), the origin marker, the candidate pin, the manoeuvre point and the route lines are removed. The pin goes back to what the NAV-003 card shows: the point of the card that opened the panel, or no pin if that card no longer exists.
- Contrast (checker): origin ring ≥ 3:1 on `earth`, `major` and the selected route (day); at night the dark centre is ≥ 3:1 on the selected route. Candidate pin: the `pin.fill` stroke and head dot on its `ui.surface` body are ≥ 3:1 (pair `pin.fill` / `ui.surface` in `contrastPairs`: 5.8:1 day, 6.3:1 night).

### 7.4 Active navigation on Android (NAV-005)
Story NAV-005 AC 1, 2, 23, 51, 58, 59. Rules for the camera, banner and states: [`navigation-ux.md`](navigation-ux.md); screen layout: [`screens/NAV-005-android-navigation.md`](screens/NAV-005-android-navigation.md).

**Native style (AC 1).** The Android map uses the **same** style as the web: the `Day` / `Night` flavors from `tokens.json`, the §3.2 trunk and §3.3 low-zoom post-processing, the §4.1 label expression `["coalesce", ["get", "name:mn"], ["get", "name"], ["get", "name:en"]]` on every name layer (unit-tested on Android too), the §4.1 label sizes, the §5 fontstacks and the §6 sprites. [ADR-0009](../architecture/adr/0009-android-guidance-client.md) §6: the style JSON for both flavors is **generated by the web `buildStyle`** into the APK's assets (committed, with a staleness check), and the glyphs and sprites are the same vendored files, copied into the APK at build time; **no third-party CDN** (AC 65) and no hand-written second style. MapLibre Native's attribution button and logo are off; the app draws the credit strip (screen spec › R5). Source: `pmtiles://<gateway>/tiles/basemap.pmtiles`, max zoom from the archive header (§1).

**Route line during guidance.** One GeoJSON source `nav-route` with the route being navigated (our own layers; Ferrostar's map UI is not used, ADR-0009 §1):

| Layer id | Type | Colour | Width (z12 / z15 / z18) | When |
|---|---|---|---|---|
| `nav-route-sel-casing` | `line`, round cap and join | `route.selected-casing` | 10 / 14 / 18 | on route |
| `nav-route-sel` | `line` | `route.selected` | 6 / 9 / 13 | on route |
| `nav-route-old-casing` | `line` | `route.alternative-casing` | 6 / 9 / 12 | off-route episode: the old route until a new one is active or the user is back on it (AC 42–46) |
| `nav-route-old` | `line` | `route.alternative` | 4 / 6 / 9 | same |

- Inserted below the first symbol layer (as §7.2), so street names stay readable over the line.
- Guidance widths are ~2 px wider than the §7.2 preview widths at the same zoom: the camera is tilted (45°) and the phone is 50–70 cm away in a mount.
- **Not in this slice:** alternatives, manoeuvre arrows on the line, travelled-route trimming or greying, traffic colours.
- **Destination:** the NAV-003 pin (§7.1) at the destination point, content description = the destination text. No origin marker during guidance.

**Puck (navigation chevron).** Drawn above every style layer and below the UI (MapLibre location component with a custom bitmap, or a symbol layer; mobile's choice). 40 dp (`size.nav-puck`) including a 3 dp ring (`nav.puck-stroke`) and a 1 dp outer hairline (`nav.puck-outline`), soft shadow (elevation 1). The chevron points along the course; in heading-up it points to the top of the screen. **No accuracy circle during guidance** (clutter in a tilted view; the §7 circle stays on S1). Stale variant while GPS is lost (AC 51): `nav.puck-stale-*`, same shape, at the last position. Off-route: the puck shows the raw position (AC 23).

| Key | Day | Night |
|---|---|---|
| `nav.puck-fill` | #1A73E8 | #E8EAED |
| `nav.puck-stroke` | #FFFFFF | #10151C |
| `nav.puck-outline` | #0B3D91 | #10151C |
| `nav.puck-stale-fill` | #80868B | #9AA0A6 |
| `nav.puck-stale-stroke` | #FFFFFF | #10151C |

- Night inverts the puck (light chevron, dark ring) because the night route line is light blue (#8AB4F8): a blue puck would disappear on it. The chevron is the only bright element on the night map, 40 dp, so it does not glare.
- Contrast (checker): ring ≥ 3:1 on the selected route in both modes; fill ≥ 3:1 on its ring and on `earth`; hairline ≥ 3:1 on the dimmed old route; stale fill ≥ 3:1 on `earth`.

**Theme switch during guidance (AC 59):** re-add `nav-route*` with the current data and the new mode's colours in the same frame as the style switch; the puck bitmap switches with it; 0 route requests.

**Camera** (pitch 45° heading-up / 0° north-up, zoom by speed, puck at 70 % of the uncovered map height, 1 s follow): `navigation-ux.md` §8. Above the archive max zoom (14) MapLibre over-zooms, so guidance zooms 15–17.5 show roads and labels (§1).

### 7.5 Web demo mode (NAV-017)
Story NAV-017 AC 9, 13, 14, 22, 41, 44. Screen spec: [`screens/NAV-017-web-demo-mode.md`](screens/NAV-017-web-demo-mode.md); replay rules: [`navigation-ux.md` §11](navigation-ux.md). Demo-mode build only; the public and normal builds draw none of this (story AC 4). **No new colours:** every value below is an existing `route.*`, `pin.*` or `nav.*` token.

**Picker (D1, AC 9).** The selected entry's recorded route is drawn like a NAV-004 route with **one** feature: source `nav-route` with `selected: true`, layers `nav-route-sel-casing` and `nav-route-sel` (§7.2 widths and tokens). No alternative layers and no `nav-route-hit` layer (there is nothing to click). Markers: the §7.3 **origin marker** (18 px circle, `route.origin-*`) at the route start and the §7.1 **pin** at the route end, accessible names = the manifest names (or «Сонгосон цэг»). Selecting another entry replaces the source data; the selection never leaves two routes on the map.

**Replay (D2/D3).** At «Эхлэх» the origin marker is removed (as NAV-005: no origin marker during guidance); the destination pin stays. The route line switches to the §7.4 guidance widths with the selected tokens (CSS px, linear by zoom):

| Layer id | Type | Colour token | Width z12 / z15 / z18 |
|---|---|---|---|
| `nav-route-sel-casing` | `line`, round cap and join | `route.selected-casing` | 10 / 14 / 18 |
| `nav-route-sel` | `line` | `route.selected` | 6 / 9 / 13 |

- Inserted below the first symbol layer (as §7.2), so street names stay readable over the line. No old-route layers (no reroute), no travelled-route trimming, no manoeuvre arrows (as §7.4).
- **Puck:** the §7.4 chevron (40 px including the 3 px `nav.puck-stroke` ring and the 1 px `nav.puck-outline` hairline, `nav.puck-fill`, elevation 1), drawn as a MapLibre HTML `Marker` (decorative: `aria-hidden="true"`, not focusable) with `rotationAlignment: "map"`, `pitchAlignment: "map"` and rotation = the course (navigation-ux §11.2), so it points up while the camera follows heading-up and turns with the map after a user rotation. Night inverts it as §7.4. **No stale variant** (no GPS loss in a replay), **no accuracy circle**, and the NAV-002 location dot and its accuracy layers are never added in the demo-mode build.
- **Glide:** the marker position animates along the route line between snapped fixes (navigation-ux §11.2), with `requestAnimationFrame`; reduced motion = jump per fix.
- **Theme switch (AC 41):** re-add `nav-route` with its current data and the new mode's colours in the same frame as the style switch (as §7.2); the puck and pin colours switch through the UI custom properties. 0 requests except tiles.
- **Attribution (AC 44):** the recorded routes are derived from OSM (ODbL), so the R5 strip «© OpenStreetMap contributors» is shown wherever the route is drawn: every demo state (screen spec Layout rule 1).

### 7.6 Android route preview with alternatives (NAV-011)
Story NAV-011 AC 15–17, 19–21. Screen spec: [`screens/NAV-011-android-route-preview-search-parity.md`](screens/NAV-011-android-route-preview-search-parity.md) › Map. Replaces the single preview line of NAV-005 on Android; guidance (§7.4) is unchanged.

**Source and layers.** The same as §7.2: one GeoJSON source `nav-route` with one feature per route (1–3, Valhalla order), properties `index` and `selected`; layers `nav-route-alt-casing`, `nav-route-alt`, `nav-route-sel-casing`, `nav-route-sel`, below the first symbol layer, with the §7.2 colour tokens (no new colours). Widths are the §7.2 values read as **dp** (MapLibre Native line widths are density-independent): the alternative is ≥ 2 dp narrower than the selected line at every zoom stop, in fill and in total width (AC 15). Selecting a route only rewrites `selected` (`setGeoJson`), so 0 requests and no camera move (AC 17). No `nav-route-step` layer in NAV-011 (no turn list); NAV-018 adds it with the turn list (§7.7).

**Hit test (AC 17).** No invisible hit layer. A tap (not the end of a pan, not a long-press) queries `queryRenderedFeatures` in a **48 × 48 dp box centred on the tap** against `nav-route-alt` and `nav-route-sel`, so the target reaches ≥ 24 dp on each side of a line centre (gloves in winter). Result:
- any **unselected** route in the box → select it; with two unselected routes in the box, the one whose geometry is nearest to the tap point;
- only the selected route in the box, or nothing → no change (the tap falls through to the map: no card, no camera move).
That makes the overlap rule of AC 17 ("the line that is **not** selected is chosen") a property of the query. Long-press keeps its NAV-005 meaning (coordinate card) even on a line.

**Markers.** As NAV-005 S3: origin = the location dot («Миний байршил», no second marker); destination = the §7.1 pin. With NAV-018 the start can be another point: §7.7. Contrast of the selected line against `earth`, `major`, `minor_a` and the casing rule: §7.2 (already in `contrastPairs`, AC 15).

**«Эхлэх» (AC 19).** The selected route's geometry becomes the §7.4 guidance source; the other features are removed from the map within 1 s; reroutes draw one route.

**Theme, language, rotation (AC 20).** Re-add the source with its current data, including `selected`, and the layers in the new flavor's colours in the same frame as the style switch (as §7.2); 0 route requests.

### 7.7 Android start point, candidate pin and manoeuvre point (NAV-018)
Story NAV-018 AC 6, 8, 22. Screen spec: [`screens/NAV-018-android-origin-turn-list.md`](screens/NAV-018-android-origin-turn-list.md) › Map. It brings the §7.3 web markers to the Android preview (§7.6), in dp. **No new colours or sizes:** every value is an existing `route.*`, `pin.*`, `location.*` token or `size.*` dimension, already in `contrastPairs`.

| Marker | When | Look (day / night tokens) | Content description (TalkBack) |
|---|---|---|---|
| **Start = «Миний байршил»** (device start) | Start field shows «Миний байршил» | The NAV-005 location marker (§7, S3 dot), **no second marker** (AC 8) | «Эхлэх цэг: Миний байршил» (`route_origin` + ": " + field text) |
| **Start = chosen start** (search result, «Сонгосон цэг», or «Миний байршил» swapped to the destination side) | Start field shows anything else | §7.3 origin marker: circle **18 dp** (`size.origin-marker`) incl. a **4 dp** ring, anchor centre, elevation 1; fill `route.origin-fill`, ring `route.origin-stroke`. The live location dot is still drawn when there is a fix (it is the live position, not a route point) | «Эхлэх цэг: <field text>» |
| **Destination** | Always | §7.1 pin (28×40 dp, anchor bottom) | «Очих газар: <field text>» |
| **Destination = «Миний байршил»** (after a swap) | Destination field shows «Миний байршил» | The §7.1 pin at the fix the start was set from (the point is fixed when set; it is not the live dot) | «Очих газар: Миний байршил» |
| **Candidate point** | Coordinate card open during the preview (AC 6) | §7.3 candidate pin: outline variant (body `ui.surface`, 2 dp `pin.fill` stroke, `pin.fill` head dot) | «Сонгосон цэг». Removed when the card closes |
| **Manoeuvre point** | After a turn-list row tap (AC 22), until the route changes or the preview closes | Style layer `nav-route-step` (§7.2): circle radius **6 dp**, fill `route.step-fill`, 3 dp ring `route.step-stroke`, inserted above `nav-route-sel` and below the first symbol layer | none (decorative; the row carries the name) |

- **At most one start and one destination marker** (AC 8). Stacking (bottom → top): route lines < manoeuvre point < start marker < destination pin < candidate pin < live location dot.
- **Colour is never the only signal:** the start is a ring, the destination a pin, the candidate an outlined pin; the field icons in the sheet repeat the shapes (ring / `my_location` / pin), so map and sheet match (Gestalt similarity).
- **Camera fit** (NAV-011 AC 16) includes both markers. A turn-row tap centres `maneuver.location` in the map band above the collapsed sheet (or beside the side sheet), zoom 17 or the current zoom if higher, 40 dp padding (screen spec › Camera).
- **Theme, language, rotation:** re-add `nav-route-step` with its current data in the same frame as the style switch (as §7.2); marker colours follow the theme; 0 requests.
- **Guidance:** at «Эхлэх» the start marker and the manoeuvre point are removed (as §7.4: no origin marker during guidance). «Эхлэх» is only possible from a device start (NAV-018 AC 15).

## 8. Day / night switching
- NAV-002: manual toggle, day on first visit, choice remembered (AC 26–28; PO decision D12, 2026-09-30).
- The switch calls `map.setStyle(buildStyle(otherTheme, cfg))` with diffing on (ADR-0004 §3): the other style object is generated at runtime from `tokens.json`, not loaded from a static JSON file. The camera must not move (AC 27). Re-add §7 layers (and the §7.2 route source and layers with their current data). The UI chrome switches through CSS custom properties from `tokens.json › color.<mode>.ui` in the same frame.
- **Android (NAV-005, AC 58):** three options «Өдрийн горим», «Шөнийн горим», «Автомат». «Автомат» is the default; the map flavor and the Compose colours switch together within 1 s, with the §7.4 (and §7.6) layers re-added. The choice is remembered.
- **Android «Автомат» since NAV-012** (AC 41–43; story Open question 4 (a), working assumption): day flavor from sunrise to sunset at the current position, night flavor otherwise, computed on the device in UTC with 0 network requests; switch within 60 s after the computed time, at most once per 10 min (`motion.theme-auto-hold`). The Android system dark-theme setting no longer drives «Автомат» (it did under NAV-005, D63). Same flavors, same re-add rules; no new colours. Timing rules: [`navigation-ux.md` §12.7](navigation-ux.md).

## 9. Reserved (later stories)
- Travelled-route trimming and manoeuvre arrows on the line (formerly labelled "NAV-012 polish"; not in the agreed NAV-012 scope, story Open question 7: a separate item through triage), lane and speed-limit display (NAV-013, NAV-014; layout slots in `navigation-ux.md` §2.6–2.7), traffic colours (Phase 3), unpaved-road styling (needs `surface`, §3.1). Tokens are added to `tokens.json` when those stories are designed.

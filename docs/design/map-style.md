# Map style spec (MapLibre)

- **Owner:** ux-designer
- **Stories:** NAV-002 (AC 5–10, 15, 26–29, 35, 46), NAV-003 (§7.1 selected-place pin; AC 21, 23, 25). Later stories add sections (route line: NAV-004, active navigation camera: NAV-005, traffic: Phase 3).
- **Colour values:** `docs/design/tokens.json` is the single source of truth. The tables below quote it for readability. `node docs/design/prototypes/check-contrast.mjs` fails if a table value here drifts from `tokens.json`, or if a listed text/background pair drops below WCAG AA.
- **Status:** v0.3, 2026-09-30 (§1 zoom range: D1 max zoom 14 read from the archive header; §7.1 NAV-003 pin). v0.2, 2026-09-30 (§1 post-processing moved to runtime per ADR-0004 §3; PO decisions D11–D13, D16 recorded). v0.1, 2026-09-29. Colours are a first proposal. The PO judges them on real tiles in the NAV-002 demo (story goal: "is the open basemap good enough for Mongolian users").
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

## 8. Day / night switching
- NAV-002: manual toggle, day on first visit, choice remembered (AC 26–28; PO decision D12, 2026-09-30).
- The switch calls `map.setStyle(buildStyle(otherTheme, cfg))` with diffing on (ADR-0004 §3): the other style object is generated at runtime from `tokens.json`, not loaded from a static JSON file. The camera must not move (AC 27). Re-add §7 layers. The UI chrome switches through CSS custom properties from `tokens.json › color.<mode>.ui` in the same frame.
- Automatic switching by sunrise/sunset (design principle) is specified with mobile night mode in Phase 1, not here.

## 9. Reserved (later stories)
- Route line and alternatives (NAV-004), active-navigation camera and puck (NAV-005), traffic colours (Phase 3), unpaved-road styling (needs `surface`, §3.1). Tokens are added to `tokens.json` when those stories are designed.

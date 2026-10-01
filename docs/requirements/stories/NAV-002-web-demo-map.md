---
id: NAV-002
title: Web demo map (MapLibre GL JS + gateway PMTiles, Mongolian labels, my location, day/night, mn/en UI, OSM attribution)
phase: 0
priority: must
size: M
needs_design: true
needs_backend: false
needs_mobile: true
status: ready
---

# NAV-002: Web demo map

## Story
As a **UB commuter by car**, I want **to open a web map of Ulaanbaatar and Mongolia with Mongolian street and place names, move it freely, see where I am, and switch between a day and a night look**, so that **I can orient myself on real OSM data in my own language, and the team and PO can judge whether the open basemap is good enough for Mongolian users before the mobile apps are built**.

Secondary personas:
- **Tourist (English UI)**: switches the interface to English. Map labels stay in the local script in NAV-002 (PO decision D11, 2026-09-30, see Open question 1).
- **Pedestrian**: uses "my location" and zooms into street level (z16 to z18) to read small streets and footways.
- **Taxi / delivery driver**: uses the night style during long winter evenings (UB sunset is about 17:00 in December).
- **Intercity / countryside driver**: pans and zooms across the whole Mongolia extract, including areas with few features. The map must not show an error just because an area is empty.

## Context
- Research `docs/osm-navigation-research.md` §7 Phase 0 PoC: "web demo (MapLibre GL JS)". §2 row 1: smooth vector map, zoom/rotate, night mode. §5 (tiles): custom MapLibre style, day/night, Mongolian labels. Search and routing in the same PoC are NAV-003 and NAV-004. They are **not** part of this story.
- Stack (ADR-0001): MapLibre GL JS with Protomaps PMTiles. Web client in `web/`, plain TypeScript + Vite (mobile-engineer rules).
- Tiles come from the NAV-001 gateway: `GET /tiles/basemap.pmtiles` (operation `getBasemapPmtiles` in `docs/architecture/api/openapi.yaml`), read with the PMTiles protocol over HTTP Range. **No API contract change is needed.**
- **ADR-0002: `name:mn` is not in the Protomaps tiles.** The label rule `name:mn` → `name` → `name:en` (CLAUDE.md rule 7, glossary C8) is still implemented in full, so it starts working when upstream adds `mn`. In practice labels start at `name`, which is Cyrillic Mongolian for most of Mongolia.
- ADR-0002 Consequences: the PMTiles metadata attribution is only `© OpenStreetMap`. The client renders the full «© OpenStreetMap contributors» from its resource files. The landcover layer (z0 to z7) is CC BY 4.0, so the ESA WorldCover credit is also required when that layer is shown.
- **Decisions already made (not open):**
  - The web demo is **local dev only**. Hosting is a separate backlog item. *Update 2026-09-30 (PO decision D44):* the static build of the NAV-002/NAV-003 web demo is now **also published, publicly**, on the PO's existing **Hostinger web hosting plan** (shared web hosting, **not** a VPS). It offers map viewing. Search shows the unavailable state and routing is unavailable until a VPS backend exists (NAV-008, VPS KVM 4, D25). The site's hostname is not recorded in the repo. Checks: section M (AC 51–54).
  - Mongolian is the default UI language, with English second (glossary language policy).
  - NAV-002 is **frontend-only**. It adds no backend endpoint and makes no change under `backend/`, `infra/` or `openapi.yaml`. The map style, glyphs (fonts including Cyrillic) and sprites are **bundled as static assets inside `web/`**, for example from the Protomaps basemaps assets, with licences recorded. If the architect decides a backend endpoint is really needed, it is recorded as a follow-up story and not built here.
  - The gateway is a **shared, read-only service** for this story. Tests never restart it, and they simulate failures on the client side (see "Test approach").
- **PO decisions of 2026-09-30** (`docs/requirements/decisions.md`, the answers to Open questions 1–6):
  - D11: the English UI keeps the Cyrillic map labels (`name:mn` → `name` → `name:en` in both UI languages).
  - D12: day style by default, manual toggle, choice remembered.
  - D13: the opening view is Ulaanbaatar, centred on Sükhbaatar Square (P1).
  - D14: supported browsers are current desktop Chrome, Edge and Firefox, plus Android Chrome. Safari and iOS come later. Automated tests run on Chromium only.
  - D15: the page title is «Газрын зураг» ("Map") until a product name exists.
  - D16: the PO judges low-zoom road widths in the demo, and they are widened later if needed. This is not an acceptance gate.
- **Reference points:** P1 to P6, X1 and X2 are the ones in NAV-001 (`NAV-001-backend-stack-docker-compose.md`, "Reference test locations"). P1 = Sükhbaatar Square (47.9189, 106.9176). X2 = Beijing (39.9042, 116.4074, outside coverage).
- **Reference environment** for timings: the NAV-001 reference machine (4 CPU, 15 GB RAM), NAV-001 stack healthy and warm (`GET /health` = 200), desktop Chromium from `/opt/pw-browsers`, viewport 1366×768, web app served by the Vite dev server at `http://localhost:5173`.
- **UX inputs this story depends on** (owner: ux-designer): screen spec `docs/design/screens/NAV-002-web-map.md`, map style spec `docs/design/map-style.md` (day and night), design tokens `docs/design/tokens.json` (light and night).

### User-facing strings
All strings come from `docs/requirements/glossary.md`. The terms NAV-002 needed that were not in the glossary when this story was written are listed below as G1–G7. **They were added to the glossary on 2026-09-29** (section 7 "Errors, permissions and status messages" for G1–G6 with status `needs native review`, section 8 for G7 with status `n/a (not translated)`). Implementation uses **exactly** these strings. Mobile and UX do not invent their own.

**Existing glossary terms used**

| Use in NAV-002 | Glossary row | `mn` | `en` |
|---|---|---|---|
| Map canvas accessible name, page title (until a product name exists) | Map | «Газрын зураг» | "Map" |
| "My location" button (tooltip and accessible name) | Recenter | «Байршил руу буцах» | "Recenter" |
| Location marker accessible name | My location | «Миний байршил» | "My location" |
| Zoom buttons | Zoom in / zoom out | «Томруулах» / «Жижигрүүлэх» | "Zoom in" / "Zoom out" |
| Reset-bearing button | North up (Compass) | «Хойд зүг дээшээ» | "North up" |
| Theme toggle | Night mode | «өдрийн горим» / «шөнийн горим» | "Day mode" / "Night mode" |
| Language toggle | Language | «Хэл»: «Монгол» / «English» | "Language": «Монгол» / "English" |
| Offline banner | No connection | «Интернэт холболт алга» | "No internet connection" |
| Retry button | Try again | «Дахин оролдох» | "Try again" |
| Unexpected failure | Generic error | «Алдаа гарлаа» | "Something went wrong" |
| Scale bar units | C3 (banner abbreviations) | «м» / «км» | "m" / "km" |
| Attribution | OSM attribution | «© OpenStreetMap contributors» (not translated) | same |

**Glossary additions G1–G7 (added to `glossary.md` 2026-09-29)**

| # | English term | Proposed `mn` | `en` | Where | Notes | Glossary |
|---|---|---|---|---|---|---|
| G1 | Loading | «Ачаалж байна…» | "Loading…" | Loading state | Generic, reusable by NAV-003/004 | added 2026-09-29, §7 "Loading", `needs native review` |
| G2 | Map could not be loaded | «Газрын зургийг ачаалж чадсангүй» | "The map could not be loaded" | Tiles-unavailable state | Uses «газрын зураг» (Map row) | added 2026-09-29, §7 "Map could not be loaded", `needs native review` |
| G3 | Location permission denied | «Байршлын зөвшөөрөл олгоогүй байна» | "Location permission is turned off" | My location, denied | Pairs with the existing "Location permission" row | added 2026-09-29, §7 "Location permission denied", `needs native review` |
| G4 | Allow location in browser settings | «Хөтчийн тохиргоонд байршлын зөвшөөрлийг асаана уу» | "Allow location access in your browser settings" | My location, denied (hint) | C1 polite imperative. «хөтөч» here means web browser. It is unrelated to the rejected «дуут хөтөч» (voice guidance). The panel should confirm | added 2026-09-29, §7 "Allow location in browser settings", `needs native review` |
| G5 | Location could not be determined | «Байршил тодорхойлж чадсангүй» | "Your location could not be determined" | My location: timeout, unavailable, unsupported, lost after a fix | Deliberately not «GPS дохио тасарлаа», because browser location is often Wi-Fi/IP-based, not GPS | added 2026-09-29, §7 "Location could not be determined", `needs native review` |
| G6 | Close (dismiss) | «Хаах» | "Close" | Dismiss button on messages | Button uses the -х form (C1) | added 2026-09-29, §7 "Close (dismiss)", `needs native review` |
| G7 | ESA WorldCover credit | «© ESA WorldCover project / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium» | same | Attribution, landcover zooms | Licence credit (ADR-0002) | added 2026-09-29, §8 "ESA WorldCover credit", `n/a (not translated)` |

## Acceptance criteria

### A. Start-up and configuration
1. **Given** a clean checkout and Node LTS installed, **When** the developer follows `web/README.md` (install, then the dev command), **Then** the app is served at `http://localhost:5173` and the README lists: the install, dev, build, typecheck and test commands; the gateway base URL setting and its default; and the note that the gateway's `CORS_ALLOWED_ORIGINS` must allow the web origin (NAV-001 default `*`).
2. **Given** no gateway setting is provided, **When** the app starts, **Then** it reads tiles from `pmtiles://http://localhost:8080/tiles/basemap.pmtiles`.
3. **Given** the gateway base URL is set to another value in `web/.env` (the key is documented in `web/.env.example` with a one-line comment), for example `http://localhost:8081` or `http://localhost:8081/` (trailing slash), **When** the app starts, **Then** every tile request goes to `http://localhost:8081/tiles/basemap.pmtiles` (no double slash), and no request goes to port 8080.
4. **Given** the `web/` project, **When** the build and typecheck commands from the README run, **Then** both exit 0.

### B. Basemap and labels
5. **Given** the stack is healthy, **When** the page is opened for the first time, **Then** the map shows centre P1 (±100 m; Sükhbaatar Square, PO decision D13), zoom 12 (±0.1), bearing 0°, and it reaches MapLibre `idle` with visible roads and labels within **5 s** of navigation start on the reference environment.
6. **Given** the map at z14 centred on P1 in the default (Mongolian) UI, **When** rendered label features are queried (for example `queryRenderedFeatures` on symbol layers), **Then** at least **10** labels are rendered, at least one contains a Mongolian-specific letter (`ө`, `ү`, `Ө` or `Ү`), and every glyph request for the Cyrillic range (`1024-1279`) of each font stack in use returns **200** from a bundled asset (so the letters are drawn, not blank boxes).
7. **Given** the style used for the day **and** night modes, **When** every symbol layer that shows a feature name is inspected, **Then** its `text-field` resolves names in the order `name:mn` → `name` → `name:en` (for example `["coalesce", ["get","name:mn"], ["get","name"], ["get","name:en"]]`). No layer uses `name:ru` or any other `name:*` key.
8. **Given** three test features injected into a symbol layer that uses the label expression (QA test hook or fixture page), with properties (a) `name:mn`=«Тест А», `name`=«Тест Б», `name:en`="Test C"; (b) only `name`=«Тест Б» and `name:en`="Test C"; (c) only `name:en`="Test C", **When** rendered, **Then** the labels read (a) «Тест А», (b) «Тест Б», (c) "Test C". A feature with none of the three keys renders **no** label and causes no error.
9. **Given** the map is at P1, **When** the user zooms to z18, **Then** roads and labels are still shown (over-zoomed from the archive's max zoom, which is 14 or higher), and there is no blank map and no error state.
10. **Given** the user pans to X2 (Beijing, outside the extract) or to an empty countryside area inside Mongolia, **When** the map is idle, **Then** only whatever low-zoom data exists is shown, and **no** error, tiles-unavailable or offline message appears.

### C. Map interaction (pan, zoom, rotate)
11. **Given** the map is idle, **When** the user drags with the mouse, uses the arrow keys with the map focused, or drags with one finger on a touch device, **Then** the map pans in the drag direction.
12. **Given** the map is idle, **When** the user uses the scroll wheel, double-click, pinch, the `+`/`-` keys, or the zoom buttons («Томруулах» / «Жижигрүүлэх»), **Then** the zoom changes. Each button press changes the zoom by exactly 1. Zoom is limited to the range **3 to 19**: at zoom 3 «Жижигрүүлэх» is disabled, and at zoom 19 «Томруулах» is disabled.
13. **Given** the map is idle, **When** the user rotates with right-drag, Ctrl+drag, Shift+Left/Right arrow or a two-finger twist, **Then** the bearing changes, and the reset-bearing button («Хойд зүг дээшээ») shows a compass needle that points to north.
14. **Given** the bearing is not 0°, **When** the user activates «Хойд зүг дээшээ», **Then** the bearing returns to 0° (±0.5°) within **1 s**, and the centre and zoom stay the same.
15. **Given** any interaction in AC 11 to 14, **When** it finishes, **Then** labels stay upright and readable, and the attribution (section H) remains visible.

### D. Metric scale bar
16. **Given** the map at any zoom and latitude, **When** it is idle, **Then** a scale bar is visible showing **metric units only**: «м» below 1 km and «км» from 1 km up in the Mongolian UI, "m"/"km" in the English UI. There is no imperial option.
17. **Given** the map is centred on P1 at z12, z15 and z18, **When** the scale bar's pixel length and label are compared with the true ground distance at the map centre (from the map's metres-per-pixel), **Then** the labelled distance is within **±5 %** of the true distance at each zoom. The bar updates within **500 ms** after a zoom ends.

### E. My location
18. **Given** the page loads, **When** no one has pressed the my-location button, **Then** the app makes **no** geolocation API call and the browser shows **no** location permission prompt.
19. **Given** location permission is granted and the (mocked) position is P1 with accuracy 30 m, **When** the user presses «Байршил руу буцах», **Then** within **3 s**: the map centre is within **20 m** of the position; the zoom is **≥ 15** (a higher current zoom is kept); a location marker with accessible name «Миний байршил» is shown; an accuracy circle is drawn with radius 30 m (±10 %); and the button shows its "active / following" state.
20. **Given** AC 19 and the user has not moved the map since, **When** the mocked position moves 200 m, **Then** the marker moves to the new position within **2 s** and the camera follows. **Given** the user has since panned the map, **When** the position moves, **Then** the marker moves but the camera does not, and the button shows "not following". Pressing the button again recentres as in AC 19.
21. **Given** location permission is denied (by the prompt or already blocked), **When** the user presses «Байршил руу буцах», **Then** within **1 s** a message shows G3 «Байршлын зөвшөөрөл олгоогүй байна» and G4 «Хөтчийн тохиргоонд байршлын зөвшөөрлийг асаана уу», with a «Хаах» (G6) button. The my-location button shows a visually distinct "denied" state, and pressing it again shows the message again. The map stays fully usable, and no uncaught error appears in the console.
22. **Given** permission is granted but the browser returns `POSITION_UNAVAILABLE`, or no fix arrives within **10 s** (timeout), **When** the user pressed the button, **Then** a message shows G5 «Байршил тодорхойлж чадсангүй» with «Дахин оролдох». Pressing «Дахин оролдох» starts a new location request.
23. **Given** a location fix was shown, **When** the browser reports a location error afterwards (fixes stop), **Then** within **2 s** the marker stays at the last known position with a "stale" style defined by UX, and G5 is shown. **When** fixes resume, **Then** the marker returns to its normal style and the message hides within **2 s**.
24. **Given** the browser has no Geolocation API, or the page is not in a secure context (for example opened over `http://<LAN-IP>:5173`), **When** the page loads, **Then** the my-location button is shown **disabled**, with G5 as its accessible description, and no exception is thrown.
25. **Given** the mocked position is X2 (outside Mongolia), **When** the user presses the button, **Then** the camera and marker move there as in AC 19, and no error message appears (AC 10 applies to the empty map).

### F. Day and night style
26. **Given** a first visit (no saved preference), **When** the page loads, **Then** the day style is active. *(PO decision D12, 2026-09-30, see Open question 2.)*
27. **Given** the theme toggle («өдрийн горим» / «шөнийн горим»), **When** the user switches mode, **Then** within **2 s** the map style and the UI controls switch to that mode's values in `docs/design/map-style.md` and `docs/design/tokens.json` (QA compares at least the background, water, major-road and label colours with the spec). The camera does not change (centre ±1 px, zoom ±0.01, bearing ±0.5°). The label rule (AC 7), the location marker (if shown) and the attribution stay in place.
28. **Given** the user chose a mode, **When** the page is reloaded, **Then** the same mode is active.
29. **Given** each mode, **When** checked with an automated accessibility checker (for example axe-core), **Then** all UI control text and message text meet WCAG 2.1 AA contrast (**≥ 4.5:1**), including the attribution text on its background.

### G. Language and strings
30. **Given** a first visit, **When** the page loads with the browser language set to `en-US`, **Then** the UI is still Mongolian and `<html lang="mn">` is set (Mongolian first regardless of browser language).
31. **Given** the language toggle («Хэл»: «Монгол» / «English», each shown in its own language), **When** the user switches to English, **Then** within **500 ms** every UI string (buttons, tooltips, accessible names, messages, scale units, page title) is English and `<html lang="en">` is set, with no page reload and no camera change. The map labels do **not** change *(PO decision D11, 2026-09-30)*. The choice survives a reload.
32. **Given** the `web/` sources, **When** the check documented in the README runs (lint rule or scripted scan), **Then** no user-facing text literal is hard-coded outside the `mn` and `en` resource files. The two files have **identical key sets**, and no value is empty.
33. **Given** the `mn` resource file, **When** each value is compared with `docs/requirements/glossary.md` (including rows G1 to G7, added 2026-09-29), **Then** 100 % of values match a glossary term exactly. Any mismatch fails the check.

### H. Attribution and licences
34. **Given** viewport widths **320, 360, 768, 1366 and 1920 px**, both modes, both languages, and every state (map shown, loading, tiles unavailable, offline, location message open), **When** the page is inspected, **Then** «© OpenStreetMap contributors» is fully inside the viewport, not covered by another element, not collapsed behind an info ("i") button, and at least **11 CSS px** in size. It links to `https://www.openstreetmap.org/copyright` and opens in a new tab.
35. **Given** the map is at zoom **≤ 7** (landcover layer shown), **When** inspected at the widths in AC 34, **Then** the ESA WorldCover credit G7 is also visible, with the same visibility rules. UX may decide to show it at all zooms.
36. **Given** `web/`, **When** the third-party notices file named in the README is read, **Then** it lists every bundled or shipped third-party asset (style base, glyph fonts, sprites, MapLibre GL JS, the PMTiles library, any other runtime dependency) with its source URL, version or commit, and licence. Font licences (for example SIL OFL 1.1) are included in full.

### I. States: loading, tiles unavailable, offline
37. **Given** the page is opened, **When** the map is not ready (no `idle` with tiles) within **300 ms**, **Then** a loading indicator with G1 «Ачаалж байна…» is shown and announced to screen readers (`aria-live="polite"`). It disappears within **500 ms** of the first `idle`.
38. **Given** the gateway is unreachable at start-up (gateway base URL set to a closed port such as `http://localhost:59999`, or tile requests aborted by the test), **When** the page loads, **Then** within **10 s** the loading indicator is replaced by the tiles-unavailable state showing G2 «Газрын зургийг ачаалж чадсангүй» and «Дахин оролдох». There is no endless spinner, and the attribution stays visible.
39. **Given** the gateway answers the PMTiles request with **404**, **5xx**, or a body that is not a PMTiles v3 archive (for example an HTML error page), **When** the page loads, **Then** the same tiles-unavailable state as AC 38 appears within **10 s**. This covers the NAV-001 data rebuild window, which takes about 6 minutes.
40. **Given** the tiles-unavailable state, **When** the cause is removed (the test stops aborting requests or the gateway is back) and the user presses «Дахин оролдох», **Then** the map renders within **5 s**, the error state disappears, and the chosen language and mode are kept.
41. **Given** the map is shown and the browser is online, **When** **3 consecutive** tile requests fail (network error, 404 or 5xx), **Then** within **5 s** a non-blocking banner with G2 and «Дахин оролдох» appears. Tiles already drawn stay visible, and the map stays interactive. **When** a later tile request succeeds (after panning or pressing «Дахин оролдох»), **Then** the banner hides within **2 s**.
42. **Given** the PMTiles archive is replaced on the gateway during a session (new `ETag`, for example after a NAV-001 rebuild), **When** the user keeps panning, **Then** the map either keeps rendering, or shows the AC 41 banner, where «Дахин оролдох» recovers it. At no point is the map blank without a message for more than **10 s**. A page reload always loads the new archive.
43. **Given** the map is shown, **When** the browser goes offline (`navigator.onLine` false / `offline` event, for example Playwright `setOffline(true)`), **Then** within **2 s** a banner shows «Интернэт холболт алга». Tiles already drawn stay visible, and pan/zoom within the loaded area still works. The tiles-unavailable banner is **not** shown at the same time (offline takes precedence).
44. **Given** the offline banner is shown, **When** the browser comes back online, **Then** the banner hides within **2 s**, and missing tiles load without a page reload.
45. **Given** more than one state applies, **When** messages are shown, **Then** the precedence is offline > tiles unavailable > loading, and only the highest one is shown. Location messages (section E) are independent and may appear together with them, but they never cover the attribution.

### J. Network hygiene and privacy
46. **Given** a full test session (load, pan across Mongolia, zoom 3 to 19, both modes, both languages, my location), **When** all network requests are logged, **Then** every request goes to either the page origin (`http://localhost:5173`) or the gateway base URL. **Zero** requests go to any other host (no font CDNs, no `protomaps.github.io`, no map-style hosts, no analytics).
47. **Given** a location fix is shown, **When** all requests after it are logged, **Then** none contains the device coordinates in the URL, headers or body. NAV-002 sends location nowhere.

### K. Accessibility and layout
48. **Given** keyboard-only use, **When** the user presses Tab from the page top, **Then** every control (zoom, reset bearing, my location, theme, language, retry, close, attribution link) is reachable in a logical order, shows a visible focus indicator, and has an accessible name in the current UI language.
49. **Given** viewports 320×568, 360×640 and 1920×1080, **When** the page is shown, **Then** no two controls overlap, no control covers the attribution or the scale bar, and every touch target is at least **44×44 CSS px**.

### L. Supported browsers (added 2026-09-30, PO decision D14)
50. **Given** `web/README.md`, **When** it is read, **Then** it has a "Supported browsers" section that names exactly: current desktop Chrome, Edge and Firefox, and Android Chrome. It states that Safari and iOS are **not supported yet**, and that automated tests run on desktop Chromium only. *(Amended 2026-10-01, D72:)* the section may add **one** sentence that points to the demo-mode section of the README, where iPhone Safari is supported for the NAV-017 demo-mode build only and its tests also run on WebKit. The rest of this section stays as written.

### M. Published static demo (added 2026-09-30, PO decision D44)
"Public site" below means the production build of `web/` published on the PO's Hostinger web hosting. Its URL is given to QA by the orchestrator and is **not** written into the repo. AC 1–50 are unchanged; they still run against the Vite dev server.
51. **Given** the production build (the build command in `web/README.md`) is published on the public site, **When** it is opened over `https://` from a fixed-line connection in UB, **Then**:
    - the certificate is publicly trusted and the browser shows no warning, and `http://` answers **301 or 308** to the `https://` URL
    - on desktop Chromium at 1366×768, the opening view (P1, D13) is drawn with tiles (first `idle` with tiles) within **10 s** of navigation start
    - the NAV-002 checks for AC 5, 7, 26, 31, 34, 35, 48 and 49 pass against the public site as they do on the dev server
    - «© OpenStreetMap contributors» is visible on the map screen (CLAUDE.md rule 8, AC 34)
    - `web/README.md` names the build setting(s) used for the static deployment, with the key(s) in `web/.env.example` and no hostname
52. **Given** the public site, **When** a session pans across Mongolia (P1, X1 and one empty steppe point) and zooms from **z3 to z18**, **Then** every basemap read is an HTTP Range request answered **206**, the whole archive is never downloaded in one response (no single tile-archive response > **10 MB**), and every request goes to the page origin or to the one tiles URL configured in the build. **0** requests go to any other host (AC 46 rule, with "page origin" = the public site). Overzoom above the archive's `maxzoom` works as in AC 9.
53. **Given** the public site has no backend (D44), **When** the user types a settled query of **≥ 2** characters, presses «Дахин оролдох», or opens a coordinate card (right-click / long-press), **Then**:
    - within **1 s** the results list shows «Хайлт түр ажиллахгүй байна» with «Дахин оролдох» (NAV-003 AC 33 wording), or the card's nearest-place area shows it (NAV-003 AC 30)
    - there is no endless spinner, and the map, attribution and other controls keep working (NAV-003 AC 39)
    - **0** `search` or `reverse` requests are sent to **any** host, the page origin included, so no query text or coordinates reach the web host's logs. A documented build setting turns search, reverse and routing off for a static deployment
54. **Given** NAV-008 AC 24 has **no** recorded legal-review outcome of go (or go with conditions, with the conditions met), **When** the public site's build configuration and a **5-minute** session log (typing 3 queries, opening 1 coordinate card) are inspected, **Then** search, reverse and routing are turned off as in AC 53, and **0** requests go to the NAV-008 staging backend. Pointing the public site at a backend is a change recorded in this story's Change log, dated **on or after** the AC 24 record (D9; confirmed by the PO as written, D47, 2026-09-30).

## Edge cases
- **GPS or location lost:** AC 23 (stale marker, G5). Desktop browsers often locate by Wi-Fi or IP, so accuracy may be hundreds of metres or more. The accuracy circle shows the real value (AC 19) and is not hidden.
- **Permission denied, or blocked earlier:** AC 21. **Unsupported API or insecure origin (LAN IP over http):** AC 24.
- **No network:** AC 43 to 44. A page opened while already offline cannot load from the Vite dev server at all. That is expected for local dev, and offline start is out of scope.
- **Gateway down or data rebuilding** (NAV-001 rebuild takes about 6 minutes, and the gateway may return 404 or 502): AC 38 to 41. The app never restarts or calls anything but the tiles URL.
- **Archive replaced mid-session (ETag change):** AC 42.
- **CORS rejected** (gateway allowlist does not include the web origin): the browser blocks the tile requests. The app shows the tiles-unavailable state (AC 38), and the README explains the fix (AC 1).
- **Off-route / no search result / Cyrillic–Latin search:** not applicable. There is no routing or search in NAV-002 (NAV-003, NAV-004).
- **Empty map areas** (countryside, outside the extract): AC 10. No error.
- **Missing glyph ranges:** a `name` in a script whose glyph range is not bundled (for example traditional Mongolian script U+1800–U+18AF, or CJK near the border) may render without that label. It must **not** trigger the tiles-unavailable state or break other labels. QA records any 404 glyph ranges seen in the AC 46 session as information.
- **Names in other scripts in `name`:** some POIs (brands, embassies, hotels) carry a Latin or English `name`. Those labels show in Latin, because that is the data (see R2).
- **Winter conditions:** the night style exists for long winter evenings (UB sunset about 17:00 in December) and for night taxi shifts. Winter road status is out of scope.
- **Unpaved roads:** NAV-002 styles roads only by class. Whether paved and unpaved roads can be styled differently depends on the tile schema (R6). It is not required here.
- **Very narrow screens (320 px):** AC 34, 49.
- **Language toggled while a message is open:** the open message switches language as well (covered by AC 31, "every UI string").
- **Public static site (D44):** no backend, so search and reverse show «Хайлт түр ажиллахгүй байна» without sending a request (AC 53). "My location" works there because the site is served over HTTPS (a secure origin, AC 24). A visitor on a weak mobile connection loads tiles by range requests only (AC 52), never the whole archive. The web host being down shows the browser's own error page, because the app cannot load at all; that is accepted for a demo.

## Data dependencies & risks
| # | Risk | Impact on NAV-002 | Mitigation / owner |
|---|---|---|---|
| R1 | **`name:mn` is not in the Protomaps tiles** (ADR-0002) | The first step of the label rule never matches, and labels come from `name` | AC 7 and 8 keep the full rule, so it works as soon as tiles carry `name:mn`. Architect: upstream proposal to add `mn` to `OsmNames.ALLOWED_LANGS` |
| R2 | **`name` is not always Mongolian Cyrillic** (brands, embassies, some POIs in Latin or English; Russian or Chinese near borders) | Some labels are not in Mongolian. The PO may see this as poor quality | Data issue, not a client bug. QA records, as information, the share of rendered labels at z14 around P1 that contain no Cyrillic character, as a baseline for a later mapping programme |
| R3 | **`name:en` coverage is low** in UB | The last fallback is rarely used, and English-UI tourists see Cyrillic labels | Open question 1. Transliterated labels are a later story |
| R4 | **Glyph coverage and size.** The bundled fonts must include Cyrillic (U+0400–U+04FF, including ө/ү). Traditional Mongolian script and CJK are not guaranteed | Missing letters or labels | AC 6, edge case "Missing glyph ranges". Mobile picks fonts, and licences are recorded (AC 36) |
| R5 | **Bundled asset licences** (Protomaps style and sprites, Noto fonts) | A licence breach if a notice is missing | AC 36. Architect review |
| R6 | **Tile schema may not carry `surface`** | Unpaved roads cannot be styled differently (intercity persona) | Out of scope here. Architect/UX to confirm what the Protomaps schema exposes, for a later story |
| R7 | **Archive freshness and replacement.** The NAV-001 rebuild replaces the file in place | ETag mismatch mid-session, and 404/502 during rebuilds | AC 39, 41 and 42 |
| R8 | **Landcover licence (CC BY 4.0)** | Missing credit at z0 to z7 | AC 35 |
| R9 | **Browser geolocation accuracy in Mongolia** (Wi-Fi/IP-based on desktops) | "My location" may be far off on laptops | The accuracy circle shows it honestly (AC 19). Not a client bug |
| R10 | **Shared web hosting may not serve the basemap archive well** (added 2026-09-30, D44). The Mongolia archive at z14 is about 112 MiB (`docs/architecture/system-overview.md`). A shared plan may cap file size or uploads, or may not answer HTTP Range requests with 206 | AC 52 fails, and the public site shows the tiles-unavailable state or downloads far too much | Architect and mobile-engineer confirm Range support and file limits before publishing. If it fails, options (for example a smaller UB-only archive, or waiting for the VPS) go to the PO. The BA does not choose |
| R11 | **The web host's own access logs** (added 2026-09-30, D44) keep visitors' IP addresses, and we cannot configure them the way NAV-008 AC 14 requires. The plan's data-centre location is not recorded | Personal data (IP addresses) of public visitors sits with the web host, possibly abroad (D9) | AC 53 and AC 54 keep query text and coordinates away from the web host and from staging. The PO confirmed this working assumption on 2026-09-30 (D47). PO |
| R12 | **Public visitors see the known data gaps** (added 2026-09-30, D44): Cyrillic-only labels in the English UI (D11, R3), labels in other scripts (R2) | People outside the team judge the product on OSM data, not on the client | Same as NAV-008 R7: tell reviewers what is known to be weak. BA and PO |

## Out of scope
- Search, autocomplete and reverse geocoding («Энд юу байна?») (NAV-003). Routing and route preview (NAV-004). Active navigation (NAV-005).
- Any backend change, new endpoint, or `openapi.yaml` change. Serving the style, glyphs or sprites from the gateway is a possible follow-up, only if the architect asks for it.
- Hosting or deploying the web demo (a separate backlog item). Service worker or offline map caching. *(Changed 2026-09-30, D44: publishing the static build on the PO's Hostinger web hosting is now **in scope**, section M. Still out of scope: serving the demo from the NAV-008 backend host, deployment automation (CI/CD) for the web hosting, and connecting the public site to any backend.)*
- Tilt/pitch, 3D buildings, satellite, traffic and layer menus. Tilt may stay enabled by default but is not tested.
- English or transliterated map labels for the English UI (PO decision D11: a later tourist story may add two-line labels).
- Automatic day/night switching (PO decision D12: revisited with mobile night mode in Phase 1).
- Paved/unpaved road styling (R6).
- Browsers other than desktop Chromium in automated tests. Safari and iOS support (PO decision D14: later). *(2026-10-01, D72: the NAV-017 demo-mode build supports iPhone Safari and runs its own tests on WebKit too. NAV-002 itself is unchanged.)*
- Widening low-zoom roads (PO decision D16: judged in the demo, a later change if needed).

## Test approach (for QA, binding)
- Run the NAV-002 browser tests (Playwright, Chromium from `/opt/pw-browsers`) **only when `http://localhost:8080/health` returns 200**. If it does not, wait and retry. Never restart, stop or rebuild the gateway, and never run docker, docker compose or make commands in `backend/`.
- Simulate failures on the client side only: a closed-port gateway URL (AC 38), Playwright request interception to abort or answer 404/5xx/HTML (AC 39, 41, 42), `context.setOffline` (AC 43 to 44), and Playwright geolocation permissions and mocked positions (AC 18 to 25).
- Tests live under `tests/e2e/nav002/`. NAV-001 tests stay unchanged.

## Open questions
All decided by the PO on 2026-09-30 ("all recommended", `docs/requirements/decisions.md` D11–D16). None of 1–6 remain open. Question 7 was added with D44 and decided on 2026-09-30 (D47).
1. **Map labels in the English UI.** Options: (a) keep the rule-7 order `name:mn` → `name` → `name:en` in both UI languages, so the English UI shows Mongolian Cyrillic labels; (b) the English UI uses `name:en` → `name`; (c) the English UI shows two-line labels (`name` plus `name:en` when present). *Recommendation: (a) for NAV-002, because it matches CLAUDE.md rule 7 and `name:en` coverage is low (R3). Consider (c) in a later tourist story.* **Decided (D11): (a).** AC 7 and AC 31 apply.
2. **Default theme.** Options: (a) day by default, manual toggle, choice remembered; (b) follow the OS `prefers-color-scheme` («автомат» in the glossary); (c) switch by UB sunrise and sunset. *Recommendation: (a) for the demo. Add (b) or (c) with mobile night mode in Phase 1.* **Decided (D12): (a).** AC 26 and AC 28 apply.
3. **Initial view.** Options: (a) UB, centre P1 at z12; (b) whole-Mongolia overview. *Recommendation: (a), because UB comes first.* **Decided (D13): (a), Sükhbaatar Square.** AC 5 applies.
4. **Browser support for the demo.** Options: (a) current desktop Chrome/Edge/Firefox plus Android Chrome, with automated tests on Chromium only; (b) also Safari/iOS. *Recommendation: (a) while the demo is local dev only. Revisit with hosting.* **Decided (D14): (a), Safari/iOS later.** New AC 50 makes the list visible in the README.
5. **Page title and product name.** The product has only a working name. *Recommendation: use «Газрын зураг» / "Map" until the user picks a name.* **Decided (D15):** «Газрын зураг» / "Map" until a product name exists.
6. **Low-zoom road widths** (raised after the UX map-style work; the colour fix is in `docs/design/map-style.md` §3.3). Options: (a) the PO judges the widths in the demo and they are widened later if needed; (b) widen now. **Decided (D16): (a).** No AC. A later widening goes through the UX map-style spec as a change.
7. **Public site and the D9 legal gate** (added 2026-09-30, with D44; decided 2026-09-30, D47). The public site sends no queries or coordinates, but visitors' IP addresses reach the web host's logs (R11). Options: (a) map-only public site now, with search, reverse and routing off until the NAV-008 AC 24 review outcome is go (AC 54, working assumption); (b) connect the public site to staging as soon as it exists and accept the D9 risk. *Recommendation: (a).* Tracked in `decisions.md`, Items to confirm 7. **Decided (PO, 2026-09-30, D47): (a)** ("All as recommended"). AC 53 and AC 54 unchanged.

## Traceability
| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC1–4 | — | `getBasemapPmtiles` | `web/README.md`, `web/.env.example`, `web/src/**` (TBD) | `tests/e2e/nav002/` (TBD) | |
| AC5–10 | `docs/design/screens/NAV-002-web-map.md`, `docs/design/map-style.md` (TBD) | `getBasemapPmtiles` | TBD | TBD | ADR-0002 (`name:mn` absent) |
| AC11–15 | NAV-002 screen spec (TBD) | — | TBD | TBD | |
| AC16–17 | NAV-002 screen spec (TBD) | — | TBD | TBD | |
| AC18–25 | NAV-002 screen spec, location states (TBD) | — | TBD | TBD | |
| AC26–29 | `docs/design/map-style.md`, `docs/design/tokens.json` (TBD) | — | TBD | TBD | |
| AC30–33 | NAV-002 screen spec (TBD) | — | `web/` resource files (TBD) | TBD | Glossary G1–G7 added 2026-09-29 (glossary §7, §8) |
| AC34–36 | NAV-002 screen spec, attribution layout (TBD) | — | TBD | TBD | ADR-0002 Consequences (ESA credit) |
| AC37–45 | NAV-002 screen spec, states (TBD) | `getBasemapPmtiles` (200/206/404/5xx) | TBD | TBD | |
| AC46–47 | — | `getBasemapPmtiles` | TBD | TBD | NFR-P1 |
| AC48–49 | NAV-002 screen spec (TBD) | — | TBD | TBD | |
| AC50 | — | — | `web/README.md` ("Supported browsers" section, mobile-engineer) | README doc check (QA, TBD) | PO decision D14 2026-09-30; one pointer sentence to the NAV-017 demo-mode section allowed (D72, 2026-10-01) |
| AC51–54 | NAV-002 and NAV-003 screen specs (unchanged; NAV-003 unavailable state) | `getBasemapPmtiles` (from the public site or the configured tiles URL); `search`, `reverse`, `postRoute` **not called** | `web/` static-deployment build setting and `web/README.md` (mobile-engineer, TBD); publishing on the web hosting (PO-side operator) | checks against the public site: TLS and redirect, Range/206, request log, unavailable state, 0 backend requests (QA, TBD) | PO decision D44 2026-09-30; D9 / NAV-008 AC 24; R10, R11; Open question 7 (decided, D47: public site map-only until the AC 24 go, AC 54 confirmed) |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-09-29 | — | Created from the backlog draft row (team design §5) and the NAV-002 feature request. 49 AC covering start-up/config, labels, pan/zoom/rotate, scale bar, my location, day/night, mn/en UI, attribution and licences, loading / tiles-unavailable / offline states, network hygiene and accessibility. Proposed glossary additions G1–G7. Five non-blocking open questions. | Refine NAV-002 to `ready` for UX and architect. Frontend-only, and it runs alongside the NAV-001 change request. |
| 2026-09-29 | NAV-002 AC 33 blocker | Rows G1–G7 added to `glossary.md` with the exact strings from this story and `web/src/i18n/mn.json`. G1–G6 are in section 7 with status `needs native review`, and G7 is in section 8 with status `n/a (not translated)`. The G1–G7 table now has a "Glossary" column, the "User-facing strings" intro no longer calls them pending, the AC 33 wording refers to the added rows, and the AC30–33 traceability row is updated. No AC changed in substance. | AC 33 needs every `mn` value to match a glossary term. The web app already uses these strings, so acceptance was waiting only on the glossary rows. |
| 2026-09-30 | PO decisions D11–D16 (`docs/requirements/decisions.md`, PO answer "all recommended", relayed by the orchestrator) | **Context:** new bullet listing D11–D16. **Open questions 1–5** marked decided, each with the BA-recommended option, and **Open question 6** (low-zoom road widths) added and marked decided (D16, no AC). **AC 5** notes D13, **AC 26** now cites D12 instead of "BA recommendation", and **AC 31** cites D11. The numbers in these AC are unchanged. **New AC 50** (section L): `web/README.md` names the supported browsers (D14). It is numbered after AC 49 so existing numbers stay stable. **Out of scope** lines for labels, automatic day/night, browsers and low-zoom widths now cite the decisions. **Traceability** row AC50 added. Secondary persona "tourist" cites D11. | The PO accepted every BA recommendation, so the AC that already followed them stay the same in substance. AC 50 makes the D14 browser decision checkable, because `web/README.md` currently has no browser list. |
| 2026-09-30 | PO decision D44 ("go ahead with recommendations", relayed by the orchestrator): publish the static web demo on the PO's existing Hostinger web hosting (not a VPS), public | **Context:** the "local dev only" bullet gains a D44 update. **New section M, AC 51–54** (numbered after AC 50, existing numbers unchanged): AC 51 HTTPS, redirect, opening view within 10 s and a subset of NAV-002 checks against the public site; AC 52 basemap by HTTP Range (206) only, no request to other hosts; AC 53 search, reverse and routing turned off by a documented build setting, with the NAV-003 unavailable message within 1 s and 0 `search`/`reverse` requests; AC 54 no request to the staging backend until the NAV-008 AC 24 legal-review record exists (D9). **Edge cases:** public static site. **Risks:** new R10 (Range support and file limits on shared hosting), R11 (web host access logs, D9), R12 (data gaps seen by the public). **Out of scope:** publishing the static build is now in scope; the backend host, CI/CD and connecting a backend stay out. **Open questions:** new 7 (public site and D9). **Traceability:** row AC51–54. The public site's hostname is not recorded | The PO wants the demo shareable now, before the VPS backend exists. The new AC make the published site checkable, keep query text and coordinates away from the shared web host and from staging (D9, NAV-003 R10), and surface the Range-support risk before publishing |
| 2026-09-30 | PO answer "All as recommended" in chat, relayed by the orchestrator (`decisions.md` D47; NAV-004-run Q1, Items to confirm 7) | **AC 54:** the reference to Items to confirm 7 now cites D47, which confirms the AC as written. No wording of the condition, the 5-minute session or the 0-request rule changed. **R11:** mitigation notes the PO confirmation. **Open questions:** intro and question 7 marked decided (a), D47. **Traceability:** row AC51–54 cites D47 | The PO confirmed the working assumption: the public site stays map-only, with search, reverse and routing off, until NAV-008 AC 24 records a go. It was already implemented, so no behaviour changes |
| 2026-10-01 | NAV-017 web demo mode (feature request), PO decision D72 ("All as recommended", relayed by the orchestrator) | **AC 50:** one added sentence allows a pointer from "Supported browsers" to the README's demo-mode section, where iPhone Safari is supported for the NAV-017 demo-mode build only. The browser list, "not supported yet" for Safari and iOS, and "automated tests on desktop Chromium only" stay as written for NAV-002. **Out of scope** (browsers line) and **Traceability** (AC50) note D72. No other AC changed | D72 supports iPhone Safari for the demo mode only and keeps D14 for the rest of the web demo. Without the amendment, the NAV-017 README text would conflict with AC 50's "names exactly". The existing AC 50 test in `tests/e2e/nav002/static.test.mjs` keeps passing if the README keeps its current sentences |

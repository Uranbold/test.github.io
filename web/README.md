# Web demo map (NAV-002) with search (NAV-003) and route preview (NAV-004)

MapLibre GL JS page that shows the Mongolia basemap from the NAV-001 gateway (`GET /tiles/basemap.pmtiles`, read with the
PMTiles protocol over HTTP Range), with place search, a place card and a coordinate card (NAV-003). Plain TypeScript +
Vite, no UI framework. Local dev, plus a **map-only static build** for the PO's public web hosting (PO decision D44,
NAV-002 section M): see [Static public demo](#static-public-demo-d44-nav-002-ac-5154).

- Story: `docs/requirements/stories/NAV-002-web-demo-map.md`
- Screen spec, flows, style, tokens: `docs/design/screens/NAV-002-web-map.md`, `docs/design/flows/NAV-002-web-demo-map.md`,
  `docs/design/map-style.md`, `docs/design/tokens.json` (colours are imported from this file at build time, never copied)
- Architecture: `docs/architecture/adr/0004-web-demo-client-style-and-bundled-assets.md`
- NAV-003: story `docs/requirements/stories/NAV-003-search-cyrillic-latin-autocomplete.md`, screen spec and flow
  `docs/design/screens/NAV-003-search.md`, `docs/design/flows/NAV-003-search.md`, ADR-0006 (query assistance and request
  orchestration).
- NAV-004: story `docs/requirements/stories/NAV-004-route-preview-web.md`, screen spec and flow
  `docs/design/screens/NAV-004-route-preview.md`, `docs/design/flows/NAV-004-route-preview.md`, map style §7.2–7.3,
  ADR-0008 (turn text is built on the client from the manoeuvre fields; Valhalla's narrative is never shown).
- API (`docs/architecture/api/openapi.yaml` 0.5.0): `getBasemapPmtiles`, `search` (`GET /v1/search`), `reverse`
  (`GET /v1/reverse`) and `postRoute` (`POST /v1/route`, web route-preview client profile). No other backend call. The Photon types in `src/search/photon.ts` follow the contract schemas and
  `src/search/display.test.ts` checks them against the contract text.

## Requirements

- Node.js LTS **22.12 or newer** (tested with 22.22) and npm.
- The NAV-001 stack running, with `GET http://localhost:8080/health` returning 200.

## Supported browsers

PO decision D14 (NAV-002 AC 50). Supported:

- current desktop **Chrome**
- current desktop **Edge**
- current desktop **Firefox**
- current **Android Chrome**

**Safari and iOS are not supported yet** (planned later). The automated tests (`tests/e2e/nav002/`, Playwright) run
on **desktop Chromium only**; the other supported browsers are checked by hand.

## Commands

Run everything from `web/`.

| What | Command |
|---|---|
| Install | `npm ci` |
| Dev server (http://localhost:5173) | `npm run dev` |
| Production build (to `dist/`) | `npm run build` |
| Static public demo build (to `dist-static-demo/`, map only, D44) | `npm run build:static-demo` |
| Serve the build (http://localhost:4173) | `npm run preview` |
| Typecheck | `npm run typecheck` |
| Unit tests (Vitest) | `npm test` |
| Lint (ESLint + i18n scan) | `npm run lint` |
| Hard-coded text scan only (AC 32) | `npm run check:i18n` |
| Glossary match of the `mn` strings (AC 33) | `npm run check:glossary` (G1–G7 are glossary rows now; `-- --mn <file>` checks another resource file) |
| Re-fetch bundled fonts and sprites | `npm run vendor-assets` (then `node scripts/gen-third-party-notices.mjs`) |

**Glossary check (AC 33).** Every `mn` value must equal an approved term in `docs/requirements/glossary.md`: the
"Approved Mongolian" column of the term tables, the «quoted» usage forms in their "Definition" column, or the «quoted»
examples in the "Rule" column of the conventions table (C1–C8). Notes, Status, the change log and any sentence about
Avoid / Rejected / Alternative / previous-proposal wording never count, so a banned term such as «навигаци» fails the
check although the glossary quotes it. The NAV-002 rows G1–G7 are in the glossary (sections 7 and 8), so there is no
story-only fallback any more. `scripts/check-glossary.test.mjs` (part of `npm test`) covers the banned-term cases.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `VITE_GATEWAY_BASE_URL` | `http://localhost:8080` (static demo: the page origin) | Gateway base URL. Tiles are read from `<base>/tiles/basemap.pmtiles`; trailing slashes are ignored. **`same-origin`** (or `/`) means the page's own origin at runtime (`window.location.origin`), so one build works on any host without a rebuild. A value starting with one `/` (e.g. `/gw`) means the page origin plus that path. An absolute URL is used as is. |
| `VITE_STATIC_DEMO` | `false` | **Static public demo build setting** (NAV-002 AC 51, 53, 54). `true` (or `1`) turns **search, reverse and routing off**: 0 requests to `/v1/search`, `/v1/reverse` or `/v1/route` on any host, and the search box and the coordinate card show «Хайлт түр ажиллахгүй байна» with «Дахин оролдох» at once (250 ms after the last keystroke). The map, day/night, my location and attribution work as usual. `false`, `0` or unset = normal build. Any other value fails the build (and would count as `true` at runtime). In code: `cfg.staticDemo` and `cfg.features` (`search`, `reverse`, `routing`) in `src/config.ts`; routing (NAV-004) uses `cfg.features.routing` (no second switch). |

Copy `.env.example` to `.env` and edit it, or pass the variable on the command line
(`VITE_GATEWAY_BASE_URL=http://localhost:8081 npm run dev`). Vite reads it at start-up, so restart the dev server after a change.

Mode files: `.env.static-demo` (committed, no hostnames) holds the keys for `npm run build:static-demo` and overrides
`.env` / `.env.local`. Use `.env.static-demo.local` (ignored by git) for a local override.

**CORS:** the gateway must allow the web origin. Its `CORS_ALLOWED_ORIGINS` setting (NAV-001, default `*`) must include
`http://localhost:5173` (dev) and `http://localhost:4173` (preview) if it is ever narrowed. If it does not, the browser blocks
the tile requests and the page shows «Газрын зургийг ачаалж чадсангүй» (tiles unavailable).

## Static public demo (D44, NAV-002 AC 51–54)

The public demo on the PO's shared web hosting has **no backend**, so it is built map-only. Real hostnames are never
written into the repo (D35); below, `https://<demo-host>` is a placeholder for whatever (sub)domain serves the site.

1. Build: `npm run build:static-demo` (uses `.env.static-demo`: `VITE_STATIC_DEMO=true`, `VITE_GATEWAY_BASE_URL=same-origin`).
   The output is `dist-static-demo/`. The build log prints "static demo build: search, reverse and routing are off".
   The same single setting also turns **routing** off (NAV-004 AC 53–54): the route preview still opens from
   «Маршрут гаргах», but shows «Маршрутын үйлчилгээ түр ажиллахгүй байна» with «Дахин оролдох» at once and sends
   **0** `/v1/route` requests to any host.
2. Upload the **contents** of `dist-static-demo/` to the web root of the site (the domain root: asset URLs start with `/`,
   so a sub-folder is not supported).
3. Upload the basemap archive (NAV-001 `basemap.pmtiles`) to `<web root>/tiles/basemap.pmtiles`, so the page reads
   `https://<demo-host>/tiles/basemap.pmtiles`. The page origin is read at runtime, so a changed (sub)domain needs **no
   rebuild**, only the same files under the new host.
4. Hosting checks before sharing the link (NAV-002 AC 51–52, R10), for example with
   `curl -sI -H "Range: bytes=0-16383" https://<demo-host>/tiles/basemap.pmtiles`: the answer must be **206** with a
   `Content-Range` header and **no** `Content-Encoding` (the host must not gzip the archive, or range reads break);
   `http://` must redirect with 301/308 to `https://` (enable the host's "force HTTPS" setting); the host's file-size
   limit must allow the archive (about 112 MiB for Mongolia at z14).

What the static demo does: tiles, fonts and sprites come only from the page origin (0 requests to any other host). Search
and the coordinate card's nearest place show «Хайлт түр ажиллахгүй байна» with «Дахин оролдох» without sending anything
(`src/search/unavailableClient.ts`; the real client also gets a fetch that refuses every call). The route preview shows
«Маршрутын үйлчилгээ түр ажиллахгүй байна» and never builds a route request (`RouteClient` with `enabled: false` and a
refusing fetch, `src/route/routeClient.ts`). Offline still shows
«Интернэт холболт алга» (state precedence offline > unavailable). Pointing the public site at a backend is a story change
recorded after the NAV-008 AC 24 legal review (AC 54), not a setting to flip here.

## What is bundled

Only two hosts are contacted at runtime: the page origin and the gateway (AC 46). The static demo contacts the page
origin only.

- `public/fonts/Noto Sans {Regular,Medium,Italic}/` holds all 256 glyph ranges for each font stack, including Cyrillic
  `1024-1279` with Ө ө Ү ү. `public/sprites/v4/` holds the light (day) and dark (night) sprites. They are pinned to
  protomaps/basemaps-assets commit `028c18f7…` (see `public/fonts/BASEMAPS_ASSETS_COMMIT` and `public/SHA256SUMS`).
- The style is generated in the browser from `@protomaps/basemaps` 5.7.2 plus our post-processing (`src/style/buildStyle.ts`):
  - the label rule `["coalesce", ["get","name:mn"], ["get","name"], ["get","name:en"]]` on every name layer;
  - trunk and low-zoom road colours;
  - label sizes;
  - bundled font stacks only.
- Licences for every shipped asset and library: **`THIRD_PARTY_NOTICES.md`** (generated by
  `scripts/gen-third-party-notices.mjs`).

## Layout of the code

```
src/main.ts                      entry: tokens CSS fallback, PMTiles protocol, App
src/boot/bootLoading.ts          pre-module script inlined into index.html by vite.config.ts (loading pill at 300 ms)
src/config.ts                    gateway URL (absolute, same-origin), asset base URL, static demo switch, features
src/style/tokens.ts              reads docs/design/tokens.json (flavors, UI and location colours, CSS variables)
src/style/buildStyle.ts          buildStyle(), LABEL_EXPRESSION, applyLabelRule(), road colours, text sizes
src/state/status.ts              loading / tiles unavailable / offline state machine (precedence offline > tiles > loading)
src/location/locationController.ts  my-location states (idle, locating, following, not-following, denied, unsupported, stale)
src/ui/app.ts                    DOM chrome: controls, messages, scale bar, attribution, marker; map wiring
src/ui/tooltip.ts, icons.ts      tooltip behaviour, 24 px icons
src/search/                      NAV-003 pure core (no DOM): text.ts (settled query, script detection, traditional-script
                                 stripping), coords.ts, transliterate.ts, queryPlan.ts (ADR-0006 rules A–D), merge.ts,
                                 display.ts (type labels 1–32, name, context line, zoom), photon.ts (contract types),
                                 gateway.ts (typed client, 8 s timeout, outcome classes, Retry-After, cooldown),
                                 searchController.ts (debounce, generations, states), reverseController.ts,
                                 unavailableClient.ts (static demo: "unavailable" at once, no network),
                                 lexicon.json (abbreviations, transliteration tables, name suffixes: data, never displayed)
src/ui/searchBox.ts              combobox, results list, state rows, live region
src/ui/placeCard.ts              place card, coordinate card, the single pin
src/ui/searchFeature.ts          wiring: camera (fitBounds / centre), right-click and long-press (src/ui/longPress.ts)
src/route/                       NAV-004 pure core (no DOM): routeClient.ts (postRoute body, 12 s timeout, outcome
                                 classes, Retry-After, static-build guard), osrm.ts (contract types, response parsing),
                                 polyline.ts, routeController.ts (settle, one request in flight, states, 429 wait,
                                 offline, same point), instructions.ts (ADR-0008 rule table, language-neutral keys) with
                                 maneuvers.fixture.json (shared cases, reused by NAV-005), format.ts (distance, duration,
                                 «Хүрэх цаг»), routeLayers.ts (map-style §7.2 sources and layers, part of buildStyle)
src/ui/routePreview.ts, .css     route panel, fields, tabs, avoid switch, result region, markers, camera, the
                                 coordinate card during the preview
src/ui/routeField.ts             origin / destination combobox (reuses the NAV-003 SearchController)
src/ui/routeIcons.ts             mode, swap and manoeuvre icons (own paths)
src/i18n/{mn,en}.json            every UI string (mn default); src/i18n/i18n.ts
fixtures/label-rule.html         AC 8 fixture page (test hook)
scripts/                         vendor-assets.sh, check-i18n.mjs, check-glossary.mjs, gen-third-party-notices.mjs
```

## Test hooks (for QA, `tests/e2e/nav002/`)

- `data-testid` on every control and message (`map`, `language-toggle`, `theme-toggle`, `compass`, `zoom-in`, `zoom-out`,
  `my-location`, `status-banner`, `banner-retry`, `location-message`, `location-retry`, `location-close`, `loading`,
  `blocking-card`, `card-retry`, `scale-bar`, `scale-label`, `attribution`, `attribution-osm`, `attribution-esa`,
  `location-marker`).
- The my-location button has `data-state`: `idle`, `locating`, `following`, `not-following`, `denied`, `unsupported`.
  The marker has `data-stale="true|false"`. The status banner and the blocking card have `data-reason`: `tiles`,
  `offline`, `generic`. The scale label has `data-meters`, and the bar has `data-width-px`.
- Dev builds only: `window.__nav002 = { map, LABEL_EXPRESSION, app }`.
- NAV-003 (screen spec › Components): `search-field`, `search-input`, `search-clear`, `search-popup` (`data-state` =
  `closed|results|coordinate|loading|no-results|unavailable|offline|rate-limited|error`), `search-results` (listbox,
  `aria-busy`), `search-option` (`data-kind` = `place|coordinate`, `data-type` = the `placeType.*` key; children
  `search-option-name`, `search-option-type`, `search-option-context`), `search-state` (`data-state` =
  `loading|no-results|unavailable|offline|rate-limited|error`), `search-retry` (`aria-disabled="true"` during a 429 wait),
  `search-live` (polite live region), `place-card` (`data-kind` = `place|point`), `place-card-title`, `place-card-close`,
  `place-card-meta`, `place-card-coords`, `place-nearest` (`data-state` = `pending|loading|place|empty|unavailable|
  offline|rate-limited|error`), `place-nearest-name`, `place-retry`, `place-pin` (`role="img"`, `aria-label`).
  Option ids are `search-option-<index>` (`aria-activedescendant`).
- Dev builds only: `window.__nav003 = { search, map }` (`search.controller.view`, `search.reverse.view`).
- NAV-004 (screen spec › Components): `route-open` («Маршрут гаргах» on the place and coordinate card), `route-slot`,
  `route-panel` (`data-state` = `empty|pending|loading|route|same-point|no-route|out-of-area|too-far|unavailable|offline|
  rate-limited|error|static`), `route-title`, `route-close`, `route-origin`, `route-destination` (comboboxes; listboxes
  `#route-origin-results`, `#route-destination-results`), `route-field-list` (`data-field` = `origin|destination`,
  `data-state`), `route-my-location`, `route-field-option`, `route-field-state`, `route-field-retry`, `route-swap`
  (`aria-disabled`), `route-tab-car|walk|bike`, `route-avoid` (`role="switch"`, hidden on walk/bike), `route-result`
  (`aria-busy`), `route-state` (`data-state` as the panel), `route-retry` (`aria-disabled="true"` during a 429 wait),
  `route-avoid-hint`, `route-summary`, `route-duration`, `route-distance`, `route-eta`, `route-next-day`,
  `route-snap-notice`, `route-options` (radiogroup), `route-option` (`data-index`, `aria-checked`), `route-steps`,
  `route-step` (`data-index`, `data-key` = the ADR-0008 key), `route-live` (polite live region), `route-origin-marker`,
  `route-point-card`, `route-point-title`, `route-point-close`, `route-point-coords`, `route-set-origin`,
  `route-set-destination`, `route-point-nearest`, `route-point-retry`, `route-candidate-pin`. The destination marker is
  the NAV-003 `place-pin`. Map sources `nav-route` (one LineString per route, `index`, `selected`) and `nav-route-step`.
- Dev builds only: `window.__nav004 = { route, map }` (`route.controller.view`, `.origin`, `.destination`, `.mode`).
- Simulate route failures with route interception on `**/v1/route` (POST; let `OPTIONS` continue). For a 429 add
  `Access-Control-Expose-Headers: Retry-After`. Playwright treats `aria-disabled="true"` as disabled and waits before a
  `click()`; use `click({ force: true })` or the keyboard to check that the disabled retry button does nothing.
- `http://localhost:5173/fixtures/label-rule.html` renders the three AC 8 features with the exported label expression, plus
  one feature with no name keys. `window.__labelRule.ready` resolves on the first `idle`.
- Simulate search failures with route interception on `**/v1/search?**` and `**/v1/reverse?**`. For a 429 add
  `Access-Control-Expose-Headers: Retry-After`, otherwise the browser hides `Retry-After` and the client waits 5 s (the
  running dev gateway does not expose it yet, ADR-0006 F13).
- Simulate failures in the browser only: route interception on `**/tiles/basemap.pmtiles`, a closed-port
  `VITE_GATEWAY_BASE_URL`, `context.setOffline()`, and Playwright geolocation. Never restart the gateway.

## Behaviour notes

- **Mongolian first.** The UI language is `mn` unless the user picked English, whatever the browser language is. The theme
  and language choices are stored in `localStorage` (`navmn.theme`, `navmn.lang`). If storage is unavailable, the
  defaults apply.
- **PO decisions D11–D15.** Map labels use `name:mn` → `name` → `name:en` in both UI languages, so the English UI keeps
  Cyrillic labels (D11). Day mode by default; the manual toggle is remembered (D12). The page opens on Sükhbaatar
  Square, zoom 12 (D13). Page title `app.title` «Газрын зураг» / "Map" (D15). Browsers (D14): see Supported browsers.
- **Loading before the modules load (AC 37, NAV-002 follow-up 1).** `index.html` has the pill laid out in the initial
  HTML as `.pill.pending` (transparent), and `vite.config.ts` (plugin `navmn-boot-indicator`) fills in the
  default-language text, the design-token CSS, the `<title>` and `src/boot/bootLoading.ts` as a small inline script.
  The script switches the text to the saved language and starts a Web Animations opacity reveal with `delay: 270` and
  `startTime = 0`. The document timeline starts at navigation start, so the reveal is at 270 ms after navigation start
  however late the first style pass runs (a CSS `animation-delay` counts from the first style pass that sees the class,
  which landed 1–2 frames late: 348–374 ms). 270 ms is inside the UX tolerance of 250–300 ms. The compositor runs the
  reveal while MapLibre's start-up blocks the main thread. `aria-hidden` is lifted by a main-thread timer at 300 ms. The
  app cancels the reveal (`cancelBootReveal`) as soon as the pill is not needed (ready, card, banner), and retries use the
  StatusMachine timer only. Measured with the unchanged NAV-002 AC 37 screencast test: on screen at 287–319 ms, 10/10.
  Test hook: before 300 ms the pill has no `idle` class but has `pending` (transparent), so "no `idle` class" alone
  does not mean "visible". There is no `--pill-delay` any more.
- **Archive max zoom.** The basemap source is declared only by `url: pmtiles://…`, so MapLibre reads `minzoom`/`maxzoom`
  from the archive header (default archive: 14, D1) and overzooms beyond it. `MAX_ZOOM = 19` in `src/map/createMap.ts` is
  the camera limit, not the archive's.
- **Start-up errors.** Before the first render, only a basemap error without `e.tile` (archive header / TileJSON)
  fails the attempt and shows the blocking card at once. Tile errors count towards the 3-consecutive-error tiles
  banner, so one transient tile failure never shows the card. If no tile loads at all, the 10 s watchdog shows the card.
- **Attribution link target (AC 49).** As the screen spec says (Components › Attribution strip, Layout rule 6a): the
  strip stays 24 px high at one line (padding 4 px 16 px). The OSM link is `inline-block` with transparent padding
  `24px 8px 4px` cancelled by margin `-24px -8px -4px` and `min-width: 44px`, so its hit area is 44 px high and text
  width + 16 px wide, reaching 20 px above the strip into R4's 20 px bottom padding. The link text is in an inner
  `<span>` (it carries `data-i18n`), and the focus ring is drawn around that span only.
- **No location access before the first press.** Neither the Geolocation API nor the Permissions API is called before
  the first press of the my-location button. Coordinates never leave the page.
- **Retry uses a fresh PMTiles instance, then sets the style again without diffing.**
  - pmtiles caches a failed header load, so the old instance would keep failing (ADR-0004 §5).
  - MapLibre 6 cannot reload errored vector tiles in place (they stay "loading"), so the source has to be rebuilt.
- **Missing icons.** POI icons that the upstream style asks for but sprites v4 lacks (e.g. `townhall`) are registered as
  empty images. The label still shows, with no icon.

## Search (NAV-003)

- **Requests.** 250 ms after the last keystroke (Enter searches at once). At most 2 `search` requests per settled query
  (ADR-0006 §2.3): an abbreviation («БЗД») sends the expanded name and the query as typed; a Latin query sends the query
  as typed plus a Cyrillic transliteration; a Cyrillic query with у/о sends the ү/ө variant only after an empty answer;
  everything else sends one request. `lang` = UI language, `limit=8`, bias `lat`/`lon` with 3 decimals: the device fix
  while following (≤ 60 s old), otherwise the map centre. Search never calls the Geolocation API. Coordinates typed as
  `lat, lon` give a «Сонгосон цэг» option without a request.
- **Responses.** Merged without duplicates (`osm_type` + `osm_id`), `MN` first, at most 10 options. A response for an
  older query is dropped (abort + generation counter). Client timeout 8 s.
- **States.** No results «Илэрц олдсонгүй»; 5xx, network, CORS or timeout «Хайлт түр ажиллахгүй байна» with one retry;
  400 «Алдаа гарлаа»; offline «Интернэт холболт алга» with no request and one search when back online; 429 «Түр
  хүлээгээд дахин оролдоно уу» with the retry disabled for `Retry-After` seconds (5 s if missing or unreadable) and
  nothing sent automatically afterwards. Nothing else retries automatically.
- **Keyboard (AC 41, PO approval F5, D33).** Tab or Shift+Tab from the input closes the popup without selecting, except
  a state row with «Дахин оролдох» (unavailable, rate-limited): it stays open so the retry button is the Tab stop after
  the compass. Esc on the input or on the retry button closes that row and focuses the input (text kept). The retry
  button has `aria-describedby="search-state-text"`. If the focused retry button disappears (popup closed, options or
  another state row), focus goes to the input, never to `<body>`.
- **Map click versus drag (AC 41, D43).** A click on the map (the MapLibre `click` event) closes the popup (options or
  any state row) without selecting; a drag, pinch, rotate or wheel zoom never closes it. Opening a coordinate card closes
  it too. There is no close-on-blur: focus moves to the canvas on pointer down, which is also the start of a drag.
- **Coordinate card.** Right-click (desktop) or a 600 ms long-press (touch, ≤ 10 px movement) on a ready map, or a typed
  coordinate. Exactly one `reverse` request (6 decimals, `limit=1`, `radius=0.5`). The camera does not move for
  right-click / long-press.
- **Camera.** Results with `extent` use `fitBounds` with 40 px plus the covering UI (top bar, card) and max zoom 17.
  Point results are centred in the viewport at zoom 13 (area types) or 16; the layout keeps the viewport centre
  uncovered. Reduced motion jumps.
- **Privacy.** Query text and coordinates are never logged, stored or put in the console.

## Route preview (NAV-004)

- **Open.** «Маршрут гаргах» on the place card or coordinate card opens the panel (the search field and the card are
  hidden, not closed). Destination = the card's point («Сонгосон цэг» for a coordinate card). Origin = «Миний байршил»
  when my location is on (activated, last fix ≤ 60 s old, no Geolocation call), otherwise empty with focus in the
  origin field and its «Миний байршил» option (picking it starts the NAV-002 location request; 10 s wait).
- **Fields.** Each field is a NAV-003 search box (same request rules, bias, states, typed coordinates) with its own list
  in the panel. Esc, Tab or a click elsewhere without a pick restores the point's text. Right-click / long-press during
  the preview opens a coordinate card in the panel's place with «Эхлэх цэг болгох» / «Очих газар болгох».
- **Requests.** `POST /v1/route` only, body per the openapi web client profile: 6-decimal coordinates, `costing`
  `auto|pedestrian|bicycle`, `alternates: 2`, `format: "osrm"`, `banner_instructions: true`, `units: "kilometers"`,
  `language` `mn-MN|en-US`; `costing_options.auto.exclude_unpaved` only on «Машин» with the switch on (the switch is
  hidden on «Явган» and «Дугуй»); no `voice_instructions`, no toll option. One request per triggering action (point set,
  swap, switch, retry, back online); mode tabs settle 300 ms; a newer action aborts the older request. No request when
  start and destination are ≤ 10 m apart, while offline, during a 429 wait or in the static build. Client timeout 12 s.
- **States.** Loading after 300 ms; «Маршрут олдсонгүй» (NoRoute; DistanceExceeded on car; hint when the switch is on);
  outside the service area (NoSegment, error 171); too far on foot or by bike (DistanceExceeded on walk/bike);
  «Маршрутын үйлчилгээ түр ажиллахгүй байна» + one retry (network, CORS, 5xx, timeout); «Түр хүлээгээд дахин оролдоно уу»
  with the retry disabled for `Retry-After` s (5 s if unreadable), nothing sent automatically; «Интернэт холболт алга»
  and one request when back online; «Алдаа гарлаа» (other 400, 413, unparsable 200). Precedence: same point > offline >
  429 wait > unavailable/static > errors > loading > route.
- **Result.** Up to 3 lines (selected above, alternatives narrower; click an alternative or a route option to select it:
  0 requests, no camera move), summary «13 мин · 4,6 км» / «Хүрэх цаг 14:03» (refreshed every 60 s from the clock),
  snap notice above 500 m, route options (k ≥ 2), turn list (one Tab stop, arrow keys; Enter/click centres the map on the
  step at zoom ≥ 17). Turn text comes from `src/route/instructions.ts` and the `maneuver.*` resource keys in both UI
  languages, so a language switch sends 0 requests. The camera fits all lines and both markers into the uncovered map
  area (max zoom 17; jump with reduced motion).
- **Close.** «Хаах» or Esc: lines, origin marker and candidate pin removed, the request aborted, focus back to
  «Маршрут гаргах».
- **Privacy.** Coordinates, request bodies and the device position are never logged, stored or put in the URL. The device
  position leaves the browser only as the origin of a route request whose origin is «Миний байршил».

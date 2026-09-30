# Screen: Web map (NAV-002 web demo)

- **Stories:** NAV-002 (AC 5–54; AC 1–4 are developer set-up and have no UI; AC 50 is a README check; AC 51–54 are the static public demo, D44). **NAV-003** adds the search field to R1, two grid rows (RS search panel, RC card row) and changes the Tab order: see [`NAV-003-search.md`](NAV-003-search.md), which wins where the two differ.
- **Platforms:** Web (MapLibre GL JS, `web/`). Desktop Chromium is the tested target; current Chrome/Edge/Firefox and Android Chrome must work; Safari/iOS are not supported yet (PO decision D14, 2026-09-30; story AC 50). The layout rules also hold for the later Android/iOS map screen, where the Material 3 / HIG equivalents replace the web components.
- **Flow:** [`flows/NAV-002-web-demo-map.md`](../flows/NAV-002-web-demo-map.md)
- **Prototype:** [`prototypes/NAV-002-web-map.html`](../prototypes/NAV-002-web-map.html) (static wireframe, no map library; switch state, theme, language and width with the toolbar or the URL hash, e.g. `#state=denied&theme=night&lang=en`). No Figma file for NAV-002.
- **Style and tokens:** [`map-style.md`](../map-style.md), [`tokens.json`](../tokens.json). Token names below are `tokens.json` paths without the mode (e.g. `ui.surface` = `color.light.ui.surface` in day, `color.night.ui.surface` in night).

## Purpose
Let a UB user (and the PO) look at the Mongolia OSM basemap in Mongolian, move it freely, find their own position, and switch day/night and mn/en, with every failure explained in words.

## Layout

One full-viewport screen. The MapLibre canvas fills the viewport. All UI sits in an **overlay grid** (one explicit column, `grid-template-columns: minmax(0, 1fr)`, and every region pinned to `grid-column: 1` so the blocking layer can never push a region into an implicit second column) on top of it (`pointer-events: none` on the grid, `auto` on its children), so no UI element changes the map size and the camera never jumps when a message appears.

```
Desktop 1366×768 (day, mn)                                          Phone 320×568 (night, mn, zoom < 8, worst case)
┌──────────────────────────────────────────────────────────────┐    ┌──────────────────────────┐
│ (reserved for NAV-003 search)      [ English ] [ ☾ ] [ ◈ ]   │ R1 │     [ English ][ ☀ ][ ◈ ]│
│              ┌────────────────────────────────┐              │    │┌────────────────────────┐│
│              │ ⚠ Газрын зургийг ачаалж        │  status      │ R2 ││⚠ Газрын зургийг ачаалж ││
│              │   чадсангүй    [Дахин оролдох] │  banner      │    ││  чадсангүй             ││
│              └────────────────────────────────┘              │    ││          [Дахин оролдох]││
│              ┌────────────────────────────────┐              │    │└────────────────────────┘│
│              │ Байршлын зөвшөөрөл олгоогүй... │  location    │    │┌────────────────────────┐│
│              │ Хөтчийн тохиргоонд ...  [Хаах] │  message     │    ││Байршлын зөвшөөрөл      ││
│              └────────────────────────────────┘              │    ││олгоогүй байна          ││
│                                                              │ R3 ││Хөтчийн тохиргоонд [Хаах]│
│                         (map)                                │    ││байршлын зөвшөөрлийг ...││
│                                                        [+]   │    │└────────────────────────┘│
│                                                        [−]   │ R4 │                      [+] │
│ ┌──────────┐                                           [◎]   │    │                      [−] │
│ │├──────┤ 1 км                                               │    │ ├──────┤ 200 км      [◎] │
├──────────────────────────────────────────────────────────────┤    ├──────────────────────────┤
│ © OpenStreetMap contributors                                 │ R5 │ © OpenStreetMap contrib. │
└──────────────────────────────────────────────────────────────┘    │ © ESA WorldCover project │
                                                                    │ / Contains modified ...  │
                                                                    └──────────────────────────┘
```
(The phone attribution is shortened here for the drawing only. On screen it is never truncated; it wraps to 4 lines at 320 px.)

### Regions (overlay grid rows, top → bottom)
| Row | Content | Sizing |
|---|---|---|
| R1 Top bar | Right, in this order: Language button, Theme button, Compass (gap 8). Left: the NAV-003 search field (below 600 px it takes line 1 and the three buttons move to line 2, right-aligned; see NAV-003 spec › Layout). | `auto`. Padding: top 12 px + `env(safe-area-inset-top)`, sides `spacing.gutter` 16 px |
| R2 Messages | Status banner (offline or tiles unavailable), then the location message, stacked with gap 8. Centred, width `min(100% − 32 px, size.message-max-width 560 px)`. | `minmax(0, auto)`. **If space runs out the row shrinks and scrolls (`overflow-y: auto`), it never pushes R4/R5 off screen or under another element.** |
| R3 Spacer | Map shows through. Blocking layer (loading pill, blocking card) is centred over R2–R4. | `minmax(0, 1fr)` |
| R4 Bottom controls | Left: scale bar, bottom-aligned. Right column (gap 8): Zoom group (+ / −), My location. | `auto`. Sides 16 px, bottom **20 px** = exactly the height the attribution link's invisible hit area reaches above R5 (Components › Attribution strip). The hit area ends where R4's content begins: it touches, but never overlaps, the scale bar (not interactive), and the right-column buttons are horizontally clear of it (link about 200 px wide at the left edge, buttons in the rightmost 64 px, 256 px from the left at 320 px). A larger value (20 + 8 px gap) was measured and rejected: it makes the 320×568 worst case (rule 7) scroll the message row by 6 px (AC 49) |
| R5 Attribution strip | Full width, opaque. OSM credit always; ESA credit line while zoom < 8. | `auto` (1 line, 2–3 lines at 320 px when the ESA line shows). Bottom padding `env(safe-area-inset-bottom)` |

### Layout rules (AC 15, 34, 45, 49)
1. **Attribution is never covered.** R5 is its own grid row. Nothing is positioned over it: no tooltip, message, card or MapLibre control. It is not collapsible and has no "i" button. It is present in every state, including loading, blocking card and generic error.
2. **Controls never overlap.** Every control lives in exactly one grid cell. R2 can shrink and scroll, R3 can shrink to 0. Verified in the prototype at 320×568, 360×640, 768×1024, 1366×768 and 1920×1080 with all messages open (see Verification).
3. **Messages sit at the top**, under R1, so they never cover the bottom controls, the scale bar or the attribution. Banners and location messages may show together (status first, AC 45).
4. **Before the first tiles render** (loading, blocking card, generic error) R4 and the compass are hidden: they would act on an empty map. Language, theme and the attribution stay usable.
5. **Actions in banners and messages** sit at the end of the text line. Below a 480 px viewport, a banner or message with a wide action («Дахин оролдох», or two buttons) moves its actions to their own line, right-aligned; a single «Хаах» stays inline. Mongolian strings are designed first; every text container wraps (no ellipsis on any message text).
6. **Tooltips** appear to the left of the R4 column and below R1 buttons, never over R5.
6a. **The attribution link's hit area** is the only thing that reaches outside its row: transparent padding extends it 20 px above R5 into R4's bottom padding (AC 49). It is part of R5, paints nothing, and R4's 20 px bottom padding keeps it clear of the scale bar and every control. A click in that band opens the OSM copyright page; a drag that starts there does not pan the map (accepted, same as Google Maps' credit link).
7. **Measured worst case** (prototype, 320×568, zoom < 8 so the ESA credit takes 4 lines, tiles banner + denied message open, attribution link hit area included): all regions fit without the R2 fallback scroll. The compass moved to R1 for this reason, and R4's bottom padding is exactly the hit area's 20 px (rule 6a). **Since NAV-003** the compact top bar is two lines (124 px instead of 68 px), so at 320×568 this worst case uses the R2 fallback scroll (no overlap, NAV-002 AC 49 holds; measured with `check-layout-nav003.mjs`).
8. MapLibre's built-in `AttributionControl`, `NavigationControl`, `GeolocateControl` and `ScaleControl` are **not** used as-is (see Components): they collapse or position themselves in corners outside this grid, and their text is not in our resource files.

## Components
| Component | Source | Notes |
|---|---|---|
| Map canvas | MapLibre GL JS `Map` | `attributionControl: false`, `minZoom: 3`, `maxZoom: 19`, centre P1 (47.9189, 106.9176, Sükhbaatar Square; PO decision D13), zoom 12, bearing 0 (AC 5). Gesture and keyboard handlers on (AC 11–13). Canvas accessible name = `map.label` (MapLibre `locale['Map.Title']`, updated on language switch). |
| Language button | Custom, Material 3 **outlined text button** on `ui.surface` | 48 px high, min 48 px wide, padding 0 12 px, `radius.md`, elevation 1. Visible text = the *other* language in its own language (`language.en` «English» in the mn UI, `language.mn` «Монгол» in the en UI) with a matching `lang` attribute. Accessible name = the visible text; accessible description and tooltip = `language.label` («Хэл»). |
| Theme button | Custom, Material 3 **icon button** (standard) on `ui.surface` | 48×48. Shows the mode it switches **to**: day active → moon icon, name and tooltip `theme.night` «Шөнийн горим»; night active → sun icon, `theme.day` «Өдрийн горим». |
| Compass (reset bearing) | Custom icon button, in R1 | 48×48, always visible once the map is ready (also at bearing 0, so it is always in the tab order). Needle rotates with `−bearing`, north half `ui.error`, south half `ui.on-surface-variant`. Name and tooltip `control.northUp` «Хойд зүг дээшээ». Press → bearing 0 (and pitch 0) in `motion.duration-medium` 300 ms, centre and zoom unchanged (AC 13–14). |
| Zoom group | Custom, two icon buttons in one `ui.surface` container with an `ui.outline-variant` divider | Each 48×48. `+` = `control.zoomIn` «Томруулах», `−` = `control.zoomOut` «Жижигрүүлэх». One press = ±1 zoom, animated 300 ms. At zoom 19 / 3 the button gets `aria-disabled="true"`, `ui.disabled-content` icon, stays focusable (AC 12). |
| My-location button | Custom icon button | 48×48, states below. Name and tooltip `control.recenter` «Байршил руу буцах». |
| Location marker | HTML marker (dot) + two GeoJSON layers (accuracy) | See map-style.md §7. Dot element `role="img"`, name `marker.myLocation` «Миний байршил» (AC 19). |
| Scale bar | Custom (or MapLibre `ScaleControl` re-created on language change, if it meets this row) | Metric only. Pill on `ui.surface-container`, `radius.sm`, padding 2 px 6 px. Bar: 2 px line in `ui.on-surface` with end ticks, max width `size.scale-bar-max` 100 px. Label `typography.caption` in `ui.on-surface`: «500 м», «1 км», «2 км» (mn) / "500 m", "1 km" (en). Round values 1-2-3-5 × 10ⁿ. If a decimal ever appears, comma in mn («0,5 км», glossary C3), point in en. Updates on `move` and at the latest 500 ms after zoom ends (AC 16–17). |
| Attribution strip | Custom | Opaque `ui.surface-container`, `typography.caption` 12/16, text `ui.on-surface-variant`, padding 4 px 16 px, elevation 1 (top shadow). Line 1: `attribution.osm` «© OpenStreetMap contributors», a link to `https://www.openstreetmap.org/copyright`, `target="_blank" rel="noopener"`, always underlined. Line 2 while `zoom < 8`: `attribution.esa` (plain text). Lines wrap; never truncated. **Link hit area (AC 49): ≥ 44×44 CSS px without enlarging the visible strip** (strip stays 24 px high at one line). The link is `display: inline-block` with transparent padding cancelled by equal negative margins, so the line box and the strip height do not change: `padding: 24px 8px 4px; margin: -24px -8px -4px`. Result: the link box is 16 + 24 + 4 = **44 px** high and text width + 16 px wide (measured 202 px in the prototype; `min-width: 44px` as a floor). It runs from 20 px above the strip's top edge down to the strip's bottom content edge (one line) or 4 px into line 2 (ESA line shown). It extends upward, not downward, because at one line the strip is the last row and a downward extension would leave the viewport. No background, border or outline on the padding; the focus ring (2 px `ui.focus-ring`, 2 px offset) is drawn around the **text** only: the link text sits in an inner `<span>`, `a:focus-visible { outline: none }` and `a:focus-visible > span { outline: 2px solid; outline-offset: 2px }`, so neither the padding nor the strip changes visibly. |
| Status banner | Custom, Material 3 **banner** pattern | `radius.md`, elevation 2, padding 12 px 16 px, min height 48 px, icon 24 px + `typography.body` text + optional text button. Variants: **offline** (`ui.message-surface` / `ui.on-message-surface`, 1 px `ui.message-outline`, cloud-off icon, no action) and **tiles unavailable** (`ui.error-container` / `ui.on-error-container`, warning icon, `action.retry` text button). `role="status"`. |
| Location message | Custom, Material 3 **snackbar** pattern, persistent (no timeout) | `ui.message-surface` / `ui.on-message-surface`, 1 px `ui.message-outline`, `radius.md`, elevation 3, padding 12 px 8 px 12 px 16 px. Title (`typography.body`, weight 600) + optional hint line + actions (text buttons in `ui.message-action`, 48 px high). `role="alert"`. **Night deviates from Material 3** (inverse surface would be a light box on the dark map, i.e. glare): a raised dark surface with an outline instead. |
| Loading pill | Custom, Material 3 circular progress (indeterminate, 20 px) + label | `ui.surface`, `radius.full`, elevation 2, padding 8 px 16 px, text `status.loading`. Container `role="status" aria-live="polite"`. |
| Blocking card | Custom, Material 3 **elevated card** | Centred, width `min(100% − 32 px, 360 px)`, `radius.lg`, elevation 3, padding 24 px, centred content: icon 40 px, title `typography.title`, action as a filled tonal button (48 px high, `ui.primary-container` / `ui.on-primary-container`). |
| Tooltip | Custom | `typography.caption`, `ui.on-message-surface` on `ui.message-surface` with 1 px `ui.message-outline` (no light box at night), `radius.sm`. Shows on hover after 500 ms and on keyboard focus immediately; hides on Esc, blur, or pointer leave; hoverable (WCAG 1.4.13). Touch: no tooltip. |
| Ferrostar components | **Not used in NAV-002** | There is no route or guidance in this story. Ferrostar Web's map and banner components arrive with NAV-005. Keep one MapLibre `Map` owned by our code so NAV-004/005 can add route layers to it. |

### My-location button states (AC 18–25)
| `data-state` | When | Icon (24 px) | Colours | ARIA |
|---|---|---|---|---|
| `idle` | Supported and not pressed yet (also when the browser already blocks location: that is only known after the first press, see `denied`), or permission prompt not answered yet | Crosshair outline (`my_location` outlined) | icon `ui.on-surface-variant` on `ui.surface` | `aria-pressed="false"` |
| `locating` | Pressed, waiting for the first fix (≤ 10 s) | Crosshair outline + indeterminate 2 px ring around the button edge in `ui.primary` (reduced motion: static ring) | as idle | `aria-busy="true"` |
| `following` | Fix shown and the camera follows (AC 19–20) | Crosshair **filled** | icon `ui.primary` on `ui.primary-container` | `aria-pressed="true"` |
| `not-following` | Fix shown, user has panned (AC 20) | Crosshair outline | icon `ui.primary` on `ui.surface` | `aria-pressed="false"` |
| `denied` | After a press, the request returns `PERMISSION_DENIED` (prompt refused or already blocked) (AC 21). **Never set at load:** no Geolocation or Permissions API call happens before the first press (AC 18; ADR-0004 §6 and Amendment 1). A press from `denied` starts a new request. | Crosshair **with slash** (`location_disabled`) | icon `ui.error` on `ui.surface` | `aria-pressed="false"`, description `location.denied.title` |
| `unsupported` | No Geolocation API or not a secure context (AC 24) | Crosshair with slash | icon `ui.disabled-content` | `aria-disabled="true"` (stays focusable), description and tooltip `location.unavailable` |

`data-state` is a QA hook and not a user-facing string. The marker has its own `data-stale="true|false"`.

## States
| State | What the user sees |
|---|---|
| **Default (ready)** | Map at P1 z12 with Cyrillic labels. R1 language, theme, compass; R4 zoom, my location, scale bar (about «1 км» at 1366 px, z12: 12.8 m/px at P1, so the 100 px maximum bar rounds down to a 78 px «1 км» bar); R5 OSM credit. No messages. |
| **Loading** (AC 37) | After 300 ms without the first `idle`: centred loading pill «Ачаалж байна…». Map area shows `map.flavor.earth` (no white flash at night). R4 and compass hidden; language, theme and attribution visible (and, since NAV-003, the search field). Hides ≤ 500 ms after the first `idle`. **Timing rule (NAV-002 follow-up 1, 2026-09-30):** the pill must not depend on a script timer or on the module graph loading. It is present, with its text, in the initial HTML/CSS and becomes visible through a CSS animation (`animation-delay`, `animation-fill-mode: both`), so the compositor paints it even while MapLibre's start-up blocks the main thread. Script only removes it (≤ 500 ms after the first `idle`). **Design tolerance:** the reveal may be scheduled up to **50 ms early** (target 250–300 ms after navigation start) to absorb the 1–2 frames between the scheduled time and the first painted frame. This does not change AC 37 (it requires the pill when the map is not ready within 300 ms and sets no lower bound) and it keeps the no-flash intent: a map that is ready before 250 ms never shows the pill. It must be on screen by **350 ms** (QA limit, not loosened). |
| **Empty / no result** (AC 10) | Not an error. Empty countryside or outside-coverage areas (X2 Beijing) show only low-zoom land/water/boundaries and whatever labels exist. No message. |
| **Error: tiles unavailable at start** (AC 38–40) | Blocking card: map-off icon, title `status.tilesUnavailable` «Газрын зургийг ачаалж чадсангүй», button `action.retry` «Дахин оролдох». Pressing it: button shows a 20 px progress indicator and `aria-busy`, the card stays; success → card fades out, map renders ≤ 5 s, focus moves to the map canvas; failure → card stays, focus stays on the button. Theme and language are kept. |
| **Error: tiles failing while shown** (AC 41–42) | Top banner, error container: `status.tilesUnavailable` + `action.retry`. Drawn tiles stay, map stays interactive. Hides ≤ 2 s after a tile request succeeds. |
| **Error: generic / no WebGL** | Blocking card with `status.genericError` «Алдаа гарлаа» and `action.retry` (reloads the page). |
| **Offline** (AC 43–45) | Top banner, message surface: `status.offline` «Интернэт холболт алга», no button. Drawn tiles stay, pan/zoom in the loaded area works. Replaces the tiles-unavailable banner while offline. Before the first tiles render, the blocking card shows the offline text instead (no button; the map loads by itself when back online). Hides ≤ 2 s after `online`. |
| **Location: permission denied** (AC 21) | Location message: title `location.denied.title` «Байршлын зөвшөөрөл олгоогүй байна», hint `location.denied.hint` «Хөтчийн тохиргоонд байршлын зөвшөөрлийг асаана уу», action `action.close` «Хаах». Button `denied`. Pressing the button again shows the message again. |
| **Location: could not determine** (AC 22) | Location message: `location.unavailable` «Байршил тодорхойлж чадсангүй», actions `action.retry` «Дахин оролдох» and `action.close` «Хаах». |
| **GPS / location lost after a fix** (AC 23) | Marker turns stale grey at the last position (accuracy circle grey), location message `location.unavailable` with `action.close`. When fixes resume: marker normal and the message hides ≤ 2 s. |
| **Location unsupported / insecure origin** (AC 24) | Button `unsupported`, no message on load. Tooltip/description `location.unavailable`. Pressing it does nothing (no exception). |
| **Night** (AC 27) | Same layout. Map = night flavor, UI = `color.night.ui` tokens. Day is the default on a first visit; the manual choice is remembered; no automatic switching in NAV-002 (PO decision D12). |
| **English UI** (AC 31) | Same layout, English strings, `<html lang="en">`. Map labels unchanged (Cyrillic, `name:mn` → `name` → `name:en`; PO decision D11). |
| **Static public demo** (AC 51–54, PO decision D44) | The production build published on the PO's web hosting (`https://<demo-host>`, placeholder; the hostname is never written in the repo, D35). **Map-only**: search, reverse and routing are off by a build setting, so the site sends **0** `search`, `reverse` or routing requests to any host (AC 53, 54). **Same layout and controls** as the default state: the search field stays in R1 (NAV-003), and there is no demo badge, extra banner or changed copy. Map, pan/zoom/rotate, compass, theme, language, scale bar, my location (the site is HTTPS, so AC 24's insecure-origin state does not occur) and «© OpenStreetMap contributors» work as specified. **Search:** the field accepts text; a settled query of ≥ 2 characters, «Дахин оролдох» or a coordinate card shows «Хайлт түр ажиллахгүй байна» with «Дахин оролдох» within **1 s**, without a request and without a spinner (details: NAV-003 spec › States › Static public demo). **Tiles** come only by HTTP Range (206) from the page origin or the one configured tiles URL (AC 52); the UI is unchanged by this. If the host cannot serve the archive, the existing **Error: tiles unavailable at start** card shows (no new state; NAV-002 risk R10). **Any domain:** the build must refer to its own files (script, style, fonts, sprites, basemap archive) by relative URLs and shows no hostname anywhere in the UI, so a changed subdomain needs no rebuild. Theme and language are stored per origin, so a new subdomain starts with the defaults (Mongolian, day) once; no message. If the web host itself is down, the browser's own error page shows (accepted for a demo, story edge cases). |

## Interactions
- **Pan:** mouse drag, one-finger drag, arrow keys with the canvas focused (AC 11). Panning while `following` → `not-following`.
- **Zoom:** wheel, double-click, pinch, `+`/`-` keys, zoom buttons (AC 12). While `following`, wheel and pinch zoom around the centre so following continues. Double-click zooms at the pointer and ends following.
- **Rotate:** right-drag, Ctrl+drag, Shift+←/→, two-finger twist (AC 13). Compass needle follows. Rotation keeps following.
- **Compass press:** bearing (and pitch) → 0 in 300 ms (AC 14).
- **My location press:** see flow F3. First fix: `flyTo` in `motion.duration-camera` 1000 ms (reduced motion: `jumpTo`) to the fix, zoom `max(current, 15)` (AC 19). Follow updates: `easeTo` 500 ms, zoom unchanged.
- **Theme press:** switch style and UI tokens, camera unchanged, remembered (AC 27–28).
- **Language press:** swap strings ≤ 500 ms, no reload, camera unchanged, remembered (AC 31). The button keeps focus after the press, and its new text is announced.
- **Retry:** see States. **Close:** hides the location message, focus returns to the my-location button.
- **Attribution link:** opens the OSM copyright page in a new tab (AC 34).
- **Persistence:** theme and language in `localStorage` (wrapped in try/catch); unavailable storage → defaults, no error.

## Copy (mn / en)
Every string comes from `docs/requirements/glossary.md`. The story's rows G1–G7 are in the glossary since 2026-09-29 (G1–G6 in §7, G7 in §8; G1–G6 `needs native review`, G7 not translated), and "Day mode", "Metre (on-screen unit)" and "Kilometre (on-screen unit)" since 2026-09-30 (§5, `needs native review`). No string here is new. Resource keys are a proposal for `web/` (mobile-engineer owns the files).

| Key | mn | en | Glossary source |
|---|---|---|---|
| `app.title` (document title) | Газрын зураг | Map | Map (PO decision D15: until a product name exists) |
| `map.label` (canvas name) | Газрын зураг | Map | Map |
| `control.zoomIn` | Томруулах | Zoom in | Zoom in / zoom out |
| `control.zoomOut` | Жижигрүүлэх | Zoom out | Zoom in / zoom out |
| `control.northUp` | Хойд зүг дээшээ | North up | North up |
| `control.recenter` | Байршил руу буцах | Recenter | Recenter |
| `marker.myLocation` | Миний байршил | My location | My location |
| `theme.day` | Өдрийн горим | Day mode | Day mode (§5) |
| `theme.night` | Шөнийн горим | Night mode | Night mode |
| `language.label` | Хэл | Language | Language |
| `language.mn` | Монгол | Монгол | Language (own-language name, same in both files) |
| `language.en` | English | English | Language (own-language name, same in both files) |
| `status.loading` | Ачаалж байна… | Loading… | G1 |
| `status.tilesUnavailable` | Газрын зургийг ачаалж чадсангүй | The map could not be loaded | G2 |
| `status.offline` | Интернэт холболт алга | No internet connection | No connection |
| `status.genericError` | Алдаа гарлаа | Something went wrong | Generic error |
| `action.retry` | Дахин оролдох | Try again | Try again |
| `action.close` | Хаах | Close | G6 |
| `location.denied.title` | Байршлын зөвшөөрөл олгоогүй байна | Location permission is turned off | G3 |
| `location.denied.hint` | Хөтчийн тохиргоонд байршлын зөвшөөрлийг асаана уу | Allow location access in your browser settings | G4 |
| `location.unavailable` | Байршил тодорхойлж чадсангүй | Your location could not be determined | G5 |
| `unit.m` | м | m | Metre (on-screen unit) (§5), C3 |
| `unit.km` | км | km | Kilometre (on-screen unit) (§5), C3 |
| `attribution.osm` | © OpenStreetMap contributors | © OpenStreetMap contributors | OSM attribution (not translated) |
| `attribution.esa` | © ESA WorldCover project / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium | same | G7 (not translated) |

Notes
- **Capital letter:** the glossary lists some terms in lower case (e.g. «газрын зураг», «томруулах», «шөнийн горим»). As stand-alone labels they start with a capital letter, as the story already writes them («Газрын зураг», «Томруулах»). The AC 33 check should compare with the first letter case-insensitive (request to BA below).
- **Length (Cyrillic first):** the longest strings are `location.denied.hint` (51 characters mn vs 46 en) and `status.tilesUnavailable` (31 mn vs 27 en). At 320 px they wrap to 2–3 lines; no container truncates. Icon-only buttons have no visible text, so control sizes do not change with language. The language button is 48 px wide minimum and grows with «English» / «Монгол».
- **Scale label:** number + space + unit key («500 м»). Numbers are not strings.
- **Accessible descriptions** reuse the keys above; there are no extra screen-reader-only strings. The attribution link has no "(opens in a new tab)" hint yet, because the glossary has no term for it (optional request to BA).

## Accessibility
- **Contrast (AC 29):** every UI text/background pair in both modes is ≥ 4.5:1, icons, control borders and focus rings ≥ 3:1 against the map earth colour (`tokens.json › contrastPairs`, checked by `prototypes/check-contrast.mjs`). All text backgrounds are **opaque** (attribution strip, scale pill, banners, messages, tooltips), so automated checkers can compute contrast and the result does not depend on the map underneath.
- **Names (AC 48):** every control has an accessible name from resources in the current UI language. Icon buttons use `aria-label`. The language button's name equals its visible text (WCAG 2.5.3).
- **Focus (AC 48):** visible focus ring 2 px `ui.focus-ring` with 2 px offset on every control, including the map canvas. DOM and tab order (NAV-002 only): map canvas → language → theme → compass → status banner action → location message actions → zoom in → zoom out → my location → attribution link. The map container comes first (it is the main content and MapLibre owns it); the overlay grid follows in visual reading order, top to bottom. **Since NAV-003** (its AC 1: the search field is the first Tab stop) the order is: search input → clear → language → theme → compass → results-list retry → place card controls → map canvas → status banner action → … (unchanged). See NAV-003 spec › Accessibility. Messages do not steal focus. `role="alert"` for location messages, `role="status"` for the status banner and loading pill.
- **Keyboard:** MapLibre keyboard handler on the focused canvas (arrows, `+`/`-`, Shift+arrows). Esc closes a tooltip. All actions are buttons (Enter/Space).
- **Touch targets (AC 49):** every button is 48×48 CSS px (≥ 44 required), with 8 px gaps. The attribution link, the only text link, has a 44 px high (and ≥ 44 px wide) hit area from transparent padding, while the visible strip keeps its size (Components › Attribution strip, Layout rule 6a).
- **Text size / dynamic type:** all UI type in `rem`. At 200 % browser zoom the grid rules still hold (R2 scrolls). Attribution never below 11 CSS px (12 px at 100 %).
- **Reduced motion:** `prefers-reduced-motion: reduce` → camera jumps instead of flying, static progress indicators, no fades.
- **Forced colours (Windows High Contrast):** buttons keep a 1 px border (`outline` token becomes `ButtonBorder`), icons use `currentColor`.
- **Language of parts:** `<html lang>` follows the UI language; «English» / «Монгол» on the language button carry their own `lang`.
- **No time limits:** no message auto-dismisses except when its cause is gone.
- **Driver safety:** NAV-002 is a desktop demo, not in-car use. Nothing here needs typing. The rules for active navigation (1-second glance, larger type) are in `navigation-ux.md` with NAV-005.

## AC traceability
| AC | Where in this spec |
|---|---|
| 5, 9, 10 | Components › Map canvas; States › Default, Empty; map-style.md §1 |
| 6–8 | map-style.md §4–5 |
| 11–15 | Interactions; Components › Compass, Zoom group |
| 16–17 | Components › Scale bar |
| 18–25 | My-location button states; States › Location rows; flow F3 |
| 26–29 | Components › Theme button; States › Night; Accessibility › Contrast; map-style.md §8; tokens.json |
| 30–33 | Copy; Components › Language button |
| 34–35 | Layout rules 1, 6; Components › Attribution strip |
| 37–45 | States; Layout rules 3–4; flows F1, F2, F5 |
| 46–47 | map-style.md §5–6 (bundled assets); flow F3 (no coordinates sent) |
| 48–49 | Accessibility; Layout rules 2, 5 |
| 51–54 | States › Static public demo (D44); search behaviour in NAV-003 spec › States › Static public demo |

## Verification
- `node docs/design/prototypes/check-contrast.mjs`: contrast pairs, flavor key parity, map-style.md values vs tokens.json.
- `docs/design/prototypes/check-layout.mjs` (Playwright, Chromium from `/opt/pw-browsers`): opens the prototype at 320×568, 360×640, 768×1024, 1366×768 and 1920×1080, in every state × day/night × mn/en, and checks that no two controls overlap, that the attribution is fully in the viewport, uncovered and ≥ 11 px, and that every control is ≥ 44×44 px. The attribution link is measured like every other control (its `getBoundingClientRect` includes the transparent padding), must stay inside the viewport, and must not overlap the scale bar or another control; the strip height is checked to stay at one-line size (≤ 25 px at normal zoom, desktop widths). It checks the wireframe, not the real app; QA's NAV-002 tests check the app.

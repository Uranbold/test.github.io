# Screen: Route preview A→B, alternatives, mode tabs and turn list (NAV-004, on the NAV-002/NAV-003 web map)

- **Stories:** NAV-004 (AC 1–54). Traceability per AC at the end.
- **Platforms:** Web (`web/`, MapLibre GL JS), current desktop Chrome/Edge/Firefox and Android Chrome; automated tests on desktop Chromium (D14). Later native route previews reuse the layout rules, states and copy (Components › Native mapping).
- **Base screens:** [`NAV-002-web-map.md`](NAV-002-web-map.md) (map, controls, messages, attribution) and [`NAV-003-search.md`](NAV-003-search.md) (search field, results list, place card, coordinate card, pin). Everything this spec does not change stays as specified there.
- **Flow:** [`flows/NAV-004-route-preview.md`](../flows/NAV-004-route-preview.md) (F1 open the preview, F2 set and change points, F3 route request lifecycle, F4 result, alternatives and turn list, F5 error and recovery, F6 close, language and theme, F7 static public build).
- **Prototype:** [`prototypes/NAV-004-route-preview.html`](../prototypes/NAV-004-route-preview.html), a static wireframe of every state below, day/night, mn/en. The toolbar or the URL hash picks the view, for example `#state=no-route-avoid&theme=night&lang=en&extra=worst`. Layout checked by `prototypes/check-layout-nav004.mjs` (Verification). No Figma file.
- **Tokens:** [`tokens.json`](../tokens.json) v0.3.0. New for NAV-004: `color.*.route.*`, `typography.scale.title-large`, `size.side-panel`, `size.route-sheet-max`, `size.origin-marker`, `size.switch-width/height`, `motion.route-mode-settle`, `motion.route-eta-refresh`, `motion.route-camera` (700 ms, added in fix round 1 for D1; additive, version unchanged), and 40 new `contrastPairs`. Map layers and markers: [`map-style.md` §7.2–7.3](../map-style.md).
- **API:** `openapi.yaml` **0.5.0** `postRoute` (`POST /v1/route`; 0.5.0 contracts `bicycle`, documents `exit` and the web preview client profile, ADR-0008 §4). This design needs no further contract change. Search in the fields uses NAV-003's `search` (AC 6); the coordinate card uses `reverse`.
- **Instruction text source:** [ADR-0008](../../architecture/adr/0008-route-instruction-text-client-side.md) (accepted 2026-09-30): option **(b)**, client-side text from the OSRM manoeuvre fields and glossary templates, in **both** UI languages; Valhalla's narrative is never shown. The rule order is ADR-0008 §2; the keys in Copy › Manoeuvre texts follow its suggested names with a `maneuver.` prefix (the mobile engineer owns the final names).

## Purpose
Let a user pick where to go and where to start, compare up to three routes by car, on foot or by bike, see distance, duration and «Хүрэх цаг», and read every turn in correct Mongolian, before leaving. Every failure (no route, outside the service area, too far on foot, routing down, offline, too many requests, same start and destination) is explained in words, never as an endless spinner.

## Layout

The route panel **replaces the NAV-003 search UI while it is open**, the way Google Maps' directions panel replaces the search box: the R1 search field and the place card are **hidden, not cleared or closed**, and come back when the panel closes (AC 41). The panel has its own origin and destination fields (AC 6), so a third search field on screen would compete with them.

```
Compact < 600 px (360×640, mn, day)           Medium 600–839 px (768×1024)                  Expanded ≥ 840 px (1366×768): two grid columns
Bottom sheet in RC                            Bottom sheet in RC, max 400 px wide           Column 1 (432 px) = panel; NAV-002 rows move to column 2
┌──────────────────────────────┐              ┌────────────────────────────────────────┐   ┌──────────────────┬──────────────────────────────────────┐
│         [English][☾][◈]      │ R1           │                   [English][☾][◈]      │   │┌────────────────┐│                    [English][☾][◈]   │ R1
│  (messages R2, if any)       │ R2           │  (messages R2, if any)                 │   ││Маршрут харах[×]││     (messages R2, centred in col 2)  │ R2
│        ◯━━━━━┓               │              │                                        │   ││◯ Миний байршил ││                                      │
│  (map)       ┗━━━━━📍  [+]   │ R3 + R4      │            (map with route)            │   ││📍 Зайсан толгой⇅││          ◯━━━━━━┓                    │
│                        [−]   │              │                                  [+]   │   ││[🚗Машин][🚶Явган][🚲Дугуй]││                 ┗━━━━━━━━━📍         │
│ ├────┤ 1 км            [◎]   │              │ ├────┤ 1 км                      [−]   │   ││Шороон замаас… ◯││          (map)                       │
│┌────────────────────────────┐│ RC (sheet)   │┌──────────────────┐              [◎]   │   ││13 мин · 4,6 км ││                                      │
││Маршрут харах            [×]││ sticky head  ││Маршрут харах  [×]│                    │   ││Хүрэх цаг 14:03 ││                                [+]   │
││◯ Миний байршил          ⇅  ││              ││◯ Миний байршил ⇅ │                    │   ││◉ ━ Маршрут 1   ││                                [−]   │
││📍 Зайсан толгой            ││              ││📍 Зайсан толгой  │                    │   ││○ ━ Маршрут 2   ││ ├────┤ 1 км                    [◎]   │ R4
││[🚗Машин][🚶Явган][🚲Дугуй] ││              ││[Машин][Явган][..]│                    │   ││Маршрутын заавар││                                      │
││Шороон замаас зайлсхийх  ◯  ││              ││Шороон замаас.. ◯ │                    │   ││↱ Баруун тийш.. ││                                      │
││13 мин · 4,6 км             ││ (scrolls)    ││13 мин · 4,6 км   │                    │   │└──── scrolls ───┘│                                      │
│└────────────────────────────┘│              │└──────────────────┘                    │   │                  │                                      │
├──────────────────────────────┤              ├────────────────────────────────────────┤   ├──────────────────┴──────────────────────────────────────┤
│ © OpenStreetMap contributors │ R5           │ © OpenStreetMap contributors           │   │ © OpenStreetMap contributors                            │ R5
└──────────────────────────────┘              └────────────────────────────────────────┘   └─────────────────────────────────────────────────────────┘
```
(Icons in the drawing stand for 24 px SVG icons; the prototype shows them.)

### Regions
The NAV-003 overlay grid (`grid-template-rows: auto minmax(0,auto) minmax(0,auto) minmax(0,1fr) auto minmax(0,auto) auto`, rows R1, RS, R2, R3, R4, RC, R5) is unchanged. NAV-004 adds one grid item, **the route slot**, and, at expanded width only, a second grid column while the panel is open.

| Width | Route slot position | Other rows |
|---|---|---|
| **Compact < 600 px** | Grid row 6 (RC, where the NAV-003 card sits), full width minus the 16 px gutters. Padding `0 16px 20px` (the 20 px keeps the NAV-002 attribution link's hit area clear, NAV-002 rule 6a). The panel's `max-height` is `size.route-sheet-max` **60dvh**; the grid row can shrink it further, and the panel scrolls inside. | R1 shows only the NAV-002 cluster (the search field is hidden), so R1 is **68 px** (one line) instead of NAV-003's 124 px. R4 bottom padding **8 px** while the sheet is in RC (as NAV-003 with a card). |
| **Medium 600–839 px** | As compact, panel `max-width: 400px` (`size.search-width`), left-aligned. | As compact. |
| **Expanded ≥ 840 px** | `#ui` gets `grid-template-columns: size.side-panel (432px) minmax(0,1fr)`. The route slot is `grid-column: 1; grid-row: 1 / 7` (R1 to RC), padding `12px + safe area` top, 16 px sides, **20 px** bottom (attribution hit area). The panel is 400 px wide, as tall as its content up to the slot height, and scrolls inside. | Every other grid item (R1 cluster, RS, R2, R3, R4, RC) moves to **column 2**. The map canvas and **R5 attribution span both columns**. R4 bottom padding stays 20 px. The NAV-002 rows cannot overlap the panel, by construction. |

### Layout rules (AC 16, 17, 43; NAV-002 rules 1–8 and NAV-003 rules 1–9 still apply)
1. **The panel lives in its own grid area.** It can never cover the attribution, the scale bar or a NAV-002 control, and no NAV-002 message can cover it (AC 43). Only the panel, R2 and RS may shrink below their content and scroll inside.
2. **Breakpoints** as NAV-003 (`tokens.json › breakpoint`): < 600 compact, 600–839 medium, ≥ 840 expanded. The two-column layout starts at 840 px because column 2 then keeps at least 408 px for the NAV-002 cluster (≈ 200 px) and messages. At 600–839 px it would leave < 216 px.
3. **One element, one DOM position.** The route slot is one element. Only its grid placement changes with the width, so resizing never changes its focus order or state (as NAV-003 rule 4).
4. **Search UI while the panel is open.** The R1 search field and the NAV-003 results popup are hidden (`hidden`, not removed): text, results and request state are kept. The NAV-003 place card is hidden, not closed. The NAV-003 pin becomes the destination marker (map-style §7.3). All return when the panel closes (AC 41).
5. **The panel scrolls as one box, with a sticky header.** The header row (heading + «Хаах») is `position: sticky; top: 0` inside the panel, so «Хаах» is always reachable. Everything else scrolls: fields, tabs, avoid switch, result region, route options, turn list. At every AC 43 width a 200-step turn list scrolls inside the panel (story edge case "very long intercity routes").
6. **Field result lists are in flow.** A field's results list (NAV-003 listbox and state rows) opens **directly below the fields block**, inside the panel, and pushes the tabs and results down. It never floats over other UI and never leaves the panel. Only one field list is open at a time.
7. **Coordinate card during the preview** (AC 7): it is shown **in the route slot, in place of the panel** (the panel is hidden, not closed, like NAV-003 rule 5 "list over card"). Same layout as the NAV-003 coordinate card plus two buttons (Components › Coordinate card during the preview). Closing it shows the panel again.
8. **Messages.** Route states show only inside the panel's result region (AC 40). NAV-002 banners and location messages keep row R2 (column 2 at expanded width, above the map at compact width). NAV-002 precedence (offline > tiles unavailable > loading) is unchanged.
9. **Measured fit** (prototype, `check-layout-nav004.mjs`, 24 states × 5 widths × day/night × mn/en × with/without the NAV-002 worst-case messages = 960 combinations, 0 problems):

   | Viewport | Panel top → bottom | Where the summary starts | Map band above the sheet |
   |---|---|---|---|
   | 320×568 | 236 → 524 (288 px, limited by the grid) | 501 px: the first line peeks in at the bottom edge; the user scrolls the sheet (Known limitations 1) | 160 px (R4 area) |
   | 360×640 | 236 → 596 (360 px) | 501 px, fully visible | 160 px |
   | 390×844 (common phone, not an AC width) | 294 → 800 | 559 px, visible with the first route option | 226 px |
   | 768×1024 | 366 → 980 (60dvh) | 639 px, visible with all options | 298 px |
   | 1366×768 | 12 → 724 (column 1) | 285 px, visible with options and the first turns | column 2 is 934 px wide |

### Recommended DOM structure (Tab order = DOM order)
Inside `#ui`: R1 (search field, cluster) → RS (NAV-003 results popup) → NAV-003 card → **route slot** (`#route`: the route panel `<section>`, then the preview-time coordinate card) → `#map` → R2 → blocking layer → R4 → R5. The route slot directly follows the NAV-003 card, so both side panels have the same place in the Tab order. The mobile engineer may choose another structure if the Tab order (Accessibility) and the layout rules hold. NAV-004 markup goes in its own modules (for example `web/src/route/**`, `web/src/ui/routePanel*.ts`); the shared `index.html`, `app.ts` and `styles.css` get only the slot and the grid rules (story coordination rules).

## Components

| Component | Source | Spec |
|---|---|---|
| **«Маршрут гаргах» button** (entry point, AC 1–2) | Custom, Material 3 **filled button** | On the NAV-003 place card **and** coordinate card, directly **below the coordinates** (the slot NAV-003 reserved), above the coordinate card's nearest-place divider. Height 48 px, `radius.full`, padding 0 24 0 16, leading 18 px "directions" icon (decorative), label `route.getDirections` «Маршрут гаргах» (`typography.label`), `ui.primary` background, `ui.on-primary` text (6.4:1 day, 7.5:1 night). Left-aligned, wraps if needed, never truncated. Enter/Space/click open the panel within 500 ms. Shown in the static build too (AC 53). **Not shown** on the coordinate card that opens during the preview (its two set buttons replace it). Test id `route-open`. |
| **Route panel** | Custom, Material 3 **side sheet** (expanded) / **bottom sheet** (compact, medium, fixed height, no drag handle) | `<section aria-labelledby="route-title">` on `ui.surface`, `radius.md` (8 px), elevation 2, width 100 % (max 400 px), `overflow-y: auto`, `overscroll-behavior: contain`. Opens with a 150 ms fade (`motion.duration-short`), none with reduced motion. Test id `route-panel`, `data-state` = the result-region state (QA hook, not user-facing). |
| Panel header (sticky) | Custom | Row, min height 56 px (48 px below 600 px), padding 4 4 4 16, `ui.surface` background (so scrolled content passes under it). Heading `<h2 id="route-title" tabindex="-1">` `route.title` «Маршрут харах» (`typography.title` 16/24 600, `ui.on-surface`, wraps, never truncated). Close icon button 48×48, `action.close` «Хаах» as `aria-label` and tooltip. Test ids `route-title`, `route-close`. |
| **Origin field / destination field** (AC 4–6) | Custom, NAV-003 search field anatomy on `ui.surface-container` (filled field inside the sheet) | Stacked, 48 px high each, gap 8, `radius.md`, no shadow. Leading 24 px icon (decorative): origin = ring (`ui.on-surface-variant`), destination = pin shape in `pin.fill` (matches the destination marker). Input `typography.body-large` 16/24 `ui.on-surface`, placeholder in `ui.on-surface-variant`, `text-overflow: ellipsis` (the field text is a name; the full name is in the marker's accessible name and the place card). Focus ring 2 px `ui.focus-ring` around the whole field (`:focus-within`). Attributes as the NAV-003 input (`role="combobox"`, `aria-autocomplete="list"`, `aria-expanded`, `aria-controls` → the field's listbox, `aria-activedescendant`, `maxlength="200"`, `autocomplete/autocorrect/autocapitalize off`, `spellcheck="false"`, `enterkeyhint="search"`), plus `aria-label` `route.origin` «Эхлэх цэг» / `route.destination` «Очих газар» and placeholders `route.originPlaceholder` «Эхлэх цэг сонгох» / `route.destinationPlaceholder` «Очих газар сонгох». **No clear button** (no glossary term fits "clear a route point"; Interactions › Editing a field). A field text in Cyrillic gets `lang="mn"` in the English UI (NAV-003 language of parts). Test ids `route-origin`, `route-destination`. |
| Swap button (AC 8) | Custom icon button | 48×48, transparent, icon "swap vertical" 24 px `ui.on-surface-variant`, to the right of the fields, vertically centred on the fields block. `aria-label` and tooltip `route.swap` «Эхлэх цэг, очих газрыг солих». `aria-disabled="true"` (stays focusable, icon `ui.disabled-content`, press does nothing) while either point is empty. Test id `route-swap`. |
| Field results list (AC 5, 6) | NAV-003 listbox, result option, coordinate option and state row, **reused unchanged** | In flow below the fields block (rule 6), margin 8 16 0, 1 px `ui.outline-variant` border, `radius.md`, padding 8 0. One listbox id per field (`route-origin-results`, `route-destination-results`), `aria-label` `search.results` «Хайлтын илэрц». All NAV-003 states and copy (loading, no results, unavailable, offline, rate-limited, bad request, typed coordinates). **Origin only:** when the origin field has focus and its text is empty, the list shows one option **«Миний байршил»** (`marker.myLocation`, leading my-location crosshair icon) as the first option, with no request (AC 5). When text is typed, the list shows search results only. Test ids `route-field-list`, option `route-my-location`. |
| **Mode tabs** (AC 11, 45) | Custom, Material 3 **primary tabs** with inline icon | `role="tablist"`, `aria-label` `route.modes` «Зорчих хэлбэр». Three `role="tab"` buttons of equal width, 48 px high: icon (car / walk / bike, 24 px) + label `route.mode.car` «Машин», `route.mode.walk` «Явган», `route.mode.bike` «Дугуй» (`typography.label`). Selected: `aria-selected="true"`, text and icon `ui.primary`, 3 px indicator `ui.primary` at the bottom (rounded top, inset 12 px), ≥ 3:1 on `ui.surface`. Unselected: `ui.on-surface-variant`. A 1 px `ui.outline-variant` line under the list. Each tab `aria-controls` the tab panel. Roving `tabindex` (selected tab 0, others −1). Test ids `route-tab-car`, `route-tab-walk`, `route-tab-bike`. |
| Tab panel | `role="tabpanel"` | `aria-labelledby` = the selected tab. Contains the avoid switch and the result region. One panel for all three tabs (the content changes with the tab). |
| **Avoid switch** (AC 12) | Custom, Material 3 **switch** in a list row | The whole row is the control: `<button role="switch" aria-checked>`, min height 48 px, padding 4 16, label `route.avoidUnpaved` «Шороон замаас зайлсхийх» on the left (`typography.body` 14/20, fits one line at 320 px in both languages), track on the right (`size.switch-width/height` 52×32). Off: track `ui.surface-container` with a 2 px `ui.outline` border, 16 px handle `ui.outline` (outline ≥ 3:1 on surface and surface-container). On: track `ui.primary`, 24 px handle `ui.on-primary`. **Visible only on «Машин»**: ADR-0008 §4 / `openapi.yaml` 0.5.0 contract `exclude_unpaved` for `auto` only, so the switch is hidden on «Явган» / «Дугуй» and no `costing_options` are sent there (AC 12). Off by default; its state is kept across tabs and for the page session (not stored). Test id `route-avoid`. |
| **Result region** | Custom | Below the switch, padding 4 0 12. `aria-busy="true"` while loading (AC 32). Shows **exactly one** of: nothing (no origin yet), one state row (States), or the route result (summary, snap notice, route options, turn list). Test id `route-result`. |
| State row (route) | NAV-003 state row | Min height 56 px, grid: icon 24 · message (`body` 14/20 `ui.on-surface`, wraps, never truncated) · optional text button `action.retry` «Дахин оролдох» (48 px high, `ui.primary`; below 480 px it moves to its own line, right-aligned). The retry button has `aria-describedby` → the message. A second line (`ui.on-surface-variant`) is used only for the avoid hint (AC 33). Test id `route-state`, `data-state` = `loading|no-route|out-of-area|too-far|same-point|unavailable|offline|rate-limited|error|static` (QA hook). Retry test id `route-retry`. |
| **Summary** (AC 22–25) | Custom | Padding 8 16 4. Line 1: duration in `typography.title-large` 22/28 500 `ui.on-surface`, then " · " and the distance in `body-large` `ui.on-surface-variant` (the " · " is CSS, not text). Line 2: `route.eta` «Хүрэх цаг» + " HH:MM" (`body`, `ui.on-surface`), and, when the arrival is on a later day, `route.nextDay.*` «+1 өдөр» in `ui.on-surface-variant`. Example «13 мин · 4,6 км» / «Хүрэх цаг 14:03». Numbers use tabular figures. Test ids `route-duration`, `route-distance`, `route-eta`. |
| Snap notice (AC 21) | Custom info row | Only when a snap distance is > 500 m. Grid: info icon 24 `ui.on-surface-variant` · text `route.snapNotice` «Хамгийн ойрын зам сонгосон цэгээс {distance} зайтай» (`body`, `ui.on-surface`). Below the summary, above the route options. Not an error colour: the route is valid. Test id `route-snap-notice`. |
| **Route options** (AC 18, 19, 45) | Custom, Material 3 **radio list** | Only when *k* ≥ 2. `role="radiogroup"`, `aria-label` `route.options` «Маршрут сонгох». Top border 1 px `ui.outline-variant`. One `role="radio"` row per route, min height 56 px, padding 4 16, gap 16: radio mark (20 px; selected: ring and dot `ui.primary`; unselected: ring `ui.on-surface-variant`), a 24×6 px **colour swatch** that matches the route's line (`route.selected` or `route.alternative` with its casing; decorative, `aria-hidden`), label `route.option` «Маршрут {n}» (`body-large` 500) and meta «15 мин · 5,2 км» (`body`, `ui.on-surface-variant`). `aria-checked` on the selected row. Non-selected rows get `aria-describedby` → a visually hidden `route.alternative` «Өөр маршрут» (AC 19). Roving `tabindex` (selected row 0). Hover `ui.state-hover`. Test id `route-option`. |
| **Turn list** (AC 26, 30, 45) | Custom | Heading `<h3 id="route-directions-title">` `route.directions` «Маршрутын заавар» (`typography.title`, padding 12 16 4, top border 1 px `ui.outline-variant`). `<ol aria-labelledby="route-directions-title">`, one `<li>` per step, each holding one `<button>` row: grid icon 24 · text · distance, min height 56 px, padding 8 16, gap 16. Icon: manoeuvre glyph (Content rules › Manoeuvre icons), `ui.on-surface-variant`, `aria-hidden`. Text: instruction (`body-large` `ui.on-surface`, wraps, never truncated) and, on its own line, the street name (`body`, `ui.on-surface-variant`; omitted when empty). Distance at the right (`body`, `ui.on-surface-variant`, tabular, no wrap); none on the `arrive` row. Accessible name (`aria-label`) = instruction, street name and distance joined with ", " (AC 45), for example «Баруун тийш эргэнэ үү, Энхтайвны өргөн чөлөө, 600 м». **One Tab stop for the whole list** (roving `tabindex`, Interactions › Keyboard). Hover `ui.state-hover`; the last activated row keeps a 4 px left bar in `route.selected` while its manoeuvre point is on the map. Test ids `route-steps`, `route-step`. |
| Live region (route) | Visually hidden `<div aria-live="polite">` | Outside the panel, always in the DOM. One text per result or state (AC 47, Accessibility › Announcements). Not updated for loading or for the 60 s ETA refresh. Test id `route-live`. |
| **Coordinate card during the preview** (AC 7) | NAV-003 coordinate card (same component and layout) | Shown in the route slot in place of the panel (rule 7): heading «Сонгосон цэг», coordinates, **two outlined buttons** `route.setOrigin` «Эхлэх цэг болгох» and `route.setDestination` «Очих газар болгох» (48 px high, `radius.full`, 1 px `ui.outline` border, `ui.primary` text; side by side, wrapping onto two lines when they do not fit), then the NAV-003 nearest-place area (`reverse` as NAV-003 AC 27–30, 36–37). No «Маршрут гаргах» on it. The point is marked with the **candidate pin** (map-style §7.3). It does **not** replace or close the NAV-003 card that opened the panel. Test ids `route-point-card`, `route-set-origin`, `route-set-destination`. |
| Origin marker, destination marker, candidate pin, manoeuvre point | MapLibre HTML markers / style layer | [`map-style.md` §7.3](../map-style.md). |
| Route lines | MapLibre style layers | [`map-style.md` §7.2](../map-style.md). |
| Tooltip | NAV-002 tooltip | «Хаах» (panel and card) and the swap button only. |
| **Ferrostar components** | **Not used in NAV-004** | The preview needs no Ferrostar Web component (story Context). The manoeuvre mapping (text keys + icons) is written so NAV-005 can reuse it: Ferrostar's banner and step views take our instruction text and icon per step instead of Valhalla's `mn-MN` narrative (NAV-007 F1–F10). NAV-005 decides which Ferrostar views it customises. |
| Native mapping (later, not NAV-004) | Compose: Material 3 `ModalBottomSheet`/`BottomSheetScaffold` with drag handle, `PrimaryTabRow`, `Switch`, `RadioButton` rows, `LazyColumn` steps. SwiftUI: `.sheet` with detents, segmented `Picker`, `Toggle`, `List` | Same states, copy and anatomy. The native bottom sheet gets drag detents (collapsed = header + summary), which removes Known limitations 1. **Driver safety:** typing in the fields is blocked while the vehicle moves (NAV-005 navigation-ux rules). |

### Content rules

**Field texts (AC 1–7, 9).** Result selected → the result name (NAV-003 result content rules: name, else street + house number, else type label). Device position → «Миний байршил» (`marker.myLocation`). Map coordinate, typed coordinates, or a coordinate card → «Сонгосон цэг» (`place.selectedPoint`), never the `reverse` name (AC 2, 7). The marker's accessible name is always the field text (AC 9).

**Distances (AC 23)**, in the summary, route options, step rows and the snap notice. `d` in metres:
| Range | Format | mn | en |
|---|---|---|---|
| d < 995 | nearest 10 m, minimum 10 | «850 м» | "850 m" |
| 995 ≤ d < 99 950 | km, one decimal, trailing ",0"/".0" dropped; comma in mn, point in en | «1,4 км», «12 км», «12,4 км» | "1.4 km", "12 km", "12.4 km" |
| d ≥ 99 950 | whole km | «245 км» | "245 km" |

Number and unit are separated by a space (U+0020 in resources; the code may use a no-break space U+00A0 so «4,6 км» never wraps between number and unit). Units come from `unit.m`, `unit.km`.

**Durations (AC 24).** Rounded to the nearest minute, minimum 1: < 3 570 s → «25 мин»; otherwise «1 ц 25 мин», «2 ц» (0 minutes omitted). Units `unit.h` «ц», `unit.min` «мин». English "25 min", "1 h 25 min".

**«Хүрэх цаг» (AC 25).** `route.eta` + " " + HH:MM (24-hour, device local time at response + `route.duration`, rounded to the minute). Later calendar day: + " " + `route.nextDay.one|other` with `{days}` («+1 өдөр», "+1 day", "+2 days"). Recomputed every `motion.route-eta-refresh` 60 s from the clock, silently (no announcement, no request).

**Street names (AC 26).** `step.name` after removing U+200B, U+200C, U+200D, U+FEFF and trimming; omitted when empty. Traditional Mongolian script is removed as in NAV-003 AC 18. Names are never translated or transliterated (D11); a Cyrillic name in the English UI gets `lang="mn"`.

**Instruction texts (AC 27–29).** Exactly the AC 27 table, chosen by the ADR-0008 §2 rule order (first match wins: depart, arrive, roundabout with exit, roundabout without exit, exit roundabout, U-turn, fork, merge, on ramp, off ramp, any turning modifier, everything else = «Чигээрээ явна уу»). The capital first letter is applied by code (idempotent) or stored in the resource (AC 48 allows either). Resource keys are in Copy › Manoeuvre texts. Never English, never a placeholder, never a bare «зүүн»/«баруун» (AC 28). ADR-0008 notes one interpretation for the BA: a `continue`/`new name` step with a turning modifier shows the turn text.

**Manoeuvre icons** (decorative, 24 px, `currentColor`). Shapes follow **Material Symbols**; the names below are the visual reference. The web app currently draws its icons as its own simple paths in Material Symbols style (`web/src/ui/icons.ts`); continue that, or copy the Material Symbols SVGs verbatim and add their licence (Apache-2.0) to `web/THIRD_PARTY_NOTICES.md` (architect confirms in review). Bundled in the build, no icon font or CDN (NAV-002 AC 46).
| OSRM step | Icon (Material Symbols name) |
|---|---|
| `depart` | `trip_origin` |
| turn / slight / sharp, left or right | `turn_left`, `turn_right`, `turn_slight_left`, `turn_slight_right`, `turn_sharp_left`, `turn_sharp_right` |
| `uturn` | `u_turn_left` (right-hand traffic: U-turns go left) |
| straight / continue / new name / notification | `straight` |
| `fork` left / right | `fork_left`, `fork_right` (fork straight: `straight`) |
| `merge` (any side) | `merge` |
| `on ramp`, `off ramp` | `ramp_left` for left side, `ramp_right` for right side or no side (ramps are on the right in right-hand traffic) |
| `roundabout`, `rotary`, `exit roundabout`, `exit rotary` | the counter-clockwise roundabout glyph (right-hand traffic; check the direction of `roundabout_left` / `roundabout_right` visually before choosing) |
| `arrive` (any side) | `flag` |
| Mode tabs | `directions_car`, `directions_walk`, `directions_bike` |
| Swap, «Маршрут гаргах», my location, info | `swap_vert`, `directions`, `my_location`, `info` |

### Camera rules (AC 17, 18, 30)
- **First render of a response** (AC 17): `fitBounds` of all drawn route lines plus both markers, **max zoom 17**, duration **`motion.route-camera` 700 ms**, or `duration: 0` (jump) with `prefers-reduced-motion: reduce` (done within 500 ms). AC 17 counts 1 s from the render to the camera's arrival; the shared `motion.duration-camera` (1000 ms) measured 1025–1296 ms in the app (verification round 0, D1), so the route camera leaves about 300 ms for render-to-first-frame overhead. `motion.duration-camera` stays 1000 ms for NAV-002 (my location) and NAV-003 (search results); only the two route-preview moves in this section use `motion.route-camera`. Padding = **40 px + the UI that covers that edge**:
  - top: R1 bottom (68 px while the panel is open) + the R2 message row height when a message shows;
  - left: 432 px (`size.side-panel`) at expanded width, else 0;
  - bottom: R5 height + (sheet height + 20 px) below 840 px, else R5 height;
  - right: 64 px (R4 control column: 48 + 16) — a design addition; AC 17 only requires the panel and top bar, but the zoom buttons would otherwise sit on the route end.
  If the padded area is smaller than 80×80 px (320×568 with NAV-002 messages), drop the right padding first, then fit into whatever area remains; never throw.
- **Selecting another route** (AC 18): **no camera move**, 0 requests.
- **Turn-list row activated** (AC 30): `easeTo` the step's `maneuver.location` at the centre of the **uncovered map area** (same padding as above), zoom 17 or the current zoom if higher, duration **`motion.route-camera` 700 ms** so it arrives ≤ 1 s after activation (`duration: 0` jump with reduced motion, done within 500 ms). Show the manoeuvre point there (map-style §7.3). The route and selection stay.
- **States without a route** (no route, outside the area, same point, errors): the camera does not move. Markers stay where they are.
- **Opening the panel, setting a point, switching tabs, the loading state:** no camera move until the next route renders.

## States

### Result region (one at a time)
| State | What the user sees | AC |
|---|---|---|
| **No origin yet** (location not on) | Origin field empty with «Эхлэх цэг сонгох», focus in it, its list open with «Миний байршил». Destination field shows the destination. Swap disabled. Result region empty (no message: the placeholder says what to do). Destination marker shown, no origin marker, no line. No request, no location prompt. | 4, 5, 46 |
| **Waiting for location** («Миний байршил» picked while location was not on) | NAV-002 my-location button in its requesting state. Origin field stays empty until a fix arrives; after 300 ms the result region shows the loading row. Fix within 10 s → field «Миний байршил», request sent. Denied → NAV-002 AC 21 location message in R2; `POSITION_UNAVAILABLE` or timeout → NAV-002 location message «Байршил тодорхойлж чадсангүй» in R2. In both failure cases the loading row goes away, the origin stays empty, focus returns to the origin field, no request. | 5 |
| **Pending ≤ 300 ms** | Previous lines, summary, options and turn list are already **removed** (AC 32); markers stay; the region is empty. | 13, 32 |
| **Loading** (> 300 ms) | State row: 20 px spinner (static with reduced motion) + «Ачаалж байна…». Region `aria-busy="true"`. Gone ≤ 200 ms after the outcome. Ends at the latest with the 12 s client timeout (→ unavailable). Not announced. | 32, 36 |
| **Route, *k* = 1** | Summary («13 мин · 4,6 км», «Хүрэх цаг 14:03»), snap notice if any, turn list. No route options. One line on the map; camera fits. Announced «1 маршрут олдлоо, 4,6 км, 13 мин, Хүрэх цаг 14:03». | 15–17, 22–26, 47 |
| **Route, *k* = 2–3** | As *k* = 1, plus route options between the summary and the turn list; route 1 selected. *k* lines, the selected one emphasised. | 16, 19 |
| **Alternative selected** | Summary and turn list show that route; its option checked; its line emphasised. No camera move. Announced «Маршрут 2, 5,2 км, 15 мин, Хүрэх цаг 14:05». | 18, 47 |
| **Snap notice** (a snap distance > 500 m) | Route shown, plus the info row «Хамгийн ойрын зам сонгосон цэгээс 1,4 км зайтай» (larger of the two distances). | 21 |
| **Next-day arrival** | «Хүрэх цаг 00:30 +1 өдөр». | 25 |
| **Same start and destination** (≤ 10 m, checked before any request) | State row, pin icon `ui.on-surface-variant`: «Эхлэх цэг, очих газар ижил байна». No retry. No line; both markers stay. Within 500 ms, 0 requests. | 14 |
| **No route** (400 `NoRoute`; `DistanceExceeded` on «Машин») | State row, route-off icon `ui.on-surface-variant`: «Маршрут олдсонгүй». If the avoid switch is on, a second line «Шороон замаас зайлсхийх тохиргоог унтрааж дахин оролдоно уу». No retry (the same request would fail). Not an error colour. | 33, 35 |
| **Outside the service area** (400 `NoSegment`, `ValhallaError` 171) | State row, warning icon `ui.on-surface-variant`: «Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна». No retry, no line, markers stay. | 34 |
| **Too far on foot or by bike** (400 `DistanceExceeded` on «Явган»/«Дугуй») | State row, info icon: «Энэ зай явганаар эсвэл дугуйгаар хэт хол байна». No retry. The «Машин» tab is one click away. | 35 |
| **Routing unavailable** (network, CORS, 502, 503, 504, 12 s timeout, while online) | State row, warning icon `ui.error`: «Маршрутын үйлчилгээ түр ажиллахгүй байна» + «Дахин оролдох» (re-sends the current request once). No automatic retry. | 36 |
| **Offline** | State row, cloud-off icon `ui.on-surface-variant`: «Интернэт холболт алга», no button, no request. The NAV-002 offline banner also shows. Back online with both points set and no route shown → one request within 2 s by itself. | 38 |
| **Rate-limited** (429, `Retry-After: N`; missing or invalid → 5 s) | State row, hourglass icon: «Түр хүлээгээд дахин оролдоно уу» + «Дахин оролдох» **disabled** (`aria-disabled="true"`, `ui.disabled-content`, focusable) for N s. Fields, tabs, swap and switch still update the inputs; nothing is sent for N s. After N s the button turns active; nothing is sent by itself; the button or the next triggering action sends one request. No countdown. | 37 |
| **Error** (400 `InvalidOptions`, `InvalidValue`, `TooBig`, unknown; other `ValhallaError`; 413; unparsable 200) | State row, error icon `ui.error`: «Алдаа гарлаа». No retry of the same request; the next triggering action tries again. | 39 |
| **Static public build** | Section below. | 53 |

**Precedence** (only one state row at a time): same start and destination > offline > rate-limited (while the wait runs) > unavailable / static > outside the service area / too far / no route / error > loading > route result. Same-point wins because it needs no network at all and tells the user what to change.

### Map, markers and panel in other situations
| Situation | What the user sees | AC |
|---|---|---|
| **Night / theme switch** | Panel switches colours in the same frame; lines and markers re-drawn in night colours; selection kept; 0 requests. | 20 |
| **English UI** | All labels and messages in English, "Arrive at 14:03", decimal point («4.6 km»), tabs "Car", "Walk", "Bike". Street names and map labels stay Cyrillic (D11), marked `lang="mn"`. «Миний байршил» reads "My location". Instruction text = the AC 27 `en` column from the client templates (ADR-0008 §1: English also uses client templates). | 29, 49 |
| **Language switch with a route shown** | Labels and instruction texts switch in place ≤ 500 ms, **0 requests** (ADR-0008: the text is generated on the client from the route already shown). Route, selection, camera and scroll position stay. Focus stays on the same control; a focused turn row keeps its index. | 40, 50 |
| **GPS lost / location updates while the panel is open** | Nothing is re-requested (the preview is a plan). The NAV-002 dot may turn stale grey; the route stays. The origin stays the fix used for the request. | Edge cases |
| **Tiles unavailable (NAV-002)** | Routing still works; the banner shows in R2; route lines draw over the empty map. | 40 |
| **Coordinate card during the preview** | Panel hidden, card in its slot with «Эхлэх цэг болгох» / «Очих газар болгох», candidate pin at the point, nearest place as NAV-003. Route lines and markers stay visible. | 7 |
| **Field list open** | NAV-003 list and states under the fields block; the current route stays below and on the map until a new point is chosen. | 6 |

### Static public build (routing off; NAV-004 AC 53–54, NAV-002 AC 53–54, D44; PO decision Q1 2026-09-30: map-only until the NAV-008 AC 24 legal review)
Same single build setting as search and reverse (`config.features.routing` is false in `STATIC_DEMO_FEATURES`); no second switch. The client never builds or sends a route request (AC 54). Everything not listed works as in the rest of this spec.

| Element / action | What the user sees | AC |
|---|---|---|
| «Маршрут гаргах» on a place card or coordinate card | Shown and works: the panel opens as usual (fields, tabs, switch, focus rules AC 4/46). | 53 |
| Result region at opening | Within **1 s**, whatever the origin state: the **Routing unavailable** state row «Маршрутын үйлчилгээ түр ажиллахгүй байна» + «Дахин оролдох». No loading row, no spinner. `data-state="static"`. | 53 |
| «Дахин оролдох», tab switch, swap, avoid switch, setting a point | The same row is shown again within 1 s and announced once per action. **0** `/v1/route` requests to any host. | 53, 54 |
| Typing in a field | NAV-003 static behaviour: «Хайлт түр ажиллахгүй байна» in the field list, 0 search requests. Typed coordinates and «Миний байршил» still set points (no request needed). | 53; NAV-003 static state |
| Right-click / long-press during the preview | The coordinate card opens with its two buttons; its nearest-place area shows the NAV-003 static unavailable row. | 53 |
| Offline | Offline wins (precedence). | 38 |
| Map, attribution, controls | Work as NAV-002 › Static public demo. | 53 |

No demo badge and no "routing comes later" text: no glossary term exists (same decision as NAV-003).

## Interactions

**Open (AC 1–4, 46).** Activating «Маршрут гаргах» (click, tap, Enter, Space) opens the panel within 500 ms: destination = the card's point, destination field = the card's name (or «Сонгосон цэг»). Origin: location on (NAV-002 activated and last fix ≤ 60 s old) → «Миний байршил», the last fix, request sent at once, focus to the selected mode tab; otherwise the origin field is empty, focus goes to it and its list shows «Миний байршил». No Geolocation call and no browser prompt on open.

**Editing a field (AC 5, 6).** Typing behaves as the NAV-003 search box (debounce, request rules, bias, states, strings, keyboard). A point that is already set **stays set** while the user types, and the route stays on screen. Selecting a result or the coordinate option replaces the point; one request follows when both points are set. Esc with the list open closes the list and **restores the field text of the current point**; Tab or a click elsewhere without a pick does the same. Emptying the origin field while it has focus shows the «Миний байршил» option. A point is never cleared by the user (there is no clear button); the way to change it is to pick another one or swap.

**Swap (AC 8).** Swaps the points, field texts and markers, sends one request. Focus stays on the swap button.

**Mode tabs (AC 11).** Click, tap, or ArrowLeft/ArrowRight/Home/End (automatic activation: the focused tab is selected). The request is sent when a tab has stayed selected for `motion.route-mode-settle` 300 ms; two quick ArrowRight presses send one request, for the final tab. Costing: «Машин» `auto`, «Явган» `pedestrian`, «Дугуй» `bicycle` (contracted in 0.5.0). Selecting the already-selected tab does nothing. The switch hides on «Явган»/«Дугуй» and comes back with its kept state on «Машин».

**Avoid switch (AC 12).** Click, tap, Space or Enter toggles it; one request each time.

**Route selection (AC 18).** Click or tap an alternative line (hit area ≥ 10 px each side, map-style §7.2) or a route option; ArrowUp/ArrowDown (and Left/Right) in the radio group move and select. Within 200 ms: emphasis, summary and turn list follow; 0 requests; no camera move. Clicking the selected line does nothing.

**Turn-list row (AC 30).** Click, tap, Enter or Space: camera to that step (Camera rules), manoeuvre point shown. Focus stays on the row.

**Right-click / long-press on the map (AC 7).** Opens the coordinate card in the route slot (rule 7). «Эхлэх цэг болгох» / «Очих газар болгох» set that point («Сонгосон цэг»), close the card, show the panel, and send one request when both points are set; focus goes to the field that was set. «Хаах» or Esc on the card: card closed, panel back unchanged, focus to the panel heading.

**Retry (AC 36, 37).** «Дахин оролдох» re-sends the current request once. While it is disabled (429 wait), Enter/Space do nothing. When the retry button disappears (the loading row replaces it), focus moves to the **selected mode tab**, never to `<body>`.

**Close (AC 41).** «Хаах», or Esc with focus in the panel and no field list open. Within 200 ms: panel, route lines, origin marker, candidate pin and manoeuvre point removed; any in-flight request aborted; 0 new requests. The search field and the NAV-003 card show again; the pin goes back to the card's point. Focus returns to the «Маршрут гаргах» button that opened the panel, or to the search input if that card no longer exists. The fallback is defensive: while the panel is open the search field and card are hidden, so the user cannot close or replace that card, and the preview-time coordinate card never replaces it (Design notes 3).

**Keyboard summary (AC 45).**
| Key | Where | Result |
|---|---|---|
| Tab / Shift+Tab | Page | Next / previous stop in the order under Accessibility |
| ArrowLeft / ArrowRight, Home / End | Mode tabs | Move and select, wrapping; request after 300 ms settle |
| ArrowUp / ArrowDown (also Left / Right) | Route options | Move and select, wrapping; 0 requests |
| ArrowUp / ArrowDown, Home / End | Turn list | Move focus between rows (roving `tabindex`), no wrapping, no activation |
| Enter / Space | Turn row | Camera to the step |
| Enter / Space | Switch, swap, buttons | Activate |
| ArrowDown / ArrowUp, Enter, Esc | Origin / destination field | NAV-003 combobox keys; Esc closes the list and restores the point's text |
| Esc | Anywhere in the panel with no field list open | Close the panel |
| Esc | Coordinate card during the preview | Close the card, back to the panel |

## Copy (mn / en)
Every string is a glossary term (existing rows, NAV-003 T-rows, NAV-004 N1–N23). No new term is needed. Keys are a proposal for `web/src/i18n/{mn,en}.json` (mobile engineer); both files get the same keys (NAV-002 AC 32). `{n}`, `{count}`, `{distance}`, `{days}` stay literally in both files.

| Key | mn | en | Glossary source |
|---|---|---|---|
| `route.getDirections` | Маршрут гаргах | Directions | Get directions (button) |
| `route.title` | Маршрут харах | Route preview | Route preview |
| `route.origin` (field name) | Эхлэх цэг | Start | Origin / starting point |
| `route.originPlaceholder` | Эхлэх цэг сонгох | Choose starting point | N2 |
| `route.destination` (field name) | Очих газар | Destination | Destination |
| `route.destinationPlaceholder` | Очих газар сонгох | Choose destination | N3 |
| `marker.myLocation` (reused) | Миний байршил | My location | My location / Origin |
| `route.swap` | Эхлэх цэг, очих газрыг солих | Swap start and destination | N4 |
| `route.modes` (tab list name) | Зорчих хэлбэр | Travel mode | N19 |
| `route.mode.car` | Машин | Car | Travel mode |
| `route.mode.walk` | Явган | Walk | Travel mode |
| `route.mode.bike` | Дугуй | Bike | N1 |
| `route.avoidUnpaved` | Шороон замаас зайлсхийх | Avoid unpaved roads | Avoid unpaved roads |
| `route.options` (group name) | Маршрут сонгох | Choose a route | N20 |
| `route.option` | Маршрут {n} | Route {n} | N6 |
| `route.alternative` | Өөр маршрут | Alternative route | Alternative route |
| `route.eta` | Хүрэх цаг | Arrive at | ETA (arrival time) |
| `route.nextDay.one` | +{days} өдөр | +{days} day | N16 |
| `route.nextDay.other` | +{days} өдөр | +{days} days | N16 |
| `route.directions` (turn list name) | Маршрутын заавар | Directions | N5 |
| `route.count.one` | {count} маршрут олдлоо | 1 route found | N7 |
| `route.count.other` | {count} маршрут олдлоо | {count} routes found | N7 |
| `route.noRoute` | Маршрут олдсонгүй | No route found | No route found |
| `route.noRouteAvoidHint` | Шороон замаас зайлсхийх тохиргоог унтрааж дахин оролдоно уу | Turn off “Avoid unpaved roads” and try again | N12 |
| `route.outOfArea` | Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна | The start or destination is outside the service area | N9 |
| `route.samePoint` | Эхлэх цэг, очих газар ижил байна | The start and destination are the same | N10 |
| `route.tooFar` | Энэ зай явганаар эсвэл дугуйгаар хэт хол байна | This distance is too far on foot or by bike | N11 |
| `route.unavailable` | Маршрутын үйлчилгээ түр ажиллахгүй байна | Routing is temporarily unavailable | N8 |
| `route.rateLimited` | Түр хүлээгээд дахин оролдоно уу | Too many route requests. Wait a moment and try again | Too many requests (T5), route `en` value from the story |
| `route.snapNotice` | Хамгийн ойрын зам сонгосон цэгээс {distance} зайтай | The nearest road is {distance} from the chosen point | N13 |
| `route.setOrigin` | Эхлэх цэг болгох | Set as start | N17 |
| `route.setDestination` | Очих газар болгох | Set as destination | N18 |
| `unit.h` | ц | h | N21 |
| `unit.min` | мин | min | N22 |
| `unit.m`, `unit.km` (reused) | м, км | m, km | Metre / Kilometre |
| `status.loading`, `status.offline`, `status.genericError`, `action.retry`, `action.close`, `place.selectedPoint`, `place.nearest`, `location.unavailable`, `search.results`, `search.unavailable` (reused) | as NAV-002/NAV-003 | as NAV-002/NAV-003 | G1, No connection, Generic error, Try again, G6, T6, T7, G5, T2, T4 |

### Manoeuvre texts (AC 27, ADR-0008 §2)
Key = `maneuver.` + the ADR-0008 key. Stored with a lower-case first letter as in the glossary, or capitalised; code capitalises the first letter for display (AC 48). The same key selects the icon (Content rules › Manoeuvre icons).

| Key | mn | en |
|---|---|---|
| `maneuver.depart.n` / `.ne` / `.e` / `.se` / `.s` / `.sw` / `.w` / `.nw` | Хойд зүг рүү явна уу / Зүүн хойд зүг рүү явна уу / Зүүн зүг рүү явна уу / Зүүн өмнө зүг рүү явна уу / Өмнө зүг рүү явна уу / Баруун өмнө зүг рүү явна уу / Баруун зүг рүү явна уу / Баруун хойд зүг рүү явна уу | Head north / northeast / east / southeast / south / southwest / west / northwest |
| `maneuver.turn.left` / `.right` | Зүүн тийш эргэнэ үү / Баруун тийш эргэнэ үү | Turn left / Turn right |
| `maneuver.turn.slightLeft` / `maneuver.turn.slightRight` | Бага зэрэг зүүн тийш эргэнэ үү / Бага зэрэг баруун тийш эргэнэ үү | Turn slightly left / Turn slightly right |
| `maneuver.turn.sharpLeft` / `maneuver.turn.sharpRight` | Огцом зүүн тийш эргэнэ үү / Огцом баруун тийш эргэнэ үү | Turn sharp left / Turn sharp right |
| `maneuver.uturn` | Буцаж эргэнэ үү | Make a U-turn |
| `maneuver.continue` | Чигээрээ явна уу | Continue straight |
| `maneuver.keep.left` / `.right` | Зүүн талаа барина уу / Баруун талаа барина уу | Keep left / Keep right |
| `maneuver.merge` / `.left` / `.right` | Замд нийлнэ үү / Зүүн талаас замд нийлнэ үү / Баруун талаас замд нийлнэ үү | Merge / Merge from the left / Merge from the right |
| `maneuver.onRamp` / `.left` / `.right` | Орох зам руу эргэнэ үү / Зүүн талын орох зам руу эргэнэ үү / Баруун талын орох зам руу эргэнэ үү | Take the ramp / Take the ramp on the left / Take the ramp on the right |
| `maneuver.offRamp` / `.left` / `.right` | Гарах зам руу эргэнэ үү / Зүүн талын гарах зам руу эргэнэ үү / Баруун талын гарах зам руу эргэнэ үү | Take the exit / Take the exit on the left / Take the exit on the right |
| `maneuver.roundabout.exit` | Тойрог: {n}-р гарц | Roundabout: exit {n} |
| `maneuver.roundabout.enter` | Тойрогт орно уу | Enter the roundabout |
| `maneuver.roundabout.leave` | Тойргоос гарна уу | Exit the roundabout |
| `maneuver.arrive` / `.left` / `.right` | Та очих газартаа ирлээ / Таны очих газар зүүн талд байна / Таны очих газар баруун талд байна | You have arrived / Your destination is on the left / Your destination is on the right |

Depart sectors: 8 sectors of 45° centred on 0°, 45°, … (`n` = [337.5°, 22.5°), `ne` = [22.5°, 67.5°), …). The boundary 22.5° belongs to `ne`, 337.5° to `n`; 22.4° is `n` (AC 27 fixture).

Notes
- **Length, Mongolian first.** Longest strings: «Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна» (62), «Шороон замаас зайлсхийх тохиргоог унтрааж дахин оролдоно уу» (58), «Хамгийн ойрын зам сонгосон цэгээс 1,4 км зайтай» (47), «Энэ зай явганаар эсвэл дугуйгаар хэт хол байна» (46), «Баруун талын гарах зам руу эргэнэ үү» (36, a turn row). All wrap, none is truncated; the prototype checks 320 px in both languages. Only the field texts ellipsise (they are names; the full name is in the card and the marker name). English is longer only for the placeholder "Choose starting point" (21 vs 16) and fits.
- **`en` "Directions"** is both the button (`route.getDirections`) and the turn-list heading (`route.directions`), as the glossary lists; two keys keep them independent.
- **Plural keys:** `.one`/`.other` picked with `Intl.PluralRules(uiLang)`; Mongolian values are identical (same approach as NAV-003 `search.resultCount`).
- **Punctuation** (" · ", ", " in accessible names, the space before «+1 өдөр») is formatting, not a string.

## Accessibility
- **Landmark and heading.** The panel is a `<section>` labelled by its `<h2>` «Маршрут харах»; the turn list has an `<h3>`. Screen-reader users can jump by heading.
- **Tab order while the panel is open** (AC 45): language → theme → compass → **«Хаах» → origin field → destination field → swap → selected mode tab → avoid switch (car only) → «Дахин оролдох» (when shown) → selected route option (k ≥ 2) → turn list (one stop: the last focused row, else the first)** → map canvas → status banner action → location message actions → zoom in → zoom out → my location → attribution link. The hidden search field and card are skipped. The heading is `tabindex="-1"` (focus target only). With a field list open, its options are not Tab stops (combobox `aria-activedescendant`); state rows with «Дахин оролдох» follow the NAV-003 Tab rule (the list stays open so the button can be reached).
- **Turn list keyboard model (design decision).** The list is **one Tab stop** with roving `tabindex`; ArrowUp/ArrowDown/Home/End move between rows. Reason: an intercity route has 100–300 steps, and a Tab stop per row would put hundreds of stops between the panel and the map canvas. Each row is still reachable and activatable (AC 45) and has its own accessible name. Screen-reader browse mode reads the `<ol>` as a normal list ("list, 6 items").
- **Focus management.** Open: origin field if the origin is empty, else the selected mode tab (AC 46). Point set from the coordinate card: that field. Card closed: panel heading. Retry removed: selected mode tab. Any focused element inside the result region that is removed by a new result or state: the selected mode tab. Close: the «Маршрут гаргах» button (AC 41). Focus never lands on `<body>`. Focus is never moved by a route result arriving (the user may be in a field or on a tab).
- **Focus not obscured (WCAG 2.2 SC 2.4.11).** The sticky header can cover rows scrolled under it; every focusable element in the panel uses `scroll-margin-top: 64px` (header height + 8) so `focus()` and Tab scroll it fully into view below the header.
- **Announcements** (polite live region, AC 47): after a result, once: `route.count.*` + ", " + distance + ", " + duration + ", " + «Хүрэх цаг HH:MM» (for example «2 маршрут олдлоо, 4,3 км, 12 мин, Хүрэх цаг 14:35»); after selecting another route: «Маршрут {n}» + ", " + its distance, duration and «Хүрэх цаг». After a state: the state message (with the avoid hint appended for AC 33). Not announced: loading, the ETA refresh, field typing (NAV-003 announces search results itself). State rows are not `role="alert"`.
- **Names and states.** Tabs `aria-selected`; switch `role="switch"` + `aria-checked`; route options `role="radio"` + `aria-checked` + «Өөр маршрут» description; swap `aria-disabled` while a point is missing; retry `aria-disabled` during the 429 wait; result region `aria-busy` while loading; markers `role="img"` with the field text (map-style §7.3).
- **Contrast** (AC 44, WCAG 2.2 AA): all NAV-004 text uses checked pairs: `ui.on-surface`, `ui.on-surface-variant` and `ui.primary` on `ui.surface` and on `ui.surface-container` (fields), `ui.on-primary` on `ui.primary` (filled button, switch handle): all ≥ 4.5:1 in both modes. Non-text: tab indicator and radio `ui.primary` on `ui.surface` ≥ 3:1; switch outline `ui.outline` on `ui.surface` / `ui.surface-container` ≥ 3:1; focus ring ≥ 3:1. Route line ≥ 3:1 on `earth` and `major` (map-style §7.2). All in `tokens.json › contrastPairs`, checked by `check-contrast.mjs`. Disabled controls are exempt but keep their names.
- **Touch targets** (AC 43): every control ≥ 48 px high (fields, tabs, switch row, swap and close 48×48, route options and turn rows ≥ 56, buttons 48). Checked ≥ 44×44 at all five widths by the layout checker.
- **Dynamic type / zoom.** All type in `rem`. At 200 % browser zoom a 1366 px window is 683 CSS px (medium layout, bottom sheet); every rule holds and everything scrolls inside the panel.
- **Reduced motion:** camera jumps (`duration: 0` instead of `motion.route-camera`, AC 17, 30), static spinner, no panel fade.
- **Forced colours:** panel, fields and card get a 1 px `CanvasText` border; the selected tab indicator uses `Highlight`; the switch track keeps its border; icons use `currentColor`.
- **Language of parts:** Cyrillic field texts and street names in the English UI get `lang="mn"`; Latin-only names in the Mongolian UI get `lang="en"` (NAV-003 rule).
- **No time limits:** state rows stay until the next triggering action; the 429 wait only disables the retry button.
- **Driver safety.** The web preview is a planning screen, not for use while driving; it has no speed input. Native previews will block typing while moving (NAV-005). The turn list is not a banner: NAV-005's banner rules (navigation-ux) apply to active guidance only.

## Design notes (decisions made inside the AC)
1. **Instruction text source = ADR-0008 option (b)**, which UX supports: it is the only option that gives exactly the AC 27 texts today (NAV-007 F1, F2, F6, F7 make (a) fail), the same language-neutral key also picks the manoeuvre icon (Content rules › Manoeuvre icons), and NAV-005 can reuse both for Ferrostar banners. The street name stays on its own line, never inside the sentence (ADR-0008 §2), which is also the clearer layout for scanning a list.
2. **Destination marker = NAV-003 pin.** Keeps "one pin" (NAV-003 AC 22) and "exactly one destination marker" (AC 9) true together, and the pin already sits on the card's point when the panel opens.
3. **Preview-time coordinate card does not replace the opening card** (rule 7): closing the preview returns the user to the place they started from (AC 41 focus target exists), as Google Maps does.
4. **No «Маршрут гаргах» on the preview-time coordinate card:** it would do the same as «Очих газар болгох» and add a third button at 320 px.
5. **No clear button in the route fields:** no glossary term fits (the search "Clear search" term describes clearing a query, not removing a route point), and a point that disappears would leave a half-empty request state. Changing a point = picking another one.
6. **Bottom sheet without drag detents on the web.** A fixed 60dvh sheet is simpler to build and test than a draggable sheet; the native apps get detents.

## Known limitations (design, within the AC)
1. **320×568: the summary starts at the bottom edge of the sheet.** The fields, tabs and switch take about 265 px, and the grid leaves the sheet 288 px (R1, R4 and R5 keep their size). The first summary line peeks in and the user scrolls the sheet; screen-reader users hear the summary in the live region (AC 47). At 360×640 and larger the summary is visible without scrolling (measured, Layout rule 9). With NAV-002 messages open (worst case) the summary is below the fold at 320 and 360 px; those messages are transient and closable. A collapsible inputs area (as in Google Maps mobile) is left to the native apps (Components › Native mapping).
2. **Small map band on phones.** Below 840 px the map area above the sheet is about 160 px at 360×640 (the R4 controls sit in it). The route is fitted into it (Camera rules), and the user can pan and zoom. Larger phones (390×844) get about 226 px.
3. **No labels on route lines.** Users match lines to options by colour swatch and by clicking the line; duration callouts on the map (Google Maps) are not in NAV-004.
4. **The panel hides the search field.** To search for another place the user closes the preview (one click, Esc). A new destination can still be searched in the destination field.

## AC traceability
| AC | Where in this spec / flow |
|---|---|
| 1–2 | Components › «Маршрут гаргах» button; Interactions › Open; flow F1 |
| 3–5 | States › No origin yet, Waiting for location; Interactions › Open, Editing a field; Components › Field results list; flow F1, F2 |
| 6 | Components › Origin/destination field, Field results list; Layout rule 6; Interactions › Editing a field; flow F2 |
| 7 | Components › Coordinate card during the preview; Layout rule 7; map-style §7.3 candidate pin; flow F2 |
| 8 | Components › Swap button; Interactions › Swap |
| 9 | Content rules › Field texts; map-style §7.3; Design notes 2 |
| 10, 13, 15 | flow F3 (request body and ordering are behaviour; UX adds the settle and precedence rules) |
| 11 | Components › Mode tabs; Interactions › Mode tabs; `motion.route-mode-settle` |
| 12 | Components › Avoid switch |
| 14 | States › Same start and destination; precedence |
| 16, 18, 20 | map-style §7.2; Components › Route options; Interactions › Route selection; States › Night |
| 17, 30 | Camera rules; `motion.route-camera` |
| 19 | Components › Route options |
| 21 | Components › Snap notice; States |
| 22–25 | Components › Summary; Content rules › Distances, Durations, «Хүрэх цаг» |
| 26–29, 31 | Components › Turn list; Content rules › Street names, Instruction texts, Manoeuvre icons; Copy › Manoeuvre texts; Design notes 1; ADR-0008 |
| 32–39 | States › Result region; precedence; flow F3, F5 |
| 40 | Layout rule 8; States › Tiles unavailable, Language switch |
| 41 | Interactions › Close; Layout rule 4; flow F6 |
| 42 | Layout rule 4 (NAV-002/NAV-003 unchanged while the panel is closed); Recommended DOM structure |
| 43 | Layout, Regions, Layout rules 1–9; Verification |
| 44–47 | Accessibility |
| 48–50 | Copy; States › English UI, Language switch |
| 51–52 | No UI (network hygiene and privacy are behaviour; the panel never shows coordinates in the URL or storage) |
| 53–54 | States › Static public build; flow F7 |

## Verification
- `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav004.mjs`: opens the wireframe at 320×568, 360×640, 768×1024, 1366×768 and 1920×1080, in 24 states (place card and coordinate card with «Маршрут гаргах»; origin empty with the «Миний байршил» list; loading; route with 3, 1 and a long intercity turn list; alternative selected; walk; snap notice; next-day ETA; field list with results and with a NAV-003 state row; no route with and without the avoid hint; outside the area; too far; same point; unavailable; offline; rate-limited; error; static build; coordinate card during the preview) × day/night × mn/en × with/without the NAV-002 worst-case messages = 960 combinations. Checks: no control overlaps another, no panel overlaps another panel or a foreign control, attribution fully visible and uncovered, scale bar uncovered, every control ≥ 44×44, panel heading visible and not truncated, close button visible (sticky header), search input hidden while the panel is open and first Tab stop when it is closed, language button first while the panel is open, no horizontal scroll. Result on 2026-09-30: **0 problems**; INFO lines list where the summary is below the fold (Known limitations 1). It checks the wireframe, not the app; QA's NAV-004 tests check the app.
- `node docs/design/prototypes/check-contrast.mjs`: includes the route line, origin marker, manoeuvre point, candidate pin, switch, field and filled-button pairs, and the new map-style.md §7.2–7.3 colour tables. Result on 2026-09-30: all checks passed.

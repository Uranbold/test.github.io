# Screen: Search, results list, pin and place card (NAV-003, on the NAV-002 web map)

- **Stories:** NAV-003 (AC 1–46). The carried NAV-002 follow-ups are in the NAV-002 spec and `map-style.md`, not here.
- **Platforms:** Web (`web/`, MapLibre GL JS), current desktop Chrome/Edge/Firefox and Android Chrome; automated tests on desktop Chromium (PO decision D14). The later native search (after NAV-005) reuses the layout rules and states; see Components › Native mapping.
- **Base screen:** [`NAV-002-web-map.md`](NAV-002-web-map.md). Everything that this spec does not change stays as specified there (controls, messages, attribution, loading, night, tooltips).
- **Flow:** [`flows/NAV-003-search.md`](../flows/NAV-003-search.md) (F1 typing and request rules, F2 selection and card, F3 coordinate card, F4 request states, F5 keyboard, F6 language switch).
- **Prototype:** [`prototypes/NAV-003-search.html`](../prototypes/NAV-003-search.html), a static wireframe. Switch state, theme, language, NAV-002 worst-case messages and low zoom in the toolbar or the URL hash, for example `#state=coord-unavailable&theme=night&lang=en&extra=worst&zoom=low`. Layout checked by `prototypes/check-layout-nav003.mjs` (see Verification). No Figma file.
- **Tokens:** [`tokens.json`](../tokens.json) v0.2.0 (new for NAV-003: `color.*.pin.*`, `typography.scale.body-large`, `size.search-width`, `size.list-row-min`, `size.pin-width/height`, `breakpoint.medium/expanded`, `motion.search-debounce`, `motion.long-press`). Pin: [`map-style.md` §7.1](../map-style.md).
- **API:** `openapi.yaml` 0.4.1 `search` and `reverse`. No contract change.
- **Revision 2026-09-30 (PO "go ahead", D28, D31–D33):** Tab keeps a state row with «Дахин оролдох» open (F5, AC 41); type-label count 1–32 + 4a (F2); PO decisions on English names (D28), no camera move on right-click / long-press (D31) and viewport centring (D32) recorded in Known limitations.

## Purpose
Let a UB user find a place, street, khoroo, district, town or coordinate by typing Cyrillic or Latin letters, see matching places while typing, and see the chosen place on the map with a pin and a short card. Every failure (nothing found, service down, offline, too many requests) is explained in words.

## Layout

The NAV-002 overlay grid gains two rows: **RS** (search panel) under the top bar and **RC** (card row) above the attribution. The map canvas is unchanged (full viewport); every NAV-003 element sits in the grid, so nothing is ever positioned over the attribution, the scale bar or a NAV-002 control (AC 23, 39).

```
Compact < 600 px (320×568, mn, day)          Medium 600–839 px (768×1024)                    Expanded ≥ 840 px (1366×768)
Typing: results list open                    Card open (bottom-left)                         Card open (left panel)
┌──────────────────────────────┐             ┌──────────────────────────────────────────┐    ┌─────────────────────────────────────────────────────────┐
│┌────────────────────────────┐│ R1 line 1   │┌──────────────────┐  [Монгол][☾][◈]     │ R1 │┌──────────────────┐                 [English][☾][◈]     │ R1
││🔍 Сүхб                  [×]││             ││🔍 Sukhb       [×]│                      │    ││🔍 Сүхбаатарын т [×]│                                     │
│└────────────────────────────┘│             │└──────────────────┘                      │    │└──────────────────┘                                     │
│         [English][☾][◈]      │ R1 line 2   │   (status banner / location message, R2) │ R2 │┌──────────────────┐                                     │ RS
│┌────────────────────────────┐│ RS          │                                          │    ││Сүхбаатарын    [×]│                                     │
││📍 Сүхбаатарын талбай       ││             │                                          │    ││талбай            │                                     │
││   Талбай · Бага тойрог, УБ ││             │                 (map)                    │ R3 ││Талбай · Бага ... │                                     │
││                            ││             │                    📍                     │    ││47.91881, 106.9169│         (map)                       │
││📍 Сүхбаатар дүүрэг         ││             │                                          │    │└──────────────────┘            📍                       │
││   Дүүрэг · Улаанбаатар     ││ (scrolls)   │                                   [+]    │ R4 │   (status banner / location message, R2)                 │
│└────────────────────────────┘│             │ ├────┤ 1 km                       [−]    │    │                                                   [+]   │
│                        [+]   │ R4          │                                   [◎]    │    │                                                   [−]   │
│                        [−]   │             │┌──────────────────┐                      │ RC │ ├────┤ 1 км                                       [◎]   │
│ ├────┤ 1 км            [◎]   │             ││Sukhbaatar Sq. [×]│                      │    │                                                         │
├──────────────────────────────┤             ││Square · Baga ... │                      │    │                                                         │
│ © OpenStreetMap contributors │ R5          │└──────────────────┘                      │    │                                                         │
└──────────────────────────────┘             ├──────────────────────────────────────────┤    ├─────────────────────────────────────────────────────────┤
                                             │ © OpenStreetMap contributors             │ R5 │ © OpenStreetMap contributors                            │
                                             └──────────────────────────────────────────┘    └─────────────────────────────────────────────────────────┘
```
(Icons in the drawing stand for 24 px SVG icons; the prototype shows them.)

### Regions (overlay grid rows, top → bottom)
`grid-template-rows: auto minmax(0,auto) minmax(0,auto) minmax(0,1fr) auto minmax(0,auto) auto`

| Row | Content | Sizing |
|---|---|---|
| R1 Top bar | **Search field** (left) and the NAV-002 cluster Language, Theme, Compass (right, gap 8, unchanged). Compact: search field on line 1 (full width), cluster on line 2 (right-aligned), `flex-wrap: wrap`, row gap 8. Medium and expanded: one line, search field `flex: 0 1 size.search-width` (400 px), cluster pushed right. | `auto`. Padding as NAV-002: top 12 + safe area, sides 16, bottom 8. Height 68 px (one line) or 124 px (compact) |
| RS Search panel | **Results list** (popup) directly under the search field, same left edge and width (compact: full width; ≥ 600: max 400 px). Expanded only: also the **place card** (never at the same time as the list, see rule 5). | `minmax(0, auto)`, sides 16 px, the panel has an 8 px bottom margin. Shrinks and scrolls inside the panel when space runs out |
| R2 Messages | NAV-002 status banner and location message, unchanged. | `minmax(0, auto)` (NAV-002) |
| R3 Spacer | Map shows through. NAV-002 blocking layer spans R2–R4 (grid rows 3–5), never RS or RC. | `minmax(0, 1fr)` |
| R4 Bottom controls | NAV-002 scale bar, zoom group, my location, unchanged. | `auto`. Bottom padding 20 px (NAV-002 attribution hit area), or **8 px while the card is in RC** (the card, not R4, is then next to R5) |
| RC Card row | Compact and medium only: the **place card**, bottom-left, width as the search field. | `minmax(0, auto)`. Padding 0 16 px **20 px** (keeps the NAV-002 attribution-link hit area clear, NAV-002 rule 6a). Card `max-height: 40dvh`, scrolls inside |
| R5 Attribution | Unchanged, never covered. | `auto` |

### Layout rules (AC 20, 21, 23, 39; NAV-002 rules 1–8 still apply)
1. **Every NAV-003 element lives in its own grid cell.** The results list, the place card and the pin can never cover the attribution, the scale bar or a NAV-002 control (AC 23). Only RS, R2 and RC may shrink below their content (`min-height: 0`) and then scroll inside; R1, R4 and R5 keep their size, so controls and the attribution never leave the viewport.
2. **Breakpoints** (`tokens.json › breakpoint`): < 600 px compact, 600–839 px medium, ≥ 840 px expanded (Material 3 window classes).
3. **Card position.** Compact and medium: RC (bottom, like a Google Maps bottom sheet). Expanded: RS, under the search field (like the Google Maps side panel). The breakpoint `expanded` must stay > 2 × (16 + 400) = 832 px: then a pin at the viewport centre (AC 20) is always right of the panel. Below 840 px the card is at the bottom, and its `max-height: 40dvh` keeps its top below the viewport centre on every AC 23 viewport (checked, see Verification).
4. **The card is one element.** Only its `grid-row` changes with the breakpoint (6 → 2), so its DOM position, focus order and state never change on resize.
5. **List over card.** While the results popup is open (options or a state row), the place card is **hidden, not closed** (at every width). The pin stays. When the popup closes without a selection (Esc, Tab from a list of options, click on the map, see Interactions › Tab for the state-row exception), the card shows again. Reason: at 320×568 the list and a card cannot both fit, and a user who types is looking at the list.
6. **Messages.** NAV-003 state messages appear only inside the results list or the card (AC 39). NAV-002 banners keep their row; the list pushes them down, never covers them.
7. **Markers are below the UI.** The pin is a MapLibre HTML marker inside the map container, which is below the overlay grid in the stacking order. The UI can cover the pin, never the reverse (AC 23).
8. **Search is available in every NAV-002 state**, including loading, the blocking card and offline, like the language and theme buttons. The coordinate card (right-click / long-press) needs a ready map.
9. **Measured fit** (prototype, 320×568, worst case: tiles banner + location-denied message + card, or zoom < 8 with the 4-line ESA credit + coordinate card): all rows fit; R2 and the card scroll inside themselves. At compact with the list open, 2–3 options are visible at 320×568 and the list scrolls; at 360×640 about 4.

### Recommended DOM structure (so the search field is the first Tab stop, AC 1)
NAV-002 puts `#map` before the overlay grid, which makes the map canvas the first Tab stop. NAV-003 AC 1 needs the search field first. Recommended (used in the prototype):
- Move `#map` **into** the overlay grid as a grid item: `grid-row: 1 / -1; grid-column: 1; position: relative; z-index: 0; contain: strict; pointer-events: auto`. `contain: strict` stops the canvas size from influencing the grid tracks. Every other grid item gets `position: relative; z-index: 1` and keeps `pointer-events: none` on the row container and `auto` on its children, as in NAV-002. Only RS, R2 and RC get `min-height: 0`; giving it to every item would let R4 shrink under its buttons (seen in the prototype).
- DOM order inside the grid: R1 (search field, then the language/theme/compass cluster) → RS (results popup) → card → `#map` → R2 → blocking layer → R4 → R5. The Tab order then follows the visual reading order: the top bar left to right, the search panel / card, the map, then the NAV-002 rows below.
- The mobile-engineer may choose another structure if the Tab order and the layout rules hold.

## Components
| Component | Source | Spec |
|---|---|---|
| Search field | Custom, Material 3 **search bar** (docked) styling, on `ui.surface` | 48 px high, `radius.md`, elevation 1, no border (forced colours: 1 px `CanvasText`). Leading search icon 24 px (decorative, `aria-hidden`), 12 px from the left edge. Input: `typography.body-large` 16/24 in `ui.on-surface`, placeholder `search.placeholder` in `ui.on-surface-variant` (opacity 1), `text-overflow: ellipsis` for the placeholder (English is 234 px of 236 px available at 320 px). Trailing clear button. Focus ring (2 px `ui.focus-ring`, offset 2) is drawn around the **whole field** (`:focus-within`), the input itself has no outline. No separate search button: Enter (or the phone keyboard's search key, `enterkeyhint="search"`) searches (AC 41). |
| Input attributes | — | `type="text"` (not `search`: Chrome's own clear icon would duplicate ours), `role="combobox"`, `aria-label` = `search.label`, `aria-autocomplete="list"`, `aria-expanded`, `aria-controls="search-results"`, `aria-activedescendant` (highlighted option), `maxlength="200"` (AC 2), `autocomplete="off"`, `autocapitalize="off"`, `autocorrect="off"`, `spellcheck="false"`, `enterkeyhint="search"`. No `lang` attribute (the text can be either script). Test id `search-input`. |
| Clear button | Custom icon button inside the field | 48×48, transparent, icon × 24 px `ui.on-surface-variant`. `aria-label` and tooltip `search.clear` «Хайлтыг арилгах». Visible only while the input has text (AC 31). Press: empties the input, closes the list, aborts any request, focus back to the input. Does not close an open card. Test id `search-clear`. |
| Results popup ("list area") | Custom, Material 3 **menu / search view** list styling | `ui.surface`, `radius.md`, elevation 2, padding 8 px 0, `overflow-y: auto` (grid row RS limits its height). Contains the listbox and the state row; exactly one of them is visible (loading: see States). Test id `search-popup`. Closes on Esc, a click on the map (MapLibre `click`, which does not fire after a drag), selection, or empty input, and on Tab **except** while it shows a state row with «Дахин оролдох» (unavailable, rate-limited; AC 41, PO approval F5, D33), see Interactions › Tab. |
| Listbox | `<ul role="listbox">` | `id="search-results"`, `aria-label` = `search.results` «Хайлтын илэрц» (AC 40). Hidden when a state row shows (an empty listbox fails axe). `aria-busy="true"` while the loading row shows (AC 13). Options are not focusable; the highlight is `aria-activedescendant`. Test id `search-results`. |
| Result option | `<li role="option">` | Min height `size.list-row-min` 56 px (≥ 44 AC 23), padding 8 16 8 12, gap 16. Leading place icon 24 px `ui.on-surface-variant`. **Line 1 name** (`body-large`, `ui.on-surface`), max **2 lines** then ellipsis (`line-clamp: 2`). **Line 2** type label, then " · " and the context line if any (`body` 14/20, `ui.on-surface-variant`), max 2 lines. The " · " separator is CSS (`::before`), not text in resources. Hover: `ui.state-hover` background. **Highlighted** (`aria-selected="true"`): `ui.state-hover` background **plus** a 2 px inset outline in `ui.focus-ring` (visible "virtual focus"). Accessible name = name, type label, context line joined with ", " (for example «Сүхбаатарын талбай, Талбай, Бага тойрог, Улаанбаатар»). Test id `search-option`. |
| Coordinate option | Same as result option | Leading crosshair-dot icon. Line 1 `place.selectedPoint` «Сонгосон цэг», line 2 the normalised coordinates «47.91890, 106.91760» (5 decimals, point decimal, AC 26). |
| State row | Custom, inside the popup | Min height 56 px, grid: icon 24 · message (`body` 14/20 `ui.on-surface`, wraps, never truncated) · optional text button `action.retry` (48 px high, `ui.primary`). Below 480 px width the button moves to its own line, right-aligned (NAV-002 rule 5). The retry button has `aria-describedby` → the message text, so a screen reader reaching it with Tab hears «Дахин оролдох, Хайлт түр ажиллахгүй байна» (it is reached after the top-bar cluster, away from the message context). Variants in States. Test id `search-state`, `data-state` = `loading|no-results|unavailable|offline|rate-limited|error` (QA hook, not user-facing). Retry test id `search-retry`. |
| Live region (search) | Visually hidden `<div aria-live="polite">` | Outside the popup, always in the DOM. Gets one text per settled query: the count, «Илэрц олдсонгүй», or the state message (AC 42). Not updated for the loading row (avoids chatter), not on every keystroke. Test id `search-live`. |
| Place card | Custom, Material 3 **card** (elevated) | `<section aria-labelledby>` on `ui.surface`, `radius.md`, elevation 2, width as the search field, padding 4 4 16 16. **Header:** heading `<h2 tabindex="-1">` (`typography.title` 16/24 weight 600, `ui.on-surface`, wraps, **never truncated**) + close button (48×48 icon button, `action.close` «Хаах», tooltip). **Meta line:** type label · context line (`body`, `ui.on-surface-variant`). **Coordinates:** «47.91881, 106.91690» (`body`, tabular numbers, `ui.on-surface-variant`, selectable text so users can copy it). Compact/medium `max-height: 40dvh`, scrolls inside. Enters with a 150 ms fade (`motion.duration-short`), none with reduced motion. The card has no action buttons in NAV-003; «Маршрут гаргах» (Get directions) is added below the coordinates by NAV-004. Test id `place-card`, heading `place-card-title`, close `place-card-close`. |
| Coordinate card | Same card | Heading `place.selectedPoint` «Сонгосон цэг», then coordinates, then a divider (1 px `ui.outline-variant`) and the **nearest-place area** (`aria-live="polite"`, min height 44 px so the card does not jump): label `place.nearest` «Ойролцоох газар» (`caption` 12/16 weight 500, `ui.on-surface-variant`), then the nearest place's name (`body-large`), then type label · context line (`body`). While pending or failed, a state row replaces name and meta (same variants as the list, icon + text + optional retry). Test ids `place-nearest`, `place-retry`. |
| Pin | MapLibre `Marker` with a custom HTML element | 28×40 px teardrop, anchor `bottom` (tip on the point, ±2 px AC 21). Colours `pin.fill`, 2 px `pin.stroke`, head dot `pin.center` (map-style.md §7.1). `role="img"`, `aria-label` = result name or «Сонгосон цэг» (AC 21, 25). Not focusable (the card is the interactive part). One pin at most (AC 22). Stays at its geographic position while panning/zooming (AC 24). An HTML marker survives the theme switch (`setStyle`) without re-adding; its colours come from CSS custom properties generated from `tokens.json › color.<mode>.pin` (like `--loc-*` today). Test id `place-pin`. |
| Tooltip | NAV-002 tooltip | Clear button and card close button only. Shows below the clear button; it may overlap the popup for the moment it is shown (it is transient and hoverable). |
| Ferrostar components | **Not used** | Ferrostar has no search UI. NAV-003 is custom, on the one MapLibre `Map` that NAV-002 owns. |
| Native mapping (later, not NAV-003) | Material 3 `SearchBar` + `SearchView` (Compose), SwiftUI `.searchable` | Same states, copy and result anatomy. Card = Material 3 bottom sheet / iOS sheet. **Driver safety:** on phones, typing is blocked while the vehicle moves (NAV-005 navigation-ux rules); NAV-003 is a desktop/web demo with no speed input. |

### Result content rules (AC 17–19, 45)
- **Name:** `name`; else `street` + " " + `housenumber`; else the type label (AC 17).
- **Type label:** the story's table "Type labels" (33 ordered rules: 1–32 plus 4a, first match wins; rules 1–4 match Cyrillic and Latin name endings, 4a and 5–31 read Photon's `osm_key` / `osm_value` / `type`, PO approval F2, D33), localised; the same row in both UI languages (AC 19). Resource keys in Copy.
- **Context line:** first 2 distinct, non-empty values of `district`, `locality`, `city`, `county`, `state`, skipping values equal to the name, joined with ", ". Omitted if empty (no empty line, no "undefined").
- **Traditional Mongolian script (AC 18):** UX confirms the BA default. Characters U+1800–U+18AF and the whitespace around them are removed from every displayed value; if nothing remains, the next field is used. Reason: the system font stack has no reliable glyphs (tofu), and vertical script cannot be set inline in a one-line row.
- **Language of parts:** a name or context value written in Cyrillic gets `lang="mn"` when the UI is English, and a Latin-only value gets `lang="en"` when the UI is Mongolian, so screen readers switch voice. (Detection by script: any character in U+0400–U+04FF = Cyrillic.)
- **Coordinates:** `lat, lon` with 5 decimals and a point decimal in both languages (AC 21). This deliberately differs from glossary C3 (decimal comma for distances in Mongolian): coordinates are a machine format users copy.
- **Order:** `MN` results first, otherwise the upstream order (AC 8). At most 10 options (AC 7).
- Match highlighting (bold matched letters) is **not** in NAV-003: with transliteration and abbreviation expansion the matched part is often not the typed text.

### Camera rules (AC 20, 25, 26)
- **Point result (no `extent`):** `flyTo` the point at the **viewport centre** (±5 px; not the centre of the area left uncovered by the top bar and the card, PO decision D32), zoom 13 for type labels «Хот», «Суурин», «Дүүрэг», «Хороо», «Хороолол», «Аймаг», «Сум», zoom 16 for all others. The zoom follows the type-label row, so it is the same in both UI languages (AC 20). Duration `motion.duration-camera` 1000 ms (arrives < 2 s). The layout (rule 3) keeps the centre visible on every AC 23 viewport.
- **Result with `extent`:** `fitBounds` with padding **40 px plus the UI that covers that edge**: top = R1 height; left = 16 + 400 px when the card is in the left panel (expanded); bottom = R5 height + (card height + 20 px when the card is in RC). Max zoom 17. ("At least 40 px", AC 20.)
- **Typed coordinates:** centre on the point, zoom 16, or the current zoom if higher (AC 26).
- **Right-click / long-press:** the camera does not move (AC 25, D31; Known limitations 1).
- **Reduced motion:** `jumpTo` / `fitBounds` with `duration: 0`, done within 500 ms (AC 20).
- The pin and the card appear **at selection**, not after the flight, so the user gets instant feedback; they are in place when the camera arrives (AC 21).

## States

### Search field and results list
| State | What the user sees | AC |
|---|---|---|
| **Default / empty** | Field with placeholder «Газар, хаяг хайх», no clear button, list closed, no message, no request. Clearing (× or deleting all text) returns here; an open card stays. | 1, 31 |
| **Typing, < 2 characters** | Text and clear button, list closed, no request. | 4 |
| **Debouncing / pending ≤ 300 ms** | Previous options (if any) stay, no option highlighted; nothing new is shown yet. | 3, 5 |
| **Loading** (pending > 300 ms) | Popup with one row: 20 px indeterminate spinner (static with reduced motion) + «Ачаалж байна…». Listbox `aria-busy="true"`. Gone within 200 ms of the response. No endless spinner: the 8 s client timeout ends it. | 13, 33 |
| **Results** | Up to 10 options (name / type label · context). Live region «5 илэрц олдлоо» / "5 results" / "1 result". | 7, 8, 12, 17–19, 42 |
| **Coordinate typed** | One option «Сонгосон цэг» + «47.91890, 106.91760». No request. | 26 |
| **No results** (200, empty) | State row, search-off icon `ui.on-surface-variant`: «Илэрц олдсонгүй». Not styled as an error. Announced. | 32, 42 |
| **Error: search unavailable** (network, CORS, 502, 503, 504, 8 s timeout, while online) | State row, warning icon `ui.error`: «Хайлт түр ажиллахгүй байна» + «Дахин оролдох» (re-sends the current settled query once). A new keystroke also searches. No automatic retry. | 33 |
| **Error: bad request** (400) | State row, error icon `ui.error`: «Алдаа гарлаа». No retry button (the same request would fail again); the next keystroke searches. | 37 |
| **Offline** | State row, cloud-off icon `ui.on-surface-variant`: «Интернэт холболт алга», no button, no request. NAV-002 offline banner shows as well (NAV-002 AC 43). When back online, the current settled query (≥ 2 characters) is searched once within 2 s by itself. | 34 |
| **Rate-limited** (429, `Retry-After: N`, default 5 s if missing/invalid) | State row, hourglass icon `ui.on-surface-variant`: «Түр хүлээгээд дахин оролдоно уу» + «Дахин оролдох» shown **disabled** (`aria-disabled="true"`, `ui.disabled-content`, stays focusable, no tooltip) for N s. The input keeps accepting text; nothing is sent for N s. After N s the button turns active; **nothing is sent automatically**. No countdown number (no glossary term; not needed). | 35 |
| **GPS lost / stale fix / location permission denied** | No visible change in search. The bias falls back to the map centre; search never waits for or asks for location. NAV-002 location messages behave as before. | 9, edge cases |
| **Tiles unavailable (NAV-002)** | Search works normally; the list sits above the banner row and pushes it down. | 39 |
| **Night** | Same layout, `color.night.ui` tokens, pin night colours. No light surfaces. | 40 |
| **English UI** | English copy and type labels (same type-label row as in the Mongolian UI, AC 19); result names from `lang=en` (English name where OSM has one, else Mongolian). **One name line only**, no Cyrillic second line (D28, Known limitations 5). Map labels stay Cyrillic (D11). | 45 |

State precedence in the list area (only one row at a time): offline > rate-limited > unavailable > bad request > loading > no results / results.

### Place card and coordinate card
| State | What the user sees | AC |
|---|---|---|
| **Result card** | Pin at the feature; card: heading = name, type label · context, coordinates. Input shows the name. Focus on the heading. | 20–22, 43 |
| **Card while the list is open** | Card hidden (not closed), pin stays; card returns when the list closes without a pick. | rule 5 |
| **Coordinate card: pending ≤ 300 ms** | Heading «Сонгосон цэг», coordinates, label «Ойролцоох газар», empty 44 px area. | 25, 27 |
| **Coordinate card: loading** (> 300 ms) | Nearest-place area: spinner + «Ачаалж байна…». | 27 |
| **Coordinate card: nearest place** | «Ойролцоох газар» / name / type label · context. Pin and heading stay at the chosen point. | 28 |
| **Coordinate card: nothing near** (200, empty; for example X2 Beijing, empty steppe) | Nearest-place area: search-off icon + «Илэрц олдсонгүй». Not an error. | 29 |
| **Coordinate card: unavailable** | Warning icon + «Хайлт түр ажиллахгүй байна» + «Дахин оролдох» (re-sends `reverse` once). | 30 |
| **Coordinate card: offline** | Cloud-off icon + «Интернэт холболт алга», no button. If the browser comes back online while the card is open, `reverse` is sent once by itself (same rule as search, AC 34). | 30 |
| **Coordinate card: rate-limited** | Hourglass icon + «Түр хүлээгээд дахин оролдоно уу» + disabled «Дахин оролдох» for N s; after N s nothing is sent until the user presses it or opens another coordinate card. | 36 |
| **Coordinate card: bad request** | Error icon + «Алдаа гарлаа», no retry. | 37 |
| **Language switched with a card open** | Labels, type label and state message switch ≤ 500 ms; the place name stays as it is (no new request for the card). | 38 |

## Interactions
- **Type / paste** in the field: see flow F1. Debounce `motion.search-debounce` 250 ms (allowed 200–350, AC 3). Settled query rules, abbreviation expansion and transliteration: story AC 3–6, 15, 16. The input always shows what the user typed.
- **Focus** on the field alone opens nothing and sends nothing (AC 1). **ArrowDown** with a closed list reopens the last list if it belongs to the current text.
- **Arrow keys** move the highlight, wrapping; focus stays in the input; the highlighted option scrolls into view (`block: nearest`) (AC 41).
- **Enter:** selects the highlighted option; with none, the first option of the current query's list; if that list is not there yet, searches at once (no debounce) and selects its first option when it arrives, unless it is empty or a state (AC 41).
- **Escape:** 1st closes the list (text stays), 2nd clears the input. With focus in the card: closes the card, focus back to the input (AC 22, 41).
- **Tab** from the input (AC 41, amended by PO approval F5, D33):
  - **List of options** (results or the coordinate option), and state rows **without** a button (loading, no results, offline, bad request): Tab closes the popup without selecting; focus goes to the next stop.
  - **State row with «Дахин оролдох»** (unavailable AC 33, rate-limited AC 35): the popup **stays open** so the retry button can be reached with Tab in the order of Accessibility › Tab order (input → clear → language → theme → compass → **retry**). In the rate-limited state the button is reached while `aria-disabled="true"` (focusable, Enter/Space do nothing until the wait ends). Moving focus with Tab or Shift+Tab, including onto the map canvas, does not close it; the place card stays hidden meanwhile (rule 5), so its stops are skipped.
  - Such a state row closes with **Esc** (from the input or from the retry button; focus goes to the input, text stays; a second Esc in the input clears it), a **new settled query**, or **clearing the input** (story AC 41). Design addition, pending AC wording (see handoff): a **click on the map** (MapLibre `click`, which does not fire after a drag, so panning the map keeps the row) and **opening a coordinate card** (right-click / long-press) also close it, because the new card would otherwise stay hidden under rule 5.
  - **Retry pressed:** focus moves to the input at once and the request is sent (states Loading → Results / state row as usual; the live region announces the outcome, AC 42). General rule: whenever the focused retry button is removed (replaced by another state or by options), focus goes to the input, never to `<body>`.
- **Click / tap an option:** selects it (flow F2). Mouse hover only paints the hover background; it does not change `aria-activedescendant`.
- **Click on the map** while the popup is open (options or any state row): closes it (no selection); the map click itself does nothing else.
- **Right-click** on the map canvas (desktop): pin + coordinate card at that point; the browser context menu is suppressed only on the canvas (AC 25). **Long-press** ≥ `motion.long-press` 600 ms without moving > 10 px (touch): the same. A drag, pinch or normal click never opens a card. On Android Chrome, one long-press = one card and one `reverse` request (the `contextmenu` event that Chrome also fires is ignored when a long-press has already been handled).
- **Close card** («Хаах» or Esc in the card): pin and card removed, focus to the input, query text stays (AC 22).
- **Select another result / another coordinate:** replaces pin and card (AC 22).
- **Pan / zoom** with a card open: pin stays at its place, card stays open (AC 24).
- **Language switch:** flow F6 (AC 38). **Theme switch:** pin and card switch colours in the same frame; camera, list and card content unchanged.
- **Optional (not required by AC):** the ContextMenu key or Shift+F10 on the focused map canvas opens a coordinate card at the map centre. The keyboard path the AC require is typed coordinates (AC 26).

## Copy (mn / en)
Every string is a glossary term: existing rows or NAV-003 T1–T31 (added 2026-09-30, `needs native review`). No new term. Keys are a proposal for `web/src/i18n/{mn,en}.json` (mobile-engineer owns them); both files get the same keys (NAV-002 AC 32).

| Key | mn | en | Glossary source |
|---|---|---|---|
| `search.label` (input accessible name) | Хайх | Search | Search |
| `search.placeholder` | Газар, хаяг хайх | Search for a place or address | Search (placeholder) |
| `search.clear` | Хайлтыг арилгах | Clear search | T1 Clear search |
| `search.results` (listbox name) | Хайлтын илэрц | Search results | T2 Search results |
| `search.resultCount.one` | {count} илэрц олдлоо | 1 result | T3 Results count (en singular) |
| `search.resultCount.other` | {count} илэрц олдлоо | {count} results | T3 Results count |
| `search.noResults` | Илэрц олдсонгүй | No results found | No results |
| `search.unavailable` | Хайлт түр ажиллахгүй байна | Search is temporarily unavailable | T4 Search unavailable |
| `search.rateLimited` | Түр хүлээгээд дахин оролдоно уу | Too many searches. Wait a moment and try again | T5 Too many requests |
| `place.selectedPoint` | Сонгосон цэг | Selected point | T6 Selected point |
| `place.nearest` | Ойролцоох газар | Nearest place | T7 Nearest place |
| `status.loading` (reused) | Ачаалж байна… | Loading… | Loading (G1) |
| `status.offline` (reused) | Интернэт холболт алга | No internet connection | No connection |
| `status.genericError` (reused) | Алдаа гарлаа | Something went wrong | Generic error |
| `action.retry` (reused) | Дахин оролдох | Try again | Try again |
| `action.close` (reused) | Хаах | Close | Close (dismiss) (G6) |

Type labels (story table "Type labels", rule order in brackets):

| Key | mn | en | Glossary source |
|---|---|---|---|
| `placeType.district` (1, 4a) | Дүүрэг | District | Düüreg |
| `placeType.khoroo` (2) | Хороо | Khoroo | Khoroo |
| `placeType.aimag` (3) | Аймаг | Aimag | Aimag |
| `placeType.soum` (4) | Сум | Soum | Soum |
| `placeType.city` (5) | Хот | City or town | T8 |
| `placeType.settlement` (6) | Суурин | Settlement | T9 |
| `placeType.neighbourhood` (7) | Хороолол | Neighbourhood | T10 |
| `placeType.square` (8) | Талбай | Square | T11 |
| `placeType.fuel` (9) | ШТС | Petrol station | Petrol station (screen form) |
| `placeType.hospital` (10) | Эмнэлэг | Hospital or clinic | T12 |
| `placeType.pharmacy` (11) | Эмийн сан | Pharmacy | T13 |
| `placeType.school` (12) | Сургууль | School | T14 |
| `placeType.university` (13) | Их сургууль | University or college | T15 |
| `placeType.restaurant` (14) | Хоолны газар | Restaurant or café | T16 |
| `placeType.hotel` (15) | Зочид буудал | Hotel | T17 |
| `placeType.mall` (16) | Худалдааны төв | Shopping centre | T19 |
| `placeType.shop` (17) | Дэлгүүр | Shop | T18 |
| `placeType.market` (18) | Зах | Market | T20 |
| `placeType.bank` (19) | Банк | Bank or ATM | T21 |
| `placeType.busStop` (20) | Автобусны буудал | Bus stop | T22 |
| `placeType.railwayStation` (21) | Галт тэрэгний буудал | Railway station | T23 |
| `placeType.airport` (22) | Нисэх онгоцны буудал | Airport | T24 |
| `placeType.parking` (23) | Зогсоол | Parking | T25 |
| `placeType.museum` (24) | Музей | Museum | T26 |
| `placeType.historic` (25) | Дурсгалт газар | Monument or historic site | T27 |
| `placeType.worship` (26) | Сүм хийд | Place of worship | T28 |
| `placeType.park` (27) | Цэцэрлэгт хүрээлэн | Park | T29 |
| `placeType.government` (28) | Төрийн байгууллага | Government office | T30 |
| `placeType.embassy` (29) | Элчин сайдын яам | Embassy | T31 |
| `placeType.road` (30) | Зам | Road | Street / avenue / road |
| `placeType.address` (31) | Хаяг | Address | Address |
| `placeType.place` (32) | Газар | Place | Place (POI) |

Notes
- **`{count}`** stays literally in both resource files (AC 44). The client picks `.one` or `.other` with `Intl.PluralRules(uiLang)`. Mongolian has one form, so both `mn` values are identical; this keeps the key sets equal.
- **Capital letter:** the glossary lists «газар», «хаяг», «зам», «дүүрэг», «хороо», «аймаг», «сум» in lower case; as stand-alone labels they start with a capital letter, as the story writes them. The `check:glossary` comparison already treats the first letter case-insensitively (NAV-002 spec, Copy notes).
- **Length (Cyrillic first, then checked in English):** longest in Mongolian: «Хайлт түр ажиллахгүй байна» (26), «Түр хүлээгээд дахин оролдоно уу» (31), «Нисэх онгоцны буудал» (20). Here English is often longer ("Too many searches. Wait a moment and try again", 46; placeholder 29 vs 16). Every message wraps; no message text is truncated. Only result names and result meta lines clamp at 2 lines (the card shows the full text).
- **Not used and not needed:** «Энд юу байна?» (glossary "Reverse geocoding" UI action): the coordinate card opens directly on right-click / long-press, with no context menu. «Маршрут гаргах» comes with NAV-004.
- Punctuation (" · ", ", ") is formatting, not a string.

## Accessibility
- **Combobox pattern** (WAI-ARIA 1.2, AC 40–41): input `role="combobox"` + `aria-expanded` (true only while options are shown) + `aria-controls` → listbox `aria-label` «Хайлтын илэрц» + `aria-activedescendant` → highlighted option `aria-selected="true"`. The listbox is `hidden` while a state row shows. axe: 0 serious/critical in every state.
- **Tab order** (AC 1, 43; changes NAV-002 AC 48's first stops): **search input → clear (when shown)** → language → theme → compass → **results-list retry (when a state row shows it) → place card close → place card retry (when shown) → map canvas** → status banner action → location message actions → zoom in → zoom out → my location → attribution link. Ready state with no text and no card: search input, language, theme, compass, map canvas, zoom in, zoom out, my location, attribution. The NAV-002 AC 48 test expects the map canvas first; QA updates that expectation (the story allows it for Tab-order changes). The card heading is `tabindex="-1"` (focus target only, not a Tab stop). The results list has no Tab stops. The results-list retry is reachable only because a state row with «Дахин оролдох» stays open on Tab (AC 41 exception, D33, Interactions › Tab); while it is open the card is hidden, so the next stop after the retry is the map canvas.
- **Focus management:** selecting a result or opening a coordinate card moves focus to the card heading (AC 43). Closing the card returns focus to the input (AC 22). Clearing keeps focus in the input (AC 31). A right-click card also moves focus to the heading (the user can Esc back). Focus is never lost to `<body>`: if the focused element hides (card hidden by rule 5), focus is already in the input; if a focused results-list retry is removed, focus goes to the input (Interactions › Tab). A focused card retry (`place-retry`) that is replaced by the nearest place or another state moves focus to the card heading.
- **Announcements:** search live region (polite) once per settled query (AC 42); nearest-place area `aria-live="polite"` so the reverse result is read after the heading. State rows are not `role="alert"` (they are expected outcomes of typing, not interruptions).
- **Contrast** (AC 40, WCAG 2.2 AA): all NAV-003 text uses existing checked pairs: `ui.on-surface` / `ui.on-surface-variant` / `ui.primary` on `ui.surface` (≥ 4.5:1 in both modes). Icons `ui.on-surface-variant` and `ui.error` on `ui.surface` ≥ 3:1. Highlight outline `ui.focus-ring` on `ui.surface` ≥ 3:1. Pin `pin.fill` ≥ 3:1 on earth, major roads and water in both modes (tokens `contrastPairs`, checker). Disabled retry is exempt (inactive control) but keeps its accessible name.
- **Touch targets** (AC 23): field 48 px high, clear and close 48×48, options ≥ 56 px high and full width, retry 48 px high. Gaps ≥ 8 px to NAV-002 controls.
- **Dynamic type / zoom:** all type in `rem`. At 200 % browser zoom a 1366 px window is 683 CSS px (medium layout) and every rule holds; long names wrap, the card and the list scroll.
- **Reduced motion:** camera jumps (AC 20), static spinners, no card fade.
- **Forced colours:** field, popup and card get a 1 px `CanvasText` border; icons use `currentColor`; the highlighted option keeps its outline (`Highlight`).
- **Language of parts:** see Result content rules.
- **No time limits:** state rows stay until the next query; the 429 wait only disables the retry button, it does not dismiss anything.
- **Driver safety:** the web demo is not for use while driving. Nothing in NAV-003 needs typing while moving on a phone; native apps will block typing while moving (NAV-005).

## Known limitations (design, within the AC)
1. **Right-click / long-press near the bottom on a phone:** the card opens in RC and can cover the chosen point, because AC 25 says the camera does not move. Google Maps pans the map in this case. **PO decision D31 (2026-09-30):** keep AC 25 and accept this limitation for NAV-003; a "smart pan" (move the map only when the card would cover the point) is a later item (story › Out of scope).
2. **Short landscape viewports** (height < about 480 px, for example a phone turned sideways, compact or medium layout): after a point result the viewport centre can be under the bottom card. The five AC 23 viewports are not affected (checked). **PO decision D32 (2026-09-30):** point results stay centred in the **viewport** (AC 20), not in the uncovered map area; centring in the uncovered area can come as a change request for the native apps (ADR-0006 §5).
3. **NAV-002 messages can cover the pin** (for example the tiles banner and the location message together at 320×568). They are transient and closable; NAV-002 precedence is unchanged.
4. **Compact list height:** at 320×568 only 2–3 options fit above the NAV-002 bottom controls (the list scrolls). Covering the controls is not allowed (AC 23).
5. **English UI shows one name only** (PO decision D28, 2026-09-30, AC 45): with `lang=en` the list and card show the English name ("Sukhbaatar Square") while the map label stays Cyrillic «Сүхбаатарын талбай» (D11). No Cyrillic second line in NAV-003; it comes in a later tourist story together with two-line map labels. The result option anatomy (line 1 name, max 2 lines) leaves room for it then.

## AC traceability
| AC | Where in this spec / flow |
|---|---|
| 1 | Components › Search field, Input attributes; Accessibility › Tab order; Recommended DOM structure |
| 2 | Input attributes (`maxlength`) |
| 3–6, 9, 11, 15, 16, 46 | Interactions › Type; flow F1 (request rules are behaviour for mobile/architect; UX adds no rule) |
| 7, 8 | Result content rules › Order |
| 10, 12, 14 | Data and timing (no UI rule); States › Results, Loading |
| 13 | States › Loading; Listbox `aria-busy` |
| 17–19 | Result content rules; Copy › Type labels |
| 20 | Camera rules (D32); Layout rule 3; Known limitations 2 |
| 21, 22, 24 | Components › Place card, Pin; Interactions; flow F2 |
| 23 | Layout rules 1–9; Verification |
| 25–30 | Components › Coordinate card; States › Place card and coordinate card; Camera rules; Known limitations 1 (D31); flow F3 |
| 31–37 | States › Search field and results list; flow F4 |
| 38 | States (language rows); flow F6 |
| 39 | Layout rules 6, 8; States › Tiles unavailable |
| 40–43 | Accessibility; Components › Listbox, Result option, Live region, State row; Interactions › Tab (AC 41 state-row exception, D33 F5); flow F5 |
| 44 | Copy |
| 45 | States › English UI; Copy; Known limitations 5 (D28) |

## Verification
- `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav003.mjs`: opens the wireframe at 320×568, 360×640, 768×1024, 1366×768 and 1920×1080, in 21 NAV-003 states × day/night × mn/en × with/without the NAV-002 worst-case messages (× low zoom for coordinate cards), 1,120 combinations. Checks: no control overlaps another, no panel (list, card, banner, message) overlaps another panel or a foreign control, attribution fully visible and uncovered, scale bar uncovered, every control ≥ 44×44, search input visible, ≥ 120 px wide and first in focus order, pin at the viewport centre not covered by NAV-003 UI or controls whenever the camera centred on it, card heading never truncated, no horizontal scroll. It checks the wireframe, not the app; QA's NAV-003 tests check the app.
- `node docs/design/prototypes/check-contrast.mjs`: includes the new pin pairs.

# Screen: Web demo mode — route picker, simulated guidance and arrival (NAV-017)

- **Stories:** NAV-017 (AC 1–49; UI-relevant AC 8–41, 43). Traceability per AC at the end. PO decisions D71–D75 (2026-10-01); demo folder public without a password with `noindex` (D107, 2026-10-03; D17 does not cover the demo, D116, 2026-10-04).
- **Platforms:** Web (`web/`, MapLibre GL JS), **demo-mode build only**. Target: **iPhone Safari, current major iOS** (D72), plus the NAV-002 browser set. Automated tests: desktop Chromium and, where the container can run it, Playwright WebKit with iPhone descriptors (AC 47). The rest of the web demo stays as specified (D14).
- **Base screens:** [`NAV-002-web-map.md`](NAV-002-web-map.md) (map, NAV-002 controls, messages, attribution, static demo) and [`NAV-004-route-preview.md`](NAV-004-route-preview.md) (route slot, sheet, route line and markers, content rules). Guidance rules: [`navigation-ux.md`](../navigation-ux.md) §2–4, §7–8 and **§11 (web demo-mode replay, new in v0.5)**. Everything this spec does not change stays as specified there.
- **Flow:** [`flows/NAV-017-web-demo-mode.md`](../flows/NAV-017-web-demo-mode.md) (F0 hosting, F1 open, F2 pick, F3 start, F4 replay states, F5 voice and chime, F6 arrival, F7 language, theme, rotation, network).
- **Prototype:** [`prototypes/NAV-017-demo-mode.html`](../prototypes/NAV-017-demo-mode.html), a static wireframe of every state below in CSS px, day/night, mn/en, text zoom 100/200 %, with simulated safe-area insets. Hash example: `#state=worst&theme=night&lang=mn&tz=2&insets=0,0,34,0`. Checked by `prototypes/check-layout-nav017.mjs` (Verification). No Figma file.
- **Tokens:** [`tokens.json`](../tokens.json) **v0.5.0**. New for NAV-017: `color.*.demo.badge`, `demo.on-badge`, `demo.badge-outline` and 4 `contrastPairs`. Everything else reuses NAV-002/004/005 tokens (`nav.*` banner and puck, `route.*`, `ui.*`). Map layers: [`map-style.md` §7.5](../map-style.md).
- **API:** none, except the basemap archive (`getBasemapPmtiles`, from the page origin or the public tile path, architect's decision on story R6). 0 `search`, `reverse` or `route` requests (AC 11, 42).
- **Instruction and voice text:** ADR-0008 client templates (`web/src/route/instructions.ts`, unchanged) for the banner; the navigation-ux §4.1 voice generator ported from Android (AC 24). Valhalla text is never shown or spoken (AC 18).

## Purpose
Let the PO (and the people the PO shares the link with) open the demo page on an iPhone (a public folder with `noindex`, no password: D107, D116), pick one of three recorded Ulaanbaatar routes and watch the guidance the product will give, exactly as a driver would see and hear it: a moving puck, the Mongolian banner and distance, voice or a chime, recenter and arrival. A visible «Туршилтын горим» badge makes clear that the position is simulated.

## Screens in this spec
| ID | Screen / surface | Pattern | Main AC |
|---|---|---|---|
| D1 | Route picker over the map | NAV-004 route slot: bottom sheet below 840 px, side panel from 840 px; Material 3 radio list | 8–11 |
| D2 | Guidance (replay) | NAV-005 S5 guidance layout, web version | 12–31, 37–41 |
| D3 | Arrival | D2 with the arrival panel | 32–34 |
| — | NAV-002 states before the picker (loading, tiles unavailable, offline) | NAV-002, unchanged | 8, 40 |

There is no password prompt: the demo folder is public, and `noindex` (AC 3) plus the «Туршилтын горим» badge (AC 14) mark it as a test (D107, D116). The host's own error pages are not our UI.

## Layout

The page is **one screen** (no routes, no URL changes). The MapLibre canvas fills the viewport; all UI sits in the NAV-002 overlay grid (`pointer-events: none` on the grid, `auto` on its children). Two arrangements of that grid exist: **picker** (D1) and **guidance** (D2/D3).

**Viewport rules for iPhone Safari (all states):**
- The app container is sized to the **visible** viewport: `position: fixed; inset: 0` (or `height: 100dvh`). **Never `100vh`**: on iOS it includes the area under Safari's toolbar, so the attribution, «Эхлэх» and «Дуусгах» would sit under the toolbar.
- The demo-mode build's `index.html` uses `<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">`, added by the demo-mode build only (like the `noindex` meta, AC 3), so the public build is unchanged. With `cover`, the map runs under the notch and the home indicator, and **every UI region adds `env(safe-area-inset-*)`** on the edges it touches (top: R1 / RB; bottom: R5; left and right: every region in landscape). Nothing interactive is ever inside an inset (AC 37). If the architect prefers to keep the default viewport, Safari letterboxes the page instead; the rules below still hold with insets of 0. **No `maximum-scale` or `user-scalable=no`** (WCAG 1.4.4).
- `html { -webkit-text-size-adjust: 100%; text-size-adjust: 100%; }` so iOS does not inflate the text in landscape (the layout below is measured without inflation).
- `html, body { overscroll-behavior: none; }` so a drag does not rubber-band the whole page.
- Overlay regions (banner, demo row, panels, sheet) get `touch-action: pan-x pan-y`: a two-finger pinch that starts on them does not zoom the page by accident. Safari's text-size setting still works.
- All type is in `rem` (text zoom = root font size). "Text zoom 200 %" in this spec and the checker = the root font size doubled. The guidance overlay keeps the navigation-ux §2.5 caps (1.3, banner distance 1.2) as `min(<rem value>, <px cap>)`.

### D1 picker (portrait 390×844, mn, day; landscape 844×390)
```
Portrait < 600 px: bottom sheet in the route slot          Landscape ≥ 840 px: side panel (column 1, 432 px + left inset)
┌──────────────────────────────┐                           ┌──────────────────────┬─────────────────────────────────────┐
│        [English] [☾] [◈]     │ R1 (no search field)      │┌────────────────────┐│                [English] [☾] [◈]    │ R1
│ (NAV-002 messages, if any)   │ R2                        ││Туршилтын горим     ││  (messages, column 2)               │ R2
│                              │                           ││Маршрут сонгох      ││                                     │
│     (map, route of the       │ R3                        │├────────────────────┤│        (map with the route)         │
│      selected entry)   [+]   │                           ││◉ ○ Сүхбаатарын тал.││                                     │
│ ├────┤ 1 км            [−]   │ R4 (no my-location)       ││  📍 Зайсан толгой   ││                               [+]   │
│┌────────────────────────────┐│ RC route slot             ││  🚗 Машин · 6,1 км ·││                               [−]   │
││Туршилтын горим             ││  heading (pinned)         ││  (list scrolls)    ││ ├────┤ 1 км                         │ R4
││Маршрут сонгох              ││  list label               │├────────────────────┤│                                     │
│├────────────────────────────┤│                           ││ⓘ Алдаа гарлаа      ││                                     │
││◉ ○ Сүхбаатарын талбай      ││  entry R1 (selected)      ││     [Дахин оролдох]││                                     │
││  📍 Зайсан толгой           ││                           ││[▶     Эхлэх      ] ││                                     │
││  🚗 Машин · 6,1 км · 9 мин  ││                           │└────────────────────┘│                                     │
│├────────────────────────────┤│                           ├──────────────────────┴─────────────────────────────────────┤
││◯ ○ Сүхбаатарын талбай      ││  entry R2                 │ © OpenStreetMap contributors                               │ R5
││  📍 Улсын их дэлгүүр        ││                           └────────────────────────────────────────────────────────────┘
││  🚶 Явган · 1,2 км · 15 мин ││
│├────────────────────────────┤│
││◯ ○ Сонгосон цэг … (R3)     ││  entry R3
│├────────────────────────────┤│
││ (state row, if any)        ││  footer (pinned):
││[▶        Эхлэх           ] ││  state row + «Эхлэх»
│└────────────────────────────┘│
├──────────────────────────────┤
│ © OpenStreetMap contributors │ R5
└──────────────────────────────┘
```
(Names, distances and times are placeholders; the real values come from the demo route manifest. Icons stand for 16–24 px SVG icons; the prototype draws them.)

### D2 guidance and D3 arrival (portrait 390×844)
```
D2 guidance (following)                  D2 first 8 s, no Mongolian voice, offline   D3 arrival
┌──────────────────────────────┐ RB      ┌──────────────────────────────┐           ┌──────────────────────────────┐
│┌────────────────────────────┐│         │┌────────────────────────────┐│           │┌────────────────────────────┐│
││┌───┐ 300 м                 ││         ││┌───┐ 1,2 км                ││           ││┌───┐ Таны очих газар баруун ││
│││ ↱ │ Баруун тийш эргэнэ үү ││         │││ ↱ │ Баруун талын гарах    ││           │││ ⚑ │ талд байна             ││
││└───┘ Энхтайвны өргөн чөлөө ││         ││└───┘ зам руу эргэнэ үү     ││           ││└───┘                       ││
│└────────────────────────────┘│         │└────────────────────────────┘│           │└────────────────────────────┘│
│[⚗ Туршилтын горим] [English][☾]│ RD    │[⚗ Туршилтын горим] [English][☾]│         │[⚗ Туршилтын горим] [English][☾]│
│                              │ map     │                              │           │                              │
│            ▲ puck at 70 %    │ band    │[◎ Байршил руу буцах]   ▲     │           │             ▲                │
│                              │         │┌────────────────────────────┐│ RN        │                              │
│                              │         ││ⓘ Энэ утсанд монгол дуут    ││ message   │                              │
│                              │         ││  заавар ажиллахгүй байна.  ││ stack     │                              │
│                              │         ││  Заавар зөвхөн дэлгэцэнд …  ││           │                              │
│                              │         ││ⓘ Интернэт холболт алга      ││           │                              │
│┌────────────────────────────┐│ RP      │└────────────────────────────┘│           │┌────────────────────────────┐│
││Хүрэх цаг 14:35     [🔊] (✕)││         │┌──── progress panel ────────┐│           ││⚑ Зайсан толгой     [ Хаах ]││
││9 мин · 6,1 км              ││         │└────────────────────────────┘│           │└────────────────────────────┘│
│└────────────────────────────┘│         │                              │           │                              │
├──────────────────────────────┤ R5      ├──────────────────────────────┤           ├──────────────────────────────┤
│ © OpenStreetMap contributors │         │ © OpenStreetMap contributors │           │ © OpenStreetMap contributors │
└──────────────────────────────┘         └──────────────────────────────┘           └──────────────────────────────┘
```
((✕) is the round «Дуусгах» button, [🔊] the voice button «Дууг хаах», ⚗ the badge's decorative icon, [◈] the NAV-002 compass.)

### D2 guidance, landscape (844×390, notch insets 47 px left and right, 21 px bottom)
```
┌──47──┬────────────────────────────┬───────────────────────────────────────────────┬──47──┐
│      │┌──────────────────────────┐│                                               │      │
│      ││┌───┐ 300 м               ││            (map, route)  ▲                    │      │
│      │││ ↱ │ Баруун талын гарах  ││                                               │      │
│      ││└───┘ зам руу эргэнэ үү   ││                                               │      │
│      │└──────────────────────────┘│ [◎ Байршил руу буцах]                         │      │
│      │[⚗ Туршилтын горим][English][☾]┌─────────────────────────────────────────┐   │      │
│      │┌──────────────────────────┐││ Хүрэх цаг 14:35                [🔊] (✕) │   │      │
│      ││ⓘ A1 notice / offline     │││ 9 мин · 6,1 км                          │   │      │
│      │└── left column scrolls ───┘│└─────────────────────────────────────────┘   │      │
├──────┴────────────────────────────┴───────────────────────────────────────────────┴──────┤
│      © OpenStreetMap contributors                                                        │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```
Left column width = `clamp(320px, 40%, 400px)` + the left inset: banner, demo row and message stack. The map spans the whole width behind it; the camera padding treats the column as covered. The progress or arrival panel and the recenter button sit in the map column. **Column arrangement** is used when the viewport is ≥ 840 px wide, or landscape with a height below 500 px (any phone in landscape). Otherwise the portrait stack.

### Regions
**D1 picker.** The NAV-002/NAV-003/NAV-004 overlay grid is reused unchanged. Rows: R1 (NAV-002 cluster only, right-aligned: language, theme, compass), R2 (NAV-002 messages), R3 (spacer), R4 (scale bar left; zoom group right; **no** my-location button), RC (the route slot holds the picker), R5 (attribution). The route slot follows NAV-004 › Regions exactly: compact < 600 px grid row 6, padding `0 16px 20px` (the 20 px keeps the attribution link's hit area clear, NAV-002 rule 6a); medium 600–839 px the same with `max-width: 400px`; expanded ≥ 840 px column 1 (`size.side-panel` 432 px), rows R1–RC, padding `12px + safe area` top, 16 px sides, 20 px bottom, and every other grid item in column 2. Sheet `max-height` **60dvh** below 840 px.

**D2 / D3 guidance.**
| Region | Content | Portrait stack | Column arrangement |
|---|---|---|---|
| **RB** banner | NAV-005 banner card (manoeuvre or arrival variant) | Top, 8 px + top inset from the edge, 8 px sides (+ side insets). The top area (RB + RD) is at most **50 %** of the viewport height and scrolls inside beyond that | Top of the left column |
| **RD** demo row | Badge «Туршилтын горим» (left) · language button · theme button (right) | 8 px below RB, same 8 px side margins, one row (`flex-wrap: nowrap`), min height 48 px | Below RB in the left column |
| map band | Map, route, puck | Everything between RD and RN/RP | Right of the left column |
| **Recenter** | Extended FAB «Байршил руу буцах», only while not following | Bottom-left of the band, 16 px from the left (+ inset), 12 px above RN/RP; max width = band width − 32 px; the label wraps, never truncated | Bottom-left of the map column |
| **RN** message stack | One card holding up to two messages: A1 (8 s, once per replay) and the offline indicator | Directly above RP, 8 px gap | Below RD in the left column |
| **RP** progress / arrival panel | Progress panel (ETA, remaining, voice button, «Дуусгах»), or the arrival panel | Bottom, 8 px sides, **20 px** above R5 (attribution hit area) | Bottom of the map column |
| **R5** attribution | NAV-002 strip | Bottom edge, bottom padding = bottom inset | Full width |

### Layout rules (AC 14, 22, 29, 37, 38, 40)
1. **The attribution is never covered** (NAV-002 rule 1, story AC 37): R5 is its own grid row in both arrangements, in every state, theme, language, orientation and text zoom. The link keeps its 44 px hit area (NAV-002 rule 6a); RP and the picker sheet stay 20 px above R5 so the hit area never reaches a control.
2. **Nothing interactive under a safe-area inset** (AC 37). Each region adds the insets of the edges it touches. In landscape the left column starts after the left inset, and the map column's controls end before the right inset.
3. **The badge is visible for the whole replay** (AC 14): in RD, from «Эхлэх» until the picker returns (also on the arrival panel), never inside the banner, the progress or arrival panel, or R5. In D1 the picker heading «Туршилтын горим» carries the same label, so exactly one W1 label is on screen at any time (Design notes 5).
4. **The puck is never under UI** (AC 22): camera padding = covered edges + 16 px (RB + RD at the top; RN + RP at the bottom; the left column in the column arrangement). The puck sits at **70 %** of the uncovered height, horizontally centred in it. Padding is recomputed when RN appears or disappears, when the banner changes height and on rotation. The recenter button only shows while the camera is not following, so it is not part of the padding.
5. **One message stack, above the progress panel.** The A1 notice and the offline indicator share **one** card in RN (A1 first, offline second), not two separate floating messages. Measured at 375×548 and 100 % in the worst case: two separate cards (offline at the top as on Android, A1 above the panel) left a 60 px map band; one card saves the second card's padding and frame and leaves 80 px. Every AC 37 viewport keeps ≥ 77 px at 200 % (Verification). The card never covers the banner, RD, RP or R5 (AC 29, 40). In the column arrangement the stack moves to the left column, below RD.
6. **Minimum map band** (portrait, from the bottom of RD to the top of RN/RP): **≥ 150 px at text zoom 100 %** and **≥ 80 px at 200 %** on every AC 37 portrait viewport; while A1 shows, **≥ 80 px** at 100 % and **≥ 64 px** at 200 %. The band must also hold the recenter button (56 px + 12 px + 8 px) when it shows. Ways the layout keeps it: the overlay text caps (navigation-ux §2.5); the web stacked banner (navigation-ux §11.3: 48 px icon and 12 px vertical padding from text zoom 115 %); the one-card message stack (rule 5); the RD row never wraps (the badge text wraps inside the pill instead).
7. **Banner instruction** wraps up to 3 lines at word boundaries, never truncated (AC 37); the street name is one line with an ellipsis (a name, not an instruction; the full name is in the accessible text).
8. **Picker sheet:** the heading and the footer («Эхлэх» and the state row) are pinned; only the list scrolls between them. If even the pinned parts do not fit (text zoom 200 % on a very short viewport), the whole sheet scrolls. The sheet never covers R5, R4 or R1 (NAV-004 rule 1).
9. **Controls never overlap** each other or another region; every control is ≥ 48 × 48 CSS px (AC 38 requires ≥ 44).
10. **Theme, language and rotation keep the state** (AC 31, 41): one DOM tree, regions re-arranged by CSS, nothing re-created that holds replay state.

### Recommended DOM structure (Tab order = DOM order)
D1: as NAV-004 (`#ui`: R1 cluster → route slot with the picker `<section>` → `#map` → R2 → blocking layer → R4 → R5). D2/D3: RB banner → RD (badge, language, theme) → recenter → RN message stack → RP (progress text, voice, «Дуусгах» / arrival panel) → `#map` → R5. The demo-mode modules live in their own files (for example `web/src/demo/**`) and are not bundled into the public or normal builds (AC 4). The mobile engineer may choose another structure if the Tab order and the layout rules hold.

## Components

### Ferrostar: reuse and customise
| Ferrostar part | Decision | Why |
|---|---|---|
| Ferrostar Web UI components (`ferrostar-map`, banner / instructions view, trip progress view, its MapLibre wrapper) | **Not used** | They render Valhalla's text and English library strings (AC 18–19), expect a live route provider and device location (AC 14, 42), and do not have our banner variants, caps, badge or chime. Same decision as Android (ADR-0009 §1) |
| Ferrostar core (Rust via WASM: snapping, step advance, trip progress numbers) | **Architect's call** | It could drive the replay with the simulated fixes. The UI below does not depend on it: it needs the snapped position, the current step, the distance to the next manoeuvre and the remaining distance and duration (AC 13, 17, 21) |
| Spoken-instruction observer, route provider, reroute | **Not used** | Voice text and schedule are ours (navigation-ux §4, §11); no reroute in a replay |

### Component specs
| Component | Source | Spec |
|---|---|---|
| **Picker sheet** (D1) | Custom, NAV-004 route panel container | `<section aria-labelledby="demo-heading">` on `ui.surface`, `radius.md` 8 px, elevation 2 (night: 1 px `ui.outline-variant`), width 100 % (max 400 px), flex column: heading, list area (`overflow-y: auto; overscroll-behavior: contain`), footer. Test id `demo-picker`. |
| Picker heading | Custom | `<h2 id="demo-heading" tabindex="-1">` `demo.mode` «Туршилтын горим» (`typography.title` 16/24 600, `ui.on-surface`, wraps), padding 12 16 0. **No close button**: the picker is the home of the demo. Test id `demo-heading`. |
| List label | Custom | Visible text `route.options` «Маршрут сонгох» (`typography.label` 14/20 500, `ui.on-surface-variant`), padding 0 16 4, `id` used by the list's `aria-labelledby`. |
| **Route list** (AC 8, 9) | Custom, Material 3 **radio list** (as NAV-004 route options) | `role="radiogroup"` `aria-labelledby` = the list label. Exactly three `role="radio"` rows (R1, R2, R3, manifest order). Row: grid 20 px radio mark · 16 px gap · text block; min height 56 px, padding 8 16, 1 px `ui.outline-variant` top border. Text block: line 1 origin name with a 12 px ring icon (`ui.on-surface`), line 2 destination name with a 12 px pin icon (`pin.fill`), both `typography.body-large` 16/24, wrapping (names are never truncated here: the PO must be able to read them); line 3 meta `typography.body` 14/20 `ui.on-surface-variant`, tabular figures: mode icon 16 px + `route.mode.car` «Машин» or `route.mode.walk` «Явган» + " · " + distance (NAV-004 AC 23 format) + " · " + duration (NAV-004 AC 24 format), for example «Машин · 6,1 км · 9 мин». Radio mark: unselected ring `ui.on-surface-variant`; selected ring and dot `ui.primary`, `aria-checked="true"`. Hover `ui.state-hover`. Roving `tabindex` (selected row 0, or the first row when none is selected). Names carry `lang="mn"` in the English UI. Accessible name = visually hidden `route.origin` «Эхлэх цэг» + ", " + origin + ", " + visually hidden `route.destination` «Очих газар» + ", " + destination + ", " + meta. Test id `demo-route`, `data-route="R1|R2|R3"`. |
| **Footer** (pinned) | Custom | Top border 1 px `ui.outline-variant`. Holds the state row (when any) and «Эхлэх». |
| State row (D1) | NAV-004 state row | Min height 56 px, padding 8 16: icon 24 · message (`body`, wraps) · optional text button `action.retry` «Дахин оролдох» (48 px; at the end of the line from a 480 px viewport at 100 % text zoom, otherwise on its own line, right-aligned; NAV-002 rule 5). `role="status"`. Variants: loading (20 px spinner, static with reduced motion) «Ачаалж байна…» after 300 ms; error (error icon `ui.error`) «Алдаа гарлаа» + «Дахин оролдох»; offline (cloud-off icon) «Интернэт холболт алга», no button. The row is **above «Эхлэх»**, outside the scrolling list, so it is always visible next to the button it explains (Design notes 8). Test id `demo-state`, `data-state="loading|error|offline"`; retry `demo-retry`. |
| **«Эхлэх»** (AC 9, 12, 27) | Material 3 filled button | Full width, **56 px**, `radius.full`, play icon + `nav.start` «Эхлэх» (`typography.label` at 16 px), `ui.primary` / `ui.on-primary`. Disabled (`aria-disabled="true"`, `ui.disabled-content` on a 12 % tint, stays focusable) until the selected entry's data are loaded. Disabled again **at the first activation**, so a double tap starts one replay only. The activation handler is where the audio unlock happens (F3). Test id `demo-start`. |
| **Banner** (D2, RB; AC 17–20) | Custom, the NAV-005 NavBanner in CSS px | Card `nav.banner`, `radius.lg` 16 px, elevation 2 (night: 1 px `nav.banner-outline`), padding 16, min height 104 px. **Row layout:** icon 56 px (`nav.on-banner`, decorative, `aria-hidden`) · 12 px · text column: distance `nav-distance` 32/40 700 tabular (`min(2rem, 38.4px)`), instruction `nav-instruction` 24/30 500, ≤ 3 lines, word wrap, no ellipsis (`min(1.5rem, 31.2px)`), street `nav-street` 18/24 `nav.on-banner-variant`, one line with ellipsis, omitted when empty (`min(1.125rem, 23.4px)`). **Stacked layout** from text zoom 115 %: row 1 = icon + distance, then instruction and street at full width; on the web the icon is **48 px** and the vertical padding 12 px (navigation-ux §11.3). Variants: manoeuvre and arrival only (no recalculating variant: no reroute in a replay). No Then strip (story Out of scope). Test ids `demo-nav-banner`, `demo-nav-distance`, `demo-nav-text`, `demo-nav-street`, `data-variant="maneuver|arrival"`. |
| **Badge** (RD; AC 14) | Custom, Material 3 assist-chip look, **not interactive** | Pill, min height 32 px, padding 4 12 4 8, `radius` 16 px, `demo.badge` background, `demo.on-badge` text, 1 px `demo.badge-outline`. Decorative 18 px "science" (flask) icon + `demo.mode` «Туршилтын горим» (`typography.label` 14/20 **600**, capped 1.3). The text may wrap to two lines inside the pill; it is never truncated, and the row does not wrap. Plain text for assistive technology (read in DOM order after the banner), no role. Test id `demo-badge`. |
| Language button, theme button (RD) | NAV-002 components, unchanged | 48 px. Text and icon sizes follow the overlay cap (1.3). Same behaviour and storage as NAV-002 (AC 31, 41). |
| **Recenter** (AC 23) | Material 3 extended FAB | Min 56 px high, `radius` 16 px, `ui.primary-container` / `ui.on-primary-container`, icon `my_location` 24 px + `nav.recenter` «Байршил руу буцах» (`typography.label`, capped 1.3, wraps, never truncated). Appears ≤ 300 ms after a map gesture; disappears when following resumes. Test id `demo-nav-recenter`. |
| **Message stack** (RN; AC 29, 40) | Custom, NAV-002 message look | One card: `ui.message-surface` / `ui.on-message-surface`, 1 px `ui.message-outline`, `radius` 16 px, padding 12 16, elevation 2. Items, 8 px apart, each icon 24 · text (`typography.body` 14/20, capped 1.3, wraps, never truncated), each its own `role="status"` (polite). Order: (1) **A1** `nav.voiceUnavailable` (info icon), shown once per replay for **8 s** (`motion.nav-voice-notice`), or until the item is tapped (the item is a button-like target ≥ 48 px high with the A1 text as its name; tapping dismisses it); (2) **offline** `status.offline` «Интернэт холболт алга» (cloud-off icon), from ≤ 2 s after `offline` until ≤ 2 s after `online`. The card exists only while it has an item. Test ids `demo-nav-messages`, items `data-kind="voice-unavailable|offline"`. |
| **Progress panel** (RP; AC 21, 30, 35) | Custom, the NAV-005 TripProgressPanel in CSS px | Card `ui.surface`, `radius.lg`, elevation 2 (night 1 px `ui.outline-variant`), padding 12 12 12 16, min height 72 px, one row. Text column (fills): line 1 `route.eta` «Хүрэх цаг» + " 14:35" (`title-large` 22/28 500 tabular, capped 1.3; «+1 өдөр» in `ui.on-surface-variant` at 16 px), line 2 remaining time + " · " + remaining distance (`body-large`, `ui.on-surface-variant`), e.g. «9 мин · 6,1 км». Right: **voice button** (standard icon button 48 px, `volume_up` / `volume_off` 24 px, `ui.on-surface`; name and tooltip «Дууг хаах» when on, «Дууг нээх» when muted), then **«Дуусгах»** (round outlined icon button 48 px, `close` icon in `ui.error`, 1 px `ui.outline`, name and tooltip «Дуусгах»). The text column is one accessible text: «Хүрэх цаг 14:35, үлдсэн хугацаа 9 мин, үлдсэн зай 6,1 км» (not live). Values update at least every 5 s; frozen while paused. Test ids `demo-nav-progress`, `demo-nav-eta`, `demo-nav-remaining`, `demo-nav-voice`, `demo-nav-end`. |
| **Arrival panel** (D3, RP; AC 33) | Custom, same card as RP | Flag icon 24 px (decorative) + destination name from the manifest (`title-large`, wraps, `lang="mn"` in the English UI) + filled button `action.close` «Хаах» (48 px, right-aligned). Test id `demo-nav-arrival`. |
| Live region | Visually hidden `<div aria-live="polite">` | Outside the banner, always in the DOM. Receives the new instruction text **once** when the banner's instruction changes (step advance, arrival). Not updated for distance changes, the first banner after «Эхлэх» (focus reads it), a language switch or a theme switch. Test id `demo-nav-live`. |
| Puck, route line, markers | MapLibre (our layers and markers) | [`map-style.md` §7.5](../map-style.md). |
| Attribution strip | NAV-002, unchanged | Every state. |
| **Hidden in the demo-mode build** | — | NAV-003 search field and results, place and coordinate cards (no long-press or right-click card), «Маршрут гаргах», NAV-004 route panel, NAV-002 my-location button and location dot. **Hidden during D2/D3 only:** zoom group, compass, scale bar (pinch, rotate and tilt still work; recenter restores heading-up). Design notes 1–3. |

### Content rules
- **Distances, durations, «Хүрэх цаг»**: NAV-004 AC 23–25 rules (`web/src/route/format.ts`), in the picker meta, the banner and the progress panel. Mongolian decimal comma, English point, no-break space between number and unit.
- **Remaining duration** (AC 21): the not-yet-driven share (by distance) of the current step's recorded `duration` plus the recorded durations of the later steps. «Хүрэх цаг» = device clock + remaining duration, recomputed with it.
- **Banner text**: NAV-004 AC 27 text (ADR-0008, D56) of the upcoming manoeuvre (navigation-ux §2.1: the banner never shows `depart`; the depart text is voice only). Arrival variant texts from the `arrive` modifier.
- **Street names**: `step.name` with U+200B, U+200C, U+200D, U+FEFF removed and trimmed; Traditional Mongolian script removed (NAV-003 rule); never translated (D11); `lang="mn"` in the English UI.
- **Place names in the picker and on the arrival panel** are manifest data (`name:mn` → `name` → `name:en` of the named OSM feature, story › Demo routes), the same Cyrillic text in both UI languages, `lang="mn"`. An end without a named feature shows the glossary term «Сонгосон цэг» (see Open question U1 for the English UI).
- **Coordinates are never shown** anywhere in the demo (AC 43).
- **Manoeuvre icons**: NAV-004 icon table, 56 px in the banner (48 px stacked).

### Camera rules (AC 9, 22, 23)
- **Picker, entry selected** (AC 9): NAV-004 camera rule: `fitBounds` of the route line plus both markers, **max zoom 17**, `motion.route-camera` 700 ms, jump with reduced motion. Padding = 40 px + the UI on that edge (top: R1 and R2; bottom: R5 + sheet + 20 px below 840 px; left: 432 px from 840 px; right: 64 px for the zoom group).
- **«Эхлэх»**: ease to the first fix at the navigation-ux §8 follow pose (heading up, pitch 45°, zoom by speed, puck at 70 % of the uncovered height) in `motion.route-camera` 700 ms; jump with reduced motion.
- **Following** (AC 22): navigation-ux §8 and §11.2. Bearing = course between consecutive fixes, kept below 1 m/s; the camera reaches each new position and bearing within 1 s (`motion.nav-camera-follow`, linear easing so the motion is continuous).
- **Map gesture** (AC 23): following stops at once; «Байршил руу буцах» appears ≤ 300 ms; the replay continues. **Recenter** or **15 s** without a gesture: following resumes ≤ 1 s, heading up, the button disappears.
- **Arrival:** keeps following the (now still) puck until «Хаах».
- **«Дуусгах», «Хаах», track end:** bearing and pitch ease to 0 in `motion.duration-medium` 300 ms, centre and zoom unchanged (jump with reduced motion).

## States

### D1 Route picker
| State | What the user sees | «Эхлэх» | AC |
|---|---|---|---|
| **Before the map is ready** | NAV-002 loading pill (after 300 ms), tiles-unavailable card or offline card. No picker yet. | — | 8; NAV-002 AC 37–45 |
| **Default (nothing selected)** | Within 1 s of the first map `idle`: sheet with «Туршилтын горим», «Маршрут сонгох», R1–R3 with names, mode, distance and duration. Map at P1 z12 (D13). No route on the map. | disabled | 8 |
| **Loading** (> 300 ms) | Selected row checked; footer row «Ачаалж байна…». Previous route already removed. | disabled | 10 |
| **Route shown** | Selected row checked; route line (§7.2 selected tokens), origin marker and destination pin; camera fits the route above the sheet within 1 s. | **enabled** | 9 |
| **Data error** (404, 500, HTML body, unparsable) | Footer row «Алдаа гарлаа» + «Дахин оролдох» within 1 s; the selection stays; no route drawn; map and the other entries work. «Дахин оролдох» loads the file again, once per press. | disabled | 10 |
| **Offline** (selected entry not loaded yet) | Footer row «Интернэт холболт алга» (no button) and the NAV-002 offline banner in R2. Back online: one load by itself. An entry already loaded in this page session draws without a request. | disabled (enabled if already loaded) | 10, 40 |
| **Tiles failing while shown** | NAV-002 banner «Газрын зургийг ачаалж чадсангүй» + «Дахин оролдох» in R2; the picker keeps working; the route draws over the map background. | as above | NAV-002 AC 41–42 |
| **Empty / no result** | Does not occur: the list always has exactly R1–R3 (the manifest is bundled in the build). | — | 8 |
| **GPS lost / permission denied** | Does not occur: the demo-mode build never calls the Geolocation API and shows no my-location button. | — | 14 |
| **Night / English** | Same layout; night tokens; English labels («Demo mode», "Choose a route", "Car", "Walk", "Start"); names stay Cyrillic. | — | 11 |

### D2 Guidance
| State | Banner | RD / RN | Map | Voice | AC |
|---|---|---|---|---|---|
| **Following** (default) | Manoeuvre variant, distance updated on every simulated fix | badge, language, theme | Puck glides along the route, follow camera | Schedule (navigation-ux §4.2, §11.4) | 12–14, 17–22, 26 |
| **First seconds, no usable voice** | unchanged | + A1 in the message stack, 8 s or until tapped | — | one chime per prompt | 27–29 |
| **Not following** | unchanged | unchanged | Camera free; «Байршил руу буцах» bottom-left; back after 15 s | unchanged | 23 |
| **Muted** | unchanged | unchanged | — | 0 utterances, 0 chimes; button «Дууг нээх» | 30 |
| **Offline** | unchanged | + «Интернэт холболт алга» in the message stack | Loaded tiles stay; missing tiles show the map background | unchanged | 40 |
| **Paused (page hidden)** | not visible; values frozen | — | — | stopped; nothing while hidden | 15 |
| **Language switch** | text switches ≤ 1 s, icon and distance stay | labels switch; A1 if newly needed | — | current utterance stops; next prompt in the new language | 31 |
| **Theme switch / rotation** | colours or arrangement switch ≤ 1 s | — | route, puck, camera pose kept | 0 repeated prompts | 41 |
| **Text zoom 200 %** | stacked web layout, instruction ≤ 3 lines | wraps inside the pill and the card | band ≥ 80 px (≥ 64 px with A1) on AC 37 viewports | — | 37 |
| **GPS lost** | Does not occur: the position is simulated; no GPS-lost state or message exists in the demo | | | | 14 |
| **No result** | Does not occur: «Эхлэх» is only enabled with loaded data; a track that ends without arrival behaves as «Дуусгах» (picker, no message) | | | | 16 |

### D3 Arrival
Banner arrival variant («Та очих газартаа ирлээ», «Таны очих газар баруун талд байна» / «Таны очих газар зүүн талд байна»); arrival panel with the destination name and «Хаах» in place of the progress panel; badge stays in RD; the message stack keeps the offline indicator if offline (A1 is gone by then or is dismissed with the progress panel); no recenter button; the camera keeps following. Offline, GPS lost, no result: nothing to show (no network or location use).

## Interactions
- **Select an entry** (tap, click, Space on a focused row, ArrowUp/ArrowDown/Left/Right in the radio group): selects and loads it (F2). A newer selection aborts an older load. Selecting the already-selected entry does nothing, except after an error, where it loads again (same as «Дахин оролдох»).
- **«Дахин оролдох»** (picker): one load per press; focus stays on the button until the row changes, then moves to the selected row (never to `<body>`).
- **«Эхлэх»**: F3. Focus moves to the banner instruction (`tabindex="-1"`), so VoiceOver reads the first instruction once.
- **Map gestures during the replay** (pan, pinch, two-finger rotate, two-finger tilt, double-tap zoom): following stops; recenter appears ≤ 300 ms; 0 requests except tiles.
- **«Байршил руу буцах»**: following resumes ≤ 1 s; focus moves to the banner instruction (the button disappears).
- **Voice button**: toggles mute; a current utterance or chime stops ≤ 1 s when muting; stored in `localStorage` (try/catch; unavailable storage = voice on); focus stays on the button and its new name is announced.
- **«Дуусгах»**: ends at once, no confirmation (NAV-005 Design notes 1); picker within 1 s, no entry selected; focus to the picker heading.
- **A1 item tap**: dismisses the A1 notice early (the offline item, if any, stays).
- **Arrival «Хаах»**: picker, no entry selected, no route; focus to the picker heading.
- **Language / theme buttons** (D1 R1, D2 RD): NAV-002 behaviour; during the replay see F7. Focus stays on the button.
- **Page hidden / visible**: F4 Paused (no UI).
- **Browser Back / reload**: leaves or reloads the page; no history entries are added; the next load shows the picker.
- **No text input anywhere** in the demo-mode build (no search field), so no keyboard appears on the iPhone.

**Keyboard (desktop)**
| Key | Where | Result |
|---|---|---|
| Tab / Shift+Tab | page | DOM order (Recommended DOM structure) |
| Arrow keys | route list | move and select, wrapping (loads the entry) |
| Enter / Space | «Эхлэх», buttons, A1 item | activate |
| Arrow keys, `+`/`-` | focused map canvas | MapLibre keyboard handler; during the replay a key that moves the camera stops following (as a gesture) |

## Copy (mn / en)
Every `mn` value is a glossary term. **No new term beyond W1** (glossary §2.3, `needs native review`). Keys are a proposal for `web/src/i18n/mn.json` / `en.json`; the mobile engineer owns the final names. Keys that already exist in the web resources are reused unchanged.

### UI
| Key | mn | en | Glossary source | New? |
|---|---|---|---|---|
| `demo.mode` (picker heading, badge) | Туршилтын горим | Demo mode | W1 | **new** |
| `route.options` (route list name) | Маршрут сонгох | Choose a route | N20 | existing |
| `route.mode.car` / `route.mode.walk` | Машин / Явган | Car / Walk | Travel mode | existing |
| `route.origin` / `route.destination` (hidden prefixes in a row's name) | Эхлэх цэг / Очих газар | Start / Destination | Origin, Destination | existing |
| `nav.start` | Эхлэх | Start | Start navigation | **new key** |
| `nav.end` | Дуусгах | End | End navigation | **new key** |
| `nav.mute` / `nav.unmute` | Дууг хаах / Дууг нээх | Mute / Unmute | Voice on/off, A5 | **new keys** |
| `nav.recenter` | Байршил руу буцах | Back to my location | Recenter (D22: never «Төвлөрүүлэх») | **new key** (the NAV-002 `control.recenter` keeps its `en` "Recenter"; the guidance FAB uses the story's `en` text, as Android) |
| `nav.voiceUnavailable` | Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана. | Mongolian voice guidance is not available on this phone. Instructions are shown on screen only. | A1 | **new key** |
| `nav.remainingTime` / `nav.remainingDistance` (accessible text) | үлдсэн хугацаа / үлдсэн зай | time left / distance left | Remaining time, Remaining distance | **new keys** |
| `route.eta`, `route.nextDay.one/other` | Хүрэх цаг, +{days} өдөр | Arrive at, +{days} day(s) | ETA, N16 | existing |
| `unit.m`, `unit.km`, `unit.h`, `unit.min` | м, км, ц, мин | m, km, h, min | units, N21, N22 | existing |
| `status.loading`, `status.genericError`, `status.offline`, `status.tilesUnavailable` | Ачаалж байна…, Алдаа гарлаа, Интернэт холболт алга, Газрын зургийг ачаалж чадсангүй | Loading…, Something went wrong, No internet connection, The map could not be loaded | G1, Generic error, No connection, G2 | existing |
| `action.retry`, `action.close` | Дахин оролдох, Хаах | Try again, Close | Try again, G6 | existing |
| `place.selectedPoint` | Сонгосон цэг | Selected point | T6 | existing (Open question U1) |
| `language.*`, `theme.*`, `control.northUp`, `control.zoomIn/Out`, `map.label`, `attribution.osm` | as NAV-002 | as NAV-002 | — | existing |

### Banner and voice
- **Banner:** the existing `maneuver.*` keys (NAV-004 › Copy › Manoeuvre texts), including `maneuver.arrive*`. Unchanged.
- **Voice** (port of NAV-005 › Copy › Voice texts; new web keys): `voice.prefixM` (`one`/`other`) «{n} метрт» (A8), `voice.prefixKm` (`one`/`other`) «{n} километрт» (A9), `voice.roundaboutExit` «Тойрогт ороод {ordinal} гарцаар гарна уу» (A10), `voice.ordinal.1` … `voice.ordinal.10` «нэгдүгээр» … «аравдугаар» (C4), `voice.approaching` (`one`/`other`) «{n} метрт очих газартаа хүрнэ» (A11), `voice.continueOn` (`one`/`other`) «{n} километр үргэлжлүүлэн явна уу» (A12), `voice.then` «{first}, дараа нь {second}» (A13). English texts, the plural rule (decided on the formatted number) and the D67 rounded-1000 rule exactly as NAV-005 › Copy › Voice texts; flat `_one` / `_other` keys follow the existing web `route.nextDay.one/other` pattern. The off-route and GPS voice keys are **not** needed (no such states in a replay).

### Length notes (Mongolian first)
Longest strings on D2: A1 (82 characters `mn`, 96 `en`: 3–4 lines in the message stack at 375 px), banner instruction «Баруун талын гарах зам руу эргэнэ үү» (36: 2 lines at every measured viewport and text zoom), «Таны очих газар баруун талд байна» (33), recenter «Байршил руу буцах» (17) vs "Back to my location" (19). The badge «Туршилтын горим» (15) is about 1.7 times as wide as "Demo mode" (9); at 200 % on 375 px it may wrap inside the pill. Picker names are data and wrap.

## Accessibility
- **VoiceOver order** (D2): banner (distance, instruction, street as one group) → badge text → language → theme → recenter (when shown) → message items → progress text → voice → «Дуусгах» → attribution. The map is one node («Газрын зураг»); the puck and markers are not focusable. D1: R1 cluster → picker heading → list label → route rows (one Tab stop, roving) → state row and «Дахин оролдох» → «Эхлэх» → map → R2 → R4 → attribution.
- **Announcements** (AC 38): each new banner instruction is announced **once** through the polite live region; distance updates are not announced; message items are polite `role="status"`; nothing is assertive. The picker's state row is polite.
- **Names:** every control has a glossary name (Copy). Icons are decorative. The language button's name equals its visible text (WCAG 2.5.3).
- **Touch targets:** every control ≥ 48 × 48 CSS px (AC 38 requires ≥ 44), with 8 px between neighbours (gloves in winter); the attribution link keeps its 44 px hit area.
- **Contrast** (WCAG 2.2 AA, `check-contrast.mjs`): banner and panel texts as NAV-005 (checked pairs); badge text 10.3:1 day and 9.4:1 night; badge outline on the map earth 4.3:1 and 3.9:1; every text background is opaque.
- **Text size:** all type in `rem`. Safari's text-size setting (aA menu, text size) scales the UI; the guidance overlay is capped at 1.3 (distance 1.2) for the measured map-band reason in navigation-ux §2.5; the picker, R1, R2 and the attribution scale without a cap.
- **Reduced motion:** camera jumps, the puck jumps from fix to fix (no glide), no banner cross-fade, static spinner.
- **Colour is never the only signal:** the badge has text; muted = different icon and name; the selected route row has a filled radio mark and `aria-checked`.
- **Language of parts:** Cyrillic names and street names carry `lang="mn"` in the English UI; `<html lang>` follows the UI language.
- **Time limits:** only the A1 notice (8 s, ≥ 5 s required, informational; the banner carries the guidance).

## Design notes (decisions inside the AC)
1. **Search, «Маршрут гаргах» and the long-press card are hidden** in the demo-mode build (AC 11 lets UX choose hidden or "as the public static build"). Why: the picker is the only way in; a field that always answers «Хайлт түр ажиллахгүй байна» and a route panel that always answers «Маршрутын үйлчилгээ түр ажиллахгүй байна» would only confuse the reviewers, and on a phone the field would compete with the sheet for space. The NAV-003 card is also opened by a long-press and offers «Маршрут гаргах», so it is hidden as well. 0 `search`, `reverse` and `route` requests either way.
2. **No my-location button and no Geolocation call anywhere in the demo-mode build** (AC 14 requires it during the replay only). Why: a real blue dot next to a simulated puck invites exactly the confusion the badge is there to prevent; no permission prompt in a demo; nothing location-related can reach storage (AC 43).
3. **During the replay, the zoom group, the compass and the scale bar are hidden** (Android S5 precedent, NAV-005 screen spec › Map controls: "Not shown on S5"). Pinch, rotate and tilt still work, and recenter (or 15 s) restores the heading-up camera. They work in the picker as on the public site (AC 11). This needs QA to read AC 11 as the picker context (request in the handoff).
4. **The voice button is in the progress panel**, next to «Дуусгах» (AC 21 lists both in the progress area). There is no separate control pair: the orientation toggle is out of scope.
5. **The badge shows only during the replay**; in the picker the sheet heading is the same W1 label (AC 8). One «Туршилтын горим» on screen at a time.
6. **One message stack above the progress panel** for A1 and the offline indicator (Layout rule 5). Measured, not taste: separate cards (offline at the top as on Android, A1 above the panel) left 60 px of map at 375×548 and 100 % in the worst case, below the 80 px minimum; one card leaves 80 px.
7. **The puck glides between simulated fixes** (AC 13 allows it): it moves along the route line from the previous snapped fix to the new one over the fix interval (1 s at 1 Hz), linear by distance; it never moves backwards along the route (if a snapped fix is behind the shown position, the puck holds). Distances, banners and prompts use the snapped fix, not the animated position. Reduced motion: jump per fix. Why: a 1 Hz jump looks broken next to a camera that eases continuously.
8. **The picker's state row sits above «Эхлэх»** in the pinned footer, not under the list. Measured: under the list it scrolled out of view in landscape (844×390), exactly when the user had just tapped an entry.
9. **«Дуусгах» has no confirmation dialog** and is a round icon button (NAV-005 Design notes 1). Restarting is two taps in the picker.
10. **Language and theme stay reachable during the replay** in the demo row (AC 31, 41): the web has no settings sheet, and they cannot go into the progress panel without pushing «Хүрэх цаг» onto two lines at 375 px.
11. **The visible list label «Маршрут сонгох»** above the rows (AC 8 asks only for the list's name): it tells the PO what to do in the picker, where the heading only says which mode this is.
12. **Web stacked banner** (48 px icon, 12 px vertical padding from text zoom 115 %): the Android stacked layout left 61 px of map at 375×667 and 200 % in English; the web variant leaves 77 px. Row layout (text zoom < 115 %) is unchanged.
13. **After «Дуусгах», «Хаах» or a track end, no entry is selected** (AC 33, 35): the PO picks again; the route data stay cached for the page session, so picking the same entry again draws at once.

## Known limitations (design, within the AC)
1. **Safari with its toolbars shown on a 375 px iPhone (375×548 visible) at 200 % text zoom** is outside the design target: with A1 and/or the offline indicator the map band drops below the minimum (18–70 px), and in the triple worst case (A1 + offline + recenter) the recenter overlaps the banner or the demo row. At 100 % every rule holds there (band ≥ 80 px). Every AC 37 viewport passes at 100 % and 200 %. If the PO's iPhone is a 375 px model and the PO uses large text, scrolling Safari's toolbar away (it collapses on scroll) gives the AC 37 size.
2. **Safari "page zoom"** (aA menu, zoom percentage, unlike text size) shrinks the CSS viewport: at 125 % a 375 px iPhone becomes 300 CSS px wide, which nothing here is designed for. Text size works as designed.
3. **The picker shows 2 of 3 routes without scrolling** on a 375 px iPhone with Safari's toolbars, and in landscape (844×390), 1 of 3 at 844×340; the heading and «Эхлэх» stay visible. 3 of 3 on every other measured portrait viewport at 100 %.
4. **No WebKit in this container:** the checker runs Chromium with iPhone viewport sizes and simulated insets. Real safe areas, the Safari toolbar, the text-size setting and VoiceOver are real-iPhone checks (AC 48).
5. **English UI without an English voice:** chime, no notice (AC 29; the A1 `en` text names Mongolian).
6. **No Then strip** although chained prompts (A13) are spoken: the story puts the strip out of scope; the voice says the pair.
7. **Wide-font stress run** (DejaVu Sans, much wider than SF Pro): 40 problems, all at 200 % text zoom in the A1 / worst-case states on 375 and 390 px phones, plus the offline state at 375×548. Not a design target; recorded so QA knows where the margins are thin.

## Open questions (none blocks the build)
| # | Question | Options | UX recommendation |
|---|---|---|---|
| U1 | An end of a demo route without a named OSM feature: the story says the manifest uses «Сонгосон цэг», and that manifest names show unchanged in the English UI (D11). Should that fallback follow the UI language like NAV-003/NAV-004 («Сонгосон цэг» / "Selected point")? | (a) the manifest stores no name for that end and the UI shows `place.selectedPoint` in the UI language; (b) the manifest stores the Cyrillic text, shown in both languages | **(a)**: «Сонгосон цэг» is a UI term (T6), not a place name, and every other screen translates it. In the Mongolian UI both give the same text |

## AC traceability
| AC | Where |
|---|---|
| 1–7 | No UI. Flow F0; viewport meta added by the demo-mode build only (Layout › Viewport rules) |
| 8 | D1 states › Default; Components › picker heading, list label, route list; Layout rule 3 |
| 9 | D1 states › Route shown; Camera rules; map-style §7.5 |
| 10 | D1 states › Loading, Data error, Offline; Components › state row; flow F2 |
| 11 | Design notes 1–3; Components › Hidden in the demo-mode build |
| 12–16 | Flow F3, F4; Design notes 7; navigation-ux §11 |
| 17–20 | Components › Banner; Content rules; navigation-ux §2–3 |
| 21 | Components › Progress panel; Content rules › Remaining duration |
| 22–23 | Camera rules; Layout rule 4; Components › Recenter; navigation-ux §8, §11.2 |
| 24–31 | Copy › Banner and voice; Components › Message stack, Progress panel (voice button); flow F5; navigation-ux §4, §11.4–11.6 |
| 32–35 | D3; Components › Arrival panel; flow F4, F6; navigation-ux §7 |
| 36 | No UI (README) |
| 37 | Layout (viewport rules, rules 1, 2, 6–9); Verification |
| 38 | Accessibility |
| 39 | No UI (wake lock is silent); flow F3, F4 |
| 40 | D2 states › Offline; Layout rule 5; flow F7 |
| 41 | D2 states › Theme switch / rotation; Layout rule 10; flow F7 |
| 42–44 | Design notes 2; Content rules (no coordinates); flow F2 (page-origin loads only) |
| 45–49 | No UI (verification); Verification below lists the real-iPhone design checks |

## Verification
- `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav017.mjs`: opens the wireframe in Chromium at the four AC 37 viewports (375×667 with no inset; 390×844 and 430×932 with a 34 px home-indicator inset; 844×390 with 47 px notch insets left and right and 21 px at the bottom) and three viewports with Safari's toolbars shown (375×548, 390×664, 844×340 with the notch insets), in 12 states (picker, picker with a route, loading, data error, offline; guidance, longest instruction, A1, not following, offline, worst case = longest instruction + A1 + offline + recenter, arrival) × day/night × mn/en × text zoom 100/200 % = **672 combinations**, with Liberation Sans (Helvetica/Arial metrics, close to SF Pro Text). Checks: attribution fully visible, ≥ 11 px and intersecting nothing; no overlaps between regions or controls; every control ≥ 44 px (48 px reported when smaller); no control inside a simulated safe-area inset; banner instruction ≤ 3 lines and not clipped; control labels not clipped; portrait map band (Layout rule 6); puck centre inside the uncovered map; «Эхлэх» and the picker heading visible without scrolling; no horizontal overflow.
  - **Result 2026-10-01: 0 problems** in the design target; 28 INFO lines for 375×548 at 200 % (Known limitations 1).
  - Longest instruction «Баруун талын гарах зам руу эргэнэ үү»: **2 lines** at every viewport, language and text zoom. Minimum portrait map band: 375×667 **199 px** (100 %) / **77 px** (200 %, worst case); 390×844 362 / 246 px; 430×932 450 / 334 px; 390×664 216 / 100 px; 375×548 80 px (100 %).
  - Iterations the checker forced (Design notes 6, 8, 12): offline indicator moved from the top into one message stack with A1; the demo row stopped wrapping (the badge wraps inside the pill instead); web stacked banner with a 48 px icon; the picker's state row moved into the pinned footer; the picker sheet scrolls as a whole when its pinned parts do not fit; picker rows tightened (8 px vertical padding) so all three routes fit at 375×667.
  - `--wide` (DejaVu Sans stress run): 40 problems, Known limitations 7.
- `node docs/design/prototypes/check-contrast.mjs`: all `contrastPairs` including the 4 new `demo.*` pairs, flavor key parity, map-style.md tables vs tokens.json: all pass.
- **Needs the real iPhone** (to add to the AC 48 checklist; QA owns the list): Safari toolbar collapse and the visible-viewport sizing (no control under the toolbar); safe areas in landscape with `viewport-fit=cover`; Safari text size at 200 % (aA menu); that a pinch starting on the banner does not zoom the page; VoiceOver order and the single announcement per instruction; badge readability in sunlight (day) and at night.

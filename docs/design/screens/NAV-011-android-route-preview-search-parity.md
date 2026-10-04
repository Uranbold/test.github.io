# Screen: Android route preview and search parity (alternatives, «Дугуй», assisted search, nearest place on the card, typing lock)

- **Stories:** NAV-011 (AC 1–47; UI-relevant AC 2–4, 7–24, 31–34, 38, 44). Traceability per AC at the end.
- **Platforms:** **Android only** (Kotlin, Jetpack Compose, Material 3, MapLibre Native). iOS is NAV-015 and reuses this spec with HIG controls.
- **Base spec:** this is a **delta** on [`screens/NAV-005-android-navigation.md`](NAV-005-android-navigation.md). It changes S1 (typing lock), S2 (coordinate card) and S3 (route preview). Everything it does not name (tokens, attribution strip R5, browse overlay D5, location messages S4, guidance S5/S6, settings S7, notification S8, Layout rules 1–5 and 7) stays exactly as NAV-005.
- **Flow:** [`flows/NAV-011-android-route-preview-search-parity.md`](../flows/NAV-011-android-route-preview-search-parity.md) (F1 assisted search, F2 coordinate card, F3 preview, F4 typing lock, F5 «Дугуй» guidance).
- **Rules:** [`navigation-ux.md`](../navigation-ux.md) v0.7 §4.2, §4.4, §8: the new **«Дугуй» column** (AC 26). Map layers: [`map-style.md`](../map-style.md) v0.7 **§7.6** (alternatives on Android, 48 dp tap box).
- **Prototype:** [`prototypes/NAV-011-android-preview.html`](../prototypes/NAV-011-android-preview.html) (1 CSS px = 1 dp; hash example `#state=preview-expanded&theme=night&lang=mn&scale=2`), checked by [`prototypes/check-layout-nav011.mjs`](../prototypes/check-layout-nav011.mjs) (Evidence). No Figma file.
- **Tokens:** [`tokens.json`](../tokens.json) v0.5.0, **unchanged**. Every colour used here already exists with a checked contrast pair (`ui.*`, `ui.message-*`, `route.*`).
- **API:** `openapi.yaml` 0.5.2 `search`, `reverse`, `postRoute`. No contract change for the design. Section A (assistance) looks the same on screen for either ADR option.
- **Change 2026-10-04 (typed coordinates, D140–D146; change request run 2, PO "All as recommended"):** a typed coordinate pair shows one option «Сонгосон цэг» with 0 `search` requests; selecting it closes the field, centres the camera once (camera rule **C1**) and opens the existing coordinate card (web parity with NAV-003 AC 26). It supersedes the D115 "sent as typed" path. New or changed here: Context row, Alternatives 6–7, Layout (S2 coordinate option), Layout rule **P8** (wide-window card column), C1, Components › Coordinate option, States › S2 typed coordinate option, Interactions, Accessibility, Design rationale (typed coordinates), Design notes 10–13, Known limitations 6–8, AC traceability and Evidence. Ferrostar, tokens, copy keys and the API are unchanged.

## Purpose
Bring the web demo's preview and search to the Android app: find a place typed in Cyrillic, Latin or with a district abbreviation; see the nearest named place for a long-pressed point; compare up to three routes and pick one; plan by bike; and stop a driver from typing while the car moves, with a one-tap way out for a passenger.

## Context and goal
| Surface | Primary task | Moment of use and time budget |
|---|---|---|
| S1/S2 search (assisted) | Find the destination on the first try, whatever script or keyboard layout | Parked driver, passenger, pedestrian, planner at home. A minute is fine; a result within 1 s of the settled query |
| S1/S2 typing lock | Not typing while driving; a passenger still types | **Driving at ≥ 15 km/h: one glance.** The driver taps the bar by habit; the card must say in < 1 s why nothing happens and what to do. A passenger needs one tap |
| S2 typed coordinates (D140) | Put a pair a customer sent in a messenger ("47.9189, 106.9176") on the map and route to it | Parked driver or passenger pasting from a messenger: one paste, one tap. **No reading needed to recognise the option** (icon + «Сонгосон цэг»); the camera must show the point without a pan |
| S2 coordinate card (nearest place) | Check that a long-pressed **or typed** point is the place meant (taxi: what the customer said) | Parked or passenger. 2–5 s of reading; «Маршрут гаргах» must not wait |
| S3 route preview (alternatives, «Дугуй») | Check the route, maybe pick another, press «Эхлэх» | Usually parked, sometimes at a red light. **Summary + «Эхлэх» in one glance**; comparing routes takes a few seconds and is optional |

### Alternatives considered
**1. Typing lock presentation (AC 31).**
- (a) **Inline lock card under the search bar** (message look, K1 + K3 + «Хаах» + «Би зорчигч») shown only after a tap. **Chosen.**
- (b) Modal dialog. Rejected: it steals focus, dims the map and needs a dismiss tap from a driver; the story wants the field to show the message.
- (c) Lock state visible on the bar before any tap (lock icon, changed placeholder). Rejected: the bar would change on its own while the car moves and pull the driver's eyes to the screen (Von Restorff working against safety). The lock only matters at the moment of the tap.
- (d) Bar disabled (greyed out). Rejected: a passenger can't find the way out, and "disabled" doesn't explain itself.

**2. How alternatives are shown and chosen (AC 15–18).**
- (a) Alternative lines on the map are tappable. The collapsed sheet shows the **selected** route's summary. A radio list «Маршрут сонгох» is in the expanded part of the sheet. **Chosen.**
- (b) The route list is the summary, always visible in the collapsed sheet. Rejected after measuring: with *k* = 3 the collapsed sheet grows to about 390 dp at 360×640, which leaves about 150 dp of map. After the 40 dp camera padding on each side (AC 16), about 70 dp remain to show three routes.
- (c) Duration callouts on the lines (Google Maps). Deferred: label collision handling, and no precedent in NAV-004 (its Design note 3).
- (d) A chip row ("Маршрут 1 · 13 мин"). Rejected: three Mongolian chips don't fit at 200 % and would wrap into a second row.

**3. Where «Эхлэх» sits in the draggable sheet (AC 18, D55).**
- (a) Directly under the summary, so it moves up when the sheet expands (Google Maps). Rejected: a moving primary target is easy to miss, and when expanded it ends up mid-screen, out of thumb reach.
- (b) **Pinned at the bottom of the sheet in both states, as in NAV-005 Layout rule 6.** **Chosen** (Fitts's law: it stays at the thumb and never moves). The prototype tried (a) first; the switch to (b) is recorded in Evidence.

**4. Landscape preview.**
- (a) Bottom sheet, as in portrait. Rejected: at 640×360 the app area is 312 dp and the collapsed sheet is about 270 dp, which leaves about 40 dp of map, so AC 16 cannot be met.
- (b) **Side sheet at the start edge** for wide windows (the NAV-005 D5 class: width ≥ 600 dp, or landscape with width ≥ 480 dp), the same pattern as NAV-004's left column. **Chosen.**

**5. Nearest place on the card (AC 8–11).**
- (a) **Area between the coordinates and «Маршрут гаргах»**. The card is bottom-anchored, so the area grows upwards and the button never moves. **Chosen.**
- (b) Under the button (NAV-003 web order). Rejected: on a bottom-anchored card the button would jump up when the result arrives, 300 ms to 8 s after the user started reaching for it.

**6. How a typed coordinate pair is offered (AC 7, D140).**
- (a) **One list option «Сонгосон цэг» + the normalised coordinates, selected by a tap**, like the web (NAV-003 AC 26). **Chosen:** same model on both clients (Jakob's law across our own products), the user confirms the parse by seeing the normalised numbers before anything moves, and it reuses the results-list row and the existing card.
- (b) Jump straight to the card as soon as the pair settles. Rejected: the map would move while the user is still typing or correcting digits (a half-typed "47.9189, 10" may already be a valid pair), with no chance to check the swap of lat and lon.
- (c) Search the pair as text and add the option on top of the results (Google Maps shows the point first). Rejected: it puts the coordinates back into `q` (AC 39 privacy) and spends a request for results that are almost never useful.

**7. Where the camera puts the typed point, and the card on wide windows (AC 7a, D142).**
- (a) **Centre the point in the map area left free by the top group and the card, with room for the 40 dp pin (camera rule C1); on wide windows the card becomes a start-edge column of the P5 side-sheet width, so the free area is beside it (P8).** **Chosen** after measuring.
- (b) Viewport centre, as the web does for point results (D32). Rejected: on Android the card is bottom-anchored, so the viewport centre lies at or under its top edge (360×640, place state: centre 320 dp, card top 316 dp) and well under it at 640×360.
- (c) C1 with the D5 full-width card on wide windows. Rejected after measuring: at 640×360 the card fills the left column from the search row to R5 (208 dp of 224 dp), so no free band exists and the pin was covered in every theme, language and scale.
- (d) Don't move the camera (as long-press). Rejected by the PO (D142): a typed point is usually off-screen, so the user would see a card for a point they cannot see.

## Layout

### S3 route preview, portrait (360×640 dp, mn, day). Collapsed is the default; TalkBack on opens it expanded
```
Collapsed (k = 3, route 1)                Expanded (route 2 selected)                 «Дугуй», too far
┌──────────────────────────────────┐      ┌──────────────────────────────────┐        ┌──────────────────────────────────┐
│          ┃ alt  ┏━━━ alt          │      │        (map, 3 routes)           │        │          (map, no route)         │
│          ┃      ┃                 │      │┌────────────────────────────────┐│        │                                  │
│   ◉━━━━━━┛━━━━━━┛━━📍  selected   │      ││              ───               ││ handle │┌────────────────────────────────┐│
│  (camera fits all routes + pins  │      ││ Маршрут харах              [✕] ││ head   ││              ───               ││
│   above the sheet, 40 dp pad)    │      ││ Зайсан толгой                  ││        ││ Маршрут харах              [✕] ││
│┌────────────────────────────────┐│      ││ [🚗 Машин][🚶 Явган][🚲 Дугуй] ││ tabs   ││ Зайсан толгой                  ││
││              ───               ││      │├────────────────────────────────┤│        ││ [🚗 Машин][🚶 Явган][🚲 Дугуй] ││
││ Маршрут харах              [✕] ││      ││ Маршрут 2                      ││ summary│├────────────────────────────────┤│
││ Зайсан толгой                  ││      ││ 15 мин · 5,2 км                ││        ││ ⓘ Энэ зай явганаар эсвэл       ││
││ [🚗 Машин][🚶 Явган][🚲 Дугуй] ││      ││ Хүрэх цаг 14:03                ││        ││   дугуйгаар хэт хол байна      ││
│├────────────────────────────────┤│      │├────────────────────────────────┤│ lower  ││                                ││
││ Маршрут 1                      ││      ││ Маршрут сонгох                 ││ (scrolls)│ (switch hidden on «Дугуй»)   ││
││ 13 мин · 4,6 км                ││      ││ ○ ▬ Маршрут 1  13 мин · 4,6 км ││        ││ ◎ Миний байршил                ││
││ Хүрэх цаг 14:03                ││      ││ ● ▬ Маршрут 2  15 мин · 5,2 км ││        ││ 📍 Зайсан толгой               ││
││ [▶          Эхлэх            ] ││      ││ ○ ▬ Маршрут 3 …  (scroll)      ││        ││ [▷   Эхлэх (disabled)        ] ││
│└────────────────────────────────┘│      ││ [▶          Эхлэх            ] ││ pinned │└────────────────────────────────┘│
├──────────────────────────────────┤      │└────────────────────────────────┘│        ├──────────────────────────────────┤
│ © OpenStreetMap contributors     │      │ © OpenStreetMap contributors     │        │ © OpenStreetMap contributors     │
└──────────────────────────────────┘      └──────────────────────────────────┘        └──────────────────────────────────┘
```

### S3 route preview, wide window (landscape 640×360 dp)
```
┌──────────────────────────────┬─────────────────────────────────────────┐
│┌────────────────────────────┐│                                         │
││ Маршрут харах          [✕] ││        (map; camera padding left =      │
││ Зайсан толгой              ││         side sheet width + 40 dp)       │
││ [Машин][Явган][Дугуй]      ││                                         │
││ Маршрут 2 · 15 мин · 5,2 км││          ┏━━━━━━━┓                      │
││ Хүрэх цаг 14:03            ││    ◉━━━━━┛  alt  ┗━━━📍                 │
││ Маршрут сонгох … (scrolls) ││                                         │
││ [▶       Эхлэх           ] ││                                         │
│└────────────────────────────┘│                                         │
├──────────────────────────────┴─────────────────────────────────────────┤
│ © OpenStreetMap contributors                                           │
└────────────────────────────────────────────────────────────────────────┘
```
Side sheet width `clamp(320 dp, 40 %, 400 dp)`, 8 dp margins, full height above R5. Everything except «Эхлэх» scrolls together. «Эхлэх» is pinned at the bottom. There is no handle (nothing to drag).

### S1/S2 typing lock and coordinate card (portrait 360×640 dp)
```
Lock card after a tap                    Lock engaged while typing                Coordinate card, nearest place
┌──────────────────────────────────┐     ┌──────────────────────────────────┐     ┌──────────────────────────────────┐
│┌────────────────────────────┐[⚙] │     │┌────────────────────────────┐[⚙] │     │┌────────────────────────────┐[⚙] │
││🔍 Газар, хаяг хайх          │    │     ││🔍 Sukhbaatar            [✕]│    │     ││🔍 Газар, хаяг хайх          │    │
│└────────────────────────────┘    │     │└────────────────────────────┘    │     │└────────────────────────────┘    │
│┌────────────────────────────────┐│     │┌────────────────────────────────┐│     │                            [+]   │
││🔒 Хөдөлж байх үед бичих        ││     ││🔒 Хөдөлж байх үед бичих        ││     │            (map) 📍        [−]   │
││   боломжгүй                    ││     ││   боломжгүй                    ││     │                            [◎]   │
││   Жолооч бол зогсоод хайна уу  ││     ││   Жолооч бол зогсоод хайна уу  ││     │┌────────────────────────────────┐│
││            [Хаах] [Би зорчигч] ││     ││            [Хаах] [Би зорчигч] ││     ││ Сонгосон цэг               [✕] ││
│└────────────────────────────────┘│     │└────────────────────────────────┘│     ││ 47.88580, 106.91730            ││
│                             [+]  │     │┌────────────────────────────────┐│     ││ ────────────────────────────── ││
│            (map)            [−]  │     ││ Сүхбаатарын талбай             ││     ││ Ойролцоох газар                ││
│                             [◎]  │     ││ Талбай · Сүхбаатар дүүрэг, …   ││     ││ Зайсангийн дурсгалт цогцолбор  ││
│                                  │     ││ Сүхбаатар дүүрэг …  (scrolls)  ││     ││ Дурсгалт газар · Хан-Уул …     ││
│                                  │     │└────────────────────────────────┘│     ││ [⟐      Маршрут гаргах       ] ││
│   (no keyboard)                  │     │   (keyboard hidden, text kept)   │     │└────────────────────────────────┘│
├──────────────────────────────────┤     ├──────────────────────────────────┤     ├──────────────────────────────────┤
│ © OpenStreetMap contributors     │     │ © OpenStreetMap contributors     │     │ © OpenStreetMap contributors     │
└──────────────────────────────────┘     └──────────────────────────────────┘     └──────────────────────────────────┘
```

### S2 typed coordinates (portrait 360×640 dp; D140)
```
Coordinate option (offline)              After selecting it (C1)                  Wide 640×360, after selecting (P8)
┌──────────────────────────────────┐     ┌──────────────────────────────────┐     ┌─────────────────────────────────────────────────────┐
│┌────────────────────────────┐[⚙] │     │┌────────────────────────────┐[⚙] │     │┌───────────────────────────────────────────────┐[⚙] │
││🔍 47.9189, 106.9176     [✕]│    │     ││🔍 47.9189, 106.9176     [✕]│    │     ││🔍 47.9189, 106.9176                        [✕]│    │
│└────────────────────────────┘    │     │└────────────────────────────┘    │     │└───────────────────────────────────────────────┘    │
│┌────────────────────────────────┐│     │      ↕ 16 dp + 40 dp (pin)  [+]  │     │┌──────────────────────────┐                   [+]  │
││ ⌖  Сонгосон цэг                ││     │                             [−]  │     ││ Сонгосон цэг         [✕] │        📍         [−]  │
││    47.91890, 106.91760         ││     │              📍             [◎]  │     ││ 47.91890, 106.91760      │   (point centred  [◎]  │
│└────────────────────────────────┘│     │      ↕ 16 dp (centred)           │     ││ ──────────────────────── │    between card        │
│┌────────────────────────────────┐│     │┌────────────────────────────────┐│     ││ Ачаалж байна…            │    and lane)           │
││ ⦸ Интернэт холболт алга        ││     ││ Сонгосон цэг               [✕] ││     ││ [⟐   Маршрут гаргах    ] │                        │
│└────────────────────────────────┘│     ││ 47.91890, 106.91760            ││     │└──────────────────────────┘                        │
│                             [+]  │     ││ ────────────────────────────── ││     ├─────────────────────────────────────────────────────┤
│   (keyboard hidden or open)  [−] │     ││ Ачаалж байна…                  ││     │ © OpenStreetMap contributors                        │
│                             [◎]  │     ││ [⟐      Маршрут гаргах       ] ││     └─────────────────────────────────────────────────────┘
├──────────────────────────────────┤     │└────────────────────────────────┘│
│ © OpenStreetMap contributors     │     ├──────────────────────────────────┤
└──────────────────────────────────┘     │ © OpenStreetMap contributors     │
                                         └──────────────────────────────────┘
```
⌖ = the existing `ic_location_searching` drawable (D146). The field keeps the typed text after the selection (as after a normal result on Android, and as the web keeps it for the coordinate option).

### Layout rules (new; NAV-005 Layout rules 1–5 and 7 still apply)
- **P1. Sheet regions (portrait).** From top to bottom: handle zone (24 dp, a 32×4 dp handle) → **top part** (header row: «Маршрут харах» plus the destination text on a second line; the tab row) → **summary region** (one of: summary, state row, location message) → **lower part**, expanded only (the «Маршрут сонгох» group when *k* ≥ 2, the avoid switch on «Машин», the points block) → **«Эхлэх» footer**, pinned at the bottom of the sheet in both states. The footer is 56 dp plus 8/12 dp padding, with a 1 dp `ui.outline-variant` top divider while expanded.
- **P2. What never scrolls.** In the collapsed state the summary region and «Эхлэх» never scroll. When space is short (large font scales only), the top part scrolls inside, and may scroll out entirely, before either of them would. In the expanded state the top part, summary and lower part scroll together above the pinned «Эхлэх». At font scale 100 % on ≥ 360×640, nothing in the collapsed sheet scrolls (AC 18, measured).
- **P3. Sheet heights.** Collapsed: content height, at most **60 %** of the area above R5, unless the summary region plus «Эхлэх» alone need more (then the top part scrolls out). Expanded: at most **80 %** (NAV-005 rule 6). The map above the collapsed sheet keeps **≥ 160 dp** at font scale 100 % and **≥ 96 dp** above that on ≥ 360×640 (measured 238 / 208 / 185 dp at 360×640). The camera fit (AC 16) uses the collapsed sheet's height (plus 40 dp padding) on the first render. Expanding the sheet never moves the camera.
- **P4. Two snap states only** (collapsed and expanded), no hidden state; drag or fling between them. The sheet keeps its state across mode changes, new responses, theme, language and rotation (AC 20). Opening the preview starts collapsed. Exceptions: **TalkBack on → expanded**, and the state «Маршрут олдсонгүй» with the avoid hint, which expands the sheet once (250 ms, `motion.duration-medium`) so the switch it names becomes visible. No other state moves the sheet.
- **P5. Wide windows** (NAV-005 D5 class): side sheet as drawn above. It is always "expanded" (no handle). The camera padding on the start side is the sheet width plus 40 dp.
- **P6. Coordinate card.** The order is title row, coordinates, nearest-place area, «Маршрут гаргах». The card is bottom-anchored (NAV-005 D5 narrow and wide arrangements), so the nearest-place area grows upwards and **«Маршрут гаргах» does not move** between the reverse states (measured: identical position across the 7 states, every viewport, language and scale). The nearest-place area reserves `64 dp × font scale` so the common states (loading, place, empty, offline) don't change the card's height.
- **P7. Lock card** sits in R2, directly under the search row, **above** any S1 message and above the results list (it answers the user's own tap). On narrow windows it joins the D5 top group: the group shrinks and scrolls above the map controls, and the controls never overlap it.
- **P8. Coordinate card on wide windows (D140; changes the NAV-005 D5 wide arrangement for the card only).** In the D5 wide class (width ≥ 600 dp, or landscape and ≥ 480 dp) the coordinate card is a **start-edge column**, width `clamp(320 dp, 40 %, 400 dp)` (the P5 side-sheet width, the same column NAV-018 Q6 uses during a preview), bottom-anchored, 8 dp margins. It applies to **both** entry points, so the card looks the same however it opened. The search row, the results and the S1 messages keep the D5 wide arrangement; the control lane stays on the end edge. Short heights: the card still scrolls inside and «Маршрут гаргах» keeps its 48 dp (NAV-005 D5 rule). Measured: «Маршрут гаргах» at the same position in all 7 reverse states, both entry points.
- **C1. Camera for the typed-coordinate option (AC 7a, D142).** One move, at the selection, after the card's first layout (so its height is known); never again while that card is open (AC 9).
  - **Free rectangle:** narrow windows: full map width, from the bottom of the top group (search row, plus any S1 message or lock card still shown after the list closes) to the top of the card. Wide windows (P8): from the card column's end edge to the control lane's start edge, from the bottom of the top group to the top of R5.
  - **Camera padding** inside it: top **16 dp + 40 dp** (the pin is 40 dp tall and stands above the point), bottom, start and end **16 dp**. The point (the pin tip) is the camera target, so it lands in the centre of what is left and the whole pin is clear of the top group, the card and the controls.
  - **Zoom** max(current zoom, 16). Bearing and tilt unchanged. **Duration** 700 ms (`motion.route-camera`, the NAV-018 Q8 "focus a point" move); with animations off (Android animator duration scale 0, reduced motion) a jump.
  - **Following stops** (`followingMe` = false), so the follow effect does not pull the camera back; «Миний байршил» (my location) brings it back as usual.
  - Fallback if the rectangle is smaller than the padding (never in the design target, measured): centre the pin in the rectangle without the 16 dp margins.
  - Long-press keeps "camera does not move" (NAV-005 AC 4, AC 9).

## Components

### Ferrostar
No change from NAV-005 (ADR-0009 §1: only `core` is used). The preview, sheet, tabs, route options, lock card and card are app components. Alternatives are drawn by the app (map-style §7.6), not by Ferrostar's map UI.

### Component specs (new or changed)
| Component | Source | Spec |
|---|---|---|
| **Route preview sheet** (S3, changed) | Custom draggable sheet: M3 standard bottom sheet look; `AnchoredDraggable` with 2 anchors, or M3 `BottomSheetScaffold` if mobile can keep the footer pinned | `ui.surface`, `radius.lg` top corners (16 dp), 8 dp side and bottom margins as NAV-005, elevation 2 (night: 1 dp `ui.outline-variant`). Regions and heights per Layout rules P1–P5. Pane title (TalkBack) «Маршрут харах». Test tag `preview-sheet`; custom semantics `sheetState` = `collapsed\|expanded` (QA hook). |
| **Drag handle** | M3 `BottomSheetDefaults.DragHandle` | 32×4 dp, `ui.outline` (night `ui.outline`), centred in a 24 dp zone. Drag and fling on the handle zone and the header row. Tapping the handle toggles the state (M3 default). Its accessibility actions are the library's localised expand/collapse actions (no app string). Hidden in wide windows. |
| **Header row** | Custom | «Маршрут харах» (`title` 16/24, 600) with the destination text below it (`body` 14/20, `ui.on-surface-variant`, 1 line, ellipsis: it's a name). Close «Хаах» icon button 48 dp on the right. The destination line's content description is «Очих газар: …» (`route_destination` prefix, as NAV-005). |
| **Mode tabs** (changed) | M3 `PrimaryTabRow`, 3 fixed tabs | Group name «Зорчих хэлбэр» (N19). Tabs «Машин» / «Явган» / «Дугуй» with icons `directions_car` / `directions_walk` / `directions_bike` (Material Symbols, bundled). Each tab gets ⅓ of the width, min 48 dp high, icon and label side by side. **At font scale ≥ 1.5 (or when the label doesn't fit) the icon goes above the label** (M3 stacked tab, min 64 dp), so «Машин», «Явган», «Дугуй» are never cut. Selected: `ui.primary` label and 3 dp indicator. A tab selection that stays 300 ms sends one request (AC 22). Test tags `mode-car`, `mode-walk`, `mode-bike`. |
| **Summary** (changed) | Custom | When *k* ≥ 2, first line «Маршрут {n}» (`label-large` 14/20, 500, `ui.primary`) names the selected route. Then duration (`title-large`) + " · " + distance (`body-large`, `ui.on-surface-variant`), «Хүрэх цаг 14:03» (+ «+1 өдөр»), and the snap notice row when the **selected** route's snap is > 500 m (D51). Same formats as NAV-004 AC 23–25. With *k* = 1 there is no «Маршрут {n}» line (as NAV-005). Test tag `preview-summary`. |
| **Route options «Маршрут сонгох»** (new) | Custom M3 radio list (`Modifier.selectableGroup()`, rows `selectable(role = Role.RadioButton)`) | Only when *k* ≥ 2, at the top of the lower part. Visible group heading «Маршрут сонгох» (`label-large`, `ui.on-surface-variant`); the same text is the group's accessible name (N20). One row per route, **min 56 dp**, padding 4/16, gap 16: radio (20 dp; selected `ui.primary`), colour swatch 24×6 dp that matches the line (`route.selected` with `route.selected-casing` outline, or `route.alternative` with `route.alternative-casing`; decorative), label «Маршрут {n}» (`body-large` 500) and meta «15 мин · 5,2 км» (`body`, `ui.on-surface-variant`, tabular figures). Order = Valhalla order (route 1 first, serial position). Selecting a row: AC 17 within 200 ms. The sheet stays as it is (it does not collapse), so the user stays in control. Test tag `route-option`, semantics `selected`. |
| **Avoid switch row** (moved) | As NAV-005 | In the lower part, on «Машин» only (hidden on «Явган» and «Дугуй», AC 23). It keeps its state while hidden. |
| **Points block** (moved) | As NAV-005 | «Миний байршил» and destination rows (48 dp each, content descriptions «Эхлэх цэг: …» / «Очих газар: …») at the end of the lower part. Display only (origin editing is out of scope). |
| **«Эхлэх»** | As NAV-005 (M3 filled, 56 dp, full width) | Pinned footer (P1). Enabled only in the route state with precise location (NAV-005 AC 7, 10). Starts the **selected** route (AC 19). |
| **Alternative route lines** | MapLibre Native, map-style §7.6 | §7.2 layers and tokens in dp; tap box 48×48 dp centred on the tap, unselected route preferred. |
| **Lock card** (new) | Custom, NAV-005 S1 message-card look | `ui.message-surface`, 1 dp `ui.message-outline`, `radius.md` (12 dp), elevation 2. Padding 12/8/4/16. Grid: icon `lock` 24 dp (`ui.on-message-surface`, decorative) · title **K1** «Хөдөлж байх үед бичих боломжгүй» (`body-large` 16/24, 500, `ui.on-message-surface`, wraps) · hint **K3** «Жолооч бол зогсоод хайна уу» (`body` 14/20, `ui.on-message-surface` at 87 %, wraps). Actions row, right-aligned and wrapping, **8 dp gap**: text button «Хаах» (`ui.message-action`) and **filled tonal button «Би зорчигч»** (K2; `ui.primary-container` / `ui.on-primary-container`, the one primary action). Both ≥ 48 dp. Polite live region announcing K1 only (Accessibility). Test tags `typing-lock`, `typing-lock-passenger`, `typing-lock-close`. |
| **Search bar** (changed behaviour only) | As NAV-005 | No visual change in any lock state. When locked, a tap shows the lock card instead of focusing the field and expanding the search view. The clear button «Хайлтыг арилгах» keeps working (AC 33). |
| **Coordinate option** (new, D140) | A row of the S2 results list (`SearchResults`, as built), NAV-003 «Coordinate option» component | The **only** row of the list when the settled query is a recognised pair (AC 7). Same row as a result (min **56 dp**, padding 8/16, full-width tap target; the card's surface and 16 dp radius) with a **leading icon**: `ic_location_searching` (existing drawable, D146), 24 dp, `ui.on-surface-variant`, decorative; 16 dp gap. Line 1 `place_selected_point` «Сонгосон цэг» (`body-large`, `ui.on-surface`, wraps: it's a label, not a name). Line 2 the normalised coordinates via the existing coordinate formatter (`Formatters.coordinates`: 5 decimals, point separator, both languages; `body`, `ui.on-surface-variant`, tabular figures), e.g. «47.91890, 106.91760». No divider below (single row). No loading row before it. Clickable while the typing lock is engaged (AC 33). Content description: see Accessibility. Test tag `search-coordinate-option` (proposal), inside `search-results`. |
| **Coordinate card** (changed) | As NAV-005 + nearest-place area | Title «Сонгосон цэг» + close; coordinates (5 decimals, point separator); **nearest-place area** (P6): top divider 1 dp `ui.outline-variant`, label «Ойролцоох газар» (`label` 12/16, 500, `ui.on-surface-variant`), name (`body-large`, 1 line, ellipsis), type label + " · " + context line (`body`, `ui.on-surface-variant`, wraps; same display rules as the search rows, D34/D45 labels); or a state row (icon 24 dp + text, optional «Дахин оролдох» text button right-aligned below). «Маршрут гаргах» is last and usable at once (AC 8). **Two entry points** (long-press, typed option, AC 8); the card is identical for both; for the typed entry the coordinates line shows the typed point normalised to 5 decimals. Wide windows: start-edge column (P8). Test tags `coord-card`, `coord-nearest`, semantics `reverseState` = `pending\|loading\|place\|empty\|offline\|unavailable\|rate-limited\|error` (QA hook). |

### Content rules (additions)
- **Nearest-place name:** from the `reverse` feature with the search-row display rules (NAV-003 / NAV-005 S2): `lang` = UI language, so English names follow D28. A Cyrillic name in the English UI keeps its text and gets the `mn` locale for TalkBack. It is **never** used as the destination text: the destination stays «Сонгосон цэг» (AC 9, R2).
- **Assisted search:** the field always shows what the user typed. There is no "Showing results for …" line and no correction hint (no glossary term is needed, and Tesler's law puts the complexity in the system). Rows look exactly like NAV-005 S2 rows, whichever request returned them. **Typed coordinates are not searched** (D140): a pair the ADR-0006 §2.2 rule recognises becomes the coordinate option (0 requests, never in `q`, AC 39); a pair it rejects (swapped or out of range, decimal comma, DMS, mixed with words) is ordinary text and searched as typed. There is no "did you mean coordinates" hint for rejected pairs.
- **Coordinates:** `lat, lon`, 5 decimals, point separator in both languages (NAV-003 content rule; a machine format users copy, so it deliberately differs from glossary C3). The option, the card and its content description use the same formatted string.
- **Route numbers:** «Маршрут {n}» follows Valhalla order and is renumbered on every new response (AC 21: route 1 is selected again).

## States

### S2 search (AC 7): list states for text queries (unchanged)
For text the coordinate rule does not recognise: «Ачаалж байна…» (after 300 ms), list, «Илэрц олдсонгүй», «Хайлт түр ажиллахгүй байна» + «Дахин оролдох», «Түр хүлээгээд дахин оролдоно уу» (retry disabled for `Retry-After`), «Интернэт холболт алга» (0 requests). With a request pair: both fail → the failure state; one returns → its results, with no error shown (AC 7). GPS lost or no permission: search works with the map-centre bias (D30).

### S2 search: typed coordinate option (AC 7, 7a, 13, 33; D140)
| State | List shows | Requests | AC |
|---|---|---|---|
| **Coordinate typed** (online) | Exactly one row: ⌖ «Сонгосон цэг» / «47.91890, 106.91760». No loading row at any time | 0 `search` | 7 |
| Coordinate typed, **offline** | The same one row (not «Интернэт холболт алга» in the list). The S1 offline message «Интернэт холболт алга» still shows below the list, as on every S1/S2 screen offline (NAV-005 S1). Selecting opens the card, whose nearest-place area shows its offline state (1 request ≤ 2 s after the network returns, AC 11) | 0 `search` | 7, 11 |
| Coordinate typed, **429 cooldown** running | The same one row (not «Түр хүлээгээд дахин оролдоно уу») | 0 | 7 |
| «Дахин оролдох» pressed earlier / network returns while the option shows | Nothing changes | 0 | 7 |
| Typing lock engaged with the option shown | Lock card above the list (P7); the option stays visible and selectable (at 200 % on 360×640 the list may need a scroll under the lock card, Known limitations 8) | 0 | 31, 33 |
| Language switch / rotation with the option shown | Line 1 switches («Сонгосон цэг» / "Selected point") ≤ 1 s; coordinates unchanged; the option stays | 0 `search`, 0 `reverse` | 13 |
| A newer text query settles | Normal search states replace the option; an older text response never replaces the option | per section A | 7 |
| **Rejected pair** ("106.9176, 47.9189", "47,9189, 106,9176") | Normal search states for the text (results, «Илэрц олдсонгүй», …) | 1 `search`, as typed | 7 |
| Two plain integers ("47 106", D143) | The coordinate option (web parity; Known limitations 6) | 0 | 7 |
| **Option selected** | List, keyboard and field focus gone ≤ 500 ms; the field keeps the typed text and its clear button; pin + coordinate card (loading state first); camera C1 once | 0 `search`, 1 `reverse` | 7a, 8 |
| Card from the option, point outside Mongolia | Card as normal (often «Илэрц олдсонгүй»); «Маршрут гаргах» leads to the NAV-005 outside-area preview state | 1 `reverse` | 8, 10 |
| GPS lost / no permission | No effect on the option, the card or C1 (none of them uses the device position) | — | — |

### S1/S2 typing lock (AC 30–35)
| State | What the user sees | Keyboard | AC |
|---|---|---|---|
| Released / no usable fixes / overridden | NAV-005 S1/S2, no lock card | opens on tap | 32, 34, 35 |
| Engaged, field not tapped | **Nothing changes** on screen | — | 30 |
| Engaged, field tapped | Lock card (K1, K3, «Хаах», «Би зорчигч») within 500 ms; field not focused | stays closed | 31 |
| Engaged while typing | Keyboard hides ≤ 1 s; typed text stays in the bar; in-flight search completes; results stay visible and selectable; lock card between the bar and the results; K1 announced once | hidden | 31 |
| Lock card shown, user taps «Би зорчигч» | Card disappears; field focused; keyboard ≤ 500 ms; no override indicator (the override is the user's own statement) | opens | 34 |
| Lock card shown, lock releases (stopped) | Card removed ≤ 1 s, no announcement; **keyboard does not open by itself** | closed until a tap | 32 |
| Lock card shown, «Хаах» / Back / map tap | Card removed; S1/S2 as before | closed | 33 |
| Offline while locked | Lock card as above. If the user unlocks, the list shows «Интернэт холболт алга» | — | 7 |
| Location permission revoked or services off while engaged | Lock releases ≤ 1 s; the card is removed; no location message (the lock never asks) | opens on tap | 27, 32 |
| GPS lost (no good fix 30 s) | Lock releases | opens on tap | 32 |

### S2 coordinate card: nearest-place area (AC 8–13)
| State | Area shows | Retry | AC |
|---|---|---|---|
| Pending ≤ 300 ms | Reserved space, empty | — | 8 |
| Loading > 300 ms | «Ачаалж байна…» (no icon, text only, as NAV-005 loading rows) | — | 8 |
| Place | «Ойролцоох газар» / name / type · context | — | 9 |
| Empty (X2) | «Ойролцоох газар» / «Илэрц олдсонгүй» (not an error) | — | 10 |
| Offline | Offline icon + «Интернэт холболт алга» within 500 ms; 1 request ≤ 2 s after the network returns | — | 11 |
| Unavailable (network, 502–504, 8 s) | Info icon + «Хайлт түр ажиллахгүй байна» | «Дахин оролдох» (sends once) | 11 |
| Rate-limited (429) | Info icon + «Түр хүлээгээд дахин оролдоно уу» | disabled for N s (5 s default), then enabled; nothing auto-sent | 11 |
| Error (400) | Info icon + «Алдаа гарлаа» | none | 11 |
| GPS lost / no permission | No effect (the card uses the chosen point, not the position) | — | — |
In every state: heading, coordinates, pin, camera and «Маршрут гаргах» are unchanged (AC 9, 11). The camera rule holds **after the card has opened**: the typed entry's C1 move happens once at the selection, before any of these states can change (AC 7a, 9).

### S3 route preview (result region; NAV-005 S3 precedence unchanged)
| State | Summary region | Lower part (expanded) | «Эхлэх» | AC |
|---|---|---|---|---|
| Route, *k* = 1 | Summary (no «Маршрут {n}» line) | No «Маршрут сонгох» group | enabled | 15, 18 |
| Route, *k* = 2–3 | «Маршрут {n}» + summary of the selected route | «Маршрут сонгох» with *k* rows | enabled | 15–18 |
| Selection changed | Summary follows ≤ 200 ms; camera still; 0 requests | Radio moves | enabled | 17 |
| Snap > 500 m (selected route) | + snap notice row | — | enabled | 17, D51 |
| «Явган» / «Дугуй» | Summary | Avoid switch hidden | enabled | 23 |
| Too far on «Явган» / «Дугуй» | «Энэ зай явганаар эсвэл дугуйгаар хэт хол байна» | Avoid switch hidden; no options | disabled | 24 |
| `DistanceExceeded` on «Машин» | «Маршрут олдсонгүй» | — | disabled | 24 |
| No route, switch on | «Маршрут олдсонгүй» + «Шороон замаас зайлсхийх тохиргоог унтрааж дахин оролдоно уу»; **sheet expands once** (P4) | Switch visible | disabled | NAV-005 AC 7 |
| Pending ≤ 300 ms / loading | As NAV-005 (region empty, then spinner + «Ачаалж байна…»); the old lines are removed at request start | Options removed | disabled | 14 |
| Offline, 429, unavailable, outside area, same point, error, location problem | As NAV-005 S3 table, same texts and precedence | — | disabled | NAV-005 AC 7 |
| First use (TalkBack on) | Sheet opens expanded | Visible | — | 18 |
| Night / English | Night tokens / `en` texts; street and place names stay as they are | — | — | 20 |
The S3 surfaces have no text field, so the typing lock has nothing to block there (AC 33 controls stay usable).

### S5 guidance on «Дугуй» (AC 25–26)
Same screen and states as NAV-005 S5. Only the voice schedule (navigation-ux §4.2 «Дугуй» column, §4.4 chaining ≤ 60 m) and the camera zoom (17.5) differ. Off-route reroutes keep `bicycle` with `alternates: 0`.

## Interactions
- **Tap an alternative line** (48×48 dp box, map-style §7.6): selects it ≤ 200 ms, 0 requests, no camera move. A tap on the selected line or on empty map does nothing. A tap that ends a pan is not a tap. Long-press on a line opens the coordinate card (NAV-005).
- **Route option row tap:** the same as tapping a line.
- **Drag or fling the sheet:** collapsed ↔ expanded (P4). The map above stays pannable and zoomable (non-modal, NAV-005 rule 6).
- **Mode tab:** 300 ms settle, then 1 request with `alternates: 2`. The new response selects its route 1. The avoid switch hides or reappears with its kept state.
- **«Эхлэх»:** guidance on the selected route ≤ 1 s, other lines removed ≤ 1 s, 0 extra requests (AC 19).
- **Search bar tap while locked:** lock card ≤ 500 ms (no keyboard, no search view). **«Би зорчигч»:** focus + keyboard ≤ 500 ms; override for the rest of the app session. **«Хаах» / Back:** card closes.
- **Long-press while a reverse request is pending:** new card, old request cancelled or ignored (AC 12).
- **Tap the coordinate option** (also while the lock is engaged, AC 33): within 500 ms the list closes, the keyboard hides and the field loses focus, keeping the typed text (as after a normal result, D146); the pin and the card open at the typed point and send 1 `reverse` with the typed values (AC 8); following stops and the camera makes the one C1 move. A long-press later, or another typed option, replaces the card as usual (AC 12). TalkBack focus: see Accessibility.
- **Tap the field again after a typed card opened:** the search view opens with the typed text and shows the option again (0 requests); the card stays until the user selects something else or closes it (NAV-005 behaviour for the field and the card).
- **«Дахин оролдох» on the card:** one request per tap. Disabled during `Retry-After`.
- **System Back:** expanded sheet → collapses first (M3 convention), collapsed sheet → S1 or the card (NAV-005); lock card → closes; coordinate card → S1 (the camera stays where C1 put it; the field keeps the typed text).
- **Rotation / theme / language:** routes, selection, sheet state, lock card, the coordinate option and card content are kept; 0 requests (AC 13, 20). C1 is not repeated after a rotation (the camera move is one-shot, not state).

## Copy (mn / en)
Every `mn` value is a glossary term. **K1–K3 are `needs native review`** (glossary §2.4; the NAV-007 panel rates them; the PO may pre-review). Resource names are a proposal; the mobile engineer owns the final names. `mn` and `en` key sets are identical (AC 38). Placeholders stay literal (`{n}`).

### New keys
| Key (proposal) | mn | en | Glossary source |
|---|---|---|---|
| `route_mode_bike` | Дугуй | Bike | N1 |
| `route_options` | Маршрут сонгох | Choose a route | N20 |
| `route_option` | Маршрут {n} | Route {n} | N6 |
| `route_alternative` (TalkBack description of non-selected options) | Өөр маршрут | Alternative route | "Alternative route" |
| `place_nearest` | Ойролцоох газар | Nearest place | T7 |
| `typing_lock_title` | Хөдөлж байх үед бичих боломжгүй | You can't type while moving | K1 (needs native review) |
| `typing_lock_hint` | Жолооч бол зогсоод хайна уу | If you are driving, stop before you search | K3 (needs native review) |
| `typing_lock_passenger` | Би зорчигч | I'm a passenger | K2 (needs native review) |

### Reused keys (already in `values/` and `values-en/`)
`route_title`, `route_modes`, `route_mode_car`, `route_mode_walk`, `route_avoid_unpaved`, `route_origin`, `route_destination`, `route_eta`, `route_next_day_*`, `route_snap_notice`, `route_too_far`, `route_no_route`, `route_no_route_avoid_hint`, `route_unavailable`, `route_rate_limited`, `route_out_of_area`, `route_same_point`, `nav_start`, `place_selected_point`, `place_type_*`, `search_*`, `status_loading`, `status_offline`, `status_generic_error`, `action_retry`, `action_close`, `marker_my_location`, `unit_*`.

**Typed coordinates (D140) add no key.** The option uses `place_selected_point` (T6) and the existing coordinate formatter; its content description joins those two with ", " (a separator, not translatable text). No new glossary row (glossary T6 "Where" already cites NAV-011 AC 7, 7a and 8).

### Length notes (Mongolian first)
- Longest new strings: K1 «Хөдөлж байх үед бичих боломжгүй» (31 characters; 2 lines at 360 dp in the lock card at 100 %, wraps further at larger scales), K3 (27), K2 «Би зорчигч» (10) against the en "I'm a passenger" (15). The English K3, "If you are driving, stop before you search" (42), is longer than the Mongolian. Everything wraps.
- Tab labels: «Машин», «Явган», «Дугуй» are 5 letters each, against "Car", "Walk", "Bike". At 200 % with icon and label side by side, «Машин» no longer fits its ⅓-width tab at 360 dp while the English labels still do (measured), so the stacked layout from 1.5 exists for Mongolian; the rule applies to both languages.
- **Glossary note for K2:** «Би зорчигч» deviates from C1 (buttons use the -х form). The glossary records the C1-conform alternative «Зорчигчоор үргэлжлүүлэх» (22 characters) for the panel. The lock card was also checked with the longer wording: measured at 360 dp, the two actions share a row at 100 % and wrap onto two rows from 130 % with no clipping, so the layout holds either way.

## Accessibility
- **Touch targets:** tabs ≥ 48 dp (64 dp stacked), route option rows ≥ 56 dp, the coordinate option ≥ 56 dp (full row), lock card buttons ≥ 48 dp with an **8 dp gap**, «Эхлэх» 56 dp, card buttons 48 dp. Alternative lines have a 48×48 dp tap box (AC 17, 44). Measured by the checker.
- **Contrast** (WCAG 2.2 AA, all existing `contrastPairs`, `check-contrast.mjs` passes): K1/K3 text on `ui.message-surface`; «Хаах» in `ui.message-action` on `ui.message-surface`; K2 on `ui.primary-container`; «Маршрут {n}» in `ui.primary` on `ui.surface`; selected line vs `earth` / `major` ≥ 3:1 (§7.2); alternative vs selected ≥ 2:1.
- **TalkBack:**
  - **Sheet:** pane title «Маршрут харах». With touch exploration on, the sheet opens expanded (P4), so the options are in the reading order without a drag gesture.
  - **Reading order:** header → tabs («Зорчих хэлбэр», tab N of 3, selected) → summary → «Маршрут сонгох» → avoid switch → points → «Эхлэх».
  - **Route options:** a selectable group named «Маршрут сонгох». The selected row reads «Маршрут 2, 15 мин, 5,2 км, selected». Each non-selected row's description ends with «Өөр маршрут» (AC 18).
  - **Map lines:** not focusable. The options group is the accessible equivalent.
  - **Lock card:** polite live region announcing **K1 once per engagement** (AC 31); K3 and the buttons follow in swipe order. Accessibility focus does not jump.
  - **Nearest-place area:** polite live region. It announces «Ойролцоох газар, <name>» or the state text once per card. Language switches don't re-announce (AC 13).
  - **Coordinate option (AC 7):** one merged node, role button, content description «Сонгосон цэг, 47.91890, 106.91760» (`place_selected_point` + ", " + the formatted coordinates; existing strings only), double-tap selects. The icon is decorative. It is not a live region (result lists aren't announced either; the user is in the field and reads on).
  - **After selecting the option:** accessibility focus moves to the card title «Сонгосон цэг» (heading semantics), so focus is not lost with the closed list (web parity: NAV-003 AC 43 moves focus to the card heading). The nearest-place live region then announces as for a long-press card.
  - **Selection change:** the summary is not live (the option's own state change is announced).
- **Dynamic type:** S1–S3 scale without a cap (NAV-005). Stacked tabs from 1.5. The top part of the sheet gives way first (P2). No new text is cut mid-word at 200 % (checker: words never break, no overflow).
- **Colour is never the only signal:**
  - selected route: blue **and** wider **and** drawn on top **and** radio state **and** the «Маршрут {n}» line;
  - lock: icon **and** text.
- **Reduced motion:** the sheet jumps between states; no expand animation for the avoid-hint state; the C1 camera move is a jump.
- **No time limits.** The lock card stays until the user acts or the lock releases.

## Design rationale
| UX law / heuristic | How this design applies it | Deliberate trade-off |
|---|---|---|
| **Fitts's law** | «Эхлэх» is pinned at the bottom of the sheet in both states, never moving and in thumb reach. K2 is the larger filled-tonal target at the right of the lock card, «Хаах» smaller with 8 dp between them. Alternative lines get a 48 dp tap box (24 dp each side, gloves) | Alternative 3(a) (Google's moving Start) rejected; expanded content scrolls above the button instead |
| **Hick's law** | Mode tabs = 3, routes ≤ 3, one primary action per state («Эхлэх»; «Би зорчигч» on the lock card). The avoid switch and points block move into the expanded part (progressive disclosure) | Changing the avoid switch now needs a drag; mitigated by auto-expand in the only state that asks for it (P4) |
| **Miller's law** | The summary is one chunk: «Маршрут {n}» / duration · distance / «Хүрэх цаг». An option row is name + one meta line. The lock card is message, hint and two actions | — |
| **Jakob's law** | Google Maps / Waze habits: alternatives as grey lines you tap, a draggable bottom sheet, a side panel in landscape, long-press for a point, search on top | The lock card is our own pattern (no common Android convention exists for a passenger override); it reuses the familiar message-card look |
| **Doherty threshold** | Selection ≤ 200 ms with no request, lock card ≤ 500 ms, loading text after 300 ms (reverse, preview), offline ≤ 500 ms. «Маршрут гаргах» never waits for reverse | Reverse can take up to 8 s; the card is fully usable meanwhile |
| **Von Restorff** | One emphasis per screen: the selected route (blue, wide, on top) while the alternatives are muted. On S1 nothing changes while driving until the user taps | — |
| **Serial position** | Route 1 (Valhalla's best) first and selected; the summary first, «Эхлэх» last (bottom); the lock card above other messages | — |
| **Gestalt (proximity, common region, similarity)** | The selected route's information sits in one region (summary). Options share one radio component with swatches that echo their lines. The lock card uses the existing message-card look | — |
| **Tesler's law** | Script, keyboard layout and abbreviations are handled behind the field; the lock decides by itself from speed; no setting for either | No visible "assisted" hint, so users can't see why a Latin query found Cyrillic names (acceptable: the result is what they wanted) |
| **Postel's law** | Cyrillic, Latin, Russian layout, district abbreviations all accepted (AC 2–4); the field shows exactly what was typed | — |
| **Peak–end rule** | No dead ends: too far → clear N11 text with the other tabs one tap away; reverse failure keeps «Маршрут гаргах»; the lock card always offers a way forward | — |
| **Zeigarnik effect** | Typed text and results survive the lock; a hidden avoid switch keeps its state; sheet state survives rotation | — |
| **Aesthetic–usability / prägnanz** | No new colours or tokens; one new card type reuses the message look; tabs and options are stock Material 3 | — |
| **Cognitive load (taps to start)** | Search result → preview → «Эхлэх» stays **2 taps**, as NAV-005. Choosing an alternative adds 1 tap (line) | — |

**Nielsen heuristics checked:**
- **Visibility of system status:** the reverse states, the lock card, and «Маршрут {n}» naming the selection.
- **Match with the real world:** glossary terms only; «Ойролцоох газар», never «Хаяг».
- **User control:** «Хаах» on the lock card; Back collapses the sheet first; the selection never auto-changes except on a new response (AC 21).
- **Consistency:** NAV-004 swatch and radio pattern, NAV-005 tokens and message look.
- **Error prevention:** the button doesn't move (P6), the line tap prefers the unselected route, the avoid switch is hidden where the contract rejects it.
- **Recognition over recall:** the destination name in the header, swatches matching the lines.
- **Flexibility:** experts tap lines; novices use the list.
- **Minimalist design:** the collapsed sheet shows only what's needed to start.
- **Error recovery:** every failure says what happened and offers the next step where there is one.
- **Help:** K3 explains the lock inline.

Two needed a design change during the work: error prevention (the card's button order, Alternative 5) and user control (the auto-expand for the avoid hint, P4).

### Design rationale: typed coordinates (D140–D146)
| UX law / heuristic | How the change applies it | Deliberate trade-off |
|---|---|---|
| **Jakob's law** (our own products too) | Same model as the web (NAV-003 AC 26): one option, tap, card. The field closes as after any result (D146) | The camera rule differs from the web's viewport centre (D32): on a phone the bottom card would hide the pin (Alternative 7b) |
| **Hick's law** | One option and nothing else: no list of guesses, no "search it as text" second row | A user who really meant two numbers as text ("47 106", D143) gets no search (Known limitations 6) |
| **Fitts's law** | Full-width 56 dp row directly under the field, where the thumb already is for results; usable while locked (AC 33) | — |
| **Recognition over recall / error prevention** | Line 2 shows the **normalised** pair, so a swapped or mistyped pair is visible before anything moves; rejected pairs fall back to a normal search instead of an error | No "did you mean coordinates" hint for a rejected pair (no glossary term; rare) |
| **Doherty threshold** | 0 requests, so the option appears with the settled query (no loading row); list closes ≤ 500 ms; camera arrives ≤ 1 s (700 ms move) | — |
| **Tesler's law / Postel's law** | Comma, comma-space, space; NBSP from messengers normalised; offline and rate-limited still work, because nothing has to be fetched | Decimal comma, DMS, `geo:` and Plus codes stay out of scope (searched as text) |
| **Visibility of system status / Von Restorff** | After the tap the camera lands with the pin in the free map, clear of the card (C1, measured): the one thing that changed is visible | The camera moves although a long-press never moves it; justified because the typed point is usually off-screen (D142) |
| **Consistency** | Reuses the result row, `ic_location_searching`, T6 and the long-press card; P8 reuses the P5 / NAV-018 column width | P8 also narrows the long-press card on wide windows (one card, one layout) |
| **User control** | Back closes the card; the field keeps the typed text for a correction; following stops but «Миний байршил» brings it back | — |

## Design notes (decisions inside the AC)
1. **Collapsed sheet shows the selected route only** (Alternative 2a). AC 18 asks for the group in the sheet, not in the collapsed part. TalkBack users get it expanded.
2. **«Эхлэх» pinned at the bottom in both states** (Alternative 3b): NAV-005 rule 6 carries over to the draggable sheet.
3. **The destination text moves into the header** as a second line, so the collapsed sheet keeps the confirmation of where the route goes without the 96 dp points block. The points block (with the NAV-005 content descriptions) stays in the expanded part.
4. **No pre-tap lock indicator** (Alternative 1c): safety beats visibility here, because the lock state is irrelevant until someone tries to type.
5. **No override indicator.** After «Би зорчигч», the bar looks normal. The override is the user's own statement for this session (AC 34); a persistent badge would nag a passenger at every glance.
6. **The lock card doesn't reopen the keyboard on release.** A keyboard springing open when the car stops would surprise and cover the map.
7. **Landscape side sheet** (Alternative 4b) for D5 wide windows. The camera padding uses the sheet width.
8. **«Дугуй» guidance numbers** (navigation-ux §4.2 rule 8): main clamp(*v* × 15 s, 60, 150) m, now clamp(*v* × 3 s, 15, 30) m, no early prompt, continue-on ≥ 2 km, chaining ≤ 60 m, zoom 17.5. The outcome band for G10 is 15–150 m. These need no new glossary text and no new C3a table entries.
9. **BA open questions:** the design follows the BA defaults: (1) override for the app session; (2) «Эхлэх» works on «Дугуй»; (3) no origin choice or turn list; (4) lock fixes read whenever permission is granted.
10. **Typed coordinates (D140, D146): one option, then the existing card.** The row is the result row plus a leading `ic_location_searching` icon (no new asset). The field closes as after a normal result and keeps the typed text (Android result path; the web keeps the typed text for this option too).
11. **Camera C1 (D142).** "Centre, then open the card" is built as: the card is composed in the same frame as the selection so C1 can use its height, and the camera arrives ≤ 1 s later. The pin is shown at once and moves with the map (NAV-003 rule: feedback at the selection, in place when the camera arrives).
12. **P8: card column on wide windows.** Without it C1 has no free band at 640×360 (Alternative 7c, measured). Same width as the P5 side sheet and the NAV-018 preview card column, so the card sits in the same place in browse and preview.
13. **NAV-018 reuse (D144, D145).** The coordinate option row is the same component in the NAV-018 point editor, but there selecting it sets the point «Сонгосон цэг» directly with no card and no C1 move (NAV-004 AC 6 parity). That is specified in the NAV-018 spec by its own run; this spec does not change it.

## Known limitations (design)
1. **200 % font scale on 360×640 with a snap notice** (or a long state row): the top part (header with close, tabs) scrolls out of the collapsed sheet so that the summary and «Эхлэх» stay. Close is still reachable by scrolling the top part or with Back. The checker reports no failure, because this is the designed fallback (P2).
2. **200 % expanded sheet on 360×640:** the summary and the first option fill the sheet; the rest of «Маршрут сонгох» scrolls. With the avoid hint at 200 %, the switch is below the fold even after the auto-expand.
3. **320×568 above 100 %** stays outside the design target (D66). The checker reports 2 INFO lines (expanded no-route-avoid at 130 %: less than 48 dp of the lower part visible before scrolling).
4. **The typing lock is a safety aid, not a guarantee** (AC 35, R7). There is no lock without precise fixes, and the honour-system override can be tapped by a driver. The design does not try to detect who is holding the phone.
5. **The prototype draws the map and lines statically.** The real camera fit, the tap box and render times are QA and device checks (AC 16, 17, 43, 47).
6. **Two plain integers are a coordinate pair** (D143, web parity): "47 106" or "13 40" shows the option and is not searched, so a digit-only shorthand (khoroo / building numbers) finds nothing. Accepted by the PO; changing it is a NAV-003 + ADR-0006 change on both clients.
7. **The nearest place for a typed point is often far or empty** in ger districts and on the steppe (NAV-011 R2). The card still shows the coordinates and «Маршрут гаргах»; the destination stays «Сонгосон цэг».
8. **200 % font scale on 360×640 with the lock card engaged:** the lock card fills the top group and the coordinate option is below it in the group's scroll (the same as results in the existing lock-results state). It stays selectable after a scroll; the checker skips scrolled-out targets.

## AC traceability
| AC | Where |
|---|---|
| 1–6 | Content rules (assisted search); States › S2 search; flow F1. No visual change |
| 7 | States › S2 search and S2 typed coordinate option; Components › Coordinate option; Content rules (typed coordinates, coordinates); Copy (no new key); Accessibility (option description); flow F1 coordinate branch |
| 7a | Interactions (tap the coordinate option); Layout rules C1 and P8; Accessibility (focus to the card title); Design notes 10–12; flow F1 → F2 typed entry |
| 8–13 | Coordinate card component (two entry points); Layout rules P6, P8; States › nearest-place area ("after the card has opened"); States › S2 typed coordinate option (AC 13 row); Accessibility (live region); flow F2 |
| 14 | Interactions (mode tab, request profile); flow F3 |
| 15–16 | map-style §7.6; Layout rule P3 (camera fit uses the collapsed sheet); flow F3 |
| 17 | map-style §7.6 tap box; Route options; Interactions |
| 18 | Route preview sheet, Route options, Layout rules P1–P4; Accessibility (TalkBack expanded, «Өөр маршрут») |
| 19–21 | «Эхлэх»; Interactions; map-style §7.6; Content rules (route numbers) |
| 22–24 | Mode tabs; Avoid switch row; States › S3 (too far, `DistanceExceeded` on car) |
| 25–26 | States › S5 on «Дугуй»; navigation-ux §4.2 rule 8, §4.4, §8; flow F5 |
| 27–37 | Lock card; States › typing lock; Design notes 4–6; flow F4, F4a. AC 27–29, 36 have no UI (rule, privacy). AC 33: the coordinate option stays selectable (States › S2 typed coordinate option, Interactions) |
| 38 | Copy (new keys, glossary sources, K1–K3 needs native review) |
| 39–43, 45–47 | No UI (privacy, regression, verification). AC 39: Content rules (a recognised pair never in `q`). Evidence lists what this spec checked |
| 44 | Accessibility; Evidence (48 dp, contrast, 200 %, attribution) |

## Evidence
- **Prototype:** `prototypes/NAV-011-android-preview.html`, 17 states:
  - coordinate card × 7 nearest-place states;
  - lock card on the bar, and lock card over results while typing;
  - preview *k* = 3 collapsed, *k* = 1, expanded with route 2 selected, «Дугуй» expanded, snap notice, too far on «Дугуй», routing unavailable, no route with the avoid hint (auto-expanded).
- **Layout checker:** `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav011.mjs` covers 360×640, 412×915, 320×568 and 640×360 dp (24 dp status bar; 48 dp 3-button navigation bar in portrait, 24 dp in landscape) × 17 states × day/night × mn/en × font scale 1.0/1.3/2.0 = **816 combinations**, with Liberation Sans.
  - **Result 2026-10-03: 0 problems**, 2 INFO lines outside the target (Known limitations 3).
  - Stress run `--wide` (DejaVu Sans): **0 problems**, 6 INFO lines outside the target.
  - Measured:
    - collapsed sheet (*k* = 3) **274 dp** at 360×640 at 100 % (304 at 130 %, 317 at 200 % with the top part scrolling);
    - minimum route band above the collapsed sheet: **238 / 208 / 185 dp** at 360×640 (100 / 130 / 200 %), 529 / 473 / 313 dp at 412×915;
    - «Маршрут гаргах» at the **same position in all 7 reverse states** for every viewport, theme, language and scale;
    - nothing scrolls in the collapsed sheet at 100 % on ≥ 360×640 (AC 18).
  - **Iterations the checker forced:**
    1. The collapsed sheet at 200 % with the snap notice left a 23 dp map band. This led to the 60 % cap with the top part giving way first (P2, P3).
    2. In the expanded sheet the header and tabs were squeezed to 20 dp because every region shrank equally. Fixed by making the expanded body one scroll above a pinned footer.
    3. «Эхлэх» first sat under the summary and moved up on expand. Changed to the pinned footer (Alternative 3).
    4. A long Mongolian card title pushed the card wider than the window at 130 % and above (card `min-width: 0`).
- **Typed coordinates (2026-10-04, D140):** the prototype gained 3 states (`coord-option`, `coord-option-offline` with the S1 offline message, `coord-option-locked`) and the `entry=typed` variant of the 7 card states (`#state=card-place&entry=typed`), which draws the pin where C1 puts it. The checker now runs **1,296 combinations** (27 state variants × 4 viewports × day/night × mn/en × 3 font scales) and adds two rules: the option row is a ≥ 48 dp control with no text overflow, and the typed-point pin (28×40 dp, placed by C1 from the card-loading layout, the moment of the move) is clear of the top group, the card, every control and R5 in **all 7** reverse states (the card may grow after the move; the camera does not follow).
  - **Result 2026-10-04: 0 problems**, the same 2 INFO lines (Known limitations 3). Stress run `--wide`: **0 problems**, the same 6 INFO lines.
  - Minimum vertical clearance between the pin and the nearest box or control (narrow font; `--wide` in brackets): 360×640 **73 / 63 / 49 dp** (73 / 53 / 46) at 100 / 130 / 200 %; 412×915 214 / 198 / 111 dp; 640×360 74 / 76 / 79 dp (74 / 76 / 84); 320×568 63 dp at 100 % (outside the target above it: 61 / 37).
  - **Iteration the checker forced:** with the D5 full-width card, 640×360 failed in all 84 typed-card combinations (pin under the card: no free band). That led to P8 (Alternative 7c). Before P8: 84 problems; after: 0.
  - The existing measurements did not change (route band 238 / 208 / 185 dp; «Маршрут гаргах» fixed across the reverse states, now also with P8 and for both entry points).
- **Contrast:** `node docs/design/prototypes/check-contrast.mjs` → **all checks passed** (80 map-style values; no new tokens or pairs).
- **Mermaid:** the 6 diagrams in the flow parse with Mermaid's parser (no syntax errors; re-run 2026-10-04 after the F1 coordinate branch and the F2 typed entry).
- **Timing budgets** (delegated to QA, JVM/Robolectric per D108): AC 7a (500 ms list, keyboard and field closed; camera at max(zoom, 16) with the point inside the C1 rectangle), AC 8 (300 ms loading text, button usable at once), AC 11 (500 ms offline, 2 s on network return), AC 13 (1 s language switch), AC 16 (1 s camera fit), AC 17 (200 ms selection), AC 31 (500 ms lock card, 1 s keyboard hide), AC 34 (500 ms keyboard), AC 43 (1,500 ms response to UI state). On-device render, sheet drag and the line tap with gloves are real-device checks (AC 47, not verified: no Android test phone).

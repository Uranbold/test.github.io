# Screen: Android route preview, start point choice (search, map point, swap) and turn list «Маршрутын заавар»

- **Stories:** NAV-018 (AC 1–38; UI-relevant AC 1–9, 11, 14, 15, 18, 21–30, 33, 34). Traceability per AC at the end.
- **Platforms:** **Android only** (Kotlin, Jetpack Compose, Material 3, MapLibre Native). iOS is NAV-015 and reuses this spec with HIG controls.
- **Base spec:** this is a **delta** on [`screens/NAV-011-android-route-preview-search-parity.md`](NAV-011-android-route-preview-search-parity.md), which is a delta on [`screens/NAV-005-android-navigation.md`](NAV-005-android-navigation.md). It changes S3 (the preview sheet: header row → points block; «Эхлэх» footer → O1; lower part → turn list), adds the **point editor** (a field-editing state of S3), and changes the **coordinate card while a preview is open**. NAV-012's battery entry row and hint keep their rules (AC 30 order below). Everything this spec does not name stays as NAV-011 / NAV-005 / NAV-012.
- **Web parity reference:** [`screens/NAV-004-route-preview.md`](NAV-004-route-preview.md) (fields, swap, turn list, manoeuvre icons, distance formats) and ADR-0008 (instruction text). Where Android differs, Alternatives considered says why.
- **Flow:** [`flows/NAV-018-android-origin-turn-list.md`](../flows/NAV-018-android-origin-turn-list.md) (F1 open, F2 point editor, F3 map point, F4 swap and requests, F5 «Эхлэх» and O1, F6 turn list, F7 close and session).
- **Map:** [`map-style.md`](../map-style.md) v0.9 **§7.7** (start marker for a chosen start, candidate pin, manoeuvre point; no new colours).
- **Prototype:** [`prototypes/NAV-018-android-points.html`](../prototypes/NAV-018-android-points.html) (1 CSS px = 1 dp; hash example `#state=pv-chosen-exp&theme=night&lang=mn&scale=2`; `v=b` / `v=c` show the measured alternatives), checked by [`prototypes/check-layout-nav018.mjs`](../prototypes/check-layout-nav018.mjs) (Evidence). No Figma file.
- **Tokens:** [`tokens.json`](../tokens.json) v0.6.1: **no new tokens**. Two contrast pairs added (`route.selected` on `ui.surface`, day and night) for the activated turn-row bar.
- **API / architecture:** `openapi.yaml` `search`, `reverse`, `postRoute`; no contract change for the design. Client structure: ADR-0015 (draft, in parallel): field search §5, turn-list model and row camera rule §8, markers §9; it leaves the sheet-collapse decision to this spec (Q8).

## Purpose
On Android, let a driver plan a trip that does not start where the phone is (a customer's pickup, tomorrow's commute, the way back), swap the two ends with one tap, and read every turn in Mongolian before leaving, with the same rules and words as the web preview.

## Context and goal
| Surface | Primary task | Moment of use and time budget |
|---|---|---|
| S3 points block (fields, swap) | See and change where the route starts and ends | **Planning, parked or a passenger.** One glance confirms "from where to where" (< 1 s); changing a point takes 5–30 s. While driving the typing lock blocks the keyboard (AC 34) |
| Point editor | Find the start or destination by name, or use «Миний байршил» | Parked or passenger; up to a minute. The one non-typing option («Миний байршил») is one tap |
| Coordinate card «Эхлэх цэг болгох» / «Очих газар болгох» | Use a map point (pickup without an address) as start or destination | Parked; 2–5 s reading the nearest place; the buttons must not wait or move |
| O1 hint at «Эхлэх» | Understand why guidance can't start from a chosen start and how to get it back | One glance (< 1 s): a disabled button needs its reason next to it |
| Turn list «Маршрутын заавар» | Check where the turns are (ger-district streets, the city exit, a roundabout) | Planning at home or parked; 10 s to a minute. Never during guidance (the banner does that, NAV-005) |

### Alternatives considered
**1. Where the start and destination fields go (AC 1, 27).** Budget at 360×640 / 100 %: the area above R5 is 544 dp, and AC 27 keeps ≥ 160 dp of map above the collapsed sheet, so the collapsed sheet may be at most 376 dp. NAV-011's collapsed sheet is 274 dp.
- (a) **The points block replaces the NAV-011 header row** («Маршрут харах» + destination line + ✕) at the top of the sheet: ✕, two 48 dp fields in one container, swap. The title stays as the TalkBack pane title only. **Chosen.** Measured collapsed: 326 dp (device start), 354 dp (chosen start with O1), 374 dp (device start with the NAV-012 battery row); map band 210 / 182 / 162 dp.
- (b) Keep the visible header row and add the fields below it. Rejected after measuring (`v=b`): the device-start sheet already reaches the cap (band 160 dp) and with a chosen start the top part scrolls by 26 dp at 100 %, so the fields and tabs are cut (AC 27 fails). The title says nothing the two fields don't.
- (c) A separate card at the top of the screen (Google Maps). Rejected: two surfaces to keep in step (and a third arrangement for the landscape side sheet, which AC 27 wants to hold the fields), the fields move out of thumb reach on tall phones, the camera fit would need a second padding source (NAV-011 P3 pads only for the sheet), and estimated from the same component heights it does not save height (≈ 112 dp card + ≈ 230 dp sheet).

**2. Where «Хаах» goes when the header row is gone.**
- (a) **Leading ✕, aligned with the start field row** (the position of Google Maps' back arrow; M3 puts close/navigation at the start). **Chosen.**
- (b) Trailing ✕ on the start row. Rejected: an ✕ at the end of a text field means "clear this text" on every platform; here it would close the whole preview (error prevention).
- (c) ✕ in a 48 dp handle row. Rejected: +24 dp of height, which pushes the chosen-start sheet over the cap.

**3. How a field is edited (AC 3–5, 7).**
- (a) Inline list inside the sheet (web NAV-004 rule 6). Rejected: with the keyboard open (≈ 260 dp at 360×640) the sheet would hold about two rows, and a draggable sheet that must also follow the keyboard insets is fragile.
- (b) **Point editor in the S1/S2 search position:** the sheet hides, the field opens at the top of the screen in the S1 search-bar shape with a label line, and the NAV-011 search results card follows below it; the map stays visible underneath. It reuses the as-built `SearchRow` / results components with the second `SearchController` instance of ADR-0015 §5 (AC 4: "the existing search code"; the map-screen query is kept). **Chosen.** Measured at 360×640 with the keyboard: 196 dp of list = «Миний байршил» + 2½ results at 100 %.
- (c) A full-screen planner page with both fields (Google Maps directions editor). Rejected: a new screen type (the BA's size-L warning), and the second field costs 56 dp of list space for little gain.

**4. Where O1 goes (AC 15).**
- (a) **One line above «Эхлэх» in the pinned footer** (info icon + O1, `ui.on-surface-variant`). «Эхлэх» keeps its full-width bottom position. **Chosen.** Costs 28 dp at 100 % (one line in both languages at 360 dp).
- (b) Beside a narrower disabled «Эхлэх» (`v=c`). Saves the 28 dp at 100 %, but O1 wraps to 4 lines at 130 % and the footer grows further at 200 %, and the button's width would change with the kind of start.
- (c) As a second line inside the disabled button. Rejected: disabled content is 38 % opacity, so the reason would be the least readable text on the screen.
- (d) O1 plus a fix-it button that sets «Миний байршил». Rejected for now: new product behaviour and a button label that is not a C1 verb; the fix is already two taps away (start field → «Миний байршил») or one swap.

**5. What a turn-row tap does with the expanded sheet (AC 22).**
- (a) **The sheet collapses and the camera eases to the step** in the band above the collapsed sheet (Apple Maps / Google Maps "show this step"). The list keeps its scroll position and marks the row. **Chosen.**
- (b) Stay expanded. Rejected: the expanded sheet is 80 %, so at 360×640 the uncovered band is 109 dp, or 29 dp after the 40 dp padding.
- (c) A third, half-height detent. Rejected: NAV-011 P4 has exactly two snap states, and a third would change every NAV-011 sheet test.
- Exception: **TalkBack on, or a wide window:** the sheet stays as it is (it is expanded for TalkBack, NAV-011 P4; the side sheet never collapses) and the camera uses the uncovered area.

**6. The two buttons on the coordinate card during the preview (AC 6).**
- (a) **Stacked, full width, «Эхлэх цэг болгох» above «Очих газар болгох»**, both filled tonal with the ring and pin icons of the fields. The order repeats the fields (start on top). **Chosen**: the positions are the same in both languages and at every font scale, and the card is bottom-anchored, so the buttons never move between the reverse states (measured).
- (b) Side by side. Rejected: «Эхлэх цэг болгох» + «Очих газар болгох» need about 300 dp at 100 % and wrap from 130 %, so the targets would jump between languages and scales.
- (c) One filled and one tonal. Rejected: neither choice is the "right" one; equal weight avoids nudging (Von Restorff is reserved for «Эхлэх»).

**7. «Миний байршил» in the start editor (AC 3).**
- (a) **Its own option card directly under the field**, shown whenever the start is empty or a chosen start, also while typing and while locked. **Chosen**: the most common correction is one tap, and the row keeps the same position in every editor state.
- (b) Only while the field is empty (web NAV-004 AC 5). Rejected: a user who started typing would have to clear the text to get back to their location.

## Layout

### S3 route preview, portrait (360×640 dp, mn, day). Collapsed is the default; TalkBack on opens it expanded
```
Device start, k = 3 (collapsed)          Chosen start + O1 (collapsed)             Chosen start, expanded, list
┌──────────────────────────────────┐     ┌──────────────────────────────────┐     ┌──────────────────────────────────┐
│     ┏━━━ alt                     │     │                                  │     │          (map, 3 routes)         │
│   ◉━┛━━━━━━━━━📍  (camera fits   │     │   ○━━━━━━━━━━📍   (○ = start     │     │┌────────────────────────────────┐│
│    routes + markers, 40 dp pad)  │     │     ┗━━ alt         marker §7.7) ││ ──── handle                    ││
│┌────────────────────────────────┐│     │┌────────────────────────────────┐││[✕]┌─────────────────────┐ [⇅] ││
││              ───               ││     ││              ───               │││   │○ Гандан хийд        │     ││
││[✕]┌──────────────────────┐ [⇅] ││     ││[✕]┌──────────────────────┐ [⇅] │││   │📍 Зайсан толгой      │     ││
││   │◎ Миний байршил       │     ││     ││   │○ Гандан хийд         │     │││   └─────────────────────┘     ││
││   │📍 Зайсан толгой       │     ││     ││   │📍 Зайсан толгой       │     │││ [🚗 Машин][🚶 Явган][🚲 Дугуй] ││
││   └──────────────────────┘     ││     ││   └──────────────────────┘     │││ Маршрут 1 · 18 мин · 7,4 км …  ││
││ [🚗 Машин][🚶 Явган][🚲 Дугуй] ││     ││ [🚗 Машин][🚶 Явган][🚲 Дугуй] │││ Маршрут сонгох  ○ ▬ Маршрут 1… ││
││ Маршрут 1                      ││     ││ Маршрут 1                      │││ Шороон замаас зайлсхийх   ◯   ││
││ 18 мин · 7,4 км                ││     ││ 18 мин · 7,4 км                │││ Маршрутын заавар               ││
││ Хүрэх цаг 14:08                ││     ││ Хүрэх цаг 14:08                │││ ◎ Өмнө зүг рүү явна уу  350 м  ││
││ [▶          Эхлэх            ] ││     ││ ⓘ Замчлал зөвхөн таны          │││   Гандангийн гудамж            ││
│└────────────────────────────────┘│     ││   байршлаас эхэлнэ             │││ ↱ Баруун тийш эргэнэ үү 2,1 км ││
├──────────────────────────────────┤     ││ [▷   Эхлэх (disabled)        ] │││   Энхтайвны өргөн чөлөө (scroll)│
│ © OpenStreetMap contributors     │     │└────────────────────────────────┘││ ⓘ Замчлал зөвхөн … (pinned)    ││
└──────────────────────────────────┘     ├──────────────────────────────────┤││ [▷   Эхлэх (disabled)        ] ││
                                         │ © OpenStreetMap contributors     ││└────────────────────────────────┘│
                                         └──────────────────────────────────┘│ © OpenStreetMap contributors     │
                                                                             └──────────────────────────────────┘
```

### Point editor and coordinate card (portrait 360×640 dp)
```
Start editor, typing "Gandan"            Start editor, typing lock engaged          Coordinate card during the preview
┌──────────────────────────────────┐     ┌──────────────────────────────────┐     ┌──────────────────────────────────┐
│┌────────────────────────────────┐│     │┌────────────────────────────────┐│     │        ○━━━━━━━━━━📍             │
││○ Эхлэх цэг                  [✕]││     ││○ Эхлэх цэг                     ││     │            ◇ candidate pin       │
││  Gandan|                       ││     ││  Гандан хийд  (not focused)    ││     │┌────────────────────────────────┐│
│└────────────────────────────────┘│     │└────────────────────────────────┘│     ││ Сонгосон цэг               [✕] ││
│┌────────────────────────────────┐│     │┌────────────────────────────────┐│     ││ 47.92120, 106.89480            ││
││◎ Миний байршил                 ││     ││◎ Миний байршил                 ││     ││ ────────────────────────────── ││
│└────────────────────────────────┘│     │└────────────────────────────────┘│     ││ Ойролцоох газар                ││
│┌────────────────────────────────┐│     │┌────────────────────────────────┐│     ││ Гандантэгчинлэн хийд           ││
││ Гандан хийд                    ││     ││🔒 Хөдөлж байх үед бичих        ││     ││ Хийд · Сонгинохайрхан …        ││
││ Хийд · Сонгинохайрхан дүүрэг…  ││     ││   боломжгүй                    ││     ││ [○   Эхлэх цэг болгох        ] ││
││ Гандангийн гудамж  (scrolls)   ││     ││   Жолооч бол зогсоод хайна уу  ││     ││ [📍  Очих газар болгох        ] ││
│└────────────────────────────────┘│     ││            [Хаах] [Би зорчигч] ││     │└────────────────────────────────┘│
│ © OpenStreetMap contributors     │     │└────────────────────────────────┘│     ├──────────────────────────────────┤
├──────────────────────────────────┤     │            (map)                 │     │ © OpenStreetMap contributors     │
│        (keyboard, 260 dp)        │     ├──────────────────────────────────┤     └──────────────────────────────────┘
└──────────────────────────────────┘     │ © OpenStreetMap contributors     │
                                         └──────────────────────────────────┘
```

### Wide window (landscape 640×360 dp)
The NAV-011 P5 side sheet (`clamp(320 dp, 40 %, 400 dp)`, start edge, always expanded) holds, top to bottom: the points block, the tabs, the summary region, the lower part (battery hint, «Маршрут сонгох», avoid switch, «Маршрутын заавар»), all in one scroll, and the pinned footer ([O1] + «Эхлэх»). The point editor and the coordinate card take the same column when they are open. The camera pads the start side by the column width + 40 dp.

### Layout rules (new or changed; NAV-011 P4, P6, P7 and NAV-005 rules 1–5, 7 still apply)
- **Q1. Sheet regions (replaces NAV-011 P1).** Portrait, top to bottom: handle zone (24 dp) → **top part** (points block, then the tab row) → **summary region** (summary, a state row, or a location message) → NAV-012 **battery entry row** when shown (collapsed only) → **footer**, pinned at the bottom in both states: **O1** when shown, then «Эхлэх» (56 dp; padding 8/12 dp; 1 dp `ui.outline-variant` top divider while expanded). Expanded: handle zone → **one lazy body** (points block, tabs, summary region, then the lower part: battery hint → «Маршрут сонгох» (*k* ≥ 2) → avoid switch («Машин» only) → «Маршрутын заавар») → footer. The NAV-011 points block at the end of the lower part is **removed** (the fields replace it).
- **Q2. What never scrolls (collapsed).** The summary region, the battery entry row and the footer (O1 + «Эхлэх») never scroll. The top part gives way first, and inside it the points block comes first, so the tabs scroll out before the fields do. At font scale 100 % on ≥ 360×640, the top part does not scroll in any route state (AC 27, measured), with one exception: a chosen start **and** the battery entry row (both hints, AC 30), where the points block stays fully visible and the tab row is scrolled by 26 dp.
- **Q3. Collapsed height cap (replaces NAV-011 P3's 60 %).** Collapsed = content height, at most **min(75 % of the area above R5, that area − band_min − 8 dp)** on ≥ 360×640, where band_min = **160 dp** at font scale 100 % and **96 dp** above (AC 27; NAV-011 P3 minimums unchanged). Below 360×640 the NAV-011 60 % stays. If the summary region, the entry row and the footer alone exceed the cap (font scale > 100 % only), the battery entry row joins the top part and scrolls after the points block and the tabs. Expanded: at most 80 % (unchanged). The camera fit uses the collapsed sheet's height + 40 dp (NAV-011 P3).
- **Q4. Points block.** Grid `48 dp | 1fr | 48 dp`, 4 dp column gap, 4 dp side padding, 4 dp bottom padding. Column 1: ✕ «Хаах» aligned with the start field. Column 2: one `ui.surface-container` container (`radius.md` 12 dp) holding the start field and the destination field, 48 dp each, separated by a hairline `ui.outline-variant` inset by 48 dp. Column 3: swap ⇅, vertically centred on the container. ✕ and ⇅ sit at opposite edges, so a bumped finger can't hit one for the other.
- **Q5. Point editor.** Replaces the sheet (hidden, not closed) in the S1/S2 position (NAV-005 D5 arrangements as built): 8 dp below the status bar, 16 dp margins (wide: the side-sheet column). The **editor field** (56 dp) is pinned; below it **one scroll** holds, in this order: the **option card** «Миний байршил» (start editor only), the NAV-011 **lock card** (when locked), the **results card** or its state row. The map stays visible below; R5 stays above the keyboard (as S2 as built). In landscape Android shows its full-screen keyboard for a single-line field while typing; the list is visible when the keyboard hides (Known limitations 3).
- **Q6. Coordinate card during the preview.** NAV-011 P6 order (title row, coordinates, nearest-place area) with the two stacked buttons last. The card replaces the sheet (hidden, not closed), is bottom-anchored, and the buttons keep the same position in all 7 reverse states (measured).
- **Q7. O1.** In the footer, above «Эхлэх», 8 dp gap; grid 20 dp icon `info` + text (`body` 14/20, `ui.on-surface-variant`, wraps, never truncated). It appears ≤ 200 ms after a route renders with a chosen start and disappears with the next response from a device start. TalkBack order: «Эхлэх» first, then O1 (AC 15; Accessibility).
- **Q8. Turn-row camera (AC 22).** Portrait with touch: the sheet collapses (NAV-011 P4 animation, 250 ms; reduced motion: jump) and the camera eases (700 ms, `motion.route-camera`) to `maneuver.location` at zoom max(17, current), centred in the band above the collapsed sheet with ≥ 40 dp padding on every side; both finish ≤ 1 s. TalkBack on or a wide window: no collapse; the camera centres the step in the uncovered map area with the same padding. The route, the selection and the list's scroll position stay; 0 requests.

## Components

### Ferrostar
No change (ADR-0009 §1: only `core`). The fields, editor, turn list and card are app components. The instruction text comes from the app's ADR-0008 Android rules (`instructions/ManeuverRules.kt`, the NAV-005 banner mapping), not from Ferrostar's banner text.

### Component specs (new or changed)
| Component | Source | Spec |
|---|---|---|
| **Points block** (new, replaces the NAV-011 header row) | Custom row | Q4 grid. Test tag `route-points`. |
| **«Хаах»** (moved) | M3 icon button | 48×48 dp, `close` 24 dp `ui.on-surface`, content description «Хаах». Closes the preview (NAV-011). Test tag `route-close`. |
| **Start field / destination field** (new) | Custom read-only "button field" on `ui.surface-container` | 48 dp row, padding 4/12 dp, gap 12 dp: leading 24 dp icon (decorative) + text (`body-large` 16/24, 1 line, ellipsis: it is a name). **Start icon and colour:** device start → `my_location` and text in `ui.primary` («Миний байршил»); chosen start → ring `trip_origin` in `ui.on-surface-variant`, text `ui.on-surface`; empty → ring, placeholder «Эхлэх цэг сонгох» in `ui.on-surface-variant`. **Destination:** pin glyph in `pin.fill`, text `ui.on-surface` (the destination is never empty, AC 7). Tap → point editor (Q5) or, while the typing lock is engaged, the locked editor (AC 34). Semantics: role button, content description «Эхлэх цэг» / «Очих газар» (AC 1), state description = the field text (or the placeholder). Test tags `route-origin`, `route-destination`. |
| **Swap** (new) | M3 icon button | 48×48 dp, `swap_vert` 24 dp `ui.on-surface-variant`; content description «Эхлэх цэг, очих газрыг солих». **Disabled while either point is empty**: icon `ui.disabled-content`, stays focusable, TalkBack reads it as disabled (AC 11). Tooltip on long-press (M3 plain tooltip) with the same text. Test tag `route-swap`. |
| **Mode tabs, summary, route options, avoid switch** | As NAV-011 | Unchanged. The options and the switch stay in the lower part (Q1). |
| **«Эхлэх» footer** (changed) | As NAV-011 + O1 | Pinned (Q1). Enabled only in the route state **with a device start** and precise permission (AC 15); otherwise disabled. With a chosen start in the route state, O1 sits above it (Q7). Test tags `nav-start`, `route-origin-hint` (O1). |
| **O1 hint** (new) | Custom text row | Q7. Not a live region (it appears with the route; TalkBack meets it right after «Эхлэх»). |
| **Turn list «Маршрутын заавар»** (new) | Items of the expanded body's `LazyColumn` | Last part of the lower part; only in the route state (never collapsed, AC 18). Heading «Маршрутын заавар» (`title` 16/24, 600, `ui.on-surface`, padding 12/16/4, 1 dp `ui.outline-variant` top divider; heading semantics). **Rows** (one per `legs[0].steps` of the selected route, `depart` first, `arrive` last): min height **56 dp**, padding 8/16, grid icon 24 dp · text · distance, gap 16 dp. Icon: the NAV-005 banner drawables (`ic_depart`, `ic_turn*`, `ic_slight*`, `ic_sharp*`, `ic_uturn`, `ic_straight`, `ic_fork*`, `ic_merge*`, `ic_ramp*`, `ic_roundabout_ccw`, `ic_flag`; same key → icon mapping as NAV-004 Content rules), `ui.on-surface-variant`, decorative, not focusable. Text: instruction (`body-large`, `ui.on-surface`, wraps, never truncated) and, on its own line, the street name (`body`, `ui.on-surface-variant`, 1 line, ellipsis; omitted when empty). Distance at the end (`body`, `ui.on-surface-variant`, tabular, no wrap; none on `arrive`). **From font scale 1.5 the distance moves under the text** (same threshold as NAV-011's stacked tabs) so a long word never overflows (measured). **Activated row** (its step is on the map): 4 dp start-edge bar in `route.selected` (pair checked, 5.37 / 7.14 : 1) plus the map's manoeuvre point. Test tags `route-steps`, `route-step`. |
| **Battery entry row / hint** (NAV-012) | As NAV-012 | Unchanged component. Order with NAV-018 (AC 30): collapsed = summary → entry row → O1 → «Эхлэх»; expanded = the hint first in the lower part, O1 pinned in the footer. Q3 last resort above 100 %. |
| **Point editor field** (new) | S1 search-bar shape (as built `SearchRow`) | 56 dp pill, `ui.surface`, elevation 2 (night: 1 dp `ui.outline-variant`). Leading 24 dp ring (start) or pin (destination), decorative. Two lines: label «Эхлэх цэг» / «Очих газар» (`label` 12/16, `ui.on-surface-variant`) and the input (`body-large` 16/24). Opens with the current field text **fully selected** (one keystroke replaces it; 0 requests on open), focus and keyboard on (unless locked). IME action Search. Trailing «Хайлтыг арилгах» (48 dp) while there is text. Placeholder when empty: «Эхлэх цэг сонгох» / «Очих газар сонгох». Test tag `point-editor`, semantics `editing` = `origin` \| `destination` (QA hook). |
| **Option card «Миний байршил»** (new) | Results-card look, one row | `ui.surface` card, `radius.md`, elevation 2, one 56 dp row: `my_location` 24 dp + «Миний байршил» (`body-large` 500, `ui.primary`). Start editor only; shown when the start is empty or a chosen start (AC 3), hidden when it already is «Миний байршил». **While a fix is awaited** (≤ 10 s, NAV-005 F3): trailing 20 dp progress indicator, and after 300 ms the supporting line «Ачаалж байна…». **On failure:** the NAV-005 message for the case appears inside this card under the row (text + its action, e.g. «Байршлын зөвшөөрөл олгоогүй байна» / «Утасны тохиргоонд байршлын зөвшөөрлийг асаана уу» / «Тохиргоо нээх»; «Байршил тодорхойлж чадсангүй»), the editor stays open, the start is unchanged, 0 route requests (AC 3). Test tag `point-option-my-location`. |
| **Lock card** | NAV-011 lock card | Unchanged component, placed after the option card (Q5). «Хаах» closes the editor (back to the preview, nothing changed); «Би зорчигч» focuses the field and opens the keyboard. |
| **Results card** | As built S2 results (NAV-005 / NAV-011) | Same rows, states and strings as the main search (AC 4). No own max height inside the editor (the editor body scrolls). Selecting a row closes the editor ≤ 500 ms. |
| **Coordinate card during the preview** (changed) | NAV-011 card | «Маршрут гаргах» is replaced by two **filled tonal** buttons (`ui.primary-container` / `ui.on-primary-container`), full width, 48 dp min, 8 dp apart, stacked: «Эхлэх цэг болгох» (leading ring icon) then «Очих газар болгох» (leading pin icon). Usable at once, never waiting for `reverse` (AC 6). Test tags `coord-set-origin`, `coord-set-destination`. |
| **Map markers** (changed) | map-style §7.7, §7.8 | Device start = the NAV-005 browse location dot with its accuracy circle (*wording updated 2026-10-04, NAV-005 section N, D171, D177*: the dot is the **shown** (filtered) position, while the route still starts at the raw good fix, so a held dot may sit some metres from the line's start; when stale it is a hollow grey ring with a dashed circle; no NAV-018 AC changes); chosen start = 18 dp ring marker; destination = pin; candidate = outline pin; manoeuvre point = `nav-route-step`. Content descriptions «Эхлэх цэг: <field text>» / «Очих газар: <field text>» (AC 8). |

### Content rules (additions)
- **Field texts** (AC 1, 4, 6): result selected → the result name (NAV-003 / NAV-005 S2 display rules); device position → «Миний байршил»; map point or chosen coordinate → «Сонгосон цэг» (never the nearest-place name, NAV-011 AC 9). In the English UI the result name stays as returned (D11); a Cyrillic name gets the `mn` locale for TalkBack.
- **Instruction texts** (AC 19, 20): exactly the NAV-004 AC 27 texts from the Android ADR-0008 rules and the existing `maneuver_*` resources (no second mapping, no new strings); the first letter as stored. The same key picks the icon.
- **Street names** (AC 18): `step.name` with U+200B, U+200C, U+200D, U+FEFF removed and trimmed; omitted when empty; never translated (D11); `mn` locale in the English UI.
- **Distances** in the rows: NAV-004 AC 23 formats («350 м», «2,1 км»; "350 m", "2.1 km"); no-break space between number and unit.
- **Same point** (AC 9): computed when a point is set or swapped (haversine ≤ 10 m), before any request.

## States

### S3 points and footer (the summary region follows NAV-005 / NAV-011 S3 precedence unchanged)
| State | Start field | Swap | Summary region | Footer | AC |
|---|---|---|---|---|---|
| Device start, route | ◎ «Миний байршил» (primary) | enabled | NAV-011 summary | «Эхлэх» enabled | 1, 15 |
| Chosen start, route | ○ result name / «Сонгосон цэг» | enabled | NAV-011 summary (+ snap notice for the larger of the two snaps > 500 m) | O1 + «Эхлэх» disabled | 14, 15 |
| No usable location on open (never asked, denied, approximate, services off, no fix in 10 s) | ○ empty, «Эхлэх цэг сонгох» | **disabled** | the NAV-005 location message for the case, with its action | «Эхлэх» disabled, no O1 | 2 |
| Waiting for the first fix (≤ 10 s) | ◎ «Миний байршил» (pending) | enabled | «Ачаалж байна…» after 300 ms (NAV-005) | disabled | 1 |
| Loading after a point change or swap | new text at once | enabled | old route removed at request start; region empty ≤ 300 ms, then spinner + «Ачаалж байна…» | disabled | 11, 13 |
| Same point | either | enabled | «Эхлэх цэг, очих газар ижил байна»; 0 requests | disabled | 9 |
| Outside the service area (either point) | either | enabled | «Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна» | disabled | 17 |
| Too far on «Явган» / «Дугуй», no route, unavailable, error | either | enabled | NAV-011 / NAV-005 texts unchanged (N11; «Маршрут олдсонгүй» + avoid hint; «Маршрутын үйлчилгээ түр ажиллахгүй байна» + «Дахин оролдох»; «Алдаа гарлаа») | disabled | 17 |
| Offline | either | enabled | «Интернэт холболт алга»; one request by itself ≤ 2 s after the network returns | disabled | 17 |
| Rate-limited (429) | updates with every change | enabled | «Түр хүлээгээд дахин оролдоно уу», «Дахин оролдох» disabled for `Retry-After`; fields and markers still update; 0 requests during the wait | disabled | 12 |
| GPS lost / permission revoked with a route | unchanged (the start is fixed when set) | unchanged | unchanged; 0 requests | device start: NAV-005 AC 10 rule for «Эхлэх»; chosen start: as before | 10 |
| After a swap that moved «Миний байршил» to the destination | ○ the old destination's text | enabled | route from it | O1 + disabled «Эхлэх» | 11, 15 |
| First use with TalkBack | as above | — | sheet opens expanded (NAV-011 P4) | — | 23 |
| Night / English | night tokens / `en` texts («My location», «Selected point»); result and street names unchanged | — | — | — | 24, 33 |

### Turn list
| State | What the user sees | AC |
|---|---|---|
| Route, expanded or wide | Heading and rows of the selected route; first row `depart`, last `arrive` | 18 |
| Collapsed sheet, or no route (loading, errors, no start) | No list | 18 |
| Another route selected | Rows of that route ≤ 200 ms; if the user had scrolled into the list, the heading is scrolled to the top | 21 |
| Row tapped | Sheet collapses (portrait touch), camera to the step ≤ 1 s, manoeuvre point drawn, row activated | 22 |
| New response | List of the new route 1, scrolled to its first row; activated row and manoeuvre point cleared | 21 |
| Very long route (500 steps) | Lazy rows; the body scrolls smoothly (device check) | 25 |
| Unnamed streets (countryside) | Rows without the street line; many «Чигээрээ явна уу» rows (R2) | 18 |
| Language switch | Labels and instructions switch ≤ 1 s, street names unchanged, scroll position kept, 0 requests | 24 |

### Point editor
| State | What the user sees | Keyboard | AC |
|---|---|---|---|
| Opened (start, chosen start) | Field with the current text selected; option card «Миний байршил» | open | 3 |
| Opened (start, device start) | Field «Миний байршил» selected; no option card | open | 3 |
| Opened (destination) | Field with the current text selected; nothing below until typing | open | 4 |
| Typing | NAV-011 assisted search: «Ачаалж байна…» after 300 ms, results, «Илэрц олдсонгүй», «Хайлт түр ажиллахгүй байна» + «Дахин оролдох», «Түр хүлээгээд дахин оролдоно уу», «Интернэт холболт алга» (0 requests) | open | 4 |
| Typed coordinates | What NAV-011 S2 shows at verification time (before D137: a normal search; after D137: its coordinate option, which sets «Сонгосон цэг») | open | 5 |
| «Миний байршил» chosen, waiting for a fix | Progress in the option card; «Ачаалж байна…» after 300 ms; the NAV-005 rationale dialog first if needed | open | 3 |
| «Миний байршил» failed | NAV-005 message inside the option card; editor stays; start unchanged; 0 route requests | open | 3 |
| Typing lock engaged on tap | Field not focused; option card (start); lock card K1, K3, «Хаах», «Би зорчигч» ≤ 500 ms | closed | 34 |
| Lock engages while typing | Keyboard hides ≤ 1 s, text kept, lock card appears after the option card, results stay selectable | hidden | 34 |
| Offline | Option card works (no network needed for the fix); results area shows «Интернэт холболт алга» | open | 4 |
| GPS lost / no permission | Search still works (D30 map-centre bias); «Миний байршил» runs the NAV-005 flow when tapped | open | 3 |

### Coordinate card during the preview
NAV-011 nearest-place states (pending, loading, place, empty, offline, unavailable, rate-limited, error) unchanged. In every state the title, coordinates, candidate pin and **both buttons** stay where they are and work at once (AC 6). GPS lost or no permission: no effect.

## Interactions
- **Tap a field:** point editor ≤ 300 ms (locked: ≤ 500 ms, AC 34). **Back / tap the map in the editor:** editor closes, the field shows its previous text, 0 requests (AC 7). **Select a result / «Миний байршил»:** editor closes ≤ 500 ms, the sheet returns in its previous state, 1 request when both points are set (AC 3, 4).
- **Swap:** ≤ 200 ms points, texts, icons and markers swap; 1 request (AC 11). Focus stays on the swap button. Disabled while a point is empty.
- **Long-press on the map in the preview:** coordinate card in place of the sheet, candidate pin. **«Эхлэх цэг болгох» / «Очих газар болгох»:** card closes ≤ 500 ms, sheet back, markers updated, 1 request when both points are set (AC 6). **«Хаах» / Back on the card:** back to the preview, 0 requests.
- **Disabled «Эхлэх»** (chosen start): no action, no dialog, 0 requests (AC 15).
- **Turn-row tap:** Q8. **Drag the sheet up again:** the list is where the user left it.
- **System Back:** point editor → closes it; coordinate card → preview; expanded sheet → collapses; collapsed sheet → closes the preview (NAV-011).
- **Rotation / theme / language:** points, swap state, list, selection, sheet state, editor text and card content kept; 0 route requests (AC 24, 33).
- **«Хаах»:** closes the preview; a chosen start is not kept for the next preview (AC 32).

## Copy (mn / en)
Every `mn` value is a glossary term. **O1 is `needs native review`** (glossary §2.6; the NAV-007 panel rates it; the PO may pre-review). Resource names are a proposal; the mobile engineer owns the final names. `mn` and `en` key sets are identical (AC 29).

### New keys
| Key (proposal) | mn | en | Glossary source |
|---|---|---|---|
| `route_origin_placeholder` | Эхлэх цэг сонгох | Choose starting point | N2 |
| `route_destination_placeholder` | Очих газар сонгох | Choose destination | N3 |
| `route_swap` | Эхлэх цэг, очих газрыг солих | Swap start and destination | N4 |
| `route_directions` | Маршрутын заавар | Directions | N5 |
| `route_set_origin` | Эхлэх цэг болгох | Set as start | N17 |
| `route_set_destination` | Очих газар болгох | Set as destination | N18 |
| `route_start_only_from_location` | Замчлал зөвхөн таны байршлаас эхэлнэ | Navigation starts only from your location | O1 (needs native review) |

### Reused keys (already in `values/` and `values-en/`)
`route_origin` («Эхлэх цэг» / "Start": field name, editor label, marker prefix), `route_destination`, `route_title` (pane title only), `marker_my_location`, `place_selected_point`, `place_nearest`, `action_close`, `action_retry`, `action_continue`, `action_open_settings`, `nav_start`, `route_same_point`, `route_out_of_area`, `route_too_far`, `route_no_route*`, `route_unavailable`, `route_rate_limited`, `route_snap_notice`, `route_option*`, `route_mode_*`, `route_avoid_unpaved`, `route_eta`, `search_*`, `status_*`, `location_*`, `typing_lock_*`, `battery_hint`, `maneuver_*`, `unit_*`.

### Length notes (Mongolian first)
- **Field text** is one line with ellipsis: 172 dp of text at 360 dp (about 19 Cyrillic letters at 16 sp, «Сүхбаатарын талбай» fits, «Гандантэгчинлэн хийд» is cut by one letter), 224 dp at 412 dp, 148 dp in the landscape side sheet. The full name is in the marker's and the field's TalkBack text, and the editor shows it whole when opened.
- **O1** «Замчлал зөвхөн таны байршлаас эхэлнэ» (36) and "Navigation starts only from your location" (41): one line at 360 dp and 100 % in both languages, two lines at 130 % and 200 % (measured); never cut.
- **Card buttons** «Эхлэх цэг болгох» (16) / «Очих газар болгох» (17) vs "Set as start" / "Set as destination": each has its own full-width row, so neither language wraps at 100 %; at 200 % they wrap inside the button (no clipping, checker).
- **Turn rows:** the longest instruction «Баруун талын гарах зам руу эргэнэ үү» (36) and «Таны очих газар баруун талд байна» (33) wrap to two lines at 360 dp; from font scale 1.5 the distance moves under the text, because "Roundabout:" at 200 % did not fit next to the distance (checker finding).
- **Placeholder** "Choose starting point" (21) is longer than «Эхлэх цэг сонгох» (16); both fit.

## Accessibility
- **Touch targets:** ✕, swap, fields 48 dp; editor field 56 dp; option and result rows ≥ 56 dp; card buttons 48 dp, 8 dp apart; turn rows ≥ 56 dp; «Эхлэх» 56 dp; lock card buttons 48 dp, 8 dp apart. Measured by the checker (AC 22, 28). ✕ and swap are at opposite edges of the block (Q4).
- **Contrast** (all pairs in `tokens.json › contrastPairs`, `check-contrast.mjs` passes): «Миний байршил» in `ui.primary` on `ui.surface-container` 5.74 / 7.75 : 1; field text `ui.on-surface` and placeholder `ui.on-surface-variant` on `ui.surface-container` ≥ 4.5 : 1; O1 and street names `ui.on-surface-variant` on `ui.surface` 9.39 : 1 (day); tonal buttons `ui.on-primary-container` on `ui.primary-container` 12.57 : 1; activated-row bar `route.selected` on `ui.surface` 5.37 / 7.14 : 1 (new pairs); origin marker ring and candidate pin as map-style §7.3. Disabled «Эхлэх» and swap are exempt but keep their names.
- **TalkBack reading order (S3, expanded):** pane title «Маршрут харах» (announced on open) → «Хаах» → start field («Эхлэх цэг», field text) → destination field («Очих газар», field text) → swap → tabs → summary → battery hint → «Маршрут сонгох» → avoid switch → «Маршрутын заавар» (heading, then the rows) → «Эхлэх» (disabled when so) → **O1** (AC 15, 23). The footer is one traversal group with «Эхлэх» before O1 although O1 is drawn above it.
- **Turn list semantics:** the heading has heading semantics; the rows are a collection named «Маршрутын заавар» with positions («3 of 7»: collection and item info on the rows); each row's content description = instruction, street name and distance joined with ", " (no distance on `arrive`), e.g. «Баруун тийш эргэнэ үү, Энхтайвны өргөн чөлөө, 2,1 км»; icons are not focusable. Double-tap = the Q8 camera move without collapsing (TalkBack branch); focus stays on the row (AC 22, 23).
- **Fields:** role button with the field name as the content description and the field text as the state description; empty start reads «Эхлэх цэг, Эхлэх цэг сонгох». Swap reads «Эхлэх цэг, очих газрыг солих», disabled while a point is empty (AC 11).
- **Point editor:** the field's accessible name is its label («Эхлэх цэг» / «Очих газар»); the option card row reads «Миний байршил», button; the results list keeps its NAV-005 name «Хайлтын илэрц». The lock card announces K1 once (NAV-011).
- **Markers:** «Эхлэх цэг: …» / «Очих газар: …» (AC 8). Map lines are not focusable; the options group and the list are the accessible equivalents.
- **Dynamic type:** uncapped, as NAV-011. The top part gives way first (Q2, Q3). No new text is cut mid-word at 200 % (checker). From 1.5 the turn-row distance stacks.
- **Colour is never the only signal:** device start = `my_location` icon **and** «Миний байршил» text **and** primary colour; chosen start = ring; the activated row = bar **and** the point on the map; disabled «Эхлэх» = disabled state **and** O1 text.
- **Reduced motion:** sheet collapse and camera move jump (done ≤ 500 ms).
- **No time limits.**

## Map
- **Markers and the manoeuvre point:** map-style §7.7. At most one start and one destination marker; the candidate pin only while the card is open.
- **Camera:** first render of a response = NAV-011 AC 16 fit (both markers included). Turn-row tap = Q8. Swap, point changes, opening the editor or the card, and loading never move the camera on their own.
- **Lines:** NAV-011 §7.6 unchanged. Old lines are removed when a new request starts (NAV-011 S3 pending state), so a line never ends at a marker that has moved (Design notes 4).

## Design rationale
| UX law / heuristic | How this design applies it | Deliberate trade-off |
|---|---|---|
| **Fitts's law** | «Эхлэх» stays full width at the bottom (O1 goes above it, Alternative 4). ✕ and swap at opposite edges of the points block. Fields, swap and card buttons are full 48 dp targets; the card buttons are stacked full-width so they never jump. The fields sit in the bottom sheet, in thumb reach | The sheet's visible title is gone; the pane title keeps it for TalkBack |
| **Hick's law** | One primary action per state: «Эхлэх» (or, on the card, two equal choices). The start editor offers one non-typing option. Swap is one icon, not a menu | No "choose on map" row in the editor (long-press is the way; Known limitations 4) |
| **Miller's law** | The points block is one chunk (from / to), the summary another, each turn row one manoeuvre + street + distance | — |
| **Jakob's law** | Google Maps / Apple Maps habits: from/to fields with a swap on the right, a leading close, a full search when a field is tapped, «Your location» as the first option, long-press for a point, a step list that shows the step on the map | Fields in the bottom sheet (Apple Maps, Waze) rather than at the top (Google Maps), for height and thumb reach (Alternative 1) |
| **Doherty threshold** | Swap ≤ 200 ms, editor ≤ 300 ms, set point ≤ 500 ms, O1 ≤ 200 ms, list selection ≤ 200 ms, row camera ≤ 1 s; «Ачаалж байна…» after 300 ms; the card buttons never wait for `reverse` | — |
| **Gestalt (proximity, common region, similarity)** | Both fields in one container with the swap beside them; the same ring / pin / `my_location` shapes in the fields, the card buttons and the map markers; the «Миний байршил» option in its own card, apart from search results | — |
| **Von Restorff** | One emphasis: the selected route and «Эхлэх». With a chosen start, the only coloured field text is gone (no `ui.primary`) and O1 is quiet grey text: the screen does not shout about the disabled state | — |
| **Serial position** | Start above destination everywhere (fields, card buttons); «Миний байршил» first in the start editor; the turn list starts with the next step and is the last item of the sheet so the switch and options are not buried after 300 rows | — |
| **Tesler's law** | The system decides when guidance is possible (O1) and fixes the start when set; assisted search accepts any script | O1 adds one line of text instead of a hidden rule |
| **Postel's law** | Cyrillic, Latin, Russian layout and abbreviations in both fields (NAV-011 assistance); typed coordinates follow the NAV-011 rule | — |
| **Peak–end rule** | Every failure keeps the user moving: «Миний байршил» failure stays in the editor with the NAV-005 fix-it action; same point and outside-area say what to change; O1 says how to get «Эхлэх» back | — |
| **Goal-gradient** | Not a guidance screen; the summary keeps distance, duration and «Хүрэх цаг» | — |
| **Zeigarnik effect** | Leaving the editor restores the field; the sheet state, list scroll position and activated row survive a row tap, rotation and language switches | A chosen start is not kept across previews (AC 32), on purpose |
| **Aesthetic–usability / prägnanz** | No new colours or tokens; one new component family (fields) built from the existing surface-container and search-bar shapes | — |
| **Cognitive load (taps to start)** | Search result → preview → «Эхлэх» stays **2 taps**. Back to «Миний байршил» from a chosen start: 2 taps (field → option) or 1 (swap back). Pickup by long-press: long-press → «Эхлэх цэг болгох» (2 actions) | Planning from a chosen start can't end in «Эхлэх» (Open question 1 (a)) |

**Nielsen heuristics checked:**
- **Visibility of system status:** the field icon and colour show whether the start is the phone; O1 explains the disabled «Эхлэх»; the option card shows the wait for a fix.
- **Match with the real world:** glossary terms only; «Сонгосон цэг» for map points, never a guessed address.
- **User control and freedom:** Back or a map tap leaves the editor without changes; «Хаах» on the card; swap is its own undo (swap twice).
- **Consistency and standards:** NAV-004 words and order, NAV-011 sheet, search and lock card, NAV-005 icons, M3 controls.
- **Error prevention:** no ✕ at the end of a field (Alternative 2); the destination can't be emptied; swap is disabled until both points exist; the same-point check runs before any request.
- **Recognition over recall:** the label line in the editor shows which point is being edited; «Миний байршил» is offered, not typed.
- **Flexibility and efficiency:** experts long-press or swap; novices use the editor.
- **Aesthetic and minimalist design:** the collapsed sheet shows from/to, mode, summary, «Эхлэх»; the list and options are one drag away.
- **Help users recover from errors:** each failure names what happened and the next step.
- **Help and documentation:** O1 is inline help.

Three needed a design change during the work: error prevention (the close button left the field row, Alternative 2), consistency (the stacked card buttons and the option card, so positions don't change between languages and lock states), and minimalist design (the visible title went, after the measurement in Alternative 1).

## Design notes (decisions inside the AC)
1. **Header row replaced by the points block** (Alternative 1a). The NAV-011 destination line in the header and the display-only points block in the lower part both go; the fields carry the same content descriptions.
2. **Point editor = the S1/S2 search surface** (Alternative 3b), so AC 4 "the existing search code" is also the existing search UI. AC 7's "the other field" path does not exist (one field is edited at a time); Back and a map tap are the ways out.
3. **O1 above «Эхлэх»** (Alternative 4a); TalkBack order «Эхлэх» → O1.
4. **Old route lines are removed when the new request starts** after a point change or swap (NAV-011 S3 pending state, NAV-004 AC 32), while the markers move at once. AC 6's second bullet says the preview returns "with the existing route lines until the new response arrives": the screen spec follows the NAV-011 rule because a line from the old start to the new markers would show a route the user did not ask for. Raised for the BA (handoff, non-blocking).
5. **Turn-row tap collapses the sheet** in portrait touch use (Alternative 5a); not with TalkBack or in wide windows.
6. **«Миний байршил» failures stay in the editor** (in the option card), not in the summary region, so a chosen start's route is not replaced by a location message the user did not ask about.
7. **Collapsed cap rule** changes from NAV-011's 60 % to min(75 %, area − band − 8 dp) on ≥ 360×640 (Q3), because the fields add 53 dp and AC 27's 160 dp band is the real constraint. The NAV-011 and NAV-012 Compose tests that assert "≤ 60 %" change with this story (handoff).
8. **BA open questions:** the design follows the BA defaults: (1) (a) «Эхлэх» disabled with O1; (2) (a) NAV-005 messages plus the empty start field; (3) (a) the chosen start is not kept. Only Q7 / O1 and the «Эхлэх» footer row change if the PO picks another option for (1).

## Known limitations (design)
1. **Chosen start plus the battery entry row at 360×640 / 100 %:** the tab row is scrolled by 26 dp in the collapsed sheet (the points, summary, row, O1 and «Эхлэх» stay). The tabs are one scroll or one drag away; AC 30 asks only that both hints are visible after expanding. On 412×915 nothing scrolls.
2. **Expanded sheet with a chosen start at 130 % on 360×640:** the two-line O1 in the pinned footer leaves less than 48 dp of the lower part visible before scrolling (checker INFO). The body is plainly scrollable (expanded sheet); at 100 % 73 dp are visible.
3. **Landscape typing** uses Android's full-screen keyboard; the results show when the keyboard hides (checker INFO for the 12 landscape keyboard combinations). Same as S2 today.
4. **No "choose on map" row** in the editor: no glossary term exists, and long-press is the NAV-005 gesture. Long-press is not discoverable for new users (as on S1). A future glossary row could add it.
5. **The turn list is not in the collapsed sheet** (AC 18). Users find it by dragging up; TalkBack users get it expanded.
6. **320×568 above 100 %** stays outside the design target (D66): checker INFO lines.
7. **The prototype draws the map statically;** the camera fit, the row camera move, the lazy list and scrolling a 500-step list are QA and device checks (AC 25, 38).

## AC traceability
| AC | Where |
|---|---|
| 1, 2 | Components › fields; States › S3 (device start, no usable location); flow F1 |
| 3 | Option card «Миний байршил»; States › point editor; Alternative 7; flow F2 |
| 4, 5 | Point editor field, results card; States › point editor; Design note 2; flow F2 |
| 6 | Coordinate card during the preview; Q6; Alternative 6; Design note 4; flow F3 |
| 7 | Interactions (Back / map tap); Design note 2 |
| 8 | Map markers; map-style §7.7 |
| 9, 10 | Content rules (same point); States › S3 (GPS lost); flow F1, F4 |
| 11, 12 | Swap; States › S3 (loading, 429, after a swap); flow F4 |
| 13, 14, 16, 17 | States › S3; flow F4, F5 (request bodies are not UI) |
| 15 | O1 hint, «Эхлэх» footer, Q7; Alternative 4; flow F5 |
| 18–21, 24–26 | Turn list component, States › turn list, Content rules; flow F6 |
| 22 | Q8; Alternative 5; map-style §7.7 |
| 23 | Accessibility (turn list semantics, reading order) |
| 27 | Q1–Q4; Evidence (360×640, both languages, both themes, 200 %, wide) |
| 28 | Accessibility (targets, contrast, 200 %); Evidence |
| 29 | Copy |
| 30 | Battery entry row / hint order; Q2, Q3; Known limitations 1 |
| 31, 32, 35–38 | No UI (privacy, session, regression, verification); Interactions (close) |
| 33 | Interactions (rotation, theme, language); States (night / English) |
| 34 | Lock card in the editor; States › point editor; flow F2 |

## Evidence
- **Prototype:** `prototypes/NAV-018-android-points.html`, 26 states: 13 preview (device / chosen start collapsed and expanded, with and without the battery row and hint, no usable location, loading after a swap, same point, outside the area, rate-limited, scrolled to an activated turn row), 6 point editor (start, start with results, destination with results, start offline, start locked, destination locked; keyboard simulated at 260 dp portrait / 170 dp landscape), 7 coordinate card states with the two buttons.
- **Layout checker:** `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav018.mjs` covers 360×640, 412×915, 320×568 and 640×360 dp (24 dp status bar; 48 / 24 dp navigation bar) × 26 states × day/night × mn/en × font scale 1.0/1.3/2.0 = **1,248 combinations**, Liberation Sans.
  - **Result 2026-10-04: 0 problems**, 136 INFO lines outside the target (320×568 above 100 %, landscape with the keyboard, non-route band, Known limitations 1–3).
  - Stress run `--wide` (DejaVu Sans): **0 problems**, 148 INFO lines.
  - Measured at 360×640, 100 %, mn and en identical:

    | State (collapsed) | Sheet | Map band | Top part scrolls |
    |---|---|---|---|
    | NAV-011 baseline (header row, *k* = 3) | 274 dp | 238 dp | no |
    | Device start | 326 dp (60 %) | 210 dp | no |
    | Chosen start + O1 | 354 dp (65 %) | 182 dp | no |
    | Device start + battery row | 374 dp (69 %) | 162 dp | no |
    | Chosen start + O1 + battery row | 376 dp (69 %) | 160 dp | 26 dp (tabs) |

    At 130 % / 200 % the minimum band in a route state is 128 / 124 dp (≥ 96). 412×915: band ≥ 409 dp at 100 %, ≥ 214 dp at 200 %.
  - Expanded (360×640, 100 %): sheet 435 dp (80 %); 101 dp of the lower part visible (device start), 73 dp (chosen start, O1 in the footer); turn rows 60 dp with a street line.
  - Point editor with the keyboard (360×640, 100 %): 196 dp of list (option + 2½ results); 412×915: 245 dp.
  - Coordinate card: both buttons at the same position in all 7 reverse states, every viewport, theme, language and scale in the target.
  - **Alternatives measured (`v=b`, `v=c`):** header row kept → band 160 dp with the top part scrolling 26 dp for a chosen start at 100 % (fails AC 27); O1 beside the button → 2 lines at 100 %, 4 at 130 %, the button 111–156 dp wide.
  - **Iterations the checker forced:**
    1. The battery row with a device start cut the tab indicator by 11 dp → points block padding 8 → 4 dp, hairline divider, entry row flush under the summary (Q4).
    2. "Roundabout:" at 200 % overflowed next to the distance → distance stacks under the text from 1.5 (Turn list).
    3. Landscape locked editor pushed «Миний байршил» below the fold and the lock card over the attribution → pinned field with one scrolling body; option card before the lock card (Q5, Alternative 7).
    4. Chosen start + battery row at 200 % with a wide font left 53 dp of map → Q3 last resort (entry row joins the top part above 100 %).
- **Contrast:** `node docs/design/prototypes/check-contrast.mjs` → **all checks passed** (150 contrast pairs including the 2 new ones, flavor key parity, 80 map-style values).
- **Mermaid:** the 7 diagrams in the flow parse with Mermaid's parser.
- **Timing budgets** (delegated to QA, JVM/Robolectric, light-QA mode): AC 2 (500 ms message removed), AC 4 (500 ms list closes), AC 6 (500 ms card closes), AC 9 (500 ms N10), AC 11 (200 ms swap), AC 15 (200 ms O1), AC 21 (200 ms list follows selection), AC 22 (1 s camera), AC 24 (1 s language), AC 34 (500 ms lock card). On-device timing, scrolling and the camera move are real-device checks (AC 38, not verified: no Android test phone).

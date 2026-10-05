# Screen: Android demo build — route picker, demo badge, pause control and demo-mode states (NAV-019)

- **Stories:** NAV-019 (AC 1–44; UI-relevant AC 1, 6–11, 15–17, 19, 22–27, 30–32, 35–39, 43). Traceability per AC at the end. PO decisions D154 (option B, 2026-10-04) and D155 (light process). The orchestrator defaults **R1–R7 are proposed defaults, PO to confirm** (story Open questions 1–7). This spec is written against them and says where a different PO answer would change it.
- **Platforms:** **Android, `demo` build type only** (ADR-0016 §2). Debug and release builds are unchanged. The web counterpart is NAV-017 ([`NAV-017-web-demo-mode.md`](NAV-017-web-demo-mode.md)); iOS has no demo build.
- **Base screens (reused unchanged, only the deltas below differ):** [`NAV-005-android-navigation.md`](NAV-005-android-navigation.md) (S3 preview, S4 location rationale, S5 guidance, S6 arrival, S7 settings, S8 notification), [`NAV-011-android-route-preview-search-parity.md`](NAV-011-android-route-preview-search-parity.md) (sheet behaviour, typed coordinates, typing lock), [`NAV-012-android-background-lock-screen.md`](NAV-012-android-background-lock-screen.md) (N1 notification, L1 lock screen, H1/H2 battery hint), [`NAV-018-android-origin-turn-list.md`](NAV-018-android-origin-turn-list.md) (points block, point editor, turn list «Маршрутын заавар»). Design pattern of the picker and badge: NAV-017 D1 and RD.
- **Flow:** [`flows/NAV-019-android-demo-mode.md`](../flows/NAV-019-android-demo-mode.md) (F0 install, F1 open and tiles, F2 pick and preview, F3 start and permissions, F4 replay states with pause, F5 background, lock screen and interruptions, F6 end and arrival, F7 demo-mode unavailable states).
- **Rules:** [`navigation-ux.md`](../navigation-ux.md) §1–§10 and §12 apply unchanged to the replay; **§13 (new, v0.9)** lists the demo-only timing rules (pause, resume, end of track, offline indicator).
- **Architecture:** [ADR-0016](../../architecture/adr/0016-android-demo-mode.md) (written in parallel): `ReplayVariant` slots `StartScreen`, `GuidanceControls` and `Badge`; `ReplayLocationSource`; `DemoNetworkBlock`; tiles copied once and opened as `pmtiles://file://`. No API operation is used (0 requests, AC 33).
- **Prototype:** [`prototypes/NAV-019-android-demo.html`](../prototypes/NAV-019-android-demo.html), 1 CSS px = 1 dp, 12 states, day/night, mn/en, font scale 1.0/1.3/2.0, portrait and landscape; layout alternatives `v=b`, `v=c` in the hash. Checked by `prototypes/check-layout-nav019.mjs` (Evidence). The preview sheet in the wireframe is a simplified NAV-018 collapsed sheet; the NAV-018 wireframe and checker stay authoritative for the sheet itself. No Figma file.
- **Tokens:** [`tokens.json`](../tokens.json) v0.6.1, **no change**. Reused: `demo.badge`, `demo.on-badge`, `demo.badge-outline` (NAV-017), `ui.primary-container` / `ui.on-primary-container` (pause), `ui.primary` / `ui.on-primary` (resume), NAV-005 `nav.*`.

## Purpose
Let the PO open the demo build on their own Android phone, pick one of three recorded Ulaanbaatar routes, check it in the real route preview, press «Эхлэх» and judge the real guidance (banner, voice or chime, notification, lock screen, Bluetooth, calls, battery savers) while a simulated position drives along the recorded track, with no server, and never mistake the simulation for real navigation.

## Context and goal
| Surface | Primary task | Moment of use and time budget |
|---|---|---|
| **P1 route picker** (new) | Choose which recorded route to test | PO at a desk, in a parked car or as a passenger, preparing a test run. 2–5 s to read three entries and tap one |
| **P2 preview** (NAV-005/011/018 S3 + badge) | Confirm the route, read the turn list, start | Same; 5–30 s. «Эхлэх» is one tap away and always enabled for a loaded entry (AC 10) |
| **P3 guidance** (NAV-005 S5 + demo row) | Watch and listen to the guidance like a driver; pause to set up Bluetooth or take a call; end | One glance < 1 s for the banner (unchanged NAV-005 budget). The pause control is used rarely, by a stationary tester, but must be findable in < 2 s |
| **P4 arrival** (NAV-005 S6 + badge) | See the arrival, go back to the picker | One glance; one tap «Хаах» |
| Notification, lock screen (NAV-012) | Test background behaviour | Unchanged NAV-012 budgets; the demo label identifies the app |

**Who:** the PO (reference persona: UB commuter by car; secondary checks for the taxi driver, the pedestrian and the English-speaking tourist, story › Story). The PO already knows the NAV-017 web demo ("three demo simulation works great"), so P1 and the badge copy its patterns and words.

### Alternatives considered

**1. Picker pattern (AC 6, 9).**
- (a) **NAV-017 web pattern:** radio list, the selected route drawn on the picker map, «Эхлэх» in the picker footer. Rejected for Android: R4 and AC 9 require the normal NAV-005/011/018 preview anyway, so a picker «Эхлэх» would duplicate it (two start moments, one extra tap to select before acting), and the preview is exactly what the PO must test.
- (b) **Tap-to-open list in a bottom sheet over the map. Chosen.** One tap on an entry opens the preview, the way a search result opens the preview in the real app (NAV-005 S2 → S3). The map behind the sheet shows from the first second whether the bundled tiles work (AC 35, 36) and carries the attribution.
- (c) Full-screen list page without the map. Rejected: a broken tile archive would only show after an entry is chosen, it looks like a different app, and it adds a screen type.
- (d) The routes as suggestions in the S1 search view. Rejected: the field invites typing that can only answer «Хайлт түр ажиллахгүй байна», and the demo nature is hidden.

**2. Where the badge and the pause control go on guidance (AC 17, 22). Measured with the checker (Evidence).**
- (a) **Demo row RD directly below the banner: badge left, «Түр зогсоох» right (labelled tonal button). Chosen.** Both demo-only items sit in one row, outside every NAV-005 region, so the real guidance layout under it is untouched; W2 is visible as text (it is new and needs native review); pause and «Дуусгах» are at opposite ends of the screen. Cost: 56 dp (70 dp at font scale 1.3, badge on two lines) of map band. Result: **0 problems** in the design target; under the stricter NAV-005 target 48 problems, all on 360×640-class phones above font scale 1.0 (A1 window, recenter worst case) and 320×568 with A1.
- (b) Badge at the bottom-left of the map band, pause as an icon-only third button of the control pair (`v=b`). Zero top cost, but: the pause loses its label (W2 only in the tooltip and TalkBack), the recenter has to stack above the badge, and in landscape the map column is too narrow for badge + three buttons. 48 problems under the NAV-005 target, including 640×360 at 1.3/2.0 and 412×915 overlaps.
- (c) RD with the badge only, icon-only pause in the control pair (`v=c`). 28 problems under the NAV-005 target (A1 window on 360×640 and 320×568, landscape recenter clipped), and the same unlabelled pause. Fewer failures than (a) but none of the gains: it still cannot meet the A1-window minimum on 360×640 above 1.0.
- (d) Pause in the progress panel next to «Тохиргоо» and «Дуусгах». Rejected without measuring: three 48 dp buttons push «Хүрэх цаг 14:35» to two lines at 360 dp, and pause would sit 8 dp from the destructive «Дуусгах» (Fitts, error prevention).
- (e) Badge inside the banner card. Rejected: the banner holds only the manoeuvre (Von Restorff); TalkBack's live region would read "demo mode" as part of an instruction.
- **Why (a) despite more strict failures than (c):** no layout with a persistent badge meets the NAV-005 A1-window minimum on a 360×640 phone above font scale 1.0 (NAV-005 itself has 15 dp to spare there). Given that, the labelled pause and the untouched real layout win; the limitation is bounded by measurement (360×780 passes every rule at every font scale) and recorded (Known limitations 1).

**3. Badge on the preview (AC 17).**
- (a) **Floating pill at the top-left of the map, 8 dp below the status bar. Chosen.** Same pill as on guidance, top area, nothing in the NAV-018 sheet moves. The camera fit pads the top by the badge height + 40 dp.
- (b) Inside the sheet (a row above the points block). Rejected: NAV-018 measured the collapsed sheet to the dp (cap Q3); a 32 dp row would push the battery-row case over the cap.

**4. What the preview controls that would need a new route do (AC 11, 31).**
- (a) **Keep them enabled; they show the real "unavailable" states with 0 requests (ADR-0016 §7 interceptor). Chosen** for the mode tabs, the avoid switch, swap, the start and destination fields (point editor and its search, typed coordinates) and long-press on the preview map. The PO sees the real screen and the real failure states (BA recommendation (a) for Open question 5).
- (b) Disable them. Rejected: a preview with half its controls greyed out reads as broken, and a disabled control gives no reason.
- **Hidden instead** (would need the device position, which the demo never reads, AC 13): the «Миний байршил» option in the start editor and the my-location button. See Components › Hidden or changed in the demo build.

## Screens in this spec
| ID | Surface | Pattern | Main AC |
|---|---|---|---|
| **P1** | Route picker (the demo build's home, replaces S1) | M3 standard bottom sheet, list items | 6–8, 35–37 |
| **P2** | Route preview | NAV-018 S3 sheet unchanged + badge pill + AC 10 «Эхлэх» rule | 9–11, 17, 31 |
| **P3** | Guidance | NAV-005 S5 unchanged + demo row RD | 12–22, 32 |
| **P4** | Arrival | NAV-005 S6 + RD (badge only) | 24 |
| — | Notification N1, lock screen L1, settings S7, location rationale S4, notification-permission dialog | Unchanged; demo label in the notification header; RD over the lock screen | 26–28 |

## Layout

Edge-to-edge as NAV-005 (system-bar insets added by every region). 1 dp grid; side gutter 16 dp (8 dp for the guidance banner and RD).

### P1 picker, P2 preview, P3 paused (portrait 360×640 dp, mn, day; measured in the wireframe)
```
P1 picker (entry 1 failed to load)       P2 preview (collapsed NAV-018 sheet)     P3 guidance, paused
┌──────────────────────────────────┐     ┌──────────────────────────────────┐     ┌──────────────────────────────────┐
│ (NAV-002 message, if any: loading│     │[⚗ Туршилтын горим]  badge pill   │     │┌────────────────────────────────┐│ RB
│  pill or tiles card)        PM   │     │                                  │     ││┌────┐ 300 м                    ││
│                                  │     │      (map: route, markers)       │     │││ ↱  │ Баруун тийш эргэнэ үү    ││
│        (map backdrop, P1 z12)    │     │                                  │     ││└────┘ Энхтайвны өргөн чөлөө    ││
│                                  │     │┌────────────────────────────────┐│     │└────────────────────────────────┘│
│┌────────────────────────────────┐│     ││              ───               ││     │[⚗ Туршилтын горим] [▶ Үргэлжлүүлэх]│ RD
││ Туршилтын горим           [⚙]  ││ PH  ││[✕]┌──────────────────────┐ [⇅] ││     │                                  │
││ Маршрут сонгох                 ││ PL  ││   │○ Сүхбаатарын талбай  │     ││     │              ●  puck (still)     │ map
│├────────────────────────────────┤│     ││   │📍 Зайсан Голден Вилл  │     ││     │                      [🔊] [⬆]   │ RC
││○ Сүхбаатарын талбай            ││ E1  ││   └──────────────────────┘     ││     │┌────────────────────────────────┐│ RP
││📍 Зайсан Голден Вилл            ││     ││ [  Машин ][ Явган ][ Дугуй ]   ││     ││ Хүрэх цаг 14:35        [⚙] (✕)││
││Машин · 4,3 км · 5 мин          ││     ││ 5 мин · 4,3 км                 ││     ││ 4 мин · 3,1 км   (frozen)      ││
││   ◯ Алдаа гарлаа               ││     ││ Хүрэх цаг 14:08                ││     │└────────────────────────────────┘│
│├────────────────────────────────┤│     ││ [▶          Эхлэх            ] ││     │                                  │
││○ Сүхбаатарын талбай       E2   ││     │└────────────────────────────────┘│     │                                  │
││📍 Хаан банк                     ││     ├──────────────────────────────────┤     ├──────────────────────────────────┤
││Явган · 1,3 км · 16 мин         ││     │ © OpenStreetMap contributors     │     │ © OpenStreetMap contributors     │ R5
│├────────────────────────────────┤│     └──────────────────────────────────┘     └──────────────────────────────────┘
││○ Сонгосон цэг             E3   ││
││📍 Золтамир                      ││
││Машин · 9,2 км · 1 ц 11 мин     ││
│└────────────────────────────────┘│
├──────────────────────────────────┤
│ © OpenStreetMap contributors     │ R5
└──────────────────────────────────┘
```
(⚗ is the badge's decorative flask icon, [⚙] «Тохиргоо», ○ the start ring, 📍 the destination pin. Distances and durations are the recorded responses' values in NAV-004 formats; the names are manifest data.)

### P3 guidance, landscape (640×360 dp)
```
┌───────────────────────────┬────────────────────────────────────────────┐
│┌─────────────────────────┐│                                            │
││┌────┐ 300 м             ││              (map, route)  ●               │
│││ ↱  │ Баруун тийш       ││                                            │
││└────┘ эргэнэ үү         ││                                            │
│└─────────────────────────┘│ [◎ Байршил руу буцах]          [🔊] [⬆]    │
│[⚗ Туршилтын горим]        │┌──────────────────────────────────────────┐│
│[⏸ Түр зогсоох]  (RD wraps)││ Хүрэх цаг 14:35                 [⚙] (✕) ││
│ (column scrolls if needed)│└──────────────────────────────────────────┘│
├───────────────────────────┴────────────────────────────────────────────┤
│ © OpenStreetMap contributors                                           │
└────────────────────────────────────────────────────────────────────────┘
```
P1 and P2 in landscape: the sheet is the NAV-011 P5 side sheet (start edge, `clamp(320 dp, 40 %, 400 dp)`); the badge pill (P2) and the NAV-002 message (P1) sit at the top of the map column.

### Regions (new or changed)
| Region | Surface | Content | Portrait | Landscape |
|---|---|---|---|---|
| **PM** message | P1 | NAV-002 loading pill «Ачаалж байна…» (tile copy) or the tiles card «Газрын зургийг ачаалж чадсангүй» + «Дахин оролдох» | Top, 8 dp below the status bar, 8 dp sides (pill centred) | Top of the map column |
| **P1 sheet** | P1 | PH heading row, PL list label (both pinned), the list (E1–E3) | Bottom, 8 dp sides and bottom, above R5. Height = content, at most **min(75 % of the area above R5, that area − PM − 8 dp − 48 dp)** (rule P2) | Side sheet column, full height above R5 |
| **Badge pill** | P2 | Badge «Туршилтын горим» | Top-left, 8 dp below the status bar, 16 dp from the start edge (+ insets) | Top-left of the map column |
| **RD** demo row | P3, P4 | Badge (start) · pause / resume button (end); P4 badge only | Directly below RB (RB + RT), 8 dp gap, 8 dp sides; **one row**, never scrolled away | Below RB in the left column; may wrap to two rows (the column scrolls) |
| everything else | P2–P4 | NAV-005 / NAV-011 / NAV-012 / NAV-018 regions | unchanged | unchanged |

### Layout rules (new; NAV-005 Layout rules 1–7, NAV-011 P3–P7 and NAV-018 Q1–Q8 still apply)
- **P1. Attribution on every map screen** (AC 37): R5 is its own region below the picker sheet, the preview sheet and the guidance panels, in every state, theme, language, orientation and font scale. Nothing intersects it (measured).
- **P2. Picker sheet height:** content height, capped at min(75 % of the area above R5, that area − PM − 8 dp − 48 dp), so at least 48 dp of map stays visible between PM and the sheet and the sheet never reaches PM or R5. The heading row and the list label are pinned; only the list scrolls.
- **P3. Picker entries visible without scrolling:** all three at font scale 1.0 on ≥ 360×640 portrait; at least one whole entry at ≤ 1.3 everywhere; at 2.0 at least 48 dp of the first entry. When the pinned part plus the first entry do not fit (font scale 2.0, landscape), the **whole sheet scrolls** instead of only the list.
- **P4. Exactly one «Туршилтын горим» on screen** (AC 17): P1 heading, P2 badge pill, P3/P4 badge in RD; never two at once, never none.
- **P5. RD never covers the banner, the progress area or the attribution** (AC 17), and is never scrolled out of view: the NAV-005 50 % cap and inside scrolling apply to RB only; RD is laid out below it. RC, the recenter, RS and RN keep their NAV-005 places relative to the band, which now starts below RD.
- **P6. Camera padding** (NAV-005 rule 2) adds RD to the covered top edge in P3/P4, and the badge pill + 40 dp to the top edge of the P2 route fit (NAV-011 P3 still pads the bottom by the sheet). The puck stays at 70 % of the uncovered band.
- **P7. RD is one row in portrait:** the badge text wraps inside the pill (up to 2 lines) instead of the row wrapping; the pause button never shrinks or truncates its label. In landscape the row may wrap (no band rule in landscape).
- **P8. Font-scale caps:** RD follows the NAV-005 guidance-overlay cap (1.3, navigation-ux §2.5); P1 and the P2 badge pill scale without a cap.

## Components

### Ferrostar
No change. ADR-0009 §1 (only `core`) and ADR-0016: the simulated fixes enter the same guidance engine through `ReplayLocationSource`; every visible component is the app's own.

### Component specs (new or changed)
| Component | Source | Spec |
|---|---|---|
| **P1 picker sheet** | M3 standard bottom sheet (non-modal, not draggable, no handle) | `ui.surface`, `radius.lg` 16 dp, elevation 2 (night: 1 dp `ui.outline-variant`). Column: PH, PL, list. Test tag `demo-picker`. |
| PH heading row | Custom | Heading `demo_mode` «Туршилтын горим» (`typography.title` 16/24 **600**, `ui.on-surface`, wraps, never truncated; semantics `heading()` and the pane title of the picker) + standard icon button 48 dp, gear 24 dp, content description `settings_title` «Тохиргоо» (opens S7, AC 7: 1 tap). Padding 8 4 0 16. **No close button**: the picker is the home of the demo build. Test tags `demo-heading`, `demo-settings`. |
| PL list label | Custom | `route_options` «Маршрут сонгох» (`typography.label` 14/20 500, `ui.on-surface-variant`), padding 4 16. Used as the list's collection name for TalkBack. |
| **Entry row** E1–E3 (AC 6) | M3 list item (three-line), whole row clickable | Min height 56 dp (measured 85 dp at 100 % on 360 dp), padding 8 16, 1 dp `ui.outline-variant` divider above. Grid 16 dp marker column · 12 dp · text: line 1 start ring (12 dp, 2 dp `ui.on-surface` stroke) + origin name; line 2 destination pin (12 dp, `pin.fill`) + destination name, both `body-large` 16/24, **wrap, never truncated** (names are data, the PO must read them); line 3 meta `body` 14/20 `ui.on-surface-variant`, tabular figures: `route_mode_car` «Машин» / `route_mode_walk` «Явган» + " · " + distance (NAV-004 AC 23) + " · " + duration (NAV-004 AC 24), e.g. «Машин · 4,3 км · 5 мин», «Машин · 9,2 км · 1 ц 11 мин». Order = manifest order (r1, r2, r3). Ripple on tap; no trailing icon, no radio, no selected state (a tap opens P2 at once). Names carry `LocaleList("mn")` in the English UI. An end with no OSM feature (`osm: null`, the R3 origin) shows `place_selected_point` «Сонгосон цэг» / "Selected point" in the UI language (story › User-facing strings, T6). Test tag `demo-entry`, semantics `entry` = `r1|r2|r3`. |
| Entry error row (AC 8) | NAV-004 state row look, inline | Directly **under the failed entry**, inside the list: error icon 24 dp `ui.error` + `status_generic_error` «Алдаа гарлаа» (`body`), padding 8 16 12 44 (aligned with the names). `liveRegion = Polite`. No «Дахин оролдох»: a broken packaged file does not repair itself; the other entries are the way on. Cleared when another entry is tapped successfully. Test tag `demo-entry-error`. |
| PM loading pill / tiles card (AC 35, 36) | NAV-002 message components, unchanged | Pill «Ачаалж байна…» after 300 ms while the tile copy runs (≤ 30 s target). Card «Газрын зургийг ачаалж чадсангүй» + text button «Дахин оролдох» (48 dp) within 5 s of a failed copy or header check; the button re-runs the copy (ADR-0016 §9). The picker list stays usable in both. |
| **Badge** (P2 pill, RD in P3/P4; AC 17) | Custom, the NAV-017 badge in dp, **not interactive** | Pill, min height 32 dp, padding 4 12 4 8, radius 16 dp, `demo.badge` background, `demo.on-badge` text and icon, 1 dp `demo.badge-outline`, elevation 1. Decorative 18 dp flask icon + `demo_mode` «Туршилтын горим» (`typography.label` 14/20 **600**). Text wraps inside the pill (≤ 2 lines), never truncated. TalkBack: plain text node, read after the banner (P3) or first on the screen (P2); no role, not focusable as a control. Test tag `demo-badge`. |
| **Pause / resume button** (RD; AC 22) | M3 **filled tonal** button (running) / **filled** button (paused), 48 dp | Running: `ui.primary-container` / `ui.on-primary-container`, pause icon 18 dp + `demo_pause` «Түр зогсоох» (W2). Paused: `ui.primary` / `ui.on-primary`, play icon + `action_continue` «Үргэлжлүүлэх» (A7). `radius.full`, padding 8 16 8 12, `typography.label` 14/20 500 (cap 1.3), label never truncated (`softWrap = false`; the badge gives way, rule P7). Content description = the visible label; after a tap focus stays on the button and TalkBack announces the new label. Responds within 1 s (AC 22). Hidden on P4 (nothing to pause). Only present if the pause of R4 is confirmed (story Open question 4); without it RD holds the badge alone. Test tag `demo-pause`, semantics `paused` = `true|false`. |
| **«Эхлэх»** on P2 (AC 10) | NAV-005 S3 button, unchanged look | **Enabled** for the loaded entry although the start is a chosen point; the NAV-018 O1 hint is never shown in the demo build. Disabled only in the existing states without a route (AC 11, 31). Disabled again at the first activation, so a double tap starts one replay only (story › Edge cases). |
| **Launcher icon** (AC 1, 2) | Adaptive icon of the normal app | Same foreground; **background layer in `demo.badge`** (light value `#FDE293`) instead of the normal background, with the launcher label «Туршилтын горим» (ADR-0016 §2). The two icons next to each other differ by colour and label. A demo-only resource override in `src/demo/res`. |
| Notification N1 (AC 27) | NAV-012, unchanged | The header shows the application label «Туршилтын горим» (ADR-0016 §2), so the demo is recognisable in the shade and on the lock screen. Content, actions and cadence unchanged. **No pause action** in the notification (keeps NAV-012 unchanged; pause is on the screen and over the lock screen). |

### Hidden or changed in the demo build
| Element | Demo build | Why |
|---|---|---|
| S1 search bar, S1 map controls, S1 long-press coordinate card | **Not shown**: P1 replaces the S1 browse overlay (ADR-0016 `StartScreen`). Pan and pinch on the picker map still work | The picker is the only start; a field that can only answer «Хайлт түр ажиллахгүй байна» is a dead end; the S1 card's «Маршрут гаргах» starts from the device position |
| My-location button and location dot | **Not shown** on any screen | The demo never reads the device position (AC 13); a real dot next to a simulated puck causes exactly the confusion the badge prevents |
| «Миний байршил» option card in the P2 start editor (NAV-018 Q5) | **Not shown** | It needs a device fix (AC 13) |
| P2 mode tabs, avoid switch, swap, start and destination fields (point editor, search, typed coordinates), long-press card on the P2 map with «Эхлэх цэг болгох» / «Очих газар болгох» | **Unchanged and enabled**; every request they would send is blocked (ADR-0016 §7) and the existing state shows: Components › Demo-mode states | Alternatives 4 |
| NAV-018 O1 hint | Never shown | AC 10 |
| NAV-012 restore notice, N2 interrupted notification | Never shown | AC 30 (Open question 8 (a)) |
| S5 offline status «Интернэт холболт алга» (RS) | **Not shown when the tiles are bundled** (`nav.demoTilesFile`); shown as NAV-005 with `nav.demoTilesUrl` | Design note 4 |
| S5 GPS-lost and GPS-restored messages | Never occur (the position is simulated, AC 13, 22) | — |

### Content rules (additions)
- **Picker distance and duration** are the recorded response's summary values in the NAV-004 formats (story AC 6). The recorded duration of R3 (≈ 71 min) is longer than its 1× replay (787 s) because the synthetic track drives faster than Valhalla's estimate; the preview and the progress panel show the recorded values, as the real app would (Known limitations 3).
- **Place names** are manifest data (story › Demo routes): same Cyrillic text in both UI languages, `LocaleList("mn")`; only a `null`-OSM end becomes T6 in the UI language.
- **Coordinates are never shown** on P1–P4 (AC 40); the typed-coordinate option in the point editor shows only what the user typed, as NAV-011.

## States

### P1 route picker
| State | What the user sees | AC |
|---|---|---|
| **Default** | Within 2 s of launch: sheet «Туршилтын горим», «Маршрут сонгох», E1–E3 with names, mode, distance, duration. Map backdrop at P1 z12 in the theme's flavor. App opens in Mongolian on first launch (D59). R5 visible | 6 |
| **Tiles copying** (first launch per installed version) | PM pill «Ачаалж байна…» after 300 ms; map shows its background colour until ready (≤ 30 s target); the list works, an entry can be opened | 35 |
| **Tiles failed** (missing, unreadable, bad header) | PM card «Газрын зургийг ачаалж чадсангүй» + «Дахин оролдох» within 5 s; list still shows E1–E3 and opens previews (route over the map background) | 36 |
| **Entry error** (route or track asset unreadable) | Within 1 s of the tap: «Алдаа гарлаа» under that entry; no preview; other entries work | 8 |
| **Manifest unreadable** (should be impossible: the build validates it, ADR-0016 §8) | Sheet with PH and one state row «Алдаа гарлаа» in place of PL and the list; settings still reachable | 8 (extension) |
| **Empty / no result** | Does not occur: the list is the packaged manifest (exactly R1–R3) | 6 |
| **Offline** | Nothing to show with bundled tiles (0 requests). With `nav.demoTilesUrl`: the NAV-002 offline message in PM; loaded tiles stay | 33, 34 |
| **GPS lost / permission denied** | Not applicable: P1 never asks for or uses a location (the permission flow is at «Эхлэх», F3) | 13 |
| **Night / English / font 200 %** | Night tokens; `en` labels ("Demo mode", "Choose a route", "Car", "Walk", "Selected point"); names stay Cyrillic; at 200 % the whole sheet scrolls when needed (rule P3) | 37, 39 |
| **After «Дуусгах», arrival «Хаах», end of track, process death** | Default state; no entry marked; nothing restored | 23–25, 30 |

### P2 preview (NAV-005/011/018 S3 states apply; demo-mode states added)
| State | What the user sees | «Эхлэх» | AC |
|---|---|---|---|
| **Recorded route** (default) | Within 1 s of the tap: badge pill; NAV-018 points block with the manifest names, the recorded mode tab selected, summary «5 мин · 4,3 км», «Хүрэх цаг 14:08»; route line and markers; turn list «Маршрутын заавар» in the expanded sheet; NAV-012 battery row if the app is restricted (H1). No O1 | **enabled** | 9, 10, 17 |
| **Another mode, avoid switch toggled, swap, a new start or destination** | The summary region shows the existing routing state: «Маршрутын үйлчилгээ түр ажиллахгүй байна» + «Дахин оролдох» (validated network) or «Интернэт холболт алга» (none); «Дахин оролдох» shows the same state again; 0 requests. The previous route line is removed as in the real app | disabled | 11, 31 |
| **Point editor: search** | NAV-011 result-list state «Хайлт түр ажиллахгүй байна» + «Дахин оролдох», or «Интернэт холболт алга»; 0 requests. No «Миний байршил» option card | — | 31 |
| **Point editor: typed coordinates** | The «Сонгосон цэг» option appears as in NAV-011 AC 7 (no request); choosing it returns to P2 with the routing state above | disabled | 31 |
| **Long-press on the P2 map** | NAV-018 coordinate card; its nearest-place area shows the reverse state («Хайлт түр ажиллахгүй байна» / «Интернэт холболт алга»); «Эхлэх цэг болгох» / «Очих газар болгох» lead to the routing state | — | 11, 31 |
| **Getting the recorded route back** | ✕ or system Back → P1 within 1 s → the same entry → the recorded route again (AC 11) | enabled | 11 |
| **Location rationale / denied** (only if Open question 6 is (b) and permission is missing at «Эхлэх») | NAV-005 S4 rationale, then the OS dialog; if denied, the NAV-005 S4 denied message in the summary region with «Тохиргоо нээх» | disabled while denied | 26; ADR-0016 §5 |
| GPS lost | Not applicable (no device fix is ever needed for the preview) | — | 13 |

### P3 guidance (NAV-005 S5 and NAV-012 L1 states apply with the simulated position; RD added)
| State | Banner | RD | Map / voice | AC |
|---|---|---|---|---|
| **Replaying** (default) | NAV-005 manoeuvre variant, Then strip when chaining applies | badge · «Түр зогсоох» (tonal) | Puck follows the simulated fixes; voice or chime per navigation-ux §4 | 12–15, 17 |
| **First seconds, no usable Mongolian voice** | unchanged | unchanged | A1 notice above RP (8 s, NAV-005); chime per prompt | 19 |
| **Paused** | unchanged (current manoeuvre, distance frozen, **not dimmed**) | badge · **«Үргэлжлүүлэх»** (filled primary) | Puck still; 0 prompts; utterance or chime stopped; remaining distance and «Хүрэх цаг» frozen; no «GPS дохио тасарлаа»; map gestures and recenter still work; the notification keeps its last content | 22 |
| **Not following** | unchanged | unchanged | NAV-005 recenter «Байршил руу буцах»; back after 15 s | 15 |
| **Deviation detected** (not expected on R1–R3) | Recalculating variant + «Маршрутын үйлчилгээ түр ажиллахгүй байна» (or «Интернэт холболт алга») | unchanged | Replay continues along the track; normal banner ≤ 2 s after back within 50 m; 0 requests | 32 |
| **Muted / language / theme / rotation** | NAV-005 rules | labels switch ≤ 1 s | NAV-005 AC 37, 59, 60, 64 | 20, 21 |
| **Screen off, Home, swipe-away** | — | — | Replay continues in the foreground service (unlike NAV-017); NAV-012 L1 on screen-on shows RD too | 16, 28 |
| **Phone call** | unchanged | unchanged | NAV-012 §12.6: 0 prompts during the call, one catch-up after; **the replay keeps running** unless the PO pauses | 29 |
| **Offline** | unchanged | unchanged | No offline status with bundled tiles (Design note 4); with `nav.demoTilesUrl` NAV-005 AC 54 | 33, 34 |
| **GPS lost** | Does not occur | — | — | 13, 22 |
| **No result** | Does not occur: «Эхлэх» needs a loaded route; a track that ends without arrival behaves as «Дуусгах» (P1 within 2 s, no message) | — | — | 25 |

### P4 arrival
NAV-005 S6 unchanged (arrival variant, arrival panel with the destination name and «Хаах», service stops within 10 s); RD keeps the **badge only** (no pause); «Хаах» → P1 with no entry marked. Over the lock screen: NAV-012 AC 10. Offline, GPS lost, no result: nothing to show.

## Interactions
- **P1 entry tap:** loads and parses the entry (AC 9 ≤ 1 s) → P2. A second tap while loading is ignored. Error → inline row (AC 8).
- **P1 gear:** S7 settings «Тохиргоо» (language «Хэл», theme options, «Батарейн хязгаарлалт») as in the normal app (AC 7: 1 tap). Back closes S7.
- **P1 map:** pan and pinch only (no long-press card, no controls).
- **P1 system Back:** leaves the app (Android default for a home screen); the next launch shows P1.
- **P2:** NAV-018 interactions; ✕ / Back → P1 (≤ 1 s); «Эхлэх» → F3.
- **P3 «Түр зогсоох» / «Үргэлжлүүлэх»:** toggles within 1 s (navigation-ux §13). Also over the lock screen (non-destructive, like the voice button).
- **P3 «Дуусгах»** (screen, lock screen or notification): ends at once, no confirmation (NAV-005 Design notes 1), also while paused; P1 within 2 s with no entry marked.
- **P3 system Back:** NAV-005 rule unchanged (app to background, the replay continues).
- **P4 «Хаах»:** P1.
- **No typing while the replay runs:** the NAV-011 typing lock never engages in the demo (it has no device fixes, AC 17); no text field is reachable from P3 anyway.

## Copy (mn / en)
Every `mn` value is a glossary term. **No new term beyond W2** (already added by the BA, `needs native review`). Keys: demo-only keys live in `src/demo/res/values{,-en}/strings.xml` (ADR-0016 §12); all others already exist in `src/main/res`.

| Key | mn | en | Glossary | New? |
|---|---|---|---|---|
| `demo_mode` (launcher label, picker heading, badge, notification header) | Туршилтын горим | Demo mode | W1 | new key (`src/demo`) |
| `demo_pause` (pause button) | Түр зогсоох | Pause | **W2**, `needs native review` | new key (`src/demo`), only if Open question 4 keeps the pause |
| `action_continue` (resume) | Үргэлжлүүлэх | Continue | A7 | existing |
| `route_options` (picker list label) | Маршрут сонгох | Choose a route | N20 | existing |
| `route_mode_car` / `route_mode_walk` | Машин / Явган | Car / Walk | Travel mode | existing |
| `route_origin` / `route_destination` (TalkBack prefixes in an entry) | Эхлэх цэг / Очих газар | Start / Destination | Origin, Destination | existing |
| `place_selected_point` (`null`-OSM end) | Сонгосон цэг | Selected point | T6 | existing |
| `settings_title` | Тохиргоо | Settings | Settings | existing |
| `status_generic_error`, `status_loading`, `status_tiles_unavailable`, `action_retry`, `status_offline` | Алдаа гарлаа, Ачаалж байна…, Газрын зургийг ачаалж чадсангүй, Дахин оролдох, Интернэт холболт алга | Something went wrong, Loading…, The map could not be loaded, Try again, No internet connection | Generic error, G1, G2, Try again, No connection | existing |
| `search_unavailable`, `route_unavailable` | Хайлт түр ажиллахгүй байна, Маршрутын үйлчилгээ түр ажиллахгүй байна | as NAV-011 | T4, N8 | existing |
| Banner, voice, progress, arrival, notification, A1, recenter, mute, «Дуусгах», «Эхлэх», «Хаах», «Маршрутын заавар» | as NAV-005 / NAV-012 / NAV-018 | as NAV-005 / NAV-012 / NAV-018 | NAV-005 rows | existing |

**Length notes (Mongolian first).** «Туршилтын горим» (15) is about 1.7 times as wide as "Demo mode" (9); «Үргэлжлүүлэх» (12) vs "Continue" (8) is the widest RD state: in portrait at font scale 1.3 on 360 dp the badge wraps to two lines inside the pill (measured), at 1.0 everything is one line. Entry names wrap (data); the longest meta line «Машин · 9,2 км · 1 ц 11 мин» (27) fits one line at 360 dp / 100 %. The W2 panel check against the Avoid term «Зогсоох» (glossary note) applies; «Түр зогсоох» never labels the end action.

## Accessibility
- **TalkBack order.** P1: heading «Туршилтын горим» → «Тохиргоо» → list «Маршрут сонгох» (collection of 3) → E1–E3 → R5. Each entry is one node: «Эхлэх цэг, Сүхбаатарын талбай, Очих газар, Зайсан Голден Вилл, Машин, 4,3 км, 5 мин» (AC 39); the inline error row is a polite live region. P2: badge → NAV-018 order (TalkBack opens the sheet expanded, NAV-011 P4). P3: banner → badge → pause/resume → NAV-005 order (status, recenter, voice notice, progress, voice button, orientation, «Тохиргоо», «Дуусгах») → R5.
- **Names:** every control's name is its glossary label (pause: «Түр зогсоох» / «Үргэлжлүүлэх»; gear: «Тохиргоо»). Icons are decorative. The badge is text, not a control.
- **Touch targets:** every control ≥ 48 × 48 dp with ≥ 8 dp between neighbours (measured); pause and «Дуусгах» are at opposite ends of the screen (top row vs bottom panel).
- **Contrast** (`check-contrast.mjs`, existing pairs): badge text 10.3:1 day / 9.4:1 night, badge outline on the map earth ≥ 3:1; pause tonal `on-primary-container` on `primary-container` and resume `on-primary` on `primary` ≥ 4.5:1 in both themes; picker texts on `ui.surface` as NAV-005.
- **Dynamic type:** P1 and the P2 badge scale without a cap; RD follows the NAV-005 overlay cap 1.3. Layout measured at 1.0 / 1.3 / 2.0 (Evidence).
- **Colour is never the only signal:** paused = different label and icon (not only the filled colour); the badge has text; the launcher icon differs by colour **and** label.
- **Reduced motion:** NAV-005 rules (camera jumps). Nothing new animates except the button state change (no animation needed).
- **Time limits:** only the NAV-005 A1 notice (8 s).

## Design rationale
| UX law / heuristic | How this design applies it | Deliberate trade-off |
|---|---|---|
| **Fitts's law** | Entries are full-width rows (85 dp high at 100 %); «Эхлэх» keeps its 56 dp full-width bottom place; pause is a 48 dp labelled button at the top-right, as far as possible from «Дуусгах» at the bottom-right, so a slip cannot end the replay | Pause is not in thumb reach at the bottom; it is used rarely and by a stationary tester |
| **Hick's law** | Three entries, no choices before them (no mode, speed or settings in the list); one action per entry (tap = open); one demo control during the replay (pause) | No speed choice (1×, Open question 4); no seek |
| **Miller's law** | An entry is three chunks: from, to, one meta line (mode · distance · duration) | Duration and distance not in separate columns |
| **Jakob's law** | Tap a list item → route preview → «Эхлэх» is the Google Maps / NAV-005 search-result flow; the badge and the picker heading copy NAV-017, which the PO already uses | The picker replaces the search bar, unlike the real home screen |
| **Doherty threshold** | Picker ≤ 2 s, preview ≤ 1 s, guidance ≤ 1 s, pause ≤ 1 s, «Дуусгах» → picker ≤ 2 s; the first-launch tile copy shows «Ачаалж байна…» and never blocks the list | The first launch may show the map background for up to 30 s |
| **Gestalt (proximity, common region, similarity)** | The two demo-only items share one row (RD), separate from the real controls; the badge is the same component on P2, P3 and P4 and the same words as the P1 heading; the entry error sits under the entry it belongs to | — |
| **Von Restorff** | The banner stays the single focal point in P3; the badge is a small amber pill; only while paused does «Үргэлжлүүлэх» become the strongest control (filled primary), because then it is the one thing to do | The badge is quieter than a full-width strip would be |
| **Serial position** | Manifest order R1 (car, short), R2 (walk), R3 (roundabouts, long): the quickest test first, the long screen-off test last | — |
| **Tesler's law** | The system carries the complexity: tiles copied once, simulated location, network blocked with existing states, no restore, permission flow identical to the real app; the PO configures nothing | — |
| **Postel's law** | Typed coordinates are still accepted in the point editor (they need no request) | Text search cannot work without a backend |
| **Peak–end rule** | Arrival is the real NAV-005 arrival, then one tap back to the list for the next test; a broken asset says «Алдаа гарлаа» right under the entry and leaves the others working | — |
| **Goal-gradient** | Remaining time and distance in the progress panel count down as in the real app | Recorded durations can be longer than the replay (R3) |
| **Zeigarnik effect** | Pause keeps the replay visibly unfinished («Үргэлжлүүлэх» filled); a killed replay is deliberately not resumed | Open question 8 (a): no restore, so the PO sees that the OEM killed the app |
| **Aesthetic–usability / prägnanz** | No new colours or tokens; one new component (pause button) from the M3 set; the rest is reused | — |
| **Cognitive load (count it)** | Taps from launch to guidance: **2** (entry, «Эхлэх»), plus one-time permission dialogs; words added on the guidance screen: **4** («Туршилтын горим», «Түр зогсоох») | — |

Nielsen heuristics checked: **visibility of system status** (badge always, paused label, tile loading and failure, launcher and notification label); **match with the real world** (glossary terms only; place names as in OSM); **user control and freedom** («Дуусгах» everywhere, pause, Back from the preview to the list); **consistency and standards** (NAV-005/011/012/018 components unchanged, NAV-017 words); **error prevention** (pause far from «Дуусгах», «Эхлэх» disabled after the first tap, no location dot to confuse); **recognition over recall** (entries show from, to, mode, distance, duration); **flexibility** (settings one tap from the list); **minimalist design** (only three demo-only elements); **recover from errors** (entry error with the other entries working; tiles «Дахин оролдох»; unavailable states are the real ones); **help** (README checklist for the PO, AC 43). Design changes the checker forced: the picker heading went from `title-large` 22 sp to `title` 16 sp so the pinned part plus one entry fit at 200 %; the picker cap now subtracts the top message (the tiles card overlapped the sheet at 200 %); the whole sheet scrolls when its pinned part does not fit; RD may wrap in landscape (the badge text was clipped next to «Үргэлжлүүлэх» at 1.3).

## Design notes (decisions inside the AC)
1. **Tap opens the preview** (no selection step in the picker): Alternatives 1. «Эхлэх» exists only in P2.
2. **The picker is the home and has no close button;** system Back leaves the app as on any Android home screen.
3. **Preview controls stay enabled and show the real states** (AC 11 lets UX pick per control): Alternatives 4. Getting the recorded route back is Back → the same entry. *Nice-to-have, not required:* if the demo route path answered a request equal to the armed entry's (same points, mode and avoid flag) from the packaged response, tapping the recorded mode tab again would restore the route in one tap; this is the architect's call (handoff request), the AC are met either way.
4. **No offline status during a bundled-tiles replay.** NAV-005 AC 54 shows «Интернэт холболт алга» on the map because reroute and tiles need the network; in the bundled-tiles demo build nothing needs it, so the message would stay for the whole airplane-mode run (AC 34) and only cost map band. NAV-019 AC 15 does not list NAV-005 AC 54. With `nav.demoTilesUrl` the tiles need the network and the status shows as in NAV-005. The routing states in P2 and the reroute banner still say «Интернэт холболт алга» when there is no network (AC 31, 32), because there the user asked for something that needs it. The BA is asked to record this in the story (handoff).
5. **No pause action in the notification:** NAV-012 N1 stays identical, so the PO's notification test is the production one.
6. **Paused does not dim the banner** (unlike NAV-005 GPS lost): dimming means "stale data" in NAV-005; here the data are current and frozen on purpose. The button label carries the state.
7. **The pause control is optional by construction:** if the PO answers Open question 4 with (c) "no pause", RD holds the badge alone and nothing else moves.
8. **Location permission (Open question 6):** with (b) the NAV-005 rationale and OS dialog appear at the first «Эхлэх» exactly as in the real app (F3); with (a) no location prompt exists in the demo build. The screens are the same either way.
9. **Launcher icon in the badge colour** so the two installs differ at a glance on the home screen (AC 2), not only by label.

## Known limitations (design, within the AC)
1. **Short 640 dp phones above font scale 1.0 during guidance.** On 360×640 dp at font scale 1.3 and 2.0, the A1 window (8 s, once per replay) leaves 0–33 dp of map band below RD instead of 64 dp, and the control pair can overlap RD; in the not-following worst case (Then strip + longest instruction + recenter) the band is 62–77 dp instead of 80 dp. 320×568 with A1 at 1.0 has the same overlap. Measured: no persistent-badge layout meets the NAV-005 A1-window minimum there (Alternatives 2). **360×780 passes every rule at every font scale (band ≥ 134 dp).** If the PO's phone (AC 43 (a)) is a 640 dp-tall model used with large text, the fallback is layout (c) (`v=c`) or the phone's default font size for the test.
2. **Picker at font scale 2.0 and in landscape:** 0–1 of 3 entries fully visible without scrolling (the whole sheet scrolls; ≥ 48 dp of the first entry always visible). 3 of 3 at 1.0 on every portrait viewport ≥ 360×640.
3. **Recorded durations vs replay time:** R3's recorded duration (≈ 1 h 11 min) is much longer than its 1× replay (≈ 13 min), so «Хүрэх цаг» in P2 and P3 does not match the drive; R1 and R2 roughly match. A data property of the synthetic tracks (story R5), not a UI defect.
4. **Wide-font stress run** (DejaVu Sans, much wider than Roboto): 24 problems, in landscape 640×360 (A1 notice and deviation states) and on 360×780 at font scale 1.3–2.0 (A1 notice, paused: the wider «Үргэлжлүүлэх» squeezes the badge); not a design target.
5. **Not provable in a wireframe:** real notification skin with the demo label, lock-screen behaviour, TalkBack reading order, sunlight readability of the amber badge (AC 43 checklist).

## Open questions (none blocks the build)
| # | Question | Options | UX recommendation |
|---|---|---|---|
| U1 | Offline status during a bundled-tiles replay (Design note 4) | (a) not shown when tiles are bundled; (b) shown as NAV-005 AC 54 | **(a)**: nothing in the build needs the network; (b) shows a permanent message in the airplane-mode run |
| U2 | Font scale for the PO's test on a 640 dp-tall phone (Known limitations 1) | (a) accept the limitation, PO tests at the default font size; (b) build layout (c) instead | **(a)**: most current phones are ≥ 780 dp tall, where every rule holds |

## AC traceability
| AC | Where |
|---|---|
| 1, 2 | Components › Launcher icon, Notification N1; flow F0 |
| 3–5 | No UI (build); flow F0 |
| 6 | P1 states › Default; Components › P1 sheet, PH, PL, entry row |
| 7 | Components › PH (gear, 1 tap); Interactions |
| 8 | P1 states › Entry error; Components › entry error row |
| 9 | P2 states › Recorded route; flow F2 |
| 10 | Components › «Эхлэх» on P2; Hidden or changed (O1) |
| 11 | Alternatives 4; P2 states; Design note 3 |
| 12–16 | P3 states; flow F3, F4, F5; navigation-ux §13 |
| 17 | Layout rules P4–P6; Components › Badge; Hidden or changed (typing lock) |
| 18–21 | P3 states (voice, A1, language); NAV-005 unchanged; flow F4 |
| 22 | Components › Pause / resume; P3 states › Paused; navigation-ux §13.2 |
| 23–25 | P4; Interactions; flow F6 |
| 26–29 | P2 states › Location rationale; Components › Notification N1; P3 states (screen off, call); flow F3, F5 |
| 30 | P1 states › After process death; Hidden or changed (restore) |
| 31–34 | P2 states; P3 states › Deviation, Offline; Design note 4; flow F7 |
| 35, 36 | P1 states › Tiles copying, Tiles failed; Components › PM |
| 37 | Layout rule P1; Evidence |
| 38 | Copy |
| 39 | Accessibility; Evidence |
| 40 | Content rules (no coordinates) |
| 41, 42, 44 | No UI |
| 43 | Evidence › needs the real phone (items for the checklist) |

## Evidence
- **Prototype:** [`prototypes/NAV-019-android-demo.html`](../prototypes/NAV-019-android-demo.html), states `picker`, `picker-loading`, `picker-tiles`, `picker-error`, `preview`, `preview-unavailable`, `guidance`, `worst` (Then strip + longest instruction + long street + recenter), `paused`, `notice` (A1), `reroute` (deviation with N8), `arrival`; `v=b` / `v=c` show the measured alternatives.
- **Layout checker:** `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav019.mjs`: 360×640, 360×780, 412×915, 320×568 and 640×360 dp × 12 states × day/night × mn/en × font scale 1.0/1.3/2.0 = **720 combinations, 0 problems** in the design target (2026-10-04), 222 INFO lines outside it (Known limitations 1, 2). Checks: attribution fully visible and intersecting nothing; no overlapping boxes or controls; every control ≥ 48 dp with an unclipped label; exactly one W1 label, fully visible; RD one row in portrait; banner instruction ≤ 3 lines; portrait map band below RD ≥ 150 dp at 1.0 and ≥ 80 dp above (≥ 80/64 dp with A1); puck inside the band; picker entries per rule P3; «Эхлэх» visible on P2; no horizontal overflow.
  - Minimum portrait map band below RD: 360×640 **154 dp** (1.0); 360×780 294 / 149 / 134 dp (1.0 / 1.3 / 2.0); 412×915 441 / 334 / 319 dp. Banner instruction ≤ 2 lines on 360 and 412 dp, ≤ 3 on 320 dp and in the landscape column.
  - Picker entries fully visible: 3 of 3 at 1.0 on 360×640, 360×780 and 412×915; 2 of 3 at 1.3 on 360×640; 1 of 3 in landscape.
  - `--strict` (the NAV-005 target): variant (a) 48 problems, (b) 48, (c) 28; per state in Alternatives 2 and Known limitations 1.
  - `--wide` (DejaVu Sans stress): 24 problems, Known limitations 4.
- **Contrast:** `node docs/design/prototypes/check-contrast.mjs`: all `contrastPairs`, flavor key parity and the map-style tables pass; no token was added or changed.
- **Timing budgets** (AC 6, 8, 9, 12, 22, 23, 35, 36): delegated to the mobile tests named in ADR-0016 §13 and to the PO's phone run.
- **Needs the real phone** (suggested items for the AC 43 checklist, QA/orchestrator own it): the launcher icon and label next to the debug build; «Туршилтын горим» in the notification header and over the lock screen; pause over the lock screen; the first-launch tile copy time; TalkBack reading of an entry; the phone's screen size in dp and font size (Known limitations 1).

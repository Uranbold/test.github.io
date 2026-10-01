# Screen: Android app — map, destination, route preview, guidance and arrival (NAV-005 first slice)

- **Stories:** NAV-005 (AC 1–73; UI-relevant AC 1–39, 41–64). Traceability per AC at the end.
- **Platforms:** **Android only** (Kotlin, Jetpack Compose, Material 3, MapLibre Native, Ferrostar Android). iOS is NAV-015 (D24); it reuses this spec with HIG controls.
- **Flow:** [`flows/NAV-005-android-navigation.md`](../flows/NAV-005-android-navigation.md) (F1–F11).
- **Rules:** [`navigation-ux.md`](../navigation-ux.md) (banner, voice text and schedule, off-route, GPS loss, camera, arrival). This spec owns the layout, components, states, interactions and copy; navigation-ux owns the timing and the voice.
- **Prototype:** [`prototypes/NAV-005-guidance.html`](../prototypes/NAV-005-guidance.html), a static wireframe in dp (1 CSS px = 1 dp) of S3, S4, S5 and S6 states, day/night, mn/en, font scale 100/130/200 %, portrait and landscape. Hash example: `#state=reroute-failed&theme=night&lang=mn&scale=2` (the viewport is the browser window size). Checked by `prototypes/check-layout-nav005.mjs` (Verification). No Figma file.
- **Tokens:** [`tokens.json`](../tokens.json) **v0.4.0**. New for NAV-005: `color.*.nav.*` (banner variants, puck), `typography.scale.nav-distance`, `nav-instruction`, `nav-street`, `size.nav-*`, `motion.nav-*`, and 31 new `contrastPairs`. Map layers: [`map-style.md` §7.4](../map-style.md).
- **API and architecture:** `openapi.yaml` 0.5.0 `search`, `postRoute`, basemap PMTiles (0.5.1 adds documentation only). No contract change is needed for the design. [ADR-0009](../../architecture/adr/0009-android-guidance-client.md) (accepted 2026-10-01, written in parallel with this spec) answers A1–A7: app-owned route client and reroute policy, text-token rewrite with app-rendered banner, notification and voice (the voice schedule implements navigation-ux §4 on the device), bundled style assets, `values/` = Mongolian with `values-en/`, platform location without Google Play services.
- **Instruction text:** ADR-0008 (client templates from `maneuver.type`/`modifier`/`exit`/`bearing_after`, D56), ported to Kotlin with the shared fixture (AC 29). Valhalla's narrative is never shown or spoken.

## Purpose
Let a driver (or pedestrian) in Ulaanbaatar pick a destination, check the route, press «Эхлэх» and be guided turn by turn with a banner readable in under a second, Mongolian voice prompts (or a chime when the phone has no Mongolian voice), automatic reroutes and clear messages for every problem, without typing or tapping while moving.

## Screens in this spec
| ID | Screen / surface | Material 3 pattern | Main AC |
|---|---|---|---|
| S1 | Map (home) | Full-bleed map, docked search bar, FABs | 1, 2, 58 |
| S2 | Search results, coordinate card | Search view (expanded search bar), bottom sheet card | 3, 4 |
| S3 | Route preview | Standard (non-modal) bottom sheet | 5–7, 10, 15 |
| S4 | Location rationale and location messages | Basic dialog (rationale), message card (inline) | 8–12 |
| S5 | Guidance | Custom navigation layout over the map | 15–64 |
| S6 | Arrival | S5 with the arrival panel | 55–57 |
| S7 | Settings sheet «Тохиргоо» | Modal bottom sheet | 58, 60 |
| S8 | Ongoing notification | `NotificationCompat`, channel «Замчлал» | 13, 15, 17, 19 |

## Layout

All screens are **edge-to-edge**: the map draws under the status and navigation bars; UI regions add the system bar insets (`WindowInsets.safeDrawing`). Spacing uses `tokens.json › spacing` (dp). Side gutter **16 dp**, except the guidance banner (8 dp, `size.nav-banner-margin`, so the instruction gets the width).

### S1 Map and S3 Route preview (portrait, 360×640 dp, mn, day)
```
S1 map                                   S3 route preview (sheet)                S2 coordinate card
┌──────────────────────────────────┐     ┌──────────────────────────────────┐   ┌──────────────────────────────────┐
│┌────────────────────────────┐[⚙] │ R1  │                                  │   │┌────────────────────────────┐[⚙] │
││🔍 Газар, хаяг хайх          │    │     │        ◉━━━━━━━┓                 │   ││🔍 Газар, хаяг хайх          │    │
│└────────────────────────────┘    │     │   (map, route) ┗━━━━━━📍         │   │└────────────────────────────┘    │
│ (message card, if any)           │ R2  │                                  │   │                                  │
│                                  │     │┌────────────────────────────────┐│   │            (map) 📍              │
│            (map)            [+]  │ R3  ││ Маршрут харах              [✕] ││   │                                  │
│                             [−]  │     ││ ◎ Миний байршил                ││   │┌────────────────────────────────┐│
│                             [N]  │     ││ 📍 Зайсан толгой               ││   ││ Сонгосон цэг               [✕] ││
│                             [◎]  │     ││ [🚗 Машин ][🚶 Явган ]          ││   ││ 47.88580, 106.91730            ││
│                                  │     ││ Шороон замаас зайлсхийх   (◯ ) ││   ││ [➦ Маршрут гаргах]             ││
│                                  │     ││ 13 мин · 4,6 км                ││   │└────────────────────────────────┘│
│                                  │     ││ Хүрэх цаг 14:03                ││   │                                  │
│                                  │     ││ [▶          Эхлэх            ] ││   │                                  │
│                                  │     │└────────────────────────────────┘│   │                                  │
├──────────────────────────────────┤     ├──────────────────────────────────┤   ├──────────────────────────────────┤
│ © OpenStreetMap contributors     │ R5  │ © OpenStreetMap contributors     │   │ © OpenStreetMap contributors     │
└──────────────────────────────────┘     └──────────────────────────────────┘   └──────────────────────────────────┘
```

### S5 Guidance (portrait 360×640 dp) and S6 Arrival
```
S5 guidance, manoeuvre + Then strip      S5 off-route, reroute failed            S5 first seconds, no Mongolian voice    S6 arrival
┌──────────────────────────────────┐     ┌──────────────────────────────────┐   ┌──────────────────────────────────┐   ┌──────────────────────────────────┐
│┌────────────────────────────────┐│ RB  │┌────────────────────────────────┐│   │┌────────────────────────────────┐│   │┌────────────────────────────────┐│
││┌────┐ 300 м                    ││     ││┌────┐ Маршрутыг дахин          ││   ││┌────┐ 1,2 км                   ││   ││┌────┐ Таны очих газар баруун   ││
│││ ↱  │ Баруун талын гарах       ││     │││ ⟳  │ тооцоолж байна           ││   │││ ↱  │ Баруун талын гарах       ││   │││ ⚑  │ талд байна               ││
││└────┘ замаар гарна уу          ││     ││└────┘ Маршрутын үйлчилгээ түр  ││   ││└────┘ замаар гарна уу          ││   ││└────┘ Зайсангийн гудамж        ││
││       Чингисийн өргөн чөлөө, Н…││     ││       ажиллахгүй байна         ││   ││       Энхтайвны өргөн чөлөө    ││   │└────────────────────────────────┘│
│├────────────────────────────────┤│ RT  │└────────────────────────────────┘│   │└────────────────────────────────┘│   │                                  │
││ Дараа нь  ↰                    ││     │                                  │   │                                  │   │            (map) ⚑▲              │
│└────────────────────────────────┘│     │   (map, old route dimmed)        │   │             (map)                │   │                                  │
│ ┌──────────────────────────────┐ │ RS  │          ▲ (raw position)        │   │               ▲                  │   │                                  │
│ │ ⓧ GPS дохио тасарлаа          │ │     │                                  │   │                                  │   │┌────────────────────────────────┐│
│ └──────────────────────────────┘ │     │[◎ Байршил руу     ]  [🔊] [⬆]   │   │                      [🔊] [⬆]   │   ││ ⚑ Зайсан толгой                ││
│              ▲  puck at 70 %     │ map │[  буцах           ]             │   │┌────────────────────────────────┐│   ││                       [ Хаах ] ││
│                      [🔊] [⬆]   │ RC  │┌────────────────────────────────┐│   ││ ⓘ Энэ утсанд монгол дуут заавар││   │└────────────────────────────────┘│
│┌────────────────────────────────┐│ RP  ││ Хүрэх цаг 14:41        [⚙] (✕)││   ││ ажиллахгүй байна. Заавар зөвхөн││   │                                  │
││ Хүрэх цаг 14:35        [⚙] (✕)││     ││ 31 мин · 12,9 км               ││   ││ дэлгэцэнд харагдана.           ││   │                                  │
││ 25 мин · 12,4 км               ││     │└────────────────────────────────┘│   │└────────────────────────────────┘│   │                                  │
│└────────────────────────────────┘│     │                                  │   │┌──── progress panel (as left) ──┐│   │                                  │
├──────────────────────────────────┤ R5  ├──────────────────────────────────┤   ├──────────────────────────────────┤   ├──────────────────────────────────┤
│ © OpenStreetMap contributors     │     │ © OpenStreetMap contributors     │   │ © OpenStreetMap contributors     │   │ © OpenStreetMap contributors     │
└──────────────────────────────────┘     └──────────────────────────────────┘   └──────────────────────────────────┘   └──────────────────────────────────┘
```
(Icons in the drawings stand for 24 dp Material Symbols, 56 dp in the banner; (✕) is the round «Дуусгах» button, [⚙] «Тохиргоо», [🔊] «Дууг хаах», [⬆] the orientation toggle; the prototype draws them.)

### S5 Guidance, landscape (640×360 dp)
```
┌───────────────────────────┬────────────────────────────────────────────┐
│┌─────────────────────────┐│                                            │
││┌────┐ 300 м             ││              (map, route)                  │
│││ ↱  │ Баруун тийш       ││                    ▲                       │
││└────┘ эргэнэ үү         ││                                            │
│└─────────────────────────┘│ [◎ Байршил руу буцах]          [🔊] [⬆]    │
│┌─────────────────────────┐│┌──────────────────────────────────────────┐│
││ ⓧ GPS дохио тасарлаа     │││ Хүрэх цаг 14:35                 [⚙] (✕) ││
│└─────────────────────────┘││ 25 мин · 12,4 км                         ││
│ (column scrolls if needed)│└──────────────────────────────────────────┘│
├───────────────────────────┴────────────────────────────────────────────┤
│ © OpenStreetMap contributors                                           │
└────────────────────────────────────────────────────────────────────────┘
```
Left column width = `clamp(40 %, 320 dp, 400 dp)`: banner, Then strip and status messages. The map spans the full width behind it (the column is cards, not a solid panel); the camera padding treats the column as covered. The progress panel, the control pair and the recenter button sit at the bottom of the **map** column.

### Regions (S5, both orientations)
| Region | Content | Portrait | Landscape |
|---|---|---|---|
| **RB** banner | Banner card (manoeuvre / recalculating / arrival variant) | Top, below the status bar inset, full width − 2 × 8 dp. **At most 50 % of the app height**; scrolls inside beyond that (only at large font scales on small phones, Layout rule 4) | Top of the left column |
| **RT** Then strip | «Дараа нь» + icon, only when the chaining condition holds (navigation-ux §2.4); later also the NAV-013 lane strip | Attached to the bottom of RB (same card) | Same |
| **RS** status area | 0–2 short status messages (GPS lost / restored, offline), stacked | Floats on the map, 8 dp below RB/RT, 16 dp gutters (full width) | Below RB in the left column; the column scrolls if needed |
| **RC** control pair | Voice and orientation icon buttons, 48 dp each, 8 dp apart, **side by side** | Floats on the map at the **bottom-right**, 16 dp above RP (or the voice notice) | Bottom-right of the map column, above RP |
| map | Map, route, puck | Everything not covered | Right of the left column |
| **Recenter** | Extended FAB «Байршил руу буцах», only while not following | Bottom-left of the map, 12 dp above RP, max width = band width − 16 − 8 − 104 (RC) − 16 dp; the label wraps, never truncated | Bottom-left of the map column |
| **RN** voice notice | Glossary A1 notice, once per session for 8 s (navigation-ux §4.6) | Directly **above** RP (RP stays usable) | Above RP in the map column |
| **RP** progress / arrival panel | Trip progress panel (with «Тохиргоо» and «Дуусгах»), or the arrival panel (S6) | Bottom, above R5, full width − 2 × 8 dp | Bottom of the map column |
| **R5** attribution | «© OpenStreetMap contributors» strip | Bottom edge, above the navigation bar inset | Bottom edge, full width |

### Layout rules
1. **Nothing covers the attribution.** R5 is its own region (a strip on `ui.surface-container`, 24 dp min height, `caption` text) at the bottom of **every** map screen (S1, S2, S3, S5, S6), in both themes, both languages, both orientations and at font scale 200 %. Sheets, panels, FABs, messages and dialogs are laid out **above** it; only a dialog scrim (S4 rationale, the OS notification-permission dialog) dims it while that dialog is open. MapLibre's own attribution button and logo are **off** (map-style §1). AC 2's Compose test asserts R5 is displayed and its bounds intersect no other visible element.
2. **The puck is never under UI.** The camera padding = the covered edges (RB + RT + RS at the top, RN + RP and the RC/recenter row at the bottom, the left column in landscape) + 16 dp. Padding is recomputed when RT, RS, RN or RP change height (AC 23). The puck sits at 70 % of the remaining height, horizontally centred, so it is clear of the bottom-row controls (RC right, recenter left).
3. **Floating regions never overlap each other**: RS is at the top of the map band, RC and recenter at the bottom, side by side; the recenter's max width leaves room for RC. With two status messages RS grows downwards.
4. **Minimum map band** (portrait, measured from the lowest of RB/RT/RS to the top of RN or RP): **≥ 150 dp at font scale 100 %** and **≥ 80 dp at 130 % and 200 %** on 360×640 dp and larger (3-button navigation bar, worst case: Then strip + GPS message + recenter); while the voice notice shows, ≥ 80 dp at 100 % and ≥ 64 dp above. Ways the layout keeps it: the guidance overlay text has font-scale caps (navigation-ux §2.5); RB is capped at 50 % of the app height and scrolls inside beyond that; if two status messages would leave less than the minimum, only the higher-priority message shows (GPS before offline). 320 dp-wide phones above 100 % font scale are outside the design target (Known limitations).
5. **One Compose tree per screen; configuration changes keep state.** Rotation recomposes the regions into the landscape arrangement; no state is held in views (AC 64).
6. **S3 sheet** is non-modal: the map above it stays interactive (pan and zoom); its height is the content height up to **80 %** of the screen, the content scrolls, the «Эхлэх» button is pinned at the bottom of the sheet (always visible). At font scale 100 % on 360×640 every state (route, location message, error) fits without scrolling (measured).
7. **Message placement on S1/S3.** S1 messages (location, tiles, offline) sit in R2 under the search bar. In S3, preview states and location messages show **inside the sheet's result region**, never as a second floating card.

## Components

### Ferrostar: reuse and customise
[ADR-0009](../../architecture/adr/0009-android-guidance-client.md) §1 decides that only Ferrostar's **`core`** artifact is used in this slice; its UI modules (`ui-compose`, `ui-maplibre`) are not, because their views render Valhalla text and English library strings and cannot meet AC 2, 21–25 and 31. So every visible component here is ours; Ferrostar supplies the navigation state.

| Ferrostar part | Decision | Why |
|---|---|---|
| Core `NavigationSession` (route snapping, step advance, deviation detection, trip progress numbers), driven by the app's guidance engine | **Reuse** | ADR-0009 §1; thresholds per ADR-0009 §1 and §4 (AC 41) |
| OSRM response parser | **Reuse** after the app's text-token rewrite | ADR-0009 §3.1: Valhalla text never enters the model |
| Route provider, `FerrostarCore` reroute | **Not used**: app-owned route client and reroute policy | ADR-0009 §2, §4 (AC 5, 43–50) |
| Banner (`InstructionsView`) | **Not used**: custom `NavBanner` | Our text, three variants, never blank (#969), Then strip, font cap and stacked layout, Mongolian wrapping, TalkBack live region on the instruction only |
| Trip progress (`TripProgressView`) | **Not used**: custom `TripProgressPanel` | «Хүрэх цаг» label, NAV-004 formats, «Тохиргоо» and «Дуусгах» in the panel |
| Spoken-instruction observer / TTS observer | **Not used**: app voice scheduler and output | navigation-ux §4, ADR-0009 §3.3–3.4 (AC 34, 36, 39) |
| Map view, puck, navigation camera | **Not used**: MapLibre Native directly, our route layers, puck and camera | map-style §7.4, navigation-ux §8 (pitch 45°, zoom by speed, padding from Layout rule 2) |
| Mute, recenter, zoom, road-name and speed-limit views; foreground-service notification builder | **Not used** | Ours (glossary names, chime muting, labelled recenter); road name is in the banner; speed limit is NAV-014; notification from our resources (ADR-0009 §9) |

### Component specs
| Component | Source | Spec |
|---|---|---|
| **Search bar** (S1) | M3 `SearchBar` (docked) | Full width − 16 dp − 56 dp (settings button), 56 dp high, `radius.full`, elevation 2 (day) / outline `ui.outline-variant` (night). Leading search icon, placeholder «Газар, хаяг хайх» (`body-large`, `ui.on-surface-variant`). Tapping expands to S2. Trailing clear button «Хайлтыг арилгах» when text is present. Test tag `search-bar`. |
| **Settings button** (S1) | M3 icon button (filled tonal, 48 dp) | Gear icon, content description «Тохиргоо», right of the search bar. Opens S7. Test tag `settings`. |
| **Map controls** (S1) | M3 small FAB / icon buttons, 48 dp, `ui.surface`, elevation 1, right edge | Top→bottom above R5: zoom in «Томруулах», zoom out «Жижигрүүлэх», reset bearing «Хойд зүг дээшээ» (shown only when bearing ≠ 0), my location «Миний байршил» (`ui.primary-container` while following). 8 dp gaps. Not shown on S5. |
| **Search results** (S2) | M3 search view (expanded `SearchBar`), `LazyColumn` | List named «Хайлтын илэрц» (`contentDescription` of the list). Rows ≥ 56 dp: name (`body-large`), type label and area (`body`, `ui.on-surface-variant`), NAV-003 content rules. State rows: «Ачаалж байна…» (after 300 ms), «Илэрц олдсонгүй», «Хайлт түр ажиллахгүй байна» + «Дахин оролдох», «Түр хүлээгээд дахин оролдоно уу» (retry disabled for `Retry-After`), «Интернэт холболт алга». A result tap opens S3 within 500 ms (AC 3). |
| **Coordinate card** (S2) | M3 standard bottom sheet, card layout | Title «Сонгосон цэг» (`title`), close «Хаах» (icon button 48 dp), coordinates `lat, lon` with **5 decimals**, point as decimal separator in both languages (`body`, tabular), filled button «Маршрут гаргах» (48 dp, `ui.primary`). Candidate pin (map-style §7.3) at the point. 0 reverse requests (AC 4). |
| **Route preview sheet** (S3) | M3 standard bottom sheet (no drag handle in this slice; height = content, max 80 %, Layout rule 6) | Header row: «Маршрут харах» (`title`), close «Хаах». **Points block** (display only in this slice; origin editing is NAV-011): row 1 my-location icon + «Миний байршил» (content description «Эхлэх цэг: Миний байршил»), row 2 pin icon + destination text (result name or «Сонгосон цэг», content description «Очих газар: …»), each 48 dp, text ellipsised (names). **Mode tabs**: M3 `PrimaryTabRow`, group name «Зорчих хэлбэр», tabs «Машин» / «Явган» with icons, 48 dp. **Avoid switch row** «Шороон замаас зайлсхийх» + M3 `Switch` (car only; hidden on «Явган», AC 5). **Result region** (one of: loading row, state row, location message, summary). **Summary**: duration `title-large` + " · " + distance `body-large` `ui.on-surface-variant`; line 2 «Хүрэх цаг 14:03» (+ «+1 өдөр»); snap notice row (info icon + «Хамгийн ойрын зам сонгосон цэгээс 1,4 км зайтай») when > 500 m (AC 6). **«Эхлэх»**: M3 filled button, full width, **56 dp**, play/navigation icon + «Эхлэх» (`label`, 16 sp), pinned at the sheet bottom; disabled (`ui.disabled-content`, still focusable, content description unchanged) in every state without a route or without precise location (AC 7, 10). |
| **Location rationale** (S4) | M3 basic dialog | Icon `location_on` 24 dp `ui.primary`, headline «Байршлаа ашиглахыг зөвшөөрнө үү» (`title`, wraps), actions: text button «Хаах», filled button «Үргэлжлүүлэх». No body text (no glossary sentence exists; the headline says it). |
| **Location message** (S4) | Custom message card (S1: in R2; S3: in the result region) | Icon 24 dp + title (`body`, `ui.on-surface`) + optional hint line (`body`, `ui.on-surface-variant`) + actions row (text buttons, 48 dp, right-aligned, wrap). Variants: denied «Байршлын зөвшөөрөл олгоогүй байна» + hint «Утасны тохиргоонд байршлын зөвшөөрлийг асаана уу» + «Тохиргоо нээх», «Хаах»; approximate «Нарийвчилсан байршлыг зөвшөөрнө үү» + «Тохиргоо нээх», «Хаах»; services off «Байршил тогтоох үйлчилгээ унтарсан байна» + «Тохиргоо нээх», «Хаах»; no fix «Байршил тодорхойлж чадсангүй» + «Дахин оролдох», «Хаах». On S1 the card uses `ui.message-surface` (NAV-002 messages); in S3 it is a state row on `ui.surface`. |
| **NavBanner** (S5, RB) | **Custom** (replaces Ferrostar `InstructionsView`) | Card `nav.banner` (variant colours: navigation-ux §2.3), `radius.lg`, elevation 2, night 1 dp `nav.banner-outline`. Padding 16 dp. **Row layout** (effective banner font factor < 1.15): icon column 56 dp (`size.nav-banner-icon`, `nav.on-banner`) · 12 dp · text column: distance (`nav-distance` 32/40 700), instruction (`nav-instruction` 24/30 500, max 3 lines, word wrap, no ellipsis), street (`nav-street` 18/24 400, `nav.on-banner-variant`, **1 line**, ellipsis at the end). **Stacked layout** (factor ≥ 1.15): row 1 = icon + distance (icon alone in the recalculating and arrival variants); instruction and street below at the full banner width. Font caps: navigation-ux §2.5 (instruction and street 1.3, distance 1.2). Min height 104 dp; at most 50 % of the app height, scrolls inside beyond that (Layout rule 4). Distance uses tabular figures. Test tags `nav-banner`, `nav-banner-distance`, `nav-banner-text`, `nav-banner-street`, custom semantics property `variant` = `maneuver|reroute|arrival` (QA hook). |
| **Then strip** (S5, RT) | Custom, part of the banner card | 40 dp min, `nav.banner-then`, padding 8 16: «Дараа нь» (`label` 14/20, cap 1.3, `nav.on-banner`) + 24 dp icon. Test tag `nav-then`. |
| **Status message** (S5, RS) | Custom (M3 snackbar look, but placed at the top and not dismissing on scroll) | `ui.message-surface`, `radius.md`, elevation 2 (night: 1 dp `ui.message-outline`), padding 12 16, icon 24 dp + text (`body` 14/20, cap 1.3, `ui.on-message-surface`, wraps, never truncated). Up to **2** visible, 8 dp apart, by priority: (1) GPS lost / GPS restored, (2) offline «Интернэт холболт алга». No buttons (no tap needed while driving). Polite live region. Test tag `nav-status`, semantics `kind` = `gps-lost|gps-restored|offline`. |
| **Voice notice** (S5, RN) | Custom, same look as a status message, full RP width | Text «Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.» (`body`, cap 1.3), info icon. Shown **once per guidance session** for 8 s (`motion.nav-voice-notice`; AC 39 needs ≥ 5 s) directly above RP, so «Дуусгах» and the progress stay usable; tap dismisses it early. Never covers RB or R5. Polite live region. Test tag `nav-voice-notice`. |
| **Voice button** (RC) | M3 filled tonal icon button 48 dp (`ui.surface`, elevation 1; night 1 dp `ui.outline-variant`) | Icon `volume_up` (on) / `volume_off` (muted). Content description and tooltip: «Дууг хаах» when on, «Дууг нээх» when muted (AC 37). State remembered. Test tag `nav-voice`. |
| **Orientation button** (RC) | Same, right of the voice button | Icon shows the **current** mode (heading-up arrow / compass "N"); content description names the mode it switches **to**: «Хойд зүг дээшээ» while heading-up, «Явах чиглэл дээшээ» while north-up (AC 24). Test tag `nav-orientation`. |
| **Settings button** (RP) | M3 standard icon button 48 dp inside the progress panel | Gear, «Тохиргоо», left of «Дуусгах»; opens S7 over the guidance (guidance continues). Test tag `nav-settings`. |
| **Recenter** | M3 extended FAB (min 56 dp high, `ui.primary-container` / `ui.on-primary-container`) | Icon `my_location` + «Байршил руу буцах» (`label`, cap 1.3). Appears ≤ 300 ms after a map gesture, disappears when following resumes (AC 25). The label wraps to two lines when the width is short (the FAB grows); never truncated. Test tag `nav-recenter`. |
| **TripProgressPanel** (RP) | **Custom** (replaces Ferrostar `TripProgressView`) | Card `ui.surface`, `radius.lg`, elevation 2 (night 1 dp `ui.outline-variant`), padding 12 16, min 72 dp, one row. Left (fills): line 1 «Хүрэх цаг 14:35» (`title-large`, cap 1.3, `ui.on-surface`; «+1 өдөр» in `ui.on-surface-variant`; wraps at large font scales), line 2 remaining time + " · " + remaining distance (`body-large`, cap 1.3, `ui.on-surface-variant`), e.g. «25 мин · 12,4 км». Right: «Тохиргоо» icon button, then **«Дуусгах»**: round outlined icon button 48 dp, `close` icon in `ui.error`, 1 dp `ui.outline` ring, content description and tooltip «Дуусгах». Recomputed ≥ every 5 s and after every reroute (AC 22); frozen during GPS loss. TalkBack: one node «Хүрэх цаг 14:35, үлдсэн хугацаа 25 мин, үлдсэн зай 12,4 км» (not live). Test tags `nav-progress`, `nav-eta`, `nav-remaining`, `nav-end`. |
| **Arrival panel** (S6, RP) | Custom, same card as RP | Flag icon 24 dp + destination text (`title-large`, wraps up to 2 lines) + filled button «Хаах» (48 dp, right-aligned). Test tag `nav-arrival`. |
| **Attribution strip** (R5) | Custom (NAV-002 rule) | `ui.surface-container`, min 24 dp, padding 4 16, `caption` 12/16 `ui.on-surface-variant`: «© OpenStreetMap contributors». On S1 at zoom < 8 the ESA WorldCover credit follows on its own line (NAV-002 rule; never during guidance, zoom ≥ 15). Not focusable as a control; TalkBack reads it. Test tag `attribution`. |
| **Settings sheet** (S7) | M3 modal bottom sheet | Title «Тохиргоо». Section 1, theme (heading **pending BA term**, see Copy): radio rows «Өдрийн горим», «Шөнийн горим», «Автомат». Section 2 «Хэл»: radio rows «Монгол», «English» (each in its own language). Section 3: switch row «Дуут заавар» (same setting as the S5 voice button). Rows ≥ 56 dp. Changes apply immediately; no save button. Scrim over the map; on S5 guidance, voice and the notification continue underneath. |
| **Notification** (S8) | `NotificationCompat`, channel «Замчлал» | Importance **LOW** (silent: voice is separate; no heads-up), ongoing, category `navigation`, small icon = monochrome navigation arrow. **Title** = banner line (instruction, «Маршрутыг дахин тооцоолж байна», or «GPS дохио тасарлаа» while lost). **Text** = distance + " · " + street name (street omitted when empty; no text in the recalculating and GPS-lost cases). **Action** «Дуусгах». Tap opens S5. Updated when its text changes, at most once per second, and at least every 5 s while the distance changes (AC 17). Removed within 2 s of ending, within 10 s of arrival. Never contains coordinates (AC 67). Rich layout and lock-screen view: NAV-012. |
| **Puck, route line, destination pin** | MapLibre Native (our layers and markers) | map-style §7.4. |

### Content rules
- **Distances, durations, «Хүрэх цаг»**: exactly the NAV-004 rules (AC 23–25), reused in the preview, the banner, the progress panel and the notification. Mongolian decimal comma, English point; number and unit joined by a no-break space; tabular figures.
- **Banner text**: navigation-ux §2–3. The first letter of a stand-alone instruction is upper case (code capitalises, idempotent).
- **Street names**: `step.name` with U+200B, U+200C, U+200D, U+FEFF removed and trimmed; Traditional Mongolian script removed (NAV-003 rule); never translated (D11). In the English UI a Cyrillic street name keeps its text; TalkBack gets the locale `mn` for that span (`LocaleSpan` / Compose `LocaleList`) so it is read with Mongolian rules where available.
- **Destination text**: the search result name (NAV-003 content rules) or «Сонгосон цэг»; same text in S3, S6 and the destination pin's content description.
- **Coordinates** (S2): `47.91890, 106.91760`, 5 decimals, point separator in both languages (NAV-003). Never shown on S5/S6, never in the notification.
- **Manoeuvre icons**: NAV-004 icon table (Material Symbols shapes; bundled vector drawables; Apache-2.0 notice if copied, architect confirms). The banner uses the 56 dp size; the Then strip 24 dp.

## States

### S1 Map
| State | What the user sees | AC |
|---|---|---|
| Default | Map at P1 z12, flavor by theme, search bar, settings, controls, attribution. No location prompt | 1, 2 |
| Loading (tiles not ready) | NAV-002 loading pill «Ачаалж байна…» after 300 ms in R2 | 1 |
| Tiles unavailable | Message «Газрын зургийг ачаалж чадсангүй» + «Дахин оролдох» (R2); search and long-press still work | NAV-002 rule |
| Offline | Message «Интернэт холболт алга» (R2); loaded tiles stay | 54 (same indicator) |
| Following my location | My-location button in `ui.primary-container`; location dot (map-style §7) | — |
| Location problem (my-location tapped) | S4 rationale or message card in R2 (F3) | 8–12 |

### S2 Search and coordinate card
As NAV-003 for every list state (Components › Search results); coordinate card as above. Offline: list row «Интернэт холболт алга», 0 requests. GPS lost or no permission: search still works (bias from the map centre, D30). No result: «Илэрц олдсонгүй».

### S3 Route preview (result region, one at a time)
| State | What the user sees | «Эхлэх» | AC |
|---|---|---|---|
| Waiting for location | Loading row «Ачаалж байна…» after 300 ms | disabled | 9 |
| Location problem | S4 message variant (rationale first if never asked) | disabled | 8–12 |
| Pending ≤ 300 ms | Region empty, previous summary removed | disabled | 7 |
| Loading > 300 ms | Spinner + «Ачаалж байна…» (12 s timeout → unavailable) | disabled | 7 |
| Route | Summary, «Хүрэх цаг», snap notice if > 500 m; line and markers on the map; camera fits the route into the area above the sheet (NAV-004 camera rule, padding = sheet + 40 dp) | **enabled** | 6 |
| Same point | «Эхлэх цэг, очих газар ижил байна» | disabled | 7 |
| No route | «Маршрут олдсонгүй» (+ «Шороон замаас зайлсхийх тохиргоог унтрааж дахин оролдоно уу» when the switch is on) | disabled | 7 |
| Outside the area | «Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна» | disabled | 7 |
| Too far on foot | «Энэ зай явганаар эсвэл дугуйгаар хэт хол байна» | disabled | 7 |
| Routing unavailable | «Маршрутын үйлчилгээ түр ажиллахгүй байна» + «Дахин оролдох» | disabled | 7 |
| Offline | «Интернэт холболт алга»; 1 request by itself within 2 s after the network returns | disabled | 7 |
| Rate-limited | «Түр хүлээгээд дахин оролдоно уу» + «Дахин оролдох» disabled for `Retry-After` (5 s default) | disabled | 7 |
| Error | «Алдаа гарлаа» | disabled | 7 |
Precedence (NAV-004): location problem > same point > offline > rate-limited > unavailable > no route / outside / too far / error > loading > route.

### S5 Guidance
| State | Banner | Status area | Puck / map | Voice | AC |
|---|---|---|---|---|---|
| **On route** | Manoeuvre variant, distance live | empty | Snapped puck, follow camera, route in guidance colours | Schedule (navigation-ux §4.2) | 21–25, 31–35 |
| **Then** (close pair) | + Then strip | — | — | chained prompt | 32 (chaining) |
| **Not following** (after a gesture) | unchanged | unchanged | Camera free; «Байршил руу буцах» shown; back after 15 s | unchanged | 25 |
| **Off-route, requesting / 429 wait** | Recalculating variant, no secondary line | — | Raw puck; old route dimmed | «Та маршрутаас гарлаа» once | 41–44, 47 |
| **Off-route, reroute failing** | Recalculating + secondary «Маршрутын үйлчилгээ түр ажиллахгүй байна» / «Маршрут олдсонгүй» / «Алдаа гарлаа» / «Интернэт холболт алга» | offline indicator hidden while the banner says it | same | — | 48–50 |
| **New route** | Manoeuvre variant ≤ 500 ms | — | New line, snapped puck | catch-up prompt | 45 |
| **GPS lost** | Last manoeuvre, distance frozen and dimmed | «GPS дохио тасарлаа» (stays) | Stale grey puck at the last position | once | 51, 53 |
| **GPS restored** | Correct next manoeuvre ≤ 2 s | «GPS дохио сэргэлээ» 3 s | Blue puck | once + catch-up | 52, 53 |
| **Offline on route** | unchanged | «Интернэт холболт алга» ≤ 2 s, gone ≤ 2 s after return | Loaded tiles; missing tiles = map background | unchanged | 54 |
| **No usable voice** | unchanged | — (the A1 voice notice shows above RP once per session, 8 s) | — | chime per prompt | 39 |
| **Muted** | unchanged | — | — | 0 utterances, 0 chimes; button «Дууг нээх» | 37 |
| **Notification permission dialog** (first start, Android 13+) | visible under the OS dialog scrim | — | — | depart prompt still plays | 13 |
| **Night** | night tokens | night tokens | night puck and route | — | 58, 59 |
| **English UI** | `en` texts; street names stay Cyrillic | `en` texts | — | English voice or chime | 30, 60 |
| **Font 200 %** | Stacked layout, instruction ≤ 3 lines | messages wrap | map band ≥ 150 dp | — | 63 |
| **Landscape** | Left column | Left column (scrolls) | Map on the right | — | 64 |

### S6 Arrival
Banner arrival variant; arrival panel with destination text and «Хаах»; status area empty (GPS and offline messages are cleared: guidance has ended); no recenter button; the camera keeps following until «Хаах». Offline / GPS lost: nothing to show (no network or location needed any more).

### S7 Settings and S8 Notification
S7 has no loading or error state (local settings only). S8 states follow the banner (Components › Notification); with notification permission denied there is no notification and guidance works unchanged (AC 13).

## Interactions
- **S1 long-press** (600 ms, ≤ 10 dp movement): coordinate card; haptic `LONG_PRESS`. A second long-press moves the card's point.
- **S1 my-location button:** location access flow F3; then follow my location (NAV-002 behaviour).
- **S2 result tap:** S3 within 500 ms; the search query stays in the search bar for when the user comes back.
- **S3 mode tab / avoid switch:** one request each (tab changes settle 300 ms as NAV-004). **«Эхлэх»:** F4 checks (precise location, fresh good fix), then S5 within 1 s. **«Хаах» / Back:** back to S1 (or the coordinate card); in-flight request cancelled.
- **S5 map gestures** (pan, pinch, rotate, two-finger tilt): stop following, show recenter ≤ 300 ms; 0 requests. Double-tap zooms in (MapLibre default). **Recenter tap:** follow within 1 s.
- **S5 «Дуусгах»** (screen or notification): ends immediately (no confirmation dialog, Design notes 1); S1 without a route within 2 s.
- **S5 voice button:** toggles; the current utterance or chime stops within 1 s when muting.
- **S5 orientation button:** toggles heading up / north up within 1 s.
- **S5 settings button:** S7 over the guidance; guidance, voice and the notification continue.
- **S5 status A1 notice tap:** dismisses it early.
- **System Back:** S2 → S1; S3 → S1 (or card); S5 → **app to background** (`moveTaskToBack`), guidance continues (nothing destructive on Back while driving); S6 → «Хаах»; S7 → close sheet.
- **Home / screen off / other app:** guidance continues in the foreground service (AC 17).
- **Swipe-away from Recents:** guidance ends within 5 s (AC 20).
- **Rotation, theme, language:** state kept (AC 59, 60, 64).
- **No text input anywhere on S5/S6** (principle: no typing while moving).

## Copy (mn / en)
Every `mn` value is a glossary term (existing rows, NAV-002 G-rows, NAV-003 T-rows, NAV-004 N-rows, NAV-005 A1–A13). Android string resource names are a **proposal** (mobile engineer owns the final names and the resource folder decision, architect A5). `mn` and `en` key sets are identical (AC 61). `{n}`, `{count}`, `{distance}`, `{days}`, `{ordinal}`, `{first}`, `{second}` stay literally (`%1$s` mapping is a code concern; the literal braces are what the glossary check compares).

### UI
| Key | mn | en | Glossary source |
|---|---|---|---|
| `app_name` | Газрын зураг | Map | Map (launcher label per story Open question 8) |
| `attribution_osm` | © OpenStreetMap contributors | © OpenStreetMap contributors | OSM attribution (not translated) |
| `attribution_esa` | (ESA credit, as the web) | (same) | ESA WorldCover credit (not translated) |
| `search_placeholder` | Газар, хаяг хайх | Search for a place or address | Search |
| `search_clear` | Хайлтыг арилгах | Clear search | T1 |
| `search_results` | Хайлтын илэрц | Search results | T2 |
| `search_no_results` | Илэрц олдсонгүй | No results found | No results |
| `search_unavailable` | Хайлт түр ажиллахгүй байна | Search is temporarily unavailable | T4 |
| `search_rate_limited` | Түр хүлээгээд дахин оролдоно уу | Too many searches. Wait a moment and try again | T5 |
| `place_selected_point` | Сонгосон цэг | Selected point | T6 |
| `control_zoom_in` / `control_zoom_out` | Томруулах / Жижигрүүлэх | Zoom in / Zoom out | Zoom in / zoom out |
| `control_north_up` | Хойд зүг дээшээ | North up | North up |
| `control_heading_up` | Явах чиглэл дээшээ | Heading up | Heading up (D21) |
| `control_recenter` | Байршил руу буцах | Back to my location | Recenter (D22: never «Төвлөрүүлэх») |
| `marker_my_location` | Миний байршил | My location | My location |
| `settings_title` | Тохиргоо | Settings | Settings |
| `settings_theme_heading` | **pending BA** | Theme | — (request in the handoff) |
| `theme_day` / `theme_night` / `theme_auto` | Өдрийн горим / Шөнийн горим / Автомат | Day mode / Night mode / Automatic | Day mode, Night mode, A6 |
| `language_label` | Хэл | Language | Language |
| `language_mn` / `language_en` | Монгол / English | Монгол / English | Language (each in its own language) |
| `voice_guidance` | Дуут заавар | Voice guidance | Voice guidance / Voice on/off |
| `route_get_directions` | Маршрут гаргах | Directions | Get directions |
| `route_title` | Маршрут харах | Route preview | Route preview |
| `route_origin` / `route_destination` (a11y prefixes) | Эхлэх цэг / Очих газар | Start / Destination | Origin, Destination |
| `route_modes` | Зорчих хэлбэр | Travel mode | N19 |
| `route_mode_car` / `route_mode_walk` | Машин / Явган | Car / Walk | Travel mode |
| `route_avoid_unpaved` | Шороон замаас зайлсхийх | Avoid unpaved roads | Avoid unpaved roads |
| `route_eta` | Хүрэх цаг | Arrive at | ETA |
| `route_next_day_one` / `_other` | +{days} өдөр | +{days} day / +{days} days | N16 |
| `route_no_route` | Маршрут олдсонгүй | No route found | No route found |
| `route_no_route_avoid_hint` | Шороон замаас зайлсхийх тохиргоог унтрааж дахин оролдоно уу | Turn off “Avoid unpaved roads” and try again | N12 |
| `route_out_of_area` | Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна | The start or destination is outside the service area | N9 |
| `route_same_point` | Эхлэх цэг, очих газар ижил байна | The start and destination are the same | N10 |
| `route_too_far` | Энэ зай явганаар эсвэл дугуйгаар хэт хол байна | This distance is too far on foot or by bike | N11 |
| `route_unavailable` | Маршрутын үйлчилгээ түр ажиллахгүй байна | Routing is temporarily unavailable | N8 |
| `route_rate_limited` | Түр хүлээгээд дахин оролдоно уу | Too many route requests. Wait a moment and try again | T5 |
| `route_snap_notice` | Хамгийн ойрын зам сонгосон цэгээс {distance} зайтай | The nearest road is {distance} from the chosen point | N13 |
| `nav_name` (channel, notification) | Замчлал | Navigation | Navigation (active guidance) |
| `nav_start` / `nav_end` | Эхлэх / Дуусгах | Start / End | Start / End navigation |
| `nav_mute` / `nav_unmute` | Дууг хаах / Дууг нээх | Mute / Unmute | Voice on/off, A5 |
| `nav_rerouting` | Маршрутыг дахин тооцоолж байна | Recalculating route | Reroute |
| `nav_gps_lost` / `nav_gps_restored` | GPS дохио тасарлаа / GPS дохио сэргэлээ | GPS signal lost / GPS signal restored | GPS rows |
| `nav_voice_unavailable` | Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана. | Mongolian voice guidance is not available on this phone. Instructions are shown on screen only. | A1 |
| `nav_then` | дараа нь (shown capitalised: «Дараа нь») | then (shown "Then") | Then (chained instruction) |
| `nav_remaining_time` / `nav_remaining_distance` (TalkBack) | үлдсэн хугацаа / үлдсэн зай | time left / distance left | Remaining time / distance |
| `unit_m` / `unit_km` / `unit_h` / `unit_min` | м / км / ц / мин | m / km / h / min | Metre, Kilometre, N21, N22 |
| `location_rationale` | Байршлаа ашиглахыг зөвшөөрнө үү | Allow access to your location | Location permission (rationale) |
| `location_denied_title` | Байршлын зөвшөөрөл олгоогүй байна | Location permission is turned off | G3 |
| `location_denied_hint` | Утасны тохиргоонд байршлын зөвшөөрлийг асаана уу | Allow location access in your phone settings | A2 |
| `location_precise` | Нарийвчилсан байршлыг зөвшөөрнө үү | Allow precise location | A4 |
| `location_services_off` | Байршил тогтоох үйлчилгээ унтарсан байна | Location services are turned off | Location services off |
| `location_unavailable` | Байршил тодорхойлж чадсангүй | Your location could not be determined | G5 |
| `action_open_settings` | Тохиргоо нээх | Open settings | A3 |
| `action_continue` | Үргэлжлүүлэх | Continue | A7 |
| `action_retry` / `action_close` | Дахин оролдох / Хаах | Try again / Close | Try again, G6 |
| `status_loading` / `status_offline` / `status_generic_error` / `status_tiles_unavailable` | Ачаалж байна… / Интернэт холболт алга / Алдаа гарлаа / Газрын зургийг ачаалж чадсангүй | Loading… / No internet connection / Something went wrong / The map could not be loaded | G1, No connection, Generic error, G2 |

### Manoeuvre texts (banner, notification; AC 26–30)
Keys `maneuver_*` = the NAV-004 keys with `_` (e.g. `maneuver_turn_left`, `maneuver_depart_ne`, `maneuver_roundabout_exit` «Тойрог: {n}-р гарц», `maneuver_arrive_right`). Values: exactly NAV-004 screen spec › Copy › Manoeuvre texts (`mn` and `en`). The banner never uses the `depart_*` keys (navigation-ux §2.1); voice does.

### Voice texts (AC 32; navigation-ux §4.1)
| Key | mn | en | Glossary source |
|---|---|---|---|
| `voice_prefix_m` (plural: `one` / `other`) | {n} метрт (both forms) | `one`: In {n} meter · `other`: In {n} meters | A8 |
| `voice_prefix_km` (plural: `one` / `other`) | {n} километрт (both forms) | `one`: In {n} kilometer · `other`: In {n} kilometers | A9 |
| `voice_roundabout_exit` | Тойрогт ороод {ordinal} гарцаар гарна уу | Enter the roundabout and take the {ordinal} exit | A10 |
| `voice_ordinal_1` … `voice_ordinal_10` | нэгдүгээр, хоёрдугаар, гуравдугаар, дөрөвдүгээр, тавдугаар, зургаадугаар, долоодугаар, наймдугаар, есдүгээр, аравдугаар | first, second, third, fourth, fifth, sixth, seventh, eighth, ninth, tenth | C4 (quoted forms requested from the BA for the AC 61 exact-match check) |
| `voice_approaching` (plural: `one` / `other`) | {n} метрт очих газартаа ирнэ (both forms) | `one`: In {n} meter, you will arrive · `other`: In {n} meters, you will arrive | A11 |
| `voice_continue_on` (plural: `one` / `other`) | {n} километр үргэлжлүүлэн явна уу (both forms) | `one`: Continue for {n} kilometer · `other`: Continue for {n} kilometers | A12 |
| `voice_then` | {first}, дараа нь {second} | {first}, then {second} | A13 |
| `voice_off_route` | Та маршрутаас гарлаа | You have left the route | Off-route |
| (reused) `nav_gps_lost`, `nav_gps_restored`, `maneuver_*` incl. `depart_*` and `arrive*` | | | |
Joining a prefix and an instruction is code formatting: `mn` = prefix + " " + instruction (lower-case first letter), `en` = prefix + ", " + instruction (lower-case first letter). English decimal point, Mongolian comma.

**Plural forms (NAV-005-D4, AC 32 `en`; navigation-ux §4.1 "English singular / plural").** The four distance templates above are Android `<plurals>` resources under their existing names, with the quantities `one` and `other`, in both `values/` (`mn`) and `values-en/` (`en`). `mn`: both items carry the current text unchanged (Mongolian does not inflect the unit after a number). `en`: `one` = singular unit, `other` = plural unit, exactly as in the table. **Quantity selection:** `one` if and only if the formatted number `{n}` (after the §4.1 rounding, before substitution) is exactly `1`; otherwise `other`. Do not derive the quantity from an `int` cast of the distance in km (1.5 km would cast to `1` and give "In 1.5 kilometer"): decide `one` / `other` from the formatted string, then substitute `{n}`. The `{n}` placeholder stays the substitution point (no `%d`), as today. If the Context-free voice generator (AC 40, JVM) cannot read `<plurals>`, the equivalent flat keys are `<key>_one` / `<key>_other` (the `route_next_day_one` / `_other` precedent); the forms and the rule are the same either way. Examples (`en`): 1,040 m → "In 1 kilometer, turn slightly left"; 1,500 m → "In 1.5 kilometers, keep left"; 2,000 m → "Continue for 2 kilometers"; 300 m → "In 300 meters, turn right". Examples (`mn`, unchanged): «1 километрт бага зэрэг зүүн тийш эргэнэ үү», «1,5 километрт зүүн талаа барина уу». Banner, notification, progress and preview distances use `unit_m` / `unit_km` ("m" / "km"), which do not inflect, so they get no plural forms.

### Length notes (Mongolian first)
Longest banner instructions: «Баруун талын гарах замаар гарна уу» (34), «Таны очих газар баруун талд байна» (33), «Бага зэрэг баруун тийш эргэнэ үү» (32), «Маршрутыг дахин тооцоолж байна» (30); longest banner secondary line «Маршрутын үйлчилгээ түр ажиллахгүй байна» (40); longest status message A1 (82); longest preview state «Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна» (62); longest button «Байршил руу буцах» (17) and «Үргэлжлүүлэх» (12). All wrap; only names ellipsise. Some English strings are longer (search placeholder "Search for a place or address" 29 vs 16, "Back to my location" 19 vs 17); every rule applies to both languages and the layout checker runs both.

## Accessibility
- **TalkBack order (S5):** banner (distance node, then the instruction + street node) → Then strip → status messages → recenter (when shown) → voice → orientation → voice notice (when shown) → progress node → «Тохиргоо» → «Дуусгах» → attribution. The map is one node («Газрын зураг»); the puck is not focusable.
- **Announcements (AC 62):** the instruction + street node is a **polite live region**: it announces once when the instruction changes (step advance, reroute, recalculating, arrival). The distance node is not live (no announcement per fix). Status messages and the voice notice are polite live regions. Nothing is `assertive`.
- **Names:** every control has a glossary name: «Дуусгах», «Байршил руу буцах», «Дууг хаах» / «Дууг нээх», «Хойд зүг дээшээ» / «Явах чиглэл дээшээ», «Тохиргоо», «Эхлэх», «Хаах»; icons in the banner and Then strip are decorative (`contentDescription = null`); the Then strip's text node is «Дараа нь, <instruction>».
- **Touch targets:** all ≥ **48 × 48 dp** (icon buttons 48, FAB and «Эхлэх» 56, rows ≥ 56); 8 dp between adjacent targets (gloves in winter).
- **Contrast** (WCAG 2.2 AA, checked by `check-contrast.mjs`): banner text on every banner variant ≥ 4.5:1 in both themes (white on `nav.banner` 6.5:1 day; `on-banner` on night banner 9.7:1); `nav.on-banner-variant` ≥ 4.5:1; status text on `ui.message-surface`, panel text on `ui.surface`, `ui.error` («Дуусгах») on `ui.surface` ≥ 4.5:1; puck ring against the route and the earth ≥ 3:1; recenter label on `ui.primary-container` ≥ 4.5:1.
- **Dynamic type:** all text in `sp`. The **guidance overlay** (banner, Then strip, status messages, voice notice, progress panel, recenter label) scales with the system font scale up to a cap of **1.3** (banner distance 1.2), navigation-ux §2.5; the reason is measured, not taste: uncapped, a 3-line Mongolian instruction plus the panels leave no map at 200 % on a 360×640 phone. Every other screen (S1–S4, S6 panel, S7, attribution) scales without a cap; panels grow, wrap and never truncate except names.
- **Reduced motion** (Android "Remove animations" / animator scale 0): camera jumps, no banner cross-fade, the recalculating icon does not rotate.
- **Colour is never the only signal:** off-route = grey banner **and** different icon **and** text; stale puck = grey **and** the «GPS дохио тасарлаа» message; muted = different icon **and** name.
- **Language of parts:** Cyrillic street names in the English UI carry the `mn` locale for TalkBack.
- **No time limits** except the A1 notice (8 s, ≥ 5 s required, AC 39) and «GPS дохио сэргэлээ» (3 s, AC 52); both are informational, the banner and voice carry the guidance.

## Design notes (decisions inside the AC)
1. **«Дуусгах» without a confirmation dialog.** Google Maps pattern; AC 19 measures 2 s from the tap. Accidental-tap risk is reduced by placement (far right of the progress panel, away from recenter bottom-left and below the control pair) and a round outlined icon button (not a big filled target). Icon-only (content description «Дуусгах», tooltip on long-press) because a labelled button pushed «Хүрэх цаг 14:35» onto two lines at 360 dp (prototype measurement); the close glyph in the error colour is the Google Maps / Waze convention. Restarting is two taps from S1 (the destination stays in search).
2. **Recalculating banner is neutral grey**, not red (navigation-ux §2.3).
3. **Pitch 45° in heading-up**, 0° in north-up (navigation-ux §8): more road ahead visible, familiar.
4. **Then strip** shows the second manoeuvre of a close pair, matching the chained voice prompt (navigation-ux §2.4). It uses only the glossary word «дараа нь».
5. **Guidance overlay font cap 1.3** (distance 1.2) with a stacked banner from an effective factor of 1.15 (navigation-ux §2.5). The first proposal (1.5 / 1.4) failed the prototype check: at 130 % the map band at 360×640 fell to 32 dp.
6. **Status area holds at most 2 short messages** (GPS > offline). The long voice notice A1 goes **above the progress panel** instead (measured: in the status area it took 5 lines and pushed the map band below 70 dp); there it never hides «Дуусгах».
7. **System Back on S5 sends the app to the background** instead of ending guidance.
8. **Settings reachable during guidance** (gear in the progress panel) so the language and theme switches of AC 59–60 are in-app, not only through system settings.
9. **Origin is fixed to «Миний байршил»** in this slice (story: origin «Миний байршил»; editable origin is NAV-011), so the preview has no origin field and no swap button.
10. **Search result → preview directly** (AC 3: 500 ms to the preview), no place card in between.
11. **Notification importance LOW** (silent): sound comes from the voice controller with navigation audio attributes; a notification sound would double every update.
12. **Recenter auto-resume 15 s** (story default) confirmed: long enough to look at a junction ahead, short enough that a driver who panned by accident gets the follow camera back without a tap.

## Known limitations (design, within the AC)
1. **No typing lock on S1/S3 while the phone moves.** The principle says no typing while moving; S5 has no input, but S1's search can be used in a moving car (a passenger may be typing). A lock with a passenger override needs glossary strings; recommended for NAV-011 (Open question in the handoff).
2. **No travelled-route trimming** (the route behind the puck stays drawn); polish for NAV-012.
3. **No turn list in the Android preview** (NAV-011); the banner shows one manoeuvre at a time.
4. **English UI without an English TTS voice** gets the chime but no notice until the BA provides a language-neutral notice (request in the handoff).
5. **Puck does not move in tunnels** (no dead reckoning, story edge case).
6. **Settings theme heading** waits for a BA glossary term; until then the theme radio group has no visible heading (the options are still readable, «Автомат» is the weakest).
7. **320 dp-wide phones above 100 % font scale** are outside the design target: the banner region scrolls and the floating controls can overlap the status message (the checker reports these as INFO). At 100 % on 320×568 every rule holds except the 150 dp map band (74 dp measured).
8. **Landscape with large fonts:** the left column (banner, Then strip, status) scrolls when it does not fit; the instruction is always at the top. With a much wider font than Roboto (DejaVu Sans stress run) «Маршрутыг дахин тооцоолж байна» needs 4 lines in the 320 dp landscape column.

## AC traceability
| AC | Where |
|---|---|
| 1, 2 | S1, Layout rule 1, R5 attribution strip; flow F1 |
| 3, 4 | S2 components and states; flow F2a, F2b |
| 5–7 | S3 components and states; flow F4 |
| 8–14 | S4 rationale and message; S3/S1 placement (Layout rule 7); flow F3 |
| 13, 15–20 | S5 states (notification dialog), S8 notification, Interactions (end, Back, swipe-away); flow F4, F5 |
| 21–25 | NavBanner, TripProgressPanel, control pair (RC), recenter; navigation-ux §2, §8; Layout rule 2 |
| 26–31 | Copy › Manoeuvre texts; navigation-ux §2–3; NavBanner variants |
| 32–40 | Copy › Voice texts (incl. English plural forms, D4); navigation-ux §4; flow F9 |
| 41–50 | S5 states (off-route rows); navigation-ux §5; flow F6 |
| 51–53 | S5 states (GPS rows); navigation-ux §6; flow F7 |
| 54 | S5 states (offline on route); flow F8 |
| 55–57 | S6; navigation-ux §7; flow F10 |
| 58–60 | S7; navigation-ux §9; flow F11; tokens night mode |
| 61 | Copy (keys, glossary sources) |
| 62–64 | Accessibility; Layout rules 4–5; Verification |
| 65–73 | No UI (privacy, build, verification); S8 never shows coordinates |

## Verification
- `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav005.mjs`: opens the wireframe at 360×640, 412×915, 320×568 (smallest Android) and 640×360 (landscape) dp, all with a 24 dp status bar and a 48 dp 3-button navigation bar (worst case), in 13 states (guidance; Then strip with a long street; worst case Then + GPS lost + recenter; recalculating; reroute failed; GPS lost; offline; voice notice; arrival; preview with route; preview with the denied-location message; preview out-of-area; rationale dialog) × day/night × mn/en × font scale 1.0/1.3/2.0 = 624 combinations, with Liberation Sans (Arial metrics, slightly wider than Roboto). Checks: attribution fully visible and intersecting no other element; no two boxes or controls overlap; every control ≥ 48 × 48; banner instruction ≤ 3 lines and not clipped; control labels not clipped; map band (Layout rule 4); puck centre inside the uncovered map; preview sheet without scrolling at 100 % on ≥ 360×640; no horizontal overflow. `--wide` runs the same with DejaVu Sans as a stress test.
  - **Result 2026-10-01: 0 problems** in the design target (232 INFO lines for 320×568 above 100 %, Known limitations 7). Longest Mongolian instruction «Баруун талын гарах замаар гарна уу»: **2 lines** at 360 and 412 dp at every font scale, 3 lines at 320 dp and in the landscape column. Minimum portrait map band at 360×640: **204 dp** at 100 %, **86 dp** at 130 %, **75 dp** at 200 % (worst-case state with the voice notice: rule 64 dp); at 412×915: 479 / 397 / 386 dp.
  - **Stress run (`--wide`, DejaVu Sans, much wider than Roboto): 104 problems**, all at 360×640 with font scale 130–200 % (worst case, voice notice, offline and the preview sheet at 200 %) and in the recalculating banner at 320 dp and in the landscape column (4 lines), Known limitations 8. Not a design target; recorded so QA knows where the margins are thin on phones with wide OEM fonts.
  - Iterations that the checker forced (recorded in Design notes 1, 5, 6): font caps lowered from 1.5 to 1.3, the control column moved from top-right to a bottom-right pair, «Тохиргоо» moved into the progress panel, «Дуусгах» became icon-only, the voice notice moved above the progress panel, the street name kept to one line.
  - It checks the design, not the app; QA's Compose tests check the app (AC 2, 62, 63).
- `node docs/design/prototypes/check-contrast.mjs`: all `contrastPairs` including the new `nav.*` pairs, flavor key parity, and the map-style.md colour tables (now including §7.4).

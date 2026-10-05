# Screen: Android offline Mongolia map («Монголын газрын зураг татах»: first-launch offer, Settings section, dialogs, progress, updates, delete) and the "offline" indicator

- **Stories:** [NAV-022](../../requirements/stories/NAV-022-android-offline-pack-download-update-map.md) (UI-relevant AC 1–15, 17, 22, 26–30, 33, 36–41, 44–45); the indicator OF24 for [NAV-021](../../requirements/stories/NAV-021-android-on-device-routing-reroute.md) AC 27–28 and [NAV-023](../../requirements/stories/NAV-023-offline-search-reverse.md) AC 21–24. Traceability per AC at the end.
- **Decisions:** D160, D163–D166, D169, D170, D199, D200, D201 ([decisions.md](../../requirements/decisions.md)). No new decision is recorded by this spec; the open points are listed under Open questions.
- **Platforms:** Android (D162; iOS later). Phones with and without Google Play services (D62).
- **Flow:** [`flows/NAV-022-offline-download.md`](../flows/NAV-022-offline-download.md) (F1–F8).
- **Prototype:** [`prototypes/NAV-022-offline-pack.html`](../prototypes/NAV-022-offline-pack.html) (1 CSS px = 1 dp; 24 states; hash example `#state=offer-first&theme=night&lang=mn&scale=2`; `v=b` / `v=c` show the measured alternatives). Checked by `prototypes/check-layout-nav022.mjs` (Evidence). No Figma file.
- **Base specs (unchanged unless named here):** NAV-005 S1, S2, S5, S7 ([NAV-005-android-navigation.md](NAV-005-android-navigation.md)), NAV-011 S2 results and coordinate card ([NAV-011-…](NAV-011-android-route-preview-search-parity.md)), NAV-012 S7 battery row and notification ([NAV-012-…](NAV-012-android-background-lock-screen.md)), NAV-018 S3 sheet ([NAV-018-…](NAV-018-android-origin-turn-list.md)). Tokens: [`tokens.json`](../tokens.json) v0.6.3 (adds `size.offline-indicator`, `size.offline-indicator-icon` and the pair `light.ui.error` on `light.ui.surface` ≥ 4.5).

## Purpose
Let a driver put the whole Mongolia map, routing and search on the phone once, over Wi-Fi by default, keep it fresh without thinking about it, and always know when a route or a search result came from that offline data.

## Context and goal

| Surface | Primary task | Moment of use | Time budget |
|---|---|---|---|
| **O1** first-launch offer (on S1) | Decide once: download now or later | First minutes with the app, usually at home on Wi-Fi, not driving (the offer needs Wi-Fi and never shows during guidance) | Read and decide in ~10 s; the decision is reversible in «Тохиргоо» |
| **O2** Settings section «Офлайн газрын зураг» (in S7) | Start, watch, cancel, update or delete the pack; check how old the data is | Planning at home, or parked before a countryside trip | Status in one glance (< 2 s); actions one tap |
| **O3** mobile-data dialog | Accept spending ~120 MB of mobile data, or wait for Wi-Fi | Parked or passenger, a deliberate act just after tapping «Татах» | One decision, two buttons |
| **O4** 14-day offer (on S1) | Refresh routing + search (~33 MB) over mobile data, or not now | A countryside or taxi driver opening the app with no Wi-Fi for weeks; possibly in a parked car | ~5 s; one tap; repeats at most every 7 days |
| **O5** delete dialog | Confirm freeing ~202 MB | Planning, deliberate | One decision |
| **O6** messages and notifications | Know that the download finished or failed, and what to do | Any time, often with the app in the background | One glance; one action |
| **O7** "offline" indicator | Notice that a route or results came from week-old offline data | S3 preview (parked, one glance), S2 search (typing or passenger), S5 guidance (driving: **peripheral only**, must not compete with the banner) | Recognised in < 1 s, never read on purpose while driving |

**Users:** intercity / countryside drivers (primary), taxi / delivery drivers without home Wi-Fi, UB commuters, pedestrians, tourists (English UI). Mongolian strings are designed first; the English strings are shorter in every row measured here.

### Alternatives considered

**1. How the first-launch offer is presented (AC 1–2).**
- (a) **Modal bottom sheet that ends above the attribution strip R5** (scrim over the map only), heading, one-line benefit, the two sizes, «Дараа» and a filled «Татах» pinned at the bottom. **Chosen.**
- (b) Centred M3 dialog with the same content. Rejected after measuring: the primary button's centre sits at **66 %** of the screen height at 360×640 / 100 % (a: **83 %**), 72 % vs 80 % at 320×568, 71 % vs 88 % at 412×915, i.e. further from the thumb (Fitts); and a dialog scrim would dim R5 (NAV-005 Layout rule 1 allows that only for system-like dialogs).
- (c) Non-modal card in R2 under the search bar (NAV-002 message style). Rejected: it competes with the location / tiles / network messages that own R2 (NAV-005 Layout rule 7), it would stay while the user pans, and AC 2 sets the flag the moment it shows, so a card that the user scrolls past is an offer lost for good.

**2. Where the download lives in «Тохиргоо» (AC 4).**
- (a) **A section «Офлайн газрын зураг» at the end of the existing S7 sheet**, with the whole state (status, dates, actions) inline. **Chosen.** S7 opens scrolled to this section when it is opened from an offline notification or an offline message (rule B4).
- (b) A row in S7 that opens a separate full-screen page (the Google Maps "Offline maps" pattern). Rejected for now: AC 4 asks S7 itself to show the section's state; one more level for a single pack (no regions to manage, D158) adds a tap without adding choice. Revisit if regions or a storage location ever come (Out of scope today).
- (c) The section at the top of S7. Rejected: theme, language and voice are quick everyday toggles; the pack is set up once. Measured cost of (a): the section starts **527 dp** down the S7 content at 100 % (547 at 130 %, 642 at 200 %), so from the gear the user scrolls once; every path that is *about* the pack lands on it directly (rule B4).

**3. How progress and failures are shown (AC 12, 15, 41).**
- (a) **One status row inside O2** (icon, text, M3 linear progress, «Цуцлах» or «Дахин оролдох») **plus** the low-importance notification, **plus** an S1 message for the end of a user-started download when the user is on S1/S2. **Chosen.** The same row component serves progress, waiting, checking, failed and storage-full (Gestalt similarity).
- (b) A full-screen progress page. Rejected: a 120 MB download takes minutes; a page traps the user (user control and freedom) for something that runs in the background anyway.
- (c) Failures as dialogs. Rejected: a dialog can pop up over guidance or a search; failures here are never urgent (the old pack keeps working, AC 15, 17).

**4. The indicator's form (D201; NAV-021 AC 27–28, NAV-023 AC 21–24).** Measured in the prototype (`v=a` / `v=b` / `v=c`):

| Form | S3 collapsed sheet, 360×640 mn, 100 / 130 / 200 % | Map band above it | S5 progress panel, 360×640 mn, 100 / 130 % | Guidance map band, 100 / 130 / 200 % |
|---|---|---|---|---|
| (a) **Labelled chip** (icon + «Офлайн») on the line of its anchor | **312 / 327 / 388 dp** (no cost at 100 % and 130 %: it sits on the duration line) | 224 / 204 / 132 dp | 102 / 157 dp (+26 / +29 dp: it wraps to its own line at 360 dp) | 260 / 144 / 129 dp |
| (b) **Icon only** (same glyph, name OF25 for TalkBack) | 312 / 327 / 368 dp | 224 / 204 / 152 dp | **76 / 128 dp** (no cost) | **286 / 173 / 158 dp** |
| (c) Full-width row «Офлайн газрын зургаас» | 356 / 373 / 388 dp (+44 dp at 100 %) | 180 / 158 / 132 dp | 136 / 200 dp | 226 / 101 / **18 dp (fails the 80 dp minimum at 200 %)** |

**Chosen: (a) on S2, S3 and the cards; (b) on S5 guidance.** Where the user plans (preview, results, cards) the label costs nothing at 100–130 % and teaches the glyph («Офлайн» in words, recognition over recall). In guidance the label would cost 26–29 dp of map band at 360 dp and add words to a glance-only panel (driver safety, cognitive load), so the same glyph appears alone, after the user has already seen it labelled on the preview. (c) is louder than the selected route (Von Restorff) and fails the guidance band.

**5. The indicator's colour.** (a) **Neutral**: `ui.surface-container` fill, `ui.on-surface-variant` text and icon, 1 dp `ui.outline-variant` border. **Chosen.** (b) Amber like the demo badge: reads as a warning or as "test". (c) `ui.primary-container`: competes with the selected route and «Эхлэх». Offline data is normal and useful, not an error; it must be noticeable, not alarming.

**6. Glyph.** (a) **Material Symbols `offline_pin`** (a tick in a circle: "saved on the phone", the convention for downloaded content in Google apps). **Chosen.** (b) `cloud_off` / `wifi_off`: says "no connection", which is wrong after a 3.0 s fallback with a working network (D199) and would clash with the NAV-005 network message.

## Layout

### O1 first-launch offer and O4 14-day offer (portrait, 360×640 dp, mn, day; `offer-first`, `offer-14`)

```
┌────────────────────────────────┐   ┌────────────────────────────────┐
│ [🔍 Газар, хаяг хайх     ] [⚙] │   │ (S1 dimmed by the scrim)       │
│ (map dimmed by the scrim)      │   │                                │
│                                │   │                                │
│╭──────────────────────────────╮│   │╭──────────────────────────────╮│
││            ▬▬                ││   ││            ▬▬                ││
││ [map icon 40]                ││   ││ [download icon 40]           ││
││ Монголын газрын зураг татах  ││   ││ Маршрут, хайлтын мэдээлэл    ││
││ Интернэт холболтгүй үед ч    ││   ││ хуучирсан байна              ││
││ газрын зураг, маршрут, хайлт ││   ││ Мобайл датагаар 33 МБ        ││
││ ажиллана                     ││   ││ шинэчлэх үү?                 ││
││ ⤓ Татах хэмжээ: 120 МБ       ││   ││ Маршрут: 2026-09-15          ││
││ ▯ Утсанд шаардлагатай зай:   ││   ││ Хайлт: 2026-09-15            ││
││   338 МБ                     ││   ││                              ││
││            Дараа  [ Татах ]  ││   ││         Дараа  [ Шинэчлэх ]  ││
│╰──────────────────────────────╯│   │╰──────────────────────────────╯│
│ © OpenStreetMap contributors   │   │ © OpenStreetMap contributors   │
└────────────────────────────────┘   └────────────────────────────────┘
```

### O2 Settings section (end of S7; `set-*`)

```
No pack (set-none)                 Downloading (set-downloading)      Installed, update available (set-update)
│ Офлайн газрын зураг            │ │ Офлайн газрын зураг            │ │ Офлайн газрын зураг            │
│ Интернэт холболтгүй үед ч      │ │ ⤓ Татаж байна… 42%             │ │ Мэдээллийн огноо               │
│ газрын зураг, маршрут, хайлт   │ │ ▬▬▬▬▬▬▬▬▬────────────          │ │ Газрын зураг: 2026-09-07       │
│ ажиллана                       │ │                       Цуцлах   │ │ Маршрут: 2026-09-15            │
│ ⤓ Татах хэмжээ: 120 МБ         │ │ ⤓ Татах хэмжээ: 120 МБ         │ │ Хайлт: 2026-09-15              │
│ ▯ Утсанд шаардлагатай зай:     │ │                                │ │ Эзэлж буй зай: 202 МБ          │
│   338 МБ                       │ │ Waiting (set-waiting):         │ │ ⤓ Татах хэмжээ: 33 МБ          │
│ [⤓ Монголын газрын зураг татах]│ │ ◠ Wi-Fi холболт хүлээж байна   │ │ [⤓        Шинэчлэх           ] │
│                                │ │                       Цуцлах   │ │────────────────────────────────│
│ Failed (set-failed):           │ │                                │ │ 🗑 Офлайн газрын зургийг устгах │
│ ⓘ Газрын зургийг татаж         │ │ Checking (set-verifying):      │ │ © OpenStreetMap contributors   │
│   чадсангүй                    │ │ ⤓ Татаж байна… 100%  (OQ 1)    │ │ Open Database License (ODbL)   │
│                Дахин оролдох   │ │ ───▬▬▬▬▬───── (indeterminate)  │ │ 1.0 ↗                          │
```

### O3 mobile-data dialog and O5 delete dialog (M3 basic dialogs over S7; `dlg-mobile`, `dlg-delete`)

```
╭──────────────────────────────╮    ╭──────────────────────────────╮
│ Мобайл датагаар 120 МБ       │    │ Офлайн газрын зургийг устгах │
│ татах уу?                    │    │ уу?                          │
│                              │    │ Эзэлж буй зай: 202 МБ        │
│        Wi-Fi хүлээх   Татах  │    │              Цуцлах  Устгах  │
╰──────────────────────────────╯    ╰──────────────────────────────╯
```

### O7 indicator placements (`ind-*`)

```
S3 collapsed summary               S2 results                         S5 progress panel
│ 18 мин · 7,4 км  [✓ Офлайн]    │ │ [← Гандан                  ✕] │ │ Хүрэх цаг 14:35        ⚙  (✕) │
│ Хүрэх цаг 14:08                │ │ [✓ Офлайн]                     │ │ 25 мин · 12,4 км ✓             │
│ [▶          Эхлэх            ] │ │ ◎ Гандан хийд                  │
                                   │   Хийд · Сонгинохайрхан …      │  Coordinate card
                                   │ ◎ Гандангийн гудамж …          │ │ Ойролцоох газар [✓ Офлайн]    │
```

### Layout rules

- **F1. Attribution.** R5 «© OpenStreetMap contributors» stays visible on every map screen of this feature. The O1 / O4 scrim covers the app **above** R5 only, and the sheet ends at R5's top edge. S7 and the O3 / O5 dialogs keep the NAV-012 precedent (their full-screen scrim may dim R5 while open). The pack section repeats «© OpenStreetMap contributors» and the ODbL line (AC 4, 33).
- **F2. O2 in S7.** O2 is the **last** section of S7, after the NAV-012 battery row, with a 1 dp divider above. Section heading OF1 in the S7 section style (`label` 14/20 500, `ui.primary`). Within the section, top to bottom: the status or benefit line → the facts (sizes) → the primary action of the state → (installed) divider → delete → attribution → ODbL line. **One primary action per state** (table in States). When S7 is opened for the pack (rule B4) it scrolls so that the section heading is at the top of the sheet's scroll area; at font scale ≤ 130 % on ≥ 360×640 the heading and the primary action are then both visible without further scrolling (measured, all states).
- **F3. O1 / O4 content.** Drag handle 24 dp; hero icon 40 dp (`ui.primary`, decorative); heading (`title-large` 22/28, wraps, never truncated); body (`body-large` 16/24); facts (`body` 14/20, `ui.on-surface-variant`, 20 dp leading icons, tabular figures). Content scrolls inside the sheet; the **button row is pinned** at the bottom (padding 12/24/16, 1 dp `ui.outline-variant` top divider only while the content scrolls). Buttons: text «Дараа» then filled «Татах» / «Шинэчлэх» at the end edge, **16 dp apart**, both ≥ 48 dp; they wrap onto two rows when they do not fit (never measured to wrap at 100–200 % at ≥ 320 dp).
- **F4. O1 / O4 sheet.** Max width 640 dp (centred on wider windows), top corners 28 dp, at least 56 dp of dimmed map above it in portrait (16 dp in landscape), elevation as the M3 modal sheet (night: 1 dp `ui.outline-variant` instead of the shadow, NAV-005 night rule). At 200 % on 360×640 and on 320×568 the content scrolls (buttons stay pinned); at 412×915 nothing scrolls even at 200 %.
- **F5. Dialogs O3 / O5.** M3 basic dialog: width min(560 dp, window − 48 dp), radius 28 dp, padding 24 dp, title `headline-small` 24/32 (wraps), optional supporting text `body` (`ui.on-surface-variant`), actions right-aligned text buttons, 8 dp apart, wrapping. No icon, no scrollable body. The confirming action is at the end edge («Татах», «Устгах»); «Устгах» uses `ui.error` text (6.54:1 day, 8.81:1 night).
- **F6. S1 messages.** O6 messages use the NAV-002 / NAV-005 message card in R2 under the search row, with the NAV-005 D5 top-group cap (the card shrinks and scrolls inside; it never runs under the map controls).
- **F7. Indicator component.** Non-interactive label, min height 24 dp (`size.offline-indicator`), padding 2/8/2/6 dp, radius `md` 8 dp, fill `ui.surface-container`, border 1 dp `ui.outline-variant`, icon `offline_pin` 16 dp + 4 dp gap + «Офлайн» (`caption` 12/16, weight 500), text and icon `ui.on-surface-variant` (8.44:1 day, 7.27:1 night). Scales with the font scale (27 dp at 130 %, 38 dp at 200 %), **never truncated, never wraps internally** (one word). Never a touch target (no ripple, no click).
- **F8. Indicator placement (S2, S3, cards).** It sits **on the line of its anchor** and wraps to the next line as a whole when that line is full; it never covers the summary, a result name, the search field, «Эхлэх», «Маршрут гаргах» or R5 (checked). S3: after the duration · distance text of the summary (every option of the same answer shares the source, so the summary carries it for the selected option; in the expanded «Маршрут сонгох» group it appears once after the group heading). S2: a header row (about 36 dp at 100 %) above the first result (start-aligned, padding 8/16/4), **once per list** (NAV-023 AC 21); for «Илэрц олдсонгүй» after the text. Coordinate card: after the «Ойролцоох газар» label. Place card: after the type · address line. In the wide side column the indicator scrolls together with its anchor.
- **F9. Indicator in S5 guidance.** Icon only, 20 dp `offline_pin`, `ui.on-surface-variant`, after «25 мин · 12,4 км» on line 2 of the NAV-005 progress panel, 8 dp gap, vertically centred on the line; it follows the panel's 1.3 font cap. It adds **no height** to the panel at 360 dp (76 dp at 100 %, 128 dp at 130 %, same as without it). Never in the banner, the Then strip, the status area or on the map.

### Behaviour rules

- **B1. Offers wait for an idle S1.** O1 and O4 appear only on S1 with no sheet, card, dialog, keyboard, permission prompt or preview open, and never during guidance (AC 1, 27). If the condition comes true while the user is busy, the offer waits until they are back on idle S1 in the same foreground session. They never stack: O1 wins on a first launch; O4 never appears in the same session as O1.
- **B2. Failures surface where the user is.** S7 open → the O2 status row. S1 / S2 → an O6 message (and the O2 row when S7 is opened later in the same process). App in the background → a notification. Never a dialog, never on S3 or S5 (the old pack keeps working).
- **B3. Success is quiet.** A finished user-started download shows OF11 as a 3 s S1 message on S1 / S2, a notification in the background, and nothing on S3 / S5 / S7 (O2 changes to the installed state, which is the confirmation). Automatic updates show nothing (AC 22). *(S3 / S5 part: Open question 3.)*
- **B4. Landing on O2.** A tap on an offline notification (progress, waiting, ready, failed) or on an O6 message body opens S7 scrolled to O2 (F2), on top of whatever screen was open (S5 guidance continues underneath). The gear opens S7 at its top as today.
- **B5. Immediate feedback.** Every tap in O1 / O2 / O3 / O4 changes the visible state within 400 ms (Doherty): the status row appears at once with «Татаж байна… 0%» or «Wi-Fi холболт хүлээж байна», before the first byte. The first request follows within 5 s (AC 8).
- **B6. Focus after state changes (TalkBack).** After «Татах» focus moves to the status row; after «Цуцлах» or a failure to the next primary action («Монголын газрын зураг татах» or «Дахин оролдох»); after delete to «Монголын газрын зураг татах».
- **B7. Demo build.** The NAV-019 `demo` build type shows **no** O1, **no** O2 section, **no** O4 and sends no manifest request (0 requests, D184). The indicator never appears there (no on-device engine use is visible in the demo).

## Components

| Component | Source (Ferrostar / Material 3 / custom) | Notes |
|---|---|---|
| **O1 / O4 offer sheet** | Custom in-layout sheet with M3 bottom-sheet styling (not the window-level `ModalBottomSheet`, which would draw over R5) | A `Box` inside the map region: scrim (`ui.scrim`) + sheet aligned to the bottom of the region above R5. `BackHandler` = «Дараа». Swipe down on the handle and a tap on the scrim = «Дараа». Pane title = the heading. Traversal group so TalkBack stays inside while it is open. Test tags `offline-offer`, `offline-offer-download`, `offline-offer-later`, `offline-stale-offer`, `offline-stale-update`. |
| **O2 section** | M3 list styling inside S7 (NAV-005 / NAV-012) | Heading OF1; benefit line OF3 (no pack only); facts OF6 / OF7 with 20 dp icons `download` / `smartphone`; `FilledTonalButton` full width for OF2 / OF16 (wraps; 48 dp min); dates block OF19 + OF20 lines (`body-large`, tabular); OF28 (`body`); `TextButton` with `delete` icon in `ui.error` for OF21; caption «© OpenStreetMap contributors»; `TextButton` OF29 with `open_in_new` icon opens the manifest `licence.url` in the browser (`ACTION_VIEW`). Test tags `offline-section`, `offline-download`, `offline-update`, `offline-delete`, `offline-dates`, `offline-licence`. |
| **Status row** | Custom (shared by progress, waiting, checking, failed, storage) | 24 dp leading icon (`download`; `wifi` for waiting; `error` in `ui.error` for failures) + text (`body-large`, wraps, tabular figures) + M3 `LinearProgressIndicator` (determinate: `ui.primary` on `ui.primary-container`, 4.92:1 / 5.93:1; indeterminate while checking) + end-aligned `TextButton` («Цуцлах» or «Дахин оролдох»). Live region polite, throttled to one announcement per 10 s (AC 45). Test tag `offline-status`. |
| **O3 / O5 dialogs** | M3 `AlertDialog` (basic) | F5. O3: title OF13, buttons OF14, OF4. O5: title OF22, supporting OF28, buttons OF10, OF23 (error colour). Test tags `offline-mobile-data-dialog`, `offline-delete-dialog`. |
| **O6 S1 message** | NAV-002 / NAV-005 message card | OF11 with a `check_circle` icon, no action, 3 s. OF12 / OF15 with `error` icon, «Дахин оролдох» and «Хаах» (`ui.message-action`, 7.68:1 / 6.90:1). Test tag `offline-message`. |
| **O6 notifications** | `NotificationCompat`, channel id `offline_pack`, name OF1, `IMPORTANCE_LOW` | Progress: title OF1, text OF8, determinate progress, action OF10, ongoing, silent. Waiting: text OF9, action OF10. Checking: indeterminate progress, text OF8 at 100 % (Open question 1). Ready (user-started, app in background): title OF11, auto-cancel. Failed: title OF12, text OF15 when it was the storage case, action «Дахин оролдох». Small icon `download`. Content intent: S7 at O2 (B4). Not the guidance channel; lock-screen visibility public (no personal data). |
| **O7 indicator** | Custom `OfflineIndicator` (Surface + Icon + Text) | F7–F9. `semantics { contentDescription = OF25 }`, merged into the anchor's node (see Accessibility). Icon-only variant for the progress panel. |
| Progress panel, banner, S2, S3, cards | As NAV-005 / NAV-011 / NAV-018 | Only the indicator is added. No Ferrostar component is used or changed by this feature (Ferrostar `core` only, ADR-0009 §1); the progress panel is the custom `TripProgressPanel` of NAV-005. |

## States

### O1 first-launch offer and O4 14-day offer

| State | What the user sees | AC |
|---|---|---|
| First launch, Wi-Fi, manifest ok, S1 idle (`offer-first`) | O1: OF2 heading, OF3, OF6 «Татах хэмжээ: 120 МБ», OF7 «Утсанд шаардлагатай зай: 338 МБ», «Дараа» / «Татах» | 1 |
| First launch, no Wi-Fi / manifest failed / pack installed / flag set | No offer (S1 as today) | 2, 3 |
| Guidance running or S1 busy | No offer yet (B1) | 1 |
| 14-day conditions hold, S1 idle (`offer-14`) | O4: OF17, OF18 «Мобайл датагаар 33 МБ шинэчлэх үү?», the two OF20 lines for routing and search, «Дараа» / «Шинэчлэх» | 27 |
| After «Дараа» on O4 | Nothing for 7 × 24 h | 29 |

### O2 section (one primary action per state)

| State (prototype id) | Content | Primary action | AC |
|---|---|---|---|
| No pack, manifest known (`set-none`) | OF3, OF6, OF7 | «Монголын газрын зураг татах» | 4 |
| No pack, manifest unknown: offline or not fetched yet (`set-none-nomanifest`) | OF3 only (sizes appear in O3 or the storage check) | «Монголын газрын зураг татах» | 4 |
| Downloading (`set-downloading`) | Status row «Татаж байна… 42%» + bar; OF6 | «Цуцлах» | 12, 13 |
| Waiting for Wi-Fi (`set-waiting`) | Status row «Wi-Fi холболт хүлээж байна»; OF6 | «Цуцлах» | 9, 10 |
| Checking and installing (`set-verifying`) | Status row «Татаж байна… 100%» + indeterminate bar (term pending, Open question 1) | «Цуцлах» | 13, 16 |
| Failed, no pack (`set-failed`) | Error row «Газрын зургийг татаж чадсангүй»; OF6, OF7 | «Дахин оролдох» | 15, 17 |
| Not enough storage (`set-storage`) | Error row «Утсанд хангалттай зай алга. 45 МБ зай чөлөөлнө үү.»; OF6, OF7 | «Дахин оролдох» (re-checks the space) | 6, 7 |
| Installed, up to date (`set-installed`) | OF19 + OF20 lines, OF28, delete, attribution, OF29 | none (the section is informational; delete is secondary) | 4, 39 |
| Installed, files to fetch (`set-update`) | Dates, OF28, OF6 of the update «Татах хэмжээ: 33 МБ» | «Шинэчлэх» | 4, 26 |
| Installed, update running (`set-update-running`) | Dates, OF28, status row | «Цуцлах» | 12, 22 |
| Installed, update failed (`set-update-failed`) | Dates, OF28, error row OF12 | «Дахин оролдох» | 15 |
| Installed, pack too new for the app | Dates of the installed files, no «Шинэчлэх» | none | 24 |
| Deleted | Back to `set-none` (no extra message; focus on the button, B6) | «Монголын газрын зураг татах» | 36 |
| Deleted during guidance | `set-none` at once (files freed after guidance, AC 36) | same | 36 |

The installed lines show only installed kinds (AC 39): on a first install where routing was not offered (AC 24), «Маршрут: …» is absent.

### Dialogs, messages, notifications

| State (id) | What the user sees | AC |
|---|---|---|
| Mobile data at start (`dlg-mobile`) | O3 «Мобайл датагаар 120 МБ татах уу?» «Wi-Fi хүлээх» / «Татах» | 8, 9 |
| Delete confirmation (`dlg-delete`) | O5 «Офлайн газрын зургийг устгах уу?», «Эзэлж буй зай: 202 МБ», «Цуцлах» / «Устгах» | 36, 37 |
| Done, on S1 / S2 (`msg-ready`) | S1 message «Офлайн газрын зураг бэлэн боллоо», 3 s | 41 |
| Failed, on S1 / S2 (`msg-failed`) | S1 message «Газрын зургийг татаж чадсангүй» + «Дахин оролдох», «Хаах» | 15, 41 |
| Storage, on S1 / S2 (`msg-storage`) | S1 message OF15 + «Дахин оролдох», «Хаах» | 6, 7 |
| App in the background | Notifications (Components) | 12, 41 |
| Notification permission denied | No notification; the O2 row shows the progress | 41 |

### O7 indicator

| State (id) | Where | AC |
|---|---|---|
| Preview from the device, *k* = 1 (`ind-preview`) / *k* ≥ 2 (`ind-preview-k2`) | Chip after the duration · distance text; once after «Маршрут сонгох» when expanded | NAV-021 27 |
| Guidance on a device route (`ind-guidance`) | Icon after the remaining time and distance | NAV-021 28 |
| Results from the device (`ind-results`) | Chip in the list header row | NAV-023 21 |
| No result from the device (`ind-noresult`) | Chip after «Илэрц олдсонгүй» | NAV-023 21 |
| Coordinate card reverse from the device (`ind-card`) / place card | Chip after «Ойролцоох газар» / after the type line | NAV-023 22, 23 |
| Answer from the gateway, or a later gateway reroute | No indicator | NAV-021 27, 28; NAV-023 21 |

### The required state space, mapped

| Required state | Here |
|---|---|
| Default | `set-installed`; S2/S3 from the gateway without indicator |
| Loading | `set-downloading`, `set-verifying`; manifest fetch shows «Татаж байна… 0%» (B5) |
| Empty / no result | `set-none`; `ind-noresult` |
| Error | `set-failed`, `set-update-failed`, `msg-failed` |
| Offline | `set-waiting` (queued); O2 works without network for delete and dates; the indicator is the offline result itself |
| GPS lost / location permission denied | Not used by this feature (no location is read for packs, AC 42); the map behaves as NAV-005 |
| Permission denied | Notification permission: Dialogs table. No storage permission exists (AC 20) |
| Slow network | Progress moves slowly; «Цуцлах» always available; the app stays usable (background job) |
| First use | O1 |
| Static demo "unavailable" | B7: hidden in the NAV-019 demo build |
| Storage full | `set-storage`, `msg-storage` |

## Interactions
- **O1 «Татах»:** sheet closes (150 ms, `motion.duration-short`; reduced motion: instant) → F2 storage check → Wi-Fi: download; mobile data: cannot happen on O1 (O1 needs Wi-Fi), but if Wi-Fi was lost between show and tap, O3 asks (AC 8).
- **O1 «Дараа» / Back / swipe down / scrim tap:** sheet closes; flag stays set (AC 2).
- **O2 «Монголын газрын зураг татах» / «Шинэчлэх» / «Дахин оролдох»:** user-started download (F2); the button is replaced by the status row in place (no layout jump above it).
- **O2 «Цуцлах»:** stop within 5 s, back to the previous state; no confirmation (the download can be restarted, and resume keeps nothing after cancel by design, AC 13).
- **O2 «Офлайн газрын зургийг устгах»:** O5. **O5 «Устгах»:** F6. **O5 «Цуцлах» / Back / outside:** nothing.
- **O2 ODbL line:** opens the licence text in the browser; if no browser can handle it, nothing happens and the line stays (no error message invented).
- **O3 «Татах»:** download on any validated network until it ends. **«Wi-Fi хүлээх» / Back / outside:** queued (OF9).
- **O4 «Шинэчлэх»:** routing + search over mobile data, no second confirmation (AC 28); the S1 message reports the end (B3). **«Дараа» / Back / swipe / scrim:** 7-day pause (AC 29).
- **Notifications:** «Цуцлах» action = O2 «Цуцлах»; «Дахин оролдох» action restarts as user-started; tap = S7 at O2 (B4).
- **Indicator:** no interaction (not focusable on its own, no ripple).

## Copy (mn / en)

Every `mn` value is a glossary term (section 2.7, all `needs native review`; OF29 not translated) or a reused row. Key names are a proposal; the mobile engineer owns the final names. `{size}` = number + U+00A0 + OF26 / OF27 (size display rule); `{percent}` integer; `{date}` `YYYY-MM-DD`.

| Key | mn | en | Glossary |
|---|---|---|---|
| `offline_title` (S7 section, notification channel) | Офлайн газрын зураг | Offline map | OF1 |
| `offline_download_action` (O1 heading, O2 button) | Монголын газрын зураг татах | Download Mongolia map | OF2 |
| `offline_benefit` | Интернэт холболтгүй үед ч газрын зураг, маршрут, хайлт ажиллана | Map, routes and search work even without an internet connection | OF3 |
| `offline_download` | Татах | Download | OF4 |
| `offline_not_now` | Дараа | Not now | OF5 |
| `offline_download_size` | Татах хэмжээ: {size} | Download size: {size} | OF6 |
| `offline_space_needed` | Утсанд шаардлагатай зай: {size} | Space needed on phone: {size} | OF7 |
| `offline_downloading` | Татаж байна… {percent}% | Downloading… {percent}% | OF8 |
| `offline_waiting_wifi` | Wi-Fi холболт хүлээж байна | Waiting for Wi-Fi | OF9 |
| `offline_cancel` | Цуцлах | Cancel | OF10 |
| `offline_ready` | Офлайн газрын зураг бэлэн боллоо | Offline map is ready | OF11 |
| `offline_failed` | Газрын зургийг татаж чадсангүй | The map could not be downloaded | OF12 |
| `offline_mobile_data_question` | Мобайл датагаар {size} татах уу? | Download {size} using mobile data? | OF13 |
| `offline_wait_wifi` | Wi-Fi хүлээх | Wait for Wi-Fi | OF14 |
| `offline_no_space` | Утсанд хангалттай зай алга. {size} зай чөлөөлнө үү. | Not enough space on the phone. Free up {size}. | OF15 |
| `offline_update` | Шинэчлэх | Update | OF16 |
| `offline_stale_title` | Маршрут, хайлтын мэдээлэл хуучирсан байна | Route and search data are out of date | OF17 |
| `offline_stale_question` | Мобайл датагаар {size} шинэчлэх үү? | Update {size} using mobile data? | OF18 |
| `offline_data_date` | Мэдээллийн огноо | Data date | OF19 |
| `offline_date_map` / `_routes` / `_search` | Газрын зураг: {date} / Маршрут: {date} / Хайлт: {date} | Map: {date} / Routes: {date} / Search: {date} | OF20 |
| `offline_delete` | Офлайн газрын зургийг устгах | Delete offline map | OF21 |
| `offline_delete_question` | Офлайн газрын зургийг устгах уу? | Delete the offline map? | OF22 |
| `offline_delete_confirm` | Устгах | Delete | OF23 |
| `offline_indicator` | Офлайн | Offline | OF24 |
| `offline_indicator_a11y` | Офлайн газрын зургаас | From the offline map | OF25 |
| `unit_mb` / `unit_gb` | МБ / ГБ | MB / GB | OF26 / OF27 |
| `offline_space_used` | Эзэлж буй зай: {size} | Space used: {size} | OF28 |
| `offline_licence` | Open Database License (ODbL) 1.0 | Open Database License (ODbL) 1.0 | OF29 |
| `offline_checking` (status while checking and installing) | **missing: requested from the BA** (until then `offline_downloading` at 100 %) | **missing** | Open question 1 |
| Reused | Дахин оролдох · Хаах · Тохиргоо · © OpenStreetMap contributors · Илэрц олдсонгүй · Ойролцоох газар · {count} илэрц олдлоо | Try again · Close · Settings · © OpenStreetMap contributors · No results found · Nearest place · {count} results | existing rows |

Truncation: measured with the Mongolian strings at 100 / 130 / 200 % on 320–412 dp; nothing is truncated or cut mid-word (checker rule "text overflows", all states); the longest O2 line (OF15 with «45 МБ») wraps to two or three lines. «Wi-Fi» stays in Latin letters and is never spoken.

## Accessibility
- **Contrast** (`check-contrast.mjs`, 163 pairs pass): body and facts text `ui.on-surface` / `ui.on-surface-variant` on `ui.surface`; tonal buttons 12.57:1 / 7.85:1; filled «Татах» 6.39:1 / 7.50:1; «Устгах» and delete `ui.error` 6.54:1 / 8.81:1 (new day pair at 4.5); indicator 8.44:1 / 7.27:1 (AC 45, NAV-021 AC 27, NAV-023 AC 24); progress bar 4.92:1 / 5.93:1 against its track (non-text 3:1); message actions 7.68:1 / 6.90:1.
- **Targets:** every control ≥ 48 × 48 dp (checker, all states); the O1 / O4 buttons ≥ 16 dp apart, dialog actions ≥ 8 dp; the indicator is not a target.
- **Dynamic type:** all text in `sp`, uncapped everywhere except the S5 progress panel (NAV-005 cap 1.3, so the icon follows it). Measured at 100 / 130 / 200 %.
- **TalkBack names and order:**
  - O1: pane title = heading; order heading → OF3 → OF6 → OF7 → «Дараа» → «Татах». O4 likewise. Back closes.
  - O2: heading OF1 is a heading node; the status row is one node «Татаж байна… 42%» with the progress bar's range info, followed by «Цуцлах»; progress is announced at most every **10 s** (AC 45), and the final «Офлайн газрын зураг бэлэн боллоо» once. Dates are one node per line. The ODbL button says OF29 and has the role "link".
  - Dialogs: title is announced on open; focus starts on the title.
  - Indicator: not separately focusable. S3: the summary node reads «18 мин, 7,4 км, Хүрэх цаг 14:08, Офлайн газрын зургаас» (NAV-021 AC 27). S2: after results render the announcement is «{count} илэрц олдлоо» then «Офлайн газрын зургаас» (NAV-023 AC 21). S5: the progress node gains «, Офлайн газрын зургаас» at the end, and the change is **not** a live announcement except once when a route from the device starts (NAV-021 AC 28). Cards: appended to «Ойролцоох газар» / the type line.
- **Colour is never the only signal:** failures have the `error` glyph and the words; the indicator has the glyph and (outside guidance) the word; the progress bar has the percentage text.
- **Reduced motion:** sheet and row transitions are instant; the indeterminate bar becomes a static 30 % segment with the text unchanged.

## Design rationale

| UX law / heuristic | How this screen applies it | Deliberate trade-off |
|---|---|---|
| **Fitts's law** | O1 / O4 buttons pinned at the bottom in thumb reach (primary centre at 83 % of the height at 360×640, 88 % at 412×915), filled primary at the end edge, 16 dp from «Дараа»; O2 primary actions full width; «Устгах» separated from «Шинэчлэх» by a divider and the dates | The centred dialog (alternative 1b) would put the primary at 66 % |
| **Hick's law** | Two buttons per offer and dialog; **one primary action per O2 state** (table); no regions, no storage choice, no pause (Out of scope; Open question 2 of the story) | Pause is not offered: cancel + automatic resume covers the cases with one fewer choice |
| **Miller's law** | O2 chunks: status → sizes → action → dates → delete → licence; data dates as three short lines, not a sentence | — |
| **Jakob's law** | Bottom-sheet offer and M3 dialogs; progress in a low-importance notification with «Цуцлах» like Play Store / Google Maps downloads; `offline_pin` glyph for downloaded content | The pack lives in S7 rather than a separate "Offline maps" page (alternative 2b) because there is one pack and AC 4 wants the state in S7 |
| **Doherty threshold** | B5: state change under 400 ms on every tap; progress ≤ 2 s (AC 12); no frozen screen (all work in the background) | — |
| **Gestalt** | One status-row component for five states (similarity); the indicator sits on its anchor's line (proximity); O2 is one region with its own heading (common region) | — |
| **Von Restorff** | One emphasised element per surface: the filled primary in O1 / O4; the selected route stays the standout in S3, the indicator is neutral grey | Neutral colour is less noticeable than amber; chosen because offline data is not an error (alternative 5) |
| **Serial position** | O2 is the last S7 section (remembered, out of the way of everyday toggles); within O2 the status comes first; in S2 the indicator is at the top of the list, before the first result | From the gear the user scrolls 527 dp to reach O2; every pack-related path lands there directly (B4) |
| **Tesler's law** | The system chooses Wi-Fi, schedules updates, checks space, resumes, verifies and switches files; the user decides only on mobile data and delete | — |
| **Postel's law** | Any validated network type is accepted after O3; a metered hotspot is treated safely as mobile data | — |
| **Peak–end rule** | The end of the download is a clear «Офлайн газрын зураг бэлэн боллоо» and visible data dates; a failure always offers «Дахин оролдох» and says how much space to free | — |
| **Goal-gradient** | Percentage and bar; the notification shows progress while the user does other things | — |
| **Zeigarnik effect** | A waiting or failed download stays visible in O2 and in the notification until it ends or is cancelled; a killed download resumes, never restarts | — |
| **Aesthetic–usability / prägnanz** | Few colours (primary, neutral, error), generous spacing, no illustrations beyond one 40 dp icon | — |
| **Cognitive load (measured)** | Taps from first launch to a running download: **1** («Татах» in O1); from S1 via Settings: **2** (gear, button) + one scroll; words on the S5 indicator: **0** | — |

Nielsen heuristics checked: **visibility of system status** (status row, notification, dates, the indicator); **match with the real world** (glossary terms; sizes in МБ with the decimal comma; dates as digits); **user control and freedom** («Дараа», «Цуцлах», Back everywhere; delete confirmable and cancellable); **consistency and standards** (M3 sheet, dialogs, NAV-002 messages, NAV-005 tokens); **error prevention** (space checked before any request, mobile data never used without O3 or O4, delete confirmed); **recognition over recall** (the labelled chip teaches the glyph used alone in guidance; sizes shown before every mobile-data choice); **flexibility** (automatic for novices, «Шинэчлэх» for impatient experts); **minimalist design** (one primary action per state); **recover from errors** (what happened and what to do: «… 45 МБ зай чөлөөлнө үү.», «Дахин оролдох»); **help** (OF3 explains the benefit inline). Design changes the review and the checker forced: the guidance indicator became icon-only (measured +26 dp panel at 360 dp with the label); the S1 message got the NAV-005 D5 cap in the prototype after the wide-font stress test; sizes use a no-break space after «120» broke from «МБ» in the O3 title at 360 dp; the installed section order moved «Эзэлж буй зай» next to the dates.

## Known limitations
1. **Checking / installing copy** is pending a BA glossary term; until then the status row keeps «Татаж байна… 100%» with an indeterminate bar (Open question 1).
2. **320×568 at 200 %** (outside the design target, D66): after the scroll to O2 the primary action of the update states needs one more scroll; S1 messages scroll inside the top group; the S3 sheet's «Эхлэх» meets R5 (as NAV-018).
3. **Landscape 640×360 at 200 %:** the side column scrolls; the indicator scrolls together with its anchor and can be cut at the column edge (INFO in the checker).
4. **Wide-font stress test** (`--wide`): one state, `msg-storage` mn at 360×640 / 200 %, scrolls inside the top group (NAV-005 D5); everything else passes.
5. **Demo build:** O1, O2 and O4 are hidden (B7); this needs a line in NAV-022 (request to the BA).
6. **Notifications** are system-drawn (Android 12+ templates); their content is specified here but not prototyped. The S7 sheet from the gear opens at its top; the prototype always shows the scrolled-to-O2 position.
7. The place card is described (F8) but not drawn in the prototype; the coordinate card stands in for both cards.

## Open questions
1. **Term for "checking and installing"** (after 100 % until the files are active; can take tens of seconds on a low-end phone). Requested from the BA (`en` proposal "Installing…" or "Checking the map…"; the BA writes the Mongolian). Working assumption: «Татаж байна… 100%» + indeterminate bar.
2. **O4 while the phone moves.** Options: (a) O4 waits until the device is still (speed below the NAV-011 typing-lock threshold); (b) as AC 27 (only "not during guidance"). *Recommendation: (a).* A taxi driver browsing the map while driving should not get a two-button sheet; the condition costs nothing (the offer comes at the next stop).
3. **Where OF11 shows in the foreground.** AC 41 says an in-app message for 3 s. Options: (a) only on S1 / S2; on S3, S5 and S7 nothing (O2 changes state; B3); (b) everywhere, including over guidance. *Recommendation: (a).* No unasked text during guidance; S7 already shows the result.
4. **Delete while a download runs.** Options: (a) delete stays available; confirming cancels the download first, then deletes; (b) delete is hidden while a download runs. *Recommendation: (a)* (the user stays in control; one rule).
5. **Indicator in guidance: icon only** (measured, alternative 4). NAV-021 AC 28 says "the indicator OF24 is visible". Options: (a) icon with the OF25 name (this spec); (b) the labelled chip, costing 26–29 dp of map band at 360 dp. *Recommendation: (a).* Needs the AC wording from the BA.
6. **One indicator per answer on S3.** NAV-021 AC 27 says "each on-device route option shows the indicator". All options of one answer share one source, so this spec shows the chip once on the summary (and once after the «Маршрут сонгох» heading). *Recommendation:* the BA rewords AC 27 accordingly.
7. **Offer timing vs AC 1's 10 s.** B1 can delay O1 past 10 s if the user is already searching. *Recommendation:* AC 1 reads "within 10 s, or as soon as S1 is next idle in that session".

## AC traceability

| AC | Where |
|---|---|
| NAV-022 1–3 | O1, B1, F3–F4; flow F1 |
| 4 | O2 (F2, States) |
| 5 | Copy (size rule, no-break space) |
| 6, 7 | `set-storage`, `msg-storage`; flow F2, F3 |
| 8–11 | O3, `set-waiting`; flow F2 |
| 12–15 | Status row, notifications; flow F3 |
| 16–18 | `set-verifying`, failures (Open question 1); flow F3 |
| 19 | Flow F3 (map switch keeps the camera; no UI) |
| 20 | States › permission (no storage permission) |
| 21–25 | Flow F4 (no UI except O2 dates and «Шинэчлэх») |
| 26 | `set-update`, `set-update-running` |
| 27–30 | O4, B1; flow F5 |
| 31–35 | Map unchanged; F1 attribution; NAV-005 spec |
| 36–38 | O5, `set-none` after delete; flow F6 |
| 39 | O2 dates |
| 40 | F9 / O7: never on the map |
| 41 | O6, B2, B3 (Open question 3) |
| 42, 43 | No coordinates or identifiers in any UI state; nothing stored by the UI beyond the flag and the decline time |
| 44 | Copy table (glossary rows) |
| 45 | Accessibility; checker |
| NAV-021 27, 28 | O7, F8, F9 (Open questions 5, 6) |
| NAV-023 21–24 | O7, F8 |

## Evidence
- **Prototype:** `docs/design/prototypes/NAV-022-offline-pack.html`, 24 states: 11 O2 states, 2 offers, 2 dialogs, 3 S1 messages, 6 indicator placements.
- **Layout checker:** `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav022.mjs` → **1152 combinations** (4 viewports 360×640, 412×915, 320×568, 640×360 × 24 states × day/night × mn/en × font scale 1.0/1.3/2.0), **0 problems**, 80 INFO outside the design target (320×568 above 100 %, and scroll-on cases at 200 % listed in Known limitations 2–3). Rules: attribution uncovered (and the offer scrim ends above it), no overlaps, 48 dp targets, labels not clipped, no text overflow, O2 heading + primary visible after the scroll to the section at ≤ 130 % on ≥ 360×640, offer buttons pinned and ≥ 8 dp apart, dialogs ≥ 16 dp from the edges with both actions visible, S1 message visible, indicator visible with its anchor and never overlapping it, «Эхлэх» / «Маршрут гаргах» visible, NAV-018 Q3 cap and map band ≥ 160 / 96 dp, first result visible with the keyboard at ≤ 130 %, guidance instruction ≤ 3 lines, band ≥ 150 / 80 dp, puck uncovered.
  - `--wide` (much wider font): 1152 combinations, **2 problems** (Known limitations 4).
  - `--light` (day theme only, as the task allowed): 576 combinations, 0 problems.
  - `--v=b --light` (offer as a centred dialog, indicator icon-only, guidance with the labelled chip) and `--v=c --light` (full-width indicator row): measurements in Alternatives 1 and 4 (576 combinations each); `v=b` passes every rule (it is worse on the measured values, not broken), `v=c` fails 9 checks (guidance band 18 dp at 200 %, controls over the banner, chip over R5 in landscape, first result hidden by the keyboard at 320×568).
  - Measured values used above: offer primary centre 83 / 82 / 79 % of the height at 360×640 (100 / 130 / 200 %), 88 / 87 / 86 % at 412×915; offer content scrolls only at 200 % on 360×640 and 320×568; O2 starts 527 / 547 / 642 dp down the S7 content; indicator height 24 / 27 / 38 dp; S3 collapsed 312 / 327 / 388 dp and band 224 / 204 / 132 dp at 360×640; S5 panel 76 / 128 dp and band 286 / 173 / 158 dp at 360×640.
- **Contrast:** `node docs/design/prototypes/check-contrast.mjs` → all checks passed (163 pairs, flavor parity 90 + 5 keys, 86 map-style values).
- **Timing budgets:** AC 1 (10 s), 8 (5 s), 9 (30 s), 12 (2 s), 13 (5 s), 19 (5 s), 22 (5 s), 36 (5 s) and B5 (400 ms) are behaviour; delegated to the mobile engineer's tests and QA (AC 46).

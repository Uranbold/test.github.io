# Screen: Android background and lock-screen navigation (rich notification, lock screen, restore, battery hint, automatic theme)

- **Stories:** NAV-012 (AC 1–52; UI-relevant AC 1–30, 34, 40–44, 46). Traceability per AC at the end.
- **Platforms:** **Android only** (Kotlin, Jetpack Compose, Material 3, `NotificationCompat`). iOS is NAV-015.
- **Delta on:** [`screens/NAV-005-android-navigation.md`](NAV-005-android-navigation.md) (S3, S5, S6, S7, S8) and the NAV-011 collapsed route-preview sheet ([`flows/NAV-011-…`](../flows/NAV-011-android-route-preview-search-parity.md) F3). Everything not named here stays as those specs say. NAV-005 itself is not edited (mid-flight rule); QA adapts the NAV-005 tests listed in the story Context table.
- **Flow:** [`flows/NAV-012-android-background-lock-screen.md`](../flows/NAV-012-android-background-lock-screen.md) (F1–F10).
- **Rules:** [`navigation-ux.md` §12](../navigation-ux.md) owns the timing and voice rules of this story (notification cadence, restore prompt, call catch-up, output changes); §9 and [`map-style.md` §8](../map-style.md) own the «Автомат» theme. This spec owns layout, components, states, interactions and copy.
- **Prototype:** [`prototypes/NAV-012-background.html`](../prototypes/NAV-012-background.html) (1 CSS px = 1 dp): lock-screen guidance, restoring, restored (incl. the worst case), GPS lost while restoring, arrival over the lock screen, route preview with the battery entry row (collapsed) and the hint (expanded), settings with the battery row (restricted / exempt), and 8 notification states. Hash example: `#state=restoring&theme=night&lang=mn&scale=1.3`. Checked by `prototypes/check-layout-nav012.mjs` (Evidence). No Figma file.
- **Tokens:** [`tokens.json`](../tokens.json) v0.6.0 adds two durations: `motion.nav-resumed-notice` (3 s) and `motion.theme-auto-hold` (10 min). No new colours: every surface reuses NAV-005 tokens and contrast pairs.
- **Architecture:** no API change. Design questions B-A1–B-A8 go to the architect (ADR-0009 amendment or a new ADR). Where this spec depends on an answer it says so (B-A1 restore without a request, B-A2 silent restart, B-A5 notification template, B-A7 lock-screen flags).

## Purpose
Keep guidance visible, audible and recoverable when the driver is not looking at the app: in the notification, on the lock screen, after a swipe-away, after the phone kills the app, through the car stereo and around phone calls, and switch the map to night at sunset without a tap.

## Context and goal
| Surface | Primary task | Moment of use and time budget |
|---|---|---|
| **N1** ongoing notification | Read the next turn without opening the app | Driving with the phone locked or another app open; **one glance < 1 s**. Pedestrian: phone taken out of a pocket, ≤ 2 s |
| **N2** «Замчлал тасарлаа» notification | Get guidance back after the phone closed the app | Stopped or a passenger; a few seconds; one tap |
| **L1** guidance over the lock screen | Same as S5 without unlocking | Driving; one glance; taps only on 48 dp controls |
| **R1** restoring / restored guidance | Trust that guidance came back to the same destination | Driving; the screen must not ask anything; ≤ 3 s to guidance |
| **H1** battery hint on the route preview | Learn that the phone may stop navigation, and fix it once | Planning before «Эхлэх» (stationary or a passenger); up to a minute; never while moving in guidance |
| **H2** «Батарейн хязгаарлалт» row in S7 | Find the fix again later | Planning; seconds |
| **T1** «Автомат» theme | Nothing: the map turns dark at sunset and light at sunrise | Any; zero interaction |

Calls, Bluetooth routing, volume keys and swipe-away have **no new UI**: the design is the absence of surprises (F3, F8, F9).

### Alternatives considered
| Decision | Options explored | Chosen and why |
|---|---|---|
| **Notification layout** (N1) | (a) Standard template + `BigTextStyle`: title = distance, text = instruction, expanded adds street and «Хүрэх цаг»; manoeuvre as large icon. (b) Custom `RemoteViews` (`DecoratedCustomViewStyle`) copying the banner. (c) NAV-005 S8: title = instruction, text = distance · street | **(a).** Google Maps / Waze pattern (Jakob). The distance is short, so the title never truncates (measured: 0 clipped distance titles in 144 renders); the instruction gets the text line. States without a distance use a short status as the title, as AC 2 asks («Маршрутыг дахин тооцоолж байна», «GPS дохио тасарлаа», «Ачаалж байна…»); only the arrival sentence goes in the text line, because it wraps there in the expanded card. The system draws it, so OEM skins, the dark shade, font scale and TalkBack work without our code. (b) has a 48 dp collapsed height limit on Android 12+ and renders differently per OEM. (c) puts the longest string in the boldest single line: at 360 dp the 16 sp title holds fewer characters than the 14 sp text line (the 30-character recalculating title was already shortened in the wireframe), so long instructions lose more of their end, and the distance moves to the smaller second line |
| **Restore entry** (R1) | (a) Resume automatically with the «Замчлал сэргэлээ» notice. (b) Ask first with «Үргэлжлүүлэх» / «Дуусгах». (c) Resume silently | **(a)**, story working assumption (Open question 5). No question while the driver may be moving; «Дуусгах» is one tap away. (c) would leave the driver unsure why guidance is on |
| **Banner before the first fix after a restore** | (a) Neutral banner «Ачаалж байна…» and a progress skeleton, camera on the stored route, no puck. (b) Show the stored route's first manoeuvre. (c) Map screen with a spinner until a fix | **(a).** (b) can show a turn the driver has already passed (dangerous). (c) loses the "guidance is back" context (Zeigarnik) and needs a second screen change. (a) reuses the grey "system working" banner variant, so it reads like recalculating (similarity) |
| **Restore notice placement** | (a) Status area RS (same component as «GPS дохио сэргэлээ»). (b) Snackbar at the bottom. (c) Second line of the banner | **(a).** (b) covers the progress panel and «Дуусгах». (c) mixes a status with the instruction. RS already holds short timed statuses |
| **Battery hint placement** (H1) | (a) Full hint in the NAV-011 collapsed sheet after the summary. (b) Floating card at the top of the map. (c) Dialog after «Эхлэх». (d) Message during guidance. (e) **One-line entry row** «Батарейн хязгаарлалт» in the collapsed sheet; the full hint first in the expanded part (progressive disclosure) | **(e).** Measured on 360×640 at 100 %: (a) needs about 128 dp more than NAV-011's 60 % cap allows, leaving about 100 dp of map instead of the ≥ 160 dp of NAV-011 P3 (wireframe run 3: 112 dp with the hint already scrolling), on **every** preview until the user dismisses it (many users never will), which also breaks tapping alternative lines (NAV-011 AC 17). (b) costs the same height as (a) above the sheet (a ≈ 130 dp card) and breaks NAV-005 Layout rule 7 (preview messages live in the sheet); with the NAV-005 sheet it left < 20 dp of map in the first wireframe run. (c) blocks «Эхлэх» (AC 26 forbids). (d) forbidden and unsafe. (e) keeps NAV-011 P2/P3 intact (sheet 59 %, 206 dp of map) and the full B2 text is one tap away. Trade-off: B2 is not on screen until the row is tapped or the sheet dragged up, so AC 26 is met in the expanded sheet (open question in the handoff) |
| **Full hint shape** (expanded) | (a) Text full width, icon and actions in one bottom row. (b) Icon column + text + actions row (NAV-005 message card) | **(a)**: 128 dp instead of 164 dp at 360 dp (measured), so it fits the expanded sheet at 100 % without scrolling |
| **Lock-screen view** (L1) | (a) The full S5 over the lock screen, unchanged. (b) A reduced lock-screen card (banner only). (c) Notification only | **(a).** AC 8 asks for banner, map, progress and controls; one layout to learn (consistency), Google Maps behaves this way. (b) is a second guidance layout to build and test |
| **«Тохиргоо» over the lock screen** | (a) Visible; tapping asks to unlock first. (b) Hidden over the lock screen | **(a)**: the layout does not jump when the phone locks; AC 9 names the unlock prompt for it |
| **S7 battery row** (H2) | (a) M3 list item: icon, headline B1, supporting text B2 (while restricted), text button «Тохиргоо нээх» below. (b) Trailing button on the same line. (c) Whole row tappable without a button | **(a).** In (b) B2 wraps to 8+ lines in the narrow text column at 360 dp. (c) hides the action (recognition over recall) and AC 28 names the button |

## Surfaces
| ID | Surface | Pattern | Main AC |
|---|---|---|---|
| N1 | Ongoing guidance notification (replaces NAV-005 S8 content) | `NotificationCompat` standard template, `BigTextStyle`, channel «Замчлал» | 1–7, 12 |
| N2 | Interrupted notification «Замчлал тасарлаа» | Standard template, channel «Замчлал» | 22 |
| L1 | S5 guidance and S6 arrival over the lock screen | NAV-005 S5/S6 layout, shown when locked | 8–12, 34 |
| R1 | S5 restoring and restored states | NAV-005 S5 regions with new state content | 18–20, 23 |
| H1 | Battery entry row (collapsed) and battery hint (expanded) in the route preview sheet | NAV-011 sheet: list row + inline card | 26, 27, 30 |
| H2 | «Батарейн хязгаарлалт» row in S7 | M3 list item with action | 27, 28 |
| T1 | «Автомат» theme option (meaning only) | NAV-005 S7 radio row | 40–44 |

## Layout

### N1 notification (360 dp phone, Android 12+, mn, day; approximate system template)
```
Collapsed (lock screen, shade below the top)           Expanded (top of the shade)
┌──────────────────────────────────────────────┐       ┌──────────────────────────────────────────────┐
│ (▲) 300 м                                ┌──┐│       │ (▲) Газрын зураг · Хүрэх цаг 14:35        ┌──┐│
│     Баруун талын гарах зам руу эр…       │↗ ││       │     300 м                                │↗ ││
│                                          └──┘│       │     Баруун талын гарах зам руу эргэнэ үү └──┘│
└──────────────────────────────────────────────┘       │     Энхтайвны өргөн чөлөө                    │
                                                       │     [ Дууг хаах ]  [ Дуусгах ]               │
(▲) = monochrome navigation arrow (small icon)         └──────────────────────────────────────────────┘
↗   = manoeuvre icon, large icon (bitmap of the NAV-005 banner drawable)
Card colour: nav.banner (colourised foreground-service notification), text by the system.
```

### N2 interrupted notification
```
┌──────────────────────────────────────────────┐
│ (▲) Газрын зураг                             │   not colourised (it is not live guidance)
│     Замчлал тасарлаа                         │   dismissable; disappears when the restore window ends
│     Үргэлжлүүлэхийн тулд дарна уу            │   tap = «Үргэлжлүүлэх»
│     [ Үргэлжлүүлэх ]  [ Дуусгах ]            │
└──────────────────────────────────────────────┘
```

### R1 restoring and restored (portrait 360×640 dp) and L1
```
R1 restoring (no fix yet)             R1 restored (on route)               L1 arrival over the lock screen
┌──────────────────────────────────┐  ┌──────────────────────────────────┐ ┌──────────────── 🔒 ──────────────┐
│┌────────────────────────────────┐│  │┌────────────────────────────────┐│ │┌────────────────────────────────┐│
││ ⌖   Ачаалж байна…  (grey)      ││  ││┌────┐ 300 м                    ││ ││┌────┐ Таны очих газар баруун   ││
│└────────────────────────────────┘│  │││ ↗  │ Баруун талын гарах       ││ │││ ⚑  │ талд байна               ││
│ ┌──────────────────────────────┐ │  ││└────┘ зам руу эргэнэ үү        ││ ││└────┘ Энхтайвны өргөн чөлөө    ││
│ │ ○ Замчлал сэргэлээ   (3 s)   │ │  ││       Энхтайвны өргөн чөлөө    ││ │└────────────────────────────────┘│
│ └──────────────────────────────┘ │  │└────────────────────────────────┘│ │                                  │
│   (map: stored route, no puck)   │  │ ┌──────────────────────────────┐ │ │           (map) ⚑▲               │
│                                  │  │ │ ○ Замчлал сэргэлээ   (3 s)   │ │ │                                  │
│                      [🔊] [⬆]   │  │ └──────────────────────────────┘ │ │┌────────────────────────────────┐│
│┌────────────────────────────────┐│  │              ▲                   │ ││ Зайсан толгой          [ Хаах ]││
││ ▭▭▭▭▭▭▭▭▭▭        [⚙]  (✕)   ││  │                      [🔊] [⬆]   │ │└────────────────────────────────┘│
││ ▭▭▭▭▭▭                         ││  │┌────────────────────────────────┐│ │                                  │
│└────────────────────────────────┘│  ││ Хүрэх цаг 14:35        [⚙] (✕)││ │                                  │
├──────────────────────────────────┤  │└────────────────────────────────┘│ ├──────────────────────────────────┤
│ © OpenStreetMap contributors     │  ├──────────────────────────────────┤ │ © OpenStreetMap contributors     │
└──────────────────────────────────┘  │ © OpenStreetMap contributors     │ └──────────────────────────────────┘
                                      └──────────────────────────────────┘
```
L1 guidance is S5 exactly; the only difference is the system lock glyph in the status bar (drawn by the OS, not by us).

### H1 battery entry row and hint in the NAV-011 preview sheet, H2 S7 battery row
```
H1 collapsed (360×640, mn, 100 %)        H1 expanded (after a tap on the row)      H2 S7 «Тохиргоо» (scrolled to the end)
┌──────────────────────────────────┐     ┌──────────────────────────────────┐     ┌──────────────────────────────────┐
│  (map, routes fitted above the   │     │  (map)                           │     │ ...                              │
│   collapsed sheet, 206 dp)       │     │┌────────────────────────────────┐│     │ Дуут заавар                 (●)  │
│                                  │     ││              ───               ││     │──────────────────────────────────│
│┌────────────────────────────────┐│     ││ Маршрут харах              [✕] ││     │ ▯  Батарейн хязгаарлалт          │
││              ───               ││     ││ Зайсан толгой                  ││     │    Батарей хэмнэх тохиргоо        │
││ Маршрут харах              [✕] ││     ││ [Машин] [Явган] [Дугуй]        ││     │    замчлалыг зогсоож болзошгүй.   │
││ Зайсан толгой                  ││     ││ Маршрут 1                      ││     │    Утасны тохиргоонд батарейн     │
││ [Машин] [Явган] [Дугуй]        ││     ││ 13 мин · 4,6 км                ││     │    хязгаарлалтыг унтраана уу.     │
││ Маршрут 1                      ││     ││ Хүрэх цаг 14:03                ││     │    [Тохиргоо нээх]                │
││ 13 мин · 4,6 км                ││     ││┌──────────────────────────────┐││     │                                  │
││ Хүрэх цаг 14:03                ││     │││Батарей хэмнэх тохиргоо       │││     │ (exempt: no supporting text,     │
││┌──────────────────────────────┐││     │││замчлалыг зогсоож болзошгүй.  │││     │  neutral battery icon)           │
│││ ▯! Батарейн хязгаарлалт    ⌃ │││     │││Утасны тохиргоонд батарейн    │││     └──────────────────────────────────┘
││└──────────────────────────────┘││     │││хязгаарлалтыг унтраана уу.    │││
││ [▶          Эхлэх            ] ││     │││ ▯          Хаах  Тохиргоо нээх│││
│└────────────────────────────────┘│     ││└──────────────────────────────┘││
├──────────────────────────────────┤     ││ (NAV-011 lower part: routes,   ││
│ © OpenStreetMap contributors     │     ││  avoid switch, points … scroll)││
└──────────────────────────────────┘     ││ [▶          Эхлэх            ] ││
                                         │└────────────────────────────────┘│
                                         │ © OpenStreetMap contributors     │
                                         └──────────────────────────────────┘
```
Wide windows (NAV-011 P5 side sheet, always expanded): no entry row; the full hint is the first item after the summary in the side sheet's scroll.

### Layout rules (new; NAV-005 Layout rules 1–7 apply unchanged)
8. **Status-area priority** (RS, max 2 messages, NAV-005 Layout rule 4): (1) «GPS дохио тасарлаа» / «GPS дохио сэргэлээ», (2) «Замчлал сэргэлээ», (3) «Интернэт холболт алга». If two messages would leave less than the minimum map band (150 dp at font scale 100 %, 80 dp above), only the higher-priority one shows. Measured: on 360×640 the restored worst case (Then strip + «Замчлал сэргэлээ» + offline + recenter) shows one message (band 204 dp at 100 %); on 412×915 both show.
9. **Restoring has no puck and no recenter**: there is no position yet. The camera fits the stored route into the uncovered map area (padding = NAV-005 Layout rule 2). On the first good fix the camera switches to the follow camera within 1 s (NAV-005 §8 easing).
10. **Battery hint in the NAV-011 sheet; NAV-011 P1–P5 stay intact.** Collapsed: a one-line **entry row** (48 dp, inside the summary region, after «Хүрэх цаг», before the pinned «Эхлэх») — it belongs to the part that never scrolls (P2), so at large font scales the top part gives way first, as NAV-011 designs. With the row, the collapsed sheet must still meet P3 (≤ 60 % of the area above R5; map ≥ 160 dp at 100 % and ≥ 96 dp above on ≥ 360×640; header and tabs not scrolling at 100 %). Expanded: the **full hint** is the first item of the lower part (before «Маршрут сонгох», the avoid switch and the points block) and is fully visible at 100 % on 360×640 without scrolling. Wide windows: the full hint follows the summary in the side sheet. Tapping the row expands the sheet (NAV-011 P4 drag animation, 250 ms) with the hint in view; TalkBack users get the expanded sheet anyway (NAV-011 P4).
11. **Nothing of the app other than S5/S6 is ever drawn over the lock screen.** Sheets (S7) close when the screen turns off. Over the lock screen, any action that would show another app screen goes through the OS unlock prompt first (B-A7).
12. **The notification is never the only place a state is shown**: every N1 state equals the banner state of the same moment (AC 2), so the screen, the lock screen and the notification never disagree.

## Components

### Ferrostar: reuse and customise
Unchanged from NAV-005 ([ADR-0009](../../architecture/adr/0009-android-guidance-client.md) §1): only Ferrostar's `core` is used. Every surface in this spec is ours. B-A1 asks whether a stored route can be fed back into the Ferrostar navigator for a restore with 0 route requests; the screens are the same either way (the restoring state just lasts until the route request returns when B-A1 says no).

### Component specs
| Component | Source | Spec |
|---|---|---|
| **N1 ongoing notification** | `NotificationCompat`, channel «Замчлал» (importance LOW, no badge), category `NAVIGATION`, foreground service | **Small icon:** monochrome navigation arrow (unchanged, AC 1). **Large icon:** the NAV-005 banner manoeuvre drawable for the next manoeuvre, rendered to a bitmap at 48 dp × density, `nav.on-banner` stroke on transparent (B-A5). **Title:** distance in the NAV-004 AC 23 format («300 м», «1,2 км»), tabular figures. **Text:** the banner instruction (NAV-005 section E text; first letter upper case). **Big text (expanded):** instruction + line break + street (street omitted when empty). **Sub-text (header):** «Хүрэх цаг 14:35» (+ « +1 өдөр»). **Actions (expanded only, system behaviour):** voice toggle «Дууг хаах» / «Дууг нээх» first, «Дуусгах» last (same order as on screen: voice before end, end at the far end). **Content intent:** opens S5 (AC 6). **Flags:** ongoing, `setOnlyAlertOnce(true)`, silent, no heads-up, `setShowWhen(false)` (a time stamp next to a distance is noise). **Colour:** `setColorized(true)` with the active theme's `nav.banner` (`nav.banner-reroute` while recalculating); where an OEM ignores colourisation the content is unchanged. **Visibility:** `VISIBILITY_PRIVATE` with a public version (title «Замчлал», no text, no actions), so the full content shows on the lock screen by default and the user's "hide sensitive content" setting is respected (AC 12). The notification never contains the destination name or coordinates in any state. **Android 16 progress style** (optional, architect B-A5): if used, the progress value = distance travelled / route distance (goal-gradient), no segment points; the content fields above stay the same |
| **N2 interrupted notification** | `NotificationCompat`, channel «Замчлал» | Title «Замчлал тасарлаа» (B4), text «Үргэлжлүүлэхийн тулд дарна уу» (B5), actions «Үргэлжлүүлэх» (A7) and «Дуусгах». Not colourised, not ongoing (it is not live guidance and may be swiped away), `setTimeoutAfter` = time left in the restore window, silent (channel importance LOW; see Known limitations 3). Content intent = «Үргэлжлүүлэх». Visibility private, public version «Замчлал» |
| **Restoring banner** (R1) | Custom `NavBanner`, new variant `restoring` | Same look as the recalculating variant (grey `nav.banner-reroute`, night outline): icon `location_searching` 56 dp, text «Ачаалж байна…» (`nav-instruction`), no distance, no street, no Then strip. Semantics `variant` = `restoring` (QA hook). TalkBack live region announces it once |
| **Restoring progress panel** (R1) | `TripProgressPanel` | Left side: two skeleton bars (`ui.surface-container`, night `ui.outline-variant`, 4 dp radius, decorative, static: no shimmer, so reduced-motion needs nothing). «Тохиргоо» and «Дуусгах» are active. TalkBack node: «Ачаалж байна…». Real values replace the skeleton on the first good fix (AC 19) |
| **Restore notice** (R1) | NAV-005 status message, `kind` = `resumed` | «Замчлал сэргэлээ» (B3), info icon, `motion.nav-resumed-notice` 3 s, polite live region, not tappable, Layout rule 8 priority 2 |
| **Battery entry row** (H1, collapsed) | Custom list row (M3 list-item look), one button | 48 dp min, full sheet width − 32 dp, `ui.surface-container`, `radius.md` 12 dp. Leading `battery_alert` 24 dp in `ui.error`; label «Батарейн хязгаарлалт» (B1, `body` 14/20, `ui.on-surface`, wraps at large scales, never truncated); trailing `expand_less` 24 dp (decorative: the row opens the sheet). Accessible name = the label; role button; TalkBack action label: the system default. Test tag `battery-entry` |
| **Battery hint** (H1, expanded and wide) | Custom inline card, first item of the lower part | `ui.surface-container` card, `radius.md` 12 dp, 16 dp side margins, padding 12/12/4/16. Text B2 (`body` 14/20, `ui.on-surface`, full width, wraps, never truncated). Bottom row: battery icon 24 dp (`ui.on-surface-variant`, decorative) at the start, then text buttons «Хаах» and «Тохиргоо нээх» (48 dp, `ui.primary`) at the end; the buttons wrap under each other if they do not fit. Semantics: a container node with the text, then the two buttons. Test tag `battery-hint` |
| **Battery row** (H2) | M3 list item with an action, in S7 after the «Дуут заавар» row | Leading icon 24 dp: `battery_alert` in `ui.error` while restricted, `battery_full` in `ui.on-surface-variant` when exempt (the icon is never the only signal: the supporting text appears only while restricted). Headline «Батарейн хязгаарлалт» (`body-large`). Supporting text B2 (`body`, `ui.on-surface-variant`) while restricted. Text button «Тохиргоо нээх» (48 dp) below, aligned with the text. Divider above. Test tag `settings-battery` |
| **Theme radio «Автомат»** (T1) | NAV-005 S7 | Unchanged look and copy. Meaning: sunrise/sunset (navigation-ux §9). No supporting line yet (no glossary term; request in the handoff) |
| **S5 over the lock screen** (L1) | NAV-005 S5 / S6 | Unchanged components. The window shows over the lock screen only while guidance or its arrival panel is active (B-A7). Volume keys control the prompt stream while S5 is visible (AC 34) |

### Notification content per state (N1, AC 1–2)
| State | Large icon | Title | Text / big text | Sub-text | Actions | Colour |
|---|---|---|---|---|---|---|
| Manoeuvre | manoeuvre | distance | instruction / + street | «Хүрэх цаг 14:35» | «Дууг хаах» or «Дууг нээх», «Дуусгах» | `nav.banner` |
| Then pair | manoeuvre of *k* | distance | instruction of *k* (no Then line: a notification shows one manoeuvre, Miller) | ETA | same | same |
| Recalculating | none | «Маршрутыг дахин тооцоолж байна» (AC 2) | none (the banner's secondary failure line is not copied: no tap is possible from it) | none (ETA is stale) | same | `nav.banner-reroute` |
| GPS lost | none | «GPS дохио тасарлаа» | last instruction (still the next manoeuvre; no distance) | none (frozen) | same | `nav.banner` |
| Arrival | flag | none | arrival text exactly as the banner («Таны очих газар баруун талд байна» / «Та очих газартаа ирлээ») | none | none (guidance has ended) | `nav.banner` |
| Restoring | none | «Ачаалж байна…» | none | none | same | `nav.banner-reroute` |
| Muted | as the state | as the state | as the state | as the state | «Дууг нээх» instead of «Дууг хаах» | as the state |
| Public version (lock screen, content hidden by the user) | none | «Замчлал» | none | none | none | not colourised |

Rule: the bold **title** holds the distance or a short status; the arrival sentence (up to 33 characters next to the 48 dp flag) goes in the text line, which wraps in the expanded card. Text and big text are the same string except where the table adds a second line.

## States
Every surface's default, loading, empty, error, offline, GPS-lost, permission, first-use and slow-network state. The static web demo (NAV-017) is not affected: this story is Android only, so it has no "unavailable in the demo" state.

### N1 / N2 notification
| State | What the user sees | AC |
|---|---|---|
| Default | N1 manoeuvre row of the table above | 1 |
| Loading (restoring, before the first fix) | Title «Ачаалж байна…», grey card | 18 |
| Offline | Unchanged on the route; off-route offline = recalculating title (the banner's offline line is not copied) | 20 |
| GPS lost | «GPS дохио тасарлаа» + last instruction | 2 |
| Error (reroute failing) | Recalculating title, as long as the banner is recalculating | 2 |
| Permission denied (notifications, Android 13+) | No notification; guidance, lock screen, swipe-away and restore-on-open work | 7 |
| Swiped away (Android 14+) | Gone until the next banner instruction change, then back once | 5 |
| First use | NAV-005 notification-permission dialog at the first «Эхлэх» | NAV-005 AC 13 |
| Interrupted (N2) | «Замчлал тасарлаа» card with «Үргэлжлүүлэх», «Дуусгах» | 22 |
| Language switch | Texts, actions and channel name in the new language ≤ 2 s | 4 |

### L1 guidance over the lock screen
All NAV-005 S5/S6 states apply unchanged (on route, Then, off-route, reroute failing, GPS lost and restored, offline, no voice, muted, night, English, 200 %, landscape). Additional:
| State | What the user sees | AC |
|---|---|---|
| Unlock prompt («Тохиргоо») | OS keyguard prompt over S5; cancel → S5 stays | 9 |
| After «Дуусгах» | Phone lock screen ≤ 2 s | 9 |
| Arrival | S6 arrival panel over the lock screen until «Хаах» or screen off; then the lock screen | 10 |
| No guidance | App never shown over the lock screen | 11 |

### R1 restoring and restored
| State | Banner | Status area | Map / puck | Progress panel | Voice | AC |
|---|---|---|---|---|---|---|
| **Restoring** (≤ 10 s, waiting for a good fix) | `restoring`: «Ачаалж байна…» | «Замчлал сэргэлээ» (3 s) | Stored route fitted, no puck | skeleton + «Тохиргоо», «Дуусгах» | none (no depart prompt) | 18 |
| **Restored, on route** | manoeuvre, live distance | «Замчлал сэргэлээ» until its 3 s end | follow camera, snapped puck | live values | **one** prompt for the next manoeuvre ≤ 3 s (if ≥ 30 m ahead) | 19, 20 |
| **Restored, off route** (> 50 m) | recalculating (NAV-005 G) | — | raw puck, stored route dimmed | live values | «Та маршрутаас гарлаа» once, then catch-up after the new route | 19 |
| **Restored, offline on route** | manoeuvre | «Интернэт холболт алга» (after the notice, or together where the band allows) | follow | live | one prompt | 20 |
| **Restored, offline off route** | recalculating + «Интернэт холболт алга» (NAV-005 AC 50) | — | raw puck | live | once | 20 |
| **GPS lost while restoring** (no fix in 10 s) | stays «Ачаалж байна…» (there is no last manoeuvre to freeze) | «GPS дохио тасарлаа» | stored route, no puck | skeleton | «GPS дохио тасарлаа» once | 19 |
| **No route at the restore reroute** | recalculating + «Маршрут олдсонгүй» | — | raw puck | live | — | NAV-005 AC 49 |
| **Location permission revoked / services off / approximate** | — (S1 map, not S5) | S1 R2 message: NAV-005 S4 variant with «Тохиргоо нээх», «Хаах» | S1 | — | — | 23 |
| **Expired / crash loop / unreadable record** | — (S1 map as usual, no notice) | — | — | — | — | 21, 24 |
| **Slow network** (B-A1 = no stored-route resume) | restoring banner stays until the route response; after 12 s NAV-005 AC 48 «Маршрутын үйлчилгээ түр ажиллахгүй байна» on the recalculating banner | — | — | skeleton | — | 19 |

### H1 battery hint and H2 settings row
| State | H1 (route preview) | H2 (S7 row) | AC |
|---|---|---|---|
| Restricted, first time | Collapsed: entry row «Батарейн хязгаарлалт»; expanded / wide: full hint first in the lower part | Row + B2 text + `battery_alert` icon | 26, 28 |
| Restricted, dismissed < 30 days ago | No entry row, no hint | Row + B2 text | 26, 28 |
| Restricted, after a restore | Entry row and hint once, even if dismissed | Row + B2 text | 26 |
| Exempt | No entry row, no hint (both disappear ≤ 2 s after returning from settings; the collapsed sheet shrinks back to the NAV-011 height) | Row without B2, neutral icon | 27, 28 |
| Preview without a route (loading, error, offline, no route, location problem) | No entry row, no hint (only with a route) | — | 26 |
| During guidance / over the lock screen | Never | S7 reachable from guidance: the row shows as usual | 26 |
| No battery-optimisation settings screen on the phone | «Тохиргоо нээх» opens the app's system details page | same | 27 |
| Offline / GPS lost | The hint is local; unaffected | unaffected | — |

### T1 theme
No visible states besides day and night. Position unknown → P1 (no message: the theme still switches, Tesler). Offline → unaffected (on-device calculation).

## Interactions
- **N1 tap:** S5 within 2 s (AC 6). **«Дууг хаах» / «Дууг нээх»:** toggles voice within 1 s without opening the app (AC 3). **«Дуусгах»:** ends guidance, deletes the record (AC 15, 17). **Swipe (Android 14+):** dismisses until the next instruction change (AC 5).
- **N2 tap / «Үргэлжлүүлэх»:** opens the app into R1 restoring (AC 22 → AC 18). **«Дуусгах»:** deletes the record and removes the notification ≤ 2 s.
- **Power button during guidance:** screen off; on again → L1 within 1 s (AC 8). Open S7 sheets close when the screen turns off (Layout rule 11).
- **L1 «Тохиргоо»:** OS unlock prompt → S7 (AC 9). **L1 System Back:** app to background → phone lock screen; guidance continues. **L1 «Дуусгах» / arrival «Хаах»:** phone lock screen ≤ 2 s (AC 9, 10).
- **Swipe-away from Recents:** nothing visible changes; guidance continues (AC 13). Reopening → S5 (AC 14). *Replaces the NAV-005 interaction "guidance ends within 5 s".*
- **Opening the app after an interruption:** straight into R1 (no S1 flash: the launch route decides before the first frame) (AC 18).
- **R1 «Дуусгах»:** ends the restored guidance like any guidance (NAV-005 AC 19), record deleted.
- **H1 entry row tap:** expands the sheet (NAV-011 P4) with the full hint in view. **Hint «Хаах»:** the hint and the entry row disappear (150 ms fade, `motion.duration-short`; reduced motion: instant); the sheet stays expanded until the user collapses it, and the collapsed sheet returns to the NAV-011 height (AC 26). **«Тохиргоо нээх»:** system battery settings (AC 27). **«Эхлэх»:** one tap from the collapsed or the expanded sheet, unaffected by the hint.
- **H2 «Тохиргоо нээх»:** same target as H1 (AC 27, 28).
- **Volume keys while S5 / L1 is visible:** prompt volume (AC 34); the system volume panel shows as usual.
- **No new gesture and no text input** anywhere in this story.

## Copy (mn / en)
Every `mn` value is a glossary term. New keys use rows **B1–B5** (glossary section 2.5, `needs native review`); all other strings reuse NAV-005 keys. Key names are a proposal (the mobile engineer owns the final names). `mn` and `en` key sets stay identical (AC 46). No OEM name, settings path or Android system wording appears in any resource (AC 30).

### New keys
| Key | mn | en | Glossary |
|---|---|---|---|
| `battery_restrictions` | Батарейн хязгаарлалт | Battery restrictions | B1 |
| `battery_hint` | Батарей хэмнэх тохиргоо замчлалыг зогсоож болзошгүй. Утасны тохиргоонд батарейн хязгаарлалтыг унтраана уу. | Battery saving can stop navigation. Turn off battery restrictions in your phone settings. | B2 |
| `nav_resumed` | Замчлал сэргэлээ | Navigation resumed | B3 |
| `nav_interrupted_title` | Замчлал тасарлаа | Navigation was interrupted | B4 |
| `nav_interrupted_text` | Үргэлжлүүлэхийн тулд дарна уу | Tap to continue | B5 |

### Reused keys (NAV-005 screen spec › Copy)
| Use | Key | mn | en |
|---|---|---|---|
| Channel, public version, N2 header | `nav_name` | Замчлал | Navigation |
| N1 action, L1 button | `nav_end` | Дуусгах | End |
| N1 voice action | `nav_mute` / `nav_unmute` | Дууг хаах / Дууг нээх | Mute / Unmute |
| N1 sub-text | `route_eta`, `route_next_day_*` | Хүрэх цаг, +{days} өдөр | Arrive at, +{days} day(s) |
| N1 recalculating, GPS lost | `nav_rerouting`, `nav_gps_lost` | Маршрутыг дахин тооцоолж байна, GPS дохио тасарлаа | Recalculating route, GPS signal lost |
| N1 arrival | `maneuver_arrive*` | Та очих газартаа ирлээ, Таны очих газар баруун/зүүн талд байна | You have arrived…, Your destination is on the right/left |
| Restoring banner, N1 restoring, progress node | `status_loading` | Ачаалж байна… | Loading… |
| N2 action | `action_continue` | Үргэлжлүүлэх | Continue |
| H1, H2 action | `action_open_settings` | Тохиргоо нээх | Open settings |
| H1 dismiss | `action_close` | Хаах | Close |
| Theme options | `theme_day` / `theme_night` / `theme_auto` | Өдрийн горим / Шөнийн горим / Автомат | Day mode / Night mode / Automatic |
| Restore location problems | `location_denied_*`, `location_services_off`, `location_precise` | NAV-005 S4 texts | NAV-005 S4 texts |
| Attribution | `attribution_osm` | © OpenStreetMap contributors | © OpenStreetMap contributors |

**Glossary row for `theme_auto`:** its Definition still says "follows the phone's system dark-theme setting". Under Open question 4 (a) the BA updates the definition (no wording change); request in the handoff.

### Length notes
B2 is the longest new string (107 characters mn, 89 en): 3 lines in the hint and in the S7 row at 360 dp and 100 % (measured). B4 «Замчлал тасарлаа» and "Navigation was interrupted" ellipsise in the notification title only at font scale 200 % on 360 dp (system template). The longest instruction in the **collapsed** notification shows 28 of 36 characters at 360 dp / 100 % («Баруун талын гарах зам руу…»), all 36 at 412 dp; every instruction of 28 characters or fewer is complete (Known limitations 1). The title «Маршрутыг дахин тооцоолж байна» (30) needs 259 dp where the 360 dp card offers 256 dp in the wireframe (Liberation Sans, slightly wider than Roboto): borderline, real-device check; it fits at 412 dp; the arrival side variant «Таны очих газар баруун талд байна» shows 28 of 33 next to the flag (complete at 412 dp).

## Accessibility
- **TalkBack, N1:** the system reads title, text and actions; the large icon is decorative (no content description). The distance title is read with the unit («300 м» → "300 метр" by the TTS engine). The public version reads «Замчлал».
- **TalkBack, R1:** the restoring banner is a polite live region («Ачаалж байна…»), then the manoeuvre announcement replaces it once (NAV-005 AC 62 rule: once per instruction change). The restore notice is a polite live region. Nothing is assertive.
- **TalkBack, H1:** NAV-011 opens the sheet expanded when TalkBack is on, so TalkBack users meet the full hint directly: reading order summary → hint text → «Хаах» → «Тохиргоо нээх» → lower part → «Эхлэх». The hint is not a live region (it appears with the route; no extra announcement during planning). The collapsed entry row reads «Батарейн хязгаарлалт», button.
- **TalkBack, L1:** as S5. When the unlock prompt appears, focus moves to the system prompt; on cancel it returns to «Тохиргоо».
- **Touch targets:** notification actions are system buttons (≥ 48 dp tall on Android 12+); every app control ≥ 48 × 48 dp with ≥ 8 dp between targets (gloves in winter; measured by the checker).
- **Contrast:** no new colour pairs. Restoring banner = NAV-005 recalculating pairs (≥ 4.5:1 day and night); hint text `ui.on-surface` on `ui.surface-container` and S7 texts reuse checked pairs; `ui.error` icon on `ui.surface` ≥ 3:1. The colourised notification uses `nav.banner` (white text 6.5:1 day; the system picks the text colour) and stays readable where an OEM ignores colourisation (system colours). `check-contrast.mjs`: all pairs pass.
- **Dynamic type:** S5 overlay caps unchanged (1.3, distance 1.2). The entry row, the hint and S7 scale uncapped and wrap. Notifications follow the system font scale (system template).
- **Reduced motion:** the hint dismiss and the theme cross-fade are instant; the skeleton never animates.
- **Colour is never the only signal:** restoring = grey **and** its own icon **and** text; restricted = red icon **and** the B2 text.
- **No time limits** beyond the informational 3 s notice; the banner and voice carry the guidance.

## Design rationale
| UX law / heuristic | How this design applies it | Deliberate trade-off |
|---|---|---|
| **Fitts's law** | «Дуусгах» and voice keep their NAV-005 places over the lock screen; the entry row and the hint's buttons are 48 dp and separated from «Эхлэх» (8 dp+ gap, different look); notification actions use the system's large buttons | The entry row costs 48 dp of map in the collapsed preview while the app is restricted (206 dp left at 360×640, NAV-011 P3 still met) |
| **Hick's law** | Restore asks nothing (no choice while driving); the collapsed sheet gains one row, not three controls (progressive disclosure); the hint offers exactly two actions; the notification two | Resuming automatically removes the "don't resume" choice; «Дуусгах» is the one-tap exit |
| **Miller's law** | The notification carries one manoeuvre, one distance, one street (no Then line); ETA goes to the header | The notification shows less than the banner on purpose |
| **Jakob's law** | Google Maps notification pattern (distance as title, manoeuvre icon, coloured card, end action); full guidance over the lock screen; automatic dark map at night | — |
| **Doherty threshold** | Restore shows a guidance screen ≤ 3 s with a skeleton instead of a blank or spinner; the notification follows the banner ≤ 1 s; voice toggle ≤ 1 s | The skeleton shows no numbers until the first fix (correctness over a fast wrong number) |
| **Gestalt (similarity, common region)** | Restore notice = status message component; restoring banner = recalculating look ("the system is working"); hint = one card with its own actions; entry row and S7 row share the label and the icon | — |
| **Von Restorff** | Only the manoeuvre stands out; the entry row and the hint are quiet `surface-container` cards (only the small warning icon is red) so the route and «Эхлэх» stay the focal point | A quiet hint is easier to ignore; it returns after a restore, when it matters most |
| **Serial position** | The entry row sits last before «Эхлэх»; in the expanded sheet the hint is the first item after the summary; the S7 battery row is the last item | The S7 row needs scrolling on 360×640 |
| **Tesler's law** | The system absorbs the complexity: restore, call catch-up, output switching, sun times, crash-loop limit; the user sets nothing | Automatic «Автомат» replaces the system dark-theme behaviour (Open question 4) |
| **Peak–end rule** | Arrival over the lock screen ends cleanly on the phone's lock screen; an interrupted trip ends with "guidance is back" instead of a dead end | — |
| **Goal-gradient** | Optional Android 16 progress style shows distance travelled; ETA in the header | Optional, depends on B-A5 |
| **Zeigarnik effect** | An unfinished trip comes back by itself (restore) or waits as «Замчлал тасарлаа» until the window ends | 30 min window (Open question 5) |
| **Postel's law** | Accepts any output (A2DP, wired, USB, phone speaker) and any call type (cellular, VoIP) without settings | HFP-only car kits get the phone speaker (out of scope) |
| **Aesthetic–usability / prägnanz** | No new colours, no new components beyond one card and one banner variant | — |

Nielsen heuristics checked: **visibility of system status** (restore notice, restoring banner, the notification mirrors the banner); **match with the real world** (glossary terms only; no Android jargon in the UI); **user control and freedom** («Дуусгах» everywhere, hint dismissable, unlock-cancel returns to S5); **consistency** (NAV-005 components and tokens); **error prevention** (no restored stale manoeuvre, crash-loop limit, no settings over the lock screen); **recognition over recall** (S7 row always present for the battery fix); **flexibility** (notification actions for experts, automatic defaults for novices); **minimalist design** (one manoeuvre in the notification); **recover from errors** (N2 tells what happened and what to do); **help** (inline hint; README for OEM steps). Design changes the review and the checker forced: the hint moved from a top card into the sheet and then, measured against NAV-011's collapsed-sheet caps, behind a one-line entry row (progressive disclosure); the hint icon moved into the action row; status-area priority 2 went to the restore notice, and the arrival sentence moved from the notification title to the text line.

## Design notes (decisions inside the AC)
1. **Distance as the notification title** (not the instruction): see Alternatives. AC 1 (b), (c) are both in the collapsed card; (c) may be shortened by the system at 360 dp (Known limitations 1).
2. **No street and no ETA in the collapsed card**: Android shows only title and one text line collapsed; they are in the expanded card (AC 1 (d), (e)), which is how the top notification of the shade usually shows.
3. **Arrival notification has no actions**: guidance has ended (NAV-005 AC 55); «Дуусгах» would do nothing and the voice has nothing to say.
4. **Colourised card** (`nav.banner`): foreground-service notifications may be colourised; green = guidance, like the banner, so the notification is found at a glance in a crowded shade. Not relied on for meaning.
5. **Restore notice for 3 s** like «GPS дохио сэргэлээ» (same component, same token value, new token name so the two can change separately).
6. **Silent restart path (AC 22 first branch) shows no restore notice**: nobody is looking at the screen; the voice prompt and the notification carry it. If the user opens the app later it shows S5 as after a swipe-away (AC 14).
7. **Hint after a restore**: the restore is the evidence that the phone killed the app, so the hint comes back once even inside the 30 days (AC 26).
8. **«Автомат» keeps its name**: one option, new meaning, no new string (Open question 4 (a)). Night starts at sunset (−0.833°), not at civil dusk; in UB the 20–40 minutes of twilight after sunset are still bright enough for the day map, and the 10-minute hold prevents flicker.

## Known limitations (design, within the AC)
1. **Collapsed notification at 360 dp**: instructions longer than about 28 characters are shortened by the system with "…" (measured 28/36 at 100 %, 20/36 at 130 %, 11/36 at 200 % for «Баруун талын гарах зам руу эргэнэ үү»). The distance and the manoeuvre icon are always visible, the expanded card and the lock-screen S5 show the full text. AC 1 asks that (c) is "visible" in the collapsed card: QA should read that as "present and starting visible" on the reference device, or the BA tightens the AC (open question in the handoff).
2. **Long status titles and font scale 130–200 % in the system template**: the recalculating title is borderline at 360 dp / 100 % (259 of 256 dp in the wireframe) and is shortened at 130 % and above (AC 2 fixes it as the title; if the reference device shortens it at 100 %, the fallback is to move it to the text line, open question in the handoff); the arrival side variant shows 28/33 characters next to the flag at 100 % (it lasts ≤ 10 s and the arrival panel and prompt carry it); «Замчлал тасарлаа» ellipsises at 200 %; action labels may be shortened by the system at 200 % on 360 dp. N2's whole card is the «Үргэлжлүүлэх» target, so the action is never only in a shortened label.
3. **N2 is silent** (channel «Замчлал» is LOW importance; a second, alerting channel would need a new glossary name). The driver may not notice it until the next look at the phone; opening the app restores anyway. Possible follow-up through the BA.
4. **B2 needs one tap in the collapsed preview**: the collapsed sheet shows only «Батарейн хязгаарлалт»; the explanation and «Хаах» / «Тохиргоо нээх» are in the expanded sheet (and directly on wide windows and with TalkBack). AC 26 says the hint "appears" on the route preview; whether the expanded sheet satisfies that is an open question for the BA (handoff). Expanded at 130 % and above on 360×640 the hint is partly below the fold of the lower part (the user scrolls it, as everything in the expanded part).
5. **S7 battery row needs scrolling** on 360×640 (last item).
6. **320 dp-wide phones above 100 %** are outside the design target (NAV-005 Known limitations 7, D66); the checker reports them as INFO, as in NAV-005.
7. **Real-device behaviour** (OEM notification skins, lock-screen flags per API level, Bluetooth latency, VoIP detection) is not provable in a wireframe: AC 52 list.

## AC traceability
| AC | Where |
|---|---|
| 1–3 | N1 component, notification content table, Interactions; flow F1 |
| 4 | N1 flags (silent, alert once), Copy; flow F1 |
| 5, 6 | Interactions (swipe, tap); N1 states; flow F1 |
| 7 | N1 states (permission denied); flow F1, F6 |
| 8–12 | L1 surface, Layout rule 11, L1 states, N1 visibility/public version; flow F2 |
| 13–15 | Interactions (swipe-away, reopen); flow F3 |
| 16, 17, 21, 24 | No UI (record); R1 states (expired, crash loop); flow F4 |
| 18–20 | R1 restoring/restored states, restoring banner, progress skeleton, restore notice, Layout rules 8–9; flow F5; navigation-ux §12.4 |
| 22 | N2 component and states; Design note 6; flow F6 |
| 23 | R1 states (location problems); flow F5 |
| 25 | No UI (manifest) |
| 26, 27, 30 | H1 component, Layout rule 10, H1 states; flow F7 |
| 28 | H2 component and states; flow F7 |
| 29 | README (mobile-engineer); not a screen |
| 31–33, 35–39 | No UI; navigation-ux §12.5–12.6; flow F8, F9 |
| 34 | L1 component (volume keys); flow F8 |
| 40–44 | T1; navigation-ux §9, §12.7; map-style §8; flow F10 |
| 45, 47–51 | No UI (privacy, manifest, build) |
| 46 | Copy (B1–B5 + reused keys) |
| 52 | Known limitations 7; Evidence (what the wireframe cannot show) |

## Evidence
- `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav012.mjs`: 4 viewports (360×640, 412×915, 320×568, 640×360 dp; 24 dp status bar, 48 dp 3-button navigation bar) × 18 states × day/night × mn/en × font scale 1.0/1.3/2.0 = **864 combinations, 0 problems in the design target** (2026-10-03, Liberation Sans). 142 INFO lines outside the target: 138 at 320×568 above 100 % (Known limitations 6) and 4 notification action labels at 200 % on 360×640 (Known limitations 2).
  - Minimum portrait map band in the guidance states: 360×640 **204 / 94 / 83 dp** at 100 / 130 / 200 % (rule 150 / 80 / 80); 412×915 421 / 345 / 334 dp. Banner instruction ≤ 2 lines at 360 and 412 dp, ≤ 3 at 320 dp and in landscape.
  - Status area: the restored worst case shows one message on 360×640 and two on 412×915 (Layout rule 8).
  - Route preview (NAV-011 sheet model: collapsed composition copied from the NAV-011 wireframe, 274 dp at 360×640 without the row): collapsed with the entry row **59 % of the area above R5, 206 dp of map** at 100 % (P3: ≤ 60 %, ≥ 160 dp), 200 / 196 dp at 130 / 200 % (the top part scrolls there, NAV-011 P2); summary, entry row and «Эхлэх» visible at every scale; 412×915: 473 / 460 / 363 dp. Expanded: the full hint visible without scrolling at 100 % on 360×640 and up to 130 % on 412×915. Landscape side sheet: the hint follows the summary and needs a scroll at every scale (reported, not a rule).
  - Collapsed notification, longest instruction: 28/36 characters at 360 dp 100 %, 36/36 at 412 dp; English "Take the exit on the right" complete up to 130 %. Recalculating title borderline at 360 dp 100 % (259/256 dp), fits at 412 dp; arrival side variant 28/33 (33/33 at 412 dp). Distance titles never clipped.
  - Iterations the checker forced: (1) the restored worst case had a 146 dp band on 360×640 with two messages → Layout rule 8 (only the higher-priority message shows); (2) the first hint layout (icon column) was 164 dp tall → full-width text with the icon in the action row (128 dp); (3) modelled on the NAV-011 collapsed sheet, the full hint broke NAV-011 P3 (about 100 dp of map) → entry row + hint in the expanded part; (4) the arrival sentence beside the flag was shortened as a title → moved to the text line, where the expanded card wraps it.
- `--wide` stress run (DejaVu Sans): 42 problems of 864, all at 360×640 with font scale 130–200 % in the restored states (status message against the control pair and recenter), the same pattern as the NAV-005 stress run; not a design target.
- `node docs/design/prototypes/check-contrast.mjs`: all checks pass (no new colour pairs; two new duration tokens).
- Mermaid: the 11 diagrams of the flow parse with Mermaid 11 (checked in a headless browser).
- Timing budgets (AC 2–6, 8–10, 14, 18–19, 22, 27, 32, 36, 38, 42): stated here and in navigation-ux §12; measured by QA (Robolectric with fake clocks) and on the reference device (AC 52). Not measured by the wireframe.

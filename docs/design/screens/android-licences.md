# Screen: Android licences (row «Лиценз» in «Тохиргоо», list page S9, detail page S10)

- **Stories:** [NAV-005](../../requirements/stories/NAV-005-active-navigation-android.md) **section P, AC 88–98** (in-app licences screen, [D195](../../requirements/decisions.md), P2 / standard; must pass before the first external Android release, D17, D108). Section Q (AC 99–100, the APK API-level check in the mobile checks) has no UI and goes to the mobile engineer. Related: AC 61 and 94 (strings through resources and the glossary), AC 65 (hosts), CLAUDE.md rule 8 (OSM credit), [NAV-021](../../requirements/stories/NAV-021-android-on-device-routing-reroute.md) AC 2 (`valhalla-mobile` notices "listed for the licences screen"), [NAV-022](../../requirements/stories/NAV-022-android-offline-pack-download-update-map.md) AC 4 / R7 (pack attribution), ADR-0017 §7, ADR-0009 §11 (JNA under Apache-2.0). Traceability at the end.
- **Glossary:** section 2.8, rows **LC1–LC9** (BA, 2026-10-05, `needs native review`): all page chrome below uses them. Nothing is invented here.
- **Platforms:** Android (D162; iOS later). Phones with and without Google Play services (D62), which rules out the Play Services licence screen.
- **Flow:** the small flow at the end of Interactions (three steps; no separate flow file).
- **Prototype:** none (PO, 2026-10-05: reuse existing components, no prototype). Evidence: the contrast check, the counts from `mobile/android/THIRD_PARTY_NOTICES.md`, and one **throwaway** measurement of the top block (Evidence; the page was not kept).
- **Base specs:** the S7 sheet and its rows ([NAV-005](NAV-005-android-navigation.md) S7, [NAV-012](NAV-012-android-background-lock-screen.md) H2, [android-offline-pack.md](android-offline-pack.md) O2). Tokens: [`tokens.json`](../tokens.json) v0.6.3, **unchanged**: every colour, type step and size used here exists with a checked pair.

## Purpose
Show, without a network, who made each part of the app and of the map data and under which licence, so the app can ship to people outside the team with its legal notices.

## Context and goal
- **Primary task:** (a) check the OpenStreetMap credit and the ODbL notice, or (b) find the licence of one named component.
- **Who and when:** the PO or a tester before a release, a curious user, a reviewer, someone copying a licence name. Planning or parked, a passenger at most; it is a reference page with no task attached. Time budget: the OpenStreetMap notice is on the first screen without scrolling (AC 92); any entry is one tap and at most two scrolls away; reading takes as long as the reader wants (texts are long).
- **Not a map screen** (no map is drawn), so CLAUDE.md rule 8's "OSM credit visible on every map screen" does not apply to it; the credit is the first thing on the page instead. **Not an About page** (no changelog, no contact).

### Alternatives considered

**1. How the user gets there (AC 88).**
- (a) **A plain row «Лиценз» at the end of the S7 sheet**, opening a full-screen page. **Chosen.** 2 taps from the map (gear, row) plus one scroll; S7 already holds every rarely used item (the offline section is also at the end).
- (b) A second pane inside the S7 sheet. Rejected: the ODbL text alone is very long, and a bottom sheet with a nested scroll fights its own drag gesture.
- (c) An (i) button beside the attribution on the map (Google Maps habit). Rejected for now: R5 «© OpenStreetMap contributors» must stay clear on every map screen and a new control costs map band in guidance; it can be added later without changing this page.
- (d) The Play Services `OssLicensesMenuActivity`. Rejected: needs Google Play services (D62) and cannot list our own assets, the native libraries or the ODbL, ESA and pack blocks.

**2. How the 181 library artifacts are grouped (AC 90).** The notices file lists **181** runtime artifacts, **136** of them AndroidX.
- (a) **Component families**: 11 software rows + 2 font / icon rows, named by proper names users may recognise. A family page carries each licence text once and lists **every** artifact with its version and licence name. **Chosen** (Miller's law, recognition over recall).
- (b) One row per Maven group (the grouping AC 90 names). Rejected: `androidx.*` alone is more than 40 groups, so the list would be about 70 rows, mostly the same Apache-2.0 text.
- (c) One row per artifact (181 rows). Rejected: a wall of near-identical rows; nobody finds "MapLibre" among 130 `androidx.*` lines.
- (d) Grouped by licence (about 10 rows). Rejected: tidy for lawyers, but users look for a name; the licence name stays on every row, so nothing is lost.
AC 90 says "UX may group rows by Maven group"; (a) groups by **component family**, a coarser grouping. Every artifact still appears on screen with its licence name (the 100 % rule holds). *Request to the BA: reword the clause to "by Maven group or component family".*

**3. Where the texts live (AC 89).** (a) **Bundled in the APK** as generated assets. **Chosen**: works in airplane mode, no host at all. (b) Links to licence URLs. Rejected: needs a network and a browser, and AC 89 says the screen has no external link.

**4. Order of a detail page (AC 91).** (a) **Facts, then the licence text, then the artifact list.** **Chosen**: the text is what a reviewer came for; the 136 AndroidX lines above it would push it down by about 2,700 dp. (b) Artifacts first. Rejected for that reason.

## Layout

```
S7 «Тохиргоо» (end of the sheet)   S9 list (360×640 dp, mn, day)               S10 detail (valhalla-mobile)
│ Офлайн газрын зураг (O2)     │   ┌──────────────────────────────────┐        ┌──────────────────────────────────┐
│ … © OpenStreetMap contributors│   │ ←  Лиценз                        │ 64 dp  │ ←  Лиценз                        │
│────────────────────────────── │   ├──────────────────────────────────┤        ├──────────────────────────────────┤
│ ▤  Лиценз                   › │56 │ Газрын зургийн мэдээлэл (LC2)    │        │ valhalla-mobile                  │
└───────────────────────────────┘   │ © OpenStreetMap contributors     │        │ Хувилбар 0.6.3 · MIT   (LC5)     │
                                    │ Газрын зургийн мэдээлэл          │        │ © 2024 Adventure Consortium Inc  │
                                    │ OpenStreetMap төслөөс авсан …    │ LC7    │ © 2018 Valhalla contributors     │
                                    │ Open Database License (ODbL) 1.0›│ OF29   │ ── Лицензийн бичвэр (LC6) ────── │
                                    │ © ESA WorldCover project / …     │        │ MIT · Valhalla 3.6.3, date, …    │
                                    │ CC BY 4.0                      › │        │ Permission is hereby granted,    │
                                    │ ──────────────────────────────── │        │ free of charge, to any person…   │
                                    │ Офлайн газрын зураг (OF1)        │ only   │ … (full text, scrolls)           │
                                    │ Офлайн газрын зураг, маршрут … LC8│ with   │ BSD-3-Clause · protobuf 4.25.1   │
                                    │ Open Database License (ODbL) 1.0›│ a pack │ …                                │
                                    │ Мэдээллийн огноо (OF19)          │        │ ── Includes ─────────────────────│
                                    │ Газрын зураг: 2026-09-07 (OF20)… │        │ valhalla-models 0.5.2 · MIT      │
                                    │ ──────────────────────────────── │        └──────────────────────────────────┘
                                    │ Программ хангамж (LC3)           │
                                    │ Ferrostar          0.57.0 ·BSD-3›│
                                    │ … 11 rows                        │
                                    │ Фонт, дүрс (LC4)                 │
                                    │ … 2 rows                         │
                                    └──────────────────────────────────┘
```
The «Includes» sub-heading of S10 (artifact list of a family) has **no glossary row yet** (request below); until then the list is introduced only by spacing and the licence name on every line.

### Layout rules
- **P1. Page type.** A full-screen page over the app (not a map screen: no map, no R5 strip). M3 `Scaffold` with a standard small `TopAppBar` (64 dp, navigation icon `arrow_back` 48 dp with the accessible name of the existing resource «Хаах» (AC 88), title = LC1 `title` 16/24 500, `ui.on-surface` on `ui.surface`, tonal `ui.surface-container` once content scrolls). The bar uses `enterAlwaysScrollBehavior`: it leaves the screen while reading downwards (landscape 640×360 would lose 18 % of the height otherwise). System Back always works.
- **P2. The two blocks at the top of S9 (AC 92, 93).** Block 1, always: heading LC2 (S7 section style: `label` 14/20 500 `ui.primary`, padding 16/16/8), the credit line «© OpenStreetMap contributors» (`body-large` 16/24 `ui.on-surface`), the sentence LC7 (`body` 14/20 `ui.on-surface`), a 56 dp row OF29 «Open Database License (ODbL) 1.0» (opens the bundled ODbL text), the ESA credit (glossary G7, `body`, wraps to three lines), a 56 dp row «CC BY 4.0» (opens the bundled text). **Block 1 is visible without scrolling at 360×640 / 100 % in both languages** (Evidence: 324 of 504 dp). Block 2, only while a pack file is installed: heading OF1, the sentence LC8, a row OF29 (same text), the heading OF19 and one OF20 line per installed kind with the same dates as S7 (AC 93); a 1 dp `ui.outline-variant` divider above each block; absent otherwise, never greyed.
- **P3. List rows (S9).** M3 two-line list item, min height `size.list-row-min` 56 dp, whole row is the target: headline `body-large` = the component name (wraps, never truncated); supporting `body` `ui.on-surface-variant` = «{version} · {licence name}» for single-version components (for example «0.57.0 · BSD-3-Clause») and «{licence name}» only for families with many versions (AndroidX, Kotlin); several licences: main first («MIT · BSD-3-Clause · BSL-1.0»), wrapping; **order of the 11 software rows** = how much the product depends on them: Ferrostar, MapLibre, valhalla-mobile, SQLite, Protomaps Basemaps, JNA, Kotlin, AndroidX, Dagger, OkHttp / Okio / Moshi, Timber / Gson / JSR-305 / JSpecify / ListenableFuture; trailing `chevron_right` 24 dp. Dividers 1 dp `ui.outline-variant`, inset 16 dp. Headings LC3 «Программ хангамж» and LC4 «Фонт, дүрс» use the section style of P2.
- **P4. Detail page (S10).** One scrolling column, gutters 16 dp, **max content width 640 dp** (centred on wider windows: lines stay near 80 characters), `LazyColumn` with **one item per paragraph** (a 30 KB text is never one `Text`: one long layout pass on a mid-range phone). Order: (1) component name `headline-small` 24/32 500 (wraps); (2) LC5 «Хувилбар {version}» · licence name, `body-large` `ui.on-surface-variant`; (3) the copyright lines, one line each, `body` `ui.on-surface`, **selectable**; (4) heading LC6 «Лицензийн бичвэр», then per licence of the entry a sub-heading «{part} · {licence}» (`label`, `ui.primary`) and the verbatim text in `body` 14/20 `ui.on-surface`, paragraphs 8 dp apart, indented clauses keep their breaks and wrap; an Apache-2.0 component with an upstream NOTICE file shows it after the text (AC 91); (5) for families, the artifact list «{group:artifact} · {version} · {licence name}», `body` `ui.on-surface-variant`, one line per artifact (wraps), so **every artifact is on screen with its licence name** (AC 90).
- **P5. Text colour.** `ui.on-surface` for text, `ui.on-surface-variant` for secondary, never pure white in night mode (glare rule). No colour carries information.
- **P6. Back stack.** S9 Back → S7 (reopened at the same scroll position, the row in view); S10 Back → S9, scroll kept (AC 88, 95). The open entry and the scroll position survive rotation, a theme switch, a language switch and process death (`rememberSaveable`), so a half-read licence is where the user left it (Zeigarnik, AC 95).
- **P7. Motion.** A 150 ms fade (`motion.duration-short`) between pages; instant with the system reduced-motion setting.

## Rules for the content (UX, with the AC they serve)
| # | Rule | AC |
|---|---|---|
| **L1** | The row «Лиценз» is the **last item of S7**, after O2, below a 1 dp divider; ≥ 56 dp; opens S9 within 1 s. It is present in the debug, demo and release variants and **also while guidance runs** (S7 opened by the gear in the progress panel): guidance, voice and notification continue underneath, no prompt repeats. Opening it takes 3 deliberate steps (gear, scroll, row) and the voice carries on, which is why it is not hidden (Open question 1). | 88, 98 |
| **L2** | Offline by construction: opening S9 or S10 sends **0** requests and starts no other app (no `ACTION_VIEW`, no browser, no custom tab). The index and texts are APK assets; URLs inside notices are plain, selectable text, never links. Works in airplane mode with an empty cache, with and without a pack. | 89 |
| **L3** | Complete: every artifact of §2 of `THIRD_PARTY_NOTICES.md` (181 today), every bundled asset group of §1 and every native library statically linked into a shipped `.so` belongs to exactly one entry; nothing is listed that the variant does not ship. | 90, 96 |
| **L4** | Verbatim and untranslated: names, versions, licence names (SPDX), copyright lines, addresses and texts are generated data (glossary LC9, not translated, identical in `mn` and `en`). Only the page chrome (LC1–LC8, OF-rows, the back-control name) is in resource files. | 91, 94 |
| **L5** | Entries. *Software (11 rows):* Ferrostar (BSD-3-Clause; includes `osrm-openapi`, `libferrostar`), MapLibre (BSD-2-Clause for `android-sdk`, gestures and `libmaplibre`, Apache-2.0 for the geojson and turf libraries), **valhalla-mobile** (MIT, with Valhalla 3.6.3 and its date library MIT, protobuf BSD-3-Clause, Abseil Apache-2.0, Boost BSL-1.0, lz4 BSD-2-Clause, RapidJSON, robin-hood-hashing and unordered_dense MIT, `valhalla-models*` MIT: **one sub-section per licence text**, copyright «© 2024 Adventure Consortium Inc (dba Rallista)» and «© 2018 Valhalla contributors»), SQLite (bundled by `androidx.sqlite:sqlite-bundled`: the SQLite public-domain notice), Protomaps Basemaps (the style: BSD-3-Clause code, CC0-1.0 design), JNA (Apache-2.0 option of "Apache-2.0 OR LGPL-2.1", stated in the entry), Kotlin (16 artifacts), AndroidX (136; one of them, `datastore-preferences-external-protobuf`, is BSD-3-Clause and gets its own sub-section), Dagger (6: Dagger, Hilt, `javax` / `jakarta` inject), OkHttp, Okio, Moshi (8), Timber, Gson, JSR-305, JSpecify, ListenableFuture (5). *Fonts and icons (2 rows):* Noto Sans (SIL OFL 1.1, copyright and Reserved Font Name line of `assets/fonts/OFL.txt`), Tangram icons, Protomaps sprites (MIT and CC0-1.0). *Map data:* the two blocks of P2. | 90–93 |
| **L6** | The OSM credit line equals the glossary string, the pack manifest's `attribution` and the PMTiles metadata (a fixture comparison, QA). Block 2 reads only the app's resources and `active.json`, never a network manifest. | 92, 93 |
| **L7** | One text asset per licence, referenced by every entry that uses it (Apache-2.0 once, not 172 times); assets ≤ 0.5 MB compressed; the screen is interactive ≤ 1 s with ≥ 150 entries (lazy list). | 91, 97 |
| **L8** | Generated, not written by hand: the index comes from `tools/gen-third-party-notices.py` and the repository licence files; a Gradle check fails the build when an artifact has no entry, an entry has no text or copyright line, a licence is outside the allow-list, or the data differs from the committed notices file. | 96 |
| **L9** | Language switch: chrome ≤ 1 s, texts untouched, 0 requests. | 95 |

## Components

| Component | Source (Ferrostar / Material 3 / custom) | Notes |
|---|---|---|
| **S7 row «Лиценз»** | M3 list item in the S7 sheet (same family as the voice and battery rows) | Leading `description` icon 24 dp `ui.on-surface-variant`, headline `body-large` = LC1, trailing `chevron_right`; whole row is the target, ≥ 56 dp. Test tag `licences-row`, role button. |
| **Page frame** | M3 `Scaffold` + small `TopAppBar` + `enterAlwaysScrollBehavior` | New to the app (no other full-screen page yet); standard M3, no custom drawing. Test tags `licences-list`, `licences-detail`. |
| **Notice blocks, list rows, headings** | M3 `ListItem` (two-line, trailing icon), S7 section heading style, `body` text | P2, P3. Test tags `licences-notice`, `licences-pack`, `licence-entry-<id>`. |
| **Detail content** | `LazyColumn` of `Text` inside a `SelectionContainer` | P4; one item per paragraph. |
| **Loading row / error row** | The NAV-005 loading row and state row (info icon + text + `TextButton`) | «Ачаалж байна…» after 300 ms; «Алдаа гарлаа» + «Дахин оролдох». |
| Ferrostar | Not used or changed | Only a listed dependency (ADR-0009 §1). |

## States

| State | What the user sees |
|---|---|
| **Default (S9)** | Block 1, (block 2 with a pack), the 11 software rows, the 2 font / icon rows. Block 1 is fully visible on the first screen at 360×640 / 100 %. |
| **Default (S10)** | Name, LC5 · licence, copyright lines, LC6 and the text, (families) the artifact list. |
| **Loading** | S9 renders from the small index in one frame (≤ 1 s from the tap, AC 88 / 89 allow 2 s). S10 reads its text asset; «Ачаалж байна…» shows only if that takes > 300 ms, the facts above it are already there. |
| **Empty** | Cannot happen (the list is generated). A missing or damaged index (damaged APK) shows the error row, never a blank page. |
| **Error** | S9: «Алдаа гарлаа» + «Дахин оролдох» in place of the list (block 1 stays: the credit is app text). S10: the same row in place of the text; name, version, licence name and copyright lines stay. No automatic retry; nothing logged with user data. |
| **Offline / airplane mode** | Identical to online (L2). |
| **No pack installed** | Block 2 absent; block 1 and both lists unchanged. |
| **Pack installed, only partly usable** (for example the routing file is too new) | Block 2 shows the dates of the installed kinds only (NAV-022 AC 39). |
| **Demo build (NAV-019)** | Row and page present; block 2 absent (no pack, O2 hidden, B7). |
| **During guidance** | Row present (L1); guidance continues underneath; if guidance **starts** while S9 / S10 is open (a restore, NAV-012), the page closes and the guidance screen shows. |
| **GPS lost / location permission denied** | Not used: the page reads no location and works in any permission state. |
| **Slow network / first use** | Not applicable (no network). |
| **Static demo "unavailable"** | Not applicable (Android only; the web demo gets no licences page in this change). |
| **Long text** | The longest text (ODbL 1.0, about 30 KB) is a long scroll. *Estimate:* at 360 dp and 100 % about 45 characters per line, about 650 lines, about 13,000 dp; at 200 % about 23 characters per line and 40 dp lines, about 1,300 lines, about 52,000 dp (about 80 screens). Only visible paragraphs are laid out (P4); the scrollbar thumb is shown; the bar leaves the screen (P1). |
| **Font scale 130 / 200 %** | Rows grow (name, then up to three lines of supporting text); nothing is truncated or cut mid-word; headings and the artifact lines wrap; targets stay ≥ 48 dp. Block 1 is **324 dp at 100 %, 432 at 130 %, 856 (mn) / 776 (en) at 200 %** on 360×640, so from 200 % the first screen scrolls (AC 92 asks for no scrolling at 100 % only). |
| **Night** | Night tokens only; text `ui.on-surface` on `ui.surface`, not white on black. |
| **Landscape 640×360** | Same column, width capped at 640 dp, the bar scrolls away downwards. |
| **Rotation / theme / language switch** | Entry and scroll position come back (P6); chrome updates ≤ 1 s. |

## Interactions
- **S7 row tap:** S9 opens (150 ms fade); the sheet closes behind it and returns on Back.
- **S9 row tap:** S10 for that entry. **OF29 and «CC BY 4.0» rows:** S10 for the ODbL 1.0 / CC BY 4.0 text (the OSM entry: credit line, LC7, OF29, full text, the copyright page address as plain text). **Back / `arrow_back`:** previous page, scroll kept.
- **S10 long-press on text:** the system selection and «Copy»; addresses are not links, a tap does nothing.
- **Error «Дахин оролдох»:** reloads once.
- **Flow:** map (S1) → gear → S7 → (scroll) → «Лиценз» → S9 → entry → S10. Errors: asset unreadable → error row → retry or Back.

## Copy (mn / en)

Page chrome only; all other text is data (L4). All `mn` values are existing glossary rows (LC1–LC8 section 2.8 are `needs native review`).

| Key | mn | en | Glossary |
|---|---|---|---|
| `licences_title` (S7 row, page title) | Лиценз | Licences | LC1 |
| `licences_section_data` | Газрын зургийн мэдээлэл | Map data | LC2 |
| `licences_section_software` | Программ хангамж | Software | LC3 |
| `licences_section_fonts` | Фонт, дүрс | Fonts and icons | LC4 |
| `licences_version` | Хувилбар {version} | Version {version} | LC5 |
| `licences_text_heading` | Лицензийн бичвэр | Licence text | LC6 |
| `licences_osm_notice` | Газрын зургийн мэдээлэл OpenStreetMap төслөөс авсан бөгөөд Open Database License (ODbL) 1.0 лицензтэй. | Map data is taken from the OpenStreetMap project and is under the Open Database License (ODbL) 1.0. | LC7 |
| `licences_pack_notice` | Офлайн газрын зураг, маршрут, хайлтын мэдээлэл нь OpenStreetMap төслийн мэдээллээс бэлтгэсэн бөгөөд ижил лицензтэй. | Offline map, route and search data are made from OpenStreetMap data and have the same licence. | LC8 |
| Pack block heading, data dates | Офлайн газрын зураг; Мэдээллийн огноо; Газрын зураг / Маршрут / Хайлт: {date} | Offline map; Data date; Map / Routes / Search: {date} | OF1, OF19, OF20 |
| ODbL row | Open Database License (ODbL) 1.0 | same | OF29 (not translated) |
| OSM credit | © OpenStreetMap contributors | same | glossary §8 (not translated) |
| ESA credit | © ESA WorldCover project / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium | same | glossary §8 (not translated) |
| Back control name | Хаах | Close | existing `action_close` (AC 88) |
| Loading / error / retry | Ачаалж байна… / Алдаа гарлаа / Дахин оролдох | Loading… / Something went wrong / Try again | existing |
| Names, versions, licence names, copyright lines, texts | not translated | not translated | LC9 |

Length check: the S7 row and page title are one word; LC7 is the longest chrome string and wraps to three lines at 360 dp / 100 % (measured in Evidence); the ESA credit wraps to three lines; nothing is cut with an ellipsis. **Missing term (request to the BA):** the heading of the artifact list on S10 (en proposal "Includes"; the Mongolian is the BA's). Until it exists the list has no heading (Layout note).

## Accessibility
- **Contrast** (`check-contrast.mjs`, all pairs pass): `ui.on-surface` on `ui.surface` 16.48:1 day / 12.01:1 night (text, names, credit); `ui.on-surface-variant` on `ui.surface` 9.39 / 8.20 (supporting text, artifact lines) and on `ui.surface-container` ≥ 4.5 (tonal bar once scrolled); `ui.primary` on `ui.surface` 6.39 / 8.75 (section and sub-headings); `ui.outline` on `ui.surface` 4.53 / 4.90 (chevron, non-text ≥ 3:1). Dividers are decorative.
- **Targets (AC 95):** the S7 row, every S9 row and the OF29 / CC BY rows are full width and ≥ 56 dp; the navigation icon is 48 × 48 dp; there is no other control.
- **Dynamic type:** all text in `sp`, uncapped (this is not a guidance screen); no horizontal scrolling at 200 %.
- **TalkBack (AC 95):** the S7 row reads «Лиценз, button». S9: the page title and the section headings are headings; each row is **one node** «{name}, {version}, {licence name}, button» (families with many versions omit the version, as on screen). S10: focus **moves to the component name (a heading) when the page opens**; each sub-heading is a heading; **each paragraph is one node** (swipe moves paragraph by paragraph, "next heading" jumps between licences); each artifact line is one node. Back returns focus to the row that opened the page.
- **Colour is never the only signal.** Reduced motion: P7.

## Design rationale

| UX law / heuristic | How this screen applies it | Deliberate trade-off |
|---|---|---|
| **Fitts's law** | Full-width 56 dp rows; the one control, the navigation icon, is 48 dp, and the system Back gesture is the thumb-friendly way | The arrow is at the top left, far from the thumb; the system Back covers it |
| **Hick's law** | Two levels, one choice per level, no search or filter; 13 rows | 13 rows are scanned, not searched; fine for a rare page |
| **Miller's law** | 181 artifacts become 13 rows; the top block holds 6 short items | The artifact name is found in the family page, not on S9 |
| **Jakob's law** | Settings → licences row → list → text is the Android pattern users know (Google apps' "Open source licences") | A full-screen page is a new screen type for this app |
| **Doherty threshold** | S9 from a precomputed index within 1 s; loading row only after 300 ms; per-paragraph laziness | — |
| **Gestalt (similarity, proximity, common region)** | Every row is one component; the OSM credit, its notice and the licence row are one block; a sub-heading sits nearer its text than the previous text | — |
| **Serial position** | The legally required OpenStreetMap notice is the first thing; the 136-artifact AndroidX row is in the middle of a list ordered by how much the product depends on a component | The order is by product importance, not alphabetical (so users cannot rely on A–Z) |
| **Tesler's law** | List and texts are generated from the notices file; a missing entry fails the **build**, nobody has to remember to update a screen | A new dependency needs a family assignment (the generator tells the developer) |
| **Postel's law** | Selectable and copyable text; any font scale, window or language | — |
| **Von Restorff / aesthetic–minimalist** | Nothing stands out: no primary action; only the headings carry colour | — |
| **Zeigarnik effect** | The entry and scroll position survive rotation and process death | — |
| **Safety (principle 1)** | The page needs 3 deliberate steps, has no typing, guidance continues underneath | A driver could open it mid-trip (Open question 1); the banner is hidden while it is open |
| **Cognitive load (measured)** | Taps from the map to a licence text: **3** (gear, row, entry) plus one scroll in S7; chrome words on S9: 3 headings, 0 buttons | — |

Nielsen heuristics checked: **visibility of system status** (loading row only when slow, error rows with retry); **match with the real world** (proper names, glossary terms for the chrome, the credit and ODbL untranslated); **user control and freedom** (Back at every level, scroll kept); **consistency and standards** (M3 list items, S7 section style, existing loading and error rows); **error prevention** (a missing entry or text fails the build, not the user); **recognition over recall** (names, licence name on every row); **flexibility and efficiency** (selectable text, the OF29 row jumps straight to the ODbL text); **aesthetic and minimalist design**; **help users recover from errors** («Дахин оролдох», Back); **help and documentation** (the page is itself documentation).

## Known limitations
1. The heading of the artifact list on S10 has no glossary row yet (request below); it works without it.
2. Licence texts are English only (L4). A Mongolian summary is a different task (glossary: an explanation may be added on an About screen, never replacing the credit line).
3. The long-text figures are **estimates** (canonical text sizes, a 7 dp mean character width); only the top block was measured, in a throwaway page. Mobile should measure S9 / S10 with the real assets on 360×640, 320×568 and 640×360 at 100 / 130 / 200 %.
4. SQLite's notice and the exact list of statically linked native code must be confirmed against the shipped libraries by the generator (ADR-0017 follow-up 1 already corrected the valhalla list once).
5. The back control's accessible name is «Хаах» (AC 88: an existing resource) on a back arrow. A «Буцах» row would be more exact (request, optional).
6. No (i) shortcut from the map (alternative 1c).

## Open questions
1. **The row during guidance.** AC 88 has the page opened and closed while guidance runs, so the row is shown (L1). Options: (a) show it (as designed, matches AC 88); (b) hide it while guidance runs, because a full-screen page hides the banner for as long as it is open. *Recommendation: (a)*: 3 deliberate steps, voice continues, one Back returns; revisit if testers report it. Needs the PO only if (b) is wanted (then AC 88 changes).
2. **The O2 line OF29** in the offline section currently opens the manifest URL in the browser (NAV-022 AC 4, kept by AC 89). It could open the ODbL text of this screen instead (works offline, no browser). *Recommendation: (b), later*: a one-line change, but it changes NAV-022 AC 4, so it is the BA's call; this spec leaves O2 as it is.
3. **Row grouping.** AC 90 allows grouping "by Maven group"; this design groups by component family (Alternative 2). *Recommendation:* the BA rewords AC 90 to allow both.
4. **Mongolian summary of the ODbL notice for lay readers.** *Recommendation:* not in this change; PO to say if wanted (needs the BA and a native reviewer).

## Traceability
| Source | Where |
|---|---|
| AC 88 (entry, Back, during guidance) | L1; P1, P6; States › During guidance; Open question 1 |
| AC 89 (offline, no link) | L2; States › Offline; Open question 2 |
| AC 90 (every component listed, licence names) | L3, L5; P3, P4 (artifact lines); Alternative 2; Open question 3 |
| AC 91 (version, copyright, full text per entry) | P4; L5; L7 |
| AC 92 (OSM and ODbL block, visible without scrolling) | P2; Evidence (324 of 504 dp) |
| AC 93 (pack attribution block) | P2 block 2; L6; States › No pack |
| AC 94 (strings) | Copy; L4 |
| AC 95 (layout, accessibility, rotation) | P1, P4, P6; Accessibility; States |
| AC 96 (generated, build fails on drift) | L8 (no UI) |
| AC 97 (size and speed) | L7 |
| AC 98 (variants, release gate) | L1; States › Demo build |
| AC 99–100 (APK API-level check) | No UI: the mobile engineer's request in the handoff |
| D195; CLAUDE.md rule 8 | Whole spec; Context ("not a map screen"), P2 (credit first) |

## Evidence
- **Contrast:** `node docs/design/prototypes/check-contrast.mjs` → **all checks passed** (166 PASS, 0 FAIL, flavor parity 90 + 5 keys, 86 map-style values), 2026-10-05. The pairs this page uses are listed under Accessibility. No token changed.
- **Top block (AC 92), throwaway measurement 2026-10-05:** a page with the block's real `mn` / `en` strings (LC2, the credit, LC7, OF29, the ESA credit, «CC BY 4.0»), Liberation Sans (Arial metrics, slightly wider than Roboto), the type steps and 56 dp rows of P2, in Chromium (Playwright from `tests/e2e`), not kept in the repository. Block height against the content area (window − 24 dp status bar − 48 dp navigation bar − 64 dp app bar): **360×640 / 100 %: 324 of 504 dp (mn and en), fits**; 130 %: 432 (mn) / 406 (en), fits; 200 %: 856 / 776, scrolls. **412×915:** 304 / 373 / 736 (mn) of 779 dp, fits at every scale. **320×568:** 324 of 432 at 100 % (fits), 432 of 432 at 130 % (just fits), 936 / 816 at 200 % (scrolls; outside the design target, D66). Not measured: the rest of S9, S10, landscape.
- **Counts from `mobile/android/THIRD_PARTY_NOTICES.md` (2026-10-05):** 181 artifact rows in §2 = AndroidX 136, Kotlin 16, OkHttp / Okio / Moshi 8, Dagger / inject 6, Timber, Gson, JSR-305, JSpecify, ListenableFuture 5, MapLibre 4, valhalla-mobile 3, Ferrostar 2 (`core`, `osrm-openapi`), JNA 1. Licence names in the file: Apache-2.0 under four spellings 172 (150 + 14 + 4 + 4), BSD-3-Clause 3, MIT 3, "BSD" 2 (MapLibre, shown as BSD-2-Clause, AC 90), JNA dual 1. Page size: 13 rows (11 software, 2 font / icon) plus two notice blocks.
- **Not in the repository:** the canonical texts of Apache-2.0, ODbL 1.0, BSL-1.0, BSD-2-Clause and CC BY 4.0 (the web demo has only the MIT, BSD-3, CC0 and OFL files under `web/licenses/` and `assets/fonts/OFL.txt`); the generator must add verbatim upstream texts (request in the handoff).
- **Timing budgets:** S9 ≤ 1 s, texts ≤ 1 s (AC 88, 89, 91, 97): delegated to the mobile engineer's Robolectric tests and QA; not measured here.

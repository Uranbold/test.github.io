---
id: NAV-022
title: Android offline Mongolia map download and update ("Download Mongolia map"), including the map from the installed pack
phase: 1
priority: must          # P1 / standard, feature, PO-confirmed 2026-10-04 (D192; item 4 merged). BA MoSCoW mapping P1 → must
size: L                 # PackManager (WorkManager), first-launch offer, Settings section, network rules, storage checks, resume, integrity, atomic install, automatic updates, 14-day offer, delete, data dates, map from the pack
needs_design: true      # first-launch offer, Settings section, mobile-data confirmation, 14-day offer, progress, delete, data dates
needs_backend: false    # consumes NAV-020 files over openapi 0.6.0; no contract change
needs_mobile: true      # Android (mobile/android)
status: ready           # 2026-10-04. Delivery slot 3, after NAV-021 (D197). Non-blocking open questions only
---

# NAV-022: Android offline Mongolia map download and update, including the map from the pack

## Story
As an **intercity / countryside driver**, I want **to download the whole Mongolia map to my phone once over Wi-Fi and have the app keep it up to date by itself**, so that **the map, routes and search keep working when I drive out of signal, without me having to remember to update anything or spend my mobile data by surprise**.

Secondary personas:
- **Taxi / delivery driver** without home Wi-Fi: can download over mobile data after seeing the size, and gets a small routing + search update when the data is old.
- **UB commuter by car:** gets the offer once on first launch on Wi-Fi; the map then always comes from the phone (no tile data use).
- **Pedestrian:** the same pack serves walking routes and search.
- **Tourist (English UI):** the same flow in English; place labels follow the map label rule.

## Context
- **Request and decisions.** Offline is a launch requirement for the real Android app ([D156–D159](../decisions.md)). Spike §9 items 3 and 4; [ADR-0017](../../architecture/adr/0017-offline-mongolia-pack-android.md) §5 (app) and §6 (contract); triage 2026-10-04 rows "§9 item 3" (feature, P1 / standard, [D192](../decisions.md)) and "§9 item 4" (merged into this story as section H).
- **PO decisions that apply:**
  - downloaded in the app, not bundled ([D160](../decisions.md)); all of Mongolia ([D158](../decisions.md)); z0–14 ([D170](../decisions.md))
  - **Wi-Fi by default; mobile data only after a per-download confirmation that shows the size** ([D164](../decisions.md))
  - **automatic background updates on Wi-Fi with battery not low**; if the installed routing and search data are **older than 14 days** and there is no Wi-Fi, offer a **mobile-data update of routing + search only** ([D165](../decisions.md)); after "not now", **offer it again after 7 days** ([D200](../decisions.md))
  - **map monthly, routing and search weekly** ([D166](../decisions.md)); **full files** ([D167](../decisions.md))
  - **offer the download once on first launch when on Wi-Fi, and always in «Тохиргоо»** ([D169](../decisions.md)); a first launch without Wi-Fi shows no offer
  - **the map comes from the pack** whenever it is installed, with or without internet ([D163](../decisions.md))
  - the small **"offline" indicator** is for routes and search results only ([D201](../decisions.md); NAV-021, NAV-023); the map shows the data dates in Settings instead (AC 40)
  - delivery slot 3, after NAV-021 ([D197](../decisions.md))
- **What exists today.** No pack manager, no Settings pack section and no download code in `mobile/android`. ADR-0016 reads a bundled demo PMTiles file with `pmtiles://file://` for NAV-019 only (MapLibre Native 13.6.1 reads `pmtiles://file://` but not `pmtiles://asset://`). Phones without Google Play services are supported (D62), `minSdk` 26.
- **Not changed by this story:** NAV-005 (map, network-loss indicator, AC 54, AC 65), NAV-011 and NAV-012 AC. Change 7a aligns NAV-005 after this story and NAV-021 ([D194](../decisions.md)). The licences screen with the ODbL notice is the separate change on NAV-005 ([D195](../decisions.md)); this story shows the pack's own attribution and licence line in the pack section (AC 4).
- **Coordination (binding):** shared files with NAV-019 and the NAV-005 location-dot change (`NavApplication.kt`, `AppModule.kt`, `strings.xml`, settings): read first, small targeted edits, never revert another story. Development before NAV-020 runs on a server uses a locally produced pack on a local static server (AC 47); never write to or restart the shared dev stack at `http://127.0.0.1:8080`.

### Terms used in this story
| Term | Meaning here |
|---|---|
| **Pack** | The three files of region `mn` (`tiles`, `routing`, `search`) listed in the manifest (openapi `OfflinePackManifest`) |
| **Installed** | Files that passed AC 16 and are recorded in `active.json` |
| **Wi-Fi** | A validated network that Android reports as not metered (`NET_CAPABILITY_VALIDATED` + `NET_CAPABILITY_NOT_METERED`); WorkManager `NetworkType.UNMETERED` |
| **Mobile data** | A validated network that is metered (including a phone hotspot that Android marks metered) |
| **User-started download** | A download started by «Татах», OF2, «Шинэчлэх» or the 14-day offer |
| **Files to fetch** | The compatible files whose manifest `sha256` differs from the installed one (all three on a first install) |
| **Required space** | Σ `bytes` of the files to fetch + the largest `download_bytes` among them + **50 MB** margin (first install ≈ 338 MB, weekly routing + search ≈ 160 MB, with the measured sizes) |
| **Size display** | Decimal units (1 MB = 1,000,000 bytes), rounded **up**: below 1,000 MB whole «МБ» (for example «120 МБ»); from 1,000 MB «ГБ» with one decimal and a comma (for example «1,2 ГБ») |

### User-facing strings
Every Mongolian string comes from `docs/requirements/glossary.md`, **section 2.7 "Offline Mongolia map"**, added with this story (all `needs native review`), plus reused rows:

| Use in NAV-022 | Glossary row | `mn` | `en` |
|---|---|---|---|
| Settings section, notification channel | OF1 | «Офлайн газрын зураг» | "Offline map" |
| Offer heading, Settings button | OF2 | «Монголын газрын зураг татах» | "Download Mongolia map" |
| Offer body | OF3 | «Интернэт холболтгүй үед ч газрын зураг, маршрут, хайлт ажиллана» | "Map, routes and search work even without an internet connection" |
| Download / Not now | OF4 / OF5 | «Татах» / «Дараа» | "Download" / "Not now" |
| Sizes | OF6 / OF7 / OF28 | «Татах хэмжээ: {size}» / «Утсанд шаардлагатай зай: {size}» / «Эзэлж буй зай: {size}» | "Download size: {size}" / "Space needed on phone: {size}" / "Space used: {size}" |
| Progress, waiting, cancel | OF8 / OF9 / OF10 | «Татаж байна… {percent}%» / «Wi-Fi холболт хүлээж байна» / «Цуцлах» | "Downloading… {percent}%" / "Waiting for Wi-Fi" / "Cancel" |
| Done, failed | OF11 / OF12 | «Офлайн газрын зураг бэлэн боллоо» / «Газрын зургийг татаж чадсангүй» | "Offline map is ready" / "The map could not be downloaded" |
| Mobile-data confirmation | OF13 / OF14 | «Мобайл датагаар {size} татах уу?» / «Wi-Fi хүлээх» | "Download {size} using mobile data?" / "Wait for Wi-Fi" |
| Not enough storage | OF15 | «Утсанд хангалттай зай алга. {size} зай чөлөөлнө үү.» | "Not enough space on the phone. Free up {size}." |
| Update, 14-day offer | OF16 / OF17 / OF18 | «Шинэчлэх» / «Маршрут, хайлтын мэдээлэл хуучирсан байна» / «Мобайл датагаар {size} шинэчлэх үү?» | "Update" / "Route and search data are out of date" / "Update {size} using mobile data?" |
| Data dates | OF19 / OF20 | «Мэдээллийн огноо» / «Газрын зураг: {date}», «Маршрут: {date}», «Хайлт: {date}» | "Data date" / "Map: {date}", "Routes: {date}", "Search: {date}" |
| Delete | OF21 / OF22 / OF23 | «Офлайн газрын зургийг устгах» / «Офлайн газрын зургийг устгах уу?» / «Устгах» | "Delete offline map" / "Delete the offline map?" / "Delete" |
| Units | OF26 / OF27 | «МБ» / «ГБ» | "MB" / "GB" |
| Licence line in the pack section | OF29 (not translated) | «Open Database License (ODbL) 1.0» | same |
| Reused | Try again, Settings, OSM attribution, ESA credit | «Дахин оролдох», «Тохиргоо», «© OpenStreetMap contributors», ESA credit | as today |

`{size}` uses the size display rule; `{percent}` is an integer 0–100; `{date}` is `YYYY-MM-DD` (digits only, no month names). "Wi-Fi" stays in Latin letters in both languages (the Android system term).

## Acceptance criteria

### A. Entry points: first-launch offer and Settings (D169)
1. **Given** the app starts with no installed pack, the offer flag not set, and Wi-Fi, **When** the browse map (S1) has rendered, **Then** within **10 s** the manifest is fetched and the offer is shown: heading OF2, body OF3, OF6 with `total_download_bytes`, OF7 with the required space, and the buttons «Татах» (OF4) and «Дараа» (OF5). The offer is never shown while guidance runs, and it never hides the OSM attribution once closed.
2. **Given** the offer has been shown, **When** the user taps «Татах», taps «Дараа», presses Back, dismisses it, or the process dies while it is open, **Then** the offer flag is stored, and the offer is **never** shown again on that installation (also not after AC 36 delete). The download stays available in «Тохиргоо» (AC 4).
3. **Given** the first app start has no Wi-Fi, **When** the app runs, **Then** **no** offer is shown on that start and the flag is set (D169: no offer without Wi-Fi at first launch). **Given** the manifest cannot be fetched on a first start with Wi-Fi (network error, 404, 429, or no answer within 10 s), **When** that start continues, **Then** no offer is shown and the flag is **not** set, so the offer can appear on a later start that has Wi-Fi and a manifest. **Given** an installation that existed before this feature (app update), **When** the first start of the new version runs, **Then** it counts as the first launch (BA reading of D169).
4. **Given** «Тохиргоо», **When** it opens, **Then** it always shows the section OF1 «Офлайн газрын зураг» with:
   - no pack installed: the button OF2, and OF6 / OF7 once the manifest is known (the button works without them; sizes then show in the AC 8 dialog or AC 6 check)
   - pack installed: OF19 with one OF20 line per installed kind (AC 39), OF28 with the installed size, OF16 «Шинэчлэх» only when files to fetch exist, OF21 delete, «© OpenStreetMap contributors» and the line OF29 opening the ODbL 1.0 text (manifest `licence.url`)
   - a download running: OF8 with the percentage and OF10 «Цуцлах»; a download waiting for Wi-Fi: OF9 and OF10

### B. Size and storage checks
5. **Given** any size shown by this story, **When** it renders, **Then** it follows the size display rule (Terms) in both languages, with OF26 / OF27 as units, and the values come from the manifest (`download_bytes`, `bytes`), never from a hard-coded number.
6. **Given** a user-started download or an automatic update, **When** it is about to start, **Then** the allocatable space (`StorageManager.getAllocatableBytes` on the pack directory's volume) is compared with the required space. If it is smaller, **0** file requests are sent, nothing changes, and for a user-started download OF15 is shown with `{size}` = required space − allocatable space (size display rule). Automatic updates skip silently and try again at the next periodic check.
7. **Given** a download is running, **When** the disk becomes full (a write fails with no space), **Then** the transfer stops, its partial files are deleted within **5 s**, the installed pack stays active and unchanged, and for a user-started download OF15 is shown as in AC 6.

### C. Network rules (D164)
8. **Given** a user-started download and Wi-Fi, **When** it starts, **Then** the first file request is sent within **5 s**. **Given** mobile data instead, **When** it starts, **Then** the confirmation OF13 is shown with `{size}` = Σ `download_bytes` of the files to fetch, with the buttons «Татах» (OF4) and «Wi-Fi хүлээх» (OF14); **0** file requests are sent before an answer.
9. **Given** the OF13 confirmation, **When** the user taps «Татах», **Then** that download runs on any validated network (mobile data or Wi-Fi) until it ends. **When** the user taps «Wi-Fi хүлээх» or dismisses it, **Then** the download is queued, the status shows OF9, and it starts within **30 s** after Wi-Fi becomes available. The confirmation is **never** remembered: the next user-started download over mobile data asks again.
10. **Given** no validated network, **When** a user-started download is requested, **Then** it is queued with OF9 and never uses mobile data without the AC 8 confirmation.
11. **Given** any network, **When** the manifest (a few KB) is needed, **Then** it may be fetched on any validated network, including mobile data, without a confirmation. **0** pack file requests are sent over mobile data without a confirmation (AC 8) or the 14-day offer (AC 28).

### D. Download, progress, cancel and resume
12. **Given** a download is running, **When** the user watches the notification (channel OF1, low importance) or «Тохиргоо», **Then** OF8 shows the integer percentage of bytes received of Σ `download_bytes` of the files to fetch, updated at least every **2 s**, and the notification offers the action OF10 «Цуцлах».
13. **Given** a download is running, **When** the user taps OF10 (notification or Settings), **Then** within **5 s** all transfers stop, **0** partial files remain, no further pack requests are sent for that download, and the state is the one before the download (no pack, or the old pack active).
14. **Given** a download at about **50 %** of a file, **When** the network drops or the app process is killed (`adb shell am kill` or a swipe-away) and the allowed network later returns, **Then** the download resumes with `Range: bytes=<bytes on disk>-` and `If-Range: <ETag>`, and the bytes transferred for that file in total are **≤** `download_bytes` + **1 MB** (checked on a test proxy). A **200** answer to the resumed request (validator changed) restarts that file from byte 0.
15. **Given** a download, **When** the server answers **404** for a file (retired after a newer publication), **Then** the app re-reads the manifest and downloads the files to fetch of the new manifest. **When** it answers **429**, **Then** no request is sent before `Retry-After` has passed, then the download resumes. **When** **5** consecutive attempts fail with network errors or 5xx during a user-started download, **Then** OF12 is shown with «Дахин оролдох», partial files are kept for a resume, and the old pack stays active. A phone reboot during a download resumes it after the reboot under the same network rule.

### E. Integrity, self-tests and atomic install
16. **Given** a downloaded file, **When** it is checked, **Then** in this order: SHA-256 of the downloaded bytes = `download_sha256`; it is decompressed (streaming gzip) to `bytes` bytes with SHA-256 `sha256`; and the self-tests pass before activation:
    - manifest `pack_schema` is known to the app (otherwise no file of that manifest is offered)
    - `tiles`: PMTiles v3 magic; header minzoom 0, maxzoom 14; bounds contain P1–P6
    - `routing`: `format.graph_builder` is on the NAV-021 allow-list (otherwise the routing file is not offered), and the NAV-021 `:routing` process routes `self_test.route` with OSRM `code` `Ok` (in the routing process, so a bad file cannot crash the main process)
    - `search`: `PRAGMA quick_check` = `ok`, and `self_test.search` returns ≥ **1** row
17. **Given** a test proxy flips one byte in the middle of `routing.tar.gz` (and, separately, serves a valid gzip of a wrong file), **When** the download completes, **Then** the check fails, **0** files of that version are activated, the version's partial directory is deleted within **5 s**, the installed pack is unchanged, a user-started download shows OF12 with «Дахин оролдох», and an automatic update fails silently and retries at the next periodic check.
18. **Given** files pass AC 16, **When** they are installed, **Then** they are written to `noBackupFilesDir/packs/<fileVersion>.partial/`, renamed to `packs/<fileVersion>/`, and `packs/active.json` (the installed version of each kind) is written to a temporary file and renamed. **Given** `kill -9` of the app process at **10** points spread over download, check and install, **When** the app restarts each time, **Then** `active.json` references only complete files whose SHA-256 matches, every partial directory is deleted within **10 s** of the start, and the map, routing and search work from the last complete state.
19. **Given** new files are installed, **When** the consumers switch, **Then**:
    - search: the next query uses the new file (a query in flight finishes on the old one)
    - map, not guiding: the map switches within **5 s** with a style reload that keeps the camera position and zoom
    - map, during guidance: at the first style load after guidance ends, or at the next app start
    - routing: after guidance ends (NAV-021 AC 17)
    - old version files are deleted within **60 s** after no consumer uses them
20. **Given** the pack, **When** the app's files are inspected, **Then** the pack lives under `noBackupFilesDir` (excluded from cloud backup), and the app requests **no** storage permission for it.

### F. Automatic updates (D165, D166, D167)
21. **Given** an installed pack, **When** the app is installed or updated, **Then** a periodic worker is scheduled every **24 h** with the constraints Wi-Fi (`UNMETERED`), battery not low and storage not low. It fetches the manifest with `If-None-Match`; a **304** ends the check with **0** file requests.
22. **Given** the manifest lists files to fetch, **When** the worker runs on Wi-Fi, **Then** they are downloaded and installed in the background **without any dialog** (the progress notification of AC 12 may show at low importance, and **no** completion notification is shown); `routing` and `search` are fetched in the same job; the Settings data dates (AC 39) change within **5 s** after the install.
23. **Given** a phone on mobile data only for **48 h** (emulator with a metered network and a shortened worker period in a test build), **When** automatic checks run, **Then** **0** pack file requests are sent (manifest requests are allowed).
24. **Given** a manifest with an unknown `pack_schema`, **When** it is read, **Then** **no** file of it is offered or downloaded, and the installed pack stays. **Given** a manifest whose `routing.format.graph_builder` is not on the allow-list, **When** it is read, **Then** the routing file is not offered or downloaded and the installed routing file stays, while compatible `tiles` and `search` files still update. On a first install the compatible kinds are installed, and routing works online only until a compatible routing file is published.
25. **Given** the manifest lists a file whose `sha256` differs from the installed one but whose `version` is **older** (the server republished an older manifest after a data rollback, NAV-020 AC 28), **When** the check runs, **Then** that file is downloaded and installed like any update.
26. **Given** files to fetch exist, **When** the user taps OF16 «Шинэчлэх» in «Тохиргоо», **Then** it runs as a user-started download (AC 6, AC 8–15).

### G. The 14-day routing + search offer over mobile data (D165, D200)
27. **Given** all of these hold: the app is in the foreground; the network is mobile data (validated, metered); the older `data_timestamp` of the installed `routing` and `search` files is more than **14 × 24 h** before the device clock; the current manifest has files to fetch among `routing` / `search`; guidance is not running; the offer was not declined in the last **7 × 24 h**; and it has not been shown in this foreground session, **When** these conditions are met, **Then** the offer shows OF17 and OF18, with `{size}` = Σ `download_bytes` of the `routing` / `search` files to fetch (about 33 MB with today's sizes), and the buttons OF16 «Шинэчлэх» and OF5 «Дараа». `tiles` is **never** part of this offer.
28. **Given** the 14-day offer, **When** the user taps «Шинэчлэх», **Then** only the `routing` / `search` files to fetch are downloaded over mobile data with **no** second confirmation (the offer is the D164 confirmation for this download), with progress as AC 12.
29. **Given** the 14-day offer, **When** the user taps «Дараа», presses Back or dismisses it, **Then** it is not shown again for **7 × 24 h** from that moment (D200). After that it is offered again while the AC 27 conditions hold. The time of the last decline survives app restarts.
30. **Given** the routing and search data become fresh (an update on Wi-Fi, or `data_timestamp` within 14 days), **When** the conditions are checked, **Then** the offer is not shown.

### H. The map from the installed pack (item 4, merged; D163, D170)
31. **Given** a `tiles` file is installed, **When** the map shows (with or without network), **Then** the style's tile source is `pmtiles://file://<pack dir>/<fileVersion>/basemap.pmtiles`, and during a **5-minute** browse session over UB and the countryside **0** requests go to `/tiles/basemap.pmtiles` (network capture).
32. **Given** airplane mode and an installed pack, **When** the camera moves to zoom 14 at P1, Darkhan, Khovd, Choibalsan, Ölgii and Dalanzadgad, **Then** at each place roads and place labels render within **3 s** with no blank tiles, labels follow `name:mn` → `name` → `name:en`, and zooms above 14 overzoom as online.
33. **Given** any map screen with the pack map, **When** it shows, **Then** «© OpenStreetMap contributors» and the ESA WorldCover credit are visible exactly as today (CLAUDE.md rule 8, NAV-002 G7 rule).
34. **Given** no `tiles` file is installed (no pack, before the first install, or after delete), **When** the map shows, **Then** it uses the online PMTiles as today, and without network the NAV-005 behaviour applies unchanged (AC 54 there).
35. **Given** a new `tiles` version is installed, **When** the map switches (AC 19), **Then** **0** tile decode errors are logged by MapLibre in the **60 s** after the switch while panning over UB, because every version has its own file path.

### I. Delete
36. **Given** an installed pack and no guidance, **When** the user taps OF21 and confirms OF22 with «Устгах» (OF23), **Then** within **5 s** every pack file and `active.json` are deleted, OF28 disappears and the space is freed, the map switches to online PMTiles within **5 s**, and search and routing behave as without a pack (NAV-021 AC 13). **Given** guidance is running, **When** the user confirms, **Then** the search file is deleted at once, and the map and routing files stay in use until guidance ends and are deleted within **10 s** after it ends.
37. **Given** the OF22 confirmation, **When** the user taps «Цуцлах» (OF10) or Back, **Then** nothing changes.
38. **Given** the pack was deleted, **When** periodic checks run, **Then** **0** pack files are downloaded (automatic updates only update installed kinds), and the first-launch offer is not shown again (AC 2). OF2 in «Тохиргоо» downloads it again.

### J. Data dates and the indicator scope
39. **Given** an installed pack, **When** «Тохиргоо» shows OF19, **Then** there is one OF20 line per installed kind, with `{date}` = the file's `data_timestamp` converted to the Asia/Ulaanbaatar calendar date as `YYYY-MM-DD`. The three dates may differ (monthly map, weekly routing and search, an older search dump date).
40. **Given** the map comes from the pack, **When** any map screen shows, **Then** **no** "offline" indicator is shown on the map itself (D201 covers routes and search results, NAV-021 AC 27–28 and NAV-023); the user sees the map's age in the AC 39 data dates.

### K. Notifications and permissions
41. **Given** a user-started download, **When** it finishes, **Then** OF11 is shown (a notification when the app is in the background, an in-app message for **3 s** when it is in the foreground); when it fails, OF12 with «Дахин оролдох». Completed automatic updates show **no** notification. **Given** the notification permission is denied (Android 13+), **When** a download runs, **Then** it still runs and its progress shows in «Тохиргоо»; this story asks for **no** new permission.

### L. Privacy and network
42. **Given** a full session (first install, an automatic update, the 14-day offer, delete), **When** all requests are captured, **Then** pack requests go only to the configured gateway base URL (`/packs/mn/…`, openapi 0.6.0), and carry **no** coordinates, device or user identifiers, and no auth; the `User-Agent` contains only the app name and version.
43. **Given** Logcat, files and preferences after that session, **When** they are scanned, **Then** **0** coordinates (regex `-?\d{1,3}\.\d{4,}`) are found, and the only stored pack state is `active.json`, the offer flag, the time of the last 14-day decline and WorkManager's own records.

### M. Strings and accessibility
44. **Given** the Android resources, **When** the NAV-005 AC 61 checks run, **Then** they pass: every new `mn` value matches a glossary section 2.7 term exactly (placeholders kept literally), `en` values exist, and no user-facing literal is hard-coded.
45. **Given** TalkBack and font scale **200 %**, **When** the offer, the Settings section and the dialogs (OF13, OF17 / OF18, OF22) are used, **Then** every control has its glossary name, touch targets are ≥ **48 × 48 dp**, text contrast is ≥ **4.5:1** in both themes, progress changes are announced at most once every **10 s**, and no text is truncated mid-word.

### N. Verification
46. **Given** the deliverables, **When** QA reports, **Then** the report covers JVM / Robolectric tests against a mock `/packs/` server for sections A–G, I and J, and these real-phone checks on the PO's Android phone: a full first install on Wi-Fi, the OF13 path on mobile data, a resume after airplane mode at about 50 %, the airplane-mode map of AC 32, an offline route with NAV-021, delete. Checks that could not run are listed as **not verified** with the reason.
47. **Given** NAV-020 is not yet published on a server, **When** this story is developed and tested, **Then** a pack produced by the NAV-020 pipeline (or its test configuration) is served by a local static server or a separate test gateway (never the shared dev stack at `http://127.0.0.1:8080`), and the debug build's packs base URL comes from a build property or an uncommitted local file (no hostname or IP in the repo; loopback allowed in debug).

## Edge cases
- **No network on first launch:** no offer (AC 3); the download stays in «Тохиргоо».
- **Wi-Fi without internet** (captive portal, not validated): not Wi-Fi for this story; the download waits (OF9).
- **Phone hotspot** that Android marks metered: treated as mobile data, so OF13 asks (AC 8).
- **Interrupted download** (Wi-Fi drops in a ger district, lift, power cut): resume (AC 14), reboot (AC 15).
- **Server publishes a new pack during a download:** retired file → 404 → manifest re-read (AC 15); files of the last 3 manifests stay on the server (NAV-020 AC 16).
- **Corrupted or wrong file:** AC 16–17; the old pack keeps working.
- **Storage full** (32 GB phones with photos): AC 6–7 with the space to free.
- **Device clock wrong** (set years back): the 14-day check may never fire, or fire early; downloads and checksums are not affected. Not handled further.
- **Pack too new for the app** (`pack_schema` or `graph_builder` unknown): AC 24; the user keeps the installed pack.
- **Data rollback on the server:** AC 25.
- **Guidance running during an update or delete:** routing and map switch after guidance (AC 19, AC 36); no prompt is shown during guidance (AC 1, AC 27).
- **Off-route or GPS loss during a download:** guidance is unaffected; NAV-005 rules apply.
- **Countryside driver who never sees Wi-Fi:** the first install needs one OF13 confirmation (about 120 MB); after that, the 14-day offer keeps routing and search fresh with about 33 MB; the map stays at its install date until Wi-Fi.
- **Cyrillic / Latin search:** not applicable here (NAV-023).
- **Winter:** low battery in the cold blocks automatic updates (battery-not-low constraint); user-started downloads still run.

## Data dependencies & risks
| # | Risk | Impact | Mitigation / owner |
|---|---|---|---|
| R1 | **Storage on low-end phones:** about 202 MB installed, and about 338 MB free needed for a first install | Some users cannot install | AC 6 with the exact space to free; z0–13 is a possible later storage saver (D170 chose z0–14). PO |
| R2 | **OEM battery savers** stop or delay WorkManager on some phones (NAV-012 R list) | Downloads stall, updates late | Resume (AC 14), periodic retry, data dates visible (AC 39). Mobile, QA (real-phone checks) |
| R3 | **Mobile data cost** for users without Wi-Fi | Surprise charges | Per-download confirmation with the size (AC 8), routing + search only in the 14-day offer (AC 27). PO-decided (D164, D165) |
| R4 | **Map older than routing** (monthly vs weekly, D166) | A new road is routable but not drawn on the offline map | Data dates per kind (AC 39); "offline" indicator on routes and results (D201). PO-accepted |
| R5 | **OSM data quality** (names, addresses, countryside coverage) is in the pack unchanged | Offline map and search no better than online | Backlog data-quality risks |
| R6 | **Bandwidth cost** of about 0.23 GB per active user per month on the pack host | NAV-009 sizing | Per-file cadence and skip of unchanged files (AC 22); delta spike parked (D196) |
| R7 | **ODbL:** the pack is a distributed derivative database | Licence breach | Attribution and licence line in the pack section (AC 4); the licences screen is D195 (before the first external release) |

## Out of scope
- Building and publishing the files (NAV-020); on-device routing (NAV-021); offline search (NAV-023).
- A pause control for downloads (cancel plus automatic resume only; Open question 2).
- Per-region packs, choosing a storage location (SD card), z0–13 storage saver.
- Delta updates (D167, D196).
- The in-app licences screen (change on NAV-005, D195).
- iOS (D162).
- Showing an "offline" indicator on the map (D201 is for routes and search results).

## Open questions
Non-blocking; each has a working assumption in the AC.
1. **14-day offer during guidance.** Options: (a) never during guidance; shown at the next foreground moment outside guidance (AC 27); (b) also during guidance. *Recommendation: (a).* A driver should not answer a dialog while driving (same reasoning as the NAV-011 typing lock, D65).
2. **Pause control for downloads.** Options: (a) no pause; cancel and automatic resume only (as written); (b) add a pause button (one more glossary term). *Recommendation: (a).* Resume after a network drop is automatic, and cancel keeps the UI simple.
3. **App update counts as first launch** (AC 3, BA reading of D169). Options: (a) yes, existing installations get the offer once on Wi-Fi after the update; (b) no, Settings only. *Recommendation: (a).* Early testers would otherwise never learn about the pack.

## Traceability
| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC1–AC4 | UX: offline map screen spec (TBD): first-launch offer, Settings section | `getOfflinePackManifest` | PackManager, Settings section (mobile, TBD) | Robolectric UI tests | D169; Open question 3 |
| AC5–AC7 | UX spec (sizes, OF15) | `getOfflinePackManifest` | storage check (mobile) | JVM tests with fake allocatable bytes | ADR-0017 §5 |
| AC8–AC11 | UX spec (OF13 dialog, OF9) | `getOfflinePackFile` | network rules (WorkManager) | Robolectric with network-type fakes; device OF13 path | D164 |
| AC12–AC15 | UX spec (progress, notification) | `getOfflinePackFile` (200, 206, 404, 416, 429) | downloader (mobile) | MockWebServer tests; device resume check | openapi 0.6.0 |
| AC16–AC20 | — | — | verifier, installer, consumer switch (mobile) | corruption and kill tests | ADR-0017 §5; NAV-021 AC 17, 29 |
| AC21–AC26 | — | `getOfflinePackManifest` (304), `getOfflinePackFile` | periodic worker (mobile) | WorkManager test harness | D165, D166, D167; NAV-020 AC 28 |
| AC27–AC30 | UX spec (14-day offer) | `getOfflinePackFile` | offer logic (mobile) | JVM tests with fake clock and network | D165, D200; Open question 1 |
| AC31–AC35 | map-style (unchanged tokens); NAV-005 screen spec | — (no `getBasemapPmtiles` while installed) | map source (mobile; ADR-0016 `pmtiles://file://`) | network capture; device airplane-mode check | D163, D170; item 4 merged |
| AC36–AC38 | UX spec (delete dialog) | — | delete (mobile) | Robolectric tests | |
| AC39–AC40 | UX spec (data dates) | — | Settings section (mobile) | Robolectric tests | D166, D201 |
| AC41 | UX spec (notifications) | — | notifications (mobile) | Robolectric notification tests | |
| AC42–AC43 | — | `getOfflinePackManifest`, `getOfflinePackFile` | network and storage hygiene | capture and log scan | D9 unchanged |
| AC44–AC45 | UX spec (copy, accessibility) | — | resources | glossary check, TalkBack check | glossary section 2.7 |
| AC46–AC47 | — | — | debug packs base URL property | QA report, device checklist | isolation rules |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-04 | Offline spike follow-up §9 items 3 and 4 (triage log 2026-10-04; PO "all as recommended", [D192](../decisions.md), [D197](../decisions.md), [D200](../decisions.md), [D201](../decisions.md)) | Story written: 47 AC in sections A–N (entry points, storage, network rules, download and resume, integrity and atomic install, automatic updates, 14-day offer, map from the pack, delete, data dates, notifications, privacy, strings, verification). Item 4 merged as section H. Glossary section 2.7 (OF1–OF29) added with this story. Status `ready` | Launch-blocking (D159): without an installed pack there is no offline map, routing or search |

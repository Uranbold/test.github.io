---
id: NAV-019
title: Android demo mode (separate demo build, no backend) that replays recorded Ulaanbaatar routes through the real NAV-005 guidance engine with a simulated location, for the PO's real-phone test
phase: 1
priority: should       # BA MoSCoW proposal; triage proposed P1 / standard (triage log 2026-10-04). PO to confirm (Open question 9)
size: M
needs_design: true     # Android route picker, demo badge, pause control (small delta on the NAV-005/011/012/018 screens)
needs_backend: false   # no service, gateway or openapi.yaml change; no backend at all at runtime
needs_mobile: true     # Android only (mobile/android); the web counterpart is NAV-017
status: ready          # AC written against the proposed defaults R1–R7; none blocks UX or the architect. Confirm R1–R7 (Open questions 1–8) before the mobile build
---

# NAV-019: Android demo mode with simulated replay through the real guidance engine

## Story
As a **UB commuter by car**, represented in this demo by **the PO on the PO's own Android phone**, I want **to install a separate demo build of our Android app next to the normal one, pick one of the recorded Ulaanbaatar routes and let the real guidance engine drive it with a simulated position (real banner, real Android voice or the fallback chime, real notification, lock screen and background behaviour) with no server at all**, so that **I can judge guidance, voice, notification, lock screen, Bluetooth audio, phone calls and battery savers on a real Android phone now, before any backend host is reachable from a phone (NAV-008)**.

Secondary personas (what the PO checks on their behalf):
- **Taxi / delivery driver:** guidance keeps running and talking with the screen off, after a swipe-away, during a phone call and through the car's Bluetooth (NAV-012 behaviour on the PO's phone).
- **Pedestrian:** the walking replay R2 with the «Явган» prompt schedule and the lock-screen view.
- **Tourist (English UI):** switches the app to English and hears an English voice (or the chime); place names stay Cyrillic (as D11).
- **Intercity / countryside driver:** not covered by the reference routes (all three are paved UB streets). Weak signal is approximated by the airplane-mode run (AC 34).

## Context
- **Request:** PO chat, 2026-10-04 (triage log 2026-10-04, "NAV-019 Android demo mode"). The PO has an **Android phone** as well as the iPhone, and NAV-008 staging is still not available. **The PO approved option B ("Ok", [D154](../decisions.md))**: a demo build of the real Android app that replays recorded UB routes through the real NAV-005 guidance engine with a simulated location along the recorded track and **no backend at all**. This is the Android counterpart of NAV-017 (web demo mode, ADR-0011), which the PO liked ("three demo simulation works great").
- **Re-opens** the 2026-10-01 triage item "Android debug app demo mode", which was closed as superseded by NAV-017 because the PO's phone was an iPhone. The 2026-10-01 runtime "server address" setting and the public APK download are **dropped**: there is no backend, and the APK goes to the PO by direct file transfer only.
- **Light process ([D155](../decisions.md), applying [D108](../decisions.md)):** no long QA run. The orchestrator does a quick check (build, unit tests, the no-request and repository scans, AC 42), and **the PO's real-phone test is the QA** (AC 43–44). Full QA runs before any public release.
- **Proposed defaults R1–R7 (orchestrator, 2026-10-04).** The AC below are written against them. **They are proposed defaults, PO to confirm, not PO decisions** (Open questions 1–7). If the PO changes one, the affected AC change through the BA before the mobile build.
- **What the demo must reuse unchanged (the point of the story):** the NAV-005 guidance engine (Ferrostar core and the ADR-0009 app rules: snapping, step advance, banner text from ADR-0008, voice text and prompt schedule, the D23 chime and notice A1, arrival), the Android TTS path (NAV-005 AC 36–39), the foreground service and rich notification (NAV-005 AC 15, NAV-012 section A), the lock-screen view, swipe-away, battery hint, Bluetooth and call handling (NAV-012 sections B, C, E, F, G), the route preview (NAV-005 AC 6, NAV-011) and the turn list «Маршрутын заавар» (NAV-018 section D). **Only the location source (simulated) and the network layer (none) differ.** NAV-005, NAV-011, NAV-012 and NAV-018 AC are **not changed** by this story; where the demo build behaves differently, this story says so for the demo build only.
- **What exists today (2026-10-04):** GPX replay providers and the fake TTS exist **only** in `src/test` / `src/debug` (ADR-0009 §10, NAV-005 AC 72) and are excluded from release builds (NAV-005 AC 61). Build types are `debug` (`applicationIdSuffix ".debug"`, default gateway on loopback) and `release` (not minified; R8 keep rules are ADR-0013 B-A8, not done yet). Glyphs and sprites are already bundled as assets (ADR-0009 §6). MapLibre Native is 13.6.1 and Ferrostar 0.57.0 (`libs.versions.toml`). The NAV-017 demo route manifest is `web/src/demo/routes.manifest.json`.
- **Other decisions that apply:** [D17](../decisions.md) / [D91](../decisions.md) (no release to external users before NAV-007; **not narrowed for this story**: [D116](../decisions.md) covers the web demo only), D11 (Cyrillic labels in the English UI), D23 / [D73](../decisions.md) (chime plus A1 without a Mongolian voice), D35 (public repo: no secrets, hostnames or IPs), D59 (Mongolian on first launch), D62 (no Google Play services needed), D64 (placeholder application ID), D67, D68, D120 («Автомат» = sunrise/sunset), D147 (NAV-018 «Эхлэх» only from the device start; see AC 10 for the demo build).
- **Coordination (binding):** never stop, restart or rebuild the shared dev stack at `http://127.0.0.1:8080`; this story needs no live backend. No secrets, keystores, APKs, tile archives, hostnames or IPs in the repo. NAV-018 edits shared Android files in its own run: read the latest file first, make small targeted edits, never revert another story's changes (order: Open question 10).

### Demo routes (same set as NAV-017)
| # | Picker entry (names from the manifest) | Mode | Recorded response (route) | Track | Replay at 1× |
|---|---|---|---|---|---|
| R1 | «Сүхбаатарын талбай» → «Зайсан Голден Вилл» | «Машин» | `mobile/android/app/src/test/resources/routes/p1-p3-car-mn.json` | `tests/gpx/nav005/G1.gpx` | 308 s |
| R2 | «Сүхбаатарын талбай» → «Хаан банк» | «Явган» | `mobile/android/app/src/test/resources/routes/p1-p2-walk-mn.json` | `tests/gpx/nav005/G5.gpx` | 932 s |
| R3 | «Сонгосон цэг» → «Золтамир» | «Машин» | `mobile/android/app/src/test/resources/routes/g8-roundabout-car-mn.json` | `tests/gpx/nav005/G8.gpx` | 787 s |

- The entries are the `picker: true` rows of `web/src/demo/routes.manifest.json` (ids `r1`, `r2`, `r3`). **Place names are map data, not UI strings**, as in NAV-017 ("Demo routes"): they come from the manifest, follow `name:mn` → `name` → `name:en`, and show in Cyrillic in both UI languages. The Android glossary check skips them.
- **Not offered in the picker:** G4 (test-only arrival track; tests may use it), G2 (off-route; there is no backend to reroute, R5), G3, G6, G7, G9, G10.

### Terms used in this story
- **Demo build:** the APK of the separate `demo` build type (or flavor, Open question 1) of `mobile/android` (AC 1). **Normal builds:** `debug` and `release`.
- **Replay:** the simulated drive from «Эхлэх» until arrival, «Дуусгах» or process end. **Replay clock:** time since «Эхлэх» (monotonic clock, `elapsedRealtime` or equivalent), excluding paused time. At **1×**, one second of track time is one second of replay clock. 1× is the only speed (Open question 4).
- **Simulated fix:** one GPX track point delivered to the guidance engine as a location (accuracy ≤ **5 m**, so it is a NAV-005 "good fix") at its track time.
- **Reference device:** the PO's Android phone. The PO records its model and Android version in the checklist (AC 43).
- **Quick check:** the orchestrator's light-QA check under D155 (AC 42).

### User-facing strings
Every Mongolian string comes from `docs/requirements/glossary.md`. **One new row:** W2 «Түр зогсоох» (pause the replay), added 2026-10-04 to glossary section 2.3 as `needs native review`, used only if the pause control of R4 is confirmed (Open question 4). Everything else is reused:

| Use in NAV-019 | Glossary row | `mn` | `en` |
|---|---|---|---|
| Demo badge, picker heading, launcher label of the demo build | W1 Demo mode | «Туршилтын горим» | "Demo mode" |
| Picker list name | N20 Choose a route | «Маршрут сонгох» | "Choose a route" |
| Modes | Travel mode | «Машин», «Явган» | "Car", "Walk" |
| Start / end the replay | Start / End navigation | «Эхлэх» / «Дуусгах» | "Start" / "End" |
| Pause / resume the replay | **W2 Pause replay (new, `needs native review`)** / A7 Continue | «Түр зогсоох» / «Үргэлжлүүлэх» | "Pause" / "Continue" |
| Recenter, voice button | Recenter, Voice on / off, A5 | «Байршил руу буцах», «Дууг хаах» / «Дууг нээх» | as NAV-005 |
| No Mongolian voice | A1 | «Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.» | as NAV-005 |
| Banner, voice, progress, arrival | NAV-005 "User-facing strings" (sections 2, 2.1, 2.2, 3) | as NAV-005, e.g. «Хүрэх цаг», «+{days} өдөр», «{n} метрт очих газартаа хүрнэ», «Та очих газартаа ирлээ» | as NAV-005 |
| Turn list in the preview | N5 Directions | «Маршрутын заавар» | "Directions" |
| Notification channel | Navigation (active guidance) | «Замчлал» | as NAV-005 |
| Unavailable and offline states | T4, N8, Reroute, No connection, Try again, Generic error | «Хайлт түр ажиллахгүй байна», «Маршрутын үйлчилгээ түр ажиллахгүй байна», «Маршрутыг дахин тооцоолж байна», «Интернэт холболт алга», «Дахин оролдох», «Алдаа гарлаа» | as NAV-005 / NAV-011 |
| Map and loading states, close | G1, G2, G6 | «Ачаалж байна…», «Газрын зургийг ачаалж чадсангүй», «Хаах» | as NAV-002 |
| Settings reachable from the picker | Settings, Language, theme rows, B1, B2 | «Тохиргоо», «Хэл», «Өдрийн горим», «Шөнийн горим», «Автомат», «Батарейн хязгаарлалт», B2 | as NAV-012 |
| Unknown end point (manifest data) | T6 Selected point | «Сонгосон цэг» | "Selected point" |
| Attribution | OSM attribution | «© OpenStreetMap contributors» | same |

## Acceptance criteria

### A. Build variant and installation (R1, R2, R3)
1. **Given** a clean checkout, an Android SDK outside the repo and the Gradle property `nav.demoTilesFile` set to the absolute path of a local `.pmtiles` file, **When** `./gradlew :app:assembleDemo` (task name per the build-type choice, named in `mobile/android/README.md`) runs in `mobile/android`, **Then** an APK is produced that: has the application ID of the normal app plus the suffix `.demo`; is signed with the local debug key (nothing signing-related is committed); is **not** debuggable (`android:debuggable` absent or false in the merged manifest); and has the launcher label «Туршилтын горим» (`en` "Demo mode", W1).
2. **Given** the debug build and the demo build of the same commit, **When** both are installed on one phone (`adb install` or a file manager), **Then** both install side by side, both launcher icons are present, and opening one never opens, changes or deletes the other's settings or data (different application IDs). Uninstalling the demo build leaves the debug build working.
3. **Given** neither `nav.demoTilesFile` nor `nav.demoTilesUrl` is set, **When** a demo build task runs, **Then** it fails within the Gradle configuration or pre-build step with one message naming both properties and pointing to the README. **When** `assembleDebug`, `assembleRelease` (with its existing gateway rule) or `testDebugUnitTest` run without them, **Then** they are **not** affected.
4. **Given** the demo build output, **When** its assets are listed, **Then** they contain the manifest entries `r1`, `r2`, `r3` with exactly their 3 route JSON files and 3 GPX tracks (byte-identical to the repo files named in "Demo routes"), the demo route manifest (or the subset the app reads), and the tile archive from `nav.demoTilesFile` (absent when `nav.demoTilesUrl` is used). These files are copied at build time by a Gradle task from their repo paths: **0** copies of them are committed under `mobile/` (apart from the route JSON files, which already live in `src/test/resources`).
5. **Given** the debug and release APKs, **When** they are inspected, **Then** they contain **0** demo classes (the demo package or source set named in the README), **0** GPX files, **0** demo route assets and **0** `.pmtiles` assets. The demo build contains the real Android TTS path and **no** fake TTS.

### B. Route picker (R4)
6. **Given** the demo build is opened (first launch or later), **When** the first screen is ready, **Then** within **2 s** a route picker is shown over the map with the heading «Туршилтын горим», a list named «Маршрут сонгох» and exactly the entries R1, R2 and R3, each with the origin and destination names from the manifest, the mode label («Машин» or «Явган»), the distance (NAV-004 AC 23 format) and the duration (NAV-004 AC 24 format) of its recorded response. The app opens in Mongolian (D59). «© OpenStreetMap contributors» is visible.
7. **Given** the picker, **When** the user looks for settings, **Then** the existing settings «Тохиргоо» are reachable from the picker in at most **2** taps, with the language switch «Хэл», the theme options and the battery row «Батарейн хязгаарлалт» (NAV-012 AC 28), all working as in the normal app.
8. **Given** an entry's packaged route or track cannot be read or parsed (test with a corrupted asset), **When** it is selected, **Then** within **1 s** «Алдаа гарлаа» is shown in the picker, no preview opens, and the other entries keep working.

### C. Route preview (R4)
9. **Given** the picker, **When** an entry is selected, **Then** within **1 s** the normal route preview (NAV-005 AC 6, NAV-011, NAV-018 layout) opens for the recorded response with **0** route requests: one route line, start and destination markers, the start and destination fields showing the manifest names, the recorded mode selected, distance, duration and «Хүрэх цаг HH:MM», and the turn list «Маршрутын заавар» with one row per step of the recorded route, built by the NAV-018 rules (AC 18–19, 22–23).
10. **Given** the demo preview, **When** «Эхлэх» renders, **Then** it is **enabled** although the start is not the device position, and the NAV-018 hint O1 is **not** shown. (Demo build only. In normal builds NAV-018 AC 15 / D147 is unchanged.)
11. **Given** the demo preview, **When** the user activates a control that would need a new route (another mode tab, swap, editing the start or destination field, «Эхлэх цэг болгох» / «Очих газар болгох» on a long-press card), **Then** **0** requests are sent and either the control is disabled or the existing routing state is shown (AC 31); the UX screen spec picks one per control. Back from the preview returns to the picker within **1 s**; selecting the entry again restores the recorded route.

### D. Replay through the real guidance engine
12. **Given** the demo preview, **When** «Эхлэх» is activated, **Then** within **1 s** the normal guidance screen is shown, the NAV-005 foreground service runs, the recorded route is navigated with **0** route requests, and the first simulated fix is the first track point. Simulated fix *i* is delivered when the replay clock reaches its track time (± **200 ms** at 1×, JVM test with a virtual clock).
13. **Given** a replay, **When** simulated fixes are delivered, **Then** they go through the **same** guidance path as real fixes in the normal app (Ferrostar core, ADR-0009 snapping and step advance, banner, progress, camera, recenter, prompt schedule, arrival). Only the location source differs. **0** location requests are registered with the platform `LocationManager` or any fused provider during the whole demo session (Robolectric shadow check), so the real GPS position is never shown or used.
14. **Given** G1 (R1), G5 (R2) and G8 (R3) in `mn` and `en`, **When** each is replayed through the demo replay path on the JVM (host Ferrostar, virtual clock, fake TTS in the test only), **Then** the sequence of (manoeuvre index, voice text) equals the rows of `tests/gpx/nav005/golden/voice-golden.tsv` for that track and language **exactly**, each prompt starts within **± 2 s** of the row's `t_s`, and the banner sequence equals the one the NAV-005 replay test produces for that track. A difference is a defect, never a silent test change.
15. **Given** a replay, **When** the banner, progress and camera render, **Then** NAV-005 AC 21–28, 30 and 31 hold with the simulated position as "the position": banner never blank, ADR-0008 text only (the NAV-005 AC 27 sentinel check also passes through the demo path), heading up, «Байршил руу буцах» after a map gesture with following resumed after **15 s**.
16. **Given** a replay, **When** the screen is turned off, the user presses Home, switches app, or removes the app from Recents (NAV-012 AC 13), **Then** the replay **continues** in the foreground service: simulated fixes keep arriving at the track rate with no gap longer than **2 s**, prompts play, and the notification keeps updating. (Unlike NAV-017 AC 15, the replay does not pause when the app is hidden.) On the reference device, the R3 replay with the screen off ends within **787 s ± 15 s** of «Эхлэх» (AC 43).
17. **Given** the replay runs, **When** simulated fixes arrive, **Then** the NAV-011 typing lock and the NAV-012 «Автомат» theme use **no** device location: the typing lock never engages (it has no device fixes), and «Автомат» computes sun times for the current simulated position (P1 before the first fix). The badge «Туршилтын горим» is visible on the picker, the preview and the guidance screen, without covering the banner, the progress area or the attribution.

### E. Voice path (real Android TTS)
18. **Given** «Эхлэх» with voice on, **When** the replay starts, **Then** the voice is chosen exactly as NAV-005 AC 38 (TTS initialises within **3 s**, `mn` / `mn-MN` must be available for the Mongolian UI, an English voice for the English UI), and the depart prompt (or its chime) starts within **2 s** (NAV-005 AC 35).
19. **Given** no usable Mongolian voice (expected on most phones), **When** the replay runs in the Mongolian UI, **Then** NAV-005 AC 39 holds: Mongolian text is never spoken by a non-Mongolian voice, every prompt plays the chime (≤ **1 s**), and the notice «Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.» is shown once per replay for at least **5 s** without covering the banner, «Дуусгах» or the attribution.
20. **Given** a usable voice, **When** prompts play, **Then** NAV-005 AC 36 (navigation-guidance audio attributes, transient focus with ducking, released within **1 s**) and AC 37 (voice button «Дууг хаах» / «Дууг нээх», remembered) hold, and the notification voice action works as NAV-012 AC 3.
21. **Given** a replay, **When** the UI language is switched (settings reachable during guidance as in the normal app), **Then** NAV-005 AC 60 holds: texts switch within **1 s**, the next prompt uses the new language and voice (AC 38–39 re-evaluated), **0** prompts are repeated and **0** requests are sent.

### F. Pause, stop and arrival (R4)
22. **Given** a replay, **When** «Түр зогсоох» (W2) is activated, **Then** within **1 s** the replay clock stops, the puck stays, any utterance or chime stops, **0** prompts are produced, remaining distance and «Хүрэх цаг» freeze, and **0** «GPS дохио тасарлаа» messages appear however long the pause lasts. The button then reads «Үргэлжлүүлэх». **When** «Үргэлжлүүлэх» is activated, **Then** within **1 s** the replay resumes from the same track time with **0** repeated prompts and no depart prompt. *(Only if the pause control of R4 is confirmed, Open question 4.)*
23. **Given** a replay, **When** «Дуусгах» is activated on the screen, over the lock screen or in the notification, **Then** NAV-005 AC 19 holds (within **2 s** the service stops, the notification is removed, utterances stop, **0** requests), and the **picker** is shown with no entry selected (over the lock screen: NAV-012 AC 9).
24. **Given** a replay, **When** the snapped simulated position is within **30 m** of the route end or has passed it, **Then** NAV-005 AC 55 holds: «Та очих газартаа ирлээ» or the side variant «Таны очих газар баруун талд байна» / «Таны очих газар зүүн талд байна», spoken once (or one chime), preceded by «{n} метрт очих газартаа хүрнэ» when the schedule has one; **0** further prompts; within **10 s** the service stops; the arrival panel with «Хаах» stays until closed, and «Хаах» returns to the picker. For G1, G5, G8 and the test-only G4, exactly **1** arrival message is produced (JVM).
25. **Given** the track ends without arrival having been detected, **When** the last fix has been delivered, **Then** within **2 s** the replay behaves as after «Дуусгах» (AC 23). (For R1–R3 arrival is expected; a missing arrival is a data defect.)

### G. Notification, lock screen and background (NAV-012 behaviour)
26. **Given** Android 13 or later, **When** «Эхлэх» is activated for the first time in the demo build, **Then** the OS notification permission is requested once (NAV-005 AC 13). If it is denied, the replay still starts and works without the notification.
27. **Given** a replay with the notification permission granted, **When** the notification in channel «Замчлал» is shown, **Then** it has the NAV-012 AC 1 content and actions (manoeuvre icon, distance equal to the banner, instruction, street, «Хүрэх цаг», «Дуусгах», voice toggle) and follows NAV-012 AC 2–6. The app name shown in the notification header is the demo launcher label (AC 1), so the demo build is recognisable on the lock screen.
28. **Given** a replay and the guidance screen in front, **When** the screen is turned off and on again, **Then** NAV-012 AC 8–12 hold (guidance screen over the lock screen without unlocking; leaving it needs unlocking; arrival panel over the lock screen; no app over the lock screen after the replay ends).
29. **Given** a replay, **When** a Bluetooth, wired or USB output connects or disconnects, or a phone call (cellular or VoIP) starts and ends, **Then** NAV-012 sections F and G hold unchanged (prompts follow the platform output; during a call **0** prompts and chimes; one catch-up prompt after the call per NAV-012 AC 38), and the replay itself keeps running during the call.
30. **Given** the app process ends during a replay (OEM battery saver, crash, force stop), **When** the user opens the demo build again, **Then** the picker is shown within **2 s**, no restore notice and no restore notification appear, and nothing about the interrupted replay is stored (proposed default for the demo build, Open question 8: the NAV-012 restore record is not written in the demo build). The PO's checklist records that the replay was killed (AC 43).

### H. No backend: unavailable states and 0 network requests (R5)
31. **Given** the demo build, **When** search, reverse geocoding (coordinate card), typed coordinates or a new route request are reachable (at least through the preview fields, AC 11; the UX spec names the entry points that stay), **Then** **0** requests are sent and the existing state for the current connectivity is shown: with no validated network «Интернэт холболт алга»; otherwise «Хайлт түр ажиллахгүй байна» (search and reverse) or «Маршрутын үйлчилгээ түр ажиллахгүй байна» (routing), each with «Дахин оролдох», which again sends **0** requests. A typed coordinate pair still shows the «Сонгосон цэг» option (NAV-011 AC 7, it needs no request); what follows it uses these states.
32. **Given** a replay, **When** the guidance engine detects a deviation (test: the G2 track replayed through the demo configuration on the JVM; G2 is not in the picker), **Then** **0** route requests are sent, the banner shows «Маршрутыг дахин тооцоолж байна» with the secondary line «Маршрутын үйлчилгээ түр ажиллахгүй байна» (or «Интернэт холболт алга» without network, NAV-005 AC 48 / 50 wording), the replay continues along the track, and normal guidance resumes within **2 s** after the position is back within 50 m of the route.
33. **Given** a full scripted demo session (picker, R1–R3 to arrival, «Дуусгах» in one replay, pause and resume, a language and a theme switch, recenter, mute, the AC 31 entry points), **When** all network activity of the app is captured (JVM/Robolectric: the HTTP layer and MapLibre resource requests counted), **Then** **0** requests leave the app when built with `nav.demoTilesFile`. With `nav.demoTilesUrl`, the only requests are HTTP Range requests for that tile archive over HTTPS.
34. **Given** the demo build with bundled tiles on the reference device in **airplane mode** (Bluetooth may be on), **When** the PO runs R1 from «Эхлэх» to arrival, **Then** the map, banner, voice or chime, notification and arrival all work (AC 43).

### I. Tiles and attribution (R3)
35. **Given** the demo build with `nav.demoTilesFile`, **When** the picker, preview or guidance map is shown, **Then** the basemap is drawn from the bundled archive with the `docs/design/map-style.md` day or night flavor, the bundled glyphs and sprites, and labels by `name:mn` → `name` → `name:en`, with **0** network requests. The URL scheme that MapLibre Native 13.6.1 accepts (`pmtiles://asset://…`, or a copy to app storage and `pmtiles://file://…`) is the architect's finding and is named in the README. If the archive is copied to app storage, the copy happens once per installed version and takes ≤ **30 s** on the reference device, with «Ачаалж байна…» shown meanwhile.
36. **Given** the archive is missing, unreadable or not a PMTiles archive at runtime (test: corrupt asset), **When** the map loads, **Then** within **5 s** «Газрын зургийг ачаалж чадсангүй» is shown with «Дахин оролдох», and the picker still lists R1–R3.
37. **Given** any map screen of the demo build (picker, preview, guidance, arrival, the lock-screen view), in both themes, both languages, portrait and landscape, **When** it is rendered, **Then** «© OpenStreetMap contributors» is visible and intersects no other visible element (Compose test as NAV-005 AC 2).

### J. Strings, accessibility, privacy and regression
38. **Given** the Android resources after this story, **When** the NAV-005 AC 61 checks run, **Then** every new `mn` value matches a glossary term exactly (W1, W2 and the reused rows), the `mn` and `en` key sets are identical, and **0** user-facing literals are hard-coded. Place names from the manifest are data and are skipped.
39. **Given** TalkBack, **When** the picker and the pause control are used, **Then** every control has an accessible name from the glossary, each picker entry is read with its names, mode, distance and duration, touch targets are ≥ **48 × 48 dp**, and text contrast is ≥ **4.5:1** in both themes.
40. **Given** a demo session under Robolectric, **When** Logcat, files, DataStore and the notification are inspected, **Then** NAV-005 AC 67 holds: **0** coordinates (regex `-?\d{1,3}\.\d{4,}`) in logs or storage, and after the replay ends only the settings remain (voice, theme, language, orientation).
41. **Given** the repository after this story, **When** it is scanned, **Then** it contains **no** APK, **no** `.pmtiles` file, **no** keystore, **no** value for `nav.demoTilesFile` or `nav.demoTilesUrl`, and no hostname or IP other than loopback or documentation ranges (D35, NAV-005 AC 66). `mobile/android/README.md` documents both properties with placeholders only (`<path-to>.pmtiles`, `https://<host>/<path>.pmtiles`), and they may be set in the uncommitted `gateway.local.properties` or on the command line.

### K. Verification and the PO's phone test
42. **Given** light-QA mode (D155), **When** the orchestrator does the quick check, **Then** it records: `assembleDemo` (AC 1, with a local test archive) and `assembleDebug` pass; `./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required` passes, including the new tests for AC 3–5, 12–14, 22, 24, 31–33, 38 and 40 and the existing suite unchanged; the APK inspections for AC 1, 4 and 5; the repository scan for AC 41. Everything else is listed as **not verified in this environment** and left to the PO's test (AC 43).
43. **Given** `mobile/android/README.md`, **When** the section "Demo build (NAV-019)" is read, **Then** it has the build command and the tile properties (AC 1, 3, 41), the install steps for the PO (copy the APK by direct file transfer, allow installs from that source, install next to the debug build), a statement that the APK goes **only to the PO by direct file transfer, never to the public website, a store or any download link** (D17; Open question 1), and a **PO phone checklist** with a result column (pass / fail / note) for:
    - (a) phone model and Android version; the TTS engine set in **Settings › Text-to-speech output** (or the phone's equivalent) and whether a Mongolian voice is listed (input for NAV-007 AC 9);
    - (b) R1 in the Mongolian UI: banner texts readable, A1 shown or Mongolian speech heard, chime audible when there is no Mongolian voice;
    - (c) R1 in the English UI: English speech heard;
    - (d) R2 (walk) with the phone locked: lock-screen view, notification content and actions («Дуусгах», voice toggle);
    - (e) R3 with the screen off for the whole replay: prompts heard, ends within 787 s ± 15 s (AC 16);
    - (f) swipe-away from Recents during a replay (AC 16);
    - (g) Bluetooth car audio or headset: first word of 5 consecutive prompts audible (NAV-012 AC 33);
    - (h) a phone call during a replay: no prompt during the call, one catch-up prompt after it (AC 29);
    - (i) battery saver on, and the B2 hint on the preview (NAV-012 AC 26); whether the replay survives (AC 30);
    - (j) airplane-mode run of R1 (AC 34);
    - (k) pause and resume, «Дуусгах», arrival back to the picker (AC 22–24);
    - (l) the debug build still works after installing the demo build (AC 2).
44. **Given** the PO has installed the demo build, **When** the PO runs the AC 43 checklist on the reference device, **Then** the orchestrator records the PO's results and any defects in this story; defects found here stay in this story's fix loop (D108). Items the PO skips stay **not verified**.

## Edge cases
- **GPS lost / poor accuracy:** not simulated; the replay never uses the real GPS (AC 13), and pausing does not trigger «GPS дохио тасарлаа» (AC 22). Real-GPS checks stay with NAV-005 AC 73 once staging is reachable.
- **No network:** the normal case for the bundled-tiles build (AC 33, 34). States for features that would need the network: AC 31.
- **Off-route:** not expected on R1–R3; if the engine detects one, AC 32 (no reroute, existing unavailable line).
- **No result found:** search is unavailable (AC 31); a broken packaged route: AC 8.
- **Cyrillic/Latin search** ("Sukhbaatar" vs «Сүхбаатар»): search is unavailable in the demo build. Picker names are Cyrillic in both UI languages (data, D11).
- **Unpaved roads / intercity:** not covered by the reference routes (all paved UB streets). G9 is not offered because it never arrives.
- **Winter:** cold drains the battery faster during long screen-off replays; gloves make touch harder (48 dp targets, AC 39). «Автомат» switches to the night theme early on winter afternoons (sun times for the simulated UB position, AC 17).
- **Replay vs real driving:** the PO must not drive by the demo. The badge «Туршилтын горим» (AC 17) and the launcher and notification label (AC 1, 27) mark it as a simulation; the puck moves without the phone moving.
- **Two installs:** debug and demo builds side by side (AC 2). Starting guidance in both at once is not supported; Android audio focus decides which voice is heard.
- **Notification permission denied:** AC 26.
- **OEM kills the process:** AC 30 (back to the picker, nothing restored).
- **Language switched during a replay:** AC 21. **Theme switched or phone rotated:** NAV-005 AC 59, 64 hold.
- **Tapping «Эхлэх» twice quickly:** one replay only (no second depart prompt).
- **Phone without Google Play services:** nothing in the demo build needs them (D62); the simulated provider does not use the fused provider.

## Data dependencies & risks
| # | Risk | Impact on NAV-019 | Mitigation / owner |
|---|---|---|---|
| R1 | **PMTiles from assets in MapLibre Native 13.6.1** is unverified (the `pmtiles://asset://` scheme may not work) | Blank map in the demo build | Architect verifies; fallback is a one-time copy to app storage (AC 35); failure state AC 36 |
| R2 | **APK size with bundled tiles.** The Mongolia archive is about 112 MiB and more (NAV-017 R6, NAV-001 AC 12); the APK goes by file transfer | Slow transfer, storage use on the phone | Recommend a UB-area extract that covers R1–R3 with a margin (Open question 3); README gives the extract command; `nav.demoTilesUrl` as the alternative |
| R3 | **Android 14+ foreground-service prerequisite.** A service of type `location` (NAV-005 AC 15) needs a granted location permission **and** location services on; R6 proposes no location permission | The demo service would fail to start on Android 14+ under R6 as written | **Open question 6** (blocking for the mobile build only): request the permission as NAV-005 does, or use a different service type in the demo build. Architect design question DM-2 |
| R4 | **No Mongolian TTS voice on most Android phones** (device and platform risk, NAV-007 R1; an eSpeak NG `mn` voice may be installable, D82) | In the Mongolian UI the PO probably hears only chimes | D23 minimum (AC 19); the checklist records the evidence for NAV-007 AC 9 (AC 43 (a)) |
| R5 | **Recorded routes are a snapshot** (Valhalla 3.9.0, OSM at recording time); **synthetic GPX tracks** follow the geometry exactly with no noise | Smoother than real driving; streets may have changed | Accepted for a demo (as NAV-017 R1, R2); real GPS stays NAV-005 AC 73 |
| R6 | **Valhalla `mn-MN` narrative inside the recorded responses** (NAV-007 F1–F10) | Wrong text if it leaks | ADR-0008 text only; sentinel check through the demo path (AC 15) |
| R7 | **Unreviewed Mongolian guidance text** (NAV-007 not done) | Judged on unreviewed wording | The APK goes to the PO only (D17 respected, AC 43); W1, W2 and all reused rows stay `needs native review` or panel pending |
| R8 | **One reference device, one OEM.** Battery-saver, lock-screen and Bluetooth behaviour differs by OEM and Android version | The PO's result does not cover other phones | Accepted; NAV-012 AC 52 still lists the other families as not verified |
| R9 | **Shared Android files with NAV-018 in flight** (`MainActivity`, `NavRoot`, `strings.xml`, preview sheet, guidance service) | Merge conflicts or reverted lines | Demo code in its own source set; small targeted edits; order: Open question 10 |
| R10 | **`name:mn` coverage** of the manifest names and street names (NAV-005 R6) | Street lines missing; «Сонгосон цэг» for R3's origin | As NAV-017 R8 |
| R11 | **`maxspeed` coverage** (NAV-001 R8) | Recorded durations and «Хүрэх цаг» are optimistic | Accepted (traffic is Phase 3) |
| R12 | **R8 keep rules not done** (ADR-0013 B-A8) | A minified demo build may crash in Ferrostar/JNA | The demo build stays unminified, like the current release build (architect, DM-4) |

### Design questions for the architect (record in an ADR-0009 amendment or a new ADR; none blocks UX)
- **DM-1. Simulated location source.** How simulated fixes enter the same NAV-005 guidance path (a demo implementation of the app's location-source interface in the demo source set; Ferrostar's own simulated provider or the existing test GPX provider promoted to `src/demo`), with the replay clock owned by the foreground service so that AC 16 holds.
- **DM-2. Foreground-service type and permissions on Android 14+** (R3, Open question 6), and what the demo manifest declares.
- **DM-3. Network isolation.** How the demo build guarantees AC 33: no gateway URL, a short-circuiting HTTP layer that produces the existing unavailable states, and optionally removing `android.permission.INTERNET` from the demo manifest when `nav.demoTilesFile` is used.
- **DM-4. Build type vs flavor, signing and minification** (Open question 1, R12), the Gradle copy tasks for the routes and the archive, and the build-time failure of AC 3.
- **DM-5. PMTiles URL scheme** in MapLibre Native 13.6.1 (R1, AC 35).
- **DM-6. Process death in the demo build** (AC 30, Open question 8): skipping the restore record without touching the normal-build code path.

## Out of scope
- Any backend, gateway or `openapi.yaml` change; live search, reverse or routing; connecting the demo build to NAV-008 staging or to the dev stack.
- A runtime "server address" setting (from the 2026-10-01 item, dropped).
- Publishing the APK anywhere (website, store, download link) or giving it to anyone but the PO (D17).
- Off-route, reroute and GPS-loss simulation in the picker; real device location during the replay.
- Replay speeds other than 1×, seeking, user-recorded or uploaded GPX tracks (Open question 4).
- Restoring an interrupted replay (Open question 8).
- iOS (NAV-015) and any change to the NAV-017 web demo.
- Any change to NAV-005, NAV-011, NAV-012 or NAV-018 behaviour in the normal builds.
- Cloud TTS, recorded clips or the NAV-016 clip pack (D75, D76).

## Open questions
**None blocks UX or the architect.** Questions 1–8 are the orchestrator's **proposed defaults R1–R7 (PO to confirm)** plus one BA default; question 6 blocks the **mobile build** only. Question 9 is the priority, 10 the build order.

| # | Question | Options | BA recommendation | Blocking |
|---|---|---|---|---|
| 1 | **R1 Build** (proposed default, PO to confirm): a separate Gradle build type (or flavor) `demo`, not debuggable, signed with the local debug key, `applicationIdSuffix ".demo"` so it installs next to the debug app, launcher label showing a demo marker; APK to the PO only by direct file transfer (D17) | (a) build type `demo`, launcher label «Туршилтын горим» (W1, no new term); (b) flavor `demo` (doubles every variant: `demoDebug`, `demoRelease`, …); (c) a combined label such as "Map, demo" (needs a new glossary row) | **(a)**. One extra variant, no new term, and the notification header shows the same label (AC 27). Keep D17: the APK never leaves the PO | no (mobile build: yes) |
| 2 | **R2 Routes** (proposed default, PO to confirm): the same three picker routes as the web demo, packaged by a Gradle copy task from the repo paths, no copies committed under `mobile/` | (a) as proposed; (b) also offer G4 or other tracks | **(a)**. Same set as NAV-017, so the PO can compare web and Android directly | no |
| 3 | **R3 Tiles** (proposed default, PO to confirm): bundled offline via `nav.demoTilesFile` (local path, never committed); alternative `nav.demoTilesUrl` (HTTPS PMTiles on the PO's static site, never committed); demo build fails clearly if neither is set | (a) file, with a UB-area extract covering R1–R3; (b) file, with the full Mongolia archive; (c) URL only | **(a)**. Works in airplane mode (AC 34), smallest APK. (c) needs network and sends Range requests to the PO's host | no |
| 4 | **R4 Entry, speed and pause** (proposed default, PO to confirm): picker → normal preview → «Эхлэх» → simulated guidance → arrival back to the picker; 1× with 2×/4× "if NAV-017 has it"; pause/stop | (a) 1× only (NAV-017 has 1× only, its Out of scope and Open question 2), with pause «Түр зогсоох» (W2, new, `needs native review`) and resume «Үргэлжлүүлэх»; (b) (a) plus 2×/4× (new glossary label; timing AC stay 1×-only; notification and voice timing no longer realistic); (c) no pause, «Дуусгах» only | **(a)**. NAV-017 has no speed choice, so R4's condition resolves to 1×. A pause helps the PO set up Bluetooth or take a call mid-route (AC 22) | no |
| 5 | **R5 No backend** (proposed default, PO to confirm): search, reverse, typed coordinates and reroute show their existing unavailable states; 0 network requests; off-route tracks excluded from the picker, any detected deviation shows the reroute-unavailable state | (a) as proposed (AC 31–33); (b) hide every network-dependent entry point instead | **(a)**, with the UX spec free to hide individual entry points (AC 11, 31). The PO also sees how the real app looks when the service is down | no |
| 6 | **R6 Location permission** (proposed default, PO to confirm): not needed for the replay; notification permission (Android 13+) still requested as NAV-012. **Conflict found by the BA:** on Android 14+ a foreground service of type `location` needs a granted location permission and location services on (R3), so R6 as written needs a different service type | (a) R6 as proposed, and the demo build uses a service type without that prerequisite (for example `specialUse`; the demo is never on Play); (b) the demo build asks for location through the normal NAV-005 rationale and OS dialog and keeps the production `location` service type, but never reads fixes (AC 13); (c) (a) on Android 14+ only, `location` type below | **(b)**. The service, its type and the permission flow stay identical to the real app, so OEM battery-saver and lock-screen results transfer to production; the cost is one rationale and one OS dialog for the PO. If the PO prefers no location prompt, (a) | **yes, mobile build** |
| 7 | **R7 Strings** (proposed default, PO to confirm): everything through resources (mn default, en) with glossary terms, NAV-017 terms reused, new terms `needs native review`, OSM attribution on every map screen | (a) as proposed: one new row W2 «Түр зогсоох» (only if Open question 4 keeps the pause); (b) PO pre-reviews W2 first | **(a)**. Implement with `needs native review`; the PO may pre-review W2 on the review page (as D69). The panel checks that «Түр зогсоох» is not confused with the Avoid «Зогсоох» for ending guidance | no |
| 8 | **Process death in the demo build** (BA default, not one of R1–R7) | (a) no restore record in the demo build; after process death the picker opens (AC 30); (b) restore the replay at the same track time within the NAV-012 30 min window | **(a)**. A restore of a simulated drive tests little of NAV-012 section D (which needs a real position), and (a) tells the PO plainly that the OEM killed the app | no |
| 9 | **Priority** (triage proposed P1 / standard, 2026-10-04) | P1 / standard; P2 | **P1 / standard**, MoSCoW **should** in the backlog (a review tool for the PO, not product MVP; same mapping as NAV-017) | no |
| 10 | **Build order with NAV-018** (in its mobile build stage, shared Android files) | (a) start the NAV-019 mobile build after NAV-018 has landed (mid-flight default "finish first"); (b) build in parallel with demo code in its own source set and small targeted edits in shared files | **(a)** if NAV-018 lands within the day; otherwise (b). NAV-019 is not injected into the running NAV-018 agents in either case | no |

## Traceability
| AC | Screen spec / flow | API operation | ADR | Code | Test | Issues |
|---|---|---|---|---|---|---|
| AC1–5 | — | none | architect DM-4 (TBD) | `mobile/android/app/build.gradle.kts`, demo source set (TBD) | APK inspection, Gradle checks (quick check) | R1 (Open question 1), R2, R3; D17; NAV-005 AC 61, 72 |
| AC6–8 | UX: NAV-019 picker delta (TBD) | none | — | TBD | Robolectric/Compose (TBD) | glossary W1, N20; NAV-017 AC 8 (web reference) |
| AC9–11 | NAV-011 / NAV-018 screen specs (demo deltas TBD) | none (would-be `postRoute` blocked) | ADR-0012, ADR-0015 | TBD | Robolectric (TBD) | NAV-005 AC 6; NAV-011; NAV-018 AC 15 (demo exception), AC 18–23; D147 |
| AC12–17 | NAV-005 guidance screen spec; navigation-ux §2, §4, §8 | none | ADR-0008, ADR-0009; architect DM-1 (TBD) | TBD | JVM replay with host Ferrostar, golden `tests/gpx/nav005/golden/voice-golden.tsv` (TBD) | NAV-005 AC 15, 21–31, 34; NAV-011 typing lock; NAV-012 AC 13, 41 |
| AC18–21 | navigation-ux §4.1–4.8 | none | ADR-0009 §3 | existing TTS path | JVM + real device (AC 43) | NAV-005 AC 35–39, 60; NAV-012 AC 3; D23, D73; NAV-007 AC 9 (evidence) |
| AC22–25 | UX: pause control (TBD) | none | — | TBD | JVM virtual clock (TBD) | glossary W2, A7; NAV-005 AC 19, 55, 56 |
| AC26–30 | NAV-012 screen spec | none | ADR-0013; architect DM-2, DM-6 (TBD) | existing service and notification | Robolectric + real device (AC 43) | NAV-005 AC 13, 15; NAV-012 sections A–C, E–G; R3 (Open questions 6, 8) |
| AC31–34 | NAV-011 / NAV-005 state rows | none | architect DM-3 (TBD) | TBD | JVM request counter (TBD); airplane-mode run (AC 43) | R5 (Open question 5); NAV-005 AC 48, 50; NAV-011 AC 7 |
| AC35–37 | `docs/design/map-style.md` | none (`getBasemapPmtiles` only with `nav.demoTilesUrl`) | ADR-0004, ADR-0009 §6; architect DM-5 (TBD) | TBD | Compose attribution test (TBD) | R3 (Open question 3); NAV-005 AC 2 |
| AC38–41 | — | none | — | resources, README | Android glossary check, privacy scan, repo scan (TBD) | R7 (Open question 7); D35; NAV-005 AC 61, 66, 67 |
| AC42–44 | — | — | — | `mobile/android/README.md` (TBD) | quick check; PO phone checklist | D108, D155 |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-04 | Feature request (PO chat 2026-10-04, triage log 2026-10-04 "NAV-019 Android demo mode", feature lane); PO approved option B ("Ok", [D154](../decisions.md)) and the light process ([D155](../decisions.md)) | Created as NAV-019: 44 AC in sections A–K (separate `demo` build installed next to the debug build; picker with the three NAV-017 routes; normal preview with the turn list and an enabled «Эхлэх» in the demo build only; replay through the real NAV-005 guidance path with a simulated location that keeps running in the background; real Android TTS path with the D23 fallback; pause, «Дуусгах», arrival back to the picker; NAV-012 notification, lock screen, Bluetooth and calls; 0 network requests with the existing unavailable states; bundled offline tiles; attribution; a PO phone checklist). The orchestrator's defaults R1–R7 recorded as **proposed defaults, PO to confirm** (Open questions 1–7), plus BA default 8 (no restore in the demo build), priority (9) and build order (10). BA finding: R6 conflicts with the Android 14+ prerequisite for `location` foreground services (R3, Open question 6). Glossary: W2 «Түр зогсоох» added (`needs native review`); W1 reused for the badge, picker heading and launcher label | Lets the PO test guidance, voice, notification, lock screen, Bluetooth, calls and battery savers on a real Android phone while NAV-008 staging is unavailable |

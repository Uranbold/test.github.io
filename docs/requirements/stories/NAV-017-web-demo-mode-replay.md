---
id: NAV-017
title: Web demo mode (public folder with noindex, no backend) with simulated turn-by-turn replay of recorded Ulaanbaatar routes, for the PO's iPhone Safari
phase: 1
priority: should       # PO 2026-10-01: triage class P1 / standard (D71). "should" is the BA's MoSCoW mapping (Open question 1)
size: L
needs_design: true
needs_backend: false   # static files only; no service, gateway or openapi.yaml change
needs_mobile: true     # web/ (mobile-engineer owns web/)
status: ready
---

# NAV-017: Web demo mode with simulated turn-by-turn replay

## Story
As a **UB commuter by car**, represented in this demo by **the PO and the team members the PO chooses** (D74), I want **to open the demo page on my iPhone (a public folder with `noindex` since D107), pick one of a few recorded Ulaanbaatar routes and watch simulated turn-by-turn guidance (moving position, the Mongolian banner instruction and distance, voice or a chime, recenter and arrival)**, so that **I can judge how our guidance looks, reads and sounds on a real phone before an iOS app exists (NAV-015) and before any backend host is reachable from a phone (NAV-008)**.

Secondary personas (what the PO checks on their behalf):
- **Pedestrian:** the walking replay (R2) with the «Явган» prompt schedule.
- **Tourist (English UI):** switches to English and sees the English banners and hears an English voice. Place names stay Cyrillic (as D11).

## Context
- **Request:** PO in chat, 2026-10-01 ("option 1, password-protected"). The PO's phone is an **iPhone**, so the NAV-005 Android APK cannot be used. **The PO has no VPS yet**, so NAV-008 staging is not reachable. The PO's Hostinger Business plan is shared static web hosting (HTTPS; HTTP Range works). The public map-only demo (NAV-002 section M, D44/D47) already runs there.
- **Supersedes** the on-hold Android demo-mode change on NAV-005 (triage log 2026-10-01, closed as superseded). **NAV-005 itself is unchanged.**
- **PO decisions for this story (2026-10-01, "All as recommended"):**
  - [D71](../decisions.md): priority **P1 / standard** (also for the separate Mongolian voice spike).
  - [D72](../decisions.md): **iPhone Safari (current iOS) is supported for this demo mode only.** [D14](../decisions.md) stays for the rest of the web demo. QA adds WebKit runs (Playwright `webkit`, iPhone device emulation) for the demo-mode AC where the container allows it, and lists what needs the real iPhone.
  - [D73](../decisions.md): with no Mongolian voice, keep the **[D23](../decisions.md) minimum**: on-screen text plus **a short chime per instruction**, with the existing glossary notice A1. Guidance is **not** silent.
  - [D74](../decisions.md): **the password goes to the PO plus team members the PO chooses.** The demo stays non-public until NAV-007 is done ([D17](../decisions.md)). **Superseded:** since [D107](../decisions.md) (2026-10-03) the demo folder is **public without a password**, and [D116](../decisions.md) (2026-10-04) says D17 does not cover this demo. The «Туршилтын горим» badge (AC 14) and `noindex` (AC 3) stay, and the demo still sends no data (AC 42).
  - [D75](../decisions.md): the final voice solution is chosen **after real-phone voice evidence** (D23 order kept). The parallel voice spike only narrows the options. **No cloud TTS and no audio clips in this story.**
- **Pending change (not yet in the AC; recorded 2026-10-01):** [D78](../decisions.md) (voice spike, item 3). This demo uses **only local, on-device browser voices** (`localService === true`), in both UI languages. Online voices such as Edge desktop's «Microsoft Yesui Online (Natural)» / «Microsoft Bataa Online (Natural)» are not used. **AC 28 is not changed yet.** The change comes through triage as spike follow-up F1 ([D81](../decisions.md)), together with the voice-list probe. Because this story is in progress, the default is "finish first". Expected effect when applied: on Edge desktop the Mongolian UI falls back to the chime plus A1 (AC 29). The PO's iPhone is not affected, because its system voices are local.
- **Other decisions that apply:** D9/D47 (no data to any backend; this demo sends none), D11 (Cyrillic labels in the English UI), D12 (day theme by default on the web), D15 (page title «Газрын зураг»), D22 (Avoid terms), D35 (public repo: no secrets, hostnames or IPs), D44 (the public static site), D56 (ADR-0008 rule 11), D67 (rounded 1,000 m is «1 километрт»), D68 (`arrive` < 30 m after the previous manoeuvre), D69/D70 (current A11 and ramp wording).
- **What exists today (2026-10-01):** banner text from the manoeuvre fields in `web/src/route/instructions.ts` with the shared fixture `maneuvers.fixture.json` (ADR-0008, NAV-004); distance, duration and «Хүрэх цаг» formatting in `web/src/route/format.ts`; the static build switch `VITE_STATIC_DEMO` and `npm run build:static-demo` (0 search, reverse and route requests). **There is no guidance, replay, voice or chime code in `web/src`.** The voice text rules (NAV-005 AC 32, `docs/design/navigation-ux.md` §4.1) and the prompt schedule (§4.2–4.5) exist only in the Android client (ADR-0009 §3.3) and must be ported. The current static build assumes the web root ("a sub-folder is not supported", `web/README.md`), while this demo lives in a sub-folder.
- **Recorded data to reuse (no new recording needed):** recorded `postRoute` responses (Valhalla 3.9.0, OSRM format) and synthetic 1 Hz GPX tracks from the NAV-005 QA set (`tests/gpx/nav005/manifest.json`), plus the NAV-005 voice golden set `tests/gpx/nav005/golden/voice-golden.tsv` (rows for G1, G5 and G8 in `mn` and `en`).
- **Coordination (binding):** never stop, restart or rebuild the shared dev stack at `http://localhost:8080`. This story needs no live backend at all. NAV-002/003/004 behaviour does not change. The public static build (`build:static-demo`) must not contain demo mode. No passwords, `.htpasswd` files, hostnames or IPs in the repo.

### Demo routes (reference set)
| # | Picker entry | Mode | Recorded response (route) | Track | Length of the replay at 1× |
|---|---|---|---|---|---|
| R1 | P1 Sükhbaatar Square → P3 Zaisan | «Машин» | `mobile/android/app/src/test/resources/routes/p1-p3-car-mn.json` | `tests/gpx/nav005/G1.gpx` | 308 s |
| R2 | P1 Sükhbaatar Square → P2 State Department Store | «Явган» | `mobile/android/app/src/test/resources/routes/p1-p2-walk-mn.json` | `tests/gpx/nav005/G5.gpx` | 932 s |
| R3 | UB car route over Ikh Toiruu with two roundabouts (exit 2) | «Машин» | `mobile/android/app/src/test/resources/routes/g8-roundabout-car-mn.json` | `tests/gpx/nav005/G8.gpx` | 787 s |

- One recorded response per route is enough. Banner and voice text are generated on the client from the manoeuvre fields (ADR-0008), so the UI language only changes the client text. The `en` recordings (`p1-p3-car-en.json`, `p1-p2-walk-en.json`, `g8-roundabout-car-en.json`) may be used by QA to check that the manoeuvre fields match.
- **Place names in the picker and on the arrival panel are data, not UI strings.** They live in a demo route manifest file next to the fixtures, like `web/src/search/lexicon.json`, and follow the map label rule `name:mn` → `name` → `name:en` of the named OSM feature at each end (QA records the OSM IDs in the manifest). If an end has no named feature within 50 m, the manifest uses the glossary term «Сонгосон цэг» (NAV-003 T6) for it. The same Cyrillic names show in the English UI (as D11). The i18n and glossary scans skip the manifest as data.
- G4 (`tests/gpx/nav005/G4.gpx`: last 500 m of R1, then stationary 10 m before the end for 30 s) is a **test-only** track for the arrival AC. It is not offered in the picker.
- Not offered (out of scope): G2 (reroute), G3 variants (GPS loss), G6/G7 (outliers, poor accuracy), G9 (partial intercity track that never arrives), G10.

### Which NAV-005 behaviour applies to the replay (resolves conflict 4)
| NAV-005 AC | In NAV-017? | Note |
|---|---|---|
| 21 banner content, 22 trip progress, 23 camera follow (heading up), 25 recenter | **yes**, rewritten below as AC 17–23 | The puck is the simulated position |
| 26–28, 30, 31 banner text, sentinel, scans, English, never blank | **yes**, AC 18–20 | Same ADR-0008 table as NAV-004 AC 27 |
| 32 voice text rules (D67), 33 voice scan | **yes**, AC 24–25 | Port of navigation-ux §4.1 |
| 34 prompt timing (with the D68 exemption) | **yes, at 1× only**, AC 26 | Measured against the replay clock |
| 35 depart prompt, 37 mute, 38–39 voice choice and the D23 fallback | **yes, adapted to the browser**, AC 27–31 | `speechSynthesis` and Web Audio instead of Android TTS |
| 55 arrival, 56 one arrival message | **yes, without the Android service parts**, AC 32–34 | |
| 24 orientation toggle | no | Heading up only; out of scope |
| 36 Android audio focus and ducking | no | No browser equivalent; ducking is not checked |
| 41–53 off-route, reroute, GPS loss | no | No reroute is possible without a backend; GPS is not used |
| 54 network loss on the route | partly, AC 40 | Replay data are already loaded; missing tiles show the background |
| 1–20, 40, 57–73 (map screen search, permissions, foreground service, notification, Android build, JVM golden set) | no | Android-only or not applicable |

### Terms used in this story
- **Demo-mode build:** the separate production build of `web/` with demo mode on (AC 1). **Public static build:** the existing `build:static-demo` output (NAV-002 section M).
- **Demo folder:** the sub-folder of the PO's web hosting (public, no password since D107) that serves the demo-mode build. Its name and the site's hostname are placeholders in the repo (`<demo-host>`, `<demo-folder>`).
- **Replay:** the simulated drive from «Эхлэх» until arrival, «Дуусгах» or leaving the page. **Replay clock:** time since «Эхлэх», excluding paused time (AC 15). At **1×** one second of track time is one second of replay clock (the only speed in this story, Open question 2).
- **Simulated fix:** one GPX track point applied to the replay at its track time.
- **Reference devices:** the PO's iPhone with Safari on the current major iOS version at test time (version recorded in the QA report), and desktop Chromium for the automated tests. **WebKit emulation:** Playwright `webkit` with the iPhone device descriptors, where the container can run it.

### User-facing strings
Every Mongolian string comes from `docs/requirements/glossary.md`. **One new row was needed (resolves conflict 5):** W1, added on 2026-10-01 in new glossary section 2.3 with status `needs native review`. The route picker reuses the existing term «Маршрут сонгох» (N20, "Choose a route"). If another string is needed (for example a replay speed label), it is requested from the business-analyst first. Nobody invents one.

| Use in NAV-017 | Glossary row | `mn` | `en` |
|---|---|---|---|
| Demo-mode label (badge during the picker and the replay, and the picker heading) | **W1 Demo mode (new, `needs native review`)** | «Туршилтын горим» | "Demo mode" |
| Route picker list name | N20 Choose a route | «Маршрут сонгох» | "Choose a route" |
| Modes in the picker | Travel mode | «Машин», «Явган» | "Car", "Walk" |
| Start / end the replay | Start navigation / End navigation | «Эхлэх» / «Дуусгах» | "Start" / "End" |
| Recenter | Recenter | «Байршил руу буцах» | "Back to my location" |
| Voice button | Voice on / off, A5 | «Дууг хаах» / «Дууг нээх» | "Mute" / "Unmute" |
| No Mongolian voice | A1 | «Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.» | "Mongolian voice guidance is not available on this phone. Instructions are shown on screen only." |
| Progress | ETA / Remaining time / Remaining distance, N16, N21, N22, units | «Хүрэх цаг», «үлдсэн хугацаа», «үлдсэн зай», «+{days} өдөр», «ц», «мин», «м», «км» | as NAV-004 |
| Banner instructions | Section 3, N14, N15, N23, "Arrival (voice)" | NAV-004 AC 27 table | NAV-004 AC 27 table |
| Voice | C3, C4, A8–A13, "Approaching destination (voice)", "Arrival (voice)" | NAV-005 AC 32 | NAV-005 AC 32 / navigation-ux §4.1 |
| Arrival panel close, loading, errors | Close, Loading, Generic error, Try again, Map could not be loaded, No connection | «Хаах», «Ачаалж байна…», «Алдаа гарлаа», «Дахин оролдох», «Газрын зургийг ачаалж чадсангүй», «Интернэт холболт алга» | as NAV-002 |
| Unknown end point name (manifest data) | T6 Selected point | «Сонгосон цэг» | "Selected point" |
| Attribution | OSM attribution | «© OpenStreetMap contributors» | same |

## Acceptance criteria

### A. Build, hosting and protection
1. **Given** a clean checkout, **When** `npm run build:demo-mode` runs in `web/`, **Then** a production build is written to its own output folder (`dist-demo-mode/`; the architect may rename both, and `web/README.md` names them), the build log prints one line saying that demo mode is on and that search, reverse and routing are off, and the build uses the static-demo setting (`VITE_STATIC_DEMO=true`) plus a demo-mode setting whose key is listed in `web/.env.example` without any hostname.
2. **Given** the demo-mode build output, **When** its contents are uploaded into **any** sub-folder of a web root (for example `/<demo-folder>/`, or two levels deep), **Then** the page, its scripts, styles, fonts, sprites and route data load from that sub-folder **without a rebuild**, and the opening view (P1, zoom 12, D13) is drawn with tiles. QA checks two different folder names with `vite preview` or a static server, plus one real check on the PO's host (AC 49).
3. **Given** the demo-mode build output, **When** its file list is inspected, **Then** it contains **no** `.htaccess`, `.htpasswd` or other server configuration file (so uploading it never changes the folder's server settings), and `index.html` contains `<meta name="robots" content="noindex, nofollow">`.
4. **Given** the public static build (`npm run build:static-demo`) and the normal build (`npm run build`), **When** their outputs are searched, **Then** they contain **no** demo-mode code, picker, fixture or track data: **0** matches of the demo-mode test-id prefix chosen by the mobile-engineer (named in `web/README.md`), **0** files from the demo route data folder, and **0** matches of a 40-character substring of the R1 route geometry. NAV-002 AC 51–54 and NAV-004 AC 53–54 still pass against the public static build.
5. **Given** `web/README.md`, **When** it is read, **Then** a section "Demo mode (NAV-017)" documents with placeholders only (`<demo-host>`, `<demo-folder>`):
   - the build command and output folder;
   - the upload of the output **contents** into `<web root>/<demo-folder>/`, and that re-uploading the public static build must not delete that folder;
   - where the basemap archive is read from (inside the demo folder or the public `/tiles/basemap.pmtiles`, per the architect's decision on R6) and the Range check for it (206, `Content-Range`, no `Content-Encoding`);
   - that the demo folder is **public, without a password** ([D107](../decisions.md)), that `noindex` (AC 3) and the «Туршилтын горим» badge (AC 14) must stay, and that D17 does not cover this demo ([D116](../decisions.md));
   - the checks after **every** upload: a request to `https://<demo-host>/<demo-folder>/` without credentials answers **200** and the page contains the `noindex` meta tag (AC 3); the tile archive answers **206** to a Range request;
   - the supported browsers for demo mode (AC 36) and the list of real-iPhone checks (AC 48).
6. **Given** the repository, **When** it is scanned after this story, **Then** it contains **no** `.htpasswd` file, no `AuthUserFile` or `AuthName` directive, no password, no hostname of the PO's site and no IP address other than loopback or documentation ranges (D35, CLAUDE.md rule 9). `.gitignore` covers `dist-demo-mode/`.
7. **Given** the public site (D44), **When** its pages are inspected, **Then** they contain **no** link or reference to the demo folder.

### B. Route picker
8. **Given** the demo-mode build is opened, **When** the page is ready, **Then** within **1 s** of the first map `idle` the picker is shown over the map with the heading «Туршилтын горим», a list named «Маршрут сонгох», and exactly the entries R1, R2 and R3, each with: the origin and destination names from the manifest, the mode label («Машин» or «Явган»), the distance (NAV-004 AC 23 format) and the duration (NAV-004 AC 24 format) of its recorded response. «© OpenStreetMap contributors» is visible.
9. **Given** the picker, **When** an entry is selected, **Then** within **1 s** its route line (map-style §7.2 selected tokens), origin and destination markers are drawn, the camera fits the route into the area not covered by the picker (max zoom 17; jump with reduced motion), and «Эхлэх» becomes enabled. Selecting another entry replaces the route. **0** network requests go anywhere except the page origin (AC 42).
10. **Given** an entry's route data cannot be loaded or parsed (Playwright interception answers 404, 500 or an HTML body for its data file), **When** the entry is selected, **Then** within **1 s** «Алдаа гарлаа» with «Дахин оролдох» is shown in the picker, «Эхлэх» stays disabled, the map and the other entries keep working, and «Дахин оролдох» loads the file again once per press. While data are loading for more than **300 ms**, «Ачаалж байна…» is shown.
11. **Given** the demo-mode build, **When** the page is used, **Then** the NAV-002 controls (language, theme, zoom, compass, attribution) work as on the public site. Search and «Маршрут гаргах» are either hidden or behave exactly as on the public static build (NAV-002 AC 53, NAV-004 AC 53); the UX screen spec picks one. In both cases **0** `search`, `reverse` or `route` requests are sent.

### C. Replay
12. **Given** an entry is selected and its data are loaded, **When** «Эхлэх» is activated, **Then** within **1 s** the guidance view replaces the picker, the replay starts at the first track point, and simulated fix *i* is applied when the replay clock reaches the track time of fix *i* (± **100 ms** at 1×).
13. **Given** a replay, **When** a simulated fix is applied, **Then** the puck moves to that fix snapped to the route line within **200 ms**. The UX spec may animate the puck between fixes. If it does, the puck never moves backwards along the route, and the position used for distances, banners and prompts is the snapped fix position.
14. **Given** the replay, **When** it runs, **Then** the Geolocation API is **never** called (counted with a stub in tests), the NAV-002 my-location control is hidden, and the badge «Туршилтын горим» stays visible for the whole replay without covering the banner, the progress area or the attribution.
15. **Given** a replay, **When** the page becomes hidden (`visibilitychange` to `hidden`: Safari tab switch, app switch, screen lock), **Then** within **1 s** the replay clock pauses, any utterance or chime stops, and no prompt is produced while hidden. **When** the page is visible again, **Then** the replay resumes within **1 s** from the same position and replay time, with **0** prompts repeated and **0** prompts for anything passed (nothing is passed while paused).
16. **Given** a replay, **When** the track ends without arrival having been detected, **Then** the replay stops at the last fix and the view behaves as after «Дуусгах» (AC 35). (For R1, R2 and R3 arrival is expected; QA confirms that each track's last fix is within 30 m of its route end. If one is not, that is a QA data defect, not a reason to change AC 32.)

### D. Guidance view: banner, progress, camera, recenter
17. **Given** a replay, **When** the guidance view renders, **Then** the banner shows the next manoeuvre's icon (decorative, hidden from assistive technology), its instruction text (AC 18), the distance to it in the NAV-004 AC 23 format updated on every simulated fix, and the street name of the step after the manoeuvre (`step.name` with U+200B, U+200C, U+200D and U+FEFF removed; omitted when empty).
18. **Given** a step, **When** its banner text is produced, **Then** it is exactly the NAV-004 AC 27 text for its manoeuvre (`mn` and `en`, including D56), from `web/src/route/instructions.ts` and the `maneuver.*` resource keys. Valhalla's `maneuver.instruction`, `bannerInstructions[].*.text` and `voiceInstructions[].announcement` are **never** shown or spoken. **Sentinel check:** with a test copy of R1 and R3 whose Valhalla text fields are all replaced by `VALHALLA_TEXT_SENTINEL`, the sentinel appears **0** times on screen, in live regions and in the text passed to speech.
19. **Given** the banner texts of R1, R2 and R3 in the Mongolian UI, **When** scanned (street-name line excluded), **Then** they contain **0** Latin letters, **0** `<`, `>` or `{…}` tokens, **0** bare «зүүн»/«баруун» (glossary C2), **0** zero-width characters and **0** Avoid terms (NAV-005 AC 28 list plus the D70 old ramp forms). **In the English UI** every banner text is the `en` text of NAV-004 AC 27 and contains **0** Cyrillic letters outside the street-name line.
20. **Given** the simulated position passes a manoeuvre, **When** the next fix is applied, **Then** the banner shows the next manoeuvre within **1 s**, and the banner is **never blank** between «Эхлэх» and the end of the replay (after the last manoeuvre it shows the arrival text, AC 32).
21. **Given** a replay, **When** the progress area renders, **Then** it shows «Хүрэх цаг HH:MM» (device clock + remaining duration, NAV-004 AC 25 rounding and «+{days} өдөр»), the remaining time (NAV-004 AC 24 format) and the remaining distance (NAV-004 AC 23 format), recomputed at least every **5 s**, plus «Дуусгах» and the voice button. Remaining duration = the not-yet-driven share (by distance) of the current step's recorded `duration` plus the recorded durations of the later steps. While paused (AC 15) the values freeze.
22. **Given** a replay, **When** the camera follows, **Then** it tracks the puck with heading up (bearing = the course between consecutive fixes, kept when the speed is below 1 m/s), the puck stays inside the map area not covered by the banner, progress area or badge, and the camera reaches each new position or bearing within **1 s**.
23. **Given** a replay, **When** the user pans, zooms or rotates the map, **Then** following stops and «Байршил руу буцах» appears within **300 ms**, the replay continues, and **0** network requests are sent except tiles. **When** «Байршил руу буцах» is activated, or after **15 s** with no map gesture, **Then** following resumes within **1 s** and the button disappears (as NAV-005 AC 25).

### E. Voice, chime and the D23 fallback
24. **Given** a prompt in the Mongolian UI, **When** its text is produced, **Then** it follows NAV-005 AC 32 exactly (distance prefix rules including D67, lower-case instruction after a prefix, roundabout A10 / «Тойрогт орно уу», depart, arrival and its side variants, approaching A11 «{n} метрт очих газартаа хүрнэ», continue on A12, chaining A13 per navigation-ux §4.4), as a pure function with unit tests covering at least the NAV-005 AC 32 examples. The English UI uses the navigation-ux §4.1 English texts. No generated voice text contains «1000 метрт» or "1000 meters".
25. **Given** every voice text generated for R1, R2 and R3 and the shared manoeuvre fixture in the Mongolian UI, **When** scanned with the NAV-005 AC 33 rules, **Then** there are **0** matches of each forbidden pattern listed there (digits followed by «м»/«км», `\d+-р`, the old ordinal form, «ШТС», Latin letters except `GPS`, `<`, `>`, `{`, `}`, zero-width characters), and every relative «зүүн»/«баруун» is followed by a C2 form.
26. **Given** a replay at 1×, **When** prompts are produced, **Then** they follow the navigation-ux §4.2 schedule (car and walk columns, rules 1–6), §4.4 chaining and §4.5 playback rules 1, 3, 4 and 6, and:
    - each prompt is produced **at most once**, starting within **1 s** after the simulated position passes its trigger point; a prompt that cannot start within **3 s** of its trigger is dropped, not queued; no prompt is produced for a manoeuvre already passed;
    - the distance stated in each prompt differs from the true remaining distance at its start by ≤ max(**30 m**, **20 %**);
    - for every car manoeuvre except `depart`, at least one prompt starts while it is **20–250 m** ahead, with the [D68](../decisions.md) exemption for an `arrive` whose final step is shorter than 30 m;
    - **cross-platform check:** for G1 (R1), G5 (R2) and G8 (R3), in `mn` and `en`, the sequence of (manoeuvre index, text) equals the rows of `tests/gpx/nav005/golden/voice-golden.tsv` for that track and language **exactly**, and each prompt starts within **± 2 s** of the row's `t_s`. A difference is a defect in one of the two clients, or a golden-set change agreed through the BA. The test is never relaxed silently.
27. **Given** «Эхлэх» is activated with voice on, **When** the replay starts, **Then** the depart prompt (or its chime, AC 29) starts within **2 s**. The first `speechSynthesis.speak` call and the creation or `resume()` of the Web Audio context happen **inside the «Эхлэх» activation handler**, so that iOS Safari allows later speech and sound without another tap (architect confirms the exact unlock, request 2).
28. **Given** the picker is opened or the UI language changes, **When** the voice is chosen, **Then** a voice is **usable** only if `window.speechSynthesis` exists and, within **3 s** (waiting for `voiceschanged` if the list starts empty), `getVoices()` contains a voice whose `lang` matches `^mn([-_]|$)` (case-insensitive) for the Mongolian UI, or `^en([-_]|$)` for the English UI (prefer `en-US`). Every utterance sets that voice and its `lang` explicitly. The voice found is never sent anywhere and never stored.
29. **Given** no usable voice for the UI language (expected on iPhone for Mongolian; D23 minimum, [D73](../decisions.md)), **When** the replay runs, **Then**:
    - Mongolian text is **never** passed to a non-Mongolian voice (**0** `speak` calls with Mongolian text, checked with a stubbed `speechSynthesis`);
    - every prompt that would have been spoken plays **one chime** instead, with the same timing rules (AC 26); the chime is generated with Web Audio as in navigation-ux §4.8 (two-tone, about 330 ms, ≤ **1 s**, no sound file, no third-party licence);
    - the notice «Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.» (A1) is shown **once per replay** for **8 s** (navigation-ux §4.6; at least 5 s) or until tapped, without covering the banner, the progress area («Дуусгах» stays reachable) or the attribution;
    - a speech error during the replay (`error` event on an utterance) switches to the chime fallback for the rest of the replay within **1 s**, and shows the notice if it was not shown yet.
    The English UI without an English voice also uses the chime, with **no** notice (as navigation-ux §4.6).
30. **Given** the voice button, **When** voice is on, **Then** it reads «Дууг хаах»; when muted it reads «Дууг нээх», any current utterance or chime stops within **1 s**, and **0** utterances and **0** chimes are produced until it is turned on again. Voice is on by default. The choice is stored in `localStorage` (key named in `web/README.md`) and survives a reload. Banners are unaffected.
31. **Given** a replay, **When** the UI language is switched, **Then** labels, banner and progress texts switch within **1 s**, the current utterance stops, the next prompt uses the new language and voice (AC 28 re-evaluated; the A1 notice shows if it has not been shown in this replay and the new language has no usable voice), **0** prompts are repeated and **0** network requests are sent.

### F. Arrival and ending
32. **Given** a replay, **When** the snapped position is within **30 m** of the route end or has passed it, **Then** the banner shows «Та очих газартаа ирлээ» or the side variant «Таны очих газар баруун талд байна» / «Таны очих газар зүүн талд байна» from the `arrive` modifier, and it is spoken once (or one chime). The approaching prompt «{n} метрт очих газартаа хүрнэ» came before it when the schedule had one. The arrival prompt waits for a current utterance to finish (≤ **3 s**).
33. **Given** arrival, **When** it is detected, **Then** **0** further prompts or chimes occur, the replay clock stops, and an arrival panel with the destination name from the manifest and «Хаах» replaces the progress area and stays until «Хаах» is activated. «Хаах» returns to the picker with no route drawn.
34. **Given** G1 (R1), G5 (R2), G8 (R3) and the test-only G4, **When** each is replayed, **Then** exactly **1** arrival message is produced, also while G4 stays stationary 10 m before the end for 30 s.
35. **Given** a replay, **When** «Дуусгах» is activated, **Then** within **1 s** the replay stops, any utterance or chime stops, the route and puck are removed and the picker is shown again with no entry selected. A screen wake lock taken for the replay (AC 39) is released within **1 s**.

### G. Browsers, iPhone layout and accessibility
36. **Given** `web/README.md`, **When** the "Demo mode (NAV-017)" section is read, **Then** it states that the demo-mode build supports **iPhone Safari on the current major iOS version** in addition to the NAV-002 supported browsers ([D72](../decisions.md)), that Safari and iOS stay unsupported for the rest of the web demo (D14), and which demo-mode tests run on desktop Chromium, which on WebKit emulation and which only on the real iPhone. The NAV-002 "Supported browsers" section stays as written, plus at most one sentence pointing to the demo-mode section (NAV-002 AC 50, amended 2026-10-01).
37. **Given** WebKit emulation (or desktop Chromium where WebKit cannot run, stated in the QA report) with the iPhone viewports **375×667**, **390×844** and **430×932** in portrait and **844×390** in landscape, at 100 % and 200 % text zoom, **When** the picker, the guidance view, the A1 notice and the arrival panel are rendered, **Then** «© OpenStreetMap contributors» is visible and intersects no other visible element; nothing interactive sits under the screen's safe-area insets (`env(safe-area-inset-*)`); the banner instruction wraps up to **3** lines without truncating mid-word; and «Дуусгах», the voice button and «Байршил руу буцах» stay reachable.
38. **Given** assistive technology (VoiceOver on the iPhone; the accessibility tree in automated tests), **When** the demo is used, **Then** every control has an accessible name from the glossary, each new banner instruction is announced **once** through a polite live region (distance updates are not announced), touch targets are ≥ **44 × 44** CSS px, and text contrast is ≥ **4.5:1** in both themes (non-text indicators ≥ **3:1**).
39. **Given** the Screen Wake Lock API is available, **When** a replay is running and the page is visible, **Then** a `screen` wake lock is held (re-requested within **1 s** after the page becomes visible again) and released within **1 s** after the replay ends. **If the API is missing or the request fails**, the replay works without it and **no** message is shown.
40. **Given** a replay, **When** the network is lost (`context.setOffline` in tests), **Then** the replay, banner, prompts, progress and arrival continue from the already loaded data; already loaded tiles stay visible and missing tiles show the map background; the NAV-002 offline indicator «Интернэт холболт алга» appears as in NAV-002 AC 43 without covering the banner or attribution.
41. **Given** a replay, **When** the theme is switched or the phone is rotated, **Then** within **1 s** the route, puck, banner and progress stay, in the new colours or layout, with **0** repeated prompts and **0** network requests other than tiles.

### H. Privacy and network hygiene
42. **Given** a full demo-mode session (picker, all three replays to arrival, «Дуусгах» in the middle of one replay, language and theme switches, recenter, mute, one offline period), **When** all requests are logged, **Then** every request goes to the page origin (the demo folder, or the public tile archive path if the architect chooses it). **0** requests go to `/v1/search`, `/v1/reverse` or `/v1/route` on any host, **0** go to any other host (no CDN, font, analytics, TTS or speech service), and **0** use a service worker.
43. **Given** the same session, **When** `localStorage`, `sessionStorage`, cookies, the console and the page URL are inspected, **Then** the only stored values are the existing NAV-002 preferences (theme, language) and the voice choice (AC 30); **0** coordinates (regex `-?\d{1,3}\.\d{4,}`) appear in storage, the console or the URL.
44. **Given** the demo route data and tracks, **When** they are reviewed, **Then** they contain only the recorded route responses and the synthetic GPX tracks listed in "Demo routes", with no device positions of real people, and the OSM attribution is shown wherever the route geometry is drawn (ODbL; the recorded routes are derived from OSM).

### I. Unchanged behaviour and verification
45. **Given** this story is implemented, **When** the existing web checks run (`npm test`, `npm run lint`, `npm run check:glossary`, `npm run typecheck`, and the NAV-002/003/004 Playwright suites against the dev server when `/health` is 200), **Then** they pass **unchanged**, except the NAV-002 AC 50 README test, which keeps passing with the amended AC 50. Every new `mn` resource value matches a glossary term exactly (W1 included).
46. **Given** `npm test`, **When** it runs, **Then** it covers at least: the voice text generator in `mn` and `en` against the NAV-005 AC 32 examples and the shared manoeuvre fixture (AC 24, 25); the prompt schedule and playback rules with a fake clock (AC 26, 32–34); the golden comparison for G1, G5 and G8 in `mn` and `en` (AC 26); the replay engine with a fake clock including pause and resume (AC 12, 15, 16); the voice decision and the chime fallback with a stubbed `speechSynthesis` and Web Audio (AC 27–31); the remaining time and distance (AC 21); and the build-output checks (AC 3, 4).
47. **Given** Playwright, **When** the demo-mode E2E suite runs against `vite preview` of the demo-mode build, **Then** it runs on desktop Chromium **and** on WebKit with the iPhone device descriptors where WebKit can run in the container, using the Playwright clock to run replays faster than real time. The QA report states for each AC which engine verified it, and which AC WebKit could not verify and why.
48. **Given** the checks that need the real iPhone, **When** QA reports, **Then** they are listed as **not verified in this environment**, to be run by the PO (with a short checklist in `web/README.md`) on the PO's host: the 200 and `noindex` checks after upload (AC 5); tiles over HTTP Range (R6); whether the A1 notice appears (no Mongolian voice found) and whether iOS **Settings › Accessibility › Spoken Content › Voices** lists a Mongolian voice (input for NAV-007 AC 9 and the voice spike, D75); English speech in the English UI; chime audibility with the ring/silent switch **on and off**; that speech and chime start without an extra tap after «Эхлэх» (AC 27); the screen staying on for the whole R3 replay (787 s, AC 39); pause and resume after locking the phone (AC 15); safe areas, rotation and VoiceOver (AC 37, 38, 41); and the iOS version used.
49. **Given** the PO has uploaded the build to the public demo folder (D107; no password), **When** the PO opens the demo folder on the iPhone, **Then** the PO can complete R1 from «Эхлэх» to arrival, and the orchestrator records the PO's result and any defects. Defects found here stay in this story's fix loop.

## Edge cases
- **GPS lost / poor accuracy:** not applicable. The position is simulated and the Geolocation API is never called (AC 14). GPS-loss tracks are not offered.
- **No network:** before the page loads, Safari shows its own error (accepted for a demo). During a replay, AC 40. Route data fail to load: AC 10.
- **Off-route:** not possible in a replay (tracks follow the route). Reroute needs a backend, so it is out of scope.
- **No result found:** search is off (AC 11). A missing or broken route data file: AC 10.
- **Cyrillic/Latin search** ("Sukhbaatar" vs «Сүхбаатар»): search is off in this build. Picker names are Cyrillic in both UI languages (data, as D11).
- **Unpaved roads / intercity:** the reference routes are paved city streets. The partial intercity track G9 is not offered, because it never arrives (Out of scope).
- **Winter:** gloves make touch harder (44 px targets, AC 38). Cold drains the iPhone battery faster, which matters only for very long sessions. The recorded routes do not model snow or seasonal closures.
- **iPhone specifics:**
  - the ring/silent switch may silence Web Audio and possibly speech, so it is a real-iPhone check (AC 48). The README tells the PO to test with the switch off first;
  - Low Power Mode may slow timers; the replay clock uses timestamps, not timer counts, so positions stay correct;
  - auto-lock, app switch, an incoming call or Control Centre: AC 15 (pause), AC 39 (wake lock);
  - private browsing may block `localStorage`: the defaults apply (voice on, Mongolian, day), and nothing fails;
  - a "Add to Home Screen" web app is not required.
- **Anyone with the URL** can open the demo (public folder, D107, D116). It sends no data (AC 42), it is not linked from the public site (AC 7), and `noindex` (AC 3) and the «Туршилтын горим» badge (AC 14) mark it as a test.
- **Re-uploading the public static build** deletes the demo folder or its protection: README warning and the after-upload checks (AC 5).
- **Two people using the demo at the same time:** static files only, no shared state, no effect.
- **English UI without an English voice** (rare on iPhone): chime, no notice (AC 29).
- **Tap «Эхлэх» twice quickly:** one replay only (no second depart prompt).

## Data dependencies & risks
| # | Risk | Impact on NAV-017 | Mitigation / owner |
|---|---|---|---|
| R1 | **Recorded routes are a snapshot** of OSM and Valhalla 3.9.0 at recording time (NAV-005 QA). Streets may have changed since | The PO may see a route that differs from today's live routing | Accepted for a demo. Durations are not live ETAs. README says the routes are recorded |
| R2 | **Synthetic GPX tracks** follow the route geometry exactly, with no GPS noise, at scripted speeds | The demo looks smoother than real driving; jitter, drift and tunnels are not shown | Accepted. Real-GPS behaviour is NAV-005 (Android) and NAV-015 (iOS) |
| R3 | **Valhalla `mn-MN` narrative is still inside the recorded responses** (NAV-007 F1–F10) | Wrong or English text if it leaks into the banner or speech | ADR-0008 text only; sentinel check (AC 18); scans (AC 19, 25) |
| R4 | **No Mongolian voice in iOS** (expected; device and platform risk, NAV-007 R1) | In the Mongolian UI the PO hears only chimes, not Mongolian speech | D23 minimum (AC 29, D73); the real-iPhone check records the evidence (AC 48) for NAV-007 AC 9 and the voice spike (D75) |
| R5 | **iOS Safari audio rules:** speech and Web Audio need a user gesture, and the ring/silent switch may mute Web Audio | Silent demo if the unlock fails | Unlock inside the «Эхлэх» handler (AC 27); architect request 2; real-iPhone check (AC 48) |
| R6 | **HTTP Range under HTTP Basic auth on shared hosting** (with NAV-002 R10). Tiles may be inside the protected folder (about 112 MiB uploaded again) or reuse the public `/tiles/basemap.pmtiles` | The map may not load under the protection, or the archive is duplicated | Architect request 1 decides before the build is published; AC 5 documents the choice; real check (AC 48). *2026-10-04:* no Basic auth since D107, so only the Range part of this risk remains |
| R7 | **Unreviewed Mongolian guidance text** (NAV-007 not done) | Anyone with the URL can judge the product on unreviewed wording | **Accepted by the PO** for the demo: public folder ([D107](../decisions.md)), not covered by D17 ([D116](../decisions.md)). Remaining mitigations: `noindex` (AC 3), the «Туршилтын горим» badge (AC 14), no link from the public site (AC 7) |
| R8 | **`name:mn` coverage and unnamed streets** (NAV-001 R6, NAV-005 R6) | Street-name lines missing in the banner; picker names may fall back to «Сонгосон цэг» | AC 17 omits empty names; manifest rule ("Demo routes") |
| R9 | **`maxspeed` coverage** (NAV-001 R8) | Recorded durations and «Хүрэх цаг» are optimistic for UB traffic | Accepted (traffic is Phase 3) |
| R10 | **Golden-set drift:** the Android voice golden set changes (a NAV-005 fix) or the web port differs | AC 26 cross-platform check fails | Changes go through the BA; both clients use the same golden file |
| R11 | **The web host's access logs** keep the testers' IP addresses (NAV-002 R11) | Team IPs sit with the host | Accepted: team only, no query text or coordinates sent (AC 42) |
| R12 | **Server settings on shared hosting live in an `.htaccess` file** (no password protection since D107) | An upload that contains `.htaccess` changes the folder's server settings | AC 3 (no `.htaccess` in the build); after-upload 200 and `noindex` check (AC 5) |

## Out of scope
- Any backend, gateway or `openapi.yaml` change; live search, reverse or routing; connecting the demo to NAV-008 staging (D47 rules apply as for the public site).
- Off-route, reroute, GPS loss and poor-accuracy simulation; real device location during the replay.
- Cloud TTS, recorded or generated Mongolian audio clips, or any other voice solution beyond the browser's own voices and the chime ([D75](../decisions.md); NAV-016 after NAV-007 AC 9).
- Replay speeds other than 1×, pause and resume buttons, seeking, user-recorded tracks, uploading GPX files (Open question 2).
- Orientation toggle (north up), the «Дараа нь» Then strip, lane guidance, speed limits.
- Safari and iOS support for the rest of the web demo (D14 unchanged); Android Chrome is supported as part of the NAV-002 set but not specially tested for demo mode beyond Chromium.
- Deployment automation (CI/CD) to the web hosting; the PO uploads by hand.
- Any change to NAV-002/003/004/005 behaviour, the public static build or the shared dev stack.
- Password protection of the demo folder (removed by [D107](../decisions.md); D17 does not cover this demo, [D116](../decisions.md)). *Before 2026-10-04 this line read "Release to users outside the team before NAV-007 (D17, D74)".*

## Open questions
**None blocks design or the architect.** The PO decided items (a)–(e) on 2026-10-01 (D71–D75), and the BA settled conflicts 4 and 5 in this story (applicable NAV-005 AC table; glossary row W1).

| # | Question | Options | BA recommendation | Blocking |
|---|---|---|---|---|
| 1 | MoSCoW value for the backlog column (the PO set triage class P1 / standard, D71) | should; must; could | **should**: it is a review tool for the PO, not part of the product MVP. P1 still means "this iteration" | no |
| 2 | Replay speed. R2 lasts 932 s and R3 787 s at 1× | (a) 1× only in this story; (b) add a 4× option now (new glossary label, timing AC stay 1×-only) | **(a)**: keeps the timing checks meaningful and needs no new term. Add (b) later through triage if the PO finds the replays too long | no |
| 3 | PO pre-review of the new glossary row W1 «Туршилтын горим» | pre-review now; leave it to the NAV-007 panel | Implement now with `needs native review`; the PO may pre-review it on the review page (as D69) | no |

## Traceability
| AC | Screen spec / flow | API operation | ADR | Code | Test | Issues |
|---|---|---|---|---|---|---|
| AC1–7 | — | `getBasemapPmtiles` only (from the page origin or the public tile path) | architect: demo-mode build, sub-folder base, tiles under Basic auth (request 1) | `web/` build config, `web/README.md`, `web/.env.example` (TBD) | build-output checks, repo scan (QA, TBD) | D35, D44, D72, D74 (password part superseded), D107, D116; R6, R7, R12 |
| AC8–11 | UX: NAV-017 demo-mode screen spec (TBD) | none | — | TBD | Playwright Chromium + WebKit (TBD) | glossary W1, N20 |
| AC12–16 | UX (TBD) | none | architect (replay engine) | TBD | Vitest fake clock (TBD) | — |
| AC17–23 | UX (TBD); `docs/design/navigation-ux.md` §2, §8 | none | ADR-0008 | `web/src/route/instructions.ts`, `format.ts` (reused) | Vitest + Playwright (TBD) | NAV-005 AC 21–31 |
| AC24–31 | navigation-ux §4.1–4.8 | none | ADR-0009 §3.3 (schedule, to port); architect request 2 (iOS speech) | TBD | Vitest; golden `tests/gpx/nav005/golden/voice-golden.tsv` (TBD) | D23, D67, D68, D73, D75; glossary A1, A5, A8–A13; D78 pending for AC 28 (through triage, F1) |
| AC32–35 | navigation-ux §7 | none | — | TBD | Vitest + Playwright (G4) (TBD) | NAV-005 AC 55–56 |
| AC36–41 | UX (TBD) | `getBasemapPmtiles` | — | TBD | Playwright WebKit emulation; real iPhone (AC 48) | D14, D72; NAV-002 AC 50 (amended) |
| AC42–44 | — | none | — | TBD | request log, storage scan (TBD) | D9, D47 |
| AC45–49 | — | — | — | — | QA report (TBD) | D72 |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-01 | Feature request (PO in chat, triaged as feature / P1 standard, triage log 2026-10-01; feature-delivery workflow) with PO decisions (a)–(e) ("All as recommended", D71–D75) | Created as NAV-017, the next free story ID: 49 AC in sections A–I (separate demo-mode build in a sub-folder with no server files and `noindex`; public static build unchanged; README upload and hPanel steps with placeholders; route picker R1–R3 from the NAV-005 fixtures; replay engine with pause on hide; banner, progress, camera, recenter; voice text and prompt schedule ported from NAV-005 / navigation-ux with a cross-platform golden check; browser speech only with a Mongolian voice, otherwise the D23 chime plus A1; arrival; iPhone layout, accessibility, wake lock; 0 backend requests; WebKit emulation and a real-iPhone checklist). Conflicts settled: 1 (D72), 2 (D73), 3 (D74), 4 (applicable NAV-005 AC table), 5 (glossary W1 added, N20 reused). Three non-blocking open questions | Lets the PO review guidance on an iPhone now, without a VPS or an iOS app, and without exposing unreviewed Mongolian guidance publicly |
| 2026-10-01 | PO decision D78 on the Mongolian voice spike (item 3, "All as recommended") | **Note only:** Context bullet "Pending change" (local, on-device browser voices only, `localService === true`), plus a Traceability reference for AC24–31. **No AC changed.** AC 28 changes only through triage (spike follow-up F1, D81) | The story is being built now. Mid-flight changes go through triage (default "finish first"), so the decision is visible to the team without altering the AC under construction |
| 2026-10-04 | PO answer "All as recommended" in chat, item 7 ([D116](../decisions.md)), applying [D107](../decisions.md) (2026-10-03, demo folder public without a password) | Password removed from the story: title, Story, Context (D74 bullet marked superseded), Terms ("Demo folder"), AC 3 (reason only; still no `.htaccess` or `.htpasswd` in the build), **AC 5** (the hPanel password step and "who may receive the password" replaced by the public-folder note; the after-upload check is now 200 without credentials plus the `noindex` tag, and the 206 Range check), AC 8 (no password prompt), AC 48 (password prompt and 401/200 checks replaced by the 200 and `noindex` checks), **AC 49** (upload to the public folder, no password), Edge cases (wrong-password case replaced by "anyone with the URL"), R6 (Basic auth part gone), **R7** (accepted by the PO, remaining mitigations), R12, **Out of scope** (the D17 / D74 release line replaced by "password protection of the demo folder"), Traceability. **Kept:** `noindex` (AC 3), the «Туршилтын горим» badge (AC 14), 0 backend requests (AC 42), no password files, hostnames or IPs in the repo (AC 6) | D116: D17 does not cover the web demo now that D107 made it public. The change entry D107 asked for |

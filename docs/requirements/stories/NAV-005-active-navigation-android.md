---
id: NAV-005
title: Active turn-by-turn navigation on Android with Mongolian voice guidance and off-route reroute (first slice)
phase: 1
priority: must         # PO 2026-10-01, D57
size: L
needs_design: true
needs_backend: false   # true only if the architect's ADR adds a gateway static asset path (ADR-0004 follow-up) or another contract change
needs_mobile: true     # Android only (mobile/android); iOS is out of scope (D24)
status: ready
---

# NAV-005: Active turn-by-turn navigation on Android (first slice)

## Story
As a **UB commuter by car**, I want **to pick a destination, press «Эхлэх» and be guided turn by turn on my Android phone with on-screen instructions and Mongolian voice prompts, and get a new route automatically when I leave the planned one**, so that **I can drive to an unfamiliar address without reading a list or asking for directions, in my own language**.

Secondary personas:
- **Taxi / delivery driver**: long-presses the map when a ger-district customer address cannot be searched, navigates with the phone mounted and the screen sometimes off, and needs reroutes that never flood the server or stall on a weak connection.
- **Pedestrian**: uses «Явган» for walking guidance in the city centre.
- **Tourist (English UI)**: switches the app to English and gets English banners and English voice; street names and map labels stay Cyrillic (D11).
- **Intercity / countryside driver**: drives long unpaved stretches with weak or no signal; guidance must continue on the downloaded route without a network, and GPS loss must never trigger a reroute storm.

## Context
- Research `docs/osm-navigation-research.md` §2 rows 9–11 and 14 (voice, snapping, off-route and reroute, ETA), §4.5 (Ferrostar), §4.7 (voice via device TTS, "Mongolian TTS voice availability varies by device"), §5.2 (Android: Kotlin, Jetpack Compose, MapLibre Native, Ferrostar), §6 items 1 and 7, §7 Phase 1 ("Android app … active navigation, Mongolian voice, off-route rerouting, night mode"). Team design `docs/team/agent-architecture.md` §5.
- **Started by the PO on 2026-10-01** ("Go", relayed by the orchestrator). **Priority: must** ([D57](../decisions.md), PO 2026-10-01). The eight open questions and the test plan questions Q1 and Q2 were decided the same day ([D57–D68](../decisions.md), "All as recommended"; see "Decided questions").
- **PO decisions that apply (not open):**
  - [D24](../decisions.md): Android first, then iOS. **iOS is out of scope** for this story (follow-up NAV-015).
  - [D17](../decisions.md): NAV-007 (native-speaker review) must finish **before NAV-005 is released to any external user**. This story builds and verifies the app; it does not release it.
  - [D23](../decisions.md): the TTS fallback is decided after NAV-007 AC 9. **Until then the minimum is on-screen text plus a chime** (section F).
  - [D37](../decisions.md): routing engine **R1**, our own Valhalla behind `/v1/route`. No Hamuga routing, no client SDK (D36).
  - [D52](../decisions.md): no toll-avoid option in NAV-004, "revisit with NAV-005". **Revisited 2026-10-01: still none in NAV-005 ([D60](../decisions.md)).**
  - [D9](../decisions.md) / [D26](../decisions.md): reroutes send the current position to the backend. On the Singapore staging host only the project team (the PO included) uses the app until NAV-008 AC 24 records a go. Staging RTT is about 100 ms, so timing measured there is recorded, not pass/fail.
  - [D21](../decisions.md) / [D22](../decisions.md): «Явах чиглэл дээшээ» (heading up) is approved for NAV-005; «Төвлөрүүлэх», «навигаци», «км/ц» and «хоёр дахь» are **Avoid**. **The recenter button is «Байршил руу буцах»** (glossary "Recenter", binding). The feature request named «Төвлөрүүлэх»; that wording is an Avoid term since D22, so the glossary term is used (Decided questions, row 8, informational).
  - [D11](../decisions.md): the English UI keeps Cyrillic map labels. [D30](../decisions.md): search bias rule. [D51](../decisions.md): snap notice above 500 m.
- **Stack (ADR-0001):** Kotlin + Jetpack Compose, MapLibre Native Android, **Ferrostar Android** (Maven Central, `com.stadiamaps.ferrostar`) for navigation state, route snapping, off-route detection and reroute, and spoken-instruction triggers. Ferrostar is pre-1.0: versions are pinned (AC 70). Ferrostar's own minimum is Android API 25.
- **API:** `docs/architecture/api/openapi.yaml` **0.5.0**: `postRoute` (`POST /v1/route`, Valhalla 3.9.0 pass-through, OSRM format, `language` `mn-MN`/`en-US`, `locations[].heading`, 429 `RateLimited` + `Retry-After`, 502/503/504, gateway read timeout 10 s), `search` (Photon), the basemap PMTiles. Reroute is decided on the client and is "the same POST with the current position (+ heading)" (system overview §4, NFR-N1). **How Ferrostar consumes the gateway is the architect's decision** (design question A1).
- **Instruction text (ADR-0008, accepted):** on-screen text is built on the client from OSRM `maneuver.type` / `modifier` / `exit` / `bearing_after` with the glossary templates; Valhalla's `mn-MN` narrative is never shown. ADR-0008 §3 and Consequences ask NAV-005 to port the same rule table, test it against the **same fixture file** (`web/src/route/maneuvers.fixture.json`, to be moved or shared, architect's call) and to decide in a **new ADR** how banners and voice get our text (design question A2).
- **Map:** same basemap PMTiles and style as the web demo (`docs/design/map-style.md`, `docs/design/tokens.json`), labels `name:mn` → `name` → `name:en` (ADR-0004 label rule; "Android and iOS (NAV-005) must apply the same override"), OSM attribution «© OpenStreetMap contributors» on every map screen. ADR-0004 open item: native clients either bundle style, fonts and sprites or fetch them from a gateway static path (design question A3).
- **Build and test environment (facts, not blockers):** the cloud container has Java and Gradle 8.14.3 but no Android SDK yet. `dl.google.com`, `maven.google.com` and Maven Central are reachable; `github.com` release downloads are not (403). The mobile engineer installs the Android SDK command-line tools **outside the repo** (for example `/opt/android-sdk`, `ANDROID_HOME`) and builds a debug APK with Gradle. There is probably no emulator/KVM: QA verifies with JVM unit tests, Robolectric where possible and GPX replays (section M), and lists what needs a real phone. The PO tests the APK on a phone later; that needs a backend reachable from the phone (**NAV-008 staging, waiting for the VPS KVM 4 host details**). This is a dependency for the phone test, not for building and JVM verification.
- **Coordination (binding for this delivery):**
  - Never stop, restart or rebuild the shared dev stack at `http://localhost:8080`. If `/health` is not 200, wait and retry. Live route requests: **at most 2 per second** from the whole test run.
  - Android code only under `mobile/android/`. QA tests under `tests/` (for example `tests/android/`, `tests/gpx/nav005/`) and `mobile/android/app/src/test/` as agreed between mobile and QA in their handoffs.
  - **NAV-002/003/004 web behaviour does not change.** If the shared manoeuvre fixture moves, the web tests must still pass unchanged.
  - No secrets, keystores, SDK files or server hostnames/IPs in the repo (CLAUDE.md rule 9, D35). Loopback addresses for local dev are not server addresses.

### First slice and follow-ups
This story is the **first slice**: the smallest Android app that a driver can actually navigate with, and that QA can verify in this environment. **The PO confirmed this scope on 2026-10-01 ([D58](../decisions.md), option (a)).** Everything else is a follow-up item in the backlog (draft, priority TBD).

| In this story (NAV-005) | Follow-up (backlog draft) |
|---|---|
| Map screen with attribution, day/night/auto theme, mn/en UI | — |
| Destination by **search** (NAV-003 request profile, query sent as typed) or **long-press** on the map | **NAV-011** Android route preview and search parity: up to 2 alternatives on the map, «Дугуй», the NAV-003/ADR-0006 Cyrillic/Latin query assistance port and tier-B quality, reverse geocoding on the coordinate card, typing lock while moving with a passenger override ([D65](../decisions.md)) |
| Route preview with origin «Миний байршил», «Машин» / «Явган», «Шороон замаас зайлсхийх» (car), **one route**, distance, duration, «Хүрэх цаг», «Эхлэх» | (NAV-011) |
| Location permission flow, foreground service while navigating, keep screen on | **NAV-012** Background and lock-screen polish: rich notification (icon, distance), lock-screen view, continue after swipe-away and restore after process death, OEM battery-killer guidance, Bluetooth/car audio routing, phone-call handling, automatic sunrise/sunset theme |
| Banner, trip progress, camera follow, recenter «Байршил руу буцах», heading up / north up | **NAV-013** Lane guidance (Phase 2; OSM `turn:lanes`) |
| Voice guidance (Android `TextToSpeech`), glossary voice templates, D23 fallback (chime + notice) | **NAV-016** Mongolian voice fallback implementation (server neural TTS or recorded prompts), after NAV-007 AC 9 and the PO decision (owed decision 7) |
| Off-route detection and rate-limited reroute (429 `Retry-After`), GPS loss / tunnel, network loss, arrival | **NAV-014** Speed limit display and speeding warning (Phase 2; OSM `maxspeed`) |
| JVM/Robolectric tests, GPX replays, debug APK | **NAV-015** iOS active navigation (Phase 2, D24) |
| — | Not planned: Android Auto / CarPlay (Phase 2 evaluation only, through triage), offline regions (owed decision 3), waypoints, traffic |

### Design questions for the architect (record in ADR(s); none blocks UX)
- **A1. How Ferrostar consumes the gateway.** Ferrostar's Valhalla route adapter pointed at `{gateway}/v1/route`, or a custom route provider; how the request carries `language`, `units`, `voice_instructions`, `costing_options` and `heading` (AC 5, 43); whether any `openapi.yaml` change is needed (contract first, ADR if significant). Recommended by the BA: the stock adapter against the unchanged 0.5.0 contract if it can send the AC 5 body.
- **A2. Banner and voice text (ADR-0008 follow-up).** Options listed in ADR-0008 Consequences: (i) transform the OSRM JSON before Ferrostar parses it, (ii) custom banner views and a custom spoken-instruction observer keyed on the manoeuvre, (iii) server-side text. Also: whether voice **trigger points** stay Valhalla's `voiceInstructions[].distanceAlongGeometry` with our text replacing `announcement` (BA recommendation: yes, AC 34 is written against that), and how the off-route banner avoids going blank (Ferrostar issue #969, R10).
- **A3. Native style assets (ADR-0004 follow-up).** Bundle the style, fonts and sprites in the APK, or serve them from a gateway static path (contract change, backend work). Either way: no third-party CDN (AC 65), same flavors and label rule as the web.
- **A4. Shared manoeuvre fixture location** so web and Android test the identical cases (AC 29) without changing web behaviour.
- **A5. Android resources and language switch:** `values/` = `mn` with `values-en/`, or `values-mn/` with another default; per-app language (AndroidX `AppCompatDelegate.setApplicationLocales` or equivalent). Product rule: the app opens in Mongolian unless the user chose English in the app (AC 60, [D59](../decisions.md)).
- **A6. Off-route thresholds and reroute pacing in Ferrostar** (`StaticThreshold(minimumHorizontalAccuracy, maxAcceptableDeviation)`, `minimumTimeBeforeRecalculation`, any custom deviation detector) so that the outcomes in AC 41–50 hold.
- **A7. Gateway base URL per build** (dev loopback through `adb reverse` or the emulator alias, staging HTTPS) from a build property or an uncommitted local file, never a committed hostname (AC 66).

### Terms used in this story
- **Guidance:** the active navigation state between «Эхлэх» and the end (arrival or «Дуусгах»). UI name «Замчлал».
- **Fix:** one location update from the platform. **Good fix:** horizontal accuracy ≤ **25 m**. **Fresh fix:** ≤ **10 s** old (≤ **60 s** for the route-preview origin, as NAV-004).
- **Off-route episode:** from off-route detection until a new route is active or the user is back within 50 m of the current route.
- **Reference environment:** the debug APK's code under JVM/Robolectric tests on the NAV-001 machine, the live dev gateway `http://127.0.0.1:8080` when `/health` is 200, or recorded route fixtures. **Reference device:** a real Android phone (Android 12 or later), used only for the checks listed in AC 73.
- **Reference points:** P1–P6, X1 from NAV-001 (P1 Sükhbaatar Square 47.9189, 106.9176; P2 State Department Store 47.9139, 106.9044; P3 Zaisan 47.8858, 106.9173; P6 Chingeltei ger district 47.9600, 106.9000; X1 Erdenet 49.0270, 104.0440).
- **GPX replay set** (QA builds it; tracks at 1 Hz, accuracy 5 m unless stated; each with the recorded `postRoute` response so it can run offline):

| # | Track | Purpose |
|---|---|---|
| G1 | P1 → P3 by car, on the route, ≤ 60 km/h | Banners, voice sequence, progress, ETA |
| G2 | P1 → P3 by car with a wrong turn: leaves the route at a junction and drives ≥ 300 m on another road, then follows the new route to P3 | Off-route and reroute |
| G3 | P1 → P3 by car with a **30 s gap** in fixes mid-route (tunnel or underpass), fixes resume 400 m further along the route | GPS loss and restore |
| G4 | Last 500 m to P3, then stationary 10 m from the route end for 30 s | Arrival |
| G5 | P1 → P2 on foot, 1.4 m/s | Pedestrian guidance |
| G6 | G1 with **one** outlier fix 80 m off the route | No false reroute |
| G7 | G1 with 20 s of fixes with accuracy 60 m drifting up to 80 m off the route | No reroute on poor accuracy |
| G8 | A UB car route with a roundabout step with `exit` ≥ 2 (QA picks it from live data) | Roundabout banner and voice |
| G9 | 20 km of the P1 → X1 intercity road at 80 km/h | Kilometre prompts, «… километр үргэлжлүүлэн явна уу» |

### User-facing strings
Every Mongolian string comes from `docs/requirements/glossary.md`. **Rows NAV-005 needed and that were missing are A1–A13, added on 2026-10-01 in new glossary section 2.2 "Active navigation (NAV-005)", all `needs native review`** (BA proposals; the NAV-007 panel reviews them, as D27/D53 did for NAV-003/NAV-004). **PO pre-review 2026-10-01 (D69):** A1–A10, A12 and A13 approved as written (`PO-approved 2026-10-01 (panel pending)`); A11 changed by the PO to «{n} метрт очих газартаа хүрнэ» (`PO-revised 2026-10-01 (panel pending)`), and the ramp banners N14/N15 used here were reworded (see the NAV-004 AC 27 table). The NAV-007 panel still reviews them. If another string is needed, request it from the business-analyst first; do not invent one.

**Existing glossary terms used**

| Use in NAV-005 | Glossary row | `mn` | `en` |
|---|---|---|---|
| Guidance screen name, notification channel and title | Navigation (active guidance) | «Замчлал» | "Navigation" |
| Start / end guidance | Start navigation / End navigation | «Эхлэх» / «Дуусгах» | "Start" / "End" |
| Preview entry and heading | Get directions / Route preview | «Маршрут гаргах» / «Маршрут харах» | "Directions" / "Route preview" |
| Origin, destination | Origin / My location / Destination | «Миний байршил», «Эхлэх цэг», «Очих газар» | "My location", "Start", "Destination" |
| Modes, avoid toggle | Travel mode / Avoid unpaved roads | «Машин», «Явган», «Шороон замаас зайлсхийх» | "Car", "Walk", "Avoid unpaved roads" |
| Progress | ETA / Remaining time / Remaining distance | «Хүрэх цаг», «үлдсэн хугацаа», «үлдсэн зай» | "Arrive at", "Time left", "Distance left" |
| Off-route / reroute | Off-route / Reroute | voice «Та маршрутаас гарлаа»; status «Маршрутыг дахин тооцоолж байна» | "You have left the route" / "Recalculating route" |
| GPS | GPS signal lost / restored | «GPS дохио тасарлаа» / «GPS дохио сэргэлээ» | "GPS signal lost" / "GPS signal restored" |
| Recenter | Recenter | «Байршил руу буцах» | "Back to my location" |
| Orientation | North up / Heading up | «Хойд зүг дээшээ» / «Явах чиглэл дээшээ» | "North up" / "Heading up" |
| Voice toggle | Voice guidance / Voice on/off | «Дуут заавар», «Дууг хаах» | "Voice guidance", "Mute" |
| Theme | Night mode / Day mode | «Шөнийн горим» / «Өдрийн горим» | "Night mode" / "Day mode" |
| Language | Language | «Хэл»: «Монгол» / «English» | "Language" |
| Permissions and location | Location permission, G3, G5, Location services off | «Байршлаа ашиглахыг зөвшөөрнө үү», «Байршлын зөвшөөрөл олгоогүй байна», «Байршил тодорхойлж чадсангүй», «Байршил тогтоох үйлчилгээ унтарсан байна» | as glossary / NAV-002 |
| States | No connection, N8, T5, No route found, Generic error, Try again, G1, G6 | «Интернэт холболт алга», «Маршрутын үйлчилгээ түр ажиллахгүй байна», «Түр хүлээгээд дахин оролдоно уу», «Маршрут олдсонгүй», «Алдаа гарлаа», «Дахин оролдох», «Ачаалж байна…», «Хаах» | as NAV-004 |
| Preview states | N9, N10, N11, N12, N13 | as NAV-004 | as NAV-004 |
| Search | Search, No results, T1, T2, T4, T6 | «Газар, хаяг хайх», «Илэрц олдсонгүй», «Хайлтыг арилгах», «Хайлтын илэрц», «Хайлт түр ажиллахгүй байна», «Сонгосон цэг» | as NAV-003 |
| Banner instructions | Section 3, N14, N15, N23, "Arrival (voice)" | NAV-004 AC 27 table | NAV-004 AC 27 table |
| Voice | C3, C4 voice ordinals, "Distance prefix", "Approaching destination", "Continue on", "Then", "Enter roundabout and take exit N" | see AC 32 | see AC 32 |
| Units | Metre / Kilometre (on-screen), N21, N22, N16 | «м», «км», «ц», «мин», «+{days} өдөр» | "m", "km", "h", "min", "+{days} day(s)" |
| Attribution | OSM attribution | «© OpenStreetMap contributors» (not translated) | same |

**Glossary additions A1–A13 (added 2026-10-01; PO pre-review 2026-10-01, D69: 12 `PO-approved`, A11 `PO-revised`)**

| # | English term | `mn` | `en` | Where |
|---|---|---|---|---|
| A1 | Mongolian voice unavailable (notice) | «Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.» | "Mongolian voice guidance is not available on this phone. Instructions are shown on screen only." | AC 39 (D23 fallback) |
| A2 | Allow location in phone settings (hint) | «Утасны тохиргоонд байршлын зөвшөөрлийг асаана уу» | "Allow location access in your phone settings" | AC 11 |
| A3 | Open settings (button) | «Тохиргоо нээх» | "Open settings" | AC 10–12 |
| A4 | Allow precise location (message) | «Нарийвчилсан байршлыг зөвшөөрнө үү» | "Allow precise location" | AC 10 |
| A5 | Unmute (button) | «Дууг нээх» | "Unmute" | AC 37 |
| A6 | Automatic theme (option) | «Автомат» | "Automatic" | AC 58 |
| A7 | Continue (button) | «Үргэлжлүүлэх» | "Continue" | AC 8 |
| A8 | Voice distance prefix, metres (template) | «{n} метрт» | "In {n} meters" | AC 32 |
| A9 | Voice distance prefix, kilometres (template) | «{n} километрт» | "In {n} kilometers" | AC 32 |
| A10 | Enter roundabout and take exit N (voice template) | «Тойрогт ороод {ordinal} гарцаар гарна уу» | "Enter the roundabout and take the {ordinal} exit" | AC 32 |
| A11 | Approaching destination (voice template) | «{n} метрт очих газартаа хүрнэ» | "In {n} meters, you will arrive" | AC 32, AC 55 (changed 2026-10-01, D69) |
| A12 | Continue on (voice template) | «{n} километр үргэлжлүүлэн явна уу» | "Continue for {n} kilometers" | AC 32 |
| A13 | Then (chained voice prompt template) | «{first}, дараа нь {second}» | "{first}, then {second}" | AC 32 |

`{n}`, `{ordinal}`, `{first}`, `{second}`, `{days}`, `{distance}` are placeholders kept literally in resource files. `{ordinal}` takes only the C4 voice words «нэгдүгээр» … «аравдугаар». English voice wording is not glossary-bound; UX may refine the `en` texts.

## Acceptance criteria

### A. Map screen, destination and route preview
1. **Given** the app is opened, **When** the map screen appears, **Then** it shows Ulaanbaatar centred on P1 at zoom 12 (as D13), the basemap from the gateway PMTiles with the `docs/design/map-style.md` day or night flavor, labels by the ADR-0004 rule `name:mn` → `name` → `name:en` (same expression as the web, unit-tested), and «© OpenStreetMap contributors» visible. The app shows **no** OS location prompt on launch; it asks only after a user action (AC 8), as NAV-002 AC 18.
2. **Given** any map screen (map, route preview, guidance, arrival), in both themes, both languages, portrait and landscape and at font scale 200 %, **When** it is rendered, **Then** «© OpenStreetMap contributors» is visible and not covered by any banner, sheet, notice or control (Compose UI test asserts it is displayed and its bounds intersect no other visible element).
3. **Given** the search field «Газар, хаяг хайх», **When** the user types, **Then** requests follow the NAV-003 request profile (`GET /v1/search`, `lang` `mn`/`en` from the UI language, debounce 250 ms, at most 2 requests per settled query, bias per D30 with coordinates rounded to 3 decimals, 429 per `Retry-After`), results render in a list named «Хайлтын илэрц», an empty result shows «Илэрц олдсонгүй», failures show «Хайлт түр ажиллахгүй байна» with «Дахин оролдох», offline shows «Интернэт холболт алга», and selecting a result sets the destination and opens the route preview within **500 ms**. The query is sent **as typed** (the ADR-0006 query assistance is NAV-011). Live check: «Сүхбаатарын талбай» returns a result within **300 m** of P1 in the top 5; "Sukhbaatar" renders either a list or «Илэрц олдсонгүй», never an error state (result recorded for NAV-011).
4. **Given** the map screen, **When** the user long-presses the map, **Then** a card «Сонгосон цэг» with the coordinates (≥ 5 decimals) and «Маршрут гаргах» opens, and **0** `reverse` requests are sent (reverse is NAV-011). «Маршрут гаргах» opens the route preview with that coordinate as the destination.
5. **Given** the route preview opens with a fresh good fix (≤ 60 s) as origin «Миний байршил», **When** the route is requested, **Then** exactly one `POST {gateway}/v1/route` is sent with: `locations` [origin, destination] (≥ 5 decimals), `costing` `auto` («Машин», default) or `pedestrian` («Явган»), `costing_options: { auto: { exclude_unpaved: true } }` only when «Шороон замаас зайлсхийх» is on (car only, hidden on «Явган», as NAV-004 AC 12), `alternates: 0`, `format: "osrm"`, `banner_instructions: true`, `voice_instructions: true`, `units: "kilometers"`, `language` `mn-MN` (Mongolian UI) or `en-US` (English UI), no toll option. The `GET` form is never used.
6. **Given** a 200 response, **When** the preview renders, **Then** one route line (map-style §7.2 selected tokens), origin and destination markers, the distance, duration and «Хүрэх цаг HH:MM» formatted exactly as NAV-004 AC 23–25 (Mongolian decimal comma, «+{days} өдөр» after midnight) and an enabled «Эхлэх» are shown. With a snap distance > **500 m**, the NAV-004 AC 21 notice «Хамгийн ойрын зам сонгосон цэгээс {distance} зайтай» is shown (D51).
7. **Given** the preview, **When** a request fails or cannot be sent, **Then** the NAV-004 state rules and strings apply: loading after 300 ms; 400 `NoRoute` → «Маршрут олдсонгүй» (plus the N12 hint when the avoid toggle is on); `NoSegment` / error 171 → N9; `DistanceExceeded` on «Явган» → N11; network error, 502/503/504 or no response within **12 s** → N8 with «Дахин оролдох»; 429 → «Түр хүлээгээд дахин оролдоно уу», 0 requests for `Retry-After` seconds (5 s if missing or invalid); offline → «Интернэт холболт алга», 0 requests; other 400/413/unparsable → «Алдаа гарлаа»; origin and destination within **10 m** → «Эхлэх цэг, очих газар ижил байна» and 0 requests. «Эхлэх» is disabled in every state without a route.

### B. Location permission and location services
8. **Given** location permission was never granted, **When** the user activates «Маршрут гаргах», the origin option «Миний байршил», or the my-location control, **Then** an in-app rationale shows «Байршлаа ашиглахыг зөвшөөрнө үү» with «Үргэлжлүүлэх» and «Хаах»; «Үргэлжлүүлэх» opens the OS dialog for `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`. The app **never** requests `ACCESS_BACKGROUND_LOCATION` (manifest check).
9. **Given** precise location is granted, **When** a good fix arrives within **10 s**, **Then** it becomes the origin and the route is requested (AC 5). Otherwise «Байршил тодорхойлж чадсангүй» is shown and **0** route requests are sent.
10. **Given** only approximate location is granted (Android 12+), **When** the user tries to route from «Миний байршил» or start guidance, **Then** «Нарийвчилсан байршлыг зөвшөөрнө үү» is shown with «Тохиргоо нээх», «Эхлэх» stays disabled and **0** route requests from «Миний байршил» are sent.
11. **Given** the permission is denied (once, or permanently with "don't ask again"), **When** the result returns, **Then** «Байршлын зөвшөөрөл олгоогүй байна» and «Утасны тохиргоонд байршлын зөвшөөрлийг асаана уу» are shown with «Тохиргоо нээх» (opens the app's system settings page). After a permanent denial the OS dialog is not requested again. **When** the user returns from settings with the permission granted, **Then** the pending action continues within **2 s** without another tap. The user can still pick a destination by search or long-press.
12. **Given** device location services are off, **When** location is needed, **Then** «Байршил тогтоох үйлчилгээ унтарсан байна» is shown with «Тохиргоо нээх» (opens the system location settings), and the action continues within **2 s** after the user returns with location on.
13. **Given** Android 13 or later, **When** «Эхлэх» is activated for the first time, **Then** the OS notification permission is requested once. **If it is denied, guidance still starts and works** (foreground service running, AC 15–17); only the visible notification is missing.
14. **Given** a phone **without Google Play services**, **When** guidance runs, **Then** location comes from the platform `LocationManager` (GPS provider) and every AC in sections C–J holds. If the architect adds the fused provider where available, the platform provider stays the fallback. Support for these phones is **required** ([D62](../decisions.md), PO 2026-10-01); no Google Play services dependency.

### C. Starting guidance and the foreground service
15. **Given** a route preview with a route and a fresh good fix, **When** «Эхлэх» is activated, **Then** within **1 s** the guidance screen is shown, the previewed route is navigated with **0** additional route requests, a foreground service of type `location` is running (manifest declares `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION` for Android 14+), and an ongoing notification in channel «Замчлал» shows the current banner instruction and distance with a «Дуусгах» action; tapping the notification opens the guidance screen.
16. **Given** guidance, **When** location updates are requested, **Then** the interval is **1 s** (± 0.2 s requested) for the whole session and updates stop within **2 s** after guidance ends.
17. **Given** guidance, **When** the user presses Home, switches apps or turns the screen off, **Then** guidance continues: fixes keep arriving at 1 Hz, voice prompts are spoken, off-route and reroute work, and the notification text updates at least every **5 s**. (Robolectric: the service stays started and the notification updates when the activity is stopped; real-device check of ≥ **10 min** with the screen off: AC 73.)
18. **Given** the guidance screen is visible, **When** it is shown, **Then** the screen is kept on (`FLAG_KEEP_SCREEN_ON` or the Compose equivalent); the flag is cleared within **1 s** after guidance ends.
19. **Given** guidance, **When** «Дуусгах» is activated on the screen or in the notification, **Then** within **2 s** the service stops, the notification is removed, location updates stop (**0** fixes delivered afterwards), any utterance or chime stops, **0** route requests are sent, and the map screen is shown without a route.
20. **Given** guidance, **When** the user removes the app from Recents, **Then** guidance ends as AC 19 within **5 s** (continuing after swipe-away is NAV-012).

### D. Guidance screen
21. **Given** guidance on a route, **When** the screen renders, **Then** the banner shows the next manoeuvre's icon (decorative, hidden from TalkBack), its instruction text (section E), the distance to it in the NAV-004 AC 23 format, and the street name of the step after the manoeuvre (`step.name` with U+200B, U+200C, U+200D and U+FEFF removed; omitted when empty). The distance updates on every fix.
22. **Given** guidance, **When** the trip progress area renders, **Then** it shows «Хүрэх цаг HH:MM» (current time + remaining duration, NAV-004 AC 25 rounding and «+{days} өдөр»), remaining time (NAV-004 AC 24 format) and remaining distance (NAV-004 AC 23 format), recomputed at least every **5 s** and after every reroute; plus «Дуусгах», the voice toggle (AC 37), the orientation toggle (AC 24) and, when not following, «Байршил руу буцах» (AC 25).
23. **Given** guidance on a route, **When** the camera follows, **Then** it tracks the route-snapped position with heading up («Явах чиглэл дээшээ») by default, the puck stays inside the map area not covered by the banner or progress area, and the camera reaches a new position or bearing within **1 s**. When off-route (section G), the puck shows the raw position.
24. **Given** guidance, **When** the orientation toggle is used, **Then** it switches between «Явах чиглэл дээшээ» and «Хойд зүг дээшээ» within **1 s**; the toggle's accessible name names the mode it switches to, and the choice is kept for the rest of the session.
25. **Given** guidance, **When** the user pans, zooms or rotates the map, **Then** following stops and «Байршил руу буцах» appears within **300 ms**. **When** it is activated, or after **15 s** with no map gesture (BA default; UX may propose another value through the BA), **Then** following resumes within **1 s** and the button disappears. Map gestures send **0** route requests.

### E. Banner instruction text (ADR-0008 port)
26. **Given** a step, **When** its banner text is produced, **Then** it is exactly the NAV-004 AC 27 text for its manoeuvre (`mn` and `en`), including the D56 clarification, using the ADR-0008 §2 rule order. Valhalla's `maneuver.instruction`, `bannerInstructions[].*.text` and `voiceInstructions[].announcement` are **never** shown, spoken or put in the notification.
27. **Given** a recorded route whose Valhalla text fields are all replaced by the sentinel `VALHALLA_TEXT_SENTINEL`, **When** G1 and G8 are replayed, **Then** the sentinel appears **0** times on screen, in the notification and in the captured TTS input.
28. **Given** the banner texts of G1, G2 (after reroute), G5, G8, G9 and the AC 29 fixture in the Mongolian UI, **When** scanned (street-name line excluded), **Then** they contain **0** Latin letters, **0** `<`, `>` or `{…}` tokens, **0** bare «зүүн»/«баруун» (glossary C2, same rule as NAV-004 AC 28), **0** zero-width characters, **0** Avoid terms («навигаци», `км/ц(?!аг)`, «<number> дахь/дэх», «зорьсон газар», «налуу зам», «Төвлөрүүлэх»), and each is one of the NAV-004 AC 27 texts.
29. **Given** the shared manoeuvre fixture file (ADR-0008 §3, the file the web tests use), **When** the Android JVM unit tests run, **Then** **100 %** of its cases give the expected key and parameters, and the web unit tests still pass unchanged.
30. **Given** the English UI, **When** G1 and G8 are replayed, **Then** every banner text is the `en` text of NAV-004 AC 27 and contains **0** Cyrillic letters outside the street-name line.
31. **Given** the banner text for a manoeuvre, **When** guidance passes the manoeuvre, **Then** the banner shows the next manoeuvre within **1 s**, and the banner is **never blank** during guidance: while there is no active route it shows «Маршрутыг дахин тооцоолж байна» (section G) or the arrival text (section J).

### F. Voice guidance and the TTS fallback
32. **Given** a voice trigger in the Mongolian UI, **When** its text is produced, **Then** it follows these rules (pure function, JVM unit tests with at least the examples below):
    - **distance prefix** (C3, A8, A9) from the distance *d* to the manoeuvre at the trigger point: *d* < 30 m → no prefix; 30 ≤ *d* < 95 m → nearest 10 m; 95 m ≤ *d* < 995 m → nearest 50 m, **except that a value that rounds to 1000 is spoken in kilometres as «1 километрт»** ([D67](../decisions.md)); 995 m ≤ *d* < 9,950 m → kilometres with one decimal and a **comma**, «,0» dropped; *d* ≥ 9,950 m → whole kilometres. Units are spelled out («метрт», «километрт»); the number stays in digits. **No** generated voice text contains «1000 метрт» (Mongolian) or "1000 meters" (English);
    - **instruction**: the NAV-004 AC 27 `mn` text with a lower-case first letter after a prefix (for example «баруун тийш эргэнэ үү»);
    - **roundabout** with `exit` 1–10: «Тойрогт ороод {ordinal} гарцаар гарна уу» (A10) with the C4 word («нэгдүгээр» … «аравдугаар»); `exit` > 10 or missing: «Тойрогт орно уу»;
    - **depart**: the NAV-004 AC 27 depart text («Хойд зүг рүү явна уу» …); **arrive**: «Та очих газартаа ирлээ» or the side variant; **approaching the destination**: «{n} метрт очих газартаа хүрнэ» (A11, PO-revised 2026-10-01, D69), for example 200 m before the route end → «200 метрт очих газартаа хүрнэ»;
    - **continue on** (A12): «{n} километр үргэлжлүүлэн явна уу», only right after a manoeuvre when the next manoeuvre is ≥ **2 km** away;
    - **chaining** (A13): «{first}, дараа нь {second}» when the route's voice instruction for a step covers two manoeuvres (UX/architect define the trigger);
    - examples: 300 m + `turn right` → «300 метрт баруун тийш эргэнэ үү»; 960 m + `turn right` → «950 метрт баруун тийш эргэнэ үү»; 980 m + `turn right` → «1 километрт баруун тийш эргэнэ үү» (D67); 1,000 m + `turn right` → «1 километрт баруун тийш эргэнэ үү»; 1,500 m + `fork` left → «1,5 километрт зүүн талаа барина уу»; 200 m + roundabout `exit` 2 → «200 метрт тойрогт ороод хоёрдугаар гарцаар гарна уу»; 20 m + `turn left` → «Зүүн тийш эргэнэ үү»; `arrive` → «Та очих газартаа ирлээ».
    In the English UI the same structure uses the `en` texts with spelled-out units ("In 300 meters, turn right"); a value that rounds to 1000 m is "In 1 kilometer, …" (980 m + `turn right` → "In 1 kilometer, turn right"), never "In 1000 meters" (D67).
33. **Given** every voice text generated for G1, G2, G5, G8, G9 and the fixture in the Mongolian UI, **When** scanned with the NAV-007 AC 13 voice rules, **Then** there are **0** matches of `\d\s?(м|км)(\s|-|/|$)`, **0** of `\d+-р`, **0** of the old ordinal regex `(\d+|нэг|хоёр|гурав|дөрөв|тав|зургаа|долоо|найм|ес|арав)\s(дахь|дэх)`, **0** «ШТС», **0** Latin letters except the token `GPS`, **0** `<`, `>`, `{`, `}`, **0** zero-width characters, and every relative «зүүн»/«баруун» is followed by a C2 form.
34. **Given** a route with voice instructions, **When** G1 is replayed at its recorded speeds, **Then**: each voice trigger produces **at most one** utterance (or chime), starting within **1 s** after the simulated position passes the trigger point; no prompt is produced for a manoeuvre already passed; a prompt that cannot start within **3 s** of its trigger is dropped, not queued; the distance stated in each prompt differs from the true remaining distance at speaking time by ≤ max(**30 m**, **20 %**); and for every car manoeuvre except `depart` at least one prompt is spoken while it is **20–250 m** ahead. **Exemption ([D68](../decisions.md)):** an `arrive` manoeuvre whose final step (from the previous manoeuvre to the route end) is **shorter than 30 m** along the route is exempt from this 20–250 m check; it is not chained with the previous manoeuvre, and its arrival prompt (AC 55) is still produced **exactly once** (spoken, or the chime, AC 39). Every other part of this AC applies to it unchanged, and the exemption covers no other manoeuvre.
35. **Given** «Эхлэх» is activated with voice on, **When** guidance starts, **Then** the depart prompt is spoken once within **2 s**.
36. **Given** a prompt is spoken, **When** audio is played, **Then** it uses audio attributes usage `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, requests transient audio focus with ducking and releases it within **1 s** after the utterance ends. **If focus is denied** (for example during a phone call), the prompt is skipped, not queued.
37. **Given** the voice toggle, **When** voice is on, **Then** the button reads «Дууг хаах»; when muted it reads «Дууг нээх», and **0** utterances and **0** chimes are produced. Voice is on by default; the choice is remembered across app restarts. Banners are unaffected.
38. **Given** guidance starts (and after a language switch, AC 60), **When** the voice is chosen, **Then** the Mongolian UI uses a TTS voice for `mn` / `mn-MN` and the English UI an English voice. A voice is **usable** only if `TextToSpeech` initialises within **3 s** and the language check reports it available (not `LANG_MISSING_DATA` or `LANG_NOT_SUPPORTED`). The engine and voice found are recorded only on the device (debug log without coordinates), never sent anywhere.
39. **Given** no usable voice for the UI language (D23 minimum), **When** guidance runs, **Then**: Mongolian text is **never** spoken by a non-Mongolian voice; every voice trigger plays a **chime** of ≤ **1 s** (a sound file made for the project or generated, with no third-party licence) instead; the notice «Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.» (A1) is shown once per guidance session for at least **5 s** without covering the banner or attribution; client prompts (off-route, GPS lost and restored) also become a chime plus their on-screen text. A TTS error during the session switches to this fallback for the rest of the session within **1 s**.
40. **Given** the voice and banner text generators, **When** QA builds the golden announcement set for G1, G5, G8 and G9, **Then** the generators run on the JVM without a device, so the set can feed NAV-007 AC 12 (snapshot owned by QA).

### G. Off-route detection and reroute
41. **Given** guidance on a route, **When** the position leaves it, **Then** off-route is detected using only good fixes (≤ 25 m accuracy) more than **50 m** from the route line (BA-proposed values; the architect may propose others in the A6 ADR, changed through the BA). Replay outcomes: G2 → off-route detected within **8 s** after the first good fix > 50 m away; G6 (one outlier) → **0** reroute requests; G7 (poor accuracy) → **0** reroute requests during the poor-accuracy period.
42. **Given** off-route is detected, **When** the episode starts, **Then** within **1 s** the banner shows «Маршрутыг дахин тооцоолж байна» (never blank), «Та маршрутаас гарлаа» is spoken once per episode (or the chime, AC 39), and the first reroute request is sent within **1 s**.
43. **Given** a reroute, **When** the request is sent, **Then** it is the AC 5 body with `locations[0]` = the current position (≥ 5 decimals) plus `heading` = the course over ground (integer 0–359) when speed ≥ **2 m/s** (tolerance per the A6/A1 ADR), `locations[1]` = the original destination, and the **same** `costing`, `costing_options`, `language` and `units` as the route being followed, `alternates: 0`.
44. **Given** any sequence of off-route episodes, **When** reroute requests are counted, **Then** at most **1** is in flight, consecutive requests start ≥ **5 s** apart, and there are at most **6** in any rolling **60 s** (unit test with a fake clock and a mock server that always answers 400 `NoRoute` or always 503).
45. **Given** a reroute 200 response, **When** it arrives, **Then** within **500 ms** the new route replaces the old one on the map and in the banner, guidance continues with normal prompts, and the episode ends. Against the live dev gateway, from detection to the new route on screen is ≤ **3 s** for ≥ **9 of 10** G2 replays (≤ 2 requests per second). On staging the time is recorded with the NAV-008 AC 8 RTT, not pass/fail (D26).
46. **Given** the user returns within 50 m of the old route before a reroute response arrives, **When** the response arrives, **Then** either route may be used, but **0** extra requests are sent and the banner shows an instruction within **1 s**.
47. **Given** a reroute returns **429** with `Retry-After: N`, **When** it arrives, **Then** **0** route requests are sent for N seconds (5 s if missing, not a positive integer, or unreadable); afterwards, if still off-route, exactly **1** request is sent **automatically** (no tap while driving); the banner keeps «Маршрутыг дахин тооцоолж байна» without a secondary line. QA checks with `Retry-After: 3`: 0 requests in the 3 s window, then 1.
48. **Given** a reroute fails with a network error, 502/503/504 or no response within **12 s**, **When** still off-route, **Then** the banner shows «Маршрутыг дахин тооцоолж байна» with the secondary line «Маршрутын үйлчилгээ түр ажиллахгүй байна», and retries are automatic with back-off **5, 10, 20, 30, 30 … s** (each also respecting AC 44), stopping when back on a route or when guidance ends.
49. **Given** a reroute returns 400 `NoRoute`, `NoSegment` or error 171, **When** still off-route, **Then** the secondary line is «Маршрут олдсонгүй» and the next request is sent only after the position has moved ≥ **200 m** from the failed request's origin **and** ≥ **30 s** have passed. Any other 400, 413 or an unparsable 200 shows «Алдаа гарлаа» with the same retry rule.
50. **Given** the phone has no validated network while off-route, **When** off-route persists, **Then** **0** route requests are sent, the secondary line is «Интернэт холболт алга», and within **3 s** after the network returns (still off-route) exactly **1** request is sent.

### H. GPS loss, tunnels and poor accuracy
51. **Given** guidance, **When** no good fix arrives for **10 s**, **Then** within **1 s** «GPS дохио тасарлаа» is shown and spoken once (or chime), the puck stays at the last position marked as stale (UX), remaining distance and «Хүрэх цаг» freeze, manoeuvre prompts are not spoken, and **0** reroute requests are sent while the loss lasts. Guidance does not end on its own, however long the loss lasts.
52. **Given** GPS was lost, **When** the first good fix arrives, **Then** «GPS дохио сэргэлээ» is shown for **3 s** and spoken once (or chime), guidance resumes from the new position within **2 s**, manoeuvres passed during the loss are not announced, and if the position is > 50 m from the route the section G rules apply.
53. **Given** G3 (30 s gap), **When** replayed, **Then** exactly **1** lost and **1** restored message are produced, **0** reroute requests are sent during the gap, and the banner shows the correct next manoeuvre within **2 s** after fixes resume.

### I. Network loss on the route
54. **Given** guidance on a route, **When** the network is lost, **Then** banners, voice, progress and arrival keep working from the downloaded route; already loaded tiles stay visible and missing tiles show the map background; a non-blocking indicator «Интернэт холболт алга» appears within **2 s** without covering the banner or attribution, and disappears within **2 s** after the network returns. **0** route requests are sent while on the route, with or without network.

### J. Arrival
55. **Given** guidance, **When** the position is within **30 m** of the route end or has passed it, **Then** the banner shows «Та очих газартаа ирлээ» (or «Таны очих газар зүүн талд байна» / «… баруун талд байна» from the `arrive` modifier) and it is spoken once (or chime); the approaching prompt «{n} метрт очих газартаа хүрнэ» was spoken before it when the route's voice instructions include one. After arrival **0** reroute requests and **0** further prompts occur; within **10 s** the foreground service and guidance location updates stop; an arrival panel with «Хаах» stays until closed and then the map screen is shown.
56. **Given** G4, **When** replayed, **Then** exactly **1** arrival message is produced, also while stationary 10 m from the end for 30 s.
57. **Given** a destination more than 500 m from the nearest road (snap notice shown in the preview, AC 6), **When** the route end is reached, **Then** arrival is at the route end as AC 55 (the remaining off-road distance is not guided).

### K. Theme, language, accessibility, rotation
58. **Given** a theme choice of «Өдрийн горим», «Шөнийн горим» or «Автомат» (default «Автомат» = follow the Android system dark-theme setting; [D63](../decisions.md)), **When** it applies, **Then** the map uses the matching `tokens.json` flavor and the Compose UI the matching colours; the choice is remembered across restarts.
59. **Given** guidance with a route, **When** the theme changes (by the user or the system), **Then** within **1 s** the route, puck, banner and progress stay, in the new colours, with **0** route requests and **0** repeated prompts.
60. **Given** the app's language setting («Хэл»: «Монгол» / «English»), **When** the app is first installed, **Then** it opens in **Mongolian whatever the device language** ([D59](../decisions.md)). **When** the user switches language (during guidance too), **Then** labels and banner texts switch within **1 s**, **0** route requests are sent, the next voice prompt uses the new language and voice (AC 38–39 re-evaluated), and the choice is remembered.
61. **Given** the Android resources, **When** the checks run, **Then** every user-facing string comes from string resources (`mn` and `en` key sets identical, a lint or scan finds **0** hard-coded user-facing literals in Kotlin/Compose), and every `mn` value matches a glossary Mongolian term exactly (placeholders kept literally), using an Android equivalent of the NAV-002 AC 33 check. Debug-only tooling (AC 72) is excluded and absent from release builds.
62. **Given** TalkBack, **When** the guidance screen is used, **Then** every control has an accessible name from the glossary («Дуусгах», «Байршил руу буцах», «Дууг хаах» / «Дууг нээх», «Хойд зүг дээшээ» / «Явах чиглэл дээшээ»), each new banner instruction is announced once politely (distance updates are not announced), touch targets are ≥ **48 × 48 dp**, and text meets contrast ≥ **4.5:1** in both themes (non-text indicators ≥ 3:1).
63. **Given** font scale **200 %**, **When** the banner renders, **Then** the instruction wraps (up to 3 lines) and is not truncated mid-word, and AC 2 still holds. On phones **320 dp wide** at font scale **above 100 %** the banner region may scroll and floating controls may overlap the status message (PO-accepted known limitation, [D66](../decisions.md)); the instruction is never truncated mid-word and AC 2 (attribution) still holds there too.
64. **Given** guidance, **When** the phone rotates or another configuration change happens, **Then** guidance continues with **0** route requests and **0** repeated prompts, and the banner stays visible.

### L. Privacy and network hygiene
65. **Given** a full session (search, long-press, preview in both modes, guidance G1–G9, reroutes, language and theme switches), **When** all network requests are captured (interceptor or mock server in tests, plus dependency review), **Then** every request goes to the configured gateway base URL, and **0** go to any other host (no analytics, crash reporting, map telemetry, font, sprite or style CDN, public routing or Hamuga host).
66. **Given** the repository, **When** it is inspected, **Then** the gateway base URL comes from a build property or an uncommitted local file, the repo contains **no** server hostname or IP (loopback for local dev is allowed), no keystore, no `local.properties` and no SDK files, and cleartext HTTP is allowed only for loopback/emulator addresses in debug builds (network security config); staging uses HTTPS.
67. **Given** a G1 and G2 replay under Robolectric, **When** the captured Logcat (`ShadowLog`), files, SharedPreferences/DataStore and the notification are inspected, **Then** **0** coordinates (regex `-?\d{1,3}\.\d{4,}`), route bodies or search texts are found in logs or storage, and after guidance ends nothing about the trip is kept on the device except the settings (voice, theme, language, orientation).
68. **Given** route and search requests, **When** sent, **Then** coordinates are only in POST bodies (route) or the D30-rounded bias / user-chosen points (search); no coordinates in URLs of route requests. The gateway's no-PII logging (NFR-P1) is unchanged, so no backend change is needed for privacy.

### M. Build and verification
69. **Given** a clean checkout and an Android SDK installed outside the repo (`ANDROID_HOME`), **When** `./gradlew :app:assembleDebug` runs in `mobile/android`, **Then** a debug APK is produced, and `mobile/android/README.md` documents the SDK setup, the gateway URL property and the commands.
70. **Given** the Gradle build files, **When** inspected, **Then** Ferrostar, MapLibre Native and every other dependency are pinned to exact versions (no `+` or dynamic ranges), all resolved from Maven Central or Google's Maven repository, and `minSdk` is **26** (Android 8.0, PO-confirmed, [D61](../decisions.md)).
71. **Given** `./gradlew :app:testDebugUnitTest`, **When** it runs, **Then** it passes and covers at least: the manoeuvre mapping against the shared fixture (AC 29); banner and voice text in `mn` and `en` (AC 26, 32, 33); distance, duration and ETA formatting; the reroute pacer, back-off, 429 and offline rules with a fake clock (AC 44, 47–50); the GPS-loss state machine (AC 51–53); the TTS usable/fallback decision (AC 38–39); the privacy log scan (AC 67); and, with Robolectric, the permission flow (AC 8–13), service start and stop and notification content (AC 15, 17, 19), keep-screen-on (AC 18) and configuration changes (AC 64).
72. **Given** the GPX replay set G1–G9, **When** replayed in tests (Ferrostar's simulated location or a GPX location provider in **debug/test code only**) against the recorded route fixtures, and against the live dev gateway when `/health` is 200 (≤ 2 route requests per second), **Then** the assertions of AC 27, 28, 30, 33, 34, 41, 45, 53 and 56 are checked from the captured banner sequence, the captured TTS input (through a fake TTS) and the request log.
73. **Given** the checks that need a real phone, **When** QA reports, **Then** the report lists them as **not verified in this environment** with the reason, to be run on the reference device once NAV-008 staging is reachable: real TTS audio and the `mn` voice availability (NAV-007 AC 9), real GPS and a real tunnel/underpass, ≥ 10 min of guidance with the screen off, Doze and OEM battery savers, the notification-permission-denied behaviour, audio ducking with music, the chime audibility in a moving car, and battery use per hour.

## Edge cases
- **GPS lost / tunnel / underpass:** AC 51–53 (10 s threshold so short UB underpasses do not flap). No dead reckoning in this slice.
- **Poor accuracy / urban canyon / single jumps:** AC 41 (G6, G7).
- **No network on the route:** AC 54. **No network while off-route:** AC 50. **Weak countryside signal:** 12 s timeout, back-off (AC 48), never a tight loop.
- **Off-route:** AC 41–50. **Repeated off-route on unmapped ger-district tracks** (the driver follows a track that is not in OSM): rate limits (AC 44) and the 200 m rule (AC 49) keep requests bounded; data risk R4.
- **Route needs a U-turn after a reroute:** Valhalla may return one; the banner shows «Буцаж эргэнэ үү» (NAV-004 AC 27).
- **Start far from the route or while moving:** the previewed route is navigated; if the position is > 50 m off, the normal off-route rules reroute.
- **Start in a garage / no fix:** AC 9 («Байршил тодорхойлж чадсангүй», no request).
- **No result / out of coverage:** search AC 3; preview AC 7 (N9); reroute AC 49.
- **Cyrillic/Latin search** ("Sukhbaatar" vs «Сүхбаатар»): sent as typed (AC 3); full assistance and tier-B quality in NAV-011.
- **Unpaved roads:** the avoid toggle (car) is kept for reroutes (AC 43); untagged dirt roads may still be used (R7).
- **Winter:** gloves (48 dp targets, AC 62); short daylight and dark mornings (automatic theme, AC 58); cold drains batteries faster and cars are noisy with the heater on (chime audibility and battery are device checks, AC 73); snow-covered or seasonal roads are routed as in OSM (NAV-004 R9).
- **Phone call during a prompt:** the prompt is skipped (AC 36). **Another app playing music:** ducking (AC 36).
- **Language or theme switch, rotation during guidance:** AC 59, 60, 64.
- **Device without a Mongolian voice** (expected to be common): AC 39.
- **Device without Google Play services:** AC 14.
- **Arrival with the destination far off-road:** AC 57.
- **Device clock wrong:** «Хүрэх цаг» is wrong by the same amount (accepted, as NAV-004).
- **App killed by an OEM battery saver:** guidance stops; restore is NAV-012.

## Data dependencies & risks
| # | Risk | Impact on NAV-005 | Mitigation / owner |
|---|---|---|---|
| R1 | **No usable Mongolian TTS voice** on most Android phones (device risk, NAV-007 R1) | Voice is chime-only for most users; the "Mongolian voice" promise is not met | D23 fallback (AC 39); NAV-007 AC 9 measures it; NAV-016 after the PO's fallback decision |
| R2 | **Valhalla `mn-MN` narrative defects** (NAV-007 F1–F10) | Wrong or English text if Valhalla text leaks | ADR-0008 templates; sentinel test (AC 27); scans (AC 28, 33) |
| R3 | **`maxspeed` coverage low, no traffic** (NAV-001 R8) | «Хүрэх цаг» optimistic in UB rush hour | Accept for Phase 1; traffic in Phase 3 |
| R4 | **Missing or misplaced roads** (new developments, ger-district tracks) | False off-route and repeated reroutes; routes over roads that do not exist | Rate limits (AC 44, 49); `osm-data` lane for human mappers |
| R5 | **One-ways, turn restrictions, access tags** incomplete | Illegal manoeuvres; reroute loops when the driver refuses them | `osm-data` lane; AC 44 bounds requests |
| R6 | **Unnamed streets** (`name` empty) | Banners without a street line; voice without names (names are not spoken in this slice) | AC 21 omits empty names |
| R7 | **`surface` coverage** | Avoid-unpaved reroutes may still use untagged dirt or fail with `NoRoute` | AC 49 secondary line; N12 hint in preview |
| R8 | **Staging latency (~100 ms, D26) and the D9 gate** | Reroute timing looks worse on staging; outside testers wait for NAV-008 AC 24 | Record RTT (AC 45); team-only use of staging |
| R9 | **Rate limits on shared staging behind carrier NAT** | 429 during reroutes for several drivers on one operator NAT | AC 44, 47 |
| R10 | **Ferrostar pre-1.0** (API changes; known issue #969: the visual instruction goes null on deviation before recalculation) | Blank banner or build breaks on upgrade | Pin versions (AC 70); AC 31 "never blank"; architect A2/A6 |
| R11 | **OEM background killers** (Xiaomi, Huawei, Samsung battery savers are common) | Guidance stops with the screen off | Device check (AC 73); NAV-012 |
| R12 | **Personal data:** every reroute sends the current position (D9) | Legal gate before outside testers on the Singapore host | NAV-008 AC 24; AC 65–68 |
| R13 | **Search without query assistance** (ADR-0006 not ported yet) | Latin, ү/у and abbreviation queries miss results | Long-press fallback (AC 4); NAV-011 |
| R14 | **GPS accuracy** in the dense centre and between high-rises | Late off-route detection or jitter | AC 41 thresholds; device check |
| R15 | **Phones without Google Play services** | Crash or no location if the fused provider is required | AC 14; support required ([D62](../decisions.md)), platform `LocationManager` only |
| R16 | **TTS reading of digits and decimal commas** («1,5 километрт») | Mispronounced distances on phones that do have a voice | NAV-007 AC 10 listening test; spelled-out numbers later if needed; a rounded 1,000 m is «1 километрт», not «1000 метрт» ([D67](../decisions.md)) |

### Known limitations (PO-accepted 2026-10-01)
| # | Limitation | Decision | Follow-up |
|---|---|---|---|
| L1 | **No typing lock while the phone moves.** Search on the map and preview screens can be used in a moving car (screen spec › Known limitations 1). The guidance screen has no text input | [D65](../decisions.md) | NAV-011: typing lock with a passenger override (glossary strings requested when NAV-011 is refined) |
| L2 | **320 dp-wide phones at font scale above 100 %:** the banner region may scroll and floating controls may overlap the status message (screen spec › Known limitations 7). Attribution (AC 2) is not relaxed | [D66](../decisions.md) | none planned |
| L3 | **Placeholder application ID** (`.debug` suffix on debug builds). The final ID cannot change after the first Play upload | [D64](../decisions.md) | the PO fixes the final ID (and a product name) before any Play upload |

## Out of scope
- iOS (NAV-015, D24); Android Auto and CarPlay; web active navigation (the web stays the Phase 0 demo and planner).
- Alternatives on the map, «Дугуй», reverse geocoding on the coordinate card, search query assistance, typing lock while moving with a passenger override (NAV-011; D58, D65).
- Rich or lock-screen notification, continuing after swipe-away, restore after process death, Bluetooth routing, automatic sunrise/sunset theme (NAV-012).
- Lane guidance (NAV-013), speed limits and speeding warnings (NAV-014), toll option (D52, [D60](../decisions.md)), waypoints «дайрах цэг», traffic, incidents, offline regions and offline routing, trip history, sharing ETA, OSM-note feedback.
- Server-side TTS or recorded voice packs (NAV-016); street names in voice prompts.
- Any change to the gateway rate-limit, `real_ip` or header files, the shared dev stack, or NAV-002/003/004 web behaviour; any `openapi.yaml` change unless the architect decides one is needed (contract first).
- Release to external users (D17: after NAV-007) and any app-store upload (the final application ID is fixed by the PO before any Play upload, D64).

## Open questions
**None open.** All eight story questions and the test plan questions Q1 and Q2 were decided by the PO on 2026-10-01 ("All as recommended"), see below. **Still owed before any Play upload (not a NAV-005 blocker):** the final application ID and a product name (D64).

### Decided questions (PO, 2026-10-01)
| # | Question | Options considered | Decision |
|---|---|---|---|
| 1 | Priority of NAV-005 | must / should / could | **must** ([D57](../decisions.md)) |
| 2 | First-slice scope vs follow-ups NAV-011–NAV-016 | (a) as written: car + walk, one route, search as typed plus long-press, foreground service without lock-screen polish; (b) also alternatives and «Дугуй» (+M); (c) also the ADR-0006 search assistance port (+M) | **(a)**; alternatives, «Дугуй» and search assistance stay in NAV-011 ([D58](../decisions.md)) |
| 3 | UI language on first launch | (a) always Mongolian, switch in «Хэл» (remembered); (b) follow the device language | **(a)**, AC 60 ([D59](../decisions.md)); matches the code |
| 4 | Toll option (D52 "revisit with NAV-005") | (a) still no «Төлбөртэй замаас зайлсхийх»; (b) add it to «Машин» | **(a)**, AC 5 unchanged ([D60](../decisions.md)) |
| 5 | Minimum Android version | API 25; API 26; API 29 | **API 26** (Android 8.0), AC 70 ([D61](../decisions.md)); matches the code |
| 6 | Phones without Google Play services | (a) required, platform location as baseline; (b) not required | **(a)**, AC 14 ([D62](../decisions.md)); matches the code |
| 7 | Default theme | (a) «Автомат» following the system dark theme; (b) day with a manual toggle; (c) sunrise/sunset (NAV-012) | **(a)**, AC 58 ([D63](../decisions.md)); matches the code |
| 8 | App label and application ID for the debug APK | label «Газрын зураг» now or wait for a product name; placeholder ID or final ID now | label «Газрын зураг» ("Map"), placeholder ID with `.debug`; **final ID fixed before any Play upload** ([D64](../decisions.md)); matches the code. Informational and unchanged: the recenter label is the glossary term «Байршил руу буцах», not «Төвлөрүүлэх» (Avoid, D22) |
| 9 | Typing lock while moving (screen spec › Known limitations 1) | add to NAV-011; add to NAV-005 now; not planned | **NAV-011**, with a passenger override ([D65](../decisions.md)); limitation L1 |
| 10 | 320 dp-wide phones above 100 % font scale (screen spec › Known limitations 7) | accept; redesign | **accepted as a known limitation** ([D66](../decisions.md)); AC 63, limitation L2 |
| 11 | Test plan Q1: rounded 1,000 m in voice | «1 километрт» / "In 1 kilometer"; keep «1000 метрт» | **«1 километрт» / "In 1 kilometer"**, AC 32 changed ([D67](../decisions.md)) |
| 12 | Test plan Q2 (defect NAV-005-D3): `arrive` < 30 m after the previous manoeuvre | exempt it in AC 34; chain it (needs a new glossary form) | **exempt in AC 34**, no chaining, no new glossary term ([D68](../decisions.md)) |

## Traceability
Paths (existing on 2026-10-01): screen spec `docs/design/screens/NAV-005-android-navigation.md`, flow `docs/design/flows/NAV-005-android-navigation.md`, banner/voice/camera rules `docs/design/navigation-ux.md` (§4.1 voice text, §4.2 prompt schedule), ADR-0009 `docs/architecture/adr/0009-android-guidance-client.md` (A1–A7), test plan `docs/qa/test-plans/NAV-005.md`, code `mobile/android/**`, tests `mobile/android/app/src/test/**` and `tests/gpx/nav005/**`, map-style `docs/design/map-style.md` §7.4 "Active navigation on Android (NAV-005)". The per-row Code and Test cells below were not re-mapped in this update; the test plan holds the AC-to-test mapping.

| AC | Screen spec / flow | API operation | ADR | Code | Test | Issues |
|---|---|---|---|---|---|---|
| AC1–7 | Map, search, coordinate card, route preview | `search`, tiles, `postRoute` | ADR-0004 (labels, style assets A3), ADR-0006 (request profile), ADR-0008 | TBD | TBD | D13, D30, D51; D58 (scope), D60 (no toll option); NAV-011 |
| AC8–14 | Permission flow | — | A7 (config) | TBD | Robolectric (TBD) | Glossary A2–A4, A7; D62 (no Play services) |
| AC15–20 | Guidance start/end, notification | `postRoute` (0 extra) | — | TBD | Robolectric (TBD) | NAV-012 |
| AC21–25 | Guidance screen, camera | — | — | TBD | Compose UI tests (TBD) | D21, D22 |
| AC26–31 | Banner | `postRoute` (`maneuver`, `bannerInstructions`) | ADR-0008 + new ADR (A2, A4) | TBD | JVM + replay (TBD) | NAV-007 F1–F10; Ferrostar #969 |
| AC32–40 | Voice spec: `docs/design/navigation-ux.md` §4.1 (AC 32, D67 change pending), §4.2 rule 6 (AC 34, D68 change pending) | `postRoute` (`voiceInstructions`) | ADR-0009 (A2) | TBD | JVM + replay; AC 34 for G8: `QaGpxReplayTest.tcR10_g8Roundabout` (defect NAV-005-D3); voice golden set (G1 «1000 метрт» → «1 километрт», D67) | D23; D67, D68; glossary A1, A5, A8–A13 (A11 PO-revised 2026-10-01, D69: «… хүрнэ»; resources, golden set and tests update pending); NAV-007 AC 9–13 |
| AC41–50 | Off-route state | `postRoute` (`heading`, 429, 5xx) | new ADR (A1, A6) | TBD | JVM + replay (TBD) | D26, NAV-008 AC 8 |
| AC51–54 | GPS / network states | — | — | TBD | JVM + replay (TBD) | — |
| AC55–57 | Arrival | — | — | TBD | replay (TBD) | D51; glossary A11 (D69) |
| AC58–64 | Theme, language, a11y | — | A5 | TBD | Robolectric / Compose (TBD) | D11; D59 (first-launch language), D63 (theme «Автомат»), D66 (320 dp limitation) |
| AC65–68 | — | all | A3, A7 | TBD | interceptor + log scan (TBD) | D9, D35, NFR-P1 |
| AC69–73 | — | — | — | `mobile/android/README.md` | Gradle, QA report (TBD) | NAV-008 (phone test); D61 (minSdk 26), D64 (label, placeholder ID) |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-01 | NAV-005 feature request (orchestrator, feature-delivery; PO "Go" in chat) | Created from the backlog draft row as the **first slice** for Android (D24): 73 AC in sections A–M (map and entry, permissions, foreground service, guidance screen, ADR-0008 banner port, voice with glossary templates and the D23 chime fallback, off-route and rate-limited reroute with 429 `Retry-After`, GPS loss, network loss, arrival, theme/language/accessibility, privacy, build and verification). GPX replay set G1–G9. Design questions A1–A7 for the architect. Glossary rows A1–A13 added (`needs native review`). Follow-ups NAV-011–NAV-016 added to the backlog as drafts. Priority not recorded (PO). Recenter uses the glossary «Байршил руу буцах» instead of the requested «Төвлөрүүлэх» (Avoid, D22). Eight non-blocking open questions | Refine NAV-005 to `ready` for UX and the architect, scoped so it can be built and verified without an Android device in this environment |
| 2026-10-01 | PO answer "All as recommended" in chat to the 12 NAV-005 questions (orchestrator; [D57–D68](../decisions.md)) | Priority **must** (D57). Open questions 1–8 moved to "Decided questions" (D57–D64) with the AC references updated: Context, A5, AC 14, 58, 60, 70, Out of scope. Known limitations L1–L3 added (D64–D66) and AC 63 notes the 320 dp limitation (attribution not relaxed). **AC 32 changed (D67):** a metre value that rounds to 1000 is spoken «1 километрт» / "In 1 kilometer", never «1000 метрт» / "1000 meters"; examples 960 m, 980 m and 1,000 m added. **AC 34 changed (D68):** an `arrive` whose final step is < 30 m is exempt from the 20–250 m check, with no chaining, no new glossary term and the arrival prompt still produced exactly once; nothing else in AC 34 changes. NAV-011 gains the typing lock (D65). Traceability paths updated to the existing artefacts; AC 32–40 row names navigation-ux §4.1/§4.2, `tcR10_g8Roundabout` and the voice golden set. D59, D61, D62, D63 and D64 checked against `mobile/android`: all match | Record the PO's decisions. D67 removes two spoken forms for the same distance (test plan Q1). D68 settles QA defect NAV-005-D3 (test plan Q2): the spec already skips the approaching prompt below 30 m and never chains `arrive`, so the AC now matches the design |
| 2026-10-01 | PO native review of the glossary rows on the review page ([D69](../decisions.md), relayed by the orchestrator) | **User-facing strings:** A1–A10, A12 and A13 approved as written (`PO-approved 2026-10-01 (panel pending)`), no wording changed. **A11 changed by the PO:** «{n} метрт очих газартаа ирнэ» → «{n} метрт очих газартаа хүрнэ» (`PO-revised 2026-10-01 (panel pending)`; `en` unchanged "In {n} meters, you will arrive"); the glossary section 2 row "Approaching destination (voice)" changed with it. The arrival text «Та очих газартаа ирлээ» is unchanged. **AC 32:** approaching bullet quotes the new form, with the example «200 метрт очих газартаа хүрнэ». **AC 55:** approaching prompt quotes the new form. **Banner ramp texts** (AC 26 via the NAV-004 AC 27 table, glossary N14/N15) are now «Орох зам руу эргэнэ үү» / «Гарах зам руу эргэнэ үү» and the side forms (`PO-delegated 2026-10-01 (panel pending)`); in voice they take a lower-case first letter after a prefix as AC 32 says, for example «300 метрт гарах зам руу эргэнэ үү». **Traceability** AC32–40 and AC55–57 note D69. No threshold, timing or other AC changed | PO wording decision (A11) and the delegated fix of a translation error (N14/N15). AC 28 and AC 33 scans apply unchanged to the new forms. Android `strings.xml`, the voice text generator, `tests/gpx/nav005/golden/voice-golden.tsv` and the Android tests that quote «…ирнэ» or the old ramp texts must be updated by their owners; until then AC 32, AC 55 and AC 61 fail on those strings |

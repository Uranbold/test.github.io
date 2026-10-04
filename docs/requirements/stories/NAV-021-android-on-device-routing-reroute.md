---
id: NAV-021
title: Android on-device routing and reroute (valhalla-mobile in a separate process behind RouteRequester), online first with the 3.0 s fallback, Gate 1 parity on the shipped library, device benchmark
phase: 1
priority: must          # P1 / standard, feature, PO-confirmed 2026-10-04 (D193); BA MoSCoW mapping P1 → must
size: L                 # engine + :routing process + AIDL/pipe transport, fallback policy, reroute integration, Gate 1 CI, device benchmark, offline indicator
needs_design: true      # "offline" indicator on routes (D201) on the preview and, if confirmed, during guidance
needs_backend: false    # files come from NAV-020; no contract change
needs_mobile: true      # Android (mobile/android)
status: ready           # 2026-10-04. Delivery slot 2: first on the mobile side, after the NAV-005 location-dot change (D197). Non-blocking open questions only
---

# NAV-021: Android on-device routing and reroute

## Story
As an **intercity / countryside driver** with weak or no signal, I want **the app to compute a new route, and a new route after I leave the planned one, on my phone when the internet does not answer**, so that **I still get turn-by-turn guidance to my destination between soum centres and in dead zones, and a native engine problem never ends the guidance I am already following**.

Secondary personas:
- **Taxi / delivery driver:** a reroute in a UB underpass or in a dead zone at the city edge still arrives within seconds instead of waiting for the network.
- **UB commuter by car:** online routes are unchanged when the network is fine (online first, D163).
- **Pedestrian:** «Явган» routes work offline too.
- **Tourist (English UI):** the same behaviour; route text comes from the client templates in both languages (ADR-0008), so the engine version never changes the wording.

## Context
- **Request and decisions.** Offline is a launch requirement for the real Android app ([D156–D159](../decisions.md)): new route and reroute must work without internet ([D157](../decisions.md)). Spike §9 item 5; [ADR-0017](../../architecture/adr/0017-offline-mongolia-pack-android.md) §2 (engine in a separate process), §4 (Gate 1), §5 (fallback order). Triage 2026-10-04 row "Offline spike follow-up §9 item 5": feature, P1 / standard ([D193](../decisions.md)); it **absorbs Gate 1** of the Valhalla version coupling.
- **PO decisions that apply:**
  - [D163](../decisions.md): with a pack and internet, route preview and reroute go **online first**, with the on-device fallback; with a pack and no network, on-device; without a pack, today's behaviour
  - [D199](../decisions.md): the on-device fallback **starts ≤ 3.0 s after the online request was sent** without a usable answer, **or at once** when the device reports no validated network
  - [D201](../decisions.md): routes that came from the pack show a small **"offline" indicator** (UX designs it; glossary OF24 / OF25)
  - [D197](../decisions.md): delivery slot 2, the first mobile item; it carries the largest technical risk, so the device benchmark and the process-isolation proof come first (section A)
- **Constraints carried from review (they are AC here, not options):** the parity gate runs the **shipped** `valhalla-mobile` library (AC 33); the engine runs in a **separate Android process** so a native crash cannot end guidance (AC 4, AC 22–25).
- **What exists today.** `RouteRequester` is HTTP only (ADR-0009 §2); classification → keyed text rewrite → Ferrostar `createOsrmResponseParser(6u)` (ADR-0009 §3.1); `ReroutePolicy` pacing (NAV-005 AC 44); NAV-005 AC 47–50 reroute error states; NAV-012 restore on the stored route (D118). Valhalla's narrative is always replaced on the client, so no user-visible text depends on the engine version.
- **Files on the phone.** The installed `routing.tar` comes from [NAV-022](NAV-022-android-offline-pack-download-update-map.md) (delivery slot 3, after this story). Until NAV-022 exists, debug builds get a routing file through a debug-only provisioning path (AC 3). Published files come from [NAV-020](NAV-020-offline-pack-build-publication.md).
- **Not changed by this story:** NAV-005, NAV-011 and NAV-012 AC. Where their AC describe the no-network states, the change requests 7a (NAV-005) and 7c (NAV-012) align them after this story is written ([D194](../decisions.md)); until then, this story states the behaviour **with an installed routing file**, and **without** one the NAV-005 / NAV-011 / NAV-012 behaviour and tests stay exactly as they are (AC 30).
- **Coordination (binding):** `mobile/android` is shared with the running NAV-005 location-dot change and with NAV-019 (`NavApplication.kt`, `AppModule.kt`): read the latest file first, make small targeted edits, never revert another story's changes. Never stop or rebuild the shared dev stack at `http://127.0.0.1:8080`.

### Terms used in this story
| Term | Meaning here |
|---|---|
| **Routing file** | The installed `routing.tar` of one file version (NAV-022 `active.json`) |
| **On-device engine** | `valhalla-mobile` (pinned; 0.6.3 = Valhalla 3.6.3 at the time of writing), used unmodified, inside the `:routing` process |
| **Online attempt** | One `POST /v1/route` to the gateway (`postRoute`) for a preview or a reroute |
| **Fallback** | The on-device engine answering the same request body in the same attempt |
| **Validated network** | Android reports `NET_CAPABILITY_VALIDATED` on the active network |
| **Usable online answer** | Response headers received within 3.0 s, then a response handled by today's classification (ADR-0009) |
| **Authoritative answer** | `Ok`, `NoRoute`, `OutOfCoverage` (`NoSegment`), `TooFar` (`DistanceExceeded`) or another 400 from the gateway: shown as today, never retried locally |
| **Benchmark phones** | *Mid-range:* 6–8 GB RAM, released 2022 or later, Android 12+. *Low-end:* 3–4 GB RAM, Android 10+. Model and Android version are recorded in the report |

### User-facing strings
Every Mongolian string comes from `docs/requirements/glossary.md`. **New rows (glossary section 2.7, `needs native review`):** OF24 «Офлайн» (indicator) and OF25 «Офлайн газрын зургаас» (its accessible name). Everything else is reused unchanged: the NAV-005 / NAV-011 preview and reroute rows («Маршрутыг дахин тооцоолж байна», «Маршрутын үйлчилгээ түр ажиллахгүй байна», «Маршрут олдсонгүй», N9 «Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна», N11 «Энэ зай явганаар эсвэл дугуйгаар хэт хол байна», «Интернэт холболт алга», «Алдаа гарлаа», «Дахин оролдох»).

## Acceptance criteria

### A. Proof first: engine, process isolation and device benchmark
1. **Given** this story starts, **When** the first mobile change lands, **Then** it is a build that adds the pinned `valhalla-mobile` dependency, the `:routing` process and a debug-only benchmark entry, and the AC 4, AC 22 and AC 34 results on the PO's Android phone are reported to the orchestrator **before** the fallback UI work (sections C–F) is merged. A failed benchmark threshold is reported with the measured values; the PO decides whether to continue (Open question 3).
2. **Given** the release APK, **When** it is inspected, **Then** `valhalla-mobile` is the exact pinned version (no fork or patch by us), the native library is present for every ABI the app ships, and its notices are listed for the licences screen (D195; MIT, BSD-3, BSL-1.0, BSD-2 per ADR-0017 §7). The APK grows by **≤ 4 MB** per ABI split download and **≤ 12 MB** installed (*BA proposal*; spike estimate +3.3 MB / +10 MB).
3. **Given** a debug build and a local `routing.tar` (for example copied from the dev stack's `backend/data/valhalla/valhalla_tiles.tar`, never committed), **When** the developer uses the debug-only provisioning path documented in `mobile/android/README.md` (for example an `adb push` into the pack directory plus a debug command that writes `active.json`), **Then** the app treats it as an installed routing file. The release APK contains **0** classes or entry points of this path.
4. **Given** the app with an installed routing file, **When** the first on-device request runs, **Then** the engine runs in a separate process (`:routing`, bound service): `libvalhalla` is mapped only in the `:routing` process (checked through `/proc/<pid>/maps` or an equivalent instrumented test), and the main process's DI graph, MapLibre, notification channels and WorkManager initialisation are **not** run in `:routing` (a log-free test hook or instrumented test shows 0 such initialisations there).

### B. Same request, same answer (parity)
5. **Given** a preview or reroute, **When** the on-device engine is used, **Then** it receives the **exact** ADR-0009 §2 body the gateway would get (same fields, `language`, `units`, `costing`, `costing_options`, `heading` rule, `alternates`), and its OSRM bytes go through the **same** classification, keyed text rewrite and Ferrostar parser as online responses. A JVM test with a fake engine asserts byte-equal request bodies for AC 5 (NAV-005), AC 43 (NAV-005) and the NAV-011 `alternates: 2` preview.
6. **Given** the on-device engine's OSRM result, **When** it is classified, **Then** `Ok` → `Ok`, `NoRoute` → `NoRoute`, `NoSegment` → `OutOfCoverage` (shows N9), `DistanceExceeded` → `TooFar` (shows N11), and any other error, exception, timeout or process death → `Unavailable` (local engine), which shows the existing preview / reroute error states.
7. **Given** the golden route set (NAV-020 AC 9) and a `routing.tar` from the server builder, **When** the app's on-device path (in the Gate 1 environment, AC 33) routes each request, **Then** every result equals the server engine's result on the same tar by the NAV-020 AC 10 rule (code, distance to 1 m, duration to 1 s, manoeuvre sequence, street names, geometry at 6 decimals, alternates).

### C. Fallback rule (D163, D199)
8. **Given** an installed routing file and **no** validated network, **When** a preview or reroute is requested, **Then** the on-device engine answers at once, and **0** HTTP route requests are sent (network capture).
9. **Given** an installed routing file and a validated network, **When** a preview or reroute is requested, **Then** the online attempt is sent first, and the request is cancelled and answered by the on-device engine in the same attempt when, before response headers arrive: the connection fails (`Offline`), the gateway answers 502 / 503 / 504 (`Unavailable`) or 429 (`RateLimited`), **or no response headers arrive within 3.0 s** of sending. Measured: in JVM tests with a fake clock and a mock server that delays headers by 10 s, the on-device call starts at **3.0 s**; on a benchmark phone with a delaying test server, the measured start is **≤ 3.2 s** after sending (200 ms measurement tolerance for scheduling).
10. **Given** response headers arrived within 3.0 s, **When** the body is read, **Then** the existing client timeouts apply (12 s for routes) and the answer is handled as today; an authoritative answer (Terms) is shown as today and **0** on-device requests are made for it.
11. **Given** a fallback caused by a timeout or `Offline`, **When** further preview or reroute requests come within **60 s**, **Then** they go straight to the on-device engine with **0** online attempts, until 60 s have passed **or** the network callback reports a **newly** validated network, whichever comes first.
12. **Given** the gateway answered 429 with `Retry-After: N`, **When** requests come during the next N seconds (5 s if missing or unreadable, as NAV-005 AC 47), **Then** **0** online attempts are sent and the on-device engine answers them; after N seconds online-first resumes.
13. **Given** no installed routing file (or on-device routing disabled by AC 24), **When** a preview or reroute is requested, **Then** behaviour is exactly today's (NAV-005 AC 47–50, NAV-011), and the `:routing` process is never started.

### D. Reroute during guidance
14. **Given** guidance with an installed routing file, **When** reroutes are counted over any sequence of off-route episodes, **Then** NAV-005 AC 44 still holds (at most **1** in flight, ≥ **5 s** apart, ≤ **6** per rolling **60 s**), where one attempt = the online try plus its fallback. The NAV-005 AC 48 back-off (5, 10, 20, 30 … s) applies only when **both** sources fail.
15. **Given** G2 (off-route) replayed with an installed routing file and **no** validated network, **When** off-route is detected, **Then** the banner shows «Маршрутыг дахин тооцоолж байна» within **1 s** (NAV-005 AC 42), the secondary line «Интернэт холболт алга» is **not** shown, **0** HTTP requests are sent, and from detection to the new route on screen takes **≤ 2.0 s** on the mid-range and **≤ 4.0 s** on the low-end benchmark phone for ≥ **9 of 10** replays (*BA proposal*).
16. **Given** G2 replayed with an installed routing file and a gateway that delays headers by 10 s, **When** off-route is detected, **Then** the new route is on screen **≤ 5.0 s** after detection on the mid-range phone for ≥ **9 of 10** replays (3.0 s budget plus local compute).
17. **Given** a routing file is installed or updated by NAV-022 while guidance runs, **When** on-device reroutes happen in that guidance session, **Then** they all use the routing file version that was active when guidance started; the next on-device request after guidance ends uses the new version. A route never joins two routing file versions.
18. **Given** a trip previewed online and the network drops during guidance, **When** a reroute is answered on the device, **Then** the new route replaces the whole remaining route (no join with the online route), and guidance continues with normal prompts within **500 ms** of the answer (NAV-005 AC 45).
19. **Given** guidance on an on-device route and the network returns, **When** the user stays on the route, **Then** **0** route requests are sent (NAV-005 AC 54 unchanged); the next off-route episode uses online first again.
20. **Given** guidance is restored after process death (NAV-012 restore on the stored route, D118) with no validated network and an installed routing file, **When** an off-route happens, **Then** the reroute is answered on the device as AC 15. *(NAV-012 itself is aligned by change 7c.)*
21. **Given** the engine is bound lazily, **When** a routing file is installed and the network is lost during guidance, **Then** the `:routing` service is bound within **5 s** of the loss (before an off-route), so the first offline reroute does not pay the cold start. While guidance runs, the main process binds with `BIND_IMPORTANT`.

### E. Native crash and resource safety
22. **Given** guidance on G1 with an installed routing file, **When** the `:routing` process is killed (`adb shell kill -9 <pid>`, or a test hook that aborts the native engine) at **3** different moments (idle, during a preview request, during a reroute request), **Then** each time the main process survives, banner, voice, progress and arrival keep working on the current route, the in-flight request is classified `Unavailable` (existing error state), and the next request rebinds and is answered on the device.
23. **Given** the on-device engine does not answer within **10 s**, **When** the timeout fires, **Then** the request is classified `Unavailable`, the binding is dropped, and the next request rebinds.
24. **Given** the `:routing` process dies **3** times within **10 minutes**, **When** a fourth request comes, **Then** on-device routing is disabled until the next app start or the next routing file install, AC 13 behaviour applies, and a crash counter without coordinates may be recorded locally.
25. **Given** the longest golden route (Choibalsan → Ölgii, car, banners and voice, more than 1 MB of OSRM JSON), **When** it is answered on the device, **Then** it is delivered intact (same SHA-256 as the engine output) through a pipe or cache file, never inside a binder transaction, and **0** `TransactionTooLargeException` occur.
26. **Given** the app is in the background and not guiding, **When** Android reports `onTrimMemory(RUNNING_CRITICAL)` (or a test sends it), **Then** the `:routing` service is unbound within **5 s**; during guidance it stays bound.

### F. "Offline" indicator on routes (D201)
27. **Given** a preview route computed on the device, **When** the preview renders, **Then** each on-device route option shows the indicator OF24 «Офлайн» (`en` "Offline") as designed by UX, with the accessible name OF25 «Офлайн газрын зургаас» (`en` "From the offline map"); a route from the gateway shows **no** indicator. The indicator never covers the route summary, «Эхлэх» or the OSM attribution, and meets contrast ≥ **4.5:1** in both themes.
28. **Given** guidance follows a route computed on the device (preview or reroute), **When** the guidance screen shows, **Then** the indicator OF24 is visible in the progress area until guidance ends or a reroute from the gateway replaces the route; it never covers the banner, «Дуусгах», the recenter control or the OSM attribution, and is not announced by TalkBack more than once per route. *(BA proposal, Open question 1.)*

### G. Gate 1: compatibility with the shipped library (CI)
29. **Given** the app, **When** it is built, **Then** it contains a compiled-in allow-list of `graph_builder` values (initially `valhalla 3.9.0`), and a routing file whose manifest `format.graph_builder` is not on the list is never used by the engine (NAV-022 does not offer it; a provisioned file is refused with a log line without coordinates).
30. **Given** no routing file is installed, **When** the existing Android suite runs (`./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required`) and the NAV-005 GPX replays G1–G9 run, **Then** all pass unchanged.
31. **Given** an installed routing file and no validated network, **When** G1, G5, G8 and G9 are replayed, **Then** guidance results equal the NAV-005 expectations for those tracks (same manoeuvres and prompts, voice golden set unchanged), because the parsed route is equal by AC 7.
32. **Given** a change to the pinned `valhalla-mobile` version, the server Valhalla version, or the golden route set, **When** CI runs, **Then** the Gate 1 job runs.
33. **Given** the Gate 1 job, **When** it runs, **Then** it routes the golden set with **the shipped AAR**: an instrumented test on an x86_64 emulator that loads the same AAR version through the same `:routing` service if the AAR has an x86_64 ABI, otherwise a host build of the same Rallista fork commit with the same build options (the NAV-020 Gate 2 image may be reused). It compares with the server engine on the same tar by the NAV-020 AC 10 rule. A pass is the only way a `graph_builder` value enters the allow-list; any difference fails the job and blocks the version change. The upstream Valhalla Docker image is never the gate.

### H. Device benchmark (acceptance)
34. **Given** a release-like build on the mid-range and the low-end benchmark phone with an installed routing file, **When** the benchmark runs P1 → P3 car, UB → Darkhan car and Choibalsan → Ölgii car (first request after app start = cold; then 15 requests per route = warm), **Then** the report gives cold time, warm p50 / p95 and the `:routing` process peak PSS, and these hold (*BA proposal*, Open question 3):

    | Measure | Mid-range | Low-end |
    |---|---|---|
    | Cold, UB routes (incl. binding and opening the tar) | ≤ 1.5 s | ≤ 3.0 s |
    | Cold, Choibalsan → Ölgii | ≤ 2.5 s | ≤ 5.0 s |
    | Warm p95, each route | ≤ 0.5 s | ≤ 1.5 s |
    | `:routing` peak PSS (engine cache 32 MiB) | ≤ 160 MiB | ≤ 160 MiB |

35. **Given** the low-end benchmark phone, **When** a **30-minute** G1 replay loop runs in guidance with an on-device reroute every **120 s** and the screen on, **Then** the main process is never killed (no low-memory kill in `adb logcat`/`dumpsys activity exit-info`), guidance never stops, and every reroute is answered.

### I. Privacy, network and strings
36. **Given** a full session with on-device routes and reroutes (G1, G2, G5 replays, with and without network), **When** the captured Logcat (`ShadowLog` in Robolectric, `adb logcat` on the device), files and preferences are inspected, **Then** **0** coordinates (regex `-?\d{1,3}\.\d{4,}`), route bodies or OSM IDs are found in logs or storage outside the existing NAV-012 restore record, and the crash counter contains no coordinates.
37. **Given** on-device routing without a validated network, **When** all network traffic is captured, **Then** **0** requests are sent by routing. With network, every request still goes only to the configured gateway (NAV-005 AC 65).
38. **Given** the Android resources, **When** the NAV-005 AC 61 checks run, **Then** they pass, the `mn` values of OF24 and OF25 match the glossary exactly, `en` values exist, and no user-facing literal is hard-coded.

### J. Verification
39. **Given** the deliverables, **When** QA reports, **Then** the report covers: JVM / Robolectric tests for sections B–G (fake engine, fake clock, mock server), the Gate 1 job result, the AC 22 crash test, and the device results of AC 1, 15, 16, 34, 35 on the named phones. Checks that need a phone not available are listed as **not verified** with the reason (Open question 3).

## Edge cases
- **GPS lost** during guidance: NAV-005 AC 51 unchanged; **0** reroutes (online or on-device) while the loss lasts.
- **No network at all** (countryside, airplane mode): AC 8, AC 15; the preview and reroute work from the pack with **0** requests.
- **Captive portal or Wi-Fi without internet** (network not validated): treated as no network (AC 8).
- **Slow mobile data** (headers after 3.0 s): AC 9; the late online answer is discarded.
- **Network flaps** while typing a new destination: stickiness (AC 11) avoids a 3 s wait per request.
- **429 from the gateway:** AC 12; the user still gets a route from the device.
- **Off-route in a ger district with «Шороон замаас зайлсхийх» on:** `exclude_unpaved` is in the body (AC 5); if every road is unpaved, `NoRoute` → «Маршрут олдсонгүй» with the NAV-005 AC 49 retry rule, as online.
- **Out of coverage** (destination in Beijing, or a border crossing): `NoSegment` → N9 (AC 6), as online.
- **Long countryside route** (UB → Khovd, Choibalsan → Ölgii): AC 25, AC 34.
- **Routing file older than the map or than online data:** routes can include a road not yet drawn on the offline map (monthly tiles, D166), or miss a road built this week; the indicator (AC 27, 28) tells the user the route came from offline data.
- **Pack incompatible with the app** (`graph_builder` not on the allow-list): AC 29; online routing still works when online; offline the NAV-005 no-network states apply.
- **Engine crash loop** on a broken file: AC 24, plus NAV-022 self-tests before install.
- **Winter:** cold phones throttle CPU and batteries drain faster; the low-end benchmark (AC 35) is the proxy. No winter-specific routing.
- **Cyrillic / Latin search:** not applicable (NAV-023).

## Data dependencies & risks
| # | Risk | Impact | Mitigation / owner |
|---|---|---|---|
| R1 | **Engine version coupling** (wrapper 3.6.3 on the server's 3.9.0 graph; Rallista fork, small maintainer group) | Offline routing stops or differs after an upgrade | Gate 1 (AC 33), allow-list (AC 29), NAV-020 Gate 2; fallback option: own JNI build of upstream Valhalla (ADR-0017 alternatives, +10–15 person-days). Architect, mobile |
| R2 | **Device memory** with MapLibre, TTS, Ferrostar and Valhalla on 3–4 GB phones | Low-memory kills during guidance | Separate process (AC 4), 32 MiB cache, AC 26, benchmark AC 34–35. Mobile, QA |
| R3 | **Binder and native crash behaviour** differs per OEM | Lost reroutes, or worse, lost guidance | Pipe transfer (AC 25), crash tests (AC 22–24). Mobile |
| R4 | **No low-end test phone yet** (the PO has one Android phone, D154) | AC 34–35 low-end column not verified | Open question 3. PO |
| R5 | **Data age:** offline routes use the weekly graph (≤ about 7 days old), online uses the daily graph | A road closed or opened this week is wrong offline | Online first (D163), indicator (AC 27–28), data dates in Settings (NAV-022). Accepted by D166 |
| R6 | **OSM data quality** (`surface`, `maxspeed`, one-way, countryside tracks) | ETAs and countryside routes no better offline than online | Backlog data-quality risks; human mapping via triage lane `osm-data` |

## Out of scope
- Download, install, update and delete of the routing file, and the map from the pack (NAV-022).
- Offline search and reverse (NAV-023).
- The NAV-005 / NAV-012 AC text changes (changes 7a, 7c, after this story, D194).
- Traffic-aware offline routing, multi-stop routes, toll avoidance (no toll option, D52 / D61).
- iOS (`valhalla-mobile` Swift, NAV-015 line, D162).
- Map matching with `trace_route` (possible later).
- Our own JNI build of Valhalla (fallback only, R1).

## Open questions
Non-blocking; each has a working assumption in the AC.
1. **"Offline" indicator during guidance** (the D201 detail left to the story). Options: (a) show OF24 in the progress area while the followed route came from the pack (AC 28); (b) only on the preview, not during guidance. *Recommendation: (a).* A driver who rerouted offline is on week-old data and should be able to see it, and a static chip needs no interaction. UX keeps it small.
2. **Benchmark thresholds marked *BA proposal*** (AC 2, 15, 34). *Recommendation:* accept them as acceptance values; change them only through the BA if the mid-range phone misses them by a small margin and the PO accepts the measured values.
3. **Benchmark phones.** The PO has one Android phone (model not recorded yet). Options: (a) the PO's phone as the mid-range or low-end class, plus a second phone of the other class borrowed or bought by the PO; (b) a remote device lab (an external service; PO decides on cost and data); (c) only the PO's phone, the other class listed as not verified. *Recommendation: (a).* A real low-end phone is the main memory risk (R2).

## Traceability
| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC1–AC4 | — | — | `valhalla-mobile` dependency, `:routing` service, debug provisioning (mobile, TBD) | instrumented / device checks | D193, D197; ADR-0017 §2 |
| AC5–AC7 | — | `postRoute` (body equivalence) | `OnDeviceRouteRequester` (mobile, TBD) | JVM tests; Gate 1 | ADR-0009 §2, §3.1 |
| AC8–AC13 | — | `postRoute` | fallback policy (mobile, TBD) | JVM tests with fake clock and mock server; device timing | D163, D199 |
| AC14–AC21 | NAV-005 screen spec (reroute states, unchanged strings) | `postRoute` | `ReroutePolicy` integration (mobile) | GPX replays G1, G2, G5, G8, G9; device timing | NAV-005 AC 42–50, 54; NAV-012 (D118); change 7a, 7c |
| AC22–AC26 | — | — | crash handling, pipe transport, lifecycle (mobile) | instrumented crash tests | ADR-0017 §2 |
| AC27–AC28 | UX: offline indicator spec (TBD) | — | indicator UI (mobile) | Compose / Robolectric tests, TalkBack check | D201; glossary OF24, OF25; Open question 1 |
| AC29–AC33 | — | — | allow-list, Gate 1 CI job (mobile, QA) | Gate 1 job; regression suite | ADR-0017 §4; NAV-020 AC 9–10 |
| AC34–AC35 | — | — | benchmark entry (debug) | device benchmark report (`docs/qa/`) | Open questions 2, 3 |
| AC36–AC38 | — | `postRoute` | logging, network, resources | log scan, network capture, glossary check | NAV-005 AC 61, 65, 67 |
| AC39 | — | — | — | QA report | |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-04 | Offline spike follow-up §9 item 5 (triage log 2026-10-04; PO "all as recommended", [D193](../decisions.md), [D197](../decisions.md), [D199](../decisions.md), [D201](../decisions.md)) | Story written: 39 AC in sections A–J (proof first, parity, fallback rule, reroute, crash safety, offline indicator, Gate 1, device benchmark, privacy and strings, verification). Absorbs Gate 1. Glossary OF24 / OF25 used. Status `ready` | Launch-blocking (D159); largest technical risk of the offline epic, so it goes first on the mobile side |

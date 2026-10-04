# NAV-021 task breakdown: Android on-device routing and reroute (mobile)

- **Story:** [NAV-021](../../requirements/stories/NAV-021-android-on-device-routing-reroute.md) (P1 / standard, D193; delivery slot 2, the first mobile item after the NAV-005 location-dot change, D197)
- **Design:** [ADR-0017](../adr/0017-offline-mongolia-pack-android.md) §2, §4, §5 and **Amendment A1** (engine pins, on-device config, Gate 1 path); ADR-0009 §2, §3.1 (route body, classification, keyed rewrite); ADR-0013 (foreground service, restore)
- **Contract:** no change. `postRoute` is used as today; the routing file comes from NAV-020 / NAV-022
- **Owner of this file:** architect. **Date:** 2026-10-04. Task list only, no code
- **Coordination:** `mobile/android` is shared with the running NAV-005 location-dot change and with NAV-019 (`NavApplication.kt`, `AppModule.kt`). Read first, make small edits, never revert others' work. Do not start before the location-dot change has landed (D197)

## Components this story creates and the later stories reuse
| Component | Created here | Reused by |
|---|---|---|
| `NetworkStateSource`: validated / metered / newly-validated events from `ConnectivityManager.NetworkCallback` | R7 | NAV-022 (Wi-Fi vs mobile data), NAV-023 |
| `OnlineFirstPolicy`: 3.0 s header budget, stickiness, 429 window, authoritative pass-through | R7 | NAV-023 (search, reverse) |
| `PackFiles` reader: reads `noBackupFilesDir/packs/active.json` (format defined in the NAV-022 task file §1); debug provisioning writes the same format | R4 | NAV-022 replaces the writer with `PackManager`; the format is unchanged |
| `:routing` service `selfTest(route)` | R2/R3 | NAV-022 install self-test (AC 16) |

## Mobile task list
**R1. Dependency, ABIs, licences (first PR, together with R2, R3 and R5).**
- Add `io.github.rallista:valhalla-mobile:0.6.3` to `gradle/libs.versions.toml`. This also arms the NAV-020 B15 server check.
- No fork, no patch. Keep ABI splits (or an AAB) as today. Record the APK size delta per ABI.
- List the notices for the D195 licences screen: `valhalla-mobile` MIT; Valhalla MIT; protobuf BSD-3; Boost BSL-1.0; lz4 BSD-2. Take the list from the dependency, not from memory.
- Checks: AC 2: version exact; native library present for every shipped ABI (the AAR has arm64-v8a, armeabi-v7a, x86, x86_64, ADR-0017 A1 F1); ≤ 4 MB download and ≤ 12 MB installed per ABI.

**R2. `:routing` process and bound service.**
- `OnDeviceRoutingService` declared with `android:process=":routing"`. The AIDL is small: `route(requestJson, sink: ParcelFileDescriptor): errorKind` and `selfTest(...)`. The request (a few KB) goes in the binder call; the OSRM bytes are written to the pipe's write end, never into the binder reply.
- The application class detects the process: `Application.getProcessName()` on API 28+, `/proc/self/cmdline` on API 26–27. In `:routing` it returns before the DI graph, MapLibre, notification channels and WorkManager. androidx.startup providers already run only in the main process.
- Checks:
  - an instrumented test reads `/proc/<pid>/maps`: `libvalhalla-wrapper` is mapped only in `:routing`
  - a test hook counts 0 main-process initialisations in `:routing`
  - (AC 4)

**R3. Engine host inside `:routing`.**
- Build the config from the pinned AAR's `default.json` with exactly the ADR-0017 A1 item 5 overrides: `tile_extract` = the installed tar; `max_cache_size` 33554432; `tile_dir`, `traffic_extract`, `admin`, `timezone` and `landmarks` empty; `service_limits` not overridden.
- One engine per routing-file version, opened lazily.
- The version is **captured at guidance start**. Reroutes in that session use it, and a new install takes effect after guidance (AC 17).
- A unit test asserts the override values. The same values are asserted by backend Gate 2 (NAV-020 B6).
- Checks: AC 17; the config unit test.

**R4. Debug-only provisioning.**
- In the `debug` source set only: a documented `adb push` of a local `routing.tar` (for example a copy of the dev stack's tar, never committed) into the pack directory, plus a debug command (for example a debug receiver or a debug Settings row) that writes `active.json` in the NAV-022 format.
- Document it in `mobile/android/README.md`.
- Checks: AC 3. The release APK has 0 classes of this path (a `dexdump` / `apkanalyzer` check in CI).

**R5. Benchmark entry (debug-only), first deliverable.**
- Measure P1 → P3, UB → Darkhan and Choibalsan → Ölgii: cold (first request after app start, including bind and tar open), then 15 warm requests each. Record wall time and the `:routing` peak PSS (`Debug.getPss` / `dumpsys meminfo`).
- Output a plain report (no coordinates other than these fixed routes).
- Run it on the PO's phone and report the AC 4, 22 and 34 results to the orchestrator **before** sections C–F are merged.
- Checks: AC 1, AC 34 (thresholds per the story; a miss is reported with values and the PO decides).

**R6. `OnDeviceRouteRequester`.**
- Implements the existing `RouteRequester` with the **exact** ADR-0009 §2 body that the HTTP requester sends: build the body once and hand the same string to either transport.
- The OSRM bytes go through the unchanged classification → keyed rewrite → `createOsrmResponseParser(6u)`.
- Classification: `Ok`; `NoRoute`; `NoSegment` → `OutOfCoverage`; `DistanceExceeded` → `TooFar`. Anything else, an exception, a 10 s timeout or a process death → `Unavailable`.
- Checks:
  - a JVM test with a fake engine asserts byte-equal bodies for NAV-005 AC 5, AC 43 and the NAV-011 `alternates: 2` preview
  - a classification table test
  - (AC 5, 6)

**R7. Network state and fallback policy (shared, see the table above).**
- `NetworkStateSource` on `NET_CAPABILITY_VALIDATED` and `NOT_METERED`, plus a "newly validated" event.
- `OnlineFirstPolicy`:
  - no validated network → local at once, 0 requests
  - otherwise online with a **3.0 s budget to response headers**. On `IOException`, 502/503/504, 429 or the budget expiring, cancel and answer locally in the same attempt
  - after headers arrive, the existing body timeouts apply (route 12 s)
  - authoritative answers are never retried locally
  - stickiness 60 s, or until a newly validated network
  - 429: local answers during `Retry-After` (5 s if missing or unreadable)
- `FallbackRouteRequester` composes the HTTP requester, the on-device requester and the policy.
- When no routing file is installed (or on-device routing is disabled by R9), the policy is bypassed and today's path runs unchanged; `:routing` is never started.
- Checks:
  - JVM tests with a fake clock and a mock server delaying headers by 10 s: local start at exactly 3.0 s
  - stickiness and newly-validated tests
  - a 429 window test
  - an authoritative-answer test (0 local calls)
  - device timing ≤ 3.2 s with a delaying test server (not the shared stack)
  - (AC 8–13)

**R8. Reroute integration.**
- `ReroutePolicy` counts one attempt per online try plus its fallback (≤ 1 in flight, ≥ 5 s apart, ≤ 6 per 60 s). The NAV-005 AC 48 back-off applies only when both sources fail.
- A reroute replaces the whole remaining route.
- With an installed file, pre-bind `:routing` within 5 s of losing the validated network during guidance. Bind with `BIND_IMPORTANT` while guiding.
- The NAV-012 restore path uses the same requester (no special case).
- Checks:
  - G2 replay offline: «Маршрутыг дахин тооцоолж байна» ≤ 1 s, no «Интернэт холболт алга», 0 HTTP requests, new route ≤ 2.0 s (mid-range) / ≤ 4.0 s (low-end) in ≥ 9 of 10 runs
  - G2 with 10 s header delay: ≤ 5.0 s
  - AC 18 (prompts within 500 ms), AC 19 (0 requests back online), AC 20 (restore), AC 21 (pre-bind)
  - (AC 14–21)

**R9. Crash and resource safety.**
- `linkToDeath`, `DeadObjectException` and the 10 s timeout map to `Unavailable`, drop the binding, and rebind on the next request.
- 3 deaths in 10 min → disabled until the next app start or the next routing install. A crash counter is kept locally, without coordinates.
- `onTrimMemory(RUNNING_CRITICAL)` when not guiding → unbind within 5 s.
- Checks:
  - `kill -9` of `:routing` at idle, during a preview and during a reroute: the main process survives, guidance continues, and the next request works
  - the longest golden route (> 1 MB) arrives with the engine's SHA-256 and 0 `TransactionTooLargeException`
  - (AC 22–26)

**R10. "Offline" indicator on routes** (after the UX spec; strings OF24 «Офлайн» / OF25 «Офлайн газрын зургаас», `needs native review`).
- On each on-device preview option. During guidance in the progress area, until a gateway reroute replaces the route (Open question 1, default (a)).
- Never covers the summary, «Эхлэх», «Дуусгах», recenter or the OSM attribution. Contrast ≥ 4.5:1. TalkBack announces it at most once per route.
- Checks: Compose / Robolectric tests (AC 27, 28, 38).

**R11. `graph_builder` allow-list.**
- Compiled-in list, initially `["valhalla 3.9.0"]`. A provisioned or installed routing file outside the list is refused with a log line without coordinates.
- Checks: AC 29. NAV-022 uses the same list to decide what to offer.

**R12. Gate 1 CI job.**
- Instrumented test on an **x86_64 emulator** with the shipped AAR (x86_64 ABI present, A1 F1). It runs the QA golden route set (the same fixture as NAV-020 Gate 2) through `OnDeviceRoutingService` on a `routing.tar` from the server builder.
- Pull the outputs with adb and compare them with the server engine's responses on the same tar, using the NAV-020 AC 10 rules (reuse backend's comparator rules; the reference responses may come from a NAV-020 Gate 2 run, or from `ghcr.io/valhalla/valhalla:3.9.0` = the server engine on that tar).
- Triggered when the `valhalla-mobile` pin, the server Valhalla version or the golden set changes. A pass is the only way to add a `graph_builder` to R11.
- Checks: AC 7, 32, 33. If no emulator can run in the available CI, say so plainly in the report. The fallback is the NAV-020 Gate 2 host image (ADR-0017 A1 item 3), which needs a build host (NAV-020 task file §4.4).

**R13. Regression, privacy, strings.**
- Without a routing file: `./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required` and G1–G9 pass unchanged (AC 30).
- With a file and offline: G1, G5, G8 and G9 match the NAV-005 expectations and the voice golden set (AC 31).
- Log, file and preference scan for coordinates, route bodies and OSM IDs (AC 36). Network capture: 0 routing requests offline (AC 37). NAV-005 AC 61 string checks (AC 38).

**R14. Device runs** (with QA): AC 1, 15, 16, 34 and 35 on the named phones. A missing phone class is listed as not verified (Open question 3).

**Order:** R1 + R2 + R3 + R5 (proof, report to the PO) → R4 → R6 → R7 → R8 → R9 → R11 → R12 → R10 (after UX) → R13 → R14. Estimate 8–11 person-days (spike 5–8 + separate process 2–3).

## Dependencies
| From | Needed |
|---|---|
| qa-engineer | The golden route set fixture (shared with NAV-020 B6); the device test plan for AC 15, 16, 34, 35 |
| ux-designer | The offline indicator spec (D201) for preview and guidance |
| backend (NAV-020) | A published `routing.tar` for real-file tests (until then, R4 with a local copy); comparator rules or code for R12 |
| PO | The benchmark phones (story Open question 3) |

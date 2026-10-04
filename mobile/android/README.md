# Android app (NAV-005 first slice)

Turn-by-turn navigation for Ulaanbaatar on Android: map, search, long-press point, route preview, guidance with
Mongolian banners and voice (or the D23 chime), off-route reroute, GPS loss, arrival. Story
[NAV-005](../../docs/requirements/stories/NAV-005-active-navigation-android.md), design
[ADR-0009](../../docs/architecture/adr/0009-android-guidance-client.md), UX
[screen spec](../../docs/design/screens/NAV-005-android-navigation.md) and
[navigation-ux](../../docs/design/navigation-ux.md). iOS is NAV-015.

| | |
|---|---|
| Language / UI | Kotlin 2.3.20, Jetpack Compose (BOM 2026.06.01, Material 3 1.4.0), MVVM + unidirectional flow, Hilt 2.58 (KSP 2.3.6), Coroutines/Flow |
| Navigation | Ferrostar `core` 0.57.0 only: the app drives `NavigationSession` from one engine thread (no `FerrostarCore`, no Ferrostar UI, recorder, cache or logger) |
| Map | MapLibre Native Android 13.6.1 through an `AndroidView`; style JSON generated from the web style; fonts and sprites copied from `web/public` at build time |
| Network | OkHttp 5.3.2 to the gateway only (`POST /v1/route`, `GET /v1/search`, `pmtiles://…/tiles/basemap.pmtiles`) |
| Build | AGP 8.13.2, Gradle 8.14.3 (wrapper pinned with checksum), compileSdk/targetSdk 36, **minSdk 26**, Java 17 bytecode |
| App ID | `mn.navmn.app` (+ `.debug`), a placeholder until the PO fixes the store ID (story Open question 8) |

## 1. Android SDK (outside the repository)

The SDK is never committed. Install the command-line tools anywhere outside the repo, for example `/opt/android-sdk`:

```bash
export ANDROID_HOME=/opt/android-sdk
mkdir -p "$ANDROID_HOME/cmdline-tools"
curl -fLO https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q commandlinetools-linux-13114758_latest.zip -d "$ANDROID_HOME/cmdline-tools"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Either export `ANDROID_HOME` or write `sdk.dir=/opt/android-sdk` to `mobile/android/local.properties` (git-ignored).

## 2. Gateway URL (A7, AC 66)

`BuildConfig.GATEWAY_BASE_URL` comes from, in this order:

1. Gradle property: `./gradlew assembleDebug -Pnav.gatewayBaseUrl=https://…`
2. Environment: `NAV_GATEWAY_BASE_URL=https://…`
3. The uncommitted file `mobile/android/gateway.local.properties` with `nav.gatewayBaseUrl=https://…`
4. Debug default `http://127.0.0.1:8080`

No server hostname or IP is committed. Debug builds allow cleartext only to `127.0.0.1`, `localhost` and `10.0.2.2`
(`src/debug/res/xml/network_security_config.xml`); release builds allow no cleartext and **fail to build** unless the
URL starts with `https://` (task `checkReleaseGatewayUrl`).

- Phone on USB with the local dev stack: `adb reverse tcp:8080 tcp:8080` (then the default works).
- Emulator: `-Pnav.gatewayBaseUrl=http://10.0.2.2:8080`.
- The PO's phone test needs a gateway reachable from the phone over HTTPS: NAV-008 staging (dependency, not a build blocker).

## 3. Build, test, lint

```bash
cd mobile/android
./gradlew assembleDebug testDebugUnitTest lint        # APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- **Real Ferrostar in JVM tests (M-1).** `testDebugUnitTest` first runs `tools/build-host-ferrostar.sh`, which downloads
  `ferrostar-0.57.0.crate` from crates.io, checks its checksum, builds `libferrostar.so` for the host with cargo
  (≈ 1 min, once) into `~/.cache/navmn/ferrostar-host/0.57.0/` (outside the repo; override with
  `NAV_FERROSTAR_HOST_CACHE`) and asserts UniFFI 0.31.1. JNA loads it in the replay tests.
  `-Pnav.hostFerrostar=required` makes a missing library a failure; `-Pnav.hostFerrostar=off` skips the build (the
  Ferrostar-backed tests are then reported as skipped, never faked). Needs Rust (`cargo`).
- **Live dev gateway (opt-in):** `./gradlew testDebugUnitTest --tests '*LiveGatewayTest*' -Pnav.liveGateway=http://127.0.0.1:8080`
  (checks `/health` first, ≤ 1 route request per second).
- **Glossary (AC 61):** `node mobile/android/tools/check-glossary.mjs` (runs `web/scripts/check-glossary.mjs` on
  `res/values/strings.xml`; also run by `ResourcesTest` when node is available).
- **Native style (ADR-0009 §6):** `node web/scripts/export-native-style.mjs` regenerates
  `app/src/main/assets/style/basemap-{day,night}.json` from the web `buildStyle`; `--check` fails if they are stale.
- **Notices:** `python3 tools/gen-third-party-notices.py` regenerates `THIRD_PARTY_NOTICES.md`.
- Maven Central rate-limited this build machine (ADR-0009 F12): `settings.gradle.kts` lists Google's mirror of Maven
  Central first. It is a build-time host only; the app never contacts it.
- Robolectric downloads `android-all` from the same mirror (system property set in `app/build.gradle.kts`).

## 4. Code map (ADR-0009)

| Package (`app/src/main/java/mn/navmn/app/`) | What | ADR / AC |
|---|---|---|
| `instructions` | ADR-0008 rule table port (`ManeuverRules`), banner text, street names, voice text generator | §3, AC 26–33 |
| `format` | distance, duration, «Хүрэх цаг» (NAV-004 rules, no-break space between number and unit) | AC 6, 21–22 |
| `route` | exact request body, heading rule, `RouteClient` (12 s), status classification, OSRM plan, token rewrite | §2, §3.1, AC 5, 7, 43, 47–49 |
| `reroute` | off-route debounce, `ReroutePolicy` P1–P10 | §4, AC 41–50 |
| `gps`, `arrival` | GPS-loss and arrival state machines | §5, AC 51–57 |
| `voiceplan` | device-side prompt schedule (navigation-ux §4.2–4.4, named constants), playback queue (§4.5) | §3.3, AC 32–35 |
| `engine` | `GuidanceCore` (pure, single-threaded), Ferrostar adapter, `StepCatchUp` (NAV-005-D1), `GuidanceEngine` (engine thread), `GuidanceSession` | §1, §10 |
| `voice` | TTS usable-voice decision, chime (generated PCM), `VoiceOutput` (focus, ducking, fallback) | §3.4, AC 36–39 |
| `location`, `net`, `settings` | `LocationSource` interface + `PlatformLocationSource` (`LocationManager`, no Play services), validated network, DataStore + per-app locale | §5, §8, §9 |
| `service` | `GuidanceForegroundService` (type `location`, channel «Замчлал»), notification text | §9, AC 13–20 |
| `search`, `preview`, `permission` | NAV-003 search profile (NAV-011: planned by `search.assist`), preview states (NAV-011: k routes, selection, «Дугуй»), location access decisions | AC 3–12 |
| `map`, `ui` | `MapSurface` / `MapCamera` seam (MapLibre: `MapLibreSurface` + `NavMapController`), camera rules, Compose screens S1–S8, tokens → `TokenColours` (generated) | §6, §10, AC 1–2, 21–25, 58–64 |
| `di` | `AppModule` (gateway clients, Ferrostar), `PlatformModule` (location, map surface, voice: replaced by fakes in Robolectric tests) | §10 |
| `search.assist` (NAV-011) | ADR-0006 query assistance ported to Kotlin: `Settle` (JS whitespace set, surrogate-safe 200 cap), `QueryPlanner` rules A–D, `LatinToCyrillic`, `Merge`, `CombineOutcomes`; checked against the shared fixture `web/src/search/queryPlan.vectors.json` | ADR-0012 §1–§3, NAV-011 AC 2–5 |
| `search.reverse` (NAV-011) | `ReverseClient` (`GET /v1/reverse`, 6 decimals, `limit=1`, `radius=0.5`), `ReverseController` (one request per coordinate card, own 429 cooldown) | ADR-0012 §4, AC 8–13 |
| `route.alternatives` (NAV-011) | `PreviewRoutes` (k routes, each parsed from a single-route slice into the unchanged NAV-005 pipeline), `AlternativeHitTest` (48 dp tap box) | ADR-0012 §5, AC 14–21 |
| `typinglock` (NAV-011) | `FixSpeed`, `TypingLockRule` (pure), `PlatformLockFixSource` (own 1 Hz GPS listener, S1/S3 foreground only), `PassengerOverride` (process memory only), `TypingLockController` | ADR-0012 §7, AC 27–37 |

Pure packages have no Android or Ferrostar types; all time is injected.

## 5. Notes for QA

- **Replay harness:** `app/src/test/java/mn/navmn/app/support/Replay.kt` drives `GuidanceCore` with the real Ferrostar
  session on a virtual clock (fixes, 500 ms ticker, speaker completions, route responses after a latency) and captures
  banners, TTS input, requests and the debug log. `Gpx.parse(file)` turns a GPX track into 1 Hz fixes, so the
  `tests/gpx/nav005` set can be fed in. Recorded responses: `app/src/test/resources/routes/` (P1→P3 car mn/en, P1→P2
  walk mn/en, G2 reroute from the detection point, G8 rotary exit 2). None of this is in the APK.
- **Compose test tags / semantics:** `attribution`, `nav-banner` (+ semantics `variant` = maneuver|reroute|arrival),
  `nav-banner-distance`, `nav-banner-text`, `nav-banner-street`, `nav-then`, `nav-status` (+ `kind`),
  `nav-voice-notice`, `nav-voice`, `nav-orientation`, `nav-recenter`, `nav-progress`, `nav-eta`, `nav-remaining`,
  `nav-settings`, `nav-end`, `nav-arrival`, `search-bar`, `search-results`, `settings`, `coordinate-card`,
  `route-preview`, `preview-result`, `nav-start`, `location-rationale`, `location-message`, `map`.
  NAV-011 adds: `preview-sheet-handle`, `preview-summary`, `mode-car`, `mode-walk`, `mode-bike`, `route-options`,
  `route-option` (selectable), `typing-lock`, `typing-lock-close`, `typing-lock-passenger`, `coord-nearest`,
  `coord-nearest-retry`; custom semantics `sheetState` (`collapsed`|`expanded`, on `route-preview`) and `reverseState`
  (`pending`…`error`, on `coord-nearest`). The coordinate card keeps its NAV-005 tag `coordinate-card`.
- **NAV-011 build inputs:** no new build property. `syncSharedTestResources` also copies
  `web/src/search/queryPlan.vectors.json` (AC 5); a 3-route preview response is synthesised in the tests from the
  recorded single-route responses until QA records a live one.
- **String resource keys:** the screen spec's proposed keys, with manoeuvre keys in snake_case
  (`maneuver_turn_slight_left` for web `maneuver.turn.slightLeft`, `maneuver_on_ramp_left`, …) and the place-type labels
  as `place_type_*`. `values/` = Mongolian, `values-en/` = English.
- **Ferrostar 0.57.0 detail:** `updateUserLocation` reports the deviation of the *previous* location (one fix of lag);
  `GuidanceCore` pairs it with that fix's accuracy and time. G2 detection is about 3 s after the first fix > 50 m away.
- **Step advance after a jump (NAV-005-D1).** Ferrostar's `stepAdvanceDistanceEntryAndExit(30, 5, 25)` is unchanged.
  It only advances after a fix within 30 m of the step end, so a GPS gap over a junction left guidance on the passed
  turn. `StepCatchUp` adds a fallback: when a good fix (accuracy ≤ 25 m) is more than 50 m from the current step and
  within 50 m of a later step (never the `arrive` step), the adapter calls Ferrostar's public
  `NavigationSession.advanceToNextStep` until that step is current. This is Ferrostar's own `OffStepOnRoute` rule, applied
  to the current fix instead of the previous one. Normal turns never reach it. G3b: the right turn is shown and announced
  1 s after «GPS дохио сэргэлээ», and the passed left turn is not announced.
- **Single outliers (NAV-005-D9, D10).** `StepCatchUp.Gate` applies a catch-up only after two consecutive good fixes
  agree on the same later step, within 3 s of each other, also right after a GPS gap: the first fix after a tunnel may
  be the outlier (G6c). At 1 Hz this costs 1 s, inside AC 53's 2 s. Poor fixes neither confirm nor reset. A good fix
  that is more than 50 m from the current step and not caught up is flagged (`NavSnapshot.fixOnCurrentStep = false`).
  Ferrostar snaps it to the nearest point of the step, often the manoeuvre itself, so `GuidanceCore` keeps the last
  trusted snapshot (banner distance, progress, puck) and does not evaluate the voice schedule for it. Off-route
  detection still sees every fix. G6b: no early "now" prompt; the 150 m and "now" prompts are spoken.
- **Walking dead band (NAV-005-D8, Amendment 3 §1).** A second catch-up branch covers fixes that resume 30–50 m past
  the END of the current step (neither Ferrostar's 30 m entry nor the 50 m rule catches them): the fix projects onto
  the step's last coordinate, is more than `STEP_ENTRY_M` (30 m) from it and within 25 m of a later non-arrive step.
  Same two-fix gate; a pending match is untrusted like the D9 case. `Geo.nearestIsLast` uses a 0.5 m tolerance so a
  fix exactly perpendicular to the step end (a 90° turn) counts as "past the end". G5b: the next turn is shown with
  its true distance 1 s after the restore and the passed turn is not announced.
- **Arrival gating (Amendment 1, Amendment 3 §5).** `ArrivalDetector` gets the step index: the 30 m straight-line rule
  (c) fires only while the upcoming manoeuvre is `arrive` (current step ≥ last − 1), and the ≤ 30 m remaining rule (b)
  only with a trusted fix. `ArrivalGatingReplayTest`: G1 with one fix 20 m from the end after 300 m, and a loop route
  whose end lies 20 m beside its first street, both arrive once, at the end.
- **Voice rule 2 from the playback start (NAV-005-D2).** `PlaybackQueue` reports starts and drops to a
  `PlaybackListener`; `VoiceScheduler` measures the 8 s same-manoeuvre gap from the start (the trigger stands in until
  the start is known; a dropped prompt does not count).
- **English plurals (NAV-005-D4).** The four voice distance templates are `<plurals>` (`PluralKey`,
  `Strings.plural`): `one` only when the formatted number is exactly "1" (`Plurals.isOne`), never from an int cast.
  Mongolian items are identical. `tools/check-glossary.mjs` checks every `<plurals>` item too.
- **Browse layout on short screens (NAV-005-D5).** Without a preview, portrait keeps the controls and the coordinate
  card at the bottom and lets the search results / messages above shrink and scroll; wide windows (≥ 600 dp, or
  landscape ≥ 480 dp) give the controls their own lane on the right below the search row. The coordinate card
  scrolls instead of squeezing «Маршрут гаргах».
- **Preview ETA (NAV-005-D6, NAV-004 AC 25).** `PreviewEtaClock`: the response time for the first 60 s, then the
  current clock, refreshed every 60 s counted from the response (also right after a recomposition).
- **Map-screen location (Amendment 1 §9).** `AppViewModel.mapLocation` is collected by `MainActivity` only inside
  `repeatOnLifecycle(STARTED)` and is paused while a guidance engine exists (arrival panel included). The one-shot
  preview origin (`freshGoodFix`) is unchanged.
- **Activity tests (AC 71).** `PlatformModule` binds `PlatformLocationSource`, `MapLibreSurface` and `VoiceOutput`.
  Robolectric tests replace it with `TestPlatformModule`: `FakeLocation` counts listeners, `RecordingMapSurface` has no
  native code and offers `longPress(p)`, and `RecordingVoice` records prompts. `TestAppModule.FakeGateway` serves the
  recorded P1→P3 route to the preview. `MainActivityTest` covers AC 8, 10–13 and 64 at Activity level.
- **Not verifiable here (AC 73):** MapLibre rendering of `pmtiles://` and `asset://` glyphs on a device, real TTS and
  the `mn` voice, real GPS and tunnels, foreground service with the screen off, Doze/OEM battery savers, the
  notification-permission dialog, audio ducking, chime audibility, battery use.

## 6. NAV-012: background guidance, restore, battery savers (ADR-0013)

### 6.1 Code map
| Package | What |
|---|---|
| `background/restore` | Restore record (`RestoreRecord`, `RestoreStore` in `noBackupFilesDir/restore/`), pure `RestoreRules` (window 30 min, loop limit 2 in 10 min, schema check) and `RestartPlan` (null-intent restart per API level), `RestoreStartStep`, `RestoreManager` (write at «Эхлэх», on new routes, 30 s heartbeat, delete on normal end), `RestoreLauncher` (app open), `InterruptedNotification` + `InterruptedEndReceiver` (AC 22) |
| `background/battery` | `BatteryHintRules` (pure), `BatteryHint` (power state, 30-day dismissal, one showing after a restore), `BatterySettings` (system intent with fallback), H1/H2 composables |
| `audio/calls` | `CallModes`, pure `CallGate` (skip during calls, 1 s end debounce, one catch-up, 5 s guard), `AudioModeCallSignals` (mode listener on API 31+, 250 ms poll below; transient focus loss) |
| `audio/output` | `AudioOutputRules` / `AudioOutputMonitor`: Bluetooth media output → 300 ms silent lead-in. The app never selects a device |
| `service/notification` | `RichNotification` (content per state), `NotificationPostPolicy` (1 s / 2 s / ≤ 1 per s, Android 14+ dismissal), `GuidanceNotificationBuilder` (standard template, large icon, public version) |
| `lockscreen` | `LockScreenGate`: `setShowWhenLocked` while guiding or on the arrival panel, unlock prompt before leaving the guidance screen, volume keys → prompt stream |
| `theme/sun` | `SunCalc` (NOAA algorithm, UTC), `AutoThemeHold` (≤ 1 change / 10 min), `SunTheme` (position: session fix → last known ≤ 24 h → P1) |

Tuning values (change only after a real-device check, AC 52): Bluetooth lead-in **300 ms** (`AudioOutputRules.BLUETOOTH_LEAD_IN_MS`, ≤ 500 ms by AC 33; not yet tuned on a car or headset); restore start step (ADR-0013 Amendment 2): steps within **50 m**, bearing within **60°** when the fix has a usable bearing, then the nearest step wins with a tie margin of max(**10 m**, fix accuracy) and the earliest inside the margin; without a usable bearing, one re-check on the first fix with a usable bearing within **30 s** of the start-step decision (`RestoreStartStep`, `GuidanceCore.BEARING_RECHECK_MS`; not yet tuned on a device).

### 6.2 Keeping navigation alive under OEM battery savers (AC 29)
Android itself keeps the location foreground service running with the screen off. Many manufacturers add their own
battery managers that stop apps anyway, especially after the app is removed from Recents. If guidance stops, the app
restores it when it is opened again within 30 minutes (or offers «Замчлал тасарлаа» on Android 11+), but it is better
to allow the app to run in the background. The in-app hint only opens the standard Android battery-optimisation screen;
the steps below are for testers and support. Menu names differ between OS versions and regions.

| Family | Settings path(s) that let the app run in the background | Written for | Verified on device |
|---|---|---|---|
| Samsung (One UI) | Settings → Apps → *app* → Battery → **Unrestricted**. Settings → Battery → Background usage limits → remove the app from "Sleeping apps" / "Deep sleeping apps"; optionally add it to "Never sleeping apps". Recents: long-press the app icon → "Keep open" (where offered) | One UI 6–7 (Android 14–15) | not verified |
| Xiaomi / Redmi / POCO (MIUI, HyperOS) | Settings → Apps → Manage apps → *app* → **Autostart** on; → Battery saver → **No restrictions**. Recents: pull the app card down (or long-press) → **Lock** | MIUI 14, HyperOS 1–2 (Android 13–15) | not verified |
| Huawei (EMUI, HarmonyOS) | Settings → Battery → App launch → *app* → turn off "Manage automatically", then enable **Auto-launch**, **Secondary launch** and **Run in background**. Recents: swipe the card down → **Lock** | EMUI 12, HarmonyOS 3–4 | not verified |
| Honor (MagicOS) | Settings → Battery → App launch → *app* → turn off "Manage automatically", enable **Auto-launch**, **Secondary launch**, **Run in background**. Recents: swipe the card down → **Lock** | MagicOS 7–8 (Android 13–14) | not verified |
| OPPO / realme (ColorOS, realme UI) | Settings → Apps → App management → *app* → Battery usage → allow **background activity** (and "Allow auto launch"). Settings → Battery → More settings → turn off "Optimise battery use" for the app. Recents: tap the card menu → **Lock** | ColorOS 13–14, realme UI 4–5 (Android 13–14) | not verified |
| vivo (Funtouch OS, OriginOS) | Settings → Battery → Background power consumption management → *app* → **Allow** (high background power consumption). Settings → Apps → Permissions → Autostart → enable for the app. Recents: swipe the card down → **Lock** | Funtouch OS 13–14, OriginOS 3–4 | not verified |
| OnePlus (OxygenOS) | Settings → Apps → *app* → Battery usage → **Allow background activity** (OxygenOS 13+: "Unrestricted"); Settings → Battery → Battery optimisation → *app* → **Don't optimise**. Recents: card menu → **Lock** | OxygenOS 13–14 (Android 13–14) | not verified |
| Stock Android / Pixel | Settings → Apps → *app* → App battery usage → **Unrestricted** (the in-app «Тохиргоо нээх» opens the system battery-optimisation list, where the app can be set to "Not optimised") | Android 14–15 | not verified |

Tester checklist (record the model, OS version and date in the table above once a step is verified):
1. Start guidance, turn the screen off, drive or replay at least **10 minutes**, including a period without GPS (tunnel
   or covered phone): prompts keep coming, the notification follows, no gap longer than 2 s.
2. Swipe the app away from Recents during guidance: guidance and prompts continue; reopening shows the same route, no
   depart prompt, no route request.
3. Force-stop the app during guidance (Settings → Apps → *app* → Force stop) or `adb shell am kill mn.navmn.app.debug`
   in the background, then open it within 30 minutes: guidance resumes to the same destination with «Замчлал
   сэргэлээ» and exactly one prompt. On Android 9/10 after `am kill` the system may restart the service by itself;
   on Android 11+ «Замчлал тасарлаа» appears instead.

### 6.3 Not verified in this environment (AC 52)
Real lock-screen behaviour and unlock prompts, OEM killers, the system's sticky restart, Bluetooth output and
first-word clipping, cellular and VoIP calls, ducking, a real sunset theme switch, notification rendering on OEM skins
and battery use: all need a real Android phone. JVM/Robolectric tests cover the pure rules and the Android glue.

## 7. Demo build (NAV-019)

A separate build of the real app for the PO's own phone. It needs **no server**: it replays the three recorded
Ulaanbaatar routes of the web demo (NAV-017) through the real guidance engine, with a simulated position along the
recorded track. Everything else is the production code path: banner, Android TTS voice or the D23 chime with notice A1,
the foreground-service notification «Замчлал», the lock screen, Bluetooth audio and phone-call handling. Story
[NAV-019](../../docs/requirements/stories/NAV-019-android-demo-mode.md), design
[ADR-0016](../../docs/architecture/adr/0016-android-demo-mode.md), UX
[screen spec](../../docs/design/screens/android-demo-picker.md).

> **Distribution (D17).** The demo APK goes **only to the PO, by direct file transfer** (USB cable, or `adb`). It is
> never put on the public website, in a store, or behind any download link.

The orchestrator defaults R1–R7 behind this build are **proposed defaults, PO to confirm** (story Open questions 1–8),
not PO decisions. Two of them affect what you see:

- **Location permission (Open question 6, option (b) implemented).** At the first «Эхлэх» the app asks for location
  exactly like the real app, and location services must be on. The reason: on Android 14+ a foreground service of type
  `location` needs that permission. The demo **never reads a real fix**: the position on the map is always the
  simulated one, and the phone's GPS is never used.
- **Speed 1× with a pause (Open question 4, option (a)).** «Түр зогсоох» pauses the replay and «Үргэлжлүүлэх» resumes it.
  2× and 4× exist in the replay code and its tests, but the UI offers no speed choice, because NAV-017 has none and a
  speed label would need a new glossary term.

### 7.1 Build

The demo build is the Gradle build type `demo`. It installs next to the debug app with the application ID
`mn.navmn.app.demo`. It is signed with your local debug key (`~/.android/debug.keystore`, which is never committed), is
not debuggable and is not minified. Its launcher label is «Туршилтын горим» ("Demo mode"), and its icon has an amber
background. It needs **exactly one** basemap source:

| Property (or environment variable, or `gateway.local.properties` key) | Meaning |
|---|---|
| `nav.demoTilesFile` (`NAV_DEMO_TILES_FILE`) | Absolute path to a local PMTiles v3 archive. It is copied into the APK, so the app works offline, also in airplane mode. **Recommended.** |
| `nav.demoTilesUrl` (`NAV_DEMO_TILES_URL`) | An `https://` PMTiles URL, for example on the PO's static site. The phone then needs a network for the map. |

```bash
cd mobile/android
./gradlew :app:assembleDemo -Pnav.demoTilesFile=<path-to>.pmtiles
# or: ./gradlew :app:assembleDemo -Pnav.demoTilesUrl=https://<host>/<path>.pmtiles
# APK: app/build/outputs/apk/demo/app-demo.apk
```

Never commit the archive, the APK or either property value. The repository holds placeholders only (AC 41).
`gateway.local.properties` is git-ignored, so you can put `nav.demoTilesFile=<path-to>.pmtiles` there instead of the
command line. If neither property is set, any demo task stops in `preDemoBuild` with one message that names both
properties and points here (`checkDemoTiles`). `assembleDebug`, `assembleRelease` and `testDebugUnitTest` never need
them.

**Archive.** Use a Ulaanbaatar extract that covers R1–R3 with some margin. Make it with the `pmtiles` CLI (go-pmtiles,
BSD-3-Clause) from the Mongolia archive, for example
`pmtiles extract <mongolia>.pmtiles <ub-demo>.pmtiles --bbox=106.80,47.83,107.05,47.98`. Measured on 2026-10-04 with the
local test archive used for the quick check (bbox 106.55,47.72 to 107.25,48.12, zoom 0–14): **6,293,133 bytes
(6.0 MiB)**. The archive sits twice on the phone: once inside the APK, once in app storage (see 7.3).

**What the build packages (AC 4).** The Gradle task `syncDemoAssets` runs for the demo build type only. It copies the
following byte for byte from their repo paths:

- `web/src/demo/routes.manifest.json` to `demo/routes.manifest.json`;
- the route JSON and the GPX track of every `picker: true` entry to `demo/files/<repo path>` (R1 = G1, R2 = G5,
  R3 = G8; G4 and the off-route G2 are not offered);
- the archive to `demo/basemap.pmtiles`.

It validates the manifest, the routes and the tracks. Nothing is copied under `mobile/`.

**Code (AC 5).**

| Location | Contents |
|---|---|
| `app/src/replay/java` (package `mn.navmn.app.demo.replay`) | Pure Kotlin: track parser, replay clock, the simulated `LocationSource`, the voice pause gate, the network block, tile copy, route catalogue. Compiled into the demo build and into the debug unit tests. |
| `app/src/demo` (package `mn.navmn.app.demo`) | Android parts: the `ReplayVariant` binding, wake lock, picker and pause UI, strings `demo_mode` / `demo_pause`, the launcher colour, the manifest overlay. |
| `src/main` | Only the generic optional binding `mn.navmn.app.variant.ReplayVariant`. When it is absent (debug, release, every existing test), every call site keeps today's path. |

Debug and release APKs contain no `mn.navmn.app.demo` class, no `demo/` asset, no GPX file and no `.pmtiles` file.

### 7.2 Install on the PO's phone

1. Copy `app-demo.apk` to the phone by direct file transfer (USB cable to the phone's Download folder). Alternatively,
   with USB debugging on, run `adb install -r app/build/outputs/apk/demo/app-demo.apk`.
2. On the phone, open the APK in the Files app. The first time, Android asks you to allow installs from this app. Go to
   **Settings › Apps › Special app access › Install unknown apps**, select the Files app (or the app you opened the APK
   with) and turn on **Allow from this source**. The names vary by phone maker. Then go back and tap **Install**.
3. The demo build installs **next to** the debug build: two icons, "Газрын зураг" and «Туршилтын горим» (amber icon).
   Uninstalling one never touches the other.
4. Afterwards you may switch "Allow from this source" off again.

### 7.3 What the demo does differently (and what that means for the test)

- **Start screen.** The route picker «Туршилтын горим» lists R1–R3. Tap an entry to open the normal route preview with
  «Маршрутын заавар». «Эхлэх» is enabled although the start is not your position. Settings «Тохиргоо» is the gear on
  the picker.
- **No network at all.** An in-process block sits first on the app's only HTTP client, and MapLibre gets a client that
  refuses every request, so 0 requests leave the phone. Search in the point editor, a long-press address, a new route
  (another mode, swap, changed points) and any reroute show their normal "unavailable" or «Интернэт холболт алга»
  states. The base URL is the fixed loopback discard address `https://127.0.0.1:9`, which is never contacted.
- **Tiles.** MapLibre 13.6.1 cannot read byte ranges from APK assets (upstream issue #4360; the fix is not in a
  release yet). On first launch, the app therefore copies the archive once into no-backup app storage and opens it as
  **`pmtiles://file://<absolute path>`**. While it copies, «Ачаалж байна…» shows. A broken archive shows
  «Газрын зургийг ачаалж чадсангүй» with «Дахин оролдох», and the list keeps working.
- **Background.** The replay keeps running with the screen off, after Home and after a swipe-away. To keep its timing it
  holds a partial wake lock while it runs; it releases it on pause, arrival and end. The real app relies on GPS
  wake-ups instead, so **screen-off battery drain in the demo does not predict production**.
- **Process death.** No restore record is written. If the OEM kills the app during a replay, the next launch shows the
  picker, and nothing about the replay is kept (Open question 8, default).
- **End of track.** If a track ends without arrival, the app ends guidance 2 s later as if «Дуусгах» was tapped.

### 7.4 PO phone checklist (AC 43)

Fill in one row per item: pass, fail, or a note. Skipped items stay "not verified".

| # | Check | Result (pass / fail / note) |
|---|---|---|
| a | Phone model and Android version. The TTS engine set in **Settings › Text-to-speech output** (or the phone's equivalent), and whether a Mongolian voice is listed (input for NAV-007 AC 9). Also the screen size in dp (Developer options › Smallest width) and the font size | |
| b | R1 in the Mongolian UI: banner texts readable; either Mongolian speech, or notice A1 «Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.» and an audible chime per prompt | |
| c | R1 in the English UI (Settings › «Хэл» › English): English speech heard | |
| d | R2 (walk) with the phone locked: lock-screen view with the badge, notification content and actions («Дуусгах», voice toggle), «Туршилтын горим» in the notification header | |
| e | R3 with the screen off for the whole replay: prompts heard; the replay ends within 787 s ± 15 s of «Эхлэх» | |
| f | Swipe the app away from Recents during a replay: guidance and the notification continue | |
| g | Bluetooth car audio or headset: the first word of 5 prompts in a row is audible | |
| h | A phone call during a replay: no prompt during the call, one catch-up prompt after it; the replay keeps running | |
| i | Battery saver on, and the battery hint on the preview. Does the replay survive? If the app was killed: the next launch shows the picker | |
| j | Airplane mode, R1 from «Эхлэх» to arrival: map, banner, voice or chime, notification and arrival all work | |
| k | «Түр зогсоох» and «Үргэлжлүүлэх» (also over the lock screen): no prompt and no «GPS дохио тасарлаа» while paused, no repeated prompt after resuming; «Дуусгах»; arrival «Хаах» returns to the picker | |
| l | The debug build still opens and works after installing the demo build. Both launcher icons look different (colour and label) | |
| m | First launch: how long «Ачаалж байна…» showed while the map was copied (target ≤ 30 s) | |
| n | TalkBack on the picker: an entry is read as start, destination, mode, distance and duration | |

### 7.5 Not verified in this environment

There is no emulator or device here. Only the JVM tests, lint and the APK inspection ran. These are verified only by
the PO's phone test above:

- MapLibre drawing `pmtiles://file://` on a device, and the copy time;
- real TTS and whether a Mongolian voice exists;
- the notification and lock-screen behaviour, Bluetooth and calls;
- the wake lock under Doze and OEM battery savers;
- the Android 14+ foreground-service prerequisites at runtime;
- TalkBack, and the layout on the PO's screen size.

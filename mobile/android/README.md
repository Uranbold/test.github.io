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

Tuning values (change only after a real-device check, AC 52): Bluetooth lead-in **300 ms** (`AudioOutputRules.BLUETOOTH_LEAD_IN_MS`, ≤ 500 ms by AC 33; not yet tuned on a car or headset); restore start step: earliest step within **50 m**, bearing within **60°** when the fix has a usable bearing (`RestoreStartStep`; not yet tuned on a device).

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

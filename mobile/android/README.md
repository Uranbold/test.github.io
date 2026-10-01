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
| `search`, `preview`, `permission` | NAV-003 search profile (query as typed), preview states, location access decisions | AC 3–12 |
| `map`, `ui` | `MapSurface` / `MapCamera` seam (MapLibre: `MapLibreSurface` + `NavMapController`), camera rules, Compose screens S1–S8, tokens → `TokenColours` (generated) | §6, §10, AC 1–2, 21–25, 58–64 |
| `di` | `AppModule` (gateway clients, Ferrostar), `PlatformModule` (location, map surface, voice: replaced by fakes in Robolectric tests) | §10 |

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
  detection and arrival still see every fix. G6b: no early "now" prompt; the 150 m and "now" prompts are spoken.
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

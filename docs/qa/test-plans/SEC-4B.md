# Test plan: SEC-4B client security hardening (web CSP and headers, Android dependency verification)

- **Story:** `docs/requirements/stories/SEC-4B-client-security-hardening.md` (34 AC, P1 / intangible)
- **Design:** ADR-0018 (`docs/architecture/adr/0018-client-csp-and-android-dependency-verification.md`), task breakdown `docs/architecture/tasks/SEC-4B-client-security-hardening.md`
- **QA scope:** light, by PO preference (story › Context › Process; D155 / D210). QA owns AC 17–18 (browser CSP check on B1, B2, B3) and AC 25 (the two Gradle commands with verification on). The mobile engineer verifies every other AC and reports it in the handoff.
- **Cheap independent re-checks:** QA also re-checks some engineer-verified ACs, because they can be checked in seconds from the same builds and because AC 17's zero means nothing if the policy has the wrong shape. These re-checks are AC 1–6, 9, 11–13, 15, 22–24, 29, 31 (set equality only) and 34.
- **Not covered by QA:** AC 7, 8, 10, 14, 16, 19–21, 26–28, 30, 32 and 33 are verified by the mobile engineer and reviewed by the security-engineer. AC 21 is run by the PO on the real host.

## Builds under test

| Build | Command (in the spec) | Served at | Gateway |
|---|---|---|---|
| B1 static demo | `vite build --mode static-demo` into `$TMPDIR/navmn-sec4b-b1`, `VITE_GATEWAY_BASE_URL=same-origin` | `vite preview` :5191 | page origin; `/tiles/basemap.pmtiles` is a symlink to `backend/data/tiles/basemap.pmtiles` (Range via vite preview) |
| B2 demo mode | `vite build --mode demo-mode` into `$TMPDIR/navmn-sec4b-b2`, `same-origin` | `vite preview` :5192 | page origin, same tiles symlink |
| B3 normal | `vite build` into `$TMPDIR/navmn-sec4b-b3`, `VITE_GATEWAY_BASE_URL=http://localhost:8080` (the documented default) | `vite preview` :5193 | `http://localhost:8080`, every answer mocked by Playwright interception (tiles with Range, `/v1/search`, `/v1/reverse`, `/v1/route` = recorded `p1-p3-car-mn.json`) |

- **Real build.** Every build uses `web/vite.config.ts` with all its plugins, through `tests/e2e/sec4b/vite.sec4b.config.mjs`, which only sets a separate `cacheDir`.
- **Fresh outputs.** `reuseExistingServer: false`, so each run builds the current sources.
- **Meta policy only.** `vite preview` sends no security header, so only the meta policy applies. Playwright `bypassCSP` is `false`. Request interception runs after the renderer's CSP check, so a blocked request is still reported as a violation.

## Oracles

- **Policy shape.** Each built `index.html` is parsed by **Chromium's `DOMParser`**. Hashes are recomputed with **WebCrypto** (sha256 over the UTF-8 `textContent`) in `tests/e2e/sec4b/helpers.mjs`. `web/buildtools/csp.ts` (jsdom) is never imported, so the check is independent of the implementation.
- **Violations.** A `securitypolicyviolation` listener is installed by a Playwright init script, which is injected through CDP and is not subject to the page policy. It records from the first byte. A console listener matches `/Content[ -]Security[ -]Policy/i`, and so does `pageerror`.
- **Negative control (AC 18).** `page.addScriptTag({ content: "window.__cspProbe = 1" })` must leave `__cspProbe` undefined and record exactly one `script-src` / `script-src-elem` violation.
- **B2 audio.** `HTMLMediaElement.play`, `AudioScheduledSourceNode.start` and `speechSynthesis.speak` are counted by wrappers that call the originals. This proves that the `data:audio/wav` chime element (media-src `data:`, ADR-0018 F6) was primed or played.
- **Strings.** Expected strings are quoted from the stories: «Хайлт түр ажиллахгүй байна», «Маршрутын үйлчилгээ түр ажиллахгүй байна», «Интернэт холболт алга», «Маршрут гаргах», «Эхлэх», «Дуусгах», «Машин», "© OpenStreetMap contributors".

## Test cases

| ID | AC | Level | Test | What it checks |
|---|---|---|---|---|
| TC-4B-01 | 1, 2, 3, 4, 5, 6, 9, 11, 15 | Build output (Chromium DOMParser + WebCrypto) | `tests/e2e/sec4b/build-output.test.mjs` "B1/B2/B3 SEC-4B AC1–AC6, AC9, AC11, AC15" | See the checklist below the table. |
| TC-4B-02 | 12, 13 | Build output (file list) | same file, "B1/B2/B3 SEC-4B AC12, AC13" | 0 paths with `fixtures/` or `label-rule`. No `.htaccess`, `.htpasswd`, `web.config`, `_headers`, `_redirects`, `nginx.conf` or `.user.ini`. `index.html` has no `label-rule` reference. B2 keeps `<meta name="robots" content="noindex, nofollow">` |
| TC-4B-10 | 17 (B1), 18, 34 | Browser (Chromium 1366×768, geolocation granted at a UB point) | `tests/e2e/sec4b/csp-browser.test.mjs` "B1 SEC-4B AC17, AC18, AC34" | See the B1 scenario below. |
| TC-4B-11 | 17 (B2), 18, 34 | Browser (Chromium 390×844 touch, NAV-017 reference), real time | same file, "B2 SEC-4B AC17, AC18, AC34" | See the B2 scenario below. |
| TC-4B-12 | 17 (B3), 18, 34 | Browser (Chromium 1366×768), gateway mocked | same file, "B3 SEC-4B AC17, AC18, AC34" | See the B3 scenario below. |
| TC-4B-20 | 25 | Gradle (verification on, `--refresh-dependencies`) | `./gradlew --refresh-dependencies --rerun-tasks :app:assembleRelease -Pnav.gatewayBaseUrl=https://127.0.0.1:9` | BUILD SUCCESSFUL with `gradle/verification-metadata.xml` in force. `checkReleaseGatewayUrl`, `checkReleaseLicenceGate`, `checkThirdPartyNotices` and `checkApkApiLevelTypesRelease` actually execute (`--rerun-tasks`). The gateway property is the release build's documented never-contacted placeholder (README §11.2). |
| TC-4B-21 | 25 | Gradle | `./gradlew --refresh-dependencies :app:testDebugUnitTest --rerun -Pnav.hostFerrostar=required` | BUILD SUCCESSFUL. The JUnit XML totals equal the pre-story baseline: 741 tests, 0 failures, 0 errors, 6 skipped (baseline measured by the mobile engineer before the change; the only changed test file adds two allow-list entries and no `@Test`, 4 = 4 at HEAD). |
| TC-4B-22 | 22, 23, 24 | Static | review of `mobile/android/gradle/verification-metadata.xml`, grep of `gradle.properties`, `*.kts` and the README | See the AC 22–24 checklist below the table. |
| TC-4B-23 | 29 | Static + network | `sha256sum gradle/wrapper/gradle-wrapper.jar` vs `curl -sSL https://services.gradle.org/distributions/gradle-8.14.3-wrapper.jar.sha256`; `distributionSha256Sum` vs `…-bin.zip.sha256` | Both are equal. `validateDistributionUrl=true` is unchanged. |
| TC-4B-24 | 31 (set equality) | APK | `python3 mobile/android/tools/check_native_provenance.py --apk app/build/outputs/apk/release/app-release-unsigned.apk` plus a manual `unzip -l` | The `.so` names in the release APK equal the README §11.4 table. |
| TC-4B-30 | NAV-005 AC 66 regression | Static | `python3 tests/android/nav005/static_checks.py` | TC-S13 and TC-S14 pass again. Updated for SEC-4B: `schema.gradle.org` (a Gradle XML namespace) and `www.boost.org` (a licence URL in the notices) are added as documentation hosts. Verbatim licence texts under `mobile/android/licenses/` are skipped, as the app's own `NotificationAndPrivacyTest` does. `version="…"` / `name="…"` values of `verification-metadata.xml` are ignored by the IP scan only, because the Maven version `4.1.1.4` looks like an IPv4 address. |
| TC-4B-31 | NAV-004 AC 53 regression | Browser | `tests/e2e/nav004/playwright.config.mjs` static-build server (port 5182) | Builds `--mode static-demo` with `VITE_GATEWAY_BASE_URL=same-origin`, with tiles same-origin through a symlink. An absolute gateway now fails the B1 build by design (ADR-0018 §2).  **Review item 2 (2026-10-08): QA accepts the symlink in place of a `vite preview` proxy.** It matches the public layout (`/tiles/` in the web root, D44). The AC 53 oracle counts `/v1/route` and `/v1/search\|reverse` requests to **any** host, page origin included, so it is as strong as before. The fail-closed B1 rule stays. |
| TC-4B-32 | 16 (NAV-017 tooling), review item 1 | Browser, Chromium 390×844 touch and WebKit iPhone 13 | `tests/e2e/nav017/csp.test.mjs` "SEC-4B AC16 (review item 1): helpers.hideCanvas …" | Against the NAV-017 demo-mode site, served by Caddy, with its meta CSP, `bypassCSP` false: the policy has hashed `style-src` only; every `.maplibregl-canvas` is `visible` before and `hidden` after `helpers.hideCanvas`; a canvas added later and the canvases after a resize are hidden too; 0 `securitypolicyviolation` events, 0 CSP console messages. **Written failing first:** with the old body (`page.addStyleTag`, an inline `<style>`) it threw "Refused to apply inline style … style-src". The three call sites (`helpers.fullReplayLive`, `adr.test.mjs`, `ui.test.mjs`) now share one helper, which uses a constructable stylesheet (`document.adoptedStyleSheets`). |
| TC-4B-33 | 16 (re-run of NAV-017 and NAV-004 after the fix) | Browser | `tests/e2e/nav017/run.sh` (Chromium and WebKit), `tests/e2e/nav004/run.sh` | The suites pass with the same results as before SEC-4B. AC 16 belongs to the mobile engineer; QA runs it here because the NAV-017 fix is in QA-owned files. |
| TC-4B-34 | 16 (NAV-017 AC 2 tooling, mobile-engineer request in the AC 16 handoff) | Browser, Chromium 390×844 touch | `NAV017_ENGINES=chromium NAV017_WORKERS=1 ./nav017/run.sh picker.test.mjs -g "AC2: the same build"` | `picker.test.mjs` AC2 no longer counts a `/tiles/basemap.pmtiles` request that ends in `net::ERR_ABORTED`. MapLibre cancels its own tile Range request when the camera moves to the picked route; that is not a load failure. Every other failed request still counts (now reported with its error text), and every finished tile request must still be 206. No CSP violation was in the trace of the 3-worker failure. |
| TC-4B-35 | 16 (NAV-002 AC 32, pre-existing) | Static (repository scan) | `./nav002/run.sh static.test.mjs -g "AC32 independent"` | **Kept unchanged, still failing; out of SEC-4B scope.** Fails on the SEC-4B tree and on the pre-story commit `d653176` (mobile engineer) with `web/src/demo/diagnostics.ts hard-codes «Close»` (`DIAG_TEXT.close` equals the `en` resource value "Close"). It has failed since `1dc0305` (NAV-017 triage F1). The mobile engineer asked QA to exclude `diagnostics.ts` / `DIAG_TEXT`. QA does **not** do that yet: the "internal English-only screen" status is only in a code comment and `web/README.md`. Triage F1 point 4 left that decision to the BA, and no story, decision row or glossary entry records it. See finding F-4B-02. |

**TC-4B-01 checklist.** For each build, the test checks:
- exactly one `index.html` in the output;
- the served bytes equal the bytes on disk;
- 0 `<!--`;
- exactly one CSP meta, directly after `<meta charset="utf-8">`, with no `style`, `link` or `script` element before it;
- the charset meta within the first 1024 bytes;
- no directive repeated;
- `script-src`: contains `'self'`, has none of the AC 2 forbidden sources and no host, and its set of hashes equals the set of inline-script hashes;
- `'wasm-unsafe-eval'` only in B2, and B2 has ≥ 1 `.wasm` file; B1 and B3 have 0 `.wasm` files and 0 occurrences of `'wasm-unsafe-eval'`;
- `style-src`: contains `'self'`, has no `'unsafe-inline'`, and its set of hashes equals the set of inline-style hashes; `design-tokens` is among them;
- `default-src 'self'`, `object-src 'none'`, `base-uri 'none'` or `'self'`, `form-action 'none'`;
- `connect-src`: `'self'` in B1 and B2; `'self' http://localhost:8080` in B3;
- `img-src` and `worker-src`: only the allowed sources;
- no `*` in any directive, and no host anywhere except B3's `connect-src`;
- `media-src 'self' data:` in B2 only (the architect's AC 5 amendment request);
- no `frame-ancestors`, `report-uri`, `report-to` or `sandbox`;
- 0 `on…=` attributes, 0 `javascript:` URLs, 0 `style=` attributes;
- exactly one `<meta name="referrer" content="same-origin">`.

**TC-4B-10 (B1 scenario).** Steps:
1. Load until the loading pill is `idle` and more than one tile Range request has answered 206 (network idle).
2. Switch the language mn → en → mn (`html[lang]`).
3. Switch day → night → day (`html[data-theme]`; ≥ 2 sprite sheets loaded).
4. Zoom in (the scale changes), then zoom out.
5. «Миний байршил»: the location marker is visible.
6. Search «Сүхбаатар» and then "Sukhbaatar": «Хайлт түр ажиллахгүй байна» is shown.
7. Right-click the map: the coordinate card opens with «Маршрут гаргах».
8. «Маршрут гаргах»: the route panel shows «Маршрутын үйлчилгээ түр ажиллахгүй байна».
9. `context.setOffline(true)`: the banner shows «Интернэт холболт алга».

Pass: 0 violations, 0 CSP console messages, attribution visible and not covered, negative control as in AC 18.

**TC-4B-11 (B2 scenario).** Steps:
1. The picker shows R1–R3, with the attribution visible.
2. Pick R1. «Эхлэх» becomes enabled (`aria-disabled="false"`), which means the `.wasm` was fetched (200) and compiled.
3. Tap «Эхлэх». The replay runs for ≥ 30 s, until ≥ 2 distinct banner texts (≥ 1 change) and ≥ 1 voice prompt or chime are seen. The `data:audio` chime element is primed or played.
4. Toggle voice mute twice.
5. Open the voice diagnostics, run "Test chime" (a result is shown) and close.
6. The attribution is visible during the replay.
7. Tap «Дуусгах»: the nav view closes.

Pass criteria as in B1. For the diagnostics opener, see defect D-4B-01: the five `pointerdown` events are driven on the badge element itself. Since run 2 they are sent from inside the page (one `page.evaluate`, 100 ms apart on page timers) instead of five Playwright `dispatchEvent` round trips. Under a concurrent B2 replay those round trips made the gaps 906/647/525/838 ms, above the opener's `TAP_GAP_MS` 600, and the panel stayed closed (run 2, test-only flaw). The handler and the events are unchanged.

**TC-4B-12 (B3 scenario).** Steps:
1. Load until the loading pill is `idle` with mocked tile Range answers.
2. Search «Сүхбаатар», then "Sukhbaatar": the result rows contain icon SVGs (the `innerHTML` path).
3. Open the place card from the first result.
4. «Маршрут гаргах» (origin typed as coordinates if empty), then «Машин»: ≥ 1 POST `/v1/route` and ≥ 2 turn rows.

Pass criteria as in B1.

**TC-4B-22 (AC 22–24).** Checks:
- `verify-metadata` is `true` and `verify-signatures` is `false`;
- sha256 only (1,089 checksums in 641 components);
- exactly one `<trust>` rule (`.*-(sources|javadoc)[.]jar`). Its reason is in the file's header comment, because Gradle drops comments inside the root element, and in README §11.1;
- no `org.gradle.dependency.verification=lenient|off` anywhere, and no `--dependency-verification` flag in any README command.

## How to run

```bash
cd tests/e2e && ./sec4b/run.sh                       # or: npm run test:sec4b   (≈ 6–7 min; B2 replays ≈ 4 min in real time)
cd mobile/android && ./gradlew --refresh-dependencies --rerun-tasks :app:assembleRelease -Pnav.gatewayBaseUrl=https://127.0.0.1:9
cd mobile/android && ./gradlew --refresh-dependencies :app:testDebugUnitTest --rerun -Pnav.hostFerrostar=required
python3 tests/android/nav005/static_checks.py
```

**Why the B2 replay runs in real time.** R1's first maneuver change comes about 230 s into the replay. `page.clock` was tried first, but fake time renders every MapLibre frame and ran about 3.5× slower than real time on the dev container (15 min, test timeout).

## Defects found while testing

| ID | Severity | Owner | Scope | Summary |
|---|---|---|---|---|
| D-4B-01 | minor | mobile-engineer | **Out of SEC-4B scope** (pre-existing in NAV-017 voice diagnostics, triage item F1; `web/src/demo/demo.css` is not changed by SEC-4B) → new bug issue | During the B2 replay the «Туршилтын горим» badge (`[data-testid=demo-badge]`, `span.dn-badge.demo-diag-trigger`) has computed `pointer-events: none`, inherited from `.dn-top`. Only `.dn-banner`, `.dn-row-buttons > *`, … are set back to `auto`. `document.elementFromPoint` at the badge centre returns the map `CANVAS`. Neither 5 quick taps nor the 1.5 s press can reach the badge, so the voice diagnostics cannot be opened during a replay. The README says that is the "best evidence" path for iPhone reports. The picker-heading opener is not affected. |

**D-4B-01 steps to reproduce:**
1. Run `cd web && npm run build:demo-mode && npx vite preview --outDir dist-demo-mode`.
2. Open it in Chromium with a 390×844 touch viewport.
3. Pick R1 and tap «Эхлэх».
4. Tap the «Туршилтын горим» badge 5 times quickly, or press and hold it for 1.5 s.

- **Expected:** the voice diagnostics sheet opens (web/README.md › Voice diagnostics).
- **Actual:** nothing opens. Every tap lands on the map canvas (Playwright: "canvas … intercepts pointer events").
- **Evidence:** the TC-4B-11 annotation "B2 badge hit-test". Reproduction script: `scratchpad/qa-sec4b/diag2.mjs`, not committed.
- **Note:** a failing regression test belongs in the bug lane (NAV-017 suite, real `touchscreen.tap` on the badge during a replay).

| ID | Severity | Owner | Scope | Summary |
|---|---|---|---|---|
| F-4B-02 | minor | business-analyst (decision), then qa-engineer (scan) or mobile-engineer (resource keys) | **Out of SEC-4B scope.** Pre-existing since `1dc0305` (NAV-017 F1) → new item through triage | NAV-002 AC 32 independent scan (TC-32-03, `tests/e2e/nav002/static.test.mjs:109`) fails: `web/src/demo/diagnostics.ts hard-codes «Close»`. The voice diagnostics labels (`DIAG_TEXT`) are literals, not resource keys. The code comment and `web/README.md` call the panel an "internal, English-only screen (triage item F1, point 4)", but triage F1 point 4 asked the **BA** to decide that, and nothing records the decision. If the BA records it (for example as a NAV-017 or NAV-002 AC 32 note: the hidden diagnostics panel is internal, English-only, exempt from AC 32 and from the glossary), QA narrows the scan to exclude `DIAG_TEXT` only. If the BA decides otherwise, the mobile engineer moves the labels into the resource files. |

**F-4B-02 steps to reproduce:**
1. Run `cd tests/e2e && ./nav002/run.sh static.test.mjs -g "AC32 independent"`.

- **Expected (NAV-002 AC 32):** `problems` is `[]`.
- **Actual:** `["web/src/demo/diagnostics.ts hard-codes «Close»"]`. This is the same on `d653176`, before SEC-4B.

## Results

Run 1 was on 2026-10-08, on the working tree on top of `d653176` with the SEC-4B changes uncommitted. Light QA, so there is no separate report file; the full command list is in the QA handoff.

| ID | Result |
|---|---|
| TC-4B-01 | PASS ×3. B1 and B3: 1 script hash and 1 style hash. B2: 2 script hashes (guard and boot), 2 style hashes (`design-tokens`, `demo-tokens`), `'wasm-unsafe-eval'` and 1 `.wasm`. B3: `connect-src 'self' http://localhost:8080`. |
| TC-4B-02 | PASS ×3 |
| TC-4B-10 | PASS. All 9 steps ran with 0 violations and 0 CSP console messages. The probe stayed undefined, with 1 `script-src-elem` violation. |
| TC-4B-11 | PASS. 249 s of replay, banner «Зүүн тийш эргэнэ үү» → «Баруун тийш эргэнэ үү», 1 `speak`, 8 Web Audio starts and 1 `data:audio/wav` prime. "Test chime" reported "chime played". 0 violations and 0 CSP console messages. The probe stayed undefined, with 1 `script-src-elem` violation. |
| TC-4B-12 | PASS. 2 searches with icon rows, a place card, «Машин» with 1 POST `/v1/route` and 4 turn rows. 0 violations and 0 CSP console messages. The probe stayed undefined, with 1 `script-src-elem` violation. |
| TC-4B-20 | PASS. BUILD SUCCESSFUL in 3 min 47 s, and all 4 gates executed. |
| TC-4B-21 | PASS. BUILD SUCCESSFUL in 4 min 13 s: 105 suites, 741 tests, 0 failures, 0 errors, 6 skipped, the same as the baseline. |
| TC-4B-22 | PASS |
| TC-4B-23 | PASS. Both checksums equal Gradle's published files: `7d3a4ac4…6172` and `bd711022…3531`. |
| TC-4B-24 | PASS. 7 = 7 `.so` names. |
| TC-4B-30 | PASS: 49 passed, 0 failed. Before the update: 47 passed, 2 failed. |
| TC-4B-31 | PASS. NAV-004 AC 53 static-build test, run through a scratch config without the gateway health setup because the shared gateway is down. Control: the same build with `VITE_GATEWAY_BASE_URL=http://localhost:8080` fails with the ADR-0018 §2 message. |

### Run 2 (2026-10-08, after review items 1 and 2)

Working tree on top of `d653176`, SEC-4B changes still uncommitted. Docker was started for this run (`dockerd`), and the shared stack came back up with it (gateway `/health` 200). Another agent ran `sec4b/run.sh` at the same time on the default ports, so QA's runs used `TMPDIR=<scratch>`, ports 5291–5293 and their own `--output` and `--reporter`. Neither run could overwrite the other's builds or results.

| ID | Result |
|---|---|
| TC-4B-01, TC-4B-02 | PASS ×3 (run 3 of the suite) |
| TC-4B-10 | PASS (34.8 s) |
| TC-4B-11 | Suite run 2: **FAIL**, in the voice-diagnostics step: "condition not met after 3000 ms". The trace shows `pointerdown` gaps of 906/647/525/838 ms, above `TAP_GAP_MS` 600, because each CDP `dispatchEvent` round trip took 250–450 ms while a second B2 replay (the other agent's) shared the 4 CPUs. This was a test-only flaw: the burst is now sent from inside the page. Suite run 3, under the same concurrent load: **PASS**. |
| TC-4B-12 | PASS (13.8 s) |
| Suite total | Run 3: **9 / 9 passed** in 5.6 min |
| TC-4B-20 | PASS. `--refresh-dependencies --rerun-tasks :app:assembleRelease`: BUILD SUCCESSFUL in 3 min 9 s, 70 tasks executed. `checkReleaseGatewayUrl`, `checkReleaseLicenceGate`, `checkThirdPartyNotices` and `checkApkApiLevelTypesRelease` executed, and no verification error appeared. |
| TC-4B-21 | PASS. `--refresh-dependencies :app:testDebugUnitTest --rerun -Pnav.hostFerrostar=required`: BUILD SUCCESSFUL in 3 min 55s, `testDebugUnitTest` executed. JUnit XML: 105 suites, 741 tests, 0 failures, 0 errors, 6 skipped, equal to the baseline. |
| TC-4B-22 | PASS. `verify-metadata` true, `verify-signatures` false, 1,089 sha256 checksums in 641 components, no other algorithm, one `<trust>` rule with its reason in the XML header and README §11.1, no lenient or off setting. The README mentions `--dependency-verification` only in prose ("no command here passes …"). |
| TC-4B-23 | PASS. `7d3a4ac4…6172` and `bd711022…3531` both equal `services.gradle.org` 8.14.3 `.sha256`, and `validateDistributionUrl=true`. |
| TC-4B-24 | PASS: 7 = 7 (`check_native_provenance.py` on the fresh release APK, plus `unzip -l`). |
| TC-4B-30 | PASS: 49 passed, 0 failed. |
| TC-4B-31 | Symlink accepted (review item 2). For the NAV-004 suite result, see TC-4B-33. |
| TC-4B-32 | **Reproduced, then fixed.** The old `addStyleTag` body failed: "Refused to apply inline style … style-src 'self' 'sha256-u/WyyH…' 'sha256-kvvLJm5…'". With the constructable stylesheet it passes unchanged on Chromium (2.3 s) and WebKit in the Playwright 1.56.1 image (40.3 s). |
| TC-4B-33 | NAV-017 Chromium (`run.sh`, 3 workers): 69 passed, 3 failed in 41.3 min. (a) `build.test` AC6 flagged `4.1.1.4`, a Maven version in `verification-metadata.xml` and in the QA notes quoting it. It was added to the reviewed `KNOWN_IP` version-string list beside Jetty and JDK; re-run: PASS. (b) `layout.test` AC38 arrival day and night hit the 5 min test timeout while reaching arrival, with load average 11–13 on 4 CPUs and no axe result. Re-run alone: **2 passed** (7.0 min, with the WebKit pass still running). The same two tests ran at 3.5 min each against a 5 min budget, so this was load, not SEC-4B. NAV-017 WebKit (Playwright 1.56.1 image, 2 workers): **54 passed, 0 failed, 18 skipped** by design (Chromium-only file checks) in 59.6 min, including every replay that calls `hideCanvas`. NAV-004 (`nav004/run.sh`, gateway `/health` 200): **116 / 116 passed** in 24.2 min, including AC 53 on the same-origin static build (TC-4B-31). |

**Run 2 verdict.** Every QA-owned and QA re-checked AC passed: AC 1–6, 9, 11–13, 15, 17, 18, 22–25, 29, 31 (set) and 34. AC 16 has also been re-run for NAV-004 and NAV-017, with the NAV-017 fixes in QA-owned test files and the two load timeouts re-run green. AC 7, 8, 10, 14, 19–21, 26–28, 30, 32 and 33 remain verified by the mobile engineer, with the security-engineer's review; QA did not re-run them. AC 21 is the PO's check on the real host. Open defect: D-4B-01 (minor, out of scope, unchanged).

### Run 3 (2026-10-08, after the mobile engineer's AC 16 re-run)

Same working tree on top of `d653176`, with the SEC-4B changes still uncommitted. No product file under `web/` or `mobile/android/` changed since run 2; the only test change before this run is the run 2 `KNOWN_IP` entry in `nav017/build.test.mjs`. The machine was idle at the start (load 1.0 on 4 CPUs). The shared stack was up (gateway `/health` 200), but the SEC-4B suite does not use it. Default ports 5191–5193.

| ID | Result |
|---|---|
| TC-4B-01 | PASS ×3. B1: 784 files, 0 `.wasm`, 1 script hash, 1 style hash (`design-tokens`). B2: 789 files, 1 `.wasm` (`assets/ferrostar_bg-*.wasm`), `'wasm-unsafe-eval'`, 2 script hashes (head guard, body boot), 2 style hashes (`design-tokens`, `demo-tokens`), `media-src 'self' data:`. B3: 783 files, 0 `.wasm`, `connect-src 'self' http://localhost:8080`. |
| TC-4B-02 | PASS ×3 |
| TC-4B-10 | PASS (33.6 s). All 9 steps; 0 violations / 0 CSP console messages. Probe `undefined`, violations `["script-src-elem"]`. |
| TC-4B-11 | PASS (4.4 min). 248 s replay, banners «Зүүн тийш эргэнэ үү» → «Баруун тийш эргэнэ үү», 1 `speak`, 8 Web Audio starts, 1 `data:audio/wav` muted prime. The diagnostics burst gaps were 129/139/168/153 ms (limit 600), and "Test chime" reported "chime played". 0 violations / 0 CSP console messages. Probe `undefined`, violations `["script-src-elem"]`. D-4B-01 evidence still present: the badge has `pointer-events: none` and the canvas is at its centre. |
| TC-4B-12 | PASS (14.2 s). Gateway calls: 18 tile Range requests, 3 `/v1/search`, 1 POST `/v1/route`. 4 turn rows. 0 violations / 0 CSP console messages. Probe `undefined`, violations `["script-src-elem"]`. |
| Suite total | **9 / 9 passed** in 5.4 min (`./sec4b/run.sh`, rc 0) |
| TC-4B-20 | PASS. `--refresh-dependencies --rerun-tasks :app:assembleRelease -Pnav.gatewayBaseUrl=https://127.0.0.1:9`: BUILD SUCCESSFUL in 4 min 7 s, 70 tasks executed, no verification error. `checkReleaseGatewayUrl`, `checkReleaseLicenceGate`, `checkThirdPartyNotices` and `checkApkApiLevelTypesRelease` executed. |
| TC-4B-21 | PASS. `--refresh-dependencies :app:testDebugUnitTest --rerun -Pnav.hostFerrostar=required`: BUILD SUCCESSFUL in 3 min 55 s; `testDebugUnitTest` executed. JUnit XML: 105 suites, 741 tests, 0 failures, 0 errors, 6 skipped. This equals the baseline. |
| TC-4B-22 | PASS. `verify-metadata` true, `verify-signatures` false, 1,089 sha256 in 641 components, 0 other algorithms, 1 `<trust>` (`.*-(sources|javadoc)[.]jar`) with its reason in the header comment and README. No lenient or off setting, and no `--dependency-verification` in any command. |
| TC-4B-23 | PASS. `gradle-wrapper.jar` sha256 `7d3a4ac4…6172` and `distributionSha256Sum` `bd711022…3531` each equal the `services.gradle.org` 8.14.3 `.sha256` fetched in this run. `validateDistributionUrl=true`. |
| TC-4B-24 | PASS. On the APK from TC-4B-20, `check_native_provenance.py` reports 7 = 7 names. The independent `unzip -l` gives the same 7 names. |
| TC-4B-30 | PASS: 49 passed, 0 failed, 2 info. |
| TC-4B-34 | PASS. Both AC2 tests (`/demo-a/`, `/x/y/demo-b/`) passed with the narrowed filter, 9.4 s. |
| TC-4B-35 | FAIL, reproduced and out of scope: `["web/src/demo/diagnostics.ts hard-codes «Close»"]` (F-4B-02). The test is unchanged. |

**Story text vs. build (non-blocking).** AC 5 in the story file does not yet carry the architect's requested amendment allowing `media-src 'self' data:` in B2 (ADR-0018 F6). B2 has that directive. QA reads AC 5 as written as met: `default-src 'self'` is present, no directive has `*` or a host, and `data:` is not a host. QA's oracle already expects the directive in B2 only. If the BA rejects the amendment, TC-4B-01 and the B2 chime prime (TC-4B-11) must be revisited.

**Run 3 verdict.** Every QA-owned AC passed again on the current tree: AC 17 and 18 on B1, B2 and B3, and AC 25 with both commands. So did every QA re-check: AC 1–6, 9, 11–13, 15, 22–24, 29, 31 (set) and 34. AC 7, 8, 10, 14, 16, 19–21, 26–28, 30, 32 and 33 rest on the mobile engineer's handoffs and the security-engineer's review. AC 21 is the PO's check on the real host. QA did not re-run these. The mobile engineer's AC 16 report has two residual failures. NAV-002 AC32 is pre-existing (F-4B-02, out of scope). NAV-002 AC27 is a 2,000 ms budget missed under load: 2,062 ms, passing alone 3/3, the same load sensitivity as NAV-017 run 1. Neither is caused by SEC-4B. Open defects: D-4B-01 and F-4B-02, both minor and out of scope.

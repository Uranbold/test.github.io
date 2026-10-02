# Test run report: NAV-017 web demo mode, run 2

| | |
|---|---|
| Story | [`NAV-017`](../../requirements/stories/NAV-017-web-demo-mode-replay.md), AC 1–49 |
| Test plan | [`docs/qa/test-plans/NAV-017.md`](../test-plans/NAV-017.md) |
| Previous run | [`NAV-017-run1.md`](NAV-017-run1.md) (FAIL, D1–D4) |
| Date | 2026-10-01 23:13 UTC → 2026-10-02 (the first attempt at run 2 was cut by a container restart at about 23:05 UTC; everything below is from the complete run after the restart) |
| Build under test | `web/` at commit `18cb22a`, **identical to run 1's `7b72cea` in `web/`** (no `web/` change since the mobile-engineer handoff; `find web -newer dist-demo-mode/index.html` is empty). `dist-demo-mode/`, `dist-static-demo/`, `dist/` built 22:32 UTC with the documented commands in a shell without `VITE_*` (`tests/e2e/test-results/nav017/build-*.log`) |
| Environment | 4 vCPU / 16 GB container, software WebGL, nothing else running (load came only from the two engines). **Chromium** `/opt/pw-browsers` headless, 390×844 touch viewport, `Asia/Ulaanbaatar`, `mn-MN`, 1 worker (replays 2). **WebKit** Playwright 1.56.1 `iPhone 13` descriptor inside `mcr.microsoft.com/playwright:v1.56.1-noble` (`--network host`, 1 worker), because the host lacks WebKit's system libraries. Site: the production builds served by Caddy 2.10.2 (Docker) on `127.0.0.1:18097` as `/` (public static build), `/tiles/basemap.pmtiles` (UB extract), `/demo-a/`, `/x/y/demo-b/`, `/locked-demo/` (Basic auth, throw-away credentials). The shared dev stack on `localhost:8080` was not used, started, stopped or rebuilt (`/health` 200 throughout) |
| Verdict | **FAIL.** 7 open defects (D1–D4 from run 1 reproduced unchanged; D5–D7 new, from the reproducing tests the ADR-0011 review asked for), all S3/S4. 42 of 49 AC pass at the level this environment allows; 5 fail: AC 9 (D1), AC 26 for G4 only (D3), AC 27 and 28 in the pending-decision case (D5), AC 37 (D2, D4); AC 48 and 49 are the PO's real-iPhone checks (not verified here) |
| Owner | qa-engineer |

## 1. Summary

Chain scripts (scratchpad `qa17r3-chromium.sh`, `qa17r3-webkit.sh`): one file at a time, own `--output` folder
`test-results/nav017/r3-{c,w}-<file>`, site guard before each file, per-file JSON copies `qa17r3-{c,w}-<file>.json`.

| Suite | Engine | Result | Duration |
|---|---|---|---|
| `build.test` (AC 1, 3, 4, 5, 6, 7, 36, size budget, AC 16/44 data, manifest) | Node | **15 / 15 passed** | 33 s |
| `adr.test` (ADR-0011 §7 priming, §10 licence text, Amendment 2 README note) | Chromium | **0 / 3 passed** → D5, D6, D7 | 2.5 min |
| `picker.test` (AC 2, Basic auth, 8, 9 ×3, 10 ×4, 11, double tap) | Chromium | **12 passed, 2 failed** (AC 9 at 375×667 and 390×844 → D1) | 3 min |
| `ui.test` (AC 15, 16, 23, 27 ×2, 28, 29 ×4, 30, 31 ×2, 35, 39 ×2, 40, 41) | Chromium | **17 / 17 passed** | 19 min |
| `replay.test` (7 full replays at 1× on the fake clock) | Chromium | **6 passed, 1 failed** (G4 → D3; every other G4 step passed) | 29 min |
| `layout.test -g AC37` (4 viewports × 100/200 %, emulated iPhone insets; static safe-area CSS) | Chromium | **7 passed, 2 failed** (844×390 at 100 % and 200 % → D2; at 200 % also the A1 clipping → D4) | 10 min |
| `layout.test -g AC38` (axe WCAG 2.2 AA, names, 44 px, live region; 3 states × 2 themes) | Chromium | **6 / 6 passed** | 7 min |
| `picker.test` | WebKit | **12 passed, 2 failed** (D1, same numbers) | 5 min |
| `adr.test` | WebKit | **0 passed, 1 failed, 2 skipped by design** (§7 priming → D5; the file checks run once, on Chromium) | 5 min |
| `layout.test -g AC37` | WebKit | **7 passed, 1 failed, 1 skipped by design** (844×390 at 200 % → D4; insets cannot be emulated, so D2 cannot show) | 14 min |
| `layout.test -g AC38` | WebKit | **6 / 6 passed** | 8 min |
| `ui.test` | WebKit | **17 / 17 passed** | 30 min |
| `replay.test -g 'R3 en\|G4'` (the two replays run 1 could not finish on WebKit) | WebKit | **R3 en passed** (every AC step, incl. golden G8 en with the two derived rows), **G4 failed** (D3, the same two steps as Chromium) | 11 min | |
| `replay.test` R1 mn, R1 en, R1 chime fallback, R2 mn, R3 mn | WebKit | **5 / 5 passed in run 1 §3.1 on the identical build**; not repeated here to free the machine for the regression suites (see §3.1) | — |
| AC 45: `npm test` | Vitest (Node) | **30 files, 602 tests passed** (`cd web && npm test`, `qa17r3-unit.log`) | 40 s |
| AC 45: `npm run lint` | eslint + check-i18n | **0 problems; check-i18n OK, 163 keys** (`qa17r3-lint.log`) | < 1 min |
| AC 45: `npm run typecheck` | tsc | **0 errors** (`qa17r3-typecheck.log`) | < 1 min |
| AC 45: `npm run check:glossary` | script | **163 / 163 mn values match** (`qa17r3-glossary.log`) | < 1 min |
| AC 45: NAV-002 / NAV-003 / NAV-004 Playwright suites, NAV-002 static-demo | Chromium + dev stack | __REGRESSION__ | |

**Totals for the Chromium engine: 63 passed, 8 failed of 71 tests** (D1 ×2, D2 ×2, D3, D5, D6, D7).
**WebKit: 42 passed, 4 failed, 3 skipped by design of 49 tests run here** (D1 ×2, D4, D5), plus R3 en passed, G4 failed (D3).

## 2. Acceptance criteria → result

C = Chromium (host), W = WebKit iPhone 13 (Docker), N = Node, U = the mobile engineer's Vitest suite run by QA, R = real iPhone (PO). Unchanged from run 1 unless marked **(run 2)**.

| AC | Result | Evidence |
|---|---|---|
| 1 | PASS (N) | one `demo mode build: demo mode on; search, reverse and routing are off (NAV-017)` line; `.env.demo-mode` has `VITE_STATIC_DEMO=true` + `VITE_DEMO_MODE=true`; `.env.static-demo` `VITE_DEMO_MODE=false`; `.env.example` lists the key without a host |
| 2 | PASS (C, W) | every asset, the WASM and `demo-routes/r1.json` from `/demo-a/` and `/x/y/demo-b/`; tiles 206 from `/tiles/`; opening scale bar equals the public site's; Basic-auth stand-in 401 / 200 / assets 200 / tiles 206 on both engines. **R:** the PO's host |
| 3 | PASS (N) | no server or dot files; `noindex, nofollow`; no `crossorigin`; relative URLs only |
| 4 | PASS (N) | both public builds: 0 `demo-` ids, 0 `demo-routes`, 0 R1 geometry substring, no WASM, no demo code markers; positive control finds all four in the demo build |
| 5 | PASS (N) | README section with placeholders only, 401/200/206 and `Content-Range`/`Content-Encoding` checks, hPanel step, D74 password holders, real-iPhone checklist incl. the UX additions. **D7** (route list names) is an Amendment 2 consistency item, not an AC 5 failure |
| 6 | PASS (N) | repo scan clean (no `.htpasswd`, Apache auth directive, password value, Hostinger host name, IPv4 outside loopback/documentation ranges beyond the reviewed pre-existing literals); `dist-demo-mode/` git-ignored |
| 7 | PASS (N) | public static build has no reference to a demo folder or the demo build; no `robots.txt` |
| 8 | PASS (C, W) | heading «Туршилтын горим», radiogroup «Маршрут сонгох», exactly R1–R3 with manifest names, «Машин»/«Явган» · distance · duration; attribution visible. Picker after the last opening tile **(run 2)**: 1239 ms (C), 1881 ms (W); the 1 s "after idle" figure is not observable here (plan §5) |
| 9 | **FAIL (C, W) → D1** | 1366×768 passes; 375×667 and 390×844 put a route end under the sheet, same coordinates as run 1 on both engines. Markers ≤ 1 s, «Эхлэх» enabled, replacement, 0 off-origin requests pass |
| 10 | PASS (C, W) | 404 / 500 / HTML body → «Алдаа гарлаа» + «Дахин оролдох», «Эхлэх» disabled, retry once per press, other entries work; «Ачаалж байна…» only after 300 ms |
| 11 | PASS (C, W) | language, theme, zoom, compass, attribution work; `search-field`, `route-open`, `my-location`, right-click card absent; 0 backend requests |
| 12 | PASS (C) | every banner-distance change within 115 ms after a whole replay second in all 7 replays; U `replay.test.ts` ± 100 ms |
| 13 | PARTIAL (code review) | `mapLayers.ts`: `shownAlong` only grows; geographic puck position not observable in the production build (plan §5) |
| 14 | PASS (C) | Geolocation stub count 0 in all 7 replays; badge visible every second and never intersecting banner, progress or attribution; `my-location` absent |
| 15 | PASS (C, W) | hidden mid-prompt: utterance cancelled, 60 s hidden → 0 prompts, distance and progress frozen; visible → rest of G1 = golden shifted by the pause ± 2 s; wake lock re-requested. **R:** lock/unlock |
| 16 | PASS (C, W, N) | truncated track → picker, nothing selected, puck gone, wake lock released; every track's first/last fix ≤ 30 m from the route ends |
| 17–20 | PASS (C) for R1, R2, R3 mn/en; **FAIL for G4 → D3** | banner = AC 27 text of the next manoeuvre, never blank, distance within max(25 m, 10 %) + one fix, street of the following step, switch ≤ 1 s after the first fix 5 m past; AC 19 scans 0 hits; sentinel 0 occurrences on R1 and R3 |
| 21 | PASS (C) | ETA = fake clock + remaining duration (± 1 min), remaining time/distance in AC 23/24 format, never older than 5.5 s |
| 22 | PASS (C), heading-up PARTIAL | puck centre inside the uncovered map band in every sample; camera bearing not readable (compass hidden) → screenshots and **R** |
| 23 | PASS (C, W) | drag → «Байршил руу буцах» ≤ 300 ms, replay continues, recenter ≤ 1 s, 15 s auto-resume; 0 requests other than map assets |
| 24, 25 | PASS (C, U) | AC 33 scan clean on every spoken mn text; en: 0 Cyrillic; no «1000 метрт» / "1000 meters" |
| 26 | PASS (C) for G1 mn/en, G5 mn, G8 mn, G8 en (rows 26–27 derived, §5); **FAIL for G4 → D3**; G5 en: no golden rows | (manoeuvre, text) sequence equal, every prompt within ± 2 s; schedule oracle clean on R1–R3; **W (run 2):** R3 en golden sequence equal and within ± 2 s on WebKit too; G4 fails identically (D3); R1 mn/en, R1 chime, R2 mn, R3 mn passed on WebKit in run 1 on the identical build |
| 27 | PASS (C, W) for the normal case; **FAIL → D5** when the decision is still pending at «Эхлэх» | depart prompt ≤ 2 s; first `speak()` and AudioContext create/resume inside the «Эхлэх» handler (speech and chime variants). With the voice list still loading: depart chimed (ok) but 0 `speak()` inside the handler (ADR-0011 §7 Amendment 1). **R:** real iOS unlock |
| 28 | PASS (C, W); see D5 | every utterance sets voice and `lang`; `en-US` preferred over a first-listed `en-GB`; a list that fills on `voiceschanged` after 500 ms is waited for; a list that fills after «Эхлэх» is picked up for the next prompt |
| 29 | PASS (C, W) | English-only list on R1: 0 Mongolian `speak` calls, 8 chimes = 8 golden prompts, A1 once for ~8 s not covering; speech error → chime ≤ 1 s for the rest + A1; English UI without voice → chime, no A1; switch to Mongolian → A1 then chimes; tap dismisses A1. **R:** audibility |
| 30 | PASS (C, W) | «Дууг хаах» → «Дууг нээх», cancel ≤ 1 s, 0 utterances and 0 chimes, `navmn.voiceMuted=1`, survives reload |
| 31 | PASS (C, W) | switch to English mid-replay ≤ 1 s, remaining G1 en golden texts with `en-US`, 0 repeats, 0 requests |
| 32–34 | PASS (C) | arrival banner from the arrive modifier (R1 right, R2 none, R3 left), spoken once (or one chime), approaching prompt before it where the golden has one, 0 prompts after, arrival panel with the manifest name and «Хаах», exactly 1 arrival in R1, R2, R3 and G4 (stationary 30 s) |
| 35 | PASS (C, W) | «Дуусгах» mid-prompt: cancel, picker with nothing selected, puck and pin removed, «Эхлэх» disabled, wake lock released ≤ 1 s |
| 36 | PASS (N) | "Supported browsers" kept word for word plus one sentence; demo section names iPhone Safari (D72), D14, Chromium, WebKit, real iPhone |
| 37 | **FAIL (C) → D2, (C, W) → D4**; portrait PASS (C, W) | 375×667, 390×844, 430×932 at 100 % and 200 % pass on both engines (emulated insets on C). 844×390: controls and the attribution under the left/right insets (C, both zooms); A1 clipped inside the scrolling message card at 200 % (W, and **(run 2)** C with insets). Static CSS: `viewport-fit=cover`, `.demo-nav` pads top/right/left, bottom inset present, no `100vh`. **R:** real insets, Safari toolbar, aA |
| 38 | PASS (C, W) | axe WCAG 2.2 AA 0 violations in picker, guidance, arrival × day, night on both engines; names «Эхлэх», «Дуусгах», «Дууг хаах», «Байршил руу буцах», «Хаах»; targets ≥ 44 px; `aria-live="polite"`; each new instruction announced once. **R:** VoiceOver |
| 39 | PASS (C, W) | `screen` lock at «Эхлэх», released ≤ 1 s after arrival and after «Дуусгах»/track end; re-requested after visible; refused or missing API → no message. **R:** screen stays on for R3 |
| 40 | PASS (C, W) | offline at 20 s: replay, banner, progress continue; «Интернэт холболт алга» in the message card without covering; gone when online |
| 41 | PASS (C, W) | night theme ≤ 1 s; 844×390 column layout and back; 0 repeated prompts; only the night sprite sheet loaded |
| 42, 43 | PASS (C, W) | all requests to the page origin, 0 `/v1/*`, 0 other hosts, 0 service worker, 0 page errors; storage only `navmn.theme`, `navmn.lang`, `navmn.voiceMuted`; 0 coordinates in storage, console, URL |
| 44 | PASS (N, C, W) | build data = fixtures unchanged; `make_gpx.py --check` clean; attribution in every layout state |
| 45 | __AC45_RESULT__ | |
| 46 | PASS (review) | `voiceText.test.ts`, `rules.test.ts`, `replay.test.ts`, `golden.test.ts`, `audio.test.ts`, `build.test.ts` cover the AC 46 list (run 1 §2) |
| 47 | PASS | Chromium host project and WebKit iPhone 13 project; engine per AC in this table; WebKit limits: no safe-area inset emulation, no real iOS audio; the 5 WebKit replays not repeated here passed in run 1 on the identical build |
| 48 | NOT VERIFIED (R) | §7; recording form `docs/qa/checklists/NAV-017-phone-po.md` |
| 49 | NOT VERIFIED (R) | PO on the real host |

## 3. WebKit, regression suites and the interrupted first attempt

### 3.1 WebKit
Fresh in this run: `picker` (12/2), `adr` (0/1/2 skipped), `layout AC37` (7/1/1 skipped), `layout AC38` (6/6), `ui` (17/17),
`replay -g 'R3 en|G4'` (R3 en passed, G4 failed (D3)). The five other replays (R1 mn sentinel, R1 en, R1 chime
fallback, R2 mn, R3 mn) passed on WebKit in run 1 §3.1 against the same `web/` tree and build settings; they were not
repeated so that the AC 45 regression suites could run with the machine to themselves. WebKit could not verify: AC 37
safe-area insets (no emulation; Chromium CDP covers it, the real iPhone decides) and the build checks (Node).

### 3.2 AC 45 regression suites
__REGRESSION_DETAIL__

### 3.3 The interrupted first attempt at run 2
Before the container restart (23:05 UTC), `build` (15/15) and `picker` (12/2) had completed on Chromium, `picker` (12/2)
on WebKit, `adr` on Chromium (0/3, the same three failures as here) and `layout AC37` on WebKit (7/1, the same D4). Those
numbers agree with the complete run and are not counted again. Their folders `test-results/nav017/r2-*` contain
`.last-run.json` files that say `failed` with an empty list because the run was killed; they are not results.

## 4. Defects (open; none fixed since run 1 because `web/` is unchanged)

Severity is QA's per `docs/team/intake-and-triage-flow.md` §3.4 (S1 outage/crash/dangerous guidance/leak; S2 core
feature broken for many users; S3 degraded with a workaround; S4 cosmetic). The architect's ADR-0011 Amendment 3 rates
the licence text, D1 and D2 as *major*. QA keeps **S3** for all three: the picker still works and the route is shown
in full once «Эхлэх» is tapped (D1); portrait is unaffected and only the horizontal insets in landscape are missing
(D2); the licence text has no user impact (D6). The difference changes nothing in process: the story is P1, S3 + P1 is
the bug lane, and the architect's gate "fix loop before the PO uploads" applies to all of them. D2 and D6 touch hard
rules (CLAUDE.md rule 8 attribution visible on every map screen; BSD-3-Clause redistribution), so they are marked
**must fix before upload** regardless of severity.

| # | Title | Severity | Owner | Reproducing test (stays as the regression test) | Engines |
|---|---|---|---|---|---|
| D1 | Picker camera fit: a route end lands under the sheet on 375×667 and 390×844 (AC 9) | S3 minor | mobile-engineer | `picker.test.mjs` › AC9 375×667, AC9 390×844 | C, W |
| D2 | Landscape: picker sheet, zoom/compass cluster and attribution strip under the left/right safe-area insets (AC 37; CLAUDE.md rule 8) **must fix before upload** | S3 minor | mobile-engineer | `layout.test.mjs` › AC37 844×390 at 100 % and 200 % | C (CDP insets) |
| D3 | A track that starts past a manoeuvre shows and speaks that passed manoeuvre for ~2 s (AC 17/20, AC 26 "no prompt for a passed manoeuvre"; G4 only, not reachable from the picker) | S3 minor | mobile-engineer (web port) + triage for the Android twin (ADR-0009/0011 lockstep) | `replay.test.mjs` › G4 › steps "AC17–AC20", "AC26: no prompt for a manoeuvre already passed" | C, W |
| D4 | WebKit, 844×390 at 200 % text: the A1 notice is clipped inside the scrolling message card (AC 37, AC 29) | S3 minor | mobile-engineer; UX decides the 200 % landscape message-stack layout | `layout.test.mjs` › AC37 844×390 at 200 % (WebKit project) | W |
| D5 | «Эхлэх» tapped while the 3 s voice decision is pending: speech is not primed inside the handler (ADR-0011 §7 Amendment 1; AC 27/28). On an iPhone whose voice list loads late, a fast tap can turn the whole replay into the chime fallback | S3 minor | mobile-engineer (`web/src/demo/audio.ts` › `unlockForStart`/`ensureUnlocked`) | `adr.test.mjs` › "ADR-0011 §7 Amendment 1 / AC 27–28" (soft assertion "speech primed with an empty utterance inside the «Эхлэх» handler") | C, W |
| D6 | `web/THIRD_PARTY_NOTICES.md` has no licence text for `@stadiamaps/ferrostar` 0.57.0 ("ships no licence file"), although the demo build redistributes its WASM binary (ADR-0011 §10 Amendment 1, required) **must fix before upload** | S3 minor | mobile-engineer (`web/scripts/gen-third-party-notices.mjs` override from `https://raw.githubusercontent.com/stadiamaps/ferrostar/0.57.0/LICENSE.txt`) | `adr.test.mjs` › "ADR-0011 §10 Amendment 1: THIRD_PARTY_NOTICES.md reproduces the full BSD-3-Clause licence" | N |
| D7 | `web/README.md` › Demo mode route list does not name the picker destinations: it says "R1 … → Зайсан", "R2 … → near Улсын их дэлгүүр", "R3 over Их тойруу"; the picker shows «Зайсан Голден Вилл», «Хаан банк», «Золтамир» (ADR-0011 Amendment 2 data note; AC 5/8 consistency) | S4 trivial (reported as minor) | mobile-engineer | `adr.test.mjs` › "ADR-0011 Amendment 2 README data note" | N |

### NAV-017-D5 (minor, S3) — no speech priming inside «Эхлэх» while the voice decision is still pending
- **Steps:** `speechSynthesis` stub whose `getVoices()` stays empty until the test releases it (iPhone-like late list), Mongolian UI; open `/demo-a/`, select R1 and tap «Эхлэх» within 3 s of page time of the picker opening (the decision is still polling `getVoices()`, asserted as a precondition); release the voice list 300 ms later.
- **Expected (Amendment 1 §7):** the depart prompt is chimed (decision unknown) **and** one empty, zero-volume utterance with no voice set is passed to `speechSynthesis.speak()` inside the click handler, so iOS allows later speech; once the list names a Mongolian voice, the next prompt is spoken with it.
- **Actual (Chromium, run 2; WebKit see §3):** depart chimed at ≤ 2 s (pass); **0** `speak()` calls inside the handler (fail); the late voice is picked up and «1 километрт зүүн тийш эргэнэ үү» is spoken with `mn-MN` within ± 2 s of the golden time (pass); no chime after the voice is known, no A1 (pass). The stubbed browser does not refuse the later `speak()`, which is why the rest passes here; real iOS Safari would refuse it without the in-gesture priming (ADR-0011 §7), and the error rule would then chime the rest of the replay.
- **Code (review):** `unlockForStart()` resumes the AudioContext, plays the silent buffer and calls `speechSynthesis.cancel()`; `ensureUnlocked()` primes speech only when the decision already says the language speaks.

### NAV-017-D6 (minor, S3; must fix before upload) — Ferrostar licence text missing from THIRD_PARTY_NOTICES.md
- **Steps:** read `web/THIRD_PARTY_NOTICES.md` lines 847–853.
- **Expected:** the full upstream BSD-3-Clause text of `stadiamaps/ferrostar` at tag 0.57.0 (31 lines: `Copyright (c) 2023, Stadia Maps, Inc.`, the three conditions, the disclaimer), like every other entry in the file.
- **Actual:** "BSD-3-Clause. The npm package ships no licence file; see https://github.com/stadiamaps/ferrostar."
- **Note:** the architect located the file (`LICENSE.txt`, not `LICENSE`); the test checks the three parts, not a byte-exact copy, so a verbatim paste passes.

### NAV-017-D7 (minor, S4) — README route list names differ from the picker
- **Steps:** compare `web/README.md` › "Demo mode (NAV-017)" › "Routes:" with `web/src/demo/routes.manifest.json`.
- **Expected:** each picker origin and destination named as the PO sees it («Сүхбаатарын талбай → Зайсан Голден Вилл», «Сүхбаатарын талбай → Хаан банк», «Сонгосон цэг → Золтамир»); other wording may follow.
- **Actual:** "Зайсан", "near Улсын их дэлгүүр" (the State Department Store is 377 m from the R2 end, run 1 §7), no destination for R3.

## 5. Data checks carried over from run 1 (inputs unchanged, so the run-1 evidence stands)

- **AC 26 golden currency (architect request 2):** `tests/gpx/nav005/golden/voice-golden.tsv` and the Android guidance code
  (`mobile/android/app/src/main`) last changed in the same commit `17aadee` (D69 phrases); neither changed since. Run 1
  §3.3: `QaGpxReplayTest.tcR13_goldenAnnouncementSet` regenerates the set on the current Android code with the host
  Ferrostar 0.57.0 and asserts it equals the file; 1 test, 0 failures. The golden set is current and gates AC 26.
- **`en` recordings (architect request 3):** `p1-p3-car-en` and `p1-p2-walk-en` have the same manoeuvre fields,
  distances, durations and geometry as their `mn` recordings; `g8-roundabout-car-en.json` differs (17 steps, `arrive`
  without a side, 9205.3 m, vs 18 steps, `turn left` 3.9 m then `arrive left`, 9209.2 m). Consequence and handling: run 1
  §6.1 and plan §5 (`GOLDEN_DERIVED` rows 26–27). BA decision still open.
- **OSM ids and names in `web/src/demo/routes.manifest.json` (architect request 1, mobile-engineer request 1):** run 1 §7,
  OSM API 0.6 on 2026-10-01. Every id exists and carries exactly the manifest name; every end within 50 m of a named
  feature has one; the R3 origin has no named feature within 60 m (T6 fallback correct). Two choices are product
  decisions, not errors: the R1/R2 origin uses the square (polygon edge ~0 m, centroid ~45 m) rather than the street at
  0 m; the R2 destination uses the nearest Cyrillic-named POI «Хаан банк» (41.5 m) rather than the nearest named feature
  («Түшиг төв», 23.6 m). U1 («Сонгосон цэг» stored vs translated) is open with the BA; the code supports both.
- **Basic-auth local server check (architect request 4, ADR-0011 §9):** `picker.test` "ADR-0011 §9 Basic auth
  stand-in" on both engines (this run): 401 without credentials, 200 with them, every asset 200, tiles 206.
- **WebKit availability (architect request 5, AC 47):** Playwright WebKit cannot run on the host (missing system
  libraries); it runs inside `mcr.microsoft.com/playwright:v1.56.1-noble` with `--network host` against the same site,
  Playwright 1.56.1 on both sides. The only WebKit limits are safe-area inset emulation (none) and real iOS audio.

## 6. Observations and interpretations (unchanged from run 1 unless marked)

1. G8 `en` fixture difference (§5); BA decision needed.
2. G5 `en`: no golden rows; only the mobile engineer's Vitest sanity test covers it; BA decision needed.
3. AC 8 "≤ 1 s after the first map idle" is not observable in the production build; recorded as an annotation (this run:
   Chromium 1239 ms, WebKit 1881 ms after the last opening tile, gross limit 5 s). The real check is the PO's iPhone.
4. AC 13 (puck never backwards) verified by code review only; AC 22 heading-up by screenshots only (plan §5).
5. AC 4: the shared i18n resource file (with the `demo.*`, `nav.*`, `voice.*` values) is bundled into the public builds;
   resource text, not code; accepted in ADR-0011 Amendment 1. Whether the unreviewed guidance strings may stay in the
   public bundle before NAV-007 is a PO question carried by the architect.
6. **New:** the G4 passed-manoeuvre behaviour (D3) is reported by the mobile engineer as identical on Android, where
   `tcR07_g4ArrivalExactlyOnce` checks only the arrival count. QA did not run Android here (no test asserts it; adding one
   is a `mobile/android` change). Recommendation to triage: one NAV-005 bug item for the shared rule, fixed on both
   platforms in one change with a golden regeneration through the BA, plus an Android assertion in `QaGpxReplayTest`.

## 7. Not verified in this environment (AC 48, 49; recording form `docs/qa/checklists/NAV-017-phone-po.md`)

- The browser password prompt and the 401 / 200 / 206 checks on the PO's Hostinger host; tiles over HTTP Range under
  the real protection (the local Caddy stand-in passes on both engines).
- Whether iOS lists a Mongolian voice (A1 appears or not); English speech; chime audibility with the ring/silent switch
  off and on; speech and chime starting without an extra tap after «Эхлэх» in real Safari (D5 makes the late-voice-list
  case likely to fall back to the chime).
- Screen staying on for the whole R3 replay (787 s); pause and resume after locking the phone; audio after a phone
  call or Control Centre.
- Real safe-area insets (D2 and D4 come from emulation and WebKit text metrics), the Safari toolbar collapsing, aA text
  size at 200 %, a pinch on the banner not zooming the page, rotation, VoiceOver order and announcements, the iOS
  version. 375×548 (Safari toolbars shown on a 375 px iPhone) at 200 % text is outside the design target (UX known
  limitation 1) and is not tested.
- Camera heading-up (AC 22) and the puck's geographic snapping (AC 13): not readable in the production build.
- Android behaviour on G4 (D3 twin): not run here.

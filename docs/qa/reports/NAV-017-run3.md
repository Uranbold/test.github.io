# Test run report: NAV-017 web demo mode, run 3 (re-verification of the fix round for D1–D7)

| | |
|---|---|
| Story | [`NAV-017`](../../requirements/stories/NAV-017-web-demo-mode-replay.md), AC 1–49 |
| Test plan | [`docs/qa/test-plans/NAV-017.md`](../test-plans/NAV-017.md) §6 (failing tests held in the fix loop) |
| Previous run | [`NAV-017-run2.md`](NAV-017-run2.md) (FAIL, D1–D7 open, all S3/S4; D2 and D6 "must fix before upload") |
| Date | 2026-10-02 08:20–08:48 UTC |
| Build under test | `web/` at commit **`0b166a8`** (the mobile engineer's fix round: picker fit D1, landscape safe-area insets D2, first-fix catch-up D3, 200 % landscape message layout D4, speech priming while the voice decision is pending D5, Ferrostar BSD-3 licence text D6, README route names D7). Working tree clean under `web/`. `dist-demo-mode/`, `dist-static-demo/`, `dist/` **rebuilt by `nav017/run.sh`** at 08:20:51 UTC with the documented commands (`test-results/nav017/build-*.log`) |
| Scope of this run | **Re-verification only**, as instructed by the orchestrator: the reproducing tests of D1–D7 on the engines where they failed, plus the files the fix touched most (AC 37 all viewports on WebKit, AC 41 both engines, AC 9 both engines). The full suites (`build`, the other `picker`/`ui`/`layout AC38` tests, the R1–R3 replays) and the AC 45 regression suites were **not** repeated here; see §4 |
| Environment | 4 vCPU / 15 GB container, software WebGL, nothing else running on the machine. **Chromium** `/opt/pw-browsers` headless, 390×844 touch viewport, `Asia/Ulaanbaatar`, `mn-MN`, 1 worker. **WebKit** Playwright 1.56.1 `iPhone 13` descriptor inside `mcr.microsoft.com/playwright:v1.56.1-noble` (`--network host`, 1 worker). Site: `nav017/run.sh` → `site.mjs` → Caddy 2.10.2 (Docker, container `qa-nav017-site`) on `127.0.0.1:18097` with `/` (public static build), `/tiles/basemap.pmtiles` (UB extract), `/demo-a/`, `/x/y/demo-b/`, `/locked-demo/` (Basic auth, throw-away credentials, deleted at stop). The shared dev stack on `localhost:8080` was not used, started, stopped or rebuilt (`/health` 200 before and after) |
| Verdict | **PASS for the fix round: D1, D2, D3, D4, D5, D6 and D7 are verified fixed**; every reproducing test from run 2 §4 passes **unchanged in what it asserts** (two harness-only corrections, §2) on every engine where it failed. No new defect. **Upload gate: D2 and D6 verified fixed.** The whole suite has not yet been run on this build (§4) |
| Owner | qa-engineer |

## 1. Results

Commands, from `tests/e2e` (chain scripts in the session scratchpad, `qa17r4.sh` and `qa17r4-c2.sh`; one file per
invocation with its own `--output` folder `test-results/nav017/r4-{c,w}-<tag>`; logs `qa17r4-{c,w}-<tag>.log`,
per-file JSON copies `qa17r4-{c,w}-<tag>.json`):

```bash
# build + site + Chromium adr (the first invocation goes through run.sh; NAV017_KEEP=1 leaves the site up for the rest)
NAV017_ENGINES=chromium NAV017_KEEP=1 NAV017_WORKERS=1 ./nav017/run.sh adr.test.mjs --output test-results/nav017/r4-c-adr
# the remaining files use run.sh's own two command lines, one file at a time, after a curl guard on /demo-a/ (200);
# site.mjs start is never called while the site is up, because it re-creates the Caddy container
NAV017_CHROMIUM=1 NAV017_WEBKIT=0 NAV017_WORKERS=1 npx playwright test -c nav017/playwright.config.mjs <file> [-g <filter>] --output test-results/nav017/r4-c-<tag>
docker run --rm --network host --ipc=host -v $REPO:$REPO -w $REPO/tests/e2e -e NAV017_CHROMIUM=0 -e NAV017_WEBKIT=1 -e NAV017_WORKERS=1 \
  mcr.microsoft.com/playwright:v1.56.1-noble npx playwright test -c nav017/playwright.config.mjs <file> [-g <filter>] --output test-results/nav017/r4-w-<tag>
node nav017/site.mjs stop
```

| # | File / filter | Engine | Result | Wall time | Verifies |
|---|---|---|---|---|---|
| 1 | `adr.test.mjs` (§7 priming pending decision; §10 licence text; Amendment 2 README) | Chromium | **3 / 3 passed** | 1.7 min | D5, D6, D7 |
| 2 | `replay.test.mjs -g G4` | Chromium | **1 / 1 passed** (every AC step incl. "AC17–AC20" and "AC26: no prompt for a manoeuvre already passed") | 19 s | D3 |
| 3 | `adr.test.mjs` | WebKit | **1 passed, 2 skipped by design** (the two file checks run once, on Chromium) | 4.6 min | D5 |
| 4 | `replay.test.mjs -g G4` | WebKit | **1 / 1 passed** | 1.0 min | D3 |
| 5 | `layout.test.mjs -g AC37` (4 viewports × 100/200 %, insets 0) | WebKit | **8 passed, 1 skipped by design** (static CSS check runs on Chromium) | 16.3 min | D4 (844×390 at 200 %) |
| 6 | `ui.test.mjs -g AC41` | WebKit | **1 / 1 passed** | 1.0 min | AC 41 after the landscape CSS change |
| 7 | `picker.test.mjs -g AC9` (375×667, 390×844, 1366×768) | WebKit | **3 / 3 passed** | 1.9 min | D1 |
| 8 | `layout.test.mjs -g "AC37 844×390"` (100 % and 200 %, CDP `Emulation.setSafeAreaInsetsOverride` left 47 / right 47 / bottom 21) | Chromium | **2 / 2 passed** | 4.0 min | **D2** (the only engine that can show it), D4 Chromium variant |
| 9 | `picker.test.mjs -g AC9` | Chromium | **3 / 3 passed** | 2.3 min | D1 |
| 10 | `ui.test.mjs -g AC41` | Chromium | **1 / 1 passed** | 1.2 min | AC 41 after the landscape CSS change |

**Totals: Chromium 10 passed, 0 failed; WebKit 14 passed, 0 failed, 3 skipped by design.** Rows 8–10 were added by QA
beyond the requested list because Chromium was idle while WebKit ran and D2 is an upload-gate item whose reproducing
test only has teeth on Chromium (WebKit cannot emulate insets); the mobile engineer's own Chromium run of these is
thereby confirmed first-hand.

## 2. The two harness-only corrections (test side, QA-owned; no assertion weakened)

Both were asked for by the orchestrator after the mobile engineer's local run; both are in `tests/e2e/nav017/`.

1. **`adr.test.mjs` › ADR-0011 §10 licence text.** The two regexes assumed the canonical BSD-3-Clause line wrapping;
   Ferrostar's upstream `LICENSE.txt` at tag 0.57.0 wraps "modification,\nare permitted" and "CONTRIBUTORS\n"AS IS"",
   and the fix pastes it verbatim (`web/licenses/stadiamaps-ferrostar-LICENSE.txt`, `web/THIRD_PARTY_NOTICES.md`), so
   the old regexes would have failed a correct file. Every inter-word gap is now `\s+`. The check got **stricter, not
   weaker**: the copyright line, the full "Redistribution and use …" sentence, **each of the three conditions** (source,
   binary, no endorsement), the disclaimer's opening and its last clause ("EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
   DAMAGE") must all be present, and "ships no licence file" must be absent.
2. **`checks.mjs` › `bannerProblems`, AC 20 switch oracle.** When manoeuvre *k*'s "pass" fix is the track's first fix
   (G4 starts 189 m past manoeuvre 1 of R1), the fixed build renders the banner for manoeuvre 2 inside the «Эхлэх»
   handler, before the recorder's `MutationObserver` attaches, so no mutation exists and the oracle picked the next
   switch (arrival, 49.0 s later) and misreported it. Now, **only when the first fix is already ≥ 5 m past manoeuvre
   *k***, the first 1 s record counts as the switch **if it already shows manoeuvre *k* + 1** (or *k* + 2 after a step
   < 40 m); otherwise the old mutation search runs unchanged, so a first record that still shows the passed manoeuvre
   (the D3 behaviour) is judged by its later mutation, as before. All other switches are evaluated exactly as in run 2.

**Proof that D3 still fails on the old build with the modified oracle** (`oracle-check.mjs` in the scratchpad; the
run-2 WebKit G4 recording of the buggy build was copied out of `test-results/nav017/runs/` before this run overwrote it):

| Recording | First record | Banner mutations | `bannerProblems` (modified) |
|---|---|---|---|
| run 2, WebKit, build `18cb22a` (D3 present) | «Зүүн тийш эргэнэ үү», 10 м, street «Дүнжингаравын гудамж» | 2.0 s «Баруун тийш эргэнэ үү», 49.0 s arrival | **7 problems**: 0.0 s and 1.0 s banner "Зүүн тийш эргэнэ үү" expected manoeuvre 2 "Баруун тийш эргэнэ үү"; distance 10 м vs oracle 381 m / 373 m; street expected «Зайсангийн гудамж»; **AC 20: banner switched to manoeuvre 2 2.0 s after the first fix past manoeuvre 1** |
| run 3, Chromium, build `0b166a8` | «Баруун тийш эргэнэ үү», 380 м | 49.0 s arrival only | none |
| run 3, WebKit, build `0b166a8` | «Баруун тийш эргэнэ үү», 380 м | 49.0 s arrival only | none |

## 3. Defects from run 2 → status

Severity unchanged (QA's, matrix `docs/team/intake-and-triage-flow.md` §3.4). "Verified" = the reproducing test named
in run 2 §4 passes on this build on every engine where it failed, without any change to what it asserts.

| # | Title (run 2) | Reproducing test | Run 2 | Run 3 | Status |
|---|---|---|---|---|---|
| D1 | Picker camera fit: a route end under the sheet on 375×667 and 390×844 (AC 9) | `picker.test.mjs` › AC9 375×667, AC9 390×844 | fail C, W | pass C, W (and 1366×768) | **verified fixed** (`camera.ts` `fitPadding`: margins shrink before covered edges; band ≥ 120 px) |
| D2 | Landscape: sheet, zoom/compass cluster and attribution under the left/right insets (AC 37; CLAUDE.md rule 8) — **upload gate** | `layout.test.mjs` › AC37 844×390 at 100 %, at 200 % (Chromium, CDP insets l47/r47/b21) | fail C | pass C | **verified fixed** (demo-chunk CSS adds `env(safe-area-inset-left/right)` to R1, R2, R4, the route slot and the attribution strip; public builds unchanged per the fix note, not re-verified here, see §4) |
| D3 | Track starting past a manoeuvre shows and speaks it for ~2 s (AC 17/20, 26; G4) | `replay.test.mjs` › G4 › "AC17–AC20", "AC26: no prompt for a manoeuvre already passed" | fail C, W | pass C, W | **verified fixed on the web** (`ferrostarCore.ts` `initial()` applies the step catch-up on the first good fix without the two-fix gate; `guidanceCore.ts` starts the schedule on that step). The **Android twin** (run 2 §6.6) is untouched by this commit: still with triage |
| D4 | WebKit 844×390 at 200 %: A1 clipped inside the scrolling message card (AC 37, 29) | `layout.test.mjs` › AC37 844×390 at 200 % (WebKit) | fail W (and C with insets) | pass W, pass C | **verified fixed** (`GuidanceView.placeMessages`: RN in the left column's scrolling top area in the column arrangement; above RP in the map column at stacked text; camera treats it as covered) |
| D5 | «Эхлэх» while the voice decision is pending: speech not primed inside the handler (ADR-0011 §7 Amendment 1; AC 27/28) | `adr.test.mjs` › "ADR-0011 §7 Amendment 1 / AC 27–28" | fail C, W | pass C, W | **verified fixed** (`audio.ts` `unlockForStart(lang)` primes one empty zero-volume utterance when `voiceFor(lang)` is undefined; `ensureUnlocked` likewise). Real-iOS acceptance of the later speech remains AC 48 (PO) |
| D6 | No Ferrostar licence text in `THIRD_PARTY_NOTICES.md` (ADR-0011 §10 Amendment 1) — **upload gate** | `adr.test.mjs` › "ADR-0011 §10 Amendment 1 …" | fail N | pass N | **verified fixed** (verbatim upstream `LICENSE.txt` 0.57.0 in `web/licenses/stadiamaps-ferrostar-LICENSE.txt`, reproduced by `gen-third-party-notices.mjs`; the test confirms copyright line, all three conditions, disclaimer; "ships no licence file" absent for Ferrostar) |
| D7 | README route list names ≠ picker names (ADR-0011 Amendment 2) | `adr.test.mjs` › "ADR-0011 Amendment 2 README data note" | fail N | pass N | **verified fixed** |

All seven tests stay in the suite as regression tests (plan §6).

## 4. Not run in this round, and why

- The **full NAV-017 suite** on build `0b166a8`: `build.test` (AC 1, 3–7, 36, size budget, data; the licence/README
  changes touch the repo scan and the README checks), the remaining `picker`/`ui` tests, `layout -g AC38` (axe on the
  changed landscape layout), and the R1 mn/en, R1 chime, R2 mn, R3 mn/en replays (the `initial()`/`catchUp` change
  runs on every replay's first fix; on R1–R3 the first fix is on step 0, so `n === 0` and nothing is applied, but that
  is reasoning, not a test result). The orchestrator scoped this run to the re-verification list; plan §6 asks for
  **one whole-suite run before the PO uploads**, and that is the recommended next step.
- The **AC 45 regression suites** (NAV-002 / NAV-003 / NAV-004 Playwright, NAV-002 static-demo) and `npm test`, `lint`,
  `typecheck`, `check:glossary` on `0b166a8`. The fix note says the public builds are unchanged by the D2 CSS (demo
  chunk only) and `build.test` AC 4/7 would confirm it; neither was run here.
- Everything in run 2 §7 (AC 48, 49 on the real iPhone): the browser password prompt and 401/200/206 on the PO's host,
  whether iOS lists a Mongolian voice, speech starting without an extra tap after «Эхлэх» (D5 fix), real safe-area
  insets in landscape (D2 fix) and the aA 200 % layout (D4 fix), screen staying on for R3, VoiceOver.
- Android behaviour on G4 (D3 twin): no Android code changed in this commit.

## 5. Observations

1. The `test-results/nav017/runs/` cache keeps one recording per (test, engine) and is overwritten on every execution;
   the buggy G4 recording used for the proof in §2 survives only as a scratchpad copy (`g4-run2-webkit-buggy.json`).
   If the team wants that proof reproducible later, the cache would need a build-id suffix (a `helpers.mjs` change, QA).
2. Run 2's report still carries two unfilled placeholders (`__REGRESSION__`, `__AC45_RESULT__`, `__REGRESSION_DETAIL__`
   in §1, §2 AC 45 and §3.2) because that session was cut before the handoff; the AC 45 results of run 2 exist as
   `qa17r3-reg-nav002*.json`, `qa17r3-reg-nav004.json` in the scratchpad. Not touched in this run (out of scope); QA
   should fill them or mark them "not completed" in a follow-up.
3. `nav017-results-{chromium,webkit}.json` is overwritten by every Playwright invocation of the same engine; the
   per-file copies in the scratchpad and the `r4-*` output folders are the evidence. The run-3 chain's own copy step
   had the wrong relative path for the first three files; those three were copied by hand from the logs' matching
   JSON before the next invocation (verified by test titles inside each copy).

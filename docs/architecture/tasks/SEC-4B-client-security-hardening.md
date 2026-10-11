# SEC-4B task breakdown: client security hardening (web CSP and headers, Android dependency verification)

- **Story:** [SEC-4B](../../requirements/stories/SEC-4B-client-security-hardening.md) (P1 / intangible, PO "Go" 2026-10-08, D215 release gate)
- **Design:** [ADR-0018](../adr/0018-client-csp-and-android-dependency-verification.md). The facts F1 to F12 there are what the policy is built from
- **Contract:** no change (`openapi.yaml` stays 0.6.1)
- **Owner of this file:** architect. **Date:** 2026-10-08. Task list only, no code

## Boundary
- **Backend:** no tasks. Gateway and edge headers are SEC-4A / BE-13, and the web-root headers are set by the PO from the README (story Context).
- **Mobile engineer:** `web/**` and `mobile/android/**`.
- **QA:** light run only (story Context › Process). AC 17–18 (a new Playwright spec under `tests/e2e/`) and AC 25.
- **Security-engineer:** Verify-stage review, then updates the register status.

## Backend tasks
None.

## Mobile task list: web (WS-1, WS-13, WS-16)

**MW1. `navmn-csp` plugin: generation (ADR-0018 §1 steps 1–4, §2).**
- Goes in `web/buildtools/csp.ts` with `apply: "build"`, listed **last** in `plugins` (after `demoModePlugins`, whose `demoHtml` is also `order: "post"`).
- `transformIndexHtml` with `order: "post"` does four things:
  1. strips comments outside `<script>` and `<style>`;
  2. puts `<meta charset>` first, then the CSP meta, then `<meta name="referrer" content="same-origin">`;
  3. computes a sha256 for every inline `<script>` and `<style>`;
  4. writes the §2 matrix.
- `'wasm-unsafe-eval'` and `media-src 'self' data:` are added in demo mode only (`isDemoMode`).
- B3 `connect-src` comes from one pure helper exported by `web/src/config.ts`, the same rule the runtime uses. B1 and B2 fail the build if the effective gateway is not the page origin.
- Checks: AC 1–7, 11, 15. Built `index.html` of B1, B2 and B3 inspected; the B3 policy with `VITE_GATEWAY_BASE_URL` unset gives `'self' http://localhost:8080`, `same-origin` gives `'self'`, and a scratch `https://<gateway>` gives exactly that origin (the scratch value is not committed).

**MW2. `navmn-csp` verifier (ADR-0018 §1, "Independent verification").**
- Runs in `closeBundle`: reads every `.html` in `outDir` from disk and parses it with `jsdom`.
- Hashes the parsed `textContent` of each inline element and fails with the file name and the element on any rule in ADR-0018 §1.
- Exports pure `buildPolicy` and `verifyHtml` functions. Adds `buildtools/**/*.test.ts` to the vitest `include`.
- Fixture tests:
  - an unhashed inline script fails, and so does an unhashed inline style;
  - `'wasm-unsafe-eval'` without a `.wasm` fails;
  - `onclick=`, `javascript:`, `style=` and `<!--` each fail;
  - a policy placed after a `<style>` fails;
  - `frame-ancestors` in the meta fails.
- Checks: AC 8, 9, 10 (change one character in `bootLoading.ts` in a scratch run, rebuild: new hash, no other edit, verifier passes), and AC 3 (B1 and B3 have 0 `.wasm` files and 0 `'wasm-unsafe-eval'`).

**MW3. Fixture page out of production (ADR-0018 §3).**
- Remove `labelRule` from `rollupOptions.input` for every `vite build`, generalising the demo-mode deletion. Keep `vite.config.ts` a plain object.
- Checks: AC 13 (`find dist* -path '*fixtures*' -o -name '*label-rule*'` is empty for all three builds), AC 14 (`npm run dev`, then `/fixtures/label-rule.html` with `window.__labelRule.ready`, and NAV-002 TC-08-01 passes).

**MW4. Regression of the existing web checks.**
- Checks: AC 12 (B2 still has no `.htaccess` or `.htpasswd`, and still has `noindex, nofollow`), AC 16 (`npm run typecheck`, `lint`, `test`, `check:glossary`, plus the NAV-002, NAV-003, NAV-004 and NAV-017 Playwright suites give the same results as before), AC 34 (attribution visible on B1, B2 and B3; no string or glossary change).
- **Watch:** e2e configs that spread the base config (`{ ...base }`) and build with another `VITE_GATEWAY_BASE_URL` get a matching `connect-src` automatically. A suite that builds B1 with an absolute gateway now fails by design, because of the fail-closed rule. Report any such suite to QA instead of weakening the rule.

**MW5. `web/README.md` section "Security headers (Hostinger web root)" (ADR-0018 §4).**
- Copy-paste block: `<IfModule mod_headers.c>` with `Header always set` for the five headers. `Content-Security-Policy` carries `frame-ancestors 'none'` only. HSTS uses `env=HTTPS`, and its value is marked as the PO's choice (BA recommendation (b): staged 300 then 31536000, no `includeSubDomains`, no `preload`).
- Plus the notes AC 20 requires and the checks with expected output from AC 21. Optional `AddType application/wasm .wasm`. Placeholders only, and a plain statement that the block was not run against the real host.
- Checks: AC 19–21, 33 (grep of the changed files for host names and IPs).

## Mobile task list: Android (AN-2 = WS-4, WS-8)

**MA1. Verification metadata (ADR-0018 §5).**
- Generate from a fresh `GRADLE_USER_HOME` with `--write-verification-metadata sha256 help :app:assembleDebug :app:assembleRelease :app:assembleDemo :app:testDebugUnitTest :app:lintDebug -Pnav.hostFerrostar=required`, plus the properties the release and demo builds already need. Do not use `--dry-run`.
- Set `verify-signatures=false`.
- Add one trust rule for `-sources.jar` and `-javadoc.jar`, with an XML comment and the same reason in the README. Nothing else is trusted.
- Spot-check the sha256 of the MapLibre, Ferrostar and valhalla-mobile artifacts against the checksum the publisher's repository serves.
- Land after NAV-019's current Gradle changes, or regenerate after both (R5).
- Checks: AC 22, 23, 24 (grep for `lenient` and `off`), 25 (fresh cache: `:app:assembleRelease` and `:app:testDebugUnitTest -Pnav.hostFerrostar=required` pass with the same test count, plus the README §3 check and `:app:assembleDemo`, including the notice, licence and API-level gates), 26 (scratch: one changed sha256 fails, and one unlisted dependency fails; neither committed).

**MA2. Release lock (ADR-0018 §6).**
- In `app/build.gradle.kts`: `dependencyLocking { lockMode.set(LockMode.STRICT) }`, and `activateDependencyLocking()` on `releaseRuntimeClasspath` only.
- Write the lock with `./gradlew :app:dependencies --configuration releaseRuntimeClasspath --write-locks` and commit `app/gradle.lockfile`.
- Checks: AC 27 (the `:app:dependencies --configuration releaseRuntimeClasspath` output is identical before and after), AC 28 (a scratch transitive bump fails `:app:assembleRelease` with the lock-state error).

**MA3. Wrapper checksum (WS-8).**
- No regeneration is needed. On 2026-10-08 the architect compared both values with Gradle's published files (ADR-0018 F10), and both match:
  - JAR `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172` equals `https://services.gradle.org/distributions/gradle-8.14.3-wrapper.jar.sha256`;
  - distribution `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531` equals `gradle-8.14.3-bin.zip.sha256`.
- Re-run the comparison yourself. In the README, record the expected checksum, the source URL, the date, and the command to repeat at every Gradle upgrade. `distributionSha256Sum` and `validateDistributionUrl=true` stay as they are.
- Checks: AC 29.

**MA4. `mobile/android/README.md` sections.**
- "Updating dependencies (verification metadata and lockfile)" gets the seven steps of AC 30. The same section covers the merge rule (regenerate, never hand-merge), the sources/javadoc trust reason, and the aapt2 OS-classifier gap (ADR-0018 F12).
- "Where the native libraries come from" gets one row per `.so` from `unzip -l` of the release APK, with the columns AC 31 lists, plus a separate "test only, not shipped" list. Recommended: a small set-equality check script under `mobile/android/tools/`, run with the README §3 checks.
- A known-gaps list (AC 32).
- Checks: AC 30–33. The `.so` names in the table equal the names under `lib/` in the APK.

## QA (light, story Context › Process)
- New spec under `tests/e2e/` (for example `sec4b/`). It builds B1, B2 and B3 into temporary directories and serves each with `vite preview`, following the NAV-004 static-build pattern. It runs the AC 17 scenarios with a `securitypolicyviolation` listener and a console listener, and the AC 18 negative control.
- For B2 «Эхлэх», the spec must show 0 violations. This exercises the `media-src data:` chime (ADR-0018 F6) and the WASM compile.
- The two Gradle commands of AC 25.

## Dependencies and order
1. MW1 → MW2 → MW3 → MW4 → MW5 (web), in parallel with MA1 → MA2 → MA3 → MA4 (Android). MA1 waits for the NAV-019 Gradle changes (R5).
2. QA runs after both tracks finish. The security-engineer review runs in parallel with QA.
3. **PO action after merge:** paste the README block into `<web root>/.htaccess` (with the HSTS choice), run the AC 21 checks, and re-run iPhone checklist item 11.

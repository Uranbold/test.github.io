---
id: SEC-4B
title: "Client security hardening: build-time CSP and Referrer-Policy for the three web builds, documented Hostinger response headers, no test fixture or developer comment in production HTML; Gradle dependency verification, release lockfile, wrapper checksum and native-library provenance for Android"
phase: 1
priority: must          # P1 / cos:intangible, tech debt, PO-confirmed 2026-10-08 ("Go"; triage log 2026-10-08, SEC-4 row). Release gate: medium findings are fixed before the first public release (D215)
size: M                 # CSP generator + build check for 3 builds, cleanup, README header section; Gradle verification + locking + wrapper check + README sections; one Playwright CSP spec
needs_design: false     # no screen, no user-facing string changes
needs_backend: false    # web hosting headers are documented for the PO, not built by backend; gateway headers are SEC-4A / BE-13
needs_mobile: true      # web/ (MapLibre GL JS builds) and mobile/android (Gradle)
status: ready           # 2026-10-08. One non-blocking open question (HSTS value, needed only when the PO edits .htaccess)
---

# SEC-4B: Client security hardening (web CSP and headers, Android dependency verification)

## Story
As a **tourist (English UI)** who opens the public web demo link on my phone, I want **the page to run only the code the team shipped**, so that **an injected script or a framing site cannot read my location or trick me while I use the map**.

As a **UB commuter by car** who installs the Android app, I want **the app to be built only from library files the team has checked**, so that **a tampered library on a download mirror cannot end up in the app that knows where I drive**.

Secondary personas:
- **Pedestrian / taxi driver / countryside driver:** same benefit; no visible change in any screen.
- **PO (operator, not an end-user persona):** a copy-paste header block and checks for the Hostinger web root, and a documented procedure for dependency updates.

## Context
- **Source.** Whole-project audit register [`docs/security/audits/2026-10-07-full-project.md`](../../security/audits/2026-10-07-full-project.md); PO answer "All as recommended" of 2026-10-07 ([D215](../decisions.md): one hardening batch fixes all remaining medium findings before the first public release); triage log 2026-10-08 (SEC-4 row: split into SEC-4A backend/infra, **SEC-4B clients**, SEC-4C process/docs; P1 / intangible, confirmed by the PO with "Go" on 2026-10-08).
- **Findings in scope (register IDs):**

  | ID | Severity | Finding | Covered by |
  |---|---|---|---|
  | WS-1 | medium | No Content-Security-Policy or other security headers on the public web demo / demo mode | AC 1–12, 17–21 |
  | AN-2 (= WS-4) | medium | No Gradle dependency verification or lockfile; native libraries come from single-maintainer publishers | AC 22–28, 30 |
  | WS-8 | low | Gradle wrapper JAR not validated against Gradle's published checksum | AC 29 (in scope because the PO-confirmed item names it) |
  | WS-13 | info | Test fixture page shipped in the public builds | AC 13–14 |
  | WS-16 | info | Production HTML keeps the developer comment | AC 15 |

- **The three web builds** (`web/package.json`), named in this story as:
  - **B1 static demo:** `npm run build:static-demo` → `dist-static-demo/`, served from the Hostinger web root (D44, NAV-002 AC 51–54). Map only, 0 requests to any host but the page origin.
  - **B2 demo mode:** `npm run build:demo-mode` → `dist-demo-mode/`, served from a public sub-folder without a password (D107, D116, NAV-017). Includes the Ferrostar 0.57.0 WASM core.
  - **B3 normal:** `npm run build` → `dist/`, local dev / preview against the gateway (`VITE_GATEWAY_BASE_URL`).
- **Why a meta tag and not only headers.** The PO's shared web hosting (D44) is configured by hand through hPanel, and the B2 build must never contain an `.htaccess` (NAV-017 AC 3, R12). A build-time `<meta http-equiv="Content-Security-Policy">` travels with the files, so every upload is protected even before the PO edits the server. Directives that browsers ignore in a meta tag (`frame-ancestors`, `report-uri`, `sandbox`) go into the documented response headers instead.
- **Existing behaviour that must not change:** NAV-002 AC 37 (the inline boot script reveals the loading pill 270 ms after navigation start), NAV-002 AC 46 / AC 53 and NAV-017 AC 42 (hosts contacted), NAV-017 AC 3 (no server configuration file, `noindex`), NAV-005 AC 70 (exact versions, Google Maven + Maven Central only), NAV-005 AC 96–100 (licence notices and gates), NAV-019 demo APK build.
- **Process.** Light QA at the PO's request (as in D155 / D210): QA runs the CSP-violation test (AC 17–18) and the two Gradle commands (AC 25). The mobile engineer verifies every other AC and reports it in the handoff. The security-engineer reviews in the Verify stage and updates the register status.
- **No hostnames.** No real host name or IP goes into the repo (D35, CLAUDE.md rule 9). Placeholders `<demo-host>` and `<demo-folder>` as in `web/README.md`.

## Acceptance criteria

### A. Content-Security-Policy meta tag (web, WS-1)
1. **Given** each of B1, B2 and B3 is built, **When** every `.html` file in the output is inspected, **Then** its `<head>` contains **exactly one** `<meta http-equiv="Content-Security-Policy" content="…">`, placed directly after `<meta charset="utf-8">` and **before** every `<style>`, `<link>` and `<script>` element (an inline element placed before the policy would not be covered by it).
2. **Given** the policy of any of the three builds, **When** its `script-src` directive is read, **Then** it contains `'self'` and one `'sha256-…'` source per inline script, and it contains **none** of: `'unsafe-inline'`, `'unsafe-eval'`, `'unsafe-hashes'`, `*`, `data:`, `blob:`, `http:`, `https:`, or any host name.
3. **Given** the policies of the three builds, **When** they are compared, **Then** `'wasm-unsafe-eval'` appears in `script-src` of **B2 only**, and only if `dist-demo-mode/` contains at least one `.wasm` file. B1 and B3 contain **0** `.wasm` files (NAV-017: the Ferrostar WASM is not in those outputs) and **0** occurrences of `'wasm-unsafe-eval'`.
4. **Given** the policy of any build, **When** its `style-src` directive is read, **Then** it contains `'self'` and one `'sha256-…'` source per inline `<style>` element (for example the `design-tokens` style from `vite.config.ts`), and **no** `'unsafe-inline'`.
5. **Given** the policy of any build, **When** the remaining directives are read, **Then**:
   - `default-src` is `'self'` (or `'none'` with every needed directive listed);
   - `object-src 'none'` and `base-uri 'none'` (or `'self'`) are present;
   - `form-action 'none'` is present (the pages have no form that submits);
   - `connect-src` is `'self'` in B1 and B2 (0 other hosts, NAV-002 AC 46 / AC 53, NAV-017 AC 42). In B3 it is `'self'` plus **exactly** the origin of `VITE_GATEWAY_BASE_URL` when that value is an absolute URL, read from the build environment (never written into a committed file);
   - `img-src` may add only `data:` and `blob:`; `worker-src` may add only `blob:` (if MapLibre GL JS needs a blob worker); no directive contains `*` or a host name.
6. **Given** the meta policy of any build, **When** it is read, **Then** it contains **none** of `frame-ancestors`, `report-uri`, `report-to` or `sandbox` (ignored in a meta tag; `frame-ancestors` is set as a response header, AC 19).
7. **Given** a build, **When** its HTML is generated, **Then** the hashes are **computed at build time from the final bytes** of each inline `<script>` and `<style>` element (after minification and every `transformIndexHtml` step). Nobody maintains a hash by hand.
8. **Given** a build whose output HTML has an inline `<script>` or `<style>` whose hash is not in the policy (for example a plugin added one after the hash step), **When** the build runs, **Then** it **fails** with a message naming the file and the element. A unit test in `web/` proves this with a fixture.
9. **Given** the output HTML of any build, **When** it is searched, **Then** it contains **0** inline event-handler attributes (`on…=`), **0** `javascript:` URLs and **0** `style="…"` attributes.
10. **Given** a change to `src/boot/bootLoading.ts` or to the design tokens, **When** the builds are rebuilt, **Then** the new hashes are in the new policies without any other edit, and AC 17 still reports 0 violations.

### B. Referrer-Policy meta tag (web, WS-1)
11. **Given** each of B1, B2 and B3, **When** `index.html` is inspected, **Then** `<head>` contains exactly one `<meta name="referrer" content="same-origin">`. (Reason: links such as the OSM copyright link open other sites; `same-origin` sends no page path, so the demo folder name in B2 is never sent to another host, and same-origin tile and asset requests keep their `Referer` in case the host's hotlink protection is on.)
12. **Given** the B2 output, **When** it is inspected after this story, **Then** NAV-017 AC 3 still holds: no `.htaccess`, `.htpasswd` or other server configuration file, and `index.html` still contains `<meta name="robots" content="noindex, nofollow">`.

### C. No test fixture and no developer comment in production output (WS-13, WS-16)
13. **Given** each of B1, B2 and B3, **When** the output file list is inspected, **Then** **0** paths contain `fixtures/` or `label-rule` (the NAV-002 AC 8 fixture page `fixtures/label-rule.html` and its script are not built into any production output).
14. **Given** `npm run dev`, **When** `http://localhost:5173/fixtures/label-rule.html` is opened, **Then** the fixture page still works (`window.__labelRule.ready` resolves) and the NAV-002 test TC-08-01 (`tests/e2e/nav002/basemap.test.mjs`) still passes.
15. **Given** each of B1, B2 and B3, **When** every `.html` file in the output is searched, **Then** it contains **0** occurrences of `<!--` (the developer comments in `web/index.html` lines 2–12 and 85–86 are stripped at build time; the source file may keep them). Licence comments inside JavaScript or CSS files are **not** removed by this step.
16. **Given** the three builds after this story, **When** the existing web checks run (`npm run typecheck`, `npm run lint`, `npm test`, `npm run check:glossary`, and the NAV-002, NAV-003, NAV-004 and NAV-017 Playwright suites), **Then** all pass with the same results as before this story. (Mobile engineer verifies; not part of the light QA run.)

### D. Zero CSP violations in the browser (QA, light)
17. **Given** each build served by `vite preview` (which adds no security response headers, so only the meta policy applies), a Playwright Chromium page with no browser extensions, and listeners on the `securitypolicyviolation` event and on console messages, **When** the scenario for that build runs, **Then** **0** `securitypolicyviolation` events are recorded and **0** console messages contain "Content Security Policy" or "Content-Security-Policy". Scenarios:
    - **B1:** load until the map is `idle` with tiles drawn and the loading pill hidden; switch language mn → en → mn; switch day → night → day (sprite reload); zoom in and out; «Миний байршил» with geolocation granted and mocked (a UB point); type «Сүхбаатар» and then "Sukhbaatar" in search (both show «Хайлт түр ажиллахгүй байна»); click the map to open the coordinate card; «Маршрут гаргах» (shows «Маршрутын үйлчилгээ түр ажиллахгүй байна»); go offline with `context.setOffline(true)` (shows «Интернэт холболт алга»).
    - **B2:** load until the picker shows R1–R3; pick R1; wait until «Эхлэх» is enabled (Ferrostar WASM compiled and the plan ready); tap «Эхлэх»; run at least **30 s** of replay (real time or `page.clock`) including at least one banner change and one voice prompt or chime; toggle voice mute; open the voice diagnostics (5 quick taps on the «Туршилтын горим» badge), run "Test chime", close it; tap «Дуусгах».
    - **B3:** with the gateway answers mocked by Playwright request interception at the build's configured gateway origin: search «Сүхбаатар» and "Sukhbaatar" (results list with icons), open a place card, «Маршрут гаргах» with «Машин» (route drawn, turn list shown).
18. **Given** any of the three loaded builds, **When** the test injects an inline script without a hash (for example `page.addScriptTag({ content: "window.__cspProbe = 1" })`), **Then** the script does **not** run (`window.__cspProbe` is `undefined`) and **exactly one** `securitypolicyviolation` event with `violatedDirective` `script-src` or `script-src-elem` is recorded. This negative control proves the policy is enforced, so AC 17's zero is meaningful.

### E. Response headers for the Hostinger web root, documented (WS-1)
19. **Given** `web/README.md`, **When** the PO reads the new section "Security headers (Hostinger web root)", **Then** it gives a copy-paste block for `<web root>/.htaccess` that sets exactly these response headers, with placeholders only:
    - `X-Content-Type-Options: nosniff`
    - `Referrer-Policy: same-origin`
    - `Content-Security-Policy: frame-ancestors 'none'` (this header contains **only** `frame-ancestors`)
    - `Permissions-Policy: geolocation=(self)`
    - `Strict-Transport-Security: max-age=<value>`, sent on HTTPS responses only, with the value and `includeSubDomains` choice from Open question 1 (until the PO answers, the README shows the BA recommendation and marks it as the PO's choice).
20. **Given** the same README section, **When** it is read, **Then** it states:
    - the block is **merged** into the existing web-root `.htaccess` (for example next to the host's "force HTTPS" rules), never replacing it;
    - the web-root headers also apply to `<demo-folder>/` by inheritance, so the demo folder still gets **no** `.htaccess` of its own (NAV-017 AC 3);
    - the meta policy and the header policy are **both** enforced by the browser, which is why the header carries only `frame-ancestors` and all script, style and connect rules stay in the per-build meta tag;
    - HSTS is cached by visitors' browsers for `max-age` seconds and cannot be withdrawn sooner; removing the block does not undo it for visitors who already saw it;
    - how to undo the other headers (remove the block) if the site breaks.
21. **Given** the same README section, **When** the PO follows the checks after editing `.htaccess`, **Then** the README lists commands whose expected output is stated:
    - `curl -sI https://<demo-host>/` and `curl -sI https://<demo-host>/<demo-folder>/` each show all five headers of AC 19;
    - `curl -sI -H "Range: bytes=0-16383" https://<demo-host>/tiles/basemap.pmtiles` still answers **206** with `Content-Range` and **no** `Content-Encoding` (NAV-002 AC 51–52);
    - the `.js` files answer with a JavaScript `Content-Type` and the B2 `.wasm` file with `application/wasm` (with `nosniff`, a wrong type blocks the script);
    - opening `https://<demo-host>/` and `https://<demo-host>/<demo-folder>/` in a desktop browser shows the map / picker and **0** CSP messages in the console.
    The README states plainly that these checks were **not** run against the real host by the team (no real host is reachable or named in the repo).

### F. Android dependency verification and locking (AN-2 = WS-4)
22. **Given** `mobile/android/gradle/verification-metadata.xml`, **When** it is inspected, **Then** it has `verify-metadata` `true`, `verify-signatures` `false`, and a **`sha256`** checksum for every artifact (jar, aar, pom, Gradle module file, plugin marker) that Gradle resolves for `:app:assembleDebug`, `:app:assembleRelease`, `:app:assembleDemo`, `:app:testDebugUnitTest`, `:app:lintDebug` and the build's plugin classpath. It is generated with `./gradlew --write-verification-metadata sha256 <those tasks>` from the dependencies resolved **today**, with no version change (AC 27).
23. **Given** the metadata file, **When** it is read, **Then** it contains **no** `<trusted-artifacts>` / `<trust>` entry, unless each one has an XML comment giving the reason and the same reason is listed in `mobile/android/README.md`.
24. **Given** the repository, **When** `gradle.properties`, the build scripts and the README commands are searched, **Then** none sets `org.gradle.dependency.verification` to `lenient` or `off`, and no README command passes `--dependency-verification=lenient|off` (verification is strict by default).
25. **Given** a Gradle user home with an empty cache (fresh `GRADLE_USER_HOME`, or `--refresh-dependencies`), **When** `./gradlew :app:assembleRelease` and `./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required` run in `mobile/android`, **Then** both succeed with verification on, and the unit-test count and results equal the run before this story. (**QA light check.**) The mobile engineer also runs the README §3 check `:app:testDebugUnitTest -Pnav.hostFerrostar=required :app:lintDebug :app:assembleDebug` and `:app:assembleDemo` (NAV-019), and both pass, including `checkThirdPartyNotices`, `checkReleaseLicenceGate` and `checkApkApiLevelTypes<Variant>`.
26. **Given** a scratch copy of the build where (a) one `sha256` value in the metadata is changed by one character, or (b) a dependency that is not in the metadata is added, **When** `./gradlew :app:assembleDebug` runs, **Then** the build **fails** with Gradle's dependency-verification error naming that artifact, in both cases. (Mobile engineer verifies and reports in the handoff; the scratch change is never committed.)
27. **Given** dependency locking for the **release runtime classpath** (`releaseRuntimeClasspath`), **When** the lock state is inspected, **Then** `mobile/android/app/gradle.lockfile` is committed and lists every module of that classpath at the version resolved before this story (the `:app:dependencies --configuration releaseRuntimeClasspath` output before and after this story is identical).
28. **Given** the lockfile, **When** a transitive version of `releaseRuntimeClasspath` would change without a lock update (for example a direct dependency bump that pulls a newer transitive module), **Then** `./gradlew :app:assembleRelease` **fails** with Gradle's lock-state error until the lock is regenerated on purpose (AC 30). (Mobile engineer verifies with a scratch change.)

### G. Gradle wrapper checksum (WS-8)
29. **Given** `mobile/android/gradle/wrapper/gradle-wrapper.jar`, **When** its sha256 is compared with Gradle's **published** checksum for the wrapper JAR of the Gradle version that produced it (8.14.3 expected, matching `distributionUrl`), **Then** they are equal, and the README records the expected checksum, the source it was taken from and the date. If they differ, the wrapper is regenerated with `./gradlew wrapper --gradle-version 8.14.3 --gradle-distribution-sha256-sum <the value already in gradle-wrapper.properties>` and compared again. If the published checksum cannot be obtained from the build machine, the handoff says so under "not verified", the README gives the exact command for the PO or CI to run, and the existing `distributionSha256Sum` and `validateDistributionUrl=true` stay unchanged in either case.

### H. Documentation in `mobile/android/README.md` (AN-2)
30. **Given** the README, **When** a developer bumps a dependency, **Then** a section "Updating dependencies (verification metadata and lockfile)" tells them, in order:
    1. change the exact version in `gradle/libs.versions.toml` (no `+` or ranges, NAV-005 AC 70);
    2. regenerate the metadata with the same task list as AC 22 and update the lock with `--write-locks` (or `--update-locks <group:module>`);
    3. review the diff: only the expected artifacts are added or changed; for each new artifact compare the sha256 with the checksum the publisher's repository serves next to the file (`.sha256` or `.sha1` on Google Maven / Maven Central) or with the release page;
    4. re-run the licence notices and native notice lists as already documented (README §3);
    5. commit the version, metadata, lockfile and notices **in one commit**;
    6. **never** regenerate the metadata only to make a failing build pass: a verification failure without a planned version change is treated as a possible supply-chain incident and reported to the security-engineer;
    7. Dependabot pull requests (once enabled by the PO, SP-3) do not update the metadata, so they fail until a developer regenerates it on that branch following steps 2–5.
31. **Given** the README, **When** a reviewer reads the new section "Where the native libraries come from", **Then** it has one row per `.so` file shipped in the release APK, with: file name; ABIs; the Maven coordinate and version that ships it; the publisher; the upstream source repository URL; who builds the binary (the publisher's CI, not this team); and a note where the publisher is a single maintainer or a small team (AN-2). At least these rows exist: MapLibre Native (`org.maplibre.gl:android-sdk`), Ferrostar (`com.stadiamaps.ferrostar:core`), valhalla-mobile (`io.github.rallista:valhalla-mobile`), and every other `.so` found (for example `androidx.sqlite:sqlite-bundled`, JNA, the C++ runtime). **Check:** the set of file names under `lib/` in `unzip -l` of the release APK equals the set of file names in the table. Host-only native libraries used only by JVM tests (the host `libferrostar.so` built from the crates.io crate, the `sqlite-bundled-jvm` natives) are listed in a separate "test only, not shipped" list.
32. **Given** the README, **When** it is read, **Then** it names what dependency verification does **not** cover, as known gaps: Robolectric's `android-all` download at test time (fetched by Robolectric, not Gradle), the crates that `tools/build-host-ferrostar.sh` compiles for the host (WS-17), and the fact that checksums pin the files resolved on 2026-10-08 but do not prove the publishers' builds were trustworthy (trust on first use; PGP signature verification is out of scope).

### I. General
33. **Given** every file changed by this story, **When** it is searched for host names and IP addresses, **Then** it contains none other than `localhost`, `127.0.0.1`, the placeholders `<demo-host>` / `<demo-folder>` / `<host>`, and public upstream package, source or documentation hosts (for example `openstreetmap.org`, `services.gradle.org`, `maven.google.com`, `repo.maven.apache.org`, `github.com`).
34. **Given** this story, **When** the app and the web builds are used, **Then** no user-facing string, screen or behaviour changes (no glossary term is added or changed), and "© OpenStreetMap contributors" is still visible on every map screen of B1, B2 and B3 (CLAUDE.md rule 8).

## Edge cases
- **Header inheritance into the demo folder.** Apache-style `.htaccess` headers set in the web root apply to sub-folders, so B2 gets the same headers as B1. That is why the header policy carries only `frame-ancestors`: a second, stricter `script-src` in a header would intersect with B2's meta policy and could block the WASM (AC 19–20).
- **WASM on iPhone Safari (NAV-017, D72).** `'wasm-unsafe-eval'` support on the current iOS major version is assumed, not verified here. The PO's real-iPhone checklist (`web/README.md`, item 11 "Complete R1") is re-run once after this story ships; a failure comes back as a defect in this story's fix loop.
- **`nosniff` and wrong MIME types.** If the host serves `.js` or `.wasm` with a wrong `Content-Type`, `nosniff` (and WASM streaming compile) blocks it. AC 21 makes the PO check the types.
- **HSTS is sticky.** A wrong `max-age` or `includeSubDomains` affects visitors for up to `max-age` seconds and, with `includeSubDomains`, every subdomain of the company domain (D6). Hence Open question 1 and a staged value.
- **Browser extensions** inject scripts and styles and cause CSP messages in real users' consoles. Those are not defects; AC 17 runs with no extensions.
- **No network / offline.** The policy is inside the HTML and needs no request; offline states («Интернэт холболт алга») must show with 0 violations (AC 17, B1).
- **Cyrillic and Latin search.** Result rows insert icon SVG through `innerHTML`; B3 runs both «Сүхбаатар» and "Sukhbaatar" so the results path is covered (AC 17).
- **GPS loss / my location.** `Permissions-Policy: geolocation=(self)` keeps «Миний байршил» working on the page origin; B2 never calls the Geolocation API (NAV-017), so it is unaffected.
- **`vite dev`.** The dev server injects inline scripts for hot reload; the policy applies to builds only, not to `npm run dev` (out of scope).
- **Normal build with a different gateway.** B3's `connect-src` follows `VITE_GATEWAY_BASE_URL` at build time; a relative value (`/gw`) or `same-origin` gives `'self'` only. A gateway moved after the build needs a rebuild (already true today for absolute URLs).
- **Mirror differences.** Gradle resolves from Google's mirror of Maven Central first (ADR-0009 F12). If the mirror serves a different file than Maven Central, verification fails, which is the intended result.
- **Parallel stories that add dependencies** (NAV-019 in progress; NAV-021, NAV-022, NAV-023 in the offline epic) conflict on `verification-metadata.xml` and `gradle.lockfile`. The rule is: regenerate after the merge, never merge the XML by hand.
- **Gradle upgrade.** A new Gradle version changes the wrapper JAR and the distribution checksum; AC 29 is repeated at each upgrade (README).
- **Release signing.** `assembleRelease` stays unsigned until SEC-3 (D214); verification and locking do not depend on signing.

## Data dependencies & risks
- **No OSM data dependency.** The story changes build output and documentation only.
- **R1, PO action needed for the headers.** Headers only take effect once the PO pastes the block into the Hostinger web-root `.htaccess` and runs the AC 21 checks. Until then the meta tags are the only protection, and `frame-ancestors`, `nosniff` and HSTS are missing. Tracked under backlog › owed decision 8 (PO actions).
- **R2, host capability not verified.** That the PO's shared hosting honours `Header` directives in `.htaccess` is assumed from the existing hPanel usage, not verified. If it does not, the README says so and the finding stays partly open (meta tags only).
- **R3, trust on first use.** The checksums are taken from the artifacts resolved on 2026-10-08. If one of them was already tampered with, verification pins the tampered file. The single-maintainer risk of AN-2 is documented (AC 31), not removed.
- **R4, maintenance cost.** Every dependency bump now needs a metadata and lockfile update in the same commit (AC 30). Dependabot pull requests will fail until a developer regenerates them.
- **R5, mid-flight overlap.** NAV-019 (in progress) shares `mobile/android` Gradle files. Default per CLAUDE.md §5: no change to NAV-019's scope; SEC-4B's Gradle commit lands after the current NAV-019 Gradle changes, or the metadata is regenerated after both.

## Out of scope
- Gateway and edge security headers on the VPS (BE-13, part of SEC-4A or the low backlog) and the static-pack `.htaccess` (WS-12, duplicate of BE-13).
- CI wrapper validation or supply-chain checks in GitHub Actions (WS-6, WS-7; orchestrator).
- PGP signature verification of Gradle dependencies; locking of debug, demo and test classpaths (verification metadata still covers them).
- Trusted Types, Subresource Integrity (all assets are same-origin), CSP reporting endpoints.
- R8 / minification (AN-3), WS-5 (`source-map-js`), WS-14 (glyph/sprite checksums), WS-17 (host-only native builds), iOS (no app yet).
- Any change to the content or behaviour of the pages or the app.

## Open questions
1. **HSTS value for the PO's domain** (non-blocking; needed only when the PO edits `.htaccess`, AC 19). Options: (a) `max-age=31536000` straight away, no `includeSubDomains`, no `preload`; (b) **staged:** `max-age=300` for one week of normal use, then `max-age=31536000`, no `includeSubDomains`, no `preload`; (c) as (a) or (b) with `includeSubDomains`. **BA recommendation: (b).** A mistake costs minutes instead of a year, and `includeSubDomains` would force HTTPS on every subdomain of the company domain (D6), which this team does not control.

## Traceability
| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC 1–10 | n/a | n/a | `web/vite.config.ts`, `web/buildtools/**` (CSP plugin and build check), `web/index.html` | `web/src/**` build test (mobile); AC 17–18 spec (QA) | WS-1 |
| AC 11–12 | n/a | n/a | `web/vite.config.ts` / `web/buildtools/demoMode.ts` | build test (mobile) | WS-1; NAV-017 AC 3 |
| AC 13–15 | n/a | n/a | `web/vite.config.ts` (`rollupOptions.input`), HTML comment strip | build test (mobile); `tests/e2e/nav002/basemap.test.mjs` TC-08-01 | WS-13, WS-16 |
| AC 16 | n/a | n/a | — | existing web suites | — |
| AC 17–18 | n/a | n/a | — | new Playwright CSP spec under `tests/e2e/` (QA-owned path) | WS-1 |
| AC 19–21 | n/a | n/a | `web/README.md` | review (security-engineer); PO runs AC 21 on the real host | WS-1 |
| AC 22–28 | n/a | n/a | `mobile/android/gradle/verification-metadata.xml`, `mobile/android/app/gradle.lockfile`, `mobile/android/app/build.gradle.kts` (locking) | Gradle commands (QA light: AC 25) | AN-2 = WS-4 |
| AC 29 | n/a | n/a | `mobile/android/gradle/wrapper/*`, `mobile/android/README.md` | checksum comparison (mobile) | WS-8 |
| AC 30–32 | n/a | n/a | `mobile/android/README.md` | review; `unzip -l` comparison (AC 31) | AN-2 |
| AC 33–34 | n/a | n/a | all changed files | grep (mobile, security-engineer) | D35 |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-08 | triage log 2026-10-08 (SEC-4 row); PO "Go" 2026-10-08 | Created: 34 AC in sections A–I, one non-blocking open question (HSTS value) | Client part (B) of the SEC-4 split; D215 release gate for WS-1 and AN-2; WS-8, WS-13 and WS-16 added by the PO-confirmed scope |

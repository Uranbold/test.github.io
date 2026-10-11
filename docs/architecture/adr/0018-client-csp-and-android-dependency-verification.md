# ADR-0018: Client security hardening. A per-build Content-Security-Policy meta tag with build-time hashes, plus a header policy that carries only `frame-ancestors`; strict Gradle dependency verification (sha256, trust on first use) and a strict lock on the Android release runtime classpath

- **Status:** accepted (architect, 2026-10-08). The decisions are technical and implement a PO-approved story: SEC-4B, P1 / intangible, "Go" on 2026-10-08, D215 release gate. One PO choice is still open and does not block the build work: the HSTS value (story Open question 1, §4 here).
- **Date:** 2026-10-08
- **Stories:** SEC-4B (audit register `docs/security/audits/2026-10-07-full-project.md`: WS-1, AN-2 = WS-4, WS-8, WS-13, WS-16). Task breakdown: [`tasks/SEC-4B-client-security-hardening.md`](../tasks/SEC-4B-client-security-hardening.md). Touches ADR-0004 (web builds), ADR-0009 §11 (Android dependencies) and ADR-0011 (demo mode: WASM, chime, sub-folder).
- **Contract:** no change. `openapi.yaml` stays at 0.6.1. This story changes build output and documentation only. It adds no endpoint, field or status code.

## Context
The public web builds have no Content-Security-Policy. The Android build accepts any file a repository serves for a pinned version. Both are medium findings that must be fixed before the first public release (D215).

Constraints:
- **The hosting is configured by hand.** The B1 static demo and the B2 demo-mode folder run on the PO's shared web hosting, which is edited through hPanel (D44). B2 must never ship an `.htaccess` (NAV-017 AC 3). So the policy that matters (script, style, connect) has to travel inside the HTML files.
- **Meta-tag limits.** Browsers ignore `frame-ancestors`, `report-uri`, `report-to` and `sandbox` in a meta policy. A meta policy also covers only the elements that come after it in the document.
- **Two policies intersect.** When a page has both a meta policy and a header policy, the browser enforces both. The web-root headers are inherited by `<demo-folder>/`. A header `script-src` without `'wasm-unsafe-eval'` would therefore block the B2 WASM.
- **Android publishers.** The native libraries come from small publishers: valhalla-mobile (one maintainer), Ferrostar (Stadia Maps) and MapLibre. Gradle resolves Maven Central through Google's mirror first (ADR-0009 F12).

**Facts measured in the code on 2026-10-08** (these are what the policy is built from):

| # | Fact | Source | Consequence |
|---|---|---|---|
| F1 | B1 and B3 each have two inline elements: `<style id="design-tokens">` (Vite tag, `head-prepend`) and the boot `<script>` (`body`). B2 adds a third, the trailing-slash guard `<script>`, placed `head-prepend` **before** `<meta charset>` | `web/vite.config.ts` `bootIndicator`, `web/buildtools/demoMode.ts` `demoHtml` (`order: "post"`), `web/dist-demo-mode/index.html` | The plugin must move `<meta charset>` and the policy to the top of `<head>` and hash every inline element. It must run after `demoHtml` |
| F2 | `<meta charset>` now sits after a ~1 KB developer comment and the ~3 KB tokens style, so it is beyond the first 1024 bytes that the HTML spec requires | built `index.html` of all three builds | Fixed as a side effect: the charset meta becomes the first child of `<head>` once the comments are stripped |
| F3 | The MapLibre worker is a same-origin file (`setWorkerUrl(…?worker&url)`). No blob worker is used | `web/src/main.ts:43` | `worker-src 'self'`, with no `blob:` |
| F4 | `maplibre-gl.css` uses `data:` SVG backgrounds, and MapLibre decodes images through `URL.createObjectURL` | `node_modules/maplibre-gl` | `img-src 'self' data: blob:` |
| F5 | B2 compiles Ferrostar with `WebAssembly.instantiate(bytes)`, not `instantiateStreaming` | `web/src/guidance/ferrostarCore.ts:25` | B2 needs `'wasm-unsafe-eval'`. A wrong `.wasm` `Content-Type` does **not** break compilation, because `nosniff` applies to script and style destinations, not to `fetch()` |
| F6 | When «Эхлэх» is pressed, B2 primes an `HTMLAudioElement` with a `data:audio/wav;base64,…` chime generated at runtime | `web/src/demo/audio.ts:351`, `:725` | B2 needs `media-src 'self' data:`. Without it, Chromium reports a violation on «Эхлэх». **Story AC 5 does not list this** (request to the BA, Consequences) |
| F7 | Icons are inserted with `innerHTML` as plain SVG. No `style="…"` attribute and no `on…=` handler appear anywhere in `web/src`. MapLibre sets styles through CSSOM (`el.style.x`), which CSP allows | grep of `web/src`; MapLibre source | `style-src` needs no `'unsafe-inline'`, and AC 9 holds today |
| F8 | No `<base>` element in any build. B2's relative paths come from Vite `base: "./"` | built `index.html` | `base-uri 'none'` is safe |
| F9 | The MapLibre worker bundle contains `globalThis.eval` for loading worker plugins (the RTL text plugin). This app never sets such a plugin. A same-origin worker gets its policy from its own response headers, not from the page's meta tag | `dist/assets/maplibre-gl-worker-*.js` | No effect on the page policy. Recorded so that nobody adds `'unsafe-eval'` because of it |
| F10 | `gradle-wrapper.jar` sha256 `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172` **equals** the published `https://services.gradle.org/distributions/gradle-8.14.3-wrapper.jar.sha256`. `distributionSha256Sum` `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531` **equals** `gradle-8.14.3-bin.zip.sha256` | fetched 2026-10-08 from this build machine | WS-8 needs no regeneration, only the README record (story AC 29) |
| F11 | One `gradle/verification-metadata.xml` covers the root build **and** `buildSrc`. `--write-verification-metadata` resolves every resolvable configuration but misses detached configurations and resolution at task execution time. By default, IDE `-sources.jar` / `-javadoc.jar` downloads are verified too | Gradle 8.14.3 user guide, "Verifying dependencies" | Generate with `help` **plus** the real task list. One documented trust rule for sources and javadoc (§5) |
| F12 | AGP resolves `com.android.tools.build:aapt2` with an OS classifier (`linux`, `osx`, `windows`) | AGP | Metadata generated on Linux pins only the Linux `aapt2`. A macOS or Windows build fails until its classifier is added (§5) |

## Decision

### 1. One generated policy per build, as the first element of `<head>`
One Vite plugin, `navmn-csp` (`web/buildtools/csp.ts`, `apply: "build"`), is listed **last** in `plugins`, so its `transformIndexHtml` (`order: "post"`) runs after `bootIndicator`, `demoHtml` and Vite's own tag injection. For every HTML entry it does the following:
1. **Strips HTML comments** outside `<script>` and `<style>` raw text (WS-16). The source `web/index.html` keeps them.
2. **Normalises the head order.** The first child becomes `<meta charset="utf-8">`, then the CSP meta, then `<meta name="referrer" content="same-origin">`, then everything else in its original order. This puts the B2 guard script after the policy, and the charset inside the first 1024 bytes (F1, F2).
3. **Hashes** the exact text of each inline `<script>` and `<style>` (`sha256`, base64), from the final string this hook returns.
4. **Writes the policy** from the matrix in §2.

**Independent verification** runs in `closeBundle`. It reads every `.html` file from the output directory on disk and parses it with `jsdom` (already a devDependency, MIT; a different parser from the one in the generator). It hashes each inline element's **parsed** `textContent`, which is what the browser hashes. The build **fails**, naming the file and the element, when any of these holds:
- an inline element's hash is not in the policy (AC 8);
- the policy is not the first element after `<meta charset>`, or there is more than one policy (AC 1);
- `script-src` contains a forbidden source (AC 2);
- `'wasm-unsafe-eval'` is present while the output has no `.wasm` file, or the reverse (AC 3);
- a meta-ignored directive is present (AC 6);
- an `on…=` attribute, a `javascript:` URL or a `style=` attribute is present (AC 9);
- the output contains a `<!--` (AC 15), or a path with `fixtures/` or `label-rule` (AC 13);
- the referrer meta is missing or duplicated (AC 11).

The generator and the verifier compute the hashes separately, so a whitespace or newline-normalisation bug in either one fails the build instead of shipping a broken page. The pure functions (`buildPolicy`, `verifyHtml`) are exported for a vitest fixture test. The vitest `include` gains `buildtools/**/*.test.ts`.

No hash is ever written by hand (AC 7, 10). `'unsafe-inline'` and nonces are not used: these are static files with no server to issue a nonce.

### 2. Policy matrix

| Directive | B1 static demo | B2 demo mode | B3 normal |
|---|---|---|---|
| `default-src` | `'self'` | `'self'` | `'self'` |
| `script-src` | `'self'` + boot hash | `'self'` `'wasm-unsafe-eval'` + boot hash + guard hash | `'self'` + boot hash |
| `style-src` | `'self'` + tokens hash | same | same |
| `img-src` | `'self' data: blob:` | same | same |
| `connect-src` | `'self'` | `'self'` | `'self'` + the gateway origin (below) |
| `worker-src` | `'self'` | `'self'` | `'self'` |
| `media-src` | (falls back to `default-src`) | `'self' data:` (F6) | (falls back) |
| `object-src` / `base-uri` / `form-action` | `'none'` | `'none'` | `'none'` |

Not used: `upgrade-insecure-requests` (it would break B3 against `http://localhost:8080`; HSTS covers the public host), `require-trusted-types-for`, and reporting (story Out of scope).

**B3 `connect-src`.** The value is derived from the same rule the runtime uses. `web/src/config.ts` exports one pure helper that maps `VITE_GATEWAY_BASE_URL` to either "page origin" or an absolute origin. The plugin calls that helper, so policy and runtime cannot drift.
- Unset or empty → the B3 default `http://localhost:8080`, so `connect-src` is `'self' http://localhost:8080`.
- `same-origin`, `/` or a relative path → `'self'` only.
- An absolute URL → `'self'` plus its origin.

The value is read from the build environment and never written to a committed file.

**B1 and B2 fail closed.** If their effective gateway is not the page origin, the build fails, in the same way as `staticDemoGuard`. NAV-002 AC 46 / AC 53 and NAV-017 AC 42 allow no other host.

### 3. Fixture page and developer comment
The `labelRule` input is removed from `rollupOptions.input` for **every** `vite build`. The demo-mode deletion in `demoModePlugins` is generalised into a build-only `configResolved` step, so `vite.config.ts` stays a plain object for `tests/e2e` (WS-13). The Vite dev server still serves `fixtures/label-rule.html` from the project root, so NAV-002 TC-08-01, which runs on the dev server, is unaffected (AC 14). Comments are stripped by §1 step 1.

### 4. Response headers for the Hostinger web root (documented in `web/README.md`, set by the PO)
`web/README.md` documents one block for `<web root>/.htaccess`. The PO merges it into the existing file. The block uses `Header always set` inside `<IfModule mod_headers.c>`. If the module is missing, the site keeps working, and the story's AC 21 `curl -sI` checks show that the headers are absent.

| Header | Value |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `same-origin` |
| `Content-Security-Policy` | `frame-ancestors 'none'`, **only** this directive (it intersects with the meta policy, so any other directive here could block the B2 WASM) |
| `Permissions-Policy` | `geolocation=(self)` |
| `Strict-Transport-Security` | `max-age=<PO choice>` on HTTPS responses only (`env=HTTPS`). No `includeSubDomains` and no `preload` unless the PO chooses them (story Open question 1; BA recommends the staged value 300 → 31536000) |

`AddType application/wasm .wasm` is documented as an optional line, needed only if the AC 21 check shows a wrong type. It is not required for compilation (F5).

The block is not verified on the real host (R2). That the shared hosting honours `Header` in `.htaccess` is an assumption.

### 5. Android: strict dependency verification, trust on first use
- **File.** `mobile/android/gradle/verification-metadata.xml`. It contains `verify-metadata=true` and `verify-signatures=false`, and **sha256** checksums only. It covers `buildSrc` too (F11). `buildSrc` has no dependencies today.
- **Generation**, once and from a clean cache, so the checksums come from today's downloads and not from an older local cache:
  ```
  GRADLE_USER_HOME=<fresh scratch dir> ./gradlew --write-verification-metadata sha256 \
    help :app:assembleDebug :app:assembleRelease :app:assembleDemo :app:testDebugUnitTest :app:lintDebug \
    -Pnav.hostFerrostar=required <the release/demo properties README §3/§7 already require>
  ```
  `help` adds every resolvable configuration. The task list adds resolution at execution time (lint, aapt2, the `sqliteHostNatives` configuration). Do **not** use `--dry-run`: it misses execution-time resolution (F11).
- **Spot-check before commit.** For the three native publishers (`org.maplibre.gl:android-sdk`, `com.stadiamaps.ferrostar:core`, `io.github.rallista:valhalla-mobile`) and their direct `.aar`s, compare the pinned sha256 with the checksum file the publisher's repository serves next to the artifact. Record the result in the handoff.
- **One trust rule**, which story AC 23 allows when the reason is given. A `<trust file=".*-(sources|javadoc)[.]jar" regex="true"/>` entry with an XML comment and the same reason in the README. The reason: Android Studio downloads sources and javadoc for display only, and they are never on a build, test or runtime classpath. Without the rule, IDE sync fails, and developers would learn to switch verification off. Nothing else is trusted.
- **aapt2 on other operating systems** (F12). Linux is the reference build host. The metadata pins Linux `aapt2` only. If a macOS or Windows build is needed, its `aapt2` entry is added in a reviewed commit using the README "Updating dependencies" step 3 (compare with Google Maven's served checksum). The build must never be made lenient for it. Until then the README lists this as a known gap.
- **Never lenient.** No committed `org.gradle.dependency.verification=lenient|off` and no README command with `--dependency-verification=lenient|off` (AC 24). A verification failure without a planned version change is a possible supply-chain incident and goes to the security-engineer (`docs/security/incident-response.md`).
- **Merge rule** (R5, parallel NAV-019 / NAV-021 to NAV-023 work). The XML and the lockfile are **regenerated after a merge, never merged by hand**. SEC-4B's Gradle commit lands after NAV-019's current Gradle changes.

### 6. Android: strict lock of `releaseRuntimeClasspath` only
In `app/build.gradle.kts`:
```kotlin
dependencyLocking { lockMode.set(LockMode.STRICT) }
configurations.matching { it.name == "releaseRuntimeClasspath" }
    .configureEach { resolutionStrategy.activateDependencyLocking() }
```
`configureEach` handles AGP creating the configuration lazily. `STRICT` fails the build when that configuration has no lock state, and any resolution outside the lock fails in every mode (AC 28). The lock is written with `./gradlew :app:dependencies --configuration releaseRuntimeClasspath --write-locks` to `app/gradle.lockfile`. Debug, demo, test and plugin classpaths are not locked (story Out of scope), but verification still pins them.

### 7. Native library provenance
`mobile/android/README.md` gets a table with one row per `lib/<abi>/*.so` in the release APK (story AC 31). The row set is derived from `unzip -l app-release.apk 'lib/*'`. It is **recommended**, not required, that the set-equality check is a small script under `mobile/android/tools/` run with the README §3 checks, so a later story that adds a `.so` (NAV-021 / NAV-022 already added some) cannot leave the table stale.

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| **Meta policy with build-time hashes; header carries only `frame-ancestors` (chosen)** | Travels with every upload; works before the PO edits the server; one source of truth per build; hashes cannot go stale (generated and independently verified) | Meta cannot carry `frame-ancestors` or reporting, so a header is still needed; policy covers only what follows it (handled by §1 step 2) |
| Full policy only as a response header in `.htaccess` | Standard; supports every directive | Depends on manual hPanel edits; the B2 folder must not have its own `.htaccess`, and an inherited web-root `script-src` cannot differ per build (B2 needs `'wasm-unsafe-eval'`, B1 must not have it); a hash change on every rebuild means editing the server each time |
| Move the inline boot script and tokens style into external files (no hashes needed) | Simplest policy (`'self'`) | Breaks NAV-002 AC 37: the pill must be styled and timed before the module graph loads; an extra render-blocking request on slow mobile networks |
| `'unsafe-inline'` for styles only | Less work | Violates story AC 4; allows CSS injection (data exfiltration via selectors) |
| Nonces | Standard for dynamic pages | Static files, no server to mint a nonce per response |
| Existing plugin (for example `vite-plugin-csp-guard`) | Less code | Another dependency in a security-critical step; may hash before our `order: "post"` hooks run; does not cover our checks (comments, fixture, wasm-iff-present, B1/B2 fail-closed). The plugin is ~150 lines |
| Gradle verification with PGP signatures | Proves publisher identity | Many artifacts are unsigned or have unpublished keys; key management cost; story Out of scope |
| Lock every configuration | Full reproducibility | High churn on every test-only bump; story scope is the release runtime classpath; verification already pins the rest |
| No trust rule for sources/javadoc | Strictest file | IDE sync fails for every developer, which encourages lenient mode locally; sources are never executed by the build |

## Consequences
- **Easier:** a tampered script, style or library either does not run (web) or fails the build (Android). The B2 upload stays self-contained with no server file. The `<meta charset>` is back inside the first 1024 bytes.
- **Harder:** every dependency bump needs a metadata and lockfile update in the same commit (story R4). Dependabot pull requests fail until they are regenerated. Any new inline element in the web HTML is hashed automatically, but a new `style=` or `on…=` attribute fails the build by design. A new external origin in B3 comes only from `VITE_GATEWAY_BASE_URL`, so a future story that needs another host must change §2 here.
- **Not covered** (story AC 32, R3): trust on first use; Robolectric's own `android-all` download; the host crates built by `tools/build-host-ferrostar.sh` (WS-17); iOS Safari `'wasm-unsafe-eval'` until the PO's iPhone checklist item 11 is re-run.
- **Requests that follow from this ADR** (the orchestrator relays them):
  - **business-analyst.** Story AC 5 should allow `media-src 'self' data:` in **B2 only** (F6). Optional and minor: the edge case "`nosniff` … WASM streaming compile blocks it" can say that `nosniff` affects the `.js` files, while the `.wasm` type is hygiene only (F5).
  - **security-engineer.** Review §5's single trust rule and the B2 `media-src data:` exception in the Verify stage.
- **ADR numbering.** SEC-4A (gateway headers, BE-13) may also need an ADR. If one is written in parallel, it takes the next free number after 0018.
- **Browser tests against a production build** (found in the integration review, 2026-10-08). The meta policy applies under `vite preview` and the local stand-in hosts too. A test must not inject an inline `<style>` or `<script>` into a built page: Playwright's `page.addStyleTag({ content })` and `page.addScriptTag({ content })` **throw** with "Refused to apply inline style …" (measured on B2). To hide or tweak an element, set it through CSSOM, for example `el.style.setProperty("visibility", "hidden", "important")` in `page.evaluate`, or with a constructable stylesheet (`new CSSStyleSheet()` + `replaceSync` + `document.adoptedStyleSheets`), which `style-src` does not govern today and which also matches elements created later (used by `tests/e2e/nav017/helpers.mjs` `hideCanvas`; checked on B2 on 2026-10-08 with 0 violations: by the architect in Chromium, and by QA's regression test `tests/e2e/nav017/csp.test.mjs` in Chromium and WebKit). If a future CSP level brings constructed sheets under `style-src`, switch to the per-element form. `bypassCSP: true` is allowed only in a suite that is not a CSP check, and must have a comment that says why. The SEC-4B CSP spec never bypasses.

## Implementation notes (2026-10-08, from the SEC-4B implementation and the integration review)
These notes record where the implementation differs from the text above, or adds to it. None changes a decision.

| # | Note | Where |
|---|---|---|
| F13 | Demo mode created `<style id="demo-tokens">` at runtime, and the policy blocks that. The CSS is now written into the demo-mode `index.html` at build time (`bootIndicator`, `demoTokensCss()`) and hashed. So B2 `style-src` has **two** hashes: design-tokens and demo-tokens. Rule: client code creates no `<style>` or inline `<script>` at runtime (`web/README.md` › Security) | `web/vite.config.ts`, `web/src/style/tokens.ts`, `web/src/demo/demoMain.ts` |
| Impl 1 | The generator runs in `generateBundle` (`order: "post"`), not in `transformIndexHtml` (`post`). That hook runs later: after Vite's own post hooks and the asset-URL replacement. It still has the §1 intent, hashing the final bytes, and the independent `closeBundle` check on the files on disk is unchanged | `web/buildtools/csp.ts` |
| Impl 2 | Gradle drops XML comments inside the root element when it rewrites `verification-metadata.xml`. So the reason for the trust rule (§5) is kept in the file's header comment, before the root element, which survives regeneration. The README gives the same reason (AC 23) | `mobile/android/gradle/verification-metadata.xml` |
| Impl 3 | The metadata was generated with `--refresh-dependencies` on the existing Gradle home, not with an empty `GRADLE_USER_HOME` (disk space). Story AC 25 allows either. Gradle compares each cached file with the repository's published checksum. The three native publishers were spot-checked against Maven Central's served `.sha256` | handoff 2026-10-08 |
| Impl 4 | `--write-locks` also writes `settings-gradle.lockfile` (`empty=incomingCatalogForLibs0`). It is committed with `app/gradle.lockfile` and follows the same merge rule (§5) | `mobile/android/settings-gradle.lockfile` |
| Impl 5 | B3 with no `web/.env`: `VITE_STATIC_DEMO` unset parses as `false`; only an invalid value fails closed to "static demo". With `VITE_GATEWAY_BASE_URL` also unset, the build therefore gets `connect-src 'self' http://localhost:8080`, as measured in `dist/index.html`. `gatewayConnectOrigin` uses the same parse and fallback as `loadConfig`, so policy and runtime agree | `web/src/config.ts` `gatewayConnectOrigin`, `gatewayFallback` |

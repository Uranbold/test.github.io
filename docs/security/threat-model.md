# Threat model (owner: security-engineer)

Status: first version, 2026-10-08 (SP-8, task SEC-4C); updated 2026-10-08 after the SEC-4B Verify review (rows 1.4, 8.2, 8.4, 8.6, 8.7; ADR-0018) and its re-verification the same day (negative checks of rows 8.2 and 8.6 run by the security-engineer); SEC-4B AC 16 regression round reviewed the same day (QA-owned test changes keep `bypassCSP` false and replace injected inline styles with a constructable stylesheet; no row changed). Sources: the audit register `audits/2026-10-07-full-project.md` (finding IDs), decisions D212–D215 in `docs/requirements/decisions.md`, `docs/architecture/system-overview.md`, `docs/architecture/api/openapi.yaml` 0.6.1, `backend/README.md` ("Privacy and logs"), NAV-008.

Rules for this file:
- It describes what **exists** in the repo today. Items marked **(planned)** do not exist yet.
- **Status** values: `in place` (mitigation exists in the repo), `open <ID>` (register finding not yet fixed), `decided <D>` (fix decided, not built), `accepted` (risk accepted, no fix planned), `to verify` (believed in place, not checked on a real host or release build).
- No real hostnames, IPs, domains, account names or keys. Detailed exploit evidence stays out of this public file.
- Update it whenever a story adds an endpoint, a data flow, a permission, a stored file or a third-party service (checklist gate A1).

## 1. Assets

| ID | Asset | Why it matters | Where it lives |
|---|---|---|---|
| AS1 | **User location and search text** (route origin/destination/stops, device position, search query, approximate bias location, reverse-lookup coordinates) | Personal data: shows where a person lives, works and travels | Phone; in transit to the gateway; gateway/Valhalla/Photon memory. Not in server logs by design |
| AS2 | **Routes and the restore record** (current route and destination) | Same as AS1, plus a trip in progress | Phone app-private storage (AN-12); server memory only |
| AS3 | **Offline packs** (PMTiles map, Valhalla graph, SQLite search DB, manifest) | The app trusts pack contents for maps, routing and search; a bad pack gives wrong or dangerous guidance | Staging host (publication), phone storage |
| AS4 | **Signing keys**: Android release key (D214, not created yet), pack-signing key **(planned, D212)**, tag-signing key **(planned, BE-9)** | Whoever holds them can ship code or data that users' phones accept | PO custody, offline (D214); custody of planned keys not decided |
| AS5 | **Staging server** (VPS outside Mongolia per D25), its `.env`, SSH access, backups and monitoring push URLs | Runs the gateway, data pipeline and pack publication as root-capable services | The host; backup target |
| AS6 | **Repository and agent pipeline** (public GitHub repo, tags, issue forms, Claude agents with write and shell tools) | Tags are deployed to staging; agents act on repo content and issue text | GitHub; maintainer machines |
| AS7 | **Third-party credentials**: Chimege TTS token (held outside the repo), partner (Hamuga) evaluation key (SP-10) | Misuse costs money or partner trust | Outside the repo (0 matches for either key name in non-doc files, `Grep` 2026-10-08) |
| AS8 | **Service availability** (routing, search, tiles, pack downloads) | Navigation users depend on it while driving | Staging host |

## 2. Components and flows (summary)

Phone or browser → (HTTPS) → TLS proxy (Caddy, staging) → gateway (nginx) → Valhalla / Photon / PMTiles file / pack files, all on the compose network. The data pipeline on the same host downloads OSM and Photon inputs, builds slots, switches a pointer and publishes packs weekly/monthly. Deployment runs a git tag's script on the host. Public issues are triaged by an agent. Operations: `POST /v1/route`, `GET /v1/search`, `GET /v1/reverse`, `GET /tiles/basemap.pmtiles` (Range), `GET /packs/{region}/manifest.json`, `GET /packs/{region}/{fileVersion}/{file}`, `GET /health`. No accounts, no analytics, no crash reporter in the app (no Sentry/Crashlytics/ACRA/Firebase dependency in `mobile/`, `Grep` 2026-10-08).

## 3. Trust boundaries

| ID | Boundary | Crossing data |
|---|---|---|
| TB1 | Phone / browser ↔ gateway, over the internet with TLS | AS1, AS2, tiles, route/search responses |
| TB2 | Gateway ↔ Valhalla / Photon / tile and pack files (compose network, same host) | AS1 query parameters and bodies; upstream errors |
| TB3 | Data pipeline ↔ upstream downloads (OSM extract, Photon dump, land/water polygons, Maven/JAR artefacts, images) | Build inputs that become AS3 |
| TB4 | Pack host ↔ app (manifest and files over TLS; on-device verification and extraction) | AS3 |
| TB5 | Operator / GitHub tag ↔ staging host (SSH, `deploy.sh`, systemd jobs, backups) | AS5, code to run as root |
| TB6 | Public GitHub issues ↔ triage agent and the other agents | Untrusted text and attachments, possibly AS1 (coordinates, GPX, logs) |
| TB7 | Other apps on the phone ↔ our Android app (intents, exported components, storage, logs) | Intents, AS2 |
| TB8 | Build and release ↔ users (APK signing and distribution, web bundle hosting) | AS4, app code |
| TB9 | **(planned)** On-prem partner Valhalla endpoint and PBF export (PO's own / partner setup; needs the written agreement D38 and NAV-008 AC 25) | AS1 to a partner system; partner data into TB3 |

## 4. Threats and mitigations (STRIDE)

S spoofing · T tampering · R repudiation · I information disclosure · D denial of service · E elevation of privilege.

### TB1 Phone / browser ↔ gateway

| # | S/T/R/I/D/E | Threat | Mitigation | Status |
|---|---|---|---|---|
| 1.1 | S, T | Fake or intercepted gateway | Publicly trusted certificate, TLS 1.2/1.3 only at the proxy (NAV-008 AC 7); `main` network security config sets `cleartextTrafficPermitted="false"` (cleartext only in the `debug` source set) | in place; to verify on staging and in the first release build's merged manifest |
| 1.2 | I | Search text and approximate location travel in the GET query string | Logs drop query strings; consider POST or shorter retention review | open AN-11 (info) |
| 1.3 | I | Server logs collect location or IPs | Gateway access log has no IP, query, body, Origin or User-Agent; proxy has no access log; Valhalla request lines redacted; error log at `crit` | in place (`backend/README.md`); to verify on staging (NAV-008 AC 14) |
| 1.4 | I, T | Script injection, framing or referrer leak on the public web builds (static demo, demo mode) | Per-build `<meta>` CSP with build-time sha256 hashes, no `'unsafe-inline'`, `connect-src 'self'` (B1/B2 fail the build otherwise), `'wasm-unsafe-eval'` and `media-src 'self' data:` in the demo-mode build only; `<meta name="referrer" content="same-origin">` (ADR-0018). Web-root headers `frame-ancestors 'none'` (CSP header), `nosniff`, `Referrer-Policy`, `Permissions-Policy: geolocation=(self)`, HSTS documented in `web/README.md` for the PO | meta policy in place (SEC-4B); web-root headers to verify after the PO's `.htaccess` edit (SEC-4B R1/R2, checklist C9); iOS Safari `'wasm-unsafe-eval'` to verify (PO iPhone item 11) |
| 1.4a | I | Missing HSTS and security headers at the gateway edge | HSTS and headers at the edge | open BE-13 (low, SEC-4A) |
| 1.5 | D | Expensive route requests exhaust Valhalla | Limits: car ≤ 10 stops and ≤ 3,000 km; pedestrian/bicycle limits, request timeout and concurrency cap set by the architect; defined error in `openapi.yaml` | decided D213 (SEC-2); open BE-1 |
| 1.6 | D | Rate-limit bypass by IPv6 address rotation | Rate-limit key per IPv6 /64 | decided D215; open BE-2 |
| 1.7 | D | Large downloads (tiles, packs) saturate the link; slow clients hold connections | Connection and bandwidth limits; explicit edge timeouts and body limit | decided D215; open BE-3 (medium), BE-14 (low) |
| 1.8 | T | Unvalidated parameters reach upstreams | Allow-list parameters at the gateway; CORS allow-list (`CORS_ALLOWED_ORIGINS`) | CORS in place; open BE-15 (low) |
| 1.9 | R | No user accounts, so abuse cannot be tied to a person | By design (data minimisation); per-IP limits only | accepted |

### TB2 Gateway ↔ Valhalla / Photon / files

| # | S/T/R/I/D/E | Threat | Mitigation | Status |
|---|---|---|---|---|
| 2.1 | S, E | Upstreams reached directly, bypassing gateway limits | Only the gateway publishes a port; NAV-008 AC 12 port scan | in place (compose); to verify on staging |
| 2.2 | I | Upstream error bodies leak internals | Gateway maps errors to JSON error bodies | open BE-15 (low) |
| 2.3 | E | Compromise of one container spreads to the host | Non-root, read-only root FS, dropped capabilities, `no-new-privileges`; daemon hardening | decided D215; open BE-4 (medium), BE-12 (low) |
| 2.4 | T | Photon lane can write the whole slot; packs reuse those files without hash checks | Read-only mounts per lane; hash files before packing | decided D215; open BE-5 |
| 2.5 | T | Floating or old images change under us | Pin every image by digest; current nginx line | decided D215; open BE-8 (= WS-2, WS-3) |
| 2.6 | D | One slow Valhalla request blocks workers | Request timeout and concurrency cap | decided D213; open BE-1 |

### TB3 Pipeline ↔ upstream downloads

| # | S/T/R/I/D/E | Threat | Mitigation | Status |
|---|---|---|---|---|
| 3.1 | T, S | Tampered or substituted input files | HTTPS only, checksums or signatures per source, fail closed, no redirects to other schemes | decided D215; open BE-7 (= WS-9, WS-10) |
| 3.2 | T | Third-party signing keys fetched trust-on-first-use | Pin fingerprints | open BE-20 (info) |
| 3.3 | T | Vandalised or truncated OSM data produces bad routing | Rebuild guards (minimum size, size ratio, data age, route deviation, artefact ratio), blue-green slots, `make rollback` (D209 for packs) | in place (NAV-006) |
| 3.4 | I | Credentials embedded in download URLs end up in logs | Strip userinfo before logging; keep credentials out of URLs | open BE-18 (low) |
| 3.5 | E | Vulnerable build dependency | Track advisories (aircompressor not reachable) | open BE-19 (info) |

### TB4 Pack host ↔ app

| # | S/T/R/I/D/E | Threat | Mitigation | Status |
|---|---|---|---|---|
| 4.1 | S, T | A compromised host or path serves a pack the app accepts (manifest trusted through TLS only; hashes come from the same manifest) | Ed25519-signed manifest; public key pinned in the app; unsigned or wrongly signed packs rejected | decided D212 (SEC-1); open BE-6 (= AN-1, SP-9) |
| 4.2 | T | Redirect to any host during pack download | Same-origin, HTTPS-only redirects | open AN-8 (low) |
| 4.3 | T, E | Malformed search DB or licence URL from the manifest | Validate SQLite schema; open only `https` licence URLs | open AN-4, AN-7 (low) |
| 4.4 | D | Oversized files or decompression bombs fill the phone | Size from the signed manifest checked before and during download and decompression | to verify in the SEC-1 review (inferred, not checked here) |
| 4.5 | T | Partial publication seen by phones | Write-once version directories, manifest written by temp file + `rename(2)`; app installs to a `.partial` directory then renames (ADR-0017); retained manifests for rollback | in place (NAV-020, NAV-022) |

### TB5 Operator / GitHub ↔ staging host

| # | S/T/R/I/D/E | Threat | Mitigation | Status |
|---|---|---|---|---|
| 5.1 | E, T | Anyone who can move a tag runs code as root on staging | Protected tags and `master` (ruleset); deploy only signed tags with a pinned signer **(planned tag-signing key)** | open SP-2 (high, PO action), BE-9 |
| 5.2 | E | systemd jobs run as root, unsandboxed | Dedicated user, systemd sandboxing | open BE-16 (low) |
| 5.3 | I | `.env` readable by other users | Mode 600, owner check in deploy | open BE-17 (low) |
| 5.4 | T, D | Compromised host deletes its backups; backup account not chrooted | Append-only backup target, chroot, restore test | open BE-10 (low) |
| 5.5 | E | Unpatched Docker Engine | Unattended upgrades include Docker packages | open BE-11 (low) |
| 5.6 | I | Public docs describe staging posture in detail | Move detail to private notes | open SP-15 (low) |
| 5.7 | S | SSH brute force or stolen key | Keys only, no root password login, only 443/80/SSH open (NAV-008 AC 12) | to verify on staging |

### TB6 Public issues ↔ agents

| # | S/T/R/I/D/E | Threat | Mitigation | Status |
|---|---|---|---|---|
| 6.1 | E, T | Prompt injection in issue text makes an agent run commands or change files | Issue text is untrusted data (`.claude/agents/triage-lead.md`, `.claude/workflows/triage.js`) | in place (SP-1, fixed process) |
| 6.2 | E | Agents write outside their paths; rule is prompt-only | Deny rules in `.claude/settings.json` (needs the PO's own approval) | open SP-6 (not decided) |
| 6.3 | I | Reporters post home locations, GPX tracks, logs in public issues | Privacy warning on the bug form fields | decided D215; open SP-5 |
| 6.4 | I | Vulnerabilities reported publicly | `SECURITY.md` and private vulnerability reporting | decided D215; open SP-4 (PO action) |
| 6.5 | I | A secret pushed by mistake | Secret scanning, push protection, Dependabot | open SP-3 (PO action); WS-7 (CI checks) |
| 6.6 | T | Actions pinned to mutable tags | Pin by commit SHA; least-privilege `permissions` | open WS-6 (low) |

### TB7 Other apps ↔ Android app

| # | S/T/R/I/D/E | Threat | Mitigation | Status |
|---|---|---|---|---|
| 7.1 | S | Another app sends `EXTRA_OPEN_OFFLINE` or noisy-audio broadcasts | Ignore extras from outside; non-exported receiver | open AN-14, AN-13 (info) |
| 7.2 | I | Restore record readable on a rooted or backed-up phone | App-private storage; exclude from backup; retention is a product decision | open AN-12 (info) |
| 7.3 | I | Library or native logs print positions in release | Silence third-party logging in release | open AN-6 (low, inferred) |

### TB8 Build and release ↔ users

| # | S/T/R/I/D/E | Threat | Mitigation | Status |
|---|---|---|---|---|
| 8.1 | S, T | Fake APK; release key lost or leaked | PO holds the key offline with a backup, never in the repo or CI; publish the certificate SHA-256; Play App Signing considered | decided D214 (SEC-3); open SP-7, AN-10. Demo APK stays debug-signed (D180) |
| 8.2 | T | Malicious or replaced Gradle dependency or native library | `mobile/android/gradle/verification-metadata.xml` (sha256 for every resolved artifact, metadata verified, strict; one trust rule for `-sources`/`-javadoc` jars, which are never on a classpath); STRICT lock on `releaseRuntimeClasspath` (`app/gradle.lockfile`); wrapper JAR and distribution sha256 equal Gradle's published values; native `.so` provenance table checked against the APK (`tools/check_native_provenance.py`) | in place (SEC-4B); re-verified 2026-10-08: a one-character change to a pinned sha256 fails resolution of `releaseRuntimeClasspath` with Gradle's verification error, an unlocked module fails with the lock-state error, the MapLibre, Ferrostar, valhalla-mobile and JNA `.aar`/`.module`/`.jar` pins equal Maven Central's files, and the APK `.so` set equals the README table. Residual, accepted by AN-2 scope: trust on first use (pins of 2026-10-08), single-maintainer / small-team native publishers (documented, not removed), no PGP. Not covered: Robolectric `android-all`, host crates (WS-17), `aapt2` for macOS/Windows |
| 8.3 | T | Old vendored native code in MapLibre Native | Track upstream versions | open AN-5 (low, inferred) |
| 8.4 | I | Reverse-engineering eased; debug code shipped | R8 on for release; test fixture page and HTML developer comments left out of every web build (build fails otherwise) | open AN-3; WS-13, WS-16 fixed (SEC-4B) |
| 8.5 | T | Vulnerable web dev dependency | `npm audit`, update | open WS-5 (low, dev only) |
| 8.6 | T, E | A later change weakens or breaks the web policy (new inline script or style, a runtime-created `<style>`, an `on…=` or `style=` attribute, a plugin that injects after hashing, a hand-written weaker CSP meta) | `web/buildtools/csp.ts`: the generator refuses an existing CSP or referrer meta; an independent jsdom check of the files on disk fails the build on an unhashed or unused hash, a forbidden source, a misplaced policy, comments, fixtures, `.htaccess`, or `.wasm` outside demo mode; QA's zero-violation and injected-script spec (`tests/e2e/sec4b/`); test suites never inject inline `<style>`/`<script>` into a build and set `bypassCSP` only outside CSP checks, with a reason (ADR-0018 Consequences) | in place (SEC-4B) |
| 8.7 | T, E | Dependency verification is bypassed: lenient mode to get past a failure, or regeneration that silently re-pins a changed file for an unchanged version | README §11: never lenient (nothing sets `org.gradle.dependency.verification`), a failure without a version change is a supply-chain incident (`incident-response.md`), diff review against the publisher's served checksum, regenerate after merges instead of merging by hand | in place (process); hardening of the regeneration step requested in the SEC-4B review (low) |

### TB9 (planned) Partner Valhalla / PBF export

| # | S/T/R/I/D/E | Threat | Mitigation | Status |
|---|---|---|---|---|
| 9.1 | I | User coordinates sent to a partner system | Only after the written agreement (D38); gateway-side proxy, no client-side key; privacy notice names the recipient; counsel review | planned |
| 9.2 | I | Partner key leaks (bundles, logs, history) | Key in host `.env` only; secret scan of history, bundles and 1 day of logs (NAV-008 AC 25); revoke the evaluation key | open SP-10 (PO action), SP-16 (low) |
| 9.3 | T | Partner PBF export is altered | Same integrity rules as TB3 (checksum or signature from the partner) | planned |

## 5. Out of scope and assumptions

- OpenStreetMap data errors are a data-quality issue, handled by mappers (lane `osm-data`), not a security issue, except deliberate vandalism that reaches users (3.3).
- Lost or stolen phones: the app relies on the OS lock screen and app-private storage.
- iOS: no iOS app exists yet; add a boundary when it does.
- Legal assessment (Law on Personal Data Protection, 2021; staging outside Mongolia, D9/D25) is for counsel; see `incident-response.md` and the checklist gate C.

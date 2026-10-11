# Security audit: whole project, 2026-10-07

Scope: backend and infrastructure (BE), Android app (AN), web demo and supply chain (WS), secrets hygiene and the delivery process (SP). Static review plus local checks only; no external or production host was probed. Done by the `security-engineer` role.

This public register lists findings by title, severity, owner and status only. The detailed evidence is kept out of the public repo and was given to the PO.

## Result

- **No leaked secret, key, keystore, `.env`, APK, or real server host or IP** in the working tree or in any of the 156 commits of history.
- Unique findings: critical 0 · high 2 · medium 20 · low 26 · info 14. Duplicates across the four audits are marked. Added by the re-check of 2026-10-11: low 1 (BE-22), info 2 (BE-23, BE-24).
- Severity gates (see `.claude/agents/security-engineer.md`): critical/high block the story or release; medium must be fixed before the first public release; low/info go to the backlog.

## Not verified

Container image CVE scans and image default users (no scanner or daemon in the audit run), the merged Android manifest and R8 output from a real release build, real host response headers, GitHub collaborator, deploy-key and Actions settings (no API access). Re-run these on the first staging server and the first signed release build.

## Findings

| ID | Severity | Finding | Owner | Status |
|---|---|---|---|---|
| SP-1 | high | Prompt injection: untrusted public issue text is processed by an agent with write and shell tools | orchestrator | fixed (process) 2026-10-07 |
| SP-2 | high | `master` and tags are unprotected while staging deploys run the tag's own script as root | orchestrator | open |
| AN-1 | medium | Offline packs are trusted only through TLS: the manifest is not signed | architect | duplicate of BE-6 |
| AN-2 | medium | No Gradle dependency verification or lockfile, and the native libraries come from single-maintainer publishers | mobile-engineer | fixed 2026-10-08 (SEC-4B: sha256 verification metadata, STRICT release lockfile, native provenance documented; single-maintainer risk documented, not removed) |
| BE-1 | medium | Route request cost is unbounded: Valhalla DoS | backend-engineer | fixed 2026-10-11 (SEC-2: Valhalla service_limits for every costing, processing timeout, gateway concurrency cap; ADR-0019; residuals in threat-model rows 1.10, 1.11, 2.7) |
| BE-2 | medium | IPv6 address rotation bypasses the per-IP rate limit | backend-engineer | fixed 2026-10-11 (SEC-4A: every per-client limit keyed by IPv4 address or IPv6 /64; re-check 2026-10-11) |
| BE-3 | medium | Large static downloads have no connection or bandwidth limits | backend-engineer | partly fixed 2026-10-11 (SEC-4A: pack files capped per client and in total, bandwidth limits on packs and tiles). Residual medium: no per-client connection cap on tiles (contract lists none); see re-check |
| BE-4 | medium | No container hardening on any service | backend-engineer | fixed 2026-10-11 (SEC-4A: all services cap_drop ALL, no-new-privileges, memory/PID limits; serving services non-root on read-only roots). Residuals low, accepted for staging: one-shot builders run as root (photon-import with CHOWN/DAC_OVERRIDE), Photon /tmp allows exec, ops-VM Kuma keeps default capabilities |
| BE-5 | medium | Photon lane mounts the whole slot read-write; packs reuse those files without hash checks | backend-engineer | fixed 2026-10-11 (SEC-4A: Photon mounts the slot read-only and only photon/ read-write; pack step checks build-time sha256) |
| BE-6 | medium | Offline pack manifest is not signed | architect | open |
| BE-7 | medium | Integrity of downloaded sources is incomplete | backend-engineer | fixed 2026-10-11 (SEC-4A: https only incl. redirects, tool checksums fail closed, OSM .md5 required on staging, Maven strict checksums). Residual low: Photon dump and Planetiler auxiliary data have no checksum beyond TLS |
| BE-8 | medium | Image pinning gaps and a superseded nginx line | backend-engineer | fixed 2026-10-11 (SEC-4A: every image tag@sha256, gateway on the nginx 1.30 stable line, CI refuses tag-only images). Image CVE scan still open (checklist B3) |
| BE-9 | medium | deploy.sh runs a git tag's own code as root without signature check; tags can move | backend-engineer | fixed 2026-10-11 (SEC-4A: deploy only SSH-signed annotated tags from a root-owned allowed-signers file; moved tags refused). Residuals: BE-22; `nav-ops` owns the checkout (accepted: the account already has sudo); one-off unsigned bootstrap. SP-2 ruleset still open |
| SP-3 | medium | GitHub secret scanning, push protection and Dependabot are all disabled on a public repo | orchestrator | open |
| SP-4 | medium | No vulnerability disclosure channel; reports can only arrive as public issues | orchestrator; business-analyst | partly done: `SECURITY.md` at the repo root and an issue-form contact link on the working branch, **not yet on the default branch**; private vulnerability reporting still disabled (PO) |
| SP-5 | medium | Bug issue form collects precise location, GPX tracks and logs in a public repo | business-analyst | fixed in the repo 2026-10-08 (issue-form privacy warning); **not live**: GitHub serves issue forms from the default branch, which still has the old form (merge needed) |
| SP-6 | medium | No least-privilege controls for agents; path-ownership rule is prompt-only | orchestrator | open |
| SP-7 | medium | Release signing key: no decision on custody, backup or rotation; demo APK has no verifiable identity | orchestrator | open |
| SP-8 | medium | No threat model, release security checklist or release gate | security-engineer; orchestrator | done (threat model + checklist) 2026-10-08; updated 2026-10-11 |
| SP-9 | medium | Offline packs are verified by hash only; publisher authenticity is not checked | architect | duplicate of BE-6 |
| SP-10 | medium | Partner (Hamuga) evaluation API key handed over through chat; no rotation evidence | orchestrator | open |
| SP-11 | medium | Privacy and incident-response documentation is missing at project level | business-analyst; security-engineer; orchestrator | partly done (incident runbook `docs/security/incident-response.md`); privacy notice not written yet (BA) |
| SP-12 | medium | Security review is missing or only regex-triggered in some lanes | orchestrator | fixed 2026-10-07 |
| WS-1 | medium | No Content-Security-Policy or other security headers on the public web demo / demo mode | mobile-engineer | partly fixed 2026-10-08 (SEC-4B: CSP and referrer meta in every build; re-checked 2026-10-11 after SEC-2, all three builds verified); open until the PO sets the documented web-root headers and the `web/README.md` checks pass (checklist C9) |
| WS-2 | medium | Internet-path gateway image is on a frozen tag line and not pinned by digest | backend-engineer | duplicate of BE-8 (fixed 2026-10-11) |
| WS-3 | medium | Java base image floats: no version, no digest | backend-engineer | duplicate of BE-8 (fixed 2026-10-11) |
| WS-4 | medium | Android build has no Gradle dependency verification | mobile-engineer | duplicate of AN-2 (fixed 2026-10-08) |
| AN-3 | low | R8/minification is off for release | mobile-engineer | open |
| AN-4 | low | Search pack DB is opened with SQLite defaults (schema trusted) | mobile-engineer | open |
| AN-5 | low | Old vendored native libraries in MapLibre Native 13.6.1 (inferred, not verified) | mobile-engineer, architect | open |
| AN-6 | low | Third-party native and library logging is not silenced in release (inferred) | mobile-engineer, qa-engineer | open |
| AN-7 | low | Licence URL from the (unsigned) manifest is opened with ACTION_VIEW without a scheme check | mobile-engineer | open |
| AN-8 | low | Pack HTTP client follows redirects to any host | mobile-engineer | open |
| AN-9 | low | No upper bound on route/search response bodies | mobile-engineer | open |
| BE-10 | low | Backups can be deleted from a compromised staging host; SFTP account not chrooted | backend-engineer | open |
| BE-11 | low | Docker Engine security updates are never applied automatically | backend-engineer | open |
| BE-12 | low | Default Docker daemon and network settings | backend-engineer | open (Docker daemon settings unchanged; per-container no-new-privileges now compensates in part. Note: SEC-4A docs label the nosniff header "BE-12"; that header closes BE-13 only) |
| BE-13 | low | No HSTS or other security headers at the edge | backend-engineer | fixed 2026-10-11 (SEC-4A: HSTS and no Server header at Caddy, nosniff on every gateway response, static-pack .htaccess nosniff and no listings); to verify on the staging host (checklist B4) |
| BE-14 | low | No explicit edge timeouts or body limit (slow clients) | backend-engineer | open |
| BE-15 | low | Gateway forwards every query parameter and upstream error body unvalidated | backend-engineer | open |
| BE-16 | low | systemd jobs run as root with no sandboxing | backend-engineer | open |
| BE-17 | low | Permissions of `infra/staging/.env` are not enforced | backend-engineer | open |
| BE-18 | low | Download logs may carry credentials embedded in URLs | backend-engineer | partly fixed 2026-10-11 (download helper strips credentials and query strings; the NAV-006 pipeline still logs source URLs unredacted) |
| SP-13 | low | `.gitignore` coverage depends on per-directory files; several sensitive patterns are not ignored everywhere | orchestrator | fixed 2026-10-07 |
| SP-14 | low | GitHub Action pinned by tag, not commit SHA | orchestrator | duplicate of WS-6 |
| SP-15 | low | Public docs describe the staging security posture in detail | backend-engineer; orchestrator | open |
| SP-16 | low | Partner API reconnaissance published in a public repo | orchestrator | open |
| SP-17 | low | Personal / partner email addresses in history | orchestrator | open |
| SP-18 | low | Documentation drift: security role not in the team docs the orchestrator follows | orchestrator | fixed 2026-10-07 |
| WS-5 | low | Dev-only dependency with a high advisory: `source-map-js` 1.2.1 | mobile-engineer | open |
| WS-6 | low | GitHub Actions pinned to mutable tags | orchestrator | open |
| WS-7 | low | No automated supply-chain or secret checks in CI | orchestrator | open |
| WS-8 | low | Gradle wrapper JAR not validated against Gradle's published checksum | mobile-engineer | fixed 2026-10-08 (SEC-4B: JAR and distribution equal the published sha256, recorded in the README) |
| WS-9 | low | Download helper fails open and follows redirects to any scheme | backend-engineer | duplicate of BE-7 (fixed 2026-10-11) |
| WS-10 | low | Data inputs and the Protomaps Maven build have no integrity anchor beyond TLS | backend-engineer | duplicate of BE-7 (partly: Maven strict checksums; data inputs still TLS only, residual low) |
| WS-11 | low | Python dev/test deps pinned at top level only, no hashes | backend-engineer, qa-engineer | open |
| WS-12 | low | Static-pack `.htaccess` sets no security headers | backend-engineer | duplicate of BE-13 (fixed 2026-10-11) |
| AN-10 | info | Release signing is not defined in the build | orchestrator, architect | open |
| AN-11 | info | Search text and approximate location go to the gateway in the GET query string | backend-engineer, business-analyst | open |
| AN-12 | info | Restore record (current route + destination) stored unencrypted in app-private storage | business-analyst | open |
| AN-13 | info | `AUDIO_BECOMING_NOISY` receiver is registered as EXPORTED | mobile-engineer | open |
| AN-14 | info | Exported MainActivity accepts `EXTRA_OPEN_OFFLINE` from any app | mobile-engineer | open |
| BE-19 | info | aircompressor 0.27 has an advisory for a path the project does not use | backend-engineer | open |
| BE-20 | info | Third-party keys fetched without a pinned fingerprint (TOFU) | backend-engineer | open |
| BE-21 | info | Log retention and PII: compliant; re-check on every pin change | backend-engineer, business-analyst | open |
| SP-19 | info | GitHub Pages is enabled on the public repo | orchestrator | open |
| SP-20 | info | Public-repo decision rests on an ambiguous relayed message | orchestrator | open |
| WS-13 | info | Test fixture page shipped in the public builds | mobile-engineer | fixed 2026-10-08 (SEC-4B) |
| WS-14 | info | Vendored glyph/sprite checksums are trust-on-first-use | mobile-engineer | open |
| WS-15 | info | `aircompressor` 0.27 has CVE-2025-67721 (not reachable here) | backend-engineer | duplicate of BE-19 |
| WS-16 | info | Production HTML keeps the developer comment | mobile-engineer | fixed 2026-10-08 (SEC-4B) |
| WS-17 | info | Host-only native builds are partly unlocked | mobile-engineer, backend-engineer | open |
| BE-22 | low | `deploy.sh` does not check that the signed tag's own name matches the requested tag, so an older signed release can be deployed under a new tag name (needs push access to tags, see SP-2) | backend-engineer | open (re-check 2026-10-11) |
| BE-23 | info | A burst value of 0 passes the gateway's entrypoint check but nginx then refuses to start (fails closed; wrong error message) | backend-engineer | open (re-check 2026-10-11) |
| BE-24 | info | Container users 101 and 65534 share numeric IDs with host system accounts; files in the slot `photon/` belong to host uid 101 (inferred, not checked on a host) | backend-engineer | open (re-check 2026-10-11) |

## Re-check 2026-10-11 (final security pass after SEC-2, SEC-4A, SEC-4B, SEC-4C)

Scope: `git diff c6b8c9e..HEAD` (backend, infra, web build tools and sources, Android build files, issue forms, `SECURITY.md`). Local checks only, against the dev containers `navmn-*`, throwaway gateway containers built from the same image and config, and local builds. No external host was probed; GitHub repository settings were read through the API.

What was run (results in brief):
- `infra/ci/backend-static-checks.sh` and `infra/ci/staging-static-checks.sh`: both pass (includes the signed-tag test, the /64 key test, the pack download-limit test and 93 + 38 pipeline unit tests).
- `docker inspect` of `navmn-gateway-1`, `navmn-valhalla-1`, `navmn-photon-1`: users 101 / 65534 / 101, read-only root, `CapDrop [ALL]`, no capabilities added, `no-new-privileges`, memory and PID limits, images pinned by digest. In-container writes to `/data` fail with "Read-only file system"; effective capabilities 0.
- `curl` on `127.0.0.1:8080`: `X-Content-Type-Options` and each CORS header exactly once on 200, 206 (Range), 400, 404, 405 and 416; Range reads return 206. Gateway log: no addresses or query strings.
- Throwaway gateway with rate limits on: `X-Forwarded-For` ignored from an untrusted peer; from a trusted peer, addresses in one IPv6 /64 share one bucket, different /64s do not, an IPv4-mapped address equals its IPv4 address, and only the last list entry counts. 60 parallel full-file tile downloads from one address were all accepted (BE-3 residual).
- Signed-tag probe against a scratch origin: a new tag name pointing at an older signed tag object was deployed (BE-22).
- Web: the three builds (normal, static demo, demo mode) pass the build-time CSP check after the SEC-2 UI changes (no hash drift); `buildtools/csp.test.ts` 48 tests pass; `npm audit --omit=dev` 0 vulnerabilities (WS-5 dev-only advisory unchanged). Android build files unchanged since the SEC-4B re-verification of 2026-10-08.
- GitHub settings (API): default branch has no ruleset or protection (SP-2); secret scanning, push protection and Dependabot disabled (SP-3); private vulnerability reporting disabled (SP-4). The default branch does not contain `SECURITY.md` or the new issue-form warning yet; the SEC-2/SEC-4 work is only on the working branch.

Residuals reported by the backend engineer, judged:
- Builders run as root (photon-import with CHOWN and DAC_OVERRIDE): **low, accepted** for staging. Later: non-root builders, or mount the data root `nosuid,nodev`.
- Photon `/tmp` allows exec (JNA): **low, accepted**. Possible later fix: unpack the JNA library at import time into a read-only path.
- `nav-ops` owns the checkout: **accepted** while `nav-ops` already has passwordless sudo (it is root-equivalent anyway). Revisit if a deploy account without sudo is introduced.
- One-off unsigned bootstrap (`DEPLOY_ALLOW_UNSIGNED=1`): **low, accepted** for the first deploy only. Suggested: refuse it once a deployed tag is recorded; the gate B record must show a real signer for every later deploy.
- Tiles without a per-client connection cap: **medium, not accepted for gate C**. The bandwidth limit makes each full-file download last longer, so one address can hold many connections. Needs a contract amendment (429 on tiles) and a measured cap, or a PO-approved exception for the tester-only staging host.
- Kuma with default capabilities: **low, accepted** for now (UI bound to 127.0.0.1); drop capabilities after the pinned-image test.
- BE-14 (edge timeouts, body limit, `send_timeout`) and BE-17 (`.env` mode 600): low in this register, but both are gate B checklist items (B7, B9, B10). Fix them before the first staging deploy or record a PO exception.

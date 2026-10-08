# Release security checklist (owner: security-engineer)

Status: first version, 2026-10-08 (SP-8, task SEC-4C). Threats and finding IDs: `threat-model.md` and `audits/2026-10-07-full-project.md`. Decisions: D212–D215.

How to use it:
- Three gates: **A** at every story's Verify stage, **B** before the first staging deploy (and re-run on any new host), **C** before the first public release (and every public release after it).
- A gate passes when every item is ticked or carries a recorded exception with the PO's approval. Severity rules apply: an open `critical`/`high` blocks the gate; an open `medium` blocks gate C (D215).
- Record each run as a dated note in the story (gate A) or as an audit report `audits/YYYY-MM-DD-<scope>.md` (gates B and C), with commands and results. Never paste secrets, hostnames or IPs into the record.
- Only test hosts the project owns. Scans of the staging host are run by its operator.

Owners: BE `backend-engineer`, MO `mobile-engineer`, AR `architect`, QA `qa-engineer`, BA `business-analyst`, SE `security-engineer`, OR `orchestrator`, PO product owner.

## Gate A: every story Verify

| # | Item | Owner | How to verify |
|---|---|---|---|
| A1 | Threat model updated if the story adds an endpoint, data flow, permission, stored file or third-party service | SE | Diff of `threat-model.md` in the story, or a note "no change" in the security review |
| A2 | No secrets in the diff or new history | SE, QA | `gitleaks detect --log-opts="<base>..HEAD"` → 0 findings |
| A3 | No real hostnames, IPs or domains of project servers | SE | `git diff <base> -- . \| grep -nE '([0-9]{1,3}\.){3}[0-9]{1,3}\|https?://'` reviewed by hand; only placeholders such as `<staging-host>` |
| A4 | New configuration keys appear only in `.env.example` with empty or placeholder values | BE, MO | Read the `.env.example` diff; no value that works outside dev |
| A5 | Dependency changes: lockfile committed, no new known high/critical advisory | BE, MO | `npm audit --omit=dev` (web), Gradle dependency report plus `osv-scanner` on the lockfiles; result in the review |
| A6 | Logs keep no coordinates, query strings, search text, IPs or request bodies (release builds and server logs) | BE, MO, QA | Gateway: NAV-008 AC 14 log read; Android: `adb logcat` during a test route on a release-type build shows no position or query |
| A7 | Android: no new exported component, permission or intent extra without review; location only while needed | MO, SE | Merged manifest (`app/build/intermediates/merged_manifests/…`) diff; `aapt2 dump xmltree` on the APK |
| A8 | New or changed HTTP operation is in `openapi.yaml` with size limits and a defined error; gateway validates and rate-limits it | AR, BE | Contract diff; API test of the over-limit and malformed cases |
| A9 | User-facing security or privacy text uses glossary terms; missing terms requested from the BA | BA, MO | Glossary lookup in the review |
| A10 | Findings of this review: critical/high fixed before the story is done; medium/low recorded in the register | SE, OR | Register diff |

## Gate B: first staging deploy

| # | Item | Owner | How to verify |
|---|---|---|---|
| B1 | Container hardening (BE-4): non-root user, read-only root FS where possible, `cap_drop: [ALL]`, `no-new-privileges`, memory/pid limits | BE | `docker inspect --format '{{.Config.User}} {{.HostConfig.ReadonlyRootfs}} {{.HostConfig.CapDrop}} {{.HostConfig.SecurityOpt}}' <c>` for every container |
| B2 | Every image pinned by digest (BE-8 = WS-2, WS-3); nginx on a supported line | BE | `docker compose config --images` → every line has `@sha256:`; grep `*_IMAGE=` in `.env.example` files |
| B3 | Image CVE scan: no unfixed critical/high in shipped images, or a recorded exception | BE, SE | `trivy image --severity HIGH,CRITICAL <image@digest>` (or `grype`) for each image; report attached |
| B4 | TLS: publicly trusted certificate, TLS 1.2/1.3 only, HSTS (BE-13), renewal alert ≤ 14 days | BE | NAV-008 AC 7 and AC 18 checks run by the operator (`testssl.sh <staging-host>`), `curl -sI` shows `Strict-Transport-Security` |
| B5 | Firewall: only 443, 80 (redirect/ACME) and the SSH port open; Valhalla, Photon and the gateway's internal port not reachable | BE | NAV-008 AC 12 external port scan by the operator |
| B6 | SSH: keys only, no password login, no root password login, named admin users | BE | `sshd -T \| grep -E 'passwordauthentication\|permitrootlogin\|pubkeyauthentication'` |
| B7 | `.env` and secret files mode 600, owned by the service user (BE-17) | BE | `stat -c '%a %U' infra/staging/.env <password files>` → `600 <user>`; deploy script refuses other modes |
| B8 | Backups: off-host, retention per NAV-008 (≥ 7 daily, ≥ 4 weekly), a restore drill done, host cannot delete old backups (BE-10) | BE | `restic snapshots`; restore of one slot to a scratch path; delete attempt from the host fails |
| B9 | Rate limits: per-IP and per-IPv6 /64 (BE-2), download connection/bandwidth limits (BE-3), edge timeouts and body limit (BE-14) | BE, QA | Local load test against the Docker stack: requests over the limit get 429; many addresses in one /64 share one bucket |
| B10 | Route limits active (D213, BE-1) | BE, QA | API test: 11 stops or > 3,000 km → the `openapi.yaml` over-limit error |
| B11 | Download integrity (BE-7) and read-only lane mounts with hash checks (BE-5) | BE | Pipeline test with a corrupted input → build fails closed; `docker inspect` mounts show `ro` |
| B12 | Deploy runs only protected, signed tags (BE-9, SP-2) | BE, OR, PO | Ruleset visible in GitHub settings; `deploy.sh` with an unsigned tag exits non-zero |
| B13 | Unattended security upgrades include Docker Engine (BE-11) | BE | `unattended-upgrade --dry-run -d` lists the Docker origin |
| B14 | Monitoring, health and metrics endpoints are not public; push URLs kept in `.env` only | BE | External request to monitoring paths returns 404/403; grep the repo for push tokens → 0 |
| B15 | Server logs privacy (NAV-008 AC 14) | QA | Read one day of gateway and proxy logs: no IPs, query strings, bodies or coordinates |
| B16 | systemd jobs run as a dedicated user with sandboxing (BE-16, low; backlog unless fixed in SEC-4) | BE | `systemd-analyze security <unit>` |

## Gate C: public release

| # | Item | Owner | How to verify |
|---|---|---|---|
| C1 | **Signed packs (D212, SEC-1):** manifest signed with Ed25519, public key pinned in the app, unsigned or wrongly signed packs rejected | AR, BE, MO, QA | Test: a manifest with one changed byte, one without signature, and one signed with another key are each rejected and nothing is installed |
| C2 | Pack-signing key custody and rotation decided (architect proposes, PO confirms; D212 note) | AR, PO | Row in `decisions.md`; key not on the build host unless that is the decided model |
| C3 | **Route limits (D213, SEC-2)** live, with the Mongolian over-limit message from the glossary | BE, MO, BA, QA | API test as B10; UI test shows the glossary text |
| C4 | **Release key custody (D214, SEC-3):** the PO holds the Android release key offline with a backup copy; never in the repo or CI | PO, OR | PO confirms in writing; `gitleaks` and a search for `*.jks`, `*.keystore` in the repo and CI secrets → 0 |
| C5 | **Play App Signing** considered before Google Play publication (choice not decided, D214) | PO, AR | Decision row in `decisions.md` before the first Play upload |
| C6 | **Signing certificate SHA-256 recorded** so the PO can verify APKs | MO, PO | `apksigner verify --print-certs app-release.apk` → the SHA-256 is recorded in the release notes; the PO checks every APK against it before distributing. The fingerprint is public, the key is not |
| C7 | Android release build: R8 on (AN-3), cleartext off, no crash reporter, third-party logging silenced (AN-6), exported components reviewed (AN-13, AN-14) | MO, QA | Build output shows minification; merged manifest; `adb logcat` on a test route |
| C8 | Gradle dependency verification and lockfile (AN-2 = WS-4); wrapper checksum (WS-8) | MO | `gradle/verification-metadata.xml` present; build with `--dependency-verification strict` passes |
| C9 | Web demo: CSP and security headers (WS-1); test fixture page and developer comment removed (WS-13, WS-16) | MO, BE | `curl -sI` on the demo origin shows `Content-Security-Policy`, `X-Content-Type-Options`, `Referrer-Policy`; browser console has no CSP violations |
| C10 | **No medium (or higher) finding open** in the register (D215) | SE, OR | Register shows only `fixed`, `duplicate of` or a PO-approved exception for every medium |
| C11 | **Privacy notice published** and linked from the app and web demo (SP-11) | BA, MO | Notice in the repo and reachable from Settings; text matches what the app does (threat model §2) |
| C12 | **Counsel review** of the privacy notice, data transfer abroad (staging outside Mongolia, D9/D25) and partner data flows (TB9) | PO | Dated record in `decisions.md` before the first outside user (NAV-008 AC 24) |
| C13 | **`SECURITY.md`** at the repo root (SP-4); text from `security-policy.md` | OR | File exists on `master` |
| C14 | **GitHub settings done by the PO:** SP-2 ruleset for `master` and tags; SP-3 secret scanning, push protection, Dependabot; SP-4 private vulnerability reporting | PO | Settings pages checked by the PO; a test push of a dummy token pattern is blocked; "Report a vulnerability" button visible |
| C15 | **Hamuga evaluation key revoked (SP-10)** and partner reconnaissance reviewed (SP-16) | PO, OR | Partner confirms revocation in writing; date recorded |
| C16 | Bug issue form shows the privacy warning on location, GPX and log fields (SP-5) | OR, BA | Read `.github/ISSUE_TEMPLATE/1-bug.yml` |
| C17 | Incident-response runbook reviewed by the PO; key inventory current | SE, PO | `incident-response.md` reviewed date |
| C18 | Re-run the audit's "Not verified" items on the real host and release build (image scans, merged manifest, R8 output, response headers, GitHub settings) | SE | New audit report under `audits/` |
| C19 | Agent least privilege (SP-6) decided by the PO | PO | Decision row; if (a), `.claude/settings.json` deny rules exist |

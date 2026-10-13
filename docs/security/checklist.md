# Release security checklist (owner: security-engineer)

Status: first version, 2026-10-08 (SP-8, task SEC-4C); gate status added 2026-10-11 after the final security pass on SEC-2 / SEC-4A / SEC-4B / SEC-4C (section "Gate status 2026-10-11" at the end); NAV-016 voice clip pack gates C20–C24 added 2026-10-13 (NAV-016 AC 52–53, D79, D180, D228, D230; review `audits/2026-10-13-NAV-016.md`, section "Gate status 2026-10-13 (NAV-016)" at the end). Threats and finding IDs: `threat-model.md` and `audits/2026-10-07-full-project.md`. Decisions: D212–D215.

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
| B9 | Rate limits: per-IP and per-IPv6 /64 (BE-2; the SEC-2 per-IP route cap zone too), download connection/bandwidth limits (BE-3), edge timeouts and body limit (BE-14) | BE, QA | Local load test against the Docker stack: requests over the limit get 429; many addresses in one /64 share one bucket |
| B10 | Route limits and concurrency cap active (D213, BE-1, SEC-2, ADR-0019), with cap values from the staging measurement (deployment-staging §7.2) and an explicit `send_timeout` on `/v1/route` (threat model 1.11) | BE, QA | `smoke.py` AC 44 after every slot switch; API test: 11 locations or a car route > 3,000 km straight-line → the `openapi.yaml` 400; one over-limit request per costing (incl. truck, bus, taxi, motorcycle, motor_scooter) → 400; `contract_check.py --route-cap` → 503 `RoutingBusy` with `Retry-After`; gateway startup log shows the cap values; Valhalla startup log shows `route limits applied` |
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
| C3 | **Route limits (D213, SEC-2)** live, with the Mongolian over-limit message from the glossary (N24 on car, N11 on walk/bike; 503 `RoutingBusy` shows N8 or the on-device fallback) | BE, MO, BA, QA | API test as B10; UI test shows the glossary text |
| C4 | **Release key custody (D214, SEC-3):** the PO holds the Android release key offline with a backup copy; never in the repo or CI | PO, OR | PO confirms in writing; `gitleaks` and a search for `*.jks`, `*.keystore` in the repo and CI secrets → 0 |
| C5 | **Play App Signing** considered before Google Play publication (choice not decided, D214) | PO, AR | Decision row in `decisions.md` before the first Play upload |
| C6 | **Signing certificate SHA-256 recorded** so the PO can verify APKs | MO, PO | `apksigner verify --print-certs app-release.apk` → the SHA-256 is recorded in the release notes; the PO checks every APK against it before distributing. The fingerprint is public, the key is not |
| C7 | Android release build: R8 on (AN-3), cleartext off, no crash reporter, third-party logging silenced (AN-6), exported components reviewed (AN-13, AN-14) | MO, QA | Build output shows minification; merged manifest; `adb logcat` on a test route |
| C8 | Gradle dependency verification and lockfile (AN-2 = WS-4); wrapper checksum (WS-8) | MO | `gradle/verification-metadata.xml` present (sha256, only the `-sources`/`-javadoc` trust rule); `app/gradle.lockfile` committed; `./gradlew :app:assembleRelease` passes with verification strict and nothing set to lenient; wrapper JAR sha256 equals the published value for the Gradle version (`mobile/android/README.md` §11.3); `tools/check_native_provenance.py --apk <release APK>` → OK |
| C9 | Web demo: CSP and security headers (WS-1); test fixture page and developer comment removed (WS-13, WS-16) | MO, BE, PO | Build log shows `navmn: CSP and referrer policy verified` for each build; `curl -sI` on the demo origin and the demo folder shows the five headers of `web/README.md` › Security headers (`content-security-policy: frame-ancestors 'none'` only, `x-content-type-options`, `referrer-policy`, `permissions-policy`, `strict-transport-security`); browser console has no CSP violations on both pages; demo mode re-checked on a real iPhone |
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
| C20 | **Voice pack, NAV-016 AC 53 gate 1: Chimege written output terms.** A `decisions.md` D row records the vendor's written terms. They allow in-app redistribution of the clips and state the data location (D226, D228). Until C20–C22 all pass, **no build with a pack goes beyond the PO** (C23) | PO, BA | The D row exists, and quotes the date and where the signed terms are kept (not in the repo, D46); counsel question 1 of `audits/2026-10-13-NAV-016.md` answered |
| C21 | **Voice pack, AC 53 gate 2: NAV-007 sign-off for this exact pack.** The record lists the AC 9 device matrix, the AC 10 listening scores and the AC 11 duration evidence for the **same** (`voiceId`, `packVersion`, manifest SHA-256) as the release build (D228) | QA, PO | Sign-off record in `docs/qa/`; its triple equals the one `make -C backend voice-pack-verify DIR=<pack>` prints and the `assets/voicepack/manifest.json` SHA-256 inside the release APK (`unzip -p app-release.apk assets/voicepack/manifest.json \| sha256sum`) |
| C22 | **Voice pack, AC 53 gate 3: the PO's native per-segment listen for this exact pack** is complete (ADR-0010 §4, D230): every row of `review/review.tsv` has a result and **0** rows are `bad`. The same listen was done before the first NAV-007 AC 10 session | PO | Row count of `review.tsv` = manifest entry count; `awk -F'\t' 'NR>1 && $4!="ok"' review.tsv` prints nothing; the listened pack's manifest SHA-256 equals the APK's (C21). This is also the only control that tells real vendor audio from a fake-server or placeholder pack labelled with the vendor (VP-5) |
| C23 | **Voice pack custody until C20–C22 pass (D79, D180, D228):** APKs with a pack go to the PO only, by direct file transfer; never to a store, a website, GitHub Releases or CI artifacts, or to outside testers. No clip, pack manifest or review file in git (AC 19); CI never calls the vendor | OR, PO, SE | Repo-wide scan: `git ls-files \| grep -iE '\.(wav\|m4a\|aac\|mp3\|ogg\|opus\|flac)$'` → only the chime path, if any; no tracked `manifest.json` containing `inventorySha256`; no `review/index.html`; `.github/workflows/` has no generator step; the release notes name the recipients |
| C24 | **Voice pack token:** `CHIMEGE_API_TOKEN` and its value appear **0** times in the repo, its history, the APK and the Gradle configuration (AC 8, AC 52). The token was rotated after every exposure: the 2026-10-13 chat exposure (VP-8) must be rotated **before the first real pack is built**. The token is never pasted into a chat, an issue or a file | PO, SE | PO confirms the new token and the revocation of the old one, with a date; `gitleaks detect` on the full history → 0; APK zip scan for the variable name → 0; `make -C backend voice-pack-test` sentinel test passes |

## Gate status 2026-10-11

From the re-check in `audits/2026-10-07-full-project.md` (section "Re-check 2026-10-11"). "Met in the repo" means the control exists and was checked locally; items that can only be checked on the real host or release build stay open until then. No gate has passed yet.

**Gate A** for the SEC-2 / SEC-4A / SEC-4B / SEC-4C work (base `c6b8c9e`):

| # | Status |
|---|---|
| A1 | Met: threat model updated 2026-10-11 |
| A2 | Partly: regex scan of the diff for key and token patterns found 0; `gitleaks` is not installed here, so run it in the gate B record |
| A3 | Met: only documentation-range IPs, version strings, loopback and `.invalid` / public upstream hosts in the diff |
| A4 | Met: new keys in `.env.example` have dev defaults or placeholders only |
| A5 | Met for web (`npm audit --omit=dev` 0); Android lockfile and verification metadata unchanged since 2026-10-08 |
| A6 | Met for the dev gateway (0 addresses or query strings in its log); staging read in B15 |
| A7 | Met: no Android manifest change in the diff |
| A8 | Met: route limits and `RoutingBusy` in openapi 0.7.0; tiles path has no 429 in the contract (see B9) |
| A9 | Not checked by the security-engineer (N24 is a BA glossary item) |
| A10 | Met: register updated; new findings BE-22 (low), BE-23 and BE-24 (info) |

**Gate B** (first staging deploy):

| # | Status | Still needed (owner) |
|---|---|---|
| B1 | Met in the repo (accepted low residuals: root builders, Photon `/tmp` exec, Kuma capabilities) | `docker inspect` on the host (BE) |
| B2 | Met | none |
| B3 | Open | Trivy/Grype scan of every pinned image, report attached (BE, SE) |
| B4 | Config met (TLS 1.2/1.3, HSTS) | `testssl.sh` and `curl -sI` on the host (BE operator) |
| B5, B6 | Config in bootstrap | Operator checks on the host (BE) |
| B7 | **Open** (BE-17) | Mode 600 and owner check in `deploy.sh` (BE) |
| B8 | Open (BE-10) | Restore drill; host cannot delete backups (BE) |
| B9 | Partly: /64 keys and pack limits met; **tiles connection cap and BE-14 open** | Contract amendment for 429 on tiles (AR) and a measured cap (BE), or a PO exception for the tester-only host; BE-14 timeouts and body limit (BE) |
| B10 | Partly: limits and cap active | Cap values from the staging measurement; explicit `send_timeout` on `/v1/route` (BE, with BE-14) |
| B11 | Met | none |
| B12 | Partly: `deploy.sh` signature check met; **SP-2 ruleset open (high)**; BE-22 open (low) | Ruleset for `master` and `v*` tags (PO via OR); tag-name check (BE); PO creates the signing key and the operator installs the allowed-signers file before the second deploy |
| B13 | **Open** (BE-11): bootstrap holds the Docker packages, so they are never upgraded automatically | Include Docker in unattended upgrades, or a documented monthly upgrade with a PO exception (BE) |
| B14, B15 | On the host | Operator and QA checks (BE, QA) |
| B16 | Open (BE-16, low) | Backlog unless the PO wants it before staging (BE) |


**Gate C** (public release), only the items whose state changed: C3 route limits met in the repo (UI text check by QA/BA still needed); C8 met in the repo (re-run on the release build); C9 partly (CSP meta verified in all three builds on 2026-10-11; web-root headers are a PO action); C13 and C16 met on the working branch but **not on the default branch** (GitHub serves `SECURITY.md` and issue forms from `master`); C10 not met (open medium findings: BE-3 residual, BE-6, SP-3, SP-4, SP-5 until merged, SP-6, SP-7, SP-10, SP-11, WS-1; high: SP-2). Every other gate C item is unchanged and open.

## Gate status 2026-10-13 (NAV-016)

From the NAV-016 Verify review, `audits/2026-10-13-NAV-016.md` (base `653db4d`, head `4a64023`). Findings: medium 1 (VP-8), low 5 (VP-1 to VP-5), info 5. Nothing critical or high, so the story is not blocked. VP-8 blocks gate C through C24.

**Gate A** for NAV-016:

| # | Status |
|---|---|
| A1 | Met: threat model updated 2026-10-13 (AS7, AS9, TB10, rows 7.4, 8.8, 10.1–10.7) |
| A2 | Partly: regex scan of the diff plus a token-prefix search of the tree, every ref and every git object → 0; `gitleaks` not installed |
| A3 | Met: only loopback, `.invalid` and the public vendor API URL |
| A4 | Met: `CHIMEGE_API_TOKEN=`, `NAV_VOICE_PACK_OUT=`, `NAV_VOICE_PACK_CACHE=` in `backend/.env.example`, all empty |
| A5 | Met: pip hashes equal PyPI's, image digests equal Docker Hub's, OSV clean for the six Python packages and `androidx.test:runner:1.5.0`, runner pins equal Google Maven's |
| A6 | Met: pack reason codes only in debug logs; generator logs hold no coordinates or token (sentinel test) |
| A7 | Met: no Android manifest change |
| A8 | Not applicable: no HTTP operation added (`openapi.yaml` unchanged) |
| A9 | Not checked by the security-engineer (glossary is a BA item) |
| A10 | Met: findings recorded in the NAV-016 review |

**Gate C** voice pack items: C20, C21 and C22 are open (they are the AC 53 gates). C23 is met in the repo (no audio or pack file on any ref, CI never calls the vendor; VP-3 asks for repo-wide prevention). C24 is **open** until the PO confirms the token rotation (VP-8).

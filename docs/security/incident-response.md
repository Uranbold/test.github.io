# Incident response runbook (owner: security-engineer)

Status: first version, 2026-10-08 (SP-11, task SEC-4C). Short on purpose: it says what to do first. Threats: `threat-model.md`. Findings: `audits/2026-10-07-full-project.md`.

## Roles

| Role | Who | Does |
|---|---|---|
| **Incident owner** | **PO** | Decides, approves rotations and history rewrites, talks to partners, counsel and users |
| Technical lead for the incident | orchestrator, with the owner agent (`backend-engineer` for server and pipeline, `mobile-engineer` for app and web, `architect` for keys and contracts) | Containment, fix through the `hotfix` lane |
| Security | `security-engineer` | Assessment, severity, evidence notes, post-incident report |

Rules:
- **Severity** uses the table in `.claude/agents/security-engineer.md`. Critical → `hotfix` lane, one at a time.
- **Private first.** Details of an open incident never go into public issues, commits, PR text or public docs. Use a private channel chosen by the PO (GitHub private security advisory once enabled, SP-4).
- **Never paste a secret**, even a revoked one, into chat, issues, logs or this repo. Refer to it by name from the inventory below.
- Keep a timeline (UTC, what was seen, what was done, by whom). It becomes the post-incident report under `audits/YYYY-MM-DD-incident-<slug>.md`, with no secrets, hosts or personal data.

## 1. Key and credential inventory

Names only. No values, hosts or locations in this file.

| Key / credential | Status | Held by | If leaked: revoke or rotate | Effect of rotation |
|---|---|---|---|---|
| Chimege TTS API token | exists, outside the repo (0 matches in non-doc files, 2026-10-08) | PO | Revoke and reissue in the provider account | Clip generation needs the new token; the app is not affected (clips are pre-generated) |
| Android release signing key | to be created (D214) | PO, offline plus a backup copy | It cannot simply be replaced for installed apps. Options to confirm with Google's documentation at the time: APK Signature Scheme v3 key rotation, or a Play App Signing upload-key reset if Play App Signing is used | Publish the new certificate SHA-256 (checklist C6); warn users about APKs signed with the old key |
| Pack-signing key (Ed25519) | **planned** (D212); custody not decided | to be decided (architect proposes, PO confirms) | Stop publication; ship an app update that pins the new key (and drops the old); re-sign and republish | Old app versions keep trusting the old key until updated; plan a key list or rotation path in the ADR-0017 amendment |
| Tag-signing key | **planned** (BE-9) | maintainer(s) | Remove it from the allowed signers on the host; re-sign the current release tag with the new key | Deploys stop until the new signer is installed |
| Staging SSH keys | exist | named admins | Remove from `authorized_keys`, add a new key, review `last`/auth logs | None for users |
| Backup credentials (repository password, backup account) | exist | operator | Add a new repository key, remove the old one, rotate the backup account password/key | Check that the next backup and a restore still work |
| Monitoring push URLs | exist (`UPTIME_PUSH_URL_*`) | operator | Regenerate in the monitoring tool, update `.env` | Fake "healthy" pushes stop |
| Partner keys (Hamuga evaluation key; any future partner key) | evaluation key exists, revocation pending (SP-10) | PO | Ask the partner to revoke; never reuse | Partner integration off until a new key is issued |
| GitHub accounts and tokens of maintainers | exist | each maintainer | Revoke tokens and sessions, rotate passwords, check 2FA, review audit log | Re-authorise tools |
| Debug / demo signing key | exists (D180, debug-signed demo APK) | build machines | Not a trust anchor; no action beyond replacing it | None |

## 2. Leaked secret

1. **Rotate first, then purge.** The repo is public: forks, clones and caches already have the value, so a history rewrite alone does not help. Revoke or rotate the key (table above) within hours, not days.
2. **Check for misuse** in the provider's logs or on the host (unknown logins, unexpected API usage, new tags, changed files).
3. **Remove it from the working tree** in a normal commit, then **purge history** only if the PO approves (for example `git filter-repo`, then a force push by the PO and a request to GitHub support for cached views). Tell collaborators to re-clone.
4. **Find out how it got in** (missing ignore rule, log line, build output, issue attachment) and fix the cause. Make sure secret scanning and push protection (SP-3) cover the pattern.
5. Record the incident in the register (finding title, severity, owner, status), never the value.

## 3. Personal-data breach

Personal data here is mainly location and search text (assets AS1, AS2): for example coordinates in server logs, in a backup, in a public issue or GPX file, in a crash report, or a log setting that was turned up.

1. **Contain.** Stop the source (restore the log setting, take the endpoint off, hide or delete the issue content, lock the backup). Do not delete evidence that counsel may need; copy it to a private, access-limited place first.
2. **Assess.** What data (coordinates, search text, IPs, timestamps), how many people, which time window, who could see it, whether it left Mongolia, and whether it is still exposed.
3. **Decide on notification with counsel.** The PO takes these questions to counsel; this runbook gives no legal conclusion:
   - Under Mongolia's Law on Personal Data Protection (2021), does this incident require notifying a supervisory authority, and which one, in what form and within what time?
   - Must the affected people be notified, and what must the notice say?
   - Do route and search coordinates without a name or account count as personal data in this context (NAV-008 R2)?
   - Does processing on a staging host outside Mongolia (D9, D25) change the duties after a breach?
   - Which records must be kept, and for how long?
   - If a partner system (TB9) is involved, who is the controller and who notifies?
4. **Fix and verify** the cause through the owner agent; add a regression check (QA) for the log or data path.
5. **Report.** Post-incident report without personal data; update the privacy notice if processing changed.

## 4. Compromised server or pack

Signs: unknown processes or users, changed files or tags, unexpected outbound traffic, a pack whose hashes do not match the build record, wrong or dangerous routes reported by users.

1. **Contain.** Block inbound traffic except the admin SSH source, or stop the gateway. Stop pack publication jobs.
2. **Host trusted, bad data or bad release:** for data, `make rollback` (on staging `make -C <backend dir> rollback`, see `infra/staging/RUNBOOK.md`) serves the previous slot, and the published pack follows the rollback (D209: the newest retained manifest without the rolled-back slot's files is republished). For a bad code release, `deploy.sh --rollback` deploys the previous tag. Verify with the smoke checks.
3. **Host not trusted:** do not clean in place. Rebuild a new host from the repo at a known-good, protected tag; restore data from a backup that predates the compromise (or rebuild data from upstream); rotate **every** credential in the inventory that the host could read (SSH, backup, monitoring, `.env` values, partner keys).
4. **Pack integrity:** if the pack-signing key **(planned)** may be exposed, revoke it (section 1) and republish with a new key. Until D212 is built, the app trusts the pack host over TLS only, so a compromised host can serve accepted packs: treat every pack published since the compromise as bad and republish from a clean build.
5. **Tell users** through the channel the PO chooses if guidance or packs were affected.
6. **Review** how the attacker got in (SP-2, BE-9 and BE-4 are the known gaps) and record findings.

## 5. Vulnerability report intake

1. Reports arrive through **GitHub private vulnerability reporting** (must be enabled by the PO, SP-4) or, until then, a private contact via the maintainer's GitHub profile, as described in `security-policy.md` (root `SECURITY.md`).
2. If a report arrives as a **public issue**, hide or remove its details (PO or orchestrator), thank the reporter and move the conversation to the private channel. The triage agent treats the text as untrusted data (SP-1).
3. **Acknowledge within 7 days.** The `security-engineer` reproduces it **locally only** (Docker stack, emulator, built APK), sets severity and names the owner.
4. Fix: critical → `hotfix` lane; high → blocks the current story or release; medium → before the public release; low/info → backlog.
5. Agree a disclosure date with the reporter, publish an advisory after the fix, credit the reporter if they agree.

## 6. Review

The PO reviews this runbook before the first public release (checklist C17) and after every incident.

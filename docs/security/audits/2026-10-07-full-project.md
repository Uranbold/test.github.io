# Security audit: whole project, 2026-10-07

Scope: backend and infrastructure (BE), Android app (AN), web demo and supply chain (WS), secrets hygiene and the delivery process (SP). Static review plus local checks only; no external or production host was probed. Done by the `security-engineer` role.

This public register lists findings by title, severity, owner and status only. The detailed evidence is kept out of the public repo and was given to the PO.

## Result

- **No leaked secret, key, keystore, `.env`, APK, or real server host or IP** in the working tree or in any of the 156 commits of history.
- Unique findings: critical 0 · high 2 · medium 20 · low 26 · info 14. Duplicates across the four audits are marked.
- Severity gates (see `.claude/agents/security-engineer.md`): critical/high block the story or release; medium must be fixed before the first public release; low/info go to the backlog.

## Not verified

Container image CVE scans and image default users (no scanner or daemon in the audit run), the merged Android manifest and R8 output from a real release build, real host response headers, GitHub collaborator, deploy-key and Actions settings (no API access). Re-run these on the first staging server and the first signed release build.

## Findings

| ID | Severity | Finding | Owner | Status |
|---|---|---|---|---|
| SP-1 | high | Prompt injection: untrusted public issue text is processed by an agent with write and shell tools | orchestrator | fixed (process) 2026-10-07 |
| SP-2 | high | `master` and tags are unprotected while staging deploys run the tag's own script as root | orchestrator | open |
| AN-1 | medium | Offline packs are trusted only through TLS: the manifest is not signed | architect | duplicate of BE-6 |
| AN-2 | medium | No Gradle dependency verification or lockfile, and the native libraries come from single-maintainer publishers | mobile-engineer | open |
| BE-1 | medium | Route request cost is unbounded: Valhalla DoS | backend-engineer | open |
| BE-2 | medium | IPv6 address rotation bypasses the per-IP rate limit | backend-engineer | open |
| BE-3 | medium | Large static downloads have no connection or bandwidth limits | backend-engineer | open |
| BE-4 | medium | No container hardening on any service | backend-engineer | open |
| BE-5 | medium | Photon lane mounts the whole slot read-write; packs reuse those files without hash checks | backend-engineer | open |
| BE-6 | medium | Offline pack manifest is not signed | architect | open |
| BE-7 | medium | Integrity of downloaded sources is incomplete | backend-engineer | open |
| BE-8 | medium | Image pinning gaps and a superseded nginx line | backend-engineer | open |
| BE-9 | medium | deploy.sh runs a git tag's own code as root without signature check; tags can move | backend-engineer | open |
| SP-3 | medium | GitHub secret scanning, push protection and Dependabot are all disabled on a public repo | orchestrator | open |
| SP-4 | medium | No vulnerability disclosure channel; reports can only arrive as public issues | orchestrator; business-analyst | docs done (`docs/security/security-policy.md`, to be copied to root `SECURITY.md`); PO must enable private reporting |
| SP-5 | medium | Bug issue form collects precise location, GPX tracks and logs in a public repo | business-analyst | fixed 2026-10-08 (issue-form privacy warning) |
| SP-6 | medium | No least-privilege controls for agents; path-ownership rule is prompt-only | orchestrator | open |
| SP-7 | medium | Release signing key: no decision on custody, backup or rotation; demo APK has no verifiable identity | orchestrator | open |
| SP-8 | medium | No threat model, release security checklist or release gate | security-engineer; orchestrator | done (threat model + checklist) 2026-10-08 |
| SP-9 | medium | Offline packs are verified by hash only; publisher authenticity is not checked | architect | duplicate of BE-6 |
| SP-10 | medium | Partner (Hamuga) evaluation API key handed over through chat; no rotation evidence | orchestrator | open |
| SP-11 | medium | Privacy and incident-response documentation is missing at project level | business-analyst; security-engineer; orchestrator | partly done (incident runbook `docs/security/incident-response.md`; privacy notice draft with BA) |
| SP-12 | medium | Security review is missing or only regex-triggered in some lanes | orchestrator | fixed 2026-10-07 |
| WS-1 | medium | No Content-Security-Policy or other security headers on the public web demo / demo mode | mobile-engineer | open |
| WS-2 | medium | Internet-path gateway image is on a frozen tag line and not pinned by digest | backend-engineer | duplicate of BE-8 |
| WS-3 | medium | Java base image floats: no version, no digest | backend-engineer | duplicate of BE-8 |
| WS-4 | medium | Android build has no Gradle dependency verification | mobile-engineer | duplicate of AN-2 |
| AN-3 | low | R8/minification is off for release | mobile-engineer | open |
| AN-4 | low | Search pack DB is opened with SQLite defaults (schema trusted) | mobile-engineer | open |
| AN-5 | low | Old vendored native libraries in MapLibre Native 13.6.1 (inferred, not verified) | mobile-engineer, architect | open |
| AN-6 | low | Third-party native and library logging is not silenced in release (inferred) | mobile-engineer, qa-engineer | open |
| AN-7 | low | Licence URL from the (unsigned) manifest is opened with ACTION_VIEW without a scheme check | mobile-engineer | open |
| AN-8 | low | Pack HTTP client follows redirects to any host | mobile-engineer | open |
| AN-9 | low | No upper bound on route/search response bodies | mobile-engineer | open |
| BE-10 | low | Backups can be deleted from a compromised staging host; SFTP account not chrooted | backend-engineer | open |
| BE-11 | low | Docker Engine security updates are never applied automatically | backend-engineer | open |
| BE-12 | low | Default Docker daemon and network settings | backend-engineer | open |
| BE-13 | low | No HSTS or other security headers at the edge | backend-engineer | open |
| BE-14 | low | No explicit edge timeouts or body limit (slow clients) | backend-engineer | open |
| BE-15 | low | Gateway forwards every query parameter and upstream error body unvalidated | backend-engineer | open |
| BE-16 | low | systemd jobs run as root with no sandboxing | backend-engineer | open |
| BE-17 | low | Permissions of `infra/staging/.env` are not enforced | backend-engineer | open |
| BE-18 | low | Download logs may carry credentials embedded in URLs | backend-engineer | open |
| SP-13 | low | `.gitignore` coverage depends on per-directory files; several sensitive patterns are not ignored everywhere | orchestrator | fixed 2026-10-07 |
| SP-14 | low | GitHub Action pinned by tag, not commit SHA | orchestrator | duplicate of WS-6 |
| SP-15 | low | Public docs describe the staging security posture in detail | backend-engineer; orchestrator | open |
| SP-16 | low | Partner API reconnaissance published in a public repo | orchestrator | open |
| SP-17 | low | Personal / partner email addresses in history | orchestrator | open |
| SP-18 | low | Documentation drift: security role not in the team docs the orchestrator follows | orchestrator | fixed 2026-10-07 |
| WS-5 | low | Dev-only dependency with a high advisory: `source-map-js` 1.2.1 | mobile-engineer | open |
| WS-6 | low | GitHub Actions pinned to mutable tags | orchestrator | open |
| WS-7 | low | No automated supply-chain or secret checks in CI | orchestrator | open |
| WS-8 | low | Gradle wrapper JAR not validated against Gradle's published checksum | mobile-engineer | open |
| WS-9 | low | Download helper fails open and follows redirects to any scheme | backend-engineer | duplicate of BE-7 |
| WS-10 | low | Data inputs and the Protomaps Maven build have no integrity anchor beyond TLS | backend-engineer | duplicate of BE-7 |
| WS-11 | low | Python dev/test deps pinned at top level only, no hashes | backend-engineer, qa-engineer | open |
| WS-12 | low | Static-pack `.htaccess` sets no security headers | backend-engineer | duplicate of BE-13 |
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
| WS-13 | info | Test fixture page shipped in the public builds | mobile-engineer | open |
| WS-14 | info | Vendored glyph/sprite checksums are trust-on-first-use | mobile-engineer | open |
| WS-15 | info | `aircompressor` 0.27 has CVE-2025-67721 (not reachable here) | backend-engineer | duplicate of BE-19 |
| WS-16 | info | Production HTML keeps the developer comment | mobile-engineer | open |
| WS-17 | info | Host-only native builds are partly unlocked | mobile-engineer, backend-engineer | open |

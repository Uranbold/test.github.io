---
name: nav-team-rules
description: "Use at the start of every task in OSM Navigation Mongolia: path ownership, contract-first API, story traceability, language policy, attribution and secrets rules"
---

# Team rules (summary of the repository's CLAUDE.md, which is authoritative)

1. **Write only your own paths.**

   | Agent | Owns |
   |---|---|
   | triage-lead | `docs/triage/**`, issue labels and comments |
   | architect | `docs/architecture/**` (ADRs, `api/openapi.yaml`, spikes) |
   | business-analyst | `docs/requirements/**` (stories, backlog, glossary, decisions) |
   | ux-designer | `docs/design/**` |
   | backend-engineer | `backend/**`, `infra/**` |
   | mobile-engineer | `mobile/**`, `web/**` |
   | qa-engineer | `tests/**`, `docs/qa/**` |

   Need something elsewhere? Put it in `requests_to_other_agents` in your handoff.
2. **Contract first:** `docs/architecture/api/openapi.yaml` is owned by the architect. Backend implements it and clients consume it.
3. **Trace to a story:** stories live in `docs/requirements/stories/NAV-XXX-<slug>.md`, each with Given/When/Then AC, a Traceability table and a Change log.
4. **Don't invent product decisions.** Put them in `open_questions` with options and a recommendation. The board decides. Decisions go in `docs/requirements/decisions.md`.
5. **Verify before handing off:** run the build, tests or linters you touched, and say plainly what you did not verify.
6. **Language:** the app UI, voice and map labels are Mongolian first, English second. Code, commits, ADRs and test plans are English. Every user-facing Mongolian string must come from `docs/requirements/glossary.md` (skill `mongolian-glossary`).
7. **"© OpenStreetMap contributors"** must be visible on every map screen.
8. **No secrets** (keys, passwords, private SSH keys, server IPs) in the repo or in issues. Config keys go in `.env.example`.
9. **Shared dev stack:** only the backend engineer starts, stops or rebuilds `backend/` (docker compose). Everyone else waits for `http://localhost:8080/health` to return 200.

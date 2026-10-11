# Project: OSM Navigation (working name)

A Google-Maps-style navigation product built on OpenStreetMap data and open-source components. The primary market is Mongolia (Ulaanbaatar first). UI language is Mongolian first, English second.

- Research: `docs/osm-navigation-research.md`
- Chosen stack: `docs/architecture/adr/0001-tech-stack.md`
- Agent team design: `docs/team/agent-architecture.md`

## Stack (decided, see ADR-0001)

| Layer | Technology |
|---|---|
| Map data | OpenStreetMap (Geofabrik Mongolia extract) |
| Tiles | Planetiler → Protomaps PMTiles, styled for MapLibre |
| Routing | Valhalla (costing per request, `language=mn-MN`) |
| Search | Photon (autocomplete) + Nominatim (reverse/structured) |
| Navigation SDK | Ferrostar (Rust core, Compose / SwiftUI / Web) |
| Rendering | MapLibre Native (Android/iOS), MapLibre GL JS (web) |
| Backend glue | API gateway + own services, all in Docker Compose |

## Agent team

The main session is the **orchestrator**. It breaks work down, calls the subagents in `.claude/agents/`, and passes files between them. Subagents cannot call each other; all handoffs happen through files in this repo.

| Agent | Role | Owns (writes) |
|---|---|---|
| `triage-lead` | Classifies every incoming item, proposes priority, picks the lane | `docs/triage/**`, issue labels/comments |
| `architect` | Tech lead, ADRs, API contracts, integration review, technical spikes | `docs/architecture/**` |
| `business-analyst` | Requirements, user stories, acceptance criteria | `docs/requirements/**` |
| `ux-designer` | User flows, screen specs, design tokens, map style spec | `docs/design/**` |
| `backend-engineer` | Valhalla/Photon/tiles services, API gateway, data pipeline | `backend/**`, `infra/**` |
| `mobile-engineer` | Android (Kotlin/Compose), iOS (SwiftUI), web demo | `mobile/**`, `web/**` |
| `qa-engineer` | Test plans, GPX route simulation, E2E and API tests | `tests/**`, `docs/qa/**` |
| `security-engineer` | Threat model, security and privacy review in every lane's Verify stage, whole-project audits, release security checklist | `docs/security/**` |

## Intake procedure (orchestrator)

Every new item goes through **triage first** (details: `docs/team/intake-and-triage-flow.md`):

1. A new feature idea, change, bug, question or tech-debt item arrives, as a GitHub issue (issue forms in `.github/ISSUE_TEMPLATE/`) or from the PO in chat. Run the **`triage`** workflow with `{issue}` or `{text}`.
2. `disposition=needs_info` → ask the reporter the listed questions. `close` → close with the reason. `lane=osm-data` → create an OSM mapping task for a human mapper (never edit OSM automatically).
3. Otherwise show the PO the triage proposal (`summary_for_po`) and **ask them to confirm priority/class**. Only the PO sets `prio:*`.
4. Run the lane workflow with the `next.args` from triage:

| Lane | Workflow | Notes |
|---|---|---|
| 🚨 Hotfix | `hotfix` | S1 only, one at a time. Afterwards, create the 48 h follow-up item it returns |
| 🐞 Bug | `bug-fix` | QA reproduces with a failing test first. `works_as_designed` → re-triage as a change |
| 🔁 Change | `change-request` | Run 1 returns the impact → **PO approves** → run 2 with `rerun_with` (includes `approvedImpact`) |
| ✨ Feature / tech debt | `feature-delivery` | BA → (UX ∥ Architect) → (Backend ∥ Mobile) → (QA ∥ architect review ∥ security review) → fix loop |
| 🔬 Spike | `spike` | Result + skeptic review go to the PO; follow-up items go back through triage |

5. **Mid-flight changes:** if the PO changes a story that is in progress, don't pass it to the running agents. Triage it as a change request and ask the PO: finish first (default), restart from the affected stage, or drop.
6. Defects QA finds while verifying a story stay in that story's fix loop. Only out-of-scope defects become new bug issues.
7. After a lane finishes, record the outcome in `docs/triage/log.md` (PO decision column) and on the issue, then commit.

## Language policy

| What | Language |
|---|---|
| App UI, voice guidance, map labels | **Mongolian first**, English second |
| Issue forms, triage comments, summaries and questions for the PO | **Mongolian** (technical terms and IDs stay as they are) |
| Chat with the PO | The language the PO writes in |
| Agent instructions, workflows, code, API, commits, ADRs, test plans | **English** |
| Stories and acceptance criteria | English, with user-facing Mongolian text quoted **exactly**, e.g. «300 м-т баруун тийш эргэнэ үү» |
| Glossary (`docs/requirements/glossary.md`) | **Bilingual**, and **binding**: one approved Mongolian term per concept |

Every agent that writes user-facing Mongolian (UI copy, voice prompts, test expectations, triage comments) **must use the glossary terms**. If a term is missing, don't invent one. Request it from the business-analyst via `requests_to_other_agents`. Terms marked "needs native review" are provisional until a native speaker approves them.

## Rules for every agent

1. **Only write inside the paths you own.** If you need a change in another area, describe it in your handoff under `requests_to_other_agents`. Don't edit that area yourself.
2. **Contract first.** The HTTP API between backend and clients is defined in `docs/architecture/api/openapi.yaml` (owner: architect). Backend implements it and mobile consumes it. Neither side changes it on its own.
3. **Trace to a story.** Every piece of work references a story ID such as `NAV-001`. Stories live in `docs/requirements/stories/NAV-XXX-<slug>.md`.
4. **Don't invent product decisions.** If a requirement is unclear, list it under `open_questions`. The orchestrator asks the user.
5. **End every task with a handoff block** (format in `docs/templates/handoff.md`): what you did, the files you changed, how you verified it, open questions, and requests to other agents.
6. **Verify before you hand off.** Run the build, tests or linters for anything you changed. Say plainly what you did not verify.
7. **Localisation:** all user-facing strings go through resource files (`mn` default, `en`). Never hard-code them. Mongolian wording must match the glossary. Map labels use `name:mn` → `name` → `name:en`.
8. **OSM attribution** "© OpenStreetMap contributors" must be visible on every map screen.
9. No secrets in the repo. Use `.env.example` for configuration keys.
10. **Security findings** from the `security-engineer` are fixed by the owner of the code. `critical`/`high` block the story or release; `medium` must be fixed before a public release.

## Repository layout

```
docs/
  osm-navigation-research.md
  team/agent-architecture.md     # this team's design
  requirements/                  # BA: PRD, stories, glossary
  design/                        # UX: flows, screens, tokens, map style spec
  architecture/                  # Architect: ADRs, openapi.yaml, diagrams
  qa/                            # QA: test plans, reports
  triage/                        # triage-lead: triage log
  templates/                     # story, ADR, screen spec, handoff templates
backend/                         # services + docker-compose
mobile/android  mobile/ios       # native apps
web/                             # MapLibre GL JS demo / planner
tests/                           # e2e, api, GPX fixtures
```

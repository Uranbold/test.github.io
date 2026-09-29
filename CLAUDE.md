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
| `architect` | Tech lead, ADRs, API contracts, integration review | `docs/architecture/**` |
| `business-analyst` | Requirements, user stories, acceptance criteria | `docs/requirements/**` |
| `ux-designer` | User flows, screen specs, design tokens, map style spec | `docs/design/**` |
| `backend-engineer` | Valhalla/Photon/tiles services, API gateway, data pipeline | `backend/**`, `infra/**` |
| `mobile-engineer` | Android (Kotlin/Compose), iOS (SwiftUI), web demo | `mobile/**`, `web/**` |
| `qa-engineer` | Test plans, GPX route simulation, E2E and API tests | `tests/**`, `docs/qa/**` |

The standard flow for a feature is **BA → (UX ∥ Architect) → (Backend ∥ Mobile) → (QA ∥ Architect review) → fix loop**. The saved workflow `.claude/workflows/feature-delivery.js` runs it.

## Rules for every agent

1. **Only write inside the paths you own.** If you need a change in another area, describe it in your handoff under `requests_to_other_agents`. Don't edit that area yourself.
2. **Contract first.** The HTTP API between backend and clients is defined in `docs/architecture/api/openapi.yaml` (owner: architect). Backend implements it and mobile consumes it. Neither side changes it on its own.
3. **Trace to a story.** Every piece of work references a story ID such as `NAV-001`. Stories live in `docs/requirements/stories/NAV-XXX-<slug>.md`.
4. **Don't invent product decisions.** If a requirement is unclear, list it under `open_questions`. The orchestrator asks the user.
5. **End every task with a handoff block** (format in `docs/templates/handoff.md`): what you did, the files you changed, how you verified it, open questions, and requests to other agents.
6. **Verify before you hand off.** Run the build, tests or linters for anything you changed. Say plainly what you did not verify.
7. **Localisation:** all user-facing strings go through resource files (`mn` default, `en`). Never hard-code them. Map labels use `name:mn` → `name` → `name:en`.
8. **OSM attribution** "© OpenStreetMap contributors" must be visible on every map screen.
9. No secrets in the repo. Use `.env.example` for configuration keys.

## Repository layout

```
docs/
  osm-navigation-research.md
  team/agent-architecture.md     # this team's design
  requirements/                  # BA: PRD, stories, glossary
  design/                        # UX: flows, screens, tokens, map style spec
  architecture/                  # Architect: ADRs, openapi.yaml, diagrams
  qa/                            # QA: test plans, reports
  templates/                     # story, ADR, screen spec, handoff templates
backend/                         # services + docker-compose
mobile/android  mobile/ios       # native apps
web/                             # MapLibre GL JS demo / planner
tests/                           # e2e, api, GPX fixtures
```

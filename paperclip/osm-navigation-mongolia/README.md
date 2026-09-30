# OSM Navigation Mongolia (Paperclip company)

> An [Agent Company](https://agentcompanies.io) package for [Paperclip](https://github.com/paperclipai/paperclip). It is the same agent team that builds this repository, packaged so you can run it from your local Paperclip board.

## What's inside

| Content | Count |
|---|---|
| Agents | 8 |
| Skills | 9 |
| Projects | 1 (9 open tasks) |

### Agents (org chart)

| Agent | Role | Reports to | Model |
|---|---|---|---|
| CEO | Orchestrator / delivery lead: intake, lanes, routing handoffs, board questions | — | claude-opus-5-5 |
| Triage Lead | Classifies every new item, proposes priority, picks the lane | ceo | claude-haiku-4-5 |
| Business Analyst | Stories, AC, backlog, glossary, decision log | ceo | claude-sonnet-5-5 |
| UX Designer | Flows, screen specs, tokens, map style | ceo | claude-sonnet-5-5 |
| Architect | ADRs, API contract, reviews, technical spikes | ceo | claude-opus-5-5 |
| QA Engineer | Test plans, reproduction, E2E/API/GPX tests | ceo | claude-sonnet-5-5 |
| Backend Engineer | Valhalla, Photon, tiles, gateway, pipeline, infra | architect | claude-sonnet-5-5 |
| Mobile Engineer | Android, iOS, web demo | architect | claude-sonnet-5-5 |

### Skills
`nav-team-rules`, `handoff-block`, `triage`, `feature-delivery`, `bug-fix`, `change-request`, `hotfix`, `spike`, `mongolian-glossary`

### Project tasks
Finish NAV-003, NAV-004 route preview, NAV-005 Android navigation, NAV-006 rebuild pipeline, NAV-007 voice panel, NAV-008 staging deploy, NAV-009 production hosting, NAV-010 Hamuga evaluation, and making the repo private.

## Import into your local Paperclip (http://127.0.0.1:3100)

1. **Get the repository on your computer** (this package and the project code the agents work on):
   ```bash
   git clone https://github.com/Uranbold/test.github.io.git osm-navigation
   cd osm-navigation
   git checkout claude/osm-navigation-research-97c7m0
   ```
2. **Preview the import** (nothing is changed):
   ```bash
   npx paperclipai company import ./paperclip/osm-navigation-mongolia \
     --api-base http://127.0.0.1:3100 --target new --dry-run
   ```
3. **Import:**
   ```bash
   npx paperclipai company import ./paperclip/osm-navigation-mongolia \
     --api-base http://127.0.0.1:3100 --target new
   ```
   If the CLI asks you to log in or connect first, run `npx paperclipai connect` and choose the board operator.
4. **Set the workspace:** in the Paperclip UI, open the project **OSM Navigation Mongolia** and set its workspace or working directory to your local clone (the `osm-navigation` folder). The agents use the `claude_local` adapter, so they run Claude Code in that folder and automatically read the repository's `CLAUDE.md` and `.claude/agents/`.
5. **Budgets:** set a monthly budget per agent before starting heartbeats. The CEO and Architect use the largest model.

To import straight from GitHub instead of a local path, point the command at this folder's URL on the branch, e.g. `npx paperclipai company import https://github.com/Uranbold/test.github.io/tree/claude/osm-navigation-research-97c7m0/paperclip/osm-navigation-mongolia --api-base http://127.0.0.1:3100 --target new`.

## Notes
- **Privacy:** the repository is currently public. See the task "Make the project repository private".
- **Secrets:** no keys or passwords are included. The only optional secret is `GH_TOKEN` for the Backend Engineer, which you set in Paperclip's secrets manager.
- **Shared backend:** only the Backend Engineer starts or stops the Docker stack in `backend/`. The NAV-001 stack needs Docker and about 3 GB of disk for the Mongolia data. It builds in about 9 minutes on first start.
- **Source of truth:** the agents' full instructions are also in `.claude/agents/*.md`, and the rules are in `CLAUDE.md`. Keep this package in sync when those change.

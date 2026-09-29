# Product backlog (owner: business-analyst)

Priority uses MoSCoW (`must` / `should` / `could` / `wont`). Phase follows the research roadmap (`docs/osm-navigation-research.md` §7): 0 PoC, 1 MVP mobile nav, 2 parity, 3 traffic, 4 extras. Size is S / M / L.

Status: `draft` (idea, not refined) → `ready` (story file with testable AC, no blocking questions) → `in-progress` → `done`.

## Phase 0: PoC

| ID | Title | Priority | Phase | Size | Design | Backend | Mobile | Status | Depends on | Story |
|---|---|---|---|---|---|---|---|---|---|---|
| NAV-001 | Backend stack up with one `docker compose up`: PMTiles + Valhalla (OSRM format, mn-MN) + Photon (search + reverse) behind a CORS gateway, with UB smoke tests | must | 0 | L | no | yes | no | ready | ADR-0001; OpenAPI paths from architect | [NAV-001](stories/NAV-001-backend-stack-docker-compose.md) |
| NAV-002 | Web demo: view map with Mongolian labels (`name:mn` → `name` → `name:en`) and OSM attribution | must | 0 | M | yes | no | yes | draft (proposed in team design §5, not refined) | NAV-001 | not written |
| NAV-003 | Search a place with Cyrillic/Latin input and autocomplete | must | 0 | M | yes | yes | yes | draft (proposed in team design §5, not refined) | NAV-001, NAV-002 | not written |
| NAV-004 | Route preview A→B with alternatives and mode tabs | must | 0 | M | yes | yes | yes | draft (proposed in team design §5, not refined) | NAV-001, NAV-002 | not written |

## Phase 1: MVP mobile nav (proposed, not refined)

| ID | Title | Priority | Phase | Size | Design | Backend | Mobile | Status | Depends on | Story |
|---|---|---|---|---|---|---|---|---|---|---|
| NAV-005 | Active navigation with Mongolian voice and off-route reroute (team design says Android first) | TBD | 1 | L | yes | yes | yes | draft | NAV-004; **user decision on platform order** | not written |
| NAV-006 | Daily OSM rebuild pipeline with blue/green switch | TBD | 1 | L | no | yes | no | draft | NAV-001; hosting decision | not written |

## Business risks tracked across stories (data quality)
- OSM address coverage in UB (`addr:*`, khoroo, ger-district plots) affects search and reverse (NAV-001 R9, NAV-003).
- `name:mn` / `name:en` coverage affects Latin search and English UI (NAV-001 R6).
- `maxspeed` coverage affects ETA quality (NAV-001 R8). Traffic comes in Phase 3.
- Valhalla `mn-MN` narrative quality affects voice/banner text (NAV-001 R7, NAV-005).
- `turn:lanes` / `destination` density affects lane guidance (Phase 2).

## Product decisions still owed by the user (from research §11)
These are not blocking for NAV-001. Each one blocks the stories noted.
1. Target platform order (Android / iOS / web). Blocks NAV-005 priority.
2. Coverage at launch (UB first vs all of Mongolia). Affects the production data source and NAV-006.
3. Offline navigation at launch: yes or no.
4. Traffic data partner (fleet / taxi / bus GPS). Blocks Phase 3.
5. Hosting (own servers in Mongolia vs cloud). Blocks NAV-006 and production SLAs.

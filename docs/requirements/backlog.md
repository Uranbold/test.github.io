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
| NAV-007 | Native-speaker review of Mongolian voice guidance and turn instructions: Valhalla `mn-MN` + app copy, real TTS on Android/iOS, fixes in our resource files, upstream PR to Valhalla | must (proposed, **PO to confirm**) | 1 | L | yes | yes | yes | draft (blocked on PO decisions: reviewer panel/budget, priority) | NAV-001 (Valhalla up); glossary; TTS harness or NAV-005 build for AC 9–11; architect decision on override location; should finish before NAV-005 external release | [NAV-007](stories/NAV-007-mongolian-voice-native-review.md) |

## Business risks tracked across stories (data quality)
- OSM address coverage in UB (`addr:*`, khoroo, ger-district plots) affects search and reverse (NAV-001 R9, NAV-003).
- `name:mn` / `name:en` coverage affects Latin search and English UI (NAV-001 R6).
- `maxspeed` coverage affects ETA quality (NAV-001 R8). Traffic comes in Phase 3.
- Valhalla `mn-MN` narrative quality affects voice/banner text (NAV-001 R7, NAV-005, NAV-007). The BA desk check of 3.9.0 found likely defects: English strings in `enter_roundabout_verbal`, a broken placeholder, a typo, a zero-width space, a wrong verb, and left/east ambiguity (NAV-007 F1–F10).
- Mongolian TTS voice availability on Android/iOS devices is unknown (NAV-007 AC 9, NAV-005). It is a device and platform risk, not an OSM risk.
- `turn:lanes` / `destination` density affects lane guidance (Phase 2).

## Product decisions still owed by the user (from research §11)
These are not blocking for NAV-001. Each one blocks the stories noted.
1. Target platform order (Android / iOS / web). Blocks NAV-005 priority.
2. Coverage at launch (UB first vs all of Mongolia). Affects the production data source and NAV-006.
3. Offline navigation at launch: yes or no.
4. Traffic data partner (fleet / taxi / bus GPS). Blocks Phase 3.
5. Hosting (own servers in Mongolia vs cloud). Blocks NAV-006 and production SLAs.
6. Native-speaker review panel and budget (paid drivers + editor vs volunteers vs crowd survey). Blocks NAV-007.
7. TTS fallback if devices lack a Mongolian voice (server neural TTS vs recorded prompts vs text-only). Decide after NAV-007 AC 9. Affects NAV-005.

## Cross-cutting: bilingual glossary
`glossary.md` is binding for all agents (one approved Mongolian term per concept). All Mongolian terms are `needs native review` until NAV-007 signs them off.

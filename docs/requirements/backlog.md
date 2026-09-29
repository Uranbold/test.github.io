# Product backlog (owner: business-analyst)

Priority uses MoSCoW (`must` / `should` / `could` / `wont`). Phase follows the research roadmap (`docs/osm-navigation-research.md` §7): 0 PoC, 1 MVP mobile nav, 2 parity, 3 traffic, 4 extras. Size is S / M / L.

Status: `draft` (idea, not refined) → `ready` (story file with testable AC, no blocking questions) → `in-progress` → `done`.

## Phase 0: PoC

| ID | Title | Priority | Phase | Size | Design | Backend | Mobile | Status | Depends on | Story |
|---|---|---|---|---|---|---|---|---|---|---|
| NAV-001 | Backend stack up with one `docker compose up`: PMTiles + Valhalla (OSRM format, mn-MN) + Photon (search + reverse) behind a CORS gateway, with UB smoke tests | must | 0 | L | no | yes | no | ready | ADR-0001; OpenAPI paths from architect | [NAV-001](stories/NAV-001-backend-stack-docker-compose.md) |
| NAV-002 | Web demo: view map with Mongolian labels (`name:mn` → `name` → `name:en`) and OSM attribution | must | 0 | M | yes | no | yes | draft (proposed in team design §5, not refined) | NAV-001; NAV-008 staging to share the demo outside the team | not written |
| NAV-003 | Search a place with Cyrillic/Latin input and autocomplete | must | 0 | M | yes | yes | yes | draft (proposed in team design §5, not refined) | NAV-001, NAV-002 | not written |
| NAV-004 | Route preview A→B with alternatives and mode tabs | must | 0 | M | yes | yes | yes | draft (proposed in team design §5, not refined) | NAV-001, NAV-002 | not written |
| NAV-008 | Choose and set up hosting for the backend, dev/staging first (architect spike on criteria C1–C10, PO decision, HTTPS staging reachable from Mongolian mobile networks with median RTT < 50 ms, NAV-001 smoke suite passing, Mongolia rebuild runs on the host, monitoring and backups). Production hosting is a later follow-up | must (proposed, **PO to confirm**) | 0 (spans into 1) | L | no | yes | no | draft (blocked on PO priority confirmation, NAV-001 acceptance, and the PO hosting decision (owed decision 5) before staging setup) | NAV-001 accepted; PO decision 5; unblocks NAV-002 sharing, NAV-005 phone testing, NAV-006, NAV-007 AC 9–12 | [NAV-008](stories/NAV-008-backend-hosting-staging.md) |

## Phase 1: MVP mobile nav (proposed, not refined)

| ID | Title | Priority | Phase | Size | Design | Backend | Mobile | Status | Depends on | Story |
|---|---|---|---|---|---|---|---|---|---|---|
| NAV-005 | Active navigation with Mongolian voice and off-route reroute (team design says Android first) | TBD | 1 | L | yes | yes | yes | draft | NAV-004; **user decision on platform order**; NAV-008 staging for testing on real phones | not written |
| NAV-006 | Daily OSM rebuild pipeline with blue/green switch | TBD | 1 | L | no | yes | no | draft | NAV-001; NAV-008 (hosting decision + host proven able to run the Mongolia rebuild, NAV-008 AC 15–16) | not written |
| NAV-007 | Native-speaker review of Mongolian voice guidance and turn instructions: Valhalla `mn-MN` + app copy, real TTS on Android/iOS, fixes in our resource files, upstream PR to Valhalla | must (proposed, **PO to confirm**) | 1 | L | yes | yes | yes | draft (blocked on PO decisions: reviewer panel/budget, priority) | NAV-001 (Valhalla up); glossary; TTS harness or NAV-005 build for AC 9–11; NAV-008 staging for device sessions (AC 9–12); architect decision on override location; should finish before NAV-005 external release | [NAV-007](stories/NAV-007-mongolian-voice-native-review.md) |

## Business risks tracked across stories (data quality)
- OSM address coverage in UB (`addr:*`, khoroo, ger-district plots) affects search and reverse (NAV-001 R9, NAV-003).
- `name:mn` / `name:en` coverage affects Latin search and English UI (NAV-001 R6).
- `maxspeed` coverage affects ETA quality (NAV-001 R8). Traffic comes in Phase 3.
- Valhalla `mn-MN` narrative quality affects voice/banner text (NAV-001 R7, NAV-005, NAV-007). The BA desk check of 3.9.0 found likely defects: English strings in `enter_roundabout_verbal`, a broken placeholder, a typo, a zero-width space, a wrong verb, and left/east ambiguity (NAV-007 F1–F10).
- Mongolian TTS voice availability on Android/iOS devices is unknown (NAV-007 AC 9, NAV-005). It is a device and platform risk, not an OSM risk.
- `turn:lanes` / `destination` density affects lane guidance (Phase 2).
- Once staging is public (NAV-008), outside testers will see all of the above data gaps directly. Tell testers what is known to be weak (NAV-008 R7).

## Product decisions still owed by the user (from research §11)
These are not blocking for NAV-001. Each one blocks the stories noted.
1. Target platform order (Android / iOS / web). Blocks NAV-005 priority.
2. Coverage at launch (UB first vs all of Mongolia). Affects the production data source and NAV-006.
3. Offline navigation at launch: yes or no.
4. Traffic data partner (fleet / taxi / bus GPS). Blocks Phase 3.
5. Hosting, now tracked as [NAV-008](stories/NAV-008-backend-hosting-staging.md). Blocks NAV-008 staging setup (AC 6 onwards), NAV-006, phone testing for NAV-002/NAV-005/NAV-007, and production SLAs. The PO decides after the NAV-008 architect spike (AC 1–4), which measures latency from UB mobile networks. Options:
   - (a) a VPS or cloud server hosted in Mongolia
   - (b) an international cloud region close to Mongolia (for example Hong Kong, Seoul, Tokyo, Singapore)
   - (c) the PO's own existing servers (the PO also mentioned possibly having own map data and Valhalla endpoints, still unconfirmed)
   - (d) hybrid: (b) for staging now, then (a) or (c) for production

   *BA recommendation: decide on the spike's measurements. Prefer (a) or (c) if ready within 10 working days and under 50 ms median RTT, otherwise (d).* The same decision covers budget ceiling, domain name, operator, staging data coverage and staging access (NAV-008 Open questions 1–6, each with options and a recommendation). **The PO also confirms NAV-008's priority** (BA proposes must / Phase 0, because it blocks phone testing; NAV-008 Open question 7).
6. Native-speaker review panel and budget (paid drivers + editor vs volunteers vs crowd survey). Blocks NAV-007.
7. TTS fallback if devices lack a Mongolian voice (server neural TTS vs recorded prompts vs text-only). Decide after NAV-007 AC 9. Affects NAV-005.

## Cross-cutting: bilingual glossary
`glossary.md` is binding for all agents (one approved Mongolian term per concept). The PO pre-reviewed all 90 terms on 2026-09-29: 83 are `PO-approved 2026-09-29 (panel pending)` and 7 are `PO-revised 2026-09-29 (panel pending)` («Замчлал», «эхлэх цэг» / «Миний байршил», «Байршил руу буцах», «ШТС» on screen, -дугаар/-дүгээр voice ordinals, «км/цаг»). No term is final until the NAV-007 panel signs it off.

# Product backlog (owner: business-analyst)

Priority uses MoSCoW (`must` / `should` / `could` / `wont`). Phase follows the research roadmap (`docs/osm-navigation-research.md` §7): 0 PoC, 1 MVP mobile nav, 2 parity, 3 traffic, 4 extras. Size is S / M / L.

Status: `draft` (idea, not refined) → `ready` (story file with testable AC, no blocking questions) → `in-progress` → `done`.

PO decisions are logged in [decisions.md](decisions.md) (IDs `D<n>`). The latest are D1–D24, D25–D26 (hosting) and D27–D33 (NAV-003), all from 2026-09-30.

## Phase 0: PoC

| ID | Title | Priority | Phase | Size | Design | Backend | Mobile | Status | Depends on | Story |
|---|---|---|---|---|---|---|---|---|---|---|
| NAV-001 | Backend stack up with one `docker compose up`: PMTiles + Valhalla (OSRM format, mn-MN) + Photon (search + reverse) behind a CORS gateway, with UB smoke tests | must | 0 | L | no | yes | no | implemented, verified except AC 12. AC 12 was decided by the PO on 2026-09-30 ([D1](decisions.md)): rebuild at `TILES_MAXZOOM=14` and re-measure against ≤ 200 MB, with ≤ 400 MB as the fallback. Backend rebuild and QA check update pending. AC 14 = 150 m is PO-confirmed ([D2](decisions.md)) | ADR-0001; OpenAPI paths from architect | [NAV-001](stories/NAV-001-backend-stack-docker-compose.md) |
| NAV-002 | Web demo: view map with Mongolian labels (`name:mn` → `name` → `name:en`) and OSM attribution | must | 0 | M | yes | no | yes | implemented. Glossary rows G1–G7 landed on 2026-09-29. Open questions decided on 2026-09-30 ([D11–D16](decisions.md)), and the AC already followed them. New AC 50 (supported browsers in `web/README.md`, D14) is pending | NAV-001; NAV-008 staging to share the demo outside the team | [NAV-002](stories/NAV-002-web-demo-map.md) |
| NAV-003 | Search a place with Cyrillic/Latin input and autocomplete in the web demo (results list, fly-to, place card, reverse for coordinates) | must | 0 | L | yes | no | yes | implemented, pending live verification. Open questions decided 2026-09-30 ([D27–D32](decisions.md)) and change approvals F1–F5 applied ([D33](decisions.md)); AC 41 wording aligned with the code and screen spec (2026-09-30). **Live verification pending:** QA run 4 against the shared dev stack (back up on 2026-09-30, after QA E1). Run 3 met every AC except AC 14 (tier B 11/15 = 73 %, defect D1); with F1 and F2 the expected tier B is 12–13 of 15 (threshold 80 %, fixed). Open question 7 (golden row A8 / AC 10): the architect's written request is in ADR-0006 › Consequences (name contains «Улсын их дэлгүүр», ≤ 400 m, top 3); the PO decides it through a change request | NAV-001, NAV-002; ADR-0006 | [NAV-003](stories/NAV-003-search-cyrillic-latin-autocomplete.md) |
| NAV-004 | Route preview A→B with alternatives and mode tabs | must | 0 | M | yes | yes | yes | draft (proposed in team design §5, not refined) | NAV-001, NAV-002 | not written |
| NAV-008 | Choose and set up hosting for the backend, dev/staging first (architect spike on criteria C1–C10, PO decision, HTTPS staging reachable from Mongolian mobile networks with RTT measured against the staging exception [D26](decisions.md), NAV-001 smoke suite passing, Mongolia rebuild runs on the host, monitoring and backups). Production hosting is NAV-009 | must (PO-confirmed 2026-09-30, [D3](decisions.md)) | 0 (spans into 1) | L | no | yes | no | ready. **Staging provider chosen 2026-09-30 ([D25](decisions.md)): Hostinger VPS KVM 4, Singapore**, with the C1 exception [D26](decisions.md) (about 100 ms expected; < 50 ms applies to production). Spike delivered (ADR-0005 `proposed`). The architect records the PO's alternative. **Host details (IP/hostname, SSH access) pending from the PO.** Staging setup (AC 6+) waits for those and for NAV-001 acceptance. Outside testers wait for the legal review (AC 24, D9, host abroad). ACs 2, 5, 8 and 22 changed 2026-09-30 | NAV-001 accepted (AC 6+); host details from the PO; constraints [D5–D9](decisions.md), [D25–D26](decisions.md); D10 partly answered; unblocks NAV-002 sharing, NAV-005 phone testing, NAV-006, NAV-007 AC 9–12 | [NAV-008](stories/NAV-008-backend-hosting-staging.md) |

## Phase 1: MVP mobile nav (proposed, not refined)

| ID | Title | Priority | Phase | Size | Design | Backend | Mobile | Status | Depends on | Story |
|---|---|---|---|---|---|---|---|---|---|---|
| NAV-005 | Active navigation with Mongolian voice and off-route reroute (Android first, then iOS: PO decision [D24](decisions.md)) | TBD (PO to set; platform order is no longer blocking) | 1 | L | yes | yes | yes | draft | NAV-004; NAV-008 staging for testing on real phones; NAV-007 before any external release ([D17](decisions.md)); TTS minimum on-screen text plus a chime until the fallback is decided ([D23](decisions.md)) | not written |
| NAV-006 | Daily OSM rebuild pipeline with blue/green switch | TBD | 1 | L | no | yes | no | draft | NAV-001; NAV-008 (hosting decision + host proven able to run the Mongolia rebuild, NAV-008 AC 15–16) | not written |
| NAV-007 | Native-speaker review of Mongolian voice guidance and turn instructions: Valhalla `mn-MN` + app copy, real TTS on Android/iOS, fixes in our resource files, upstream PR to Valhalla | must (PO-confirmed 2026-09-30, [D17](decisions.md)) | 1 | L | yes | yes | yes | ready (2026-09-30). Panel model decided ([D18–D20](decisions.md)). Recruitment (AC 5) starts once the PO names the partner and the panel budget (NAV-007 Open question 7) | NAV-001 (Valhalla up); glossary; TTS harness or NAV-005 build for AC 9–11 (Android first, D24); NAV-008 staging for device sessions (AC 9–12), plus NAV-008 AC 24 if the host is abroad (D9); architect decision on override location; must finish before the NAV-005 external release (D17) | [NAV-007](stories/NAV-007-mongolian-voice-native-review.md) |
| NAV-009 | Production hosting in Mongolia (ICT Group servers), with the phone latency trial (C1 median < 50 ms on ≥ 2 operators), data residency in Mongolia, and parity with the NAV-008 checks | TBD (BA proposal: must; PO to confirm) | 1 | L | no | yes | no | draft (2026-09-30, created after [D25](decisions.md)) | ICT Group host details from the PO ([D10](decisions.md)); NAV-008 IaC and verification plan; NAV-006 for blue/green; before any NAV-005 external release (BA proposal) | [NAV-009](stories/NAV-009-production-hosting-mongolia.md) |
| NAV-010 | Spike: evaluate Hamuga APIs (tiles, search, traffic, transit) as data sources: coverage, licence vs ODbL, search quality against Photon, traffic convertibility to Valhalla, GTFS | TBD (BA proposal: should; PO to confirm) | 1 | M | no | yes | no | draft (2026-09-30) | Hamuga docs and test access from the PO ([D10](decisions.md)); informs NAV-003, backlog owed decision 4 (traffic partner), Phase 4 transit | [NAV-010](stories/NAV-010-hamuga-api-evaluation-spike.md) |

## Business risks tracked across stories (data quality)
- OSM address coverage in UB (`addr:*`, khoroo, ger-district plots) affects search and reverse (NAV-001 R9, NAV-003).
- `name:mn` / `name:en` coverage affects Latin search and English UI (NAV-001 R6).
- `maxspeed` coverage affects ETA quality (NAV-001 R8). Traffic comes in Phase 3.
- Valhalla `mn-MN` narrative quality affects voice/banner text (NAV-001 R7, NAV-005, NAV-007). The BA desk check of 3.9.0 found likely defects: English strings in `enter_roundabout_verbal`, a broken placeholder, a typo, a zero-width space, a wrong verb, and left/east ambiguity (NAV-007 F1–F10).
- Mongolian TTS voice availability on Android/iOS devices is unknown (NAV-007 AC 9, NAV-005). It is a device and platform risk, not an OSM risk.
- `turn:lanes` / `destination` density affects lane guidance (Phase 2).
- Once staging is public (NAV-008), outside testers will see all of the above data gaps directly. Tell testers what is known to be weak (NAV-008 R7).

## Product decisions owed by the user (from research §11)
These are not blocking for NAV-001. Each open one blocks the stories noted. Resolved items link to [decisions.md](decisions.md).
1. ~~Target platform order (Android / iOS / web).~~ **Resolved 2026-09-30 ([D24](decisions.md)): Android first, then iOS.** The web client stays the Phase 0 demo and planner. NAV-005 priority is no longer blocked by this, but it is still to be set by the PO.
2. Coverage at launch (UB first vs all of Mongolia). Affects the production data source and NAV-006. **Open.**
3. Offline navigation at launch: yes or no. **Open.**
4. Traffic data partner (fleet / taxi / bus GPS). Blocks Phase 3. **Open.** Hamuga (ICT Group) is one candidate source to evaluate in spike [NAV-010](stories/NAV-010-hamuga-api-evaluation-spike.md). The spike supplies evidence only, and the PO chooses the partner.
5. ~~Hosting~~, tracked as [NAV-008](stories/NAV-008-backend-hosting-staging.md). **Resolved 2026-09-30 as a rule plus constraints ([D3–D9](decisions.md)):**
   - priority must / Phase 0 (D3)
   - the spike measures first; prefer an in-country or the PO's own server if the median RTT is < 50 ms, otherwise an international cloud region near Mongolia for staging (D4)
   - budget USD 50–150 per month for staging (D5)
   - a subdomain of the PO's company domain (D6)
   - a named PO-side operator, with infrastructure as code plus a runbook from the backend engineer (D7)
   - an unlisted public URL with rate limits, and auth decided before public release (D8)
   - legal review of the personal-data law before outside testers' traffic goes abroad (D9)

   **Provider decided 2026-09-30 ([D25](decisions.md), hybrid; replaces D4 for staging):**
   - staging runs on Hostinger VPS KVM 4 in Singapore
   - production runs in Mongolia, most likely on ICT Group's servers ([NAV-009](stories/NAV-009-production-hosting-mongolia.md), where the phone latency trial now sits)
   - staging has the C1 exception [D26](decisions.md): about 100 ms expected, testers only, no real users' personal data
   
   **Still to come:**
   - the architect records the PO's alternative in ADR-0005, and it is linked here (NAV-008 AC 5)
   - staging host details (IP/hostname, SSH access) from the PO, never in the repo
   
   **D10 partly answered:** ICT Group (Hamuga) has its own platform and servers; details and Hamuga docs are pending. **To confirm:**
   - staging data coverage (NAV-008 Open question 5, working assumption Geofabrik `mongolia-latest`)
   - whether D26 relaxes the D9 legal gate for informed outside testers ([decisions.md](decisions.md), Items to confirm 3; working assumption: no)
6. ~~Native-speaker review panel and budget.~~ **Resolved 2026-09-30 ([D18–D20](decisions.md)):** paid drivers through a taxi or delivery partner plus a paid editor, the PO is not a panel member, and the PO makes the final call on disagreements. **Still to name:** the partner and the panel budget amount (NAV-007 Open question 7). These block the start of NAV-007 AC 5.
7. TTS fallback if devices lack a Mongolian voice (server neural TTS vs recorded prompts vs text-only). **Partly resolved 2026-09-30 ([D23](decisions.md)):** decided after NAV-007 AC 9 (Android devices first). The minimum until then is on-screen text plus a chime. Affects NAV-005.

## Cross-cutting: bilingual glossary
`glossary.md` is binding for all agents (one approved Mongolian term per concept). The PO pre-reviewed all 90 terms on 2026-09-29: 83 are `PO-approved 2026-09-29 (panel pending)` and 7 are `PO-revised 2026-09-29 (panel pending)` («Замчлал», «эхлэх цэг» / «Миний байршил», «Байршил руу буцах», «ШТС» on screen, -дугаар/-дүгээр voice ordinals, «км/цаг»). No term is final until the NAV-007 panel signs it off. On 2026-09-29 the BA also added the 7 NAV-002 web-demo rows G1–G7: 6 `needs native review` and the ESA WorldCover credit as `n/a (not translated)`. On 2026-09-30 the PO approved the new row "Heading up" «Явах чиглэл дээшээ» (`PO-approved 2026-09-30 (panel pending)`, [D21](decisions.md)) and confirmed the Avoid wordings «навигаци», «Төвлөрүүлэх», «км/ц» and voice «хоёр дахь» ([D22](decisions.md)).

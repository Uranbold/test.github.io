---
id: NAV-010
title: "Spike: evaluate Hamuga APIs (tiles, search, traffic, transit) as data sources"
phase: 1          # BA proposal: run in Phase 1 so the findings can feed search (NAV-003), traffic (Phase 3) and transit (Phase 4) planning. PO to confirm
priority: TBD     # BA proposal: should. PO to confirm
size: M           # timeboxed spike: 5 working days after the Hamuga docs and test access arrive
needs_design: false
needs_backend: true
needs_mobile: false
status: draft     # created 2026-09-30. Blocked until the PO provides the Hamuga tile/API documentation and test access (D10, D25)
---

# NAV-010: Spike: evaluate Hamuga APIs (tiles, search, traffic, transit) as data sources

## Story
As a **UB commuter by car** (and a **pedestrian** who takes the bus), I want **the app to use the best available Mongolian data for addresses, places, live traffic and bus routes**, so that **search finds the places I actually look for, ETAs reflect UB congestion, and I can plan bus trips**.

> **Spike-type story.** The output is a written evaluation plus a recommendation for each capability. No code goes to production. Choosing a data partner is the **PO's decision** (backlog owed decision 4). This spike only supplies the evidence.

## Context
- **PO information (2026-09-30, with D25):** ICT Group makes **Hamuga** and has its own platform and servers. The PO will provide Hamuga tile/API documentation later (D10, "open: partly answered").
- **Our gaps that Hamuga might fill** (`docs/osm-navigation-research.md`):
  - address completeness and POI richness (§1, §8; R9 in NAV-001)
  - real-time traffic, which OSM does not have (§4.8, §6 item 5, roadmap Phase 3; backlog owed decision 4)
  - UB bus transit, which needs GTFS (§4.9, §6 item 6, Phase 4)
- **Stack is fixed** by ADR-0001 (OSM, PMTiles, Valhalla, Photon/Nominatim, Ferrostar, MapLibre). Any change is the architect's call through a new ADR.
- **Licensing:** OSM is ODbL 1.0 (research §4.1, §9). Mixing third-party data into a derived OSM database can trigger share-alike. Rendered maps and routes are "produced works". Commercial feeds need the terms checked for mixing and storage (research §4.8 item 4).

## Acceptance criteria (draft)
1. **Given** the Hamuga documentation and test credentials from the PO, **When** the spike is delivered within **5 working days** of receiving both, **Then** a spike document (architect, under `docs/architecture/spikes/`, with product findings summarised in this story) covers **each** of the four capabilities (tiles, search/geocoding including reverse, traffic, transit) with: endpoints, auth method, data formats, geographic coverage (UB only or national), update frequency, rate limits, SLA, pricing or "not stated", licence and attribution terms, and where the servers are physically located. Each value is labelled as documented, measured or unknown (with how and by when it will be found out).
2. **Given** Hamuga search, **When** a test set of **≥ 50** UB queries is run against Hamuga and against our Photon, **Then** the top-1 and top-5 hit rates are reported for both. The set contains **≥ 10** Latin-transliterated queries (for example "Sukhbaatar" for «Сүхбаатар»), **≥ 10** khoroo or ger-district addresses, **≥ 10** POIs and **≥ 5** queries with no expected result. The set and the expected answers are agreed with QA before the run.
3. **Given** Hamuga traffic data, **When** it is evaluated, **Then** the spike reports: the share of UB `primary`/`secondary`/`trunk` road length (km) with live speeds, the update interval in minutes, historical/predicted speed availability, and whether the data can be converted to Valhalla live traffic (`traffic.tar`) or predicted speeds, with the matching method (OSM way IDs, coordinates or another road network).
4. **Given** Hamuga transit data, **When** it is evaluated, **Then** the spike reports whether static GTFS and GTFS-Realtime (or a convertible format) exist, the number of UB bus routes and stops covered, and when the data was last updated.
5. **Given** the licence terms, **When** they are reviewed, **Then** the spike states for each capability whether we may: show the data beside OSM on one map, combine it with OSM data in our own database (ODbL share-alike risk), cache or store it, and use it offline, plus the required attribution text. Anything unclear goes to the PO's legal counsel as an open question.
6. **Given** a Hamuga API endpoint, **When** its latency is measured from phones on **≥ 2** Mongolian operators with the spike §5 protocol of NAV-008 (**≥ 20** TCP/443 samples each), **Then** the median and p95 are recorded.
7. **Given** the findings, **When** the recommendation is read, **Then** it gives **one** of "use now", "use later (phase N)" or "do not use" for each capability, with reasons, the stories affected (NAV-003, NAV-005, Phase 3 traffic, Phase 4 transit) and any ADR change the architect would need. The partner choice is left to the PO as an open question.

## Edge cases
- **No result / partial coverage:** queries Hamuga cannot answer are counted and reported, not dropped (AC 2).
- **Cyrillic/Latin search:** covered by the transliterated share of the test set (AC 2).
- **Unpaved roads and countryside:** coverage outside UB is reported separately for each capability (AC 1).
- **Winter:** if traffic data has seasonal gaps (for example sparse probes in winter), the spike records it.
- **No network:** whether any Hamuga data can be used offline (licence, AC 5; format, AC 1).
- **User privacy:** if calling Hamuga sends user coordinates to ICT Group, the spike records it. This matters for consent text and D9.

## Data dependencies & risks
- **R1: Docs and test access not delivered** (D10). The spike cannot start.
- **R2: Licence incompatibility with ODbL** may limit Hamuga data to a separate layer or a separate response, with no merge into our OSM-derived database.
- **R3: Vendor concentration** if ICT Group is both the production host (NAV-009) and a data source. The OSM stack stays as the fallback.
- **R4: Different road network.** Traffic that is not keyed to OSM ways needs map-matching, which is extra work and a quality risk.

## Out of scope
- Integrating any Hamuga API (follow-up stories after the PO decides).
- Choosing the traffic data partner (PO, backlog owed decision 4).
- Production hosting (NAV-009).

## Open questions
1. **Priority and phase.** BA proposes **should / Phase 1** (timeboxed, cheap, and it informs NAV-003 search and Phase 3 planning early). Options: (a) should / Phase 1; (b) could / Phase 2; (c) must / Phase 1 if the PO already intends Hamuga as the data partner. PO to confirm.
2. **Scope.** Options: (a) all four capabilities; (b) search and traffic only, with tiles and transit later. *Recommendation: (a). Documentation review is cheap, and the deep tests (AC 2–4) are scaled by what the docs show.*
3. **Commercial terms.** Is Hamuga data offered free, internally or at a price? Pricing is the PO's decision and is only recorded here.

## Traceability
| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC1 | — | — | spike doc (architect, TBD) | spike review | D10, D25 |
| AC2 | — | `search`, `reverse` (comparison only) | — | search comparison test set (QA, TBD) | |
| AC3 | — | — | — | traffic coverage analysis (architect, TBD) | backlog owed decision 4 |
| AC4 | — | — | — | transit data check (architect, TBD) | |
| AC5 | — | — | — | licence review (BA/architect; legal counsel if unclear) | |
| AC6 | — | — | — | latency measurement (QA, TBD) | |
| AC7 | — | — | spike doc (architect, TBD) | PO review | |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-09-30 | PO information with D25 (ICT Group / Hamuga platform), relayed by the orchestrator | Created as a draft spike: evaluate Hamuga tiles, search, traffic and transit APIs against our OSM stack. Priority to be confirmed by the PO | The PO indicated that ICT Group (Hamuga) has its own platform and data. They could fill known OSM gaps (addresses, traffic, transit) |

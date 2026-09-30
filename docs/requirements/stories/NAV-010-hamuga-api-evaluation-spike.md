---
id: NAV-010
title: "Spike: evaluate Hamuga APIs (tiles, search, routing, traffic, transit) as data sources"
phase: 1          # BA proposal: run in Phase 1 so the findings can feed search (NAV-003), traffic (Phase 3) and transit (Phase 4) planning. PO to confirm
priority: TBD     # BA proposal: should. PO to confirm
size: M           # timeboxed spike: 5 working days for the live tests after search and POI are enabled on the evaluation key (D40)
needs_design: false
needs_backend: true
needs_mobile: false
status: in-progress  # since 2026-09-30. Desk pass and first live probes delivered (spike doc, ADR-0007 accepted for the mechanism). PO decisions D36–D41 recorded. The second pass (search comparison, routing tests, transit samples, UB latency) waits for search/POI to be enabled on the evaluation key (D40, PO action)
---

# NAV-010: Spike: evaluate Hamuga APIs (tiles, search, routing, traffic, transit) as data sources

## Story
As a **UB commuter by car** (and a **pedestrian** who takes the bus), I want **the app to use the best available Mongolian data for addresses, places, live traffic and bus routes**, so that **search finds the places I actually look for, ETAs reflect UB congestion, and I can plan bus trips**.

> **Spike-type story.** The output is a written evaluation plus a recommendation for each capability. No code goes to production. Choosing a data partner is the **PO's decision** (backlog owed decision 4). This spike only supplies the evidence.

## Context
- **PO information (2026-09-30, with D25):** ICT Group makes **Hamuga** and has its own platform and servers. The PO will provide Hamuga tile/API documentation later (D10, "open: partly answered").
- **ICT Group is the PO's company** (spike doc, "Relationship to ICT Group"). Hamuga is an internal platform, not an outside vendor. ICT Group can therefore adapt its endpoints or deliver data to fit our contract, and the terms are an internal agreement. The architecture rules still apply unchanged: one contract, keys server-side only, ODbL, and a public repository (D35).
- **Spike delivered so far (architect, 2026-09-30):** `docs/architecture/spikes/nav-010-hamuga.md` (desk pass from the public SDK plus first live probes, one call per service, with a PO-provided evaluation key held outside the repo). Draft decision ADR-0007 (`docs/architecture/adr/0007-third-party-data-behind-gateway.md`), now accepted for the mechanism. Findings in short:
  - tiles: OpenMapTiles schema with UB enrichments (khoroo labels, house numbers, khashaa plots); live 200
  - search and POI: live **403** (key not subscribed); suggest has no `lang` and no location bias
  - **routing** (not in the original scope; added by the spike): a Valhalla endpoint that returns Ferrostar-compatible OSRM JSON with banner and voice instructions in `mn-MN`; live 200. Its `mn-MN` wording has the defects NAV-007 fixes
  - transit: an OpenTripPlanner endpoint; live 200; all 1,534 published UB stops are flagged `isActive: false, isVerified: false`
  - traffic: nothing visible
- **ICT Group transit platform (PO information, 2026-09-30, relayed by the orchestrator):**
  - ICT Group runs **OpenTripPlanner 2.5.0** (commit b301ea7c, built 2024-03-13) with **one GTFS feed** and a router covering **all of Mongolia**.
  - OTP's **legacy REST `plan` API** (the one the Hamuga SDK uses) is **deprecated in OTP 2.5 and removed in OTP 2.8**. Any transit integration must therefore use **OTP's GraphQL API** (or a GTFS export we self-host), never the REST `plan` endpoint. An OTP upgrade at ICT Group would otherwise break us (ADR-0007 Decision 2).
  - **Security note for ICT Group:** OTP's root info endpoint is reachable **through the Hamuga gateway** and exposes **server hardware details**. ICT Group should block it or allow-list only the paths that are needed. The PO passes this on (it is not ours to fix).
- **PO decisions of 2026-09-30** ("go ahead with recommendations", `decisions.md` D36–D41; ADR-0007 › PO decision record): see "Decided constraints for follow-up stories" below. The PO also committed to enabling search and POI on the evaluation key (D40).
- **Our gaps that Hamuga might fill** (`docs/osm-navigation-research.md`):
  - address completeness and POI richness (§1, §8; R9 in NAV-001)
  - real-time traffic, which OSM does not have (§4.8, §6 item 5, roadmap Phase 3; backlog owed decision 4)
  - UB bus transit, which needs GTFS (§4.9, §6 item 6, Phase 4)
- **Stack is fixed** by ADR-0001 (OSM, PMTiles, Valhalla, Photon/Nominatim, Ferrostar, MapLibre). Any change is the architect's call through a new ADR.
- **Licensing:** OSM is ODbL 1.0 (research §4.1, §9). Mixing third-party data into a derived OSM database can trigger share-alike. Rendered maps and routes are "produced works". Commercial feeds need the terms checked for mixing and storage (research §4.8 item 4).

### Decided constraints for follow-up stories (PO, 2026-09-30)
Every Hamuga follow-up story (search, POI, routing, tiles overlay, transit, traffic, data delivery) inherits these. They are not re-opened in those stories.

| # | Constraint | Decision |
|---|---|---|
| K1 | **Gateway only.** Every Hamuga capability is reached through our gateway (option (b)) and our `openapi.yaml`, as a separate optional upstream. **No client-side SDK**; web, Android and iOS never call Hamuga directly | D36 |
| K2 | **Ask ICT Group** to adapt its endpoints to our contract (option (d)): search with `lang` and location bias; routing with the NAV-007 `mn-MN` locale fixes. And to deliver its **non-OSM** data as separate stores that we run (option (e)) | D36 |
| K3 | **Routing stays R1**: our Valhalla behind `/v1/route`; Hamuga is a comparison source only. **R2a** (Hamuga primary, ours as fallback) is re-decided only after AC 9 (spike §5.3) passes **and** ICT Group commits to an SLA. R2b is not pursued | D37 |
| K4 | **Written internal ICT Group agreement** (spike §3.4 item 5) before any integration story moves to `ready`. Verbal approval is enough for this evaluation | D38 |
| K5 | **Keys:** one server key per environment (dev, staging, production), IP-restricted to that environment's gateway, server-side only, replacing the single evaluation key. No key, IP address or hostname in the repo | D39 |
| K6 | **Separate stores only.** Hamuga data is never merged into our Photon index, Valhalla graph or basemap PMTiles. Combined results are interleaved by source, all-OSM or all-Hamuga per feature type and region, with no cross-source deduplication. A merge needs its own ADR | D41 |
| K7 | **Transit targets OTP's GraphQL API** (or a GTFS export), not the deprecated REST `plan` endpoint (ICT Group runs OTP 2.5.0) | ADR-0007 Decision 2; PO information 2026-09-30 |

## Acceptance criteria (draft)
1. **Given** the Hamuga documentation and test credentials from the PO, **When** the spike is delivered within **5 working days** of receiving both, **Then** a spike document (architect, under `docs/architecture/spikes/`, with product findings summarised in this story) covers **each** of the four capabilities (tiles, search/geocoding including reverse, traffic, transit) with: endpoints, auth method, data formats, geographic coverage (UB only or national), update frequency, rate limits, SLA, pricing or "not stated", licence and attribution terms, and where the servers are physically located. Each value is labelled as documented, measured or unknown (with how and by when it will be found out).
   *(Note 2026-09-30.)* The desk pass and first live probes cover this partly (spike §2.4). No vendor documentation exists yet. The spike used the public SDK and one evaluation key instead. **Routing** is covered as a fifth capability (AC 9). The 5-working-day clock for the remaining live values starts when search and POI answer 200 on the evaluation key (D40).
2. **Given** Hamuga search, **When** a test set of **≥ 50** UB queries is run against Hamuga and against our Photon, **Then** the top-1 and top-5 hit rates are reported for both. The set contains **≥ 10** Latin-transliterated queries (for example "Sukhbaatar" for «Сүхбаатар»), **≥ 10** khoroo or ger-district addresses, **≥ 10** POIs and **≥ 5** queries with no expected result. The set and the expected answers are agreed with QA before the run.
   *(Note 2026-09-30.)* Blocked by 403 until the PO enables search and POI on the evaluation key (D40). The spike proposes a larger, independently labelled set (≥ 100 rows) with exit criteria (spike §5.2). This AC is **not** changed until the PO decides (Open question 4).
3. **Given** Hamuga traffic data, **When** it is evaluated, **Then** the spike reports: the share of UB `primary`/`secondary`/`trunk` road length (km) with live speeds, the update interval in minutes, historical/predicted speed availability, and whether the data can be converted to Valhalla live traffic (`traffic.tar`) or predicted speeds, with the matching method (OSM way IDs, coordinates or another road network).
4. **Given** Hamuga transit data, **When** it is evaluated, **Then** the spike reports whether static GTFS and GTFS-Realtime (or a convertible format) exist, the number of UB bus routes and stops covered, and when the data was last updated. *(Added 2026-09-30, PO information.)* It also reports: whether OTP's **GraphQL API** is reachable through the Hamuga gateway with our evaluation key (yes/no, with one sample query result recorded as counts only), whether ICT Group can export its **one GTFS feed** (yes/no/unknown), what the stop flags `isActive` and `isVerified` mean (ICT Group's written answer), and ICT Group's plan and date, if any, for upgrading from OTP 2.5.0.
5. **Given** the licence terms, **When** they are reviewed, **Then** the spike states for each capability whether we may: show the data beside OSM on one map, combine it with OSM data in our own database (ODbL share-alike risk), cache or store it, and use it offline, plus the required attribution text. Anything unclear goes to the PO's legal counsel as an open question. *(Note 2026-09-30.)* "Combine it with OSM data in our own database" is now **not allowed** without its own ADR (D41, K6). The review still records what ICT Group's terms would permit.
6. **Given** a Hamuga API endpoint, **When** its latency is measured from phones on **≥ 2** Mongolian operators with the spike §5 protocol of NAV-008 (**≥ 20** TCP/443 samples each), **Then** the median and p95 are recorded.
7. **Given** the findings, **When** the recommendation is read, **Then** it gives **one** of "use now", "use later (phase N)" or "do not use" for each capability, with reasons, the stories affected (NAV-003, NAV-005, Phase 3 traffic, Phase 4 transit) and any ADR change the architect would need. The partner choice is left to the PO as an open question.
   *(Note 2026-09-30.)* **Partly met.** The spike gives a per-capability recommendation (spike §1), and the PO accepted the mechanism and the routing choice (D36–D41, ADR-0007 accepted for the mechanism). Search/POI, transit and traffic recommendations are updated after the second pass.
8. *(Added 2026-09-30, D39.)* **Given** the spike deliverables and every evaluation harness in the repository, **When** QA runs a secret scan over the full git history (for example `gitleaks detect`) and searches the repo, the QA reports and the Playwright traces for the evaluation key, **Then** there are **0** matches for any Hamuga key, and **0** server IP addresses or hostnames of our environments. Harnesses read the key from the environment only (`HAMUGA_API_KEY`), and `.env.example` lists `HAMUGA_API_KEY=` with an empty value. After ICT Group issues the per-environment server keys (K5), the evaluation key is **not** configured in staging or production.
9. *(Added 2026-09-30, D37: the evidence for re-deciding R2a.)* **Given** Hamuga routing and our Valhalla at the same OSM build date, **When** the spike §5.3 routing tests run, **Then** the spike reports for each item whether it passes:
   - **Format:** `voiceLocale`, polyline6 geometry, `units: "kilometers"`, `costing: "pedestrian"`, `costing_options.auto.exclude_unpaved` and `alternates: 2` are honoured, and a Ferrostar OSRM-adapter parse test (unit test) passes on **every** returned route.
   - **Pairs** on the exact contract points, **7** pairs (P1→P3 auto, P1→P2 pedestrian, P1→P6 auto `exclude_unpaved`, P1→P4 auto `alternates: 2`, P5→P1, P6→P3, P1→X1): HTTP status, distance and duration difference in %, share of our polyline within **30 m** of theirs, and the `mn-MN` wording checked against the NAV-007 glossary rules.
   - **Correctness:** **≥ 10** ground-truthed cases near P1–P6 (**≥ 5** turn restrictions, **≥ 5** one-way segments), routed in the forbidden direction on both engines. **Pass = 0 illegal manoeuvres.** Any illegal manoeuvre by Hamuga blocks R2a.
   - **Latency:** Hamuga routing through our gateway, measured from UB phones on **≥ 2** operators (AC 6 protocol, **≥ 20** full requests each). **Pass for R2a: p95 < 500 ms.**
   - **Traffic detection:** P1→P3, P5→P1 and P6→P3 at **08:30, 13:00 and 18:30** UB time on one weekday. Durations that vary by **> 15 %** while ours stay constant indicate traffic-aware costing (reported, not a pass condition).
   - **Availability:** one P1→P3 probe every **5 minutes** for **7 days**, with the error rate and p95 recorded against the SLA ICT Group states.
   
   R2a comes back to the PO only when format, correctness and latency pass **and** ICT Group has committed to an SLA and to the NAV-007 locale fixes (K2, K3). Until then the gateway does not forward `/v1/route` to Hamuga.

## Edge cases
- **No result / partial coverage:** queries Hamuga cannot answer are counted and reported, not dropped (AC 2).
- **Cyrillic/Latin search:** covered by the transliterated share of the test set (AC 2). Hamuga suggest has no `lang` parameter and the style has almost no `name:en`, so the English UI (tourist persona, D28) is at risk. K2 asks ICT Group for `lang`.
- **Unpaved roads and countryside:** coverage outside UB is reported separately for each capability (AC 1). Routing: P1→X1 and `exclude_unpaved` (AC 9).
- **Winter:** if traffic data has seasonal gaps (for example sparse probes in winter), the spike records it.
- **No network:** whether any Hamuga data can be used offline (licence, AC 5; format, AC 1). A separate PMTiles overlay (option (e)) is the only offline-capable form proposed.
- **Hamuga unavailable or key revoked:** in every decided option the OSM stack stays the fallback (K1, K3): search falls back to Photon, routing is ours anyway (R1), transit shows the localised unavailable state. The AC 9 availability probe measures how often this would happen.
- **Off-route and reroute:** under R1, reroutes go to our Valhalla. Under a future R2a, every reroute is one Hamuga request, so quotas must cover the peak reroute rate (AC 9, spike §4).
- **GPS loss:** not applicable to the evaluation.
- **User privacy:** if calling Hamuga sends user coordinates to ICT Group, the spike records it. This matters for consent text and D9. Through our gateway (K1), ICT Group sees queries and coordinates but not user IPs. Search bias under K2 would send rounded coordinates (D30).

## Data dependencies & risks
- **R1: Docs and test access not delivered** (D10). The spike cannot start. *Update 2026-09-30: partly resolved. One evaluation key was provided and the first live probes ran. Search and POI still return 403 until the PO enables them (D40). No written documentation yet.*
- **R2: Licence incompatibility with ODbL** may limit Hamuga data to a separate layer or a separate response, with no merge into our OSM-derived database. *Update 2026-09-30: separation is now the decided rule (D41, K6), whatever the licence permits.*
- **R3: Vendor concentration** if ICT Group is both the production host (NAV-009) and a data source. The OSM stack stays as the fallback (K1, K3).
- **R4: Different road network.** Traffic that is not keyed to OSM ways needs map-matching, which is extra work and a quality risk.
- **R5: Transit API end of life** (added 2026-09-30). ICT Group runs OTP 2.5.0. The REST `plan` API is deprecated there and removed in OTP 2.8, so it disappears when ICT Group upgrades. Mitigation: K7 (GraphQL or GTFS export) and our own itinerary schema in the contract (ADR-0007).
- **R6: Unverified transit stops** (added 2026-09-30). All 1,534 published UB stops are `isActive: false, isVerified: false`. Until ICT Group explains the flags (AC 4), transit results are not treated as reliable.
- **R7: Key disclosure in a public repository** (added 2026-09-30). One evaluation key exists outside the repo. A leak through a commit, a QA report or a Playwright trace would expose it. Mitigation: AC 8, K5, secret scanning with push protection (spike §3.5; requested from the architect/backend as a tech-debt item).
- **R8: Information exposure at ICT Group** (added 2026-09-30). The OTP root info endpoint shows server hardware details through the Hamuga gateway. It is ICT Group's to fix; the PO passes on the note. We do not call or document that endpoint further.

## Out of scope
- Integrating any Hamuga API (follow-up stories after the PO decides). *Since 2026-09-30:* follow-up stories inherit K1–K7 and cannot move to `ready` before the written internal ICT Group agreement exists (D38).
- Choosing the traffic data partner (PO, backlog owed decision 4).
- Production hosting (NAV-009).
- Merging Hamuga data into our OSM stores (needs its own ADR, D41).
- Switching `/v1/route` to Hamuga (R2a is re-decided later, D37).

## Open questions
1. **Priority and phase.** BA proposes **should / Phase 1** (timeboxed, cheap, and it informs NAV-003 search and Phase 3 planning early). Options: (a) should / Phase 1; (b) could / Phase 2; (c) must / Phase 1 if the PO already intends Hamuga as the data partner. PO to confirm. *(Still open on 2026-09-30.)*
2. **Scope.** Options: (a) all four capabilities; (b) search and traffic only, with tiles and transit later. *Recommendation: (a). Documentation review is cheap, and the deep tests (AC 2–4) are scaled by what the docs show.* *(Note 2026-09-30: the spike covered all four plus routing, and AC 9 adds the routing tests. The PO has not chosen explicitly; the BA treats (a) plus routing as the working scope.)*
3. **Commercial terms.** Is Hamuga data offered free, internally or at a price? Pricing is the PO's decision and is only recorded here. *(Update 2026-09-30: Hamuga is internal to the PO's company, so cost allocation, quotas and the approver at ICT Group are part of the written internal agreement, D38, spike §3.4 item 5 question 1.)*
4. **AC 2 vs the revised search comparison** (added 2026-09-30). Options: (a) amend AC 2 to the spike §5.2 set (≥ 100 independently labelled rows) and its exit criteria (paired significance, English UI, distance ranking, no regressions, latency) through a change request before the second pass runs; (b) keep AC 2 as written. *Recommendation: (a).* (`decisions.md`, Items to confirm 9.)
5. **Written agreement: who and when** (added 2026-09-30, information from the PO). Who at ICT Group signs the internal agreement (role only), and by when? Every integration follow-up waits for it (D38).
6. **Publishing evaluation results** (spike §3.4 question 11; `decisions.md`, Items to confirm 5). The repository is public. *Recommendation: commit counts and metrics only until ICT Group agrees in writing.*

## Traceability
Paths: spike = `docs/architecture/spikes/nav-010-hamuga.md`; ADR = `docs/architecture/adr/0007-third-party-data-behind-gateway.md` (accepted for the mechanism, 2026-09-30).

| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC1 | — | — | spike §2.4 (desk pass + first live probes, 2026-09-30); remaining values after D40 | spike review | D10, D25, D40 |
| AC2 | — | `search`, `reverse` (comparison only) | — | search comparison test set (QA, TBD; spike §5.2) | D40 (403 until enabled); Open question 4 |
| AC3 | — | — | — | traffic coverage analysis (architect, TBD); traffic detection in AC 9 | backlog owed decision 4 |
| AC4 | — | — (a transit operation would be new, K7) | spike §5.4 | transit data check (architect, TBD) | PO information 2026-09-30 (OTP 2.5.0, one GTFS feed); R5, R6 |
| AC5 | — | — | spike §3.4 | licence review (BA/architect; legal counsel if unclear) | D41 |
| AC6 | — | — | spike §5.5 | latency measurement (QA, TBD) | |
| AC7 | — | — | spike §1, ADR-0007 | PO review | D36–D41 (mechanism and routing decided) |
| AC8 | — | — | harness env handling (QA); `.env.example` (backend) | secret scan + repo/report/trace search (QA, TBD) | D39, D35; R7 |
| AC9 | — | `postRoute` (comparison only; no contract change under R1) | spike §5.3 | routing comparison, correctness, latency and availability runs (QA/architect, TBD); Ferrostar parse test (mobile or QA) | D37; NAV-005, NAV-007 |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-09-30 | PO information with D25 (ICT Group / Hamuga platform), relayed by the orchestrator | Created as a draft spike: evaluate Hamuga tiles, search, traffic and transit APIs against our OSM stack. Priority to be confirmed by the PO | The PO indicated that ICT Group (Hamuga) has its own platform and data. They could fill known OSM gaps (addresses, traffic, transit) |
| 2026-09-30 | PO answer "go ahead with recommendations" after the spike (desk pass + first live probes) and ADR-0007, relayed by the orchestrator (`decisions.md` D36–D41); PO information on ICT Group's OpenTripPlanner | **Title** adds routing. **Front matter:** status draft → in-progress (spike partly delivered); size note now counts the 5 days from D40. **Context:** ICT Group is the PO's company; spike findings summary; new facts: OTP 2.5.0 (commit b301ea7c, built 2024-03-13), one GTFS feed, router covering all of Mongolia, REST `plan` deprecated in 2.5 and removed in 2.8 so transit must use OTP's GraphQL API, and a security note for ICT Group (OTP root info endpoint exposes server hardware details through the gateway). **New section "Decided constraints for follow-up stories" K1–K7** (D36–D39, D41, ADR-0007). **AC 1, 2, 5, 7:** dated notes (partly met, 403 until D40, merge not allowed, mechanism decided); outcomes and numbers unchanged. **AC 4:** adds GraphQL reachability, GTFS export, stop-flag meaning and the OTP upgrade plan. **New AC 8** (D39): no key, IP or hostname in the repo, reports or traces; evaluation key not used on staging/production. **New AC 9** (D37): the spike §5.3 routing tests that R2a depends on, with pass conditions. **Edge cases:** Hamuga unavailable, reroute quota, English UI risk, privacy through the gateway. **Risks:** R1, R2 updated; new R5–R8. **Out of scope:** integration gated by D38; merge (D41) and R2a (D37) excluded. **Open questions:** 2 and 3 annotated; new 4 (AC 2 vs spike §5.2), 5 (agreement signer and date), 6 (publishing results). **Traceability:** spike and ADR paths; AC8, AC9 rows added. No hostname, IP or key recorded | The PO fixed the mechanism (gateway only, no SDK, separate stores, per-environment keys), kept our Valhalla (R1) and made a written internal agreement the gate for integration. The story now states those as binding constraints and makes the R2a re-decision evidence testable. AC 2's size is left for the PO (Open question 4), because §5.2 was not named in the decision |

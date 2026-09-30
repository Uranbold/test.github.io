# ADR-0007: Hamuga (ICT Group) data and services are used only through our gateway and our contract, as separate optional upstreams or separate stores; our OSM stack remains the fallback

- **Status:** proposed (the PO decides; backlog owed decision 4 and NAV-010 AC 7). Must not be applied before the internal ICT Group terms and approval in the spike (§3.4 item 5) are in writing.
- **Date:** 2026-09-30 (amended the same day after the first live probes and the skeptic review)
- **Stories:** NAV-010 (spike). Would affect NAV-003/NAV-005 (search), NAV-001/NAV-005 (routing, if R2 is chosen), NAV-007 (`mn-MN` wording), the Phase 4 transit story (new) and the Phase 3 traffic planning.
- **Evidence:** [spike nav-010-hamuga](../spikes/nav-010-hamuga.md):
  - a desk study of the public `hamuga-imap-sdk@0.1.0`, the public style and the public web pages
  - **first live probes** by the orchestrator (one call per service, with a PO-provided key stored outside the repository)
- **Contract:** no change now (`openapi.yaml` stays 0.4.1). Routing under R2 needs **no** contract change (same OSRM shape). Each other adopted capability adds a minor-version operation (spike §3.3 contract sketch).
- **Wording note:** "third party" in the file name means "outside our own stack and repository". The PO works for ICT Group, which makes Hamuga, so Hamuga is an **internal platform of the PO's company**, not an outside vendor.

## Context
The PO pointed us to Hamuga, ICT Group's map platform. The PO works for ICT Group. What the public SDK and the first live probes (2026-09-30) show:
- **Tiles** in the OpenMapTiles schema, with UB enrichments (khoroo labels, house numbers, khashaa plots). Live: 200.
- A **custom search and POI engine** with 336 Mongolian categories. Live: **403**, the key is not subscribed to search/POI. Suggest has no `lang` and no location-bias parameter, and the style has almost no `name:en`.
- A **Valhalla routing endpoint.** Live: with our full request body (`format: "osrm"`, banner and voice instructions, `language: "mn-MN"`) it returns `code: "Ok"` with `bannerInstructions` and `voiceInstructions`. **This is Ferrostar-compatible output, passed through.**
  - The `mn-MN` wording has the defects NAV-007 fixes (glossary C1 register, C2 left/east ambiguity).
  - One sample took about 1.1 s from a cloud container outside Mongolia (not representative of UB).
- An **OpenTripPlanner REST `plan`** endpoint for bus and train. Live: 200 with `plan`. However:
  - all 1,534 published UB stops are `isActive: false, isVerified: false`
  - upstream OTP removed this REST API in 2.8.0 (2025-09-10)
  
  So transit is promising but not production-ready.

It all sits behind Cloudflare → Kong and is authenticated with an `x-api-key` header, subscribed per service. The only SDK is a browser JavaScript SDK (0.x, unstable API). No traffic capability is visible.

Forces:
- **One contract** (CLAUDE.md rule 2). Clients parse Photon and Valhalla/OSRM shapes today, and Ferrostar needs OSRM-format routes with banner and voice instructions. Hamuga routing now meets that shape; Hamuga search does not.
- **The repository is public** (D35), and web and mobile bundles are public artefacts. A key placed in them is disclosed.
- **ODbL.**
  - If Hamuga's public database derives from OSM, it is itself under ODbL share-alike (§4.4). That includes ICT Group's additions merged into it.
  - Merging Hamuga data into our stores would then republish those additions under ODbL.
  - If ICT Group keeps non-OSM data separate, that data is not share-alike. But merging it with OSM records by deduplication would fall outside the OSMF Collective Database guideline.
- **Internal platform.** ICT Group can adapt its endpoints or deliver data to fit our contract (option d/e below). Terms are an internal agreement.
- **Concentration** (NAV-010 R3). ICT Group may also host production (D25).
- **Privacy** (D9). Queries and coordinates sent to another service need a legal basis. Routing sends trip origins, destinations and reroute positions. Direct client calls would also expose user IPs.
- **Control.** `mn-MN` guidance fixes (NAV-007), costing options and the p95 route NFR are fully in our hands only with our own Valhalla. With Hamuga routing, they depend on ICT Group applying the same fixes and meeting the NFR.

## Decision (proposed)
1. **Mechanism: gateway upstreams only.** Every Hamuga capability we adopt is reached through our gateway:
   - The gateway injects `x-api-key` from a server-side environment variable (`HAMUGA_API_KEY`, empty in `.env.example`). The key is a **server key per environment, IP-restricted to our gateway**.
   - It strips any client-supplied key.
   - It forwards only allow-listed upstream paths, with TLS verification.
   - Clients (web, Android, iOS) **never** hold a Hamuga key and never call `gateway.hamuga.mn` directly.
   - The Hamuga browser SDK is **not** a dependency of `web/` or `mobile/`.
2. **Scope: hybrid, with the routing engine as an explicit PO choice.**
   - **Basemap** (PMTiles) stays on our OSM stack. A Hamuga enrichment overlay is optional later, preferably delivered as a separate PMTiles file (option E below).
   - **Routing:** `/v1/route` keeps its contract. The PO chooses:
     - **R1:** our Valhalla (Hamuga as a comparison source)
     - **R2a:** Hamuga primary, with our Valhalla as fallback
     - **R2b:** our Valhalla primary, with Hamuga as fallback
     
     **Architect's lean:** R1 now. Move to R2a only after:
     - the remaining live tests pass: turn-restriction and one-way correctness on P1–P6, p95 < 500 ms from UB via our gateway, the Ferrostar parse test, and a 7-day availability probe
     - ICT Group applies the NAV-007 `mn-MN` fixes and states an SLA
     
     Evidence of traffic-aware routing would strengthen R2a.
   - Hamuga **search/POI** may be added as a **secondary** source. Conditions:
     - the key is subscribed
     - the revised live comparison meets its exit criteria (spike §5.2): ≥ 100 independently labelled rows, a paired significance rule, English UI (D28) and distance ranking (D30)
   - Hamuga **transit** may become a new gateway operation (Phase 4), after ICT Group explains the stop status flags and states the API roadmap (OTP GraphQL or a GTFS export).
   - Traffic stays undecided until ICT Group states what exists.
3. **Separation of data.**
   - Hamuga data stays in separate upstream responses, separate stores and separate map sources/layers. It is never imported into our Photon index, Valhalla graph or basemap PMTiles.
   - When sources are combined, the combination follows the OSMF Collective Database guideline:
     - per feature type (or property) and region, the data is all OSM or all non-OSM
     - **no cross-source deduplication at the data level**
   - Search merging, if chosen, interleaves whole results with a `source` marker. It never blends fields of an OSM record with a Hamuga record.
   - No bulk caching of Hamuga results, unless the data is delivered under a written licence (option E).
   - Deliberately merging ODbL-derived Hamuga data into our stores is **not** part of this decision. It would republish ICT Group's enrichments under ODbL, so it needs its own ADR and an ICT Group business decision.
4. **Degradation.**
   - Each Hamuga-backed operation has a gateway timeout and a circuit breaker.
   - Search falls back to Photon only.
   - Routing under R2a fails over to our Valhalla.
   - Transit has no fallback, so it returns the contract's 502/503/504 `GatewayError` and the client shows its localised unavailable state.
5. **Attribution.** Every screen that shows Hamuga-derived content (tiles, search results, Hamuga-computed routes, transit) credits all sources from resource files:
   - «© OpenStreetMap contributors»
   - "© OpenMapTiles" if OMT-schema tiles are shown
   - the Hamuga credit in the wording ICT Group requires

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| **A. Client-side SDK / direct REST from the apps** | Fastest to demo. No backend work | Key disclosed in public bundles. Web only (no native SDK). Two contracts. User IP and coordinates go straight to Hamuga. No fallback. SDK UI has an XSS risk (`innerHTML`), hard-coded English strings, attribution off by default, and lossy selection |
| **B. Gateway upstreams, content chosen per capability (this ADR)** | One contract. Key hidden. Rate limits, caching and circuit breaker in one place. OSM fallback. Partner swap invisible to clients. Routing is pure forwarding, since the output already fits | Backend work per capability. An extra hop, significant while staging is in Singapore (D25). Search needs a small adapter service unless D or E removes the need |
| **C. Replace our stack with Hamuga** (tiles, search, routing) | Less infrastructure for us. Routing output is now shown to be Ferrostar-compatible | Search has no `lang` or bias, and the tiles have almost no English labels (English UI). Offline basemap lost. `mn-MN` fixes and NFRs depend on another team. Correctness, UB latency and SLA unproven. Full concentration on the same company as the host (R3). Contradicts ADR-0001 |
| **D. ICT Group adapts its endpoints to our contract** (internal platform) | Examples: routing already fits (plus NAV-007 fixes and costing options); a search endpoint with `lang`, location bias and Photon-compatible output; transit on a supported API. Gateway stays a thin forwarder; clients keep one parser | Depends on ICT Group's roadmap and capacity. Needs an internal agreement on versioning and change notice. **Compatible with this ADR:** D is how B's upstreams get better, not a replacement for B |
| **E. ICT Group delivers non-OSM data; we run it as a separate store** | Examples: a second search index next to Photon; an enrichment PMTiles overlay. No runtime dependency; latency, `lang` and bias under our control; offline possible. Collective database if kept separate and partitioned | Import pipeline and freshness work. ICT Group must state which records are non-OSM and grant a written licence. **Compatible with this ADR** as a data-level form of the separation rule |
| **F. Import Hamuga data into our OSM-derived stores, deduplicated against OSM** | One index, best ranking control | Outside the Collective Database guideline, so a derivative database under ODbL share-alike. If Hamuga's database is already OSM-derived, this republishes ICT Group's enrichments under ODbL; if not, it puts proprietary data under share-alike. Hard to reverse. Needs its own ADR and an ICT Group business decision |

## Consequences
- **Easier:**
  - Clients stay on one contract and one parser per capability. Switching the routing engine (R1 ↔ R2) is a gateway change with no client release.
  - Keys are rotated server-side without an app release.
  - An outage of Hamuga degrades search (and routing under R2a) rather than breaking it.
  - Moving to another data partner later is a gateway change.
- **Harder:**
  - Each adopted capability needs gateway config (or a small adapter for search merging), an `openapi.yaml` minor bump (except routing) and QA contract tests.
  - Under R2, the NAV-007 wording fixes must be maintained in two Valhalla deployments (ours and ICT Group's), and the route NFR depends on another team.
  - Staging latency for Hamuga-backed operations will not represent production until NAV-009 moves production to Mongolia.
- **Follow-up:**
  - enable search/POI on the key, then the rest of the live evaluation (spike §5)
  - per-environment, IP-restricted server keys
  - secret-scanning guardrails on the public repo
  - an ADR on the search aggregation shape (pass-through `/v1/places/*`, a Photon-compatible merged `/v1/search`, option D or option E)
  - a transit story with UX and contract, after ICT Group states the OTP roadmap
  - multi-source attribution strings (BA/UX)
  - sharing the NAV-007 locale fixes with ICT Group (and upstream Valhalla)
- **Reversal cost:** low while nothing is adopted. After adoption:
  - removing a Hamuga upstream is a gateway change, plus either the removal of an optional client feature (transit) or a silent quality change (search, routing)
  - option E adds a pipeline to retire

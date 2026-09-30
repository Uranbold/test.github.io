# ADR-0007: Third-party map data (Hamuga / ICT Group) is used only through our gateway, as separate optional upstreams; the OSM stack stays primary and the fallback

- **Status:** proposed (the PO decides; backlog owed decision 4 and NAV-010 AC 7). Must not be applied before ICT Group has answered the terms questions in the spike (§3.4).
- **Date:** 2026-09-30
- **Stories:** NAV-010 (spike). Would affect NAV-003/NAV-005 (search), the Phase 4 transit story (new) and the Phase 3 traffic planning.
- **Evidence:** [spike nav-010-hamuga](../spikes/nav-010-hamuga.md), a desk study of the public `hamuga-imap-sdk@0.1.0`, the public style and the public web pages. No API key was used.
- **Contract:** no change now (`openapi.yaml` stays 0.4.1). If this ADR is accepted, each adopted capability adds a minor-version operation (spike §3.3 contract sketch).

## Context
The PO pointed us to Hamuga, ICT Group's map platform. The public SDK shows:
- tiles in the OpenMapTiles schema, with UB enrichments (khoroo labels, house numbers, khashaa plots)
- a custom search and POI engine with 336 Mongolian categories
- a Valhalla-style routing endpoint that returns native `trip` JSON
- an OpenTripPlanner REST `plan` endpoint for bus and train, backed by at least 1,534 UB bus stops

It all sits behind Cloudflare → Kong and is authenticated with an `x-api-key` header. The only SDK is a browser JavaScript SDK (0.x, unstable API). No traffic capability is visible.

Forces:
- **One contract** (CLAUDE.md rule 2). Clients parse Photon and Valhalla/OSRM shapes today, and Ferrostar needs OSRM-format routes with banner and voice instructions.
- **The repository is public** (D35), and web and mobile bundles are public artefacts. A key placed in them is disclosed.
- **ODbL.** Hamuga data is likely at least partly OSM-derived. Merging it into our OSM-derived stores could create a derivative database (share-alike on the merged data) or breach ICT Group's terms.
- **Vendor concentration** (NAV-010 R3). ICT Group may also host production (D25).
- **Privacy** (D9). Queries and coordinates sent to a third party need a legal basis. Direct client calls would also expose user IPs.
- **Control.** `mn-MN` guidance fixes (NAV-007), costing options and the p95 route NFR depend on running our own Valhalla.

## Decision (proposed)
1. **Mechanism: gateway upstreams only.** Every Hamuga capability we adopt is reached through our gateway:
   - The gateway injects `x-api-key` from a server-side environment variable (`HAMUGA_API_KEY`, empty in `.env.example`).
   - It strips any client-supplied key.
   - It forwards only allow-listed upstream paths, with TLS verification.
   - Clients (web, Android, iOS) **never** hold a Hamuga key and never call `gateway.hamuga.mn` directly.
   - The Hamuga browser SDK is **not** a dependency of `web/` or `mobile/`.
2. **Scope: hybrid.**
   - **Routing** (Valhalla, Ferrostar) and the **basemap** (PMTiles) stay on our OSM stack.
   - Hamuga **search/POI** may be added as a **secondary** source, but only if the live comparison (spike §5.2) meets its exit criteria.
   - Hamuga **transit** (OTP `plan`) may become a new gateway operation (Phase 4).
   - A Hamuga **tile overlay** is optional later.
   - Traffic stays undecided until ICT Group states what exists.
3. **Separation of data.**
   - Hamuga data stays in separate upstream responses and separate map sources/layers. It is never imported into our Photon index, Valhalla graph or PMTiles.
   - Search merging, if chosen, interleaves whole results with a `source` marker. It never blends fields of an OSM record with a Hamuga record.
   - No bulk caching of Hamuga results beyond what ICT Group's terms allow.
4. **Degradation.**
   - Each Hamuga-backed operation has a gateway timeout and circuit breaker.
   - Search falls back to Photon only.
   - Transit has no fallback, so it returns the contract's 502/503/504 `GatewayError` and the client shows its localised unavailable state.
5. **Attribution.** Every screen that shows Hamuga-derived content credits all sources from resource files:
   - «© OpenStreetMap contributors»
   - "© OpenMapTiles" if OMT-schema tiles are shown
   - the Hamuga credit in the wording ICT Group requires

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| **A. Client-side SDK / direct REST from the apps** | Fastest to demo. No backend work | Key disclosed in public bundles. Web only (no native SDK). Two contracts. User IP and coordinates go straight to the vendor. No fallback. SDK UI has an XSS risk (`innerHTML`), hard-coded English strings, attribution off by default, and lossy selection |
| **B. Gateway upstreams, OSM stack primary (this ADR)** | One contract. Key hidden. Rate limits, caching and circuit breaker in one place. OSM fallback. Vendor swap invisible to clients | Backend work per capability. An extra hop, significant while staging is in Singapore (D25). Search merging needs a small adapter service |
| **C. Replace our stack with Hamuga** (tiles, search, routing) | Less infrastructure for us | Ferrostar compatibility unproven (native `trip`). Loses control of `mn-MN` wording, costing and NFRs. Offline basemap lost. Full vendor lock-in with the same company as the host (R3). Contradicts ADR-0001 |
| **D. Import Hamuga data into our OSM-derived stores** | One index, best ranking control | Creates a derivative database (ODbL share-alike on the merged data). Likely breaches vendor terms. Hard to reverse |

## Consequences
- **Easier:**
  - Clients stay on one contract and one parser per capability.
  - Keys are rotated server-side without an app release.
  - A vendor outage degrades search rather than breaking it.
  - Moving to another data partner later is a gateway change.
- **Harder:**
  - Each adopted capability needs gateway config (or a small adapter for search merging), an `openapi.yaml` minor bump and QA contract tests.
  - Staging latency for Hamuga-backed operations will not represent production until NAV-009 moves production to Mongolia.
- **Follow-up:**
  - live evaluation (spike §5)
  - secret-scanning guardrails on the public repo
  - an ADR on the search aggregation contract shape (pass-through `/v1/places/*` vs a Photon-compatible merged `/v1/search`)
  - a transit story with UX and contract
  - multi-source attribution strings (BA/UX)
- **Reversal cost:** low while nothing is adopted. After adoption, removing a Hamuga upstream is a gateway change plus the removal of an optional client feature (transit) or a silent quality change (search).

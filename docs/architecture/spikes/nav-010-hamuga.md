# Spike: NAV-010 Hamuga (ICT Group) APIs as data sources: desk study plus first live probes

- **Story:** [NAV-010](../../requirements/stories/NAV-010-hamuga-api-evaluation-spike.md). This pass partly covers AC 1, AC 5 and AC 7. The first live probe covers part of AC 3 (routing format) and AC 4 (transit reachability). AC 2 (search) is blocked by a 403 (§2.5). AC 6 (latency from UB) is still open (§5).
- **Owner:** architect
- **Date:** 2026-09-30. Two passes on the same day:
  1. a timeboxed **desk pass** from the public SDK, before any API key was received
  2. **first live probes** by the orchestrator: one call per service with a key provided by the PO. The key is stored outside the repository and appears nowhere in this document
- **Status:** a recommendation for the PO, who makes the decision (backlog owed decision 4 and NAV-010 AC 7). The draft decision on the integration pattern is [ADR-0007](../adr/0007-third-party-data-behind-gateway.md) (status `proposed`).
- **Relationship to ICT Group (stated plainly).** The PO works for ICT Group, which makes Hamuga. Hamuga is therefore an **internal platform of the PO's company, not an outside vendor**. So:
  - ICT Group can adapt its endpoints or deliver data to fit our contract (option (d), §3.3).
  - The "terms of use" in §3.4 become an internal approval and a written internal agreement, not a vendor negotiation.
  
  The architecture rules still apply unchanged: one contract, the key held server-side only, ODbL, and a public repository (D35). "Third party" in ADR-0007 means "outside our own stack and repository", not "outside the company".
- **Scope rule followed:**
  - **Desk pass.** No authenticated endpoint was called. Fetched only:
    - the public npm package `hamuga-imap-sdk@0.1.0` (registry metadata and tarball)
    - the public style `https://cdn.hamuga.mn/style.json`
    - the public sprite metadata `content_v14.json` / `@2x.json`
    - two public glyph ranges
    - the public landing pages `hamuga.mn` and `imap.hamuga.mn`
    
    Two unauthenticated requests went to the gateway (`GET /`, which returns 404, and one CORS `OPTIONS` preflight), to read headers only. **JavaScript bundles of the Hamuga web app were not scraped.**
  - **Live pass (orchestrator).** One request per service: routing (plus two variants), transit, one tile, search and POI. Raw responses are **not** committed (§3.4 question 11). Only status codes, sizes, aggregate values and the short instruction samples quoted below are recorded here.
- **Evidence labels:**
  - **D:** documented in the SDK README or TypeScript types
  - **C:** read from the SDK's compiled code (`dist/index.mjs`, what the SDK actually sends)
  - **M:** measured by us from a public, unauthenticated resource on 2026-09-30
  - **L:** live authenticated call by the orchestrator on 2026-09-30 (one sample per service, relayed to the architect)
  - **I:** inferred by the architect (a reasoned guess, not a fact)
  - **U:** unknown; the "how/when" column says how it will be found out

---

## 1. Question and short answer

**Question.** Can Hamuga (ICT Group) tiles, search, routing and public transport APIs fill the known gaps in our OSM stack (ADR-0001: PMTiles, Photon, Valhalla, Ferrostar, MapLibre)? The gaps are addresses and house numbers, typos, category queries, traffic and transit. If so, how should we integrate them without breaking the one-contract rule (`openapi.yaml` 0.4.1)?

**Short answer.**
1. **Hamuga is a full stack that looks like ours, with local enrichments.** The findings:
   - tiles in the **OpenMapTiles schema**, with UB-specific additions: khoroo (`subdistrict`) labels, `addr_district_short`, a `khashaa` landuse layer and house numbers. **Live: a tile request returned 200 (L).**
   - a **custom search engine**, likely address-rich (Radar-shaped TypeScript types, GeoJSON-feature rendering). **Live: 403, the key is not subscribed (L).** Suggest has **no `lang` and no location-bias parameter** (C), and the style has almost no `name:en` (M).
   - a **custom POI index** with 336 Mongolian categories. **Live: 403 (L).**
   - a **Valhalla routing endpoint.** **Live: it accepts our full request body and returns Ferrostar-compatible OSRM JSON with banner and voice instructions in `mn-MN` (L).**
   - an **OpenTripPlanner REST** `plan` endpoint for bus and train, backed by 1,534 UB bus stops. **Live: 200 with `plan` (L).** However, every stop is `isActive: false, isVerified: false`, and upstream OTP removed this REST API in 2025. So transit is **promising but not production-ready**.
   - Kong behind Cloudflare
   
   **No traffic capability is visible** in the public SDK or the style.
2. **Hamuga routing is now technically viable for Ferrostar.** The desk pass left this open, and the first live test answers it positively. With `format: "osrm"`, `banner_instructions: true`, `voice_instructions: true` and `language: "mn-MN"`, the endpoint returned `code: "Ok"`, `routes`/`waypoints`, and steps with `bannerInstructions` and `voiceInstructions` (L).

   What is **not yet shown**:
   - turn-restriction and one-way correctness on P1–P6
   - latency from UB phones (the only sample, about 1.1 s, came from a non-Mongolian cloud container)
   - traffic awareness (unknown)
   - availability and an SLA
   - a Ferrostar adapter parse test
   
   Hamuga's `mn-MN` wording shows the **same defects NAV-007 fixes** (glossary C1, C2; §2.5). If Hamuga routing is used, those fixes must land in ICT Group's Valhalla as well.
3. **Recommendation (the mechanism is fixed, the content is the PO's choice):**
   - **Mechanism: always option (b), through our gateway**, with the key held server-side. Never option (a), the client SDK or direct calls from the apps.
   - **Routing: two options for the PO.** Both keep `/v1/route` unchanged for the clients:
     - **R1: keep our Valhalla** as the engine; Hamuga is a comparison source only.
     - **R2: Hamuga routing as primary or fallback behind our gateway.**
     
     The architect leans towards **R1 now, with R2 re-evaluated after the remaining live tests (§5.3)**. R2 becomes the better choice if Hamuga passes the correctness and UB latency tests, ICT Group commits to an SLA and to the NAV-007 locale fixes, and especially if Hamuga turns out to have traffic.
   - **Integration shape: (c) via (b), or (d).** Option (d) means that ICT Group, as an internal platform, adapts its endpoints or delivers data to fit our contract. Examples: routing (already fits), a PMTiles overlay of the enrichment layers, a search endpoint with `lang` and location bias. Option (e), a data-level variant of (d): ICT Group delivers its **non-OSM** data, and we run it as a separate store, never merged with OSM records (OSMF Collective Database guideline, §3.4).
   
   Per capability, as NAV-010 AC 7 asks:

| Capability | Recommendation | Condition / reason |
|---|---|---|
| Tiles | **Do not use** as the basemap. **Use later**, optionally, as a separate overlay (khoroo boundaries and labels, house numbers, khashaa). Preferred form: an **enrichment PMTiles overlay delivered by ICT Group** (option d/e) | PMTiles gives offline use, no key, no per-tile dependency, and an already-accepted style (ADR-0004). Switching schema (Protomaps → OMT) would redo the UX style work. Live tile access works (L) |
| Search / POI | **Blocked, then use later (Phase 1–2), conditional on the revised live test (§5.2)** | 403 today. No `lang`, no location bias and almost no `name:en`, so the English UI (D28) and distance ranking (D30) are exit criteria. Photon stays the primary engine and the fallback. Options (d) (endpoint with `lang` and bias) or (e) (separate index we run) remove those gaps |
| Routing (car/foot) | **PO option: R1 (our Valhalla) or R2 (Hamuga primary or fallback, behind our gateway)** | Ferrostar-compatible output is proven (L). Open: correctness, UB latency, traffic, SLA, NAV-007 wording fixes (§5.3) |
| Transit (bus/train) | **Promising, not production-ready. Phase 4 candidate** | No OSM equivalent. Endpoint answers (L). But all 1,534 stops are `isActive: false` / `isVerified: false` (M), and the OTP REST `plan` API was removed upstream in OTP 2.8.0 (2025-09-10). Needs ICT Group to confirm stop data status and an API roadmap (GraphQL or a GTFS export) |
| Traffic | **Unknown. Ask ICT Group** | Nothing in the public SDK or the style. Backlog owed decision 4 stays open. The §5.3 time-of-day test detects traffic-aware costing |

The choice is the PO's decision. The internal ICT Group terms and approval in §3.4 must be in writing before any integration story starts.

---

## 2. What was found (evidence)

### 2.1 The SDK package (`hamuga-imap-sdk@0.1.0`)

| Item | Finding | Label |
|---|---|---|
| Publisher | npm maintainer `developer_ict` (an ICT Group address). Created 2025-12-10. 10 versions: 0.0.1 → 0.1.0; 8 of them published between 2026-08-21 and 2026-08-25. Latest is 0.1.0 (2026-08-25) | M (registry metadata) |
| Licence | README says **Apache-2.0**. `package.json` has **no `license` field** and no `repository`. Permissive, if confirmed | D / M |
| Dependencies | `axios ^1.13.2` (MIT). Peer dependency `maplibre-gl ^5.14.0` (BSD-3). **Browser JavaScript only; there is no Android or iOS SDK** | M |
| Default hosts | Gateway `https://gateway.hamuga.mn`, style `https://cdn.hamuga.mn/style.json` | D, C |
| Auth | Every request gets the header `x-api-key: <key>`. The README calls it a "client-safe key" and warns not to put server credentials in browser code. A 403 means "the API key is not subscribed to this service" (per-service subscription). **Confirmed live: search and POI returned 403 "You cannot consume this service"** | D, C, L |
| Search | `POST /engine/suggest` with JSON `{value, page, perPage}`. Response envelope `{data: {items, pagination: {currentPageNo, perPage, totalPages, totalRecords}}}`. The UI renders `item.properties.name`, so items are GeoJSON-feature-like | C |
| POI | `POST /engine/poi` with `{area, categoryIds, filter, llx, lly, urx, ury, name, page, size, workHour}` (bbox is SW/NE in lon/lat). `GET /engine/poi/coordinate?lat&lon&zoom` returns `{id, type, properties}` | D, C |
| Routing | `POST /route/other/v1/route` with `{locations: [{lat, lon}], costing: "auto"}`. **`costing` is hard-coded to `auto`** in the SDK, although the README says "drivingOrWalking". The SDK expects `trip` and `requestParameters`, the **native Valhalla** format. **Live: the server also honours `format: "osrm"`, banner and voice instructions and `language` (§2.5)** | C, L |
| Transit | `GET /route/routers/default/plan?fromPlace=lat,lon&toPlace=lat,lon&mode=WALK,BUS[,TRAIN]`. The response has `plan`. These are the **OpenTripPlanner REST `plan`** parameters and response key | C, I, L |
| Tiles | Default `${host}/tile/tiles/{z}/{x}/{y}.pbf` (**not** `/api/tiles/...` as noted before this spike). The SDK rewrites two legacy hosts: `imap.hamuga.mn/api/tiles/` and `backend.example.com/tiles/`, the placeholder in the public style. It adds `x-api-key` only to matching tile URLs. It also registers a `hamuga://` MapLibre protocol | D, C |
| Types vs code | The `HamugaImapAddress` type is **field-for-field identical to Radar's `RadarAddress` type** in `radar-sdk-js` (Apache-2.0). Examples: `addressLabel`, `confidence: exact/interpolated/fallback`, `dma`, `dmaCode`, `countryFlag`, and the geocode layers `place/address/postalCode/…/coarse/fine`. The autocomplete UI options also mirror Radar's. The UI code reads `properties.name` instead. **So the types say nothing reliable about Hamuga's backend engine.** The real response shape is **U** until search is enabled on the key | C, M (compared with the Radar source) |
| Location bias | The autocomplete UI stores `near` (`setNear`) but **never sends it**. Suggest has no `lat/lon`, `lang` or `bbox` parameter | C |
| Selection behaviour | On selection, the UI runs a **second** request, `POST /engine/poi {area: false, name: <selected name>}`, and returns `items[0]`. A selected suggestion can therefore resolve to a **different** object with the same name | C |
| UI quality issues (if we used the SDK UI) | (1) Result names go into `innerHTML` **without escaping**, an XSS risk if any POI name contains markup. (2) Hard-coded English strings `"No results"` and `"Search address"`, which breaks CLAUDE.md rule 7 (localisation). (3) The README example sets `attributionControl: false`. (4) `debug: true` logs full responses to the console | C, D |
| Errors | Typed errors: `HamugaImapRequestError` (`statusCode`, `gatewayMessage`, `requestId`) and `HamugaImapResponseError` (200 with the wrong shape) | D, C |

### 2.2 The public style (`https://cdn.hamuga.mn/style.json`)

| Item | Finding | Label |
|---|---|---|
| Identity | `name: "HamugaGIS v1.7"`, center `[106.9046, 47.9143]`, zoom 12. `Last-Modified: 2026-09-30 08:03 GMT`, so it changes often. Served via Cloudflare with `Access-Control-Allow-Origin: *` | M |
| Source | One vector source `hamuga` with the placeholder tiles URL `https://backend.example.com/tiles/{z}/{x}/{y}.pbf`. The style is **unusable without the SDK's `transformRequest`** or a proxy that rewrites the URL. **No `attribution` field** in the source and no `metadata` | M |
| Layers | 195 in total: 130 line, 39 symbol, 24 fill, 1 background, 1 fill-extrusion. Source layers: `transportation` (123 layers), `landuse`, `poi`, `place`, `waterway`, `transportation_name`, `landcover`, `aeroway`, `boundary`, `water`, `building`, `mountain_peak`, `water_name`, `park`, `housenumber`, `aerodrome_label`. **This is the OpenMapTiles schema** | M |
| Local enrichments | `place` classes `district` and `subdistrict` (khoroo). The `SubDistrict labels` layer formats `addr_district_short` + `name` (for example «БЗД 4-р хороо» style labels, I). Layer `landuse-khashaa` (fenced ger plots). `housenumber` labels from z15. POI classes include `train_station`/`railway` (layer «Tomor zam», from z12) | M, I |
| Label fields | `name`, `name:en` (**state labels only**), `name_int` (peaks), `ref`, `housenumber`, `addr_district_short`. **No `name:mn`**. Our rule `name:mn` → `name` → `name:en` would degrade to `name`, which in Mongolia is mostly Cyrillic Mongolian anyway (I). **For the English UI, Hamuga tiles offer almost no English labels** | M |
| Traffic / transit layers | **None.** No traffic or congestion layer, and no bus route layer. Traffic signals are shown as POI icons only | M |
| Glyphs | `https://cdn.hamuga.mn/fonts/{fontstack}/{range}.pbf`, fonts `GIP Regular / Medium / SemiBold`. Public; the Cyrillic range 1024–1279 exists (41.8 kB). **Font licence U** | M |
| Sprite | `content_v14`: 131 icons, including `bus_stop`, `bus_station`, `bus_active`, `Airport`, `atm`. Public, CORS `*` | M |

### 2.3 Public web app and infrastructure

| Item | Finding | Label |
|---|---|---|
| Web app | `imap.hamuga.mn` (canonical `map.hamuga.mn`), a Next.js app titled «Үндэсний орон зайн мэдээллийн систем» ("National Spatial Information System"). `hamuga.mn` is a "Тун удахгүй" (coming soon) page. **No terms of use, attribution or pricing text** on either page | M |
| POI categories | The page HTML embeds **336 categories** (a tree with a Mongolian `name`, an English key `name:en` and `parentId`). Many keys are OSM tag values (`townhall`, `polling_station`, `ranger_station`, `parking_entrance`, `charging_station`), which suggests OSM-derived POIs with local additions (I). Transport group 184: `train_station` = «**Вагоны буудал**» (id 186), `airport` (187), `bus_terminal` (188), `taxi_parking` (189) | M |
| Bus stops | The page HTML embeds **1,534 bus stops** (`busStopId`, `busStopName` such as «Баянзүрх дүүрэг /Хойд/», coordinates). The bbox is 106.52–108.39 E, 47.35–48.20 N (UB including outer districts). `updatedAt` 2026-08 (1,522) and 2026-09 (14). **Every stop has `isActive: false, isVerified: false`.** What these flags mean (unreviewed data, or unused fields) is U and must be confirmed by ICT Group before transit is treated as reliable | M |
| Hosting | `gateway`, `cdn`, `imap` and `map.hamuga.mn` all resolve to Cloudflare anycast. The gateway returns Kong headers (`x-kong-request-id`, `x-kong-response-latency`) and Kong's 404 body `{"message":"no Route matched with those values","request_id":…}`. **So the chain is Cloudflare → Kong → services, and the origin location is U.** TLS terminates at Cloudflare, at whichever PoP serves the user. From our sandbox it was a US PoP, which says nothing about UB | M |
| Gateway CORS | Preflight `OPTIONS /engine/suggest` → 204, `Access-Control-Allow-Origin: *`, `Allow-Headers: *`, max-age 3600. Browser-side use is technically possible | M |

### 2.4 Values required by NAV-010 AC 1 (status after the desk pass and the first live probes)

| Value | Tiles | Search / POI | Routing | Transit | Traffic |
|---|---|---|---|---|---|
| Endpoint | `/tile/tiles/{z}/{x}/{y}.pbf` (D, C, **L: 200**) | `/engine/suggest`, `/engine/poi`, `/engine/poi/coordinate` (C; **L: 403, not subscribed**) | `/route/other/v1/route` (C, **L: 200**) | `/route/routers/default/plan` (C, **L: 200**) | U: ask ICT Group |
| Auth | `x-api-key`, per-service subscription (D, L) | same | same | same | U |
| Format | MVT `application/x-protobuf`, OMT schema (M, L) | JSON envelope + GeoJSON-like items (C). Exact fields U until search is enabled | **Native Valhalla `trip` by default, OSRM JSON with banner and voice instructions on request (L)** | OTP `plan` (C, L). Itinerary content not yet assessed | U |
| Instruction language | — | No `lang` parameter (C) | **`mn-MN` honoured (L)**, with glossary C1/C2 defects (§2.5) | U | — |
| Reverse geocoding | — | **No address reverse endpoint in the SDK.** `poi/coordinate` is a POI lookup near a point (C). Address reverse U: ask | — | — | — |
| Coverage | UB and Mongolia? Style center UB; `State`/`Town` layers suggest national (I). U: measure (§5) | U: measure | UB shown (L). National U: measure (X1 Erdenet) | UB (1,534 stops, M), stop status unclear. Intercity U | U |
| Update frequency | Style changed 2026-09-30 (M). Data U | U | U | Stops updated 2026-08/09 (M) | U |
| Rate limits, SLA, pricing | U: internal ICT Group agreement (§3.4) | U | U | U | U |
| Licence and attribution | SDK Apache-2.0 (D). **Data terms U**. Style declares none (M) | U | U | U | U |
| Server location | Behind Cloudflare. Origin U: ask, and read the `cf-ray` colo from UB phones (§5.5) | same | same | same | U |

### 2.5 First live probes (orchestrator, 2026-09-30, one call per service)

The key was provided by the PO and is stored outside the repository. Calls were made from a cloud container outside Mongolia.

| Service | Request | Result | Label |
|---|---|---|---|
| Routing, native | `POST /route/other/v1/route` `{locations, costing: "auto"}`, P1 Sükhbaatar Square → Zaisan (47.8858, 106.9057) | **HTTP 200**, native Valhalla `trip`, `status` 0, **4.341 km, 324 s, 11 maneuvers**. About **1.1 s** end to end from the non-Mongolian container (one sample; not representative of UB) | L |
| Routing, `mn-MN` | the same, plus `language: "mn-MN"` | Mongolian instructions are returned | L |
| Routing, Ferrostar body | the same, plus `format: "osrm"`, `banner_instructions: true`, `voice_instructions: true` | **HTTP 200, `code: "Ok"`, `routes` and `waypoints`; steps carry `bannerInstructions` and `voiceInstructions`.** Sample voice: «баруун эргээд AH3, Энх тайваны өргөн чөлөө руу ор.» Sample banner: «Энх тайваны өргөн чөлөө». **This answers the spike's first live test (§5.1 item 3b) positively: Ferrostar-compatible output passes through** | L |
| Transit | `GET /route/routers/default/plan` with `fromPlace`, `toPlace`, `mode=WALK,BUS` | **HTTP 200** with a `plan` object. Itinerary count, legs and stop names not yet assessed | L |
| Tiles | `GET /tile/tiles/12/3264/1436.pbf` | **HTTP 200**, `application/x-protobuf`, 968 bytes. Layers not yet decoded | L |
| Search | `POST /engine/suggest` | **HTTP 403** "You cannot consume this service": the key is not subscribed. The PO was asked to enable it | L |
| POI | `POST /engine/poi` | **HTTP 403**, same message. Same request to the PO | L |

**Notes on the routing sample:**
- The Zaisan point used (lon 106.9057) differs from our contract's P3 (47.8858, 106.9173, `openapi.yaml` example `carMn`). So 4.341 km / 324 s is **not directly comparable** with our Valhalla's P1→P3 result. §5.3 reruns the exact P-points against both engines.
- `voiceLocale`, the geometry precision (polyline6) and a parse by Ferrostar's OSRM adapter were **not checked**. They stay in §5.3.

**Hamuga's `mn-MN` wording has the defects our glossary fixes (relevant to NAV-007).** The observed strings include:
- a bare «зүүн замаар», which can mean "by the left road" or "by the east road" (glossary **C2**: a relative «зүүн»/«баруун» needs a glossary form such as «тийш», and a cardinal direction needs «зүг»)
- «... дээр өмнөд жолоодоорой»
- curt imperatives such as «баруун эргээд ... руу ор» (glossary **C1**: the polite register the PO approved; C2 also applies to the bare «баруун эргээд»)

Also, the road reference «AH3» is spoken in Latin script inside a Mongolian prompt. How the TTS engine reads it is U; it is a NAV-007 check.

Interpretation (I): Hamuga probably runs the **upstream Valhalla `mn-MN` locale** without the NAV-007 fixes. If Hamuga routing is adopted (R2), the NAV-007 locale fixes must be applied in ICT Group's Valhalla too (option d). Rewriting instruction strings in our gateway would be fragile and is not proposed. Contributing the fixed locale file upstream to Valhalla would help both deployments.

---

## 3. Analysis

### 3.1 Capability map against our stack and contract (`openapi.yaml` 0.4.1)

| Capability | Our stack / contract | Hamuga | Fit and gaps |
|---|---|---|---|
| **Tiles** | Planetiler → Protomaps basemap schema, **one PMTiles file** served with HTTP Range (`GET /tiles/basemap.pmtiles`), no auth, offline-capable, style owned by UX (ADR-0004) | XYZ MVT per tile, **OMT schema**, `x-api-key` on every tile, 195-layer style tied to the Hamuga CDN (fonts, sprite) | **Not a drop-in.** The schemas differ, so our style cannot read Hamuga tiles and the Hamuga style cannot read our PMTiles. Per-tile keyed requests rule out our offline story, unless ICT Group provides a bulk file. **Value:** khoroo boundaries and labels, `addr_district_short`, house numbers and khashaa plots, which the Protomaps basemap does not carry in this form. **Best fit: ICT Group delivers these layers as a small enrichment PMTiles overlay** (option d/e) that UX styles next to our basemap |
| **Search** | `/v1/search` = Photon 1.3.0 pass-through (GET, `q`, `lat/lon` bias, `lang`, `limit`). Known gaps (QA run 6): **B4** «Сүхбатар» (typo) returns far-away soums; **B13** «Галт тэрэгний буудал» (category phrase) returns a hotel 527 km away; no ү/у folding (ADR-0006 client workaround); sparse house numbers | POST, `value/page/perPage`, **no bias, no `lang`**, own envelope. Separate POI endpoint with category IDs and opening-hours filter. **Not reachable with the current key (403)** | **Different contract shape.** Clients cannot switch without a gateway adapter or a second parser. NAV-003 already parses Photon's GeoJSON `FeatureCollection` with `osm_*` properties. **No `lang`** breaks the English UI rule (D28: English result names from the `lang=en` response). **No bias** breaks distance ranking (D30: device position or map centre). **Possible value:** addresses, house numbers, khoroo names, a Mongolian category taxonomy. Whether its fuzzy matching handles «Сүхбатар» is U (§5.2) |
| **Reverse** | `/v1/reverse` = Photon `/reverse` | Only `poi/coordinate` (POI near a point, with zoom) | **No equivalent documented.** Keep Photon |
| **Routing** | `/v1/route` = Valhalla 3.9.0 pass-through, `format: osrm` + `banner_instructions` + `voice_instructions` + `language: mn-MN` + `units: kilometers`, parsed by Ferrostar's OSRM adapter. NAV-007 patches `mn-MN` wording | **Valhalla endpoint that accepts the same body and returns OSRM JSON with banner and voice instructions in `mn-MN` (L)** | **Technically compatible (L)**, so the gateway could forward `/v1/route` to Hamuga with no client change. Open points: `units`, `costing: pedestrian`, `costing_options.auto.exclude_unpaved` (NAV-001 AC 19) and `alternates` are untested; the Valhalla version is U; turn-restriction and one-way correctness are U; the NAV-007 wording fixes are missing (§2.5); p95 < 500 ms from the production host is U (the one sample was about 1.1 s from outside Mongolia); reroute volume (every client reroute is one upstream request) needs a quota |
| **Transit** | **None** (research §4.9: MOTIS or OTP plus GTFS, roadmap Phase 4) | OTP REST `plan`, modes WALK/BUS/TRAIN, 1,534 UB stops. **Answers 200 (L)** | **New capability, no OSM equivalent, but not production-ready.** Ferrostar does not do transit guidance, so this is itinerary display (list + map), not turn-by-turn. Risks: (1) **all 1,534 stops are `isActive: false, isVerified: false`** (M), meaning unknown; (2) the OTP REST API was deprecated in OTP 2.5 (2024-03), disabled by default in 2.6 (2024-09) and **removed in 2.8.0 (2025-09-10)** in favour of the GraphQL APIs. So ICT Group runs an OTP of version 2.7 or older, or OTP 1.x, and the endpoint must change when they upgrade; (3) GTFS-Realtime and timetable freshness are U; (4) OTP is **LGPL-3.0**, which does not matter to us while ICT Group runs it as a service, and would matter only if we self-host (still fine as an unmodified server) |
| **Traffic** | None yet (Phase 3, backlog owed decision 4). Valhalla supports live and predicted speeds | **Nothing visible** | U. Ask ICT Group. The §5.3 time-of-day routing test detects whether Hamuga's routing durations vary with congestion |

### 3.2 Hamuga layers that could fix our known search gaps

| Gap (QA run 6 / openapi 0.4.1) | Hamuga asset that might help | How to verify (§5.2) | Caveat |
|---|---|---|---|
| **Typos**: B4 «Сүхбатар», B3 «Сухбаатар» (ү/у) | `/engine/suggest`, fuzziness U | Typo tier, as typed, with no client assistance | If Hamuga also fails, ADR-0006 client assistance stays our fix |
| **Category phrases**: B13 «Галт тэрэгний буудал» | POI category 186 `train_station`, **named «Вагоны буудал»**, not «Галт тэрэгний буудал». POI search by `categoryIds` + bbox. Style layer «Tomor zam» shows `train_station` from z12 | Category tier: suggest as typed; then `poi` with the category ID and a bbox | Needs a phrase → category-ID mapping (in the gateway or client). The Mongolian phrases must come from the **glossary**. «Вагоны буудал» vs «Галт тэрэгний буудал» is a glossary question for the BA, not ours to decide |
| **Addresses (khoroo, ger districts)**: B14, B15, C2, C3 | `subdistrict` places + `addr_district_short` in tiles; `neighborhood`/`county` style fields in suggest (per the types, U) | Address tier (§5.2) | Measure, don't assume. The types are Radar's, not Hamuga's |
| **House numbers** | `housenumber` tile layer (z15+); `number`/`street` fields in suggest (U) | House-number rows with **independently labelled** answers (§5.2) | Sampling from either engine's own data biases the comparison |

### 3.3 Integration options

| Option | How | Pros | Cons |
|---|---|---|---|
| **(a) Client-side SDK** | Web demo imports `hamuga-imap-sdk`. Native apps call the REST API directly with an embedded key | Fastest demo; no backend work | **Web only** (no native SDK). The key ships in the public web bundle and in APK/IPA files, so it is extractable. User IP, queries and coordinates go straight to ICT Group/Cloudflare (D9). Two contracts (ours + Hamuga's), which breaks the one-contract rule. SDK UI issues: XSS, English strings, lossy selection, attribution off by default. No fallback when Hamuga is down. Unstable 0.x API (8 releases in 5 days) |
| **(b) Through our gateway as upstreams** | nginx (or a small adapter service where response shaping or merging is needed) forwards to `gateway.hamuga.mn` and adds `x-api-key` from a server-side env var. New or extended operations in `openapi.yaml` | **One contract.** The key never leaves the server. User IP hidden from Hamuga (only the gateway IP is seen). Gateway rate limits, caching and a circuit breaker. **Fallback to the OSM stack** per capability. Client code stays Photon/Valhalla-shaped. A partner swap is invisible to clients (NAV-010 R3). **For routing it is pure forwarding**, since the output already fits (L) | Backend work per capability. An extra hop: with staging in Singapore (D25), UB → SG → Cloudflare → ICT origin (probably UB) doubles the international path, which goes away when production runs in Mongolia (NAV-009). Search needs adapter code, because nginx cannot merge two engines or add `lang`/bias |
| **(c) Hybrid** (a content choice, orthogonal to the mechanism) | Choose per capability, e.g. **our tiles + Hamuga search augmentation + transit**, with routing either ours (R1) or Hamuga's (R2) | Uses each side's strength. Transit arrives without building GTFS | Two data sources on screen means multi-source attribution. Search merging needs rules that respect ODbL (§3.4) |
| **(d) ICT Group adapts to our contract** (new; possible because Hamuga is internal) | ICT Group changes its endpoints or delivers files so that they fit `openapi.yaml` as written. Examples: **routing:** Valhalla OSRM output with `mn-MN` (**already works, L**), plus the NAV-007 locale fixes and our costing options; **tiles:** a **PMTiles overlay of the enrichment layers** (khoroo, house numbers, khashaa) with an attribution field; **search:** an endpoint with `lang` and location bias (`lat`/`lon`), ideally returning Photon-compatible GeoJSON; **transit:** a supported API (OTP GraphQL) or a GTFS export we can self-host | Our gateway stays a thin forwarder; no adapter code. Clients keep one parser. Removes the `lang`/bias/English-name gaps at the source. Offline-capable overlay | Depends on ICT Group's roadmap and capacity. The contract stays owned by us (architect), so ICT Group implements against a published spec. Needs an internal agreement on versioning and change notice |
| **(e) Data delivered into a separate store we run** (new; data-level variant of d) | ICT Group delivers its **non-OSM** data (e.g. surveyed address points with house numbers, khoroo boundaries, own POIs) as a periodic export. We run it as a **separate store**: a second search index next to Photon, and a separate PMTiles overlay. It is **never merged with OSM records**. Results are combined only at query or render time, under the OSMF Collective Database guideline (§3.4 item 2) | No runtime dependency on Hamuga. Latency and availability under our control (search p95 ≤ 300 ms is easier). Offline possible. `lang` and bias handled by our own index | Pipeline work (import, rebuild, freshness). ICT Group must state **which records are non-OSM** (provenance) and grant a written licence. Feature-type/region partitioning limits how the two sources can be combined |

**Recommendation.**
- **Mechanism:** (b), always. **Option (a) is rejected for our apps.** It is acceptable only for a throwaway internal demo on a developer's machine, with a developer key that is never committed.
- **Content:** (c) via (b), moving to (d)/(e) where ICT Group can deliver. Per capability:
  - **Basemap:** stays our PMTiles. Enrichments come later as a separate overlay, preferably a PMTiles file from ICT Group (d/e).
  - **Routing:** the PO chooses between
    - **R1:** our Valhalla, with Hamuga as a comparison source
    - **R2a:** Hamuga primary, with our Valhalla as fallback on timeout, 5xx or open circuit
    - **R2b:** our Valhalla primary, with Hamuga as fallback
    
    The architect leans towards **R1 until §5.3 passes**, then R2a if Hamuga shows traffic or clearly better data, and ICT Group applies the NAV-007 fixes and commits to an SLA. R2b adds little: two different instruction wordings in one trip, and only helps when our own Valhalla is down. Under R2a we still run our Valhalla for fallback, so the operating cost saving is small unless the fallback is later dropped.
  - **Search:** Photon stays primary. Enable search/POI on the key, then run the revised §5.2. Adopt Hamuga as a second source only if it passes. The preferred form is (d) (an endpoint with `lang` and bias) or (e) (a separate index).
  - **Transit:** Phase 4 candidate after ICT Group confirms the stop status flags and an API roadmap.

**Contract sketch (not applied; `openapi.yaml` stays 0.4.1 until the PO decides).**
- **Routing (R2a/R2b):** **no contract change.** `/v1/route` keeps its request and OSRM response shape; only the gateway's upstream changes. Optionally, a diagnostic response header `X-Upstream: valhalla | hamuga` (minor bump) so QA and clients' bug reports can tell which engine answered. `x-upstream` in the spec would list both upstreams. The gateway allow-list gains `/route/other/v1/route`.
- **Transit:** `GET /v1/transit/plan`, a pass-through of Hamuga `…/routers/default/plan` (`fromPlace`, `toPlace`, `mode`, later `date`, `time`, `arriveBy`). Key injected, `x-upstream: {service: hamuga-otp}`, errors 400/429/502/503/504 as `GatewayError`. **Because upstream OTP removed this REST API, the contract must not mirror OTP's REST shape blindly.** Decide in the transit story whether we define our own itinerary schema (stable against an OTP GraphQL migration) or pass through.
- **Search**, two shapes to decide in a follow-up ADR after §5.2:
  - **(i)** `GET /v1/places/suggest` and `/v1/places/poi` as pass-through (clients parse a second shape)
  - **(ii)** keep `/v1/search`, and have a small adapter (or ICT Group under option d, or our second index under option e) return one Photon-compatible `FeatureCollection` with `properties.source: "osm" | "hamuga"`
  
  The architect leans towards **(ii)**, because the NAV-003 web client and the NAV-005 native clients keep one parser. The merge rules must respect §3.4 (no blending of records, no cross-source deduplication at the data level).

### 3.4 Licensing

*This is an engineering reading of ODbL 1.0 and the OSMF community guidelines, not legal advice. ICT Group's counsel confirms it.*

1. **Is Hamuga data derived from OSM?** Very likely, at least in part (I): OMT schema, OSM tag values as POI keys, OSM-style `name`/`name:en`/`name_int`, and class values like `minor_construction`. The consequences depend on how ICT Group built its database:
   - **(a) If Hamuga's public database is a Derivative Database of OSM** (OSM data adapted or merged with ICT Group's additions in the same database), then it is **itself under ODbL share-alike**. It is Publicly Used through the public web app, the tiles and the API. So:
     - ODbL §4.4: it may be offered only under ODbL (or a compatible licence). This covers **all of that derivative database, including ICT Group's own additions merged into it**.
     - ODbL §4.6: ICT Group must offer the derivative database (or a file of the alterations) to anyone who receives it or a Produced Work made from it.
     - ODbL §4.3: every Produced Work (rendered tiles, maps, search results displayed) must carry an OSM attribution notice.
   - **What (a) means for us:**
     - **Merging is legally possible but has a price.** Merging Hamuga-derived records with our OSM data creates a derivative database of ours, which is also ODbL. That is compatible, because our data is already ODbL. But if we publicly use it, we must offer it (or the method to recreate it) under ODbL. In effect, **we would be republishing ICT Group's enrichments under ODbL**. Because ICT Group is the PO's company, whether to release those enrichments is an **internal business decision**, not a licence obstacle (open question 7).
     - Restrictions that do not come from ODbL still apply: government data licences (the «Үндэсний орон зайн мэдээллийн систем» context), contracts, personal data. ICT Group must state them.
     - **Attribution:** «© OpenStreetMap contributors» (which we show anyway, CLAUDE.md rule 8), plus the Hamuga credit, plus "© OpenMapTiles" if OMT-schema tiles are shown. Hamuga's own public products currently show **no OSM attribution**: the style declares none and the SDK README disables the attribution control. Under (a) this is an ODbL §4.3 compliance gap for ICT Group to fix. We must not copy that pattern.
   - **(b) If ICT Group keeps its non-OSM data as a separate collective store** (not merged with OSM records), that part is **not** under share-alike. It needs an ordinary written licence from ICT Group, and option (e) with the collective rules below applies.
2. **Our own database must stay a collective database, not a derivative one, unless the PO deliberately chooses otherwise.** The OSMF Collective Database guideline says data stays independent when, for a given feature type (or property) and region, the data is **either all OSM or all non-OSM**. Non-OSM data may also fill gaps (feature types absent from OSM), as long as it contains no OSM data. The guideline explicitly does **not** cover combining OSM and non-OSM data of the same kind by removing duplicates: that combined database may put the non-OSM data under share-alike. **Rules for us:**
   - Keep Hamuga data in **separate stores / separate responses / separate map layers**: a separate upstream, a separate index (option e) and a separate overlay source in the style. **Never import it into our Photon index, Valhalla graph or basemap PMTiles.**
   - **Partition by feature type and region when combining.** Example: UB address points with house numbers come **only** from Hamuga, and POIs come **only** from OSM (or the reverse), with the partition written in the follow-up ADR.
   - **No cross-source deduplication at the data level.** In a merged search list (contract option ii), results are **interleaved, not blended**. Each item keeps its source, and there is no field-level merge of an OSM and a Hamuga record. Whether per-response display suppression of an obvious duplicate is acceptable is a question for counsel. The default is no suppression.
   - Option (e) is valid only for records ICT Group states are **non-OSM**. Its OSM-derived records add nothing we don't already have from OSM.
   - Geocoding results: OSMF's geocoding guideline treats individual results as insubstantial extracts that need attribution only, as long as we do not systematically rebuild the database from them. **Do not cache Hamuga results in bulk** (unless delivered as data under option e with a licence).
   - A rendered map combining our OSM basemap with a Hamuga overlay is a **Produced Work**. Attribution must credit every source.
3. **Attribution text (proposed; BA/UX finalise it in resource files):** «© OpenStreetMap contributors», plus "© OpenMapTiles" if Hamuga OMT tiles are shown, plus the Hamuga credit in the wording ICT Group requires (U). Search, routing and transit result lists need no per-item credit, but the screen or an info panel must credit Hamuga (OSMF attribution guideline, by analogy). Under R2a, Hamuga-computed routes need the Hamuga credit on the navigation screen or its info panel.
4. **Other licences:**
   - SDK: Apache-2.0 per the README, but missing from `package.json`. If the code is derived from `radar-sdk-js` (Apache-2.0), Apache §4 NOTICE duties apply to ICT Group. **We do not plan to ship the SDK**, so this does not touch us.
   - **GIP fonts:** licence U. Do not bundle; load only from the Hamuga CDN, and only if we use their style.
   - OTP: LGPL-3.0, relevant only if we self-host.
   - No GPL component is proposed.
5. **Internal ICT Group terms and approval** (the PO relays them; written answers are needed before any integration story):
   1. Which internal agreement covers our use of each API: cost allocation, quotas, and who approves it at ICT Group?
   2. May we **proxy through our server** and serve results to our own Android/iOS/web apps under our brand? May Hamuga routing be the **primary navigation engine** (R2a), including reroute traffic?
   3. Caching: may we cache responses (how long, tiles vs search)? May we store data? **Offline use** of tiles or an enrichment PMTiles overlay (option d/e)?
   4. Display: may Hamuga layers appear **beside OSM layers on one map**? May Hamuga search results appear in one list with OSM results?
   5. Required attribution text, logo and placement.
   6. Data provenance: which parts derive from OSM (§3.4 item 1a vs 1b)? How does ICT Group meet ODbL attribution and share-alike for its derived databases? Are any parts government data («Үндэсний орон зайн мэдээллийн систем»), with their own licence? Which records are non-OSM and could be delivered under option (e)?
   7. **Server-side key per environment:** can we get **server keys, IP-restricted to our gateway**, one each for dev, staging and production, rather than the browser "client-safe" key? What are the rotation and revocation process and the SLA? **Please also enable search and POI on the key** (currently 403).
   8. Privacy: what does Hamuga log about requests (queries, coordinates, IP)? Retention? Is Cloudflare TLS termination outside Mongolia acceptable under D9?
   9. Traffic: is there a live or historical traffic product, and in what form (OSM way IDs, own network)? Does routing already use it?
   10. Transit: what do `isActive` / `isVerified` mean, and when will stops be verified? Which OTP version runs? Is a move to the GraphQL API planned, and when? Is GTFS / GTFS-RT available for export?
   11. May we publish evaluation results (result names and coordinates) in our **public** repository (decisions "Items to confirm" 5)?
   12. **Routing (option d):** will ICT Group apply the NAV-007 `mn-MN` locale fixes and support our costing options (`pedestrian`, `exclude_unpaved`, `alternates`)? Which Valhalla version runs, and how are changes announced?

### 3.5 Security (the repository is PUBLIC, D35)

1. **The key never enters the repo, a client bundle or a log.**
   - `.env.example` gets `HAMUGA_API_KEY=` (empty) and `HAMUGA_BASE_URL=https://gateway.hamuga.mn`. The real value lives only in the host's `.env` or a secret store, which is already git-ignored per NAV-008 AC 21. The key used for the first live probes is held by the orchestrator outside the repository; it is a single shared key and should be replaced by per-environment server keys (§3.4 question 7).
   - **Never** use a `VITE_*` variable for it: Vite inlines `VITE_*` values into the public web bundle.
   - Never put it in `mobile/**` resources, `BuildConfig` or `Info.plist`.
2. **How to proxy:**
   - The gateway sets the header `x-api-key` from the environment at container start. The existing envsubst template mechanism works; the rendered config lives only inside the container. An njs variable is an alternative.
   - The gateway **strips any client-supplied `x-api-key`** and forwards only allow-listed paths (`/engine/suggest`, `/engine/poi`, `/route/routers/default/plan`, and `/route/other/v1/route` if R2 is chosen), never a wildcard proxy to `gateway.hamuga.mn`.
   - TLS to the upstream with certificate verification (`proxy_ssl_verify on`, `proxy_ssl_server_name on`).
3. **Logging:**
   - The existing `json_privacy` access-log format already excludes headers and query strings (ADR-0002 §3.4). Keep `error_log` at `crit` for these locations, because error lines include the request line.
   - Hamuga's error body `request_id` may be logged (it is not PII) for support.
4. **Leak prevention:**
   - Enable GitHub **secret scanning with push protection** on the public repo.
   - Add a `gitleaks` pre-commit/CI check with a custom rule once the key format is known.
   - QA test harnesses read the key from the environment and **redact** it in reports and Playwright traces (request headers appear in traces).
5. **Blast radius:** per-environment keys, quotas set at ICT Group, a documented rotation runbook, and a gateway circuit breaker. A revoked key then degrades to the OSM stack (search; routing under R2a) or to a localised "transit unavailable" state, rather than failing the app.
6. **Supply chain (option a only):** the SDK is 0.x, has no repository link and no licence field, and its UI builds `innerHTML` from API data. That is one more reason not to ship it.

---

## 4. Non-functional notes

- **Latency.**
  - The only live number is about **1.1 s for one routing call from a cloud container outside Mongolia**. It includes an intercontinental path and says nothing about UB, but it means **route p95 < 500 ms (NAV-001 AC 35) is not shown for Hamuga**.
  - Under R2a, the NFR applies end to end via our gateway from the production host. Suggested failover: a gateway upstream timeout of about 2 s for Hamuga, then our Valhalla. The exact value is set by measurement (§5.5).
  - Rerouting stays on the client (Ferrostar), but every reroute is one upstream call, so quotas must cover the peak reroute rate.
  - Search: p95 ≤ 300 ms via the gateway (Photon, NAV-001 AC 36). A Hamuga-backed search must meet the same target from the production host, or it runs **in parallel with Photon under a deadline** (for example 250 ms), and late Hamuga results are dropped. Option (e) avoids this problem.
  - On staging (Singapore, D25/D26), the extra hop crosses borders twice, so staging latency for Hamuga-backed operations is **not representative** of production in Mongolia (NAV-009).
- **Availability.** Hamuga is an optional augmentation in every option:
  - search degrades to Photon only
  - routing under R2a fails over to our Valhalla
  - transit has no fallback, so it shows the localised unavailable state, using the existing error codes 502/503/504
  
  An SLA from ICT Group is U (§3.4 question 7), and a 7-day synthetic probe is part of §5.3.
- **Privacy.**
  - Via (b), ICT Group sees queries and coordinates but not user IPs.
  - Search sends no coordinates to Hamuga today, because suggest has no bias parameter. Option (d) with bias would send rounded coordinates (D30 rounding applies).
  - **Routing under R2 sends every trip's origin, destination and reroute positions (actual GPS positions) to Hamuga.** That needs the D9 review before outside users.
  - Transit sends origin and destination: consent text and D9 review before outside users.
  - If production runs on ICT Group servers anyway (D25), the data residency difference narrows, but purpose limitation and Cloudflare TLS termination (location U) remain D9 questions.
- **Concentration (R3).** ICT Group may also host production (D25). Keeping every Hamuga capability optional, behind our contract, with the OSM stack as the fallback, limits lock-in. This matters even for an internal platform, because roadmaps and priorities differ between teams.

---

## 5. Live test plan (continues from the first probes)

Owner split: QA (harness, golden set, runs) and architect (analysis, recommendation update). Time: 5 working days (NAV-010 AC 1), starting when search/POI are enabled on the key.

### 5.0 Preconditions
- The key is stored in a local `.env` / CI secret only. The harness reads `HAMUGA_API_KEY` from the environment and redacts it from all output, including Playwright traces.
- Requests are paced at ≤ 2 req/s (or ICT Group's stated limit). Every non-2xx response is logged with `statusCode` and `request_id`.
- Before comparing, record **one raw response per endpoint** as a JSON Schema snapshot. Result files stay out of the public repo until ICT Group answers question 11 in §3.4; commit counts and metrics only.
- Same day, same OSM build: Photon and Valhalla run via our gateway at the build recorded in `backend/data/build-info.json`.

### 5.1 Contract probes

| # | Probe | Status 2026-09-30 |
|---|---|---|
| 1 | `POST /engine/suggest {value:"Сүхбаатар", page:1, perPage:10}`: record the envelope, item fields and `properties` keys; any `lang`, `near` or `focus` parameter (docs) | **Blocked: 403** (L). Rerun once enabled |
| 2 | `POST /engine/poi` with `categoryIds:[186]` and a bbox of about 1 km around P5; `GET /engine/poi/coordinate` at P1, zoom 16 | **Blocked: 403** (L) |
| 3a | `POST /route/other/v1/route` with the SDK body | **Done: 200, native `trip`** (L) |
| 3b | the same with **our body** (`format:"osrm"`, banner/voice instructions, `language:"mn-MN"`) | **Done: 200, `code:"Ok"`, banner and voice instructions present** (L). **Still to check:** `units:"kilometers"`, `voiceLocale:"mn-MN"`, polyline6 geometry, `costing:"pedestrian"`, `costing_options.auto.exclude_unpaved`, `alternates:2`, and a **parse by Ferrostar's OSRM adapter** (Rust/wasm unit test by mobile or QA) |
| 4 | `GET /route/routers/default/plan` P6 → P1, `mode=WALK,BUS` | **Reachable: 200 with `plan`** (L). **Still to record:** `plan.itineraries[].legs[]` (mode, route, `realTime`, agency), stop names and language |
| 5 | Tiles: fetch z14 tiles at P1 and P6; record the vector layers and fields, including any TileJSON `attribution` | **Reachable: z12 tile 200, 968 bytes** (L). Layers not decoded yet |

### 5.2 Search comparison (NAV-010 AC 2), revised after the skeptic review

**Why the earlier criterion is replaced.** The desk pass proposed "Hamuga top-5 ≥ Photon top-5 + 10 pp on tier H plus B3/B4/B13". That is about 29 rows, so +10 pp is about **3 queries**, well within noise. Also, half of the house-number rows would have come from Hamuga's own house-number layer, which favours Hamuga. The same objection applies to rows taken from OSM, which favour Photon.

**Revised set (agreed with QA and BA, frozen before the first run):**
- **At least 100 query rows** in UB (the existing 42 golden rows may be reused where their answers qualify), with at least:
  - 30 address and house-number rows (khoroo, ger district, street + number)
  - 20 category/POI phrases, **with Mongolian wording from the glossary**; missing terms are requested from the BA
  - 15 typo and ү/у rows (B3, B4 style)
  - 15 **English/Latin rows for the English UI (tourist persona, D28)**, each with an expected **English** result name
  - 10 **distance-ranking** rows: chains and ambiguous names (several branches), each with a reference point; the expected answer is the nearest correct instance
  - 10 no-result rows
- **Independent labelling.** Each expected answer (name, coordinates, tolerance) is labelled **before the run** by QA and BA, with a native speaker for the Mongolian rows. The source must be independent of both engines: field or photo checks of house plates and signs, official address documents, operators' published lists, or local knowledge. Each row records its label source. Rows whose answers come from either engine's own data are either excluded or included in **equal numbers per engine**, and the result is also reported **without them** (sensitivity check).
- **Freeze.** The set and labels are committed before the first run. If their content cannot be published yet (§3.4 question 11), commit a SHA-256 of the file plus the row counts per tier.

**Runs per query:**
1. Photon via `/v1/search` with the NAV-003 profile (bias as in D30, 3 decimals, `limit=8`), as typed, **without** ADR-0006 client assistance
2. the same with assistance (the product baseline)
3. Photon without bias (a fair engine-to-engine comparison, since Hamuga has no bias)
4. Hamuga suggest as typed, `perPage=8`
5. for category rows only, additionally Hamuga `poi` with the category ID and a 2 km bbox around the reference point
6. English rows: Photon with `lang=en`, and Hamuga as typed (no `lang` exists)

**Metrics:** top-1 and top-5 hit rate per tier and per engine, with 95 % Wilson intervals; the no-result false-positive rate; unanswered queries (reported, not dropped); p50/p95 latency per engine from the same host. The per-tier numbers are descriptive: the tiers are too small to test individually.

**Exit criteria (proposal; the PO decides).** "Use Hamuga search" requires **all** of:
1. **Significance on the primary metric.** Top-5 hit rate over all answerable rows, Hamuga (or the merged list) vs Photon assisted, compared **per query** (paired). Use an exact McNemar test (a one-sided binomial test on the discordant rows) at α = 0.05, **and** a minimum effect of +10 pp. Example: with 100 rows and 20 discordant rows, Hamuga must win at least 15 of the 20 (p ≈ 0.02).
2. **English UI (D28).** In the English UI, the list the user sees shows an English name for every item, and the English-tier top-5 is not worse than Photon `lang=en` by more than 5 pp. Hamuga items without an English name are dropped from English results, or the English UI stays Photon-only. The failure is fixed at the source with option (d) or (e).
3. **Distance ranking (D30).** On the distance-ranking rows, the nearest correct instance is in the top 3 at least as often as with biased Photon. Without a bias parameter this is unlikely, so it is a candidate for option (d) (bias) or (e) (our index).
4. **No regressions.** No regression on tier A, and no rise in the no-result false-positive rate, when results are merged.
5. **Latency.** p95 ≤ 250 ms from the production-candidate host (or the parallel-deadline design in §4).

Otherwise the result is "do not use" or "use later, with option d/e".

### 5.3 Routing: remaining live tests (needed before R2 can be chosen)

1. **Format completeness** (from §5.1 item 3b): `voiceLocale`, polyline6, `units`, `pedestrian`, `exclude_unpaved`, `alternates`, and the Ferrostar OSRM adapter parse test.
2. **Pairs against our Valhalla,** using the **exact contract P-points**:
   - P1→P3 auto
   - P1→P2 pedestrian
   - P1→P6 auto `exclude_unpaved`
   - P1→P4 auto `alternates: 2`
   - P5→P1 auto
   - P6→P3 auto
   - P1→X1 Erdenet auto (national coverage)
   
   Per pair: HTTP status, distance and duration (Δ %), geometry overlap (share of our polyline within 30 m of theirs), snap distance, and the `mn-MN` wording checked against the NAV-007 glossary rules.
3. **Turn-restriction and one-way correctness.** QA picks **at least 10 cases near P1–P6**: at least 5 turn restrictions (OSM `restriction=*` relations, e.g. no-left-turn at signalised junctions around P1) and at least 5 one-way segments (`oneway=yes`). Each case is verified on the ground or from recent imagery, not only from OSM. Route through each case in the forbidden direction on both engines. **Pass:** zero illegal manoeuvres on both engines. Any illegal manoeuvre by Hamuga blocks R2a.
4. **Latency from UB phones** (§5.5): Hamuga routing via our gateway from the production candidate. **Pass for R2a:** p95 < 500 ms.
5. **Traffic detection:** repeat P1→P3, P5→P1 and P6→P3 at 08:30, 13:00 and 18:30 UB time on a weekday. **If durations vary by more than 15 % while our Valhalla (no traffic) stays constant, Hamuga has traffic-aware costing.** Also test whether `date_time` is accepted. Then ask ICT Group about a traffic feed (backlog owed decision 4).
6. **Availability:** a synthetic probe from staging every 5 minutes for 7 days (one P1→P3 route per probe, well within any quota). Record the error rate and p95. Compare with the SLA ICT Group states (§3.4 question 7).

### 5.4 Transit samples (NAV-010 AC 4)
- **First, from ICT Group:** the meaning of `isActive` / `isVerified`, the OTP version, the GraphQL migration plan, and GTFS / GTFS-RT availability (§3.4 question 10). Until the stop status is explained, transit results are not treated as reliable.
- **10 OD pairs** across UB, each run at weekday 08:00, weekday 14:00 and Sunday 11:00:
  - P6→P1, P3→P5, P1→P4, P2→P6
  - Bayanzürkh east → Songinokhairkhan west
  - Nalaikh → P1
  - the airport area → P1
  - P5→P3
  - 2 khoroo-to-khoroo pairs chosen by QA
  
  Modes: `WALK,BUS` and `WALK,BUS,TRAIN`.
- **Record:** number of itineraries, legs, route short names, stop names (Mongolian Cyrillic?), walking distance, transfers, `realTime` flags, agency, and the service date. Manually cross-check 5 itineraries against the operator's published routes.

### 5.5 Latency from UB (NAV-010 AC 6; NAV-008 spike §5 protocol)
- **From phones on ≥ 2 Mongolian operators:** ≥ 20 TCP/443 connect samples to `gateway.hamuga.mn`, plus ≥ 20 full HTTPS request timings each for route, suggest, POI and plan (with the key, from a test build that never ships). Record median/p95, the `cf-ray` colo suffix (which Cloudflare PoP serves UB) and `x-kong-response-latency`.
- **From our staging host** (Singapore) and later from the production candidate (ICT Group servers, NAV-009): the same request timings. This quantifies the option (b) extra hop.

### 5.6 Deliverables
- An update of this spike (§2.4 table with **L/M** labels).
- A story summary for the BA (NAV-010 AC 7).
- ADR-0007 moved to `accepted` or `rejected` by the PO, with the routing choice (R1 / R2a / R2b) recorded.
- If adopted: a follow-up ADR on the search contract shape (§3.3 i/ii, or option d/e) and the `openapi.yaml` minor bump.

---

## 6. Follow-up items for triage
See the handoff (`follow_up_items`). In short:
1. NAV-010 phase 2 live evaluation (spike), blocked until search/POI are enabled on the key
2. NAV-010 story amendment with the live results, the revised §5.2 set and exit criteria, and the routing tests (change)
3. Secret scanning and key-handling guardrails on the public repo (tech-debt)
4. Gateway support for keyed upstreams, including optional `/v1/route` failover (feature, only after the PO decides)
5. Search augmentation with Hamuga, via adapter, option (d) or option (e) (feature, conditional on §5.2)
6. Public transport trip planning via Hamuga (feature, Phase 4, after ICT Group confirms the stop status and API roadmap)
7. ADR on the search aggregation contract shape (adr, after §5.2)
8. Multi-source attribution UI and strings (change for UX/BA)
9. Share the NAV-007 `mn-MN` locale fixes with ICT Group, and consider contributing them upstream to Valhalla (change, after the NAV-007 panel)

## 7. What this pass did not do
- **Live probes were one call per service** from a non-Mongolian cloud container. No coverage, quality, correctness, latency distribution or rate limit was measured.
- **Search and POI were not reachable (403).** Everything about their responses is still C or U.
- The transit `plan` content and the tile layers were not analysed.
- No vendor documentation was available. Everything labelled C comes from reading SDK 0.1.0 code and may differ from the server.
- The Hamuga web app's JavaScript bundles were not analysed (outside the agreed scope). They may reveal more endpoints, such as traffic.
- Timings from our sandbox are not meaningful for UB. The 1.1 s routing figure is recorded only to show that the NFR is unproven.

## Sources (read 2026-09-30)
- npm registry metadata and tarball: https://registry.npmjs.org/hamuga-imap-sdk , https://registry.npmjs.org/hamuga-imap-sdk/-/hamuga-imap-sdk-0.1.0.tgz (README, `dist/index.d.ts`, `dist/index.mjs`)
- Hamuga public style, sprite, glyphs: https://cdn.hamuga.mn/style.json , https://cdn.hamuga.mn/sprite/content_v14.json , https://cdn.hamuga.mn/fonts/GIP%20Regular/1024-1279.pbf
- Hamuga web: https://imap.hamuga.mn/ , https://hamuga.mn/ ; gateway headers: https://gateway.hamuga.mn/ (404, unauthenticated)
- Live probe results: orchestrator relay, 2026-09-30 (raw responses not committed)
- Radar SDK types (field comparison) and licence: https://github.com/radarlabs/radar-sdk-js/blob/master/src/types.ts , https://github.com/radarlabs/radar-sdk-js
- Ferrostar Valhalla request generator (`format: "osrm"`, banner/voice instructions): https://github.com/stadiamaps/ferrostar/blob/main/common/ferrostar/src/routing_adapters/valhalla.rs
- OpenMapTiles licence and attribution: https://github.com/openmaptiles/openmaptiles/blob/master/LICENSE.md
- ODbL 1.0 (§4.3 notices, §4.4 share-alike, §4.6 access to derivative databases): https://opendatacommons.org/licenses/odbl/1-0/
- OSMF attribution guidelines: https://osmfoundation.org/wiki/Licence/Attribution_Guidelines
- OSMF Collective Database guideline (all-OSM or all-non-OSM per feature type and region; no deduplication-based combination): https://osmfoundation.org/wiki/Licence/Community_Guidelines/Collective_Database_Guideline_Guideline
- OSMF Geocoding guideline: https://osmfoundation.org/wiki/Licence/Community_Guidelines/Geocoding_-_Guideline
- OpenTripPlanner changelog (2.5.0 "Deprecate REST API" #5580; 2.6.0 "Disable Legacy REST API by default" #5948; 2.8.0, 2025-09-10, "Remove REST API" #6578) and APIs: https://docs.opentripplanner.org/en/latest/Changelog/ , https://docs.opentripplanner.org/en/latest/apis/Apis/
- Kong "no Route matched with those values" 404: https://github.com/Kong/kong/issues/5679
- Internal: `docs/architecture/api/openapi.yaml` 0.4.1 (P-point examples, route NFR); `docs/qa/test-plans/NAV-003.md` (run 6 golden results); `tests/e2e/nav003/fixtures/golden-set.json`; `docs/requirements/decisions.md` (D9, D10, D25, D26, D28, D30, D35); `docs/requirements/stories/NAV-007-mongolian-voice-native-review.md` (F6/F7, glossary C1/C2); `docs/osm-navigation-research.md` §4.9

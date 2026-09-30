# Spike: NAV-010 Hamuga (ICT Group) APIs as data sources: desk study from the public SDK

- **Story:** [NAV-010](../../requirements/stories/NAV-010-hamuga-api-evaluation-spike.md). This pass partly covers AC 1, AC 5 and AC 7 as a desk study. AC 2, 3, 4 and 6 need an API key (§5).
- **Owner:** architect
- **Date:** 2026-09-30 (one timeboxed desk pass, **before** any API key or vendor documentation was received)
- **Status:** a recommendation for the PO, who makes the decision (backlog owed decision 4 and NAV-010 AC 7). The draft decision on the integration pattern is [ADR-0007](../adr/0007-third-party-data-behind-gateway.md) (status `proposed`).
- **Scope rule followed:** no authenticated endpoint was called and no API key was used. Fetched only: the public npm package `hamuga-imap-sdk@0.1.0` (registry metadata and tarball), the public style `https://cdn.hamuga.mn/style.json`, the public sprite metadata `content_v14.json` / `@2x.json`, two public glyph ranges, and the public landing pages `hamuga.mn` and `imap.hamuga.mn`. Two unauthenticated requests to the gateway (`GET /`, which returns 404, and one CORS `OPTIONS` preflight), used to read headers only. **JavaScript bundles of the Hamuga web app were not scraped.**
- **Evidence labels:**
  - **D:** documented in the SDK README or TypeScript types
  - **C:** read from the SDK's compiled code (`dist/index.mjs`, what the SDK actually sends)
  - **M:** measured by us from a public, unauthenticated resource on 2026-09-30
  - **I:** inferred by the architect (a reasoned guess, not a fact)
  - **U:** unknown; the "how/when" column says how it will be found out

---

## 1. Question and short answer

**Question.** Can Hamuga (ICT Group) tiles, search, routing and public transport APIs fill the known gaps in our OSM stack (ADR-0001: PMTiles, Photon, Valhalla, Ferrostar, MapLibre)? The gaps are addresses and house numbers, typos, category queries, traffic and transit. If so, how should we integrate them without breaking the one-contract rule (`openapi.yaml` 0.4.1)?

**Short answer.**
1. **Hamuga is a full stack that looks like ours with local enrichments.** The findings:
   - tiles in the **OpenMapTiles schema**, with UB-specific additions: khoroo (`subdistrict`) labels, `addr_district_short`, a `khashaa` landuse layer and house numbers
   - a **custom search engine**, likely address-rich (Radar-shaped TypeScript types, GeoJSON-feature rendering)
   - a **custom POI index** with 336 Mongolian categories
   - a **Valhalla-style** routing endpoint that returns native `trip` JSON
   - an **OpenTripPlanner REST** `plan` endpoint for bus and train, backed by at least 1,534 UB bus stops
   - Kong behind Cloudflare
   
   **No traffic capability is visible** in the public SDK or the style.
2. **Hamuga's routing is not directly usable by Ferrostar as the SDK calls it.** The SDK sends `{locations, costing: "auto"}` and gets native Valhalla `trip` JSON. Ferrostar needs `format: "osrm"` with banner and voice instructions. Whether Hamuga's gateway forwards those fields is **U** and is the first live test (§5.3). Even if it does, our own Valhalla stays the better turn-by-turn engine: we control `mn-MN` locale fixes (NAV-007), costing options, p95 and reroute load.
3. **Recommendation: option (c) hybrid, delivered through option (b), our gateway.** Keep our Valhalla routing and PMTiles basemap. Add Hamuga **search/POI as an augmenting upstream** and Hamuga **transit (OTP)** as a new feature, both **behind our gateway** with the key held server-side and the OSM stack as fallback. Do **not** use the client-side SDK (option a) in our apps. Per capability, as NAV-010 AC 7 asks:

| Capability | Recommendation | Condition / reason |
|---|---|---|
| Tiles | **Do not use** as the basemap. **Use later**, optionally, as a separate overlay (khoroo boundaries and labels, house numbers) | PMTiles gives offline use, no key, no per-tile vendor dependency, and an already-accepted style (ADR-0004). Schema switch (Protomaps → OMT) would redo the UX style work. Overlay only if ICT Group's terms allow it |
| Search / POI | **Use later (Phase 1–2), conditional on the live test** | Adopt as a secondary source only if Hamuga beats Photon on the address, house-number and category rows (§5.2 exit criteria). Photon stays the primary engine and the fallback |
| Routing (car/foot) | **Do not use** for navigation. Use only as a comparison source | Ferrostar compatibility is unproven. `mn-MN` wording, costing and reroute load stay under our control. Re-evaluate if Hamuga shows live traffic (§5.3 time-of-day test) |
| Transit (bus/train) | **Use later (Phase 4), strongest candidate** | No OSM equivalent. Hamuga has UB bus stops and an OTP planner. Needs a new story, UX and a contract operation. Version risk: OTP's REST API is legacy |
| Traffic | **Unknown. Ask ICT Group** | Nothing in the public SDK or the style. Backlog owed decision 4 stays open |

The choice of partner is the PO's decision. The terms-of-use questions in §3.4 must be answered before any integration story starts.

---

## 2. What was found (evidence)

### 2.1 The SDK package (`hamuga-imap-sdk@0.1.0`)

| Item | Finding | Label |
|---|---|---|
| Publisher | npm maintainer `developer_ict` (`developer@ictgroup.mn`). Created 2025-12-10. 10 versions: 0.0.1 → 0.1.0; 8 of them published between 2026-08-21 and 2026-08-25. Latest is 0.1.0 (2026-08-25) | M (registry metadata) |
| Licence | README says **Apache-2.0**. `package.json` has **no `license` field** and no `repository`. Permissive, if confirmed | D / M |
| Dependencies | `axios ^1.13.2` (MIT). Peer dependency `maplibre-gl ^5.14.0` (BSD-3). **Browser JavaScript only; there is no Android or iOS SDK** | M |
| Default hosts | Gateway `https://gateway.hamuga.mn`, style `https://cdn.hamuga.mn/style.json` | D, C |
| Auth | Every request gets the header `x-api-key: <key>`. The README calls it a "client-safe key" and warns not to put server credentials in browser code. A 403 means "the API key is not subscribed to this service" (per-service subscription) | D, C |
| Search | `POST /engine/suggest` with JSON `{value, page, perPage}`. Response envelope `{data: {items, pagination: {currentPageNo, perPage, totalPages, totalRecords}}}`. The UI renders `item.properties.name`, so items are GeoJSON-feature-like | C |
| POI | `POST /engine/poi` with `{area, categoryIds, filter, llx, lly, urx, ury, name, page, size, workHour}` (bbox is SW/NE in lon/lat). `GET /engine/poi/coordinate?lat&lon&zoom` returns `{id, type, properties}` | D, C |
| Routing | `POST /route/other/v1/route` with `{locations: [{lat, lon}], costing: "auto"}`. **`costing` is hard-coded to `auto`** in the SDK, although the README says "drivingOrWalking". The response has `trip` and `requestParameters`, which is the **native Valhalla** format, not OSRM | C |
| Transit | `GET /route/routers/default/plan?fromPlace=lat,lon&toPlace=lat,lon&mode=WALK,BUS[,TRAIN]`. The response has `plan`. These are the **OpenTripPlanner REST `plan`** parameters and response key | C, I |
| Tiles | Default `${host}/tile/tiles/{z}/{x}/{y}.pbf` (**not** `/api/tiles/...` as noted before this spike). The SDK rewrites two legacy hosts: `imap.hamuga.mn/api/tiles/` and `backend.example.com/tiles/`, the placeholder in the public style. It adds `x-api-key` only to matching tile URLs. It also registers a `hamuga://` MapLibre protocol | D, C |
| Types vs code | The `HamugaImapAddress` type (fields like `addressLabel`, `confidence: exact/interpolated/fallback`, `dma`, `dmaCode`, `countryFlag`, and the geocode layers `place/address/postalCode/…/coarse/fine`) is **field-for-field identical to Radar's `RadarAddress` type** in `radar-sdk-js` (Apache-2.0). The autocomplete UI options also mirror Radar's. The UI code reads `properties.name` instead. **So the types say nothing reliable about Hamuga's backend engine.** The real response shape is **U** until the first live call | C, M (compared with the Radar source) |
| Location bias | The autocomplete UI stores `near` (`setNear`) but **never sends it**. No `lat/lon`, `lang` or `bbox` parameter exists for suggest | C |
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
| Label fields | `name`, `name:en` (state labels only), `name_int` (peaks), `ref`, `housenumber`, `addr_district_short`. **No `name:mn`**. Our rule `name:mn` → `name` → `name:en` would degrade to `name`, which in Mongolia is mostly Cyrillic Mongolian anyway (I) | M |
| Traffic / transit layers | **None.** No traffic or congestion layer, and no bus route layer. Traffic signals are shown as POI icons only | M |
| Glyphs | `https://cdn.hamuga.mn/fonts/{fontstack}/{range}.pbf`, fonts `GIP Regular / Medium / SemiBold`. Public; the Cyrillic range 1024–1279 exists (41.8 kB). **Font licence U** | M |
| Sprite | `content_v14`: 131 icons, including `bus_stop`, `bus_station`, `bus_active`, `Airport`, `atm`. Public, CORS `*` | M |

### 2.3 Public web app and infrastructure

| Item | Finding | Label |
|---|---|---|
| Web app | `imap.hamuga.mn` (canonical `map.hamuga.mn`), a Next.js app titled «Үндэсний орон зайн мэдээллийн систем» ("National Spatial Information System"). `hamuga.mn` is a "Тун удахгүй" (coming soon) page. **No terms of use, attribution or pricing text** on either page | M |
| POI categories | The page HTML embeds **336 categories** (a tree with a Mongolian `name`, an English key `name:en` and `parentId`). Many keys are OSM tag values (`townhall`, `polling_station`, `ranger_station`, `parking_entrance`, `charging_station`), which suggests OSM-derived POIs with local additions (I). Transport group 184: `train_station` = «**Вагоны буудал**» (id 186), `airport` (187), `bus_terminal` (188), `taxi_parking` (189) | M |
| Bus stops | The page HTML embeds **1,534 bus stops** (`busStopId`, `busStopName` such as «Баянзүрх дүүрэг /Хойд/», coordinates). The bbox is 106.52–108.39 E, 47.35–48.20 N (UB including outer districts). `updatedAt` 2026-08 (1,522) and 2026-09 (14). **Every stop has `isActive: false, isVerified: false`**, so the status meaning is U | M |
| Hosting | `gateway`, `cdn`, `imap` and `map.hamuga.mn` all resolve to Cloudflare anycast (104.21.26.235, 172.67.168.151). The gateway returns Kong headers (`x-kong-request-id`, `x-kong-response-latency`) and Kong's 404 body `{"message":"no Route matched with those values","request_id":…}`. **So the chain is Cloudflare → Kong → services, and the origin location is U.** TLS terminates at Cloudflare, at whichever PoP serves the user. From our sandbox it was `cf-ray …-IAD` (US), which says nothing about UB | M |
| Gateway CORS | Preflight `OPTIONS /engine/suggest` → 204, `Access-Control-Allow-Origin: *`, `Allow-Headers: *`, max-age 3600. Browser-side use is technically possible | M |

### 2.4 Values required by NAV-010 AC 1 (status after this desk pass)

| Value | Tiles | Search / POI | Routing | Transit | Traffic |
|---|---|---|---|---|---|
| Endpoint | `/tile/tiles/{z}/{x}/{y}.pbf` (D, C) | `/engine/suggest`, `/engine/poi`, `/engine/poi/coordinate` (C) | `/route/other/v1/route` (C) | `/route/routers/default/plan` (C) | U: ask ICT Group |
| Auth | `x-api-key`, per-service subscription (D) | same | same | same | U |
| Format | MVT, OMT schema (M) | JSON envelope + GeoJSON-like items (C). Exact fields U until the first call | Native Valhalla `trip` (C). OSRM format U (§5.3) | OTP `plan` (C, I) | U |
| Reverse geocoding | — | **No address reverse endpoint in the SDK.** `poi/coordinate` is a POI lookup near a point (C). Address reverse U: ask | — | — | — |
| Coverage | UB and Mongolia? Style center UB; `State`/`Town` layers suggest national (I). U: measure (§5) | U: measure | U: measure (X1 Erdenet) | UB (1,534 stops, M). Intercity U | U |
| Update frequency | Style changed 2026-09-30 (M). Data U | U | U | Stops updated 2026-08/09 (M) | U |
| Rate limits, SLA, pricing | U: ask ICT Group (§3.4) | U | U | U | U |
| Licence and attribution | SDK Apache-2.0 (D). **Data terms U**. Style declares none (M) | U | U | U | U |
| Server location | Behind Cloudflare. Origin U: ask, and read the `cf-ray` colo from UB phones (§5.5) | same | same | same | U |

---

## 3. Analysis

### 3.1 Capability map against our stack and contract (`openapi.yaml` 0.4.1)

| Capability | Our stack / contract | Hamuga | Fit and gaps |
|---|---|---|---|
| **Tiles** | Planetiler → Protomaps basemap schema, **one PMTiles file** served with HTTP Range (`GET /tiles/basemap.pmtiles`), no auth, offline-capable, style owned by UX (ADR-0004) | XYZ MVT per tile, **OMT schema**, `x-api-key` on every tile, 195-layer style tied to the Hamuga CDN (fonts, sprite) | **Not a drop-in.** Different schema, so our style cannot read Hamuga tiles, and the Hamuga style cannot read our PMTiles. Per-tile keyed requests rule out our offline story, unless ICT Group permits bulk download. **Value:** khoroo boundaries and labels, `addr_district_short`, house numbers and khashaa plots, which the Protomaps basemap does not carry in this form |
| **Search** | `/v1/search` = Photon 1.3.0 pass-through (GET, `q`, `lat/lon` bias, `lang`, `limit`). Known gaps (QA run 6): **B4** «Сүхбатар» (typo) returns far-away soums; **B13** «Галт тэрэгний буудал» (category phrase) returns a hotel 527 km away; no ү/у folding (ADR-0006 client workaround); sparse house numbers | POST, `value/page/perPage`, **no bias, no `lang`**, own envelope. Separate POI endpoint with category IDs and opening-hours filter | **Different contract shape.** Clients cannot switch without a gateway adapter or a second parser. Photon's GeoJSON `FeatureCollection` with `osm_*` properties is what NAV-003 already parses. **Possible value:** addresses, house numbers, khoroo names, a Mongolian category taxonomy. Whether its fuzzy matching handles «Сүхбатар» is U (§5.2) |
| **Reverse** | `/v1/reverse` = Photon `/reverse` | Only `poi/coordinate` (POI near a point, with zoom) | **No equivalent documented.** Keep Photon |
| **Routing** | `/v1/route` = Valhalla 3.9.0 pass-through, `format: osrm` + `banner_instructions` + `voice_instructions` + `language: mn-MN` + `units: kilometers`, parsed by Ferrostar's OSRM adapter. NAV-007 patches `mn-MN` wording | Valhalla-like request, native `trip` response. SDK exposes only `auto` | **Not Ferrostar-compatible as the SDK calls it.** Ferrostar's Valhalla request generator always sends `format: "osrm"` with banner and voice instructions and parses OSRM JSON. It would work only if Hamuga's gateway forwards the full body to a Valhalla that supports `format=osrm` and `mn-MN` (U, §5.3). Alternatively we could write a custom Ferrostar route adapter (native `trip` → Ferrostar route): extra work with no benefit over our own Valhalla. Even if compatible, we would lose control of the NAV-007 locale fixes, costing options (`exclude_unpaved`, NAV-001 AC 19), the p95 target (< 500 ms) and reroute volume |
| **Transit** | **None** (research §4.9: MOTIS or OTP plus GTFS, roadmap Phase 4) | OTP REST `plan`, modes WALK/BUS/TRAIN, 1,534 UB stops | **New capability, no OSM equivalent.** Ferrostar does not do transit guidance, so this is itinerary display (list + map), not turn-by-turn. Risks: (1) the OTP REST API is legacy in OTP 2 (deprecated, and the OTP docs say it was removed in 2025) in favour of GraphQL, so ICT Group's endpoint may change; (2) GTFS-Realtime and data freshness are U; (3) OTP is **LGPL-3.0**, which does not matter to us while ICT Group runs it as a service, and would matter only if we self-host (still fine as an unmodified server) |
| **Traffic** | None yet (Phase 3, backlog owed decision 4). Valhalla supports live and predicted speeds | **Nothing visible** | U. Ask ICT Group. The §5.3 time-of-day routing test detects whether Hamuga's routing durations vary with congestion |

### 3.2 Hamuga layers that could fix our known search gaps

| Gap (QA run 6 / openapi 0.4.1) | Hamuga asset that might help | How to verify (§5.2) | Caveat |
|---|---|---|---|
| **Typos**: B4 «Сүхбатар», B3 «Сухбаатар» (ү/у) | `/engine/suggest`, fuzziness U | Run B3 and B4 as typed, with no client assistance | If Hamuga also fails, ADR-0006 client assistance stays our fix |
| **Category phrases**: B13 «Галт тэрэгний буудал» | POI category 186 `train_station`, **named «Вагоны буудал»**, not «Галт тэрэгний буудал». POI search by `categoryIds` + bbox. Style layer «Tomor zam» shows `train_station` from z12 | Suggest as typed; then `poi` with `categoryIds: [186]` and a bbox around P5 | Needs a phrase → category-ID mapping (in the gateway or client). The Mongolian phrases must come from the **glossary**. «Вагоны буудал» vs «Галт тэрэгний буудал» is a glossary question for the BA, not ours to decide |
| **Addresses (khoroo, ger districts)**: B14, B15, C2, C3 | `subdistrict` places + `addr_district_short` in tiles; `neighborhood`/`county` style fields in suggest (per the types, U) | New tier H rows (§5.2) | Measure, don't assume. The types are Radar's, not Hamuga's |
| **House numbers** | `housenumber` tile layer (z15+); `number`/`street` fields in suggest (U) | 10 house-number rows sampled **symmetrically** (5 from OSM `addr:housenumber`, 5 from the Hamuga house-number layer, once a key allows reading tiles) | Sampling only from one source biases the comparison |

### 3.3 Integration options

| Option | How | Pros | Cons |
|---|---|---|---|
| **(a) Client-side SDK** | Web demo imports `hamuga-imap-sdk`. Native apps call the REST API directly with an embedded key | Fastest demo; no backend work | **Web only** (no native SDK). The key ships in the public web bundle and in APK/IPA files, so it is extractable. User IP, queries and coordinates go straight to ICT Group/Cloudflare (D9 consent). Two contracts (ours + Hamuga's), so it breaks the one-contract rule. SDK UI issues: XSS, English strings, lossy selection, attribution off by default. No fallback when Hamuga is down. Unstable 0.x API (8 releases in 5 days) |
| **(b) Through our gateway as upstreams** | nginx (or a small adapter service where response shaping or merging is needed) forwards to `gateway.hamuga.mn` and adds `x-api-key` from a server-side env var. New or extended operations in `openapi.yaml` | **One contract**. Key never leaves the server. User IP hidden from Hamuga (only the gateway IP is seen). Gateway rate limits, caching and a circuit breaker. **Fallback to the OSM stack** per capability. Client code stays Photon/Valhalla-shaped. Vendor swap is invisible to clients (NAV-010 R3) | Backend work per capability. An extra hop: with staging in Singapore (D25), UB → SG → Cloudflare → ICT origin (probably UB) doubles the international path (tromboning), which goes away when production runs in Mongolia (NAV-009). Response shaping for search needs code, because nginx cannot merge two engines |
| **(c) Hybrid** (a content choice, orthogonal to the mechanism) | Choose per capability: e.g. **our routing + tiles**, **Hamuga search augmentation + transit**; or the reverse (Hamuga tiles + our search) | Uses each side's strength. Routing stays under our control (Ferrostar, `mn-MN`, NFRs). Transit arrives without building GTFS | Two data sources on screen means multi-source attribution. Search merging needs deduplication rules (ODbL, §3.4) |

**Recommendation: (c) as the content choice, delivered only via (b).** Concretely:
- **Routing and basemap:** stay on our OSM stack (Valhalla, PMTiles).
- **Search:** Photon stays primary. Hamuga suggest/POI becomes a second upstream behind the gateway, **used only if the live test passes** (§5.2).
- **Transit:** new gateway operation passing Hamuga OTP `plan` through (Phase 4).
- **Option (a) is rejected for our apps.** It is acceptable only for a throwaway internal demo on a developer's machine with a developer key that is never committed.

**Contract sketch (not applied; `openapi.yaml` stays 0.4.1 until the PO decides).** Each item becomes a minor version bump:
- `GET /v1/transit/plan`: pass-through of Hamuga `…/routers/default/plan` (`fromPlace`, `toPlace`, `mode`, later `date`, `time`, `arriveBy`), key injected, `x-upstream: {service: hamuga-otp}`, errors 400/429/502/503/504 as `GatewayError`.
- Search, two shapes to decide in a follow-up ADR after the live test: **(i)** `GET /v1/places/suggest` and `/v1/places/poi` as pass-through (clients parse a second shape); or **(ii)** keep `/v1/search` and have a small adapter return one Photon-compatible `FeatureCollection` with `properties.source: "osm" | "hamuga"`. The architect leans toward **(ii)**, because the NAV-003 web client and NAV-005 native clients keep one parser. The merge rules must respect §3.4 (no deduplication that blends records).

### 3.4 Licensing

1. **Is Hamuga data derived from OSM?** Very likely, at least in part (I): OMT schema, OSM tag values as POI keys, OSM-style `name`/`name:en`/`name_int`, and class values like `minor_construction`. If ICT Group publicly uses a **derivative database** of OSM (OSM + their enrichments merged), ODbL 1.0 §4.4 share-alike applies to **that derivative database**. That is ICT Group's obligation, but it affects us:
   - **Attribution is required on our screens anyway.** «© OpenStreetMap contributors» already appears on every map screen (CLAUDE.md rule 8). Hamuga tiles in the OMT schema also require the credit "© OpenMapTiles" linked to openmaptiles.org (the OpenMapTiles schema/design licence is CC-BY 4.0), unless MapTiler has granted an exception in writing.
   - **The Hamuga style declares no attribution** and the SDK README disables the attribution control. A compliance question for ICT Group; we must not copy that pattern.
2. **Our own database must stay a collective database, not a derivative one.** OSMF's Collective Database guideline says combining is safe when, for a given feature type and region, the data is all-OSM or all-non-OSM, or non-OSM data fills gaps without incorporating OSM content. Merging Hamuga POIs into our Photon index with deduplication against OSM would create a **derivative database**. If Hamuga's data is proprietary, that would put it under ODbL share-alike. ICT Group would likely refuse that, and it would be a licence breach if their terms forbid it. **Rules for us:**
   - Keep Hamuga data in **separate stores / separate responses / separate map layers**: a separate upstream, and a separate overlay source in the style. **Never import it into our Photon index, Valhalla graph or PMTiles.**
   - In a merged search list (contract option ii), results are **interleaved, not blended**. Each item keeps its source; no field-level merge of an OSM and a Hamuga record; `source` is shown or at least carried.
   - Geocoding results: OSMF's geocoding guideline treats individual results as insubstantial extracts that need attribution only, as long as we do not systematically rebuild the database from them. **Do not cache Hamuga results in bulk.**
   - A rendered map combining our OSM basemap with a Hamuga overlay is a **produced work**. Attribution must credit every source.
3. **Attribution text (proposed, BA/UX to finalise from resource files):** «© OpenStreetMap contributors», plus "© OpenMapTiles" if Hamuga OMT tiles are shown, plus the Hamuga credit in the wording ICT Group requires (U). Search and transit result lists need no per-item credit, but the screen or an info panel must credit Hamuga (OSMF attribution guideline, by analogy).
4. **Other licences:**
   - SDK: Apache-2.0 per the README, but missing from `package.json`. If the code is derived from `radar-sdk-js` (Apache-2.0), Apache §4 NOTICE duties apply to ICT Group. **We do not plan to ship the SDK**, so this does not touch us.
   - **GIP fonts:** licence U. Do not bundle; load only from the Hamuga CDN, and only if we use their style.
   - OTP: LGPL-3.0, relevant only if we self-host.
   - No GPL component is proposed.
5. **Terms-of-use questions for ICT Group** (PO to relay; answers are needed before any integration story):
   1. Under which written terms may we use each API: free, internal or paid, and quotas?
   2. May we **proxy through our server** and serve results to our own Android/iOS/web apps under our brand?
   3. Caching: may we cache responses (how long, tiles vs search)? May we store any data? **Offline use** of tiles?
   4. Display: may Hamuga layers appear **beside OSM layers on one map**? May Hamuga search results appear in one list with OSM results?
   5. Required attribution text, logo and placement.
   6. Data provenance: which parts derive from OSM? How does ICT Group meet ODbL attribution and share-alike for its derived databases? Are any parts government data («Үндэсний орон зайн мэдээллийн систем»), with their own licence?
   7. Server-side key: can we get a **server key** (IP-restricted to our gateway, separate keys for dev, staging and production) rather than a browser "client-safe" key? What are the rotation and revocation process and the SLA?
   8. Privacy: what does Hamuga log about requests (queries, coordinates, IP)? Retention? Is Cloudflare TLS termination outside Mongolia acceptable under D9?
   9. Traffic: is there a live or historical traffic product, and in what form (OSM way IDs, own network)?
   10. Transit: is GTFS / GTFS-RT available for export (so we could self-host MOTIS/OTP)? Will the OTP REST `plan` endpoint stay, or move to GraphQL?
   11. May we publish evaluation results (result names and coordinates) in our **public** repository (decisions "Items to confirm" 5)?

### 3.5 Security (the repository is PUBLIC, D35)

1. **The key never enters the repo, a client bundle or a log.**
   - `.env.example` gets `HAMUGA_API_KEY=` (empty) and `HAMUGA_BASE_URL=https://gateway.hamuga.mn`. The real value lives only in the host's `.env` or a secret store, which is already git-ignored per NAV-008 AC 21.
   - **Never** use a `VITE_*` variable for it: Vite inlines `VITE_*` values into the public web bundle.
   - Never put it in `mobile/**` resources, `BuildConfig` or `Info.plist`.
2. **How to proxy:**
   - The gateway sets the header `x-api-key` from the environment at container start. The existing envsubst template mechanism works; the rendered config lives only inside the container. An njs variable is an alternative.
   - The gateway **strips any client-supplied `x-api-key`** and forwards only allow-listed paths (`/engine/suggest`, `/engine/poi`, `/route/routers/default/plan`), never a wildcard proxy to `gateway.hamuga.mn`.
   - TLS to the upstream with certificate verification (`proxy_ssl_verify on`, `proxy_ssl_server_name on`).
3. **Logging:**
   - The existing `json_privacy` access-log format already excludes headers and query strings (ADR-0002 §3.4). Keep `error_log` at `crit` for these locations, because error lines include the request line.
   - Hamuga's error body `request_id` may be logged (it is not PII) for vendor support.
4. **Leak prevention:**
   - Enable GitHub **secret scanning with push protection** on the public repo.
   - Add a `gitleaks` pre-commit/CI check with a custom rule once the key format is known.
   - QA test harnesses read the key from the environment and **redact** it in reports and Playwright traces (request headers appear in traces).
5. **Blast radius:** per-environment keys, quotas set at ICT Group, a documented rotation runbook, and a gateway circuit breaker so a revoked key degrades to the OSM stack (search) or a localised "transit unavailable" state rather than failing the app.
6. **Supply chain (option a only):** the SDK is 0.x, has no repository link and no licence field, and its UI builds `innerHTML` from API data. That is one more reason not to ship it.

---

## 4. Non-functional notes

- **Latency.** Unknown from UB (§5.5). Our gateway adds one hop. On staging (Singapore, D25/D26) that hop crosses borders twice, so staging latency numbers for Hamuga-backed operations are **not representative** of production in Mongolia (NAV-009). Keep the existing NFRs:
  - search p95 ≤ 300 ms via the gateway (Photon, NAV-001 AC 36). A Hamuga-backed search must meet the same target from the production host, or it runs **in parallel with Photon under a deadline** (for example 250 ms) and late Hamuga results are dropped.
  - route p95 < 500 ms is unaffected, since routing stays on our Valhalla.
- **Availability.** Hamuga is an optional augmentation. Search degrades to Photon-only. Transit has no fallback, so it shows the localised unavailable state, using existing error codes 502/503/504.
- **Privacy.** Via (b), ICT Group sees queries and coordinates but not user IPs. Search sends no coordinates to Hamuga, because suggest has no bias parameter. Transit sends origin and destination, so it needs consent text and D9 review before outside users. Cloudflare terminates TLS for Hamuga (location U).
- **Vendor concentration (R3).** ICT Group may also host production (D25). Keeping every Hamuga capability optional, behind our contract, with the OSM stack as the fallback, limits lock-in.

---

## 5. Live test plan (run when a key and the vendor docs arrive)

Owner split: QA (harness, golden set, runs) and architect (analysis, recommendation update). Time: 5 working days (NAV-010 AC 1).

### 5.0 Preconditions
- The key is stored in a local `.env` / CI secret only. The harness reads `HAMUGA_API_KEY` from the environment and redacts it from all output, including Playwright traces.
- Requests are paced at ≤ 2 req/s (or ICT Group's stated limit). Every non-2xx response is logged with `statusCode` and `request_id`.
- Before comparing, record **one raw response per endpoint** as a JSON Schema snapshot. Result files stay out of the public repo until ICT Group answers question 11 in §3.4; commit counts and metrics only.
- Same day, same OSM build: Photon runs via our gateway at the build recorded in `backend/data/build-info.json`.

### 5.1 Contract probes (day 1, about 20 requests)
1. `POST /engine/suggest {value:"Сүхбаатар", page:1, perPage:10}`: record the envelope, item fields and `properties` keys. Does any `lang`, `near` or `focus` parameter exist (vendor docs)?
2. `POST /engine/poi` with `categoryIds:[186]` and a bbox of about 1 km around P5, then `GET /engine/poi/coordinate` at P1, zoom 16.
3. `POST /route/other/v1/route` twice:
   - (a) the SDK body `{locations:[P1,P3], costing:"auto"}`
   - (b) **our body** `{…, format:"osrm", banner_instructions:true, voice_instructions:true, language:"mn-MN", units:"kilometers"}`
   
   Pass for Ferrostar compatibility: (b) returns `code:"Ok"`, `routes[0].legs[].steps[].voiceInstructions` and `bannerInstructions` present, `voiceLocale:"mn-MN"`, and polyline6 geometry. Also try `costing:"pedestrian"` and `costing_options.auto.exclude_unpaved`.
4. `GET /route/routers/default/plan` P6 → P1, `mode=WALK,BUS`: record `plan.itineraries[].legs[]` (mode, route, `realTime`, agency), stop names and language.
5. Tiles: fetch z14 tiles at P1 and P6. Record the vector layers and fields, including any `attribution` in a TileJSON if the docs point to one.

### 5.2 Search comparison (≥ 50 UB queries; NAV-010 AC 2)
- **Base set:** the 42 query rows of `tests/e2e/nav003/fixtures/golden-set.json` (tiers A/B/C; A15 is a coordinate, not a search), with the same expected answers.
- **New tier H (agreed with QA and BA before the run, AC 2):** 26 rows:
  - ≥ 6 extra khoroo/ger-district addresses (for example Chingeltei and Songinokhairkhan ger khoroos near P6), bringing the address rows to ≥ 10 with B14, B15, C2 and C3
  - **10 house-number queries**, sampled symmetrically: 5 from OSM `addr:housenumber` in UB, 5 from the Hamuga `housenumber` tile layer
  - ≥ 6 POI/category phrases (e.g. B13-style category phrases for pharmacy, fuel station, bank, hospital and bus terminal, **with Mongolian wording taken from the glossary**; missing terms requested from the BA)
  - ≥ 4 extra no-result queries
  
  Total 68 queries, of which ≥ 10 Latin (already in the base set) and ≥ 10 POIs.
- **Runs per query:**
  - (1) Photon via `/v1/search` with the NAV-003 profile (bias P-point, 3 decimals, `limit=8`), as typed, **without** ADR-0006 client assistance
  - (2) the same with assistance (the product baseline)
  - (3) Hamuga suggest as typed, `perPage=8`
  - (4) for category rows only, additionally Hamuga `poi` with the category ID and a 2 km bbox around the reference point
  
  Hamuga has no bias, so also report Photon without bias for a fair engine-to-engine comparison.
- **Metrics:** top-1 and top-5 hit rate per tier and per engine; the no-result false-positive rate; the count of unanswered queries (reported, not dropped); p50/p95 latency per engine from the same host.
- **Rows to call out explicitly:** B3, B4, B13, B14, B15 and every tier H row.
- **Exit criteria (proposal; the PO decides):** "Use Hamuga search" means **Hamuga top-5 ≥ Photon (assisted) top-5 + 10 percentage points on tier H and on B3/B4/B13 combined**, **and** no regression on tier A when results are merged, **and** p95 ≤ 250 ms from the production-candidate host. Otherwise "do not use" or "use later".

### 5.3 Routing comparison (reference points P1–P6, X1)
- **Pairs:**
  - P1→P3 auto
  - P1→P2 pedestrian
  - P1→P6 auto `exclude_unpaved`
  - P1→P4 auto `alternates: 2`
  - P5→P1 auto
  - P6→P3 auto
  - P1→X1 Erdenet auto (national coverage)
- **Per pair:** HTTP status, format (native vs OSRM), distance and duration against our Valhalla (Δ %), geometry overlap (share of our polyline within 30 m of theirs), snap distance, whether `language=mn-MN` is honoured, and whether the OSRM output is **parsed by Ferrostar's OSRM adapter** (a Rust/wasm unit test by mobile or QA).
- **Traffic detection:** repeat P1→P3, P5→P1 and P6→P3 at 08:30, 13:00 and 18:30 UB time on a weekday. **If durations vary by more than 15 % while our Valhalla (no traffic) stays constant, Hamuga has traffic-aware costing.** Then ask ICT Group about a traffic feed (backlog owed decision 4). Also test whether `date_time` is accepted.

### 5.4 Transit samples (NAV-010 AC 4)
- **10 OD pairs** across UB, each run at weekday 08:00, weekday 14:00 and Sunday 11:00:
  - P6→P1, P3→P5, P1→P4, P2→P6
  - Bayanzürkh east → Songinokhairkhan west
  - Nalaikh → P1
  - the airport area → P1
  - P5→P3
  - 2 khoroo-to-khoroo pairs chosen by QA
  
  Modes: `WALK,BUS` and `WALK,BUS,TRAIN`.
- **Record:** number of itineraries, legs, route short names, stop names (Mongolian Cyrillic?), walking distance, transfers, `realTime` flags, agency, and the service date. Manually cross-check 5 itineraries against the operator's published routes.
- **From the docs or ICT Group:** GTFS / GTFS-RT availability, route and stop counts, last update. The 1,534 stops found in §2.3 are the starting reference.

### 5.5 Latency from UB (NAV-010 AC 6; NAV-008 spike §5 protocol)
- **From phones on ≥ 2 Mongolian operators:** ≥ 20 TCP/443 connect samples to `gateway.hamuga.mn`, plus ≥ 20 full HTTPS request timings each for suggest, POI and plan (with the key, from a test build that never ships). Record median/p95, the `cf-ray` colo suffix (which Cloudflare PoP serves UB) and `x-kong-response-latency`.
- **From our staging host** (Singapore) and later from the production candidate (ICT Group servers, NAV-009): the same request timings. This quantifies the option (b) extra hop.

### 5.6 Deliverables
- An update of this spike (§2.4 table with **M** labels).
- A story summary for the BA (NAV-010 AC 7).
- ADR-0007 moved to `accepted` or `rejected` by the PO.
- If adopted: a follow-up ADR on the search contract shape (§3.3 i/ii) and the `openapi.yaml` minor bump.

---

## 6. Follow-up items for triage
See the handoff (`follow_up_items`). In short:
1. NAV-010 phase 2 live evaluation (spike)
2. NAV-010 story amendment with the desk findings and the tier H set (change)
3. Secret scanning and key-handling guardrails on the public repo (tech-debt)
4. Gateway support for keyed third-party upstreams (feature, only after the PO decides)
5. Search augmentation with Hamuga (feature, conditional on §5.2)
6. Public transport trip planning via Hamuga OTP (feature, Phase 4)
7. ADR on the search aggregation contract shape (adr, after §5.2)
8. Multi-source attribution UI and strings (change for UX/BA)

## 7. What this pass did not do
- No authenticated call. No response shape, coverage, quality, latency or rate limit was measured.
- No vendor documentation was available. Everything labelled C comes from reading SDK 0.1.0 code and may differ from the server.
- The Hamuga web app's JavaScript bundles were not analysed (outside the agreed scope). They may reveal more endpoints, such as traffic.
- Timings from our sandbox (US, Cloudflare IAD) are not meaningful for UB and are not reported.

## Sources (read 2026-09-30)
- npm registry metadata and tarball: https://registry.npmjs.org/hamuga-imap-sdk , https://registry.npmjs.org/hamuga-imap-sdk/-/hamuga-imap-sdk-0.1.0.tgz (README, `dist/index.d.ts`, `dist/index.mjs`)
- Hamuga public style, sprite, glyphs: https://cdn.hamuga.mn/style.json , https://cdn.hamuga.mn/sprite/content_v14.json , https://cdn.hamuga.mn/fonts/GIP%20Regular/1024-1279.pbf
- Hamuga web: https://imap.hamuga.mn/ , https://hamuga.mn/ ; gateway headers: https://gateway.hamuga.mn/ (404, unauthenticated)
- Radar SDK types (field comparison) and licence: https://github.com/radarlabs/radar-sdk-js/blob/master/src/types.ts , https://github.com/radarlabs/radar-sdk-js
- Ferrostar Valhalla request generator (`format: "osrm"`, banner/voice instructions): https://github.com/stadiamaps/ferrostar/blob/main/common/ferrostar/src/routing_adapters/valhalla.rs
- OpenMapTiles licence and attribution: https://github.com/openmaptiles/openmaptiles/blob/master/LICENSE.md
- OSMF attribution guidelines: https://osmfoundation.org/wiki/Licence/Attribution_Guidelines
- OSMF Collective Database guideline: https://osmfoundation.org/wiki/Licence/Community_Guidelines/Collective_Database_Guideline_Guideline
- OSMF Geocoding guideline: https://osmfoundation.org/wiki/Licence/Community_Guidelines/Geocoding_-_Guideline
- OpenTripPlanner APIs and changelog (REST API legacy/removed, GraphQL): https://docs.opentripplanner.org/en/latest/apis/Apis/ , https://docs.opentripplanner.org/en/latest/Changelog/
- Kong "no Route matched with those values" 404: https://github.com/Kong/kong/issues/5679
- Internal: `docs/architecture/api/openapi.yaml` 0.4.1; `docs/qa/test-plans/NAV-003.md` (run 6 golden results); `tests/e2e/nav003/fixtures/golden-set.json`; `docs/requirements/decisions.md` (D9, D10, D25, D26, D35); `docs/osm-navigation-research.md` §4.9

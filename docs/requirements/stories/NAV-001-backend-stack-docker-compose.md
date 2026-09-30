---
id: NAV-001
title: Backend stack up with one `docker compose up` (PMTiles + Valhalla + Photon behind a CORS gateway, UB smoke tests)
phase: 0
priority: must
size: L
needs_design: false
needs_backend: true
needs_mobile: false
status: ready
---

# NAV-001: Backend stack up with one `docker compose up`

## Story
As a **UB commuter by car** (and, through later stories, every other persona), I want **the map, routing and search services for Ulaanbaatar to run as one local stack that answers in Mongolian**, so that **the team can prove on real UB data that the chosen open-source stack returns usable Mongolian directions, search results and map tiles before any app is built (NAV-002+)**.

> Enabler story. No end user touches it directly. Persona value is delivered by the stories it unblocks:
>
> | Capability proven here | Persona-facing story it unblocks |
> |---|---|
> | PMTiles basemap served with HTTP Range + CORS | NAV-002 web map (all personas) |
> | Photon search-as-you-type + reverse | NAV-003 search (commuter, taxi driver, tourist) |
> | Valhalla OSRM-format route with `mn-MN` voice/banner instructions | NAV-004 route preview, NAV-005 active navigation (commuter, taxi/delivery, pedestrian) |
> | `exclude_unpaved` accepted per request | Later countryside/ger-district stories (intercity driver) |

## Context
- Research roadmap Phase 0 PoC (`docs/osm-navigation-research.md` §7): "Docker-compose on one VM: PMTiles + Valhalla + Photon".
- Stack is fixed by `docs/architecture/adr/0001-tech-stack.md` and not re-opened here.
- Decisions already made (not open): **local development only**, **no auth / API keys**, stack per ADR-0001.
- Gateway paths and response envelopes are the architect's call and go in `docs/architecture/api/openapi.yaml`. This story names endpoints symbolically:
  - `TILES`: the basemap PMTiles file (or tile endpoint) behind the gateway
  - `ROUTE`: Valhalla route, OSRM-compatible output
  - `SEARCH`: Photon forward search / autocomplete
  - `REVERSE`: Photon reverse geocoding
  - `HEALTH`: gateway health
- **Reference machine** for all timing and size limits below: 4 CPU, 15 GB RAM, 30 GB free disk, Docker running, dev OSM source = **full Mongolia extract** from the dev mirror `https://geo2day.com/asia/mongolia.pbf` (~70 MB PBF).
- **Default dev OSM source (PO decision 2026-09-29):** the full Mongolia extract. Every AC in this story is evaluated on a build from this default unless the AC says otherwise.
  - **Dev:** `https://geo2day.com/asia/mongolia.pbf`. This is a third-party mirror, used for **development only** because `download.geofabrik.de` is blocked from the dev container.
  - **Production:** Geofabrik `https://download.geofabrik.de/asia/mongolia-latest.osm.pbf`.
  - The URL stays configurable in `.env` (AC 3).
  - The BBBike Ulaanbaatar extract (`https://download.bbbike.org/osm/bbbike/UlanBator/UlanBator.osm.pbf`, ~4.4 MB, bbox `106.8392,47.8995,107.0167,47.9337`, about 13 × 4 km) may stay documented as an **optional small/fast alternative**. It is not the default: it excludes P3 and P6, so AC 10, 13 and 14 cannot pass on it.
- **Environment constraints known for this story:**
  - Docker Hub is rate-limited (HTTP 429). Images are pinned and already pulled.
  - ghcr.io, GitHub releases and `geo2day.com` are reachable.
  - `download.geofabrik.de` is blocked from the dev container.
  - The BBBike URL returned HTTP 503 all day on 2026-09-29. A local copy of the UB PBF exists.

### Reference test locations (Ulaanbaatar, WGS84, approximate)
QA may move any point by up to 50 m to the nearest routable road. Every point P1 to P6 must lie inside the default dev extract's bounding box. The backend checks this with `osmium fileinfo` or equivalent and records it in the README or `data/build-info.json`.

| Key | Place | lat | lon |
|---|---|---|---|
| P1 | Sükhbaatar Square (Сүхбаатарын талбай) | 47.9189 | 106.9176 |
| P2 | State Department Store (Их дэлгүүр), Peace Ave | 47.9139 | 106.9044 |
| P3 | Zaisan Memorial (Зайсан) | 47.8858 | 106.9173 |
| P4 | Gandantegchinlen Monastery (Гандан) | 47.9215 | 106.8950 |
| P5 | Ulaanbaatar railway station | 47.9095 | 106.8835 |
| P6 | Chingeltei ger-district area (unpaved roads likely) | 47.9600 | 106.9000 |
| X1 | Erdenet: inside the default Mongolia extract, outside the optional BBBike UB box | 49.0270 | 104.0440 |
| X2 | Out of any Mongolia coverage: Beijing | 39.9042 | 116.4074 |

## Acceptance criteria

### A. One-command startup and configuration
1. **Given** a clean clone with no `data/` directory, **When** the developer runs `cp .env.example .env` and then `docker compose up -d`, **Then** all services (tiles, routing, search, gateway, plus any one-shot build/init containers) finish without manual steps, and every long-running service reports `healthy` in `docker compose ps` within **30 minutes** on the reference machine.
2. **Given** `data/` already holds a complete build from a previous run, **When** the developer runs `docker compose down` and then `docker compose up -d`, **Then** all long-running services report `healthy` within **120 seconds**, and the logs show that the tiles, Valhalla graph and Photon index were **reused, not rebuilt**.
3. **Given** `.env.example`, **When** it is inspected, **Then** it contains at least:
   - an OSM source URL key whose default is `https://geo2day.com/asia/mongolia.pbf`, with a comment saying it is a **dev-only** third-party mirror of the full Mongolia extract
   - a commented production value `https://download.geofabrik.de/asia/mongolia-latest.osm.pbf`
   - if the BBBike UB extract is mentioned, it appears only as a commented, optional small/fast alternative, never as the active default
   - an optional local-file key that, when set, is used instead of downloading
   - the gateway host port (default `8080`)
   - the allowed CORS origins (default allows any origin, for local dev only)
   - the tiles max zoom `TILES_MAXZOOM`, default `14` (PO decision D1, 2026-09-30, see AC 12)

   Every key has a one-line comment, and no secrets are present.
4. **Given** the local-file key points to an existing PBF and the OSM source URL is unreachable, **When** a full build is forced, **Then** the build succeeds with the local file and makes **no** request to the OSM source URL.
5. **Given** a documented "force rebuild" command (for example `docker compose run --rm <builder>` or `make rebuild-data`), **When** the developer changes the OSM source key and runs that command, **Then** the tiles, Valhalla graph and Photon index are all rebuilt from the new source. The README states which source and date were used for the current build, or a build-info file under `data/` records them.
6. **Given** the repository after a full build, **When** `git status --porcelain` and `git ls-files | grep -Ei '\.(osm\.pbf|pbf|pmtiles|mbtiles)$'` are run, **Then** neither shows any `.pbf`, `.pmtiles`, `.mbtiles`, Valhalla tile directory/tarball or Photon index files, and `data/` is listed in `.gitignore`.
7. **Given** `docker-compose.yml` (or `compose.yaml`), **When** it is inspected, **Then** every image is pinned to an explicit version tag or digest (no `:latest`), and each image reference can be overridden from `.env`, so that a Docker Hub 429 can be worked around with a ghcr.io image, a mirror or a local build.
8. **Given** the backend README, **When** a new developer reads it, **Then** it lists:
   - the start, stop, rebuild and smoke-test commands
   - the gateway port and the symbolic endpoints mapped to real paths
   - every external download the data build needs (OSM PBF, plus any Planetiler auxiliary sources such as Natural Earth or water polygons) and the `.env` key that overrides each one
   - the dev coverage: the default source is the full Mongolia extract from the dev-only mirror, production uses Geofabrik `mongolia-latest`, and the BBBike UB extract is an optional small/fast alternative that does not cover P3 and P6
   - any component that could not be run in the development environment, and why

### B. Basemap tiles (Protomaps PMTiles via Planetiler)
9. **Given** the stack is healthy, **When** a client sends `GET TILES` with header `Range: bytes=0-126`, **Then** the gateway responds **206 Partial Content** with a `Content-Range` header and a body starting with the PMTiles v3 magic bytes `PMTiles` followed by version byte `0x03`.
10. **Given** the PMTiles archive, **When** its header and metadata are read (for example with `pmtiles show`), **Then**:
    - the bounds cover all reference points P1 to P6
    - the max zoom is at least **14**
    - the metadata `attribution` contains `OpenStreetMap`
11. **Given** the PMTiles archive, **When** the z14 tile containing P1 is extracted (QA computes x/y from P1), **Then** it is non-empty and holds at least one road feature with a non-empty `name` attribute. The smoke report records whether `name:mn` and `name:en` attributes are present (informational, see Data risks).
12. **Given** `.env.example` sets `TILES_MAXZOOM=14` with a one-line comment (AC 3 rules apply), and the archive is built from the default Mongolia dev extract with that default, **When** its header and size are checked, **Then**:
    - the header max zoom is **exactly 14**, which still meets AC 10 (≥ 14)
    - the size is **≤ 200 MB** (MB = 2^20 bytes, so ≤ 209,715,200 bytes). This is the primary limit.
    - **Fallback (PO decision D1, 2026-09-30):** if the z14 archive is larger than 200 MB, the limit for the Mongolia dev extract is **≤ 400 MB** (≤ 419,430,400 bytes). A size between the two limits passes, and the check output prints an INFO line with the measured size in bytes and the text "AC 12 fallback limit (D1) applied"
    - the measured size and max zoom are recorded in the backend README ("Measured on the reference machine") or in `data/build-info.json`

    A size above 400 MB, or a default build with a max zoom other than 14, fails. The limits apply to the default Mongolia dev build only. The optional BBBike UB build has no size limit.

### C. Routing (Valhalla, OSRM-compatible, mn-MN)
13. **Given** the stack is healthy, **When** `ROUTE` is called from P1 to P3 with `costing=auto`, `format=osrm`, `banner_instructions=true`, `voice_instructions=true`, `language=mn-MN`, `units=kilometers`, **Then**:
    - HTTP status is **200** and `code` is `"Ok"`
    - `routes` has at least 1 entry
    - `routes[0].distance` is **≥ the straight-line distance P1→P3** and **≤ 2.5 × that distance**
    - `routes[0].duration` is **> 0**
14. **Given** the response from AC 13, **When** `routes[0].geometry` is decoded as **polyline6**, **Then** the first coordinate is **≤ 100 m** from P1 and the last is **≤ 100 m** from P3. The 50 m reference-point move allowance applies, so the **hard limit is ≤ 150 m** from the P1 and P3 coordinates in the table (100 m route-end limit + 50 m allowed point move). An endpoint between 100 m and 150 m passes, and the test output prints its measured distance as an INFO line. *(The 150 m hard limit was confirmed by the PO on 2026-09-30, decision D2.)*
15. **Given** the response from AC 13, **When** the steps are inspected, **Then** every step in `routes[0].legs[0].steps` has a `maneuver` object, and at least one step has both of the following, which is the minimum Ferrostar's OSRM parser needs:
    - `voiceInstructions[]` with non-empty `announcement` and numeric `distanceAlongGeometry`
    - `bannerInstructions[]` with non-empty `primary.text` and numeric `distanceAlongGeometry`
16. **Given** the response from AC 13, **When** all `announcement` strings are concatenated, **Then** they contain at least one Cyrillic character (U+0400 to U+04FF) **and** at least one Mongolian-specific letter (`ө`, `ү`, `Ө` or `Ү`), which proves the text is Mongolian and not Russian.
17. **Given** the same request as AC 13 but with `language=en-US`, **When** it is sent, **Then** the announcements are different from the `mn-MN` ones and contain no Cyrillic characters, except street names taken from OSM.
18. **Given** the stack is healthy, **When** `ROUTE` is called from P1 to P2 with `costing=pedestrian` (other parameters as in AC 13), **Then** HTTP status is **200**, `code` is `"Ok"`, and the distance is between the straight-line P1→P2 distance and 2.5 × that distance.
19. **Given** the stack is healthy, **When** `ROUTE` is called from P1 to P6 with `costing=auto` and costing option `exclude_unpaved=true`, **Then** the gateway returns either **200** with a route, or a **4xx** "no route" error with a JSON body. It must **never** return 5xx or time out. The result is recorded in the smoke report.
20. **Given** the stack is healthy, **When** `ROUTE` is called from P1 to P4 with `alternates=2`, **Then** HTTP status is **200** with **1 to 3** routes. Getting fewer alternates than requested is not a failure.

### D. Search (Photon autocomplete + reverse)
21. **Given** the stack is healthy, **When** `SEARCH` is called with `q=Сүхбаатар`, `lat=47.9189`, `lon=106.9176`, `limit=5`, **Then** HTTP status is **200**, the body is a GeoJSON `FeatureCollection` with **1 to 5** features, and at least one feature is **≤ 5 km** from P1.
22. **Given** the stack is healthy, **When** `SEARCH` is called with the partial query `q=Сүхб` and the same bias, **Then** HTTP status is **200** with **≥ 1** feature, which proves prefix (search-as-you-type) matching.
23. **Given** the stack is healthy, **When** `SEARCH` is called with a random non-word such as `q=xqzjwvk`, **Then** HTTP status is **200** with an **empty** `features` array. It must not be an error status.
24. **Given** the stack is healthy, **When** `SEARCH` is called with the Latin query `q=Sukhbaatar` and the same bias, **Then** HTTP status is **200**. The number of results and the distance of the nearest one to P1 are **recorded as a data-quality baseline**. Zero results here does **not** fail NAV-001, because the full Cyrillic/Latin requirement belongs to NAV-003.
25. **Given** the stack is healthy, **When** `REVERSE` is called with `lat=47.9189`, `lon=106.9176`, **Then** HTTP status is **200**, with **≥ 1** feature whose point is **≤ 300 m** from P1.
26. **Given** the stack is healthy, **When** `REVERSE` is called at X2 (Beijing), **Then** HTTP status is **200** with an **empty** `features` array, or a 4xx with a JSON body. It must never return 5xx.
27. **Given** the Photon index, **When** `SEARCH` is called with `lang=mn` and with `lang=en`, **Then** both return **200** (not "language not supported"). If the chosen index source cannot support `lang=mn`, the backend documents this and the architect records the fallback in openapi.yaml.

### E. Gateway, CORS and error handling
28. **Given** the stack is healthy, **When** `GET HEALTH` is called, **Then** it returns **200** within **1 s**.
29. **Given** a browser-style preflight `OPTIONS` to `ROUTE`, `SEARCH` and `TILES` with `Origin: http://localhost:5173`, `Access-Control-Request-Method: GET` and `Access-Control-Request-Headers: Range, Content-Type`, **When** it is sent, **Then** the response is **200 or 204** and includes:
    - `Access-Control-Allow-Origin`, matching the origin or `*`
    - `Access-Control-Allow-Methods`, including `GET`, `POST` and `OPTIONS`
    - `Access-Control-Allow-Headers`, including `Range` and `Content-Type`
30. **Given** a `GET TILES` with `Origin: http://localhost:5173` and a `Range` header, **When** it is sent, **Then** the response includes `Access-Control-Allow-Origin` and `Access-Control-Expose-Headers` listing at least `Content-Range`, `Content-Length` and `ETag`. The PMTiles JS client needs these in a browser.
31. **Given** the CORS origin key in `.env` is set to `http://localhost:5173` only, **When** a preflight comes from `Origin: http://evil.example`, **Then** the response carries **no** `Access-Control-Allow-Origin` for that origin.
32. **Given** `ROUTE` is called from P1 to a point outside the configured extract (**X2** on the default Mongolia build; X1 on the optional BBBike UB build), **When** it is sent, **Then** the gateway returns a **4xx** status (not 200, not 5xx) with a JSON body containing an error message, within **3 s**. The test output records which point was used.
33. **Given** `ROUTE` is called with a malformed body (for example missing `locations`), **When** it is sent, **Then** the gateway returns **400** with a JSON body within **1 s**.
34. **Given** the routing container is stopped (`docker compose stop <valhalla>`), **When** `ROUTE` is called, **Then** the gateway returns **502 or 503** within **5 s**, and `SEARCH` and `TILES` keep working. The same applies when the search container is stopped.

Added 2026-09-29 (numbered after AC 42 so existing AC numbers stay stable):

43. **Given** the stack is healthy and the archive size `S` is known from `HEAD TILES` `Content-Length`, **When** a client sends `GET TILES` with `Origin: http://localhost:5173` and an unsatisfiable range `Range: bytes=S-` (start ≥ `S`), **Then**:
    - the status is **416** and `Content-Range` is `bytes */S`
    - `Access-Control-Allow-Origin` and `Access-Control-Expose-Headers` each appear **exactly once**, and no other `Access-Control-*` header is repeated
    - `Content-Type` is `application/json`, and the body is valid JSON matching the gateway error schema in `openapi.yaml` (`code` and `message`), **not** HTML
    - a browser `fetch` of the same request from `http://localhost:5173` resolves with status 416 instead of rejecting with a CORS/network error

    Note: `bytes=99999999-` (about 95 MB) is **satisfiable** on the ~243 MB z15 Mongolia archive, and the z14 archive (AC 12, D1) has a different size, so QA must always derive the start from `S`.

### F. Performance baseline (reference machine, warm services, via gateway)
35. **Given** 20 sequential `ROUTE` requests (auto, mn-MN, OSRM format) among P1 to P5, **When** timed, **Then** the **p95 is ≤ 500 ms**.
36. **Given** 20 sequential `SEARCH` requests with 2 to 10 character Cyrillic prefixes, **When** timed, **Then** the **p95 is ≤ 300 ms**.
37. **Given** 20 sequential `REVERSE` requests at P1 to P5, **When** timed, **Then** the **p95 is ≤ 300 ms**.
38. **Given** 20 sequential `TILES` range requests for z10 to z14 tiles around P1, **When** timed, **Then** the **p95 is ≤ 100 ms**.
39. **Given** the stack is running steadily after startup, **When** `docker stats --no-stream` is read, **Then** the combined memory of all services is **≤ 6 GB**. Peak memory during the first-run build is **≤ 12 GB**. The whole `data/` directory is **≤ 10 GB**.

### G. Smoke test suite
40. **Given** a healthy stack, **When** the single documented smoke command (for example `make smoke` or `tests/smoke/run.sh`) runs with a configurable base URL (default `http://localhost:8080`), **Then** it runs AC 9, 13 to 16, 18, 21 to 23, 25, 28 to 30 and 32 through the **gateway only**, finishes in **≤ 60 s**, and exits **0**.
41. **Given** any single check fails (for example the Valhalla container is stopped), **When** the smoke command runs, **Then** it exits **non-zero** and prints the name of each failing check with the expected and actual values.
42. **Given** the smoke command finishes, **When** its output is read, **Then** it includes the informational values from AC 11 (`name:mn`/`name:en` presence), AC 19 (unpaved result) and AC 24 (Latin search baseline).

## Edge cases
- **OSM source unreachable or failing** (dev mirror `geo2day.com` down, BBBike 503, Geofabrik blocked, no network): the build stops with a non-zero exit and a message naming the URL and HTTP status. No service goes `healthy` on missing data, and the local-file key (AC 4) is the documented workaround.
- **Interrupted or partial build** (Ctrl-C, OOM, disk full): the next `docker compose up` detects the incomplete artefact and rebuilds it. It never serves a half-written PMTiles file, graph or index. Artefacts are written to a temp path and moved into place only on success.
- **Auxiliary downloads blocked** (Planetiler Natural Earth, water polygons, Photon dump server): same behaviour as an unreachable OSM source. Each URL can be overridden (AC 8).
- **Docker Hub 429**: images come from ghcr.io or are built locally from GitHub releases, and the image names can be overridden (AC 7).
- **Out-of-coverage coordinates**: the default dev extract covers all of Mongolia, so intercity/countryside points such as X1 Erdenet are routable in dev. Points outside Mongolia (X2 Beijing) make routing return 4xx and reverse return empty, with no 5xx (AC 26, 32). On the optional BBBike UB build, X1 is also out of coverage.
- **Range beyond the end of the tiles archive**: 416 with single CORS headers and a JSON error body, so a browser client sees the real status (AC 43).
- **Unpaved / ger-district roads**: `exclude_unpaved` must be accepted, and "no route" is a valid answer (AC 19).
- **No search result**: 200 with an empty list, not an error (AC 23).
- **Cyrillic/Latin transliteration** ("Sukhbaatar" vs "Сүхбаатар"): baseline recorded only (AC 24). Full requirement in NAV-003.
- **Photon `lang` not configured for `mn`** (AC 27).
- **Port 8080 already in use**: port set in `.env` (AC 3).
- **One upstream down**: the other services keep working, and the gateway returns 502/503 quickly (AC 34).
- **GPS loss, off-route, winter conditions**: not applicable to a backend-only PoC. They are client and costing concerns (NAV-005 and later).

## Data dependencies & risks
| # | Risk | Impact on NAV-001 | Mitigation / owner |
|---|---|---|---|
| R1 | **Dev default is a third-party mirror** (`geo2day.com`), not Geofabrik. Its PBF header has no bbox and no replication timestamp (a node scan gives bbox `[81.9257, 39.0189, 120.2728, 53.0383]`) | Data provenance and date are less certain than Geofabrik's. Dev data may differ from production. Tile bounds are a rectangle wider than Mongolia | **Dev only.** Production uses Geofabrik `mongolia-latest`. Record source, sha256 and HTTP `Last-Modified` (AC 5). Backend |
| R2 | **Dev source availability** (mirror may go down; BBBike returned HTTP 503 all day on 2026-09-29) | First-run build fails | Local-file override (AC 4). BBBike UB as a documented optional alternative. Backend |
| R3 | **Geofabrik blocked in the dev container** | The production source switch cannot be verified here | Verify AC 5 with the Geofabrik URL somewhere with access. QA/orchestrator |
| R12 | **The full Mongolia extract is larger** (~70 MB PBF vs 4.4 MB; PMTiles 243 MB vs 2.6 MB; source switch measured 336 s) | Cold first run (AC 1, ≤ 30 min including downloads) and AC 12 (≤ 200 MB) are at risk | AC 1 is re-verified on the Mongolia build. AC 12 is resolved by PO decision D1 (2026-09-30): build at z14, keep ≤ 200 MB, and fall back to ≤ 400 MB if z14 is still larger. Backend rebuilds and re-measures, and QA updates the check |
| R4 | **Planetiler auxiliary sources** (Natural Earth, water polygons) may be blocked or large | Tile build fails even when the PBF is local | Overridable URLs, cache under `data/`. Backend |
| R5 | **Photon index source.** Photon cannot import a PBF directly. It needs a Nominatim database import (GPL, heavier) or a prebuilt GraphHopper JSON dump (weekly, Asia region, may differ in date from the PBF used for tiles and routing) | Search results may not match the routing and tiles data; build time and disk vary a lot | Architect decides (see Open questions). Backend documents it |
| R6 | **`name:mn` / `name:en` coverage in OSM** for UB streets and POIs | Latin search (AC 24) and English labels may be weak. `name` in UB is usually Cyrillic Mongolian, so Mongolian UX falls back acceptably | Baseline recorded (AC 11, 24). Mapping programme later |
| R7 | **Valhalla `mn-MN` translation completeness and quality** | Some instructions may come back in English or read unnaturally | AC 16 checks only that Mongolian text is present. Native-speaker review is a separate task, with fixes contributed upstream |
| R8 | **`maxspeed` coverage** is sparse | `duration` uses road-class defaults, so ETAs are unrealistic, especially at rush hour | Not a NAV-001 pass criterion (AC 13 only needs duration > 0). Traffic in Phase 3 |
| R9 | **Address coverage (`addr:*`, khoroo, ger plots)** is irregular | Reverse geocoding may return a POI or area instead of a street address | AC 25 needs only some feature ≤ 300 m away. Address quality in NAV-003 |
| R10 | **Protomaps schema may not carry `name:mn`** | NAV-002's label order `name:mn` → `name` → `name:en` may effectively start at `name` | Recorded in AC 11. Architect/UX confirm for NAV-002 |
| R11 | **ODbL attribution** | Clients must show "© OpenStreetMap contributors" | PMTiles metadata attribution (AC 10). Display is done in NAV-002 |

## Out of scope
- Any mobile or web client, map style or UI strings (NAV-002+).
- Auth, API keys, rate limiting, TLS, production hosting, CDN. Hosting is tracked in a separate backlog item, [NAV-008](NAV-008-backend-hosting-staging.md) (`docs/requirements/stories/NAV-008-backend-hosting-staging.md`), not in NAV-001.
- The daily rebuild pipeline and blue/green switch (NAV-006).
- Nominatim structured search and admin lookups, unless the architect decides Photon's index source needs it (see Open questions).
- Traffic, incidents, transit, offline data.
- Winter-specific costing.
- Native-speaker review of the Valhalla `mn-MN` text.

## Open questions
Q1 to Q3 are not blocking. Q4 and Q5 were decided by the PO on 2026-09-30 (`docs/requirements/decisions.md`, D1 and D2).
1. **Is Nominatim part of NAV-001 or deferred?** Options: (a) defer, with Photon `/reverse` covering reverse in the PoC; (b) include Nominatim now as Photon's index source and for structured/reverse. *Recommendation: (a), unless the architect picks Nominatim as the Photon index source anyway (Q2).*
2. **Photon index source for dev** (architect decision, recorded in an ADR). Options: (a) own Nominatim import from the same PBF, which keeps data consistent but is heavier and GPL server-side; (b) a prebuilt GraphHopper Photon dump filtered to `mn`, which is fast but has a different data date, needs a large regional download and may lack `name:mn`. *Recommendation: (a) for consistency with tiles and routing, if it fits the 30-minute/10 GB limits on the UB extract; otherwise (b) for dev only.*
3. **Performance and resource limits** (AC 12, 35 to 39) are BA-proposed PoC baselines, not user-agreed SLAs. *Recommendation: accept for Phase 0 and revisit production targets with the hosting decision.*
4. **AC 12 PMTiles size on the full Mongolia build (PO decision).** The limit of ≤ 200 MB was written for the UB extract. The Mongolia build measured 243 MB (z0 to 15). Options:
   - (a) raise the limit for the Mongolia dev extract to **≤ 400 MB**
   - (b) keep ≤ 200 MB and have the backend build to max zoom 14 (AC 10 requires only ≥ 14, and clients overzoom), then re-measure
   - (c) apply AC 12 only to the optional BBBike UB build

   *Recommendation: (b) first, since it keeps the size baseline and the AC 10 minimum. If z14 is still over 200 MB, then (a).*

   **Decided 2026-09-30 (D1):** (b), with (a) as the fallback. AC 12 is rewritten accordingly.
5. **AC 14 tolerance** is recorded as 150 m total (see Change log 2026-09-29). **Decided 2026-09-30 (D2):** the PO confirmed 150 m.

## Traceability
Symbols map to `openapi.yaml` operations as follows:
- `TILES` = `getBasemapPmtiles` / `headBasemapPmtiles` / `preflightTiles` (`/tiles/basemap.pmtiles`)
- `ROUTE` = `postRoute` / `getRoute` / `preflightRoute`
- `SEARCH` = `search` / `preflightSearch`
- `REVERSE` = `reverse`
- `HEALTH` = `getHealth`

Tests abbreviations:
- `smoke` = `tests/smoke/run.sh`
- `checks` = `tests/api/nav001/checks.py`
- `contract` = `tests/api/nav001/contract.py`
- `static` = `tests/api/nav001/static_checks.py`
- `e2e` = `tests/e2e/nav001/cors-browser.spec.mjs`
- `plan §5.x` = `docs/qa/test-plans/NAV-001.md`

| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC1 | — | all | `backend/compose.yaml`, `backend/scripts/*` | plan §5.2 TC-01-01 (to re-run on the Mongolia default) | CR 2026-09-29: cold run now includes the ~70 MB Mongolia download |
| AC2 | — | all | `backend/compose.yaml`, `backend/scripts/*` | plan §5.3 TC-02-01 | CR 2026-09-29: re-verify on the Mongolia build |
| AC3 | — | — | `backend/.env.example` | `static` AC03.* | CR 2026-09-29: default URL is now the Mongolia dev mirror. D1 2026-09-30: `TILES_MAXZOOM` default 14 (static check to add, QA) |
| AC4 | — | — | `backend/scripts/data-fetch.sh` | plan §5.4 TC-04-01 | |
| AC5 | — | — | `backend/Makefile` (`rebuild-data`), `backend/scripts/build_info.py` | plan §5.5 TC-05-01 | |
| AC6 | — | — | `backend/.gitignore` | `static` AC06.* | |
| AC7 | — | — | `backend/compose.yaml` | `static` AC07.* | |
| AC8 | — | — | `backend/README.md` | `static` AC08.* | CR 2026-09-29: dev-coverage section |
| AC9 | — | `getBasemapPmtiles` | `backend/gateway/` | `smoke`, `checks` smoke, `e2e` E2E-01 | |
| AC10 | — | `getBasemapPmtiles` | `backend/scripts/tiles-build.sh` | `checks` full, `e2e` E2E-02 | CR 2026-09-29: evaluated on the Mongolia build (P3, P6 now covered) |
| AC11 | — | `getBasemapPmtiles` | `backend/scripts/tiles-build.sh` | `checks` full (info in `smoke`) | |
| AC12 | — | `headBasemapPmtiles` | `backend/.env.example`, `backend/compose.yaml` (`TILES_MAXZOOM`), `backend/scripts/tiles-build.sh` | `checks` full (`AC12.size_le_200MB` to be replaced by the D1 rule: max zoom = 14, ≤ 200 MB, or ≤ 400 MB with the fallback INFO line; QA) | D1 2026-09-30 (243 MB measured at z15; rebuild at z14 and re-measure) |
| AC13 | — | `postRoute` | `backend/scripts/valhalla-build.sh` | `smoke` | CR 2026-09-29: evaluated on the Mongolia build |
| AC14 | — | `postRoute` | — | `smoke` AC14.* (+ `.move` INFO) | CR 2026-09-29: hard limit 150 m. D2 2026-09-30: PO-confirmed |
| AC15 | — | `postRoute` | — | `smoke` | |
| AC16 | — | `postRoute` | — | `smoke`, `e2e` E2E-03 | |
| AC17 | — | `postRoute` | — | `checks` full | |
| AC18 | — | `postRoute` | — | `smoke` | |
| AC19 | — | `postRoute` | — | `checks` full (info in `smoke`) | CR 2026-09-29: P6 now inside the extract |
| AC20 | — | `postRoute` | — | `checks` full | |
| AC21 | — | `search` | `backend/scripts/photon-import.sh` | `smoke`, `e2e` E2E-04 | |
| AC22 | — | `search` | — | `smoke` | |
| AC23 | — | `search` | — | `smoke` | |
| AC24 | — | `search` | — | `checks` full (info in `smoke`) | |
| AC25 | — | `reverse` | — | `smoke`, `e2e` E2E-04 | |
| AC26 | — | `reverse` | — | `checks` full | |
| AC27 | — | `search` | — | `checks` full | |
| AC28 | — | `getHealth` | `backend/gateway/` | `smoke` | |
| AC29 | — | `preflightRoute`, `preflightSearch`, `preflightTiles` | `backend/gateway/snippets/cors-headers.conf` | `smoke`, `e2e` E2E-03 | |
| AC30 | — | `getBasemapPmtiles` | `backend/gateway/snippets/cors-headers.conf` | `smoke`, `e2e` E2E-01/02 | |
| AC31 | — | preflights, `postRoute`, `search` | `backend/gateway/entrypoint/15-cors-origins.sh` | `checks` cors-allowlist, `e2e` E2E-06, plan §5.7 | |
| AC32 | — | `postRoute` | `backend/gateway/` | `smoke`, `e2e` E2E-05 | CR 2026-09-29: X2 on the Mongolia build |
| AC33 | — | `postRoute` | `backend/gateway/` | `checks` full, `e2e` E2E-05 | |
| AC34 | — | `postRoute`, `search`, `reverse` | `backend/gateway/` | `checks` outage | |
| AC35 | — | `postRoute` | — | `checks` perf | CR 2026-09-29: re-verify on the Mongolia graph |
| AC36 | — | `search` | — | `checks` perf | |
| AC37 | — | `reverse` | — | `checks` perf | |
| AC38 | — | `getBasemapPmtiles` | — | `checks` perf | CR 2026-09-29: re-verify on the Mongolia archive |
| AC39 | — | — | `backend/compose.yaml` | `checks` stats, plan §5.2 TC-39-01 | |
| AC40 | — | all | `backend/Makefile` (`smoke`) | `smoke` | |
| AC41 | — | all | — | plan §5.6 TC-41-01 | |
| AC42 | — | all | — | `smoke` | |
| AC43 | — | `getBasemapPmtiles` / `headBasemapPmtiles` (416) | `backend/gateway/templates/default.conf.template` | `checks` AC43.{GET,HEAD}.*, CT08.range_beyond_416, CT16.no_repeated_cors.*, CT17.{GET,HEAD}.* (gateway started without the archive, `tests/api/nav001/isolated-gateway.sh`); `contract` CT-R20.tiles_{GET,HEAD}_416*; `e2e` E2E-06, E2E-07 | CR 2026-09-29: architect finding, duplicate CORS headers + HTML body on 416. ADR-0002 Amendment 2 |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-09-29 or earlier | — | Created (before this change log existed) | Research roadmap Phase 0 PoC |
| 2026-09-29 | CR "Default dev OSM data = all of Mongolia; fix 416 CORS duplicate headers" (PO decision in chat, 2026-09-29) | **Context:** the default dev OSM source is now the **full Mongolia extract** from the dev-only mirror `https://geo2day.com/asia/mongolia.pbf`. Production stays Geofabrik `mongolia-latest`, and the URL stays configurable. BBBike UB is now an optional small/fast alternative. The reference machine now uses the Mongolia build, and all ACs are evaluated on it. **AC 3:** the default URL is now the mirror with a dev-only comment, and BBBike may appear only commented. **AC 8:** the README must describe dev coverage. **AC 14:** the hard limit is 150 m (100 m route-end limit + 50 m allowed reference-point move), and 100 to 150 m is printed as INFO. This is the **orchestrator's recommendation, which the PO did not object to; the PO may revise it**. **AC 32 and X1:** X1 Erdenet is inside the new default extract, so AC 32 uses X2 on the Mongolia build and X1 only on the BBBike build. This is a direct consequence of the extract switch and matches QA's existing interpretation. **New AC 43:** a 416 on `TILES` has single CORS headers and a JSON error body. It is numbered 43 so existing numbers stay stable. **Edge cases and risks** updated: R1/R2 rewritten, R12 added. **Open question 4** added: AC 12 fails on Mongolia (243 MB > 200 MB), and the AC 12 text is unchanged pending the PO. **Traceability** table added. P1 to P6 unchanged | The BBBike box (~13 × 4 km) excludes P3 Zaisan and P6 Chingeltei, so AC 10, 13 and 14 failed on it, and BBBike returned HTTP 503 all day. On a full Mongolia build the backend measured smoke 35/35 and contract 27/27. The architect found that a 416 on the PMTiles endpoint duplicated every CORS header (browsers reject a duplicated `Access-Control-Allow-Origin`) and returned an HTML body, which is inconsistent with `openapi.yaml` |
| 2026-09-29 | Follow-up after implementation and QA | **Traceability AC43:** "QA to name" replaced with the real tests: `checks` AC43.{GET,HEAD}.*, CT08.range_beyond_416, CT16.no_repeated_cors.* and CT17.{GET,HEAD}.*; `contract` CT-R20.tiles_{GET,HEAD}_416*; `e2e` E2E-06 and E2E-07. The Code column now lists only `backend/gateway/templates/default.conf.template`, where the 416 handling lives (`error_page 416 = @range_not_satisfiable`). `headBasemapPmtiles` added to the API column, because the check runs on HEAD as well. **Out of scope:** the hosting line now links to NAV-008. No AC changed | Close the traceability gap left open when AC 43 was added, and point to the hosting story that now exists |

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
- **Reference machine** for all timing and size limits below: 4 CPU, 15 GB RAM, 30 GB free disk, Docker running, dev OSM source = BBBike Ulaanbaatar extract (~4.4 MB PBF).
- **Environment constraints known for this story:** Docker Hub is rate-limited (HTTP 429); ghcr.io and GitHub releases are reachable; `download.geofabrik.de` is blocked from the dev container; `https://download.bbbike.org/osm/bbbike/UlanBator/UlanBator.osm.pbf` works but was seen returning HTTP 503 on 2026-09-29, and a local copy of the PBF exists.

### Reference test locations (Ulaanbaatar, WGS84, approximate)
QA may move any point by up to 50 m to the nearest routable road. Every point must lie inside the dev extract's bounding box, which the backend checks with `osmium fileinfo` or equivalent and records in the README.

| Key | Place | lat | lon |
|---|---|---|---|
| P1 | Sükhbaatar Square (Сүхбаатарын талбай) | 47.9189 | 106.9176 |
| P2 | State Department Store (Их дэлгүүр), Peace Ave | 47.9139 | 106.9044 |
| P3 | Zaisan Memorial (Зайсан) | 47.8858 | 106.9173 |
| P4 | Gandantegchinlen Monastery (Гандан) | 47.9215 | 106.8950 |
| P5 | Ulaanbaatar railway station | 47.9095 | 106.8835 |
| P6 | Chingeltei ger-district area (unpaved roads likely) | 47.9600 | 106.9000 |
| X1 | Out of dev coverage: Erdenet | 49.0270 | 104.0440 |
| X2 | Out of any Mongolia coverage: Beijing | 39.9042 | 116.4074 |

## Acceptance criteria

### A. One-command startup and configuration
1. **Given** a clean clone with no `data/` directory, **When** the developer runs `cp .env.example .env` and then `docker compose up -d`, **Then** all services (tiles, routing, search, gateway, plus any one-shot build/init containers) finish without manual steps, and every long-running service reports `healthy` in `docker compose ps` within **30 minutes** on the reference machine.
2. **Given** `data/` already holds a complete build from a previous run, **When** the developer runs `docker compose down` and then `docker compose up -d`, **Then** all long-running services report `healthy` within **120 seconds**, and the logs show that the tiles, Valhalla graph and Photon index were **reused, not rebuilt**.
3. **Given** `.env.example`, **When** it is inspected, **Then** it contains at least:
   - an OSM source URL key whose default is `https://download.bbbike.org/osm/bbbike/UlanBator/UlanBator.osm.pbf`
   - a commented production value `https://download.geofabrik.de/asia/mongolia-latest.osm.pbf`
   - an optional local-file key that, when set, is used instead of downloading
   - the gateway host port (default `8080`)
   - the allowed CORS origins (default allows any origin, for local dev only)

   Every key has a one-line comment, and no secrets are present.
4. **Given** the local-file key points to an existing PBF and the OSM source URL is unreachable, **When** a full build is forced, **Then** the build succeeds with the local file and makes **no** request to the OSM source URL.
5. **Given** a documented "force rebuild" command (for example `docker compose run --rm <builder>` or `make rebuild-data`), **When** the developer changes the OSM source key and runs that command, **Then** the tiles, Valhalla graph and Photon index are all rebuilt from the new source. The README states which source and date were used for the current build, or a build-info file under `data/` records them.
6. **Given** the repository after a full build, **When** `git status --porcelain` and `git ls-files | grep -Ei '\.(osm\.pbf|pbf|pmtiles|mbtiles)$'` are run, **Then** neither shows any `.pbf`, `.pmtiles`, `.mbtiles`, Valhalla tile directory/tarball or Photon index files, and `data/` is listed in `.gitignore`.
7. **Given** `docker-compose.yml` (or `compose.yaml`), **When** it is inspected, **Then** every image is pinned to an explicit version tag or digest (no `:latest`), and each image reference can be overridden from `.env`, so that a Docker Hub 429 can be worked around with a ghcr.io image, a mirror or a local build.
8. **Given** the backend README, **When** a new developer reads it, **Then** it lists:
   - the start, stop, rebuild and smoke-test commands
   - the gateway port and the symbolic endpoints mapped to real paths
   - every external download the data build needs (OSM PBF, plus any Planetiler auxiliary sources such as Natural Earth or water polygons) and the `.env` key that overrides each one
   - any component that could not be run in the development environment, and why

### B. Basemap tiles (Protomaps PMTiles via Planetiler)
9. **Given** the stack is healthy, **When** a client sends `GET TILES` with header `Range: bytes=0-126`, **Then** the gateway responds **206 Partial Content** with a `Content-Range` header and a body starting with the PMTiles v3 magic bytes `PMTiles` followed by version byte `0x03`.
10. **Given** the PMTiles archive, **When** its header and metadata are read (for example with `pmtiles show`), **Then**:
    - the bounds cover all reference points P1 to P6
    - the max zoom is at least **14**
    - the metadata `attribution` contains `OpenStreetMap`
11. **Given** the PMTiles archive, **When** the z14 tile containing P1 is extracted (QA computes x/y from P1), **Then** it is non-empty and holds at least one road feature with a non-empty `name` attribute. The smoke report records whether `name:mn` and `name:en` attributes are present (informational, see Data risks).
12. **Given** the built archive for the dev extract, **When** its size is checked, **Then** it is **≤ 200 MB**.

### C. Routing (Valhalla, OSRM-compatible, mn-MN)
13. **Given** the stack is healthy, **When** `ROUTE` is called from P1 to P3 with `costing=auto`, `format=osrm`, `banner_instructions=true`, `voice_instructions=true`, `language=mn-MN`, `units=kilometers`, **Then**:
    - HTTP status is **200** and `code` is `"Ok"`
    - `routes` has at least 1 entry
    - `routes[0].distance` is **≥ the straight-line distance P1→P3** and **≤ 2.5 × that distance**
    - `routes[0].duration` is **> 0**
14. **Given** the response from AC 13, **When** `routes[0].geometry` is decoded as **polyline6**, **Then** the first coordinate is **≤ 100 m** from P1 and the last is **≤ 100 m** from P3.
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
32. **Given** `ROUTE` is called from P1 to X1 (outside the dev extract), **When** it is sent, **Then** the gateway returns a **4xx** status (not 200, not 5xx) with a JSON body containing an error message, within **3 s**.
33. **Given** `ROUTE` is called with a malformed body (for example missing `locations`), **When** it is sent, **Then** the gateway returns **400** with a JSON body within **1 s**.
34. **Given** the routing container is stopped (`docker compose stop <valhalla>`), **When** `ROUTE` is called, **Then** the gateway returns **502 or 503** within **5 s**, and `SEARCH` and `TILES` keep working. The same applies when the search container is stopped.

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
- **OSM source unreachable or failing** (BBBike 503, Geofabrik blocked, no network): the build stops with a non-zero exit and a message naming the URL and HTTP status. No service goes `healthy` on missing data, and the local-file key (AC 4) is the documented workaround.
- **Interrupted or partial build** (Ctrl-C, OOM, disk full): the next `docker compose up` detects the incomplete artefact and rebuilds it. It never serves a half-written PMTiles file, graph or index. Artefacts are written to a temp path and moved into place only on success.
- **Auxiliary downloads blocked** (Planetiler Natural Earth, water polygons, Photon dump server): same behaviour as an unreachable OSM source. Each URL can be overridden (AC 8).
- **Docker Hub 429**: images come from ghcr.io or are built locally from GitHub releases, and the image names can be overridden (AC 7).
- **Out-of-coverage coordinates** (intercity/countryside persona, dev extract is UB-only): routing returns 4xx and reverse returns empty, with no 5xx (AC 26, 32). Countryside coverage needs the production Mongolia extract.
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
| R1 | **BBBike extract covers only the UB bounding box** and is refreshed on BBBike's own schedule | Routes and search outside UB fail by design. Dev data may be days or weeks old | Production uses Geofabrik `mongolia-latest`. Record extract date (AC 5). Backend |
| R2 | **BBBike availability** (HTTP 503 seen on 2026-09-29) | First-run build fails | Local-file override (AC 4). Backend |
| R3 | **Geofabrik blocked in the dev container** | The production source switch cannot be verified here | Verify AC 5 with the Geofabrik URL somewhere with access. QA/orchestrator |
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
- Auth, API keys, rate limiting, TLS, production hosting, CDN.
- The daily rebuild pipeline and blue/green switch (NAV-006).
- Nominatim structured search and admin lookups, unless the architect decides Photon's index source needs it (see Open questions).
- Traffic, incidents, transit, offline data.
- Winter-specific costing.
- Native-speaker review of the Valhalla `mn-MN` text.

## Open questions
None are blocking. The story is `ready`.
1. **Is Nominatim part of NAV-001 or deferred?** Options: (a) defer, with Photon `/reverse` covering reverse in the PoC; (b) include Nominatim now as Photon's index source and for structured/reverse. *Recommendation: (a), unless the architect picks Nominatim as the Photon index source anyway (Q2).*
2. **Photon index source for dev** (architect decision, recorded in an ADR). Options: (a) own Nominatim import from the same PBF, which keeps data consistent but is heavier and GPL server-side; (b) a prebuilt GraphHopper Photon dump filtered to `mn`, which is fast but has a different data date, needs a large regional download and may lack `name:mn`. *Recommendation: (a) for consistency with tiles and routing, if it fits the 30-minute/10 GB limits on the UB extract; otherwise (b) for dev only.*
3. **Performance and resource limits** (AC 12, 35 to 39) are BA-proposed PoC baselines, not user-agreed SLAs. *Recommendation: accept for Phase 0 and revisit production targets with the hosting decision.*

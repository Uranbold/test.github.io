# ADR-0002: Local dev stack. Nginx gateway in front of pass-through Valhalla, Photon and a static PMTiles file

- **Status:** accepted (amended 2026-09-29, see Amendments)
- **Date:** 2026-09-29
- **Stories:** NAV-001 (enables NAV-002 to NAV-005)

## Context
NAV-001 has to bring up tiles, routing and search for Ulaanbaatar with one `docker compose up`, behind one gateway with CORS, on a 4 CPU / 15 GB / 30 GB machine. It is local dev only, with no auth. ADR-0001 fixes the engines. This ADR fixes how they are wired together and what the HTTP surface looks like, because every client story (NAV-002+) and Ferrostar build on that surface.

Things measured in this environment on 2026-09-29 that shaped the decision:
- Docker Hub and AWS ECR Public return 429. ghcr.io, mcr.microsoft.com, GitHub release downloads and Maven Central work.
- `ghcr.io/valhalla/valhalla:3.9.0` builds the UB graph from the 4.4 MB BBBike PBF in about 2 s (3.9 MB of tiles). `POST /route` with `format=osrm, language=mn-MN, voice_instructions, banner_instructions` returns 200 in under 100 ms, with Mongolian `announcement`, `bannerInstructions` and `voiceLocale: "mn-MN"`.
- Valhalla answers `OPTIONS` with **405** and always adds `Access-Control-Allow-Origin: *` to its own responses. Photon answers `OPTIONS` with **404**. So neither upstream can handle browser preflight or an origin allowlist.
- Valhalla error bodies depend on `format`. With `format=osrm` you get `{"code":"NoSegment","message":...}`. Without it, or when the JSON cannot be parsed, you get `{"error_code":171,"error":...,"status_code":400,"status":...}`. An unknown `language` silently falls back to `en-US` (200).
- The Protomaps basemap (`protomaps/basemaps`, `tiles/`) publishes **no jar and no git release tags** for the tiles module (CHANGELOG says Tiles 4.15.2 at commit `42ffaaa4`). It builds with Maven on Java 21 against Planetiler 0.10.2. It keeps only an allow-list of `name:*` languages, and **`mn` is not on it** (`OsmNames.ALLOWED_LANGS`), so tiles carry `name`, `name:en` and `name:ru` but not `name:mn`.
- The nginx image on ghcr (`ghcr.io/nginxinc/nginx-unprivileged`) stops at `1.27.4-alpine`. Caddy has no image on ghcr.

## Decision

### 1. Topology (one compose file: `backend/compose.yaml`, run from `backend/`)
| Service | Kind | Image (default, overridable from `.env`) | Job |
|---|---|---|---|
| `gateway` | long-running | `ghcr.io/nginxinc/nginx-unprivileged:1.27.4-alpine` | The only published port (`${GATEWAY_BIND:-127.0.0.1}:${GATEWAY_PORT:-8080}`). Handles CORS, routing, JSON error pages, and serves the static PMTiles file |
| `valhalla` | long-running | `ghcr.io/valhalla/valhalla:3.9.0` | `valhalla_service` on the prebuilt graph. Not published |
| `photon` | long-running | local build: `mcr.microsoft.com/openjdk/jdk:21-ubuntu` + `photon-1.3.0.jar` (GitHub release, checksum pinned) | `photon serve`. Not published |
| `data-fetch` | one-shot | any small image already available (for example the Valhalla image, which has curl) | Resolves the OSM PBF (local file key wins over URL), the Photon dump (ADR-0003) and the Planetiler auxiliary files into `data/sources/`, and writes `data/build-info.json` |
| `tiles-build` | one-shot | local build: Protomaps basemap at commit `42ffaaa4a85a41bfcb23e43cc0f5b492a5eca123`, Maven on `mcr.microsoft.com/openjdk/jdk:21-ubuntu` | Planetiler → `data/tiles/basemap.pmtiles` |
| `valhalla-build` | one-shot | same as `valhalla` | `valhalla_build_tiles` + `valhalla_build_extract` → `data/valhalla/` |
| `photon-import` | one-shot | same as `photon` | `photon import -import-file … -languages ${PHOTON_LANGUAGES:-mn,en,ru}` → `data/photon/` |

Long-running services use `depends_on: condition: service_completed_successfully` on their builder. Each has a Docker healthcheck: Valhalla `GET /status`, Photon `GET /status`, gateway `GET /health`. `gateway` depends on the builders **but not on the health of `valhalla` or `photon`**, so that one upstream being down does not take the gateway down (NAV-001 AC 34).

### 2. HTTP surface (normative definition: `docs/architecture/api/openapi.yaml`)
| Symbolic (story) | Gateway path | Upstream | Shaping |
|---|---|---|---|
| `HEALTH` | `GET /health` | answered by nginx | static `{"status":"ok"}` |
| `TILES` | `GET/HEAD /tiles/basemap.pmtiles` | static file, read-only mount | none. Native Range / ETag / 206 / 416 |
| `ROUTE` | `POST /v1/route`, `GET /v1/route?json=` | Valhalla `/route` | **pass-through** of request and response |
| `SEARCH` | `GET /v1/search` | Photon `/api` | **pass-through** |
| `REVERSE` | `GET /v1/reverse` | Photon `/reverse` | **pass-through** |

- **Pass-through, not a wrapper.** Clients send native Valhalla route JSON and get native OSRM-format JSON back, which is exactly what Ferrostar's Valhalla request generator and OSRM route adapter produce and consume. Photon GeoJSON is returned unchanged. The gateway adds no fields and does not inject default parameters.
- **`/v1` prefix** on the dynamic endpoints. If a later story needs a shaping wrapper (auth, caching, merged search), it ships as `/v2/...` without breaking existing clients. Tiles are versioned by file name, and health is unversioned.
- **Tiles are served as one PMTiles archive over HTTP Range.** There is no z/x/y tile server. MapLibre GL JS (with the `pmtiles` protocol) and MapLibre Native (`pmtiles://`) read the archive directly. This matches the static-hosting and CDN plan in ADR-0001 and removes one service.

### 3. Gateway responsibilities (nginx)
1. **CORS is owned by the gateway only.** The allowlist comes from `CORS_ALLOWED_ORIGINS` (`*` by default, or a comma-separated list), rendered through the nginx image's `templates/` envsubst mechanism into a `map $http_origin`. Rules:
   - `OPTIONS` on any path is answered by the gateway with **204** and is never forwarded.
   - Allowed origin: `Access-Control-Allow-Origin` = the request origin (or `*` in wildcard mode), plus `Vary: Origin`.
   - Always send `Access-Control-Allow-Methods: GET, HEAD, POST, OPTIONS` and `Access-Control-Allow-Headers: Range, Content-Type, If-None-Match, If-Match, Accept-Language`.
   - Always send `Access-Control-Expose-Headers: Content-Range, Content-Length, ETag, Accept-Ranges`, and `Access-Control-Max-Age: 600`.
   - Disallowed origin: no `Access-Control-Allow-Origin` at all.
   - Upstream CORS headers are removed (`proxy_hide_header Access-Control-Allow-Origin` and friends) so that Valhalla's `*` can never leak past the allowlist or be duplicated.
   - CORS headers use `always` so that they are also present on 206, 4xx and 5xx responses.
   - Each `Access-Control-*` header appears **at most once** on every response. Watch for nginx paths that run the header filter twice, for example the range filter's 416 (Amendment 2).
2. **Fast failure when an upstream is down.** `resolver 127.0.0.11 valid=10s` plus variable-based `proxy_pass`, so nginx starts, and keeps running, when an upstream container is stopped. Timeouts: `proxy_connect_timeout 2s`, `proxy_read_timeout 10s` for route and `5s` for search/reverse. Connection or DNS failure returns **502** or **503**, and a read timeout returns **504**. A *connect* timeout (a stopped container whose IP is still in the resolver cache) is also reported as **502** `UpstreamUnavailable`, not 504, so 504 means only "the upstream accepted the connection but did not answer in time" (implemented with a `map` on `$upstream_connect_time`; NAV-001 integration review). In every case the body is JSON `{"code":"UpstreamUnavailable"|"UpstreamTimeout","message":…}`, matching the OSRM `{code,message}` error shape.
3. **Its own JSON errors** for unknown path (404 `NotFound`), wrong method (405 `MethodNotAllowed`) and oversized body (413 `PayloadTooLarge`, with `client_max_body_size 256k`).
4. **Privacy in logs.** The access log format records method, `$uri` (path **without** query string), status, bytes and `$request_time`. It records no `$request_uri`, `$args`, request body or `Origin` beyond that. Coordinates and search text are location data and are treated as PII (see `system-overview.md`, NFR-P1). nginx's own `error_log` level defaults to `crit` (`GATEWAY_ERROR_LOG_LEVEL`), because `error`-level lines such as "connect() failed" quote the request line with its query string.
   *Correction (NAV-001 integration review):* the original text said Valhalla's `*.logging.long_request` thresholds would be raised. Valhalla 3.9.0 has no such setting. Its HTTP server instead logs every request line, so `GET /route?json=…` wrote coordinates to the Docker log. The decision is now: `valhalla_service` output is piped through `sed`, which replaces every query string with `?<redacted>` before it reaches the log (`backend/scripts/valhalla-serve.sh`). `valhalla_service` stays PID 1. POST bodies are not logged by Valhalla 3.9.0, including on 400 errors (checked in review). Re-check this whenever the Valhalla pin changes.
5. **Tiles.** `location = /tiles/basemap.pmtiles` uses `alias` to the read-only mount, with `Accept-Ranges: bytes`, ETag on, `Cache-Control: public, max-age=300` (dev value), and `Content-Type: application/octet-stream`. No gzip on this location, because range offsets must be byte-exact. An unsatisfiable range returns 416 with a JSON `GatewayError` (`RangeNotSatisfiable`) and single CORS headers (Amendment 2).

### 4. Data build contract (`backend/data/`, git-ignored)
- Layout (backend may add files): `data/sources/` (PBF, Photon dump, Planetiler auxiliary files, reused between builds), `data/tiles/basemap.pmtiles`, `data/valhalla/`, `data/photon/`, `data/build-info.json`.
- **Atomic artefacts.** Each builder writes to a staging path (`<artefact>.tmp` or `<dir>.staging`), validates the result (PMTiles header magic plus `pmtiles show`, Valhalla tile tar non-empty, Photon `/status` smoke), then renames it into place and writes a `.complete` marker last. A builder whose marker exists **skips the build and logs `reused`**. A builder that finds a `.tmp` or a missing marker deletes the partial output and rebuilds it.
- **Default dev source:** the full Mongolia extract (Amendment 1). BBBike UB is an optional small build.
- **Source resolution.** If `OSM_PBF_FILE` is set and readable, it is used and **no request is made to `OSM_PBF_URL`**. Otherwise the file is downloaded with `curl --fail`. On failure the builder exits non-zero with the URL and HTTP status. The same rule applies to `PHOTON_DUMP_FILE` / `PHOTON_DUMP_URL`. Every Planetiler auxiliary file has its own `*_URL` key and is pre-fetched into `data/sources/`, so Planetiler runs without `--download`.
- **Force rebuild.** `make rebuild-data` (or an equivalent documented one-liner) deletes the markers and artefacts, **but not the cached auxiliary sources**, and re-runs all builders.
- **`data/build-info.json`** is the machine-readable record QA reads (NAV-001 AC 5). Minimum fields: `built_at`, and `osm.source` (URL or `file:`), `osm.sha256`, `osm.replication_timestamp` (from `osmium fileinfo`-equivalent header or PBF header), `osm.bbox`, `photon_dump.source`, `photon_dump.data_timestamp`, and `versions.{valhalla,photon,protomaps_commit,planetiler}`.

### 5. Pinned versions (NAV-001 AC 7)
| Component | Pin | License |
|---|---|---|
| Valhalla | `ghcr.io/valhalla/valhalla:3.9.0` (`sha256:511c095b8caf393dccceb8b519ec96b6f85a0166b2288ba014a8a748acc5a63c`) | MIT |
| Photon | `photon-1.3.0.jar` from GitHub release `1.3.0` | Apache-2.0 |
| Protomaps basemap (tiles) | git commit `42ffaaa4a85a41bfcb23e43cc0f5b492a5eca123` (Tiles 4.15.2) | BSD-3 (code), ODbL (output data) |
| Planetiler | 0.10.2 (through the Protomaps `pom.xml`) | Apache-2.0 |
| nginx | `ghcr.io/nginxinc/nginx-unprivileged:1.27.4-alpine` | BSD-2 |
| Java runtime | `mcr.microsoft.com/openjdk/jdk:21-ubuntu` | GPLv2 + Classpath Exception (runtime only, standard for Java, no copyleft effect on our code) |
| go-pmtiles (verification only) | `ghcr.io/protomaps/go-pmtiles:v1.31.2` | BSD-3 |

Every image reference is a `.env` key (`GATEWAY_IMAGE`, `VALHALLA_IMAGE`, `JAVA_BASE_IMAGE`, `PMTILES_IMAGE`), so a mirror or Docker Hub (`nginx:1.28-alpine` and similar) can be swapped in. No `:latest`.

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| **Nginx gateway (chosen)** | Native Range/ETag for PMTiles, `map`-based origin allowlist, mature `proxy_*` timeouts, image available on ghcr | CORS config is verbose. The ghcr image is 1.27.4 (Feb 2025), which is fine for local dev and gets bumped when Docker Hub is reachable |
| Caddy gateway | Short config, automatic TLS later | No ghcr image (Docker Hub only, 429 here). No built-in CORS directive, so it needs matchers anyway |
| Own gateway service (Go/Node) | Full control of shaping | Code to write and maintain. The story needs no shaping. Violates "keep it simple" |
| z/x/y tile server (Martin, `pmtiles serve`) | Works with any XYZ client | One more service. Clients already speak PMTiles. Diverges from the static/CDN production plan |
| Unversioned paths (`/route`, `/api`) | Mirrors upstream exactly | No room to add a shaping wrapper later without breaking clients |
| Photon/Valhalla `-cors-any` instead of gateway CORS | Less gateway config | Cannot enforce an allowlist (AC 31). Valhalla cannot answer preflight (405). Duplicate headers |
| Upstream `planetiler.jar` OpenMapTiles profile (prebuilt jar, no Maven) | No source build | OpenMapTiles schema, not the Protomaps schema chosen in ADR-0001. The ecosystem styles for it are separate |

## Consequences
- The client contract is exactly Valhalla + Photon, so upstream docs apply directly. Upstream upgrades can change response details, which is why versions are pinned and the OpenAPI schemas are permissive (`additionalProperties: true`) about fields the clients do not use.
- Clients must always send `format: "osrm"` and an explicit `language` to `/v1/route`, and an explicit `lang` to Photon. The gateway does not add defaults.
- Route errors come in two shapes (OSRM `{code,message}` and Valhalla `{error_code,error,…}`), and gateway errors use `{code,message}`. Clients branch on HTTP status first, then read `message` or `error`.
- **`name:mn` is not in the Protomaps tiles.** NAV-002's label order `name:mn` → `name` → `name:en` effectively starts at `name`, which is Cyrillic Mongolian for most of UB. Follow-up: propose upstream adding `"mn"` to `OsmNames.ALLOWED_LANGS` (a one-line change). We do not fork (ADR-0001 principle). Revisit in NAV-002.
- The PMTiles metadata attribution is `© OpenStreetMap` (a link), not the full "© OpenStreetMap contributors". Clients render the full string from their own resource files (CLAUDE.md rule 8) and must not rely on the metadata string.
- The Protomaps build needs about 2.5 GB of auxiliary downloads (Natural Earth 446 MB, water polygons 931 MB, land polygons 952 MB, Daylight landcover 36 MB, QRank 106 MB, pgf-encoding 0.8 MB). They are cached in `data/sources/` so they download only once. Licenses (recorded in `backend/README.md`): Natural Earth (public domain), OSM water/land polygons (ODbL), QRank (CC0), pgf-encoding (CC0 encodings, SIL OFL fonts, MIT code), and **Daylight landcover (CC BY 4.0, derived from ESA WorldCover)**. None is copyleft.
- **Extra map credit for landcover.** Because the Protomaps landcover layer (shown at z0–z7) is CC BY 4.0, every map screen that shows it must credit "© ESA WorldCover project / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium" next to "© OpenStreetMap contributors". Clients take both strings from their resource files. How the credit is laid out is a UX decision for NAV-002.
- Production (NAV-006) replaces the one-shot builders with a scheduled pipeline and blue/green switch. The gateway paths stay the same.

## Amendments

### Amendment 1 (2026-09-29): the default dev OSM source is all of Mongolia
**Trigger.** A PO decision in chat on NAV-001, change request "Default dev OSM data = all of Mongolia". The BBBike UB box (about 13 × 4 km, bbox `[106.8392, 47.8995, 107.0167, 47.9337]`) excludes reference points P3 Zaisan and P6 Chingeltei, so AC 10, 13 and 14 fail on it. BBBike also returned HTTP 503 for the whole day. On a full Mongolia build the backend measured smoke 35/35 and contract 27/27.

**Decision.**
- `.env.example` default: `OSM_PBF_URL=https://geo2day.com/asia/mongolia.pbf`, a third-party mirror. It is **dev only**, because `download.geofabrik.de` is blocked from the dev container (301 loop via the proxy).
- Production stays **Geofabrik `mongolia-latest.osm.pbf`**. It is written in `.env.example` as a comment, and the URL stays configurable.
- BBBike UB stays as a documented optional small/fast alternative (commented), not the default.
- There is no change to the builders, the build contract (§4), the gateway paths or the HTTP contract schemas. Only defaults and documentation change.

**Consequences.**
- Dev coverage for tiles, routing and search is now the same area (all of Mongolia). The ADR-0003 caveat that "search returns places routing can't reach" applies only to the optional BBBike build.
- Out-of-coverage tests use X2 Beijing on the default build (NAV-001 AC 32).
- The build gets bigger. Measured: source switch 336 s (Planetiler 229 s, graph 22 s), PMTiles 243 MB, `data/` 2.9 GB, peak build memory 4.24 GB on the source switch and 5.5 GB on the cold first run (sampled every 3 s). All are within the AC 39 limits (build peak ≤ 12 GB, `data/` ≤ 10 GB). **AC 12 (PMTiles ≤ 200 MB) is exceeded.** That limit was written for UB and is a PO decision (NAV-001 Open question 4). Architect note on option (b): building to `--maxzoom=14` stays inside the contract (`maxzoom >= 14`), because MapLibre overzooms z14 tiles.
- The cold first run (AC 1, ≤ 30 min) was re-measured on 2026-09-29: **462 s** with an empty `data/` and images already pulled (downloads 149 s for about 2.5 GB, of which 70 MB is the PBF). The BBBike cold run was 530 s; the difference is download speed on the day, not data size. Image pulls are not included (Docker Hub rate limits in the dev container).
- **Third-party mirror risk (dev only).** Availability and integrity of geo2day.com are not guaranteed, and its PBF header has no replication timestamp. `data/build-info.json` records `sha256`, HTTP `Last-Modified` and the node-scan bbox, which is enough to reproduce a dev build. `OSM_PBF_FILE` stays the offline fallback. The OSM data license (ODbL) is unchanged.

### Amendment 2 (2026-09-29): a 416 on TILES has a JSON body and single CORS headers
**Trigger.** Architect integration-review finding (minor), now NAV-001 AC 43. `GET /tiles/basemap.pmtiles` with `Range: bytes=<start ≥ size>-` returned 416 with **every CORS header twice** and nginx's HTML error page. Browsers reject a duplicated `Access-Control-Allow-Origin`, so a web client saw a CORS failure instead of 416.

**Root cause (reproduced in a scratch `nginx-unprivileged:1.27.4-alpine` container).** The headers filter (`add_header … always`) runs first with status 200. The range filter then finds the range unsatisfiable and finalizes the request as 416 through the special-response path, which runs the header filter again, and the location's `add_header … always` adds everything a second time. Adding `error_page 416 = @named` with the CORS snippet included in the named location still duplicates the headers, because the first-pass headers survive the internal redirect.

**Decision (verified recipe).**
```nginx
location = /tiles/basemap.pmtiles {
    include /etc/nginx/snippets/cors-headers.conf;
    # ... existing directives ...
    # A location that declares any error_page no longer inherits the server-level ones: re-declare them.
    error_page 404 = @not_found;
    error_page 405 = @method_not_allowed;
    error_page 416 = @range_not_satisfiable;
}
# No cors-headers.conf here on purpose: the tiles location already added them in the first
# header-filter pass, and including them again duplicates every Access-Control-* header.
location @range_not_satisfiable {
    default_type application/json;
    return 416 '{"code":"RangeNotSatisfiable","message":"Requested range not satisfiable"}';
}
```
Scratch-container results, each `Access-Control-*` header counted once:
- 416: `Content-Type: application/json`, `Content-Range: bytes */<size>` kept, and the disallowed origin gets no `Access-Control-Allow-Origin`
- HEAD 416 correct
- 200, 206, OPTIONS 204, POST 405 JSON, missing file 404 JSON all unchanged

`@range_not_satisfiable` must only be the target of the tiles location.

**Contract.** `openapi.yaml` 0.2.0 adds the `RangeNotSatisfiable` response (JSON `GatewayError`) and the new enum value. The change is additive, and no client relied on the old HTML body.

**Rejected alternatives:**
- Compute the file size in nginx and reject the range before the static handler. This needs njs or Lua, which means a new module for one edge case.
- Drop `always` from the tiles location's CORS headers. This also avoids the duplicate, but it needs a second, non-`always` copy of the shared CORS snippet. Any error in that location that is not redirected to a named location (for example a 403 or 500 from an unreadable file) would then lose its CORS headers, so browsers would again report a CORS error instead of the status.

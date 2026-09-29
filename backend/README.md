# Backend (owner: backend-engineer)

NAV-001 local stack: Protomaps **PMTiles** basemap, **Valhalla** routing (OSRM-format output with
`mn-MN` voice and banner instructions for Ferrostar), **Photon** search and reverse geocoding, all
behind one **nginx** gateway with CORS. It starts with one `docker compose up`.

- Contract: [`docs/architecture/api/openapi.yaml`](../docs/architecture/api/openapi.yaml) (owned by the architect; this stack implements it)
- Design: [ADR-0002](../docs/architecture/adr/0002-local-stack-gateway-and-api-surface.md) (gateway, paths, data build), [ADR-0003](../docs/architecture/adr/0003-photon-index-from-country-dump.md) (Photon index source)
- Story: [NAV-001](../docs/requirements/stories/NAV-001-backend-stack-docker-compose.md). Local development only, no auth.

## Commands

Run everything from `backend/`. Requirements: Docker with Compose v2, `make`, and `python3` on the host (for the smoke and contract checks).

| What | Command |
|---|---|
| First start (builds data if needed, waits until healthy) | `cp .env.example .env && docker compose up -d`, or `make up` (adds `--wait`) |
| Stop (keeps `data/`) | `docker compose down`, or `make down` |
| Status | `docker compose ps`, or `make ps` |
| Force a data rebuild from the current `.env` sources | `make rebuild-data` |
| Smoke test through the gateway | `make smoke` (QA's `../tests/smoke/run.sh` if it exists, otherwise `scripts/smoke.py`) |
| Backend smoke suite + latency baselines (AC 35–38) | `make perf` |
| Contract check against openapi.yaml | `make contract` (creates `.venv` with jsonschema + PyYAML) |
| CORS allowlist check (AC 31) | `make cors-check` |
| Memory and disk (AC 39) | `make stats` |
| Build record (sources, dates, versions) | `make build-info` (prints `data/build-info.json`) |
| PMTiles header and metadata | `make tiles-info` |

`BASE_URL` overrides the gateway URL for `smoke`, `perf` and `contract` (default `http://localhost:8080`).

## Endpoints (gateway, default `http://localhost:8080`)

Only the gateway publishes a port (`GATEWAY_BIND:GATEWAY_PORT`, default `127.0.0.1:8080`). Valhalla and Photon are reachable only through it.

| Story symbol | Gateway path | Upstream |
|---|---|---|
| `HEALTH` | `GET /health` | answered by nginx: `{"status":"ok"}` |
| `TILES` | `GET/HEAD /tiles/basemap.pmtiles` | static file with HTTP Range, ETag, `Cache-Control: public, max-age=300`, no gzip |
| `ROUTE` | `POST /v1/route` (also `GET /v1/route?json=`) | Valhalla `/route`, forwarded unchanged |
| `SEARCH` | `GET /v1/search` | Photon `/api`, forwarded unchanged |
| `REVERSE` | `GET /v1/reverse` | Photon `/reverse`, forwarded unchanged |

The gateway alone handles CORS. It answers every `OPTIONS` with 204 and strips the upstreams' own
`Access-Control-*` headers. Gateway errors are JSON `{code, message}`:
- 404 `NotFound`, 405 `MethodNotAllowed`, 413 `PayloadTooLarge` (bodies over 256 KB)
- 502 `UpstreamUnavailable`: the upstream is stopped, cannot be resolved or refuses the connection. This includes a connect timeout (2 s).
- 504 `UpstreamTimeout`: the upstream accepted the connection but did not answer within 10 s (route) or 5 s (search/reverse).

Clients must send `format: "osrm"`, `language` and `units` to `/v1/route`, and `lang` to Photon. The gateway injects no defaults (ADR-0002).

Example:
```sh
curl -s localhost:8080/v1/route -H 'Content-Type: application/json' -d '{
  "locations":[{"lat":47.9189,"lon":106.9176},{"lat":47.9139,"lon":106.9044}],
  "costing":"auto","format":"osrm","banner_instructions":true,"voice_instructions":true,
  "language":"mn-MN","units":"kilometers"}'
curl -s 'localhost:8080/v1/search?q=%D0%A1%D2%AF%D1%85%D0%B1&lang=mn&lat=47.9189&lon=106.9176&limit=5'
curl -s 'localhost:8080/v1/reverse?lat=47.9189&lon=106.9176&lang=mn'
```

## How the stack starts

```
data-fetch ──> tiles-build ───┐
           ├─> valhalla-build ┼─> build-info ──> gateway
           └─> photon-import ─┘
valhalla-build ──> valhalla      photon-import ──> photon
```

| Service | Kind | Image (a `.env` key overrides it) | Job |
|---|---|---|---|
| `data-fetch` | one-shot | `VALHALLA_IMAGE` (has curl, python3) | Resolves the OSM PBF, the Photon dump, the Planetiler auxiliary files and the pinned tools into `data/` |
| `tiles-build` | one-shot | `JAVA_BASE_IMAGE` | Builds the Protomaps jar once with Maven, runs Planetiler, validates the archive |
| `valhalla-build` | one-shot | `VALHALLA_IMAGE` | `valhalla_build_tiles` + `valhalla_build_extract` |
| `photon-import` | one-shot | `JAVA_BASE_IMAGE` | Streams the zstd dump into `photon import -languages mn,en,ru` |
| `build-info` | one-shot | `VALHALLA_IMAGE` | Writes `data/build-info.json` |
| `valhalla` | service | `VALHALLA_IMAGE` | `valhalla_service`; healthcheck `GET /status` |
| `photon` | service | `JAVA_BASE_IMAGE` | `photon serve -default-language mn -max-results 20`; healthcheck `GET /status` |
| `gateway` | service | `GATEWAY_IMAGE` | nginx; healthcheck `GET /health`. Not gated on Valhalla/Photon health, so one upstream going down does not take the others down (AC 34) |

No image is built locally. All images are pinned (see below), and the Photon jar, the Protomaps
source and Maven are downloaded at runtime with checksums.

### Reuse, rebuild and atomic writes
- Every builder writes to a staging path, validates the result, moves it into place, and then writes a `.complete` marker. The first line of the marker is the fingerprint of its inputs: the OSM sha256, tool versions, languages, max zoom and bounds.
- On `docker compose up`, a builder whose marker matches its current inputs logs `"msg":"reused"` and exits in about a second. A missing marker (interrupted build) or changed inputs trigger a rebuild. A half-written artefact is never served.
- `data-fetch` reuses an input when its recorded source key matches `.env`. A changed URL, or a changed local file (path, size or mtime), is fetched again, and the builders then rebuild automatically.
- **`make rebuild-data`** stops the services, deletes the artefacts and the resolved OSM/Photon inputs (not the ~2.5 GB of cached auxiliary files or the tools), and runs the whole chain again.
- **Photon's index is rebuilt from `PHOTON_DUMP_URL` / `PHOTON_DUMP_FILE`, not from the OSM source key** (ADR-0003). Changing `OSM_PBF_URL` rebuilds the tiles and the routing graph. The search index is re-imported from the configured dump, whose date is recorded separately.
- `data/build-info.json` records the OSM source (URL or `file:`), its sha256, the replication timestamp from the PBF header, the HTTP `Last-Modified`, the bbox (and whether it came from the header or a node scan), and which reference points lie inside it. It also records the Photon dump source and `data_timestamp`, every auxiliary source, the artefact build times and sizes, and the versions.

### `data/` layout (git-ignored)
```
data/sources/   osm.pbf (+ .sha256 .source .json .bounds .last-modified), photon-dump, Planetiler auxiliary files
data/tools/     photon.jar, aircompressor.jar, protomaps-basemap-<commit>.jar, Protomaps source + Maven tarballs
data/tiles/     basemap.pmtiles, .complete
data/valhalla/  valhalla_tiles.tar, valhalla.json (runtime config), .complete
data/photon/    photon_data/, dump-header.json, .complete
data/build-info.json
```
Files in `data/` are created by containers running as root. Use `make rebuild-data` or `make clean-data`, which delete them from inside a container, instead of deleting them by hand.

## Configuration (`.env`)

Every key is documented in [`.env.example`](.env.example) and has the same default in `compose.yaml`, so the stack also runs without a `.env`. Relative paths are resolved from `backend/`.

### Downloads needed by the data build, and the key that overrides each

| Download | Key | Default | Size | Licence |
|---|---|---|---|---|
| OSM extract | `OSM_PBF_URL` (or local `OSM_PBF_FILE`) | BBBike Ulaanbaatar. Production: Geofabrik `mongolia-latest` (commented in `.env.example`) | 4.4 MB (UB) | ODbL 1.0, © OpenStreetMap contributors |
| Photon dump | `PHOTON_DUMP_URL` (or local `PHOTON_DUMP_FILE`) | GraphHopper Mongolia `photon-dump-mongolia-1.0-latest.jsonl.zst` | 8.1 MB | ODbL (OSM-derived) |
| Natural Earth vector | `NATURAL_EARTH_URL` | naciscdn.org `natural_earth_vector.gpkg.zip` | 446 MB | Public domain |
| OSM water polygons | `WATER_POLYGONS_URL` | osmdata.openstreetmap.de `water-polygons-split-3857.zip` | 931 MB | ODbL |
| OSM land polygons | `LAND_POLYGONS_URL` | osmdata.openstreetmap.de `land-polygons-split-3857.zip` | 952 MB | ODbL |
| Daylight landcover | `LANDCOVER_URL` | r2-public.protomaps.com `daylight-landcover.gpkg` | 36 MB | CC BY 4.0, derived from ESA WorldCover. Maps that show the landcover layer (z0–z7) must credit "© ESA WorldCover project / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium" |
| Wikidata QRank | `QRANK_URL` | qrank.toolforge.org `qrank.csv.gz` | 106 MB | CC0 |
| pgf-encoding | `PGF_ENCODING_URL` | wipfli.github.io `pgf-encoding.zip` | 0.8 MB | Encodings CC0, bundled fonts SIL OFL 1.1, code MIT |
| Photon 1.3.0 jar | `PHOTON_JAR_URL` + `PHOTON_JAR_SHA256` | GitHub release | 98 MB | Apache-2.0 |
| aircompressor 0.27 (pure-Java zstd, for the dump) | `AIRCOMPRESSOR_URL` + `AIRCOMPRESSOR_SHA256` | Maven Central | 0.25 MB | Apache-2.0 |
| Protomaps basemap source @ `42ffaaa4` | `PROTOMAPS_SRC_URL` + `PROTOMAPS_SRC_SHA256` (+ `PROTOMAPS_COMMIT`) | codeload.github.com tarball | 2.9 MB | BSD-3 (code) |
| Maven 3.9.11 (builds the Protomaps jar once) | `MAVEN_DIST_URL` + `MAVEN_DIST_SHA512` | Maven Central | 9 MB | Apache-2.0 |
| Maven dependencies of the Protomaps build | none (Maven Central + repo.osgeo.org, from the Protomaps `pom.xml`) | | ~150 MB, deleted after the build | various OSS |

An auxiliary file already in `data/sources/` is used without any request, so you can pre-seed it by
hand when a host is blocked. Any failed download stops the build with a non-zero exit and a log line
that names the URL and HTTP status. No service goes healthy on missing data.

### Pinned images (AC 7)
| Key | Default |
|---|---|
| `GATEWAY_IMAGE` | `ghcr.io/nginxinc/nginx-unprivileged:1.27.4-alpine` |
| `VALHALLA_IMAGE` | `ghcr.io/valhalla/valhalla:3.9.0@sha256:511c095b8caf393dccceb8b519ec96b6f85a0166b2288ba014a8a748acc5a63c` |
| `JAVA_BASE_IMAGE` | `mcr.microsoft.com/openjdk/jdk:21-ubuntu` |
| `PMTILES_IMAGE` (only `make tiles-info`) | `ghcr.io/protomaps/go-pmtiles:v1.31.2` |

Docker Hub returned 429 in the dev container, so every default comes from ghcr.io or mcr.microsoft.com.
The ghcr nginx image stops at 1.27.4; switch to `nginxinc/nginx-unprivileged:1.28-alpine` when Docker Hub is reachable.

### Networks with TLS interception or a proxy
- `EXTRA_CA_CERT`: a PEM file whose certificates are trusted by the build downloads, both curl and Maven. The default is an empty file.
- `BUILD_HTTPS_PROXY`: an explicit outbound proxy for curl and Maven.
- Only `data-fetch` and the one-time Maven step in `tiles-build` use the network.

## Dev extract coverage (read this before judging route results)

`python3 scripts/pbfinfo.py` reads the PBF header (the equivalent of `osmium fileinfo`); the result is in `data/build-info.json` under `osm.bbox`.

| Source | bbox [minLon, minLat, maxLon, maxLat] | Data date |
|---|---|---|
| BBBike UlanBator (`/tmp/claude-0/ub.pbf`, the same file the URL served on 2026-09-29) | `[106.8392, 47.8995, 107.0167, 47.9337]` (header) | replication timestamp 2026-09-25T23:00:00Z |
| geo2day.com Mongolia (`https://geo2day.com/asia/mongolia.pbf`, used for the AC 5 source-switch test) | `[81.9257, 39.0189, 120.2728, 53.0383]` (node scan; the header has no bbox) | no replication timestamp in the header; HTTP `Last-Modified` is recorded |

**The BBBike UB box is only about 13 × 4 km. Reference points P3 (Zaisan, lat 47.8858) and P6
(Chingeltei ger district, lat 47.9600) lie outside it.** On the BBBike extract:
- the P1→P3 route snaps to the southern edge of the box, about 1.4 km short of Zaisan (AC 13/14 fail)
- the tile bounds do not cover P3/P6 (AC 10 fails)
- P1→P6 "succeeds" by snapping to the northern edge (AC 19)

`build-info` logs a warning listing the uncovered points. A source that covers all of UB, for example the Mongolia extract, passes these checks (see Measurements). Which dev default to use is an open product decision.

## Measured on the reference machine (4 CPU, 15 GB RAM, dev container, 2026-09-29)

MEASUREMENTS_PLACEHOLDER

## Privacy and logs
- **Gateway access log:** one JSON line per request with method, path without the query string, status, bytes and timings. No client IP, query string, body, Origin or User-Agent.
- **Gateway error log:** at `crit` by default (`GATEWAY_ERROR_LOG_LEVEL`), because nginx error lines contain the full request line.
- **Valhalla:** `valhalla_service` logs every HTTP request line. The serve script pipes it through `sed`, which replaces query strings with `?<redacted>`, so `GET /route?json=…` never writes coordinates. POST bodies are never logged. Valhalla 3.9.0 has no `*.logging.long_request` setting any more (ADR-0002 §3.4 assumed it did), so there is nothing to raise.
- **Photon:** logs no queries at INFO.
- **Builders:** log JSON lines with URLs, file names, sizes and durations only.

## Could not be run or verified here
- **Geofabrik (`download.geofabrik.de`) is blocked from the dev container**, where it answers with a 301 loop via a squid proxy. The production source switch was exercised with the geo2day.com Mongolia extract instead. Geofabrik PBFs carry a header bbox, so the node scan is not needed there.
- **The BBBike URL returned HTTP 503 on 2026-09-29.** The BBBike runs used the local copy through `OSM_PBF_FILE`, and the download path was verified with the other URLs. The "source unreachable" behaviour (non-zero exit naming the URL and status) was observed on a TLS failure during development.
- **Ferrostar parsing the route response end to end** is not tested here (no client in NAV-001). Only the OpenAPI schema and the fields in AC 15 are checked.
- **Docker Hub images** (`nginx:1.28`, `mediagis/nominatim`) were not tried, because Docker Hub returns 429.
- **Nominatim** is not part of NAV-001 (ADR-0003).

## Troubleshooting
- **`service "data-fetch" didn't complete successfully`**: run `docker compose logs data-fetch`. The last line names the URL and HTTP status. For the OSM or Photon source, set `OSM_PBF_FILE` / `PHOTON_DUMP_FILE` to a local file. For an auxiliary file, put it into `data/sources/` or change its `*_URL` key.
- **TLS errors** (`self-signed certificate in certificate chain`): set `EXTRA_CA_CERT` to your proxy's CA bundle.
- **Port 8080 in use**: set `GATEWAY_PORT`.
- **Photon logs `high disk watermark exceeded`**: the host disk is over 90% full. Serving still works. At 95% (OpenSearch flood stage) a new import can fail, so free some disk space.
- **`no tile bounds`**: the PBF header has no bbox and the node scan found no nodes. Set `TILES_BOUNDS`.

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
| Contract check against openapi.yaml | `make contract` (creates `.venv` with jsonschema + PyYAML; also checks the Expose-Headers tokens) |
| Rate-limit probe (NAV-008 AC 13; not against the dev stack) | `make rate-limit-check BASE_URL=...` |
| CORS allowlist check (AC 31) | `make cors-check` |
| Memory and disk (AC 39) | `make stats` |
| Build record (sources, dates, versions) | `make build-info` (prints `data/build-info.json`) |
| PMTiles header and metadata | `make tiles-info` |

`BASE_URL` overrides the gateway URL for `smoke`, `perf` and `contract` (default `http://localhost:8080`).

**Not on the staging host.** These are dev commands. `compose.yaml` has the same project name (`navmn`) as the NAV-008 staging scripts, so `make up/down/restart/rebuild-data/clean-data/cors-check` (or a plain `docker compose up/down`) there would use the dev `.env` and replace the staging gateway. The Makefile refuses those targets while `../infra/staging/.env` exists. On staging, use `infra/staging/bin/nav-compose` and `deploy.sh`, and for data the NAV-006 pipeline: `sudo make -C /opt/nav/backend rebuild [FORCE=1]` (the staging form of `make rebuild-data`), `sudo make -C /opt/nav/backend rollback` and `sudo make -C /opt/nav/backend status` ([RUNBOOK](../infra/staging/RUNBOOK.md) sections 5–6 and §7.3).

## Endpoints (gateway, default `http://localhost:8080`)

Only the gateway publishes a port (`GATEWAY_BIND:GATEWAY_PORT`, default `127.0.0.1:8080`). Valhalla and Photon are reachable only through it.

| Story symbol | Gateway path | Upstream |
|---|---|---|
| `HEALTH` | `GET /health` | answered by nginx: `{"status":"ok"}` |
| `TILES` | `GET/HEAD /tiles/basemap.pmtiles` | static file with HTTP Range, ETag, `Cache-Control: public, max-age=300`, no gzip. Max zoom 14 by default (`TILES_MAXZOOM`); clients overzoom above it. A range starting at or past the end gives a JSON 416 with `Cache-Control: no-store` |
| `ROUTE` | `POST /v1/route` (also `GET /v1/route?json=`) | Valhalla `/route`, forwarded unchanged |
| `SEARCH` | `GET /v1/search` | Photon `/api`, forwarded unchanged |
| `REVERSE` | `GET /v1/reverse` | Photon `/reverse`, forwarded unchanged |

The gateway alone handles CORS. It answers every `OPTIONS` with 204 and strips the upstreams' own
`Access-Control-*` headers. Gateway errors are JSON `{code, message}`:
- 404 `NotFound`, 405 `MethodNotAllowed`, 413 `PayloadTooLarge` (bodies over 256 KB)
- 416 `RangeNotSatisfiable` on `TILES` when the `Range` start is at or beyond the archive size, with `Content-Range: bytes */<size>` (AC 43). Like every gateway response, it carries each `Access-Control-*` header exactly once, so a browser `fetch` sees the 416 instead of a CORS error (ADR-0002 Amendment 2). The 416 carries exactly one `Cache-Control: no-store` and no `Accept-Ranges`, as required by openapi.yaml 0.3.0 and later and ADR-0002 Amendment 3. nginx adds the file's headers before the range filter turns the 200 into a 416, and they survive the internal redirect, so a small njs header filter (`gateway/njs/headers.js`) rewrites them in `@range_not_satisfiable`. njs ships in the pinned nginx image and is used only for this 416 header filter.
- 429 `RateLimited` (openapi.yaml 0.4.0, NAV-008 AC 13) when per-client-IP rate limits are on (staging; off in dev, see "Rate limits and client IP"): `Retry-After: 1`, `Cache-Control: no-store`, and each `Access-Control-*` header once. `Retry-After` is listed in `Access-Control-Expose-Headers` on every response.
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

## Rate limits and client IP (NAV-008)

The gateway can limit requests per client IP with nginx `limit_req` (`gateway/entrypoint/16-rate-limits.sh` writes
`conf.d/01-rate-limits.conf` at start). **Off in dev** (`GATEWAY_RATE_LIMIT=off`); the staging overlay forces it on.

| Key | Dev default | Staging | Meaning |
|---|---|---|---|
| `GATEWAY_RATE_LIMIT` | `off` | `on` (forced) | Enable the limits |
| `GATEWAY_RATE_ROUTE` / `GATEWAY_BURST_ROUTE` | `30r/s` / `60` | same | `/v1/route` per client IP; burst served without delay |
| `GATEWAY_RATE_SEARCH` / `GATEWAY_BURST_SEARCH` | `30r/s` / `60` | same | `/v1/search` and `/v1/reverse` together (one zone) |
| `GATEWAY_REAL_IP_FROM` | empty | Caddy's fixed edge address | Peers whose `X-Forwarded-For` is trusted as the client IP (`real_ip_recursive off`) |

`/health`, `/tiles/basemap.pmtiles` (any method, any rate) and every `OPTIONS` preflight are never limited. With the
staging values, 100 requests/s for 10 s from one IP gives 360 accepted and 640 `429` (measured). Invalid values stop
the gateway at start instead of running without limits. The client IP is only a key in shared memory: it is not in
the access log, and nginx's "limiting requests" lines are logged at `info`, below the `crit` error log level.

Checks: `infra/ci/backend-static-checks.sh` (429 shape, never-limited paths, bad values refused);
`make rate-limit-check BASE_URL=...` (AC 13 probe: 100 r/s bursts, a 10 r/s session, tiles at 200 r/s) and
`make contract BASE_URL=... CONTRACT_ARGS=--rate-limit` (429 against openapi.yaml). Run both only against a gateway
with limits on (staging, or an isolated test gateway), never against the shared dev stack.

## Staging (NAV-008)

| | |
|---|---|
| Base URL | **`https://<staging-host>`**: placeholder until the PO names the company subdomain (D6, AC 23). The real host name lives only in `infra/staging/.env` on the server; it is set in `openapi.yaml` `servers` by the architect and here once known |
| Host | Hostinger VPS KVM 4, Singapore (ADR-0005), Ubuntu 24.04; any KVM VPS works, nothing in `infra/` is provider-specific |
| What differs from dev | Caddy in front (TLS 1.2/1.3, Let's Encrypt, HTTP to HTTPS redirect, no access log), rate limits on, CORS allowlist (never `*`), Geofabrik `mongolia-latest` as the OSM source, `compose.slots.yaml` with the NAV-006 daily rebuild at 19:30 UTC (slots, pointer switch, 0 s downtime) |
| Code and runbook | [`infra/staging/`](../infra/staging/) and [`infra/staging/RUNBOOK.md`](../infra/staging/RUNBOOK.md): bootstrap, deploy by git tag, rebuild, certificates, backups/restore, monitoring, incidents |
| Design | [deployment-staging.md](../docs/architecture/deployment-staging.md), [ADR-0005](../docs/architecture/adr/0005-backend-hosting-staging.md) |

Tester-only and unlisted; not for real users (D26). `data/` is not backed up on staging: it is rebuilt from OSM.

## Daily rebuild pipeline (NAV-006, ADR-0014)

Fresh OSM data every day without downtime: each build goes into a new immutable **slot**, is verified privately on the free **lane** (blue/green: one Valhalla + one Photon each), and goes live with one `rename(2)` of a small pointer file that the gateway reads on every request (`gateway/njs/slot.js`; no nginx reload, no container restart). One-step rollback, a shared lock, disk/memory guards, structured logs and an alert hook. Contract unchanged (`openapi.yaml` 0.5.3).

| File | What |
|---|---|
| [`compose.slots.yaml`](compose.slots.yaml) | slot runtime: `gateway`, private `gateway-verify` (127.0.0.1), lanes `valhalla-/photon-{blue,green}`, builders (profile `build`). Staging base file; **not** used by the dev stack |
| [`pipeline/nav_pipeline.py`](pipeline/nav_pipeline.py) | the pipeline (stdlib Python): `rebuild`, `rollback`, `status`, `checksums`; exit codes in its docstring and RUNBOOK 7.5 |
| [`gateway/njs/slot.js`](gateway/njs/slot.js) | per-request pointer read; dev mode (no `/etc/nginx/slot`) keeps the NAV-001 upstreams |
| [`pipeline/request_loop.py`](pipeline/request_loop.py) | AC 15 request loop (keep-alive + new connections, no retries, ETag log) |
| [`pipeline/test-setup.sh`](pipeline/test-setup.sh), [`test-teardown.sh`](pipeline/test-teardown.sh), [`nav006-test.env.template`](pipeline/nav006-test.env.template) | dev-container test project `navmn-nav006` (D126) |
| [`pipeline/tests/`](pipeline/tests/) | unit tests (`make pipeline-test`): checks 3(a)-(f), source fallback, lock, rollback refusal, alert hook, config guards |

Dev-container test (separate project `navmn-nav006`, ports 18080/18089, root `/var/tmp/nav006-test`; the shared dev stack on :8080 and `data/` are never touched; `data/` is only read: aux files and tools hard-linked, extract and dump copied):
```sh
make nav006-test-setup                 # test root + config /var/tmp/nav006-test/nav006.env
make nav006-up                         # test gateway on 127.0.0.1:18080 (502/404 until the first slot)
make rebuild                           # first slot (about 6 min); later: make rebuild FORCE=1 (same extract)
make status                            # JSON: active/previous slot, data dates, last run, stale
python3 pipeline/request_loop.py --base-url http://127.0.0.1:18080 --duration 400 --out /tmp/loop.jsonl &
make rebuild FORCE=1                   # second slot on the other lane, switch under load
make rollback                          # previous slot back within 60 s
REBUILD_TEST_FAULT=verify make rebuild FORCE=1   # forced failure: never switches (test project only)
make nav006-test-teardown              # 0 containers/networks/volumes, root deleted
```
`make` targets pick the config automatically: `infra/staging/.env` on staging, else `/var/tmp/nav006-test/nav006.env` if it exists, else `NAV_ENV_FILE=...`. The pipeline refuses a Compose project whose containers were created from `compose.yaml` (the dev stack), and test faults only work with `REBUILD_ALLOW_TEST_FAULTS=1` outside `navmn`. Operator guide: [RUNBOOK section 7](../infra/staging/RUNBOOK.md).

## Offline pack publication (NAV-020, ADR-0017)

After a NAV-006 `success` (and with `make pack-publish`), the pack step cuts the offline Mongolia pack from the active slot: `routing.tar` + `search.sqlite` weekly, `basemap.pmtiles` monthly (only together with a weekly cut), each as a deterministic `.gz` under `NAV_DATA_ROOT/packs/mn/<slot>/`, checks it (self-tests, Gate 2 parity of the phone engine against the server), and publishes `packs/mn/manifest.json` with one `rename(2)`. The public gateway serves both (`/packs/`, openapi 0.6.x). The NAV-006 result never depends on it.

| File | What |
|---|---|
| [`pipeline/nav_pack.py`](pipeline/nav_pack.py) | the pack step, `pack-publish`, `pack-status`, the rollback hook (AC 28); results and exit codes in its docstring and RUNBOOK 7A.4 |
| [`pack/search_builder.py`](pack/search_builder.py) | search DB builder v1 (stdlib; runs in `PACK_SEARCH_BUILDER_IMAGE`, network none): schema, NAV-023 normalisation, self-test |
| [`gate2/`](gate2/) | Gate 2 engine recipe (valhalla-mobile 0.6.3 host build + our `driver.cpp`), the AAR `default.json` copy. **Not built in the dev container** (6-12 GB) |
| [`pack/gate2_evidence_driver.py`](pack/gate2_evidence_driver.py) | test-only stand-in (`PACK_GATE2_MODE=evidence`): the server's own Valhalla on both sides, exercises runner and comparator, **not the gate** |
| [`pack/golden-routes.provisional.json`](pack/golden-routes.provisional.json) | provisional copy of the AC 9 golden route set until QA's fixture exists |
| [`scripts/validate_manifest.py`](scripts/validate_manifest.py) | manifest vs `OfflinePackManifest` (jsonschema) |
| [`pipeline/nav020-test-setup.sh`](pipeline/nav020-test-setup.sh), [`nav020-test-teardown.sh`](pipeline/nav020-test-teardown.sh), [`nav020_test.py`](pipeline/nav020_test.py), [`nav020-test.env.template`](pipeline/nav020-test.env.template) | dev-container test project `navmn-nav020` (seeded slots, read loop) |

Dev-container test (project `navmn-nav020`, gateway `127.0.0.1:18190`, root `/var/tmp/nav020-test`; the shared dev stack and `data/` are only read):
```sh
make nav020-test-setup                                   # root + /var/tmp/nav020-test/nav020.env
E=/var/tmp/nav020-test/nav020.env
python3 pipeline/nav020_test.py --env-file $E seed 20261001T000000Z && python3 pipeline/nav020_test.py --env-file $E activate 20261001T000000Z
make pack-publish NAV_ENV_FILE=$E                        # first publication: all three files, one version
make pack-status NAV_ENV_FILE=$E
.venv/bin/python scripts/contract_check.py --base-url http://127.0.0.1:18190   # pack cases included
make pack-test                                           # unit tests (no Docker)
make nav020-test-teardown
```
Operator guide: [RUNBOOK section 7A](../infra/staging/RUNBOOK.md).

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
| OSM extract | `OSM_PBF_URL` (or local `OSM_PBF_FILE`) | Dev: full Mongolia from the geo2day.com mirror (`https://geo2day.com/asia/mongolia.pbf`, dev only). Production: Geofabrik `mongolia-latest` (commented in `.env.example`). Optional small build: BBBike Ulaanbaatar (commented) | 70 MB (Mongolia), 4.4 MB (BBBike UB) | ODbL 1.0, © OpenStreetMap contributors |
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

**Default: all of Mongolia** (PO decision 2026-09-29, NAV-001 AC 3/8, ADR-0002 Amendment 1). Tiles,
routing and search then cover the same area, and all reference points P1–P6 are inside it.

| Source (`OSM_PBF_URL`) | Use | bbox [minLon, minLat, maxLon, maxLat] | Data date |
|---|---|---|---|
| `https://geo2day.com/asia/mongolia.pbf` | **dev default**. A third-party mirror, used because `download.geofabrik.de` is blocked from the dev container | `[81.9257, 39.0189, 120.2728, 53.0383]` (node scan; the header has no bbox) | no replication timestamp in the header; the HTTP `Last-Modified` is recorded |
| `https://download.geofabrik.de/asia/mongolia-latest.osm.pbf` | **production** | from the header | replication timestamp in the header |
| `https://download.bbbike.org/osm/bbbike/UlanBator/UlanBator.osm.pbf` | optional small/fast build (~4.4 MB, cold run 530 s), **not the default** | `[106.8392, 47.8995, 107.0167, 47.9337]` (header) | replication timestamp 2026-09-25T23:00:00Z (file of 2026-09-29) |

`python3 scripts/pbfinfo.py` reads the PBF header (the equivalent of `osmium fileinfo`). The result is in `data/build-info.json` under `osm.bbox`, with `osm.reference_points_inside_bbox` for P1–P6.

**The geo2day mirror is dev only.** Nobody guarantees its availability or integrity, and it publishes no
checksum. `data/build-info.json` records the file's sha256, HTTP `Last-Modified` and bbox, which is enough to
tell which data a dev build used. If the mirror is down, `data-fetch` stops with the URL and HTTP status.
Then set `OSM_PBF_FILE` to a local copy.

**On the Mongolia build:** X1 Erdenet is routable, so out-of-coverage checks (AC 32) use X2 Beijing. The backend
smoke suite and `contract_check.py` do this automatically.

**On the optional BBBike UB build** (about 13 × 4 km): P3 (Zaisan, lat 47.8858) and P6 (Chingeltei ger
district, lat 47.9600) lie outside the box. The P1→P3 route snaps to the southern edge of the box, about 1.4 km
short of Zaisan (AC 13/14 fail). The tile bounds do not cover P3/P6 (AC 10 fails), and P1→P6 "succeeds" by
snapping to the northern edge (AC 19). `build-info` logs a warning listing the uncovered points. Use it only
for quick gateway or pipeline work, not to judge routing.

## Measured on the reference machine (4 CPU, 15 GB RAM, dev container, 2026-09-29; tiles, 416 and check rows re-measured 2026-09-30)

| What | **Mongolia, dev default** (geo2day, 70 MB) | BBBike UB, optional (4.4 MB) | Limit |
|---|---|---|---|
| **Cold first run**: empty `data/`, `make up` until all services are healthy (images already pulled) | **462 s**. Downloads 149 s (~2.5 GB, of which 70 MB is the PBF), then in parallel: graph 22 s, Photon import 33 s, Protomaps jar (Maven, once) 101 s + Planetiler 208 s | 530 s. Downloads 255 s, graph 3 s, Photon 34 s, Maven 106 s, Planetiler 164 s | AC 1: ≤ 30 min |
| **Warm restart**: `docker compose down` then `up -d --wait` | **8.7 s**. Tiles, graph and index all log `reused`, and no download is made | 8.8 s | AC 2: ≤ 120 s |
| **Source switch**: `make rebuild-data` after changing `OSM_PBF_URL`, auxiliary files cached | 336 s (BBBike → Mongolia). Graph 22 s, Photon 33 s, Maven 76 s, Planetiler 229 s | n/a | AC 5 |
| Peak memory during the build (`docker stats`, sum over containers, sampled every 3 s) | 5.5 GB (tiles-build 5.0 GB) | 3.98 GB (tiles-build 3.5 GB) | AC 39: ≤ 12 GB |
| Memory of the running services | gateway 5.7 MB, valhalla 153 MB, photon 554 MB (2026-09-30) | gateway 5 MB, valhalla 97–132 MB, photon 392–785 MB | AC 39: ≤ 6 GB |
| Total size of `data/` | 2.8 GB (2.4 GB of it is cached auxiliary sources; tiles 113 MB) | 2.6 GB | AC 39: ≤ 10 GB |
| PMTiles size (default `TILES_MAXZOOM=14`, PO decision D1 of 2026-09-30) | **117,536,866 bytes** (112.1 MiB; z0–14; 2,159,826 addressed tiles, 442,203 entries, 298,530 unique contents). **Meets the primary AC 12 limit**, so the 400 MB fallback is not needed. For comparison, z0–15 was 243,254,233 bytes (232 MiB, 732,736 unique contents) | 2.6 MB (z0–15, 177 tiles; measured before D1, not rebuilt) | AC 12: max zoom exactly 14, ≤ 209,715,200 bytes (fallback ≤ 419,430,400) |
| **Tiles-only rebuild** after changing `TILES_MAXZOOM` 15 → 14: `make up` (auxiliary files cached; graph and index log `reused`) | 382 s wall time for `make up`, of which Planetiler took 375 s. The gateway kept serving the old archive until the atomic rename. The z15 build took 208 s on 2026-09-29, and the 2026-09-30 run shared the CPU with other checks, so treat 375 s as an upper bound | n/a | |
| Routing graph / Photon index | 64 MB / 27 MB | 3.9 MB / 27 MB | |
| p95 of 20 sequential requests through the gateway: route / search / reverse / tile range | `make perf` on the z14 build: 13 / 28 / 16 / 1.1 ms (z15 build: 13 / 66 / 36 / 1.4 ms). QA `checks.py --group perf` on z15: 19.7 / 43.4 / 18.9 / 1.5 ms | 12 / 70 / 44 / 1.3 ms | AC 35–38: 500 / 300 / 300 / 100 ms |
| `scripts/smoke.py` (`--perf`) | **46 pass, 0 fail** on the z14 build. This adds AC 12 (max zoom exactly 14, size), a z14 tile at each of P1–P6, and 416 `Cache-Control: no-store` on GET and HEAD. QA `make smoke`: 41 pass, 0 fail. QA `checks.py --group full`: 101 pass, 0 fail | 36 pass, 3 fail (AC 10, 13/14: P3/P6 outside the box; measured before the AC 43 checks were added) | AC 40 |
| `scripts/contract_check.py` | **29 conform** on the z14 build (includes the GET and HEAD 416 and the X2 out-of-coverage route) | 27 conform (before the 416 cases were added) | |

Planetiler with `-Xmx3g`, max zoom 14 (the default since 2026-09-30; the earlier rows used 15). The Natural Earth pass takes about 85 s regardless of the
extract size. Photon's `lang=mn` works (AC 27). The Protomaps tiles carry `name` and `name:en` but not
`name:mn` (AC 11, as ADR-0002 expected).

## Privacy and logs
- **Staging TLS proxy (Caddy):** no access log; the runtime log is an include-list of certificate loggers only (`infra/staging/caddy/Caddyfile`).
- **Gateway access log:** one JSON line per request with method, path without the query string, status, bytes and timings. No client IP, query string, body, Origin or User-Agent.
- **Gateway error log:** at `crit` by default (`GATEWAY_ERROR_LOG_LEVEL`), because nginx error lines contain the full request line.
- **Valhalla:** `valhalla_service` logs every HTTP request line. The serve script pipes it through `sed`, which replaces query strings with `?<redacted>`, so `GET /route?json=…` never writes coordinates. POST bodies are never logged. Valhalla 3.9.0 has no `*.logging.long_request` setting any more (ADR-0002 §3.4 assumed it did), so there is nothing to raise.
- **Photon:** logs no queries at INFO.
- **Builders:** log JSON lines with URLs, file names, sizes and durations only.

## Could not be run or verified here
- **Geofabrik (`download.geofabrik.de`) is blocked from the dev container**, where it answers with a 301 loop via a squid proxy. The production URL has therefore never been fetched here. Every Mongolia build used the geo2day.com mirror, which serves the same country extract. Geofabrik PBFs carry a header bbox, so the node scan is not needed there.
- **The BBBike URL returned HTTP 503 all day on 2026-09-29** (it is only the optional small build now). The BBBike runs used the local copy through `OSM_PBF_FILE`. With the URL alone, `data-fetch` exits 1 with `"msg":"download failed","url":"https://download.bbbike.org/…","http_status":"503"` after curl's 3 retries (about 75 s), which is the documented edge-case behaviour. With `OSM_PBF_FILE` set, the build succeeds under `docker run --network none` (AC 4).
- **Ferrostar parsing the route response end to end** is not tested here (no client in NAV-001). Only the OpenAPI schema and the fields in AC 15 are checked.
- **Docker Hub images** (`nginx:1.28`, `mediagis/nominatim`) were not tried, because Docker Hub returns 429.
- **Nominatim** is not part of NAV-001 (ADR-0003).

## Troubleshooting
- **`service "data-fetch" didn't complete successfully`**: run `docker compose logs data-fetch`. The last line names the URL and HTTP status. For the OSM or Photon source, set `OSM_PBF_FILE` / `PHOTON_DUMP_FILE` to a local file. For an auxiliary file, put it into `data/sources/` or change its `*_URL` key.
- **geo2day.com mirror down or changed** (dev default OSM source): download the Mongolia PBF by any other route and set `OSM_PBF_FILE`, or switch `OSM_PBF_URL` to Geofabrik where it is reachable, or to the optional BBBike UB extract for gateway-only work.
- **A web client reports a CORS error on the tiles file**: check the raw response with `curl -s -D - -o /dev/null -H 'Origin: http://localhost:5173' …`. Every `Access-Control-*` header must appear once. `infra/ci/backend-static-checks.sh` guards this for 206 and 416, including the 416's `Cache-Control: no-store`.
- **TLS errors** (`self-signed certificate in certificate chain`): set `EXTRA_CA_CERT` to your proxy's CA bundle.
- **Port 8080 in use**: set `GATEWAY_PORT`.
- **Photon logs `high disk watermark exceeded`**: the host disk is over 90% full. Serving still works. At 95% (OpenSearch flood stage) a new import can fail, so free some disk space.
- **`no tile bounds`**: the PBF header has no bbox and the node scan found no nodes. Set `TILES_BOUNDS`.

# NAV-020 task breakdown: offline pack build and publication (backend)

- **Story:** [NAV-020](../../requirements/stories/NAV-020-offline-pack-build-publication.md) (P1 / standard, D190; delivery slot 1, D197)
- **Design:** [ADR-0017](../adr/0017-offline-mongolia-pack-android.md) §1, §4, §5, §6, §7 and **Amendment A1** (engine pins, publication rules); [ADR-0014](../adr/0014-daily-rebuild-slots-and-pointer-switch.md) (slots, pointer, lock)
- **Contract:** `openapi.yaml` **0.6.1**. The operations are unchanged from 0.6.0; 0.6.1 is documentation only (§0)
- **Owner of this file:** architect. **Date:** 2026-10-04
- **Scope:** `backend/**` and `infra/**` only. Never `mobile/android` (the NAV-005 location-dot change is running there). Never the shared dev stack (`navmn`, `127.0.0.1:8080`)

## 0. Contract check: openapi 0.6.0 against the story
| # | Story text | openapi 0.6.0 | Result |
|---|---|---|---|
| C1 | AC 19: manifest 200 + strong `ETag` + `Cache-Control: no-cache`, 304, 404 (unknown region, nothing published) | `getOfflinePackManifest` 200/304/404/429, `CacheControlNoCache`, `PackRegion` enum `[mn]` | Matches |
| C2 | AC 20: file 200/206/304/404/416, `If-Range`, `immutable`, no `Content-Encoding`, `Content-Length` = `download_bytes` | `getOfflinePackFile` with the same statuses and headers; 416 = `RangeNotSatisfiable` (JSON, `no-store`) | Matches |
| C3 | AC 21: 429 + `Retry-After` on `/packs/`; `/health`, tiles, `OPTIONS` not limited | `RateLimited` lists the "packs" group; 0.6.0 change log says the same | Matches |
| C4 | AC 7: fields, `format`, `licence`, `self_test`, one file per kind, `tiles.version ≤ routing.version` | `OfflinePackManifest`, `OfflinePackFile` | Matches. The example validates against the schema (checked with `jsonschema`, see Handoff). The Photon dump's `data_timestamp` (`2026-09-26T22:59:05.000+00:00`) is a valid `date-time`. The writer normalises it to `…Z` without milliseconds (B8) |
| C5 | AC 2: tiles due (≥ 28 d) while the weekly part is not due (< 7 d) | The schema requires `tiles.version ≤ routing.version` | **Inconsistency in the story's rule.** A tiles-only cut from a newer slot would break the invariant. **Fixed in the design** without a contract change: tiles are cut only together with a weekly cut (ADR-0017 A1 item 6). The BA is asked to align the AC 2 wording |
| C6 | AC 28: a rollback republishes an older manifest | `pack_version` was described only as "identifies the manifest" | **Gap.** A client could ignore a manifest whose `pack_version` is lower than the installed one, which would break NAV-022 AC 25. **Fixed in 0.6.1** (documentation only): `pack_version` and `published_at` can go backwards; clients decide by `sha256` |
| C7 | Traceability | `x-stories: []` on both `packs` operations | **Fixed in 0.6.1:** `[NAV-020, NAV-022]` |

Result: the contract matches the story after the 0.6.1 patch. Backend implements exactly the 0.6.0 operations. No path, status or header was added.

## 1. Design summary for backend

### 1.1 Where the step runs
```mermaid
sequenceDiagram
  participant T as nav-rebuild.timer / make rebuild
  participant P as nav_pipeline.py (NAV-006)
  participant K as pack step (NAV-020)
  participant G as gateway (public, loopback)
  participant E as Gate 2 engine (one-shot container)
  T->>P: rebuild (takes /run/lock/nav-stack.lock)
  P->>P: build, verify, switch, post-switch smoke OK, grace, stop old lane, slot cleanup
  P->>K: result == success → pack step (same process, same lock)
  K->>K: guard (free disk ≥ PACK_MIN_FREE_GB), select (due?), cut, search build, gzip + SHA-256 (from disk)
  K->>K: decompress candidates into packs/.work/<run>/ and self-test them
  K->>E: golden set on the decompressed routing.tar (network none)
  K->>G: the same golden set → the active lane's Valhalla (≤ 5 r/s)
  K->>K: compare (strict). OK → rename <v>.partial → <v>; manifest tmp → rename(2); history; retention
  K-->>P: pack result (published | not due | skipped (…) | failed (…))
  P->>P: finish(0, "success", pack=…): NAV-006 result and exit code unchanged
```
`make pack-publish [FORCE=1] [TILES=1]` runs the same `K` on its own: it takes the same lock and reads only the active slot.

### 1.2 On-disk layout (under `NAV_DATA_ROOT`, the same filesystem as the slots)
```
packs/
  mn/
    manifest.json                     served (no-cache); replaced only by rename(2)
    <slotId>/                         write-once version directory (immutable once renamed)
      routing.tar.gz  search.sqlite.gz  [basemap.pmtiles.gz]
    <slotId>.partial/                 being assembled; deleted by reconcile
    .manifests/<seq>-<pack_version>.json   copies of every published manifest (retention, rollback)
    .history.json                     ordered list of served manifests (seq, pack_version, sha256, published_at, reason)
  .work/<run-id>/                     decompressed candidates, golden-set I/O; deleted at the end and by reconcile
```
- Only `.gz` files are kept (A1 item 8). Raw files exist only in `.work/` during a run.
- Directories are `0755` and files `0644`, so the unprivileged gateway (uid 101) can read them. Dot paths are never served (B13 location regexes).

### 1.3 Results
| Pack result | When | Alert hook | `make pack-publish` exit (proposal) |
|---|---|---|---|
| `published` | manifest renamed | no | 0 |
| `not due` | neither part due, or the version directory already exists | no | 0 |
| `skipped (low disk)` | free < `PACK_MIN_FREE_GB` at start | once | 54 |
| `skipped (not eligible)` | NAV-006 result ≠ `success` (timer path: not logged as a pack run); manual: active slot in `rolled_back`, or `post_switch_pending` set | no | 55 |
| `failed (gate 2)` / `failed (self-test)` / `failed (engine version mismatch)` / `failed (build)` / `failed (interrupted)` | as named | once | 50 / 51 / 52 / 53 / 40 |
| `rollback: republished` / `rollback: no clean manifest` | `make rollback` hook (AC 28) | no / once | (NAV-006 rollback exit codes unchanged) |

The 5x range avoids NAV-006's 0–40. On the timer path the NAV-006 exit code never changes (A1 item 12).

### 1.4 Configuration keys (all in `backend/.env.example` and `infra/staging/.env.example`, one-line comment each)
| Key | Default | Notes |
|---|---|---|
| `PACK_ENABLED` | `0` | `1` in the test env; staging switches it on during the RUNBOOK checklist (AC 33) |
| `PACK_REGION` | `mn` | only value allowed |
| `PACK_WEEKLY_MIN_AGE_DAYS` | `7` | age by slot ID of the last published `routing` |
| `PACK_WEEKDAY` | empty | optional ISO weekday 1–7, evaluated in Asia/Ulaanbaatar |
| `PACK_TILES_MIN_AGE_DAYS` | `28` | checked only in a run that cuts the weekly part (A1 item 6) |
| `PACK_MIN_FREE_GB` | `2` | AC 18 (*BA proposal*) |
| `PACK_RETAIN_MANIFESTS` | `3` | AC 16 |
| `PACK_GZIP_LEVEL` | `6` | deterministic header (A1 item 9) |
| `PACK_SEARCH_BUILDER_IMAGE` | `python:3.14-slim@sha256:<pinned>` | backend pins the digest; 3.14.8 tested here (§3.1) |
| `PACK_SEARCH_BUILDER_VERSION` / `PACK_SEARCH_SCHEMA` | `1` / `1` | AC 6; recorded in the log and in `meta` |
| `PACK_GATE2_IMAGE` | `navmn-gate2:0.6.3` | local tag; the image ID is recorded in each run (AC 35) |
| `PACK_GATE2_VALHALLA_MOBILE_VERSION` | `0.6.3` | AC 13 check against `mobile/android/gradle/libs.versions.toml` |
| `PACK_GATE2_WRAPPER_COMMIT` / `PACK_GATE2_VALHALLA_COMMIT` | `b47ad5a9…c9d8` / `e2f017b1…cf72` | A1 F1; build args of the image recipe |
| `PACK_GATE2_GOLDEN_SET` | QA path in `tests/` | AC 9 (QA fixture) |
| `PACK_GATE2_RATE` | `5` | requests/s to the active lane |
| `PACK_SELF_TEST_ROUTE` | `47.9189,106.9176;49.4867,105.9228;auto` | P1 → Darkhan |
| `PACK_SELF_TEST_QUERY` | `Сүхбаатар` | |
| `PACK_ATTRIBUTION` | `© OpenStreetMap contributors` | |
| `PACK_METHOD_URL` | none committed; `.env.example` shows `https://example.org/osm-navigation/pipeline` | required when `PACK_ENABLED=1` (AC 34: the real value is configuration) |
| `GATEWAY_RATE_PACKS` / `GATEWAY_BURST_PACKS` | `2r/s` / `20` | AC 21; `16-rate-limits.sh` validates them like the others |

Ages below 1 day are accepted only when `NAV_COMPOSE_PROJECT ≠ navmn` (the AC 16 test configuration), following the `REBUILD_TEST_FAULT` pattern.

## 2. Backend task list
Each task lists the files it is expected to touch (backend's choice of names), what it must do, and its acceptance checks. **(AC n)** maps to the story.

**B1. Pack module, configuration and entry points.**
- `backend/pipeline/nav_pack.py` (new; keeps `nav_pipeline.py` from growing), hooks in `nav_pipeline.py`, `backend/Makefile` (`pack-publish`), `.env.example` files.
- Load and validate the §1.4 keys.
- Hook 1: call the pack step from `_rebuild` after the `cleanup` step and before `finish(0, "success")`. Wrap it so that no exception changes the NAV-006 outcome.
- Hook 2: a `pack-publish [--force] [--tiles]` sub-command that takes the same lock. `--tiles` implies `--force`. The scheduled entry point never passes either flag.
- Selection rules:
  - weekly part due when no manifest exists, or the last `routing.version` is ≥ `PACK_WEEKLY_MIN_AGE_DAYS` old (and the weekday matches, if set)
  - tiles due only in a run that cuts the weekly part (A1 item 6)
  - write-once rule (A1 item 7)
- Checks:
  - unit tests for every branch of the selection (no manifest; weekly due and tiles not due; both due; tiles due but weekly not → `not due`; `FORCE`; `TILES`; directory already exists)
  - a second command while the lock is held exits non-zero within ≤ 5 s and names the lock
  - a non-`success` NAV-006 result leaves the manifest unchanged
  - (AC 1, 2, 3, 4)

**B2. Cut and version directory.**
- Read the active slot's `tiles/basemap.pmtiles`, `valhalla/valhalla_tiles.tar` and `sources/photon-dump`, read-only.
- Stream each source into `<slotId>.partial/<file>.gz` (B4) and compute the source SHA-256 on the way.
- Rename the directory to `<slotId>/` only after B6 and B7 pass.
- Checks:
  - decompressed `routing.tar` and `basemap.pmtiles` have the same SHA-256 as the slot files
  - an SHA-256 listing of all pre-existing files under `packs/mn/` is identical before and after a publication
  - after NAV-006 deletes the source slot, the file is still served 200 with the same SHA-256 (it is a separate file, not a link into the slot)
  - (AC 5, 8, 17)

**B3. `search.sqlite` builder v1 (production spec in §3).**
- `backend/pack/search_builder.py`: Python stdlib only. It runs in `PACK_SEARCH_BUILDER_IMAGE` with `--network none`, the dump mounted read-only and only the work directory writable.
- Unit tests run on the host's Python with a small JSONL fixture.
- Checks:
  - the build log records the builder version and `search_schema`
  - the §3 DDL is present
  - **0** characters U+1800–U+18AF in any text column
  - two builds of the same dump give the **same SHA-256** (A1 item 9; also covers NAV-023 AC 5)
  - builds in ≤ 5 min; ≤ 30 MB raw, ≤ 10 MB gzip on the Mongolia dump (NAV-023 AC 4 budget)
  - (AC 6, AC 12 search)

**B4. gzip and SHA-256.**
- Python `gzip` with `filename=""`, `mtime=0` and `compresslevel=PACK_GZIP_LEVEL`. Write in 1 MiB chunks and check the stop flag between chunks (SIGTERM ≤ 60 s).
- `fsync` each file and the directory.
- Then hash **from disk**: `download_sha256` and `download_bytes` of the `.gz`, and `sha256` and `bytes` by streaming decompression of that `.gz`, never from the copy stream.
- Checks:
  - a unit test with a known input gives fixed digests
  - a streaming decompression of each `.gz` yields exactly `bytes` bytes with SHA-256 `sha256`
  - two runs on the same input give the same `.gz` digest
  - (AC 5, AC 12 gzip)

**B5. Gate 2 engine image recipe (§4).**
- `backend/gate2/Dockerfile`, `backend/gate2/driver.cpp`, `backend/gate2/CMakeLists.txt`, `make gate2-image`.
- Build from the two pinned commits with the wrapper's options. No patch to either tree.
- Record the variant (wrapper or upstream-only fallback, A1 item 2), the commits and the image ID in a label and in the build log.
- Checks:
  - the Dockerfile contains no `patch` / `sed` on fetched sources
  - `docker image inspect` shows the commit labels
  - the image routes `PACK_SELF_TEST_ROUTE` on the dev tar with `code: Ok`
  - the image is never `ghcr.io/valhalla/valhalla:*`
  - (AC 35, AC 10)

**B6. Gate 2 runner and comparator.**
- Read the golden set from `PACK_GATE2_GOLDEN_SET`.
- Run one container for the whole set: `--network none --cpus 1 --memory 512m`, the decompressed tar and the generated config mounted read-only (config: the AAR `default.json` of the pinned version + the A1 item 5 overrides; backend keeps a copy of that `default.json` in `backend/gate2/` with its SHA-256).
- Send the same bodies to `http://127.0.0.1:$GATEWAY_PORT/v1/route` at ≤ `PACK_GATE2_RATE` r/s.
- Compare strictly (AC 10):
  - OSRM `code`
  - route count
  - per route: `round(distance)`, `round(duration)`, decoded polyline6 points as integers
  - per step: (`maneuver.type`, `maneuver.modifier`, `maneuver.exit`) and `name`
- Narrative text and voice text are **not** compared: the client replaces them (ADR-0009 §3.1).
- Checks:
  - the golden set passes on the dev tar
  - a forced difference (a test hook that perturbs one response) blocks publication; the log names the request ID, the first differing field and both values; the alert hook is called once; the served manifest is unchanged; the serving slot is not rolled back
  - UB → Beijing gives `NoSegment` on both sides
  - (AC 9, 10, 11)

**B7. Self-tests on the decompressed candidates.**
- `tiles`: parse the PMTiles v3 header from the first 127 decompressed bytes (no raw file on disk):
  - magic and version 3
  - `min_zoom` 0 and `max_zoom` 14
  - bounds contain P1–P6 and X1
- `routing`: the Gate 2 engine routes `self_test.route` with `code: Ok` (part of the B6 container run).
- `search`:
  - `PRAGMA quick_check` = `ok`
  - `meta.search_schema` = configured
  - `self_test.search` returns ≥ 1 row with the §3.6 query semantics
- Any failure ends the step as `failed (self-test)`, exactly as AC 11.
- Checks: one negative test per kind (a truncated tar, a wrong maxzoom, a corrupted DB) (AC 12).

**B8. Manifest writer and atomic publication.**
- Build the manifest:
  - `data_timestamp`: the slot `build-info.json` OSM date for `tiles` and `routing`, and `photon_dump.data_timestamp` for `search`, normalised to `YYYY-MM-DDTHH:MM:SSZ`
  - `format.graph_builder` = `"valhalla " + versions.valhalla`
  - the totals
- Validate the manifest against `OfflinePackManifest` from the openapi file, plus the AC 7 extra rules, before writing.
- Order of operations:
  1. rename the version directory into place and `fsync`
  2. write the copy to `.manifests/`
  3. write `manifest.json.tmp` and `fsync`
  4. ensure its `mtime` differs from the served file's (A1 item 11)
  5. `rename(2)` and `fsync` the directory
  6. append to `.history.json`
- Checks:
  - a schema test
  - a read loop at ≥ 10 r/s through the test gateway during 5 consecutive publications gives 0 partial or invalid responses, and every response equals the old or the new manifest
  - an `ETag` change test for two same-size manifests written within one second
  - (AC 7, 14)

**B9. Interrupt and reconcile.**
- At the start of every pack step (and of `pack-publish`), delete `packs/.work/*`, `packs/mn/*.partial` and stray `manifest.json.tmp*`.
- Re-verify that every file referenced by the served manifest exists with its recorded `download_sha256`. On a mismatch (disk fault), alert once and republish the newest retained manifest that verifies.
- SIGTERM: kill the one-shot containers, delete the partial outputs, and record `failed (interrupted)`.
- Checks: `kill -9` at 6 points (during cut, gzip, search build, Gate 2, after the directory rename, before the manifest rename). After each, the next run starts clean and the served manifest is the last complete one, with every referenced file present and matching (AC 15).

**B10. Retention and disk guard.**
- Guard at start: free < `PACK_MIN_FREE_GB` → `skipped (low disk)`. Log the free and required values, alert once, write nothing.
- After a publication:
  - keep exactly the files referenced by the last `PACK_RETAIN_MANIFESTS` history entries (the current one included)
  - delete other files one by one, then empty version directories
  - log `packs_disk_bytes`
- Checks:
  - AC 16 test: both ages 0 and 4 publications. Files only in publication 1 are gone; every file of publications 2–4 is served 200
  - low-disk test via a configured threshold above the free space
  - (AC 16, 18)

**B11. Rollback hook (AC 28; Open question 1, working assumption (a)).**
- In `rollback()`, right after the pointer switch: if the served manifest references a file whose `version` is the rolled-back slot, republish the newest retained manifest copy with no such file. Use the B8 rename path, a fresh `mtime` and a history entry with reason `rollback`.
- If there is none, leave the manifest unchanged and alert once.
- Checks:
  - republished within ≤ 60 s of the rollback completing
  - `make status` shows it
  - a test with and without a clean candidate
  - (AC 28)

**B12. Status, logs and alerts.**
- `make status` gains a `pack` object:
  - `pack_version`, `published_at`
  - per kind: `version`, `data_timestamp`, `download_bytes`
  - `last_result` with its run ID
  - the Gate 2 engine image ID, commits and `valhalla-mobile` version
- It reads only `state.json` and the manifest (≤ 2 s).
- One JSON log line per sub-step: `select`, `copy`, `search_build`, `gzip`, `checksum`, `gate2`, `self_test`, `manifest`, `cleanup`. Each line has `run_id`, `step`, `result` and `duration_s`.
- No secrets, no client IPs, no coordinates other than P1–P6, X1 and the golden-set points.
- Alert hook (`REBUILD_ALERT_CMD`) exactly once per failed or low-disk pack step, with the run ID, result, sub-step and reason.
- Checks: AC 24 timing; a log scan; an alert count of 1 per failure; 0 alerts for `published` and `not due` (AC 24, 25, 26).

**B13. Gateway `/packs/` location.**
- `backend/gateway/templates/default.conf.template`:
  - `location = /packs/mn/manifest.json`: alias `/srv/packs/mn/manifest.json`, `Cache-Control: no-cache`, `etag on`
  - `location ~ ^/packs/mn/([0-9]{8}T[0-9]{6}Z)/(basemap\.pmtiles|routing\.tar|search\.sqlite)\.gz$`: alias `/srv/packs/mn/$1/$2.gz`, `Cache-Control: public, max-age=31536000, immutable`, `Accept-Ranges` on 200 and 206 (the existing `$http_range` map trick)
  - both: `types {}`, `default_type application/octet-stream` (the manifest: `application/json`), `gzip off`, the CORS snippet, GET/HEAD only
  - 416 → `@range_not_satisfiable` (njs makes it `no-store`), 404 → JSON `@not_found`
  - every other `/packs/…` path falls to `location /` → 404 JSON
  - no `open_file_cache`, so a renamed manifest is picked up on the next request
- `16-rate-limits.sh`: a third zone `nav_packs`, keyed only for `^/packs/` with GET or HEAD (empty key for `OPTIONS` and other paths), `GATEWAY_RATE_PACKS`, `GATEWAY_BURST_PACKS`, and 429 → `@rate_limited` in both locations.
- `compose.slots.yaml`: public gateway mount `${NAV_DATA_ROOT}/packs:/srv/packs:ro`.
- The dev `compose.yaml` gets no mount, so `/packs/` returns 404 there (backward compatible). The shared stack is not restarted by this story.
- Checks:
  - the AC 20 cases: 200 without `Range` (body SHA-256 = `download_sha256`, **no** `Content-Encoding`); 206 with `bytes=<half>-` (concatenation hashes correctly); `If-Range` mismatch → 200 full; start ≥ size → 416 with `bytes */<size>`; `If-None-Match` → 304; bad pattern or retired file → 404
  - AC 19: manifest 200/304/404
  - AC 21: *N* + 1 → 429 with `Retry-After`; `/health`, tiles and `OPTIONS` unaffected; `/v1/*` limits unchanged
  - AC 22: logs contain no client IP
  - `infra/ci/backend-static-checks.sh` still passes

**B14. Contract and smoke scripts.**
- `backend/scripts/contract_check.py`:
  - `getOfflinePackManifest`: 200 (schema), 304, 404 (`/packs/xx/manifest.json`)
  - `getOfflinePackFile`: 200, 206, 304, 404, 416, with the AC 20 header rules (exactly one `Cache-Control`, `immutable` on 200/206, `no-store` on 416, no `Content-Encoding`)
  - with `--rate-limit`, also one 429 on `/packs/`
- `smoke.py` is unchanged and still passes.
- Checks: both exit 0 against the test gateway after a publication (AC 23).

**B15. Engine version check.**
- A read-only check: when `mobile/android/gradle/libs.versions.toml` exists in the deployed checkout and pins `valhalla-mobile`, it must equal `PACK_GATE2_VALHALLA_MOBILE_VERSION`. If not → `failed (engine version mismatch)`.
- Before NAV-021 pins it, log "valhalla-mobile version from configuration (0.6.3), app pin not present".
- A backend unit test covers equal, different and missing.
- Checks: the three cases (AC 13).

**B16. Staging integration (documented and prepared; not executed on a real host here).**
- `infra/staging/RUNBOOK.md`: a NAV-020 section with every AC 33 item, plus:
  - `make gate2-image` is run once per pin change, when ≥ 30 GB is free, and `docker builder prune` afterwards
  - `PACK_ENABLED=1` is switched on only after the first manual `make pack-publish` passes
- `infra/staging/.env.example` and `compose.staging.yaml`: the public gateway mount inherits `compose.slots.yaml`. Caddy stays without `encode` (otherwise a `Content-Encoding` would break AC 20).
- Timer: unchanged. The pack step runs inside `nav-rebuild.service`: `TimeoutStartSec=2h`, start 19:30 UTC + ≤ 15 min jitter. Rebuild about 10 min plus pack ≤ 15 min ends by about 20:15 UTC, before the 21:30 UTC reboot window. SIGTERM is handled within 60 s (`TimeoutStopSec=90s`).
- Disk budget (staging, 200 GB):

  | Item | Size |
  |---|---|
  | Retained pack tree | ≤ 0.4 GB |
  | Per-run work directory | ≤ 0.3 GB |
  | Gate 2 image | about 0.3–0.6 GB |
  | Builder image | about 0.15 GB |
  | One-off Gate 2 build | 6–12 GB transient |

  All of it is far below the NAV-006 guard (`REBUILD_MIN_FREE_GB` 50). `nav-diskcheck` (85 %) is unchanged.
- Checks:
  - RUNBOOK review against AC 33
  - a secret and hostname scan finds 0 (AC 34)
  - `infra/ci/staging-static-checks.sh` passes
  - (AC 27 timing on a real host is recorded later in RUNBOOK §15)

**B17. Dev-container test setup (isolation).**
- `backend/pipeline/nav020-test.env.template` plus the setup and teardown scripts (or extend the NAV-006 ones with a project argument):
  - Compose project `navmn-nav020`
  - gateway `127.0.0.1:18090`, verify `127.0.0.1:18099` (not 8080, and not NAV-006's 18080 / 18089)
  - data root outside the repository (for example `/var/tmp/nav020-test`)
  - aux files hard-linked; the extract and the dump copied
  - `PACK_*` ages 0 for the retention test
- Checks:
  - shared-stack container IDs and `StartedAt` are equal before and after
  - a `/health` probe every ≤ 5 s shows 0 failures
  - at least 2 publications with different weekly versions (AC 31)
  - teardown leaves 0 containers, volumes and networks, and deletes the root; the report gives free disk before, at peak and after, plus the sub-step durations (AC 32)
  - (AC 29, 30, 31, 32)

**Order:**
- B1 → B2 + B4 → B3 (parallel) → B7 → B8 → B9 → B10 → B12 → B13 + B14 → B11 → B15 → B17 → B16.
- B5 starts **first** in parallel, because it has the longest lead time (an external build machine may be needed, §4.4). B6 follows B5.
- Estimate: 9–13 person-days (spike §8 said 8–12; +1 for the Gate 2 host build).

## 3. `search.sqlite` builder v1: production spec (port of spike §2.4)
The spike prototype was throwaway code and is not in the repository. This section is the spec it becomes. NAV-023 owns the schema and the rules: builder v1 already implements NAV-023 AC 1–2, so that NAV-023 extends v1 instead of replacing it. Any change that alters stored keys increments `search_schema`.

### 3.1 Runtime
- Image: `python:3.14-slim` pinned by digest. Measured here on 2026-10-04: Python 3.14.8, SQLite **3.46.1** with `ENABLE_FTS5`, `ENABLE_RTREE` and the trigram tokenizer. Stdlib `compression.zstd` decodes the dump copy (44,251 lines in 0.36 s).
- No pip packages. No network. Input read-only, output to `packs/.work/<run>/search.sqlite`.
- The phone reads the file with `sqlite-bundled` (SQLite 3.50): a newer reader on a 3.46 file, which is compatible.

### 3.2 Input
`sources/photon-dump` of the active slot: Nominatim dump format `0.1.0`, zstd JSONL.
- Line 1: `NominatimDumpFile`. Take `content.data_timestamp` and `database_version`.
- `CountryInfo`: ignored.
- `Place`: `content` is a **list**. 44,249 places / 44,270 entries in the current dump; 19 places have more than one entry.
- Entry keys:
  - always: `place_id`, `osm_key`, `osm_value`, `categories`, `address_type`, `importance`, `country_code`, `centroid`, `bbox`
  - usually: `object_type`, `object_id`, `name{}`, `address{}`
  - sometimes: `postcode`, `housenumber`, `extra`, `geometry` (ignored)

### 3.3 Selection and cleaning
1. Strip Traditional Mongolian script **U+1800–U+18AF** and U+202F from every string, then collapse whitespace and trim. Example: «Халхгол ᠬᠠᠯᠬ᠎ᠠ ᠭᠣᠣᠯ» → «Халхгол».
2. Display name by the label rule: `name:mn` → `name` → `name:en`, after cleaning.
3. Keep an entry if it has a display name, **or** it has `housenumber` and `address.street` (address points).
4. Name variants for indexing: every `name`, `name:*`, `alt_name`, `old_name`, `short_name` and `official_name` value, plus `alt_name:*` and similar. Split on `;`, clean, and remove duplicates.
5. Context fields come from the base-language keys of `address` (keys without `:`), cleaned: `street`, `suburb`, `district`, `city`, `county`, `state`.

### 3.4 Schema (`search_schema` = 1)
```sql
PRAGMA page_size = 4096;  PRAGMA application_id = 1312904781;  -- 0x4E41564D "NAVM"
PRAGMA user_version = 1;                                       -- = search_schema
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
  -- search_schema, builder_version, data_timestamp, source_sha256, sqlite_version, place_rows
  -- (no build time: deterministic output)
CREATE TABLE place (
  id INTEGER PRIMARY KEY,            -- 1..n in the order of 3.5
  osm_type TEXT, osm_id INTEGER,     -- N/W/R, may be NULL (entries without object_type)
  osm_key TEXT NOT NULL, osm_value TEXT NOT NULL, type TEXT NOT NULL,   -- type = address_type
  name TEXT, name_en TEXT,
  housenumber TEXT, street TEXT, postcode TEXT, suburb TEXT, district TEXT, city TEXT, county TEXT, state TEXT,
  country_code TEXT,
  lat REAL NOT NULL, lon REAL NOT NULL,                    -- centroid, rounded to 7 decimals
  ext_w REAL, ext_n REAL, ext_e REAL, ext_s REAL,          -- Photon extent order; NULL for a point bbox
  importance REAL NOT NULL
);
CREATE VIRTUAL TABLE place_fts USING fts5(names, skel, ctx, content='',
  tokenize = 'unicode61 remove_diacritics 2', prefix = '1 2 3');   -- rowid = place.id
CREATE VIRTUAL TABLE place_tri USING fts5(name, content='', tokenize='trigram');  -- folded display name
CREATE TABLE vocab (term TEXT PRIMARY KEY, n INTEGER NOT NULL) WITHOUT ROWID;    -- skeleton tokens, frequency
CREATE VIRTUAL TABLE place_geo USING rtree(id, min_lon, max_lon, min_lat, max_lat); -- point boxes on centroids
```
- `names`: the folded form (NAV-023 Terms) of every name variant, space-separated.
- `skel`: the NAV-023 AC 2 skeleton of every variant, plus the joined-word keys (each pair of adjacent words without the space, in folded and skeleton form).
- `ctx`: the folded context fields.
- The app maps a row to `PhotonFeature` (ADR-0012), so the type-label rules use `osm_key`, `osm_value` and `type` unchanged.

### 3.5 Determinism
- Sort the kept entries by (`osm_type` NULLS LAST, `osm_id`, `place_id`). Insert in that order in one transaction, with `journal_mode=OFF` and `synchronous=OFF` during the build.
- Then run FTS5 `optimize` on both FTS tables, `ANALYZE`, `VACUUM`, and `PRAGMA quick_check`.
- No timestamps or random values anywhere.
- Same dump + same image digest ⇒ same SHA-256 (B3 check).

### 3.6 Normalisation and the server self-test query
- `fold(s)`: lower case, then «ү»→«у», «ө»→«о», «ё»→«е».
- `skeleton(s)`: NAV-023 AC 2, steps 1–4, exactly. Builder v1 was checked against all seven NAV-023 AC 3 vector groups here (scratch script, 0 mismatches).
- When QA's shared vector file exists, the B3 unit tests read it. Until then they use the AC 3 groups from the story text.
- Self-test (B7): run `skeleton(q)`, split into tokens, quote each token, put `*` on the last one, and query `SELECT count(*) FROM place_fts WHERE place_fts MATCH 'skel : ("<t1>" "<t2>"*)'` (syntax checked on SQLite 3.45). The result must be ≥ 1. The full ranking is the app's job (NAV-023).
- Note for NAV-023: Latin spellings that write «ө» / «ү» as "u" ("Khuvsgul", "Ulgii") give a different skeleton (`huvsgul`, `ulgi`) from the Cyrillic (`hovsgol`, `olgi`). The ADR-0012 query-side vowel variants must cover this. The AC 3 vectors have no such group yet (request to the BA and QA in the handoff).

### 3.7 Budgets
The spike measured 20.4 MB raw, 7.0 MB gzip and 7.6 s (with trigram and joined words). Limits: ≤ 30 MB, ≤ 10 MB gzip, ≤ 5 min (NAV-023 AC 4).

## 4. Gate 2 engine: recipe, feasibility, fallbacks

### 4.1 Pins (measured 2026-10-04, ADR-0017 A1 F1)
| Item | Value |
|---|---|
| `valhalla-mobile` tag `0.6.3` | commit `b47ad5a9aa5d907df329bd2a0bfcc9080220c9d8` |
| `src/valhalla` submodule | upstream `valhalla/valhalla` commit `e2f017b16080f49203de245a211b09efab09cf72` = tag **3.6.3** (not a Rallista fork) |
| vcpkg baseline | `f176b58f35a75f9f8f54099cd9df97d2e2793a2e` (`src/vcpkg.json`) |
| CMake options | `ENABLE_TOOLS/DATA_TOOLS/PYTHON_BINDINGS/HTTP/SERVICES/TESTS=OFF`, C++20, Release, `-Wno-deprecated-builtins` |
| Published AAR | SHA-256 `ac6d702371b6a0a4bc19f58d0f03d17a7fa7a89d77e997f0042a55618eb11cde`. ABIs: arm64-v8a, armeabi-v7a, x86, **x86_64**. Built with Android clang 21.0.0. Contains protobuf 4.25.1 |
| On-device config | the AAR's `com/valhalla/valhalla/default.json` + the A1 item 5 overrides |

### 4.2 Recipe (`backend/gate2/`, backend implements)
- Multi-stage `Dockerfile`, build stage:
  - `ubuntu:24.04`, clang 21 from the LLVM apt repository (closest to the AAR's compiler), cmake, ninja, git, pkg-config, zlib
  - `git clone` valhalla-mobile at `b47ad5a…` with `--recurse-submodules`
  - assert that the submodule HEAD is `e2f017b…`
  - vcpkg at the baseline commit; triplet `x64-linux` with `VCPKG_LIBRARY_LINKAGE static`
  - a small top-level `CMakeLists.txt` of ours that sets the vcpkg toolchain and manifest dir to the wrapper's `src/` and does `add_subdirectory(<wrapper>/src)`
  - our `driver.cpp`: reads `{id, body}` lines from stdin, calls `ValhallaActor(config).route(body)`, and writes `{id, response}` or `{id, error}` lines to stdout
- Runtime stage: `ubuntu:24.04` (or distroless), with the static driver and the `default.json` copy. Labels record the commits, the vcpkg baseline, the compiler version and the variant.
- No patch to fetched sources. If the wrapper target does not build on Linux, use the A1 item 2 variant (upstream Valhalla `e2f017b…` only; the driver calls `tyr::actor_t::route` on a 16 MB-stack thread) and label it so.

### 4.3 Can it be built here? No, not in this run.
- The dev container has **4.1–4.7 GB free disk** (it fell during this session because a parallel Android build is running). It has 4 vCPU and about 6 GB `MemAvailable`, and the shared dev stack is running.
- A vcpkg build (protobuf, abseil, Boost subset, lz4) plus Valhalla's library objects plus the toolchain image needs an estimated **6–12 GB** of scratch and **45–90 min** of build time.
- Filling the disk under the running shared stack is not acceptable, so the build was **not attempted**.
- What was verified instead:
  - the tag and submodule commits (`git ls-remote`, a shallow clone of the wrapper without submodules)
  - that the submodule is upstream 3.6.3
  - the AAR's ABIs, compiler string, protobuf version and `default.json`
  - that `default.json` `service_limits` equal the server's values

### 4.4 Where and how to build it, and the fallbacks
1. **Primary:** build the image on a machine with ≥ 20 GB free. This can be the NAV-008 staging VPS (200 GB; once per pin change, then `docker builder prune`) or a CI runner. Move it with `docker save | docker load` (about 0.3–0.6 GB). The dev-container QA run of NAV-020 then loads it. **This needs a host. Today there is none (NAV-008 waits for host details), so this is an open question for the orchestrator/PO.** Freeing disk here would also need a decision: about 1.9 GB of images are reclaimable per `docker system df`, not enough on its own.
2. **Closest equivalent:** the A1 item 2 variant (upstream Valhalla at the same commit, the same options, no wrapper layer). It meets AC 35 (no story change). It needs the same build resources, so it does not remove the host need.
3. **Last resort (needs a story change through the BA and the PO, not recommended):** an interim Gate 2 on `ghcr.io/valhalla/valhalla:3.6.3`. It has the same source commit (F1) but different dependencies and flags. It would be paired with the NAV-021 Gate 1 emulator run on each new `graph_builder`. Use it only if 1 and 2 cannot be done before NAV-022 needs a published pack.
4. **Until the image exists:** B1–B4 and B7–B17 can be built and tested. The B6 comparator and runner are unit-tested with recorded responses. The routing self-test and Gate 2 are reported as **not verified** in QA's report, and `PACK_ENABLED` stays `0` on any real host.

## 5. Dependencies on other agents
| To | Request | Needed by |
|---|---|---|
| qa-engineer | The golden route set fixture (AC 9) as one JSON file in `tests/` holding `{id, body}` with the exact ADR-0009 §2 bodies: P1→P3 car; P1→P6 walk, car ± `exclude_unpaved`, bicycle; P1→P3 `alternates: 2`; reroute `heading: 90`; UB→Darkhan/Erdenet/Zamyn-Üüd/Khovd, Choibalsan→Ölgii car; UB→Beijing; the G1–G9 route requests (from `tests/gpx/nav005/manifest.json` and the recorded route JSON). Also the shared skeleton vector file (NAV-023 AC 3) | B6 (Gate 2), B3 tests |
| business-analyst | NAV-020 AC 2: state that `tiles` is cut only in a run that also cuts the weekly part, and that `TILES=1` implies `FORCE=1` (ADR-0017 A1 item 6). NAV-023 AC 3: add a vector group for Latin «ө»/«ү» as "u" (for example «Хөвсгөл» / "Khuvsgul" / "Hovsgol"), or state that query-side variants cover it | before QA writes the test plan |
| orchestrator / PO | A build host for the Gate 2 image (§4.4 item 1) | B5, B6, and the QA run of AC 10, 12, 35 |

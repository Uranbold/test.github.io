# ADR-0014: Daily data rebuild with immutable slots, blue/green service lanes and a per-request pointer switch

- **Status:** accepted (2026-10-04, architect; ready for backend. Reversible inside NAV-006 until staging installs it)
- **Date:** 2026-10-04
- **Stories:** NAV-006 (AC 40 is this ADR). Touches NAV-008 (staging rebuild path, `deploy.sh`, lock), NAV-001 (gateway, builders), NAV-009 (two-host production, outlook only)
- **Contract:** `docs/architecture/api/openapi.yaml` **unchanged** (stays 0.5.3). No HTTP status or data-version field is added (§9). NAV-006 AC 30 is therefore "not applicable (status is local only)"

## Context
NAV-006 must replace tiles, routing graph and search index every day from the Geofabrik Mongolia extract with **0 failed public requests**, verify the new data privately before any user sees it, switch atomically, keep one-step rollback, and survive interrupts and reboots. The NAV-008 interim rebuild (`infra/staging/bin/nav-rebuild.sh`) rebuilds in place: Valhalla restarts and Photon stops during a re-import, so routing and search have downtime and there is no slot-level rollback (deployment-staging §11).

Constraints that shaped the decision:
- **Runtime stays Docker Compose**, project `navmn` on staging (ADR-0001, ADR-0002, ADR-0005). There is one host with 4 vCPU, 16 GB RAM and 200 GB NVMe. Gateway paths stay the same (ADR-0002).
- **Sizing** (NAV-008 AC 16): cold build peak 5.5 GB RAM, of which tiles-build alone is 5.0 GB. One service set uses less than 1 GB. One data set is about 0.3 GB at z14 (PMTiles 112 MiB, graph 61 MB, Photon index 27 MB, PBF 70 MB). The shared auxiliary cache is about 2.4 GB plus 0.19 GB of tools. The limit is build peak plus serving ≤ 70 % of RAM (11.2 GB).
- **The builders already work on one `/data` tree**: `data-fetch`, `tiles-build`, `valhalla-build`, `photon-import` and `build_info.py` read `$DATA/sources/*` and `$DATA/tools/*` and write `$DATA/{tiles,valhalla,photon}` and `$DATA/build-info.json`. Planetiler resolves the auxiliary files relative to `/data/sources`.
- **The gateway already resolves upstreams per request**: variable `proxy_pass` plus Docker DNS (`resolver 127.0.0.11 valid=10s`). It serves the archive as a static file with nginx's strong `ETag` (`"<mtime hex>-<size hex>"`), and `ETag` is in `Access-Control-Expose-Headers`.
- **Web PMTiles client** `pmtiles` 4.5.0 (`web/package.json`). Its `FetchSource` compares the `ETag` of every range response with the `ETag` of the cached header. On a mismatch it sets `mustReload` (later fetches use `cache: "reload"`), and `getZxy`/`getMetadata` invalidate the header cache and retry once (`web/node_modules/pmtiles/src/index.ts`, `EtagMismatch`).

### Spike evidence (2026-10-04, this container, throwaway nginx `1.27.4-alpine` on `127.0.0.1:18099`, removed afterwards; the shared dev stack was not touched)
Four client threads ran for 14 s at about 50 requests/s each: route-like proxy and PMTiles `Range`, once with keep-alive connections and once with a new connection per request. The data was switched 6 times during the run.

| Switch mechanism | Keep-alive clients (no retry, Python `http.client`) | New connection per request |
|---|---|---|
| Rewrite an `include` file (temp + rename), then `nginx -t && nginx -s reload` | **12 failures** (`RemoteDisconnected`): exactly 1 per reload per keep-alive client. The old workers close idle keep-alive connections, and a client that reuses one fails | 0 failures |
| **njs reads a pointer file per request** (temp + rename), no reload | **0 failures** in 1,349 requests | 0 failures in 1,332 requests |

- The `ETag` changed with the archive (`"6ac1b468-493e0"` → `"6ac1b469-493e1"`) in both runs.
- Cost of the njs lookup: p50 +0.03 ms and p95 +0.05 ms on the proxy path; p95 +0.09 ms on the tiles path (3,000 sequential keep-alive requests each). This is negligible next to NFR-L1 to L3.
- A bind mount whose source is a symlink mounts the symlink's target at container creation (verified with `docker run -v <symlink>:/data`). Repointing the symlink and recreating the container rebinds it.

Browsers, OkHttp and Go's `net/http` (Caddy) usually retry a request that failed on a stale pooled connection. iOS `URLSession` is known to surface `-1005` instead, and the AC 15 loop counts every connection error. So a reload-based switch makes "0 failed requests" depend on client retry behaviour. A pointer read per request avoids the issue: no worker exits and no connection is closed.

## Decision

### 1. Slot layout and naming (AC 40 item 1)
All pipeline data lives under one root, `NAV_DATA_ROOT`, on **one filesystem**, because hard links and atomic renames need that. Staging default: `/var/lib/nav/data`, outside the git checkout, so deploys never touch data.

```
$NAV_DATA_ROOT/
  cache/
    sources/          Planetiler auxiliary files (one copy, ~2.4 GB)          kept across runs
    tools/            photon.jar, protomaps jar, aircompressor (~0.19 GB)    kept across runs
    osm/<sha256>.osm.pbf (+ .json header info, .md5, .last-modified, .source) validated extracts of active/previous/today
    photon-dump/<sha256>.jsonl.zst (+ .last-modified)                         dumps of active/previous/today
    photon-index/<fingerprint>/                                               PRISTINE imported indexes (never served)
  slots/
    <slot-id>/        complete slot: tiles/ valhalla/ photon/ sources/ tools/ build-info.json .slot-complete
    <slot-id>.partial/  being built (never served, never a target; deleted by the next run)
    <slot-id>.failed/   failed verification (kept for diagnosis until the next run deletes it)
  lanes/
    blue  -> ../slots/<slot-id>     symlink: the slot that lane "blue" serves
    green -> ../slots/<slot-id>     symlink: the slot that lane "green" serves
    build -> ../slots/<slot-id>.partial   symlink used by the builder containers
  pointer/
    public/active.json   THE active pointer. Read by the public gateway on every request
    verify/active.json   the candidate pointer. Read only by the private verify gateway
  state.json          history: previous, rolled-back checksums, last download, last run (derived, §6)
  runs/<run-id>.jsonl per-run structured log, kept REBUILD_LOG_RETENTION_DAYS (default 30, ≥ 14)
```

- **Slot ID = run ID** of the run that built it: UTC `YYYYMMDDTHHMMSSZ`, for example `20261004T193412Z`. It sorts by time and is unique per host.
- **A slot is self-contained and immutable once complete.** Its `sources/` and `tools/` hold **hard links** into `cache/` (0 extra bytes). The aux files are never written, so sharing inodes is safe. The existing builder scripts then run unchanged with the slot mounted at `/data`. Its own `sources/osm.pbf` is a hard link to the validated `cache/osm/<sha256>.osm.pbf`. Backend may choose an equivalent mechanism if the builder scripts stay unchanged for the dev stack and the aux cache stays a single copy.
- **Completeness:** builders write into `<slot-id>.partial/`. After `build-info.json` the pipeline writes `.slot-complete` (fsync), then renames the directory to `<slot-id>/` (fsync of `slots/`). Only directories without a suffix and with `.slot-complete` are switch or rollback targets.
- **Photon index:** Photon opens its index read-write (embedded OpenSearch keeps lock and state files in the data directory), so it is never hard-linked between slots and never copied from a live slot. Right after a successful import, and before any Photon process opens it, the pipeline copies the index to `cache/photon-index/<photon fingerprint>/`. A new slot with an unchanged dump gets a copy of that pristine index (`cp -a --reflink=auto`, 27 MB), with no re-import and no dump download (AC 8). If the dump download fails, the active slot's fingerprint is reused and `build-info.json` records the older `data_timestamp`.
- **Retention:** after a successful switch and a passed post-switch check, exactly **2** complete slots remain: active and previous. Everything else under `slots/` is deleted, and so are `cache/osm`, `cache/photon-dump` and `cache/photon-index` entries that neither of them references (AC 22). During a build, 3 slots exist (active, previous, new).
- **Active pointer across a reboot:** `pointer/public/active.json` is the **single source of truth** for what is served. It is written as temp file, fsync, `rename(2)`, fsync of the directory. The public gateway reads it on every request, so after a reboot it serves exactly the recorded slot. It can never point to a partial slot, because the pipeline writes it only after verification. `state.json` is derived. If it disagrees with the pointer (a crash between the two writes), the next run or `make status` logs the difference and corrects `state.json` from the pointer.

Pointer format (no secrets, paths only inside the containers):
```json
{"slot":"20261004T193412Z","lane":"green","valhalla":"valhalla-green:8002","photon":"photon-green:2322",
 "tiles":"/srv/slots/20261004T193412Z/tiles/basemap.pmtiles","switched_at":"2026-10-04T19:49:03Z"}
```

### 2. Service lanes and Compose layout
- **Two fixed service lanes, `blue` and `green`**, each with one Valhalla and one Photon: `valhalla-blue`, `photon-blue`, `valhalla-green`, `photon-green`. "Blue/green" names the **lanes**. The **slots** are the data sets, named by run ID. A lane mounts `lanes/<lane>` at `/data`: Valhalla read-only, Photon read-write (its own slot copy). The mount resolves to one slot when the container is created. Repointing a lane means: atomic symlink replace (`ln -sfn` to a temp name, then `mv -T`), then `docker compose up -d --no-deps --force-recreate valhalla-<lane> photon-<lane>`. Because the Compose configuration never changes, a plain `docker compose up -d` never recreates a lane by accident.
- **New Compose file `backend/compose.slots.yaml`** (standalone, not an overlay). It defines the public `gateway`, a private `gateway-verify`, the 4 lane services, and the builder jobs (Compose profile `build`, `restart: "no"`, `/data` = `lanes/build`). It uses the same pinned images, the same scripts and the same gateway templates as `backend/compose.yaml`. Backend may use `extends` to avoid duplication. `backend/compose.yaml` stays the **NAV-001 dev one-shot stack, unchanged in behaviour**, so the shared dev stack keeps running from it.
- **Staging** = `compose.slots.yaml` + `infra/staging/compose.staging.yaml` (+ the IPv6 overlay). The overlay only adds `caddy` and edge-network settings to `gateway`, which `compose.slots.yaml` also defines.
- **Lanes use `restart: unless-stopped`.** After a reboot, Docker brings back the lanes that were running. A lane stopped after the grace period stays stopped.
- **Builder order** (memory, §6): fetch and validate, then the builders, then the candidate lane starts. Builders never run while two service sets run.

### 3. Switch mechanism (AC 40 item 2, AC 14–17)
- The gateway template gains an njs function (`gateway/njs/slot.js`, next to `headers.js`). Through `js_set`, it supplies `$valhalla_upstream`, `$photon_upstream` and the tiles file path from `/etc/nginx/slot/active.json`. The value is read on **every request** (a few hundred bytes, from the page cache). The location blocks keep their variable `proxy_pass` and use `alias <tiles variable>` with `etag on`.
- **Validation in njs:** the gateway accepts only `valhalla-(blue|green):8002`, `photon-(blue|green):2322` and a tiles path matching `^/srv/slots/[0-9]{8}T[0-9]{6}Z/tiles/basemap\.pmtiles$`. If the pointer is missing, unreadable or invalid in slot mode, it uses the contract's existing errors (`502 UpstreamUnavailable` for route, search and reverse, `404 NotFound` for tiles). It never guesses a slot and never logs anything per request.
- **Dev mode stays as it is:** when no pointer directory is mounted (`backend/compose.yaml`), njs returns the NAV-001 env defaults (`VALHALLA_UPSTREAM`, `PHOTON_UPSTREAM`, `/srv/data/tiles/basemap.pmtiles`).
- **Mounts:** the public gateway mounts `pointer/public` at `/etc/nginx/slot` and `slots/` at `/srv/slots`, both read-only. **Mount the directory, never the file**: a single-file bind mount keeps the old inode after a rename.
- **The switch is one `rename(2)` of `pointer/public/active.json`.** Every request reads either the old or the new file completely, so each request is served wholly by one slot. No nginx reload, no container stop or recreate (AC 14), no closed connection. In-flight requests finish on the old lane, which keeps running (AC 17). The status signal reads the same file, so it shows the new slot at once (≤ 10 s, AC 14).
- **Switch order:**
  1. The candidate lane is up and verified (§4).
  2. Rename the public pointer.
  3. Write `state.json`.
  4. Post-switch check: `smoke.py` against the public URL within ≤ 60 s (AC 21).
  5. Wait for the grace period, `REBUILD_GRACE_SECONDS` ≥ 30 s (AC 17).
  6. `docker compose stop` the old lane. Stop, not rm, so a rollback can `start` it again.
  7. Clean up (§1 retention).
  
  The old lane stops only **after** the post-switch check has passed. An automatic rollback in AC 21 is then just a second pointer rename, with no service start.
- **Rollback (AC 18–19):**
  1. Under the lock, check that `state.json.previous` names a complete, not-rolled-back slot. If not, exit within ≤ 5 s with "no previous good slot".
  2. `docker compose start` the previous slot's lane. Its container still has that slot mounted. If the container is gone, repoint and recreate the lane.
  3. Wait for both health checks.
  4. Rename the pointer.
  5. Mark the slot that was rolled back from as `rolled_back` (its OSM sha256 goes to `state.json.rolled_back_sha256` for AC 20), and set `previous` to `null`.
  6. Wait for the grace period, then stop the other lane.
  
  Budget: Valhalla starts in a few seconds. Photon on a 27 MB index has to start, together with the health wait, inside the ≤ 60 s of AC 18. Backend measures this. If it does not fit, raise it with the orchestrator; do not quietly keep both lanes running.
- **Staging extra (Caddy in front):** nothing changes for Caddy. The gateway is never reloaded, so Caddy's pooled connections to it stay valid.

### 4. Private verification of the new slot (AC 40 item 3, AC 11–13)
- `gateway-verify` is the same image, templates and njs as the public gateway, published only on `127.0.0.1:${NAV_VERIFY_PORT}`. It is not on the edge network and never in Caddy's configuration. It mounts `pointer/verify` and `slots/` read-only, and runs only during verification. Rate limits are off on it (the smoke suite must not trip them). CORS is the same as the public gateway.
- Steps:
  1. Repoint the free lane to the new slot and start it.
  2. Wait for both health checks.
  3. Write `pointer/verify/active.json` and start `gateway-verify`.
  4. Run `smoke.py --base-url http://127.0.0.1:$NAV_VERIFY_PORT` and `contract_check.py --base-url … --spec docs/architecture/api/openapi.yaml`. Both must exit 0.
  5. Reference routes (AC 12): P1→P3 `auto` `mn-MN`, P1→P2 `pedestrian`, P1→P6 `auto` with `exclude_unpaved`, on both the verify gateway and the public gateway (`127.0.0.1:$GATEWAY_PORT`). The relative distance difference must be ≤ `REBUILD_ROUTE_MAX_DEVIATION` (0.20). P1→P6 is compared only when both slots return 200.
  6. Artefact sizes (AC 13): the new slot's PMTiles file, `valhalla/` directory and `photon/` directory are each ≥ `REBUILD_ARTEFACT_MIN_RATIO` (0.90) of the active slot's.
  7. Stop `gateway-verify`.
- **On failure:** stop the candidate lane and `gateway-verify`, rename the slot to `<id>.failed`, leave the public pointer untouched, exit non-zero and call the alert hook (AC 33).
- **First build** (no active slot): steps 5 and 6 and validation checks 3(d) and 3(e) are skipped and logged as `skipped: first build`.

### 5. PMTiles client continuity (AC 40 item 4, AC 16)
- **Mechanism: strong `ETag` change plus the web client's built-in retry.** nginx's `ETag` is `"<mtime>-<size>"` of the file it opens. The old and new archives have different `ETag`s.
- `pmtiles` 4.5.0 sees the mismatch on the first range read after the switch. It invalidates its cached header and directories, re-reads the header with `cache: "reload"`, which also bypasses the browser HTTP cache that `Cache-Control: public, max-age=300` allows, and retries the tile once. A client never combines a header from one archive with tile bytes from another.
- During a rollback the `ETag` changes back to the previous archive's value, which also differs from the current one.
- **Rules for the pipeline:**
  1. Never preserve or set the mtime of a newly built archive (no `cp -p`, `touch -r` or `rsync -t` from another archive).
  2. If a new archive with a different sha256 has the same `(mtime, size)` as the active one, `touch` it before the switch.
  3. An unchanged archive reused by hard link keeps its `ETag`, which is correct because the bytes are identical.
- No contract change: `ETag` is already in the 200/206/HEAD responses and in `Access-Control-Expose-Headers` (`openapi.yaml` 0.5.3). `Cache-Control` stays `public, max-age=300`.
- **MapLibre Native (Android, later iOS)** is not verified here (NAV-006 R6). Its PMTiles source also caches the header. Whether it re-reads the header on an `ETag` change is unknown. This is listed as not verified and requested from mobile-engineer for the next device session (handoff). If Native does not recover, the follow-up options are a versioned archive URL (a contract change through triage) or a client-side reload on a tile decode error. Neither is part of NAV-006.

### 6. Lock, interrupts, state and status (AC 40 item 5, AC 24–29)
- **One kernel `flock`** on `NAV_LOCK_FILE` (default `/run/lock/nav-stack.lock`, the NAV-008 lock). It is shared by `make rebuild`, `make rollback`, the timer, `deploy.sh` and `cert-dry-run.sh`. `nav-rollback-data.sh` is removed, and `nav-rebuild.sh` is removed or becomes a thin wrapper that calls the pipeline. The lock is non-blocking (`-n`): a second command exits within ≤ 5 s with a message that names the lock file (AC 25). `make status` is read-only and takes no lock.
- **The lock descriptor is not inherited by child processes** (close-on-exec; Python `open()` does this by default, bash `exec 9>` does not). After a `kill -9` of the pipeline, a still-running `docker compose run` client therefore cannot keep the lock alive.
- **Every run starts with reconciliation:**
  1. Stop and remove leftover builder and `gateway-verify` containers of this project.
  2. Stop any lane that is neither the pointer's lane nor a lane inside its grace period.
  3. Delete `*.partial` and `*.failed` slots and any slot that is not active or previous.
  4. Correct `state.json` from the pointer.
  
  This covers AC 26 after `kill -9`, a reboot or a power cut: the next run succeeds without manual steps.
- **SIGTERM or SIGINT handler:** stop the builders (`docker compose kill` of the build profile), stop the candidate lane and `gateway-verify`, delete the partial slot, write the run result, release the lock. All of this takes ≤ 60 s, and the unit sets `TimeoutStopSec=90s`. The public pointer is never touched by an interrupt.
- **Guards before any download:** free disk on `NAV_DATA_ROOT` ≥ `REBUILD_MIN_FREE_GB` (staging 50) and `MemAvailable` ≥ `REBUILD_MIN_MEM_AVAILABLE_GB` (6.5). During the build, a check at least every 10 s stops the build and deletes the partial slot when free disk drops below `REBUILD_HARD_FLOOR_FREE_GB` (5) (AC 27–28).
- **`make status`** prints one JSON object within ≤ 2 s. It reads the pointer, `state.json` and the active and previous slots' `build-info.json`, plus on staging `systemctl show nav-rebuild.timer -p NextElapseUSecRealtime`. Minimum shape (AC 29):
  ```json
  {"active":{"slot":"…","lane":"green","built_at":"…","osm_data_date":"…","osm_data_date_source":"replication_timestamp|last_modified","photon_data_timestamp":"…"},
   "previous":{"slot":"…","built_at":"…","osm_data_date":"…","photon_data_timestamp":"…"},
   "last_run":{"run_id":"…","started_at":"…","ended_at":"…","result":"success|skipped: <reason>|failed|rolled back","step":null,"reason":null,"exit_code":0},
   "stale":false,"next_scheduled_run":"…|null"}
  ```

### 7. Memory and disk budget for 3 slots and 2 service sets (AC 40 item 6, AC 27–28)
| Phase | What runs | RAM (staging, measured or derived) | Disk on `NAV_DATA_ROOT` |
|---|---|---|---|
| Steady state | 1 lane + gateway | < 1 GB | cache ~2.6 GB + 2 slots × ~0.3 GB (z14; ~0.45 GB at z15) |
| Build | 1 lane + builders | ≤ 5.5 + 1 = **6.5 GB** (the start guard requires this as `MemAvailable`) | + 1 partial slot ~0.3 GB + Planetiler temp (backend measures; expected ≤ 3 GB) |
| Verify, switch, grace | 2 lanes + gateway + verify gateway | ≤ 2 × 1 + 0.1 ≈ **2.1 GB** (builders have exited) | 3 complete slots ~0.9 GB |
| Rollback | 2 lanes during start and grace | ≈ 2.1 GB | unchanged |
- Peak = 6.5 GB ≤ 11.2 GB (70 % of 16 GB). The two-lane window and the build never overlap, because §2 orders them. `nav-stats-sampler` measures this on the real host (NAV-008 AC 16).
- Disk worst case beyond the cache ≈ 0.9 GB of slots + ~3 GB of temp ≈ 4 GB. The staging guard of 50 GB is far above that; it is the story's value (NAV-008 AC 16) and is kept. The hard floor of 5 GB stops a runaway build.
- **Dev-container test values** (free disk was 8.6 GB on 2026-10-04): `REBUILD_MIN_FREE_GB=4`, `REBUILD_HARD_FLOOR_FREE_GB=1`. These are test-only relaxations, logged on every run and listed in the QA report like the AC 37 relaxations. Staging defaults stay as above.

### 8. Fetch, validation, scheduling, logging and alerting (story sections A, F, G)
- **Source list:** `OSM_SOURCES` is a space-separated, ordered list of `https://` URLs or `file:<absolute path>`. If it is unset, the pipeline uses the existing `OSM_PBF_URL`/`OSM_PBF_FILE`, for backward compatibility. Staging default: Geofabrik only (NAV-006 Open question 1, working assumption (b)).
- **Download rules:**
  - Stall timeout `REBUILD_SOURCE_STALL_SECONDS` (120), for example `curl --speed-time 120 --speed-limit 1`.
  - One retry after `REBUILD_SOURCE_RETRY_DELAY_SECONDS` (30).
  - `.md5` check with one re-read on a mismatch.
  - Descriptive `User-Agent`.
  - **At most one successful download per Asia/Ulaanbaatar calendar day**, recorded in `state.json.last_download` (date, URL, sha256). The pipeline reuses `cache/osm/<sha256>` on later runs that day unless `FORCE=1`.
  - The unchanged check compares Geofabrik's `.md5` with the active slot's recorded md5 **before** downloading. The `.md5` request is not a download.
- **Validation 3(a)–(f):** `pbfinfo.py` is extended to read every blob to the end of the file: BlobHeader and datasize consistency, plus zlib decompression of each blob. The other checks run in this order: coverage of P1–P6 (the coordinates in `smoke.py`), size floor, shrink ratio, not older, freshness. A rejection names the check and the measured value.
- **Overrides** (make variables, recorded in the run log, never set by the timer; the scheduled entry point refuses them):
  - `FORCE=1` (AC 6, AC 20)
  - `ACCEPT_SIZE_DROP=1`, which covers 3(d) and AC 13
  - `ACCEPT_ROUTE_CHANGE=1`, which covers AC 12
- **Scheduler:** keep the unit names `nav-rebuild.service` and `nav-rebuild.timer` (19:30 UTC = 03:30 Asia/Ulaanbaatar, `RandomizedDelaySec=15min`, `Persistent=true`), but point `ExecStart` at the NAV-006 pipeline. There is then **exactly one** scheduled rebuild job, and the Uptime Kuma rebuild heartbeat (26 h) keeps its meaning (AC 23, AC 32, AC 43). The Kuma daily maintenance window around the rebuild (deployment-staging §9) is no longer needed and is removed after installation.
- **Logs:** one JSON line per step with `run_id`, `step`, `result`, `duration_s`, plus a final summary line. Output goes to stdout (journald on staging) and to `runs/<run-id>.jsonl`, deleted after `REBUILD_LOG_RETENTION_DAYS` (30). Logs contain only public source URLs, sizes, durations, slot IDs, and P1–P6 in checks. No push URLs, no alert command output, no client data (AC 31).
- **Alert hook:**
  - `REBUILD_ALERT_CMD` is empty by default. It runs under `timeout ${REBUILD_ALERT_TIMEOUT_SECONDS:-10}` with `NAV_RUN_ID`, `NAV_RUN_RESULT`, `NAV_RUN_STEP` and `NAV_RUN_REASON` in its environment.
  - It is called exactly once per qualifying run. Its exit code is logged; its output is not.
  - It never changes the run's own exit code.
- **Exit codes** (proposed; backend may renumber but must keep them distinct and list them in the runbook):

| Code | Result |
|---|---|
| 0 | `success`, or `skipped: unchanged` with a healthy active slot and fresh data |
| 2 | usage or configuration error (including an override passed to the scheduled entry point) |
| 10 | lock held by another command |
| 11 / 12 / 13 / 14 | `skipped: low disk` / `low memory` / `no valid source` / `source was rolled back` |
| 15 | `skipped: unchanged`, but the active slot failed smoke or its data is stale (> 48 h) |
| 20 / 21 / 22 / 23 | `failed` at validation / build / verification / switch |
| 24 | `rolled back` automatically after the post-switch check (AC 21) |
| 30 / 31 | rollback refused (no previous good slot) / rollback failed |
| 40 | interrupted (SIGTERM/SIGINT); cleanup done |

### 9. HTTP status or data-version field (AC 40 item 8, AC 30): **not added**
- **Why not:**
  - The operator's needs are met locally (`make status`) and by the existing rebuild heartbeat and alert hook.
  - Showing the data date to end users is explicitly out of scope (NAV-006 Open question 3, working assumption (a)).
  - QA can see the switch per request without a new field: the tiles `ETag` changes at the switch, and `make status` shows the active slot.
  - Not adding it avoids exposing build details (tool versions) on a public endpoint without a user need.
- **Revisit when:** NAV-009 needs a remote data-version check across two hosts, or the PO says yes to Open question 3. Then add `GET /v1/data-version` (active data dates, slot ID, tool versions only; no paths or hosts) through triage, with an `openapi.yaml` minor bump and `contract_check.py` coverage.

### 10. Two-host production, NAV-009 (AC 40 item 7)
- Same design on each serving host: slots, lanes, pointer and lock are host-local.
- Build **once** on one host (or a builder VM), then copy the complete slot to the other host with mtimes preserved (`rsync -a`). The archive then has the **same `ETag` on both hosts**: this is the one case where preserving mtime is required, because it is the same bytes.
- Each host verifies the slot locally (§4). Switch the hosts one after the other, each switch being the local pointer rename, and the second only after the first host passes its post-switch check.
- During the window between the two switches, a client behind a load balancer can get archive A on one request and archive B on the next. `pmtiles` then reloads once per flip, so the window is kept short (seconds) and the load balancer uses connection affinity.
- A remote data-version signal (§9) becomes useful at that point.

### 11. Building and verifying in the dev container (D126, AC 34–39)
- Separate Compose project, for example `NAV_COMPOSE_PROJECT=navmn-nav006`; it must not be `navmn`. Public test gateway on `127.0.0.1:18080`, verify gateway on `127.0.0.1:18089`. Its own `NAV_DATA_ROOT` outside `backend/data` and outside git tracking, on the same filesystem. Its own `NAV_LOCK_FILE`.
- **Isolation guard in the pipeline:** it refuses to run when the target project has containers created from `backend/compose.yaml`, that is, when the `com.docker.compose.project.config_files` label does not contain `compose.slots.yaml`. The flag that overrides this exists only for the one-time staging migration (§12). This protects the shared dev stack (project `navmn`, `127.0.0.1:8080`) from any NAV-006 command.
- **Test cache:**
  - Hard links (read-only use) of `backend/data/sources` auxiliary files and `backend/data/tools`.
  - **Copies** (not links) of `backend/data/sources/osm.pbf` and `photon-dump`, because tests truncate or replace them.
  - Never open a hard-linked file for writing, and never `chmod` or `touch` it: it is the shared stack's inode.
  - The shared Photon index in `backend/data/photon` is never read, because a live Photon has it open. The test imports from the copied dump.
- `OSM_SOURCES` in the test configuration lists the local copy first, optionally followed by the geo2day mirror to show the fallback (AC 36). Freshness 3(f) and not-older 3(e) are relaxed only through configuration keys (`REBUILD_MAX_DATA_AGE_HOURS`, `REBUILD_REQUIRE_NOT_OLDER=0`), and every run logs that it is relaxed (AC 37).
- **Fault injection for QA set item 2:**
  - `REBUILD_TEST_FAULT=validate|build|verify|post_switch` is honoured only when `REBUILD_ALLOW_TEST_FAULTS=1` **and** the project name is not `navmn`.
  - The pipeline refuses it in every other case.
  - It is not in `infra/staging/.env.example`.
- **Teardown:** `docker compose -p <test project> down -v --remove-orphans`, then delete `NAV_DATA_ROOT`. The shared stack's container IDs and `StartedAt` times are compared before and after (AC 34).

### 12. One-time staging migration (documented in RUNBOOK, executed in NAV-008 later)
Steps, under the lock:
1. Install the new units, but keep the timer disabled.
2. Move (same filesystem, `mv`) `backend/data/sources` and `backend/data/tools` into `cache/`.
3. Run a first pipeline build with no active slot. The old `compose.yaml` stack keeps serving.
4. Bring up `compose.slots.yaml` + staging overlay with `--remove-orphans`. The **gateway is recreated once**, which is a few seconds of Caddy JSON backstop. This is a deploy, not a data switch, and is accepted like any NAV-008 deploy.
5. Run `smoke.py`.
6. Enable the timer.
7. Remove `nav-rollback-data.sh` and the interim logic.
8. Delete the old `backend/data/{tiles,valhalla,photon}`.

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| **njs pointer file read per request (chosen)** | Atomic (`rename(2)`); no reload, no container change, no closed connection; 0 failures for every client in the spike, keep-alive included; reboot-safe single source of truth; ≤ 0.1 ms per request | Small njs function on the request path (njs is already used, `headers.js`); slot mode differs slightly from dev mode (fallback keeps dev unchanged) |
| Rewrite an nginx `include` + graceful `nginx -s reload` | Standard nginx practice; no per-request code | Spike: 1 failed request per reload per idle keep-alive client without retry (`RemoteDisconnected`). AC 15 counts it. iOS `URLSession` is known to surface it (`-1005`). The pointer and the served config could diverge if a reload fails |
| Flip Docker network aliases (`valhalla` → green container) | No gateway change | Not atomic across Valhalla, Photon and tiles; nginx caches DNS for 10 s; removing the old alias requires a network disconnect that breaks in-flight requests; tiles still need a second mechanism |
| Recreate the gateway or Compose services on new data | Simple | Drops connections and requests (AC 14 forbids stopping or recreating the gateway) |
| Fixed two data directories `blue/` and `green/` | Simple paths | Building into the inactive directory overwrites the rollback target (violates AC 7 and AC 18); no room for active + previous + new |
| A separate L7 switch (HAProxy, Traefik) or Caddy upstream reload | Mature tools | One more component on a single 16 GB host; Caddy exists only on staging, so the dev-container test (AC 35) would not exercise the real switch; not needed at this scale |
| HTTP `GET /v1/data-version` now | Remote freshness check; per-request evidence for QA | No user need yet (Open question 3); exposes build details; contract and test work with no story value now. Revisit in NAV-009 |
| Photon index hard-linked or copied from the live slot | No pristine cache needed | Photon writes into its data directory, so hard links corrupt the active index, and a copy of a live OpenSearch directory can be inconsistent |
| Re-import Photon every run | Simplest | AC 8 forbids re-importing an unchanged dump; extra build time and memory |

## Consequences
- **Easier:**
  - Data changes become a non-event for clients (0 failed requests by construction).
  - Rollback is a pointer rename plus, at most, one lane start.
  - The same commands drive the dev-container test project and staging (AC 24).
  - Production (NAV-009) reuses the design per host.
- **Harder:**
  - A second Compose file to keep in sync with `compose.yaml` (pinned images and scripts are shared, so this is mostly service wiring).
  - The gateway gets one more njs function, which needs a unit-like test (pointer missing, invalid, valid).
  - Disk accounting must count hard-linked cache files once (`du` over all slots in one call, or exclude `sources/` and `tools/`).
- **Unchanged:** `openapi.yaml` (0.5.3), gateway paths, CORS, rate limits, privacy of request logs, image pins, builder scripts (apart from the `pbfinfo.py` full-read mode and the extra `build-info.json` fields of AC 9: slot ID, run ID, OSM source used with sha256, size, md5 and data date, Photon dump sha256 and `data_timestamp`, `TILES_MAXZOOM`).
- **Follow-ups (through triage, not in NAV-006):**
  - MapLibre Native PMTiles behaviour on an `ETag` change (mobile, R6).
  - Auxiliary source refresh (Open question 4). The layout supports it: a new `cache/sources` generation, hard-linked into new slots, with the old generation deleted when no slot references it.
  - Own Nominatim import (Open question 2).
  - `GET /v1/data-version` (§9).
  - Zero-downtime `deploy.sh` for lane image upgrades: start the other lane on the same slot with the new image, verify, rename the pointer.
- **Supersedes:** deployment-staging §11 interim steps 3–5 once NAV-006 is installed on staging. ADR-0002's "Production (NAV-006) replaces the one-shot builders with a scheduled pipeline and blue/green switch" is realised by this ADR, with gateway paths unchanged as promised.

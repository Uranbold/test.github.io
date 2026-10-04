---
id: NAV-006
title: Daily OSM data rebuild pipeline with a blue/green switch (zero downtime, one-step rollback)
phase: 1          # Phase 1 beta, on the critical path (research §10 item 5: rebuild pipelines and blue/green from MVP)
priority: must    # PO "Yes" on 2026-10-04 to the orchestrator's proposal (D125)
size: L           # fetch + validation, slot build, private verification, atomic switch, rollback, guards, scheduler, runbook
needs_design: false
needs_backend: true
needs_mobile: false
status: in-progress  # 2026-10-04. ADR-0014 and backend built; light-QA run 1 passed all six focused items (docs/qa/reports/NAV-006-run1.md), minor findings F1, F2 with backend. Open questions 1–7 decided (D127–D133), plus D134, D135; Open question 8 decided as built (D139). Real-VPS deployment waits for NAV-008 host details (D126)
---

# NAV-006: Daily OSM data rebuild pipeline with a blue/green switch (zero downtime, one-step rollback)

## Story
As a **taxi / delivery driver** (and a **UB commuter by car**), I want **the map, search and routes to include yesterday's OpenStreetMap changes (new and closed roads, new places, fixed names) without the service ever going down while the data is replaced**, so that **I am not sent down a road that no longer exists, I can find places that local mappers just added, and navigation never fails in the middle of a trip because of a data update**.

> **Enabler story.** End users never see the pipeline. They see its effects: data at most about 1 day old (48 h worst case), and no failed tile, search or route request when the data changes. The operator (the named PO-side person, D7) sees a status signal, logs and alerts.

## Context
- **Research.** `docs/osm-navigation-research.md` §10 item 5 (operations: rebuild pipelines, blue/green deploys and monitoring are needed from the MVP) and §5 (Valhalla graph, Photon index and PMTiles are rebuilt from the OSM extract).
- **What exists today.**
  - NAV-001 builds every artefact with one-shot builders (`data-fetch` → `tiles-build`, `valhalla-build`, `photon-import` → `build-info`) into `backend/data/`. `make rebuild-data` stops the gateway, Valhalla and Photon, so the API is down during a rebuild (`backend/Makefile`).
  - NAV-008 staging has an **interim** in-place rebuild (`infra/staging/bin/nav-rebuild.sh`, `nav-rollback-data.sh`, `nav-rebuild.timer` at 19:30 UTC = 03:30 Asia/Ulaanbaatar, `flock` lock, md5 skip-if-unchanged, rollback copy, smoke after the switch). Valhalla restarts and Photon stops during a re-import, so routing and search have measurable downtime (`docs/architecture/deployment-staging.md` §11, "zero-downtime blue/green is NAV-006").
  - `data-fetch` resolves **one** configured OSM source (`OSM_PBF_URL` or local `OSM_PBF_FILE`). It has **no automatic fallback** to a second source today. The geo2day.com mirror is the dev default and is "dev only" (`backend/README.md`: no guaranteed availability or integrity, no replication timestamp in the header).
  - The **Photon index is imported from the GraphHopper country dump** (ADR-0003), published about weekly, not from our OSM extract. Our own Nominatim is the ADR-0003 "production direction" and is not part of this story (Open question 2).
  - `data/build-info.json` records sources, the OSM replication timestamp, the Photon dump date, artefact markers and versions. It is **not** exposed over HTTP. `GET /health` answers without contacting any upstream (openapi `getHealth`).
- **Sizing (NAV-008 AC 16, sizing baseline):** cold build peak **5.5 GB** RAM (tiles-build alone 5.0 GB); running services **< 1 GB**; artefacts per data set about **0.4 GB** (PMTiles 243 MB at z15, smaller at z14 per D1; routing graph 64 MB; Photon index 27 MB; OSM PBF about 70 MB); shared auxiliary cache about **2.5 GB**. Staging target **16 GB RAM / ~100 GB SSD** (the Hostinger KVM 4 has 200 GB NVMe, D25). NAV-008 AC 16 limit: build peak + serving ≤ **70 %** of RAM (≤ 11.2 GB on 16 GB).
- **PO decisions of 2026-10-04** (`decisions.md` D125, D126; PO answer "Yes", relayed by the orchestrator):
  - D125: priority **must**, Phase 1 beta, on the critical path.
  - D126: the PO has no VPS details yet (NAV-008 still waits for them), so NAV-006 is **built and verified in the dev container** on a **separate Compose project and slot directories**, while the shared dev stack at `http://127.0.0.1:8080` keeps serving. **Light-QA mode:** QA writes the test plan and runs only the focused set in AC 38. Deployment to the real VPS is documented in `infra/staging/RUNBOOK.md`, not executed.
- **PO decisions of 2026-10-04 on the open questions** (`decisions.md` D127–D135; PO answer "All as recommended", relayed by the orchestrator):
  - D127: ordered source list; staging and production default **Geofabrik only**; the geo2day mirror or a local file only in dev and test (Open question 1).
  - D128: keep the weekly GraphHopper Photon dump; our own Nominatim import is a separate later item through triage (Open question 2).
  - D129: the data date is shown to operators and QA only, not to end users (Open question 3).
  - D130: the *BA proposal* guard values are accepted as starting values, tuned after 2 weeks of staging runs through a change request (Open question 5).
  - D131: launch coverage is **all of Mongolia** (`mongolia-latest`) (Open question 6; also backlog owed decision 2 and NAV-008 Open question 5).
  - D132: rebuild at **03:30 Asia/Ulaanbaatar** (Open question 7).
  - D133: keep the manual auxiliary refresh `make rebuild REFRESH_AUX=1`, no automatic refresh (Open question 4).
  - D134: after an automatic rollback the same extract checksum is skipped until a newer extract arrives, unless `FORCE=1` (AC 20).
  - D135: a manual `make rollback` that leaves stale data active does not alert; the next scheduled run does (AC 32).
- **Runtime stays Docker Compose** (ADR-0001, ADR-0002), project `navmn` on staging. The gateway paths in `openapi.yaml` stay the same (ADR-0002: "Production (NAV-006) replaces the one-shot builders with a scheduled pipeline and blue/green switch. The gateway paths stay the same").
- **Who decides what.** The architect writes an ADR for the slot layout, the switch mechanism, PMTiles client continuity (AC 16) and private verification of the inactive slot, and changes `openapi.yaml` **only** if an HTTP status or data-version field is added (AC 30). This story fixes the observable behaviour, the limits and the checks. It does not prescribe nginx upstream reload vs symlink.

### Terms used in this story
| Term | Meaning here |
|---|---|
| **Slot** | One complete, self-contained data set: PMTiles, Valhalla graph, Photon index, the resolved OSM input and its `build-info.json`, plus a completeness marker. The ADR fixes the directory layout and naming |
| **Active slot** ("blue" in the request) | The slot the public gateway serves |
| **Inactive / new slot** ("green") | The slot being built and verified. It never receives public traffic before the switch |
| **Previous slot** | The last slot that was active before the current one. It is the one-step rollback target |
| **Run** | One execution of the pipeline (timer or `make rebuild`), with a unique run ID |
| **Data date** | The OSM replication timestamp of the slot's extract (or the HTTP `Last-Modified` when the PBF header has none), plus the Photon dump `data_timestamp` |

## Acceptance criteria

Numbers marked *BA proposal* are defaults the backend implements as configuration keys with these values. **The PO accepted them as starting values on 2026-10-04 (D130).** They are tuned after 2 weeks of staging runs on the NAV-008 VPS, through a change request.

### A. Fetch and validate the extract
1. **Given** staging is configured with `https://download.geofabrik.de/asia/mongolia-latest.osm.pbf` as the source, **When** runs happen on one calendar day (Asia/Ulaanbaatar), **Then**:
   - the extract is downloaded **at most once** per day per host when that download succeeded and passed validation (manual `make rebuild` without `FORCE=1` included; the Geofabrik `.md5` request does not count)
   - every request sends a descriptive `User-Agent` (as `nav-rebuild.sh` does today)
   - when the source publishes `<url>.md5`, the downloaded file's MD5 equals it. If it differs, the `.md5` is read once more (Geofabrik may publish between the two requests), and a second mismatch fails validation
2. **Given** an **ordered source list** in configuration (the primary source plus zero or more fallbacks, each an `https://` URL or a local file), **When** a source fails (HTTP status other than 200, no data for **≥ 120 s**, or any AC 3 check fails), **Then**:
   - that source is retried once after **≥ 30 s**, then the next source in the list is tried
   - the run log and the slot's `build-info.json` name the source that was actually used
   - if every source fails, the run ends as **"skipped: no valid source"**: nothing is built, nothing switches, and the alert hook is called (AC 32) on every such run, not only after 48 h ([D139](../decisions.md))

   **The staging and production default list contains Geofabrik only** (D127). If it fails, the day is skipped, the old data keeps serving, and the stale alert fires once the data is more than 48 h old (AC 29, AC 32). The geo2day mirror or a local file may be listed **only in dev and test configurations**, such as the test configuration in this container (AC 36).
3. **Given** a downloaded or local extract, **When** it is validated, **Then** it is accepted only if **all** of these hold, and a rejection names the failed check and the measured value:
   - **(a) readable to the end:** it parses as an OSM PBF with a valid header, and a block-level read reaches the end of the file without error. A truncated file or an HTML error page fails
   - **(b) coverage:** its bounding box contains all six NAV-001 reference points P1–P6
   - **(c) size floor:** it is at least **40 MB** (*BA proposal*; the Mongolia extract is about 70 MB)
   - **(d) no large shrink:** it is at least **90 %** of the size of the extract in the active slot (*BA proposal*). The first build, with no active slot, skips this check
   - **(e) not older:** its data date is not older than the active slot's data date
   - **(f) fresh:** its data date is at most **48 hours** old at validation time (NAV-008 AC 15). The limit is a configuration key (AC 37)
4. **Given** the extract is replaced by a copy truncated to **50 %** of its bytes, or by an HTML page saved under the extract's name, **When** a run starts, **Then** validation fails at check 3(a), no builder starts, the active slot is unchanged, and the run exits non-zero.
5. **Given** a valid extract that fails check 3(d) because of a legitimate change (for example a deliberate configuration change), **When** the operator runs `make rebuild` with an explicit one-time override (name set by the ADR or backend, for example `ACCEPT_SIZE_DROP=1`), **Then** the size check is skipped for that run only, and the run log records the override, the old size and the new size. The scheduled timer never sets the override.
6. **Given** the extract's checksum equals the checksum of the active slot's extract, **When** a scheduled run or `make rebuild` without `FORCE=1` starts, **Then**:
   - no slot is built, and the run is recorded as **"skipped: unchanged"**
   - the active slot is still checked: `backend/scripts/smoke.py` against the serving gateway exits **0**, and the data age is checked (AC 29)
   - the status signal (AC 29) shows the new run
   - on staging, the success heartbeat is sent only when both checks pass
   
   `make rebuild FORCE=1` builds a new slot even when the extract is unchanged.

### B. Build into a new slot while the active slot keeps serving
7. **Given** a slot is active, **When** a run builds, **Then** every artefact (PMTiles, Valhalla graph, Photon index, `build-info.json`) is written **only** into a new slot. A checksum listing of the active slot and of the previous slot, taken before and after the build, is identical.
8. **Given** a run builds a new slot, **When** the Photon dump is handled, **Then**:
   - if the dump's checksum equals the active slot's dump, the new slot gets the same index **without downloading the dump again that day and without re-importing it** (copy, hard link or reference, as the ADR decides)
   - if the dump changed, it is imported into the new slot
   - if the dump download fails, the new slot reuses the active slot's index, the run continues, and the log and `build-info.json` record the older dump date
   - in every case the active Photon keeps answering search and reverse during the whole build (**0** failed search requests in the AC 15 loop)
9. **Given** a completed slot, **When** its `build-info.json` is read, **Then** it contains at least:
   - the slot ID and the run ID
   - `built_at`
   - the OSM source actually used, its SHA-256, its size in bytes and its data date
   - the Photon dump source, its SHA-256 and its `data_timestamp`
   - the tool versions (as today) and the tile settings used (`TILES_MAXZOOM`)
10. **Given** staging (4 vCPU, auxiliary cache present), **When** a scheduled run builds a new slot from a changed extract, **Then** the time from the start of the run to the end of the switch (AC 14) is **≤ 30 minutes** (NAV-008 AC 15 budget). In the dev container the duration of each step is recorded, with no pass or fail threshold (AC 39).

### C. Verify the new slot before any switch
11. **Given** a new slot is built, **When** it is verified, **Then**:
    - its own services (routing, search, tiles) are started and reached through a **non-public** endpoint: bound to `127.0.0.1` or an internal Compose network, never the public port, and never through Caddy
    - `backend/scripts/smoke.py --base-url <new-slot endpoint>` exits **0**
    - `backend/scripts/contract_check.py` against `docs/architecture/api/openapi.yaml` exits **0** for the same endpoint
    
    Any failure means no switch (section H).
12. **Given** the new slot passes AC 11, **When** the NAV-001 reference checks are compared with the active slot, **Then** each of these reference routes, computed on both slots, differs in distance by **≤ 20 %** (*BA proposal*):
    - **P1 → P3** car (`auto`, `mn-MN`)
    - **P1 → P2** pedestrian
    - **P1 → P6** car with `exclude_unpaved` (ger district, unpaved roads), only when both slots return a route
    
    A larger difference fails verification, and the log names the route and both distances. The first build has nothing to compare with and skips this check. The AC 5 override, or a separate one-time override, can accept it for one manual run.
13. **Given** the new slot passes AC 11, **When** its artefact sizes are compared with the active slot, **Then** the PMTiles archive, the Valhalla graph and the Photon index are each **≥ 90 %** of the active slot's size (*BA proposal*), or verification fails. The first build skips this check, and the AC 5 override also covers it.

### D. Atomic switch with zero failed requests
14. **Given** the new slot passed section C, **When** the switch runs, **Then**:
    - all new public requests for tiles, `/v1/route`, `/v1/search` and `/v1/reverse` go to the new slot through **one** atomic gateway change (mechanism per the ADR, for example a graceful nginx reload after an upstream or symlink change)
    - the status signal (AC 29) shows the new slot as active within **≤ 10 s** of the switch starting
    - no container of the public gateway is stopped or recreated for the switch
15. **Given** a request loop runs against the public gateway at **≥ 10 requests/s** in total (mixing `GET /health`, `POST /v1/route` P1→P2 `auto`, `GET /v1/search` «Сүхбаатар» and "Sukhbaatar", `GET /v1/reverse` at P1, and PMTiles `Range` requests at z14 over UB), from **≥ 60 s** before the switch until **≥ 60 s** after it, **When** the switch happens, **Then**:
    - **0** requests fail. A failure is a connection error, no response within **5 s**, or a status other than 200 (206 for range requests)
    - the p95 latency in the window from 10 s before to 30 s after the switch is **≤ 2 ×** the p95 of the 60 s before the switch
    
    The loop's summary (request count per endpoint, failures, p95 values) goes into the QA report.
16. **Given** a PMTiles client has read the archive header before the switch (the `pmtiles` JS library version used by `web/`), **When** it requests **≥ 20** tiles over UB after the switch, **Then** **0** tiles fail to decode. Either the client detects that the archive changed and reads the header again (for example through an `ETag` change), or it keeps getting a consistent archive. The ADR states how. MapLibre Native (Android/iOS) behaviour is listed as not verified in the dev container.
17. **Given** the switch has finished, **When** the previous slot's services are no longer needed, **Then** they keep running for a grace period of **≥ 30 s** (*BA proposal*), so requests already in flight finish, and are then stopped to free memory. The previous slot's data stays on disk as the rollback target.

### E. Rollback and retention
18. **Given** a previous slot exists, **When** the operator runs `make rollback`, **Then**:
    - the gateway serves the previous slot within **≤ 60 s**, including starting its services
    - **0** requests fail in the AC 15 loop during the rollback
    - the status signal shows the previous slot and its data date as active
    - the slot it rolled back from is marked **rolled back**. It is not a rollback target, so a second `make rollback` refuses with "no previous good slot" until a new slot has been switched in
19. **Given** no previous good slot exists (first build, or right after a rollback), **When** `make rollback` runs, **Then** it exits non-zero within **≤ 5 s** with a message saying so, and nothing changes.
20. **Given** a rolled-back slot was built from extract checksum *X*, **When** a later scheduled run finds the same checksum *X*, **Then** it does not build it again: the run ends as **"skipped: source was rolled back"**, and the alert hook is called. A newer extract is built normally. `make rebuild FORCE=1` overrides this. This applies in the same way after the automatic rollback in AC 21 (D134).
21. **Given** a switch has succeeded, **When** the gateway has served the new slot for **≤ 60 s**, **Then** `smoke.py` runs once against the public gateway. If it fails, the pipeline rolls back automatically as in AC 18, marks the new slot rolled back, exits non-zero and calls the alert hook.
22. **Given** a switch has succeeded and the AC 21 check passed, **When** cleanup runs, **Then**:
    - exactly **2** complete slots remain on disk: active and previous
    - older slots, failed slots and partial slot directories are deleted
    - the shared auxiliary cache (about 2.5 GB) is kept
    - the run log records the disk used by each remaining slot and the free disk after cleanup
    
    During a build, 3 slots may exist at once (active, previous, new). The disk guard (AC 27) accounts for that.

### F. Triggers, lock and guards
23. **Given** the pipeline is installed on staging, **When** the scheduler is inspected, **Then**:
    - one daily run starts at **03:30 Asia/Ulaanbaatar** (19:30 UTC; Mongolia has no daylight saving time; D132), with a random delay of **≤ 15 min**, and missed runs are caught up after host downtime (the systemd timer from `infra/staging`, or cron if the ADR picks it)
    - **exactly one** scheduled rebuild job is enabled. The NAV-008 interim in-place rebuild timer is replaced, not left running beside it
24. **Given** the pipeline, **When** the operator uses the manual commands, **Then**:
    - `make rebuild`, `make rollback` and `make status` exist and are documented in `infra/staging/RUNBOOK.md`
    - `make rebuild` goes through the same lock, guards, validation, verification and switch as the timer
    - exit code **0** means success or "skipped: unchanged". Every guard skip and every failure exits non-zero, with distinct codes listed in the runbook
    - every command takes its Compose project name, slot root and gateway port from configuration, so the same commands drive the test project in AC 35 and `navmn` on staging
    - `make rebuild REFRESH_AUX=1` re-downloads the shared auxiliary sources (about 2.5 GB) for that run only and builds a new slot even when the extract is unchanged (it implies `FORCE=1`). If the run fails, the previous auxiliary cache is kept. It goes through the same lock, guards, validation, verification and switch. The scheduled timer never sets it, and there is no automatic auxiliary refresh (D133)
25. **Given** a run holds the lock, **When** a second `make rebuild`, the timer, `make rollback` or the NAV-008 `deploy.sh` starts, **Then**:
    - the second command exits non-zero within **≤ 5 s** with a message naming the lock
    - the running build continues and completes, and its result is the same as without the second command
    - the lock is released when its holder exits, including after `kill -9`, so the next run can start (a kernel `flock` or equivalent; no stale lock file blocks it)
26. **Given** a run is interrupted (SIGTERM such as `systemctl stop` or Ctrl-C, `kill -9`, or a host reboot or power cut), **When** it ends or the host comes back, **Then**:
    - the active slot serves throughout, and after a reboot the gateway serves the slot recorded as active, never a partial one
    - a partial slot is never switched to, and is deleted at the latest by the next run
    - the next run succeeds without manual steps
    - after SIGTERM, cleanup finishes within **≤ 60 s**, so the operator can then run `make rollback`
27. **Given** the free disk on the slot filesystem is below the configured minimum when a run starts (staging default **50 GB**, NAV-008 AC 16; it must cover a third slot plus the build's temporary files), **When** the run starts, **Then** it ends before any download as **"skipped: low disk"**, logs the free and required values, calls the alert hook, and changes nothing. If the free disk falls below a hard floor of **5 GB** (*BA proposal*) during the build, the build stops, the partial slot is deleted, and nothing switches.
28. **Given** the memory available to new processes (`MemAvailable`) is below the configured minimum when a run starts (default **6.5 GB** = the 5.5 GB build peak plus 1 GB margin, *BA proposal*), **When** the run starts, **Then** it ends as **"skipped: low memory"**, logs both values, calls the alert hook and changes nothing. On staging, the measured peak of build plus serving services, sampled every **≤ 3 s** (`nav-stats-sampler`), is **≤ 70 %** of RAM (≤ 11.2 GB on 16 GB), including the window in which both slots' services run (AC 17).

### G. Status, logs and alerting
29. **Given** the pipeline has run at least once, **When** the operator runs `make status`, **Then** within **≤ 2 s** it prints JSON with at least:
    - the active slot ID and its data dates (OSM replication timestamp, Photon dump `data_timestamp`) and `built_at`
    - the previous slot ID and its data dates, or `null`
    - the last run: run ID, start, end, result (`success`, `skipped: <reason>`, `failed`, `rolled back`), the failed step and reason
    - `stale: true` when the active OSM data date is more than **48 h** old
    - the next scheduled run time (on staging)
30. **Given** the architect decides whether an HTTP status or data-version signal is added (ADR), **When** it is added, **Then**:
    - it is defined in `docs/architecture/api/openapi.yaml` (version bump) before the backend implements it
    - it returns only the active data dates, the slot ID and the tool versions: no paths, hostnames, IPs or run logs
    - `contract_check.py` covers it
    
    If it is not added, this AC is recorded as "not applicable (status is local only)". Showing the data date to end users in the apps is **not** part of this story: operators and QA only (D129).
31. **Given** any run, **When** its logs are read, **Then**:
    - there is one structured JSON line per step (fetch, validate, build, verify, switch, post-switch check, cleanup) with run ID, step, result and duration in seconds, plus a final summary line
    - logs go to the systemd journal on staging, and to a file in the test project
    - they are kept for **≥ 14 days**
    - they contain no secrets or push tokens, no client IP addresses and no coordinates from user requests (NAV-008 AC 14). The reference points P1–P6 used by checks are allowed
32. **Given** a run ends as `failed`, `rolled back`, or skipped for a guard reason (no valid source, low disk, low memory, source was rolled back), or the active data is stale, **When** it ends, **Then** the configured alert hook is called **exactly once** for that run with the run ID, result, step and reason. The hook:
    - is a placeholder command from configuration, empty by default (no-op)
    - never fails or blocks the pipeline (timeout **≤ 10 s**)
    
    On staging, the existing Uptime Kuma rebuild heartbeat (deployment-staging §9) is sent only after `success` or a healthy "skipped: unchanged", so a missed heartbeat alerts (26 h interval).

    A manual `make rollback` is not a run. When it leaves stale data active, it does **not** call the alert hook. The next scheduled run alerts, as "skipped: source was rolled back" (AC 20) or through the stale check (D135).

### H. Failure handling: a failed build never switches
33. **Given** a failure is injected at any one of these stages:
    - validation (AC 4 truncated extract)
    - build (a builder exits non-zero)
    - verification (`smoke.py` or `contract_check.py` fails against the new slot)
    
    **When** the run ends, **Then**:
    - nothing switches: the status signal shows the same active slot and data date as before the run
    - **0** requests fail in an AC 15 loop running against the public gateway during the whole run
    - the new slot's services are stopped, and the new slot is deleted or marked `failed`. A `failed` slot is never a switch or rollback target and is deleted by the next run
    - the run exits non-zero and calls the alert hook once (AC 32)

### I. Build and verify in the dev container (D126)
34. **Given** the shared dev stack (Compose project `navmn`, `http://127.0.0.1:8080`) is running, **When** the whole NAV-006 build and QA run takes place, **Then**:
    - none of its containers is stopped, restarted, recreated or rebuilt: the container IDs and `StartedAt` times are identical before and after
    - a probe of `http://127.0.0.1:8080/health` every **≤ 5 s** for the whole run records **0** failures
    - files under the shared stack's `backend/data/` are only read (the cached extract may be copied or hard-linked), never changed or deleted
35. **Given** the test setup, **When** it is inspected, **Then** it uses a Compose project name other than `navmn`, its own slot root outside the shared stack's `backend/data/`, and a second gateway instance bound to `127.0.0.1` on a port other than 8080.
36. **Given** the test setup, **When** it runs, **Then** it uses the cached Mongolia extract already on disk under `backend/data/`, or a small extract that contains P1–P6, through the source list (AC 2). A Geofabrik download is **not** needed to pass. If one is tried and blocked, the log shows the fallback.
37. **Given** the cached extract is older than 48 h, **When** the test configuration relaxes the freshness check 3(f) and the "not older" check 3(e) where a test repeats a build from the same file, **Then** the relaxation is set only through configuration, the run log states it on every run, and the QA report lists it. Staging defaults keep both checks on.
38. **Given** light-QA mode (D126), **When** QA reports, **Then** the report contains exactly this focused set, each with its evidence (commands, logs, loop summaries):
    1. one full rebuild into a new slot and switch, with the AC 15 request loop: **0** failed requests (AC 7, 11, 14, 15, 22)
    2. one forced-failure build: no switch (AC 33; the verification-stage failure is recommended because it tests the last gate before the switch)
    3. one `make rollback` with the AC 15 loop: **0** failed requests (AC 18)
    4. the lock test (AC 25)
    5. `smoke.py` and `contract_check.py` against the test gateway before and after the switch: exit **0**
    6. the AC 34 isolation evidence
    
    Every other AC is shown by review, by the backend's own tests, or listed as not verified with the reason.
39. **Given** the test run is finished, **When** cleanup is done, **Then**:
    - the test Compose project has **0** containers, **0** volumes and **0** networks left
    - the test slot directories are deleted
    - free disk is within **200 MB** of the value before the test (committed files and kept logs excluded)
    - the report gives free disk before the run, the peak disk used during it, free disk after cleanup, the peak memory, and the duration of each step

### J. ADR, runbook and configuration
40. **Given** the architect's ADR for NAV-006, **When** it is reviewed before the backend builds the switch, **Then** it decides and explains:
    - the slot layout and naming, and how the active pointer survives a reboot
    - the switch mechanism and why it drops no requests
    - how the new slot is verified without public exposure
    - PMTiles client continuity (AC 16)
    - the lock and how it is shared with `deploy.sh` and any remaining NAV-008 scripts
    - the memory and disk budget for 3 slots and 2 service sets
    - how the design extends to the two-host production in NAV-009
    - whether an HTTP status field is added (AC 30)
41. **Given** the backend deliverables, **When** `infra/staging/RUNBOOK.md` is read, **Then** it has a NAV-006 section that covers:
    - installing the timer
    - `make rebuild` / `rollback` / `status` with examples
    - how to read the status JSON
    - every exit code and skip reason with the operator action
    - manual recovery after an interrupted run
    - the guard values and how to change them
    - how to change the source list
    - the manual auxiliary refresh `make rebuild REFRESH_AUX=1` (D133)
    - a step-by-step checklist for deploying NAV-006 on the real NAV-008 VPS (not executed in this story), including disabling the interim rebuild timer
    
    It contains no hostnames, IP addresses or secrets.
42. **Given** the deliverables, **When** the repository is inspected, **Then**:
    - every new configuration key is in the relevant `.env.example` (`infra/staging/.env.example` and/or `backend/.env.example`) with a one-line comment and the staging default
    - a secret scan finds **0** secrets
    - no file contains a hostname or IP address of a real host
43. **Given** NAV-006 is installed on staging, **When** the rebuild paths are listed, **Then** only **one** rebuild path can change served data: the interim in-place rebuild (`nav-rebuild.sh`, `nav-rollback-data.sh`) is removed, or reduced to a wrapper around the NAV-006 pipeline. `docs/architecture/deployment-staging.md` §11 and `RUNBOOK.md` §7 describe the same path. NAV-008 AC 15 "downtime measured" is then expected to record **0 s**.

## Edge cases
- **No network / international link degraded** (Geofabrik is in Germany; from a Mongolian production host, NAV-009, the route goes through Russia or China): every source fails, the run is "skipped: no valid source", the old data keeps serving, the hook fires on each such run, and after 48 h the status shows `stale: true` and the stale alert fires (AC 2, AC 29, AC 32; D139).
- **Geofabrik publishes late or not at all on a day:** "skipped: unchanged" is normal and not an alert, until the data is more than 48 h old (AC 6, AC 29).
- **Geofabrik replaces the file during the download:** the `.md5` is read once more (AC 1).
- **Truncated download or HTML error page saved as PBF:** check 3(a) (AC 3, AC 4).
- **Mass deletion or a broken edit in OSM:** a shrink of more than 10 % is caught by check 3(d). Damage to the reference routes P1→P3, P1→P2 and P1→P6 is caught by AC 12. Smaller vandalism (a renamed street, one deleted bridge elsewhere) is **not** caught and reaches users within about 24 h (risk R3). The fix is `make rollback` plus an OSM correction by a human mapper (never automatic OSM edits).
- **Large legitimate growth** (for example a building import): passes, because growth never trips 3(d). Watch the AC 27/28 guards.
- **Deliberate configuration change** (for example `TILES_MAXZOOM` 15 → 14, D1): the PMTiles archive shrinks by more than 10 % and AC 13 fails by design. The operator uses the one-time override (AC 5).
- **GraphHopper Photon dump unavailable or not updated:** the new slot reuses the active index (AC 8). Search may then be up to about a week behind the map and routing (R4).
- **Driver mid-navigation during the switch** (UB commuter, taxi driver): the server keeps no navigation session. A reroute just after the switch is answered from the new slot, and its geometry may differ slightly from the original route. No request fails (AC 15).
- **Map open in a browser or app during the switch:** PMTiles clients cache the archive header. AC 16 requires 0 decode errors for the web client. Native apps are not verified here (R6).
- **Off-route reroute storm at the switch moment:** covered by the loop in AC 15 (≥ 10 requests/s is about the reroute rate of a few hundred active drivers; production load is a NAV-009 concern).
- **Host reboot or UB winter power cut during a build** (UB winter peak electricity load): AC 26. The night window (03:30) is also the lowest-traffic time in UB.
- **Disk fills during the build:** the hard floor in AC 27 stops the build and deletes the partial slot.
- **Two runs at once** (a timer run and a manual run, or a deploy): AC 25.
- **Operator wants to roll back during a build:** the lock refuses (AC 25). The operator stops the build (cleanup ≤ 60 s, AC 26) and then runs `make rollback`.
- **Rollback, then the same bad extract comes back the next night:** AC 20.
- **Unpaved roads and countryside:** `surface`/`tracktype` changes flow into the graph like any other edit. AC 12 checks the P1→P6 ger-district route with `exclude_unpaved`. No countryside reference route exists yet (NAV-001 has only UB points).
- **Cyrillic/Latin search:** «Сүхбаатар» and "Sukhbaatar" are checked on the new slot before the switch (smoke AC 21/24 through AC 11) and in the switch loop (AC 15).
- **No search result for a new place:** expected until the GraphHopper dump includes it (R4). Not a pipeline failure.
- **GPS loss:** not applicable (client concern, NAV-005).
- **Time zone:** schedules are written in UTC and documented as Asia/Ulaanbaatar local time. Mongolia has no daylight saving time, so 19:30 UTC is always 03:30 local time.

## Data dependencies & risks
| # | Risk | Impact | Mitigation / owner |
|---|---|---|---|
| R1 | **Geofabrik blocked or unreachable** (blocked from the dev container; international link outages; NAV-008 R1) | No fresh data. Old data keeps serving and goes stale after 48 h | Source list with retries (AC 2), stale alert (AC 29, AC 32). Real-host reachability is NAV-008 AC 15. Backend, operator |
| R2 | **Mirror integrity** (the geo2day.com mirror is third-party, has no replication timestamp, and nobody guarantees its content) | A fallback could serve an unknown or manipulated extract | Staging and production use Geofabrik only; the mirror and local files are for dev and test only (D127). Validation (AC 3) checks structure, coverage and size, not authenticity. PO |
| R3 | **OSM vandalism or broken edits reach users within about 24 h** | Wrong routes or missing roads for a day | Size guard (3(d)), reference-route deviation (AC 12), one-step rollback (AC 18), rollback memory (AC 20). Small damage is not detected automatically. Operator, human OSM mappers |
| R4 | **Photon index date differs from tiles and routing** (weekly GraphHopper dump, ADR-0003; NAV-008 R8, NAV-003 R6, NAV-004 R10) | A new place can be on the map and routable but not searchable for up to about a week | Recorded in `build-info.json` and the status (AC 9, AC 29). The GraphHopper dump stays; our own Nominatim import is a separate draft item through triage (D128). Architect, PO |
| R5 | **Fresh data is not better data.** Address coverage (`addr:*`, khoroo, ger plots), `maxspeed`, `turn:lanes`, `name:mn` / `name:en` gaps (NAV-001 R6–R10) stay the same | ETAs, search and lane hints stay limited. The upside: fixes by local mappers appear the next day | Tracked in the backlog's data-quality risks. A mapping campaign goes through triage lane `osm-data`. BA, PO |
| R6 | **PMTiles clients cache the archive header**; MapLibre Native behaviour on archive change is unknown | Garbled or missing tiles after a switch in open sessions | AC 16 for web. ADR states the mechanism (ETag or versioned archive). Native behaviour checked on a device later (NAV-005 / NAV-015). Architect, mobile |
| R7 | **Memory and disk on staging** (shared-vCPU VPS, NAV-008 R12): 3 slots plus 2 service sets during the switch | Build or switch fails, or the host swaps | Guards (AC 27, AC 28), measured on the real host. Backend, operator |
| R8 | **The dev container is not staging** (no systemd timer, cached and old data, different CPU and disk) | Test results do not prove the timer, the real timings or the Geofabrik path | AC 37 relaxations listed in the report. Real-host checks in NAV-008 (AC 15–16) and the RUNBOOK checklist (AC 41). QA, operator |
| R9 | **Licence and attribution** | None new: ODbL attribution «© OpenStreetMap contributors» is unchanged on every map screen (CLAUDE.md rule 8) | No action |

## Out of scope
- Deploying NAV-006 on the real staging VPS (NAV-008: host details still pending from the PO). This story only documents it in `infra/staging/RUNBOOK.md` (AC 41).
- Production with two hosts, cross-host switching and HA (NAV-009).
- Our own Nominatim import or Photon update mode (ADR-0003 production direction). A separate draft item through triage (D128).
- Minutely or hourly OSM diffs (replication updates). Daily full rebuilds only.
- **Automatic** (scheduled) refresh of the auxiliary Planetiler sources (Natural Earth, water and land polygons, landcover, QRank). The manual `make rebuild REFRESH_AUX=1` is in scope (AC 24, D133).
- Showing the data date to end users in the web or mobile apps (D129). Later, through triage, if testers ask.
- A real alert channel beyond the placeholder hook and the existing staging heartbeat.
- Any change to gateway API paths, or to Valhalla, Photon or Planetiler versions (image pins stay; upgrades follow the NAV-008 deploy path).
- Traffic data (Phase 3).

## Open questions
**Open questions 1–7 were decided on 2026-10-04** (PO "All as recommended", `decisions.md` D127–D133). Each chose the BA recommendation, so the AC already followed them. The options are kept below for the record. **Open question 8 was decided on 2026-10-04** (PO "All as recommended", D139): option (a), keep as built. All open questions are now decided.

1. **Decided 2026-10-04: (b) (D127).** **Fallback sources for the OSM extract on staging and production.** Options:
   - (a) Geofabrik only. If it fails, skip the day, keep serving the old data, alert after 48 h
   - (b) build the ordered source list (AC 2), with Geofabrik only as the staging default; the geo2day mirror or a local file only in dev and test
   - (c) (b) plus the geo2day mirror as an automatic fallback on staging
   - (d) (b) plus another fallback the PO names (for example a copy published by ICT Group or another partner)

   *Recommendation: (b).* The mechanism is needed anyway to test in the dev container, and one missed day costs little because the 48 h freshness limit allows it. An unverified third-party mirror on staging (c) adds an integrity risk (R2). **Working assumption in the AC: (b).**
2. **Decided 2026-10-04: (a) (D128); the Nominatim item is a draft backlog row.** **Search data source.** Options:
   - (a) keep the GraphHopper Photon dump (weekly) in NAV-006, and raise our own Nominatim import as a separate item through triage
   - (b) add our own Nominatim import to NAV-006 now (search becomes as fresh as the map; more RAM, disk and build time; makes the story larger)

   *Recommendation: (a).* It keeps NAV-006 on the critical path small. ADR-0006 R1 (düüreg in the search context line) is also a candidate reason for the later Nominatim item. **Working assumption: (a).**
3. **Decided 2026-10-04: (a) now, (b) later if testers ask (D129).** **Show the data date to end users** (for example in the attribution area or settings of the web demo and the apps). Options:
   - (a) not now: the date is visible to the operator and QA only
   - (b) yes, as a follow-up through triage (needs UX, glossary terms and an HTTP field per AC 30)

   *Recommendation: (a) now, (b) later if testers ask "how fresh is the map?".* **Working assumption: (a).**
4. **Decided 2026-10-04: (a), manual `make rebuild REFRESH_AUX=1` only (D133); now in AC 24 and AC 41.** **Refresh of the auxiliary sources** (about 2.5 GB, deployment-staging §11 lists this under NAV-006). Options:
   - (a) a manual flag only (`make rebuild` with an auxiliary-refresh option), documented in the runbook
   - (b) (a) plus an automatic monthly refresh
   - (c) leave it out entirely

   *Recommendation: (a) in this story, (b) later.* These sources change rarely, and a refresh re-downloads about 2.5 GB. **Working assumption: Out of scope, unless the architect finds (a) trivial.**
5. **Decided 2026-10-04: accepted as starting values (D130).** **Guard defaults** marked *BA proposal*: 90 % size floors (AC 3(d), AC 13), 20 % reference-route deviation (AC 12), 40 MB minimum extract, 6.5 GB memory, 5 GB hard disk floor, 30 s grace. *Recommendation: accept them as starting values and tune them after 2 weeks of staging runs, through a change request.*
6. **Decided 2026-10-04: all of Mongolia, Geofabrik `mongolia-latest` (D131).** **Launch coverage** (backlog owed decision 2: UB first or all of Mongolia) and **NAV-008 Open question 5** (staging coverage). *Working assumption:* Geofabrik `mongolia-latest`, as on staging. The pipeline does not depend on the answer: coverage is just the configured source and the P1–P6 check.
7. **Decided 2026-10-04: keep 03:30 (D132).** **Rebuild time window.** *Working assumption:* 03:30 Asia/Ulaanbaatar, as the interim NAV-008 timer. Options: keep it; or move it (for example 05:00) if night-shift taxi traffic proves heavier than expected.
8. **Decided 2026-10-04: (a), keep as built (D139); AC 2, AC 32 and the code unchanged.** **When does "every source failed" alert?** (added 2026-10-04 with D127). The D127 wording says "if it fails, skip the day, keep old data, alert after 48 h". AC 2 and AC 32 (and the built pipeline, exit 13) also call the placeholder alert hook on **every** "skipped: no valid source" run, and the staging heartbeat goes missing after 26 h. Options:
   - (a) keep the AC as built: the hook fires on every "skipped: no valid source" run, and the stale alert fires after 48 h
   - (b) call the hook for "skipped: no valid source" only once the active data is more than 48 h old (a change to AC 2, AC 32 and the backend)

   *Recommendation: (a).* The hook is a no-op until a real alert channel is configured, and an early signal gives the operator a day to act before the data goes stale. **Working assumption: (a), AC unchanged.**

## Traceability
| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC1–AC3 | — | — | source list and validation step (backend, TBD); `backend/scripts/data-fetch.sh`, `pbfinfo.py` (existing) | unit or script tests (backend); AC 4 run in QA set item 2 if the validation-stage failure is chosen | D125, D126; D127 (source list, Geofabrik only on staging and production); D131 (coverage); D130 (3(c), 3(d)); D139 (Open question 8, hook on every "no valid source" run) |
| AC4 | — | — | validation step (backend, TBD) | truncated-file test (backend test or QA) | |
| AC5–AC6 | — | — | pipeline (backend, TBD) | review or backend test | |
| AC7–AC10 | — | — | slot build (backend, TBD); `build_info.py` (extend) | QA set item 1 (checksum listing, `build-info.json`) | |
| AC11 | — | all operations via `smoke.py`, `contract_check.py` | private slot endpoint (backend, TBD) | QA set items 1 and 5 | |
| AC12–AC13 | — | `postRoute` | verification step (backend, TBD) | backend test or review | D130 |
| AC14–AC15 | — | `getHealth`, `postRoute`, `search`, `reverse`, `getBasemapPmtiles` | switch (backend, per ADR) | QA set item 1 (request loop) | |
| AC16 | — | `getBasemapPmtiles` | gateway tile serving (backend, per ADR) | PMTiles continuity script (QA, if in scope of the light set; otherwise review) | R6 |
| AC17 | — | — | switch (backend) | review | D130 |
| AC18–AC19 | — | all (loop) | `make rollback` (backend, TBD) | QA set item 3 | |
| AC20–AC22 | — | `getHealth` via `smoke.py` | pipeline (backend, TBD) | review or backend test; AC 22 disk listing in QA set item 1 | D134 (AC 20 after the AC 21 automatic rollback) |
| AC23 | — | — | `infra/staging/systemd/` (backend) | review (no systemd in the dev container) | D132 |
| AC24 | — | — | Makefile targets (backend); `REFRESH_AUX=1` in `backend/Makefile` and `backend/pipeline/nav_pipeline.py` | QA set items 1–4 use them; `REFRESH_AUX=1` by backend test or review (not in the light-QA set) | D133 |
| AC25 | — | — | lock (backend) | QA set item 4 | |
| AC26 | — | — | interrupt handling (backend) | backend test or review | |
| AC27–AC28 | — | — | guards (backend) | backend test or review; AC 28 staging measurement in NAV-008 | NAV-008 AC 16; D130 |
| AC29 | — | — | `make status` (backend) | QA set items 1–3 read it | |
| AC30 | — | new status operation only if the ADR adds it (architect) | `openapi.yaml` (architect, conditional) | `contract_check.py` (conditional) | D129 (no end-user data date; ADR-0014: not applicable) |
| AC31–AC32 | — | — | logging and alert hook (backend) | review; hook call checked in QA set item 2; no hook on a stale `make rollback` seen in QA run 1 (report §5 observation) | D135; D139 (Open question 8) |
| AC33 | — | all (loop) | failure handling (backend) | QA set item 2 | |
| AC34–AC39 | — | `getHealth` (shared-stack probe) | test configuration (backend, QA) | QA report (`docs/qa/`) | D126 |
| AC40 | — | — | NAV-006 ADR (architect, TBD) | ADR review | |
| AC41–AC43 | — | — | `infra/staging/RUNBOOK.md`, `.env.example`, deployment-staging §11 (architect) | review, secret scan | NAV-008 AC 15, AC 21; D133 (AC 41 auxiliary refresh) |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-04 | Feature request NAV-006 (PO "Yes" to the orchestrator's proposal, relayed by the orchestrator; `decisions.md` D125, D126) | Story written from the backlog draft row: 43 AC in sections A–J (fetch and validation, slot build, private verification, atomic switch with a zero-failure request loop, rollback and retention, triggers/lock/guards, status/logs/alerting, failure handling, dev-container build with light QA, ADR/runbook/config). Priority must, Phase 1 (D125). Light-QA set and dev-container isolation from D126. Seven non-blocking open questions with working assumptions. Status `ready` | Daily fresh OSM data with no downtime is on the Phase 1 beta critical path. The NAV-008 interim rebuild has routing and search downtime and no slot-level rollback |
| 2026-10-04 | PO answers to the NAV-006 open questions ("All as recommended" in chat, relayed by the orchestrator; `decisions.md` D127–D135) | Open questions 1–7 marked decided (D127–D133). AC 2: staging **and production** default Geofabrik only, mirror or local file only in dev and test (D127). AC intro: *BA proposal* guard values accepted as starting values, tuned after 2 weeks of staging runs (D130). AC 20: also applies after the AC 21 automatic rollback (D134). AC 23: D132 reference. AC 24: new bullet for the manual `make rebuild REFRESH_AUX=1` (D133). AC 30: data date operators and QA only (D129). AC 32: a manual `make rollback` that leaves stale data does not alert; the next scheduled run does (D135). AC 41: the runbook covers the auxiliary refresh. Context, R2, R4, Out of scope and Traceability updated. New non-blocking Open question 8 (per-run hook on "no valid source" vs "alert after 48 h"). Status `ready` → `in-progress` (ADR-0014, backend and light-QA run 1 already done) | All recommendations were already the working assumptions, and D133–D135 match what is built (`RUNBOOK.md` §7, QA report NAV-006 run 1), so no test assertion and no code change follows from them |
| 2026-10-04 | PO answer "All as recommended" in chat, item 4, relayed by the orchestrator (`decisions.md` D139) | **Open question 8 marked decided: option (a), keep as built.** The placeholder alert hook fires on every "skipped: no valid source" run, and the stale-data alert fires once the active data is more than 48 h old. AC 2 (reference added, behaviour unchanged), Edge cases (no-network bullet now names both alerts), front matter status comment, Open questions intro and Traceability (AC1–AC3, AC31–AC32) updated. **No AC behaviour, code or test change** | The recommendation was the working assumption and matches the built pipeline (exit 13) and QA run 1. D139 clarifies the "alert after 48 h" wording of D127, which stays unchanged |

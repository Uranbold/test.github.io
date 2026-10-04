---
id: NAV-020
title: Offline Mongolia pack build and publication (NAV-006 extension) with per-file versions, Gate 2 routing parity, retention and static /packs/ serving
phase: 1
priority: must          # P1 / standard, feature, PO-confirmed 2026-10-04 (D190); launch-blocking (D159). BA MoSCoW mapping P1 → must
size: L                 # pack-publish step, search DB builder run, gzip and checksums, manifest, Gate 2, self-tests, retention, /packs/ location, contract check, runbook
needs_design: false
needs_backend: true     # backend/** and infra/** only
needs_mobile: false     # the app side is NAV-022 (download), NAV-021 (routing), NAV-023 (search)
status: ready           # 2026-10-04. Delivery slot 1, starts now in parallel with the NAV-005 location-dot change (D197). Non-blocking open questions only
---

# NAV-020: Offline Mongolia pack build and publication

## Story
As an **intercity / countryside driver** (and a **taxi / delivery driver** who often has weak or no signal), I want **the server to publish a complete, checked offline copy of the Mongolia map, routing graph and search index every week (the map every month), cut from the same data the online service has already verified**, so that **the app can download it once and keep it fresh, and my offline routes, searches and map match what the online service would show for that data date**.

> **Enabler story.** End users never see this pipeline step. They see its effect through NAV-022 (download and update), NAV-021 (on-device routing) and NAV-023 (offline search). The operator (D7) sees the published pack in `make status`, logs and alerts.

## Context
- **Request and decisions.** Offline is a launch requirement for the real Android app ([D156–D159](../decisions.md)). Spike `docs/architecture/spikes/offline-android.md` §9 item 1; [ADR-0017](../../architecture/adr/0017-offline-mongolia-pack-android.md) §1, §4 (Gate 2), §5 (server), §6 (contract), §7 (licences), accepted 2026-10-04. Contract `openapi.yaml` **0.6.0** (`getOfflinePackManifest`, `getOfflinePackFile`). Triage log 2026-10-04 row "Offline spike follow-up §9 item 1": feature, P1 / standard ([D190](../decisions.md)); it **absorbs** the NAV-006 part of item 7 (pack-publish step, retention, disk guard) and **Gate 2** of the Valhalla version coupling.
- **PO decisions that apply:** all of Mongolia, one country pack ([D158](../decisions.md)); downloaded in the app, not bundled ([D160](../decisions.md)); **map tiles monthly, routing graph and search index weekly** ([D166](../decisions.md), amends D161 for the map file); **full-file updates at launch**, deltas measured later ([D167](../decisions.md), spike parked as P3, [D196](../decisions.md)); **map detail z0–14**, the same archive as online ([D170](../decisions.md)); delivery order ([D197](../decisions.md)): this story runs **now, in parallel** with the NAV-005 location-dot change, backend and infra only.
- **Constraints carried from review (not PO decisions):** the routing parity gate tests **the shipped `valhalla-mobile` code**: here a host build of the **same Rallista fork commit** that the pinned AAR was built from, never the upstream Valhalla Docker image of the same version (ADR-0017 §4).
- **What exists today.** NAV-006 builds immutable, verified slots and switches them with a pointer rename (ADR-0014, built; light-QA run 1 passed). `backend/` has no `/packs/` location and no pack-publish code. NAV-006 is **in progress** (real-VPS deployment pending), so **its AC are not changed** by this story; this story only adds a step after the NAV-006 switch, and NAV-006 gets a change-log cross-reference.
- **Search DB boundary (triage 2026-10-04).** The search DB **schema, normalisation tables and test vectors are owned by [NAV-023](NAV-023-offline-search-reverse.md)**. This story runs the pinned builder and publishes the file. Until NAV-023 delivers its builder, this story publishes a `search.sqlite` built with **builder v1** (the spike prototype's schema re-implemented in `backend/`), which must pass the AC 12 self-test. Search quality is gated in NAV-023, not here.
- **Environment.** There is still no staging VPS (NAV-008 waits for host details). Like NAV-006 (D126), this story is **built and verified in the dev container** on a separate Compose project, slot root and gateway port. The shared dev stack at `http://127.0.0.1:8080` is never stopped, restarted, rebuilt or used for writes (AC 32). Real-VPS deployment is documented in `infra/staging/RUNBOOK.md` only (AC 35). QA level is **light** (D210, as for NAV-006): unit and integration tests, lint and the build, with no separate QA run.

### Terms used in this story
| Term | Meaning here |
|---|---|
| **Pack** | The offline data for region `mn`: three files, each with its own version (ADR-0017 §1) |
| **File kinds** | `tiles` = `basemap.pmtiles` (z0–14), `routing` = `routing.tar` (Valhalla tile extract), `search` = `search.sqlite` (SQLite FTS5 + R*Tree) |
| **File version** | The NAV-006 slot ID the file was cut from (UTC `YYYYMMDDTHHMMSSZ`) |
| **Publication** | One run of the pack-publish step that ends with a new `packs/mn/manifest.json` |
| **Weekly part** | `routing` + `search`, always cut together from one slot (same version) |
| **Golden route set** | The offline-routing parity set (AC 9), shared by Gate 1 (NAV-021) and Gate 2 (this story) |
| **Gate 2 engine** | A pinned container image built, unmodified, from the Rallista `valhalla-mobile` fork commit of the pinned AAR version (0.6.3 at the time of writing) |

## Acceptance criteria
Numbers marked *BA proposal* are starting defaults that the backend implements as configuration keys with these values (Open question 3).

### A. When a publication runs (cadence D166)
1. **Given** a NAV-006 run ended with result `success` (switch done **and** the NAV-006 AC 21 post-switch smoke passed), **When** the pipeline continues, **Then** the pack-publish step runs inside the **same lock** as the NAV-006 run (NAV-006 AC 25). **When** the run ended with any other result (`skipped: *`, `failed`, `rolled back`), **Then** the pack-publish step does **not** run and the published manifest is unchanged.
2. **Given** the pack-publish step runs, **When** it decides what to cut, **Then**:
   - **weekly part:** `routing` and `search` are cut from the now-active slot only when the last published `routing` version is **≥ 7 days** old by its slot ID (configuration key, default 7; an optional weekday key may restrict the day), or when no manifest exists yet
   - **monthly part:** `tiles` is cut in the same run only when the last published `tiles` version is **≥ 28 days** old (configuration key, default 28), or when no manifest exists yet. Otherwise the new manifest keeps pointing at the previous `tiles` file
   - when neither part is due, nothing is cut, the step ends as **"pack: not due"** (exit status of the whole run unchanged), and the manifest is unchanged
3. **Given** no manifest has ever been published, **When** the first eligible run happens, **Then** all three files are cut from the same active slot and share one version.
4. **Given** the operator, **When** they run the documented manual command (for example `make pack-publish`, name chosen by backend and listed in the runbook), **Then** it takes the same lock as `make rebuild`, reads only the active slot, and applies the AC 2 rules. A documented one-time override (for example `FORCE=1`) cuts the weekly part regardless of age, and a separate override (for example `TILES=1`) also cuts `tiles`. The timer never sets either override. A second command while the lock is held exits non-zero within **≤ 5 s** naming the lock (as NAV-006 AC 25).

### B. What a publication contains (per-file versions, ADR-0017 §1, §6)
5. **Given** a publication cuts a file, **When** the file is written, **Then**:
   - `routing.tar` is **byte-identical** (same SHA-256) to the active slot's `valhalla_tiles.tar`, and `basemap.pmtiles` is byte-identical to the active slot's tiles archive (z0–14, D170). The graph is never rebuilt for the phone
   - `search.sqlite` is built from the active slot's Photon dump by the pinned search DB builder (AC 6)
   - each file is written to `$NAV_DATA_ROOT/packs/mn/<fileVersion>/` as a gzip file `<file>.gz`, where `<fileVersion>` is the active slot ID
   - the SHA-256 and byte size of both the raw file and the `.gz` file are computed **from the bytes on disk after writing** (not from a stream copy)
6. **Given** the search DB builder, **When** it runs, **Then** its version and the `search_schema` it produces are pinned in configuration, the build log records both, and the output passes the AC 12 search self-test. Until NAV-023 replaces it, builder v1 produces `search_schema: 1` with at least a `place` table, an FTS5 index over folded names and a Latin skeleton column, and an R*Tree over centroids (spike §2.4). Traditional Mongolian script (U+1800–U+18AF) is absent from every indexed and displayed text column (NAV-003 AC 18).
7. **Given** a publication, **When** `packs/mn/manifest.json` is written, **Then** it validates against `OfflinePackManifest` in `openapi.yaml` 0.6.0, and additionally:
   - exactly one file per kind; `routing.version` = `search.version` = `pack_version` when the weekly part was cut in this run; `tiles.version` ≤ `routing.version`
   - each `path` is `<version>/<file>.gz` and the file exists under that path
   - `download_bytes`, `download_sha256`, `bytes`, `sha256` equal the AC 5 values; `total_bytes` = sum of `bytes`; `total_download_bytes` = sum of `download_bytes`
   - `data_timestamp` is the slot's OSM data date for `tiles` and `routing`, and the Photon dump `data_timestamp` for `search` (from the slot's `build-info.json`, NAV-006 AC 9)
   - `format`: `tiles` `{pmtiles: 3, minzoom: 0, maxzoom: 14}`; `routing` `{graph_builder: "valhalla <server version>"}` taken from `build-info.json`; `search` `{search_schema: <n>}`
   - `attribution` contains `OpenStreetMap`; `licence` is `ODbL-1.0` with the ODbL 1.0 URL and `method_url` = the public pipeline repository (a configuration value, ODbL §4.6)
   - `self_test.route` and `self_test.search` are present (values from configuration; default route P1 → Darkhan `auto`, default query «Сүхбаатар», as the openapi example)
8. **Given** the published files, **When** they are listed after a publication, **Then** no file under an existing version directory was modified or replaced (SHA-256 listing before and after is identical for every pre-existing file). A version directory is complete before the manifest refers to it.

### C. Verification before publication (Gate 2 and self-tests)
9. **Given** the golden route set, **When** it is inspected, **Then** it is one shared fixture in `tests/` (QA-owned; path in the QA test plan) used by both Gate 2 (this story) and Gate 1 (NAV-021), and it contains at least these requests (`language=mn-MN`, OSRM format, banners and voice on, as ADR-0009 §2):
   - P1 → P3 car; P1 → P6 walk; P1 → P6 car, with and without `exclude_unpaved`; P1 → P6 bicycle; P1 → P3 car with `alternates: 2`; a reroute body with `heading: 90`
   - UB → Darkhan, UB → Erdenet, UB → Zamyn-Üüd, UB → Khovd and Choibalsan → Ölgii, car
   - UB → Beijing (out of coverage: expected `NoSegment`)
   - the route request of every NAV-005 GPX fixture G1–G9
10. **Given** the weekly part was cut, **When** Gate 2 runs before the manifest is written, **Then** the Gate 2 engine (pinned image from the fork commit; its commit ID and `valhalla-mobile` version are recorded in the run log and `make status`) routes every golden request on the candidate `routing.tar`, and the active server Valhalla routes the same requests on the same tar. Gate 2 **passes** only when, for every request:
    - the OSRM `code` is equal
    - per route: distance equal after rounding to **1 m**, duration equal after rounding to **1 s**, the manoeuvre sequence (type, modifier, roundabout exit) equal, street names equal, and the decoded polyline6 geometry has the same number of points with every point equal at 6 decimals
    - with `alternates: 2`, the same number of routes, each equal as above
11. **Given** Gate 2 finds any difference, **When** the step ends, **Then** the candidate files are **not** published, the previous manifest stays served unchanged, the log names each differing request and the first differing field with both values, the step ends as **"pack: failed (gate 2)"**, and the NAV-006 alert hook is called **once** for the run. The serving slot is **not** rolled back (data serving is unaffected).
12. **Given** candidate files, **When** the self-tests run before the manifest is written, **Then** all of these pass, or the step fails exactly as AC 11 ("pack: failed (self-test)"):
    - `tiles`: the decompressed file starts with the PMTiles v3 magic, its header has minzoom 0 and maxzoom 14, and its bounds contain P1–P6 and X1
    - `routing`: the Gate 2 engine routes `self_test.route` on the decompressed tar with OSRM `code` `Ok`
    - `search`: `PRAGMA quick_check` returns `ok`, and `self_test.search` returns **≥ 1** row
    - each `.gz` decompresses (streaming gzip) to exactly `bytes` bytes with SHA-256 `sha256`
13. **Given** the pinned `valhalla-mobile` version of the app, **When** NAV-021 has added it to `mobile/android/gradle/libs.versions.toml`, **Then** a repository check (run by the backend tests, and by the pack-publish step when that file is present in the deployed checkout; read-only) fails when the Gate 2 engine's configured `valhalla-mobile` version differs from it, and the pack step then fails as AC 11 ("pack: failed (engine version mismatch)"). Before NAV-021 pins it, the version comes from configuration (0.6.3 per ADR-0017) and the log says so.

### D. Atomic publication
14. **Given** a publication passed section C, **When** the manifest is replaced, **Then** it is written to a temporary file in the same directory and moved into place with **one** `rename(2)`. **Given** a client loop reading `/packs/mn/manifest.json` at **≥ 10 requests/s** during **5** consecutive publications, **When** the responses are parsed, **Then** **0** responses are partial or invalid JSON, and every response equals either the old or the new manifest.
15. **Given** the pack-publish step is interrupted (SIGTERM, `kill -9`, reboot) at any point, **When** the next run or `make pack-publish` starts, **Then** the served manifest is the last complete one, every file it references exists with its recorded SHA-256, and partial files or directories from the interrupted step are deleted before new work starts.

### E. Retention, disk and slot cleanup
16. **Given** publications, **When** cleanup runs at the end of a successful publication, **Then** exactly the files referenced by **the last 3 published manifests** are kept (copies of those manifests are kept for this purpose), and every other file and version directory under `packs/mn/` is deleted. **Given** a test configuration with both minimum ages set to 0 and **4** consecutive publications, **When** cleanup runs, **Then** the files of publication 1 that publications 2–4 do not reference are gone, and every file referenced by publications 2–4 is still served (200).
17. **Given** NAV-006 cleanup deletes the slot a published file was cut from (NAV-006 AC 22 keeps 2 slots), **When** that file is requested, **Then** it is still served with **200**, and its SHA-256 is unchanged (pack files are copies or hard links outside the slot directories; ADR-0014 single filesystem).
18. **Given** the free disk on `NAV_DATA_ROOT` is below the configured pack minimum when the pack-publish step starts (default **2 GB**, *BA proposal*), **When** it starts, **Then** it ends before writing any file as **"pack: skipped (low disk)"**, logs the free and required values, calls the alert hook once, and leaves the manifest unchanged. The run log records the disk used by `packs/` after each publication (expected about 0.6–0.8 GB at steady state, ADR-0017 §5).

### F. Serving `/packs/` (openapi 0.6.0)
19. **Given** a published manifest, **When** `GET /packs/mn/manifest.json` is sent to the public test gateway, **Then** it returns **200** JSON with a strong `ETag` and `Cache-Control: no-cache`; with `If-None-Match` equal to that `ETag` it returns **304**; for an unknown region or before the first publication it returns **404** (`GatewayNotFound`).
20. **Given** a manifest file entry, **When** `GET /packs/mn/<path>` is sent, **Then**:
    - without `Range`: **200**, `Content-Length` = `download_bytes`, SHA-256 of the body = `download_sha256`, a strong `ETag`, `Accept-Ranges: bytes`, `Cache-Control: public, max-age=31536000, immutable`, and **no** `Content-Encoding` header
    - with `Range: bytes=<n>-` (n = half the size): **206** with the correct `Content-Range`, and the concatenation of the first half and this body has SHA-256 `download_sha256`
    - with `Range` and an `If-Range` that does not match the current `ETag`: **200** with the whole file
    - with a `Range` start ≥ the file size: **416** with `Content-Range: bytes */<size>`
    - with `If-None-Match` = the `ETag`: **304**
    - a `fileVersion` or `file` that does not match the openapi patterns, or a retired file: **404**
21. **Given** the per-IP "packs" limit is configured to a low test value *N* per window, **When** one client sends *N* + 1 requests to `/packs/` inside the window, **Then** the last one returns **429** with a `Retry-After` header (`RateLimited`). `/health`, `/tiles/basemap.pmtiles` and `OPTIONS` preflights are **not** limited by it, and `/v1/*` limits are unchanged. The staging value is a configuration key documented in the runbook (set by backend with the architect).
22. **Given** pack requests, **When** gateway logs are read, **Then** they contain no client IP addresses and no request headers beyond what NAV-008 AC 14 already allows; pack requests carry no coordinates, identifiers or auth by design.
23. **Given** the contract, **When** `backend/scripts/contract_check.py` runs against the test gateway after a publication, **Then** it covers `getOfflinePackManifest` (200, 304, 404) and `getOfflinePackFile` (200, 206, 304, 404, 416, header rules of AC 20) and exits **0**. `backend/scripts/smoke.py` also exits **0** (unchanged operations still pass).

### G. Integration with NAV-006 status, logs and alerts
24. **Given** at least one publication, **When** the operator runs `make status`, **Then** within **≤ 2 s** the JSON additionally contains: the published `pack_version` and `published_at`; for each kind its version, `data_timestamp`, `download_bytes`; the last pack step result (`published`, `not due`, `skipped (<reason>)`, `failed (<reason>)`) with its run ID; and the Gate 2 engine commit and `valhalla-mobile` version.
25. **Given** any pack step, **When** its logs are read, **Then** there is one structured JSON line per pack sub-step (select, copy, search build, gzip, checksum, gate 2, self-test, manifest, cleanup) with run ID, sub-step, result and duration in seconds, in the same log stream and retention as NAV-006 AC 31, and with no secrets, client IPs or user coordinates (P1–P6, X1 and the golden route points are allowed).
26. **Given** a pack step ends as `failed (*)` or `skipped (low disk)`, **When** it ends, **Then** the NAV-006 alert hook is called **exactly once** for the run with the run ID, result, sub-step and reason. `published` and `not due` do not call it.
27. **Given** staging-like resources (4 vCPU), **When** a publication cuts all three files, **Then** the pack step takes **≤ 15 minutes** from start to manifest rename (*BA proposal*). In the dev container each sub-step's duration is recorded with no pass/fail threshold. The pack step runs **after** the switch, so it never counts against NAV-006 AC 10 and never delays serving.

### H. Rollback of served data
28. **Given** the operator runs `make rollback` (NAV-006 AC 18) and the current manifest references a file cut from the slot being rolled back, **When** the rollback completes, **Then** within **≤ 60 s** the published manifest is replaced (one `rename(2)`) by the newest retained manifest that references **no** file from the rolled-back slot, `make status` shows it, and the log records it. If no such manifest exists, the manifest is left unchanged and the alert hook is called once. *(BA proposal, Open question 1: the pack follows a data rollback.)*

### I. Dev-container build and verification (isolation)
29. **Given** the shared dev stack (Compose project `navmn`, `http://127.0.0.1:8080`) is running, **When** the whole NAV-020 build and QA run takes place, **Then** none of its containers is stopped, restarted, recreated or rebuilt (container IDs and `StartedAt` equal before and after), a probe of `http://127.0.0.1:8080/health` every **≤ 5 s** records **0** failures, and files under its `backend/data/` are only read or copied.
30. **Given** the test setup, **When** it is inspected, **Then** it uses a Compose project name other than `navmn`, its own `NAV_DATA_ROOT` outside `backend/data/` and outside git tracking, and a test gateway bound to `127.0.0.1` on a port other than 8080 and other than ports used by a parallel test run.
31. **Given** the test run, **When** it publishes, **Then** it produces at least **2** publications whose weekly part differs in version (for example two slots built with `FORCE=1`), so that per-file versioning (an older `tiles.version` next to a newer `routing.version`) and retention (AC 16) are shown with real files.
32. **Given** the test run is finished, **When** cleanup is done, **Then** the test Compose project has **0** containers, volumes and networks left, the test data root is deleted, and the report gives free disk before, peak disk during and free disk after, plus the duration of each pack sub-step.

### J. Runbook, configuration and repository
33. **Given** the backend deliverables, **When** `infra/staging/RUNBOOK.md` is read, **Then** it has a NAV-020 section covering: the cadence keys, the manual command and overrides, how to read the pack part of `make status`, every pack result with the operator action, the Gate 2 engine pin and how to change it (only with the ADR-0017 §4 Gate 1 re-run), the "packs" rate-limit key, retention and disk use, the AC 28 rollback behaviour, and a checklist for enabling the step on the real NAV-008 VPS (not executed here).
34. **Given** the repository, **When** it is inspected, **Then** every new configuration key is in the relevant `.env.example` with a one-line comment and its default, a secret scan finds **0** secrets, and no file contains a hostname or IP address of a real host (the `method_url` value is configuration, not committed).
35. **Given** the deliverables, **When** the Gate 2 engine image is built, **Then** it is built from the pinned fork commit with the build options of the published AAR, unmodified (no patches), from a recipe committed in `backend/` or `infra/`, and its image digest is recorded in the run log. The upstream Valhalla Docker image of the same version is never used as the gate (evidence only).

## Edge cases
- **No network on the server / Geofabrik down:** the NAV-006 run is "skipped: no valid source", so no pack step runs (AC 1). The served manifest stays, and phones keep their installed pack. After 48 h NAV-006 alerts "stale".
- **Photon dump not updated** (NAV-006 AC 8 reuses the active index): the weekly `search` file is still cut; its `data_timestamp` is the older dump date, so the app shows an older search date than the routing date (NAV-022 data dates).
- **NAV-006 automatic rollback after a switch** (AC 21 there): the run result is `rolled back`, so no pack step runs (AC 1).
- **Manual rollback later** (vandalism found by the operator): AC 28 republishes an older manifest; phones whose installed `sha256` differs download the older files (NAV-022 AC 25).
- **Valhalla upgrade on the server** (new `graph_builder`): Gate 2 still runs; the app only uses the file if its allow-list contains the new builder (NAV-021 Gate 1, ADR-0017 §4). A server upgrade needs an ADR note that re-runs Gate 1 first.
- **Gate 2 host build differs from the server for a tiny floating-point reason:** the strict rule in AC 10 blocks publication on purpose (R1). The architect decides whether a documented tolerance is acceptable; any tolerance is a change to this story.
- **Out-of-coverage request** (UB → Beijing): both engines must return `NoSegment` (AC 9, AC 10).
- **Unpaved roads and countryside:** the golden set includes `exclude_unpaved` P1 → P6 and five intercity routes (AC 9). There is no soum-level routing reference yet.
- **Cyrillic/Latin search:** the search self-test uses «Сүхбаатар» (AC 12); quality is NAV-023.
- **Winter / power cut during publication:** AC 15.
- **Many phones resume a download right after a new publication:** files of the last 3 manifests stay (AC 16), so resumes keep working; a retired file returns 404 and the app re-reads the manifest (NAV-022).
- **GPS loss, off-route:** not applicable (server step).

## Data dependencies & risks
| # | Risk | Impact | Mitigation / owner |
|---|---|---|---|
| R1 | **Engine version coupling.** The phone engine (`valhalla-mobile` 0.6.3 = Valhalla 3.6.3, Rallista fork) reads the server's 3.9.0 graph today (12/12 identical in the spike, measured with the upstream 3.6.3 image), but a future server or wrapper upgrade can break reading or change routes | Offline routing unusable or different from online | Gate 2 on every publication with the fork commit (AC 10), Gate 1 in NAV-021, allow-list in the app, ADR note before any upgrade. Architect, backend, mobile |
| R2 | **Freshness gap.** Offline routing and search are up to about 7 days older than online, and the offline map up to about a month (D166). A new road can be routable offline before it is drawn | Users may see a route over an undrawn road | Data dates shown in the app (NAV-022), "offline" indicator on results (D201). PO-accepted by D166 |
| R3 | **OSM data quality** (address coverage, `maxspeed`, `name:mn` / `name:en`, soum and bag names) is copied into the pack unchanged | Offline search and ETA no better than online | Tracked in backlog data-quality risks; NAV-023 held-out set (D198). BA, PO |
| R4 | **Bandwidth at production hosting:** about 0.23 GB per active user per month (ADR-0017, estimate), about 2.3 TB per month at 10,000 users | Hosting cost (NAV-009 input) | Per-file cadence (D166), unchanged files not re-downloaded, delta spike parked (D196). PO, NAV-009 |
| R5 | **Disk on the host:** about 0.6–0.8 GB retained plus a publication's temporary files, on top of NAV-006's 3 slots | Publication skipped | AC 18 guard, measured on the real host later. Backend, operator |
| R6 | **ODbL obligations** for distributed derivative databases (the graph and the search DB) | Licence breach | Attribution and licence fields in the manifest (AC 7), packs downloadable without login and without DRM, the method public (`method_url`, D35). The app-side notices are NAV-022 and the licences screen (D195). Architect, BA |
| R7 | **The dev container is not staging** (old cached extract, no timer, no real bandwidth) | Timings and the timer path not proven | AC 27 recorded only, runbook checklist (AC 33), real host later (NAV-008). QA, operator |

## Out of scope
- The Android download, install and update (NAV-022), on-device routing (NAV-021), offline search quality and the final search DB schema (NAV-023).
- Delta updates (D167; spike parked, D196).
- Packs for other regions, per-aimag packs, iOS packs (D162; the same files will serve iOS later).
- CDN or third-party static hosting setup (a plain static host may serve the same tree later, ADR-0017 §5).
- Deploying on the real NAV-008 VPS (documented only, AC 33) and production hosting (NAV-009).
- Changes to NAV-006 AC (the story is in progress; a change-log cross-reference only).
- Any change to `openapi.yaml` (0.6.0 already has both operations).

## Open questions
Non-blocking; each has a working assumption in the AC.
1. **Closed 2026-10-04 by [D209](../decisions.md): option (a).** The published pack follows a manual data rollback: `make rollback` republishes the newest retained manifest without files from the rolled-back slot (AC 28). Cost: one extra Wi-Fi download of the weekly part (about 33 MB) or the map (about 86 MB).
2. **Closed 2026-10-04 by [D210](../decisions.md): option (b), light QA** as for NAV-006 (D126). The story had recommended (a); the PO's standing light process (D155) and the orchestrator's instruction chose (b). Gate 2 and the AC are unchanged.
3. **Starting values marked *BA proposal*** (pack minimum free disk 2 GB, pack step ≤ 15 min). *Recommendation:* accept them as starting values, tuned with the NAV-006 guard values after 2 weeks of staging runs (as D130).

## Traceability
| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC1–AC4 | — | — | pack-publish step in the NAV-006 pipeline (backend, TBD; `backend/pipeline/`) | backend tests; QA run | D166, D190, D197 |
| AC5–AC8 | — | `getOfflinePackManifest` (schema) | pack writer, search DB builder v1 (backend, TBD) | checksum listing, manifest schema test (QA) | D158, D166, D170; NAV-023 owns the search schema |
| AC9–AC13 | — | — | Gate 2 runner, self-tests (backend, TBD); golden route set fixture (QA, `tests/`) | Gate 2 pass and forced-difference test (QA) | ADR-0017 §4; NAV-021 Gate 1 |
| AC14–AC15 | — | `getOfflinePackManifest` | manifest rename, interrupt handling | manifest read loop, interrupt test (QA) | |
| AC16–AC18 | — | `getOfflinePackFile` | retention, disk guard (backend) | 4-publication retention test (QA) | NAV-006 AC 22, AC 27 |
| AC19–AC23 | — | `getOfflinePackManifest`, `getOfflinePackFile` | gateway `/packs/` location (backend) | `contract_check.py`, `smoke.py`, Range/If-Range tests (QA) | openapi 0.6.0 |
| AC24–AC27 | — | — | status, logs, alert hook (backend) | QA run reads them | NAV-006 AC 29, 31, 32 |
| AC28 | — | `getOfflinePackManifest` | rollback hook (backend) | rollback test (backend / light QA, D210) | D209 |
| AC29–AC32 | — | `getHealth` (shared-stack probe) | test configuration (backend, QA) | QA report (`docs/qa/`) | isolation rules (orchestrator) |
| AC33–AC35 | — | — | `infra/staging/RUNBOOK.md`, `.env.example`, Gate 2 image recipe | review, secret scan | D35 |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-04 | Offline spike follow-up §9 item 1 (triage log 2026-10-04; PO "all as recommended", [D190](../decisions.md), [D197](../decisions.md)) | Story written: 35 AC in sections A–J (cadence, contents, Gate 2 and self-tests, atomic publication, retention, `/packs/` serving, NAV-006 integration, rollback, dev-container isolation, runbook). Absorbs the NAV-006 part of item 7 and Gate 2. Status `ready` | Launch-blocking (D159); every other offline story consumes its files |
| 2026-10-04 | Open questions 1 and 2 decided by the orchestrator on the PO's standing instruction ([D209](../decisions.md), [D210](../decisions.md)) | OQ1 closed, option (a): pack follows a manual rollback (AC 28 unchanged). OQ2 closed, option (b): light QA as for NAV-006 (Context line updated); the tests in the AC are run by the implementing engineer and the "(QA)" test columns are read as light-QA checks. Open question 3 stays open | OQ1 recommended option. OQ2 differs from the story's recommendation (a) because the PO's light process applies; risk noted in D210 |

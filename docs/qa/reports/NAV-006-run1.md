# QA report: NAV-006 run 1 (light-QA focused set, dev container)

| | |
|---|---|
| Story | [`NAV-006`](../../requirements/stories/NAV-006-daily-osm-rebuild-blue-green.md), AC 38 light-QA set (D126) |
| Plan | [`docs/qa/test-plans/NAV-006.md`](../test-plans/NAV-006.md) |
| Date | 2026-10-04, 03:47–04:24 UTC |
| Build under test | Working tree on top of `4fd0dd2` (backend NAV-006 B1–B13 uncommitted), ADR-0014, `openapi.yaml` 0.5.3 |
| Environment | Dev container, 4 vCPU, 15 GiB RAM, 8.69 GB free on `/var/tmp` at the start. Test project `navmn-nav006`, public test gateway `127.0.0.1:18080`, verify gateway `127.0.0.1:18089`, root `/var/tmp/nav006-test` |
| Driver | `tests/api/nav006/light_qa.sh preflight setup first item5pre item1 item5post item2 item3 teardown` |
| Evidence | `/var/tmp/nav006-qa-evidence/` (outside git, 3 MB): loop JSONL and summaries, AC 16 JSON, run logs, status watch, mount audit, shared-stack snapshots |
| Verdict | **All six focused items pass.** 0 failed requests in 20,772 loop requests across one switch, one forced failure and one rollback. No blocker or major defect. 2 minor findings (section 5) |

## 1. Configuration and relaxations (AC 37)

Every run's `config` log line lists the relaxations:
`freshness check 3(f) OFF (REBUILD_MAX_DATA_AGE_HOURS=0)`, `REBUILD_MIN_FREE_GB=4 (staging 50)`, `REBUILD_HARD_FLOOR_FREE_GB=1 (staging 5)`; Q2 also lists `test fault injection REBUILD_TEST_FAULT=verify`.
`REBUILD_REQUIRE_NOT_OLDER=1` (3(e) on), `REBUILD_MIN_MEM_AVAILABLE_GB=6.5`, `REBUILD_GRACE_SECONDS=30` (staging values).

Data:
- **Slot A** `20261004T034712Z`: copy of the cached extract (`file:`), geo2day mirror `Last-Modified` 2026-09-29T08:02:53Z, sha256 `8d0a2325…`.
- **Slot B** `20261004T035230Z`: `OSM_SOURCES="https://geo2day.com/asia/mongolia.pbf file:<cached copy>"`. The mirror answered, so B is a **real data change**: 2026-10-03T07:30:57Z, sha256 `66d4b197…`, 69,686,979 bytes (AC 2 order, AC 36). The PMTiles archives differ (sha256 `c064adbc…` vs `3060ce4d…`).
- Photon dump: the copied cached dump, `data_timestamp` 2026-09-26T22:59:05Z. It was unchanged for B, so the index was copied from the pristine cache with no re-import (AC 8).

## 2. Results of the focused set

| Item | AC | Result | Key evidence |
|---|---|---|---|
| Q1 rebuild + switch | 7, 9, 11, 12, 13, 14, 15, 16, 17, 22, 29, 31 | **PASS** | §2.1 |
| Q2 forced failure (verify) | 33, 32 | **PASS** | §2.2 |
| Q3 `make rollback` | 18, 19, 16 | **PASS** | §2.3 |
| Q4 lock | 25 | **PASS** | §2.4 |
| Q5 smoke + contract before/after | 11, 14 | **PASS** | §2.5 |
| Q6 isolation + cleanup | 34, 35, 39 | **PASS** | §2.6 |

### 2.1 Q1: full rebuild into slot B and switch under the loop
Command: `make -C backend NAV_ENV_FILE=/var/tmp/nav006-test/nav006.env rebuild` (started 03:52:30Z), make exit 0, result `success`, 235.9 s.

**Request loop** (`switch_loop.py`, 24 req/s, 3 keep-alive + 3 new-connection clients, no retries), 03:51:20Z → 03:57:42Z:

| | requests | failures | p95 ms |
|---|---|---|---|
| `GET /health` | 1,309 | 0 | 4.7 |
| `POST /v1/route` P1→P2 auto | 1,310 | 0 | 36.1 |
| `GET /v1/search` «Сүхбаатар» | 1,310 | 0 | 49.5 |
| `GET /v1/search` "Sukhbaatar" | 1,309 | 0 | 47.2 |
| `GET /v1/reverse` P1 | 1,310 | 0 | 40.6 |
| PMTiles `Range` z14 around P1 (206) | 2,620 | 0 | 6.5 |
| **Total** | **9,168** (keep-alive 4,584 / new 4,584) | **0** | |

- Pointer rename (run log `switch.switched_at`): **03:55:55Z**. The loop covered 274 s before and 108 s after it.
- p95 over the 60 s before: 40.6 ms. p95 from −10 s to +30 s: 43.3 ms. **Ratio 1.07** (limit 2.0).
- Tiles `ETag` changed exactly once, `"6ac1ccf1-7017862"` → `"6ac1ce3d-702eb92"`, first seen 0.64 s after the rename. 0 tile decode errors.

**AC 16** (`pmtiles_continuity.mjs`, the web app's `pmtiles` 4.5.0 + `@mapbox/vector-tile` 3): one `PMTiles` instance from 03:51:20Z. Over 707 rounds of 43 z14 tiles over UB it decoded **30,401 tiles, 8,643 of them after the switch**, with **0 decode errors and 0 request errors**. The library saw 1 `EtagMismatch` and re-read the header once, so header reads went from 1 to 2. Its header ETag at the end equals the new archive's ETag. PASS.

**AC 14 and AC 29:**
- The `make status` watcher (1 call/s) showed slot B, lane `green`, at **03:55:56Z**, ≤ 1 s after the rename.
- Each `make status` call took at most **440 ms** (351 calls).
- The public gateway container `navmn-nav006-gateway-1` was created at 03:47:07Z, before every switch, with 0 restarts: no stop or recreate.
- Status after the run: active B (`osm_data_date` 2026-10-03T07:30:57Z, source geo2day, `photon_data_timestamp` 2026-09-26T22:59:05Z), previous A, `last_run.result` `success`, `stale: false`, `state_matches_pointer: true`.

**AC 7:**
- `make slot-checksums SLOT=20261004T034712Z` before and after the build: **identical**.
- QA's own full listing *including* `photon/photon_data` differs in only 2 files, `node_1/logs/photon.log` and `node_1/logs/photon_server.json`. Those are written by `photon-blue`, the Photon that was serving slot A.
- Mount audit (279 samples, every 5 s): only `valhalla-blue` and `photon-blue` ever mounted slot A. The builders (`tiles-build`, `valhalla-build`) mounted `lanes/build -> ../slots/20261004T035230Z.partial`, and `gateway-verify` mounted only the pointer and `slots/` read-only.

**AC 11, 12, 13** (run log `verify` step):
- Candidate lane `green` became healthy in 5.9 s.
- `smoke.py` against the verify gateway: exit 0, 42 passed. `contract_check.py`: exit 0, 37 conform.
- Reference routes, both slots answering 200, **deviation 0.0** on all three: P1→P3 auto 4,271.6 m, P1→P2 pedestrian 1,305.8 m, P1→P6 auto with `exclude_unpaved` 5,568.5 m.
- Artefact ratios: pmtiles 1.001, valhalla 1.000, photon 1.000.
- On the first build (slot A), both comparisons were logged as `skipped: first build`.

**AC 17:** the `grace` step lasted 30.7 s (`seconds: 30`), then stopped lane `blue`. Afterwards `valhalla-blue` was exited (130, SIGINT), `photon-blue` exited (143), and the green lane was running.

**AC 22:** after cleanup, exactly 2 slots remain (`20261004T034712Z`, `20261004T035230Z`). The `cleanup` line records per-slot usage (own disk 209.0 MB and 208.9 MB; apparent size 2.96 GB, which includes the hard-linked aux cache) and free disk 7.85 GB.

**AC 9:** `build-info.json` of B has:
- `slot.slot_id`, `slot.run_id`, `built_at`
- OSM `source`, `sha256`, `bytes`, `data_date` (+ `data_date_source: last_modified`)
- Photon dump `source`, `sha256`, `data_timestamp`
- `versions` (valhalla 3.9.0, photon 1.3.0, planetiler 0.10.2, protomaps commit)
- `settings.tiles_maxzoom` 14

**AC 31:** one JSON line per step (`config`, `reconcile`, `guards`, `fetch`, `validate`, `photon_dump`, `aux_cache`, `build`, `verify`, `switch`, `post_switch`, `grace`, `cleanup`), each with `run_id`, `result` and `duration_s`, plus a final `summary` line. The log contains only public source URLs, slot IDs, sizes and P1–P6.

**AC 21** (post-switch check only, no fault): `post_switch` ok, smoke 42 passed, 0.5 s after the switch.

### 2.2 Q2: forced verification failure (`REBUILD_TEST_FAULT=verify`)
Command: `REBUILD_TEST_FAULT=verify make -C backend NAV_ENV_FILE=… rebuild FORCE=1` (04:04:45Z). Make exited 2 and printed `Error 22`. `last_run.exit_code` is 22, result `failed`, step `verify`, after 185.7 s.
- **The fault is real, not just simulated.** The candidate Valhalla was stopped before the checks, and `smoke.py` against the verify gateway actually failed: 27 passed, 14 failed, and P1→P3 and P1→P2 got HTTP 502.
- **Nothing switched:**
  - active slot before and after: `20261004T035230Z`, `osm_data_date` 2026-10-03T07:30:57Z
  - tiles ETag unchanged (0 ETag changes in the loop)
- **Slot and containers:** `20261004T040445Z.failed` is on disk. The candidate lane (`blue`) is stopped, `gateway-verify` is removed, and only the active green lane and the gateway are running.
- **Loop**, 315.5 s from 65 s before the start to 65 s after the end: **7,578 requests, 0 failures** (keep-alive 3,789 / new 3,789), 24.0 req/s, 0 decode errors.
- **Alert hook (AC 32):** exactly 1 line for the run, `run=20261004T040445Z result=failed step=verify exit=22`. The only other line in `alerts.log` is from the first build: `success (active data stale)`, because slot A's data was more than 48 h old, which AC 32 requires.
- FORCE=1 reused the day's validated download (`fetch.how`: "today's download reused"). It did not download again (AC 1).

### 2.3 Q3: `make rollback` and the second rollback (AC 18, 19)
Command: `make -C backend NAV_ENV_FILE=… rollback` (04:15:05Z), exit 0, result `rolled back`, total 36.8 s including the 30 s grace.
- `start_previous_lane`: lane `blue` was **recreated**, because Q2 had repointed it to the failed slot. It was healthy on slot A after 5.9 s. The pointer was renamed **6.0 s** after the command started (limit 60 s).
- **Loop**, 04:14:00Z → 04:16:48Z: **4,026 requests, 0 failures** (keep-alive 2,014 / new 2,012). It covered 70 s before and 97 s after the rename. p95 went from 26.1 ms to 46.2 ms, **ratio 1.77** (limit 2.0). The ETag changed back to `"6ac1ccf1-7017862"` 0.89 s after the rename.
- **AC 16 on the rollback:** 13,373 tiles decoded, 7,740 of them after the change, with 0 decode errors and 0 request errors. 1 `EtagMismatch` and 1 header re-read.
- `make status` showed slot A, lane `blue`, at 04:15:11Z, the same second as the rename. The status shows:
  - active A with its data date 2026-09-29T08:02:53Z
  - `previous: null`
  - `rolled_back_slots: ["20261004T035230Z"]`
  - `stale: true`, which is correct: A's data is more than 48 h old
- **Second `make rollback`** (04:16:49Z): exit code 30 after **0.08 s**, message "no previous good slot to roll back to (active 20261004T034712Z, previous None); nothing changed". The active slot is unchanged.

### 2.4 Q4: lock (AC 25), 25 s into the Q1 build
| Command | Result | Time |
|---|---|---|
| `make rebuild` | make `Error 10`, "another rebuild, rollback, deploy or certificate dry run holds the lock /var/tmp/nav006-test/nav-stack.lock; not starting" | 0.07 s |
| `make rollback` | `Error 10`, same message | 0.08 s |
| `deploy.sh` mechanism `exec 9>LOCK; flock -n 9` (deploy.sh line 46–47) | exit 1 (refused) | 2 ms |

The running build ended `success` with the normal switch (Q1). Lock release after `kill -9` was not part of the light set. The lock is a kernel `flock` on a close-on-exec descriptor (code review; the backend ran it).

### 2.5 Q5: smoke and contract against the public test gateway
| When | Active slot | `smoke.py --base-url http://127.0.0.1:18080` | `contract_check.py` (openapi 0.5.3) |
|---|---|---|---|
| Before the switch (03:50:32Z) | A, blue | exit 0, 42 passed, 0 failed | exit 0, 37 conform, 0 do not |
| After the switch (04:03:36Z) | B, green | exit 0, 42 passed, 0 failed | exit 0, 37 conform, 0 do not |

### 2.6 Q6: isolation, cleanup, disk and memory (AC 34, 35, 39)
- **AC 35:** project `navmn-nav006` (containers labelled with `backend/compose.slots.yaml`), its own root `/var/tmp/nav006-test/data`, its own lock, and ports `127.0.0.1:18080` and `127.0.0.1:18089` only. With no pointer, it answered route 502 and tiles 404, as ADR §3 specifies.
- **AC 34, shared stack:**
  - `navmn-gateway-1`, `navmn-photon-1` and `navmn-valhalla-1` have the same container IDs, `StartedAt` 2026-10-04T01:54:14Z and 0 restarts before and after. The exited one-shot builders are unchanged too.
  - **1,117** `/health` probes on `:8080`, every 2 s from 03:47:01Z to 04:24:27Z: **0 non-200**.
  - The `backend/data` listing (path, size, mtime, inode, live Photon directory excluded) is **identical**.
  - Information only: inside `backend/data/photon`, the shared `navmn-photon-1` itself updated 3 files during the run: `logs/photon.log`, `logs/photon_server.json` and `data/nodes/0`. The test setup never reads or mounts that directory (ADR-0014 §11).
- **AC 39 teardown** (`make nav006-test-teardown`): 0 containers, 0 networks, 0 volumes left, test root deleted.

| Disk / memory | Value |
|---|---|
| Free disk before (`/var/tmp`) | 8,694 MB |
| Lowest free disk during the run (sampled every 3 s) | 7,070 MB (peak use 1,624 MB) |
| Free disk after cleanup | 8,689 MB (−5 MB, of which 3 MB is the kept evidence directory). Within 200 MB |
| Host memory in use at the start | 1,475 MB (shared stack + container) |
| Peak host memory in use (MemTotal − MemAvailable, 3 s) | 5,837 MB, about 4.4 GB above the start (build + serving lane). Below the 11.2 GB AC 28 budget; the real AC 28 measurement belongs on staging |

**Step durations (AC 10, dev container, no threshold):**

| Run | Total | Steps |
|---|---|---|
| First build (slot A) | 191.1 s | validate 6.7 s (full read), build 173.5 s, verify 9.9 s (lane start 5.9 s), switch 0.0 s, post-switch 0.5 s |
| Q1 (slot B) | 235.9 s | fetch 4.6 s (download 69.7 MB), validate 6.8 s (1,578 blocks), build 182.6 s (tiles 181.1 s, valhalla 20.0 s, photon copy 1.0 s, build-info 0.5 s), verify 10.4 s, switch 0.0 s, post-switch 0.5 s, grace 30.7 s, cleanup 0.0 s |
| Q2 (forced failure) | 185.7 s | tiles 172.0 s, valhalla 18.0 s, verify failed after 10.1 s |
| Q3 rollback | 36.8 s | lane start 5.9 s, switch at 6.0 s, grace 30.7 s |

## 3. Review and backend-test evidence for the other AC

| AC | Result | Evidence |
|---|---|---|
| 1–6 | Pass (unit level) | `make -C backend pipeline-test`: **31 tests, OK** (1.7 s). These include a 50 % truncated copy failing 3(a) and the HTML page, size floor, shrink and older checks. Geofabrik itself not reachable here |
| 8 | Pass (no re-import path) | Q1 `photon_index: copied from cache … (no re-import)`. Dump-download-failure path not verified (code review only, backend) |
| 20, 21, 26, 27, 28 | Not run by QA (outside the light set) | Backend ran them (`post_switch` fault → exit 24, rolled-back checksum → exit 14, SIGTERM 0.6 s, `kill -9` reconcile, disk and memory guards); the 31 unit tests pass |
| 23 | Pass (review) | `nav-rebuild.timer`: `OnCalendar=*-*-* 19:30:00 UTC`, `RandomizedDelaySec=15min`, `Persistent=true`. `nav-rebuild.service` runs `nav-rebuild.sh` (wrapper). One rebuild timer in `infra/staging/systemd/`. Not run (no systemd) |
| 24 | Pass | Q1–Q4 used `make rebuild`, `rollback`, `status` from config. The RUNBOOK §7.5 exit-code table matches the codes observed (0, 10, 22, 30) |
| 30 | Not applicable | No HTTP status field (ADR-0014 §9); `openapi.yaml` still 0.5.3 |
| 40 | Pass (review) | ADR-0014 §1–§10 cover items 1–8 |
| 41 | Pass (review) | RUNBOOK §7.1–7.9: install, commands, status JSON, exit codes, recovery, guards, source list, VPS checklist; no hostnames or IPs |
| 42 | **Pass with a minor finding** | `tests/staging/nav008/repo-checks.sh --stage pre`: RS-01/02/03/05/06/07/08 PASS (0 secrets, every listed key commented). No public IPv4 literal in the changed files. Finding F1: 4 keys the pipeline reads are not in `.env.example` |
| 43 | Pass (review) | `nav-rollback-data.sh` deleted; `nav-rebuild.sh` is a thin wrapper; deployment-staging §11 marks the interim steps as superseded history and describes the pipeline path, the same one as RUNBOOK §7 |

## 4. Not verified, and why
- Staging-only: timer firing and `next_scheduled_run`, the 30 min budget (AC 10), the AC 28 staging memory measurement, the Uptime Kuma heartbeat (AC 32), Caddy in front of the switch, and the real Geofabrik path and `.md5` (Geofabrik is blocked here). There is no staging host (NAV-008).
- MapLibre Native (Android/iOS) behaviour on an ETag change (R6): requested from mobile-engineer by the architect; no device session in this run.
- Log retention pruning after 30 days: no aged files.
- AC 3–6, 20, 21, 26–28 were not exercised end to end by QA (light-QA scope); they are covered by unit tests and the backend's runs.
- `pmtiles_continuity.mjs` was not tested against a server that changes the archive *without* changing the ETag (a negative control). That would need a modified gateway, which QA does not own. The loop's tiles client does check that every tile served under the cached ETag gunzips.

## 5. Findings

| ID | Severity | Owner | Finding |
|---|---|---|---|
| F1 | minor | backend-engineer | **AC 42: keys the pipeline reads are missing from `infra/staging/.env.example`.** `nav_pipeline.py` reads `REBUILD_USER_AGENT` (line 301), `NAV_TIMER_UNIT` (306), `NAV_ALLOW_LEGACY_PROJECT` (305) and `CONTRACT_PYTHON` (308). None is in `infra/staging/.env.example` or `backend/.env.example`; `NAV_ALLOW_LEGACY_PROJECT` is mentioned only in RUNBOOK §7.9. Steps: `grep -n 'REBUILD_USER_AGENT\|NAV_TIMER_UNIT\|NAV_ALLOW_LEGACY_PROJECT\|CONTRACT_PYTHON' infra/staging/.env.example backend/.env.example` → no match. Expected: every new key listed with a one-line comment and its default (commented out where it is optional or one-time). Actual: absent. (`REBUILD_TEST_FAULT`/`REBUILD_ALLOW_TEST_FAULTS` are correctly absent per ADR §11; `NAV_COMPOSE_OVERLAYS` is set by the wrappers, not by the operator) |
| F2 | minor | backend-engineer | **The `validate` log line claims check 3(f) ran when it is switched off.** With `REBUILD_MAX_DATA_AGE_HOURS=0`, the Q1 `validate` line says `"checks": "3(a) 3(b) 3(c) 3(f) 3(d) 3(e)"` (`nav_pipeline.py` line 930 builds that string unconditionally). Steps: any test-project run; read the `validate` line. Expected: 3(f) is not listed as passed when it is disabled, or it is listed as `3(f) off`. The `config` line does state the relaxation, so AC 37 is still met; the step line just misleads anyone reading it alone |

Observation, not a defect: a manual `make rollback` that leaves stale data active (Q3: `stale: true`) does not call the alert hook. In `nav_pipeline.py` the stale alert applies only to `kind == "rebuild"`. This matches the story's definition of a *run* (timer or `make rebuild`). The next scheduled run does alert: it finds the rolled-back extract (exit 14, `skipped: source was rolled back`) or, with an unchanged extract, stale data (exit 15).

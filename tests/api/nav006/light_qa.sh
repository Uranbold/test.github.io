#!/usr/bin/env bash
# NAV-006 light-QA driver (story AC 38, test plan docs/qa/test-plans/NAV-006.md). Runs the focused set in the
# dev container against the SEPARATE test project (navmn-nav006, 127.0.0.1:18080) and never touches the shared
# dev stack (project navmn, 127.0.0.1:8080) except for read-only probes.
#
#   tests/api/nav006/light_qa.sh <stage>      stages, in order:
#     preflight   shared-stack snapshot + /health probe (every 2 s) + disk/memory sampler (every 3 s), free disk
#     setup       make nav006-test-setup + nav006-up; isolation facts (AC 35)
#     first       first slot: make rebuild (cached extract)                                   -> slot A
#     item5pre    smoke.py + contract_check.py against 127.0.0.1:18080 (before the switch)     QA item 5
#     item1       changed extract (geo2day mirror first, cached copy as fallback): loop + AC 16 client + status
#                 watch + mount audit; lock test during the build (QA item 4); AC 7 checksums -> slot B
#     item5post   smoke.py + contract_check.py against 127.0.0.1:18080 (after the switch)      QA item 5
#     item2       REBUILD_TEST_FAULT=verify make rebuild FORCE=1 under the loop: no switch    QA item 2
#     item3       make rollback under the loop, then a second make rollback (AC 19)            QA item 3
#     teardown    make nav006-test-teardown; stop probes; shared-stack compare (AC 34); disk (AC 39)
#
# Evidence goes to $EVID (default /var/tmp/nav006-qa-evidence, outside git and outside the test root).
set -uo pipefail
REPO=$(cd "$(dirname "$0")/../../.." && pwd)
BACKEND=$REPO/backend
EVID=${EVID:-/var/tmp/nav006-qa-evidence}
ROOT=${NAV006_TEST_ROOT:-/var/tmp/nav006-test}
ENVF=$ROOT/nav006.env
BASE=http://127.0.0.1:18080
SHARED=http://127.0.0.1:8080
LOOP="python3 $REPO/tests/api/nav006/switch_loop.py"
AC16="node $REPO/tests/e2e/nav006/pmtiles_continuity.mjs"
VENV_PY=$REPO/tests/.venv/bin/python
[[ -x $VENV_PY ]] || VENV_PY=python3
MAKE=(make -s -C "$BACKEND" NAV_ENV_FILE="$ENVF")
mkdir -p "$EVID"

ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }
say() { printf '[%s] %s\n' "$(ts)" "$*" | tee -a "$EVID/driver.log"; }
free_mb() { df -Pm /var/tmp | awk 'NR==2{print $4}'; }
status_json() { "${MAKE[@]}" status 2>&1; }
active_slot() { status_json | python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("active") or {}).get("slot") or "")'; }
# background helpers run detached (setsid) so they survive the stage that started them; shell functions are
# reached through this script's internal stages (_health, _sampler, _status_watch, _mount_audit)
bg() { local name=$1; shift; setsid "$@" >"$EVID/$name.out" 2>&1 </dev/null & echo $! >"$EVID/$name.pid"; }
stop_bg() { local f=$EVID/$1.pid; [[ -f $f ]] && kill "$(cat "$f")" 2>/dev/null; rm -f "$f"; }

shared_snapshot() { # AC 34: container IDs, StartedAt, RestartCount of project navmn + backend/data listing
    {
        for c in $(docker ps -aq --filter label=com.docker.compose.project=navmn); do
            docker inspect -f '{{.Name}} {{.Id}} {{.State.Status}} {{.State.StartedAt}} restarts={{.RestartCount}}' "$c"
        done | sort
        echo "# backend/data listing sha256 (path size mtime inode, Photon runtime dir excluded):"
        (cd "$BACKEND/data" && find . -path ./photon -prune -o -printf '%p %s %T@ %i\n' | sort | sha256sum)
        echo "# backend/data/photon listing sha256 (live Photon; informational, it writes its own state):"
        (cd "$BACKEND/data" && find ./photon -printf '%p %s %i\n' 2>/dev/null | sort | sha256sum)
    } >"$1"
}

health_probe() { # every 2 s, one line per probe: ts http_code seconds
    while :; do
        r=$(curl -s -o /dev/null -m 5 -w '%{http_code} %{time_total}' "$SHARED/health" || echo "000 5")
        echo "$(ts) $r"
        sleep 2
    done
}

sampler() { # every 3 s: ts free_disk_MB used_mem_MB (MemTotal - MemAvailable) test_containers_running
    while :; do
        m=$(awk '/MemTotal/{t=$2}/MemAvailable/{a=$2}END{printf "%d", (t-a)/1024}' /proc/meminfo)
        c=$(docker ps -q --filter label=com.docker.compose.project=navmn-nav006 | wc -l)
        echo "$(ts) $(free_mb) $m $c"
        sleep 3
    done
}

status_watch() { # every 1 s: ts, make status wall time (ms), active slot, lane (AC 14 <= 10 s, AC 29 <= 2 s)
    while :; do
        t0=$(date +%s%N)
        s=$(status_json | python3 -c 'import json,sys
try:
    d=json.load(sys.stdin); a=d.get("active") or {}; print(a.get("slot"), a.get("lane"))
except Exception as e:
    print("ERR", type(e).__name__)')
        echo "$(ts) $(( ($(date +%s%N) - t0) / 1000000 )) $s"
        sleep 1
    done
}

mount_audit() { # every 5 s: which slot each running test-project container has at /data or /srv/slots (AC 7)
    while :; do
        for c in $(docker ps -q --filter label=com.docker.compose.project=navmn-nav006); do
            docker inspect -f '{{.Name}}{{range .Mounts}} {{.Source}}=>{{.Destination}}:{{if .RW}}rw{{else}}ro{{end}}{{end}}' "$c" |
                while read -r name rest; do
                    out="$name"
                    for m in $rest; do
                        src=${m%%=>*}
                        case "$src" in */lanes/*) src="$src->$(readlink "$src" 2>/dev/null)";; esac
                        out="$out $src=>${m#*=>}"
                    done
                    echo "$(ts) $out"
                done
        done
        sleep 5
    done
}

full_listing() { # sha256 of every file in a slot INCLUDING photon/photon_data (QA's own AC 7 listing)
    (cd "$ROOT/data/slots/$1" && find . -type f -print0 | sort -z | xargs -0 sha256sum) >"$2"
}

run_make_timed() { # name, args...: runs make, records exit, the pipeline's own code and wall time
    local name=$1; shift
    local t0 t1
    t0=$(date +%s.%N)
    "${MAKE[@]}" "$@" >"$EVID/$name.log" 2>&1
    local rc=$?
    t1=$(date +%s.%N)
    local code
    code=$(grep -o 'Error [0-9]*' "$EVID/$name.log" | tail -1 | awk '{print $2}')
    printf '{"cmd":"make %s","make_exit":%d,"pipeline_code":"%s","seconds":%.2f,"start":"%s"}\n' "$*" "$rc" "${code:-0}" \
        "$(awk -v a="$t0" -v b="$t1" 'BEGIN{print b-a}')" "$(date -u -d @"${t0%.*}" +%Y-%m-%dT%H:%M:%SZ)" | tee "$EVID/$name.result.json"
    return $rc
}

start_loop() { # name
    rm -f "$EVID/$1.stop"
    bg "$1-loop" $LOOP run --base-url "$BASE" --out "$EVID/$1.loop.jsonl" --stop-file "$EVID/$1.stop" --duration 2400
}

finish_loop() { # name switch_at|-
    touch "$EVID/$1.stop"
    local pidf=$EVID/$1-loop.pid
    [[ -f $pidf ]] && while kill -0 "$(cat "$pidf")" 2>/dev/null; do sleep 1; done
    rm -f "$pidf"
    if [[ ${2:-} != - && -n ${2:-} ]]; then
        $LOOP summarize "$EVID/$1.loop.jsonl" --switch-at "$2" --json "$EVID/$1.loop.summary.json" >/dev/null
    else
        $LOOP summarize "$EVID/$1.loop.jsonl" --json "$EVID/$1.loop.summary.json" >/dev/null
    fi
    python3 - "$EVID/$1.loop.summary.json" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))
keys = ["requests", "seconds", "rate_per_s", "failures", "by_client", "tile_decode_errors", "switch_at",
        "seconds_before_switch", "seconds_after_switch", "p95_ms_60s_before", "p95_ms_window_-10s_+30s", "p95_ratio",
        "etag_changes", "first_etag_change_minus_switch_s", "pass"]
print(json.dumps({k: d.get(k) for k in keys}, ensure_ascii=False))
print(json.dumps({k: v for k, v in d["per_endpoint"].items()}, ensure_ascii=False))
EOF
}

run_log_of() { ls -t "$ROOT"/data/runs/*.jsonl 2>/dev/null | head -1; }
switched_at() { python3 -c 'import json,sys
for l in open(sys.argv[1]):
    d=json.loads(l)
    if d.get("step")=="switch" and d.get("result")=="ok":
        print(d.get("switched_at") or d["ts"]); break' "$1"; }

stage=${1:-}
case "$stage" in
preflight)
    say "preflight: free disk $(free_mb) MB on /var/tmp; MemAvailable $(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo) MB"
    free_mb >"$EVID/free_mb_before"
    shared_snapshot "$EVID/shared-before.txt"; cat "$EVID/shared-before.txt"
    bg health "$0" _health; bg sampler "$0" _sampler
    say "health probe pid $(cat "$EVID/health.pid"), sampler pid $(cat "$EVID/sampler.pid")"
    ;;
setup)
    "${MAKE[@]}" nav006-test-setup 2>&1 | tee "$EVID/setup.log"
    "${MAKE[@]}" nav006-up 2>&1 | tee -a "$EVID/setup.log"
    { grep -E '^(NAV_COMPOSE_PROJECT|NAV_DATA_ROOT|NAV_LOCK_FILE|GATEWAY_PORT|NAV_VERIFY_PORT|OSM_SOURCES|REBUILD_)' "$ENVF"
      docker ps --filter label=com.docker.compose.project=navmn-nav006 --format '{{.Names}} {{.Ports}}'
      # shellcheck disable=SC2046  # one argument per container id
      docker inspect -f '{{.Name}} {{index .Config.Labels "com.docker.compose.project.config_files"}}' \
          $(docker ps -q --filter label=com.docker.compose.project=navmn-nav006)
      echo "no pointer yet: $(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' $BASE/v1/route) route, $(curl -s -o /dev/null -w '%{http_code}' -r 0-99 $BASE/tiles/basemap.pmtiles) tiles"
    } | tee "$EVID/isolation.txt"
    ;;
first)
    say "first slot (cached extract copy)"
    run_make_timed first rebuild
    status_json | tee "$EVID/status-after-first.json"
    ;;
item5pre|item5post)
    say "$stage: smoke + contract against $BASE"
    (cd "$BACKEND" && python3 scripts/smoke.py --base-url "$BASE" --report "$EVID/$stage.smoke.json") >"$EVID/$stage.smoke.log" 2>&1
    echo "smoke exit $?" | tee -a "$EVID/$stage.smoke.log"; tail -3 "$EVID/$stage.smoke.log"
    (cd "$BACKEND" && $VENV_PY scripts/contract_check.py --base-url "$BASE" --spec "$REPO/docs/architecture/api/openapi.yaml") >"$EVID/$stage.contract.log" 2>&1
    echo "contract exit $?" | tee -a "$EVID/$stage.contract.log"; tail -3 "$EVID/$stage.contract.log"
    status_json | python3 -c 'import json,sys; a=json.load(sys.stdin)["active"]; print("active slot", a["slot"], a["lane"])' | tee -a "$EVID/$stage.smoke.log"
    ;;
item1)
    A=$(active_slot); [[ -n $A ]] || { say "no active slot: run stage first"; exit 2; }
    echo "$A" >"$EVID/slotA"
    status_json >"$EVID/item1.status-before.json"
    "${MAKE[@]}" slot-checksums SLOT="$A" >"$EVID/item1.A.checksums-before.txt"
    full_listing "$A" "$EVID/item1.A.full-before.txt"
    # changed extract: the dev mirror first (newer data), the cached copy as fallback (AC 2, AC 36)
    cp "$ENVF" "$EVID/nav006.env.orig"
    sed -i "s|^OSM_SOURCES=.*|OSM_SOURCES=https://geo2day.com/asia/mongolia.pbf file:$ROOT/input/mongolia.osm.pbf|" "$ENVF"
    grep '^OSM_SOURCES' "$ENVF" | tee -a "$EVID/driver.log"
    start_loop item1
    rm -f "$EVID/item1.ac16.stop"
    bg item1-ac16 $AC16 --url "$BASE/tiles/basemap.pmtiles" --out "$EVID/item1.ac16.json" --stop-file "$EVID/item1.ac16.stop"
    bg status-watch "$0" _status_watch; bg mount-audit "$0" _mount_audit
    say "item1: loop + AC 16 client + status watch + mount audit started; 70 s of baseline"
    sleep 70
    say "item1: make rebuild (changed extract)"
    ( run_make_timed item1-rebuild rebuild ) &
    RB=$!
    sleep 25
    say "item 4 lock test while the build runs"
    run_make_timed lock-rebuild rebuild
    run_make_timed lock-rollback rollback
    t0=$(date +%s%N)
    ( exec 9>"$ROOT/nav-stack.lock"; flock -n 9 ) ; frc=$?
    echo "{\"cmd\":\"deploy.sh lock mechanism: exec 9>LOCK; flock -n 9\",\"exit\":$frc,\"ms\":$(( ($(date +%s%N)-t0)/1000000 ))}" | tee "$EVID/lock-deploy.result.json"
    status_json >"$EVID/lock.status-during.json"
    wait $RB; say "item1 rebuild ended: $(cat "$EVID/item1-rebuild.result.json")"
    say "item1: 75 s of loop after the run"
    sleep 75
    touch "$EVID/item1.ac16.stop"
    while kill -0 "$(cat "$EVID/item1-ac16.pid" 2>/dev/null)" 2>/dev/null; do sleep 1; done; rm -f "$EVID/item1-ac16.pid"
    stop_bg status-watch; stop_bg mount-audit
    RUNLOG=$(run_log_of); cp "$RUNLOG" "$EVID/item1.run.jsonl"
    SW=$(switched_at "$RUNLOG"); echo "$SW" >"$EVID/item1.switched_at"
    finish_loop item1 "$SW" | tee "$EVID/item1.loop.brief.txt"
    status_json | tee "$EVID/item1.status-after.json"
    B=$(active_slot); echo "$B" >"$EVID/slotB"
    "${MAKE[@]}" slot-checksums SLOT="$A" >"$EVID/item1.A.checksums-after.txt"
    full_listing "$A" "$EVID/item1.A.full-after.txt"
    if cmp -s "$EVID/item1.A.checksums-before.txt" "$EVID/item1.A.checksums-after.txt"; then echo "AC 7 slot $A listing identical"; else echo "AC 7 slot $A listing DIFFERS"; fi | tee "$EVID/item1.ac7.txt"
    diff "$EVID/item1.A.full-before.txt" "$EVID/item1.A.full-after.txt" | grep '^[<>]' | awk '{print $1, $3}' | sort -u >>"$EVID/item1.ac7.txt"
    { echo "slots:"; ls -la "$ROOT/data/slots"; du -sh --apparent-size "$ROOT"/data/slots/* "$ROOT/data/cache" 2>/dev/null; } | tee "$EVID/item1.slots.txt"
    cp "$ROOT/data/slots/$B/build-info.json" "$EVID/item1.B.build-info.json" 2>/dev/null
    sha256sum "$ROOT/data/slots/$A/tiles/basemap.pmtiles" "$ROOT/data/slots/$B/tiles/basemap.pmtiles" | tee "$EVID/item1.pmtiles-sha.txt"
    ;;
item2)
    status_json >"$EVID/item2.status-before.json"
    ls "$ROOT/data/slots" >"$EVID/item2.slots-before.txt"
    A0=$(wc -l <"$ROOT/alerts.log" 2>/dev/null || echo 0)
    start_loop item2
    sleep 65
    say "item2: REBUILD_TEST_FAULT=verify make rebuild FORCE=1"
    REBUILD_TEST_FAULT=verify run_make_timed item2-rebuild rebuild FORCE=1
    sleep 65
    RUNLOG=$(run_log_of); cp "$RUNLOG" "$EVID/item2.run.jsonl"
    finish_loop item2 - | tee "$EVID/item2.loop.brief.txt"
    status_json | tee "$EVID/item2.status-after.json"
    ls "$ROOT/data/slots" | tee "$EVID/item2.slots-after.txt"
    docker ps -a --filter label=com.docker.compose.project=navmn-nav006 --format '{{.Names}} {{.State}}' | sort | tee "$EVID/item2.containers.txt"
    A1=$(wc -l <"$ROOT/alerts.log" 2>/dev/null || echo 0)
    echo "alert lines before $A0 after $A1" | tee "$EVID/item2.alerts.txt"; tail -n $((A1 - A0)) "$ROOT/alerts.log" | tee -a "$EVID/item2.alerts.txt"
    ;;
item3)
    status_json >"$EVID/item3.status-before.json"
    start_loop item3
    rm -f "$EVID/item3.ac16.stop"
    bg item3-ac16 $AC16 --url "$BASE/tiles/basemap.pmtiles" --out "$EVID/item3.ac16.json" --stop-file "$EVID/item3.ac16.stop"
    bg status-watch "$0" _status_watch
    sleep 65
    say "item3: make rollback"
    run_make_timed item3-rollback rollback
    RUNLOG=$(run_log_of); cp "$RUNLOG" "$EVID/item3.run.jsonl"
    SW=$(python3 -c 'import json,sys
for l in open(sys.argv[1]):
    d=json.loads(l)
    if d.get("step")=="switch": print(d["ts"]); break' "$RUNLOG")
    echo "$SW" >"$EVID/item3.switched_at"
    sleep 65
    touch "$EVID/item3.ac16.stop"
    while kill -0 "$(cat "$EVID/item3-ac16.pid" 2>/dev/null)" 2>/dev/null; do sleep 1; done; rm -f "$EVID/item3-ac16.pid"
    stop_bg status-watch
    finish_loop item3 "$SW" | tee "$EVID/item3.loop.brief.txt"
    status_json | tee "$EVID/item3.status-after.json"
    say "AC 19: second make rollback"
    run_make_timed item3-rollback2 rollback
    tail -3 "$EVID/item3-rollback2.log"
    status_json >"$EVID/item3.status-after2.json"
    cmp -s <(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["active"])' "$EVID/item3.status-after.json") \
           <(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["active"])' "$EVID/item3.status-after2.json") &&
        echo "AC 19: active slot unchanged by the refused rollback" | tee -a "$EVID/item3.ac19.txt"
    ;;
teardown)
    "${MAKE[@]}" nav006-test-teardown 2>&1 | tee "$EVID/teardown.log"
    stop_bg health; stop_bg sampler; stop_bg status-watch; stop_bg mount-audit
    shared_snapshot "$EVID/shared-after.txt"
    # the last two lines are the live shared Photon's own directory (it writes logs and node state while it
    # serves): reported for information only, never a verdict
    if diff <(head -n -2 "$EVID/shared-before.txt") <(head -n -2 "$EVID/shared-after.txt"); then
        echo "AC 34: shared containers (ID, StartedAt, restarts) and backend/data listing identical"
    else echo "AC 34: shared stack snapshot DIFFERS"; fi | tee "$EVID/ac34.txt"
    cmp -s <(tail -n 1 "$EVID/shared-before.txt") <(tail -n 1 "$EVID/shared-after.txt") ||
        echo "INFO: backend/data/photon changed (written by the shared navmn-photon-1 itself; the test never mounts it)" | tee -a "$EVID/ac34.txt"
    awk '{n++} $2!="200"{f++} END{printf "health probes %d, non-200 %d, first %s, last %s\n", n, f+0, first, $1} NR==1{first=$1}' "$EVID/health.out" | tee -a "$EVID/ac34.txt"
    awk 'NR==1{min=$2; max=$3} $2<min{min=$2} $3>max{max=$3} END{printf "sampler: lowest free disk %d MB, peak host memory used %d MB\n", min, max}' "$EVID/sampler.out" | tee -a "$EVID/ac34.txt"
    echo "free disk before $(cat "$EVID/free_mb_before") MB, after $(free_mb) MB (evidence dir $(du -sm "$EVID" | cut -f1) MB)" | tee -a "$EVID/ac34.txt"
    for t in containers networks volumes; do
        case $t in containers) n=$(docker ps -aq --filter label=com.docker.compose.project=navmn-nav006 | wc -l);;
                   networks) n=$(docker network ls -q --filter label=com.docker.compose.project=navmn-nav006 | wc -l);;
                   volumes) n=$(docker volume ls -q --filter label=com.docker.compose.project=navmn-nav006 | wc -l);; esac
        echo "test project $t left: $n"
    done | tee -a "$EVID/ac34.txt"
    echo "test root exists: $([[ -e $ROOT ]] && echo yes || echo no)" | tee -a "$EVID/ac34.txt"
    ;;
_health) health_probe ;;
_sampler) sampler ;;
_status_watch) status_watch ;;
_mount_audit) mount_audit ;;
*)
    sed -n '2,20p' "$0"; exit 2 ;;
esac

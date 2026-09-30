#!/usr/bin/env bash
# NAV-008 staging: daily data rebuild (deployment-staging.md §11, B4/B5). Owner: backend-engineer.
# Run by nav-rebuild.service (19:30 UTC = 03:30 Asia/Ulaanbaatar), or by hand:
#   sudo infra/staging/bin/nav-rebuild.sh [--force] [--empty-aux-cache] [--no-heartbeat]
#
#   --force            full rebuild even if the source is unchanged: tiles, routing graph AND search index are
#                      rebuilt (builder markers invalidated, every tool fetched). This is staging's equivalent of
#                      the dev `make rebuild-data` (NAV-008 AC 15; never run backend/Makefile targets on staging).
#   --empty-aux-cache  implies --force. First moves the Planetiler auxiliary files (data/sources/ except the OSM
#                      and Photon inputs) and data/tools/ aside, so every C9 download happens from this host
#                      (AC 15 "empty auxiliary cache"). They are put back if the run fails and deleted after a
#                      good run. Measure memory with bin/nav-stats-sampler.sh (RUNBOOK.md section 7).
#
#   1. preflight   HEAD on OSM_PBF_URL = 200, free disk >= REBUILD_MIN_FREE_GB, gateway /health = 200.
#                  A failed preflight changes nothing (old data keeps serving) and sends no heartbeat.
#   2. unchanged?  Geofabrik's <url>.md5 equals the md5 of the last good build -> skip the build
#                  (unless --force), but still run steps 6-7 so a stale source is noticed.
#   3. rollback    copy the current tiles, routing graph, build-info and resolved sources to
#                  $NAV_STATE_DIR/rollback (if disk allows; photon too when it is re-imported).
#   4. build       fresh download (data-fetch), md5 check, then tiles + routing graph in parallel while
#                  the gateway, Valhalla and Photon KEEP SERVING (builders write to staging paths and swap
#                  atomically). Photon is stopped only when the dump changed or with --force (its index is
#                  re-imported) and starts again right after the import.
#   5. switch      restart Valhalla on the new data. API downtime = this restart (+ the Photon re-import).
#   6. smoke       tests/smoke/run.sh against SMOKE_BASE_URL (default https://$STAGING_HOST).
#   7. age         data/build-info.json osm.replication_timestamp <= REBUILD_MAX_DATA_AGE_HOURS old.
#   8. heartbeat   UPTIME_PUSH_URL_REBUILD, only when 6 and 7 pass.
# A failed build or smoke after a build restores the rollback copy (REBUILD_AUTO_ROLLBACK=1) and exits 1.
# shellcheck source=nav-env.sh
source "$(dirname "$(readlink -f "$0")")/nav-env.sh"

FORCE=0
EMPTY_AUX=0
HEARTBEAT=1
for a in "$@"; do
    case "$a" in
        --force) FORCE=1 ;;
        --empty-aux-cache) EMPTY_AUX=1; FORCE=1 ;;
        --no-heartbeat) HEARTBEAT=0 ;;
        -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
        *) nav_die "unknown argument" arg="$a" ;;
    esac
done

nav_require_root
nav_validate_env
mkdir -p "$NAV_STATE_DIR"
exec 9>"${NAV_LOCK_FILE:-/run/lock/nav-stack.lock}"
flock -n 9 || nav_die "another rebuild, deploy or rollback holds the lock; not starting"

DATA="$NAV_ROOT/backend/data"
SRC="$DATA/sources"
ROLLBACK="$NAV_STATE_DIR/rollback"
AUX_ASIDE="$NAV_STATE_DIR/aux-cache.aside"
OSM_URL=$(nav_env_get OSM_PBF_URL)
GW_PORT=$(nav_env_get GATEWAY_PORT 8080)
HOST=$(nav_env_get STAGING_HOST)
SMOKE_URL=$(nav_env_get SMOKE_BASE_URL "https://$HOST")
MIN_FREE_GB=$(nav_env_get REBUILD_MIN_FREE_GB 50)
ROLLBACK_MIN_FREE_GB=$(nav_env_get REBUILD_ROLLBACK_MIN_FREE_GB 20)
MAX_AGE_H=$(nav_env_get REBUILD_MAX_DATA_AGE_HOURS 48)
SKIP_UNCHANGED=$(nav_env_get REBUILD_SKIP_UNCHANGED 1)
AUTO_ROLLBACK=$(nav_env_get REBUILD_AUTO_ROLLBACK 1)
UA="nav-mn-staging-rebuild/1 (+https://www.openstreetmap.org/copyright; daily, one download per day)"
T0=$(date +%s)

[[ -n "$OSM_URL" ]] || nav_die "OSM_PBF_URL is empty in infra/staging/.env (staging: https://download.geofabrik.de/asia/mongolia-latest.osm.pbf)"

# ---------------------------------------------------------------- helpers
smoke() {
    nav_log info "smoke suite starting" base_url="$SMOKE_URL"
    local rc=0
    if [[ -x "$NAV_ROOT/tests/smoke/run.sh" ]]; then
        BASE_URL="$SMOKE_URL" "$NAV_ROOT/tests/smoke/run.sh" >"$NAV_STATE_DIR/last-smoke.txt" 2>&1 || rc=$?
    else
        python3 "$NAV_ROOT/backend/scripts/smoke.py" --base-url "$SMOKE_URL" >"$NAV_STATE_DIR/last-smoke.txt" 2>&1 || rc=$?
    fi
    nav_log "$([[ $rc -eq 0 ]] && echo info || echo error)" "smoke suite finished" exit_code="$rc" \
        summary="$(tail -n 2 "$NAV_STATE_DIR/last-smoke.txt" | tr '\n' ' ')" output="$NAV_STATE_DIR/last-smoke.txt"
    return $rc
}

data_age_ok() {
    if [[ "$MAX_AGE_H" == 0 ]]; then
        nav_log warn "data age check disabled (REBUILD_MAX_DATA_AGE_HOURS=0)"
        return 0
    fi
    local out rc=0
    out=$(python3 - "$DATA/build-info.json" "$MAX_AGE_H" <<'PY'
import datetime, json, sys
info = json.load(open(sys.argv[1]))
ts = (info.get("osm") or {}).get("replication_timestamp")
if not ts:
    print("missing"); sys.exit(1)
t = datetime.datetime.fromisoformat(ts.replace("Z", "+00:00"))
age = (datetime.datetime.now(datetime.timezone.utc) - t).total_seconds() / 3600
print(f"{ts} age_hours={age:.1f}")
sys.exit(0 if age <= float(sys.argv[2]) else 1)
PY
) || rc=$?
    nav_log "$([[ $rc -eq 0 ]] && echo info || echo error)" "osm replication timestamp check" result="$out" max_age_hours="$MAX_AGE_H"
    return $rc
}

gateway_healthy() { curl -fsS -m 5 -o /dev/null "http://127.0.0.1:$GW_PORT/health"; }

remote_md5() { curl -fsSL -m 30 -A "$UA" "$OSM_URL.md5" 2>/dev/null | awk 'NR==1 && $1 ~ /^[0-9a-f]{32}$/ {print $1}'; }

make_rollback_copy() {
    rm -rf "$ROLLBACK.new"
    local need_kb free_kb
    need_kb=$(du -sk "$DATA/tiles" "$DATA/valhalla" "$DATA/build-info.json" "$SRC"/osm.pbf* "$SRC"/photon-dump* 2>/dev/null | awk '{s+=$1} END {print s+0}')
    free_kb=$(df -Pk "$NAV_STATE_DIR" | awk 'NR==2 {print $4}')
    if (( free_kb - need_kb < ROLLBACK_MIN_FREE_GB * 1024 * 1024 )); then
        nav_log warn "not enough disk for a rollback copy; continuing without one" need_mb="$((need_kb / 1024))" free_mb="$((free_kb / 1024))"
        rm -rf "$ROLLBACK"
        return 0
    fi
    mkdir -p "$ROLLBACK.new/sources"
    cp -a "$DATA/tiles" "$DATA/valhalla" "$DATA/build-info.json" "$ROLLBACK.new/"
    cp -a "$SRC"/osm.pbf* "$SRC"/photon-dump* "$ROLLBACK.new/sources/"
    echo "tiles valhalla build-info sources" > "$ROLLBACK.new/COMPONENTS"
    date -u +%Y-%m-%dT%H:%M:%SZ > "$ROLLBACK.new/CREATED"
    rm -rf "$ROLLBACK"
    mv "$ROLLBACK.new" "$ROLLBACK"
    nav_log info "rollback copy ready" dir="$ROLLBACK" mb="$((need_kb / 1024))"
}

add_photon_to_rollback() {
    [[ -d "$ROLLBACK" ]] || return 0
    cp -a "$DATA/photon" "$ROLLBACK/photon"
    echo "tiles valhalla build-info sources photon" > "$ROLLBACK/COMPONENTS"
    nav_log info "photon index added to the rollback copy"
}

restore_sources() {
    [[ -d "$ROLLBACK/sources" ]] || return 0
    rm -f "$SRC"/osm.pbf "$SRC"/osm.pbf.* "$SRC"/photon-dump "$SRC"/photon-dump.*
    cp -a "$ROLLBACK/sources/." "$SRC/"
    nav_log info "resolved sources restored from the rollback copy"
}

# --empty-aux-cache: move the auxiliary downloads and tools aside (a rename on the same filesystem).
AUX_MOVED=0
empty_aux_cache() {
    if [[ -e "$AUX_ASIDE" ]]; then
        nav_die "an earlier --empty-aux-cache run left $AUX_ASIDE behind; put it back first (RUNBOOK.md section 7)" dir="$AUX_ASIDE"
    fi
    mkdir -p "$AUX_ASIDE/sources"
    AUX_MOVED=1
    local f n=0 kb
    kb=$(du -sk "$DATA/tools" 2>/dev/null | cut -f1 || echo 0)
    for f in "$SRC"/*; do
        [[ -e "$f" ]] || continue
        case "$(basename "$f")" in osm.pbf|osm.pbf.*|photon-dump|photon-dump.*) continue ;; esac
        kb=$(( kb + $(du -sk "$f" | cut -f1) ))
        mv "$f" "$AUX_ASIDE/sources/"
        n=$((n + 1))
    done
    if [[ -d "$DATA/tools" ]]; then mv "$DATA/tools" "$AUX_ASIDE/tools"; fi
    nav_log info "auxiliary cache emptied; every auxiliary file and tool is downloaded again" \
        moved_source_files="$n" moved_mb="$((kb / 1024))" aside="$AUX_ASIDE"
}

# Put the moved files back (failed run). Fresh partial downloads are replaced by the known-good copies.
restore_aux_cache() {
    [[ $AUX_MOVED -eq 1 && -d "$AUX_ASIDE" ]] || return 0
    local f
    for f in "$AUX_ASIDE/sources"/*; do
        [[ -e "$f" ]] || continue
        rm -rf "${SRC:?}/$(basename "$f")"
        mv "$f" "$SRC/"
    done
    if [[ -d "$AUX_ASIDE/tools" ]]; then
        rm -rf "$DATA/tools"
        mv "$AUX_ASIDE/tools" "$DATA/tools"
    fi
    rm -rf "$AUX_ASIDE"
    AUX_MOVED=0
    nav_log info "auxiliary cache restored after the failed run"
}

drop_aux_aside() {
    [[ $AUX_MOVED -eq 1 ]] || return 0
    rm -rf "$AUX_ASIDE"
    AUX_MOVED=0
    nav_log info "old auxiliary cache deleted; the fresh downloads are in use"
}

rollback_and_fail() {
    local why=$1
    nav_log error "rebuild failed" step="$why"
    restore_aux_cache || nav_log error "could not restore the auxiliary cache" aside="$AUX_ASIDE"
    if [[ "$AUTO_ROLLBACK" == 1 && -d "$ROLLBACK" ]]; then
        "$NAV_BIN/nav-rollback-data.sh" --locked || nav_log error "automatic rollback failed; see RUNBOOK.md 'Incidents'"
    else
        restore_sources || true
        nav_compose up -d --no-deps --wait valhalla photon || true
    fi
    exit 1
}

# Any other exit while the cache is aside (error, signal) puts it back as well.
trap 'restore_aux_cache || true' EXIT
trap 'exit 130' INT TERM
if [[ $EMPTY_AUX -eq 0 && -e "$AUX_ASIDE" ]]; then
    nav_log warn "leftover auxiliary cache from an interrupted --empty-aux-cache run; not used (delete it to free disk)" dir="$AUX_ASIDE"
fi

# ---------------------------------------------------------------- 1. preflight
code=$(curl -sS -o /dev/null -I -L -m 30 -A "$UA" -w '%{http_code}' "$OSM_URL" || true)
[[ "$code" == 200 ]] || nav_die "preflight: OSM source not reachable; rebuild skipped, old data keeps serving" url="$OSM_URL" http_status="$code"
free=$(nav_free_gb "$DATA")
(( free >= MIN_FREE_GB )) || nav_die "preflight: not enough free disk; rebuild skipped" free_gb="$free" min_gb="$MIN_FREE_GB"
gateway_healthy || nav_die "preflight: gateway /health failed; rebuild skipped (see RUNBOOK.md 'Incidents')"
nav_log info "preflight passed" url="$OSM_URL" free_gb="$free"

# ---------------------------------------------------------------- 2. unchanged source?
md5=""
if [[ "$SKIP_UNCHANGED" == 1 || "$OSM_URL" == *geofabrik* ]]; then md5=$(remote_md5 || true); fi
if [[ $FORCE -eq 0 && "$SKIP_UNCHANGED" == 1 && -n "$md5" && -s "$NAV_STATE_DIR/osm.md5" && "$md5" == "$(cat "$NAV_STATE_DIR/osm.md5")" ]]; then
    nav_log info "source unchanged since the last good build; build skipped" md5="$md5"
    rc=0
    smoke || rc=1
    data_age_ok || rc=1
    if [[ $rc -eq 0 && $HEARTBEAT -eq 1 ]]; then nav_heartbeat UPTIME_PUSH_URL_REBUILD "unchanged, smoke ok"; fi
    exit $rc
fi

# ---------------------------------------------------------------- 3. rollback copy
make_rollback_copy
if [[ $EMPTY_AUX -eq 1 ]]; then empty_aux_cache; fi
fetch_env=()
if [[ $FORCE -eq 1 ]]; then
    # Full rebuild: the builders see no complete artefact and rebuild; the served files stay until the swap.
    rm -f "$DATA/tiles/.complete" "$DATA/valhalla/.complete"
    fetch_env=(-e FETCH_ALL_TOOLS=1)
    nav_log info "forced full rebuild (tiles, routing graph, search index)" empty_aux_cache="$EMPTY_AUX"
fi

# ---------------------------------------------------------------- 4. build (services keep serving)
old_dump_sha=$(sed -n 's/^fingerprint=dump=\([0-9a-f]*\) .*/\1/p' "$DATA/photon/.complete" 2>/dev/null || true)
rm -f "$SRC"/osm.pbf "$SRC"/osm.pbf.* "$SRC"/photon-dump "$SRC"/photon-dump.*
tb=$(date +%s)
nav_compose run --rm --no-deps "${fetch_env[@]}" data-fetch fetch || rollback_and_fail "download (data-fetch)"
if [[ -n "$md5" ]]; then
    got=$(md5sum "$SRC/osm.pbf" | cut -d' ' -f1)
    # Geofabrik may publish a new file between the .md5 and the .pbf request: re-read the .md5 once.
    [[ "$got" == "$md5" ]] || md5=$(remote_md5 || true)
    [[ "$got" == "$md5" ]] || rollback_and_fail "md5 of the downloaded extract does not match $OSM_URL.md5"
    nav_log info "osm extract md5 verified" md5="$md5"
fi

nav_compose run --rm --no-deps tiles-build & pid_tiles=$!
nav_compose run --rm --no-deps valhalla-build & pid_valhalla=$!
photon_changed=0
photon_down_s=0
if [[ $FORCE -eq 1 || "$(cat "$SRC/photon-dump.sha256")" != "$old_dump_sha" ]]; then
    photon_changed=1
    nav_log info "photon stops for the re-import" reason="$([[ $FORCE -eq 1 ]] && echo forced || echo 'dump changed')"
    tp=$(date +%s)
    nav_compose stop photon
    add_photon_to_rollback
    rm -f "$DATA/photon/.complete"
fi
build_rc=0
nav_compose run --rm --no-deps photon-import || build_rc=1
if [[ $build_rc -eq 0 && $photon_changed -eq 1 ]]; then
    # Search comes back as soon as its index is ready, not after the (longer) tile build.
    nav_compose up -d --no-deps --wait photon || build_rc=1
    photon_down_s=$(( $(date +%s) - tp ))
    nav_log info "photon serving the new index" search_downtime_seconds="$photon_down_s"
fi
wait "$pid_tiles" || build_rc=1
wait "$pid_valhalla" || build_rc=1
[[ $build_rc -eq 0 ]] || rollback_and_fail "build (tiles-build, valhalla-build or photon-import)"
nav_compose run --rm --no-deps build-info || rollback_and_fail "build-info"
build_s=$(( $(date +%s) - tb ))

# ---------------------------------------------------------------- 5. switch to the new data
tv=$(date +%s)
nav_compose up -d --no-deps --wait --force-recreate valhalla || rollback_and_fail "valhalla restart"
valhalla_down_s=$(( $(date +%s) - tv ))
nav_log info "services switched to the new data" build_seconds="$build_s" \
    route_downtime_seconds="$valhalla_down_s" search_downtime_seconds="$photon_down_s" tiles_downtime_seconds=0

# ---------------------------------------------------------------- 6-8. verify, record, heartbeat
smoke || rollback_and_fail "smoke after rebuild"
drop_aux_aside
data_age_ok || { nav_log error "data is older than allowed; kept (it passed smoke), no heartbeat"; exit 1; }
[[ -n "$md5" ]] && echo "$md5" > "$NAV_STATE_DIR/osm.md5"
python3 - "$NAV_STATE_DIR/last-rebuild.json" <<PY
import json, sys
json.dump({"finished_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)", "total_seconds": $(( $(date +%s) - T0 )),
           "build_seconds": $build_s, "route_downtime_seconds": $valhalla_down_s,
           "search_downtime_seconds": $photon_down_s, "tiles_downtime_seconds": 0,
           "photon_reimported": bool($photon_changed), "forced": bool($FORCE), "empty_aux_cache": bool($EMPTY_AUX),
           "osm_url": "$OSM_URL", "osm_md5": "$md5"},
          open(sys.argv[1], "w"), indent=2)
PY
nav_log info "rebuild finished" total_seconds="$(( $(date +%s) - T0 ))" record="$NAV_STATE_DIR/last-rebuild.json"
if [[ $HEARTBEAT -eq 1 ]]; then nav_heartbeat UPTIME_PUSH_URL_REBUILD "rebuilt in $(( $(date +%s) - T0 )) s, smoke ok"; fi

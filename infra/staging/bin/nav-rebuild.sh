#!/usr/bin/env bash
# NAV-006 staging: the daily rebuild started by nav-rebuild.service / nav-rebuild.timer (19:30 UTC = 03:30
# Asia/Ulaanbaatar). Owner: backend-engineer. Since NAV-006 this is a thin wrapper around the slot pipeline
# (backend/pipeline/nav_pipeline.py, ADR-0014): the in-place NAV-008 rebuild and nav-rollback-data.sh are gone,
# so this is the only path that changes served data (AC 43).
#   sudo infra/staging/bin/nav-rebuild.sh        # what the timer runs: the scheduled entry point, no overrides
# Manual runs and overrides: sudo make -C /opt/nav/backend rebuild [FORCE=1] [ACCEPT_SIZE_DROP=1] [ACCEPT_ROUTE_CHANGE=1]
#
# Exit code = the pipeline's (RUNBOOK.md NAV-006 section). The Uptime Kuma rebuild heartbeat
# (UPTIME_PUSH_URL_REBUILD, 26 h) is pushed only after exit 0 = success or a healthy "skipped: unchanged", so a
# failed, skipped or stale run is noticed (AC 32). SIGTERM/SIGINT (systemctl stop, reboot) is forwarded to the
# pipeline, which cleans up within 60 s (nav-rebuild.service: KillMode=mixed, TimeoutStopSec=90s).
# shellcheck source=nav-env.sh
source "$(dirname "$(readlink -f "$0")")/nav-env.sh"
[[ $# -eq 0 ]] || nav_die "nav-rebuild.sh takes no arguments since NAV-006; manual runs and overrides: sudo make -C $NAV_ROOT/backend rebuild [FORCE=1]"
nav_require_root

child=""
forward() { if [[ -n "$child" ]]; then kill -TERM "$child" 2>/dev/null || true; fi; }
trap forward TERM INT
"$NAV_BIN/nav-pipeline" rebuild --scheduled &
child=$!
while true; do
    rc=0
    wait "$child" || rc=$?
    # wait returns early (> 128) when a forwarded signal interrupted it; keep waiting for the pipeline's cleanup.
    if (( rc > 128 )) && kill -0 "$child" 2>/dev/null; then continue; fi
    break
done
if [[ $rc -eq 0 ]]; then
    result=$(python3 -c 'import json,sys; print((json.load(open(sys.argv[1])).get("last_run") or {}).get("result",""))' \
        "$(nav_data_root)/state.json" 2>/dev/null || true)
    nav_heartbeat UPTIME_PUSH_URL_REBUILD "rebuild ${result:-ok}" || true
fi
exit "$rc"

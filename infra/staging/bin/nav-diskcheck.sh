#!/usr/bin/env bash
# NAV-008 staging: disk usage heartbeat (deployment-staging.md §9, AC 18). Owner: backend-engineer.
# Run every 5 min by nav-diskcheck.timer. Pushes to UPTIME_PUSH_URL_DISK ONLY while usage of every checked
# filesystem is below DISK_ALERT_PERCENT (default 85). No push for 15 min (disk full or host down) = alert.
#   infra/staging/bin/nav-diskcheck.sh            # exit 0 = below the limit (and push sent if configured)
# shellcheck source=nav-env.sh
source "$(dirname "$(readlink -f "$0")")/nav-env.sh"
nav_require_env_file

limit=$(nav_env_get DISK_ALERT_PERCENT 85)
worst=0
report=""
# / plus the filesystems holding the NAV-006 data root and Docker, de-duplicated by device.
declare -A seen=()
for path in / "$(nav_data_root)" /var/lib/docker; do
    [[ -e "$path" ]] || continue
    dev=$(df -P "$path" | awk 'NR==2 {print $1}')
    if [[ -n "${seen[$dev]:-}" ]]; then continue; fi
    seen[$dev]=1
    pct=$(nav_used_pct "$path")
    report+="${path}=${pct}% "
    if (( pct > worst )); then worst=$pct; fi
done

if (( worst < limit )); then
    nav_heartbeat UPTIME_PUSH_URL_DISK "disk ok: ${report% }"
    exit 0
fi
nav_log error "disk usage at or above the alert limit; heartbeat withheld" usage="${report% }" limit_percent="$limit"
exit 1

#!/usr/bin/env bash
# NAV-008 staging: test the Uptime Kuma push URLs from this host (RUNBOOK.md section 10). Owner: backend-engineer.
#   sudo infra/staging/bin/nav-push-test.sh                  # every configured push URL (disk, rebuild, backup)
#   sudo infra/staging/bin/nav-push-test.sh disk backup      # only these
# Each push goes to https://<OPS_HOST>/api/push/<token> through the ops VM's push-only Caddy, with the message
# "push test". The monitor turns green in the Kuma UI. The next real heartbeat is due within the monitor's interval
# (15 min disk, 26 h rebuild and backup), so a test push never hides a real failure for longer than one interval.
# Exit 0 only if every tested URL got Uptime Kuma's {"ok":true}. The URLs (tokens) are never printed.
# shellcheck source=nav-env.sh
source "$(dirname "$(readlink -f "$0")")/nav-env.sh"
nav_require_env_file

declare -A KEYS=([disk]=UPTIME_PUSH_URL_DISK [rebuild]=UPTIME_PUSH_URL_REBUILD [backup]=UPTIME_PUSH_URL_BACKUP)
names=("$@")
[[ ${#names[@]} -gt 0 ]] || names=(disk rebuild backup)

[[ -n "$(nav_env_get OPS_HOST)" ]] || nav_log warn "OPS_HOST is empty in infra/staging/.env; push URL hosts are not cross-checked"
tested=0
failed=0
for n in "${names[@]}"; do
    key=${KEYS[$n]:-}
    [[ -n "$key" ]] || nav_die "unknown monitor (disk | rebuild | backup)" monitor="$n"
    if [[ -z "$(nav_env_get "$key")" ]]; then
        nav_log warn "not configured; skipped" monitor="$n" key="$key"
        continue
    fi
    tested=$((tested + 1))
    if nav_heartbeat "$key" "push test from $(nav_env_get BACKUP_HOST_TAG nav-staging)"; then
        nav_log info "push test ok: the monitor shows green in Uptime Kuma" monitor="$n"
    else
        failed=$((failed + 1))
    fi
done
[[ $tested -gt 0 ]] || nav_die "no push URL configured (UPTIME_PUSH_URL_DISK, _REBUILD, _BACKUP in infra/staging/.env)"
nav_log "$([[ $failed -eq 0 ]] && echo info || echo error)" "push test finished" tested="$tested" failed="$failed"
[[ $failed -eq 0 ]]

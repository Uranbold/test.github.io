#!/usr/bin/env bash
# NAV-008 staging: memory sampler for AC 16 (the `make stats` equivalent, every <= 3 s). Owner: backend-engineer.
#
#   sudo infra/staging/bin/nav-stats-sampler.sh -- infra/staging/bin/nav-rebuild.sh --empty-aux-cache
#        samples while the command runs, then 5 steady-state samples; exits with the command's exit code
#   sudo infra/staging/bin/nav-stats-sampler.sh
#        samples until Ctrl-C (use in a second shell during a first deploy), then prints the summary
#   sudo infra/staging/bin/nav-stats-sampler.sh --summary /var/lib/nav/stats/<file>.tsv
#        summary of an existing sample file again
# Options: --interval S (default 2; 0.5-3), --out FILE (default $NAV_STATE_DIR/stats/stats-<UTC time>.tsv).
#
# Records per sample: host RAM in use and, per container of the compose project, memory as `docker stats` shows it
# (cgroup usage minus inactive file cache). Only service names and sizes: no client data, no addresses.
# Summary (<out>.summary.json): peak_build_mb, steady_mb, ac16_ratio = (peak_build + steady) / RAM (pass <= 0.70),
# free_disk_gb (pass >= 50), max_gap_s (<= 3). Copy the summary into RUNBOOK.md section 15 and the QA checklist.
# shellcheck source=nav-env.sh
source "$(dirname "$(readlink -f "$0")")/nav-env.sh"

nav_require_root
interval=2
out=""
summary=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --interval) interval=${2:?}; shift 2 ;;
        --out) out=${2:?}; shift 2 ;;
        --summary) summary=${2:?}; shift 2 ;;
        --) shift; break ;;
        -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
        *) nav_die "unknown argument (commands go after --)" arg="$1" ;;
    esac
done

project=$(nav_project)
data_dir="$NAV_ROOT/backend/data"
py="$NAV_BIN/nav_stats_sampler.py"
if [[ -n "$summary" ]]; then
    exec python3 "$py" --project "$project" --data-dir "$data_dir" --out "$summary" --summary
fi
mkdir -p "$NAV_STATE_DIR/stats"
out=${out:-$NAV_STATE_DIR/stats/stats-$(date -u +%Y%m%dT%H%M%SZ).tsv}
exec python3 "$py" --project "$project" --data-dir "$data_dir" --out "$out" --interval "$interval" -- "$@"

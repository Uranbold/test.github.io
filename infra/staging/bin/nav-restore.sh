#!/usr/bin/env bash
# NAV-008 staging: restore the configuration backup (deployment-staging.md §10, AC 20). Owner: backend-engineer.
#
#   sudo infra/staging/bin/nav-restore.sh                    # latest snapshot -> /var/tmp/nav-restore-<ts> (inspect only)
#   sudo infra/staging/bin/nav-restore.sh --in-place         # latest snapshot -> / on a NEW host (restore drill)
#   sudo infra/staging/bin/nav-restore.sh --in-place --snapshot <id>
#
# Preconditions for --in-place (RUNBOOK.md "Restore drill"): bootstrap.sh has run, the repository is checked out
# at the pinned tag in $NAV_ROOT, /root/.ssh/config reaches the ops VM, and RESTIC_REPOSITORY + the password file
# are in place. Because infra/staging/.env is itself in the backup, pass the repository on the command line:
#   sudo RESTIC_REPOSITORY=sftp:nav-backup@<ops-host>:/srv/restic/nav-staging infra/staging/bin/nav-restore.sh --in-place
# After an in-place restore: systemd units are reloaded, UFW and sshd reloaded, Docker restarted if daemon.json
# changed. Then run deploy.sh <tag> (first data build about 8-10 min) and the smoke suite.
# shellcheck source=nav-env.sh
source "$(dirname "$(readlink -f "$0")")/nav-env.sh"

IN_PLACE=0
SNAP=latest
while [[ $# -gt 0 ]]; do
    case "$1" in
        --in-place) IN_PLACE=1 ;;
        --snapshot) SNAP=$2; shift ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        *) nav_die "unknown argument" arg="$1" ;;
    esac
    shift
done

nav_require_root
command -v restic >/dev/null || nav_die "restic is not installed (run bootstrap.sh first)"
export RESTIC_REPOSITORY="${RESTIC_REPOSITORY:-$(nav_env_get RESTIC_REPOSITORY)}"
export RESTIC_PASSWORD_FILE="${RESTIC_PASSWORD_FILE:-$(nav_env_get RESTIC_PASSWORD_FILE /root/.config/nav/restic-password)}"
[[ -n "$RESTIC_REPOSITORY" ]] || nav_die "set RESTIC_REPOSITORY (environment or infra/staging/.env)"
[[ -s "$RESTIC_PASSWORD_FILE" ]] || nav_die "restic password file missing" file="$RESTIC_PASSWORD_FILE"

restic snapshots --tag nav-staging
if [[ $IN_PLACE -eq 0 ]]; then
    target="/var/tmp/nav-restore-$(date -u +%Y%m%dT%H%M%SZ)"
    restic restore "$SNAP" --tag nav-staging --target "$target"
    chmod 700 "$target"
    nav_log info "restored for inspection (nothing on the host was changed)" target="$target"
    exit 0
fi

# Docker volumes must exist before their mountpoints are restored into.
project=$(nav_project)
for vol in caddy_data caddy_config; do
    command -v docker >/dev/null || { nav_log warn "docker not installed; Caddy volumes not created"; break; }
    docker volume inspect "${project}_${vol}" >/dev/null 2>&1 || \
        docker volume create --label "com.docker.compose.project=${project}" --label "com.docker.compose.volume=${vol}" "${project}_${vol}" >/dev/null
done
daemon_before=$(sha256sum /etc/docker/daemon.json 2>/dev/null || true)

t0=$(date +%s)
restic restore "$SNAP" --tag nav-staging --target /
nav_log info "snapshot restored in place" seconds="$(( $(date +%s) - t0 ))"

chown -R nav-ops:nav-ops /home/nav-ops/.ssh 2>/dev/null || true
if [[ ! -d /run/systemd/system ]]; then
    nav_log warn "systemd is not running here; reloads skipped (files restored only)"
else
    systemctl daemon-reload
    if sshd -t; then systemctl reload ssh || systemctl reload sshd || true; else nav_log error "sshd -t failed after restore; sshd NOT reloaded (fix before closing this session)"; fi
    if command -v ufw >/dev/null; then ufw reload || true; fi
    if [[ "$(sha256sum /etc/docker/daemon.json 2>/dev/null || true)" != "$daemon_before" ]]; then systemctl restart docker; fi
    for t in nav-rebuild.timer nav-diskcheck.timer nav-backup.timer nav-backup-check.timer; do
        if [[ -f "/etc/systemd/system/$t" ]]; then systemctl enable --now "$t"; fi
    done
fi
nav_log info "restore finished; next: sudo infra/staging/bin/deploy.sh <tag> (see RUNBOOK.md 'Restore drill')"

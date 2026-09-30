#!/usr/bin/env bash
# NAV-008 staging: off-host configuration backup with restic over SFTP (deployment-staging.md §10, AC 19).
# Owner: backend-engineer.
#
#   sudo infra/staging/bin/nav-backup.sh init        # once: create the encrypted repository on the ops VM
#   sudo infra/staging/bin/nav-backup.sh             # = backup: snapshot + forget/prune (nav-backup.timer, daily)
#   sudo infra/staging/bin/nav-backup.sh check       # restic check (nav-backup-check.timer, weekly)
#   sudo infra/staging/bin/nav-backup.sh snapshots   # list snapshots
#   sudo infra/staging/bin/nav-backup.sh paths       # print the path list that would be backed up
#
# Config (infra/staging/.env): RESTIC_REPOSITORY (e.g. sftp:nav-backup@<ops-host>:/srv/restic/nav-staging),
# RESTIC_PASSWORD_FILE (root-only file OUTSIDE git, default /root/.config/nav/restic-password),
# optional UPTIME_PUSH_URL_BACKUP. SSH for the sftp: backend comes from /root/.ssh/config (RUNBOOK.md).
# NOT backed up: backend/data/ (rebuildable from OSM, AC 15), the rollback copy, container logs, the git
# checkout (it is in git), and the restic password and SSH private key themselves.
# shellcheck source=nav-env.sh
source "$(dirname "$(readlink -f "$0")")/nav-env.sh"

mode=${1:-backup}
nav_require_root
nav_require_env_file
command -v restic >/dev/null || nav_die "restic is not installed (bootstrap.sh installs it)"

RESTIC_REPOSITORY=$(nav_env_get RESTIC_REPOSITORY)
RESTIC_PASSWORD_FILE=$(nav_env_get RESTIC_PASSWORD_FILE /root/.config/nav/restic-password)
export RESTIC_REPOSITORY RESTIC_PASSWORD_FILE
[[ -n "$RESTIC_REPOSITORY" ]] || nav_die "RESTIC_REPOSITORY is empty in infra/staging/.env"
[[ -s "$RESTIC_PASSWORD_FILE" ]] || nav_die "restic password file missing or empty" file="$RESTIC_PASSWORD_FILE"
perm=$(stat -c '%a %U' "$RESTIC_PASSWORD_FILE")
[[ "$perm" == "600 root" || "$perm" == "400 root" ]] || nav_die "restic password file must be owned by root with mode 600" file="$RESTIC_PASSWORD_FILE" actual="$perm"
case "$RESTIC_PASSWORD_FILE" in "$NAV_ROOT"/*) nav_die "the restic password file must live outside the git checkout" ;; esac

project=$(nav_project)

# Everything on the host that is not in git and not rebuildable (AC 19). Missing paths are skipped.
backup_paths() {
    local vol
    printf '%s\n' \
        "$NAV_ENV_FILE" \
        "$NAV_ROOT/backend/.env" \
        /etc/ufw \
        /etc/default/ufw \
        /etc/ssh/sshd_config.d/00-nav-hardening.conf \
        /etc/sudoers.d/90-nav-ops \
        /home/nav-ops/.ssh/authorized_keys \
        /etc/docker/daemon.json \
        /etc/systemd/journald.conf.d/00-nav.conf \
        /etc/sysctl.d/60-nav.conf \
        /etc/default/sysstat \
        /etc/sysstat/sysstat \
        /etc/apt/apt.conf.d/20auto-upgrades \
        /etc/apt/apt.conf.d/52nav-unattended-upgrades \
        /root/.ssh/config \
        "$NAV_STATE_DIR"
    compgen -G '/etc/systemd/system/nav-*.service' || true
    compgen -G '/etc/systemd/system/nav-*.timer' || true
    # Caddy: ACME account key + certificates (caddy_data) and the autosaved, rendered config (caddy_config).
    command -v docker >/dev/null || return 0
    for vol in caddy_data caddy_config; do
        docker volume inspect "${project}_${vol}" --format '{{.Mountpoint}}' 2>/dev/null || true
    done
}

existing_paths() { local p; while IFS= read -r p; do [[ -n "$p" && -e "$p" ]] && printf '%s\n' "$p"; done < <(backup_paths); return 0; }

case "$mode" in
    init)
        restic snapshots >/dev/null 2>&1 && nav_die "repository already initialised" repository="$RESTIC_REPOSITORY"
        restic init
        nav_log info "restic repository initialised" ;;
    paths)
        existing_paths ;;
    backup)
        t0=$(date +%s)
        list=$(mktemp)
        trap 'rm -f "$list"' EXIT
        existing_paths > "$list"
        restic backup --files-from "$list" --tag nav-staging --host "$(nav_env_get BACKUP_HOST_TAG nav-staging)" \
            --exclude "$NAV_ROOT/backend/data" --exclude "$NAV_STATE_DIR/rollback" --exclude "$NAV_STATE_DIR/rollback.new" \
            --exclude "$RESTIC_PASSWORD_FILE" --one-file-system --no-scan -q
        restic forget --tag nav-staging --keep-daily 7 --keep-weekly 4 --prune -q
        nav_log info "backup finished" seconds="$(( $(date +%s) - t0 ))" paths="$(wc -l < "$list")"
        nav_heartbeat UPTIME_PUSH_URL_BACKUP "backup ok" ;;
    check)
        restic check --read-data-subset="$(nav_env_get RESTIC_CHECK_SUBSET 10%)"
        nav_log info "restic check passed" ;;
    snapshots)
        restic snapshots --tag nav-staging ;;
    *)
        nav_die "unknown mode (init | backup | check | snapshots | paths)" mode="$mode" ;;
esac

#!/usr/bin/env bash
# NAV-008 staging: install/refresh the systemd units from infra/staging/systemd/ (idempotent). Owner: backend-engineer.
#   sudo infra/staging/bin/install-units.sh
# @NAV_ROOT@ in the unit files is replaced by the checkout path. Unchanged units are not touched; a change
# triggers one daemon-reload. All timers are enabled and started.
# shellcheck source=nav-env.sh
source "$(dirname "$(readlink -f "$0")")/nav-env.sh"
nav_require_root

dest=${NAV_SYSTEMD_DIR:-/etc/systemd/system}
changed=0
timers=()
for src in "$NAV_STAGING"/systemd/nav-*.service "$NAV_STAGING"/systemd/nav-*.timer; do
    name=$(basename "$src")
    tmp=$(mktemp)
    sed "s|@NAV_ROOT@|$NAV_ROOT|g" "$src" > "$tmp"
    if ! cmp -s "$tmp" "$dest/$name"; then
        install -m 0644 -o root -g root "$tmp" "$dest/$name"
        nav_log info "unit installed" unit="$name"
        changed=1
    fi
    rm -f "$tmp"
    [[ "$name" == *.timer ]] && timers+=("$name")
done
if [[ ! -d /run/systemd/system ]]; then
    nav_log warn "systemd is not running here; units copied, not enabled" dir="$dest"
    exit 0
fi
if [[ $changed -eq 1 ]]; then systemctl daemon-reload; fi
for t in "${timers[@]}"; do
    systemctl is-enabled --quiet "$t" 2>/dev/null || systemctl enable --quiet "$t"
    systemctl is-active --quiet "$t" || systemctl start "$t"
done
nav_log info "systemd units up to date" changed="$changed" timers="${timers[*]}"

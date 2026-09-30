#!/usr/bin/env bash
# NAV-008 host baseline for Ubuntu 24.04 LTS (deployment-staging.md §3-§4, B1). Owner: backend-engineer.
# Idempotent: a second run reports "changes=0" and touches nothing. Provider-neutral (any KVM VPS).
#
#   sudo infra/staging/bootstrap/bootstrap.sh --ops-key-file /root/nav-ops.pub            # staging host
#   sudo infra/staging/bootstrap/bootstrap.sh --copy-root-keys                            # use root's authorized_keys
#   sudo infra/staging/bootstrap/bootstrap.sh --role ops --ops-key-file /root/nav-ops.pub \
#        --extra-allow-users nav-backup                                                   # ops VM (SSH only)
#
# Options
#   --ops-key-file FILE    PUBLIC key(s) of the named operator for the nav-ops account (added if missing).
#   --copy-root-keys       also copy the public keys in /root/.ssh/authorized_keys (keys set in the provider
#                          panel at VM creation) to nav-ops.
#   --role staging|ops     staging (default): UFW 22/80/443. ops: UFW 22 only (Uptime Kuma stays on 127.0.0.1).
#   --extra-allow-users "a b"   more accounts for sshd AllowUsers (ops VM: the restic SFTP account).
#   --ssh-port N           SSH port kept open in UFW (default 22; sshd's own port is not changed).
#   --docker-version V     exact docker-ce apt version to install (default: newest in Docker's repo, then held).
#   --skip LIST            comma list of steps to skip: packages,user,ssh,unattended,docker,journald,ufw,swap,
#                          sysstat,timezone,repo (for partial runs; the full baseline needs all of them).
#
# What it sets: UTC clock; nav-ops (sudo, key-only, no password); sshd drop-in 00-nav-hardening.conf (keys
# only, no root, AllowUsers); unattended-upgrades (security pocket, reboot 21:30 UTC); Docker Engine + Compose
# plugin from Docker's apt repository (held) with log rotation; journald 1 GB / 14 days; UFW deny-in with
# limit 22, allow 80/443 (IPv4+IPv6), logging off; 4 GB swap, swappiness 10; sysstat with 28 days history;
# /opt/nav owned by nav-ops. Inside a container (for CI) kernel-level steps are reported as skipped.
# Never prints key material (only fingerprints). Never sets or asks for passwords.
set -Eeuo pipefail
umask 022

ROLE=staging
OPS_KEY_FILE=""
COPY_ROOT_KEYS=0
EXTRA_ALLOW=""
SSH_PORT=22
DOCKER_VERSION=""
SKIP=","
ADMIN=nav-ops
NAV_DIR=${NAV_DIR:-/opt/nav}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --ops-key-file) OPS_KEY_FILE=$2; shift ;;
        --copy-root-keys) COPY_ROOT_KEYS=1 ;;
        --role) ROLE=$2; shift ;;
        --extra-allow-users) EXTRA_ALLOW=$2; shift ;;
        --ssh-port) SSH_PORT=$2; shift ;;
        --docker-version) DOCKER_VERSION=$2; shift ;;
        --skip) SKIP=",$2,"; shift ;;
        -h|--help) sed -n '2,31p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
    shift
done

CHANGES=0
log() { printf '{"ts":"%s","job":"bootstrap","level":"%s","msg":"%s"%s}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" "${3:+,$3}"; }
changed() { CHANGES=$((CHANGES + 1)); log info "changed" "\"what\":\"$1\""; }
ok() { log info "ok" "\"what\":\"$1\""; }
die() { log error "$1"; exit 1; }
skip() { [[ "$SKIP" == *",$1,"* ]]; }
in_container() {
    [[ -f /.dockerenv || -f /run/.containerenv ]] && return 0
    command -v systemd-detect-virt >/dev/null && systemd-detect-virt --container --quiet && return 0
    return 1
}
has_systemd() { [[ -d /run/systemd/system ]]; }

# put_file <path> <mode> <content>: write only if different. Returns 0 if changed, 1 if unchanged.
put_file() {
    local path=$1 mode=$2 content=$3 tmp
    tmp=$(mktemp)
    printf '%s' "$content" > "$tmp"
    if [[ -f "$path" ]] && cmp -s "$tmp" "$path" && [[ "$(stat -c %a "$path")" == "$mode" ]]; then
        rm -f "$tmp"
        return 1
    fi
    mkdir -p "$(dirname "$path")"
    install -m "$mode" -o root -g root "$tmp" "$path"
    rm -f "$tmp"
    changed "$path"
    return 0
}

svc() {  # svc <action> <unit>: systemctl when systemd runs, otherwise report
    if has_systemd; then systemctl "$@"; else log info "skipped (no systemd)" "\"cmd\":\"systemctl $*\""; fi
}

[[ $EUID -eq 0 ]] || die "run as root (sudo)"
[[ "$ROLE" == staging || "$ROLE" == ops ]] || die "--role must be staging or ops"
[[ "$SSH_PORT" =~ ^[0-9]+$ ]] || die "--ssh-port must be a number"
[[ -z "$OPS_KEY_FILE" || ( -f "$OPS_KEY_FILE" && -r "$OPS_KEY_FILE" ) ]] || die "--ops-key-file is not a readable file: $OPS_KEY_FILE"
. /etc/os-release
[[ "${ID:-}" == ubuntu && "${VERSION_ID:-}" == 24.04 ]] || die "Ubuntu 24.04 LTS expected, found ${PRETTY_NAME:-unknown}"
export DEBIAN_FRONTEND=noninteractive
log info "bootstrap starting" "\"role\":\"$ROLE\",\"container\":$(in_container && echo true || echo false),\"systemd\":$(has_systemd && echo true || echo false)"

# ------------------------------------------------------------------ packages
APT_UPDATED=0
apt_update_once() { if [[ $APT_UPDATED -eq 0 ]]; then apt-get update -qq; APT_UPDATED=1; fi; }
ensure_pkgs() {
    local missing=() p
    for p in "$@"; do
        dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q 'ok installed$' || missing+=("$p")
    done
    if [[ ${#missing[@]} -gt 0 ]]; then
        apt_update_once
        apt-get install -y -qq --no-install-recommends "${missing[@]}" >/dev/null
        changed "packages: ${missing[*]}"
    fi
}
BASE_PKGS=(ca-certificates curl gnupg tzdata sudo openssh-server ufw unattended-upgrades sysstat restic
           git make python3 util-linux iproute2 openssl jq)
if ! skip packages; then
    ensure_pkgs "${BASE_PKGS[@]}"
    ok "base packages"
fi

# ------------------------------------------------------------------ time zone: UTC
if ! skip timezone; then
    if [[ "$(readlink /etc/localtime || true)" != /usr/share/zoneinfo/Etc/UTC ]]; then
        ln -sf /usr/share/zoneinfo/Etc/UTC /etc/localtime
        changed "/etc/localtime -> UTC"
    fi
    put_file /etc/timezone 644 $'Etc/UTC\n' || true
    ok "time zone UTC"
fi

# ------------------------------------------------------------------ admin account nav-ops (key only)
collect_keys() {
    local f line
    for f in "$@"; do
        [[ -n "$f" && -f "$f" && -r "$f" ]] || continue
        if grep -q 'PRIVATE KEY' "$f"; then die "$f contains a PRIVATE key. Give the PUBLIC key (.pub) only."; fi
        while IFS= read -r line || [[ -n "$line" ]]; do
            line=${line%$'\r'}
            [[ "$line" =~ ^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh.com|sk-ecdsa-sha2-nistp256@openssh.com)[[:space:]] ]] || continue
            printf '%s\n' "$line"
        done < "$f"
    done
}
if ! skip user; then
    if ! id "$ADMIN" >/dev/null 2>&1; then
        useradd --create-home --shell /bin/bash --groups sudo "$ADMIN"
        changed "user $ADMIN"
    fi
    if ! id -nG "$ADMIN" | tr ' ' '\n' | grep -qx sudo; then usermod -aG sudo "$ADMIN"; changed "$ADMIN in group sudo"; fi
    # No password at all (key-only): lock the password field if a password was ever set.
    if [[ "$(passwd -S "$ADMIN" | awk '{print $2}')" != L ]]; then passwd -l "$ADMIN" >/dev/null; changed "$ADMIN password locked"; fi
    # sudo without a password: the account has none (key-only SSH). See RUNBOOK.md "Access roles".
    if put_file /etc/sudoers.d/90-nav-ops 440 "# NAV-008: key-only admin account, no password exists to ask for.
$ADMIN ALL=(ALL:ALL) NOPASSWD:ALL
"; then visudo -cf /etc/sudoers.d/90-nav-ops >/dev/null || { rm -f /etc/sudoers.d/90-nav-ops; die "sudoers drop-in invalid"; }; fi
    home=$(getent passwd "$ADMIN" | cut -d: -f6)
    ak="$home/.ssh/authorized_keys"
    install -d -m 700 -o "$ADMIN" -g "$ADMIN" "$home/.ssh"
    [[ -f "$ak" ]] || { install -m 600 -o "$ADMIN" -g "$ADMIN" /dev/null "$ak"; changed "$ak created"; }
    src_files=("$OPS_KEY_FILE")
    [[ $COPY_ROOT_KEYS -eq 1 ]] && src_files+=(/root/.ssh/authorized_keys)
    while IFS= read -r key; do
        [[ -n "$key" ]] || continue
        kt=$(mktemp); printf '%s\n' "$key" > "$kt"
        fp=$(ssh-keygen -l -f "$kt" 2>/dev/null | awk '{print $2}') || true
        rm -f "$kt"
        [[ -n "$fp" ]] || die "an entry in the key file is not a valid public key"
        body=$(awk '{print $2}' <<<"$key")
        if ! grep -qF "$body" "$ak"; then
            printf '%s\n' "$key" >> "$ak"
            changed "authorized key $fp for $ADMIN"
        fi
    done < <(collect_keys "${src_files[@]}")
    [[ "$(stat -c '%a %U' "$ak")" == "600 $ADMIN" ]] || { chown "$ADMIN:$ADMIN" "$ak"; chmod 600 "$ak"; changed "$ak permissions"; }
    nkeys=$(grep -cE '^(ssh-|ecdsa-|sk-)' "$ak" || true)
    ok "$ADMIN account ($nkeys authorized key(s))"
fi

# ------------------------------------------------------------------ SSH hardening (drop-in that sorts first)
if ! skip ssh; then
    home=$(getent passwd "$ADMIN" | cut -d: -f6 || true)
    if [[ -z "$home" ]] || ! grep -qE '^(ssh-|ecdsa-|sk-)' "$home/.ssh/authorized_keys" 2>/dev/null; then
        die "refusing to harden sshd: $ADMIN has no authorized key yet (use --ops-key-file or --copy-root-keys), you would be locked out"
    fi
    allow="$ADMIN${EXTRA_ALLOW:+ $EXTRA_ALLOW}"
    # sshd keeps the FIRST value it reads; Ubuntu cloud images may ship 50-cloud-init.conf with
    # PasswordAuthentication yes, so this file must sort before it (deployment-staging.md §3).
    conf="# NAV-008 (deployment-staging.md §3). Managed by infra/staging/bootstrap/bootstrap.sh; do not edit by hand.
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
PubkeyAuthentication yes
AuthenticationMethods publickey
PermitEmptyPasswords no
AllowUsers $allow
X11Forwarding no
MaxAuthTries 3
LoginGraceTime 30
ClientAliveInterval 300
ClientAliveCountMax 2
"
    grep -qE '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config.d/\*\.conf' /etc/ssh/sshd_config \
        || die "/etc/ssh/sshd_config has no 'Include /etc/ssh/sshd_config.d/*.conf'; add it at the top first"
    mkdir -p /run/sshd
    if put_file /etc/ssh/sshd_config.d/00-nav-hardening.conf 644 "$conf"; then
        sshd -t || { rm -f /etc/ssh/sshd_config.d/00-nav-hardening.conf; die "sshd -t failed; drop-in removed"; }
        if has_systemd; then systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || systemctl restart ssh; fi
    fi
    eff=$(sshd -T -C user="$ADMIN",host=localhost,addr=127.0.0.1 2>/dev/null \
          | grep -E '^(passwordauthentication|kbdinteractiveauthentication|permitrootlogin|pubkeyauthentication|allowusers) ' | sort | tr '\n' ';')
    for want in "passwordauthentication no" "kbdinteractiveauthentication no" "permitrootlogin no" "pubkeyauthentication yes"; do
        [[ "$eff" == *"$want"* ]] || die "effective sshd setting differs from '$want' (sshd -T): $eff"
    done
    ok "sshd effective: $eff"
fi

# ------------------------------------------------------------------ unattended-upgrades (security pocket)
if ! skip unattended; then
    put_file /etc/apt/apt.conf.d/20auto-upgrades 644 'APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
' || true
    # 50unattended-upgrades (Ubuntu default) already allows only ${distro_codename}-security (+ ESM).
    put_file /etc/apt/apt.conf.d/52nav-unattended-upgrades 644 '// NAV-008: reboot, if an update needs it, at 21:30 UTC (05:30 Asia/Ulaanbaatar), after rebuild and backup.
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-WithUsers "true";
Unattended-Upgrade::Automatic-Reboot-Time "21:30";
Unattended-Upgrade::Remove-Unused-Kernel-Packages "true";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
' || true
    # shellcheck disable=SC2016  # literal apt placeholder, not a shell variable
    grep -q '${distro_codename}-security' /etc/apt/apt.conf.d/50unattended-upgrades || die "50unattended-upgrades lacks the security origin"
    if has_systemd; then
        for t in apt-daily.timer apt-daily-upgrade.timer; do
            systemctl is-enabled --quiet "$t" || { systemctl enable --now "$t"; changed "$t enabled"; }
        done
    fi
    ok "unattended-upgrades (security, reboot 21:30 UTC)"
fi

# ------------------------------------------------------------------ Docker Engine from Docker's apt repository
DOCKER_PKGS=(docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin)
if ! skip docker; then
    for p in docker.io docker-doc docker-compose docker-compose-v2 podman-docker containerd runc; do
        if dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q 'ok installed$'; then
            apt-get remove -y -qq "$p" >/dev/null; changed "removed conflicting package $p"
        fi
    done
    if [[ ! -s /etc/apt/keyrings/docker.asc ]]; then
        install -m 0755 -d /etc/apt/keyrings
        curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
        chmod a+r /etc/apt/keyrings/docker.asc
        changed "/etc/apt/keyrings/docker.asc"
    fi
    if put_file /etc/apt/sources.list.d/docker.sources 644 "Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-noble}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
"; then APT_UPDATED=0; fi
    missing=()
    for p in "${DOCKER_PKGS[@]}"; do
        dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q 'ok installed$' || missing+=("$p")
    done
    if [[ ${#missing[@]} -gt 0 ]]; then
        apt_update_once
        if [[ -n "$DOCKER_VERSION" ]]; then
            apt-get install -y -qq --allow-change-held-packages "docker-ce=$DOCKER_VERSION" "docker-ce-cli=$DOCKER_VERSION" \
                containerd.io docker-buildx-plugin docker-compose-plugin >/dev/null
        else
            apt-get install -y -qq --allow-change-held-packages "${DOCKER_PKGS[@]}" >/dev/null
        fi
        changed "docker packages: ${missing[*]}"
    fi
    # Engine upgrades are deliberate (snapshot first, RUNBOOK.md "Upgrades"): hold the packages.
    held=$(apt-mark showhold)
    for p in "${DOCKER_PKGS[@]}"; do
        grep -qx "$p" <<<"$held" || { apt-mark hold "$p" >/dev/null; changed "apt-mark hold $p"; }
    done
    if put_file /etc/docker/daemon.json 644 '{
  "log-driver": "local",
  "log-opts": { "max-size": "10m", "max-file": "5" },
  "live-restore": true
}
'; then svc restart docker || true; fi
    if has_systemd; then
        for u in docker.service containerd.service; do
            systemctl is-enabled --quiet "$u" || { systemctl enable --now "$u"; changed "$u enabled"; }
        done
    fi
    if getent group docker >/dev/null && id "$ADMIN" >/dev/null 2>&1 && ! id -nG "$ADMIN" | tr ' ' '\n' | grep -qx docker; then
        usermod -aG docker "$ADMIN"; changed "$ADMIN in group docker"
    fi
    ok "docker $(dpkg-query -W -f='${Version}' docker-ce 2>/dev/null), compose $(dpkg-query -W -f='${Version}' docker-compose-plugin 2>/dev/null)"
fi

# ------------------------------------------------------------------ journald limits
if ! skip journald; then
    if put_file /etc/systemd/journald.conf.d/00-nav.conf 644 '# NAV-008 (deployment-staging.md §3, AC 14): bounded retention.
[Journal]
SystemMaxUse=1G
MaxRetentionSec=14day
'; then svc restart systemd-journald || true; fi
    ok "journald 1G / 14 days"
fi

# ------------------------------------------------------------------ UFW (IPv4 + IPv6, logging off)
ufw_state() { cat /etc/default/ufw /etc/ufw/ufw.conf /etc/ufw/user.rules /etc/ufw/user6.rules 2>/dev/null | sha256sum; }
if ! skip ufw; then
    before=$(ufw_state)
    grep -q '^IPV6=yes' /etc/default/ufw || sed -i 's/^IPV6=.*/IPV6=yes/' /etc/default/ufw
    # While UFW is inactive, these commands only write the rule files (so they also work in a CI container).
    grep -q '^DEFAULT_INPUT_POLICY="DROP"' /etc/default/ufw || ufw default deny incoming >/dev/null
    grep -q '^DEFAULT_OUTPUT_POLICY="ACCEPT"' /etc/default/ufw || ufw default allow outgoing >/dev/null
    ufw limit "$SSH_PORT/tcp" comment 'NAV-008 ssh' >/dev/null
    if [[ "$ROLE" == staging ]]; then
        ufw allow 80/tcp comment 'NAV-008 http (redirect + ACME)' >/dev/null
        ufw allow 443/tcp comment 'NAV-008 https' >/dev/null
    fi
    # Blocked-packet logs contain source IPs (deployment-staging.md §8).
    grep -q '^LOGLEVEL=off' /etc/ufw/ufw.conf || ufw logging off >/dev/null
    if in_container; then
        log info "skipped (container): ufw enable (no netfilter in an unprivileged container)"
    elif ! ufw status | grep -q '^Status: active'; then
        ufw --force enable >/dev/null
        changed "ufw enabled"
    fi
    after=$(ufw_state)
    [[ "$before" == "$after" ]] || changed "ufw rules"
    ok "ufw ($ROLE)"
fi

# ------------------------------------------------------------------ swap 4 GB, swappiness 10
if ! skip swap; then
    put_file /etc/sysctl.d/60-nav.conf 644 '# NAV-008 (deployment-staging.md §3): swap only as a safety margin during builds.
vm.swappiness = 10
' && { in_container || sysctl -q -p /etc/sysctl.d/60-nav.conf; }
    if in_container; then
        log info "skipped (container): swap file and swapon"
    else
        if ! swapon --show=NAME --noheadings | grep -q .; then
            if [[ ! -f /swapfile ]]; then
                fallocate -l 4G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=4096 status=none
                chmod 600 /swapfile
                mkswap /swapfile >/dev/null
                changed "/swapfile 4G"
            fi
            swapon /swapfile && changed "swapon /swapfile"
        fi
        grep -qE '^/swapfile[[:space:]]' /etc/fstab || { echo '/swapfile none swap sw 0 0' >> /etc/fstab; changed "/etc/fstab swap entry"; }
        ok "swap $(swapon --show=SIZE --noheadings | head -1 | tr -d ' ')"
    fi
fi

# ------------------------------------------------------------------ sysstat (28 days)
if ! skip sysstat; then
    # shellcheck disable=SC2016  # backticks are part of the comment text
    put_file /etc/default/sysstat 644 '# NAV-008 (deployment-staging.md §3, AC 18): collect CPU, RAM, disk I/O; read with `sar`.
ENABLED="true"
' || true
    if grep -qE '^HISTORY=' /etc/sysstat/sysstat; then
        grep -qx 'HISTORY=28' /etc/sysstat/sysstat || { sed -i 's/^HISTORY=.*/HISTORY=28/' /etc/sysstat/sysstat; changed "/etc/sysstat/sysstat HISTORY=28"; }
    else
        echo 'HISTORY=28' >> /etc/sysstat/sysstat; changed "/etc/sysstat/sysstat HISTORY=28"
    fi
    if has_systemd; then
        for u in sysstat.service sysstat-collect.timer sysstat-summary.timer; do
            systemctl list-unit-files "$u" >/dev/null 2>&1 || continue
            systemctl is-enabled --quiet "$u" || { systemctl enable --now "$u" >/dev/null 2>&1; changed "$u enabled"; }
        done
    fi
    ok "sysstat (HISTORY=28)"
fi

# ------------------------------------------------------------------ repository directory and state
if ! skip repo; then
    if [[ ! -d "$NAV_DIR" ]]; then install -d -m 755 -o "$ADMIN" -g "$ADMIN" "$NAV_DIR"; changed "$NAV_DIR"; fi
    if [[ "$(stat -c %U "$NAV_DIR")" != "$ADMIN" ]]; then chown -R "$ADMIN:$ADMIN" "$NAV_DIR"; changed "$NAV_DIR owner $ADMIN"; fi
    if [[ -d "$NAV_DIR/backend" ]]; then
        # Containers write data/ as root; the builders need the directory to exist.
        [[ -d "$NAV_DIR/backend/data" ]] || { install -d -m 755 "$NAV_DIR/backend/data"; changed "$NAV_DIR/backend/data"; }
    fi
    [[ -d /var/lib/nav ]] || { install -d -m 750 /var/lib/nav; changed "/var/lib/nav"; }
    [[ -d /root/.config/nav ]] || { install -d -m 700 /root/.config/nav; changed "/root/.config/nav"; }
    ok "$NAV_DIR (owner $ADMIN), /var/lib/nav"
fi

log info "bootstrap finished" "\"changes\":$CHANGES,\"role\":\"$ROLE\""
echo "changes=$CHANGES"

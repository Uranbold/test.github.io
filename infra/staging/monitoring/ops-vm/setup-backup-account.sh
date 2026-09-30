#!/usr/bin/env bash
# NAV-008 ops VM: SFTP-only account that receives the staging host's restic backups (deployment-staging.md §10).
# Owner: backend-engineer. Idempotent. Run as root on the ops VM AFTER
#   bootstrap.sh --role ops --ops-key-file <operator.pub> --extra-allow-users nav-backup
#
#   sudo infra/staging/monitoring/ops-vm/setup-backup-account.sh /root/nav-staging-backup.pub
#
# The argument is the PUBLIC key that nav-backup.sh on the staging host uses (generated there; the private half
# never leaves the staging host). The key is restricted to the SFTP subsystem (no shell, no forwarding) and to
# /srv/restic as the start directory. restic encrypts everything client-side, so this VM never sees plaintext.
set -Eeuo pipefail
USER_NAME=nav-backup
BASE=/srv/restic
key_file=${1:?usage: setup-backup-account.sh <public-key-file>}
[[ $EUID -eq 0 ]] || { echo "run as root" >&2; exit 1; }
grep -q 'PRIVATE KEY' "$key_file" && { echo "$key_file is a PRIVATE key; pass the .pub file" >&2; exit 1; }
key=$(grep -m1 -E '^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)) ' "$key_file") || { echo "no public key in $key_file" >&2; exit 1; }
sftp_server=$(for p in /usr/lib/openssh/sftp-server /usr/libexec/openssh/sftp-server; do [[ -x $p ]] && echo "$p" && break; done)
[[ -n "$sftp_server" ]] || { echo "sftp-server not found" >&2; exit 1; }
changes=0

if ! id "$USER_NAME" >/dev/null 2>&1; then
    useradd --system --create-home --home-dir "/home/$USER_NAME" --shell /bin/sh "$USER_NAME"
    passwd -l "$USER_NAME" >/dev/null
    changes=$((changes + 1))
fi
install -d -m 750 -o "$USER_NAME" -g "$USER_NAME" "$BASE" "$BASE/nav-staging"
install -d -m 700 -o "$USER_NAME" -g "$USER_NAME" "/home/$USER_NAME/.ssh"
line="restrict,command=\"$sftp_server -d $BASE\" $key"
ak="/home/$USER_NAME/.ssh/authorized_keys"
body=$(awk '{print $2}' <<<"$key")
if ! grep -qF "$body" "$ak" 2>/dev/null; then
    printf '%s\n' "$line" >> "$ak"
    changes=$((changes + 1))
fi
chown "$USER_NAME:$USER_NAME" "$ak"; chmod 600 "$ak"
sshd -T | grep -qE "^allowusers .*\b$USER_NAME\b" \
    || echo "WARNING: $USER_NAME is not in sshd AllowUsers; re-run bootstrap.sh --role ops --extra-allow-users $USER_NAME" >&2
echo "changes=$changes  (repository path for RESTIC_REPOSITORY: sftp:$USER_NAME@<ops-host>:$BASE/nav-staging)"

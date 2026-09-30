#!/usr/bin/env bash
# NAV-008 staging: certificate renewal dry run against the Let's Encrypt STAGING CA (deployment-staging.md §5,
# AC 7). Owner: backend-engineer. Downtime about one minute: run outside tester hours.
#   sudo infra/staging/bin/cert-dry-run.sh
# Stops the normal Caddy, starts a temporary Caddy with the same Caddyfile, a FRESH empty data directory and
# ACME_CA=Let's Encrypt staging, waits for "certificate obtained successfully", checks that the served
# certificate comes from the LE staging CA for STAGING_HOST, removes the temporary container and starts the
# normal Caddy again (also on failure). An ACME order is the same flow as a renewal. The result is appended
# to $NAV_STATE_DIR/cert-dry-run.log (copy it into the RUNBOOK log table).
# shellcheck source=nav-env.sh
source "$(dirname "$(readlink -f "$0")")/nav-env.sh"
nav_require_root
nav_validate_env
exec 9>"${NAV_LOCK_FILE:-/run/lock/nav-stack.lock}"
flock -n 9 || nav_die "another rebuild, deploy or rollback holds the lock"

HOST=$(nav_env_get STAGING_HOST)
EMAIL=$(nav_env_get ACME_EMAIL)
LE_STAGING=https://acme-staging-v02.api.letsencrypt.org/directory
image=$(nav_compose config --images | grep -m1 '^caddy\|/caddy' || true)
[[ -n "$image" ]] || nav_die "cannot resolve the Caddy image from the compose config"
name=nav-cert-dryrun
tmpdata=$(mktemp -d)

cleanup() {
    docker rm -f "$name" >/dev/null 2>&1 || true
    rm -rf "$tmpdata"
    nav_compose up -d --no-deps --wait caddy >/dev/null 2>&1 || nav_log error "normal Caddy did not come back; run: sudo infra/staging/bin/nav-compose up -d caddy"
}
trap cleanup EXIT

nav_log info "certificate dry run starting (normal Caddy stops for about a minute)" host="$HOST" ca="$LE_STAGING"
nav_compose stop caddy
docker run -d --name "$name" -p 80:80/tcp -p 443:443/tcp \
    -e STAGING_HOST="$HOST" -e ACME_EMAIL="$EMAIL" -e ACME_CA="$LE_STAGING" \
    -v "$NAV_STAGING/caddy/Caddyfile:/etc/caddy/Caddyfile:ro" -v "$tmpdata:/data" \
    "$image" >/dev/null

ok=0
for _ in $(seq 1 90); do
    if docker logs "$name" 2>&1 | grep -q '"certificate obtained successfully"'; then ok=1; break; fi
    if docker logs "$name" 2>&1 | grep -q '"level":"error".*"logger":"tls.obtain"'; then break; fi
    sleep 2
done
issuer=""
if [[ $ok -eq 1 ]]; then
    issuer=$(echo | openssl s_client -connect 127.0.0.1:443 -servername "$HOST" 2>/dev/null | openssl x509 -noout -issuer -subject -enddate 2>/dev/null | tr '\n' ' ')
fi
result=fail
if [[ $ok -eq 1 && "$issuer" == *STAGING* ]]; then result=pass; fi
printf '%s host=%s result=%s cert=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$HOST" "$result" "${issuer:-none}" >> "$NAV_STATE_DIR/cert-dry-run.log"
if [[ "$result" != pass ]]; then
    docker logs "$name" 2>&1 | grep '"logger":"tls' | tail -n 5
    nav_die "certificate dry run failed (see the tls log lines above; common causes: DNS not pointing here, port 80 blocked in the panel firewall)"
fi
nav_log info "certificate dry run passed" certificate="$issuer" record="$NAV_STATE_DIR/cert-dry-run.log"

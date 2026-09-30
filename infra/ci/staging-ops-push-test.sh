#!/usr/bin/env bash
# NAV-008: local push test of the ops VM stack (deployment-staging.md §9, §17.4). About 40 s; needs Docker and the
# pinned Caddy and Uptime Kuma images. Runs as its own compose project (default navops-ci) on its own network and
# ports; it never touches the dev stack (navmn) or anything on 127.0.0.1:8080.
#   infra/ci/staging-ops-push-test.sh            # OPS_TEST_HTTP_PORT / OPS_TEST_HTTPS_PORT change the ports
# 1. monitoring/ops-vm/compose.yaml up with OPS_HOST=ops.localhost (Caddy's internal CA instead of Let's Encrypt)
# 2. a push monitor is seeded in Kuma's SQLite DB (the operator creates it in the UI on the real VM)
# 3. only /api/push/* is forwarded: UI, API, badges, metrics, socket.io -> 404; http -> 308 to https
# 4. bin/nav-diskcheck.sh and bin/nav-push-test.sh push through Caddy; Kuma records UP heartbeats
# 5. wrong token, tunnel origin (http://localhost:3001), plain http and a foreign host are refused
# 6. Caddy's log holds no request path, token or client address
# Kuma's port 3001 is bound on 127.0.0.1 exactly as on the VM, so 127.0.0.1:3001 must be free while this runs.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OPS=$ROOT/infra/staging/monitoring/ops-vm
BIN=$ROOT/infra/staging/bin
P=${OPS_TEST_PROJECT:-navops-ci}
HP=${OPS_TEST_HTTP_PORT:-18080}
SP=${OPS_TEST_HTTPS_PORT:-18443}
TOKEN=NavCiPushToken$(date +%s)abc
TMP=$(mktemp -d)
fail() { echo "FAIL: $*" >&2; exit 1; }
compose() { docker compose -p "$P" -f "$OPS/compose.yaml" --env-file "$TMP/ops.env" "$@"; }
cleanup() { compose down -v >/dev/null 2>&1 || true; rm -rf "$TMP"; }
trap cleanup EXIT
export NO_PROXY='*' no_proxy='*'

if curl -s -m 2 -o /dev/null http://127.0.0.1:3001/ 2>/dev/null; then fail "127.0.0.1:3001 is in use; stop that service first"; fi
{ cat "$OPS/.env.example"; printf 'OPS_HOST=ops.localhost\nOPS_HTTP_PORT=%s\nOPS_HTTPS_PORT=%s\n' "$HP" "$SP"; } > "$TMP/ops.env"

echo "== 1. ops stack up (project $P, ports $HP/$SP)"
compose up -d --wait >/dev/null 2>&1 || { compose logs --tail=30; fail "compose up"; }
kuma=$(compose ps -q uptime-kuma); caddy=$(compose ps -q caddy)
for _ in $(seq 1 60); do docker exec "$kuma" test -s /app/data/kuma.db 2>/dev/null && break; sleep 1; done
docker logs "$kuma" > "$TMP/kuma.log" 2>&1
grep -q 'UPTIME_KUMA_DB_TYPE is provided by env' "$TMP/kuma.log" || fail "Kuma did not take UPTIME_KUMA_DB_TYPE (database wizard would appear)"

echo "== 2. seed a push monitor"
cat > "$TMP/seed.js" <<'EOF'
const sqlite3 = require("@louislam/sqlite3");
const db = new sqlite3.Database("/app/data/kuma.db");
const [mode, token] = process.argv.slice(2);
if (mode === "seed") {
  db.serialize(() => {
    db.run("INSERT OR IGNORE INTO user (id, username, password, active) VALUES (1, 'ci-admin', 'not-a-real-hash', 1)");
    db.run("INSERT INTO monitor (name, type, push_token, active, interval, user_id) VALUES ('nav-staging disk (ci)', 'push', ?, 1, 900, 1)", [token],
      (e) => { if (e) { console.error(e.message); process.exit(1); } });
  });
} else {
  db.all("SELECT h.status, h.msg FROM heartbeat h JOIN monitor m ON m.id = h.monitor_id WHERE m.push_token = ? ORDER BY h.id", [token],
    (e, rows) => { if (e) { console.error(e.message); process.exit(1); } rows.forEach(r => console.log(`${r.status}\t${r.msg}`)); });
}
db.close();
EOF
docker cp "$TMP/seed.js" "$kuma:/app/nav-ci-seed.js" >/dev/null
seeded=0
for _ in $(seq 1 30); do docker exec -w /app "$kuma" node /app/nav-ci-seed.js seed "$TOKEN" 2>/dev/null && { seeded=1; break; }; sleep 2; done
[[ $seeded -eq 1 ]] || fail "could not seed the push monitor"

echo "== 3. only /api/push/* reaches Kuma"
for _ in $(seq 1 30); do docker exec "$caddy" test -s /data/caddy/pki/authorities/local/root.crt 2>/dev/null && break; sleep 1; done
docker cp "$caddy:/data/caddy/pki/authorities/local/root.crt" "$TMP/root.crt" >/dev/null
export CURL_CA_BUNDLE=$TMP/root.crt
# The scripts run in child shells: the curl wrapper below reads this exported value there.
export NAV_CI_RESOLVE="ops.localhost:$SP:127.0.0.1"
code() { curl -s --resolve "ops.localhost:$SP:127.0.0.1" -o /dev/null -w '%{http_code}' "https://ops.localhost:$SP$1"; }
for p in / /dashboard /api/push /metrics /api/badge/1/status /socket.io/ /api/status-page/x /assets/index.js; do
    c=$(code "$p"); [[ "$c" == 404 ]] || fail "$p answered $c, expected 404"
done
c=$(curl -s --resolve "ops.localhost:$HP:127.0.0.1" -o /dev/null -w '%{http_code}' "http://ops.localhost:$HP/api/push/$TOKEN")
[[ "$c" == 308 ]] || fail "http push answered $c, expected 308 redirect"
echo "   UI, API, badges, metrics, socket.io, assets -> 404; http -> 308"

echo "== 4. heartbeats from the staging scripts"
cat > "$TMP/stg.env" <<EOF
STAGING_HOST=staging.example.invalid
OPS_HOST=ops.localhost
UPTIME_PUSH_URL_DISK=https://ops.localhost:$SP/api/push/$TOKEN?status=up&msg=OK&ping=
EOF
# shellcheck disable=SC2329  # exported, invoked by the scripts under test
curl() { command curl --resolve "$NAV_CI_RESOLVE" "$@"; }; export -f curl
NAV_ENV_FILE=$TMP/stg.env "$BIN/nav-diskcheck.sh" >"$TMP/disk.log" 2>&1 || { cat "$TMP/disk.log"; fail "nav-diskcheck.sh"; }
NAV_ENV_FILE=$TMP/stg.env "$BIN/nav-push-test.sh" disk >"$TMP/push.log" 2>&1 || { cat "$TMP/push.log"; fail "nav-push-test.sh"; }
unset -f curl
grep -q "$TOKEN" "$TMP/disk.log" "$TMP/push.log" && fail "a script printed the push token"
hb=$(docker exec -w /app "$kuma" node /app/nav-ci-seed.js list "$TOKEN")
[[ "$(grep -c '^1	' <<<"$hb")" -ge 2 ]] || { echo "$hb"; fail "Kuma has no UP heartbeats from the scripts"; }
echo "   Kuma heartbeats (1 = UP):"; sed 's/^/     /' <<<"$hb"

echo "== 5. refused URLs"
check_refused() {
    printf 'OPS_HOST=ops.localhost\nUPTIME_PUSH_URL_DISK=%s\n' "$1" > "$TMP/bad.env"
    if NAV_ENV_FILE=$TMP/bad.env "$BIN/nav-diskcheck.sh" >"$TMP/bad.log" 2>&1; then fail "accepted: $2"; fi
    grep -q "$3" "$TMP/bad.log" || { cat "$TMP/bad.log"; fail "unexpected message for: $2"; }
    echo "   refused: $2"
}
check_refused "http://localhost:3001/api/push/$TOKEN?status=up&msg=OK&ping=" "tunnel origin http://localhost:3001" "SSH-tunnel UI"
check_refused "http://ops.localhost:$HP/api/push/$TOKEN" "plain http" "plain http"
check_refused "https://other.example.invalid/api/push/$TOKEN" "host other than OPS_HOST" "differs from OPS_HOST"
printf 'OPS_HOST=ops.localhost\nUPTIME_PUSH_URL_DISK=https://ops.localhost:%s/api/push/WrongToken1\n' "$SP" > "$TMP/bad.env"
# shellcheck disable=SC2329  # exported, invoked by the scripts under test
curl() { command curl --resolve "$NAV_CI_RESOLVE" "$@"; }; export -f curl
if NAV_ENV_FILE=$TMP/bad.env "$BIN/nav-diskcheck.sh" >"$TMP/bad.log" 2>&1; then fail "wrong token accepted"; fi
unset -f curl
grep -q '"http_status":"404"' "$TMP/bad.log" || { cat "$TMP/bad.log"; fail "wrong token: expected Kuma's 404"; }
echo "   refused: wrong token (Kuma 404, reported without the URL)"

echo "== 6. Caddy log"
docker logs "$caddy" > "$TMP/caddy.log" 2>&1
if grep -qE "$TOKEN|/api/push|remote_ip|\"uri\"" "$TMP/caddy.log"; then fail "Caddy logged request data"; fi
echo "   loggers: $(grep -o '"logger":"[^"]*"' "$TMP/caddy.log" | sort -u | cut -d'"' -f4 | tr '\n' ' ')"
echo "OK: ops push test passed"

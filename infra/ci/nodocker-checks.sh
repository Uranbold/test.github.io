#!/usr/bin/env bash
# Gate B hardening checks that need NO Docker daemon (audit 2026-10-07 re-check 2026-10-11; owner: backend-engineer).
#   infra/ci/nodocker-checks.sh            # about 15 s; the deploy signature test needs root (skipped otherwise)
# Everything here is also covered, with real containers, by backend-static-checks.sh and staging-static-checks.sh;
# this script exists so the hardening can be checked on a machine without Docker (the agent dev container, a laptop).
#  1. gateway entrypoint scripts run with sh into a scratch conf.d: BE-23 burst 0 refused with its own message;
#     BE-3 residual tiles cap default 32 (0 = off, 1-23 refused), keyed on the client key, 429 status
#  2. gateway config text (nginx.conf, default.conf.template): BE-14 timeouts, buffers, per-route body limits, 413
#     targets on every static location, max_ranges 1, route send/body timeouts; BE-13 label (no "BE-12" on nosniff)
#  3. Caddyfile text: BE-14 timeouts, max_header_size, request_body max_size, 413 backstop, transport timeouts
#  4. bootstrap.sh daemon.json (BE-12): parses, hardened keys, address pool off the edge network
#  5. nav-env.sh (BE-17): .env refused with mode 644/660, as a symlink, owned by another user (root only); 600/400 ok
#  6. deploy.sh (BE-22, BE-9, BE-17): infra/ci/staging-deploy-signature-test.sh (root)
#  7. pipeline unit tests incl. BE-18 redaction parity with scripts/lib.sh log_url
#  8. scripts/image-scan.sh --list (B3): image list, Trivy pinned by digest, Makefile target
#  9. shellcheck / bash -n / py_compile of every file this gate touched
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
B=$ROOT/backend; ST=$ROOT/infra/staging; E=$B/gateway/entrypoint
fail() { echo "FAIL: $*" >&2; exit 1; }
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

echo "== 1. gateway entrypoint scripts (sh, scratch conf.d)"
run_ep() {  # run_ep <script> [VAR=value ...] -> stdout+stderr in $TMP/ep.out, exit code in $rc, files in $TMP/confd
    local script=$1; shift
    rm -rf "$TMP/confd"; mkdir -p "$TMP/confd"
    rc=0; env NGINX_ENVSUBST_OUTPUT_DIR="$TMP/confd" "$@" sh "$E/$script" > "$TMP/ep.out" 2>&1 || rc=$?
}
run_ep 16-rate-limits.sh GATEWAY_RATE_LIMIT=on GATEWAY_BURST_ROUTE=0
[[ $rc -ne 0 ]] && grep -q "invalid burst (expected an integer >= 1; nginx refuses burst=0): '0'" "$TMP/ep.out" || { cat "$TMP/ep.out"; fail "BE-23: burst 0 must be refused with the right message"; }
run_ep 16-rate-limits.sh GATEWAY_RATE_LIMIT=on GATEWAY_BURST_PACKS=1; [[ $rc -eq 0 ]] && grep -q 'limit_req zone=nav_packs  burst=1 nodelay;' "$TMP/confd/01-rate-limits.conf" || fail "burst 1 must be accepted"
run_ep 16-rate-limits.sh GATEWAY_RATE_LIMIT=on GATEWAY_BURST_SEARCH=abc; [[ $rc -ne 0 ]] || fail "burst abc accepted"
run_ep 18-download-limits.sh GATEWAY_RATE_LIMIT=on
[[ $rc -eq 0 ]] || { cat "$TMP/ep.out"; fail "18-download-limits.sh defaults"; }
grep -q 'limit_conn nav_dl_tiles        32;' "$TMP/confd/03-download-limits.conf" || fail "tiles cap default must be 32 (openapi 0.7.1)"
grep -q 'limit_conn_zone $nav_dl_tiles_key        zone=nav_dl_tiles:10m;' "$TMP/confd/03-download-limits.conf" || fail "tiles zone"
grep -A2 'map $uri $nav_dl_tiles_key' "$TMP/confd/03-download-limits.conf" | grep -q '/tiles/basemap.pmtiles $nav_dl_client;' || fail "tiles key must be the client key (GET and HEAD)"
grep -q '"tiles":"1m/s after 4m, 32 in flight per client"' "$TMP/ep.out" || { cat "$TMP/ep.out"; fail "tiles cap start log"; }
run_ep 18-download-limits.sh GATEWAY_RATE_LIMIT=on GATEWAY_TILES_MAX_CONN_PER_IP=0
[[ $rc -eq 0 ]] && ! grep -q 'limit_conn nav_dl_tiles' "$TMP/confd/03-download-limits.conf" || fail "0 must switch the tiles cap off"
run_ep 18-download-limits.sh GATEWAY_RATE_LIMIT=on GATEWAY_TILES_MAX_CONN_PER_IP=24; [[ $rc -eq 0 ]] && grep -q 'limit_conn nav_dl_tiles        24;' "$TMP/confd/03-download-limits.conf" || fail "24 must be accepted"
for bad in 23 1 -1 abc; do
    run_ep 18-download-limits.sh GATEWAY_RATE_LIMIT=on GATEWAY_TILES_MAX_CONN_PER_IP=$bad; [[ $rc -ne 0 ]] || fail "GATEWAY_TILES_MAX_CONN_PER_IP=$bad accepted"
done
run_ep 18-download-limits.sh GATEWAY_TILES_MAX_CONN_PER_IP=23; [[ $rc -ne 0 ]] || fail "23 must be refused even with the limits off (fail at start, never silently)"
run_ep 18-download-limits.sh; [[ $rc -eq 0 ]] && ! grep -qE 'limit_conn|limit_rate' "$TMP/confd/03-download-limits.conf" || fail "dev default must set no download limit"
run_ep 17-route-limits.sh; [[ $rc -eq 0 ]] && grep -q 'limit_conn nav_route_ip     4;' "$TMP/confd/02-route-limits.conf" || fail "route cap defaults"
grep -q 'limit_conn_status 429;' "$B/gateway/templates/default.conf.template" || fail "tiles location limit_conn_status 429"
echo "   BE-23 burst 0 refused (own message); tiles cap 32 default, 24 ok, 0 off, 1-23/abc refused; dev unlimited"

echo "== 2. gateway config text (BE-14, BE-13)"
N=$B/gateway/nginx.conf; T=$B/gateway/templates/default.conf.template
for want in 'client_header_timeout     10s;' 'client_body_timeout       10s;' 'send_timeout              10s;' 'keepalive_timeout         30s;' \
            'keepalive_requests        1000;' 'reset_timedout_connection on;' 'client_header_buffer_size   1k;' 'large_client_header_buffers 4 8k;' \
            'client_body_buffer_size     256k;' 'underscores_in_headers      off;' 'ignore_invalid_headers      on;' 'client_max_body_size        1k;'; do
    grep -qF "$want" "$N" || fail "nginx.conf lacks '$want'"
done
grep -q 'keepalive_timeout  65;' "$N" && fail "old keepalive_timeout 65 left"
grep -q '^client_max_body_size 256k;' "$T" && fail "the 256k body limit must be on /v1/route only, not http-wide"
python3 - "$T" <<'PY' || fail "default.conf.template per-location rules"
import re, sys
t = open(sys.argv[1]).read()
def loc(name):
    m = re.search(r"location (?:= |~ )?" + re.escape(name) + r".*?\n    \}", t, re.S)
    assert m, name
    return m.group(0)
route = loc("/v1/route")
for d in ("client_max_body_size 256k;", "client_body_timeout 10s;", "send_timeout        10s;", "error_page 413 = @payload_too_large;", "proxy_read_timeout 10s;"):
    assert d in route, ("ROUTE", d)
for name in ("/tiles/basemap.pmtiles", '"^/packs/mn/(?<nav_pack_version>'):
    block = loc(name)
    assert "max_ranges 1;" in block and "error_page 413 = @payload_too_large;" in block and "limit_conn_status 429;" in block, name
assert "error_page 413 = @payload_too_large;" in loc("/packs/mn/manifest.json")
for name in ("/v1/search", "/v1/reverse"):
    block = loc(name)
    assert "error_page 413 = @payload_too_large;" in block and "client_max_body_size" not in block, name
assert t.count("error_page 413 = @payload_too_large;") == 7, t.count("error_page 413 = @payload_too_large;")
assert "@payload_too_large {" in t and '"code":"PayloadTooLarge"' in t
print("   ROUTE: 256k + body/send timeouts; TILES/PACKS: max_ranges 1 + JSON 413; 7 x error_page 413")
PY
grep -rn --exclude-dir=__pycache__ "BE-12" "$B/gateway" "$B/README.md" "$ROOT/tests/api/sec4a" "$ROOT/infra/ci/backend-static-checks.sh" | grep -i "nosniff\|BE-12/BE-13\|Response headers (BE-12" && fail "BE-12 label still on the nosniff header (it is BE-13; BE-12 = Docker daemon)"
echo "   nginx.conf timeouts/buffers/1k body limit present; no BE-12 label on nosniff"

echo "== 3. Caddyfile text (BE-14)"
C=$ST/caddy/Caddyfile
for want in 'read_header 10s' 'read_body 10s' 'write 15m' 'idle 2m' 'max_header_size 16KB' 'max_size 1MB' 'handle_errors 413' 'dial_timeout 2s' 'response_header_timeout 15s' '"code":"PayloadTooLarge"'; do
    grep -qF "$want" "$C" || fail "Caddyfile lacks '$want'"
done
grep -q 'protocols h1 h2' "$C" || fail "Caddy protocols"
echo "   timeouts 10s/10s/15m/2m, headers 16KB, body 1MB -> 413 JSON, transport 2s/15s (caddy validate: staging-static-checks.sh)"

echo "== 4. bootstrap.sh daemon.json (BE-12)"
python3 - "$ST/bootstrap/bootstrap.sh" "$ST/.env.example" <<'PY' || fail "daemon.json"
import ipaddress, json, re, sys
src = open(sys.argv[1]).read()
m = re.search(r"put_file /etc/docker/daemon.json 644 '(\{.*?\n\})\n'", src, re.S)
d = json.loads(m.group(1))
assert d["no-new-privileges"] is True and d["userland-proxy"] is False and d["live-restore"] is True
assert d["log-driver"] == "json-file" and d["log-opts"] == {"max-size": "10m", "max-file": "5"}
env = dict(re.findall(r"^([A-Z][A-Z0-9_]*)=(.*)$", open(sys.argv[2]).read(), re.M))
edge = ipaddress.ip_network(env["EDGE_SUBNET_V4"])
for p in d["default-address-pools"]:
    base = ipaddress.ip_network(p["base"]); assert base.is_private and not base.overlaps(edge) and p["size"] >= base.prefixlen
print("   daemon.json ok:", json.dumps(d, separators=(",", ":")))
PY

echo "== 5. nav-env.sh .env mode/owner (BE-17)"
printf 'STAGING_HOST=staging.example.invalid\nACME_EMAIL=ops@example.invalid\nCORS_ALLOWED_ORIGINS=https://a.example.invalid\n' > "$TMP/t.env"
envcheck() { NAV_ENV_FILE="$1" bash -c 'source "$1/bin/nav-env.sh"; nav_require_env_file' _ "$ST" 2>&1 || true; }
chmod 644 "$TMP/t.env"; envcheck "$TMP/t.env" | grep -q 'must be 600' || fail "644 accepted"
chmod 660 "$TMP/t.env"; envcheck "$TMP/t.env" | grep -q 'must be 600' || fail "660 accepted"
chmod 604 "$TMP/t.env"; envcheck "$TMP/t.env" | grep -q 'must be 600' || fail "604 accepted"
ln -s "$TMP/t.env" "$TMP/l.env"; envcheck "$TMP/l.env" | grep -q 'symlinks are refused' || fail "symlink accepted"
envcheck "$TMP/missing.env" | grep -q 'install -m 600 -o root' || fail "missing .env message must show the install command"
chmod 600 "$TMP/t.env"; [[ -z "$(envcheck "$TMP/t.env")" ]] || fail "600 refused: $(envcheck "$TMP/t.env")"
chmod 400 "$TMP/t.env"; [[ -z "$(envcheck "$TMP/t.env")" ]] || fail "400 refused"
chmod 600 "$TMP/t.env"
if [[ $EUID -eq 0 ]]; then
    chown 65534 "$TMP/t.env"; envcheck "$TMP/t.env" | grep -q 'must belong to the account running' || fail "other owner accepted"; chown 0 "$TMP/t.env"
    own=", other owner refused"
else own=" (owner check needs root)"; fi
out=$(NAV_ENV_FILE="$TMP/t.env" bash -c 'source "$1/bin/nav-env.sh"; nav_validate_env; nav_env_get STAGING_HOST' _ "$ST")
[[ "$out" == staging.example.invalid ]] || fail "nav_validate_env on a good 600 file: $out"
echo "   644/660/604 and symlink refused, 600/400 accepted$own; install command in the missing-file message"

echo "== 6. deploy.sh signed-tag rules incl. BE-22 renamed tag object and BE-17 (root)"
"$ROOT/infra/ci/staging-deploy-signature-test.sh" > "$TMP/sig.log" 2>&1 || { cat "$TMP/sig.log"; fail "deploy signature checks"; }
grep -E '^(SKIP|OK|   renamed|   .env)' "$TMP/sig.log" | sed 's/^/   /'
grep -q "awk '/^\$/ {exit} /^tag / {print" "$ST/bin/deploy.sh" || fail "deploy.sh must read the tag object's own name"

echo "== 7. pipeline unit tests (BE-18 redaction parity with lib.sh log_url included)"
python3 -m unittest discover -s "$B/pipeline/tests" > "$TMP/ut.log" 2>&1 || { tail -n 30 "$TMP/ut.log"; fail "pipeline unit tests"; }
tail -n 3 "$TMP/ut.log" | sed 's/^/   /'
python3 -m unittest -q "$B/pipeline/tests/test_nav_pipeline.py" -k Redaction > "$TMP/ut2.log" 2>&1 2>&1 || true
python3 - "$B/pipeline" <<'PY' || fail "redaction"
import sys; sys.path.insert(0, sys.argv[1]); import nav_pipeline as nav
assert nav.redact_url("https://u:p@h/a.pbf?token=t") == "https://<credentials>@h/a.pbf?<redacted>"
assert "S3cret" not in nav.redact_text('{"source":"https://mirror-user:S3cret@h/x.pbf?token=T","m":"https://mirror-user:S3cret@h/x.pbf?token=T.md5 missing"}')
print("   redact_url / redact_text ok")
PY
find "$B/pipeline" -name __pycache__ -type d -prune -exec rm -rf {} +

echo "== 8. image scan (B3): list + pin + Makefile target"
"$B/scripts/image-scan.sh" --list > "$TMP/images.txt" || fail "image-scan.sh --list"
[[ $(grep -c '@sha256:' "$TMP/images.txt") -ge 9 ]] || { cat "$TMP/images.txt"; fail "image list"; }
grep -q 'ghcr.io/aquasecurity/trivy:[0-9.]*@sha256:[0-9a-f]\{64\}' "$B/scripts/image-scan.sh" || fail "Trivy not pinned by digest"
make -s -C "$B" -n image-scan | grep -q image-scan.sh || fail "make image-scan"
if docker info >/dev/null 2>&1; then echo "   (a Docker daemon is available: run make -C backend image-scan for the gate B record)"; else echo "   $(head -1 "$TMP/images.txt"); no daemon: scan itself not run"; fi

echo "== 9. syntax"
bash -n "$ST"/bin/*.sh "$ST"/bootstrap/*.sh "$ROOT"/infra/ci/*.sh "$B"/scripts/*.sh "$E"/*.sh
if command -v shellcheck >/dev/null; then
    shellcheck -x -P "$ST/bin" -S warning "$ST"/bin/*.sh "$ST"/bin/nav-compose "$ST"/bin/nav-pipeline "$ST"/bootstrap/*.sh "$ROOT"/infra/ci/*.sh || fail shellcheck
    shellcheck -x -P SCRIPTDIR -S warning "$B"/scripts/*.sh "$E"/*.sh || fail "shellcheck backend"
    echo "   shellcheck clean"
fi
python3 -m py_compile "$B"/scripts/*.py "$B"/pipeline/*.py "$ROOT"/tests/api/sec4a/*.py && rm -rf "$B/scripts/__pycache__" "$B/pipeline/__pycache__" "$ROOT/tests/api/sec4a/__pycache__"
echo "OK: no-docker gate B checks passed"

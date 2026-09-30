#!/usr/bin/env bash
# NAV-008: static checks of infra/staging (no server, no data build; about 20 s, pulls the pinned Caddy image).
#   infra/ci/staging-static-checks.sh
# 1. shellcheck (or bash -n)  2. compose config of the overlay (IPv4 and IPv6 variants): only Caddy publishes,
# gateway forced to 127.0.0.1, rate limits on, trusted proxy = Caddy's address, images pinned
# 3. `caddy validate` + adapted-config assertions (no access log, h1/h2 only, TLS 1.2-1.3, logger include-list)
# 4. systemd-analyze verify of the units  5. .env.example: every key has a comment; config parser and guards
# 6. AC 16 sampler summary maths; backend/Makefile refuses service targets when infra/staging/.env exists
# 7. no public IPv4 literal anywhere under infra/ (the real host address must never be committed)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ST=$ROOT/infra/staging
fail() { echo "FAIL: $*" >&2; exit 1; }
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

echo "== shell scripts"
scripts=("$ST"/bin/*.sh "$ST"/bin/nav-compose "$ST"/bootstrap/*.sh "$ST"/monitoring/ops-vm/*.sh "$ROOT"/infra/ci/*.sh)
for f in "${scripts[@]}"; do bash -n "$f" || fail "bash -n $f"; done
if command -v shellcheck >/dev/null; then
    shellcheck -x -P "$ST/bin" -S warning "${scripts[@]}" || fail shellcheck
else
    echo "   (shellcheck not installed: bash -n only)"
fi
for f in "$ST"/bin/*.sh "$ST"/bin/nav-compose "$ST"/bootstrap/bootstrap.sh "$ST"/monitoring/ops-vm/setup-backup-account.sh; do
    [[ "$(basename "$f")" == nav-env.sh || -x "$f" ]] || fail "not executable: $f"
done

echo "== compose config (overlay with .env.example values)"
cp "$ST/.env.example" "$TMP/stg.env"
echo "GATEWAY_BIND=0.0.0.0" >> "$TMP/stg.env"   # must NOT widen the gateway binding on staging
compose() { docker compose -p navmn --project-directory "$ROOT/backend" -f "$ROOT/backend/compose.yaml" -f "$ST/compose.staging.yaml" "$@"; }
compose --env-file "$TMP/stg.env" config > "$TMP/v4.yaml" || fail "compose config (IPv4)"
compose -f "$ST/compose.staging.ipv6.yaml" --env-file "$TMP/stg.env" config > "$TMP/v6.yaml" || fail "compose config (IPv6)"
for v in v4 v6; do
    python3 - "$TMP/$v.yaml" "$v" <<'PY' || fail "compose assertions ($v)"
import sys, yaml
d = yaml.safe_load(open(sys.argv[1])); v = sys.argv[2]; s = d["services"]
pub = {n: x.get("ports") for n, x in s.items() if x.get("ports")}
assert set(pub) == {"caddy", "gateway"}, f"services with ports: {sorted(pub)}"
cp = sorted((p["target"], p.get("protocol"), p.get("host_ip")) for p in pub["caddy"])
assert cp == [(80, "tcp", None), (443, "tcp", None)], f"caddy ports {cp}"
gp = pub["gateway"]
assert len(gp) == 1 and gp[0]["host_ip"] == "127.0.0.1", f"gateway ports {gp}"
env = s["gateway"]["environment"]
assert env["GATEWAY_RATE_LIMIT"] == "on", env["GATEWAY_RATE_LIMIT"]
assert "*" not in env["CORS_ALLOWED_ORIGINS"], env["CORS_ALLOWED_ORIGINS"]
assert env["GATEWAY_ERROR_LOG_LEVEL"] == "crit"
caddy_v4 = s["caddy"]["networks"]["edge"]["ipv4_address"]
trusted = env["GATEWAY_REAL_IP_FROM"].split(",")
want = [caddy_v4 + "/32"] + ([s["caddy"]["networks"]["edge"]["ipv6_address"] + "/128"] if v == "v6" else [])
assert trusted == want, f"trusted {trusted} != {want}"
assert set(s["gateway"]["networks"]) == {"default", "edge"} and set(s["caddy"]["networks"]) == {"edge"}
assert "@sha256:" in s["caddy"]["image"], s["caddy"]["image"]
for n, x in s.items():
    assert not x["image"].endswith(":latest"), f"{n} uses :latest"
ipam = d["networks"]["edge"]["ipam"]["config"]
assert (v == "v6") == bool(d["networks"]["edge"].get("enable_ipv6")), "enable_ipv6"
print(f"   {v}: only caddy publishes 80/443 tcp; gateway 127.0.0.1; rate limit on; trusted proxy {trusted}; {len(ipam)} edge subnet(s)")
PY
done
( cd "$ST/monitoring/ops-vm" && docker compose -f compose.yaml config --quiet ) || fail "ops-vm compose config"
docker compose -f "$ST/monitoring/ops-vm/compose.yaml" config | grep -q 'host_ip: 127.0.0.1' || fail "uptime kuma must bind to 127.0.0.1"

echo "== Caddyfile (caddy validate + adapted config)"
CADDY_IMG=$(python3 -c 'import sys,yaml; print(yaml.safe_load(open(sys.argv[1]))["services"]["caddy"]["image"])' "$TMP/v4.yaml")
CENV=(-e STAGING_HOST=staging.example.invalid -e ACME_EMAIL=ops@example.invalid -e ACME_CA=https://acme-v02.api.letsencrypt.org/directory)
docker run --rm "${CENV[@]}" -v "$ST/caddy/Caddyfile:/etc/caddy/Caddyfile:ro" "$CADDY_IMG" \
    caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >"$TMP/validate.log" 2>&1 || { cat "$TMP/validate.log"; fail "caddy validate"; }
docker run --rm "${CENV[@]}" -v "$ST/caddy/Caddyfile:/etc/caddy/Caddyfile:ro" "$CADDY_IMG" \
    caddy adapt --config /etc/caddy/Caddyfile --adapter caddyfile > "$TMP/caddy.json" 2>/dev/null || fail "caddy adapt"
python3 - "$TMP/caddy.json" <<'PY' || fail "Caddy config assertions"
import json, sys
c = json.load(open(sys.argv[1]))
srv = list(c["apps"]["http"]["servers"].values())
assert len(srv) == 1, "one server block"
srv = srv[0]
assert "logs" not in srv, "access logging is on (a `log` directive in the site block)"
assert srv.get("protocols") == ["h1", "h2"], f"protocols {srv.get('protocols')}"
pols = c["apps"]["tls"]["automation"]["policies"]
assert pols and pols[0]["issuers"][0]["ca"] == "https://acme-v02.api.letsencrypt.org/directory"
conn = srv["tls_connection_policies"][0]
assert conn.get("protocol_min") == "tls1.2" and conn.get("protocol_max") == "tls1.3", conn
log = c["logging"]["logs"]["default"]
assert set(log.get("include", [])) <= {"tls", "http.acme_client", "http.auto_https"} and log["include"], log
assert not any(x.startswith("http.log") or x == "http" for x in log["include"]), log
routes = json.dumps(srv["routes"]) ; errs = json.dumps(srv.get("errors", {}))
assert '"upstreams": [{"dial": "gateway:8080"}]' in routes, "reverse_proxy gateway:8080"
assert "UpstreamUnavailable" in errs and "no-store" in errs, "JSON 502 backstop"
assert "encode" not in routes, "no encode (byte-exact ranges)"
print("   no access log; h1+h2; TLS 1.2-1.3; include-list", log["include"], "; JSON backstop; reverse_proxy gateway:8080")
PY

echo "== systemd units"
mkdir -p "$TMP/units"
for u in "$ST"/systemd/*; do sed "s|@NAV_ROOT@|$ROOT|g" "$u" > "$TMP/units/$(basename "$u")"; done
if command -v systemd-analyze >/dev/null; then
    out=$(cd "$TMP/units" && systemd-analyze verify --man=no ./*.service ./*.timer 2>&1 || true)
    # docker.service does not exist on CI machines; everything else must be clean.
    bad=$(grep -v -E 'docker\.service|^$' <<<"$out" || true)
    [[ -z "$bad" ]] || { echo "$bad"; fail "systemd-analyze verify"; }
    echo "   systemd-analyze verify: clean (docker.service dependency not checked)"
else
    echo "   (systemd-analyze not installed: skipped)"
fi
grep -q 'OnCalendar=\*-\*-\* 19:30:00 UTC' "$ST/systemd/nav-rebuild.timer" || fail "rebuild timer must run 19:30 UTC"
grep -q 'OnCalendar=\*:0/5' "$ST/systemd/nav-diskcheck.timer" || fail "diskcheck every 5 min"

echo "== .env.example and config helpers"
python3 - "$ST/.env.example" "$ROOT/backend/.env.example" <<'PY' || fail ".env.example comments"
import re, sys
for path in sys.argv[1:]:
    lines = open(path).read().splitlines()
    for i, l in enumerate(lines):
        if re.match(r"^[A-Z][A-Z0-9_]*=", l):
            j = i - 1
            # Skip commented alternative values (# KEY=...) and, in backend/.env.example, a paired key
            # directly above (e.g. *_URL + *_SHA256 share one comment).
            while j >= 0 and (re.match(r"^# [A-Z][A-Z0-9_]*=", lines[j]) or
                              ("backend" in path and re.match(r"^[A-Z][A-Z0-9_]*=", lines[j]))):
                j -= 1
            assert j >= 0 and lines[j].startswith("#"), f"{path}:{i+1} {l.split('=')[0]} has no comment line"
print("   every key in infra/staging/.env.example and backend/.env.example has a comment")
PY
cat > "$TMP/t.env" <<'EOF'
STAGING_HOST=first.example.invalid
STAGING_HOST="staging.example.invalid"
ACME_EMAIL='ops@example.invalid'
CORS_ALLOWED_ORIGINS=https://a.example.invalid,http://localhost:5173
EMPTY=
EOF
out=$(NAV_ENV_FILE="$TMP/t.env" bash -c 'source "$1/bin/nav-env.sh"; printf "%s|%s|%s|%s" "$(nav_env_get STAGING_HOST)" "$(nav_env_get ACME_EMAIL)" "$(nav_env_get EMPTY dflt)" "$(nav_env_get MISSING d2)"; nav_validate_env' _ "$ST")
[[ "$out" == "staging.example.invalid|ops@example.invalid|dflt|d2" ]] || fail "nav_env_get: $out"
echo "CORS_ALLOWED_ORIGINS=*" >> "$TMP/t.env"
if NAV_ENV_FILE="$TMP/t.env" bash -c 'source "$1/bin/nav-env.sh"; nav_validate_env' _ "$ST" >/dev/null 2>&1; then fail "CORS '*' was accepted"; fi
echo "   parser (quotes, last assignment wins, defaults) and CORS '*' guard ok"

echo "== AC 16 memory sampler (summary maths) and backend/Makefile staging guard"
python3 -m py_compile "$ST/bin/nav_stats_sampler.py" || fail "nav_stats_sampler.py does not compile"
{
    printf '# nav-stats-sampler project=t interval=2\n'
    for i in 0 1 2 3; do t=$((1000 + i * 2))
        printf '%s\thost\thost\t3000\n%s\thost_total\thost\t16000\n%s\tservice\tgateway\t10\n%s\tservice\tphoton\t700\n' "$t" "$t" "$t" "$t"
        [[ $i -eq 1 || $i -eq 2 ]] && printf '%s\tbuild\ttiles-build\t%s\n%s\tbuild\tvalhalla-build\t500\n' "$t" "$((4000 + i * 100))" "$t"
    done
    printf '# command_end=1005.5 exit=0\n'
} > "$TMP/s.tsv"
python3 "$ST/bin/nav_stats_sampler.py" --project t --data-dir "$TMP" --out "$TMP/s.tsv" --summary > "$TMP/s.json"
python3 - "$TMP/s.json" <<'PY2' || fail "sampler summary"
import json, sys
d = json.load(open(sys.argv[1]))
assert d["peak_build_mb"] == 4700 and d["peak_build_services"] == ["tiles-build", "valhalla-build"], d
assert d["steady_mb"] == 710 and d["steady_basis"] == "samples after the command", d
assert d["ac16_ratio"] == round((4700 + 710) / 16000, 3) and d["ac16_memory_pass"] is True, d
assert d["max_gap_s"] == 2.0 and d["interval_pass"] is True and d["samples"] == 4, d
print(f"   sampler: peak_build {d['peak_build_mb']} MB + steady {d['steady_mb']} MB = ratio {d['ac16_ratio']}; max gap {d['max_gap_s']} s")
PY2
: > "$TMP/staging.env"
for g in up down restart rebuild-data clean-data cors-check .env; do
    if make -s -C "$ROOT/backend" -n "$g" STAGING_ENV_FILE="$TMP/staging.env" >/dev/null 2>&1; then fail "make $g was not refused with a staging .env present"; fi
done
for g in help ps smoke contract stats build-info; do
    make -s -C "$ROOT/backend" -n "$g" STAGING_ENV_FILE="$TMP/staging.env" >/dev/null 2>&1 || fail "read-only make $g refused with a staging .env"
done
make -s -C "$ROOT/backend" -n up STAGING_ENV_FILE="$TMP/absent.env" >/dev/null 2>&1 || fail "make up refused without a staging .env (dev must keep working)"
echo "   Makefile: up/down/restart/rebuild-data/clean-data/cors-check/.env refused when infra/staging/.env exists; read-only targets and dev unaffected"

echo "== no public IPv4 literals under infra/"
hits=$(grep -rnoE '\b([0-9]{1,3}\.){3}[0-9]{1,3}\b' "$ROOT/infra" | grep -vE ':(127\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.|0\.0\.0\.0|192\.0\.2\.|198\.51\.100\.|203\.0\.113\.|1\.1$)' | grep -vE ':[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+-1~' || true)
[[ -z "$hits" ]] || { echo "$hits"; fail "public IPv4 address literal(s) in infra/ (host addresses belong in the untracked .env only)"; }
echo "OK: staging static checks passed"

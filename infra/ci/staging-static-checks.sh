#!/usr/bin/env bash
# NAV-008: static checks of infra/staging (no server, no data build; about 20 s, pulls the pinned Caddy image).
#   infra/ci/staging-static-checks.sh
# 1. shellcheck (or bash -n)  2. compose config of the overlay (IPv4 and IPv6 variants): only Caddy publishes,
# gateway forced to 127.0.0.1, rate limits on, trusted proxy = Caddy's address, images pinned
# 3. `caddy validate` + adapted-config assertions (no access log, h1/h2 only, TLS 1.2-1.3, logger include-list) for
#    the staging Caddyfile and the ops VM push-only Caddyfile (only /api/push/* to Uptime Kuma, all else 404)
# 4. systemd-analyze verify of the units  5. .env.example: every key has a comment; config parser and guards
# 6. AC 16 sampler summary maths; backend/Makefile refuses service targets when infra/staging/.env exists
# 7. no public IPv4 literal anywhere under infra/ (the real host address must never be committed)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ST=$ROOT/infra/staging
fail() { echo "FAIL: $*" >&2; exit 1; }
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

echo "== shell scripts"
scripts=("$ST"/bin/*.sh "$ST"/bin/nav-compose "$ST"/bin/nav-pipeline "$ST"/bootstrap/*.sh "$ST"/monitoring/ops-vm/*.sh "$ROOT"/infra/ci/*.sh)
for f in "${scripts[@]}"; do bash -n "$f" || fail "bash -n $f"; done
if command -v shellcheck >/dev/null; then
    shellcheck -x -P "$ST/bin" -S warning "${scripts[@]}" || fail shellcheck
else
    echo "   (shellcheck not installed: bash -n only)"
fi
for f in "$ST"/bin/*.sh "$ST"/bin/nav-compose "$ST"/bin/nav-pipeline "$ST"/bootstrap/bootstrap.sh "$ST"/monitoring/ops-vm/setup-backup-account.sh; do
    [[ "$(basename "$f")" == nav-env.sh || -x "$f" ]] || fail "not executable: $f"
done

echo "== compose config (overlay with .env.example values)"
cp "$ST/.env.example" "$TMP/stg.env"
echo "GATEWAY_BIND=0.0.0.0" >> "$TMP/stg.env"   # must NOT widen the gateway binding on staging
compose() { docker compose -p navmn --project-directory "$ROOT/backend" -f "$ROOT/backend/compose.slots.yaml" -f "$ST/compose.staging.yaml" "$@"; }
compose --env-file "$TMP/stg.env" config > "$TMP/v4.yaml" || fail "compose config (IPv4)"
compose --env-file "$TMP/stg.env" --profile '*' config > "$TMP/all.yaml" || fail "compose config (all profiles)"
python3 - "$TMP/all.yaml" <<'PY' || fail "NAV-006 lanes / verify gateway assertions"
import sys, yaml
s = yaml.safe_load(open(sys.argv[1]))["services"]
pub = {n: x.get("ports") for n, x in s.items() if x.get("ports")}
assert set(pub) == {"caddy", "gateway", "gateway-verify"}, sorted(pub)
gv = pub["gateway-verify"]
assert len(gv) == 1 and gv[0]["host_ip"] == "127.0.0.1", f"gateway-verify ports {gv}"
assert "edge" not in (s["gateway-verify"].get("networks") or {}), "gateway-verify must never be on the edge network"
for lane in ("blue", "green"):
    for svc in (f"valhalla-{lane}", f"photon-{lane}"):
        assert "edge" not in (s[svc].get("networks") or {}), svc
data = [v["source"] for v in s["gateway"]["volumes"] if v["target"] in ("/etc/nginx/slot", "/srv/slots", "/srv/packs")]
assert all(d.startswith("/var/lib/nav/data/") for d in data) and len(data) == 3, data
packs = [v for v in s["gateway"]["volumes"] if v["target"] == "/srv/packs"][0]
assert packs["source"] == "/var/lib/nav/data/packs" and packs["read_only"], packs
assert "/srv/packs" not in {v["target"] for v in s["gateway-verify"]["volumes"]}, "gateway-verify must not serve packs"
env = s["gateway"]["environment"]
assert env["GATEWAY_RATE_PACKS"] == "2r/s" and env["GATEWAY_BURST_PACKS"] == "20", (env["GATEWAY_RATE_PACKS"], env["GATEWAY_BURST_PACKS"])
print("   NAV-006: lanes and gateway-verify not on edge; gateway-verify on 127.0.0.1 only; slot root /var/lib/nav/data")
print("   NAV-020: public gateway mounts /var/lib/nav/data/packs read-only at /srv/packs; packs limit 2r/s burst 20")
PY
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
OPSD=$ST/monitoring/ops-vm
docker compose -f "$OPSD/compose.yaml" --env-file "$OPSD/.env.example" config > "$TMP/ops.yaml" || fail "ops-vm compose config"
python3 - "$TMP/ops.yaml" <<'PY' || fail "ops-vm compose assertions"
import sys, yaml
s = yaml.safe_load(open(sys.argv[1]))["services"]
assert set(s) == {"uptime-kuma", "caddy"}, sorted(s)
kp = [(p["target"], p.get("host_ip")) for p in s["uptime-kuma"]["ports"]]
assert kp == [(3001, "127.0.0.1")], f"uptime kuma ports {kp}"
cp = sorted((p["target"], p.get("protocol"), p.get("host_ip")) for p in s["caddy"]["ports"])
assert cp == [(80, "tcp", None), (443, "tcp", None)], f"ops caddy ports {cp}"
assert s["uptime-kuma"]["environment"]["UPTIME_KUMA_DB_TYPE"] == "sqlite"
for n, x in s.items():
    assert "@sha256:" in x["image"], f"{n} image not pinned by digest: {x['image']}"
print("   ops-vm: Kuma on 127.0.0.1:3001 only; Caddy publishes 80/443 tcp; images pinned by digest")
PY
if (cd "$TMP" && docker compose -f "$OPSD/compose.yaml" --project-directory "$TMP" config --quiet >/dev/null 2>&1); then
    fail "ops-vm compose must refuse to start without OPS_HOST"
fi

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

echo "== ops VM Caddyfile (caddy validate + adapted config)"
OENV=(-e OPS_HOST=ops-staging.example.invalid -e ACME_EMAIL=ops@example.invalid -e ACME_CA=https://acme-v02.api.letsencrypt.org/directory)
OPS_CADDY_IMG=$(python3 -c 'import sys,yaml; print(yaml.safe_load(open(sys.argv[1]))["services"]["caddy"]["image"])' "$TMP/ops.yaml")
[[ "$OPS_CADDY_IMG" == "$CADDY_IMG" ]] || fail "ops VM Caddy image differs from the staging Caddy image"
docker run --rm "${OENV[@]}" -v "$OPSD/Caddyfile:/etc/caddy/Caddyfile:ro" "$OPS_CADDY_IMG" \
    caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >"$TMP/ops-validate.log" 2>&1 || { cat "$TMP/ops-validate.log"; fail "caddy validate (ops)"; }
docker run --rm "${OENV[@]}" -v "$OPSD/Caddyfile:/etc/caddy/Caddyfile:ro" "$OPS_CADDY_IMG" \
    caddy adapt --config /etc/caddy/Caddyfile --adapter caddyfile > "$TMP/ops-caddy.json" 2>/dev/null || fail "caddy adapt (ops)"
python3 - "$TMP/ops-caddy.json" "$TMP/caddy.json" <<'PY' || fail "ops Caddy config assertions"
import json, sys
c = json.load(open(sys.argv[1])); stg = json.load(open(sys.argv[2]))
srv = list(c["apps"]["http"]["servers"].values())
assert len(srv) == 1, "one server block"
srv = srv[0]
assert "logs" not in srv, "access logging is on (push tokens are in the path)"
assert srv.get("protocols") == ["h1", "h2"], f"protocols {srv.get('protocols')}"
conn = srv["tls_connection_policies"][0]
assert conn.get("protocol_min") == "tls1.2" and conn.get("protocol_max") == "tls1.3", conn
assert c["logging"]["logs"]["default"] == stg["logging"]["logs"]["default"], "log config differs from the staging Caddy"
site = srv["routes"][0]
assert site["match"] == [{"host": ["ops-staging.example.invalid"]}], site["match"]
subs = site["handle"][0]["routes"]
# Caddy groups the two `handle` blocks into one subroute: /api/push/* -> Kuma first, then the catch-all 404.
push = [r for r in subs if r.get("match") == [{"path": ["/api/push/*"]}]]
rest = [r for r in subs if "match" not in r]
assert len(push) == 1 and len(rest) == 1 and len(subs) == 2, json.dumps(subs)
assert '"upstreams": [{"dial": "uptime-kuma:3001"}]' in json.dumps(push[0]), json.dumps(push[0])
assert '"status_code": 404' in json.dumps(rest[0]) and "reverse_proxy" not in json.dumps(rest[0]), json.dumps(rest[0])
assert json.dumps(srv["routes"]).count("reverse_proxy") == 1, "exactly one upstream route"
print("   ops: no access log; h1+h2; TLS 1.2-1.3; staging log include-list; /api/push/* -> uptime-kuma:3001, all else 404")
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
# NAV-006 AC 23/43: exactly one rebuild path (timer -> nav-rebuild.sh -> pipeline), interim scripts gone
grep -q 'RandomizedDelaySec=15min' "$ST/systemd/nav-rebuild.timer" && grep -q 'Persistent=true' "$ST/systemd/nav-rebuild.timer" \
    || fail "rebuild timer: RandomizedDelaySec=15min and Persistent=true"
grep -q '^ExecStart=@NAV_ROOT@/infra/staging/bin/nav-rebuild.sh$' "$ST/systemd/nav-rebuild.service" \
    && grep -q '^KillMode=mixed$' "$ST/systemd/nav-rebuild.service" && grep -q '^TimeoutStopSec=90s$' "$ST/systemd/nav-rebuild.service" \
    || fail "nav-rebuild.service must run the NAV-006 wrapper with KillMode=mixed and TimeoutStopSec=90s"
grep -q 'nav-pipeline" rebuild --scheduled' "$ST/bin/nav-rebuild.sh" || fail "nav-rebuild.sh must call the pipeline's scheduled entry point"
[[ ! -e "$ST/bin/nav-rollback-data.sh" ]] || fail "nav-rollback-data.sh must be removed (NAV-006 AC 43)"
[[ $(find "$ST/systemd" -name '*rebuild*.timer' | wc -l) == 1 ]] || fail "exactly one rebuild timer"
grep -q 'OnCalendar=\*:0/5' "$ST/systemd/nav-diskcheck.timer" || fail "diskcheck every 5 min"

echo "== .env.example and config helpers"
python3 - "$ST/.env.example" "$ROOT/backend/.env.example" "$ST/monitoring/ops-vm/.env.example" <<'PY' || fail ".env.example comments"
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
print("   every key in infra/staging/.env.example, monitoring/ops-vm/.env.example and backend/.env.example has a comment")
PY
python3 - "$ST/.env.example" <<'PY' || fail "NAV-020 pack keys in infra/staging/.env.example"
import re, sys
kv = dict(re.findall(r"^([A-Z][A-Z0-9_]*)=(.*)$", open(sys.argv[1], encoding="utf-8").read(), re.M))
want = {"PACK_ENABLED": "0", "PACK_REGION": "mn", "PACK_WEEKLY_MIN_AGE_DAYS": "7", "PACK_TILES_MIN_AGE_DAYS": "28",
        "PACK_MIN_FREE_GB": "2", "PACK_RETAIN_MANIFESTS": "3", "PACK_GZIP_LEVEL": "6", "PACK_GATE2_MODE": "engine",
        "PACK_GATE2_RATE": "5", "GATEWAY_RATE_PACKS": "2r/s", "GATEWAY_BURST_PACKS": "20"}
bad = {k: kv.get(k) for k, v in want.items() if kv.get(k) != v}
assert not bad, bad
assert "@sha256:" in kv["PACK_SEARCH_BUILDER_IMAGE"], "builder image not pinned by digest"
assert not kv["PACK_GATE2_IMAGE"].startswith("ghcr.io/valhalla/"), "upstream Valhalla image as the Gate 2 engine"
assert "OpenStreetMap" in kv["PACK_ATTRIBUTION"]
assert kv.get("PACK_METHOD_URL") == "", "PACK_METHOD_URL must stay empty in the example (no hostnames, no placeholder)"
assert not kv.get("PACK_TEST_FAULT") and not kv.get("PACK_TEST_PAUSE_AT"), "test switches in the staging example"
print("   NAV-020: pack keys at their staging defaults (disabled until the RUNBOOK 7A.8 checklist, Gate 2 mode engine)")
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

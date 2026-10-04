#!/usr/bin/env bash
# NAV-001: fast backend checks that need no data build (CI-ready; ~30 s, pulls the gateway image).
#   infra/ci/backend-static-checks.sh
# 1. shell/python syntax  2. compose config renders with defaults only (no .env), no :latest
# 3. nginx config test of the real gateway files  4. gateway behaviour without upstreams
#    (health, CORS preflight, JSON 404/405/413, 502 when upstreams are absent,
#    AC 43: 416 with single CORS headers + JSON + Cache-Control: no-store, missing archive -> JSON 404)
# 5. NAV-008 rate limits (entrypoint/16-rate-limits.sh): off by default; with GATEWAY_RATE_LIMIT=on a JSON 429
#    RateLimited with Retry-After/Cache-Control/CORS once; /health, tiles and OPTIONS never limited; bad values refused
# 6. NAV-006 (ADR-0014): compose.slots.yaml renders, publishes only on 127.0.0.1, same image pins as compose.yaml;
#    gateway slot mode (njs/slot.js): missing/invalid pointer -> JSON 502/404, valid pointer -> slot archive, a
#    pointer rename switches the archive (new ETag) without reload; pipeline unit tests
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
B=$ROOT/backend
fail() { echo "FAIL: $*" >&2; exit 1; }

echo "== syntax"
for f in "$B"/scripts/*.sh "$B"/gateway/entrypoint/*.sh "$B"/pipeline/*.sh; do bash -n "$f" || fail "bash -n $f"; done
python3 -m py_compile "$B"/scripts/*.py "$B"/pipeline/*.py "$B"/pipeline/tests/*.py \
    && rm -rf "$B/scripts/__pycache__" "$B/pipeline/__pycache__" "$B/pipeline/tests/__pycache__"
if command -v shellcheck >/dev/null; then shellcheck -x -P SCRIPTDIR -S warning "$B"/scripts/*.sh "$B"/gateway/entrypoint/*.sh "$B"/pipeline/*.sh; fi

echo "== compose config (defaults only)"
( cd "$B" && docker compose --env-file /dev/null config --quiet ) || fail "compose config"
if ( cd "$B" && docker compose --env-file /dev/null config --images ) | grep -q ':latest'; then fail "unpinned :latest image"; fi

echo "== gateway (nginx -t and behaviour without upstreams)"
IMG=$(cd "$B" && docker compose --env-file /dev/null config --images | grep nginx | head -1)
TMP=$(mktemp -d); trap 'docker rm -f navmn-ci-gw navmn-ci-gw-rl navmn-ci-gw-bad navmn-ci-gw-slot >/dev/null 2>&1 || true; docker network rm navmn-ci-net >/dev/null 2>&1 || true; rm -rf "$TMP"' EXIT
chmod 755 "$TMP"; mkdir -p "$TMP/tiles"; printf 'PMTiles\003' > "$TMP/tiles/basemap.pmtiles"; head -c 1024 /dev/zero >> "$TMP/tiles/basemap.pmtiles"; chmod -R a+rX "$TMP"
docker run -d --name navmn-ci-gw -p 127.0.0.1:18089:8080 \
  -e CORS_ALLOWED_ORIGINS=http://localhost:5173 -e GATEWAY_ERROR_LOG_LEVEL=crit \
  -e VALHALLA_UPSTREAM=valhalla:8002 -e PHOTON_UPSTREAM=photon:2322 -e NGINX_ENTRYPOINT_QUIET_LOGS=1 \
  -v "$B/gateway/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$B/gateway/templates:/etc/nginx/templates:ro" \
  -v "$B/gateway/snippets:/etc/nginx/snippets:ro" -v "$B/gateway/njs:/etc/nginx/njs:ro" \
  -v "$B/gateway/entrypoint/15-cors-origins.sh:/docker-entrypoint.d/15-cors-origins.sh:ro" \
  -v "$B/gateway/entrypoint/16-rate-limits.sh:/docker-entrypoint.d/16-rate-limits.sh:ro" \
  -v "$TMP:/srv/data:ro" "$IMG" >/dev/null
for _ in $(seq 30); do curl -fs localhost:18089/health >/dev/null && break; sleep 1; done
docker exec navmn-ci-gw nginx -t
G=http://127.0.0.1:18089
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
[[ $(code $G/health) == 200 ]] || fail "health"
curl -s -D - -o /dev/null -H 'Origin: http://localhost:5173' $G/health | tr -d '\r' | grep -qi '^access-control-expose-headers: .*Retry-After' || fail "Retry-After not exposed (openapi 0.4.0)"
docker exec navmn-ci-gw grep -q limit_req /etc/nginx/conf.d/01-rate-limits.conf && fail "rate limits must be off by default"
[[ $(code -H 'Range: bytes=0-7' $G/tiles/basemap.pmtiles) == 206 ]] || fail "tiles range"
curl -s -D - -o /dev/null -X OPTIONS -H 'Origin: http://localhost:5173' $G/v1/route | grep -qi '^access-control-allow-origin: http://localhost:5173' || fail "preflight allowed origin"
curl -s -D - -o /dev/null -X OPTIONS -H 'Origin: http://evil.example' $G/v1/route | grep -qi '^access-control-allow-origin' && fail "disallowed origin got ACAO"
[[ $(code $G/nope) == 404 ]] || fail "404"
[[ $(code -X DELETE $G/v1/search) == 405 ]] || fail "405"
[[ $(code -X POST --data-binary @<(head -c 300000 /dev/zero) $G/v1/route) == 413 ]] || fail "413"
[[ $(code -X POST -d '{}' $G/v1/route) == 502 ]] || fail "502 without upstream"
# AC 43: Range starting at the archive size -> 416, JSON, Content-Range bytes */S, each CORS header once
S=$(stat -c %s "$TMP/tiles/basemap.pmtiles")
H=$(curl -s -D - -o "$TMP/416.json" -H 'Origin: http://localhost:5173' -H "Range: bytes=$S-" $G/tiles/basemap.pmtiles | tr -d '\r')
echo "$H" | head -1 | grep -q ' 416' || fail "416 status: $(echo "$H" | head -1)"
echo "$H" | grep -qi "^content-range: bytes \*/$S\$" || fail "416 Content-Range"
echo "$H" | grep -qi '^content-type: application/json' || fail "416 Content-Type"
grep -q '"code":"RangeNotSatisfiable"' "$TMP/416.json" || fail "416 body: $(head -c 120 "$TMP/416.json")"
DUP=$(echo "$H" | grep -i '^access-control-[a-z-]*:' | cut -d: -f1 | tr 'A-Z' 'a-z' | sort | uniq -d)
[[ -z "$DUP" ]] || fail "416 repeated headers: $DUP"
[[ $(echo "$H" | grep -ci '^access-control-allow-origin: http://localhost:5173') == 1 ]] || fail "416 ACAO"
# 416 must not be cacheable (njs filter in @range_not_satisfiable): exactly one Cache-Control: no-store, no Accept-Ranges
[[ $(echo "$H" | grep -ci '^cache-control:') == 1 ]] && echo "$H" | grep -qi '^cache-control: no-store$' || fail "416 Cache-Control: $(echo "$H" | grep -i '^cache-control:' | tr '\n' ' ')"
echo "$H" | grep -qi '^accept-ranges:' && fail "416 carries Accept-Ranges"
H=$(curl -s -I -H 'Origin: http://localhost:5173' -H "Range: bytes=$S-" $G/tiles/basemap.pmtiles | tr -d '\r')
echo "$H" | head -1 | grep -q ' 416' && echo "$H" | grep -qi '^cache-control: no-store$' || fail "HEAD 416 Cache-Control"
curl -s -D - -o /dev/null -H 'Range: bytes=0-7' $G/tiles/basemap.pmtiles | tr -d '\r' | grep -qi '^cache-control: public, max-age=300$' || fail "206 Cache-Control"
curl -s -D - -o /dev/null -H 'Origin: http://evil.example' -H "Range: bytes=$S-" $G/tiles/basemap.pmtiles | grep -qi '^access-control-allow-origin' && fail "416 disallowed origin got ACAO"
DUP=$(curl -s -D - -o /dev/null -H 'Origin: http://localhost:5173' -H 'Range: bytes=0-7' $G/tiles/basemap.pmtiles | tr -d '\r' | grep -i '^access-control-[a-z-]*:' | cut -d: -f1 | tr 'A-Z' 'a-z' | sort | uniq -d)
[[ -z "$DUP" ]] || fail "206 repeated headers: $DUP"
[[ $(code -X POST $G/tiles/basemap.pmtiles) == 405 ]] || fail "tiles POST 405"
curl -s -X POST $G/tiles/basemap.pmtiles | grep -q '"code":"MethodNotAllowed"' || fail "tiles 405 not JSON"
# Missing archive -> JSON 404 (the tiles location re-declares error_page 404; see ADR-0002 Amendment 2)
rm -f "$TMP/tiles/basemap.pmtiles"
[[ $(code $G/tiles/basemap.pmtiles) == 404 ]] || fail "missing archive 404"
curl -s $G/tiles/basemap.pmtiles | grep -q '"code":"NotFound"' || fail "missing archive 404 not JSON"
echo "== gateway rate limits (NAV-008, GATEWAY_RATE_LIMIT=on, 1r/s burst 2 for a deterministic check)"
# A user-defined network gives nginx its resolver (127.0.0.11): absent upstreams fail fast (502), so a quick
# sequence of requests really exceeds the rate.
docker network inspect navmn-ci-net >/dev/null 2>&1 || docker network create navmn-ci-net >/dev/null
rl_run() {  # rl_run NAME PORT EXTRA_ENV...
  local name=$1 port=$2; shift 2
  docker run -d --name "$name" --network navmn-ci-net -p "127.0.0.1:$port:8080" \
    -e CORS_ALLOWED_ORIGINS=http://localhost:5173 -e GATEWAY_ERROR_LOG_LEVEL=crit \
    -e VALHALLA_UPSTREAM=valhalla:8002 -e PHOTON_UPSTREAM=photon:2322 -e NGINX_ENTRYPOINT_QUIET_LOGS=1 "$@" \
    -v "$B/gateway/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$B/gateway/templates:/etc/nginx/templates:ro" \
    -v "$B/gateway/snippets:/etc/nginx/snippets:ro" -v "$B/gateway/njs:/etc/nginx/njs:ro" \
    -v "$B/gateway/entrypoint/15-cors-origins.sh:/docker-entrypoint.d/15-cors-origins.sh:ro" \
    -v "$B/gateway/entrypoint/16-rate-limits.sh:/docker-entrypoint.d/16-rate-limits.sh:ro" \
    -v "$TMP:/srv/data:ro" "$IMG" >/dev/null
}
printf 'PMTiles\003' > "$TMP/tiles/basemap.pmtiles"; head -c 1024 /dev/zero >> "$TMP/tiles/basemap.pmtiles"; chmod a+r "$TMP/tiles/basemap.pmtiles"
rl_run navmn-ci-gw-rl 18090 -e GATEWAY_RATE_LIMIT=on -e GATEWAY_RATE_ROUTE=1r/s -e GATEWAY_BURST_ROUTE=2 \
  -e GATEWAY_RATE_SEARCH=1r/s -e GATEWAY_BURST_SEARCH=2
for _ in $(seq 30); do curl -fs localhost:18090/health >/dev/null && break; sleep 1; done
R=http://127.0.0.1:18090
# never limited: 30 x health, tiles range, OPTIONS on /v1/route and /v1/search
for _ in $(seq 30); do
  for c in "$(code $R/health)" "$(code -H 'Range: bytes=0-7' $R/tiles/basemap.pmtiles)" \
           "$(code -X OPTIONS -H 'Origin: http://localhost:5173' $R/v1/route)" "$(code -X OPTIONS $R/v1/search)"; do
    [[ $c != 429 ]] || fail "health/tiles/OPTIONS was rate limited"
  done
done
# route and search+reverse: 6 quick requests each -> at least one 429 in the contract shape
for p in /v1/route "/v1/search?q=a&lang=mn" "/v1/reverse?lat=47.9&lon=106.9&lang=mn"; do
  got=$(seq 8 | xargs -P 8 -I{} curl -s -o /dev/null -w '%{http_code} ' "$R$p")
  [[ $got == *429* ]] || fail "no 429 on $p: $got"
done
seq 8 | xargs -P 8 -I{} curl -s -o /dev/null "$R/v1/route"
H=$(curl -s -D - -o "$TMP/429.json" -H 'Origin: http://localhost:5173' $R/v1/route | tr -d '\r')
echo "$H" | head -1 | grep -q ' 429' || fail "429 status: $(echo "$H" | head -1)"
grep -q '^{"code":"RateLimited","message":"Too many requests"}$' "$TMP/429.json" || fail "429 body: $(head -c 120 "$TMP/429.json")"
echo "$H" | grep -qi '^content-type: application/json' || fail "429 Content-Type"
[[ $(echo "$H" | grep -ci '^retry-after:') == 1 ]] && echo "$H" | grep -qiE '^retry-after: [1-9][0-9]*$' || fail "429 Retry-After"
[[ $(echo "$H" | grep -ci '^cache-control:') == 1 ]] && echo "$H" | grep -qi '^cache-control: no-store$' || fail "429 Cache-Control"
DUP=$(echo "$H" | grep -i '^access-control-[a-z-]*:' | cut -d: -f1 | tr 'A-Z' 'a-z' | sort | uniq -d)
[[ -z "$DUP" ]] || fail "429 repeated headers: $DUP"
[[ $(echo "$H" | grep -ci '^access-control-allow-origin: http://localhost:5173') == 1 ]] || fail "429 ACAO"
echo "$H" | grep -qi '^access-control-expose-headers: .*Retry-After' || fail "429 does not expose Retry-After"
docker logs navmn-ci-gw-rl 2>&1 | grep -qiE 'limiting requests|client: ' && fail "limit_req lines reached the log"
# the access log reaches `docker logs` asynchronously: wait up to 5 s for the 429 line (was flaky without this)
for _ in $(seq 25); do docker logs navmn-ci-gw-rl 2>&1 | grep -qE '"status":429' && break; sleep 0.2; done
docker logs navmn-ci-gw-rl 2>&1 | grep -qE '"status":429' || fail "429 not in the access log"
docker logs navmn-ci-gw-rl 2>&1 | grep -qE '([0-9]{1,3}\.){3}[0-9]{1,3}' && fail "an IPv4 address reached the gateway log"
# invalid values are refused at start (the container exits instead of running unlimited)
rl_run navmn-ci-gw-bad 18091 -e GATEWAY_RATE_LIMIT=on -e GATEWAY_RATE_ROUTE=lots
for _ in $(seq 20); do [[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] && break; sleep 0.5; done
[[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] || fail "invalid GATEWAY_RATE_ROUTE was accepted"
echo "== NAV-006 compose.slots.yaml (ADR-0014)"
printf 'NAV_COMPOSE_PROJECT=ci-slots\nNAV_DATA_ROOT=/nonexistent/nav-data\n' > "$TMP/slots.env"
( cd "$B" && docker compose -f compose.slots.yaml --env-file "$TMP/slots.env" --profile '*' config ) > "$TMP/slots.yaml" \
    || fail "compose.slots.yaml config"
( cd "$B" && docker compose -f compose.slots.yaml --env-file /dev/null config --quiet >/dev/null 2>&1 ) \
    && fail "compose.slots.yaml must require NAV_COMPOSE_PROJECT (no default: navmn would hit the dev stack)"
( cd "$B" && docker compose --env-file /dev/null config ) > "$TMP/dev.yaml"
python3 - "$TMP/slots.yaml" "$TMP/dev.yaml" <<'PY' || fail "compose.slots.yaml assertions"
import sys, yaml
s = yaml.safe_load(open(sys.argv[1]))["services"]; d = yaml.safe_load(open(sys.argv[2]))["services"]
want = {"gateway", "gateway-verify", "valhalla-blue", "photon-blue", "valhalla-green", "photon-green",
        "aux-fetch", "tiles-build", "valhalla-build", "photon-import", "build-info"}
assert set(s) == want, sorted(set(s) ^ want)
pub = {n: x["ports"] for n, x in s.items() if x.get("ports")}
assert set(pub) == {"gateway", "gateway-verify"}, sorted(pub)
for n, ports in pub.items():
    assert all(p["host_ip"] == "127.0.0.1" for p in ports), (n, ports)
for n, x in s.items():
    assert not x["image"].endswith(":latest"), n
# same pinned images as the dev stack
pins = {d[k]["image"] for k in ("valhalla", "photon", "gateway")}
for n, x in s.items():
    assert x["image"] in pins, f"{n}: {x['image']} not pinned like compose.yaml"
for lane in ("blue", "green"):
    assert s[f"valhalla-{lane}"]["profiles"] == [f"lane-{lane}"] and s[f"photon-{lane}"]["profiles"] == [f"lane-{lane}"]
    vm = [v for v in s[f"valhalla-{lane}"]["volumes"] if v["target"] == "/data"][0]
    assert vm["source"].endswith(f"/lanes/{lane}") and vm["read_only"], vm
assert s["gateway-verify"]["profiles"] == ["verify"] and s["gateway-verify"]["environment"]["GATEWAY_RATE_LIMIT"] == "off"
mounts = {v["target"]: v for v in s["gateway"]["volumes"]}
assert mounts["/etc/nginx/slot"]["source"].endswith("/pointer/public") and mounts["/etc/nginx/slot"]["read_only"]
assert {v["target"]: v for v in s["gateway-verify"]["volumes"]}["/etc/nginx/slot"]["source"].endswith("/pointer/verify")
for b in ("tiles-build", "valhalla-build", "photon-import", "build-info"):
    assert s[b]["profiles"] == ["build"] and s[b]["restart"] == "no"
    assert [v for v in s[b]["volumes"] if v["target"] == "/data"][0]["source"].endswith("/lanes/build")
print("   compose.slots.yaml: 11 services, only gateway + gateway-verify publish (127.0.0.1), pins = compose.yaml")
PY

echo "== NAV-006 gateway slot mode (njs/slot.js, pointer read per request)"
SL=$TMP/slotroot; A=20261004T000000Z; Bs=20261004T000001Z
mkdir -p "$SL/pointer" "$SL/slots/$A/tiles" "$SL/slots/$Bs/tiles"
printf 'PMTiles\003' > "$SL/slots/$A/tiles/basemap.pmtiles"; head -c 1000 /dev/zero >> "$SL/slots/$A/tiles/basemap.pmtiles"
printf 'PMTiles\003' > "$SL/slots/$Bs/tiles/basemap.pmtiles"; head -c 2000 /dev/zero >> "$SL/slots/$Bs/tiles/basemap.pmtiles"
chmod -R a+rX "$SL"
docker run -d --name navmn-ci-gw-slot --network navmn-ci-net -p 127.0.0.1:18092:8080 \
  -e CORS_ALLOWED_ORIGINS='*' -e GATEWAY_ERROR_LOG_LEVEL=crit -e VALHALLA_UPSTREAM=127.0.0.1:9 \
  -e PHOTON_UPSTREAM=127.0.0.1:9 -e NGINX_ENTRYPOINT_QUIET_LOGS=1 \
  -v "$B/gateway/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$B/gateway/templates:/etc/nginx/templates:ro" \
  -v "$B/gateway/snippets:/etc/nginx/snippets:ro" -v "$B/gateway/njs:/etc/nginx/njs:ro" \
  -v "$B/gateway/entrypoint/15-cors-origins.sh:/docker-entrypoint.d/15-cors-origins.sh:ro" \
  -v "$B/gateway/entrypoint/16-rate-limits.sh:/docker-entrypoint.d/16-rate-limits.sh:ro" \
  -v "$SL/pointer:/etc/nginx/slot:ro" -v "$SL/slots:/srv/slots:ro" "$IMG" >/dev/null
for _ in $(seq 30); do curl -fs localhost:18092/health >/dev/null && break; sleep 1; done
S=http://127.0.0.1:18092
slot_errors() {  # $1 = label: route/search/reverse -> JSON 502, tiles -> JSON 404, health 200
  [[ $(code $S/health) == 200 ]] || fail "slot mode ($1): health"
  curl -s -X POST -d '{}' $S/v1/route | grep -q '"code":"UpstreamUnavailable"' || fail "slot mode ($1): route not JSON 502"
  [[ $(code "$S/v1/search?q=a") == 502 && $(code "$S/v1/reverse?lat=47.9&lon=106.9") == 502 ]] || fail "slot mode ($1): search/reverse 502"
  [[ $(code -H 'Range: bytes=0-7' $S/tiles/basemap.pmtiles) == 404 ]] || fail "slot mode ($1): tiles 404"
  curl -s $S/tiles/basemap.pmtiles | grep -q '"code":"NotFound"' || fail "slot mode ($1): tiles 404 not JSON"
}
slot_errors "no pointer"
echo '{not json' > "$SL/pointer/active.json"; chmod a+r "$SL/pointer/active.json"; slot_errors "invalid JSON"
printf '{"slot":"%s","lane":"blue","valhalla":"evil.example.invalid:80","photon":"photon-red:2322","tiles":"/etc/passwd"}' "$A" \
    > "$SL/pointer/active.json"; slot_errors "foreign upstream and path"
printf '{"slot":"%s","lane":"blue","valhalla":"valhalla-blue:8002","photon":"photon-blue:2322","tiles":"/srv/slots/%s/tiles/basemap.pmtiles"}' "$A" "$A" \
    > "$SL/pointer/.tmp"; chmod a+r "$SL/pointer/.tmp"; mv "$SL/pointer/.tmp" "$SL/pointer/active.json"
H1=$(curl -s -D - -o /dev/null -H 'Range: bytes=0-7' $S/tiles/basemap.pmtiles | tr -d '\r')
echo "$H1" | head -1 | grep -q ' 206' && echo "$H1" | grep -qi '^content-range: bytes 0-7/1008$' || fail "slot A archive: $H1"
[[ $(code -X POST -d '{}' $S/v1/route) == 502 ]] || fail "valid pointer, absent lane must be 502"
E1=$(echo "$H1" | sed -n 's/^[Ee][Tt][Aa][Gg]: //p')
printf '{"slot":"%s","lane":"green","valhalla":"valhalla-green:8002","photon":"photon-green:2322","tiles":"/srv/slots/%s/tiles/basemap.pmtiles"}' "$Bs" "$Bs" \
    > "$SL/pointer/.tmp"; chmod a+r "$SL/pointer/.tmp"; mv "$SL/pointer/.tmp" "$SL/pointer/active.json"
H2=$(curl -s -D - -o /dev/null -H 'Range: bytes=0-7' $S/tiles/basemap.pmtiles | tr -d '\r')
echo "$H2" | grep -qi '^content-range: bytes 0-7/2008$' || fail "rename did not switch the archive: $H2"
E2=$(echo "$H2" | sed -n 's/^[Ee][Tt][Aa][Gg]: //p')
[[ -n "$E1" && -n "$E2" && "$E1" != "$E2" ]] || fail "ETag must change with the archive ($E1 -> $E2)"
[[ $(docker inspect -f '{{.RestartCount}} {{.State.Running}}' navmn-ci-gw-slot) == "0 true" ]] || fail "gateway restarted"
docker logs navmn-ci-gw-slot 2>&1 | grep -qiE 'reload|signal process started' && fail "switch must not reload nginx"
echo "   slot mode: no/invalid/foreign pointer -> JSON 502/404; rename switched the archive (ETag $E1 -> $E2), no reload"

echo "== NAV-006 pipeline unit tests"
python3 -m unittest discover -s "$B/pipeline/tests" 2>&1 | tail -n 3
python3 -m unittest discover -s "$B/pipeline/tests" >/dev/null 2>&1 || fail "pipeline unit tests"
rm -rf "$B/pipeline/__pycache__" "$B/pipeline/tests/__pycache__"

echo "OK: backend static checks passed"

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
# 7. NAV-020 (ADR-0017): /packs/ static locations (manifest no-cache + strong ETag + 304; files immutable, Range,
#    If-Range, 416 no-store, no Content-Encoding; other /packs/ paths JSON 404), the "packs" rate-limit zone, the
#    compose mount, pack + search-builder unit tests, and the Gate 2 recipe rules (no patching, never the upstream image)
# 8. NAV-022 static pack hosting: the static-host test project renders (digest-pinned httpd, loopback port only,
#    read-only document root); the export + .htaccess unit tests run with the pipeline tests. The full httpd run is
#    `make -C backend pack-export-test` (pulls ~110 MB, not part of this script).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
B=$ROOT/backend
fail() { echo "FAIL: $*" >&2; exit 1; }

echo "== syntax"
for f in "$B"/scripts/*.sh "$B"/gateway/entrypoint/*.sh "$B"/pipeline/*.sh; do bash -n "$f" || fail "bash -n $f"; done
python3 -m py_compile "$B"/scripts/*.py "$B"/pipeline/*.py "$B"/pipeline/tests/*.py "$B"/pack/*.py "$B"/pack/tests/*.py \
    && rm -rf "$B/scripts/__pycache__" "$B/pipeline/__pycache__" "$B/pipeline/tests/__pycache__" "$B/pack/__pycache__" "$B/pack/tests/__pycache__"
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
  -e GATEWAY_RATE_SEARCH=1r/s -e GATEWAY_BURST_SEARCH=2 -e GATEWAY_RATE_PACKS=1r/s -e GATEWAY_BURST_PACKS=2
for _ in $(seq 30); do curl -fs localhost:18090/health >/dev/null && break; sleep 1; done
R=http://127.0.0.1:18090
# never limited: 30 x health, tiles range, OPTIONS on /v1/route and /v1/search
for _ in $(seq 30); do
  for c in "$(code $R/health)" "$(code -H 'Range: bytes=0-7' $R/tiles/basemap.pmtiles)" \
           "$(code -X OPTIONS -H 'Origin: http://localhost:5173' $R/v1/route)" "$(code -X OPTIONS $R/v1/search)" \
           "$(code -X OPTIONS $R/packs/mn/manifest.json)"; do
    [[ $c != 429 ]] || fail "health/tiles/OPTIONS was rate limited"
  done
done
# NAV-020 AC 21: the packs zone (1r/s burst 2 here) limits /packs/ on its own, in the contract shape
got=$(seq 8 | xargs -P 8 -I{} curl -s -o /dev/null -w '%{http_code} ' "$R/packs/mn/manifest.json")
[[ $got == *429* ]] || fail "no 429 on /packs/: $got"
seq 8 | xargs -P 8 -I{} curl -s -o /dev/null "$R/packs/mn/manifest.json"
H=$(curl -s -D - -o "$TMP/429p.json" -H 'Origin: http://localhost:5173' $R/packs/mn/manifest.json | tr -d '\r')
echo "$H" | head -1 | grep -q ' 429' && grep -q '"code":"RateLimited"' "$TMP/429p.json" || fail "packs 429 shape: $(echo "$H" | head -1)"
[[ $(echo "$H" | grep -ci '^retry-after:') == 1 && $(echo "$H" | grep -ci '^cache-control: no-store$') == 1 ]] || fail "packs 429 headers"
[[ $(echo "$H" | grep -ci '^access-control-allow-origin:') == 1 ]] || fail "packs 429 ACAO"
docker exec navmn-ci-gw-rl grep -q 'zone=nav_packs' /etc/nginx/conf.d/01-rate-limits.conf || fail "nav_packs zone missing"
sleep 2
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
# the access log reaches `docker logs` asynchronously: wait up to 15 s for the 429 line (5 s was flaky on a loaded host)
for _ in $(seq 75); do docker logs navmn-ci-gw-rl 2>&1 | grep -qE '"status":429' && break; sleep 0.2; done
docker logs navmn-ci-gw-rl 2>&1 | grep -qE '"status":429' || fail "429 not in the access log"
docker logs navmn-ci-gw-rl 2>&1 | grep -qE '([0-9]{1,3}\.){3}[0-9]{1,3}' && fail "an IPv4 address reached the gateway log"
# invalid values are refused at start (the container exits instead of running unlimited)
rl_run navmn-ci-gw-bad 18091 -e GATEWAY_RATE_LIMIT=on -e GATEWAY_RATE_ROUTE=lots
for _ in $(seq 20); do [[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] && break; sleep 0.5; done
[[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] || fail "invalid GATEWAY_RATE_ROUTE was accepted"
docker rm -f navmn-ci-gw-bad >/dev/null
rl_run navmn-ci-gw-bad 18091 -e GATEWAY_RATE_LIMIT=on -e GATEWAY_RATE_PACKS=0r/s
for _ in $(seq 20); do [[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] && break; sleep 0.5; done
[[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] || fail "invalid GATEWAY_RATE_PACKS was accepted"
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
assert mounts["/srv/packs"]["source"].endswith("/packs") and mounts["/srv/packs"]["read_only"], "NAV-020 packs mount"
assert "/srv/packs" not in {v["target"] for v in s["gateway-verify"]["volumes"]}, "gateway-verify must not serve packs"
assert "/srv/packs" not in {v["target"] for v in d["gateway"]["volumes"]}, "the dev stack serves no packs (404)"
assert {v["target"]: v for v in s["gateway-verify"]["volumes"]}["/etc/nginx/slot"]["source"].endswith("/pointer/verify")
for b in ("tiles-build", "valhalla-build", "photon-import", "build-info"):
    assert s[b]["profiles"] == ["build"] and s[b]["restart"] == "no"
    assert [v for v in s[b]["volumes"] if v["target"] == "/data"][0]["source"].endswith("/lanes/build")
print("   compose.slots.yaml: 11 services, only gateway + gateway-verify publish (127.0.0.1), pins = compose.yaml")
PY

echo "== NAV-006 gateway slot mode (njs/slot.js, pointer read per request)"
SL=$TMP/slotroot; A=20261004T000000Z; Bs=20261004T000001Z
mkdir -p "$SL/pointer" "$SL/slots/$A/tiles" "$SL/slots/$Bs/tiles" "$SL/packs/mn/$A" "$SL/packs/mn/$A.partial" "$SL/packs/mn/.manifests"
head -c 300000 /dev/urandom | gzip -n -c > "$SL/packs/mn/$A/routing.tar.gz"
cp "$SL/packs/mn/$A/routing.tar.gz" "$SL/packs/mn/$A.partial/routing.tar.gz"
PZ=$(stat -c %s "$SL/packs/mn/$A/routing.tar.gz"); PSHA=$(sha256sum "$SL/packs/mn/$A/routing.tar.gz" | cut -d' ' -f1)
printf '{"pack_schema":1,"region":"mn","pack_version":"%s"}\n' "$A" > "$SL/packs/mn/manifest.json"
echo '[]' > "$SL/packs/mn/.history.json"
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
  -v "$SL/pointer:/etc/nginx/slot:ro" -v "$SL/slots:/srv/slots:ro" -v "$SL/packs:/srv/packs:ro" "$IMG" >/dev/null
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

echo "== NAV-020 /packs/ static locations (openapi 0.6.0 getOfflinePackManifest / getOfflinePackFile)"
O='Origin: http://localhost:5173'
H=$(curl -s -D - -o /dev/null -H "$O" -H 'Accept-Encoding: gzip' $S/packs/mn/manifest.json | tr -d '\r')
echo "$H" | head -1 | grep -q ' 200' || fail "manifest 200: $(echo "$H" | head -1)"
[[ $(echo "$H" | grep -ci '^cache-control: no-cache$') == 1 && $(echo "$H" | grep -ci '^cache-control:') == 1 ]] || fail "manifest Cache-Control"
echo "$H" | grep -qi '^content-type: application/json' || fail "manifest Content-Type"
echo "$H" | grep -qi '^content-encoding:' && fail "manifest must not be gzip-encoded (strong ETag)"
ME=$(echo "$H" | sed -n 's/^[Ee][Tt][Aa][Gg]: //p'); [[ "$ME" == \"*\" ]] || fail "manifest ETag not strong: $ME"
[[ $(echo "$H" | grep -ci '^access-control-allow-origin:') == 1 ]] || fail "manifest ACAO"
[[ $(code -H "If-None-Match: $ME" $S/packs/mn/manifest.json) == 304 ]] || fail "manifest 304"
F=$S/packs/mn/$A/routing.tar.gz
H=$(curl -s -D - -o "$TMP/p.gz" -H "$O" -H 'Accept-Encoding: gzip' "$F" | tr -d '\r')
echo "$H" | head -1 | grep -q ' 200' || fail "pack file 200: $(echo "$H" | head -1)"
[[ "$(sha256sum "$TMP/p.gz" | cut -d' ' -f1)" == "$PSHA" ]] || fail "pack file body SHA-256"
echo "$H" | grep -qi "^content-length: $PZ\$" || fail "pack file Content-Length"
echo "$H" | grep -qi '^content-encoding:' && fail "pack file carries Content-Encoding"
[[ $(echo "$H" | grep -ci '^cache-control:') == 1 ]] && echo "$H" | grep -qi '^cache-control: public, max-age=31536000, immutable$' || fail "pack file Cache-Control"
echo "$H" | grep -qi '^accept-ranges: bytes$' || fail "pack file Accept-Ranges"
PE=$(echo "$H" | sed -n 's/^[Ee][Tt][Aa][Gg]: //p'); [[ "$PE" == \"*\" ]] || fail "pack file ETag not strong: $PE"
HALF=$((PZ / 2))
H=$(curl -s -D - -o "$TMP/p2" -H "Range: bytes=$HALF-" -H "If-Range: $PE" "$F" | tr -d '\r')
echo "$H" | head -1 | grep -q ' 206' && echo "$H" | grep -qi "^content-range: bytes $HALF-$((PZ - 1))/$PZ\$" || fail "pack file 206: $(echo "$H" | head -3 | tr '\n' ' ')"
echo "$H" | grep -qi '^accept-ranges: bytes$' || fail "pack file 206 Accept-Ranges"
[[ "$( (head -c "$HALF" "$TMP/p.gz"; cat "$TMP/p2") | sha256sum | cut -d' ' -f1)" == "$PSHA" ]] || fail "resume halves do not hash to the file"
[[ $(curl -s -o "$TMP/p3" -w '%{http_code}' -H "Range: bytes=$HALF-" -H 'If-Range: "stale"' "$F") == 200 && $(stat -c %s "$TMP/p3") == "$PZ" ]] || fail "If-Range mismatch must give the whole file"
H=$(curl -s -D - -o "$TMP/p416" -H "$O" -H "Range: bytes=$PZ-" "$F" | tr -d '\r')
echo "$H" | head -1 | grep -q ' 416' && echo "$H" | grep -qi "^content-range: bytes \*/$PZ\$" || fail "pack 416"
[[ $(echo "$H" | grep -ci '^cache-control:') == 1 ]] && echo "$H" | grep -qi '^cache-control: no-store$' || fail "pack 416 Cache-Control"
echo "$H" | grep -qi '^accept-ranges:' && fail "pack 416 carries Accept-Ranges"
grep -q '"code":"RangeNotSatisfiable"' "$TMP/p416" || fail "pack 416 body"
[[ $(code -H "If-None-Match: $PE" "$F") == 304 ]] || fail "pack file 304"
[[ $(code -X POST "$F") == 405 ]] || fail "pack file POST 405"
[[ $(code -X OPTIONS -H "$O" "$F") == 204 ]] || fail "pack file preflight"
for p in /packs/xx/manifest.json /packs/mn/.history.json /packs/mn/.manifests/ "/packs/mn/$A.partial/routing.tar.gz" \
         "/packs/mn/$A/routing.tar" "/packs/mn/$A/other.sqlite.gz" /packs/mn/2026-10-04/routing.tar.gz \
         "/packs/mn/$Bs/routing.tar.gz" /packs/mn/ /packs/; do
  [[ $(code "$S$p") == 404 ]] || fail "$p must be 404"
  curl -s "$S$p" | grep -q '"code":"NotFound"' || fail "$p 404 not JSON"
done
rm "$SL/packs/mn/manifest.json"
[[ $(code $S/packs/mn/manifest.json) == 404 ]] && curl -s $S/packs/mn/manifest.json | grep -q '"code":"NotFound"' || fail "no manifest -> JSON 404"
# the manifest is replaced by rename(2): the next request sees the new file and a new ETag (newer mtime), no reload
printf '{"pack_schema":1,"region":"mn","pack_version":"%s"}\n' "$A" > "$SL/packs/mn/.m1"; touch -d '2026-10-04 00:00:00' "$SL/packs/mn/.m1"
chmod a+r "$SL/packs/mn/.m1"; mv "$SL/packs/mn/.m1" "$SL/packs/mn/manifest.json"
E1=$(curl -s -D - -o /dev/null $S/packs/mn/manifest.json | tr -d '\r' | sed -n 's/^[Ee][Tt][Aa][Gg]: //p')
printf '{"pack_schema":1,"region":"mn","pack_version":"%s"}\n' "$Bs" > "$SL/packs/mn/.m2"; touch -d '2026-10-04 00:00:01' "$SL/packs/mn/.m2"
chmod a+r "$SL/packs/mn/.m2"; mv "$SL/packs/mn/.m2" "$SL/packs/mn/manifest.json"
B2=$(curl -s $S/packs/mn/manifest.json); E2=$(curl -s -D - -o /dev/null $S/packs/mn/manifest.json | tr -d '\r' | sed -n 's/^[Ee][Tt][Aa][Gg]: //p')
[[ "$B2" == *"$Bs"* && -n "$E1" && "$E1" != "$E2" ]] || fail "manifest rename not picked up ($E1 -> $E2)"
docker logs navmn-ci-gw-slot 2>&1 | grep -qE '([0-9]{1,3}\.){3}[0-9]{1,3}' && fail "an IPv4 address reached the gateway log"
echo "   packs: manifest 200/304/404 (no-cache, strong ETag, not encoded); file 200/206/304/416 (immutable, no Content-Encoding, If-Range); other paths JSON 404; rename picked up"

echo "== NAV-020 Gate 2 recipe and pins"
DF=$B/gate2/Dockerfile
grep -v '^[[:space:]]*#' "$DF" | grep -qE '(\bpatch\b|\bsed\b|git apply|git am )' && fail "the Gate 2 recipe must not patch fetched sources"
grep -v '^[[:space:]]*#' "$DF" | grep -q 'valhalla/valhalla:' && fail "the Gate 2 recipe must not use the upstream Valhalla image"
grep -q 'WRAPPER_COMMIT=b47ad5a9aa5d907df329bd2a0bfcc9080220c9d8' "$DF" && grep -q 'VALHALLA_COMMIT=e2f017b16080f49203de245a211b09efab09cf72' "$DF" \
    && grep -q 'VCPKG_BASELINE=f176b58f35a75f9f8f54099cd9df97d2e2793a2e' "$DF" || fail "Gate 2 pins (ADR-0017 A1 F1)"
grep -q '^FROM \${BASE_IMAGE}' "$DF" && grep -q 'BASE_IMAGE=ubuntu:24.04@sha256:' "$DF" || fail "Gate 2 base image not pinned by digest"
[[ "$(sha256sum "$B/gate2/default.json" | cut -d' ' -f1)" == "$(sed -n 's/^GATE2_DEFAULT_JSON_SHA256 = "\(.*\)"/\1/p' "$B/pipeline/nav_pack.py")" ]] \
    || fail "backend/gate2/default.json differs from the pinned AAR copy"
echo "   Gate 2 recipe: pinned commits and base image, no patching, default.json = AAR copy"

echo "== NAV-022 static-host test project (render only)"
NAV022_WWW=/nonexistent-nav022 docker compose -f "$B/pack/static-host-test/compose.yaml" config --format json > "$TMP/nav022.json" \
    || fail "pack/static-host-test/compose.yaml does not render"
python3 - "$TMP/nav022.json" <<'PY' || fail "NAV-022 static-host test project rules"
import json, sys
c = json.load(open(sys.argv[1]))
assert c["name"] == "navmn-nav022", c["name"]
s = c["services"]["static"]
assert "@sha256:" in s["image"], "httpd image must be pinned by digest"
assert all(p.get("host_ip") == "127.0.0.1" for p in s["ports"]), "loopback only"
assert all(p.get("published") != "8080" for p in s["ports"]), "never the dev gateway port"
assert {v["target"]: v.get("read_only") for v in s["volumes"]}["/usr/local/apache2/htdocs"], "document root read-only"
PY
echo "   static-host test project: navmn-nav022, digest-pinned, 127.0.0.1 only, read-only docroot"

echo "== NAV-006 + NAV-020 + NAV-022 pipeline unit tests, NAV-020 search builder tests"
python3 -m unittest discover -s "$B/pipeline/tests" > "$TMP/ut.log" 2>&1 || { tail -n 30 "$TMP/ut.log"; fail "pipeline unit tests"; }
tail -n 3 "$TMP/ut.log"
python3 -m unittest discover -s "$B/pack/tests" > "$TMP/ut2.log" 2>&1 || { tail -n 30 "$TMP/ut2.log"; fail "search builder unit tests"; }
tail -n 3 "$TMP/ut2.log"
rm -rf "$B/pipeline/__pycache__" "$B/pipeline/tests/__pycache__" "$B/pack/__pycache__" "$B/pack/tests/__pycache__"

echo "OK: backend static checks passed"

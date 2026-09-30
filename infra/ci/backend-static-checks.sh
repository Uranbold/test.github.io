#!/usr/bin/env bash
# NAV-001: fast backend checks that need no data build (CI-ready; ~30 s, pulls the gateway image).
#   infra/ci/backend-static-checks.sh
# 1. shell/python syntax  2. compose config renders with defaults only (no .env), no :latest
# 3. nginx config test of the real gateway files  4. gateway behaviour without upstreams
#    (health, CORS preflight, JSON 404/405/413, 502 when upstreams are absent,
#    AC 43: 416 with single CORS headers + JSON + Cache-Control: no-store, missing archive -> JSON 404)
# 5. NAV-008 rate limits (entrypoint/16-rate-limits.sh): off by default; with GATEWAY_RATE_LIMIT=on a JSON 429
#    RateLimited with Retry-After/Cache-Control/CORS once; /health, tiles and OPTIONS never limited; bad values refused
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
B=$ROOT/backend
fail() { echo "FAIL: $*" >&2; exit 1; }

echo "== syntax"
for f in "$B"/scripts/*.sh "$B"/gateway/entrypoint/*.sh; do bash -n "$f" || fail "bash -n $f"; done
python3 -m py_compile "$B"/scripts/*.py && rm -rf "$B/scripts/__pycache__"
if command -v shellcheck >/dev/null; then shellcheck -x -P SCRIPTDIR -S warning "$B"/scripts/*.sh "$B"/gateway/entrypoint/*.sh; fi

echo "== compose config (defaults only)"
( cd "$B" && docker compose --env-file /dev/null config --quiet ) || fail "compose config"
if ( cd "$B" && docker compose --env-file /dev/null config --images ) | grep -q ':latest'; then fail "unpinned :latest image"; fi

echo "== gateway (nginx -t and behaviour without upstreams)"
IMG=$(cd "$B" && docker compose --env-file /dev/null config --images | grep nginx | head -1)
TMP=$(mktemp -d); trap 'docker rm -f navmn-ci-gw navmn-ci-gw-rl navmn-ci-gw-bad >/dev/null 2>&1 || true; docker network rm navmn-ci-net >/dev/null 2>&1 || true; rm -rf "$TMP"' EXIT
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
docker logs navmn-ci-gw-rl 2>&1 | grep -qE '"status":429' || fail "429 not in the access log"
docker logs navmn-ci-gw-rl 2>&1 | grep -qE '([0-9]{1,3}\.){3}[0-9]{1,3}' && fail "an IPv4 address reached the gateway log"
# invalid values are refused at start (the container exits instead of running unlimited)
rl_run navmn-ci-gw-bad 18091 -e GATEWAY_RATE_LIMIT=on -e GATEWAY_RATE_ROUTE=lots
for _ in $(seq 20); do [[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] && break; sleep 0.5; done
[[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] || fail "invalid GATEWAY_RATE_ROUTE was accepted"
echo "OK: backend static checks passed"

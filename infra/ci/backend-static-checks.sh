#!/usr/bin/env bash
# NAV-001: fast backend checks that need no data build (CI-ready; ~30 s, pulls the gateway image).
#   infra/ci/backend-static-checks.sh
# 1. shell/python syntax  2. compose config renders with defaults only (no .env), no :latest
# 3. nginx config test of the real gateway files  4. gateway behaviour without upstreams
#    (health, CORS preflight, JSON 404/405/413, 502 when upstreams are absent,
#    AC 43: 416 with single CORS headers + JSON, missing archive -> JSON 404)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
B=$ROOT/backend
fail() { echo "FAIL: $*" >&2; exit 1; }

echo "== syntax"
for f in "$B"/scripts/*.sh "$B"/gateway/entrypoint/*.sh; do bash -n "$f" || fail "bash -n $f"; done
python3 -m py_compile "$B"/scripts/*.py && rm -rf "$B/scripts/__pycache__"
if command -v shellcheck >/dev/null; then shellcheck -S warning "$B"/scripts/*.sh "$B"/gateway/entrypoint/*.sh; fi

echo "== compose config (defaults only)"
( cd "$B" && docker compose --env-file /dev/null config --quiet ) || fail "compose config"
if ( cd "$B" && docker compose --env-file /dev/null config --images ) | grep -q ':latest'; then fail "unpinned :latest image"; fi

echo "== gateway (nginx -t and behaviour without upstreams)"
IMG=$(cd "$B" && docker compose --env-file /dev/null config --images | grep nginx | head -1)
TMP=$(mktemp -d); trap 'docker rm -f navmn-ci-gw >/dev/null 2>&1 || true; rm -rf "$TMP"' EXIT
chmod 755 "$TMP"; mkdir -p "$TMP/tiles"; printf 'PMTiles\003' > "$TMP/tiles/basemap.pmtiles"; head -c 1024 /dev/zero >> "$TMP/tiles/basemap.pmtiles"; chmod -R a+rX "$TMP"
docker run -d --name navmn-ci-gw -p 127.0.0.1:18089:8080 \
  -e CORS_ALLOWED_ORIGINS=http://localhost:5173 -e GATEWAY_ERROR_LOG_LEVEL=crit \
  -e VALHALLA_UPSTREAM=valhalla:8002 -e PHOTON_UPSTREAM=photon:2322 -e NGINX_ENTRYPOINT_QUIET_LOGS=1 \
  -v "$B/gateway/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$B/gateway/templates:/etc/nginx/templates:ro" \
  -v "$B/gateway/snippets:/etc/nginx/snippets:ro" \
  -v "$B/gateway/entrypoint/15-cors-origins.sh:/docker-entrypoint.d/15-cors-origins.sh:ro" \
  -v "$TMP:/srv/data:ro" "$IMG" >/dev/null
for _ in $(seq 30); do curl -fs localhost:18089/health >/dev/null && break; sleep 1; done
docker exec navmn-ci-gw nginx -t
G=http://127.0.0.1:18089
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
[[ $(code $G/health) == 200 ]] || fail "health"
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
curl -s -D - -o /dev/null -H 'Origin: http://evil.example' -H "Range: bytes=$S-" $G/tiles/basemap.pmtiles | grep -qi '^access-control-allow-origin' && fail "416 disallowed origin got ACAO"
DUP=$(curl -s -D - -o /dev/null -H 'Origin: http://localhost:5173' -H 'Range: bytes=0-7' $G/tiles/basemap.pmtiles | tr -d '\r' | grep -i '^access-control-[a-z-]*:' | cut -d: -f1 | tr 'A-Z' 'a-z' | sort | uniq -d)
[[ -z "$DUP" ]] || fail "206 repeated headers: $DUP"
[[ $(code -X POST $G/tiles/basemap.pmtiles) == 405 ]] || fail "tiles POST 405"
curl -s -X POST $G/tiles/basemap.pmtiles | grep -q '"code":"MethodNotAllowed"' || fail "tiles 405 not JSON"
# Missing archive -> JSON 404 (the tiles location re-declares error_page 404; see ADR-0002 Amendment 2)
rm -f "$TMP/tiles/basemap.pmtiles"
[[ $(code $G/tiles/basemap.pmtiles) == 404 ]] || fail "missing archive 404"
curl -s $G/tiles/basemap.pmtiles | grep -q '"code":"NotFound"' || fail "missing archive 404 not JSON"
echo "OK: backend static checks passed"

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
# 9. SEC-2 (ADR-0019): lib.sh route limits (flags + serve-time overlay, timeout validation); gateway /v1/route
#    concurrency cap (entrypoint/17-route-limits.sh, default 8/4, bad values refused, cannot be left out) -> 503
#    RoutingBusy with Retry-After/no-store/CORS once, per IP and global, GET too, other paths never capped, no IP in the
#    logs; upstream 5xx shaping (Valhalla's plain-text 504 -> JSON UpstreamTimeout, 500/503 -> JSON 502, 400 passes
#    through, gateway read timeout 504), against the njs stub in infra/ci/stub-valhalla (same nginx image)
# 8. NAV-022 static pack hosting: the static-host test project renders (digest-pinned httpd, loopback port only,
#    read-only document root); the export + .htaccess unit tests run with the pipeline tests. The full httpd run is
#    `make -C backend pack-export-test` (pulls ~110 MB, not part of this script).
# 10. SEC-4A: every image pinned as tag@sha256 (compose defaults, .env.example, Makefile, pack images); container
#    hardening of every service (cap_drop ALL, no-new-privileges, memory/PID limits; serving services non-root on a
#    read-only root file system; Photon: slot read-only, only photon/ writable); every gateway in this script runs
#    hardened like compose (read-only, tmpfs, uid 101, no capabilities); IPv6 clients keyed by /64 (BE-2); download
#    limits (BE-3: packs per-client connection cap -> 429 RateLimited with CORS once, Range resume, bandwidth after
#    the initial burst on packs and tiles, tile range reads never limited, bad values and a missing 18-*.sh refused);
#    X-Content-Type-Options: nosniff exactly once on every response (BE-12/BE-13)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
B=$ROOT/backend
fail() { echo "FAIL: $*" >&2; exit 1; }
# docker logs into a pipe: `grep -q` may stop reading early, and under pipefail the SIGPIPE of `docker logs` would turn a
# match into a failure (flaky checks). dlogs never fails.
dlogs() { docker logs "$1" 2>&1 || true; }

echo "== syntax"
for f in "$B"/scripts/*.sh "$B"/gateway/entrypoint/*.sh "$B"/pipeline/*.sh; do bash -n "$f" || fail "bash -n $f"; done
python3 -m py_compile "$B"/scripts/*.py "$B"/pipeline/*.py "$B"/pipeline/tests/*.py "$B"/pack/*.py "$B"/pack/tests/*.py \
    && rm -rf "$B/scripts/__pycache__" "$B/pipeline/__pycache__" "$B/pipeline/tests/__pycache__" "$B/pack/__pycache__" "$B/pack/tests/__pycache__"
if command -v shellcheck >/dev/null; then shellcheck -x -P SCRIPTDIR -S warning "$B"/scripts/*.sh "$B"/gateway/entrypoint/*.sh "$B"/pipeline/*.sh; fi

echo "== compose config (defaults only)"
( cd "$B" && docker compose --env-file /dev/null config --quiet ) || fail "compose config"
if ( cd "$B" && docker compose --env-file /dev/null config --images ) | grep -q ':latest'; then fail "unpinned :latest image"; fi

echo "== SEC-4A image pins (tag@sha256 everywhere)"
PIN='^[a-z0-9._/:-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}$'   # <repo>:<tag>@sha256:<64 hex>
printf 'NAV_COMPOSE_PROJECT=ci-pins\nNAV_DATA_ROOT=/nonexistent/nav-data\n' > /tmp/navmn-ci-pins.env
for img in $( (cd "$B" && docker compose --env-file /dev/null config --images; \
               docker compose -f compose.slots.yaml --env-file /tmp/navmn-ci-pins.env --profile '*' config --images) | sort -u) \
           $(sed -n 's/^[A-Z_]*_IMAGE=\(.*\)$/\1/p' "$B/.env.example") \
           $(grep -o 'PMTILES_IMAGE:-[^}]*' "$B/Makefile" | cut -d- -f2-) \
           $(sed -n 's/^DEFAULT_[A-Z_]*IMAGE = (*"\([^"]*\)"*.*/\1/p' "$B/pipeline/nav_pack.py"); do
  # (nav_pack.py splits DEFAULT_VALHALLA_IMAGE over two lines; its full pin is checked just below)
  [[ "$img" =~ $PIN || "$img" == ghcr.io/valhalla/valhalla:3.9.0@sha256: ]] || fail "image not pinned as tag@sha256: $img"
done
rm -f /tmp/navmn-ci-pins.env
tr -d '\n "' < "$B/pipeline/nav_pack.py" | grep -q 'ghcr.io/valhalla/valhalla:3.9.0@sha256:511c095b8caf393dccceb8b519ec96b6f85a0166b2288ba014a8a748acc5a63c' \
    || fail "nav_pack Valhalla image pin"
grep -E 'nginxinc/nginx-unprivileged:1\.27|openjdk/jdk:21-ubuntu[^@]' -r "$B"/compose*.yaml "$B/.env.example" && fail "superseded nginx 1.27 or floating JDK tag left"
echo "   every compose / .env.example / Makefile / pack image is tag@sha256"

echo "== gateway (nginx -t and behaviour without upstreams)"
IMG=$(cd "$B" && docker compose --env-file /dev/null config --images | grep nginx | head -1)
# SEC-4A BE-4: every gateway in this script runs as compose runs it (uid 101, read-only root, tmpfs, no capabilities).
# shellcheck disable=SC2054  # the commas are tmpfs mount options, not array separators
GW_HARDEN=(--user 101:101 --read-only --cap-drop ALL --security-opt no-new-privileges:true
           --tmpfs /tmp:uid=101,gid=101,mode=0755,size=64m --tmpfs /etc/nginx/conf.d:uid=101,gid=101,mode=0755,size=1m)
GW_ENTRY=(-v "$B/gateway/entrypoint/15-cors-origins.sh:/docker-entrypoint.d/15-cors-origins.sh:ro"
          -v "$B/gateway/entrypoint/16-rate-limits.sh:/docker-entrypoint.d/16-rate-limits.sh:ro"
          -v "$B/gateway/entrypoint/17-route-limits.sh:/docker-entrypoint.d/17-route-limits.sh:ro"
          -v "$B/gateway/entrypoint/18-download-limits.sh:/docker-entrypoint.d/18-download-limits.sh:ro")
TMP=$(mktemp -d); trap 'docker rm -f navmn-ci-gw navmn-ci-gw-rl navmn-ci-gw-bad navmn-ci-gw-slot navmn-ci-gw-rc navmn-ci-stub navmn-ci-gw-v6 navmn-ci-gw-dl >/dev/null 2>&1 || true; docker network rm navmn-ci-net >/dev/null 2>&1 || true; rm -rf "$TMP"' EXIT
chmod 755 "$TMP"; mkdir -p "$TMP/tiles"; printf 'PMTiles\003' > "$TMP/tiles/basemap.pmtiles"; head -c 1024 /dev/zero >> "$TMP/tiles/basemap.pmtiles"; chmod -R a+rX "$TMP"
docker run -d --name navmn-ci-gw -p 127.0.0.1:18089:8080 "${GW_HARDEN[@]}" \
  -e CORS_ALLOWED_ORIGINS=http://localhost:5173 -e GATEWAY_ERROR_LOG_LEVEL=crit \
  -e VALHALLA_UPSTREAM=valhalla:8002 -e PHOTON_UPSTREAM=photon:2322 -e NGINX_ENTRYPOINT_QUIET_LOGS=1 \
  -v "$B/gateway/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$B/gateway/templates:/etc/nginx/templates:ro" \
  -v "$B/gateway/snippets:/etc/nginx/snippets:ro" -v "$B/gateway/njs:/etc/nginx/njs:ro" "${GW_ENTRY[@]}" \
  -v "$TMP:/srv/data:ro" "$IMG" >/dev/null
for _ in $(seq 30); do curl -fs localhost:18089/health >/dev/null && break; sleep 1; done
docker exec navmn-ci-gw nginx -t
G=http://127.0.0.1:18089
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
[[ $(code $G/health) == 200 ]] || fail "health"
nosniff_once() {  # nosniff_once <label> <curl args...>: SEC-4A BE-12/13, exactly one X-Content-Type-Options: nosniff
  local n; n=$(curl -s -D - -o /dev/null "${@:2}" | tr -d '\r' | grep -ci '^x-content-type-options: nosniff$' || true)
  [[ $n == 1 ]] || fail "$1: X-Content-Type-Options: nosniff appears $n times (want 1)"
}
curl -s -D - -o /dev/null -H 'Origin: http://localhost:5173' $G/health | tr -d '\r' | grep -qi '^access-control-expose-headers: .*Retry-After' || fail "Retry-After not exposed (openapi 0.4.0)"
docker exec navmn-ci-gw grep -q limit_req /etc/nginx/conf.d/01-rate-limits.conf && fail "rate limits must be off by default"
docker exec navmn-ci-gw grep -q 'limit_conn nav_route_global 8;' /etc/nginx/conf.d/02-route-limits.conf \
    && docker exec navmn-ci-gw grep -q 'limit_conn nav_route_ip     4;' /etc/nginx/conf.d/02-route-limits.conf \
    || fail "SEC-2 route cap must be on by default (8 global / 4 per IP)"
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
for args in "$G/health" "$G/nope" "-X OPTIONS -H Origin:http://localhost:5173 $G/v1/route" "-X DELETE $G/v1/search" \
            "-H Range:bytes=0-7 $G/tiles/basemap.pmtiles" "-H Range:bytes=$S- $G/tiles/basemap.pmtiles" "-X POST -d {} $G/v1/route"; do
  # shellcheck disable=SC2086
  nosniff_once "nosniff $args" $args
done
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
  docker run -d --name "$name" --network navmn-ci-net -p "127.0.0.1:$port:8080" "${GW_HARDEN[@]}" \
    -e CORS_ALLOWED_ORIGINS=http://localhost:5173 -e GATEWAY_ERROR_LOG_LEVEL=crit \
    -e VALHALLA_UPSTREAM=valhalla:8002 -e PHOTON_UPSTREAM=photon:2322 -e NGINX_ENTRYPOINT_QUIET_LOGS=1 "$@" \
    -v "$B/gateway/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$B/gateway/templates:/etc/nginx/templates:ro" \
    -v "$B/gateway/snippets:/etc/nginx/snippets:ro" -v "$B/gateway/njs:/etc/nginx/njs:ro" "${GW_ENTRY[@]}" \
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
dlogs navmn-ci-gw-rl | grep -qiE 'limiting requests|client: ' && fail "limit_req lines reached the log"
# the access log reaches `docker logs` asynchronously: wait up to 15 s for the 429 line (5 s was flaky on a loaded host)
for _ in $(seq 75); do dlogs navmn-ci-gw-rl | grep -qE '"status":429' && break; sleep 0.2; done
dlogs navmn-ci-gw-rl | grep -qE '"status":429' || fail "429 not in the access log"
dlogs navmn-ci-gw-rl | grep -qE '([0-9]{1,3}\.){3}[0-9]{1,3}' && fail "an IPv4 address reached the gateway log"
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
python3 - "$TMP/slots.yaml" "$TMP/dev.yaml" <<'PY' || fail "SEC-4A container hardening (compose.yaml / compose.slots.yaml)"
import sys, yaml
s = yaml.safe_load(open(sys.argv[1]))["services"]; d = yaml.safe_load(open(sys.argv[2]))["services"]
serving = {"gateway": "101:101", "gateway-verify": "101:101", "valhalla": "65534:65534", "valhalla-blue": "65534:65534",
           "valhalla-green": "65534:65534", "photon": "101:101", "photon-blue": "101:101", "photon-green": "101:101"}
caps = {"data-fetch": ["DAC_OVERRIDE"], "photon-import": ["CHOWN", "DAC_OVERRIDE"]}
for label, svcs in (("dev", d), ("slots", s)):
    for n, x in svcs.items():
        where = f"{label} {n}"
        assert x.get("cap_drop") == ["ALL"], (where, x.get("cap_drop"))
        assert "no-new-privileges:true" in (x.get("security_opt") or []), (where, x.get("security_opt"))
        assert x.get("mem_limit") and x.get("pids_limit"), (where, x.get("mem_limit"), x.get("pids_limit"))
        assert sorted(x.get("cap_add") or []) == caps.get(n, []), (where, x.get("cap_add"))
        if n in serving:
            assert x.get("user") == serving[n], (where, x.get("user"))
            assert x.get("read_only") is True and x.get("tmpfs"), (where, "read_only + tmpfs")
        else:
            assert not x.get("user"), (where, "builders run as root (they own data/)")
for n in ("photon", "photon-blue", "photon-green"):
    x = (d if n == "photon" else s)[n]
    m = {v["target"]: v for v in x["volumes"]}
    assert m["/data"].get("read_only") is True, (n, "/data must be read-only (SEC-4A BE-5)")
    assert m["/data/photon"]["source"].endswith("/photon") and not m["/data/photon"].get("read_only"), (n, m["/data/photon"])
    assert any(t.startswith("/tmp:exec") for t in x["tmpfs"]), (n, "JNA needs an exec /tmp")
for n in ("gateway", "gateway-verify"):
    assert "/docker-entrypoint.d/18-download-limits.sh" in {v["target"] for v in s[n]["volumes"]}, n
assert "/docker-entrypoint.d/18-download-limits.sh" in {v["target"] for v in d["gateway"]["volumes"]}
for svc in (d["gateway"], s["gateway"]):
    env = svc["environment"]
    assert (env["GATEWAY_PACKS_MAX_CONN_PER_IP"], env["GATEWAY_PACKS_LIMIT_RATE"], env["GATEWAY_TILES_MAX_CONN_PER_IP"]) == ("2", "2m", "0"), env
print("   SEC-4A: every service cap_drop ALL + no-new-privileges + mem/pids limits; serving services non-root, read-only;"
      " Photon /data read-only, only /data/photon writable; builders: only the listed capabilities")
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
docker run -d --name navmn-ci-gw-slot --network navmn-ci-net -p 127.0.0.1:18092:8080 "${GW_HARDEN[@]}" \
  -e CORS_ALLOWED_ORIGINS='*' -e GATEWAY_ERROR_LOG_LEVEL=crit -e VALHALLA_UPSTREAM=127.0.0.1:9 \
  -e PHOTON_UPSTREAM=127.0.0.1:9 -e NGINX_ENTRYPOINT_QUIET_LOGS=1 \
  -v "$B/gateway/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$B/gateway/templates:/etc/nginx/templates:ro" \
  -v "$B/gateway/snippets:/etc/nginx/snippets:ro" -v "$B/gateway/njs:/etc/nginx/njs:ro" "${GW_ENTRY[@]}" \
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
dlogs navmn-ci-gw-slot | grep -qiE 'reload|signal process started' && fail "switch must not reload nginx"
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
dlogs navmn-ci-gw-slot | grep -qE '([0-9]{1,3}\.){3}[0-9]{1,3}' && fail "an IPv4 address reached the gateway log"
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

echo "== SEC-2 Valhalla route limits (scripts/lib.sh: flags + serve-time overlay + timeout validation)"
cat > "$TMP/sec2-lib.sh" <<'SH'
source "$1/scripts/lib.sh"
trap - ERR
set +e
mapfile -t A < <(valhalla_limit_args)
printf '%s\n' "${A[@]}" > "$2/flags.txt"
valhalla_apply_limits "$2/in.json" "$2/out.json" > "$2/summary.txt" || exit 1
for v in 0 10 abc 8.5 off; do
    ( VALHALLA_TIMEOUT_SECONDS=$v; valhalla_timeout_seconds >/dev/null 2>&1 ) && { echo "accepted VALHALLA_TIMEOUT_SECONDS=$v"; exit 1; }
done
[[ "$(VALHALLA_TIMEOUT_SECONDS=1 valhalla_timeout_seconds)" == 1 && "$(VALHALLA_TIMEOUT_SECONDS= valhalla_timeout_seconds)" == 8 ]] || exit 1
SH
# Valhalla 3.9.0 defaults (ADR-0019 F1) as the input of a graph built before SEC-2
python3 - "$TMP/in.json" <<'PY'
import json, sys
d = lambda dist, locs=None: {"max_distance": dist, **({"max_locations": locs} if locs else {}), "max_matrix_distance": 1}
json.dump({"mjolnir": {"tile_extract": "/data/valhalla/valhalla_tiles.tar"}, "httpd": {"service": {"timeout_seconds": -1}},
           "service_limits": {"auto": d(5e6, 20), "taxi": d(5e6, 20), "truck": d(5e6, 20), "bus": d(5e6, 50),
                              "auto_pedestrian": d(5e6), "bicycle": d(5e5, 50), "motorcycle": d(5e5, 50),
                              "motor_scooter": d(5e5, 50), "bikeshare": d(5e5, 50), "multimodal": d(5e5, 50),
                              "transit": d(5e5, 50), "pedestrian": d(2.5e5, 50), "isochrone": d(25000, 1),
                              "centroid": d(2e5, 5), "trace": d(2e5), "future_costing": d(9e6, 99), "max_alternates": 2}},
          open(sys.argv[1], "w"))
PY
bash "$TMP/sec2-lib.sh" "$B" "$TMP" || fail "lib.sh route limit functions"
python3 - "$TMP" <<'PY' || fail "SEC-2 limits table (ADR-0019 §1)"
import json, sys
t = sys.argv[1]
o = json.load(open(f"{t}/out.json"))
sl = o["service_limits"]
want = {"auto": (3e6, 10), "bus": (3e6, 10), "taxi": (3e6, 10), "truck": (3e6, 10), "auto_pedestrian": (3e6, None),
        "bicycle": (5e5, 10), "motorcycle": (5e5, 10), "motor_scooter": (5e5, 10), "bikeshare": (5e5, 10),
        "multimodal": (5e5, 10), "transit": (5e5, 10), "pedestrian": (2.5e5, 10), "isochrone": (25000, 1),
        "centroid": (2e5, 5), "trace": (2e5, None), "future_costing": (3e6, 10)}
for k, (dist, locs) in want.items():
    assert (sl[k]["max_distance"], sl[k].get("max_locations")) == (dist, locs), (k, sl[k])
assert sl["max_alternates"] == 2 and o["httpd"]["service"]["timeout_seconds"] == 8, o["httpd"]
assert o["mjolnir"]["tile_extract"] == "/data/valhalla/valhalla_tiles.tar"
flags = open(f"{t}/flags.txt").read().split("\n")
pairs = dict(zip(flags[0::2], flags[1::2]))
assert pairs["--service-limits-auto-max-distance"] == "3000000" and pairs["--service-limits-motor-scooter-max-locations"] == "10"
assert pairs["--service-limits-pedestrian-max-distance"] == "250000" and pairs["--httpd-service-timeout-seconds"] == "8"
assert "--service-limits-auto-pedestrian-max-locations" not in pairs
print("   lib.sh:", open(f"{t}/summary.txt").read().strip())
PY
grep -q 'valhalla_apply_limits "$CONF" "$RUNTIME_CONF"' "$B/scripts/valhalla-serve.sh" && grep -q 'exec valhalla_service "$RUNTIME_CONF"' "$B/scripts/valhalla-serve.sh" \
    || fail "valhalla-serve.sh must serve the overlaid runtime config"
grep -q '"${LIMIT_ARGS\[@\]}"' "$B/scripts/valhalla-build.sh" || fail "valhalla-build.sh make_config must carry the limit flags"
grep -A1 '^valhalla_fingerprint()' "$B/scripts/lib.sh" | grep -qiE 'limit|timeout' && fail "route limits must not be in the graph fingerprint"

echo "== SEC-4A BE-7 download integrity (scripts/lib.sh: https only, checksums fail closed, no credentials in logs)"
cat > "$TMP/sec4a-lib.sh" <<'SH'
source "$1/scripts/lib.sh"
trap - ERR
set +e
f=$2/tool.bin; printf 'tool' > "$f"; good=$(sha256sum "$f" | cut -d' ' -f1)
ok()   { ( "$@" ) >/dev/null 2>&1 || { echo "refused but should pass: $*"; exit 1; }; }
nope() { ( "$@" ) >/dev/null 2>&1 && { echo "passed but must be refused: $*"; exit 1; }; printf 'tool' > "$f"; }
ok verify_sum 256 "$good" "$f"
nope verify_sum 256 "" "$f"
nope verify_sum 256 "XYZ" "$f"
nope verify_sum 256 "${good%?}0" "$f"
nope verify_sum 512 "$good" "$f"
nope verify_sum 256 skip "$f"
ALLOW_UNVERIFIED_TOOLS=1 ok verify_sum 256 skip "$f"
ALLOW_UNVERIFIED_TOOLS=1 nope verify_sum 256 "" "$f"
nope fetch "http://example.invalid/x" "$2/x"
nope fetch "file:///etc/passwd" "$2/x"
[[ "$( ( fetch "http://user:secret@example.invalid/x?token=abc" "$2/x" ) 2>&1)" != *secret* ]] || { echo "credentials logged"; exit 1; }
[[ "$(log_url 'https://u:p@mirror.example.invalid/a.pbf?token=t')" == 'https://<credentials>@mirror.example.invalid/a.pbf?<redacted>' ]] || { echo "log_url: $(log_url 'https://u:p@mirror.example.invalid/a.pbf?token=t')"; exit 1; }
[[ "$(log_url 'https://download.geofabrik.de/asia/mongolia-latest.osm.pbf')" == 'https://download.geofabrik.de/asia/mongolia-latest.osm.pbf' ]] || exit 1
grep -q -- "--proto '=https' --proto-redir '=https'" "$1/scripts/lib.sh" || { echo "fetch without --proto/--proto-redir"; exit 1; }
SH
bash "$TMP/sec4a-lib.sh" "$B" "$TMP" || fail "SEC-4A lib.sh download integrity"
grep -q -- '--strict-checksums' "$B/scripts/tiles-build.sh" || fail "Maven must run with --strict-checksums (WS-10)"
grep -q 'geo2day' "$B/compose.yaml" "$B/.env.example" && fail "third-party OSM mirror default left (SEC-4A BE-7)"
echo "   verify_sum fails closed (empty, malformed, wrong, skip without the dev flag); http/file URLs refused; URL credentials never logged"

echo "== SEC-2 gateway /v1/route concurrency cap and 5xx shaping (stub upstream, cap 3 global / 2 per IP)"
docker run -d --name navmn-ci-stub --network navmn-ci-net --network-alias valhalla \
  -v "$ROOT/infra/ci/stub-valhalla/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$ROOT/infra/ci/stub-valhalla:/stub:ro" "$IMG" >/dev/null
rl_run navmn-ci-gw-rc 18093 -e GATEWAY_ROUTE_MAX_INFLIGHT=3 -e GATEWAY_ROUTE_MAX_INFLIGHT_PER_IP=2
for _ in $(seq 30); do curl -fs localhost:18093/health >/dev/null && break; sleep 1; done
for _ in $(seq 20); do docker exec navmn-ci-stub curl -fs -o /dev/null http://127.0.0.1:8002/status && break; sleep 0.5; done
C=http://127.0.0.1:18093
rpost() { curl -s -D - -o "$TMP/rc.body" -H 'Origin: http://localhost:5173' -H 'Content-Type: application/json' -X POST -d "$1" $C/v1/route | tr -d '\r'; }
single_cors() {  # $1 = headers, $2 = label
  local dup; dup=$(echo "$1" | grep -i '^access-control-[a-z-]*:' | cut -d: -f1 | tr 'A-Z' 'a-z' | sort | uniq -d)
  [[ -z "$dup" ]] || fail "$2: repeated headers: $dup"
  [[ $(echo "$1" | grep -ci '^access-control-allow-origin: http://localhost:5173$') == 1 ]] || fail "$2: ACAO"
  [[ $(echo "$1" | grep -ci '^access-control-expose-headers: .*Retry-After') == 1 ]] || fail "$2: Expose-Headers"
}
# upstream answers: 400 passes through raw; 500 / 503 -> JSON 502; Valhalla's plain-text 504 -> JSON 504 UpstreamTimeout
H=$(rpost '{"stub":"e400"}'); echo "$H" | head -1 | grep -q ' 400' && grep -q '"code":"DistanceExceeded"' "$TMP/rc.body" || fail "upstream 400 must pass through: $(echo "$H" | head -1)"
single_cors "$H" "upstream 400"
for m in e500 e503; do
  H=$(rpost "{\"stub\":\"$m\"}")
  echo "$H" | head -1 | grep -q ' 502' && grep -q '^{"code":"UpstreamUnavailable",' "$TMP/rc.body" || fail "upstream $m must be JSON 502: $(echo "$H" | head -1) $(head -c 80 "$TMP/rc.body")"
  single_cors "$H" "upstream $m"
done
H=$(rpost '{"stub":"timeout"}')
echo "$H" | head -1 | grep -q ' 504' && grep -q '^{"code":"UpstreamTimeout","message":"routing service timed out"}$' "$TMP/rc.body" \
    && echo "$H" | grep -qi '^content-type: application/json' || fail "Valhalla timeout must be JSON 504 UpstreamTimeout: $(echo "$H" | head -1) $(head -c 80 "$TMP/rc.body")"
single_cors "$H" "upstream 504"
# per IP: 2 slow in flight, the 3rd from the same IP is 503 RoutingBusy at once (never 502), the 2 finish with 200
for i in 1 2; do curl -s -o /dev/null -w '%{http_code}' -X POST -d '{"stub":"slow"}' $C/v1/route > "$TMP/rc.slow$i" & done
sleep 0.7
t0=$(date +%s%N); H=$(rpost '{"stub":"ok"}'); ms=$(( ($(date +%s%N) - t0) / 1000000 ))
echo "$H" | head -1 | grep -q ' 503' || fail "per-IP cap: 3rd request got $(echo "$H" | head -1)"
grep -q '^{"code":"RoutingBusy","message":"routing service busy"}$' "$TMP/rc.body" || fail "503 body: $(head -c 120 "$TMP/rc.body")"
echo "$H" | grep -qi '^content-type: application/json' || fail "503 Content-Type"
[[ $(echo "$H" | grep -ci '^retry-after:') == 1 ]] && echo "$H" | grep -qiE '^retry-after: [1-9][0-9]*$' || fail "503 Retry-After"
[[ $(echo "$H" | grep -ci '^cache-control:') == 1 ]] && echo "$H" | grep -qi '^cache-control: no-store$' || fail "503 Cache-Control"
single_cors "$H" "503 RoutingBusy"
(( ms < 1000 )) || fail "503 RoutingBusy took ${ms} ms (want < 1 s)"
[[ $(curl -s -o /dev/null -w '%{http_code}' "$C/v1/route?json=%7B%22stub%22%3A%22ok%22%7D") == 503 ]] || fail "per-IP cap must count GET ?json= too"
for c in "$(curl -s -o /dev/null -w '%{http_code}' $C/health)" "$(curl -s -o /dev/null -w '%{http_code}' -X OPTIONS -H 'Origin: http://localhost:5173' $C/v1/route)" \
         "$(curl -s -o /dev/null -w '%{http_code}' -H 'Range: bytes=0-7' $C/tiles/basemap.pmtiles)" "$(curl -s -o /dev/null -w '%{http_code}' "$C/v1/search?q=a")"; do
  [[ $c != 503 ]] || fail "health/OPTIONS/tiles/search must never be capped"
done
# global: 2 from this IP + 1 from another container = 3 in flight; a 4th from the other container is 503
[[ $(docker exec navmn-ci-stub curl -s -o /dev/null -w '%{http_code}' -X POST -d '{"stub":"slow"}' http://navmn-ci-gw-rc:8080/v1/route & sleep 0.5;
     docker exec navmn-ci-stub curl -s -o /dev/null -w '%{http_code}' -X POST -d '{"stub":"ok"}' http://navmn-ci-gw-rc:8080/v1/route; wait) == *503* ]] \
    || fail "global cap: 4th request in flight (other client IP) was not 503"
wait
[[ $(cat "$TMP/rc.slow1") == 200 && $(cat "$TMP/rc.slow2") == 200 ]] || fail "requests in flight must finish normally ($(cat "$TMP/rc.slow1") $(cat "$TMP/rc.slow2"))"
[[ $(curl -s -o /dev/null -w '%{http_code}' -X POST -d '{"stub":"ok"}' $C/v1/route) == 200 ]] || fail "cap must free after the requests finish"
# gateway read timeout (10 s) still gives JSON 504 when Valhalla never answers
t0=$(date +%s); H=$(rpost '{"stub":"hang"}'); el=$(( $(date +%s) - t0 ))
echo "$H" | head -1 | grep -q ' 504' && grep -q '"code":"UpstreamTimeout"' "$TMP/rc.body" && (( el >= 9 && el <= 12 )) || fail "read timeout: $(echo "$H" | head -1) after ${el}s"
dlogs navmn-ci-gw-rc | grep -qiE 'limiting connections|client: ' && fail "limit_conn lines reached the log"
dlogs navmn-ci-gw-rc | grep -qE '([0-9]{1,3}\.){3}[0-9]{1,3}' && fail "an IPv4 address reached the gateway log"
docker rm -f navmn-ci-gw-rc navmn-ci-stub >/dev/null
# invalid values and a missing 17-route-limits.sh are refused at start (never an uncapped gateway)
for bad in "GATEWAY_ROUTE_MAX_INFLIGHT=0" "GATEWAY_ROUTE_MAX_INFLIGHT_PER_IP=off" "GATEWAY_ROUTE_MAX_INFLIGHT=abc"; do
  docker rm -f navmn-ci-gw-bad >/dev/null 2>&1 || true
  rl_run navmn-ci-gw-bad 18091 -e "$bad"
  for _ in $(seq 20); do [[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] && break; sleep 0.5; done
  [[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] || fail "invalid $bad was accepted"
done
docker rm -f navmn-ci-gw-bad >/dev/null 2>&1 || true
docker run -d --name navmn-ci-gw-bad --network navmn-ci-net "${GW_HARDEN[@]}" -e CORS_ALLOWED_ORIGINS='*' -e GATEWAY_ERROR_LOG_LEVEL=crit \
  -e VALHALLA_UPSTREAM=valhalla:8002 -e PHOTON_UPSTREAM=photon:2322 -e NGINX_ENTRYPOINT_QUIET_LOGS=1 \
  -v "$B/gateway/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$B/gateway/templates:/etc/nginx/templates:ro" \
  -v "$B/gateway/snippets:/etc/nginx/snippets:ro" -v "$B/gateway/njs:/etc/nginx/njs:ro" \
  -v "$B/gateway/entrypoint/15-cors-origins.sh:/docker-entrypoint.d/15-cors-origins.sh:ro" \
  -v "$B/gateway/entrypoint/16-rate-limits.sh:/docker-entrypoint.d/16-rate-limits.sh:ro" \
  -v "$B/gateway/entrypoint/18-download-limits.sh:/docker-entrypoint.d/18-download-limits.sh:ro" \
  -v "$TMP:/srv/data:ro" "$IMG" >/dev/null
for _ in $(seq 20); do [[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] && break; sleep 0.5; done
[[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] || fail "a gateway without 17-route-limits.sh must refuse to start"
docker rm -f navmn-ci-gw-bad >/dev/null
python3 - "$TMP/slots.yaml" "$TMP/dev.yaml" <<'PY' || fail "SEC-2 compose pass-through"
import sys, yaml
s = yaml.safe_load(open(sys.argv[1]))["services"]; d = yaml.safe_load(open(sys.argv[2]))["services"]
for name, svc in [("dev gateway", d["gateway"]), ("slots gateway", s["gateway"]), ("slots gateway-verify", s["gateway-verify"])]:
    env = svc["environment"]
    assert env["GATEWAY_ROUTE_MAX_INFLIGHT"] == "8" and env["GATEWAY_ROUTE_MAX_INFLIGHT_PER_IP"] == "4", name
    assert "/docker-entrypoint.d/17-route-limits.sh" in {v["target"] for v in svc["volumes"]}, name
for name, svc in [("dev valhalla", d["valhalla"]), ("dev valhalla-build", d["valhalla-build"]), ("valhalla-blue", s["valhalla-blue"]),
                  ("valhalla-green", s["valhalla-green"]), ("slots valhalla-build", s["valhalla-build"])]:
    assert svc["environment"]["VALHALLA_TIMEOUT_SECONDS"] == "8", name
print("   compose: cap keys + 17-route-limits.sh on every gateway, VALHALLA_TIMEOUT_SECONDS on every Valhalla service")
PY
echo "   SEC-2: limits table + overlay, 503 RoutingBusy per IP and global (GET too), 5xx shaped as JSON, 400 raw, bad values refused"

echo "== SEC-4A BE-2 per-client key: IPv4 full address, IPv6 /64 (X-Forwarded-For trusted from 0.0.0.0/0 in this test only)"
rl_run navmn-ci-gw-v6 18094 -e GATEWAY_RATE_LIMIT=on -e GATEWAY_RATE_SEARCH=1r/s -e GATEWAY_BURST_SEARCH=2 -e GATEWAY_REAL_IP_FROM=0.0.0.0/0
for _ in $(seq 30); do curl -fs localhost:18094/health >/dev/null && break; sleep 1; done
V=http://127.0.0.1:18094
burst() { seq 6 | xargs -P 6 -I{} curl -s -o /dev/null -w '%{http_code} ' -H "X-Forwarded-For: $1" "$V/v1/search?q=a"; }
got=$(burst '2001:db8:1:2::{}'); [[ $got == *429* ]] || fail "6 addresses in one IPv6 /64 must share one bucket: $got"; sleep 3
got=$(burst '2001:db8:1:{}::1'); [[ $got != *429* ]] || fail "6 different IPv6 /64s must not share a bucket: $got"; sleep 3
got=$(burst '198.51.100.{}'); [[ $got != *429* ]] || fail "6 different IPv4 addresses must not share a bucket: $got"; sleep 3
got=$(burst '198.51.100.77'); [[ $got == *429* ]] || fail "one IPv4 address must be limited: $got"; sleep 3
# shellcheck disable=SC2016
got=$(seq 6 | xargs -P 6 -I{} sh -c 'if [ $(({} % 2)) = 0 ]; then a=::ffff:203.0.113.9; else a=203.0.113.9; fi; curl -s -o /dev/null -w "%{http_code} " -H "X-Forwarded-For: $a" "$0/v1/search?q=a"' "$V")
[[ $got == *429* ]] || fail "an IPv4-mapped IPv6 address must share the IPv4 bucket: $got"
for f in 01-rate-limits.conf 02-route-limits.conf; do
  docker exec navmn-ci-gw-v6 grep -q 'default \$nav_client_key;' /etc/nginx/conf.d/$f || fail "$f is not keyed on \$nav_client_key"
  docker exec navmn-ci-gw-v6 grep -q 'binary_remote_addr' /etc/nginx/conf.d/$f && fail "$f still keys on \$binary_remote_addr"
done
dlogs navmn-ci-gw-v6 | grep -qiE '2001:db8|198\.51\.100|203\.0\.113' && fail "a client address reached the gateway log"
docker rm -f navmn-ci-gw-v6 >/dev/null
echo "   one IPv6 /64 = one bucket, different /64s and IPv4 addresses separate, IPv4-mapped = IPv4; route cap uses the same key"

echo "== SEC-4A BE-3 download limits (packs 2 per client + bandwidth after 64k at 256k/s, tiles bandwidth; test values)"
DL=$TMP/dl; A=20261004T000000Z
mkdir -p "$DL/pointer" "$DL/slots/$A/tiles" "$DL/packs/mn/$A"
printf 'PMTiles\003' > "$DL/slots/$A/tiles/basemap.pmtiles"; head -c 2000000 /dev/urandom >> "$DL/slots/$A/tiles/basemap.pmtiles"
head -c 1000000 /dev/urandom > "$DL/packs/mn/$A/routing.tar.gz"
DSHA=$(sha256sum "$DL/packs/mn/$A/routing.tar.gz" | cut -d' ' -f1); DZ=1000000
printf '{"pack_schema":1,"region":"mn","pack_version":"%s"}\n' "$A" > "$DL/packs/mn/manifest.json"
printf '{"slot":"%s","lane":"blue","valhalla":"valhalla-blue:8002","photon":"photon-blue:2322","tiles":"/srv/slots/%s/tiles/basemap.pmtiles"}' "$A" "$A" > "$DL/pointer/active.json"
chmod -R a+rX "$DL"
dl_run() {  # dl_run NAME PORT EXTRA_ENV...
  local name=$1 port=$2; shift 2
  rl_run "$name" "$port" -e GATEWAY_RATE_LIMIT=on -e GATEWAY_RATE_PACKS=100r/s -e GATEWAY_BURST_PACKS=100 \
    -e GATEWAY_PACKS_LIMIT_RATE_AFTER=64k -e GATEWAY_PACKS_LIMIT_RATE=256k -e GATEWAY_TILES_LIMIT_RATE_AFTER=64k \
    -e GATEWAY_TILES_LIMIT_RATE=256k -v "$DL/pointer:/etc/nginx/slot:ro" -v "$DL/slots:/srv/slots:ro" -v "$DL/packs:/srv/packs:ro" "$@"
}
dl_run navmn-ci-gw-dl 18095
for _ in $(seq 30); do curl -fs localhost:18095/health >/dev/null && break; sleep 1; done
D=http://127.0.0.1:18095; PF=$D/packs/mn/$A/routing.tar.gz
docker exec navmn-ci-gw-dl grep -q 'limit_conn nav_dl_packs        2;' /etc/nginx/conf.d/03-download-limits.conf || fail "packs per-client cap not 2"
docker exec navmn-ci-gw-dl grep -q 'limit_conn nav_dl_tiles' /etc/nginx/conf.d/03-download-limits.conf && fail "tiles connection cap must be off by default"
for i in 1 2; do curl -s -o "$TMP/dl$i" -w '%{http_code} %{time_total}\n' "$PF" > "$TMP/dl$i.st" & done
sleep 0.7
H=$(curl -s -D - -o "$TMP/dl429.json" -H 'Origin: http://localhost:5173' "$PF" | tr -d '\r')
echo "$H" | head -1 | grep -q ' 429' && grep -q '^{"code":"RateLimited","message":"Too many requests"}$' "$TMP/dl429.json" \
  || fail "3rd parallel pack download from one client must be 429 RateLimited: $(echo "$H" | head -1) $(head -c 80 "$TMP/dl429.json")"
[[ $(echo "$H" | grep -ci '^retry-after: 1$') == 1 && $(echo "$H" | grep -ci '^cache-control: no-store$') == 1 ]] || fail "pack 429 Retry-After/Cache-Control"
[[ $(echo "$H" | grep -ci '^access-control-allow-origin: http://localhost:5173$') == 1 ]] || fail "pack 429 ACAO once"
DUP=$(echo "$H" | grep -i '^access-control-[a-z-]*:' | cut -d: -f1 | tr 'A-Z' 'a-z' | sort | uniq -d); [[ -z "$DUP" ]] || fail "pack 429 repeated headers: $DUP"
[[ $(echo "$H" | grep -ci '^x-content-type-options: nosniff$') == 1 ]] || fail "pack 429 nosniff once"
[[ $(code -X OPTIONS -H 'Origin: http://localhost:5173' "$PF") == 204 ]] || fail "packs preflight must never be limited"
[[ $(code "$D/packs/mn/manifest.json") == 200 ]] || fail "the manifest is not counted by the packs connection cap"
[[ $(code -H 'Range: bytes=0-7' $D/tiles/basemap.pmtiles) == 206 ]] || fail "tiles must not be limited by the packs cap"
wait
for i in 1 2; do
  read -r st t < "$TMP/dl$i.st"
  [[ $st == 200 && "$(sha256sum "$TMP/dl$i" | cut -d' ' -f1)" == "$DSHA" ]] || fail "throttled download $i incomplete ($st)"
  awk -v t="$t" 'BEGIN{exit !(t >= 2.5)}' || fail "pack download $i was not slowed after 64k (took ${t}s for 1 MB at 256k/s)"
done
# resume (Range + If-Range) still works under the limits and gets its own 64k burst
E=$(curl -s -D - -o /dev/null -r 0-0 "$PF" | tr -d '\r' | sed -n 's/^[Ee][Tt][Aa][Gg]: //p')
HALF=$((DZ / 2)); H=$(curl -s -D - -o "$TMP/dl.tail" -H "Range: bytes=$HALF-" -H "If-Range: $E" "$PF" | tr -d '\r')
echo "$H" | head -1 | grep -q ' 206' && echo "$H" | grep -qi "^content-range: bytes $HALF-$((DZ - 1))/$DZ\$" || fail "pack resume under limits: $(echo "$H" | head -1)"
[[ "$( (head -c "$HALF" "$TMP/dl1"; cat "$TMP/dl.tail") | sha256sum | cut -d' ' -f1)" == "$DSHA" ]] || fail "resumed halves do not hash to the file"
# tiles: 30 parallel range reads -> all 206 at once (no connection cap, under the burst); a whole download is slowed
got=$(seq 30 | xargs -P 30 -I{} curl -s -o /dev/null -w '%{http_code}:%{time_total} ' -H 'Range: bytes=0-16383' $D/tiles/basemap.pmtiles)
[[ $(tr ' ' '\n' <<<"$got" | grep -c '^206:0\.') == 30 ]] || fail "tile range reads must all be 206 in < 1 s: $got"
n=$(curl -s -o /dev/null -w '%{size_download}' --max-time 2 $D/tiles/basemap.pmtiles || true)
(( n > 65536 && n < 2000008 )) || fail "a whole tiles download must be slowed after the 64k burst (got $n bytes in 2 s)"
dlogs navmn-ci-gw-dl | grep -qiE 'limiting connections|client: ' && fail "limit_conn lines reached the log"
docker rm -f navmn-ci-gw-dl >/dev/null
# optional tiles connection cap (off by default; needs the contract amendment): 429 RateLimited when on
dl_run navmn-ci-gw-dl 18095 -e GATEWAY_TILES_MAX_CONN_PER_IP=1
for _ in $(seq 30); do curl -fs localhost:18095/health >/dev/null && break; sleep 1; done
curl -s -o /dev/null --max-time 3 $D/tiles/basemap.pmtiles & sleep 0.7
H=$(curl -s -D - -o "$TMP/t429.json" -H 'Origin: http://localhost:5173' $D/tiles/basemap.pmtiles | tr -d '\r'); wait || true
echo "$H" | head -1 | grep -q ' 429' && grep -q '"code":"RateLimited"' "$TMP/t429.json" && [[ $(echo "$H" | grep -ci '^access-control-allow-origin:') == 1 ]] \
  || fail "GATEWAY_TILES_MAX_CONN_PER_IP=1: 2nd tiles download must be 429 RateLimited with CORS once: $(echo "$H" | head -1)"
docker rm -f navmn-ci-gw-dl >/dev/null
# off with GATEWAY_RATE_LIMIT=off (dev): no limit_conn/limit_rate at all
docker exec navmn-ci-gw-slot grep -qE 'limit_conn|limit_rate' /etc/nginx/conf.d/03-download-limits.conf && fail "download limits must be off without GATEWAY_RATE_LIMIT=on"
# bad values and a missing 18-download-limits.sh are refused at start
for bad in "GATEWAY_PACKS_LIMIT_RATE=fast" "GATEWAY_PACKS_MAX_CONN_PER_IP=0" "GATEWAY_TILES_MAX_CONN_PER_IP=-1" "GATEWAY_TILES_LIMIT_RATE_AFTER=4g"; do
  docker rm -f navmn-ci-gw-bad >/dev/null 2>&1 || true
  rl_run navmn-ci-gw-bad 18091 -e GATEWAY_RATE_LIMIT=on -e "$bad"
  for _ in $(seq 20); do [[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] && break; sleep 0.5; done
  [[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] || fail "invalid $bad was accepted"
done
docker rm -f navmn-ci-gw-bad >/dev/null 2>&1 || true
docker run -d --name navmn-ci-gw-bad --network navmn-ci-net "${GW_HARDEN[@]}" -e CORS_ALLOWED_ORIGINS='*' -e GATEWAY_ERROR_LOG_LEVEL=crit \
  -e VALHALLA_UPSTREAM=valhalla:8002 -e PHOTON_UPSTREAM=photon:2322 -e NGINX_ENTRYPOINT_QUIET_LOGS=1 \
  -v "$B/gateway/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$B/gateway/templates:/etc/nginx/templates:ro" \
  -v "$B/gateway/snippets:/etc/nginx/snippets:ro" -v "$B/gateway/njs:/etc/nginx/njs:ro" \
  -v "$B/gateway/entrypoint/15-cors-origins.sh:/docker-entrypoint.d/15-cors-origins.sh:ro" \
  -v "$B/gateway/entrypoint/16-rate-limits.sh:/docker-entrypoint.d/16-rate-limits.sh:ro" \
  -v "$B/gateway/entrypoint/17-route-limits.sh:/docker-entrypoint.d/17-route-limits.sh:ro" \
  -v "$TMP:/srv/data:ro" "$IMG" >/dev/null
for _ in $(seq 20); do [[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] && break; sleep 0.5; done
[[ $(docker inspect -f '{{.State.Running}}' navmn-ci-gw-bad) == false ]] || fail "a gateway without 18-download-limits.sh must refuse to start"
docker rm -f navmn-ci-gw-bad >/dev/null
echo "   packs: 3rd parallel download -> 429 RateLimited (CORS once), downloads slowed after 64k, resume ok; tiles ranges never limited, whole file slowed"

echo "== NAV-006 + NAV-020 + NAV-022 pipeline unit tests, NAV-020 search builder tests"
python3 -m unittest discover -s "$B/pipeline/tests" > "$TMP/ut.log" 2>&1 || { tail -n 30 "$TMP/ut.log"; fail "pipeline unit tests"; }
tail -n 3 "$TMP/ut.log"
python3 -m unittest discover -s "$B/pack/tests" > "$TMP/ut2.log" 2>&1 || { tail -n 30 "$TMP/ut2.log"; fail "search builder unit tests"; }
tail -n 3 "$TMP/ut2.log"
rm -rf "$B/pipeline/__pycache__" "$B/pipeline/tests/__pycache__" "$B/pack/__pycache__" "$B/pack/tests/__pycache__"

echo "OK: backend static checks passed"

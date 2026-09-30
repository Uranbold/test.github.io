#!/usr/bin/env bash
# NAV-008 QA helper: run ONLY the gateway from the current working tree (same image, nginx.conf, templates, snippets,
# njs and entrypoint scripts as backend/compose.yaml) in a throwaway container on its own port, with rate limits ON
# and a staging-like CORS allowlist. No Valhalla/Photon: requests that pass the limiter get 502 (limit_req runs
# before the proxy), which staging_checks.py --upstream-less and contract_nav008.py accept. Owner: qa-engineer.
# Test plan: docs/qa/test-plans/NAV-008.md section 6 (pre-staging verification of AC 11, AC 13, CT8).
#
#   tests/api/nav008/isolated-gateway-rl.sh start [PORT] [DATA_DIR]      # default 18491, backend/data (read-only)
#   tests/api/nav008/isolated-gateway-rl.sh stop
#
# Never touches the shared dev stack (compose project navmn, port 8080) that NAV-003 uses. The container name,
# network and port are separate. Rate-limit env names follow backend/gateway/entrypoint/16-rate-limits.sh.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
be="$(cd "$here/../../../backend" && pwd)"
name=nav008-qa-gw-rl; net=nav008-qa-iso
image="$(sed -n 's/^ *image: \${GATEWAY_IMAGE:-\([^}]*\)}.*/\1/p' "$be/compose.yaml" | head -1)"
origins="${CORS_ALLOWED_ORIGINS:-https://demo-staging.nav.test,http://localhost:5173}"

case "${1:-}" in
  start)
    port="${2:-18491}"; data="$(cd "${3:-$be/data}" && pwd)"
    [ "$port" != 8080 ] || { echo "port 8080 is the shared dev gateway; pick another" >&2; exit 2; }
    mounts=(-v "$be/gateway/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$be/gateway/templates:/etc/nginx/templates:ro"
            -v "$be/gateway/snippets:/etc/nginx/snippets:ro" -v "$data:/srv/data:ro")
    [ -d "$be/gateway/njs" ] && mounts+=(-v "$be/gateway/njs:/etc/nginx/njs:ro")
    for f in "$be"/gateway/entrypoint/*.sh; do mounts+=(-v "$f:/docker-entrypoint.d/$(basename "$f"):ro"); done
    docker network inspect "$net" >/dev/null 2>&1 || docker network create "$net" >/dev/null
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker run -d --name "$name" --network "$net" -p "127.0.0.1:$port:8080" \
      -e CORS_ALLOWED_ORIGINS="$origins" -e GATEWAY_ERROR_LOG_LEVEL=crit -e NGINX_ENTRYPOINT_QUIET_LOGS=1 \
      -e GATEWAY_RATE_LIMIT="${GATEWAY_RATE_LIMIT:-on}" -e GATEWAY_RATE_ROUTE="${GATEWAY_RATE_ROUTE:-30r/s}" \
      -e GATEWAY_BURST_ROUTE="${GATEWAY_BURST_ROUTE:-60}" -e GATEWAY_RATE_SEARCH="${GATEWAY_RATE_SEARCH:-30r/s}" \
      -e GATEWAY_BURST_SEARCH="${GATEWAY_BURST_SEARCH:-60}" \
      -e VALHALLA_UPSTREAM=valhalla:8002 -e PHOTON_UPSTREAM=photon:2322 "${mounts[@]}" "$image" >/dev/null
    for _ in $(seq 1 40); do
      curl -fsS --noproxy '*' -o /dev/null "http://127.0.0.1:$port/health" 2>/dev/null && {
        echo "isolated gateway $name up on http://127.0.0.1:$port (rate limit ${GATEWAY_RATE_LIMIT:-on}, origins $origins)"; exit 0; }
      sleep 0.25
    done
    docker logs "$name" >&2; exit 1 ;;
  stop)
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker network rm "$net" >/dev/null 2>&1 || true ;;
  *) sed -n '2,13p' "$0"; exit 2 ;;
esac

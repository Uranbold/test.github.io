#!/usr/bin/env bash
# NAV-001 QA helper: run ONLY the gateway (same image, nginx.conf, snippets, entrypoint as backend/compose.yaml)
# in a throwaway container, with a chosen templates/ dir and data/ dir. Owner: qa-engineer.
# Test plan: docs/qa/test-plans/NAV-001.md (CT17 missing archive, AC 43 negative control).
#
#   tests/api/nav001/isolated-gateway.sh start NAME PORT TEMPLATES_DIR DATA_DIR [CORS_ALLOWED_ORIGINS]
#   tests/api/nav001/isolated-gateway.sh stop  NAME
#
# Examples
#   # CT17: gateway with no tiles archive, current config
#   mkdir -p /tmp/empty && tests/api/nav001/isolated-gateway.sh start qa-gw-missing 18182 backend/gateway/templates /tmp/empty
#   python3 tests/api/nav001/checks.py --base-url http://localhost:18182 --group tiles-missing
#   # AC 43 negative control: the pre-fix template from git, real data
#   git show <rev>:backend/gateway/templates/default.conf.template > /tmp/old/default.conf.template
#   tests/api/nav001/isolated-gateway.sh start qa-gw-old 18181 /tmp/old backend/data
#
# Valhalla/Photon are not started: /v1/* answers 502/503 here, which these checks do not use.
# The container joins a user-defined network so nginx's `resolver 127.0.0.11` exists.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
be="$(cd "$here/../../../backend" && pwd)"
net=navmn-qa-iso
image="$(sed -n 's/^ *image: \${GATEWAY_IMAGE:-\([^}]*\)}.*/\1/p' "$be/compose.yaml" | head -1)"

cmd="${1:-}"; name="${2:-}"
[ -n "$cmd" ] && [ -n "$name" ] || { sed -n '2,20p' "$0"; exit 2; }
case "$cmd" in
  start)
    port="$3"; tpl="$(cd "$4" && pwd)"; data="$(cd "$5" && pwd)"; cors="${6:-*}"
    docker network inspect "$net" >/dev/null 2>&1 || docker network create "$net" >/dev/null
    docker rm -f "$name" >/dev/null 2>&1 || true
    # Same mounts as backend/compose.yaml: njs (tiles 416 header filter, since the NAV-001 fixes round) and every
    # entrypoint script (15-cors-origins.sh, 16-rate-limits.sh from NAV-008; rate limits default to off).
    extra=()
    [ -d "$be/gateway/njs" ] && extra+=(-v "$be/gateway/njs:/etc/nginx/njs:ro")
    for f in "$be"/gateway/entrypoint/*.sh; do extra+=(-v "$f:/docker-entrypoint.d/$(basename "$f"):ro"); done
    docker run -d --name "$name" --network "$net" -p "127.0.0.1:$port:8080" \
      -e CORS_ALLOWED_ORIGINS="$cors" -e GATEWAY_ERROR_LOG_LEVEL=crit \
      -e VALHALLA_UPSTREAM=valhalla:8002 -e PHOTON_UPSTREAM=photon:2322 -e NGINX_ENTRYPOINT_QUIET_LOGS=1 \
      -v "$be/gateway/nginx.conf:/etc/nginx/nginx.conf:ro" \
      -v "$tpl:/etc/nginx/templates:ro" \
      -v "$be/gateway/snippets:/etc/nginx/snippets:ro" \
      "${extra[@]}" \
      -v "$data:/srv/data:ro" "$image" >/dev/null
    for _ in $(seq 1 40); do
      curl -fsS -o /dev/null "http://127.0.0.1:$port/health" 2>/dev/null && { echo "gateway $name up on :$port ($image, templates=$tpl, data=$data, cors=$cors)"; exit 0; }
      sleep 0.25
    done
    docker logs "$name" >&2; exit 1 ;;
  stop)
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker network rm "$net" >/dev/null 2>&1 || true ;;
  *) echo "unknown command $cmd" >&2; exit 2 ;;
esac

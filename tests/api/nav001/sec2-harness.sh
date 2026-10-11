#!/usr/bin/env bash
# NAV-001 SEC-2 QA harness (AC 48, AC 49). Owner: qa-engineer. Test plan: docs/qa/test-plans/NAV-001.md section 3 H.
# Starts throwaway upstreams on the isolated-gateway network; never touches the shared dev stack (project navmn).
#
#   tests/api/nav001/sec2-harness.sh stub-up                 # QA stub upstream qa-sec2-upstream:8002 (slow/hang modes)
#   tests/api/nav001/sec2-harness.sh valhalla-slow [CPUS]    # real Valhalla 3.9.0 (backend/data read-only, the
#                                                            # working-tree valhalla-serve.sh) as qa-sec2-valhalla:8002 with
#                                                            # VALHALLA_TIMEOUT_SECONDS=1 and --cpus CPUS (default 0.02), so
#                                                            # a normal request runs past 1 s and hits Valhalla's own timeout
#   tests/api/nav001/sec2-harness.sh down
#   tests/api/nav001/sec2-harness.sh ac48-run [PORT]         # AC 48 in one command: stub-up, isolated gateway on PORT
#                                                            # (default 18281) with QA_VALHALLA_UPSTREAM=qa-sec2-upstream:8002,
#                                                            # checks.py --group ac48 --group ac48-hang, then stop + down
#                                                            # (always, also on failure). Exit code = checks.py's
#
# Then, for example (AC 49 per IP, default cap 4/8):
#   QA_VALHALLA_UPSTREAM=qa-sec2-upstream:8002 QA_EXTRA_NETWORK=navmn_default \
#     tests/api/nav001/isolated-gateway.sh start qa-gw-sec2 18281 backend/gateway/templates backend/data
#   python3 tests/api/nav001/checks.py --base-url http://localhost:18281 --group ac49-ip --group ac48-hang
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
be="$(cd "$here/../../../backend" && pwd)"
net=navmn-qa-iso
gw_image="$(sed -n 's/^ *image: \${GATEWAY_IMAGE:-\([^}]*\)}.*/\1/p' "$be/compose.yaml" | head -1)"
vh_image="$(sed -n 's/^ *image: \${VALHALLA_IMAGE:-\([^}]*\)}.*/\1/p' "$be/compose.yaml" | head -1)"
net_up() { docker network inspect "$net" >/dev/null 2>&1 || docker network create "$net" >/dev/null; }
wait_status() {  # container, seconds
  for _ in $(seq 1 "$2"); do
    docker exec "$1" sh -c 'curl -fsS -o /dev/null http://127.0.0.1:8002/status 2>/dev/null || wget -q -O /dev/null http://127.0.0.1:8002/status' \
      >/dev/null 2>&1 && return 0
    sleep 1
  done
  docker logs "$1" >&2; return 1
}
case "${1:-}" in
  stub-up)
    net_up; docker rm -f qa-sec2-upstream >/dev/null 2>&1 || true
    docker run -d --name qa-sec2-upstream --network "$net" --entrypoint nginx \
      -v "$here/stub-upstream/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$here/stub-upstream:/stub:ro" \
      "$gw_image" -g 'daemon off;' >/dev/null
    wait_status qa-sec2-upstream 20 && echo "qa-sec2-upstream:8002 up ($gw_image)" ;;
  valhalla-slow)
    cpus="${2:-0.02}"; net_up; docker rm -f qa-sec2-valhalla >/dev/null 2>&1 || true
    docker run -d --name qa-sec2-valhalla --network "$net" --cpus "$cpus" -e VALHALLA_TIMEOUT_SECONDS=1 -e VALHALLA_THREADS=1 \
      --entrypoint /bin/bash -v "$be/scripts:/scripts:ro" -v "$be/data:/data:ro" "$vh_image" /scripts/valhalla-serve.sh >/dev/null
    wait_status qa-sec2-valhalla 240 && { echo "qa-sec2-valhalla:8002 up (cpus $cpus)"; docker logs qa-sec2-valhalla 2>&1 | grep -m1 'route limits applied' || true; } ;;
  down)
    docker rm -f qa-sec2-upstream qa-sec2-valhalla >/dev/null 2>&1 || true ;;
  ac48-run)
    port="${2:-18281}"
    trap 'docker rm -f qa-sec2-upstream >/dev/null 2>&1 || true; "$here/isolated-gateway.sh" stop qa-gw-ac48' EXIT
    "$0" stub-up
    QA_VALHALLA_UPSTREAM=qa-sec2-upstream:8002 "$here/isolated-gateway.sh" start qa-gw-ac48 "$port" "$be/gateway/templates" "$be/data"
    rc=0
    python3 "$here/checks.py" --base-url "http://localhost:$port" --group ac48 --group ac48-hang ${QA_JSON_OUT:+--json-out "$QA_JSON_OUT"} || rc=$?
    exit "$rc" ;;
  *) sed -n '2,21p' "$0"; exit 2 ;;
esac

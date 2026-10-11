#!/usr/bin/env bash
# NAV-001 valhalla service: serve the prebuilt graph on :8002 (not published; the gateway proxies it).
#
# SEC-2 (ADR-0019 §1-2): valhalla_service runs on a container-local copy of the graph's valhalla.json with the
# lib.sh route limits (service_limits for every costing) and VALHALLA_TIMEOUT_SECONDS (1-9, default 8) overlaid.
# /data stays read-only, and a data/ build or NAV-006 slot made before SEC-2 serves the limits without a rebuild.
# An invalid VALHALLA_TIMEOUT_SECONDS stops the service with a log line.
#
# Privacy (ADR-0002 §3.4): valhalla_service logs every HTTP request line, and for
# GET /route?json=... that line holds the coordinates. Its output is piped through sed, which
# replaces every query string with "?<redacted>" before anything reaches the Docker log.
# valhalla_service stays PID 1 (exec), so stop signals reach it directly.
SERVICE=valhalla
# shellcheck source=lib.sh
source /scripts/lib.sh
trap - ERR

CONF=$DATA/valhalla/valhalla.json
RUNTIME_CONF=/tmp/valhalla.json
[[ -f "$DATA/valhalla/.complete" && -s "$CONF" ]] || die "routing graph not built; refusing to start" config="${CONF#"$DATA"/}"
valhalla_timeout_seconds >/dev/null || exit 1
limits=$(valhalla_apply_limits "$CONF" "$RUNTIME_CONF") || die "could not apply the route limits; refusing to start"
log info "route limits applied" limits="$limits"
log info "starting valhalla_service" threads="${VALHALLA_THREADS:-$(nproc)}"
exec > >(exec sed -u -E 's#(GET|POST|HEAD|PUT|DELETE|OPTIONS|PATCH) ([^ ?]*)\?[^ ]*#\1 \2?<redacted>#g') 2>&1
exec valhalla_service "$RUNTIME_CONF" "${VALHALLA_THREADS:-$(nproc)}"

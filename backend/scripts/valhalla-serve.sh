#!/usr/bin/env bash
# NAV-001 valhalla service: serve the prebuilt graph on :8002 (not published; the gateway proxies it).
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
[[ -f "$DATA/valhalla/.complete" && -s "$CONF" ]] || die "routing graph not built; refusing to start" config="${CONF#"$DATA"/}"
log info "starting valhalla_service" threads="${VALHALLA_THREADS:-$(nproc)}"
exec > >(exec sed -u -E 's#(GET|POST|HEAD|PUT|DELETE|OPTIONS|PATCH) ([^ ?]*)\?[^ ]*#\1 \2?<redacted>#g') 2>&1
exec valhalla_service "$CONF" "${VALHALLA_THREADS:-$(nproc)}"

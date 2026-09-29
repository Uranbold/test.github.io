#!/usr/bin/env bash
# NAV-001 valhalla service: serve the prebuilt graph on :8002 (not published; the gateway proxies it).
SERVICE=valhalla
# shellcheck source=lib.sh
source /scripts/lib.sh
trap - ERR

CONF=$DATA/valhalla/valhalla.json
[[ -f "$DATA/valhalla/.complete" && -s "$CONF" ]] || die "routing graph not built; refusing to start" config="${CONF#"$DATA"/}"
log info "starting valhalla_service" threads="${VALHALLA_THREADS:-$(nproc)}"
exec valhalla_service "$CONF" "${VALHALLA_THREADS:-$(nproc)}"

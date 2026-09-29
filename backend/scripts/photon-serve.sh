#!/usr/bin/env bash
# NAV-001 photon service (ADR-0003): serve data/photon on :2322 (not published; the gateway proxies it).
# No -cors-any: CORS belongs to the gateway (ADR-0002 §3.1).
SERVICE=photon
# shellcheck source=lib.sh
source /scripts/lib.sh
trap - ERR

[[ -f "$DATA/photon/.complete" && -d "$DATA/photon/photon_data" ]] || die "photon index not built; refusing to start"
log info "starting photon" default_language="${PHOTON_DEFAULT_LANGUAGE:-mn}" max_results="${PHOTON_MAX_RESULTS:-20}"
# shellcheck disable=SC2086
exec java ${PHOTON_JAVA_OPTS:-} -jar "$DATA/tools/photon.jar" serve \
    -data-dir "$DATA/photon" \
    -listen-ip 0.0.0.0 \
    -listen-port 2322 \
    -default-language "${PHOTON_DEFAULT_LANGUAGE:-mn}" \
    -max-results "${PHOTON_MAX_RESULTS:-20}"

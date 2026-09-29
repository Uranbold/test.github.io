#!/usr/bin/env bash
# NAV-001 valhalla-build (ADR-0002 §1, §4): routing graph from data/sources/osm.pbf
#   -> data/valhalla/valhalla_tiles.tar + data/valhalla/valhalla.json (runtime config)
# Built in data/valhalla.staging and swapped in only after the tar is complete.
SERVICE=valhalla-build
# shellcheck source=lib.sh
source /scripts/lib.sh

FINAL=$DATA/valhalla
STAGING=$DATA/valhalla.staging
MARKER=$FINAL/.complete

require_file "$DATA/sources/osm.pbf.sha256"
FP=$(valhalla_fingerprint)

if marker_matches "$MARKER" "$FP" && [[ -s "$FINAL/valhalla_tiles.tar" && -s "$FINAL/valhalla.json" ]]; then
    log info "reused" artefact="valhalla/valhalla_tiles.tar" bytes="$(stat -c %s "$FINAL/valhalla_tiles.tar")"
    exit 0
fi
log info "building routing graph" reason="$([[ -f "$MARKER" ]] && echo 'inputs changed' || echo 'no complete artefact')"
rm -rf "$STAGING"
mkdir -p "$STAGING/tiles"

# make_config <tile_dir> <tile_extract>: Valhalla defaults + our paths. No admin/timezone DBs in
# Phase 0 (defaults: right-hand traffic, which is correct for Mongolia).
make_config() {
    valhalla_build_config \
        --mjolnir-tile-dir "$1" \
        --mjolnir-tile-extract "$2" \
        --mjolnir-traffic-extract "" \
        --mjolnir-admin "" \
        --mjolnir-timezone "" \
        --mjolnir-landmarks "" \
        --mjolnir-concurrency "$(threads)" \
        --service-limits-max-alternates 2 \
        --httpd-service-listen "tcp://*:8002"
}

make_config "$STAGING/tiles" "$STAGING/valhalla_tiles.tar" > "$STAGING/build.json"

t0=$(now_s)
( cd "$STAGING" && valhalla_build_tiles -c "$STAGING/build.json" "$DATA/sources/osm.pbf" ) \
    || die "valhalla_build_tiles failed"
valhalla_build_extract -c "$STAGING/build.json" --overwrite \
    || die "valhalla_build_extract failed"
[[ -s "$STAGING/valhalla_tiles.tar" ]] || die "valhalla_build_extract produced no tar"
secs=$(( $(now_s) - t0 ))

make_config "$FINAL/tiles" "$FINAL/valhalla_tiles.tar" > "$STAGING/valhalla.json"
rm -rf "$STAGING/tiles" "$STAGING/build.json"
find "$STAGING" -maxdepth 1 -type f ! -name 'valhalla_tiles.tar' ! -name 'valhalla.json' -delete
swap_dir "$STAGING" "$FINAL"
write_marker "$MARKER" "$FP" "seconds=$secs" "bytes=$(stat -c %s "$FINAL/valhalla_tiles.tar")"
log info "routing graph built" artefact="valhalla/valhalla_tiles.tar" bytes="$(stat -c %s "$FINAL/valhalla_tiles.tar")" seconds="$secs"

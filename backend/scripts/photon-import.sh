#!/usr/bin/env bash
# NAV-001 photon-import (ADR-0003): Photon JSON dump -> data/photon/photon_data
# Imported into data/photon.staging and swapped in only after a successful import.
SERVICE=photon-import
# shellcheck source=lib.sh
source /scripts/lib.sh

FINAL=$DATA/photon
STAGING=$DATA/photon.staging
MARKER=$FINAL/.complete
DUMP=$DATA/sources/photon-dump

require_file "$DATA/sources/photon-dump.sha256"
require_file "$DATA/tools/photon.jar"
FP=$(photon_fingerprint)

# SEC-4A BE-4/BE-5: Photon serves as a non-root user with data/ read-only and only this directory writable, so the
# index (OpenSearch lock and state files) must belong to that user. Idempotent; also fixes indexes built before.
give_index_to_photon() {
    chown -R "$PHOTON_RUN_UID:$PHOTON_RUN_UID" "$FINAL" || die "cannot hand the Photon index to uid $PHOTON_RUN_UID (CHOWN capability?)"
}

if marker_matches "$MARKER" "$FP" && [[ -d "$FINAL/photon_data" ]]; then
    give_index_to_photon
    log info "reused" artefact="photon/photon_data"
    exit 0
fi
log info "importing photon index" reason="$([[ -f "$MARKER" ]] && echo 'inputs changed' || echo 'no complete artefact')" languages="$PHOTON_LANGUAGES"
require_file "$DATA/tools/aircompressor.jar"
require_file "$DUMP"
rm -rf "$STAGING"
mkdir -p "$STAGING"

ZCAT=(java -cp "$DATA/tools/aircompressor.jar" /scripts/ZstdCat.java)
"${ZCAT[@]}" "$DUMP" --head 1 > "$STAGING/dump-header.json" || die "cannot read the Photon dump header"

country_args=()
[[ -n "${PHOTON_COUNTRY_CODES:-}" ]] && country_args=(-country-codes "$PHOTON_COUNTRY_CODES")

t0=$(now_s)
# shellcheck disable=SC2086
"${ZCAT[@]}" "$DUMP" \
    | java ${PHOTON_JAVA_OPTS:-} -jar "$DATA/tools/photon.jar" import \
        -import-file - \
        -languages "$PHOTON_LANGUAGES" \
        "${country_args[@]}" \
        -data-dir "$STAGING" \
        -j "$(threads)" \
    || die "photon import failed"
[[ -d "$STAGING/photon_data" ]] || die "photon import produced no photon_data directory"
secs=$(( $(now_s) - t0 ))

swap_dir "$STAGING" "$FINAL"
write_marker "$MARKER" "$FP" "seconds=$secs" "bytes=$(du -sb "$FINAL/photon_data" | cut -f1)"
give_index_to_photon
log info "photon index built" artefact="photon/photon_data" seconds="$secs"

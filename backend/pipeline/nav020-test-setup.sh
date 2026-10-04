#!/usr/bin/env bash
# NAV-020 dev-container test setup (AC 29-32; task file B17). Creates a separate root for the Compose project
# navmn-nav020 from the shared dev stack's artefacts WITHOUT changing backend/data:
#   - the Photon dump and build-info.json: COPIES (small)
#   - the PMTiles archive, the Valhalla tar + config and the Photon/aircompressor jars: HARD LINKS by default (0 extra
#     bytes; the pack step and the lanes only read them, nothing here opens them for writing or changes their mode),
#     COPIES with NAV020_COPY_DATA=1 (about 0.3 GB more). backend/data/photon is never read (a live Photon has it open).
# Then renders <root>/nav020.env from nav020-test.env.template. Slots are seeded with pipeline/nav020_test.py.
#
#   backend/pipeline/nav020-test-setup.sh [TEST_ROOT]        default /var/tmp/nav020-test
#   make -C backend nav020-test-setup [NAV020_TEST_ROOT=...]
# Undo: backend/pipeline/nav020-test-teardown.sh [TEST_ROOT]
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
BACKEND=$(cd "$HERE/.." && pwd)
SRC=$BACKEND/data
ROOT=${1:-/var/tmp/nav020-test}
DATA=$ROOT/data
IN=$ROOT/input

log() { printf '{"ts":"%s","job":"nav020-test-setup","msg":"%s"}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

[[ "$ROOT" == /* ]] || die "TEST_ROOT must be absolute"
case "$(readlink -f "$ROOT")/" in "$(readlink -f "$BACKEND")"/*) die "TEST_ROOT must be outside the git checkout" ;; esac
for f in sources/photon-dump tiles/basemap.pmtiles valhalla/valhalla_tiles.tar valhalla/valhalla.json valhalla/.complete \
         tiles/.complete build-info.json tools/photon.jar tools/aircompressor.jar; do
    [[ -s "$SRC/$f" ]] || die "missing $SRC/$f (build the dev stack first: make up)"
done
mkdir -p "$ROOT"
[[ "$(stat -c %d "$ROOT")" == "$(stat -c %d "$SRC/sources")" ]] || die "TEST_ROOT must be on the same filesystem as backend/data"
[[ ! -e "$DATA/state.json" ]] || die "$ROOT is already set up (run nav020-test-teardown.sh first)"

mkdir -p "$DATA"/{slots,lanes,runs,packs,cache} "$DATA"/pointer/{public,verify} "$IN/tools"
chmod 755 "$ROOT" "$DATA" "$DATA/slots" "$DATA/pointer" "$DATA"/pointer/* "$DATA/packs"

put() {  # put SRC DST: hard link (default) or copy
    if [[ "${NAV020_COPY_DATA:-0}" == 1 ]]; then cp "$1" "$2"; else ln "$1" "$2"; fi
}
put "$SRC/tiles/basemap.pmtiles" "$IN/basemap.pmtiles"
put "$SRC/valhalla/valhalla_tiles.tar" "$IN/valhalla_tiles.tar"
put "$SRC/tools/photon.jar" "$IN/tools/photon.jar"
put "$SRC/tools/aircompressor.jar" "$IN/tools/aircompressor.jar"
cp "$SRC/valhalla/valhalla.json" "$IN/valhalla.json"
cp "$SRC/valhalla/.complete" "$IN/valhalla.complete"
cp "$SRC/tiles/.complete" "$IN/tiles.complete"
cp "$SRC/build-info.json" "$IN/build-info.json"
cp "$SRC/sources/photon-dump" "$IN/photon-dump.jsonl.zst"
for s in photon-dump.sha256 photon-dump.source photon-dump.last-modified; do
    [[ -s "$SRC/sources/$s" ]] && cp "$SRC/sources/$s" "$IN/$s"
done
: > "$IN/mongolia.osm.pbf"     # the NAV-006 config needs a source entry; no rebuild runs in this test
sed "s|@ROOT@|$ROOT|g" "$HERE/nav020-test.env.template" > "$ROOT/nav020.env"
mode=$([[ "${NAV020_COPY_DATA:-0}" == 1 ]] && echo copies || echo "hard links (read-only use)")
log "test root ready: $ROOT (tiles, graph, jars: $mode; dump and build-info: copies); config: $ROOT/nav020.env"
log "next: python3 $HERE/nav020_test.py --env-file $ROOT/nav020.env seed <SLOT_ID>; ... activate <SLOT_ID>; make -C $BACKEND pack-publish NAV_ENV_FILE=$ROOT/nav020.env"

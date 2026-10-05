#!/usr/bin/env bash
# NAV-006 dev-container test setup (D126, ADR-0014 §11). Creates a separate slot root for the test project
# navmn-nav006 from the shared dev stack's cache WITHOUT changing backend/data:
#   - auxiliary Planetiler files and tools: HARD LINKS (read-only use; 0 extra bytes)
#   - the OSM extract and the Photon dump: COPIES (tests truncate or replace their own copies)
#   - backend/data/photon is never read (a live Photon has it open)
# Then renders <root>/nav006.env from nav006-test.env.template and creates the test gateway's directories.
#
#   backend/pipeline/test-setup.sh [TEST_ROOT]        default /var/tmp/nav006-test
#   make -C backend nav006-test-setup [NAV006_TEST_ROOT=...]
# Undo: backend/pipeline/test-teardown.sh [TEST_ROOT]
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
BACKEND=$(cd "$HERE/.." && pwd)
SRC=$BACKEND/data
ROOT=${1:-/var/tmp/nav006-test}
DATA=$ROOT/data

log() { printf '{"ts":"%s","job":"nav006-test-setup","msg":"%s"}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

[[ "$ROOT" == /* ]] || die "TEST_ROOT must be absolute"
case "$(readlink -f "$ROOT")/" in "$(readlink -f "$BACKEND")"/*) die "TEST_ROOT must be outside the git checkout" ;; esac
for f in osm.pbf photon-dump; do [[ -s "$SRC/sources/$f" ]] || die "missing $SRC/sources/$f (build the dev stack first: make up)"; done
[[ -d "$SRC/tools" ]] || die "missing $SRC/tools"
mkdir -p "$ROOT"
[[ "$(stat -c %d "$ROOT")" == "$(stat -c %d "$SRC/sources")" ]] || die "TEST_ROOT must be on the same filesystem as backend/data (hard links)"
[[ ! -e "$DATA/state.json" ]] || die "$ROOT is already set up (run test-teardown.sh first)"

mkdir -p "$DATA"/cache/{sources,tools,osm,photon-dump,photon-index} "$DATA"/{slots,lanes,runs,packs} \
         "$DATA"/pointer/{public,verify} "$ROOT/input"
chmod 755 "$ROOT" "$DATA" "$DATA/slots" "$DATA/pointer" "$DATA"/pointer/* "$DATA/packs"

n=0
for f in "$SRC"/sources/*; do
    b=$(basename "$f")
    case "$b" in osm.pbf|osm.pbf.*|photon-dump|photon-dump.*) continue ;; esac
    [[ -f "$f" ]] || continue
    ln "$f" "$DATA/cache/sources/$b"; n=$((n + 1))
done
t=0
for f in "$SRC"/tools/*; do
    [[ -f "$f" ]] || continue
    ln "$f" "$DATA/cache/tools/$(basename "$f")"; t=$((t + 1))
done
cp "$SRC/sources/osm.pbf" "$ROOT/input/mongolia.osm.pbf"
cp "$SRC/sources/photon-dump" "$ROOT/input/photon-dump.jsonl.zst"
for s in osm.pbf:mongolia.osm.pbf photon-dump:photon-dump.jsonl.zst; do
    if [[ -s "$SRC/sources/${s%%:*}.last-modified" ]]; then
        cp "$SRC/sources/${s%%:*}.last-modified" "$ROOT/input/${s#*:}.last-modified"
    fi
done
sed "s|@ROOT@|$ROOT|g" "$HERE/nav006-test.env.template" > "$ROOT/nav006.env"
log "test root ready: $ROOT (aux hard links: $n, tool hard links: $t, copies: extract + dump); config: $ROOT/nav006.env"
log "next: make -C $BACKEND nav006-up NAV_ENV_FILE=$ROOT/nav006.env && make -C $BACKEND rebuild NAV_ENV_FILE=$ROOT/nav006.env"

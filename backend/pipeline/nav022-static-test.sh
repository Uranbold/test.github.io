#!/usr/bin/env bash
# NAV-022 static pack host test (make pack-export-test). Separate Compose project navmn-nav022 on 127.0.0.1:18191; the
# shared dev stack, backend/data and any NAV-020 test root are never read or written.
#   1. publishes a small synthetic pack (pipeline/nav022_test_pack.py, ~0.5 MB) into <root>/data/packs
#   2. make pack-export into <root>/www/packs (with .htaccess) and <root>/www/packs-raw (HTACCESS=0, negative control)
#   3. starts Apache httpd 2.4 configured like a typical shared host (pack/static-host-test/hosting.conf)
#   4. scripts/pack_static_check.py: www/packs must pass (--full --local), www/packs-raw must FAIL
#   5. a weekly cut (routing + search only), re-export into the same DEST, check again; the README curl checks
#   6. teardown: compose down -v, the httpd image (unless NAV022_KEEP_IMAGE=1), the root (unless NAV022_KEEP=1)
#
#   backend/pipeline/nav022-static-test.sh [TEST_ROOT]        default /var/tmp/nav022-static-test
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
BACKEND=$(cd "$HERE/.." && pwd)
ROOT=${1:-/var/tmp/nav022-static-test}
PORT=${NAV022_STATIC_PORT:-18191}
BASE=http://127.0.0.1:$PORT
PY=${PYTHON:-python3}
DC=(docker compose -p navmn-nav022 -f "$BACKEND/pack/static-host-test/compose.yaml")
V1=20261001T000000Z
V2=20261008T000000Z

log() { printf '{"ts":"%s","job":"nav022-static-test","msg":"%s"}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "FAILED: $*"; exit 1; }

[[ "$ROOT" == /* ]] || die "TEST_ROOT must be absolute"
case "$(readlink -f "$ROOT")/" in "$(readlink -f "$BACKEND/..")"/*) die "TEST_ROOT must be outside the git checkout" ;; esac
[[ ! -e "$ROOT" ]] || die "$ROOT exists (left from an earlier run? remove it first)"
[[ "$PORT" != 8080 ]] || die "port 8080 is the shared dev gateway"

teardown() {
    rc=$?
    export NAV022_WWW="$ROOT/www"
    "${DC[@]}" logs --no-color static 2>/dev/null | grep -v AH00558 | tail -n 5 | sed "s/^/  httpd: /" || true
    if [[ "${NAV022_KEEP_IMAGE:-0}" == 1 ]]; then "${DC[@]}" down -v >/dev/null 2>&1 || true
    else "${DC[@]}" down -v --rmi all >/dev/null 2>&1 || true; fi
    if [[ "${NAV022_KEEP:-0}" != 1 ]]; then rm -rf "$ROOT"; fi
    log "teardown done (project navmn-nav022 removed$([[ "${NAV022_KEEP:-0}" == 1 ]] && echo ", root kept: $ROOT" || echo ", root removed"))"
    exit $rc
}
trap teardown EXIT

mkdir -p "$ROOT/www"
chmod 755 "$ROOT" "$ROOT/www"
printf '<!doctype html><title>CMS</title><p>front controller page</p>\n' > "$ROOT/www/index.html"
chmod 644 "$ROOT/www/index.html"

log "1. synthetic pack $V1"
$PY "$HERE/nav022_test_pack.py" --packs-dir "$ROOT/data/packs" --version $V1
log "2. export (with .htaccess) and negative control (HTACCESS=0)"
make -s -C "$BACKEND" pack-export PACKS_DIR="$ROOT/data/packs" DEST="$ROOT/www/packs"
make -s -C "$BACKEND" pack-export PACKS_DIR="$ROOT/data/packs" DEST="$ROOT/www/packs-raw" HTACCESS=0 >/dev/null
chmod -R a+rX "$ROOT/www"
sleep 2   # Apache sends a WEAK ETag for a file modified < 1 s before the request (If-Range then answers 200)

log "3. httpd (typical shared-host config) on 127.0.0.1:$PORT"
export NAV022_WWW="$ROOT/www"
"${DC[@]}" up -d --wait --quiet-pull
docker exec navmn-nav022-static-1 httpd -v | sed -n 1p

log "4a. exported tree with .htaccess: must pass"
$PY "$BACKEND/scripts/pack_static_check.py" --base-url "$BASE/packs/" --local "$ROOT/www/packs" --full \
    || die "the exported tree with .htaccess does not pass"
log "4b. negative control without .htaccess: must FAIL"
if $PY "$BACKEND/scripts/pack_static_check.py" --base-url "$BASE/packs-raw/" --local "$ROOT/www/packs-raw"; then
    die "the negative control passed: the test host does not reproduce shared-host defaults"
fi
log "4b. negative control failed as expected"

log "5. weekly cut $V2 (routing + search; tiles stay $V1), re-export into the same DEST"
$PY "$HERE/nav022_test_pack.py" --packs-dir "$ROOT/data/packs" --version $V2 --keep-tiles
make -s -C "$BACKEND" pack-export PACKS_DIR="$ROOT/data/packs" DEST="$ROOT/www/packs"
chmod -R a+rX "$ROOT/www"
sleep 2   # see above (weak ETag of just-written files)
find "$ROOT/www/packs" -type f -printf '  %P %s\n' | sort
[[ -f "$ROOT/www/packs/mn/$V1/basemap.pmtiles.gz" && ! -e "$ROOT/www/packs/mn/$V1/routing.tar.gz" \
   && -f "$ROOT/www/packs/mn/$V2/routing.tar.gz" ]] || die "unexpected tree after the weekly cut"
$PY "$BACKEND/scripts/pack_static_check.py" --base-url "$BASE/packs/" --local "$ROOT/www/packs" --full \
    || die "the re-exported tree does not pass"

log "5b. README curl checks (the same commands the PO runs; host shown here is the loopback test host)"
B="$BASE/packs"
F=$(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); print([f["path"] for f in m["files"] if f["kind"]=="routing"][0])' "$ROOT/www/packs/mn/manifest.json")
set -x
curl -sS -o /dev/null -D - "$B/mn/manifest.json" -H 'Accept-Encoding: gzip, br' | grep -iE '^(HTTP|etag|cache-control|content-type|content-encoding)'
ETAG=$(curl -sS -o /dev/null -D - "$B/mn/manifest.json" | awk 'tolower($1)=="etag:"{print $2}' | tr -d '\r')
curl -sS -o /dev/null -w '%{http_code}\n' "$B/mn/manifest.json" -H "If-None-Match: $ETAG"
curl -sS -o /dev/null -D - "$B/mn/$F" -H 'Accept-Encoding: gzip, br' | grep -iE '^(HTTP|etag|content-length|content-encoding|accept-ranges|cache-control|content-type)'
curl -sS -o /dev/null -D - "$B/mn/$F" -H 'Range: bytes=1000-' | grep -iE '^(HTTP|content-range|content-length)'
curl -sS -o /dev/null -w '%{http_code}\n' "$B/mn/19990101T000000Z/routing.tar.gz"
set +x
log "PASS: export + .htaccess verified on httpd with shared-host defaults; negative control failed as expected"

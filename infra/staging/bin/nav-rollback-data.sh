#!/usr/bin/env bash
# NAV-008 staging: put the previous data set back (B4). Owner: backend-engineer.
#   sudo infra/staging/bin/nav-rollback-data.sh          # restore $NAV_STATE_DIR/rollback, restart, smoke
# The rollback copy is made by nav-rebuild.sh before every build (if disk allows). It holds the previous
# tiles archive, routing graph, build-info.json and resolved OSM/Photon sources, plus the Photon index when
# that run re-imported it (see $NAV_STATE_DIR/rollback/COMPONENTS). The copy is kept, so this can be re-run.
# The gateway keeps serving throughout; Valhalla (and Photon, if restored) restart on the old data.
# shellcheck source=nav-env.sh
source "$(dirname "$(readlink -f "$0")")/nav-env.sh"

LOCKED=0
[[ "${1:-}" == "--locked" ]] && LOCKED=1   # called by nav-rebuild.sh, which already holds the lock

nav_require_root
nav_require_env_file
if [[ $LOCKED -eq 0 ]]; then
    exec 9>"${NAV_LOCK_FILE:-/run/lock/nav-stack.lock}"
    flock -n 9 || nav_die "another rebuild, deploy or rollback holds the lock"
fi

DATA="$NAV_ROOT/backend/data"
RB="$NAV_STATE_DIR/rollback"
[[ -s "$RB/COMPONENTS" ]] || nav_die "no rollback copy found" dir="$RB"
read -r -a parts < "$RB/COMPONENTS"
nav_log info "rolling back data" created="$(cat "$RB/CREATED" 2>/dev/null || echo unknown)" components="${parts[*]}"

# swap_in <name>: copy the rollback directory next to the live one, then swap with two renames.
swap_in() {
    local name=$1
    rm -rf "$DATA/$name.rollback-tmp" "$DATA/$name.rollback-old"
    cp -a "$RB/$name" "$DATA/$name.rollback-tmp"
    if [[ -e "$DATA/$name" ]]; then mv "$DATA/$name" "$DATA/$name.rollback-old"; fi
    mv "$DATA/$name.rollback-tmp" "$DATA/$name"
    rm -rf "$DATA/$name.rollback-old"
}

photon=0
for p in "${parts[@]}"; do [[ "$p" == photon ]] && photon=1; done
if [[ $photon -eq 1 ]]; then nav_compose stop photon; fi

for p in "${parts[@]}"; do
    case "$p" in
        tiles|valhalla|photon) swap_in "$p" ;;
        build-info) cp -a "$RB/build-info.json" "$DATA/build-info.json.rollback-tmp" && mv -f "$DATA/build-info.json.rollback-tmp" "$DATA/build-info.json" ;;
        sources)
            rm -f "$DATA/sources"/osm.pbf "$DATA/sources"/osm.pbf.* "$DATA/sources"/photon-dump "$DATA/sources"/photon-dump.*
            cp -a "$RB/sources/." "$DATA/sources/" ;;
        *) nav_log warn "unknown rollback component ignored" component="$p" ;;
    esac
done

nav_compose up -d --no-deps --wait --force-recreate valhalla
nav_compose up -d --no-deps --wait photon
rm -f "$NAV_STATE_DIR/osm.md5"   # the next rebuild must not skip as "unchanged"
nav_log info "rollback finished; data is the previous set" built_at="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("built_at"))' "$DATA/build-info.json" 2>/dev/null || echo unknown)"

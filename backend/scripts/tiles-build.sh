#!/usr/bin/env bash
# NAV-001 tiles-build (ADR-0002 §1, §4): Protomaps basemap (Planetiler) -> data/tiles/basemap.pmtiles
#  1. build the Protomaps jar once per commit with Maven (cached in data/tools/)
#  2. run Planetiler on data/sources/osm.pbf without --download (all inputs pre-fetched)
#  3. validate the archive (magic, version, max zoom, attribution) and rename it into place
SERVICE=tiles-build
# shellcheck source=lib.sh
source /scripts/lib.sh

FINAL_DIR=$DATA/tiles
FINAL=$FINAL_DIR/basemap.pmtiles
MARKER=$FINAL_DIR/.complete
STAGING=$FINAL_DIR/.staging

require_file "$DATA/sources/osm.pbf.sha256"
FP=$(tiles_fingerprint)

if marker_matches "$MARKER" "$FP" && [[ -s "$FINAL" ]]; then
    log info "reused" artefact="tiles/basemap.pmtiles" bytes="$(stat -c %s "$FINAL")"
    exit 0
fi
log info "building tiles" reason="$([[ -f "$MARKER" ]] && echo 'inputs changed' || echo 'no complete artefact')"
mkdir -p "$FINAL_DIR"
rm -f "$MARKER"
rm -rf "$STAGING"

build_protomaps_jar() {
    local jar work mvn_home t0
    jar=$(protomaps_jar)
    [[ -s "$jar" ]] && return 0
    t0=$(now_s)
    require_file "$DATA/tools/protomaps-basemaps-${PROTOMAPS_COMMIT}.tar.gz"
    require_file "$DATA/tools/maven-bin.tar.gz"
    work=$(mktemp -d)
    tar -xzf "$DATA/tools/protomaps-basemaps-${PROTOMAPS_COMMIT}.tar.gz" -C "$work"
    mkdir -p "$work/maven"
    tar -xzf "$DATA/tools/maven-bin.tar.gz" -C "$work/maven" --strip-components=1
    mvn_home=$work/maven
    JAVA_NET_OPTS=$(java_net_opts)
    log info "building Protomaps basemap jar with Maven" commit="$PROTOMAPS_COMMIT"
    (
        cd "$work"/basemaps-*/tiles
        # shellcheck disable=SC2086
        MAVEN_OPTS="$JAVA_NET_OPTS" "$mvn_home/bin/mvn" -B -q -ntp -DskipTests \
            -Dmaven.repo.local="$DATA/tools/m2" package
        cp target/protomaps-basemap-HEAD-with-deps.jar "$jar.part"
    ) || die "Maven build of the Protomaps basemap failed" commit="$PROTOMAPS_COMMIT"
    mv -f "$jar.part" "$jar"
    rm -rf "$work" "$DATA/tools/m2"
    log info "Protomaps jar built" jar="${jar#"$DATA"/}" seconds="$(( $(now_s) - t0 ))"
}

build_protomaps_jar

for f in natural_earth_vector.gpkg.zip water-polygons-split-3857.zip land-polygons-split-3857.zip \
         daylight-landcover.gpkg qrank.csv.gz pgf-encoding.zip; do
    require_file "$DATA/sources/$f"
done

BOUNDS=$(tiles_bounds)
# Without bounds Planetiler would render land/water polygons for the whole world up to maxzoom.
[[ -n "$BOUNDS" ]] || die "no tile bounds: the PBF has no bbox and TILES_BOUNDS is empty; set TILES_BOUNDS=minLon,minLat,maxLon,maxLat"
mkdir -p "$STAGING/tmp"
t0=$(now_s)
log info "running Planetiler" maxzoom="${TILES_MAXZOOM:-14}" bounds="$BOUNDS" threads="$(threads)"
# Protomaps resolves its auxiliary sources relative to the working directory: /data/sources.
cd /
# shellcheck disable=SC2086
java ${TILES_JAVA_OPTS:-} -jar "$(protomaps_jar)" \
    --area=extract \
    --osm_path="$DATA/sources/osm.pbf" \
    --output="$STAGING/basemap.pmtiles" \
    --maxzoom="${TILES_MAXZOOM:-14}" \
    --bounds="$BOUNDS" \
    --tmpdir="$STAGING/tmp" \
    --threads="$(threads)" \
    --force \
    || die "Planetiler failed"
secs=$(( $(now_s) - t0 ))

info=$(java /scripts/PmtilesCheck.java "$STAGING/basemap.pmtiles" 14) || die "PMTiles validation failed; keeping the previous archive"
mv -f "$STAGING/basemap.pmtiles" "$FINAL"
rm -rf "$STAGING"
write_marker "$MARKER" "$FP" "seconds=$secs" "bytes=$(stat -c %s "$FINAL")" "header=$info"
log info "tiles built" artefact="tiles/basemap.pmtiles" bytes="$(stat -c %s "$FINAL")" seconds="$secs"

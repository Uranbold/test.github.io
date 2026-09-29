#!/usr/bin/env bash
# NAV-001 data-fetch (ADR-0002 §4, ADR-0003): resolve every input into data/sources/ and data/tools/.
#
#   fetch (default)  OSM PBF, Photon dump, Planetiler auxiliary files, pinned tool downloads
#   clean            delete built artefacts and the resolved OSM/Photon inputs, keep the
#                    auxiliary files and tools (used by `make rebuild-data`)
#
# Local file keys win: when OSM_PBF_FILE / PHOTON_DUMP_FILE is set, the file is copied and the URL
# is never requested (AC 4). Everything already present and matching the configured key is reused
# without any network request (AC 2).
SERVICE=data-fetch
# shellcheck source=lib.sh
source /scripts/lib.sh

SRC=$DATA/sources
TOOLS=$DATA/tools

clean() {
    rm -rf "$DATA/tiles" "$DATA/valhalla" "$DATA/photon" \
           "$DATA"/*.staging "$DATA"/*.old "$DATA/build-info.json" \
           "$SRC"/osm.pbf "$SRC"/osm.pbf.* "$SRC"/photon-dump "$SRC"/photon-dump.*
    log info "cleaned artefacts and resolved OSM/Photon inputs (auxiliary sources and tools kept)"
}

# resolve_input <label> <file-key-value> <mount> <url> <dest>
# Writes <dest>, <dest>.sha256 and finally <dest>.source (the completeness marker).
resolve_input() {
    local label=$1 file_key=$2 mount=$3 url=$4 dest=$5 key
    if [[ -n "$file_key" ]]; then
        [[ -f "$mount" && -s "$mount" ]] || die "local file key is set but the file is missing or empty" input="$label" file="$file_key"
        key="file:${file_key}|$(stat -c '%s|%Y' "$mount")"
    else
        [[ -n "$url" ]] || die "neither a URL nor a local file is configured" input="$label"
        key="url:${url}"
    fi
    if [[ -s "$dest" && -f "$dest.source" && "$(cat "$dest.source")" == "$key" ]]; then
        log info "reused" input="$label" source="${key%%|*}"
        return 0
    fi
    rm -f "$dest" "$dest".*
    if [[ -n "$file_key" ]]; then
        log info "using local file; no request is made to the URL" input="$label" file="$file_key"
        cp "$mount" "$dest.part"
        mv -f "$dest.part" "$dest"
    else
        fetch "$url" "$dest"
    fi
    sha256sum "$dest" | cut -d' ' -f1 > "$dest.sha256"
    echo "$key" > "$dest.source.tmp"
    mv -f "$dest.source.tmp" "$dest.source"
}

resolve_osm() {
    local dest=$SRC/osm.pbf
    resolve_input osm "${OSM_PBF_FILE:-}" /input/osm.pbf "${OSM_PBF_URL:-}" "$dest"
    if [[ ! -s "$dest.json" ]]; then
        if ! python3 /scripts/pbfinfo.py "$dest" > "$dest.json.tmp"; then
            rm -f "$dest" "$dest".*
            die "OSM source is not a readable PBF (HTML error page or truncated download?)" source="${OSM_PBF_FILE:-$OSM_PBF_URL}"
        fi
        mv -f "$dest.json.tmp" "$dest.json"
        python3 -c 'import json,sys; b=json.load(open(sys.argv[1]))["bbox"]; print(",".join(str(x) for x in b) if b and None not in b else "")' \
            "$dest.json" > "$dest.bounds"
    fi
    log info "osm source ready" bytes="$(stat -c %s "$dest")" bbox="$(cat "$dest.bounds")" \
        replication_timestamp="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["replication_timestamp"])' "$dest.json")"
}

resolve_photon_dump() {
    resolve_input photon_dump "${PHOTON_DUMP_FILE:-}" /input/photon-dump "${PHOTON_DUMP_URL:-}" "$SRC/photon-dump"
}

# Planetiler auxiliary sources: a file already in data/sources/ is used as-is (no request).
resolve_aux() {
    local entry file url
    for entry in \
        "natural_earth_vector.gpkg.zip|${NATURAL_EARTH_URL:-}" \
        "water-polygons-split-3857.zip|${WATER_POLYGONS_URL:-}" \
        "land-polygons-split-3857.zip|${LAND_POLYGONS_URL:-}" \
        "daylight-landcover.gpkg|${LANDCOVER_URL:-}" \
        "qrank.csv.gz|${QRANK_URL:-}" \
        "pgf-encoding.zip|${PGF_ENCODING_URL:-}"; do
        file=${entry%%|*}
        url=${entry#*|}
        if [[ -s "$SRC/$file" ]]; then
            log info "reused" input="$file"
        elif [[ -z "$url" ]]; then
            die "auxiliary source missing and no URL configured" input="$file"
        else
            fetch "$url" "$SRC/$file"
            echo "url:$url" > "$SRC/$file.source"
        fi
    done
}

# ensure_tool <file> <url> <algo> <sum>: download once, verify checksum, remember the verified sum.
ensure_tool() {
    local file=$1 url=$2 algo=$3 sum=$4 dest=$TOOLS/$1
    if [[ -s "$dest" && -f "$dest.verified" && "$(cat "$dest.verified")" == "${sum:-unverified}|$url" ]]; then
        log info "reused" tool="$file"
        return 0
    fi
    rm -f "$dest" "$dest.verified"
    fetch "$url" "$dest"
    verify_sum "$algo" "$sum" "$dest"
    echo "${sum:-unverified}|$url" > "$dest.verified"
}

fetch_all() {
    local t0
    t0=$(now_s)
    mkdir -p "$SRC" "$TOOLS"
    setup_curl_ca

    resolve_osm
    resolve_photon_dump

    ensure_tool photon.jar "${PHOTON_JAR_URL:-}" 256 "${PHOTON_JAR_SHA256:-}"
    if marker_matches "$DATA/photon/.complete" "$(photon_fingerprint)"; then
        log info "photon index up to date; zstd decoder not needed"
    else
        ensure_tool aircompressor.jar "${AIRCOMPRESSOR_URL:-}" 256 "${AIRCOMPRESSOR_SHA256:-}"
    fi

    if marker_matches "$DATA/tiles/.complete" "$(tiles_fingerprint)" && [[ -s "$DATA/tiles/basemap.pmtiles" ]]; then
        log info "tiles up to date; auxiliary sources and tile tooling not needed"
    else
        resolve_aux
        if [[ -s "$(protomaps_jar)" ]]; then
            log info "reused" tool="$(basename "$(protomaps_jar)")"
        else
            ensure_tool "protomaps-basemaps-${PROTOMAPS_COMMIT}.tar.gz" "${PROTOMAPS_SRC_URL:-}" 256 "${PROTOMAPS_SRC_SHA256:-}"
            ensure_tool maven-bin.tar.gz "${MAVEN_DIST_URL:-}" 512 "${MAVEN_DIST_SHA512:-}"
        fi
    fi
    log info "data-fetch finished" seconds="$(( $(now_s) - t0 ))"
}

case "${1:-fetch}" in
    fetch) fetch_all ;;
    clean) clean ;;
    *) die "unknown mode (use fetch or clean)" mode="$1" ;;
esac

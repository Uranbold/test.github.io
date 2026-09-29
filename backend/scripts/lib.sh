# shellcheck shell=bash
# Shared helpers for the NAV-001 one-shot builders (ADR-0002 §4). Sourced, not executed.
# Logs are one JSON object per line on stderr, with no coordinates or user data (only URLs,
# file names, sizes, durations).

set -Eeuo pipefail

SERVICE="${SERVICE:-$(basename "${0%.sh}")}"
DATA=/data

_json_escape() {
    local s=${1//\\/\\\\}
    s=${s//\"/\\\"}
    s=${s//$'\n'/ }
    printf '%s' "$s"
}

# log <level> <msg> [key=value ...]
log() {
    local level=$1 msg=$2 kv out
    shift 2
    out="{\"ts\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"service\":\"${SERVICE}\",\"level\":\"${level}\",\"msg\":\"$(_json_escape "$msg")\""
    for kv in "$@"; do
        out+=",\"$(_json_escape "${kv%%=*}")\":\"$(_json_escape "${kv#*=}")\""
    done
    printf '%s}\n' "$out" >&2
}

die() { log error "$@"; exit 1; }

trap 'log error "unexpected failure" line="$LINENO" command="$BASH_COMMAND"' ERR

now_s() { date +%s; }

# Threads: BUILD_THREADS or all CPUs.
threads() { if [[ -n "${BUILD_THREADS:-}" ]]; then echo "$BUILD_THREADS"; else nproc; fi; }

# ---------------------------------------------------------------- TLS / proxy for downloads
# EXTRA_CA_CERT (mounted at /run/extra-ca.pem, empty by default) is appended to the system bundle.
setup_curl_ca() {
    local sys=/etc/ssl/certs/ca-certificates.crt
    CURL_CA=/tmp/ca-bundle.pem
    cat "$sys" > "$CURL_CA" 2>/dev/null || : > "$CURL_CA"
    if [[ -s /run/extra-ca.pem ]]; then
        cat /run/extra-ca.pem >> "$CURL_CA"
        log info "extra CA certificates added for downloads"
    fi
    export CURL_CA_BUNDLE="$CURL_CA"
    if [[ -n "${BUILD_HTTPS_PROXY:-}" ]]; then
        export HTTPS_PROXY="$BUILD_HTTPS_PROXY" https_proxy="$BUILD_HTTPS_PROXY"
    fi
}

# Java (Maven): JDK cacerts + every cert of EXTRA_CA_CERT in one truststore; print JVM options.
java_net_opts() {
    local store=/tmp/cacerts.p12 opts n
    if [[ -s /run/extra-ca.pem ]]; then
        n=$(java /scripts/TrustStore.java "${JAVA_HOME}/lib/security/cacerts" /run/extra-ca.pem "$store")
        log info "extra CA certificates added to the Java truststore" count="$n"
        opts="-Djavax.net.ssl.trustStore=$store -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12"
    else
        opts=""
    fi
    if [[ -n "${BUILD_HTTPS_PROXY:-}" ]]; then
        local hp=${BUILD_HTTPS_PROXY#*://}
        hp=${hp%%/*}
        hp=${hp##*@}
        opts+=" -Dhttps.proxyHost=${hp%%:*} -Dhttps.proxyPort=${hp##*:} -Dhttp.proxyHost=${hp%%:*} -Dhttp.proxyPort=${hp##*:}"
    fi
    printf '%s' "$opts"
}

# ---------------------------------------------------------------- downloads
# fetch <url> <dest>: curl --fail into <dest>.part, then rename. Exits non-zero naming URL + HTTP status.
fetch() {
    local url=$1 dest=$2 code t0
    t0=$(now_s)
    log info "downloading" url="$url" file="${dest#"$DATA"/}"
    rm -f "$dest.part"
    local rc=0
    # (`|| rc=$?` keeps the ERR trap quiet; the failure is reported below with URL + status)
    code=$(curl -sS -L --fail --retry 3 --retry-delay 5 --connect-timeout 20 \
        -o "$dest.part" -w '%{http_code}' "$url" 2>/tmp/curl.err) || rc=$?
    if [[ $rc -ne 0 ]]; then
        rm -f "$dest.part"
        die "download failed" url="$url" http_status="${code:-000}" curl_exit="$rc" error="$(tail -c 300 /tmp/curl.err)"
    fi
    mv -f "$dest.part" "$dest"
    log info "downloaded" url="$url" file="${dest#"$DATA"/}" bytes="$(stat -c %s "$dest")" seconds="$(( $(now_s) - t0 ))"
}

# verify_sum <algo:256|512> <expected> <file>; deletes the file on mismatch. Empty expected = skip.
verify_sum() {
    local algo=$1 want=$2 file=$3 got
    [[ -n "$want" ]] || { log warn "checksum not configured, skipped" file="${file#"$DATA"/}"; return 0; }
    got=$("sha${algo}sum" "$file" | cut -d' ' -f1)
    if [[ "$got" != "$want" ]]; then
        rm -f "$file"
        die "checksum mismatch" file="${file#"$DATA"/}" expected="$want" actual="$got"
    fi
}

# ---------------------------------------------------------------- artefact markers
# A marker's first line is the fingerprint of the inputs; builders reuse the artefact only when
# the marker exists and the fingerprint matches. Other lines are key=value facts for build-info.
marker_matches() {
    local marker=$1 fp=$2
    [[ -f "$marker" ]] && [[ "$(head -n1 "$marker")" == "fingerprint=$fp" ]]
}

write_marker() {
    local marker=$1 fp=$2
    shift 2
    {
        echo "fingerprint=$fp"
        echo "built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        local kv
        for kv in "$@"; do echo "$kv"; done
    } > "$marker.tmp"
    mv -f "$marker.tmp" "$marker"
}

# Replace directory <final> by <staging> (both under /data, same filesystem).
swap_dir() {
    local staging=$1 final=$2
    rm -rf "$final.old"
    if [[ -e "$final" ]]; then mv "$final" "$final.old"; fi
    mv "$staging" "$final"
    rm -rf "$final.old"
}

require_file() {
    [[ -s "$1" ]] || die "required input missing (did data-fetch succeed?)" file="${1#"$DATA"/}"
}

# ---------------------------------------------------------------- input fingerprints
# Shared by data-fetch (to skip downloads that are not needed) and the builders (reuse check).
osm_sha()   { cat "$DATA/sources/osm.pbf.sha256"; }
tiles_bounds() {
    if [[ -n "${TILES_BOUNDS:-}" ]]; then echo "$TILES_BOUNDS"
    elif [[ -s "$DATA/sources/osm.pbf.bounds" ]]; then cat "$DATA/sources/osm.pbf.bounds"
    else echo ""; fi
}
tiles_fingerprint()    { echo "osm=$(osm_sha) protomaps=${PROTOMAPS_COMMIT} maxzoom=${TILES_MAXZOOM:-15} bounds=$(tiles_bounds)"; }
valhalla_fingerprint() { echo "osm=$(osm_sha) valhalla=${VALHALLA_VERSION}"; }
photon_fingerprint()   { echo "dump=$(cat "$DATA/sources/photon-dump.sha256") photon=${PHOTON_VERSION} languages=${PHOTON_LANGUAGES} countries=${PHOTON_COUNTRY_CODES:-all}"; }
protomaps_jar()        { echo "$DATA/tools/protomaps-basemap-${PROTOMAPS_COMMIT}.jar"; }

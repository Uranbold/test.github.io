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
        -o "$dest.part" -w '%{http_code}' -D /tmp/curl.headers "$url" 2>/tmp/curl.err) || rc=$?
    if [[ $rc -ne 0 ]]; then
        rm -f "$dest.part"
        die "download failed" url="$url" http_status="${code:-000}" curl_exit="$rc" error="$(tail -c 300 /tmp/curl.err)"
    fi
    mv -f "$dest.part" "$dest"
    # Last-Modified of the final response: the source date when the file itself carries none.
    tr -d '\r' < /tmp/curl.headers | awk 'tolower($1)=="last-modified:"{sub(/^[^:]*: */,""); lm=$0} END{if (lm) print lm}' \
        > "$dest.last-modified" || true
    [[ -s "$dest.last-modified" ]] || rm -f "$dest.last-modified"
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
tiles_fingerprint()    { echo "osm=$(osm_sha) protomaps=${PROTOMAPS_COMMIT} maxzoom=${TILES_MAXZOOM:-14} bounds=$(tiles_bounds)"; }
# The SEC-2 route limits below are deliberately NOT part of this fingerprint: a limit change never rebuilds the graph.
valhalla_fingerprint() { echo "osm=$(osm_sha) valhalla=${VALHALLA_VERSION}"; }
photon_fingerprint()   { echo "dump=$(cat "$DATA/sources/photon-dump.sha256") photon=${PHOTON_VERSION} languages=${PHOTON_LANGUAGES} countries=${PHOTON_COUNTRY_CODES:-all}"; }
protomaps_jar()        { echo "$DATA/tools/protomaps-basemap-${PROTOMAPS_COMMIT}.jar"; }

# ---------------------------------------------------------------- SEC-2 route request limits (ADR-0019 §1, §2)
# ONE source for the Valhalla service_limits and the processing timeout. Used by valhalla-build.sh (make_config
# flags, so a fresh valhalla.json describes what is served) and by valhalla-serve.sh, which overlays the same
# values onto the graph's valhalla.json at start. The serve-time overlay is authoritative, so data/ builds made
# before SEC-2 and every NAV-006 slot serve these limits without a graph rebuild.
# The values are the contract (openapi 0.7.0 "Request limits", D216/D217/D222), not .env keys.
# Entry = <service_limits section>:<max_distance metres>:<max_locations, "-" = the section has none>.
VALHALLA_LIMIT_MAX_LOCATIONS=10
VALHALLA_LIMIT_MAX_DISTANCE=3000000
VALHALLA_ROUTE_LIMITS="auto:3000000:10 bus:3000000:10 taxi:3000000:10 truck:3000000:10 auto_pedestrian:3000000:- \
bicycle:500000:10 motorcycle:500000:10 motor_scooter:500000:10 bikeshare:500000:10 multimodal:500000:10 \
transit:500000:10 pedestrian:250000:10"

# VALHALLA_TIMEOUT_SECONDS: integer 1-9, default 8 (below the gateway's 10 s read timeout). Anything else stops
# the caller with a log line (never a silent fallback). Prints the value.
valhalla_timeout_seconds() {
    local t=${VALHALLA_TIMEOUT_SECONDS:-8}
    [[ "$t" =~ ^[1-9]$ ]] || die "invalid VALHALLA_TIMEOUT_SECONDS (expected an integer 1-9, below the 10 s gateway timeout)" value="$t"
    printf '%s' "$t"
}

# valhalla_limit_args: valhalla_build_config flags for the table above plus the httpd timeout.
valhalla_limit_args() {
    local t e sec dist locs flag
    t=$(valhalla_timeout_seconds) || exit 1
    for e in $VALHALLA_ROUTE_LIMITS; do
        IFS=: read -r sec dist locs <<< "$e"
        flag=${sec//_/-}
        printf -- '--service-limits-%s-max-distance\n%s\n' "$flag" "$dist"
        [[ "$locs" == "-" ]] || printf -- '--service-limits-%s-max-locations\n%s\n' "$flag" "$locs"
    done
    printf -- '--httpd-service-timeout-seconds\n%s\n' "$t"
}

# valhalla_apply_limits <in.json> <out.json>: copy of <in> with the table, the backstop rule (every service_limits
# section: max_locations > 10 -> 10, max_distance > 3,000 km -> 3,000 km; nothing is raised) and
# httpd.service.timeout_seconds applied. Prints a one-line summary such as
# "auto=3000km/10 ... pedestrian=250km/10 timeout=8s" (no request or user data).
valhalla_apply_limits() {
    local in=$1 out=$2 t
    t=$(valhalla_timeout_seconds) || exit 1
    python3 - "$in" "$out" "$t" "$VALHALLA_ROUTE_LIMITS" "$VALHALLA_LIMIT_MAX_LOCATIONS" "$VALHALLA_LIMIT_MAX_DISTANCE" <<'PY'
import json, sys
src, dst, timeout, table, cap_locs, cap_dist = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], int(sys.argv[5]), float(sys.argv[6])
with open(src, encoding="utf-8") as f:
    cfg = json.load(f)
sl = cfg.setdefault("service_limits", {})
for entry in table.split():
    sec, dist, locs = entry.split(":")
    s = sl.setdefault(sec, {})
    s["max_distance"] = float(dist)
    if locs != "-":
        s["max_locations"] = int(locs)
for sec, s in sl.items():  # backstop: a section the table does not name can never be more permissive
    if not isinstance(s, dict):
        continue
    if isinstance(s.get("max_locations"), (int, float)) and s["max_locations"] > cap_locs:
        s["max_locations"] = cap_locs
    if isinstance(s.get("max_distance"), (int, float)) and s["max_distance"] > cap_dist:
        s["max_distance"] = cap_dist
cfg.setdefault("httpd", {}).setdefault("service", {})["timeout_seconds"] = timeout
with open(dst, "w", encoding="utf-8") as f:
    json.dump(cfg, f, indent=2)
def fmt(sec):
    s = sl[sec]
    return f"{sec}={int(s['max_distance']) // 1000}km/{s.get('max_locations', '-')}"
print(" ".join(fmt(x) for x in ("auto", "bus", "taxi", "truck", "motorcycle", "motor_scooter", "bicycle", "pedestrian"))
      + f" timeout={timeout}s")
PY
}

#!/usr/bin/env bash
# Security checklist B3 (docs/security/checklist.md): CVE scan of every pinned image with Trivy, itself pinned by
# digest and run in a container. Owner: backend-engineer.
#
#   make -C backend image-scan                 # scan every image; exit 1 on an unfixed-with-fix HIGH/CRITICAL finding
#   backend/scripts/image-scan.sh --list       # only print the image list (no Docker daemon needed)
#   IMAGE_SCAN_OUT=<dir> make image-scan       # also write one Trivy JSON report per image into <dir> (gate B record)
#   IMAGE_SCAN_SEVERITY=HIGH,CRITICAL          # severities that fail the scan (default)
#   IMAGE_SCAN_DOCKER_ARGS="--network host"    # extra `docker run` args for the scanner (proxy, network), never in git
#
# Images: the dev stack (compose.yaml), the slot runtime (compose.slots.yaml), the staging overlay (Caddy), the ops VM
# (Caddy + Uptime Kuma), the NAV-020 search-DB builder, the go-pmtiles CLI and the NAV-016 voice-pack base images:
# everything pinned as tag@sha256 in git. Locally built images (navmn-gate2, navmn-voice-pack) are not pulled from a
# registry and are listed as skipped. Trivy reads the images straight from the registries (--image-src remote), so the
# Docker socket is NOT mounted into the scanner; the daemon is only needed to run the scanner container. `--ignore-unfixed`
# keeps findings without a fixed version out of the exit code (they still appear in the table and the JSON report).
# Without a Docker daemon the scan is skipped with exit 0 and a message (CI machines without Docker); `--require`
# turns that into a failure (the gate B record must come from a real run).
set -Eeuo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
B=$(cd "$HERE/.." && pwd)
ROOT=$(cd "$B/.." && pwd)
ST=$ROOT/infra/staging

# Trivy, pinned by digest (ghcr.io/aquasecurity/trivy:0.69.1, multi-arch index digest, 2026-10-14). Re-pin with the
# monthly image re-pin (README.md "Image pins"): docker buildx imagetools inspect ghcr.io/aquasecurity/trivy:<tag>.
TRIVY_IMAGE=${TRIVY_IMAGE:-ghcr.io/aquasecurity/trivy:0.69.1@sha256:1c78ed1ef824ab8bb05b04359d186e4c1229d0b3e67005faacb54a7d71974f73}
SEVERITY=${IMAGE_SCAN_SEVERITY:-HIGH,CRITICAL}
OUT=${IMAGE_SCAN_OUT:-}
PIN='^[a-z0-9._/-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}$'

LIST_ONLY=0; REQUIRE=0
for a in "$@"; do
    case "$a" in
        --list) LIST_ONLY=1 ;;
        --require) REQUIRE=1 ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "image-scan.sh: unknown argument $a" >&2; exit 2 ;;
    esac
done

# every image the repository pins (docker compose config needs the CLI only, not a daemon)
images() {
    local tmp
    tmp=$(mktemp)
    printf 'NAV_COMPOSE_PROJECT=image-scan\nNAV_DATA_ROOT=/nonexistent/nav-data\n' > "$tmp"
    (cd "$B" && docker compose --env-file /dev/null config --images)
    (cd "$B" && docker compose -f compose.slots.yaml --env-file "$tmp" --profile '*' config --images)
    (cd "$B" && docker compose --project-directory "$B" -f compose.slots.yaml -f "$ST/compose.staging.yaml" \
        --env-file "$ST/.env.example" --profile '*' config --images)
    docker compose -f "$ST/monitoring/ops-vm/compose.yaml" --env-file "$ST/monitoring/ops-vm/.env.example" config --images
    sed -n 's/^[A-Z_]*_IMAGE=\(.*\)$/\1/p' "$B/.env.example" "$ST/.env.example" "$ST/monitoring/ops-vm/.env.example"
    grep -o 'PMTILES_IMAGE:-[^}]*' "$B/Makefile" | cut -d- -f2-
    sed -n 's/^ARG [A-Z_]*_IMAGE=\(.*\)$/\1/p' "$B/tools/voice-pack/Dockerfile" 2>/dev/null || true
    rm -f "$tmp"
}
mapfile -t ALL < <(images | sort -u)
PINNED=(); SKIPPED=()
for img in "${ALL[@]}"; do
    [[ -n "$img" ]] || continue
    if [[ "$img" =~ $PIN ]]; then PINNED+=("$img"); else SKIPPED+=("$img"); fi
done
echo "images pinned by digest: ${#PINNED[@]} (locally built, not scanned: ${SKIPPED[*]:-none})"
printf '  %s\n' "${PINNED[@]}"
[[ "$TRIVY_IMAGE" =~ $PIN ]] || { echo "FAIL: TRIVY_IMAGE is not pinned as tag@sha256: $TRIVY_IMAGE" >&2; exit 1; }
[[ $LIST_ONLY -eq 0 ]] || exit 0

if ! docker info >/dev/null 2>&1; then
    msg="no Docker daemon here: image scan skipped (run 'make -C backend image-scan' on a machine with Docker; gate B3 needs the real run)"
    if [[ $REQUIRE -eq 1 ]]; then echo "FAIL: $msg" >&2; exit 1; fi
    echo "SKIP: $msg"; exit 0
fi

[[ -z "$OUT" ]] || mkdir -p "$OUT"
# shellcheck disable=SC2206  # IMAGE_SCAN_DOCKER_ARGS is a space-separated list by design
EXTRA=(${IMAGE_SCAN_DOCKER_ARGS:-})
docker volume create navmn-trivy-cache >/dev/null
rc=0; failed=()
for img in "${PINNED[@]}"; do
    echo "== $img"
    name=$(printf '%s' "$img" | sed 's#[/:@]#_#g')
    args=(image --image-src remote --severity "$SEVERITY" --ignore-unfixed --exit-code 1 --no-progress --scanners vuln)
    if [[ -n "$OUT" ]]; then
        docker run --rm "${EXTRA[@]}" -v navmn-trivy-cache:/root/.cache/ -v "$OUT:/out" "$TRIVY_IMAGE" \
            "${args[@]}" --format json --output "/out/$name.json" "$img" || true
    fi
    docker run --rm "${EXTRA[@]}" -v navmn-trivy-cache:/root/.cache/ "$TRIVY_IMAGE" "${args[@]}" --format table "$img" \
        || { rc=1; failed+=("$img"); }
done
if [[ $rc -ne 0 ]]; then
    echo "FAIL: $SEVERITY findings with a fix in: ${failed[*]} (re-pin the image or record a PO exception in the gate B report)" >&2
    exit 1
fi
echo "OK: no $SEVERITY finding with a fix in ${#PINNED[@]} images${OUT:+ (JSON reports in $OUT)}"

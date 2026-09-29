#!/usr/bin/env bash
# Re-fetch the bundled map glyphs and sprites from protomaps/basemaps-assets (NAV-002, ADR-0004 §4).
#
# Usage: bash scripts/vendor-assets.sh [commit]
#   commit defaults to the pinned commit below. After an upgrade, update THIRD_PARTY_NOTICES.md
#   and run `npm test` (the label-rule and asset tests must pass).
#
# Writes (under web/public/):
#   fonts/<fontstack>/<start>-<end>.pbf   all 256 ranges for each fontstack in FONTSTACKS
#   fonts/OFL.txt                         SIL Open Font License 1.1 (full text)
#   sprites/v4/{light,dark}{,@2x}.{json,png}
#   SHA256SUMS                            checksums of every file above (verify: cd public && sha256sum -c SHA256SUMS)
set -euo pipefail

COMMIT="${1:-028c18f713baecad011301ff7a69acc39bcc2ae7}"
BASE="https://raw.githubusercontent.com/protomaps/basemaps-assets/${COMMIT}"
FONTSTACKS=("Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic")
SPRITES=(light dark)

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PUBLIC="${HERE}/../public"
mkdir -p "${PUBLIC}/fonts" "${PUBLIC}/sprites/v4"

LIST="$(mktemp)"
trap 'rm -f "${LIST}"' EXIT

for stack in "${FONTSTACKS[@]}"; do
  mkdir -p "${PUBLIC}/fonts/${stack}"
  enc="${stack// /%20}"
  for ((start = 0; start < 65536; start += 256)); do
    range="${start}-$((start + 255))"
    printf '%s\t%s\n' "${BASE}/fonts/${enc}/${range}.pbf" "${PUBLIC}/fonts/${stack}/${range}.pbf" >>"${LIST}"
  done
done
printf '%s\t%s\n' "${BASE}/fonts/OFL.txt" "${PUBLIC}/fonts/OFL.txt" >>"${LIST}"
for s in "${SPRITES[@]}"; do
  for suffix in ".json" ".png" "@2x.json" "@2x.png"; do
    printf '%s\t%s\n' "${BASE}/sprites/v4/${s}${suffix}" "${PUBLIC}/sprites/v4/${s}${suffix}" >>"${LIST}"
  done
done

echo "Fetching $(wc -l <"${LIST}") files from basemaps-assets@${COMMIT} ..."
# shellcheck disable=SC2016
tr '\t' '\n' <"${LIST}" | xargs -d '\n' -n 2 -P 16 sh -c 'curl -fsS --retry 5 --retry-all-errors -o "$2" "$1" || { echo "FAILED: $1" >&2; exit 255; }' _

(
  cd "${PUBLIC}"
  find fonts sprites -type f \( -name '*.pbf' -o -name '*.png' -o -name '*.json' -o -name 'OFL.txt' \) -print0 \
    | LC_ALL=C sort -z | xargs -0 sha256sum >SHA256SUMS
)
printf '%s\n' "${COMMIT}" >"${PUBLIC}/fonts/BASEMAPS_ASSETS_COMMIT"
echo "Done. $(wc -l <"${PUBLIC}/SHA256SUMS") files checksummed in public/SHA256SUMS."

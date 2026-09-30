#!/usr/bin/env bash
# NAV-008 AC 10 (also AC 15 and AC 20 exit condition): run the NAV-001 smoke suite against the staging BASE_URL
# from OUTSIDE the host (and, with --tethered, from a laptop tethered to a Mongolian mobile network). Owner: qa-engineer.
# Test plan: docs/qa/test-plans/NAV-008.md, cases SM-01 .. SM-04.
#
#   tests/staging/nav008/smoke-staging.sh [--base-url URL] [--tethered] [--contract] [--allow-http]
#
#   BASE_URL      default: BASE_URL or https://$STAGING_HOST (environment or staging.local.env)
#   --tethered    labels the run for the second AC 10 run (set OPERATOR and NET too); refuses if a proxy is set
#   --contract    also runs the NAV-001 contract test and the NAV-008 contract checks WITHOUT bursts
#                 (needs tests/.venv: python3 -m venv tests/.venv && tests/.venv/bin/pip install -r tests/api/requirements.txt)
#   --allow-http  dry runs against a local gateway only (staging must be https://)
#
# Output: the smoke log and JSON under tests/staging/nav008/results/ (git-ignored). Exit = smoke exit code
# (0 = every NAV-001 smoke check passed), or 1 if a --contract part failed.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
nav8_load_env
URL=""; TETHERED=0; CONTRACT=0; ALLOW_HTTP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --base-url) URL="$2"; shift 2 ;;
    --tethered) TETHERED=1; shift ;;
    --contract) CONTRACT=1; shift ;;
    --allow-http) ALLOW_HTTP=1; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
URL="${URL:-${BASE_URL:-${STAGING_HOST:+https://$STAGING_HOST}}}"
[ -n "$URL" ] || { echo "no BASE_URL: pass --base-url or set BASE_URL / STAGING_HOST (staging.local.env)" >&2; exit 2; }
case "$URL" in https://*) ;; *) [ "$ALLOW_HTTP" = 1 ] || { echo "staging BASE_URL must be https:// (AC 6/7). --allow-http is for local dry runs." >&2; exit 2; } ;; esac
if [ "$TETHERED" = 1 ]; then
  for v in HTTPS_PROXY https_proxy ALL_PROXY all_proxy; do
    [ -n "${!v:-}" ] && { echo "$v is set: a proxy hides the mobile network. Unset it for the tethered run." >&2; exit 2; }
  done
fi
mkdir -p "$NAV8_RESULTS"
label="$([ "$TETHERED" = 1 ] && echo "tethered-${OPERATOR:-unknown}-${NET:-unknown}" || echo outside)"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
log="$NAV8_RESULTS/smoke-$label-$stamp.log"; js="$NAV8_RESULTS/smoke-$label-$stamp.json"
echo "NAV-008 AC 10 smoke: base=$URL run=$label" | tee "$log"
hc="$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' "$URL/health" 2>&1)"
echo "SM-01 precheck /health -> $hc" | tee -a "$log"
BASE_URL="$URL" "$NAV8_REPO/tests/smoke/run.sh" --json-out "$js" 2>&1 | tee -a "$log"
rc=${PIPESTATUS[0]}
echo "SM-02 NAV-001 smoke exit $rc ($label). Log: $log" | tee -a "$log"
crc=0
if [ "$CONTRACT" = 1 ]; then
  py="$NAV8_REPO/tests/.venv/bin/python"; [ -x "$py" ] || py=python3
  "$py" "$NAV8_REPO/tests/api/nav001/contract.py" --base-url "$URL" 2>&1 | tee -a "$log"; c1=${PIPESTATUS[0]}
  "$py" "$NAV8_REPO/tests/api/nav008/contract_nav008.py" --base-url "$URL" --no-429 2>&1 | tee -a "$log"; c2=${PIPESTATUS[0]}
  echo "SM-03 contract exits: nav001=$c1 nav008(no burst)=$c2" | tee -a "$log"
  [ "$c1" = 0 ] && [ "$c2" = 0 ] || crc=1
fi
[ "$rc" = 0 ] && exit "$crc"
exit "$rc"

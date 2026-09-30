#!/usr/bin/env bash
# NAV-001 smoke suite (AC 40-42). Owner: qa-engineer. Test plan: docs/qa/test-plans/NAV-001.md
#
#   tests/smoke/run.sh                      # BASE_URL defaults to http://localhost:8080
#   BASE_URL=http://localhost:18080 tests/smoke/run.sh
#   tests/smoke/run.sh --json-out /tmp/smoke.json
#
# Runs AC 9, 13-16, 18, 21-23, 25, 28-30 and 32 through the gateway only, prints the AC 42
# informational values (AC 11 name:mn/name:en, AC 19 unpaved result, AC 24 Latin baseline),
# and exits non-zero naming each failed check with expected and actual values (AC 41).
# Needs only python3 (3.9+, stdlib). backend `make smoke` calls this script when it is executable.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
command -v python3 >/dev/null || { echo "FAIL  PRE.python3: python3 not found on PATH" >&2; exit 2; }
start=$(date +%s)
python3 "$here/../api/nav001/checks.py" --base-url "$BASE_URL" --group smoke "$@"
rc=$?
echo "smoke finished in $(( $(date +%s) - start )) s, exit $rc (AC 40 limit: 60 s)"
exit $rc

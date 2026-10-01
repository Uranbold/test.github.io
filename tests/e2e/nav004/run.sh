#!/usr/bin/env bash
# NAV-004 E2E runner. Waits (read-only) for the gateway /health = 200, then runs the suite.
# Never starts, stops or rebuilds the gateway. Extra args go to Playwright (e.g. a file or -g filter).
set -euo pipefail
cd "$(dirname "$0")/.."
export PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-/opt/pw-browsers}"
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/health || true)
  [ "$code" = "200" ] && break
  echo "[nav004] gateway /health = ${code:-none}; waiting 15 s ($i/60)"; sleep 15
done
[ "$code" = "200" ] || { echo "[nav004] gateway not healthy; tests NOT run"; exit 2; }
exec npx playwright test -c nav004/playwright.config.mjs "$@"

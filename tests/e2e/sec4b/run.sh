#!/usr/bin/env bash
# SEC-4B browser CSP checks (story AC 1–6, 9, 11–13, 15 re-check; AC 17, 18, 34). Builds B1, B2 and B3 into temporary
# directories and serves each with `vite preview` (playwright.config.mjs). No gateway and no Docker needed.
# Extra args go to Playwright (e.g. -g "B2").
set -euo pipefail
cd "$(dirname "$0")/.."
export PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-/opt/pw-browsers}"
[ -f ../../backend/data/tiles/basemap.pmtiles ] || { echo "[sec4b] backend/data/tiles/basemap.pmtiles missing (NAV-001 archive); tests NOT run"; exit 2; }
exec npx playwright test -c sec4b/playwright.config.mjs "$@"

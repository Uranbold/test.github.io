#!/usr/bin/env bash
# NAV-002 section M (AC 51–54, PO decision D44): static public demo checks against a LOCAL static server.
# Builds web/ with the documented static-demo mode, adds a basemap copy under /tiles/basemap.pmtiles and serves the site
# with Caddy (Docker, port 18088, container qa-nav002-static-demo). Does NOT need or touch the shared dev stack
# (http://localhost:8080). Extra args go to Playwright (e.g. -g AC53).
#   NAV002_SD_TILES=ub    (default) small UB extract (go-pmtiles extract of backend/data/tiles/basemap.pmtiles)
#   NAV002_SD_TILES=full  the whole NAV-001 archive (about 112 MiB), for the AC 52 pan across Mongolia
#   NAV002_SD_KEEP=1      keep the Caddy container after the run
set -euo pipefail
cd "$(dirname "$0")/.."
export PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-/opt/pw-browsers}"
exec npx playwright test -c nav002/static-demo.config.mjs "$@"

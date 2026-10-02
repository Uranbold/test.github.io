#!/usr/bin/env bash
# NAV-017 E2E runner (story AC 47). Builds web/ with the documented commands, serves the outputs from a local static web
# root (nav017/site.mjs, Caddy in Docker on 127.0.0.1:18097), then runs
#   1. Chromium on the host (project chromium-iphone), and
#   2. WebKit with the iPhone 13 descriptor inside mcr.microsoft.com/playwright:v1.56.1-noble (project webkit-iphone;
#      the host lacks WebKit's system libraries), sharing the same build and server (--network host).
# Never touches the shared dev stack (http://localhost:8080). Extra args go to Playwright (e.g. a file or -g filter).
#   NAV017_ENGINES=chromium|webkit|both (default both)   NAV017_SKIP_BUILD=1 reuse web/dist*   NAV017_KEEP=1 keep the site
set -uo pipefail
cd "$(dirname "$0")/.."
export PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-/opt/pw-browsers}"
ENGINES="${NAV017_ENGINES:-both}"
REPO="$(cd ../.. && pwd)"
IMAGE="mcr.microsoft.com/playwright:v1.56.1-noble"
node nav017/site.mjs start || exit 2
rc=0
if [ "$ENGINES" != "webkit" ]; then
  NAV017_CHROMIUM=1 NAV017_WEBKIT=0 npx playwright test -c nav017/playwright.config.mjs "$@" || rc=1
fi
if [ "$ENGINES" != "chromium" ]; then
  docker run --rm --network host --ipc=host -v "$REPO:$REPO" -w "$REPO/tests/e2e" \
    -e NAV017_CHROMIUM=0 -e NAV017_WEBKIT=1 -e NAV017_WORKERS="${NAV017_WORKERS:-2}" "$IMAGE" \
    npx playwright test -c nav017/playwright.config.mjs "$@" || rc=1
fi
[ "${NAV017_KEEP:-0}" = "1" ] || node nav017/site.mjs stop
exit $rc

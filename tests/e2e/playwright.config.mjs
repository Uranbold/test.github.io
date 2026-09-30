// Playwright config for browser E2E tests. Chromium only (preinstalled in the dev container).
// NAV-001: a page on origin http://localhost:5173 (the Vite dev origin the story names) calls the
// gateway cross-origin, so the browser itself enforces CORS (AC 29, 30, 31) and the real PMTiles JS
// client reads the archive through HTTP Range (AC 9, 30).
//
// No webServer, on purpose (fix 2026-09-30, port collision): the fixture origin is served by Playwright
// request interception in nav001/cors-browser.spec.mjs (context.route on http://localhost:5173 and
// http://127.0.0.1:5173), so NO process has to listen on port 5173. The previous config started
// `python3 -m http.server 5173` with reuseExistingServer=true: when the NAV-002 web dev server (Vite, also
// 5173) was running, Playwright silently "reused" it; when it was not, the python server grabbed 5173 and
// then blocked the NAV-002 suite's Vite server. With no webServer, NAV-001 E2E neither needs nor touches
// port 5173, and both suites can run in any order, or while `npm run dev` in web/ is up.
// NAV-002 has its own config: nav002/playwright.config.mjs (testMatch *.test.mjs, never picked up here).
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.spec\.mjs$/,
  timeout: 30_000,
  retries: 0,
  reporter: [['list']],
  use: { baseURL: 'http://localhost:5173', trace: 'off' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});

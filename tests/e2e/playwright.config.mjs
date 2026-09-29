// Playwright config for browser E2E tests. Chromium only (preinstalled in the dev container).
// NAV-001: a page served from http://localhost:5173 (the Vite dev origin the story names) calls the
// gateway cross-origin, so the browser itself enforces CORS (AC 29, 30, 31) and the real PMTiles JS
// client reads the archive through HTTP Range (AC 9, 30).
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.spec\.mjs$/,
  timeout: 30_000,
  retries: 0,
  reporter: [['list']],
  use: { baseURL: 'http://localhost:5173', trace: 'off' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    // Serves tests/e2e/ (page + node_modules/pmtiles) as the "web client" origin.
    command: 'python3 -m http.server 5173 --bind 127.0.0.1',
    url: 'http://localhost:5173/nav001/page.html',
    reuseExistingServer: true,
    timeout: 15_000,
  },
});

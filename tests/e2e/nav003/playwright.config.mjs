// NAV-003 search in the web demo: browser E2E tests (Playwright, Chromium from /opt/pw-browsers).
// Story: docs/requirements/stories/NAV-003-search-cyrillic-latin-autocomplete.md. Test plan: docs/qa/test-plans/NAV-003.md.
//
// Run from tests/e2e:  ./nav003/run.sh            (waits for gateway /health = 200, then runs everything)
//                or:  npx playwright test -c nav003/playwright.config.mjs
//
// The gateway (NAV-001, http://localhost:8080) is a SHARED read-only service (NAV-008 runs in parallel). These tests
// never start, stop or rebuild it. Failures, 429s, delays and fixture features F1–F13 are simulated in the browser by
// request interception (story "Test approach"). Live tests (golden set, AC 10, 12, 28, 29, 32) run serially and keep
// the whole run at <= 2 search/reverse requests per second (see helpers.mjs › pace()).
import { defineConfig, devices } from '@playwright/test';
import { fileURLToPath } from 'node:url';

const WEB = fileURLToPath(new URL('../../../web/', import.meta.url));

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.test\.mjs$/, // not *.spec.mjs, so the NAV-001 root config never picks these up
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: 0,
  workers: 1, // serial: timing ACs (3, 12, 13, 20, 25, 33-35) and the shared-gateway request budget
  reporter: [['list'], ['json', { outputFile: '../test-results/nav003-results.json' }]],
  outputDir: '../test-results/nav003',
  globalSetup: './global-setup.mjs',
  use: {
    baseURL: 'http://localhost:5173',
    viewport: { width: 1366, height: 768 }, // story reference viewport
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 768 } } }],
  webServer: [
    {
      command: 'npm run dev',
      cwd: WEB,
      url: 'http://localhost:5173/',
      reuseExistingServer: true,
      timeout: 60_000,
      stdout: 'ignore',
      stderr: 'pipe',
      env: { VITE_GATEWAY_BASE_URL: '' },
    },
  ],
});

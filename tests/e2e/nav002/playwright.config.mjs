// NAV-002 web demo map: browser E2E tests (Playwright, Chromium from /opt/pw-browsers).
// Story: docs/requirements/stories/NAV-002-web-demo-map.md. Test plan: docs/qa/test-plans/NAV-002.md.
//
// Run from tests/e2e:  ./nav002/run.sh            (waits for gateway /health = 200, then runs everything)
//                or:  npx playwright test -c nav002/playwright.config.mjs
//
// The gateway (NAV-001, http://localhost:8080) is a shared read-only service. These tests never start,
// stop or rebuild it. Failures are simulated in the browser only (story "Test approach").
//
// Servers (all Vite dev servers of web/, the story's reference environment):
//   5173  default config (no VITE_GATEWAY_BASE_URL)                  every test that needs the real map
//   5174  VITE_GATEWAY_BASE_URL=http://localhost:59999 (closed port)  AC 38
//   5175  VITE_GATEWAY_BASE_URL=http://localhost:8081/ (trailing /)  AC 3
//   5176  VITE_GATEWAY_BASE_URL=http://localhost:8081                AC 3
import { defineConfig, devices } from '@playwright/test';
import { fileURLToPath } from 'node:url';

const WEB = fileURLToPath(new URL('../../../web/', import.meta.url));
const ALT = fileURLToPath(new URL('./vite.alt.config.mjs', import.meta.url));

const altServer = (port, gateway) => ({
  command: `npx vite --config "${ALT}" --port ${port} --strictPort`,
  cwd: WEB,
  env: { VITE_GATEWAY_BASE_URL: gateway, NAV002_ALT_PORT: String(port) },
  url: `http://localhost:${port}/`,
  reuseExistingServer: false,
  timeout: 60_000,
  stdout: 'ignore',
  stderr: 'pipe',
});

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.test\.mjs$/, // not *.spec.mjs, so the NAV-001 root config never picks these up
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: 0,
  workers: 1, // timing ACs (5, 14, 17, 27, 31, 37-44) are measured on the reference environment; parallel SwiftShader browsers would skew them
  reporter: [['list'], ['json', { outputFile: '../test-results/nav002-results.json' }]],
  outputDir: '../test-results/nav002',
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
    altServer(5174, 'http://localhost:59999'),
    altServer(5175, 'http://localhost:8081/'),
    altServer(5176, 'http://localhost:8081'),
  ],
});

// NAV-004 route preview in the web demo: browser E2E tests (Playwright, Chromium from /opt/pw-browsers).
// Story: docs/requirements/stories/NAV-004-route-preview-web.md. Test plan: docs/qa/test-plans/NAV-004.md.
//
// Run from tests/e2e:  ./nav004/run.sh            (waits for gateway /health = 200, then runs everything)
//                or:  npx playwright test -c nav004/playwright.config.mjs
//
// The gateway (http://localhost:8080) is a SHARED service: never started, stopped or rebuilt here. Failures, 429s,
// delays, snap distances and the AC 27 fixture route are simulated in the browser by request interception (story
// "Test approach"). Live routing tests run serially and keep the whole run at <= 2 route requests per second
// (helpers.mjs › livePace).
//
// Own dev servers, on purpose: NAV-002/NAV-003 suites (and the parallel NAV-003 follow-up) start and stop their own
// Vite server on 5173 (reuseExistingServer). Sharing it made one suite's teardown kill the other's server. NAV-004 uses
//   5181  the app in dev mode (dev hooks window.__nav002/__nav003/__nav004), gateway http://localhost:8080;
//   5182  the PRODUCTION static public build (VITE_STATIC_DEMO=true, vite build + vite preview), for AC 53 only,
//         with the gateway URL pointed at http://localhost:8080 so the map works and any route request would be seen.
// The gateway allows any origin (CORS *), checked by a preflight from http://localhost:5177 on 2026-09-30.
import { defineConfig, devices } from '@playwright/test';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = fileURLToPath(new URL('../../../web/', import.meta.url));
const CFG = fileURLToPath(new URL('./vite.nav004.config.mjs', import.meta.url));
export const DEV_PORT = Number(process.env.NAV004_DEV_PORT ?? 5181);
export const STATIC_PORT = Number(process.env.NAV004_STATIC_PORT ?? 5182);
const STATIC_OUT = path.join(os.tmpdir(), 'navmn-nav004-static-build');

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.test\.mjs$/, // not *.spec.mjs, so the NAV-001 root config never picks these up
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: 0,
  workers: 1, // serial: timing ACs and the shared-gateway request budget (<= 2 route requests/s)
  reporter: [['list'], ['json', { outputFile: '../test-results/nav004-results.json' }]],
  outputDir: '../test-results/nav004',
  globalSetup: './global-setup.mjs',
  use: {
    baseURL: `http://localhost:${DEV_PORT}`,
    viewport: { width: 1366, height: 768 }, // story reference viewport
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 768 } } }],
  webServer: [
    {
      command: `npx vite --config "${CFG}" --port ${DEV_PORT} --strictPort`,
      cwd: WEB,
      url: `http://localhost:${DEV_PORT}/`,
      reuseExistingServer: true,
      timeout: 90_000,
      stdout: 'ignore',
      stderr: 'pipe',
      env: { VITE_GATEWAY_BASE_URL: 'http://localhost:8080', VITE_STATIC_DEMO: 'false', NAV004_INSTANCE: 'dev', VITE_CONFIG_NATIVE_IGNORE_WARNING: 'true' },
    },
    {
      command: `npx vite build --config "${CFG}" --mode static-demo --outDir "${STATIC_OUT}" --emptyOutDir --logLevel warn && npx vite preview --config "${CFG}" --outDir "${STATIC_OUT}" --port ${STATIC_PORT} --strictPort`,
      cwd: WEB,
      url: `http://localhost:${STATIC_PORT}/`,
      reuseExistingServer: true,
      timeout: 180_000,
      stdout: 'ignore',
      stderr: 'pipe',
      // Process env beats .env.static-demo in Vite: routing/search stay off (VITE_STATIC_DEMO=true), tiles come from
      // the dev gateway so the map, attribution and controls can be checked (AC 53).
      env: { VITE_GATEWAY_BASE_URL: 'http://localhost:8080', VITE_STATIC_DEMO: 'true', NAV004_INSTANCE: 'static', VITE_CONFIG_NATIVE_IGNORE_WARNING: 'true' },
    },
  ],
});

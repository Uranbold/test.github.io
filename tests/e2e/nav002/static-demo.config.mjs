// NAV-002 section M (AC 51–54, PO decision D44): the static public demo build, served by a local Range-capable static
// server (Caddy in Docker) that stands in for the PO's shared web hosting. Story:
// docs/requirements/stories/NAV-002-web-demo-map.md › M. Test plan: docs/qa/test-plans/NAV-002.md › M.
//
// Run from tests/e2e:  ./nav002/run-static-demo.sh    (or: npx playwright test -c nav002/static-demo.config.mjs)
//
// Separate from nav002/playwright.config.mjs on purpose: no gateway, no Vite dev server. Files end in .demo.mjs so the
// main NAV-002 config (*.test.mjs) never picks them up. The production build has no window.__nav002 test hook, so these
// tests use the DOM, the browser's network events and the static server's access log only.
//
// Hostnames: the build must work on ANY domain without a rebuild. Chromium maps *.demo-host.test to 127.0.0.1, so the
// same files are opened as http://a.demo-host.test:18088/ and http://b.demo-host.test:18088/. No real hostname is used
// or written anywhere (D35).
import { defineConfig, devices } from '@playwright/test';

export const SD_PORT = Number(process.env.NAV002_SD_PORT ?? 18088);

export default defineConfig({
  testDir: '.',
  testMatch: /static-demo\.demo\.mjs$/,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: 0,
  workers: 1,
  reporter: [['list'], ['json', { outputFile: '../test-results/nav002-static-demo-results.json' }]],
  outputDir: '../test-results/nav002-static-demo/out',
  globalSetup: './static-demo.setup.mjs',
  use: {
    viewport: { width: 1366, height: 768 }, // story reference viewport (AC 51)
    trace: 'retain-on-failure',
    launchOptions: { args: ['--host-resolver-rules=MAP *.demo-host.test 127.0.0.1'] },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 768 } } }],
});

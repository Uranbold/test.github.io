// NAV-017 web demo mode: browser E2E tests against the PRODUCTION demo-mode build served from sub-folders of a local
// static web root (nav017/site.mjs, Caddy in Docker on 127.0.0.1:18097). Story:
// docs/requirements/stories/NAV-017-web-demo-mode-replay.md. Test plan: docs/qa/test-plans/NAV-017.md.
//
// Run from tests/e2e:  ./nav017/run.sh                 (builds, starts the site, Chromium on the host, WebKit in Docker)
//                or:  node nav017/site.mjs start && npx playwright test -c nav017/playwright.config.mjs --project chromium-iphone
//
// Engines (story AC 47, D72):
//   chromium-iphone  Chromium from /opt/pw-browsers, 390×844 touch viewport (the container's only host browser)
//   webkit-iphone    Playwright WebKit with the iPhone 13 descriptor. The host lacks WebKit's system libraries, so
//                    run.sh runs this project inside the official image mcr.microsoft.com/playwright:v1.56.1-noble
//                    (same Playwright version, --network host). Enabled with NAV017_WEBKIT=1.
// No gateway, no Vite dev server: the shared dev stack (http://localhost:8080) is neither needed nor touched.
import { defineConfig, devices } from '@playwright/test';

const webkit = process.env.NAV017_WEBKIT === '1';
const chromium = process.env.NAV017_CHROMIUM !== '0';

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.test\.mjs$/, // never *.spec.mjs (root NAV-001 config)
  timeout: 15 * 60_000, // full replays at fake time (R2 = 932 s of replay)
  expect: { timeout: 10_000 },
  retries: 0,
  workers: Number(process.env.NAV017_WORKERS ?? 3),
  fullyParallel: false,
  reporter: [['list'], ['json', { outputFile: `../test-results/nav017-results-${webkit && !chromium ? 'webkit' : chromium && !webkit ? 'chromium' : 'all'}.json` }]],
  outputDir: '../test-results/nav017/out',
  globalSetup: './global-setup.mjs',
  use: { trace: 'retain-on-failure', serviceWorkers: 'allow', timezoneId: 'Asia/Ulaanbaatar', locale: 'mn-MN' },
  projects: [
    ...(chromium ? [{ name: 'chromium-iphone', use: { ...devices['Desktop Chrome'], viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, deviceScaleFactor: 1 } }] : []),
    ...(webkit ? [{ name: 'webkit-iphone', use: { ...devices['iPhone 13'] } }] : []),
  ],
});

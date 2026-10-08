// SEC-4B client security hardening: browser checks of the build-time Content-Security-Policy (story AC 17, AC 18) plus an
// independent re-check of the built HTML (AC 1–6, 9, 11–13, 15) and the attribution on every build (AC 34).
// Story: docs/requirements/stories/SEC-4B-client-security-hardening.md. Test plan: docs/qa/test-plans/SEC-4B.md.
//
// Run from tests/e2e:  ./sec4b/run.sh        (or: npx playwright test -c sec4b/playwright.config.mjs)
//
// Each build is produced by the real `vite build` (web/vite.config.ts through vite.sec4b.config.mjs) into a temporary
// directory and served by `vite preview`, which adds NO security response header, so only the meta policy applies
// (story AC 17). No gateway and no Docker are needed:
//   5191  B1 static demo (--mode static-demo, gateway = page origin). /tiles/basemap.pmtiles is a symlink to the NAV-001
//         archive in backend/data/tiles, served same-origin with Range by vite preview, as on the PO's host.
//   5192  B2 demo mode (--mode demo-mode, gateway = page origin), same tiles symlink.
//   5193  B3 normal build with VITE_GATEWAY_BASE_URL=http://localhost:8080 (the documented default); every gateway answer
//         (tiles, search, reverse, route) is mocked by Playwright request interception at that origin (story AC 17 B3).
// Playwright never bypasses CSP (bypassCSP stays false), and interception happens after the renderer's CSP check, so a
// blocked request is still reported as a violation.
import { defineConfig, devices } from '@playwright/test';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = fileURLToPath(new URL('../../../web/', import.meta.url));
const CFG = fileURLToPath(new URL('./vite.sec4b.config.mjs', import.meta.url));
const TILES = fileURLToPath(new URL('../../../backend/data/tiles/basemap.pmtiles', import.meta.url));

export const BUILDS = {
  B1: { mode: 'static-demo', port: Number(process.env.SEC4B_B1_PORT ?? 5191), out: path.join(os.tmpdir(), 'navmn-sec4b-b1'), gateway: 'same-origin', tiles: true, demo: false },
  B2: { mode: 'demo-mode', port: Number(process.env.SEC4B_B2_PORT ?? 5192), out: path.join(os.tmpdir(), 'navmn-sec4b-b2'), gateway: 'same-origin', tiles: true, demo: true },
  B3: { mode: 'production', port: Number(process.env.SEC4B_B3_PORT ?? 5193), out: path.join(os.tmpdir(), 'navmn-sec4b-b3'), gateway: 'http://localhost:8080', tiles: false, demo: false },
};
export const urlOf = (b) => `http://localhost:${BUILDS[b].port}/`;

const server = (name) => {
  const b = BUILDS[name];
  const tiles = b.tiles ? ` && mkdir -p "${b.out}/tiles" && ln -sfn "${TILES}" "${b.out}/tiles/basemap.pmtiles"` : '';
  return {
    command: `npx vite build --config "${CFG}" --mode ${b.mode} --outDir "${b.out}" --emptyOutDir --logLevel warn${tiles} && npx vite preview --config "${CFG}" --outDir "${b.out}" --port ${b.port} --strictPort`,
    cwd: WEB,
    url: `http://localhost:${b.port}/`,
    reuseExistingServer: false, // always a fresh build of the current web/ sources
    timeout: 300_000,
    stdout: 'ignore',
    stderr: 'pipe',
    env: {
      VITE_GATEWAY_BASE_URL: b.gateway,
      ...(name === 'B3' ? { VITE_STATIC_DEMO: 'false', VITE_DEMO_MODE: 'false' } : {}),
      SEC4B_INSTANCE: name.toLowerCase(),
      VITE_CONFIG_NATIVE_IGNORE_WARNING: 'true',
    },
  };
};

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.test\.mjs$/, // not *.spec.mjs, so the NAV-001 root config never picks these up
  timeout: 180_000,
  expect: { timeout: 15_000 },
  retries: 0,
  workers: 1,
  reporter: [['list'], ['json', { outputFile: '../test-results/sec4b-results.json' }]],
  outputDir: '../test-results/sec4b',
  use: { viewport: { width: 1366, height: 768 }, trace: 'retain-on-failure', bypassCSP: false, timezoneId: 'Asia/Ulaanbaatar' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 768 }, bypassCSP: false } }],
  webServer: [server('B1'), server('B2'), server('B3')],
});

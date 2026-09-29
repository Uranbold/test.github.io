// NAV-002 H. Attribution (AC 34, 35) across widths × modes × languages × states,
// and J. Network hygiene (AC 46).
import { test, expect } from '@playwright/test';
import { GATEWAY, P1, S, TILES_GLOB, attributionProblems, jump, openApp, tid, waitIdle } from './helpers.mjs';

const VIEWPORTS = [[320, 568], [360, 640], [768, 1024], [1366, 768], [1920, 1080]];

for (const [w, h] of VIEWPORTS) {
  for (const theme of ['day', 'night']) {
    for (const lang of ['mn', 'en']) {
      test(`AC34/AC35 ${w}×${h} ${theme} ${lang}: OSM credit (and ESA at zoom ≤ 7) visible, uncovered, ≥ 11 px in every state`, async ({ browser }) => {
        test.setTimeout(120_000);
        const ctx = await browser.newContext({ viewport: { width: w, height: h } });
        const problems = [];
        const check = async (page, state, withEsa = false) => {
          for (const p of await attributionProblems(page)) problems.push(`${state}: ${p}`);
          if (withEsa) for (const p of await attributionProblems(page, 'attribution-esa', S.esa)) problems.push(`${state}: ${p}`);
        };
        try {
          // loading → map shown
          const page = await ctx.newPage();
          await page.route(TILES_GLOB, async (r) => {
            await new Promise((res) => setTimeout(res, 1200));
            await r.continue().catch(() => {});
          });
          await openApp(page, { theme, lang, wait: false });
          await expect(tid(page, 'loading')).toBeVisible({ timeout: 5000 });
          await check(page, 'loading');
          await expect(tid(page, 'zoom-in')).toBeVisible({ timeout: 30_000 });
          await page.unroute(TILES_GLOB);
          await waitIdle(page);
          await check(page, 'map shown z12');
          // AC 35: zoom ≤ 7 → ESA credit too
          for (const z of [7, 5, 3]) {
            await jump(page, { center: P1, zoom: z });
            await expect(tid(page, 'attribution-esa')).toBeVisible();
            await check(page, `map shown z${z}`, true);
          }
          await jump(page, { center: P1, zoom: 12 });
          // location message open (permission denied)
          await tid(page, 'my-location').click();
          await expect(tid(page, 'location-message')).toBeVisible();
          await check(page, 'location message');
          // offline (with the location message still open)
          await ctx.setOffline(true);
          await expect(tid(page, 'status-banner')).toHaveAttribute('data-reason', 'offline');
          await check(page, 'offline + location message');
          // worst case: zoom < 8 (ESA lines) + banner + message
          await page.evaluate(() => window.__nav002.map.jumpTo({ zoom: 6 }));
          await page.waitForTimeout(300);
          await check(page, 'offline + message + ESA', true);
          await ctx.setOffline(false);
          await page.close();
          // tiles unavailable (blocking card)
          const p2 = await ctx.newPage();
          await p2.route(TILES_GLOB, (r) => r.abort());
          await openApp(p2, { theme, lang, wait: false });
          await expect(tid(p2, 'blocking-card')).toBeVisible({ timeout: 11_000 });
          await check(p2, 'tiles unavailable card');
          await p2.close();
        } finally {
          await ctx.close();
        }
        expect(problems).toEqual([]);
      });
    }
  }
}

test('AC34 attribution link opens https://www.openstreetmap.org/copyright in a new tab', async ({ page, context }) => {
  await openApp(page);
  await context.route('https://www.openstreetmap.org/**', (r) => r.fulfill({ status: 200, contentType: 'text/html', body: '<title>copyright</title>' }));
  const [popup] = await Promise.all([context.waitForEvent('page'), tid(page, 'attribution-osm').click()]);
  expect(popup.url()).toBe(S.osmHref);
  expect(page.url()).toContain('localhost:5173'); // original tab unchanged
  await expect(tid(page, 'attribution-osm')).toHaveAttribute('rel', /noopener/);
});

test('AC46 full session: every request goes to the page origin or the gateway; zero other hosts', async ({ page, context }) => {
  test.setTimeout(240_000);
  const reqs = [];
  const glyph404 = [];
  context.on('request', (r) => reqs.push(r.url()));
  context.on('response', (r) => {
    if (r.url().includes('/fonts/') && r.status() >= 400) glyph404.push(`${r.status()} ${decodeURIComponent(r.url())}`);
  });
  page.on('websocket', (ws) => reqs.push(ws.url()));
  await context.grantPermissions(['geolocation']);
  await context.setGeolocation({ latitude: P1.lat, longitude: P1.lng, accuracy: 40 });
  await openApp(page);
  // Pan across Mongolia (west to east, north to south) at mid zoom
  for (const c of [[91.64, 48.0], [96.0, 49.9], [100.2, 49.6], [102.8, 46.3], [104.4, 43.6], [106.9, 47.9], [110.0, 46.0], [114.5, 47.3], [118.0, 47.5]]) {
    await jump(page, { center: { lng: c[0], lat: c[1] }, zoom: 9 });
  }
  // Zoom 3 → 19 at P1
  for (let z = 3; z <= 19; z += 2) await jump(page, { center: P1, zoom: z });
  await jump(page, { center: P1, zoom: 19 });
  // Both modes, both languages
  await tid(page, 'theme-toggle').click();
  await waitIdle(page);
  await tid(page, 'language-toggle').click();
  await tid(page, 'theme-toggle').click();
  await waitIdle(page);
  await tid(page, 'language-toggle').click();
  // My location
  await tid(page, 'my-location').click();
  await expect(tid(page, 'my-location')).toHaveAttribute('data-state', 'following', { timeout: 3000 });
  await waitIdle(page);
  // Labels in all three fonts at a few zooms (lakes: Italic; country/city: Medium)
  await jump(page, { center: { lng: 100.4, lat: 51.0 }, zoom: 8 }); // Khövsgöl lake
  await jump(page, { center: P1, zoom: 5 });
  const allowed = new Set(['http://localhost:5173', 'ws://localhost:5173', GATEWAY]);
  const hosts = new Map();
  for (const u of reqs) {
    if (/^(data|blob|about):/.test(u)) continue;
    const o = new URL(u).origin;
    hosts.set(o, (hosts.get(o) ?? 0) + 1);
  }
  test.info().annotations.push({ type: 'AC46 request origins', description: JSON.stringify(Object.fromEntries(hosts)) });
  test.info().annotations.push({ type: 'AC46 glyph ranges not found (info)', description: glyph404.length ? [...new Set(glyph404)].join('; ') : 'none' });
  expect([...hosts.keys()].filter((o) => !allowed.has(o))).toEqual([]);
  expect(reqs.length).toBeGreaterThan(100);
  expect(hosts.get(GATEWAY)).toBeGreaterThan(10);
});

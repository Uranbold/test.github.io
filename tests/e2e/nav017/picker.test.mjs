// NAV-017 A/B: sub-folder hosting (AC 2), the local Basic-auth stand-in for hPanel protection (ADR-0011 §9), and the
// route picker (AC 8–11), plus the double-tap edge case. Production demo-mode build from nav017/site.mjs.
import { test, expect } from '@playwright/test';
import { S, ROUTES, demoUrl, netLog, openDemo, qaInit, selectRoute, site, tick, tid, fmtDistance, fmtDuration, nb, readJson, waitPicker } from './helpers.mjs';

const ORIGIN = () => site().origin;
const offOrigin = (reqs) => reqs.filter((r) => !r.url.startsWith(ORIGIN()) && !/^(data|blob):/.test(r.url)).map((r) => r.url);
const backend = (reqs) => reqs.filter((r) => /\/v1\/(search|reverse|route)/.test(r.url)).map((r) => r.url);

test.describe('A. Sub-folder hosting and protection', () => {
  for (const folder of ['a', 'b']) {
    test(`AC2: the same build files load from ${folder === 'a' ? '/demo-a/' : '/x/y/demo-b/ (two levels deep)'} without a rebuild: page, scripts, styles, fonts, sprites, WASM and route data from that folder; opening view drawn with tiles`, async ({ page }) => {
      const reqs = [];
      page.on('requestfinished', async (r) => { const res = await r.response(); reqs.push({ url: r.url(), status: res?.status() }); });
      const failed = [];
      page.on('requestfailed', (r) => failed.push(r.url()));
      await page.addInitScript(qaInit, { voices: [] });
      await page.goto(demoUrl(folder));
      await expect(tid(page, 'demo-picker')).toBeVisible({ timeout: 20_000 });
      await selectRoute(page, 'R1');
      await page.waitForTimeout(500);
      const base = site().folders[folder];
      const own = reqs.filter((r) => !r.url.includes('/tiles/basemap.pmtiles'));
      expect(failed).toEqual([]);
      expect(own.filter((r) => r.status >= 400)).toEqual([]);
      for (const r of own) expect(new URL(r.url).pathname.startsWith(base), r.url).toBe(true);
      const kinds = (re) => own.filter((r) => re.test(new URL(r.url).pathname)).length;
      expect(kinds(/\.js$/)).toBeGreaterThan(0);
      expect(kinds(/\.css$/)).toBeGreaterThan(0);
      expect(kinds(/\/fonts\/.+\.pbf$/)).toBeGreaterThan(0);
      expect(kinds(/\/sprites\/v4\//)).toBeGreaterThan(0);
      expect(kinds(/\.wasm$/)).toBe(1);
      expect(kinds(/\/demo-routes\/r1\.json$/)).toBe(1);
      // tiles from the public archive at the origin root (ADR-0011 §4), answered 206
      const tiles = reqs.filter((r) => new URL(r.url).pathname === '/tiles/basemap.pmtiles');
      expect(tiles.length).toBeGreaterThan(0);
      expect(tiles.every((r) => r.status === 206)).toBe(true);
      // tiles are drawn: the canvas is not a flat colour
      const png = await page.locator('canvas.maplibregl-canvas').screenshot();
      expect(png.length).toBeGreaterThan(20_000);
    });
  }

  test('AC2 opening view equals the public site\'s (P1, zoom 12, D13): same scale bar label and width as the public static build at the web root', async ({ page, browser }) => {
    await page.goto(ORIGIN() + '/');
    await expect(tid(page, 'scale-label')).toBeVisible({ timeout: 20_000 });
    await page.waitForTimeout(1500);
    const pub = await page.evaluate(() => ({ label: document.querySelector('[data-testid=scale-label]').textContent, w: document.querySelector('[data-testid=scale-bar]').getBoundingClientRect().width }));
    const p2 = await page.context().newPage();
    await p2.goto(demoUrl('a'));
    await expect(tid(p2, 'demo-picker')).toBeVisible({ timeout: 20_000 });
    await p2.waitForTimeout(1500);
    const demo = await p2.evaluate(() => ({ label: document.querySelector('[data-testid=scale-label]').textContent, w: document.querySelector('[data-testid=scale-bar]').getBoundingClientRect().width }));
    expect(demo.label).toBe(pub.label);
    expect(Math.abs(demo.w - pub.w)).toBeLessThanOrEqual(2);
  });

  test('ADR-0011 §9 Basic auth stand-in: /locked-demo/ answers 401 without credentials and 200 with them; every asset, the WASM and the route data load with credentials; tiles come from the unprotected /tiles/ (206)', async ({ browser, request }, ti) => {
    const s = site();
    const url = s.origin + s.folders.locked;
    expect((await request.get(url, { failOnStatusCode: false })).status()).toBe(401);
    expect((await request.get(url + 'demo-routes/r1.json', { failOnStatusCode: false })).status()).toBe(401);
    const auth = 'Basic ' + Buffer.from(`${s.user}:${s.pass}`).toString('base64');
    expect((await request.get(url, { headers: { Authorization: auth } })).status()).toBe(200);
    const { defaultBrowserType, ...use } = ti.project.use;
    const ctx = await browser.newContext({ ...use, httpCredentials: { username: s.user, password: s.pass } });
    const page = await ctx.newPage();
    const res = [];
    page.on('requestfinished', async (r) => res.push({ url: r.url(), status: (await r.response())?.status() }));
    const failed = [];
    page.on('requestfailed', (r) => failed.push(`${r.url()} ${r.failure()?.errorText}`));
    await page.addInitScript(qaInit, { voices: [] });
    await page.goto(url);
    await expect(tid(page, 'demo-picker')).toBeVisible({ timeout: 20_000 });
    await selectRoute(page, 'R3');
    await page.waitForTimeout(500);
    expect(failed).toEqual([]);
    expect(res.filter((r) => r.status >= 400)).toEqual([]);
    expect(res.some((r) => r.url.endsWith('.wasm') && r.status === 200)).toBe(true);
    expect(res.some((r) => r.url.endsWith('/demo-routes/r3.json') && r.status === 200)).toBe(true);
    expect(res.some((r) => new URL(r.url).pathname === '/tiles/basemap.pmtiles' && r.status === 206)).toBe(true);
    await ctx.close();
  });
});

test.describe('B. Route picker', () => {
  test('AC8: picker over the map ≤ 1 s after the opening tiles: heading «Туршилтын горим», list «Маршрут сонгох», exactly R1, R2, R3 with manifest names, mode, distance and duration (NAV-004 AC 23/24); attribution visible', async ({ page }) => {
    const tileDone = [];
    page.on('requestfinished', (r) => { if (r.url().includes('/tiles/basemap.pmtiles')) tileDone.push(Date.now()); });
    await page.addInitScript(qaInit, { voices: [] });
    const t0 = Date.now();
    await page.goto(demoUrl('a'));
    await expect(tid(page, 'demo-picker')).toBeVisible({ timeout: 20_000 });
    const tPicker = Date.now();
    const lastTileBefore = Math.max(...tileDone.filter((t) => t <= tPicker), t0);
    // "≤ 1 s after the first map idle": idle is not observable in the production build (no map handle). Proxy: time from
    // the last opening tile response to the picker. Software rendering on a shared 4-core container makes this an
    // environment-bound number: recorded as an annotation, asserted only against a gross 5 s limit (QA report: PARTIAL).
    const ms = tPicker - lastTileBefore;
    test.info().annotations.push({ type: 'AC8 picker after last opening tile (ms)', description: String(ms) });
    expect(ms, 'picker after the last opening tile (ms)').toBeLessThanOrEqual(5000);
    await expect(tid(page, 'demo-heading')).toHaveText(S.mn.mode);
    const group = page.getByRole('radiogroup', { name: S.mn.choose });
    await expect(group).toBeVisible();
    const rows = group.getByRole('radio');
    await expect(rows).toHaveCount(3);
    const manifest = JSON.parse((await import('node:fs')).readFileSync(new URL('../../../web/src/demo/routes.manifest.json', import.meta.url), 'utf8'));
    for (const [i, label] of ['R1', 'R2', 'R3'].entries()) {
      const row = rows.nth(i);
      await expect(row).toHaveAttribute('data-route', label);
      const r = readJson(ROUTES[label].route).routes[0];
      const m = manifest.routes.find((e) => e.label === label);
      const txt = nb(await row.innerText());
      expect(txt).toContain(m.origin.name);
      expect(txt).toContain(m.destination.name);
      const meta = `${ROUTES[label].mode === 'walk' ? S.mn.walk : S.mn.car} · ${fmtDistance(r.distance, 'mn')} · ${fmtDuration(r.duration, 'mn')}`;
      expect(txt).toContain(meta);
    }
    await expect(page.getByText(S.mn.attribution)).toBeVisible();
  });

  for (const [w, h] of [[375, 667], [390, 844], [1366, 768]]) {
    test(`AC9 ${w}×${h}: selecting an entry draws its line and markers ≤ 1 s, fits the route ends into the map area not covered by the picker, enables «Эхлэх»; another entry replaces the route; 0 requests off the page origin`, async ({ page }) => {
      await page.setViewportSize({ width: w, height: h });
      const reqs = netLog(page);
      await openDemo(page, { voices: [] });
      const ends = async () => page.evaluate(() => {
        const r = (s) => document.querySelector(s)?.getBoundingClientRect();
        const sheet = r('[data-testid=demo-picker]');
        const wide = innerWidth >= 840;
        const o = r('[data-testid=demo-origin-marker]'), d = r('[data-testid=demo-destination-pin]');
        // anchor points: origin marker centre, destination pin tip (anchor bottom)
        const pts = { origin: [(o.left + o.right) / 2, (o.top + o.bottom) / 2], destination: [(d.left + d.right) / 2, d.bottom] };
        const free = wide ? { left: sheet.right, top: 0, right: innerWidth, bottom: innerHeight } : { left: 0, top: 0, right: innerWidth, bottom: sheet.top };
        const out = [];
        for (const [k, [x, y]] of Object.entries(pts)) if (!(x >= free.left && x <= free.right && y >= free.top && y <= free.bottom)) out.push(`${k} at (${x.toFixed(0)}, ${y.toFixed(0)}) is under the picker (free area ${JSON.stringify(free)})`);
        return { out, pos: pts };
      });
      const problems = [];
      let prev = null;
      for (const R of ['R1', 'R2', 'R3']) {
        await page.locator(`[data-route="${R}"]`).click();
        for (let i = 0; i < 10; i++) await tick(page, 100);
        await expect(tid(page, 'demo-start')).toHaveAttribute('aria-disabled', 'false');
        await expect(page.locator(`[data-route="${R}"]`)).toHaveAttribute('aria-checked', 'true');
        await expect(tid(page, 'demo-origin-marker')).toHaveCount(1);
        await expect(tid(page, 'demo-destination-pin')).toHaveCount(1);
        const e = await ends();
        for (const x of e.out) problems.push(`${R}: ${x}`);
        if (prev) expect(JSON.stringify(e.pos), 'markers moved to the new route').not.toBe(prev);
        prev = JSON.stringify(e.pos);
        await test.info().attach(`ac9-${w}x${h}-${R}.png`, { body: await page.screenshot(), contentType: 'image/png' });
      }
      expect(offOrigin(reqs)).toEqual([]);
      expect(backend(reqs)).toEqual([]);
      expect(problems).toEqual([]);
    });
  }

  for (const [kind, reply] of [['404', { status: 404, body: 'Not found' }], ['500', { status: 500, body: 'error' }], ['HTML body', { status: 200, contentType: 'text/html', body: '<!doctype html><title>x</title>' }]]) {
    test(`AC10: route data answered ${kind} → «Алдаа гарлаа» + «Дахин оролдох» ≤ 1 s, «Эхлэх» stays disabled, other entries work, retry loads again once per press`, async ({ page }) => {
      let n = 0;
      let fail = true;
      await page.route('**/demo-routes/r2.json', async (r) => { n++; if (fail) await r.fulfill(reply); else await r.continue(); });
      await openDemo(page, { voices: [] });
      await page.locator('[data-route="R2"]').click();
      for (let i = 0; i < 10; i++) await tick(page, 100);
      await expect(tid(page, 'demo-state')).toHaveAttribute('data-state', 'error');
      await expect(tid(page, 'demo-state')).toContainText(S.mn.error);
      await expect(tid(page, 'demo-retry')).toHaveText(S.mn.retry);
      await expect(tid(page, 'demo-start')).toHaveAttribute('aria-disabled', 'true');
      expect(n).toBe(1);
      await tid(page, 'demo-retry').click();
      for (let i = 0; i < 5; i++) await tick(page, 100);
      expect(n).toBe(2);
      await expect(tid(page, 'demo-state')).toHaveAttribute('data-state', 'error');
      // other entries keep working
      await selectRoute(page, 'R1');
      await expect(tid(page, 'demo-state')).toBeHidden();
      // back to R2, now healthy: retry press loads once and enables «Эхлэх»
      fail = false;
      await page.locator('[data-route="R2"]').click();
      for (let i = 0; i < 10; i++) await tick(page, 100);
      await expect(tid(page, 'demo-start')).toHaveAttribute('aria-disabled', 'false');
      expect(n).toBe(3);
    });
  }

  test('AC10: «Ачаалж байна…» shows when loading takes more than 300 ms (not before), and goes away when the data arrive', async ({ page }) => {
    let release;
    const gate = new Promise((r) => (release = r));
    await page.route('**/demo-routes/r3.json', async (r) => { await gate; await r.continue(); });
    await openDemo(page, { voices: [] });
    await page.locator('[data-route="R3"]').click();
    await tick(page, 250);
    await expect(tid(page, 'demo-state')).toBeHidden();
    await tick(page, 100);
    await expect(tid(page, 'demo-state')).toHaveAttribute('data-state', 'loading');
    await expect(tid(page, 'demo-state')).toContainText(S.mn.loading);
    release();
    for (let i = 0; i < 100; i++) { await tick(page, 100); await page.waitForTimeout(30); if (await tid(page, 'demo-state').isHidden()) break; }
    await expect(tid(page, 'demo-state')).toBeHidden();
    await expect(tid(page, 'demo-start')).toHaveAttribute('aria-disabled', 'false');
  });

  test('AC11: language, theme, zoom, compass and attribution work in the picker; search, «Маршрут гаргах», the long-press/right-click card and my-location are not offered; 0 search/reverse/route requests', async ({ page }) => {
    const reqs = netLog(page);
    await openDemo(page, { voices: [], clock: false });
    for (const id of ['search-field', 'route-open', 'my-location']) await expect(tid(page, id)).toBeHidden();
    // language
    await tid(page, 'language-toggle').click();
    await expect(tid(page, 'demo-heading')).toHaveText(S.en.mode);
    await expect(page.getByRole('radiogroup', { name: S.en.choose })).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await tid(page, 'language-toggle').click();
    await expect(tid(page, 'demo-heading')).toHaveText(S.mn.mode);
    // theme
    await tid(page, 'theme-toggle').click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'night');
    await tid(page, 'theme-toggle').click();
    await expect(page.locator('html')).not.toHaveAttribute('data-theme', 'night');
    // zoom changes the scale bar
    const before = await tid(page, 'scale-label').textContent();
    await tid(page, 'zoom-in').click();
    await tid(page, 'zoom-in').click();
    await expect.poll(async () => tid(page, 'scale-label').textContent(), { timeout: 5000 }).not.toBe(before);
    await expect(tid(page, 'compass')).toBeVisible();
    // right-click / long-press on the map: no place or coordinate card
    const box = await page.locator('canvas.maplibregl-canvas').boundingBox();
    await page.mouse.click(box.x + box.width / 2, box.y + 120, { button: 'right' });
    await page.waitForTimeout(800);
    await expect(tid(page, 'place-card')).toBeHidden();
    await expect(page.getByText(S.mn.attribution)).toBeVisible();
    expect(backend(reqs)).toEqual([]);
    expect(offOrigin(reqs)).toEqual([]);
  });

  test('Edge case: tapping «Эхлэх» twice quickly starts one replay only (one depart prompt, one wake lock)', async ({ page }) => {
    await openDemo(page, { voices: ['mn-MN'] });
    await selectRoute(page, 'R1');
    await tid(page, 'demo-start').dblclick();
    await tick(page, 2000);
    const log = await page.evaluate(() => window.__qa);
    expect(log.speak.filter((s) => s.text).length).toBe(1);
    expect(log.wake.length).toBe(1);
    await expect(tid(page, 'demo-nav')).toBeVisible();
  });
});

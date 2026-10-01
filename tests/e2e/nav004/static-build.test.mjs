// NAV-004 K. Static public build: AC 53 (production build with VITE_STATIC_DEMO=true, served by `vite preview` on the
// NAV-004 static port; see playwright.config.mjs) and AC 54 (the web unit test exists and passes).
// Production build: no window.__nav002/__nav004 hooks, so these tests use the DOM and the browser's network events.
import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { REF, STATIC_APP, T, WEB, attributionProblems, rightClick, tid } from './helpers.mjs';

async function openStatic(page) {
  const net = [];
  page.context().on('request', (r) => net.push({ url: r.url(), method: r.method() }));
  await page.addInitScript(() => {
    window.__rst = [];
    let last = '';
    new MutationObserver(() => {
      const p = document.querySelector('[data-testid=route-panel]');
      const row = document.querySelector('[data-testid=route-state]');
      const st = JSON.stringify([p && !p.closest('[hidden]') ? p.dataset.state : null, row?.dataset.state ?? null, row?.textContent ?? null]);
      if (st !== last) {
        last = st;
        window.__rst.push({ t: performance.now(), st: JSON.parse(st) });
      }
    }).observe(document, { subtree: true, childList: true, characterData: true, attributes: true });
    window.__act = [];
    window.__rows = []; // each time a new route-state row element appears
    let lastRow = null;
    new MutationObserver(() => {
      const row = document.querySelector('[data-testid=route-state]');
      if (row && row !== lastRow) window.__rows.push({ t: performance.now() });
      lastRow = row;
    }).observe(document, { subtree: true, childList: true });
    document.addEventListener('click', (e) => window.__act.push({ t: performance.now(), id: e.target.closest?.('[data-testid]')?.dataset.testid }), true);
  });
  await page.goto(STATIC_APP);
  await expect(tid(page, 'zoom-in')).toBeVisible({ timeout: 20_000 });
  await page.waitForTimeout(2500); // first render (no idle hook in production)
  return net;
}

/**
 * After the last click on `id`: every panel/row state recorded since then is the static unavailable row (never loading,
 * never another state) and the row is shown at +1 s. Returns { replaced: ms until the row element was re-rendered, or
 * null if the same row element stayed on screen, which also satisfies "shows … within 1 s" }.
 */
async function staticRowAfter(page, id) {
  const t0 = await page.evaluate((id) => [...window.__act].reverse().find((a) => a.id === id)?.t, id);
  const wait = Math.max(0, 1000 - ((await page.evaluate(() => performance.now())) - t0));
  await page.waitForTimeout(wait);
  return page.evaluate((t0) => {
    const since = window.__rst.filter((s) => s.t >= t0);
    const bad = since.filter((s) => s.st[1] !== 'static');
    const now = document.querySelector('[data-testid=route-state]');
    const shown = !!now && now.dataset.state === 'static' && !now.closest('[hidden]');
    const rep = window.__rows.find((r) => r.t >= t0);
    return { ok: shown && bad.length === 0, bad: bad.map((b) => b.st), replaced: rep ? rep.t - t0 : null };
  }, t0);
}

test('AC53: static build: «Маршрут гаргах» opens the panel; within 1 s «Маршрутын үйлчилгээ түр ажиллахгүй байна» with «Дахин оролдох»; retry, tabs, swap and the avoid switch show it again; 0 /v1/route requests to any host; map, attribution and controls keep working', async ({ page }) => {
  const net = await openStatic(page);
  expect(await page.evaluate(() => typeof window.__nav004)).toBe('undefined');
  const box = await tid(page, 'map').boundingBox();
  await rightClick(page, box.x + box.width * 0.6, box.y + box.height * 0.5);
  await expect(tid(page, 'place-card')).toBeVisible();
  await expect(tid(page, 'route-open')).toBeVisible();
  await expect(tid(page, 'route-open')).toHaveText(T.mn.getDirections);
  await tid(page, 'route-open').click();
  await expect(tid(page, 'route-panel')).toBeVisible();
  await expect.poll(() => page.evaluate(() => window.__rst.at(-1)?.st[1]), { timeout: 3000 }).toBe('static');
  const tOpen = await page.evaluate(() => {
    const t0 = [...window.__act].reverse().find((a) => a.id === 'route-open')?.t;
    return window.__rst.find((s) => s.t >= t0 && s.st[1] === 'static').t - t0;
  });
  expect(tOpen, 'static row within 1 s of opening').toBeLessThanOrEqual(1000);
  const times = { open: { ok: true, replaced: tOpen } };
  const row = tid(page, 'route-state');
  await expect(row).toContainText(T.mn.unavailable);
  await expect(tid(page, 'route-retry')).toHaveText(T.mn.retry);
  // no loading row, no spinner at any time
  expect(await page.evaluate(() => window.__rst.some((s) => s.st[1] === 'loading' || s.st[0] === 'loading'))).toBe(false);
  // origin by typed coordinates (no search request needed), then every triggering action
  await tid(page, 'route-origin').click();
  await page.keyboard.type(`${REF.P1.lat.toFixed(5)}, ${REF.P1.lng.toFixed(5)}`);
  await expect(page.locator('#route-origin-results [data-testid=route-field-option]').first()).toBeVisible();
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await expect.poll(async () => tid(page, 'route-origin').inputValue()).toBe(T.mn.selectedPoint);
  for (const id of ['route-retry', 'route-tab-walk', 'route-tab-bike', 'route-tab-car', 'route-swap', 'route-avoid']) {
    await tid(page, id).click();
    times[id] = await staticRowAfter(page, id);
    await expect(row).toContainText(T.mn.unavailable);
  }
  test.info().annotations.push({ type: 'AC53 static row per action (re-rendered after ms, or null = same row stayed)', description: JSON.stringify(Object.fromEntries(Object.entries(times).map(([k, v]) => [k, v.replaced === null ? null : Math.round(v.replaced)]))) });
  for (const [k, v] of Object.entries(times)) {
    expect(v.ok, `${k}: only the static unavailable row since the action, still shown at +1 s ${JSON.stringify(v.bad ?? [])}`).toBe(true);
    if (v.replaced !== null) expect(v.replaced, `${k}: within 1 s`).toBeLessThanOrEqual(1000);
  }
  // Map and controls keep working
  const before = await tid(page, 'scale-label').getAttribute('data-meters');
  await tid(page, 'zoom-in').click();
  await page.waitForTimeout(1200);
  expect(await tid(page, 'scale-label').getAttribute('data-meters')).not.toBe(before);
  expect(await attributionProblems(page, 'attribution-osm', '© OpenStreetMap contributors')).toEqual([]);
  await tid(page, 'theme-toggle').click();
  await page.waitForTimeout(500);
  await tid(page, 'route-close').click();
  await expect(tid(page, 'route-panel')).toBeHidden();
  const routeReqs = net.filter((r) => /\/v1\/route/.test(r.url));
  const backend = net.filter((r) => /\/v1\/(search|reverse)/.test(r.url));
  test.info().annotations.push({ type: 'AC53 requests', description: `route ${routeReqs.length}, search/reverse ${backend.length}, total ${net.length}, hosts ${[...new Set(net.map((r) => new URL(r.url).host))].join(', ')}` });
  expect(routeReqs, '0 /v1/route requests to any host, the page origin included').toEqual([]);
  expect(backend).toEqual([]);
});

test('AC53: web/README.md states that the static-build setting also turns routing off; the static build contains no route request URL builder reachable at runtime (0 requests checked above)', () => {
  const md = readFileSync(WEB + 'README.md', 'utf8');
  const line = md.split('\n').find((l) => /VITE_STATIC_DEMO/.test(l) && /routing/i.test(l));
  expect(line, 'README row for VITE_STATIC_DEMO mentions routing').toBeTruthy();
  expect(line).toMatch(/search, reverse and routing off/i);
});

test('AC54: the web unit test "static public build (AC 53–54)" exists in src/route/routeClient.test.ts and passes (vitest)', () => {
  const src = readFileSync(WEB + 'src/route/routeClient.test.ts', 'utf8');
  expect(src).toContain('static public build (AC 53–54)');
  const out = execFileSync('npx', ['vitest', 'run', 'src/route/routeClient.test.ts', '-t', 'static public build', '--reporter=verbose'], { cwd: WEB, encoding: 'utf8', env: { ...process.env, CI: '1', NO_COLOR: '1' } });
  test.info().annotations.push({ type: 'AC54 vitest', description: out.split('\n').filter((l) => /static public build|Tests|passed|failed/.test(l)).join(' | ').slice(0, 400) });
  expect(out).toMatch(/with features\.routing off the client never builds or sends a request/);
  expect(out).toMatch(/Tests\s+1 passed/);
});

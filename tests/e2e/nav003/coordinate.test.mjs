// NAV-003 F. Coordinate card (reverse geocoding), AC 25–30.
import { test, expect } from '@playwright/test';
import {
  CORS, REF, REVERSE_GLOB, SEARCH_GLOB, T, camera, card, decimals, fc, feature, haversine, jumpTo, mapCentrePx, mock, openApp,
  options, params, pins, project, requestCount, requests, rightClick, tid, typeQuery, waitSettled,
} from './helpers.mjs';

const NEAR = feature('Сүхбаатарын хөшөө', 106.91766, 47.91885, { osm_key: 'historic', osm_value: 'monument', district: 'Бага Тойрог', city: 'Улаанбаатар' }, 777);

async function unproject(page, x, y) {
  return page.evaluate(
    ({ x, y }) => {
      const m = window.__nav002.map;
      const r = m.getCanvas().getBoundingClientRect();
      const ll = m.unproject([x - r.left, y - r.top]);
      return { lat: ll.lat, lng: ll.lng };
    },
    { x, y },
  );
}

const coordsOk = (text, p) => {
  const [a, b] = text.split(', ').map(Number);
  return /^-?\d+\.\d{5}, -?\d+\.\d{5}$/.test(text) && Math.abs(a - p.lat) <= 0.000011 && Math.abs(b - p.lng) <= 0.000011;
};

test('AC25 desktop: right-click opens pin «Сонгосон цэг» + coordinate card within 500 ms; camera does not move; click and drag open nothing', async ({ page }) => {
  const rev = await mock(page, REVERSE_GLOB, () => ({ body: fc([NEAR]) }));
  await openApp(page);
  await jumpTo(page, REF.P1, 15);
  // normal click and drag: no card
  await page.mouse.click(900, 420);
  await page.mouse.move(900, 420);
  await page.mouse.down();
  await page.mouse.move(980, 470, { steps: 6 });
  await page.mouse.up();
  await page.waitForTimeout(700);
  expect((await card(page)).open).toBe(false);
  expect(rev.length).toBe(0);
  // right-click
  const before = await camera(page);
  const t0 = await page.evaluate(() => performance.now());
  await rightClick(page, 900, 400);
  await expect(tid(page, 'place-card')).toBeVisible();
  const tCard = await page.evaluate((t0) => window.__st.find((s) => s.t >= t0 && s.card === 'point')?.t - t0, t0);
  test.info().annotations.push({ type: 'AC25 right-click -> card', description: `${tCard?.toFixed(0)} ms` });
  expect(tCard).toBeLessThanOrEqual(500);
  const c = await card(page);
  expect(c.kind).toBe('point');
  expect(c.title).toBe(T.mn.selectedPoint);
  const p = await unproject(page, 900, 400);
  expect(coordsOk(c.coords, p), `${c.coords} vs ${JSON.stringify(p)}`).toBe(true);
  const ps = await pins(page);
  expect(ps.length).toBe(1);
  expect(ps[0].label).toBe(T.mn.selectedPoint);
  expect(Math.hypot(ps[0].x - 900, ps[0].y - 400)).toBeLessThanOrEqual(2);
  await page.waitForTimeout(600);
  const after = await camera(page);
  expect(Math.abs(after.lat - before.lat) + Math.abs(after.lng - before.lng)).toBeLessThan(1e-9);
  expect(after.zoom).toBeCloseTo(before.zoom, 6);
  // right-drag (rotate) is a drag, not a press
  await tid(page, 'place-card-close').click();
  await page.mouse.move(700, 400);
  await page.mouse.down({ button: 'right' });
  await page.mouse.move(760, 400, { steps: 6 });
  await page.mouse.up({ button: 'right' });
  await page.waitForTimeout(600);
  expect((await card(page)).open).toBe(false);
});

test.describe('AC25 touch (CDP touch events)', () => {
  test.use({ hasTouch: true, viewport: { width: 360, height: 640 } });
  test('long-press >= 600 ms opens the card (one reverse); short tap, 30 px drag and pinch do not', async ({ page }) => {
    const rev = await mock(page, REVERSE_GLOB, () => ({ body: fc([NEAR]) }));
    await openApp(page);
    const cdp = await page.context().newCDPSession(page);
    const c = await mapCentrePx(page);
    const at = { x: Math.round(c.x), y: Math.round(c.y - 60) };
    const touch = async (frames, holdMs, gapMs = 16) => {
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: frames[0].map((p, i) => ({ ...p, id: i })) });
      for (const f of frames.slice(1)) {
        await new Promise((r) => setTimeout(r, gapMs));
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: f.map((p, i) => ({ ...p, id: i })) });
      }
      await new Promise((r) => setTimeout(r, holdMs));
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    };
    await touch([[at]], 200); // short tap
    await page.waitForTimeout(800);
    expect((await card(page)).open, 'short tap').toBe(false);
    await touch(Array.from({ length: 6 }, (_, i) => [{ x: at.x + i * 6, y: at.y }]), 700); // 30 px drag, then hold
    await page.waitForTimeout(800);
    expect((await card(page)).open, '30 px drag').toBe(false);
    await touch(Array.from({ length: 10 }, (_, i) => [{ x: at.x - 30 - i * 6, y: at.y }, { x: at.x + 30 + i * 6, y: at.y }]), 700); // pinch
    await page.waitForTimeout(800);
    expect((await card(page)).open, 'pinch').toBe(false);
    expect(rev.length).toBe(0);
    const before = await camera(page);
    const t0 = await page.evaluate(() => performance.now());
    await touch([[at]], 750); // long-press
    await expect(tid(page, 'place-card')).toBeVisible();
    const tCard = await page.evaluate((t0) => window.__st.find((s) => s.t >= t0 && s.card === 'point')?.t - t0, t0);
    // card within 500 ms after the 600 ms threshold
    expect(tCard).toBeLessThanOrEqual(600 + 500);
    expect((await card(page)).title).toBe(T.mn.selectedPoint);
    await page.waitForTimeout(800);
    expect(rev.length, 'exactly one reverse for one long-press').toBe(1);
    const after = await camera(page);
    expect(Math.abs(after.lat - before.lat) + Math.abs(after.lng - before.lng)).toBeLessThan(1e-9);
  });
});

test('AC26: a typed coordinate pair gives one «Сонгосон цэг» option without a search request; selecting centres at zoom 16 and opens the coordinate card; other formats are text', async ({ page }) => {
  const srch = await mock(page, SEARCH_GLOB, () => ({ body: fc([]) }));
  const rev = await mock(page, REVERSE_GLOB, () => ({ body: fc([NEAR]) }));
  await openApp(page);
  for (const q of ['47.9189, 106.9176', '47.9189,106.9176', '47.9189 106.9176', '-33.5, -70.25']) {
    await typeQuery(page, q);
    await waitSettled(page, q);
    const o = await options(page);
    expect(o.length, q).toBe(1);
    expect(o[0].kind).toBe('coordinate');
    expect(o[0].name).toBe(T.mn.selectedPoint);
    const [la, lo] = q.split(/[ ,]+/).map(Number);
    expect(o[0].text).toContain(`${la.toFixed(5)}, ${lo.toFixed(5)}`);
  }
  expect(srch.length).toBe(0);
  for (const q of ['106.9176, 47.9189', '47,9189, 106,9176']) {
    const n = srch.length;
    await typeQuery(page, q);
    await waitSettled(page, q);
    expect(srch.length - n, `${q} searched as text`).toBeGreaterThanOrEqual(1);
    expect(srch.at(-1).p.q).toBe(q);
  }
  // Select (zoom 12 -> 16)
  await jumpTo(page, { lat: 47.95, lng: 106.95 }, 12);
  await typeQuery(page, '47.9189, 106.9176');
  await waitSettled(page, '47.9189, 106.9176');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(1600);
  let cam = await camera(page);
  expect(cam.zoom).toBeCloseTo(16, 2);
  const pr = await project(page, 106.9176, 47.9189);
  expect(Math.hypot(pr.x - pr.cx, pr.y - pr.cy)).toBeLessThanOrEqual(5);
  let c = await card(page);
  expect(c.kind).toBe('point');
  expect(c.title).toBe(T.mn.selectedPoint);
  expect(c.coords).toBe('47.91890, 106.91760');
  expect(rev.length).toBe(1);
  // current zoom higher than 16 is kept
  await page.keyboard.press('Escape');
  await jumpTo(page, { lat: 47.92, lng: 106.92 }, 17.5);
  await typeQuery(page, '47.9189 106.9176');
  await waitSettled(page, '47.9189 106.9176');
  await tid(page, 'search-option').first().click();
  await page.waitForTimeout(1600);
  cam = await camera(page);
  expect(cam.zoom).toBeCloseTo(17.5, 2);
});

test('AC27: exactly one reverse with >= 5 decimals, lang, limit=1, radius=0.5; «Ачаалж байна…» while pending > 300 ms', async ({ page }) => {
  const rev = await mock(page, REVERSE_GLOB, () => ({ delay: 1200, body: fc([NEAR]) }));
  await openApp(page);
  await jumpTo(page, REF.P1, 15);
  await rightClick(page, 910, 410);
  const t0 = await page.evaluate(() => performance.now());
  await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'loading', { timeout: 1000 });
  await expect(page.locator('#place-nearest-state-text')).toHaveText(T.mn.loading);
  await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'place', { timeout: 3000 });
  await page.waitForTimeout(500);
  expect(rev.length).toBe(1);
  const p = rev[0].p;
  expect(decimals(p.lat)).toBeGreaterThanOrEqual(5);
  expect(decimals(p.lon)).toBeGreaterThanOrEqual(5);
  expect(p).toMatchObject({ lang: 'mn', limit: '1', radius: '0.5' });
  const pt = await unproject(page, 910, 410);
  expect(Math.abs(Number(p.lat) - pt.lat)).toBeLessThan(2e-6);
  expect(Math.abs(Number(p.lon) - pt.lng)).toBeLessThan(2e-6);
  const loadingAt = await page.evaluate(() => {
    const r = window.__req.find((x) => x.op === 'reverse');
    return window.__st.find((s) => s.t >= r.t && s.near === 'loading')?.t - r.t;
  });
  expect(loadingAt).toBeGreaterThanOrEqual(290);
  expect(loadingAt).toBeLessThanOrEqual(500);
  // English: lang=en
  await page.keyboard.press('Escape');
  await tid(page, 'language-toggle').click();
  await rightClick(page, 800, 420);
  await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'place', { timeout: 3000 });
  expect(rev.at(-1).p.lang).toBe('en');
  expect(rev.length).toBe(2);
});

test('AC28 (live): at P1 the card shows «Ойролцоох газар» + nearest name, type label, context; <= 300 m from P1; pin, heading and camera stay', async ({ page }) => {
  await openApp(page);
  const respP = page.waitForResponse((r) => r.url().includes('/v1/reverse?'));
  await typeQuery(page, '47.9189, 106.9176');
  await waitSettled(page, '47.9189, 106.9176');
  await page.keyboard.press('Enter');
  const resp = await respP;
  const body = await resp.json();
  await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'place', { timeout: 5000 });
  await page.waitForTimeout(1300);
  const f = body.features[0];
  const d = haversine(REF.P1, { lat: f.geometry.coordinates[1], lng: f.geometry.coordinates[0] });
  const c = await card(page);
  test.info().annotations.push({ type: 'AC28 nearest', description: `${f.properties.name} (${f.properties.osm_key}=${f.properties.osm_value}) ${d.toFixed(0)} m; card: ${c.nearText}` });
  expect(d).toBeLessThanOrEqual(300);
  expect(c.nearText.startsWith(T.mn.nearest)).toBe(true);
  expect(c.nearName).toBe(f.properties.name);
  expect(c.title).toBe(T.mn.selectedPoint);
  expect(c.coords).toBe('47.91890, 106.91760');
  const cam = await camera(page);
  const pr = await project(page, 106.9176, 47.9189);
  expect(Math.hypot(pr.x - pr.cx, pr.y - pr.cy)).toBeLessThanOrEqual(5); // camera did not move to the reverse feature
  const ps = await pins(page);
  expect(ps.length).toBe(1);
  expect(Math.hypot(ps[0].x - pr.x, ps[0].y - pr.y)).toBeLessThanOrEqual(2);
  expect(ps[0].label).toBe(T.mn.selectedPoint);
  expect(await requestCount(page, 'reverse')).toBe(1);
  expect(await requestCount(page, 'search')).toBe(0);
});

test('AC29 (live): empty reverse at X2 shows «Илэрц олдсонгүй», no error; heading, coordinates and pin stay', async ({ page }) => {
  await openApp(page);
  await page.waitForTimeout(600); // pacing after the previous live test
  await typeQuery(page, '39.9042, 116.4074');
  await waitSettled(page, '39.9042, 116.4074');
  await page.keyboard.press('Enter');
  await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'empty', { timeout: 5000 });
  const c = await card(page);
  expect(c.nearText).toContain(T.mn.noResults);
  expect(c.nearText).not.toContain(T.mn.unavailable);
  expect(c.nearText).not.toContain(T.mn.error);
  expect(c.retry).toBeNull();
  expect(c.title).toBe(T.mn.selectedPoint);
  expect(c.coords).toBe('39.90420, 116.40740');
  expect((await pins(page)).length).toBe(1);
});

test('AC30: reverse failure (503, network error, 8 s timeout) keeps heading/coordinates/pin, shows the state message; «Дахин оролдох» re-sends once', async ({ page }) => {
  test.setTimeout(90_000);
  let mode = '503';
  const rev = await mock(page, REVERSE_GLOB, () => (mode === '503' ? { status: 503, body: '{"error":"x"}' } : mode === 'abort' ? { abort: true } : mode === 'hang' ? { hang: true } : { body: fc([NEAR]) }));
  await openApp(page);
  await jumpTo(page, REF.P1, 15);
  for (const m of ['503', 'abort', 'hang']) {
    mode = m;
    const n = rev.length;
    await rightClick(page, 880, 380);
    const t0 = Date.now();
    await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'unavailable', { timeout: 9500 });
    // in-page: request start -> unavailable state (AC 33: within 8 s of the request start)
    const ms = await page.evaluate(() => {
      const r = window.__req.filter((x) => x.op === 'reverse').at(-1);
      return window.__st.find((s) => s.t >= r.t && s.near === 'unavailable')?.t - r.t;
    });
    test.info().annotations.push({ type: `AC30 ${m}`, description: `unavailable ${ms.toFixed(0)} ms after the request start (wall ${Date.now() - t0} ms)` });
    if (m === 'hang') expect(ms).toBeLessThanOrEqual(8100);
    const c = await card(page);
    expect(c.nearText).toContain(T.mn.unavailable);
    expect(c.title).toBe(T.mn.selectedPoint);
    expect(c.coords).toMatch(/^\d+\.\d{5}, \d+\.\d{5}$/);
    expect((await pins(page)).length).toBe(1);
    await expect(tid(page, 'place-retry')).toHaveText(T.mn.retry);
    expect(rev.length - n).toBe(1);
    await page.waitForTimeout(1500);
    expect(rev.length - n, 'no automatic retry').toBe(1);
    mode = 'ok';
    await tid(page, 'place-retry').click();
    await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'place', { timeout: 3000 });
    await page.waitForTimeout(500);
    expect(rev.length - n, 'retry re-sends once').toBe(2);
    await tid(page, 'place-card-close').click();
  }
});

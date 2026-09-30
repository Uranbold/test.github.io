// NAV-002 E. My location (AC 18–25) and J. privacy (AC 47).
// Geolocation is mocked with Playwright permissions and positions (story "Test approach"). The one
// exception is the AC 22 timeout case, which uses a stub that fires TIMEOUT after options.timeout,
// exactly as a browser without a fix would.
import { test, expect } from '@playwright/test';
import {
  P1, S, X2, camera, collectErrors, geoSpyInit, geoTimeoutStubInit, haversine, jump, north, openApp, statusView, tid, waitIdle, token, normColor,
} from './helpers.mjs';

const btn = (page) => tid(page, 'my-location');
const marker = (page) => tid(page, 'location-marker');
const markerLngLat = (page) =>
  page.evaluate(() => {
    const el = document.querySelector('[data-testid="location-marker"]');
    if (!el) return null;
    const m = window.__nav002.map;
    const r = el.getBoundingClientRect();
    const c = m.getCanvas().getBoundingClientRect();
    const ll = m.unproject([r.left + r.width / 2 - c.left, r.top + r.height / 2 - c.top]);
    return { lat: ll.lat, lng: ll.lng };
  });
/** Accuracy circle: radius from the rendered GeoJSON polygon (mean distance of the vertices to its centroid). */
const accuracyRadius = (page) =>
  page.evaluate(() => {
    const m = window.__nav002.map;
    const feats = m.queryRenderedFeatures({ layers: ['nav-location-accuracy-fill'] });
    const src = m.getSource('nav-location');
    const data = src?._data?.geojson ?? src?._data ?? null;
    const f = feats[0] ?? data?.features?.[0];
    if (!f) return { rendered: feats.length, ring: null };
    const ring = f.geometry.coordinates[0];
    return { rendered: feats.length, ring };
  });
const ringRadius = (ring, centre) => ring.reduce((s, [lng, lat]) => s + haversine(centre, { lat, lng }), 0) / ring.length;

test.describe('NAV-002 E. My location', () => {
  test('AC18 page load makes no geolocation or permissions call (no prompt)', async ({ page }) => {
    await page.addInitScript(geoSpyInit);
    await openApp(page);
    // Interact with everything except the my-location button
    await tid(page, 'zoom-in').click();
    await tid(page, 'theme-toggle').click();
    await tid(page, 'language-toggle').click();
    await page.waitForTimeout(1500);
    expect(await page.evaluate(() => window.__geoSpy)).toEqual([]);
    await expect(btn(page)).toHaveAttribute('data-state', 'idle');
    // Positive control: the spy does see the first press.
    await btn(page).click();
    await expect.poll(() => page.evaluate(() => window.__geoSpy.length)).toBeGreaterThan(0);
  });

  test('AC19 granted, P1 ±30 m: within 3 s centre ≤20 m, zoom ≥15, marker «Миний байршил», 30 m circle ±10 %, button following', async ({ page, context }) => {
    await context.grantPermissions(['geolocation']);
    await context.setGeolocation({ latitude: P1.lat, longitude: P1.lng, accuracy: 30 });
    await openApp(page);
    await jump(page, { center: north(P1, 5000), zoom: 12 }); // start 5 km away
    await expect(btn(page)).toHaveAccessibleName(S.mn.recenter);
    const t0 = Date.now();
    await btn(page).click();
    await expect(btn(page)).toHaveAttribute('data-state', 'following', { timeout: 3000 });
    await expect(marker(page)).toBeVisible({ timeout: 3000 });
    await page.waitForFunction(({ lat, lng }) => {
      const c = window.__nav002.map.getCenter();
      return Math.abs(c.lat - lat) < 0.00018 && Math.abs(c.lng - lng) < 0.00027 && !window.__nav002.map.isMoving();
    }, P1, { timeout: 3000 - (Date.now() - t0) });
    const dt = Date.now() - t0;
    const cam = await camera(page);
    test.info().annotations.push({ type: 'AC19 time to centred (ms)', description: String(dt) });
    expect(dt).toBeLessThanOrEqual(3000);
    expect(haversine(cam, P1)).toBeLessThanOrEqual(20);
    expect(cam.zoom).toBeGreaterThanOrEqual(15 - 1e-9);
    await expect(marker(page)).toHaveAttribute('role', 'img');
    await expect(marker(page)).toHaveAccessibleName(S.mn.myLocation);
    await expect(btn(page)).toHaveAttribute('aria-pressed', 'true');
    await waitIdle(page);
    const acc = await accuracyRadius(page);
    expect(acc.rendered, 'accuracy circle rendered').toBeGreaterThan(0);
    const r = ringRadius(acc.ring, P1);
    expect(r).toBeGreaterThanOrEqual(27);
    expect(r).toBeLessThanOrEqual(33);
    // Marker at the fix
    expect(haversine(await markerLngLat(page), P1)).toBeLessThan(5);
  });

  test('AC19 a higher current zoom is kept', async ({ page, context }) => {
    await context.grantPermissions(['geolocation']);
    await context.setGeolocation({ latitude: P1.lat, longitude: P1.lng, accuracy: 30 });
    await openApp(page);
    await jump(page, { center: north(P1, 800), zoom: 17 });
    await btn(page).click();
    await expect(btn(page)).toHaveAttribute('data-state', 'following', { timeout: 3000 });
    await page.waitForFunction(() => !window.__nav002.map.isMoving(), null, { timeout: 3000 });
    expect((await camera(page)).zoom).toBeCloseTo(17, 3);
  });

  test('AC20 following: 200 m move → marker within 2 s and camera follows; after a pan: marker moves, camera stays, "not following"; press recentres', async ({ page, context }) => {
    await context.grantPermissions(['geolocation']);
    await context.setGeolocation({ latitude: P1.lat, longitude: P1.lng, accuracy: 30 });
    await openApp(page);
    await btn(page).click();
    await expect(btn(page)).toHaveAttribute('data-state', 'following', { timeout: 3000 });
    await page.waitForFunction(() => !window.__nav002.map.isMoving(), null, { timeout: 3000 });
    const p2 = north(P1, 200);
    const t0 = Date.now();
    await context.setGeolocation({ latitude: p2.lat, longitude: p2.lng, accuracy: 30 });
    await expect.poll(async () => haversine((await markerLngLat(page)) ?? P1, p2), { timeout: 2000, intervals: [100] }).toBeLessThan(10);
    test.info().annotations.push({ type: 'AC20 marker update (ms)', description: String(Date.now() - t0) });
    await expect.poll(async () => haversine(await camera(page), p2), { timeout: 2000 }).toBeLessThan(20);
    // User pans
    const box = await page.locator('#map canvas').boundingBox();
    const c = { x: box.x + box.width / 2, y: box.y + box.height / 2 - 60 };
    await page.mouse.move(c.x, c.y);
    await page.mouse.down();
    for (let i = 1; i <= 10; i++) await page.mouse.move(c.x + i * 20, c.y + i * 5);
    await page.mouse.up();
    await expect(btn(page)).toHaveAttribute('data-state', 'not-following');
    await expect(btn(page)).toHaveAttribute('aria-pressed', 'false');
    await page.waitForFunction(() => !window.__nav002.map.isMoving(), null, { timeout: 3000 });
    const camAfterPan = await camera(page);
    const p3 = north(p2, 200);
    await context.setGeolocation({ latitude: p3.lat, longitude: p3.lng, accuracy: 30 });
    await expect.poll(async () => haversine((await markerLngLat(page)) ?? p2, p3), { timeout: 2000, intervals: [100] }).toBeLessThan(10);
    await page.waitForTimeout(800);
    expect(haversine(await camera(page), camAfterPan), 'camera did not follow').toBeLessThan(1);
    // Press again → recentre as in AC 19
    await btn(page).click();
    await expect(btn(page)).toHaveAttribute('data-state', 'following');
    await expect.poll(async () => haversine(await camera(page), p3), { timeout: 3000 }).toBeLessThanOrEqual(20);
    expect((await camera(page)).zoom).toBeGreaterThanOrEqual(15 - 1e-9);
  });

  test('AC21 denied: within 1 s G3 + G4 + «Хаах»; distinct "denied" state; press again shows it again; map usable; no uncaught error', async ({ page, context }) => {
    const errors = collectErrors(page);
    await context.clearPermissions(); // headless Chromium denies the prompt
    await openApp(page);
    const t0 = Date.now();
    await btn(page).click();
    const msg = tid(page, 'location-message');
    await expect(msg).toBeVisible({ timeout: 1000 });
    const dt = Date.now() - t0;
    expect(dt).toBeLessThanOrEqual(1000);
    await expect(msg).toContainText(S.mn.denied);
    await expect(msg).toContainText(S.mn.deniedHint);
    await expect(tid(page, 'location-close')).toBeVisible();
    await expect(tid(page, 'location-close')).toHaveText(S.mn.close);
    await expect(tid(page, 'location-retry')).toBeHidden();
    await expect(btn(page)).toHaveAttribute('data-state', 'denied');
    // Visually distinct from idle: different icon markup and colour
    const deniedLook = await btn(page).evaluate((b) => ({ html: b.innerHTML, color: getComputedStyle(b.querySelector('svg') ?? b).color }));
    await tid(page, 'location-close').click();
    await expect(msg).toBeHidden();
    await expect(btn(page)).toBeFocused();
    await btn(page).click();
    await expect(msg).toBeVisible({ timeout: 1000 });
    await expect(msg).toContainText(S.mn.denied);
    // Map fully usable
    const z = (await camera(page)).zoom;
    await tid(page, 'zoom-in').click();
    await expect.poll(async () => (await camera(page)).zoom).toBeGreaterThan(z + 0.9);
    expect(errors).toEqual([]);
    // Compare with a fresh idle button in another page
    const p2 = await context.newPage();
    await openApp(p2);
    const idleLook = await btn(p2).evaluate((b) => ({ html: b.innerHTML, color: getComputedStyle(b.querySelector('svg') ?? b).color }));
    expect(deniedLook.html === idleLook.html && deniedLook.color === idleLook.color, 'denied looks different from idle').toBe(false);
    expect(normColor(deniedLook.color)).toBe(normColor(token('light', 'ui.error')));
    await p2.close();
  });

  test('AC22 POSITION_UNAVAILABLE: G5 with «Дахин оролдох»; retry starts a new request', async ({ page, context }) => {
    await context.grantPermissions(['geolocation']);
    await page.addInitScript(geoSpyInit);
    await openApp(page);
    await context.setGeolocation(null); // Chromium reports POSITION_UNAVAILABLE
    await btn(page).click();
    const msg = tid(page, 'location-message');
    await expect(msg).toBeVisible({ timeout: 11_000 });
    await expect(msg).toContainText(S.mn.unavailable);
    await expect(tid(page, 'location-retry')).toHaveText(S.mn.retry);
    const calls = await page.evaluate(() => window.__geoSpy.filter((c) => c.startsWith('geolocation.')).length);
    await context.setGeolocation({ latitude: P1.lat, longitude: P1.lng, accuracy: 30 });
    await tid(page, 'location-retry').click();
    await expect.poll(() => page.evaluate(() => window.__geoSpy.filter((c) => c.startsWith('geolocation.')).length)).toBe(calls + 1);
    await expect(btn(page)).toHaveAttribute('data-state', 'following', { timeout: 3000 });
    await expect(msg).toBeHidden();
  });

  test('AC22 no fix within 10 s (TIMEOUT): G5 with «Дахин оролдох»; retry starts a new request', async ({ page }) => {
    test.setTimeout(60_000);
    await page.addInitScript(geoTimeoutStubInit);
    await openApp(page);
    const t0 = await page.evaluate(() => performance.now());
    await btn(page).click();
    await expect(btn(page)).toHaveAttribute('data-state', 'locating');
    const calls = await page.evaluate(() => window.__geoCalls);
    const opts = calls.find((c) => c.fn !== 'clearWatch').opts;
    expect(opts.timeout, 'request timeout').toBeLessThanOrEqual(10_000);
    const msg = tid(page, 'location-message');
    await expect(msg).toBeVisible({ timeout: 11_000 });
    const t1 = await page.evaluate(() => performance.now());
    test.info().annotations.push({ type: 'AC22 timeout message after (ms)', description: String(Math.round(t1 - t0)) });
    expect(t1 - t0).toBeLessThanOrEqual(10_500);
    await expect(msg).toContainText(S.mn.unavailable);
    const n = (await page.evaluate(() => window.__geoCalls)).filter((c) => c.fn !== 'clearWatch').length;
    await tid(page, 'location-retry').click();
    await expect.poll(async () => (await page.evaluate(() => window.__geoCalls)).filter((c) => c.fn !== 'clearWatch').length).toBe(n + 1);
    await expect(btn(page)).toHaveAttribute('data-state', 'locating');
  });

  test('AC23 fixes stop after a fix: within 2 s marker stale at last position + G5; fixes resume: normal + message hidden within 2 s', async ({ page, context }) => {
    await context.grantPermissions(['geolocation']);
    await context.setGeolocation({ latitude: P1.lat, longitude: P1.lng, accuracy: 30 });
    await openApp(page);
    await btn(page).click();
    await expect(marker(page)).toHaveAttribute('data-stale', 'false', { timeout: 3000 });
    const normalColour = await marker(page).evaluate((e) => getComputedStyle(e).backgroundColor);
    const tA = await page.evaluate(() => performance.now());
    await context.setGeolocation(null);
    await expect(marker(page)).toHaveAttribute('data-stale', 'true', { timeout: 2000 });
    await expect(tid(page, 'location-message')).toContainText(S.mn.unavailable, { timeout: 2000 });
    const staleAt = await page.evaluate((a) => (window.__t.find((s) => s.t >= a && s.stale === 'true') || {}).t - a, tA);
    test.info().annotations.push({ type: 'AC23 stale after (ms)', description: String(Math.round(staleAt)) });
    expect(haversine(await markerLngLat(page), P1)).toBeLessThan(5);
    const staleColour = await marker(page).evaluate((e) => getComputedStyle(e).backgroundColor);
    expect(normColor(staleColour)).toBe(normColor(token('light', 'location.stale-dot')));
    expect(normColor(normalColour)).toBe(normColor(token('light', 'location.dot')));
    // Resume
    await context.setGeolocation({ latitude: P1.lat, longitude: P1.lng, accuracy: 30 });
    await expect(marker(page)).toHaveAttribute('data-stale', 'false', { timeout: 2000 });
    await expect(tid(page, 'location-message')).toBeHidden({ timeout: 2000 });
  });

  test('AC24 no Geolocation API: button disabled with G5 as accessible description; no exception', async ({ page }) => {
    const errors = collectErrors(page);
    await page.addInitScript(() => {
      delete Navigator.prototype.geolocation;
    });
    await openApp(page);
    await expect(btn(page)).toHaveAttribute('data-state', 'unsupported');
    await expect(btn(page)).toHaveAttribute('aria-disabled', 'true');
    await expect(btn(page)).toHaveAccessibleDescription(S.mn.unavailable);
    await btn(page).click({ force: true });
    await page.waitForTimeout(500);
    await expect(tid(page, 'location-message')).toBeHidden();
    expect(errors).toEqual([]);
  });

  test('AC24 insecure context (http://<non-localhost host>:5173): button disabled with G5; no exception', async ({ page }) => {
    const errors = collectErrors(page);
    // Serve the dev server under a non-localhost http origin (like a LAN IP). Requests are forwarded by the
    // test, so the page's origin is http://nav002-lan.test:5173, which is not a secure context.
    await page.route('http://nav002-lan.test:5173/**', async (route) => {
      const url = route.request().url().replace('http://nav002-lan.test:5173', 'http://localhost:5173');
      const resp = await route.fetch({ url, headers: { ...route.request().headers(), host: 'localhost:5173' } });
      await route.fulfill({ response: resp });
    });
    await page.goto('http://nav002-lan.test:5173/');
    expect(await page.evaluate(() => window.isSecureContext)).toBe(false);
    await expect(btn(page)).toHaveAttribute('data-state', 'unsupported', { timeout: 15_000 });
    await expect(btn(page)).toHaveAttribute('aria-disabled', 'true');
    await expect(btn(page)).toHaveAccessibleDescription(S.mn.unavailable);
    // "No exception": uncaught page errors only. The Vite HMR websocket cannot go through the test's HTTP
    // forwarder, so its console error is a harness artefact.
    expect(errors.filter((e) => e.startsWith('pageerror'))).toEqual([]);
  });

  test('AC25 position X2 (outside Mongolia): camera and marker move there, no error message', async ({ page, context }) => {
    await context.grantPermissions(['geolocation']);
    await context.setGeolocation({ latitude: X2.lat, longitude: X2.lng, accuracy: 30 });
    await openApp(page);
    await btn(page).click();
    await expect(btn(page)).toHaveAttribute('data-state', 'following', { timeout: 3000 });
    await expect.poll(async () => haversine(await camera(page), X2), { timeout: 3000 }).toBeLessThanOrEqual(20);
    expect((await camera(page)).zoom).toBeGreaterThanOrEqual(15 - 1e-9);
    await expect(marker(page)).toBeVisible();
    await waitIdle(page);
    await page.waitForTimeout(1000);
    await expect(tid(page, 'location-message')).toBeHidden();
    expect(await statusView(page)).toEqual({ loading: false, card: null, banner: null });
  });
});

test.describe('NAV-002 J. Privacy', () => {
  test('AC47 after a fix, no request contains the device coordinates (URL, headers, body)', async ({ page, context }) => {
    // Distinctive coordinates that appear nowhere else.
    const pos = { lat: 47.8123457, lng: 107.0234567 };
    const needles = ['47.812', '107.023', '47,812', '107,023', '47.81234', '107.02345', '4781234', '10702345'];
    await context.grantPermissions(['geolocation']);
    await context.setGeolocation({ latitude: pos.lat, longitude: pos.lng, accuracy: 25 });
    await openApp(page);
    const reqs = [];
    page.on('request', async (r) => reqs.push({ url: r.url(), headers: JSON.stringify(await r.allHeaders()), body: r.postData() ?? '' }));
    await btn(page).click();
    await expect(btn(page)).toHaveAttribute('data-state', 'following', { timeout: 3000 });
    await waitIdle(page);
    // Keep the session going: follow a move, zoom, switch theme and language.
    await context.setGeolocation({ latitude: pos.lat + 0.001, longitude: pos.lng, accuracy: 25 });
    await page.waitForTimeout(1000);
    await tid(page, 'zoom-in').click();
    await tid(page, 'theme-toggle').click();
    await tid(page, 'language-toggle').click();
    await waitIdle(page);
    expect(reqs.length).toBeGreaterThan(0); // tiles/glyphs were requested after the fix
    const hits = reqs.filter((r) => needles.some((n) => r.url.includes(n) || r.headers.includes(n) || r.body.includes(n)));
    expect(hits.map((h) => h.url)).toEqual([]);
  });
});

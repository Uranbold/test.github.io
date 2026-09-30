// NAV-004 F. States: AC 32–40 (failures simulated by request interception, offline by context.setOffline).
import { test, expect } from '@playwright/test';
import { REF, T, lines, mockReverse, mockRoute, openApp, openPreview, panel, pnow, routeReqs, rtimeline, setField, simpleBody, tid, waitFinal, lastAct, CORS } from './helpers.mjs';

const ok = (b, specs) => ({ body: simpleBody({ lat: b.locations[0].lat, lng: b.locations[0].lon }, { lat: b.locations[1].lat, lng: b.locations[1].lon }, specs) });
const err400 = (body) => ({ status: 400, body });

/** Opens the preview with P1 → P3; the first response is `first(b)`, the rest `next(b, n)`. */
async function setup(page, next, { first, lang } = {}) {
  await mockReverse(page);
  const rr = await mockRoute(page, (b, n) => (n === 0 && first ? first(b) : next(b, n)));
  await openApp(page, lang ? { lang } : {});
  await openPreview(page, REF.P3);
  await setField(page, 'origin', REF.P1);
  await waitFinal(page, 20_000);
  return rr;
}

test('AC32: pending > 300 ms → «Ачаалж байна…» with aria-busy=true; gone within 200 ms of the result; a fast response shows no loading; the previous route is removed at the start of every request', async ({ page }) => {
  let delay = 0;
  const rr = await setup(page, (b) => ({ ...ok(b), delay }));
  expect((await lines(page)).length).toBe(1);
  // fast response (0 ms): no loading row
  const tFast = await pnow(page);
  await tid(page, 'route-swap').click();
  await waitFinal(page);
  await page.waitForTimeout(400);
  expect((await rtimeline(page)).filter((s) => s.t >= tFast && s.state === 'loading').length, 'no loading state for a fast response').toBe(0);
  // slow response (1.5 s)
  delay = 1500;
  await tid(page, 'route-swap').click();
  const tAct = await lastAct(page, 'route-swap');
  await page.waitForTimeout(100);
  // previous lines, summary and turn list removed at once
  expect((await lines(page)).length).toBe(0);
  let p = await panel(page);
  expect(p.duration).toBeNull();
  expect(p.steps.length).toBe(0);
  await expect.poll(async () => (await panel(page)).state, { timeout: 3000 }).toBe('loading');
  p = await panel(page);
  expect(p.rowText).toBe(T.mn.loading);
  expect(p.busy).toBe('true');
  await waitFinal(page);
  const reqs = await routeReqs(page);
  const last = reqs.at(-1);
  const tl = await rtimeline(page);
  const tLoading = tl.find((s) => s.t >= tAct && s.state === 'loading')?.t;
  const tRoute = tl.find((s) => s.t >= last.end && s.state === 'route')?.t;
  const noBusy = tl.find((s) => s.t >= last.end && s.busy === 'false')?.t;
  test.info().annotations.push({ type: 'AC32 timings', description: `request start → loading ${(tLoading - last.t).toFixed(0)} ms; response → route ${(tRoute - last.end).toFixed(0)} ms; response → aria-busy false ${(noBusy - last.end).toFixed(0)} ms` });
  expect(tLoading - last.t).toBeGreaterThanOrEqual(290);
  expect(tLoading - last.t).toBeLessThanOrEqual(600);
  expect(tRoute - last.end).toBeLessThanOrEqual(200);
  expect(noBusy - last.end).toBeLessThanOrEqual(200);
  expect(rr.length).toBe(3);
});

test('AC33: 400 NoRoute → «Маршрут олдсонгүй», no retry; with the avoid switch on also the hint «Шороон замаас зайлсхийх тохиргоог унтрааж дахин оролдоно уу»; not retried', async ({ page }) => {
  const rr = await setup(page, () => err400({ code: 'NoRoute', message: 'Impossible route between points' }));
  let p = await panel(page);
  expect(p.state).toBe('no-route');
  expect(p.mainText ?? p.rowText).toBe(T.mn.noRoute);
  expect(p.hint).toBeNull();
  expect(p.retry).toBeNull();
  await tid(page, 'route-avoid').click();
  await waitFinal(page);
  p = await panel(page);
  expect(p.state).toBe('no-route');
  expect(p.mainText).toBe(T.mn.noRoute);
  expect(p.hint).toBe(T.mn.avoidHint);
  expect(p.retry).toBeNull();
  await page.waitForTimeout(1500);
  expect(rr.length, 'nothing retried').toBe(2);
});

for (const [name, body] of [
  ['NoSegment', { code: 'NoSegment', message: 'Could not find a matching segment for any coordinate' }],
  ['ValhallaError 171', { error_code: 171, error: 'No suitable edges near location', status_code: 400, status: 'Bad Request' }],
]) {
  test(`AC34: 400 ${name} → «Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна», no line, markers stay, no retry`, async ({ page }) => {
    const rr = await setup(page, () => err400(body));
    const p = await panel(page);
    expect(p.state).toBe('out-of-area');
    expect(p.rowText).toBe(T.mn.outOfArea);
    expect(p.retry).toBeNull();
    expect((await lines(page)).length).toBe(0);
    const m = await page.evaluate(() => ({ o: document.querySelectorAll('[data-testid=route-origin-marker]').length, d: [...document.querySelectorAll('[data-testid=place-pin]')].filter((e) => !e.closest('[hidden]')).length }));
    expect(m).toEqual({ o: 1, d: 1 });
    await page.waitForTimeout(1000);
    expect(rr.length).toBe(1);
  });
}

test('AC35: 400 DistanceExceeded on «Явган»/«Дугуй» → «Энэ зай явганаар эсвэл дугуйгаар хэт хол байна»; on «Машин» → «Маршрут олдсонгүй»', async ({ page }) => {
  const body = { code: 'DistanceExceeded', message: 'Path distance exceeds the max distance limit' };
  await setup(page, () => err400(body));
  let p = await panel(page);
  expect(p.state).toBe('no-route');
  expect(p.mainText ?? p.rowText).toBe(T.mn.noRoute);
  for (const m of ['walk', 'bike']) {
    await tid(page, `route-tab-${m}`).click();
    await expect.poll(async () => (await panel(page)).state, { timeout: 5000 }).toBe('too-far');
    p = await panel(page);
    expect(p.rowText).toBe(T.mn.tooFar);
    expect(p.retry).toBeNull();
  }
});

const UNAVAILABLE = [
  ['network error', () => ({ abort: 'failed' })],
  ['connection refused', () => ({ abort: 'connectionrefused' })],
  ['CORS failure', (b) => ({ ...ok(b), headers: { ...CORS, 'Access-Control-Allow-Origin': 'http://other-origin.test' } })],
  ['502', () => ({ status: 502, body: { code: 'UpstreamUnavailable', message: 'routing service unavailable' } })],
  ['503', () => ({ status: 503, body: { code: 'UpstreamUnavailable', message: 'routing service unavailable' } })],
  ['504', () => ({ status: 504, body: { code: 'UpstreamTimeout', message: 'routing timed out' } })],
];
for (const [name, fail] of UNAVAILABLE) {
  test(`AC36: ${name} → «Маршрутын үйлчилгээ түр ажиллахгүй байна» with «Дахин оролдох»; retry re-sends once; nothing retries automatically`, async ({ page }) => {
    let failing = true;
    const rr = await setup(page, (b) => (failing ? fail(b) : ok(b)));
    let p = await panel(page);
    expect(p.state).toBe('unavailable');
    expect(p.rowText).toContain(T.mn.unavailable);
    expect(p.retry).toBe('enabled');
    await expect(tid(page, 'route-retry')).toHaveText(T.mn.retry);
    await page.waitForTimeout(2500);
    expect(rr.length, 'no automatic retry').toBe(1);
    failing = false;
    await tid(page, 'route-retry').click();
    await waitFinal(page);
    await page.waitForTimeout(500);
    expect(rr.length, 'one retry request').toBe(2);
    p = await panel(page);
    expect(p.state).toBe('route');
    expect(rr[1].body).toEqual(rr[0].body);
  });
}

test('AC36: no response within 12 s (client timeout) → unavailable within 12 s of the request start, no endless spinner', async ({ page }) => {
  test.setTimeout(60_000);
  const rr = await setup(page, (b, n) => (n === 0 ? ok(b) : { hang: true }));
  await tid(page, 'route-swap').click();
  await expect.poll(async () => (await panel(page)).state, { timeout: 3000 }).toBe('loading');
  await expect.poll(async () => (await panel(page)).state, { timeout: 15_000, intervals: [250] }).toBe('unavailable');
  const reqs = await routeReqs(page);
  const tl = await rtimeline(page);
  const tU = tl.find((s) => s.t >= reqs[1].t && s.state === 'unavailable').t;
  test.info().annotations.push({ type: 'AC36 timeout', description: `request start → unavailable ${(tU - reqs[1].t).toFixed(0)} ms` });
  expect(tU - reqs[1].t).toBeLessThanOrEqual(12_000 + 250);
  expect(tU - reqs[1].t).toBeGreaterThanOrEqual(11_500);
  expect(rr.length).toBe(2);
});

for (const [hdr, wait] of [
  ['3', 3],
  [null, 5],
  ['abc', 5],
  ['0', 5],
  ['Wed, 21 Oct 2026 07:28:00 GMT', 5],
]) {
  test(`AC37: 429 with Retry-After ${hdr === null ? 'missing' : `"${hdr}"`} → «Түр хүлээгээд дахин оролдоно уу», retry aria-disabled for ${wait} s, 0 requests in the window whatever the user does, nothing automatic afterwards, then one request on «Дахин оролдох»`, async ({ page }) => {
    test.setTimeout(60_000);
    let limited = true;
    const rr = await setup(page, (b) => (limited ? { status: 429, body: { code: 'RateLimited', message: 'Too many requests' }, headers: hdr === null ? {} : { 'Retry-After': hdr } } : ok(b)));
    const reqs0 = await routeReqs(page);
    const t429 = reqs0[0].end;
    let p = await panel(page);
    expect(p.state).toBe('rate-limited');
    expect(p.rowText).toContain(T.mn.rateLimited);
    expect(p.retry).toBe('true');
    limited = false;
    // In the window: tabs, swap, avoid, and the disabled retry (keyboard and forced click) send nothing
    await tid(page, 'route-tab-walk').click();
    await page.waitForTimeout(400);
    await tid(page, 'route-tab-car').click();
    await page.waitForTimeout(400);
    await tid(page, 'route-avoid').click();
    await tid(page, 'route-swap').click();
    await tid(page, 'route-retry').click({ force: true });
    await tid(page, 'route-retry').focus();
    await page.keyboard.press('Enter');
    await page.keyboard.press(' ');
    expect(await tid(page, 'route-avoid').getAttribute('aria-checked'), 'inputs still update').toBe('true');
    // retry turns enabled after N s
    await expect.poll(async () => (await panel(page)).retry, { timeout: (wait + 3) * 1000, intervals: [100] }).toBe('enabled');
    const tEnabled = await pnow(page);
    test.info().annotations.push({ type: 'AC37 retry enabled after', description: `${((tEnabled - t429) / 1000).toFixed(2)} s (expected ${wait} s)` });
    expect(tEnabled - t429).toBeGreaterThanOrEqual(wait * 1000 - 100);
    expect(tEnabled - t429).toBeLessThanOrEqual(wait * 1000 + 700);
    expect(rr.length, '0 route requests in the wait window').toBe(1);
    await page.waitForTimeout(2000);
    expect(rr.length, 'nothing sent automatically after the wait').toBe(1);
    p = await panel(page);
    expect(p.state).toBe('rate-limited');
    await tid(page, 'route-retry').click();
    await waitFinal(page);
    await page.waitForTimeout(500);
    expect(rr.length).toBe(2);
    // current inputs: swapped points and avoid on
    expect(rr[1].body.costing_options).toEqual({ auto: { exclude_unpaved: true } });
    expect(rr[1].body.locations[0].lat).toBeCloseTo(REF.P3.lat, 5);
    expect((await panel(page)).state).toBe('route');
  });
}

test('AC37: after the 429 wait the next triggering action (not only retry) sends one request', async ({ page }) => {
  let limited = true;
  const rr = await setup(page, (b) => (limited ? { status: 429, body: { code: 'RateLimited' }, headers: { 'Retry-After': '2' } } : ok(b)));
  limited = false;
  await page.waitForTimeout(2600);
  expect(rr.length).toBe(1);
  await tid(page, 'route-tab-bike').click();
  await waitFinal(page);
  await page.waitForTimeout(500);
  expect(rr.length).toBe(2);
  expect(rr[1].body.costing).toBe('bicycle');
});

test('AC38: offline → no request and «Интернэт холболт алга» within 500 ms of a triggering action; back online with both points and no route → one request within 2 s, no user action', async ({ page, context }) => {
  const rr = await setup(page, (b) => ok(b));
  await context.setOffline(true);
  await page.waitForTimeout(300);
  await tid(page, 'route-swap').click();
  const tAct = await lastAct(page, 'route-swap');
  await expect.poll(async () => (await panel(page)).state).toBe('offline');
  const tl = await rtimeline(page);
  const tOff = tl.find((s) => s.t >= tAct && s.state === 'offline').t;
  test.info().annotations.push({ type: 'AC38 offline row', description: `${(tOff - tAct).toFixed(0)} ms after the action` });
  expect(tOff - tAct).toBeLessThanOrEqual(500);
  const p = await panel(page);
  expect(p.rowText).toBe(T.mn.offline);
  expect(p.retry).toBeNull();
  await tid(page, 'route-tab-walk').click();
  await page.waitForTimeout(800);
  expect(rr.length, 'no request while offline').toBe(1);
  expect((await routeReqs(page)).length).toBe(1);
  const tOn = await pnow(page);
  await context.setOffline(false);
  await expect.poll(async () => (await routeReqs(page)).length, { timeout: 2500 }).toBe(2);
  const r2 = (await routeReqs(page))[1];
  test.info().annotations.push({ type: 'AC38 online → request', description: `${(r2.t - tOn).toFixed(0)} ms (upper bound incl. setOffline round trip)` });
  expect(r2.t - tOn).toBeLessThanOrEqual(2000);
  expect(r2.body.costing).toBe('pedestrian');
  await waitFinal(page);
  await page.waitForTimeout(2500);
  expect(rr.length, 'exactly one request after coming back online').toBe(2);
});

test('AC38: coming back online with a route already shown sends nothing', async ({ page, context }) => {
  const rr = await setup(page, (b) => ok(b));
  await context.setOffline(true);
  await page.waitForTimeout(500);
  await context.setOffline(false);
  await page.waitForTimeout(2500);
  expect(rr.length).toBe(1);
  expect((await panel(page)).state).toBe('route');
});

const GENERIC = [
  ['400 InvalidOptions', () => err400({ code: 'InvalidOptions', message: 'bad' })],
  ['400 InvalidValue', () => err400({ code: 'InvalidValue', message: 'bad' })],
  ['400 TooBig', () => err400({ code: 'TooBig', message: 'bad' })],
  ['400 unknown code', () => err400({ code: 'SomethingNew', message: 'bad' })],
  ['400 ValhallaError 170', () => err400({ error_code: 170, error: 'Locations are in unconnected regions', status_code: 400 })],
  ['400 non-JSON body', () => ({ status: 400, body: 'Bad Request' })],
  ['413', () => ({ status: 413, body: { code: 'PayloadTooLarge' } })],
  ['200 unparsable body', () => ({ status: 200, body: '{"code":"Ok","routes":[' })],
  ['200 without routes', () => ({ status: 200, body: { code: 'Ok', routes: [] } })],
];
for (const [name, fail] of GENERIC) {
  test(`AC39: ${name} → «Алдаа гарлаа», the same request is not retried`, async ({ page }) => {
    const rr = await setup(page, fail);
    const p = await panel(page);
    expect(p.state).toBe('error');
    expect(p.rowText).toBe(T.mn.error);
    expect(p.retry).toBeNull();
    await page.waitForTimeout(1500);
    expect(rr.length).toBe(1);
  });
}

test('AC40: a state message switches language within 500 ms; route messages stay inside the panel and never cover the attribution', async ({ page }) => {
  await setup(page, () => err400({ code: 'NoSegment' }));
  await tid(page, 'language-toggle').click();
  const tAct = await lastAct(page, 'language-toggle');
  await expect.poll(async () => (await panel(page)).rowText).toBe(T.en.outOfArea);
  const tl = await rtimeline(page);
  const tEn = tl.find((s) => s.t >= tAct && s.rowText === T.en.outOfArea)?.t;
  test.info().annotations.push({ type: 'AC40 message language switch', description: `${(tEn - tAct).toFixed(0)} ms` });
  expect(tEn - tAct).toBeLessThanOrEqual(500);
  const geo = await page.evaluate(() => {
    const row = document.querySelector('[data-testid=route-state]').getBoundingClientRect();
    const panel = document.querySelector('[data-testid=route-panel]').getBoundingClientRect();
    const attr = document.querySelector('[data-testid=attribution]').getBoundingClientRect();
    const inside = row.left >= panel.left - 0.5 && row.right <= panel.right + 0.5 && row.top >= panel.top - 0.5 && row.bottom <= panel.bottom + 0.5;
    const overlap = !(panel.right <= attr.left || panel.left >= attr.right || panel.bottom <= attr.top || panel.top >= attr.bottom);
    return { inside, overlap };
  });
  expect(geo.inside).toBe(true);
  expect(geo.overlap).toBe(false);
});

test('AC40: routing still works while the NAV-002 tiles-unavailable banner shows; tiles still load while routing is unavailable', async ({ page }) => {
  let routingDown = true;
  await setup(page, (b) => (routingDown ? { status: 502, body: { code: 'UpstreamUnavailable' } } : ok(b)));
  expect((await panel(page)).state).toBe('unavailable');
  // tiles keep working: pan to new tiles, no tiles banner
  const tileOk = [];
  page.on('response', (r) => {
    if (/basemap\.pmtiles/.test(r.url())) tileOk.push(r.status());
  });
  await page.evaluate(() => new Promise((res) => {
    const m = window.__nav002.map;
    m.once('idle', res);
    m.jumpTo({ center: [106.95, 47.93], zoom: 14 });
    setTimeout(res, 8000);
  }));
  expect(tileOk.length, 'tile range requests while routing is down').toBeGreaterThan(0);
  expect(tileOk.every((s) => s === 206 || s === 200)).toBe(true);
  expect(await tid(page, 'status-banner').isVisible()).toBe(false);
  // now break tiles, routing back
  await page.route('**/tiles/basemap.pmtiles', (r) => r.abort('failed'));
  routingDown = false;
  for (const c of [[106.7, 47.8], [107.2, 48.1], [106.5, 47.6], [107.5, 47.7]]) {
    await page.evaluate((c) => window.__nav002.map.jumpTo({ center: c, zoom: 15 }), c);
    await page.waitForTimeout(700);
  }
  await expect(tid(page, 'status-banner')).toBeVisible({ timeout: 15_000 });
  expect(await tid(page, 'status-banner').getAttribute('data-reason')).toBe('tiles');
  await tid(page, 'route-retry').click();
  await waitFinal(page);
  expect((await panel(page)).state).toBe('route');
  expect(await tid(page, 'status-banner').getAttribute('data-reason')).toBe('tiles');
});

test('State precedence (screen spec): same point beats offline; offline beats a running 429 wait', async ({ page, context }) => {
  let limited = false;
  await setup(page, (b) => (limited ? { status: 429, body: { code: 'RateLimited' }, headers: { 'Retry-After': '10' } } : ok(b)));
  limited = true;
  await tid(page, 'route-swap').click();
  await expect.poll(async () => (await panel(page)).state).toBe('rate-limited');
  await context.setOffline(true);
  await tid(page, 'route-tab-walk').click();
  await expect.poll(async () => (await panel(page)).state, { timeout: 3000 }).toBe('offline');
  await setField(page, 'origin', { lat: REF.P1.lat + 3 / 111_195, lng: REF.P1.lng }); // destination is P1 after the swap
  await expect.poll(async () => (await panel(page)).state, { timeout: 3000 }).toBe('same-point');
  await context.setOffline(false);
});

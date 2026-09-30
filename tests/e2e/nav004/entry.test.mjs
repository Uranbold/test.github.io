// NAV-004 A. Entry points, origin and destination: AC 1–9, 46.
import { test, expect } from '@playwright/test';
import {
  REF, T, SEARCH_GLOB, coordCard, decimals, fc, feature, firstAfter, geoSpyInit, locationOn, mock, mockReverse, mockRoute, openApp, openPreview,
  lastAct, panel, pnow, rawLocationDecimals, rightClick, routeReqs, rview, setField, simpleBody, tid, typeQuery, waitFinal, waitSettled, mapCanvasBox,
} from './helpers.mjs';

const DEPT = feature('Улсын их дэлгүүр', 106.904412, 47.913876, { osm_key: 'shop', osm_value: 'department_store', street: 'Энхтайвны өргөн чөлөө' }, 5151);

async function placeCard(page) {
  await mock(page, SEARCH_GLOB, () => ({ body: fc([DEPT]) }));
  await typeQuery(page, 'их дэлгүүр');
  await waitSettled(page, 'их дэлгүүр');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await expect(tid(page, 'place-card')).toBeVisible();
}

for (const how of ['click', 'Enter', 'Space']) {
  test(`AC1 (${how}): place card has «Маршрут гаргах» below the coordinates; activation opens «Маршрут харах» within 500 ms with destination = result name and the card's point`, async ({ page }) => {
    await mockReverse(page);
    const rr = await mockRoute(page, () => ({ body: simpleBody(REF.P1, REF.P2) }));
    await openApp(page);
    await placeCard(page);
    const btn = tid(page, 'route-open');
    await expect(btn).toBeVisible();
    await expect(btn).toHaveText(T.mn.getDirections);
    const pos = await page.evaluate(() => {
      const c = document.querySelector('[data-testid=place-card-coords]').getBoundingClientRect();
      const b = document.querySelector('[data-testid=route-open]').getBoundingClientRect();
      return { coordsBottom: c.bottom, btnTop: b.top };
    });
    expect(pos.btnTop, 'button below the coordinates').toBeGreaterThanOrEqual(pos.coordsBottom - 1);
    const coordsText = await tid(page, 'place-card-coords').textContent();
    const t0 = await pnow(page);
    if (how === 'click') await btn.click();
    else {
      await btn.focus();
      await page.keyboard.press(how === 'Space' ? ' ' : 'Enter');
    }
    await expect(tid(page, 'route-panel')).toBeVisible();
    const tAct = await lastAct(page, 'route-open');
    const tOpen = await firstAfter(page, tAct ?? t0, '(s) => s.open');
    test.info().annotations.push({ type: `AC1 open (${how})`, description: `${tOpen?.toFixed(0)} ms (in-page, MutationObserver)` });
    expect(tOpen).not.toBeNull();
    expect(tOpen).toBeLessThanOrEqual(500);
    const p = await panel(page);
    expect(p.title).toBe(T.mn.title);
    expect(p.destination).toBe('Улсын их дэлгүүр');
    const v = await rview(page);
    const [la, lo] = coordsText.split(',').map((s) => Number(s.trim()));
    expect(decimals(coordsText.split(',')[0].trim())).toBeGreaterThanOrEqual(5);
    expect(Math.abs(v.destination.point.lat - la)).toBeLessThanOrEqual(0.000011);
    expect(Math.abs(v.destination.point.lon - lo)).toBeLessThanOrEqual(0.000011);
    expect(rr.length, 'no origin yet (location off): no route request').toBe(0);
  });
}

test('AC2: coordinate card «Маршрут гаргах» → destination «Сонгосон цэг» at the chosen coordinate, not the reverse result location', async ({ page }) => {
  await mock(page, '**/v1/reverse?**', () => ({ body: fc([feature('Ойрын газар', 106.93, 47.93, {}, 99)]) })); // 1.6 km away
  await mockRoute(page, () => ({ body: simpleBody(REF.P1, REF.P2) }));
  await openApp(page);
  await jumpToP2(page);
  const box = await mapCanvasBox(page);
  const x = Math.round(box.x + box.w * 0.6);
  const y = Math.round(box.y + box.h * 0.5);
  await rightClick(page, x, y);
  await expect(tid(page, 'place-card')).toBeVisible();
  await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'place');
  const coordsText = await tid(page, 'place-card-coords').textContent();
  const t0 = await pnow(page);
  await tid(page, 'route-open').click();
  await expect(tid(page, 'route-panel')).toBeVisible();
  expect(await firstAfter(page, (await lastAct(page, 'route-open')) ?? t0, '(s) => s.open')).toBeLessThanOrEqual(500);
  const p = await panel(page);
  expect(p.destination).toBe(T.mn.selectedPoint);
  const v = await rview(page);
  const [la, lo] = coordsText.split(',').map((s) => Number(s.trim()));
  expect(Math.abs(v.destination.point.lat - la)).toBeLessThanOrEqual(0.000011);
  expect(Math.abs(v.destination.point.lon - lo)).toBeLessThanOrEqual(0.000011);
});

async function jumpToP2(page) {
  await page.evaluate(
    (c) =>
      new Promise((res) => {
        const m = window.__nav002.map;
        m.once('idle', res);
        m.jumpTo({ center: [c.lng, c.lat], zoom: 15 });
        setTimeout(res, 8000);
      }),
    REF.P2,
  );
}

test('AC3 + AC46 + AC9: location on → origin «Миний байршил» = last fix, request sent at once, no Geolocation call by NAV-004, NAV-002 marker is the origin marker, focus on the selected tab', async ({ page, context }) => {
  await page.addInitScript(geoSpyInit);
  await mockReverse(page);
  const rr = await mockRoute(page, (b) => ({ body: simpleBody({ lat: b.locations[0].lat, lng: b.locations[0].lon }, REF.P2) }));
  await openApp(page);
  const fix = { lat: 47.918934, lng: 106.917612 };
  await locationOn(page, context, fix);
  await coordCard(page, REF.P2);
  const spyBefore = await page.evaluate(() => window.__geoSpy.length);
  await tid(page, 'route-open').click();
  await expect(tid(page, 'route-panel')).toBeVisible();
  await waitFinal(page);
  const p = await panel(page);
  expect(p.origin).toBe(T.mn.myLocation);
  expect(rr.length).toBe(1);
  const req = (await routeReqs(page))[0];
  expect(req.body.locations[0].lat).toBeCloseTo(fix.lat, 6);
  expect(req.body.locations[0].lon).toBeCloseTo(fix.lng, 6);
  for (const d of rawLocationDecimals(req.raw)) expect(d.dec, `${d.k}=${d.v}`).toBeGreaterThanOrEqual(5);
  expect(await page.evaluate(() => window.__geoSpy.length), 'no Geolocation/Permissions call when the panel opens').toBe(spyBefore);
  // AC 46: origin set → focus on the selected mode tab
  expect(await page.evaluate(() => document.activeElement?.dataset.testid)).toBe('route-tab-car');
  // AC 9: NAV-002 location marker used, no second origin marker; one destination marker (NAV-003 pin) named as its field
  const m = await page.evaluate(() => {
    const vis = (e) => !!e && e.isConnected && !e.hidden && !e.closest('[hidden]') && getComputedStyle(e).display !== 'none' && e.getClientRects().length > 0;
    return {
      origin: [...document.querySelectorAll('[data-testid=route-origin-marker]')].filter(vis).length,
      loc: [...document.querySelectorAll('[data-testid=location-marker]')].filter(vis).length,
      pins: [...document.querySelectorAll('[data-testid=place-pin]')].filter(vis).map((e) => e.getAttribute('aria-label')),
    };
  });
  expect(m.origin).toBe(0);
  expect(m.loc).toBe(1);
  expect(m.pins).toEqual([T.mn.selectedPoint]);
});

test('AC4 + AC46: location not on → origin empty with placeholder «Эхлэх цэг сонгох», name «Эхлэх цэг», focus in it, 0 route requests, no location prompt', async ({ page }) => {
  await page.addInitScript(geoSpyInit);
  await mockReverse(page);
  const rr = await mockRoute(page, () => ({ body: simpleBody(REF.P1, REF.P2) }));
  await openApp(page);
  await openPreview(page, REF.P2);
  await page.waitForTimeout(800);
  const p = await panel(page);
  expect(p.origin).toBe('');
  expect(p.originPh).toBe(T.mn.originPlaceholder);
  expect(p.originName).toBe(T.mn.origin);
  expect(p.destName).toBe(T.mn.destination);
  expect(await page.evaluate(() => document.activeElement?.dataset.testid)).toBe('route-origin');
  expect(rr.length).toBe(0);
  expect(await page.evaluate(() => window.__geoSpy)).toEqual([]);
  expect(p.swapDisabled).toBe('true');
});

test('AC4: a last fix older than 60 s counts as "location not on" (origin empty, 0 requests, no Geolocation call)', async ({ page, context }) => {
  await page.clock.install();
  await page.addInitScript(geoSpyInit);
  await mockReverse(page);
  const rr = await mockRoute(page, () => ({ body: simpleBody(REF.P1, REF.P2) }));
  await openApp(page);
  await locationOn(page, context, REF.P1);
  // Stop further fixes, then let 61 s pass on the page clock
  await context.setGeolocation(null);
  await page.clock.fastForward(61_000);
  await coordCard(page, REF.P2);
  const spyBefore = await page.evaluate(() => window.__geoSpy.length);
  await tid(page, 'route-open').click();
  await expect(tid(page, 'route-panel')).toBeVisible();
  await page.waitForTimeout(500);
  const p = await panel(page);
  test.info().annotations.push({ type: 'AC4 stale fix', description: `origin field "${p.origin}", requests ${rr.length}` });
  expect(p.origin).toBe('');
  expect(rr.length).toBe(0);
  expect(await page.evaluate(() => window.__geoSpy.length)).toBe(spyBefore);
});

test('AC5: empty origin field lists «Миний байршил» first; location off + permission granted → fix within 10 s sets the origin and sends one request', async ({ page, context }) => {
  await mockReverse(page);
  const rr = await mockRoute(page, (b) => ({ body: simpleBody({ lat: b.locations[0].lat, lng: b.locations[0].lon }, REF.P2) }));
  await context.grantPermissions(['geolocation']);
  await context.setGeolocation({ latitude: REF.P1.lat, longitude: REF.P1.lng, accuracy: 20 });
  await openApp(page);
  await openPreview(page, REF.P2);
  const first = page.locator('#route-origin-results [role=option]').first();
  await expect(first).toBeVisible();
  await expect(first).toHaveAttribute('data-testid', 'route-my-location');
  await expect(first).toContainText(T.mn.myLocation);
  expect(rr.length).toBe(0);
  await first.click();
  await expect.poll(async () => (await panel(page)).origin, { timeout: 10_000 }).toBe(T.mn.myLocation);
  await waitFinal(page);
  expect(rr.length).toBe(1);
  expect(rr[0].body.locations[0].lat).toBeCloseTo(REF.P1.lat, 5);
});

test('AC5: «Миний байршил» with location permission denied → NAV-002 denied message, origin empty, 0 route requests', async ({ page }) => {
  await mockReverse(page);
  const rr = await mockRoute(page, () => ({ body: simpleBody(REF.P1, REF.P2) }));
  await openApp(page); // no permission granted: Chromium denies
  await openPreview(page, REF.P2);
  await tid(page, 'route-my-location').click();
  await expect(tid(page, 'location-message')).toBeVisible({ timeout: 10_000 });
  const kind = await tid(page, 'location-message').getAttribute('data-kind');
  test.info().annotations.push({ type: 'AC5 denied message kind', description: String(kind) });
  expect(kind).toBe('denied');
  await page.waitForTimeout(500);
  expect((await panel(page)).origin).toBe('');
  expect(rr.length).toBe(0);
  expect(await page.evaluate(() => document.activeElement?.dataset.testid)).toBe('route-origin');
});

test('AC5: «Миний байршил» with POSITION_UNAVAILABLE → «Байршил тодорхойлж чадсангүй», origin empty, 0 route requests', async ({ page, context }) => {
  await mockReverse(page);
  const rr = await mockRoute(page, () => ({ body: simpleBody(REF.P1, REF.P2) }));
  await context.grantPermissions(['geolocation']);
  await context.setGeolocation(null); // Chromium reports POSITION_UNAVAILABLE
  await openApp(page);
  await openPreview(page, REF.P2);
  await tid(page, 'route-my-location').click();
  await expect(tid(page, 'location-message')).toBeVisible({ timeout: 15_000 });
  await expect(tid(page, 'location-message')).toContainText(T.mn.locationUnavailable);
  expect((await panel(page)).origin).toBe('');
  expect(rr.length).toBe(0);
});

test('AC6: route fields search like NAV-003 (debounce, lang, limit, bias) with their own list; picking a result sets the point and sends one route request; typed coordinates → «Сонгосон цэг» with no search request', async ({ page }) => {
  await mockReverse(page);
  const GANDAN = feature('Гандантэгчэнлин хийд', 106.8950, 47.9215, { osm_key: 'amenity', osm_value: 'place_of_worship' }, 777);
  const srch = await mock(page, SEARCH_GLOB, () => ({ body: fc([GANDAN]) }));
  const rr = await mockRoute(page, (b) => ({ body: simpleBody({ lat: b.locations[0].lat, lng: b.locations[0].lon }, REF.P2) }));
  await openApp(page);
  await openPreview(page, REF.P2);
  // typed coordinates in the destination field: no search request
  const nBefore = srch.length;
  await setField(page, 'destination', REF.P3);
  expect(srch.length - nBefore).toBe(0);
  expect((await panel(page)).destination).toBe(T.mn.selectedPoint);
  expect(rr.length, 'origin still empty: no route request').toBe(0);
  // text search in the origin field
  const input = tid(page, 'route-origin');
  await input.click();
  await page.keyboard.type('Гандан', { delay: 30 });
  const opt = page.locator('#route-origin-results [data-testid=route-field-option]').first();
  await expect(opt).toBeVisible();
  await page.waitForTimeout(400);
  expect(srch.length - nBefore, 'one search request per settled query').toBe(1);
  const q = srch.at(-1).p;
  expect(q.q).toBe('Гандан');
  expect(q.lang).toBe('mn');
  expect(q.limit).toBe('8');
  expect(decimals(q.lat)).toBe(3);
  expect(decimals(q.lon)).toBe(3);
  expect(await page.locator('#route-origin-results').getAttribute('aria-label')).toBe('Хайлтын илэрц');
  expect(await input.getAttribute('aria-controls')).toBe('route-origin-results');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await waitFinal(page);
  const p = await panel(page);
  expect(p.origin).toBe('Гандантэгчэнлин хийд');
  expect(rr.length).toBe(1);
  expect(rr[0].body.locations[0].lat).toBeCloseTo(47.9215, 5);
  expect(p.destPh).toBe(T.mn.destinationPlaceholder);
});

test('AC7: right-click during the preview opens the coordinate card with «Эхлэх цэг болгох» / «Очих газар болгох» (no «Маршрут гаргах»); setting the start closes the card and sends one request', async ({ page }) => {
  await mockReverse(page);
  const rr = await mockRoute(page, (b) => ({ body: simpleBody({ lat: b.locations[0].lat, lng: b.locations[0].lon }, { lat: b.locations[1].lat, lng: b.locations[1].lon }) }));
  await openApp(page);
  await openPreview(page, REF.P2);
  await page.keyboard.press('Escape'); // close the origin list (not the panel: a list is open)
  const box = await mapCanvasBox(page);
  const x = Math.round(box.x + box.w * 0.75);
  const y = Math.round(box.y + box.h * 0.4);
  await rightClick(page, x, y);
  await expect(tid(page, 'route-point-card')).toBeVisible();
  await expect(tid(page, 'route-set-origin')).toHaveText(T.mn.setOrigin);
  await expect(tid(page, 'route-set-destination')).toHaveText(T.mn.setDestination);
  await expect(tid(page, 'route-point-title')).toHaveText(T.mn.selectedPoint);
  const openBtns = await page.evaluate(() => [...document.querySelectorAll('[data-testid=route-point-card] [data-testid=route-open]')].length);
  expect(openBtns).toBe(0);
  await tid(page, 'route-set-origin').click();
  await expect(tid(page, 'route-point-card')).toBeHidden();
  await waitFinal(page);
  expect((await panel(page)).origin).toBe(T.mn.selectedPoint);
  expect(rr.length).toBe(1);
  // "Очих газар болгох" replaces the destination and sends one more request
  await rightClick(page, x - 80, y + 60);
  await expect(tid(page, 'route-point-card')).toBeVisible();
  await tid(page, 'route-set-destination').click();
  await waitFinal(page);
  expect((await panel(page)).destination).toBe(T.mn.selectedPoint);
  expect(rr.length).toBe(2);
});

test('AC8: swap is aria-disabled while a point is empty; with both set it swaps points and texts and sends exactly one request', async ({ page }) => {
  await mockReverse(page);
  const rr = await mockRoute(page, (b) => ({ body: simpleBody({ lat: b.locations[0].lat, lng: b.locations[0].lon }, { lat: b.locations[1].lat, lng: b.locations[1].lon }) }));
  await openApp(page);
  await openPreview(page, REF.P2);
  expect((await panel(page)).swapDisabled).toBe('true');
  await tid(page, 'route-swap').click({ force: true });
  await page.waitForTimeout(400);
  expect(rr.length).toBe(0);
  await setField(page, 'origin', REF.P1);
  await waitFinal(page);
  expect(rr.length).toBe(1);
  const before = await rview(page);
  expect(await tid(page, 'route-swap').getAttribute('aria-label')).toBe(T.mn.swap);
  expect((await panel(page)).swapDisabled).not.toBe('true');
  await tid(page, 'route-swap').click();
  await waitFinal(page);
  await page.waitForTimeout(400);
  expect(rr.length).toBe(2);
  const after = await rview(page);
  expect(after.origin.point).toEqual(before.destination.point);
  expect(after.destination.point).toEqual(before.origin.point);
  expect(rr[1].body.locations[0].lat).toBeCloseTo(before.destination.point.lat, 6);
  const p = await panel(page);
  expect(p.origin).toBe(T.mn.selectedPoint);
  expect(p.destination).toBe(T.mn.selectedPoint);
});

test('AC9: both points set (typed start) → exactly one origin and one destination marker, distinct, named as their field texts', async ({ page }) => {
  await mockReverse(page);
  await mockRoute(page, (b) => ({ body: simpleBody({ lat: b.locations[0].lat, lng: b.locations[0].lon }, { lat: b.locations[1].lat, lng: b.locations[1].lon }) }));
  await mock(page, SEARCH_GLOB, () => ({ body: fc([DEPT]) }));
  await openApp(page);
  await typeQuery(page, 'их дэлгүүр');
  await waitSettled(page, 'их дэлгүүр');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await tid(page, 'route-open').click();
  await setField(page, 'origin', REF.P1);
  await waitFinal(page);
  const m = await page.evaluate(() => {
    const vis = (e) => !!e && e.isConnected && !e.closest('[hidden]') && getComputedStyle(e).display !== 'none' && getComputedStyle(e).visibility !== 'hidden' && e.getClientRects().length > 0;
    const pick = (sel) => [...document.querySelectorAll(sel)].filter(vis).map((e) => ({ label: e.getAttribute('aria-label'), role: e.getAttribute('role'), cls: e.className, bg: getComputedStyle(e).backgroundColor, w: e.getBoundingClientRect().width }));
    return { origin: pick('[data-testid=route-origin-marker]'), dest: pick('[data-testid=place-pin]') };
  });
  expect(m.origin.length).toBe(1);
  expect(m.dest.length).toBe(1);
  expect(m.origin[0].label).toBe(T.mn.selectedPoint);
  expect(m.origin[0].role).toBe('img');
  expect(m.dest[0].label).toBe('Улсын их дэлгүүр');
  expect(m.origin[0].cls).not.toBe(m.dest[0].cls);
});

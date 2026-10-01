// NAV-004 G. Closing the preview and coexistence with NAV-002/NAV-003: AC 41–43.
import { test, expect } from '@playwright/test';
import {
  FIXTURE_ROUTE, REF, SEARCH_GLOB, attributionProblems, fc, feature, lastAct, line, lines, mock, mockReverse, mockRoute, openApp, openPreview, osrmBody, osrmRoute,
  panel, rtimeline, routeReqs, setField, simpleBody, tid, typeQuery, waitFinal, waitSettled, rightClick, mapCanvasBox,
} from './helpers.mjs';

const ok = (b, specs) => ({ body: simpleBody({ lat: b.locations[0].lat, lng: b.locations[0].lon }, { lat: b.locations[1].lat, lng: b.locations[1].lon }, specs) });

for (const how of ['close button', 'Escape']) {
  test(`AC41 (${how}): within 200 ms the panel, lines and origin marker go away, an in-flight request is aborted, 0 new requests, focus back on «Маршрут гаргах» (card still open)`, async ({ page }) => {
    await mockReverse(page);
    await mock(page, SEARCH_GLOB, () => ({ body: fc([feature('Гандан', 106.895, 47.9215, {}, 1)]) }));
    let delay = 0;
    const rr = await mockRoute(page, (b) => ({ ...ok(b), delay }));
    await openApp(page);
    await openPreview(page, REF.P3);
    await setField(page, 'origin', REF.P1);
    await waitFinal(page);
    expect((await lines(page)).length).toBe(1);
    // Escape with a field list open closes only the list
    if (how === 'Escape') {
      await tid(page, 'route-destination').click();
      await page.keyboard.type('Ган');
      await expect(page.locator('#route-destination-results')).toBeVisible();
      await page.keyboard.press('Escape');
      await page.waitForTimeout(300);
      expect((await panel(page)).open, 'Escape with a list open keeps the panel').toBe(true);
    }
    delay = 2500;
    await tid(page, 'route-tab-walk').click();
    await expect.poll(async () => (await routeReqs(page)).length, { timeout: 3000 }).toBe(2); // in flight
    if (how === 'close button') await tid(page, 'route-close').click();
    else {
      await tid(page, 'route-tab-walk').focus();
      await page.keyboard.press('Escape');
    }
    const tAct = how === 'close button' ? await lastAct(page, 'route-close') : await page.evaluate(() => window.__keys?.filter((k) => k.key === 'Escape').at(-1)?.t);
    await expect(tid(page, 'route-panel')).toBeHidden();
    const tl = await rtimeline(page);
    const tClosed = tl.find((s) => s.t >= tAct && !s.open)?.t;
    test.info().annotations.push({ type: `AC41 ${how} → panel gone`, description: `${(tClosed - tAct).toFixed(0)} ms` });
    expect(tClosed - tAct).toBeLessThanOrEqual(200);
    const reqs = await routeReqs(page);
    expect(reqs[1].abortAt, 'in-flight request aborted').not.toBeNull();
    expect(reqs[1].abortAt - tAct).toBeLessThanOrEqual(200);
    expect((await lines(page)).length).toBe(0);
    const m = await page.evaluate(() => ({ o: [...document.querySelectorAll('[data-testid=route-origin-marker]')].filter((e) => e.isConnected).length }));
    expect(m.o).toBe(0);
    expect(await page.evaluate(() => document.activeElement?.dataset.testid)).toBe('route-open');
    await expect(tid(page, 'place-card')).toBeVisible();
    await expect(tid(page, 'search-input')).toBeVisible();
    await page.waitForTimeout(3000);
    expect(rr.length, '0 new requests after closing').toBe(2);
    expect((await lines(page)).length, 'the late response draws nothing').toBe(0);
  });
}

test('AC42: panel never opened → the search input is the first Tab stop, a NAV-003 session (search, card, coordinate card) sends 0 route requests', async ({ page }) => {
  await mockReverse(page);
  await mock(page, SEARCH_GLOB, () => ({ body: fc([feature('Гандан', 106.895, 47.9215, {}, 1)]) }));
  const net = [];
  page.on('request', (r) => {
    if (/\/v1\/route/.test(r.url())) net.push(r.url());
  });
  await openApp(page);
  await page.keyboard.press('Tab');
  expect(await page.evaluate(() => document.activeElement?.dataset.testid)).toBe('search-input');
  await typeQuery(page, 'Гандан');
  await waitSettled(page, 'Гандан');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await expect(tid(page, 'place-card')).toBeVisible();
  await tid(page, 'place-card-close').click();
  const box = await mapCanvasBox(page);
  await rightClick(page, box.x + box.w / 2, box.y + box.h / 2);
  await expect(tid(page, 'place-card')).toBeVisible();
  await page.waitForTimeout(1000);
  expect(net).toEqual([]);
  expect(await page.evaluate(() => window.__rr.length)).toBe(0);
});

// ------------------------------------------------------------------ AC 43 layout matrix
const VIEWPORTS = [
  [320, 568],
  [360, 640],
  [768, 1024],
  [1366, 768],
  [1920, 1080],
];
const COORDS = line(REF.P1, REF.P3, 40, 0.002);
const LONG = osrmBody([
  osrmRoute({ coords: COORDS, distance: 5400, duration: 780, steps: FIXTURE_ROUTE.steps }),
  osrmRoute({ coords: line(REF.P1, REF.P3, 40, 0.006), distance: 6100, duration: 900, steps: FIXTURE_ROUTE.steps.slice(0, 5).concat(FIXTURE_ROUTE.steps.slice(-1)) }),
]);

const NAV004_TARGETS = ['route-close', 'route-origin', 'route-destination', 'route-swap', 'route-tab-car', 'route-tab-walk', 'route-tab-bike', 'route-avoid', 'route-retry', 'route-option', 'route-step'];
const NAV002_CONTROLS = ['language-toggle', 'theme-toggle', 'compass', 'zoom-in', 'zoom-out', 'my-location', 'scale-bar', 'attribution-osm'];

for (const [w, h] of VIEWPORTS) {
  for (const theme of ['day', 'night']) {
    for (const lang of ['mn', 'en']) {
      test(`AC43 ${w}×${h} ${theme} ${lang}: route + turn list: attribution/scale/controls uncovered, no overlapping controls, panel scrolls inside, NAV-004 targets >= 44×44`, async ({ page }) => {
        await page.setViewportSize({ width: w, height: h });
        await mockReverse(page);
        await mockRoute(page, () => ({ body: LONG }));
        await openApp(page, { theme, lang });
        await openPreview(page, REF.P3);
        await setField(page, 'origin', REF.P1);
        await waitFinal(page);
        await page.waitForFunction(() => !window.__nav002.map.isMoving());
        const probs = [];
        probs.push(...(await attributionProblems(page, 'attribution-osm', '© OpenStreetMap contributors')));
        const r = await page.evaluate(
          ({ targets, controls }) => {
            const out = { overlaps: [], small: [], covered: [], scroll: null };
            const vis = (e) => !!e && !e.hidden && !e.closest('[hidden]') && getComputedStyle(e).visibility !== 'hidden' && e.getClientRects().length > 0;
            const panel = document.querySelector('[data-testid=route-panel]');
            const pr = panel.getBoundingClientRect();
            // visible part of an element (clipped to the scrolling panel if inside it, and to the viewport)
            const visRect = (e) => {
              let r = e.getBoundingClientRect();
              let { left, top, right, bottom } = r;
              if (panel.contains(e)) {
                left = Math.max(left, pr.left);
                top = Math.max(top, pr.top);
                right = Math.min(right, pr.right);
                bottom = Math.min(bottom, pr.bottom);
              }
              left = Math.max(left, 0);
              top = Math.max(top, 0);
              right = Math.min(right, innerWidth);
              bottom = Math.min(bottom, innerHeight);
              return right - left > 1 && bottom - top > 1 ? { left, top, right, bottom } : null;
            };
            const els = [];
            for (const id of [...targets, ...controls]) for (const e of document.querySelectorAll(`[data-testid="${id}"]`)) if (vis(e)) els.push({ id, e });
            // touch targets (full element size, even when scrolled out)
            for (const { id, e } of els) {
              if (!targets.includes(id)) continue;
              const b = e.getBoundingClientRect();
              if (b.width < 44 - 0.5 || b.height < 44 - 0.5) out.small.push(`${id}${e.dataset.index ?? ''} ${b.width.toFixed(1)}×${b.height.toFixed(1)}`);
            }
            // overlaps between visible parts (sticky header: rows scrolled under it are covered by design → use hit test)
            const shown = els.map((x) => ({ ...x, r: visRect(x.e) })).filter((x) => x.r);
            for (let i = 0; i < shown.length; i++)
              for (let j = i + 1; j < shown.length; j++) {
                const a = shown[i].r;
                const b = shown[j].r;
                const ix = Math.min(a.right, b.right) - Math.max(a.left, b.left);
                const iy = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
                if (ix > 1 && iy > 1) {
                  // inside the panel, the sticky header legitimately sits over scrolled rows
                  const bothInPanel = panel.contains(shown[i].e) && panel.contains(shown[j].e);
                  if (bothInPanel && (shown[i].id === 'route-close' || shown[j].id === 'route-close')) continue;
                  out.overlaps.push(`${shown[i].id}${shown[i].e.dataset.index ?? ''} × ${shown[j].id}${shown[j].e.dataset.index ?? ''}`);
                }
              }
            // NAV-002 controls must be the top element at their centre (not covered by the panel, lists or markers)
            for (const { id, e } of els) {
              if (!controls.includes(id) || id === 'attribution-osm') continue;
              const b = e.getBoundingClientRect();
              const hit = document.elementFromPoint(b.left + b.width / 2, b.top + b.height / 2);
              if (!hit || !(hit === e || e.contains(hit) || hit.contains(e))) out.covered.push(`${id} by ${hit?.tagName}.${hit?.className}`);
            }
            // the panel scrolls inside itself when its content does not fit
            const cs = getComputedStyle(panel);
            out.scroll = { scrollH: panel.scrollHeight, clientH: panel.clientHeight, overflowY: cs.overflowY, bottom: pr.bottom, vh: innerHeight, pageScroll: document.scrollingElement.scrollHeight > innerHeight + 1 };
            return out;
          },
          { targets: NAV004_TARGETS, controls: NAV002_CONTROLS },
        );
        // scale bar not covered either
        const scale = await page.evaluate(() => {
          const e = document.querySelector('[data-testid=scale-bar]');
          const b = e.getBoundingClientRect();
          const hit = document.elementFromPoint(b.left + b.width / 2, b.top + b.height / 2);
          return !!hit && (hit === e || e.contains(hit) || hit.contains(e) || hit.closest('[data-testid=scale-bar], .scale'));
        });
        if (!scale) probs.push('scale bar covered');
        probs.push(...r.small.map((s) => `target < 44: ${s}`), ...r.overlaps.map((s) => `overlap: ${s}`), ...r.covered.map((s) => `covered: ${s}`));
        if (r.scroll.scrollH > r.scroll.clientH + 1 && !['auto', 'scroll'].includes(r.scroll.overflowY)) probs.push(`panel content does not scroll inside (${JSON.stringify(r.scroll)})`);
        if (r.scroll.pageScroll) probs.push('the page itself scrolls');
        if (r.scroll.bottom > r.scroll.vh + 0.5) probs.push('panel extends below the viewport');
        // scroll the turn list to the end inside the panel: last row reachable
        await tid(page, 'route-step').last().scrollIntoViewIfNeeded();
        const lastVisible = await page.evaluate(() => {
          const e = [...document.querySelectorAll('[data-testid=route-step]')].at(-1);
          const b = e.getBoundingClientRect();
          const p = document.querySelector('[data-testid=route-panel]').getBoundingClientRect();
          return b.bottom <= p.bottom + 1 && b.top >= p.top - 1;
        });
        if (!lastVisible) probs.push('last turn row not reachable by scrolling the panel');
        probs.push(...(await attributionProblems(page, 'attribution-osm', '© OpenStreetMap contributors')).map((s) => `after scroll: ${s}`));
        test.info().annotations.push({ type: `AC43 ${w}×${h} ${theme} ${lang}`, description: probs.length ? probs.join('; ') : `ok (panel scrollH ${r.scroll.scrollH} / clientH ${r.scroll.clientH})` });
        expect(probs, probs.join('\n')).toEqual([]);
      });
    }
  }
}

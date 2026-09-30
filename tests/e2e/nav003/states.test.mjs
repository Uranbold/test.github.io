// NAV-003 G. States: empty, no results, gateway down, offline, rate-limited, bad request (AC 31–39).
// Every failure is simulated in the browser (route interception, context.setOffline). The shared gateway is untouched.
import { test, expect } from '@playwright/test';
import {
  CORS, FX, REF, REVERSE_GLOB, SEARCH_GLOB, T, TILES_GLOB, attributionProblems, card, fc, feature, jumpTo, mock, openApp, options,
  rightClick, tid, typeQuery, view, waitSettled,
} from './helpers.mjs';

const stateText = (page) => page.locator('#search-state-text');
const GANDAN = [feature('Гандан', 106.895, 47.9215, { osm_key: 'place', osm_value: 'neighbourhood' }, 11)];

test('AC31: clearing (button or deleting all text) closes the list, aborts the request, sends nothing, shows no message; clear button only with text, focus back to input; card stays open', async ({ page }) => {
  const log = await mock(page, SEARCH_GLOB, (p) => (p.q === 'Зайсан' ? { delay: 1500, body: fc([feature('ХОЦОРСОН', 106.9, 47.9)]) } : { body: fc(GANDAN) }));
  await openApp(page);
  await expect(tid(page, 'search-clear')).toBeHidden();
  await typeQuery(page, 'Гандан');
  await expect(tid(page, 'search-clear')).toBeVisible();
  await expect(tid(page, 'search-clear')).toHaveAccessibleName(T.mn.clear);
  await waitSettled(page, 'Гандан');
  await tid(page, 'search-option').first().click();
  expect((await card(page)).open).toBe(true);
  // in-flight request, then clear button
  await typeQuery(page, 'Зайсан');
  await page.waitForFunction(() => window.__req.filter((r) => r.op === 'search').length === 2);
  await tid(page, 'search-clear').click();
  expect(await page.evaluate(() => document.activeElement?.dataset.testid)).toBe('search-input');
  await expect(tid(page, 'search-input')).toHaveValue('');
  await expect(tid(page, 'search-clear')).toBeHidden();
  await page.waitForTimeout(2000); // the delayed response would have arrived
  await expect(tid(page, 'search-popup')).toBeHidden();
  expect((await page.evaluate(() => window.__st.map((s) => s.first))).includes('ХОЦОРСОН')).toBe(false);
  expect(await page.evaluate(() => window.__req.filter((r) => r.op === 'search').at(-1).outcome)).toBe('AbortError');
  expect(log.length).toBe(2);
  expect((await card(page)).open, 'card not closed by clearing').toBe(true);
  // deleting all text
  await typeQuery(page, 'Га');
  await waitSettled(page, 'Га');
  await page.keyboard.press('Backspace');
  await page.keyboard.press('Backspace');
  await page.waitForTimeout(700);
  await expect(tid(page, 'search-popup')).toBeHidden();
  await expect(tid(page, 'search-state')).toBeHidden();
  expect(log.length).toBe(3);
});

test('AC32 (live): 200 with empty features ("xqzjwvk") shows «Илэрц олдсонгүй» (not an error), announced politely', async ({ page }) => {
  await openApp(page);
  await typeQuery(page, 'xqzjwvk');
  await waitSettled(page, 'xqzjwvk');
  await expect(tid(page, 'search-popup')).toHaveAttribute('data-state', 'no-results');
  await expect(stateText(page)).toHaveText(T.mn.noResults);
  await expect(tid(page, 'search-retry')).toBeHidden();
  await expect(tid(page, 'search-live')).toHaveAttribute('aria-live', 'polite');
  await expect(tid(page, 'search-live')).toHaveText(T.mn.noResults);
});

for (const kind of ['network', 'cors', '502', '503', '504', 'timeout']) {
  test(`AC33 ${kind}: «Хайлт түр ажиллахгүй байна» + «Дахин оролдох» within 8 s; retry re-sends once; nothing automatic; a keystroke searches again`, async ({ page }) => {
    test.setTimeout(60_000);
    let fail = true;
    const log = await mock(page, SEARCH_GLOB, () => {
      if (!fail) return { body: fc(GANDAN) };
      if (kind === 'network') return { abort: true };
      // A fulfilled response WITHOUT Access-Control-Allow-Origin is let through by Playwright interception (checked
      // 2026-09-30), so a real CORS failure is produced with a mismatched origin, which Chromium does enforce.
      if (kind === 'cors') return { status: 200, headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': 'http://nav003-other-origin.test' }, body: fc(GANDAN) };
      if (kind === 'timeout') return { hang: true };
      return { status: Number(kind), headers: CORS, body: JSON.stringify({ error: { code: 'UpstreamUnavailable' } }) };
    });
    await openApp(page);
    await typeQuery(page, 'Гандан');
    await expect(tid(page, 'search-popup')).toHaveAttribute('data-state', 'unavailable', { timeout: 9000 });
    const ms = await page.evaluate(() => {
      const r = window.__req.find((x) => x.op === 'search');
      return window.__st.find((s) => s.t >= r.t && s.popup === 'unavailable')?.t - r.t;
    });
    test.info().annotations.push({ type: `AC33 ${kind}`, description: `${ms.toFixed(0)} ms after the request start` });
    expect(ms).toBeLessThanOrEqual(8000 + 100);
    await expect(stateText(page)).toHaveText(T.mn.unavailable);
    await expect(tid(page, 'search-retry')).toBeVisible();
    await expect(tid(page, 'search-retry')).toHaveText(T.mn.retry);
    expect(log.length).toBe(1);
    await page.waitForTimeout(2500);
    expect(log.length, 'no automatic retry').toBe(1);
    // retry re-sends once (still failing -> one more request only)
    await tid(page, 'search-retry').click();
    await expect(tid(page, 'search-popup')).toHaveAttribute('data-state', 'unavailable', { timeout: 9500 });
    await page.waitForTimeout(kind === 'timeout' ? 500 : 1500);
    expect(log.length).toBe(2);
    // a new keystroke searches again after the debounce
    fail = false;
    await page.keyboard.type(' хийд');
    await waitSettled(page, 'Гандан хийд');
    expect(log.length).toBe(3);
    expect((await options(page)).length).toBe(1);
  });
}

test('AC34: offline -> no request, «Интернэт холболт алга» within 500 ms of the settled query (NAV-002 banner too); back online -> searched once within 2 s', async ({ page, context }) => {
  const log = await mock(page, SEARCH_GLOB, () => ({ body: fc(GANDAN) }));
  await openApp(page);
  await context.setOffline(true);
  await typeQuery(page, 'Гандан');
  await expect(tid(page, 'search-popup')).toHaveAttribute('data-state', 'offline', { timeout: 2000 });
  const r = await page.evaluate(() => {
    const k = window.__inputs.at(-1).t;
    return window.__st.find((s) => s.t >= k && s.popup === 'offline')?.t - k;
  });
  test.info().annotations.push({ type: 'AC34 offline message', description: `${r.toFixed(0)} ms after the last keystroke (settled at +250 ms)` });
  expect(r).toBeLessThanOrEqual(350 + 500);
  await expect(stateText(page)).toHaveText(T.mn.offline);
  await expect(tid(page, 'status-banner')).toBeVisible();
  await expect(tid(page, 'status-banner')).toHaveAttribute('data-reason', 'offline');
  await page.keyboard.type(' хийд');
  await page.waitForTimeout(800);
  expect(log.length).toBe(0);
  expect(await page.evaluate(() => window.__req.length)).toBe(0);
  const t0 = await page.evaluate(() => performance.now());
  await context.setOffline(false);
  await waitSettled(page, 'Гандан хийд', 3000);
  const t1 = await page.evaluate(() => window.__req.find((x) => x.op === 'search')?.t);
  expect(t1 - t0).toBeLessThanOrEqual(2000);
  await page.waitForTimeout(1500);
  expect(log.length).toBe(1);
  expect(log[0].p.q).toBe('Гандан хийд');
});

for (const variant of ['Retry-After: 3', 'no header (5 s)']) {
  test(`AC35 search 429 (${variant}): message, retry disabled, no request in the window whatever is typed, nothing automatic, next settled query sends one`, async ({ page }) => {
    test.setTimeout(60_000);
    const N = variant.startsWith('Retry') ? 3 : 5;
    let first = true;
    const log = await mock(page, SEARCH_GLOB, (p) => {
      if (first) {
        first = false;
        const headers = { ...CORS, 'Access-Control-Expose-Headers': 'Retry-After' };
        if (N === 3) headers['Retry-After'] = '3';
        return { status: 429, headers, body: JSON.stringify({ error: { code: 'RateLimited' } }) };
      }
      return { body: fc(GANDAN) };
    });
    await openApp(page);
    await typeQuery(page, 'Гандан');
    await expect(tid(page, 'search-popup')).toHaveAttribute('data-state', 'rate-limited', { timeout: 3000 });
    const t429 = await page.evaluate(() => window.__req[0].end);
    await expect(stateText(page)).toHaveText(T.mn.rateLimited);
    await expect(tid(page, 'search-retry')).toBeVisible();
    await expect(tid(page, 'search-retry')).toHaveAttribute('aria-disabled', 'true');
    // type during the window
    for (const s of [' х', 'и', 'йд', ' 2']) {
      await page.keyboard.type(s);
      await page.waitForTimeout(400);
    }
    await expect(tid(page, 'search-input')).toHaveValue('Гандан хийд 2');
    await tid(page, 'search-retry').click({ force: true }); // disabled: ignored
    const tNow = await page.evaluate(() => performance.now());
    const remaining = N * 1000 - (tNow - t429);
    expect(remaining, 'still inside the window').toBeGreaterThan(0);
    expect(log.length, 'no request inside the window').toBe(1);
    await page.waitForTimeout(remaining - 300);
    expect(log.length, 'no request inside the window (end)').toBe(1);
    await expect(tid(page, 'search-retry')).toHaveAttribute('aria-disabled', 'true');
    await page.waitForTimeout(1300);
    // after N s: enabled, nothing automatic
    await expect(tid(page, 'search-retry')).not.toHaveAttribute('aria-disabled', 'true');
    await page.waitForTimeout(1500);
    expect(log.length, 'nothing sent automatically after the window').toBe(1);
    // next keystroke -> one request after the debounce
    await page.keyboard.type('3');
    await waitSettled(page, 'Гандан хийд 23');
    await page.waitForTimeout(600);
    expect(log.length).toBe(2);
    expect(log[1].p.q).toBe('Гандан хийд 23');
  });
}

test('AC35: after the window, «Дахин оролдох» sends exactly one request', async ({ page }) => {
  let first = true;
  const log = await mock(page, SEARCH_GLOB, () => {
    if (first) {
      first = false;
      return { status: 429, headers: { ...CORS, 'Access-Control-Expose-Headers': 'Retry-After', 'Retry-After': '1' }, body: '{}' };
    }
    return { body: fc(GANDAN) };
  });
  await openApp(page);
  await typeQuery(page, 'Гандан');
  await expect(tid(page, 'search-popup')).toHaveAttribute('data-state', 'rate-limited', { timeout: 3000 });
  await expect(tid(page, 'search-retry')).not.toHaveAttribute('aria-disabled', 'true', { timeout: 3000 });
  expect(log.length).toBe(1);
  await tid(page, 'search-retry').click();
  await waitSettled(page, 'Гандан');
  await page.waitForTimeout(500);
  expect(log.length).toBe(2);
  expect((await view(page)).state).toBe('results');
});

test('AC36: reverse 429 Retry-After 3 -> message in the card, retry disabled 3 s, no reverse; after 3 s nothing until «Дахин оролдох» (one request)', async ({ page }) => {
  let first = true;
  const rev = await mock(page, REVERSE_GLOB, () => {
    if (first) {
      first = false;
      return { status: 429, headers: { ...CORS, 'Access-Control-Expose-Headers': 'Retry-After', 'Retry-After': '3' }, body: '{}' };
    }
    return { body: fc([FX.F9.feature]) };
  });
  await openApp(page);
  await jumpTo(page, REF.P1, 15);
  await rightClick(page, 900, 400);
  await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'rate-limited', { timeout: 3000 });
  expect((await card(page)).nearText).toContain(T.mn.rateLimited);
  await expect(tid(page, 'place-retry')).toHaveAttribute('aria-disabled', 'true');
  await tid(page, 'place-retry').click({ force: true });
  await page.waitForTimeout(2000);
  expect(rev.length).toBe(1);
  await expect(tid(page, 'place-retry')).not.toHaveAttribute('aria-disabled', 'true', { timeout: 2500 });
  await page.waitForTimeout(1500);
  expect(rev.length, 'nothing automatic').toBe(1);
  await tid(page, 'place-retry').click();
  await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'place', { timeout: 3000 });
  await page.waitForTimeout(500);
  expect(rev.length).toBe(2);
});

test('AC37: 400 on search and on reverse shows «Алдаа гарлаа» and is not retried', async ({ page }) => {
  const s = await mock(page, SEARCH_GLOB, () => ({ status: 400, headers: CORS, body: '{"message":"language not supported"}' }));
  const r = await mock(page, REVERSE_GLOB, () => ({ status: 400, headers: CORS, body: '{"message":"bad"}' }));
  await openApp(page);
  await typeQuery(page, 'Гандан');
  await expect(tid(page, 'search-popup')).toHaveAttribute('data-state', 'error', { timeout: 3000 });
  await expect(stateText(page)).toHaveText(T.mn.error);
  await expect(tid(page, 'search-retry')).toBeHidden();
  await page.waitForTimeout(2000);
  expect(s.length).toBe(1);
  await page.keyboard.press('Escape');
  await jumpTo(page, REF.P1, 15);
  await rightClick(page, 900, 400);
  await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'error', { timeout: 3000 });
  expect((await card(page)).nearText).toContain(T.mn.error);
  expect((await card(page)).retry).toBeNull();
  await page.waitForTimeout(2000);
  expect(r.length).toBe(1);
});

test('AC38: language switch changes a state message within 500 ms; an open list is re-requested once with the new lang; an open card keeps its name and switches labels', async ({ page }) => {
  let unavailable = true;
  const log = await mock(page, SEARCH_GLOB, (p) => (unavailable ? { status: 503, headers: CORS, body: '{}' } : { body: fc([{ ...FX.F9.feature, properties: { ...FX.F9.feature.properties, name: p.lang === 'en' ? 'Naran Department Store' : 'Наран их дэлгүүр' } }]) }));
  const rev = await mock(page, REVERSE_GLOB, (p) => ({ body: fc([{ ...FX.F6.feature, properties: { ...FX.F6.feature.properties, name: p.lang === 'en' ? 'Petrovis EN' : 'Петровис' } }]) }));
  await openApp(page);
  // state message
  await typeQuery(page, 'Наран');
  await expect(tid(page, 'search-popup')).toHaveAttribute('data-state', 'unavailable', { timeout: 3000 });
  const n0 = log.length;
  const t0 = await page.evaluate(() => performance.now());
  await tid(page, 'language-toggle').click();
  await expect(stateText(page)).toHaveText(T.en.unavailable, { timeout: 500 });
  const dt = await page.evaluate((t0) => window.__st.find((s) => s.t >= t0 && s.rowText === 'Search is temporarily unavailable')?.t - t0, t0);
  test.info().annotations.push({ type: 'AC38 message switch', description: `${dt?.toFixed(0)} ms` });
  expect(dt).toBeLessThanOrEqual(500);
  await expect(tid(page, 'search-retry')).toHaveText(T.en.retry);
  expect(log.length - n0, 'a state message is not re-requested').toBe(0);
  // open results list -> re-requested once with lang=mn
  unavailable = false;
  await tid(page, 'search-retry').click();
  await waitSettled(page, 'Наран');
  expect((await options(page))[0].name).toBe('Naran Department Store');
  expect((await options(page))[0].type).toBe('Bus stop');
  const n1 = log.length;
  await tid(page, 'language-toggle').click();
  await page.waitForTimeout(1200);
  expect(log.length - n1).toBe(1);
  expect(log.at(-1).p.lang).toBe('mn');
  expect((await options(page))[0].name).toBe('Наран их дэлгүүр');
  expect((await options(page))[0].type).toBe('Автобусны буудал');
  // open place card keeps its name, labels switch
  await tid(page, 'search-option').first().click();
  expect((await card(page)).title).toBe('Наран их дэлгүүр');
  await tid(page, 'language-toggle').click();
  await page.waitForTimeout(600);
  let c = await card(page);
  expect(c.title).toBe('Наран их дэлгүүр');
  expect(c.type).toBe('Bus stop');
  await expect(tid(page, 'place-card-close')).toHaveAccessibleName(T.en.close);
  // coordinate card: nearest name stays, label switches, no new reverse
  await tid(page, 'place-card-close').click();
  await rightClick(page, 900, 400);
  await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'place', { timeout: 3000 });
  expect((await card(page)).nearName).toBe('Petrovis EN');
  const r0 = rev.length;
  await tid(page, 'language-toggle').click();
  await page.waitForTimeout(600);
  c = await card(page);
  expect(c.title).toBe(T.mn.selectedPoint);
  expect(c.nearText.startsWith(T.mn.nearest)).toBe(true);
  expect(c.nearName).toBe('Petrovis EN');
  expect(rev.length).toBe(r0);
});

test('AC39: NAV-003 messages only in the list/card, never over the attribution; search works while tiles are unavailable; tiles load while search is unavailable; precedence unchanged', async ({ page, context }) => {
  test.setTimeout(90_000);
  await mock(page, SEARCH_GLOB, () => ({ body: fc(GANDAN) }));
  await openApp(page);
  // tiles-unavailable banner (3 consecutive tile errors)
  await page.route(TILES_GLOB, (r) => r.fulfill({ status: 503, body: 'x', headers: { 'Access-Control-Allow-Origin': '*' } }));
  for (const c of [[100, 46], [95, 49], [110, 44], [115, 47], [92, 45], [104, 43]]) {
    if (await tid(page, 'status-banner').isVisible()) break;
    await page.evaluate((c) => window.__nav002.map.jumpTo({ center: c, zoom: 10 }), c);
    await page.waitForTimeout(700);
  }
  await expect(tid(page, 'status-banner')).toHaveAttribute('data-reason', 'tiles');
  await typeQuery(page, 'Гандан');
  await waitSettled(page, 'Гандан');
  expect((await options(page)).length).toBe(1);
  expect(await attributionProblems(page)).toEqual([]);
  // the NAV-003 message sits inside the popup
  await page.unroute(TILES_GLOB);
  await page.unroute(SEARCH_GLOB);
  await mock(page, SEARCH_GLOB, () => ({ status: 503, headers: CORS, body: '{}' }));
  await typeQuery(page, 'Зайсан');
  await expect(tid(page, 'search-popup')).toHaveAttribute('data-state', 'unavailable', { timeout: 3000 });
  expect(await page.evaluate(() => document.querySelector('#search-state').closest('[data-testid=search-popup]') !== null)).toBe(true);
  expect(await attributionProblems(page)).toEqual([]);
  // tiles still load while search is unavailable: the banner clears on retry and tiles render
  await tid(page, 'banner-retry').click().catch(() => {});
  await jumpTo(page, REF.P1, 13);
  await expect(tid(page, 'status-banner')).toBeHidden({ timeout: 15_000 });
  expect(await page.evaluate(() => window.__nav002.map.areTilesLoaded())).toBe(true);
  // offline > tiles: offline banner wins while NAV-003 shows its own offline row in the list
  await context.setOffline(true);
  await typeQuery(page, 'Гандан 2');
  await expect(tid(page, 'search-popup')).toHaveAttribute('data-state', 'offline', { timeout: 2000 });
  await expect(tid(page, 'status-banner')).toHaveAttribute('data-reason', 'offline');
  expect(await attributionProblems(page)).toEqual([]);
  await context.setOffline(false);
});

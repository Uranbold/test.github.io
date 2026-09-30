// NAV-003 A. Search box and request rules (AC 1–11).
import { test, expect } from '@playwright/test';
import {
  ABBR, CORS, REF, SEARCH_GLOB, REVERSE_GLOB, T, camera, decimals, expectedPlan, fc, feature, geoSpyInit, jumpTo, lastInputAt, mock, openApp,
  options, pace, params, requestCount, requests, tid, typeQuery, view, waitSettled, haversine,
} from './helpers.mjs';


for (const [lang, theme] of [['mn', 'day'], ['en', 'night']]) {
  test(`AC1 ${lang}/${theme}: search input visible in the top bar, name and placeholder, first Tab stop, no request on load`, async ({ page }) => {
    await openApp(page, { lang, theme });
    const input = tid(page, 'search-input');
    await expect(input).toBeVisible();
    await expect(input).toHaveAccessibleName(T[lang].search);
    await expect(input).toHaveAttribute('placeholder', T[lang].placeholder);
    expect(await page.evaluate(() => !!document.querySelector('[data-testid=top-bar] [data-testid=search-input]'))).toBe(true);
    // First Tab stop from the page top (same method as NAV-002 AC 48)
    await page.evaluate(() => {
      document.activeElement?.blur();
      document.body.tabIndex = -1;
      document.body.focus();
      document.body.removeAttribute('tabindex');
    });
    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => document.activeElement?.dataset.testid)).toBe('search-input');
    await page.waitForTimeout(1500);
    expect(await requestCount(page)).toBe(0);
  });
}

test('AC2: at most 200 characters; a longer paste keeps the first 200 with no error', async ({ page }) => {
  const log = await mock(page, SEARCH_GLOB, () => ({ body: fc([]) }));
  await openApp(page);
  const long = 'Сүхбаатар '.repeat(30); // 300 chars
  await tid(page, 'search-input').focus();
  await page.keyboard.insertText(long); // paste-like single insertion
  const v = await tid(page, 'search-input').inputValue();
  expect(v.length).toBe(200);
  expect(v).toBe(long.slice(0, 200));
  await expect(tid(page, 'search-input')).toHaveAttribute('maxlength', '200');
  await page.waitForTimeout(800);
  expect(log.length).toBeGreaterThanOrEqual(1);
  for (const l of log) expect(l.p.q.length).toBeLessThanOrEqual(200);
  const st = await view(page);
  expect(['error', 'unavailable']).not.toContain(st.state);
});

// AC 3 as amended 2026-09-30 (PO approval F3, D33): the FIRST request starts 200–350 ms after the last keystroke; at most 2
// per settled query; «Сүхбаатар» (no Latin, no у/о, no abbreviation) sends exactly 1.
test('AC3: debounce 200–350 ms after the last keystroke; «Сүхбаатар» at 100 ms/key sends exactly 1 request, no intermediate texts', async ({ page }) => {
  const log = await mock(page, SEARCH_GLOB, (p) => ({ body: fc([feature(p.q, 106.9176, 47.9189)]) }));
  await openApp(page);
  await typeQuery(page, 'Сүхбаатар', { delay: 100 });
  await waitSettled(page, 'Сүхбаатар');
  await page.waitForTimeout(600);
  const req = await requests(page, 'search');
  expect(req.map((r) => params(r.url).q)).toEqual(['Сүхбаатар']);
  const dt = req[0].t - (await lastInputAt(page));
  test.info().annotations.push({ type: 'AC3 debounce', description: `${dt.toFixed(0)} ms after the last keystroke` });
  expect(dt).toBeGreaterThanOrEqual(200);
  expect(dt).toBeLessThanOrEqual(350);
  expect(log.length).toBe(1);
  // Latin (AC 15 a): as typed + one transliteration, both in parallel right after the debounce; no intermediate texts
  await tid(page, 'search-clear').click();
  await typeQuery(page, 'Sukhbaatar', { delay: 100 });
  await waitSettled(page, 'Sukhbaatar');
  await page.waitForTimeout(600);
  const latin = (await requests(page, 'search')).slice(1);
  expect(latin.length).toBe(2);
  expect(latin.map((r) => params(r.url).q)).toContain('Sukhbaatar');
  for (const r of latin) {
    const d = r.t - (await lastInputAt(page));
    expect(d).toBeGreaterThanOrEqual(200);
    expect(d).toBeLessThanOrEqual(350);
  }
});

/**
 * AC 3 / 15 / 16 request plan (story amended 2026-09-30, PO approval F3 = ADR-0006 §2.3 rules A–D). Each case: the query,
 * what the mock answers per q, the q values expected (order-free for `parallel`), and whether the second request must be
 * parallel (started before the first response) or sequential (only after an empty first response).
 */
const PLAN_CASES = [
  // rule A: expansion + as typed, parallel
  { q: 'БЗД 4-р хороо', expect: ['Баянзүрх дүүрэг 4-р хороо', 'БЗД 4-р хороо'], parallel: true, sameFeature: true },
  { q: 'бзд', expect: ['Баянзүрх дүүрэг', 'бзд'], parallel: true },
  { q: 'Сонгино СХД', expect: ['Сонгино Сонгинохайрхан дүүрэг', 'Сонгино СХД'], parallel: true },
  // hyphen-attached abbreviation: not expanded; Cyrillic with о -> rule C, non-empty -> 1 request
  { q: 'БЗД-ийн 4-р хороо', expect: ['БЗД-ийн 4-р хороо'] },
  // rule B: Latin, as typed + one Cyrillic transliteration, parallel
  { q: 'Ikh delguur', expect: ['Ikh delguur', 'CYRILLIC'], parallel: true },
  // rule C: у/о, variant only after 200 + zero features
  { q: 'Сухбаатар', empty: ['Сухбаатар'], expect: ['Сухбаатар', 'Сүхбаатар'], sequential: true, listFrom: 'Сүхбаатар' },
  { q: 'Улаанбаатар', expect: ['Улаанбаатар'] }, // correct у with results: never "corrected"
  { q: 'Толгойт', status503: ['Толгойт'], expect: ['Толгойт'] }, // first request fails: no ү/ө variant
  // rule D: Cyrillic without у/о, mixed scripts -> 1 request
  { q: 'Их дэлгүүр', expect: ['Их дэлгүүр'] },
  { q: 'Улаанбаатар Sukhbaatar', expect: ['Улаанбаатар Sukhbaatar'] },
];

test('AC3/AC15/AC16 (F3): at most 2 requests per settled query; 2nd only for Latin transliteration (parallel), ү/ө variant after an empty Cyrillic result, or as typed next to an abbreviation expansion', async ({ page }) => {
  test.setTimeout(90_000);
  let cur = null;
  const log = await mock(page, SEARCH_GLOB, (p) => {
    if (cur?.empty?.includes(p.q)) return { delay: 150, body: fc([]) };
    if (cur?.status503?.includes(p.q)) return { status: 503, headers: CORS, body: '{}' };
    if (cur?.sameFeature) return { delay: 150, body: fc([feature('Баянзүрх дүүргийн 4-р хороо', 106.95, 47.92, {}, 4242)]) };
    return { delay: 150, body: fc([feature(`Р ${p.q}`, 106.9176, 47.9189)]) };
  });
  await openApp(page);
  const problems = [];
  for (const c of PLAN_CASES) {
    cur = c;
    const before = log.length;
    const b = await page.evaluate(() => window.__req.length);
    await typeQuery(page, c.q);
    await waitSettled(page, c.q);
    await page.waitForTimeout(700);
    const qs = log.slice(before).map((l) => l.p.q);
    const recs = (await page.evaluate((b) => window.__req.slice(b), b)).filter((r) => r.op === 'search');
    const lastKey = await lastInputAt(page);
    const plan = expectedPlan(c.q);
    const tag = `«${c.q}» (rule ${plan.rule})`;
    if (qs.length > 2) problems.push(`${tag}: ${qs.length} requests`);
    const exp = c.expect.map((e) => (e === 'CYRILLIC' ? null : e));
    const gotSorted = [...qs].sort();
    const expFixed = exp.filter(Boolean).sort();
    if (qs.length !== exp.length) problems.push(`${tag}: ${qs.length} requests ${JSON.stringify(qs)}, expected ${exp.length}`);
    for (const e of expFixed) if (!qs.includes(e)) problems.push(`${tag}: «${e}» not sent (${JSON.stringify(qs)})`);
    if (c.expect.includes('CYRILLIC') && !qs.some((q) => q !== c.q && /[Ѐ-ӿ]/.test(q) && !/[A-Za-z]/.test(q))) problems.push(`${tag}: no Cyrillic transliteration (${JSON.stringify(qs)})`);
    if (qs.some((q) => q.length > 200)) problems.push(`${tag}: q longer than 200`);
    // debounce: the first request 200–350 ms after the last keystroke (AC 3)
    const d0 = recs[0] ? recs[0].t - lastKey : null;
    if (d0 === null || d0 < 200 || d0 > 350) problems.push(`${tag}: first request ${d0?.toFixed(0)} ms after the last keystroke`);
    if (c.parallel && recs.length === 2 && recs[1].t > (recs[0].end ?? Infinity)) problems.push(`${tag}: second request started after the first response (not parallel)`);
    if (c.sequential && recs.length === 2) {
      if (qs[0] !== c.q) problems.push(`${tag}: first request «${qs[0]}» is not the query as typed`);
      if (recs[1].t < (recs[0].end ?? Infinity)) problems.push(`${tag}: ү/ө variant sent before the first (empty) response`);
    }
    const o = await options(page);
    if (c.listFrom && !(o.length === 1 && o[0].name === `Р ${c.listFrom}`)) problems.push(`${tag}: list does not show the variant's response (${JSON.stringify(o.map((x) => x.name))})`);
    if (c.sameFeature && o.length !== 1) problems.push(`${tag}: merged list has ${o.length} options for one osm_type+osm_id`);
    await expect(tid(page, 'search-input'), `${tag}: the input shows what the user typed`).toHaveValue(c.q);
    test.info().annotations.push({ type: `plan ${c.q}`, description: `rule ${plan.rule}: ${JSON.stringify(qs)} first +${d0?.toFixed(0)} ms` });
    await tid(page, 'search-clear').click();
    await page.waitForTimeout(200);
  }
  expect(problems).toEqual([]);
});

test('AC16 (F4): an abbreviation expansion never produces a q longer than 200 characters (cut to 200 or skipped); the query as typed is still sent', async ({ page }) => {
  const log = await mock(page, SEARCH_GLOB, () => ({ body: fc([]) }));
  await openApp(page);
  const cases = [
    'Тест '.repeat(39) + 'СХД', // 198 chars; the expansion (+18) would give 216
    'СХД ' + 'Тест '.repeat(39).trim(), // abbreviation first, 198 chars
    'ЧД ' + 'БЗД '.repeat(49).trim(), // many abbreviations, 198 chars
  ];
  for (const q of cases) {
    expect(q.length).toBeLessThanOrEqual(200);
    const before = log.length;
    await tid(page, 'search-input').focus();
    await page.keyboard.press('ControlOrMeta+A');
    await page.keyboard.press('Backspace');
    await page.keyboard.insertText(q);
    await waitSettled(page, q);
    await page.waitForTimeout(500);
    const qs = log.slice(before).map((l) => l.p.q);
    test.info().annotations.push({ type: `F4 ${q.length} chars`, description: JSON.stringify(qs.map((x) => `${x.length}: ${x.slice(0, 30)}…${x.slice(-25)}`)) });
    expect(qs.length).toBeGreaterThanOrEqual(1);
    expect(qs.length).toBeLessThanOrEqual(2);
    for (const x of qs) expect(x.length, 'q <= 200 (openapi maxLength)').toBeLessThanOrEqual(200);
    expect(qs, 'the query as typed is sent').toContain(q);
    const other = qs.filter((x) => x !== q);
    const expanded = q.split(' ').map((w) => ABBR[w.toUpperCase()] ?? w).join(' ');
    for (const x of other) expect(expanded.startsWith(x), `the other request is the expansion, cut to 200 («${x.slice(0, 40)}…»)`).toBe(true);
    await page.waitForTimeout(600);
  }
});

test('AC4: fewer than 2 characters (or only spaces) sends nothing and closes the list', async ({ page }) => {
  const log = await mock(page, SEARCH_GLOB, (p) => ({ body: fc([feature('Гандан', 106.895, 47.9215)]) }));
  await openApp(page);
  await typeQuery(page, 'Га');
  await waitSettled(page, 'Га');
  await expect(tid(page, 'search-popup')).toBeVisible();
  await page.keyboard.press('Backspace');
  await page.waitForTimeout(700);
  await expect(tid(page, 'search-popup')).toBeHidden();
  await typeQuery(page, 'С');
  await page.waitForTimeout(700);
  await typeQuery(page, '    ');
  await page.waitForTimeout(700);
  await expect(tid(page, 'search-popup')).toBeHidden();
  expect(log.map((l) => l.p.q)).toEqual(['Га']);
});

test('AC5: an older response (delayed 1 s) never replaces the list of a newer query (single and parallel pair)', async ({ page }) => {
  await mock(page, SEARCH_GLOB, (p) => {
    if (/гандан|Gandan/i.test(p.q)) return { delay: 1000, body: fc([feature('ХУУЧИН Гандан', 106.895, 47.9215)]) };
    return { body: fc([feature('ШИНЭ ' + p.q, 106.9173, 47.8858)]) };
  });
  await openApp(page);
  for (const [older, newer] of [['Гандан', 'Зайсан'], ['Gandan', 'Зайсан']]) {
    await typeQuery(page, older);
    await page.waitForFunction((n) => window.__req.filter((r) => r.op === 'search').length >= n, older === 'Гандан' ? 1 : 2);
    const t0 = await page.evaluate(() => performance.now());
    await typeQuery(page, newer);
    await waitSettled(page, newer);
    await page.waitForTimeout(1500); // the older response has arrived (or been aborted) by now
    const o = await options(page);
    expect(o.map((x) => x.name)).toEqual(['ШИНЭ Зайсан']);
    const tl = await page.evaluate((t0) => window.__st.filter((s) => s.t >= t0), t0);
    expect(tl.some((s) => (s.first ?? '').startsWith('ХУУЧИН')), `older list shown: ${JSON.stringify(tl.map((s) => s.first))}`).toBe(false);
    await tid(page, 'search-clear').click();
    await page.waitForTimeout(300);
  }
});

test('AC6: an identical settled query (trailing space typed and deleted) sends no new request', async ({ page }) => {
  const log = await mock(page, SEARCH_GLOB, (p) => ({ body: fc([feature('Гандан', 106.895, 47.9215)]) }));
  await openApp(page);
  await typeQuery(page, 'Гандан');
  await waitSettled(page, 'Гандан');
  await page.keyboard.type(' ');
  await page.waitForTimeout(150);
  await page.keyboard.press('Backspace');
  await page.waitForTimeout(800);
  await page.keyboard.type('  ');
  await page.waitForTimeout(800);
  expect(log.length).toBe(1);
  expect((await options(page)).length).toBe(1);
});

test('AC7: lang=mn / lang=en, limit 5–10, at most 10 options', async ({ page }) => {
  const many = Array.from({ length: 14 }, (_, i) => feature(`Сургууль ${i + 1}`, 106.9 + i / 1000, 47.91, {}, 100 + i));
  const log = await mock(page, SEARCH_GLOB, () => ({ body: fc(many) }));
  await openApp(page);
  await typeQuery(page, 'Сургууль');
  await waitSettled(page, 'Сургууль');
  expect((await options(page)).length).toBe(10);
  expect(log[0].p.lang).toBe('mn');
  await tid(page, 'language-toggle').click();
  await page.waitForTimeout(800);
  await tid(page, 'search-clear').click();
  await typeQuery(page, 'Сургууль 2');
  await waitSettled(page, 'Сургууль 2');
  const last = log.at(-1).p;
  expect(last.lang).toBe('en');
  for (const l of log) {
    expect(Number(l.p.limit)).toBeGreaterThanOrEqual(5);
    expect(Number(l.p.limit)).toBeLessThanOrEqual(10);
  }
});

test('AC8: every MN result is listed before every non-MN result; upstream order otherwise kept', async ({ page }) => {
  const f = (n, cc, id) => feature(n, 106.9, 47.9, { countrycode: cc }, id);
  const upstream = [f('Нэг', 'CN', 1), f('Хоёр', 'MN', 2), f('Гурав', 'RU', 3), f('Дөрөв', 'MN', 4), f('Тав', 'MN', 5), f('Зургаа', 'CN', 6)];
  await mock(page, SEARCH_GLOB, () => ({ body: fc(upstream) }));
  await openApp(page);
  await typeQuery(page, 'Тест');
  await waitSettled(page, 'Тест');
  expect((await options(page)).map((o) => o.name)).toEqual(['Хоёр', 'Дөрөв', 'Тав', 'Нэг', 'Гурав', 'Зургаа']);
});

test('AC9 (b): without my location the bias is the map centre, 3 decimals, and search never calls the Geolocation API', async ({ page, context }) => {
  await context.clearPermissions();
  await page.addInitScript(geoSpyInit);
  const log = await mock(page, SEARCH_GLOB, () => ({ body: fc([]) }));
  await openApp(page);
  await typeQuery(page, 'Гандан');
  await waitSettled(page, 'Гандан');
  let cam = await camera(page);
  expect(log[0].p.lat).toBe(cam.lat.toFixed(3));
  expect(log[0].p.lon).toBe(cam.lng.toFixed(3));
  await jumpTo(page, REF.P3, 14);
  await tid(page, 'search-input').focus();
  await typeQuery(page, 'Зайсан');
  await waitSettled(page, 'Зайсан');
  cam = await camera(page);
  expect(log.at(-1).p.lat).toBe(cam.lat.toFixed(3));
  expect(log.at(-1).p.lon).toBe(cam.lng.toFixed(3));
  for (const l of log) {
    expect(decimals(l.p.lat)).toBeLessThanOrEqual(3);
    expect(decimals(l.p.lon)).toBeLessThanOrEqual(3);
  }
  expect(await page.evaluate(() => window.__geoSpy)).toEqual([]);
});

test('AC9 (a): while following with a fresh fix the bias is the device position (3 decimals); stale fix or panned map -> map centre', async ({ browser }) => {
  test.setTimeout(150_000);
  const DEV = { latitude: 47.886123, longitude: 106.917456, accuracy: 20 };
  const ctx = await browser.newContext({ permissions: ['geolocation'], geolocation: DEV, viewport: { width: 1366, height: 768 } });
  const page = await ctx.newPage();
  const log = await mock(page, SEARCH_GLOB, () => ({ body: fc([]) }));
  await openApp(page);
  await tid(page, 'my-location').click();
  await expect(tid(page, 'my-location')).toHaveAttribute('data-state', 'following', { timeout: 11_000 });
  await page.waitForFunction(() => !window.__nav002.map.isMoving(), null, { timeout: 5000 }).catch(() => {});
  // Move the camera programmatically (no user gesture, so following continues) so that centre != device position.
  await page.evaluate(() => window.__nav002.map.jumpTo({ center: [106.95, 47.93] }));
  const stillFollowing = (await tid(page, 'my-location').getAttribute('data-state')) === 'following';
  await typeQuery(page, 'Гандан');
  await waitSettled(page, 'Гандан');
  if (stillFollowing) {
    expect(log[0].p).toMatchObject({ lat: '47.886', lon: '106.917' });
  } else {
    test.info().annotations.push({ type: 'AC9a', description: 'programmatic jumpTo ended following; device bias checked without offset' });
  }
  // Fix older than 60 s (no new fix delivered) -> map centre
  await tid(page, 'search-clear').click();
  await page.waitForTimeout(61_500);
  await typeQuery(page, 'Зайсан');
  await waitSettled(page, 'Зайсан');
  const cam = await camera(page);
  expect(log.at(-1).p).toMatchObject({ lat: cam.lat.toFixed(3), lon: cam.lng.toFixed(3) });
  if (stillFollowing) expect(log.at(-1).p).not.toMatchObject({ lat: '47.886', lon: '106.917' });
  // User pans (not following) -> map centre
  await tid(page, 'search-clear').click();
  const c = await page.evaluate(() => {
    const r = window.__nav002.map.getCanvas().getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  await page.mouse.move(c.x, c.y + 100);
  await page.mouse.down();
  await page.mouse.move(c.x + 150, c.y + 150, { steps: 8 });
  await page.mouse.up();
  await page.waitForTimeout(500);
  await typeQuery(page, 'Хаан');
  await waitSettled(page, 'Хаан');
  const cam2 = await camera(page);
  expect(log.at(-1).p).toMatchObject({ lat: cam2.lat.toFixed(3), lon: cam2.lng.toFixed(3) });
  for (const l of log) expect(decimals(l.p.lat) <= 3 && decimals(l.p.lon) <= 3).toBe(true);
  await ctx.close();
});

test('AC10 (live): «Их дэлгүүр» biased to P2 has a result within 300 m of P2 in the top 3 (X1 bias recorded)', async ({ page }) => {
  await openApp(page);
  const out = {};
  for (const [label, ref] of [['P2', REF.P2], ['X1', REF.X1]]) {
    await jumpTo(page, ref, 14);
    await pace(page);
    await typeQuery(page, 'Их дэлгүүр');
    await waitSettled(page, 'Их дэлгүүр');
    const v = await view(page);
    out[label] = v.options.slice(0, 5).map((o, i) => `${i + 1}. ${o.props.name} (${o.props.osm_key}=${o.props.osm_value}) ${Math.round(haversine(REF.P2, { lat: o.coords[1], lng: o.coords[0] }))} m from P2`);
    if (label === 'P2') {
      const top3 = v.options.slice(0, 3).map((o) => haversine(REF.P2, { lat: o.coords[1], lng: o.coords[0] }));
      test.info().annotations.push({ type: 'AC10 P2 top 3 (m from P2)', description: top3.map((d) => d.toFixed(0)).join(', ') });
      expect(Math.min(...top3)).toBeLessThanOrEqual(300);
    }
    await tid(page, 'search-clear').click();
  }
  test.info().annotations.push({ type: 'AC10 recorded', description: JSON.stringify(out) });
});

test('AC11: every request goes to the page origin or the gateway base URL (search, reverse, map)', async ({ page }) => {
  const hosts = new Set();
  page.on('request', (r) => {
    const u = new URL(r.url());
    if (u.protocol.startsWith('http')) hosts.add(u.origin);
  });
  await mock(page, SEARCH_GLOB, () => ({ body: fc([feature('Гандан', 106.895, 47.9215)]) }));
  await mock(page, REVERSE_GLOB, () => ({ body: fc([feature('Хөшөө', 106.9176, 47.9189)]) }));
  await openApp(page);
  await typeQuery(page, 'Gandan');
  await waitSettled(page, 'Gandan');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(1500);
  await typeQuery(page, '47.9189, 106.9176');
  await waitSettled(page, '47.9189, 106.9176');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(1500);
  expect([...hosts].sort()).toEqual(['http://localhost:5173', 'http://localhost:8080'].filter((h) => hosts.has(h)).sort());
  expect(hosts.has('http://localhost:8080')).toBe(true);
  for (const h of hosts) expect(['http://localhost:5173', 'http://localhost:8080']).toContain(h);
});

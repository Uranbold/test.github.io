// NAV-004 H. Keyboard and screen reader: AC 44–47.
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { REF, T, fmtDistance, fmtDuration, lives, mockReverse, mockRoute, nb, openApp, openPreview, panel, routeReqs, setField, simpleBody, tid, waitFinal } from './helpers.mjs';

const TWO = [
  { distance: 4300, duration: 720 },
  { distance: 5200, duration: 900 },
];
const ok = (b, specs = TWO) => ({ body: simpleBody({ lat: b.locations[0].lat, lng: b.locations[0].lon }, { lat: b.locations[1].lat, lng: b.locations[1].lon }, specs) });

async function axe(page) {
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  return r.violations.filter((v) => ['serious', 'critical'].includes(v.impact)).map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(' ')).slice(0, 4).join(' | ')}`);
}

function lum(rgb) {
  const m = rgb.match(/\d+(\.\d+)?/g).map(Number);
  const [r, g, b] = m.slice(0, 3).map((v) => v / 255).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
const ratio = (a, b) => {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
};

for (const [theme, lang] of [['day', 'mn'], ['night', 'en'], ['day', 'en'], ['night', 'mn']]) {
  test(`AC44 ${theme} ${lang}: axe-core 0 serious/critical in every section F state, with a route and the turn list; non-text indicators >= 3:1`, async ({ page, context }) => {
    test.setTimeout(150_000);
    const plan = [
      ['route', (b) => ok(b)],
      ['loading', (b) => ({ ...ok(b), delay: 6000 })],
      ['no-route', () => ({ status: 400, body: { code: 'NoRoute' } })],
      ['out-of-area', () => ({ status: 400, body: { code: 'NoSegment' } })],
      ['unavailable', () => ({ status: 502, body: { code: 'UpstreamUnavailable' } })],
      ['error', () => ({ status: 413, body: {} })],
      ['too-far', () => ({ status: 400, body: { code: 'DistanceExceeded' } })],
      ['rate-limited', () => ({ status: 429, body: { code: 'RateLimited' }, headers: { 'Retry-After': '60' } })],
    ];
    let step = 0;
    await mockReverse(page);
    await mockRoute(page, (b) => plan[Math.min(step, plan.length - 1)][1](b));
    await openApp(page, { theme, lang });
    await openPreview(page, REF.P3);
    await setField(page, 'origin', REF.P1);
    await waitFinal(page);
    const out = {};
    // non-text indicators on the route state: selected tab indicator, selected radio, focus ring
    const nonText = await page.evaluate(() => {
      const bg = getComputedStyle(document.querySelector('[data-testid=route-panel]')).backgroundColor;
      const tab = document.querySelector('[role=tab][aria-selected=true]');
      const ind = getComputedStyle(tab, '::after').backgroundColor;
      const radio = document.querySelector('[data-testid=route-option][aria-checked=true] .radio');
      const radioC = radio ? getComputedStyle(radio).borderColor : null;
      return { bg, ind, radioC };
    });
    await tid(page, 'route-tab-car').focus();
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Tab'); // keyboard focus → :focus-visible
    const ring = await page.evaluate(() => getComputedStyle(document.activeElement).outlineColor);
    const ratios = { tabIndicator: ratio(nonText.ind, nonText.bg), radio: nonText.radioC ? ratio(nonText.radioC, nonText.bg) : null, focusRing: ratio(ring, nonText.bg) };
    test.info().annotations.push({ type: `AC44 non-text ${theme}`, description: JSON.stringify({ ...nonText, ring, ratios: Object.fromEntries(Object.entries(ratios).map(([k, v]) => [k, v?.toFixed(2)])) }) });
    expect(ratios.tabIndicator).toBeGreaterThanOrEqual(3);
    expect(ratios.radio).toBeGreaterThanOrEqual(3);
    expect(ratios.focusRing).toBeGreaterThanOrEqual(3);
    out.route = await axe(page);
    // turn list activated (manoeuvre point shown)
    await tid(page, 'route-step').nth(1).click();
    await page.waitForTimeout(1200);
    out['route+step'] = await axe(page);
    for (step = 1; step < plan.length; step++) {
      const name = plan[step][0];
      if (name === 'too-far') {
        await tid(page, 'route-tab-walk').click();
      } else {
        await tid(page, 'route-swap').click();
      }
      if (name === 'loading') await expect.poll(async () => (await panel(page)).state, { timeout: 4000 }).toBe('loading');
      else await expect.poll(async () => (await panel(page)).state, { timeout: 8000 }).toBe(name);
      out[name] = await axe(page);
      if (name === 'loading') await waitFinal(page);
    }
    // offline and same point (no request needed)
    await context.setOffline(true);
    await tid(page, 'route-swap').click();
    await expect.poll(async () => (await panel(page)).state).toBe('offline');
    out.offline = await axe(page);
    const dest = await page.evaluate(() => window.__nav004.route.controller.destination.point); // after the swaps
    await setField(page, 'origin', { lat: dest.lat + 2 / 111_195, lng: dest.lon });
    await expect.poll(async () => (await panel(page)).state).toBe('same-point');
    out['same-point'] = await axe(page);
    await context.setOffline(false);
    const bad = Object.entries(out).filter(([, v]) => v.length);
    test.info().annotations.push({ type: `AC44 axe ${theme} ${lang}`, description: Object.entries(out).map(([k, v]) => `${k}: ${v.length ? v.join('; ') : '0'}`).join(' | ') });
    expect(bad, JSON.stringify(bad, null, 1)).toEqual([]);
  });
}

test('AC45: Tab order while the panel is open follows the screen spec; every stop has a visible focus indicator; hidden search field and card are skipped', async ({ page }) => {
  await mockReverse(page);
  await mockRoute(page, (b) => ok(b));
  await openApp(page);
  await openPreview(page, REF.P3);
  await setField(page, 'origin', REF.P1);
  await waitFinal(page);
  // Start at the first control of the page: Shift+Tab from it leaves the page content (nothing before it)
  await tid(page, 'language-toggle').focus();
  await page.keyboard.press('Shift+Tab');
  const before = await page.evaluate(() => document.activeElement?.dataset?.testid ?? document.activeElement?.tagName);
  test.info().annotations.push({ type: 'AC45 Shift+Tab from the language button', description: String(before) });
  expect(before, 'the language button is the first Tab stop while the panel is open').toBe('BODY');
  const seq = [];
  const noRing = [];
  for (let i = 0; i < 24; i++) {
    await page.keyboard.press('Tab');
    const info = await page.evaluate(() => {
      const a = document.activeElement;
      const id = a?.dataset?.testid ?? (a?.classList.contains('maplibregl-canvas') ? 'map-canvas' : a?.closest('[data-testid]')?.dataset.testid ?? a?.tagName);
      const els = [a, a?.parentElement, a?.closest('.rfield')].filter(Boolean);
      const ring = els.some((e) => {
        const cs = getComputedStyle(e);
        return (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) >= 1) || (cs.boxShadow && cs.boxShadow !== 'none');
      });
      return { id, ring };
    });
    seq.push(info.id);
    if (!info.ring) noRing.push(info.id);
    if (info.id === 'attribution-osm') break;
  }
  test.info().annotations.push({ type: 'AC45 Tab order', description: seq.join(' → ') });
  const expected = ['language-toggle', 'theme-toggle', 'compass', 'route-close', 'route-origin', 'route-destination', 'route-swap', 'route-tab-car', 'route-avoid', 'route-option', 'route-step', 'map-canvas'];
  expect(seq.slice(0, expected.length)).toEqual(expected);
  const tail = seq.slice(expected.length);
  const order = ['zoom-in', 'zoom-out', 'my-location', 'attribution-osm'];
  expect(tail.filter((x) => order.includes(x))).toEqual(order);
  expect(seq).not.toContain('search-input');
  expect(seq.filter((x) => x === 'route-step').length, 'turn list is one Tab stop').toBe(1);
  test.info().annotations.push({ type: 'AC45 stops without a detected focus indicator', description: noRing.join(', ') || 'none' });
  expect(noRing.filter((x) => x.startsWith('route-')), 'NAV-004 stops with no visible focus indicator').toEqual([]);
});

test('AC45: tabs pattern (tablist «Зорчих хэлбэр», ArrowLeft/Right wrap, Home/End); turn list rows reachable with ArrowUp/Down/Home/End (roving tabindex) and activatable', async ({ page }) => {
  await mockReverse(page);
  await mockRoute(page, (b) => ok(b, [{ distance: 4300, duration: 720, steps: [
    { maneuver: { type: 'depart', bearing_after: 90 }, name: 'А', distance: 200 },
    { maneuver: { type: 'turn', modifier: 'left' }, name: 'Б', distance: 300 },
    { maneuver: { type: 'turn', modifier: 'right' }, name: '', distance: 400 },
    { maneuver: { type: 'arrive' }, name: '' },
  ] }]));
  await openApp(page);
  await openPreview(page, REF.P3);
  await setField(page, 'origin', REF.P1);
  await waitFinal(page);
  const tablist = page.locator('[role=tablist]');
  expect(await tablist.getAttribute('aria-label')).toBe(T.mn.modes);
  expect(await page.locator('[role=tablist] [role=tab]').count()).toBe(3);
  const sel = () => page.evaluate(() => [document.activeElement?.dataset.testid, document.querySelector('[role=tab][aria-selected=true]')?.dataset.testid]);
  await tid(page, 'route-tab-car').focus();
  await page.keyboard.press('ArrowLeft');
  await expect.poll(sel).toEqual(['route-tab-bike', 'route-tab-bike']);
  await page.keyboard.press('ArrowRight');
  await expect.poll(sel).toEqual(['route-tab-car', 'route-tab-car']);
  await page.keyboard.press('End');
  await expect.poll(sel).toEqual(['route-tab-bike', 'route-tab-bike']);
  await page.keyboard.press('Home');
  await expect.poll(sel).toEqual(['route-tab-car', 'route-tab-car']);
  const tabIdx = await page.evaluate(() => [...document.querySelectorAll('[role=tab]')].map((t) => t.tabIndex));
  expect(tabIdx).toEqual([0, -1, -1]);
  await page.waitForTimeout(800);
  await waitFinal(page);
  // turn list
  await tid(page, 'route-step').first().focus();
  const cur = () => page.evaluate(() => document.activeElement?.dataset.index);
  await page.keyboard.press('ArrowDown');
  await expect.poll(cur).toBe('1');
  await page.keyboard.press('End');
  await expect.poll(cur).toBe('3');
  await page.keyboard.press('ArrowDown');
  await expect.poll(cur, 'no wrapping').toBe('3');
  await page.keyboard.press('Home');
  await expect.poll(cur).toBe('0');
  await page.keyboard.press('ArrowDown');
  const ti = await page.evaluate(() => [...document.querySelectorAll('[data-testid=route-step]')].map((b) => b.tabIndex));
  expect(ti).toEqual([-1, 0, -1, -1]);
  const n0 = (await routeReqs(page)).length;
  await page.keyboard.press('Enter');
  await page.waitForTimeout(1300);
  expect((await routeReqs(page)).length, 'activating a row sends nothing').toBe(n0);
  expect(await page.evaluate(() => document.activeElement?.dataset.index), 'focus stays on the row').toBe('1');
  const step = await page.evaluate(() => window.__nav002.map.getSource('nav-route-step')?.serialize().data.features.length);
  expect(step, 'manoeuvre point shown').toBe(1);
});

test('AC47: a result announces once «{count} маршрут олдлоо, distance, duration, Хүрэх цаг HH:MM» within 1 s; selecting another route announces its summary once; a state announces its message; loading is not announced', async ({ page }) => {
  await mockReverse(page);
  let mode = 'ok';
  await mockRoute(page, (b) => (mode === 'ok' ? ok(b) : mode === 'slow' ? { status: 400, body: { code: 'NoRoute' }, delay: 1500 } : ok(b)));
  await openApp(page);
  await openPreview(page, REF.P3);
  await setField(page, 'origin', REF.P1);
  await waitFinal(page);
  await page.waitForTimeout(1200);
  const reqs = await routeReqs(page);
  let L = await lives(page);
  const eta = nb((await panel(page)).eta);
  const expected = `${T.mn.count(2)}, ${fmtDistance(4300, 'mn')}, ${fmtDuration(720, 'mn')}, ${eta}`;
  const resultAnns = L.filter((x) => x.t >= reqs[0].t);
  test.info().annotations.push({ type: 'AC47 result', description: resultAnns.map((x) => `${(x.t - reqs[0].end).toFixed(0)} ms: ${x.text}`).join(' | ') });
  expect(resultAnns.length).toBe(1);
  expect(nb(resultAnns[0].text)).toBe(expected);
  expect(resultAnns[0].t - reqs[0].end).toBeLessThanOrEqual(1000);
  // select route 2
  const n = L.length;
  await tid(page, 'route-option').nth(1).click();
  await page.waitForTimeout(1300);
  L = await lives(page);
  const sel = L.slice(n);
  test.info().annotations.push({ type: 'AC47 selection', description: sel.map((x) => x.text).join(' | ') });
  expect(sel.length).toBe(1);
  expect(nb(sel[0].text)).toContain(fmtDistance(5200, 'mn'));
  expect(nb(sel[0].text)).toContain(fmtDuration(900, 'mn'));
  expect(nb(sel[0].text)).toMatch(new RegExp(`${T.mn.eta} \\d{2}:\\d{2}`));
  // state: slow NoRoute → loading (not announced) then the message once
  mode = 'slow';
  const m = L.length;
  await tid(page, 'route-swap').click();
  await expect.poll(async () => (await panel(page)).state, { timeout: 3000 }).toBe('loading');
  await waitFinal(page);
  await page.waitForTimeout(1300);
  L = await lives(page);
  const st = L.slice(m);
  test.info().annotations.push({ type: 'AC47 state', description: st.map((x) => x.text).join(' | ') });
  expect(st.length).toBe(1);
  expect(nb(st[0].text)).toBe(T.mn.noRoute);
  expect(st.some((x) => x.text.includes(T.mn.loading))).toBe(false);
});

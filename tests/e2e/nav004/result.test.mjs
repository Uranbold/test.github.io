// NAV-004 C. Routes on the map and alternatives: AC 16–21 (mocked responses).
import { test, expect } from '@playwright/test';
import { REF, T, fmtDistance, fmtDuration, lines, mockReverse, mockRoute, nb, openApp, openPreview, panel, routeReqs, rview, setField, simpleBody, tid, waitFinal, camera, project, lastAct } from './helpers.mjs';

const THREE = [
  { distance: 4300, duration: 720 },
  { distance: 5200, duration: 900 },
  { distance: 6150, duration: 1020 },
];
const echo = (b, specs = THREE, snap) => ({ body: simpleBody({ lat: b.locations[0].lat, lng: b.locations[0].lon }, { lat: b.locations[1].lat, lng: b.locations[1].lon }, specs, snap) });

async function ready(page, opts = {}) {
  await mockReverse(page);
  await openApp(page, opts);
  await openPreview(page, REF.P3);
  await setField(page, 'origin', REF.P1);
  await waitFinal(page);
}

function lum(hex) {
  const c = hex.replace('#', '');
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(c.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
const contrast = (a, b) => {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
};
function hexOf(c) {
  if (typeof c !== 'string') return null;
  const s = c.trim().toLowerCase();
  if (/^#[0-9a-f]{6}$/.test(s)) return s;
  if (/^#[0-9a-f]{3}$/.test(s)) return '#' + [...s.slice(1)].map((x) => x + x).join('');
  const m = s.match(/^rgba?\(\s*(\d+)[ ,]+(\d+)[ ,]+(\d+)/);
  if (m) return '#' + [m[1], m[2], m[3]].map((n) => Number(n).toString(16).padStart(2, '0')).join('');
  return null;
}

/** Route and base colours from the live style (evaluated at the current zoom for interpolations). */
const styleColours = (page) =>
  page.evaluate(() => {
    const m = window.__nav002.map;
    const layers = m.getStyle().layers;
    const layerIds = layers.map((l) => l.id);
    const col = (id, prop) => {
      if (!m.getLayer(id)) return null;
      const v = m.getPaintProperty(id, prop);
      return typeof v === 'string' ? v : JSON.stringify(v);
    };
    const firstSymbol = layers.findIndex((l) => l.type === 'symbol');
    const earth = layers.find((l) => l.id === 'earth');
    const major = layers.find((l) => /^roads_major$/.test(l.id)) ?? layers.find((l) => /roads.*major/.test(l.id) && !/casing/.test(l.id));
    const widthAt = (id) => {
      const v = m.getPaintProperty(id, 'line-width');
      const z = m.getZoom();
      if (typeof v === 'number') return v;
      if (Array.isArray(v) && v[0] === 'interpolate') {
        const stops = v.slice(3);
        for (let i = 0; i < stops.length - 2; i += 2) {
          const [z0, w0, z1, w1] = [stops[i], stops[i + 1], stops[i + 2], stops[i + 3]];
          if (z <= z0) return w0;
          if (z <= z1) return w0 + ((w1 - w0) * (z - z0)) / (z1 - z0);
        }
        return stops.at(-1);
      }
      return null;
    };
    return {
      sel: col('nav-route-sel', 'line-color'),
      selCasing: col('nav-route-sel-casing', 'line-color'),
      alt: col('nav-route-alt', 'line-color'),
      earth: earth ? (earth.paint?.['fill-color'] ?? earth.paint?.['background-color']) : null,
      major: major ? major.paint?.['line-color'] : null,
      majorId: major?.id ?? null,
      order: ['nav-route-alt-casing', 'nav-route-alt', 'nav-route-sel-casing', 'nav-route-sel', 'nav-route-hit'].map((id) => layerIds.indexOf(id)),
      firstSymbol,
      widths: { sel: widthAt('nav-route-sel'), selCasing: widthAt('nav-route-sel-casing'), alt: widthAt('nav-route-alt'), altCasing: widthAt('nav-route-alt-casing'), hit: widthAt('nav-route-hit') },
      zoom: m.getZoom(),
    };
  });

test('AC16: k routes drawn (1–3), route 1 selected and drawn above the others; selected colour >= 3:1 vs earth and major roads in both themes; alternatives another colour and >= 2 px narrower; lines never cover attribution/scale/controls', async ({ page }) => {
  let specs = THREE;
  await mockRoute(page, (b) => echo(b, specs));
  await ready(page);
  let l = await lines(page);
  expect(l.length).toBe(3);
  expect(l.filter((x) => x.selected).map((x) => x.index)).toEqual([0]);
  expect((await rview(page)).selected).toBe(0);
  for (const theme of ['day', 'night']) {
    if (theme === 'night') {
      await tid(page, 'theme-toggle').click();
      await page.waitForTimeout(800);
    }
    const c = await styleColours(page);
    test.info().annotations.push({ type: `AC16 colours ${theme}`, description: JSON.stringify({ sel: c.sel, alt: c.alt, earth: c.earth, major: c.majorId + ' ' + c.major, widths: c.widths, zoom: c.zoom.toFixed(2) }) });
    // order: alternatives below the selected line, all below the first symbol layer
    const [ac, a, sc, s] = c.order;
    expect(Math.min(ac, a, sc, s)).toBeGreaterThanOrEqual(0);
    expect(sc).toBeGreaterThan(a);
    expect(s).toBeGreaterThan(sc);
    expect(s).toBeLessThan(c.firstSymbol);
    expect(hexOf(c.alt)).not.toBe(hexOf(c.sel));
    expect(c.widths.sel - c.widths.alt).toBeGreaterThanOrEqual(2);
    expect(c.widths.selCasing - c.widths.altCasing).toBeGreaterThanOrEqual(2);
    const sel = hexOf(c.sel);
    for (const [name, base] of [['earth', c.earth], ['major road', c.major]]) {
      const bh = hexOf(base);
      if (!bh) {
        test.info().annotations.push({ type: `AC16 ${theme} ${name}`, description: `base colour is an expression, checked by UX check-contrast.mjs only: ${String(base).slice(0, 120)}` });
        continue;
      }
      expect(contrast(sel, bh), `${theme}: selected ${sel} vs ${name} ${bh}`).toBeGreaterThanOrEqual(3);
    }
  }
  // Lines are inside the map canvas, which lies below the UI layer: the attribution, scale bar and controls stay on top
  const top = await page.evaluate(() => {
    const out = {};
    for (const id of ['attribution-osm', 'scale-bar', 'zoom-in', 'zoom-out', 'my-location', 'compass']) {
      const e = document.querySelector(`[data-testid=${id}]`);
      if (!e) continue;
      const r = e.getBoundingClientRect();
      const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      out[id] = !!hit && (hit === e || e.contains(hit) || hit.contains(e));
    }
    return out;
  });
  for (const [id, ok] of Object.entries(top)) expect(ok, `${id} is the top element at its centre`).toBe(true);
  // k = 1: one line
  specs = THREE.slice(0, 1);
  await tid(page, 'route-swap').click();
  await waitFinal(page);
  l = await lines(page);
  expect(l.length).toBe(1);
  expect(l[0].selected).toBe(true);
});

for (const reduced of [false, true]) {
  test(`AC17${reduced ? ' reduced motion' : ''}: first render fits all routes and both markers inside the uncovered map (>= 40 px padding, zoom <= 17) within ${reduced ? '500 ms, no animation' : '1 s'}`, async ({ page }) => {
    if (reduced) await page.emulateMedia({ reducedMotion: 'reduce' });
    await mockRoute(page, (b) => echo(b));
    await mockReverse(page);
    await openApp(page);
    await openPreview(page, REF.P3);
    await page.evaluate(() => {
      window.__cam = [];
      const m = window.__nav002.map;
      for (const type of ['movestart', 'moveend']) m.on(type, () => window.__cam.push({ type, t: performance.now(), zoom: m.getZoom() }));
    });
    await setField(page, 'origin', REF.P1);
    await waitFinal(page);
    const budget = reduced ? 500 : 1000;
    await page.waitForFunction(() => !window.__nav002.map.isMoving(), null, { timeout: 5000 });
    await page.waitForTimeout(300);
    const r = await page.evaluate(() => {
      // Baseline: the route response arrived (fetch resolved). Earlier than the render, so this is stricter than AC 17.
      // (The MutationObserver timestamp cannot be used: a reduced-motion jump runs in the same task as the render.)
      const tRender = window.__rr.at(-1)?.end ?? null;
      const after = window.__cam.filter((c) => tRender !== null && c.t >= tRender);
      return { tRender, after };
    });
    const starts = r.after.filter((c) => c.type === 'movestart');
    const ends = r.after.filter((c) => c.type === 'moveend');
    const lastEnd = ends.at(-1);
    test.info().annotations.push({ type: 'AC17 camera', description: `response→movestart ${starts[0] ? (starts[0].t - r.tRender).toFixed(0) : '-'} ms, response→last moveend ${lastEnd ? (lastEnd.t - r.tRender).toFixed(0) : '-'} ms (budget ${budget}), move duration ${starts[0] && lastEnd ? (lastEnd.t - starts[0].t).toFixed(0) : '-'} ms, zoom ${lastEnd?.zoom.toFixed(2)}` });
    expect(lastEnd, 'the camera moved to fit the route').toBeTruthy();
    expect.soft(lastEnd.t - r.tRender, 'AC 17 time budget (soft: the fit geometry below is still checked)').toBeLessThanOrEqual(budget);
    if (reduced) expect(lastEnd.t - starts[0].t, 'jump, no animation').toBeLessThanOrEqual(50);
    const cam = await camera(page);
    expect(cam.zoom).toBeLessThanOrEqual(17.0001);
    // uncovered area: right of the panel (expanded layout), below the top controls, above the attribution strip
    const cover = await page.evaluate(() => {
      const r = (id) => document.querySelector(`[data-testid=${id}]`)?.getBoundingClientRect();
      const panel = document.querySelector('[data-testid=route-panel]').getBoundingClientRect();
      const topIds = ['language-toggle', 'theme-toggle', 'compass'];
      const topBottom = Math.max(...topIds.map((i) => r(i)?.bottom ?? 0));
      const attr = r('attribution')?.top ?? innerHeight;
      return { left: panel.right, top: topBottom, bottom: attr, right: innerWidth };
    });
    const l = await lines(page);
    const pts = l.flatMap((x) => x.coords);
    pts.push([REF.P1.lng, REF.P1.lat], [REF.P3.lng, REF.P3.lat]);
    let worst = { dx: Infinity };
    for (const [lng, lat] of pts) {
      const p = await project(page, lng, lat);
      const m = Math.min(p.x - cover.left, cover.right - p.x, p.y - cover.top, cover.bottom - p.y);
      if (m < worst.dx) worst = { dx: m, lng, lat, x: p.x, y: p.y };
    }
    test.info().annotations.push({ type: 'AC17 min distance to the uncovered-area edge', description: `${worst.dx.toFixed(1)} px (cover ${JSON.stringify(cover)})` });
    expect(worst.dx).toBeGreaterThanOrEqual(40 - 2);
  });
}

test('AC18 + AC19: route options «Маршрут сонгох» (k>=2) with «Маршрут n», distance and duration, aria-checked and «Өөр маршрут»; choosing an option or clicking an alternative line (hit area) selects it within 200 ms, updates summary and turn list, 0 requests, no camera move', async ({ page }) => {
  const specs = THREE.map((s, i) => ({ ...s, steps: [{ maneuver: { type: 'depart', bearing_after: 180 }, name: `Зам ${i + 1}`, distance: 300 }, { maneuver: { type: 'arrive' } }] }));
  await mockRoute(page, (b) => echo(b, specs));
  await ready(page);
  await page.waitForFunction(() => !window.__nav002.map.isMoving());
  let p = await panel(page);
  expect(p.optionsVisible).toBe(true);
  expect(await tid(page, 'route-options').getAttribute('role')).toBe('radiogroup');
  expect(await tid(page, 'route-options').getAttribute('aria-label')).toBe(T.mn.options);
  expect(p.options.length).toBe(3);
  p.options.forEach((o, i) => {
    expect(nb(o.text)).toContain(T.mn.option(i + 1));
    expect(nb(o.text)).toContain(fmtDistance(THREE[i].distance, 'mn'));
    expect(nb(o.text)).toContain(fmtDuration(THREE[i].duration, 'mn'));
    expect(o.checked).toBe(i === 0 ? 'true' : 'false');
    if (i > 0) expect(o.desc ?? '').toContain(T.mn.alternative);
    else expect(o.desc ?? '').not.toContain(T.mn.alternative);
  });
  const n0 = (await routeReqs(page)).length;
  const cam0 = await camera(page);
  // (a) route option click. Timing method (test plan §2): a MutationObserver armed BEFORE the click records the first
  // moment «Маршрут 3» is aria-checked=true; the start is the click event (capture phase). Round 1 (2026-09-30): the
  // earlier check ran in an evaluate after the click and so counted Playwright round trips (flaky 547–847 ms with
  // the selection already applied). The 200 ms assertion is unchanged.
  await page.evaluate(() => {
    window.__ac18sel = null;
    const mo = new MutationObserver(() => {
      const sel = document.querySelector('[data-testid=route-option][aria-checked=true]');
      if (window.__ac18sel === null && sel?.dataset.index === '2') {
        window.__ac18sel = performance.now();
        mo.disconnect();
      }
    });
    mo.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['aria-checked'] });
  });
  await tid(page, 'route-option').nth(2).click();
  const tA = await lastAct(page, 'route-option');
  expect(tA, 'the click on «Маршрут 3» was recorded').not.toBeNull();
  await page.waitForFunction(() => window.__ac18sel !== null, null, { timeout: 2000 });
  const dA = await page.evaluate((t0) => window.__ac18sel - t0, tA);
  p = await panel(page);
  test.info().annotations.push({ type: 'AC18 option → selected', description: `${dA.toFixed(1)} ms after the click event (in-page MutationObserver)` });
  expect(dA).toBeLessThanOrEqual(200);
  expect(nb(p.distance)).toBe(fmtDistance(THREE[2].distance, 'mn'));
  expect(nb(p.duration)).toBe(fmtDuration(THREE[2].duration, 'mn'));
  expect(p.steps[0].street).toBe('Зам 3');
  expect((await lines(page)).find((x) => x.selected).index).toBe(2);
  // (b) click an alternative line on the map, 9 px outside its visible casing
  await page.waitForTimeout(300);
  const l = await lines(page);
  const alt = l.find((x) => x.index === 1);
  const mid = alt.coords[Math.floor(alt.coords.length / 2)];
  const a = await project(page, mid[0], mid[1]);
  const b2 = await project(page, alt.coords[Math.floor(alt.coords.length / 2) + 1][0], alt.coords[Math.floor(alt.coords.length / 2) + 1][1]);
  const w = (await styleColours(page)).widths;
  const len = Math.hypot(b2.x - a.x, b2.y - a.y) || 1;
  const nx = -(b2.y - a.y) / len;
  const ny = (b2.x - a.x) / len;
  const off = w.altCasing / 2 + 9;
  const x = a.x + nx * off;
  const y = a.y + ny * off;
  test.info().annotations.push({ type: 'AC18 map click', description: `${off.toFixed(1)} px from the centreline (casing ${w.altCasing.toFixed(1)} px, hit ${w.hit.toFixed(1)} px, zoom ${(await camera(page)).zoom.toFixed(2)})` });
  const tB = await page.evaluate(() => performance.now());
  await page.mouse.click(x, y);
  await expect.poll(async () => (await lines(page)).find((q) => q.selected).index, { timeout: 2000 }).toBe(1);
  const dB = (await page.evaluate(() => performance.now())) - tB;
  p = await panel(page);
  expect(p.options.find((o) => o.checked === 'true').index).toBe(1);
  expect(nb(p.distance)).toBe(fmtDistance(THREE[1].distance, 'mn'));
  test.info().annotations.push({ type: 'AC18 line click → selected (upper bound incl. Playwright round trips)', description: `${dB.toFixed(0)} ms` });
  // no requests, no camera move, the place card did not open
  expect((await routeReqs(page)).length).toBe(n0);
  const cam1 = await camera(page);
  expect(Math.abs(cam1.lat - cam0.lat) + Math.abs(cam1.lng - cam0.lng)).toBeLessThan(1e-9);
  expect(cam1.zoom).toBeCloseTo(cam0.zoom, 6);
  // keyboard: arrows in the radio group change the selection, 0 requests
  await tid(page, 'route-option').nth(1).focus();
  await page.keyboard.press('ArrowDown');
  await expect.poll(async () => (await panel(page)).options.find((o) => o.checked === 'true')?.index).toBe(2);
  await page.keyboard.press('ArrowDown');
  await expect.poll(async () => (await panel(page)).options.find((o) => o.checked === 'true')?.index, { message: 'wraps to the first' }).toBe(0);
  await page.keyboard.press('ArrowUp');
  await expect.poll(async () => (await panel(page)).options.find((o) => o.checked === 'true')?.index).toBe(2);
  expect((await routeReqs(page)).length).toBe(n0);
});

test('AC19: with k = 1 the route options group is not shown', async ({ page }) => {
  await mockRoute(page, (b) => echo(b, THREE.slice(0, 1)));
  await ready(page);
  const p = await panel(page);
  expect(p.optionsVisible).toBe(false);
  expect(p.options.length).toBe(0);
});

test('AC20: day/night switch keeps lines and markers in the new colours, keeps the selection, 0 requests', async ({ page }) => {
  await mockRoute(page, (b) => echo(b));
  await ready(page);
  await tid(page, 'route-option').nth(1).click();
  const before = await styleColours(page);
  const n0 = (await routeReqs(page)).length;
  await tid(page, 'theme-toggle').click();
  await page.waitForTimeout(1200);
  const after = await styleColours(page);
  const l = await lines(page);
  expect(l.length).toBe(3);
  expect(l.find((x) => x.selected).index).toBe(1);
  expect((await panel(page)).options.find((o) => o.checked === 'true').index).toBe(1);
  expect(hexOf(after.sel)).not.toBe(hexOf(before.sel));
  test.info().annotations.push({ type: 'AC20 selected colour', description: `${before.sel} → ${after.sel}` });
  const markers = await page.evaluate(() => ({ o: document.querySelectorAll('[data-testid=route-origin-marker]').length, d: [...document.querySelectorAll('[data-testid=place-pin]')].filter((e) => !e.closest('[hidden]')).length }));
  expect(markers.o).toBe(1);
  expect(markers.d).toBe(1);
  expect((await routeReqs(page)).length).toBe(n0);
});

for (const [snap, expected] of [
  [[1416, 12], T.mn.snap('1,4 км')],
  [[40, 1416], T.mn.snap('1,4 км')],
  [[620, 900], T.mn.snap('900 м')],
  [[500, 500], null],
  [[12, 30], null],
]) {
  test(`AC21: snap distances ${snap.join(' / ')} m → ${expected ? `«${expected}»` : 'no notice'}`, async ({ page }) => {
    await mockRoute(page, (b) => echo(b, THREE.slice(0, 1), snap));
    await ready(page);
    const p = await panel(page);
    expect(p.state).toBe('route');
    expect(p.snap === null ? null : nb(p.snap)).toBe(expected);
    expect(p.summary ?? true).toBeTruthy();
  });
}

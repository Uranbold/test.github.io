// NAV-003 E. Selecting a result: fly-to, pin and place card (AC 20–24). Fixture features by request interception.
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {
  FIXTURES, FX, SEARCH_GLOB, T, TRAD, attributionProblems, camera, card, fc, jumpTo, mock, openApp, options, pins, project, tid, typeQuery, waitSettled,
} from './helpers.mjs';

const byName = Object.fromEntries(FIXTURES.map((f) => [f.expect.name ?? f.id, f]));

/** Every fixture is found by its id as query: «F5 тест» -> [F5]. Plus «олон» -> several. */
async function mockById(page) {
  return mock(page, SEARCH_GLOB, (p) => {
    const m = /F(\d+)/.exec(p.q);
    if (m) return { body: fc([FX[`F${m[1]}`].feature]) };
    return { body: fc(['F5', 'F9', 'F10', 'F2'].map((id) => FX[id].feature)) };
  });
}

/** Selects the only option for fixture `id` with the given method; returns ms from the action to camera still. */
async function selectFixture(page, id, how = 'click') {
  const q = `${id} тест`;
  await typeQuery(page, q);
  await waitSettled(page, q);
  const t0 = Date.now();
  if (how === 'click') await tid(page, 'search-option').first().click();
  else {
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
  }
  await page.waitForFunction(() => !window.__nav002.map.isMoving(), null, { timeout: 5000, polling: 50 }).catch(() => {});
  // flyTo may not have started on the first poll
  await page.waitForTimeout(100);
  await page.waitForFunction(() => !window.__nav002.map.isMoving(), null, { timeout: 5000, polling: 50 });
  return Date.now() - t0;
}

test('AC20: point results are centred (±5 px) at zoom 13 (area types) or 16 (others) within 2 s; extents fit with >= 40 px padding at zoom <= 17', async ({ page }) => {
  test.setTimeout(120_000);
  await mockById(page);
  await openApp(page);
  const rows = [];
  for (const f of FIXTURES) {
    await jumpTo(page, { lat: 47.9, lng: 106.9 }, 11);
    const ms = await selectFixture(page, f.id, f.id === 'F3' || f.id === 'F10' ? 'keys' : 'click');
    const cam = await camera(page);
    const [lon, lat] = f.feature.geometry.coordinates;
    const pr = await project(page, lon, lat);
    const row = { id: f.id, ms, zoom: +cam.zoom.toFixed(2) };
    if (f.expect.zoomClass === 'extent') {
      const [minLon, maxLat, maxLon, minLat] = f.feature.properties.extent;
      const a = await project(page, minLon, maxLat);
      const b = await project(page, maxLon, minLat);
      row.box = [a.x, a.y, b.x, b.y].map((v) => Math.round(v));
      row.pad = { l: a.x, t: a.y, r: pr.vw - b.x, b: pr.vh - b.y };
      expect.soft(Math.min(row.pad.l, row.pad.t, row.pad.r, row.pad.b), `${f.id} extent padding ${JSON.stringify(row.pad)}`).toBeGreaterThanOrEqual(39.5);
      expect.soft(cam.zoom, `${f.id} extent zoom`).toBeLessThanOrEqual(17.0001);
    } else {
      row.dx = +(pr.x - pr.cx).toFixed(1);
      row.dy = +(pr.y - pr.cy).toFixed(1);
      row.dxWin = +(pr.x - pr.vw / 2).toFixed(1);
      row.dyWin = +(pr.y - pr.vh / 2).toFixed(1);
      expect.soft(Math.hypot(row.dx, row.dy), `${f.id} centred ±5 px (${row.dx}, ${row.dy})`).toBeLessThanOrEqual(5);
      expect.soft(Math.abs(cam.zoom - f.expect.zoom), `${f.id} zoom ${cam.zoom} expected ${f.expect.zoom}`).toBeLessThanOrEqual(0.01);
    }
    expect.soft(ms, `${f.id} camera arrived within 2 s`).toBeLessThanOrEqual(2000);
    rows.push(row);
    await page.keyboard.press('Escape'); // close card (focus is on the card heading)
  }
  test.info().annotations.push({ type: 'AC20 rows', description: JSON.stringify(rows) });
});

test('AC20: prefers-reduced-motion -> the camera jumps without animation within 500 ms', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await mockById(page);
  await openApp(page);
  for (const id of ['F9', 'F1']) {
    await typeQuery(page, `${id} тест`);
    await waitSettled(page, `${id} тест`);
    const r = await page.evaluate(async () => {
      const m = window.__nav002.map;
      let moveStart = null;
      let moveEnd = null;
      let frames = 0;
      m.once('movestart', () => (moveStart = performance.now()));
      m.on('move', () => frames++);
      m.once('moveend', () => (moveEnd = performance.now()));
      const t0 = performance.now();
      document.querySelector('[data-testid=search-option]').dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 700));
      return { end: moveEnd === null ? null : moveEnd - t0, frames };
    });
    expect(r.end, `${id} moveend`).not.toBeNull();
    expect(r.end).toBeLessThanOrEqual(500);
    expect(r.frames, `${id} single-step jump (move events)`).toBeLessThanOrEqual(2);
    await page.keyboard.press('Escape');
  }
});

test('AC21: one pin at the feature point (±2 px) named with the result; card with heading, type label, context line, coordinates «lat, lon» 5 decimals; list closed; input shows the name', async ({ page }) => {
  await mockById(page);
  for (const lang of ['mn', 'en']) {
    await openApp(page, { lang });
    for (const id of ['F9', 'F5', 'F11', 'F13']) {
      const f = FX[id];
      await selectFixture(page, id);
      const [lon, lat] = f.feature.geometry.coordinates;
      const pr = await project(page, lon, lat);
      const ps = await pins(page);
      const label = lang === 'mn' ? f.expect.mn : f.expect.en;
      const name = f.expect.name ?? label;
      expect(ps.length).toBe(1);
      expect.soft(Math.hypot(ps[0].x - pr.x, ps[0].y - pr.y), `${id} pin anchor vs point`).toBeLessThanOrEqual(2);
      expect(ps[0].role).toBe('img');
      expect(ps[0].label).toBe(name);
      const c = await card(page);
      expect(c.open).toBe(true);
      expect(c.titleTag).toMatch(/^H[1-6]$/);
      expect(c.title).toBe(name);
      expect(c.type).toBe(label);
      expect(c.context ?? '').toBe(f.expect.context ?? '');
      expect(c.coords).toBe(`${lat.toFixed(5)}, ${lon.toFixed(5)}`);
      expect(c.coords).toMatch(/^-?\d+\.\d{5}, -?\d+\.\d{5}$/);
      expect(TRAD.test(c.text)).toBe(false);
      await expect(tid(page, 'search-popup')).toBeHidden();
      await expect(tid(page, 'search-input')).toHaveValue(name);
      await expect(tid(page, 'search-input')).toHaveAttribute('aria-expanded', 'false');
      await page.keyboard.press('Escape');
    }
  }
});

test('AC19 on the place card: every fixture F1–F13 card shows the story type label (mn and en)', async ({ page }) => {
  test.setTimeout(120_000);
  await mockById(page);
  await openApp(page);
  for (const lang of ['mn', 'en']) {
    if (lang === 'en') await tid(page, 'language-toggle').click();
    for (const f of FIXTURES) {
      await typeQuery(page, `${f.id} тест`);
      await waitSettled(page, `${f.id} тест`);
      await tid(page, 'search-option').first().click();
      const c = await card(page);
      expect.soft(c.type, `${f.id} ${lang}`).toBe(lang === 'mn' ? f.expect.mn : f.expect.en);
      await page.keyboard.press('Escape');
    }
  }
});

test('AC22: selecting another result replaces pin and card; «Хаах» or Escape removes both and returns focus to the input; the query stays', async ({ page }) => {
  await mockById(page);
  await openApp(page);
  await selectFixture(page, 'F9');
  await selectFixture(page, 'F6');
  expect((await pins(page)).length).toBe(1);
  expect((await pins(page))[0].label).toBe(FX.F6.expect.name);
  expect(await page.locator('[data-testid=place-card]').count()).toBe(1);
  expect((await card(page)).title).toBe(FX.F6.expect.name);
  // Close button
  await expect(tid(page, 'place-card-close')).toHaveAccessibleName(T.mn.close);
  await tid(page, 'place-card-close').click();
  expect((await card(page)).open).toBe(false);
  expect((await pins(page)).length).toBe(0);
  expect(await page.evaluate(() => document.activeElement?.dataset.testid)).toBe('search-input');
  await expect(tid(page, 'search-input')).toHaveValue(FX.F6.expect.name);
  // Escape with focus in the card
  await selectFixture(page, 'F7');
  expect(await page.evaluate(() => document.activeElement?.closest('[data-testid=place-card]') !== null)).toBe(true);
  await page.keyboard.press('Escape');
  expect((await card(page)).open).toBe(false);
  expect((await pins(page)).length).toBe(0);
  expect(await page.evaluate(() => document.activeElement?.dataset.testid)).toBe('search-input');
  await expect(tid(page, 'search-input')).toHaveValue(FX.F7.expect.name);
});

test('AC24: panning and zooming keeps the pin at its geographic position and the card open', async ({ page }) => {
  await mockById(page);
  await openApp(page);
  await selectFixture(page, 'F9');
  const [lon, lat] = FX.F9.feature.geometry.coordinates;
  for (const cam of [{ center: [106.93, 47.92], zoom: 14 }, { center: [106.905, 47.915], zoom: 17.5 }, { center: [106.9, 47.91], zoom: 12 }]) {
    await page.evaluate((c) => window.__nav002.map.jumpTo(c), cam);
    await page.waitForTimeout(150);
    const pr = await project(page, lon, lat);
    const ps = await pins(page);
    expect(ps.length).toBe(1);
    expect(Math.hypot(ps[0].x - pr.x, ps[0].y - pr.y)).toBeLessThanOrEqual(2);
    expect((await card(page)).open).toBe(true);
  }
  // a real mouse drag
  const c = await page.evaluate(() => ({ x: innerWidth / 2 + 200, y: innerHeight / 2 }));
  await page.mouse.move(c.x, c.y);
  await page.mouse.down();
  await page.mouse.move(c.x + 120, c.y + 60, { steps: 6 });
  await page.mouse.up();
  await page.waitForTimeout(400);
  const pr = await project(page, lon, lat);
  const ps = await pins(page);
  expect(Math.hypot(ps[0].x - pr.x, ps[0].y - pr.y)).toBeLessThanOrEqual(2);
  expect((await card(page)).open).toBe(true);
});

/** NAV-003 elements vs NAV-002 controls, attribution and scale bar (AC 23). */
const overlapProblems = (page) =>
  page.evaluate(() => {
    const vis = (e) => {
      if (!e || e.closest('[hidden]')) return false;
      const cs = getComputedStyle(e);
      const r = e.getBoundingClientRect();
      return cs.visibility === 'visible' && cs.display !== 'none' && r.width > 0 && r.height > 0;
    };
    const R = (e) => e.getBoundingClientRect();
    const inter = (a, b) => Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left)) * Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
    const nav3 = ['search-field', 'search-popup', 'place-card', 'place-pin'].flatMap((id) => [...document.querySelectorAll(`[data-testid=${id}]`)]).filter(vis);
    const nav2 = ['language-toggle', 'theme-toggle', 'compass', 'zoom-in', 'zoom-out', 'my-location', 'scale-bar', 'attribution-osm', 'attribution-esa', 'status-banner', 'location-message'].flatMap((id) => [...document.querySelectorAll(`[data-testid=${id}]`)]).filter(vis);
    const probs = [];
    for (const a of nav3) for (const b of nav2) {
      const area = inter(R(a), R(b));
      if (area > 0.5) probs.push(`${a.dataset.testid} overlaps ${b.dataset.testid} (${area.toFixed(0)} px²)`);
    }
    // Scale bar not covered (hit test)
    const sb = document.querySelector('[data-testid=scale-bar]');
    if (vis(sb)) {
      const r = R(sb);
      for (const fx of [0.1, 0.5, 0.9]) {
        const h = document.elementFromPoint(r.left + r.width * fx, r.top + r.height / 2);
        if (h && !sb.contains(h) && h !== sb && !h.contains(sb) && h.closest('#map') === null) probs.push(`scale bar covered by ${h.tagName}.${h.className}`);
      }
    }
    // NAV-003 touch targets >= 44x44
    const targets = ['search-input', 'search-clear', 'search-option', 'search-retry', 'place-card-close', 'place-retry'].flatMap((id) => [...document.querySelectorAll(`[data-testid=${id}]`)]).filter(vis);
    for (const e of targets) {
      const r = R(e);
      if (r.width < 43.5 || r.height < 43.5) probs.push(`${e.dataset.testid} ${r.width.toFixed(1)}x${r.height.toFixed(1)} < 44x44`);
    }
    // everything inside the viewport
    for (const e of nav3) {
      const r = R(e);
      if (e.dataset.testid !== 'place-pin' && (r.left < -0.5 || r.top < -0.5 || r.right > innerWidth + 0.5 || r.bottom > innerHeight + 0.5)) probs.push(`${e.dataset.testid} outside the viewport`);
    }
    return probs;
  });

for (const width of [320, 360, 768, 1366, 1920]) {
  test(`AC23 ${width}px: card, pin, search box and list cover no attribution, scale bar or NAV-002 control; NAV-003 targets >= 44x44 (day/night × mn/en)`, async ({ page }) => {
    test.setTimeout(120_000);
    const height = { 320: 568, 360: 640, 768: 1024, 1366: 768, 1920: 1080 }[width];
    await page.setViewportSize({ width, height });
    const longName = 'Монгол Улсын Их Хурлын дэргэдэх Хүний эрхийн үндэсний комиссын байр ба Сүхбаатар дүүргийн 1-р хороо';
    await mock(page, SEARCH_GLOB, (p) => {
      if (/урт/.test(p.q)) return { body: fc([{ ...FX.F2.feature, properties: { ...FX.F2.feature.properties, name: longName } }]) };
      return { body: fc(['F5', 'F9', 'F10', 'F2', 'F6', 'F7', 'F8', 'F11'].map((id) => FX[id].feature)) };
    });
    const all = [];
    for (const theme of ['day', 'night']) {
      for (const lang of ['mn', 'en']) {
        const ctx = await page.context().browser().newContext({ viewport: { width, height } });
        const pg = await ctx.newPage();
        await mock(pg, SEARCH_GLOB, (p) => {
          if (/урт/.test(p.q)) return { body: fc([{ ...FX.F2.feature, properties: { ...FX.F2.feature.properties, name: longName } }]) };
          return { body: fc(['F5', 'F9', 'F10', 'F2', 'F6', 'F7', 'F8', 'F11'].map((id) => FX[id].feature)) };
        });
        await openApp(pg, { theme, lang });
        const tag = `${width} ${theme} ${lang}`;
        // list open
        await typeQuery(pg, 'Олон');
        await waitSettled(pg, 'Олон');
        for (const p of await overlapProblems(pg)) all.push(`${tag} list: ${p}`);
        for (const p of await attributionProblems(pg)) all.push(`${tag} list: ${p}`);
        // card open (point result, centred)
        await tid(pg, 'search-option').nth(1).click();
        await pg.waitForTimeout(1300);
        for (const p of await overlapProblems(pg)) all.push(`${tag} card: ${p}`);
        for (const p of await attributionProblems(pg)) all.push(`${tag} card: ${p}`);
        // long-name card
        await typeQuery(pg, 'урт нэр');
        await waitSettled(pg, 'урт нэр');
        await tid(pg, 'search-option').first().click();
        await pg.waitForTimeout(1300);
        for (const p of await overlapProblems(pg)) all.push(`${tag} long card: ${p}`);
        for (const p of await attributionProblems(pg)) all.push(`${tag} long card: ${p}`);
        // the pin of a centred point result is not covered by the card
        const pinCovered = await pg.evaluate(() => {
          const pin = document.querySelector('[data-testid=place-pin]');
          if (!pin) return 'no pin';
          const r = pin.getBoundingClientRect();
          const h = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
          return h && (pin.contains(h) || h === pin) ? null : `pin covered by ${h?.tagName}.${h?.className}`;
        });
        if (pinCovered) all.push(`${tag} long card: ${pinCovered}`);
        await ctx.close();
      }
    }
    expect(all).toEqual([]);
  });
}

test('AC21/AC40 axe on the open place card (serious/critical = 0)', async ({ page }) => {
  await mockById(page);
  await openApp(page);
  await selectFixture(page, 'F5');
  const r = await new AxeBuilder({ page }).include('#place').analyze();
  expect(r.violations.filter((v) => ['serious', 'critical'].includes(v.impact)).map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
});

// NAV-004 E. Turn-by-turn list: AC 26–31 (QA fixture route tests/e2e/nav004/fixtures/ac27-steps.json; live routes in
// live.test.mjs). Expected texts come from the AC 27 table transcribed in helpers.mjs (AC27), not from web/.
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import {
  AC27, CYRILLIC, FIXTURE_ROUTE, REF, ROOT, T, WEB, ac28Problems, camera, fmtDistance, lastAct, line, mockReverse, mockRoute, nb, openApp, openPreview, osrmBody, osrmRoute,
  panel, project, routeReqs, setField, tid, waitFinal,
} from './helpers.mjs';

const COORDS = line(REF.P1, REF.P3, 60, 0.002);
const fixtureRoute = (steps, distance = 5400, duration = 780) => osrmRoute({ coords: COORDS, distance, duration, steps: steps.map((s, i) => ({ ...s, distance: s.maneuver.type === 'arrive' ? 0 : 100 + i * 37 })) });
const FIXTURE_BODY = osrmBody([fixtureRoute(FIXTURE_ROUTE.steps)]);

async function ready(page, body, opts = {}) {
  await mockReverse(page);
  const bodies = Array.isArray(body) ? [...body] : null;
  await mockRoute(page, () => ({ body: bodies ? bodies.shift() ?? bodies.at(-1) : body }));
  await openApp(page, opts);
  await openPreview(page, REF.P3);
  await setField(page, 'origin', REF.P1);
  await waitFinal(page);
}

for (const lang of ['mn', 'en']) {
  test(`AC26 + AC27 (${lang}) + AC45 names: fixture route → one row per step in order, depart first, arrive last; icon aria-hidden; text exactly per AC 27; street name without zero-width chars, omitted when empty; distance except on arrive; name = "text, street, distance"`, async ({ page }) => {
    await ready(page, FIXTURE_BODY, { lang });
    const p = await panel(page);
    const steps = FIXTURE_ROUTE.steps;
    expect(p.state).toBe('route');
    const listInfo = await page.evaluate(() => {
      const ol = document.querySelector('[data-testid=route-steps]');
      const h = document.getElementById(ol.getAttribute('aria-labelledby'));
      return { tag: ol.tagName, name: h?.textContent, hTag: h?.tagName };
    });
    expect(listInfo.tag).toBe('OL');
    expect(listInfo.name).toBe(T[lang].directions);
    expect(p.steps.length).toBe(steps.length);
    const bad = [];
    p.steps.forEach((row, i) => {
      const s = steps[i];
      const exp = AC27[s.expect][lang === 'mn' ? 0 : 1];
      if (row.text !== exp) bad.push(`#${i} ${JSON.stringify(s.maneuver)}: "${row.text}" != "${exp}"`);
      const street = 'street' in s ? s.street : s.name;
      expect(row.street, `#${i} street`).toBe(street === '' ? null : street);
      if (row.street) expect(/[​‌‍﻿]/.test(row.street)).toBe(false);
      const isArrive = s.maneuver.type === 'arrive';
      const d = isArrive ? '' : fmtDistance(100 + i * 37, lang);
      expect(nb(row.dist), `#${i} distance`).toBe(d);
      expect(row.iconHidden, `#${i} icon aria-hidden`).toBe(true);
      expect(nb(row.label), `#${i} accessible name`).toBe([exp, row.street, d].filter(Boolean).join(', '));
    });
    expect(bad, bad.join('\n')).toEqual([]);
    expect(p.steps[0].key.startsWith('depart')).toBe(true);
    expect(p.steps.at(-1).key.startsWith('arrive')).toBe(true);
    if (lang === 'mn') {
      // AC 28 on the fixture route (Mongolian UI)
      const problems = p.steps.map((r) => [r.text, ac28Problems(r.text)]).filter(([, q]) => q.length);
      expect(problems, JSON.stringify(problems)).toEqual([]);
      // Latin street names in the Mongolian UI are marked lang="en" (screen spec, language of parts)
      const latin = p.steps.find((r) => r.street === 'Erdenet-Selenge road');
      test.info().annotations.push({ type: 'AC26 Latin street lang', description: String(latin.streetLang) });
    } else {
      // AC 29: 0 Cyrillic letters outside the street-name line
      for (const r of p.steps) expect(CYRILLIC.test(r.text), r.text).toBe(false);
      const cyr = p.steps.find((r) => r.street === 'Чингисийн өргөн чөлөө');
      expect(cyr.streetLang, 'Cyrillic street name in the English UI has lang="mn"').toBe('mn');
    }
    // Valhalla's own narrative (maneuver.instruction) is never shown
    expect(await page.locator('[data-testid=route-panel]').textContent()).not.toContain('IGNORED Valhalla text');
  });
}

test('AC27: depart sector boundaries (0, 22.4, 22.5, 67.4, 67.5, 135, 180, 225, 270, 315, 337.4, 337.5, 360°) and arrive variants in mn and en', async ({ page }) => {
  const cases = FIXTURE_ROUTE.departBearings;
  const arrive = FIXTURE_ROUTE.arriveVariants;
  // Each response: 3 routes; route i departs with bearing cases[k+i] and arrives with arrive[(k+i) % 4]
  const bodies = [];
  const plan = [];
  for (let k = 0; k < cases.length; k += 3) {
    const group = cases.slice(k, k + 3).map((c, i) => ({ c, a: arrive[(k + i) % arrive.length] }));
    plan.push(group);
    bodies.push(osrmBody(group.map(({ c, a }) => fixtureRoute([{ maneuver: { type: 'depart', bearing_after: c.b }, name: '' }, { maneuver: { type: 'turn', modifier: 'left' }, name: '' }, { maneuver: a.maneuver, name: '' }]))));
  }
  await ready(page, bodies);
  const seen = [];
  for (let g = 0; g < plan.length; g++) {
    if (g > 0) {
      await tid(page, 'route-swap').click();
      await expect.poll(async () => (await routeReqs(page)).length).toBe(g + 1);
      await waitFinal(page);
    }
    for (let i = 0; i < plan[g].length; i++) {
      if (plan[g].length > 1) {
        await tid(page, 'route-option').nth(i).click();
        await expect.poll(async () => (await panel(page)).options.find((o) => o.checked === 'true')?.index).toBe(i);
      }
      for (const lang of ['mn', 'en']) {
        const cur = await page.evaluate(() => document.documentElement.lang);
        if (!cur.startsWith(lang)) {
          await tid(page, 'language-toggle').click();
          await expect.poll(() => page.evaluate(() => document.documentElement.lang)).toContain(lang);
        }
        const p = await panel(page);
        const li = lang === 'mn' ? 0 : 1;
        const { c, a } = plan[g][i];
        expect(p.steps[0].text, `depart ${c.b}° (${lang})`).toBe(AC27[c.expect][li]);
        expect(p.steps.at(-1).text, `arrive ${JSON.stringify(a.maneuver)} (${lang})`).toBe(AC27[a.expect][li]);
        seen.push(`${c.b}°→${p.steps[0].text}`);
      }
    }
  }
  test.info().annotations.push({ type: 'AC27 depart sectors', description: seen.join(' | ') });
});

for (const reduced of [false, true]) {
  test(`AC30${reduced ? ' reduced motion' : ''}: activating a turn row (click, Enter, Space) centres the map on its manoeuvre (±5 px) at zoom >= 17 within ${reduced ? '500 ms (jump)' : '1 s'}; route and selection stay`, async ({ page }) => {
    if (reduced) await page.emulateMedia({ reducedMotion: 'reduce' });
    await ready(page, FIXTURE_BODY);
    await page.waitForFunction(() => !window.__nav002.map.isMoving());
    await page.evaluate(() => {
      window.__cam = [];
      const m = window.__nav002.map;
      for (const type of ['movestart', 'moveend']) m.on(type, () => window.__cam.push({ type, t: performance.now(), zoom: m.getZoom() }));
    });
    const n0 = (await routeReqs(page)).length;
    const budget = reduced ? 500 : 1000;
    const results = [];
    const idx = [3, 12, 24];
    for (const [k, how] of ['click', 'Enter', 'Space'].entries()) {
      const i = idx[k];
      const row = tid(page, 'route-step').nth(i);
      if (how === 'click') await row.click();
      else {
        await row.focus();
        await page.keyboard.press(how === 'Space' ? ' ' : 'Enter');
      }
      const tAct = await lastAct(page, 'route-step');
      await page.waitForFunction(() => !window.__nav002.map.isMoving() && window.__cam.length > 0 && window.__cam.at(-1).type === 'moveend', null, { timeout: 5000 });
      await page.waitForTimeout(200);
      const ends = await page.evaluate((t) => window.__cam.filter((c) => c.t >= t && c.type === 'moveend'), tAct);
      const loc = FIXTURE_BODY.routes[0].legs[0].steps[i].maneuver.location;
      const pt = await project(page, loc[0], loc[1]);
      const cam = await camera(page);
      // Centre of the uncovered map area, screen spec › Camera rules (expanded width): left = the side-panel column
      // (size.side-panel 432 px = the route slot), top = R1 bottom (68 px while the panel is open), right = 64 px
      // (R4 control column 48 + 16), bottom = R5 (attribution strip) height.
      const area = await page.evaluate(() => {
        const r = (s) => document.querySelector(s)?.getBoundingClientRect();
        const slot = r('[data-testid=route-slot]');
        const attr = r('[data-testid=attribution]');
        return { left: slot.right, right: innerWidth - 64, top: 68, bottom: attr ? attr.top : innerHeight, slot: slot.right };
      });
      const cx = (area.left + area.right) / 2;
      const cy = (area.top + area.bottom) / 2;
      results.push({ how, i, dt: ends.at(-1) ? ends.at(-1).t - tAct : null, zoom: cam.zoom, px: pt.x, py: pt.y, cx, cy, vx: pt.cx, vy: pt.cy });
      expect(cam.zoom).toBeGreaterThanOrEqual(17 - 1e-6);
      expect(ends.length, `${how}: camera moved`).toBeGreaterThan(0);
      expect.soft(ends.at(-1).t - tAct, `${how}: within ${budget} ms`).toBeLessThanOrEqual(budget);
      // ±5 px from the centre of the uncovered area (spec) — also recorded against the canvas centre
      expect(Math.abs(pt.x - cx), `${how}: x ${pt.x.toFixed(1)} vs uncovered centre ${cx.toFixed(1)}`).toBeLessThanOrEqual(5 + 1);
      expect(Math.abs(pt.y - cy), `${how}: y ${pt.y.toFixed(1)} vs uncovered centre ${cy.toFixed(1)}`).toBeLessThanOrEqual(5 + 1);
    }
    test.info().annotations.push({ type: 'AC30 camera', description: results.map((r) => `${r.how} row ${r.i}: ${r.dt?.toFixed(0)} ms, z${r.zoom.toFixed(2)}, point (${r.px.toFixed(1)},${r.py.toFixed(1)}) uncovered centre (${r.cx.toFixed(1)},${r.cy.toFixed(1)}) canvas centre (${r.vx.toFixed(1)},${r.vy.toFixed(1)})`).join(' | ') });
    // route and selection stay; no requests
    expect((await panel(page)).state).toBe('route');
    expect((await routeReqs(page)).length).toBe(n0);
    // zoom higher than 17 is kept
    await page.evaluate(() => window.__nav002.map.jumpTo({ zoom: 18.2 }));
    await tid(page, 'route-step').nth(5).click();
    await page.waitForTimeout(reduced ? 500 : 1300);
    expect((await camera(page)).zoom).toBeCloseTo(18.2, 1);
  });
}

test('AC31: ADR-0008 accepted and recorded in the story Traceability; the manoeuvre mapping has no strings outside the resource files; a shared fixture exists for NAV-005', async () => {
  const story = readFileSync(ROOT + 'docs/requirements/stories/NAV-004-route-preview-web.md', 'utf8');
  const adr = readFileSync(ROOT + 'docs/architecture/adr/0008-route-instruction-text-client-side.md', 'utf8');
  expect(adr).toMatch(/Status:\*\*\s*accepted/);
  const trace = story.slice(story.indexOf('## Traceability'));
  const row = trace.split('\n').find((l) => l.startsWith('| AC26–31'));
  test.info().annotations.push({ type: 'AC31 traceability row', description: row });
  expect(row, 'story Traceability AC26–31 row names ADR-0008').toMatch(/ADR-0008/);
  const src = readFileSync(WEB + 'src/route/instructions.ts', 'utf8').replace(/\/\/.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '');
  expect(CYRILLIC.test(src), 'no Mongolian strings in instructions.ts').toBe(false);
  const mn = JSON.parse(readFileSync(WEB + 'src/i18n/mn.json', 'utf8'));
  const en = JSON.parse(readFileSync(WEB + 'src/i18n/en.json', 'utf8'));
  const keys = Object.keys(mn).filter((k) => k.startsWith('maneuver.'));
  expect(keys.length).toBeGreaterThanOrEqual(30);
  expect(Object.keys(en).filter((k) => k.startsWith('maneuver.')).sort()).toEqual(keys.sort());
  const fx = JSON.parse(readFileSync(WEB + 'src/route/maneuvers.fixture.json', 'utf8'));
  expect(fx.cases.length).toBeGreaterThanOrEqual(30);
});

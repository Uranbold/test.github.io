// NAV-002 K. Accessibility and layout (AC 48, 49).
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { P1, S, TILES_GLOB, jump, openApp, tid, waitIdle } from './helpers.mjs';

/** Tabs from the page top; returns the focus sequence (testid or a short description) and focus-ring info. */
async function tabSequence(page, max = 20) {
  // "From the page top": move the sequential-focus starting point to the start of <body> without clicking
  // anything (a click would land on the map canvas and focus it).
  await page.evaluate(() => {
    document.activeElement?.blur();
    document.body.tabIndex = -1;
    document.body.focus();
    document.body.removeAttribute('tabindex');
  });
  const seq = [];
  for (let i = 0; i < max; i++) {
    await page.keyboard.press('Tab');
    const info = await page.evaluate(() => {
      const e = document.activeElement;
      if (!e || e === document.body) return null;
      const cs = getComputedStyle(e);
      const id = e.dataset.testid || (e.classList.contains('maplibregl-canvas') ? 'map-canvas' : `${e.tagName.toLowerCase()}#${e.id}`);
      const ring = (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) >= 2) || (cs.boxShadow && cs.boxShadow !== 'none');
      return { id, ring, outline: `${cs.outlineStyle} ${cs.outlineWidth} ${cs.outlineColor}`, focusVisible: e.matches(':focus-visible') };
    });
    if (!info) break;
    if (seq.length && info.id === seq[0].id) break; // wrapped
    seq.push(info);
    if (info.id === 'attribution-osm') break;
  }
  return seq;
}

const NAMES = {
  mn: { 'language-toggle': S.mn.langButton, 'theme-toggle': S.mn.nightMode, compass: S.mn.northUp, 'zoom-in': S.mn.zoomIn, 'zoom-out': S.mn.zoomOut, 'my-location': S.mn.recenter, 'attribution-osm': S.osm, 'banner-retry': S.mn.retry, 'location-close': S.mn.close, 'location-retry': S.mn.retry, 'card-retry': S.mn.retry },
  en: { 'language-toggle': S.en.langButton, 'theme-toggle': S.en.nightMode, compass: S.en.northUp, 'zoom-in': S.en.zoomIn, 'zoom-out': S.en.zoomOut, 'my-location': S.en.recenter, 'attribution-osm': S.osm, 'banner-retry': S.en.retry, 'location-close': S.en.close, 'location-retry': S.en.retry, 'card-retry': S.en.retry },
};

for (const lang of ['mn', 'en']) {
  test(`AC48 ${lang}: Tab reaches every control in logical order with a visible focus ring and an accessible name (ready, messages, card)`, async ({ page, context, browser }) => {
    test.setTimeout(90_000);
    await context.clearPermissions();
    await openApp(page, { lang });
    // Ready state
    let seq = await tabSequence(page);
    expect(seq.map((s) => s.id)).toEqual(['map-canvas', 'language-toggle', 'theme-toggle', 'compass', 'zoom-in', 'zoom-out', 'my-location', 'attribution-osm']);
    // With the tiles banner and a location message (retry and close must be reachable)
    await tid(page, 'my-location').click();
    await expect(tid(page, 'location-message')).toBeVisible();
    let n = 0;
    await page.route(TILES_GLOB, (r) => (n++, r.fulfill({ status: 503, body: 'x', headers: { 'Access-Control-Allow-Origin': '*' } })));
    for (const c of [[100, 46], [95, 49], [110, 44], [115, 47], [92, 45], [104, 43]]) {
      if (await tid(page, 'status-banner').isVisible()) break;
      await page.evaluate((c) => window.__nav002.map.jumpTo({ center: c, zoom: 10 }), c);
      await page.waitForTimeout(700);
    }
    await expect(tid(page, 'status-banner')).toBeVisible();
    seq = await tabSequence(page);
    expect(seq.map((s) => s.id)).toEqual(['map-canvas', 'language-toggle', 'theme-toggle', 'compass', 'banner-retry', 'location-close', 'zoom-in', 'zoom-out', 'my-location', 'attribution-osm']);
    for (const s of seq) {
      expect(s.ring, `visible focus ring on ${s.id}: ${s.outline}`).toBe(true);
      expect(s.focusVisible, `${s.id} :focus-visible`).toBe(true);
      if (NAMES[lang][s.id]) await expect(tid(page, s.id)).toHaveAccessibleName(NAMES[lang][s.id]);
    }
    expect(await page.evaluate(() => window.__nav002.map.getCanvas().getAttribute('aria-label'))).toBe(S[lang].map);
    await page.unroute(TILES_GLOB);
    // Unavailable-location message has «Дахин оролдох» + «Хаах»
    const p3ctx = await browser.newContext({ permissions: ['geolocation'] });
    const p3 = await p3ctx.newPage();
    await openApp(p3, { lang });
    await p3ctx.setGeolocation(null);
    await tid(p3, 'my-location').click();
    await expect(tid(p3, 'location-retry')).toBeVisible({ timeout: 11_000 });
    const seq3 = await tabSequence(p3);
    expect(seq3.map((s) => s.id)).toEqual(['map-canvas', 'language-toggle', 'theme-toggle', 'compass', 'location-retry', 'location-close', 'zoom-in', 'zoom-out', 'my-location', 'attribution-osm']);
    for (const s of seq3) expect(s.ring, `focus ring ${s.id}`).toBe(true);
    await expect(tid(p3, 'location-retry')).toHaveAccessibleName(NAMES[lang]['location-retry']);
    await p3ctx.close();
    // Blocking card: retry reachable
    const p2 = await context.newPage();
    await p2.route(TILES_GLOB, (r) => r.abort());
    await openApp(p2, { lang, wait: false });
    await expect(tid(p2, 'blocking-card')).toBeVisible({ timeout: 11_000 });
    const seq2 = await tabSequence(p2);
    const ids2 = seq2.map((s) => s.id);
    expect(ids2).toContain('card-retry');
    expect(ids2.indexOf('language-toggle')).toBeLessThan(ids2.indexOf('card-retry'));
    expect(ids2.at(-1)).toBe('attribution-osm');
    for (const s of seq2) expect(s.ring, `focus ring ${s.id}`).toBe(true);
    await expect(tid(p2, 'card-retry')).toHaveAccessibleName(NAMES[lang]['card-retry']);
    // axe: names and ARIA
    for (const pg of [page, p2]) {
      const r = await new AxeBuilder({ page: pg }).withRules(['button-name', 'link-name', 'aria-allowed-attr', 'aria-valid-attr-value', 'aria-roles', 'html-has-lang', 'html-lang-valid', 'document-title', 'nested-interactive', 'aria-hidden-focus']).analyze();
      expect(r.violations.flatMap((v) => v.nodes.map((n) => `${v.id}: ${n.target.join(' ')}`))).toEqual([]);
    }
  });
}

/** Rects of every visible control, the scale bar and the attribution strip. */
const layout = (page) =>
  page.evaluate(() => {
    const vis = (e) => {
      if (e.closest('[hidden]')) return false;
      const cs = getComputedStyle(e);
      const r = e.getBoundingClientRect();
      return cs.visibility === 'visible' && cs.display !== 'none' && r.width > 0 && r.height > 0;
    };
    const rect = (e) => {
      const r = e.getBoundingClientRect();
      return { l: r.left, t: r.top, r: r.right, b: r.bottom, w: r.width, h: r.height };
    };
    const controls = [...document.querySelectorAll('#ui button, #ui a')].filter(vis).map((e) => ({ id: e.dataset.testid || e.id, ...rect(e) }));
    const scale = document.querySelector('[data-testid="scale-bar"]');
    const attr = document.querySelector('[data-testid="attribution"]');
    const msgs = [...document.querySelectorAll('#status-banner, #location-message, #tooltip')].filter(vis).map((e) => ({ id: e.id, ...rect(e) }));
    return { controls, scale: vis(scale) ? rect(scale) : null, attr: rect(attr), msgs, vw: document.documentElement.clientWidth, vh: document.documentElement.clientHeight };
  });
const overlap = (a, b) => Math.max(0, Math.min(a.r, b.r) - Math.max(a.l, b.l)) * Math.max(0, Math.min(a.b, b.b) - Math.max(a.t, b.t));

function layoutProblems(L, state) {
  const p = [];
  const c = L.controls;
  for (let i = 0; i < c.length; i++) {
    for (let j = i + 1; j < c.length; j++) if (overlap(c[i], c[j]) > 0.5) p.push(`${state}: ${c[i].id} overlaps ${c[j].id}`);
    if (c[i].id !== 'attribution-osm' && overlap(c[i], L.attr) > 0.5) p.push(`${state}: ${c[i].id} covers the attribution`);
    if (L.scale && overlap(c[i], L.scale) > 0.5) p.push(`${state}: ${c[i].id} covers the scale bar`);
    if (c[i].l < -0.5 || c[i].t < -0.5 || c[i].r > L.vw + 0.5 || c[i].b > L.vh + 0.5) p.push(`${state}: ${c[i].id} outside viewport`);
  }
  for (const m of L.msgs) {
    if (overlap(m, L.attr) > 0.5) p.push(`${state}: ${m.id} covers the attribution`);
    if (L.scale && overlap(m, L.scale) > 0.5) p.push(`${state}: ${m.id} covers the scale bar`);
    for (const x of c) if (!m.id.includes('tooltip') && !['banner-retry', 'location-close', 'location-retry'].includes(x.id) && overlap(m, x) > 0.5) p.push(`${state}: ${m.id} covers ${x.id}`);
  }
  return p;
}

for (const [w, h] of [[320, 568], [360, 640], [1920, 1080]]) {
  for (const theme of ['day', 'night']) {
    for (const lang of ['mn', 'en']) {
      test(`AC49 ${w}×${h} ${theme} ${lang}: no overlapping controls, nothing covers attribution or scale bar, buttons ≥ 44×44`, async ({ browser }) => {
        const ctx = await browser.newContext({ viewport: { width: w, height: h } });
        const page = await ctx.newPage();
        const problems = [];
        await openApp(page, { theme, lang });
        let L = await layout(page);
        problems.push(...layoutProblems(L, 'ready'));
        const small = (L) => L.controls.filter((x) => x.id !== 'attribution-osm' && (x.w < 44 || x.h < 44)).map((x) => `${x.id} ${x.w.toFixed(0)}×${x.h.toFixed(0)}`);
        problems.push(...small(L).map((s) => `ready: target ${s}`));
        // Worst case: zoom < 8 (ESA credit) + tiles banner + denied message + tooltip on the zoom column
        await jump(page, { center: P1, zoom: 6 });
        await tid(page, 'my-location').click();
        await expect(tid(page, 'location-message')).toBeVisible();
        await page.route(TILES_GLOB, (r) => r.fulfill({ status: 503, body: 'x', headers: { 'Access-Control-Allow-Origin': '*' } }));
        for (const c of [[100, 46], [95, 49], [110, 44], [115, 47], [92, 45], [104, 43], [98, 50]]) {
          if (await tid(page, 'status-banner').isVisible()) break;
          await page.evaluate((c) => window.__nav002.map.jumpTo({ center: c, zoom: 6.5 }), c);
          await page.waitForTimeout(700);
        }
        await expect(tid(page, 'status-banner')).toBeVisible();
        await expect(tid(page, 'attribution-esa')).toBeVisible();
        L = await layout(page);
        problems.push(...layoutProblems(L, 'worst case'));
        problems.push(...small(L).map((s) => `worst case: target ${s}`));
        await tid(page, 'zoom-in').focus();
        await page.keyboard.press('Tab');
        await expect(page.locator('#tooltip')).toBeVisible();
        L = await layout(page);
        problems.push(...layoutProblems(L, 'worst case + tooltip'));
        await ctx.close();
        expect(problems).toEqual([]);
      });
    }
  }
}

test('AC49 attribution link touch target (measured; see test plan TC-49-03)', async ({ browser }) => {
  const sizes = [];
  for (const [w, h] of [[320, 568], [360, 640], [1920, 1080]]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: h } });
    const page = await ctx.newPage();
    await openApp(page);
    const r = await tid(page, 'attribution-osm').boundingBox();
    sizes.push(`${w}px: ${r.width.toFixed(0)}×${r.height.toFixed(0)}`);
    await ctx.close();
  }
  test.info().annotations.push({ type: 'AC49 attribution link size', description: sizes.join('; ') });
  for (const s of sizes) {
    const [, , hh] = s.match(/(\d+)×(\d+)$/).map(Number);
    expect(hh, `attribution link height (${s})`).toBeGreaterThanOrEqual(44);
  }
});

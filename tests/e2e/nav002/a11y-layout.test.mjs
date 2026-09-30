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
      const id = e.dataset.testid || (e.classList.contains('maplibregl-canvas') ? 'map-canvas' : `${e.tagName.toLowerCase()}#${e.id}`);
      const hasRing = (el) => {
        const c = getComputedStyle(el);
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0 && ((c.outlineStyle !== 'none' && parseFloat(c.outlineWidth) >= 2) || (c.boxShadow && c.boxShadow !== 'none'));
      };
      // Screen spec › Components › Attribution strip (2026-09-30): the link's ring is drawn around its inner <span>
      // (the text), not around the transparent 44 px hit area. So a ring on a direct child <span> counts, but only
      // when the focused element itself has none. Changed 2026-09-30 (run 5) to follow the spec, not a looser check.
      let ringEl = e;
      if (!hasRing(e)) {
        const sp = e.querySelector(':scope > span');
        if (sp && hasRing(sp)) ringEl = sp;
      }
      // NAV-003 screen spec › Components › Search field (2026-09-30): the search input has no outline of its own; the ring
      // is drawn on its parent [data-testid=search-field] via :focus-within. Counted only for search-input, only when the
      // input itself has none. Added 2026-09-30 with the NAV-003 Tab-order change (qa-engineer, NAV-003 run 1).
      if (!hasRing(ringEl) && e.dataset.testid === 'search-input') {
        const f = e.closest('[data-testid="search-field"]');
        if (f && hasRing(f)) ringEl = f;
      }
      const cs = getComputedStyle(ringEl);
      const on = ringEl === e ? 'self' : ringEl.dataset?.testid === 'search-field' ? 'search-field (focus-within)' : 'inner span';
      return { id, ring: hasRing(ringEl), outline: `${on}: ${cs.outlineStyle} ${cs.outlineWidth} ${cs.outlineColor}`, focusVisible: e.matches(':focus-visible') };
    });
    if (!info) break;
    if (seq.length && info.id === seq[0].id) break; // wrapped
    seq.push(info);
    if (info.id === 'attribution-osm') break;
  }
  return seq;
}

const NAMES = {
  mn: { 'search-input': 'Хайх', 'language-toggle': S.mn.langButton, 'theme-toggle': S.mn.nightMode, compass: S.mn.northUp, 'zoom-in': S.mn.zoomIn, 'zoom-out': S.mn.zoomOut, 'my-location': S.mn.recenter, 'attribution-osm': S.osm, 'banner-retry': S.mn.retry, 'location-close': S.mn.close, 'location-retry': S.mn.retry, 'card-retry': S.mn.retry },
  en: { 'search-input': 'Search', 'language-toggle': S.en.langButton, 'theme-toggle': S.en.nightMode, compass: S.en.northUp, 'zoom-in': S.en.zoomIn, 'zoom-out': S.en.zoomOut, 'my-location': S.en.recenter, 'attribution-osm': S.osm, 'banner-retry': S.en.retry, 'location-close': S.en.close, 'location-retry': S.en.retry, 'card-retry': S.en.retry },
};

for (const lang of ['mn', 'en']) {
  test(`AC48 ${lang}: Tab reaches every control in logical order with a visible focus ring and an accessible name (ready, messages, card)`, async ({ page, context, browser }) => {
    test.setTimeout(90_000);
    await context.clearPermissions();
    // Negative control (test-only, off by default): NAV002_AC48_NEGCTL=1 removes the attribution link's inner ring,
    // so this test must FAIL on attribution-osm.
    if (process.env.NAV002_AC48_NEGCTL) {
      await page.addInitScript(() => {
        const st = document.createElement('style');
        st.textContent = '.attr a:focus-visible > span{outline:none !important}';
        const mo = new MutationObserver(() => {
          if (!document.head) return;
          mo.disconnect();
          document.head.appendChild(st);
        });
        mo.observe(document, { childList: true, subtree: true });
      });
    }
    await openApp(page, { lang });
    // Ready state
    let seq = await tabSequence(page);
    // Tab order changed by NAV-003 AC 1 (search input is the first Tab stop; NAV-003 screen spec › Tab order, 2026-09-30).
    // Previous NAV-002 expectation: map-canvas, language-toggle, theme-toggle, compass, zoom-in, zoom-out, my-location,
    // attribution-osm. Updated by qa-engineer in the NAV-003 run (story NAV-003 › Test approach allows it).
    expect(seq.map((s) => s.id)).toEqual(['search-input', 'language-toggle', 'theme-toggle', 'compass', 'map-canvas', 'zoom-in', 'zoom-out', 'my-location', 'attribution-osm']);
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
    expect(seq.map((s) => s.id)).toEqual(['search-input', 'language-toggle', 'theme-toggle', 'compass', 'map-canvas', 'banner-retry', 'location-close', 'zoom-in', 'zoom-out', 'my-location', 'attribution-osm']);
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
    expect(seq3.map((s) => s.id)).toEqual(['search-input', 'language-toggle', 'theme-toggle', 'compass', 'map-canvas', 'location-retry', 'location-close', 'zoom-in', 'zoom-out', 'my-location', 'attribution-osm']);
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
        // Every touch target, including the attribution link (run 4: the link is no longer exempt, TC-49-03).
        const small = (L) => L.controls.filter((x) => x.w < 44 - 0.5 || x.h < 44 - 0.5).map((x) => `${x.id} ${x.w.toFixed(0)}×${x.h.toFixed(0)}`);
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

const attrGeometry = (page) =>
  page.evaluate(() => {
    const R = (e) => { const r = e.getBoundingClientRect(); return { l: r.left, t: r.top, r: r.right, b: r.bottom, w: r.width, h: r.height }; };
    const vis = (e) => !!e && !e.closest('[hidden]') && getComputedStyle(e).display !== 'none' && e.getBoundingClientRect().width > 0;
    const link = document.querySelector('[data-testid="attribution-osm"]');
    const strip = document.querySelector('[data-testid="attribution"]');
    const cs = getComputedStyle(strip);
    const others = [...document.querySelectorAll('#ui button')].filter(vis).map((e) => ({ id: e.dataset.testid || e.id, ...R(e) }));
    const scale = document.querySelector('[data-testid="scale-bar"]');
    return {
      link: R(link), strip: R(strip), others, scale: vis(scale) ? R(scale) : null,
      // Visible strip height without the bottom safe-area inset (0 in desktop Chromium).
      stripH: strip.getBoundingClientRect().height,
      padB: cs.paddingBottom, vw: document.documentElement.clientWidth, vh: document.documentElement.clientHeight,
      esa: vis(document.querySelector('[data-testid="attribution-esa"]')),
    };
  });

test('AC49 TC-49-03 attribution link touch target ≥ 44×44, inside the viewport, overlapping no control and not the scale bar', async ({ browser }) => {
  const rows = [];
  const problems = [];
  for (const [w, h] of [[320, 568], [360, 640], [1920, 1080]]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: h } });
    const page = await ctx.newPage();
    await openApp(page);
    for (const zoom of [12, 6]) { // 6: ESA line shown (zoom < 8), strip has two or more lines
      await jump(page, { center: P1, zoom });
      await page.waitForTimeout(300);
      const g = await attrGeometry(page);
      const L = g.link;
      rows.push(`${w}px z${zoom}: link ${L.w.toFixed(0)}×${L.h.toFixed(0)}, strip ${g.stripH.toFixed(0)} px${g.esa ? ' (ESA)' : ''}`);
      if (L.w < 44 - 0.5 || L.h < 44 - 0.5) problems.push(`${w}px z${zoom}: link box ${L.w.toFixed(1)}×${L.h.toFixed(1)} < 44×44`);
      if (L.l < -0.5 || L.t < -0.5 || L.r > g.vw + 0.5 || L.b > g.vh + 0.5) problems.push(`${w}px z${zoom}: link box outside the viewport`);
      for (const o of g.others) if (overlap(L, o) > 0.5) problems.push(`${w}px z${zoom}: link box overlaps ${o.id}`);
      if (g.scale && overlap(L, g.scale) > 0.5) problems.push(`${w}px z${zoom}: link box overlaps the scale bar`);
    }
    await ctx.close();
  }
  test.info().annotations.push({ type: 'AC49 attribution geometry', description: rows.join('; ') });
  expect(problems).toEqual([]);
});

test('AC49 TC-49-04 screen spec rule 6a: the visible attribution strip is not enlarged by the hit area (≤ 24 px at one line)', async ({ browser }) => {
  // Design conformance (docs/design/screens/NAV-002-web-map.md, Components › Attribution strip, Layout rule 6a,
  // 2026-09-30): the link's 44 px hit area comes from transparent padding with equal negative margins, so the strip
  // stays 24 px high at one line (12/16 caption + 4 px padding top and bottom). The story AC 49 itself is TC-49-03.
  const rows = [];
  for (const [w, h] of [[320, 568], [360, 640], [1920, 1080]]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: h } });
    const page = await ctx.newPage();
    await openApp(page);
    await jump(page, { center: P1, zoom: 12 }); // zoom ≥ 8: OSM line only
    await page.waitForTimeout(300);
    const g = await attrGeometry(page);
    rows.push({ w, stripH: g.stripH, esa: g.esa });
    await ctx.close();
  }
  test.info().annotations.push({ type: 'TC-49-04 strip height at one line', description: rows.map((r) => `${r.w}px: ${r.stripH.toFixed(1)} px`).join('; ') });
  for (const r of rows) {
    expect(r.esa, `${r.w}px: ESA line hidden at z12`).toBe(false);
    expect(r.stripH, `${r.w}px: visible strip height at one line (screen spec: 24 px)`).toBeLessThanOrEqual(24.5);
  }
});

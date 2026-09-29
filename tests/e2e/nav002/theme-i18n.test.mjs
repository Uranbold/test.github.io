// NAV-002 F. Day and night style (AC 26–29) and G. Language (AC 30–31).
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {
  CYRILLIC, LABEL_EXPRESSION, P1, S, attributionProblems, camera, jump, normColor, openApp, renderedLabels, tid, token, waitIdle,
} from './helpers.mjs';

const MODE = { day: 'light', night: 'night' };

async function mapColours(page) {
  return page.evaluate(() => {
    const m = window.__nav002.map;
    const p = (id, prop) => (m.getLayer(id) ? m.getPaintProperty(id, prop) : undefined);
    return {
      background: p('background', 'background-color'),
      earth: p('earth', 'fill-color'),
      water: p('water', 'fill-color'),
      major: p('roads_major', 'line-color'),
      roadLabel: p('roads_labels_major', 'text-color'),
      cityLabel: p('places_locality', 'text-color'),
    };
  });
}
/** For an expression, the value used for ordinary major roads at z ≥ 10 (last output of step/match). */
function majorAtHighZoom(expr) {
  if (typeof expr === 'string') return expr;
  // ["step", ["zoom"], lowValue, 10, highValue] where highValue = ["match", ["get","kind_detail"], [trunk...], trunkColour, otherColour]
  const high = expr[0] === 'step' ? expr[expr.length - 1] : expr;
  return Array.isArray(high) && high[0] === 'match' ? high[high.length - 1] : high;
}
async function uiColours(page) {
  return page.evaluate(() => {
    const cs = (sel, prop) => getComputedStyle(document.querySelector(sel))[prop];
    return {
      surface: cs('[data-testid="theme-toggle"]', 'backgroundColor'),
      attrBg: cs('[data-testid="attribution"]', 'backgroundColor'),
      attrText: cs('[data-testid="attribution-osm"]', 'color'),
      onSurface: cs('[data-testid="scale-label"]', 'color'),
    };
  });
}
function expectTheme(colours, ui, theme) {
  const mode = MODE[theme];
  const f = (k) => normColor(token(mode, `map.flavor.${k}`));
  expect(normColor(colours.background), `${theme} background`).toBe(f('background'));
  expect(normColor(colours.earth), `${theme} earth`).toBe(f('earth'));
  expect(normColor(colours.water), `${theme} water`).toBe(f('water'));
  expect(normColor(majorAtHighZoom(colours.major)), `${theme} major road`).toBe(f('major'));
  expect(normColor(colours.roadLabel), `${theme} major road label`).toBe(f('roads_label_major'));
  expect(normColor(ui.surface), `${theme} ui.surface`).toBe(normColor(token(mode, 'ui.surface')));
  expect(normColor(ui.attrBg), `${theme} attribution background`).toBe(normColor(token(mode, 'ui.surface-container')));
  expect(normColor(ui.attrText), `${theme} attribution text`).toBe(normColor(token(mode, 'ui.on-surface-variant')));
}

/**
 * axe-core color-contrast, plus a WCAG 2.1 contrast computation for nodes axe reports as "incomplete".
 * The overlay grid has pointer-events: none (screen spec), so elementsFromPoint() skips the message's
 * ancestors and axe reports "bgOverlap" although the text sits on its own opaque surface. For those nodes
 * the ratio is computed from the text colour and the nearest opaque ancestor background; a translucent
 * background on the way counts as undecided.
 */
async function axeContrast(page, state) {
  const r = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze();
  const v = r.violations.flatMap((x) => x.nodes.map((n) => `${state}: ${n.target.join(' ')} ${n.any?.[0]?.message ?? ''}`));
  const inc = [];
  const manual = [];
  for (const n of r.incomplete.flatMap((x) => x.nodes)) {
    const sel = n.target.join(' ');
    const m = await page.evaluate(manualContrast, sel);
    if (m.undecided) inc.push(`${state}: ${sel} (${m.undecided})`);
    else if (m.ratio < 4.5) v.push(`${state}: ${sel} manual ratio ${m.ratio.toFixed(2)} (${m.fg} on ${m.bg})`);
    else manual.push(`${sel} ${m.ratio.toFixed(2)}`);
  }
  return { v, inc, manual, passes: r.passes.reduce((s, x) => s + x.nodes.length, 0) + manual.length };
}
function manualContrast(sel) {
  const el = document.querySelector(sel);
  if (!el) return { undecided: 'element gone' };
  const parse = (c) => {
    const m = c.match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    const [r, g, b, a = 1] = m[1].split(/[ ,/]+/).filter(Boolean).map(Number);
    return { r, g, b, a };
  };
  const lum = ({ r, g, b }) => {
    const f = (v) => ((v /= 255) <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
  };
  const fg = parse(getComputedStyle(el).color);
  if (!fg || fg.a < 1) return { undecided: 'translucent text' };
  let bg = null;
  for (let x = el; x; x = x.parentElement) {
    const cs = getComputedStyle(x);
    if (cs.backgroundImage !== 'none') return { undecided: 'background image' };
    const c = parse(cs.backgroundColor);
    if (c && c.a > 0) {
      if (c.a < 1) return { undecided: 'translucent background' };
      bg = c;
      break;
    }
  }
  if (!bg) return { undecided: 'no opaque background' };
  const [l1, l2] = [lum(fg), lum(bg)].sort((a, b) => b - a);
  return { ratio: (l1 + 0.05) / (l2 + 0.05), fg: getComputedStyle(el).color, bg: `rgb(${bg.r},${bg.g},${bg.b})` };
}

test.describe('NAV-002 F. Day and night', () => {
  test('AC26 first visit: day style active', async ({ page }) => {
    await openApp(page);
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'day');
    expectTheme(await mapColours(page), await uiColours(page), 'day');
    await expect(tid(page, 'theme-toggle')).toHaveAccessibleName(S.mn.nightMode);
  });

  test('AC27 toggle: within 2 s map + UI switch to spec values; camera unchanged; label rule, marker and attribution kept', async ({ page, context }) => {
    await context.grantPermissions(['geolocation']);
    await context.setGeolocation({ latitude: P1.lat, longitude: P1.lng, accuracy: 30 });
    await openApp(page);
    await tid(page, 'my-location').click();
    await expect(tid(page, 'my-location')).toHaveAttribute('data-state', 'following', { timeout: 3000 });
    await page.waitForFunction(() => !window.__nav002.map.isMoving());
    await jump(page, { bearing: 30 });
    await waitIdle(page);
    for (const theme of ['night', 'day']) {
      const before = await camera(page);
      const px0 = await page.evaluate(() => window.__nav002.map.project(window.__nav002.map.getCenter()));
      const dt = await page.evaluate(
        (theme) =>
          new Promise((resolve) => {
            const m = window.__nav002.map;
            const t0 = performance.now();
            document.querySelector('[data-testid="theme-toggle"]').click();
            const check = () => {
              if (document.documentElement.dataset.theme === theme && m.isStyleLoaded() && m.loaded() && m.areTilesLoaded()) return resolve(performance.now() - t0);
              if (performance.now() - t0 > 5000) return resolve(9999);
              setTimeout(check, 20);
            };
            m.once('idle', check);
            setTimeout(check, 50);
          }),
        theme,
      );
      test.info().annotations.push({ type: `AC27 switch to ${theme} (ms to idle)`, description: String(Math.round(dt)) });
      expect(dt).toBeLessThanOrEqual(2000);
      expectTheme(await mapColours(page), await uiColours(page), theme);
      const after = await camera(page);
      const px1 = await page.evaluate((c) => window.__nav002.map.project([c.lng, c.lat]), before);
      expect(Math.hypot(px1.x - px0.x, px1.y - px0.y), 'centre ±1 px').toBeLessThanOrEqual(1);
      expect(Math.abs(after.zoom - before.zoom)).toBeLessThanOrEqual(0.01);
      expect(Math.abs(after.bearing - before.bearing)).toBeLessThanOrEqual(0.5);
      const tfs = await page.evaluate(() => window.__nav002.map.getStyle().layers.filter((l) => l.type === 'symbol' && JSON.stringify(l.layout?.['text-field'] ?? '').includes('"name')).map((l) => JSON.stringify(l.layout['text-field'])));
      expect(tfs.length).toBeGreaterThanOrEqual(11);
      tfs.forEach((t) => expect(t).toBe(JSON.stringify(LABEL_EXPRESSION)));
      await expect(tid(page, 'location-marker')).toBeVisible();
      const acc = await page.evaluate(() => window.__nav002.map.queryRenderedFeatures({ layers: ['nav-location-accuracy-fill'] }).length);
      expect(acc, 'accuracy circle kept after style switch').toBeGreaterThan(0);
      expect(normColor(await tid(page, 'location-marker').evaluate((e) => getComputedStyle(e).backgroundColor))).toBe(normColor(token(MODE[theme], 'location.dot')));
      expect(await attributionProblems(page)).toEqual([]);
    }
  });

  test('AC28 chosen mode survives a reload', async ({ page }) => {
    await openApp(page);
    await tid(page, 'theme-toggle').click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'night');
    await page.reload();
    await expect(tid(page, 'zoom-in')).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'night');
    await waitIdle(page);
    expectTheme(await mapColours(page), await uiColours(page), 'night');
    await tid(page, 'theme-toggle').click();
    await page.reload();
    await expect(tid(page, 'zoom-in')).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'day');
  });

  for (const theme of ['day', 'night']) {
    for (const lang of ['mn', 'en']) {
      test(`AC29 ${theme}/${lang}: axe-core colour contrast (WCAG 2.1 AA ≥ 4.5:1) in ready, message, banner and card states`, async ({ page, context }) => {
        test.setTimeout(90_000);
        const scan = (state, pg = page) => axeContrast(pg, state);
        const all = { v: [], inc: [], passes: 0 };
        const add = (r) => ((all.v.push(...r.v)), all.inc.push(...r.inc), (all.passes += r.passes));
        await context.clearPermissions();
        await openApp(page, { theme, lang });
        await jump(page, { zoom: 6 }); // ESA line visible too
        add(await scan('ready'));
        await tid(page, 'my-location').click();
        await expect(tid(page, 'location-message')).toBeVisible();
        add(await scan('denied message'));
        // Tooltip (keyboard focus)
        await tid(page, 'zoom-out').focus();
        await page.keyboard.press('Shift+Tab');
        await expect(page.locator('#tooltip')).toBeVisible();
        add(await scan('tooltip'));
        // Tiles banner: fail every tile request, pan to new areas
        await page.route('**/tiles/basemap.pmtiles', (r) => r.fulfill({ status: 503, body: 'x' }));
        for (const c of [[100, 46], [95, 49], [110, 44], [115, 47], [92, 45]]) {
          await page.evaluate((c) => window.__nav002.map.jumpTo({ center: c, zoom: 9 }), c);
          await page.waitForTimeout(600);
          if (await tid(page, 'status-banner').isVisible()) break;
        }
        await expect(tid(page, 'status-banner')).toHaveAttribute('data-reason', 'tiles');
        add(await scan('tiles banner'));
        await context.setOffline(true);
        await expect(tid(page, 'status-banner')).toHaveAttribute('data-reason', 'offline');
        add(await scan('offline banner'));
        await context.setOffline(false);
        // Blocking card and loading pill on a fresh load with the archive failing / delayed
        const p2 = await context.newPage();
        await p2.route('**/tiles/basemap.pmtiles', (r) => r.abort());
        await openApp(p2, { theme, lang, wait: false });
        await expect(tid(p2, 'blocking-card')).toBeVisible({ timeout: 11_000 });
        add(await scan('card', p2));
        const p3 = await context.newPage();
        await p3.route('**/tiles/basemap.pmtiles', async (r) => {
          await new Promise((res) => setTimeout(res, 3000));
          await r.continue().catch(() => {});
        });
        await openApp(p3, { theme, lang, wait: false });
        await expect(tid(p3, 'loading')).toBeVisible({ timeout: 3000 });
        await expect(tid(p3, 'loading')).toContainText(S[lang].loading);
        add(await scan('loading', p3));
        test.info().annotations.push({ type: `AC29 ${theme}/${lang}`, description: `${all.passes} contrast checks passed, ${all.v.length} violations, ${all.inc.length} incomplete: ${all.inc.slice(0, 5).join('; ')}` });
        expect(all.v).toEqual([]);
        // Nodes axe could not decide were decided by manualContrast(); none may remain undecided.
        expect(all.inc).toEqual([]);
        expect(all.passes).toBeGreaterThan(10);
      });
    }
  }
});

test.describe('NAV-002 G. Language', () => {
  test.describe('browser language en-US', () => {
    test.use({ locale: 'en-US', extraHTTPHeaders: { 'Accept-Language': 'en-US,en;q=0.9' } });
    test('AC30 first visit with en-US browser: UI is Mongolian and <html lang="mn">', async ({ page }) => {
      await openApp(page);
      expect(await page.evaluate(() => navigator.language)).toBe('en-US');
      await expect(page.locator('html')).toHaveAttribute('lang', 'mn');
      await expect(page).toHaveTitle(S.mn.map);
      await expect(tid(page, 'zoom-in')).toHaveAccessibleName(S.mn.zoomIn);
      await expect(tid(page, 'language-toggle')).toHaveText(S.mn.langButton);
      await expect(tid(page, 'scale-label')).toHaveText(/ (м|км)$/);
    });
  });

  const uiStrings = (page) =>
    page.evaluate(() => {
      const out = [];
      const ui = document.querySelector('#ui');
      const walker = document.createTreeWalker(ui, NodeFilter.SHOW_TEXT);
      for (let n = walker.nextNode(); n; n = walker.nextNode()) {
        const el = n.parentElement;
        if (!el || el.closest('[hidden]') || !n.textContent.trim()) continue;
        out.push({ where: el.dataset.testid || el.id || el.className, text: n.textContent.trim(), lang: el.closest('[lang]')?.getAttribute('lang') });
      }
      for (const el of document.querySelectorAll('[aria-label]')) out.push({ where: 'aria-label ' + (el.dataset.testid || el.className), text: el.getAttribute('aria-label'), lang: el.closest('[lang]')?.getAttribute('lang') });
      for (const el of document.querySelectorAll('[aria-describedby]')) {
        const d = document.getElementById(el.getAttribute('aria-describedby'));
        if (d?.textContent.trim()) out.push({ where: 'description ' + el.dataset.testid, text: d.textContent.trim() });
      }
      out.push({ where: 'title', text: document.title });
      return out;
    });

  test('AC31 switch to English: every UI string English within 500 ms, <html lang="en">, no reload, no camera or label change; survives reload', async ({ page, context }) => {
    await context.clearPermissions();
    await openApp(page);
    await jump(page, { center: P1, zoom: 14, bearing: 20 });
    // Open a message first: it must switch language too (edge case).
    await tid(page, 'my-location').click();
    await expect(tid(page, 'location-message')).toBeVisible();
    const labelsBefore = (await renderedLabels(page)).map((l) => l.text).sort();
    const camBefore = await camera(page);
    await page.evaluate(() => (window.__noReload = true));
    const dt = await page.evaluate(
      () =>
        new Promise((resolve) => {
          const t0 = performance.now();
          document.querySelector('[data-testid="language-toggle"]').click();
          const check = () => {
            const ok = document.documentElement.lang === 'en' && document.title === 'Map' && document.querySelector('[data-testid="zoom-in"]').getAttribute('aria-label') === 'Zoom in';
            if (ok) return resolve(performance.now() - t0);
            if (performance.now() - t0 > 3000) return resolve(9999);
            requestAnimationFrame(check);
          };
          check();
        }),
    );
    test.info().annotations.push({ type: 'AC31 switch time (ms)', description: dt.toFixed(1) });
    expect(dt).toBeLessThanOrEqual(500);
    expect(await page.evaluate(() => window.__noReload)).toBe(true);
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(page).toHaveTitle(S.en.map);
    // Every control / message string
    await expect(tid(page, 'zoom-in')).toHaveAccessibleName(S.en.zoomIn);
    await expect(tid(page, 'zoom-out')).toHaveAccessibleName(S.en.zoomOut);
    await expect(tid(page, 'compass')).toHaveAccessibleName(S.en.northUp);
    await expect(tid(page, 'my-location')).toHaveAccessibleName(S.en.recenter);
    await expect(tid(page, 'theme-toggle')).toHaveAccessibleName(S.en.nightMode);
    await expect(tid(page, 'language-toggle')).toHaveText(S.en.langButton);
    await expect(tid(page, 'language-toggle')).toHaveAttribute('lang', 'mn');
    await expect(tid(page, 'language-toggle')).toHaveAccessibleDescription('Language');
    await expect(tid(page, 'location-message')).toContainText(S.en.denied);
    await expect(tid(page, 'location-message')).toContainText(S.en.deniedHint);
    await expect(tid(page, 'location-close')).toHaveText(S.en.close);
    await expect(tid(page, 'my-location')).toHaveAccessibleDescription(S.en.denied);
    await expect(tid(page, 'scale-label')).toHaveText(/^\d+(\.\d+)? (m|km)$/);
    expect(await page.evaluate(() => window.__nav002.map.getCanvas().getAttribute('aria-label'))).toBe(S.en.map);
    // Tooltip on keyboard focus
    await tid(page, 'zoom-in').focus();
    await page.keyboard.press('Tab'); // keyboard focus (:focus-visible) on «Zoom out»
    await expect(tid(page, 'zoom-out')).toBeFocused();
    await expect(page.locator('#tooltip')).toBeVisible();
    await expect(page.locator('#tooltip')).toHaveText(S.en.zoomOut);
    // No Cyrillic left anywhere in the UI except the «Монгол» button (lang="mn")
    const cyr = (await uiStrings(page)).filter((s) => CYRILLIC.test(s.text) && s.lang !== 'mn');
    expect(cyr).toEqual([]);
    // Map labels and camera unchanged
    const labelsAfter = (await renderedLabels(page)).map((l) => l.text).sort();
    expect(labelsAfter).toEqual(labelsBefore);
    const camAfter = await camera(page);
    expect(camAfter).toEqual(camBefore);
    // Survives reload
    await page.reload();
    await expect(tid(page, 'zoom-in')).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(page).toHaveTitle(S.en.map);
    await expect(tid(page, 'zoom-in')).toHaveAccessibleName(S.en.zoomIn);
  });

  test('AC31 English message texts for every state (tiles card, offline banner, loading)', async ({ page, context }) => {
    await page.route('**/tiles/basemap.pmtiles', (r) => r.abort());
    await openApp(page, { lang: 'en', wait: false });
    await expect(tid(page, 'blocking-card')).toContainText(S.en.tiles, { timeout: 11_000 });
    await expect(tid(page, 'card-retry')).toHaveText(S.en.retry);
    await page.unroute('**/tiles/basemap.pmtiles');
    await tid(page, 'card-retry').click();
    await expect(tid(page, 'zoom-in')).toBeVisible({ timeout: 10_000 });
    await context.setOffline(true);
    await expect(tid(page, 'status-banner')).toContainText(S.en.offline);
    await context.setOffline(false);
  });
});

// NAV-002 A (AC 2, 3) and B. Basemap and labels (AC 5–10).
import { test, expect } from '@playwright/test';
import {
  APP, CYRILLIC, GOBI, LABEL_EXPRESSION, MN_LETTER, P1, TILES_URL, X2,
  camera, collectErrors, haversine, jump, openApp, renderedLabels, renderedRoadCount, statusView, tid, waitIdle,
} from './helpers.mjs';

test.describe('NAV-002 A. Configuration (gateway base URL)', () => {
  test('AC2 no setting: tiles are read from pmtiles://http://localhost:8080/tiles/basemap.pmtiles', async ({ page }) => {
    const tileReqs = [];
    page.on('request', (r) => r.url().includes('basemap.pmtiles') && tileReqs.push(r.url()));
    await openApp(page);
    const src = await page.evaluate(() => window.__nav002.map.getStyle().sources.protomaps.url);
    expect(src).toBe('pmtiles://http://localhost:8080/tiles/basemap.pmtiles');
    expect(tileReqs.length).toBeGreaterThan(0);
    expect([...new Set(tileReqs)]).toEqual([TILES_URL]);
  });

  for (const [port, value] of [[5175, 'http://localhost:8081/'], [5176, 'http://localhost:8081']]) {
    test(`AC3 VITE_GATEWAY_BASE_URL=${value}: every tile request goes to http://localhost:8081/tiles/basemap.pmtiles, none to 8080`, async ({ page }) => {
      const all = [];
      page.on('request', (r) => all.push(r.url()));
      // Nothing listens on 8081. Forward 8081 to the real gateway inside the test, so the map renders and
      // many tile requests are made (the gateway itself is untouched).
      await page.route('http://localhost:8081/**', async (route) => {
        const req = route.request();
        const url = req.url().replace('http://localhost:8081', 'http://localhost:8080');
        const resp = await route.fetch({ url, headers: req.headers() });
        await route.fulfill({ response: resp });
      });
      await openApp(page, { url: `http://localhost:${port}/` });
      await jump(page, { center: P1, zoom: 14 });
      const src = await page.evaluate(() => window.__nav002.map.getStyle().sources.protomaps.url);
      expect(src).toBe('pmtiles://http://localhost:8081/tiles/basemap.pmtiles');
      const tiles = all.filter((u) => u.includes('.pmtiles'));
      expect(tiles.length).toBeGreaterThan(1);
      expect([...new Set(tiles)]).toEqual(['http://localhost:8081/tiles/basemap.pmtiles']);
      expect(all.filter((u) => u.startsWith('http://localhost:8080'))).toEqual([]);
      expect(all.filter((u) => /\/\/tiles/.test(u.replace(/^https?:\/\//, '')))).toEqual([]);
    });
  }
});

test.describe('NAV-002 B. Basemap and labels', () => {
  test('AC5 first open: centre P1 ±100 m, zoom 12 ±0.1, bearing 0, idle with roads and labels within 5 s', async ({ page }) => {
    const errors = collectErrors(page);
    await openApp(page, { wait: false });
    // Ready = first `idle` with basemap tiles (app shows the bottom controls at that moment).
    await expect(tid(page, 'zoom-in')).toBeVisible({ timeout: 15_000 });
    const tReady = await page.evaluate(() => (window.__t.find((s) => s.ready) || {}).t ?? null);
    test.info().annotations.push({ type: 'AC5 ready ms after navigation start', description: String(Math.round(tReady)) });
    expect(tReady).not.toBeNull();
    expect(tReady).toBeLessThanOrEqual(5000);
    const cam = await camera(page);
    expect(haversine(cam, P1)).toBeLessThanOrEqual(100);
    expect(Math.abs(cam.zoom - 12)).toBeLessThanOrEqual(0.1);
    expect(Math.abs(cam.bearing)).toBeLessThanOrEqual(0.5);
    expect(await renderedRoadCount(page)).toBeGreaterThan(0);
    const labels = (await renderedLabels(page)).filter((l) => l.text);
    expect(labels.length).toBeGreaterThan(0);
    expect(errors).toEqual([]);
  });

  test('AC6 z14 at P1: ≥10 labels, one with ө/ү, Cyrillic glyph range 200 from the bundle for every font stack in use (+R2 baseline)', async ({ page }) => {
    const glyphs = [];
    page.on('response', (r) => {
      if (r.url().includes('/fonts/')) glyphs.push({ url: decodeURIComponent(r.url()), status: r.status() });
    });
    await openApp(page);
    await jump(page, { center: P1, zoom: 14 });
    const labels = (await renderedLabels(page)).filter((l) => l.text && !['address_label', 'roads_shields'].includes(l.layer));
    const texts = [...new Set(labels.map((l) => l.text))];
    test.info().annotations.push({ type: 'AC6 labels at z14', description: `${labels.length} rendered, ${texts.length} distinct` });
    expect(labels.length).toBeGreaterThanOrEqual(10);
    expect(texts.some((t) => MN_LETTER.test(t)), `a label with ө/ү among: ${texts.slice(0, 20).join(', ')}`).toBe(true);

    // Font stacks in use by the rendered label layers
    const stacks = await page.evaluate((layerIds) => {
      const m = window.__nav002.map;
      const out = new Set();
      for (const id of layerIds) {
        const f = m.getLayoutProperty(id, 'text-font');
        JSON.stringify(f).match(/Noto Sans [A-Za-z]+/g)?.forEach((s) => out.add(s));
      }
      return [...out];
    }, [...new Set(labels.map((l) => l.layer))]);
    expect(stacks.length).toBeGreaterThan(0);
    for (const s of stacks) {
      const reqs = glyphs.filter((g) => g.url.startsWith(`http://localhost:5173/fonts/${s}/1024-1279.pbf`));
      expect(reqs.length, `Cyrillic range requested for ${s}`).toBeGreaterThan(0);
      for (const r of reqs) expect(r.status, r.url).toBe(200);
    }
    // Every Cyrillic-range request seen (any stack) was 200 from the page origin.
    for (const g of glyphs.filter((g) => g.url.includes('1024-1279'))) {
      expect(g.url.startsWith('http://localhost:5173/fonts/')).toBe(true);
      expect(g.status).toBe(200);
    }
    // R2 (information): share of rendered name labels with no Cyrillic character.
    const nonCyr = texts.filter((t) => !CYRILLIC.test(t));
    test.info().annotations.push({ type: 'R2 baseline (info)', description: `${nonCyr.length}/${texts.length} distinct labels at z14 around P1 have no Cyrillic: ${nonCyr.slice(0, 15).join(' | ')}` });
  });

  for (const theme of ['day', 'night']) {
    test(`AC7 ${theme}: every name-showing symbol layer uses coalesce(name:mn, name, name:en); no other name:* key`, async ({ page }) => {
      await openApp(page, { theme });
      const layers = await page.evaluate(() => window.__nav002.map.getStyle().layers);
      const keysOf = (e, acc = []) => {
        if (Array.isArray(e)) {
          if ((e[0] === 'get' || e[0] === 'has') && typeof e[1] === 'string') acc.push(e[1]);
          e.forEach((x) => keysOf(x, acc));
        } else if (e && typeof e === 'object') Object.values(e).forEach((x) => keysOf(x, acc));
        return acc;
      };
      const nameLayers = [];
      const problems = [];
      for (const l of layers) {
        const allKeys = keysOf([l.layout, l.paint, l.filter]);
        const bad = allKeys.filter((k) => /^name:/.test(k) && !['name:mn', 'name:en'].includes(k));
        if (bad.length) problems.push(`${l.id} reads ${bad.join(',')}`);
        if (allKeys.some((k) => /^(pgf:|name2$|name3$)/.test(k))) problems.push(`${l.id} reads pgf/name2/name3`);
        if (l.type !== 'symbol') continue;
        const tf = l.layout?.['text-field'];
        if (tf === undefined) continue;
        const tfKeys = keysOf(tf);
        if (tfKeys.some((k) => k === 'name' || k.startsWith('name:'))) {
          nameLayers.push(l.id);
          if (JSON.stringify(tf) !== JSON.stringify(LABEL_EXPRESSION)) problems.push(`${l.id} text-field ${JSON.stringify(tf)}`);
        }
      }
      expect(problems).toEqual([]);
      // The screen/style spec lists these name layers (map-style.md §4.1); all must be present and use the rule.
      for (const id of ['places_country', 'places_region', 'places_locality', 'places_subplace', 'pois', 'roads_labels_major', 'roads_labels_minor', 'water_label_lakes', 'water_label_ocean', 'water_waterway_label', 'earth_label_islands']) {
        expect(nameLayers, `name layer ${id}`).toContain(id);
      }
    });
  }

  test('AC7 style switch day → night at runtime keeps the label rule on every name layer', async ({ page }) => {
    await openApp(page);
    await tid(page, 'theme-toggle').click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'night');
    await waitIdle(page);
    const tfs = await page.evaluate(() => window.__nav002.map.getStyle().layers.filter((l) => l.type === 'symbol' && JSON.stringify(l.layout?.['text-field'] ?? '').includes('"name')).map((l) => JSON.stringify(l.layout['text-field'])));
    expect(tfs.length).toBeGreaterThanOrEqual(11);
    for (const tf of tfs) expect(tf).toBe(JSON.stringify(LABEL_EXPRESSION));
  });

  test('AC8 fixture: (a) «Тест А», (b) «Тест Б», (c) "Test C", no-name feature renders no label and no error', async ({ page }) => {
    const errors = collectErrors(page);
    await page.goto('/fixtures/label-rule.html');
    await page.waitForFunction(() => !!window.__labelRule);
    await page.evaluate(() => window.__labelRule.ready);
    const res = await page.evaluate(() => {
      const m = window.__labelRule.map;
      const expr = m.getLayoutProperty('fixture-labels', 'text-field');
      const feats = m.queryRenderedFeatures({ layers: ['fixture-labels'] });
      // MapLibre only returns symbol features that were placed, i.e. that have a label.
      const byCase = {};
      for (const f of feats) byCase[f.properties.case] = f;
      return { expr, cases: Object.keys(byCase).sort(), exported: window.__labelRule.LABEL_EXPRESSION };
    });
    expect(res.expr).toEqual(LABEL_EXPRESSION);
    expect(res.exported).toEqual(LABEL_EXPRESSION); // the fixture uses the app's exported expression
    expect(res.cases).toEqual(['a', 'b', 'c']); // (d) has no label
    // Evaluate the rendered text with MapLibre's own expression engine for each fixture feature.
    const texts = await page.evaluate(async () => {
      const m = window.__labelRule.map;
      const layer = m.style.getLayer('fixture-labels');
      const out = {};
      for (const f of m.querySourceFeatures('fixture')) {
        const v = layer.layout.get('text-field').evaluate(f, {}, {});
        out[f.properties.case] = v && typeof v === 'object' && 'sections' in v ? v.sections.map((s) => s.text).join('') : v ?? '';
      }
      return out;
    });
    expect(texts).toEqual({ a: 'Тест А', b: 'Тест Б', c: 'Test C', d: '' });
    expect(errors).toEqual([]);
  });

  test('AC9 zoom 18 at P1: roads and labels still shown (over-zoom), no blank map, no error state', async ({ page }) => {
    const errors = collectErrors(page);
    await openApp(page);
    await jump(page, { center: P1, zoom: 18 });
    expect((await camera(page)).zoom).toBeCloseTo(18, 1);
    expect(await renderedRoadCount(page)).toBeGreaterThan(0);
    expect((await renderedLabels(page)).filter((l) => l.text).length).toBeGreaterThan(0);
    expect(await statusView(page)).toEqual({ loading: false, card: null, banner: null });
    expect(errors).toEqual([]);
  });

  for (const [name, p, z] of [['X2 Beijing (outside extract)', X2, 10], ['X2 Beijing z14', X2, 14], ['empty Gobi countryside', GOBI, 12]]) {
    test(`AC10 ${name}: idle, no error / tiles-unavailable / offline message`, async ({ page }) => {
      const errors = collectErrors(page);
      const mapErrors = [];
      await openApp(page);
      await page.evaluate(() => window.__nav002.map.on('error', (e) => (window.__mapErr = (window.__mapErr || 0) + 1)));
      await jump(page, { center: p, zoom: z });
      await page.waitForTimeout(1500); // any late error would raise a banner within this window
      expect(await statusView(page)).toEqual({ loading: false, card: null, banner: null });
      mapErrors.push(await page.evaluate(() => window.__mapErr || 0));
      expect(mapErrors[0], 'MapLibre error events').toBe(0);
      expect(errors).toEqual([]);
    });
  }
});

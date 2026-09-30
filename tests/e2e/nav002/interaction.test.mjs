// NAV-002 C. Map interaction (AC 11–15) and D. Metric scale bar (AC 16–17).
import { test, expect } from '@playwright/test';
import { P1, attributionProblems, camera, jump, openApp, tid, waitIdle, haversine } from './helpers.mjs';

const mapBox = async (page) => (await page.locator('#map canvas').boundingBox());
const centre = async (page) => {
  const b = await mapBox(page);
  return { x: b.x + b.width / 2, y: b.y + b.height / 2 - 60 }; // above the bottom controls
};
async function drag(page, from, dx, dy, { button = 'left', steps = 12 } = {}) {
  await page.mouse.move(from.x, from.y);
  await page.mouse.down({ button });
  for (let i = 1; i <= steps; i++) {
    await page.mouse.move(from.x + (dx * i) / steps, from.y + (dy * i) / steps);
    await page.waitForTimeout(16);
  }
  await page.mouse.up({ button });
  await page.waitForTimeout(400);
}
const focusCanvas = (page) => page.evaluate(() => window.__nav002.map.getCanvas().focus());
const settle = async (page) => {
  await page.waitForFunction(() => !window.__nav002.map.isMoving(), null, { timeout: 5000 });
};

async function touch(page, cdp, frames) {
  // frames: array of arrays of {x,y}; first = touchStart, last = touchEnd
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: frames[0].map((p, i) => ({ ...p, id: i })) });
  for (const f of frames.slice(1)) {
    await page.waitForTimeout(16);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: f.map((p, i) => ({ ...p, id: i })) });
  }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await page.waitForTimeout(500);
}

test.describe('NAV-002 C. Map interaction', () => {
  test.beforeEach(async ({ page }) => {
    await openApp(page);
  });

  test('AC11 mouse drag pans in the drag direction', async ({ page }) => {
    const c = await centre(page);
    const before = await camera(page);
    await drag(page, c, 200, 0); // content moves right → view centre moves west
    await settle(page);
    const a = await camera(page);
    expect(a.lng).toBeLessThan(before.lng - 0.01);
    await drag(page, c, 0, 150); // content moves down → view centre moves north
    await settle(page);
    const b = await camera(page);
    expect(b.lat).toBeGreaterThan(a.lat + 0.005);
  });

  test('AC11 arrow keys with the map focused pan the map', async ({ page }) => {
    await focusCanvas(page);
    const before = await camera(page);
    await page.keyboard.press('ArrowRight');
    await settle(page);
    const a = await camera(page);
    expect(a.lng).toBeGreaterThan(before.lng);
    await page.keyboard.press('ArrowUp');
    await settle(page);
    const b = await camera(page);
    expect(b.lat).toBeGreaterThan(a.lat);
    expect(Math.abs(b.bearing)).toBeLessThan(0.01);
  });

  test('AC12 wheel, double-click and +/- keys change the zoom', async ({ page }) => {
    const c = await centre(page);
    let z = (await camera(page)).zoom;
    await page.mouse.move(c.x, c.y);
    await page.mouse.wheel(0, -400);
    await page.waitForTimeout(600);
    await settle(page);
    let z2 = (await camera(page)).zoom;
    expect(z2).toBeGreaterThan(z + 0.1);
    z = z2;
    await page.mouse.dblclick(c.x, c.y);
    await page.waitForTimeout(700);
    await settle(page);
    z2 = (await camera(page)).zoom;
    expect(z2).toBeGreaterThan(z + 0.5);
    z = z2;
    await focusCanvas(page);
    await page.keyboard.press('-');
    await settle(page);
    z2 = (await camera(page)).zoom;
    expect(z2).toBeLessThan(z - 0.5);
    z = z2;
    await page.keyboard.press('+');
    await settle(page);
    expect((await camera(page)).zoom).toBeGreaterThan(z + 0.5);
  });

  test('AC12 zoom buttons change zoom by exactly 1; range 3–19; «Жижигрүүлэх» disabled at 3, «Томруулах» at 19', async ({ page }) => {
    const zi = tid(page, 'zoom-in');
    const zo = tid(page, 'zoom-out');
    await expect(zi).toHaveAccessibleName('Томруулах');
    await expect(zo).toHaveAccessibleName('Жижигрүүлэх');
    await jump(page, { zoom: 12 });
    await zi.click();
    await settle(page);
    expect((await camera(page)).zoom).toBeCloseTo(13, 6);
    await zo.click();
    await settle(page);
    await zo.click();
    await settle(page);
    expect((await camera(page)).zoom).toBeCloseTo(11, 6);
    // Upper limit
    await jump(page, { zoom: 18 });
    await expect(zi).not.toHaveAttribute('aria-disabled', 'true');
    await zi.click();
    await settle(page);
    expect((await camera(page)).zoom).toBeCloseTo(19, 6);
    await expect(zi).toHaveAttribute('aria-disabled', 'true');
    await zi.click({ force: true });
    await page.waitForTimeout(400);
    expect((await camera(page)).zoom).toBeCloseTo(19, 6);
    // Wheel cannot exceed 19 either
    const c = await centre(page);
    await page.mouse.move(c.x, c.y);
    await page.mouse.wheel(0, -600);
    await page.waitForTimeout(600);
    expect((await camera(page)).zoom).toBeLessThanOrEqual(19 + 1e-9);
    // Lower limit
    await jump(page, { zoom: 4 });
    await expect(zo).not.toHaveAttribute('aria-disabled', 'true');
    await zo.click();
    await settle(page);
    expect((await camera(page)).zoom).toBeCloseTo(3, 6);
    await expect(zo).toHaveAttribute('aria-disabled', 'true');
    await expect(zi).not.toHaveAttribute('aria-disabled', 'true');
    await zo.click({ force: true });
    await page.waitForTimeout(400);
    expect((await camera(page)).zoom).toBeCloseTo(3, 6);
    await page.mouse.wheel(0, 800);
    await page.waitForTimeout(600);
    expect((await camera(page)).zoom).toBeGreaterThanOrEqual(3 - 1e-9);
  });

  const needleAngle = (page) =>
    page.evaluate(() => {
      const n = document.querySelector('[data-testid="compass"] .needle');
      const t = getComputedStyle(n).transform;
      if (!t || t === 'none') return 0;
      const [a, b] = t.match(/matrix\(([^)]+)\)/)[1].split(',').map(Number);
      return (Math.atan2(b, a) * 180) / Math.PI;
    });
  const angleDiff = (x, y) => Math.abs(((x - y + 540) % 360) - 180);

  test('AC13 right-drag, Ctrl+drag and Shift+arrow rotate; compass needle points north', async ({ page }) => {
    const c = await centre(page);
    await drag(page, c, 150, 0, { button: 'right' });
    await settle(page);
    const b1 = (await camera(page)).bearing;
    expect(Math.abs(b1)).toBeGreaterThan(5);
    expect(angleDiff(await needleAngle(page), -b1)).toBeLessThan(1);
    await page.keyboard.down('Control');
    await drag(page, c, -120, 0);
    await page.keyboard.up('Control');
    await settle(page);
    const b2 = (await camera(page)).bearing;
    expect(angleDiff(b2, b1)).toBeGreaterThan(5);
    expect(angleDiff(await needleAngle(page), -b2)).toBeLessThan(1);
    await focusCanvas(page);
    await page.keyboard.press('Shift+ArrowLeft');
    await settle(page);
    const b3 = (await camera(page)).bearing;
    expect(angleDiff(b3, b2)).toBeGreaterThan(5);
    expect(angleDiff(await needleAngle(page), -b3)).toBeLessThan(1);
    await expect(tid(page, 'compass')).toHaveAccessibleName('Хойд зүг дээшээ');
  });

  test('AC14 «Хойд зүг дээшээ» returns bearing to 0° (±0.5°) within 1 s; centre and zoom unchanged', async ({ page }) => {
    await jump(page, { center: P1, zoom: 14, bearing: 45 });
    const before = await camera(page);
    // AC 14 "within 1 s" is measured in the page: from the click event on the button to the first
    // rendered frame with |bearing| <= 0.5. (Measuring around Playwright's click() from Node also counts
    // actionability checks and CDP round-trips, which gave false failures under CPU load: 1026 ms in
    // run 2026-09-29 while the app's ease is 300 ms.) The Node-side time is kept as information.
    await page.evaluate(() => {
      const m = window.__nav002.map;
      window.__ac14 = {};
      document.querySelector('[data-testid="compass"]').addEventListener('click', () => { window.__ac14.click ??= performance.now(); }, { capture: true, once: true });
      const onRender = () => {
        if (window.__ac14.click != null && Math.abs(m.getBearing()) <= 0.5) { window.__ac14.done = performance.now(); m.off('render', onRender); }
      };
      m.on('render', onRender);
    });
    const t0 = Date.now();
    await tid(page, 'compass').click();
    await page.waitForFunction(() => window.__ac14.done != null, null, { timeout: 5000 });
    const nodeDt = Date.now() - t0;
    const dt = await page.evaluate(() => window.__ac14.done - window.__ac14.click);
    await settle(page);
    const after = await camera(page);
    test.info().annotations.push({ type: 'AC14 reset time (ms, click event → frame at bearing ≤ 0.5°)', description: `${Math.round(dt)} (Node round-trip incl. click actionability: ${nodeDt})` });
    expect(dt).toBeLessThanOrEqual(1000);
    expect(Math.abs(after.bearing)).toBeLessThanOrEqual(0.5);
    expect(haversine(after, before)).toBeLessThan(1);
    expect(Math.abs(after.zoom - before.zoom)).toBeLessThan(0.001);
  });

  test('AC15 after pan/zoom/rotate: point labels upright (viewport-aligned), line labels keep upright, attribution visible', async ({ page }) => {
    const c = await centre(page);
    await drag(page, c, 100, 50);
    await drag(page, c, 120, 0, { button: 'right' });
    await tid(page, 'zoom-in').click();
    await settle(page);
    await waitIdle(page);
    const bad = await page.evaluate(() => {
      const m = window.__nav002.map;
      const out = [];
      for (const l of m.getStyle().layers.filter((x) => x.type === 'symbol')) {
        const placement = m.getLayoutProperty(l.id, 'symbol-placement') ?? 'point';
        const rot = m.getLayoutProperty(l.id, 'text-rotation-alignment') ?? 'auto';
        const upright = m.getLayoutProperty(l.id, 'text-keep-upright');
        const pitch = m.getLayoutProperty(l.id, 'text-pitch-alignment') ?? 'auto';
        if (placement === 'point' && rot === 'map') out.push(`${l.id}: point label rotates with the map`);
        if (placement !== 'point' && upright === false) out.push(`${l.id}: text-keep-upright false`);
        if (pitch === 'map' && placement === 'point') out.push(`${l.id}: pitch-aligned`);
      }
      return out;
    });
    expect(bad).toEqual([]);
    expect(Math.abs((await camera(page)).bearing)).toBeGreaterThan(5);
    expect(await attributionProblems(page)).toEqual([]);
  });
});

test.describe('NAV-002 C. Touch gestures (CDP touch events)', () => {
  test.use({ hasTouch: true });

  test('AC11/12/13 one-finger drag pans, pinch zooms, two-finger twist rotates', async ({ page }) => {
    await openApp(page);
    const cdp = await page.context().newCDPSession(page);
    const c = await centre(page);
    // One-finger drag to the right
    let before = await camera(page);
    await touch(page, cdp, Array.from({ length: 15 }, (_, i) => [{ x: c.x + i * 12, y: c.y }]));
    await settle(page);
    let after = await camera(page);
    expect(after.lng, 'one-finger drag right moves view west').toBeLessThan(before.lng - 0.005);
    // Pinch out
    before = after;
    await touch(page, cdp, Array.from({ length: 15 }, (_, i) => [{ x: c.x - 40 - i * 8, y: c.y }, { x: c.x + 40 + i * 8, y: c.y }]));
    await settle(page);
    after = await camera(page);
    expect(after.zoom, 'pinch out zooms in').toBeGreaterThan(before.zoom + 0.3);
    // Two-finger twist (rotate both points 40° around the centre)
    before = after;
    const r = 80;
    await touch(page, cdp, Array.from({ length: 20 }, (_, i) => {
      const a = (i * 2 * Math.PI) / 180;
      return [{ x: c.x + r * Math.cos(a), y: c.y + r * Math.sin(a) }, { x: c.x - r * Math.cos(a), y: c.y - r * Math.sin(a) }];
    }));
    await settle(page);
    after = await camera(page);
    expect(Math.abs(after.bearing - before.bearing), 'twist rotates').toBeGreaterThan(5);
  });
});

test.describe('NAV-002 D. Metric scale bar', () => {
  const read = (page) =>
    page.evaluate(() => {
      const label = document.querySelector('[data-testid="scale-label"]').textContent.trim();
      const bar = document.querySelector('#scale-bar').getBoundingClientRect().width;
      const vis = !!document.querySelector('[data-testid="scale-bar"]').offsetParent;
      const m = window.__nav002.map;
      return { label, bar, vis, lat: m.getCenter().lat, zoom: m.getZoom() };
    });
  // Independent ground resolution: Web Mercator, 512 px tiles (MapLibre), WGS84 equatorial radius.
  const mpp = (lat, zoom) => (2 * Math.PI * 6378137 * Math.cos((lat * Math.PI) / 180)) / (512 * 2 ** zoom);
  const parse = (label, lang) => {
    const m = label.match(lang === 'mn' ? /^(\d+(?:,\d+)?) (м|км)$/ : /^(\d+(?:\.\d+)?) (m|km)$/);
    if (!m) return null;
    const v = Number(m[1].replace(',', '.'));
    return /к|k/.test(m[2]) ? v * 1000 : v;
  };

  test('AC16 metric only at any zoom and latitude: «м» below 1 km, «км» from 1 km (mn); m/km (en)', async ({ page }) => {
    await openApp(page);
    const places = [P1, { lat: 42.5, lng: 105 }, { lat: 51.5, lng: 99 }];
    for (const lang of ['mn', 'en']) {
      if (lang === 'en') await tid(page, 'language-toggle').click();
      for (const p of places) {
        for (const z of [3, 5, 8, 10, 12, 14, 16, 17, 18, 19]) {
          await jump(page, { center: p, zoom: z });
          await page.waitForTimeout(50);
          const s = await read(page);
          expect(s.vis, `scale visible z${z}`).toBe(true);
          const meters = parse(s.label, lang);
          expect(meters, `label «${s.label}» (${lang}, z${z}, lat ${p.lat})`).not.toBeNull();
          expect(s.label).not.toMatch(/ft|mi|yd|feet|mile/i);
          const unitKm = /к|k/.test(s.label);
          expect(unitKm, `unit for ${meters} m`).toBe(meters >= 1000);
        }
      }
    }
  });

  test('AC17 at P1 z12/z15/z18 the labelled distance is within ±5 % of bar length × metres-per-pixel; update ≤ 500 ms after zoom end', async ({ page }) => {
    await openApp(page);
    const results = [];
    for (const z of [12, 15, 18]) {
      await jump(page, { center: P1, zoom: z });
      await page.waitForTimeout(100);
      const s = await read(page);
      const labelled = parse(s.label, 'mn');
      const truth = s.bar * mpp(s.lat, s.zoom);
      const err = Math.abs(labelled - truth) / truth;
      results.push(`z${z}: «${s.label}» bar ${s.bar.toFixed(1)} px = ${truth.toFixed(1)} m, error ${(err * 100).toFixed(2)} %`);
      expect(err, results.at(-1)).toBeLessThanOrEqual(0.05);
    }
    test.info().annotations.push({ type: 'AC17 scale accuracy', description: results.join('; ') });
    // Screen spec example (UX, 2026-09-30): at P1 z12 on the 1366 px reference viewport the bar reads «1 км»
    // (12.8 m/px, 100 px maximum bar rounds down to a 78 px «1 км» bar).
    await jump(page, { center: P1, zoom: 12 });
    await page.waitForTimeout(100);
    expect((await read(page)).label, 'screen spec example at P1 z12, 1366 px').toBe('1 км');
    // Update latency: zoom with the button, then measure from `zoomend` to a correct label.
    for (const [from, btn] of [[12, 'zoom-in'], [15, 'zoom-out'], [17, 'zoom-in']]) {
      await jump(page, { center: P1, zoom: from });
      const lag = await page.evaluate(
        (btn) =>
          new Promise((resolve) => {
            const m = window.__nav002.map;
            m.once('zoomend', () => {
              const t0 = performance.now();
              const mppNow = (2 * Math.PI * 6378137 * Math.cos((m.getCenter().lat * Math.PI) / 180)) / (512 * 2 ** m.getZoom());
              const check = () => {
                const label = document.querySelector('[data-testid="scale-label"]').textContent.trim();
                const w = document.querySelector('#scale-bar').getBoundingClientRect().width;
                const mm = label.match(/^(\d+(?:,\d+)?) (м|км)$/);
                const v = mm ? Number(mm[1].replace(',', '.')) * (mm[2] === 'км' ? 1000 : 1) : NaN;
                const ok = Math.abs(v - w * mppNow) / (w * mppNow) <= 0.05;
                if (ok) return resolve(performance.now() - t0);
                if (performance.now() - t0 > 2000) return resolve(9999);
                requestAnimationFrame(check);
              };
              check();
            });
            document.querySelector(`[data-testid="${btn}"]`).click();
          }),
        btn,
      );
      expect(lag, `scale update after ${btn} from z${from}`).toBeLessThanOrEqual(500);
    }
  });
});

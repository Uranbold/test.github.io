// SEC-4B D: zero CSP violations in the browser (story AC 17) and the negative control (AC 18) on the three production
// builds served by `vite preview` (no security response headers, so only the meta policy applies), plus the attribution
// on every map screen (AC 34). Chromium, no extensions, bypassCSP false.
// Each test records `securitypolicyviolation` events from the very first byte (init script) and every console message
// that mentions "Content Security Policy" / "Content-Security-Policy", runs the story scenario, asserts 0 of both, then
// injects an unhashed inline script and asserts it is blocked with exactly one script-src(-elem) violation.
import { test, expect } from '@playwright/test';
import { urlOf } from './playwright.config.mjs';
import { S, UB, attributionVisible, cspConsole, mockGateway, negativeControl, recorderInit, tid, violations } from './helpers.mjs';

/** Steps are logged into an annotation so the report shows what the zero was measured over. */
function stepLog() {
  const steps = [];
  return { steps, step: async (title, fn) => test.step(title, async () => { await fn(); steps.push(title); }) };
}

async function finish(page, consoleMsgs, steps, name) {
  const v = await violations(page);
  test.info().annotations.push({ type: `${name} steps run`, description: steps.join(' → ') });
  test.info().annotations.push({ type: `${name} AC17 violations / CSP console messages`, description: `${v.length} / ${consoleMsgs.length}${v.length ? ' ' + JSON.stringify(v.slice(0, 5)) : ''}${consoleMsgs.length ? ' ' + JSON.stringify(consoleMsgs.slice(0, 5)) : ''}` });
  expect(v, `${name} AC 17: securitypolicyviolation events`).toEqual([]);
  expect(consoleMsgs, `${name} AC 17: CSP console messages`).toEqual([]);
  // AC 34
  expect(await attributionVisible(page), `${name} AC 34: «${S.osm}» visible`).toBe('ok');
  // AC 18
  const nc = await negativeControl(page);
  test.info().annotations.push({ type: `${name} AC18 negative control`, description: JSON.stringify({ probe: nc.probe, violations: nc.added.map((x) => x.directive), addScriptTag: nc.addError }) });
  expect(nc.probe, `${name} AC 18: injected inline script must not run`).toBe('undefined');
  expect(nc.added.length, `${name} AC 18: exactly one violation`).toBe(1);
  expect(['script-src', 'script-src-elem']).toContain(nc.added[0].directive);
}

test.describe('B1 static demo', () => {
  test.use({ geolocation: UB, permissions: ['geolocation'], locale: 'mn-MN' });

  test('B1 SEC-4B AC17, AC18, AC34: load, mn→en→mn, day→night→day, zoom, «Миний байршил», search «Сүхбаатар»/"Sukhbaatar" (unavailable), coordinate card, «Маршрут гаргах» (unavailable), offline: 0 CSP violations; injected script blocked', async ({ page, context }) => {
    const consoleMsgs = cspConsole(page);
    const tiles = [];
    const sprites = new Set();
    page.on('response', (r) => {
      const u = new URL(r.url());
      if (u.pathname === '/tiles/basemap.pmtiles') tiles.push(r.status());
      if (u.pathname.includes('/sprites/')) sprites.add(u.pathname.replace(/@2x/, '').replace(/\.(json|png)$/, ''));
    });
    await page.addInitScript(recorderInit);
    const { steps, step } = stepLog();

    await step('load until tiles drawn and the loading pill hidden', async () => {
      await page.goto(urlOf('B1'));
      await expect(page.locator('#loading')).toHaveClass(/\bidle\b/, { timeout: 30_000 });
      await expect.poll(() => tiles.filter((s) => s === 206).length, { timeout: 30_000 }).toBeGreaterThan(1);
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(1500);
      expect(tiles.every((s) => s === 206 || s === 200), `tile statuses ${[...new Set(tiles)]}`).toBe(true);
    });
    await step('language mn → en → mn', async () => {
      await tid(page, 'language-toggle').click();
      await expect(page.locator('html')).toHaveAttribute('lang', 'en');
      await page.waitForTimeout(800);
      await tid(page, 'language-toggle').click();
      await expect(page.locator('html')).toHaveAttribute('lang', 'mn');
      await page.waitForTimeout(800);
    });
    await step('theme day → night → day (sprite reload)', async () => {
      await tid(page, 'theme-toggle').click();
      await expect(page.locator('html')).toHaveAttribute('data-theme', 'night');
      await page.waitForTimeout(2000);
      await tid(page, 'theme-toggle').click();
      await expect(page.locator('html')).toHaveAttribute('data-theme', 'day');
      await page.waitForTimeout(2000);
      expect(sprites.size, `sprite sheets loaded: ${[...sprites].join(', ')}`).toBeGreaterThanOrEqual(2);
    });
    await step('zoom in and out', async () => {
      const m0 = await tid(page, 'scale-label').getAttribute('data-meters');
      await tid(page, 'zoom-in').click();
      await page.waitForTimeout(1200);
      expect(await tid(page, 'scale-label').getAttribute('data-meters')).not.toBe(m0);
      await tid(page, 'zoom-out').click();
      await page.waitForTimeout(1200);
    });
    await step('«Миний байршил» with geolocation granted (UB point)', async () => {
      await tid(page, 'my-location').click();
      await expect(page.locator('[data-testid="location-marker"]')).toBeVisible({ timeout: 10_000 });
      await page.waitForTimeout(1500);
    });
    await step('search «Сүхбаатар» and "Sukhbaatar": «Хайлт түр ажиллахгүй байна»', async () => {
      await tid(page, 'search-input').click();
      await tid(page, 'search-input').fill('Сүхбаатар');
      await expect(tid(page, 'search-state')).toContainText(S.searchOff);
      await tid(page, 'search-input').fill('Sukhbaatar');
      await page.waitForTimeout(800);
      await expect(tid(page, 'search-state')).toContainText(S.searchOff);
      await page.keyboard.press('Escape');
      await tid(page, 'search-input').blur();
    });
    await step('coordinate card from the map (right-click)', async () => {
      const box = await tid(page, 'map').boundingBox();
      await page.mouse.move(box.x + box.width * 0.6, box.y + box.height * 0.55);
      await page.mouse.down({ button: 'right' });
      await page.mouse.up({ button: 'right' });
      await expect(tid(page, 'place-card')).toBeVisible();
      await expect(tid(page, 'route-open')).toHaveText(S.getDirections);
    });
    await step('«Маршрут гаргах»: «Маршрутын үйлчилгээ түр ажиллахгүй байна»', async () => {
      await tid(page, 'route-open').click();
      await expect(tid(page, 'route-panel')).toBeVisible();
      await expect(tid(page, 'route-state')).toContainText(S.routeOff);
      await page.waitForTimeout(800);
    });
    await step('offline (context.setOffline): «Интернэт холболт алга»', async () => {
      await context.setOffline(true);
      await expect(tid(page, 'status-banner')).toContainText(S.offline, { timeout: 10_000 });
      await page.waitForTimeout(1000);
      await context.setOffline(false);
      await page.waitForTimeout(2000);
    });
    await finish(page, consoleMsgs, steps, 'B1');
  });
});

test.describe('B2 demo mode', () => {
  // NAV-017 reference: iPhone-like touch viewport (tests/e2e/nav017/playwright.config.mjs, chromium-iphone)
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, deviceScaleFactor: 1, locale: 'mn-MN' });

  /**
   * Real time (story AC 17 allows real time or page.clock). page.clock was tried first: fake time renders every MapLibre
   * frame and ran ≈ 3.5× slower than real time on this machine (R1's first maneuver change is ≈ 230 s into the replay),
   * so real time is both faster and closer to a phone. Polls `cond` every `stepMs` until it holds.
   */
  async function advanceUntil(page, cond, maxMs, stepMs = 250) {
    const t0 = Date.now();
    while (Date.now() - t0 < maxMs) {
      if (await cond()) return Date.now() - t0;
      await page.waitForTimeout(stepMs);
    }
    if (await cond()) return Date.now() - t0;
    throw new Error(`condition not met after ${maxMs} ms`);
  }

  test('B2 SEC-4B AC17, AC18, AC34: picker, R1, «Эхлэх» after the WASM compiled, real-time replay with a banner change and a chime/voice prompt, mute, voice diagnostics "Test chime", «Дуусгах»: 0 CSP violations; injected script blocked', async ({ page }) => {
    test.setTimeout(600_000); // R1's first maneuver change comes ≈ 230 s into the replay
    const consoleMsgs = cspConsole(page);
    const wasmResp = [];
    page.on('response', (r) => { if (r.url().endsWith('.wasm')) wasmResp.push(r.status()); });
    await page.addInitScript(recorderInit);
    const { steps, step } = stepLog();
    const visible = (id) => () => tid(page, id).isVisible().catch(() => false);

    await step('load until the picker shows R1–R3', async () => {
      await page.goto(urlOf('B2'));
      await advanceUntil(page, async () => (await page.locator('[data-testid="demo-route"]').count()) === 3 && (await page.locator('[data-testid="demo-route"][data-route="R1"]').isVisible()), 30_000);
      for (const r of ['R1', 'R2', 'R3']) await expect(page.locator(`[data-testid="demo-route"][data-route="${r}"]`)).toBeVisible();
      expect(await attributionVisible(page), 'AC 34 on the picker').toBe('ok');
    });
    await step('pick R1; «Эхлэх» enabled (Ferrostar WASM compiled, plan ready)', async () => {
      await page.locator('[data-testid="demo-route"][data-route="R1"]').tap();
      await advanceUntil(page, async () => (await page.locator('[data-testid="demo-start"][aria-disabled="false"]').count()) === 1 || (await page.locator('[data-testid="demo-start"]:not([disabled]):not([aria-disabled="true"])').count()) === 1, 60_000, 100);
      await expect(page.locator('[data-testid="demo-start"]')).toContainText(S.start);
      expect(wasmResp, '.wasm fetched').toContain(200);
    });
    await step('tap «Эхлэх»', async () => {
      await page.locator('[data-testid="demo-start"]').tap();
      await advanceUntil(page, visible('demo-nav'), 10_000);
    });
    let replayMs = 0;
    await step('replay ≥ 30 s (real time) until ≥ 1 banner change and ≥ 1 chime or voice prompt', async () => {
      const audible = async () => {
        const a = await page.evaluate(() => window.__audio);
        return a.speak + a.nodeStart + a.mediaPlay.filter((m) => !m.muted).length;
      };
      replayMs = await advanceUntil(page, async () => (await page.evaluate(() => window.__banners.length)) >= 2 && (await audible()) >= 1, 330_000, 1000);
      if (replayMs < 30_000) { await page.waitForTimeout(30_000 - replayMs); replayMs = 30_000; }
      const audio = await page.evaluate(() => window.__audio);
      const banners = await page.evaluate(() => window.__banners);
      test.info().annotations.push({ type: 'B2 replay', description: JSON.stringify({ replaySeconds: Math.round(replayMs / 1000), banners, audio: { speak: audio.speak, audioNodeStart: audio.nodeStart, mediaPlay: audio.mediaPlay.map((m) => `${m.scheme}${m.muted ? ' (muted prime)' : ''}`) } }) });
      expect(banners.length, 'banner changes').toBeGreaterThanOrEqual(2);
      expect(await audible(), 'voice prompts + chimes').toBeGreaterThanOrEqual(1);
      expect(audio.mediaPlay.some((m) => m.scheme.startsWith('data:audio')), 'the data: chime element was primed/played (media-src data:)').toBe(true);
      await expect(tid(page, 'demo-nav')).toBeVisible();
    });
    await step('toggle voice mute (twice)', async () => {
      await tid(page, 'demo-nav-voice').tap();
      await page.waitForTimeout(600);
      await tid(page, 'demo-nav-voice').tap();
      await page.waitForTimeout(600);
    });
    await step('voice diagnostics: 5 quick taps on the «Туршилтын горим» badge, "Test chime", close', async () => {
      // Real taps cannot reach the badge during the replay: it inherits pointer-events: none from .dn-top
      // (web/src/demo/demo.css), so every tap lands on the map canvas (pre-existing NAV-017 defect, reported separately,
      // not caused by SEC-4B). The opener's own pointerdown handler is therefore driven directly, 5 times within 0.6 s,
      // so the diagnostics panel and its "Test chime" still run under the policy.
      const atCentre = await page.evaluate(() => {
        const r = document.querySelector('[data-testid="demo-badge"]').getBoundingClientRect();
        const e = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
        return `${e?.tagName.toLowerCase()} pointer-events of badge: ${getComputedStyle(document.querySelector('[data-testid="demo-badge"]')).pointerEvents}`;
      });
      test.info().annotations.push({ type: 'B2 badge hit-test (out-of-scope defect evidence)', description: `element at the badge centre: ${atCentre}` });
      // The burst is sent from inside the page, 100 ms apart on the page's own timers. Run 2 (2026-10-08) sent it as five
      // Playwright dispatchEvent round trips, which took 250–450 ms each while another B2 replay shared the CPU; the gaps
      // between pointerdowns (906/647/525/838 ms) then exceeded the opener's TAP_GAP_MS 600 and the panel stayed closed.
      // That was the test's latency, not the page's: same handler, same events, only without CDP round trips.
      const gaps = await page.evaluate(async () => {
        const badge = document.querySelector('[data-testid="demo-badge"]');
        const init = { isPrimary: true, button: 0, pointerType: 'touch', bubbles: true, cancelable: true };
        const at = [];
        for (let i = 0; i < 5; i++) {
          at.push(performance.now());
          badge.dispatchEvent(new PointerEvent('pointerdown', init));
          badge.dispatchEvent(new PointerEvent('pointerup', init));
          if (i < 4) await new Promise((r) => setTimeout(r, 100));
        }
        return at.slice(1).map((t, i) => Math.round(t - at[i]));
      });
      test.info().annotations.push({ type: 'B2 diagnostics burst gaps (ms, limit 600)', description: JSON.stringify(gaps) });
      await advanceUntil(page, visible('demo-diag'), 3000, 100);
      await tid(page, 'demo-diag-test-chime').click();
      await advanceUntil(page, async () => ((await tid(page, 'demo-diag-result-chime').innerText().catch(() => '')) ?? '').trim() !== '', 5000, 100);
      await page.waitForTimeout(2000);
      test.info().annotations.push({ type: 'B2 Test chime result', description: (await tid(page, 'demo-diag-result-chime').innerText()).slice(0, 120) });
      await tid(page, 'demo-diag-close').click();
      await advanceUntil(page, async () => !(await visible('demo-diag')()), 3000, 100);
    });
    await step('AC 34 during the replay', async () => {
      expect(await attributionVisible(page)).toBe('ok');
    });
    await step('tap «Дуусгах»', async () => {
      await expect(tid(page, 'demo-nav-end')).toHaveAccessibleName(S.end);
      await tid(page, 'demo-nav-end').tap();
      await advanceUntil(page, async () => !(await visible('demo-nav')()), 10_000);
      await page.waitForTimeout(1000);
    });
    await finish(page, consoleMsgs, steps, 'B2');
  });
});

test.describe('B3 normal build', () => {
  test.use({ geolocation: UB, permissions: ['geolocation'], locale: 'mn-MN' });

  test('B3 SEC-4B AC17, AC18, AC34: gateway mocked at http://localhost:8080; search «Сүхбаатар» and "Sukhbaatar" (results with icons), place card, «Маршрут гаргах» with «Машин» (route and turn list): 0 CSP violations; injected script blocked', async ({ page, context }) => {
    const gw = await mockGateway(context);
    const consoleMsgs = cspConsole(page);
    await page.addInitScript(recorderInit);
    const { steps, step } = stepLog();

    await step('load until tiles drawn and the loading pill hidden', async () => {
      await page.goto(urlOf('B3'));
      await expect(page.locator('#loading')).toHaveClass(/\bidle\b/, { timeout: 30_000 });
      await expect.poll(() => gw.filter((r) => r.path === '/tiles/basemap.pmtiles' && r.method === 'GET').length, { timeout: 30_000 }).toBeGreaterThan(1);
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(1500);
    });
    for (const q of ['Сүхбаатар', 'Sukhbaatar']) {
      await step(`search "${q}": results list with icons`, async () => {
        await tid(page, 'search-input').click();
        await tid(page, 'search-input').fill(q);
        await expect(page.locator('[data-testid="search-results"] [data-testid="search-option"]').first()).toBeVisible({ timeout: 10_000 });
        await page.waitForTimeout(900);
        const icons = await page.locator('[data-testid="search-results"] [data-testid="search-option"] svg').count();
        expect(icons, 'icon SVGs in the result rows (innerHTML path)').toBeGreaterThan(0);
      });
    }
    await step('open a place card from the first result', async () => {
      await page.locator('[data-testid="search-results"] [data-testid="search-option"]').first().click();
      await expect(tid(page, 'place-card')).toBeVisible();
      await expect(tid(page, 'place-card-title')).not.toBeEmpty();
      await page.waitForTimeout(1200);
    });
    await step('«Маршрут гаргах» with «Машин»: route drawn, turn list shown', async () => {
      await expect(tid(page, 'route-open')).toHaveText(S.getDirections);
      await tid(page, 'route-open').click();
      await expect(tid(page, 'route-panel')).toBeVisible();
      if ((await tid(page, 'route-origin').inputValue()) === '') {
        await tid(page, 'route-origin').click();
        await page.keyboard.type('47.91890, 106.91760');
        await expect(page.locator('#route-origin-results [data-testid=route-field-option]').first()).toBeVisible();
        await page.keyboard.press('ArrowDown');
        await page.keyboard.press('Enter');
      }
      await tid(page, 'route-tab-car').click();
      await expect(tid(page, 'route-tab-car')).toContainText(S.car);
      await expect.poll(() => gw.filter((r) => r.path === '/v1/route' && r.method === 'POST').length, { timeout: 15_000 }).toBeGreaterThanOrEqual(1);
      await expect(page.locator('[data-testid="route-steps"] [data-testid="route-step"]').first()).toBeVisible({ timeout: 15_000 });
      const n = await page.locator('[data-testid="route-steps"] [data-testid="route-step"]').count();
      test.info().annotations.push({ type: 'B3 route', description: `${n} turn rows; gateway calls ${JSON.stringify(Object.entries(gw.reduce((a, r) => ((a[`${r.method} ${r.path}`] = (a[`${r.method} ${r.path}`] ?? 0) + 1), a), {})))}` });
      expect(n).toBeGreaterThanOrEqual(2);
      await page.waitForTimeout(1500);
    });
    await finish(page, consoleMsgs, steps, 'B3');
  });
});

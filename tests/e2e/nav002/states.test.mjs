// NAV-002 I. States: loading, tiles unavailable, offline (AC 37–45).
// Failures are simulated in the browser only: a closed-port gateway URL (port 5174 server), Playwright
// request interception, and context.setOffline(). The shared gateway is never touched.
import { test, expect } from '@playwright/test';
import {
  P1, S, TILES_GLOB, attributionProblems, camera, jump, openApp, renderedRoadCount, statusView, tid, waitIdle,
} from './helpers.mjs';

/** Records the first MapLibre `idle` with the basemap source loaded (ms since navigation start). */
function firstIdleInit() {
  const iv = setInterval(() => {
    const m = window.__nav002?.map;
    if (!m) return;
    clearInterval(iv);
    m.on('idle', () => {
      if (!window.__firstIdle && m.getSource('protomaps') && m.isSourceLoaded('protomaps')) window.__firstIdle = performance.now();
    });
  }, 1);
}
const delayArchive = (page, ms) =>
  page.route(TILES_GLOB, async (r) => {
    await new Promise((res) => setTimeout(res, ms));
    await r.continue().catch(() => {});
  });
const timeline = (page) => page.evaluate(() => window.__t);
const tOf = async (page, pred) => (await timeline(page)).find(pred)?.t ?? null;

/** Pans to fresh areas until `n` failed archive requests have been counted (or gives up). */
async function panUntilFailures(page, counter, n) {
  const spots = [[100, 46], [95, 49], [110, 44], [115, 47], [92, 45], [104, 43], [98, 50], [112, 48], [90, 47], [118, 46], [102, 42], [96, 44]];
  for (const c of spots) {
    if (counter.n >= n) break;
    await page.evaluate((c) => window.__nav002.map.jumpTo({ center: c, zoom: 10 }), c);
    await page.waitForTimeout(700);
  }
  return counter.n;
}

test.describe('NAV-002 I. Loading', () => {
  // TC-37-01, run 4 (2026-09-30). Measures when the pill is really ON SCREEN, from compositor frames (CDP
  // Page.startScreencast), not from DOM classes. Reason: since the boot script (web/src/boot/bootLoading.ts) the pill
  // loses its `idle` class at ~30 ms while it is still transparent (`.pending`, opacity reveal after --pill-delay), so the
  // old timeline flag flipped at boot and the old assertion could no longer fail. Frame timestamps (wall clock, s) are
  // put on the page clock with performance.timeOrigin; a calibration run gave frame lag 17–21 ms after a DOM insert
  // (one frame, never negative). Pass: first frame showing the G1 text ≤ 350 ms (300 ms + 50 ms frame tolerance).
  test('AC37 loading indicator is on screen when the map is not ready within 300 ms (measured from navigation start, screencast frames)', async ({ page, context }) => {
    await delayArchive(page, 1500);
    // Negative control (test-only, off by default): NAV002_AC37_NEGCTL_MS=700 hides the pill until 700 ms after
    // navigation start, so this test must FAIL. Proves the screencast measurement can detect a late reveal.
    const negCtl = Number(process.env.NAV002_AC37_NEGCTL_MS || 0);
    if (negCtl > 0) {
      await page.addInitScript((ms) => {
        const st = document.createElement('style');
        st.textContent = '#loading{visibility:hidden !important}';
        document.documentElement.appendChild(st);
        setTimeout(() => st.remove(), Math.max(0, ms - performance.now()));
      }, negCtl);
    }
    // Diagnostics only (not the gate): when `pending` was added + its --pill-delay (the app's scheduled reveal), the
    // CSS animation's start time on the document timeline, and when `pending` was removed (boot `due` timer).
    await page.addInitScript(() => {
      const d = (window.__ac37 = { pendingAt: null, delay: null, animStart: null, pendingOff: null });
      // Installed before any page script; the boot script runs inline during parsing, so observe the whole document.
      new MutationObserver((recs) => {
        const now = performance.now();
        for (const rec of recs) {
          const el = rec.target;
          if (el.id !== 'loading') continue;
          if (el.classList.contains('pending') && d.pendingAt === null) {
            d.pendingAt = now;
            d.delay = el.style.getPropertyValue('--pill-delay');
            requestAnimationFrame(() => {
              const a = el.getAnimations()[0];
              if (a) d.animStart = a.startTime;
            });
          } else if (!el.classList.contains('pending') && d.pendingAt !== null && d.pendingOff === null) d.pendingOff = now;
        }
      }).observe(document.documentElement, { attributes: true, subtree: true, attributeFilter: ['class'] });
    });
    const cdp = await context.newCDPSession(page);
    const frames = [];
    cdp.on('Page.screencastFrame', (f) => {
      frames.push({ ts: f.metadata.timestamp * 1000, data: f.data });
      cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {});
    });
    await cdp.send('Page.startScreencast', { format: 'png', everyNthFrame: 1 });
    await openApp(page, { wait: false });
    // The archive is delayed 1.5 s, so at 1.1 s the pill must be fully shown: that frame is the reference.
    await page.waitForFunction(() => performance.now() > 1100, null, { timeout: 10_000 });
    const info = await page.evaluate(() => {
      const r = document.querySelector('#loading-text').getBoundingClientRect();
      return {
        box: { x: r.x, y: r.y, w: r.width, h: r.height }, origin: performance.timeOrigin,
        opacity: getComputedStyle(document.querySelector('#loading')).opacity,
        fcp: performance.getEntriesByName('first-contentful-paint')[0]?.startTime ?? null,
      };
    });
    await cdp.send('Page.stopScreencast');
    await cdp.detach();
    expect(info.opacity, 'pill fully shown at 1.1 s (reference frame)').toBe('1');
    expect(info.box.w * info.box.h, 'pill text laid out').toBeGreaterThan(0);
    // Decode the frames in a separate blank page: mean RGB distance of the text box to the reference frame.
    const dec = await context.newPage();
    const dist = await dec.evaluate(async ({ frames, box }) => {
      const crop = async (d) => {
        const img = new Image();
        img.src = `data:image/png;base64,${d}`;
        await img.decode();
        const c = new OffscreenCanvas(img.width, img.height);
        const g = c.getContext('2d');
        g.drawImage(img, 0, 0);
        return g.getImageData(Math.round(box.x), Math.round(box.y), Math.max(1, Math.round(box.w)), Math.max(1, Math.round(box.h))).data;
      };
      const px = [];
      for (const f of frames) px.push(await crop(f.data));
      const ref = px[px.length - 1];
      return px.map((a) => {
        let s = 0;
        for (let i = 0; i < a.length; i += 4) s += Math.abs(a[i] - ref[i]) + Math.abs(a[i + 1] - ref[i + 1]) + Math.abs(a[i + 2] - ref[i + 2]);
        return s / (a.length / 4);
      });
    }, { frames, box: info.box });
    await dec.close();
    const maxD = Math.max(...dist);
    const shown = dist.map((d) => maxD > 0 && d < 0.25 * maxD); // frames are bimodal (0.00 vs 1.00 of max) in practice
    const t = frames.map((f) => f.ts - info.origin);
    const iOn = shown.findIndex((x, i) => x && t[i] >= 0);
    expect(iOn, 'a frame showing the pill text was captured').toBeGreaterThanOrEqual(0);
    const onScreen = t[iOn];
    const lastWithout = t.filter((x, i) => i < iOn && x >= 0).pop() ?? null;
    const classFlag = await tOf(page, (s) => s.loading); // old measurement (DOM class), information only
    const d = await page.evaluate(() => window.__ac37);
    const r = (x) => (x === null || x === undefined ? 'n/a' : Math.round(x));
    test.info().annotations.push({
      type: 'AC37 pill on screen (ms after navigation start)',
      description: `${Math.round(onScreen)} (last frame without it: ${lastWithout === null ? 'none' : Math.round(lastWithout)}; ` +
        `first-contentful-paint ${Math.round(info.fcp)}; DOM class flag ${Math.round(classFlag)}; ${frames.length} frames; ` +
        `pending added ${r(d?.pendingAt)} + --pill-delay ${d?.delay || 'n/a'} = scheduled ${d?.pendingAt != null && d?.delay ? Math.round(d.pendingAt + parseFloat(d.delay)) : 'n/a'}; ` +
        `animation start ${r(d?.animStart)}; pending removed ${r(d?.pendingOff)})`,
    });
    expect(maxD, 'the pill changes the pixels of its text box (screencast works)').toBeGreaterThan(10);
    expect(onScreen, 'loading indicator on screen by 350 ms after navigation start').toBeLessThanOrEqual(350);
  });

  test('AC37 loading indicator shows G1, is announced (aria-live polite) and hides within 500 ms of the first idle', async ({ page }) => {
    await page.addInitScript(firstIdleInit);
    await delayArchive(page, 1500);
    await openApp(page, { wait: false });
    const pill = tid(page, 'loading');
    await expect(pill).toBeVisible({ timeout: 5000 });
    await expect(pill).toHaveText(S.mn.loading);
    await expect(pill).toHaveAttribute('aria-live', 'polite');
    await expect(pill).toHaveAttribute('role', 'status');
    await expect(pill).not.toHaveAttribute('aria-hidden', 'true');
    expect(await attributionProblems(page)).toEqual([]);
    await expect(tid(page, 'zoom-in')).toBeVisible({ timeout: 20_000 });
    await expect(pill).toBeHidden();
    await page.waitForTimeout(300);
    const firstIdle = await page.evaluate(() => window.__firstIdle);
    const off = await tOf(page, (s) => s.ready && !s.loading);
    test.info().annotations.push({ type: 'AC37 hide lag after first idle (ms)', description: String(Math.round(off - firstIdle)) });
    expect(firstIdle).toBeTruthy();
    expect(off - firstIdle).toBeLessThanOrEqual(500);
  });
});

test.describe('NAV-002 I. Tiles unavailable at start', () => {
  const expectCard = async (page, lang = 'mn') => {
    const card = tid(page, 'blocking-card');
    await expect(card).toBeVisible({ timeout: 10_000 });
    await expect(card).toHaveAttribute('data-reason', 'tiles');
    await expect(card).toContainText(S[lang].tiles);
    await expect(tid(page, 'card-retry')).toHaveText(S[lang].retry);
    await expect(tid(page, 'loading')).toBeHidden(); // no endless spinner
    expect(await attributionProblems(page)).toEqual([]);
    const t = await tOf(page, (s) => s.card === 'tiles');
    return t;
  };

  test('AC38 closed-port gateway (VITE_GATEWAY_BASE_URL=http://localhost:59999): card with G2 + «Дахин оролдох» within 10 s', async ({ page }) => {
    const reqs = [];
    page.on('request', (r) => reqs.push(r.url()));
    await openApp(page, { url: 'http://localhost:5174/', wait: false });
    const t = await expectCard(page);
    test.info().annotations.push({ type: 'AC38 card after (ms)', description: String(Math.round(t)) });
    expect(t).toBeLessThanOrEqual(10_000);
    expect(reqs.filter((u) => u.includes(':8080'))).toEqual([]);
    expect(reqs.some((u) => u === 'http://localhost:59999/tiles/basemap.pmtiles')).toBe(true);
  });

  test('AC38 tile requests aborted by the test: card within 10 s', async ({ page }) => {
    await page.route(TILES_GLOB, (r) => r.abort('connectionrefused'));
    await openApp(page, { wait: false });
    const t = await expectCard(page);
    expect(t).toBeLessThanOrEqual(10_000);
  });

  for (const [name, reply] of [
    ['404', { status: 404, contentType: 'application/json', body: '{"error":"not_found"}' }],
    ['500', { status: 500, body: 'error' }],
    ['502', { status: 502, contentType: 'text/html', body: '<html><body>502 Bad Gateway</body></html>' }],
    ['503', { status: 503, body: 'unavailable' }],
    ['200 HTML body (not PMTiles v3)', { status: 200, contentType: 'text/html', body: '<!doctype html><html><body>' + 'x'.repeat(20000) + '</body></html>' }],
    ['206 HTML body (not PMTiles v3)', { status: 206, contentType: 'text/html', headers: { 'Content-Range': 'bytes 0-16383/99999', 'Access-Control-Allow-Origin': '*' }, body: '<html>' + 'y'.repeat(16378) + '</html>' }],
  ]) {
    test(`AC39 archive answers ${name}: card within 10 s`, async ({ page }) => {
      await page.route(TILES_GLOB, (r) => r.fulfill({ ...reply, headers: { 'Access-Control-Allow-Origin': '*', ...(reply.headers ?? {}) } }));
      await openApp(page, { wait: false });
      const t = await expectCard(page);
      test.info().annotations.push({ type: `AC39 ${name} card after (ms)`, description: String(Math.round(t)) });
      expect(t).toBeLessThanOrEqual(10_000);
    });
  }

  test('AC40 cause removed + «Дахин оролдох»: map renders within 5 s, card gone, language and mode kept', async ({ page }) => {
    await page.route(TILES_GLOB, (r) => r.abort());
    await openApp(page, { wait: false, lang: 'en', theme: 'night' });
    await expect(tid(page, 'blocking-card')).toBeVisible({ timeout: 10_000 });
    // A retry while the cause is still there keeps the card (flow F1)
    await tid(page, 'card-retry').click();
    await page.waitForTimeout(1500);
    await expect(tid(page, 'blocking-card')).toBeVisible();
    await page.unroute(TILES_GLOB);
    const t0 = Date.now();
    await tid(page, 'card-retry').click();
    await expect(tid(page, 'blocking-card')).toBeHidden({ timeout: 5000 });
    await expect(tid(page, 'zoom-in')).toBeVisible({ timeout: 5000 - (Date.now() - t0) });
    await waitIdle(page);
    const dt = Date.now() - t0;
    test.info().annotations.push({ type: 'AC40 recovery (ms)', description: String(dt) });
    expect(dt).toBeLessThanOrEqual(5000);
    expect(await renderedRoadCount(page)).toBeGreaterThan(0);
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'night');
    expect(await statusView(page)).toEqual({ loading: false, card: null, banner: null });
  });
});

test.describe('NAV-002 I. Tiles failing while shown', () => {
  test('AC41 three consecutive tile failures → banner G2 + «Дахин оролдох» within 5 s; drawn tiles stay; interactive; next good tile hides it within 2 s', async ({ page }) => {
    await openApp(page);
    await jump(page, { center: P1, zoom: 12 });
    const counter = { n: 0, at: [] };
    await page.route(TILES_GLOB, (r) => {
      counter.n++;
      counter.at.push(Date.now());
      return r.fulfill({ status: 503, body: 'x', headers: { 'Access-Control-Allow-Origin': '*' } });
    });
    // Small pans first so already drawn tiles stay in view.
    for (let i = 1; i <= 8 && counter.n < 3; i++) {
      await page.evaluate((i) => window.__nav002.map.jumpTo({ center: [106.9176 + i * 0.12, 47.9189], zoom: 12 }), i);
      await page.waitForTimeout(700);
    }
    if (counter.n < 3) await panUntilFailures(page, counter, 3);
    expect(counter.n).toBeGreaterThanOrEqual(3);
    const banner = tid(page, 'status-banner');
    await expect(banner).toBeVisible({ timeout: 5000 });
    const shownAt = Date.now();
    test.info().annotations.push({ type: 'AC41 banner after 3rd failure (ms)', description: String(shownAt - counter.at[2]) });
    expect(shownAt - counter.at[2]).toBeLessThanOrEqual(5000);
    await expect(banner).toHaveAttribute('data-reason', 'tiles');
    await expect(banner).toContainText(S.mn.tiles);
    await expect(tid(page, 'banner-retry')).toHaveText(S.mn.retry);
    await expect(tid(page, 'blocking-card')).toBeHidden();
    // Drawn tiles stay: go back to the loaded area
    await jump(page, { center: P1, zoom: 12 });
    expect(await renderedRoadCount(page)).toBeGreaterThan(0);
    // Interactive
    const z = (await camera(page)).zoom;
    await tid(page, 'zoom-out').click();
    await expect.poll(async () => (await camera(page)).zoom).toBeLessThan(z - 0.9);
    expect(await attributionProblems(page)).toEqual([]);
    // Cause removed; a later good tile (pan to a new area) hides the banner within 2 s
    await page.unroute(TILES_GLOB);
    let goodAt = null;
    page.on('response', (r) => {
      if (!goodAt && r.url().includes('basemap.pmtiles') && r.status() === 206) goodAt = Date.now();
    });
    // An area never requested in this session (Darkhan). Areas whose tiles errored while the route was
    // failing are not re-requested by MapLibre; there the banner's «Дахин оролдох» is the recovery (next test).
    await page.evaluate(() => window.__nav002.map.jumpTo({ center: [105.95, 49.48], zoom: 12 }));
    await expect(banner).toBeHidden({ timeout: 10_000 });
    const hiddenAt = Date.now();
    expect(goodAt, 'a tile request succeeded').not.toBeNull();
    test.info().annotations.push({ type: 'AC41 banner hide after good tile (ms)', description: String(hiddenAt - goodAt) });
    expect(hiddenAt - goodAt).toBeLessThanOrEqual(2000);
  });

  test('AC41 banner «Дахин оролдох» after the cause is removed hides the banner within 2 s and tiles load', async ({ page }) => {
    await openApp(page);
    const counter = { n: 0 };
    await page.route(TILES_GLOB, (r) => {
      counter.n++;
      return r.fulfill({ status: 502, body: 'x', headers: { 'Access-Control-Allow-Origin': '*' } });
    });
    await panUntilFailures(page, counter, 3);
    await expect(tid(page, 'status-banner')).toBeVisible({ timeout: 5000 });
    await page.unroute(TILES_GLOB);
    const t0 = Date.now();
    await tid(page, 'banner-retry').click();
    await expect(tid(page, 'status-banner')).toBeHidden({ timeout: 2000 });
    test.info().annotations.push({ type: 'AC41 retry → banner hidden (ms)', description: String(Date.now() - t0) });
    await waitIdle(page);
    // The failed areas render now (countryside: land at least), and a city shows roads.
    expect(await page.evaluate(() => window.__nav002.map.queryRenderedFeatures({ layers: ['earth'] }).length)).toBeGreaterThan(0);
    await jump(page, { center: P1, zoom: 12 });
    expect(await renderedRoadCount(page)).toBeGreaterThan(0);
    expect(await statusView(page)).toEqual({ loading: false, card: null, banner: null });
  });

  test('AC42 (simulated) archive replaced mid-session — new ETag on every response: map keeps rendering or shows the banner; never blank without message > 10 s; reload loads', async ({ page }) => {
    // Simulation limit: only the ETag changes (the bytes are the real archive). A real content change
    // needs a NAV-001 rebuild, which this run must not trigger.
    await openApp(page);
    let changed = false;
    await page.route(TILES_GLOB, async (r) => {
      const resp = await r.fetch();
      const headers = { ...resp.headers() };
      if (changed) headers.etag = '"nav002-replaced-archive"';
      await r.fulfill({ response: resp, headers });
    });
    changed = true;
    const problems = [];
    for (const c of [[106.5, 47.7], [105.0, 46.0], [100.0, 49.0], [107.5, 48.3], [106.9176, 47.9189]]) {
      await page.evaluate((c) => window.__nav002.map.jumpTo({ center: c, zoom: 12 }), c);
      const t0 = Date.now();
      let ok = false;
      while (Date.now() - t0 < 10_000) {
        const roads = await renderedRoadCount(page).catch(() => 0);
        const sv = await statusView(page);
        const earthDrawn = await page.evaluate(() => window.__nav002.map.queryRenderedFeatures({ layers: ['earth'] }).length);
        if (roads > 0 || earthDrawn > 0 || sv.banner || sv.card) {
          ok = true;
          break;
        }
        await page.waitForTimeout(250);
      }
      if (!ok) problems.push(`blank without message > 10 s at ${c}`);
    }
    expect(problems).toEqual([]);
    const sv = await statusView(page);
    if (sv.banner) {
      await tid(page, 'banner-retry').click();
      await expect(tid(page, 'status-banner')).toBeHidden({ timeout: 5000 });
    }
    await page.reload();
    await expect(tid(page, 'zoom-in')).toBeVisible({ timeout: 20_000 });
    await waitIdle(page);
    expect(await renderedRoadCount(page)).toBeGreaterThan(0);
  });
});

test.describe('NAV-002 I. Offline', () => {
  test('AC43 offline → banner «Интернэт холболт алга» within 2 s; drawn tiles stay; pan/zoom in loaded area works; no tiles banner at the same time', async ({ page, context }) => {
    await openApp(page);
    await jump(page, { center: P1, zoom: 12 });
    const t0 = await page.evaluate(() => performance.now());
    await context.setOffline(true);
    const banner = tid(page, 'status-banner');
    await expect(banner).toHaveAttribute('data-reason', 'offline', { timeout: 2000 });
    await expect(banner).toBeVisible();
    const t = await tOf(page, (s) => s.t >= t0 && s.banner === 'offline');
    test.info().annotations.push({ type: 'AC43 offline banner after (ms)', description: String(Math.round(t - t0)) });
    expect(t - t0).toBeLessThanOrEqual(2000);
    await expect(banner).toContainText(S.mn.offline);
    await expect(tid(page, 'banner-retry')).toBeHidden();
    expect(await renderedRoadCount(page)).toBeGreaterThan(0);
    // Pan and zoom inside the loaded area
    const before = await camera(page);
    await page.evaluate(() => window.__nav002.map.panBy([80, 40], { duration: 0 }));
    await tid(page, 'zoom-in').click();
    await page.waitForTimeout(600);
    const after = await camera(page);
    expect(after.zoom).toBeGreaterThan(before.zoom + 0.9);
    expect(await renderedRoadCount(page)).toBeGreaterThan(0);
    // Pan to unloaded areas: tile requests fail, but the tiles banner must not replace or join the offline banner
    for (const c of [[100, 46], [95, 49], [110, 44], [92, 45]]) {
      await page.evaluate((c) => window.__nav002.map.jumpTo({ center: c, zoom: 10 }), c);
      await page.waitForTimeout(600);
    }
    const sv = await statusView(page);
    expect(sv).toEqual({ loading: false, card: null, banner: 'offline' });
    const tl = await timeline(page);
    expect(tl.filter((s) => s.t >= t0 && s.banner === 'tiles'), 'tiles banner never shown while offline').toEqual([]);
    expect(await attributionProblems(page)).toEqual([]);
  });

  test('AC44 back online → banner hides within 2 s; missing tiles load without a reload', async ({ page, context }) => {
    await openApp(page);
    await page.evaluate(() => (window.__noReload = true));
    await context.setOffline(true);
    await expect(tid(page, 'status-banner')).toHaveAttribute('data-reason', 'offline', { timeout: 2000 });
    const NEW = { lat: 46.2, lng: 100.7 }; // not loaded yet
    await page.evaluate((c) => window.__nav002.map.jumpTo({ center: [c.lng, c.lat], zoom: 11 }), NEW);
    await page.waitForTimeout(1500);
    const t0 = await page.evaluate(() => performance.now());
    await context.setOffline(false);
    await expect(tid(page, 'status-banner')).toBeHidden({ timeout: 2000 });
    const t = await tOf(page, (s) => s.t >= t0 && !s.banner);
    test.info().annotations.push({ type: 'AC44 banner hidden after (ms)', description: String(Math.round(t - t0)) });
    expect(t - t0).toBeLessThanOrEqual(2000);
    await waitIdle(page);
    await expect.poll(() => page.evaluate(() => window.__nav002.map.queryRenderedFeatures({ layers: ['earth'] }).length), { timeout: 10_000 }).toBeGreaterThan(0);
    const c = await camera(page);
    expect(Math.abs(c.lat - NEW.lat)).toBeLessThan(0.01);
    expect(await page.evaluate(() => window.__noReload)).toBe(true);
    expect(await statusView(page)).toEqual({ loading: false, card: null, banner: null });
  });
});

test.describe('NAV-002 I. Precedence', () => {
  test('AC45 before the first render: tiles-unavailable card + offline → only the offline message; loading never shown with it', async ({ page, context }) => {
    await page.route(TILES_GLOB, (r) => r.abort());
    await openApp(page, { wait: false });
    await expect(tid(page, 'blocking-card')).toHaveAttribute('data-reason', 'tiles', { timeout: 10_000 });
    await context.setOffline(true);
    await expect(tid(page, 'blocking-card')).toHaveAttribute('data-reason', 'offline', { timeout: 2000 });
    await expect(tid(page, 'blocking-card')).toContainText(S.mn.offline);
    await expect(tid(page, 'blocking-card')).not.toContainText(S.mn.tiles);
    expect(await statusView(page)).toEqual({ loading: false, card: 'offline', banner: null });
    const tl = await timeline(page);
    expect(tl.filter((s) => [s.loading, !!s.card, !!s.banner].filter(Boolean).length > 1), 'never two status messages at once').toEqual([]);
    await context.setOffline(false);
  });

  test('AC45 loading + offline → only the offline message', async ({ page, context }) => {
    await delayArchive(page, 4000);
    await openApp(page, { wait: false });
    await expect(tid(page, 'loading')).toBeVisible({ timeout: 3000 });
    await context.setOffline(true);
    await expect(tid(page, 'blocking-card')).toHaveAttribute('data-reason', 'offline', { timeout: 2000 });
    expect(await statusView(page)).toEqual({ loading: false, card: 'offline', banner: null });
    await context.setOffline(false);
  });

  test('AC45 location message together with a status banner; attribution never covered', async ({ page, context }) => {
    await context.clearPermissions();
    await openApp(page);
    await tid(page, 'my-location').click();
    await expect(tid(page, 'location-message')).toBeVisible();
    await context.setOffline(true);
    await expect(tid(page, 'status-banner')).toHaveAttribute('data-reason', 'offline');
    await expect(tid(page, 'location-message')).toBeVisible();
    expect(await attributionProblems(page)).toEqual([]);
    await context.setOffline(false);
    const tl = await timeline(page);
    expect(tl.filter((s) => s.banner === 'tiles' && s.t > 0 && tl.some((x) => x.banner === 'offline' && x.t === s.t))).toEqual([]);
  });
});

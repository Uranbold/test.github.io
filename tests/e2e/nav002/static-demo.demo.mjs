// NAV-002 section M (AC 51–54, PO decision D44) plus NAV-003 AC 30/33/39/41 in the static public demo, and the NAV-003
// screen spec › States › Static public demo (UX, 2026-09-30). Runs against the documented static-demo build served by a
// local Range-capable static server (see static-demo.config.mjs, static-demo.setup.mjs). Test plan:
// docs/qa/test-plans/NAV-002.md › M.
//
// What the local server can and cannot stand in for: it is a plain static host with HTTP Range, no compression and an
// access log, like the PO's shared web hosting is expected to be. HTTPS, the 301/308 redirect, the certificate, the
// host's file-size limit and the "within 10 s from a UB fixed line" timing of AC 51 need the real public site and are
// NOT covered here (docs/qa/test-plans/NAV-002.md › 6).
import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { S, WEB, attributionProblems, tid, timelineInit } from './helpers.mjs';
import { T } from '../nav003/helpers.mjs';

const PORT = Number(process.env.NAV002_SD_PORT ?? 18088);
const HOST_A = `http://a.demo-host.test:${PORT}`;
const HOST_B = `http://b.demo-host.test:${PORT}`;
const HOST_IP = `http://127.0.0.1:${PORT}`;
const ARCHIVE_BYTES = Number(process.env.NAV002_SD_ARCHIVE_BYTES ?? 0);
const TILES_KIND = process.env.NAV002_SD_TILES_KIND ?? 'ub';
const CONTAINER = process.env.NAV002_SD_CONTAINER ?? 'qa-nav002-static-demo';
const TEN_MB = 10 * 1024 * 1024;
const BACKEND_PATH = /\/v1\/|\/(search|reverse|route|api|status)(\/|\?|$)/i;
// Every path the static site may legitimately serve (the built files and the basemap archive).
const STATIC_PATH = /^\/($|index\.html$|assets\/|fonts\/|sprites\/|tiles\/basemap\.pmtiles$|favicon\.ico$)/;
const read = (p) => readFileSync(p, 'utf8');

// ------------------------------------------------------------------------------------------------ helpers

/** Records every request of a context with its response (status, headers, body size) and completion. */
function recordNet(context) {
  const list = [];
  const byReq = new Map();
  context.on('request', (r) => {
    const e = { url: r.url(), method: r.method(), range: r.headers()['range'] ?? null, type: r.resourceType(), done: false, status: null, headers: {}, bytes: null, failed: null };
    list.push(e);
    byReq.set(r, e);
  });
  context.on('requestfinished', async (r) => {
    const e = byReq.get(r);
    if (!e) return;
    try {
      const resp = await r.response();
      e.status = resp?.status() ?? null;
      e.headers = resp ? await resp.allHeaders() : {};
      const sz = await r.sizes().catch(() => null);
      e.bytes = sz ? sz.responseBodySize : null;
    } catch {}
    e.done = true;
  });
  context.on('requestfailed', (r) => {
    const e = byReq.get(r);
    if (!e) return;
    e.failed = r.failure()?.errorText ?? 'failed';
    e.done = true;
  });
  return list;
}

/** Waits until no recorded request has been pending for `quietMs`. */
async function netQuiet(list, quietMs = 700, timeout = 20_000) {
  const t0 = Date.now();
  let lastBusy = Date.now();
  let lastLen = list.length;
  while (Date.now() - t0 < timeout) {
    if (list.some((e) => !e.done) || list.length !== lastLen) {
      lastBusy = Date.now();
      lastLen = list.length;
    }
    if (Date.now() - lastBusy >= quietMs) return;
    await new Promise((r) => setTimeout(r, 100));
  }
}

/** In-page recorder for the NAV-003 search box and the coordinate card (production build: DOM only, no test hooks). */
function sdInit() {
  const sd = (window.__sd = { keys: [], clicks: [], ctx: [], states: [], near: [] });
  document.addEventListener('keydown', () => sd.keys.push(performance.now()), true);
  document.addEventListener('click', (e) => sd.clicks.push({ t: performance.now(), id: e.target?.closest?.('[data-testid]')?.dataset.testid ?? null }), true);
  document.addEventListener('contextmenu', () => sd.ctx.push(performance.now()), true);
  let lastS = '';
  let lastN = '';
  const vis = (e) => !!e && !e.hidden && !e.closest('[hidden]');
  const snap = () => {
    const q = (s) => document.querySelector(s);
    const pop = q('#search-popup');
    const row = q('#search-state');
    const s = {
      popup: vis(pop) ? pop.dataset.state ?? '?' : 'hidden',
      row: vis(row) ? row.dataset.state ?? '?' : null,
      text: vis(row) ? q('#search-state-text')?.textContent ?? null : null,
      retry: vis(q('#search-retry')),
      options: document.querySelectorAll('#search-results [role=option]').length,
    };
    const k = JSON.stringify(s);
    if (k !== lastS) {
      lastS = k;
      sd.states.push({ t: performance.now(), ...s });
    }
    const near = q('#place-nearest');
    const n = { card: vis(q('#place-card')), state: vis(near) ? near.dataset.state ?? '?' : null, text: vis(near) ? near.innerText.replace(/\s+/g, ' ').trim() : null, retry: vis(q('#place-retry')) };
    const nk = JSON.stringify(n);
    if (nk !== lastN) {
      lastN = nk;
      sd.near.push({ t: performance.now(), ...n });
    }
  };
  new MutationObserver(snap).observe(document, { subtree: true, childList: true, characterData: true, attributes: true });
}

async function openDemo(page, base, { lang, theme } = {}) {
  await page.addInitScript(timelineInit);
  await page.addInitScript(sdInit);
  if (lang || theme) {
    await page.addInitScript(({ lang, theme }) => {
      if (lang) localStorage.setItem('navmn.lang', lang);
      if (theme) localStorage.setItem('navmn.theme', theme);
    }, { lang, theme });
  }
  await page.goto(base + '/');
  await expect(tid(page, 'zoom-in')).toBeVisible({ timeout: 20_000 });
}

/** ms since navigation start when the app first showed the ready UI (first idle with tiles, NAV-002 timeline). */
const readyAt = (page) => page.evaluate(() => (window.__t || []).find((s) => s.ready)?.t ?? null);

/** Caddy access log lines (JSON) of the local static server. */
function accessLog() {
  const out = execFileSync('docker', ['logs', CONTAINER], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 });
  return out
    .split('\n')
    .filter((l) => l.startsWith('{') && l.includes('"request"'))
    .map((l) => JSON.parse(l))
    .filter((j) => j.request?.uri)
    .map((j) => ({ uri: j.request.uri, host: j.request.host, range: j.request.headers?.Range?.[0] ?? null, referer: j.request.headers?.Referer?.[0] ?? null, status: j.status, size: j.size }));
}

/** Types a query key by key and waits for the settled result (the state row or the list). */
async function typeSettled(page, text) {
  const input = tid(page, 'search-input');
  await input.click();
  await input.fill('');
  await page.keyboard.type(text, { delay: 40 });
  await page.waitForTimeout(1300); // debounce 250 ms + the 1 s budget, then measured in the page
}

/** From the in-page record: ms from the last keydown to the first unavailable row with message and retry. */
async function unavailableAfterLastKey(page, message) {
  return page.evaluate((message) => {
    const sd = window.__sd;
    const last = Math.max(...sd.keys);
    const first = sd.keys.length ? Math.min(...sd.keys) : last;
    const hit = sd.states.find((s) => s.t >= last && s.row === 'unavailable' && s.text === message && s.retry);
    const loading = sd.states.filter((s) => s.t >= first && (s.row === 'loading' || s.popup === 'loading'));
    return { ms: hit ? hit.t - last : null, loading: loading.length, states: sd.states.filter((s) => s.t >= first).map((s) => ({ ...s, t: Math.round(s.t - last) })) };
  }, message);
}

function backendOrForeign(list, origin) {
  return list.filter((e) => /^https?:/.test(e.url)).filter((e) => new URL(e.url).origin !== origin || BACKEND_PATH.test(new URL(e.url).pathname)).map((e) => `${e.method} ${e.url}`);
}

// ------------------------------------------------------------------------------------------------ tests

test.describe('NAV-002 M. Static public demo (D44), local static server', () => {
  test('AC51/AC52 any domain, no rebuild: the same build opens on two hostnames and 127.0.0.1; tiles come from each page origin; ready within 10 s; attribution visible; day default (AC26)', async ({ browser }) => {
    const ready = {};
    for (const base of [HOST_A, HOST_B, HOST_IP]) {
      const ctx = await browser.newContext({ viewport: { width: 1366, height: 768 } });
      const net = recordNet(ctx);
      const page = await ctx.newPage();
      await openDemo(page, base);
      await netQuiet(net);
      ready[base] = await readyAt(page);
      const tiles = net.filter((e) => e.url.includes('/tiles/'));
      expect(tiles.length, `${base}: basemap requests`).toBeGreaterThan(0);
      for (const e of tiles) expect(e.url, 'tiles URL = page origin + /tiles/basemap.pmtiles').toBe(`${base}/tiles/basemap.pmtiles`);
      expect(backendOrForeign(net, base), `${base}: 0 requests to another host or a backend path (AC 52, AC 53)`).toEqual([]);
      expect(net.filter((e) => e.failed || (e.status ?? 0) >= 400).map((e) => `${e.status ?? e.failed} ${e.url}`), `${base}: no failed request`).toEqual([]);
      expect(await page.evaluate(() => document.documentElement.dataset.theme), 'AC 26 first visit: day').toBe('day');
      expect(await attributionProblems(page), `${base}: AC 34 «© OpenStreetMap contributors» visible`).toEqual([]);
      await expect(page).toHaveTitle(S.mn.map);
      await expect(tid(page, 'map-canvas')).toBeVisible();
      await ctx.close();
    }
    test.info().annotations.push({ type: 'AC51 ready (first idle with tiles, ms since navigation start, local server)', description: JSON.stringify(ready) });
    for (const [base, t] of Object.entries(ready)) {
      expect(t, `${base}: ready recorded`).not.toBeNull();
      expect(t, `${base}: AC 51 opening view with tiles within 10 s (local server; the UB fixed-line check needs the public site)`).toBeLessThanOrEqual(10_000);
    }
  });

  test('AC51 subset: language switch (AC31), theme toggle (AC26), ESA credit at z <= 7 (AC35), Tab order (AC48) on the static build', async ({ page }) => {
    await openDemo(page, HOST_A);
    // AC 31: English within 500 ms, <html lang="en">, title "Map"
    const t0 = Date.now();
    await tid(page, 'language-toggle').click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en', { timeout: 500 });
    await expect(page).toHaveTitle(S.en.map);
    test.info().annotations.push({ type: 'AC31 switch (Node-side, upper bound)', description: `${Date.now() - t0} ms` });
    await expect(tid(page, 'zoom-in')).toHaveAccessibleName(S.en.zoomIn);
    await tid(page, 'language-toggle').click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'mn');
    // AC 26: theme toggle switches to night and back
    await tid(page, 'theme-toggle').click();
    await expect.poll(() => page.evaluate(() => document.documentElement.dataset.theme)).toBe('night');
    await tid(page, 'theme-toggle').click();
    await expect.poll(() => page.evaluate(() => document.documentElement.dataset.theme)).toBe('day');
    // AC 35: at zoom <= 7 (5 zoom-out presses from z12) the ESA credit is visible too
    for (let i = 0; i < 5; i++) {
      await tid(page, 'zoom-out').click();
      await page.waitForTimeout(400);
    }
    await expect(tid(page, 'attribution-esa')).toBeVisible();
    expect(await attributionProblems(page, 'attribution-esa', S.esa)).toEqual([]);
    expect(await attributionProblems(page)).toEqual([]);
    // AC 48: Tab order as on the dev server (NAV-003 order: search input first)
    await page.keyboard.press('Escape');
    await page.evaluate(() => document.activeElement?.blur());
    const seq = [];
    for (let i = 0; i < 9; i++) {
      await page.keyboard.press('Tab');
      seq.push(await page.evaluate(() => document.activeElement?.dataset.testid ?? document.activeElement?.tagName));
    }
    expect(seq).toEqual(['search-input', 'language-toggle', 'theme-toggle', 'compass', 'map-canvas', 'zoom-in', 'zoom-out', 'my-location', 'attribution-osm']);
  });

  test('AC52: every basemap read is an HTTP Range request answered 206 (browser and server log), no response > 10 MB or anywhere near the whole archive, no Content-Encoding, page origin only; zoom z12 -> z18 -> z3 and pans', async ({ page, context }) => {
    test.setTimeout(180_000);
    const logBefore = accessLog().length;
    const net = recordNet(context);
    await openDemo(page, HOST_A);
    await netQuiet(net);
    const scale = [];
    const note = async (what) => scale.push(`${what}: ${(await tid(page, 'scale-label').textContent())?.trim()}`);
    await note('open z12');
    for (let i = 0; i < 6; i++) {
      await tid(page, 'zoom-in').click();
      await netQuiet(net, 500);
    }
    await note('after 6x zoom in (z18)');
    const drag = async (dx, dy) => {
      await page.mouse.move(700, 450);
      await page.mouse.down();
      for (let i = 1; i <= 10; i++) await page.mouse.move(700 + (dx * i) / 10, 450 + (dy * i) / 10);
      await page.mouse.up();
      await netQuiet(net, 500);
    };
    await drag(-250, 120);
    for (let i = 0; i < 15; i++) {
      await tid(page, 'zoom-out').click();
      await netQuiet(net, 400);
    }
    await note('after 15x zoom out (z3)');
    // Towards Erdenet (X1) and then the steppe, at z6, then back down to z8
    for (let i = 0; i < 3; i++) {
      await tid(page, 'zoom-in').click();
      await netQuiet(net, 400);
    }
    await drag(130, 75);
    await drag(-60, -260);
    for (let i = 0; i < 2; i++) {
      await tid(page, 'zoom-in').click();
      await netQuiet(net, 400);
    }
    await note('end (z8)');
    await page.waitForTimeout(500);
    const tiles = net.filter((e) => e.url.includes('/tiles/basemap.pmtiles'));
    const problems = [];
    for (const e of tiles) {
      if (!/^bytes=\d+-\d+$/.test(e.range ?? '')) problems.push(`no bounded Range header: ${e.range}`);
      if (e.status !== 206) problems.push(`status ${e.status ?? e.failed} for Range ${e.range}`);
      if (!/^bytes \d+-\d+\/\d+$/.test(e.headers['content-range'] ?? '')) problems.push(`Content-Range «${e.headers['content-range']}»`);
      if (e.headers['content-encoding']) problems.push(`Content-Encoding ${e.headers['content-encoding']}`);
      const len = Number(e.headers['content-length'] ?? e.bytes ?? 0);
      if (len > TEN_MB) problems.push(`response ${len} B > 10 MB`);
      if (ARCHIVE_BYTES && len >= ARCHIVE_BYTES / 2) problems.push(`response ${len} B is >= half the archive (${ARCHIVE_BYTES} B)`);
    }
    const server = accessLog().slice(logBefore);
    const serverTiles = server.filter((l) => l.uri.startsWith('/tiles/'));
    const serverProblems = serverTiles.filter((l) => l.status !== 206 || !l.range).map((l) => `${l.status} ${l.uri} Range=${l.range}`);
    const maxLen = Math.max(0, ...tiles.map((e) => Number(e.headers['content-length'] ?? 0)));
    const sum = tiles.reduce((n, e) => n + Number(e.headers['content-length'] ?? 0), 0);
    test.info().annotations.push({ type: 'AC52 session', description: JSON.stringify({ tilesArchive: TILES_KIND, archiveBytes: ARCHIVE_BYTES, browserReads: tiles.length, serverReads: serverTiles.length, maxResponseBytes: maxLen, sumBytes: sum, scale }) });
    expect(tiles.length, 'basemap reads recorded').toBeGreaterThan(5);
    expect(problems, 'AC 52 browser side').toEqual([]);
    expect(serverTiles.length, 'server log saw the reads').toBeGreaterThan(5);
    expect(serverProblems, 'AC 52 server side: every archive read answered 206 to a Range request').toEqual([]);
    expect(backendOrForeign(net, HOST_A), 'AC 52: 0 requests to any other host').toEqual([]);
    expect(server.filter((l) => !STATIC_PATH.test(l.uri.split('?')[0]) || l.uri.includes('?')).map((l) => l.uri), 'server log: only static files, no query strings').toEqual([]);
  });

  for (const lang of ['mn', 'en']) {
    test(`AC53 (${lang}): a settled query shows «${T[lang].unavailable}» with «${T[lang].retry}» within 1 s, no spinner; retry and the coordinate card too; 0 search/reverse/route requests (browser and server log); map keeps working (NAV-003 AC 30, 33, 39)`, async ({ page, context }) => {
      const logBefore = accessLog().length;
      const net = recordNet(context);
      await openDemo(page, HOST_A, { lang });
      await netQuiet(net);
      const netBefore = net.length;
      const msg = T[lang].unavailable;
      // 1. Settled query >= 2 characters
      const query = lang === 'mn' ? 'Сүхбаатар' : 'Sukhbaatar';
      await typeSettled(page, query);
      const r1 = await unavailableAfterLastKey(page, msg);
      test.info().annotations.push({ type: `AC53 ${lang} query → unavailable (ms after the last key)`, description: JSON.stringify({ ms: r1.ms, loading: r1.loading, states: r1.states }) });
      expect(r1.ms, 'unavailable row with message and retry after the settled query').not.toBeNull();
      expect(r1.ms, 'AC 53: within 1 s').toBeLessThanOrEqual(1000);
      expect(r1.loading, 'no loading row / spinner (UX static demo state)').toBe(0);
      await expect(tid(page, 'search-retry')).toHaveText(T[lang].retry);
      await expect(page.locator('#search-state-text')).toHaveText(msg);
      // 2. «Дахин оролдох»
      await tid(page, 'search-retry').click();
      await page.waitForTimeout(1000);
      const r2 = await page.evaluate(() => {
        const sd = window.__sd;
        const c = sd.clicks.filter((x) => x.id === 'search-retry').at(-1);
        const after = sd.states.filter((s) => s.t >= c.t);
        const cur = sd.states.at(-1);
        return { clickAt: c.t, changes: after.map((s) => ({ ...s, t: Math.round(s.t - c.t) })), current: cur };
      });
      test.info().annotations.push({ type: `AC53 ${lang} retry`, description: JSON.stringify(r2) });
      expect(r2.current.row, 'after «Дахин оролдох»: still the unavailable row').toBe('unavailable');
      expect(r2.current.text).toBe(msg);
      expect(r2.current.retry).toBe(true);
      expect(r2.changes.filter((s) => s.row === 'loading' || s.popup === 'loading'), 'no loading row after retry').toEqual([]);
      expect(r2.changes.filter((s) => s.t <= 1000).every((s) => s.row === 'unavailable' || s.popup === 'hidden' || s.row === null), 'settles on unavailable within 1 s').toBe(true);
      // 3. Coordinate card (right-click): pin, coordinates, and the nearest-place area shows the message within 1 s
      await page.keyboard.press('Escape');
      await page.mouse.move(900, 420);
      await page.mouse.down({ button: 'right' });
      await page.mouse.up({ button: 'right' });
      await expect(tid(page, 'place-card')).toBeVisible();
      await page.waitForTimeout(1200);
      const r3 = await page.evaluate((msg) => {
        const sd = window.__sd;
        const c = sd.ctx.at(-1);
        const hit = sd.near.find((n) => n.t >= c && n.card && n.text?.includes(msg) && n.retry);
        return { ms: hit ? hit.t - c : null, near: sd.near.filter((n) => n.t >= c).map((n) => ({ ...n, t: Math.round(n.t - c) })) };
      }, msg);
      test.info().annotations.push({ type: `AC53 ${lang} coordinate card nearest place`, description: JSON.stringify(r3) });
      expect(r3.ms, 'nearest-place area shows the message with the retry').not.toBeNull();
      expect(r3.ms, 'AC 53: within 1 s of the right-click').toBeLessThanOrEqual(1000);
      await expect(tid(page, 'place-card-coords')).not.toBeEmpty();
      await expect(tid(page, 'place-card-title')).toHaveText(T[lang].selectedPoint);
      // Card retry: still unavailable, no request
      await tid(page, 'place-retry').click();
      await page.waitForTimeout(800);
      await expect(tid(page, 'place-nearest')).toContainText(msg);
      // 4. The map, attribution and other controls keep working (NAV-003 AC 39)
      await tid(page, 'place-card-close').click();
      const before = (await tid(page, 'scale-label').textContent())?.trim();
      await tid(page, 'zoom-in').click();
      await expect.poll(async () => (await tid(page, 'scale-label').textContent())?.trim(), { timeout: 3000 }).not.toBe(before);
      expect(await attributionProblems(page)).toEqual([]);
      // 5. Requests: 0 search / reverse / route requests to ANY host (page origin included), browser and server side
      await netQuiet(net);
      const during = net.slice(netBefore);
      expect(backendOrForeign(net, HOST_A), 'AC 53: 0 backend-path or other-host requests (browser)').toEqual([]);
      expect(during.filter((e) => !e.url.includes('/tiles/') && !e.url.startsWith('data:') && !e.url.startsWith('blob:')).map((e) => e.url), 'after start-up, only tile reads (zoom) go out').toEqual([]);
      const server = accessLog().slice(logBefore);
      const enc = [query, encodeURIComponent(query), 'q=', 'lat=', 'lon='];
      expect(server.filter((l) => !STATIC_PATH.test(l.uri.split('?')[0]) || l.uri.includes('?')).map((l) => l.uri), 'server log: only static files, no query strings').toEqual([]);
      expect(server.filter((l) => enc.some((s) => l.uri.includes(s) || (l.referer ?? '').includes(s))).map((l) => l.uri), 'no query text or coordinates in the web host log').toEqual([]);
    });
  }

  test('AC53 UX static demo state (screen spec › States › Static public demo): input stays enabled with the accessible description; 1 character sends nothing and shows no row; offline wins; D43 drag keeps the row, a map click closes it (NAV-003 AC 41)', async ({ page, context }) => {
    const net = recordNet(context);
    await openDemo(page, HOST_A);
    await netQuiet(net);
    const n0 = net.length;
    const input = tid(page, 'search-input');
    await expect(input, 'not the HTML disabled state').toBeEnabled();
    await expect(input).toBeEditable();
    await expect(input, 'design addition: accessible description «Хайлт түр ажиллахгүй байна»').toHaveAccessibleDescription(new RegExp(T.mn.unavailable));
    // One character: no settled query (AC 53 says >= 2), no row
    await input.click();
    await page.keyboard.type('С');
    await page.waitForTimeout(800);
    await expect(tid(page, 'search-state')).toBeHidden();
    // D43: drag keeps the unavailable row, a click closes it
    await typeSettled(page, 'Их дэлгүүр');
    await expect(tid(page, 'search-state')).toHaveAttribute('data-state', 'unavailable');
    await page.mouse.move(700, 450);
    await page.mouse.down();
    for (let i = 1; i <= 10; i++) await page.mouse.move(700 + 18 * i, 450 + 9 * i);
    await page.mouse.up();
    await page.waitForTimeout(500);
    await expect(tid(page, 'search-popup'), 'D43: the row stays open after a map drag').toBeVisible();
    await expect(tid(page, 'search-retry')).toBeVisible();
    await page.mouse.click(900, 400);
    await expect(tid(page, 'search-popup'), 'D43: a map click closes it').toBeHidden({ timeout: 1000 });
    // Offline wins over unavailable (screen spec; README state precedence)
    await context.setOffline(true);
    await typeSettled(page, 'Гандан');
    const offlineRow = await page.evaluate(() => ({ state: document.querySelector('#search-state')?.dataset.state, text: document.querySelector('#search-state-text')?.textContent }));
    test.info().annotations.push({ type: 'offline row', description: JSON.stringify(offlineRow) });
    expect(offlineRow.text, 'offline: «Интернэт холболт алга»').toBe(T.mn.offline);
    await context.setOffline(false);
    await netQuiet(net);
    expect(net.slice(n0).filter((e) => !e.url.includes('/tiles/')).map((e) => e.url), 'no request except tile reads').toEqual([]);
  });

  test('AC54: 5-minute session (3 queries, 1 coordinate card): search, reverse and routing stay off; 0 requests to any host but the page origin; the server log holds only static files', async ({ page, context }) => {
    test.setTimeout(420_000);
    const logBefore = accessLog().length;
    const net = recordNet(context);
    const t0 = Date.now();
    await openDemo(page, HOST_B);
    const at = async (sec) => {
      const wait = t0 + sec * 1000 - Date.now();
      if (wait > 0) await page.waitForTimeout(wait);
    };
    const seen = [];
    for (const [sec, q] of [[5, 'Сүхбаатарын талбай'], [95, 'Zaisan'], [185, 'Улсын их дэлгүүр']]) {
      await at(sec);
      await typeSettled(page, q);
      seen.push(await page.evaluate(() => document.querySelector('#search-state')?.dataset.state ?? null));
      await page.keyboard.press('Escape');
    }
    await at(240);
    await page.mouse.move(800, 380);
    await page.mouse.down({ button: 'right' });
    await page.mouse.up({ button: 'right' });
    await expect(tid(page, 'place-nearest')).toContainText(T.mn.unavailable, { timeout: 2000 });
    await at(300);
    await netQuiet(net);
    const elapsed = Math.round((Date.now() - t0) / 1000);
    const server = accessLog().slice(logBefore);
    test.info().annotations.push({ type: 'AC54 session', description: JSON.stringify({ seconds: elapsed, states: seen, browserRequests: net.length, serverLines: server.length, hosts: [...new Set(net.filter((e) => /^https?:/.test(e.url)).map((e) => new URL(e.url).origin))] }) });
    expect(elapsed).toBeGreaterThanOrEqual(300);
    expect(seen, 'every query ends in the unavailable row').toEqual(['unavailable', 'unavailable', 'unavailable']);
    expect(backendOrForeign(net, HOST_B), 'AC 54: 0 requests to a backend (staging or any other host)').toEqual([]);
    expect(server.filter((l) => !STATIC_PATH.test(l.uri.split('?')[0]) || l.uri.includes('?')).map((l) => l.uri), 'server log: only static files').toEqual([]);
  });

  test('AC51/AC53/AC54 build configuration: documented build setting, committed mode file, no hostnames (D35), static-demo build log line', () => {
    const pkg = JSON.parse(read(WEB + 'package.json'));
    expect(pkg.scripts['build:static-demo'], 'build command').toMatch(/vite build --mode static-demo/);
    const md = read(WEB + 'README.md');
    expect(md).toContain('`npm run build:static-demo`');
    expect(md).toContain('VITE_STATIC_DEMO');
    const envEx = read(WEB + '.env.example').split('\n');
    const i = envEx.findIndex((l) => l.startsWith('VITE_STATIC_DEMO='));
    expect(i, '.env.example has the VITE_STATIC_DEMO key').toBeGreaterThan(0);
    expect(envEx[i - 1]).toMatch(/^#\s*\S/);
    expect(envEx[i], 'normal builds stay with search on').toBe('VITE_STATIC_DEMO=false');
    const mode = read(WEB + '.env.static-demo');
    expect(mode).toMatch(/^VITE_STATIC_DEMO=true$/m);
    expect(mode).toMatch(/^VITE_GATEWAY_BASE_URL=same-origin$/m);
    // D35: committed config and the README's static-demo section name no real host (placeholders and localhost only)
    const sec = md.slice(md.indexOf('## Static public demo'), md.indexOf('\n## ', md.indexOf('## Static public demo') + 5));
    const hosts = [...`${mode}\n${envEx.join('\n')}\n${sec}`.matchAll(/https?:\/\/([^/\s`)'"*]+)/g)].map((m) => m[1]);
    const bad = hosts.filter((h) => !/^(<[a-z-]+>|localhost(:\d+)?|127\.0\.0\.1(:\d+)?)$/.test(h));
    test.info().annotations.push({ type: 'hosts named', description: JSON.stringify([...new Set(hosts)]) });
    expect(bad, 'no real hostname in the static-demo config or README section').toEqual([]);
    const log = read(process.env.NAV002_SD_BUILD_LOG);
    expect(log).toContain('static demo build: search, reverse and routing are off');
  });

  test('AC54 fail closed: an invalid VITE_STATIC_DEMO value fails the build', () => {
    test.setTimeout(180_000);
    const out = mkdtempSync(path.join(os.tmpdir(), 'nav002-sd-bad-'));
    try {
      let code = 0;
      let text = '';
      try {
        const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !k.startsWith('VITE_')));
        text = execFileSync('npx', ['vite', 'build', '--mode', 'static-demo', '--outDir', out, '--emptyOutDir'], { cwd: WEB, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], env: { ...env, VITE_STATIC_DEMO: 'yes', FORCE_COLOR: '0' }, timeout: 150_000 });
      } catch (e) {
        code = e.status ?? 1;
        text = `${e.stdout ?? ''}${e.stderr ?? ''}`;
      }
      test.info().annotations.push({ type: 'build with VITE_STATIC_DEMO=yes', description: `exit ${code}: ${text.split('\n').find((l) => l.includes('VITE_STATIC_DEMO')) ?? text.slice(-300)}` });
      expect(code, 'build refuses VITE_STATIC_DEMO=yes').not.toBe(0);
      expect(text).toContain('VITE_STATIC_DEMO');
    } finally {
      rmSync(out, { recursive: true, force: true });
    }
  });
});

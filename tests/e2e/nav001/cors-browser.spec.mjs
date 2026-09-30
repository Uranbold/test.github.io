// NAV-001 browser E2E: the gateway used cross-origin from a real Chromium page.
// Test plan: docs/qa/test-plans/NAV-001.md (E2E-01 .. E2E-07).
//
//   cd tests/e2e && npm ci && BASE_URL=http://localhost:8080 npx playwright test nav001
//   # AC 31 variant, with the gateway started with CORS_ALLOWED_ORIGINS=http://localhost:5173:
//   E2E_EXPECT_ALLOWLIST=http://localhost:5173 npx playwright test nav001
//
// Why a browser: curl cannot prove that a browser will expose Content-Range/ETag to JavaScript or
// accept a preflight. Here Chromium enforces CORS itself, and the real PMTiles JS client is used.
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect } from '@playwright/test';

// The fixture origin (http://localhost:5173, and http://127.0.0.1:5173 for E2E-06) is served by Playwright
// request interception from tests/e2e/, not by whatever process listens on port 5173. Reason: the NAV-002
// web demo's Vite dev server also uses 5173 and answers every path with its SPA index.html, which made
// `reuseExistingServer` load the wrong page (pmtiles not defined). Only same-origin fixture requests are
// intercepted; every request to the gateway goes over the real network and Chromium enforces CORS as usual.
const E2E_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FIXTURES = {
  '/nav001/page.html': 'text/html; charset=utf-8',
  '/node_modules/pmtiles/dist/pmtiles.js': 'text/javascript; charset=utf-8',
};

const GW = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const ALLOWLIST = process.env.E2E_EXPECT_ALLOWLIST || '';
const P1 = { lat: 47.9189, lon: 106.9176 };
const P2 = { lat: 47.9139, lon: 106.9044 };
// AC 32 out-of-coverage point. NAV-001 CR 2026-09-29: the default dev extract is all of Mongolia, so
// X1 Erdenet is routable and X2 Beijing is used (X1 only on the optional BBBike UB build).
const X2 = { lat: 39.9042, lon: 116.4074 };

function tileXY(lat, lon, z) {
  const n = 2 ** z;
  const x = Math.floor(((lon + 180) / 360) * n);
  const y = Math.floor(((1 - Math.asinh(Math.tan((lat * Math.PI) / 180)) / Math.PI) / 2) * n);
  return { x, y };
}

test.beforeEach(async ({ context, page }) => {
  // An intercepted document has no remote IP, so Chromium's Local Network Access check puts it in the
  // `unknown` address space and blocks its requests to localhost before CORS is even evaluated. A real
  // client served from localhost is in the local space and never hits that check, so grant it here. This
  // keeps CORS as the only thing that can block a request (E2E-06 must be blocked by CORS, not by LNA).
  for (const origin of ['http://localhost:5173', 'http://127.0.0.1:5173']) {
    await context.grantPermissions(['local-network-access'], { origin });
  }
  await context.route(/^http:\/\/(localhost|127\.0\.0\.1):5173\//, async (route) => {
    const { pathname } = new URL(route.request().url());
    const contentType = FIXTURES[pathname];
    if (!contentType) return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not a NAV-001 fixture' });
    return route.fulfill({ status: 200, contentType, body: await readFile(path.join(E2E_ROOT, pathname)) });
  });
  await page.goto('http://localhost:5173/nav001/page.html');
  expect(await page.evaluate(() => location.origin)).toBe('http://localhost:5173');
});

test('E2E-01 AC9/AC30: Range fetch of the PMTiles header; Content-Range, Content-Length, ETag readable by JS', async ({ page }) => {
  const r = await page.evaluate(async (gw) => {
    const res = await fetch(`${gw}/tiles/basemap.pmtiles`, { headers: { Range: 'bytes=0-126' } });
    const b = new Uint8Array(await res.arrayBuffer());
    return {
      status: res.status,
      contentRange: res.headers.get('Content-Range'),
      contentLength: res.headers.get('Content-Length'),
      etag: res.headers.get('ETag'),
      magic: String.fromCharCode(...b.slice(0, 7)),
      version: b[7],
      len: b.length,
    };
  }, GW);
  expect(r.status).toBe(206);
  expect(r.contentRange).toMatch(/^bytes 0-126\/\d+$/);
  expect(r.contentLength).toBe('127');
  expect(r.etag).toBeTruthy();
  expect(r.magic).toBe('PMTiles');
  expect(r.version).toBe(3);
});

test('E2E-02 AC10/AC11/AC30: the real PMTiles JS client reads header, metadata and the z14 tile at P1', async ({ page }) => {
  const { x, y } = tileXY(P1.lat, P1.lon, 14);
  const r = await page.evaluate(async ({ gw, x, y }) => {
    // eslint-disable-next-line no-undef
    const p = new pmtiles.PMTiles(`${gw}/tiles/basemap.pmtiles`);
    const h = await p.getHeader();
    const m = await p.getMetadata();
    const t = await p.getZxy(14, x, y);
    return {
      specVersion: h.specVersion, maxZoom: h.maxZoom, tileType: h.tileType,
      attribution: m.attribution, tileBytes: t ? t.data.byteLength : 0,
    };
  }, { gw: GW, x, y });
  expect(r.specVersion).toBe(3);
  expect(r.maxZoom).toBeGreaterThanOrEqual(14);
  expect(r.attribution).toContain('OpenStreetMap');
  expect(r.tileBytes).toBeGreaterThan(0);
});

test('E2E-03 AC29/AC13/AC16: POST /v1/route with JSON body (preflighted) returns Mongolian OSRM route', async ({ page }) => {
  const r = await page.evaluate(async ({ gw, a, b }) => {
    const res = await fetch(`${gw}/v1/route`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        locations: [a, b], costing: 'auto', format: 'osrm', banner_instructions: true,
        voice_instructions: true, language: 'mn-MN', units: 'kilometers',
      }),
    });
    const j = await res.json();
    const ann = j.routes[0].legs[0].steps.flatMap((s) => (s.voiceInstructions || []).map((v) => v.announcement)).join(' ');
    return { status: res.status, code: j.code, voiceLocale: j.routes[0].voiceLocale, ann };
  }, { gw: GW, a: P1, b: P2 });
  expect(r.status).toBe(200);
  expect(r.code).toBe('Ok');
  expect(r.voiceLocale).toBe('mn-MN');
  expect(r.ann).toMatch(/[өүӨҮ]/);
});

test('E2E-04 AC29/AC21/AC25: search and reverse readable cross-origin', async ({ page }) => {
  const r = await page.evaluate(async ({ gw, p }) => {
    const s = await fetch(`${gw}/v1/search?q=${encodeURIComponent('Сүхбаатар')}&lat=${p.lat}&lon=${p.lon}&limit=5&lang=mn`);
    const sj = await s.json();
    const v = await fetch(`${gw}/v1/reverse?lat=${p.lat}&lon=${p.lon}&lang=mn`);
    const vj = await v.json();
    return { s: s.status, sType: sj.type, sN: sj.features.length, v: v.status, vN: vj.features.length };
  }, { gw: GW, p: P1 });
  expect(r.s).toBe(200);
  expect(r.sType).toBe('FeatureCollection');
  expect(r.sN).toBeGreaterThanOrEqual(1);
  expect(r.v).toBe(200);
  expect(r.vN).toBeGreaterThanOrEqual(1);
});

test('E2E-05 AC32/AC33 + contract: error statuses and JSON bodies are readable by JS (CORS on errors)', async ({ page }) => {
  const r = await page.evaluate(async ({ gw, a, x }) => {
    const out = {};
    const noSeg = await fetch(`${gw}/v1/route`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ locations: [a, x], costing: 'auto', format: 'osrm', language: 'mn-MN' }),
    });
    out.noSeg = { status: noSeg.status, body: await noSeg.json() };
    const bad = await fetch(`${gw}/v1/route`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    out.bad = { status: bad.status, body: await bad.json() };
    const nf = await fetch(`${gw}/nope`);
    out.nf = { status: nf.status, body: await nf.json() };
    return out;
  }, { gw: GW, a: P1, x: X2 });
  expect(r.noSeg.status).toBeGreaterThanOrEqual(400);
  expect(r.noSeg.status).toBeLessThan(500);
  expect(r.noSeg.body.message || r.noSeg.body.error).toBeTruthy();
  expect(r.bad.status).toBe(400);
  expect(r.nf.status).toBe(404);
  expect(r.nf.body.code).toBe('NotFound');
});

test('E2E-07 AC43: a 416 on the tiles archive reaches JS as status 416 with a JSON body, not a CORS TypeError', async ({ page }) => {
  const r = await page.evaluate(async (gw) => {
    const url = `${gw}/tiles/basemap.pmtiles`;
    const head = await fetch(url, { method: 'HEAD' });
    const size = Number(head.headers.get('Content-Length')); // exposed by Access-Control-Expose-Headers
    const out = { size };
    for (const method of ['GET', 'HEAD']) {
      try {
        const res = await fetch(url, { method, headers: { Range: `bytes=${size}-` } });
        const text = method === 'GET' ? await res.text() : '';
        let body = null;
        try { body = text ? JSON.parse(text) : null; } catch { body = `not JSON: ${text.slice(0, 60)}`; }
        out[method] = {
          status: res.status, contentType: res.headers.get('Content-Type'),
          contentRange: res.headers.get('Content-Range'), body,
        };
      } catch (e) {
        out[method] = { error: `${e.name}: ${e.message}` }; // a duplicated ACAO lands here (pre-fix behaviour)
      }
    }
    return out;
  }, GW);
  expect(r.size).toBeGreaterThan(0);
  for (const method of ['GET', 'HEAD']) {
    expect(r[method].error, `${method} must not be rejected by CORS`).toBeUndefined();
    expect(r[method].status).toBe(416);
    expect(r[method].contentType).toMatch(/^application\/json/);
    expect(r[method].contentRange).toBe(`bytes */${r.size}`);
  }
  expect(r.GET.body).toMatchObject({ code: 'RangeNotSatisfiable' });
  expect(typeof r.GET.body.message).toBe('string');
});

test('E2E-06 AC31: a page on a non-allowlisted origin is blocked by the browser', async ({ page }) => {
  test.skip(!ALLOWLIST, 'set E2E_EXPECT_ALLOWLIST=http://localhost:5173 with the gateway started on that allowlist');
  // http://127.0.0.1:5173 is a different origin from http://localhost:5173.
  await page.goto('http://127.0.0.1:5173/nav001/page.html');
  const r = await page.evaluate(async (gw) => {
    const res = {};
    for (const [k, init] of [
      ['tiles', { headers: { Range: 'bytes=0-126' } }],
      ['tiles416', { headers: { Range: 'bytes=999999999999-' } }], // AC 43: the 416 also carries no ACAO for this origin
      ['route', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }],
    ]) {
      try {
        const x = await fetch(`${gw}/${k.startsWith('tiles') ? 'tiles/basemap.pmtiles' : 'v1/route'}`, init);
        res[k] = `readable ${x.status}`;
      } catch (e) {
        res[k] = `blocked ${e.name}`;
      }
    }
    return res;
  }, GW);
  expect(r.tiles).toMatch(/^blocked/);
  expect(r.tiles416).toMatch(/^blocked/);
  expect(r.route).toMatch(/^blocked/);
});

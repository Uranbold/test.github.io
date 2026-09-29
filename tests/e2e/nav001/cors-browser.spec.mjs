// NAV-001 browser E2E: the gateway used cross-origin from a real Chromium page.
// Test plan: docs/qa/test-plans/NAV-001.md (E2E-01 .. E2E-06).
//
//   cd tests/e2e && npm ci && BASE_URL=http://localhost:8080 npx playwright test nav001
//   # AC 31 variant, with the gateway started with CORS_ALLOWED_ORIGINS=http://localhost:5173:
//   E2E_EXPECT_ALLOWLIST=http://localhost:5173 npx playwright test nav001
//
// Why a browser: curl cannot prove that a browser will expose Content-Range/ETag to JavaScript or
// accept a preflight. Here Chromium enforces CORS itself, and the real PMTiles JS client is used.
import { test, expect } from '@playwright/test';

const GW = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const ALLOWLIST = process.env.E2E_EXPECT_ALLOWLIST || '';
const P1 = { lat: 47.9189, lon: 106.9176 };
const P2 = { lat: 47.9139, lon: 106.9044 };
const X1 = { lat: 49.027, lon: 104.044 };

function tileXY(lat, lon, z) {
  const n = 2 ** z;
  const x = Math.floor(((lon + 180) / 360) * n);
  const y = Math.floor(((1 - Math.asinh(Math.tan((lat * Math.PI) / 180)) / Math.PI) / 2) * n);
  return { x, y };
}

test.beforeEach(async ({ page }) => {
  await page.goto('/nav001/page.html');
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
  }, { gw: GW, a: P1, x: X1 });
  expect(r.noSeg.status).toBeGreaterThanOrEqual(400);
  expect(r.noSeg.status).toBeLessThan(500);
  expect(r.noSeg.body.message || r.noSeg.body.error).toBeTruthy();
  expect(r.bad.status).toBe(400);
  expect(r.nf.status).toBe(404);
  expect(r.nf.body.code).toBe('NotFound');
});

test('E2E-06 AC31: a page on a non-allowlisted origin is blocked by the browser', async ({ page }) => {
  test.skip(!ALLOWLIST, 'set E2E_EXPECT_ALLOWLIST=http://localhost:5173 with the gateway started on that allowlist');
  // http://127.0.0.1:5173 is a different origin from http://localhost:5173.
  await page.goto('http://127.0.0.1:5173/nav001/page.html');
  const r = await page.evaluate(async (gw) => {
    const res = {};
    for (const [k, init] of [
      ['tiles', { headers: { Range: 'bytes=0-126' } }],
      ['route', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }],
    ]) {
      try {
        const x = await fetch(`${gw}/${k === 'tiles' ? 'tiles/basemap.pmtiles' : 'v1/route'}`, init);
        res[k] = `readable ${x.status}`;
      } catch (e) {
        res[k] = `blocked ${e.name}`;
      }
    }
    return res;
  }, GW);
  expect(r.tiles).toMatch(/^blocked/);
  expect(r.route).toMatch(/^blocked/);
});

// Shared helpers for the SEC-4B browser checks. Story: docs/requirements/stories/SEC-4B-client-security-hardening.md.
// Test plan: docs/qa/test-plans/SEC-4B.md.
//
// Independent of web/buildtools/csp.ts: the policy is parsed and the inline-element hashes are recomputed here, with
// Chromium's own HTML parser (DOMParser) and WebCrypto, from the bytes vite preview serves. Oracles are the story text.
import { closeSync, openSync, readFileSync, readSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = fileURLToPath(new URL('../../../', import.meta.url));
export const TILES = ROOT + 'backend/data/tiles/basemap.pmtiles';
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const tid = (page, id) => page.locator(`[data-testid="${id}"]`);

/** UB point for the mocked geolocation (NAV-001 reference P1, Sükhbaatar Square). */
export const UB = { latitude: 47.9189, longitude: 106.9176, accuracy: 20 };

// Strings quoted in the stories (asserted literally; glossary terms).
export const S = {
  searchOff: 'Хайлт түр ажиллахгүй байна',
  routeOff: 'Маршрутын үйлчилгээ түр ажиллахгүй байна',
  offline: 'Интернэт холболт алга',
  getDirections: 'Маршрут гаргах',
  start: 'Эхлэх',
  end: 'Дуусгах',
  car: 'Машин',
  osm: '© OpenStreetMap contributors',
};

/** Every file below `dir`, as paths relative to it (symlinks reported as files, not followed). */
export function listFiles(dir) {
  const out = [];
  const walk = (d) => {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else out.push(path.relative(dir, p));
    }
  };
  walk(dir);
  return out.sort();
}

/** Parses a CSP string into Map(directive → sources[]), plus the list of repeated directive names. */
export function parsePolicy(policy) {
  const d = new Map();
  const repeated = [];
  for (const part of policy.split(';')) {
    const [name, ...src] = part.trim().split(/\s+/).filter(Boolean);
    if (!name) continue;
    if (d.has(name.toLowerCase())) repeated.push(name);
    else d.set(name.toLowerCase(), src);
  }
  return { d, repeated };
}

/**
 * In the browser: parse raw HTML with DOMParser and describe the head and every inline element, with the CSP hash
 * Chromium would compute (sha256 over the UTF-8 textContent, base64).
 */
export async function analyseHtml(page, html) {
  return page.evaluate(async (html) => {
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
    const hash = async (t) => `'sha256-${b64(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(t)))}'`;
    const head = [...doc.head.children].map((e) => ({
      tag: e.tagName.toLowerCase(),
      httpEquiv: e.getAttribute('http-equiv'),
      name: e.getAttribute('name'),
      charset: e.getAttribute('charset'),
      content: e.getAttribute('content'),
      src: e.getAttribute('src'),
      id: e.id || null,
    }));
    const inline = [];
    for (const e of doc.querySelectorAll('script:not([src]), style')) {
      inline.push({ tag: e.tagName.toLowerCase(), id: e.id || null, inHead: doc.head.contains(e), hash: await hash(e.textContent), start: e.textContent.slice(0, 60) });
    }
    const bad = { on: [], style: [], js: [] };
    for (const e of doc.querySelectorAll('*')) {
      for (const a of e.attributes) {
        if (/^on/i.test(a.name)) bad.on.push(`${e.tagName.toLowerCase()} ${a.name}`);
        if (a.name.toLowerCase() === 'style') bad.style.push(e.tagName.toLowerCase());
        if (/^\s*javascript:/i.test(a.value)) bad.js.push(`${e.tagName.toLowerCase()} ${a.name}`);
      }
    }
    return { head, inline, bad };
  }, html);
}

/**
 * Init script: records every securitypolicyviolation (document and workers' reports bubble to the document), the audio
 * and speech calls of the demo replay, and the banner text history. Injected by Playwright through CDP, so it is NOT
 * subject to the page's CSP (it is the test harness, not page content).
 */
export function recorderInit() {
  window.__cspv = [];
  document.addEventListener('securitypolicyviolation', (e) => {
    window.__cspv.push({ directive: e.violatedDirective, effective: e.effectiveDirective, blocked: e.blockedURI, source: e.sourceFile, line: e.lineNumber, sample: e.sample, t: performance.now() });
  }, true);
  window.__audio = { mediaPlay: [], nodeStart: 0, speak: 0 };
  const mp = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function (...a) {
    window.__audio.mediaPlay.push({ scheme: String(this.currentSrc || this.src).slice(0, 15), muted: this.muted, t: performance.now() });
    return mp.apply(this, a);
  };
  if (window.AudioScheduledSourceNode) {
    const st = AudioScheduledSourceNode.prototype.start;
    AudioScheduledSourceNode.prototype.start = function (...a) { window.__audio.nodeStart++; return st.apply(this, a); };
  }
  if (window.speechSynthesis) {
    const sp = speechSynthesis.speak.bind(speechSynthesis);
    speechSynthesis.speak = (u) => { window.__audio.speak++; return sp(u); };
  }
  window.__banners = [];
  new MutationObserver(() => {
    const t = document.querySelector('[data-testid="demo-nav-text"]')?.textContent?.trim();
    if (t && window.__banners.at(-1) !== t) window.__banners.push(t);
  }).observe(document, { subtree: true, childList: true, characterData: true });
}

/** Console messages that mention CSP (story AC 17: "Content Security Policy" or "Content-Security-Policy"). */
export function cspConsole(page) {
  const msgs = [];
  page.on('console', (m) => { if (/Content[ -]Security[ -]Policy/i.test(m.text())) msgs.push(`${m.type()}: ${m.text().slice(0, 300)}`); });
  page.on('pageerror', (e) => { if (/Content[ -]Security[ -]Policy/i.test(String(e))) msgs.push(`pageerror: ${String(e).slice(0, 300)}`); });
  return msgs;
}

export const violations = (page) => page.evaluate(() => window.__cspv);

/** AC 18 negative control: an unhashed inline script must not run and must give exactly one script-src(-elem) violation. */
export async function negativeControl(page) {
  const before = (await violations(page)).length;
  let addError = null;
  await page.addScriptTag({ content: 'window.__cspProbe = 1' }).catch((e) => { addError = String(e).split('\n')[0]; });
  await page.waitForTimeout(500);
  const probe = await page.evaluate(() => typeof window.__cspProbe === 'undefined' ? 'undefined' : window.__cspProbe);
  const added = (await violations(page)).slice(before);
  return { probe, added, addError };
}

// ------------------------------------------------------------------ B3 gateway mock (http://localhost:8080)
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS', 'Access-Control-Expose-Headers': 'Content-Range, Content-Length, ETag, Retry-After' };
const ROUTE_BODY = readFileSync(ROOT + 'mobile/android/app/src/test/resources/routes/p1-p3-car-mn.json', 'utf8'); // recorded Valhalla answer, P1 → P3 by car, mn-MN
const FEATURES = JSON.parse(readFileSync(ROOT + 'tests/e2e/nav003/fixtures/features.json', 'utf8')).fixtures.map((f) => f.feature);

function rangeSlice(range) {
  const size = statSync(TILES).size;
  const m = /bytes=(\d+)-(\d*)/.exec(range ?? '');
  const a = m ? Number(m[1]) : 0;
  const b = m && m[2] ? Math.min(Number(m[2]), size - 1) : size - 1;
  const buf = Buffer.alloc(b - a + 1);
  const fd = openSync(TILES, 'r');
  readSync(fd, buf, 0, buf.length, a);
  closeSync(fd);
  return { buf, a, b, size };
}

/** Mocks every gateway answer at `origin`. Returns a log of { method, path }. */
export async function mockGateway(context, origin = 'http://localhost:8080') {
  const log = [];
  await context.route(`${origin}/**`, async (route) => {
    const req = route.request();
    const u = new URL(req.url());
    log.push({ method: req.method(), path: u.pathname });
    if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS });
    if (u.pathname === '/tiles/basemap.pmtiles') {
      const { buf, a, b, size } = rangeSlice(req.headers()['range']);
      return route.fulfill({ status: 206, headers: { ...CORS, 'Content-Type': 'application/octet-stream', 'Content-Range': `bytes ${a}-${b}/${size}`, 'Accept-Ranges': 'bytes' }, body: buf });
    }
    const json = (body) => route.fulfill({ status: 200, headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8' }, body: typeof body === 'string' ? body : JSON.stringify(body) });
    if (u.pathname === '/v1/search') return json({ type: 'FeatureCollection', features: FEATURES.slice(0, 5) });
    if (u.pathname === '/v1/reverse') return json({ type: 'FeatureCollection', features: FEATURES.slice(0, 1) });
    if (u.pathname === '/v1/route') return json(ROUTE_BODY);
    if (u.pathname === '/health') return json({ status: 'ok' });
    return route.fulfill({ status: 404, headers: CORS, body: '' });
  });
  return log;
}

/** Attribution check (CLAUDE.md rule 8, story AC 34): exact text, visible, not covered. */
export async function attributionVisible(page) {
  return page.evaluate((expected) => {
    const e = document.querySelector('[data-testid="attribution-osm"]');
    if (!e) return 'missing';
    if (e.closest('[hidden]')) return 'hidden';
    const text = e.textContent.replace(/\s+/g, ' ').trim();
    if (text !== expected) return `text «${text}»`;
    const r = e.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return 'zero size';
    const cs = getComputedStyle(e);
    if (cs.visibility !== 'visible' || cs.display === 'none') return `not visible (${cs.visibility}/${cs.display})`;
    const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    if (top && !e.contains(top) && !top.contains(e)) return `covered by ${top.tagName.toLowerCase()}#${top.id}`;
    return 'ok';
  }, S.osm);
}

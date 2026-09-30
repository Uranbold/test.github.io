// Shared helpers for the NAV-002 E2E tests. Independent of web/ source code: everything is read
// through the DOM, the test hooks documented in web/README.md (data-testid, data-state, data-stale,
// data-reason, data-meters, window.__nav002 in dev builds) and the MapLibre public API.
import { expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export const ROOT = fileURLToPath(new URL('../../../', import.meta.url));
export const WEB = ROOT + 'web/';
export const APP = 'http://localhost:5173/';
export const GATEWAY = 'http://localhost:8080';
export const TILES_URL = `${GATEWAY}/tiles/basemap.pmtiles`;
export const TILES_GLOB = '**/tiles/basemap.pmtiles';

// NAV-001 reference points (lat, lng), story "Context".
export const P1 = { lat: 47.9189, lng: 106.9176 }; // Sükhbaatar Square
export const X2 = { lat: 39.9042, lng: 116.4074 }; // Beijing, outside coverage
// Empty countryside inside Mongolia (Gobi, south of Dalanzadgad, no settlement).
export const GOBI = { lat: 42.7, lng: 104.9 };

export const TOKENS = JSON.parse(readFileSync(ROOT + 'docs/design/tokens.json', 'utf8'));
// NAV002_MN_FILE: test-only override for negative controls of the AC 33 checks (default: the real web resource file).
export const MN = JSON.parse(readFileSync(process.env.NAV002_MN_FILE || WEB + 'src/i18n/mn.json', 'utf8'));
export const EN = JSON.parse(readFileSync(WEB + 'src/i18n/en.json', 'utf8'));

// Strings quoted in the story (G1–G7 and the existing glossary rows). Tests assert these literally,
// not the resource files, so a wrong resource value fails the test.
export const S = {
  mn: {
    map: 'Газрын зураг',
    recenter: 'Байршил руу буцах',
    myLocation: 'Миний байршил',
    zoomIn: 'Томруулах',
    zoomOut: 'Жижигрүүлэх',
    northUp: 'Хойд зүг дээшээ',
    dayMode: 'Өдрийн горим',
    nightMode: 'Шөнийн горим',
    offline: 'Интернэт холболт алга',
    retry: 'Дахин оролдох',
    loading: 'Ачаалж байна…',
    tiles: 'Газрын зургийг ачаалж чадсангүй',
    denied: 'Байршлын зөвшөөрөл олгоогүй байна',
    deniedHint: 'Хөтчийн тохиргоонд байршлын зөвшөөрлийг асаана уу',
    unavailable: 'Байршил тодорхойлж чадсангүй',
    close: 'Хаах',
    m: 'м',
    km: 'км',
    langButton: 'English',
  },
  en: {
    map: 'Map',
    recenter: 'Recenter',
    myLocation: 'My location',
    zoomIn: 'Zoom in',
    zoomOut: 'Zoom out',
    northUp: 'North up',
    dayMode: 'Day mode',
    nightMode: 'Night mode',
    offline: 'No internet connection',
    retry: 'Try again',
    loading: 'Loading…',
    tiles: 'The map could not be loaded',
    denied: 'Location permission is turned off',
    deniedHint: 'Allow location access in your browser settings',
    unavailable: 'Your location could not be determined',
    close: 'Close',
    m: 'm',
    km: 'km',
    langButton: 'Монгол',
  },
  osm: '© OpenStreetMap contributors',
  esa: '© ESA WorldCover project / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium',
  osmHref: 'https://www.openstreetmap.org/copyright',
};

export const LABEL_EXPRESSION = ['coalesce', ['get', 'name:mn'], ['get', 'name'], ['get', 'name:en']];
export const MN_LETTER = /[өүӨҮ]/;
export const CYRILLIC = /[Ѐ-ӿ]/;

export const tid = (page, id) => page.locator(`[data-testid="${id}"]`);

/** Great-circle distance in metres (independent implementation; mean Earth radius). */
export function haversine(a, b) {
  const R = 6371008.8;
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Point `meters` north of p. */
export const north = (p, meters) => ({ lat: p.lat + meters / 111_195, lng: p.lng });

/**
 * Init script: records a timeline of UI state changes with performance.now() (ms since navigation start),
 * so timing ACs (37, 38, 41, 43, 44) are measured in the page, not by polling from Node.
 */
export function timelineInit() {
  window.__t = [];
  let last = '';
  const snap = () => {
    const q = (s) => document.querySelector(s);
    const vis = (s) => {
      const e = q(s);
      return !!e && !e.hidden && !e.closest('[hidden]');
    };
    const loading = q('#loading');
    const st = {
      loading: !!loading && !loading.classList.contains('idle'),
      card: vis('#card') ? q('#card').dataset.reason ?? 'unknown' : null,
      banner: vis('#status-banner') ? q('#status-banner').dataset.reason ?? 'unknown' : null,
      ready: vis('#bottom'),
      locmsg: vis('#location-message') ? q('#location-message').dataset.kind ?? 'unknown' : null,
      loc: q('#locate-btn')?.dataset.state ?? null,
      stale: q('[data-testid="location-marker"]')?.dataset.stale ?? null,
    };
    const key = JSON.stringify(st);
    if (key !== last) {
      last = key;
      window.__t.push({ t: performance.now(), ...st });
    }
  };
  new MutationObserver(snap).observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['hidden', 'class', 'data-state', 'data-stale', 'data-reason', 'data-kind'] });
}

/** First timeline entry (ms since navigation start) at or after `after` matching pred, or null. */
export async function firstTime(page, predSrc, after = 0) {
  return page.evaluate(
    ({ predSrc, after }) => {
      const pred = new Function('s', `return (${predSrc});`);
      const e = (window.__t || []).find((s) => s.t >= after && pred(s));
      return e ? e.t : null;
    },
    { predSrc, after },
  );
}

export const now = (page) => page.evaluate(() => performance.now());

/** Opens the app with optional saved preferences; installs the timeline recorder. */
export async function openApp(page, { url = APP, theme, lang, wait = true } = {}) {
  await page.addInitScript(timelineInit);
  if (theme || lang) {
    await page.addInitScript(
      ({ theme, lang }) => {
        try {
          if (theme) localStorage.setItem('navmn.theme', theme);
          if (lang) localStorage.setItem('navmn.lang', lang);
        } catch {}
      },
      { theme, lang },
    );
  }
  await page.goto(url);
  if (wait) await waitReady(page);
}

/** Waits until the map has rendered its first tiles (bottom controls shown) and MapLibre is idle. */
export async function waitReady(page, timeout = 20_000) {
  await expect(tid(page, 'zoom-in')).toBeVisible({ timeout });
  await waitIdle(page);
}

export async function waitIdle(page) {
  await page.waitForFunction(() => !!window.__nav002?.map, null, { timeout: 15_000 });
  await page.evaluate(
    () =>
      new Promise((resolve) => {
        const m = window.__nav002.map;
        const done = () => resolve();
        if (m.loaded() && !m.isMoving() && m.areTilesLoaded()) return done();
        m.once('idle', done);
        m.triggerRepaint();
        setTimeout(done, 15_000);
      }),
  );
}

/** jumpTo and wait for idle. */
export async function jump(page, opts) {
  await page.evaluate(
    (o) =>
      new Promise((resolve) => {
        const m = window.__nav002.map;
        const c = o.center ? [o.center.lng, o.center.lat] : undefined;
        m.once('idle', () => resolve());
        m.jumpTo({ ...o, center: c });
        m.triggerRepaint();
        setTimeout(resolve, 15_000);
      }),
    opts,
  );
}

export const camera = (page) =>
  page.evaluate(() => {
    const m = window.__nav002.map;
    const c = m.getCenter();
    return { lat: c.lat, lng: c.lng, zoom: m.getZoom(), bearing: m.getBearing(), pitch: m.getPitch() };
  });

/** Rendered labels of the basemap (symbol layers of the protomaps source), with the text the label rule resolves. */
export const renderedLabels = (page) =>
  page.evaluate(() => {
    const m = window.__nav002.map;
    const ids = m.getStyle().layers.filter((l) => l.type === 'symbol' && l.source === 'protomaps').map((l) => l.id);
    return m.queryRenderedFeatures({ layers: ids }).map((f) => {
      const p = f.properties;
      const text = p['name:mn'] ?? p.name ?? p['name:en'] ?? p.addr_housenumber ?? p.shield_text ?? null;
      return { layer: f.layer.id, text };
    });
  });

export const renderedRoadCount = (page) =>
  page.evaluate(() => {
    const m = window.__nav002.map;
    const ids = m.getStyle().layers.filter((l) => l.type === 'line' && l.source === 'protomaps' && l.id.startsWith('roads_')).map((l) => l.id);
    return m.queryRenderedFeatures({ layers: ids }).length;
  });

/** Collects uncaught page errors and console errors (filtering Chromium's SwiftShader/GPU noise). */
export function collectErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    const t = m.text();
    if (/GL Driver|WebGL|SwiftShader|GroupMarkerNotSet/.test(t)) return;
    // Failed resource loads caused on purpose by a test (aborted/404 tile requests) show up as console errors.
    if (/Failed to load resource|net::ERR_/.test(t)) return;
    errors.push(`console.error: ${t}`);
  });
  return errors;
}

/** Visible-message helpers */
export const statusView = (page) =>
  page.evaluate(() => {
    const vis = (s) => {
      const e = document.querySelector(s);
      return !!e && !e.hidden && getComputedStyle(e).display !== 'none' && getComputedStyle(e).visibility !== 'hidden';
    };
    return {
      loading: !document.querySelector('#loading').classList.contains('idle') && vis('#loading'),
      card: vis('#card') ? document.querySelector('#card').dataset.reason : null,
      banner: vis('#status-banner') ? document.querySelector('#status-banner').dataset.reason : null,
    };
  });

/**
 * Attribution visibility per AC 34: fully inside the viewport, not covered (hit-test at a grid of points),
 * not collapsed, font ≥ 11 CSS px, correct link. Returns a list of problems (empty = pass).
 */
export async function attributionProblems(page, testid = 'attribution-osm', expectedText = S.osm) {
  return page.evaluate(
    ({ testid, expectedText }) => {
      const probs = [];
      const e = document.querySelector(`[data-testid="${testid}"]`);
      if (!e) return [`${testid} missing`];
      if (e.hidden || e.closest('[hidden]')) return [`${testid} hidden`];
      const text = e.textContent.replace(/\s+/g, ' ').trim();
      if (text !== expectedText) probs.push(`${testid} text «${text}»`);
      const cs = getComputedStyle(e);
      if (cs.visibility !== 'visible' || cs.display === 'none' || Number(cs.opacity) < 1) probs.push(`${testid} not visible (${cs.visibility}/${cs.display}/${cs.opacity})`);
      const fs = parseFloat(cs.fontSize);
      if (!(fs >= 11)) probs.push(`${testid} font ${fs}px < 11`);
      const vw = document.documentElement.clientWidth;
      const vh = document.documentElement.clientHeight;
      // Use the text's own line boxes (a <p> block can be wider than its text).
      const range = document.createRange();
      range.selectNodeContents(e);
      const rects = [...range.getClientRects()].filter((r) => r.width > 0 && r.height > 0);
      if (!rects.length) probs.push(`${testid} has no rendered text`);
      for (const r of rects) {
        if (r.left < -0.5 || r.top < -0.5 || r.right > vw + 0.5 || r.bottom > vh + 0.5) probs.push(`${testid} outside viewport ${JSON.stringify([r.left, r.top, r.right, r.bottom])} vw=${vw} vh=${vh}`);
        for (const fx of [0.02, 0.25, 0.5, 0.75, 0.98]) {
          for (const fy of [0.3, 0.7]) {
            const x = r.left + r.width * fx;
            const y = r.top + r.height * fy;
            const hit = document.elementFromPoint(x, y);
            if (!hit || !(hit === e || e.contains(hit) || hit.contains(e))) {
              probs.push(`${testid} covered at (${x.toFixed(0)},${y.toFixed(0)}) by ${hit ? hit.tagName + '#' + hit.id + '.' + hit.className : 'nothing'}`);
            }
          }
        }
      }
      if (testid === 'attribution-osm') {
        if (e.tagName !== 'A') probs.push('attribution is not a link');
        if (e.getAttribute('href') !== 'https://www.openstreetmap.org/copyright') probs.push(`href ${e.getAttribute('href')}`);
        if (e.getAttribute('target') !== '_blank') probs.push(`target ${e.getAttribute('target')}`);
      }
      // "Not collapsed behind an info button": MapLibre's compact attribution must not be used.
      if (document.querySelector('.maplibregl-ctrl-attrib-button, .maplibregl-compact')) probs.push('MapLibre compact attribution present');
      return [...new Set(probs)];
    },
    { testid, expectedText },
  );
}

/** Colour helpers */
export function normColor(c) {
  if (typeof c !== 'string') return JSON.stringify(c);
  const s = c.trim().toLowerCase();
  const m = s.match(/^rgba?\(\s*(\d+)[ ,]+(\d+)[ ,]+(\d+)(?:[ ,/]+([\d.]+))?\s*\)$/);
  if (m) {
    const hex = '#' + [m[1], m[2], m[3]].map((n) => Number(n).toString(16).padStart(2, '0')).join('');
    return m[4] !== undefined && Number(m[4]) !== 1 ? `${hex}@${Number(m[4])}` : hex;
  }
  if (/^#[0-9a-f]{3}$/.test(s)) return '#' + [...s.slice(1)].map((x) => x + x).join('');
  return s;
}

export const token = (mode, path) => {
  let n = TOKENS.color[mode];
  for (const k of path.split('.')) n = n?.[k];
  return n?.$value ?? n;
};

/** Stub for navigator.geolocation that behaves like a browser that never gets a fix: it fires TIMEOUT
 *  after options.timeout (as a real browser does) and records every call. Used for AC 22 (timeout). */
export function geoTimeoutStubInit() {
  const calls = [];
  window.__geoCalls = calls;
  const stub = {
    getCurrentPosition(ok, err, opts) {
      calls.push({ fn: 'getCurrentPosition', opts });
      if (opts && Number.isFinite(opts.timeout)) setTimeout(() => err?.({ code: 3, message: 'Timeout expired', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 }), opts.timeout);
    },
    watchPosition(ok, err, opts) {
      calls.push({ fn: 'watchPosition', opts });
      const id = calls.length;
      if (opts && Number.isFinite(opts.timeout)) setTimeout(() => err?.({ code: 3, message: 'Timeout expired', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 }), opts.timeout);
      return id;
    },
    clearWatch(id) {
      calls.push({ fn: 'clearWatch', id });
    },
  };
  Object.defineProperty(Navigator.prototype, 'geolocation', { configurable: true, get: () => stub });
}

/** Counts every Geolocation and Permissions API call (AC 18) without changing behaviour. */
export function geoSpyInit() {
  window.__geoSpy = [];
  const wrap = (obj, name, label) => {
    if (!obj || typeof obj[name] !== 'function') return;
    const orig = obj[name];
    obj[name] = function (...args) {
      window.__geoSpy.push(label);
      return orig.apply(this, args);
    };
  };
  const g = navigator.geolocation;
  if (g) {
    const proto = Object.getPrototypeOf(g);
    wrap(proto, 'getCurrentPosition', 'geolocation.getCurrentPosition');
    wrap(proto, 'watchPosition', 'geolocation.watchPosition');
  }
  if (navigator.permissions) wrap(Object.getPrototypeOf(navigator.permissions), 'query', 'permissions.query');
}

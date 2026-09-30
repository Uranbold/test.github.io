// Shared helpers for the NAV-003 E2E tests. Independent of web/ source code: everything is read through the DOM,
// the test hooks documented in web/README.md › Test hooks (data-testid, data-state, data-kind, data-type,
// window.__nav002.map, window.__nav003 in dev builds) and the MapLibre public API.
// Reuses (imports, never modifies) the NAV-002 helpers.
import { expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { openApp as openApp002, tid, haversine, waitIdle } from '../nav002/helpers.mjs';

export { tid, haversine, waitIdle };
export { attributionProblems, collectErrors, S as S002, TILES_GLOB, LABEL_EXPRESSION, geoSpyInit, ROOT, WEB } from '../nav002/helpers.mjs';

const HERE = fileURLToPath(new URL('./', import.meta.url));
export const FIXTURES = JSON.parse(readFileSync(HERE + 'fixtures/features.json', 'utf8')).fixtures;
export const GOLDEN = JSON.parse(readFileSync(HERE + 'fixtures/golden-set.json', 'utf8'));
export const FX = Object.fromEntries(FIXTURES.map((f) => [f.id, f]));

// Reference points (NAV-001 "Reference test locations", story Context). {lat, lng}
export const REF = {
  P1: { lat: 47.9189, lng: 106.9176 },
  P2: { lat: 47.9139, lng: 106.9044 },
  P3: { lat: 47.8858, lng: 106.9173 },
  P4: { lat: 47.9215, lng: 106.895 },
  P5: { lat: 47.9095, lng: 106.8835 },
  X1: { lat: 49.027, lng: 104.044 },
  X2: { lat: 39.9042, lng: 116.4074 },
};

// Story strings, asserted literally (story "User-facing strings" and "Type labels"), not read from resource files.
export const T = {
  mn: {
    search: 'Хайх',
    placeholder: 'Газар, хаяг хайх',
    clear: 'Хайлтыг арилгах',
    results: 'Хайлтын илэрц',
    count: (n) => `${n} илэрц олдлоо`,
    noResults: 'Илэрц олдсонгүй',
    unavailable: 'Хайлт түр ажиллахгүй байна',
    rateLimited: 'Түр хүлээгээд дахин оролдоно уу',
    selectedPoint: 'Сонгосон цэг',
    nearest: 'Ойролцоох газар',
    loading: 'Ачаалж байна…',
    offline: 'Интернэт холболт алга',
    retry: 'Дахин оролдох',
    close: 'Хаах',
    error: 'Алдаа гарлаа',
  },
  en: {
    search: 'Search',
    placeholder: 'Search for a place or address',
    clear: 'Clear search',
    results: 'Search results',
    count: (n) => (n === 1 ? '1 result' : `${n} results`),
    noResults: 'No results found',
    unavailable: 'Search is temporarily unavailable',
    rateLimited: 'Too many searches. Wait a moment and try again',
    selectedPoint: 'Selected point',
    nearest: 'Nearest place',
    loading: 'Loading…',
    offline: 'No internet connection',
    retry: 'Try again',
    close: 'Close',
    error: 'Something went wrong',
  },
};

/** Type labels, story section D (order = rule number). Rule 4a (amended 2026-09-30, F2) reuses row 1's label «Дүүрэг» / "District". */
export const TYPE_LABELS = [
  [1, 'Дүүрэг', 'District'], [2, 'Хороо', 'Khoroo'], [3, 'Аймаг', 'Aimag'], [4, 'Сум', 'Soum'], [5, 'Хот', 'City or town'],
  [6, 'Суурин', 'Settlement'], [7, 'Хороолол', 'Neighbourhood'], [8, 'Талбай', 'Square'], [9, 'ШТС', 'Petrol station'],
  [10, 'Эмнэлэг', 'Hospital or clinic'], [11, 'Эмийн сан', 'Pharmacy'], [12, 'Сургууль', 'School'], [13, 'Их сургууль', 'University or college'],
  [14, 'Хоолны газар', 'Restaurant or café'], [15, 'Зочид буудал', 'Hotel'], [16, 'Худалдааны төв', 'Shopping centre'], [17, 'Дэлгүүр', 'Shop'],
  [18, 'Зах', 'Market'], [19, 'Банк', 'Bank or ATM'], [20, 'Автобусны буудал', 'Bus stop'], [21, 'Галт тэрэгний буудал', 'Railway station'],
  [22, 'Нисэх онгоцны буудал', 'Airport'], [23, 'Зогсоол', 'Parking'], [24, 'Музей', 'Museum'], [25, 'Дурсгалт газар', 'Monument or historic site'],
  [26, 'Сүм хийд', 'Place of worship'], [27, 'Цэцэрлэгт хүрээлэн', 'Park'], [28, 'Төрийн байгууллага', 'Government office'], [29, 'Элчин сайдын яам', 'Embassy'],
  [30, 'Зам', 'Road'], [31, 'Хаяг', 'Address'], [32, 'Газар', 'Place'],
];
export const AREA_LABELS_MN = ['Хот', 'Суурин', 'Дүүрэг', 'Хороо', 'Хороолол', 'Аймаг', 'Сум']; // AC 20: zoom 13

/** ADR-0006 §2.3 abbreviation table (story AC 16). */
export const ABBR = { СБД: 'Сүхбаатар дүүрэг', БЗД: 'Баянзүрх дүүрэг', ХУД: 'Хан-Уул дүүрэг', БГД: 'Баянгол дүүрэг', ЧД: 'Чингэлтэй дүүрэг', СХД: 'Сонгинохайрхан дүүрэг' };

/**
 * The request plan the story expects for a settled text query (AC 3, 15, 16 as amended 2026-09-30, F3 = ADR-0006 §2.3
 * rules A–D). Derived by QA from the story wording, not from web/ code. The transliteration itself is the implementer's
 * choice, so rule B returns `secondary: 'cyrillic'` instead of a string.
 */
export function expectedPlan(q) {
  const tokens = q.split(' ');
  if (tokens.some((w) => ABBR[w.toUpperCase()])) {
    const expanded = tokens.map((w) => ABBR[w.toUpperCase()] ?? w).join(' ');
    return { rule: 'A', mode: 'parallel', primary: expanded, secondary: q };
  }
  const letters = [...q.matchAll(/\p{L}/gu)].map((m) => m[0]);
  if (letters.length >= 2 && letters.every((c) => /\p{Script=Latin}/u.test(c))) return { rule: 'B', mode: 'parallel', primary: q, secondary: 'cyrillic' };
  if (letters.length && letters.every((c) => /\p{Script=Cyrillic}/u.test(c)) && /[уУоО]/.test(q)) {
    const v = q.replace(/у/g, 'ү').replace(/У/g, 'Ү').replace(/о/g, 'ө').replace(/О/g, 'Ө');
    return { rule: 'C', mode: 'ifEmpty', primary: q, secondary: v };
  }
  return { rule: 'D', mode: 'none', primary: q };
}

export const TRAD = /[᠀-᢯]/;
export const CORS = { 'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/json; charset=utf-8' };
export const SEARCH_GLOB = '**/v1/search?**';
export const REVERSE_GLOB = '**/v1/reverse?**';

/**
 * In-page recorder: every gateway search/reverse fetch (start and settle times), every input event of the search
 * field, and a timeline of the search popup / live region / place card (performance.now(), ms since navigation start).
 */
export function nav003Init() {
  window.__req = [];
  window.__inputs = [];
  window.__st = [];
  const of = window.fetch;
  window.fetch = function (input, init) {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const m = /\/v1\/(search|reverse)\?/.exec(url);
    if (!m) return of.apply(this, arguments);
    const rec = { op: m[1], url, t: performance.now(), end: null, outcome: null, count: null };
    window.__req.push(rec);
    const p = of.apply(this, arguments);
    p.then(
      (r) => {
        rec.end = performance.now();
        rec.outcome = r.status;
        // Number of features in a 200 response (ADR-0006 rule C "ifEmpty" check); read from a clone, never the original.
        if (r.status === 200) r.clone().json().then((j) => (rec.count = Array.isArray(j?.features) ? j.features.length : null), () => {});
      },
      (e) => ((rec.end = performance.now()), (rec.outcome = String(e && e.name))),
    );
    return p;
  };
  document.addEventListener(
    'input',
    (e) => {
      if (e.target && e.target.id === 'search-input') window.__inputs.push({ t: performance.now(), v: e.target.value });
    },
    true,
  );
  let last = '';
  const snap = () => {
    const q = (s) => document.querySelector(s);
    const popup = q('#search-popup');
    const list = q('#search-results');
    const row = q('#search-state');
    const card = q('#place');
    const near = q('#place-nearest');
    const ctl = window.__nav003?.search?.controller?.view;
    const st = {
      popup: popup ? (popup.hidden ? 'hidden' : popup.dataset.state ?? '?') : null,
      n: list ? list.querySelectorAll('[role=option]').length : 0,
      first: list?.querySelector('[data-testid=search-option-name]')?.textContent ?? null,
      row: row && !row.hidden ? row.dataset.state ?? '?' : null,
      rowText: row && !row.hidden ? q('#search-state-text')?.textContent ?? '' : null,
      busy: popup?.getAttribute('aria-busy') ?? null,
      live: q('#search-live')?.textContent ?? '',
      card: card && !card.hidden ? q('#place-card')?.dataset.kind ?? '?' : null,
      near: near && !near.hidden ? near.dataset.state ?? null : null,
      query: ctl ? ctl.query : null,
    };
    const key = JSON.stringify(st);
    if (key !== last) {
      last = key;
      window.__st.push({ t: performance.now(), ...st });
    }
  };
  new MutationObserver(snap).observe(document, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['hidden', 'data-state', 'aria-busy', 'data-kind'] });
}

/** Opens the app (NAV-002 openApp + NAV-003 recorder). */
export async function openApp(page, opts = {}) {
  await page.addInitScript(nav003Init);
  await openApp002(page, opts);
  await page.waitForFunction(() => !!window.__nav003?.search?.controller, null, { timeout: 15_000 });
}

export const requests = (page, op) => page.evaluate((op) => window.__req.filter((r) => !op || r.op === op), op);
export const requestCount = async (page, op) => (await requests(page, op)).length;
export const params = (url) => Object.fromEntries(new URL(url).searchParams);
export const timeline = (page) => page.evaluate(() => window.__st);
export const lastInputAt = (page) => page.evaluate(() => window.__inputs.at(-1)?.t ?? null);
export const pnow = (page) => page.evaluate(() => performance.now());
export const view = (page) =>
  page.evaluate(() => {
    const v = window.__nav003.search.controller.view;
    return { state: v.state, query: v.query, retry: v.retry, busy: v.busy, options: v.options.map((o) => (o.kind === 'place' ? { kind: o.kind, props: o.feature.properties, coords: o.feature.geometry.coordinates } : { kind: o.kind, point: o.point })) };
  });

/** Focuses the search field and types `text` (keyboard events, `delay` ms per key). Clears it first. */
export async function typeQuery(page, text, { delay = 0, clear = true } = {}) {
  const input = tid(page, 'search-input');
  await input.focus();
  if (clear && (await input.inputValue()) !== '') {
    await page.keyboard.press('ControlOrMeta+A');
    await page.keyboard.press('Backspace');
  }
  await page.keyboard.type(text, { delay });
}

/** Waits until the popup shows a final state (results, coordinate, no-results or a message) for the normalised query. */
export async function waitSettled(page, query, timeout = 10_000) {
  const q = query.normalize('NFC').replace(/\s+/g, ' ').trim();
  await page.waitForFunction(
    (q) => {
      const v = window.__nav003.search.controller.view;
      return v.query === q && !['closed', 'loading'].includes(v.state);
    },
    q,
    { timeout },
  );
}

/** Visible options: name, type, context, kind, data-type. */
export const options = (page) =>
  page.evaluate(() =>
    [...document.querySelectorAll('#search-results [role=option]')].map((li) => ({
      id: li.id,
      kind: li.dataset.kind,
      dataType: li.dataset.type ?? null,
      name: li.querySelector('[data-testid=search-option-name]')?.textContent ?? null,
      type: li.querySelector('[data-testid=search-option-type]')?.textContent ?? null,
      context: li.querySelector('[data-testid=search-option-context]')?.textContent ?? null,
      text: li.textContent,
      selected: li.getAttribute('aria-selected'),
    })),
  );

/** A Photon FeatureCollection body. */
export const fc = (features) => JSON.stringify({ type: 'FeatureCollection', features });

/** A simple fixture feature. */
export function feature(name, lon, lat, extra = {}, id = Math.floor(Math.random() * 1e9)) {
  return { type: 'Feature', geometry: { type: 'Point', coordinates: [lon, lat] }, properties: { osm_type: 'N', osm_id: id, osm_key: 'amenity', osm_value: 'school', countrycode: 'MN', name, city: 'Улаанбаатар', ...extra } };
}

/**
 * Mocks /v1/search (and optionally /v1/reverse) by route interception. `handler(p, route, n)` receives the query
 * params and the call index and returns { status, body, headers, delay, abort, hang } or 'continue' (live).
 * Returns the call log (Node side).
 */
export async function mock(page, glob, handler) {
  const log = [];
  await page.route(glob, async (route) => {
    const url = route.request().url();
    const p = params(url);
    const n = log.length;
    log.push({ t: Date.now(), p, url });
    const r = (await handler(p, route, n)) ?? {};
    if (r === 'continue') return route.continue();
    if (r.delay) await new Promise((res) => setTimeout(res, r.delay));
    if (r.hang) return; // never answered (client timeout)
    if (r.abort) return route.abort(r.abort === true ? 'failed' : r.abort).catch(() => {});
    await route
      .fulfill({ status: r.status ?? 200, headers: r.headers ?? CORS, body: r.body ?? fc([]) })
      .catch(() => {}); // the client may have aborted it
  });
  return log;
}

/** Pacing for live tests: keeps the run at <= 2 requests per second (story Test approach). */
export async function pace(page, nRequests = 1) {
  await page.waitForTimeout(Math.max(600, nRequests * 550));
}

/** Map helpers through the dev hook. */
export async function jumpTo(page, center, zoom) {
  await page.evaluate(
    ({ center, zoom }) =>
      new Promise((resolve) => {
        const m = window.__nav002.map;
        m.once('idle', () => resolve());
        m.jumpTo({ center: [center.lng, center.lat], ...(zoom ? { zoom } : {}) });
        m.triggerRepaint();
        setTimeout(resolve, 10_000);
      }),
    { center, zoom },
  );
}

export const camera = (page) =>
  page.evaluate(() => {
    const m = window.__nav002.map;
    const c = m.getCenter();
    const cv = m.getCanvas();
    return { lat: c.lat, lng: c.lng, zoom: m.getZoom(), moving: m.isMoving(), w: cv.clientWidth, h: cv.clientHeight, padding: m.getPadding() };
  });

/** Screen position (CSS px, viewport) of a lng/lat and the map viewport centre. */
export const project = (page, lng, lat) =>
  page.evaluate(
    ({ lng, lat }) => {
      const m = window.__nav002.map;
      const p = m.project([lng, lat]);
      const r = m.getCanvas().getBoundingClientRect();
      return { x: r.left + p.x, y: r.top + p.y, cx: r.left + r.width / 2, cy: r.top + r.height / 2, vw: innerWidth, vh: innerHeight };
    },
    { lng, lat },
  );

/** Pins currently on the map and the anchor point (bottom centre) of each. */
export const pins = (page) =>
  page.evaluate(() =>
    [...document.querySelectorAll('[data-testid=place-pin]')]
      .filter((e) => e.isConnected && !e.closest('[hidden]'))
      .map((e) => {
        const r = e.getBoundingClientRect();
        return { x: r.left + r.width / 2, y: r.bottom, role: e.getAttribute('role'), label: e.getAttribute('aria-label'), w: r.width, h: r.height };
      }),
  );

export async function card(page) {
  return page.evaluate(() => {
    const vis = (e) => !!e && !e.hidden && !e.closest('[hidden]');
    const q = (s) => document.querySelector(s);
    const c = q('[data-testid=place-card]');
    const near = q('[data-testid=place-nearest]');
    return {
      open: vis(c),
      kind: c?.dataset.kind ?? null,
      title: q('[data-testid=place-card-title]')?.textContent ?? null,
      titleTag: q('[data-testid=place-card-title]')?.tagName ?? null,
      meta: vis(q('[data-testid=place-card-meta]')) ? q('[data-testid=place-card-meta]').textContent : null,
      type: vis(q('#place-card-type')) ? q('#place-card-type').textContent : null,
      context: vis(q('#place-card-context')) ? q('#place-card-context').textContent : null,
      coords: q('[data-testid=place-card-coords]')?.textContent ?? null,
      near: vis(near) ? near.dataset.state ?? null : null,
      nearText: vis(near) ? near.innerText.replace(/\s+/g, ' ').trim() : null,
      nearName: vis(q('[data-testid=place-nearest-name]')) ? q('[data-testid=place-nearest-name]').textContent : null,
      retry: vis(q('[data-testid=place-retry]')) ? q('[data-testid=place-retry]').getAttribute('aria-disabled') ?? 'enabled' : null,
      text: vis(c) ? c.innerText : '',
    };
  });
}

/** Right-clicks the map at viewport point (x, y). */
export async function rightClick(page, x, y) {
  await page.mouse.move(x, y);
  await page.mouse.down({ button: 'right' });
  await page.mouse.up({ button: 'right' });
}

/** Viewport centre of the map canvas. */
export const mapCentrePx = (page) =>
  page.evaluate(() => {
    const r = window.__nav002.map.getCanvas().getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });

/** Waits until the camera stops moving; returns ms waited. */
export async function waitCameraStill(page, timeout = 5000) {
  const t0 = Date.now();
  await page.waitForFunction(() => !window.__nav002.map.isMoving(), null, { timeout });
  return Date.now() - t0;
}

export const decimals = (s) => (String(s).split('.')[1] ?? '').length;

export async function expectNoTrad(page) {
  const txt = await page.evaluate(() => document.body.innerText + ' ' + [...document.querySelectorAll('[aria-label]')].map((e) => e.getAttribute('aria-label')).join(' '));
  expect(TRAD.test(txt), 'no traditional Mongolian script displayed (AC 18)').toBe(false);
}

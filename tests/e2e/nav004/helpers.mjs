// Shared helpers for the NAV-004 E2E tests. Independent of web/ source code: everything is read through the DOM, the
// test hooks documented in web/README.md › Test hooks (data-testid, data-state, data-index, data-key, aria-*,
// window.__nav002.map, window.__nav004 in dev builds) and the MapLibre public API.
// Reuses (imports, never modifies) the NAV-002 and NAV-003 helpers.
import { expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { openApp as openApp003, mock, fc, feature, typeQuery, waitSettled, CORS, SEARCH_GLOB, REVERSE_GLOB, rightClick, jumpTo, camera, project } from '../nav003/helpers.mjs';
import { tid, haversine, waitIdle, attributionProblems, geoSpyInit, ROOT, WEB, CYRILLIC } from '../nav002/helpers.mjs';
import { DEV_PORT, STATIC_PORT } from './playwright.config.mjs';

export { tid, haversine, waitIdle, attributionProblems, geoSpyInit, ROOT, WEB, CYRILLIC, mock, fc, feature, typeQuery, waitSettled, CORS, SEARCH_GLOB, REVERSE_GLOB, rightClick, jumpTo, camera, project };

export const APP = `http://localhost:${DEV_PORT}/`;
export const STATIC_APP = `http://localhost:${STATIC_PORT}/`;
export const GATEWAY = 'http://localhost:8080';
export const ROUTE_GLOB = '**/v1/route**';
const HERE = fileURLToPath(new URL('./', import.meta.url));
export const FIXTURE_ROUTE = JSON.parse(readFileSync(HERE + 'fixtures/ac27-steps.json', 'utf8'));

// Reference points (NAV-001 "Reference test locations", story Context). {lat, lng}
export const REF = {
  P1: { lat: 47.9189, lng: 106.9176 },
  P2: { lat: 47.9139, lng: 106.9044 },
  P3: { lat: 47.8858, lng: 106.9173 },
  P4: { lat: 47.9215, lng: 106.895 },
  P5: { lat: 47.9095, lng: 106.8835 },
  P6: { lat: 47.96, lng: 106.9 },
  X1: { lat: 49.027, lng: 104.044 },
  X2: { lat: 39.9042, lng: 116.4074 },
};

// Reference route set (story "Terms used"). RS8 is redefined as P1 → X2 on foot by ADR-0008 §4 (P1 → X1 on foot is
// 200 on dev, ~245 km straight line < 250 km walk limit); the original RS8 is recorded as RS8a.
export const RS = {
  RS1: { o: 'P1', d: 'P3', mode: 'car' },
  RS2: { o: 'P1', d: 'P2', mode: 'walk' },
  RS3: { o: 'P1', d: 'P4', mode: 'car' },
  RS4: { o: 'P4', d: 'P5', mode: 'bike' },
  RS5: { o: 'P1', d: 'P6', mode: 'car', avoid: true },
  RS6: { o: 'P1', d: 'X1', mode: 'car' },
  RS7: { o: 'P1', d: 'X2', mode: 'car' },
  RS8: { o: 'P1', d: 'X2', mode: 'walk' },
  RS8a: { o: 'P1', d: 'X1', mode: 'walk' },
};
export const COSTING = { car: 'auto', walk: 'pedestrian', bike: 'bicycle' };

// Story strings (story "User-facing strings", glossary N1–N23), asserted literally, NOT read from resource files.
export const T = {
  mn: {
    getDirections: 'Маршрут гаргах',
    title: 'Маршрут харах',
    origin: 'Эхлэх цэг',
    myLocation: 'Миний байршил',
    destination: 'Очих газар',
    car: 'Машин',
    walk: 'Явган',
    bike: 'Дугуй',
    avoid: 'Шороон замаас зайлсхийх',
    alternative: 'Өөр маршрут',
    eta: 'Хүрэх цаг',
    loading: 'Ачаалж байна…',
    noRoute: 'Маршрут олдсонгүй',
    offline: 'Интернэт холболт алга',
    rateLimited: 'Түр хүлээгээд дахин оролдоно уу',
    retry: 'Дахин оролдох',
    close: 'Хаах',
    error: 'Алдаа гарлаа',
    selectedPoint: 'Сонгосон цэг',
    locationUnavailable: 'Байршил тодорхойлж чадсангүй',
    originPlaceholder: 'Эхлэх цэг сонгох',
    destinationPlaceholder: 'Очих газар сонгох',
    swap: 'Эхлэх цэг, очих газрыг солих',
    directions: 'Маршрутын заавар',
    option: (n) => `Маршрут ${n}`,
    count: (n) => `${n} маршрут олдлоо`,
    unavailable: 'Маршрутын үйлчилгээ түр ажиллахгүй байна',
    outOfArea: 'Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна',
    samePoint: 'Эхлэх цэг, очих газар ижил байна',
    tooFar: 'Энэ зай явганаар эсвэл дугуйгаар хэт хол байна',
    avoidHint: 'Шороон замаас зайлсхийх тохиргоог унтрааж дахин оролдоно уу',
    snap: (d) => `Хамгийн ойрын зам сонгосон цэгээс ${d} зайтай`,
    nextDay: (n) => `+${n} өдөр`,
    setOrigin: 'Эхлэх цэг болгох',
    setDestination: 'Очих газар болгох',
    modes: 'Зорчих хэлбэр',
    options: 'Маршрут сонгох',
  },
  en: {
    getDirections: 'Directions',
    title: 'Route preview',
    origin: 'Start',
    myLocation: 'My location',
    destination: 'Destination',
    car: 'Car',
    walk: 'Walk',
    bike: 'Bike',
    avoid: 'Avoid unpaved roads',
    alternative: 'Alternative route',
    eta: 'Arrive at',
    loading: 'Loading…',
    noRoute: 'No route found',
    offline: 'No internet connection',
    rateLimited: 'Too many route requests. Wait a moment and try again',
    retry: 'Try again',
    close: 'Close',
    error: 'Something went wrong',
    selectedPoint: 'Selected point',
    originPlaceholder: 'Choose starting point',
    destinationPlaceholder: 'Choose destination',
    swap: 'Swap start and destination',
    directions: 'Directions',
    option: (n) => `Route ${n}`,
    count: (n) => (n === 1 ? '1 route found' : `${n} routes found`),
    unavailable: 'Routing is temporarily unavailable',
    outOfArea: 'The start or destination is outside the service area',
    samePoint: 'The start and destination are the same',
    tooFar: 'This distance is too far on foot or by bike',
    avoidHint: 'Turn off “Avoid unpaved roads” and try again',
    snap: (d) => `The nearest road is ${d} from the chosen point`,
    nextDay: (n) => (n === 1 ? '+1 day' : `+${n} days`),
    setOrigin: 'Set as start',
    setDestination: 'Set as destination',
    modes: 'Travel mode',
    options: 'Choose a route',
  },
};

// AC 27 table, transcribed by QA from the story (not from web/): [key used in this file] → [mn, en].
export const AC27 = {
  'depart.n': ['Хойд зүг рүү явна уу', 'Head north'],
  'depart.ne': ['Зүүн хойд зүг рүү явна уу', 'Head northeast'],
  'depart.e': ['Зүүн зүг рүү явна уу', 'Head east'],
  'depart.se': ['Зүүн өмнө зүг рүү явна уу', 'Head southeast'],
  'depart.s': ['Өмнө зүг рүү явна уу', 'Head south'],
  'depart.sw': ['Баруун өмнө зүг рүү явна уу', 'Head southwest'],
  'depart.w': ['Баруун зүг рүү явна уу', 'Head west'],
  'depart.nw': ['Баруун хойд зүг рүү явна уу', 'Head northwest'],
  'turn.left': ['Зүүн тийш эргэнэ үү', 'Turn left'],
  'turn.right': ['Баруун тийш эргэнэ үү', 'Turn right'],
  'turn.slightLeft': ['Бага зэрэг зүүн тийш эргэнэ үү', 'Turn slightly left'],
  'turn.slightRight': ['Бага зэрэг баруун тийш эргэнэ үү', 'Turn slightly right'],
  'turn.sharpLeft': ['Огцом зүүн тийш эргэнэ үү', 'Turn sharp left'],
  'turn.sharpRight': ['Огцом баруун тийш эргэнэ үү', 'Turn sharp right'],
  uturn: ['Буцаж эргэнэ үү', 'Make a U-turn'],
  continue: ['Чигээрээ явна уу', 'Continue straight'],
  'keep.left': ['Зүүн талаа барина уу', 'Keep left'],
  'keep.right': ['Баруун талаа барина уу', 'Keep right'],
  merge: ['Замд нийлнэ үү', 'Merge'],
  'merge.left': ['Зүүн талаас замд нийлнэ үү', 'Merge from the left'],
  'merge.right': ['Баруун талаас замд нийлнэ үү', 'Merge from the right'],
  onRamp: ['Орох зам руу эргэнэ үү', 'Take the ramp'],
  'onRamp.left': ['Зүүн талын орох зам руу эргэнэ үү', 'Take the ramp on the left'],
  'onRamp.right': ['Баруун талын орох зам руу эргэнэ үү', 'Take the ramp on the right'],
  offRamp: ['Гарах зам руу эргэнэ үү', 'Take the exit'],
  'offRamp.left': ['Зүүн талын гарах зам руу эргэнэ үү', 'Take the exit on the left'],
  'offRamp.right': ['Баруун талын гарах зам руу эргэнэ үү', 'Take the exit on the right'],
  'roundabout.exit.2': ['Тойрог: 2-р гарц', 'Roundabout: exit 2'],
  'roundabout.exit.3': ['Тойрог: 3-р гарц', 'Roundabout: exit 3'],
  'roundabout.enter': ['Тойрогт орно уу', 'Enter the roundabout'],
  'roundabout.leave': ['Тойргоос гарна уу', 'Exit the roundabout'],
  arrive: ['Та очих газартаа ирлээ', 'You have arrived'],
  'arrive.left': ['Таны очих газар зүүн талд байна', 'Your destination is on the left'],
  'arrive.right': ['Таны очих газар баруун талд байна', 'Your destination is on the right'],
};
/** Every allowed AC 27 text (roundabout exit n = 1..99) in one language. */
export function allowedTexts(lang) {
  const i = lang === 'mn' ? 0 : 1;
  const s = new Set(Object.values(AC27).map((v) => v[i]));
  for (let n = 1; n < 100; n++) s.add(lang === 'mn' ? `Тойрог: ${n}-р гарц` : `Roundabout: exit ${n}`);
  return s;
}

/** AC 28 scan of one Mongolian instruction text; returns the list of problems. */
export function ac28Problems(text) {
  const p = [];
  if (/[A-Za-z]/.test(text)) p.push('Latin letter');
  if (/[<>]|\{[^}]*\}/.test(text)) p.push('placeholder token');
  const bare = /(зүүн|баруун)(?!\s+(тийш|талаа|талаас|талын|талд|зүг|хойд|өмнө|эгнээ))/giu;
  if (bare.test(text)) p.push('bare зүүн/баруун');
  if (/[​‌‍﻿]/.test(text)) p.push('zero-width char');
  if (/навигаци/iu.test(text) || /км\/ц(?!аг)/u.test(text) || /хоёр дахь/iu.test(text) || /\d+\s*(дахь|дэх)/u.test(text) || /зорьсон газар/iu.test(text) || /налуу зам/iu.test(text)) p.push('glossary Avoid term');
  if (!allowedTexts('mn').has(text)) p.push('not an AC 27 text');
  return p;
}

// ---------------------------------------------------------------- formatting per AC 23–25 (QA oracle, from the story)

export function fmtDistance(d, lang) {
  const km = lang === 'mn' ? 'км' : 'km';
  const m = lang === 'mn' ? 'м' : 'm';
  if (d < 995) return `${Math.max(10, Math.round(d / 10) * 10)} ${m}`;
  if (d < 99_950) {
    const v = (Math.round(d / 100) / 10).toFixed(1).replace(/\.0$/, '');
    return `${lang === 'mn' ? v.replace('.', ',') : v} ${km}`;
  }
  return `${Math.round(d / 1000)} ${km}`;
}
export function fmtDuration(t, lang) {
  const h = lang === 'mn' ? 'ц' : 'h';
  const mi = lang === 'mn' ? 'мин' : 'min';
  const mins = Math.max(1, Math.round(t / 60));
  if (t < 3570) return `${mins} ${mi}`;
  const H = Math.floor(mins / 60);
  const M = mins % 60;
  return M === 0 ? `${H} ${h}` : `${H} ${h} ${M} ${mi}`;
}
/** Normalises no-break spaces (the screen spec allows U+00A0 between number and unit). */
export const nb = (s) => (s ?? '').replace(/ /g, ' ').replace(/\s+/g, ' ').trim();

// ---------------------------------------------------------------- polyline6 + OSRM fixture responses

function encSigned(v) {
  let s = v < 0 ? ~(v << 1) : v << 1;
  let out = '';
  while (s >= 0x20) {
    out += String.fromCharCode((0x20 | (s & 0x1f)) + 63);
    s >>= 5;
  }
  return out + String.fromCharCode(s + 63);
}
/** polyline6 of [[lng, lat], …]. */
export function polyline6(coords) {
  let plat = 0;
  let plng = 0;
  let out = '';
  for (const [lng, lat] of coords) {
    const la = Math.round(lat * 1e6);
    const ln = Math.round(lng * 1e6);
    out += encSigned(la - plat) + encSigned(ln - plng);
    plat = la;
    plng = ln;
  }
  return out;
}

/** Straight line from a to b ({lat,lng}) with n points, optionally bowed sideways by `bow` degrees. */
export function line(a, b, n = 12, bow = 0) {
  const pts = [];
  for (let i = 0; i < n; i++) {
    const f = i / (n - 1);
    const off = Math.sin(Math.PI * f) * bow;
    pts.push([a.lng + (b.lng - a.lng) * f + off, a.lat + (b.lat - a.lat) * f + off * 0.5]);
  }
  return pts;
}

/**
 * An OSRM route object. `steps` = [{ maneuver: {type, modifier?, exit?, bearing_after?}, name?, distance? }]; the
 * maneuver locations are spread along the geometry.
 */
export function osrmRoute({ coords, distance, duration, steps }) {
  const n = steps.length;
  const st = steps.map((s, i) => {
    const at = coords[Math.min(coords.length - 1, Math.round((i / Math.max(1, n - 1)) * (coords.length - 1)))];
    return {
      distance: s.distance ?? (s.maneuver.type === 'arrive' ? 0 : 120 + i * 10),
      duration: s.duration ?? 20,
      weight: 20,
      name: s.name ?? '',
      mode: 'driving',
      driving_side: 'right',
      geometry: polyline6([at, at]),
      maneuver: { bearing_before: 0, bearing_after: 0, location: at, instruction: 'IGNORED Valhalla text <ORDINAL VALUE> зүүн эргэ.', ...s.maneuver },
      intersections: [{ location: at, bearings: [0], entry: [true], out: 0 }],
    };
  });
  return {
    distance,
    duration,
    weight: duration,
    weight_name: 'auto',
    geometry: polyline6(coords),
    legs: [{ distance, duration, weight: duration, summary: 'fixture', steps: st, via_waypoints: [], admins: [{ iso_3166_1: 'MN', iso_3166_1_alpha3: 'MNG' }] }],
  };
}

/** A full OSRM 200 body with `routes` (osrmRoute objects) and snap distances. */
export function osrmBody(routes, snap = [3.2, 4.1], ends = null) {
  const first = routes[0].legs[0].steps;
  const a = ends?.[0] ?? first[0].maneuver.location;
  const b = ends?.[1] ?? first.at(-1).maneuver.location;
  return { code: 'Ok', routes, waypoints: [{ name: '', location: a, distance: snap[0] }, { name: '', location: b, distance: snap[1] }] };
}

/** Simple k-route fixture between origin o and destination d ({lat,lng}); route i has distance/duration given. */
export function simpleBody(o, d, specs = [{ distance: 4300, duration: 720 }], snap) {
  const routes = specs.map((s, i) =>
    osrmRoute({
      coords: line(o, d, 16, i === 0 ? 0 : (i % 2 ? 1 : -1) * 0.004 * i),
      distance: s.distance,
      duration: s.duration,
      steps: s.steps ?? [
        { maneuver: { type: 'depart', bearing_after: 180 }, name: 'Энхтайвны өргөн чөлөө', distance: 600 },
        { maneuver: { type: 'turn', modifier: 'right' }, name: 'Чингисийн өргөн чөлөө', distance: s.distance - 600 },
        { maneuver: { type: 'arrive' }, name: '' },
      ],
    }),
  );
  return osrmBody(routes, snap, [[o.lng, o.lat], [d.lng, d.lat]]);
}

export const JSON_CORS = { ...CORS, 'Access-Control-Expose-Headers': 'Retry-After' };

/**
 * Mocks POST /v1/route by interception (OPTIONS preflight continues to the gateway). `handler(body, n, route)` returns
 * { status, body, headers, delay, abort, hang } or 'continue' (live). Returns the Node-side call log.
 */
export async function mockRoute(page, handler) {
  const log = [];
  await page.route(ROUTE_GLOB, async (route) => {
    const req = route.request();
    if (req.method() === 'OPTIONS') return route.continue();
    let body = null;
    try {
      body = JSON.parse(req.postData() ?? 'null');
    } catch {}
    const n = log.length;
    const rec = { t: Date.now(), method: req.method(), url: req.url(), body, raw: req.postData() };
    log.push(rec);
    const r = (await handler(body, n, route)) ?? {};
    if (r === 'continue') return route.continue();
    if (r.delay) await new Promise((res) => setTimeout(res, r.delay));
    if (r.hang) return;
    if (r.abort) return route.abort(r.abort === true ? 'failed' : r.abort).catch(() => {});
    const out = typeof r.body === 'string' ? r.body : JSON.stringify(r.body ?? { code: 'Ok', routes: [] });
    await route.fulfill({ status: r.status ?? 200, headers: { ...JSON_CORS, ...(r.headers ?? {}) }, body: out }).catch(() => {});
  });
  return log;
}

// ---------------------------------------------------------------- in-page recorder

/**
 * In-page recorder: every gateway route fetch (method, URL, body, start/settle time, status/abort), plus a timeline of
 * the route panel (data-state, aria-busy, state row, lines, live region). performance.now() ms.
 */
export function nav004Init() {
  window.__rr = [];
  window.__rst = [];
  window.__live = [];
  window.__act = []; // activation events (click / Enter / Space) on NAV-004 controls: {t, id}
  const actRec = (e) => {
    const el = e.target && e.target.closest && e.target.closest('[data-testid]');
    if (!el) return;
    if (e.type === 'keydown' && !['Enter', ' '].includes(e.key)) {
      (window.__keys ??= []).push({ t: performance.now(), ts: e.timeStamp, id: el.dataset.testid, key: e.key });
      return;
    }
    window.__act.push({ t: performance.now(), ts: e.timeStamp, id: el.dataset.testid, type: e.type, key: e.key ?? null });
  };
  document.addEventListener('click', actRec, true);
  document.addEventListener('keydown', actRec, true);
  const of = window.fetch;
  window.fetch = function (input, init) {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    if (!/\/v1\/route/.test(url)) return of.apply(this, arguments);
    const method = (init && init.method) || (input && input.method) || 'GET';
    let body = null;
    try {
      body = init && typeof init.body === 'string' ? JSON.parse(init.body) : null;
    } catch {}
    const rec = { url, method, body, raw: init && typeof init.body === 'string' ? init.body : null, t: performance.now(), end: null, outcome: null, abortAt: null };
    window.__rr.push(rec);
    const sig = init && init.signal;
    if (sig) sig.addEventListener('abort', () => (rec.abortAt ??= performance.now()), { once: true });
    const p = of.apply(this, arguments);
    p.then(
      (r) => ((rec.end = performance.now()), (rec.outcome = r.status)),
      (e) => ((rec.end = performance.now()), (rec.outcome = String(e && e.name))),
    );
    return p;
  };
  let last = '';
  let lastLive = '';
  const snap = () => {
    const q = (s) => document.querySelector(s);
    const panel = q('[data-testid=route-panel]');
    const slot = q('[data-testid=route-slot]');
    const res = q('[data-testid=route-result]');
    const row = q('[data-testid=route-state]');
    const live = q('[data-testid=route-live]')?.textContent ?? '';
    if (live !== lastLive) {
      lastLive = live;
      if (live) window.__live.push({ t: performance.now(), text: live });
    }
    const st = {
      open: !!panel && !panel.hidden && !panel.closest('[hidden]'),
      slot: slot ? !slot.hidden : null,
      state: panel?.dataset.state ?? null,
      busy: res?.getAttribute('aria-busy') ?? null,
      row: row ? row.dataset.state : null,
      rowText: row ? row.querySelector('#route-state-text')?.textContent ?? row.textContent : null,
      summary: !!q('[data-testid=route-summary]'),
      steps: document.querySelectorAll('[data-testid=route-step]').length,
      eta: q('[data-testid=route-eta]')?.textContent ?? null,
    };
    const key = JSON.stringify(st);
    if (key !== last) {
      last = key;
      window.__rst.push({ t: performance.now(), ...st });
    }
  };
  new MutationObserver(snap).observe(document, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['hidden', 'data-state', 'aria-busy'] });
}

/** Opens the app on the NAV-004 dev server (NAV-002 openApp + NAV-003 recorder + NAV-004 recorder). */
export async function openApp(page, opts = {}) {
  await page.addInitScript(nav004Init);
  await openApp003(page, { url: APP, ...opts });
  await page.waitForFunction(() => !!window.__nav004?.route?.controller, null, { timeout: 15_000 });
}

export const routeReqs = (page) => page.evaluate(() => window.__rr.map(({ url, method, body, raw, t, end, outcome, abortAt }) => ({ url, method, body, raw, t, end, outcome, abortAt })));
/** Max number of route requests in flight at once (interval = start → min(settle, abort)). */
export function maxInFlight(reqs) {
  const ev = [];
  for (const r of reqs) {
    const stop = Math.min(r.end ?? Infinity, r.abortAt ?? Infinity);
    ev.push([r.t, 1], [stop, -1]);
  }
  ev.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  let cur = 0;
  let max = 0;
  for (const [, d] of ev) max = Math.max(max, (cur += d));
  return max;
}
export const routeCount = async (page) => (await page.evaluate(() => window.__rr.length));
export const rtimeline = (page) => page.evaluate(() => window.__rst);
export const pnow = (page) => page.evaluate(() => performance.now());
export const lives = (page) => page.evaluate(() => window.__live);
/** Time (performance.now) of the last activation event on the element with test id `id` (click, Enter or Space). */
export const lastAct = (page, id) => page.evaluate((id) => [...window.__act].reverse().find((a) => a.id === id)?.t ?? null, id);

/** First timeline entry at or after t0 that satisfies pred (source string of (s) => bool); returns ms after t0 or null. */
export async function firstAfter(page, t0, predSrc) {
  return page.evaluate(
    ({ t0, predSrc }) => {
      const pred = new Function('s', `return (${predSrc})(s)`);
      const e = window.__rst.find((s) => s.t >= t0 && pred(s));
      return e ? e.t - t0 : null;
    },
    { t0, predSrc },
  );
}

/** Controller view summary through the dev hook. */
export const rview = (page) =>
  page.evaluate(() => {
    const c = window.__nav004.route.controller;
    const v = c.view;
    return { state: v.state, selected: v.selected, k: v.response?.routes.length ?? 0, retry: v.retry, mode: c.mode, avoid: c.avoid, origin: c.origin, destination: c.destination, open: c.open };
  });

/** Panel snapshot from the DOM. */
export async function panel(page) {
  return page.evaluate(() => {
    const q = (s) => document.querySelector(s);
    const vis = (e) => !!e && !e.hidden && !e.closest('[hidden]') && e.getClientRects().length > 0;
    const p = q('[data-testid=route-panel]');
    const row = q('[data-testid=route-state]');
    const tabs = ['car', 'walk', 'bike'].map((m) => q(`[data-testid=route-tab-${m}]`));
    return {
      open: vis(p),
      state: p?.dataset.state ?? null,
      title: q('[data-testid=route-title]')?.textContent ?? null,
      origin: q('[data-testid=route-origin]')?.value ?? null,
      destination: q('[data-testid=route-destination]')?.value ?? null,
      originPh: q('[data-testid=route-origin]')?.placeholder ?? null,
      destPh: q('[data-testid=route-destination]')?.placeholder ?? null,
      originName: q('[data-testid=route-origin]')?.getAttribute('aria-label') ?? null,
      destName: q('[data-testid=route-destination]')?.getAttribute('aria-label') ?? null,
      swapDisabled: q('[data-testid=route-swap]')?.getAttribute('aria-disabled') ?? null,
      tabs: tabs.map((t) => ({ text: t?.textContent.trim() ?? null, selected: t?.getAttribute('aria-selected') })),
      avoidVisible: vis(q('[data-testid=route-avoid]')),
      avoidChecked: q('[data-testid=route-avoid]')?.getAttribute('aria-checked') ?? null,
      row: vis(row) ? row.dataset.state : null,
      rowText: vis(row) ? q('#route-state-text')?.textContent ?? row.textContent : null,
      mainText: vis(row) ? (row.querySelector('.msg-main')?.textContent ?? null) : null,
      hint: vis(q('[data-testid=route-avoid-hint]')) ? q('[data-testid=route-avoid-hint]').textContent : null,
      retry: vis(q('[data-testid=route-retry]')) ? q('[data-testid=route-retry]').getAttribute('aria-disabled') ?? 'enabled' : null,
      busy: q('[data-testid=route-result]')?.getAttribute('aria-busy') ?? null,
      duration: q('[data-testid=route-duration]')?.textContent ?? null,
      distance: q('[data-testid=route-distance]')?.textContent ?? null,
      eta: q('[data-testid=route-eta]')?.textContent ?? null,
      snap: vis(q('[data-testid=route-snap-notice]')) ? q('[data-testid=route-snap-notice]').textContent : null,
      options: [...document.querySelectorAll('[data-testid=route-option]')].map((o) => ({ index: Number(o.dataset.index), checked: o.getAttribute('aria-checked'), label: o.getAttribute('aria-label'), text: o.textContent, desc: o.getAttribute('aria-describedby') ? (document.getElementById(o.getAttribute('aria-describedby'))?.textContent ?? null) : null })),
      optionsVisible: vis(q('[data-testid=route-options]')),
      steps: [...document.querySelectorAll('[data-testid=route-step]')].map((b) => ({
        index: Number(b.dataset.index),
        key: b.dataset.key,
        text: b.querySelector('.in')?.textContent ?? null,
        street: b.querySelector('.st')?.textContent ?? null,
        streetLang: b.querySelector('.st')?.getAttribute('lang') ?? null,
        dist: b.querySelector('.dd')?.textContent ?? null,
        label: b.getAttribute('aria-label'),
        iconHidden: b.querySelector('svg, .si')?.closest('[aria-hidden=true]') ? true : b.querySelector('[aria-hidden=true]') ? true : false,
        tabindex: b.getAttribute('tabindex'),
      })),
      live: q('[data-testid=route-live]')?.textContent ?? '',
    };
  });
}

/** Route line features in the nav-route source (index, selected, coordinate count). */
export const lines = (page) =>
  page.evaluate(() => {
    const m = window.__nav002.map;
    const src = m.getSource('nav-route');
    const data = src?.serialize?.().data ?? window.__nav004.route.styleData?.lines;
    const fs = data && typeof data === 'object' ? data.features ?? [] : [];
    return fs.map((f) => ({ index: f.properties.index, selected: f.properties.selected, n: f.geometry.coordinates.length, coords: f.geometry.coordinates }));
  });

// ---------------------------------------------------------------- flows

/** Pins the NAV-003 reverse call of coordinate cards to a fixture (no live reverse load). */
export async function mockReverse(page, name = 'Сүхбаатарын талбай') {
  return mock(page, REVERSE_GLOB, () => ({ body: fc([feature(name, 106.9176, 47.9189, { osm_key: 'place', osm_value: 'square' }, 4242)]) }));
}

/** Opens a coordinate card for `p` via typed coordinates in the search box (no search request). */
export async function coordCard(page, p) {
  const q = `${p.lat.toFixed(5)}, ${p.lng.toFixed(5)}`;
  await typeQuery(page, q);
  await waitSettled(page, q);
  await page.keyboard.press('Enter');
  await expect(tid(page, 'place-card')).toBeVisible();
  await expect(tid(page, 'route-open')).toBeVisible();
}

/** Opens the route preview from a coordinate card at `dest` (location off → origin empty). */
export async function openPreview(page, dest) {
  await coordCard(page, dest);
  await tid(page, 'route-open').click();
  await expect(tid(page, 'route-panel')).toBeVisible();
}

/** Sets a route field (origin|destination) to typed coordinates `p` and selects the coordinate option. */
export async function setField(page, which, p) {
  const input = tid(page, `route-${which}`);
  await input.click();
  await page.keyboard.press('ControlOrMeta+A');
  await page.keyboard.press('Backspace');
  const q = `${p.lat.toFixed(5)}, ${p.lng.toFixed(5)}`;
  await page.keyboard.type(q);
  const opt = page.locator(`#route-${which}-results [data-testid=route-field-option]`).first();
  await expect(opt).toBeVisible();
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
}

/** Full A→B preview with typed coordinates: dest via coordinate card, origin via the origin field. */
export async function preview(page, o, d, { mode } = {}) {
  await openPreview(page, d);
  await page.keyboard.press('Escape'); // close the origin list first (defect NAV-004-D2)
  if (mode && mode !== 'car') {
    await tid(page, `route-tab-${mode}`).click();
    await page.waitForTimeout(350);
  }
  await setField(page, 'origin', o);
}

/** Waits for the panel to reach a final state (not empty/pending/loading). */
export async function waitFinal(page, timeout = 15_000) {
  await page.waitForFunction(() => {
    const s = document.querySelector('[data-testid=route-panel]')?.dataset.state;
    return s && !['empty', 'pending', 'loading'].includes(s);
  }, null, { timeout });
  return (await panel(page)).state;
}

/** Pacing for live routing tests: the whole run stays <= 2 route requests per second (story Test approach). */
export async function livePace(page, n = 1) {
  await page.waitForTimeout(Math.max(600, n * 550));
}

export const decimals = (x) => {
  const s = typeof x === 'string' ? x : String(x);
  return (s.split('.')[1] ?? '').length;
};

/** Raw decimals of lat/lon of each location as serialised in the request body text. */
export function rawLocationDecimals(raw) {
  const out = [];
  for (const m of (raw ?? '').matchAll(/"(lat|lon)"\s*:\s*(-?\d+(?:\.(\d+))?)/g)) out.push({ k: m[1], v: m[2], dec: (m[3] ?? '').length });
  return out;
}

/** Enables NAV-002 my location with a fix at p (grants permission; clicks the button; waits for following). */
export async function locationOn(page, context, p) {
  await context.grantPermissions(['geolocation']);
  await context.setGeolocation({ latitude: p.lat, longitude: p.lng, accuracy: 20 });
  await tid(page, 'my-location').click();
  await expect(tid(page, 'my-location')).toHaveAttribute('data-state', /following|not-following/);
}

export async function mapCanvasBox(page) {
  return page.evaluate(() => {
    const r = window.__nav002.map.getCanvas().getBoundingClientRect();
    return { x: r.left, y: r.top, w: r.width, h: r.height };
  });
}

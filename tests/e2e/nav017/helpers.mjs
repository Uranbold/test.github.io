// Shared helpers for the NAV-017 E2E tests (web demo mode). Story: docs/requirements/stories/NAV-017-web-demo-mode-replay.md.
// Test plan: docs/qa/test-plans/NAV-017.md.
//
// Independent of web/ source code: the app is driven through the PRODUCTION demo-mode build (served by nav017/site.mjs),
// the DOM, the documented test ids (web/README.md › Demo mode › Behaviour and test hooks), browser stubs installed with
// addInitScript (speechSynthesis, Web Audio, Geolocation, Wake Lock, page visibility) and the Playwright clock.
// Oracles come from the repo fixtures (recorded routes, GPX tracks, the NAV-005 voice golden set) and from the story
// text (NAV-004 AC 23–27 transcriptions in ../nav004/helpers.mjs), never from web/src.
import { expect } from '@playwright/test';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { AC27, allowedTexts, ac28Problems, fmtDistance, fmtDuration, nb } from '../nav004/helpers.mjs';
import { OUT, ROOT, state } from './site.mjs';
const RUNS = OUT + 'runs/';

export { AC27, allowedTexts, ac28Problems, fmtDistance, fmtDuration, nb, ROOT };
export const WEB = ROOT + 'web/';
export const CYRILLIC = /[Ѐ-ӿ]/;
/** Fake clock installed before navigation; the page reads CLOCK_START + CLOCK_PAUSE_MS when it first paints. */
export const CLOCK_START = new Date('2026-10-01T13:50:00+08:00');
export const CLOCK_PAUSE_MS = 5_000;

// ---------------------------------------------------------------- strings quoted in the story (asserted literally)
export const S = {
  mn: {
    mode: 'Туршилтын горим', choose: 'Маршрут сонгох', car: 'Машин', walk: 'Явган', start: 'Эхлэх', end: 'Дуусгах',
    recenter: 'Байршил руу буцах', mute: 'Дууг хаах', unmute: 'Дууг нээх',
    a1: 'Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.',
    eta: 'Хүрэх цаг', close: 'Хаах', loading: 'Ачаалж байна…', error: 'Алдаа гарлаа', retry: 'Дахин оролдох',
    offline: 'Интернэт холболт алга', selectedPoint: 'Сонгосон цэг', attribution: '© OpenStreetMap contributors',
    arrive: 'Та очих газартаа ирлээ', arriveRight: 'Таны очих газар баруун талд байна', arriveLeft: 'Таны очих газар зүүн талд байна',
  },
  en: {
    mode: 'Demo mode', choose: 'Choose a route', car: 'Car', walk: 'Walk', start: 'Start', end: 'End',
    recenter: 'Back to my location', mute: 'Mute', unmute: 'Unmute',
    a1: 'Mongolian voice guidance is not available on this phone. Instructions are shown on screen only.',
    eta: 'Arrive at', close: 'Close', loading: 'Loading…', error: 'Something went wrong', retry: 'Try again',
    offline: 'No connection', selectedPoint: 'Selected point', attribution: '© OpenStreetMap contributors',
    arrive: 'You have arrived', arriveRight: 'Your destination is on the right', arriveLeft: 'Your destination is on the left',
  },
};

// ---------------------------------------------------------------- demo routes (story › Demo routes) and fixtures
export const ROUTES = {
  R1: { id: 'r1', mode: 'car', route: 'mobile/android/app/src/test/resources/routes/p1-p3-car-mn.json', track: 'tests/gpx/nav005/G1.gpx', golden: 'G1', length: 308 },
  R2: { id: 'r2', mode: 'walk', route: 'mobile/android/app/src/test/resources/routes/p1-p2-walk-mn.json', track: 'tests/gpx/nav005/G5.gpx', golden: 'G5', length: 932 },
  R3: { id: 'r3', mode: 'car', route: 'mobile/android/app/src/test/resources/routes/g8-roundabout-car-mn.json', track: 'tests/gpx/nav005/G8.gpx', golden: 'G8', length: 787 },
};
export const G4 = { route: ROUTES.R1.route, track: 'tests/gpx/nav005/G4.gpx' };
export const readJson = (p) => JSON.parse(readFileSync(ROOT + p, 'utf8'));

/** GPX 1.1 track points: [{lat, lon, tMs}] with tMs relative to the first point. */
export function readGpx(p) {
  const xml = readFileSync(ROOT + p, 'utf8');
  const pts = [...xml.matchAll(/<trkpt lat="([-\d.]+)" lon="([-\d.]+)"><time>([^<]+)<\/time>/g)].map((m) => ({ lat: +m[1], lon: +m[2], t: Date.parse(m[3]) }));
  const t0 = pts[0].t;
  return pts.map((q) => ({ lat: q.lat, lon: q.lon, tMs: q.t - t0 }));
}

/** NAV-005 golden rows for a track and language: [{t, m, text}]. */
export function golden(track, lang) {
  return readFileSync(ROOT + 'tests/gpx/nav005/golden/voice-golden.tsv', 'utf8').split('\n')
    .filter((l) => l && !l.startsWith('#')).map((l) => l.split('\t'))
    .filter(([tr, lg]) => tr === track && lg === lang).map(([, , t, m, text]) => ({ t: Number(t), m: Number(m), text }));
}

// ---------------------------------------------------------------- geometry oracle (QA's own, from the fixtures)
const R = 6371008.8;
const rad = (d) => (d * Math.PI) / 180;
export function haversine(a, b) {
  const dLat = rad(b.lat - a.lat), dLon = rad(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
export function decodePolyline6(str) {
  const out = [];
  let i = 0, lat = 0, lon = 0;
  const next = () => { let r = 0, s = 0, b; do { b = str.charCodeAt(i++) - 63; r |= (b & 0x1f) << s; s += 5; } while (b >= 0x20); return r & 1 ? ~(r >> 1) : r >> 1; };
  while (i < str.length) { lat += next(); lon += next(); out.push({ lat: lat / 1e6, lon: lon / 1e6 }); }
  return out;
}

/**
 * Along-route oracle for one recorded response: the route line, cumulative metres, the along-route position of every
 * manoeuvre (step boundary) and a projection of any point onto the line. Built from the fixture only.
 */
export class RouteOracle {
  constructor(resp) {
    const route = resp.routes[0];
    this.route = route;
    this.steps = route.legs[0].steps;
    this.line = decodePolyline6(route.geometry);
    this.cum = [0];
    for (let i = 1; i < this.line.length; i++) this.cum.push(this.cum[i - 1] + haversine(this.line[i - 1], this.line[i]));
    this.length = this.cum.at(-1);
    // manoeuvre k starts after the steps before it: project its location, searching forward from the previous one
    // (search around the cumulative step distance, never before the previous manoeuvre)
    this.maneuverAlong = [];
    let from = 0, guess = 0;
    for (const s of this.steps) {
      const [lon, lat] = s.maneuver.location;
      const a = this.project({ lat, lon }, Math.max(from, guess - 300), guess + 300).along;
      this.maneuverAlong.push(a);
      from = a;
      guess = a + s.distance;
    }
  }
  /** Nearest point on the line between along positions [lo, hi]: {along, off}. */
  project(p, lo = 0, hi = Infinity) {
    let best = { along: 0, off: Infinity };
    const kx = Math.cos(rad(p.lat)) * R * Math.PI / 180, ky = R * Math.PI / 180;
    for (let i = 1; i < this.line.length; i++) {
      if (this.cum[i] < lo || this.cum[i - 1] > hi) continue;
      const a = this.line[i - 1], b = this.line[i];
      const ax = (a.lon - p.lon) * kx, ay = (a.lat - p.lat) * ky, bx = (b.lon - p.lon) * kx, by = (b.lat - p.lat) * ky;
      const dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
      const t = len2 > 0 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2)) : 0;
      const x = ax + t * dx, y = ay + t * dy, off = Math.hypot(x, y);
      if (off < best.off) best = { along: this.cum[i - 1] + t * (this.cum[i] - this.cum[i - 1]), off };
    }
    return best;
  }
  /** Along positions of a whole track (forward search, so a self-crossing route is not mis-projected). */
  alongTrack(track) {
    let last = null;
    return track.map((p) => {
      // first point: global search (G4 starts 3.8 km along R1); then forward search near the previous position
      const r = last === null ? this.project(p) : this.project(p, Math.max(0, last - 30), last + 400);
      last = Math.max(last ?? 0, r.along);
      return { ...r, tMs: p.tMs };
    });
  }
  pointAt(along) {
    let i = this.cum.findIndex((c) => c >= along);
    if (i <= 0) return this.line[Math.max(0, i)];
    const f = (along - this.cum[i - 1]) / (this.cum[i] - this.cum[i - 1] || 1);
    const a = this.line[i - 1], b = this.line[i];
    return { lat: a.lat + f * (b.lat - a.lat), lon: a.lon + f * (b.lon - a.lon) };
  }
}

/** Along position at replay time tMs (linear between fixes; the fix applied at t is the one with tMs <= t). */
export function alongAt(alongs, tMs) {
  let k = 0;
  while (k + 1 < alongs.length && alongs[k + 1].tMs <= tMs) k++;
  return alongs[k].along;
}

/** Distance stated in a prompt, in metres (null when none): «150 метрт», «1 километрт», "In 150 meters", "In 1 kilometer". */
export function statedDistance(text) {
  let m = text.match(/(\d+(?:[.,]\d+)?)\s*(метрт|километрт)/u);
  if (m) return parseFloat(m[1].replace(',', '.')) * (m[2] === 'километрт' ? 1000 : 1);
  m = text.match(/\bIn (\d+(?:\.\d+)?)\s*(meters?|kilometers?)\b/i);
  if (m) return parseFloat(m[1]) * (/^k/i.test(m[2]) ? 1000 : 1);
  return null;
}

// ---------------------------------------------------------------- AC 25 / NAV-005 AC 33 voice scan (QA transcription)
export function voiceScanProblems(text) {
  const p = [];
  if (/\d\s*(м|км)(?![Ѐ-ӿ])/u.test(text)) p.push('digits followed by м/км abbreviation');
  if (/\d+-р/u.test(text)) p.push('\\d+-р');
  if (/\d+\s*(дахь|дэх)/u.test(text) || /хоёр дахь/iu.test(text)) p.push('old ordinal form');
  if (/ШТС/u.test(text)) p.push('ШТС');
  if (/[A-Za-z]/.test(text.replace(/GPS/g, ''))) p.push('Latin letters');
  if (/[<>{}]/.test(text)) p.push('<>{} token');
  if (/[​‌‍﻿]/.test(text)) p.push('zero-width character');
  if (/1000 метрт|1000 meters/.test(text)) p.push('«1000 метрт» (D67)');
  const bare = /(зүүн|баруун)(?!\s+(тийш|талаа|талаас|талын|талд|зүг|хойд|өмнө|эгнээ))/giu;
  if (bare.test(text)) p.push('bare зүүн/баруун (C2)');
  return p;
}

// ---------------------------------------------------------------- browser stubs (addInitScript)
/**
 * cfg: { voices: ['mn-MN', 'en-US'] | [], lang, theme, muted, speechErrorAt: n (1-based non-empty speak call that fails),
 *        wakeLock: 'ok' | 'reject' | 'missing',
 *        voicesLate: true (getVoices empty until voiceschanged after 500 ms) | <ms> (after that many ms)
 *                    | 'manual' (empty until the test calls window.__qaListVoices(); ADR-0011 §7 pending-decision case) }
 */
export function qaInit(cfg) {
  const log = (window.__qa = {
    geo: 0, speak: [], cancel: 0, chimes: [], ctxCreated: [], ctxResume: [], wake: [], wakeRelease: [], inClick: false,
    startClicks: [], lastClick: null, rec: [], live: [], banners: [], console: [], voicesCalls: [],
  });
  const now = () => performance.now();
  try {
    if (cfg.lang) localStorage.setItem('navmn.lang', cfg.lang);
    if (cfg.theme) localStorage.setItem('navmn.theme', cfg.theme);
    if (cfg.muted !== undefined) localStorage.setItem('navmn.voiceMuted', cfg.muted ? '1' : '0');
  } catch {}
  // "inside the activation handler": window capture phase sets the flag, window bubble phase clears it (AC 27)
  window.addEventListener('click', (e) => {
    log.inClick = true;
    const t = e.target instanceof Element ? e.target.closest('[data-testid]')?.getAttribute('data-testid') : null;
    log.lastClick = { id: t, t: now() };
    if (t === 'demo-start') log.startClicks.push(now());
  }, true);
  window.addEventListener('click', () => { log.inClick = false; }, false);
  // Geolocation: counted, never answers (AC 14)
  const geo = { getCurrentPosition() { log.geo++; }, watchPosition() { log.geo++; return 1; }, clearWatch() {} };
  Object.defineProperty(navigator, 'geolocation', { get: () => geo, configurable: true });
  if ('permissions' in navigator) {
    const q = navigator.permissions.query.bind(navigator.permissions);
    navigator.permissions.query = (d) => { if (d && d.name === 'geolocation') log.geo++; return q(d); };
  }
  // speechSynthesis
  let voices = (cfg.voices || []).map((l, i) => ({ lang: l, name: 'QA voice ' + l, localService: true, default: i === 0, voiceURI: 'qa-' + l }));
  let listed = !cfg.voicesLate;
  const listeners = new Set();
  class Utt extends EventTarget {
    constructor(text) { super(); this.text = text; this.voice = null; this.lang = ''; this.rate = 1; this.pitch = 1; this.volume = 1; this.onstart = null; this.onend = null; this.onerror = null; }
  }
  let current = null, endTimer = null, nCalls = 0, nPrompts = 0;
  const fire = (u, type, extra) => { const ev = Object.assign(new Event(type), extra || {}); try { u['on' + type] && u['on' + type](ev); } catch {} u.dispatchEvent(ev); };
  const synth = {
    getVoices: () => { log.voicesCalls.push(now()); return listed ? voices.slice() : []; },
    speak(u) {
      nCalls++;
      log.speak.push({ text: u.text, lang: u.lang, voice: u.voice ? u.voice.lang : null, t: now(), inClick: log.inClick, volume: u.volume });
      if (!u.text) return; // an empty priming utterance (unlock) is not a prompt
      nPrompts++;
      current = u;
      if (cfg.speechErrorAt && nPrompts === cfg.speechErrorAt) { setTimeout(() => { if (current === u) { current = null; fire(u, 'error', { error: 'synthesis-failed' }); } }, 20); return; }
      setTimeout(() => current === u && fire(u, 'start'), 10);
      const ms = Math.min(5000, 300 + 55 * u.text.length);
      endTimer = setTimeout(() => { if (current === u) { current = null; fire(u, 'end'); } }, ms);
    },
    cancel() { log.cancel++; if (current) { const u = current; current = null; clearTimeout(endTimer); setTimeout(() => fire(u, 'error', { error: 'interrupted' }), 0); } },
    pause() {}, resume() {},
    addEventListener(t, f) { if (t === 'voiceschanged') listeners.add(f); },
    removeEventListener(t, f) { listeners.delete(f); },
    get speaking() { return !!current; }, pending: false, paused: false, onvoiceschanged: null,
  };
  const listVoices = () => { listed = true; listeners.forEach((f) => f(new Event('voiceschanged'))); if (typeof synth.onvoiceschanged === 'function') synth.onvoiceschanged(new Event('voiceschanged')); };
  window.__qaListVoices = listVoices;
  if (cfg.voicesLate === true || typeof cfg.voicesLate === 'number') setTimeout(listVoices, cfg.voicesLate === true ? 500 : cfg.voicesLate);
  Object.defineProperty(window, 'speechSynthesis', { get: () => synth, configurable: true });
  window.SpeechSynthesisUtterance = Utt;
  // Web Audio: counts chimes (one 880 Hz oscillator start per chime, navigation-ux §4.8)
  class Param { constructor() { this._v = 0; } setValueAtTime() {} linearRampToValueAtTime() {} exponentialRampToValueAtTime() {} set value(v) { this._v = v; } get value() { return this._v; } }
  class Node_ { connect() {} disconnect() {} }
  class Osc extends Node_ { constructor() { super(); this.frequency = new Param(); this.type = 'sine'; } start() { if (this.frequency.value === 880) log.chimes.push(now()); } stop() {} }
  class Ctx {
    constructor() { this.state = 'suspended'; this.currentTime = 0; this.destination = {}; log.ctxCreated.push({ t: now(), inClick: log.inClick }); }
    resume() { log.ctxResume.push({ t: now(), inClick: log.inClick }); this.state = 'running'; return Promise.resolve(); }
    suspend() { this.state = 'suspended'; return Promise.resolve(); }
    close() { return Promise.resolve(); }
    createGain() { const g = new Node_(); g.gain = new Param(); return g; }
    createOscillator() { return new Osc(); }
    createBuffer() { return {}; }
    createBufferSource() { const s = new Node_(); s.start = () => {}; s.stop = () => {}; return s; }
  }
  window.AudioContext = Ctx;
  window.webkitAudioContext = Ctx;
  // Screen Wake Lock (AC 39)
  if (cfg.wakeLock === 'missing') {
    try { Object.defineProperty(navigator, 'wakeLock', { get: () => undefined, configurable: true }); } catch {}
  } else {
    Object.defineProperty(navigator, 'wakeLock', {
      configurable: true,
      get: () => ({
        request: async (type) => {
          log.wake.push({ t: now(), type, visible: document.visibilityState });
          if (cfg.wakeLock === 'reject') throw new DOMException('denied', 'NotAllowedError');
          const s = new EventTarget(); s.released = false; s.type = type;
          s.release = async () => { if (!s.released) { s.released = true; log.wakeRelease.push(now()); } };
          return s;
        },
      }),
    });
  }
  // Page visibility, switchable from the test (AC 15)
  let hidden = false;
  Object.defineProperty(document, 'visibilityState', { get: () => (hidden ? 'hidden' : 'visible'), configurable: true });
  Object.defineProperty(document, 'hidden', { get: () => hidden, configurable: true });
  window.__qaSetHidden = (h) => { hidden = h; document.dispatchEvent(new Event('visibilitychange')); };
  // console capture (AC 43)
  for (const k of ['log', 'info', 'warn', 'error', 'debug']) {
    const orig = console[k].bind(console);
    console[k] = (...a) => { try { log.console.push(a.map((x) => (typeof x === 'string' ? x : JSON.stringify(x))).join(' ')); } catch {} orig(...a); };
  }
  // Recorder: started by startRecorder() at «Эхлэх»
  window.__qaRecord = (periodMs) => {
    const q = (id) => document.querySelector(`[data-testid="${id}"]`);
    const vis = (e) => !!e && !e.hidden && !e.closest('[hidden]') && e.getClientRects().length > 0;
    const rect = (e) => { if (!vis(e)) return null; const r = e.getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom]; };
    const t0 = log.startClicks.at(-1) ?? now();
    const snap = () => {
      const msgs = [...document.querySelectorAll('[data-testid="demo-nav-messages"] [data-kind]')].filter(vis).map((e) => e.getAttribute('data-kind'));
      log.rec.push({
        t: now() - t0,
        variant: q('demo-nav-banner')?.getAttribute('data-variant') ?? null,
        text: vis(q('demo-nav-text')) ? q('demo-nav-text').textContent : '',
        dist: vis(q('demo-nav-distance')) ? q('demo-nav-distance').textContent : null,
        street: vis(q('demo-nav-street')) ? q('demo-nav-street').textContent : null,
        eta: vis(q('demo-nav-eta')) ? q('demo-nav-eta').textContent : null,
        remaining: vis(q('demo-nav-remaining')) ? q('demo-nav-remaining').textContent : null,
        arrival: vis(q('demo-nav-arrival')) ? q('demo-nav-arrival').textContent : null,
        progress: vis(q('demo-nav-progress')),
        recenter: vis(q('demo-nav-recenter')),
        badge: vis(q('demo-badge')),
        msgs,
        puck: rect(q('demo-nav-puck')),
        rects: { top: rect(document.querySelector('.dn-top')), banner: rect(q('demo-nav-banner')), badge: rect(q('demo-badge')), progress: rect(q('demo-nav-progress')), arrival: rect(q('demo-nav-arrival')), messages: rect(q('demo-nav-messages')), attribution: rect(document.querySelector('[data-testid="attribution"]') || document.getElementById('attribution')) },
      });
    };
    snap();
    setInterval(snap, periodMs);
    const live = q('demo-nav-live');
    if (live) new MutationObserver(() => log.live.push({ t: now() - t0, text: live.textContent })).observe(live, { childList: true, characterData: true, subtree: true });
    const txt = q('demo-nav-text');
    if (txt) new MutationObserver(() => log.banners.push({ t: now() - t0, text: txt.textContent })).observe(txt, { childList: true, characterData: true, subtree: true });
    const ban = q('demo-nav-banner');
    log.variants = [{ t: 0, v: ban?.getAttribute('data-variant') }];
    if (ban) new MutationObserver(() => { const v = ban.getAttribute('data-variant'); if (log.variants.at(-1).v !== v) log.variants.push({ t: now() - t0, v }); }).observe(ban, { attributes: true, attributeFilter: ['data-variant'] });
    const dist = q('demo-nav-distance');
    log.dists = [];
    if (dist) new MutationObserver(() => log.dists.push({ t: now() - t0, text: dist.textContent })).observe(dist, { childList: true, characterData: true, subtree: true });
  };
}

// ---------------------------------------------------------------- navigation
export const site = () => state();
export const demoUrl = (folder = 'a') => site().origin + site().folders[folder];
export const tid = (page, id) => page.getByTestId(id);

/** Network log of a page: every request URL with its time. */
export function netLog(page) {
  const reqs = [];
  page.on('request', (r) => reqs.push({ url: r.url(), method: r.method(), sw: !!r.serviceWorker?.() }));
  return reqs;
}

/**
 * Opens the demo-mode build with the stubs and the Playwright clock installed BEFORE navigation (web/README.md note).
 * Returns when the picker is visible. Advances fake time in small steps so MapLibre and the picker can settle.
 */
export async function openDemo(page, cfg = {}, folder = 'a') {
  await page.addInitScript(qaInit, { voices: [], ...cfg });
  // install() alone lets time keep flowing in real time; pauseAt() makes page time advance ONLY through tick()/runFor(),
  // so "nothing happened in N ms" assertions are exact.
  if (cfg.clock !== false) {
    const t = cfg.time ?? CLOCK_START;
    await page.clock.install({ time: t });
    // pauseAt() must target a time ahead of the fake clock, which runs in real time between install() and pauseAt().
    // WebKit needs more than 1 ms for the round trip ("Cannot fast-forward to the past"), so pause CLOCK_PAUSE_MS ahead;
    // the page's clock then reads CLOCK_START + CLOCK_PAUSE_MS at the first paint (ETA oracle: replay.test START).
    await page.clock.pauseAt(new Date(t.getTime() + CLOCK_PAUSE_MS));
  }
  await page.goto(demoUrl(folder));
  await waitPicker(page);
}

/**
 * Hides the MapLibre canvas for logic replays (visibility only: tiles still load, layout unchanged). Software WebGL
 * painting dominates the wall time on this machine (measured 0.6 s vs 0.2 s per replay second).
 */
export async function hideCanvas(page) {
  // SEC-4B (2026-10-08): the build's meta CSP allows only hashed inline styles, so page.addStyleTag (an inline <style>)
  // is refused. A constructable stylesheet (CSSOM, document.adoptedStyleSheets) is not an inline style and is not subject
  // to style-src; it keeps the old semantics (one rule, also matches canvases created later). Never bypassCSP.
  await page.evaluate(() => {
    const sheet = new CSSStyleSheet();
    sheet.replaceSync('.maplibregl-canvas{visibility:hidden !important}');
    document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
  });
}

export async function waitPicker(page, maxMs = 30_000) {
  const t0 = Date.now();
  while (Date.now() - t0 < maxMs) {
    if (await tid(page, 'demo-picker').isVisible().catch(() => false)) return;
    await tick(page, 200);
    await page.waitForTimeout(100);
  }
  throw new Error('picker not shown');
}

/** Advance the fake clock (no-op if the clock is not installed). */
export async function tick(page, ms) {
  try { await page.clock.runFor(ms); } catch (e) { if (!/clock/i.test(String(e))) throw e; await page.waitForTimeout(ms); }
}

export async function selectRoute(page, label) {
  await page.locator(`[data-testid="demo-route"][data-route="${label}"]`).click();
  for (let i = 0; i < 100; i++) {
    if (await page.locator('[data-testid="demo-start"][aria-disabled="false"]').count()) return;
    await tick(page, 100);
    await page.waitForTimeout(30);
  }
  throw new Error(`«Эхлэх» not enabled after selecting ${label}`);
}

/** Taps «Эхлэх» and starts the in-page recorder (snapshot every `periodMs` of replay time). */
export async function startReplay(page, periodMs = 250) {
  await tid(page, 'demo-start').click();
  await page.evaluate((p) => window.__qaRecord(p), periodMs);
}

/** Runs the replay for `seconds` of fake time in chunks. */
export async function runFor(page, seconds, chunkMs = 5_000) {
  let left = seconds * 1000;
  while (left > 0) {
    const ms = Math.min(chunkMs, left);
    await page.clock.runFor(ms);
    left -= ms;
  }
}

export const qa = (page) => page.evaluate(() => {
  const q = window.__qa;
  const t0 = q.startClicks.at(-1) ?? 0;
  return { ...q, t0, speakRel: q.speak.map((s) => ({ ...s, rel: s.t - t0 })), chimesRel: q.chimes.map((t) => t - t0) };
});

/** Spoken prompts (non-empty texts) relative to the «Эхлэх» tap, in seconds. */
export const spoken = (log) => log.speakRel.filter((s) => s.text).map((s) => ({ t: s.rel / 1000, text: s.text, voice: s.voice, lang: s.lang, inClick: s.inClick }));

/** Asserts no rectangle a intersects rectangle b. */
export const intersects = (a, b, eps = 0.5) => !!a && !!b && a[0] < b[2] - eps && b[0] < a[2] - eps && a[1] < b[3] - eps && b[1] < a[3] - eps;

/** Every banner text of a record list is an AC 27 text in `lang` (or the arrival texts). */
export function bannerProblems(rec, lang) {
  const allowed = allowedTexts(lang);
  const out = [];
  for (const r of rec) {
    if (r.arrival !== null && r.variant === 'arrival' && !allowed.has(r.text)) out.push(`${r.t}: arrival text "${r.text}"`);
    if (!r.text) out.push(`${(r.t / 1000).toFixed(1)} s: banner blank`);
    else if (!allowed.has(r.text)) out.push(`${(r.t / 1000).toFixed(1)} s: "${r.text}" is not an AC 27 ${lang} text`);
  }
  return [...new Set(out)];
}

export { expect };

// ---------------------------------------------------------------- AC 27 key of a recorded step (QA transcription of NAV-004 AC 27)
const LEFT = new Set(['left', 'slight left', 'sharp left']);
const RIGHT = new Set(['right', 'slight right', 'sharp right']);
const TURN = { left: 'turn.left', right: 'turn.right', 'slight left': 'turn.slightLeft', 'slight right': 'turn.slightRight', 'sharp left': 'turn.sharpLeft', 'sharp right': 'turn.sharpRight' };
/** Expected banner text of a step's manoeuvre in `lang` (NAV-004 AC 27 incl. D56). */
export function ac27Text(m, lang) {
  const i = lang === 'mn' ? 0 : 1;
  const mod = m.modifier ?? null;
  const side = (base) => AC27[LEFT.has(mod) ? `${base}.left` : RIGHT.has(mod) ? `${base}.right` : base][i];
  switch (m.type) {
    case 'depart': {
      const s = ['n', 'ne', 'e', 'se', 's', 'sw', 'w', 'nw'][Math.floor((((m.bearing_after ?? 0) + 22.5) % 360) / 45)];
      return AC27[`depart.${s}`][i];
    }
    case 'arrive': return side('arrive');
    case 'roundabout': case 'rotary':
      return m.exit ? (lang === 'mn' ? `Тойрог: ${m.exit}-р гарц` : `Roundabout: exit ${m.exit}`) : AC27['roundabout.enter'][i];
    case 'exit roundabout': case 'exit rotary': return AC27['roundabout.leave'][i];
    case 'fork': return LEFT.has(mod) ? AC27['keep.left'][i] : RIGHT.has(mod) ? AC27['keep.right'][i] : AC27.continue[i];
    case 'merge': return side('merge');
    case 'on ramp': return side('onRamp');
    case 'off ramp': return side('offRamp');
    default:
      if (mod === 'uturn') return AC27.uturn[i];
      if (TURN[mod]) return AC27[TURN[mod]][i];
      return AC27.continue[i];
  }
}

/** Sentinel copy of a recorded response (AC 18): every Valhalla text field replaced. */
export const SENTINEL = 'VALHALLA_TEXT_SENTINEL';
export function sentinelCopy(resp) {
  const r = structuredClone(resp);
  for (const route of r.routes) for (const leg of route.legs) for (const s of leg.steps) {
    if (s.maneuver) s.maneuver.instruction = SENTINEL;
    for (const b of s.bannerInstructions ?? []) for (const part of ['primary', 'secondary', 'sub']) {
      if (!b[part]) continue;
      b[part].text = SENTINEL;
      for (const c of b[part].components ?? []) if ('text' in c) c.text = SENTINEL;
    }
    for (const v of s.voiceInstructions ?? []) { v.announcement = SENTINEL; if (v.ssmlAnnouncement) v.ssmlAnnouncement = `<speak>${SENTINEL}</speak>`; }
  }
  return r;
}

/**
 * Builds a demo-routes/<id>.json body (the build's format, read from the served file) with another track or response.
 * Used for the test-only G4 track (AC 34) and the sentinel copies (AC 18).
 */
export async function routeBody(id, { route, track } = {}) {
  const s = site();
  const base = await (await fetch(`${s.origin}${s.folders.a}demo-routes/${id}.json`)).json();
  if (route) base.route = route;
  if (track) base.track = { t_ms: track.map((p) => p.tMs), lonlat: track.map((p) => [p.lon, p.lat]) };
  return base;
}

/**
 * One full replay in a fresh context. opts: { label, lang, voices, body (override for the route data file), seconds,
 * reducedMotion, viewport, before(page), during(page, sec) called every 10 s of replay time }.
 * Returns everything the assertions need.
 */
export async function fullReplay(browser, project, opts) {
  // NAV017_REUSE_RUNS=1: re-evaluate the assertions on the recorded run of a previous execution (same name and engine),
  // without replaying again. The QA report always cites fresh runs.
  const file = `${RUNS}${opts.name.replace(/[^A-Za-z0-9]+/g, '_')}-${project.name}.json`;
  if (process.env.NAV017_REUSE_RUNS === '1' && existsSync(file)) {
    const saved = JSON.parse(readFileSync(file, 'utf8'));
    return { ...saved, page: { content: async () => saved.html }, ctx: { close: async () => {} } };
  }
  const res = await fullReplayLive(browser, project, opts);
  mkdirSync(RUNS, { recursive: true });
  writeFileSync(file, JSON.stringify({ log: res.log, reqs: res.reqs, errors: res.errors, storage: res.storage, html: await res.page.content() }));
  return res;
}

async function fullReplayLive(browser, project, opts) {
  const { defaultBrowserType, ...use } = project.use;
  const ctx = await browser.newContext({ ...use, reducedMotion: opts.reducedMotion ?? 'reduce', ...(opts.viewport ? { viewport: opts.viewport } : {}) });
  const page = await ctx.newPage();
  const reqs = netLog(page);
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  if (opts.body) await page.route(`**/demo-routes/${ROUTES[opts.label].id}.json`, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(opts.body) }));
  if (opts.before) await opts.before(page);
  await openDemo(page, { voices: opts.voices ?? [], lang: opts.lang ?? 'mn', ...(opts.cfg ?? {}) });
  // Logic replays: the map canvas is not painted (visibility only; tiles still load, layout unchanged). Software WebGL
  // painting dominates the wall time on this machine (measured 0.6 s vs 0.2 s per replay second).
  if (opts.hideCanvas !== false) await hideCanvas(page);
  await selectRoute(page, opts.label);
  await startReplay(page, opts.period ?? 1000);
  const total = opts.seconds ?? ROUTES[opts.label].length + 15;
  for (let s = 0; s < total; s += 10) {
    await page.clock.runFor(10_000);
    if (opts.during) await opts.during(page, s + 10);
  }
  const log = await qa(page);
  const storage = await page.evaluate(() => ({ local: { ...localStorage }, session: { ...sessionStorage }, cookie: document.cookie, url: location.href, sw: !!navigator.serviceWorker?.controller }));
  const out = { log, reqs, errors, storage, page, ctx };
  return out;
}

// ---------------------------------------------------------------- one browser per test
// Software WebGL in a long-lived browser grows the GPU process (3.7 GB after a few replays) and slowed one replay
// second from ~0.4 s to ~3 s of wall time. Every NAV-017 browser test therefore gets its own browser: `context`/`page`
// come from a browser launched for that test (project options kept), and `freshBrowser` is the same for tests that
// create several contexts.
import { test as base } from '@playwright/test';
export const projectContextOptions = (ti, extra = {}) => { const { defaultBrowserType, ...use } = ti.project.use; delete use.trace; delete use.serviceWorkers; return { ...use, serviceWorkers: 'allow', ...extra }; };
export const test = base.extend({
  reducedMotion: [null, { option: true }],
  freshBrowser: async ({ playwright, browserName }, use) => {
    const b = await playwright[browserName].launch();
    await use(b);
    await b.close();
  },
  context: async ({ freshBrowser, reducedMotion }, use, ti) => {
    const ctx = await freshBrowser.newContext(projectContextOptions(ti, reducedMotion ? { reducedMotion } : {}));
    await use(ctx);
    await ctx.close();
  },
});

// NAV-017 assertion helpers shared by the replay tests. Each returns a list of problems (empty = pass), so one replay
// can feed several AC-level tests. Oracles: the recorded response and GPX (RouteOracle), the NAV-005 golden set, the
// NAV-004 AC 23–27 transcriptions. Never web/src.
import { RouteOracle, ROUTES, alongAt, readGpx, readJson, statedDistance, voiceScanProblems, ac27Text, ac28Problems, allowedTexts, fmtDistance, fmtDuration, nb, intersects, golden, CYRILLIC } from './helpers.mjs';

export function oracleFor(label, track = null) {
  const r = ROUTES[label];
  const o = new RouteOracle(readJson(r.route));
  const t = track ?? readGpx(r.track);
  return { o, track: t, alongs: o.alongTrack(t) };
}

/** AC 26 cross-platform check: (manoeuvre, text) sequence equals the golden rows exactly; each start within ± 2 s. */
export function goldenProblems(spokenList, track, lang) {
  const g = golden(track, lang);
  const p = [];
  if (g.length === 0) return [`no golden rows for ${track} ${lang}`];
  const n = Math.max(g.length, spokenList.length);
  for (let i = 0; i < n; i++) {
    const a = spokenList[i], e = g[i];
    if (!a) { p.push(`#${i} missing: golden ${e.t} s «${e.text}»`); continue; }
    if (!e) { p.push(`#${i} extra: ${a.t.toFixed(1)} s «${a.text}»`); continue; }
    if (a.text !== e.text) p.push(`#${i} text «${a.text}» != golden «${e.text}» (m ${e.m})`);
    if (Math.abs(a.t - e.t) > 2) p.push(`#${i} «${a.text}» at ${a.t.toFixed(1)} s, golden ${e.t} s (Δ ${(a.t - e.t).toFixed(1)} s > 2 s)`);
  }
  return p;
}

/**
 * AC 26 oracle bullets on the prompts that match golden rows (so the manoeuvre index is known):
 *  - at most once; - stated distance within max(30 m, 20 %) of the true remaining distance at its start;
 *  - no prompt for a manoeuvre already passed; - car: each manoeuvre except depart has a prompt 20–250 m ahead
 *    (D68: not for an arrive whose final step is < 30 m).
 */
export function scheduleProblems(spokenList, label, track, lang, mode, oracle = oracleFor(label)) {
  const { o, alongs } = oracle;
  // manoeuvre index of prompt i: from the golden row of this language when its text matches, else from the mn row of the
  // same track at the same position (the demo plays the mn recording in both UI languages, story › Demo routes)
  const gl = golden(track, lang), gm = golden(track, 'mn');
  const g = spokenList.map((s, i) => (gl[i]?.text === s.text ? gl[i] : gm.length === spokenList.length ? { ...gm[i], text: s.text } : undefined));
  const p = [];
  const seen = new Set();
  const within = new Map();
  spokenList.forEach((s, i) => {
    let m = g[i]?.m;
    if (m === undefined) {
      // no golden rows (G4): the next manoeuvre ahead of the position (arrival texts: the last one)
      const a = alongAt(alongs, s.t * 1000);
      m = /очих газар|destination|arrived/i.test(s.text) && !/метрт|meters/.test(s.text) ? o.steps.length - 1 : i === 0 ? 0 : o.maneuverAlong.findIndex((x, k) => k > 0 && x > a - 15);
      if (m < 0) m = o.steps.length - 1;
    }
    const key = `${m}|${s.text}`;
    if (seen.has(key)) p.push(`#${i} «${s.text}» produced twice`);
    seen.add(key);
    const along = alongAt(alongs, s.t * 1000);
    const target = m === o.steps.length - 1 ? o.length : o.maneuverAlong[m];
    const remaining = target - along;
    if (m > 0 && remaining < -15) p.push(`#${i} «${s.text}» for manoeuvre ${m} already passed (${remaining.toFixed(0)} m)`);
    const d = statedDistance(s.text);
    if (d !== null && m > 0) {
      const tol = Math.max(30, 0.2 * remaining);
      if (Math.abs(d - remaining) > tol) p.push(`#${i} «${s.text}» states ${d} m, true remaining ${remaining.toFixed(0)} m (tolerance ${tol.toFixed(0)} m)`);
    }
    if (m > 0 && remaining >= 20 && remaining <= 250) within.set(m, true);
    // a chained prompt (navigation-ux §4.4, A13 «…, дараа нь …» / "…, then …") also announces manoeuvre m + 1
    if (/, дараа нь |, then /.test(s.text) && m + 1 < o.steps.length) {
      const r2 = o.maneuverAlong[m + 1] - along;
      if (r2 >= 20 && r2 <= 250) within.set(m + 1, true);
    }
  });
  if (mode === 'car') {
    for (let m = 1; m < o.steps.length; m++) {
      if (o.maneuverAlong[m] < alongs[0].along + 20) continue; // already passed (or too close) when the track starts
      const isArrive = o.steps[m].maneuver.type === 'arrive';
      const lastStepLen = o.steps[m - 1].distance;
      if (isArrive && lastStepLen < 30) continue; // D68
      if (!within.get(m)) p.push(`car manoeuvre ${m} (${o.steps[m].maneuver.type} ${o.steps[m].maneuver.modifier ?? ''}) has no prompt starting 20–250 m ahead`);
    }
  }
  return p;
}

/** AC 24/25 voice scan (Mongolian UI) on every spoken text; English: no Cyrillic, no "1000 meters". */
export function voiceTextProblems(spokenList, lang) {
  const p = [];
  for (const s of spokenList) {
    if (lang === 'mn') for (const q of voiceScanProblems(s.text)) p.push(`«${s.text}»: ${q}`);
    else {
      if (CYRILLIC.test(s.text)) p.push(`"${s.text}": Cyrillic in English speech`);
      if (/1000 meters/.test(s.text)) p.push(`"${s.text}": 1000 meters (D67)`);
    }
  }
  return p;
}

/**
 * AC 17/18/19/20 from the 1 s records and the banner mutation log:
 *  - banner text = AC 27 text of the next manoeuvre (oracle), never blank, sequence in route order;
 *  - distance in AC 23 format and within max(25 m, 10 %) + one fix of travel of the oracle distance;
 *  - street line = step name of the step after the manoeuvre (zero-width removed), omitted when empty;
 *  - next manoeuvre shown ≤ 1 s after the first fix past the manoeuvre.
 */
export function bannerProblems(log, label, lang, oracle = oracleFor(label)) {
  const { o, alongs, track } = oracle;
  const p = [];
  const allowed = allowedTexts(lang);
  const steps = o.steps;
  const expected = steps.map((s) => ac27Text(s.maneuver, lang));
  const recs = log.rec.filter((r) => r.t >= 0);
  // never blank, every text allowed
  for (const r of recs) {
    if (r.arrival === null && !r.text) p.push(`${(r.t / 1000).toFixed(1)} s: banner blank`);
    if (r.text && !allowed.has(r.text)) p.push(`${(r.t / 1000).toFixed(1)} s: "${r.text}" is not an AC 27 ${lang} text`);
    if (lang === 'mn' && r.text) for (const q of ac28Problems(r.text)) if (q !== 'not an AC 27 text') p.push(`${(r.t / 1000).toFixed(1)} s: "${r.text}": ${q}`);
    if (lang === 'en' && r.text && CYRILLIC.test(r.text)) p.push(`${(r.t / 1000).toFixed(1)} s: Cyrillic in English banner "${r.text}"`);
  }
  // per-sample manoeuvre check (skip samples within 2 s of a manoeuvre)
  for (const r of recs) {
    if (r.variant !== 'maneuver' || !r.text) continue;
    const along = alongAt(alongs, r.t);
    let k = o.maneuverAlong.findIndex((a, i) => i > 0 && a > along + 1);
    if (k < 0) k = steps.length - 1;
    const near = alongs.some((a) => Math.abs(a.tMs - r.t) <= 2000 && o.maneuverAlong.some((m, i) => i > 0 && Math.abs(m - a.along) < 40));
    if (near) continue;
    const accepted = new Set([expected[k]]);
    // a very short following step may be skipped by the client (one fix covers it): accept the next one too
    if (k + 1 < steps.length && steps[k].distance < 40) accepted.add(expected[k + 1]);
    if (!accepted.has(r.text)) p.push(`${(r.t / 1000).toFixed(1)} s: banner "${r.text}", expected manoeuvre ${k} "${expected[k]}"`);
    // distance
    const remaining = o.maneuverAlong[k] - along;
    const shown = nb(r.dist);
    const fmtOk = /^(\d+ (м|m)|\d+(,\d)? км|\d+(\.\d)? km)$/.test(shown);
    if (!fmtOk) p.push(`${(r.t / 1000).toFixed(1)} s: distance "${shown}" not in AC 23 format`);
    const m = shown.match(/^([\d,.]+) (м|m|км|km)$/);
    if (m) {
      const v = parseFloat(m[1].replace(',', '.')) * (/к|k/.test(m[2]) ? 1000 : 1);
      const speedish = 20; // up to one fix of travel at ≤ 72 km/h
      const tol = Math.max(25, 0.1 * remaining) + speedish;
      if (Math.abs(v - remaining) > tol) p.push(`${(r.t / 1000).toFixed(1)} s: distance ${shown} vs oracle ${remaining.toFixed(0)} m`);
      if (lang === 'mn' && fmtDistance(Math.max(0, remaining), lang).includes(',') !== shown.includes(',') && remaining > 1100 && Math.abs(v - remaining) > 150) p.push(`${(r.t / 1000).toFixed(1)} s: decimal format`);
    }
    // street line
    const exp = (steps[k].name ?? '').replace(/[​‌‍﻿]/g, '').trim();
    const got = r.street === null ? '' : r.street;
    if (got !== exp && !(k + 1 < steps.length && steps[k].distance < 40)) p.push(`${(r.t / 1000).toFixed(1)} s: street "${got}" expected "${exp}"`);
    if (/[​‌‍﻿]/.test(got)) p.push(`${(r.t / 1000).toFixed(1)} s: zero-width in street`);
  }
  // AC 20: next manoeuvre within 1 s after the first fix past manoeuvre k. A step switch is a banner text change or an
  // upward jump of the banner distance (two consecutive manoeuvres can have the same text, R2). Switches are assigned to
  // manoeuvres in order, each after the position came within 30 m of that manoeuvre (Ferrostar's entry distance).
  const parse = (txt) => { const m = nb(txt).match(/^([\d,.]+) (м|m|км|km)$/); return m ? parseFloat(m[1].replace(',', '.')) * (/к|k/.test(m[2]) ? 1000 : 1) : null; };
  const events = [];
  for (const c of log.banners) events.push({ t: c.t, kind: 'text' });
  let prevD = null;
  for (const d of log.dists ?? []) { const v = parse(d.text); if (v !== null && prevD !== null && v > prevD + 15) events.push({ t: d.t, kind: 'dist' }); if (v !== null) prevD = v; }
  for (const v of log.variants ?? []) if (v.v === 'arrival') events.push({ t: v.t, kind: 'arrival' });
  events.sort((a, b) => a.t - b.t);
  const sw = events.filter((e, i) => i === 0 || e.t - events[i - 1].t > 300);
  let last = -1;
  for (let k = 1; k < steps.length - 1; k++) {
    const entry = alongs.find((a) => a.along >= o.maneuverAlong[k] - 30);
    const pass = alongs.find((a) => a.along >= o.maneuverAlong[k] + 1);
    if (!entry || !pass) continue;
    const e = sw.find((x) => x.t >= entry.tMs - 50 && x.t > last);
    if (!e) { p.push(`no banner switch after manoeuvre ${k}`); continue; }
    last = e.t;
    if (steps[k].distance < 40 && k + 1 < steps.length - 1) continue; // a very short step: timing not judged
    if (e.t > pass.tMs + 1000 + 150) p.push(`AC 20: banner switched to manoeuvre ${k + 1} "${expected[k + 1]}" ${((e.t - pass.tMs) / 1000).toFixed(1)} s after the first fix past manoeuvre ${k} (${(o.maneuverAlong[k]).toFixed(0)} m; position then ${(alongAt(alongs, e.t) - o.maneuverAlong[k]).toFixed(1)} m past it)`);
  }
  return [...new Set(p)].slice(0, 40);
}

/** AC 21: ETA, remaining time and distance vs the oracle; recomputed at least every 5 s while moving. */
export function progressProblems(log, label, lang, startDate, oracle = oracleFor(label)) {
  const { o, alongs } = oracle;
  const p = [];
  const steps = o.steps;
  const recs = log.rec.filter((r) => r.progress && r.remaining);
  for (const r of recs) {
    const along = alongAt(alongs, r.t);
    let j = 0;
    while (j + 1 < steps.length && o.maneuverAlong[j + 1] <= along) j++;
    const stepLen = Math.max(1, (o.maneuverAlong[j + 1] ?? o.length) - o.maneuverAlong[j]);
    const frac = Math.min(1, Math.max(0, (along - o.maneuverAlong[j]) / stepLen));
    let dur = (1 - frac) * steps[j].duration;
    for (let k = j + 1; k < steps.length; k++) dur += steps[k].duration;
    const dist = o.length - along;
    const [timeTxt, distTxt] = nb(r.remaining).split(' · ');
    const okTimes = new Set([fmtDuration(Math.max(0, dur - 45), lang), fmtDuration(dur, lang), fmtDuration(dur + 45, lang)]);
    if (!okTimes.has(timeTxt)) p.push(`${(r.t / 1000).toFixed(0)} s: remaining time "${timeTxt}", oracle ${dur.toFixed(0)} s → ${fmtDuration(dur, lang)}`);
    const m = (distTxt ?? '').match(/^([\d,.]+) (м|m|км|km)$/);
    if (!m) p.push(`${(r.t / 1000).toFixed(0)} s: remaining distance "${distTxt}" not in AC 23 format`);
    else {
      const v = parseFloat(m[1].replace(',', '.')) * (/к|k/.test(m[2]) ? 1000 : 1);
      if (Math.abs(v - dist) > Math.max(60, 0.06 * dist)) p.push(`${(r.t / 1000).toFixed(0)} s: remaining distance ${distTxt} vs oracle ${dist.toFixed(0)} m`);
    }
    // ETA = device clock + remaining duration (rounded to the minute), HH:MM
    const etaTxt = nb(r.eta);
    const label_ = lang === 'mn' ? 'Хүрэх цаг' : 'Arrive at';
    const mm = etaTxt.match(new RegExp(`^${label_} (\\d{2}):(\\d{2})( \\+\\d+ (өдөр|days?))?$`));
    if (!mm) p.push(`${(r.t / 1000).toFixed(0)} s: ETA "${etaTxt}" not «${label_} HH:MM»`);
    else {
      const now = new Date(startDate.getTime() + r.t);
      const exp = new Date(now.getTime() + dur * 1000);
      const shownMin = Number(mm[1]) * 60 + Number(mm[2]);
      const fmt = (d) => { const s = d.toLocaleString('en-GB', { timeZone: 'Asia/Ulaanbaatar', hour: '2-digit', minute: '2-digit', hour12: false }); const [h, mi] = s.split(':').map(Number); return h * 60 + mi; };
      const cands = [-60, 0, 60].map((dt) => fmt(new Date(exp.getTime() + dt * 1000 + 30_000 - ((exp.getTime() + 30_000) % 60_000))));
      if (!cands.some((c) => Math.abs(c - shownMin) <= 1)) p.push(`${(r.t / 1000).toFixed(0)} s: ETA ${mm[1]}:${mm[2]}, oracle ${exp.toISOString()}`);
    }
    // recomputed at least every 5 s: the shown remaining distance equals the formatted oracle value of some moment in
    // the last 5.5 s (± 35 m slack: one fix of travel, the snapped vs. projected position and Ferrostar step-distance jumps of up to 30 m seen on R1)
    if (m && dist > 50) {
      const cands = new Set();
      for (let dt = 0; dt <= 5500; dt += 250) {
        const d2 = o.length - alongAt(alongs, Math.max(0, r.t - dt));
        for (const e of [-35, -25, -12, 0, 12, 25, 35]) cands.add(fmtDistance(Math.max(0, d2 + e), lang));
      }
      if (!cands.has(nb(distTxt))) p.push(`${(r.t / 1000).toFixed(0)} s: remaining distance ${distTxt} is older than 5 s (oracle now ${fmtDistance(dist, lang)})`);
    }
  }
  return [...new Set(p)].slice(0, 40);
}

/** AC 14 / 22 / 29 / 37: badge visible all replay, nothing covers banner, progress, attribution; puck in the uncovered map. */
export function layoutDuringReplayProblems(log, { checkPuck = true } = {}) {
  const p = [];
  const recs = log.rec.filter((r) => r.t >= 0 && (r.progress || r.arrival !== null));
  for (const r of recs) {
    const T = `${(r.t / 1000).toFixed(0)} s`;
    if (!r.badge) p.push(`${T}: badge «Туршилтын горим» not visible`);
    const R = r.rects;
    for (const [a, b] of [['badge', 'banner'], ['badge', 'progress'], ['badge', 'attribution'], ['messages', 'banner'], ['messages', 'progress'], ['messages', 'attribution'], ['progress', 'attribution'], ['arrival', 'attribution'], ['banner', 'attribution']]) {
      if (intersects(R[a], R[b])) p.push(`${T}: ${a} intersects ${b}`);
    }
    if (checkPuck && r.puck && r.progress) {
      const [l, t, rr, bt] = r.puck;
      const cx = (l + rr) / 2, cy = (t + bt) / 2;
      const topEdge = Math.max(R.top?.[3] ?? 0);
      const bottomEdge = Math.min(...[R.progress?.[1], R.messages?.[1], R.arrival?.[1]].filter((v) => v !== undefined && v !== null));
      const leftEdge = R.top && R.top[3] > 0.6 * (R.attribution?.[1] ?? 800) ? R.top[2] : 0; // column arrangement
      if (!(cy > topEdge && cy < bottomEdge && cx > leftEdge)) p.push(`${T}: puck centre (${cx.toFixed(0)}, ${cy.toFixed(0)}) not in the uncovered map (top ${topEdge.toFixed(0)}, bottom ${bottomEdge.toFixed(0)}, left ${leftEdge.toFixed(0)})`);
    }
  }
  return [...new Set(p)].slice(0, 30);
}

/** AC 38: each new banner instruction announced once through the polite live region; nothing else. */
export function liveRegionProblems(log) {
  const p = [];
  const ann = log.live.filter((x) => x.text);
  const changes = [{ t: 0, text: log.rec[0]?.text ?? '' }];
  for (const c of log.banners) if (changes.at(-1)?.text !== c.text) changes.push(c);
  // the first banner after «Эхлэх» is read through focus, not the live region
  const expected = changes.slice(1).map((c) => c.text);
  // two consecutive manoeuvres with the same text (R2: two right turns) are one banner text change but two announcements:
  // compare with consecutive duplicates collapsed
  const got = ann.map((a) => a.text).filter((t, i, a) => i === 0 || a[i - 1] !== t);
  if (got.length !== expected.length || got.some((t, i) => t !== expected[i])) p.push(`live region announced ${JSON.stringify(got)}; banner changes after the first: ${JSON.stringify(expected)}`);
  for (const a of ann) if (/\d/.test(a.text) && /(м|km|км|m)$/.test(a.text)) p.push(`distance announced: ${a.text}`);
  return p;
}

// NAV-003 AC 14 (golden query set, live gateway), and on every golden row the request plan of AC 3, 15 and 16 as amended
// 2026-09-30 (PO approval F3, D33 = ADR-0006 §2.3 rules A–D): at most 2 requests; a second one only for the Latin
// transliteration (15 a, parallel), the ү/ө variant after an empty Cyrillic result (15 b), or the query as typed next to
// an abbreviation expansion (16); merged list without duplicate osm_type+osm_id; no q longer than 200 (16, F4).
// Fixture: fixtures/golden-set.json (story "Golden query set"; B15 amended by F1, B10 and C6 by F2).
// Rate: <= 2 search requests/s (rows are paced).
import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GOLDEN, T, TYPE_LABELS, expectedPlan, haversine, jumpTo, openApp, options, params, tid, typeQuery, view, waitSettled } from './helpers.mjs';

const OUT = fileURLToPath(new URL('../test-results/nav003/', import.meta.url));
const LATIN_ONLY = (s) => /\p{L}/u.test(s) && [...s.matchAll(/\p{L}/gu)].every((m) => /\p{Script=Latin}/u.test(m[0]));
const CYR = /[Ѐ-ӿ]/;

function refOf(bias) {
  if (Array.isArray(bias)) return { lat: bias[0], lng: bias[1] };
  const r = GOLDEN.refs[bias];
  return { lat: r[0], lng: r[1] };
}

/** Evaluates one row. Returns { pass, detail }. */
function evaluate(row, res) {
  const e = row.expect;
  if (e.coordinateOption) {
    const ok = res.dom.length === 1 && res.dom[0].kind === 'coordinate' && res.dom[0].name === T.mn.selectedPoint && res.requests.length === 0;
    return { pass: ok, detail: `options=${res.dom.length} kind=${res.dom[0]?.kind} requests=${res.requests.length}` };
  }
  if (e.noResults) return { pass: res.state === 'no-results' && res.stateText === T[row.ui].noResults, detail: `state=${res.state} «${res.stateText}»` };
  const top = res.opts.slice(0, e.top);
  const hits = top.map((o, i) => {
    const dom = res.dom[i] ?? {};
    const conds = [];
    if (e.near) conds.push(haversine(refOf(e.near), { lat: o.coords[1], lng: o.coords[0] }) <= e.m);
    if (e.type) conds.push(dom.type === e.type || (row.ui === 'en' && e.type === 'Дүүрэг' && dom.type === 'District'));
    if (e.nameContains) conds.push(new RegExp(e.nameContains, e.flags ?? '').test(dom.name ?? ''));
    if (e.nameOrContextContains) conds.push(new RegExp(e.nameOrContextContains, e.flags ?? '').test(`${dom.name ?? ''} ${dom.context ?? ''}`));
    return conds.every(Boolean);
  });
  const rank = hits.indexOf(true);
  const listing = res.opts.slice(0, Math.max(e.top, 3)).map((o, i) => {
    const d = e.near ? ` ${Math.round(haversine(refOf(e.near), { lat: o.coords[1], lng: o.coords[0] }))}m` : '';
    return `${i + 1}.${res.dom[i]?.name}[${res.dom[i]?.type}]${d}`;
  });
  return { pass: rank >= 0, detail: `${rank >= 0 ? `hit #${rank + 1}` : 'miss'}; ${listing.join(' | ') || `state=${res.state}`}` };
}

test('AC14/AC15/AC16 (live): golden query set, tiers A (all), B (>= 80 % of counted rows), C (baseline)', async ({ page }) => {
  test.setTimeout(420_000);
  await openApp(page);
  let lang = 'mn';
  const results = {};
  const problems15 = [];
  const problems16 = [];
  const problems3 = [];
  for (const row of GOLDEN.rows) {
    if (row.ui !== lang) {
      await tid(page, 'language-toggle').click();
      lang = row.ui;
      await page.waitForTimeout(300);
    }
    await jumpTo(page, refOf(row.bias), row.bias === 'X1' || Array.isArray(row.bias) ? 11 : 13);
    const before = await page.evaluate(() => window.__req.length);
    await typeQuery(page, row.q);
    await waitSettled(page, row.q, 12_000).catch(() => {});
    await page.waitForTimeout(150);
    const v = await view(page);
    const dom = await options(page);
    const reqRecs = (await page.evaluate((b) => window.__req.slice(b), before)).filter((r) => r.op === 'search');
    const requests = reqRecs.map((r) => params(r.url));
    const stateText = await page.locator('#search-state-text').textContent();
    const inputValue = await tid(page, 'search-input').inputValue();
    const res = { state: v.state, stateText, opts: v.options.filter((o) => o.kind === 'place'), dom, requests };
    const ev = evaluate(row, res);
    const plan = row.expect.coordinateOption ? null : expectedPlan(row.q);
    results[row.id] = {
      ...row, ...ev, plan: plan?.rule ?? 'coordinate', requests: requests.map((p) => p.q), lang: requests.map((p) => p.lang), counts: reqRecs.map((r) => r.count), dom: dom.slice(0, 5),
      top3: res.opts.slice(0, 3).map((o, i) => ({ name: dom[i]?.name, type: dom[i]?.type, osm_key: o.props.osm_key, osm_value: o.props.osm_value, type_photon: o.props.type, osm: `${o.props.osm_type}${o.props.osm_id}` })),
    };
    const qs = requests.map((p) => p.q);
    const keys = res.opts.map((o) => `${o.props.osm_type}${o.props.osm_id}`);

    // AC 3: at most 2 requests per settled query; AC 16 / openapi: no q longer than 200
    if (requests.length > 2) problems3.push(`${row.id}: ${requests.length} requests`);
    if (qs.some((q) => q.length > 200)) problems16.push(`${row.id}: q longer than 200`);
    if (plan?.rule === 'A') {
      // AC 16: expansion AND the query as typed, in parallel (2 requests); the input still shows what the user typed
      if (!qs.includes(plan.primary)) problems16.push(`${row.id}: expanded «${plan.primary}» not sent (${qs.join(' / ')})`);
      if (!qs.includes(plan.secondary)) problems16.push(`${row.id}: as-typed «${plan.secondary}» not sent (${qs.join(' / ')})`);
      if (qs.length !== 2) problems16.push(`${row.id}: ${qs.length} requests, expected 2 (expanded + as typed)`);
      if (inputValue !== row.q) problems16.push(`${row.id}: input shows «${inputValue}»`);
      if (new Set(keys).size !== keys.length) problems16.push(`${row.id}: duplicate osm_type+osm_id in the merged list`);
    } else if (plan?.rule === 'B') {
      // AC 15 (a): as typed + exactly one Cyrillic transliteration, in parallel; merged list without duplicates
      if (qs[0] !== row.q && !qs.includes(row.q)) problems15.push(`${row.id}: as-typed query not sent (${qs.join(' / ')})`);
      const extra = qs.filter((q) => q !== row.q);
      if (extra.length !== 1 || !CYR.test(extra[0] ?? '')) problems15.push(`${row.id}: expected exactly one Cyrillic extra request, got ${JSON.stringify(extra)}`);
      if (reqRecs.length === 2 && reqRecs[1].t > (reqRecs[0].end ?? Infinity)) problems15.push(`${row.id}: transliteration sent after the first response (not parallel)`);
      if (new Set(keys).size !== keys.length) problems15.push(`${row.id}: duplicate osm_type+osm_id in the merged list`);
    } else if (plan?.rule === 'C') {
      // AC 15 (b): as typed first; the ү/ө variant only after a 200 with zero features
      if (qs[0] !== row.q) problems15.push(`${row.id}: first request «${qs[0]}» is not the query as typed`);
      if (qs.length === 2 && qs[1] !== plan.secondary) problems15.push(`${row.id}: second request «${qs[1]}» is not the ү/ө variant «${plan.secondary}»`);
      if (qs.length === 2 && !(reqRecs[0].outcome === 200 && reqRecs[0].count === 0)) problems15.push(`${row.id}: ү/ө variant sent although the first response was ${reqRecs[0].outcome} with ${reqRecs[0].count} features`);
      if (qs.length === 1 && reqRecs[0].outcome === 200 && reqRecs[0].count === 0) problems15.push(`${row.id}: first response empty but no ү/ө variant sent`);
    } else if (plan?.rule === 'D') {
      // AC 15: any other text query sends 1 request, as typed
      if (qs.length !== 1 || qs[0] !== row.q) problems15.push(`${row.id}: expected exactly 1 request «${row.q}», got ${JSON.stringify(qs)}`);
    }
    await tid(page, 'search-clear').click().catch(() => {});
    // <= 2 requests/s: 2 requests -> >= 1.1 s before the next row
    await page.waitForTimeout(Math.max(700, requests.length * 560));
  }

  // Tier evaluation ("x/y = z%" as the orchestrator reports it)
  const A = GOLDEN.rows.filter((r) => r.tier === 'A');
  const B = GOLDEN.rows.filter((r) => r.tier === 'B');
  const failedA = A.filter((r) => !results[r.id].pass).map((r) => `${r.id} «${r.q}»: ${results[r.id].detail}`);
  const counted = B.filter((r) => !r.control || results[r.control].pass);
  const gaps = B.filter((r) => r.control && !results[r.control].pass).map((r) => r.id);
  const passedB = counted.filter((r) => results[r.id].pass);
  const shareB = counted.length ? passedB.length / counted.length : 1;
  // AC 14 counting rule (story, no rounding): pass when passed × 5 ≥ counted × 4 (integer arithmetic)
  const passB = passedB.length * 5 >= counted.length * 4;
  // C4: context line of A2 / A6 top options; C5: share of Latin names among A/B top-3 options in the mn UI
  const c4 = ['A2', 'A6'].map((id) => `${id}: ${results[id].dom.slice(0, 3).map((d) => `${d.name} — «${d.context}»`).join(' | ')}`);
  const mnNames = [...A, ...B].filter((r) => r.ui === 'mn').flatMap((r) => results[r.id].dom.slice(0, 3).map((d) => d.name ?? ''));
  const latin = mnNames.filter((n) => LATIN_ONLY(n));
  // C6 / AC 19 same-row risk (R13): every OSM object in the top 3 of both an en and an mn C6 row; recorded, never fails (tier C)
  const rowOf = (label, ui) => TYPE_LABELS.find((t) => t[ui === 'en' ? 2 : 1] === label)?.[0] ?? null;
  const c6Seen = {};
  for (const r of GOLDEN.rows.filter((x) => x.expect.c6)) {
    for (const o of results[r.id].top3) (c6Seen[o.osm] ??= {})[r.ui] ??= { row: r.id, name: o.name, label: o.type, rule: rowOf(o.type, r.ui), photon: `${o.osm_key}/${o.osm_value}/type=${o.type_photon}` };
  }
  const c6Same = Object.entries(c6Seen).filter(([, v]) => v.en && v.mn).map(([osm, v]) => `${osm} ${v.photon}: en ${v.en.row} "${v.en.name}" [${v.en.label}] row ${v.en.rule} / mn ${v.mn.row} «${v.mn.name}» [${v.mn.label}] row ${v.mn.rule} → ${v.en.rule === v.mn.rule ? 'same row' : 'DIFFERENT ROW'}`);
  const totalRequests = Object.values(results).reduce((n, r) => n + r.requests.length, 0);
  const summary = {
    run: new Date().toISOString(),
    tierA: `${A.length - failedA.length}/${A.length} = ${(((A.length - failedA.length) / A.length) * 100).toFixed(0)}%`,
    failedA,
    tierB: `${passedB.length}/${counted.length} = ${(shareB * 100).toFixed(0)}% of counted rows (threshold 80 %, fixed; ${passedB.length}×5 ${passB ? '≥' : '<'} ${counted.length}×4); data gaps (control failed, not counted): ${gaps.join(', ') || 'none'}`,
    failedB: counted.filter((r) => !results[r.id].pass).map((r) => `${r.id} «${r.q}»: ${results[r.id].detail}`),
    tierC: GOLDEN.rows.filter((r) => r.tier === 'C').map((r) => `${r.id} «${r.q}»: ${results[r.id].dom.slice(0, 5).map((d, i) => `${i + 1}.${d.name}[${d.type}] «${d.context}»`).join(' | ')}`),
    C6: GOLDEN.rows.filter((r) => r.expect.c6).map((r) => `${r.id} «${r.q}» (${r.ui}): ${results[r.id].top3.map((o, i) => `${i + 1}.${o.name}[${o.type}] ${o.osm_key}/${o.osm_value}/type=${o.type_photon} ${o.osm}`).join(' | ')}`),
    C6sameObject: c6Same,
    totalSearchRequests: totalRequests,
    C4: c4,
    C5: `${latin.length}/${mnNames.length} top-3 names in the mn UI are Latin-only${latin.length ? ': ' + [...new Set(latin)].join(', ') : ''}`,
    problemsAC3: problems3,
    problemsAC15: problems15,
    problemsAC16: problems16,
    rows: results,
  };
  mkdirSync(OUT, { recursive: true });
  writeFileSync(OUT + 'golden-latest.json', JSON.stringify(summary, null, 1));
  for (const k of ['tierA', 'failedA', 'tierB', 'failedB', 'C4', 'C5', 'C6', 'C6sameObject', 'totalSearchRequests', 'problemsAC3', 'problemsAC15', 'problemsAC16']) {
    test.info().annotations.push({ type: k, description: JSON.stringify(summary[k]) });
  }
  for (const r of GOLDEN.rows) test.info().annotations.push({ type: `row ${r.id}`, description: `${results[r.id].pass ? 'PASS' : 'FAIL'} «${r.q}» rule=${results[r.id].plan} req=${JSON.stringify(results[r.id].requests)} counts=${JSON.stringify(results[r.id].counts)} ${results[r.id].detail}` });

  expect.soft(problems3, 'AC 3: <= 2 requests per settled query').toEqual([]);
  expect.soft(problems15, 'AC 15').toEqual([]);
  expect.soft(problems16, 'AC 16').toEqual([]);
  expect.soft(failedA, 'AC 14 tier A: every row passes').toEqual([]);
  expect(passB, `AC 14 tier B: ${summary.tierB}`).toBe(true);
});

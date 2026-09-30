// NAV-003 AC 14 (golden query set, live gateway), AC 15 (Latin + transliteration, <= 2 requests, no duplicates),
// AC 16 (district abbreviations expanded), AC 3 (<= 2 requests per settled query) on every golden row.
// Fixture: fixtures/golden-set.json (story "Golden query set"). Rate: <= 2 search requests/s (rows are paced).
import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GOLDEN, T, haversine, jumpTo, openApp, options, params, tid, typeQuery, view, waitSettled } from './helpers.mjs';

const OUT = fileURLToPath(new URL('../test-results/nav003/', import.meta.url));
const ABBR = { БЗД: 'Баянзүрх дүүрэг', ЧД: 'Чингэлтэй дүүрэг', ХУД: 'Хан-Уул дүүрэг', СБД: 'Сүхбаатар дүүрэг', БГД: 'Баянгол дүүрэг', СХД: 'Сонгинохайрхан дүүрэг' };
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
    if (e.nameContains) conds.push(new RegExp(e.nameContains).test(dom.name ?? ''));
    if (e.nameOrContextContains) conds.push(new RegExp(e.nameOrContextContains).test(`${dom.name ?? ''} ${dom.context ?? ''}`));
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
    const requests = (await page.evaluate((b) => window.__req.slice(b), before)).filter((r) => r.op === 'search').map((r) => params(r.url));
    const stateText = await page.locator('#search-state-text').textContent();
    const inputValue = await tid(page, 'search-input').inputValue();
    const res = { state: v.state, stateText, opts: v.options.filter((o) => o.kind === 'place'), dom, requests };
    const ev = evaluate(row, res);
    results[row.id] = { ...row, ...ev, requests: requests.map((p) => p.q), lang: requests.map((p) => p.lang), dom: dom.slice(0, 5) };

    // AC 3: at most 2 requests per settled query
    if (requests.length > 2) problems3.push(`${row.id}: ${requests.length} requests`);
    // AC 15: Latin-only query -> as typed + at most one Cyrillic transliteration; merged list without duplicates
    if (LATIN_ONLY(row.q)) {
      const qs = requests.map((p) => p.q);
      if (!qs.includes(row.q)) problems15.push(`${row.id}: as-typed query not sent (${qs.join(' / ')})`);
      const extra = qs.filter((q) => q !== row.q);
      if (extra.length > 1 || extra.some((q) => !CYR.test(q))) problems15.push(`${row.id}: extra requests ${JSON.stringify(extra)}`);
      const keys = res.opts.map((o) => `${o.props.osm_type}${o.props.osm_id}`);
      if (new Set(keys).size !== keys.length) problems15.push(`${row.id}: duplicate osm_type+osm_id in the merged list`);
    }
    // AC 16: abbreviation token expanded before sending; the input still shows what the user typed
    const tok = row.q.split(/\s+/).find((w) => ABBR[w.toUpperCase()]);
    if (tok) {
      const expanded = row.q.replace(tok, ABBR[tok.toUpperCase()]);
      if (!requests.some((p) => p.q === expanded)) problems16.push(`${row.id}: expanded «${expanded}» not sent (${requests.map((p) => p.q).join(' / ')})`);
      if (inputValue !== row.q) problems16.push(`${row.id}: input shows «${inputValue}»`);
    }
    await tid(page, 'search-clear').click().catch(() => {});
    // <= 2 requests/s: 2 requests -> >= 1.1 s before the next row
    await page.waitForTimeout(Math.max(700, requests.length * 560));
  }

  // Tier evaluation
  const A = GOLDEN.rows.filter((r) => r.tier === 'A');
  const B = GOLDEN.rows.filter((r) => r.tier === 'B');
  const failedA = A.filter((r) => !results[r.id].pass).map((r) => `${r.id} «${r.q}»: ${results[r.id].detail}`);
  const counted = B.filter((r) => !r.control || results[r.control].pass);
  const gaps = B.filter((r) => r.control && !results[r.control].pass).map((r) => r.id);
  const passedB = counted.filter((r) => results[r.id].pass);
  const shareB = counted.length ? passedB.length / counted.length : 1;
  // C4: context line of A2 / A6 top options; C5: share of Latin names among A/B top-3 options in the mn UI
  const c4 = ['A2', 'A6'].map((id) => `${id}: ${results[id].dom.slice(0, 3).map((d) => `${d.name} — «${d.context}»`).join(' | ')}`);
  const mnNames = [...A, ...B].filter((r) => r.ui === 'mn').flatMap((r) => results[r.id].dom.slice(0, 3).map((d) => d.name ?? ''));
  const latin = mnNames.filter((n) => LATIN_ONLY(n));
  const summary = {
    run: new Date().toISOString(),
    tierA: `${A.length - failedA.length}/${A.length} pass`,
    failedA,
    tierB: `${passedB.length}/${counted.length} counted rows pass (${(shareB * 100).toFixed(0)} %; threshold 80 %); data gaps (control failed, not counted): ${gaps.join(', ') || 'none'}`,
    failedB: counted.filter((r) => !results[r.id].pass).map((r) => `${r.id} «${r.q}»: ${results[r.id].detail}`),
    tierC: GOLDEN.rows.filter((r) => r.tier === 'C').map((r) => `${r.id} «${r.q}»: ${results[r.id].dom.slice(0, 5).map((d, i) => `${i + 1}.${d.name}[${d.type}] «${d.context}»`).join(' | ')}`),
    C4: c4,
    C5: `${latin.length}/${mnNames.length} top-3 names in the mn UI are Latin-only${latin.length ? ': ' + [...new Set(latin)].join(', ') : ''}`,
    problemsAC3: problems3,
    problemsAC15: problems15,
    problemsAC16: problems16,
    rows: results,
  };
  mkdirSync(OUT, { recursive: true });
  writeFileSync(OUT + 'golden-latest.json', JSON.stringify(summary, null, 1));
  for (const k of ['tierA', 'failedA', 'tierB', 'failedB', 'C4', 'C5', 'problemsAC3', 'problemsAC15', 'problemsAC16']) {
    test.info().annotations.push({ type: k, description: JSON.stringify(summary[k]) });
  }
  for (const r of GOLDEN.rows) test.info().annotations.push({ type: `row ${r.id}`, description: `${results[r.id].pass ? 'PASS' : 'FAIL'} «${r.q}» req=${JSON.stringify(results[r.id].requests)} ${results[r.id].detail}` });

  expect.soft(problems3, 'AC 3: <= 2 requests per settled query').toEqual([]);
  expect.soft(problems15, 'AC 15').toEqual([]);
  expect.soft(problems16, 'AC 16').toEqual([]);
  expect.soft(failedA, 'AC 14 tier A: every row passes').toEqual([]);
  expect(shareB, `AC 14 tier B: ${summary.tierB}`).toBeGreaterThanOrEqual(0.8);
});

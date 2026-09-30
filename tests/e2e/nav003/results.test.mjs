// NAV-003 B. Autocomplete timing (AC 12, 13) and D. Results list content (AC 17–19) with fixtures F1–F13 and F14a/F14b/F15/F16
// (story amended 2026-09-30, PO approval F2, D33: Latin name endings for rules 1–4, rule 4a, same rule row in both UI languages).
import { test, expect } from '@playwright/test';
import { FIXTURES, FX, REF, SEARCH_GLOB, T, TRAD, expectNoTrad, fc, feature, jumpTo, mock, openApp, options, pace, tid, typeQuery, waitSettled } from './helpers.mjs';


test('AC12 (live): results or «Илэрц олдсонгүй» render within 1,000 ms of the last keystroke for >= 19 of 20 samples', async ({ page }) => {
  test.setTimeout(120_000);
  await openApp(page);
  await jumpTo(page, REF.P1, 12);
  const queries = ['Сүхбаатар', 'Сүхб', 'Гандан', 'Зайсан', 'Их дэлгүүр', 'Энхтайвны өргөн чөлөө'];
  const samples = [];
  for (let i = 0; i < 20; i++) {
    const q = queries[i % queries.length];
    await typeQuery(page, q, { delay: 20 });
    const tKey = await page.evaluate(() => window.__inputs.at(-1).t);
    await waitSettled(page, q, 5000).catch(() => {});
    const tRender = await page.evaluate(
      ({ q, tKey }) => window.__st.find((s) => s.t >= tKey && s.query === q && (s.popup === 'results' || s.popup === 'no-results'))?.t ?? null,
      { q, tKey },
    );
    samples.push({ q, ms: tRender === null ? Infinity : tRender - tKey });
    await tid(page, 'search-clear').click();
    await pace(page); // <= 2 requests/s (one request per sample)
  }
  const ok = samples.filter((s) => s.ms <= 1000).length;
  const sorted = samples.map((s) => s.ms).sort((a, b) => a - b);
  test.info().annotations.push({ type: 'AC12 samples (ms)', description: samples.map((s) => `${s.q}:${Math.round(s.ms)}`).join(', ') });
  test.info().annotations.push({ type: 'AC12 summary', description: `${ok}/20 <= 1000 ms; median ${Math.round(sorted[9])} ms; max ${Math.round(sorted[19])} ms` });
  expect(ok).toBeGreaterThanOrEqual(19);
});

test('AC13: a request pending > 300 ms shows «Ачаалж байна…» with aria-busy=true; gone within 200 ms of the results', async ({ page }) => {
  await mock(page, SEARCH_GLOB, () => ({ delay: 1200, body: fc([feature('Гандан хийд', 106.895, 47.9215)]) }));
  await openApp(page);
  await typeQuery(page, 'Гандан');
  await waitSettled(page, 'Гандан');
  const r = await page.evaluate(() => {
    const req = window.__req.find((x) => x.op === 'search');
    const tl = window.__st.filter((s) => s.t >= req.t);
    const load = tl.find((s) => s.row === 'loading');
    const res = tl.find((s) => s.popup === 'results');
    return { reqT: req.t, end: req.end, load, res };
  });
  expect(r.load, 'loading row shown').toBeTruthy();
  expect(r.load.rowText).toBe(T.mn.loading);
  expect(r.load.busy).toBe('true');
  const after = r.load.t - r.reqT;
  test.info().annotations.push({ type: 'AC13', description: `loading at +${after.toFixed(0)} ms after request start; results ${(r.res.t - r.end).toFixed(0)} ms after the response` });
  expect(after).toBeGreaterThanOrEqual(290);
  expect(after).toBeLessThanOrEqual(500);
  expect(r.res.t - r.end).toBeLessThanOrEqual(200);
  expect(r.res.busy).toBe('false');
  await expect(tid(page, 'search-results')).toHaveAttribute('aria-busy', 'false');
  // English
  await tid(page, 'search-clear').click();
  await tid(page, 'language-toggle').click();
  await typeQuery(page, 'Гандан хийд');
  await expect(page.locator('#search-state-text')).toHaveText(T.en.loading, { timeout: 2000 });
  await expect(tid(page, 'search-popup')).toHaveAttribute('aria-busy', 'true');
});

/**
 * Serves the fixtures in batches (at most 10 options per list, AC 7). F14a and F14b are the lang=en and lang=mn forms of
 * the same OSM object (R 9014), so they are served in separate lists.
 */
const BATCHES = [
  ['Тест нэг', ['F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'F7']],
  ['Тест 2', ['F8', 'F9', 'F10', 'F11', 'F13', 'F12']], // F12 (CN) is sent first upstream, listed last (AC 8)
  ['Тест 3', ['F14a', 'F15', 'F16']],
  ['Тест 4', ['F14b']],
];
async function mockFixtures(page) {
  const upstream = { 'Тест 2': ['F12', 'F8', 'F9', 'F10', 'F11', 'F13'] };
  return mock(page, SEARCH_GLOB, (p) => {
    const b = BATCHES.find(([q]) => q === p.q) ?? BATCHES[0];
    return { body: fc((upstream[b[0]] ?? b[1]).map((id) => FX[id].feature)) };
  });
}

for (const lang of ['mn', 'en']) {
  test(`AC17/AC18/AC19 ${lang}: fixture options F1–F16 show name, type label and context line as the story rules say`, async ({ page }) => {
    await mockFixtures(page);
    await openApp(page, { lang });
    const got = {};
    for (const [q, ids] of BATCHES) {
      await typeQuery(page, q);
      await waitSettled(page, q);
      const o = await options(page);
      expect(o.length).toBe(ids.length);
      ids.forEach((id, i) => (got[id] = o[i]));
      await expectNoTrad(page);
      await tid(page, 'search-clear').click();
    }
    const problems = [];
    for (const f of FIXTURES) {
      const e = f.expect;
      const o = got[f.id];
      const label = lang === 'mn' ? e.mn : e.en;
      const expName = e.name ?? label; // AC 17: both absent -> the type label
      if (o.name !== expName) problems.push(`${f.id} name «${o.name}» != «${expName}»`);
      if (o.type !== label) problems.push(`${f.id} (rule ${e.rule}) type «${o.type}» != «${label}»`);
      if ((o.context ?? null) !== e.context) problems.push(`${f.id} context «${o.context}» != «${e.context}»`);
      if (/undefined|null|NaN/.test(o.text)) problems.push(`${f.id} text contains undefined/null: ${o.text}`);
      if (TRAD.test(o.text)) problems.push(`${f.id} traditional script shown`);
    }
    expect(problems).toEqual([]);
    // AC 8 inside batch 2: the CN feature F12 is last although upstream sent it first
    expect(got.F12.id).toBe('search-option-5');
    // AC 19 (F2): the lang=en form "Chingeltei" and the lang=mn form «Чингэлтэй» of the same object get the same row
    // (4a), and no F14–F16 option falls back to «Газар» / "Place" or rule 7 / rule 28.
    expect(got.F14a.type, 'F14 en form and mn form: same type-label row').toBe(got.F14b.type);
    expect(got.F14a.type).toBe(lang === 'mn' ? 'Дүүрэг' : 'District');
    for (const id of ['F14a', 'F14b', 'F15', 'F16']) expect(got[id].type, id).not.toBe(lang === 'mn' ? 'Газар' : 'Place');
    expect(got.F15.type, 'F15 rule 2 (Latin " khoroo"), not rule 28').not.toBe(lang === 'mn' ? 'Төрийн байгууллага' : 'Government office');
    expect(got.F16.type, 'F16 rule 1 before rule 7').not.toBe(lang === 'mn' ? 'Хороолол' : 'Neighbourhood');
  });
}

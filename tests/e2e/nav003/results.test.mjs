// NAV-003 B. Autocomplete timing (AC 12, 13) and D. Results list content (AC 17–19) with fixtures F1–F13 and F14a/F14b/F15/F16
// (story amended 2026-09-30, PO approval F2, D33: Latin name endings for rules 1–4, rule 4a, same rule row in both UI languages)
// and F17a/F17b/F17c (PO decision D34, 2026-09-30: rule 4 (b) boundary/administrative + type=county; «Сум» in both UI languages).
import { test, expect } from '@playwright/test';
import { FIXTURES, FX, REF, SEARCH_GLOB, T, TRAD, TYPE_LABELS, expectNoTrad, fc, feature, jumpTo, mock, openApp, options, pace, tid, typeQuery, waitSettled } from './helpers.mjs';


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
 * the same OSM object (R 9014), F17a and F17b those of R 7297914, so each pair is served in separate lists.
 */
const BATCHES = [
  ['Тест нэг', ['F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'F7']],
  ['Тест 2', ['F8', 'F9', 'F10', 'F11', 'F13', 'F12']], // F12 (CN) is sent first upstream, listed last (AC 8)
  ['Тест 3', ['F14a', 'F15', 'F16', 'F17a', 'F17c']],
  ['Тест 4', ['F14b', 'F17b']],
];
async function mockFixtures(page) {
  const upstream = { 'Тест 2': ['F12', 'F8', 'F9', 'F10', 'F11', 'F13'] };
  return mock(page, SEARCH_GLOB, (p) => {
    const b = BATCHES.find(([q]) => q === p.q) ?? BATCHES[0];
    return { body: fc((upstream[b[0]] ?? b[1]).map((id) => FX[id].feature)) };
  });
}

for (const lang of ['mn', 'en']) {
  test(`AC17/AC18/AC19 ${lang}: fixture options F1–F17 show name, type label and context line as the story rules say`, async ({ page }) => {
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
    // AC 19 (D34): the lang=en form "Bayan-Undur" and the lang=mn form «Баян-Өндөр сум» of the soum R 7297914 get the same
    // row 4 and the label «Сум» in both UI languages (English UI untranslated: not "Soum", not "Place").
    expect(got.F17a.type, 'F17 en form and mn form: same type-label row').toBe(got.F17b.type);
    for (const id of ['F17a', 'F17b', 'F17c']) {
      expect(got[id].type, `${id} D34 «Сум» in the ${lang} UI`).toBe('Сум');
      expect(got[id].type, id).not.toBe(lang === 'mn' ? 'Газар' : 'Place');
      expect(got[id].type, id).not.toBe('Soum');
    }
  });
}

// QA defect D4 (run 6 / golden run 4, tier C row C6g/C6h, risk R13): AC 19 "the same OSM object returned with lang=mn and
// with lang=en ... both get the label of the same table row ... never 'Place' in one language and a specific label in the
// other". Features copied from the live gateway responses of 2026-09-30 08:14–08:19 UTC (properties as Photon sent them,
// traditional script kept). The aimag pair is a control (same row today). Until PO decision D34 the expected label was NOT
// asserted (no product decision by QA), only that both languages use the same rule row. Stays as the regression test for D4.
// Changed 2026-09-30 for PO decision D34 (the approved expected label only): the soum is «Сум» (row 4) in BOTH UI languages,
// untranslated in the English UI (TYPE_LABELS row 4 en = «Сум», was "Soum"). The aimag label is still not asserted (story
// Open question 9, not part of D34): only the same-row check applies to it.
// Changed 2026-09-30 for PO decision D45 (resolves Open question 9; the approved expected label only): the aimag R 270075
// (place/state/type=state, no « аймаг» / " aimag" name ending) is «Аймаг» (row 3, rule 3 (b)) in BOTH UI languages,
// untranslated in the English UI (TYPE_LABELS row 3 en = «Аймаг», was "Aimag"). Before D45 it was «Газар» / "Place" (row 32).
const liveForms = {
  soum: {
    osm: 'R7297914',
    mn: { q: 'Баян-Өндөр сум', props: { osm_type: 'R', osm_id: 7297914, osm_key: 'boundary', osm_value: 'administrative', type: 'county', countrycode: 'MN', name: 'Баян-Өндөр сум', state: 'Өвөрхангай ᠥᠪᠦᠷ ᠬᠠᠩᠭ᠋ᠠᠢ' }, lonlat: [102.0, 46.0] },
    en: { props: { osm_type: 'R', osm_id: 7297914, osm_key: 'boundary', osm_value: 'administrative', type: 'county', countrycode: 'MN', name: 'Bayan-Undur', state: 'Uvurkhangai' }, lonlat: [102.0, 46.0] },
  },
  aimag: {
    osm: 'R270075',
    mn: { q: 'Архангай', props: { osm_type: 'R', osm_id: 270075, osm_key: 'place', osm_value: 'state', type: 'state', countrycode: 'MN', name: 'Архангай ᠠᠷᠤ ᠬᠠᠩᠭ᠋ᠠᠢ' }, lonlat: [101.4, 47.6] },
    en: { props: { osm_type: 'R', osm_id: 270075, osm_key: 'place', osm_value: 'state', type: 'state', countrycode: 'MN', name: 'Arkhangai' }, lonlat: [101.4, 47.6] },
  },
};
const ruleOf = (label, ui) => TYPE_LABELS.find((t) => t[ui === 'en' ? 2 : 1] === label)?.[0] ?? null;

test('AC19 same row (D4, R13): a live soum boundary and a live aimag get the same type-label row with lang=mn and lang=en', async ({ page }) => {
  // The mock answers with the form that matches the request's lang (as the live gateway does for the same object).
  let current = liveForms.soum;
  await mock(page, SEARCH_GLOB, (p) => {
    const f = current[p.lang === 'en' ? 'en' : 'mn'];
    return { body: fc([{ type: 'Feature', geometry: { type: 'Point', coordinates: f.lonlat }, properties: f.props }]) };
  });
  await openApp(page, { lang: 'mn' });
  const out = [];
  for (const [kind, forms] of Object.entries(liveForms)) {
    current = forms;
    if ((await page.evaluate(() => document.documentElement.lang)) !== 'mn') await tid(page, 'language-toggle').click();
    await typeQuery(page, forms.mn.q);
    await waitSettled(page, forms.mn.q);
    const mn = (await options(page))[0];
    // AC 38: switching the UI language re-requests the open list with lang=en
    await tid(page, 'language-toggle').click();
    await expect.poll(async () => (await options(page))[0]?.name, { timeout: 3000 }).toBe(forms.en.props.name);
    const en = (await options(page))[0];
    out.push({ kind, osm: forms.osm, mn: `${mn.name} [${mn.type}] row ${ruleOf(mn.type, 'mn')}`, en: `${en.name} [${en.type}] row ${ruleOf(en.type, 'en')}`, same: ruleOf(mn.type, 'mn') === ruleOf(en.type, 'en') });
    await tid(page, 'search-clear').click();
  }
  test.info().annotations.push({ type: 'AC19 D4 same row', description: JSON.stringify(out) });
  expect(out.filter((o) => !o.same).map((o) => `${o.kind} ${o.osm}: mn ${o.mn} / en ${o.en}`), 'AC 19: same OSM object, same type-label row in both UI languages').toEqual([]);
  // D34 (approved expected label): the soum boundary is «Сум», row 4, in the Mongolian AND the English UI
  const soum = out.find((o) => o.kind === 'soum');
  expect([soum.mn, soum.en], 'D34: soum R 7297914 labelled «Сум» (row 4) in both UI languages').toEqual(['Баян-Өндөр сум [Сум] row 4', 'Bayan-Undur [Сум] row 4']);
  // D45 (approved expected label): the aimag relation is «Аймаг», row 3, in the Mongolian AND the English UI
  const aimag = out.find((o) => o.kind === 'aimag');
  expect([aimag.mn, aimag.en], 'D45: aimag R 270075 labelled «Аймаг» (row 3) in both UI languages').toEqual(['Архангай [Аймаг] row 3', 'Arkhangai [Аймаг] row 3']);
});

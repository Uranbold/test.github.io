// NAV-003 aimag type label (PO decision D45, 2026-09-30; resolves story Open question 9 and decisions.md Items to confirm 6).
// Story AC 19, 20, 45, fixture F18, risk R13; test plan › tier C row C8 (QA data check) and its "Verify step".
// Rule 3 (b): type=state OR (osm_key=place AND osm_value=state) -> «Аймаг», shown as the Mongolian word in BOTH UI languages
// (en value of row 3 = «Аймаг», untranslated, like «Сум» for D34), with lang="mn" in the English UI (screen spec › Language
// of parts). Ulaanbaatar (place/city/type=city) and every other object named like an aimag keep their own row.
//
// Fixture F18 = fixtures/aimags-live.json: live gateway features captured by QA on 2026-09-30 (properties exactly as Photon
// returned them, lang=mn and lang=en forms). The mocks answer each request with the form matching its `lang`, as the live
// gateway does. AM01–AM21 aimag relations, AMN1 the Töv place=state label node, AX01–AX31 objects that are not aimags.
// Negative control: against the pre-D45 client the AM/AMN rows of these tests fail (see the test plan run record).
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { REF, REVERSE_GLOB, SEARCH_GLOB, TRAD, TYPE_LABELS, camera, card, fc, jumpTo, mapCentrePx, mock, openApp, pace, project, rightClick, tid, typeQuery, view, waitSettled } from './helpers.mjs';

const F18 = JSON.parse(readFileSync(fileURLToPath(new URL('./fixtures/aimags-live.json', import.meta.url)), 'utf8')).fixtures;
const AIMAG = 'Аймаг';
const isAimagFx = (f) => f.group === 'aimag' || f.group === 'aimagNode';
const AIMAG_OSM = new Set(F18.filter(isAimagFx).map((f) => f.osm));
const UB_OSM = new Set(['R270090', 'R4479697']);
const ruleOf = (label, ui) => TYPE_LABELS.find((t) => t[ui === 'en' ? 2 : 1] === label)?.[0] ?? null;

/** Type label, name and the lang attribute of each visible option's type label (own attribute and nearest ancestor). */
const optionsWithLang = (page) =>
  page.evaluate(() =>
    [...document.querySelectorAll('#search-results [role=option]')].map((li) => {
      const ty = li.querySelector('[data-testid=search-option-type]');
      return {
        name: li.querySelector('[data-testid=search-option-name]')?.textContent ?? null,
        type: ty?.textContent ?? null,
        own: ty?.getAttribute('lang') ?? null,
        inherited: ty?.closest('[lang]')?.getAttribute('lang') ?? null,
        text: li.textContent,
      };
    }),
  );

// Lists of at most 10 options (AC 7), pure Cyrillic queries (ADR-0006 rule D: one request each).
const WORDS = ['нэг', 'хоёр', 'гурав', 'дөрөв', 'тав', 'зургаа'];
const BATCHES = WORDS.map((w, i) => [`Аймгийн тест ${w}`, F18.slice(i * 10, i * 10 + 10).map((f) => f.id)]).filter(([, ids]) => ids.length);
const FXID = Object.fromEntries(F18.map((f) => [f.id, f]));

/** Serves a batch by its query, or one fixture by its id in the query («AM01 тест»), in the form of the request's lang. */
function mockF18(page) {
  return mock(page, SEARCH_GLOB, (p) => {
    const form = p.lang === 'en' ? 'en' : 'mn';
    const m = /A(?:M|MN|X)\d+/.exec(p.q);
    if (m && FXID[m[0]]) return { body: fc([FXID[m[0]][form]]) };
    const b = BATCHES.find(([q]) => q === p.q);
    return { body: fc(b ? b[1].map((id) => FXID[id][form]) : []) };
  });
}

test('F18 fixture sanity (story Test approach): 21 aimag relations and the Töv node in mn and en, 31 non-aimags incl. Ulaanbaatar', () => {
  const am = F18.filter((f) => f.group === 'aimag');
  expect(am.length).toBe(21);
  expect(new Set(am.map((f) => f.osm)).size).toBe(21);
  expect(F18.filter((f) => f.group === 'aimagNode').map((f) => f.osm)).toEqual(['N2704047290']);
  expect(F18.filter((f) => f.group === 'negative').length).toBe(31);
  for (const id of ['R270090', 'R4479697']) expect(F18.find((f) => f.osm === id)?.group, `Ulaanbaatar ${id} is a negative`).toBe('negative');
  for (const f of am) expect(Array.isArray(f.mn.properties.extent) && Array.isArray(f.en.properties.extent), `${f.id} has extent`).toBe(true);
  expect(FXID.AM17.mn.properties).toMatchObject({ osm_key: 'boundary', osm_value: 'administrative', type: 'state' });
});

for (const lang of ['mn', 'en']) {
  test(`AC19/AC45 F18 ${lang} UI: every aimag relation and the Töv node is «Аймаг» (row 3) in the list; Ulaanbaatar and every other non-aimag keeps its label and is never «Аймаг»`, async ({ page }) => {
    await mockF18(page);
    await openApp(page, { lang });
    const got = {};
    for (const [q, ids] of BATCHES) {
      await typeQuery(page, q);
      await waitSettled(page, q);
      const o = await optionsWithLang(page);
      const v = await view(page);
      expect(o.length, `${q}: one option per fixture`).toBe(ids.length);
      ids.forEach((id, i) => (got[id] = { ...o[i], osm: `${v.options[i].props.osm_type}${v.options[i].props.osm_id}` }));
      await tid(page, 'search-clear').click();
    }
    const problems = [];
    const counts = {};
    for (const f of F18) {
      const o = got[f.id];
      const exp = lang === 'mn' ? f.expect.mn : f.expect.en;
      const expName = lang === 'mn' ? f.nameMn : f.nameEn;
      counts[o.type] = (counts[o.type] ?? 0) + 1;
      if (o.osm !== f.osm) problems.push(`${f.id}: option is ${o.osm}, expected ${f.osm}`);
      if (o.type !== exp) problems.push(`${f.id} ${f.osm} ${f.osm_key}/${f.osm_value}/${f.type} «${o.name}»: type «${o.type}» != «${exp}»`);
      if (o.name !== expName) problems.push(`${f.id}: name «${o.name}» != «${expName}»`);
      if (TRAD.test(o.text)) problems.push(`${f.id}: traditional script shown`);
      if (isAimagFx(f)) {
        if (o.type !== AIMAG) problems.push(`${f.id} ${f.osm}: aimag not «Аймаг» (got «${o.type}»)`);
        if (ruleOf(o.type, lang) !== 3) problems.push(`${f.id}: rule row ${ruleOf(o.type, lang)}, expected 3`);
        // AC 45 / screen spec › Language of parts: «Аймаг» in the English UI is marked lang="mn"; in the mn UI no override
        if (lang === 'en' && o.own !== 'mn') problems.push(`${f.id}: en UI «Аймаг» without lang="mn" (own=${o.own})`);
        if (lang === 'mn' && o.own !== null) problems.push(`${f.id}: mn UI «Аймаг» has a lang override (${o.own})`);
      } else {
        if (o.type === AIMAG) problems.push(`${f.id} ${f.osm} (${f.note}): non-aimag labelled «Аймаг»`);
        if (lang === 'en' && /^[\p{Script=Latin} ]+$/u.test(o.type ?? '') && o.inherited === 'mn') problems.push(`${f.id}: Latin label «${o.type}» resolves to lang="mn"`);
      }
    }
    test.info().annotations.push({ type: `F18 ${lang} label counts`, description: JSON.stringify(counts) });
    test.info().annotations.push({ type: `F18 ${lang} Ulaanbaatar`, description: JSON.stringify(['AX01', 'AX02'].map((id) => got[id])) });
    expect(problems).toEqual([]);
    // Ulaanbaatar is the capital, not an aimag: «Хот» / "City or town" (row 5) in both relations
    for (const id of ['AX01', 'AX02']) {
      expect(UB_OSM.has(got[id].osm)).toBe(true);
      expect(got[id].type, `Ulaanbaatar ${got[id].osm} (${lang})`).toBe(lang === 'mn' ? 'Хот' : 'City or town');
    }
    // Story F18 counts (per UI language: half of the 106 features)
    const city = lang === 'mn' ? 'Хот' : 'City or town';
    const district = lang === 'mn' ? 'Дүүрэг' : 'District';
    const neighbourhood = lang === 'mn' ? 'Хороолол' : 'Neighbourhood';
    const settlement = lang === 'mn' ? 'Суурин' : 'Settlement';
    expect(counts).toEqual({ [AIMAG]: 22, [city]: 11, [district]: 7, Сум: 10, [neighbourhood]: 2, [settlement]: 1 });
  });
}

/** Clicks the only option; with `waitCamera`, waits until the camera has settled (AC 20). */
async function selectOnly(page, q, waitCamera = true) {
  await typeQuery(page, q);
  await waitSettled(page, q);
  if (!waitCamera) {
    await tid(page, 'search-option').first().click();
    await expect.poll(async () => (await card(page)).open, { timeout: 3000 }).toBe(true);
    return;
  }
  await page.evaluate(() => {
    window.__f18 = { end: null };
    window.__nav002.map.once('moveend', () => (window.__f18.end = performance.now()));
  });
  await tid(page, 'search-option').first().click();
  await page.waitForFunction(() => window.__f18.end !== null && !window.__nav002.map.isMoving(), null, { timeout: 5000, polling: 50 });
  await page.waitForTimeout(150);
  await page.waitForFunction(() => !window.__nav002.map.isMoving(), null, { timeout: 5000, polling: 50 });
}

test('AC19/AC20/AC45 F18 mn and en: place card «Аймаг» for every aimag and the Töv node (lang="mn" in the English UI); aimags fit their extent, the node flies to zoom 13; non-aimag cards keep their label', async ({ page }) => {
  test.setTimeout(480_000); // 2 × 53 selections, 44 of them with a fly-to (about 4.5 min)
  await mockF18(page);
  const seen = {};
  const problems = [];
  for (const lang of ['mn', 'en']) {
    await openApp(page, { lang });
    for (const f of F18) {
      // Camera (AC 20) only for the aimag features; the non-aimag cards are checked for their label (AC 19)
      if (isAimagFx(f)) await jumpTo(page, { lat: 47.9, lng: 106.9 }, 11);
      await selectOnly(page, `${f.id} тест`, isAimagFx(f));
      const c = await card(page);
      const typeLang = await page.locator('#place-card-type').getAttribute('lang');
      const exp = lang === 'mn' ? f.expect.mn : f.expect.en;
      const row = { type: c.type, lang: typeLang };
      if (c.type !== exp) problems.push(`${lang} ${f.id} ${f.osm}: card type «${c.type}» != «${exp}»`);
      if (isAimagFx(f)) {
        if (lang === 'en' && typeLang !== 'mn') problems.push(`en ${f.id}: card «Аймаг» without lang="mn"`);
        if (lang === 'mn' && typeLang !== null) problems.push(`mn ${f.id}: card «Аймаг» has lang override ${typeLang}`);
        const cam = await camera(page);
        row.zoom = +cam.zoom.toFixed(2);
        const props = f[lang].properties;
        if (props.extent) {
          // AC 20: extent fit with >= 40 px padding at zoom <= 17 (extent = [minLon, maxLat, maxLon, minLat])
          const [minLon, maxLat, maxLon, minLat] = props.extent;
          const a = await project(page, minLon, maxLat);
          const b = await project(page, maxLon, minLat);
          row.pad = Math.round(Math.min(a.x, a.y, a.vw - b.x, a.vh - b.y));
          if (row.pad < 39.5 - 0.0001) problems.push(`${lang} ${f.id}: extent padding ${row.pad} px < 40`);
          if (cam.zoom > 17.0001) problems.push(`${lang} ${f.id}: extent zoom ${cam.zoom} > 17`);
        } else {
          // AC 20: no extent, area label «Аймаг» -> centred ±5 px at zoom 13 (AMN1)
          const [lon, lat] = f[lang].geometry.coordinates;
          const pr = await project(page, lon, lat);
          row.d = +Math.hypot(pr.x - pr.cx, pr.y - pr.cy).toFixed(1);
          if (row.d > 5) problems.push(`${lang} ${f.id}: not centred (${row.d} px)`);
          if (Math.abs(cam.zoom - 13) > 0.01) problems.push(`${lang} ${f.id}: zoom ${cam.zoom}, expected 13 (area label «Аймаг»)`);
        }
      } else if (c.type === AIMAG) problems.push(`${lang} ${f.id} ${f.osm}: non-aimag card labelled «Аймаг»`);
      (seen[f.id] ??= {})[lang] = row;
      await page.keyboard.press('Escape');
    }
  }
  test.info().annotations.push({ type: 'F18 cards and camera (aimags)', description: JSON.stringify(Object.fromEntries(Object.entries(seen).filter(([id]) => isAimagFx(FXID[id])))) });
  expect(problems).toEqual([]);
  // Same row and same zoom in both UI languages (AC 19, AC 20)
  for (const f of F18.filter(isAimagFx)) {
    expect.soft([seen[f.id].mn.type, seen[f.id].en.type], `${f.id} same row mn/en`).toEqual([AIMAG, AIMAG]);
    expect.soft(seen[f.id].en.zoom, `${f.id} same zoom mn/en`).toBeCloseTo(seen[f.id].mn.zoom, 1);
  }
});

test('AC19/AC28/AC45 F18: the coordinate card nearest place is «Аймаг» for an aimag relation (lang="mn" in the English UI, none in the Mongolian UI) and not for Ulaanbaatar', async ({ page }) => {
  let reverseFx = FXID.AM01;
  await mock(page, REVERSE_GLOB, (p) => ({ body: fc([reverseFx[p.lang === 'en' ? 'en' : 'mn']]) }));
  const out = [];
  for (const lang of ['mn', 'en']) {
    await openApp(page, { lang });
    for (const id of ['AM01', 'AM17', 'AX01']) {
      reverseFx = FXID[id];
      await rightClick(page, 900, 400);
      await expect.poll(async () => (await card(page)).near, { timeout: 3000 }).toBe('place');
      const nearType = page.locator('#place-nearest-type');
      const text = await nearType.textContent();
      const own = await nearType.getAttribute('lang');
      out.push({ lang, id, osm: FXID[id].osm, text, own });
      await tid(page, 'place-card-close').click().catch(() => page.keyboard.press('Escape'));
    }
  }
  test.info().annotations.push({ type: 'F18 nearest place', description: JSON.stringify(out) });
  expect(out.map((o) => `${o.lang} ${o.id} ${o.text} lang=${o.own}`)).toEqual([
    'mn AM01 Аймаг lang=null',
    'mn AM17 Аймаг lang=null',
    'mn AX01 Хот lang=null',
    'en AM01 Аймаг lang=mn',
    'en AM17 Аймаг lang=mn',
    'en AX01 City or town lang=null',
  ]);
});

// Live (A-live, shared gateway, read only, paced <= 2 requests/s): the aimag R 270075 is «Аймаг» in both UIs with
// lang="mn" in the English UI, Ulaanbaatar is not, and no option outside the 21 aimag relations and the Töv node is «Аймаг».
// The reverse result at the Arkhangai centre is recorded only (story AC 19: depends on the index).
test('AC19/AC45 (live, D45): «Архангай» / "Arkhangai" shows the aimag R 270075 as «Аймаг» in both UIs; «Улаанбаатар» / "Ulaanbaatar" never «Аймаг»', async ({ page }) => {
  test.setTimeout(120_000);
  await openApp(page, { lang: 'mn' });
  await jumpTo(page, REF.P1, 12);
  const rows = [];
  const problems = [];
  for (const [lang, q] of [['mn', 'Архангай'], ['mn', 'Улаанбаатар'], ['en', 'Arkhangai'], ['en', 'Ulaanbaatar']]) {
    if ((await page.evaluate(() => document.documentElement.lang)) !== lang) {
      await tid(page, 'language-toggle').click();
      await page.waitForTimeout(300);
    }
    const before = await page.evaluate(() => window.__req.length);
    await typeQuery(page, q);
    await waitSettled(page, q, 12_000);
    await page.waitForTimeout(150);
    const dom = await optionsWithLang(page);
    const v = await view(page);
    const opts = v.options.map((o, i) => ({ ...dom[i], osm: o.kind === 'place' ? `${o.props.osm_type}${o.props.osm_id}` : o.kind, photon: o.kind === 'place' ? `${o.props.osm_key}/${o.props.osm_value}/${o.props.type}` : null }));
    rows.push({ lang, q, top: opts.slice(0, 5).map((o) => `${o.name}[${o.type}]${o.own ? ' lang=' + o.own : ''} ${o.osm} ${o.photon}`) });
    for (const o of opts) {
      if (o.type === AIMAG && !AIMAG_OSM.has(o.osm)) problems.push(`${lang} «${q}»: ${o.osm} ${o.photon} «${o.name}» labelled «Аймаг» but is not in the 21 aimags + Töv node`);
      if (UB_OSM.has(o.osm) && o.type === AIMAG) problems.push(`${lang} «${q}»: Ulaanbaatar ${o.osm} labelled «Аймаг»`);
      if (o.type === AIMAG && lang === 'en' && o.own !== 'mn') problems.push(`en «${q}»: «Аймаг» without lang="mn"`);
    }
    if (/Архангай|Arkhangai/.test(q)) {
      const a = opts.find((o) => o.osm === 'R270075');
      if (!a) problems.push(`${lang} «${q}»: aimag R 270075 not in the list`);
      else if (a.type !== AIMAG) problems.push(`${lang} «${q}»: R 270075 labelled «${a.type}», expected «Аймаг»`);
    } else {
      const ub = opts.filter((o) => UB_OSM.has(o.osm));
      if (!ub.length) problems.push(`${lang} «${q}»: no Ulaanbaatar relation in the list`);
      for (const o of ub) if (o.type !== (lang === 'mn' ? 'Хот' : 'City or town')) problems.push(`${lang} «${q}»: Ulaanbaatar ${o.osm} labelled «${o.type}»`);
    }
    await tid(page, 'search-clear').click();
    const n = (await page.evaluate((b) => window.__req.slice(b).length, before)) || 1;
    await pace(page, n);
  }
  // Recorded only: reverse at the Arkhangai centre (QA probe 2026-09-30 returned R 270075)
  await jumpTo(page, { lat: 47.8626, lng: 101.0316 }, 12);
  const respP = page.waitForResponse((r) => r.url().includes('/v1/reverse?'), { timeout: 10_000 }).catch(() => null);
  const centre = await mapCentrePx(page);
  await rightClick(page, centre.x, centre.y);
  const resp = await respP;
  await expect.poll(async () => (await card(page)).near, { timeout: 5000 }).not.toBe('loading').catch(() => {});
  const f = resp ? (await resp.json().catch(() => ({}))).features?.[0] : null;
  const near = { osm: f ? `${f.properties.osm_type}${f.properties.osm_id}` : null, photon: f ? `${f.properties.osm_key}/${f.properties.osm_value}/${f.properties.type}` : null, card: (await card(page)).nearText, typeLang: await page.locator('#place-nearest-type').getAttribute('lang').catch(() => null) };
  test.info().annotations.push({ type: 'D45 live rows', description: JSON.stringify(rows) });
  test.info().annotations.push({ type: 'D45 live reverse at Arkhangai centre (recorded, en UI)', description: JSON.stringify(near) });
  expect(problems).toEqual([]);
});

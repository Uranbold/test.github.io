// NAV-003 I. Strings and localisation (AC 44, 45), J. Privacy (AC 46), and static checks of the carried NAV-002
// follow-ups 2 and 3 (they trace to NAV-002, not to NAV-003 AC; follow-up 1 is the unchanged NAV-002 AC 37 test).
import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import {
  FX, LABEL_EXPRESSION, REVERSE_GLOB, ROOT, SEARCH_GLOB, T, TYPE_LABELS, WEB, decimals, fc, feature, mock, openApp, options, tid, typeQuery, waitSettled,
} from './helpers.mjs';

const MN = JSON.parse(readFileSync(WEB + 'src/i18n/mn.json', 'utf8'));
const EN = JSON.parse(readFileSync(WEB + 'src/i18n/en.json', 'utf8'));
const NAV3 = (k) => /^(search|place|placeType)\./.test(k);

function run(cmd, args) {
  try {
    return { code: 0, out: execFileSync(cmd, args, { cwd: WEB, encoding: 'utf8', stdio: 'pipe', timeout: 120_000 }) };
  } catch (e) {
    return { code: e.status ?? 1, out: `${e.stdout ?? ''}${e.stderr ?? ''}` };
  }
}

test('AC44: check:i18n (hard-coded text scan, identical key sets) and check:glossary pass; every NAV-003 mn value is a story/glossary string, {count} kept literally', async () => {
  test.setTimeout(180_000);
  const i18n = run('npm', ['run', '--silent', 'check:i18n']);
  expect(i18n.code, i18n.out).toBe(0);
  const gl = run('npm', ['run', '--silent', 'check:glossary']);
  expect(gl.code, gl.out).toBe(0);
  test.info().annotations.push({ type: 'AC44 check output', description: `${i18n.out.trim().split('\n').slice(-2).join(' | ')} || ${gl.out.trim().split('\n').slice(-2).join(' | ')}` });
  expect(Object.keys(MN).sort()).toEqual(Object.keys(EN).sort());
  const allowedMn = new Set([...Object.values(T.mn).filter((v) => typeof v === 'string'), '{count} илэрц олдлоо', ...TYPE_LABELS.map((r) => r[1])]);
  const allowedEn = new Set([...Object.values(T.en).filter((v) => typeof v === 'string'), '{count} results', '1 result', ...TYPE_LABELS.map((r) => r[2])]);
  const keys = Object.keys(MN).filter(NAV3);
  // search.* (9) + place.* (2) + placeType.* (32); loading/offline/retry/close/error reuse NAV-002 keys (status.*, action.*)
  expect(keys.length).toBeGreaterThanOrEqual(43);
  for (const k of ['status.loading', 'status.offline', 'status.genericError', 'action.retry', 'action.close']) expect(allowedMn.has(MN[k]), k).toBe(true);
  const bad = keys.filter((k) => !allowedMn.has(MN[k])).map((k) => `mn ${k}=«${MN[k]}»`).concat(keys.filter((k) => !allowedEn.has(EN[k])).map((k) => `en ${k}="${EN[k]}"`));
  expect(bad).toEqual([]);
  const countKeys = keys.filter((k) => k.startsWith('search.resultCount'));
  expect(countKeys.length).toBeGreaterThanOrEqual(1);
  for (const k of countKeys) expect(MN[k]).toBe('{count} илэрц олдлоо');
  // every type label of the story table has a resource value in both languages
  const mnVals = new Set(keys.map((k) => MN[k]));
  const enVals = new Set(keys.map((k) => EN[k]));
  for (const [rule, mn, en] of TYPE_LABELS) {
    expect(mnVals.has(mn), `rule ${rule} mn «${mn}»`).toBe(true);
    expect(enVals.has(en), `rule ${rule} en "${en}"`).toBe(true);
  }
});

test('AC45: English UI uses the en labels/messages, names come from the lang=en response; map labels stay Cyrillic (D11)', async ({ page }) => {
  const log = await mock(page, SEARCH_GLOB, (p) => ({
    body: fc([
      { ...FX.F5.feature, properties: { ...FX.F5.feature.properties, name: p.lang === 'en' ? 'Sukhbaatar Square' : 'Сүхбаатарын талбай' } },
      { ...FX.F4.feature, properties: { ...FX.F4.feature.properties, name: 'Бага Тойрог' } }, // no English name: Mongolian fallback
    ]),
  }));
  await openApp(page, { lang: 'en' });
  await typeQuery(page, 'Sukhbaatar');
  await waitSettled(page, 'Sukhbaatar');
  for (const l of log) expect(l.p.lang).toBe('en');
  const o = await options(page);
  expect(o.map((x) => [x.name, x.type])).toEqual([
    ['Sukhbaatar Square', 'Square'],
    ['Бага Тойрог', 'Neighbourhood'],
  ]);
  await expect(tid(page, 'search-input')).toHaveAttribute('placeholder', T.en.placeholder);
  await expect(tid(page, 'search-live')).toHaveText(T.en.count(2));
  // the Cyrillic name in the English UI is marked lang=mn (screen spec, language of parts)
  expect(await page.locator('#search-option-1 [data-testid=search-option-name]').getAttribute('lang')).toBe('mn');
  const layers = await page.evaluate(() => window.__nav002.map.getStyle().layers.filter((l) => l.type === 'symbol' && JSON.stringify(l.layout?.['text-field'] ?? '').includes('name')).map((l) => l.layout['text-field']));
  expect(layers.length).toBeGreaterThan(0);
  for (const tf of layers) expect(JSON.stringify(tf)).toContain(JSON.stringify(LABEL_EXPRESSION));
});

test('AC46: with my location active, bias has <= 3 decimals; no other request carries coordinates; query text and coordinates never reach storage, cookies or the console', async ({ browser }) => {
  const DEV = { latitude: 47.886123, longitude: 106.917456, accuracy: 15 };
  const ctx = await browser.newContext({ permissions: ['geolocation'], geolocation: DEV, viewport: { width: 1366, height: 768 } });
  const page = await ctx.newPage();
  const consoleLines = [];
  page.on('console', (m) => consoleLines.push(m.text()));
  const urls = [];
  page.on('request', (r) => urls.push(r.url()));
  const srch = await mock(page, SEARCH_GLOB, () => ({ body: fc([feature('Нууцлалтест', 106.9, 47.9)]) }));
  const rev = await mock(page, REVERSE_GLOB, () => ({ body: fc([feature('Ойрын', 106.917, 47.886)]) }));
  await openApp(page);
  await tid(page, 'my-location').click();
  await expect(tid(page, 'my-location')).toHaveAttribute('data-state', 'following', { timeout: 11_000 });
  const QUERY = 'Нууцлалтест';
  await typeQuery(page, QUERY);
  await waitSettled(page, QUERY);
  await tid(page, 'search-option').first().click();
  await page.waitForTimeout(1200);
  await typeQuery(page, '47.918912, 106.917634');
  await waitSettled(page, '47.918912, 106.917634');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(1200);
  expect(srch.length).toBeGreaterThanOrEqual(1);
  for (const l of srch) {
    expect(decimals(l.p.lat), `bias lat ${l.p.lat}`).toBeLessThanOrEqual(3);
    expect(decimals(l.p.lon), `bias lon ${l.p.lon}`).toBeLessThanOrEqual(3);
  }
  expect(rev.length).toBe(1);
  // no other request carries coordinates
  const others = urls.filter((u) => !/\/v1\/(search|reverse)\?/.test(u));
  const coordLike = others.filter((u) => /[?&](lat|lon|lng|latitude|longitude)=/i.test(u) || /47\.8861|106\.9174|47\.9189|106\.9176|47\.886|106\.917/.test(u));
  expect(coordLike).toEqual([]);
  // storage, cookies, console
  const store = await page.evaluate(() => {
    const dump = (s) => Object.keys(s).map((k) => `${k}=${s.getItem(k)}`).join('\n');
    return `${dump(localStorage)}\n${dump(sessionStorage)}\n${document.cookie}`;
  });
  const cookies = JSON.stringify(await ctx.cookies());
  const hay = `${store}\n${cookies}\n${consoleLines.join('\n')}`;
  for (const needle of [QUERY, 'Нууцлал', '47.918912', '106.917634', '47.9189', '47.886', '106.917', '47.8861']) {
    expect(hay.includes(needle), `«${needle}» found in storage/cookies/console`).toBe(false);
  }
  test.info().annotations.push({ type: 'AC46 storage keys', description: store.split('\n').filter(Boolean).map((l) => l.split('=')[0]).join(', ') || '(none)' });
  await ctx.close();
});

test('NAV-002 carried follow-ups 2 and 3 (docs + client): no "BA to add" for G1–G7; map-style says default archive max zoom exactly 14 and clients read maxzoom from the header; web/src does not hard-code the archive maxzoom', async () => {
  const screen = readFileSync(ROOT + 'docs/design/screens/NAV-002-web-map.md', 'utf8');
  expect(/BA to add/i.test(screen), 'NAV-002 screen spec still says "BA to add"').toBe(false);
  const style = readFileSync(ROOT + 'docs/design/map-style.md', 'utf8');
  const sec1 = style.split(/\n## /).slice(0, 3).join('\n## ');
  expect(sec1).toMatch(/max(imum)?\s*zoom[^.\n]*\b14\b/i);
  expect(sec1).toMatch(/header/i);
  // client: the basemap source must not declare minzoom/maxzoom (MapLibre reads them from the PMTiles header)
  const files = [];
  const walk = (d) => {
    for (const f of readdirSync(d)) {
      const p = d + '/' + f;
      if (statSync(p).isDirectory()) walk(p);
      else if (/\.ts$/.test(f) && !/\.test\.ts$/.test(f)) files.push(p);
    }
  };
  walk(WEB + 'src');
  const hits = files.flatMap((f) => readFileSync(f, 'utf8').split('\n').map((l, i) => [f.replace(ROOT, ''), i + 1, l])).filter(([, , l]) => /\bmaxzoom\s*:\s*1[45]\b/i.test(l));
  expect(hits).toEqual([]);
});

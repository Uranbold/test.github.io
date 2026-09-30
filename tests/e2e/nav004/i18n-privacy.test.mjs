// NAV-004 I. Strings and localisation (AC 48–50) and J. privacy (AC 52).
import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import {
  AC27, CYRILLIC, FIXTURE_ROUTE, REF, T, WEB, fmtDistance, lastAct, line, locationOn, mockReverse, mockRoute, nb, openApp, openPreview, osrmBody, osrmRoute, panel, routeReqs,
  rtimeline, setField, simpleBody, tid, waitFinal,
} from './helpers.mjs';

const MN = JSON.parse(readFileSync(WEB + 'src/i18n/mn.json', 'utf8'));
const EN = JSON.parse(readFileSync(WEB + 'src/i18n/en.json', 'utf8'));
const ok = (b, specs) => ({ body: simpleBody({ lat: b.locations[0].lat, lng: b.locations[0].lon }, { lat: b.locations[1].lat, lng: b.locations[1].lon }, specs) });

test('AC48: npm run check:i18n and npm run check:glossary exit 0 with the NAV-004 keys', () => {
  const i18n = execFileSync('npm', ['run', '-s', 'check:i18n'], { cwd: WEB, encoding: 'utf8' });
  const gl = execFileSync('npm', ['run', '-s', 'check:glossary'], { cwd: WEB, encoding: 'utf8' });
  const summary = gl.trim().split('\n').at(-1);
  const derived = gl.split('\n').filter((l) => /derived:/.test(l)).map((l) => l.trim().split(/\s+/)[1]);
  test.info().annotations.push({ type: 'AC48 check:i18n', description: i18n.trim().split('\n')[0].slice(0, 120) });
  test.info().annotations.push({ type: 'AC48 check:glossary', description: `${summary}; matched only by a derivation rule (not quoted in the glossary): ${derived.join(', ') || 'none'}` });
  expect(summary).toMatch(/(\d+)\/\1 mn values match/);
});

test('AC48: every NAV-004 mn value is a story/glossary string (N1–N23, existing rows, AC 27), placeholders kept literally; mn and en key sets identical', () => {
  // Story tables, quoted literally (helpers T and AC27), plus the reused NAV-002/NAV-003 keys.
  const allowed = new Set([
    ...Object.values(T.mn).filter((v) => typeof v === 'string'),
    'Маршрут {n}', '{count} маршрут олдлоо', 'Хамгийн ойрын зам сонгосон цэгээс {distance} зайтай', '+{days} өдөр', 'ц', 'мин', 'Тойрог: {n}-р гарц',
    ...Object.values(AC27).map((v) => v[0]),
  ]);
  const lc1 = (s) => s.charAt(0).toLowerCase() + s.slice(1);
  const keys = Object.keys(MN).filter((k) => /^(route|maneuver)\./.test(k) || ['unit.h', 'unit.min'].includes(k));
  const bad = keys.filter((k) => {
    const v = MN[k];
    return ![...allowed].some((a) => a === v || lc1(a) === lc1(v));
  }).map((k) => `${k}: «${MN[k]}»`);
  test.info().annotations.push({ type: 'AC48 NAV-004 keys checked', description: `${keys.length} keys` });
  expect(bad).toEqual([]);
  expect(Object.keys(EN).sort()).toEqual(Object.keys(MN).sort());
  // plural ".one" forms may spell the number out in English ("1 route found", screen spec › Copy)
  for (const k of keys.filter((k) => !k.endsWith(".one"))) for (const ph of MN[k].match(/\{\w+\}/g) ?? []) expect(EN[k], `${k} en keeps ${ph}`).toContain(ph);
});

test('AC49: English UI shows every NAV-004 label and message in English, "My location", decimal point; street names stay Cyrillic', async ({ page }) => {
  await mockReverse(page);
  await mockRoute(page, (b) => ok(b, [{ distance: 4600, duration: 780 }, { distance: 5200, duration: 900 }]));
  await openApp(page, { lang: 'en' });
  await openPreview(page, REF.P3);
  let p = await panel(page);
  expect(p.title).toBe(T.en.title);
  expect(p.originPh).toBe(T.en.originPlaceholder);
  expect(p.destPh).toBe(T.en.destinationPlaceholder);
  expect(p.originName).toBe(T.en.origin);
  expect(p.destName).toBe(T.en.destination);
  expect(p.destination).toBe(T.en.selectedPoint);
  await expect(tid(page, 'route-my-location')).toContainText(T.en.myLocation);
  await setField(page, 'origin', REF.P1);
  await waitFinal(page);
  p = await panel(page);
  expect(p.tabs.map((t) => t.text)).toEqual([T.en.car, T.en.walk, T.en.bike]);
  await expect(tid(page, 'route-avoid')).toContainText(T.en.avoid);
  expect(await tid(page, 'route-swap').getAttribute('aria-label')).toBe(T.en.swap);
  expect(await tid(page, 'route-close').getAttribute('aria-label')).toBe(T.en.close);
  expect(await page.locator('[role=tablist]').getAttribute('aria-label')).toBe(T.en.modes);
  expect(await tid(page, 'route-options').getAttribute('aria-label')).toBe(T.en.options);
  expect(nb(p.distance)).toBe('4.6 km');
  expect(nb(p.duration)).toBe('13 min');
  expect(nb(p.eta)).toMatch(/^Arrive at \d{2}:\d{2}$/);
  expect(nb(p.options[0].text)).toContain(T.en.option(1));
  expect(p.options[1].desc).toContain(T.en.alternative);
  expect(await page.locator('#route-directions-title').textContent()).toBe(T.en.directions);
  expect(p.steps[0].street).toBe('Энхтайвны өргөн чөлөө');
  expect(p.steps[0].streetLang).toBe('mn');
  // Panel chrome has no Cyrillic outside street names
  const chrome = await page.evaluate(() => {
    const panel = document.querySelector('[data-testid=route-panel]').cloneNode(true);
    panel.querySelectorAll('.st, input').forEach((e) => e.remove());
    return panel.textContent + ' ' + [...panel.querySelectorAll('[aria-label]')].map((e) => e.getAttribute('aria-label')).join(' ');
  });
  const labels = await page.evaluate(() => [...document.querySelectorAll('[data-testid=route-panel] [aria-label]')].map((e) => e.getAttribute('aria-label')));
  const cyr = labels.filter((l) => CYRILLIC.test(l.replace(/Энхтайвны өргөн чөлөө|Чингисийн өргөн чөлөө/g, '')));
  expect(cyr, 'aria-labels in the English UI contain no Cyrillic outside street names').toEqual([]);
  expect(CYRILLIC.test(chrome.replace(/Энхтайвны өргөн чөлөө|Чингисийн өргөн чөлөө/g, ''))).toBe(false);
});

test('AC50: switching language with a route shown: labels ≤ 500 ms, instruction texts ≤ 1,500 ms, 0 route requests, selected route kept', async ({ page }) => {
  await mockReverse(page);
  const COORDS = line(REF.P1, REF.P3, 40, 0.002);
  const body = osrmBody([
    osrmRoute({ coords: COORDS, distance: 5400, duration: 780, steps: FIXTURE_ROUTE.steps }),
    osrmRoute({ coords: line(REF.P1, REF.P3, 40, 0.006), distance: 6100, duration: 900, steps: FIXTURE_ROUTE.steps.slice(0, 8).concat(FIXTURE_ROUTE.steps.slice(-1)) }),
  ]);
  const rr = await mockRoute(page, () => ({ body }));
  await openApp(page);
  await openPreview(page, REF.P3);
  await setField(page, 'origin', REF.P1);
  await waitFinal(page);
  await tid(page, 'route-option').nth(1).click();
  await expect.poll(async () => (await panel(page)).options.find((o) => o.checked === 'true')?.index).toBe(1);
  await page.evaluate(() => {
    window.__langT = [];
    new MutationObserver(() => {
      const t = document.querySelector('[data-testid=route-title]')?.textContent;
      const s = document.querySelector('[data-testid=route-step] .in')?.textContent;
      window.__langT.push({ t: performance.now(), title: t, step: s });
    }).observe(document, { subtree: true, childList: true, characterData: true });
  });
  await tid(page, 'language-toggle').click();
  const tAct = await lastAct(page, 'language-toggle');
  await expect.poll(async () => (await panel(page)).title).toBe(T.en.title);
  await page.waitForTimeout(800);
  const tl = await page.evaluate(() => window.__langT);
  const tTitle = tl.find((x) => x.t >= tAct && x.title === T.en.title)?.t;
  const tStep = tl.find((x) => x.t >= tAct && x.step === AC27['depart.n'][1])?.t;
  test.info().annotations.push({ type: 'AC50 switch', description: `labels ${(tTitle - tAct).toFixed(0)} ms, instruction texts ${(tStep - tAct).toFixed(0)} ms` });
  expect(tTitle - tAct).toBeLessThanOrEqual(500);
  expect(tStep - tAct).toBeLessThanOrEqual(1500);
  const p = await panel(page);
  expect(p.options.find((o) => o.checked === 'true').index).toBe(1);
  expect(p.steps.length).toBe(9);
  for (const [i, s] of FIXTURE_ROUTE.steps.slice(0, 8).entries()) expect(p.steps[i].text).toBe(AC27[s.expect][1]);
  expect(p.steps.at(-1).text).toBe(AC27['arrive.right'][1]);
  expect(rr.length, '0 route requests on a language switch (client-side text, ADR-0008)').toBe(1);
  expect(nb(p.distance)).toBe(fmtDistance(6100, 'en'));
});

test('AC52: route bodies, origin/destination coordinates and the device position are not written to localStorage, sessionStorage, cookies, the console or the URL; the fix leaves the browser only as the «Миний байршил» origin', async ({ page, context }) => {
  const consoleLines = [];
  page.on('console', (m) => consoleLines.push(m.text()));
  page.on('pageerror', (e) => consoleLines.push(String(e)));
  const outbound = [];
  page.on('request', (r) => outbound.push({ url: r.url(), body: r.postData() ?? '' }));
  await mockReverse(page);
  await mockRoute(page, (b) => ok(b));
  await openApp(page);
  const FIX = { lat: 47.917373, lng: 106.919482 };
  await locationOn(page, context, FIX);
  await openPreview(page, REF.P3);
  await waitFinal(page);
  await tid(page, 'route-swap').click();
  await waitFinal(page);
  await tid(page, 'route-tab-walk').click();
  await page.waitForTimeout(500);
  await waitFinal(page);
  await tid(page, 'language-toggle').click();
  await page.waitForTimeout(500);
  const store = await page.evaluate(() => ({
    local: Object.fromEntries(Object.keys(localStorage).map((k) => [k, localStorage.getItem(k)])),
    session: Object.fromEntries(Object.keys(sessionStorage).map((k) => [k, sessionStorage.getItem(k)])),
    cookie: document.cookie,
    url: location.href,
  }));
  const cookies = await context.cookies();
  const pats = [/47\.91\d/, /106\.91\d/, /47\.88\d/, /"locations"/, /costing/];
  const leaks = [];
  const scan = (where, s) => {
    for (const p of pats) if (p.test(s)) leaks.push(`${where}: ${p} in ${s.slice(0, 120)}`);
  };
  scan('localStorage', JSON.stringify(store.local));
  scan('sessionStorage', JSON.stringify(store.session));
  scan('document.cookie', store.cookie);
  scan('cookies', JSON.stringify(cookies));
  scan('URL', store.url);
  for (const l of consoleLines) scan('console', l);
  test.info().annotations.push({ type: 'AC52 storage keys', description: JSON.stringify(Object.keys(store.local).concat(Object.keys(store.session))) });
  expect(leaks).toEqual([]);
  // The fix appears only as the origin/destination of route requests (after the swap it is the destination of the
  // same «Миний байршил» point) and never in any other request (URL or body).
  const fixRe = /47\.9173|106\.9194/;
  const withFix = outbound.filter((r) => fixRe.test(r.url) || fixRe.test(r.body));
  const other = withFix.filter((r) => !/\/v1\/route$/.test(r.url));
  expect(other.map((r) => r.url), 'the device position is sent nowhere but /v1/route').toEqual([]);
  const reqs = await routeReqs(page);
  expect(reqs[0].body.locations[0].lat).toBeCloseTo(FIX.lat, 6);
  for (const r of withFix) expect(r.url).toBe('http://localhost:8080/v1/route');
});

// NAV-004 B. Route request: AC 10–14 (mocked gateway responses; the request bodies are the real client's).
import { test, expect } from '@playwright/test';
import {
  REF, T, COSTING, lastAct, maxInFlight, mockReverse, mockRoute, openApp, openPreview, panel, rawLocationDecimals, routeReqs, rview, setField, simpleBody,
  tid, waitFinal, firstAfter,
} from './helpers.mjs';

const echo = (b, specs) => ({ body: simpleBody({ lat: b.locations[0].lat, lng: b.locations[0].lon }, { lat: b.locations[1].lat, lng: b.locations[1].lon }, specs) });

async function ready(page, lang) {
  await mockReverse(page);
  await openApp(page, lang ? { lang } : {});
  await openPreview(page, REF.P3);
  await setField(page, 'origin', REF.P1);
  await waitFinal(page);
}

for (const lang of ['mn', 'en']) {
  test(`AC10 (${lang} UI): exactly one POST {gateway}/v1/route with the contracted body; language ${lang === 'mn' ? 'mn-MN' : 'en-US'}; no toll option; no GET ?json=`, async ({ page }) => {
    const rr = await mockRoute(page, (b) => echo(b));
    const net = [];
    page.on('request', (r) => {
      if (/\/v1\/route/.test(r.url())) net.push({ method: r.method(), url: r.url() });
    });
    await ready(page, lang);
    expect(rr.length).toBe(1);
    const req = (await routeReqs(page))[0];
    expect(req.method).toBe('POST');
    expect(req.url).toBe('http://localhost:8080/v1/route');
    const b = req.body;
    expect(b.locations.length).toBe(2);
    expect(b.locations[0].lat).toBeCloseTo(REF.P1.lat, 5);
    expect(b.locations[0].lon).toBeCloseTo(REF.P1.lng, 5);
    expect(b.locations[1].lat).toBeCloseTo(REF.P3.lat, 5);
    expect(b.locations[1].lon).toBeCloseTo(REF.P3.lng, 5);
    const decs = rawLocationDecimals(req.raw);
    expect(decs.length).toBe(4);
    for (const d of decs) expect(d.dec, `${d.k}=${d.v} has >= 5 decimals in the JSON text`).toBeGreaterThanOrEqual(5);
    expect(b.costing).toBe('auto');
    expect(b.alternates).toBe(2);
    expect(b.format).toBe('osrm');
    expect(b.banner_instructions).toBe(true);
    expect(b.units).toBe('kilometers');
    expect(b.language).toBe(lang === 'mn' ? 'mn-MN' : 'en-US');
    expect(b.costing_options, 'avoid off: no costing_options').toBeUndefined();
    expect(JSON.stringify(b)).not.toMatch(/toll/i);
    test.info().annotations.push({ type: 'AC10 body keys', description: Object.keys(b).join(', ') });
    // Network level: only POST (plus the CORS preflight), never a GET with ?json=
    for (const r of net) {
      expect(['POST', 'OPTIONS']).toContain(r.method);
      expect(r.url).not.toContain('?');
    }
  });
}

test('AC11: «Машин» selected at load; tabs map to auto/pedestrian/bicycle; one request per tab after it stays selected 300 ms; ArrowRight twice within 300 ms → 1 request for the final tab', async ({ page }) => {
  const rr = await mockRoute(page, (b) => echo(b));
  await ready(page);
  let p = await panel(page);
  expect(p.tabs.map((t) => t.text)).toEqual([T.mn.car, T.mn.walk, T.mn.bike]);
  expect(p.tabs.map((t) => t.selected)).toEqual(['true', 'false', 'false']);
  expect(await tid(page, 'route-tab-car').evaluate((e) => e.closest('[role=tablist]').getAttribute('aria-label'))).toBe(T.mn.modes);
  expect(rr.length).toBe(1);
  // click «Явган»: one request after >= 300 ms
  await tid(page, 'route-tab-walk').click();
  const tClick = await lastAct(page, 'route-tab-walk');
  await waitFinal(page);
  await page.waitForTimeout(400);
  let reqs = await routeReqs(page);
  expect(reqs.length).toBe(2);
  expect(reqs[1].body.costing).toBe(COSTING.walk);
  const settle = reqs[1].t - tClick;
  test.info().annotations.push({ type: 'AC11 click → request', description: `${settle.toFixed(0)} ms` });
  expect(settle).toBeGreaterThanOrEqual(290);
  expect(settle).toBeLessThan(2000); // no upper bound in AC 11; SwiftShader frames delay timers here
  // keyboard: back to «Машин» first (real key press), then ArrowRight twice 100 ms apart → one request, for «Дугуй».
  // The two presses are dispatched inside the page: with SwiftShader WebGL the main thread is busy with map frames after
  // each tab change, so Playwright's real key presses arrived 500–1,400 ms apart (measured with event.timeStamp).
  await tid(page, 'route-tab-walk').focus();
  await page.keyboard.press('ArrowLeft');
  await expect(tid(page, 'route-tab-car')).toHaveAttribute('aria-selected', 'true');
  await page.waitForTimeout(800);
  await waitFinal(page);
  const n0 = (await routeReqs(page)).length;
  const tKeys = await page.evaluate(
    () =>
      new Promise((res) => {
        const fire = () => document.activeElement.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
        const t1 = performance.now();
        fire();
        // 100 ms later (busy wait: a timer would fire late while SwiftShader renders the frame the first press caused)
        while (performance.now() - t1 < 100);
        fire();
        res({ t1, t2: performance.now() });
      }),
  );
  await page.waitForTimeout(900);
  await waitFinal(page);
  test.info().annotations.push({ type: 'AC11 two ArrowRight', description: `${(tKeys.t2 - tKeys.t1).toFixed(0)} ms apart` });
  expect(tKeys.t2 - tKeys.t1).toBeLessThan(300);
  const after = (await routeReqs(page)).slice(n0);
  expect(after.map((r) => r.body.costing)).toEqual([COSTING.bike]);
  expect(after[0].t - tKeys.t2).toBeGreaterThanOrEqual(290);
  reqs = await routeReqs(page);
  test.info().annotations.push({ type: 'AC11 costings sent', description: reqs.map((r) => r.body.costing).join(' → ') });
  expect(reqs.map((r) => r.body.costing)).toEqual(['auto', 'pedestrian', 'auto', 'bicycle']);
  p = await panel(page);
  expect(p.tabs.map((t) => t.selected)).toEqual(['false', 'false', 'true']);
  // selecting the already-selected tab sends nothing
  await tid(page, 'route-tab-bike').click();
  await page.waitForTimeout(600);
  expect((await routeReqs(page)).length).toBe(4);
});

test('AC12: avoid switch visible on «Машин», off by default; on → costing_options.auto.exclude_unpaved=true, one request per toggle; state kept across tabs; hidden on «Явган»/«Дугуй» with no costing_options sent', async ({ page }) => {
  const rr = await mockRoute(page, (b) => echo(b));
  await ready(page);
  let p = await panel(page);
  expect(p.avoidVisible).toBe(true);
  expect(p.avoidChecked).toBe('false');
  expect(await tid(page, 'route-avoid').getAttribute('role')).toBe('switch');
  await expect(tid(page, 'route-avoid')).toContainText(T.mn.avoid);
  await tid(page, 'route-avoid').click();
  await waitFinal(page);
  await page.waitForTimeout(300);
  expect(rr.length).toBe(2);
  expect(rr[1].body.costing_options).toEqual({ auto: { exclude_unpaved: true } });
  expect((await panel(page)).avoidChecked).toBe('true');
  for (const m of ['walk', 'bike']) {
    await tid(page, `route-tab-${m}`).click();
    await page.waitForTimeout(450);
    await waitFinal(page);
    p = await panel(page);
    expect(p.avoidVisible, `switch hidden on ${m}`).toBe(false);
    const last = rr.at(-1).body;
    expect(last.costing).toBe(COSTING[m]);
    expect(last.costing_options, `${m}: no costing_options`).toBeUndefined();
  }
  await tid(page, 'route-tab-car').click();
  await page.waitForTimeout(450);
  await waitFinal(page);
  p = await panel(page);
  expect(p.avoidVisible).toBe(true);
  expect(p.avoidChecked, 'state kept across tabs').toBe('true');
  expect(rr.at(-1).body.costing_options).toEqual({ auto: { exclude_unpaved: true } });
  const n = rr.length;
  await tid(page, 'route-avoid').focus();
  await page.keyboard.press(' ');
  await waitFinal(page);
  await page.waitForTimeout(300);
  expect(rr.length).toBe(n + 1);
  expect(rr.at(-1).body.costing_options).toBeUndefined();
  expect(JSON.stringify(rr.map((r) => r.body))).not.toMatch(/toll/i);
});

test('AC13: a newer triggering action aborts/ignores the older request; a late older response (delayed 1 s) never replaces the newer result; at most 1 request in flight', async ({ page }) => {
  let calls = 0;
  await mockRoute(page, (b) => {
    calls++;
    if (b.costing === 'pedestrian') return { ...echo(b, [{ distance: 1111, duration: 900 }]), delay: 1000 };
    if (b.costing === 'bicycle') return echo(b, [{ distance: 2222, duration: 480 }]);
    return echo(b, [{ distance: 4300, duration: 720 }]);
  });
  await ready(page);
  await tid(page, 'route-tab-walk').click();
  await page.waitForTimeout(450); // walk request is in flight (1 s delay)
  await tid(page, 'route-tab-bike').click();
  await page.waitForTimeout(2000); // walk response arrives late, after the bike one
  await waitFinal(page);
  const p = await panel(page);
  expect(p.distance?.replace(/ /g, ' ')).toBe('2,2 км');
  const reqs = await routeReqs(page);
  expect(reqs.map((r) => r.body.costing)).toEqual(['auto', 'pedestrian', 'bicycle']);
  expect(maxInFlight(reqs), 'at most one route request in flight').toBe(1);
  test.info().annotations.push({ type: 'AC13 older request', description: `abortAt=${reqs[1].abortAt?.toFixed(0)} outcome=${reqs[1].outcome}` });
  // Back to «Машин»: the newest result is shown
  await tid(page, 'route-tab-car').click();
  await page.waitForTimeout(1500);
  expect((await panel(page)).distance?.replace(/ /g, ' ')).toBe('4,3 км');
});

test('AC14: start and destination within 10 m → no request and «Эхлэх цэг, очих газар ижил байна» within 500 ms; 11 m apart → one request', async ({ page }) => {
  const rr = await mockRoute(page, (b) => echo(b));
  await mockReverse(page);
  await openApp(page);
  await openPreview(page, REF.P2);
  const near = { lat: REF.P2.lat + 7 / 111_195, lng: REF.P2.lng }; // 7 m north
  await setField(page, 'origin', near);
  const tAct = await lastAct(page, 'route-origin');
  await expect.poll(async () => (await panel(page)).state).toBe('same-point');
  const dt = await firstAfter(page, tAct, "(s) => s.state === 'same-point'");
  test.info().annotations.push({ type: 'AC14 same-point', description: `${dt?.toFixed(0)} ms after Enter` });
  expect(dt).toBeLessThanOrEqual(500);
  const p = await panel(page);
  expect(p.rowText).toBe(T.mn.samePoint);
  expect(p.retry).toBeNull();
  await page.waitForTimeout(500);
  expect(rr.length).toBe(0);
  // 11.2 m (just outside) → request
  const far = { lat: REF.P2.lat + 11.2 / 111_195, lng: REF.P2.lng };
  await setField(page, 'origin', far);
  await waitFinal(page);
  expect(rr.length).toBe(1);
  expect((await rview(page)).state).toBe('route');
});

// Regression test for QA defect NAV-004-D2 (found 2026-09-30, run 1). Stays forever.
// Steps: open the preview with location off (the origin field is focused and its list with «Миний байршил» is open,
// AC 4/5), then click «Явган» once. Expected (AC 11): «Явган» is selected, and the route request sent when the origin
// is set uses costing "pedestrian". Actual (run 1): the origin field's focusout closes its in-flow list, the tabs move
// up 82 px between mousedown and mouseup (266 → 184 px at 1366×768), the click lands on the panel, and «Машин» stays
// selected, so the request uses "auto". The same applies to every control below the open list (avoid switch).
test('AC11 regression D2: with the origin list open (panel just opened, location off) one click on «Явган» selects it; the next request uses pedestrian', async ({ page }) => {
  const rr = await mockRoute(page, (b) => echo(b));
  await mockReverse(page);
  await openApp(page);
  await openPreview(page, REF.P3);
  await expect(page.locator('#route-origin-results')).toBeVisible();
  const before = await tid(page, 'route-tab-walk').boundingBox();
  await tid(page, 'route-tab-walk').click();
  const after = await tid(page, 'route-tab-walk').boundingBox();
  test.info().annotations.push({ type: 'D2 tab position', description: `top ${before.y.toFixed(0)} → ${after.y.toFixed(0)} px` });
  await expect(tid(page, 'route-tab-walk'), 'one click selects «Явган»').toHaveAttribute('aria-selected', 'true');
  await page.waitForTimeout(400);
  await setField(page, 'origin', REF.P1);
  await waitFinal(page);
  expect(rr.at(-1).body.costing).toBe('pedestrian');
});

test('AC12 regression D2: with the origin list open, one click on «Шороон замаас зайлсхийх» turns it on', async ({ page }) => {
  await mockRoute(page, (b) => echo(b));
  await mockReverse(page);
  await openApp(page);
  await openPreview(page, REF.P3);
  await expect(page.locator('#route-origin-results')).toBeVisible();
  await tid(page, 'route-avoid').click();
  await expect(tid(page, 'route-avoid')).toHaveAttribute('aria-checked', 'true');
});

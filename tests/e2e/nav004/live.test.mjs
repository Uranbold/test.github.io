// NAV-004 live gateway tests (no route mocking): AC 15 (timing), 16 (RS3 k), 28 (RS1–RS6 turn text scan), 29 (English
// RS1, RS3), 33 (RS5), 34 (RS7), 35 (RS8 = P1 → X2 on foot, ADR-0008 §4; RS8a P1 → X1 recorded), 51 (network hygiene).
// Pacing: at most 2 route requests per second for the whole run (livePace), serial (workers: 1). Reverse lookups of the
// coordinate cards are mocked (not under test here) to keep the shared gateway load low.
import { test, expect } from '@playwright/test';
import { writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { CYRILLIC, REF, RS, T, ac28Problems, allowedTexts, lastAct, lines, livePace, mockReverse, openApp, openPreview, panel, routeReqs, rtimeline, setField, tid, waitFinal } from './helpers.mjs';

const OUT = fileURLToPath(new URL('../test-results/nav004/', import.meta.url));
const record = {};

/** Opens the preview for a reference route (destination via coordinate card, mode tab, avoid, then origin). */
async function openRS(page, rs, { lang } = {}) {
  const r = RS[rs];
  await openPreview(page, REF[r.d]);
  // Close the origin field's list first (Escape keeps the panel open): with the list open, the first click on a mode
  // tab is lost (QA defect NAV-004-D2, regression test in request.test.mjs), and these tests are about the routes.
  await page.keyboard.press('Escape');
  if (r.mode !== 'car') {
    await tid(page, `route-tab-${r.mode}`).click();
    await page.waitForTimeout(350);
  }
  if (r.avoid) await tid(page, 'route-avoid').click();
  await setField(page, 'origin', REF[r.o]);
  return waitFinal(page, 20_000);
}

/** Time from the triggering action (Enter in the destination field) to the selected line + summary on screen. */
async function sample(page, rs) {
  const r = RS[rs];
  await setField(page, 'destination', REF[r.d]);
  const tAct = await lastAct(page, 'route-destination');
  await waitFinal(page, 20_000);
  const tl = await rtimeline(page);
  const hit = tl.find((s) => s.t >= tAct && s.state === 'route' && s.summary);
  const l = await lines(page);
  return { ms: hit ? hit.t - tAct : null, lines: l.length, selected: l.filter((x) => x.selected).length };
}

test.describe.configure({ mode: 'serial' });

test('AC15: RS1–RS4 (20 samples, ≤ 2 route requests/s): selected line + summary within 1,500 ms in ≥ 19 of 20; RS6 within 3,000 ms in ≥ 4 of 5', async ({ page }) => {
  test.setTimeout(300_000);
  await mockReverse(page);
  await openApp(page);
  const res = {};
  for (const rs of ['RS1', 'RS2', 'RS3', 'RS4', 'RS6']) {
    await page.reload();
    await page.waitForFunction(() => !!window.__nav004?.route?.controller, null, { timeout: 20_000 });
    await expect(tid(page, 'zoom-in')).toBeVisible();
    await page.waitForTimeout(1500);
    await openRS(page, rs);
    await livePace(page);
    res[rs] = [];
    for (let i = 0; i < 5; i++) {
      const s = await sample(page, rs);
      res[rs].push(s);
      expect(s.selected, `${rs} sample ${i}: one selected line`).toBe(1);
      await livePace(page);
    }
  }
  const city = ['RS1', 'RS2', 'RS3', 'RS4'].flatMap((k) => res[k].map((s) => s.ms));
  const within = city.filter((ms) => ms !== null && ms <= 1500).length;
  const rs6 = res.RS6.map((s) => s.ms);
  const rs6ok = rs6.filter((ms) => ms !== null && ms <= 3000).length;
  const sorted = [...city].sort((a, b) => a - b);
  const p95 = sorted[Math.ceil(0.95 * sorted.length) - 1];
  record.AC15 = { samples: Object.fromEntries(Object.entries(res).map(([k, v]) => [k, v.map((s) => Math.round(s.ms))])), cityWithin1500: `${within}/20`, cityP95: Math.round(p95), rs6Within3000: `${rs6ok}/5` };
  test.info().annotations.push({ type: 'AC15', description: JSON.stringify(record.AC15) });
  expect(within).toBeGreaterThanOrEqual(19);
  expect(rs6ok).toBeGreaterThanOrEqual(4);
});

test('AC16 + AC28 + AC29 + AC33 + AC34 + AC35 + AC51: live RS1–RS8 session in both UI languages (≤ 2 route requests/s)', async ({ page }) => {
  test.setTimeout(300_000);
  const hosts = new Set();
  const routeUrls = [];
  page.on('request', (r) => {
    const u = new URL(r.url());
    hosts.add(u.host);
    // routing-like requests to any host other than the app's own dev server (whose module paths contain "route")
    if (u.host !== 'localhost:5181' && /route|osrm|valhalla|graphhopper|hamuga|directions/i.test(r.url()) && !/\/v1\/(search|reverse)/.test(r.url())) routeUrls.push(`${r.method()} ${r.url()}`);
  });
  await mockReverse(page);
  await openApp(page);
  const out = {};
  const problems = [];
  const reload = async () => {
    await page.reload();
    await page.waitForFunction(() => !!window.__nav004?.route?.controller, null, { timeout: 20_000 });
    await expect(tid(page, 'zoom-in')).toBeVisible();
    await page.waitForTimeout(1200);
  };
  for (const rs of ['RS1', 'RS2', 'RS3', 'RS4', 'RS5', 'RS6', 'RS7', 'RS8', 'RS8a']) {
    await reload();
    const t0 = await page.evaluate(() => performance.now());
    const state = await openRS(page, rs);
    const p = await panel(page);
    const v = await page.evaluate(() => ({ k: window.__nav004.route.controller.view.response?.routes.length ?? 0 }));
    const reqs = await routeReqs(page);
    const last = reqs.at(-1);
    out[rs] = { state, k: v.k, steps: p.steps.length, gatewayMs: last.end ? Math.round(last.end - last.t) : null, distance: p.distance, duration: p.duration, row: p.rowText };
    // AC 28 (mn) on every route of RS1–RS6
    if (['RS1', 'RS2', 'RS3', 'RS4', 'RS5', 'RS6'].includes(rs) && state === 'route') {
      for (let i = 0; i < v.k; i++) {
        if (v.k > 1) {
          await tid(page, 'route-option').nth(i).click();
          await expect.poll(async () => (await panel(page)).options.find((o) => o.checked === 'true')?.index).toBe(i);
        }
        const q = await panel(page);
        for (const s of q.steps) {
          const pr = ac28Problems(s.text);
          if (pr.length) problems.push(`${rs} route ${i + 1} step ${s.index} (${s.key}): «${s.text}» ${pr.join(', ')}`);
        }
        out[rs][`keys${i + 1}`] = [...new Set(q.steps.map((s) => s.key))].join(',');
      }
    }
    // AC 29 English RS1 and RS3 (0 requests: client text)
    if (['RS1', 'RS3'].includes(rs)) {
      const n = reqs.length;
      await tid(page, 'language-toggle').click();
      await page.waitForTimeout(700);
      const en = allowedTexts('en');
      for (let i = 0; i < v.k; i++) {
        if (v.k > 1) await tid(page, 'route-option').nth(i).click();
        const q = await panel(page);
        for (const s of q.steps) {
          if (!en.has(s.text)) problems.push(`${rs} EN route ${i + 1} step ${s.index}: "${s.text}" is not an AC 27 en text`);
          if (CYRILLIC.test(s.text)) problems.push(`${rs} EN step ${s.index}: Cyrillic in "${s.text}"`);
        }
      }
      expect((await routeReqs(page)).length, `${rs}: language switch sends 0 route requests`).toBe(n);
      await tid(page, 'language-toggle').click();
      await page.waitForTimeout(300);
    }
    if (rs === 'RS5') expect(['route', 'no-route'], 'RS5: a route or «Маршрут олдсонгүй», never an error or spinner (AC 33)').toContain(state);
    if (rs === 'RS7') {
      expect(state).toBe('out-of-area');
      expect(p.rowText).toBe(T.mn.outOfArea);
      const tl = await rtimeline(page);
      const tState = tl.find((s) => s.t >= last.t && s.state === 'out-of-area').t;
      const tAct = await lastAct(page, 'route-origin');
      out.RS7.msFromAction = Math.round(tState - tAct);
      expect(tState - tAct, 'RS7 within 4 s of the triggering action (AC 34)').toBeLessThanOrEqual(4000);
      expect((await lines(page)).length).toBe(0);
    }
    if (rs === 'RS8') {
      expect(state).toBe('too-far');
      expect(p.rowText).toBe(T.mn.tooFar);
    }
    if (['RS1', 'RS2', 'RS3', 'RS4', 'RS6'].includes(rs)) expect(state, rs).toBe('route');
    await livePace(page, 2);
  }
  record.live = out;
  record.AC28problems = problems;
  record.AC51 = { hosts: [...hosts].sort(), routeLike: [...new Set(routeUrls)] };
  test.info().annotations.push({ type: 'live RS results', description: JSON.stringify(out) });
  test.info().annotations.push({ type: 'AC16 RS3 k', description: String(out.RS3.k) });
  test.info().annotations.push({ type: 'AC28/29 problems', description: problems.length ? problems.slice(0, 20).join(' | ') : 'none' });
  test.info().annotations.push({ type: 'AC51 hosts', description: JSON.stringify(record.AC51) });
  expect(problems, problems.join('\n')).toEqual([]);
  // AC 51: only the app origin and the gateway; every route request goes to the gateway /v1/route
  expect([...hosts].sort()).toEqual(['localhost:5181', 'localhost:8080']);
  for (const u of routeUrls) expect(u).toMatch(/^(POST|OPTIONS) http:\/\/localhost:8080\/v1\/route$/);
  mkdirSync(OUT, { recursive: true });
  writeFileSync(OUT + 'live-latest.json', JSON.stringify(record, null, 1));
});

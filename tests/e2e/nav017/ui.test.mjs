// NAV-017 C–G: partial replays for pause on hide (AC 15), track end (AC 16), recenter (AC 23), speech error (AC 29),
// mute (AC 30), language switch (AC 31), «Дуусгах» (AC 35), wake lock variants (AC 39), offline (AC 40), theme and
// rotation (AC 41), the depart prompt and audio unlock inside the «Эхлэх» handler (AC 27) and the 3 s voice wait
// (AC 28). Production demo-mode build, Playwright clock installed before navigation, stubs from helpers.qaInit.
import { test, expect } from '@playwright/test';
import { S, ROUTES, openDemo, selectRoute, startReplay, qa, spoken, tick, tid, golden, netLog, site, routeBody, readGpx, intersects } from './helpers.mjs';

test.use({ reducedMotion: 'reduce' });

const run = async (page, seconds, chunk = 1000) => { for (let left = seconds * 1000; left > 0; left -= chunk) await page.clock.runFor(Math.min(chunk, left)); };
const dist = (page) => tid(page, 'demo-nav-distance').textContent();
const nonTile = (reqs, from = 0) => reqs.slice(from).filter((r) => !r.url.includes('/tiles/basemap.pmtiles') && !/^(data|blob):/.test(r.url)).map((r) => r.url);
const hideCanvas = (page) => page.addStyleTag({ content: '.maplibregl-canvas{visibility:hidden !important}' });

test('AC27: depart prompt starts ≤ 2 s after «Эхлэх»; the first speak() and the AudioContext creation/resume happen inside the «Эхлэх» click handler', async ({ page }) => {
  await openDemo(page, { voices: ['mn-MN'] });
  await selectRoute(page, 'R1');
  await startReplay(page);
  await tick(page, 2000);
  const log = await qa(page);
  const first = log.speakRel.find((s) => s.text);
  expect(first.text).toBe(golden('G1', 'mn')[0].text);
  expect(first.rel).toBeLessThanOrEqual(2000);
  expect(log.speak[0].inClick, 'first speak() inside the activation handler').toBe(true);
  const ctxStart = [...log.ctxCreated, ...log.ctxResume].filter((c) => c.t >= log.t0);
  expect(ctxStart.length).toBeGreaterThan(0);
  expect(ctxStart.some((c) => c.inClick), 'AudioContext created or resumed inside the handler').toBe(true);
});

test('AC27/AC29: with no Mongolian voice the depart chime plays inside the «Эхлэх» handler (≤ 2 s) and no speak() gets Mongolian text', async ({ page }) => {
  await openDemo(page, { voices: ['en-US'] });
  await selectRoute(page, 'R1');
  await startReplay(page);
  await tick(page, 2000);
  const log = await qa(page);
  expect(log.chimesRel.length).toBe(1);
  expect(log.chimesRel[0]).toBeLessThanOrEqual(2000);
  expect(log.speak.filter((s) => /[Ѐ-ӿ]/.test(s.text))).toEqual([]);
  expect([...log.ctxCreated, ...log.ctxResume].some((c) => c.inClick)).toBe(true);
});

test('AC28: an empty voice list that fills on voiceschanged after 500 ms is waited for (≤ 3 s): the Mongolian voice is used, no A1', async ({ page }) => {
  await openDemo(page, { voices: ['mn-MN'], voicesLate: true });
  await tick(page, 1000);
  await selectRoute(page, 'R1');
  await startReplay(page);
  await tick(page, 2000);
  const log = await qa(page);
  expect(spoken(log)[0]?.voice).toBe('mn-MN');
  await expect(page.locator('[data-testid="demo-nav-messages"] [data-kind="voice-unavailable"]')).toBeHidden();
});

test('AC15: page hidden → clock paused ≤ 1 s, utterance stopped, nothing produced while hidden; visible → resumes ≤ 1 s from the same position and time, 0 prompts repeated or skipped (rest of R1 = golden shifted by the pause)', async ({ page }) => {
  test.setTimeout(20 * 60_000);
  await openDemo(page, { voices: ['mn-MN'] });
  await hideCanvas(page);
  await selectRoute(page, 'R1');
  await startReplay(page);
  await run(page, 231, 5000);
  await run(page, 1.5, 500); // «150 метрт зүүн тийш эргэнэ үү» starts at ~232 s and is still playing
  const before = await qa(page);
  expect(spoken(before).at(-1)?.text).toBe('150 метрт зүүн тийш эргэнэ үү');
  const cancelsBefore = before.cancel;
  const d0 = await dist(page);
  const r0 = await tid(page, 'demo-nav-remaining').textContent();
  await page.evaluate(() => window.__qaSetHidden(true));
  await tick(page, 1000);
  const afterHide = await qa(page);
  expect(afterHide.cancel, 'utterance cancelled on hide').toBeGreaterThan(cancelsBefore);
  await run(page, 60, 5000); // 60 s hidden: the left turn at 243 s would be due here if the clock ran
  const hidden = await qa(page);
  expect(hidden.speak.length, 'no prompt while hidden').toBe(afterHide.speak.length);
  expect(hidden.chimes.length).toBe(afterHide.chimes.length);
  expect(await dist(page), 'position frozen while hidden').toBe(d0);
  expect(await tid(page, 'demo-nav-remaining').textContent(), 'progress frozen while hidden').toBe(r0);
  await page.evaluate(() => window.__qaSetHidden(false));
  await run(page, 90, 5000);
  const end = await qa(page);
  const after = spoken(end).filter((s) => s.t > 233);
  // remaining golden prompts after 233 s, shifted by the ~61 s pause
  const rest = golden('G1', 'mn').filter((g) => g.t > 233);
  expect(after.map((s) => s.text)).toEqual(rest.map((g) => g.text));
  const shift = after[0].t - rest[0].t;
  expect(shift).toBeGreaterThan(59);
  expect(shift).toBeLessThan(64);
  after.forEach((s, i) => expect(Math.abs(s.t - shift - rest[i].t), s.text).toBeLessThanOrEqual(2));
  // wake lock re-requested within 1 s after visible again (AC 39)
  const reWake = end.wake.filter((w) => w.t > hidden.speak.at(-1).t);
  expect(reWake.length).toBeGreaterThanOrEqual(1);
});

test('AC16: a track that ends without arrival stops at the last fix and behaves as «Дуусгах» (picker again, nothing selected)', async ({ page }) => {
  const track = readGpx(ROUTES.R1.track).slice(0, 60); // first 60 s of G1 only
  const body = await routeBody('r1', { track });
  await page.route('**/demo-routes/r1.json', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) }));
  await openDemo(page, { voices: ['mn-MN'] });
  await selectRoute(page, 'R1');
  await startReplay(page);
  await run(page, 62, 2000);
  await expect(tid(page, 'demo-picker')).toBeVisible();
  await expect(tid(page, 'demo-nav')).toBeHidden();
  await expect(page.locator('[data-testid="demo-route"][aria-checked="true"]')).toHaveCount(0);
  await expect(tid(page, 'demo-nav-puck')).toHaveCount(0);
  const log = await qa(page);
  expect(log.wakeRelease.length).toBeGreaterThanOrEqual(1);
});

test('AC23: a map drag stops following and shows «Байршил руу буцах» ≤ 300 ms, the replay continues, 0 non-tile requests; recenter resumes ≤ 1 s and hides the button; 15 s without a gesture also resumes', async ({ page }) => {
  const reqs = netLog(page);
  await openDemo(page, { voices: ['mn-MN'] });
  await selectRoute(page, 'R1');
  await startReplay(page);
  await run(page, 20, 1000);
  const from = reqs.length;
  const box = await page.locator('canvas.maplibregl-canvas').boundingBox();
  const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx + 60, cy + 40, { steps: 6 });
  await page.mouse.up();
  await tick(page, 300);
  await expect(tid(page, 'demo-nav-recenter')).toBeVisible();
  await expect(tid(page, 'demo-nav-recenter')).toHaveText(S.mn.recenter);
  const d1 = await dist(page);
  await run(page, 5, 1000);
  expect(await dist(page), 'replay continues').not.toBe(d1);
  await tid(page, 'demo-nav-recenter').click();
  await tick(page, 1000);
  await expect(tid(page, 'demo-nav-recenter')).toBeHidden();
  // second gesture, then 15 s without one
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx - 50, cy - 30, { steps: 6 });
  await page.mouse.up();
  await tick(page, 300);
  await expect(tid(page, 'demo-nav-recenter')).toBeVisible();
  await run(page, 14, 1000);
  await expect(tid(page, 'demo-nav-recenter')).toBeVisible();
  await run(page, 2, 500);
  await expect(tid(page, 'demo-nav-recenter')).toBeHidden();
  expect(nonTile(reqs, from)).toEqual([]);
});

test('AC29: a speech error during the replay switches to the chime for the rest of the replay ≤ 1 s and shows A1 once', async ({ page }) => {
  test.setTimeout(15 * 60_000);
  await openDemo(page, { voices: ['mn-MN'], speechErrorAt: 2 });
  await hideCanvas(page);
  await selectRoute(page, 'R1');
  await startReplay(page);
  await run(page, 175, 5000);
  await run(page, 4, 500); // speak call 2 («1 километрт …» at ~176 s) fails
  const log = await qa(page);
  const failedAt = log.speak.filter((s) => s.text)[1].rel;
  const chimeAfter = log.chimesRel.find((t) => t >= failedAt);
  expect(chimeAfter, 'chime replaces the failed prompt').toBeDefined();
  expect(chimeAfter - failedAt).toBeLessThanOrEqual(1000);
  await expect(page.locator('[data-testid="demo-nav-messages"] [data-kind="voice-unavailable"]')).toBeVisible();
  await expect(page.locator('[data-kind="voice-unavailable"]')).toContainText(S.mn.a1);
  await run(page, 80, 5000);
  const end = await qa(page);
  const later = end.speak.filter((s) => s.text && s.rel > failedAt + 100);
  expect(later, 'no speech after the error').toEqual([]);
  const chimes = end.chimesRel.filter((t) => t >= failedAt);
  expect(chimes.length).toBe(golden('G1', 'mn').filter((g) => g.t >= 176).length);
});

test('AC30: voice button «Дууг хаах» → «Дууг нээх» stops the current utterance ≤ 1 s, then 0 utterances and 0 chimes; choice stored in localStorage navmn.voiceMuted and survives a reload; banners unaffected', async ({ page }) => {
  await openDemo(page, { voices: ['mn-MN'] });
  await selectRoute(page, 'R1');
  await startReplay(page);
  await tick(page, 300);
  await expect(tid(page, 'demo-nav-voice')).toHaveAttribute('aria-label', S.mn.mute);
  const c0 = (await qa(page)).cancel;
  await tid(page, 'demo-nav-voice').click();
  await tick(page, 1000);
  await expect(tid(page, 'demo-nav-voice')).toHaveAttribute('aria-label', S.mn.unmute);
  expect((await qa(page)).cancel, 'current utterance stopped').toBeGreaterThan(c0);
  expect(await page.evaluate(() => localStorage.getItem('navmn.voiceMuted'))).toBe('1');
  const n = (await qa(page)).speak.length;
  await run(page, 60, 2000);
  const banner = await tid(page, 'demo-nav-text').textContent();
  expect(banner).toBeTruthy();
  const log = await qa(page);
  expect(log.speak.length - n).toBe(0);
  expect(log.chimes.length).toBe(0);
  // reload keeps the choice
  await page.reload();
  for (let i = 0; i < 60 && !(await tid(page, 'demo-picker').isVisible().catch(() => false)); i++) { await tick(page, 200); await page.waitForTimeout(50); }
  await selectRoute(page, 'R1');
  await startReplay(page);
  await tick(page, 2000);
  await expect(tid(page, 'demo-nav-voice')).toHaveAttribute('aria-label', S.mn.unmute);
  const log2 = await qa(page);
  expect(log2.speak.filter((s) => s.text).length, 'muted: no depart prompt').toBe(0);
  expect(log2.chimes.length).toBe(0);
});

test('AC31: switching to English mid-replay switches labels, banner and progress ≤ 1 s, stops the current utterance, next prompt in English with the English voice, 0 repeats, 0 requests', async ({ page }) => {
  test.setTimeout(15 * 60_000);
  const reqs = netLog(page);
  await openDemo(page, { voices: ['mn-MN', 'en-US'] });
  await hideCanvas(page);
  await selectRoute(page, 'R1');
  await startReplay(page);
  await run(page, 177, 5000);
  await run(page, 1, 500); // «1 километрт зүүн тийш эргэнэ үү» playing
  const from = reqs.length;
  const c0 = (await qa(page)).cancel;
  await tid(page, 'language-toggle').click();
  await tick(page, 1000);
  expect((await qa(page)).cancel).toBeGreaterThan(c0);
  await expect(tid(page, 'demo-nav-text')).toHaveText('Turn left');
  await expect(tid(page, 'demo-badge')).toHaveText(S.en.mode);
  await expect(tid(page, 'demo-nav-eta')).toContainText(S.en.eta);
  await expect(tid(page, 'demo-nav-end')).toHaveAttribute('aria-label', S.en.end);
  await run(page, 135, 5000);
  const said = spoken(await qa(page));
  const after = said.filter((s) => s.t > 179);
  const enRest = golden('G1', 'en').filter((g) => g.t > 179);
  expect(after.map((s) => s.text)).toEqual(enRest.map((g) => g.text));
  for (const s of after) expect(s.voice).toBe('en-US');
  const texts = said.map((s) => s.text);
  expect(new Set(texts).size, '0 repeated prompts').toBe(texts.length);
  expect(nonTile(reqs, from)).toEqual([]);
});

test('AC31/AC29: switching to Mongolian mid-replay on a phone without a Mongolian voice shows A1 (not shown before in this replay) and chimes from then on', async ({ page }) => {
  await openDemo(page, { voices: ['en-US'], lang: 'en' });
  await selectRoute(page, 'R1');
  await startReplay(page);
  await tick(page, 3000);
  expect(spoken(await qa(page))[0]?.text).toBe('Head south');
  await expect(page.locator('[data-kind="voice-unavailable"]')).toBeHidden();
  await tid(page, 'language-toggle').click();
  await tick(page, 1000);
  await expect(page.locator('[data-kind="voice-unavailable"]')).toBeVisible();
  await expect(page.locator('[data-kind="voice-unavailable"]')).toContainText(S.mn.a1);
  await tick(page, 9000);
  await expect(page.locator('[data-kind="voice-unavailable"]')).toBeHidden();
});

test('AC29 (en): English UI without an English voice → chime, and no A1 notice', async ({ page }) => {
  await openDemo(page, { voices: [], lang: 'en' });
  await selectRoute(page, 'R1');
  await startReplay(page);
  await tick(page, 3000);
  const log = await qa(page);
  expect(log.chimesRel.length).toBe(1);
  expect(log.speak.filter((s) => s.text)).toEqual([]);
  await expect(page.locator('[data-kind="voice-unavailable"]')).toBeHidden();
});

test('AC35: «Дуусгах» stops the replay ≤ 1 s: utterance stopped, route and puck removed, picker shown with no entry selected, wake lock released', async ({ page }) => {
  await openDemo(page, { voices: ['mn-MN'] });
  await selectRoute(page, 'R2');
  await startReplay(page);
  await tick(page, 1500); // depart prompt playing
  const c0 = (await qa(page)).cancel;
  await tid(page, 'demo-nav-end').click();
  await tick(page, 1000);
  await expect(tid(page, 'demo-picker')).toBeVisible();
  await expect(tid(page, 'demo-nav')).toBeHidden();
  await expect(page.locator('[data-testid="demo-route"][aria-checked="true"]')).toHaveCount(0);
  await expect(tid(page, 'demo-nav-puck')).toHaveCount(0);
  await expect(tid(page, 'demo-destination-pin')).toHaveCount(0);
  await expect(tid(page, 'demo-start')).toHaveAttribute('aria-disabled', 'true');
  const log = await qa(page);
  expect(log.cancel).toBeGreaterThan(c0);
  expect(log.wakeRelease.length).toBe(1);
  expect(log.wakeRelease[0] - log.lastClick.t).toBeLessThanOrEqual(1000);
  const n = log.speak.length;
  await tick(page, 30_000);
  expect((await qa(page)).speak.length, 'nothing after «Дуусгах»').toBe(n);
});

for (const mode of ['reject', 'missing']) {
  test(`AC39: wake lock ${mode === 'reject' ? 'request fails' : 'API missing'} → the replay works without it and no message is shown`, async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await openDemo(page, { voices: ['mn-MN'], wakeLock: mode });
    await selectRoute(page, 'R1');
    await startReplay(page);
    await run(page, 10, 1000);
    const d1 = await dist(page);
    await run(page, 10, 1000);
    expect(await dist(page)).not.toBe(d1);
    await expect(tid(page, 'demo-nav-messages')).toBeHidden();
    await expect(tid(page, 'status-banner')).toBeHidden();
    expect(errors).toEqual([]);
  });
}

test('AC40: network lost mid-replay → replay, banner, prompts and progress continue; «Интернэт холболт алга» appears in the message card without covering banner or attribution; gone when back online', async ({ page, context }) => {
  await openDemo(page, { voices: ['mn-MN'] });
  await selectRoute(page, 'R1');
  await startReplay(page);
  await run(page, 20, 1000);
  await context.setOffline(true);
  await run(page, 3, 500);
  const off = page.locator('[data-testid="demo-nav-messages"] [data-kind="offline"]');
  await expect(off).toBeVisible();
  await expect(off).toContainText(S.mn.offline);
  const r = await page.evaluate(() => { const b = (s) => { const e = document.querySelector(s); const x = e.getBoundingClientRect(); return [x.left, x.top, x.right, x.bottom]; }; return { m: b('[data-testid=demo-nav-messages]'), banner: b('[data-testid=demo-nav-banner]'), attr: b('[data-testid=attribution]'), prog: b('[data-testid=demo-nav-progress]') }; });
  expect(intersects(r.m, r.banner)).toBe(false);
  expect(intersects(r.m, r.attr)).toBe(false);
  expect(intersects(r.m, r.prog)).toBe(false);
  const d1 = await dist(page);
  const p1 = await tid(page, 'demo-nav-remaining').textContent();
  await run(page, 15, 1000);
  expect(await dist(page)).not.toBe(d1);
  expect(await tid(page, 'demo-nav-remaining').textContent()).not.toBe(p1);
  await context.setOffline(false);
  await run(page, 3, 500);
  await expect(off).toBeHidden();
});

test('AC41: theme switch and rotation mid-replay keep route, puck, banner and progress (new colours / layout ≤ 1 s), 0 repeated prompts, 0 non-tile requests', async ({ page }) => {
  const reqs = netLog(page);
  await openDemo(page, { voices: ['mn-MN'] });
  await selectRoute(page, 'R1');
  await startReplay(page);
  await run(page, 10, 1000);
  const from = reqs.length;
  const bg0 = await tid(page, 'demo-nav-progress').evaluate((e) => getComputedStyle(e).backgroundColor);
  await tid(page, 'theme-toggle').click();
  await tick(page, 1000);
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'night');
  expect(await tid(page, 'demo-nav-progress').evaluate((e) => getComputedStyle(e).backgroundColor)).not.toBe(bg0);
  for (const id of ['demo-nav-banner', 'demo-nav-progress', 'demo-nav-puck', 'demo-badge']) await expect(tid(page, id)).toBeVisible();
  await page.setViewportSize({ width: 844, height: 390 });
  await tick(page, 1000);
  for (const id of ['demo-nav-banner', 'demo-nav-progress', 'demo-nav-puck', 'demo-nav-end', 'demo-nav-voice']) await expect(tid(page, id)).toBeVisible();
  const lay = await page.evaluate(() => { const b = document.querySelector('[data-testid=demo-nav-banner]').getBoundingClientRect(); const p = document.querySelector('[data-testid=demo-nav-progress]').getBoundingClientRect(); return { bannerRight: b.right, progLeft: p.left, w: innerWidth }; });
  expect(lay.bannerRight, 'landscape: banner in the left column').toBeLessThan(lay.w * 0.7);
  await run(page, 10, 1000);
  await page.setViewportSize({ width: 390, height: 844 });
  await run(page, 5, 1000);
  const said = spoken(await qa(page)).map((s) => s.text);
  expect(new Set(said).size).toBe(said.length);
  expect(nonTile(reqs, from)).toEqual([]);
});

test('AC29: tapping the A1 notice dismisses it ≤ 1 s and it does not return in this replay; «Дуусгах» stays reachable while it shows', async ({ page }) => {
  await openDemo(page, { voices: ['en-US'] });
  await selectRoute(page, 'R1');
  await startReplay(page);
  await tick(page, 1500);
  const a1 = page.locator('[data-testid="demo-nav-messages"] [data-kind="voice-unavailable"]');
  await expect(a1).toBeVisible();
  const reach = await page.evaluate(() => { const e = document.querySelector('[data-testid=demo-nav-end]'); const r = e.getBoundingClientRect(); const h = document.elementFromPoint((r.left + r.right) / 2, (r.top + r.bottom) / 2); return e === h || e.contains(h); });
  expect(reach, '«Дуусгах» reachable while A1 shows').toBe(true);
  await a1.getByRole('button').click();
  await tick(page, 1000);
  await expect(a1).toBeHidden();
  await run(page, 30, 1000);
  await expect(a1).toBeHidden();
});

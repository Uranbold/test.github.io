// NAV-003 H. Keyboard and screen reader (AC 40–43; AC 41 amended 2026-09-30 by PO approval F5, D33: Tab exception for state rows
// with «Дахин оролдох»).
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { CORS, FX, REF, REVERSE_GLOB, SEARCH_GLOB, T, camera, card, fc, feature, haversine, jumpTo, mock, openApp, options, rightClick, tid, typeQuery, view, waitCameraStill, waitSettled } from './helpers.mjs';

const FIVE = ['F5', 'F9', 'F6', 'F7', 'F8'].map((id) => FX[id].feature);
const seriousOrCritical = (r) => r.violations.filter((v) => ['serious', 'critical'].includes(v.impact)).flatMap((v) => v.nodes.map((n) => `${v.id} (${v.impact}): ${n.target.join(' ')} ${n.failureSummary?.split('\n')[1] ?? ''}`));

test('AC40: combobox pattern attributes; axe 0 serious/critical with list, card and each state message in both themes and languages', async ({ browser }) => {
  test.setTimeout(240_000);
  const problems = [];
  for (const theme of ['day', 'night']) {
    for (const lang of ['mn', 'en']) {
      const ctx = await browser.newContext({ viewport: { width: 1366, height: 768 } });
      const page = await ctx.newPage();
      let mode = 'ok';
      await mock(page, SEARCH_GLOB, () => {
        if (mode === 'ok') return { body: fc(FIVE) };
        if (mode === 'empty') return { body: fc([]) };
        if (mode === '503') return { status: 503, headers: CORS, body: '{}' };
        if (mode === '400') return { status: 400, headers: CORS, body: '{}' };
        if (mode === '429') return { status: 429, headers: { ...CORS, 'Access-Control-Expose-Headers': 'Retry-After', 'Retry-After': '30' }, body: '{}' };
        if (mode === 'slow') return { delay: 4000, body: fc(FIVE) };
      });
      await mock(page, REVERSE_GLOB, () => ({ status: 503, headers: CORS, body: '{}' }));
      await openApp(page, { theme, lang });
      const tag = `${theme}/${lang}`;
      const run = async (what) => {
        const r = await new AxeBuilder({ page }).analyze();
        for (const p of seriousOrCritical(r)) problems.push(`${tag} ${what}: ${p}`);
      };
      const input = tid(page, 'search-input');
      await expect(input).toHaveAttribute('role', 'combobox');
      await expect(input).toHaveAttribute('aria-controls', 'search-results');
      await expect(input).toHaveAttribute('aria-expanded', 'false');
      await typeQuery(page, 'Олон газар');
      await waitSettled(page, 'Олон газар');
      await expect(input).toHaveAttribute('aria-expanded', 'true');
      await expect(tid(page, 'search-results')).toHaveAttribute('role', 'listbox');
      await expect(tid(page, 'search-results')).toHaveAccessibleName(T[lang].results);
      await page.keyboard.press('ArrowDown');
      await expect(input).toHaveAttribute('aria-activedescendant', 'search-option-0');
      await expect(page.locator('#search-option-0')).toHaveAttribute('aria-selected', 'true');
      await run('list open');
      await page.keyboard.press('Enter');
      await expect(tid(page, 'place-card')).toBeVisible();
      await run('place card');
      await page.keyboard.press('Escape');
      await jumpTo(page, REF.P1, 15);
      await rightClick(page, 900, 400);
      await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'unavailable', { timeout: 3000 });
      await run('coordinate card (unavailable)');
      await tid(page, 'place-card-close').click();
      for (const m of ['empty', '503', '400', 'slow', '429']) {
        mode = m;
        await typeQuery(page, `Тест ${m}`);
        if (m === 'slow') await expect(tid(page, 'search-state')).toHaveAttribute('data-state', 'loading', { timeout: 2000 });
        else await waitSettled(page, `Тест ${m}`);
        await run(`state ${m}`);
        if (m !== '429') await page.keyboard.press('Escape');
      }
      await ctx.setOffline(true);
      await typeQuery(page, 'Тест offline');
      await expect(tid(page, 'search-popup')).toHaveAttribute('data-state', 'offline', { timeout: 2000 });
      await run('state offline');
      await ctx.close();
    }
  }
  expect(problems).toEqual([]);
});

test('AC41: ArrowDown/Up move the highlight with wrapping, focus stays in the input; Enter selects the highlight; Escape closes, second Escape clears; Tab closes without selecting', async ({ page }) => {
  await mock(page, SEARCH_GLOB, () => ({ body: fc(FIVE) }));
  await openApp(page);
  await typeQuery(page, 'Олон');
  await waitSettled(page, 'Олон');
  const input = tid(page, 'search-input');
  const active = () => input.getAttribute('aria-activedescendant');
  const focused = () => page.evaluate(() => document.activeElement?.dataset.testid);
  await page.keyboard.press('ArrowDown');
  expect(await active()).toBe('search-option-0');
  for (let i = 1; i < 5; i++) await page.keyboard.press('ArrowDown');
  expect(await active()).toBe('search-option-4');
  await page.keyboard.press('ArrowDown');
  expect(await active(), 'wrap last -> first').toBe('search-option-0');
  await page.keyboard.press('ArrowUp');
  expect(await active(), 'wrap first -> last').toBe('search-option-4');
  await page.keyboard.press('ArrowUp');
  expect(await active()).toBe('search-option-3');
  expect(await focused()).toBe('search-input');
  expect((await options(page)).filter((o) => o.selected === 'true').map((o) => o.id)).toEqual(['search-option-3']);
  await page.keyboard.press('Enter');
  expect((await card(page)).title).toBe(FX.F7.expect.name);
  await page.keyboard.press('Escape'); // closes the card, focus back to input
  // Escape closes, second Escape clears
  await typeQuery(page, 'Олон');
  await waitSettled(page, 'Олон');
  await page.keyboard.press('Escape');
  await expect(tid(page, 'search-popup')).toBeHidden();
  await expect(input).toHaveValue('Олон');
  await page.keyboard.press('Escape');
  await expect(input).toHaveValue('');
  // Tab closes without selecting
  await typeQuery(page, 'Олон');
  await waitSettled(page, 'Олон');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Tab');
  await expect(tid(page, 'search-popup')).toBeHidden();
  expect((await card(page)).open).toBe(false);
  expect(await focused()).not.toBe('search-input');
  await expect(input).toHaveValue('Олон');
});

/**
 * AC 41 exception (story amended 2026-09-30, PO approval F5, D33) and screen spec › Interactions › Tab / Accessibility ›
 * Tab order: a state row that offers «Дахин оролдох» (unavailable AC 33, rate-limited AC 35) stays open when focus leaves
 * the input with Tab, and the retry button is reached with Tab after the top-bar cluster
 * (input → clear → language → theme → compass → retry); in the rate-limited state it is focusable while
 * aria-disabled="true". It closes with Escape, a new settled query, or clearing the input.
 */
async function f5Setup(page) {
  const ctl = { mode: '503' };
  await mock(page, SEARCH_GLOB, () => {
    if (ctl.mode === '503') return { status: 503, headers: CORS, body: '{}' };
    if (ctl.mode === '429') return { status: 429, headers: { ...CORS, 'Access-Control-Expose-Headers': 'Retry-After', 'Retry-After': '30' }, body: '{}' };
    if (ctl.mode === 'empty') return { body: fc([]) };
    return { body: fc(FIVE) };
  });
  await openApp(page);
  return ctl;
}
const F5_ORDER = ['search-clear', 'language-toggle', 'theme-toggle', 'compass', 'search-retry'];
const focusedId = (page) => page.evaluate(() => document.activeElement?.dataset.testid ?? document.activeElement?.tagName);
async function tabWalk(page, n = F5_ORDER.length) {
  const seq = [];
  for (let i = 0; i < n; i++) {
    await page.keyboard.press('Tab');
    seq.push({ id: await focusedId(page), open: await tid(page, 'search-popup').isVisible() });
  }
  return seq;
}

test('AC41 (F5): Tab keeps an unavailable / rate-limited state row open and reaches «Дахин оролдох» after compass (aria-disabled while rate-limited); a new settled query and clearing the input close it', async ({ page }) => {
  const ctl = await f5Setup(page);
  const input = tid(page, 'search-input');
  const popup = tid(page, 'search-popup');
  // Unavailable (AC 33)
  await typeQuery(page, 'Тест алдаа');
  await waitSettled(page, 'Тест алдаа');
  await expect(tid(page, 'search-state')).toHaveAttribute('data-state', 'unavailable');
  let seq = await tabWalk(page);
  test.info().annotations.push({ type: 'AC41 F5 unavailable Tab sequence', description: JSON.stringify(seq) });
  expect(seq.map((x) => x.id), 'Tab order input → clear → language → theme → compass → retry').toEqual(F5_ORDER);
  expect(seq.every((x) => x.open), 'the unavailable state row stays open while tabbing').toBe(true);
  await expect(tid(page, 'search-retry')).toHaveAccessibleName(new RegExp(T.mn.retry));
  // Screen spec › State row: the retry reached with Tab reads its message too («Дахин оролдох, Хайлт түр ажиллахгүй байна»)
  await expect(tid(page, 'search-retry')).toHaveAccessibleDescription(T.mn.unavailable);
  // A new settled query closes (replaces) it
  ctl.mode = 'ok';
  await typeQuery(page, 'Олон');
  await waitSettled(page, 'Олон');
  await expect(tid(page, 'search-state')).toBeHidden();
  await expect(tid(page, 'search-results')).toBeVisible();
  await page.keyboard.press('Escape');
  // Rate-limited (AC 35): retry reached with Tab while aria-disabled="true"
  ctl.mode = '429';
  await typeQuery(page, 'Тест хязгаар');
  await waitSettled(page, 'Тест хязгаар');
  await expect(tid(page, 'search-state')).toHaveAttribute('data-state', 'rate-limited');
  seq = await tabWalk(page);
  test.info().annotations.push({ type: 'AC41 F5 rate-limited Tab sequence', description: JSON.stringify(seq) });
  expect(seq.map((x) => x.id)).toEqual(F5_ORDER);
  expect(seq.every((x) => x.open), 'the rate-limited state row stays open while tabbing').toBe(true);
  await expect(tid(page, 'search-retry')).toHaveAttribute('aria-disabled', 'true');
  // Clearing the input closes it
  await input.focus();
  await page.keyboard.press('ControlOrMeta+A');
  await page.keyboard.press('Backspace');
  await expect(popup, 'clearing the input closes the rate-limited row').toBeHidden({ timeout: 2000 });
});

test('AC41 (F5): Escape closes a state row with «Дахин оролдох», from the input and from the focused retry button (focus → input, text kept; screen spec › Interactions › Tab)', async ({ page }) => {
  await f5Setup(page);
  const input = tid(page, 'search-input');
  const popup = tid(page, 'search-popup');
  // From the input
  await typeQuery(page, 'Тест алдаа');
  await waitSettled(page, 'Тест алдаа');
  await page.keyboard.press('Escape');
  await expect(popup, 'Escape in the input closes the unavailable row').toBeHidden();
  await expect(input).toHaveValue('Тест алдаа');
  // From the retry button reached with Tab
  await typeQuery(page, 'Тест алдаа 2');
  await waitSettled(page, 'Тест алдаа 2');
  await tabWalk(page);
  expect(await focusedId(page)).toBe('search-retry');
  await page.keyboard.press('Escape');
  await expect(popup, 'Escape on the retry button closes the unavailable row').toBeHidden({ timeout: 1000 });
  expect(await focusedId(page), 'focus returns to the input (never <body>)').toBe('search-input');
  await expect(input).toHaveValue('Тест алдаа 2');
});

// AC 41 as amended in the working-tree story (2026-09-30, BA, from the UX design addition): a state row with «Дахин оролдох»
// also closes on a click on the map and when a coordinate card opens (right-click, AC 25), so the new card is never hidden.
// The drag case (PO decision D43) is the separate test below.
test('AC41 (amended): a state row with «Дахин оролдох» closes on a click on the map and when a coordinate card opens (right-click); the card is visible', async ({ page }) => {
  await mock(page, REVERSE_GLOB, () => ({ body: fc([feature('Сүхбаатарын талбай', REF.P1.lng, REF.P1.lat, { osm_key: 'place', osm_value: 'square' })]) }));
  await f5Setup(page);
  const popup = tid(page, 'search-popup');
  // Click on the map (unavailable row)
  await typeQuery(page, 'Тест алдаа');
  await waitSettled(page, 'Тест алдаа');
  await expect(tid(page, 'search-state')).toHaveAttribute('data-state', 'unavailable');
  await page.mouse.click(900, 400);
  await expect(popup, 'a click on the map closes the unavailable row').toBeHidden({ timeout: 1000 });
  expect((await card(page)).open, 'a plain click opens no card').toBe(false);
  // Opening a coordinate card (right-click) closes it and the card is not hidden under the row
  await typeQuery(page, 'Тест алдаа 2');
  await waitSettled(page, 'Тест алдаа 2');
  await expect(tid(page, 'search-state')).toHaveAttribute('data-state', 'unavailable');
  await rightClick(page, 900, 400);
  await expect(popup, 'opening a coordinate card closes the unavailable row').toBeHidden({ timeout: 1000 });
  await expect.poll(async () => (await card(page)).open, { timeout: 1500 }).toBe(true);
  const c = await card(page);
  expect(c.kind).toBe('point');
  expect(c.title).toBe(T.mn.selectedPoint);
  await expect(tid(page, 'place-card')).toBeVisible();
});

// AC 41 as changed 2026-09-30 by PO decision D43: a state row with «Дахин оролдох» stays open while the map is dragged
// (panned), and closes on a map click (MapLibre `click`, which does not fire after a drag). Screen spec › Interactions ›
// Tab (D43 table). The drag is a real mouse drag on the canvas (down, 10 moves, up); the camera must have moved, so the
// test cannot pass on a drag that never happened. Checked for the unavailable row (AC 33) with focus in the input and with
// focus on «Дахин оролдох» (reached with Tab), and for the rate-limited row (AC 35).
async function dragMap(page, dx = 180, dy = 90) {
  const before = await camera(page);
  await page.mouse.move(700, 450);
  await page.mouse.down();
  for (let i = 1; i <= 10; i++) await page.mouse.move(700 + (dx * i) / 10, 450 + (dy * i) / 10);
  await page.mouse.up();
  await waitCameraStill(page).catch(() => {});
  const after = await camera(page);
  return haversine({ lat: before.lat, lng: before.lng }, { lat: after.lat, lng: after.lng });
}
test('AC41 (D43): dragging the map keeps a state row with «Дахин оролдох» open (unavailable and rate-limited, focus in the input or on the retry); a map click afterwards closes it', async ({ page }) => {
  const ctl = await f5Setup(page);
  const popup = tid(page, 'search-popup');
  const retry = tid(page, 'search-retry');
  // 1. Unavailable row, focus in the input
  await typeQuery(page, 'Тест алдаа');
  await waitSettled(page, 'Тест алдаа');
  await expect(tid(page, 'search-state')).toHaveAttribute('data-state', 'unavailable');
  let moved = await dragMap(page);
  test.info().annotations.push({ type: 'AC41 D43 drag 1 camera moved (m)', description: moved.toFixed(0) });
  expect(moved, 'the drag really panned the map').toBeGreaterThan(50);
  await page.waitForTimeout(300);
  await expect(popup, 'D43: the unavailable row stays open after a map drag').toBeVisible();
  await expect(tid(page, 'search-state')).toHaveAttribute('data-state', 'unavailable');
  await expect(retry, 'D43: «Дахин оролдох» is still shown after the drag').toBeVisible();
  await expect(retry).toHaveAccessibleName(new RegExp(T.mn.retry));
  expect((await card(page)).open, 'a drag opens no card').toBe(false);
  // 2. Same row, focus on «Дахин оролдох» (reached with Tab from the input), then a drag
  await tid(page, 'search-input').focus();
  await expect(popup).toBeVisible();
  await tabWalk(page);
  expect(await focusedId(page)).toBe('search-retry');
  moved = await dragMap(page, -160, -60);
  expect(moved).toBeGreaterThan(50);
  await page.waitForTimeout(300);
  await expect(popup, 'D43: the row stays open after a drag that started with focus on the retry button').toBeVisible();
  await expect(retry).toBeVisible();
  // Screen spec D43 table: "keeps focus if it had it" (design detail beyond the AC wording; recorded, not asserted)
  test.info().annotations.push({ type: 'AC41 D43 focus after a drag that started with focus on the retry', description: String(await focusedId(page)) });
  // 3. A plain map click (no movement) closes it
  await page.mouse.click(900, 400);
  await expect(popup, 'D43: a map click closes the unavailable row').toBeHidden({ timeout: 1000 });
  expect((await card(page)).open, 'a plain click opens no card').toBe(false);
  // 4. Rate-limited row (AC 35): drag keeps it, click closes it
  ctl.mode = '429';
  await typeQuery(page, 'Тест хязгаар');
  await waitSettled(page, 'Тест хязгаар');
  await expect(tid(page, 'search-state')).toHaveAttribute('data-state', 'rate-limited');
  moved = await dragMap(page, 120, -120);
  expect(moved).toBeGreaterThan(50);
  await page.waitForTimeout(300);
  await expect(popup, 'D43: the rate-limited row stays open after a map drag').toBeVisible();
  await expect(retry).toBeVisible();
  await page.mouse.click(900, 400);
  await expect(popup, 'D43: a map click closes the rate-limited row').toBeHidden({ timeout: 1000 });
});

// Screen spec › Interactions › "Click on the map" (revision 2026-09-30, D43): dragging the map never closes the popup, also
// when it shows options; a map click closes it without selecting. Design conformance beyond the AC 41 wording (which
// covers state rows); traced to AC 41 / D43 and the screen spec.
test('AC41 (D43, screen spec): dragging the map keeps a list of results open; a map click closes it without selecting', async ({ page }) => {
  await f5Setup(page).then((ctl) => (ctl.mode = 'ok'));
  const popup = tid(page, 'search-popup');
  await typeQuery(page, 'Олон');
  await waitSettled(page, 'Олон');
  await expect(tid(page, 'search-results')).toBeVisible();
  const n = (await options(page)).length;
  expect(n).toBeGreaterThan(0);
  const moved = await dragMap(page);
  expect(moved, 'the drag really panned the map').toBeGreaterThan(50);
  await page.waitForTimeout(300);
  await expect(popup, 'screen spec (D43): a map drag does not close a list of results').toBeVisible();
  expect((await options(page)).length).toBe(n);
  await page.mouse.click(900, 400);
  await expect(popup, 'a map click closes the list').toBeHidden({ timeout: 1000 });
  expect((await card(page)).open, 'no selection, no card').toBe(false);
  await expect(tid(page, 'search-input')).toHaveValue('Олон');
});

test('AC41 screen spec › Interactions › Tab: a state row without a button (no results) closes on Tab, like a list of results', async ({ page }) => {
  const ctl = await f5Setup(page);
  ctl.mode = 'empty';
  await typeQuery(page, 'Хийх');
  await waitSettled(page, 'Хийх');
  await expect(tid(page, 'search-state')).toHaveAttribute('data-state', 'no-results');
  await page.keyboard.press('Tab');
  await expect(tid(page, 'search-popup'), 'no-results row (no button) closes on Tab').toBeHidden();
});

test('AC41: Enter without highlight selects the first option; before the list renders it searches at once (no debounce) and selects the first option on arrival; not for empty or state results', async ({ page }) => {
  await page.addInitScript(() => {
    window.__enterKeys = [];
    document.addEventListener('keydown', (e) => e.key === 'Enter' && window.__enterKeys.push({ t: performance.now(), v: e.target.value }), true);
  });
  const log = await mock(page, SEARCH_GLOB, (p) => (p.q === 'Хийх' ? { body: fc([]) } : p.q === 'Алдаа' ? { status: 503, headers: CORS, body: '{}' } : { delay: 400, body: fc(FIVE) }));
  await openApp(page);
  // list rendered, no highlight
  await typeQuery(page, 'Олон');
  await waitSettled(page, 'Олон');
  await page.keyboard.press('Enter');
  expect((await card(page)).title).toBe(FX.F5.expect.name);
  await page.keyboard.press('Escape');
  // Enter immediately after typing: no debounce
  await typeQuery(page, 'Шууд');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(800);
  // Measured from the Enter keydown as the page receives it: under SwiftShader the key event can reach the page up to
  // ~200 ms after the last input event while the previous fly-to animation busies the main thread (seen 2026-09-30).
  const rq = await page.evaluate(() => {
    const k = window.__enterKeys.at(-1);
    return { k: k.t, value: k.v, req: window.__req.filter((x) => x.op === 'search' && x.t >= window.__inputs.at(-1).t).map((x) => ({ dt: x.t - k.t, q: new URL(x.url).searchParams.get('q') })) };
  });
  test.info().annotations.push({ type: 'AC41 Enter keydown -> requests', description: JSON.stringify(rq) });
  expect(rq.value).toBe('Шууд');
  expect(rq.req.length).toBe(1);
  expect(rq.req[0].dt, 'request starts at once on Enter (no 250 ms debounce)').toBeLessThan(50);
  await expect(tid(page, 'place-card')).toBeVisible({ timeout: 3000 });
  expect((await card(page)).title).toBe(FX.F5.expect.name);
  expect(log.filter((l) => l.p.q === 'Шууд').length).toBe(1);
  await page.keyboard.press('Escape');
  // empty result or state message: nothing selected
  for (const q of ['Хийх', 'Алдаа']) {
    await typeQuery(page, q);
    await page.keyboard.press('Enter');
    await waitSettled(page, q);
    await page.waitForTimeout(300);
    expect((await card(page)).open, q).toBe(false);
  }
});

test('AC42: the polite live region announces «N илэрц олдлоо» / "N results" / "1 result" / «Илэрц олдсонгүй» / state message within 1 s, once per settled query', async ({ page }) => {
  await mock(page, SEARCH_GLOB, (p) => (p.q.startsWith('Нэг') ? { body: fc([FX.F9.feature]) } : p.q.startsWith('Хийх') ? { body: fc([]) } : p.q.startsWith('Алдаа') ? { status: 503, headers: CORS, body: '{}' } : { body: fc(FIVE) }));
  await openApp(page);
  const live = tid(page, 'search-live');
  await expect(live).toHaveAttribute('aria-live', 'polite');
  const check = async (q, text) => {
    const t0 = await page.evaluate(() => performance.now());
    await typeQuery(page, q, { delay: 60 });
    await waitSettled(page, q);
    await expect(live).toHaveText(text);
    const r = await page.evaluate((t0) => {
      const k = window.__inputs.at(-1).t;
      const ch = [];
      let prev = null;
      for (const s of window.__st.filter((s) => s.t >= t0)) {
        if (s.live !== prev) ch.push({ t: s.t, live: s.live });
        prev = s.live;
      }
      const non = ch.filter((c) => c.live !== '' && c.t >= t0);
      return { first: non.find((c) => c.t >= k), count: non.filter((c) => c.t >= k - 5000).length, k };
    }, t0);
    expect(r.first.t - r.k, `${q} announced within 1 s of the last keystroke`).toBeLessThanOrEqual(1000);
    return r;
  };
  let r = await check('Олон газар', T.mn.count(5));
  expect(r.count, 'announced once per settled query, not per keystroke').toBe(1);
  await check('Хийх', T.mn.noResults); // no у/о: a single request (rule C would add a ү/ө variant)
  await check('Алдаа', T.mn.unavailable);
  await tid(page, 'language-toggle').click();
  await check('Нэг', T.en.count(1));
  await check('Олон 2', T.en.count(5));
});

test('AC43: focus moves to the card heading (result and coordinate card); «Хаах» and «Дахин оролдох» reachable with Tab and have a visible focus ring', async ({ page }) => {
  await mock(page, SEARCH_GLOB, () => ({ body: fc(FIVE) }));
  await mock(page, REVERSE_GLOB, () => ({ status: 503, headers: CORS, body: '{}' }));
  await openApp(page);
  await typeQuery(page, 'Олон');
  await waitSettled(page, 'Олон');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  expect(await page.evaluate(() => document.activeElement?.dataset.testid)).toBe('place-card-title');
  const ring = () =>
    page.evaluate(() => {
      const e = document.activeElement;
      const c = getComputedStyle(e);
      return { id: e.dataset.testid, ring: (c.outlineStyle !== 'none' && parseFloat(c.outlineWidth) >= 2) || (c.boxShadow && c.boxShadow !== 'none'), fv: e.matches(':focus-visible') };
    });
  await page.keyboard.press('Tab');
  let f = await ring();
  expect(f.id).toBe('place-card-close');
  expect(f.ring && f.fv, JSON.stringify(f)).toBe(true);
  await page.keyboard.press('Enter');
  expect(await page.evaluate(() => document.activeElement?.dataset.testid)).toBe('search-input');
  // coordinate card with the retry button
  await jumpTo(page, REF.P1, 15);
  await rightClick(page, 900, 400);
  await expect(tid(page, 'place-nearest')).toHaveAttribute('data-state', 'unavailable', { timeout: 3000 });
  expect(await page.evaluate(() => document.activeElement?.dataset.testid)).toBe('place-card-title');
  expect((await card(page)).title).toBe(T.mn.selectedPoint);
  const seen = [];
  for (let i = 0; i < 3; i++) {
    await page.keyboard.press('Tab');
    f = await ring();
    seen.push(f);
  }
  const ids = seen.map((s) => s.id);
  expect(ids).toContain('place-card-close');
  expect(ids).toContain('place-retry');
  for (const s of seen.filter((s) => ['place-card-close', 'place-retry'].includes(s.id))) expect(s.ring && s.fv, JSON.stringify(s)).toBe(true);
  await expect(tid(page, 'place-retry')).toHaveAccessibleName(T.mn.retry);
  await expect(tid(page, 'place-card-close')).toHaveAccessibleName(T.mn.close);
});

// AC 45 (row 4 en value «Сум», D34) and screen spec › Result content rules › Language of parts for type labels (revision
// 2026-09-30, WCAG 2.2 SC 3.1.2): in the English UI the Cyrillic type label «Сум» carries lang="mn" in the result option,
// the place card meta line and the coordinate card's nearest-place type. Latin labels carry no lang="mn". Control: in the
// Mongolian UI the same label has no lang override (it is in the page language).
test('AC45 (D34, screen spec language of parts): English UI «Сум» type label has lang="mn" in the option, the place card and the nearest place', async ({ page }) => {
  const soum = FX.F17a.feature; // "Bayan-Undur", boundary/administrative, type=county → row 4 «Сум»
  const school = feature('School 5', REF.P1.lng, REF.P1.lat); // amenity=school → "School" (Latin label)
  await mock(page, SEARCH_GLOB, () => ({ body: fc([soum, school]) }));
  await mock(page, REVERSE_GLOB, () => ({ body: fc([soum]) }));
  await openApp(page, { lang: 'en' });
  await typeQuery(page, 'Bayan');
  await waitSettled(page, 'Bayan');
  const opt = await page.evaluate(() =>
    [...document.querySelectorAll('#search-results [role=option]')].map((li) => {
      const ty = li.querySelector('[data-testid=search-option-type]');
      return { type: ty?.textContent ?? null, lang: ty?.closest('[lang]')?.getAttribute('lang') ?? null, own: ty?.getAttribute('lang') ?? null };
    }),
  );
  test.info().annotations.push({ type: 'AC45 option type labels (en UI)', description: JSON.stringify(opt) });
  expect(opt[0].type).toBe('Сум');
  expect(opt[0].own, 'option type «Сум» has lang="mn" (en UI)').toBe('mn');
  expect(opt[1].type).toBe('School');
  expect(opt[1].lang, 'a Latin type label resolves to the page language en').not.toBe('mn');
  // Place card meta line
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await expect.poll(async () => (await card(page)).open).toBe(true);
  expect((await card(page)).type).toBe('Сум');
  await expect(page.locator('#place-card-type'), 'place card type «Сум» has lang="mn"').toHaveAttribute('lang', 'mn');
  await tid(page, 'place-card-close').click().catch(() => page.keyboard.press('Escape'));
  // Coordinate card, nearest place
  await rightClick(page, 900, 400);
  await expect.poll(async () => (await card(page)).near, { timeout: 3000 }).toBe('place');
  await expect(page.locator('#place-nearest-type')).toHaveText('Сум');
  await expect(page.locator('#place-nearest-type'), 'nearest-place type «Сум» has lang="mn"').toHaveAttribute('lang', 'mn');
});

test('AC45 control: Mongolian UI «Сум» type label has no lang override', async ({ page }) => {
  await mock(page, SEARCH_GLOB, () => ({ body: fc([FX.F17b.feature]) }));
  await openApp(page, { lang: 'mn' });
  await typeQuery(page, 'Баян');
  await waitSettled(page, 'Баян');
  const ty = page.locator('#search-results [role=option] [data-testid=search-option-type]').first();
  await expect(ty).toHaveText('Сум');
  expect(await ty.getAttribute('lang')).toBeNull();
});

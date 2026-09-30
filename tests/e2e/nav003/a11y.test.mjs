// NAV-003 H. Keyboard and screen reader (AC 40–43).
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { CORS, FX, REF, REVERSE_GLOB, SEARCH_GLOB, T, card, fc, feature, jumpTo, mock, openApp, options, rightClick, tid, typeQuery, view, waitSettled } from './helpers.mjs';

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

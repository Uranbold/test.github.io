// NAV-017 G: iPhone layout (AC 37) and accessibility (AC 38), on the AC 37 viewports 375×667, 390×844, 430×932 portrait
// and 844×390 landscape, at 100 % and 200 % text zoom (root font size, as Safari's aA text size scales rem; the UX
// checker uses the same method), in the picker, guidance (with recenter shown), A1 notice and arrival states.
// Safe-area insets cannot be emulated in a headless engine (env(safe-area-inset-*) is 0 here): the CSS is checked
// statically instead, and the real insets stay a real-iPhone check (AC 48).
import { expect } from '@playwright/test';
import { test } from './helpers.mjs';
import AxeBuilder from '@axe-core/playwright';
import { readdirSync, readFileSync } from 'node:fs';
import { S, ROUTES, WEB, openDemo, selectRoute, startReplay, tick, tid, routeBody, readGpx, intersects } from './helpers.mjs';

const VIEWPORTS = [[375, 667], [390, 844], [430, 932], [844, 390]];
// iPhone safe-area insets (CSS px) for those viewports: SE (status bar only), 13, 15 Pro Max, 13 landscape (notch left
// and right, home indicator). Emulated in Chromium with CDP Emulation.setSafeAreaInsetsOverride; WebKit has no
// equivalent, so its runs check the layout with zero insets (real insets: AC 48).
const INSETS = { '375x667': { top: 20, left: 0, bottom: 0, right: 0 }, '390x844': { top: 47, left: 0, bottom: 34, right: 0 }, '430x932': { top: 59, left: 0, bottom: 34, right: 0 }, '844x390': { top: 0, left: 47, bottom: 21, right: 47 } };
async function emulateInsets(page, w, h) {
  if (page.context().browser()?.browserType().name() !== 'chromium') return false;
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Emulation.setSafeAreaInsetsOverride', { insets: INSETS[`${w}x${h}`] });
  return true;
}
const ZOOMS = [100, 200];

/** Short arrival track: the last 40 s of G1 (the arrival state without a 5 min replay). */
let tailBody;
async function arrivalBody() {
  tailBody ??= await routeBody('r1', { track: readGpx(ROUTES.R1.track).slice(-40).map((p, i) => ({ ...p, tMs: i * 1000 })) });
  return tailBody;
}

async function reach(page, state, zoom, w, h) {
  if (w) await emulateInsets(page, w, h);
  // WebKit runs init scripts before <html> exists: a throw here would also skip the init scripts added after it
  // (qaInit), so retry on readystatechange instead of failing.
  if (zoom !== 100) await page.addInitScript((z) => {
    const set = () => { try { document.documentElement.style.fontSize = `${z}%`; return true; } catch { return false; } };
    if (!set()) document.addEventListener('readystatechange', () => set(), { once: true });
  }, zoom);
  if (state === 'arrival') { const body = await arrivalBody(); await page.route('**/demo-routes/r1.json', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })); }
  await openDemo(page, { voices: state === 'a1' ? ['en-US'] : ['mn-MN'] });
  await selectRoute(page, 'R1');
  if (state === 'picker') { await tick(page, 1000); return; }
  await startReplay(page);
  if (state === 'a1') { await tick(page, 2000); return; }
  if (state === 'guidance') {
    await tick(page, 5000);
    const box = await page.locator('canvas.maplibregl-canvas').boundingBox();
    const mapBand = await page.evaluate(() => { const t = document.querySelector('.dn-top').getBoundingClientRect(); const b = document.querySelector('[data-testid=demo-nav-progress]').getBoundingClientRect(); return { top: t.bottom, bottom: b.top, left: innerWidth > innerHeight ? t.right : 0 }; });
    const x = (Math.max(mapBand.left, box.x) + box.x + box.width) / 2, y = (mapBand.top + mapBand.bottom) / 2;
    await page.mouse.move(x, y); await page.mouse.down(); await page.mouse.move(x + 30, y + 20, { steps: 4 }); await page.mouse.up();
    await tick(page, 400);
    return;
  }
  for (let i = 0; i < 60; i++) { await tick(page, 1000); if (await tid(page, 'demo-nav-arrival').isVisible()) break; }
  await tick(page, 1000);
}

/** Layout problems in the current state (AC 37). */
async function layoutProblems(page, state) {
  return page.evaluate(({ state, attrText }) => {
    const out = [];
    const vis = (e) => !!e && e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden' && !e.closest('[hidden]');
    const R = (e) => e.getBoundingClientRect();
    const vw = innerWidth, vh = innerHeight;
    const attr = document.querySelector('[data-testid=attribution]');
    const link = document.querySelector('[data-testid=attribution-osm]');
    if (!vis(attr) || !link.textContent.includes(attrText)) out.push('attribution not visible');
    const ar = R(link);
    if (ar.bottom > vh + 0.5 || ar.top < 0) out.push('attribution outside the viewport');
    // nothing visible intersects the attribution text
    const blocks = ['[data-testid=demo-picker]', '.dn-top', '[data-testid=demo-nav-banner]', '.dn-row', '[data-testid=demo-nav-messages]', '[data-testid=demo-nav-progress]', '[data-testid=demo-nav-arrival]', '[data-testid=demo-nav-recenter]', '[data-testid=zoom-in]', '[data-testid=zoom-out]', '[data-testid=compass]', '[data-testid=scale-bar]', '[data-testid=language-toggle]', '[data-testid=theme-toggle]'];
    for (const s of blocks) {
      const e = document.querySelector(s);
      if (!vis(e)) continue;
      const r = R(e);
      if (r.left < ar.right - 0.5 && ar.left < r.right - 0.5 && r.top < ar.bottom - 0.5 && ar.top < r.bottom - 0.5) out.push(`${s} intersects the attribution`);
    }
    // the attribution link is the topmost element at its centre
    const top = document.elementFromPoint((ar.left + ar.right) / 2, (ar.top + ar.bottom) / 2);
    if (!top || !(link.contains(top) || top.contains(link))) out.push(`attribution covered by ${top?.className || top?.tagName}`);
    // interactive elements inside the safe area (env(safe-area-inset-*) measured with a probe) and reachable
    const probe = document.createElement('div');
    probe.style.cssText = 'position:fixed;visibility:hidden;pointer-events:none;left:env(safe-area-inset-left);top:env(safe-area-inset-top);right:env(safe-area-inset-right);bottom:env(safe-area-inset-bottom)';
    document.body.append(probe);
    const pr = probe.getBoundingClientRect();
    const inset = { l: pr.left, t: pr.top, r: vw - pr.right, b: vh - pr.bottom };
    probe.remove();
    const controls = [...document.querySelectorAll('button, [role=radio], a[href]')].filter(vis).filter((e) => !e.closest('.maplibregl-ctrl-attrib'));
    for (const c of controls) {
      const r = R(c);
      if (r.width === 0 || r.height === 0) continue;
      if (r.left < inset.l - 0.5 || r.right > vw - inset.r + 0.5 || r.top < inset.t - 0.5 || r.bottom > vh - inset.b + 0.5) {
        // inside a scrolling sheet a row may be scrolled out of view (Known limitation 3): allowed when its scroller can reach it
        const sc = c.closest('.demo-list, .demo-picker');
        const inSafeX = r.left >= inset.l - 0.5 && r.right <= vw - inset.r + 0.5;
        if (!sc || sc.scrollHeight <= sc.clientHeight || !inSafeX) out.push(`control under a safe-area inset or outside the viewport (insets t${inset.t} r${inset.r} b${inset.b} l${inset.l}): ${c.getAttribute('data-testid') || c.getAttribute('aria-label') || c.textContent.trim().slice(0, 30)} [${r.left | 0},${r.top | 0},${r.right | 0},${r.bottom | 0}]`);
      }
    }
    const must = state === 'picker' ? ['demo-start'] : state === 'arrival' ? ['demo-nav-close'] : ['demo-nav-end', 'demo-nav-voice', ...(state === 'guidance' ? ['demo-nav-recenter'] : [])];
    for (const id of must) {
      const e = document.querySelector(`[data-testid=${id}]`);
      if (!vis(e)) { out.push(`${id} not visible`); continue; }
      const r = R(e);
      const cx = Math.min(Math.max((r.left + r.right) / 2, 1), vw - 1), cy = Math.min(Math.max((r.top + r.bottom) / 2, 1), vh - 1);
      const hit = document.elementFromPoint(cx, cy);
      if (!hit || !(e === hit || e.contains(hit))) out.push(`${id} not reachable (covered by ${hit?.getAttribute('data-testid') || hit?.className || hit?.tagName})`);
      if (r.bottom > vh + 0.5 || r.top < -0.5) out.push(`${id} outside the viewport`);
    }
    // banner instruction: ≤ 3 lines, not truncated, no mid-word break
    const ins = document.querySelector('[data-testid=demo-nav-text]');
    if (state !== 'picker' && vis(ins)) {
      const cs = getComputedStyle(ins);
      const lh = parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.25;
      const lines = Math.round(ins.getBoundingClientRect().height / lh);
      if (lines > 3) out.push(`banner instruction ${lines} lines`);
      if (ins.scrollHeight > ins.clientHeight + 1 || ins.scrollWidth > ins.clientWidth + 1) out.push('banner instruction clipped');
      if (cs.textOverflow === 'ellipsis' && cs.overflow !== 'visible') out.push('banner instruction can be truncated (ellipsis)');
      if (cs.wordBreak === 'break-all' || cs.overflowWrap === 'anywhere' || cs.hyphens === 'auto') out.push(`banner instruction may break mid-word (${cs.wordBreak}/${cs.overflowWrap}/${cs.hyphens})`);
    }
    // controls do not overlap each other, and none is clipped inside a scrolling card (the message stack has
    // overflow-y: auto; a clipped A1 notice means its text is cut, which the screen spec forbids: "wraps, never truncated")
    const cs2 = controls.filter((c) => !c.closest('.demo-list'));
    const clipped = (c) => { const sc = c.closest('.dn-messages'); if (!sc || sc.scrollHeight <= sc.clientHeight + 1) return null; const a = R(c), s = R(sc); return a.bottom > s.bottom + 0.5 || a.top < s.top - 0.5 ? `${c.getAttribute('data-testid') || c.textContent.trim().slice(0, 20)} clipped inside a scrolling card (card ${s.top | 0}–${s.bottom | 0}, control ${a.top | 0}–${a.bottom | 0}, card scrolls ${sc.scrollHeight - sc.clientHeight} px)` : null; };
    for (const c of cs2) { const m = clipped(c); if (m) out.push(m); }
    for (let i = 0; i < cs2.length; i++) for (let j = i + 1; j < cs2.length; j++) {
      if (cs2[i].contains(cs2[j]) || cs2[j].contains(cs2[i])) continue;
      if (clipped(cs2[i]) || clipped(cs2[j])) continue; // reported above; the visible parts are bounded by the card
      const a = R(cs2[i]), b = R(cs2[j]);
      if (a.left < b.right - 0.5 && b.left < a.right - 0.5 && a.top < b.bottom - 0.5 && b.top < a.bottom - 0.5) out.push(`controls overlap: ${cs2[i].getAttribute('data-testid') || cs2[i].textContent.trim().slice(0, 20)} / ${cs2[j].getAttribute('data-testid') || cs2[j].textContent.trim().slice(0, 20)}`);
    }
    return out;
  }, { state, attrText: S.mn.attribution });
}

for (const [w, h] of VIEWPORTS) {
  for (const zoom of ZOOMS) {
    test(`AC37 ${w}×${h} at ${zoom} %: picker, guidance (recenter shown), A1 notice, arrival: attribution visible and uncovered, controls inside the viewport and reachable, banner ≤ 3 lines without truncation`, async ({ freshBrowser: browser }, ti) => {
      test.setTimeout(10 * 60_000);
      const { defaultBrowserType, trace, ...use } = ti.project.use;
      const problems = [];
      for (const state of ['picker', 'guidance', 'a1', 'arrival']) {
        const ctx = await browser.newContext({ ...use, viewport: { width: w, height: h }, reducedMotion: 'reduce' });
        const page = await ctx.newPage();
        await reach(page, state, zoom, w, h);
        if (state === 'a1') await expect(page.locator('[data-kind="voice-unavailable"]')).toBeVisible();
        if (state === 'arrival') await expect(tid(page, 'demo-nav-arrival')).toBeVisible();
        for (const p of await layoutProblems(page, state)) problems.push(`${state}: ${p}`);
        await ti.attach(`${w}x${h}-${zoom}-${state}.png`, { body: await page.screenshot(), contentType: 'image/png' });
        await ctx.close();
      }
      expect(problems).toEqual([]);
    });
  }
}

test('AC37 safe areas (static): viewport-fit=cover, and every demo region pads with env(safe-area-inset-*) on the edges it touches; the app is sized to the visible viewport (no 100vh)', ({}, ti) => {
  test.skip(ti.project.name !== 'chromium-iphone', 'static check, once');
  const dir = WEB + 'dist-demo-mode/';
  const html = readFileSync(dir + 'index.html', 'utf8');
  expect(html).toMatch(/viewport-fit=cover/);
  const css = readdirSync(dir + 'assets').filter((f) => f.endsWith('.css')).map((f) => readFileSync(dir + 'assets/' + f, 'utf8')).join('\n');
  for (const edge of ['top', 'bottom', 'left', 'right']) expect(css, `env(safe-area-inset-${edge})`).toContain(`env(safe-area-inset-${edge}`);
  // the guidance overlay container pads top, right and left; the attribution strip (R5) pads the bottom
  expect(css).toMatch(/\.demo-nav\{[^}]*safe-area-inset-top[^}]*safe-area-inset-right[^}]*safe-area-inset-left/);
  expect(css).toMatch(/safe-area-inset-bottom/);
  expect(css).not.toMatch(/height:\s*100vh/);
});

/**
 * axe-core yields between rule groups with setTimeout; under the paused Playwright clock those timers never fire and
 * analyze() hangs (seen as 5 min timeouts). Pump the fake clock in small steps until the scan resolves; the page state
 * stays frozen apart from those timers (verified: the same scan finishes in about 1.5 s with the pump).
 */
async function axeAnalyze(page, builder) {
  let settled = false;
  const scan = builder.analyze().finally(() => { settled = true; });
  const t0 = Date.now();
  while (!settled && Date.now() - t0 < 120_000) { await page.clock.runFor(200).catch(() => {}); await page.waitForTimeout(10); }
  return scan;
}

async function a11yState(page, state, theme) {
  await page.addInitScript((t) => localStorage.setItem('navmn.theme', t), theme);
  await reach(page, state === 'guidance' ? 'guidance' : state, 100);
}

for (const theme of ['day', 'night']) {
  for (const state of ['picker', 'guidance', 'arrival']) {
    test(`AC38 ${state} (${theme}): axe WCAG 2.2 AA (contrast ≥ 4.5:1, names, ARIA) on the demo UI; every control has a glossary name; touch targets ≥ 44×44 CSS px`, async ({ page }) => {
      test.setTimeout(5 * 60_000);
      await a11yState(page, state, theme);
      const res = await axeAnalyze(page, new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).exclude('canvas').exclude('.maplibregl-canvas-container'));
      const v = res.violations.map((x) => `${x.id}: ${x.nodes.map((n) => n.target.join(' ')).slice(0, 4).join(' | ')}`);
      expect(v).toEqual([]);
      // names
      const names = await page.evaluate(() => [...document.querySelectorAll('button, [role=radio], a[href]')].filter((e) => e.getClientRects().length && !e.closest('[hidden]')).map((e) => ({ id: e.getAttribute('data-testid'), name: (e.getAttribute('aria-label') || e.textContent || '').trim(), w: e.getBoundingClientRect().width, h: e.getBoundingClientRect().height })));
      const want = { 'demo-start': S.mn.start, 'demo-nav-end': S.mn.end, 'demo-nav-voice': S.mn.mute, 'demo-nav-recenter': S.mn.recenter, 'demo-nav-close': S.mn.close };
      for (const n of names) {
        if (want[n.id]) expect(n.name, n.id).toBe(want[n.id]);
        expect(n.name.length, `${n.id} has a name`).toBeGreaterThan(0);
        if (n.id === 'attribution-osm') continue; // NAV-002 rule 6a: hit area via padding of the strip, checked in NAV-002
        expect(Math.min(n.w, n.h), `${n.id || n.name} target ${n.w.toFixed(0)}×${n.h.toFixed(0)}`).toBeGreaterThanOrEqual(44);
      }
      if (state === 'picker') {
        const r = page.getByRole('radio').first();
        await expect(r).toHaveAccessibleName(/Эхлэх цэг.*Очих газар/);
      }
      if (state !== 'picker') await expect(page.locator('[data-testid=demo-nav-banner] .dn-icon')).toHaveAttribute('aria-hidden', 'true');
      await expect(tid(page, 'demo-nav-live')).toHaveAttribute('aria-live', 'polite');
    });
  }
}

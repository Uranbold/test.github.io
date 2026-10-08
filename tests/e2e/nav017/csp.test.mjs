// NAV-017 test tooling under the SEC-4B Content-Security-Policy (SEC-4B AC 16: the NAV-017 suite still passes after the
// story; review item 1 of 2026-10-08). The demo-mode build now carries a meta CSP whose style-src allows only hashed
// inline styles, so the old helpers.hideCanvas body (page.addStyleTag with an inline <style>) is refused by the page
// ("Refused to apply inline style … style-src"), throws, and leaves the canvas painted. Playwright never bypasses CSP
// here (bypassCSP stays false): the suite must keep testing the build as shipped.
// Regression test, written failing first (bug-lane rule) and kept forever.
import { expect } from '@playwright/test';
import { test } from './helpers.mjs';
import { hideCanvas, openDemo } from './helpers.mjs';

const CSP_TEXT = /Content[ -]Security[ -]Policy|Refused to (apply|load|execute)/i;

test('SEC-4B AC16 (review item 1): helpers.hideCanvas hides every MapLibre canvas of the demo-mode build under its meta CSP with 0 CSP violations', async ({ page }) => {
  const console = [];
  page.on('console', (m) => { if (CSP_TEXT.test(m.text())) console.push(m.text()); });
  // CDP init script: not subject to the page policy, records from the first byte.
  await page.addInitScript(() => {
    window.__qaCsp = [];
    document.addEventListener('securitypolicyviolation', (e) => window.__qaCsp.push({ directive: e.violatedDirective, blocked: e.blockedURI, sample: e.sample }));
  });
  await openDemo(page, { voices: ['mn-MN'] });
  // Precondition: the build under test enforces a meta CSP with hashed inline styles only (the reason for this test).
  const policy = await page.locator('meta[http-equiv="Content-Security-Policy"]').getAttribute('content');
  expect(policy, 'demo-mode build carries the SEC-4B meta policy').toMatch(/style-src 'self'( 'sha256-[^']+')+;/);
  expect(policy).not.toContain("'unsafe-inline'");
  const before = await page.evaluate(() => [...document.querySelectorAll('.maplibregl-canvas')].map((c) => getComputedStyle(c).visibility));
  expect(before.length, 'precondition: the map canvas exists when the picker shows').toBeGreaterThan(0);
  expect(before.every((v) => v === 'visible')).toBe(true);

  await hideCanvas(page);

  const after = await page.evaluate(() => [...document.querySelectorAll('.maplibregl-canvas')].map((c) => getComputedStyle(c).visibility));
  expect(after, 'every MapLibre canvas is hidden').toEqual(before.map(() => 'hidden'));
  // The rule must survive MapLibre re-styling its canvas (it sets inline width/height on resize) and cover a canvas
  // created later, as the old stylesheet rule did.
  await page.setViewportSize({ width: 400, height: 800 });
  const late = await page.evaluate(() => {
    const c = document.createElement('canvas');
    c.className = 'maplibregl-canvas';
    document.body.append(c);
    const v = getComputedStyle(c).visibility;
    c.remove();
    return v;
  });
  expect(late, 'a canvas added after hideCanvas is hidden too').toBe('hidden');
  const resized = await page.evaluate(() => [...document.querySelectorAll('.maplibregl-canvas')].map((c) => getComputedStyle(c).visibility));
  expect(resized.every((v) => v === 'hidden'), 'still hidden after a resize').toBe(true);
  expect(await page.evaluate(() => window.__qaCsp), '0 securitypolicyviolation events').toEqual([]);
  expect(console, '0 CSP console messages').toEqual([]);
});

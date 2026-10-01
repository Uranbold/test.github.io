// NAV-017: items the architect's integration review of ADR-0011 (Amendments 1 and 2) made binding for the demo-mode
// build, written here as reproducing tests so the fix loop has a failing test first (bug-lane rule) and a regression
// test afterwards. Story AC 27/28 (voice unlock and decision) and the build's licence duties; README consistency.
import { expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { test } from './helpers.mjs';
import { WEB, S, golden, openDemo, qa, spoken, tick, tid } from './helpers.mjs';

test.use({ reducedMotion: 'reduce' });
const run = async (page, seconds, chunk = 5000) => { for (let left = seconds * 1000; left > 0; left -= chunk) await page.clock.runFor(Math.min(chunk, left)); };

test('ADR-0011 §7 Amendment 1 / AC 27–28: «Эхлэх» tapped while the 3 s voice decision is still pending → depart prompt chimed, speech primed inside the handler with one empty zero-volume utterance (no voice set, no Mongolian text); once the late voice list names a Mongolian voice, the next prompt is spoken with it', async ({ page }) => {
  test.setTimeout(10 * 60_000);
  // The list stays empty until the test calls window.__qaListVoices(); waitForVoices() polls every 250 ms for 3 s.
  await openDemo(page, { voices: ['mn-MN'], voicesLate: 'manual' });
  await page.addStyleTag({ content: '.maplibregl-canvas{visibility:hidden !important}' });
  // Select R1 WITHOUT advancing the page clock, so the decision (3 s of page time from the picker opening) is still
  // running when «Эхлэх» is tapped. Data, WASM and plan load in real time.
  await page.locator('[data-testid="demo-route"][data-route="R1"]').click();
  await expect(page.locator('[data-testid="demo-start"][aria-disabled="false"]')).toHaveCount(1, { timeout: 30_000 });
  // Precondition: the decision is still polling getVoices() (a call within the last 260 ms of page time).
  const calls0 = (await qa(page)).voicesCalls.length;
  await tick(page, 260);
  const calls1 = (await qa(page)).voicesCalls.length;
  expect(calls1, 'precondition: the voice decision is still pending (getVoices() polled) at «Эхлэх»').toBeGreaterThan(calls0);
  await tid(page, 'demo-start').click();
  await page.evaluate((p) => window.__qaRecord(p), 1000);
  await tick(page, 500);
  const start = await qa(page);
  // As implemented and accepted by the amendment: the depart prompt is a chime while the decision is pending.
  expect(start.chimesRel.length, 'depart prompt chimed inside/after the handler').toBe(1);
  expect(start.chimesRel[0]).toBeLessThanOrEqual(2000);
  expect(start.speak.filter((s) => /[Ѐ-ӿ]/.test(s.text)), 'no Mongolian text to a missing voice').toEqual([]);
  // Required by the amendment: one empty, zero-volume utterance with no voice, spoken INSIDE the «Эхлэх» handler.
  // (soft, so the rest of the scenario is still reported for the fix loop)
  const priming = start.speak.filter((s) => s.text === '' && s.inClick);
  expect.soft(priming.length, 'speech primed with an empty utterance inside the «Эхлэх» handler').toBeGreaterThanOrEqual(1);
  if (priming.length) {
    expect.soft(priming[0].volume, 'priming utterance volume 0').toBe(0);
    expect.soft(priming[0].voice, 'priming utterance has no voice set').toBeNull();
  }
  // The voice list arrives late (within the 3 s window): the decision finds the Mongolian voice.
  await page.evaluate(() => window.__qaListVoices());
  await tick(page, 300);
  // Next golden prompt («1 километрт зүүн тийш эргэнэ үү» at ~176 s) is spoken with the Mongolian voice, not chimed.
  await run(page, 180);
  const end = await qa(page);
  const said = spoken(end);
  const second = golden('G1', 'mn')[1];
  const spokenSecond = said.find((s) => s.text === second.text);
  expect(spokenSecond, `«${second.text}» spoken once the voice is known`).toBeDefined();
  expect(spokenSecond.voice).toBe('mn-MN');
  expect(Math.abs(spokenSecond.t - second.t)).toBeLessThanOrEqual(2);
  expect(end.chimesRel.length, 'no chime after the voice is known').toBe(1);
  await expect(page.locator('[data-testid="demo-nav-messages"] [data-kind="voice-unavailable"]'), 'no A1: a Mongolian voice exists').toBeHidden();
});

test.describe('Build duties from the ADR-0011 review (no browser)', () => {
  test.beforeEach(({}, ti) => test.skip(ti.project.name !== 'chromium-iphone', 'file checks run once'));

  test('ADR-0011 §10 Amendment 1: THIRD_PARTY_NOTICES.md reproduces the full BSD-3-Clause licence of @stadiamaps/ferrostar 0.57.0 (copyright line, conditions, disclaimer), because the demo-mode build redistributes its WASM binary', () => {
    const notices = readFileSync(WEB + 'THIRD_PARTY_NOTICES.md', 'utf8');
    const i = notices.indexOf('#### @stadiamaps/ferrostar');
    expect(i, 'Ferrostar entry present').toBeGreaterThan(0);
    const next = notices.indexOf('\n#### ', i + 10);
    const sec = notices.slice(i, next > 0 ? next : undefined);
    // Upstream LICENSE.txt at tag 0.57.0 (fetched 2026-10-01: 31 lines): the three BSD-3-Clause parts
    expect(sec).toContain('Copyright (c) 2023, Stadia Maps, Inc.');
    expect(sec).toMatch(/Redistribution and use in source and binary forms, with or without\s+modification, are permitted provided that the following conditions are met/);
    expect(sec).toMatch(/THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"/);
    expect(sec).not.toMatch(/ships no licen[cs]e file/i);
  });

  test('ADR-0011 Amendment 2 README data note: the README "Demo mode" route list names each picker destination exactly as the manifest (what the PO sees in the picker)', () => {
    const readme = readFileSync(WEB + 'README.md', 'utf8');
    const i = readme.indexOf('## Demo mode (NAV-017)');
    const sec = readme.slice(i, readme.indexOf('\n## ', i + 10));
    const m = JSON.parse(readFileSync(WEB + 'src/demo/routes.manifest.json', 'utf8'));
    const problems = [];
    for (const e of m.routes.filter((r) => r.picker)) {
      if (!sec.includes(e.destination.name)) problems.push(`${e.label}: destination «${e.destination.name}» not named in the README route list`);
      if (e.origin.name !== S.mn.selectedPoint && !sec.includes(e.origin.name)) problems.push(`${e.label}: origin «${e.origin.name}» not named in the README route list`);
    }
    expect(problems).toEqual([]);
  });
});

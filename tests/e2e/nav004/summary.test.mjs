// NAV-004 D. Summary: distance, duration and «Хүрэх цаг»: AC 22–25 (mocked responses, fake clock).
// The unit-test lists of AC 23/24 are covered by web/src/route/format.test.ts (mobile-engineer); these tests check the
// same boundary values on screen (summary, route options, step rows) against QA's own oracle (helpers fmtDistance/
// fmtDuration, transcribed from the story).
import { test, expect } from '@playwright/test';
import { REF, T, fmtDistance, fmtDuration, lives, mockReverse, mockRoute, nb, openApp, openPreview, panel, routeReqs, setField, simpleBody, tid, waitFinal } from './helpers.mjs';

test.use({ timezoneId: 'Asia/Ulaanbaatar' });

const DIST = [4, 5, 994, 995, 1000, 1449, 1450, 12_000, 12_449, 99_949, 99_950, 245_300];
const DUR = [0, 29, 30, 89, 1500, 3569, 3570, 5100, 7200, 108_000, 7260, 60];

let queue = [];
const echo = (b) => {
  const specs = queue.shift() ?? [{ distance: 4300, duration: 720 }];
  return { body: simpleBody({ lat: b.locations[0].lat, lng: b.locations[0].lon }, { lat: b.locations[1].lat, lng: b.locations[1].lon }, specs) };
};

async function ready(page, opts) {
  await mockReverse(page);
  await openApp(page, opts);
  await openPreview(page, REF.P3);
  await setField(page, 'origin', REF.P1);
  await waitFinal(page);
}

for (const lang of ['mn', 'en']) {
  test(`AC22–24 (${lang}): summary, route options and step rows format distances and durations per AC 23/24 (${lang === 'mn' ? 'comma' : 'point'} decimal)`, async ({ page }) => {
    queue = [];
    await mockRoute(page, echo);
    await ready(page, { lang });
    const rows = [];
    for (let i = 0; i < DIST.length; i += 3) {
      const specs = [0, 1, 2]
        .filter((j) => i + j < DIST.length)
        .map((j) => ({ distance: DIST[i + j], duration: DUR[i + j], steps: [{ maneuver: { type: 'depart', bearing_after: 0 }, name: '', distance: DIST[i + j] }, { maneuver: { type: 'arrive' }, distance: 0 }] }));
      queue.push(specs);
      await tid(page, 'route-swap').click();
      await waitFinal(page);
      await expect.poll(async () => (await routeReqs(page)).length).toBe(2 + i / 3);
      const p = await panel(page);
      expect(nb(p.distance), `summary distance ${specs[0].distance}`).toBe(fmtDistance(specs[0].distance, lang));
      expect(nb(p.duration), `summary duration ${specs[0].duration}`).toBe(fmtDuration(specs[0].duration, lang));
      expect(nb(p.eta)).toMatch(new RegExp(`^${T[lang].eta} \\d{2}:\\d{2}( .+)?$`));
      expect(nb(p.steps[0].dist), 'step row distance').toBe(fmtDistance(specs[0].distance, lang));
      expect(p.steps.at(-1).dist ?? '', 'arrive row has no distance').toBe('');
      if (specs.length > 1) {
        p.options.forEach((o, k) => {
          expect(nb(o.text), `option ${k + 1}`).toContain(fmtDistance(specs[k].distance, lang));
          expect(nb(o.text), `option ${k + 1}`).toContain(fmtDuration(specs[k].duration, lang));
          rows.push(`${specs[k].distance} m → ${fmtDistance(specs[k].distance, lang)}; ${specs[k].duration} s → ${fmtDuration(specs[k].duration, lang)}`);
        });
      }
    }
    test.info().annotations.push({ type: `AC23/24 ${lang} values`, description: rows.join(' | ') });
  });
}

test('AC25: response at 13:50:00 with duration 2,700 s → «Хүрэх цаг 14:35»; recomputed every 60 s from the clock (13:51 → 14:36) with 0 requests and no announcement', async ({ page }) => {
  queue = [];
  await page.clock.install({ time: new Date('2026-09-30T13:45:00+08:00') });
  await mockRoute(page, echo);
  await ready(page);
  await page.clock.pauseAt(new Date('2026-09-30T13:50:00+08:00'));
  queue.push([{ distance: 4300, duration: 2700 }]);
  const n0 = (await routeReqs(page)).length;
  await tid(page, 'route-swap').click();
  await expect.poll(async () => (await routeReqs(page)).length).toBe(n0 + 1);
  await expect.poll(async () => nb((await panel(page)).eta)).toBe(`${T.mn.eta} 14:35`);
  expect((await panel(page)).state).toBe('route');
  // The result announcement is scheduled with a timer, which the paused clock holds: let it fire first.
  await page.clock.runFor(1500);
  await expect.poll(async () => (await lives(page)).at(-1)?.text ?? '').toContain(T.mn.count(1));
  const live0 = (await lives(page)).length;
  test.info().annotations.push({ type: 'AC25/47 result announcement', description: (await lives(page)).at(-1).text });
  await page.clock.runFor(60_000);
  await expect.poll(async () => nb((await panel(page)).eta)).toBe(`${T.mn.eta} 14:36`);
  await page.clock.runFor(60_000);
  await expect.poll(async () => nb((await panel(page)).eta)).toBe(`${T.mn.eta} 14:37`);
  expect((await routeReqs(page)).length, '0 requests for the minute refresh').toBe(n0 + 1);
  expect((await lives(page)).length, 'the minute update is not announced').toBe(live0);
});

test('AC25: arrival on a later day → «Хүрэх цаг 00:30 +1 өдөр» (23:30:00 + 3,600 s), English "Arrive at 00:30 +1 day"; +2 days', async ({ page }) => {
  queue = [];
  await page.clock.install({ time: new Date('2026-09-30T23:20:00+08:00') });
  await mockRoute(page, echo);
  await ready(page);
  await page.clock.pauseAt(new Date('2026-09-30T23:30:00+08:00'));
  queue.push([{ distance: 60_000, duration: 3600 }]);
  await tid(page, 'route-swap').click();
  await expect.poll(async () => nb((await panel(page)).eta)).toBe(`${T.mn.eta} 00:30 ${T.mn.nextDay(1)}`);
  await tid(page, 'language-toggle').click();
  await expect.poll(async () => nb((await panel(page)).eta)).toBe(`${T.en.eta} 00:30 ${T.en.nextDay(1)}`);
  queue.push([{ distance: 3_000_000, duration: 86_400 + 3600 }]); // 23:30 + 25 h = 00:30 two calendar days later
  await tid(page, 'route-swap').click();
  await expect.poll(async () => nb((await panel(page)).eta)).toBe(`${T.en.eta} 00:30 ${T.en.nextDay(2)}`);
  await tid(page, 'language-toggle').click();
  await expect.poll(async () => nb((await panel(page)).eta)).toBe(`${T.mn.eta} 00:30 ${T.mn.nextDay(2)}`);
});

test('AC25: ETA rounds to the nearest minute (13:50:29 + 60 s → 13:51; 13:50:31 + 60 s → 13:52)', async ({ page }) => {
  queue = [];
  await page.clock.install({ time: new Date('2026-09-30T13:45:00+08:00') });
  await mockRoute(page, echo);
  await ready(page);
  await page.clock.pauseAt(new Date('2026-09-30T13:50:29+08:00'));
  queue.push([{ distance: 400, duration: 60 }]);
  await tid(page, 'route-swap').click();
  await expect.poll(async () => nb((await panel(page)).eta)).toBe(`${T.mn.eta} 13:51`);
  await page.clock.pauseAt(new Date('2026-09-30T13:50:31+08:00'));
  queue.push([{ distance: 400, duration: 60 }]);
  await tid(page, 'route-swap').click();
  await expect.poll(async () => nb((await panel(page)).eta)).toBe(`${T.mn.eta} 13:52`);
});

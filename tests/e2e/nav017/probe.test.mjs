// TEMP probe (deleted after timing): how long does R1 take at fake time?
import { test } from '@playwright/test';
import { openDemo, selectRoute, startReplay, runFor, qa, spoken } from './helpers.mjs';

test('probe R1 timing', async ({ page }) => {
  const t0 = Date.now();
  await openDemo(page, { voices: ['mn-MN'] });
  console.log('picker', Date.now() - t0);
  await selectRoute(page, 'R1');
  console.log('selected', Date.now() - t0);
  await startReplay(page);
  for (let s = 0; s < 330; s += 30) { await runFor(page, 30); console.log('t', s, Date.now() - t0); }
  const log = await qa(page);
  console.log(JSON.stringify(spoken(log)));
  console.log('rec', log.rec.length, JSON.stringify(log.rec.at(-1)));
});

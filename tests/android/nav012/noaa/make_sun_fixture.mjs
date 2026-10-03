#!/usr/bin/env node
// NAV-012 AC 40 reference fixture: NOAA Solar Calculator sunrise/sunset for the story's four reference points on the
// four AC 40 dates. Runs the NOAA calculator's own calculation functions (noaa_solcalc_calc.cjs, vendored unchanged
// from https://gml.noaa.gov/grad/solcalc/main.js) the way the web page calls them: jday = getJD(local date),
// calcSunriseSet(rise, jday, lat, lon, tz). The local date and UTC offset are what the page uses for the IANA zone
// of each point (Asia/Ulaanbaatar +08:00, Asia/Hovd +07:00; Mongolia has no DST).
//
//   node tests/android/nav012/noaa/make_sun_fixture.mjs           # (re)write the fixture
//   node tests/android/nav012/noaa/make_sun_fixture.mjs --check   # exit 1 if the committed fixture is stale
//
// Consumer: mobile/android/app/src/test/java/mn/navmn/app/qa/QaNav012Test.kt (TC-H01).
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const noaa = require('./noaa_solcalc_calc.cjs');
const out = join(here, '..', 'noaa-sun-fixture.json');

// Story "Terms": reference points (NAV-001 P1, X1; NAV-012 K1, Z1).
const points = [
  { id: 'P1', name: 'Sukhbaatar Square', lat: 47.9189, lon: 106.9176, zone: 'Asia/Ulaanbaatar', tz: 8 },
  { id: 'X1', name: 'Erdenet', lat: 49.027, lon: 104.044, zone: 'Asia/Ulaanbaatar', tz: 8 },
  { id: 'K1', name: 'Khovd', lat: 48.0056, lon: 91.6419, zone: 'Asia/Hovd', tz: 7 },
  { id: 'Z1', name: 'Zamyn-Uud', lat: 43.7167, lon: 111.9, zone: 'Asia/Ulaanbaatar', tz: 8 },
];
const dates = ['2026-03-20', '2026-06-21', '2026-09-23', '2026-12-21'];

function pad(n, w = 2) { return String(n).padStart(w, '0'); }

/** NOAA result {jday, timelocal (min)} → UTC ISO instant (rounded to the second) and the local HH:MM:SS. */
function toUtc(res, tz) {
  // jday is the Julian day of local midnight (x.5); convert to a Unix ms of local midnight, then subtract the offset.
  const localMidnightMs = (res.jday - 2440587.5) * 86400000;
  const ms = Math.round((localMidnightMs + res.timelocal * 60000 - tz * 3600000) / 1000) * 1000;
  const t = Math.round(res.timelocal * 60);
  const local = `${pad(Math.floor(t / 3600))}:${pad(Math.floor((t % 3600) / 60))}:${pad(t % 60)}`;
  return { utc: new Date(ms).toISOString().replace('.000Z', 'Z'), local };
}

const rows = [];
for (const p of points) {
  for (const d of dates) {
    const [y, m, day] = d.split('-').map(Number);
    const jday = noaa.getJD(y, m, day);
    const rise = noaa.calcSunriseSet(1, jday, p.lat, p.lon, p.tz);
    const set = noaa.calcSunriseSet(0, jday, p.lat, p.lon, p.tz);
    if (rise.azimuth < 0 || set.azimuth < 0) throw new Error(`no sunrise/sunset for ${p.id} ${d}`);
    const r = toUtc(rise, p.tz);
    const s = toUtc(set, p.tz);
    rows.push({
      point: p.id, lat: p.lat, lon: p.lon, zone: p.zone, utcOffsetHours: p.tz, localDate: d,
      sunriseUtc: r.utc, sunsetUtc: s.utc, sunriseLocal: r.local, sunsetLocal: s.local,
    });
  }
}

const fixture = {
  story: 'NAV-012',
  ac: 40,
  source: 'NOAA GML Solar Calculator, https://gml.noaa.gov/grad/solcalc/ (main.js calculation functions, vendored in tests/android/nav012/noaa/noaa_solcalc_calc.cjs)',
  sourceSha256: '3832956f24724eafacf9e18173b9299b1554d6252bed0784d9c9aca6d9f54856',
  recorded: '2026-10-03',
  method: 'jday = getJD(local date); calcSunriseSet(1|0, jday, lat, lon east-positive, utcOffsetHours); sun centre at -0.833 deg (zenith 90.833)',
  toleranceMinutes: 2,
  points: points.map(({ id, name, lat, lon, zone, tz }) => ({ id, name, lat, lon, zone, utcOffsetHours: tz })),
  rows,
};
const text = JSON.stringify(fixture, null, 2) + '\n';

if (process.argv.includes('--check')) {
  const cur = readFileSync(out, 'utf8');
  if (cur !== text) {
    console.error('noaa-sun-fixture.json is stale: run make_sun_fixture.mjs');
    process.exit(1);
  }
  console.log(`noaa-sun-fixture.json up to date (${rows.length} rows)`);
} else {
  writeFileSync(out, text);
  for (const r of rows) console.log(`${r.point} ${r.localDate} rise ${r.sunriseLocal} (${r.sunriseUtc})  set ${r.sunsetLocal} (${r.sunsetUtc})`);
}

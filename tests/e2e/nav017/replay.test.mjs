// NAV-017 C–F and H: full replays of R1, R2, R3 (and the test-only G4) at 1× on the Playwright clock, against the
// production demo-mode build in /demo-a/. One replay per describe block (serial, shared page); every test below asserts
// one AC on what that replay recorded. Story AC 12, 14, 17–22, 24–29, 32–34, 38, 39, 42, 43.
//
// Oracles: tests/gpx/nav005/golden/voice-golden.tsv (AC 26 cross-platform check, never relaxed), the recorded response
// and the GPX track (QA's RouteOracle), NAV-004 AC 23–27 transcriptions. The map canvas is not painted during these
// logic replays (helpers.fullReplay › hideCanvas); layout and rendering are covered in ui.test.mjs and layout.test.mjs.
import { expect } from '@playwright/test';
import { test } from './helpers.mjs';
import { ROUTES, G4, S, SENTINEL, fullReplay, golden, readGpx, readJson, routeBody, sentinelCopy, spoken, intersects } from './helpers.mjs';
import { bannerProblems, goldenProblems, layoutDuringReplayProblems, liveRegionProblems, oracleFor, progressProblems, scheduleProblems, voiceTextProblems } from './checks.mjs';

const START = new Date('2026-10-01T13:50:00+08:00');
const COORD = /-?\d{1,3}\.\d{4,}/;

/** Hosts and paths of every request (AC 42). */
function networkProblems(reqs, origin) {
  const p = [];
  for (const r of reqs) {
    const u = new URL(r.url);
    if (u.protocol === 'data:' || u.protocol === 'blob:') continue;
    if (u.origin !== origin) p.push(`other host: ${r.url}`);
    if (/\/v1\/(search|reverse|route)/.test(u.pathname)) p.push(`backend request: ${r.url}`);
    if (r.sw) p.push(`service worker request: ${r.url}`);
  }
  return [...new Set(p)];
}

/** AC 43: only theme, language and voice choice stored; 0 coordinates in storage, console or URL. */
function privacyProblems(run) {
  const p = [];
  const keys = Object.keys(run.storage.local);
  for (const k of keys) if (!['navmn.theme', 'navmn.lang', 'navmn.voiceMuted'].includes(k)) p.push(`localStorage key ${k}`);
  if (Object.keys(run.storage.session).length) p.push(`sessionStorage: ${JSON.stringify(run.storage.session)}`);
  if (run.storage.cookie) p.push(`cookie: ${run.storage.cookie}`);
  if (run.storage.sw) p.push('service worker controls the page');
  if (COORD.test(run.storage.url)) p.push(`coordinate in URL ${run.storage.url}`);
  for (const v of Object.values(run.storage.local)) if (COORD.test(v)) p.push(`coordinate in storage: ${v}`);
  for (const c of run.log.console) if (COORD.test(c)) p.push(`coordinate in console: ${c.slice(0, 120)}`);
  return p;
}

/**
 * Declares the tests of one full replay. `spec`: { name, label, lang, voices, golden: [track, lang] | null, sentinel,
 * mode, expectChime, track (override) }.
 */
function replaySuite(spec) {
  test(`${spec.name}`, async ({ freshBrowser: browser }, ti) => {
    test.setTimeout(40 * 60_000);
    let run;
    let said;
    const oracle = () => oracleFor(spec.label, spec.track ?? null);
    // One test per replay; every AC is a test.step with soft assertions, so one failing AC does not hide the others.
    // The QA report lists the AC steps (JSON reporter: steps[] with errors).
    {
      let body;
      if (spec.sentinel) body = await routeBody(ROUTES[spec.label].id, { route: sentinelCopy(readJson(ROUTES[spec.label].route)) });
      if (spec.track) body = await routeBody(ROUTES[spec.label].id, { track: spec.track });
      const secs = spec.track ? Math.ceil(spec.track.at(-1).tMs / 1000) + 15 : undefined;
      run = await fullReplay(browser, ti.project, { name: spec.name, label: spec.label, lang: spec.lang, voices: spec.voices, body, seconds: secs, cfg: spec.cfg });
      said = spoken(run.log);
      await ti.attach(`${spec.name}.json`, { body: JSON.stringify({ spoken: said, chimes: run.log.chimesRel, banners: run.log.banners, live: run.log.live, wake: run.log.wake, wakeRelease: run.log.wakeRelease, t0: run.log.t0, errors: run.errors, last: run.log.rec.at(-1) }, null, 1), contentType: 'application/json' });
    }

    if (spec.golden) {
      await test.step(`AC26 golden: (manoeuvre, text) sequence equals voice-golden.tsv ${spec.golden.join(' ')} exactly, each prompt within ± 2 s`, async () => {
        const list = spec.expectChime ? run.log.chimesRel.map((t, i) => ({ t: t / 1000, text: golden(...spec.golden)[i]?.text ?? '?' })) : said;
        expect.soft(goldenProblems(list, ...spec.golden)).toEqual([]);
      });
    }
    if (!spec.expectChime) {
      await test.step('AC26 schedule oracle: at most once, stated distance within max(30 m, 20 %), no prompt for a passed manoeuvre, car: a prompt 20–250 m before each manoeuvre (D68 exemption)', async () => {
        expect.soft(scheduleProblems(said, spec.label, spec.golden?.[0] ?? 'G4', spec.golden?.[1] ?? spec.lang, ROUTES[spec.label].mode, oracle())).toEqual([]);
      });
      await test.step(`AC24/AC25: every spoken text passes the ${spec.lang === 'mn' ? 'NAV-005 AC 33 scan (0 digit+м/км, 0 \\d+-р, 0 Latin, 0 tokens, C2 after зүүн/баруун, no «1000 метрт»)' : 'English checks (0 Cyrillic, no "1000 meters")'}`, async () => {
        expect.soft(said.length).toBeGreaterThan(0);
        expect.soft(voiceTextProblems(said, spec.lang)).toEqual([]);
      });
      await test.step(`AC28: every utterance sets the ${spec.lang} voice and its lang explicitly${spec.lang === 'en' ? ' (en-US preferred)' : ''}`, async () => {
        for (const s of said) {
          expect.soft(s.voice, s.text).toMatch(spec.lang === 'mn' ? /^mn([-_]|$)/i : /^en-US$/i);
          expect.soft(s.lang, s.text).toBe(s.voice);
        }
      });
    }
    await test.step('AC12: the replay starts at the first track point; banner distance updates land within 100 ms after each whole replay second', async () => {
      const ds = run.log.rec.filter((r) => r.dist).length;
      expect.soft(ds).toBeGreaterThan(10);
      // distance mutations come from fixes only: their time since «Эхлэх» mod 1000 ms must be < 100 ms (+ 15 ms rendering)
      const off = run.log.banners.filter((b) => b.t > 50 && (b.t % 1000) > 115).map((b) => `${b.t.toFixed(0)} ms «${b.text}»`);
      expect.soft(off, `banner changes not aligned with fixes (of ${run.log.banners.length})`).toEqual([]);
    });
    await test.step('AC14: Geolocation never called; badge «Туршилтын горим» visible for the whole replay and not covering banner, progress or attribution; my-location control absent', async () => {
      expect.soft(run.log.geo).toBe(0);
      expect.soft(layoutDuringReplayProblems(run.log, { checkPuck: false })).toEqual([]);
    });
    await test.step('AC17–AC20: banner = AC 27 text of the next manoeuvre (never Valhalla text, never blank), AC 23 distance per fix, street of the following step, next manoeuvre ≤ 1 s after passing; AC 19 scans', async () => {
      expect.soft(bannerProblems(run.log, spec.label, spec.lang, oracle())).toEqual([]);
    });
    if (spec.sentinel) {
      await test.step(`AC18 sentinel: Valhalla text fields replaced by ${SENTINEL} appear 0 times on screen, in the live region and in speech`, async () => {
        const html = await run.page.content();
        expect.soft(html.includes(SENTINEL)).toBe(false);
        expect.soft(run.log.rec.filter((r) => JSON.stringify(r).includes(SENTINEL))).toEqual([]);
        expect.soft(run.log.live.filter((l) => l.text.includes(SENTINEL))).toEqual([]);
        expect.soft(run.log.speak.filter((s) => s.text.includes(SENTINEL))).toEqual([]);
      });
    }
    if (!spec.track) {
      await test.step('AC21: «Хүрэх цаг HH:MM» = clock + remaining duration (not-yet-driven share of the step + later steps), remaining time and distance in AC 23/24 format, refreshed ≤ 5 s', async () => {
        expect.soft(progressProblems(run.log, spec.label, spec.lang, START, oracle())).toEqual([]);
      });
      await test.step('AC22: the puck stays inside the map area not covered by banner, demo row, messages or progress while following', async () => {
        expect.soft(layoutDuringReplayProblems(run.log, { checkPuck: true })).toEqual([]);
      });
    }
    await test.step('AC32–AC34: arrival banner from the arrive modifier, spoken once (or one chime), approaching prompt before it; then 0 prompts, arrival panel with the manifest name and «Хаах»; exactly 1 arrival', async () => {
      const o = oracle().o;
      const arrive = o.steps.at(-1).maneuver;
      const L = S[spec.lang];
      const want = arrive.modifier && /left/.test(arrive.modifier) ? L.arriveLeft : arrive.modifier && /right/.test(arrive.modifier) ? L.arriveRight : L.arrive;
      const last = run.log.rec.at(-1);
      expect.soft(last.variant).toBe('arrival');
      expect.soft(last.text).toBe(want);
      expect.soft(last.arrival, 'arrival panel visible').not.toBeNull();
      expect.soft(last.arrival).toContain(spec.destination);
      expect.soft(last.arrival).toContain(L.close);
      expect.soft(last.progress).toBe(false);
      if (spec.expectChime) {
        const nArr = run.log.rec.findIndex((r) => r.variant === 'arrival');
        const tArr = run.log.rec[nArr].t;
        const after = run.log.chimesRel.filter((t) => t > tArr + 3500);
        expect.soft(after, 'chimes after the arrival chime').toEqual([]);
      } else {
        const arrivals = said.filter((s) => s.text === want);
        expect.soft(arrivals, 'exactly one arrival message').toHaveLength(1);
        const idx = said.indexOf(arrivals[0]);
        expect.soft(said.slice(idx + 1), '0 prompts after arrival').toEqual([]);
        // approaching prompt before it, when the schedule has one (A11)
        const approach = said.slice(0, idx).filter((s) => /очих газартаа хүрнэ|you will arrive/i.test(s.text));
        // required when the schedule of the played (mn) recording has one: the mn golden rows of the same track
        if (golden(spec.golden?.[0] ?? '', 'mn').some((g) => /очих газартаа хүрнэ/.test(g.text)) || spec.track) expect.soft(approach.length).toBeGreaterThanOrEqual(1);
      }
      // arrival banner stays (no further banner changes)
      const iArr = run.log.banners.findIndex((b) => b.text === want);
      expect.soft(iArr).toBeGreaterThanOrEqual(0);
      expect.soft(run.log.banners.slice(iArr + 1).filter((b) => b.text !== want)).toEqual([]);
    });
    await test.step('AC38: each new banner instruction announced once through the polite live region (first banner via focus; no distance updates)', async () => {
      expect.soft(liveRegionProblems(run.log)).toEqual([]);
    });
    await test.step('AC39: a screen wake lock is requested at «Эхлэх» while visible and released ≤ 1 s after arrival', async () => {
      expect.soft(run.log.wake.length).toBeGreaterThanOrEqual(1);
      expect.soft(run.log.wake[0].type).toBe('screen');
      expect.soft(run.log.wake[0].t - run.log.t0).toBeLessThan(1000);
      // arrival = the banner's switch to the arrival variant (the arrive text is already the "next manoeuvre" on the last step)
      const tArr = (run.log.variants ?? []).find((v) => v.v === 'arrival')?.t ?? run.log.rec.find((r) => r.variant === 'arrival')?.t;
      expect.soft(tArr).toBeTruthy();
      expect.soft(run.log.wakeRelease.length).toBeGreaterThanOrEqual(1);
      expect.soft(run.log.wakeRelease[0] - run.log.t0 - tArr, 'release after arrival (ms)').toBeLessThanOrEqual(1000);
    });
    await test.step('AC42/AC43: every request goes to the page origin (0 /v1/search|reverse|route, 0 other hosts, 0 service worker); storage holds only theme, language, voice choice; 0 coordinates in storage, console, URL; 0 page errors', async () => {
      expect.soft(networkProblems(run.reqs, new URL(run.storage.url).origin)).toEqual([]);
      expect.soft(privacyProblems(run)).toEqual([]);
      expect.soft(run.errors).toEqual([]);
    });
    if (spec.track) {
      await test.step('AC26: no prompt for a manoeuvre already passed when the track starts (G4 starts 189 m past the left turn of R1)', async () => {
        const { o, alongs } = oracle();
        const passedLeft = o.maneuverAlong[1] < alongs[0].along;
        expect.soft(passedLeft, 'G4 starts after manoeuvre 1').toBe(true);
        expect.soft(said.filter((x) => /зүүн тийш/iu.test(x.text)).map((x) => `${x.t.toFixed(1)} s «${x.text}»`)).toEqual([]);
      });
    }
    if (spec.expectChime) {
      await test.step('AC29: no usable voice → 0 speak calls with Mongolian text, one chime per prompt with the AC 26 timing, A1 notice once for 8 s without covering banner, progress or attribution', async () => {
        expect.soft(run.log.speak.filter((s) => /[Ѐ-ӿ]/.test(s.text))).toEqual([]);
        const g = golden(...spec.golden);
        expect.soft(run.log.chimesRel.length, 'one chime per prompt').toBe(g.length);
        const shown = run.log.rec.filter((r) => r.msgs.includes('voice-unavailable'));
        expect.soft(shown.length, 'A1 visible in ≥ 5 one-second samples').toBeGreaterThanOrEqual(5);
        const first = shown[0].t, lastT = shown.at(-1).t;
        expect.soft(lastT - first, 'A1 shown for about 8 s').toBeLessThanOrEqual(9_000);
        expect.soft(lastT - first).toBeGreaterThanOrEqual(5_000 - 1_000);
        // once per replay: contiguous
        const gaps = shown.slice(1).filter((r, i) => r.t - shown[i].t > 1500);
        expect.soft(gaps, 'A1 shown once').toEqual([]);
        for (const r of shown) for (const k of ['banner', 'progress', 'attribution']) expect.soft(intersects(r.rects.messages, r.rects[k]), `A1 covers ${k} at ${r.t}`).toBe(false);
      });
    }
    await run.ctx.close();
  });
}

replaySuite({ name: 'R1 mn with a Mongolian voice (sentinel copy of the R1 response)', label: 'R1', lang: 'mn', voices: ['mn-MN', 'en-US'], golden: ['G1', 'mn'], sentinel: true, destination: 'Зайсан Голден Вилл' });
replaySuite({ name: 'R1 en with English voices (en-GB listed first)', label: 'R1', lang: 'en', voices: ['en-GB', 'en-US'], golden: ['G1', 'en'], destination: 'Зайсан Голден Вилл' });
replaySuite({ name: 'R1 mn on an iPhone-like voice list (English only): chime fallback', label: 'R1', lang: 'mn', voices: ['en-US', 'en-GB'], golden: ['G1', 'mn'], expectChime: true, destination: 'Зайсан Голден Вилл' });
replaySuite({ name: 'R2 mn walk with a Mongolian voice', label: 'R2', lang: 'mn', voices: ['mn-MN'], golden: ['G5', 'mn'], destination: 'Хаан банк' });
replaySuite({ name: 'R3 mn with a Mongolian voice (sentinel copy of the R3 response)', label: 'R3', lang: 'mn', voices: ['mn-MN'], golden: ['G8', 'mn'], sentinel: true, destination: 'Золтамир' });
replaySuite({ name: 'R3 en with an English voice', label: 'R3', lang: 'en', voices: ['en-US'], golden: ['G8', 'en'], destination: 'Золтамир' });
replaySuite({ name: 'G4 (test-only: last 500 m of R1, then stationary 10 m before the end for 30 s)', label: 'R1', lang: 'mn', voices: ['mn-MN'], golden: null, track: readGpx(G4.track), destination: 'Зайсан Голден Вилл' });

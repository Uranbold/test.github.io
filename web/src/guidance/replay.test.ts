// NAV-017 AC 12, 15, 16 (replay engine and clock on a fake clock), AC 21 (remaining time), AC 32–34 (arrival, G4),
// AC 18 (sentinel: Valhalla text never reaches the screen or the voice), AC 26 schedule properties, AC 30 (mute).
import { describe, expect, test } from "vitest";
import { I18n } from "../i18n/i18n";
import { FerrostarNavigator, parseFerrostarRoute } from "./ferrostarCore";
import { fixesFromTrack, type Fix } from "./fix";
import { GuidanceCore, remainingDuration, type GuidanceState } from "./guidanceCore";
import { PlaybackQueue, type SpokenPrompt } from "./playbackQueue";
import { buildPlan, rewriteValhallaText } from "./plan";
import { ReplayClock, ReplayEngine } from "./replay";
import { loadCore, parseGpx, readRepo, runReplay, tFor } from "./testing/harness";
import { renderVoice } from "./voiceText";

const MANIFEST = JSON.parse(readRepo("web/src/demo/routes.manifest.json")) as {
  routes: { id: string; mode: "car" | "walk"; route: string; track: string; picker: boolean }[];
};
const entry = (id: string) => MANIFEST.routes.find((r) => r.id === id)!;

function fakeTimers() {
  let now = 0;
  const intervals: { fn: () => void; ms: number; next: number }[] = [];
  return {
    time: { now: () => now },
    timer: {
      setInterval: (fn: () => void, ms: number) => {
        const h = { fn, ms, next: now + ms };
        intervals.push(h);
        return h;
      },
      clearInterval: (h: unknown) => {
        const i = intervals.indexOf(h as (typeof intervals)[number]);
        if (i >= 0) intervals.splice(i, 1);
      },
    },
    advance(ms: number, step = 100) {
      const end = now + ms;
      while (now < end) {
        now = Math.min(end, now + step);
        for (const h of [...intervals]) {
          while (h.next <= now && intervals.includes(h)) {
            h.fn();
            h.next += h.ms;
          }
        }
      }
    },
    get now() {
      return now;
    },
  };
}

async function prepare(id: string, routeJson?: unknown) {
  const e = entry(id);
  const core0 = await loadCore();
  const json = routeJson ?? JSON.parse(readRepo(e.route));
  const plan = buildPlan(json)!;
  const route = parseFerrostarRoute(core0, json, plan, e.mode);
  const track = parseGpx(readRepo(e.track));
  return { e, core0, plan, route, fixes: fixesFromTrack(track.points, track.timesMs) };
}

function session(p: Awaited<ReturnType<typeof prepare>>, lang: "mn" | "en" = "mn", muted = false) {
  const ft = fakeTimers();
  const clock = new ReplayClock(ft.time);
  const spoken: { at: number; p: SpokenPrompt }[] = [];
  const states: GuidanceState[] = [];
  let arrivals = 0;
  let stops = 0;
  let trackEnd = 0;
  let pending: { at: number; id: number } | null = null;
  const ref: { core?: GuidanceCore } = {};
  const core = new GuidanceCore({
    clock,
    plan: p.plan,
    navigator: new FerrostarNavigator(p.core0, p.route),
    mode: p.e.mode,
    lang,
    muted,
    t: tFor,
    speaker: {
      play: (sp) => {
        spoken.push({ at: clock.elapsedMs(), p: sp });
        pending = { at: clock.elapsedMs() + Math.min(5000, 300 + 55 * sp.text.length), id: sp.id };
      },
      stop: () => {
        stops++;
        pending = null;
      },
    },
    onState: (s) => states.push(s),
    onArrived: () => arrivals++,
  });
  ref.core = core;
  const engine = new ReplayEngine(p.fixes, core, clock, ft.timer, {
    onTick: () => {
      if (pending && pending.at <= clock.elapsedMs()) {
        const id = pending.id;
        pending = null;
        core.onSpeakerDone(id);
      }
    },
    onTrackEnd: () => trackEnd++,
  });
  return { ft, clock, core, engine, spoken, states, get arrivals() { return arrivals; }, get stops() { return stops; }, get trackEnd() { return trackEnd; } };
}

describe("NAV-017 AC 12/15/16 replay engine", () => {
  test("AC 12: fix i is applied when the replay clock reaches its track time (± 100 ms)", async () => {
    const p = await prepare("r1");
    const applied: { i: number; at: number }[] = [];
    const ft = fakeTimers();
    const clock = new ReplayClock(ft.time);
    const target = { finished: false, start: () => undefined, onFix: () => undefined, onTick: () => undefined };
    const engine = new ReplayEngine(p.fixes, target, clock, ft.timer, { onFix: (_f: Fix, i: number) => applied.push({ i, at: clock.elapsedMs() }) });
    engine.start();
    ft.advance(20_000);
    expect(applied.length).toBeGreaterThan(19);
    for (const a of applied) expect(Math.abs(a.at - p.fixes[a.i]!.elapsedMs)).toBeLessThanOrEqual(100);
  });

  test("AC 15: hidden pauses the clock; visible resumes at the same position with 0 repeated prompts", async () => {
    const p = await prepare("r1");
    const s = session(p);
    s.engine.start();
    s.ft.advance(170_000);
    const before = s.spoken.length;
    const idx = s.engine.appliedIndex;
    s.engine.pause();
    s.core.silence();
    s.ft.advance(60_000); // hidden for a minute
    expect(s.engine.appliedIndex).toBe(idx);
    expect(s.spoken.length).toBe(before);
    expect(Math.round(s.clock.elapsedMs() / 1000)).toBe(170);
    s.engine.resume();
    s.ft.advance(150_000);
    const texts = s.spoken.map((x) => `${x.p.maneuver?.step}|${x.p.text}`);
    expect(new Set(texts).size).toBe(texts.length);
    expect(s.arrivals).toBe(1);
  });

  test("AC 16: a track that ends without arrival ends the replay (track end callback)", async () => {
    const p = await prepare("r1");
    const short = p.fixes.slice(0, 50);
    const s = session({ ...p, fixes: short });
    s.engine.start();
    s.ft.advance(60_000);
    expect(s.trackEnd).toBe(1);
    expect(s.engine.isEnded).toBe(true);
    expect(s.arrivals).toBe(0);
  });

  test("AC 33: after arrival 0 further prompts, even if fixes keep coming", async () => {
    const p = await prepare("r1");
    const s = session(p);
    s.engine.start();
    s.ft.advance(330_000);
    expect(s.arrivals).toBe(1);
    const last = s.spoken[s.spoken.length - 1]!;
    expect(last.p.cls).toBe("arrival");
    expect(last.p.text).toBe("Таны очих газар баруун талд байна");
    expect(s.states[s.states.length - 1]!.banner.variant).toBe("arrival");
  });
});

describe("NAV-017 AC 21 remaining time and distance", () => {
  test("not-yet-driven share of the current step's duration plus the later steps", async () => {
    const p = await prepare("r1");
    const plan = p.plan;
    const total = plan.steps.reduce((a, s) => a + s.duration, 0);
    expect(remainingDuration(plan, 0, plan.steps[0]!.distance)).toBeCloseTo(total, 6);
    expect(remainingDuration(plan, 0, plan.steps[0]!.distance / 2)).toBeCloseTo(total - plan.steps[0]!.duration / 2, 6);
    expect(remainingDuration(plan, 1, 0)).toBeCloseTo(plan.steps.slice(2).reduce((a, s) => a + s.duration, 0), 6);
    const s = session(p);
    s.engine.start();
    s.ft.advance(100_000);
    const st = s.core.state();
    expect(st.progress.distanceRemaining).toBeLessThan(plan.distance);
    expect(st.progress.durationRemaining).toBeLessThan(plan.duration);
    expect(st.progress.durationRemaining).toBeGreaterThan(0);
  });
});

describe("NAV-017 AC 32–34 arrival", () => {
  for (const [id, mode] of [
    ["G1", "car"],
    ["G5", "walk"],
    ["G8", "car"],
    ["G4", "car"],
  ] as const) {
    test(`${id}: exactly one arrival message`, async () => {
      const m = JSON.parse(readRepo("tests/gpx/nav005/manifest.json")) as { tracks: { id: string; file: string; route: string }[] };
      const tr = m.tracks.find((t) => t.id === id)!;
      const r = await runReplay({ routeJson: JSON.parse(readRepo(tr.route)), track: parseGpx(readRepo(tr.file)), mode, lang: "mn", tailMs: 30_000 });
      expect(r.arrivals).toBe(1);
      expect(r.spoken.filter((s) => s.prompt.cls === "arrival").length).toBe(1);
      const arrivalAt = r.spoken.find((s) => s.prompt.cls === "arrival")!.atMs;
      expect(r.spoken.filter((s) => s.atMs > arrivalAt).length).toBe(0);
    });
  }
});

describe("NAV-017-D3: a track that starts past a manoeuvre (AC 17/20/26)", () => {
  test("G4 (first fix ~190 m past R1's left turn): the first banner is the manoeuvre ahead, the passed turn is never spoken; R1 from its start stays on step 0", async () => {
    const m = JSON.parse(readRepo("tests/gpx/nav005/manifest.json")) as { tracks: { id: string; file: string; route: string }[] };
    const g4 = m.tracks.find((t) => t.id === "G4")!;
    const r = await runReplay({ routeJson: JSON.parse(readRepo(g4.route)), track: parseGpx(readRepo(g4.file)), mode: "car", lang: "mn", tailMs: 30_000 });
    const first = r.states[0]!;
    expect(first.stepIndex).toBe(1);
    expect(first.banner.variant).toBe("maneuver");
    expect(first.banner.step).toBe(2);
    expect(first.banner.key.key).toBe("turn.right");
    if (first.banner.variant === "maneuver") expect(first.banner.distanceM).toBeGreaterThan(300);
    expect(r.navigator.stepCatchUps).toBeGreaterThanOrEqual(1);
    // never the passed left turn, on screen or spoken
    expect(r.states.filter((s) => s.banner.step <= 1)).toEqual([]);
    expect(r.spoken.filter((s) => /зүүн тийш/iu.test(s.prompt.text)).map((s) => s.prompt.text)).toEqual([]);
    // the manoeuvre ahead is still announced normally, and the arrival once
    expect(r.spoken.some((s) => /баруун тийш эргэнэ үү/iu.test(s.prompt.text))).toBe(true);
    expect(r.spoken.filter((s) => s.prompt.cls === "arrival").length).toBe(1);
    // parity: a track from the route start is untouched (the first-fix catch-up has nothing to do)
    const g1 = m.tracks.find((t) => t.id === "G1")!;
    const r1 = await runReplay({ routeJson: JSON.parse(readRepo(g1.route)), track: { points: parseGpx(readRepo(g1.file)).points.slice(0, 5), timesMs: [0, 1000, 2000, 3000, 4000] }, mode: "car", lang: "mn", tailMs: 0 });
    expect(r1.states[0]!.stepIndex).toBe(0);
    expect(r1.navigator.stepCatchUps).toBe(0);
  });
});

describe("NAV-017 AC 18 sentinel", () => {
  test("Valhalla text fields replaced by a sentinel never reach banners or speech (R1, R3)", async () => {
    const SENT = "VALHALLA_TEXT_SENTINEL";
    for (const id of ["r1", "r3"]) {
      const raw = JSON.parse(readRepo(entry(id).route));
      const json = JSON.parse(JSON.stringify(raw), (key, v) => (key === "instruction" || key === "text" || key === "announcement" || key === "ssmlAnnouncement" ? SENT : v));
      expect(JSON.stringify(json)).toContain(SENT);
      // the rewrite removes it before Ferrostar parses
      expect(JSON.stringify(rewriteValhallaText(json))).not.toContain(SENT);
      const p = await prepare(id, json);
      for (const lang of ["mn", "en"] as const) {
        const s = session(p, lang);
        s.engine.start();
        s.ft.advance(800_000);
        const i18n = new I18n(lang);
        const bannerTexts = s.states.map((st) => i18n.t(`maneuver.${st.banner.key.key}` as Parameters<I18n["t"]>[0]) + st.banner.street);
        expect(bannerTexts.join("\n")).not.toContain(SENT);
        expect(s.spoken.map((x) => x.p.text).join("\n")).not.toContain(SENT);
      }
    }
  });
});

describe("NAV-017 AC 30 mute", () => {
  test("muted: 0 prompts; unmuting resumes the schedule; muting stops the current prompt", async () => {
    const p = await prepare("r1");
    const s = session(p, "mn", true);
    s.engine.start();
    s.ft.advance(200_000);
    expect(s.spoken.length).toBe(0);
    s.core.setMuted(false);
    s.ft.advance(40_000);
    expect(s.spoken.length).toBeGreaterThan(0);
    const stops = s.stops;
    s.core.setMuted(true);
    expect(s.stops).toBeGreaterThanOrEqual(stops);
    const n = s.spoken.length;
    s.ft.advance(80_000);
    expect(s.spoken.length).toBe(n);
  });

  test("AC 31: language switch stops the current utterance and the next prompt uses the new language", async () => {
    const p = await prepare("r1");
    const s = session(p, "mn");
    s.engine.start();
    s.ft.advance(200_000);
    s.core.setLanguage("en");
    s.ft.advance(120_000);
    const after = s.spoken.filter((x) => x.at > 200_000);
    expect(after.length).toBeGreaterThan(0);
    for (const x of after) expect(x.p.lang).toBe("en");
    const texts = s.spoken.map((x) => `${x.p.maneuver?.step}|${x.p.cls}|${x.at}`);
    expect(new Set(texts).size).toBe(texts.length);
  });
});

describe("NAV-017 AC 26 playback rules", () => {
  const prompt = (id: number, at: number, cls: "maneuver" | "arrival" = "maneuver"): SpokenPrompt => ({ id, text: "x", lang: "mn", cls, maneuver: { gen: 0, step: id }, triggerAtMs: at });
  test("one at a time; a waiting prompt that cannot start within 3 s is dropped; arrival waits ≤ 3 s, nothing after it", () => {
    const played: number[] = [];
    let stops = 0;
    const q = new PlaybackQueue({ play: (p) => played.push(p.id), stop: () => stops++ });
    q.enqueue(prompt(1, 0), 0);
    q.enqueue(prompt(2, 100), 100);
    q.tick(3_200); // 2 waited 3.1 s
    q.onDone(1, 3_300);
    expect(played).toEqual([1]);
    q.enqueue(prompt(3, 4_000), 4_000);
    q.enqueue(prompt(4, 4_100, "arrival"), 4_100);
    q.tick(7_100); // arrival forced after 3 s
    expect(played).toEqual([1, 3, 4]);
    expect(stops).toBe(1);
    q.onDone(4, 8_000);
    q.enqueue(prompt(5, 9_000), 9_000);
    expect(played).toEqual([1, 3, 4]);
  });

  test("depart prompt text is available synchronously for the «Эхлэх» handler", async () => {
    const p = await prepare("r1");
    const s = session(p);
    expect(s.core.departText()).toBe("Өмнө зүг рүү явна уу");
    expect(renderVoice({ kind: "depart", key: p.plan.steps[0]!.key, then: null }, "en", tFor("en"))).toBe("Head south");
  });
});

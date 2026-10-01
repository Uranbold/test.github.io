// Test-only replay harness (Vitest, Node): drives GuidanceCore exactly like the Android QA harness
// (mobile/android/app/src/test/java/mn/navmn/app/support/Replay.kt › Replay.run) on a virtual clock: the first fix
// starts guidance, then every 100 ms the speaker completions, the due fixes and (every 500 ms) the ticker, with the
// speech-duration model min(5,000, 300 + 55 × text length) ms (ADR-0011 W8). Never bundled into the app.
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { I18n, type Lang } from "../../i18n/i18n";
import { FerrostarNavigator, initFerrostar, parseFerrostarRoute, type FerrostarBindings } from "../ferrostarCore";
import { fixesFromTrack, type Fix } from "../fix";
import type { LatLon } from "../geo";
import { GuidanceCore, type GuidanceState } from "../guidanceCore";
import type { SpokenPrompt } from "../playbackQueue";
import { buildPlan } from "../plan";

export const REPO = fileURLToPath(new URL("../../../../", import.meta.url));
export const readRepo = (p: string): string => readFileSync(REPO + p, "utf8");

const require = createRequire(import.meta.url);
export function loadCore(): Promise<FerrostarBindings> {
  const wasm = require.resolve("@stadiamaps/ferrostar/ferrostar_bg.wasm");
  return initFerrostar(async () => readFileSync(wasm));
}

export interface Track {
  points: LatLon[];
  timesMs: number[];
}

/** GPX 1.1 track points with their <time> (relative to the first point). */
export function parseGpx(xml: string): Track {
  const re = /<trkpt[^>]*lat="([-0-9.]+)"[^>]*lon="([-0-9.]+)"[^>]*>([\s\S]*?)<\/trkpt>/g;
  const points: LatLon[] = [];
  const abs: number[] = [];
  for (const m of xml.matchAll(re)) {
    points.push({ lat: Number(m[1]), lon: Number(m[2]) });
    const t = /<time>([^<]+)<\/time>/.exec(m[3] ?? "");
    abs.push(t ? Date.parse(t[1]!) : NaN);
  }
  const t0 = abs[0] ?? 0;
  return { points, timesMs: abs.map((t, i) => (Number.isFinite(t) ? t - t0 : i * 1000)) };
}

export interface RunResult {
  spoken: { atMs: number; prompt: SpokenPrompt }[];
  states: GuidanceState[];
  arrivals: number;
  core: GuidanceCore;
  navigator: FerrostarNavigator;
}

export const tFor = (lang: Lang) => {
  const i18n = new I18n(lang);
  return (k: Parameters<I18n["t"]>[0]) => i18n.t(k, lang);
};

export async function runReplay(opts: {
  routeJson: unknown;
  track: Track;
  mode: "car" | "walk";
  lang: Lang;
  tailMs?: number;
  muted?: boolean;
  fixes?: Fix[];
}): Promise<RunResult> {
  const core0 = await loadCore();
  const plan = buildPlan(opts.routeJson);
  if (!plan) throw new Error("fixture does not parse");
  const route = parseFerrostarRoute(core0, opts.routeJson, plan, opts.mode);
  const navigator = new FerrostarNavigator(core0, route);
  let now = 0;
  const clock = { elapsedMs: () => now };
  const spoken: RunResult["spoken"] = [];
  const pendingDone: { at: number; id: number }[] = [];
  const states: GuidanceState[] = [];
  let arrivals = 0;
  const core = new GuidanceCore({
    clock,
    plan,
    navigator,
    mode: opts.mode,
    lang: opts.lang,
    muted: opts.muted ?? false,
    t: tFor,
    speaker: {
      play: (p) => {
        spoken.push({ atMs: now, prompt: p });
        pendingDone.push({ at: now + Math.min(5_000, 300 + p.text.length * 55), id: p.id });
      },
      stop: () => {
        pendingDone.length = 0;
      },
    },
    onState: (s) => states.push(s),
    onArrived: () => arrivals++,
  });
  const fixes = opts.fixes ?? fixesFromTrack(opts.track.points, opts.track.timesMs);
  now = fixes[0]!.elapsedMs;
  core.start(fixes[0]!);
  const end = fixes[fixes.length - 1]!.elapsedMs + (opts.tailMs ?? 10_000);
  let i = 1;
  for (let t = now; t <= end; t += 100) {
    now = t;
    const due = pendingDone.filter((d) => d.at <= t);
    for (const d of due) core.onSpeakerDone(d.id);
    for (let j = pendingDone.length - 1; j >= 0; j--) if (pendingDone[j]!.at <= t) pendingDone.splice(j, 1);
    while (i < fixes.length && fixes[i]!.elapsedMs <= t) core.onFix(fixes[i++]!);
    if (t % 500 === 0) core.onTick();
  }
  return { spoken, states, arrivals, core, navigator };
}

export interface GoldenRow {
  track: string;
  lang: string;
  tS: number;
  maneuver: string;
  text: string;
}

export function goldenRows(): GoldenRow[] {
  return readRepo("tests/gpx/nav005/golden/voice-golden.tsv")
    .split("\n")
    .filter((l) => l && !l.startsWith("#"))
    .map((l) => {
      const [track, lang, t, maneuver, text] = l.split("\t");
      return { track: track!, lang: lang!, tS: Number(t), maneuver: maneuver!, text: text! };
    });
}

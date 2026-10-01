// NAV-017 AC 26 cross-platform parity (ADR-0011 §6 "Parity gate"): G1 (R1), G5 (R2) and G8 (R3) run through the real
// Ferrostar 0.57.0 WASM core and the TypeScript ports with the Android harness's fix model and speech-duration model.
// The (manoeuvre, text) sequence must equal tests/gpx/nav005/golden/voice-golden.tsv exactly, each prompt within ± 2 s.
import { describe, expect, test } from "vitest";
import type { Lang } from "../i18n/i18n";
import { goldenRows, parseGpx, readRepo, runReplay } from "./testing/harness";

const MANIFEST = JSON.parse(readRepo("tests/gpx/nav005/manifest.json")) as {
  tracks: { id: string; file: string; route: string; mode: "car" | "walk"; also_en?: string }[];
};
const track = (id: string) => MANIFEST.tracks.find((t) => t.id === id)!;

describe("NAV-017 AC 26 golden parity", () => {
  const cases: [string, Lang, "route" | "also_en"][] = [
    ["G1", "mn", "route"],
    ["G1", "en", "also_en"],
    ["G5", "mn", "route"],
    ["G8", "mn", "route"],
    ["G8", "en", "also_en"],
  ];
  for (const [id, lang, src] of cases) {
    test(`${id} ${lang}`, async () => {
      const tr = track(id);
      const routeFile = src === "also_en" ? tr.also_en! : tr.route;
      const r = await runReplay({ routeJson: JSON.parse(readRepo(routeFile)), track: parseGpx(readRepo(tr.file)), mode: tr.mode, lang });
      const got = r.spoken.map((s) => ({ t: s.atMs / 1000, m: String(s.prompt.maneuver?.step ?? "-"), text: s.prompt.text }));
      const want = goldenRows().filter((g) => g.track === id && g.lang === lang);
      expect(got.map((g) => `${g.m}\t${g.text}`)).toEqual(want.map((w) => `${w.maneuver}\t${w.text}`));
      for (let i = 0; i < want.length; i++) expect(Math.abs(got[i]!.t - want[i]!.tS), `row ${i} «${want[i]!.text}»`).toBeLessThanOrEqual(2);
      expect(r.arrivals).toBe(1);
    });
  }
});

// Ports of the Android ADR-0009 app rules (ADR-0011 §6): arrival detector (§5 a/b/c with gating), step catch-up gate
// (two good fixes ≤ 3 s), geo helpers, and the Valhalla text rewrite.
import { describe, expect, test } from "vitest";
import { ArrivalDetector } from "./arrivalDetector";
import { distance, distanceToLine, nearestIsLast, type LatLon } from "./geo";
import { rewriteValhallaText } from "./plan";
import { CatchUpGate, StepCatchUp } from "./stepCatchUp";

const P = (lat: number, lon: number): LatLon => ({ lat, lon });
const end = P(47.9, 106.9);
const base = { complete: false, offRoute: false, goodFix: true, trustedFix: true, distanceRemaining: 500, position: P(47.91, 106.9), routeEnd: end, stepIndex: 0, lastStepIndex: 3 };

describe("ArrivalDetector (ADR-0009 §5, Amendments 1/3)", () => {
  test("(a) complete, (b) trusted good fix ≤ 30 m remaining, (c) ≤ 30 m from the end only on the last leg; fires once", () => {
    expect(new ArrivalDetector().check({ ...base, complete: true })).toBe(true);
    expect(new ArrivalDetector().check({ ...base, distanceRemaining: 25 })).toBe(true);
    expect(new ArrivalDetector().check({ ...base, distanceRemaining: 25, trustedFix: false })).toBe(false);
    const near = P(47.9, 106.9001); // ~7.5 m from the end
    expect(new ArrivalDetector().check({ ...base, position: near, stepIndex: 0 })).toBe(false);
    const d = new ArrivalDetector();
    expect(d.check({ ...base, position: near, stepIndex: 2 })).toBe(true);
    expect(d.check({ ...base, complete: true })).toBe(false);
  });
});

describe("StepCatchUp gate (NAV-005-D10)", () => {
  test("two consecutive good fixes on the same target within 3 s confirm; a poor fix neither confirms nor resets", () => {
    const g = new CatchUpGate();
    expect(g.decide(2, true, 0)).toBe(false);
    expect(g.decide(2, false, 500)).toBe(false);
    expect(g.decide(2, true, 1000)).toBe(true);
    expect(g.decide(3, true, 2000)).toBe(false);
    expect(g.decide(3, true, 5500)).toBe(false); // too late: restarts
    expect(g.decide(3, true, 6000)).toBe(true);
    expect(g.decide(4, true, 7000)).toBe(false);
    expect(g.decide(null, true, 7500)).toBe(false); // back on the current step: reset
    expect(g.decide(4, true, 8000)).toBe(false);
  });

  test("lateral branch (a): off the current step by > 50 m and within 50 m of a later step", () => {
    const s0 = [P(47.9, 106.9), P(47.9, 106.91)];
    const s1 = [P(47.9, 106.91), P(47.91, 106.91)];
    const s2 = [P(47.91, 106.91), P(47.92, 106.91)];
    const arrive = [P(47.92, 106.91)];
    const steps = [s0, s1, s2, arrive];
    const remaining = { length: steps.length, get: (i: number) => steps[i]! };
    expect(StepCatchUp.stepsToAdvance(P(47.905, 106.9101), 5, remaining)).toBe(1);
    expect(StepCatchUp.stepsToAdvance(P(47.905, 106.9101), 40, remaining)).toBe(0); // poor accuracy
    expect(StepCatchUp.stepsToAdvance(P(47.9001, 106.905), 5, remaining)).toBe(0); // on the current step
  });
});

describe("geo (Geo.kt port)", () => {
  test("distance, distance to a line, nearest-is-last", () => {
    expect(distance(P(47.9, 106.9), P(47.9, 106.9)).valueOf()).toBe(0);
    expect(distance(P(47.9, 106.9), P(47.901, 106.9))).toBeCloseTo(111.2, 0);
    const line = [P(47.9, 106.9), P(47.9, 106.91)];
    expect(distanceToLine(P(47.9009, 106.905), line)).toBeCloseTo(100.1, 0);
    expect(nearestIsLast(P(47.9, 106.92), line)).toBe(true);
    expect(nearestIsLast(P(47.9, 106.905), line)).toBe(false);
  });
});

describe("ADR-0009 §3.1 text rewrite", () => {
  test("instructions and banner texts become tokens, voiceInstructions empty, warnings removed, input untouched", () => {
    const input = {
      code: "Ok",
      warnings: ["x"],
      routes: [
        {
          legs: [
            { steps: [{ maneuver: { instruction: "Valhalla" }, bannerInstructions: [{ primary: { text: "A", components: [{ text: "B" }] } }], voiceInstructions: [{ announcement: "C" }] }] },
            { steps: [{ maneuver: { instruction: "D" } }] },
          ],
        },
      ],
    };
    const out = rewriteValhallaText(input) as { routes: { legs: { steps: { maneuver: { instruction: string }; voiceInstructions?: unknown[] }[] }[] }[] };
    expect(JSON.stringify(out)).not.toMatch(/Valhalla|"A"|"B"|"C"|"D"/);
    expect(out.routes[0]!.legs[0]!.steps[0]!.maneuver.instruction).toBe("nav:0:m:0");
    expect(out.routes[0]!.legs[1]!.steps[0]!.maneuver.instruction).toBe("nav:0:m:1");
    expect(out.routes[0]!.legs[0]!.steps[0]!.voiceInstructions).toEqual([]);
    expect("warnings" in (out as object)).toBe(false);
    expect(input.warnings).toEqual(["x"]);
  });
});

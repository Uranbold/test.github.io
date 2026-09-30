// motionMs reads motion durations from tokens.json (NAV-004 D1: route camera uses motion.route-camera).
import { describe, expect, it } from "vitest";
import tokens from "@design/tokens.json";
import { motionMs } from "./tokens";

describe("motionMs", () => {
  it("returns the route camera duration from tokens.json, shorter than the 1 s budget of AC 17/30", () => {
    const raw = (tokens.motion as unknown as Record<string, { $value: string } | undefined>)["route-camera"]?.$value ?? "";
    expect(motionMs("route-camera")).toBe(Number(raw.replace(/ms$/, "")));
    expect(motionMs("route-camera")).toBeLessThan(1000);
  });

  it("leaves the shared NAV-002/NAV-003 camera token as it is", () => {
    expect(motionMs("duration-camera")).toBe(1000);
  });

  it("throws for a missing or non-duration token", () => {
    expect(() => motionMs("no-such-token")).toThrow(/motion\.no-such-token/);
    expect(() => motionMs("easing-standard")).toThrow(/not a millisecond duration/);
  });
});

import { describe, expect, it } from "vitest";
import { circlePolygon } from "./circle";
import { distanceMeters } from "./scale";

describe("accuracy circle (AC 19: radius ± 10 %)", () => {
  it.each([[106.9176, 47.9189, 30], [116.4074, 39.9042, 1500], [0, 0, 5]])("at %f,%f radius %f m", (lng, lat, r) => {
    const ring = circlePolygon(lng, lat, r).coordinates[0]!;
    expect(ring.length).toBe(65);
    expect(ring[0]).toEqual(ring[64]);
    for (const [x, y] of ring) expect(Math.abs(distanceMeters({ lng, lat }, { lng: x!, lat: y! }) - r) / r).toBeLessThan(0.001);
  });
});

import { describe, expect, it } from "vitest";
import { computeScaleBar, distanceMeters, roundScaleValue } from "./scale";

// MapLibre web-mercator ground resolution with 512 px tiles.
const mpp = (lat: number, zoom: number) => (40075016.686 * Math.cos((lat * Math.PI) / 180)) / (512 * 2 ** zoom);

describe("scale bar (AC 16–17)", () => {
  it("rounds to 1-2-3-5 × 10ⁿ", () => {
    expect([1, 1.9, 2, 2.9, 3, 4.9, 5, 9.99, 12, 250, 999, 1280].map(roundScaleValue)).toEqual([1, 1, 2, 2, 3, 3, 5, 5, 10, 200, 500, 1000]);
  });

  it.each([12, 15, 18])("is within ±5 %% of the true ground distance at P1 z%i", (z) => {
    const m = mpp(47.9189, z);
    const s = computeScaleBar(m, 100)!;
    expect(s.widthPx).toBeLessThanOrEqual(100);
    const trueDistance = s.widthPx * m;
    expect(Math.abs(s.meters - trueDistance) / trueDistance).toBeLessThan(0.05);
    const labelled = s.unit === "km" ? s.value * 1000 : s.value;
    expect(labelled).toBe(s.meters);
  });

  it("switches from m to km at 1 km, metric only", () => {
    expect(computeScaleBar(5, 100)).toMatchObject({ unit: "m", value: 500, widthPx: 100 });
    expect(computeScaleBar(4, 100)).toMatchObject({ unit: "m", value: 300, widthPx: 75 });
    expect(computeScaleBar(12.8, 100)).toMatchObject({ unit: "km", value: 1 });
    expect(computeScaleBar(mpp(47.9, 3), 100)).toMatchObject({ unit: "km" });
    expect(computeScaleBar(0, 100)).toBeNull();
  });

  it("measures distance like MapLibre (haversine, R = 6371008.8 m)", () => {
    // 0.0018° of latitude ≈ 200 m (AC 20 fixture).
    expect(distanceMeters({ lng: 106.9176, lat: 47.9189 }, { lng: 106.9176, lat: 47.9207 })).toBeCloseTo(200.15, 1);
  });
});

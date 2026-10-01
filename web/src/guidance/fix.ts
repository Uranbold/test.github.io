// One simulated location update (NAV-017 "simulated fix"), shaped like the Android location/Fix.kt so the ported rules
// read it the same way. `elapsedMs` is the replay-clock time used by every timing rule; `wallTimeMs` only feeds
// Ferrostar's timestamp.
import { bearing, distance, type LatLon } from "./geo";

export interface Fix {
  lat: number;
  lon: number;
  /** Horizontal accuracy in metres. */
  accuracyM: number;
  bearingDeg: number | null;
  bearingAccuracyDeg: number | null;
  speedMps: number | null;
  elapsedMs: number;
  wallTimeMs: number;
}

/** Story "Terms" (NAV-005): good fix ≤ 25 m accuracy, fresh ≤ 10 s old. */
export const GOOD_ACCURACY_M = 25;
export const FRESH_MS = 10_000;
/** Accuracy of a simulated fix (as the Android replay harness, ADR-0011 W8). */
export const SIMULATED_ACCURACY_M = 5;
/** Base of `wallTimeMs` for simulated fixes (any fixed epoch works; it never reaches the UI). */
export const SIMULATED_WALL_BASE_MS = 1_790_000_000_000;

export const latLonOf = (f: Fix): LatLon => ({ lat: f.lat, lon: f.lon });

export function isGood(f: Fix, nowElapsedMs: number, maxAgeMs = FRESH_MS): boolean {
  return Number.isFinite(f.accuracyM) && f.accuracyM <= GOOD_ACCURACY_M && nowElapsedMs - f.elapsedMs <= maxAgeMs;
}

/**
 * Track points → fixes exactly as the Android harness builds them (support/Replay.kt › Tracks.fromPoints, ADR-0011 W8):
 * accuracy 5 m, course to the next point (from the previous point for the last) with accuracy 10°, speed = distance to
 * the next point per second, time = the point's track time. Looking ahead is legitimate in a replay and keeps the
 * prompt timing equal to the golden set.
 */
export function fixesFromTrack(points: readonly LatLon[], timesMs: readonly number[], accuracyM = SIMULATED_ACCURACY_M): Fix[] {
  return points.map((p, i) => {
    const next = points[i + 1];
    const prev = points[i - 1];
    let course: number | null = null;
    if (next && distance(p, next) > 0.5) course = bearing(p, next);
    else if (prev && distance(prev, p) > 0.5) course = bearing(prev, p);
    const t = timesMs[i] ?? i * 1000;
    const tNext = next ? (timesMs[i + 1] ?? (i + 1) * 1000) : t;
    const dt = Math.max(1, (tNext - t) / 1000);
    const speed = next ? distance(p, next) / dt : 0;
    return {
      lat: p.lat,
      lon: p.lon,
      accuracyM,
      bearingDeg: course,
      bearingAccuracyDeg: course !== null ? 10 : null,
      speedMps: speed,
      elapsedMs: t,
      wallTimeMs: SIMULATED_WALL_BASE_MS + t,
    };
  });
}

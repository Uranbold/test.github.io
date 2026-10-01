// Geodesy helpers for the guidance core (NAV-017, ADR-0011 §6). 1:1 port of the Android
// mobile/android/app/src/main/java/mn/navmn/app/geo/Geo.kt (same formulas, constants and tie-breaks), so the step
// catch-up and arrival rules give the same answers on both platforms. Pure: no DOM, no MapLibre.

export interface LatLon {
  lat: number;
  lon: number;
}

const EARTH_RADIUS_M = 6_371_008.8;
const rad = (deg: number): number => (deg * Math.PI) / 180;
const deg = (r: number): number => (r * 180) / Math.PI;
const clamp = (v: number, lo: number, hi: number): number => Math.min(hi, Math.max(lo, v));

/** Great-circle distance in metres. */
export function distance(a: LatLon, b: LatLon): number {
  const dLat = rad(b.lat - a.lat);
  const dLon = rad(b.lon - a.lon);
  const s1 = Math.sin(dLat / 2);
  const s2 = Math.sin(dLon / 2);
  const h = s1 * s1 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * s2 * s2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(clamp(h, 0, 1)));
}

/** Initial bearing from a to b, 0–360°. */
export function bearing(a: LatLon, b: LatLon): number {
  const p1 = rad(a.lat);
  const p2 = rad(b.lat);
  const dl = rad(b.lon - a.lon);
  const y = Math.sin(dl) * Math.cos(p2);
  const x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
  return (deg(Math.atan2(y, x)) + 360) % 360;
}

/** Length of a polyline in metres. */
export function length(line: readonly LatLon[]): number {
  let total = 0;
  for (let i = 0; i < line.length - 1; i++) total += distance(line[i]!, line[i + 1]!);
  return total;
}

/** Point at `distanceM` along a polyline (clamped to its ends). */
export function along(line: readonly LatLon[], distanceM: number): LatLon {
  if (line.length === 0) throw new Error("empty line");
  if (distanceM <= 0) return line[0]!;
  let rest = distanceM;
  for (let i = 0; i < line.length - 1; i++) {
    const a = line[i]!;
    const b = line[i + 1]!;
    const seg = distance(a, b);
    if (rest <= seg && seg > 0) {
      const f = rest / seg;
      return { lat: a.lat + (b.lat - a.lat) * f, lon: a.lon + (b.lon - a.lon) * f };
    }
    rest -= seg;
  }
  return line[line.length - 1]!;
}

interface Projection {
  /** Shortest distance from the point to the line (m). */
  distance: number;
  /** Along-line position of the nearest point (m, local projection). */
  along: number;
  /** Total line length (m, local projection). */
  total: number;
}

/**
 * Nearest point of `line` to `p` in a local equirectangular projection around `p` (accurate to well under 1 % at the
 * street scale this is used for). On a tie the later segment wins (Geo.kt `nearestIsLast`).
 */
function project(p: LatLon, line: readonly LatLon[]): Projection {
  const kx = EARTH_RADIUS_M * rad(1) * Math.cos(rad(p.lat));
  const ky = EARTH_RADIUS_M * rad(1);
  let best = Number.POSITIVE_INFINITY;
  let bestAlong = 0;
  let total = 0;
  for (let i = 0; i < line.length - 1; i++) {
    const a = line[i]!;
    const b = line[i + 1]!;
    const ax = (a.lon - p.lon) * kx;
    const ay = (a.lat - p.lat) * ky;
    const bx = (b.lon - p.lon) * kx;
    const by = (b.lat - p.lat) * ky;
    const dx = bx - ax;
    const dy = by - ay;
    const len2 = dx * dx + dy * dy;
    const len = Math.sqrt(len2);
    const t = len2 === 0 ? 0 : clamp(-(ax * dx + ay * dy) / len2, 0, 1);
    const cx = ax + t * dx;
    const cy = ay + t * dy;
    const d = Math.sqrt(cx * cx + cy * cy);
    if (d <= best) {
      best = d;
      bestAlong = total + t * len;
    }
    total += len;
  }
  return { distance: best, along: bestAlong, total };
}

/** Shortest distance in metres from `p` to the polyline `line`; +∞ for an empty line. */
export function distanceToLine(p: LatLon, line: readonly LatLon[]): number {
  if (line.length === 0) return Number.POSITIVE_INFINITY;
  if (line.length === 1) return distance(p, line[0]!);
  return project(p, line).distance;
}

/**
 * True when the nearest point of `line` to `p` is its last coordinate (the projection is clamped at the end).
 * `toleranceM` absorbs rounding for a point almost exactly perpendicular to the last segment at its end.
 */
export function nearestIsLast(p: LatLon, line: readonly LatLon[], toleranceM = 0.5): boolean {
  if (line.length === 0) return false;
  if (line.length === 1) return true;
  const pr = project(p, line);
  return pr.along >= pr.total - toleranceM;
}

/**
 * Along-route position (m, great-circle lengths) of the point of `line` nearest to `p`. Used by the puck glide only
 * (never by guidance decisions), so it may use its own measure.
 */
export function alongPosition(p: LatLon, line: readonly LatLon[]): number {
  if (line.length < 2) return 0;
  const kx = EARTH_RADIUS_M * rad(1) * Math.cos(rad(p.lat));
  const ky = EARTH_RADIUS_M * rad(1);
  let best = Number.POSITIVE_INFINITY;
  let bestAlong = 0;
  let total = 0;
  for (let i = 0; i < line.length - 1; i++) {
    const a = line[i]!;
    const b = line[i + 1]!;
    const ax = (a.lon - p.lon) * kx;
    const ay = (a.lat - p.lat) * ky;
    const bx = (b.lon - p.lon) * kx;
    const by = (b.lat - p.lat) * ky;
    const dx = bx - ax;
    const dy = by - ay;
    const len2 = dx * dx + dy * dy;
    const t = len2 === 0 ? 0 : clamp(-(ax * dx + ay * dy) / len2, 0, 1);
    const cx = ax + t * dx;
    const cy = ay + t * dy;
    const d = Math.sqrt(cx * cx + cy * cy);
    const seg = distance(a, b);
    if (d < best) {
      best = d;
      bestAlong = total + t * seg;
    }
    total += seg;
  }
  return bestAlong;
}

/** Polyline6 decoding is in src/route/polyline.ts ([lng, lat] pairs); this converts them. */
export function fromLngLat(coords: readonly (readonly [number, number])[]): LatLon[] {
  return coords.map(([lng, lat]) => ({ lat, lon: lng }));
}

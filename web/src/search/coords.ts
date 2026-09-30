// Coordinates in NAV-003 (AC 9, 21, 26, 27, 46; ADR-0006 §2.2, §2.6, §3, §4).

export interface LatLon {
  lat: number;
  lon: number;
}

const COORDINATE = /^(-?\d{1,2}(?:\.\d+)?)(?:\s*,\s*|\s+)(-?\d{1,3}(?:\.\d+)?)$/;

/**
 * A settled query that is a coordinate pair `<lat>, <lon>`, `<lat>,<lon>` or `<lat> <lon>` with point decimals,
 * lat in −90…90 and lon in −180…180 (AC 26). Anything else (decimal commas, swapped pairs out of range) is text.
 */
export function parseCoordinate(settled: string): LatLon | null {
  const m = COORDINATE.exec(settled);
  if (!m) return null;
  const lat = Number(m[1]);
  const lon = Number(m[2]);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
  return { lat, lon };
}

/** «lat, lon» with 5 decimals and a point decimal separator in both languages (AC 21). */
export function formatCoordinate(p: LatLon): string {
  return `${p.lat.toFixed(5)}, ${p.lon.toFixed(5)}`;
}

/** Search bias point: 3 decimals, about 100 m (AC 9, 46, privacy R10). */
export function biasParams(p: LatLon): { lat: string; lon: string } {
  return { lat: fixed(p.lat, 3), lon: fixed(p.lon, 3) };
}

/** Reverse point: 6 decimals, a point the user chose (AC 27, ADR-0006 §4). */
export function reverseParams(p: LatLon): { lat: string; lon: string } {
  return { lat: fixed(p.lat, 6), lon: fixed(p.lon, 6) };
}

/** toFixed without a "-0.000" for values that round to zero. */
function fixed(v: number, digits: number): string {
  const s = v.toFixed(digits);
  return /^-0\.?0*$/.test(s) ? s.slice(1) : s;
}

/** Wraps a longitude into −180…180 (the map renders world copies). */
export function wrapLon(lon: number): number {
  const w = ((((lon + 180) % 360) + 360) % 360) - 180;
  return w === -180 && lon > 0 ? 180 : w;
}

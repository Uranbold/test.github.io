// Metric scale bar maths (NAV-002 AC 16–17, screen spec › Scale bar).

export const EARTH_RADIUS_M = 6371008.8;

/** Great-circle distance in metres (haversine; same model as MapLibre LngLat.distanceTo). */
export function distanceMeters(a: { lng: number; lat: number }, b: { lng: number; lat: number }): number {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Largest "round" value (1, 2, 3 or 5 × 10ⁿ) that is ≤ the input. */
export function roundScaleValue(meters: number): number {
  if (!(meters > 0) || !Number.isFinite(meters)) return 0;
  const pow = 10 ** Math.floor(Math.log10(meters));
  const d = meters / pow;
  const step = d >= 5 ? 5 : d >= 3 ? 3 : d >= 2 ? 2 : 1;
  return step * pow;
}

export interface ScaleBar {
  /** Bar length in CSS px (≤ maxWidthPx). */
  widthPx: number;
  /** Labelled distance in metres (the bar length on the ground). */
  meters: number;
  /** Number shown in the label (metres below 1 km, kilometres from 1 km). */
  value: number;
  unit: "m" | "km";
}

/**
 * @param metersPerPixel ground distance of one CSS pixel at the map centre
 * @param maxWidthPx maximum bar length (tokens: size.scale-bar-max = 100 px)
 */
export function computeScaleBar(metersPerPixel: number, maxWidthPx = 100): ScaleBar | null {
  if (!(metersPerPixel > 0) || !Number.isFinite(metersPerPixel)) return null;
  const maxMeters = metersPerPixel * maxWidthPx;
  const meters = roundScaleValue(maxMeters);
  if (meters <= 0) return null;
  const widthPx = meters / metersPerPixel;
  const unit = meters >= 1000 ? "km" : "m";
  const value = unit === "km" ? meters / 1000 : meters;
  // Avoid float artefacts such as 0.30000000000000004 (only possible for sub-metre values).
  return { widthPx, meters, unit, value: Number(value.toPrecision(12)) };
}

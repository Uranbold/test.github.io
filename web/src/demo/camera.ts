// Follow camera of the replay (navigation-ux §8, §11.2; NAV-017 AC 22–23; screen spec › Camera rules). Heading up,
// pitch 45°, zoom by speed with a 5 km/h hysteresis, the puck at 70 % of the map area not covered by the UI and
// horizontally centred in it, each new position reached within 1 s with linear easing. Reduced motion: jumps.
import type { Map as MapLibreMap, PaddingOptions } from "maplibre-gl";
import type { LatLon } from "../guidance/geo";

export const FOLLOW_PITCH = 45;
export const FOLLOW_MS = 1000; // motion.nav-camera-follow
export const START_MS = 700; // motion.route-camera
export const RESET_MS = 300; // motion.duration-medium
export const UI_MARGIN = 16;
export const PUCK_AT = 0.7;

/** Speed bands (km/h) → zoom, navigation-ux §8. */
const BANDS: { min: number; zoom: number }[] = [
  { min: 80, zoom: 15 },
  { min: 50, zoom: 16 },
  { min: 20, zoom: 17 },
  { min: 0, zoom: 17.5 },
];
const HYSTERESIS_KMH = 5;

export class ZoomBySpeed {
  private zoom = 17.5;
  constructor(private readonly walk: boolean) {}

  update(speedMps: number): number {
    if (this.walk) return (this.zoom = 17.5);
    const kmh = speedMps * 3.6;
    const target = BANDS.find((b) => kmh >= b.min)!.zoom;
    if (target === this.zoom) return this.zoom;
    // Leave the current band only when the speed is 5 km/h past its edge.
    const cur = BANDS.find((b) => b.zoom === this.zoom)!;
    const idx = BANDS.indexOf(cur);
    const upper = idx > 0 ? BANDS[idx - 1]!.min : Number.POSITIVE_INFINITY;
    if (kmh >= upper + HYSTERESIS_KMH || kmh < cur.min - HYSTERESIS_KMH) this.zoom = target;
    return this.zoom;
  }
}

export interface Covered {
  top: number;
  bottom: number;
  left: number;
  right: number;
}

/** Padding that puts the camera centre at 70 % of the uncovered height (layout rule 4). */
export function followPadding(mapHeight: number, c: Covered): PaddingOptions {
  const top = c.top + UI_MARGIN;
  const bottomEdge = mapHeight - c.bottom - UI_MARGIN;
  const h = Math.max(1, bottomEdge - top);
  const nn = (v: number) => (Number.isFinite(v) ? Math.max(0, v) : 0);
  return { top: nn(top + (2 * PUCK_AT - 1) * h), bottom: nn(mapHeight - bottomEdge), left: nn(c.left + UI_MARGIN), right: nn(c.right + UI_MARGIN) };
}

export const FIT_MIN_BAND_PX = 120;

export interface Margins {
  top: number;
  bottom: number;
  left: number;
  right: number;
}

/**
 * AC 9 picker fit (screen spec › Camera rules): padding = the UI covering each edge + a margin inside the uncovered
 * band, while the band left for the route stays ≥ FIT_MIN_BAND_PX on each axis. When the band is smaller, the margins
 * shrink first (both edges in proportion); only when the UI itself leaves less than the minimum are the covered edges
 * scaled down. So a route end never lands under the picker sheet as long as the sheet leaves room for it (NAV-017-D1:
 * the earlier half-height clamp cut the bottom padding below the sheet on 375×667 and 390×844).
 */
export function fitPadding(mapWidth: number, mapHeight: number, c: Covered, m: Margins): PaddingOptions {
  const nn = (v: number) => (Number.isFinite(v) ? Math.max(0, v) : 0);
  const axis = (size: number, a: number, b: number, ma: number, mb: number): [number, number] => {
    const room = Math.max(0, size - FIT_MIN_BAND_PX);
    let ta = nn(a) + nn(ma);
    let tb = nn(b) + nn(mb);
    if (ta + tb > room) {
      const margins = nn(ma) + nn(mb);
      const cut = Math.min(ta + tb - room, margins);
      if (margins > 0) {
        ta -= (cut * nn(ma)) / margins;
        tb -= (cut * nn(mb)) / margins;
      }
      if (ta + tb > room) {
        const scale = ta + tb > 0 ? room / (ta + tb) : 0;
        ta *= scale;
        tb *= scale;
      }
    }
    return [nn(ta), nn(tb)];
  };
  const [top, bottom] = axis(mapHeight, c.top, c.bottom, m.top, m.bottom);
  const [left, right] = axis(mapWidth, c.left, c.right, m.left, m.right);
  return { top, bottom, left, right };
}

export function follow(map: MapLibreMap, target: LatLon, bearing: number, zoom: number, padding: PaddingOptions, ms: number, reduced: boolean): void {
  const opts = { center: [target.lon, target.lat] as [number, number], bearing, pitch: FOLLOW_PITCH, zoom, padding };
  if (reduced || ms <= 0) map.jumpTo(opts);
  // Linear, clamped: MapLibre may evaluate the first frame with k slightly below 0 (frame time before the call), and an
  // unclamped linear easing would then interpolate a negative padding, which MapLibre rejects.
  else map.easeTo({ ...opts, duration: ms, easing: (t: number) => Math.min(1, Math.max(0, t)), essential: true });
}

// Accuracy circle as a ground-true GeoJSON polygon (map-style.md §7, NAV-002 AC 19: radius ± 10 %).
import { EARTH_RADIUS_M } from "./scale";

/** Destination point from (lng, lat) after `distance` metres on `bearingDeg` (spherical Earth). */
export function destination(lng: number, lat: number, distance: number, bearingDeg: number): [number, number] {
  const rad = Math.PI / 180;
  const δ = distance / EARTH_RADIUS_M;
  const θ = bearingDeg * rad;
  const φ1 = lat * rad;
  const λ1 = lng * rad;
  const φ2 = Math.asin(Math.sin(φ1) * Math.cos(δ) + Math.cos(φ1) * Math.sin(δ) * Math.cos(θ));
  const λ2 = λ1 + Math.atan2(Math.sin(θ) * Math.sin(δ) * Math.cos(φ1), Math.cos(δ) - Math.sin(φ1) * Math.sin(φ2));
  return [(((λ2 / rad + 540) % 360) - 180), φ2 / rad];
}

export function circlePolygon(lng: number, lat: number, radiusM: number, vertices = 64): GeoJSON.Polygon {
  const ring: [number, number][] = [];
  for (let i = 0; i < vertices; i++) ring.push(destination(lng, lat, radiusM, (i * 360) / vertices));
  ring.push(ring[0] as [number, number]);
  return { type: "Polygon", coordinates: [ring] };
}

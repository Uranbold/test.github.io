// Merging the responses of one settled query (story AC 7, 8, 15; ADR-0006 §2.5).
import { featureKey, type PhotonFeature } from "./photon";

/** The results list shows at most 10 options (AC 7). */
export const MAX_OPTIONS = 10;
/** `limit` sent with every search request (AC 7: 5–10). */
export const SEARCH_LIMIT = 8;

/** Stable partition: every `countrycode == "MN"` result before every other result (AC 8). */
export function mnFirst(features: readonly PhotonFeature[]): PhotonFeature[] {
  const mn: PhotonFeature[] = [];
  const other: PhotonFeature[] = [];
  for (const f of features) (f.properties.countrycode?.toUpperCase() === "MN" ? mn : other).push(f);
  return [...mn, ...other];
}

function dedupe(features: readonly PhotonFeature[]): PhotonFeature[] {
  const seen = new Set<string>();
  const out: PhotonFeature[] = [];
  for (const f of features) {
    const k = featureKey(f);
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(f);
  }
  return out;
}

/** Interleaves p1, s1, p2, s2, … */
function interleave(a: readonly PhotonFeature[], b: readonly PhotonFeature[]): PhotonFeature[] {
  const out: PhotonFeature[] = [];
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if (i < a.length) out.push(a[i]!);
    if (i < b.length) out.push(b[i]!);
  }
  return out;
}

/**
 * Final options of a settled query. `secondary` is null for mode `none`; for `ifEmpty` pass the secondary response
 * as `primary` (it replaces the empty primary).
 */
export function mergeFeatures(primary: readonly PhotonFeature[], secondary: readonly PhotonFeature[] | null): PhotonFeature[] {
  const all = secondary === null ? [...primary] : interleave(primary, secondary);
  return mnFirst(dedupe(all)).slice(0, MAX_OPTIONS);
}

// NAV-011 AC 5 / ADR-0012 §2: the shared ADR-0006 vector fixture (queryPlan.vectors.json). The Android JVM test reads
// the same file (copied by Gradle), so the Kotlin port and these functions are checked against identical rows.
// Any change to the fixture keeps both suites green in the same commit.
import { describe, expect, it } from "vitest";
import { mergeFeatures } from "./merge";
import type { PhotonFeature } from "./photon";
import { planQuery } from "./queryPlan";
import { normalizeQuery } from "./text";
import { latinToCyrillic } from "./transliterate";
import vectors from "./queryPlan.vectors.json";

interface FixtureFeature {
  osm_type?: string;
  osm_id?: number;
  countrycode?: string;
}

function feature(p: FixtureFeature): PhotonFeature {
  return {
    type: "Feature",
    geometry: { type: "Point", coordinates: [106.9, 47.9] },
    properties: { ...p, osm_type: p.osm_type as "N" | "W" | "R" | undefined },
  };
}

const key = (f: PhotonFeature) => `${f.properties.osm_type}${f.properties.osm_id}`;

describe("shared query-assistance vectors (ADR-0012 §2)", () => {
  it("has version 1 and rows in every section", () => {
    expect(vectors.version).toBe(1);
    for (const section of [vectors.settle, vectors.plan, vectors.latinToCyrillic, vectors.merge]) expect(section.length).toBeGreaterThan(0);
  });

  it.each(vectors.settle.map((r) => [JSON.stringify(r.raw).slice(0, 40), r] as const))("settle %s", (_, r) => {
    expect(normalizeQuery(r.raw)).toBe(r.settled);
  });

  it.each(vectors.plan.map((r) => [JSON.stringify(r.input).slice(0, 40), r] as const))("plan %s", (_, r) => {
    const p = planQuery(r.input);
    expect(p.kind).toBe(r.kind);
    if (p.kind === "coordinate") expect(p.point).toEqual({ lat: (r as { lat: number }).lat, lon: (r as { lon: number }).lon });
    if (p.kind === "text") expect({ primary: p.primary, secondary: p.secondary, mode: p.mode }).toEqual({ primary: r.primary, secondary: r.secondary, mode: r.mode });
  });

  it.each(vectors.latinToCyrillic.map((r) => [r.latin, r] as const))("latinToCyrillic %s", (_, r) => {
    expect(latinToCyrillic(r.latin)).toBe(r.cyrillic);
  });

  it.each(vectors.merge.map((r) => [r.name, r] as const))("merge: %s", (_, r) => {
    const primary = r.primary.map(feature);
    const secondary = r.secondary === null ? null : r.secondary.map(feature);
    let out: PhotonFeature[];
    if (r.mode === "parallel") out = mergeFeatures(primary, secondary);
    else if (r.mode === "ifEmpty") out = mergeFeatures(primary.length === 0 ? (secondary ?? []) : primary, null);
    else out = mergeFeatures(primary, null);
    expect(out.map(key)).toEqual(r.expected);
  });
});

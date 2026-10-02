// Ferrostar core 0.57.0 (Rust, compiled to WASM) in the browser (ADR-0011 §6). Loaded by hand (no Vite WASM plugin):
// bytes → WebAssembly.instantiate with the wasm-bindgen glue as the only import module → __wbg_set_wasm →
// __wbindgen_start. Not instantiateStreaming, so a host that serves .wasm with the wrong MIME type still works.
// The navigator below is a port of the Android engine/FerrostarNavigation.kt › FerrostarNavigator: snapping, step
// advance and trip progress are Ferrostar's, with the app's step catch-up (StepCatchUp) on top. Ferrostar's
// visualInstruction and spokenInstruction are never read (ADR-0008).
import * as bg from "@stadiamaps/ferrostar/ferrostar_bg.js";
import { ferrostarControllerConfig, FerrostarConfig } from "./ferrostarConfig";
import { latLonOf, type Fix } from "./fix";
import type { LatLon } from "./geo";
import { rewriteValhallaText, type GuidancePlan } from "./plan";
import { CatchUpGate, StepCatchUp } from "./stepCatchUp";

export interface FerrostarBindings {
  RouteAdapter: typeof bg.RouteAdapter;
  NavigationController: typeof bg.NavigationController;
}

let ready: Promise<FerrostarBindings> | null = null;

/** Instantiates the core once per page (later calls reuse it). A failed load can be retried. */
export function initFerrostar(loadBytes: () => Promise<ArrayBuffer | Uint8Array>): Promise<FerrostarBindings> {
  ready ??= (async () => {
    const bytes = await loadBytes();
    const { instance } = await WebAssembly.instantiate(bytes as BufferSource, {
      "./ferrostar_bg.js": bg as unknown as WebAssembly.ModuleImports,
    });
    bg.__wbg_set_wasm(instance.exports);
    (instance.exports.__wbindgen_start as () => void)();
    return { RouteAdapter: bg.RouteAdapter, NavigationController: bg.NavigationController };
  })().catch((err: unknown) => {
    ready = null;
    throw err;
  });
  return ready;
}

/** Reserved `.invalid` endpoint: `generateRequest` is never called, so nothing is ever contacted (ADR-0011 §6). */
const UNUSED_ENDPOINT = "http://unused.invalid";

/** Ferrostar's route object for a recorded response, parsed after the ADR-0009 §3.1 text rewrite. */
export function parseFerrostarRoute(core: FerrostarBindings, recorded: unknown, plan: GuidancePlan, mode: "car" | "walk"): unknown {
  const rewritten = new TextEncoder().encode(JSON.stringify(rewriteValhallaText(recorded, plan.generation)));
  const adapter = new core.RouteAdapter({ Valhalla: { endpointUrl: UNUSED_ENDPOINT, profile: mode === "walk" ? "pedestrian" : "auto" } });
  try {
    const routes = adapter.parseResponse(rewritten) as { steps: unknown[] }[];
    const route = routes[0];
    // Step i of the plan must be step i of the parsed route; a mismatch is a data error.
    if (!route || route.steps.length !== plan.steps.length) throw new Error("route step count mismatch");
    return route;
  } finally {
    adapter.free();
  }
}

export type Deviation = "none" | "offStepOnRoute" | "completelyOffRoute";

/** ADR-0009 §10: the only Ferrostar-derived data the app uses. */
export interface NavSnapshot {
  /** Current step k = steps − remainingSteps; the upcoming manoeuvre is step k + 1. */
  stepIndex: number;
  distanceToNextManeuver: number;
  distanceRemaining: number;
  durationRemaining: number;
  deviation: Deviation;
  snapped: LatLon;
  snappedCourseDeg: number | null;
  complete: boolean;
  /** NAV-005-D9/D8: false when the (good) fix is off the current step and not caught up, or a catch-up is pending. */
  fixOnCurrentStep: boolean;
}

export interface Navigator {
  initial(fix: Fix): NavSnapshot;
  update(fix: Fix): NavSnapshot;
  close(): void;
}

interface Coord {
  lat: number;
  lng: number;
}
interface FsStep {
  geometry: Coord[];
  distance: number;
  duration: number;
}
interface FsNavigating {
  remainingSteps: FsStep[];
  snappedUserLocation: { coordinates: Coord; courseOverGround?: { degrees: number } | null };
  progress: { distanceToNextManeuver: number; distanceRemaining: number; durationRemaining: number };
  deviation: unknown;
}
interface FsState {
  tripState: unknown;
}

function tripState(s: FsState): { kind: "navigating"; t: FsNavigating } | { kind: "complete"; at: Coord | null } | { kind: "idle" } {
  const t = s.tripState as Record<string, unknown> | string;
  if (typeof t === "object" && t !== null && "Navigating" in t) return { kind: "navigating", t: t.Navigating as FsNavigating };
  if (t === "Complete") return { kind: "complete", at: null };
  if (typeof t === "object" && t !== null && "Complete" in t) {
    const c = t.Complete as { userLocation?: { coordinates?: Coord } } | null;
    return { kind: "complete", at: c?.userLocation?.coordinates ?? null };
  }
  return { kind: "idle" };
}

function deviationOf(d: unknown): Deviation {
  if (d === "NoDeviation" || d === null || d === undefined) return "none";
  const s = JSON.stringify(d);
  return s.includes("CompletelyOffRoute") ? "completelyOffRoute" : s.includes("OffStepOnRoute") ? "offStepOnRoute" : "none";
}

/** Drives Ferrostar's `NavigationController` synchronously with the ADR-0009 §1 configuration. */
export class FerrostarNavigator implements Navigator {
  private readonly controller: bg.NavigationController;
  private readonly route: { steps: FsStep[]; distance: number };
  private state: FsState | null = null;
  private readonly gate = new CatchUpGate();
  private lastFixOffStep = false;
  /** Steps advanced by the catch-up in this session (tests only). */
  stepCatchUps = 0;

  constructor(core: FerrostarBindings, route: unknown) {
    this.route = route as { steps: FsStep[]; distance: number };
    this.controller = new core.NavigationController(route, ferrostarControllerConfig(), false);
  }

  /**
   * First fix of a session. Ferrostar's initial state is always step 0; when the first good fix is clearly on a later
   * step (StepCatchUp branch (a) or (b)), the catch-up is applied at once, so a manoeuvre already behind the first fix
   * is never shown or spoken (NAV-017-D3, AC 17/20/26 on G4). The two-fix gate (NAV-005-D10) protects a trusted
   * position against one outlier; at start there is no earlier position to protect, so the first fix decides alone.
   */
  initial(fix: Fix): NavSnapshot {
    this.gate.reset();
    const s = this.catchUp(this.controller.getInitialState(userLocation(fix)) as FsState, fix, true);
    this.state = s;
    return this.snapshot(s, fix);
  }

  update(fix: Fix): NavSnapshot {
    const prev = this.state;
    if (!prev) return this.initial(fix);
    const s = this.catchUp(this.controller.updateUserLocation(userLocation(fix), prev) as FsState, fix);
    this.state = s;
    return this.snapshot(s, fix);
  }

  close(): void {
    this.controller.free();
  }

  /** `atStart`: the first fix of the session; the catch-up applies without the two-fix gate (see `initial`). */
  private catchUp(s: FsState, fix: Fix, atStart = false): FsState {
    this.lastFixOffStep = false;
    const good = fix.accuracyM <= StepCatchUp.MIN_ACCURACY_M;
    const ts = tripState(s);
    if (ts.kind !== "navigating") {
      this.gate.decide(null, good, fix.elapsedMs);
      return s;
    }
    const steps = ts.t.remainingSteps;
    const cache = new Map<number, LatLon[]>();
    const remaining = {
      length: steps.length,
      get: (i: number): LatLon[] => {
        let g = cache.get(i);
        if (!g) {
          g = steps[i]!.geometry.map((c) => ({ lat: c.lat, lon: c.lng }));
          cache.set(i, g);
        }
        return g;
      },
    };
    const p = latLonOf(fix);
    const n = StepCatchUp.stepsToAdvance(p, fix.accuracyM, remaining);
    const current = this.route.steps.length - steps.length;
    const apply = atStart ? good && n > 0 : this.gate.decide(n === 0 ? null : current + n, good, fix.elapsedMs);
    if (!apply) {
      this.lastFixOffStep = n > 0 || (remaining.length > 0 && StepCatchUp.offCurrentStep(p, fix.accuracyM, remaining.get(0)));
      return s;
    }
    let next = s;
    for (let i = 0; i < n; i++) next = this.controller.advanceToNextStep(next) as FsState;
    this.stepCatchUps += n;
    return next;
  }

  private snapshot(s: FsState, fix: Fix): NavSnapshot {
    const ts = tripState(s);
    const total = this.route.steps.length;
    if (ts.kind === "navigating") {
      const t = ts.t;
      const course = t.snappedUserLocation.courseOverGround?.degrees;
      return {
        stepIndex: Math.max(0, total - t.remainingSteps.length),
        distanceToNextManeuver: t.progress.distanceToNextManeuver,
        distanceRemaining: t.progress.distanceRemaining,
        durationRemaining: t.progress.durationRemaining,
        deviation: deviationOf(t.deviation),
        snapped: { lat: t.snappedUserLocation.coordinates.lat, lon: t.snappedUserLocation.coordinates.lng },
        snappedCourseDeg: typeof course === "number" ? course : null,
        complete: false,
        fixOnCurrentStep: !this.lastFixOffStep,
      };
    }
    if (ts.kind === "complete") {
      return {
        stepIndex: Math.max(0, total - 1),
        distanceToNextManeuver: 0,
        distanceRemaining: 0,
        durationRemaining: 0,
        deviation: "none",
        snapped: ts.at ? { lat: ts.at.lat, lon: ts.at.lng } : latLonOf(fix),
        snappedCourseDeg: fix.bearingDeg,
        complete: true,
        fixOnCurrentStep: true,
      };
    }
    return {
      stepIndex: 0,
      distanceToNextManeuver: this.route.steps[0]?.distance ?? 0,
      distanceRemaining: this.route.distance,
      durationRemaining: this.route.steps.reduce((a, st) => a + st.duration, 0),
      deviation: "none",
      snapped: latLonOf(fix),
      snappedCourseDeg: fix.bearingDeg,
      complete: false,
      fixOnCurrentStep: true,
    };
  }
}

/** Fix → Ferrostar `UserLocation` (FerrostarNavigator.userLocation in Kotlin). */
function userLocation(fix: Fix): unknown {
  const b = fix.bearingDeg;
  const course =
    b !== null && Number.isFinite(b)
      ? {
          degrees: ((Math.round(b) % 360) + 360) % 360,
          accuracy:
            fix.bearingAccuracyDeg !== null && Number.isFinite(fix.bearingAccuracyDeg) && fix.bearingAccuracyDeg >= 0
              ? Math.min(65535, Math.round(fix.bearingAccuracyDeg))
              : undefined,
        }
      : undefined;
  return {
    coordinates: { lat: fix.lat, lng: fix.lon },
    horizontalAccuracy: Number.isFinite(fix.accuracyM) ? fix.accuracyM : 9_999,
    courseOverGround: course,
    timestamp: fix.wallTimeMs,
    speed: fix.speedMps !== null && Number.isFinite(fix.speedMps) && fix.speedMps >= 0 ? { value: fix.speedMps, accuracy: undefined } : undefined,
  };
}

export { FerrostarConfig };

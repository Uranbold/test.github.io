// ADR-0009 §1 NavigationControllerConfig values, shared with Android (engine/FerrostarNavigation.kt › FerrostarConfig).
// Changed only through the BA (deviation values) or a recorded replay finding (step-advance distances), on both
// platforms in lockstep (ADR-0011 §6).
export const FerrostarConfig = {
  WAYPOINT_RANGE_M: 30,
  STEP_ENTRY_M: 30,
  STEP_EXIT_M: 5,
  ARRIVAL_STEP_M: 30,
  MIN_ACCURACY_M: 25,
  MAX_DEVIATION_M: 50,
} as const;

/** The config object in the shape the Ferrostar 0.57.0 WASM bindings expect (serde names). */
export function ferrostarControllerConfig(): unknown {
  const c = FerrostarConfig;
  return {
    waypointAdvance: { WaypointWithinRange: c.WAYPOINT_RANGE_M },
    stepAdvanceCondition: {
      DistanceEntryExit: {
        distanceToEndOfStep: c.STEP_ENTRY_M,
        distanceAfterEndStep: c.STEP_EXIT_M,
        minimumHorizontalAccuracy: c.MIN_ACCURACY_M,
        hasReachedEndOfCurrentStep: false,
      },
    },
    arrivalStepAdvanceCondition: { DistanceToEndOfStep: { distance: c.ARRIVAL_STEP_M, minimumHorizontalAccuracy: c.MIN_ACCURACY_M } },
    routeDeviationTracking: { StaticThreshold: { minimumHorizontalAccuracy: c.MIN_ACCURACY_M, maxAcceptableDeviation: c.MAX_DEVIATION_M } },
    snappedLocationCourseFiltering: "SnapToRoute",
  };
}

// ADR-0009 §5 arrival with the Amendment 1 / Amendment 3 §5 gating (NAV-017 AC 32–34). 1:1 port of the Android
// arrival/ArrivalDetector.kt. Fires exactly once when
//  (a) Ferrostar reports the trip complete;
//  (b) not off-route, a good AND trusted fix and ≤ 30 m remaining (G8 ends 3.9 m short of Ferrostar's `Complete`);
//  (c) a good fix within 30 m straight-line of the route's last coordinate, but only while the upcoming manoeuvre is
//      the `arrive` step (G4: stationary 10 m beside the end).
import { distance, type LatLon } from "./geo";

export interface ArrivalInput {
  complete: boolean;
  offRoute: boolean;
  goodFix: boolean;
  trustedFix: boolean;
  distanceRemaining: number;
  position: LatLon;
  routeEnd: LatLon;
  /** Current step k of the navigator (the upcoming manoeuvre is k + 1). */
  stepIndex: number;
  /** Index of the route's `arrive` step (steps.length − 1). */
  lastStepIndex: number;
}

export class ArrivalDetector {
  arrived = false;
  constructor(private readonly radiusM = 30) {}

  check(i: ArrivalInput): boolean {
    if (this.arrived) return false;
    const onLastLeg = i.stepIndex >= i.lastStepIndex - 1;
    const hit =
      i.complete ||
      (!i.offRoute && i.goodFix && i.trustedFix && i.distanceRemaining <= this.radiusM) ||
      (i.goodFix && onLastLeg && distance(i.position, i.routeEnd) <= this.radiusM);
    if (hit) this.arrived = true;
    return hit;
  }
}

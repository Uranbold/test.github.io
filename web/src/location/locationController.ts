// "My location" (NAV-002 AC 18–25, 47; screen spec › My-location button states; flow F3).
// No Geolocation or Permissions API call before the first press (AC 18, ADR-0004 §6).
// Coordinates stay in the page: this module never sends them anywhere (AC 47).

export type LocationButtonState = "idle" | "locating" | "following" | "not-following" | "denied" | "unsupported";
export type LocationMessage = null | "denied" | "unavailable" | "stale";

export interface Fix {
  lng: number;
  lat: number;
  accuracy: number;
}

export interface LocationView {
  button: LocationButtonState;
  message: LocationMessage;
  fix: Fix | null;
  stale: boolean;
}

export interface CameraPort {
  /** First fix or recentre: fly to the fix, zoom max(current, 15). */
  recenter(fix: Fix): void;
  /** Follow update: move to the fix, zoom unchanged. */
  follow(fix: Fix): void;
}

export const RECENTER_MIN_ZOOM = 15;
export const GEO_OPTIONS: PositionOptions = { enableHighAccuracy: true, timeout: 10_000, maximumAge: 0 };

// GeolocationPositionError codes (numeric so the module also runs outside a browser).
const PERMISSION_DENIED = 1;

export class LocationController {
  private watchId: number | null = null;
  private v: LocationView;
  private readonly listeners = new Set<(v: LocationView) => void>();

  constructor(
    private readonly geo: Geolocation | undefined,
    secureContext: boolean,
    private readonly camera: CameraPort,
  ) {
    const supported = secureContext && geo !== undefined;
    this.v = { button: supported ? "idle" : "unsupported", message: null, fix: null, stale: false };
  }

  get view(): Readonly<LocationView> {
    return this.v;
  }

  onChange(l: (v: LocationView) => void): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  /** The my-location button was pressed. */
  press(): void {
    const b = this.v.button;
    if (b === "unsupported" || b === "locating") return;
    if (this.watchId !== null && this.v.fix) {
      // Watching with a fix: recentre and follow again (AC 20, last sentence).
      this.set({ button: "following" });
      this.camera.recenter(this.v.fix);
      return;
    }
    this.startWatch();
  }

  /** «Дахин оролдох» in the "could not determine" message (AC 22). */
  retry(): void {
    this.set({ message: null });
    this.stopWatch();
    this.startWatch();
  }

  /** «Хаах». */
  dismissMessage(): void {
    this.set({ message: null });
  }

  /** The user moved the map (drag, keyboard pan, double-click zoom): stop following (AC 20). */
  userMovedMap(): void {
    if (this.v.button === "following") this.set({ button: "not-following" });
  }

  dispose(): void {
    this.stopWatch();
    this.listeners.clear();
  }

  private startWatch(): void {
    if (!this.geo) return;
    this.set({ button: "locating", message: this.v.message === "denied" ? null : this.v.message });
    try {
      this.watchId = this.geo.watchPosition(
        (p) => this.onPosition(p),
        (e) => this.onError(e),
        GEO_OPTIONS,
      );
    } catch {
      this.watchId = null;
      this.set({ button: "idle", message: "unavailable" });
    }
  }

  private stopWatch(): void {
    if (this.watchId !== null && this.geo) this.geo.clearWatch(this.watchId);
    this.watchId = null;
  }

  private onPosition(p: GeolocationPosition): void {
    const fix: Fix = { lng: p.coords.longitude, lat: p.coords.latitude, accuracy: p.coords.accuracy };
    const first = this.v.button === "locating";
    const wasStale = this.v.stale;
    const message = wasStale || this.v.message === "unavailable" ? null : this.v.message;
    if (first) {
      this.set({ fix, stale: false, button: "following", message });
      this.camera.recenter(fix);
      return;
    }
    this.set({ fix, stale: false, message });
    if (this.v.button === "following") this.camera.follow(fix);
  }

  private onError(e: GeolocationPositionError): void {
    if (e.code === PERMISSION_DENIED) {
      this.stopWatch();
      this.set({ button: "denied", message: "denied", fix: null, stale: false });
      return;
    }
    if (this.v.fix) {
      // Fixes stopped after a position was shown: keep the last position, grey (AC 23). Keep watching.
      this.set({ stale: true, message: "stale" });
      return;
    }
    // POSITION_UNAVAILABLE or TIMEOUT before the first fix (AC 22).
    this.stopWatch();
    this.set({ button: "idle", message: "unavailable" });
  }

  private set(patch: Partial<LocationView>): void {
    this.v = { ...this.v, ...patch };
    for (const l of this.listeners) l(this.v);
  }
}

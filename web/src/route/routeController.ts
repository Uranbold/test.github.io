// Route preview request lifecycle (NAV-004 AC 3–5, 8, 10–14, 18, 32–39, 47, 50, 53; screen spec › States; flow F3,
// F5, F7). No DOM and no MapLibre: the panel (src/ui/routePreview.ts) feeds user actions in and renders the view.
//
// Rules implemented here:
//  - one request per triggering action; at most one in flight, a newer action aborts the older one (AC 13);
//  - mode tabs settle for 300 ms before the request (AC 11); the previous route is removed at once (AC 32);
//  - no request when start and destination are ≤ 10 m apart (AC 14), while offline (AC 38), during a 429 wait
//    (AC 37) or in the static public build (AC 53–54);
//  - «Дахин оролдох» re-sends the current request once; nothing is retried automatically (AC 36, 37);
//  - back online with both points set and the offline state shown: one request (AC 38).
import type { Lang } from "../i18n/i18n";
import { distanceMeters } from "../geo/scale";
import { Cooldown, browserTimers, type Timers } from "../search/gateway";
import type { RetryState } from "../search/searchController";
import type { OsrmRouteResponse } from "./osrm";
import type { Costing, RouteClient, RouteOutcome, RoutePoint } from "./routeClient";

/** tokens: motion.route-mode-settle (AC 11). */
export const MODE_SETTLE_MS = 300;
/** tokens: motion.loading-delay (AC 32). */
export const ROUTE_LOADING_DELAY_MS = 300;
/** AC 14: start and destination this close (haversine) count as the same point. */
export const SAME_POINT_M = 10;
/** AC 21 / Open question 1 default: snap notice above this distance. */
export const SNAP_NOTICE_M = 500;

export type Mode = "car" | "walk" | "bike";
export const MODES: readonly Mode[] = ["car", "walk", "bike"];
export const COSTING: Readonly<Record<Mode, Costing>> = { car: "auto", walk: "pedestrian", bike: "bicycle" };

/** What a field shows for a point (screen spec › Content rules › Field texts). */
export type PointLabel = { kind: "myLocation" } | { kind: "selected" } | { kind: "name"; text: string };

export interface RouteEnd {
  point: RoutePoint;
  label: PointLabel;
}

export type RouteState =
  | "empty"
  | "pending"
  | "loading"
  | "route"
  | "same-point"
  | "no-route"
  | "out-of-area"
  | "too-far"
  | "unavailable"
  | "offline"
  | "rate-limited"
  | "error"
  | "static";

export interface RouteView {
  state: RouteState;
  /** Set only in state "route". */
  response: OsrmRouteResponse | null;
  /** Selected route index (0 = Valhalla's first). */
  selected: number;
  /** AC 33: the «Шороон замаас зайлсхийх…» hint under «Маршрут олдсонгүй». */
  avoidHint: boolean;
  retry: RetryState;
  /** Device clock (ms) when the response arrived: base of «Хүрэх цаг» (AC 25). */
  receivedAt: number;
}

export type RouteAnnouncement = { kind: "result" } | { kind: "selection" } | { kind: "state"; state: RouteState };

export const EMPTY_VIEW: RouteView = Object.freeze({
  state: "empty",
  response: null,
  selected: 0,
  avoidHint: false,
  retry: "none",
  receivedAt: 0,
});

export interface RouteControllerDeps {
  client: Pick<RouteClient, "route" | "enabled">;
  lang: () => Lang;
  isOnline: () => boolean;
  /** Monotonic clock for the 429 wait (performance.now). */
  now?: () => number;
  /** Wall clock for «Хүрэх цаг» (Date.now). */
  clock?: () => number;
  timers?: Timers;
}

/** Haversine distance between two points in metres. */
export function pointDistance(a: RoutePoint, b: RoutePoint): number {
  return distanceMeters({ lat: a.lat, lng: a.lon }, { lat: b.lat, lng: b.lon });
}

/** The larger snap distance if it is above the notice threshold, else null (AC 21). */
export function snapNoticeDistance(r: OsrmRouteResponse): number | null {
  const max = Math.max(...r.snapDistances.map((d) => d ?? 0), 0);
  return max > SNAP_NOTICE_M ? max : null;
}

export class RouteController {
  private v: RouteView = EMPTY_VIEW;
  private originEnd: RouteEnd | null = null;
  private destinationEnd: RouteEnd | null = null;
  private currentMode: Mode = "car";
  private avoidUnpaved = false;
  private isOpen = false;
  private gen = 0;
  private inflight: AbortController | null = null;
  private settleTimer: unknown = null;
  private loadingTimer: unknown = null;
  private readonly timers: Timers;
  private readonly cooldown: Cooldown;
  private readonly clock: () => number;
  private readonly viewListeners = new Set<(v: RouteView) => void>();
  private readonly announceListeners = new Set<(a: RouteAnnouncement) => void>();

  constructor(private readonly deps: RouteControllerDeps) {
    this.timers = deps.timers ?? browserTimers;
    this.cooldown = new Cooldown(deps.now ?? (() => performance.now()), this.timers);
    this.clock = deps.clock ?? (() => Date.now());
  }

  get view(): Readonly<RouteView> {
    return this.v;
  }
  get origin(): RouteEnd | null {
    return this.originEnd;
  }
  get destination(): RouteEnd | null {
    return this.destinationEnd;
  }
  get mode(): Mode {
    return this.currentMode;
  }
  get avoid(): boolean {
    return this.avoidUnpaved;
  }
  get open(): boolean {
    return this.isOpen;
  }
  get inFlight(): boolean {
    return this.inflight !== null;
  }
  get cooldownActive(): boolean {
    return this.cooldown.active;
  }

  onChange(l: (v: RouteView) => void): () => void {
    this.viewListeners.add(l);
    return () => this.viewListeners.delete(l);
  }

  onAnnounce(l: (a: RouteAnnouncement) => void): () => void {
    this.announceListeners.add(l);
    return () => this.announceListeners.delete(l);
  }

  // ------------------------------------------------------------------ user actions

  /**
   * The panel opens (AC 1–4). The request goes out at once when both points are set; the static build shows the
   * unavailable row whatever the origin state (screen spec › Static public build).
   */
  openWith(destination: RouteEnd, origin: RouteEnd | null): void {
    this.cancelAll();
    this.isOpen = true;
    this.destinationEnd = destination;
    this.originEnd = origin;
    this.v = EMPTY_VIEW;
    this.request();
  }

  /** «Хаах» / Escape (AC 41): in-flight request aborted, nothing sent. Mode and avoid are kept for the session. */
  close(): void {
    this.cancelAll();
    this.isOpen = false;
    this.originEnd = null;
    this.destinationEnd = null;
    this.emit(EMPTY_VIEW);
  }

  setOrigin(end: RouteEnd): void {
    if (!this.isOpen) return;
    this.originEnd = end;
    this.request();
  }

  setDestination(end: RouteEnd): void {
    if (!this.isOpen) return;
    this.destinationEnd = end;
    this.request();
  }

  /** AC 8: only with both points set; one request. */
  swap(): boolean {
    if (!this.isOpen || !this.originEnd || !this.destinationEnd) return false;
    [this.originEnd, this.destinationEnd] = [this.destinationEnd, this.originEnd];
    this.request();
    return true;
  }

  /** AC 11: the request follows once the tab has stayed selected for 300 ms. The old route goes at once (AC 32). */
  setMode(mode: Mode): void {
    if (mode === this.currentMode) return;
    this.currentMode = mode;
    if (!this.isOpen) return;
    this.cancelInflight();
    this.clearSettle();
    if (this.v.state === "route" || this.v.state === "loading" || this.v.state === "pending") this.emit(EMPTY_VIEW);
    this.settleTimer = this.timers.setTimeout(() => {
      this.settleTimer = null;
      this.request();
    }, MODE_SETTLE_MS);
  }

  /** AC 12: toggling sends one request on «Машин»; the value is kept across tabs. */
  setAvoid(on: boolean): void {
    if (on === this.avoidUnpaved) return;
    this.avoidUnpaved = on;
    if (this.isOpen && this.currentMode === "car") this.request();
  }

  /** «Дахин оролдох» (AC 36, 37, 53). Ignored while disabled or absent. */
  retry(): void {
    if (!this.isOpen || this.v.retry !== "enabled") return;
    this.request();
  }

  /** `online` event (AC 38): one request when both points are set and the offline state is shown. */
  online(): void {
    if (!this.isOpen || this.v.state !== "offline" || !this.originEnd || !this.destinationEnd) return;
    this.request();
  }

  /** AC 18: another route becomes the selected one; 0 requests. */
  selectRoute(index: number): void {
    const r = this.v.response;
    if (this.v.state !== "route" || !r || index === this.v.selected || index < 0 || index >= r.routes.length) return;
    this.emit({ ...this.v, selected: index });
    this.announce({ kind: "selection" });
  }

  dispose(): void {
    this.cancelAll();
    this.cooldown.dispose();
    this.viewListeners.clear();
    this.announceListeners.clear();
  }

  // ------------------------------------------------------------------ internals

  /** One triggering action with the current inputs. Precedence: same point > offline > 429 wait > static. */
  private request(): void {
    this.cancelInflight();
    this.clearSettle();
    const o = this.originEnd;
    const d = this.destinationEnd;
    if (!o || !d) {
      if (!this.deps.client.enabled) this.showState("static", "enabled");
      else this.emit(EMPTY_VIEW);
      return;
    }
    if (pointDistance(o.point, d.point) <= SAME_POINT_M) {
      this.showState("same-point", "none");
      return;
    }
    if (!this.deps.isOnline()) {
      this.showState("offline", "none");
      return;
    }
    if (this.cooldown.active) {
      this.showState("rate-limited", "disabled");
      return;
    }
    if (!this.deps.client.enabled) {
      this.showState("static", "enabled");
      return;
    }
    void this.send(o, d);
  }

  private async send(o: RouteEnd, d: RouteEnd): Promise<void> {
    const g = ++this.gen;
    const ctrl = new AbortController();
    this.inflight = ctrl;
    this.emit({ ...EMPTY_VIEW, state: "pending" });
    this.loadingTimer = this.timers.setTimeout(() => {
      this.loadingTimer = null;
      if (g === this.gen) this.emit({ ...EMPTY_VIEW, state: "loading" });
    }, ROUTE_LOADING_DELAY_MS);
    const mode = this.currentMode;
    let outcome: RouteOutcome;
    try {
      outcome = await this.deps.client.route(
        { origin: o.point, destination: d.point, costing: COSTING[mode], avoidUnpaved: this.avoidUnpaved, lang: this.deps.lang() },
        ctrl.signal,
      );
    } catch {
      outcome = { kind: "unavailable" };
    }
    if (g !== this.gen) return; // AC 13: an older response never replaces a newer request's result
    this.inflight = null;
    this.clearLoading();
    this.apply(outcome, mode);
  }

  private apply(r: RouteOutcome, mode: Mode): void {
    switch (r.kind) {
      case "ok":
        this.emit({ state: "route", response: r.response, selected: 0, avoidHint: false, retry: "none", receivedAt: this.clock() });
        this.announce({ kind: "result" });
        return;
      case "noRoute":
        this.showState("no-route", "none", mode === "car" && this.avoidUnpaved);
        return;
      case "distanceExceeded":
        this.showState(mode === "car" ? "no-route" : "too-far", "none");
        return;
      case "outOfArea":
        this.showState("out-of-area", "none");
        return;
      case "error":
        this.showState("error", "none");
        return;
      case "unavailable":
        this.showState("unavailable", "enabled");
        return;
      case "offline":
        this.showState("offline", "none");
        return;
      case "rateLimited":
        this.cooldown.start(r.retryAfterS, () => {
          // After N s the button turns active; nothing is sent automatically (AC 37).
          if (this.v.state === "rate-limited") this.emit({ ...this.v, retry: "enabled" });
        });
        this.showState("rate-limited", "disabled");
        return;
      case "disabled":
        this.showState("static", "enabled");
        return;
      case "aborted":
        return;
    }
  }

  private showState(state: RouteState, retry: RetryState, avoidHint = false): void {
    this.emit({ ...EMPTY_VIEW, state, retry, avoidHint });
    this.announce({ kind: "state", state });
  }

  private cancelInflight(): void {
    this.gen += 1;
    this.inflight?.abort();
    this.inflight = null;
    this.clearLoading();
  }

  private cancelAll(): void {
    this.cancelInflight();
    this.clearSettle();
  }

  private clearSettle(): void {
    if (this.settleTimer !== null) this.timers.clearTimeout(this.settleTimer);
    this.settleTimer = null;
  }

  private clearLoading(): void {
    if (this.loadingTimer !== null) this.timers.clearTimeout(this.loadingTimer);
    this.loadingTimer = null;
  }

  private emit(v: RouteView): void {
    this.v = v;
    for (const l of this.viewListeners) l(v);
  }

  private announce(a: RouteAnnouncement): void {
    for (const l of this.announceListeners) l(a);
  }
}

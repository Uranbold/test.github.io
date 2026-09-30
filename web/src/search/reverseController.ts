// Nearest place for a coordinate card (story AC 25–30, 36, 37; ADR-0006 §4; flow F3).
// Exactly one `reverse` request per card; «Дахин оролдох» re-sends once; per-operation 429 cooldown.
import type { Lang } from "../i18n/i18n";
import { reverseParams, type LatLon } from "./coords";
import { Cooldown, browserTimers, type GatewayClient, type Outcome, type Timers } from "./gateway";
import type { PhotonFeature } from "./photon";
import { LOADING_DELAY_MS, type RetryState } from "./searchController";

export type NearState = "pending" | "loading" | "place" | "empty" | "unavailable" | "offline" | "rate-limited" | "error";

export interface NearView {
  state: NearState;
  feature: PhotonFeature | null;
  retry: RetryState;
}

export interface ReverseDeps {
  client: Pick<GatewayClient, "reverse">;
  lang: () => Lang;
  isOnline: () => boolean;
  now?: () => number;
  timers?: Timers;
}

export class ReverseController {
  private v: NearView | null = null;
  private point: LatLon | null = null;
  private gen = 0;
  private inflight: AbortController | null = null;
  private loadingTimer: unknown = null;
  private readonly timers: Timers;
  private readonly cooldown: Cooldown;
  private readonly listeners = new Set<(v: NearView | null) => void>();

  constructor(private readonly deps: ReverseDeps) {
    this.timers = deps.timers ?? browserTimers;
    this.cooldown = new Cooldown(deps.now ?? (() => performance.now()), this.timers);
  }

  get view(): Readonly<NearView> | null {
    return this.v;
  }

  onChange(l: (v: NearView | null) => void): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  /** A coordinate card opened (right-click, long-press, typed coordinate). */
  open(point: LatLon): void {
    this.point = point;
    void this.send();
  }

  retry(): void {
    if (this.v?.retry !== "enabled") return;
    void this.send();
  }

  /** Card closed or replaced by a result card. */
  close(): void {
    this.cancel();
    this.point = null;
    this.emit(null);
  }

  /** Back online with the card in the offline state: send once (screen spec › coordinate card offline). */
  online(): void {
    if (this.v?.state === "offline") void this.send();
  }

  dispose(): void {
    this.cancel();
    this.cooldown.dispose();
    this.listeners.clear();
  }

  private async send(): Promise<void> {
    const point = this.point;
    if (!point) return;
    this.cancel();
    const g = this.gen;
    if (!this.deps.isOnline()) {
      this.emit({ state: "offline", feature: null, retry: "none" });
      return;
    }
    if (this.cooldown.active) {
      this.emit({ state: "rate-limited", feature: null, retry: "disabled" });
      return;
    }
    const ctrl = new AbortController();
    this.inflight = ctrl;
    this.emit({ state: "pending", feature: null, retry: "none" });
    this.loadingTimer = this.timers.setTimeout(() => {
      this.loadingTimer = null;
      if (g === this.gen) this.emit({ state: "loading", feature: null, retry: "none" });
    }, LOADING_DELAY_MS);
    let r: Outcome;
    try {
      r = await this.deps.client.reverse({ ...reverseParams(point), lang: this.deps.lang(), limit: 1, radius: 0.5 }, ctrl.signal);
    } catch {
      r = { kind: "unavailable" };
    }
    if (g !== this.gen) return;
    this.inflight = null;
    this.clearLoadingTimer();
    switch (r.kind) {
      case "ok": {
        const f = r.features[0] ?? null;
        this.emit({ state: f ? "place" : "empty", feature: f, retry: "none" });
        return;
      }
      case "rateLimited":
        this.cooldown.start(r.retryAfterS, () => {
          // After N s nothing is sent until the user presses «Дахин оролдох» or opens another card (AC 36).
          if (this.v?.state === "rate-limited") this.emit({ ...this.v, retry: "enabled" });
        });
        this.emit({ state: "rate-limited", feature: null, retry: "disabled" });
        return;
      case "unavailable":
        this.emit({ state: "unavailable", feature: null, retry: "enabled" });
        return;
      case "badRequest":
        this.emit({ state: "error", feature: null, retry: "none" });
        return;
      case "offline":
        this.emit({ state: "offline", feature: null, retry: "none" });
        return;
      case "aborted":
        return;
    }
  }

  private cancel(): void {
    this.gen += 1;
    this.inflight?.abort();
    this.inflight = null;
    this.clearLoadingTimer();
  }

  private clearLoadingTimer(): void {
    if (this.loadingTimer !== null) this.timers.clearTimeout(this.loadingTimer);
    this.loadingTimer = null;
  }

  private emit(v: NearView | null): void {
    this.v = v;
    for (const l of this.listeners) l(v);
  }
}

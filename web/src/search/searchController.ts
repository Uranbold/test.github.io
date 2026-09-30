// Search-as-you-type orchestration (story AC 3–9, 13, 15, 16, 26, 31–38, 41, 42; ADR-0006 §3; flows F1, F4, F6).
// No DOM: the UI (src/ui/searchBox.ts) feeds input events in and renders the view it gets back.
import type { Lang } from "../i18n/i18n";
import { biasParams, type LatLon } from "./coords";
import { Cooldown, browserTimers, type GatewayClient, type Outcome, type Timers } from "./gateway";
import { mergeFeatures, SEARCH_LIMIT } from "./merge";
import type { PhotonFeature } from "./photon";
import { planQuery, type QueryPlan } from "./queryPlan";
import { isSearchable, normalizeQuery } from "./text";

/** tokens: motion.search-debounce (AC 3 allows 200–350 ms). */
export const DEBOUNCE_MS = 250;
/** Loading row after this long (AC 13). */
export const LOADING_DELAY_MS = 300;

export type ListState =
  | "closed"
  | "results"
  | "coordinate"
  | "loading"
  | "no-results"
  | "unavailable"
  | "offline"
  | "rate-limited"
  | "error";

export type MessageState = "no-results" | "unavailable" | "offline" | "rate-limited" | "error";
export type RetryState = "none" | "enabled" | "disabled";

export type SearchOption = { kind: "place"; feature: PhotonFeature } | { kind: "coordinate"; point: LatLon };

export interface SearchView {
  state: ListState;
  /** Options when `state` is results or coordinate; empty otherwise. */
  options: readonly SearchOption[];
  retry: RetryState;
  /** The loading row shows (aria-busy, AC 13). */
  busy: boolean;
  /** The settled query the view belongs to. */
  query: string | null;
}

export type Announcement = { kind: "count"; count: number } | { kind: "message"; state: MessageState };

export const CLOSED: SearchView = Object.freeze({ state: "closed", options: [], retry: "none", busy: false, query: null });

export interface SearchDeps {
  client: Pick<GatewayClient, "search">;
  lang: () => Lang;
  /** Search bias point (AC 9): device fix ≤ 60 s old while following, otherwise the map centre. */
  bias: () => LatLon;
  isOnline: () => boolean;
  now?: () => number;
  timers?: Timers;
}

type TextPlan = Extract<QueryPlan, { kind: "text" }>;

const OPENABLE: ReadonlySet<ListState> = new Set(["results", "coordinate", "no-results"]);

export class SearchController {
  private v: SearchView = CLOSED;
  private raw = "";
  private gen = 0;
  private inflight: AbortController | null = null;
  private inflightQuery: string | null = null;
  private debounceTimer: unknown = null;
  private loadingTimer: unknown = null;
  private readonly cooldown: Cooldown;
  private readonly timers: Timers;
  /** Last completed content that can be re-opened / reused for an identical query (AC 6, ArrowDown). */
  private last: SearchView | null = null;
  private lastLang: Lang | null = null;
  /** Settled query of the last request attempt («Дахин оролдох» re-sends it). */
  private lastRequested: string | null = null;
  private waitingOnline = false;
  private selectOnArrival = false;
  private readonly viewListeners = new Set<(v: SearchView) => void>();
  private readonly announceListeners = new Set<(a: Announcement) => void>();
  private readonly selectListeners = new Set<(o: SearchOption) => void>();

  constructor(private readonly deps: SearchDeps) {
    this.timers = deps.timers ?? browserTimers;
    this.cooldown = new Cooldown(deps.now ?? (() => performance.now()), this.timers);
  }

  get view(): Readonly<SearchView> {
    return this.v;
  }

  /** Current raw text as the controller knows it. */
  get text(): string {
    return this.raw;
  }

  get cooldownActive(): boolean {
    return this.cooldown.active;
  }

  onChange(l: (v: SearchView) => void): () => void {
    this.viewListeners.add(l);
    return () => this.viewListeners.delete(l);
  }

  onAnnounce(l: (a: Announcement) => void): () => void {
    this.announceListeners.add(l);
    return () => this.announceListeners.delete(l);
  }

  /** Enter without a highlighted option selects the first option when it arrives (AC 41). */
  onAutoSelect(l: (o: SearchOption) => void): () => void {
    this.selectListeners.add(l);
    return () => this.selectListeners.delete(l);
  }

  /** Every input event (typing, paste, deleting). */
  input(raw: string): void {
    this.raw = raw;
    this.clearDebounce();
    this.selectOnArrival = false;
    if (raw === "") {
      this.clear();
      return;
    }
    // AC 5: a response for an older query never replaces the list for a newer one.
    if (this.inflight && normalizeQuery(raw) !== this.inflightQuery) this.cancelInflight();
    this.debounceTimer = this.timers.setTimeout(() => {
      this.debounceTimer = null;
      this.settle(false);
    }, DEBOUNCE_MS);
  }

  /** Enter with no highlighted option (AC 41): select the first option of the current query, searching at once if needed. */
  submit(): void {
    this.clearDebounce();
    const q = normalizeQuery(this.raw);
    if (!isSearchable(q)) return;
    if (this.inflight && this.inflightQuery === q) {
      this.selectOnArrival = true;
      return;
    }
    this.settle(true);
  }

  /** «Дахин оролдох» in the list (AC 33, 35). Ignored while disabled. */
  retry(): void {
    if (this.v.retry !== "enabled" || this.lastRequested === null) return;
    const plan = planQuery(this.lastRequested);
    if (plan.kind === "text") void this.run(this.lastRequested, plan, false);
  }

  /** Clear button or all text deleted (AC 31): list closed, request aborted, nothing sent, no message. */
  clear(): void {
    this.clearDebounce();
    this.cancelInflight();
    this.raw = "";
    this.last = null;
    this.waitingOnline = false;
    this.selectOnArrival = false;
    this.emit(CLOSED);
  }

  /** Escape, Tab, click on the map (AC 41): closes the list without selecting. */
  close(): void {
    this.clearDebounce();
    this.cancelInflight();
    this.selectOnArrival = false;
    if (this.v.state !== "closed") this.emit(CLOSED);
  }

  /** ArrowDown with a closed list re-opens the last list if it belongs to the current text. */
  reopen(): boolean {
    if (this.v.state !== "closed" || !this.last) return false;
    if (this.last.query !== normalizeQuery(this.raw) || this.lastLang !== this.deps.lang()) return false;
    this.emit(this.last);
    return true;
  }

  /** A result was selected: the input now shows `text`; nothing is searched for it. */
  accept(text: string): void {
    this.clearDebounce();
    this.cancelInflight();
    this.raw = text;
    this.last = null;
    this.selectOnArrival = false;
    this.emit(CLOSED);
  }

  /** UI language switched (AC 38): re-request an open list (or a pending query) once with the new `lang`. */
  langChanged(): void {
    const q = this.inflight ? this.inflightQuery : this.v.state === "results" || this.v.state === "no-results" ? this.v.query : null;
    if (q !== null) {
      const plan = planQuery(q);
      if (plan.kind === "text") {
        void this.run(q, plan, this.selectOnArrival);
        return;
      }
    }
    // State messages and the coordinate option only switch their text (the UI re-renders).
    this.emit(this.v);
  }

  /** `online` event (AC 34): search the current settled query once if the list shows the offline state. */
  online(): void {
    if (!this.waitingOnline || this.v.state !== "offline") return;
    this.waitingOnline = false;
    const q = normalizeQuery(this.raw);
    if (!isSearchable(q)) return;
    const plan = planQuery(q);
    if (plan.kind === "text") void this.run(q, plan, false);
  }

  dispose(): void {
    this.clearDebounce();
    this.cancelInflight();
    this.cooldown.dispose();
    this.viewListeners.clear();
    this.announceListeners.clear();
    this.selectListeners.clear();
  }

  // ------------------------------------------------------------------ internals

  private settle(selectFirst: boolean): void {
    const q = normalizeQuery(this.raw);
    if (!isSearchable(q)) {
      this.cancelInflight();
      this.emit(CLOSED);
      return;
    }
    if (!selectFirst && this.inflight && this.inflightQuery === q) return; // already on its way
    const lang = this.deps.lang();
    const last = this.last;
    if (last && last.query === q && this.lastLang === lang && OPENABLE.has(last.state)) {
      // AC 6: identical settled query, no new request.
      this.cancelInflight();
      this.emit(last);
      if (selectFirst && last.options[0]) this.autoSelect(last.options[0]);
      return;
    }
    const plan = planQuery(q);
    if (plan.kind === "skip") return;
    if (plan.kind === "coordinate") {
      // AC 26: one «Сонгосон цэг» option, no request.
      this.cancelInflight();
      const view: SearchView = { state: "coordinate", options: [{ kind: "coordinate", point: plan.point }], retry: "none", busy: false, query: q };
      this.last = view;
      this.lastLang = lang;
      this.emit(view);
      this.announce({ kind: "count", count: 1 });
      if (selectFirst) this.autoSelect(view.options[0]!);
      return;
    }
    void this.run(q, plan, selectFirst);
  }

  private async run(q: string, plan: TextPlan, selectFirst: boolean): Promise<void> {
    this.cancelInflight();
    const g = this.gen;
    this.lastRequested = q;
    this.selectOnArrival = selectFirst;
    if (!this.deps.isOnline()) {
      // AC 34: nothing is sent while offline.
      this.waitingOnline = true;
      this.showMessage("offline", q);
      return;
    }
    this.waitingOnline = false;
    if (this.cooldown.active) {
      // AC 35: nothing is sent inside the Retry-After window, whatever the user types.
      this.showMessage("rate-limited", q);
      return;
    }
    const ctrl = new AbortController();
    this.inflight = ctrl;
    this.inflightQuery = q;
    this.loadingTimer = this.timers.setTimeout(() => {
      this.loadingTimer = null;
      if (g === this.gen) this.emit({ state: "loading", options: [], retry: "none", busy: true, query: q });
    }, LOADING_DELAY_MS);

    const lang = this.deps.lang();
    const bias = biasParams(this.deps.bias());
    const send = (text: string): Promise<Outcome> =>
      this.deps.client.search({ q: text, lang, limit: SEARCH_LIMIT, lat: bias.lat, lon: bias.lon }, ctrl.signal);

    let result: Outcome;
    try {
      if (plan.mode === "parallel" && plan.secondary !== null) {
        const [a, b] = await Promise.all([send(plan.primary), send(plan.secondary)]);
        result = combineParallel(a, b);
      } else {
        const a = await send(plan.primary);
        if (g !== this.gen) return;
        if (plan.mode === "ifEmpty" && plan.secondary !== null && a.kind === "ok" && a.features.length === 0) {
          result = await send(plan.secondary);
        } else {
          result = a;
        }
        if (result.kind === "ok") result = { kind: "ok", features: mergeFeatures(result.features, null) };
      }
    } catch {
      result = { kind: "unavailable" };
    }
    if (g !== this.gen) return;
    this.inflight = null;
    this.inflightQuery = null;
    this.clearLoadingTimer();
    this.apply(q, lang, result);
  }

  private apply(q: string, lang: Lang, r: Outcome): void {
    switch (r.kind) {
      case "ok": {
        const options: SearchOption[] = r.features.map((feature) => ({ kind: "place", feature }));
        const view: SearchView = { state: options.length ? "results" : "no-results", options, retry: "none", busy: false, query: q };
        this.last = view;
        this.lastLang = lang;
        this.emit(view);
        this.announce(options.length ? { kind: "count", count: options.length } : { kind: "message", state: "no-results" });
        const first = options[0];
        if (this.selectOnArrival && first) this.autoSelect(first);
        this.selectOnArrival = false;
        return;
      }
      case "rateLimited":
        this.cooldown.start(r.retryAfterS, () => {
          // After N s the button turns active; nothing is sent automatically (AC 35).
          if (this.v.state === "rate-limited") this.emit({ ...this.v, retry: "enabled" });
        });
        this.showMessage("rate-limited", q);
        return;
      case "unavailable":
        this.showMessage("unavailable", q);
        return;
      case "badRequest":
        this.showMessage("error", q);
        return;
      case "offline":
        this.waitingOnline = true;
        this.showMessage("offline", q);
        return;
      case "aborted":
        return;
    }
  }

  private showMessage(state: MessageState, q: string): void {
    this.selectOnArrival = false;
    const retry: RetryState =
      state === "unavailable" ? "enabled" : state === "rate-limited" ? (this.cooldown.active ? "disabled" : "enabled") : "none";
    this.emit({ state, options: [], retry, busy: false, query: q });
    this.announce({ kind: "message", state });
  }

  private autoSelect(o: SearchOption): void {
    this.selectOnArrival = false;
    for (const l of this.selectListeners) l(o);
  }

  private cancelInflight(): void {
    this.gen += 1;
    this.inflight?.abort();
    this.inflight = null;
    this.inflightQuery = null;
    this.clearLoadingTimer();
  }

  private clearDebounce(): void {
    if (this.debounceTimer !== null) this.timers.clearTimeout(this.debounceTimer);
    this.debounceTimer = null;
  }

  private clearLoadingTimer(): void {
    if (this.loadingTimer !== null) this.timers.clearTimeout(this.loadingTimer);
    this.loadingTimer = null;
  }

  private emit(v: SearchView): void {
    this.v = v;
    for (const l of this.viewListeners) l(v);
  }

  private announce(a: Announcement): void {
    for (const l of this.announceListeners) l(a);
  }
}

/** ADR-0006 §3 "Combining a parallel pair". */
export function combineParallel(a: Outcome, b: Outcome): Outcome {
  if (a.kind === "rateLimited" || b.kind === "rateLimited") {
    const s = Math.max(a.kind === "rateLimited" ? a.retryAfterS : 0, b.kind === "rateLimited" ? b.retryAfterS : 0);
    return { kind: "rateLimited", retryAfterS: s };
  }
  if (a.kind === "ok" || b.kind === "ok") {
    const pa = a.kind === "ok" ? a.features : [];
    const pb = b.kind === "ok" ? b.features : [];
    return { kind: "ok", features: mergeFeatures(pa, pb) };
  }
  return a;
}

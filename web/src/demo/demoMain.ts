// NAV-017 web demo mode (demo-mode build only; ADR-0011): route picker → simulated turn-by-turn replay of a recorded
// Ulaanbaatar route → arrival, on the NAV-002 map shell. Reached only through the compile-time branch in src/main.ts,
// so no other build contains this module, its CSS, the Ferrostar WASM or the route data (AC 4).
// Specs: docs/design/screens/NAV-017-web-demo-mode.md, flows/NAV-017-web-demo-mode.md, navigation-ux.md §11,
// map-style.md §7.5. 0 backend requests and no Geolocation call anywhere in this build (AC 14, 42).
import "./demo.css";
import type { Map as MapLibreMap } from "maplibre-gl";
import routeSummaries from "virtual:navmn-demo-routes";
import wasmUrl from "@stadiamaps/ferrostar/ferrostar_bg.wasm?url";
import { FerrostarNavigator } from "../guidance/ferrostarCore";
import { latLonOf, type Fix } from "../guidance/fix";
import { bearing, distance } from "../guidance/geo";
import { GuidanceCore, type GuidanceState } from "../guidance/guidanceCore";
import { ReplayClock, ReplayEngine } from "../guidance/replay";
import type { I18n, Lang } from "../i18n/i18n";
import { LocationController } from "../location/locationController";
import { colourGroup, type Theme } from "../style/tokens";
import { App, type AppDeps } from "../ui/app";
import { AudioOut, loadMuted, saveMuted } from "./audio";
import { follow, followPadding, RESET_MS, START_MS, ZoomBySpeed, type Covered } from "./camera";
import { el } from "./dom";
import { GuidanceView } from "./guidanceView";
import { DemoMapLayers } from "./mapLayers";
import { PickerView, placeName } from "./picker";
import { RouteDataLoader, type DemoRouteSummary, type PreparedRoute } from "./routeData";
import { ScreenWakeLock } from "./wakeLock";

const LOADING_DELAY_MS = 300; // motion.loading-delay
const RECENTER_TIMEOUT_MS = 15_000; // motion.nav-recenter-timeout
const ARRIVAL_TAIL_MS = 5_000;
const FIT_MAX_ZOOM = 17;
const FIT_MS = 700; // motion.route-camera
/** Text zoom from which the banner stacks (navigation-ux §11.3). */
const STACK_REM_PX = 16 * 1.15;
const COLUMNS_QUERY = "(min-width: 840px), (orientation: landscape) and (max-height: 499px)";

const reducedMotion = (): boolean => window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;

/** nav.* and demo.* token colours as custom properties (day on :root, night on [data-theme="night"]). */
function demoTokenCss(): string {
  const decl = (theme: Theme) =>
    [
      ...Object.entries(colourGroup(theme, "nav")).map(([k, v]) => `--nav-${k}:${v}`),
      ...Object.entries(colourGroup(theme, "demo")).map(([k, v]) => `--demo-${k}:${v}`),
    ].join(";");
  return `:root{${decl("day")}}\n:root[data-theme="night"]{${decl("night")}}\n`;
}

interface Replay {
  prepared: PreparedRoute;
  core: GuidanceCore;
  engine: ReplayEngine;
  zoom: ZoomBySpeed;
  following: boolean;
  recenterTimer: number | null;
  course: number;
  lastFix: Fix | null;
  arrivedAt: number | null;
  state: GuidanceState | null;
}

export function startDemoMode(deps: AppDeps): DemoController {
  const style = document.createElement("style");
  style.id = "demo-tokens";
  style.textContent = demoTokenCss();
  document.head.append(style);
  const layers = new DemoMapLayers();
  const app = new App({
    ...deps,
    searchAndRoute: false,
    styleTransform: (s) => layers.transform(s),
    // AC 14 / screen spec Design notes 2: no Geolocation anywhere in the demo-mode build.
    createLocation: (camera) => new LocationController(undefined, false, camera),
  });
  el("ui").classList.add("demo-build");
  app.start();
  const c = new DemoController(app, deps.i18n, layers, routeSummaries);
  c.start();
  return c;
}

export class DemoController {
  private readonly picker: PickerView;
  private readonly view: GuidanceView;
  private readonly loader: RouteDataLoader;
  private readonly audio: AudioOut;
  private readonly wakeLock: ScreenWakeLock;
  private readonly ui = el("ui");
  private readonly slot = el("route");
  private readonly cluster: HTMLElement;
  private readonly langBtn = el("lang-btn");
  private readonly themeBtn = el("theme-btn");
  private pickerShown = false;
  private selected = -1;
  private prepared: PreparedRoute | null = null;
  private abort: AbortController | null = null;
  private loadingTimer: number | null = null;
  private waitingOnline = false;
  private replay: Replay | null = null;
  private muted: boolean;
  private readonly onVisibility = () => this.visibilityChanged();
  private readonly onAnyTap = () => this.audio.ensureUnlocked(this.i18n.lang);
  private readonly onResize = () => this.layoutChanged();

  constructor(
    private readonly app: App,
    private readonly i18n: I18n,
    private readonly layers: DemoMapLayers,
    private readonly routes: readonly DemoRouteSummary[],
  ) {
    this.cluster = this.langBtn.parentElement as HTMLElement;
    this.picker = new PickerView(i18n, routes, { select: (i) => this.select(i), retry: () => this.retry(), start: () => this.startReplay() });
    this.view = new GuidanceView(i18n, {
      recenter: () => this.recenter(),
      toggleVoice: () => this.toggleVoice(),
      end: () => this.endReplay(),
      close: () => this.endReplay(),
      dismissNotice: () => this.replay?.core.dismissVoiceNotice(),
    });
    this.loader = new RouteDataLoader({ fetch: (u, i) => window.fetch(u, i), baseUri: () => document.baseURI, wasmUrl });
    this.audio = new AudioOut({
      speechSynthesis: "speechSynthesis" in window ? window.speechSynthesis : undefined,
      SpeechSynthesisUtterance: typeof SpeechSynthesisUtterance === "function" ? SpeechSynthesisUtterance : undefined,
      AudioContext: window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext,
      timers: { setTimeout: (f, ms) => window.setTimeout(f, ms), clearTimeout: (h) => window.clearTimeout(h as number) },
      storage: safeStorage(),
    });
    this.wakeLock = new ScreenWakeLock((navigator as unknown as { wakeLock?: ConstructorParameters<typeof ScreenWakeLock>[0] }).wakeLock);
    this.muted = loadMuted(safeStorage());
  }

  private get map(): MapLibreMap | null {
    return this.app.map;
  }

  start(): void {
    this.ui.insertBefore(this.view.root, el("map"));
    if (this.map) this.layers.attach(this.map);
    this.layers.reducedMotion = reducedMotion();
    const status = this.app.statusMachine;
    const check = () => {
      if (!this.pickerShown && status.state.ready) this.showPicker();
      this.onlineChanged(status.state.online);
    };
    status.onChange(check);
    check();
    this.i18n.onChange((lang) => this.langChanged(lang));
    this.observeTextZoom();
    window.addEventListener("resize", this.onResize);
    this.bindGestures();
  }

  // ---------------------------------------------------------------- picker (D1)

  private showPicker(): void {
    this.pickerShown = true;
    if (!this.picker.section.isConnected) this.slot.append(this.picker.section);
    this.slot.hidden = false;
    this.ui.classList.add("route-open", "demo-picker-open");
    this.ui.classList.remove("demo-replay");
    void this.audio.decide(); // AC 28: decided before «Эхлэх»
  }

  private select(i: number): void {
    if (this.replay) return;
    const s = this.routes[i];
    if (!s) return;
    const state = this.pickerState;
    if (i === this.selected && state !== "error" && state !== "offline") return;
    this.selected = i;
    this.picker.setSelected(i);
    this.cancelLoad();
    this.prepared = null;
    this.picker.setStartEnabled(false);
    this.layers.setRoute(null);
    const hit = this.loader.cached(s.id);
    if (hit) {
      this.setPickerState("none");
      this.show(hit);
      return;
    }
    if (!this.app.statusMachine.state.online) {
      this.waitingOnline = true;
      this.setPickerState("offline");
      return;
    }
    this.load(s);
  }

  private retry(): void {
    const s = this.routes[this.selected];
    if (s && !this.replay) this.load(s);
  }

  private pickerState: "none" | "loading" | "error" | "offline" = "none";
  private setPickerState(s: "none" | "loading" | "error" | "offline"): void {
    const hadRetryFocus = document.activeElement === this.picker.retryBtn;
    this.pickerState = s;
    this.picker.setState(s);
    if (hadRetryFocus && s !== "error" && s !== "loading") this.picker.focusSelectedRow();
  }

  private cancelLoad(): void {
    this.abort?.abort();
    this.abort = null;
    if (this.loadingTimer !== null) window.clearTimeout(this.loadingTimer);
    this.loadingTimer = null;
    this.waitingOnline = false;
  }

  private load(s: DemoRouteSummary): void {
    this.cancelLoad();
    const abort = new AbortController();
    this.abort = abort;
    if (this.pickerState !== "error") this.setPickerState("none");
    this.loadingTimer = window.setTimeout(() => {
      this.loadingTimer = null;
      if (!abort.signal.aborted) this.setPickerState("loading");
    }, LOADING_DELAY_MS);
    this.loader.load(s, abort.signal).then(
      (p) => {
        if (abort.signal.aborted) return;
        this.cancelLoad();
        this.setPickerState("none");
        this.show(p);
      },
      (err: unknown) => {
        if (abort.signal.aborted || (err instanceof DOMException && err.name === "AbortError")) return;
        this.cancelLoad();
        // A failed request while offline is the offline row (loads by itself when back), otherwise AC 10.
        if (!this.app.statusMachine.state.online) {
          this.waitingOnline = true;
          this.setPickerState("offline");
        } else {
          this.setPickerState("error");
        }
      },
    );
  }

  private names(p: { origin: DemoRouteSummary["origin"]; destination: DemoRouteSummary["destination"] }) {
    return { origin: placeName(p.origin, this.i18n).text, destination: placeName(p.destination, this.i18n).text };
  }

  /** AC 9: route line, markers, camera fit over the uncovered map, «Эхлэх» enabled. */
  private show(p: PreparedRoute): void {
    this.prepared = p;
    this.layers.setPhase("picker");
    this.layers.setRoute(p.line, this.names(p.summary));
    this.picker.setStartEnabled(true);
    const m = this.map;
    if (!m) return;
    let w = Infinity, s = Infinity, e = -Infinity, n = -Infinity;
    for (const [lng, lat] of p.line) {
      w = Math.min(w, lng);
      e = Math.max(e, lng);
      s = Math.min(s, lat);
      n = Math.max(n, lat);
    }
    const pad = this.pickerPadding();
    m.fitBounds(
      [
        [w, s],
        [e, n],
      ],
      { padding: pad, maxZoom: FIT_MAX_ZOOM, duration: reducedMotion() ? 0 : FIT_MS, bearing: m.getBearing(), pitch: 0 },
    );
  }

  private pickerPadding(): { top: number; bottom: number; left: number; right: number } {
    const m = this.map!;
    const box = m.getContainer().getBoundingClientRect();
    const r1 = (document.querySelector(".r1") as HTMLElement).getBoundingClientRect();
    const r2 = el("messages").getBoundingClientRect();
    const sheet = this.picker.section.getBoundingClientRect();
    const wide = window.matchMedia("(min-width: 840px)").matches;
    const top = Math.max(r1.bottom, r2.height > 0 ? r2.bottom : 0) - box.top;
    const attr = el("attribution").getBoundingClientRect();
    const bottom = wide ? box.bottom - attr.top : box.bottom - sheet.top + 20;
    const left = wide ? sheet.right - box.left : 0;
    const clamp = (v: number, max: number) => Math.max(0, Math.min(v, max));
    return { top: clamp(40 + top, box.height / 2), bottom: clamp(40 + bottom, box.height / 2), left: clamp(40 + left, box.width / 2), right: 64 };
  }

  private onlineChanged(online: boolean): void {
    this.view.setOffline(!online);
    if (online && this.waitingOnline && !this.replay) {
      const s = this.routes[this.selected];
      this.waitingOnline = false;
      if (s) this.load(s);
    }
  }

  // ---------------------------------------------------------------- replay (D2/D3)

  /** «Эхлэх» (AC 12, 27, 39): synchronous up to the first prompt, so iOS allows later speech and sound. */
  private startReplay(): void {
    const p = this.prepared;
    if (!p || this.replay) return;
    this.picker.setStartEnabled(false); // a second tap starts nothing
    const lang = this.i18n.lang;
    this.audio.resetReplay();
    this.audio.unlockForStart();
    const navigator_ = new FerrostarNavigator(p.core, p.ferrostarRoute);
    const clock = new ReplayClock({ now: () => performance.now() });
    const ref: { core?: GuidanceCore } = {};
    const speaker = this.audio.speaker({ done: (id) => ref.core?.onSpeakerDone(id), fallback: () => ref.core?.onVoiceFallback() });
    const core = new GuidanceCore({
      clock,
      plan: p.plan,
      navigator: navigator_,
      mode: p.summary.mode,
      speaker,
      t: (l: Lang) => (k) => this.i18n.t(k, l),
      lang,
      muted: this.muted,
      onState: (s) => this.onState(s),
    });
    ref.core = core;
    const engine = new ReplayEngine(p.fixes, core, clock, { setInterval: (f, ms) => window.setInterval(f, ms), clearInterval: (h) => window.clearInterval(h as number) }, {
      onFix: (f) => this.onFix(f),
      onTick: () => this.onTick(),
      onTrackEnd: () => this.endReplay(),
    });
    const r: Replay = { prepared: p, core, engine, zoom: new ZoomBySpeed(p.summary.mode === "walk"), following: true, recenterTimer: null, course: p.fixes[0]?.bearingDeg ?? 0, lastFix: null, arrivedAt: null, state: null };
    this.replay = r;
    this.enterGuidanceLayout(p);
    engine.start(); // core.start → the depart prompt (speak or chime) inside this handler
    this.wakeLock.request();
    this.voiceCheck();
    document.addEventListener("visibilitychange", this.onVisibility);
    document.addEventListener("pointerdown", this.onAnyTap, true);
    this.view.instruction.focus({ preventScroll: true });
  }

  /** A1 when the UI language has no usable voice (decided now, or when the 3 s decision ends). */
  private voiceCheck(): void {
    const r = this.replay;
    if (!r) return;
    const lang = this.i18n.lang;
    const v = this.audio.voiceFor(lang);
    if (v === undefined) {
      void this.audio.decide().then(() => this.voiceCheck());
      return;
    }
    if (!this.audio.speaks(lang)) r.core.onVoiceFallback();
  }

  private enterGuidanceLayout(p: PreparedRoute): void {
    this.ui.classList.remove("route-open", "demo-picker-open");
    this.ui.classList.add("demo-replay");
    this.slot.hidden = true;
    this.view.rowButtons.append(this.langBtn, this.themeBtn);
    this.view.open(placeName(p.summary.destination, this.i18n));
    this.layers.setPhase("replay");
  }

  private leaveGuidanceLayout(): void {
    this.cluster.prepend(this.langBtn, this.themeBtn);
    this.view.close_();
    this.ui.classList.remove("demo-replay");
  }

  private onState(s: GuidanceState): void {
    const r = this.replay;
    if (!r) return;
    const wasArrived = r.state?.phase === "arrived";
    r.state = s;
    if (s.phase === "ended") return;
    this.view.render(s, performance.now());
    if (s.phase === "arrived" && !wasArrived) {
      r.arrivedAt = r.engine.clock.elapsedMs();
      this.wakeLock.release(); // AC 39: released within 1 s after the replay ends
      this.view.setFollowing(true);
    }
  }

  private onFix(f: Fix): void {
    const r = this.replay;
    if (!r) return;
    const prev = r.lastFix;
    r.lastFix = f;
    // AC 22: bearing = course between consecutive fixes, kept below 1 m/s.
    if (prev) {
      const dt = Math.max(0.001, (f.elapsedMs - prev.elapsedMs) / 1000);
      if (distance(latLonOf(prev), latLonOf(f)) / dt >= 1) r.course = bearing(latLonOf(prev), latLonOf(f));
    }
    const puck = r.core.state().puck;
    if (!puck) return;
    if (!prev) this.layers.placePuck(puck.position, r.course);
    const target = prev ? this.layers.movePuck(puck.position, r.course) : puck.position;
    if (r.following) {
      const m = this.map;
      if (m) follow(m, target, r.course, r.zoom.update(r.core.state().speedMps), this.followPadding(), prev ? 1000 : START_MS, reducedMotion());
    }
  }

  private onTick(): void {
    const r = this.replay;
    if (!r || !r.state) return;
    this.view.render(r.core.state(), performance.now());
    // After arrival the queue may still play the arrival prompt (≤ 3 s wait); then the replay clock stops (AC 33).
    if (r.arrivedAt !== null && (r.core.queue.closed || r.engine.clock.elapsedMs() - r.arrivedAt > ARRIVAL_TAIL_MS)) r.engine.stop();
  }

  private covered(): Covered {
    const m = this.map!;
    const box = m.getContainer().getBoundingClientRect();
    const top = this.view.top.getBoundingClientRect();
    const bottom = this.view.bottom.getBoundingClientRect();
    if (window.matchMedia(COLUMNS_QUERY).matches) {
      return { top: 0, bottom: Math.max(0, box.bottom - bottom.top), left: Math.max(0, top.right - box.left), right: 0 };
    }
    return { top: Math.max(0, top.bottom - box.top), bottom: Math.max(0, box.bottom - bottom.top), left: 0, right: 0 };
  }

  private followPadding() {
    const m = this.map!;
    return followPadding(m.getContainer().clientHeight, this.covered());
  }

  private resumeFollowing(ms: number): void {
    const r = this.replay;
    const m = this.map;
    if (!r || !m) return;
    r.following = true;
    if (r.recenterTimer !== null) window.clearTimeout(r.recenterTimer);
    r.recenterTimer = null;
    this.view.setFollowing(true);
    const target = this.layers.puckTarget ?? this.layers.puckPosition;
    if (target) follow(m, target, r.course, r.zoom.update(r.core.state().speedMps), this.followPadding(), ms, reducedMotion());
  }

  private recenter(): void {
    this.audio.ensureUnlocked(this.i18n.lang);
    this.resumeFollowing(START_MS);
    this.view.instruction.focus({ preventScroll: true });
  }

  /** AC 23: a user map gesture stops following; recenter appears; 15 s later following resumes by itself. */
  private userGesture(): void {
    const r = this.replay;
    if (!r) return;
    if (r.following) {
      r.following = false;
      this.view.setFollowing(false);
    }
    this.armRecenterTimer();
  }

  private armRecenterTimer(): void {
    const r = this.replay;
    if (!r || r.following) return;
    if (r.recenterTimer !== null) window.clearTimeout(r.recenterTimer);
    r.recenterTimer = window.setTimeout(() => {
      if (this.replay === r) this.resumeFollowing(START_MS);
    }, RECENTER_TIMEOUT_MS);
  }

  private bindGestures(): void {
    const m = this.map;
    if (!m) return;
    const onUser = (e: { originalEvent?: unknown }) => {
      if (e.originalEvent) this.userGesture();
    };
    m.on("dragstart", onUser);
    m.on("zoomstart", onUser);
    m.on("rotatestart", onUser);
    m.on("pitchstart", onUser);
    m.on("movestart", onUser);
  }

  private toggleVoice(): void {
    this.muted = !this.muted;
    saveMuted(safeStorage(), this.muted);
    if (!this.muted) this.audio.ensureUnlocked(this.i18n.lang);
    this.replay?.core.setMuted(this.muted);
  }

  /** «Дуусгах», «Хаах», track end (AC 33, 35, 16). */
  private endReplay(): void {
    const r = this.replay;
    if (!r) return;
    this.replay = null;
    if (r.recenterTimer !== null) window.clearTimeout(r.recenterTimer);
    r.engine.stop();
    r.core.end();
    this.audio.stop();
    this.wakeLock.release();
    document.removeEventListener("visibilitychange", this.onVisibility);
    document.removeEventListener("pointerdown", this.onAnyTap, true);
    this.layers.setRoute(null);
    this.layers.setPhase("picker");
    this.leaveGuidanceLayout();
    const m = this.map;
    if (m) {
      m.easeTo({ bearing: 0, pitch: 0, padding: { top: 0, bottom: 0, left: 0, right: 0 }, duration: reducedMotion() ? 0 : RESET_MS });
    }
    this.selected = -1;
    this.prepared = null;
    this.picker.setSelected(-1);
    this.setPickerState("none");
    this.picker.setStartEnabled(false);
    this.showPicker();
    this.picker.heading.focus({ preventScroll: true });
  }

  private visibilityChanged(): void {
    const r = this.replay;
    if (!r) return;
    if (document.visibilityState === "hidden") {
      // AC 15: clock paused, utterance or chime stopped, nothing produced while hidden.
      r.engine.pause();
      r.core.silence();
      this.audio.suspend();
    } else {
      r.engine.resume();
      this.audio.resume();
      this.wakeLock.onVisible();
      if (!r.following) this.armRecenterTimer();
    }
  }

  private langChanged(lang: Lang): void {
    this.picker.render();
    this.view.renderStatic();
    const p = this.prepared;
    if (p && !this.replay) this.layers.renameMarkers(this.names(p.summary));
    const r = this.replay;
    if (!r) return;
    // AC 31: current utterance stops, next prompt in the new language and voice; A1 if newly needed.
    r.core.setLanguage(lang);
    this.audio.ensureUnlocked(lang);
    void this.audio.decide().then(() => this.voiceCheck());
    this.layers.renameMarkers(this.names(r.prepared.summary));
  }

  private layoutChanged(): void {
    const r = this.replay;
    if (r?.following) this.resumeFollowing(0);
  }

  /** Banner stacked layout from 115 % text zoom (navigation-ux §11.3): measured with a 1rem probe. */
  private observeTextZoom(): void {
    const probe = document.createElement("div");
    probe.className = "demo-rem-probe";
    probe.setAttribute("aria-hidden", "true");
    document.body.append(probe);
    const apply = () => {
      this.view.root.dataset.stacked = String(probe.getBoundingClientRect().height >= STACK_REM_PX);
      if (this.replay?.following) this.resumeFollowing(0);
    };
    apply();
    if (typeof ResizeObserver === "function") new ResizeObserver(apply).observe(probe);
  }
}

function safeStorage(): Storage | undefined {
  try {
    return window.localStorage;
  } catch {
    return undefined;
  }
}

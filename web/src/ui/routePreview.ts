// NAV-004 route preview in the web demo: panel, fields, mode tabs, avoid switch, result region (states, summary,
// route options, turn list), route layers and markers, camera, and the coordinate card shown during the preview.
// Story: docs/requirements/stories/NAV-004-route-preview-web.md (AC 1–54). Screen spec: docs/design/screens/
// NAV-004-route-preview.md; flow: docs/design/flows/NAV-004-route-preview.md; map style §7.2–7.3; ADR-0008;
// openapi.yaml 0.5.0 postRoute. Request lifecycle: src/route/routeController.ts. Turn text: src/route/instructions.ts.
// Never writes coordinates, request bodies or the device position to the console, storage or the URL (AC 52).
import { Marker, type GeoJSONSource, type Map as MapLibreMap, type MapMouseEvent } from "maplibre-gl";
import type { AppConfig } from "../config";
import type { I18n, MessageKey } from "../i18n/i18n";
import type { Fix, LocationController, LocationView } from "../location/locationController";
import { formatCoordinate, type LatLon } from "../search/coords";
import { resultInfo } from "../search/display";
import { GatewayClient } from "../search/gateway";
import { ReverseController, type NearView } from "../search/reverseController";
import { SearchController } from "../search/searchController";
import { refusingFetch, UNAVAILABLE_CLIENT } from "../search/unavailableClient";
import { computeEta, formatDistance, formatDuration, formatEta, formatNextDay, formatRouteCount } from "../route/format";
import { instructionText, maneuverKey, streetName } from "../route/instructions";
import type { OsrmRoute, OsrmRouteResponse } from "../route/osrm";
import { RouteClient } from "../route/routeClient";
import {
  MODES,
  RouteController,
  snapNoticeDistance,
  type Mode,
  type PointLabel,
  type RouteAnnouncement,
  type RouteEnd,
  type RouteState,
  type RouteView,
} from "../route/routeController";
import {
  boundsOf,
  EMPTY_ROUTE_STYLE,
  ROUTE_HIT_LAYER_ID,
  ROUTE_SEL_CASING_LAYER_ID,
  ROUTE_SEL_LAYER_ID,
  ROUTE_SOURCE_ID,
  ROUTE_STEP_SOURCE_ID,
  routeGeometries,
  routeLineData,
  stepPointData,
  type RouteStyleData,
} from "../route/routeLayers";
import { ICONS } from "./icons";
import { RouteField, type FieldPick, type FieldList } from "./routeField";
import { maneuverIcon, ROUTE_ICONS } from "./routeIcons";
import { partLang, STATE_ROW } from "./searchBox";
import type { SearchFeature } from "./searchFeature";
import type { Tooltip } from "./tooltip";
import "./routePreview.css";

const CAMERA_PADDING_PX = 40; // AC 17
const CONTROL_COLUMN_PX = 64; // screen spec › Camera rules: R4 control column (48 + 16)
const MIN_FIT_AREA_PX = 80;
const FIT_MAX_ZOOM = 17; // AC 17
const STEP_ZOOM = 17; // AC 30
const DURATION_CAMERA = 1000; // tokens: motion.duration-camera
const ETA_REFRESH_MS = 60_000; // tokens: motion.route-eta-refresh (AC 25)
const LOCATION_WAIT_MS = 10_000; // AC 5
/** "Location is on": last fix at most this old (NAV-003 AC 9 freshness rule; NAV-004 Terms). */
export const FIX_MAX_AGE_MS = 60_000;

const prefersReducedMotion = (): boolean => window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
const isExpanded = (): boolean => window.matchMedia?.("(min-width: 840px)").matches ?? false;

export interface RoutePreviewDeps {
  cfg: AppConfig;
  i18n: I18n;
  tooltip: Tooltip;
  map: () => MapLibreMap | null;
  search: SearchFeature;
  /** Search bias for the fields (NAV-003 AC 9). */
  bias: () => LatLon;
  /** The last fix when location is on (activated, fix ≤ 60 s old), else null. Never calls the Geolocation API. */
  freshFix: () => Fix | null;
  location: () => LocationController | undefined;
  stopFollowing: () => void;
  mapReady: () => boolean;
  fetch?: typeof fetch;
}

function el<T extends HTMLElement = HTMLElement>(id: string): T {
  const e = document.getElementById(id);
  if (!e) throw new Error(`missing #${id}`);
  return e as T;
}

/** Small element factory (no text: every string comes from the resource files). */
function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Record<string, string> = {}, ...children: (Node | null)[]): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else e.setAttribute(k, v);
  }
  for (const c of children) if (c) e.append(c);
  return e;
}

function iconSpan(svg: string, cls = "icon"): HTMLSpanElement {
  const s = h("span", { class: cls, "aria-hidden": "true" });
  s.innerHTML = svg;
  return s;
}

/** Message key and icon of a result-region state row (screen spec › States › Result region). */
const ROUTE_STATE_ROW: Partial<Record<RouteState, { key: MessageKey; icon: string | null }>> = {
  loading: { key: "status.loading", icon: null },
  "no-route": { key: "route.noRoute", icon: ROUTE_ICONS.noRoute },
  "out-of-area": { key: "route.outOfArea", icon: ICONS.warning },
  "too-far": { key: "route.tooFar", icon: ROUTE_ICONS.info },
  "same-point": { key: "route.samePoint", icon: ROUTE_ICONS.pin },
  unavailable: { key: "route.unavailable", icon: ICONS.warning },
  static: { key: "route.unavailable", icon: ICONS.warning },
  offline: { key: "status.offline", icon: ICONS.cloudOff },
  "rate-limited": { key: "route.rateLimited", icon: ICONS.hourglass },
  error: { key: "status.genericError", icon: ICONS.error },
};

const MODE_KEY: Record<Mode, MessageKey> = { car: "route.mode.car", walk: "route.mode.walk", bike: "route.mode.bike" };
const MODE_ICON: Record<Mode, string> = { car: ROUTE_ICONS.car, walk: ROUTE_ICONS.walk, bike: ROUTE_ICONS.bike };

type FocusMark = { kind: "option" | "step"; index: number } | { kind: "retry" } | null;

export class RoutePreview {
  readonly controller: RouteController;
  readonly client: RouteClient;
  /** Current route layer data (map-style §7.2), also used by App.style() so a style switch keeps it (AC 20). */
  styleData: RouteStyleData = EMPTY_ROUTE_STYLE;

  private readonly ui = el("ui");
  private readonly slot = el("route");
  private readonly live: HTMLElement;
  private readonly panel: HTMLElement;
  private readonly title: HTMLHeadingElement;
  private readonly closeBtn: HTMLButtonElement;
  private readonly swapBtn: HTMLButtonElement;
  private readonly originInput: HTMLInputElement;
  private readonly destInput: HTMLInputElement;
  private readonly tabs: Record<Mode, HTMLButtonElement>;
  private readonly tabpanel: HTMLElement;
  private readonly avoidBtn: HTMLButtonElement;
  private readonly result: HTMLElement;
  private readonly fields: { origin: RouteField; destination: RouteField };
  private readonly fieldList: FieldList;
  private readonly pointCard: PointCard;
  private readonly reverse: ReverseController;

  private geometries: [number, number][][] = [];
  private renderedResponse: OsrmRouteResponse | null = null;
  private renderedSelected = -1;
  private activeStep = -1;
  private focusedStep = 0;
  private etaTimer: ReturnType<typeof setInterval> | null = null;
  /** Clock base of «Хүрэх цаг»: the response arrival, then the time of each 60 s refresh (AC 25). */
  private etaBase = 0;
  private originMarker: Marker | null = null;
  private readonly originMarkerEl: HTMLElement;
  private openedFrom: "card" | "search" = "search";
  private locationWait: (() => void) | null = null;
  private announceTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly deps: RoutePreviewDeps) {
    const isOnline = (): boolean => navigator.onLine;
    const f = deps.fetch ?? ((input: RequestInfo | URL, init?: RequestInit) => window.fetch(input, init));
    const features = deps.cfg.features;
    // Static public build (AC 53–54): features.routing is false; the client never builds or sends a request, and its
    // fetch is a refusing stub as a second guard.
    this.client = new RouteClient({ baseUrl: deps.cfg.gatewayBaseUrl, fetch: features.routing ? f : refusingFetch, isOnline, enabled: features.routing });
    this.controller = new RouteController({ client: this.client, lang: () => deps.i18n.lang, isOnline });

    // Fields search like the NAV-003 box (AC 6), each with its own controller; static build: search off.
    const searchClient = features.search || features.reverse ? deps.search.client : new GatewayClient({ baseUrl: deps.cfg.gatewayBaseUrl, fetch: refusingFetch, isOnline });
    const fieldController = () =>
      new SearchController({ client: features.search ? searchClient : UNAVAILABLE_CLIENT, lang: () => deps.i18n.lang, bias: deps.bias, isOnline });
    this.reverse = new ReverseController({ client: features.reverse ? searchClient : UNAVAILABLE_CLIENT, lang: () => deps.i18n.lang, isOnline });

    this.live = h("div", { id: "route-live", class: "sr-only", "aria-live": "polite", "data-testid": "route-live" });
    document.body.append(this.live);

    // ---- panel DOM (screen spec › Components; DOM order = Tab order)
    this.title = h("h2", { id: "route-title", tabindex: "-1", "data-testid": "route-title", "data-i18n": "route.title" });
    this.closeBtn = h("button", { type: "button", class: "btn flat", id: "route-close", "data-testid": "route-close", "data-i18n-aria-label": "action.close", "data-tooltip": "action.close" });
    this.closeBtn.innerHTML = ICONS.close;
    const head = h("div", { class: "rp-head" }, this.title, this.closeBtn);

    const mkInput = (which: "origin" | "destination") =>
      h("input", {
        id: `route-${which}`,
        type: "text",
        role: "combobox",
        "aria-autocomplete": "list",
        "aria-expanded": "false",
        "aria-controls": `route-${which}-results`,
        maxlength: "200",
        autocomplete: "off",
        autocapitalize: "off",
        autocorrect: "off",
        spellcheck: "false",
        enterkeyhint: "search",
        "data-testid": `route-${which}`,
        "data-i18n-aria-label": which === "origin" ? "route.origin" : "route.destination",
        "data-i18n-placeholder": which === "origin" ? "route.originPlaceholder" : "route.destinationPlaceholder",
      });
    this.originInput = mkInput("origin");
    this.destInput = mkInput("destination");
    const originField = h("div", { class: "rfield" }, iconSpan(ROUTE_ICONS.ring, "lead o"), this.originInput);
    const destField = h("div", { class: "rfield" }, iconSpan(ROUTE_ICONS.pin, "lead d"), this.destInput);
    this.swapBtn = h("button", { type: "button", class: "btn flat", id: "route-swap", "data-testid": "route-swap", "aria-disabled": "true", "data-i18n-aria-label": "route.swap", "data-tooltip": "route.swap" });
    this.swapBtn.innerHTML = ROUTE_ICONS.swap;
    const fieldsBlock = h("div", { class: "rp-fields" }, h("div", { class: "rp-fcol" }, originField, destField), this.swapBtn);

    const originList = h("ul", { id: "route-origin-results", role: "listbox", "data-i18n-aria-label": "search.results" });
    const destList = h("ul", { id: "route-destination-results", role: "listbox", "data-i18n-aria-label": "search.results" });
    const listStateText = h("span", { class: "txt", id: "route-field-state-text" });
    const listRetry = h("button", { type: "button", class: "act", id: "route-field-retry", "data-testid": "route-field-retry", "data-i18n": "action.retry", "aria-describedby": "route-field-state-text" });
    const listState = h("div", { class: "state", "data-testid": "route-field-state" }, iconSpan(""), listStateText, listRetry);
    listState.hidden = true;
    const listContainer = h("div", { class: "rp-list", id: "route-field-list", "data-testid": "route-field-list" }, originList, destList, listState);
    listContainer.hidden = true;
    this.fieldList = { container: listContainer, stateRow: listState, stateText: listStateText, retryBtn: listRetry };

    const tablist = h("div", { class: "rp-tabs", role: "tablist", "data-i18n-aria-label": "route.modes" });
    this.tabs = {} as Record<Mode, HTMLButtonElement>;
    for (const m of MODES) {
      const b = h("button", { type: "button", class: "tab", role: "tab", id: `route-tab-${m}`, "data-testid": `route-tab-${m}`, "aria-controls": "route-tabpanel", "aria-selected": "false", tabindex: "-1" });
      b.append(iconSpan(MODE_ICON[m], "ti"), h("span", { "data-i18n": MODE_KEY[m] }));
      this.tabs[m] = b;
      tablist.append(b);
    }
    this.avoidBtn = h(
      "button",
      { type: "button", class: "rp-avoid", role: "switch", "aria-checked": "false", id: "route-avoid", "data-testid": "route-avoid" },
      h("span", { class: "lbl", "data-i18n": "route.avoidUnpaved" }),
      h("span", { class: "track", "aria-hidden": "true" }, h("span", { class: "handle" })),
    );
    this.result = h("div", { class: "rp-result", id: "route-result", "data-testid": "route-result", "aria-busy": "false" });
    this.tabpanel = h("div", { role: "tabpanel", id: "route-tabpanel" }, this.avoidBtn, this.result);

    this.panel = h("section", { class: "route-panel", id: "route-panel", "data-testid": "route-panel", "aria-labelledby": "route-title", "data-state": "empty" }, head, fieldsBlock, listContainer, tablist, this.tabpanel);

    this.pointCard = new PointCard(deps.i18n, this.reverse, deps.tooltip, deps.map, {
      set: (which, p) => this.setFromCard(which, p),
      closed: () => this.closePointCard(),
    });
    this.slot.append(this.panel, this.pointCard.section);

    this.originMarkerEl = h("div", { class: "route-origin-marker", role: "img", "data-testid": "route-origin-marker" });

    // ---- fields
    const fieldDeps = (which: "origin" | "destination", input: HTMLInputElement, listbox: HTMLUListElement) => ({
      which,
      i18n: deps.i18n,
      input,
      listbox,
      list: this.fieldList,
      controller: fieldController(),
      live: el("search-live"),
      pointText: () => this.fieldText(which === "origin" ? this.controller.origin : this.controller.destination),
      onPick: (p: FieldPick) => this.onFieldPick(which, p),
      onOpen: () => {
        const other = which === "origin" ? this.fields.destination : this.fields.origin;
        other.closeQuietly();
        originList.hidden = which !== "origin";
        destList.hidden = which !== "destination";
      },
    });
    this.fields = {
      origin: new RouteField(fieldDeps("origin", this.originInput, originList)),
      destination: new RouteField(fieldDeps("destination", this.destInput, destList)),
    };
    listRetry.addEventListener("click", () => (this.fields.origin.isActive ? this.fields.origin : this.fields.destination).retry());

    this.bind();
    this.controller.onChange((v) => this.onView(v));
    this.controller.onAnnounce((a) => this.announce(a));
    window.addEventListener("online", () => {
      this.controller.online();
      this.reverse.online();
      this.fields.origin.online();
      this.fields.destination.online();
    });
    this.renderTabs();
    this.renderAvoid();
  }

  get isOpen(): boolean {
    return this.controller.open;
  }

  /** Called once the map exists. */
  attachMap(map: MapLibreMap): void {
    map.on("click", (e: MapMouseEvent) => this.onMapClick(map, e));
    map.on("mousemove", (e: MapMouseEvent) => {
      if (!this.isOpen || !map.getLayer(ROUTE_HIT_LAYER_ID)) return;
      const hit = map.queryRenderedFeatures(e.point, { layers: [ROUTE_HIT_LAYER_ID] }).length > 0;
      map.getCanvas().style.cursor = hit ? "pointer" : "";
    });
    // Right-click / long-press during the preview opens the preview-time coordinate card (AC 7, layout rule 7).
    this.deps.search.mapPointOverride = (p) => {
      if (!this.isOpen) return false;
      this.openPointCard(p);
      return true;
    };
  }

  // ------------------------------------------------------------------ open / close

  /** «Маршрут гаргах» on the NAV-003 place or coordinate card (AC 1–4). */
  openFromCard(): void {
    const sel = this.deps.search.card.selection;
    if (!sel) return;
    this.openedFrom = "card";
    const destination: RouteEnd = { point: { lat: sel.point.lat, lon: sel.point.lon }, label: sel.kind === "place" ? { kind: "name", text: sel.name } : { kind: "selected" } };
    this.open(destination);
  }

  private open(destination: RouteEnd): void {
    this.deps.tooltip.hide();
    const fix = this.deps.freshFix();
    // AC 3: location on → origin = the last fix, no Geolocation call. AC 4: otherwise empty, no prompt.
    const origin: RouteEnd | null = fix ? { point: { lat: fix.lat, lon: fix.lng }, label: { kind: "myLocation" } } : null;
    this.deps.search.controller.close();
    this.ui.classList.add("route-open");
    this.slot.hidden = false;
    this.panel.hidden = false;
    this.pointCard.close(false);
    this.controller.openWith(destination, origin);
    this.syncPoints();
    // AC 46: focus to the origin field when it is empty, otherwise to the selected mode tab.
    if (!origin) this.fields.origin.focus();
    else this.tabs[this.controller.mode].focus();
  }

  /** «Хаах» / Escape (AC 41). */
  close(): void {
    if (!this.isOpen) return;
    this.cancelLocationWait();
    this.deps.tooltip.hide();
    this.fields.origin.closeQuietly();
    this.fields.destination.closeQuietly();
    this.pointCard.close(false);
    this.controller.close();
    this.slot.hidden = true;
    this.ui.classList.remove("route-open");
    this.originMarker?.remove();
    this.originMarker = null;
    this.deps.search.card.restorePin();
    this.setStep(null);
    this.stopEta();
    const card = this.deps.search.card;
    const opener = document.getElementById("route-open");
    if (card.isOpen && opener && !card.wrapper.hidden) opener.focus();
    else document.getElementById("search-input")?.focus();
  }

  // ------------------------------------------------------------------ language, theme

  /** Language switch (AC 40, 49, 50): every label, message and instruction text switches; 0 requests. */
  renderI18n(): void {
    this.fields.origin.renderI18n();
    this.fields.destination.renderI18n();
    this.pointCard.renderI18n();
    this.syncMarkerNames();
    // Rebuild the result region in the new language; route, selection, activated turn row and camera stay.
    this.onView(this.controller.view, true);
  }

  /** The UI language changed (App.setLang): an open field list is re-requested once with the new `lang` (AC 6). */
  langChanged(): void {
    this.fields.origin.langChanged();
    this.fields.destination.langChanged();
  }

  // ------------------------------------------------------------------ events

  private bind(): void {
    const t = this.deps.tooltip;
    t.attach(this.closeBtn, "below");
    t.attach(this.swapBtn, "below");
    this.closeBtn.addEventListener("click", () => this.close());
    this.panel.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && !e.defaultPrevented && !e.isComposing) {
        e.preventDefault();
        this.close();
      }
    });
    this.swapBtn.addEventListener("click", () => {
      if (this.swapBtn.getAttribute("aria-disabled") === "true") return;
      if (this.controller.swap()) this.syncPoints();
    });
    for (const m of MODES) {
      this.tabs[m].addEventListener("click", () => this.selectMode(m, false));
      this.tabs[m].addEventListener("keydown", (e) => this.onTabKey(e, m));
    }
    this.avoidBtn.addEventListener("click", () => {
      this.controller.setAvoid(!this.controller.avoid);
      this.renderAvoid();
    });
    this.result.addEventListener("click", (e) => this.onResultClick(e));
    this.result.addEventListener("keydown", (e) => this.onResultKey(e));
    const opener = document.getElementById("route-open");
    opener?.addEventListener("click", () => this.openFromCard());
  }

  private selectMode(m: Mode, focus: boolean): void {
    this.controller.setMode(m);
    this.renderTabs();
    this.renderAvoid();
    if (focus) this.tabs[m].focus();
  }

  private onTabKey(e: KeyboardEvent, m: Mode): void {
    const i = MODES.indexOf(m);
    let next: number | null = null;
    if (e.key === "ArrowRight") next = (i + 1) % MODES.length;
    else if (e.key === "ArrowLeft") next = (i - 1 + MODES.length) % MODES.length;
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = MODES.length - 1;
    if (next === null) return;
    e.preventDefault();
    this.selectMode(MODES[next]!, true); // automatic activation; request after the 300 ms settle (AC 11)
  }

  private onFieldPick(which: "origin" | "destination", p: FieldPick): void {
    if (p.kind === "myLocation") {
      this.useMyLocation();
      return;
    }
    const end: RouteEnd =
      p.kind === "place"
        ? (() => {
            const info = resultInfo(p.feature);
            return { point: { lat: info.lat, lon: info.lon }, label: { kind: "name", text: info.name ?? this.deps.i18n.t(info.typeKey) } as PointLabel };
          })()
        : { point: { lat: p.point.lat, lon: p.point.lon }, label: { kind: "selected" } };
    if (which === "origin") this.controller.setOrigin(end);
    else this.controller.setDestination(end);
    this.syncPoints();
  }

  /** AC 5: «Миний байршил» picked in the origin field. */
  private useMyLocation(): void {
    const fix = this.deps.freshFix();
    if (fix) {
      this.controller.setOrigin({ point: { lat: fix.lat, lon: fix.lng }, label: { kind: "myLocation" } });
      this.syncPoints();
      return;
    }
    const loc = this.deps.location();
    if (!loc) return;
    this.cancelLocationWait();
    const before = loc.view.fix;
    let done = false;
    const finish = (fixOrNull: Fix | null): void => {
      if (done) return;
      done = true;
      this.cancelLocationWait();
      this.setWaitingForLocation(false);
      if (fixOrNull && this.isOpen) {
        this.controller.setOrigin({ point: { lat: fixOrNull.lat, lon: fixOrNull.lng }, label: { kind: "myLocation" } });
        this.syncPoints();
      } else if (this.isOpen) {
        // Denied or unavailable: the NAV-002 location message shows in R2; the origin stays empty, no request.
        this.fields.origin.syncText();
        this.fields.origin.focus();
      }
    };
    const unsubscribe = loc.onChange((v: LocationView) => {
      if (v.fix && v.fix !== before && !v.stale) finish(v.fix);
      else if (v.message === "denied" || v.message === "unavailable" || v.button === "denied" || v.button === "unsupported") finish(null);
    });
    const timer = setTimeout(() => finish(null), LOCATION_WAIT_MS);
    this.locationWait = () => {
      unsubscribe();
      clearTimeout(timer);
    };
    this.setWaitingForLocation(true);
    loc.press(); // this user action may show the browser prompt (AC 5)
    if (loc.view.button === "unsupported") finish(null);
  }

  private cancelLocationWait(): void {
    this.locationWait?.();
    this.locationWait = null;
  }

  /** Screen spec › States › Waiting for location: the loading row after 300 ms while the origin is empty. */
  private waitingTimer: ReturnType<typeof setTimeout> | null = null;
  private setWaitingForLocation(on: boolean): void {
    if (this.waitingTimer) clearTimeout(this.waitingTimer);
    this.waitingTimer = null;
    if (on) {
      this.waitingTimer = setTimeout(() => {
        if (this.controller.view.state === "empty") this.renderStateRow("loading");
      }, 300);
    } else if (this.controller.view.state === "empty") {
      this.result.replaceChildren();
      this.result.setAttribute("aria-busy", "false");
    }
  }

  // ------------------------------------------------------------------ points, fields, markers

  private fieldText(end: RouteEnd | null): string {
    if (!end) return "";
    const t = (k: MessageKey) => this.deps.i18n.t(k);
    if (end.label.kind === "myLocation") return t("marker.myLocation");
    if (end.label.kind === "selected") return t("place.selectedPoint");
    return end.label.text;
  }

  /** Field texts, swap state and markers follow the controller's points (AC 8, 9). */
  private syncPoints(): void {
    this.fields.origin.syncText();
    this.fields.destination.syncText();
    const both = this.controller.origin !== null && this.controller.destination !== null;
    this.swapBtn.setAttribute("aria-disabled", String(!both));
    this.renderMarkers();
  }

  private renderMarkers(): void {
    const map = this.deps.map();
    const o = this.controller.origin;
    const d = this.controller.destination;
    if (map && o && o.label.kind !== "myLocation") {
      // AC 9: one origin marker; «Миний байршил» uses the NAV-002 location dot instead (no second marker).
      if (!this.originMarker) this.originMarker = new Marker({ element: this.originMarkerEl, anchor: "center" });
      this.originMarker.setLngLat([o.point.lon, o.point.lat]).addTo(map);
    } else {
      this.originMarker?.remove();
      this.originMarker = null;
    }
    // The NAV-003 pin is the destination marker while the panel is open (map-style §7.3).
    if (d) this.deps.search.card.pinTo({ lat: d.point.lat, lon: d.point.lon }, this.fieldText(d));
    this.syncMarkerNames();
  }

  private syncMarkerNames(): void {
    this.originMarkerEl.setAttribute("aria-label", this.fieldText(this.controller.origin));
    const d = this.controller.destination;
    if (d && this.isOpen) this.deps.search.card.pinTo({ lat: d.point.lat, lon: d.point.lon }, this.fieldText(d));
  }

  // ------------------------------------------------------------------ view rendering

  private onView(v: RouteView, force = false): void {
    this.panel.dataset.state = v.state;
    if (v.state !== "empty" && this.waitingTimer) {
      clearTimeout(this.waitingTimer);
      this.waitingTimer = null;
    }
    const focus = this.focusMark();
    const newResponse = v.response !== this.renderedResponse;
    if (v.state === "route" && v.response) {
      if (newResponse) {
        this.etaBase = v.receivedAt;
        this.geometries = routeGeometries(v.response);
        this.activeStep = -1;
        this.focusedStep = 0;
      }
      if (newResponse || v.selected !== this.renderedSelected || force) {
        if (v.selected !== this.renderedSelected) {
          this.activeStep = -1;
          this.focusedStep = 0;
        }
        this.renderRoute(v);
        this.setLines(routeLineData(this.geometries, v.selected));
        if (this.activeStep < 0) this.setStep(null);
      }
      if (newResponse && !force) this.fitRoutes();
      this.startEta();
    } else {
      this.geometries = [];
      this.setLines(null);
      this.setStep(null);
      this.stopEta();
      if (v.state === "empty" || v.state === "pending") {
        this.result.replaceChildren();
        this.result.setAttribute("aria-busy", "false");
      } else {
        this.renderStateRow(v.state, v);
      }
    }
    this.renderedResponse = v.response;
    this.renderedSelected = v.state === "route" ? v.selected : -1;
    this.restoreFocus(focus);
  }

  /** Where focus is inside the result region before a rebuild. */
  private focusMark(): FocusMark {
    const a = document.activeElement as HTMLElement | null;
    if (!a || !this.result.contains(a)) return null;
    if (a.dataset.testid === "route-retry") return { kind: "retry" };
    if (a.dataset.testid === "route-option") return { kind: "option", index: Number(a.dataset.index) };
    if (a.dataset.testid === "route-step") return { kind: "step", index: Number(a.dataset.index) };
    return null;
  }

  /** A focused element removed by a new result or state → the selected mode tab; never <body> (Focus management). */
  private restoreFocus(mark: FocusMark): void {
    if (!mark) return;
    const a = document.activeElement;
    if (a && a !== document.body && this.result.contains(a)) return;
    let target: HTMLElement | null;
    if (mark.kind === "retry") target = this.result.querySelector<HTMLElement>('[data-testid="route-retry"]');
    else if (mark.kind === "option") target = this.result.querySelector<HTMLElement>(`[data-testid="route-option"][data-index="${mark.index}"]`);
    else target = this.result.querySelector<HTMLElement>(`[data-testid="route-step"][data-index="${mark.index}"]`);
    (target ?? this.tabs[this.controller.mode]).focus({ preventScroll: true });
  }

  private renderTabs(): void {
    for (const m of MODES) {
      const sel = m === this.controller.mode;
      this.tabs[m].setAttribute("aria-selected", String(sel));
      this.tabs[m].tabIndex = sel ? 0 : -1;
    }
    this.tabpanel.setAttribute("aria-labelledby", `route-tab-${this.controller.mode}`);
  }

  /** AC 12: the switch is shown only on «Машин» (0.5.0 contracts exclude_unpaved for auto only). */
  private renderAvoid(): void {
    this.avoidBtn.hidden = this.controller.mode !== "car";
    this.avoidBtn.setAttribute("aria-checked", String(this.controller.avoid));
  }

  private stateMessage(state: RouteState): string {
    const row = ROUTE_STATE_ROW[state];
    return row ? this.deps.i18n.t(row.key) : "";
  }

  private renderStateRow(state: RouteState, v?: RouteView): void {
    const row = ROUTE_STATE_ROW[state];
    if (!row) return;
    const t = (k: MessageKey) => this.deps.i18n.t(k);
    const txt = h("span", { class: "txt", id: "route-state-text" });
    txt.append(h("span", { class: "msg-main" }));
    (txt.firstChild as HTMLElement).textContent = t(row.key);
    if (v?.avoidHint) {
      const hint = h("span", { class: "hint", "data-testid": "route-avoid-hint" });
      hint.textContent = t("route.noRouteAvoidHint");
      txt.append(hint);
    }
    const icon = iconSpan(row.icon ?? '<span class="spin"></span>');
    const stateEl = h("div", { class: "state", "data-testid": "route-state", "data-state": state }, icon, txt);
    const retry = v?.retry ?? "none";
    if (retry !== "none") {
      const b = h("button", { type: "button", class: "act", "data-testid": "route-retry", "aria-describedby": "route-state-text" });
      b.textContent = t("action.retry");
      if (retry === "disabled") b.setAttribute("aria-disabled", "true");
      b.addEventListener("click", () => {
        if (b.getAttribute("aria-disabled") === "true") return;
        this.controller.retry();
      });
      stateEl.classList.add("has-act");
      stateEl.append(b);
    }
    this.result.setAttribute("aria-busy", String(state === "loading"));
    this.result.replaceChildren(stateEl);
  }

  private renderRoute(v: RouteView): void {
    const r = v.response!;
    const route = r.routes[v.selected]!;
    const t = (k: MessageKey) => this.deps.i18n.t(k);
    const lang = this.deps.i18n.lang;
    this.result.setAttribute("aria-busy", "false");

    // Summary (AC 22–25)
    const dur = h("span", { class: "d", "data-testid": "route-duration" });
    dur.textContent = formatDuration(route.duration, t);
    const dist = h("span", { class: "dist", "data-testid": "route-distance" });
    dist.textContent = formatDistance(route.distance, lang, t);
    const eta = h("p", { class: "eta", "data-testid": "route-eta" });
    const sum = h("div", { class: "rp-sum", "data-testid": "route-summary" }, h("p", { class: "dur" }, dur, dist), eta);
    const nodes: Node[] = [sum];
    this.renderEta(eta, route, this.etaBase);

    // Snap notice (AC 21)
    const snap = snapNoticeDistance(r);
    if (snap !== null) {
      const txt = h("span");
      txt.textContent = t("route.snapNotice").replace("{distance}", formatDistance(snap, lang, t));
      nodes.push(h("div", { class: "rp-note", "data-testid": "route-snap-notice" }, iconSpan(ROUTE_ICONS.info), txt));
    }

    // Route options (AC 19): only with k ≥ 2
    if (r.routes.length >= 2) {
      const group = h("div", { class: "rp-opts", role: "radiogroup", "data-testid": "route-options" });
      group.setAttribute("aria-label", t("route.options"));
      const altDesc = h("span", { id: "route-alt-desc", hidden: "" });
      altDesc.textContent = t("route.alternative");
      r.routes.forEach((rt, i) => {
        const sel = i === v.selected;
        const label = t("route.option").replace("{n}", String(i + 1));
        const meta = `${formatDuration(rt.duration, t)}`;
        const metaDist = formatDistance(rt.distance, lang, t);
        const nm = h("span", { class: "nm" });
        nm.textContent = label;
        const mt = h("span", { class: "mt" }, h("span", { class: "ty" }), h("span", { class: "cx" }));
        (mt.firstChild as HTMLElement).textContent = meta;
        (mt.lastChild as HTMLElement).textContent = metaDist;
        const opt = h(
          "div",
          { class: "rp-opt", role: "radio", "aria-checked": String(sel), tabindex: sel ? "0" : "-1", "data-testid": "route-option", "data-index": String(i) },
          h("span", { class: "radio", "aria-hidden": "true" }),
          h("span", { class: `sw${sel ? " sel" : ""}`, "aria-hidden": "true" }),
          h("span", { class: "tx" }, nm, mt),
        );
        opt.setAttribute("aria-label", [label, metaDist, meta].join(", "));
        if (!sel) opt.setAttribute("aria-describedby", "route-alt-desc");
        group.append(opt);
      });
      group.append(altDesc);
      nodes.push(group);
    }

    // Turn list (AC 26–30, 45)
    const heading = h("h3", { class: "rp-dir-h", id: "route-directions-title" });
    heading.textContent = t("route.directions");
    const ol = h("ol", { class: "rp-steps", "aria-labelledby": "route-directions-title", "data-testid": "route-steps" });
    const focusIndex = Math.min(this.focusedStep, route.steps.length - 1);
    route.steps.forEach((s, i) => {
      const key = maneuverKey(s.maneuver).key;
      const text = instructionText(s.maneuver, lang, t);
      const name = streetName(s.name);
      const isArrive = s.maneuver.type === "arrive";
      const inEl = h("span", { class: "in" });
      inEl.textContent = text;
      const tx = h("span", { class: "tx" }, inEl);
      if (name) {
        const st = h("span", { class: "st" });
        st.textContent = name;
        const pl = partLang(name, lang);
        if (pl) st.lang = pl;
        tx.append(st);
      }
      const d = isArrive ? "" : formatDistance(s.distance, lang, t);
      const dEl = h("span", { class: "dd" });
      dEl.textContent = d;
      const btn = h("button", { type: "button", class: `step${i === this.activeStep ? " active" : ""}`, "data-testid": "route-step", "data-index": String(i), "data-key": key, tabindex: i === focusIndex ? "0" : "-1" }, iconSpan(maneuverIcon(key), "si"), tx, dEl);
      btn.setAttribute("aria-label", [text, name, d].filter((x) => x !== "").join(", "));
      ol.append(h("li", {}, btn));
    });
    nodes.push(heading, ol);
    this.result.replaceChildren(...nodes);
  }

  private renderEta(target: HTMLElement, route: OsrmRoute, baseMs: number): void {
    const t = (k: MessageKey) => this.deps.i18n.t(k);
    const lang = this.deps.i18n.lang;
    const eta = computeEta(baseMs, route.duration);
    target.replaceChildren(`${t("route.eta")} ${eta.time}`);
    if (eta.days > 0) {
      const nd = h("span", { class: "nd", "data-testid": "route-next-day" });
      nd.textContent = formatNextDay(eta.days, lang, t);
      target.append(" ", nd);
    }
  }

  /** AC 25: «Хүрэх цаг» recomputed from the clock every 60 s; not announced, 0 requests. */
  private startEta(): void {
    if (this.etaTimer) return;
    this.etaTimer = setInterval(() => {
      const v = this.controller.view;
      const target = this.result.querySelector<HTMLElement>('[data-testid="route-eta"]');
      if (v.state !== "route" || !v.response || !target) return;
      this.etaBase = Date.now();
      this.renderEta(target, v.response.routes[v.selected]!, this.etaBase);
    }, ETA_REFRESH_MS);
  }

  private stopEta(): void {
    if (this.etaTimer) clearInterval(this.etaTimer);
    this.etaTimer = null;
  }

  // ------------------------------------------------------------------ result region interaction

  private onResultClick(e: MouseEvent): void {
    const target = e.target as HTMLElement;
    const opt = target.closest<HTMLElement>('[data-testid="route-option"]');
    if (opt) {
      this.controller.selectRoute(Number(opt.dataset.index));
      return;
    }
    const step = target.closest<HTMLElement>('[data-testid="route-step"]');
    if (step) this.activateStep(Number(step.dataset.index));
  }

  private onResultKey(e: KeyboardEvent): void {
    const target = e.target as HTMLElement;
    if (target.dataset.testid === "route-option") {
      const n = this.controller.view.response?.routes.length ?? 0;
      const i = Number(target.dataset.index);
      let next: number | null = null;
      if (e.key === "ArrowDown" || e.key === "ArrowRight") next = (i + 1) % n;
      else if (e.key === "ArrowUp" || e.key === "ArrowLeft") next = (i - 1 + n) % n;
      else if (e.key === " " || e.key === "Enter") next = i;
      if (next === null) return;
      e.preventDefault();
      this.controller.selectRoute(next); // 0 requests, no camera move (AC 18)
      this.result.querySelector<HTMLElement>(`[data-testid="route-option"][data-index="${next}"]`)?.focus();
      return;
    }
    if (target.dataset.testid === "route-step") {
      const rows = [...this.result.querySelectorAll<HTMLElement>('[data-testid="route-step"]')];
      const i = Number(target.dataset.index);
      let next: number | null = null;
      if (e.key === "ArrowDown") next = Math.min(rows.length - 1, i + 1);
      else if (e.key === "ArrowUp") next = Math.max(0, i - 1);
      else if (e.key === "Home") next = 0;
      else if (e.key === "End") next = rows.length - 1;
      if (next === null) return;
      e.preventDefault();
      // Roving tabindex: one Tab stop for the whole list, no wrapping, no activation (screen spec › Keyboard).
      target.tabIndex = -1;
      const row = rows[next]!;
      row.tabIndex = 0;
      row.focus();
      this.focusedStep = next;
    }
  }

  /** AC 30: camera to the step's manoeuvre point, zoom 17 (or higher), manoeuvre point shown; route stays. */
  private activateStep(index: number): void {
    const v = this.controller.view;
    const step = v.response?.routes[v.selected]?.steps[index];
    const map = this.deps.map();
    if (!step || !map) return;
    this.activeStep = index;
    this.focusedStep = index;
    for (const b of this.result.querySelectorAll<HTMLElement>('[data-testid="route-step"]')) {
      const on = Number(b.dataset.index) === index;
      b.classList.toggle("active", on);
      b.tabIndex = on ? 0 : -1;
    }
    const [lng, lat] = step.maneuver.location;
    this.setStep([lng, lat]);
    const pad = this.padding();
    const zoom = Math.max(STEP_ZOOM, map.getZoom());
    const offset: [number, number] = [(pad.left - pad.right) / 2, (pad.top - pad.bottom) / 2];
    this.deps.stopFollowing();
    if (prefersReducedMotion()) {
      // jumpTo has no `offset`: centre on the point, then shift it to the uncovered area's centre (both instant).
      map.jumpTo({ center: [lng, lat], zoom });
      if (offset[0] !== 0 || offset[1] !== 0) map.panBy([-offset[0], -offset[1]], { duration: 0 });
    } else {
      // `offset` (not `padding`) so the map's padding state is not changed for NAV-002/NAV-003 camera moves.
      map.easeTo({ center: [lng, lat], zoom, offset, duration: DURATION_CAMERA, essential: false });
    }
  }

  // ------------------------------------------------------------------ map layers and camera

  private setLines(data: RouteStyleData["lines"] | null): void {
    this.styleData = { ...this.styleData, lines: data ?? EMPTY_ROUTE_STYLE.lines };
    this.deps.map()?.getSource<GeoJSONSource>(ROUTE_SOURCE_ID)?.setData(this.styleData.lines);
  }

  private setStep(lngLat: [number, number] | null): void {
    if (!lngLat) this.activeStep = -1;
    this.styleData = { ...this.styleData, step: stepPointData(lngLat) };
    this.deps.map()?.getSource<GeoJSONSource>(ROUTE_STEP_SOURCE_ID)?.setData(this.styleData.step);
  }

  private onMapClick(map: MapLibreMap, e: MapMouseEvent): void {
    if (!this.isOpen || this.controller.view.state !== "route" || !map.getLayer(ROUTE_HIT_LAYER_ID)) return;
    // Clicking the selected line does nothing, even where an alternative's hit area overlaps it.
    if (map.queryRenderedFeatures(e.point, { layers: [ROUTE_SEL_LAYER_ID, ROUTE_SEL_CASING_LAYER_ID] }).length > 0) return;
    const hit = map.queryRenderedFeatures(e.point, { layers: [ROUTE_HIT_LAYER_ID] })[0];
    const index = hit?.properties?.index;
    if (typeof index === "number") this.controller.selectRoute(index); // AC 18: 0 requests, camera stays
  }

  /** 40 px plus the UI covering each edge (screen spec › Camera rules). */
  private padding(): { top: number; bottom: number; left: number; right: number } {
    const vh = window.innerHeight;
    const vw = window.innerWidth;
    const r1 = document.querySelector<HTMLElement>(".r1")?.getBoundingClientRect().bottom ?? 0;
    const messages = document.getElementById("messages");
    const r2 = messages && [...messages.children].some((c) => !(c as HTMLElement).hidden) ? messages.getBoundingClientRect().bottom : 0;
    const attrTop = document.getElementById("attribution")?.getBoundingClientRect().top ?? vh;
    let top = Math.max(r1, r2);
    let bottom = vh - attrTop;
    let left = 0;
    const slot = this.slot.getBoundingClientRect();
    if (!this.slot.hidden) {
      if (isExpanded()) left = slot.right;
      else {
        const box = (this.panel.hidden ? this.pointCard.section : this.panel).getBoundingClientRect();
        bottom = Math.max(bottom, vh - box.top + 20);
      }
    }
    let right = CONTROL_COLUMN_PX;
    top += CAMERA_PADDING_PX;
    bottom += CAMERA_PADDING_PX;
    left += CAMERA_PADDING_PX;
    right += CAMERA_PADDING_PX;
    if (vw - left - right < MIN_FIT_AREA_PX) right = CAMERA_PADDING_PX;
    if (vw - left - right < MIN_FIT_AREA_PX) {
      left = Math.max(0, vw - right - MIN_FIT_AREA_PX);
    }
    if (vh - top - bottom < MIN_FIT_AREA_PX) {
      const excess = MIN_FIT_AREA_PX - (vh - top - bottom);
      top = Math.max(0, top - excess / 2);
      bottom = Math.max(0, bottom - excess / 2);
    }
    return { top, bottom, left, right };
  }

  /** AC 17: fit every drawn route and both markers into the uncovered area, max zoom 17; jump with reduced motion. */
  private fitRoutes(): void {
    const map = this.deps.map();
    if (!map) return;
    const pts: [number, number][] = this.geometries.flat();
    const o = this.controller.origin;
    const d = this.controller.destination;
    if (o) pts.push([o.point.lon, o.point.lat]);
    if (d) pts.push([d.point.lon, d.point.lat]);
    const b = boundsOf(pts);
    if (!b) return;
    const bounds: [[number, number], [number, number]] = [
      [b[0], b[1]],
      [b[2], b[3]],
    ];
    const pad = this.padding();
    for (const padding of [pad, { ...pad, right: CAMERA_PADDING_PX }, CAMERA_PADDING_PX, 0]) {
      let cam;
      try {
        cam = map.cameraForBounds(bounds, { padding, maxZoom: FIT_MAX_ZOOM });
      } catch {
        cam = undefined;
      }
      if (!cam || cam.center === undefined) continue;
      this.deps.stopFollowing();
      const center = cam.center as [number, number] | { lng: number; lat: number };
      const zoom = Math.min(FIT_MAX_ZOOM, cam.zoom ?? map.getZoom());
      if (prefersReducedMotion()) map.jumpTo({ center, zoom, bearing: map.getBearing() });
      else map.easeTo({ center, zoom, duration: DURATION_CAMERA, essential: false });
      return;
    }
  }

  // ------------------------------------------------------------------ coordinate card during the preview (AC 7)

  private openPointCard(p: LatLon): void {
    this.fields.origin.closeQuietly();
    this.fields.destination.closeQuietly();
    this.panel.hidden = true;
    this.pointCard.open(p);
  }

  private closePointCard(): void {
    this.panel.hidden = false;
    this.title.focus({ preventScroll: true });
  }

  private setFromCard(which: "origin" | "destination", p: LatLon): void {
    this.pointCard.close(false);
    this.panel.hidden = false;
    const end: RouteEnd = { point: { lat: p.lat, lon: p.lon }, label: { kind: "selected" } };
    if (which === "origin") this.controller.setOrigin(end);
    else this.controller.setDestination(end);
    this.syncPoints();
    (which === "origin" ? this.originInput : this.destInput).focus({ preventScroll: false });
  }

  // ------------------------------------------------------------------ announcements (AC 47)

  private summaryText(route: OsrmRoute, baseMs: number): string {
    const t = (k: MessageKey) => this.deps.i18n.t(k);
    const lang = this.deps.i18n.lang;
    return [formatDistance(route.distance, lang, t), formatDuration(route.duration, t), formatEta(computeEta(baseMs, route.duration), lang, t)].join(", ");
  }

  private announcementText(a: RouteAnnouncement): string {
    const v = this.controller.view;
    const t = (k: MessageKey) => this.deps.i18n.t(k);
    const lang = this.deps.i18n.lang;
    if (a.kind === "result" && v.response) {
      const route = v.response.routes[v.selected]!;
      return `${formatRouteCount(v.response.routes.length, lang, t)}, ${this.summaryText(route, v.receivedAt)}`;
    }
    if (a.kind === "selection" && v.response) {
      const route = v.response.routes[v.selected]!;
      return `${t("route.option").replace("{n}", String(v.selected + 1))}, ${this.summaryText(route, this.etaBase)}`;
    }
    if (a.kind === "state") {
      const msg = this.stateMessage(a.state);
      return v.avoidHint ? `${msg}. ${t("route.noRouteAvoidHint")}` : msg;
    }
    return "";
  }

  /** Once per result or state; identical texts (static build, repeated actions) are re-announced by clearing first. */
  private announce(a: RouteAnnouncement): void {
    const text = this.announcementText(a);
    if (this.announceTimer) clearTimeout(this.announceTimer);
    this.live.textContent = "";
    this.announceTimer = setTimeout(() => {
      this.announceTimer = null;
      this.live.textContent = text;
    }, 50);
  }
}

// ---------------------------------------------------------------------------------------------------------------------

/** The NAV-003 coordinate card, shown in the route slot during the preview, with the two set buttons (AC 7). */
class PointCard {
  readonly section: HTMLElement;
  private readonly title: HTMLHeadingElement;
  private readonly coords: HTMLElement;
  private readonly near: HTMLElement;
  private readonly nearPlace: HTMLElement;
  private readonly nearName: HTMLElement;
  private readonly nearType: HTMLElement;
  private readonly nearContext: HTMLElement;
  private readonly nearState: HTMLElement;
  private readonly nearStateText: HTMLElement;
  private readonly retryBtn: HTMLButtonElement;
  private point: LatLon | null = null;
  private nearNameValue: string | null = null;
  private marker: Marker | null = null;
  private readonly pinEl: HTMLElement;

  constructor(
    private readonly i18n: I18n,
    private readonly reverse: ReverseController,
    tooltip: Tooltip,
    private readonly map: () => MapLibreMap | null,
    private readonly on: { set: (which: "origin" | "destination", p: LatLon) => void; closed: () => void },
  ) {
    this.title = h("h2", { id: "route-point-title", tabindex: "-1", "data-testid": "route-point-title", "data-i18n": "place.selectedPoint" });
    const close = h("button", { type: "button", class: "btn flat", "data-testid": "route-point-close", "data-i18n-aria-label": "action.close", "data-tooltip": "action.close" });
    close.innerHTML = ICONS.close;
    this.coords = h("p", { class: "coords", "data-testid": "route-point-coords" });
    const setO = h("button", { type: "button", class: "obtn", "data-testid": "route-set-origin", "data-i18n": "route.setOrigin" });
    const setD = h("button", { type: "button", class: "obtn", "data-testid": "route-set-destination", "data-i18n": "route.setDestination" });
    this.nearName = h("p", { class: "nn", "data-testid": "route-point-nearest-name" });
    this.nearType = h("span", { class: "ty" });
    this.nearContext = h("span", { class: "cx" });
    this.nearPlace = h("div", { class: "near-place" }, this.nearName, h("p", { class: "pm" }, this.nearType, this.nearContext));
    this.nearStateText = h("span", { class: "txt" });
    this.retryBtn = h("button", { type: "button", class: "act", "data-testid": "route-point-retry", "data-i18n": "action.retry" });
    this.nearState = h("div", { class: "state" }, iconSpan(""), this.nearStateText, this.retryBtn);
    this.near = h("div", { class: "near", "aria-live": "polite", "data-testid": "route-point-nearest" }, h("p", { class: "lab", "data-i18n": "place.nearest" }), this.nearPlace, this.nearState);
    this.section = h(
      "section",
      { class: "place-card rp-card", id: "route-point-card", "data-testid": "route-point-card", "aria-labelledby": "route-point-title" },
      h("div", { class: "ph" }, this.title, close),
      this.coords,
      h("div", { class: "cacts" }, setO, setD),
      this.near,
    );
    this.section.hidden = true;
    this.pinEl = h("div", { class: "place-pin candidate", role: "img", "data-testid": "route-candidate-pin" });
    this.pinEl.innerHTML = ICONS.pin;

    close.addEventListener("click", () => this.userClose());
    this.section.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && !e.isComposing) {
        e.preventDefault();
        e.stopPropagation();
        this.userClose();
      }
    });
    setO.addEventListener("click", () => this.point && on.set("origin", this.point));
    setD.addEventListener("click", () => this.point && on.set("destination", this.point));
    this.retryBtn.addEventListener("click", () => {
      if (this.retryBtn.getAttribute("aria-disabled") === "true") return;
      this.title.focus();
      reverse.retry();
    });
    reverse.onChange((v) => this.renderNear(v));
    tooltip.attach(close, "below");
  }

  open(p: LatLon): void {
    this.point = p;
    this.nearNameValue = null;
    this.section.hidden = false;
    this.render();
    const map = this.map();
    if (map) {
      if (!this.marker) this.marker = new Marker({ element: this.pinEl, anchor: "bottom" });
      this.marker.setLngLat([p.lon, p.lat]).addTo(map);
    }
    this.reverse.open(p);
    this.title.focus({ preventScroll: true });
  }

  /** Closes the card and removes the candidate pin (either button, «Хаах», Esc, or the panel closing). */
  close(notify: boolean): void {
    const wasOpen = !this.section.hidden;
    this.point = null;
    this.section.hidden = true;
    this.marker?.remove();
    this.marker = null;
    this.reverse.close();
    if (notify && wasOpen) this.on.closed();
  }

  renderI18n(): void {
    this.render();
    this.renderNear(this.reverse.view);
  }

  private userClose(): void {
    this.close(true);
  }

  private render(): void {
    if (!this.point) return;
    this.coords.textContent = formatCoordinate(this.point);
    this.pinEl.setAttribute("aria-label", this.i18n.t("place.selectedPoint"));
  }

  private renderNear(v: NearView | null): void {
    if (!this.point || !v) {
      this.near.removeAttribute("data-state");
      this.nearPlace.hidden = true;
      this.nearState.hidden = true;
      return;
    }
    const t = (k: MessageKey) => this.i18n.t(k);
    const lang = this.i18n.lang;
    this.near.dataset.state = v.state;
    if (v.state === "place" && v.feature) {
      const info = resultInfo(v.feature);
      if (this.nearNameValue === null) this.nearNameValue = info.name ?? t(info.typeKey);
      this.nearPlace.hidden = false;
      this.nearState.hidden = true;
      this.nearName.textContent = this.nearNameValue;
      setLang(this.nearName, partLang(this.nearNameValue, lang));
      this.nearType.textContent = t(info.typeKey);
      setLang(this.nearType, partLang(this.nearType.textContent, lang));
      this.nearContext.textContent = info.context ?? "";
      setLang(this.nearContext, info.context ? partLang(info.context, lang) : null);
      return;
    }
    this.nearNameValue = null;
    this.nearPlace.hidden = true;
    if (v.state === "pending") {
      this.nearState.hidden = true;
      return;
    }
    const row = v.state === "empty" ? STATE_ROW["no-results"] : STATE_ROW[v.state as keyof typeof STATE_ROW];
    this.nearState.hidden = false;
    this.nearState.dataset.state = v.state === "empty" ? "no-results" : v.state;
    this.nearState.querySelector<HTMLElement>(".icon")!.innerHTML = row.icon ?? '<span class="spin"></span>';
    this.nearStateText.textContent = t(row.key);
    const focusOnRetry = document.activeElement === this.retryBtn;
    this.retryBtn.hidden = v.retry === "none";
    this.nearState.classList.toggle("has-act", v.retry !== "none");
    if (v.retry === "disabled") this.retryBtn.setAttribute("aria-disabled", "true");
    else this.retryBtn.removeAttribute("aria-disabled");
    if (focusOnRetry && this.retryBtn.hidden) this.title.focus();
  }
}

function setLang(e: HTMLElement, lang: string | null): void {
  if (lang) e.lang = lang;
  else e.removeAttribute("lang");
}

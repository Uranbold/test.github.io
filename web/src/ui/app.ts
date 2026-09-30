// UI chrome and map wiring for NAV-002 (screen spec: docs/design/screens/NAV-002-web-map.md).
import { Marker, type GeoJSONSource, type Map as MapLibreMap } from "maplibre-gl";
import type { AppConfig } from "../config";
import { circlePolygon } from "../geo/circle";
import { computeScaleBar, distanceMeters } from "../geo/scale";
import type { I18n, Lang, MessageKey } from "../i18n/i18n";
import {
  RECENTER_MIN_ZOOM,
  type CameraPort,
  type Fix,
  type LocationController,
  type LocationView,
} from "../location/locationController";
import type { CreateMapOptions } from "../map/createMap";
import { MAX_ZOOM, MIN_ZOOM } from "../map/createMap";
import { saveLang, saveTheme } from "../prefs";
import { buildStyle, EMPTY_LOCATION, LOCATION_SOURCE_ID, SOURCE_ID, type LocationData, type Theme } from "../style/buildStyle";
import type { StatusMachine, StatusView } from "../state/status";
import { ICONS } from "./icons";
import { Tooltip } from "./tooltip";

const ESA_MAX_ZOOM_EXCLUSIVE = 8; // screen spec › Attribution strip: ESA line while zoom < 8
const SCALE_MAX_PX = 100; // tokens: size.scale-bar-max
const DURATION_MEDIUM = 300; // tokens: motion.duration-medium
const DURATION_CAMERA = 1000; // tokens: motion.duration-camera
const FOLLOW_EASE_MS = 500;
const ZOOM_EPSILON = 1e-6;

export interface AppDeps {
  cfg: AppConfig;
  i18n: I18n;
  status: StatusMachine;
  theme: Theme;
  freshArchive: () => void;
  createMap: (o: CreateMapOptions) => MapLibreMap;
  createLocation: (camera: CameraPort) => LocationController;
}

function el<T extends HTMLElement = HTMLElement>(id: string): T {
  const e = document.getElementById(id);
  if (!e) throw new Error(`missing #${id}`);
  return e as T;
}

const prefersReducedMotion = (): boolean => window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;

export class App {
  map: MapLibreMap | null = null;
  private theme: Theme;
  private readonly i18n: I18n;
  private readonly status: StatusMachine;
  private location!: LocationController;
  private marker: Marker | null = null;
  private markerEl: HTMLElement | null = null;
  private locationData: LocationData = EMPTY_LOCATION;
  private sawTile = false;
  private attemptErrored = false;
  private zoomTarget: number | null = null;
  private scaleFrame = 0;
  private tooltip: Tooltip;

  private readonly ui = {
    root: document.documentElement,
    mapContainer: el("map"),
    langBtn: el<HTMLButtonElement>("lang-btn"),
    themeBtn: el<HTMLButtonElement>("theme-btn"),
    compassBtn: el<HTMLButtonElement>("compass-btn"),
    banner: el("status-banner"),
    bannerText: el("status-banner-text"),
    bannerRetry: el<HTMLButtonElement>("banner-retry"),
    locMsg: el("location-message"),
    locMsgTitle: el("location-message-title"),
    locMsgHint: el("location-message-hint"),
    locRetry: el<HTMLButtonElement>("location-retry"),
    locClose: el<HTMLButtonElement>("location-close"),
    loading: el("loading"),
    loadingText: el("loading-text"),
    card: el("card"),
    cardTitle: el("card-title"),
    cardRetry: el<HTMLButtonElement>("card-retry"),
    bottom: el("bottom"),
    scaleBar: el("scale-bar"),
    scaleLabel: el("scale-label"),
    zoomIn: el<HTMLButtonElement>("zoom-in"),
    zoomOut: el<HTMLButtonElement>("zoom-out"),
    locateBtn: el<HTMLButtonElement>("locate-btn"),
    locateDesc: el("locate-desc"),
    esa: el("attribution-esa"),
    tooltip: el("tooltip"),
  };

  constructor(private readonly deps: AppDeps) {
    this.theme = deps.theme;
    this.i18n = deps.i18n;
    this.status = deps.status;
    this.tooltip = new Tooltip(this.ui.tooltip, (e) => this.tooltipText(e), [el("scale")]);
  }

  start(): void {
    this.ui.root.dataset.theme = this.theme;
    this.ui.locateDesc.id = "locate-desc";
    this.bindChrome();
    this.applyI18n();
    this.status.onChange((v) => this.renderStatus(v));
    this.status.start(performance.now());
    this.renderStatus(this.status.view);

    try {
      this.map = this.deps.createMap({
        container: this.ui.mapContainer,
        style: this.style(),
        mapLabel: this.i18n.t("map.label"),
      });
    } catch (err) {
      // No WebGL or an unexpected start-up failure: generic error card (flow F1 note).
      console.error(err);
      this.status.fail();
      return;
    }
    this.location = this.deps.createLocation(this.cameraPort());
    this.location.onChange((v) => this.renderLocation(v));
    this.renderLocation(this.location.view);
    this.bindMap(this.map);
  }

  // ---------------------------------------------------------------- map

  private style() {
    return buildStyle(this.theme, this.deps.cfg, this.locationData);
  }

  private bindMap(map: MapLibreMap): void {
    map.on("error", (e) => {
      const sourceId = (e as { sourceId?: string }).sourceId;
      if (sourceId !== SOURCE_ID) {
        // Glyph, sprite or other non-tile errors never raise the tiles states (ADR-0004 §5).
        console.warn("map error (ignored for tile state)", e.error?.message ?? e);
        return;
      }
      if (!navigator.onLine) return; // caused by being offline; the offline state covers it
      if (!this.status.state.ready) this.attemptErrored = true;
      this.status.sourceError();
    });
    map.on("sourcedata", (e) => {
      if (e.sourceId === SOURCE_ID && e.tile) {
        this.sawTile = true;
        this.status.tileLoaded();
      }
    });
    map.on("idle", () => {
      if (this.status.state.ready || this.attemptErrored || !this.sawTile) return;
      if (!map.getSource(SOURCE_ID) || !map.isSourceLoaded(SOURCE_ID)) return;
      const hadFocusOnCard = document.activeElement === this.ui.cardRetry;
      this.status.markReady();
      if (hadFocusOnCard) map.getCanvas().focus();
    });

    // The upstream style asks for a few POI icons that sprites/v4 does not have (e.g. "townhall").
    // Register an empty image so the label still renders without an icon and without console noise.
    map.setMissingStyleImageResolver((id) => {
      if (!map.hasImage(id)) map.addImage(id, { width: 1, height: 1, data: new Uint8Array(4) });
    });

    map.on("move", () => this.scheduleScale());
    map.on("zoom", () => this.renderZoomState());
    map.on("zoomend", () => {
      this.zoomTarget = null;
      this.renderScale();
    });
    map.on("rotate", () => this.renderCompass());
    map.on("load", () => {
      this.renderZoomState();
      this.renderCompass();
      this.renderScale();
    });

    // Follow / not-following (AC 20, flow F3 rules).
    map.on("dragstart", () => this.location.userMovedMap());
    map.on("movestart", (e) => {
      const oe = (e as { originalEvent?: Event }).originalEvent;
      if (!oe) return;
      if (oe.type === "dblclick") this.location.userMovedMap();
      if (oe instanceof KeyboardEvent && !oe.shiftKey && oe.key.startsWith("Arrow")) this.location.userMovedMap();
    });

    window.addEventListener("online", () => this.onOnline());
    window.addEventListener("offline", () => this.status.setOnline(false));
  }

  /** New load attempt before the first render: fresh PMTiles instance, then set the style again. */
  private reloadBasemap(): void {
    const map = this.map;
    if (!map) return;
    this.deps.freshArchive();
    this.attemptErrored = false;
    this.sawTile = false;
    map.setStyle(this.style(), { diff: false });
  }

  /**
   * After the first render (banner «Дахин оролдох», back online): fresh PMTiles instance and a full
   * source rebuild. MapLibre 6 cannot re-request errored vector tiles in place (source.setUrl /
   * refreshTiles leave them stuck in "loading", because the worker refuses to reload a tile that never
   * loaded), so the style is set again without diffing. Camera, theme and location layers are kept.
   */
  private reloadTiles(): void {
    const map = this.map;
    if (!map) return;
    this.deps.freshArchive();
    map.setStyle(this.style(), { diff: false });
  }

  private onOnline(): void {
    this.status.setOnline(true);
    if (!this.status.state.ready) {
      this.status.start();
      this.reloadBasemap();
    } else {
      this.status.tileLoaded(); // errors while offline do not count
      this.reloadTiles();
    }
  }

  private onCardRetry(): void {
    const v = this.status.view;
    if (v.kind !== "card") return;
    if (v.reason === "generic") {
      window.location.reload();
      return;
    }
    if (v.busy) return;
    this.status.retryStart();
    this.reloadBasemap();
  }

  // ---------------------------------------------------------------- chrome

  private bindChrome(): void {
    const u = this.ui;
    u.langBtn.addEventListener("click", () => this.setLang(this.i18n.lang === "mn" ? "en" : "mn"));
    u.themeBtn.addEventListener("click", () => this.setTheme(this.theme === "day" ? "night" : "day"));
    u.compassBtn.addEventListener("click", () => {
      const m = this.map;
      if (!m) return;
      if (prefersReducedMotion()) m.jumpTo({ bearing: 0, pitch: 0 });
      else m.easeTo({ bearing: 0, pitch: 0, duration: DURATION_MEDIUM });
    });
    u.zoomIn.addEventListener("click", () => this.zoomBy(1));
    u.zoomOut.addEventListener("click", () => this.zoomBy(-1));
    u.locateBtn.addEventListener("click", () => this.location?.press());
    u.bannerRetry.addEventListener("click", () => this.reloadTiles());
    u.cardRetry.addEventListener("click", () => this.onCardRetry());
    u.locRetry.addEventListener("click", () => this.location?.retry());
    u.locClose.addEventListener("click", () => {
      this.location?.dismissMessage();
      u.locateBtn.focus();
    });

    this.tooltip.attach(u.langBtn, "below");
    this.tooltip.attach(u.themeBtn, "below");
    this.tooltip.attach(u.compassBtn, "below");
    this.tooltip.attach(u.zoomIn, "left");
    this.tooltip.attach(u.zoomOut, "left");
    this.tooltip.attach(u.locateBtn, "left");
  }

  private tooltipText(e: HTMLElement): string | null {
    if (e === this.ui.themeBtn) return this.i18n.t(this.theme === "day" ? "theme.night" : "theme.day");
    if (e === this.ui.locateBtn) {
      return this.i18n.t(this.location?.view.button === "unsupported" ? "location.unavailable" : "control.recenter");
    }
    const key = e.dataset.tooltip as MessageKey | undefined;
    return key ? this.i18n.t(key) : null;
  }

  private setLang(lang: Lang): void {
    this.i18n.setLang(lang);
    saveLang(lang);
    this.applyI18n();
  }

  private setTheme(theme: Theme): void {
    this.theme = theme;
    this.ui.root.dataset.theme = theme;
    saveTheme(theme);
    this.renderThemeButton();
    this.tooltip.refresh();
    // Camera untouched: the style has no center/zoom (AC 27). Location layers are part of the style.
    this.map?.setStyle(this.style(), { diff: true });
  }

  private zoomBy(delta: number): void {
    const m = this.map;
    if (!m) return;
    const from = this.zoomTarget ?? m.getZoom();
    const to = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, from + delta));
    if (Math.abs(to - from) < ZOOM_EPSILON) return;
    this.zoomTarget = to;
    // Zoom around the centre, so following continues (flow F3 rules).
    if (prefersReducedMotion()) m.jumpTo({ zoom: to });
    else m.easeTo({ zoom: to, duration: DURATION_MEDIUM });
  }

  /** Swaps every UI string, <html lang>, title, accessible names (AC 31). No reload, camera unchanged. */
  private applyI18n(): void {
    const t = (k: MessageKey) => this.i18n.t(k);
    const lang = this.i18n.lang;
    this.ui.root.lang = lang;
    document.title = t("app.title");
    document.querySelectorAll<HTMLElement>("[data-i18n]").forEach((n) => {
      n.textContent = t(n.dataset.i18n as MessageKey);
    });
    document.querySelectorAll<HTMLElement>("[data-i18n-aria-label]").forEach((n) => {
      n.setAttribute("aria-label", t(n.dataset.i18nAriaLabel as MessageKey));
    });
    const other: Lang = lang === "mn" ? "en" : "mn";
    this.ui.langBtn.textContent = t(other === "mn" ? "language.mn" : "language.en");
    this.ui.langBtn.lang = other;
    this.renderThemeButton();
    this.map?.getCanvas().setAttribute("aria-label", t("map.label"));
    if (this.markerEl) this.markerEl.setAttribute("aria-label", t("marker.myLocation"));
    this.renderStatus(this.status.view);
    if (this.location) this.renderLocation(this.location.view);
    this.renderScale();
    this.tooltip.refresh();
  }

  private renderThemeButton(): void {
    const target = this.theme === "day" ? "theme.night" : "theme.day";
    this.ui.themeBtn.innerHTML = this.theme === "day" ? ICONS.moon : ICONS.sun;
    this.ui.themeBtn.setAttribute("aria-label", this.i18n.t(target));
  }

  // ---------------------------------------------------------------- status

  private renderStatus(v: StatusView): void {
    const u = this.ui;
    const t = (k: MessageKey) => this.i18n.t(k);
    const ready = this.status.state.ready && v.kind !== "card";

    // Layout rule 4: before the first tiles render, R4 and the compass are hidden.
    u.bottom.hidden = !ready;
    u.compassBtn.hidden = !ready;

    // Loading pill (AC 37). The live region stays in the DOM; only its text and visibility change.
    const loading = v.kind === "loading";
    u.loading.classList.toggle("idle", !loading);
    u.loading.setAttribute("aria-hidden", String(!loading));
    u.loadingText.textContent = loading ? t("status.loading") : "";

    // Blocking card (AC 38–40, 45).
    if (v.kind === "card") {
      u.card.hidden = false;
      u.card.dataset.reason = v.reason;
      const title: MessageKey =
        v.reason === "generic" ? "status.genericError" : v.reason === "offline" ? "status.offline" : "status.tilesUnavailable";
      u.cardTitle.textContent = t(title);
      (u.card.querySelector(".icon") as HTMLElement).innerHTML =
        v.reason === "offline" ? ICONS.cloudOff : v.reason === "generic" ? ICONS.error : ICONS.mapOff;
      u.cardRetry.hidden = v.reason === "offline";
      u.cardRetry.setAttribute("aria-busy", String(v.busy));
    } else {
      u.card.hidden = true;
      u.cardRetry.setAttribute("aria-busy", "false");
    }

    // Top banner after the first render (AC 41–45).
    if (v.kind === "banner") {
      u.banner.hidden = false;
      u.banner.dataset.reason = v.reason;
      u.banner.classList.toggle("stack-narrow", v.reason === "tiles");
      u.bannerText.textContent = t(v.reason === "offline" ? "status.offline" : "status.tilesUnavailable");
      (u.banner.querySelector(".icon") as HTMLElement).innerHTML = v.reason === "offline" ? ICONS.cloudOff : ICONS.warning;
      u.bannerRetry.hidden = v.reason === "offline";
    } else {
      u.banner.hidden = true;
      delete u.banner.dataset.reason;
    }

    if (ready) {
      this.renderZoomState();
      this.renderCompass();
      this.renderScale();
    }
  }

  // ---------------------------------------------------------------- controls

  private renderZoomState(): void {
    const m = this.map;
    if (!m) return;
    const z = m.getZoom();
    this.ui.zoomIn.setAttribute("aria-disabled", String(z >= MAX_ZOOM - ZOOM_EPSILON));
    this.ui.zoomOut.setAttribute("aria-disabled", String(z <= MIN_ZOOM + ZOOM_EPSILON));
    this.ui.esa.hidden = z >= ESA_MAX_ZOOM_EXCLUSIVE;
  }

  private renderCompass(): void {
    const m = this.map;
    if (!m) return;
    const needle = this.ui.compassBtn.querySelector<SVGElement>(".needle");
    if (needle) needle.style.transform = `rotate(${-m.getBearing()}deg)`;
    this.ui.compassBtn.dataset.bearing = m.getBearing().toFixed(1);
  }

  private scheduleScale(): void {
    if (this.scaleFrame) return;
    this.scaleFrame = requestAnimationFrame(() => {
      this.scaleFrame = 0;
      this.renderScale();
    });
  }

  /** Metric scale bar from metres per pixel at the map centre (AC 16–17). */
  private renderScale(): void {
    const m = this.map;
    if (!m) return;
    const c = m.getContainer();
    const x = c.clientWidth / 2;
    const y = c.clientHeight / 2;
    const a = m.unproject([x - 50, y]);
    const b = m.unproject([x + 50, y]);
    const s = computeScaleBar(distanceMeters(a, b) / 100, SCALE_MAX_PX);
    if (!s) return;
    this.ui.scaleBar.style.width = `${s.widthPx.toFixed(1)}px`;
    this.ui.scaleLabel.textContent = `${this.i18n.formatNumber(s.value)} ${this.i18n.t(s.unit === "km" ? "unit.km" : "unit.m")}`;
    this.ui.scaleLabel.dataset.meters = String(s.meters);
    this.ui.scaleBar.dataset.widthPx = s.widthPx.toFixed(2);
  }

  // ---------------------------------------------------------------- location

  private cameraPort(): CameraPort {
    return {
      recenter: (fix: Fix) => {
        const m = this.map;
        if (!m) return;
        const zoom = Math.max(m.getZoom(), RECENTER_MIN_ZOOM);
        if (prefersReducedMotion()) m.jumpTo({ center: [fix.lng, fix.lat], zoom });
        else m.flyTo({ center: [fix.lng, fix.lat], zoom, duration: DURATION_CAMERA });
      },
      follow: (fix: Fix) => {
        const m = this.map;
        if (!m) return;
        if (prefersReducedMotion()) m.jumpTo({ center: [fix.lng, fix.lat] });
        else m.easeTo({ center: [fix.lng, fix.lat], duration: FOLLOW_EASE_MS });
      },
    };
  }

  private renderLocation(v: LocationView): void {
    const u = this.ui;
    const t = (k: MessageKey) => this.i18n.t(k);
    const b = u.locateBtn;
    b.dataset.state = v.button;
    b.setAttribute("aria-pressed", String(v.button === "following"));
    if (v.button === "locating") b.setAttribute("aria-busy", "true");
    else b.removeAttribute("aria-busy");
    if (v.button === "unsupported") b.setAttribute("aria-disabled", "true");
    else b.removeAttribute("aria-disabled");
    b.innerHTML =
      v.button === "following" ? ICONS.locateFilled : v.button === "denied" || v.button === "unsupported" ? ICONS.locateOff : ICONS.locate;
    const desc: MessageKey | null =
      v.button === "denied" ? "location.denied.title" : v.button === "unsupported" ? "location.unavailable" : null;
    if (desc) {
      u.locateDesc.textContent = t(desc);
      b.setAttribute("aria-describedby", "locate-desc");
    } else {
      u.locateDesc.textContent = "";
      b.removeAttribute("aria-describedby");
    }

    // Wheel and pinch zoom around the centre while following, so following continues (flow F3).
    const m = this.map;
    if (m) {
      if (v.button === "following") {
        m.scrollZoom.enable({ around: "center" });
        m.touchZoomRotate.enable({ around: "center" });
      } else {
        m.scrollZoom.enable();
        m.touchZoomRotate.enable();
      }
    }

    this.renderMarker(v);

    // Location message (AC 21–23). Newest replaces older; role="alert".
    if (v.message === null) {
      u.locMsg.hidden = true;
      delete u.locMsg.dataset.kind;
    } else {
      u.locMsg.hidden = false;
      u.locMsg.dataset.kind = v.message;
      u.locMsgTitle.textContent = t(v.message === "denied" ? "location.denied.title" : "location.unavailable");
      u.locMsgHint.textContent = v.message === "denied" ? t("location.denied.hint") : "";
      u.locMsgHint.hidden = v.message !== "denied";
      u.locRetry.hidden = v.message !== "unavailable";
      u.locMsg.classList.toggle("stack-narrow", v.message === "unavailable");
    }
  }

  private renderMarker(v: LocationView): void {
    const m = this.map;
    if (!m) return;
    if (!v.fix) {
      this.marker?.remove();
      this.marker = null;
      this.markerEl = null;
      this.setLocationData(EMPTY_LOCATION);
      return;
    }
    if (!this.marker) {
      const dot = document.createElement("div");
      dot.className = "loc-dot";
      dot.setAttribute("role", "img");
      dot.dataset.testid = "location-marker";
      dot.setAttribute("aria-label", this.i18n.t("marker.myLocation"));
      this.markerEl = dot;
      this.marker = new Marker({ element: dot }).setLngLat([v.fix.lng, v.fix.lat]).addTo(m);
    } else {
      this.marker.setLngLat([v.fix.lng, v.fix.lat]);
    }
    this.markerEl?.setAttribute("data-stale", String(v.stale));
    this.setLocationData({
      type: "FeatureCollection",
      features: [
        {
          type: "Feature",
          properties: { stale: v.stale },
          geometry: circlePolygon(v.fix.lng, v.fix.lat, Math.max(0, v.fix.accuracy)),
        },
      ],
    });
  }

  private setLocationData(data: LocationData): void {
    this.locationData = data;
    this.map?.getSource<GeoJSONSource>(LOCATION_SOURCE_ID)?.setData(data);
  }
}

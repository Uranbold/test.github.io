// NAV-003 wiring: gateway client, search and reverse controllers, search box, place card, pin, camera and the
// right-click / long-press coordinate card, on the one MapLibre map that NAV-002 owns.
// Story: docs/requirements/stories/NAV-003-search-cyrillic-latin-autocomplete.md; ADR-0006; screen spec NAV-003-search.md.
import type { Map as MapLibreMap, MapMouseEvent } from "maplibre-gl";
import type { AppConfig } from "../config";
import type { I18n } from "../i18n/i18n";
import { wrapLon, type LatLon } from "../search/coords";
import { EXTENT_MAX_ZOOM, POINT_ZOOM, resultInfo, zoomForType } from "../search/display";
import { GatewayClient } from "../search/gateway";
import type { PhotonFeature } from "../search/photon";
import { ReverseController } from "../search/reverseController";
import { SearchController, type SearchOption } from "../search/searchController";
import { refusingFetch, UNAVAILABLE_CLIENT } from "../search/unavailableClient";
import { LongPress, LONG_PRESS_TOLERANCE_PX } from "./longPress";
import { PlaceCard } from "./placeCard";
import { SearchBox } from "./searchBox";
import type { Tooltip } from "./tooltip";

const CAMERA_PADDING_PX = 40; // AC 20: at least 40 px on every side
const DURATION_CAMERA = 1000; // tokens: motion.duration-camera

const prefersReducedMotion = (): boolean => window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;

export interface SearchFeatureDeps {
  cfg: AppConfig;
  i18n: I18n;
  tooltip: Tooltip;
  map: () => MapLibreMap | null;
  /** AC 9: device fix ≤ 60 s old while following, otherwise the map centre (never calls the Geolocation API). */
  bias: () => LatLon;
  /** The coordinate card from the map needs a ready map (screen spec layout rule 8). */
  mapReady: () => boolean;
  /** Moving the camera to a result ends "following" (NAV-002 flow F3). */
  stopFollowing: () => void;
  fetch?: typeof fetch;
}

function el<T extends HTMLElement = HTMLElement>(id: string): T {
  const e = document.getElementById(id);
  if (!e) throw new Error(`missing #${id}`);
  return e as T;
}

export class SearchFeature {
  readonly client: GatewayClient;
  readonly controller: SearchController;
  readonly reverse: ReverseController;
  readonly box: SearchBox;
  readonly card: PlaceCard;
  private readonly topBar = el("search").closest<HTMLElement>(".r1")!;
  private readonly attribution = el("attribution");
  private readonly longPress: LongPress;
  private rightDown: { x: number; y: number } | null = null;

  constructor(private readonly deps: SearchFeatureDeps) {
    const isOnline = (): boolean => navigator.onLine;
    const f = deps.fetch ?? ((input: RequestInfo | URL, init?: RequestInit) => window.fetch(input, init));
    // Static public demo (NAV-002 AC 53–54): with search and reverse off, nothing reaches the network.
    const { search: searchOn, reverse: reverseOn } = deps.cfg.features;
    this.client = new GatewayClient({ baseUrl: deps.cfg.gatewayBaseUrl, fetch: searchOn || reverseOn ? f : refusingFetch, isOnline });
    const lang = () => deps.i18n.lang;
    this.controller = new SearchController({ client: searchOn ? this.client : UNAVAILABLE_CLIENT, lang, bias: deps.bias, isOnline });
    this.reverse = new ReverseController({ client: reverseOn ? this.client : UNAVAILABLE_CLIENT, lang, isOnline });
    this.card = new PlaceCard({
      i18n: deps.i18n,
      map: deps.map,
      reverse: this.reverse,
      tooltip: deps.tooltip,
      onClosed: () => this.box.focusInput(),
    });
    this.box = new SearchBox({
      i18n: deps.i18n,
      controller: this.controller,
      tooltip: deps.tooltip,
      onSelect: (o) => this.select(o),
      onOpenChange: (open) => this.card.setListOpen(open),
    });
    this.controller.onAutoSelect((o) => this.box.select(o));
    if (!searchOn) this.describeSearchOff();
    this.longPress = new LongPress((x, y) => this.openPointAtClient(x, y));
    window.addEventListener("online", () => {
      this.controller.online();
      this.reverse.online();
    });
  }

  /**
   * Static public demo (NAV-003 screen spec › States › Static public demo): the input stays enabled, and screen readers
   * hear «Хайлт түр ажиллахгүй байна» on focus through a visually hidden description. App.applyI18n updates its text.
   */
  private describeSearchOff(): void {
    const input = el<HTMLInputElement>("search-input");
    const note = document.createElement("span");
    note.id = "search-off-note";
    note.className = "sr-only";
    note.dataset.testid = "search-off-note";
    note.dataset.i18n = "search.unavailable";
    note.textContent = this.deps.i18n.t("search.unavailable");
    input.parentElement!.append(note);
    const ids = (input.getAttribute("aria-describedby") ?? "").split(/\s+/).filter(Boolean);
    input.setAttribute("aria-describedby", [...ids, note.id].join(" "));
  }

  /** Called once the map exists (NAV-002 creates it after the chrome). */
  attachMap(map: MapLibreMap): void {
    const container = map.getCanvasContainer();
    this.longPress.attach(container);
    container.addEventListener("mousedown", (e) => {
      if (e.button === 2) this.rightDown = { x: e.clientX, y: e.clientY };
    });
    map.on("contextmenu", (e: MapMouseEvent) => this.onContextMenu(e));
    // A click on the map closes the list without selecting (screen spec › Interactions).
    map.on("click", () => {
      if (this.controller.view.state !== "closed") this.controller.close();
    });
    map.on("dragstart", () => this.longPress.cancel());
  }

  /** Every text of the current state in the current language (called by App.applyI18n). */
  renderI18n(): void {
    this.box.renderI18n();
    this.card.renderI18n();
  }

  /** The UI language changed (AC 38): re-request an open list once with the new `lang`. */
  langChanged(): void {
    this.controller.langChanged();
  }

  // ------------------------------------------------------------------ selection and camera

  private select(o: SearchOption): void {
    if (o.kind === "place") {
      this.card.showPlace(o.feature);
      this.card.focusHeading();
      this.flyToFeature(o.feature);
    } else {
      this.card.showPoint(o.point);
      this.card.focusHeading();
      const map = this.deps.map();
      if (map) this.moveCamera(map, { center: [o.point.lon, o.point.lat], zoom: Math.max(POINT_ZOOM, map.getZoom()) });
    }
  }

  /** AC 20: extent → fitBounds with ≥ 40 px padding plus the covering UI, max zoom 17; point → centre at zoom 13 or 16. */
  private flyToFeature(f: PhotonFeature): void {
    const map = this.deps.map();
    if (!map) return;
    const info = resultInfo(f);
    if (info.bounds) {
      for (const padding of [this.paddingForUi(), CAMERA_PADDING_PX]) {
        const cam = map.cameraForBounds(info.bounds, { padding, maxZoom: EXTENT_MAX_ZOOM });
        if (cam) {
          this.moveCamera(map, { center: cam.center as [number, number] | { lng: number; lat: number }, zoom: cam.zoom ?? map.getZoom() });
          return;
        }
      }
    }
    // Point results are centred in the viewport (±5 px); the layout keeps the viewport centre uncovered (layout rule 3).
    this.moveCamera(map, { center: [info.lon, info.lat], zoom: zoomForType(info.typeKey) });
  }

  private moveCamera(map: MapLibreMap, cam: { center: [number, number] | { lng: number; lat: number }; zoom: number }): void {
    this.deps.stopFollowing();
    if (prefersReducedMotion()) map.jumpTo({ center: cam.center, zoom: cam.zoom });
    else map.flyTo({ center: cam.center, zoom: cam.zoom, duration: DURATION_CAMERA, essential: false });
  }

  /** 40 px plus the UI that covers each edge (screen spec › Camera rules). */
  private paddingForUi(): { top: number; bottom: number; left: number; right: number } {
    const vh = window.innerHeight;
    const top = this.topBar.getBoundingClientRect().bottom;
    let bottom = vh - this.attribution.getBoundingClientRect().top;
    let left = 0;
    if (this.card.isOpen && !this.card.wrapper.hidden) {
      const r = this.card.card.getBoundingClientRect();
      if (this.card.inSidePanel) left = r.right;
      else bottom = Math.max(bottom, vh - r.top);
    }
    return {
      top: Math.max(0, top) + CAMERA_PADDING_PX,
      bottom: Math.max(0, bottom) + CAMERA_PADDING_PX,
      left: Math.max(0, left) + CAMERA_PADDING_PX,
      right: CAMERA_PADDING_PX,
    };
  }

  // ------------------------------------------------------------------ coordinate card from the map (AC 25)

  private onContextMenu(e: MapMouseEvent): void {
    e.originalEvent?.preventDefault(); // the browser menu is suppressed on the canvas only
    const down = this.rightDown;
    this.rightDown = null;
    if (this.longPress.consumeContextMenu()) return; // Chrome on touch: same gesture as the long-press (during or after it)
    const oe = e.originalEvent as MouseEvent | undefined;
    // A right-drag (rotate) that ends with contextmenu is a drag, not a press.
    if (down && oe && Math.hypot(oe.clientX - down.x, oe.clientY - down.y) > LONG_PRESS_TOLERANCE_PX) return;
    if (!this.deps.mapReady()) return;
    this.openPoint({ lat: e.lngLat.lat, lon: wrapLon(e.lngLat.lng) });
  }

  private openPointAtClient(clientX: number, clientY: number): void {
    const map = this.deps.map();
    if (!map || !this.deps.mapReady()) return;
    const r = map.getCanvasContainer().getBoundingClientRect();
    const ll = map.unproject([clientX - r.left, clientY - r.top]);
    this.openPoint({ lat: ll.lat, lon: wrapLon(ll.lng) });
  }

  /** The camera does not move (AC 25). */
  private openPoint(p: LatLon): void {
    if (this.controller.view.state !== "closed") this.controller.close();
    this.card.showPoint(p);
    this.card.focusHeading();
  }
}

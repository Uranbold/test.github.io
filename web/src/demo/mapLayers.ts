// Demo map layers (map-style.md §7.5): the selected entry's route line in the existing `nav-route` source and
// `nav-route-sel*` layers (picker: §7.2 widths; replay: the §7.4 guidance widths), the origin marker and the
// destination pin (§7.3, §7.1), and the navigation puck (§7.4 chevron) as a decorative HTML marker that glides along the
// route between snapped fixes and never moves backwards (navigation-ux §11.2). No new colours: every value is a
// route.*, pin.* or nav.* token. The style transform keeps the line through day/night switches (AC 41).
import { Marker, type ExpressionSpecification, type GeoJSONSource, type Map as MapLibreMap, type StyleSpecification } from "maplibre-gl";
import { along, distance, type LatLon } from "../guidance/geo";
import { ROUTE_SEL_CASING_LAYER_ID, ROUTE_SEL_LAYER_ID, ROUTE_SOURCE_ID, type RouteLineData } from "../route/routeLayers";
import { ICONS } from "../ui/icons";

export type LinePhase = "picker" | "replay";

const width = (stops: number[]): ExpressionSpecification =>
  ["interpolate", ["linear"], ["zoom"], ...stops] as unknown as ExpressionSpecification;
/** §7.2 preview widths (z5/10/14/18) and §7.4/§7.5 guidance widths (z12/15/18). */
const WIDTHS: Record<LinePhase, { casing: ExpressionSpecification; line: ExpressionSpecification }> = {
  picker: { casing: width([5, 8, 10, 10, 14, 12, 18, 16]), line: width([5, 4, 10, 6, 14, 8, 18, 12]) },
  replay: { casing: width([12, 10, 15, 14, 18, 18]), line: width([12, 6, 15, 9, 18, 13]) },
};

const EMPTY: RouteLineData = { type: "FeatureCollection", features: [] };

const PUCK_SVG =
  '<svg viewBox="0 0 40 40" aria-hidden="true" focusable="false"><path class="puck-outline" d="M20 3 34 36 20 28.5 6 36Z"/><path class="puck-ring" d="M20 3 34 36 20 28.5 6 36Z"/><path class="puck-arrow" d="M20 3 34 36 20 28.5 6 36Z"/></svg>';

export class DemoMapLayers {
  private data: RouteLineData = EMPTY;
  private phase: LinePhase = "picker";
  private map: MapLibreMap | null = null;
  private originMarker: Marker | null = null;
  private destMarker: Marker | null = null;
  private puck: Marker | null = null;
  private readonly puckEl: HTMLElement;
  // glide state (along-route metres on `line`)
  private line: LatLon[] = [];
  private cumulative: number[] = [];
  private shownAlong = 0;
  private fromAlong = 0;
  private toAlong = 0;
  private glideStart = 0;
  private glideMs = 1000;
  private frame = 0;
  private course = 0;
  reducedMotion = false;

  constructor() {
    this.puckEl = document.createElement("div");
    this.puckEl.className = "demo-puck";
    this.puckEl.setAttribute("aria-hidden", "true");
    this.puckEl.dataset.testid = "demo-nav-puck";
    this.puckEl.innerHTML = PUCK_SVG;
  }

  attach(map: MapLibreMap): void {
    this.map = map;
  }

  /** App style transform: our line data and the phase's widths, in every style the app sets. */
  transform(style: StyleSpecification): StyleSpecification {
    const src = style.sources[ROUTE_SOURCE_ID];
    if (src && src.type === "geojson") style.sources[ROUTE_SOURCE_ID] = { ...src, data: this.data };
    const w = WIDTHS[this.phase];
    style.layers = style.layers.map((l) => {
      if (l.id === ROUTE_SEL_CASING_LAYER_ID && l.type === "line") return { ...l, paint: { ...l.paint, "line-width": w.casing } };
      if (l.id === ROUTE_SEL_LAYER_ID && l.type === "line") return { ...l, paint: { ...l.paint, "line-width": w.line } };
      return l;
    });
    return style;
  }

  /** Draws (or with null removes) the selected route with its markers. */
  setRoute(line: [number, number][] | null, names?: { origin: string; destination: string }): void {
    this.data = line ? { type: "FeatureCollection", features: [{ type: "Feature", properties: { index: 0, selected: true }, geometry: { type: "LineString", coordinates: line } }] } : EMPTY;
    this.map?.getSource<GeoJSONSource>(ROUTE_SOURCE_ID)?.setData(this.data);
    this.originMarker?.remove();
    this.destMarker?.remove();
    this.originMarker = null;
    this.destMarker = null;
    this.removePuck();
    this.line = line ? line.map(([lng, lat]) => ({ lat, lon: lng })) : [];
    this.cumulative = [0];
    for (let i = 1; i < this.line.length; i++) this.cumulative.push(this.cumulative[i - 1]! + distance(this.line[i - 1]!, this.line[i]!));
    if (!line || !this.map || line.length < 2 || !names) return;
    const o = document.createElement("div");
    o.className = "route-origin-marker";
    o.setAttribute("role", "img");
    o.setAttribute("aria-label", names.origin);
    o.dataset.testid = "demo-origin-marker";
    this.originMarker = new Marker({ element: o, anchor: "center" }).setLngLat(line[0]!).addTo(this.map);
    const d = document.createElement("div");
    d.className = "place-pin";
    d.setAttribute("role", "img");
    d.setAttribute("aria-label", names.destination);
    d.dataset.testid = "demo-destination-pin";
    d.innerHTML = ICONS.pin;
    this.destMarker = new Marker({ element: d, anchor: "bottom" }).setLngLat(line[line.length - 1]!).addTo(this.map);
  }

  /** Updates the accessible names after a language switch (the «Сонгосон цэг» fallback follows the UI language). */
  renameMarkers(names: { origin: string; destination: string }): void {
    this.originMarker?.getElement().setAttribute("aria-label", names.origin);
    this.destMarker?.getElement().setAttribute("aria-label", names.destination);
  }

  setPhase(phase: LinePhase): void {
    this.phase = phase;
    const m = this.map;
    if (!m) return;
    const w = WIDTHS[phase];
    if (m.getLayer(ROUTE_SEL_CASING_LAYER_ID)) m.setPaintProperty(ROUTE_SEL_CASING_LAYER_ID, "line-width", w.casing);
    if (m.getLayer(ROUTE_SEL_LAYER_ID)) m.setPaintProperty(ROUTE_SEL_LAYER_ID, "line-width", w.line);
    if (phase === "replay") {
      // As NAV-005: no origin marker during guidance; the destination pin stays.
      this.originMarker?.remove();
      this.originMarker = null;
    }
  }

  /** Along-route position of `p`, searched near the last shown position (loops and roundabouts stay unambiguous). */
  alongOf(p: LatLon): number {
    const line = this.line;
    if (line.length < 2) return 0;
    const lo = this.shownAlong - 60;
    const hi = this.shownAlong + 400;
    let best = Number.POSITIVE_INFINITY;
    let bestAlong = this.shownAlong;
    for (let pass = 0; pass < 2 && !Number.isFinite(best); pass++) {
      for (let i = 0; i < line.length - 1; i++) {
        const a0 = this.cumulative[i]!;
        const a1 = this.cumulative[i + 1]!;
        if (pass === 0 && (a1 < lo || a0 > hi)) continue;
        const a = line[i]!;
        const b = line[i + 1]!;
        const kx = Math.cos((p.lat * Math.PI) / 180);
        const ax = (a.lon - p.lon) * kx;
        const ay = a.lat - p.lat;
        const bx = (b.lon - p.lon) * kx;
        const by = b.lat - p.lat;
        const dx = bx - ax;
        const dy = by - ay;
        const len2 = dx * dx + dy * dy;
        const t = len2 === 0 ? 0 : Math.min(1, Math.max(0, -(ax * dx + ay * dy) / len2));
        const cx = ax + t * dx;
        const cy = ay + t * dy;
        const d = cx * cx + cy * cy;
        if (d < best) {
          best = d;
          bestAlong = a0 + t * (a1 - a0);
        }
      }
    }
    return bestAlong;
  }

  /** Places the puck at the first fix without a glide. */
  placePuck(p: LatLon, courseDeg: number): void {
    if (!this.map) return;
    this.shownAlong = this.alongOf(p);
    this.fromAlong = this.toAlong = this.shownAlong;
    this.course = courseDeg;
    const pos = along(this.line, this.shownAlong);
    if (!this.puck) this.puck = new Marker({ element: this.puckEl, anchor: "center", rotationAlignment: "map", pitchAlignment: "map" });
    this.puck.setLngLat([pos.lon, pos.lat]).setRotation(courseDeg).addTo(this.map);
  }

  /**
   * A new snapped fix: glide from the shown position to it over `ms` (linear by distance), never backwards (a fix
   * behind the shown position makes the puck hold). Returns the target position (the camera eases to it).
   */
  movePuck(p: LatLon, courseDeg: number, ms = 1000): LatLon {
    const target = this.alongOf(p);
    this.course = courseDeg;
    this.puck?.setRotation(courseDeg);
    if (target <= this.shownAlong) {
      this.toAlong = this.fromAlong = this.shownAlong;
      return along(this.line, this.shownAlong);
    }
    if (this.reducedMotion) {
      this.shownAlong = this.fromAlong = this.toAlong = target;
      this.render();
      return along(this.line, target);
    }
    this.fromAlong = this.shownAlong;
    this.toAlong = target;
    this.glideStart = performance.now();
    this.glideMs = ms;
    if (!this.frame) this.frame = requestAnimationFrame(() => this.animate());
    return along(this.line, target);
  }

  /** Current shown puck position. */
  get puckPosition(): LatLon | null {
    return this.puck && this.line.length > 1 ? along(this.line, this.shownAlong) : null;
  }

  get puckCourse(): number {
    return this.course;
  }

  /** Target of the current glide (where the camera should be). */
  get puckTarget(): LatLon | null {
    return this.puck && this.line.length > 1 ? along(this.line, this.toAlong) : null;
  }

  removePuck(): void {
    if (this.frame) cancelAnimationFrame(this.frame);
    this.frame = 0;
    this.puck?.remove();
    this.puck = null;
    this.shownAlong = this.fromAlong = this.toAlong = 0;
  }

  private animate(): void {
    this.frame = 0;
    const f = Math.min(1, (performance.now() - this.glideStart) / this.glideMs);
    this.shownAlong = Math.max(this.shownAlong, this.fromAlong + (this.toAlong - this.fromAlong) * f);
    this.render();
    if (f < 1) this.frame = requestAnimationFrame(() => this.animate());
  }

  private render(): void {
    if (!this.puck || this.line.length < 2) return;
    const pos = along(this.line, this.shownAlong);
    this.puck.setLngLat([pos.lon, pos.lat]);
  }
}

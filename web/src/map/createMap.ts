import { Map as MapLibreMap, type StyleSpecification } from "maplibre-gl";

/** P1 Sükhbaatar Square (NAV-001 reference location). */
export const P1: [number, number] = [106.9176, 47.9189];
export const INITIAL_ZOOM = 12;
export const MIN_ZOOM = 3;
export const MAX_ZOOM = 19;

export interface CreateMapOptions {
  container: HTMLElement;
  style: StyleSpecification;
  mapLabel: string;
}

/** Map canvas per screen spec › Components › Map canvas (AC 5, 11–13). Built-in controls are not used. */
export function createMap(o: CreateMapOptions): MapLibreMap {
  return new MapLibreMap({
    container: o.container,
    style: o.style,
    center: P1,
    zoom: INITIAL_ZOOM,
    bearing: 0,
    minZoom: MIN_ZOOM,
    maxZoom: MAX_ZOOM,
    attributionControl: false,
    keyboard: true,
    dragRotate: true,
    dragPan: true,
    touchZoomRotate: true,
    scrollZoom: true,
    doubleClickZoom: true,
    renderWorldCopies: true,
    locale: { "Map.Title": o.mapLabel },
  });
}

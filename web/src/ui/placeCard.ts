// Place card, coordinate card and the single pin (NAV-003 AC 21–30, 36–38, 43; screen spec › Components › Place card,
// Coordinate card, Pin; flows F2, F3).
import { Marker, type Map as MapLibreMap } from "maplibre-gl";
import type { I18n, MessageKey } from "../i18n/i18n";
import { formatCoordinate, type LatLon } from "../search/coords";
import { resultInfo, type ResultInfo } from "../search/display";
import type { PhotonFeature } from "../search/photon";
import type { NearView, ReverseController } from "../search/reverseController";
import { ICONS } from "./icons";
import { partLang, STATE_ROW } from "./searchBox";
import type { Tooltip } from "./tooltip";

function el<T extends HTMLElement = HTMLElement>(id: string): T {
  const e = document.getElementById(id);
  if (!e) throw new Error(`missing #${id}`);
  return e as T;
}

type Content =
  | { kind: "place"; feature: PhotonFeature; info: ResultInfo; name: string }
  | { kind: "point"; point: LatLon; nearName: string | null };

export interface PlaceCardDeps {
  i18n: I18n;
  map: () => MapLibreMap | null;
  reverse: ReverseController;
  tooltip: Tooltip;
  /** The card was closed by the user: focus goes back to the search input (AC 22). */
  onClosed: () => void;
}

export class PlaceCard {
  readonly wrapper = el("place");
  readonly card = el("place-card");
  private readonly title = el("place-card-title");
  private readonly closeBtn = el<HTMLButtonElement>("place-card-close");
  private readonly meta = el("place-card-meta");
  private readonly type = el("place-card-type");
  private readonly context = el("place-card-context");
  private readonly coords = el("place-card-coords");
  private readonly near = el("place-nearest");
  private readonly nearPlace = el("place-nearest-place");
  private readonly nearName = el("place-nearest-name");
  private readonly nearType = el("place-nearest-type");
  private readonly nearContext = el("place-nearest-context");
  private readonly nearState = el("place-nearest-state");
  private readonly nearStateText = el("place-nearest-state-text");
  private readonly retryBtn = el<HTMLButtonElement>("place-retry");
  private content: Content | null = null;
  private listOpen = false;
  private marker: Marker | null = null;
  private readonly pinEl: HTMLElement;

  constructor(private readonly deps: PlaceCardDeps) {
    this.pinEl = document.createElement("div");
    this.pinEl.className = "place-pin";
    this.pinEl.setAttribute("role", "img");
    this.pinEl.dataset.testid = "place-pin";
    this.pinEl.innerHTML = ICONS.pin;
    deps.reverse.onChange((v) => this.renderNear(v));
    this.closeBtn.addEventListener("click", () => this.closeByUser());
    this.card.addEventListener("keydown", (e) => {
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        this.closeByUser();
      }
    });
    this.retryBtn.addEventListener("click", () => {
      if (this.retryBtn.getAttribute("aria-disabled") === "true") return;
      this.title.focus();
      deps.reverse.retry();
    });
    deps.tooltip.attach(this.closeBtn, "below");
  }

  get isOpen(): boolean {
    return this.content !== null;
  }

  /** The card is in the left panel (≥ 840 px) rather than the bottom row (NAV-003 layout rule 3). */
  get inSidePanel(): boolean {
    return window.matchMedia?.("(min-width: 840px)").matches ?? false;
  }

  /** Result card (AC 21): pin at the feature, heading = name, type label · context line, coordinates. */
  showPlace(feature: PhotonFeature): void {
    this.deps.reverse.close();
    const info = resultInfo(feature);
    this.content = { kind: "place", feature, info, name: info.name ?? this.deps.i18n.t(info.typeKey) };
    this.placePin({ lat: info.lat, lon: info.lon });
    this.render();
  }

  /** Coordinate card (AC 25–27): pin at the point, heading «Сонгосон цэг», one `reverse` request. */
  showPoint(point: LatLon): void {
    this.content = { kind: "point", point, nearName: null };
    this.placePin(point);
    this.render();
    this.deps.reverse.open(point);
  }

  /** Removes pin and card (close button, Escape, or replaced by nothing). */
  close(): void {
    this.content = null;
    this.deps.reverse.close();
    this.marker?.remove();
    this.marker = null;
    this.render();
  }

  /** Layout rule 5: while the results list is open, the card is hidden, not closed. The pin stays. */
  setListOpen(open: boolean): void {
    this.listOpen = open;
    this.wrapper.hidden = this.content === null || open;
  }

  focusHeading(): void {
    if (!this.wrapper.hidden) this.title.focus({ preventScroll: true });
  }

  /** Language switch (AC 38): labels switch, the place name stays. */
  renderI18n(): void {
    this.render();
    this.renderNear(this.deps.reverse.view);
  }

  private closeByUser(): void {
    this.deps.tooltip.hide();
    this.close();
    this.deps.onClosed();
  }

  private placePin(p: LatLon): void {
    const map = this.deps.map();
    if (!map) return;
    if (!this.marker) this.marker = new Marker({ element: this.pinEl, anchor: "bottom" });
    this.marker.setLngLat([p.lon, p.lat]).addTo(map);
  }

  private render(): void {
    const c = this.content;
    const t = (k: MessageKey) => this.deps.i18n.t(k);
    this.wrapper.hidden = c === null || this.listOpen;
    if (!c) {
      this.card.removeAttribute("data-kind");
      return;
    }
    this.card.dataset.kind = c.kind;
    const lang = this.deps.i18n.lang;
    if (c.kind === "place") {
      this.title.textContent = c.name;
      setLang(this.title, partLang(c.name, lang));
      this.pinEl.setAttribute("aria-label", c.name);
      this.meta.hidden = false;
      this.type.textContent = t(c.info.typeKey);
      setLang(this.type, partLang(this.type.textContent, lang)); // «Сум» in the English UI (D34)
      this.context.textContent = c.info.context ?? "";
      setLang(this.context, c.info.context ? partLang(c.info.context, lang) : null);
      this.coords.textContent = formatCoordinate({ lat: c.info.lat, lon: c.info.lon });
      this.near.hidden = true;
    } else {
      this.title.textContent = t("place.selectedPoint");
      setLang(this.title, null);
      this.pinEl.setAttribute("aria-label", t("place.selectedPoint"));
      this.meta.hidden = true;
      this.coords.textContent = formatCoordinate(c.point);
      this.near.hidden = false;
    }
    this.pinEl.dataset.kind = c.kind;
  }

  private renderNear(v: NearView | null): void {
    const c = this.content;
    if (!c || c.kind !== "point" || !v) {
      this.near.removeAttribute("data-state");
      return;
    }
    const t = (k: MessageKey) => this.deps.i18n.t(k);
    this.near.dataset.state = v.state;
    if (v.state === "place" && v.feature) {
      const info = resultInfo(v.feature);
      // The nearest place keeps the name it arrived with when the language switches (AC 38).
      if (c.nearName === null) c.nearName = info.name ?? t(info.typeKey);
      this.nearPlace.hidden = false;
      this.nearState.hidden = true;
      this.nearName.textContent = c.nearName;
      setLang(this.nearName, partLang(c.nearName, this.deps.i18n.lang));
      this.nearType.textContent = t(info.typeKey);
      setLang(this.nearType, partLang(this.nearType.textContent, this.deps.i18n.lang)); // «Сум» in the English UI (D34)
      this.nearContext.textContent = info.context ?? "";
      setLang(this.nearContext, info.context ? partLang(info.context, this.deps.i18n.lang) : null);
      return;
    }
    c.nearName = null;
    this.nearPlace.hidden = true;
    if (v.state === "pending") {
      this.nearState.hidden = true; // empty 44 px area for the first 300 ms
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

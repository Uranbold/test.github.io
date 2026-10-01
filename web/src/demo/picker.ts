// D1 route picker (screen spec NAV-017 › Components: picker sheet, heading, list label, radio list, footer with the
// state row and «Эхлэх»; flow F2). Lives in the NAV-004 route slot, so it is a bottom sheet below 840 px and a side
// panel from 840 px (NAV-004 › Regions). Pure view: the controller decides what happens.
import type { I18n } from "../i18n/i18n";
import { formatDistance, formatDuration } from "../route/format";
import { ICONS } from "../ui/icons";
import { ROUTE_ICONS } from "../ui/routeIcons";
import { DEMO_ICONS, h, icon } from "./dom";
import type { DemoRouteSummary, ManifestEnd } from "./routeData";

export type PickerState = "none" | "loading" | "error" | "offline";

export interface PickerHandlers {
  select(index: number): void;
  retry(): void;
  start(): void;
}

/** Place name of a route end: manifest data, or the UI term «Сонгосон цэг» when the manifest has none (U1). */
export function placeName(end: ManifestEnd, i18n: I18n): { text: string; isData: boolean } {
  return end.name ? { text: end.name, isData: true } : { text: i18n.t("place.selectedPoint"), isData: false };
}

export class PickerView {
  readonly section: HTMLElement;
  readonly heading: HTMLHeadingElement;
  private readonly label: HTMLElement;
  private readonly rows: HTMLElement[] = [];
  private readonly stateRow: HTMLElement;
  private readonly stateIcon: HTMLElement;
  private readonly stateText: HTMLElement;
  readonly retryBtn: HTMLButtonElement;
  readonly startBtn: HTMLButtonElement;
  private readonly startLabel: HTMLElement;
  private selected = -1;
  private state: PickerState = "none";

  constructor(
    private readonly i18n: I18n,
    private readonly routes: readonly DemoRouteSummary[],
    private readonly on: PickerHandlers,
  ) {
    this.heading = h("h2", { id: "demo-heading", class: "demo-heading", tabindex: "-1", "data-testid": "demo-heading" });
    this.label = h("p", { id: "demo-list-label", class: "demo-list-label" });
    const list = h("div", { class: "demo-list", role: "radiogroup", "aria-labelledby": "demo-list-label" });
    routes.forEach((r, i) => {
      const row = h("div", { class: "demo-route", role: "radio", "aria-checked": "false", tabindex: i === 0 ? "0" : "-1", "data-testid": "demo-route", "data-route": r.label });
      row.addEventListener("click", () => this.on.select(i));
      row.addEventListener("keydown", (e) => this.onKey(e, i));
      this.rows.push(row);
      list.append(row);
    });
    this.stateIcon = h("span", { class: "icon", "aria-hidden": "true" });
    this.stateText = h("span", { class: "txt", id: "demo-state-text" });
    this.retryBtn = h("button", { type: "button", class: "act", "data-testid": "demo-retry", "aria-describedby": "demo-state-text" });
    this.retryBtn.addEventListener("click", () => this.on.retry());
    this.stateRow = h("div", { class: "state demo-state", "data-testid": "demo-state", hidden: "" }, this.stateIcon, this.stateText, this.retryBtn);
    const live = h("div", { role: "status", class: "demo-state-live" }, this.stateRow);
    this.startLabel = h("span", {});
    this.startBtn = h("button", { type: "button", class: "demo-start", "data-testid": "demo-start", "aria-disabled": "true" }, icon(DEMO_ICONS.play), this.startLabel);
    this.startBtn.addEventListener("click", () => {
      if (this.startBtn.getAttribute("aria-disabled") === "true") return;
      this.on.start();
    });
    const footer = h("div", { class: "demo-footer" }, live, this.startBtn);
    this.section = h("section", { class: "demo-picker", "aria-labelledby": "demo-heading", "data-testid": "demo-picker" }, h("div", { class: "demo-head" }, this.heading, this.label), list, footer);
    this.render();
  }

  private onKey(e: KeyboardEvent, i: number): void {
    const n = this.rows.length;
    let to: number | null = null;
    if (e.key === "ArrowDown" || e.key === "ArrowRight") to = (i + 1) % n;
    else if (e.key === "ArrowUp" || e.key === "ArrowLeft") to = (i - 1 + n) % n;
    else if (e.key === " " || e.key === "Enter") to = i;
    if (to === null) return;
    e.preventDefault();
    this.rows[to]!.focus();
    this.on.select(to);
  }

  get selectedIndex(): number {
    return this.selected;
  }

  setSelected(index: number): void {
    this.selected = index;
    this.rows.forEach((r, i) => {
      r.setAttribute("aria-checked", String(i === index));
      r.tabIndex = i === (index >= 0 ? index : 0) ? 0 : -1;
    });
  }

  setState(state: PickerState): void {
    this.state = state;
    this.renderState();
  }

  setStartEnabled(on: boolean): void {
    this.startBtn.setAttribute("aria-disabled", String(!on));
  }

  focusSelectedRow(): void {
    this.rows[Math.max(0, this.selected)]?.focus();
  }

  render(): void {
    const t = this.i18n;
    this.heading.textContent = t.t("demo.mode");
    this.label.textContent = t.t("route.options");
    this.startLabel.textContent = t.t("nav.start");
    this.retryBtn.textContent = t.t("action.retry");
    this.routes.forEach((r, i) => this.renderRow(this.rows[i]!, r));
    this.renderState();
  }

  private renderRow(row: HTMLElement, r: DemoRouteSummary): void {
    const t = (k: Parameters<I18n["t"]>[0]) => this.i18n.t(k);
    const lang = this.i18n.lang;
    const o = placeName(r.origin, this.i18n);
    const d = placeName(r.destination, this.i18n);
    const mode = t(r.mode === "walk" ? "route.mode.walk" : "route.mode.car");
    const meta = `${mode} · ${formatDistance(r.distance, lang, t)} · ${formatDuration(r.duration, t)}`;
    const name = (p: { text: string; isData: boolean }) => {
      const s = h("span", { class: "demo-name" }, p.text);
      if (p.isData && lang !== "mn") s.lang = "mn";
      return s;
    };
    row.replaceChildren(
      h("span", { class: "demo-radio", "aria-hidden": "true" }),
      h(
        "span",
        { class: "demo-route-text", "aria-hidden": "true" },
        h("span", { class: "demo-line" }, icon(ROUTE_ICONS.ring, "demo-end-icon origin"), name(o)),
        h("span", { class: "demo-line" }, icon(ICONS.pin, "demo-end-icon dest"), name(d)),
        h("span", { class: "demo-meta" }, icon(r.mode === "walk" ? ROUTE_ICONS.walk : ROUTE_ICONS.car, "demo-mode-icon"), meta),
      ),
    );
    row.setAttribute("aria-label", [t("route.origin"), o.text, t("route.destination"), d.text, meta].join(", "));
  }

  private renderState(): void {
    const s = this.state;
    this.stateRow.hidden = s === "none";
    this.stateRow.dataset.state = s;
    this.stateRow.classList.toggle("has-act", s === "error");
    this.retryBtn.hidden = s !== "error";
    if (s === "none") {
      this.stateText.textContent = "";
      return;
    }
    const t = (k: Parameters<I18n["t"]>[0]) => this.i18n.t(k);
    this.stateText.textContent = t(s === "loading" ? "status.loading" : s === "error" ? "status.genericError" : "status.offline");
    this.stateIcon.innerHTML = s === "loading" ? '<span class="spin small"></span>' : s === "error" ? ICONS.error : ICONS.cloudOff;
  }
}

// Origin / destination field of the route preview (NAV-004 AC 4–6; screen spec › Components › Origin field /
// destination field, Field results list; Interactions › Editing a field). Each field reuses the NAV-003
// SearchController unchanged (debounce, request rules, bias, states, typed coordinates, AC 6) and renders its own
// WAI-ARIA 1.2 combobox: focus stays in the input, the highlight is aria-activedescendant.
// The origin field adds one «Миний байршил» option when it is focused and empty (AC 5), with no request.
import type { I18n, MessageKey } from "../i18n/i18n";
import { formatCoordinate } from "../search/coords";
import { resultInfo } from "../search/display";
import type { Announcement, SearchController, SearchOption, SearchView } from "../search/searchController";
import { ROUTE_ICONS } from "./routeIcons";
import { ICONS } from "./icons";
import { partLang, STATE_ROW } from "./searchBox";

/** A picked option: a search result, a typed coordinate, or «Миний байршил». */
export type FieldPick = SearchOption | { kind: "myLocation" };

export interface FieldList {
  container: HTMLElement;
  stateRow: HTMLElement;
  stateText: HTMLElement;
  retryBtn: HTMLButtonElement;
}

export interface RouteFieldDeps {
  which: "origin" | "destination";
  i18n: I18n;
  input: HTMLInputElement;
  listbox: HTMLUListElement;
  list: FieldList;
  controller: SearchController;
  /** Current text of the field's point, or "" (restored on Esc, Tab, click elsewhere). */
  pointText: () => string;
  onPick: (p: FieldPick) => void;
  /** The list opened: the other field closes its own (only one list at a time). */
  onOpen: () => void;
  /** Live region shared with NAV-003 search (result counts and search states). */
  live: HTMLElement;
}

type Row = { kind: "option"; option: FieldPick };

export class RouteField {
  private highlight = -1;
  private rows: Row[] = [];
  private myLocationOpen = false;
  private active = false;
  private renderedOptions: readonly SearchOption[] | null = null;

  constructor(private readonly deps: RouteFieldDeps) {
    const { input, controller } = deps;
    controller.onChange((v) => this.render(v));
    controller.onAnnounce((a) => {
      if (this.active) deps.live.textContent = this.announcementText(a);
    });
    controller.onAutoSelect((o) => this.pick(o));
    input.addEventListener("input", () => {
      this.setHighlight(-1);
      this.myLocationOpen = false;
      if (input.value === "" && deps.which === "origin") {
        controller.clear();
        this.openMyLocation();
        return;
      }
      controller.input(input.value);
    });
    input.addEventListener("focus", () => {
      if (deps.which === "origin" && input.value === "") this.openMyLocation();
    });
    input.addEventListener("keydown", (e) => this.onKeyDown(e));
    input.addEventListener("focusout", (e) => {
      const to = e.relatedTarget as Node | null;
      if (to && (deps.list.container.contains(to) || to === input)) return;
      // As the NAV-003 box: the language and theme buttons keep the list open, so a language switch re-requests it.
      if (to instanceof Element && to.closest(".cluster")) return;
      // A click elsewhere without a pick restores the point's text (Interactions › Editing a field).
      if (this.isOpen && !(to && this.staysOpenOnTab())) this.closeAndRestore();
    });
    deps.listbox.addEventListener("mousedown", (e) => e.preventDefault());
    deps.listbox.addEventListener("click", (e) => {
      const li = (e.target as HTMLElement).closest<HTMLElement>("[role=option]");
      if (!li) return;
      const row = this.rows[Number(li.dataset.index)];
      if (row) this.pick(row.option);
    });
  }

  get isOpen(): boolean {
    return this.myLocationOpen || this.deps.controller.view.state !== "closed";
  }

  /** The shared list container shows this field's view. */
  get isActive(): boolean {
    return this.active;
  }

  /** Shows the field text of the current point (after a pick, a swap or a language switch). */
  syncText(): void {
    if (this.isOpen && document.activeElement === this.deps.input) return; // the user is typing
    this.deps.input.value = this.deps.pointText();
    this.setLangOfValue();
  }

  /** Closes the list without a pick and restores the point's text. */
  closeAndRestore(): void {
    this.myLocationOpen = false;
    this.deps.controller.accept(this.deps.pointText());
    this.deps.input.value = this.deps.pointText();
    this.setLangOfValue();
    this.render(this.deps.controller.view);
  }

  /** Another field opened its list. */
  closeQuietly(): void {
    if (!this.isOpen) {
      this.active = false;
      return;
    }
    this.closeAndRestore();
    this.active = false;
  }

  focus(): void {
    this.deps.input.focus({ preventScroll: false });
  }

  renderI18n(): void {
    this.renderedOptions = null;
    this.render(this.deps.controller.view);
    this.syncText();
  }

  /** UI language switch: an open list is re-requested once with the new `lang` (NAV-003 AC 38 behaviour). */
  langChanged(): void {
    if (this.deps.controller.view.state !== "closed") this.deps.controller.langChanged();
  }

  /** `online` event: an offline list re-searches once (NAV-003 AC 34 behaviour). */
  online(): void {
    this.deps.controller.online();
  }

  /** «Дахин оролдох» in the shared list (bound once by the panel, routed to the active field). */
  retry(): void {
    if (this.deps.list.retryBtn.getAttribute("aria-disabled") === "true") return;
    this.deps.input.focus();
    this.deps.controller.retry();
  }

  // ------------------------------------------------------------------ internals

  private openMyLocation(): void {
    this.myLocationOpen = true;
    this.render(this.deps.controller.view);
  }

  private pick(o: FieldPick): void {
    this.myLocationOpen = false;
    if (o.kind === "place") {
      const info = resultInfo(o.feature);
      this.deps.controller.accept(info.name ?? this.deps.i18n.t(info.typeKey));
    } else {
      this.deps.controller.accept(this.deps.input.value);
    }
    this.render(this.deps.controller.view);
    this.deps.onPick(o);
    this.syncText();
  }

  private hasOptions(): boolean {
    return this.rows.length > 0;
  }

  private staysOpenOnTab(): boolean {
    const v = this.deps.controller.view;
    return (v.state === "unavailable" || v.state === "rate-limited") && v.retry !== "none";
  }

  private onKeyDown(e: KeyboardEvent): void {
    if (e.isComposing) return;
    const c = this.deps.controller;
    switch (e.key) {
      case "ArrowDown":
      case "ArrowUp": {
        if (!this.hasOptions()) {
          if (e.key === "ArrowDown") {
            if (this.deps.which === "origin" && this.deps.input.value === "") {
              e.preventDefault();
              this.openMyLocation();
            } else if (c.reopen()) e.preventDefault();
          }
          return;
        }
        e.preventDefault();
        const n = this.rows.length;
        const next = this.highlight < 0 ? (e.key === "ArrowDown" ? 0 : n - 1) : (this.highlight + (e.key === "ArrowDown" ? 1 : -1) + n) % n;
        this.setHighlight(next);
        return;
      }
      case "Enter": {
        e.preventDefault();
        const row = this.highlight >= 0 ? this.rows[this.highlight] : undefined;
        if (row) this.pick(row.option);
        else if (!this.myLocationOpen) c.submit();
        return;
      }
      case "Escape":
        if (this.isOpen) {
          // Esc with the list open closes the list and restores the point's text; it does not close the panel.
          e.preventDefault();
          e.stopPropagation();
          this.closeAndRestore();
        }
        return;
      case "Tab":
        if (this.isOpen && !this.staysOpenOnTab()) this.closeAndRestore();
        return;
    }
  }

  private setHighlight(i: number): void {
    const prev = this.highlight;
    this.highlight = i;
    if (prev >= 0) this.deps.listbox.children[prev]?.setAttribute("aria-selected", "false");
    const li = i >= 0 ? (this.deps.listbox.children[i] as HTMLElement | undefined) : undefined;
    if (li) {
      li.setAttribute("aria-selected", "true");
      this.deps.input.setAttribute("aria-activedescendant", li.id);
      li.scrollIntoView({ block: "nearest" });
    } else {
      this.deps.input.removeAttribute("aria-activedescendant");
    }
  }

  private render(v: SearchView): void {
    const { list, listbox, input } = this.deps;
    const focusOnRetry = document.activeElement === list.retryBtn;
    const open = this.myLocationOpen || v.state !== "closed";
    if (open && !this.active) {
      this.deps.onOpen();
      this.active = true;
    }
    if (!this.active) return;
    const searchOptions = (v.state === "results" || v.state === "coordinate") && !this.myLocationOpen ? v.options : [];
    const rows: Row[] = this.myLocationOpen ? [{ kind: "option", option: { kind: "myLocation" } }] : searchOptions.map((o) => ({ kind: "option", option: o }));
    if (this.myLocationOpen || searchOptions !== this.renderedOptions) {
      this.renderRows(rows);
      this.renderedOptions = this.myLocationOpen ? null : searchOptions;
    }
    this.rows = rows;
    const hasOptions = rows.length > 0;
    listbox.hidden = !hasOptions;
    listbox.setAttribute("aria-busy", String(v.busy));
    input.setAttribute("aria-expanded", String(open && hasOptions));
    if (!hasOptions) this.setHighlight(-1);
    list.container.hidden = !open;
    list.container.dataset.field = this.deps.which;
    list.container.dataset.state = this.myLocationOpen ? "my-location" : v.state;
    list.container.setAttribute("aria-busy", String(v.busy));
    if (open && !hasOptions && !this.myLocationOpen) this.renderState(v);
    else list.stateRow.hidden = true;
    if (focusOnRetry && (list.container.hidden || list.stateRow.hidden || list.retryBtn.hidden)) input.focus();
    if (!open) this.active = false;
  }

  private renderRows(rows: readonly Row[]): void {
    this.highlight = -1;
    this.deps.input.removeAttribute("aria-activedescendant");
    const lang = this.deps.i18n.lang;
    const t = (k: MessageKey) => this.deps.i18n.t(k);
    const items = rows.map((row, i) => {
      const o = row.option;
      const li = document.createElement("li");
      li.id = `route-${this.deps.which}-option-${i}`;
      li.className = "opt";
      li.setAttribute("role", "option");
      li.setAttribute("aria-selected", "false");
      li.dataset.index = String(i);
      li.dataset.kind = o.kind;
      const tx = document.createElement("span");
      tx.className = "tx";
      const nm = document.createElement("span");
      nm.className = "nm";
      tx.append(nm);
      if (o.kind === "myLocation") {
        li.dataset.testid = "route-my-location";
        li.innerHTML = ROUTE_ICONS.myLocation;
        nm.textContent = t("marker.myLocation");
        li.setAttribute("aria-label", nm.textContent);
      } else if (o.kind === "coordinate") {
        li.dataset.testid = "route-field-option";
        li.innerHTML = ICONS.crosshair;
        nm.textContent = t("place.selectedPoint");
        const mt = document.createElement("span");
        mt.className = "mt";
        mt.textContent = formatCoordinate(o.point);
        tx.append(mt);
        li.setAttribute("aria-label", [nm.textContent, mt.textContent].join(", "));
      } else {
        li.dataset.testid = "route-field-option";
        li.innerHTML = ICONS.place;
        const info = resultInfo(o.feature);
        const name = info.name ?? t(info.typeKey);
        nm.textContent = name;
        const pl = partLang(name, lang);
        if (pl) nm.lang = pl;
        const mt = document.createElement("span");
        mt.className = "mt";
        const ty = document.createElement("span");
        ty.className = "ty";
        ty.textContent = t(info.typeKey);
        const tl = partLang(ty.textContent, lang);
        if (tl) ty.lang = tl;
        mt.append(ty);
        if (info.context) {
          const cx = document.createElement("span");
          cx.className = "cx";
          cx.textContent = info.context;
          const cl = partLang(info.context, lang);
          if (cl) cx.lang = cl;
          mt.append(cx);
        }
        tx.append(mt);
        li.setAttribute("aria-label", [name, t(info.typeKey), info.context].filter((x): x is string => !!x).join(", "));
      }
      li.append(tx);
      return li;
    });
    this.deps.listbox.replaceChildren(...items);
  }

  private renderState(v: SearchView): void {
    const { list } = this.deps;
    const row = STATE_ROW[v.state as keyof typeof STATE_ROW];
    if (!row) {
      list.stateRow.hidden = true;
      return;
    }
    list.stateRow.hidden = false;
    list.stateRow.dataset.state = v.state;
    list.stateRow.querySelector<HTMLElement>(".icon")!.innerHTML = row.icon ?? '<span class="spin"></span>';
    list.stateText.textContent = this.deps.i18n.t(row.key);
    list.retryBtn.hidden = v.retry === "none";
    list.stateRow.classList.toggle("has-act", v.retry !== "none");
    if (v.retry === "disabled") list.retryBtn.setAttribute("aria-disabled", "true");
    else list.retryBtn.removeAttribute("aria-disabled");
  }

  private announcementText(a: Announcement): string {
    if (a.kind === "count") return this.deps.i18n.resultCount(a.count);
    return this.deps.i18n.t(STATE_ROW[a.state].key);
  }

  /** Cyrillic field text in the English UI gets lang="mn" (NAV-003 language of parts). */
  private setLangOfValue(): void {
    const pl = partLang(this.deps.input.value, this.deps.i18n.lang);
    if (pl) this.deps.input.lang = pl;
    else this.deps.input.removeAttribute("lang");
  }
}

// Search field + results popup (NAV-003 screen spec › Components, Accessibility; story AC 1, 2, 13, 17–19, 31–35,
// 37, 40–42; flow F5). WAI-ARIA 1.2 combobox: focus stays in the input, the highlight is aria-activedescendant.
import type { I18n, MessageKey } from "../i18n/i18n";
import { formatCoordinate } from "../search/coords";
import { resultInfo } from "../search/display";
import type { Announcement, ListState, SearchController, SearchOption, SearchView } from "../search/searchController";
import { hasCyrillic, isLatinOnly } from "../search/text";
import { ICONS } from "./icons";
import type { Tooltip } from "./tooltip";

export interface SearchBoxDeps {
  i18n: I18n;
  controller: SearchController;
  tooltip: Tooltip;
  /** A result or the coordinate option was picked (click, Enter). */
  onSelect: (o: SearchOption) => void;
  /** The popup opened or closed (the place card hides while it is open, screen spec layout rule 5). */
  onOpenChange: (open: boolean) => void;
}

function el<T extends HTMLElement = HTMLElement>(id: string): T {
  const e = document.getElementById(id);
  if (!e) throw new Error(`missing #${id}`);
  return e as T;
}

/** Message key and icon of a state row (screen spec › States; shared with the place card). */
export const STATE_ROW: Record<Exclude<ListState, "closed" | "results" | "coordinate">, { key: MessageKey; icon: string | null }> = {
  loading: { key: "status.loading", icon: null },
  "no-results": { key: "search.noResults", icon: ICONS.searchOff },
  unavailable: { key: "search.unavailable", icon: ICONS.warning },
  offline: { key: "status.offline", icon: ICONS.cloudOff },
  "rate-limited": { key: "search.rateLimited", icon: ICONS.hourglass },
  error: { key: "status.genericError", icon: ICONS.error },
};

/** `lang` for a data value whose script differs from the UI language (screen spec › Language of parts). */
export function partLang(value: string, uiLang: string): string | null {
  if (uiLang === "en" && hasCyrillic(value)) return "mn";
  if (uiLang === "mn" && isLatinOnly(value)) return "en";
  return null;
}

export interface OptionText {
  name: string;
  type: string | null;
  context: string | null;
}

export class SearchBox {
  readonly input = el<HTMLInputElement>("search-input");
  private readonly clearBtn = el<HTMLButtonElement>("search-clear");
  private readonly popup = el("search-popup");
  private readonly listbox = el("search-results");
  private readonly stateRow = el("search-state");
  private readonly stateText = el("search-state-text");
  private readonly retryBtn = el<HTMLButtonElement>("search-retry");
  private readonly live = el("search-live");
  private highlight = -1;
  private renderedOptions: readonly SearchOption[] | null = null;
  private lastAnnouncement: Announcement | null = null;
  private wasOpen = false;

  constructor(private readonly deps: SearchBoxDeps) {
    const c = deps.controller;
    c.onChange((v) => this.render(v));
    c.onAnnounce((a) => this.announce(a));
    this.bind();
    deps.tooltip.attach(this.clearBtn, "below");
  }

  get view(): Readonly<SearchView> {
    return this.deps.controller.view;
  }

  /** Text of an option in the current UI language (AC 17–19, 26). */
  optionText(o: SearchOption): OptionText {
    const t = (k: MessageKey) => this.deps.i18n.t(k);
    if (o.kind === "coordinate") return { name: t("place.selectedPoint"), type: null, context: formatCoordinate(o.point) };
    const info = resultInfo(o.feature);
    return { name: info.name ?? t(info.typeKey), type: t(info.typeKey), context: info.context };
  }

  /** Picks an option: closes the list, the input shows the selected name (AC 21), the card opens (onSelect). */
  select(o: SearchOption): void {
    if (o.kind === "place") {
      const name = this.optionText(o).name;
      this.input.value = name;
      this.deps.controller.accept(name);
    } else {
      this.deps.controller.accept(this.input.value);
    }
    this.clearBtn.hidden = this.input.value === "";
    this.deps.onSelect(o);
  }

  focusInput(): void {
    this.input.focus();
  }

  /** Language switch: every text of the current view is re-rendered (AC 38). */
  renderI18n(): void {
    this.renderedOptions = null;
    this.render(this.view);
    if (this.lastAnnouncement) this.live.textContent = this.announcementText(this.lastAnnouncement);
  }

  // ------------------------------------------------------------------ events

  private bind(): void {
    const c = this.deps.controller;
    this.input.addEventListener("input", () => {
      this.clearBtn.hidden = this.input.value === "";
      this.setHighlight(-1);
      c.input(this.input.value);
    });
    this.input.addEventListener("keydown", (e) => this.onKeyDown(e));
    this.input.addEventListener("focusout", (e) => {
      const to = e.relatedTarget as Node | null;
      // Blur to the map closes an options list (screen spec › Results popup). Clicking the language or theme button
      // keeps it open, so a language switch re-requests the open list (AC 38). Tab is handled in onKeyDown.
      if (to && document.getElementById("map")?.contains(to) && this.hasOptions()) c.close();
    });
    this.clearBtn.addEventListener("click", () => {
      this.input.value = "";
      this.clearBtn.hidden = true;
      this.deps.tooltip.hide();
      c.clear();
      this.input.focus();
    });
    // Keep focus in the input when an option or the retry button is pressed with a mouse or finger.
    this.popup.addEventListener("mousedown", (e) => e.preventDefault());
    this.listbox.addEventListener("click", (e) => {
      const li = (e.target as HTMLElement).closest<HTMLElement>("[role=option]");
      if (!li) return;
      const o = this.view.options[Number(li.dataset.index)];
      if (o) this.select(o);
    });
    this.retryBtn.addEventListener("click", () => {
      if (this.retryBtn.getAttribute("aria-disabled") === "true") return;
      this.input.focus();
      c.retry();
    });
    // Esc on «Дахин оролдох» closes its state row and returns focus to the input; the text stays (AC 41,
    // screen spec › Interactions › Tab). Works while the button is aria-disabled (rate-limited) too.
    this.retryBtn.addEventListener("keydown", (e) => {
      if (e.key !== "Escape" || e.isComposing) return;
      e.preventDefault();
      this.input.focus();
      c.close();
    });
  }

  private hasOptions(): boolean {
    const s = this.view.state;
    return (s === "results" || s === "coordinate") && this.view.options.length > 0;
  }

  /**
   * A state row with «Дахин оролдох» (unavailable AC 33, rate-limited AC 35) stays open on Tab so the button can be
   * reached (AC 41 exception, PO approval F5, D33). Every other open view (options, loading, no results, offline,
   * bad request) closes on Tab.
   */
  private staysOpenOnTab(): boolean {
    const v = this.view;
    return (v.state === "unavailable" || v.state === "rate-limited") && v.retry !== "none";
  }

  private onKeyDown(e: KeyboardEvent): void {
    const c = this.deps.controller;
    if (e.isComposing) return;
    switch (e.key) {
      case "ArrowDown":
      case "ArrowUp": {
        if (!this.hasOptions()) {
          if (e.key === "ArrowDown" && c.reopen()) e.preventDefault();
          return;
        }
        e.preventDefault();
        const n = this.view.options.length;
        const next = this.highlight < 0 ? (e.key === "ArrowDown" ? 0 : n - 1) : (this.highlight + (e.key === "ArrowDown" ? 1 : -1) + n) % n;
        this.setHighlight(next);
        return;
      }
      case "Enter": {
        e.preventDefault();
        const o = this.highlight >= 0 && this.hasOptions() ? this.view.options[this.highlight] : undefined;
        if (o) this.select(o);
        else c.submit();
        return;
      }
      case "Escape": {
        if (this.view.state !== "closed") {
          e.preventDefault();
          c.close();
        } else if (this.input.value !== "") {
          e.preventDefault();
          this.input.value = "";
          this.clearBtn.hidden = true;
          c.clear();
        }
        return;
      }
      case "Tab":
        // Tab (and Shift+Tab) leaves the input and closes the popup without selecting (AC 41). Exception: a state
        // row with «Дахин оролдох» stays, so the button is the Tab stop after the top-bar cluster (screen spec ›
        // Interactions › Tab, Accessibility › Tab order). Focus moves on by the default action (not prevented).
        if (this.view.state !== "closed" && !this.staysOpenOnTab()) c.close();
        return;
    }
  }

  private setHighlight(i: number): void {
    const prev = this.highlight;
    this.highlight = i;
    if (prev >= 0) this.listbox.children[prev]?.setAttribute("aria-selected", "false");
    const li = i >= 0 ? (this.listbox.children[i] as HTMLElement | undefined) : undefined;
    if (li) {
      li.setAttribute("aria-selected", "true");
      this.input.setAttribute("aria-activedescendant", li.id);
      li.scrollIntoView({ block: "nearest" });
    } else {
      this.input.removeAttribute("aria-activedescendant");
    }
  }

  // ------------------------------------------------------------------ rendering

  private render(v: SearchView): void {
    // Read before anything is hidden: browsers may blur a focused element as soon as it is hidden.
    const focusOnRetry = document.activeElement === this.retryBtn;
    const open = v.state !== "closed";
    const hasOptions = (v.state === "results" || v.state === "coordinate") && v.options.length > 0;
    this.popup.hidden = !open;
    this.popup.dataset.state = v.state;
    if (v.options !== this.renderedOptions) {
      this.renderOptions(hasOptions ? v.options : []);
      this.renderedOptions = v.options;
    }
    this.listbox.hidden = !hasOptions;
    this.listbox.setAttribute("aria-busy", String(v.busy));
    this.popup.setAttribute("aria-busy", String(v.busy));
    this.input.setAttribute("aria-expanded", String(open && hasOptions));
    if (!hasOptions) this.setHighlight(-1);

    if (open && !hasOptions) this.renderState(v);
    else this.stateRow.hidden = true;

    // Whenever the focused retry button is removed (popup closed, replaced by options, another state without a
    // button), focus goes to the input, never to <body> (screen spec › Interactions › Tab, Focus management).
    if (focusOnRetry && (this.popup.hidden || this.stateRow.hidden || this.retryBtn.hidden)) this.input.focus();

    if (open !== this.wasOpen) {
      this.wasOpen = open;
      this.deps.onOpenChange(open);
    }
  }

  private renderOptions(options: readonly SearchOption[]): void {
    this.highlight = -1;
    this.input.removeAttribute("aria-activedescendant");
    const lang = this.deps.i18n.lang;
    const items = options.map((o, i) => {
      const txt = this.optionText(o);
      const li = document.createElement("li");
      li.id = `search-option-${i}`;
      li.className = "opt";
      li.setAttribute("role", "option");
      li.setAttribute("aria-selected", "false");
      li.dataset.testid = "search-option";
      li.dataset.index = String(i);
      li.dataset.kind = o.kind;
      if (o.kind === "place") li.dataset.type = resultInfo(o.feature).typeKey;
      li.setAttribute("aria-label", [txt.name, txt.type, txt.context].filter((x): x is string => !!x).join(", "));
      li.innerHTML = o.kind === "coordinate" ? ICONS.crosshair : ICONS.place;
      const tx = document.createElement("span");
      tx.className = "tx";
      const nm = document.createElement("span");
      nm.className = "nm";
      nm.dataset.testid = "search-option-name";
      nm.textContent = txt.name;
      const pl = partLang(txt.name, lang);
      if (pl && o.kind === "place") nm.lang = pl;
      const mt = document.createElement("span");
      mt.className = "mt";
      if (txt.type !== null) {
        const ty = document.createElement("span");
        ty.className = "ty";
        ty.dataset.testid = "search-option-type";
        ty.textContent = txt.type;
        mt.append(ty);
      }
      if (txt.context !== null) {
        const cx = document.createElement("span");
        cx.className = "cx";
        cx.dataset.testid = "search-option-context";
        cx.textContent = txt.context;
        const cl = partLang(txt.context, lang);
        if (cl && o.kind === "place") cx.lang = cl;
        mt.append(cx);
      }
      tx.append(nm, mt);
      li.append(tx);
      return li;
    });
    this.listbox.replaceChildren(...items);
  }

  private renderState(v: SearchView): void {
    const row = STATE_ROW[v.state as keyof typeof STATE_ROW];
    if (!row) {
      this.stateRow.hidden = true;
      return;
    }
    this.stateRow.hidden = false;
    this.stateRow.dataset.state = v.state;
    const icon = this.stateRow.querySelector<HTMLElement>(".icon")!;
    icon.innerHTML = row.icon ?? '<span class="spin"></span>';
    this.stateText.textContent = this.deps.i18n.t(row.key);
    this.retryBtn.hidden = v.retry === "none";
    this.stateRow.classList.toggle("has-act", v.retry !== "none");
    if (v.retry === "disabled") this.retryBtn.setAttribute("aria-disabled", "true");
    else this.retryBtn.removeAttribute("aria-disabled");
  }

  private announcementText(a: Announcement): string {
    if (a.kind === "count") return this.deps.i18n.resultCount(a.count);
    return this.deps.i18n.t(STATE_ROW[a.state].key);
  }

  /** Live region: once per settled query (AC 42). */
  private announce(a: Announcement): void {
    this.lastAnnouncement = a;
    this.live.textContent = this.announcementText(a);
  }
}

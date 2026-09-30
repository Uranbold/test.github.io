// Tooltip (screen spec › Components › Tooltip): hover after 500 ms, keyboard focus immediately,
// hides on Esc, blur or pointer leave, and is hoverable (WCAG 1.4.13). No tooltip for touch.
// Placement: below R1 buttons, left of the R4 column, so it never covers the attribution strip (R5).

const HOVER_DELAY_MS = 500;
const GAP_PX = 8;

export type TextFor = (el: HTMLElement) => string | null;

export class Tooltip {
  private owner: HTMLElement | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private overTip = false;

  constructor(
    private readonly tip: HTMLElement,
    private readonly textFor: TextFor,
    private readonly avoid: HTMLElement[] = [],
  ) {
    tip.addEventListener("pointerenter", () => (this.overTip = true));
    tip.addEventListener("pointerleave", () => {
      this.overTip = false;
      this.hide();
    });
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape") this.hide();
    });
  }

  attach(el: HTMLElement, placement: "below" | "left"): void {
    el.dataset.tooltipPlacement = placement;
    el.addEventListener("pointerenter", (e) => {
      if (e.pointerType === "touch") return;
      this.clearTimer();
      this.timer = setTimeout(() => this.show(el), HOVER_DELAY_MS);
    });
    el.addEventListener("pointerleave", () => {
      this.clearTimer();
      // Leave time to move onto the tooltip itself (hoverable).
      setTimeout(() => {
        if (!this.overTip && this.owner === el && document.activeElement !== el) this.hide();
      }, 100);
    });
    el.addEventListener("focus", () => {
      if (el.matches(":focus-visible")) this.show(el);
    });
    el.addEventListener("blur", () => {
      if (this.owner === el) this.hide();
    });
    el.addEventListener("pointerdown", () => this.hide());
  }

  /** Re-renders the text of an open tooltip (language switch). */
  refresh(): void {
    if (this.owner) this.show(this.owner);
  }

  hide(): void {
    this.clearTimer();
    this.owner = null;
    this.tip.hidden = true;
  }

  private show(el: HTMLElement): void {
    const text = this.textFor(el);
    if (!text || el.hidden) return;
    this.owner = el;
    this.tip.textContent = text;
    this.tip.hidden = false;
    const r = el.getBoundingClientRect();
    const t = this.tip.getBoundingClientRect();
    let left: number;
    let top: number;
    if (el.dataset.tooltipPlacement === "left") {
      left = r.left - GAP_PX - t.width;
      top = r.top + (r.height - t.height) / 2;
      // Narrow screens: never cover the scale bar (or anything else in the avoid list); move up instead.
      for (const avoid of this.avoid) {
        const a = avoid.getBoundingClientRect();
        if (avoid.closest("[hidden]") || a.width === 0) continue;
        const overlapsX = left < a.right && left + t.width > a.left;
        const overlapsY = top < a.bottom && top + t.height > a.top;
        if (overlapsX && overlapsY) top = a.top - GAP_PX - t.height;
      }
    } else {
      left = Math.min(window.innerWidth - t.width - GAP_PX, Math.max(GAP_PX, r.left + (r.width - t.width) / 2));
      top = r.bottom + GAP_PX;
    }
    this.tip.style.left = `${Math.max(GAP_PX, left)}px`;
    this.tip.style.top = `${Math.max(GAP_PX, top)}px`;
  }

  private clearTimer(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }
}

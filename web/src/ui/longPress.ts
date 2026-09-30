// Touch long-press on the map canvas (NAV-003 AC 25; screen spec › Interactions): ≥ 600 ms without moving more than
// 10 px opens a coordinate card. A second finger, a drag or lifting the finger earlier cancels it. On Android Chrome the
// same gesture also fires `contextmenu`; consumeContextMenu() lets the caller ignore that one.
import { browserTimers, type Timers } from "../search/gateway";

export const LONG_PRESS_MS = 600; // tokens: motion.long-press
export const LONG_PRESS_TOLERANCE_PX = 10;
/** A `contextmenu` this soon after a handled long-press belongs to the same gesture. */
export const CONTEXTMENU_DEDUPE_MS = 1000;

interface Point {
  x: number;
  y: number;
}

export class LongPress {
  private timer: unknown = null;
  private start: Point | null = null;
  private last: Point | null = null;
  private firedAt = -Infinity;

  constructor(
    private readonly onLongPress: (clientX: number, clientY: number) => void,
    private readonly now: () => number = () => performance.now(),
    private readonly timers: Timers = browserTimers,
  ) {}

  /** Wires the detector to an element (passive listeners: MapLibre keeps handling the same touches). */
  attach(target: HTMLElement): void {
    const opts = { passive: true } as const;
    target.addEventListener("touchstart", (e) => this.touchStart(e.touches.length, e.touches[0]), opts);
    target.addEventListener("touchmove", (e) => this.touchMove(e.touches[0]), opts);
    target.addEventListener("touchend", () => this.cancel(), opts);
    target.addEventListener("touchcancel", () => this.cancel(), opts);
  }

  touchStart(count: number, t: { clientX: number; clientY: number } | undefined): void {
    this.cancel();
    if (count !== 1 || !t) return; // a second finger (pinch) never opens a card
    this.start = { x: t.clientX, y: t.clientY };
    this.last = this.start;
    this.timer = this.timers.setTimeout(() => {
      this.timer = null;
      const p = this.last ?? this.start;
      this.start = null;
      if (!p) return;
      this.firedAt = this.now();
      this.onLongPress(p.x, p.y);
    }, LONG_PRESS_MS);
  }

  touchMove(t: { clientX: number; clientY: number } | undefined): void {
    if (!this.start || !t) return;
    this.last = { x: t.clientX, y: t.clientY };
    if (Math.hypot(t.clientX - this.start.x, t.clientY - this.start.y) > LONG_PRESS_TOLERANCE_PX) this.cancel();
  }

  cancel(): void {
    if (this.timer !== null) this.timers.clearTimeout(this.timer);
    this.timer = null;
    this.start = null;
    this.last = null;
  }

  /** A touch press is being timed (finger down, not yet 600 ms, not moved or lifted). */
  get pressing(): boolean {
    return this.start !== null;
  }

  /**
   * True if the `contextmenu` belongs to a touch gesture this detector owns and must be ignored: either the press is
   * still being timed (Chrome fires `contextmenu` during the hold, before 600 ms), or a long-press was just handled.
   */
  consumeContextMenu(): boolean {
    return this.pressing || this.now() - this.firedAt < CONTEXTMENU_DEDUPE_MS;
  }
}

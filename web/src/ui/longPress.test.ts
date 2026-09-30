// NAV-003 AC 25: long-press ≥ 600 ms without moving > 10 px; a drag, a second finger or an early lift cancel it.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { LONG_PRESS_MS, LongPress } from "./longPress";

describe("LongPress", () => {
  let fired: Array<[number, number]>;
  let lp: LongPress;
  beforeEach(() => {
    vi.useFakeTimers();
    fired = [];
    lp = new LongPress((x, y) => fired.push([x, y]), () => Date.now());
  });
  afterEach(() => vi.useRealTimers());

  it("fires once after 600 ms at the finger position", () => {
    lp.touchStart(1, { clientX: 100, clientY: 200 });
    vi.advanceTimersByTime(LONG_PRESS_MS - 1);
    expect(fired).toEqual([]);
    lp.touchMove({ clientX: 105, clientY: 206 }); // 7.8 px: still a press
    vi.advanceTimersByTime(1);
    expect(fired).toEqual([[105, 206]]);
    expect(lp.consumeContextMenu()).toBe(true); // Android's contextmenu for the same gesture is ignored
    vi.advanceTimersByTime(1000);
    expect(lp.consumeContextMenu()).toBe(false);
  });

  it("a contextmenu during the hold (Chrome fires it before 600 ms) is ignored; the long-press still fires once", () => {
    lp.touchStart(1, { clientX: 100, clientY: 200 });
    vi.advanceTimersByTime(500);
    expect(lp.consumeContextMenu()).toBe(true);
    vi.advanceTimersByTime(100);
    expect(fired).toHaveLength(1);
  });

  it("a move over 10 px, lifting early, or a second finger cancels", () => {
    lp.touchStart(1, { clientX: 100, clientY: 200 });
    lp.touchMove({ clientX: 111, clientY: 200 });
    vi.advanceTimersByTime(LONG_PRESS_MS);
    lp.touchStart(1, { clientX: 100, clientY: 200 });
    vi.advanceTimersByTime(300);
    lp.cancel(); // touchend
    vi.advanceTimersByTime(LONG_PRESS_MS);
    lp.touchStart(2, { clientX: 100, clientY: 200 });
    vi.advanceTimersByTime(LONG_PRESS_MS);
    expect(fired).toEqual([]);
    expect(lp.consumeContextMenu()).toBe(false);
  });
});

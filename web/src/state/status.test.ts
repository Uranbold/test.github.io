import { describe, expect, it } from "vitest";
import { LOADING_DELAY_MS, START_WATCHDOG_MS, StatusMachine, viewOf, type StatusState, type Timers } from "./status";

class FakeTimers implements Timers {
  now = 0;
  private queue: { at: number; fn: () => void; id: number }[] = [];
  private next = 1;
  setTimeout(fn: () => void, ms: number) {
    const id = this.next++;
    this.queue.push({ at: this.now + ms, fn, id });
    return id;
  }
  clearTimeout(h: unknown) {
    this.queue = this.queue.filter((q) => q.id !== h);
  }
  advance(ms: number) {
    const end = this.now + ms;
    for (;;) {
      this.queue.sort((a, b) => a.at - b.at);
      const q = this.queue[0];
      if (!q || q.at > end) break;
      this.queue.shift();
      this.now = q.at;
      q.fn();
    }
    this.now = end;
  }
}

const base: StatusState = { online: true, ready: false, loadingDelayElapsed: false, startFailed: false, retrying: false, genericError: false, consecutiveTileErrors: 0 };

describe("viewOf precedence (AC 45)", () => {
  it("offline > tiles unavailable > loading before the first render", () => {
    expect(viewOf({ ...base, online: false, startFailed: true, loadingDelayElapsed: true })).toEqual({ kind: "card", reason: "offline", busy: false });
    expect(viewOf({ ...base, startFailed: true, loadingDelayElapsed: true })).toEqual({ kind: "card", reason: "tiles", busy: false });
    expect(viewOf({ ...base, loadingDelayElapsed: true })).toEqual({ kind: "loading" });
    expect(viewOf(base)).toEqual({ kind: "none" });
  });
  it("offline > tiles unavailable after the first render (banners)", () => {
    expect(viewOf({ ...base, ready: true, online: false, consecutiveTileErrors: 5 })).toEqual({ kind: "banner", reason: "offline" });
    expect(viewOf({ ...base, ready: true, consecutiveTileErrors: 3 })).toEqual({ kind: "banner", reason: "tiles" });
    expect(viewOf({ ...base, ready: true, consecutiveTileErrors: 2 })).toEqual({ kind: "none" });
  });
  it("generic error wins", () => {
    expect(viewOf({ ...base, genericError: true, online: false })).toEqual({ kind: "card", reason: "generic", busy: false });
  });
});

describe("StatusMachine timing", () => {
  it("shows loading only after 300 ms and hides it when ready (AC 37)", () => {
    const t = new FakeTimers();
    const m = new StatusMachine(true, t);
    m.start();
    t.advance(LOADING_DELAY_MS - 1);
    expect(m.view.kind).toBe("none");
    t.advance(1);
    expect(m.view.kind).toBe("loading");
    m.markReady();
    expect(m.view.kind).toBe("none");
  });

  it("counts time already spent since navigation start", () => {
    const t = new FakeTimers();
    const m = new StatusMachine(true, t);
    m.start(250);
    t.advance(49);
    expect(m.view.kind).toBe("none");
    t.advance(1);
    expect(m.view.kind).toBe("loading");
  });

  it("is loading at once when started after 300 ms, so the pre-module pill never blinks off (AC 37)", () => {
    const m = new StatusMachine(true, new FakeTimers());
    const seen: string[] = [];
    m.onChange((v) => seen.push(v.kind));
    m.start(450);
    expect(m.view.kind).toBe("loading");
    expect(seen).toEqual(["loading"]);
  });

  it("ends in the tiles card after a source error or the 10 s watchdog, never an endless spinner (AC 38–39)", () => {
    const t = new FakeTimers();
    const a = new StatusMachine(true, t);
    a.start();
    a.sourceError();
    expect(a.view).toEqual({ kind: "card", reason: "tiles", busy: false });

    const t2 = new FakeTimers();
    const b = new StatusMachine(true, t2);
    b.start();
    t2.advance(START_WATCHDOG_MS - 1);
    expect(b.view.kind).toBe("loading");
    t2.advance(1);
    expect(b.view).toEqual({ kind: "card", reason: "tiles", busy: false });
  });

  it("keeps the card with a busy button while retrying; success clears it, failure keeps it (AC 40)", () => {
    const t = new FakeTimers();
    const m = new StatusMachine(true, t);
    m.start();
    m.sourceError();
    m.retryStart();
    expect(m.view).toEqual({ kind: "card", reason: "tiles", busy: true });
    m.sourceError();
    expect(m.view).toEqual({ kind: "card", reason: "tiles", busy: false });
    m.retryStart();
    t.advance(START_WATCHDOG_MS);
    expect(m.view).toEqual({ kind: "card", reason: "tiles", busy: false });
    m.retryStart();
    m.markReady();
    expect(m.view.kind).toBe("none");
  });

  it("shows the banner after 3 consecutive tile errors and clears it on the next good tile (AC 41)", () => {
    const m = new StatusMachine(true, new FakeTimers());
    m.start();
    m.markReady();
    m.tileError();
    m.tileError();
    m.tileLoaded();
    m.tileError();
    m.tileError();
    expect(m.view.kind).toBe("none");
    m.tileError();
    expect(m.view).toEqual({ kind: "banner", reason: "tiles" });
    m.tileLoaded();
    expect(m.view.kind).toBe("none");
  });

  it("counts a source (header) error after the first render like a tile error", () => {
    const m = new StatusMachine(true, new FakeTimers());
    m.start();
    m.markReady();
    m.sourceError();
    m.tileError();
    expect(m.view.kind).toBe("none");
    m.sourceError();
    expect(m.view).toEqual({ kind: "banner", reason: "tiles" });
  });

  it("a tile error before the first render never raises the blocking card on its own", () => {
    const t = new FakeTimers();
    const m = new StatusMachine(true, t);
    m.start();
    m.tileError();
    t.advance(LOADING_DELAY_MS);
    expect(m.view.kind).toBe("loading");
    expect(m.state.startFailed).toBe(false);
    m.tileLoaded();
    m.markReady();
    expect(m.view.kind).toBe("none");
  });

  it("tile errors before the first render count towards the banner once the map is ready", () => {
    const m = new StatusMachine(true, new FakeTimers());
    m.start();
    m.tileError();
    m.tileError();
    m.tileError();
    expect(m.view.kind).toBe("none");
    m.markReady();
    expect(m.view).toEqual({ kind: "banner", reason: "tiles" });
    m.tileLoaded();
    expect(m.view.kind).toBe("none");
  });

  it("only tile errors and no tile at all: the 10 s watchdog ends in the card (AC 38)", () => {
    const t = new FakeTimers();
    const m = new StatusMachine(true, t);
    m.start();
    for (let i = 0; i < 5; i++) m.tileError();
    t.advance(START_WATCHDOG_MS - 1);
    expect(m.view.kind).toBe("loading");
    t.advance(1);
    expect(m.view).toEqual({ kind: "card", reason: "tiles", busy: false });
  });

  it("a card retry starts the tile error count again", () => {
    const m = new StatusMachine(true, new FakeTimers());
    m.start();
    m.tileError();
    m.tileError();
    m.sourceError();
    m.retryStart();
    expect(m.state.consecutiveTileErrors).toBe(0);
    m.tileError();
    m.markReady();
    expect(m.view.kind).toBe("none");
  });

  it("switches to the offline banner and back (AC 43–44)", () => {
    const m = new StatusMachine(true, new FakeTimers());
    const seen: string[] = [];
    m.onChange((v) => seen.push(v.kind === "banner" ? `banner:${v.reason}` : v.kind));
    m.start();
    m.markReady();
    m.setOnline(false);
    expect(m.view).toEqual({ kind: "banner", reason: "offline" });
    m.setOnline(true);
    expect(m.view.kind).toBe("none");
    expect(seen).toEqual(["none", "banner:offline", "none"]);
  });
});

// Screen wake lock for the replay (NAV-017 AC 39, ADR-0011 §7): requested in the «Эхлэх» handler, re-requested when
// the page becomes visible again, released at the end. Missing API or a refused request: silently skipped.
interface Sentinel {
  release(): Promise<void>;
}
interface WakeLockApi {
  request(type: "screen"): Promise<Sentinel>;
}

export class ScreenWakeLock {
  private sentinel: Sentinel | null = null;
  private wanted = false;

  constructor(private readonly api: WakeLockApi | undefined) {}

  get held(): boolean {
    return this.sentinel !== null;
  }

  request(): void {
    this.wanted = true;
    if (!this.api || this.sentinel) return;
    try {
      this.api
        .request("screen")
        .then((s) => {
          if (this.wanted) this.sentinel = s;
          else void s.release().catch(() => undefined);
        })
        .catch(() => undefined);
    } catch {
      // not allowed here: the replay works without it
    }
  }

  /** The browser drops the lock when the page is hidden; request it again on return. */
  onVisible(): void {
    if (!this.wanted) return;
    this.sentinel = null;
    this.request();
  }

  release(): void {
    this.wanted = false;
    const s = this.sentinel;
    this.sentinel = null;
    if (s) void s.release().catch(() => undefined);
  }
}

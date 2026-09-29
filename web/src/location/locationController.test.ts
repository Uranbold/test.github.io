import { describe, expect, it, vi } from "vitest";
import { GEO_OPTIONS, LocationController, type CameraPort } from "./locationController";

type Ok = (p: GeolocationPosition) => void;
type Err = (e: GeolocationPositionError) => void;

class FakeGeo {
  ok: Ok | null = null;
  err: Err | null = null;
  options: PositionOptions | undefined;
  watchCalls = 0;
  cleared: number[] = [];
  watchPosition(ok: Ok, err: Err, options?: PositionOptions) {
    this.ok = ok;
    this.err = err;
    this.options = options;
    return ++this.watchCalls;
  }
  clearWatch(id: number) {
    this.cleared.push(id);
  }
  getCurrentPosition() {
    throw new Error("not used");
  }
  fix(lat: number, lng: number, accuracy = 30) {
    this.ok!({ coords: { latitude: lat, longitude: lng, accuracy }, timestamp: Date.now() } as GeolocationPosition);
  }
  fail(code: number) {
    this.err!({ code, message: "", PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 } as GeolocationPositionError);
  }
}

function setup(secure = true, withGeo = true) {
  const geo = new FakeGeo();
  const camera: CameraPort = { recenter: vi.fn(), follow: vi.fn() };
  const c = new LocationController(withGeo ? (geo as unknown as Geolocation) : undefined, secure, camera);
  return { geo, camera, c };
}

describe("LocationController (AC 18–25)", () => {
  it("makes no geolocation call before the first press (AC 18)", () => {
    const { geo, c } = setup();
    expect(c.view.button).toBe("idle");
    expect(geo.watchCalls).toBe(0);
  });

  it("is unsupported without the API or a secure context; pressing does nothing (AC 24)", () => {
    for (const [secure, withGeo] of [[false, true], [true, false]] as const) {
      const { geo, c } = setup(secure, withGeo);
      expect(c.view.button).toBe("unsupported");
      c.press();
      expect(geo.watchCalls).toBe(0);
      expect(c.view.message).toBeNull();
    }
  });

  it("watches with high accuracy, 10 s timeout, maximumAge 0 and follows the first fix (AC 19)", () => {
    const { geo, camera, c } = setup();
    c.press();
    expect(c.view.button).toBe("locating");
    expect(geo.options).toEqual(GEO_OPTIONS);
    expect(GEO_OPTIONS).toEqual({ enableHighAccuracy: true, timeout: 10000, maximumAge: 0 });
    geo.fix(47.9189, 106.9176, 30);
    expect(c.view).toMatchObject({ button: "following", fix: { lat: 47.9189, lng: 106.9176, accuracy: 30 }, stale: false, message: null });
    expect(camera.recenter).toHaveBeenCalledTimes(1);
  });

  it("follows new fixes; after a user pan only the marker moves; pressing recentres (AC 20)", () => {
    const { geo, camera, c } = setup();
    c.press();
    geo.fix(47.9189, 106.9176);
    geo.fix(47.9207, 106.9176);
    expect(camera.follow).toHaveBeenCalledTimes(1);
    c.userMovedMap();
    expect(c.view.button).toBe("not-following");
    geo.fix(47.9225, 106.9176);
    expect(camera.follow).toHaveBeenCalledTimes(1);
    expect(c.view.fix?.lat).toBe(47.9225);
    c.press();
    expect(c.view.button).toBe("following");
    expect(camera.recenter).toHaveBeenCalledTimes(2);
    expect(geo.watchCalls).toBe(1);
  });

  it("shows the denied message and state; pressing again shows it again (AC 21)", () => {
    const { geo, c } = setup();
    c.press();
    geo.fail(1);
    expect(c.view).toMatchObject({ button: "denied", message: "denied" });
    c.dismissMessage();
    expect(c.view.message).toBeNull();
    c.press();
    geo.fail(1);
    expect(c.view).toMatchObject({ button: "denied", message: "denied" });
  });

  it("shows 'could not determine' on unavailable/timeout before a fix; retry starts a new request (AC 22)", () => {
    for (const code of [2, 3]) {
      const { geo, c } = setup();
      c.press();
      geo.fail(code);
      expect(c.view).toMatchObject({ button: "idle", message: "unavailable" });
      c.retry();
      expect(geo.watchCalls).toBe(2);
      expect(c.view).toMatchObject({ button: "locating", message: null });
    }
  });

  it("turns stale after a fix when fixes stop, and recovers when they resume (AC 23)", () => {
    const { geo, c } = setup();
    c.press();
    geo.fix(47.9189, 106.9176);
    geo.fail(2);
    expect(c.view).toMatchObject({ button: "following", stale: true, message: "stale", fix: { lat: 47.9189 } });
    expect(geo.cleared).toEqual([]);
    geo.fix(47.919, 106.9177);
    expect(c.view).toMatchObject({ stale: false, message: null });
  });

  it("treats a position outside Mongolia like any other fix (AC 25)", () => {
    const { geo, camera, c } = setup();
    c.press();
    geo.fix(39.9042, 116.4074);
    expect(c.view).toMatchObject({ button: "following", message: null });
    expect(camera.recenter).toHaveBeenCalledWith({ lat: 39.9042, lng: 116.4074, accuracy: 30 });
  });
});

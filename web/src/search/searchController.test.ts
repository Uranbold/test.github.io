// SearchController and ReverseController: story AC 3–9, 13, 15, 26–38, 41, 42 with fake timers and a fake client.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Lang } from "../i18n/i18n";
import type { Outcome, ReverseQuery, SearchQuery } from "./gateway";
import type { PhotonFeature } from "./photon";
import { ReverseController } from "./reverseController";
import { DEBOUNCE_MS, LOADING_DELAY_MS, SearchController, type Announcement, type SearchOption, type SearchView } from "./searchController";

interface Call<Q> {
  q: Q;
  signal: AbortSignal;
  at: number;
  resolve: (o: Outcome) => void;
}

class FakeClient {
  searches: Call<SearchQuery>[] = [];
  reverses: Call<ReverseQuery>[] = [];
  search = (q: SearchQuery, signal: AbortSignal): Promise<Outcome> =>
    new Promise((resolve) => this.searches.push({ q, signal, at: Date.now(), resolve }));
  reverse = (q: ReverseQuery, signal: AbortSignal): Promise<Outcome> =>
    new Promise((resolve) => this.reverses.push({ q, signal, at: Date.now(), resolve }));
}

const feat = (id: number, name = `n${id}`, cc = "MN"): PhotonFeature => ({
  type: "Feature",
  geometry: { type: "Point", coordinates: [106.9 + id / 1000, 47.9] },
  properties: { osm_type: "N", osm_id: id, name, countrycode: cc },
});
const ok = (...f: PhotonFeature[]): Outcome => ({ kind: "ok", features: f });

let client: FakeClient;
let online: boolean;
let lang: Lang;
let bias: { lat: number; lon: number };
let views: SearchView[];
let announced: Announcement[];
let selected: SearchOption[];
let c: SearchController;

async function type(text: string, perCharMs = 100): Promise<void> {
  for (let i = 1; i <= text.length; i++) {
    c.input(text.slice(0, i));
    await vi.advanceTimersByTimeAsync(perCharMs);
  }
}
const settle = () => vi.advanceTimersByTimeAsync(DEBOUNCE_MS);

beforeEach(() => {
  vi.useFakeTimers();
  client = new FakeClient();
  online = true;
  lang = "mn";
  bias = { lat: 47.91891234, lon: 106.91764 };
  c = new SearchController({ client, lang: () => lang, bias: () => bias, isOnline: () => online, now: () => Date.now() });
  views = [];
  announced = [];
  selected = [];
  c.onChange((v) => views.push(v));
  c.onAnnounce((a) => announced.push(a));
  c.onAutoSelect((o) => selected.push(o));
});
afterEach(() => {
  c.dispose();
  vi.useRealTimers();
});

describe("A. request rules", () => {
  it("AC 3: «Сүхбаатар» typed at 100 ms per key sends exactly 1 request, 250 ms after the last key, lang/limit/bias", async () => {
    await type("Сүхбаатар");
    const lastKey = Date.now() - 100;
    expect(client.searches).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS);
    expect(client.searches).toHaveLength(1);
    const s = client.searches[0]!;
    expect(s.at - lastKey).toBeGreaterThanOrEqual(200);
    expect(s.at - lastKey).toBeLessThanOrEqual(350);
    expect(s.q).toEqual({ q: "Сүхбаатар", lang: "mn", limit: 8, lat: "47.919", lon: "106.918" });
  });

  it("AC 4: fewer than 2 characters (or spaces) → no request, list closed", async () => {
    c.input("С");
    await settle();
    c.input("   ");
    await settle();
    expect(client.searches).toHaveLength(0);
    expect(c.view.state).toBe("closed");
  });

  it("AC 5: an older response never replaces the list of a newer query (and is aborted)", async () => {
    c.input("Гандан");
    await settle();
    c.input("Зайсан");
    await settle();
    expect(client.searches).toHaveLength(2);
    expect(client.searches[0]!.signal.aborted).toBe(true);
    client.searches[1]!.resolve(ok(feat(2, "Зайсан")));
    await vi.advanceTimersByTimeAsync(0);
    client.searches[0]!.resolve(ok(feat(1, "Гандан")));
    await vi.advanceTimersByTimeAsync(1000);
    expect(c.view.query).toBe("Зайсан");
    expect(c.view.options.map((o) => (o.kind === "place" ? o.feature.properties.name : ""))).toEqual(["Зайсан"]);
  });

  it("AC 5: also for parallel (Latin) pairs", async () => {
    c.input("Gandan");
    await settle();
    c.input("Zaisan");
    await settle();
    expect(client.searches).toHaveLength(4);
    client.searches[2]!.resolve(ok(feat(2, "Зайсан")));
    client.searches[3]!.resolve(ok());
    await vi.advanceTimersByTimeAsync(0);
    client.searches[0]!.resolve(ok(feat(1, "Гандан")));
    client.searches[1]!.resolve(ok());
    await vi.advanceTimersByTimeAsync(1000);
    expect(c.view.query).toBe("Zaisan");
    expect(c.view.options).toHaveLength(1);
  });

  it("AC 6: an identical settled query (trailing space typed and deleted) sends no new request", async () => {
    c.input("Гандан");
    await settle();
    client.searches[0]!.resolve(ok(feat(1)));
    await vi.advanceTimersByTimeAsync(0);
    c.input("Гандан ");
    await vi.advanceTimersByTimeAsync(50);
    c.input("Гандан");
    await settle();
    expect(client.searches).toHaveLength(1);
    expect(c.view.state).toBe("results");
  });

  it("AC 7: lang follows the UI language", async () => {
    lang = "en";
    c.input("Gandan");
    await settle();
    expect(client.searches.map((s) => s.q.lang)).toEqual(["en", "en"]);
  });
});

describe("C. assistance (AC 15, 16) and merging (AC 8)", () => {
  it("Latin: as typed + Cyrillic in parallel, merged without duplicates, MN first", async () => {
    c.input("Ikh delguur");
    await settle();
    expect(client.searches.map((s) => s.q.q)).toEqual(["Ikh delguur", "их дэлгүүр"]);
    client.searches[0]!.resolve(ok(feat(1, "a", "CN"), feat(2)));
    client.searches[1]!.resolve(ok(feat(2), feat(3)));
    await vi.advanceTimersByTimeAsync(0);
    expect(c.view.options.map((o) => (o.kind === "place" ? o.feature.properties.osm_id : 0))).toEqual([2, 3, 1]);
    expect(announced).toEqual([{ kind: "count", count: 3 }]);
  });

  it("abbreviation: expanded + as typed", async () => {
    c.input("БЗД 4-р хороо");
    await settle();
    expect(client.searches.map((s) => s.q.q)).toEqual(["Баянзүрх дүүрэг 4-р хороо", "БЗД 4-р хороо"]);
  });

  it("у/о Cyrillic: the ү/ө variant only after an empty first answer", async () => {
    c.input("Сухбаатар");
    await settle();
    expect(client.searches).toHaveLength(1);
    client.searches[0]!.resolve(ok());
    await vi.advanceTimersByTimeAsync(0);
    expect(client.searches.map((s) => s.q.q)).toEqual(["Сухбаатар", "Сүхбаатар"]);
    client.searches[1]!.resolve(ok(feat(9)));
    await vi.advanceTimersByTimeAsync(0);
    expect(c.view.state).toBe("results");

    c.input("Зайсан толгой");
    await settle();
    client.searches[2]!.resolve(ok(feat(10)));
    await vi.advanceTimersByTimeAsync(0);
    expect(client.searches).toHaveLength(3); // non-empty: no variant
  });

  it("a 429 on either parallel request drops both", async () => {
    c.input("Gandan");
    await settle();
    client.searches[0]!.resolve(ok(feat(1)));
    client.searches[1]!.resolve({ kind: "rateLimited", retryAfterS: 3 });
    await vi.advanceTimersByTimeAsync(0);
    expect(c.view.state).toBe("rate-limited");
  });
});

describe("B/G. states", () => {
  it("AC 13: loading row after 300 ms (aria-busy), gone with the answer", async () => {
    c.input("Гандан");
    await settle();
    await vi.advanceTimersByTimeAsync(LOADING_DELAY_MS - 1);
    expect(c.view.busy).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(c.view).toMatchObject({ state: "loading", busy: true });
    client.searches[0]!.resolve(ok(feat(1)));
    await vi.advanceTimersByTimeAsync(0);
    expect(c.view).toMatchObject({ state: "results", busy: false });
    expect(announced).toEqual([{ kind: "count", count: 1 }]); // not announced for the loading row
  });

  it("AC 32: empty → no-results, announced", async () => {
    c.input("xqzjwvk");
    await settle();
    client.searches.forEach((s) => s.resolve(ok()));
    await vi.advanceTimersByTimeAsync(0);
    expect(c.view.state).toBe("no-results");
    expect(announced).toEqual([{ kind: "message", state: "no-results" }]);
  });

  it("AC 33: unavailable with retry; retry re-sends once; nothing retries automatically", async () => {
    c.input("Гандан");
    await settle();
    client.searches[0]!.resolve({ kind: "unavailable" });
    await vi.advanceTimersByTimeAsync(20_000);
    expect(c.view).toMatchObject({ state: "unavailable", retry: "enabled" });
    expect(client.searches).toHaveLength(1);
    c.retry();
    expect(client.searches).toHaveLength(2);
    expect(client.searches[1]!.q.q).toBe("Гандан");
  });

  it("AC 34: offline sends nothing and shows offline; back online searches once", async () => {
    online = false;
    c.input("Гандан");
    await settle();
    expect(client.searches).toHaveLength(0);
    expect(c.view.state).toBe("offline");
    expect(announced).toEqual([{ kind: "message", state: "offline" }]);
    online = true;
    c.online();
    c.online();
    expect(client.searches).toHaveLength(1);
  });

  it("AC 35: Retry-After 3 → nothing sent for 3 s whatever is typed; then nothing automatically; next query sends 1", async () => {
    c.input("Гандан");
    await settle();
    client.searches[0]!.resolve({ kind: "rateLimited", retryAfterS: 3 });
    await vi.advanceTimersByTimeAsync(0);
    expect(c.view).toMatchObject({ state: "rate-limited", retry: "disabled" });
    c.retry(); // disabled
    await type("Зайсан", 150);
    await settle();
    expect(client.searches).toHaveLength(1);
    expect(c.view).toMatchObject({ state: "rate-limited", retry: "disabled" });
    await vi.advanceTimersByTimeAsync(3000);
    expect(c.view.retry).toBe("enabled");
    expect(client.searches).toHaveLength(1);
    c.input("Зайсан т");
    await settle();
    expect(client.searches).toHaveLength(2);
  });

  it("AC 35: after the window, «Дахин оролдох» sends one request", async () => {
    c.input("Гандан");
    await settle();
    client.searches[0]!.resolve({ kind: "rateLimited", retryAfterS: 5 });
    await vi.advanceTimersByTimeAsync(5000);
    c.retry();
    expect(client.searches).toHaveLength(2);
  });

  it("AC 37: 400 → error, no retry", async () => {
    c.input("Гандан");
    await settle();
    client.searches[0]!.resolve({ kind: "badRequest" });
    await vi.advanceTimersByTimeAsync(0);
    expect(c.view).toMatchObject({ state: "error", retry: "none" });
    c.retry();
    expect(client.searches).toHaveLength(1);
  });

  it("AC 31: clear aborts, closes, sends nothing", async () => {
    c.input("Гандан");
    await settle();
    const s = client.searches[0]!;
    c.input("");
    expect(s.signal.aborted).toBe(true);
    expect(c.view.state).toBe("closed");
    s.resolve(ok(feat(1)));
    await vi.advanceTimersByTimeAsync(1000);
    expect(c.view.state).toBe("closed");
  });

  it("AC 26: a coordinate pair gives one option and no request", async () => {
    c.input("47.9189, 106.9176");
    await settle();
    expect(client.searches).toHaveLength(0);
    expect(c.view.state).toBe("coordinate");
    expect(c.view.options).toEqual([{ kind: "coordinate", point: { lat: 47.9189, lon: 106.9176 } }]);
    expect(announced).toEqual([{ kind: "count", count: 1 }]);
  });

  it("AC 38: language switch re-requests an open list once with the new lang", async () => {
    c.input("Гандан");
    await settle();
    client.searches[0]!.resolve(ok(feat(1)));
    await vi.advanceTimersByTimeAsync(0);
    lang = "en";
    c.langChanged();
    expect(client.searches).toHaveLength(2);
    expect(client.searches[1]!.q.lang).toBe("en");
  });

  it("AC 38: a state message is not re-requested on language switch", async () => {
    c.input("Гандан");
    await settle();
    client.searches[0]!.resolve({ kind: "badRequest" });
    await vi.advanceTimersByTimeAsync(0);
    c.langChanged();
    expect(client.searches).toHaveLength(1);
  });
});

describe("H. keyboard (AC 41)", () => {
  it("Enter before the list exists searches at once and selects the first option on arrival", async () => {
    c.input("Гандан");
    c.submit();
    expect(client.searches).toHaveLength(1); // no debounce
    client.searches[0]!.resolve(ok(feat(1), feat(2)));
    await vi.advanceTimersByTimeAsync(0);
    expect(selected).toHaveLength(1);
    expect(selected[0]).toMatchObject({ kind: "place" });
    await settle();
    expect(client.searches).toHaveLength(1);
  });

  it("Enter during a pending request for the same query waits for it; empty results select nothing", async () => {
    c.input("Гандан");
    await settle();
    c.submit();
    expect(client.searches).toHaveLength(1);
    client.searches[0]!.resolve(ok());
    await vi.advanceTimersByTimeAsync(0);
    expect(selected).toHaveLength(0);
  });

  it("Enter with the list shown selects its first option; ArrowDown re-opens a closed list for the same text", async () => {
    c.input("Гандан");
    await settle();
    client.searches[0]!.resolve(ok(feat(1)));
    await vi.advanceTimersByTimeAsync(0);
    c.close();
    expect(c.view.state).toBe("closed");
    expect(c.reopen()).toBe(true);
    c.submit();
    expect(selected).toHaveLength(1);
    expect(client.searches).toHaveLength(1);
  });

  it("Enter on a coordinate selects the coordinate option", async () => {
    c.input("47.9189 106.9176");
    c.submit();
    expect(selected).toEqual([{ kind: "coordinate", point: { lat: 47.9189, lon: 106.9176 } }]);
  });
});

describe("ReverseController (AC 27–30, 36, 37)", () => {
  let r: ReverseController;
  beforeEach(() => {
    r = new ReverseController({ client, lang: () => lang, isOnline: () => online, now: () => Date.now() });
  });

  it("one request with 6 decimals, lang, limit 1, radius 0.5; loading after 300 ms; place", async () => {
    r.open({ lat: 47.9189, lon: 106.9176 });
    expect(client.reverses).toHaveLength(1);
    expect(client.reverses[0]!.q).toEqual({ lat: "47.918900", lon: "106.917600", lang: "mn", limit: 1, radius: 0.5 });
    expect(r.view?.state).toBe("pending");
    await vi.advanceTimersByTimeAsync(LOADING_DELAY_MS);
    expect(r.view?.state).toBe("loading");
    client.reverses[0]!.resolve(ok(feat(1)));
    await vi.advanceTimersByTimeAsync(0);
    expect(r.view?.state).toBe("place");
  });

  it("empty → «Илэрц олдсонгүй» state, not an error", async () => {
    r.open({ lat: 39.9, lon: 116.4 });
    client.reverses[0]!.resolve(ok());
    await vi.advanceTimersByTimeAsync(0);
    expect(r.view).toMatchObject({ state: "empty", retry: "none" });
  });

  it("unavailable → retry re-sends once", async () => {
    r.open({ lat: 47.9, lon: 106.9 });
    client.reverses[0]!.resolve({ kind: "unavailable" });
    await vi.advanceTimersByTimeAsync(0);
    expect(r.view).toMatchObject({ state: "unavailable", retry: "enabled" });
    r.retry();
    expect(client.reverses).toHaveLength(2);
  });

  it("429: retry disabled N s, nothing sent, nothing after N s until retry or a new card", async () => {
    r.open({ lat: 47.9, lon: 106.9 });
    client.reverses[0]!.resolve({ kind: "rateLimited", retryAfterS: 3 });
    await vi.advanceTimersByTimeAsync(0);
    expect(r.view).toMatchObject({ state: "rate-limited", retry: "disabled" });
    r.retry();
    r.open({ lat: 47.8, lon: 106.8 });
    expect(client.reverses).toHaveLength(1);
    expect(r.view).toMatchObject({ state: "rate-limited", retry: "disabled" });
    await vi.advanceTimersByTimeAsync(3000);
    expect(r.view?.retry).toBe("enabled");
    expect(client.reverses).toHaveLength(1);
    r.retry();
    expect(client.reverses).toHaveLength(2);
  });

  it("400 → error, no retry; offline → nothing sent, online sends once", async () => {
    r.open({ lat: 47.9, lon: 106.9 });
    client.reverses[0]!.resolve({ kind: "badRequest" });
    await vi.advanceTimersByTimeAsync(0);
    expect(r.view).toMatchObject({ state: "error", retry: "none" });
    online = false;
    r.open({ lat: 47.9, lon: 106.9 });
    expect(client.reverses).toHaveLength(1);
    expect(r.view?.state).toBe("offline");
    online = true;
    r.online();
    expect(client.reverses).toHaveLength(2);
  });

  it("a newer card ignores the older card's late answer", async () => {
    r.open({ lat: 47.9, lon: 106.9 });
    r.open({ lat: 47.8, lon: 106.8 });
    client.reverses[1]!.resolve(ok());
    client.reverses[0]!.resolve(ok(feat(1)));
    await vi.advanceTimersByTimeAsync(0);
    expect(r.view?.state).toBe("empty");
    r.close();
    expect(r.view).toBeNull();
  });
});

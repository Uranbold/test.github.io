// NAV-017 AC 1–4, 16, 44 (build side): the demo route emitter's validation and the build outputs. Runs the real Vite
// builds into temporary folders (the committed dist folders are never touched).
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, test } from "vitest";
import { DEMO_BUILD_LOG, loadDemoRoute, parseDemoMode, readManifest, summaryOf, type ManifestEntry } from "../../buildtools/demoMode";
import { assetBaseUrl, loadConfig } from "../config";

const WEB = fileURLToPath(new URL("../../", import.meta.url));
const REPO = fileURLToPath(new URL("../../../", import.meta.url));

describe("NAV-017 demo route manifest and emitter validation (ADR-0011 §5)", () => {
  test("R1–R3 are picker entries, G4 is test-only; every entry validates", () => {
    const m = readManifest(WEB);
    expect(m.filter((e) => e.picker).map((e) => e.label)).toEqual(["R1", "R2", "R3"]);
    expect(m.find((e) => e.id === "g4")?.picker).toBe(false);
    for (const e of m) {
      const p = loadDemoRoute(REPO, e);
      expect(p.track.t_ms[0]).toBe(0);
      expect(p.track.lonlat.length).toBe(p.track.t_ms.length);
      const s = summaryOf(e, p);
      expect(s.url).toBe(`demo-routes/${e.id}.json`);
      expect(s.distance).toBeGreaterThan(0);
    }
  });

  test("the build fails on a path outside the repo, a non-1 Hz track or a track that ends far from the route end", () => {
    const r1 = readManifest(WEB).find((e) => e.id === "r1")!;
    expect(() => loadDemoRoute(REPO, { ...r1, route: "../etc/passwd" })).toThrow(/outside the repository/);
    const dir = mkdtempSync(join(tmpdir(), "nav017-"));
    try {
      const gpx = readFileSync(REPO + r1.track, "utf8");
      // drop the 10th point: the track is no longer continuous at 1 Hz
      let i = 0;
      const gap = gpx.replace(/<trkpt[\s\S]*?<\/trkpt>/g, (m) => (++i === 10 ? "" : m));
      writeFileSync(join(dir, "gap.gpx"), gap);
      // temp files are outside the repo: validate the content rules with "/" as the repository root
      const asRepo = (f: string): ManifestEntry => ({ ...r1, route: (REPO + r1.route).slice(1), track: f });
      expect(() => loadDemoRoute("/", asRepo(join(dir, "gap.gpx").slice(1)))).toThrow(/1 Hz/);
      // keep only the first 100 points: the last point is far from the route end
      let j = 0;
      const short = gpx.replace(/<trkpt[\s\S]*?<\/trkpt>/g, (m) => (++j > 100 ? "" : m));
      writeFileSync(join(dir, "short.gpx"), short);
      expect(() => loadDemoRoute("/", asRepo(join(dir, "short.gpx").slice(1)))).toThrow(/route end/);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  test("VITE_DEMO_MODE parsing (invalid values are null and fail the build)", () => {
    expect(parseDemoMode(undefined)).toBe(false);
    expect(parseDemoMode("0")).toBe(false);
    expect(parseDemoMode(" TRUE ")).toBe(true);
    expect(parseDemoMode("yes")).toBeNull();
  });
});

describe("NAV-017 AC 2 sub-folder asset base", () => {
  test("a relative base resolves against the page; absolute bases are unchanged", () => {
    expect(assetBaseUrl("https://h.example", "./", "https://h.example/folder/")).toBe("https://h.example/folder/");
    expect(assetBaseUrl("https://h.example", "./", "https://h.example/a/b/index.html?x=1#y")).toBe("https://h.example/a/b/");
    expect(assetBaseUrl("http://localhost:5173", "/")).toBe("http://localhost:5173/");
    expect(assetBaseUrl("http://localhost:5173", "/", "http://localhost:5173/x/")).toBe("http://localhost:5173/");
    const cfg = loadConfig({ VITE_STATIC_DEMO: "true", VITE_GATEWAY_BASE_URL: "same-origin", BASE_URL: "./" }, "https://h.example", "https://h.example/demo-x/");
    expect(cfg.assetBaseUrl).toBe("https://h.example/demo-x/");
    expect(cfg.tilesUrl).toBe("https://h.example/tiles/basemap.pmtiles");
  });
});

/** All files under a folder, relative. */
function files(dir: string, base = dir): string[] {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? files(p, base) : [p.slice(base.length + 1)];
  });
}

/** Strings that only the voice diagnostics panel has (triage item F1). */
const DIAG_MARKERS = ["Voice diagnostics", "Test default speech (no voice set)", "silent-buffer unlock", "silent keep-alive", "data:audio/wav;base64,"];

describe("NAV-017 AC 1, 3, 4 build outputs", () => {
  const out = mkdtempSync(join(tmpdir(), "nav017-build-"));
  const build = (mode: string | null, dir: string): string =>
    execFileSync("npx", ["vite", "build", ...(mode ? ["--mode", mode] : []), "--outDir", join(out, dir), "--emptyOutDir"], {
      cwd: WEB,
      encoding: "utf8",
      env: { ...process.env, VITE_CONFIG_NATIVE_IGNORE_WARNING: "true" },
      stdio: ["ignore", "pipe", "pipe"],
    });
  let demoLog = "";
  let staticLog = "";
  beforeAll(() => {
    demoLog = build("demo-mode", "demo");
    staticLog = build("static-demo", "static");
    build(null, "normal");
  }, 180_000);
  afterAll(() => rmSync(out, { recursive: true, force: true }));

  const r1Geometry = (JSON.parse(readFileSync(REPO + "mobile/android/app/src/test/resources/routes/p1-p3-car-mn.json", "utf8")) as { routes: { geometry: string }[] }).routes[0]!.geometry.slice(100, 140);

  test("AC 1: the demo build logs the demo-mode line and writes demo-routes/r1..r3.json", () => {
    expect(demoLog).toContain(DEMO_BUILD_LOG.replace("navmn: ", ""));
    expect(staticLog).not.toContain("demo mode on");
    for (const id of ["r1", "r2", "r3"]) expect(existsSync(join(out, "demo", "demo-routes", `${id}.json`))).toBe(true);
    expect(existsSync(join(out, "demo", "demo-routes", "g4.json"))).toBe(false);
  });

  test("AC 3: no server configuration files; robots noindex; no crossorigin; relative URLs", () => {
    const list = files(join(out, "demo"));
    expect(list.filter((f) => /(^|\/)(\.htaccess|\.htpasswd|web\.config|\.user\.ini)$/i.test(f))).toEqual([]);
    const html = readFileSync(join(out, "demo", "index.html"), "utf8");
    expect(html).toContain('<meta name="robots" content="noindex, nofollow">');
    expect(html).not.toMatch(/crossorigin/i);
    expect(html).not.toMatch(/(src|href)="\/(?!\/)/);
  });

  test("AC 4: the public static and normal builds contain no demo code, picker, data, WASM or R1 geometry", () => {
    for (const dir of ["static", "normal"]) {
      const list = files(join(out, dir));
      expect(list.filter((f) => f.includes("demo-routes")), dir).toEqual([]);
      expect(list.filter((f) => f.endsWith(".wasm")), dir).toEqual([]);
      for (const f of list.filter((x) => /\.(js|css|html|json)$/.test(x))) {
        const text = readFileSync(join(out, dir, f), "utf8");
        expect(text.includes("demo-"), `${dir}/${f} has the demo- test-id prefix`).toBe(false);
        expect(text.includes(r1Geometry), `${dir}/${f} has R1 geometry`).toBe(false);
        expect(text.includes("NavigationController"), `${dir}/${f} has the Ferrostar core`).toBe(false);
        // triage item F1: the hidden voice diagnostics exist only in the demo-mode build
        for (const marker of DIAG_MARKERS) expect(text.includes(marker), `${dir}/${f} has voice diagnostics code (${marker})`).toBe(false);
      }
    }
    // and the demo build does have them
    const demoText = files(join(out, "demo")).filter((f) => f.endsWith(".js") || f.endsWith(".json")).map((f) => readFileSync(join(out, "demo", f), "utf8")).join("\n");
    expect(demoText).toContain("demo-");
    expect(demoText).toContain(r1Geometry);
    for (const marker of DIAG_MARKERS) expect(demoText).toContain(marker);
  });

  test("ADR-0011 §10: the demo adds at most 1.5 MB over the static-demo build", () => {
    const size = (dir: string) => files(join(out, dir)).filter((f) => !f.startsWith("fonts/") && !f.startsWith("sprites/") && !f.startsWith("demo-routes/")).reduce((a, f) => a + statSync(join(out, dir, f)).size, 0);
    expect(size("demo") - size("static")).toBeLessThanOrEqual(1.5 * 1024 * 1024);
  });
});

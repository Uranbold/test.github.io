// NAV-017 demo-mode build (ADR-0011 §2–§5). Vite plugins that are active only in `npm run build:demo-mode`
// (mode "demo-mode", file .env.demo-mode), plus the build guard that runs in every build:
//  - demoModeGuard: VITE_DEMO_MODE must be true/1/false/0/unset, and demo mode requires the static-demo setting, so
//    demo mode can never run with search, reverse or routing on (fail closed, like staticDemoGuard);
//  - demoHtml: removes every `crossorigin` attribute (Safari and cached Basic-auth credentials, ADR-0011 W7), adds the
//    `noindex, nofollow` robots meta (AC 3) and a trailing-slash guard before any module loads (relative URLs);
//  - demoRoutes: reads the recorded responses and GPX tracks named in src/demo/routes.manifest.json read-only from
//    their repo paths, validates them, emits demo-routes/<id>.json (stable, un-hashed paths) and provides the picker
//    summaries as the virtual module "virtual:navmn-demo-routes". Nothing is copied into web/ or web/public/.
import { existsSync, readFileSync } from "node:fs";
import { relative, resolve, sep } from "node:path";
import { loadEnv, type Plugin } from "vite";
import { distance, type LatLon } from "../src/guidance/geo";
import { decodePolyline } from "../src/route/polyline";

export const DEMO_ROUTES_DIR = "demo-routes";
export const VIRTUAL_ID = "virtual:navmn-demo-routes";
const RESOLVED_VIRTUAL_ID = "\0" + VIRTUAL_ID;
export const DEMO_BUILD_LOG = "navmn: demo mode build: demo mode on; search, reverse and routing are off (NAV-017)";
/** AC 16 / ADR-0011 §5: first and last track point must be this close to the route start and end. */
export const END_TOLERANCE_M = 30;

/** VITE_DEMO_MODE: "true"/"1" on, "false"/"0"/unset/empty off, anything else invalid (null). */
export function parseDemoMode(raw: string | undefined | null): boolean | null {
  const v = (raw ?? "").trim().toLowerCase();
  if (v === "" || v === "false" || v === "0") return false;
  if (v === "true" || v === "1") return true;
  return null;
}

export interface ManifestEnd {
  /** Place name (data, Cyrillic as in OSM), or null for the UI term «Сонгосон цэг» in the UI language. */
  name: string | null;
  osm: string | null;
}

export interface ManifestEntry {
  id: string;
  label: string;
  picker: boolean;
  mode: "car" | "walk";
  route: string;
  track: string;
  origin: ManifestEnd;
  destination: ManifestEnd;
}

export interface DemoRoutePayload {
  schema: 1;
  id: string;
  mode: "car" | "walk";
  origin: ManifestEnd;
  destination: ManifestEnd;
  route: unknown;
  track: { t_ms: number[]; lonlat: [number, number][] };
}

export interface DemoRouteSummary {
  id: string;
  label: string;
  mode: "car" | "walk";
  origin: ManifestEnd;
  destination: ManifestEnd;
  distance: number;
  duration: number;
  url: string;
}

export function readManifest(webRoot: string): ManifestEntry[] {
  const m = JSON.parse(readFileSync(resolve(webRoot, "src/demo/routes.manifest.json"), "utf8")) as { routes: ManifestEntry[] };
  return m.routes;
}

function repoPath(repoRoot: string, p: string): string {
  const abs = resolve(repoRoot, p);
  const rel = relative(repoRoot, abs);
  if (rel.startsWith("..") || rel.split(sep).includes("..")) throw new Error(`demo route path outside the repository: ${p}`);
  if (!existsSync(abs)) throw new Error(`demo route file missing: ${p}`);
  return abs;
}

/** GPX 1.1 track points with their <time>. */
export function parseTrack(xml: string): { points: LatLon[]; timesMs: number[] } {
  const re = /<trkpt[^>]*lat="([-0-9.]+)"[^>]*lon="([-0-9.]+)"[^>]*>([\s\S]*?)<\/trkpt>/g;
  const points: LatLon[] = [];
  const timesMs: number[] = [];
  for (const m of xml.matchAll(re)) {
    points.push({ lat: Number(m[1]), lon: Number(m[2]) });
    const t = /<time>([^<]+)<\/time>/.exec(m[3] ?? "");
    timesMs.push(t ? Date.parse(t[1]!) : Number.NaN);
  }
  return { points, timesMs };
}

/** Reads and validates one manifest entry (ADR-0011 §5 "Build validation"). Throws with a reason. */
export function loadDemoRoute(repoRoot: string, e: ManifestEntry): DemoRoutePayload {
  const where = `demo route ${e.id}`;
  const route = JSON.parse(readFileSync(repoPath(repoRoot, e.route), "utf8")) as {
    code?: string;
    routes?: { geometry?: string; legs?: { steps?: unknown[] }[] }[];
  };
  if (route.code !== "Ok") throw new Error(`${where}: code is not "Ok"`);
  if (!Array.isArray(route.routes) || route.routes.length !== 1) throw new Error(`${where}: expected exactly 1 route`);
  const r = route.routes[0]!;
  if (!Array.isArray(r.legs) || r.legs.length !== 1) throw new Error(`${where}: expected exactly 1 leg`);
  if (!Array.isArray(r.legs[0]!.steps) || r.legs[0]!.steps.length < 2) throw new Error(`${where}: expected at least 2 steps`);
  const line = decodePolyline(r.geometry ?? "", 6);
  if (line.length < 2) throw new Error(`${where}: route geometry does not decode`);
  const { points, timesMs } = parseTrack(readFileSync(repoPath(repoRoot, e.track), "utf8"));
  if (points.length < 2) throw new Error(`${where}: track has fewer than 2 points`);
  const t0 = timesMs[0]!;
  for (let i = 0; i < timesMs.length; i++) {
    if (!(timesMs[i] === t0 + i * 1000)) throw new Error(`${where}: track is not continuous at 1 Hz (point ${i})`);
  }
  if (e.picker) {
    const start = { lat: line[0]![1], lon: line[0]![0] };
    const end = { lat: line[line.length - 1]![1], lon: line[line.length - 1]![0] };
    const dStart = distance(points[0]!, start);
    const dEnd = distance(points[points.length - 1]!, end);
    if (dStart > END_TOLERANCE_M) throw new Error(`${where}: first track point is ${dStart.toFixed(1)} m from the route start`);
    if (dEnd > END_TOLERANCE_M) throw new Error(`${where}: last track point is ${dEnd.toFixed(1)} m from the route end`);
  }
  return {
    schema: 1,
    id: e.id,
    mode: e.mode,
    origin: e.origin,
    destination: e.destination,
    route,
    track: { t_ms: timesMs.map((t) => t - t0), lonlat: points.map((p) => [p.lon, p.lat]) },
  };
}

export function summaryOf(e: ManifestEntry, p: DemoRoutePayload): DemoRouteSummary {
  const r = (p.route as { routes: { distance: number; duration: number }[] }).routes[0]!;
  return { id: e.id, label: e.label, mode: e.mode, origin: e.origin, destination: e.destination, distance: r.distance, duration: r.duration, url: `${DEMO_ROUTES_DIR}/${e.id}.json` };
}

/** Fails the build on an invalid demo-mode setting; prints the AC 1 log line in a demo-mode build. */
export function demoModeGuard(parseStaticDemo: (raw: string | undefined) => boolean | null): Plugin {
  return {
    name: "navmn-demo-mode-guard",
    configResolved(config) {
      const raw = config.env["VITE_DEMO_MODE"] as string | undefined;
      const on = parseDemoMode(raw);
      if (on === null) throw new Error(`VITE_DEMO_MODE=${JSON.stringify(raw)} is not valid: use "true" or "false" (web/.env.example)`);
      if (on && parseStaticDemo(config.env["VITE_STATIC_DEMO"] as string | undefined) !== true) {
        throw new Error("VITE_DEMO_MODE=true needs VITE_STATIC_DEMO=true: demo mode never runs with search, reverse or routing on (NAV-017 AC 1)");
      }
      if (on && config.command === "build") config.logger.info(DEMO_BUILD_LOG);
    },
  };
}

/** Demo-mode index.html: no crossorigin, robots noindex, trailing-slash guard (ADR-0011 §2). */
export function demoHtml(): Plugin {
  // Runs before any module or stylesheet: without the trailing slash, relative URLs resolve against the parent folder.
  const slashGuard = `(function(){var l=location,p=l.pathname;if(!/(\\/|\\.html)$/.test(p))l.replace(p+"/"+l.search+l.hash)})();`;
  return {
    name: "navmn-demo-html",
    transformIndexHtml: {
      order: "post",
      handler(html) {
        return {
          html: html.replace(/\scrossorigin(?:=(?:"[^"]*"|'[^']*'|[^\s>]+))?(?=[\s>/])/g, ""),
          tags: [
            { tag: "script", children: slashGuard, injectTo: "head-prepend" },
            { tag: "meta", attrs: { name: "robots", content: "noindex, nofollow" }, injectTo: "head" },
          ],
        };
      },
    },
  };
}

/** Emits demo-routes/<id>.json and the picker summaries (ADR-0011 §5). */
export function demoRoutes(webRoot: string, repoRoot: string): Plugin {
  let payloads: { entry: ManifestEntry; payload: DemoRoutePayload }[] | null = null;
  const load = () =>
    (payloads ??= readManifest(webRoot)
      .filter((e) => e.picker)
      .map((entry) => ({ entry, payload: loadDemoRoute(repoRoot, entry) })));
  return {
    name: "navmn-demo-routes",
    buildStart() {
      payloads = null;
      for (const { entry } of load()) {
        this.addWatchFile(resolve(repoRoot, entry.route));
        this.addWatchFile(resolve(repoRoot, entry.track));
      }
    },
    resolveId(id) {
      return id === VIRTUAL_ID ? RESOLVED_VIRTUAL_ID : null;
    },
    load(id) {
      if (id !== RESOLVED_VIRTUAL_ID) return null;
      return `export default ${JSON.stringify(load().map(({ entry, payload }) => summaryOf(entry, payload)))};`;
    },
    generateBundle() {
      for (const { payload } of load()) {
        this.emitFile({ type: "asset", fileName: `${DEMO_ROUTES_DIR}/${payload.id}.json`, source: JSON.stringify(payload) });
      }
    },
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const m = /\/demo-routes\/([a-z0-9]+)\.json(?:\?|$)/.exec(req.url ?? "");
        const hit = m ? load().find((p) => p.payload.id === m[1]) : undefined;
        if (!hit) return next();
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify(hit.payload));
      });
    },
  };
}

/** Resolves the virtual module in builds without demo mode too (to an empty list), so an unused import never fails. */
export function demoRoutesStub(): Plugin {
  return {
    name: "navmn-demo-routes-stub",
    resolveId(id) {
      return id === VIRTUAL_ID ? RESOLVED_VIRTUAL_ID : null;
    },
    load(id) {
      return id === RESOLVED_VIRTUAL_ID ? "export default [];" : null;
    },
  };
}

/** True when the Vite mode's env turns demo mode on (an invalid value counts as off here; the guard fails the build). */
export function isDemoMode(mode: string, webRoot: string): boolean {
  const env = loadEnv(mode, webRoot, "VITE_");
  return parseDemoMode(env.VITE_DEMO_MODE ?? process.env.VITE_DEMO_MODE) === true;
}

/**
 * Every NAV-017 build plugin, decided per Vite mode, so vite.config.ts stays a plain object:
 *  - always: the guard, and the compile-time switch `__NAVMN_DEMO_MODE__` (the only branch into the demo code, so other
 *    builds contain no demo chunk, no WASM and no route data, AC 4);
 *  - demo mode only: relative base "./" (any sub-folder, AC 2), no module preload and one CSS file (no runtime-created
 *    `<link crossorigin>`, ADR-0011 W5/W7), repo files readable by the dev server, the demo index.html and the route
 *    emitter. Public builds keep base "/" (ADR-0004 §4).
 * The label-rule fixture page is left out of every build by vite.config.ts (noFixturesInBuild, SEC-4B AC 13), the demo
 * build included (ADR-0011 §2).
 */
export function demoModePlugins(o: { webRoot: string; repoRoot: string; parseStaticDemo: (raw: string | undefined) => boolean | null }): Plugin[] {
  const demoOnly = (p: Plugin): Plugin => ({ ...p, apply: (_c, env) => isDemoMode(env.mode, o.webRoot) });
  return [
    demoModeGuard(o.parseStaticDemo),
    {
      name: "navmn-demo-mode-config",
      config(_c, env) {
        const demo = isDemoMode(env.mode, o.webRoot);
        return {
          define: { __NAVMN_DEMO_MODE__: JSON.stringify(demo) },
          ...(demo ? { base: "./", build: { modulePreload: false, cssCodeSplit: false }, server: { fs: { allow: [o.repoRoot] } } } : {}),
        };
      },
    },
    demoOnly(demoHtml()),
    demoOnly(demoRoutes(o.webRoot, o.repoRoot)),
    { ...demoRoutesStub(), apply: (_c, env) => !isDemoMode(env.mode, o.webRoot) },
  ];
}

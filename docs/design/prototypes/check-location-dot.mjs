#!/usr/bin/env node
// Android browse location dot checker (owner: ux-designer). NAV-005 section N, map-style.md §7.8. No dependencies.
// Usage: node docs/design/prototypes/check-location-dot.mjs [--dot-radius=10.5]
// 1. 64-vertex accuracy polygon: every vertex and every edge midpoint is within ±10 % of the accuracy (AC 74).
// 2. Circle visibility rule (§7.8): the minzoom from which the circle is larger than the dot, per accuracy.
// 3. On-screen radius of a 30 m and a 100 m circle by zoom (how much of a 360 dp phone the circle takes).
// 4. Composite contrast of the translucent circle (normal and stale) over the map, and the night glare rule
//    (tokens.json meta: no large area lighter than #3A4452).
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const tokens = JSON.parse(readFileSync(join(here, "..", "tokens.json"), "utf8"));
const arg = process.argv.find((a) => a.startsWith("--dot-radius="));
const R_DOT = arg ? Number(arg.split("=")[1]) : 10.5; // outer radius in dp = circle-radius + circle-stroke-width (as built: 7.5 + 3)
const LAT_UB = 47.9189; // P1 Sükhbaatar Square
const LATS = [["UB P1", LAT_UB], ["Erdenet X1", 49.027], ["Dalanzadgad", 43.57]];
const M_PER_DP_Z0 = 40075016.686 / 512; // MapLibre zoom: 512 dp per world at z0
const EARTH_R = 6371008.8;
let failures = 0;
const rad = (d) => (d * Math.PI) / 180;
const deg = (r) => (r * 180) / Math.PI;

function destination(lat, lon, bearingDeg, distM) {
  const d = distM / EARTH_R, b = rad(bearingDeg), p1 = rad(lat), l1 = rad(lon);
  const p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b));
  const l2 = l1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2));
  return [deg(p2), deg(l2)];
}
function haversine([a1, o1], [a2, o2]) {
  const dp = rad(a2 - a1), dl = rad(o2 - o1);
  const h = Math.sin(dp / 2) ** 2 + Math.cos(rad(a1)) * Math.cos(rad(a2)) * Math.sin(dl / 2) ** 2;
  return 2 * EARTH_R * Math.asin(Math.sqrt(h));
}

console.log("1) 64-vertex accuracy polygon, radius error (AC 74: within ±10 %)");
for (const acc of [5, 30, 100]) {
  const c = [LAT_UB, 106.9176];
  const v = Array.from({ length: 64 }, (_, i) => destination(c[0], c[1], (360 * i) / 64, acc));
  let lo = Infinity, hi = -Infinity;
  for (let i = 0; i < 64; i++) {
    const a = v[i], b = v[(i + 1) % 64];
    const mid = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
    for (const p of [a, mid]) { const e = haversine(c, p) / acc - 1; lo = Math.min(lo, e); hi = Math.max(hi, e); }
  }
  const ok = lo >= -0.1 && hi <= 0.1;
  if (!ok) failures++;
  console.log(`${ok ? "PASS" : "FAIL"}  ${String(acc).padStart(3)} m: ${(lo * 100).toFixed(3)} % .. ${(hi * 100).toFixed(3)} %`);
}

const mPerDp = (lat, z) => (M_PER_DP_Z0 * Math.cos(rad(lat))) / 2 ** z;
const minZoom = (lat, acc) => Math.log2((R_DOT * M_PER_DP_Z0 * Math.cos(rad(lat))) / acc);

console.log(`\n2) circle visibility: minzoom = log2(R_dot x 78271.517 x cos(lat) / accuracy), R_dot = ${R_DOT} dp`);
const accs = [5, 10, 15, 25, 35, 50, 75, 100];
console.log(`accuracy m  ${LATS.map(([n]) => n.padStart(12)).join("")}`);
for (const acc of accs) console.log(`${String(acc).padStart(10)}  ${LATS.map(([, lat]) => minZoom(lat, acc).toFixed(2).padStart(12)).join("")}`);

console.log("\n3) on-screen circle radius at UB (dp)");
console.log(`zoom   ${[5, 10, 30, 100].map((a) => `${a} m`.padStart(8)).join("")}`);
for (let z = 12; z <= 18; z++) {
  console.log(`${String(z).padStart(4)}   ${[5, 10, 30, 100].map((a) => (a / mPerDp(LAT_UB, z)).toFixed(1).padStart(8)).join("")}`);
}

function parse(v) {
  if (v.startsWith("#")) { const n = parseInt(v.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 1]; }
  const m = v.match(/[\d.]+/g).map(Number); return [m[0], m[1], m[2], m[3] ?? 1];
}
const over = (fg, bg) => [0, 1, 2].map((i) => fg[i] * fg[3] + bg[i] * (1 - fg[3]));
const lum = (c) => c.slice(0, 3).map((x) => { x /= 255; return x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4; })
  .reduce((a, x, i) => a + x * [0.2126, 0.7152, 0.0722][i], 0);
const ratio = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
const hex = (c) => "#" + c.map((x) => Math.round(x).toString(16).padStart(2, "0")).join("").toUpperCase();

console.log("\n4) translucent circle over the map (composite contrast; informational, the dot carries position and state)");
const GLARE = lum(parse("#3A4452"));
for (const mode of ["light", "night"]) {
  const loc = tokens.color[mode].location, fl = tokens.color[mode].map.flavor;
  const bgs = ["earth", "buildings", "park_a", "water", "minor_a", "major"];
  console.log(`-- ${mode}`);
  for (const key of ["accuracy-fill", "accuracy-stroke", "stale-accuracy-fill", "stale-accuracy-stroke"]) {
    const fg = parse(loc[key].$value);
    console.log(`${key.padEnd(22)} ${bgs.map((b) => `${b}=${ratio(over(fg, parse(fl[b].$value)), parse(fl[b].$value)).toFixed(2)}`).join(" ")}`);
  }
  if (mode === "night") {
    for (const key of ["accuracy-fill", "stale-accuracy-fill"]) {
      for (const b of ["earth", "buildings", "park_a", "water"]) {
        const c = over(parse(loc[key].$value), parse(fl[b].$value));
        const ok = lum(c) < GLARE;
        if (!ok) failures++;
        console.log(`${ok ? "PASS" : "FAIL"}  glare: ${key} over ${b} = ${hex(c)} ${ok ? "<" : ">="} #3A4452`);
      }
    }
  }
}

console.log(failures ? `\n${failures} failure(s)` : "\nall checks passed");
process.exit(failures ? 1 : 0);

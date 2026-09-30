#!/usr/bin/env node
// Layout checker for the NAV-002 wireframe (owner: ux-designer). Checks the DESIGN, not the app.
// Usage: PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout.mjs
// Uses the Playwright copy installed for QA under tests/e2e (read-only; nothing is installed or changed there).
// For every viewport x state x theme x language x zoom it checks the screen-spec layout rules:
//   - no two visible controls overlap, and no message overlaps a control it does not contain
//   - the attribution is fully inside the viewport, not covered, font-size >= 11px
//   - no control or message covers the attribution or the scale bar
//   - every control is >= 44x44 CSS px, including the attribution link (its box includes the transparent padding)
//   - the attribution link's hit area does not overlap the scale bar, and the strip keeps its one-line height
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "..", "..", "..", "tests", "e2e", "package.json"));
const { chromium } = require("playwright");

const page_url = pathToFileURL(join(here, "NAV-002-web-map.html")).href;
const viewports = [[320, 568], [360, 640], [768, 1024], [1366, 768], [1920, 1080]];
const states = ["ready", "loading", "tiles-start", "tiles-banner", "offline", "offline-start", "generic", "following",
  "not-following", "locating", "denied", "unavailable", "stale", "unsupported", "worst"];

const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto(`${page_url}#toolbar=0`);
let runs = 0, failures = [], infos = [];

for (const [w, h] of viewports) {
  await page.setViewportSize({ width: w, height: h });
  for (const state of states) for (const theme of ["day", "night"]) for (const lang of ["mn", "en"]) for (const zoom of ["normal", "low"]) {
    await page.evaluate((hash) => { location.hash = hash; }, `state=${state}&theme=${theme}&lang=${lang}&zoom=${zoom}&toolbar=0`);
    await page.waitForFunction((s) => document.documentElement.lang && location.hash.includes(s), state);
    runs++;
    const problems = await page.evaluate(() => {
      const out = [];
      const vis = (el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== "hidden"; };
      const name = (el) => el.id || el.dataset.k || el.className || el.tagName;
      const r2box = document.getElementById("messages").getBoundingClientRect();
      // Elements inside the scrollable message row (R2) are clipped to it: overflow that is scrolled out of view covers nothing.
      const box = (el) => {
        const r = el.getBoundingClientRect();
        if (!document.getElementById("messages").contains(el)) return r;
        const top = Math.max(r.top, r2box.top), bottom = Math.min(r.bottom, r2box.bottom);
        return { left: r.left, right: r.right, width: r.width, top, bottom: Math.max(top, bottom), height: Math.max(0, bottom - top) };
      };
      const overlap = (a, b) => a.left < b.right - 0.5 && b.left < a.right - 0.5 && a.top < b.bottom - 0.5 && b.top < a.bottom - 0.5;
      const vw = innerWidth, vh = innerHeight;

      const controls = [...document.querySelectorAll("[data-control]")].filter(vis);
      const messages = [...document.querySelectorAll(".banner, .msg, .card, .pill")].filter(vis);
      const attr = document.getElementById("attribution");
      const scale = document.getElementById("scale");

      for (const c of controls) {
        const r = box(c);
        if (r.width < 44 || r.height < 44) out.push(`${name(c)} is ${r.width.toFixed(0)}x${r.height.toFixed(0)} (< 44)`);
        if (r.left < 0 || r.top < 0 || r.right > vw + 0.5 || r.bottom > vh + 0.5) out.push(`${name(c)} outside viewport`);
      }
      for (let i = 0; i < controls.length; i++) for (let j = i + 1; j < controls.length; j++) {
        if (overlap(box(controls[i]), box(controls[j]))) out.push(`controls overlap: ${name(controls[i])} / ${name(controls[j])}`);
      }
      for (const m of messages) for (const c of controls) {
        if (!m.contains(c) && overlap(box(m), box(c))) out.push(`message ${name(m)} overlaps control ${name(c)}`);
      }
      const r2 = r2box;
      const ar = box(attr);
      if (ar.top < -0.5 || ar.bottom > vh + 0.5 || ar.left < -0.5 || ar.right > vw + 0.5) out.push("attribution not fully in viewport");
      if (Math.abs(ar.width - vw) > 1) out.push(`attribution strip is ${ar.width.toFixed(0)}px wide, viewport ${vw}px`);
      if (document.documentElement.scrollWidth > vw) out.push("horizontal page scroll");
      const fs = parseFloat(getComputedStyle(attr).fontSize);
      if (fs < 11) out.push(`attribution font ${fs}px`);
      const others = [...controls.filter((c) => !attr.contains(c)), ...messages];
      for (const o of others) if (overlap(box(o), ar)) out.push(`${name(o)} covers attribution`);
      if (vis(scale)) for (const o of others) if (overlap(box(o), box(scale))) out.push(`${name(o)} covers scale bar`);
      if (r2.bottom > ar.top + 0.5) out.push("message row extends into attribution");
      const link = attr.querySelector("a");
      if (vis(scale) && overlap(box(link), box(scale))) out.push("attribution link hit area overlaps scale bar");
      if (document.getElementById("esa").hidden && ar.height > 24.5) out.push(`attribution strip is ${ar.height.toFixed(1)}px high at one line (> 24)`);
      // Uncovered: sample points of every attribution line must hit the strip itself.
      for (const p of attr.querySelectorAll("p")) {
        if (!vis(p)) continue;
        const rr = p.getBoundingClientRect();
        for (const fx of [0.02, 0.5, 0.98]) {
          const x = rr.left + rr.width * fx, y = rr.top + rr.height / 2;
          const hit = document.elementFromPoint(x, y);
          if (!hit || !attr.contains(hit)) out.push(`attribution covered at ${x.toFixed(0)},${y.toFixed(0)} by ${hit ? name(hit) : "nothing"}`);
        }
      }
      const osm = attr.querySelector("a");
      if (!vis(osm) || osm.textContent !== "© OpenStreetMap contributors") out.push("OSM credit missing");
      const m = document.getElementById("messages");
      if (m.scrollHeight > m.clientHeight + 1) out.push("INFO message row scrolls");
      return out;
    });
    for (const p of problems) (p.startsWith("INFO") ? infos : failures).push(`${w}x${h} ${state} ${theme} ${lang} zoom=${zoom}: ${p}`);
  }
}
await browser.close();
const unique = [...new Set(failures)];
console.log(infos.join("\n") || "INFO message row never needs to scroll");
console.log(unique.slice(0, 80).join("\n"));
console.log(`\n${runs} combinations checked, ${unique.length} problem(s)`);
process.exit(unique.length ? 1 : 0);

#!/usr/bin/env node
// Layout checker for the NAV-004 route preview wireframe (owner: ux-designer). Checks the DESIGN, not the app.
// Usage: PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav004.mjs
// Uses the Playwright copy installed for QA under tests/e2e (read-only; nothing is installed or changed there).
// For every viewport (story AC 43: 320, 360, 768, 1366, 1920) x state x theme x language x extra (NAV-002 worst-case
// messages) it checks the NAV-004 screen-spec layout rules (AC 43; NAV-002 AC 34, 49 and NAV-003 AC 23 must stay true):
//   - no two visible controls overlap; no panel (route panel, card, banner, message) overlaps another panel or a
//     control it does not contain; controls scrolled out of a scroll box are clipped to it
//   - the attribution is fully inside the viewport, uncovered, >= 11px; nothing covers the scale bar
//   - every control (fields, swap, tabs, switch, route options, turn rows, retry, card buttons) is >= 44x44 CSS px
//   - the panel heading is visible, never truncated, and the close button stays in the viewport (sticky header)
//   - panel open: the NAV-003 search input is hidden and the first Tab stop is the language button;
//     panel closed (place card): the search input is the first Tab stop (NAV-003 AC 1)
//   - no horizontal page scroll
// INFO lines (not failures): which boxes scroll, the height of the map area left uncovered above the bottom sheet,
// and whether the route summary is visible without scrolling the panel.
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "..", "..", "..", "tests", "e2e", "package.json"));
const { chromium } = require("playwright");

const url = pathToFileURL(join(here, "NAV-004-route-preview.html")).href;
const viewports = [[320, 568], [360, 640], [768, 1024], [1366, 768], [1920, 1080]];
const states = ["place-card", "point-card", "origin-empty", "loading", "route", "route-alt", "route-one", "route-long", "walk", "snap",
  "eta-next-day", "field-list", "field-state", "no-route", "no-route-avoid", "out-of-area", "too-far", "same-point", "unavailable",
  "offline", "rate-limited", "error", "static", "coord-card"];

const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto(`${url}#toolbar=0`);
let runs = 0;
const failures = [], infos = new Set();

for (const [w, h] of viewports) {
  await page.setViewportSize({ width: w, height: h });
  for (const state of states) for (const theme of ["day", "night"]) for (const lang of ["mn", "en"]) for (const extra of ["none", "worst"]) {
    const hash = `state=${state}&theme=${theme}&lang=${lang}&extra=${extra}&toolbar=0`;
    await page.evaluate((hsh) => { location.hash = hsh; }, hash);
    await page.waitForFunction((hsh) => location.hash.slice(1) === hsh && document.documentElement.lang, hash);
    runs++;
    const problems = await page.evaluate(([st, ex]) => {
      const out = [];
      const vis = (el) => { if (!el) return false; const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== "hidden"; };
      const name = (el) => el.id || el.getAttribute("aria-label") || (el.textContent || "").trim().slice(0, 24) || el.className || el.tagName;
      const scrollers = ["messages", "panel", "pcard", "card"].map((id) => document.getElementById(id));
      const box = (el) => {
        let r = el.getBoundingClientRect();
        r = { left: r.left, right: r.right, top: r.top, bottom: r.bottom };
        for (const s of scrollers) {
          if (!s || s === el || !s.contains(el)) continue;
          const b = s.getBoundingClientRect();
          r = { left: Math.max(r.left, b.left), right: Math.min(r.right, b.right), top: Math.max(r.top, b.top), bottom: Math.min(r.bottom, b.bottom) };
        }
        r.width = Math.max(0, r.right - r.left); r.height = Math.max(0, r.bottom - r.top);
        return r;
      };
      const overlap = (a, b) => a.width > 0 && b.width > 0 && a.height > 0 && b.height > 0 &&
        a.left < b.right - 0.5 && b.left < a.right - 0.5 && a.top < b.bottom - 0.5 && b.top < a.bottom - 0.5;
      const vw = innerWidth, vh = innerHeight;

      const controls = [...document.querySelectorAll("[data-control]")].filter(vis);
      const panels = [...document.querySelectorAll(".banner, .msg, #panel, #pcard, #card")].filter(vis);
      const attr = document.getElementById("attribution");
      const scale = document.getElementById("scale");

      for (const c of controls) {
        const r = box(c);
        if (r.height < 0.5) continue;
        const full = c.getBoundingClientRect();
        const clipped = r.height < full.height - 0.5;
        if (!clipped && (full.width < 44 || full.height < 44)) out.push(`${name(c)} is ${full.width.toFixed(0)}x${full.height.toFixed(0)} (< 44)`);
        if (r.left < -0.5 || r.top < -0.5 || r.right > vw + 0.5 || r.bottom > vh + 0.5) out.push(`${name(c)} outside viewport`);
      }
      for (let i = 0; i < controls.length; i++) for (let j = i + 1; j < controls.length; j++) {
        if (controls[i].contains(controls[j]) || controls[j].contains(controls[i])) continue;
        if (overlap(box(controls[i]), box(controls[j]))) out.push(`controls overlap: ${name(controls[i])} / ${name(controls[j])}`);
      }
      for (const p of panels) for (const c of controls) if (!p.contains(c) && overlap(box(p), box(c))) out.push(`panel ${name(p)} overlaps control ${name(c)}`);
      for (let i = 0; i < panels.length; i++) for (let j = i + 1; j < panels.length; j++) {
        if (overlap(box(panels[i]), box(panels[j]))) out.push(`panels overlap: ${name(panels[i])} / ${name(panels[j])}`);
      }
      const ar = box(attr);
      if (ar.top < -0.5 || ar.bottom > vh + 0.5 || ar.left < -0.5 || ar.right > vw + 0.5) out.push("attribution not fully in viewport");
      if (document.documentElement.scrollWidth > vw) out.push("horizontal page scroll");
      if (parseFloat(getComputedStyle(attr).fontSize) < 11) out.push("attribution font < 11px");
      const others = [...controls.filter((c) => !attr.contains(c)), ...panels];
      for (const o of others) if (overlap(box(o), ar)) out.push(`${name(o)} covers attribution`);
      if (vis(scale)) for (const o of others) if (overlap(box(o), box(scale))) out.push(`${name(o)} covers scale bar`);
      if (vis(scale) && overlap(box(attr.querySelector("a")), box(scale))) out.push("attribution link hit area overlaps scale bar");
      for (const p of attr.querySelectorAll("p")) {
        if (!vis(p)) continue;
        const rr = p.getBoundingClientRect();
        for (const fx of [0.02, 0.5, 0.98]) {
          const hit = document.elementFromPoint(rr.left + rr.width * fx, rr.top + rr.height / 2);
          if (!hit || !attr.contains(hit)) out.push(`attribution covered by ${hit ? name(hit) : "nothing"}`);
        }
      }
      const q = document.getElementById("q");
      const focusables = [...document.querySelectorAll("button, input, a[href], [tabindex]:not([tabindex='-1'])")].filter((e) => vis(e) && !e.closest("#tb"));
      const open = !document.getElementById("route").hidden;
      if (open) {
        if (vis(q)) out.push("search input visible while the route panel is open");
        const lang = document.querySelector("#cluster button");
        if (focusables[0] !== lang) out.push(`first Tab stop is ${name(focusables[0])}, not the language button`);
        const panelVisible = vis(document.getElementById("panel"));
        if (panelVisible) {
          const hd = document.getElementById("route-h"), cl = document.getElementById("route-close");
          if (!vis(hd)) out.push("panel heading not visible");
          else if (hd.scrollWidth > hd.clientWidth + 1) out.push("panel heading truncated");
          const cr = box(cl);
          if (cr.height < 44) out.push("close button not fully visible");
          const pr = document.getElementById("panel").getBoundingClientRect();
          const sum = document.querySelector(".rp-sum");
          if (sum) {
            const sr = sum.getBoundingClientRect();
            if (sr.top + 28 > pr.bottom) out.push(`INFO ${st} extra=${ex}: summary duration below the fold of the panel`);
          }
          if (innerWidth < 840) {
            const r1 = document.getElementById("r1").getBoundingClientRect();
            const r2 = document.getElementById("messages").getBoundingClientRect();
            const band = Math.round(pr.top - Math.max(r1.bottom, r2.bottom));
            out.push(`INFO map band above the sheet ${band < 120 ? "< 120" : ">= 120"} px (e.g. ${band})`);
          }
        }
      } else {
        if (!vis(q)) out.push("search input not visible with the panel closed");
        if (focusables[0] !== q) out.push(`first Tab stop is ${name(focusables[0])}, not the search input`);
      }
      const hd2 = document.getElementById("card-h");
      if (vis(hd2) && hd2.scrollWidth > hd2.clientWidth + 1) out.push("card heading truncated");
      const m = document.getElementById("messages");
      if (m.scrollHeight > m.clientHeight + 1) out.push("INFO message row scrolls");
      for (const id of ["panel", "pcard", "card"]) {
        const e = document.getElementById(id);
        if (vis(e) && e.scrollHeight > e.clientHeight + 1) out.push(`INFO ${id} scrolls`);
      }
      return out;
    }, [state, extra]);
    for (const p of problems) {
      if (p.startsWith("INFO")) infos.add(`${w}x${h} ${p.slice(5)}`);
      else failures.push(`${w}x${h} ${state} ${theme} ${lang} extra=${extra}: ${p}`);
    }
  }
}
await browser.close();
const unique = [...new Set(failures)];
console.log([...infos].sort().join("\n") || "INFO nothing scrolls");
console.log(unique.slice(0, 80).join("\n"));
console.log(`\n${runs} combinations checked, ${unique.length} problem(s)`);
process.exit(unique.length ? 1 : 0);

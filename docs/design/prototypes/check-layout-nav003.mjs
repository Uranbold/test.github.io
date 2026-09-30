#!/usr/bin/env node
// Layout checker for the NAV-003 search wireframe (owner: ux-designer). Checks the DESIGN, not the app.
// Usage: PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav003.mjs
// Uses the Playwright copy installed for QA under tests/e2e (read-only; nothing is installed or changed there).
// For every viewport x state x theme x language x extra (NAV-002 worst-case messages) x zoom it checks
// the NAV-003 screen-spec layout rules (story AC 20, 21, 23, 39; NAV-002 AC 34, 49 must stay true):
//   - no two visible controls overlap; no panel (results list, place card, banner, message) overlaps another panel
//     or a control it does not contain; options/buttons scrolled out of a scroll box are clipped to it
//   - the attribution is fully inside the viewport, uncovered, >= 11px; nothing covers the scale bar
//   - every control (incl. the search input, clear button, result options, card buttons) is >= 44x44 CSS px
//   - the search input is visible, >= 120px wide, and is the first focusable element (AC 1)
//   - when a card is open, the pin at the viewport centre is not covered by any UI (AC 20: centred point stays visible)
//   - the place-card heading is never truncated; no horizontal page scroll
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "..", "..", "..", "tests", "e2e", "package.json"));
const { chromium } = require("playwright");

const url = pathToFileURL(join(here, "NAV-003-search.html")).href;
const viewports = [[320, 568], [360, 640], [768, 1024], [1366, 768], [1920, 1080]];
const states = ["ready", "short", "loading", "results", "results-long", "coord-option", "no-results", "unavailable", "offline", "rate-limited",
  "bad-request", "card", "card-long", "coord-loading", "coord-result", "coord-empty", "coord-unavailable", "coord-offline",
  "coord-rate-limited", "coord-bad-request", "list-over-card"];

const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto(`${url}#toolbar=0`);
let runs = 0;
const failures = [], infos = new Set();

for (const [w, h] of viewports) {
  await page.setViewportSize({ width: w, height: h });
  for (const state of states) for (const theme of ["day", "night"]) for (const lang of ["mn", "en"]) for (const extra of ["none", "worst"])
  for (const zoom of state.startsWith("coord-") && state !== "coord-option" ? ["normal", "low"] : ["normal"]) {
    const hash = `state=${state}&theme=${theme}&lang=${lang}&extra=${extra}&zoom=${zoom}&toolbar=0`;
    await page.evaluate((hsh) => { location.hash = hsh; }, hash);
    await page.waitForFunction((hsh) => location.hash.slice(1) === hsh && document.documentElement.lang, hash);
    runs++;
    const problems = await page.evaluate(() => {
      const out = [];
      const vis = (el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== "hidden"; };
      const name = (el) => el.id || el.getAttribute("aria-label") || (el.textContent || "").trim().slice(0, 24) || el.className || el.tagName;
      const scrollers = ["messages", "popup", "card"].map((id) => document.getElementById(id));
      // Visible part of an element: clipped to every scroll box that contains it.
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
      const panels = [...document.querySelectorAll(".banner, .msg, #popup, #card")].filter(vis);
      const attr = document.getElementById("attribution");
      const scale = document.getElementById("scale");

      for (const c of controls) {
        const r = box(c);
        if (r.height < 0.5) continue; // fully scrolled out of its scroll box
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
      // Search field (AC 1)
      const q = document.getElementById("q");
      if (!vis(q)) out.push("search input not visible");
      else if (q.getBoundingClientRect().width < 120) out.push(`search input only ${q.getBoundingClientRect().width.toFixed(0)}px wide`);
      const focusables = [...document.querySelectorAll("button, input, a[href], [tabindex]:not([tabindex='-1'])")].filter((e) => vis(e) && !e.closest("#tb"));
      if (focusables[0] !== q) out.push(`first Tab stop is ${name(focusables[0])}, not the search input`);
      // Pin at the viewport centre must stay visible when a card is open and the camera centred on it (AC 20, 21, 26).
      // Not checked for zoom=low: that card comes from a right-click, where the camera does not move (AC 25) and the pin
      // is wherever the user clicked (screen spec, Known limitations). NAV-002 banners/messages over the pin are INFO only
      // (transient, closable, NAV-002 precedence unchanged).
      const pin = document.getElementById("pin");
      if (!pin.hidden && document.body.dataset.zoom !== "low") {
        const pr = pin.getBoundingClientRect();
        const nav003 = [document.getElementById("card"), document.getElementById("popup"), document.getElementById("q").closest(".field")];
        const covering = [...controls, ...panels, ...(vis(scale) ? [scale] : []), attr, nav003[2]].filter((o) => vis(o) && overlap(box(o), pr));
        const cardShown = !document.getElementById("place").hidden;
        for (const c of covering) {
          const isNav002Msg = c.matches(".banner, .msg") || c.closest(".banner, .msg");
          if (cardShown && !isNav002Msg) out.push(`pin covered by ${name(c)}`);
          else out.push(`INFO pin under ${name(c)}${cardShown ? " (NAV-002 message)" : " while the list is open (card hidden)"}`);
        }
      }
      const hd = document.getElementById("card-h");
      if (vis(hd) && hd.scrollWidth > hd.clientWidth + 1) out.push("card heading truncated");
      const m = document.getElementById("messages");
      if (m.scrollHeight > m.clientHeight + 1) out.push("INFO message row scrolls");
      const pop = document.getElementById("popup");
      if (vis(pop) && pop.scrollHeight > pop.clientHeight + 1) out.push("INFO results list scrolls");
      const cd = document.getElementById("card");
      if (vis(cd) && cd.scrollHeight > cd.clientHeight + 1) out.push("INFO place card scrolls");
      return out;
    });
    for (const p of problems) {
      if (p.startsWith("INFO")) infos.add(`${w}x${h} ${p.slice(5)}`);
      else failures.push(`${w}x${h} ${state} ${theme} ${lang} extra=${extra} zoom=${zoom}: ${p}`);
    }
  }
}
await browser.close();
const unique = [...new Set(failures)];
console.log([...infos].sort().join("\n") || "INFO nothing scrolls");
console.log(unique.slice(0, 80).join("\n"));
console.log(`\n${runs} combinations checked, ${unique.length} problem(s)`);
process.exit(unique.length ? 1 : 0);

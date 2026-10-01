#!/usr/bin/env node
// Layout checker for the NAV-017 web demo-mode wireframe (owner: ux-designer). Checks the DESIGN, not the app.
// Usage: PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav017.mjs [--wide]
// Uses the Playwright copy installed for QA under tests/e2e (read-only; nothing is installed or changed there).
// Chromium only: this container has no WebKit build, so iPhone Safari is approximated by its viewport sizes and
// simulated safe-area insets (screen spec › Verification lists what needs the real iPhone).
// For every viewport x state x theme x language x text zoom it checks the screen-spec layout rules
// (docs/design/screens/NAV-017-web-demo-mode.md › Layout rules; story AC 8, 14, 22, 29, 37, 38):
//   - the attribution strip is fully inside the viewport, text >= 11px, and intersects no other box or control (AC 37)
//   - no two boxes (banner, badge, message stack, progress/arrival panel, picker, recenter) overlap,
//     and no control overlaps a box it is not inside
//   - every control is >= 44x44 CSS px (AC 38); 44-48 px is reported as INFO (design target 48)
//   - no control lies under a simulated safe-area inset (AC 37)
//   - the banner instruction has <= 3 lines and is not clipped; control labels are not clipped (AC 37)
//   - guidance, portrait: the map band between the top UI and the bottom UI is >= 150px at text zoom 1 and >= 80px at 2
//     (64px / 80px while the A1 voice notice shows); the puck point is inside the uncovered map (AC 22)
//   - picker: «Эхлэх» is visible without scrolling; the picker heading is visible (AC 8)
//   - no horizontal overflow
// Default font: Liberation Sans (Arial/Helvetica metrics; close to SF Pro Text). --wide: DejaVu Sans (stress test).
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "..", "..", "..", "tests", "e2e", "package.json"));
const { chromium } = require("playwright");

const font = process.argv.includes("--wide") ? "wide" : "narrow";
const url = pathToFileURL(join(here, "NAV-017-demo-mode.html")).href;
// [width, height, insets "t,r,b,l", label]. First four: the AC 37 device viewports (Safari toolbars collapsed, page under
// the home indicator). Last three: the area Safari actually leaves with its toolbars shown (design addition).
const viewports = [
  [375, 667, "0,0,0,0", "AC37 iPhone SE"],
  [390, 844, "0,0,34,0", "AC37 iPhone 12-15"],
  [430, 932, "0,0,34,0", "AC37 iPhone Pro Max"],
  [844, 390, "0,47,21,47", "AC37 landscape"],
  [375, 548, "0,0,0,0", "Safari bars, iPhone SE"],
  [390, 664, "0,0,0,0", "Safari bars, iPhone 12-15"],
  [844, 340, "0,47,0,47", "Safari bars, landscape"],
];
const states = ["picker", "picker-selected", "picker-loading", "picker-error", "picker-offline",
  "guidance", "long", "notice", "recenter", "offline", "worst", "arrival"];
const zooms = [1, 2];

const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto(`${url}#toolbar=0`);
let runs = 0;
const failures = [], infos = [];
const lineInfo = new Map(), bandInfo = new Map(), smallInfo = new Set(), scrollInfo = new Set();

for (const [w, h, insets, label] of viewports) {
  await page.setViewportSize({ width: w, height: h });
  for (const state of states) for (const theme of ["day", "night"]) for (const lang of ["mn", "en"]) for (const tz of zooms) {
    const hash = `state=${state}&theme=${theme}&lang=${lang}&tz=${tz}&insets=${insets}&font=${font}&toolbar=0`;
    await page.evaluate((hsh) => { location.hash = hsh; }, hash);
    await page.waitForFunction((hsh) => location.hash.slice(1) === hsh, hash);
    await page.evaluate((z) => { window.__tz = z; return document.fonts.ready; }, tz);
    runs++;
    const r = await page.evaluate(() => {
      const out = [], info = { small: [] };
      const cs = getComputedStyle(document.documentElement);
      const [sat, sar, sab, sal] = ["--sat", "--sar", "--sab", "--sal"].map((k) => parseFloat(cs.getPropertyValue(k)) || 0);
      const vis = (el) => { const b = el.getBoundingClientRect(); return b.width > 0 && b.height > 0 && !el.closest("[hidden]"); };
      const scrollers = [document.getElementById("top"), document.getElementById("pscroll"), document.getElementById("r2")].filter(Boolean);
      const rect = (el) => {
        let q = el.getBoundingClientRect();
        q = { left: q.left, right: q.right, top: q.top, bottom: q.bottom };
        for (const sc of scrollers) {
          if (sc === el || !sc.contains(el)) continue;
          const b = sc.getBoundingClientRect();
          q = { left: Math.max(q.left, b.left), right: Math.min(q.right, b.right), top: Math.max(q.top, b.top), bottom: Math.min(q.bottom, b.bottom) };
        }
        q.width = Math.max(0, q.right - q.left); q.height = Math.max(0, q.bottom - q.top);
        return q;
      };
      info.scrolls = scrollers.filter((sc) => sc.scrollHeight > sc.clientHeight + 1).map((sc) => sc.id);
      const inter = (a, b) => Math.min(a.right, b.right) - Math.max(a.left, b.left) > 0.5 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 0.5;
      const name = (el) => el.dataset.box || el.getAttribute("aria-label") || (el.textContent || "").trim().slice(0, 20) || el.tagName;
      const boxes = [...document.querySelectorAll("[data-box]")].filter(vis);
      const controls = [...document.querySelectorAll("[data-control]")].filter(vis);
      const vw = innerWidth, vh = innerHeight;
      // attribution (AC 37)
      const attr = document.querySelector('[data-box="attribution"]');
      const ar = rect(attr);
      if (ar.left < 0 || ar.top < 0 || ar.right > vw + 0.5 || ar.bottom > vh + 0.5) out.push("attribution not fully in the viewport");
      if (parseFloat(getComputedStyle(attr).fontSize) < 11) out.push("attribution text < 11px");
      const link = attr.querySelector("a").getBoundingClientRect();
      if (link.top < vh - sab - 4 - 40 && link.bottom > vh - sab + 0.5) out.push("attribution text under the bottom inset");
      for (const el of [...boxes, ...controls]) {
        if (el === attr || attr.contains(el)) continue;
        if (inter(ar, rect(el))) out.push(`attribution intersects ${name(el)}`);
      }
      // boxes do not overlap
      for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
        const a = boxes[i], b = boxes[j];
        if (a.contains(b) || b.contains(a) || a === attr || b === attr) continue;
        if (inter(rect(a), rect(b))) out.push(`${name(a)} overlaps ${name(b)}`);
      }
      // controls
      for (const c of controls) {
        const cr = rect(c);
        if (cr.width < 0.5 || cr.height < 0.5) continue; // scrolled out of view inside a scroll box
        const full = c.getBoundingClientRect();
        if (full.width < 43.5 || full.height < 43.5) out.push(`control < 44px: ${name(c)} ${full.width.toFixed(0)}x${full.height.toFixed(0)}`);
        else if (full.width < 47.5 || full.height < 47.5) info.small.push(name(c));
        if (c.scrollWidth > c.clientWidth + 1 || c.scrollHeight > c.clientHeight + 1) out.push(`control label clipped: ${name(c)}`);
        for (const b of boxes) if (!b.contains(c) && b.dataset.box !== "recenter" && inter(cr, rect(b))) out.push(`control ${name(c)} overlaps ${name(b)}`);
        // safe areas (AC 37): nothing interactive under an inset
        if (cr.left < sal - 0.5 || cr.right > vw - sar + 0.5 || cr.top < sat - 0.5 || cr.bottom > vh - sab + 0.5) out.push(`control under a safe-area inset: ${name(c)}`);
      }
      for (let i = 0; i < controls.length; i++) for (let j = i + 1; j < controls.length; j++) {
        if (controls[i].contains(controls[j]) || controls[j].contains(controls[i])) continue;
        if (inter(rect(controls[i]), rect(controls[j]))) out.push(`controls overlap: ${name(controls[i])} / ${name(controls[j])}`);
      }
      // banner instruction (AC 37: up to 3 lines, no mid-word truncation)
      const instr = document.querySelector('[data-check="instruction"]');
      if (instr) {
        const lh = parseFloat(getComputedStyle(instr).lineHeight);
        info.lines = Math.round(instr.getBoundingClientRect().height / lh);
        if (info.lines > 3) out.push(`banner instruction has ${info.lines} lines (> 3)`);
        if (instr.scrollWidth > instr.clientWidth + 1) out.push("banner instruction overflows horizontally");
        const top = document.getElementById("top");
        if (top && rect(instr).height + 0.5 < instr.getBoundingClientRect().height) out.push("banner instruction scrolled out of view");
      }
      // guidance, portrait: map band and puck (AC 22, 29)
      const wide = document.documentElement.dataset.wide === "1";
      const prog = document.querySelector('[data-box="progress"]');
      const banner = document.querySelector('[data-box="banner"]');
      if (prog && banner) {
        const top = document.getElementById("top");
        const topBottom = wide ? 0 : rect(top).bottom;
        const stackEl = document.querySelector('[data-box="messages"]');
        const bottomTop = rect(stackEl && !wide ? stackEl : prog).top;
        const band = bottomTop - Math.max(topBottom, wide ? 0 : 0);
        if (!wide) {
          info.band = Math.round(band);
          const notice = !!stackEl && stackEl.dataset.hasA1 === "true";
          const need = notice ? (window.__tz > 1 ? 64 : 80) : (window.__tz > 1 ? 80 : 150);
          if (band < need) out.push(`map band ${band.toFixed(0)}px < ${need}px`);
        }
        const puck = document.getElementById("puck");
        const pr = rect(puck), cy = (pr.top + pr.bottom) / 2, cx = (pr.left + pr.right) / 2;
        if (cy < topBottom || cy > bottomTop) out.push("puck centre is under the UI");
        if (wide && cx < rect(top).right) out.push("puck centre is under the left column");
      }
      // picker (AC 8): heading and «Эхлэх» visible without scrolling
      const startBtn = document.querySelector("[data-start]");
      if (startBtn) {
        const sr = startBtn.getBoundingClientRect();
        if (sr.bottom > vh - sab + 0.5 || sr.top < 0) out.push("«Эхлэх» not fully visible");
        const ph = document.getElementById("ph").getBoundingClientRect();
        if (ph.top < 0 || ph.height < 1) out.push("picker heading not visible");
        const entries = [...document.querySelectorAll("[data-entry]")];
        info.entriesVisible = entries.filter((e) => { const q = rect(e); return q.height >= e.getBoundingClientRect().height - 0.5; }).length;
      }
      if (document.documentElement.scrollWidth > vw + 0.5) out.push("horizontal overflow");
      return { out, info };
    });
    const key = `${w}x${h} (${label}) tz ${tz}`;
    if (r.info.lines != null) lineInfo.set(`${key} ${lang}`, Math.max(lineInfo.get(`${key} ${lang}`) ?? 0, r.info.lines));
    if (r.info.band != null) bandInfo.set(key, Math.min(bandInfo.get(key) ?? 1e9, r.info.band));
    if (r.info.entriesVisible != null) {
      const k = `INFO  picker entries fully visible without scrolling ${key} ${state}: ${r.info.entriesVisible}/3`;
      if (r.info.entriesVisible < 3 && state === "picker-selected" && theme === "day" && lang === "mn") infos.push(k);
    }
    for (const s of r.info.small) smallInfo.add(`${key}: ${s}`);
    for (const s of r.info.scrolls) scrollInfo.add(`${key} ${state}: #${s}`);
    for (const p of r.out) failures.push(`${w}x${h} ${state} ${theme} ${lang} tz=${tz}: ${p}`);
  }
}
await browser.close();
for (const [k, v] of lineInfo) console.log(`INFO  max banner instruction lines ${k}: ${v}`);
for (const [k, v] of bandInfo) console.log(`INFO  min portrait map band ${k}: ${v}px`);
for (const k of infos) console.log(k);
for (const k of scrollInfo) console.log(`INFO  scrolls: ${k}`);
for (const k of smallInfo) console.log(`INFO  control 44-48px: ${k}`);
for (const f of failures) console.log(`FAIL  ${f}`);
console.log(`\n${runs} combinations (font: ${font}), ${failures.length} problem(s)`);
process.exit(failures.length ? 1 : 0);

#!/usr/bin/env node
// Layout checker for the NAV-005 Android guidance wireframe (owner: ux-designer). Checks the DESIGN, not the app.
// Usage: PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav005.mjs
// Uses the Playwright copy installed for QA under tests/e2e (read-only; nothing is installed or changed there).
// 1 CSS px = 1 dp. For every viewport x state x theme x language x font scale it checks the screen-spec layout rules
// (docs/design/screens/NAV-005-android-navigation.md › Layout rules; navigation-ux.md §2.5; story AC 2, 23, 62, 63):
//   - the attribution strip is fully inside the viewport, >= 11px text, and intersects no other box or control (AC 2)
//   - no two boxes (banner, status messages, progress/arrival panel, sheet, recenter, dialog) overlap, and no control
//     overlaps a box it is not inside
//   - every control is >= 48x48 (AC 62)
//   - the banner instruction has <= 3 lines and is not clipped; button labels are not clipped (AC 63)
//   - portrait guidance: the map band between the top UI and the
//     bottom panel is >= 150dp at font scale 1 and >= 80dp at 1.3 and 2 (Layout rule 4; 320x568 reported as INFO only);
//     the puck point (70 % of the band) is inside the uncovered map (AC 23)
//   - no horizontal overflow
// Runs with Liberation Sans (Arial metrics, slightly wider than Roboto, the Android default) by default; pass --wide for
// DejaVu Sans (much wider), a stress test whose failures are reported but are not a design target.
// INFO lines (not failures): banner instruction line counts and map band heights.
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "..", "..", "..", "tests", "e2e", "package.json"));
const { chromium } = require("playwright");

const font = process.argv.includes("--wide") ? "wide" : "narrow";
const url = pathToFileURL(join(here, "NAV-005-guidance.html")).href;
const viewports = [[360, 640], [412, 915], [320, 568], [640, 360]];
const states = ["guidance", "then", "worst", "reroute", "reroute-failed", "gps-lost", "offline", "notice", "arrival",
  "preview-route", "preview-denied", "preview-state", "rationale"];
const scales = [1, 1.3, 2];

const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto(`${url}#toolbar=0`);
let runs = 0;
const failures = [];
const lineInfo = new Map(); // key: vp/scale/lang -> max instruction lines
const bandInfo = new Map(); // key: vp/scale -> min band
const scrollInfo = new Set(); // combinations where the banner region scrolls
const outside = []; // problems outside the design target

for (const [w, h] of viewports) {
  await page.setViewportSize({ width: w, height: h });
  for (const state of states) for (const theme of ["day", "night"]) for (const lang of ["mn", "en"]) for (const scale of scales) {
    const hash = `state=${state}&theme=${theme}&lang=${lang}&scale=${scale}&font=${font}&toolbar=0`;
    await page.evaluate((hsh) => { location.hash = hsh; }, hash);
    await page.waitForFunction((hsh) => location.hash.slice(1) === hsh, hash);
    await page.evaluate((sc) => { window.__scale = sc; return document.fonts.ready; }, scale);
    runs++;
    const r = await page.evaluate(() => {
      const out = [], info = {};
      const vis = (el) => { const b = el.getBoundingClientRect(); return b.width > 0 && b.height > 0 && !el.closest("[hidden]"); };
      // Boxes inside a scroll container (the banner region #top and the preview sheet's scroll area scroll when their
      // content does not fit, Layout rules 4 and 6) are clipped to it, as the user sees them.
      const scrollers = [document.getElementById("top"), document.querySelector("#sheet .scroll")].filter(Boolean);
      const rect = (el) => {
        let r = el.getBoundingClientRect();
        r = { left: r.left, right: r.right, top: r.top, bottom: r.bottom };
        for (const sc of scrollers) {
          if (sc === el || !sc.contains(el)) continue;
          const b = sc.getBoundingClientRect();
          r = { left: Math.max(r.left, b.left), right: Math.min(r.right, b.right), top: Math.max(r.top, b.top), bottom: Math.min(r.bottom, b.bottom) };
        }
        r.width = Math.max(0, r.right - r.left); r.height = Math.max(0, r.bottom - r.top);
        return r;
      };
      info.scrolls = scrollers.some((sc) => sc.scrollHeight > sc.clientHeight + 1);
      const inter = (a, b) => Math.min(a.right, b.right) - Math.max(a.left, b.left) > 0.5 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 0.5;
      const name = (el) => el.dataset.box || el.getAttribute("aria-label") || (el.textContent || "").trim().slice(0, 20) || el.tagName;
      const boxes = [...document.querySelectorAll("[data-box]")].filter(vis);
      const controls = [...document.querySelectorAll("[data-control]")].filter(vis);
      const vw = innerWidth, vh = innerHeight;
      // attribution
      const attr = document.querySelector('[data-box="attribution"]');
      const ar = rect(attr);
      if (ar.left < 0 || ar.top < 0 || ar.right > vw + 0.5 || ar.bottom > vh + 0.5) out.push("attribution not fully in the viewport");
      if (parseFloat(getComputedStyle(attr).fontSize) < 11) out.push("attribution text < 11px");
      if (attr.scrollWidth > attr.clientWidth + 1) out.push("attribution text clipped");
      const dialogOpen = !!document.getElementById("scrim");
      for (const el of [...boxes, ...controls]) {
        if (el === attr) continue;
        if (dialogOpen && el.closest("#scrim")) continue; // the OS-style dialog scrim may dim the strip (Layout rule 1)
        if (inter(ar, rect(el))) out.push(`attribution intersects ${name(el)}`);
      }
      // boxes do not overlap each other (except nesting)
      for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
        const a = boxes[i], b = boxes[j];
        if (a.contains(b) || b.contains(a)) continue;
        if (dialogOpen && (a.closest("#scrim") || b.closest("#scrim"))) continue;
        if (inter(rect(a), rect(b))) out.push(`${name(a)} overlaps ${name(b)}`);
      }
      // controls: size, not overlapping foreign boxes or each other, labels not clipped
      for (const c of controls) {
        const cr = rect(c);
        if (cr.width < 0.5 || cr.height < 0.5) continue; // scrolled out of view inside a scroll box
        const full = c.getBoundingClientRect();
        if (full.width < 47.5 || full.height < 47.5) out.push(`control < 48dp: ${name(c)} ${full.width.toFixed(0)}x${full.height.toFixed(0)}`);
        if (c.scrollWidth > c.clientWidth + 1 || c.scrollHeight > c.clientHeight + 1) out.push(`control label clipped: ${name(c)}`);
        for (const b of boxes) if (!b.contains(c) && !(dialogOpen && (b.closest("#scrim") || c.closest("#scrim"))) && inter(cr, rect(b)) && b.dataset.box !== "recenter") out.push(`control ${name(c)} overlaps ${name(b)}`);
      }
      for (let i = 0; i < controls.length; i++) for (let j = i + 1; j < controls.length; j++) {
        if (dialogOpen && (controls[i].closest("#scrim") !== controls[j].closest("#scrim"))) continue;
        if (inter(rect(controls[i]), rect(controls[j]))) out.push(`controls overlap: ${name(controls[i])} / ${name(controls[j])}`);
      }
      // banner instruction lines and clipping
      const instr = document.querySelector('[data-check="instruction"]');
      if (instr) {
        const lh = parseFloat(getComputedStyle(instr).lineHeight);
        const lines = Math.round(instr.getBoundingClientRect().height / lh);
        info.lines = lines;
        if (lines > 3) out.push(`banner instruction has ${lines} lines (> 3)`);
        if (instr.scrollWidth > instr.clientWidth + 1) out.push("banner instruction overflows horizontally");
      }
      // portrait guidance rules
      const port = document.documentElement.dataset.orient === "port";
      const top = document.getElementById("top"), prog = document.querySelector('[data-box="progress"]');
      if (port && prog && document.querySelector('[data-box="banner"]')) {
        const msgs = [...document.querySelectorAll("#status .msg")].filter(vis);
        const topBottom = Math.max(rect(document.querySelector('[data-box="banner"]')).bottom, ...msgs.map((m) => rect(m).bottom));
        const bottomTop = rect(document.querySelector('[data-box="voice-notice"]') || prog).top;
        const band = bottomTop - topBottom;
        info.band = Math.round(band);
        // Layout rule 4: >= 150dp at font scale 1 and >= 80dp (puck + 20dp each side) at 1.3 and 2, on 360x640 and
        // larger (the guidance overlay text is capped at 1.3, navigation-ux §2.5); 320x568 is reported by the INFO lines.
        // While the voice notice (A1) shows above the progress panel (8 s, once per session) the minimum is 80dp at font
        // scale 1 and 64dp (puck + 12dp each side) above it.
        const notice = !!document.querySelector('[data-box="voice-notice"]');
        const need = innerWidth < 360 ? 0 : notice ? (window.__scale > 1 ? 64 : 80) : window.__scale > 1 ? 80 : 150;
        if (band < need) out.push(`map band ${band.toFixed(0)}dp < ${need}dp`);
        const puck = document.getElementById("puck");
        if (puck) {
          const pr = rect(puck), cy = (pr.top + pr.bottom) / 2;
          if (cy < topBottom || cy > bottomTop) out.push("puck centre is under the UI");
        }
      }
      // Preview sheet at font scale 1 on 360x640 and larger: every control is visible without scrolling (Layout rule 6).
      const sheetScroll = document.querySelector("#sheet .scroll");
      if (sheetScroll && window.__scale === 1 && innerWidth >= 360 && innerHeight >= 640 && sheetScroll.scrollHeight > sheetScroll.clientHeight + 1)
        out.push("preview sheet needs scrolling at font scale 1");
      if (document.documentElement.scrollWidth > vw + 0.5) out.push("horizontal overflow");
      return { out, info };
    });
    const key = `${w}x${h} scale ${scale} ${lang}`;
    if (r.info.lines != null) lineInfo.set(key, Math.max(lineInfo.get(key) ?? 0, r.info.lines));
    if (r.info.band != null) { const k = `${w}x${h} scale ${scale}`; bandInfo.set(k, Math.min(bandInfo.get(k) ?? 1e9, r.info.band)); }
    if (r.info.scrolls) scrollInfo.add(`${w}x${h} ${state} scale ${scale}`);
    // 320x568 above font scale 1 is outside the design target (screen spec › Known limitations): reported, not failed.
    const target = !(w < 360 && scale > 1);
    for (const p of r.out) (target ? failures : outside).push(`${w}x${h} ${state} ${theme} ${lang} scale=${scale}: ${p}`);
  }
}
await browser.close();
for (const [k, v] of lineInfo) console.log(`INFO  max banner instruction lines ${k}: ${v}`);
for (const [k, v] of bandInfo) console.log(`INFO  min portrait map band ${k}: ${v}dp`);
for (const k of scrollInfo) console.log(`INFO  scroll region (banner column or preview sheet) scrolls: ${k}`);
for (const f of outside) console.log(`INFO  outside target: ${f}`);
for (const f of failures) console.log(`FAIL  ${f}`);
console.log(`\n${runs} combinations (font: ${font}), ${failures.length} problem(s), ${outside.length} outside the design target (INFO)`);
process.exit(failures.length ? 1 : 0);

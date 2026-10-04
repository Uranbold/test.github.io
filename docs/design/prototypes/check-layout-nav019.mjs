#!/usr/bin/env node
// Layout checker for the NAV-019 Android demo wireframe (owner: ux-designer). Checks the DESIGN, not the app.
// Usage: PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav019.mjs [--wide] [--v=a|b|c] [--strict]
// Uses the Playwright copy installed for QA under tests/e2e (read-only; nothing is installed or changed there).
// 1 CSS px = 1 dp. For every viewport x state x theme x language x font scale it checks the rules of
// docs/design/screens/android-demo-picker.md › Layout rules (NAV-019 AC 6, 17, 37, 39) plus the NAV-005 rules it reuses:
//   - the attribution strip is fully inside the viewport, >= 11px text, and intersects no other box or control (AC 37)
//   - no two boxes overlap (badge, banner, progress/arrival panel, voice notice, sheets, messages, recenter), and no
//     control overlaps a box it is not inside or another control
//   - every control is >= 48x48 and its label is not clipped (AC 39)
//   - the badge «Туршилтын горим» is fully visible (not scrolled away, not clipped) on preview, guidance and arrival;
//     the picker heading (same W1 label) is fully visible on the picker; exactly one W1 label is on screen (AC 17)
//   - guidance: the demo row is one row (badge and pause side by side); the banner instruction has <= 3 lines;
//     portrait map band from the bottom of the demo row to the top of RN/RP >= 150dp at font scale 1 and >= 80dp
//     above (>= 80/64dp while the voice notice shows); the puck point is inside the band (NAV-005 Layout rules 2, 4)
//   - preview: «Эхлэх» fully visible
//   - no horizontal overflow
// Outside the design target, reported as INFO: 320x568 above font scale 1 (as NAV-005), and guidance on phones smaller
// than 360x640 or on 360x640 above font scale 1 (screen spec › Known limitations 1). --strict uses the NAV-005 target.
//   - picker: one whole entry visible without scrolling at font scale <= 1.3 (>= 48 dp of it at 2.0); all three at
//     font scale 1 on >= 360x640 portrait
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "..", "..", "..", "tests", "e2e", "package.json"));
const { chromium } = require("playwright");

const font = process.argv.includes("--wide") ? "wide" : "narrow";
const strict = process.argv.includes("--strict"); // --strict: the NAV-005 design target (only 320x568 above font scale 1 excluded)
const variant = (process.argv.find((a) => a.startsWith("--v=")) || "--v=a").slice(4); // a = chosen layout; b, c = measured alternatives
const url = pathToFileURL(join(here, "NAV-019-android-demo.html")).href;
const viewports = [[360, 640], [360, 780], [412, 915], [320, 568], [640, 360]];
const states = ["picker", "picker-loading", "picker-tiles", "picker-error", "preview", "preview-unavailable",
  "guidance", "worst", "paused", "notice", "reroute", "arrival"];
const scales = [1, 1.3, 2];

const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto(`${url}#toolbar=0`);
let runs = 0;
const failures = [], outside = [];
const bandInfo = new Map(), lineInfo = new Map(), pickerInfo = new Map();

for (const [w, h] of viewports) {
  await page.setViewportSize({ width: w, height: h });
  for (const state of states) for (const theme of ["day", "night"]) for (const lang of ["mn", "en"]) for (const scale of scales) {
    const hash = `state=${state}&theme=${theme}&lang=${lang}&scale=${scale}&font=${font}&v=${variant}&toolbar=0`;
    await page.evaluate((hsh) => { location.hash = hsh; }, hash);
    await page.waitForFunction((hsh) => location.hash.slice(1) === hsh, hash);
    await page.evaluate((sc) => { window.__scale = sc; return document.fonts.ready; }, scale);
    runs++;
    const r = await page.evaluate(() => {
      const out = [], info = {};
      const vis = (el) => { const b = el.getBoundingClientRect(); return b.width > 0 && b.height > 0 && !el.closest("[hidden]"); };
      const scrollers = [...document.querySelectorAll("#top, #left, .sheet .scroll")];
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
      const fully = (el) => { const a = el.getBoundingClientRect(), b = rect(el); return b.height >= a.height - 0.5 && b.width >= a.width - 0.5; };
      const inter = (a, b) => Math.min(a.right, b.right) - Math.max(a.left, b.left) > 0.5 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 0.5;
      const name = (el) => el.dataset.box || el.getAttribute("aria-label") || (el.textContent || "").trim().slice(0, 20) || el.tagName;
      const boxes = [...document.querySelectorAll("[data-box]")].filter(vis);
      const controls = [...document.querySelectorAll("[data-control]")].filter(vis);
      const vw = innerWidth, vh = innerHeight, surface = document.documentElement.dataset.surface;
      const attr = document.querySelector('[data-box="attribution"]'), ar = rect(attr);
      if (ar.left < 0 || ar.top < 0 || ar.right > vw + 0.5 || ar.bottom > vh + 0.5) out.push("attribution not fully in the viewport");
      if (parseFloat(getComputedStyle(attr).fontSize) < 11) out.push("attribution text < 11px");
      for (const el of [...boxes, ...controls]) if (el !== attr && inter(ar, rect(el))) out.push(`attribution intersects ${name(el)}`);
      for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
        const a = boxes[i], b = boxes[j];
        if (a.contains(b) || b.contains(a)) continue;
        if (inter(rect(a), rect(b))) out.push(`${name(a)} overlaps ${name(b)}`);
      }
      for (const c of controls) {
        const cr = rect(c);
        if (cr.width < 0.5 || cr.height < 0.5) continue; // scrolled out of view inside a scroll box
        const full = c.getBoundingClientRect();
        if (full.width < 47.5 || full.height < 47.5) out.push(`control < 48dp: ${name(c)} ${full.width.toFixed(0)}x${full.height.toFixed(0)}`);
        if (c.scrollWidth > c.clientWidth + 1 || c.scrollHeight > c.clientHeight + 1) out.push(`control label clipped: ${name(c)}`);
        for (const b of boxes) if (!b.contains(c) && b.dataset.box !== "recenter" && inter(cr, rect(b))) out.push(`control ${name(c)} overlaps ${name(b)}`);
      }
      for (let i = 0; i < controls.length; i++) for (let j = i + 1; j < controls.length; j++)
        if (!controls[i].contains(controls[j]) && !controls[j].contains(controls[i]) && inter(rect(controls[i]), rect(controls[j]))) out.push(`controls overlap: ${name(controls[i])} / ${name(controls[j])}`);
      // AC 17: exactly one W1 label, fully visible
      const badge = document.querySelector('[data-box="badge"]'), heading = document.querySelector('[data-check="picker-heading"]');
      const w1 = [badge, heading].filter((e) => e && vis(e));
      if (w1.length !== 1) out.push(`expected exactly 1 W1 label on screen, found ${w1.length}`);
      for (const e of w1) if (!fully(e)) out.push(`W1 label not fully visible (${surface})`);
      if (badge && badge.scrollWidth > badge.clientWidth + 1) out.push("badge text clipped");
      // guidance
      if (surface === "guidance") {
        const pause = document.querySelector('[data-check="pause"]');
        if (pause && badge && document.getElementById("rd").contains(pause) && document.documentElement.dataset.orient === "port") { const a = badge.getBoundingClientRect(), p = pause.getBoundingClientRect(); if (a.bottom <= p.top || p.bottom <= a.top) out.push("demo row wraps (badge and pause on different rows)"); }
        const instr = document.querySelector('[data-check="instruction"]');
        const lines = Math.round(instr.getBoundingClientRect().height / parseFloat(getComputedStyle(instr).lineHeight));
        info.lines = lines;
        if (lines > 3) out.push(`banner instruction has ${lines} lines (> 3)`);
        if (document.documentElement.dataset.orient === "port") {
          const topBottom = rect(document.getElementById("left")).bottom;
          const bottomTop = rect(document.querySelector('[data-box="voice-notice"]') || document.querySelector('[data-box="progress"]')).top;
          const band = bottomTop - topBottom; info.band = Math.round(band);
          const notice = !!document.querySelector('[data-box="voice-notice"]');
          const need = innerWidth < 360 ? 0 : notice ? (window.__scale > 1 ? 64 : 80) : window.__scale > 1 ? 80 : 150;
          if (band < need) out.push(`map band ${band.toFixed(0)}dp < ${need}dp`);
          const puck = document.getElementById("puck");
          if (puck) { const pr = rect(puck), cy = (pr.top + pr.bottom) / 2; if (cy < topBottom || cy > bottomTop) out.push("puck centre is under the UI"); }
        }
      }
      // picker
      if (surface === "picker") {
        const rows = [...document.querySelectorAll("[data-entry]")];
        const whole = rows.filter(fully).length; info.entries = whole;
        // Rule P3: at font scale <= 1.3 one whole entry is visible without scrolling; at 2.0 at least 48 dp of the first.
        if (window.__scale <= 1.3 && whole < 1) out.push("no picker entry fully visible without scrolling");
        if (window.__scale > 1.3 && rows.length && rect(rows[0]).height < 48) out.push("less than 48dp of the first picker entry visible");
        if (window.__scale === 1 && innerWidth >= 360 && innerHeight >= 640 && innerHeight > innerWidth && whole < 3) out.push(`only ${whole} of 3 picker entries visible at font scale 1`);
        const err = document.querySelector('[data-check="entry-error"]');
        if (err && window.__scale <= 1.3 && !fully(err)) out.push("entry error row not fully visible");
      }
      // preview
      if (surface === "preview") {
        const s = document.querySelector('[data-check="start"]');
        if (!s || !fully(s)) out.push("«Эхлэх» not fully visible");
      }
      if (document.documentElement.scrollWidth > vw + 0.5) out.push("horizontal overflow");
      return { out, info };
    });
    const k = `${w}x${h} scale ${scale}`;
    if (r.info.band != null) bandInfo.set(k, Math.min(bandInfo.get(k) ?? 1e9, r.info.band));
    if (r.info.lines != null) lineInfo.set(`${k} ${lang}`, Math.max(lineInfo.get(`${k} ${lang}`) ?? 0, r.info.lines));
    if (r.info.entries != null) pickerInfo.set(k, Math.min(pickerInfo.get(k) ?? 9, r.info.entries));
    // Outside the design target (screen spec › Known limitations 1): 320x568 above font scale 1 (as NAV-005), and guidance
    // below 360x640 or on 360x640 above font scale 1 (the demo row costs 56-70 dp of map band; alternatives v=b, v=c).
    const guid = ["guidance", "worst", "paused", "notice", "reroute", "arrival"].includes(state);
    const target = strict ? !(w < 360 && scale > 1) : !(w < 360 && scale > 1) && !(guid && (w < 360 || (w <= 360 && h <= 640 && scale > 1)));
    for (const p of r.out) (target ? failures : outside).push(`${w}x${h} ${state} ${theme} ${lang} scale=${scale}: ${p}`);
  }
}
await browser.close();
for (const [k, v] of lineInfo) console.log(`INFO  max banner instruction lines ${k}: ${v}`);
for (const [k, v] of bandInfo) console.log(`INFO  min portrait map band (guidance, below the demo row) ${k}: ${v}dp`);
for (const [k, v] of pickerInfo) console.log(`INFO  min picker entries fully visible without scrolling ${k}: ${v} of 3`);
for (const f of outside) console.log(`INFO  outside target: ${f}`);
for (const f of failures) console.log(`FAIL  ${f}`);
console.log(`\n${runs} combinations (font: ${font}, variant ${variant}${strict ? ", NAV-005 target" : ""}), ${failures.length} problem(s), ${outside.length} outside the design target (INFO)`);
process.exit(failures.length ? 1 : 0);

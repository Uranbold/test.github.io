#!/usr/bin/env node
// Layout checker for the NAV-022 offline map wireframe (owner: ux-designer). Checks the DESIGN, not the app.
// Usage: PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav022.mjs [--wide] [--v=a|b|c] [--light]
// Uses the Playwright copy installed for QA under tests/e2e (read-only; nothing is installed or changed there).
// 1 CSS px = 1 dp. For every viewport x state x theme x language x font scale it checks the rules of
// docs/design/screens/android-offline-pack.md › Layout rules F1-F9 (NAV-022 AC 1, 4, 8, 36, 41, 45; NAV-021 AC 27-28;
// NAV-023 AC 21-24):
//   - the attribution strip is inside the viewport and not clipped; nothing intersects it, except under the full-screen
//     S7 / dialog scrim (NAV-012 precedent); the offer scrim ends above it (F4)
//   - no two boxes overlap; no control overlaps a box it is not inside; no two controls overlap
//   - every control is >= 48x48 and its label is not clipped; no wrapping text overflows; no horizontal page overflow
//   - S7: after the scroll to the offline section, its heading and the state's primary control are fully visible (F2)
//   - offer: both buttons fully visible without scrolling (pinned footer) and >= 8 dp apart; heading visible (F3, F4)
//   - dialogs: inside the viewport with >= 16 dp margins, both actions fully visible and >= 8 dp apart (F5)
//   - S1 message: fully visible (F6)
//   - indicator: fully visible, label not clipped, never overlapping the summary, a result name, «Эхлэх»,
//     «Маршрут гаргах», the progress text or the attribution (F7-F9)
//   - preview: «Эхлэх» fully visible; NAV-018 Q3 cap and map band >= 160 / 96 dp on >= 360x640 portrait
//   - search: the field and the first result fully visible with the keyboard open, at font scale <= 1.3
//   - guidance: banner instruction <= 3 lines; map band >= 150 / 80 dp; puck uncovered; chip inside the panel
// 320x568 above font scale 1 is outside the design target (D66, NAV-005 Known limitations 7): reported as INFO.
// --light runs the day theme only (the task allowed a light-only run; the default runs day and night).
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "..", "..", "..", "tests", "e2e", "package.json"));
const { chromium } = require("playwright");

const font = process.argv.includes("--wide") ? "wide" : "narrow";
const variant = (process.argv.find((a) => a.startsWith("--v=")) || "--v=a").slice(4);
const themes = process.argv.includes("--light") ? ["day"] : ["day", "night"];
const url = pathToFileURL(join(here, "NAV-022-offline-pack.html")).href;
const viewports = [[360, 640], [412, 915], [320, 568], [640, 360]];
const states = ["set-none", "set-none-nomanifest", "set-downloading", "set-waiting", "set-verifying", "set-installed", "set-update",
  "set-update-running", "set-failed", "set-storage", "set-update-failed", "offer-first", "offer-14", "dlg-mobile", "dlg-delete",
  "msg-ready", "msg-failed", "msg-storage", "ind-preview", "ind-preview-k2", "ind-results", "ind-noresult", "ind-card", "ind-guidance"];
const scales = [1, 1.3, 2];

const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto(`${url}#toolbar=0`);
let runs = 0;
const failures = [], outside = [], info = new Map();
const addInfo = (k, v, agg = "max") => { const o = info.get(k); info.set(k, o == null ? v : agg === "max" ? Math.max(o, v) : Math.min(o, v)); };

for (const [w, h] of viewports) {
  await page.setViewportSize({ width: w, height: h });
  for (const state of states) for (const theme of themes) for (const lang of ["mn", "en"]) for (const scale of scales) {
    const hash = `state=${state}&theme=${theme}&lang=${lang}&scale=${scale}&font=${font}&v=${variant}&toolbar=0`;
    await page.evaluate((hsh) => { location.hash = hsh; }, hash);
    await page.waitForFunction((hsh) => location.hash.slice(1) === hsh, hash);
    await page.evaluate((sc) => { window.__scale = sc; return document.fonts.ready; }, scale);
    runs++;
    const r = await page.evaluate(() => {
      const out = [], inf = {};
      const root = document.documentElement, surface = root.dataset.surface, state = root.dataset.state;
      const vis = (el) => { const b = el.getBoundingClientRect(); return b.width > 0 && b.height > 0 && !el.closest("[hidden]"); };
      const scrollers = [...document.querySelectorAll("#setscroll, .osheet .scroll, .sheet .scroll, .results, .card .scroll, #top, .dlg")];
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
      const fully = (el) => { if (!el) return false; const a = rect(el), f = el.getBoundingClientRect(); return a.height >= f.height - 0.5 && a.width >= f.width - 0.5 && f.top >= -0.5 && f.bottom <= innerHeight + 0.5 && f.left >= -0.5 && f.right <= innerWidth + 0.5; };
      const inter = (a, b) => Math.min(a.right, b.right) - Math.max(a.left, b.left) > 0.5 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 0.5;
      const gap = (a, b) => Math.max(b.left - a.right, a.left - b.right, b.top - a.bottom, a.top - b.bottom);
      const name = (el) => el.dataset.box || el.getAttribute("aria-label") || (el.textContent || "").trim().slice(0, 24) || el.tagName;
      const boxes = [...document.querySelectorAll("[data-box]")].filter(vis);
      const controls = [...document.querySelectorAll("[data-control]")].filter(vis);
      const vw = innerWidth, vh = innerHeight;
      const fullModal = !!document.querySelector("#modal, #dlgwrap");
      // attribution
      const attr = document.querySelector('[data-box="attribution"]'), ar = rect(attr);
      if (ar.left < 0 || ar.top < 0 || ar.right > vw + 0.5 || ar.bottom > vh + 0.5) out.push("attribution not fully in the viewport");
      if (attr.scrollWidth > attr.clientWidth + 1) out.push("attribution text clipped");
      if (!fullModal) for (const el of [...boxes, ...controls]) if (el !== attr && inter(ar, rect(el))) out.push(`attribution intersects ${name(el)}`);
      // overlaps
      for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
        const a = boxes[i], b = boxes[j];
        if (a.contains(b) || b.contains(a)) continue;
        if (fullModal && (a === attr || b === attr)) continue; // dimmed under the full-screen scrim (NAV-012 precedent)
        if (inter(rect(a), rect(b))) out.push(`${name(a)} overlaps ${name(b)}`);
      }
      for (const c of controls) {
        const cr = rect(c);
        if (cr.width < 0.5 || cr.height < 0.5) continue; // scrolled out of view inside a scroll box
        const full = c.getBoundingClientRect();
        if (full.width < 47.5 || full.height < 47.5) out.push(`control < 48dp: ${name(c)} ${full.width.toFixed(0)}x${full.height.toFixed(0)}`);
        if (c.scrollWidth > c.clientWidth + 1) out.push(`control label clipped: ${name(c)}`);
        for (const b of boxes) if (!(fullModal && b === attr) && !b.contains(c) && !c.contains(b) && inter(cr, rect(b))) out.push(`control ${name(c)} overlaps ${name(b)}`);
      }
      for (let i = 0; i < controls.length; i++) for (let j = i + 1; j < controls.length; j++) {
        if (controls[i].contains(controls[j]) || controls[j].contains(controls[i])) continue;
        if (inter(rect(controls[i]), rect(controls[j]))) out.push(`controls overlap: ${name(controls[i])} / ${name(controls[j])}`);
      }
      for (const el of document.querySelectorAll(".wrap")) if (vis(el) && el.scrollWidth > el.clientWidth + 1) out.push(`text overflows: "${el.textContent.trim().slice(0, 30)}"`);
      if (root.scrollWidth > vw + 0.5) out.push("horizontal overflow");
      const target = innerWidth >= 360 && innerHeight >= 640;
      const port = innerHeight > innerWidth;
      // F2 settings
      if (surface === "settings" && !state.startsWith("dlg")) {
        inf.offTop = Number(root.dataset.offTop);
        const head = document.querySelector('[data-check="off-heading"]'), prim = document.querySelector("[data-primary]");
        const soft = window.__scale > 1.3 || !target; // at 200 % or on small/landscape windows the user scrolls on: INFO
        const msgs = [];
        if (!fully(head)) msgs.push("offline section heading not visible after the scroll to the section");
        if (prim && !fully(prim)) msgs.push("primary control of the offline section not visible after the scroll to the section");
        if (msgs.length) (soft ? (inf.soft = inf.soft || []) : out).push(...msgs);
        const st = document.querySelector('[data-box="status"]');
        if (st && !fully(st) && !soft) out.push("status row not fully visible");
      }
      // F3/F4 offer
      if (surface === "offer") {
        const prim = document.querySelector("[data-primary]"), dec = document.querySelector('[data-check="decline"]');
        if (!fully(prim) || !fully(dec)) out.push("offer buttons not fully visible without scrolling");
        if (prim && dec && gap(prim.getBoundingClientRect(), dec.getBoundingClientRect()) < 7.5) out.push("offer buttons < 8dp apart");
        const head = document.querySelector('[data-check="offer-heading"]');
        if (window.__scale <= 1.3 && target && !fully(head)) out.push("offer heading not visible");
        const scrim = document.getElementById("ovl").getBoundingClientRect();
        if (scrim.bottom > ar.top + 0.5) out.push("offer scrim covers the attribution");
        const pb = prim.getBoundingClientRect();
        inf.primY = Math.round(((pb.top + pb.bottom) / 2 / innerHeight) * 100);
        const sc = document.querySelector(".osheet .scroll"); inf.offerScroll = sc.scrollHeight > sc.clientHeight + 1 ? 1 : 0;
      }
      // F5 dialogs
      if (state.startsWith("dlg")) {
        const d = document.querySelector('[data-box="dialog"]').getBoundingClientRect();
        if (d.left < 15.5 || d.right > vw - 15.5 || d.top < 15.5 || d.bottom > vh - 15.5) out.push("dialog closer than 16dp to the screen edge");
        const acts = [...document.querySelectorAll(".dlg [data-control]")];
        for (const a of acts) if (!fully(a)) out.push(`dialog action not visible: ${name(a)}`);
        if (acts.length === 2 && gap(acts[0].getBoundingClientRect(), acts[1].getBoundingClientRect()) < 7.5) out.push("dialog actions < 8dp apart");
      }
      // F6 message
      if (surface === "s1") { const m = document.querySelector('[data-box="message"]'); if (!fully(m)) (port ? out : (inf.soft = inf.soft || [])).push("S1 message not fully visible (it scrolls inside the top group, NAV-005 D5)"); }
      // F7-F9 indicator
      const chip = document.querySelector('[data-box="chip"]');
      if (state.startsWith("ind-")) {
        // The indicator sits next to its anchor (summary text, «Ойролцоох газар», the results list top, the remaining
        // time): it must be visible whenever the anchor is; in a short side column both may scroll away together.
        const anchor = document.querySelector('[data-box="sum-text"], [data-box="near-label"], [data-box="remaining"], [data-box="empty-text"], [data-box="name0"]');
        const anchorShown = anchor && fully(anchor);
        if (!chip) out.push("offline indicator missing");
        else if (!fully(chip) && (port && target)) out.push("offline indicator not fully visible");
        else if (!fully(chip) && anchorShown) (inf.soft = inf.soft || []).push("offline indicator cut by its scrolling side column while its anchor shows");
        else if (fully(chip)) {
          if (chip.scrollWidth > chip.clientWidth + 1) out.push("indicator label clipped");
          inf.chipH = Math.round(chip.getBoundingClientRect().height);
        }
      }
      if (surface === "preview") {
        const s = document.querySelector('[data-check="start"]'); if (!fully(s)) out.push("«Эхлэх» not fully visible");
        const sum = document.querySelector('[data-check="summary"]'); if (port && !fully(sum)) out.push("summary region not fully visible");
        const sheet = document.querySelector('[data-box="sheet"]').getBoundingClientRect();
        inf.sheetH = Math.round(sheet.height);
        if (port && target) {
          const app = document.getElementById("app").getBoundingClientRect(), attrH = attr.getBoundingClientRect().height;
          const area = app.height - attrH, band = sheet.top - app.top, need = window.__scale > 1 ? 96 : 160;
          inf.band = Math.round(band);
          if (band < need) out.push(`map band above the collapsed sheet ${band.toFixed(0)}dp < ${need}dp`);
          if (sheet.height > Math.min(area * 0.75, area - need - 8) + 1) out.push(`collapsed sheet ${sheet.height.toFixed(0)}dp above the Q3 cap`);
        }
      }
      if (surface === "search") {
        const f = document.querySelector('[data-box="search"]'); if (!fully(f)) out.push("search field not fully visible");
        const first = document.querySelector('[data-check="first-result"]');
        if (first && window.__scale <= 1.3 && !fully(first)) out.push("first result not fully visible with the keyboard open");
      }
      if (surface === "card") { const d = document.querySelector('[data-check="directions"]'); if (!fully(d)) out.push("«Маршрут гаргах» not fully visible"); }
      if (surface === "guidance") {
        const instr = document.querySelector('[data-check="instruction"]');
        const lines = Math.round(instr.getBoundingClientRect().height / parseFloat(getComputedStyle(instr).lineHeight));
        if (lines > 3) out.push(`banner instruction has ${lines} lines (> 3)`);
        const panel = document.querySelector('[data-box="progress"]');
        if (chip && !panel.contains(chip)) out.push("indicator outside the progress panel");
        inf.panelH = Math.round(panel.getBoundingClientRect().height);
        if (port) {
          const band = panel.getBoundingClientRect().top - rect(document.getElementById("top")).bottom;
          inf.band = Math.round(band);
          const need = innerWidth < 360 ? 0 : window.__scale > 1 ? 80 : 150;
          if (band < need) out.push(`guidance map band ${band.toFixed(0)}dp < ${need}dp`);
          const pk = document.getElementById("puck").getBoundingClientRect(), cy = (pk.top + pk.bottom) / 2;
          if (cy > panel.getBoundingClientRect().top || cy < rect(document.getElementById("top")).bottom) out.push("puck centre is under the UI");
        }
      }
      return { out, inf };
    });
    const k = `${w}x${h} scale ${scale}`;
    const inTarget = !(w < 360 && scale > 1);
    for (const p of r.out) (inTarget ? failures : outside).push(`${w}x${h} ${state} ${theme} ${lang} scale=${scale}: ${p}`);
    for (const p of r.inf.soft || []) outside.push(`${w}x${h} ${state} ${theme} ${lang} scale=${scale}: ${p} (user scrolls on)`);
    if (theme !== "day") continue;
    if (r.inf.offTop != null && state === "set-none") addInfo(`S7: offline section starts this far down the sheet content (scroll needed when opened from the gear) ${k} ${lang}`, r.inf.offTop);
    if (r.inf.primY != null) addInfo(`offer: primary button centre at % of screen height (higher = nearer the thumb) ${state} ${k} ${lang}`, r.inf.primY, "min");
    if (r.inf.offerScroll != null) addInfo(`offer: content scrolls (1 = yes) ${state} ${k} ${lang}`, r.inf.offerScroll);
    if (r.inf.sheetH != null) addInfo(`preview: collapsed sheet height ${state} ${k} ${lang}`, r.inf.sheetH);
    if (r.inf.band != null) addInfo(`map band ${state} ${k} ${lang}`, r.inf.band, "min");
    if (r.inf.chipH != null) addInfo(`indicator height ${k} ${lang}`, r.inf.chipH);
    if (r.inf.panelH != null) addInfo(`guidance: progress panel height ${k} ${lang}`, r.inf.panelH);
  }
}
await browser.close();
for (const [k, v] of info) console.log(`INFO  ${k}: ${v}`);
for (const f of outside) console.log(`INFO  outside target: ${f}`);
for (const f of failures) console.log(`FAIL  ${f}`);
console.log(`\n${runs} combinations (font: ${font}, variant ${variant}, themes: ${themes.join("+")}), ${failures.length} problem(s), ${outside.length} outside the design target (INFO)`);
process.exit(failures.length ? 1 : 0);

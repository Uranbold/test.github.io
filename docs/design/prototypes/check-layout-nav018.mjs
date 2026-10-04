#!/usr/bin/env node
// Layout checker for the NAV-018 Android wireframe (owner: ux-designer). Checks the DESIGN, not the app.
// Usage: PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav018.mjs [--wide]
// Uses the Playwright copy installed for QA under tests/e2e (read-only; nothing is installed or changed there).
// 1 CSS px = 1 dp. For every viewport x state x theme x language x font scale it checks the screen-spec rules
// (docs/design/screens/NAV-018-android-origin-turn-list.md › Layout rules Q1–Q8; story AC 6, 11, 15, 18, 22, 27, 28, 30, 34):
//   - the attribution strip is fully inside the viewport and intersects no other box or control (NAV-005 AC 2, AC 27)
//   - no two boxes overlap; no control overlaps a box it is not inside; no two controls overlap
//   - every control is >= 48x48 (AC 22, 28); the lock card's two actions are >= 8dp apart
//   - no wrapping text overflows horizontally (no word cut, AC 28); control labels are not clipped; only names ellipsise
//   - preview, portrait, collapsed: summary region, O1 (when shown) and «Эхлэх» fully visible at every scale (AC 15, 27);
//     at font scale 1 on >= 360x640 the top part (points block + tabs) does not scroll, so both field texts are visible
//     (AC 27) — except the chosen-start + battery-row state, where only the points block must stay fully visible (Q3)
//   - collapsed cap: height <= min(75 % of the area above R5, area - band - 8) unless the fixed parts alone need more (Q3)
//   - map band above the collapsed sheet >= 160dp at font scale 1 and >= 96dp above it, on >= 360x640 (AC 27)
//   - expanded sheet <= 80 % of the app height; summary visible; >= 48dp of the lower part visible (NAV-011 P3)
//   - «Эхлэх» pinned at the bottom of the sheet in every portrait state (NAV-011 P1)
//   - the turn list «Маршрутын заавар» is shown only in expanded/wide route states, never in the collapsed sheet (AC 18)
//   - point editor: the field is fully visible; the «Миний байршил» row is fully visible in every start-editor state,
//     also with the lock card and without the keyboard (AC 3, 34)
//   - coordinate card: «Эхлэх цэг болгох» and «Очих газар болгох» do not move between the reverse states (AC 6, Q6)
//   - no horizontal page overflow
// 320x568 above font scale 1 is outside the design target (D66, NAV-005 Known limitations 7): reported as INFO.
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "..", "..", "..", "tests", "e2e", "package.json"));
const { chromium } = require("playwright");

const font = process.argv.includes("--wide") ? "wide" : "narrow";
const url = pathToFileURL(join(here, "NAV-018-android-points.html")).href;
const viewports = [[360, 640], [412, 915], [320, 568], [640, 360]];
const preview = ["pv-device", "pv-device-exp", "pv-device-b2", "pv-chosen", "pv-chosen-exp", "pv-chosen-b2", "pv-chosen-b2-exp",
  "pv-nostart", "pv-loading", "pv-same", "pv-outside", "pv-ratelimited", "pv-list"];
const editor = ["ed-start", "ed-start-results", "ed-dest-results", "ed-start-offline", "ed-start-lock", "ed-dest-lock"];
const cardStates = ["cd-loading", "cd-place", "cd-empty", "cd-offline", "cd-unavailable", "cd-ratelimited", "cd-error"];
const states = [...preview, ...editor, ...cardStates];
const scales = [1, 1.3, 2];

const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto(`${url}#toolbar=0`);
let runs = 0;
const failures = [], outside = [];
const bandInfo = new Map(), goTop = new Map(), sheetInfo = new Map();

for (const [w, h] of viewports) {
  await page.setViewportSize({ width: w, height: h });
  for (const state of states) for (const theme of ["day", "night"]) for (const lang of ["mn", "en"]) for (const scale of scales) {
    const hash = `state=${state}&theme=${theme}&lang=${lang}&scale=${scale}&font=${font}&toolbar=0`;
    await page.evaluate((hsh) => { location.hash = hsh; }, hash);
    await page.waitForFunction((hsh) => location.hash.slice(1) === hsh, hash);
    await page.evaluate((sc) => { window.__scale = sc; return document.fonts.ready; }, scale);
    runs++;
    const r = await page.evaluate((state) => {
      const out = [], info = {};
      const vis = (el) => { const b = el.getBoundingClientRect(); return b.width > 0 && b.height > 0 && !el.closest("[hidden]"); };
      const scrollers = [...document.querySelectorAll("#ebody, .card .scroll, #body, #sideScroll, #topscroll")];
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
      const fully = (el) => { const a = rect(el), f = el.getBoundingClientRect(); return a.height >= f.height - 0.5 && a.width >= f.width - 0.5 && f.top >= -0.5 && f.bottom <= innerHeight + 0.5; };
      const inter = (a, b) => Math.min(a.right, b.right) - Math.max(a.left, b.left) > 0.5 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 0.5;
      const name = (el) => el.dataset.box || el.getAttribute("aria-label") || (el.textContent || "").trim().slice(0, 24) || el.tagName;
      const boxes = [...document.querySelectorAll("[data-box]")].filter(vis);
      const controls = [...document.querySelectorAll("[data-control]")].filter(vis);
      const vw = innerWidth, vh = innerHeight;
      const attr = document.querySelector('[data-box="attribution"]');
      const ar = rect(attr);
      if (ar.left < 0 || ar.top < 0 || ar.right > vw + 0.5 || ar.bottom > vh + 0.5) out.push("attribution not fully in the viewport");
      if (attr.scrollWidth > attr.clientWidth + 1) out.push("attribution text clipped");
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
        if (c.scrollWidth > c.clientWidth + 1) out.push(`control label clipped: ${name(c)}`);
        for (const b of boxes) if (!b.contains(c) && !c.contains(b) && inter(cr, rect(b))) out.push(`control ${name(c)} overlaps ${name(b)}`);
      }
      for (let i = 0; i < controls.length; i++) for (let j = i + 1; j < controls.length; j++) {
        if (controls[i].contains(controls[j]) || controls[j].contains(controls[i])) continue;
        if (inter(rect(controls[i]), rect(controls[j]))) out.push(`controls overlap: ${name(controls[i])} / ${name(controls[j])}`);
      }
      const la = [...document.querySelectorAll("[data-lockact]")].map((e) => e.getBoundingClientRect());
      if (la.length === 2) {
        const gap = Math.max(la[1].left - la[0].right, la[0].left - la[1].right, la[1].top - la[0].bottom, la[0].top - la[1].bottom);
        if (gap < 7.5) out.push(`lock card actions only ${gap.toFixed(0)}dp apart`);
      }
      for (const el of document.querySelectorAll(".wrap")) if (vis(el) && el.scrollWidth > el.clientWidth + 1) out.push(`text overflows: "${el.textContent.trim().slice(0, 30)}"`);

      const target360 = innerWidth >= 360 && innerHeight >= 640;
      const sheet = document.querySelector('[data-box="sheet"]');
      const port = document.documentElement.dataset.wide === "0";
      if (sheet) {
        const app = document.getElementById("app").getBoundingClientRect();
        const start = document.querySelector("[data-start]");
        if (!fully(start)) out.push("«Эхлэх» not fully visible");
        const o1 = document.querySelector("[data-o1]");
        if (o1 && !fully(o1)) out.push("O1 not fully visible");
        const sum = document.querySelector("[data-box-sum]");
        const expanded = sheet.dataset.expanded === "1";
        const steps = document.querySelector("[data-steps]");
        const route = /^pv-(device|chosen|list)/.test(state);
        if (steps && vis(steps) && port && !expanded) out.push("turn list shown in the collapsed sheet");
        if (route && (expanded || !port) && !steps) out.push("turn list missing in a route state with the sheet expanded");
        if (!route && steps) out.push("turn list shown without a route");
        if (port && !expanded) {
          const q = sum.getBoundingClientRect(), s = sheet.getBoundingClientRect();
          if (q.top < s.top - 0.5 || q.bottom > s.bottom + 0.5 || q.bottom > vh) out.push("summary region not fully visible in the collapsed sheet");
          const ts = document.getElementById("topscroll");
          if (window.__scale === 1 && target360) {
            // Q3: the points block (both field texts) is always fully visible at 100 %; the whole top part (points + tabs)
            // does not scroll in route states (AC 27), except with both hints (chosen start + battery row, AC 30)
            const pts = document.querySelector("[data-box-points]");
            if (!fully(pts)) out.push("points block not fully visible at font scale 1");
            if (ts.scrollHeight > ts.clientHeight + 1) out.push(`${route && state !== "pv-chosen-b2" ? "" : "INFO-ONLY "}top part (points + tabs) scrolls at font scale 1`);
          }
          const wrapH = document.getElementById("previewwrap").clientHeight;
          const bandMin = window.__scale > 1 ? 96 : 160;
          const cap = target360 ? Math.min(wrapH * 0.75, wrapH - bandMin - 8) : wrapH * 0.6;
          if (sheet.getBoundingClientRect().height > cap + 1 && ts.clientHeight > 0.5) out.push("collapsed sheet taller than its cap with the top part still shown");
          const band = sheet.getBoundingClientRect().top - app.top;
          info.band = Math.round(band);
          const need = !target360 ? 0 : window.__scale > 1 ? 96 : 160;
          // AC 27 / NAV-011 P3: the band is for fitting the route (route states); states without a route report INFO
          if (band < need - 0.5) out.push(`${route ? "" : "INFO-ONLY "}map band above the collapsed sheet ${band.toFixed(0)}dp < ${need}dp`);
        }
        if (port && expanded) {
          if (sheet.getBoundingClientRect().height > app.height * 0.8 + 1) out.push("expanded sheet taller than 80 % of the app");
          const body = document.getElementById("body"), bb = body.getBoundingClientRect();
          if (state !== "pv-list") {
            const q = sum.getBoundingClientRect();
            if (window.__scale <= 1.3 && (q.top < bb.top - 0.5 || q.bottom > bb.bottom + 0.5)) out.push("summary not fully visible in the expanded sheet");
            const more = document.getElementById("more");
            if (window.__scale <= 1.3 && target360 && bb.bottom - more.getBoundingClientRect().top < 47.5 && body.scrollHeight > body.clientHeight + 1) out.push(`${window.__scale > 1 && document.querySelector("[data-o1]") ? "INFO-ONLY " : ""}expanded sheet: lower part < 48dp visible`);
          } else {
            // scrolled to the list: the heading and the activated row are visible
            const act = document.querySelector(".step.act");
            if (!fully(act)) out.push("activated turn row not visible after scrolling to the list");
          }
        }
        if (port && Math.abs(sheet.getBoundingClientRect().bottom - start.closest(".start").getBoundingClientRect().bottom) > 1) out.push("«Эхлэх» is not pinned at the sheet bottom");
        info.sheetH = Math.round(sheet.getBoundingClientRect().height);
      }
      const ef = document.querySelector('[data-box="efield"]');
      if (ef) {
        if (!fully(ef)) out.push("editor field not fully visible");
        const me = document.querySelector("[data-me]");
        if (state.startsWith("ed-start") && (!me || !fully(me))) out.push("«Миний байршил» row not fully visible in the start editor");
        if (!state.startsWith("ed-start") && me) out.push("«Миний байршил» row in the destination editor");
        const eb = document.getElementById("ebody");
        if (eb.firstElementChild && rect(eb).height < 55.5) out.push(`editor body shows < 56dp (${rect(eb).height.toFixed(0)})`);
      }
      const go = [...document.querySelectorAll("[data-go]")];
      if (go.length) info.goTop = go.map((g) => Math.round(g.getBoundingClientRect().top)).join("/");
      if (document.documentElement.scrollWidth > vw + 0.5) out.push("horizontal overflow");
      return { out, info };
    }, state);
    // Landscape with the keyboard open: Android shows its full-screen (extract) keyboard for a single-line field, so the
    // app UI under it is not visible while typing; the list is checked in the locked states (no keyboard) instead.
    const imeLand = w > h && state.startsWith("ed-") && !state.endsWith("-lock");
    const target = !(w < 360 && scale > 1) && !imeLand;
    for (const p of r.out) (target && !p.startsWith("INFO-ONLY ") ? failures : outside).push(`${w}x${h} ${state} ${theme} ${lang} scale=${scale}: ${p.replace("INFO-ONLY ", "")}`);
    if (r.info.band != null) { const k = `${w}x${h} scale ${scale}`; const cur = bandInfo.get(k); if (!cur || r.info.band < cur.v) bandInfo.set(k, { v: r.info.band, s: state }); }
    if (r.info.sheetH != null && ["pv-device", "pv-chosen", "pv-device-b2"].includes(state)) sheetInfo.set(`${w}x${h} ${state} scale ${scale} ${lang}`, r.info.sheetH);
    if (r.info.goTop != null) {
      const k = `${w}x${h} ${theme} ${lang} scale=${scale}`;
      const g = goTop.get(k) ?? new Map(); g.set(state, r.info.goTop); goTop.set(k, g);
    }
  }
}
await browser.close();
for (const [k, g] of goTop) {
  if (new Set(g.values()).size > 1) {
    const msg = `${k}: card buttons move between reverse states (${[...g].map(([s, v]) => `${s}=${v}`).join(", ")})`;
    (/^320x/.test(k) && !/scale=1$/.test(k) ? outside : failures).push(msg);
  }
}
for (const [k, v] of bandInfo) console.log(`INFO  min map band above the collapsed sheet ${k}: ${v.v}dp (${v.s})`);
for (const [k, v] of sheetInfo) if (/^360x640|^412x915/.test(k) && / mn$/.test(k)) console.log(`INFO  collapsed sheet height ${k}: ${v}dp`);
for (const f of outside) console.log(`INFO  outside target: ${f}`);
for (const f of failures) console.log(`FAIL  ${f}`);
console.log(`\n${runs} combinations (font: ${font}), ${failures.length} problem(s), ${outside.length} outside the design target (INFO)`);
process.exit(failures.length ? 1 : 0);

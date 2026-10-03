#!/usr/bin/env node
// Layout checker for the NAV-011 Android wireframe (owner: ux-designer). Checks the DESIGN, not the app.
// Usage: PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav011.mjs [--wide]
// Uses the Playwright copy installed for QA under tests/e2e (read-only; nothing is installed or changed there).
// 1 CSS px = 1 dp. For every viewport x state x theme x language x font scale it checks the screen-spec rules
// (docs/design/screens/NAV-011-android-route-preview-search-parity.md › Layout rules; story AC 2, 8, 15–18, 31, 44):
//   - the attribution strip is fully inside the viewport and intersects no other box or control (NAV-005 AC 2, AC 44)
//   - no two boxes (search bar, lock card, results, coordinate card, preview sheet) overlap; no control overlaps a box it
//     is not inside; no two controls overlap
//   - every control is >= 48x48 (AC 17, 18, 44); the lock card's two actions are >= 8dp apart (bumped finger)
//   - no wrapping text overflows horizontally (a word cut or pushed out: AC 44 "no text cut mid-word at 200 %");
//     control labels are not clipped; only names (.name) may ellipsise
//   - collapsed preview sheet, portrait: summary and «Эхлэх» fully visible at every font scale, nothing scrolls at font
//     scale 1 on >= 360x640 (AC 18), and the collapsed sheet is <= 60 % of the app height
//   - «Эхлэх» is fully visible in every preview state (all scales), never scrolled away (AC 18)
//   - the expanded sheet is <= 80 % of the app height (NAV-005 Layout rule 6)
//   - route band above the collapsed sheet (portrait): >= 160dp at font scale 1 and >= 96dp above it, on >= 360x640
//     (AC 16: room to fit the routes with 40dp padding); INFO for all
//   - coordinate card: «Маршрут гаргах» does not move between the reverse states (loading → place/empty/error) (AC 8)
//   - no horizontal page overflow
// 320x568 above font scale 1 is outside the design target (D66, NAV-005 Known limitations 7): reported as INFO.
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "..", "..", "..", "tests", "e2e", "package.json"));
const { chromium } = require("playwright");

const font = process.argv.includes("--wide") ? "wide" : "narrow";
const url = pathToFileURL(join(here, "NAV-011-android-preview.html")).href;
const viewports = [[360, 640], [412, 915], [320, 568], [640, 360]];
const cardStates = ["card-loading", "card-place", "card-empty", "card-offline", "card-unavailable", "card-ratelimited", "card-error"];
const states = [...cardStates, "lock-tap", "lock-results",
  "preview-3", "preview-1", "preview-expanded", "preview-bike", "preview-snap", "preview-toofar", "preview-unavailable", "preview-noroute-avoid"];
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
    const r = await page.evaluate(() => {
      const out = [], info = {};
      const vis = (el) => { const b = el.getBoundingClientRect(); return b.width > 0 && b.height > 0 && !el.closest("[hidden]"); };
      const scrollers = [...document.querySelectorAll("#topgroup, .card .scroll, #body, #sideScroll, #topscroll")];
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
      info.scrolls = scrollers.filter((sc) => sc.scrollHeight > sc.clientHeight + 1).map((sc) => sc.id || sc.className);
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
      // lock card actions: >= 8dp apart
      const la = [...document.querySelectorAll("[data-lockact]")].map((e) => e.getBoundingClientRect());
      if (la.length === 2) {
        const gapX = Math.max(la[1].left - la[0].right, la[0].left - la[1].right);
        const gapY = Math.max(la[1].top - la[0].bottom, la[0].top - la[1].bottom);
        if (Math.max(gapX, gapY) < 7.5) out.push(`lock card actions only ${Math.max(gapX, gapY).toFixed(0)}dp apart`);
      }
      // wrapping text never overflows (words are never broken: overflow = a word does not fit)
      for (const el of document.querySelectorAll(".wrap")) if (vis(el) && el.scrollWidth > el.clientWidth + 1) out.push(`text overflows: "${el.textContent.trim().slice(0, 30)}"`);
      // preview sheet rules
      const sheet = document.querySelector('[data-box="sheet"]');
      const port = document.documentElement.dataset.wide === "0";
      if (sheet) {
        const app = document.getElementById("app").getBoundingClientRect();
        const start = document.querySelector("[data-start]");
        const sr = rect(start), sf = start.getBoundingClientRect();
        if (sr.height < sf.height - 0.5 || sf.bottom > vh + 0.5 || sf.top < 0) out.push("«Эхлэх» not fully visible");
        const sum = document.querySelector("[data-box-sum]");
        const expanded = sheet.dataset.expanded === "1";
        if (port && !expanded) {
          // summary region and «Эхлэх» never scroll (Layout rule P2); at font scale 1 on >= 360x640 nothing in the
          // collapsed sheet scrolls at all (AC 18: visible without dragging)
          const q = sum.getBoundingClientRect(), s = sheet.getBoundingClientRect();
          if (q.top < s.top - 0.5 || q.bottom > s.bottom + 0.5 || q.bottom > vh) out.push("summary not fully visible in the collapsed sheet");
          const ts = document.getElementById("topscroll");
          if (window.__scale === 1 && innerWidth >= 360 && innerHeight >= 640 && ts.scrollHeight > ts.clientHeight + 1) out.push("collapsed sheet scrolls at font scale 1");
          // <= 60 % of the area above R5, unless the summary and «Эхлэх» alone need more (header and tabs then scroll out)
          const wrapH = document.getElementById("previewwrap").clientHeight;
          if (sheet.getBoundingClientRect().height > wrapH * 0.6 + 1 && ts.clientHeight > 0.5) out.push("collapsed sheet taller than 60 % with header/tabs still shown");
        }
        if (port && expanded && sheet.getBoundingClientRect().height > app.height * 0.8 + 1) out.push("expanded sheet taller than 80 % of the app");
        if (port && expanded) {
          // expanded: one body scroll above the pinned «Эхлэх»; with the body scrolled to the top, the summary is fully
          // visible and at least 48dp of the lower section shows (so the options are discoverable)
          const body = document.getElementById("body"), bb = body.getBoundingClientRect();
          const q = sum.getBoundingClientRect();
          if (window.__scale <= 1.3 && (q.top < bb.top - 0.5 || q.bottom > bb.bottom + 0.5)) out.push("summary not fully visible in the expanded sheet");
          const more = document.getElementById("more");
          if (window.__scale <= 1.3 && more && bb.bottom - more.getBoundingClientRect().top < 47.5 && body.scrollHeight > body.clientHeight + 1) out.push("expanded sheet: lower section < 48dp visible");
        }
        // «Эхлэх» is pinned at the bottom of the sheet in every portrait state (it does not move when the sheet expands)
        if (port && Math.abs(sheet.getBoundingClientRect().bottom - start.closest(".start").getBoundingClientRect().bottom) > 1) out.push("«Эхлэх» is not pinned at the sheet bottom");
        if (port && !expanded) {
          const band = sheet.getBoundingClientRect().top - app.top;
          info.band = Math.round(band);
          const need = innerWidth < 360 ? 0 : window.__scale > 1 ? 96 : 160;
          if (band < need) out.push(`route band above the collapsed sheet ${band.toFixed(0)}dp < ${need}dp`);
        }
        info.sheetH = Math.round(sheet.getBoundingClientRect().height);
      }
      const go = document.querySelector("[data-go]");
      if (go) info.goTop = Math.round(go.getBoundingClientRect().top);
      if (document.documentElement.scrollWidth > vw + 0.5) out.push("horizontal overflow");
      return { out, info };
    });
    const target = !(w < 360 && scale > 1);
    for (const p of r.out) (target ? failures : outside).push(`${w}x${h} ${state} ${theme} ${lang} scale=${scale}: ${p}`);
    if (r.info.band != null) { const k = `${w}x${h} scale ${scale}`; bandInfo.set(k, Math.min(bandInfo.get(k) ?? 1e9, r.info.band)); }
    if (r.info.sheetH != null && state === "preview-3") sheetInfo.set(`${w}x${h} scale ${scale} ${lang}`, r.info.sheetH);
    if (r.info.goTop != null && cardStates.includes(state)) {
      const k = `${w}x${h} ${theme} ${lang} scale=${scale}`;
      const g = goTop.get(k) ?? new Map(); g.set(state, r.info.goTop); goTop.set(k, g);
    }
  }
}
await browser.close();
for (const [k, g] of goTop) {
  const vals = [...g.values()];
  if (Math.max(...vals) - Math.min(...vals) > 1) {
    const msg = `${k}: «Маршрут гаргах» moves between reverse states (${[...g].map(([s, v]) => `${s}=${v}`).join(", ")})`;
    (/^320x/.test(k) && !/scale=1$/.test(k) ? outside : failures).push(msg);
  }
}
for (const [k, v] of bandInfo) console.log(`INFO  min route band above the collapsed sheet ${k}: ${v}dp`);
for (const [k, v] of sheetInfo) console.log(`INFO  collapsed sheet height (preview-3) ${k}: ${v}dp`);
for (const f of outside) console.log(`INFO  outside target: ${f}`);
for (const f of failures) console.log(`FAIL  ${f}`);
console.log(`\n${runs} combinations (font: ${font}), ${failures.length} problem(s), ${outside.length} outside the design target (INFO)`);
process.exit(failures.length ? 1 : 0);

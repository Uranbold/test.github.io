#!/usr/bin/env node
// Layout checker for the NAV-012 Android wireframe (owner: ux-designer). Checks the DESIGN, not the app.
// Usage: PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav012.mjs [--wide]
// Uses the Playwright copy installed for QA under tests/e2e (read-only; nothing is installed or changed there).
// 1 CSS px = 1 dp. For every viewport x state x theme x language x font scale it checks the NAV-012 screen-spec rules
// (docs/design/screens/NAV-012-android-background-lock-screen.md › Layout rules; story AC 1, 8, 10, 18, 26, 28):
//   guidance states (lock screen, restoring, restored, arrival over the lock screen) — the NAV-005 rules:
//     attribution fully visible and intersecting nothing; no overlaps; controls >= 48 dp; instruction <= 3 lines;
//     map band >= 150 dp at font scale 1 and >= 80 dp above (360x640 and larger); puck (when drawn) inside the band
//   route preview with the battery hint (AC 26), modelled on the NAV-011 sheet (P2/P3: collapsed <= 60 %, expanded
//     <= 80 % of the area above R5): collapsed — «Эхлэх», the summary and the one-line entry row fully visible at every
//     scale, the map above the sheet >= 160 dp at font scale 1 and >= 96 dp above, header and tabs fixed at font
//     scale 1; expanded — the full hint (B2 + actions) visible at font scale 1 (portrait 360x640 and larger)
//   settings sheet (AC 28): controls >= 48 dp, no overlaps, the battery row and its text not clipped
//   notification cards (AC 1–2, 22): the distance title is never clipped; action buttons >= 48 dp and not clipped;
//     how much of the instruction fits in the collapsed card is reported (INFO); other titles that ellipsise: INFO
//   all: no horizontal overflow
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "..", "..", "..", "tests", "e2e", "package.json"));
const { chromium } = require("playwright");

const font = process.argv.includes("--wide") ? "wide" : "narrow";
const url = pathToFileURL(join(here, "NAV-012-background.html")).href;
const viewports = [[360, 640], [412, 915], [320, 568], [640, 360]];
const states = ["lock-guidance", "restoring", "restored", "restored-worst", "restoring-gps-lost", "lock-arrival",
  "preview-battery", "preview-battery-expanded", "settings-restricted", "settings-exempt",
  "notif-collapsed", "notif-expanded", "notif-muted", "notif-reroute", "notif-gps-lost", "notif-arrival", "notif-interrupted", "notif-public"];
const scales = [1, 1.3, 2];

const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto(`${url}#toolbar=0`);
let runs = 0;
const failures = [], outside = [], infos = new Map();
const addInfo = (k, v) => { if (!infos.has(k)) infos.set(k, v); };

for (const [w, h] of viewports) {
  await page.setViewportSize({ width: w, height: h });
  for (const state of states) for (const theme of ["day", "night"]) for (const lang of ["mn", "en"]) for (const scale of scales) {
    const hash = `state=${state}&theme=${theme}&lang=${lang}&scale=${scale}&font=${font}&toolbar=0`;
    await page.evaluate((hsh) => { location.hash = hsh; }, hash);
    await page.waitForFunction((hsh) => location.hash.slice(1) === hsh, hash);
    await page.evaluate((sc) => { window.__scale = sc; return document.fonts.ready; }, scale);
    await page.evaluate(() => render()); // re-run the layout rules with the loaded font metrics
    runs++;
    const r = await page.evaluate(() => {
      const out = [], info = {};
      const kind = document.documentElement.dataset.kind;
      const vis = (el) => { const b = el.getBoundingClientRect(); return b.width > 0 && b.height > 0 && !el.closest("[hidden]"); };
      const scrollers = [document.getElementById("top"), document.querySelector("#sheet .toppart"), document.querySelector("#sheet .scrollall"), document.getElementById("sheet"), document.querySelector("#set .scroll"), document.getElementById("shade")].filter(Boolean);
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
      const fullyIn = (el, sc) => { const a = el.getBoundingClientRect(), b = sc.getBoundingClientRect(); return a.top >= b.top - 0.5 && a.bottom <= b.bottom + 0.5; };
      const inter = (a, b) => Math.min(a.right, b.right) - Math.max(a.left, b.left) > 0.5 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 0.5;
      const name = (el) => el.dataset.box || el.getAttribute("aria-label") || (el.textContent || "").trim().slice(0, 20) || el.tagName;
      const modal = document.getElementById("modal") || document.getElementById("shade");
      const boxes = [...document.querySelectorAll("[data-box]")].filter(vis).filter((el) => !modal || modal.contains(el) || el.dataset.box === "attribution");
      const controls = [...document.querySelectorAll("[data-control]")].filter(vis).filter((el) => !modal || modal.contains(el));
      const vw = innerWidth, vh = innerHeight;
      // attribution on every map screen (the settings scrim may dim it; the notification shade is not a map screen)
      const attr = document.querySelector('[data-box="attribution"]');
      if (kind === "guidance" || kind === "preview") {
        const ar = rect(attr);
        if (ar.left < 0 || ar.top < 0 || ar.right > vw + 0.5 || ar.bottom > vh + 0.5) out.push("attribution not fully in the viewport");
        if (attr.scrollWidth > attr.clientWidth + 1) out.push("attribution text clipped");
        for (const el of [...boxes, ...controls]) if (el !== attr && inter(ar, rect(el))) out.push(`attribution intersects ${name(el)}`);
      }
      const bx = boxes.filter((el) => el !== attr);
      for (let i = 0; i < bx.length; i++) for (let j = i + 1; j < bx.length; j++) {
        const a = bx[i], b = bx[j];
        if (a.contains(b) || b.contains(a)) continue;
        if (inter(rect(a), rect(b))) out.push(`${name(a)} overlaps ${name(b)}`);
      }
      for (const c of controls) {
        const cr = rect(c);
        if (cr.width < 0.5 || cr.height < 0.5) continue; // scrolled out of view
        const full = c.getBoundingClientRect();
        if (full.width < 47.5 || full.height < 47.5) out.push(`control < 48dp: ${name(c)} ${full.width.toFixed(0)}x${full.height.toFixed(0)}`);
        if (c.scrollWidth > c.clientWidth + 1 || c.scrollHeight > c.clientHeight + 1) out.push(`control label clipped: ${name(c)}`);
        for (const b of bx) if (!b.contains(c) && inter(cr, rect(b)) && b.dataset.box !== "recenter") out.push(`control ${name(c)} overlaps ${name(b)}`);
      }
      for (let i = 0; i < controls.length; i++) for (let j = i + 1; j < controls.length; j++)
        if (inter(rect(controls[i]), rect(controls[j]))) out.push(`controls overlap: ${name(controls[i])} / ${name(controls[j])}`);

      if (kind === "guidance") {
        const instr = document.querySelector('[data-check="instruction"]');
        const lh = parseFloat(getComputedStyle(instr).lineHeight);
        info.lines = Math.round(instr.getBoundingClientRect().height / lh);
        if (info.lines > 3) out.push(`banner instruction has ${info.lines} lines (> 3)`);
        if (document.documentElement.dataset.orient === "port") {
          const msgs = [...document.querySelectorAll("#status .msg")].filter(vis);
          info.msgs = msgs.map((m) => m.dataset.kind).join("+");
          const topBottom = Math.max(rect(document.querySelector('[data-box="banner"]')).bottom, ...msgs.map((m) => rect(m).bottom));
          const bottomTop = rect(document.querySelector('[data-box="progress"]')).top;
          const band = bottomTop - topBottom;
          info.band = Math.round(band);
          const need = innerWidth < 360 ? 0 : window.__scale > 1 ? 80 : 150;
          if (band < need) out.push(`map band ${band.toFixed(0)}dp < ${need}dp`);
          const puck = document.getElementById("puck");
          if (puck) { const pr = rect(puck), cy = (pr.top + pr.bottom) / 2; if (cy < topBottom || cy > bottomTop) out.push("puck centre is under the UI"); }
        }
      }
      if (kind === "preview") {
        const port = document.documentElement.dataset.orient === "port", big = innerWidth >= 360 && innerHeight >= 640;
        const sheet = document.getElementById("sheet"), start = document.querySelector('[data-check="start"]');
        const sum = document.querySelector('[data-check="summary"]'), tp = document.querySelector("#sheet .toppart");
        const entry = document.querySelector('[data-check="battery-entry"]'), hint = document.querySelector('[data-check="battery-hint"]');
        const area = document.getElementById("app").clientHeight - attr.offsetHeight;
        const fully = (el) => !!el && rect(el).height >= el.getBoundingClientRect().height - 0.5;
        if (!fully(start)) out.push("«Эхлэх» not fully visible");
        info.sheetPct = Math.round(sheet.getBoundingClientRect().height / area * 100);
        info.mapAbove = Math.round(sheet.getBoundingClientRect().top - document.getElementById("app").getBoundingClientRect().top);
        info.sumVisible = fully(sum); info.topScrolls = !!tp && tp.scrollHeight > tp.clientHeight + 1;
        info.entryVisible = entry ? fully(entry) : null; info.hintWhole = hint ? fully(hint) : null;
        if (port && big) {
          if (sheet.dataset.state === "collapsed") {
            // NAV-011 P2/P3 must still hold with the NAV-012 entry row (Layout rule 10)
            if (!info.sumVisible) out.push("route summary not fully visible");
            if (!info.entryVisible) out.push("battery entry row not fully visible");
            const need = window.__scale > 1 ? 96 : 160;
            if (info.mapAbove < need - 0.5) out.push(`map above the collapsed sheet ${info.mapAbove}dp < ${need}dp`);
            if (window.__scale === 1 && info.topScrolls) out.push("top part scrolls in the collapsed sheet at font scale 1");
          } else if (window.__scale === 1 && !info.hintWhole) out.push("full battery hint not visible in the expanded sheet at font scale 1");
        }
      }
      if (kind === "settings") {
        const row = document.querySelector('[data-check="battery-row"]');
        if (row.scrollWidth > row.clientWidth + 1) out.push("battery row clipped horizontally");
        info.setScroll = (() => { const s = document.querySelector("#set .scroll"); return s.scrollHeight > s.clientHeight + 1; })();
      }
      if (kind === "notif") {
        const ti = document.querySelector('[data-check="ntf-title"]'), card = document.querySelector('[data-box="notification"]');
        const clipped = ti ? ti.scrollWidth > ti.clientWidth + 1 : false;
        const isDistance = ti ? /^\d/.test(ti.textContent.trim()) : false;
        if (clipped && isDistance) out.push("distance title clipped");
        if (clipped && !isDistance) info.titleEllipsis = ti.textContent.trim();
        if (card.dataset.check === "collapsed") {
          const tx = document.querySelector('[data-check="ntf-text"]');
          // characters that fit: binary search on a probe span with the same font
          const probe = document.createElement("span"); probe.style.cssText = "position:absolute;visibility:hidden;white-space:nowrap";
          probe.style.font = getComputedStyle(tx).font; document.body.appendChild(probe);
          const full = tx.textContent.trim(); let n = full.length;
          probe.textContent = full;
          if (probe.getBoundingClientRect().width > tx.clientWidth) {
            n = 0; for (let k = 1; k <= full.length; k++) { probe.textContent = full.slice(0, k) + "…"; if (probe.getBoundingClientRect().width <= tx.clientWidth) n = k; else break; }
          }
          probe.remove();
          info.collapsedChars = `${n}/${full.length}`;
        }
      }
      if (document.documentElement.scrollWidth > vw + 0.5) out.push("horizontal overflow");
      return { out, info, kind };
    });
    const key = `${w}x${h} ${lang} scale ${scale}`;
    if (r.info.collapsedChars && theme === "day") addInfo(`collapsed notification ${state}: text chars visible ${key}`, r.info.collapsedChars);
    if (r.info.titleEllipsis && theme === "day") addInfo(`notification title ellipsised ${key} ${state}`, r.info.titleEllipsis);
    if (r.info.band != null && theme === "day") { const k = `min portrait map band ${w}x${h} scale ${scale}`; infos.set(k, Math.min(infos.get(k) ?? 1e9, r.info.band)); }
    if (r.info.lines != null && theme === "day") { const k = `max banner instruction lines ${key}`; infos.set(k, Math.max(infos.get(k) ?? 0, r.info.lines)); }
    if (r.info.mapAbove != null && theme === "day") addInfo(`${state}: ${key}`, `sheet ${r.info.sheetPct}% of the area above R5, map above ${r.info.mapAbove}dp, summary ${r.info.sumVisible ? "visible" : "scrolls"}${r.info.entryVisible != null ? `, entry row ${r.info.entryVisible ? "visible" : "scrolls"}` : ""}${r.info.hintWhole != null ? `, full hint ${r.info.hintWhole ? "visible" : "scrolls"}` : ""}, top part ${r.info.topScrolls ? "scrolls" : "fixed"}`);
    if (r.info.setScroll && theme === "day" && state === "settings-restricted") addInfo(`settings sheet scrolls ${key}`, "yes");
    if (r.info.msgs && theme === "day" && state === "restored-worst") addInfo(`restored-worst visible status ${key}`, r.info.msgs);
    // 320x568 above font scale 1 is outside the design target (NAV-005 Known limitations 7, D66). Notification action
    // labels above font scale 1.3 are drawn by the system template, which cannot wrap them (spec › Known limitations).
    const target = !(w < 360 && scale > 1);
    const sysLimit = (p) => scale > 1.3 && r.kind === "notif" && p.startsWith("control label clipped");
    for (const p of r.out) (target && !sysLimit(p) ? failures : outside).push(`${w}x${h} ${state} ${theme} ${lang} scale=${scale}: ${p}`);
  }
}
await browser.close();
for (const [k, v] of infos) console.log(`INFO  ${k}: ${v}`);
for (const f of outside) console.log(`INFO  outside target: ${f}`);
for (const f of failures) console.log(`FAIL  ${f}`);
console.log(`\n${runs} combinations (font: ${font}), ${failures.length} problem(s), ${outside.length} outside the design target (INFO)`);
process.exit(failures.length ? 1 : 0);

// UX audit of the web demo builds (docs/design/ux-audit-2026-10-13.md, owner: ux-designer). Drives the PRODUCTION
// builds in Chromium: `web/dist` (normal build; the gateway at localhost:8080 is intercepted: PMTiles from
// backend/data/tiles/basemap.pmtiles with HTTP Range, search/reverse from the NAV-003 QA fixtures, route from the
// NAV-004 QA OSRM builder) and `web/dist-demo-mode` (NAV-017 picker, replay, arrival). Phone (390x844), desktop
// (1280x800) and landscape (844x390), day/night, mn/en, every reachable state. Per state: a screenshot, the bounding
// box of every interactive element (< 44 / < 48 px flagged), gaps < 8 px and overlaps (rows inside a scrolled panel
// are reported although they are clipped), missing accessible names, the WCAG contrast of every visible text node
// against its first opaque ancestor, the smallest font size, the Tab order with the focus ring, and axe-core.
//
// Usage (repo root, builds present, Playwright from tests/e2e, Chromium from /opt/pw-browsers):
//   CHROMIUM_PATH=/opt/pw-browsers/chromium-1194/chrome-linux/chrome node docs/design/prototypes/audit-web.mjs demo
//   ... app | extra | arrival   (modes: demo = picker and replay states; app = normal build; extra = route result,
//   coordinate card by long-press, location denied, text zoom 200 %; arrival = real-time R1 replay to the arrival panel)
// Output: $AUDIT_OUT (default <os tmpdir>/navmn-ux-audit): shots/NNN-<state>.png, audit-web-<mode>.json, stdout log.
// Nothing is written inside the repo. The audit numbers quoted in the audit document come from this script.
import { createRequire } from "node:module";
import { readFileSync, writeFileSync, mkdirSync, openSync, readSync, statSync } from "node:fs";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";

const HERE = fileURLToPath(new URL("./", import.meta.url));
const ROOT = fileURLToPath(new URL("../../../", import.meta.url));
const require = createRequire(ROOT + "tests/e2e/package.json");
const { chromium } = require("playwright");
const { AxeBuilder } = require("@axe-core/playwright");
const nav004 = await import(ROOT + "tests/e2e/nav004/helpers.mjs");

const TILES = ROOT + "backend/data/tiles/basemap.pmtiles";
const OUT = (process.env.AUDIT_OUT ?? tmpdir() + "/navmn-ux-audit") + "/";
const SHOTS = OUT + "shots/";
const TILES_FD = openSync(TILES, "r");
const TILES_SIZE = statSync(TILES).size;
mkdirSync(SHOTS, { recursive: true });
let PORT_DEMO = 4891;
if ((process.argv[2] ?? "all") === "arrival") PORT_DEMO = 4901;
let PORT_APP = 4892;
if ((process.argv[2] ?? "all") === "extra") PORT_APP = 4893;
const ONLY = process.argv[2] ?? "all"; // all | demo | app

const VIEWPORTS = [
  { name: "phone", width: 390, height: 844, mobile: true },
  { name: "desktop", width: 1280, height: 800, mobile: false },
];
const LANDSCAPE = { name: "landscape", width: 844, height: 390, mobile: true };

// ---------------------------------------------------------------- in-page measurement
const MEASURE = `(() => {
  const out = { targets: [], texts: [], gaps: [], overlaps: [], unnamed: [], smallText: [] };
  const vis = (el) => {
    if (el.closest('[hidden]')) return false;
    const r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return false;
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0) return false;
    if (r.bottom < 0 || r.right < 0 || r.top > innerHeight || r.left > innerWidth) return false;
    return true;
  };
  const parse = (c) => { const m = c.match(/rgba?\\(([^)]+)\\)/); if (!m) return null; const [r,g,b,a] = m[1].split(',').map(Number); return { r, g, b, a: isNaN(a) ? 1 : a }; };
  const lum = ({ r, g, b }) => { const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
  const ratio = (a, b) => { const l1 = lum(a), l2 = lum(b); return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };
  const blend = (fg, bg) => ({ r: fg.r * fg.a + bg.r * (1 - fg.a), g: fg.g * fg.a + bg.g * (1 - fg.a), b: fg.b * fg.a + bg.b * (1 - fg.a), a: 1 });
  const bgOf = (el) => {
    // walk up until an opaque background; alpha layers are composited; the map canvas counts as unknown
    const layers = [];
    let e = el;
    while (e && e !== document.documentElement) {
      const cs = getComputedStyle(e);
      const c = parse(cs.backgroundColor);
      if (c && c.a > 0) { layers.unshift(c); if (c.a >= 1) { let acc = layers[0]; for (const l of layers.slice(1)) acc = blend(l, acc); return { color: acc, opaque: true, from: e.id || e.className }; } }
      if (e.id === 'map') break;
      e = e.parentElement;
    }
    return { color: null, opaque: false, layers: layers.length };
  };
  const name = (el) => el.getAttribute('aria-label') || (el.getAttribute('aria-labelledby') && document.getElementById(el.getAttribute('aria-labelledby'))?.textContent) || el.getAttribute('title') || el.textContent.trim().replace(/\\s+/g, ' ').slice(0, 60) || (el.tagName === 'INPUT' ? el.placeholder : '');
  const desc = (el) => (el.dataset.testid ? '[' + el.dataset.testid + ']' : el.id ? '#' + el.id : el.tagName.toLowerCase() + (el.className ? '.' + String(el.className).split(' ')[0] : ''));
  const sel = 'button, a[href], input, select, textarea, [role=radio], [role=tab], [role=switch], [role=option], [role=button], [tabindex]:not([tabindex="-1"])';
  const targets = [...document.querySelectorAll(sel)].filter(vis).filter((el) => !el.classList.contains('maplibregl-canvas'));
  for (const el of targets) {
    const r = el.getBoundingClientRect();
    const n = name(el);
    const t = { el: desc(el), name: n, w: Math.round(r.width), h: Math.round(r.height), x: Math.round(r.left), y: Math.round(r.top), disabled: el.getAttribute('aria-disabled') === 'true' || el.disabled === true, role: el.getAttribute('role') || el.tagName.toLowerCase() };
    out.targets.push(t);
    if (!n) out.unnamed.push(t.el);
  }
  for (let i = 0; i < targets.length; i++) for (let j = i + 1; j < targets.length; j++) {
    const a = targets[i].getBoundingClientRect(), b = targets[j].getBoundingClientRect();
    if (targets[i].contains(targets[j]) || targets[j].contains(targets[i])) continue;
    const dx = Math.max(0, Math.max(a.left, b.left) - Math.min(a.right, b.right));
    const dy = Math.max(0, Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom));
    const ox = Math.min(a.right, b.right) - Math.max(a.left, b.left), oy = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
    if (ox > 1 && oy > 1) out.overlaps.push([desc(targets[i]), desc(targets[j]), Math.round(ox), Math.round(oy)]);
    else { const gap = Math.round(Math.hypot(dx, dy)); if (gap < 8) out.gaps.push([desc(targets[i]), desc(targets[j]), gap]); }
  }
  // text: every element with a direct non-empty text node
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const seen = new Set();
  let node;
  while ((node = walker.nextNode())) {
    const el = node.parentElement;
    if (!el || seen.has(el) || !node.textContent.trim() || !vis(el) || el.closest('.sr-only, script, style')) continue;
    seen.add(el);
    const cs = getComputedStyle(el);
    const fg = parse(cs.color);
    const bg = bgOf(el);
    const size = parseFloat(cs.fontSize), weight = Number(cs.fontWeight) || 400;
    const large = size >= 24 || (size >= 18.66 && weight >= 700);
    const fgc = fg.a < 1 && bg.color ? blend(fg, bg.color) : fg;
    const t = { el: desc(el), text: node.textContent.trim().replace(/\\s+/g, ' ').slice(0, 50), size: +size.toFixed(1), weight, color: cs.color, bg: bg.color ? 'rgb(' + [bg.color.r, bg.color.g, bg.color.b].map(Math.round).join(',') + ')' : 'unknown(map)', ratio: bg.color ? +ratio(fgc, bg.color).toFixed(2) : null, large, need: large ? 3 : 4.5 };
    t.pass = t.ratio === null ? null : t.ratio >= t.need;
    out.texts.push(t);
    if (size < 12) out.smallText.push(t);
  }
  return out;
})()`;

// ---------------------------------------------------------------- helpers
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
async function step(label, fn) { try { await fn(); } catch (e) { console.log(`STEP FAILED ${label}: ${String(e.message).split("\n")[0]}`); results.push({ failed: label, error: String(e.message).split("\n")[0] }); } }
const results = [];
let shotIndex = 0;

async function capture(page, label, extra = {}) {
  const file = `${String(++shotIndex).padStart(3, "0")}-${label}.png`;
  await page.screenshot({ path: SHOTS + file, fullPage: false });
  const m = await page.evaluate(MEASURE);
  const small = m.targets.filter((t) => (t.w < 44 || t.h < 44));
  const below48 = m.targets.filter((t) => (t.w < 48 || t.h < 48) && !(t.w < 44 || t.h < 44));
  const failContrast = m.texts.filter((t) => t.pass === false);
  const unknownBg = m.texts.filter((t) => t.pass === null);
  const r = { shot: file, label, ...extra, targets: m.targets.length, small44: small, below48, gaps: m.gaps, overlaps: m.overlaps, unnamed: m.unnamed, failContrast, unknownBg: unknownBg.map((t) => t.el + ':' + t.text), minFont: Math.min(...m.texts.map((t) => t.size)), smallText: m.smallText, texts: m.texts };
  results.push(r);
  writeFileSync(OUT + `audit-web-${ONLY}.json`, JSON.stringify(results, null, 1));
  console.log(`${file}  targets=${m.targets.length} <44=${small.length} 44..47=${below48.length} gaps<8=${m.gaps.length} overlaps=${m.overlaps.length} unnamed=${m.unnamed.length} contrastFail=${failContrast.length} minFont=${r.minFont}`);
  for (const t of small) console.log(`   <44: ${t.el} "${t.name}" ${t.w}x${t.h}`);
  for (const t of below48) console.log(`   44..47: ${t.el} "${t.name}" ${t.w}x${t.h}`);
  for (const g of m.gaps) console.log(`   gap<8: ${g.join(' ')}`);
  for (const o of m.overlaps) console.log(`   overlap: ${o.join(' ')}`);
  for (const t of failContrast) console.log(`   contrast ${t.ratio}:1 (${t.need}) ${t.el} "${t.text}" ${t.color} on ${t.bg} ${t.size}px/${t.weight}`);
  return r;
}

async function axe(page, label) {
  try {
    const res = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"]).analyze();
    const v = res.violations.map((x) => ({ id: x.id, impact: x.impact, help: x.help, nodes: x.nodes.slice(0, 5).map((n) => n.target.join(" ")) }));
    results.push({ axe: label, violations: v });
    console.log(`axe ${label}: ${v.length} violations ${v.map((x) => x.id + '(' + x.impact + ')').join(', ')}`);
  } catch (e) {
    console.log(`axe ${label} failed: ${e.message}`);
  }
}

async function tabOrder(page, label, max = 40) {
  const seq = [];
  await page.evaluate(() => document.activeElement?.blur?.());
  for (let i = 0; i < max; i++) {
    await page.keyboard.press("Tab");
    const d = await page.evaluate(() => {
      const el = document.activeElement;
      if (!el || el === document.body) return "body";
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      const ring = cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0 ? '' : (el.matches(':focus-visible') ? ' NO-RING' : '');
      return (el.dataset.testid ? '[' + el.dataset.testid + ']' : el.id ? '#' + el.id : el.tagName.toLowerCase() + '.' + String(el.className).split(' ')[0]) + ring + (r.width === 0 ? ' (0px)' : '');
    });
    seq.push(d);
    if (i > 2 && seq[0] === d) break;
  }
  results.push({ tabOrder: label, seq });
  console.log(`tab ${label}: ${seq.join(' → ')}`);
}

function startServer(root, port) {
  const p = spawn("node", [HERE + "audit-web-serve.mjs", root, String(port), TILES], { stdio: "inherit" });
  return p;
}

async function setPrefs(context, lang, theme) {
  await context.addInitScript(({ lang, theme }) => {
    try { localStorage.setItem("navmn.lang", lang); localStorage.setItem("navmn.theme", theme); } catch {}
  }, { lang, theme });
}

// Gateway mock for the normal build (fixtures from tests/e2e, QA-authored).
const FEATURES = JSON.parse(readFileSync(ROOT + "tests/e2e/nav003/fixtures/features.json", "utf8")).fixtures.map((f) => f.feature);
const ROUTE_FIXTURE = JSON.parse(readFileSync(ROOT + "tests/e2e/nav004/fixtures/ac27-steps.json", "utf8"));
const JSON_CORS = { "Access-Control-Allow-Origin": "*", "Content-Type": "application/json; charset=utf-8" };

async function mockGateway(page, { tiles = "ok", routeMode = "ok" } = {}) {
  await page.route("http://localhost:8080/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/tiles/basemap.pmtiles") {
      if (tiles === "fail") return route.abort("failed");
      const range = route.request().headers()["range"];
      const size = TILES_SIZE;
      if (range) {
        const m = /bytes=(\d+)-(\d*)/.exec(range);
        const start = Number(m[1]);
        const end = m[2] ? Math.min(Number(m[2]), size - 1) : size - 1;
        const buf = Buffer.alloc(end - start + 1);
        readSync(TILES_FD, buf, 0, buf.length, start);
        return route.fulfill({ status: 206, headers: { "Access-Control-Allow-Origin": "*", "Content-Range": `bytes ${start}-${end}/${size}`, "Accept-Ranges": "bytes", "Content-Type": "application/octet-stream" }, body: buf });
      }
      return route.fulfill({ status: 200, headers: { "Access-Control-Allow-Origin": "*", "Accept-Ranges": "bytes", "Content-Type": "application/octet-stream" }, body: readFileSync(TILES) });
    }
    if (url.pathname === "/v1/search") {
      const q = (url.searchParams.get("q") || "").toLowerCase();
      const feats = /zzz|ззз|жжж/.test(q) ? [] : FEATURES.filter((f) => f.properties.name).slice(0, 6);
      return route.fulfill({ status: 200, headers: JSON_CORS, body: JSON.stringify({ type: "FeatureCollection", features: feats }) });
    }
    if (url.pathname === "/v1/reverse") {
      return route.fulfill({ status: 200, headers: JSON_CORS, body: JSON.stringify({ type: "FeatureCollection", features: [FEATURES[1]] }) });
    }
    if (url.pathname === "/v1/route") {
      if (routeMode === "unavailable") return route.fulfill({ status: 503, headers: JSON_CORS, body: "{}" });
      if (routeMode === "noroute") return route.fulfill({ status: 400, headers: JSON_CORS, body: JSON.stringify({ code: "NoRoute", message: "no route" }) });
      let o = nav004.REF.P1, d = nav004.REF.P3;
      try { const b = JSON.parse(route.request().postData() ?? "{}"); if (b.locations?.length === 2) { o = { lat: b.locations[0].lat, lng: b.locations[0].lon }; d = { lat: b.locations[1].lat, lng: b.locations[1].lon }; } } catch {}
      const steps = ROUTE_FIXTURE.steps.map((x) => ({ maneuver: x.maneuver, name: x.name ?? "", distance: 180 }));
      const body = nav004.simpleBody(o, d, [{ distance: 4300, duration: 720, steps }, { distance: 5100, duration: 840 }]);
      return route.fulfill({ status: 200, headers: JSON_CORS, body: JSON.stringify(body) });
    }
    return route.fulfill({ status: 404, headers: JSON_CORS, body: "{}" });
  });
}

async function waitReady(page) {
  await page.waitForFunction(() => {
    const b = document.getElementById("bottom");
    const l = document.getElementById("loading");
    return b && !b.hidden && (!l || l.getAttribute("aria-hidden") === "true");
  }, null, { timeout: 60_000 });
  await page.waitForFunction(() => window.__nav002?.map?.loaded?.() === true, null, { timeout: 60_000 }).catch(() => {});
  await wait(800);
}

// ---------------------------------------------------------------- demo-mode build
async function auditDemo(browser) {
  for (const vp of [...VIEWPORTS, LANDSCAPE]) {
    for (const theme of ["day", "night"]) {
      for (const lang of ["mn", "en"]) {
        if (vp.name === "landscape" && (theme === "night" || lang === "en")) continue;
        if (vp.name === "desktop" && lang === "en" && theme === "night") continue;
        const tag = `demo-${vp.name}-${theme}-${lang}`;
        const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, isMobile: vp.mobile, hasTouch: vp.mobile, deviceScaleFactor: 1, locale: lang === "mn" ? "mn-MN" : "en-US" });
        await setPrefs(context, lang, theme);
        const page = await context.newPage();
        page.on("pageerror", (e) => console.log("PAGE ERROR", e.message));
        await page.goto(`http://127.0.0.1:${PORT_DEMO}/`, { waitUntil: "load" });
        await page.waitForSelector("[data-testid=demo-picker]", { state: "visible", timeout: 60_000 });
        await wait(1500);
        await capture(page, `${tag}-picker`, { build: "demo", vp: vp.name, theme, lang, state: "picker" });
        if (vp.name === "phone" && theme === "day" && lang === "mn") { await axe(page, `${tag}-picker`); await tabOrder(page, `${tag}-picker`); }
        // select R1
        await page.locator("[data-testid=demo-route]").first().click();
        await page.waitForFunction(() => document.querySelector("[data-testid=demo-start]")?.getAttribute("aria-disabled") === "false", null, { timeout: 30_000 });
        await wait(1200);
        await capture(page, `${tag}-picker-selected`, { build: "demo", vp: vp.name, theme, lang, state: "picker-selected" });
        // start the replay
        await page.locator("[data-testid=demo-start]").click();
        await page.waitForSelector("[data-testid=demo-nav-banner]", { state: "visible", timeout: 20_000 });
        await wait(3000);
        await capture(page, `${tag}-guidance`, { build: "demo", vp: vp.name, theme, lang, state: "guidance" });
        if (vp.name === "phone" && lang === "mn") { await axe(page, `${tag}-guidance`); if (theme === "day") await tabOrder(page, `${tag}-guidance`); }
        // offline during the replay
        await context.setOffline(true);
        await wait(2500);
        await capture(page, `${tag}-guidance-offline`, { build: "demo", vp: vp.name, theme, lang, state: "guidance-offline" });
        await context.setOffline(false);
        await wait(2500);
        // map gesture → recenter
        const box = await page.locator("#map").boundingBox();
        const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
        await page.mouse.move(cx, cy);
        await page.mouse.down();
        await page.mouse.move(cx + 80, cy + 60, { steps: 8 });
        await page.mouse.up();
        await wait(800);
        await capture(page, `${tag}-guidance-notfollowing`, { build: "demo", vp: vp.name, theme, lang, state: "guidance-notfollowing" });
        if (vp.name === "phone" && theme === "day" && lang === "mn") {
          // text zoom 200 % (root font size doubled, as the NAV-017 spec defines it)
          await page.evaluate(() => { document.documentElement.style.fontSize = "32px"; });
          await wait(1200);
          await capture(page, `${tag}-guidance-zoom200`, { build: "demo", vp: vp.name, theme, lang, state: "guidance-zoom200" });
          await page.evaluate(() => { document.documentElement.style.fontSize = ""; });
          await wait(600);
        }
        // arrival: let the replay run to its end on its own? R1 is 308 s. Fast-forward is not possible without the
        // fake clock installed before load; instead end the replay here (back to the picker, focus on the heading).
        await page.locator("[data-testid=demo-nav-end]").click();
        await wait(1200);
        await capture(page, `${tag}-picker-after-end`, { build: "demo", vp: vp.name, theme, lang, state: "picker-after-end" });
        await context.close();
      }
    }
  }
  // arrival state with the fake clock (phone, day, mn)
  for (const theme of ["day", "night"]) {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 });
    await setPrefs(context, "mn", theme);
    const page = await context.newPage();
    await page.clock.install({ time: new Date("2026-10-13T14:00:00+08:00") });
    await page.goto(`http://127.0.0.1:${PORT_DEMO}/`, { waitUntil: "load" });
    await page.waitForSelector("[data-testid=demo-picker]", { state: "visible", timeout: 60_000 });
    await page.locator("[data-testid=demo-route]").nth(2).click(); // R3 roundabout route, 787 s? use R1 (308 s) instead
    await page.waitForFunction(() => document.querySelector("[data-testid=demo-start]")?.getAttribute("aria-disabled") === "false", null, { timeout: 30_000 });
    await page.locator("[data-testid=demo-route]").first().click();
    await page.waitForFunction(() => document.querySelector("[data-testid=demo-start]")?.getAttribute("aria-disabled") === "false", null, { timeout: 30_000 });
    await page.locator("[data-testid=demo-start]").click();
    await page.waitForSelector("[data-testid=demo-nav-banner]", { state: "visible", timeout: 20_000 });
    try {
      for (let i = 0; i < 34; i++) await page.clock.runFor(10_000);
      await page.waitForSelector("[data-testid=demo-nav-arrival]", { state: "visible", timeout: 10_000 });
      await wait(800);
      await capture(page, `demo-phone-${theme}-mn-arrival`, { build: "demo", vp: "phone", theme, lang: "mn", state: "arrival" });
      if (theme === "day") await axe(page, `demo-phone-${theme}-mn-arrival`);
    } catch (e) {
      console.log("arrival capture failed: " + e.message);
      await capture(page, `demo-phone-${theme}-mn-arrival-attempt`, { build: "demo", state: "arrival-attempt" });
    }
    await context.close();
  }
}

// ---------------------------------------------------------------- normal build (mocked gateway)
async function auditApp(browser) {
  for (const vp of VIEWPORTS) {
    for (const theme of ["day", "night"]) {
      for (const lang of ["mn", "en"]) {
        if (vp.name === "desktop" && lang === "en" && theme === "night") continue;
        const tag = `app-${vp.name}-${theme}-${lang}`;
        const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, isMobile: vp.mobile, hasTouch: vp.mobile, deviceScaleFactor: 1, locale: lang === "mn" ? "mn-MN" : "en-US" });
        await setPrefs(context, lang, theme);
        const page = await context.newPage();
        page.on("pageerror", (e) => console.log("PAGE ERROR", e.message));
        await mockGateway(page);
        const x = { build: "app", vp: vp.name, theme, lang };
        const full = vp.name === "phone" && theme === "day" && lang === "mn";
        await step(`${tag}-ready`, async () => {
          await page.goto(`http://127.0.0.1:${PORT_APP}/`, { waitUntil: "load" });
          await waitReady(page);
          await capture(page, `${tag}-ready`, { ...x, state: "ready" });
          if (full) { await axe(page, `${tag}-ready`); await tabOrder(page, `${tag}-ready`); }
        });
        await step(`${tag}-search-results`, async () => {
          await page.locator("#search-input").click();
          await page.keyboard.type("Сүхбаатар");
          await page.waitForSelector("#search-results [role=option]", { state: "visible", timeout: 10_000 });
          await wait(600);
          await capture(page, `${tag}-search-results`, { ...x, state: "search-results" });
          if (full) await axe(page, `${tag}-search-results`);
        });
        await step(`${tag}-search-noresults`, async () => {
          await page.keyboard.press("ControlOrMeta+A");
          await page.keyboard.type("zzz");
          await page.waitForSelector("#search-state:not([hidden])", { timeout: 10_000 });
          await wait(500);
          await capture(page, `${tag}-search-noresults`, { ...x, state: "search-noresults" });
        });
        await step(`${tag}-place-card`, async () => {
          await page.locator("#search-input").click();
          await page.keyboard.press("ControlOrMeta+A");
          await page.keyboard.type("Сүхбаатар");
          await page.waitForSelector("#search-results [role=option]", { state: "visible", timeout: 10_000 });
          await page.locator("#search-results [role=option]").first().click();
          await page.waitForSelector("#place-card", { state: "visible", timeout: 10_000 });
          await wait(1500);
          await capture(page, `${tag}-place-card`, { ...x, state: "place-card" });
          if (full) { await axe(page, `${tag}-place-card`); await tabOrder(page, `${tag}-place-card`); }
        });
        await step(`${tag}-route`, async () => {
          await page.locator("#route-open").click();
          await page.waitForSelector("#route-panel", { state: "visible", timeout: 10_000 });
          await wait(800);
          await capture(page, `${tag}-route-empty-origin`, { ...x, state: "route-empty-origin" });
          await page.locator("#route-origin").click();
          await page.keyboard.type("Зайсан");
          await page.waitForSelector("#route-origin-results [role=option]", { state: "visible", timeout: 10_000 });
          await wait(300);
          await capture(page, `${tag}-route-origin-list`, { ...x, state: "route-origin-list" });
          await page.locator("#route-origin-results [role=option]").nth(1).click();
          await page.waitForFunction(() => document.querySelector("#route-panel")?.dataset.state === "route", null, { timeout: 15_000 }).catch(() => {});
          await wait(1500);
          await capture(page, `${tag}-route-result`, { ...x, state: "route-result" });
          if (full) { await axe(page, `${tag}-route-result`); await tabOrder(page, `${tag}-route-result`); }
          await page.evaluate(() => { const p = document.querySelector("#route-panel"); if (p) p.scrollTop = p.scrollHeight; });
          await wait(400);
          await capture(page, `${tag}-route-turnlist`, { ...x, state: "route-turnlist" });
          await page.evaluate(() => { const p = document.querySelector("#route-panel"); if (p) p.scrollTop = 0; });
          await page.locator("#route-tab-walk").click();
          await wait(1500);
          await capture(page, `${tag}-route-walk`, { ...x, state: "route-walk" });
          await context.setOffline(true);
          await page.locator("#route-tab-bike").click();
          await wait(1500);
          await capture(page, `${tag}-route-offline`, { ...x, state: "route-offline" });
          await context.setOffline(false);
          await wait(1500);
        });
        await step(`${tag}-coordinate-card`, async () => {
          await page.keyboard.press("Escape");
          await wait(500);
          await page.locator("#place-card-close").click({ timeout: 3000 }).catch(() => {});
          await wait(300);
          const box = await page.locator("#map").boundingBox();
          if (vp.mobile) {
            const cx = box.x + box.width / 2, cy = box.y + box.height * 0.45;
            await page.mouse.move(cx, cy);
            await page.mouse.down();
            await wait(800);
            await page.mouse.up();
          } else {
            await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2, { button: "right" });
          }
          await page.waitForSelector("#place-card", { state: "visible", timeout: 5000 });
          await wait(1500);
          await capture(page, `${tag}-coordinate-card`, { ...x, state: "coordinate-card" });
          await page.locator("#place-card-close").click({ timeout: 3000 }).catch(() => {});
        });
        await step(`${tag}-location-denied`, async () => {
          await page.locator("#locate-btn").click();
          await wait(2500);
          await capture(page, `${tag}-location-denied`, { ...x, state: "location-denied" });
          await page.locator("#location-close").click({ timeout: 3000 }).catch(() => {});
        });
        await step(`${tag}-offline`, async () => {
          await context.setOffline(true);
          await wait(2000);
          await capture(page, `${tag}-offline`, { ...x, state: "offline" });
          await context.setOffline(false);
        });
        await context.close();
      }
    }
  }
  for (const theme of ["day", "night"]) {
    await step(`tiles-unavailable-${theme}`, async () => {
      const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 });
      await setPrefs(context, "mn", theme);
      const page = await context.newPage();
      await mockGateway(page, { tiles: "fail" });
      await page.goto(`http://127.0.0.1:${PORT_APP}/`, { waitUntil: "load" });
      await page.waitForSelector("#card:not([hidden])", { timeout: 30_000 }).catch(() => {});
      await wait(800);
      await capture(page, `app-phone-${theme}-mn-tiles-unavailable`, { build: "app", vp: "phone", theme, lang: "mn", state: "tiles-unavailable" });
      if (theme === "day") await axe(page, `app-phone-${theme}-mn-tiles-unavailable`);
      await context.close();
    });
  }
  for (const mode of ["unavailable", "noroute"]) {
    await step(`route-${mode}`, async () => {
      const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 });
      await setPrefs(context, "mn", "day");
      const page = await context.newPage();
      await mockGateway(page, { routeMode: mode });
      await page.goto(`http://127.0.0.1:${PORT_APP}/`, { waitUntil: "load" });
      await waitReady(page);
      await page.locator("#search-input").click();
      await page.keyboard.type("Сүхбаатар");
      await page.waitForSelector("#search-results [role=option]", { state: "visible", timeout: 10_000 });
      await page.locator("#search-results [role=option]").first().click();
      await page.waitForSelector("#place-card", { state: "visible", timeout: 10_000 });
      await page.locator("#route-open").click();
      await page.waitForSelector("#route-panel", { state: "visible", timeout: 10_000 });
      await page.locator("#route-origin").click();
      await page.keyboard.type("Зайсан");
      await page.waitForSelector("#route-origin-results [role=option]", { state: "visible", timeout: 10_000 });
      await page.locator("#route-origin-results [role=option]").nth(1).click();
      await wait(2500);
      await capture(page, `app-phone-day-mn-route-${mode}`, { build: "app", vp: "phone", theme: "day", lang: "mn", state: `route-${mode}` });
      await context.close();
    });
  }
}


async function auditExtra(browser) {
  for (const vp of VIEWPORTS) {
    for (const theme of ["day", "night"]) {
      const lang = "mn";
      const tag = `app-${vp.name}-${theme}-${lang}`;
      const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, isMobile: vp.mobile, hasTouch: vp.mobile, deviceScaleFactor: 1, locale: "mn-MN" });
      await setPrefs(context, lang, theme);
      await context.addInitScript(() => {
        const denied = { code: 1, PERMISSION_DENIED: 1, message: "denied" };
        const g = { getCurrentPosition: (_ok, err) => setTimeout(() => err && err(denied), 50), watchPosition: (_ok, err) => { setTimeout(() => err && err(denied), 50); return 1; }, clearWatch: () => {} };
        Object.defineProperty(navigator, "geolocation", { value: g, configurable: true });
      });
      const page = await context.newPage();
      await mockGateway(page);
      const x = { build: "app", vp: vp.name, theme, lang };
      const full = vp.name === "phone" && theme === "day";
      await step(`${tag}-x-ready`, async () => { await page.goto(`http://127.0.0.1:${PORT_APP}/`, { waitUntil: "load" }); await waitReady(page); });
      await step(`${tag}-x-location-denied`, async () => {
        await page.locator("#locate-btn").click();
        await page.waitForSelector("#location-message:not([hidden])", { timeout: 8000 });
        await wait(600);
        await capture(page, `${tag}-location-denied`, { ...x, state: "location-denied" });
        if (full) await axe(page, `${tag}-location-denied`);
        await page.locator("#location-close").click({ timeout: 3000 }).catch(() => {});
      });
      await step(`${tag}-x-coordinate-card`, async () => {
        const box = await page.locator("#map").boundingBox();
        const cx = box.x + box.width / 2, cy = box.y + box.height * 0.45;
        if (vp.mobile) {
          const cdp = await context.newCDPSession(page);
          await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x: cx, y: cy }] });
          await wait(900);
          await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
        } else {
          await page.mouse.click(cx, cy, { button: "right" });
        }
        await page.waitForSelector("#place-card", { state: "visible", timeout: 5000 });
        await wait(1500);
        await capture(page, `${tag}-coordinate-card`, { ...x, state: "coordinate-card" });
        if (full) { await axe(page, `${tag}-coordinate-card`); await tabOrder(page, `${tag}-coordinate-card`); }
      });
      await step(`${tag}-x-route-from-coordinate`, async () => {
        await page.locator("#route-open").click();
        await page.waitForSelector("#route-panel", { state: "visible", timeout: 10_000 });
        await page.locator("#route-origin").click();
        await page.keyboard.type("Зайсан");
        await page.waitForSelector("#route-origin-results [role=option]", { state: "visible", timeout: 10_000 });
        await page.locator("#route-origin-results [role=option]").nth(1).click();
        await page.waitForFunction(() => document.querySelector("#route-panel")?.dataset.state === "route", null, { timeout: 15_000 });
        await wait(1500);
        await capture(page, `${tag}-route-result`, { ...x, state: "route-result" });
        if (full) { await axe(page, `${tag}-route-result`); await tabOrder(page, `${tag}-route-result`, 45); }
        await page.evaluate(() => { const p = document.querySelector("#route-panel"); if (p) p.scrollTop = 420; });
        await wait(400);
        await capture(page, `${tag}-route-options`, { ...x, state: "route-options" });
        await page.evaluate(() => { const p = document.querySelector("#route-panel"); if (p) p.scrollTop = p.scrollHeight; });
        await wait(400);
        await capture(page, `${tag}-route-turnlist`, { ...x, state: "route-turnlist" });
        // activate a turn row (camera to the step)
        await page.locator("#route-panel [data-testid=route-step]").nth(3).click({ timeout: 3000 }).catch(() => {});
        await wait(1200);
        await capture(page, `${tag}-route-step-active`, { ...x, state: "route-step-active" });
        // text zoom 200 % on the phone
        if (vp.mobile) {
          await page.evaluate(() => { document.documentElement.style.fontSize = "32px"; });
          await wait(800);
          await capture(page, `${tag}-route-zoom200`, { ...x, state: "route-zoom200" });
          await page.evaluate(() => { document.documentElement.style.fontSize = ""; });
        }
      });
      await context.close();
    }
  }
}


async function auditArrival(browser) {
  for (const theme of ["day", "night"]) {
    await step(`arrival-${theme}`, async () => {
      const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 });
      await setPrefs(context, "mn", theme);
      const page = await context.newPage();
      await page.goto(`http://127.0.0.1:${PORT_DEMO}/`, { waitUntil: "load" });
      await page.waitForSelector("[data-testid=demo-picker]", { state: "visible", timeout: 60_000 });
      await page.locator("[data-testid=demo-route]").first().click();
      await page.waitForFunction(() => document.querySelector("[data-testid=demo-start]")?.getAttribute("aria-disabled") === "false", null, { timeout: 30_000 });
      await page.locator("[data-testid=demo-start]").click();
      await page.waitForSelector("[data-testid=demo-nav-banner]", { state: "visible", timeout: 20_000 });
      // the R1 replay is 308 s at 1x; capture the banner near the end, then the arrival panel
      await page.waitForFunction(() => document.querySelector("[data-testid=demo-nav-banner]")?.dataset.variant === "arrival", null, { timeout: 420_000 });
      await wait(1200);
      await capture(page, `demo-phone-${theme}-mn-arrival`, { build: "demo", vp: "phone", theme, lang: "mn", state: "arrival" });
      if (theme === "day") { await axe(page, `demo-phone-${theme}-mn-arrival`); await tabOrder(page, `demo-phone-${theme}-mn-arrival`); }
      await context.close();
    });
  }
}

// ---------------------------------------------------------------- main
const servers = [];
if (ONLY === "demo" || ONLY === "all") servers.push(startServer(ROOT + "web/dist-demo-mode", PORT_DEMO));
if (ONLY === "arrival") servers.push(startServer(ROOT + "web/dist-demo-mode", PORT_DEMO));
if (ONLY === "app" || ONLY === "all" || ONLY === "extra") servers.push(startServer(ROOT + "web/dist", PORT_APP));
await wait(600);
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH, args: ["--use-gl=swiftshader", "--enable-webgl", "--ignore-gpu-blocklist"] });
try {
  if (ONLY === "demo" || ONLY === "all") await auditDemo(browser);
  if (ONLY === "arrival") await auditArrival(browser);
  if (ONLY === "extra") await auditExtra(browser); else if (ONLY !== "demo") await auditApp(browser);
} finally {
  await browser.close();
  for (const s of servers) s.kill();
}
writeFileSync(OUT + `audit-web-${ONLY}.json`, JSON.stringify(results, null, 1));
console.log("done");

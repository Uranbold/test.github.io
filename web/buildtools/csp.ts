// SEC-4B (ADR-0018 §1–§3): Content-Security-Policy and Referrer-Policy meta tags for every production HTML file, built
// from the final bytes of each inline <script> and <style>, plus an independent check of the files written to disk.
//
//  - Generation (generateBundle, order "post", plugin listed last): runs after every transformIndexHtml step (bootIndicator,
//    demoHtml, Vite's own tags) and after Vite's asset-URL replacement, on the HTML asset that is about to be written.
//    1. strips HTML comments outside <script>/<style> raw text (WS-16; the source index.html keeps them);
//    2. puts <meta charset="utf-8"> first in <head>, then the policy, then <meta name="referrer" content="same-origin">,
//       so every inline element (also the demo-mode trailing-slash guard) follows the policy and the charset sits inside
//       the first 1024 bytes (ADR-0018 F1, F2);
//    3. hashes every inline <script> (no src) and <style> (sha256, base64);
//    4. writes the per-build policy (ADR-0018 §2, buildPolicy).
//  - Verification (closeBundle): reads every file of the output directory from disk, parses each .html with jsdom (a
//    different parser from the generator's scanner) and hashes the parsed textContent, which is what the browser hashes.
//    Any rule of ADR-0018 §1 that does not hold fails the build with the file and the element (verifyHtml, verifyOutput).
//    So a whitespace or newline bug on either side fails the build instead of shipping a page whose script is blocked.
//
// No hash is ever written by hand; 'unsafe-inline' and nonces are never used (static files, no server to mint a nonce).
// The policy applies to builds only: `npm run dev` injects inline scripts for hot reload and gets no policy.
import { createHash } from "node:crypto";
import { readdirSync, readFileSync } from "node:fs";
import { basename, join, relative, resolve, sep } from "node:path";
import type { Plugin } from "vite";
import { gatewayConnectOrigin, parseStaticDemo } from "../src/config";
import { parseDemoMode } from "./demoMode";

export const REFERRER_POLICY = "same-origin";
/** Directives a browser ignores in a meta policy (story AC 6); frame-ancestors goes into the response header (web/README.md). */
export const META_IGNORED_DIRECTIVES = ["frame-ancestors", "report-uri", "report-to", "sandbox"] as const;

export interface PolicyOptions {
  /** B2 demo-mode build (NAV-017): may get 'wasm-unsafe-eval' (F5) and gets media-src 'self' data: (runtime chime, F6). */
  demo: boolean;
  /** The output contains at least one .wasm file. */
  wasm: boolean;
  /** Absolute gateway origin for connect-src (B3), or null when the gateway is the page origin ('self'). */
  gatewayOrigin: string | null;
}

/** CSP hash source of an inline element's text, as the browser computes it (UTF-8, after CR/CRLF → LF). */
export function sha256Source(text: string): string {
  return `'sha256-${createHash("sha256").update(text.replace(/\r\n?/g, "\n"), "utf8").digest("base64")}'`;
}

/** The ADR-0018 §2 policy matrix. Hashes keep their document order; duplicates are listed once. */
export function buildPolicy(o: PolicyOptions & { scriptHashes: readonly string[]; styleHashes: readonly string[] }): string {
  const uniq = (a: readonly string[]): string[] => [...new Set(a)];
  const directives: [string, string[]][] = [
    ["default-src", ["'self'"]],
    ["script-src", ["'self'", ...(o.demo && o.wasm ? ["'wasm-unsafe-eval'"] : []), ...uniq(o.scriptHashes)]],
    ["style-src", ["'self'", ...uniq(o.styleHashes)]],
    ["img-src", ["'self'", "data:", "blob:"]],
    ["connect-src", ["'self'", ...(o.gatewayOrigin ? [o.gatewayOrigin] : [])]],
    ["worker-src", ["'self'"]],
    ...(o.demo ? ([["media-src", ["'self'", "data:"]]] as [string, string[]][]) : []),
    ["object-src", ["'none'"]],
    ["base-uri", ["'none'"]],
    ["form-action", ["'none'"]],
  ];
  return directives.map(([name, sources]) => `${name} ${sources.join(" ")}`).join("; ");
}

/** Parses a policy into directive → sources (names lower-case). A repeated directive is reported (browsers ignore it). */
export function parsePolicy(policy: string): { directives: Map<string, string[]>; repeated: string[] } {
  const directives = new Map<string, string[]>();
  const repeated: string[] = [];
  for (const part of policy.split(";")) {
    const [name, ...sources] = part.trim().split(/\s+/).filter(Boolean);
    if (!name) continue;
    const key = name.toLowerCase();
    if (directives.has(key)) repeated.push(key);
    else directives.set(key, sources);
  }
  return { directives, repeated };
}

const RAW_TEXT_OPEN_RE = /<(script|style)\b[^>]*>/iy;

/**
 * Removes HTML comments outside <script>/<style> raw text (WS-16). A comment on a line of its own takes its indentation
 * and line break with it. An unterminated comment throws.
 */
export function stripHtmlComments(html: string): string {
  let out = "";
  let i = 0;
  while (i < html.length) {
    const lt = html.indexOf("<", i);
    if (lt < 0) {
      out += html.slice(i);
      break;
    }
    out += html.slice(i, lt);
    if (html.startsWith("<!--", lt)) {
      const end = html.indexOf("-->", lt + 4);
      if (end < 0) throw new Error("unterminated HTML comment");
      i = end + 3;
      const lineStart = out.lastIndexOf("\n") + 1;
      const eol = /^[ \t]*\r?\n/.exec(html.slice(i));
      if (eol && /^[ \t]*$/.test(out.slice(lineStart))) {
        out = out.slice(0, lineStart);
        i += eol[0].length;
      }
      continue;
    }
    RAW_TEXT_OPEN_RE.lastIndex = lt;
    const open = RAW_TEXT_OPEN_RE.exec(html);
    if (open) {
      // raw text: copied unchanged up to and including the closing tag
      const closeRe = new RegExp(`</${open[1]}\\s*>`, "gi");
      closeRe.lastIndex = lt + open[0].length;
      const close = closeRe.exec(html);
      const stop = close ? close.index + close[0].length : html.length;
      out += html.slice(lt, stop);
      i = stop;
      continue;
    }
    out += "<";
    i = lt + 1;
  }
  return out;
}

export interface InlineElement {
  tag: "script" | "style";
  text: string;
}

/** Inline <script> (without src) and <style> elements of an HTML string, in document order (generator side). */
export function inlineElements(html: string): InlineElement[] {
  const found: InlineElement[] = [];
  for (const m of html.matchAll(/<(script|style)\b([^>]*)>([\s\S]*?)<\/\1\s*>/gi)) {
    const tag = m[1]!.toLowerCase() as "script" | "style";
    if (tag === "script" && /(?:^|\s)src\s*=/i.test(m[2]!)) continue;
    found.push({ tag, text: m[3]! });
  }
  return found;
}

/** The generator: comments stripped, head order normalised, policy and referrer meta written (ADR-0018 §1 steps 1–4). */
export function secureHtml(html: string, o: PolicyOptions): string {
  let h = stripHtmlComments(html);
  if (/<meta\b[^>]*\bhttp-equiv\s*=\s*["']?content-security-policy\b/i.test(h)) {
    throw new Error("the HTML already has a Content-Security-Policy meta; the policy is generated by buildtools/csp.ts only");
  }
  if (/<meta\b[^>]*\bname\s*=\s*["']?referrer\b/i.test(h)) {
    throw new Error("the HTML already has a referrer meta; it is generated by buildtools/csp.ts only");
  }
  const charset = /[ \t]*<meta\s+charset\s*=\s*["']?([^"'\s/>]+)["']?\s*\/?>[ \t]*(?:\r?\n)?/i.exec(h);
  if (charset && charset[1]!.toLowerCase() !== "utf-8") throw new Error(`<meta charset="${charset[1]}">: only utf-8 is supported`);
  if (charset) h = h.slice(0, charset.index) + h.slice(charset.index + charset[0].length);
  const head = /<head\b[^>]*>/i.exec(h);
  if (!head) throw new Error("no <head> element");
  const els = inlineElements(h);
  const policy = buildPolicy({
    ...o,
    scriptHashes: els.filter((e) => e.tag === "script").map((e) => sha256Source(e.text)),
    styleHashes: els.filter((e) => e.tag === "style").map((e) => sha256Source(e.text)),
  });
  if (/["&<>]/.test(policy)) throw new Error(`policy is not attribute-safe: ${policy}`);
  const nl = "\n    ";
  const metas =
    `${nl}<meta charset="utf-8" />` +
    `${nl}<meta http-equiv="Content-Security-Policy" content="${policy}" />` +
    `${nl}<meta name="referrer" content="${REFERRER_POLICY}" />`;
  const at = head.index + head[0].length;
  return h.slice(0, at) + metas + h.slice(at);
}

// ------------------------------------------------------------------------------------------------ independent verifier

export interface VerifyOptions {
  /** File name used in the messages (relative to the output directory). */
  file: string;
  demo: boolean;
  /** The output directory contains at least one .wasm file. */
  hasWasm: boolean;
  gatewayOrigin: string | null;
}

const HASH_RE = /^'sha256-[A-Za-z0-9+/]+={0,2}'$/;
const isHash = (s: string): boolean => HASH_RE.test(s);

function describe(el: Element, index: number): string {
  const where = el.closest("head") ? "head" : "body";
  const text = (el.textContent ?? "").replace(/\s+/g, " ").trim().slice(0, 60);
  const id = el.id ? `#${el.id}` : "";
  return `inline <${el.tagName.toLowerCase()}${id}> #${index + 1} in <${where}> "${text}${text.length === 60 ? "…" : ""}"`;
}

/**
 * Checks one built HTML file against ADR-0018 §1 (story AC 1–6, 8, 9, 11, 15). Returns the problems ("<file>: …"); an
 * empty list means the file passes. Parses with jsdom (no scripts run, no resources load).
 */
export async function verifyHtml(html: string, o: VerifyOptions): Promise<string[]> {
  const problems: string[] = [];
  const fail = (m: string): void => {
    problems.push(`${o.file}: ${m}`);
  };
  if (html.includes("<!--")) fail('contains "<!--" (developer comments are stripped from production HTML, WS-16 / AC 15)');
  const charsetAt = html.search(/<meta\s+charset\s*=/i);
  if (charsetAt < 0) fail("no <meta charset>");
  else if (Buffer.byteLength(html.slice(0, charsetAt), "utf8") >= 1024) fail("<meta charset> is not within the first 1024 bytes");

  const { JSDOM } = await import("jsdom");
  const dom = new JSDOM(html);
  const doc = dom.window.document;
  try {
    const kids = [...doc.head.children];
    const first = kids[0];
    if (!first || first.tagName !== "META" || (first.getAttribute("charset") ?? "").toLowerCase() !== "utf-8") {
      fail('the first element of <head> is not <meta charset="utf-8">');
    }
    const cspMetas = [...doc.querySelectorAll("meta")].filter((m) => (m.getAttribute("http-equiv") ?? "").toLowerCase() === "content-security-policy");
    if (cspMetas.length !== 1) fail(`expected exactly 1 Content-Security-Policy meta, found ${cspMetas.length}`);
    const csp = cspMetas[0];
    if (csp && kids[1] !== csp) fail("the Content-Security-Policy meta is not the element directly after <meta charset>");
    if (csp) {
      // DOCUMENT_POSITION_PRECEDING = 2: an element the policy does not cover.
      for (const el of doc.querySelectorAll("script, style, link")) {
        if (csp.compareDocumentPosition(el) & 2) fail(`<${el.tagName.toLowerCase()}> comes before the Content-Security-Policy meta`);
      }
    }
    const referrers = [...doc.querySelectorAll("meta")].filter((m) => (m.getAttribute("name") ?? "").toLowerCase() === "referrer");
    if (referrers.length !== 1) fail(`expected exactly 1 referrer meta, found ${referrers.length}`);
    else if (referrers[0]!.getAttribute("content") !== REFERRER_POLICY || !referrers[0]!.closest("head")) {
      fail(`the referrer meta must be <meta name="referrer" content="${REFERRER_POLICY}"> in <head>`);
    }

    const { directives, repeated } = parsePolicy(csp?.getAttribute("content") ?? "");
    for (const r of repeated) fail(`directive ${r} appears more than once`);
    for (const d of META_IGNORED_DIRECTIVES) if (directives.has(d)) fail(`${d} is ignored in a meta policy (set it as a response header)`);
    const only = (name: string, allowed: (s: string) => boolean, required: string[] = []): string[] => {
      const v = directives.get(name);
      if (!v) {
        fail(`directive ${name} is missing`);
        return [];
      }
      for (const s of v) if (!allowed(s)) fail(`${name} contains the source ${s}, which is not allowed`);
      for (const r of required) if (!v.includes(r)) fail(`${name} lacks ${r}`);
      return v;
    };
    const exactly = (name: string, sources: string[]): void => {
      const v = directives.get(name);
      if (!v || v.length !== sources.length || sources.some((s, i) => v[i] !== s)) {
        fail(`${name} must be "${sources.join(" ")}", is "${v ? v.join(" ") : "(missing)"}"`);
      }
    };
    const scriptSrc = only("script-src", (s) => s === "'self'" || s === "'wasm-unsafe-eval'" || isHash(s), ["'self'"]);
    const styleSrc = only("style-src", (s) => s === "'self'" || isHash(s), ["'self'"]);
    exactly("default-src", ["'self'"]);
    exactly("object-src", ["'none'"]);
    exactly("base-uri", ["'none'"]);
    exactly("form-action", ["'none'"]);
    only("img-src", (s) => ["'self'", "data:", "blob:"].includes(s), ["'self'"]);
    only("worker-src", (s) => ["'self'", "blob:"].includes(s), ["'self'"]);
    exactly("connect-src", ["'self'", ...(o.gatewayOrigin ? [o.gatewayOrigin] : [])]);
    if (o.demo) exactly("media-src", ["'self'", "data:"]);
    else if (directives.has("media-src")) fail("media-src is set outside the demo-mode build");
    for (const name of directives.keys()) {
      if (!["default-src", "script-src", "style-src", "img-src", "connect-src", "worker-src", "media-src", "object-src", "base-uri", "form-action"].includes(name)) {
        fail(`unexpected directive ${name}`);
      }
    }

    const wasmEval = scriptSrc.includes("'wasm-unsafe-eval'");
    if (wasmEval && !o.hasWasm) fail("'wasm-unsafe-eval' is in script-src but the output has no .wasm file");
    if (o.hasWasm && !wasmEval) fail("the output has a .wasm file but script-src lacks 'wasm-unsafe-eval'");
    if (wasmEval && !o.demo) fail("'wasm-unsafe-eval' outside the demo-mode build");

    const check = (els: Element[], allowed: string[], directive: string): void => {
      const used = new Set<string>();
      els.forEach((el, i) => {
        const h = sha256Source(el.textContent ?? "");
        used.add(h);
        if (!allowed.includes(h)) fail(`${describe(el, i)} has hash ${h}, which is not in ${directive}`);
      });
      for (const h of allowed.filter(isHash)) if (!used.has(h)) fail(`${directive} lists ${h}, but no inline element has that hash`);
    };
    check([...doc.querySelectorAll("script:not([src])")], scriptSrc, "script-src");
    check([...doc.querySelectorAll("style")], styleSrc, "style-src");

    for (const el of doc.querySelectorAll("*")) {
      for (const a of [...el.attributes]) {
        const tag = `<${el.tagName.toLowerCase()}>`;
        if (/^on/i.test(a.name)) fail(`${tag} has an inline event handler attribute ${a.name}= (AC 9)`);
        if (a.name.toLowerCase() === "style") fail(`${tag} has a style= attribute (AC 9)`);
        // eslint-disable-next-line no-control-regex
        if (/^javascript:/i.test(a.value.replace(/[\u0000- ]/g, ""))) fail(`${tag} has a javascript: URL in ${a.name}= (AC 9)`);
      }
    }
  } finally {
    dom.window.close();
  }
  return problems;
}

function listFiles(dir: string): string[] {
  const out: string[] = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...listFiles(p));
    else out.push(p);
  }
  return out;
}

/** Checks a whole output directory on disk: file list rules (AC 3, 12, 13) and every .html file (verifyHtml). */
export async function verifyOutput(outDir: string, o: { demo: boolean; gatewayOrigin: string | null }): Promise<{ problems: string[]; htmlFiles: number }> {
  const problems: string[] = [];
  const files = listFiles(outDir).map((p) => relative(outDir, p).split(sep).join("/"));
  const hasWasm = files.some((f) => f.endsWith(".wasm"));
  if (hasWasm && !o.demo) problems.push(`${files.filter((f) => f.endsWith(".wasm")).join(", ")}: a .wasm file outside the demo-mode build (NAV-017 AC 4, SEC-4B AC 3)`);
  for (const f of files) {
    if (/(?:^|\/)fixtures\//.test(f) || f.includes("label-rule")) problems.push(`${f}: test fixture in a production build (WS-13, SEC-4B AC 13)`);
    if ([".htaccess", ".htpasswd"].includes(basename(f))) problems.push(`${f}: server configuration file in a build output (NAV-017 AC 3)`);
  }
  const html = files.filter((f) => f.endsWith(".html"));
  if (!html.includes("index.html")) problems.push("index.html is missing from the output");
  for (const f of html) problems.push(...(await verifyHtml(readFileSync(join(outDir, f), "utf8"), { file: f, demo: o.demo, hasWasm, gatewayOrigin: o.gatewayOrigin })));
  return { problems, htmlFiles: html.length };
}

// ------------------------------------------------------------------------------------------------ the Vite plugin

/**
 * The build's connect-src gateway origin and the fail-closed rule (ADR-0018 §2): the static demo (B1) and demo mode (B2)
 * connect to the page origin only (NAV-002 AC 46 / AC 53, NAV-017 AC 42), so any other gateway fails the build.
 */
export function buildGatewayOrigin(env: Record<string, string | undefined>): string | null {
  const origin = gatewayConnectOrigin({ VITE_GATEWAY_BASE_URL: env["VITE_GATEWAY_BASE_URL"], VITE_STATIC_DEMO: env["VITE_STATIC_DEMO"] });
  const staticDemo = parseStaticDemo(env["VITE_STATIC_DEMO"]) ?? true;
  if (staticDemo && origin !== null) {
    throw new Error(
      `VITE_GATEWAY_BASE_URL=${JSON.stringify(env["VITE_GATEWAY_BASE_URL"])} points to ${origin}, but the static demo and demo-mode builds ` +
        `connect to the page origin only (NAV-002 AC 46 / AC 53, NAV-017 AC 42, SEC-4B AC 5): use "same-origin" or a path such as "/gw"`,
    );
  }
  return origin;
}

/** Build-only plugin. List it LAST in `plugins`, so its post hook sees the final HTML (ADR-0018 §1). */
export function navmnCsp(): Plugin {
  let demo = false;
  let gatewayOrigin: string | null = null;
  let outDir = "";
  let write = true;
  let log: (msg: string) => void = () => undefined;
  return {
    name: "navmn-csp",
    apply: "build",
    configResolved(config) {
      demo = parseDemoMode(config.env["VITE_DEMO_MODE"] as string | undefined) === true;
      gatewayOrigin = buildGatewayOrigin(config.env as Record<string, string | undefined>);
      outDir = resolve(config.root, config.build.outDir);
      write = config.build.write !== false;
      log = (msg) => config.logger.info(msg);
    },
    generateBundle: {
      order: "post",
      handler(_options, bundle) {
        const wasm = Object.keys(bundle).some((f) => f.endsWith(".wasm"));
        for (const [fileName, out] of Object.entries(bundle)) {
          if (out.type !== "asset" || !fileName.endsWith(".html")) continue;
          const source = typeof out.source === "string" ? out.source : new TextDecoder().decode(out.source);
          try {
            out.source = secureHtml(source, { demo, wasm, gatewayOrigin });
          } catch (e) {
            this.error(`navmn-csp: ${fileName}: ${(e as Error).message}`);
          }
        }
      },
    },
    async closeBundle(error?: Error) {
      if (error || !write) return;
      const { problems, htmlFiles } = await verifyOutput(outDir, { demo, gatewayOrigin });
      if (problems.length > 0) {
        throw new Error(`navmn-csp: the build output fails the SEC-4B checks (ADR-0018 §1):\n  - ${problems.join("\n  - ")}`);
      }
      log(`navmn: CSP and referrer policy verified in ${htmlFiles} HTML file(s) (SEC-4B)`);
    },
  };
}

// SEC-4B (ADR-0018 §1–§2): fixture tests for the CSP generator (secureHtml, buildPolicy) and the independent verifier
// (verifyHtml, verifyOutput). Story AC 1–6, 8, 9, 11, 13, 15; the B3 connect-src rule shared with src/config.ts.
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { gatewayConnectOrigin } from "../src/config";
import { buildGatewayOrigin, buildPolicy, parsePolicy, secureHtml, sha256Source, stripHtmlComments, verifyHtml, verifyOutput, type PolicyOptions } from "./csp";

const B1: PolicyOptions = { demo: false, wasm: false, gatewayOrigin: null };
const B2: PolicyOptions = { demo: true, wasm: true, gatewayOrigin: null };
const B3: PolicyOptions = { demo: false, wasm: false, gatewayOrigin: "http://localhost:8080" };

// Shaped like Vite's output for web/index.html: developer comment, inline tokens style, charset late, inline boot script.
const SOURCE = `<!doctype html>
<!--
  developer comment (WS-16)
-->
<html lang="mn">
  <head>
    <style id="design-tokens">:root{--a:#fff}
:root[data-theme="night"]{--a:#000}
</style>

    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width" />
    <title>T</title>
    <script type="module" crossorigin src="/assets/main.js"></script>
    <link rel="stylesheet" crossorigin href="/assets/main.css">
  </head>
  <body>
    <!-- NAV-004 route slot -->
    <div id="route"></div>
    <a href="https://www.openstreetmap.org/copyright">osm</a>
    <script>(function(c){document.title=c.t})({"t":"\\u003c/script>"});</script>
  </body>
</html>
`;

const opts = (o: PolicyOptions, hasWasm = o.wasm) => ({ file: "index.html", demo: o.demo, hasWasm, gatewayOrigin: o.gatewayOrigin });
const policyOf = (html: string): Map<string, string[]> => parsePolicy(/http-equiv="Content-Security-Policy" content="([^"]*)"/.exec(html)![1]!).directives;

describe("secureHtml: generator (AC 1–7, 11, 15)", () => {
  it.each([
    ["B1", B1],
    ["B2", B2],
    ["B3", B3],
  ] as const)("%s output passes the independent verifier", async (_n, o) => {
    expect(await verifyHtml(secureHtml(SOURCE, o), opts(o))).toEqual([]);
  });

  it("puts charset, policy and referrer first in <head>, strips every comment, keeps the source order of the rest", () => {
    const out = secureHtml(SOURCE, B1);
    expect(out).toMatch(/<head>\n {4}<meta charset="utf-8" \/>\n {4}<meta http-equiv="Content-Security-Policy" content="[^"]+" \/>\n {4}<meta name="referrer" content="same-origin" \/>\n {4}<style id="design-tokens">/);
    expect(out).not.toContain("<!--");
    expect(out.match(/<meta charset/g)).toHaveLength(1);
    expect(out.indexOf("<meta charset")).toBeLessThan(1024);
    expect(out.indexOf('name="viewport"')).toBeLessThan(out.indexOf("<title>"));
  });

  it("hashes the exact inline text: one script hash and one style hash, no 'unsafe-inline'", () => {
    const p = policyOf(secureHtml(SOURCE, B1));
    expect(p.get("script-src")).toEqual(["'self'", sha256Source('(function(c){document.title=c.t})({"t":"\\u003c/script>"});')]);
    expect(p.get("style-src")).toEqual(["'self'", sha256Source(':root{--a:#fff}\n:root[data-theme="night"]{--a:#000}\n')]);
    expect(JSON.stringify([...p])).not.toContain("unsafe-inline");
  });

  it("a changed boot script gets a new hash without any other edit (AC 10)", () => {
    const a = policyOf(secureHtml(SOURCE, B1)).get("script-src");
    const b = policyOf(secureHtml(SOURCE.replace("c.t", "c.t+''"), B1)).get("script-src");
    expect(a).not.toEqual(b);
  });

  it("policy matrix per build (ADR-0018 §2)", () => {
    const common = { scriptHashes: [], styleHashes: [] };
    expect(buildPolicy({ ...B1, ...common })).toBe(
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; connect-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'",
    );
    const b2 = parsePolicy(buildPolicy({ ...B2, ...common })).directives;
    expect(b2.get("script-src")).toEqual(["'self'", "'wasm-unsafe-eval'"]);
    expect(b2.get("media-src")).toEqual(["'self'", "data:"]);
    expect(b2.get("connect-src")).toEqual(["'self'"]);
    expect(parsePolicy(buildPolicy({ ...B3, ...common })).directives.get("connect-src")).toEqual(["'self'", "http://localhost:8080"]);
    // demo mode without a .wasm in the output: no 'wasm-unsafe-eval'
    expect(parsePolicy(buildPolicy({ ...B2, wasm: false, ...common })).directives.get("script-src")).toEqual(["'self'"]);
    for (const o of [B1, B2, B3]) {
      const p = buildPolicy({ ...o, ...common });
      for (const d of ["frame-ancestors", "report-uri", "report-to", "sandbox", "*", "'unsafe-"]) expect(p).not.toContain(d);
    }
  });

  it("refuses a hand-written policy or referrer meta in the source, and a charset other than utf-8", () => {
    expect(() => secureHtml(SOURCE.replace("<title>", '<meta http-equiv="content-security-policy" content="x"><title>'), B1)).toThrow(/already has a Content-Security-Policy/);
    expect(() => secureHtml(SOURCE.replace("<title>", '<meta name="referrer" content="no-referrer"><title>'), B1)).toThrow(/referrer/);
    expect(() => secureHtml(SOURCE.replace('charset="utf-8"', 'charset="windows-1251"'), B1)).toThrow(/utf-8/);
  });
});

describe("stripHtmlComments (AC 15)", () => {
  it("removes comments and their own lines, keeps <script>/<style> raw text unchanged", () => {
    const html = '<p>a</p>\n  <!-- x -->\n<p>b<!--y-->c</p>\n<script>var s="<!--not a comment-->";</script><style>/*<!--*/</style>';
    expect(stripHtmlComments(html)).toBe('<p>a</p>\n<p>bc</p>\n<script>var s="<!--not a comment-->";</script><style>/*<!--*/</style>');
    expect(() => stripHtmlComments("<p><!-- open")).toThrow(/unterminated/);
  });
});

describe("verifyHtml: the build fails on (AC 8, 9, 1, 3, 6, 11, 15)", () => {
  const good = secureHtml(SOURCE, B1);
  const expectFail = async (html: string, re: RegExp, o: PolicyOptions = B1, hasWasm?: boolean) => {
    const problems = await verifyHtml(html, opts(o, hasWasm));
    expect(problems.join("\n")).toMatch(re);
    for (const p of problems) expect(p.startsWith("index.html: ")).toBe(true);
  };

  it("an inline script added after the hash step (names the file and the element)", async () => {
    await expectFail(good.replace("</body>", "<script>window.__late=1</script></body>"), /inline <script> #2 in <body> "window.__late=1" has hash 'sha256-[^']+', which is not in script-src/);
  });
  it("an inline style added after the hash step", async () => {
    await expectFail(good.replace("</head>", "<style>body{}</style></head>"), /inline <style> #2 in <head> "body\{\}" has hash .* not in style-src/);
  });
  it("an inline script whose bytes changed by one character after hashing", async () => {
    await expectFail(good.replace("c.t})", "c.t })"), /inline <script> #1 .* not in script-src/);
  });
  it("a hash with no element (stale policy)", async () => {
    await expectFail(good.replace("<style id=\"design-tokens\">", '<style id="design-tokens">/**/'), /style-src lists 'sha256-[^']+', but no inline element has that hash/);
  });
  it("'wasm-unsafe-eval' without a .wasm in the output, and a .wasm without it", async () => {
    await expectFail(secureHtml(SOURCE, B2), /'wasm-unsafe-eval' is in script-src but the output has no .wasm file/, B2, false);
    await expectFail(secureHtml(SOURCE, { ...B2, wasm: false }), /has a .wasm file but script-src lacks 'wasm-unsafe-eval'/, B2, true);
    await expectFail(good.replace("script-src 'self'", "script-src 'self' 'wasm-unsafe-eval'"), /'wasm-unsafe-eval' outside the demo-mode build/, B1, true);
  });
  it.each([
    ["onclick=", '<div id="route" onclick="x()">', /inline event handler attribute onclick=/],
    ["javascript: URL", '<div id="route"><a href=" javascript:alert(1)">x</a>', /javascript: URL in href=/],
    ["style= attribute", '<div id="route" style="color:red">', /style= attribute/],
  ])("%s", async (_n, el, re) => {
    await expectFail(good.replace('<div id="route">', el), re);
  });
  it('a "<!--" anywhere in the file', async () => {
    await expectFail(good.replace("<body>", "<body><!-- x -->"), /contains "<!--"/);
  });
  it("a policy placed after an inline <style>", async () => {
    const moved = good.replace(/(\n {4}<meta http-equiv="Content-Security-Policy" content="[^"]+" \/>)([\s\S]*?<\/style>)/, "$2$1");
    expect(moved).not.toBe(good);
    await expectFail(moved, /<style> comes before the Content-Security-Policy meta/);
  });
  it("a second policy, and no policy", async () => {
    await expectFail(good.replace("<title>", `<meta http-equiv="Content-Security-Policy" content="default-src *"><title>`), /exactly 1 Content-Security-Policy meta, found 2/);
    await expectFail(good.replace(/<meta http-equiv="Content-Security-Policy"[^>]*>/, ""), /exactly 1 Content-Security-Policy meta, found 0/);
  });
  it.each(["frame-ancestors 'none'", "report-uri /r", "report-to r", "sandbox"])("a meta-ignored directive: %s", async (d) => {
    await expectFail(good.replace("form-action 'none'", `form-action 'none'; ${d}`), /is ignored in a meta policy/);
  });
  it.each(["'unsafe-inline'", "'unsafe-eval'", "'unsafe-hashes'", "*", "data:", "blob:", "https:", "http:", "localhost:8080"])("a forbidden script-src source: %s", async (s) => {
    await expectFail(good.replace("script-src 'self'", `script-src 'self' ${s}`), /script-src contains the source/);
  });
  it("a style-src 'unsafe-inline', a wildcard img-src, a connect-src host in B1, media-src outside demo mode", async () => {
    await expectFail(good.replace("style-src 'self'", "style-src 'self' 'unsafe-inline'"), /style-src contains the source 'unsafe-inline'/);
    await expectFail(good.replace("img-src 'self'", "img-src *"), /img-src contains the source \*/);
    await expectFail(good.replace("connect-src 'self'", "connect-src 'self' http://localhost:8080"), /connect-src must be "'self'"/);
    await expectFail(good.replace("worker-src 'self'", "worker-src 'self'; media-src 'self' data:"), /media-src is set outside the demo-mode build/);
  });
  it("a missing or wrong referrer meta, a charset that is not first", async () => {
    await expectFail(good.replace('<meta name="referrer" content="same-origin" />', ""), /exactly 1 referrer meta, found 0/);
    await expectFail(good.replace('content="same-origin"', 'content="unsafe-url"'), /referrer meta must be/);
    await expectFail(good.replace('<meta charset="utf-8" />', "").replace("<title>", '<meta charset="utf-8"><title>'), /first element of <head> is not <meta charset="utf-8">/);
  });
});

describe("verifyOutput: file list (AC 3, 12, 13)", () => {
  let dir = "";
  afterEach(() => dir && rmSync(dir, { recursive: true, force: true }));
  const out = (files: Record<string, string>): string => {
    dir = mkdtempSync(join(tmpdir(), "navmn-csp-"));
    for (const [f, c] of Object.entries(files)) {
      mkdirSync(dirname(join(dir, f)), { recursive: true });
      writeFileSync(join(dir, f), c);
    }
    return dir;
  };

  it("passes a clean B1 and B2 output", async () => {
    expect((await verifyOutput(out({ "index.html": secureHtml(SOURCE, B1), "assets/a.js": "" }), B1)).problems).toEqual([]);
    rmSync(dir, { recursive: true, force: true });
    expect((await verifyOutput(out({ "index.html": secureHtml(SOURCE, B2), "assets/f.wasm": "" }), B2)).problems).toEqual([]);
  });
  it("fails on the label-rule fixture, a .wasm outside demo mode and a server configuration file", async () => {
    const r = await verifyOutput(out({ "index.html": secureHtml(SOURCE, B1), "fixtures/label-rule.html": "", "assets/labelRule-x.js": "", "assets/f.wasm": "", ".htaccess": "" }), B1);
    const text = r.problems.join("\n");
    expect(text).toMatch(/fixtures\/label-rule.html: test fixture/);
    expect(text).toMatch(/assets\/f.wasm: a .wasm file outside the demo-mode build/);
    expect(text).toMatch(/\.htaccess: server configuration file/);
    expect(text).toMatch(/fixtures\/label-rule.html: contains no <meta charset>|fixtures\/label-rule.html: no <meta charset>/);
  });
});

describe("B3 connect-src follows the runtime gateway rule (src/config.ts, ADR-0018 §2)", () => {
  it.each([
    [undefined, "false", "http://localhost:8080"],
    ["", "false", "http://localhost:8080"],
    ["same-origin", "false", null],
    ["/", "false", null],
    ["/gw/", "false", null],
    ["http://localhost:8081/", "false", "http://localhost:8081"],
    ["https://127.0.0.1:8443/api/", "false", "https://127.0.0.1:8443"],
    [undefined, "true", null],
    ["same-origin", "true", null],
  ])("VITE_GATEWAY_BASE_URL=%j, VITE_STATIC_DEMO=%s → %s", (gw, sd, expected) => {
    expect(gatewayConnectOrigin({ VITE_GATEWAY_BASE_URL: gw, VITE_STATIC_DEMO: sd })).toBe(expected);
  });
  it("a value the policy cannot express fails the build", () => {
    expect(() => gatewayConnectOrigin({ VITE_GATEWAY_BASE_URL: "localhost:8080", VITE_STATIC_DEMO: "false" })).toThrow(/not an http\(s\) URL/);
    expect(() => gatewayConnectOrigin({ VITE_GATEWAY_BASE_URL: "//localhost:8080", VITE_STATIC_DEMO: "false" })).toThrow(/not an http\(s\) URL/);
  });
  it("B1 and B2 (static demo) fail closed on any gateway that is not the page origin", () => {
    expect(() => buildGatewayOrigin({ VITE_STATIC_DEMO: "true", VITE_GATEWAY_BASE_URL: "http://localhost:8080" })).toThrow(/connect to the page origin only/);
    expect(buildGatewayOrigin({ VITE_STATIC_DEMO: "true", VITE_GATEWAY_BASE_URL: "same-origin" })).toBeNull();
    expect(buildGatewayOrigin({ VITE_STATIC_DEMO: "false", VITE_GATEWAY_BASE_URL: "http://localhost:8080" })).toBe("http://localhost:8080");
  });
});

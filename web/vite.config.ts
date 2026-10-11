/// <reference types="vitest/config" />
import { basename } from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig, runnerImport, type Plugin } from "vite";
import { navmnCsp } from "./buildtools/csp";
import { demoModePlugins, isDemoMode } from "./buildtools/demoMode";
import { bootLoading, type BootConfig } from "./src/boot/bootLoading";
import { parseStaticDemo } from "./src/config";
import en from "./src/i18n/en.json";
import mn from "./src/i18n/mn.json";
import { DEFAULT_LANG } from "./src/i18n/i18n";
import { LANG_KEY, THEME_KEY } from "./src/prefs";
import { LOADING_DELAY_MS, LOADING_REVEAL_MS } from "./src/state/status";

// docs/design/tokens.json is the single source of truth for colours (owner: ux-designer).
// It is imported read-only through the @design alias; web/ never copies the values by hand.
const designDir = fileURLToPath(new URL("../docs/design", import.meta.url));
const webRoot = fileURLToPath(new URL(".", import.meta.url));
const repoRoot = fileURLToPath(new URL("..", import.meta.url));
const alias = { "@design": designDir };

const escapeHtml = (s: string): string => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
/** JSON that is safe inside an inline <script> (no "</script>"; U+2028/2029 are valid in ES2019+ strings). */
const inlineJson = (v: unknown): string => JSON.stringify(v).replace(/</g, "\\u003c");

/**
 * NAV-002 AC 37: index.html gets, before any module loads,
 *  - the design-token CSS variables (so the pill, the map earth colour and night mode are styled at once),
 *  - the default-language page title (D15, from src/i18n/mn.json),
 *  - the default-language loading text inside the pill, which index.html lays out transparent ("pending"),
 *  - a tiny classic script (src/boot/bootLoading.ts) that reveals the pill 270 ms after navigation start (Web
 *    Animations, start time pinned to navigation start) and switches it to the saved language.
 * All text comes from the resource files; index.html itself stays free of UI text (AC 32).
 */
function bootIndicator(): Plugin {
  const cfg: BootConfig = {
    delayMs: LOADING_DELAY_MS,
    revealMs: LOADING_REVEAL_MS,
    defaultLang: DEFAULT_LANG,
    langKey: LANG_KEY,
    themeKey: THEME_KEY,
    strings: {
      mn: { loading: mn["status.loading"], title: mn["app.title"] },
      en: { loading: en["status.loading"], title: en["app.title"] },
    },
  };
  // src/style/tokens.ts imports tokens.json through the @design alias, which the config bundler does not
  // resolve, so it is loaded through Vite's module runner (once per process).
  // NAV-017 demo mode adds the nav.* / demo.* colours as a second build-time style (SEC-4B: no runtime <style>, so the
  // Content-Security-Policy can hash it).
  let tokensCssText: Promise<{ ui: string; demo: string }> | null = null;
  const loadTokensCss = (): Promise<{ ui: string; demo: string }> =>
    (tokensCssText ??= runnerImport<typeof import("./src/style/tokens")>(
      fileURLToPath(new URL("./src/style/tokens.ts", import.meta.url)),
      { configFile: false, logLevel: "warn", resolve: { alias } },
    ).then((r) => ({ ui: r.module.tokensCss(), demo: r.module.demoTokensCss() })));
  let demo = false;
  return {
    name: "navmn-boot-indicator",
    configResolved(config) {
      demo = isDemoMode(config.mode, webRoot);
    },
    async transformIndexHtml(html, ctx) {
      if (basename(ctx.filename) !== "index.html") return html;
      const { ui: tokensCss, demo: demoTokensCss } = await loadTokensCss();
      return {
        html: html
          .replace("<title></title>", `<title>${escapeHtml(mn["app.title"])}</title>`)
          .replace('<span id="loading-text"></span>', `<span id="loading-text">${escapeHtml(mn["status.loading"])}</span>`),
        tags: [
          { tag: "style", attrs: { id: "design-tokens" }, children: tokensCss, injectTo: "head-prepend" },
          ...(demo ? [{ tag: "style", attrs: { id: "demo-tokens" }, children: demoTokensCss, injectTo: "head-prepend" as const }] : []),
          { tag: "script", children: `(${bootLoading.toString()})(${inlineJson(cfg)});`, injectTo: "body" },
        ],
      };
    },
  };
}

/**
 * NAV-002 AC 53–54: VITE_STATIC_DEMO must be a value src/config.ts understands. A typo fails the build instead of
 * silently producing a public site with search switched on. `npm run build:static-demo` (mode "static-demo", file
 * .env.static-demo) sets it.
 */
function staticDemoGuard(): Plugin {
  return {
    name: "navmn-static-demo-guard",
    configResolved(config) {
      const raw = config.env["VITE_STATIC_DEMO"] as string | undefined;
      const on = parseStaticDemo(raw);
      if (on === null) throw new Error(`VITE_STATIC_DEMO=${JSON.stringify(raw)} is not valid: use "true" or "false" (web/.env.example)`);
      if (on && config.command === "build") {
        config.logger.info("navmn: static demo build: search, reverse and routing are off (NAV-002 AC 53)");
      }
    },
  };
}

/**
 * SEC-4B AC 13 (WS-13): the NAV-002 AC 8 fixture page (fixtures/label-rule.html) is an input of the dev server only. It
 * stays in rollupOptions.input below so the dev server's dependency scan sees it, and is removed from every `vite build`
 * (static demo, demo mode, normal). `npm run dev` still serves /fixtures/label-rule.html (AC 14, TC-08-01).
 */
function noFixturesInBuild(): Plugin {
  return {
    name: "navmn-no-fixtures-in-build",
    apply: "build",
    configResolved(config) {
      const input = config.build.rollupOptions.input;
      if (input && typeof input === "object" && !Array.isArray(input)) delete (input as Record<string, string>).labelRule;
    },
  };
}

// The config stays a plain object (tests/e2e wrap it with `{ ...base }`). Demo mode is decided per Vite mode by the
// plugins in buildtools/demoMode.ts (NAV-017, ADR-0011 §2): base, the compile-time switch and the demo-only settings.
export default defineConfig({
  // navmnCsp (SEC-4B, ADR-0018 §1) must stay LAST: its post hook hashes the final HTML of every other plugin.
  plugins: [
    bootIndicator(),
    staticDemoGuard(),
    ...demoModePlugins({ webRoot, repoRoot, parseStaticDemo: (raw) => parseStaticDemo(raw) }),
    noFixturesInBuild(),
    navmnCsp(),
  ],
  resolve: { alias },
  server: {
    host: "localhost",
    port: 5173,
    strictPort: true,
    fs: { allow: [".", designDir] },
  },
  preview: {
    host: "localhost",
    port: 4173,
    strictPort: true,
  },
  build: {
    target: "es2022",
    chunkSizeWarningLimit: 1600,
    rollupOptions: {
      input: {
        main: fileURLToPath(new URL("./index.html", import.meta.url)),
        labelRule: fileURLToPath(new URL("./fixtures/label-rule.html", import.meta.url)),
      },
    },
  },
  test: {
    include: ["src/**/*.test.ts", "scripts/**/*.test.mjs", "buildtools/**/*.test.ts"],
    environment: "node",
  },
});

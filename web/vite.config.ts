/// <reference types="vitest/config" />
import { basename } from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv, runnerImport, type Plugin } from "vite";
import { demoHtml, demoModeGuard, demoRoutes, demoRoutesStub, parseDemoMode } from "./buildtools/demoMode";
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
  let tokensCssText: Promise<string> | null = null;
  const loadTokensCss = (): Promise<string> =>
    (tokensCssText ??= runnerImport<typeof import("./src/style/tokens")>(
      fileURLToPath(new URL("./src/style/tokens.ts", import.meta.url)),
      { configFile: false, logLevel: "warn", resolve: { alias } },
    ).then((r) => r.module.tokensCss()));
  return {
    name: "navmn-boot-indicator",
    async transformIndexHtml(html, ctx) {
      if (basename(ctx.filename) !== "index.html") return html;
      const tokensCss = await loadTokensCss();
      return {
        html: html
          .replace("<title></title>", `<title>${escapeHtml(mn["app.title"])}</title>`)
          .replace('<span id="loading-text"></span>', `<span id="loading-text">${escapeHtml(mn["status.loading"])}</span>`),
        tags: [
          { tag: "style", attrs: { id: "design-tokens" }, children: tokensCss, injectTo: "head-prepend" },
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

export default defineConfig(({ mode }) => {
  // NAV-017 (ADR-0011 §2): demo mode is decided at build time. The guard plugin rejects invalid values; here an invalid
  // value counts as off, so the switch below can never turn demo code on by accident.
  const env = loadEnv(mode, webRoot, "VITE_");
  const demo = parseDemoMode(env.VITE_DEMO_MODE ?? process.env.VITE_DEMO_MODE) === true;
  return {
    plugins: [
      bootIndicator(),
      staticDemoGuard(),
      demoModeGuard((raw) => parseStaticDemo(raw)),
      ...(demo ? [demoHtml(), demoRoutes(webRoot, repoRoot)] : [demoRoutesStub()]),
    ],
    // The only branch into the demo code (src/main.ts) is on this compile-time constant, so every other build contains
    // no demo chunk, no WASM and no route data (NAV-017 AC 4).
    define: { __NAVMN_DEMO_MODE__: JSON.stringify(demo) },
    // Demo-mode build only: relative URLs, so the output works from any sub-folder without a rebuild (AC 2). Public
    // builds keep "/" (ADR-0004 §4).
    base: demo ? "./" : "/",
    resolve: { alias },
    server: {
      host: "localhost",
      port: 5173,
      strictPort: true,
      fs: { allow: [".", designDir, ...(demo ? [repoRoot] : [])] },
    },
    preview: {
      host: "localhost",
      port: 4173,
      strictPort: true,
    },
    build: {
      target: "es2022",
      chunkSizeWarningLimit: 1600,
      // Demo mode: no runtime-created <link crossorigin> (preloads, split CSS), ADR-0011 §2 / W5, W7.
      ...(demo ? { modulePreload: false as const, cssCodeSplit: false } : {}),
      rollupOptions: {
        input: (demo
          ? { main: fileURLToPath(new URL("./index.html", import.meta.url)) }
          : {
              main: fileURLToPath(new URL("./index.html", import.meta.url)),
              labelRule: fileURLToPath(new URL("./fixtures/label-rule.html", import.meta.url)),
            }) as Record<string, string>,
      },
    },
    test: {
      include: ["src/**/*.test.ts", "scripts/**/*.test.mjs"],
      environment: "node",
    },
  };
});

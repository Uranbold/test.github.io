/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * Gateway base URL (default http://localhost:8080). "same-origin" or "/" = the page's own origin at runtime.
   * See .env.example.
   */
  readonly VITE_GATEWAY_BASE_URL?: string;
  /** "true" = static public demo: map only, search / reverse / routing off (NAV-002 AC 53). See .env.example. */
  readonly VITE_STATIC_DEMO?: string;
  /** "true" = NAV-017 demo-mode build (needs VITE_STATIC_DEMO=true). Read at build time only (vite.config.ts). */
  readonly VITE_DEMO_MODE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

/** NAV-017: true only in `npm run build:demo-mode` (vite.config.ts `define`, ADR-0011 §2). */
declare const __NAVMN_DEMO_MODE__: boolean;

declare module "virtual:navmn-demo-routes" {
  const summaries: import("../buildtools/demoMode").DemoRouteSummary[];
  export default summaries;
}

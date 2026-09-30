/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * Gateway base URL (default http://localhost:8080). "same-origin" or "/" = the page's own origin at runtime.
   * See .env.example.
   */
  readonly VITE_GATEWAY_BASE_URL?: string;
  /** "true" = static public demo: map only, search / reverse / routing off (NAV-002 AC 53). See .env.example. */
  readonly VITE_STATIC_DEMO?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

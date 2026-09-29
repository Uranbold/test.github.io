/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Gateway base URL (default http://localhost:8080). See .env.example. */
  readonly VITE_GATEWAY_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

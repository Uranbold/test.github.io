/// <reference types="vitest/config" />
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

// docs/design/tokens.json is the single source of truth for colours (owner: ux-designer).
// It is imported read-only through the @design alias; web/ never copies the values by hand.
const designDir = fileURLToPath(new URL("../docs/design", import.meta.url));

export default defineConfig({
  resolve: {
    alias: { "@design": designDir },
  },
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
    include: ["src/**/*.test.ts", "scripts/**/*.test.mjs"],
    environment: "node",
  },
});

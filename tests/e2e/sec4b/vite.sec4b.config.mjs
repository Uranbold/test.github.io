// Test-only wrapper around web/vite.config.ts for the SEC-4B builds (B1 static demo, B2 demo mode, B3 normal), each
// built into a temporary directory and served by `vite preview`. Same config as the app (all plugins, navmn-csp last);
// only a separate dependency cache per instance, so these builds never write to the optimiser cache of another suite's
// Vite server. Changes nothing in web/.
import os from 'node:os';
import path from 'node:path';
import base from '../../../web/vite.config.ts';

export default {
  ...base,
  cacheDir: path.join(os.tmpdir(), `navmn-sec4b-vite-${process.env.SEC4B_INSTANCE ?? 'x'}`),
};

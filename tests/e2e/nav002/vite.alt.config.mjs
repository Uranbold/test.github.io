// Test-only wrapper around web/vite.config.ts for the extra NAV-002 dev servers (ports 5174-5176).
// Same config as the app; only a separate dependency cache per port so parallel Vite instances never
// write to the same optimiser cache. Does not change anything in web/.
import os from 'node:os';
import path from 'node:path';
import base from '../../../web/vite.config.ts';

export default {
  ...base,
  cacheDir: path.join(os.tmpdir(), `navmn-nav002-vite-${process.env.NAV002_ALT_PORT ?? 'alt'}`),
};

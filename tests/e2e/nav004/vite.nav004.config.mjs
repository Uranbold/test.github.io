// Test-only wrapper around web/vite.config.ts for the NAV-004 dev server (port 5181) and the static-build server
// (port 5182). Same config as the app; only a separate dependency cache per instance, so this Vite never writes to the
// optimiser cache of the NAV-002/NAV-003 servers that may run in parallel on 5173-5176. Does not change anything in web/.
import os from 'node:os';
import path from 'node:path';
import base from '../../../web/vite.config.ts';

export default {
  ...base,
  cacheDir: path.join(os.tmpdir(), `navmn-nav004-vite-${process.env.NAV004_INSTANCE ?? 'dev'}`),
};

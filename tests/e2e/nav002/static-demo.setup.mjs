// Global setup for the NAV-002 static demo checks (AC 51–54, D44). Never touches the shared dev stack.
// 1. Builds web/ in the documented static-demo mode (package.json "build:static-demo" = vite build --mode static-demo,
//    here with an --outDir inside tests/e2e/test-results). VITE_* variables are removed from the environment so the
//    committed web/.env.static-demo decides, as on the PO's machine.
// 2. Puts a basemap copy at <site>/tiles/basemap.pmtiles (default: a small UB extract made with go-pmtiles).
// 3. Starts Caddy (Docker) as a plain static file server with Range support on 127.0.0.1:18088 and returns a teardown.
import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, statSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../../', import.meta.url));
const WEB = ROOT + 'web/';
const SD = ROOT + 'tests/e2e/test-results/nav002-static-demo/';
const SITE = SD + 'site/';
const CADDYFILE = fileURLToPath(new URL('./static-demo.Caddyfile', import.meta.url));
const SRC_TILES = ROOT + 'backend/data/tiles/basemap.pmtiles';
const UB_TILES = SD + 'tiles-ub/basemap.pmtiles';
const UB_BBOX = '106.55,47.75,107.25,48.05';
const PORT = Number(process.env.NAV002_SD_PORT ?? 18088);
const NAME = 'qa-nav002-static-demo';
const CADDY = 'caddy:2.10.2-alpine';
const PMTILES = 'ghcr.io/protomaps/go-pmtiles:v1.31.2';

const sh = (cmd, args, opts = {}) => execFileSync(cmd, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...opts });

export default async function setup() {
  mkdirSync(SD, { recursive: true });
  // 1. Build
  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !k.startsWith('VITE_')));
  const log = sh('npx', ['vite', 'build', '--mode', 'static-demo', '--outDir', SITE, '--emptyOutDir'], { cwd: WEB, env: { ...env, FORCE_COLOR: '0' }, timeout: 300_000 });
  writeFileSync(SD + 'build.log', log);
  if (!existsSync(SITE + 'index.html')) throw new Error('static demo build produced no index.html');
  // 2. Tiles
  const which = process.env.NAV002_SD_TILES ?? 'ub';
  let tiles = SRC_TILES;
  if (which === 'ub') {
    if (!existsSync(UB_TILES)) {
      mkdirSync(SD + 'tiles-ub', { recursive: true });
      sh('docker', ['run', '--rm', '-v', `${ROOT}backend/data/tiles:/in:ro`, '-v', `${SD}tiles-ub:/out`, PMTILES, 'extract', '/in/basemap.pmtiles', '/out/basemap.pmtiles', `--bbox=${UB_BBOX}`], { timeout: 300_000 });
    }
    tiles = UB_TILES;
  }
  if (!existsSync(tiles)) throw new Error(`basemap archive missing: ${tiles}`);
  mkdirSync(SITE + 'tiles', { recursive: true });
  copyFileSync(tiles, SITE + 'tiles/basemap.pmtiles');
  process.env.NAV002_SD_ARCHIVE_BYTES = String(statSync(SITE + 'tiles/basemap.pmtiles').size);
  process.env.NAV002_SD_TILES_KIND = which;
  process.env.NAV002_SD_CONTAINER = NAME;
  process.env.NAV002_SD_BUILD_LOG = SD + 'build.log';
  // 3. Static server
  try { sh('docker', ['rm', '-f', NAME]); } catch {}
  sh('docker', ['run', '-d', '--rm', '--name', NAME, '-p', `127.0.0.1:${PORT}:80`, '-v', `${SITE}:/srv:ro`, '-v', `${CADDYFILE}:/etc/caddy/Caddyfile:ro`, CADDY]);
  const t0 = Date.now();
  for (;;) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/`);
      if (r.status === 200) break;
    } catch {}
    if (Date.now() - t0 > 30_000) throw new Error(`static server on ${PORT} did not answer 200`);
    await new Promise((r) => setTimeout(r, 300));
  }
  return async () => {
    if (process.env.NAV002_SD_KEEP === '1') return;
    try { writeFileSync(SD + 'access.log', sh('docker', ['logs', NAME])); } catch {}
    try { sh('docker', ['rm', '-f', NAME]); } catch {}
  };
}

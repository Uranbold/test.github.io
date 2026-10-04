// NAV-017 local stand-in for the PO's shared web hosting (story AC 2, 3, 4, 7; ADR-0011 §9). QA only.
// Story: docs/requirements/stories/NAV-017-web-demo-mode-replay.md. Test plan: docs/qa/test-plans/NAV-017.md.
//
//   node nav017/site.mjs start   builds web/ with the DOCUMENTED commands (npm run build:demo-mode, build:static-demo,
//                                build), lays out a web root and starts Caddy (Docker) on 127.0.0.1:18097
//   node nav017/site.mjs stop    stops Caddy, keeps its access log, deletes the state file
//
// Web root layout (mirrors the hosting in web/README.md › Demo mode):
//   /                      the public static demo build (dist-static-demo), as on the PO's site (D44)
//   /tiles/basemap.pmtiles UB extract of the NAV-001 archive (outside every demo folder, ADR-0011 §4)
//   /demo-a/               the demo-mode build (dist-demo-mode contents), folder name 1
//   /x/y/demo-b/           the same files, two levels deep, folder name 2 (AC 2: no rebuild)
//
// Every folder is served WITHOUT a password, as the PO's demo folder is since D107/D116 (public, `noindex`).
// Retired 2026-10-04: the `/locked-demo/` folder behind HTTP Basic auth with random throw-away credentials (ADR-0011 §9
// stand-in for hPanel "Password protect directories"). Its only test (picker.test.mjs, 401 without / 200 with
// credentials) was replaced by the public-folder test, so no test needs it; it is in git history if ever needed again.
// No real host name, folder name or password is used or written anywhere (D35, CLAUDE.md rule 9). The shared dev stack
// (http://localhost:8080) is never touched.
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, copyFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export const ROOT = fileURLToPath(new URL('../../../', import.meta.url));
export const WEB = ROOT + 'web/';
export const OUT = ROOT + 'tests/e2e/test-results/nav017/';
export const SITE = OUT + 'site/';
export const STATE = OUT + 'state.json';
export const PORT = Number(process.env.NAV017_PORT ?? 18097);
export const ORIGIN = `http://127.0.0.1:${PORT}`;
export const FOLDERS = { a: '/demo-a/', b: '/x/y/demo-b/' };
const NAME = 'qa-nav017-site';
const CADDY = 'caddy:2.10.2-alpine';
const PMTILES = 'ghcr.io/protomaps/go-pmtiles:v1.31.2';
const SRC_TILES = ROOT + 'backend/data/tiles/basemap.pmtiles';
const UB_TILES_NAV002 = ROOT + 'tests/e2e/test-results/nav002-static-demo/tiles-ub/basemap.pmtiles';
const UB_TILES = OUT + 'tiles-ub/basemap.pmtiles';
const UB_BBOX = '106.55,47.75,107.25,48.05';

const sh = (cmd, args, opts = {}) => execFileSync(cmd, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 << 20, ...opts });
/** The PO's shell: no VITE_* variables, so the committed .env.<mode> files decide (AC 1). */
const cleanEnv = () => ({ ...Object.fromEntries(Object.entries(process.env).filter(([k]) => !k.startsWith('VITE_'))), FORCE_COLOR: '0', NO_COLOR: '1' });

function npmBuild(script, logName) {
  let log;
  try {
    log = sh('npm', ['run', script], { cwd: WEB, env: cleanEnv(), timeout: 600_000, stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (e) {
    writeFileSync(OUT + logName, String(e.stdout ?? '') + String(e.stderr ?? ''));
    throw new Error(`npm run ${script} failed, see ${OUT + logName}`);
  }
  writeFileSync(OUT + logName, log);
}

export async function start() {
  mkdirSync(OUT, { recursive: true });
  if (process.env.NAV017_SKIP_BUILD !== '1') {
    npmBuild('build:demo-mode', 'build-demo-mode.log');
    npmBuild('build:static-demo', 'build-static-demo.log');
    npmBuild('build', 'build-normal.log');
  }
  for (const d of ['dist-demo-mode', 'dist-static-demo', 'dist']) if (!existsSync(WEB + d + '/index.html')) throw new Error(`web/${d}/index.html missing`);
  rmSync(SITE, { recursive: true, force: true });
  mkdirSync(SITE, { recursive: true });
  cpSync(WEB + 'dist-static-demo/', SITE, { recursive: true });
  for (const f of Object.values(FOLDERS)) cpSync(WEB + 'dist-demo-mode/', SITE + f.slice(1), { recursive: true });
  // Tiles: the small UB extract (all three demo routes are inside the bbox).
  let tiles = existsSync(UB_TILES_NAV002) ? UB_TILES_NAV002 : UB_TILES;
  if (!existsSync(tiles)) {
    mkdirSync(OUT + 'tiles-ub', { recursive: true });
    sh('docker', ['run', '--rm', '-v', `${ROOT}backend/data/tiles:/in:ro`, '-v', `${OUT}tiles-ub:/out`, PMTILES, 'extract', '/in/basemap.pmtiles', '/out/basemap.pmtiles', `--bbox=${UB_BBOX}`], { timeout: 300_000 });
    tiles = UB_TILES;
  }
  if (!existsSync(tiles)) throw new Error(`no basemap archive (${SRC_TILES} extract failed)`);
  mkdirSync(SITE + 'tiles', { recursive: true });
  copyFileSync(tiles, SITE + 'tiles/basemap.pmtiles');
  const caddyfile = `{
\tadmin off
\tauto_https off
}
:80 {
\troot * /srv
\tfile_server
\tlog {
\t\toutput stdout
\t\tformat json
\t}
}
`;
  writeFileSync(OUT + 'Caddyfile', caddyfile);
  try { sh('docker', ['rm', '-f', NAME]); } catch {}
  sh('docker', ['run', '-d', '--rm', '--name', NAME, '-p', `127.0.0.1:${PORT}:80`, '-v', `${SITE}:/srv:ro`, '-v', `${OUT}Caddyfile:/etc/caddy/Caddyfile:ro`, CADDY]);
  const t0 = Date.now();
  for (;;) {
    try { if ((await fetch(`${ORIGIN}${FOLDERS.a}`)).status === 200) break; } catch {}
    if (Date.now() - t0 > 30_000) throw new Error(`static server on ${PORT} did not answer 200`);
    await new Promise((r) => setTimeout(r, 300));
  }
  writeFileSync(STATE, JSON.stringify({ origin: ORIGIN, folders: FOLDERS, container: NAME, startedAt: new Date().toISOString() }));
  console.log(`[nav017] site up on ${ORIGIN} (${Object.values(FOLDERS).join(', ')})`);
}

export function stop() {
  try { writeFileSync(OUT + 'access.log', sh('docker', ['logs', NAME])); } catch {}
  try { sh('docker', ['rm', '-f', NAME]); } catch {}
  rmSync(STATE, { force: true });
  rmSync(OUT + 'Caddyfile', { force: true });
  console.log('[nav017] site stopped');
}

export function state() {
  if (!existsSync(STATE)) throw new Error('NAV-017 site not running: run `node nav017/site.mjs start` (or ./nav017/run.sh)');
  return JSON.parse(readFileSync(STATE, 'utf8'));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const cmd = process.argv[2];
  if (cmd === 'start') await start();
  else if (cmd === 'stop') stop();
  else { console.error('usage: node nav017/site.mjs start|stop'); process.exit(2); }
}

// NAV-017 A (AC 1, 3, 4, 5, 6, 7, 36) and data (AC 16, 44, manifest): checks on the build outputs, the repository, the
// README and the demo route data. No browser. The outputs are the ones nav017/site.mjs built with the DOCUMENTED
// commands (npm run build:demo-mode / build:static-demo / build) in a shell without VITE_* variables.
// Runs once (Chromium project only); the WebKit project skips this file.
import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { OUT, WEB } from './site.mjs';
import { ROOT, ROUTES, G4, RouteOracle, haversine, readGpx, readJson } from './helpers.mjs';

test.beforeEach(({}, ti) => test.skip(ti.project.name !== 'chromium-iphone', 'build and data checks run once (no browser)'));

const files = (dir) => readdirSync(dir, { recursive: true, withFileTypes: true }).filter((d) => d.isFile()).map((d) => relative(dir, join(d.parentPath ?? d.path, d.name)));
const text = (dir, f) => readFileSync(join(dir, f), 'utf8');
const DEMO = WEB + 'dist-demo-mode';
const STATIC = WEB + 'dist-static-demo';
const NORMAL = WEB + 'dist';
const R1_GEOM = readJson(ROUTES.R1.route).routes[0].geometry;
const R1_SUB = R1_GEOM.slice(200, 240); // a 40-character substring of the R1 route geometry (AC 4)
const SERVER_FILES = /(^|\/)(\.htaccess|\.htpasswd|\.user\.ini|web\.config|nginx\.conf|_headers|_redirects|\.well-known)(\/|$)/i;

test.describe('A. Build, hosting and protection', () => {
  test('AC1: npm run build:demo-mode writes dist-demo-mode/, logs one "demo mode on; search, reverse and routing are off" line, uses VITE_STATIC_DEMO=true and a demo key listed in .env.example without a hostname; README names command and folder', () => {
    const log = readFileSync(OUT + 'build-demo-mode.log', 'utf8');
    const lines = log.split('\n').filter((l) => /demo mode/i.test(l) && /on/.test(l) && /search, reverse and routing are off/.test(l));
    expect(lines, log).toHaveLength(1);
    expect(existsSync(DEMO + '/index.html')).toBe(true);
    const pkg = JSON.parse(readFileSync(WEB + 'package.json', 'utf8'));
    expect(pkg.scripts['build:demo-mode']).toMatch(/--outDir dist-demo-mode\b/);
    const envDemo = readFileSync(WEB + '.env.demo-mode', 'utf8');
    expect(envDemo).toMatch(/^VITE_STATIC_DEMO=true$/m);
    expect(envDemo).toMatch(/^VITE_DEMO_MODE=true$/m);
    const example = readFileSync(WEB + '.env.example', 'utf8');
    const line = example.split('\n').find((l) => l.startsWith('VITE_DEMO_MODE='));
    expect(line, '.env.example lists VITE_DEMO_MODE').toBeTruthy();
    expect(line).not.toMatch(/[a-z0-9-]+\.(com|net|org|mn|io|app|dev)\b|https?:\/\//i);
    // the static public build must not turn demo mode on
    expect(readFileSync(WEB + '.env.static-demo', 'utf8')).toMatch(/^VITE_DEMO_MODE=false$/m);
    const readme = readFileSync(WEB + 'README.md', 'utf8');
    expect(readme).toContain('npm run build:demo-mode');
    expect(readme).toContain('dist-demo-mode/');
  });

  test('AC3: demo output has no .htaccess/.htpasswd or other server configuration file; index.html has <meta name="robots" content="noindex, nofollow">', () => {
    const list = files(DEMO);
    expect(list.filter((f) => SERVER_FILES.test(f) || f.split('/').some((p) => p.startsWith('.')))).toEqual([]);
    const html = readFileSync(DEMO + '/index.html', 'utf8');
    expect(html).toMatch(/<meta\s+name="robots"\s+content="noindex, nofollow"\s*\/?>/);
    // ADR-0011 §2 (Safari + Basic auth): no crossorigin attribute, relative asset URLs only
    expect(html).not.toMatch(/\scrossorigin\b/);
    for (const m of html.matchAll(/\s(?:src|href)="([^"]+)"/g)) {
      const u = m[1];
      if (/^https:\/\/www\.openstreetmap\.org\//.test(u) || u.startsWith('#') || u.startsWith('data:')) continue;
      expect(u, `asset URL ${u} must be relative`).not.toMatch(/^(\/|https?:)/);
    }
  });

  for (const [name, dir] of [['build:static-demo', STATIC], ['build', NORMAL]]) {
    test(`AC4: npm run ${name} output contains no demo-mode code, picker, fixture or track data (0 "demo-" test ids, 0 demo-routes files, 0 R1 geometry substring, no WASM)`, () => {
      const list = files(dir);
      expect(list.filter((f) => f.includes('demo-routes'))).toEqual([]);
      expect(list.filter((f) => f.endsWith('.wasm'))).toEqual([]);
      const hits = [];
      for (const f of list) {
        if (f.startsWith('fonts/') || /\.(png|pbf|jpg)$/.test(f)) continue;
        const t = text(dir, f);
        if (/["'`]demo-[a-z]/.test(t)) hits.push(`${f}: "demo-" test-id prefix`);
        if (t.includes(R1_SUB)) hits.push(`${f}: R1 geometry`);
        // The shared i18n resource file (demo.mode, nav.*, voice.* values) is bundled into every build; that is resource
        // text, not demo code (observation in the QA report). Demo code markers:
        if (/NavigationController|demoMain|virtual:navmn-demo-routes|ferrostar_bg/.test(t)) hits.push(`${f}: demo code`);
        if (/G1\.gpx|p1-p3-car-mn|voice-golden/.test(t)) hits.push(`${f}: fixture name`);
      }
      expect(hits).toEqual([]);
    });
  }

  test('AC4 positive control: the same scans DO find the prefix, the R1 geometry and the WASM in the demo-mode output', () => {
    const list = files(DEMO);
    expect(list.filter((f) => f.endsWith('.wasm')).length).toBe(1);
    expect(list.filter((f) => /^demo-routes\/r[123]\.json$/.test(f)).sort()).toEqual(['demo-routes/r1.json', 'demo-routes/r2.json', 'demo-routes/r3.json']);
    expect(list.some((f) => /["'`]demo-[a-z]/.test(text(DEMO, f)))).toBe(true);
    expect(text(DEMO, 'demo-routes/r1.json')).toContain(R1_SUB);
  });

  test('ADR-0011 §10: the demo chunk plus WASM add ≤ 1.5 MB (uncompressed) over the static-demo build (fonts, sprites and route data excluded)', () => {
    const size = (dir) => files(dir).filter((f) => !/^(fonts|sprites|demo-routes)\//.test(f)).reduce((a, f) => a + statSync(join(dir, f)).size, 0);
    const extra = size(DEMO) - size(STATIC);
    expect(extra, `${(extra / 1e6).toFixed(2)} MB`).toBeLessThanOrEqual(1.5e6);
  });

  // D107/D116 change (2026-10-04): AC 5 and AC 48 now describe a PUBLIC demo folder (no password, `noindex` kept). The
  // old expectations (hPanel password step, `<username>`, 401 without / 200 with credentials, D74 password holders,
  // "password prompt" checklist item) are replaced by the new AC 5 items below, plus negative checks that the password
  // instructions are gone. Every other expectation of the old test is kept.
  test('AC5 (D107/D116): web/README.md "Demo mode (NAV-017)" documents build, upload of the contents, tiles + Range check, the public folder without a password (D107, D116, D17 not covering the demo) with noindex and the badge kept, after-upload checks (200 without credentials, noindex, 206), supported browsers and the real-iPhone checklist, with placeholders only and no password step', () => {
    const readme = readFileSync(WEB + 'README.md', 'utf8');
    const i = readme.indexOf('## Demo mode (NAV-017)');
    expect(i).toBeGreaterThan(0);
    const sec = readme.slice(i, readme.indexOf('\n## ', i + 10));
    for (const s of ['<demo-host>', '<demo-folder>', 'npm run build:demo-mode', 'dist-demo-mode/', '**contents**', '/tiles/basemap.pmtiles', 'Range', '206', 'Content-Range', 'Content-Encoding', '200', 'D107', 'D116', 'D17', 'noindex', '«Туршилтын горим»', 'iPhone Safari', 'D14']) {
      expect(sec, `README demo section mentions ${s}`).toContain(s);
    }
    expect(sec).toMatch(/must not delete `<demo-folder>\/`/);
    expect(sec, 'public folder without a password (D107)').toMatch(/\*\*public, without a password\*\*/);
    expect(sec, 'D17 does not cover the demo (D116)').toMatch(/D17 does not cover this demo/);
    expect(sec).toMatch(/never\*\*\s+written into the repo/);
    // after-upload checks (AC 5): 200 without credentials, the noindex meta in the served page, 206 for the tile archive
    const c = sec.indexOf('Checks after every upload');
    expect(c, 'after-upload checks block').toBeGreaterThan(0);
    const checks = sec.slice(c, sec.indexOf('\n### ', c));
    expect(checks).toMatch(/\*\*200\*\*\s+without credentials/);
    expect(checks).toContain('name="robots" content="noindex, nofollow"');
    expect(checks).toMatch(/\*\*206\*\*/);
    // the password instructions are gone (D107): no credential placeholder, no 401 expectation, no password holders,
    // and "Password protect directories" appears only as "Do **not** set ..."
    expect(sec).not.toContain('<username>');
    expect(sec).not.toMatch(/\b401\b/);
    expect(sec).not.toMatch(/PO and the team members the PO chooses|who may (get|receive) the password/i);
    expect(sec.replace(/Do \*\*not\*\* set "Password protect directories"/g, '')).not.toContain('Password protect directories');
    // real-iPhone checklist covers the AC 48 items (password prompt replaced by "without a password prompt" + 200/noindex)
    for (const s of ['iOS version', 'without a password prompt', 'noindex', 'Spoken Content', 'Mongolian voice', 'English speech', 'without an extra tap', 'silent switch', '787 s', 'Lock the phone', 'Safe areas', 'VoiceOver', 'R1']) expect(sec, `checklist item ${s}`).toContain(s);
    // placeholders only: no real host, no credentials
    expect(sec).not.toMatch(/https?:\/\/(?!<demo-host>|www\.openstreetmap\.org|localhost)[a-z0-9.-]+\.[a-z]{2,}/i);
    expect(sec).not.toMatch(/-u\s+[A-Za-z0-9_]+:[^\s]+/);
  });

  test('AC6: repository has no .htpasswd, no AuthUserFile/AuthName directive, no password value, no host name of a Hostinger site, no IP outside loopback/documentation ranges (pre-existing documented exceptions listed); .gitignore covers dist-demo-mode/', () => {
    const tracked = execFileSync('git', ['ls-files', '-co', '--exclude-standard'], { cwd: ROOT, encoding: 'utf8', maxBuffer: 64 << 20 }).split('\n').filter(Boolean);
    expect(tracked.filter((f) => /(^|\/)\.htpasswd$/.test(f))).toEqual([]);
    const BIN = /\.(png|jpg|jpeg|webp|ico|pbf|pmtiles|wasm|zip|gz|zst|ttf|otf|woff2?|pdf|jar|apk|aab|keystore|jks|so|dylib|bin|mbtiles|gpkg|crate|tgz)$/i;
    // Pre-existing IPv4 literals, each reviewed (not the PO's site, not added by NAV-017). Any other one fails.
    const KNOWN_IP = new Map([
      ['10.0.2.2', 'Android emulator alias of the host loopback'],
      ['172.30.81.0', 'NAV-008 staging Docker network (private)'], ['172.30.81.2', 'NAV-008 staging Docker network (private)'], ['172.30.81.8', 'NAV-008 staging Docker network (private)'],
      ['172.18.0.4', 'NAV-003/NAV-008 log-scan fixture (Docker private address)'],
      ['9.4.51.1', 'version string (Jetty) in a log fixture'], ['21.0.12.1', 'version string (JDK) in a log fixture'],
      ['4.1.1.4', 'version string (Maven com.google.android:annotations) in the SEC-4B Gradle verification metadata and the QA notes about it'],
      ['27.123.215.206', 'NAV-008 hosting spike: public traceroute hop (MobiCom), not ours'], ['180.149.98.146', 'NAV-008 hosting spike: RIPE Atlas anchor (Gemnet), not ours'],
      ['104.238.161.230', 'NAV-008 hosting spike: RIPE Atlas anchor (Vultr Tokyo), not ours'], ['103.50.204.94', 'NAV-008 hosting spike: cloud.mn reference, not ours'],
    ]);
    const allowedIp = (ip) => /^127\./.test(ip) || ip === '0.0.0.0' || /^(192\.0\.2|198\.51\.100|203\.0\.113)\./.test(ip) || KNOWN_IP.has(ip);
    const bad = [];
    for (const f of tracked) {
      if (BIN.test(f) || f.startsWith('tests/e2e/test-results/') || !existsSync(ROOT + f) || statSync(ROOT + f).size > 8e6) continue;
      const t = readFileSync(ROOT + f, 'utf8');
      if (/^\s*(AuthUserFile|AuthName|AuthType)\s+\S/m.test(t)) bad.push(`${f}: Apache auth directive`);
      for (const m of t.matchAll(/(?<![\d.])((?:\d{1,3}\.){3}\d{1,3})(?![\d.])/g)) if (!allowedIp(m[1])) bad.push(`${f}: IP ${m[1]}`);
      if (/\b[a-z0-9-]+\.(hostingersite\.com|hstgr\.(io|cloud))\b/i.test(t)) bad.push(`${f}: Hostinger host name`);
      if (/^\s*(VITE_)?[A-Z_]*(PASSWORD|PASSWD|HTPASSWD)(?![A-Z_]*_FILE\b)[A-Z_]*\s*=\s*[^\s<#$]+/m.test(t) && !/\.example$/.test(f)) bad.push(`${f}: password assignment`);
      if (/^\s*(DEMO|HPANEL|BASIC_AUTH)[A-Z_]*_(USER|PASS)[A-Z_]*\s*=\s*\S+/m.test(t)) bad.push(`${f}: credential assignment`);
    }
    expect(bad).toEqual([]);
    // .gitignore covers the demo output
    execFileSync('git', ['check-ignore', '-q', 'web/dist-demo-mode/index.html'], { cwd: ROOT });
  });

  test('AC7: the public site (static build at the web root) has no link or reference to a demo folder, the demo build or its data; no robots.txt naming it', () => {
    const list = files(STATIC);
    expect(list.filter((f) => /robots\.txt$/i.test(f))).toEqual([]);
    const hits = [];
    for (const f of list) {
      if (f.startsWith('fonts/') || /\.(png|pbf)$/.test(f)) continue;
      const t = text(STATIC, f);
      for (const s of ['demo-mode', 'dist-demo-mode', 'demo-routes', '<demo-folder>', 'locked-demo', 'demo-a/']) if (t.includes(s)) hits.push(`${f}: ${s}`);
    }
    expect(hits).toEqual([]);
  });

  test('AC36: README "Supported browsers" (NAV-002 AC 50) is unchanged plus at most one sentence; the demo section states iPhone Safari (D72), D14 for the rest, and which tests run on Chromium, WebKit and the real iPhone', () => {
    const now = readFileSync(WEB + 'README.md', 'utf8');
    const before = execFileSync('git', ['show', '084a207:web/README.md'], { cwd: ROOT, encoding: 'utf8' });
    const sec = (t) => { const i = t.indexOf('## Supported browsers'); return t.slice(i, t.indexOf('\n## ', i + 5)).trim(); };
    const a = sec(before), b = sec(now);
    expect(b.startsWith(a), 'old section kept word for word').toBe(true);
    const added = b.slice(a.length).trim();
    expect(added.split(/(?<=[.!?])\s+/).filter(Boolean).length, `added: ${added}`).toBeLessThanOrEqual(1);
    const i = now.indexOf('### Supported browsers (demo mode)');
    const demo = now.slice(i, now.indexOf('\n### ', i + 5));
    expect(demo).toMatch(/iPhone Safari on the current major iOS version/);
    expect(demo).toMatch(/D72/);
    expect(demo).toMatch(/D14/);
    expect(demo).toMatch(/Chromium/);
    expect(demo).toMatch(/WebKit/);
    expect(demo).toMatch(/real\s+iPhone/);
  });
});

test.describe('Demo route data (AC 16, 44; story › Demo routes)', () => {
  for (const [label, r] of Object.entries(ROUTES)) {
    test(`AC16/AC44 ${label}: track ${r.track} is 1 Hz continuous, ${r.length} s long, first and last fix within 30 m of the route start and end; build data = the recorded response and the GPX points unchanged`, () => {
      const resp = readJson(r.route);
      const track = readGpx(r.track);
      expect(track.at(-1).tMs / 1000).toBe(r.length);
      track.forEach((p, i) => expect(p.tMs, `point ${i}`).toBe(i * 1000));
      const o = new RouteOracle(resp);
      const start = o.line[0], end = o.line.at(-1);
      const dStart = haversine(track[0], start), dEnd = haversine(track.at(-1), end);
      expect(dStart, 'first fix to route start (m)').toBeLessThanOrEqual(30);
      expect(dEnd, 'last fix to route end (m)').toBeLessThanOrEqual(30);
      // the build ships exactly the fixture data (no device positions, no edits)
      const built = JSON.parse(readFileSync(`${DEMO}/demo-routes/${r.id}.json`, 'utf8'));
      expect(built.route).toEqual(resp);
      expect(built.mode).toBe(r.mode);
      expect(built.track.lonlat.length).toBe(track.length);
      built.track.lonlat.forEach(([lon, lat], i) => { expect(lon).toBeCloseTo(track[i].lon, 7); expect(lat).toBeCloseTo(track[i].lat, 7); });
      expect(built.track.t_ms).toEqual(track.map((p) => p.tMs));
    });
  }

  test('AC44: every demo track is a synthetic track produced by the NAV-005 generator (make_gpx.py --check: committed tracks equal the generator output)', () => {
    const out = execFileSync('python3', ['tests/gpx/nav005/make_gpx.py', '--check'], { cwd: ROOT, encoding: 'utf8' });
    expect(out).not.toMatch(/stale|differs|FAIL/i);
    for (const r of [...Object.values(ROUTES).map((x) => x.track), G4.track]) expect(readFileSync(ROOT + r, 'utf8')).toContain('creator="NAV-005 QA make_gpx.py"');
  });

  test('manifest: R1–R3 entries point at the story fixtures; G4 is test-only (not in the picker); names follow the OSM rule or «Сонгосон цэг»', () => {
    const m = JSON.parse(readFileSync(WEB + 'src/demo/routes.manifest.json', 'utf8'));
    const by = Object.fromEntries(m.routes.map((e) => [e.label, e]));
    for (const [label, r] of Object.entries(ROUTES)) {
      expect(by[label].route).toBe(r.route);
      expect(by[label].track).toBe(r.track);
      expect(by[label].mode).toBe(r.mode);
      expect(by[label].picker).toBe(true);
    }
    expect(by.G4.picker).toBe(false);
    expect(by.G4.track).toBe(G4.track);
    for (const e of m.routes) for (const end of [e.origin, e.destination]) {
      expect(end.name === 'Сонгосон цэг' ? end.osm : 'named', `${e.label}: «Сонгосон цэг» has no OSM id`).toEqual(end.name === 'Сонгосон цэг' ? null : 'named');
      if (end.osm) expect(end.osm).toMatch(/^(node|way|relation)\/\d+$/);
    }
  });
});

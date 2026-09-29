// NAV-002 section A (AC 1, 4) and the repository-level checks of AC 32, 33, 36.
// No browser needed; these run against the web/ working tree.
import { test, expect } from '@playwright/test';
import { execFileSync, spawn } from 'node:child_process';
import { cpSync, existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { EN, MN, ROOT, WEB } from './helpers.mjs';

const read = (p) => readFileSync(p, 'utf8');
const run = (cmd, args, cwd = WEB, timeout = 300_000) => {
  try {
    const out = execFileSync(cmd, args, { cwd, encoding: 'utf8', timeout, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, FORCE_COLOR: '0' } });
    return { code: 0, out };
  } catch (e) {
    return { code: e.status ?? 1, out: `${e.stdout ?? ''}${e.stderr ?? ''}` };
  }
};

test.describe('NAV-002 A. Start-up and configuration', () => {
  test('AC1 README lists install/dev/build/typecheck/test commands, gateway setting + default, CORS note', () => {
    const md = read(WEB + 'README.md');
    for (const cmd of ['npm ci', 'npm run dev', 'npm run build', 'npm run typecheck', 'npm test']) {
      expect(md, `README lists \`${cmd}\``).toContain('`' + cmd + '`');
    }
    expect(md).toContain('http://localhost:5173');
    expect(md).toContain('VITE_GATEWAY_BASE_URL');
    expect(md).toMatch(/VITE_GATEWAY_BASE_URL`\s*\|\s*`http:\/\/localhost:8080`/);
    expect(md).toContain('CORS_ALLOWED_ORIGINS');
    expect(md).toMatch(/default `\*`/);
    // package.json scripts behind the documented commands exist
    const pkg = JSON.parse(read(WEB + 'package.json'));
    for (const s of ['dev', 'build', 'typecheck', 'test']) expect(pkg.scripts[s], `script ${s}`).toBeTruthy();
    // Dev server port is 5173 and strict (so the documented URL is the real one)
    const vc = read(WEB + 'vite.config.ts');
    expect(vc).toMatch(/port:\s*5173/);
    expect(vc).toMatch(/strictPort:\s*true/);
  });

  test('AC3 .env.example documents VITE_GATEWAY_BASE_URL with a one-line comment', () => {
    const lines = read(WEB + '.env.example').split('\n');
    const i = lines.findIndex((l) => l.startsWith('VITE_GATEWAY_BASE_URL='));
    expect(i).toBeGreaterThan(0);
    expect(lines[i - 1]).toMatch(/^#\s*\S/);
    expect(lines[i]).toBe('VITE_GATEWAY_BASE_URL=http://localhost:8080');
  });

  test('AC1 clean checkout: install + dev command serve the app (fresh copy of web/)', async () => {
    test.setTimeout(420_000);
    // A clean copy of the tracked + untracked-not-ignored files of web/ and docs/design (tokens alias).
    const tmp = mkdtempSync(path.join(os.tmpdir(), 'nav002-clean-'));
    try {
      const files = execFileSync('git', ['ls-files', '-co', '--exclude-standard', 'web', 'docs/design'], { cwd: ROOT, encoding: 'utf8' })
        .split('\n')
        .filter(Boolean);
      expect(files.length).toBeGreaterThan(50);
      for (const f of files) cpSync(path.join(ROOT, f), path.join(tmp, f), { recursive: true });
      const ci = run('npm', ['ci', '--no-audit', '--no-fund'], path.join(tmp, 'web'), 360_000);
      expect(ci.code, ci.out.slice(-2000)).toBe(0);
      // Same dev command, other port only because 5173 is already taken by the suite's server.
      const port = 5177;
      const child = spawn('npx', ['vite', '--port', String(port), '--strictPort'], { cwd: path.join(tmp, 'web'), stdio: 'ignore', detached: true });
      try {
        let html = '';
        for (let i = 0; i < 60 && !html; i++) {
          await new Promise((r) => setTimeout(r, 1000));
          html = await fetch(`http://localhost:${port}/`).then((r) => (r.ok ? r.text() : ''), () => '');
        }
        expect(html).toContain('data-testid="map"');
        const main = await fetch(`http://localhost:${port}/src/main.ts`);
        expect(main.status).toBe(200);
      } finally {
        try {
          process.kill(-child.pid, 'SIGTERM');
        } catch {}
      }
    } finally {
      rmSync(tmp, { recursive: true, force: true });
    }
  });

  test('AC4 build and typecheck commands exit 0', () => {
    test.setTimeout(300_000);
    const tc = run('npm', ['run', 'typecheck']);
    expect(tc.code, tc.out.slice(-3000)).toBe(0);
    const b = run('npm', ['run', 'build']);
    expect(b.code, b.out.slice(-3000)).toBe(0);
    expect(existsSync(WEB + 'dist/index.html')).toBe(true);
  });
});

test.describe('NAV-002 G. Language and strings (repository checks)', () => {
  test('AC32 resource files: identical key sets, no empty value', () => {
    expect(Object.keys(MN).sort()).toEqual(Object.keys(EN).sort());
    for (const [k, v] of Object.entries(MN)) expect(v.trim(), `mn ${k}`).not.toBe('');
    for (const [k, v] of Object.entries(EN)) expect(v.trim(), `en ${k}`).not.toBe('');
  });

  test('AC32 documented check (npm run check:i18n and npm run lint) exits 0', () => {
    test.setTimeout(180_000);
    const md = read(WEB + 'README.md');
    expect(md).toContain('npm run check:i18n');
    const r = run('npm', ['run', 'check:i18n']);
    expect(r.code, r.out.slice(-2000)).toBe(0);
    const l = run('npm', ['run', 'lint']);
    expect(l.code, l.out.slice(-3000)).toBe(0);
  });

  test('AC32 independent scan: no resource string hard-coded in web/src, web/index.html or web/fixtures', () => {
    const files = [];
    const walk = (d) => {
      for (const n of readdirSync(d)) {
        const p = path.join(d, n);
        if (statSync(p).isDirectory()) walk(p);
        else if (/\.(ts|js|mjs|html|css)$/.test(n) && !/\.test\./.test(n)) files.push(p);
      }
    };
    walk(WEB + 'src');
    files.push(WEB + 'index.html');
    const literals = new Set(
      [...Object.values(MN), ...Object.values(EN)].filter((v) => v.length >= 4), // skip «м», «км», "m", "km", "Хэл", "Map"
    );
    const problems = [];
    for (const f of files) {
      const src = read(f);
      // Comments may quote strings (spec references); only code counts.
      const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1').replace(/<!--[\s\S]*?-->/g, '');
      for (const v of literals) {
        const esc = v.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        if (new RegExp(`["'\`>]\\s*${esc}\\s*["'\`<]`).test(code)) problems.push(`${path.relative(ROOT, f)} hard-codes «${v}»`);
      }
      // Any Cyrillic outside comments is a hard-coded Mongolian string.
      const m = code.match(/[Ѐ-ӿ][^"'`<\n]*/);
      if (m) problems.push(`${path.relative(ROOT, f)} has Cyrillic literal «${m[0].slice(0, 40)}»`);
    }
    expect(problems).toEqual([]);
  });

  test('AC33 every mn value matches a glossary term (docs/requirements/glossary.md)', () => {
    const md = read(ROOT + 'docs/requirements/glossary.md');
    const norm = (s) => s.replace(/\*\*|`/g, '').replace(/\s+/g, ' ').trim();
    const terms = new Set();
    for (const m of md.matchAll(/«([^»]+)»/g)) terms.add(norm(m[1]));
    for (const line of md.split('\n')) {
      if (!line.startsWith('|') || /^\|\s*-/.test(line)) continue;
      const cells = line.split('|').slice(1, -1);
      if (cells.length < 3) continue;
      const mn = cells[1].replace(/«[^»]*»/g, '|').replace(/\([^)]*\)/g, '|');
      for (const piece of mn.split(/[|/,;:]/)) if (norm(piece)) terms.add(norm(piece));
    }
    // Stand-alone labels may start with a capital where the glossary row is lower case (UX/mobile proposal,
    // BA confirmation pending: open question). Nothing else may differ.
    const matches = (v) => terms.has(v) || [...terms].some((t) => t.length === v.length && t.slice(1) === v.slice(1) && t[0].toLowerCase() === v[0].toLowerCase());
    const missing = Object.entries(MN).filter(([, v]) => !matches(norm(v))).map(([k, v]) => `${k}: «${v}»`);
    expect(missing, `mn values without a glossary term (${missing.length}/${Object.keys(MN).length})`).toEqual([]);
  });
});

test.describe('NAV-002 H. Licences', () => {
  test('AC36 third-party notices list every shipped asset with source, version/commit and licence; OFL in full', () => {
    const md = read(WEB + 'README.md');
    expect(md).toContain('THIRD_PARTY_NOTICES.md');
    const n = read(WEB + 'THIRD_PARTY_NOTICES.md');
    const commit = read(WEB + 'public/fonts/BASEMAPS_ASSETS_COMMIT').trim();
    expect(commit).toMatch(/^[0-9a-f]{7,40}/);
    // Glyph fonts and sprites (bundled): source, commit, licence
    expect(n).toMatch(/Noto Sans/);
    expect(n).toContain('github.com/protomaps/basemaps-assets');
    expect(n).toContain(commit.slice(0, 7));
    expect(n).toMatch(/SIL Open Font License|OFL-1\.1|OFL 1\.1/);
    // Full OFL text (key clauses of SIL OFL 1.1)
    expect(n).toContain('PERMISSION & CONDITIONS');
    expect(n).toContain('TERMINATION');
    expect(n).toMatch(/Reserved Font Name/);
    // Sprites: MIT (tangrams icons)
    expect(n).toMatch(/sprites/i);
    expect(n).toMatch(/MIT/);
    // Style base, MapLibre, PMTiles and every runtime npm package with version and licence
    const lock = JSON.parse(read(WEB + 'package-lock.json'));
    const pkg = JSON.parse(read(WEB + 'package.json'));
    for (const [name, ver] of Object.entries(pkg.dependencies)) {
      expect(n, `${name}@${ver} listed`).toContain(name);
      expect(n, `${name} version ${ver}`).toContain(ver);
    }
    expect(n).toMatch(/@protomaps\/basemaps[\s\S]{0,400}BSD-3-Clause/);
    expect(n).toMatch(/maplibre-gl[\s\S]{0,400}BSD-3-Clause/);
    expect(n).toMatch(/pmtiles[\s\S]{0,400}BSD-3-Clause/);
    // Every production (non-dev) package in the lock file is named
    const runtime = Object.entries(lock.packages)
      .filter(([k, v]) => k.startsWith('node_modules/') && !v.dev)
      .map(([k]) => k.replace(/^.*node_modules\//, ''));
    const missing = runtime.filter((p) => !n.includes(p));
    expect(missing, 'runtime packages missing from notices').toEqual([]);
    // Source URL for each npm package: registry or repository URL present
    expect(n).toMatch(/https:\/\/(www\.npmjs\.com|github\.com)\//);
  });
});

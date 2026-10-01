// NAV-002 section A (AC 1, 4) and the repository-level checks of AC 32, 33, 36, 50.
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

  // AC 33, strict (run 4, 2026-09-30). The earlier version accepted every «…» anywhere in the glossary, so an
  // Avoid term quoted in a Notes cell (e.g. «навигаци», «Төвлөрүүлэх») or a change-log line counted as a match.
  // Accepted sources now: the "Approved Mongolian" column of the user-facing term tables (sections 2-8), «…» forms
  // in their Definition column, and «…» examples in the Rule column of section 1 (conventions). Sentences that
  // negate or forbid ("never", "Avoid", "not", "previous proposal", "Rejected") are ignored. Notes, Status,
  // section 9 (team-internal) and the change log are never read. Mirrors web/scripts/check-glossary.mjs, but is
  // independent of it.
  //
  // Extractor hardening (NAV-004 verification round 0, 2026-09-30): the glossary's banned-term marker is the English
  // word "Avoid", but the glossary also has English TERM NAMES that start with "Avoid" (section 6 "Avoid tolls",
  // "Avoid unpaved roads", NAV-004). A sentence that merely names such an option next to a quoted Mongolian word
  // (N12 at HEAD 084a207: «Маршрут олдсонгүй», «тохиргоо») was read as an Avoid marker. Before the Avoid / NEG checks
  // every sentence is now normalised (no ** or `), English labels in “…” / "…" are removed, and every English term
  // name from column 1 of the term tables that contains "Avoid" is removed, quoted or not. Every other "Avoid"
  // still counts, anywhere in the sentence, so the banned set is not narrowed (18 terms before and after on the
  // glossary of 2026-09-30). The negative controls in 'AC33 Avoid-term extractor self-check' pin this behaviour.
  const parseGlossary = (md) => {
    const norm = (x) => x.replace(/\*\*|`/g, '').replace(/\s+/g, ' ').trim();
    const quoted = (x) => [...x.matchAll(/«([^»]+)»/g)].map((m) => norm(m[1]));
    const sentences = (x) => x.split(/(?<=[.;])\s+|\s+e\.g\.\s+/);
    const NEG = /\b(never|Avoid|avoid|not|Rejected|rejected|previous proposal|Previous proposal|instead of)\b/;
    const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    // Pass 1: English term names (column 1 of a term table, without a trailing "(…)" qualifier) that contain "Avoid".
    const avoidNames = [];
    {
      let inTerms = false;
      for (const line of md.split('\n')) {
        if (/^##\s/.test(line)) { inTerms = false; continue; }
        if (!line.startsWith('|') || /^\|\s*-/.test(line)) continue;
        if (/Approved Mongolian/.test(line)) { inTerms = true; continue; }
        if (!inTerms) continue;
        const name = norm(line.split('|')[1] || '').replace(/\s*\([^)]*\)\s*$/, '');
        if (/\bAvoid\b/.test(name) && !avoidNames.includes(name)) avoidNames.push(name);
      }
    }
    avoidNames.sort((a, b) => b.length - a.length); // longest first, so "Avoid unpaved roads" wins over a shorter prefix
    const NAMES = avoidNames.length ? new RegExp(`\\b(?:${avoidNames.map(esc).join('|')})\\b`, 'g') : null;
    const plain = (x) => {
      const t = norm(x).replace(/“[^”]*”|"[^"]*"/g, '');
      return NAMES ? t.replace(NAMES, '') : t;
    };
    const approved = new Set();
    const banned = new Set();
    let section = '';
    let kind = null; // 'terms' | 'conventions' | null
    for (const line of md.split('\n')) {
      const h = line.match(/^##\s+(.*)$/);
      if (h) { section = h[1]; kind = null; continue; }
      if (!line.startsWith('|') || /^\|\s*-/.test(line)) continue;
      const cells = line.split('|').slice(1, -1).map((c) => c.trim());
      if (/Approved Mongolian/.test(line)) { kind = 'terms'; continue; }
      if (/^\|\s*#\s*\|\s*Convention/.test(line)) { kind = 'conventions'; continue; }
      const num = Number((section.match(/^(\d+)\./) || [])[1]);
      if (!kind || !(num >= 1 && num <= 9)) continue;
      // Banned: «…» in any sentence that says Avoid (Rule/Definition/Notes cells), except "not marked Avoid".
      for (const c of cells.slice(1)) for (const snt of sentences(c)) {
        if (/\bAvoid\b/.test(plain(snt)) && !/not marked Avoid/.test(snt)) for (const q of quoted(snt)) if (!q.includes('<')) banned.add(q);
      }
      if (num === 9) continue; // team-internal terms are not UI terms
      if (kind === 'terms' && cells.length >= 5) {
        const [, mn, def] = cells;
        for (const q of quoted(mn)) approved.add(q);
        const rest = mn.replace(/«[^»]*»/g, '|').replace(/\([^)]*\)/g, '|').replace(/\b(Banner|Layer|UI|Mute icon|Rationale prompt|Voice)\b/g, '|');
        for (const piece of rest.split(/[|/,;:.]/)) if (norm(piece) && /[А-Яа-яӨөҮүЁё]|^[A-Z][a-z]+$/.test(norm(piece))) approved.add(norm(piece));
        for (const snt of sentences(def)) if (!NEG.test(plain(snt))) for (const q of quoted(snt)) approved.add(q);
      } else if (kind === 'conventions' && cells.length >= 5) {
        for (const snt of sentences(cells[2])) if (!NEG.test(plain(snt))) for (const q of quoted(snt)) approved.add(q);
      }
    }
    return { approved, banned, norm, avoidNames };
  };
  const glossary = () => parseGlossary(read(ROOT + 'docs/requirements/glossary.md'));

  test('AC33 Avoid-term extractor self-check (negative controls on synthetic glossary rows)', () => {
    // Synthetic glossary: one conventions table (section 1) and one term table (section 6) in the real layout.
    const md = [
      '## 1. Language conventions', '',
      '| # | Convention | Rule | Notes | Status |', '|---|---|---|---|---|',
      '| C9 | Units | «60 км/цаг». | Avoid «км/ч» (Russian). **Avoid** «км/ц» as a unit. | s |',
      '', '## 6. Settings', '',
      '| English term | Approved Mongolian (UI/voice) | Definition (English) | Notes | Status |', '|---|---|---|---|---|',
      '| Avoid unpaved roads | «Шороон замаас зайлсхийх» | Switch in route preview. | Only when Avoid tolls is off. | s |',
      '| Avoid tolls | «Төлбөртэй замаас зайлсхийх» | Switch. | n/a | s |',
      // Round-0 wording of N12 (quoted English option name) and an unquoted variant: NOT Avoid markers.
      '| No route hint | «Хинт А» | Hint under «Маршрут олдсонгүй» when "Avoid unpaved roads" is on. `en`: "Turn off “Avoid unpaved roads” and try again". | Uses "Avoid unpaved roads" and «тохиргоо» ("Settings"). | s |',
      '| No route hint 2 | «Хинт Б» | Shown with «Хайлт А» when Avoid unpaved roads or Avoid tolls is on. | Built from «Тохиргоо Б». | s |',
      // Real markers in every position the glossary uses: sentence start, bold, "a bare", mid-sentence, after the name.
      '| Turn | «Эргэнэ үү» | Turn. | We add «тийш». Avoid a bare «зүүн эргэ». | s |',
      '| Stop | «Зогсоол» | Stop. | Drivers may misread it, so Avoid «Зогсоох». | s |',
      '| Toll hint | «Хинт В» | Hint. | Like Avoid tolls, but Avoid «Төлбөрийн зам». | s |',
      '| First | «нэгдүгээр» | Ordinal. | «эхний» is not marked Avoid because it has other uses. | s |',
    ].join('\n');
    const { banned, approved, avoidNames } = parseGlossary(md);
    expect(avoidNames).toEqual(['Avoid unpaved roads', 'Avoid tolls']);
    for (const must of ['км/ч', 'км/ц', 'зүүн эргэ', 'Зогсоох', 'Төлбөрийн зам']) expect([...banned], `real Avoid marker «${must}» must be banned`).toContain(must);
    for (const not of ['Маршрут олдсонгүй', 'тохиргоо', 'Хайлт А', 'Тохиргоо Б', 'тийш', 'эхний']) expect([...banned], `«${not}» is only next to an English option name / not an Avoid`).not.toContain(not);
    // The same option names must not turn a Definition sentence into a "negation" either.
    for (const ok of ['Маршрут олдсонгүй', 'Хайлт А']) expect([...approved], `Definition form «${ok}» is read as approved`).toContain(ok);
    // Record which real English term names are treated as names (not markers) in this run.
    test.info().annotations.push({ type: 'AC33 English term names containing "Avoid" (not markers)', description: glossary().avoidNames.join(', ') });
  });

  test('AC33 every mn value matches a glossary term (strict: approved column, Definition and convention examples only)', () => {
    const { approved, norm } = glossary();
    // Stand-alone labels may start with a capital where the glossary row is lower case (e.g. «Өдрийн горим» vs
    // «өдрийн горим»); nothing else may differ. Same interpretation as runs 1-3 (test plan TC-33-01).
    const matches = (v) => approved.has(v) || [...approved].some((t) => t.length === v.length && t.slice(1) === v.slice(1) && t[0].toLowerCase() === v[0].toLowerCase());
    const missing = Object.entries(MN).filter(([, v]) => !matches(norm(v))).map(([k, v]) => `${k}: «${v}»`);
    test.info().annotations.push({ type: 'AC33 approved glossary forms read', description: String(approved.size) });
    expect(missing, `mn values without an approved glossary term (${missing.length}/${Object.keys(MN).length})`).toEqual([]);
  });

  test('AC33 / D22 no mn value uses a glossary Avoid term («навигаци», «Төвлөрүүлэх», «км/ц» as a unit, «хоёр дахь», …)', () => {
    const { banned, norm } = glossary();
    for (const must of ['навигаци', 'Төвлөрүүлэх', 'км/ц', 'хоёр дахь']) expect([...banned], `Avoid list must contain «${must}» (parser self-check)`).toContain(must);
    const hit = (v, b) => (b === 'км/ц' ? /км\/ц(?!аг)/.test(v) : v.toLowerCase().includes(b.toLowerCase()));
    const found = Object.entries(MN).flatMap(([k, v]) => [...banned].filter((b) => hit(norm(v), b)).map((b) => `${k}: «${v}» contains Avoid term «${b}»`));
    test.info().annotations.push({ type: 'AC33 Avoid terms checked', description: [...banned].join(', ') });
    expect(found).toEqual([]);
  });

  test('AC50 web/README.md has a "Supported browsers" section: desktop Chrome, Edge, Firefox, Android Chrome; Safari/iOS not yet; tests on desktop Chromium only (D14)', () => {
    const md = read(WEB + 'README.md');
    const m = md.match(/^(#{1,6})\s+Supported browsers\s*$([\s\S]*?)(?=^#{1,6}\s|(?![\s\S]))/m);
    expect(m, 'a heading "Supported browsers" in web/README.md').not.toBeNull();
    const sec = m[2];
    for (const [what, re] of [
      ['desktop Chrome', /\bChrome\b/], ['Edge', /\bEdge\b/], ['Firefox', /\bFirefox\b/], ['Android Chrome', /Android\s+Chrome|Chrome\s+(for|on)\s+Android/i],
      ['"current"', /\bcurrent\b/i], ['Safari not supported yet', /Safari[\s\S]{0,80}not supported yet|not supported yet[\s\S]{0,80}Safari/i],
      ['iOS not supported yet', /iOS[\s\S]{0,80}not supported yet|not supported yet[\s\S]{0,80}iOS/i],
      ['automated tests on desktop Chromium only', /Chromium[\s\S]{0,80}only|only[\s\S]{0,80}Chromium/i],
    ]) expect(re.test(sec), `Supported browsers section names ${what}`).toBe(true);
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

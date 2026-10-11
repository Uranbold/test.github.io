// SEC-4B A–C: independent re-check of the three production builds (B1 static demo, B2 demo mode, B3 normal) as built
// by the webServer entries of playwright.config.mjs. The mobile engineer verifies these ACs (story › Process); QA re-checks
// them cheaply here so that the zero of AC 17 is measured against a policy that has the required shape.
// The HTML is parsed by Chromium's DOMParser and hashed with WebCrypto (helpers.mjs › analyseHtml), never with
// web/buildtools/csp.ts.
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { BUILDS, urlOf } from './playwright.config.mjs';
import { analyseHtml, listFiles, parsePolicy } from './helpers.mjs';

const HOSTLIKE = /^(?!'|data:$|blob:$)([a-z][a-z0-9+.-]*:\/\/)?[a-z0-9-]+(\.[a-z0-9-]+)+(:\d+)?\/?$|^[a-z][a-z0-9+.-]*:\/\//i;

for (const name of ['B1', 'B2', 'B3']) {
  const b = BUILDS[name];

  test(`${name} SEC-4B AC1–AC6, AC9, AC11, AC15: every .html has one CSP meta right after <meta charset>, before every style/link/script; one hash per inline script and style; required and forbidden directives; referrer same-origin; no on*=, javascript:, style=, <!--`, async ({ page, request }) => {
    const files = listFiles(b.out);
    const htmlFiles = files.filter((f) => f.endsWith('.html'));
    expect(htmlFiles, 'HTML files in the output').toEqual(['index.html']);
    const wasm = files.filter((f) => f.endsWith('.wasm'));
    await page.goto('about:blank');
    await page.goto(urlOf(name)); // secure context (localhost) for WebCrypto
    for (const f of htmlFiles) {
      const raw = readFileSync(path.join(b.out, f), 'utf8');
      const served = await (await request.get(urlOf(name) + f)).text();
      expect(served, `${f}: vite preview serves the bytes on disk`).toBe(raw);
      // AC 15: no HTML comment at all
      expect(raw.includes('<!--'), `${f}: contains <!--`).toBe(false);
      const a = await analyseHtml(page, raw);
      const csp = a.head.filter((e) => e.tag === 'meta' && (e.httpEquiv ?? '').toLowerCase() === 'content-security-policy');
      expect(csp.length, `${f}: exactly one CSP meta (AC 1)`).toBe(1);
      // the CSP meta is the element right after <meta charset>, and before every style/link/script (AC 1)
      const idxCharset = a.head.findIndex((e) => e.tag === 'meta' && e.charset);
      const idxCsp = a.head.findIndex((e) => e === csp[0] || (e.tag === 'meta' && (e.httpEquiv ?? '').toLowerCase() === 'content-security-policy'));
      expect(idxCharset, `${f}: <meta charset> present`).toBeGreaterThanOrEqual(0);
      expect(a.head[idxCharset].charset.toLowerCase()).toBe('utf-8');
      expect(idxCsp, `${f}: CSP meta directly after <meta charset>`).toBe(idxCharset + 1);
      const firstActive = a.head.findIndex((e) => ['style', 'link', 'script'].includes(e.tag));
      expect(firstActive === -1 || firstActive > idxCsp, `${f}: no style/link/script before the policy`).toBe(true);
      expect(raw.indexOf('<meta charset'), `${f}: charset within the first 1024 bytes (ADR-0018 F1)`).toBeLessThan(1024);
      // every inline element is after the policy (head: index check above; body elements are always after the head)
      const { d, repeated } = parsePolicy(csp[0].content);
      expect(repeated, `${f}: repeated directives`).toEqual([]);
      const scriptSrc = d.get('script-src') ?? [];
      const styleSrc = d.get('style-src') ?? [];
      const inlineScripts = a.inline.filter((e) => e.tag === 'script');
      const inlineStyles = a.inline.filter((e) => e.tag === 'style');
      // AC 2
      expect(scriptSrc).toContain("'self'");
      for (const forbidden of ["'unsafe-inline'", "'unsafe-eval'", "'unsafe-hashes'", '*', 'data:', 'blob:', 'http:', 'https:']) {
        expect(scriptSrc, `${f}: script-src contains ${forbidden}`).not.toContain(forbidden);
      }
      expect(scriptSrc.filter((s) => HOSTLIKE.test(s)), `${f}: host names in script-src`).toEqual([]);
      const scriptHashes = scriptSrc.filter((s) => s.startsWith("'sha256-"));
      expect(new Set(scriptHashes), `${f}: script-src hashes = the inline scripts' hashes (AC 2, AC 7)`).toEqual(new Set(inlineScripts.map((e) => e.hash)));
      expect(inlineScripts.length, `${f}: at least the boot script is inline`).toBeGreaterThanOrEqual(1);
      // AC 3
      if (name === 'B2') {
        expect(wasm.length, 'B2 ships the Ferrostar WASM').toBeGreaterThanOrEqual(1);
        expect(scriptSrc).toContain("'wasm-unsafe-eval'");
      } else {
        expect(wasm, `${name}: .wasm files`).toEqual([]);
        expect(raw.includes('wasm-unsafe-eval'), `${name}: 'wasm-unsafe-eval' present`).toBe(false);
      }
      // AC 4
      expect(styleSrc).toContain("'self'");
      expect(styleSrc).not.toContain("'unsafe-inline'");
      expect(new Set(styleSrc.filter((s) => s.startsWith("'sha256-"))), `${f}: style-src hashes = the inline styles' hashes`).toEqual(new Set(inlineStyles.map((e) => e.hash)));
      expect(inlineStyles.map((e) => e.id)).toContain('design-tokens');
      // AC 5
      expect(d.get('default-src')).toEqual(["'self'"]);
      expect(d.get('object-src')).toEqual(["'none'"]);
      expect(["'none'", "'self'"]).toContain((d.get('base-uri') ?? []).join(' '));
      expect(d.get('form-action')).toEqual(["'none'"]);
      if (name === 'B3') expect(d.get('connect-src'), 'B3 connect-src = self + exactly the gateway origin').toEqual(["'self'", 'http://localhost:8080']);
      else expect(d.get('connect-src'), `${name} connect-src`).toEqual(["'self'"]);
      for (const s of d.get('img-src') ?? []) expect(["'self'", 'data:', 'blob:']).toContain(s);
      for (const s of d.get('worker-src') ?? []) expect(["'self'", 'blob:']).toContain(s);
      for (const [dir, src] of d) {
        expect(src, `${f}: ${dir} contains *`).not.toContain('*');
        if (dir !== 'connect-src') expect(src.filter((s) => HOSTLIKE.test(s)), `${f}: host in ${dir}`).toEqual([]);
      }
      // media-src: B2 only, 'self' data: (ADR-0018 F6, AC 5 amendment requested by the architect)
      if (name === 'B2') expect(d.get('media-src')).toEqual(["'self'", 'data:']);
      else expect(d.has('media-src'), `${name}: media-src`).toBe(false);
      // AC 6
      for (const dir of ['frame-ancestors', 'report-uri', 'report-to', 'sandbox']) expect(d.has(dir), `${f}: ${dir} in the meta policy`).toBe(false);
      // AC 9
      expect(a.bad.on, `${f}: inline event handlers`).toEqual([]);
      expect(a.bad.js, `${f}: javascript: URLs`).toEqual([]);
      expect(a.bad.style, `${f}: style= attributes`).toEqual([]);
      // AC 11
      const ref = a.head.filter((e) => e.tag === 'meta' && (e.name ?? '').toLowerCase() === 'referrer');
      expect(ref.map((e) => e.content), `${f}: referrer meta`).toEqual(['same-origin']);
      test.info().annotations.push({ type: `${name} ${f} policy`, description: csp[0].content.replace(/'sha256-[^']+'/g, (h) => h.slice(0, 16) + "…'") });
      test.info().annotations.push({ type: `${name} ${f} inline`, description: a.inline.map((e) => `${e.tag}${e.id ? '#' + e.id : ''}${e.inHead ? '(head)' : '(body)'}`).join(', ') });
    }
  });

  test(`${name} SEC-4B AC12, AC13: 0 output paths with fixtures/ or label-rule; no .htaccess/.htpasswd or other server configuration file${name === 'B2' ? '; robots noindex, nofollow kept' : ''}`, async () => {
    const files = listFiles(b.out);
    expect(files.filter((f) => /fixtures[\\/]|label-rule/i.test(f)), 'fixture paths (AC 13)').toEqual([]);
    expect(files.filter((f) => /(^|[\\/])(\.htaccess|\.htpasswd|web\.config|_headers|_redirects|nginx\.conf|\.user\.ini)$/i.test(f)), 'server configuration files').toEqual([]);
    const html = readFileSync(path.join(b.out, 'index.html'), 'utf8');
    expect(html.includes('label-rule'), 'index.html references label-rule').toBe(false);
    if (name === 'B2') expect(html).toMatch(/<meta name="robots" content="noindex, nofollow"\s*\/?>/);
    test.info().annotations.push({ type: `${name} output`, description: `${files.length} files; .wasm: ${files.filter((f) => f.endsWith('.wasm')).join(', ') || 'none'}` });
  });
}

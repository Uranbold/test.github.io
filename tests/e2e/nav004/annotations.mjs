// Prints the test annotations (measured values) from the last NAV-004 JSON report. Usage: node nav004/annotations.mjs [filter]
import { readFileSync } from 'node:fs';
const d = JSON.parse(readFileSync(new URL('../test-results/nav004-results.json', import.meta.url), 'utf8'));
const f = process.argv[2] ? new RegExp(process.argv[2]) : null;
const walk = (s, file) => {
  for (const sp of s.specs ?? []) for (const t of sp.tests) {
    const res = t.results.at(-1);
    const anns = res?.annotations?.length ? res.annotations : t.annotations ?? [];
    const line = `${res?.status ?? '?'}\t${file}: ${sp.title}`;
    if (f && !f.test(line)) continue;
    console.log(line);
    for (const a of anns) console.log(`\t- ${a.type}: ${a.description}`);
  }
  for (const c of s.suites ?? []) walk(c, file ?? c.file);
};
for (const s of d.suites) walk(s, s.file);

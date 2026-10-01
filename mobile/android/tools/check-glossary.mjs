#!/usr/bin/env node
// NAV-005 AC 61: every Mongolian value in app/src/main/res/values/strings.xml must match an approved glossary term
// exactly (placeholders such as {n} compared literally). Android equivalent of web/scripts/check-glossary.mjs: it
// parses strings.xml and runs THAT script with --mn, so web and Android apply one rule set (no second copy of the
// extraction rules). Strings marked translatable="false" (the OSM / ESA credits, not translated) are skipped.
//
//   node mobile/android/tools/check-glossary.mjs [--strings <values/strings.xml>]
// Exit code 1 if any value has no match.
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const HERE = fileURLToPath(new URL(".", import.meta.url));
const ROOT = resolve(HERE, "../../..");
const i = process.argv.indexOf("--strings");
const STRINGS = i > 0 ? resolve(process.argv[i + 1]) : resolve(HERE, "../app/src/main/res/values/strings.xml");

/** Android string resource unescaping (the subset our files use): \' \" \\ \@ \? and XML entities. */
function unescape(raw) {
  return raw
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, "&")
    .replace(/\\(['"@?\\])/g, "$1");
}

const xml = readFileSync(STRINGS, "utf8");
const values = {};
for (const m of xml.matchAll(/<string\s+name="([^"]+)"([^>]*)>([\s\S]*?)<\/string>/g)) {
  const [, name, attrs, body] = m;
  if (/translatable="false"/.test(attrs)) continue;
  values[name] = unescape(body);
}
if (Object.keys(values).length === 0) {
  console.error(`no strings found in ${STRINGS}`);
  process.exit(2);
}
const dir = mkdtempSync(join(tmpdir(), "navmn-glossary-"));
const json = join(dir, "mn.json");
writeFileSync(json, JSON.stringify(values, null, 2));
const res = spawnSync(process.execPath, [join(ROOT, "web/scripts/check-glossary.mjs"), "--mn", json], { stdio: "inherit" });
rmSync(dir, { recursive: true, force: true });
process.exit(res.status ?? 1);

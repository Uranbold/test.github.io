// NAV-006 AC 16 (light-QA set item 1): PMTiles client continuity across a data switch.
//
//   node tests/e2e/nav006/pmtiles_continuity.mjs --url http://127.0.0.1:18080/tiles/basemap.pmtiles \
//        --out ac16.json [--seconds 1800] [--stop-file FILE]
//
// Uses the SAME libraries as the web app (web/node_modules): pmtiles 4.5.0 (FetchSource + PMTiles) and
// @mapbox/vector-tile 3 + pbf (the MVT decoder MapLibre GL JS uses). ONE PMTiles instance reads the header
// before the switch and keeps requesting z14 tiles over Ulaanbaatar (5 x 5 around NAV-001 P1, plus 3 x 3 around
// P3 and P6). Every tile is decoded: every layer and every feature geometry is read.
//
// Pass (AC 16): >= 20 tiles requested after EACH ETag change seen on the server, 0 decode errors, 0 request
// errors, and the client re-read the archive header after each change (proves the ETag mechanism of
// ADR-0014 §5 is what keeps it consistent, not luck).
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const nm = path.resolve(here, "../../../web/node_modules");
const { PMTiles, FetchSource, EtagMismatch } = await import(path.join(nm, "pmtiles/dist/esm/index.js"));
const { VectorTile } = await import(path.join(nm, "@mapbox/vector-tile/index.js"));
const pbfMod = await import(path.join(nm, "pbf/index.js"));
const Pbf = pbfMod.PbfReader || pbfMod.default; // pbf 5: named export PbfReader (as MapLibre GL JS uses it)
const pmtilesVersion = JSON.parse(readFileSync(path.join(nm, "pmtiles/package.json"), "utf8")).version;

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, v, i, a) => (v.startsWith("--") ? acc.concat([[v.slice(2), a[i + 1]]]) : acc), []));
const url = args.url;
const out = args.out;
const seconds = Number(args.seconds || 1800);
const stopFile = args["stop-file"];
if (!url || !out) { console.error("usage: --url <pmtiles url> --out <json> [--seconds N] [--stop-file F]"); process.exit(2); }

// Count header reads (offset 0) and ETag mismatches inside the library's own FetchSource.
class CountingSource extends FetchSource {
  constructor(u) { super(u); this.headerReads = []; this.mismatches = 0; }
  async getBytes(offset, length, signal, etag) {
    if (offset === 0) this.headerReads.push(new Date().toISOString());
    try { return await super.getBytes(offset, length, signal, etag); }
    catch (e) { if (e instanceof EtagMismatch) this.mismatches++; throw e; }
  }
}

const P = { P1: [47.9189, 106.9176], P3: [47.8858, 106.9173], P6: [47.9600, 106.9000] };
const z = 14, n = 2 ** z;
const tileOf = ([lat, lon]) => [Math.floor((lon + 180) / 360 * n),
  Math.floor((1 - Math.asinh(Math.tan(lat * Math.PI / 180)) / Math.PI) / 2 * n)];
const tiles = [];
for (const [name, r] of [["P1", 2], ["P3", 1], ["P6", 1]]) {
  const [x0, y0] = tileOf(P[name]);
  for (let dx = -r; dx <= r; dx++) for (let dy = -r; dy <= r; dy++) tiles.push([x0 + dx, y0 + dy]);
}

function decode(buf) {
  const vt = new VectorTile(new Pbf(new Uint8Array(buf)));
  let feats = 0;
  const names = Object.keys(vt.layers);
  for (const ln of names) {
    const l = vt.layers[ln];
    for (let i = 0; i < l.length; i++) { const f = l.feature(i); f.loadGeometry(); f.properties; feats++; }
  }
  return { layers: names.length, features: feats };
}

const src = new CountingSource(url);
const pm = new PMTiles(src);
const h0 = await pm.getHeader();
const res = {
  story: "NAV-006", ac: "AC 16", url, pmtiles_version: pmtilesVersion, started: new Date().toISOString(),
  header_etag_at_start: h0.etag, tiles_per_round: tiles.length, rounds: 0, tiles_ok: 0, tiles_empty: 0,
  decode_errors: 0, request_errors: 0, errors: [], server_etags: [], tiles_after_change: [],
};
let lastEtag = null;
let readsAtPrevRound = src.headerReads.length; // a change can hit mid-round, so compare with the previous round
const end = Date.now() + seconds * 1000;
while (Date.now() < end && !(stopFile && existsSync(stopFile))) {
  const readsNow = src.headerReads.length;
  const head = await fetch(url, { method: "HEAD" }).catch(() => null);
  const et = head ? head.headers.get("etag") : null;
  if (et && et !== lastEtag) {
    if (lastEtag !== null) res.tiles_after_change.push({ t: new Date().toISOString(), etag: et, tiles: 0, header_reads_before: readsAtPrevRound });
    res.server_etags.push({ t: new Date().toISOString(), etag: et });
    lastEtag = et;
  }
  for (const [x, y] of tiles) {
    try {
      const r = await pm.getZxy(z, x, y);
      if (!r) { res.tiles_empty++; continue; }
      try { decode(r.data); res.tiles_ok++; if (res.tiles_after_change.length) res.tiles_after_change.at(-1).tiles++; }
      catch (e) { res.decode_errors++; res.errors.push(`${new Date().toISOString()} decode ${z}/${x}/${y}: ${e.message}`); }
    } catch (e) { res.request_errors++; res.errors.push(`${new Date().toISOString()} getZxy ${z}/${x}/${y}: ${e.message}`); }
  }
  res.rounds++;
  readsAtPrevRound = readsNow;
  await new Promise((r) => setTimeout(r, 400));
}
const hEnd = await pm.getHeader();
res.ended = new Date().toISOString();
res.header_etag_at_end = hEnd.etag;
res.header_reads = src.headerReads.length;
res.header_read_times = src.headerReads.slice(0, 20);
res.etag_mismatches_seen_by_library = src.mismatches;
for (const c of res.tiles_after_change) c.header_reread = src.headerReads.length > c.header_reads_before;
res.errors = res.errors.slice(0, 20);
res.pass = res.decode_errors === 0 && res.request_errors === 0 && res.tiles_after_change.length > 0 &&
  res.tiles_after_change.every((c) => c.tiles >= 20 && c.header_reread) && res.header_etag_at_end === lastEtag;
writeFileSync(out, JSON.stringify(res, null, 1) + "\n");
console.log(JSON.stringify(res, null, 1));
process.exit(res.pass ? 0 : 1);

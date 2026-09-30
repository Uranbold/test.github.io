// Bundled glyphs and sprites (NAV-002 AC 6, 36, 46; map-style.md §5–6; ADR-0004 §4).
import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { BUNDLED_FONTSTACKS, buildStyle, fontsIn } from "./style/buildStyle";

const PUBLIC = fileURLToPath(new URL("../public/", import.meta.url));
const CFG = { sourceUrl: "pmtiles://http://localhost:8080/tiles/basemap.pmtiles", assetBaseUrl: "http://localhost:5173/" };

/** Glyph ids in a MapLibre glyph PBF (message glyphs { fontstack stacks = 1 { glyph glyphs = 3 { uint32 id = 1 } } }). */
function glyphIds(buf: Uint8Array): Set<number> {
  const ids = new Set<number>();
  const varint = (b: Uint8Array, p: { i: number }): number => {
    let r = 0;
    let s = 0;
    for (;;) {
      const x = b[p.i++]!;
      r += (x & 0x7f) * 2 ** s;
      if (x < 0x80) return r;
      s += 7;
    }
  };
  const fields = (b: Uint8Array, cb: (field: number, wire: number, sub: Uint8Array | null, v: number) => void) => {
    const p = { i: 0 };
    while (p.i < b.length) {
      const key = varint(b, p);
      const field = key >>> 3;
      const wire = key & 7;
      if (wire === 2) {
        const len = varint(b, p);
        cb(field, wire, b.subarray(p.i, p.i + len), 0);
        p.i += len;
      } else if (wire === 0) cb(field, wire, null, varint(b, p));
      else if (wire === 5) p.i += 4;
      else if (wire === 1) p.i += 8;
      else throw new Error(`wire type ${wire}`);
    }
  };
  fields(buf, (f, _w, stack) => {
    if (f !== 1 || !stack) return;
    fields(stack, (f2, _w2, glyph) => {
      if (f2 !== 3 || !glyph) return;
      fields(glyph, (f3, w3, _s, v) => {
        if (f3 === 1 && w3 === 0) ids.add(v);
      });
    });
  });
  return ids;
}

describe("bundled glyphs", () => {
  it.each(BUNDLED_FONTSTACKS)("%s has all 256 ranges", (stack) => {
    const files = readdirSync(join(PUBLIC, "fonts", stack)).filter((f) => f.endsWith(".pbf"));
    expect(files.length).toBe(256);
    for (let start = 0; start < 65536; start += 256) expect(files).toContain(`${start}-${start + 255}.pbf`);
  });

  it.each(BUNDLED_FONTSTACKS)("%s covers Cyrillic incl. Ө ө Ү ү (AC 6)", (stack) => {
    const ids = glyphIds(readFileSync(join(PUBLIC, "fonts", stack, "1024-1279.pbf")));
    for (const ch of "ӨөҮүАяЁё") expect(ids.has(ch.codePointAt(0)!), `${stack} ${ch}`).toBe(true);
  });

  it("covers every font stack the styles use", () => {
    for (const theme of ["day", "night"] as const) {
      for (const l of buildStyle(theme, CFG).layers) {
        if (l.type !== "symbol") continue;
        for (const f of fontsIn(l.layout?.["text-font"])) expect(existsSync(join(PUBLIC, "fonts", f, "1024-1279.pbf")), f).toBe(true);
      }
    }
  });

  it("ships the OFL licence text", () => {
    expect(readFileSync(join(PUBLIC, "fonts", "OFL.txt"), "utf8")).toContain("SIL OPEN FONT LICENSE Version 1.1");
  });
});

describe("bundled sprites", () => {
  it.each(["light", "dark"])("%s has 1x/2x json+png and every literal icon the style names", (name) => {
    for (const suffix of [".json", ".png", "@2x.json", "@2x.png"]) expect(existsSync(join(PUBLIC, "sprites", "v4", name + suffix))).toBe(true);
    const index = JSON.parse(readFileSync(join(PUBLIC, "sprites", "v4", `${name}.json`), "utf8")) as Record<string, unknown>;
    for (const icon of ["arrow", "capital", "townspot", "train_station"]) expect(index[icon], icon).toBeDefined();
  });
});

describe("SHA256SUMS", () => {
  it("matches every vendored file", () => {
    const lines = readFileSync(join(PUBLIC, "SHA256SUMS"), "utf8").trim().split("\n");
    expect(lines.length).toBe(3 * 256 + 1 + 8);
    for (const line of lines) {
      const [sum, file] = [line.slice(0, 64), line.slice(66)];
      const actual = createHash("sha256").update(readFileSync(join(PUBLIC, file))).digest("hex");
      expect(actual, file).toBe(sum);
    }
  });
});

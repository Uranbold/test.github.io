#!/usr/bin/env python3
"""NAV-020 / NAV-023 search DB builder v2: Photon dump -> search.sqlite (offline search, ADR-0017 §3).

    python3 search_builder.py build    --dump DUMP --out OUT.sqlite [--builder-version 2] [--schema 1]
    python3 search_builder.py selftest --db DB --query Q [--schema 1]
    python3 search_builder.py skeleton TEXT...            # prints one skeleton per argument (test helper)

Production spec: docs/architecture/tasks/NAV-020-offline-pack-build-publication.md §3 (port of the offline spike
prototype). The schema, the normalisation rules and the shared test vectors are owned by NAV-023 (AC 1-5); a change
that alters stored keys increments search_schema. Shared vectors: backend/pack/vectors/search-normalisation.v1.json
(read by the builder tests and the Android engine tests). Reference query plan and ranking: search_engine.py.
v2 (NAV-023, still search_schema 1: no published pack or engine consumed v1): context values from `<key>:mn` first
(as Photon answers lang=mn), a `locality` column (address.neighbourhood, Photon `locality`), the skeleton
joined-word keys also in vocab, host zstd fallback.

Runtime: Python stdlib only. In the pack step it runs in PACK_SEARCH_BUILDER_IMAGE (python:3.14-slim pinned by
digest: SQLite 3.46.1 with FTS5, R*Tree and the trigram tokenizer; compression.zstd for the dump) with
`--network none`, the dump mounted read-only and only the output directory writable. The unit tests import it on
the host Python with a plain (uncompressed) JSONL fixture.

Determinism (ADR-0017 A1 item 9): rows are inserted in a fixed order, every set is sorted before use (Python
string hashing is randomised per process), no timestamps or random values are stored, and the file is finished
with FTS5 optimize, ANALYZE and VACUUM. Same dump + same image digest => same SHA-256.

ODbL: the meta table carries attribution, licence and licence_url (LICENCE_META); the self-test requires them.

Output (stdout): one JSON line with the build facts (rows, seconds, sizes, sqlite_version, builder_version,
search_schema, data_timestamp, source_sha256). Nothing else is printed; no query text, no coordinates.
"""
import argparse
import hashlib
import io
import json
import os
import re
import sqlite3
import sys
import time
import unicodedata

BUILDER_VERSION = 2                     # v2 (NAV-023): `<key>:mn` context, `locality`, joined keys in vocab
SEARCH_SCHEMA = 1
APPLICATION_ID = 0x4E41564D            # "NAVM"
# ODbL notice inside the derivative database itself (NAV-020 review minor): the same values as the manifest's
# `attribution` and `licence` (nav_pack.ODBL_URL), so a search.sqlite copied out of a pack still carries them.
ATTRIBUTION = "© OpenStreetMap contributors"
LICENCE = "ODbL-1.0"
LICENCE_URL = "https://opendatacommons.org/licenses/odbl/1-0/"
LICENCE_META = {"attribution": ATTRIBUTION, "licence": LICENCE, "licence_url": LICENCE_URL}
ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"

# ------------------------------------------------------------------ normalisation (NAV-023 AC 1-2)
TRAD_MONGOLIAN = re.compile("[\u1800-\u18af\u202f]+")
WS = re.compile(r"\s+")
CYR2LAT = {
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ж": "j", "з": "z", "и": "i", "й": "i",
    "к": "k", "л": "l", "м": "m", "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u",
    "ф": "f", "х": "h", "ц": "c", "ч": "ch", "ш": "sh", "щ": "sh", "ы": "i", "э": "e", "ю": "yu", "я": "ya",
    "ь": "", "ъ": "",
}
NAME_KEY = re.compile(r"^(name|alt_name|old_name|short_name|official_name|int_name|loc_name|reg_name)(:.+)?$")
# place column -> dump address key. `locality` is Photon's name for address.neighbourhood (where bag names live).
# Each value is taken from `<key>:mn` when present, else the base key: Photon answers lang=mn that way, so the context
# line equals the online one (the base `city` of Ulaanbaatar is the Russian «Улан-Батор»; `city:mn` is «Улаанбаатар»).
CONTEXT_KEYS = ("street", "suburb", "locality", "district", "city", "county", "state")
ADDRESS_KEY = {"locality": "neighbourhood"}
NON_ALNUM = re.compile(r"[^0-9a-z ]+")
SEP = re.compile(r"[^\w]+", re.UNICODE)


def clean(s):
    """Strip Traditional Mongolian script (U+1800-U+18AF) and U+202F, collapse whitespace, trim (NAV-003 AC 18)."""
    if not isinstance(s, str):
        return ""
    return WS.sub(" ", TRAD_MONGOLIAN.sub(" ", s)).strip()


def fold(s):
    """NAV-023 Terms: lower case, then ү -> у, ө -> о, ё -> е."""
    return s.lower().replace("ү", "у").replace("ө", "о").replace("ё", "е")


def skeleton(s):
    """NAV-023 AC 2, steps 1-4, exactly."""
    # 1. fold and remove Latin diacritics (ü -> u, ö -> o). NFD + dropping combining marks also turns й into и,
    #    which step 2 maps to "i" either way.
    s = unicodedata.normalize("NFD", fold(s))
    s = "".join(ch for ch in s if not unicodedata.combining(ch))
    # 2. Cyrillic -> Latin; ь and ъ are dropped (ү, ө, ё are already folded)
    s = "".join(CYR2LAT.get(ch, ch) for ch in s)
    # 3. on the Latin result: kh -> h, ts -> c, zh -> j, then y -> i
    s = s.replace("kh", "h").replace("ts", "c").replace("zh", "j").replace("y", "i")
    # 4. collapse every run of the same letter to one letter; drop characters other than a-z, 0-9 and spaces
    s = re.sub(r"([a-z])\1+", r"\1", s)
    s = NON_ALNUM.sub("", s)
    return WS.sub(" ", s).strip()


def words(s):
    return [w for w in SEP.split(s) if w]


def joined_pairs(ws):
    return [ws[i] + ws[i + 1] for i in range(len(ws) - 1)]


# ------------------------------------------------------------------ input
def open_dump(path):
    with open(path, "rb") as f:
        magic = f.read(4)
    if magic == ZSTD_MAGIC:
        try:
            from compression import zstd   # Python >= 3.14 (the pinned pack image)
            return io.TextIOWrapper(zstd.open(path, "rb"), encoding="utf-8")
        except ImportError:
            pass
        try:
            import zstandard               # host tools only (search_eval.py on a dev machine); same decoded bytes
        except ImportError:
            sys.exit("search_builder: the dump is zstd-compressed and this Python has neither compression.zstd nor "
                     "zstandard (run it in PACK_SEARCH_BUILDER_IMAGE)")
        return io.TextIOWrapper(zstandard.ZstdDecompressor().stream_reader(open(path, "rb"), closefd=True),
                                encoding="utf-8")
    return open(path, encoding="utf-8")


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def norm_ts(s):
    """'2026-09-26T22:59:05.000+00:00' -> '2026-09-26T22:59:05Z' (UTC offsets only; the dump writes +00:00)."""
    if not s:
        return ""
    m = re.match(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.\d+)?(Z|[+-]00:?00)?$", s)
    return m.group(1) + "Z" if m else s


def osm_type(e):
    t = e.get("object_type")
    return t if t in ("N", "W", "R") else None


def as_int(v, default=0):
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def read_entries(path):
    """(header, [entry, ...]) from the dump; entries are kept only if they have a display name or are an address
    point (housenumber + address.street), task spec §3.3."""
    header, kept = {}, []
    with open_dump(path) as f:
        for line in f:
            if not line.strip():
                continue
            rec = json.loads(line)
            kind = rec.get("type")
            if kind == "NominatimDumpFile":
                header = rec.get("content") or {}
                continue
            if kind != "Place":
                continue
            content = rec.get("content")
            for e in (content if isinstance(content, list) else [content]):
                if not isinstance(e, dict) or not e.get("centroid"):
                    continue
                row = make_row(e)
                if row is not None:
                    kept.append(row)
    return header, kept


def make_row(e):
    names = e.get("name") or {}
    addr = e.get("address") or {}
    display = ""
    for k in ("name:mn", "name", "name:en"):
        display = clean(names.get(k))
        if display:
            break
    street = clean(addr.get("street:mn")) or clean(addr.get("street"))
    hn = clean(e.get("housenumber"))
    if not display and not (hn and street):
        return None
    variants = set()
    for k in sorted(names):
        if NAME_KEY.match(k):
            for part in str(names[k]).split(";"):
                c = clean(part)
                if c:
                    variants.add(c)
    if hn and street:
        variants.add(f"{street} {hn}")       # address points: findable as "<street> <number>"
    ctx = {}
    for k in CONTEXT_KEYS:
        a = ADDRESS_KEY.get(k, k)
        ctx[k] = clean(addr.get(a + ":mn")) or clean(addr.get(a)) or None
    lon, lat = e["centroid"][0], e["centroid"][1]
    bbox = e.get("bbox") or []
    ext = (None, None, None, None)
    if len(bbox) == 4 and not (bbox[0] == bbox[2] and bbox[1] == bbox[3]):
        ext = tuple(round(float(x), 7) for x in bbox)      # Photon extent order: w, n, e, s
    return {
        "sort": (osm_type(e) is None, osm_type(e) or "", as_int(e.get("object_id")), as_int(e.get("place_id")),
                 str(e.get("place_id"))),
        "osm_type": osm_type(e), "osm_id": as_int(e.get("object_id"), None) if e.get("object_id") is not None else None,
        "osm_key": str(e.get("osm_key") or ""), "osm_value": str(e.get("osm_value") or ""),
        "type": str(e.get("address_type") or ""),
        "name": display or None, "name_en": clean(names.get("name:en")) or None,
        "housenumber": hn or None, "postcode": clean(e.get("postcode")) or None,
        "country_code": clean(e.get("country_code")) or None,
        "lat": round(float(lat), 7), "lon": round(float(lon), 7), "ext": ext,
        "importance": float(e.get("importance") or 0.0),
        "variants": sorted(variants), **ctx,
    }


# ------------------------------------------------------------------ build
DDL = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
CREATE TABLE place (
  id INTEGER PRIMARY KEY,
  osm_type TEXT, osm_id INTEGER,
  osm_key TEXT NOT NULL, osm_value TEXT NOT NULL, type TEXT NOT NULL,
  name TEXT, name_en TEXT,
  housenumber TEXT, street TEXT, postcode TEXT, suburb TEXT, locality TEXT, district TEXT, city TEXT, county TEXT, state TEXT,
  country_code TEXT,
  lat REAL NOT NULL, lon REAL NOT NULL,
  ext_w REAL, ext_n REAL, ext_e REAL, ext_s REAL,
  importance REAL NOT NULL
);
CREATE VIRTUAL TABLE place_fts USING fts5(names, skel, ctx, content='',
  tokenize = 'unicode61 remove_diacritics 2', prefix = '1 2 3');
CREATE VIRTUAL TABLE place_tri USING fts5(name, content='', tokenize='trigram');
CREATE TABLE vocab (term TEXT PRIMARY KEY, n INTEGER NOT NULL) WITHOUT ROWID;
CREATE VIRTUAL TABLE place_geo USING rtree(id, min_lon, max_lon, min_lat, max_lat);
"""


def index_keys(row):
    """(names, skel, ctx, vocab tokens) for one place, every list sorted (deterministic)."""
    folded, skels, joined = set(), set(), set()
    for v in row["variants"]:
        f = fold(v)
        folded.add(f)
        fw = words(f)
        joined.update(joined_pairs(fw))
        for s in {skeleton(v), skeleton(" ".join(words(v)))}:
            if s:
                skels.add(s)
                sw = s.split()
                joined.update(joined_pairs(sw))
    # vocab (edit-distance expansion, NAV-023 AC 6): every Latin term of the skel column, i.e. the skeleton words AND
    # the skeleton joined-word keys, so a typo in a joined form («Энхтайвны» for «Энх тайваны») can still expand
    vocab = sorted({t for s in skels for t in s.split()} | {j for j in joined if j.isascii()})
    ctx = " ".join(fold(row[k]) for k in CONTEXT_KEYS if row[k])
    skel_col = " ".join(sorted(skels) + sorted(joined - skels))
    return " ".join(sorted(folded)), skel_col, ctx, vocab


def build(dump, out, builder_version=BUILDER_VERSION, schema=SEARCH_SCHEMA):
    t0 = time.monotonic()
    if os.path.exists(out):
        os.remove(out)
    header, rows = read_entries(dump)
    rows.sort(key=lambda r: r["sort"])
    db = sqlite3.connect(out, isolation_level=None)
    db.execute("PRAGMA page_size = 4096")
    db.execute(f"PRAGMA application_id = {APPLICATION_ID}")
    db.execute(f"PRAGMA user_version = {int(schema)}")
    db.execute("PRAGMA journal_mode = OFF")
    db.execute("PRAGMA synchronous = OFF")
    db.executescript(DDL)
    vocab = {}
    db.execute("BEGIN")
    for i, r in enumerate(rows, 1):
        ext = r["ext"]
        db.execute("INSERT INTO place VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                   (i, r["osm_type"], r["osm_id"], r["osm_key"], r["osm_value"], r["type"], r["name"], r["name_en"],
                    r["housenumber"], r["street"], r["postcode"], r["suburb"], r["locality"], r["district"], r["city"], r["county"],
                    r["state"], r["country_code"], r["lat"], r["lon"], ext[0], ext[1], ext[2], ext[3],
                    r["importance"]))
        names, skel, ctx, toks = index_keys(r)
        db.execute("INSERT INTO place_fts (rowid, names, skel, ctx) VALUES (?,?,?,?)", (i, names, skel, ctx))
        if r["name"]:
            db.execute("INSERT INTO place_tri (rowid, name) VALUES (?,?)", (i, fold(r["name"])))
        db.execute("INSERT INTO place_geo VALUES (?,?,?,?,?)", (i, r["lon"], r["lon"], r["lat"], r["lat"]))
        for t in toks:
            vocab[t] = vocab.get(t, 0) + 1
    db.executemany("INSERT INTO vocab VALUES (?,?)", sorted(vocab.items()))
    meta = {"search_schema": str(int(schema)), "builder_version": str(int(builder_version)),
            "data_timestamp": norm_ts(header.get("data_timestamp")), "source_sha256": sha256_file(dump),
            "sqlite_version": sqlite3.sqlite_version, "place_rows": str(len(rows)),
            "dump_database_version": str(header.get("database_version") or ""), **LICENCE_META}
    db.executemany("INSERT INTO meta VALUES (?,?)", sorted(meta.items()))
    db.execute("COMMIT")
    db.execute("INSERT INTO place_fts (place_fts) VALUES ('optimize')")
    db.execute("INSERT INTO place_tri (place_tri) VALUES ('optimize')")
    db.execute("ANALYZE")
    db.execute("VACUUM")
    check = db.execute("PRAGMA quick_check").fetchone()[0]
    db.close()
    if check != "ok":
        raise SystemExit(f"search_builder: quick_check failed: {check}")
    facts = {"job": "search-builder", "result": "ok", "rows": len(rows), "vocab_terms": len(vocab),
             "bytes": os.path.getsize(out), "seconds": round(time.monotonic() - t0, 1), **meta,
             "search_schema": int(schema), "builder_version": int(builder_version), "quick_check": check}
    return facts


# ------------------------------------------------------------------ server self-test (task spec §3.6, NAV-020 AC 12)
def self_test_match(query):
    toks = skeleton(query).split()
    if not toks:
        return None
    quoted = [f'"{t}"' for t in toks]
    quoted[-1] += "*"
    return "skel : (" + " ".join(quoted) + ")"


def selftest(db_path, query, schema=SEARCH_SCHEMA):
    db = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        quick = db.execute("PRAGMA quick_check").fetchone()[0]
        meta = dict(db.execute("SELECT key, value FROM meta").fetchall())
        user_version = db.execute("PRAGMA user_version").fetchone()[0]
        expr = self_test_match(query)
        hits = db.execute("SELECT count(*) FROM place_fts WHERE place_fts MATCH ?", (expr,)).fetchone()[0] if expr else 0
        trad = db.execute("SELECT count(*) FROM place WHERE "
                          + " OR ".join(f"{c} GLOB '*[\u1800-\u18af]*'" for c in
                                        ("name", "name_en", "street", "suburb", "locality", "district", "city", "county",
                                         "state"))
                          ).fetchone()[0]
    finally:
        db.close()
    problems = []
    if quick != "ok":
        problems.append(f"quick_check: {quick}")
    if str(meta.get("search_schema")) != str(schema) or user_version != int(schema):
        problems.append(f"search_schema meta={meta.get('search_schema')} user_version={user_version}, configured {schema}")
    if hits < 1:
        problems.append("self-test query returned 0 rows")
    for k, v in LICENCE_META.items():
        if meta.get(k) != v:
            problems.append(f"meta.{k}={meta.get(k)!r}, expected {v!r}")
    if trad:
        problems.append(f"{trad} rows contain Traditional Mongolian script")
    return {"job": "search-selftest", "result": "ok" if not problems else "failed", "quick_check": quick,
            "search_schema": meta.get("search_schema"), "hits": hits, "trad_rows": trad, "problems": problems,
            "sqlite_version": sqlite3.sqlite_version}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("--dump", required=True)
    b.add_argument("--out", required=True)
    b.add_argument("--builder-version", type=int, default=BUILDER_VERSION)
    b.add_argument("--schema", type=int, default=SEARCH_SCHEMA)
    s = sub.add_parser("selftest")
    s.add_argument("--db", required=True)
    s.add_argument("--query", required=True)
    s.add_argument("--schema", type=int, default=SEARCH_SCHEMA)
    k = sub.add_parser("skeleton")
    k.add_argument("text", nargs="+")
    a = ap.parse_args(argv)
    if a.cmd == "build":
        if a.builder_version != BUILDER_VERSION or a.schema != SEARCH_SCHEMA:
            print(json.dumps({"job": "search-builder", "result": "failed",
                              "reason": f"this is builder v{BUILDER_VERSION} (search_schema {SEARCH_SCHEMA}); configured "
                                        f"v{a.builder_version} / schema {a.schema}"}), flush=True)
            return 3
        print(json.dumps(build(a.dump, a.out, a.builder_version, a.schema), ensure_ascii=False), flush=True)
        return 0
    if a.cmd == "selftest":
        r = selftest(a.db, a.query, a.schema)
        print(json.dumps(r, ensure_ascii=False), flush=True)
        return 0 if r["result"] == "ok" else 1
    for t in a.text:
        print(skeleton(t))
    return 0


if __name__ == "__main__":
    sys.exit(main())

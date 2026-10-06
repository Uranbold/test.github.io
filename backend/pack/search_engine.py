#!/usr/bin/env python3
"""NAV-023 reference on-device search engine (Python) over search.sqlite: the query plan and ranking the Android engine
ports, used by search_eval.py to measure the D168 gate and the D198 held-out report before the Kotlin engine exists.

    python3 search_engine.py search --db search.sqlite --q "Сүхбаатар" [--lang mn] [--bias 47.9189,106.9176]
    python3 search_engine.py reverse --db search.sqlite --at 47.9189,106.9176

What is ported unchanged from the Android app (ADR-0012, mobile/android/.../search/assist/*.kt):
  - Settle (NFC, JS whitespace set, trim, 200 code units), the minimum length 2
  - CoordinateInput (typed coordinates: 0 queries to the file, NAV-023 AC 8)
  - QueryPlanner rules A-D (ADR-0006 abbreviations, Latin -> Cyrillic transliteration, Russian-layout vowel variant)
    with their modes (parallel / ifEmpty / none); every planned query is one "request" to the file
  - Merge (interleave, de-duplicate on osm_type+osm_id, MN first, 10 options) and PlaceDisplay (type-label rules
    1-32, name, context line) on a PhotonFeature-shaped dict
What is new (NAV-023 AC 6, the part the Kotlin engine must implement the same way; ranking FROZEN as RANKING_VERSION):
  1. match: the folded words of the query on {names skel ctx} AND the skeleton words on skel, each an AND of tokens
     with a prefix match on the last token; bm25 weights names 10, skel 5, ctx 1; top CANDIDATES rows
  2. 0 hits -> edit-distance expansion of the skeleton tokens over vocab (1 edit for 5-8 letters, 2 for >= 9; a
     token already in vocab, or the last token when it prefixes a vocab term, is kept)
  3. still 0 hits -> trigram fallback on place_tri (folded display name): OR of the query's 3-grams, kept when
     >= TRIGRAM_MIN_SHARE of them occur in the name
  4. rank (score in rank()), keep PER_QUERY_LIMIT (= the online request's limit 8)
Reverse (AC 20): nearest place with a display name (name, or street + house number) within 500 m (the online
radius), expanding box on place_geo.

Privacy: this module prints nothing by itself and logs nothing; the CLI prints results for a query the operator typed.
Normalisation (clean, fold, skeleton, words) is imported from search_builder.py so builder and engine cannot drift.
"""
import argparse
import json
import math
import re
import sqlite3
import sys
import time
import unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from search_builder import clean, fold, skeleton, words  # noqa: E402

KNOWN_SEARCH_SCHEMAS = {1}
RANKING_VERSION = 1           # bump when rank() or the stage rules change (the held-out report names it)
PER_QUERY_LIMIT = 8           # mobile SearchClient.LIMIT: the online request's `limit`
MAX_OPTIONS = 10              # Merge.MAX_OPTIONS
CANDIDATES = 300              # rows taken from FTS5 (by bm25) into rank()
TRIGRAM_MIN_SHARE = 0.5
REVERSE_RADIUS_M = 500        # Reverse.kt RADIUS_KM = 0.5
FUZZY_MAX_CANDIDATES = 8      # vocab terms per expanded token (closest, then most frequent)

# ------------------------------------------------------------------ ADR-0012 port: Settle / Scripts / coordinates
_JS_WS = ("\t\n\u000b\f\r \u00a0\u1680" + "".join(chr(c) for c in range(0x2000, 0x200B))
          + "\u2028\u2029\u202f\u205f\u3000\ufeff")          # Settle.WS_CLASS (the JavaScript \\s set)
_WHITESPACE = re.compile("[" + re.escape(_JS_WS) + "]+")
_WORD_SEPARATORS = re.compile("[" + re.escape(_JS_WS) + "-]+")
_COORDINATE = re.compile(r"^(-?[0-9]{1,2}(?:\.[0-9]+)?)(?:\s*,\s*|\s+)(-?[0-9]{1,3}(?:\.[0-9]+)?)$")


def _js_trim(s):
    return s.strip(_JS_WS)


def settle(raw):
    return cap(_js_trim(_WHITESPACE.sub(" ", unicodedata.normalize("NFC", raw))))


def cap(s):
    """<= 200 UTF-16 code units without splitting a surrogate pair (Settle.cap)."""
    units = s.encode("utf-16-le")
    if len(units) <= 400:
        return s
    return _js_trim(units[:400].decode("utf-16-le", errors="ignore"))


def _letters(s):
    return [c for c in s if c.isalpha()]


def is_latin_only(s):
    ls = _letters(s)
    return len(ls) >= 2 and all(unicodedata.name(c, "").startswith("LATIN") for c in ls)


def is_cyrillic_only(s):
    ls = _letters(s)
    return bool(ls) and all(unicodedata.name(c, "").startswith("CYRILLIC") for c in ls)


def parse_coordinate(settled):
    m = _COORDINATE.match(settled)
    if not m:
        return None
    lat, lon = float(m.group(1)), float(m.group(2))
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None
    return lat, lon


# ------------------------------------------------------------------ ADR-0006 §2.4 Latin -> Cyrillic (LatinToCyrillic.kt)
_PRECOMPOSED = {"ö": "ө", "ő": "ө", "ü": "ү", "ű": "ү"}
_FRONT = {"ө", "ү"}
_MULTI = [("shch", "щ"), ("kh", "х"), ("ts", "ц"), ("ch", "ч"), ("sh", "ш"), ("zh", "ж"),
          ("ya", "я"), ("yu", "ю"), ("yo", "ё"), ("ye", "е"), ("ii", "ий")]
_SINGLE = {"a": "а", "b": "б", "c": "ц", "d": "д", "e": "э", "f": "ф", "g": "г", "h": "х", "i": "и", "j": "ж",
           "k": "к", "l": "л", "m": "м", "n": "н", "p": "п", "q": "к", "r": "р", "s": "с", "t": "т", "v": "в",
           "w": "в", "x": "х", "z": "з"}
_VOWELS = {"a", "e", "i", "o", "u"} | _FRONT


def _prepare(s):
    s = "".join(_PRECOMPOSED.get(c, c) for c in s.lower())
    s = "".join(c for c in unicodedata.normalize("NFD", s) if not ("̀" <= c <= "ͯ"))
    return unicodedata.normalize("NFC", s)


def _front_word(w):
    return "a" not in w and any(c == "e" or c in _FRONT for c in w)


def _latin_word(w):
    front, out, i = _front_word(w), [], 0
    while i < len(w):
        multi = next(((f, t) for f, t in _MULTI if w.startswith(f, i)), None)
        if multi:
            out.append(multi[1])
            i += len(multi[0])
            continue
        ch = w[i]
        prev = w[i - 1] if i > 0 else ""
        nxt = w[i + 1] if i + 1 < len(w) else ""
        if ch in "yi" and prev in _VOWELS and nxt not in _VOWELS:
            out.append("й")
        elif ch == "u":
            out.append("ү" if front else "у")
        elif ch == "o":
            out.append("ө" if front else "о")
        elif ch == "y":
            out.append("ы")
        else:
            out.append(_SINGLE.get(ch, ch))
        i += 1
    return "".join(out)


def latin_to_cyrillic(s):
    s = _prepare(s)
    out, last = [], 0
    for m in _WORD_SEPARATORS.finditer(s):
        out.append(_latin_word(s[last:m.start()]))
        out.append(m.group(0))
        last = m.end()
    out.append(_latin_word(s[last:]))
    return "".join(out)


# ------------------------------------------------------------------ QueryPlanner (rules A-D)
ABBREVIATIONS = {  # ADR-0006 §2.3 rule A (web/src/search/lexicon.json; OSM search data, not UI text)
    "СБД": "Сүхбаатар дүүрэг", "БЗД": "Баянзүрх дүүрэг", "ХУД": "Хан-Уул дүүрэг",
    "БГД": "Баянгол дүүрэг", "ЧД": "Чингэлтэй дүүрэг", "СХД": "Сонгинохайрхан дүүрэг",
}
_ABBR_LOWER = {k.lower(): v for k, v in ABBREVIATIONS.items()}
_VOWEL_FALLBACK = {"у": "ү", "У": "Ү", "о": "ө", "О": "Ө"}


def plan(text):
    """{'kind': 'skip'|'coordinate'|'text', ...}; text plans carry primary, secondary, mode, rule (QueryPlanner.plan)."""
    q = settle(text)
    if len(q) < 2:
        return {"kind": "skip"}
    c = parse_coordinate(q)
    if c:
        return {"kind": "coordinate", "point": c}

    def text_plan(p, s, mode, rule):
        p, s = cap(p), (cap(s) if s is not None else None)
        if s is None or settle(s) == settle(p):
            return {"kind": "text", "primary": p, "secondary": None, "mode": "none", "rule": rule}
        return {"kind": "text", "primary": p, "secondary": s, "mode": mode, "rule": rule}

    toks = q.split(" ")
    if any(t.lower() in _ABBR_LOWER for t in toks):
        return text_plan(" ".join(_ABBR_LOWER.get(t.lower(), t) for t in toks), q, "parallel", "A")
    if is_latin_only(q):
        return text_plan(q, latin_to_cyrillic(q), "parallel", "B")
    if is_cyrillic_only(q) and any(c in _VOWEL_FALLBACK for c in q):
        return text_plan(q, "".join(_VOWEL_FALLBACK.get(c, c) for c in q), "ifEmpty", "C")
    return text_plan(q, None, "none", "D")


# ------------------------------------------------------------------ Merge (ADR-0006 §2.5)
def feature_key(f):
    if f.get("osm_type") in ("N", "W", "R") and f.get("osm_id") is not None:
        return f"{f['osm_type']}{f['osm_id']}"
    return f"?{f.get('name') or ''}@{f.get('lon')},{f.get('lat')}"


def merge(primary, secondary=None):
    allf = primary if secondary is None else [x for i in range(max(len(primary), len(secondary)))
                                              for x in (primary[i:i + 1] + secondary[i:i + 1])]
    seen, out = set(), []
    for f in allf:
        k = feature_key(f)
        if k not in seen:
            seen.add(k)
            out.append(f)
    mn = [f for f in out if (f.get("countrycode") or "").upper() == "MN"]
    return (mn + [f for f in out if (f.get("countrycode") or "").upper() != "MN"])[:MAX_OPTIONS]


# ------------------------------------------------------------------ PlaceDisplay (NAV-003 AC 17-19 type-label rules)
_SUFFIX = {"district": [" дүүрэг", " duureg", " düüreg", " district"], "khoroo": [" хороо", " khoroo", " horoo"],
           "aimag": [" аймаг", " aimag", " province"], "soum": [" сум", " sum", " soum"]}


def _ends(name, kind):
    n = unicodedata.normalize("NFC", name).lower()
    return any(n.endswith(s) for s in _SUFFIX[kind])


def _is(f, key, values=None):
    return f.get("osm_key") == key and (values is None or f.get("osm_value") in values)


# (glossary §4.1 row number, test) in PlaceDisplay.RULES order; first match wins; row 32 «Газар» otherwise
_RULES = [
    (1, lambda f, n: _ends(n, "district")),
    (2, lambda f, n: _ends(n, "khoroo")),
    (3, lambda f, n: _ends(n, "aimag") or f.get("type") == "state" or _is(f, "place", ["state"])),
    (4, lambda f, n: (_ends(n, "soum") and f.get("osm_key") in ("boundary", "place"))
     or (_is(f, "boundary", ["administrative"]) and f.get("type") == "county")),
    (1, lambda f, n: _is(f, "boundary", ["administrative"]) and f.get("type") == "district"),
    (5, lambda f, n: _is(f, "place", ["city", "town"])),
    (6, lambda f, n: _is(f, "place", ["village", "hamlet", "isolated_dwelling", "locality"])),
    (7, lambda f, n: _is(f, "place", ["suburb", "neighbourhood", "quarter"])),
    (8, lambda f, n: _is(f, "place", ["square"])),
    (9, lambda f, n: _is(f, "amenity", ["fuel"])),
    (10, lambda f, n: _is(f, "amenity", ["hospital", "clinic", "doctors"])),
    (11, lambda f, n: _is(f, "amenity", ["pharmacy"])),
    (12, lambda f, n: _is(f, "amenity", ["school"])),
    (13, lambda f, n: _is(f, "amenity", ["university", "college"])),
    (14, lambda f, n: _is(f, "amenity", ["restaurant", "cafe", "fast_food"])),
    (15, lambda f, n: _is(f, "tourism", ["hotel", "hostel", "guest_house", "motel"])),
    (16, lambda f, n: _is(f, "shop", ["mall", "department_store"])),
    (17, lambda f, n: _is(f, "shop")),
    (18, lambda f, n: _is(f, "amenity", ["marketplace"])),
    (19, lambda f, n: _is(f, "amenity", ["bank", "atm"])),
    (20, lambda f, n: _is(f, "highway", ["bus_stop"]) or _is(f, "public_transport", ["platform", "stop_position"])),
    (21, lambda f, n: _is(f, "railway", ["station", "halt"])),
    (22, lambda f, n: _is(f, "aeroway", ["aerodrome", "terminal"])),
    (23, lambda f, n: _is(f, "amenity", ["parking"])),
    (24, lambda f, n: _is(f, "tourism", ["museum"])),
    (25, lambda f, n: _is(f, "historic") or _is(f, "tourism", ["attraction", "viewpoint"])),
    (26, lambda f, n: _is(f, "amenity", ["place_of_worship"])),
    (27, lambda f, n: _is(f, "leisure", ["park", "garden"])),
    (28, lambda f, n: _is(f, "office", ["government"]) or _is(f, "amenity", ["townhall"])),
    (29, lambda f, n: _is(f, "office", ["diplomatic"]) or _is(f, "amenity", ["embassy"])),
    (30, lambda f, n: _is(f, "highway")),
    (31, lambda f, n: bool(clean(f.get("housenumber") or ""))),
]


def type_row(f):
    n = clean(f.get("name") or "")
    return next((row for row, test in _RULES if test(f, n)), 32)


def display_name(f):
    n = clean(f.get("name") or "")
    if n:
        return n
    return " ".join(x for x in (clean(f.get("street") or ""), clean(f.get("housenumber") or "")) if x) or None


def context_line(f, name):
    out = []
    for k in ("district", "locality", "city", "county", "state"):
        v = clean(f.get(k) or "")
        if not v or v == name or v in out:
            continue
        out.append(v)
        if len(out) == 2:
            break
    return ", ".join(out) or None


# ------------------------------------------------------------------ geometry
def haversine_m(a_lat, a_lon, b_lat, b_lon):
    r = 6371008.8
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dp, dl = p2 - p1, math.radians(b_lon - a_lon)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(h)))


def levenshtein_within(a, b, k):
    """Edit distance of a and b if <= k, else None (banded DP)."""
    if abs(len(a) - len(b)) > k:
        return None
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        lo = max(1, i - k)
        hi = min(len(b), i + k)
        if lo > 1:
            cur[lo - 1] = k + 1
        for j in range(lo, hi + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != b[j - 1]))
        if hi < len(b):
            cur[hi + 1:] = [k + 1] * (len(b) - hi)
        if min(cur) > k:
            return None
        prev = cur
    return prev[-1] if prev[-1] <= k else None


def _phrase(t, prefix=False):
    return '"' + t.replace('"', '""') + '"' + ("*" if prefix else "")


def _conj(toks):
    return " AND ".join(_phrase(t, i == len(toks) - 1) for i, t in enumerate(toks))


def trigrams(s):
    s = s.replace(" ", "")
    return {s[i:i + 3] for i in range(len(s) - 2)}


# ------------------------------------------------------------------ the engine
class SearchEngine:
    def __init__(self, path):
        self.db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        self.meta = dict(self.db.execute("SELECT key, value FROM meta").fetchall())
        schema = int(self.meta.get("search_schema", "0"))
        if schema not in KNOWN_SEARCH_SCHEMAS:
            raise ValueError(f"search_schema {schema} not known (known: {sorted(KNOWN_SEARCH_SCHEMAS)})")
        self._vocab = None
        self.file_queries = 0          # statements against the file (AC 8: 0 for coordinates)

    def close(self):
        self.db.close()

    # -- vocab (the Kotlin engine builds a SymSpell/BK index lazily per file version; a scan is fine here)
    def vocab(self):
        if self._vocab is None:
            self._vocab = dict(self.db.execute("SELECT term, n FROM vocab"))
            self._vocab_sorted = sorted(self._vocab)
        return self._vocab

    def _prefixes_vocab(self, t):
        import bisect
        self.vocab()
        i = bisect.bisect_left(self._vocab_sorted, t)
        return i < len(self._vocab_sorted) and self._vocab_sorted[i].startswith(t)

    def _fts(self, expr):
        self.file_queries += 1
        return self.db.execute("SELECT rowid, bm25(place_fts, 10.0, 5.0, 1.0) AS s FROM place_fts "
                               "WHERE place_fts MATCH ? ORDER BY s LIMIT ?", (expr, CANDIDATES)).fetchall()

    def _rows(self, ids):
        if not ids:
            return {}
        self.file_queries += 1
        cur = self.db.execute(f"SELECT * FROM place WHERE id IN ({','.join('?' * len(ids))})", list(ids))
        cols = [d[0] for d in cur.description]
        return {r[0]: dict(zip(cols, r)) for r in cur.fetchall()}

    def _match(self, ftoks, stoks):
        parts = []
        if ftoks:
            parts.append("{names skel ctx} : (" + _conj(ftoks) + ")")
        if stoks:
            parts.append("skel : (" + _conj(stoks) + ")")
        return self._fts(" OR ".join(parts))

    def _fuzzy(self, stoks):
        vocab = self.vocab()
        groups, changed = [], False
        for i, t in enumerate(stoks):
            last = i == len(stoks) - 1
            if t in vocab or (last and self._prefixes_vocab(t)):
                groups.append(_phrase(t, last))
                continue
            k = 1 if 5 <= len(t) <= 8 else 2 if len(t) >= 9 else 0
            cands = []
            if k:
                for v, n in vocab.items():
                    d = levenshtein_within(t, v, k)
                    if d is not None:
                        cands.append((d, -n, v))
            cands = [v for _, _, v in sorted(cands)[:FUZZY_MAX_CANDIDATES]]
            if not cands:
                groups.append(_phrase(t, last))
                continue
            changed = True
            groups.append("(" + " OR ".join(_phrase(v) for v in cands) + ")")
        if not changed:
            return []
        return self._fts("skel : (" + " AND ".join(groups) + ")")

    def _trigram(self, ftoks):
        q = "".join(ftoks)
        grams = sorted(trigrams(q))
        if not grams:
            return []
        self.file_queries += 1
        hits = self.db.execute("SELECT rowid, bm25(place_tri) AS s FROM place_tri WHERE place_tri MATCH ? "
                               "ORDER BY s LIMIT ?", (" OR ".join(_phrase(g) for g in grams), CANDIDATES)).fetchall()
        if not hits:
            return []
        rows = self._rows([h[0] for h in hits])
        need = math.ceil(TRIGRAM_MIN_SHARE * len(grams))
        return [h for h in hits if h[0] in rows
                and len(trigrams(fold(rows[h[0]]["name"] or "")) & set(grams)) >= need]

    def rank(self, hits, rows, ftoks, stoks, bias):
        """RANKING_VERSION 1 (frozen 2026-10-05 on the golden set, before the held-out set was written).

        score = 3 exact + 1.5 prefix + 1 bm25/best_bm25 + 2 importance + 4 proximity, proximity = 1 / (1 + d_km / 2)
        to the bias point (0 without bias); exact/prefix compare the folded words or the skeleton of the query with the
        display name and the skeleton of name:en; ties by place.id. Weights 4-6 and scales 1-3 km all gave 29/30.
        """
        if not hits:
            return []
        qf, qs = " ".join(ftoks), " ".join(stoks)
        best = min(s for _, s in hits) or -1.0
        scored = []
        for rid, s in hits:
            r = rows.get(rid)
            if r is None:
                continue
            nm = r["name"] or " ".join(x for x in (r["street"], r["housenumber"]) if x)
            nf = " ".join(words(fold(nm)))
            ns = skeleton(nm)
            es = skeleton(r["name_en"]) if r["name_en"] else ""
            exact = qf == nf or (qs and (qs == ns or qs == es or qs.replace(" ", "") == ns.replace(" ", "")))
            prefix = (qf and nf.startswith(qf)) or (qs and (ns.startswith(qs) or (es and es.startswith(qs))))
            rel = (s / best) if best < 0 else 0.0
            prox = 0.0
            if bias:
                d_km = haversine_m(bias[0], bias[1], r["lat"], r["lon"]) / 1000
                prox = 1.0 / (1.0 + d_km / 2.0)
            score = 3.0 * bool(exact) + 1.5 * bool(prefix) + 1.0 * rel + 2.0 * r["importance"] + 4.0 * prox
            scored.append((-score, rid, r))
        scored.sort(key=lambda x: (x[0], x[1]))
        return [r for _, _, r in scored]

    def query(self, q, bias=None, lang="mn"):
        """One planned query (= one online request): (features <= PER_QUERY_LIMIT, stage)."""
        qc = clean(q)
        ftoks, stoks = words(fold(qc)), skeleton(qc).split()
        if not ftoks and not stoks:
            return [], "empty"
        stage, hits = "match", self._match(ftoks, stoks)
        if not hits and stoks:
            stage, hits = "fuzzy", self._fuzzy(stoks)
        if not hits:
            stage, hits = "trigram", self._trigram(ftoks)
        rows = self._rows([h[0] for h in hits])
        ranked = self.rank(hits, rows, ftoks, stoks, bias)[:PER_QUERY_LIMIT]
        return [to_feature(r, lang) for r in ranked], (stage if hits else "none")

    def search(self, text, lang="mn", bias=None):
        """A settled query through the ADR-0012 plan: {'kind', 'features', 'plan', 'stages', 'file_queries', 'ms'}."""
        t0 = time.perf_counter()
        before = self.file_queries
        p = plan(text)
        out = {"kind": p["kind"], "plan": p, "features": [], "stages": []}
        if p["kind"] == "text":
            prim, st1 = self.query(p["primary"], bias, lang)
            out["stages"].append(st1)
            if p["mode"] == "parallel":
                sec, st2 = self.query(p["secondary"], bias, lang)
                out["stages"].append(st2)
                out["features"] = merge(prim, sec)
            elif p["mode"] == "ifEmpty" and not prim:
                sec, st2 = self.query(p["secondary"], bias, lang)
                out["stages"].append(st2)
                out["features"] = merge(sec)
            else:
                out["features"] = merge(prim)
        out["file_queries"] = self.file_queries - before
        out["ms"] = round((time.perf_counter() - t0) * 1000, 2)
        return out

    def reverse(self, lat, lon, lang="mn", radius_m=REVERSE_RADIUS_M):
        """Nearest place with a display name (a name, or street + house number as PlaceDisplay.name) within radius_m
        (expanding box), as a feature with 'distance_m'; None if none."""
        for r in (25, 50, 100, 200, 350, radius_m):
            r = min(r, radius_m)
            dlat = r / 111320.0
            dlon = r / (111320.0 * max(0.01, math.cos(math.radians(lat))))
            self.file_queries += 1
            cur = self.db.execute(
                "SELECT p.* FROM place_geo g JOIN place p ON p.id = g.id WHERE g.min_lon <= ? AND g.max_lon >= ? "
                "AND g.min_lat <= ? AND g.max_lat >= ? "
                "AND (p.name IS NOT NULL OR (p.housenumber IS NOT NULL AND p.street IS NOT NULL))",
                (lon + dlon, lon - dlon, lat + dlat, lat - dlat))
            cols = [d[0] for d in cur.description]
            best = None
            for row in cur.fetchall():
                rr = dict(zip(cols, row))
                d = haversine_m(lat, lon, rr["lat"], rr["lon"])
                if d <= r and (best is None or (d, rr["id"]) < (best[0], best[1]["id"])):
                    best = (d, rr)
            if best:
                f = to_feature(best[1], lang)
                f["distance_m"] = round(best[0], 1)
                return f
            if r >= radius_m:
                break
        return None


def to_feature(r, lang="mn"):
    """place row -> PhotonFeature-shaped dict (NAV-023 SM4). Photon maps address.suburb to `district`."""
    name = r["name"]
    if lang == "en" and r.get("name_en"):
        name = r["name_en"]
    return {"osm_type": r["osm_type"], "osm_id": r["osm_id"], "osm_key": r["osm_key"], "osm_value": r["osm_value"],
            "type": r["type"], "name": name, "housenumber": r["housenumber"], "street": r["street"],
            "postcode": r["postcode"], "district": r["suburb"] or r["district"], "city": r["city"],
            "county": r["county"], "state": r["state"], "countrycode": (r["country_code"] or "").upper() or None,
            "lat": r["lat"], "lon": r["lon"]}


def _point(s):
    a, b = s.split(",")
    return float(a), float(b)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("search")
    s.add_argument("--db", required=True)
    s.add_argument("--q", required=True)
    s.add_argument("--lang", default="mn", choices=["mn", "en"])
    s.add_argument("--bias", type=_point)
    r = sub.add_parser("reverse")
    r.add_argument("--db", required=True)
    r.add_argument("--at", type=_point, required=True)
    r.add_argument("--lang", default="mn", choices=["mn", "en"])
    a = ap.parse_args(argv)
    eng = SearchEngine(a.db)
    try:
        if a.cmd == "search":
            res = eng.search(a.q, a.lang, a.bias)
            for f in res["features"]:
                f["label_row"] = type_row(f)
            print(json.dumps(res, ensure_ascii=False, indent=1))
        else:
            print(json.dumps(eng.reverse(a.at[0], a.at[1], a.lang), ensure_ascii=False, indent=1))
    finally:
        eng.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

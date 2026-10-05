#!/usr/bin/env python3
"""NAV-023 offline search measurement: D168 gate (AC 17), D198 held-out report (AC 19), reverse comparison (AC 20)
and the AC 5 determinism check, on a built search.sqlite with the reference engine (search_engine.py: the ADR-0012
query plan and the AC 6 on-device steps the Android engine ports).

    python3 pack/search_eval.py golden  --db search.sqlite [--online URL] [--db2 second.sqlite] [--out report.json]
    python3 pack/search_eval.py heldout --db search.sqlite --online URL [--set pack/heldout/countryside-v1.json]
    python3 pack/search_eval.py reverse --db search.sqlite --online URL
    make search-eval DB=<search.sqlite> [ONLINE=http://127.0.0.1:8080] [DB2=<second build>]

--online is a gateway base URL serving Photon from the SAME slot the file was cut from (only read-only GET
/v1/search and /v1/reverse, paced to <= 2 requests/s). The online answer goes through the same plan and merge as the
app (ADR-0006 rules A-D, limit 8, bias rounded to 3 decimals).

Golden (AC 17): applicable rows are tier A and B minus the coordinate row (A15; checked separately: 0 file queries).
Type-label, name, distance and top-N conditions are evaluated as tests/e2e/nav003/golden.test.mjs does. With
--online, a row that fails offline AND online is a data gap (not counted); the gate passes when
passed x 10 >= counted x 9. The all-rows count (Open question 2 option (b)) is reported next to it.
Held-out (AC 19, D198, report only): a row passes when the object online ranks first is in the offline top 5;
rows with 0 online results are excluded and listed. Never a gate.

Exit code: 0 always for heldout/reverse (reports); golden exits 1 when the gate fails, 0 otherwise.
No user data: every query comes from a committed fixture; nothing is logged.
"""
import argparse
import json
import re
import sqlite3
import statistics
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import search_engine as se  # noqa: E402

REPO = HERE.parents[1]
GOLDEN = REPO / "tests" / "e2e" / "nav003" / "fixtures" / "golden-set.json"
HELDOUT = HERE / "heldout" / "countryside-v1.json"
# P1-P6 (NAV-001 reference locations, tests/api/nav001/lib.py)
REVERSE_POINTS = {"P1": (47.9189, 106.9176), "P2": (47.9139, 106.9044), "P3": (47.8858, 106.9173),
                  "P4": (47.9215, 106.8950), "P5": (47.9095, 106.8835), "P6": (47.9600, 106.9000)}
# Type-label table (glossary §4.1 rows; the same strings as tests/e2e/nav003/helpers.mjs TYPE_LABELS), used only to
# compare with the golden fixture's expectations.
TYPE_LABELS = {
    1: ("Дүүрэг", "District"), 2: ("Хороо", "Khoroo"), 3: ("Аймаг", "Аймаг"), 4: ("Сум", "Сум"),
    5: ("Хот", "City or town"), 6: ("Суурин", "Settlement"), 7: ("Хороолол", "Neighbourhood"), 8: ("Талбай", "Square"),
    9: ("ШТС", "Petrol station"), 10: ("Эмнэлэг", "Hospital or clinic"), 11: ("Эмийн сан", "Pharmacy"),
    12: ("Сургууль", "School"), 13: ("Их сургууль", "University or college"), 14: ("Хоолны газар", "Restaurant or café"),
    15: ("Зочид буудал", "Hotel"), 16: ("Худалдааны төв", "Shopping centre"), 17: ("Дэлгүүр", "Shop"),
    18: ("Зах", "Market"), 19: ("Банк", "Bank or ATM"), 20: ("Автобусны буудал", "Bus stop"),
    21: ("Галт тэрэгний буудал", "Railway station"), 22: ("Нисэх онгоцны буудал", "Airport"), 23: ("Зогсоол", "Parking"),
    24: ("Музей", "Museum"), 25: ("Дурсгалт газар", "Monument or historic site"), 26: ("Сүм хийд", "Place of worship"),
    27: ("Цэцэрлэгт хүрээлэн", "Park"), 28: ("Төрийн байгууллага", "Government office"),
    29: ("Элчин сайдын яам", "Embassy"), 30: ("Зам", "Road"), 31: ("Хаяг", "Address"), 32: ("Газар", "Place"),
}


def label(f, ui):
    return TYPE_LABELS[se.type_row(f)][1 if ui == "en" else 0]


def bias_point(p):
    """D30 / D174: the bias sent (and used offline) is the shown position rounded to 3 decimals."""
    return None if p is None else (round(p[0], 3), round(p[1], 3))


# ------------------------------------------------------------------ online (same plan and merge as the app)
class Online:
    def __init__(self, base, pace=0.55):
        self.base, self.pace, self.last, self.requests = base.rstrip("/"), pace, 0.0, 0

    def _get(self, path, params):
        wait = self.last + self.pace - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        url = f"{self.base}{path}?{urllib.parse.urlencode(params)}"
        try:
            with urllib.request.urlopen(url, timeout=15) as r:
                body = json.loads(r.read().decode("utf-8"))
        finally:
            self.last = time.monotonic()
            self.requests += 1
        return [_photon(f) for f in body.get("features", []) if (f.get("geometry") or {}).get("type") == "Point"]

    def search_one(self, q, lang, bias):
        params = {"q": q, "lang": lang, "limit": se.PER_QUERY_LIMIT}
        if bias:
            params.update(lat=f"{bias[0]:.3f}", lon=f"{bias[1]:.3f}")
        return self._get("/v1/search", params)

    def search(self, text, lang, bias):
        p = se.plan(text)
        if p["kind"] != "text":
            return {"kind": p["kind"], "features": []}
        prim = self.search_one(p["primary"], lang, bias)
        if p["mode"] == "parallel":
            return {"kind": "text", "features": se.merge(prim, self.search_one(p["secondary"], lang, bias))}
        if p["mode"] == "ifEmpty" and not prim:
            return {"kind": "text", "features": se.merge(self.search_one(p["secondary"], lang, bias))}
        return {"kind": "text", "features": se.merge(prim)}

    def reverse(self, lat, lon, lang="mn"):
        fs = self._get("/v1/reverse", {"lat": f"{lat:.6f}", "lon": f"{lon:.6f}", "lang": lang, "limit": 1,
                                       "radius": 0.5})
        return fs[0] if fs else None


def _photon(f):
    p = dict(f.get("properties") or {})
    p["lon"], p["lat"] = f["geometry"]["coordinates"][:2]
    return p


# ------------------------------------------------------------------ golden evaluation (port of golden.test.mjs evaluate)
def evaluate(row, res, refs):
    e, ui = row["expect"], row["ui"]
    feats = res["features"]
    if e.get("noResults"):
        return {"pass": res["kind"] == "text" and not feats, "rank": None}
    for i, f in enumerate(feats[:e["top"]]):
        name = se.display_name(f) or ""
        conds = []
        if "near" in e:
            ref = refs[e["near"]]
            conds.append(se.haversine_m(ref[0], ref[1], f["lat"], f["lon"]) <= e["m"])
        if "type" in e:
            lb = label(f, ui)
            conds.append(lb == e["type"] or (ui == "en" and e["type"] == "Дүүрэг" and lb == "District"))
        flags = re.I if "i" in (e.get("flags") or "") else 0
        if "nameContains" in e:
            conds.append(re.search(e["nameContains"], name, flags) is not None)
        if "nameOrContextContains" in e:
            ctx = se.context_line(f, name) or ""
            conds.append(re.search(e["nameOrContextContains"], f"{name} {ctx}", flags) is not None)
        if all(conds):
            return {"pass": True, "rank": i + 1}
    return {"pass": False, "rank": None}


def listing(res, row, refs, n=5):
    out, e = [], row["expect"]
    for f in res["features"][:n]:
        name = se.display_name(f)
        d = ""
        if "near" in e:
            ref = refs[e["near"]]
            d = f" {round(se.haversine_m(ref[0], ref[1], f['lat'], f['lon']))}m"
        out.append(f"{name}[{label(f, row['ui'])}]{d} {f.get('osm_type')}{f.get('osm_id')}")
    return out


def applicable(rows):
    return [r for r in rows if r.get("tier") in ("A", "B") and not r["expect"].get("coordinateOption")]


def gate_passes(passed, counted):
    """D168 / AC 17, integer arithmetic: passed x 10 >= counted x 9 (with 30 counted rows, 27 must pass)."""
    return passed * 10 >= counted * 9


def keys(res):
    return [se.feature_key(f) for f in res["features"]]


def golden(args):
    doc = json.loads(Path(args.golden).read_text(encoding="utf-8"))
    refs = {k: tuple(v) for k, v in doc["refs"].items()}
    eng = se.SearchEngine(args.db)
    eng2 = se.SearchEngine(args.db2) if args.db2 else None
    online = Online(args.online) if args.online else None
    rows, times, det_diff = [], [], []
    for row in applicable(doc["rows"]):
        b = bias_point(refs[row["bias"]] if isinstance(row["bias"], str) else row["bias"])
        res = eng.search(row["q"], row["ui"], b)
        times.append(res["ms"])
        ev = evaluate(row, res, refs)
        rec = {"id": row["id"], "q": row["q"], "ui": row["ui"], "pass": ev["pass"], "rank": ev["rank"],
               "rule": res["plan"].get("rule"), "stages": res["stages"], "ms": res["ms"],
               "offline_top5": listing(res, row, refs)}
        if eng2:
            if keys(eng2.search(row["q"], row["ui"], b)) != keys(res):
                det_diff.append(row["id"])
        if online:
            ores = online.search(row["q"], row["ui"], b)
            oev = evaluate(row, ores, refs)
            rec.update(online_pass=oev["pass"], online_rank=oev["rank"], online_top5=listing(ores, row, refs))
        rec["counted"] = rec["pass"] or rec.get("online_pass", True)
        rows.append(rec)
    # A15 (coordinates, AC 8): never reaches the file
    a15 = next((r for r in doc["rows"] if r["expect"].get("coordinateOption")), None)
    coord = None
    if a15:
        c = eng.search(a15["q"], a15["ui"], None)
        coord = {"id": a15["id"], "kind": c["kind"], "file_queries": c["file_queries"],
                 "pass": c["kind"] == "coordinate" and c["file_queries"] == 0}
    counted = [r for r in rows if r["counted"]]
    passed = [r for r in counted if r["pass"]]
    gate = gate_passes(len(passed), len(counted))
    all_passed = [r for r in rows if r["pass"]]
    report = {
        "job": "search-eval-golden", "db_meta": {k: eng.meta.get(k) for k in
                                                ("search_schema", "builder_version", "data_timestamp", "source_sha256")},
        "ranking_version": se.RANKING_VERSION, "online": bool(online),
        "applicable": len(rows), "counted": len(counted), "passed": len(passed),
        "gate": "pass" if gate else "fail",
        "gate_rule": f"{len(passed)} x 10 {'>=' if gate else '<'} {len(counted)} x 9 (D168, AC 17)",
        "rate_counted": round(100 * len(passed) / len(counted), 1) if counted else None,
        "all_rows": f"{len(all_passed)}/{len(rows)} = {round(100 * len(all_passed) / len(rows), 1)} % "
                    "(Open question 2 option (b): every applicable row counted)",
        "data_gaps": [r["id"] for r in rows if not r["counted"]],
        "failing": [r for r in counted if not r["pass"]],
        "a15_coordinate": coord,
        "latency_ms_reference_python": {"p50": round(statistics.median(times), 2),
                                        "p95": round(sorted(times)[max(0, int(0.95 * len(times)) - 1)], 2),
                                        "max": max(times)},
        "rows": rows,
    }
    if eng2:
        report["determinism"] = {"db2": True, "table_rows_equal": table_counts(args.db) == table_counts(args.db2),
                                 "rows_with_different_results": det_diff}
    if online:
        report["online_requests"] = online.requests
    return report, (0 if gate else 1)


def table_counts(path):
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        return {t: db.execute(f"SELECT count(*) FROM {t}").fetchone()[0]
                for t in ("place", "vocab", "place_geo", "meta")} | {
            "place_fts_docs": db.execute("SELECT count(*) FROM place_fts_docsize").fetchone()[0]
            if db.execute("SELECT 1 FROM sqlite_master WHERE name='place_fts_docsize'").fetchone() else None}
    finally:
        db.close()


# ------------------------------------------------------------------ held-out (D198, report only)
def heldout(args):
    doc = json.loads(Path(args.set).read_text(encoding="utf-8"))
    eng = se.SearchEngine(args.db)
    online = Online(args.online)
    rows, excluded = [], []
    for row in doc["rows"]:
        b = bias_point(row.get("bias"))
        ores = online.search(row["q"], row.get("ui", "mn"), b)
        res = eng.search(row["q"], row.get("ui", "mn"), b)
        off5 = [se.feature_key(f) for f in res["features"][:5]]
        rec = {"id": row["id"], "q": row["q"], "group": row["group"], "script": row["script"],
               "offline_top5": [f"{se.display_name(f)}[{label(f, 'mn')}] {se.feature_key(f)}"
                                for f in res["features"][:5]],
               "stages": res["stages"], "ms": res["ms"]}
        if not ores["features"]:
            rec["online_top1"] = None
            excluded.append(rec)
            continue
        top1 = ores["features"][0]
        rec["online_top1"] = f"{se.display_name(top1)}[{label(top1, 'mn')}] {se.feature_key(top1)}"
        rec["pass"] = se.feature_key(top1) in off5
        rec["offline_rank_of_online_top1"] = off5.index(se.feature_key(top1)) + 1 if rec["pass"] else None
        rows.append(rec)
    passed = [r for r in rows if r["pass"]]
    by = {}
    for r in rows:
        for k in (f"group:{r['group']}", f"script:{r['script']}"):
            g = by.setdefault(k, [0, 0])
            g[0] += r["pass"]
            g[1] += 1
    report = {"job": "search-eval-heldout", "set": doc.get("name"), "set_version": doc.get("version"),
              "ranking_version": se.RANKING_VERSION, "gate": "none (D198: report only; the PO sets a pass mark later)",
              "queries": len(doc["rows"]), "measured": len(rows), "passed": len(passed),
              "rate": round(100 * len(passed) / len(rows), 1) if rows else None,
              "breakdown": {k: f"{v[0]}/{v[1]}" for k, v in sorted(by.items())},
              "excluded_online_zero": [{"id": r["id"], "q": r["q"], "offline_top5": r["offline_top5"]}
                                       for r in excluded],
              "failing": [r for r in rows if not r["pass"]], "rows": rows, "online_requests": online.requests}
    return report, 0


# ------------------------------------------------------------------ reverse (AC 20)
def reverse(args):
    eng = se.SearchEngine(args.db)
    online = Online(args.online)
    rows = []
    for pid, (lat, lon) in REVERSE_POINTS.items():
        off = eng.reverse(lat, lon)
        on = online.reverse(lat, lon)
        rec = {"id": pid, "offline": off and f"{se.display_name(off)} {se.feature_key(off)} {off['distance_m']}m",
               "online": on and f"{se.display_name(on)} {se.feature_key(on)}"}
        if on is None:
            rec["pass"] = None
            rec["note"] = "online returned nothing within 500 m"
        elif off is None:
            rec["pass"] = False
        else:
            d = se.haversine_m(off["lat"], off["lon"], on["lat"], on["lon"])
            rec["distance_to_online_m"] = round(d, 1)
            rec["pass"] = se.feature_key(off) == se.feature_key(on) or d <= 50
        rows.append(rec)
    measured = [r for r in rows if r["pass"] is not None]
    return {"job": "search-eval-reverse", "rule": "same OSM object as online, or a named object within 50 m of the "
            "online result (AC 20)", "passed": sum(1 for r in measured if r["pass"]), "measured": len(measured),
            "rows": rows, "online_requests": online.requests}, 0


def summary(rep):
    if rep["job"] == "search-eval-golden":
        lines = [f"golden (D168 gate, AC 17): {rep['passed']}/{rep['counted']} counted rows pass = "
                 f"{rep['rate_counted']} % -> gate {rep['gate'].upper()} ({rep['gate_rule']})",
                 f"  all applicable rows: {rep['all_rows']}; data gaps (fail online too, not counted): "
                 f"{', '.join(rep['data_gaps']) or 'none'}; online measured: {rep['online']}",
                 f"  A15 coordinate: {rep['a15_coordinate']}",
                 f"  reference-engine latency (host Python, not a phone): {rep['latency_ms_reference_python']}"]
        for r in rep["failing"]:
            lines.append(f"  FAIL {r['id']} «{r['q']}» offline: {' | '.join(r['offline_top5']) or '(none)'}")
            if "online_top5" in r:
                lines.append(f"       online: {' | '.join(r['online_top5']) or '(none)'}")
        if "determinism" in rep:
            lines.append(f"  determinism (AC 5): {rep['determinism']}")
        return "\n".join(lines)
    if rep["job"] == "search-eval-heldout":
        lines = [f"held-out (D198, report only): {rep['passed']}/{rep['measured']} = {rep['rate']} % "
                 f"({rep['queries']} queries, {len(rep['excluded_online_zero'])} excluded: 0 online results)",
                 f"  breakdown: {rep['breakdown']}"]
        for r in rep["failing"]:
            lines.append(f"  FAIL {r['id']} «{r['q']}» online#1 {r['online_top1']}; offline: "
                         f"{' | '.join(r['offline_top5']) or '(none)'}")
        for r in rep["excluded_online_zero"]:
            lines.append(f"  EXCLUDED {r['id']} «{r['q']}» (online 0); offline: {' | '.join(r['offline_top5']) or '(none)'}")
        return "\n".join(lines)
    lines = [f"reverse (AC 20): {rep['passed']}/{rep['measured']}"]
    for r in rep["rows"]:
        lines.append(f"  {r['id']}: {'ok ' if r['pass'] else 'FAIL' if r['pass'] is False else 'n/a '} "
                     f"offline {r['offline']} | online {r['online']} | {r.get('distance_to_online_m', '-')} m")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("golden")
    g.add_argument("--golden", default=str(GOLDEN))
    g.add_argument("--db2")
    h = sub.add_parser("heldout")
    h.add_argument("--set", default=str(HELDOUT))
    r = sub.add_parser("reverse")
    for p in (g, h, r):
        p.add_argument("--db", required=True)
        p.add_argument("--online", required=p is not g)
        p.add_argument("--out")
    a = ap.parse_args(argv)
    rep, rc = {"golden": golden, "heldout": heldout, "reverse": reverse}[a.cmd](a)
    if a.out:
        Path(a.out).write_text(json.dumps(rep, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(summary(rep))
    return rc


if __name__ == "__main__":
    sys.exit(main())

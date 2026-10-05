"""NAV-023 reference engine and measurement script: unit tests (host Python, fixture dump, no network, no Docker).

    make -C backend pack-test      # or: python3 -m unittest discover -s backend/pack/tests -v

- The ADR-0012 port (settle, plan, Latin -> Cyrillic, merge) is checked against the shared ADR-0006 vector file that the
  web and Android suites read (web/src/search/queryPlan.vectors.json, read only); a missing file fails.
- The AC 6 stages (match, prefix, joined words, fold, skeleton, edit distance, trigram), ranking by proximity, the
  coordinate path (0 file queries, AC 8), reverse (AC 20) and the golden evaluator / gate arithmetic (AC 17).
- The held-out set composition (AC 18 minimums).
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import search_builder as sb  # noqa: E402
import search_engine as se  # noqa: E402
import search_eval as ev  # noqa: E402

REPO = HERE.parents[2]
ADR0006_VECTORS = REPO / "web" / "src" / "search" / "queryPlan.vectors.json"
P1 = (47.9189, 106.9176)


def place(pid, otype, oid, names, lon, lat, key="amenity", value="cafe", atype="house", addr=None, hn=None, imp=0.1):
    e = {"place_id": str(pid), "object_type": otype, "object_id": oid, "osm_key": key, "osm_value": value,
         "categories": [], "address_type": atype, "importance": imp, "country_code": "mn", "centroid": [lon, lat],
         "bbox": [lon, lat, lon, lat], "address": addr or {}}
    if names:
        e["name"] = names
    if hn:
        e["housenumber"] = hn
    return {"type": "Place", "content": [e]}


FIXTURE = [
    {"type": "NominatimDumpFile", "content": {"version": "0.1.0", "data_timestamp": "2026-09-26T22:59:05Z"}},
    place(1, "N", 1, {"name:mn": "Сүхбаатарын талбай", "name": "Сүхбаатарын талбай", "name:en": "Sukhbaatar Square"},
          106.9176, 47.9189, key="place", value="square", atype="locality", imp=0.3),
    place(2, "R", 2, {"name": "Сүхбаатар", "name:en": "Sükhbaatar"}, 113.2846, 46.6808, key="place", value="state",
          atype="state", imp=0.55),
    place(3, "N", 3, {"name": "Гандантэгчэнлин хийд", "alt_name": "Гандан хийд", "name:en": "Gandan Monastery"},
          106.8950, 47.9215, key="amenity", value="place_of_worship", imp=0.2),
    place(4, "W", 4, {"name": "gandan"}, 105.9598, 49.4704, key="landuse", value="religious", atype="other", imp=0.1),
    place(5, "W", 5, {"name": "Энх тайваны гүүр"}, 106.9137, 47.9096, key="highway", value="primary", atype="street"),
    place(6, "R", 6, {"name": "Баянзүрх дүүрэг", "name:en": "Bayanzürkh"}, 107.15, 47.96, key="boundary",
          value="administrative", atype="district", imp=0.33),
    place(7, "W", 7, None, 106.9045, 47.9141, key="building", value="yes", addr={"street": "Сөүлийн гудамж"}, hn="77"),
    place(8, "N", 8, {"name": "Хөвсгөл нуур"}, 100.4, 51.0, key="natural", value="water", atype="other", imp=0.3),
    place(9, "N", 9, {"name": "Кофе Шоп"}, 106.9180, 47.9190),
    place(10, "N", 10, {"name": "Кофе Шоп"}, 106.9900, 47.9500),
    place(11, "N", 11, {"name": "Гандан"}, 106.8960, 47.9205, key="place", value="suburb", atype="district", imp=0.15),
]


class PortVsSharedAdr0006Vectors(unittest.TestCase):
    """The Python port must match the vectors the web and Android suites use (NAV-011 AC 5): 100 % of the rows."""

    @classmethod
    def setUpClass(cls):
        cls.doc = json.loads(ADR0006_VECTORS.read_text(encoding="utf-8"))

    def test_settle(self):
        bad = [r for r in self.doc["settle"] if se.settle(r["raw"]) != r["settled"]]
        self.assertEqual(bad, [])

    def test_plan(self):
        bad = []
        for r in self.doc["plan"]:
            p = se.plan(r["input"])
            if r["kind"] == "skip":
                ok = p["kind"] == "skip"
            elif r["kind"] == "coordinate":
                ok = p["kind"] == "coordinate" and p["point"] == (r["lat"], r["lon"])
            else:
                ok = (p["kind"] == "text" and p["primary"] == r.get("primary") and p["secondary"] == r.get("secondary")
                      and p["mode"] == r.get("mode"))
            if not ok:
                bad.append((r, p))
        self.assertTrue(self.doc["plan"])
        self.assertEqual(bad, [])

    def test_latin_to_cyrillic(self):
        bad = [(r, se.latin_to_cyrillic(r["latin"])) for r in self.doc["latinToCyrillic"]
               if se.latin_to_cyrillic(r["latin"]) != r["cyrillic"]]
        self.assertEqual(bad, [])

    def test_merge(self):
        bad = []
        for r in self.doc["merge"]:
            prim, sec = r["primary"], r.get("secondary") or []
            if r["mode"] == "parallel":
                merged = se.merge(prim, sec)
            elif r["mode"] == "ifEmpty":                       # the response the plan ends with, merged on its own
                merged = se.merge(prim if prim else sec)
            else:
                merged = se.merge(prim)
            got = [se.feature_key(f) for f in merged]
            if got != r["expected"]:
                bad.append((r["name"], got))
        self.assertEqual(bad, [])


class DisplayRules(unittest.TestCase):
    def test_type_rows(self):
        self.assertEqual(se.type_row({"name": "Баянзүрх дүүрэг", "osm_key": "boundary"}), 1)
        self.assertEqual(se.type_row({"name": "БЗД-ийн 4-р хороо", "osm_key": "office", "osm_value": "government"}), 2)
        self.assertEqual(se.type_row({"name": "Хэрлэн", "osm_key": "boundary", "osm_value": "administrative",
                                      "type": "county"}), 4)
        self.assertEqual(se.type_row({"name": "Энх тайвны өргөн чөлөө", "osm_key": "highway", "osm_value": "trunk"}), 30)
        self.assertEqual(se.type_row({"street": "Сөүлийн гудамж", "housenumber": "77", "osm_key": "building"}), 31)
        self.assertEqual(se.type_row({"name": "x", "osm_key": "landuse"}), 32)
        self.assertEqual(ev.label({"name": "Чингэлтэй", "osm_key": "boundary", "osm_value": "administrative",
                                   "type": "district"}, "en"), "District")

    def test_name_and_context(self):
        f = {"street": "Сөүлийн гудамж", "housenumber": "77", "district": "Бага Тойрог", "city": "Улаанбаатар"}
        self.assertEqual(se.display_name(f), "Сөүлийн гудамж 77")
        self.assertEqual(se.context_line(f, "Сөүлийн гудамж 77"), "Бага Тойрог, Улаанбаатар")


class EngineTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="nav023-se-")
        d = Path(cls.tmp.name)
        dump = d / "dump.jsonl"
        dump.write_text("".join(json.dumps(x, ensure_ascii=False) + "\n" for x in FIXTURE), encoding="utf-8")
        cls.db = d / "search.sqlite"
        sb.build(str(dump), str(cls.db))
        cls.eng = se.SearchEngine(str(cls.db))

    @classmethod
    def tearDownClass(cls):
        cls.eng.close()
        cls.tmp.cleanup()

    def names(self, q, lang="mn", bias=P1):
        return [se.display_name(f) for f in self.eng.search(q, lang, bias)["features"]]

    def test_exact_prefix_fold_and_latin(self):
        self.assertEqual(self.names("Сүхбаатарын талбай")[0], "Сүхбаатарын талбай")
        self.assertIn("Сүхбаатарын талбай", self.names("Сүхб"))                     # prefix on the last token
        self.assertIn("Сүхбаатарын талбай", self.names("Сухбаатарын"))              # Russian layout (fold)
        self.assertIn("Sukhbaatar Square", self.names("sukhbaatar square", "en"))    # skeleton, name:en in the en UI
        self.assertIn("Сүхбаатарын талбай", self.names("Suhbaatar"))                 # skeleton suhbatar

    def test_abbreviation_and_joined_words(self):
        r = self.eng.search("БЗД", "mn", P1)
        self.assertEqual(r["plan"]["rule"], "A")
        self.assertEqual(se.display_name(r["features"][0]), "Баянзүрх дүүрэг")
        self.assertIn("Энх тайваны гүүр", self.names("Энхтайваны"))                 # joined-word key

    def test_edit_distance_then_trigram(self):
        r = self.eng.search("Энхтайвны гүүр", "mn", P1)                              # enhtaivni: 1 edit from the key
        self.assertEqual(r["stages"], ["fuzzy"])
        self.assertEqual(se.display_name(r["features"][0]), "Энх тайваны гүүр")
        r = self.eng.search("андантэгч", "mn", P1)                                   # infix: only trigrams find it
        self.assertEqual(r["stages"], ["trigram"])
        self.assertEqual(se.display_name(r["features"][0]), "Гандантэгчэнлин хийд")
        r = self.eng.search("xqzjwvk", "mn", P1)
        self.assertEqual(r["features"], [])

    def test_proximity_ranking(self):
        r = self.eng.search("Кофе Шоп", "mn", P1)
        self.assertEqual(r["features"][0]["osm_id"], 9)                             # the near one first
        r = self.eng.search("Гандан", "mn", P1)
        top = [f["osm_id"] for f in r["features"][:3]]
        self.assertEqual(top[0], 11)                                                # near exact beats far exact
        self.assertIn(3, top)                                                       # near prefix match in the top 3

    def test_vowel_gap_is_measured_not_hidden(self):
        # NAV-020 task §3.6: Latin u for ө is not joined by the skeleton; it stays a known gap (vectors D01-D03)
        self.assertNotIn("Хөвсгөл нуур", self.names("Khuvsgul", "en"))
        self.assertIn("Хөвсгөл нуур", self.names("Khovsgol", "en"))

    def test_coordinates_never_query_the_file(self):
        r = self.eng.search("47.9189, 106.9176", "mn", P1)
        self.assertEqual((r["kind"], r["file_queries"], r["features"]), ("coordinate", 0, []))
        self.assertEqual(self.eng.search("x", "mn", P1)["file_queries"], 0)

    def test_limits(self):
        self.assertLessEqual(len(self.eng.search("а", "mn", P1)["features"]), se.MAX_OPTIONS)
        feats, _ = self.eng.query("Кофе", P1)
        self.assertLessEqual(len(feats), se.PER_QUERY_LIMIT)

    def test_feature_shape(self):
        f = self.eng.search("Сөүлийн гудамж 77", "mn", P1)["features"][0]
        for k in ("osm_type", "osm_id", "osm_key", "osm_value", "type", "name", "housenumber", "street", "district",
                  "city", "county", "state", "countrycode", "lat", "lon"):
            self.assertIn(k, f)
        self.assertEqual((f["osm_type"], f["osm_id"], f["countrycode"]), ("W", 7, "MN"))

    def test_reverse(self):
        f = self.eng.reverse(*P1)
        self.assertEqual(f["name"], "Сүхбаатарын талбай")
        f = self.eng.reverse(47.9139, 106.9044)                                     # P2: the address-only building
        self.assertEqual((f["osm_id"], se.display_name(f)), (7, "Сөүлийн гудамж 77"))
        self.assertIsNone(self.eng.reverse(45.0, 100.0))                             # nothing within 500 m

    def test_unknown_schema_refused(self):
        import shutil
        import sqlite3
        db = Path(self.tmp.name) / "s2.sqlite"
        shutil.copy(self.db, db)
        c = sqlite3.connect(db)
        c.execute("UPDATE meta SET value = '99' WHERE key = 'search_schema'")
        c.commit()
        c.close()
        with self.assertRaises(ValueError):
            se.SearchEngine(str(db))


class EvalTests(unittest.TestCase):
    REFS = {"P1": P1}

    def res(self, *feats):
        return {"kind": "text", "features": list(feats)}

    def test_evaluate_conditions(self):
        near = {"name": "Сүхбаатарын талбай", "osm_key": "place", "osm_value": "square", "lat": 47.9190, "lon": 106.9177}
        far = {"name": "Сүхбаатар", "osm_key": "place", "osm_value": "state", "lat": 46.68, "lon": 113.28}
        row = {"ui": "mn", "expect": {"near": "P1", "m": 500, "top": 1}}
        self.assertTrue(ev.evaluate(row, self.res(near, far), self.REFS)["pass"])
        self.assertFalse(ev.evaluate(row, self.res(far, near), self.REFS)["pass"])
        row = {"ui": "mn", "expect": {"type": "Талбай", "nameContains": "талбай", "top": 3}}
        self.assertEqual(ev.evaluate(row, self.res(far, near), self.REFS)["rank"], 2)
        row = {"ui": "en", "expect": {"nameContains": "chingeltei|Чингэлтэй", "flags": "i", "type": "District", "top": 5}}
        f = {"name": "Chingeltei", "osm_key": "boundary", "osm_value": "administrative", "type": "district"}
        self.assertTrue(ev.evaluate(row, self.res(f), self.REFS)["pass"])
        row = {"ui": "mn", "expect": {"noResults": True}}
        self.assertTrue(ev.evaluate(row, self.res(), self.REFS)["pass"])
        self.assertFalse(ev.evaluate(row, self.res(near), self.REFS)["pass"])

    def test_gate_arithmetic(self):
        self.assertTrue(ev.gate_passes(27, 30))
        self.assertFalse(ev.gate_passes(26, 30))
        self.assertTrue(ev.gate_passes(29, 29))

    def test_applicable_rows_of_the_golden_set(self):
        doc = json.loads(ev.GOLDEN.read_text(encoding="utf-8"))
        ids = [r["id"] for r in ev.applicable(doc["rows"])]
        want = [f"A{i}" for i in range(1, 17) if i != 15] + [f"B{i}" for i in range(1, 16)]
        self.assertEqual(ids, want)                                                 # AC 17: 30 rows, A15 excluded

    def test_bias_rounding(self):
        self.assertEqual(ev.bias_point((47.91894, 106.91765)), (47.919, 106.918))
        self.assertIsNone(ev.bias_point(None))


class HeldOutSetTests(unittest.TestCase):
    def test_composition_meets_ac18_minimums(self):
        doc = json.loads(ev.HELDOUT.read_text(encoding="utf-8"))
        rows = doc["rows"]
        self.assertIn("REPORT ONLY", doc["status"])
        count = lambda k, v: sum(1 for r in rows if r[k] == v)  # noqa: E731
        self.assertGreaterEqual(len(rows), 30)
        self.assertGreaterEqual(count("group", "soum_centre"), 10)
        self.assertGreaterEqual(count("group", "bag"), 5)
        self.assertGreaterEqual(count("group", "aimag_centre"), 5)
        self.assertGreaterEqual(count("script", "latin"), 8)
        self.assertGreaterEqual(count("script", "ru_layout"), 4)
        self.assertEqual(len({r["id"] for r in rows}), len(rows))
        for r in rows:
            self.assertTrue(r["bias"] is None or (len(r["bias"]) == 2 and 41 < r["bias"][0] < 53), r["id"])
            if r["script"] == "ru_layout":
                self.assertFalse(set("үөҮӨ") & set(r["q"]), r["id"])


if __name__ == "__main__":
    unittest.main()

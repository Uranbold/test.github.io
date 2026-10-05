"""NAV-020 B3 / NAV-023 AC 1-5: search DB builder v1 unit tests (host Python, plain JSONL fixture, no Docker).

    make -C backend pack-test      # or: python3 -m unittest discover -s backend/pack/tests -v

The skeleton vectors are the NAV-023 AC 3 groups from the story text. When QA's shared vector file exists
(NAV_SEARCH_VECTORS=<path>, or tests/offline/search-skeleton-vectors.json), its groups are checked too.
"""
import hashlib
import json
import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import search_builder as sb  # noqa: E402

REPO = HERE.parents[2]
AC3_GROUPS = [
    (["Сүхбаатар", "Сухбаатар", "Sukhbaatar", "Suhbaatar", "Sükhbaatar", "SUKHBAATAR"], "suhbatar"),
    (["Зайсан", "Zaisan", "Zaysan"], "zaisan"),
    (["Баянзүрх", "Bayanzurkh", "Bayanzurh"], "baianzurh"),
    (["Чингэлтэй", "Chingeltei"], "chingeltei"),
    (["Их дэлгүүр", "Ikh delguur"], "ih delgur"),
    (["Эрдэнэт", "Erdenet"], "erdenet"),
    (["Гандан", "Gandan"], "gandan"),
]
TRAD = "ᠬᠠᠯᠬ᠎ᠠ"


def place(pid, otype, oid, name=None, centroid=(106.9176, 47.9189), bbox=None, addr=None, hn=None, key="amenity",
          value="place_of_worship", atype="other"):
    e = {"place_id": str(pid), "osm_key": key, "osm_value": value, "categories": [], "address_type": atype,
         "importance": 0.1 + pid / 1000, "country_code": "mn", "centroid": list(centroid),
         "bbox": list(bbox or (centroid[0], centroid[1], centroid[0], centroid[1])), "address": addr or {}}
    if otype:
        e["object_type"], e["object_id"] = otype, oid
    if name:
        e["name"] = name
    if hn:
        e["housenumber"] = hn
    return e


FIXTURE = [
    {"type": "NominatimDumpFile", "content": {"version": "0.1.0", "data_timestamp": "2026-09-26T22:59:05.000+00:00",
                                              "database_version": "1.0.0-4"}},
    {"type": "CountryInfo", "content": [{"country_code": "mn", "name": {"name": "Монгол"}}]},
    {"type": "Place", "content": [place(3, "N", 30, {"name:mn": "Сүхбаатарын талбай", "name": "Сүхбаатарын талбай",
                                                     "name:en": "Sukhbaatar Square", "alt_name": "Төв талбай;Чингисийн талбай"},
                                        bbox=(106.916, 47.920, 106.919, 47.918),
                                        addr={"city": "Улаанбаатар " + TRAD, "state": "Улаанбаатар", "city:en": "Ulaanbaatar"})]},
    {"type": "Place", "content": [place(1, "W", 10, {"name": "Халхгол " + TRAD}, addr={"county": "Халх гол " + TRAD})]},
    {"type": "Place", "content": [place(2, None, None, None, addr={"street": "Энхтайваны өргөн чөлөө"}, hn="12")]},
    {"type": "Place", "content": [place(4, "N", 40, None)]},                                   # no name, no address: dropped
    {"type": "Place", "content": [place(5, "R", 50, {"name": "Энх тайваны гүүр"}),            # two entries, one place
                                  place(6, "R", 50, {"name:en": "Peace Bridge"})]},
    {"type": "Place", "content": [place(7, "N", 70, {"name": "Гандан хийд", "name:ru": "Гандан"}, centroid=(106.894, 47.921))]},
]


class SkeletonTests(unittest.TestCase):
    def test_ac3_groups(self):
        for forms, want in AC3_GROUPS:
            for f in forms:
                with self.subTest(form=f):
                    self.assertEqual(sb.skeleton(f), want)

    def test_shared_vector_file_if_present(self):
        path = Path(os.environ.get("NAV_SEARCH_VECTORS", REPO / "tests" / "offline" / "search-skeleton-vectors.json"))
        if not path.is_file():
            self.skipTest(f"QA vector file not present yet ({path})")
        doc = json.loads(path.read_text(encoding="utf-8"))
        for g in doc.get("groups", doc if isinstance(doc, list) else []):
            for f in g["forms"]:
                self.assertEqual(sb.skeleton(f), g["skeleton"], f)

    def test_fold_and_clean(self):
        self.assertEqual(sb.fold("ҮӨЁ"), "уое")
        self.assertEqual(sb.clean("Халхгол " + TRAD), "Халхгол")
        self.assertEqual(sb.clean("a b"), "a b")
        self.assertEqual(sb.skeleton("Баян-Өлгий"), "baianolgi")
        self.assertEqual(sb.skeleton("Ölgii"), "olgi")

    def test_self_test_match_expression(self):
        self.assertEqual(sb.self_test_match("Сүхбаатар"), 'skel : ("suhbatar"*)')
        self.assertEqual(sb.self_test_match("Их дэлгүүр"), 'skel : ("ih" "delgur"*)')
        self.assertIsNone(sb.self_test_match("!!!"))


class BuildTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="nav020-sb-")
        d = Path(cls.tmp.name)
        cls.dump = d / "dump.jsonl"
        cls.dump.write_text("".join(json.dumps(x, ensure_ascii=False) + "\n" for x in FIXTURE), encoding="utf-8")
        cls.db1, cls.db2 = d / "a.sqlite", d / "b.sqlite"
        cls.facts = sb.build(str(cls.dump), str(cls.db1))
        sb.build(str(cls.dump), str(cls.db2))

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def q(self, sql, *args):
        db = sqlite3.connect(self.db1)
        try:
            return db.execute(sql, args).fetchall()
        finally:
            db.close()

    def test_facts_and_meta(self):
        self.assertEqual(self.facts["rows"], 6)
        self.assertEqual(self.facts["data_timestamp"], "2026-09-26T22:59:05Z")
        meta = dict(self.q("SELECT key, value FROM meta"))
        self.assertEqual(meta["search_schema"], "1")
        self.assertEqual(meta["builder_version"], "1")
        self.assertEqual(meta["place_rows"], "6")
        self.assertEqual(meta["source_sha256"], hashlib.sha256(self.dump.read_bytes()).hexdigest())
        self.assertNotIn("built_at", meta)
        # ODbL notice in the derivative database (NAV-020 review minor), same values as the manifest
        self.assertEqual(meta["attribution"], "© OpenStreetMap contributors")
        self.assertEqual(meta["licence"], "ODbL-1.0")
        self.assertEqual(meta["licence_url"], "https://opendatacommons.org/licenses/odbl/1-0/")
        self.assertEqual(self.q("PRAGMA user_version")[0][0], 1)
        self.assertEqual(self.q("PRAGMA application_id")[0][0], 0x4E41564D)

    def test_schema_objects(self):
        names = {r[0] for r in self.q("SELECT name FROM sqlite_master WHERE type IN ('table')")}
        for t in ("meta", "place", "place_fts", "place_tri", "vocab", "place_geo"):
            self.assertIn(t, names)
        cols = [r[1] for r in self.q("PRAGMA table_info(place)")]
        self.assertEqual(cols[:6], ["id", "osm_type", "osm_id", "osm_key", "osm_value", "type"])
        for c in ("name", "name_en", "housenumber", "street", "city", "county", "state", "lat", "lon", "ext_w", "importance"):
            self.assertIn(c, cols)

    def test_deterministic(self):
        self.assertEqual(hashlib.sha256(self.db1.read_bytes()).hexdigest(), hashlib.sha256(self.db2.read_bytes()).hexdigest())

    def test_rows_order_and_label_rule(self):
        rows = self.q("SELECT id, osm_type, osm_id, name, name_en, street, housenumber, ext_w FROM place ORDER BY id")
        self.assertEqual([r[1] for r in rows], ["N", "N", "R", "R", "W", None])      # osm_type, NULLS LAST
        sq = rows[0]
        self.assertEqual((sq[3], sq[4]), ("Сүхбаатарын талбай", "Sukhbaatar Square"))
        self.assertIsNotNone(sq[7])                  # extent kept for a real bbox
        self.assertIsNone(rows[1][7])                # point bbox -> NULL extent
        self.assertEqual(rows[5][5:7], ("Энхтайваны өргөн чөлөө", "12"))
        self.assertEqual(rows[3][4], "Peace Bridge")

    def test_no_traditional_mongolian(self):
        for col in ("name", "name_en", "street", "suburb", "district", "city", "county", "state"):
            for (v,) in self.q(f"SELECT {col} FROM place WHERE {col} IS NOT NULL"):
                self.assertFalse(any("᠀" <= ch <= "᢯" for ch in v), (col, v))
        r = sb.selftest(str(self.db1), "Сүхбаатар")
        self.assertEqual(r["trad_rows"], 0)

    def test_fts_skeleton_prefix_joined_and_alt_names(self):
        def hits(expr):
            return [r[0] for r in self.q("SELECT rowid FROM place_fts WHERE place_fts MATCH ? ORDER BY rowid", expr)]
        self.assertEqual(hits(sb.self_test_match("Sukhbaatar")), [1])
        self.assertEqual(hits(sb.self_test_match("Сухбаа")), [1])               # prefix on the last token
        self.assertEqual(hits('skel : "enhtaivani"'), [3, 6])   # joined-word key «Энх тайваны» = the street «Энхтайваны»
        self.assertEqual(hits('names : "чингисийн"'), [1])                      # alt_name split on ';'
        self.assertEqual(hits('skel : "gandan"'), [2])
        self.assertTrue(self.q("SELECT n FROM vocab WHERE term = 'suhbatarin'"))
        self.assertEqual(self.q("SELECT count(*) FROM place_geo WHERE min_lon <= 106.92 AND max_lon >= 106.91 AND "
                                "min_lat <= 47.92 AND max_lat >= 47.91")[0][0], 5)
        self.assertEqual(self.q("SELECT rowid FROM place_tri WHERE place_tri MATCH 'андан'"), [(2,)])

    def test_selftest(self):
        r = sb.selftest(str(self.db1), "Сүхбаатар")
        self.assertEqual(r["result"], "ok", r)
        self.assertGreaterEqual(r["hits"], 1)
        bad = sb.selftest(str(self.db1), "Сүхбаатар", schema=2)
        self.assertEqual(bad["result"], "failed")
        none = sb.selftest(str(self.db1), "Хөвсгөл")
        self.assertIn("self-test query returned 0 rows", none["problems"])

    def test_selftest_requires_licence_meta(self):
        import shutil
        db = Path(self.tmp.name) / "nolicence.sqlite"
        shutil.copy(self.db1, db)
        c = sqlite3.connect(db)
        c.execute("DELETE FROM meta WHERE key = 'licence_url'")
        c.execute("UPDATE meta SET value = 'someone' WHERE key = 'attribution'")
        c.commit()
        c.close()
        r = sb.selftest(str(db), "Сүхбаатар")
        self.assertEqual(r["result"], "failed")
        self.assertTrue(any(x.startswith("meta.licence_url=") for x in r["problems"]), r["problems"])
        self.assertTrue(any(x.startswith("meta.attribution=") for x in r["problems"]), r["problems"])

    def test_licence_constants_match_the_pack_step(self):
        sys.path.insert(0, str(HERE.parents[1] / "pipeline"))
        import nav_pack
        self.assertEqual(sb.LICENCE_URL, nav_pack.ODBL_URL)
        self.assertEqual((sb.ATTRIBUTION, sb.LICENCE), (nav_pack.DEFAULT_ATTRIBUTION, nav_pack.ODBL_NAME))

    def test_cli_refuses_other_versions(self):
        rc = sb.main(["build", "--dump", str(self.dump), "--out", str(Path(self.tmp.name) / "c.sqlite"), "--schema", "2"])
        self.assertEqual(rc, 3)


if __name__ == "__main__":
    unittest.main()

"""NAV-020 pack step unit tests (stdlib unittest, no Docker, a few seconds).

    make -C backend pack-test        # or: python3 -m unittest discover -s backend/pipeline/tests -p 'test_nav_pack*.py' -v

Covers: configuration guards (ages below 1 day, evidence mode and test faults only outside navmn, upstream image refused
as the Gate 2 engine, digest-pinned builder image), every selection branch (no manifest; weekly due and tiles kept;
both due; tiles due but weekly not -> not due; FORCE; TILES; write-once directory; weekday; tiles newer than the active
slot), deterministic gzip and checksums from disk, the PMTiles header self-test, the Gate 2 comparator (rounding,
manoeuvres, geometry, alternates, wrapper error envelope vs OSRM code), the app pin check (AC 13), manifest rules
(AC 7), and the whole step with Docker parts faked: first publication, per-file versions, retention over 4 publications
(AC 16), Gate 2 difference and self-test failure leave the manifest unchanged and alert once (AC 11, 26), low disk
(AC 18), interrupt cleanup and reconcile (AC 15), rollback republish (AC 28), the NAV-006 hook never changing the run
result (AC 1, A1 item 12), the lock (AC 4) and status timing (AC 24).
"""
import fcntl
import gzip
import hashlib
import inspect
import io
import json
import os
import signal
import sqlite3
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
PIPELINE = HERE.parent / "nav_pipeline.py"
sys.path.insert(0, str(HERE.parent))
import nav_pipeline as nav  # noqa: E402
import nav_pack  # noqa: E402

UTC = nav.dt.timezone.utc
# SHA-256 of nav_pack.gzip_file(1024 zero bytes, level 6) with the host's zlib (B4 fixed vector).
FIXED_GZIP_SHA256 = "8564620f1ca97e2d67cda7086a6271585fd2eef5a44e617537e7c36ee7da3ead"   # zlib 1.3


def slot_id(days_ago=0.0, seconds=0):
    t = nav.utc_now() - nav.dt.timedelta(days=days_ago) + nav.dt.timedelta(seconds=seconds)
    return t.strftime("%Y%m%dT%H%M%SZ")


def pmtiles_bytes(minz=0, maxz=14, bounds=(81.9256698, 39.018867, 120.2727588, 53.0383124), extra=8192, seed=b"t"):
    h = bytearray(127)
    h[:7] = b"PMTiles"
    h[7] = 3
    h[100], h[101] = minz, maxz
    struct.pack_into("<iiii", h, 102, *(int(round(v * 1e7)) for v in bounds))
    return bytes(h) + hashlib.sha256(seed).digest() * (extra // 32)


OK_ROUTE = {"code": "Ok", "routes": [{"distance": 4271.63, "duration": 280.045, "geometry": "_p~iF~ps|U_ulLnnqC_mqNvxq`@",
                                      "legs": [{"steps": [{"name": "Энхтайваны өргөн чөлөө",
                                                           "maneuver": {"type": "depart", "modifier": None}},
                                                          {"name": "", "maneuver": {"type": "arrive"}}]}]}],
            "waypoints": []}


class Env:
    """A temporary NAV_DATA_ROOT with an env file, a lock and an alert log."""

    def __init__(self, extra=None):
        self.tmp = tempfile.TemporaryDirectory(prefix="nav020-unit-")
        self.dir = Path(self.tmp.name)
        self.root = self.dir / "data"
        self.alerts = self.dir / "alerts.log"
        golden = self.dir / "golden.json"
        golden.write_text(json.dumps({"requests": [
            {"id": "p1-p3-car", "body": {"locations": [{"lat": 47.9189, "lon": 106.9176}, {"lat": 47.8858, "lon": 106.9173}],
                                         "costing": "auto", "format": "osrm"}},
            {"id": "ub-beijing", "body": {"locations": [{"lat": 47.9189, "lon": 106.9176}, {"lat": 39.9042, "lon": 116.4074}],
                                          "costing": "auto", "format": "osrm"}}],
            "expect": {"ub-beijing": "NoSegment"}}))
        vals = {"NAV_COMPOSE_PROJECT": "navmn-unittest", "NAV_DATA_ROOT": str(self.root),
                "NAV_LOCK_FILE": str(self.dir / "lock"), "GATEWAY_PORT": "1", "NAV_VERIFY_PORT": "2",
                "REBUILD_ALLOW_TEST_FAULTS": "1",
                "REBUILD_ALERT_CMD": f"'echo \"$NAV_RUN_RESULT|$NAV_RUN_STEP|$NAV_RUN_EXIT_CODE\" >> {self.alerts}'",
                "PACK_ENABLED": "1", "PACK_WEEKLY_MIN_AGE_DAYS": "0", "PACK_TILES_MIN_AGE_DAYS": "28",
                "PACK_MIN_FREE_GB": "0", "PACK_GATE2_MODE": "evidence", "PACK_GATE2_GOLDEN_SET": str(golden),
                "PACK_METHOD_URL": "https://pipeline.navmn.test/osm-navigation"}
        vals.update(extra or {})
        self.env_file = self.dir / "test.env"
        self.env_file.write_text("".join(f"{k}={v}\n" for k, v in vals.items()))

    def config(self, **env):
        old = {k: os.environ.get(k) for k in env}
        os.environ.update({k: str(v) for k, v in env.items()})
        try:
            cfg = nav.Config(self.env_file).load()
            cfg.values.update({k: str(v) for k, v in env.items()})    # PackConfig reads them later
            return cfg
        finally:
            for k, v in old.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v

    def pipeline(self, **env):
        p = nav.Pipeline(self.config(**env), "pack-unittest")
        p.begin()
        return p

    def add_slot(self, sid, tiles_seed=b"t", tar_seed=b"r", activate=True):
        s = self.root / "slots" / sid
        for d in ("tiles", "valhalla", "sources"):
            (s / d).mkdir(parents=True, exist_ok=True)
        (s / "tiles" / "basemap.pmtiles").write_bytes(pmtiles_bytes(seed=tiles_seed))
        (s / "valhalla" / "valhalla_tiles.tar").write_bytes(hashlib.sha256(tar_seed).digest() * 2048)
        (s / "sources" / "photon-dump").write_bytes(b"dump")
        (s / "build-info.json").write_text(json.dumps({
            "osm": {"http_last_modified": "Tue, 29 Sep 2026 08:02:53 GMT"},
            "photon_dump": {"data_timestamp": "2026-09-26T22:59:05.000+00:00"}, "versions": {"valhalla": "3.9.0"}}))
        (s / ".slot-complete").write_text("{}\n")
        if activate:
            (self.root / "pointer" / "public").mkdir(parents=True, exist_ok=True)
            nav.write_pointer(self.root / "pointer" / "public" / "active.json", sid, "blue")
            st = nav.load_state(nav.Layout(self.root))
            st["active"] = sid
            nav.save_state(nav.Layout(self.root), st)
        return s

    def alert_lines(self):
        return self.alerts.read_text().splitlines() if self.alerts.exists() else []

    def close(self):
        self.tmp.cleanup()


class FakeStep(nav_pack.PackStep):
    """The pack step with its Docker parts replaced (search builder, search self-test, Gate 2 engine) and the
    reference server answered in-process. Everything else is the real code."""
    engine_route = OK_ROUTE
    server_route = OK_ROUTE
    beijing_engine = {"code": 171, "message": "No suitable edges near location"}
    beijing_server = {"code": "NoSegment", "message": "One of the supplied input coordinates could not snap"}

    def kill_containers(self):
        return 0

    def search_build(self, sdir):
        out = self.work / "src" / "search.sqlite"
        with self.step("search_build") as s:
            db = sqlite3.connect(out)
            db.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execute("INSERT INTO meta VALUES ('search_schema', '1')")
            db.commit()
            db.close()
            self.facts["search"] = {"data_timestamp": "2026-09-26T22:59:05Z"}
            s["builder_version"] = 1
        return out

    def search_selftest(self, db):
        return 0, {"result": "ok", "hits": 3}, ""

    def run_engine(self, image, requests):
        self.engine_config()
        out = {}
        for r in requests:
            body = self.beijing_engine if r["id"] == "ub-beijing" else self.engine_route
            out[r["id"]] = {"id": r["id"], "response": json.dumps(body)}
        return out

    def reference(self, requests):
        return {r["id"]: ((400, json.dumps(self.beijing_server)) if r["id"] == "ub-beijing"
                          else (200, json.dumps(self.server_route))) for r in requests}


def fake_validate(python, manifest):
    return ""


class PackBase(unittest.TestCase):
    def setUp(self):
        self.env = Env()
        self._validate = nav_pack.validate_with_schema
        nav_pack.validate_with_schema = fake_validate

    def tearDown(self):
        nav_pack.validate_with_schema = self._validate
        signal.signal(signal.SIGTERM, signal.SIG_DFL)
        signal.signal(signal.SIGINT, signal.default_int_handler)
        self.env.close()

    def publish(self, step_cls=FakeStep, force=False, tiles=False, **env):
        p = self.env.pipeline(**env)
        try:
            return step_cls(p, "unittest").run(force=force, tiles=tiles), p
        finally:
            p.log.close()
            if p.lock_fd:
                p.lock_fd.close()

    def manifest(self):
        return json.loads((self.env.root / "packs" / "mn" / "manifest.json").read_text())


# ====================================================================== configuration
class ConfigTests(unittest.TestCase):
    def setUp(self):
        self.env = Env()

    def tearDown(self):
        self.env.close()

    def pc(self, **env):
        return nav_pack.PackConfig(self.env.config(**env))

    def test_defaults_and_relaxations(self):
        pc = self.pc(PACK_WEEKLY_MIN_AGE_DAYS="7", PACK_TILES_MIN_AGE_DAYS="28", PACK_MIN_FREE_GB="2",
                     PACK_GATE2_MODE="engine")
        self.assertEqual((pc.weekly_min_days, pc.tiles_min_days, pc.retain, pc.gzip_level), (7, 28, 3, 6))
        self.assertEqual(pc.self_test_route["to"], {"lat": 49.4867, "lon": 105.9228})
        self.assertEqual(pc.self_test_query, "Сүхбаатар")
        self.assertEqual(pc.relaxations(), [])
        self.assertIn("NOT the Gate 2 engine", " ".join(self.pc().relaxations()))

    def test_navmn_guards(self):
        for env in ({"PACK_WEEKLY_MIN_AGE_DAYS": "0"}, {"PACK_GATE2_MODE": "evidence"},
                    {"PACK_TEST_FAULT": "gate2_diff", "PACK_GATE2_MODE": "engine"}):
            with self.subTest(env=env):
                base = {"NAV_COMPOSE_PROJECT": "navmn", "PACK_WEEKLY_MIN_AGE_DAYS": "7", "PACK_TILES_MIN_AGE_DAYS": "28",
                        "PACK_GATE2_MODE": "engine"}
                base.update(env)
                with self.assertRaises(nav.ConfigError):
                    self.pc(**base)

    def test_partial_tiles_and_notes_test_only(self):
        pc = self.pc(PACK_TEST_PARTIAL_TILES="1", PACK_TEST_NOTES="TEST ONLY")
        self.assertEqual(set(pc.tiles_check_points), set(nav.REF_POINTS))
        self.assertEqual(pc.notes, "TEST ONLY")
        text = " ".join(pc.relaxations())
        self.assertIn("PACK_TEST_PARTIAL_TILES=1", text)
        self.assertIn("PACK_TEST_NOTES", text)
        self.assertIn("X1", self.pc().tiles_check_points)
        for env in ({"PACK_TEST_PARTIAL_TILES": "1"}, {"PACK_TEST_NOTES": "x"}):
            with self.subTest(env=env):
                with self.assertRaises(nav.ConfigError):
                    self.pc(REBUILD_ALLOW_TEST_FAULTS="0", **env)
                with self.assertRaises(nav.ConfigError):
                    self.pc(NAV_COMPOSE_PROJECT="navmn", PACK_WEEKLY_MIN_AGE_DAYS="7", PACK_TILES_MIN_AGE_DAYS="28",
                            PACK_GATE2_MODE="engine", **env)

    def test_bad_values(self):
        for env in ({"PACK_GATE2_IMAGE": "ghcr.io/valhalla/valhalla:3.6.3"}, {"PACK_SEARCH_BUILDER_IMAGE": "python:3.14-slim"},
                    {"PACK_REGION": "cn"}, {"PACK_ATTRIBUTION": "someone"}, {"PACK_METHOD_URL": "http://x.example"},
                    {"PACK_GATE2_RATE": "9"}, {"PACK_SELF_TEST_ROUTE": "47.9,106.9;49.4,105.9;car"},
                    {"PACK_WEEKDAY": "8"}, {"PACK_TEST_FAULT": "nope"}, {"PACK_TEST_PAUSE_AT": "nope"},
                    {"PACK_GZIP_LEVEL": "0"}):
            with self.subTest(env=env), self.assertRaises(nav.ConfigError):
                self.pc(**env)

    def test_placeholder_method_url_refused(self):
        """NAV-020 review minor: documentation / reserved names are never a public pipeline repository."""
        for url in ("https://example.org/osm-navigation/pipeline", "https://example.invalid/x", "https://www.example.com/a",
                    "https://git.example.net/a", "https://repo.example/a", "https://x.invalid", "https://localhost.localhost/a",
                    "https://user@EXAMPLE.ORG./a"):
            with self.subTest(url=url), self.assertRaises(nav.ConfigError) as cm:
                self.pc(PACK_METHOD_URL=url)
            self.assertIn("placeholder", str(cm.exception))
        # .test: accepted in test projects, refused in navmn
        self.assertEqual(self.pc(PACK_METHOD_URL="https://pipeline.navmn.test/x").method_url, "https://pipeline.navmn.test/x")
        navmn = {"NAV_COMPOSE_PROJECT": "navmn", "PACK_WEEKLY_MIN_AGE_DAYS": "7", "PACK_TILES_MIN_AGE_DAYS": "28",
                 "PACK_GATE2_MODE": "engine"}
        with self.assertRaises(nav.ConfigError):
            self.pc(PACK_METHOD_URL="https://pipeline.navmn.test/x", **navmn)
        # real-looking hosts that merely contain the words pass
        for url in ("https://examples.org.mn/p", "https://notexample.com/p", "https://code.example-org.mn/p"):
            with self.subTest(url=url):
                self.assertEqual(self.pc(PACK_METHOD_URL=url, **navmn).method_url, url)
        self.assertEqual(nav_pack.placeholder_url(""), "")


# ====================================================================== selection (AC 2-4, A1 items 6-7)
class SelectTests(PackBase):
    def step(self, **env):
        p = self.env.pipeline(**env)
        self.addCleanup(p.log.close)
        p.lock_fd.close()          # select() needs no lock; the next step() takes it again
        p.lock_fd = None
        nav_pack.PackLayout(self.env.root).ensure()
        return FakeStep(p, "unittest")

    def served(self, routing, tiles):
        f = lambda k, v: {"kind": k, "version": v, "path": f"{v}/{nav_pack.FILE_NAME[k]}.gz"}  # noqa: E731
        return {"files": [f("tiles", tiles), f("routing", routing), f("search", routing)]}

    def test_branches(self):
        cur = slot_id()
        cases = [  # (env, served, force, tiles, expected cut)
            ({}, None, False, False, {"tiles", "routing", "search"}),
            ({"PACK_WEEKLY_MIN_AGE_DAYS": "7"}, self.served(slot_id(8), slot_id(10)), False, False, {"routing", "search"}),
            ({"PACK_WEEKLY_MIN_AGE_DAYS": "7"}, self.served(slot_id(8), slot_id(30)), False, False, {"tiles", "routing", "search"}),
            ({"PACK_WEEKLY_MIN_AGE_DAYS": "7"}, self.served(slot_id(3), slot_id(40)), False, False, set()),   # tiles waits
            ({"PACK_WEEKLY_MIN_AGE_DAYS": "7"}, self.served(slot_id(3), slot_id(10)), True, False, {"routing", "search"}),
            ({"PACK_WEEKLY_MIN_AGE_DAYS": "7"}, self.served(slot_id(3), slot_id(10)), True, True, {"tiles", "routing", "search"}),
            ({"PACK_WEEKLY_MIN_AGE_DAYS": "7"}, self.served(slot_id(3), slot_id(10)), False, True, {"tiles", "routing", "search"}),
        ]
        for env, served, force, tiles, want in cases:
            with self.subTest(env=env, force=force, tiles=tiles, want=want):
                st = self.step(**env)
                cut, why = st.select(served, cur, force or tiles, tiles)
                self.assertEqual(cut, want, why)

    def test_weekday_and_write_once_and_newer_tiles(self):
        cur = slot_id()
        today = nav.utc_now().astimezone(nav.UB).isoweekday()
        st = self.step(PACK_WEEKDAY=str(today % 7 + 1))
        cut, why = st.select(self.served(slot_id(8), slot_id(10)), cur, False, False)
        self.assertEqual(cut, set())
        self.assertIn("PACK_WEEKDAY", why)
        st = self.step()
        (self.env.root / "packs" / "mn" / cur).mkdir()
        cut, why = st.select(self.served(slot_id(8), slot_id(10)), cur, True, True)
        self.assertEqual(cut, set())
        self.assertIn("already published", why)
        older = slot_id(2)
        cut, why = st.select(self.served(slot_id(8), slot_id(1)), older, False, False)
        self.assertIn("tiles", cut)


# ====================================================================== gzip, checksums, PMTiles
class FileTests(unittest.TestCase):
    def test_gzip_is_deterministic_and_hashed_from_disk(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            src = d / "in.bin"
            src.write_bytes(b"NAV-020 " * 100000)
            a, b = d / "a.gz", d / "b.gz"
            sa = nav_pack.gzip_file(src, a, 6)
            time.sleep(1.1)
            sb = nav_pack.gzip_file(src, b, 6)
            self.assertEqual(sa, sb)
            self.assertEqual(sa, (hashlib.sha256(src.read_bytes()).hexdigest(), 800000))
            self.assertEqual(a.read_bytes(), b.read_bytes())
            raw = a.read_bytes()
            self.assertEqual(raw[:3], b"\x1f\x8b\x08")
            self.assertEqual(raw[3] & 0x08, 0, "no FNAME field")
            self.assertEqual(raw[4:8], b"\x00\x00\x00\x00", "mtime 0")
            out = d / "out.bin"
            sha, n, head = nav_pack.gunzip_digest(a, out, head=8)
            self.assertEqual((sha, n, head), (sa[0], 800000, b"NAV-020 "))
            self.assertEqual(out.read_bytes(), src.read_bytes())
            self.assertEqual(len(raw), os.path.getsize(a))

    def test_fixed_gzip_vector(self):
        """Known input -> fixed digests (B4): the gzip bytes depend only on the input, the level and zlib."""
        with tempfile.TemporaryDirectory() as d:
            src = Path(d) / "v"
            src.write_bytes(b"\x00" * 1024)
            nav_pack.gzip_file(src, Path(d) / "v.gz", 6)
            raw = (Path(d) / "v.gz").read_bytes()
            self.assertEqual(raw[:10], b"\x1f\x8b\x08\x00\x00\x00\x00\x00\x00\xff")
            self.assertEqual(gzip.decompress(raw), b"\x00" * 1024)
            self.assertEqual(raw[-8:], struct.pack("<II", zlib.crc32(b"\x00" * 1024), 1024))   # CRC-32 and ISIZE
            self.assertEqual(hashlib.sha256(raw).hexdigest(), FIXED_GZIP_SHA256)

    def test_pmtiles_header(self):
        hdr = nav_pack.pmtiles_header(pmtiles_bytes()[:127])
        self.assertEqual((hdr["version"], hdr["min_zoom"], hdr["max_zoom"]), (3, 0, 14))
        self.assertEqual(nav_pack.tiles_problems(hdr), [])
        self.assertIn("zoom 0-13", " ".join(nav_pack.tiles_problems(nav_pack.pmtiles_header(pmtiles_bytes(maxz=13)[:127]))))
        ub_only = nav_pack.pmtiles_header(pmtiles_bytes(bounds=(106.5, 47.7, 107.2, 48.1))[:127])
        self.assertIn("X1", " ".join(nav_pack.tiles_problems(ub_only)))
        self.assertEqual(nav_pack.tiles_problems(ub_only, dict(nav.REF_POINTS)), [])
        self.assertEqual(nav_pack.tiles_problems(nav_pack.pmtiles_header(b"x" * 127)), ["not a PMTiles archive (magic missing)"])


# ====================================================================== Gate 2 comparator
class CompareTests(unittest.TestCase):
    def test_polyline(self):
        self.assertEqual(nav_pack.decode_polyline6("_p~iF~ps|U_ulLnnqC_mqNvxq`@"),
                         [(3850000, -12020000), (4070000, -12095000), (4325200, -12645300)])

    def test_equal_and_rounding(self):
        a = json.loads(json.dumps(OK_ROUTE))
        b = json.loads(json.dumps(OK_ROUTE))
        b["routes"][0]["distance"] = 4271.9          # rounds to 4272 on both sides
        a["routes"][0]["distance"] = 4272.4
        b["routes"][0]["legs"][0]["steps"][0]["maneuver"]["instruction"] = "different narrative"   # not compared
        self.assertIsNone(nav_pack.first_difference(nav_pack.normalise(json.dumps(a)), nav_pack.normalise(json.dumps(b))))
        a["routes"][0]["distance"] = 4273.0
        d = nav_pack.first_difference(nav_pack.normalise(json.dumps(a)), nav_pack.normalise(json.dumps(b)))
        self.assertEqual(d, ("routes[0].distance_m", 4273, 4272))

    def test_manoeuvre_geometry_alternates(self):
        a = json.loads(json.dumps(OK_ROUTE))
        b = json.loads(json.dumps(OK_ROUTE))
        b["routes"][0]["legs"][0]["steps"][0]["maneuver"]["modifier"] = "left"
        self.assertEqual(nav_pack.first_difference(nav_pack.normalise(a), nav_pack.normalise(b))[0],
                         "routes[0].steps[0].modifier")
        b = json.loads(json.dumps(OK_ROUTE))
        b["routes"][0]["geometry"] = "_p~iF~ps|U_ulLnnqC_mqNvxq`A"
        self.assertTrue(nav_pack.first_difference(nav_pack.normalise(a), nav_pack.normalise(b))[0].startswith("routes[0].geometry"))
        b = json.loads(json.dumps(OK_ROUTE))
        b["routes"].append(b["routes"][0])
        self.assertEqual(nav_pack.first_difference(nav_pack.normalise(a), nav_pack.normalise(b))[0], "routes (count)")

    def test_error_envelope_vs_osrm(self):
        eng = nav_pack.normalise(json.dumps({"code": 171, "message": "No suitable edges near location"}))
        srv = nav_pack.normalise(json.dumps({"code": "NoSegment", "message": "One of the supplied ..."}))
        self.assertEqual(eng, {"code": "NoSegment"})
        self.assertIsNone(nav_pack.first_difference(eng, srv))
        self.assertEqual(nav_pack.normalise(json.dumps({"code": 442, "message": "x"}))["code"], "NoRoute")
        self.assertEqual(nav_pack.normalise(json.dumps({"code": 154, "message": "x"}))["code"], "DistanceExceeded")
        self.assertNotEqual(nav_pack.normalise("<html>")["code"], "Ok")


# ====================================================================== AC 13 app pin
class AppPinTests(unittest.TestCase):
    def check(self, text, configured="0.6.3"):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "libs.versions.toml"
            if text is not None:
                p.write_text(text)
            pc = type("PC", (), {"mobile_version": configured})()
            return nav_pack.engine_version_check(pc, p)

    def test_cases(self):
        ok, msg = self.check(None)
        self.assertTrue(ok)
        self.assertIn("from configuration (0.6.3), app pin not present", msg)
        ref = '[versions]\nvalhallaMobile = "0.6.3"\n[libraries]\nvalhalla-mobile = { module = "io.github.rallista:valhalla-mobile", version.ref = "valhallaMobile" }\n'
        self.assertEqual(self.check(ref)[0], True)
        self.assertEqual(self.check(ref.replace('"0.6.3"', '"0.7.0"'))[0], False)
        inline = '[libraries]\nvm = { module = "io.github.rallista:valhalla-mobile", version = "0.6.4" }\n'
        ok, msg = self.check(inline)
        self.assertFalse(ok)
        self.assertIn("0.6.4", msg)
        self.assertTrue(self.check('[libraries]\nvm = "io.github.rallista:valhalla-mobile:0.6.3"\n')[0])
        self.assertTrue(self.check('[versions]\nkotlin = "2.0"\n')[0])     # not pinned yet


# ====================================================================== the whole step (Docker parts faked)
class StepTests(PackBase):
    def test_first_publication_and_per_file_versions(self):
        a = slot_id(10)
        self.env.add_slot(a)
        rec, _ = self.publish()
        self.assertEqual(rec["result"], "published", rec)
        m = self.manifest()
        self.assertEqual({f["version"] for f in m["files"]}, {a})
        self.assertEqual(m["pack_version"], a)
        self.assertEqual(nav_pack.manifest_problems(m, self.env.root / "packs" / "mn"), [])
        for f in m["files"]:
            gz = self.env.root / "packs" / "mn" / f["path"]
            self.assertEqual(hashlib.sha256(gz.read_bytes()).hexdigest(), f["download_sha256"])
            self.assertEqual(hashlib.sha256(gzip.decompress(gz.read_bytes())).hexdigest(), f["sha256"])
        tiles = {f["kind"]: f for f in m["files"]}
        slot = self.env.root / "slots" / a
        self.assertEqual(tiles["routing"]["sha256"], hashlib.sha256((slot / "valhalla" / "valhalla_tiles.tar").read_bytes()).hexdigest())
        self.assertEqual(tiles["tiles"]["sha256"], hashlib.sha256((slot / "tiles" / "basemap.pmtiles").read_bytes()).hexdigest())
        self.assertEqual(tiles["routing"]["data_timestamp"], "2026-09-29T08:02:53Z")
        self.assertEqual(tiles["search"]["data_timestamp"], "2026-09-26T22:59:05Z")
        self.assertEqual(tiles["routing"]["format"], {"graph_builder": "valhalla 3.9.0"})
        self.assertEqual(self.env.alert_lines(), [])
        self.assertFalse((self.env.root / "packs" / ".work" / "x").exists())
        self.assertEqual(list((self.env.root / "packs" / ".work").iterdir()), [])
        # weekly cut from a newer slot keeps the older tiles (A1 item 6); no pre-existing file changes (AC 8)
        before = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in (self.env.root / "packs" / "mn").glob("*/*.gz")}
        b = slot_id(2)
        self.env.add_slot(b, tar_seed=b"r2")
        rec, _ = self.publish()
        self.assertEqual(rec["result"], "published", rec)
        m = self.manifest()
        v = {f["kind"]: f["version"] for f in m["files"]}
        self.assertEqual(v, {"tiles": a, "routing": b, "search": b})
        for p, sha in before.items():
            self.assertEqual(hashlib.sha256(p.read_bytes()).hexdigest(), sha)
        # same active slot again: not due (write-once), manifest unchanged
        mt = (self.env.root / "packs" / "mn" / "manifest.json").stat().st_mtime
        rec, _ = self.publish(force=True)
        self.assertEqual(rec["result"], "not due")
        self.assertEqual((self.env.root / "packs" / "mn" / "manifest.json").stat().st_mtime, mt)

    def test_retention_over_four_publications(self):
        ids = [slot_id(40 - 10 * i) for i in range(4)]
        for i, sid in enumerate(ids):
            self.env.add_slot(sid, tiles_seed=bytes([i]), tar_seed=bytes([i]))
            rec, _ = self.publish(PACK_TILES_MIN_AGE_DAYS="0")
            self.assertEqual(rec["result"], "published", rec)
        region = self.env.root / "packs" / "mn"
        self.assertFalse((region / ids[0]).exists(), "publication 1 files are retired")
        for sid in ids[1:]:
            for f in ("basemap.pmtiles.gz", "routing.tar.gz", "search.sqlite.gz"):
                self.assertTrue((region / sid / f).is_file(), f"{sid}/{f}")
        self.assertEqual(len(list((region / ".manifests").iterdir())), 3)
        hist = json.loads((region / ".history.json").read_text())
        self.assertEqual([h["pack_version"] for h in hist], ids)

    def test_mtime_always_advances(self):
        a = slot_id(10)
        self.env.add_slot(a)
        self.publish()
        m1 = (self.env.root / "packs" / "mn" / "manifest.json").stat().st_mtime
        self.env.add_slot(slot_id(5), tar_seed=b"z")
        self.publish()
        self.assertGreater(int((self.env.root / "packs" / "mn" / "manifest.json").stat().st_mtime), int(m1))

    def test_gate2_difference_blocks_and_alerts_once(self):
        a = slot_id(10)
        self.env.add_slot(a)
        self.publish()
        before = (self.env.root / "packs" / "mn" / "manifest.json").read_bytes()
        newest = slot_id(3)
        self.env.add_slot(newest, tar_seed=b"new")
        for env in ({"PACK_TEST_FAULT": "gate2_diff"}, {}):
            with self.subTest(env=env):
                cls = FakeStep
                if not env:
                    class Different(FakeStep):
                        engine_route = dict(OK_ROUTE, routes=[dict(OK_ROUTE["routes"][0], duration=290.0)])
                    cls = Different
                n0 = len(self.env.alert_lines())
                rec, _ = self.publish(step_cls=cls, **env)
                self.assertEqual(rec["result"], "failed (gate 2)", rec)
                self.assertIn("p1-p3-car", rec["reason"])
                self.assertEqual((self.env.root / "packs" / "mn" / "manifest.json").read_bytes(), before)
                self.assertEqual(len(self.env.alert_lines()) - n0, 1)
                self.assertIn("pack: failed (gate 2)|pack.gate2|50", self.env.alert_lines()[-1])
                self.assertEqual([d.name for d in (self.env.root / "packs" / "mn").iterdir() if d.name.endswith(".partial")], [])
        # the serving slot is not rolled back: the pointer still names the newest slot
        self.assertEqual(nav.Layout(self.env.root).pointer()["slot"], newest)

    def test_self_test_failures(self):
        self.env.add_slot(slot_id(10))
        rec, _ = self.publish(PACK_TEST_FAULT="self_test_tiles")
        self.assertEqual(rec["result"], "failed (self-test)")
        self.assertIn("zoom 0-13", rec["reason"])
        self.assertFalse((self.env.root / "packs" / "mn" / "manifest.json").exists())

        class BadSearch(FakeStep):
            def search_selftest(self, db):
                return 1, {"result": "failed", "problems": ["quick_check: *** in database main ***"]}, ""

        rec, _ = self.publish(step_cls=BadSearch)
        self.assertEqual(rec["result"], "failed (self-test)")
        self.assertIn("quick_check", rec["reason"])

        class NoRoute(FakeStep):
            def run_engine(self, image, requests):
                out = super().run_engine(image, requests)
                out[nav_pack.SELF_TEST_ID] = {"id": nav_pack.SELF_TEST_ID, "response": json.dumps({"code": 442, "message": "x"})}
                return out

        rec, _ = self.publish(step_cls=NoRoute)
        self.assertEqual(rec["result"], "failed (self-test)")
        self.assertEqual(len(self.env.alert_lines()), 3)

    def test_low_disk_and_not_eligible(self):
        self.env.add_slot(slot_id(10))
        rec, _ = self.publish(PACK_MIN_FREE_GB="100000")
        self.assertEqual(rec["result"], "skipped (low disk)")
        self.assertIn("required 100000 GB", rec["reason"])
        self.assertEqual(len(self.env.alert_lines()), 1)
        self.assertFalse((self.env.root / "packs" / "mn" / "manifest.json").exists())
        st = nav.load_state(nav.Layout(self.env.root))
        st["post_switch_pending"] = {"slot": "x"}
        nav.save_state(nav.Layout(self.env.root), st)
        rec, _ = self.publish()
        self.assertEqual(rec["result"], "skipped (not eligible)")
        self.assertEqual(len(self.env.alert_lines()), 1, "not eligible does not alert")

    def test_engine_version_mismatch(self):
        self.env.add_slot(slot_id(10))
        orig = nav_pack.APP_VERSIONS_TOML
        toml = self.env.dir / "libs.versions.toml"
        toml.write_text('[libraries]\nvm = "io.github.rallista:valhalla-mobile:0.7.0"\n')
        nav_pack.APP_VERSIONS_TOML = toml
        try:
            # engine_version_check's default argument was bound at import: pass through the module attribute
            orig_check = nav_pack.engine_version_check
            nav_pack.engine_version_check = lambda pc, t=None: orig_check(pc, toml)
            rec, _ = self.publish()
        finally:
            nav_pack.APP_VERSIONS_TOML = orig
            nav_pack.engine_version_check = orig_check
        self.assertEqual(rec["result"], "failed (engine version mismatch)")
        self.assertIn("0.7.0", rec["reason"])

    def test_interrupt_and_reconcile(self):
        a = slot_id(10)
        self.env.add_slot(a)
        self.publish()
        served = (self.env.root / "packs" / "mn" / "manifest.json").read_bytes()
        b = slot_id(3)
        self.env.add_slot(b, tar_seed=b"b")

        class Killed(FakeStep):
            def gzip_all(self, src, cut):
                super().gzip_all(src, cut)
                raise nav.Interrupted(signal.SIGTERM)

        rec, _ = self.publish(step_cls=Killed)
        self.assertEqual(rec["result"], "failed (interrupted)")
        region = self.env.root / "packs" / "mn"
        self.assertFalse((region / f"{b}.partial").exists())
        self.assertEqual((region / "manifest.json").read_bytes(), served)
        # kill -9 leftovers: a partial dir, a renamed but unpublished version dir, a temp manifest, a work dir
        (region / f"{b}.partial").mkdir()
        (region / "20200101T000000Z").mkdir()
        (region / "20200101T000000Z" / "routing.tar.gz").write_bytes(b"x")
        (region / f".manifest.json.tmp-{os.getpid()}").write_text("{")
        (self.env.root / "packs" / ".work" / "20200101T000000Z").mkdir(parents=True)
        rec, _ = self.publish()
        self.assertEqual(rec["result"], "published", rec)
        names = {p.name for p in region.iterdir()}
        self.assertNotIn(f"{b}.partial", names)
        self.assertNotIn("20200101T000000Z", names)
        self.assertFalse(any(n.startswith(".manifest.json.tmp") for n in names))
        self.assertEqual(self.manifest()["pack_version"], b)
        for f in self.manifest()["files"]:
            self.assertEqual(hashlib.sha256((region / f["path"]).read_bytes()).hexdigest(), f["download_sha256"])

    def test_history_recovered_after_crash_between_rename_and_history(self):
        a = slot_id(10)
        self.env.add_slot(a)
        self.publish()
        region = self.env.root / "packs" / "mn"
        (region / ".history.json").write_text("[]\n")
        self.env.add_slot(slot_id(3), tar_seed=b"q")
        rec, _ = self.publish()
        self.assertEqual(rec["result"], "published")
        hist = json.loads((region / ".history.json").read_text())
        self.assertEqual(hist[0]["reason"], "recovered (history written by reconcile)")
        self.assertTrue((region / a).is_dir(), "files of the recovered manifest are kept")

    def test_rollback_republish_and_no_clean_manifest(self):
        a, b = slot_id(10), slot_id(3)
        self.env.add_slot(a)
        self.publish()
        self.env.add_slot(b, tar_seed=b"b")
        self.publish()
        p = self.env.pipeline()
        try:
            t0 = time.monotonic()
            res = nav_pack.rollback_hook(p, b)
            self.assertLess(time.monotonic() - t0, 60)
        finally:
            p.log.close()
            p.lock_fd.close()
        self.assertEqual(res["result"], "rollback: republished")
        m = self.manifest()
        self.assertEqual(m["pack_version"], a)
        self.assertTrue(all(f["version"] == a for f in m["files"]))
        hist = json.loads((self.env.root / "packs" / "mn" / ".history.json").read_text())
        self.assertEqual(hist[-1]["reason"], "rollback")
        self.assertEqual(nav_pack.pack_status(self.env.config())["last_result"]["result"], "rollback: republished")
        n0 = len(self.env.alert_lines())
        p = self.env.pipeline()
        try:
            res = nav_pack.rollback_hook(p, a)
        finally:
            p.log.close()
            p.lock_fd.close()
        self.assertEqual(res["result"], "rollback: no clean manifest")
        self.assertEqual(self.manifest()["pack_version"], a)
        self.assertEqual(len(self.env.alert_lines()) - n0, 1)

    def test_status_is_fast_and_complete(self):
        self.env.add_slot(slot_id(10))
        self.publish()
        t0 = time.monotonic()
        r = subprocess.run([sys.executable, str(PIPELINE), "--env-file", str(self.env.env_file), "status"],
                           capture_output=True, text=True, timeout=30)
        self.assertLess(time.monotonic() - t0, 2.0 + 1.0, "status within 2 s (+1 s interpreter start on a loaded host)")
        doc = json.loads(r.stdout)["pack"]
        self.assertEqual(doc["last_result"]["result"], "published")
        self.assertEqual(set(doc["files"]), {"tiles", "routing", "search"})
        for f in doc["files"].values():
            self.assertTrue(f["version"] and f["data_timestamp"] and f["download_bytes"])
        self.assertIn("valhalla_mobile_version", doc["gate2_engine"])
        self.assertTrue(doc["pack_version"] and doc["published_at"])


class HookTests(PackBase):
    def test_hook_runs_only_on_success_and_never_raises(self):
        src = inspect.getsource(nav.Pipeline._rebuild)
        self.assertEqual(src.count("pack_after_success()"), 1)
        self.assertLess(src.index("pack_after_success()"), src.index('return self.finish(0, "success")'))
        self.assertGreater(src.index("pack_after_success()"), src.index('with self.step("cleanup")'))
        for name in ("unchanged", "handle_failure", "rollback_after_switch"):
            self.assertNotIn("pack_after_success", inspect.getsource(getattr(nav.Pipeline, name)))
        p = self.env.pipeline(PACK_ENABLED="0")
        try:
            self.assertEqual(p.pack_after_success(), {"result": "disabled"})
            orig = nav_pack.after_rebuild
            nav_pack.after_rebuild = lambda _p: 1 / 0
            try:
                self.assertEqual(p.pack_after_success()["result"], "failed (build)")
            finally:
                nav_pack.after_rebuild = orig
        finally:
            p.log.close()
            p.lock_fd.close()

    def test_run_log_survives_a_full_disk(self):
        class Full(io.StringIO):
            def write(self, s):
                raise OSError(28, "No space left on device")
        log = nav.RunLog(nav.Layout(self.env.root), None, "unittest")
        log.f = Full()
        out = io.StringIO()
        real = sys.stdout
        sys.stdout = out
        try:
            log.event("info", "first")
            log.event("info", "second")
        finally:
            sys.stdout = real
        self.assertIsNone(log.f)
        lines = out.getvalue().splitlines()
        self.assertEqual(len(lines), 3, lines)          # first, the write-failure note, second
        self.assertIn("No space left on device", lines[1])

    def test_bad_pack_config_on_hook_alerts_but_does_not_raise(self):
        p = self.env.pipeline(PACK_GATE2_RATE="50")
        try:
            r = nav_pack.after_rebuild(p)
        finally:
            p.log.close()
            p.lock_fd.close()
        self.assertEqual(r["result"], "failed (build)")
        self.assertEqual(len(self.env.alert_lines()), 1)

    def test_second_command_while_locked_exits_10_within_5s(self):
        lock = self.env.dir / "lock"
        fd = open(lock, "a")
        fcntl.flock(fd.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            t0 = time.monotonic()
            r = subprocess.run([sys.executable, str(PIPELINE), "--env-file", str(self.env.env_file), "pack-publish"],
                               capture_output=True, text=True, timeout=30)
            took = time.monotonic() - t0
        finally:
            fd.close()
        self.assertEqual(r.returncode, 10, r.stdout + r.stderr)
        self.assertLess(took, 5.0)
        self.assertIn(str(lock), r.stdout)

    def test_manifest_rules(self):
        a = slot_id(10)
        self.env.add_slot(a)
        self.publish()
        m = self.manifest()
        region = self.env.root / "packs" / "mn"
        bad = json.loads(json.dumps(m))
        bad["files"][0]["version"] = slot_id(-1)
        bad["files"][0]["path"] = f"{bad['files'][0]['version']}/basemap.pmtiles.gz"
        self.assertTrue(any("tiles.version > routing.version" in x for x in nav_pack.manifest_problems(bad, region)))
        bad = json.loads(json.dumps(m))
        bad["total_bytes"] += 1
        self.assertIn("total_bytes != sum(bytes)", nav_pack.manifest_problems(bad, region))
        bad = json.loads(json.dumps(m))
        bad["files"].pop()
        self.assertTrue(nav_pack.manifest_problems(bad, region))

    @unittest.skipUnless((nav_pack.BACKEND / ".venv" / "bin" / "python").exists(), "backend/.venv (jsonschema) not present")
    def test_manifest_validates_against_openapi(self):
        self.env.add_slot(slot_id(10))
        self.publish()
        py = str(nav_pack.BACKEND / ".venv" / "bin" / "python")
        self.assertEqual(self._validate(py, self.manifest()), "")
        bad = dict(self.manifest(), pack_schema=2)
        self.assertIn("pack_schema", self._validate(py, bad))


if __name__ == "__main__":
    unittest.main()

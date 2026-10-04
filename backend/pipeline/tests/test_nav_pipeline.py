"""NAV-006 pipeline unit tests (stdlib unittest, no Docker, a few seconds).

    make -C backend pipeline-test        # or: python3 -m unittest discover -s backend/pipeline/tests -v

Covers: check 3(a)-(f) on synthetic PBFs (50 % truncated copy, HTML page, too small, shrunk, older, stale,
missing coverage), the ordered source list with fallback and the 13/20 classification, configuration guards
(test faults only outside navmn, relative/dev data roots, grace/retry minimums, scheduled overrides), the lock
(second command exits 10 within 5 s and names the lock; a SIGKILLed holder leaves no stale lock), rollback
refusal without a previous slot (exit 30 within 5 s), the alert hook (environment, timeout, exit code unchanged),
pointer validation and atomic writes, status on an empty root.
"""
import datetime as dt
import json
import os
import random
import signal
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


# ---------------------------------------------------------------- synthetic PBF writer
def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def zz(n):
    return (n << 1) ^ (n >> 63)


def fld(num, wt, payload):
    key = varint((num << 3) | wt)
    if wt == 0:
        return key + varint(payload)
    return key + varint(len(payload)) + payload


def blob(btype, data, compress=True):
    body = fld(2, 0, len(data)) + (fld(3, 2, zlib.compress(data)) if compress else fld(1, 2, data))
    header = fld(1, 2, btype.encode()) + fld(3, 0, len(body))
    return struct.pack(">I", len(header)) + header + body


def make_pbf(path, bbox=(106.0, 47.0, 108.0, 49.0), ts=None, pad_bytes=0):
    """Minimal valid PBF: OSMHeader (bbox, replication timestamp) + one DenseNodes block + random padding blocks."""
    left, bottom, right, top = (int(v * 1e9) for v in bbox)
    hbbox = fld(1, 0, zz(left)) + fld(2, 0, zz(right)) + fld(3, 0, zz(top)) + fld(4, 0, zz(bottom))
    header = fld(1, 2, hbbox) + fld(4, 2, b"OsmSchema-V0.6") + fld(4, 2, b"DenseNodes")
    if ts is not None:
        header += fld(32, 0, int(ts.timestamp()))
    lat, lon = int(47.9189e7), int(106.9176e7)   # granularity 100 nanodegrees
    dense = fld(1, 2, varint(zz(1))) + fld(8, 2, varint(zz(lat))) + fld(9, 2, varint(zz(lon)))
    prim = fld(1, 2, fld(1, 2, b"")) + fld(2, 2, fld(2, 2, dense))
    rnd = random.Random(7)
    with open(path, "wb") as f:
        f.write(blob("OSMHeader", header))
        f.write(blob("OSMData", prim))
        while pad_bytes > 0:
            chunk = bytes(rnd.getrandbits(8) for _ in range(min(pad_bytes, 65536)))
            f.write(blob("OSMData", chunk, compress=False))
            pad_bytes -= len(chunk)


# ---------------------------------------------------------------- helpers
class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="nav006-unit-"))
        self.root = self.tmp / "data"
        self.env = self.tmp / "t.env"
        self.write_env()

    def tearDown(self):
        for p in getattr(self, "_pipelines", []):
            p.log.close()
        subprocess.run(["rm", "-rf", str(self.tmp)], check=False)

    def write_env(self, **extra):
        base = {"NAV_COMPOSE_PROJECT": "navmn-unit", "NAV_DATA_ROOT": str(self.root),
                "NAV_LOCK_FILE": str(self.tmp / "lock"), "REBUILD_MIN_EXTRACT_MB": "0.0001",
                "REBUILD_MAX_DATA_AGE_HOURS": "48", "OSM_SOURCES": "file:/nonexistent"}
        base.update(extra)
        self.env.write_text("".join(f"{k}={v}\n" for k, v in base.items()))

    def cfg(self, **extra):
        self.write_env(**extra)
        return nav.Config(self.env).load()

    def pipeline(self, **extra):
        p = nav.Pipeline(self.cfg(**extra), "rebuild")
        p.lay.root.mkdir(parents=True, exist_ok=True)
        p.lay.ensure()
        p.run_id = "20261004T000000Z"
        p.log = nav.RunLog(p.lay, p.run_id, "unit")
        p.sleep = lambda s: None          # the 30 s retry delay is not waited for in unit tests
        self._pipelines = getattr(self, "_pipelines", []) + [p]
        return p

    def cli(self, *args, timeout=20):
        return subprocess.run([sys.executable, str(PIPELINE), "--env-file", str(self.env), *args],
                              capture_output=True, text=True, timeout=timeout)


# ---------------------------------------------------------------- validation 3(a)-(f) and AC 4
class ValidationTests(Base):
    def setUp(self):
        super().setUp()
        self.now = nav.utc_now() - dt.timedelta(hours=2)
        self.good = self.tmp / "good.osm.pbf"
        make_pbf(self.good, ts=self.now, pad_bytes=200_000)
        self.p = self.pipeline()
        self.active = {"bytes": os.path.getsize(self.good), "date": nav.iso(self.now - dt.timedelta(hours=24))}

    def check_fails(self, path, check, active=None, **cfg):
        p = self.pipeline(**cfg) if cfg else self.p
        with self.assertRaises(nav.SourceError) as cm:
            p.validate(path, {}, active, {})
        self.assertTrue(cm.exception.check.startswith(check), cm.exception)
        return cm.exception

    def test_good_extract_passes_all_checks(self):
        info, facts = self.p.validate(self.good, {}, self.active, {})
        self.assertEqual(facts["data_date_source"], "replication_timestamp")
        self.assertEqual(facts["blocks"], 2 + 4)
        self.assertIn("full_read", info)

    def test_truncated_to_50_percent_fails_3a(self):
        half = self.tmp / "half.osm.pbf"
        half.write_bytes(self.good.read_bytes()[: os.path.getsize(self.good) // 2])
        e = self.check_fails(half, "3(a)")
        self.assertIn("truncated", e.measured)

    def test_html_page_fails_3a(self):
        page = self.tmp / "page.osm.pbf"
        page.write_text("<!DOCTYPE html><html><body>502 Bad Gateway</body></html>")
        e = self.check_fails(page, "3(a)")
        self.assertIn("HTML", e.measured)

    def test_one_byte_short_fails_3a(self):
        short = self.tmp / "short.osm.pbf"
        short.write_bytes(self.good.read_bytes()[:-1])
        self.check_fails(short, "3(a)")

    def test_bbox_without_reference_points_fails_3b(self):
        far = self.tmp / "far.osm.pbf"
        make_pbf(far, bbox=(100.0, 45.0, 101.0, 46.0), ts=self.now)
        e = self.check_fails(far, "3(b)")
        self.assertIn("P1", e.measured)

    def test_too_small_fails_3c(self):
        e = self.check_fails(self.good, "3(c)", REBUILD_MIN_EXTRACT_MB="40")
        self.assertIn("40 MB", e.measured)

    def test_shrunk_fails_3d_and_override_accepts(self):
        active = dict(self.active, bytes=int(os.path.getsize(self.good) / 0.8))   # new = 80 % of active
        e = self.check_fails(self.good, "3(d)", active)
        self.assertIn("80", e.measured)
        _, facts = self.p.validate(self.good, {}, active, {"accept_size_drop": True})
        self.assertEqual(facts["bytes"], os.path.getsize(self.good))

    def test_first_build_skips_3d_3e(self):
        _, facts = self.p.validate(self.good, {}, None, {})
        self.assertIn("first build", facts["first_build"])

    def test_older_fails_3e_unless_relaxed(self):
        active = dict(self.active, date=nav.iso(self.now + dt.timedelta(hours=1)))
        self.check_fails(self.good, "3(e)", active)
        p = self.pipeline(REBUILD_REQUIRE_NOT_OLDER="0")
        p.validate(self.good, {}, active, {})

    def test_stale_fails_3f_unless_disabled(self):
        old = self.tmp / "old.osm.pbf"
        make_pbf(old, ts=self.now - dt.timedelta(hours=60))
        e = self.check_fails(old, "3(f)")
        self.assertIn("> 48 h", e.measured)
        self.pipeline(REBUILD_MAX_DATA_AGE_HOURS="0").validate(old, {}, None, {})

    def test_last_modified_is_the_date_without_replication_timestamp(self):
        plain = self.tmp / "plain.osm.pbf"
        make_pbf(plain, ts=None)
        lm = (nav.utc_now() - dt.timedelta(hours=3)).strftime("%a, %d %b %Y %H:%M:%S GMT")
        _, facts = self.p.validate(plain, {"last_modified": lm}, None, {})
        self.assertEqual(facts["data_date_source"], "last_modified")

    def test_injected_validation_fault(self):
        p = self.pipeline(REBUILD_TEST_FAULT="validate", REBUILD_ALLOW_TEST_FAULTS="1")
        with self.assertRaises(nav.SourceError):
            p.validate(self.good, {}, None, {})


# ---------------------------------------------------------------- source list (AC 2) and outcomes
class SourceListTests(Base):
    def test_fallback_to_second_source_after_one_retry(self):
        good = self.tmp / "good.osm.pbf"
        make_pbf(good, ts=nav.utc_now())
        p = self.pipeline(OSM_SOURCES=f"file:{self.tmp}/missing.pbf file:{good}")
        st = nav.load_state(p.lay)
        kind, entry = p.resolve_osm(st, None, False, {})
        self.assertEqual(kind, "candidate")
        self.assertEqual(entry["source"], f"file:{good}")
        lines = [json.loads(x) for x in p.log.path.read_text().splitlines()]
        failed = [x for x in lines if x.get("result") == "failed" and x.get("step") == "fetch"]
        self.assertEqual([x["attempt"] for x in failed], [1, 2])   # the failing source was retried once
        self.assertTrue((p.lay.cache_osm / f"{entry['sha256']}.osm.pbf").exists())

    def test_every_source_unreachable_is_skipped_no_valid_source(self):
        p = self.pipeline(OSM_SOURCES=f"file:{self.tmp}/a.pbf file:{self.tmp}/b.pbf")
        with self.assertRaises(nav.Outcome) as cm:
            p.resolve_osm(nav.load_state(p.lay), None, False, {})
        self.assertEqual((cm.exception.code, cm.exception.result), (13, "skipped: no valid source"))

    def test_invalid_extract_everywhere_is_failed_at_validation(self):
        bad = self.tmp / "bad.osm.pbf"
        bad.write_text("<html>not found</html>")
        p = self.pipeline(OSM_SOURCES=f"file:{bad}")
        with self.assertRaises(nav.Outcome) as cm:
            p.resolve_osm(nav.load_state(p.lay), None, False, {})
        self.assertEqual(cm.exception.code, 20)
        self.assertIn("3(a)", cm.exception.reason)

    def test_unchanged_and_rolled_back_checksums(self):
        good = self.tmp / "good.osm.pbf"
        make_pbf(good, ts=nav.utc_now())
        sha = nav.sha256_file(good)
        p = self.pipeline(OSM_SOURCES=f"file:{good}")
        slot = p.lay.slot("20261003T000000Z")
        slot.mkdir(parents=True)
        (slot / "build-info.json").write_text(json.dumps({"osm": {"sha256": sha, "bytes": 1}}))
        kind, _ = p.resolve_osm(nav.load_state(p.lay), "20261003T000000Z", False, {})
        self.assertEqual(kind, "unchanged")
        kind, _ = p.resolve_osm(nav.load_state(p.lay), "20261003T000000Z", True, {})   # FORCE=1 builds
        self.assertEqual(kind, "candidate")
        st = nav.load_state(p.lay)
        st["rolled_back_sha256"] = [sha]
        with self.assertRaises(nav.Outcome) as cm:
            p.resolve_osm(st, None, False, {})
        self.assertEqual((cm.exception.code, cm.exception.result), (14, "skipped: source was rolled back"))


# ---------------------------------------------------------------- configuration guards
class ConfigTests(Base):
    def assert_config_error(self, text, **extra):
        with self.assertRaises(nav.ConfigError) as cm:
            self.cfg(**extra)
        self.assertIn(text, str(cm.exception))

    def test_test_faults_only_outside_navmn_and_with_allow_flag(self):
        self.assert_config_error("REBUILD_ALLOW_TEST_FAULTS", REBUILD_TEST_FAULT="verify")
        self.assert_config_error("other than navmn", REBUILD_TEST_FAULT="verify", REBUILD_ALLOW_TEST_FAULTS="1",
                                 NAV_COMPOSE_PROJECT="navmn")
        self.assertEqual(self.cfg(REBUILD_TEST_FAULT="verify", REBUILD_ALLOW_TEST_FAULTS="1").test_fault, "verify")

    def test_data_root_rules(self):
        self.assert_config_error("absolute", NAV_DATA_ROOT="relative/data")
        self.assert_config_error("outside backend/data", NAV_DATA_ROOT=str(nav.BACKEND / "data" / "x"))
        self.assert_config_error("NAV_COMPOSE_PROJECT", NAV_COMPOSE_PROJECT="")

    def test_minimums(self):
        self.assert_config_error("REBUILD_GRACE_SECONDS", REBUILD_GRACE_SECONDS="5")
        self.assert_config_error("REBUILD_SOURCE_RETRY_DELAY_SECONDS", REBUILD_SOURCE_RETRY_DELAY_SECONDS="10")
        self.assert_config_error("REBUILD_LOG_RETENTION_DAYS", REBUILD_LOG_RETENTION_DAYS="7")
        self.assert_config_error("https://", OSM_SOURCES="http://insecure.example/x.pbf")

    def test_relaxations_are_listed(self):
        c = self.cfg(REBUILD_MAX_DATA_AGE_HOURS="0", REBUILD_REQUIRE_NOT_OLDER="0", REBUILD_MIN_FREE_GB="4")
        text = " ".join(c.relaxations())
        for want in ("3(f) OFF", "3(e) OFF", "REBUILD_MIN_FREE_GB=4"):
            self.assertIn(want, text)

    def test_source_list_fallback_keys(self):
        self.assertEqual(self.cfg(OSM_SOURCES="", OSM_PBF_FILE="/x.pbf").sources, ["file:/x.pbf"])
        self.assertEqual(self.cfg(OSM_SOURCES="").sources, [nav.DEFAULT_OSM_URL])

    def test_env_parser(self):
        f = self.tmp / "p.env"
        f.write_text("A=1\nA='two'\nexport B=\"x y\"\n# C=3\nD=\n")
        self.assertEqual(nav.parse_env_file(f), {"A": "two", "B": "x y", "D": ""})

    def test_scheduled_entry_point_refuses_overrides(self):
        r = self.cli("rebuild", "--scheduled", "--force")
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
        self.assertIn("refused on the scheduled entry point", r.stdout)


# ---------------------------------------------------------------- lock (AC 25), rollback refusal (AC 19)
class LockAndRollbackTests(Base):
    def test_second_command_exits_10_naming_the_lock_and_sigkill_releases_it(self):
        lock = self.tmp / "lock"
        holder = subprocess.Popen([sys.executable, "-c", (
            "import fcntl,sys,time; f=open(sys.argv[1],'a'); fcntl.flock(f, fcntl.LOCK_EX); "
            "print('held', flush=True); time.sleep(60)"), str(lock)], stdout=subprocess.PIPE, text=True)
        self.assertEqual(holder.stdout.readline().strip(), "held")
        t0 = time.monotonic()
        r = self.cli("rollback")
        took = time.monotonic() - t0
        self.assertEqual(r.returncode, 10, r.stdout)
        self.assertIn(str(lock), r.stdout)
        self.assertLess(took, 5)
        holder.send_signal(signal.SIGKILL)
        holder.wait()
        r = self.cli("rollback")      # the kernel released the lock: no stale lock file blocks
        self.assertEqual(r.returncode, 30, r.stdout)

    def test_rollback_without_previous_slot_refuses_quickly(self):
        t0 = time.monotonic()
        r = self.cli("rollback")
        self.assertLess(time.monotonic() - t0, 5)
        self.assertEqual(r.returncode, 30)
        self.assertIn("no previous good slot", r.stdout)
        st = json.loads((self.root / "state.json").read_text())
        self.assertEqual(st["last_run"]["exit_code"], 30)
        self.assertIsNone(st["active"])

    def test_status_on_empty_root_is_fast_json(self):
        t0 = time.monotonic()
        r = self.cli("status")
        self.assertLess(time.monotonic() - t0, 2)
        doc = json.loads(r.stdout)
        self.assertIsNone(doc["active"])
        for key in ("previous", "last_run", "stale", "next_scheduled_run"):
            self.assertIn(key, doc)


# ---------------------------------------------------------------- alert hook (AC 32), pointer, state
class HookPointerStateTests(Base):
    def test_alert_hook_environment_and_timeout(self):
        out = self.tmp / "alert.txt"
        p = self.pipeline(REBUILD_ALERT_CMD=f"'env | grep ^NAV_RUN_ | sort > {out}'")
        p.alert(22, "failed", "verify", "smoke.py exit 1")
        text = out.read_text()
        self.assertIn("NAV_RUN_RESULT=failed", text)
        self.assertIn("NAV_RUN_STEP=verify", text)
        self.assertIn("NAV_RUN_EXIT_CODE=22", text)
        p = self.pipeline(REBUILD_ALERT_CMD="'sleep 30'", REBUILD_ALERT_TIMEOUT_SECONDS="1")
        t0 = time.monotonic()
        p.alert(13, "skipped: no valid source", "fetch", "x")
        self.assertLess(time.monotonic() - t0, 5)
        last = json.loads(p.log.path.read_text().splitlines()[-1])
        self.assertEqual(last["hook_exit_code"], "timeout")
        self.assertNotIn("sleep 30", p.log.path.read_text())   # the command itself is never logged

    def test_pointer_validation_and_atomic_write(self):
        lay = nav.Layout(self.root)
        lay.ensure()
        self.assertIsNone(lay.pointer())
        nav.write_pointer(lay.ptr_public, "20261004T193412Z", "green")
        p = lay.pointer()
        self.assertEqual(p["valhalla"], "valhalla-green:8002")
        self.assertEqual(p["tiles"], "/srv/slots/20261004T193412Z/tiles/basemap.pmtiles")
        self.assertEqual(os.stat(lay.ptr_public).st_mode & 0o777, 0o644)   # readable by the nginx user
        lay.ptr_public.write_text('{"slot":"../../etc","lane":"blue"}')
        self.assertIsNone(lay.pointer())

    def test_state_is_corrected_from_the_pointer(self):
        p = self.pipeline()
        lay = p.lay
        for s in ("20261001T000000Z", "20261002T000000Z"):
            lay.slot(s).mkdir(parents=True)
            (lay.slot(s) / ".slot-complete").write_text("{}")
            (lay.slot(s) / "build-info.json").write_text(json.dumps({"osm": {"sha256": s}}))
        # crash between the pointer rename and the state write of a switch
        nav.save_state(lay, dict(nav.load_state(lay), active="20261001T000000Z"))
        nav.write_pointer(lay.ptr_public, "20261002T000000Z", "green")
        st = p.reconcile_state_only(nav.load_state(lay), lay.pointer())
        self.assertEqual((st["active"], st["previous"]), ("20261002T000000Z", "20261001T000000Z"))
        # crash between the pointer rename and the state write of a rollback
        nav.write_pointer(lay.ptr_public, "20261001T000000Z", "blue")
        st = p.reconcile_state_only(nav.load_state(lay), lay.pointer())
        self.assertEqual((st["active"], st["previous"]), ("20261001T000000Z", None))
        self.assertIn("20261002T000000Z", st["rolled_back"])


class RefreshAuxTests(Base):
    def test_set_aside_restore_and_keep(self):
        p = self.pipeline()
        (p.lay.cache_sources / "qrank.csv.gz").write_text("old")
        (p.lay.cache_tools / "photon.jar").write_text("old")
        p.set_aux_aside()
        self.assertEqual(list(p.lay.cache_sources.iterdir()), [])          # empty: aux-fetch downloads everything
        (p.lay.cache_sources / "qrank.csv.gz").write_text("partial")
        p.restore_aux_aside(keep_new=False)                                 # failed run: the old cache comes back
        self.assertEqual((p.lay.cache_sources / "qrank.csv.gz").read_text(), "old")
        self.assertFalse(p.lay.cache_sources.with_name("sources.previous").exists())
        p.set_aux_aside()
        (p.lay.cache_tools / "photon.jar").write_text("new")
        p.restore_aux_aside(keep_new=True)                                  # good run: fresh files kept
        self.assertEqual((p.lay.cache_tools / "photon.jar").read_text(), "new")
        self.assertFalse(p.lay.cache_tools.with_name("tools.previous").exists())

    def test_refresh_aux_is_refused_on_the_scheduled_entry_point(self):
        r = self.cli("rebuild", "--scheduled", "--refresh-aux")
        self.assertEqual(r.returncode, 2)
        self.assertIn("REFRESH_AUX", r.stdout)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""NAV-006 daily OSM rebuild pipeline: immutable slots, blue/green lanes, pointer switch (ADR-0014).

    nav_pipeline.py --env-file FILE rebuild [--force] [--accept-size-drop] [--accept-route-change]
                                            [--refresh-aux] [--scheduled] [--assume-locked]
    nav_pipeline.py --env-file FILE rollback
    nav_pipeline.py --env-file FILE status
    nav_pipeline.py --env-file FILE checksums SLOT_ID

Normally run through `make rebuild | rollback | status` (backend/Makefile) or, on staging, the timer
(infra/staging/bin/nav-rebuild.sh). Python 3 stdlib only; needs docker (compose v2) and curl on the host.

rebuild:  lock -> reconcile -> guards (disk, memory) -> fetch + validate the extract (ordered source list)
          -> Photon dump -> build into slots/<run-id>.partial (one-shot builders) -> complete + rename
          -> verify on the free lane through the private gateway-verify (smoke, contract, reference routes,
          artefact sizes) -> switch = rename(2) of pointer/public/active.json -> post-switch smoke (automatic
          rollback on failure) -> grace -> stop the old lane -> keep exactly 2 slots. An interrupt during the
          post-switch smoke leaves the old lane running and state.json.post_switch_pending set; the next run's
          reconciliation runs that smoke before it stops the old lane (rollback on failure).
rollback: start the previous slot's lane, rename the pointer back, mark the newer slot rolled back.
status:   one JSON object (active/previous slot, data dates, last run, stale, next scheduled run). No lock.

Exit codes (RUNBOOK.md NAV-006 section):
  0 success, or skipped: unchanged with a healthy active slot and fresh data
  2 usage/configuration error (also: an override on the scheduled entry point, isolation guard)
  10 lock held by another command
  11 skipped: low disk   12 skipped: low memory   13 skipped: no valid source   14 skipped: source was rolled back
  15 skipped: unchanged, but the active slot failed smoke or its data is stale
  20 failed at validation   21 failed at build   22 failed at verification   23 failed at switch / post-switch
  24 rolled back automatically after the post-switch check (also by reconciliation, when the post-switch check of
     an interrupted run is still pending and fails then)
  30 rollback refused (no previous good slot)   31 rollback failed
  40 interrupted (SIGTERM/SIGINT); cleanup done

Logs: one JSON line per step (run_id, step, result, duration_s) plus a final summary, on stdout and in
NAV_DATA_ROOT/runs/<run-id>.jsonl. Only public source URLs, local paths, sizes, durations, slot IDs and the
reference points P1-P6. Never the alert command, push URLs or client data.
"""
import argparse
import contextlib
import datetime as dt
import errno
import fcntl
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from email.utils import parsedate_to_datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
BACKEND = HERE.parent
REPO = BACKEND.parent
COMPOSE_FILE = BACKEND / "compose.slots.yaml"
SMOKE = BACKEND / "scripts" / "smoke.py"
CONTRACT = BACKEND / "scripts" / "contract_check.py"
SPEC = REPO / "docs" / "architecture" / "api" / "openapi.yaml"
sys.path.insert(0, str(BACKEND / "scripts"))
import pbfinfo  # noqa: E402  (backend/scripts/pbfinfo.py)

UB = dt.timezone(dt.timedelta(hours=8))          # Asia/Ulaanbaatar, no DST
SLOT_RE = re.compile(r"^[0-9]{8}T[0-9]{6}Z$")
LANES = ("blue", "green")
REF_POINTS = {  # NAV-001 reference points (lat, lon), the same as scripts/smoke.py
    "P1": (47.9189, 106.9176), "P2": (47.9139, 106.9044), "P3": (47.8858, 106.9173),
    "P4": (47.9215, 106.8950), "P5": (47.9095, 106.8835), "P6": (47.9600, 106.9000),
}
AUX_FILES = ("natural_earth_vector.gpkg.zip", "water-polygons-split-3857.zip", "land-polygons-split-3857.zip",
             "daylight-landcover.gpkg", "qrank.csv.gz", "pgf-encoding.zip")
DEFAULT_OSM_URL = "https://download.geofabrik.de/asia/mongolia-latest.osm.pbf"
DEFAULT_DUMP_URL = "https://download1.graphhopper.com/public/asia/mongolia/photon-dump-mongolia-1.0-latest.jsonl.zst"
DEFAULT_UA = "nav-mn-rebuild/2 (NAV-006; +https://www.openstreetmap.org/copyright; daily, at most one download per day)"
TEST_FAULTS = ("validate", "build", "verify", "post_switch")
# Results that call the alert hook (AC 32). Plus: stale active data on any run.
ALERT_CODES = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24, 31, 40}
# Staging defaults; a smaller/disabled value is logged as a relaxation on every run (AC 37).
STAGING = {"REBUILD_MIN_FREE_GB": 50.0, "REBUILD_HARD_FLOOR_FREE_GB": 5.0, "REBUILD_MIN_MEM_AVAILABLE_GB": 6.5,
           "REBUILD_MAX_DATA_AGE_HOURS": 48.0, "REBUILD_MIN_EXTRACT_MB": 40.0}


# ====================================================================== small helpers
def utc_now():
    return dt.datetime.now(dt.timezone.utc)


def iso(t=None):
    return (t or utc_now()).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(s):
    if not s:
        return None
    try:
        return dt.datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone(dt.timezone.utc)
    except ValueError:
        return None


def http_date_to_iso(s):
    try:
        return iso(parsedate_to_datetime(s).astimezone(dt.timezone.utc))
    except (TypeError, ValueError):
        return None


def sha256_file(path, algo="sha256"):
    h = hashlib.new(algo)
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fsync_dir(path):
    fd = os.open(str(path), os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic_write(path, data, mode=0o644):
    """temp file in the same directory, fsync, rename(2), fsync of the directory (ADR-0014 §1)."""
    path = Path(path)
    tmp = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.chmod(tmp, mode)
    os.rename(tmp, path)
    fsync_dir(path.parent)


def read_json(path, default=None):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, NotADirectoryError):
        return default
    except ValueError:
        return default


def symlink_atomic(link, target):
    """ln -sfn target link, atomically (temp symlink + rename)."""
    link = Path(link)
    tmp = link.with_name(f".{link.name}.tmp-{os.getpid()}")
    with contextlib.suppress(FileNotFoundError):
        os.unlink(tmp)
    os.symlink(target, tmp)
    os.rename(tmp, link)
    fsync_dir(link.parent)


def free_gib(path):
    st = os.statvfs(path)
    return st.f_bavail * st.f_frsize / 1024 ** 3


def mem_available_gib():
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) / 1024 ** 2
    return 0.0


def dir_usage(path, exclude=()):
    """(apparent bytes of all files, disk bytes of files not hard-linked elsewhere)."""
    total = unique = 0
    for root, dirs, files in os.walk(path):
        rel = os.path.relpath(root, path)
        if any(rel == e or rel.startswith(e + os.sep) for e in exclude):
            dirs[:] = []
            continue
        for name in files:
            try:
                st = os.lstat(os.path.join(root, name))
            except FileNotFoundError:
                continue
            total += st.st_size
            if st.st_nlink == 1:
                unique += st.st_blocks * 512
    return total, unique


def rmtree(path):
    shutil.rmtree(path, ignore_errors=True)
    if os.path.lexists(path):
        subprocess.run(["rm", "-rf", "--", str(path)], check=False)


# ====================================================================== configuration
def parse_env_file(path):
    """KEY=VALUE lines, last assignment wins, optional export and surrounding quotes (never sourced)."""
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\r\n")
            m = re.match(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$", line)
            if not m:
                continue
            val = m.group(2)
            if len(val) >= 2 and val[0] == val[-1] and val[0] in "'\"":
                val = val[1:-1]
            out[m.group(1)] = val
    return out


class ConfigError(Exception):
    pass


class Config:
    """Pipeline configuration: the env file, overridden by the process environment (as Compose does)."""

    def __init__(self, env_file):
        self.env_file = Path(env_file).resolve()
        if not self.env_file.is_file():
            raise ConfigError(f"config file not found: {self.env_file}")
        self.values = parse_env_file(self.env_file)

    def get(self, key, default=""):
        v = os.environ.get(key)
        if v is None:
            v = self.values.get(key)
        return default if v is None or v == "" else v

    def num(self, key, default):
        raw = self.get(key, str(default))
        try:
            return float(raw)
        except ValueError:
            raise ConfigError(f"{key} must be a number, got {raw!r}") from None

    def load(self):
        g = self.get
        self.project = g("NAV_COMPOSE_PROJECT")
        if not re.match(r"^[a-z0-9][a-z0-9_-]*$", self.project or ""):
            raise ConfigError("NAV_COMPOSE_PROJECT must be set (lower-case Compose project name)")
        root = g("NAV_DATA_ROOT")
        if not root or not os.path.isabs(root):
            raise ConfigError("NAV_DATA_ROOT must be an absolute path (staging /var/lib/nav/data)")
        self.root = Path(root)
        dev_data = (BACKEND / "data").resolve()
        rr = Path(os.path.realpath(root))
        if rr == dev_data or dev_data in rr.parents or rr in dev_data.parents:
            raise ConfigError(f"NAV_DATA_ROOT must be outside backend/data (the dev stack's data): {root}")
        self.lock_file = Path(g("NAV_LOCK_FILE", "/run/lock/nav-stack.lock"))
        self.overlays = [str((REPO / p).resolve()) if not os.path.isabs(p) else p
                         for p in g("NAV_COMPOSE_OVERLAYS").split()]
        self.gateway_port = int(self.num("GATEWAY_PORT", 8080))
        self.verify_port = int(self.num("NAV_VERIFY_PORT", 8089))
        if self.verify_port == self.gateway_port:
            raise ConfigError("NAV_VERIFY_PORT must differ from GATEWAY_PORT")
        self.public_url = f"http://127.0.0.1:{self.gateway_port}"
        self.verify_url = f"http://127.0.0.1:{self.verify_port}"
        # Post-switch / "unchanged" smoke: through Caddy on staging (STAGING_HOST), else the public gateway port.
        host = g("STAGING_HOST")
        self.smoke_url = g("SMOKE_BASE_URL", f"https://{host}" if host else self.public_url).rstrip("/")
        # source list (AC 2): OSM_SOURCES, else the NAV-001 keys
        srcs = g("OSM_SOURCES").split()
        if not srcs:
            srcs = [f"file:{g('OSM_PBF_FILE')}"] if g("OSM_PBF_FILE") else [g("OSM_PBF_URL", DEFAULT_OSM_URL)]
        for s in srcs:
            if not (s.startswith("https://") or (s.startswith("file:/") and len(s) > 6)):
                raise ConfigError(f"OSM_SOURCES entries must be https:// URLs or file:<absolute path>: {s!r}")
        self.sources = srcs
        self.dump_file = g("PHOTON_DUMP_FILE")
        self.dump_url = g("PHOTON_DUMP_URL", DEFAULT_DUMP_URL)
        self.stall_s = int(self.num("REBUILD_SOURCE_STALL_SECONDS", 120))
        self.retry_delay_s = self.num("REBUILD_SOURCE_RETRY_DELAY_SECONDS", 30)
        if self.retry_delay_s < 30:
            raise ConfigError("REBUILD_SOURCE_RETRY_DELAY_SECONDS must be >= 30 (AC 2)")
        self.min_extract_mb = self.num("REBUILD_MIN_EXTRACT_MB", 40)
        self.min_size_ratio = self.num("REBUILD_MIN_SIZE_RATIO", 0.90)
        self.require_not_older = g("REBUILD_REQUIRE_NOT_OLDER", "1") == "1"
        self.max_age_h = self.num("REBUILD_MAX_DATA_AGE_HOURS", 48)
        self.stale_h = self.max_age_h if self.max_age_h > 0 else 48.0
        self.route_dev = self.num("REBUILD_ROUTE_MAX_DEVIATION", 0.20)
        self.artefact_ratio = self.num("REBUILD_ARTEFACT_MIN_RATIO", 0.90)
        self.min_free_gb = self.num("REBUILD_MIN_FREE_GB", 50)
        self.floor_free_gb = self.num("REBUILD_HARD_FLOOR_FREE_GB", 5)
        self.min_mem_gb = self.num("REBUILD_MIN_MEM_AVAILABLE_GB", 6.5)
        self.grace_s = self.num("REBUILD_GRACE_SECONDS", 30)
        if self.grace_s < 30:
            raise ConfigError("REBUILD_GRACE_SECONDS must be >= 30 (AC 17)")
        self.lane_timeout_s = int(self.num("REBUILD_LANE_START_TIMEOUT_SECONDS", 240))
        self.rollback_timeout_s = int(self.num("REBUILD_ROLLBACK_START_TIMEOUT_SECONDS", 55))
        self.retention_days = self.num("REBUILD_LOG_RETENTION_DAYS", 30)
        if self.retention_days < 14:
            raise ConfigError("REBUILD_LOG_RETENTION_DAYS must be >= 14 (AC 31)")
        self.alert_cmd = g("REBUILD_ALERT_CMD")
        self.alert_timeout = min(self.num("REBUILD_ALERT_TIMEOUT_SECONDS", 10), 10)
        self.user_agent = g("REBUILD_USER_AGENT", DEFAULT_UA)
        self.https_proxy = g("BUILD_HTTPS_PROXY")
        self.test_fault = g("REBUILD_TEST_FAULT")
        self.allow_faults = g("REBUILD_ALLOW_TEST_FAULTS") == "1"
        self.allow_legacy = g("NAV_ALLOW_LEGACY_PROJECT") == "1"
        self.timer_unit = g("NAV_TIMER_UNIT", "nav-rebuild.timer")
        venv = BACKEND / ".venv" / "bin" / "python"
        self.contract_python = g("CONTRACT_PYTHON", str(venv) if venv.exists() else sys.executable)
        if self.test_fault:
            if self.test_fault not in TEST_FAULTS:
                raise ConfigError(f"REBUILD_TEST_FAULT must be one of {', '.join(TEST_FAULTS)}")
            if not self.allow_faults or self.project == "navmn":
                raise ConfigError("REBUILD_TEST_FAULT is honoured only with REBUILD_ALLOW_TEST_FAULTS=1 and a "
                                  "Compose project other than navmn (test projects only)")
        return self

    def relaxations(self):
        out = []
        if self.max_age_h <= 0:
            out.append("freshness check 3(f) OFF (REBUILD_MAX_DATA_AGE_HOURS=0)")
        elif self.max_age_h > STAGING["REBUILD_MAX_DATA_AGE_HOURS"]:
            out.append(f"freshness check 3(f) relaxed to {self.max_age_h:g} h (staging 48)")
        if not self.require_not_older:
            out.append("not-older check 3(e) OFF (REBUILD_REQUIRE_NOT_OLDER=0)")
        for key, attr in (("REBUILD_MIN_FREE_GB", "min_free_gb"), ("REBUILD_HARD_FLOOR_FREE_GB", "floor_free_gb"),
                          ("REBUILD_MIN_MEM_AVAILABLE_GB", "min_mem_gb"), ("REBUILD_MIN_EXTRACT_MB", "min_extract_mb")):
            if getattr(self, attr) < STAGING[key]:
                out.append(f"{key}={getattr(self, attr):g} (staging {STAGING[key]:g})")
        if self.test_fault:
            out.append(f"test fault injection REBUILD_TEST_FAULT={self.test_fault}")
        return out


# ====================================================================== layout, state, pointer
class Layout:
    def __init__(self, root):
        self.root = Path(root)
        self.cache = self.root / "cache"
        self.cache_sources = self.cache / "sources"
        self.cache_tools = self.cache / "tools"
        self.cache_osm = self.cache / "osm"
        self.cache_dump = self.cache / "photon-dump"
        self.cache_index = self.cache / "photon-index"
        self.slots = self.root / "slots"
        self.lanes = self.root / "lanes"
        self.ptr_public = self.root / "pointer" / "public" / "active.json"
        self.ptr_verify = self.root / "pointer" / "verify" / "active.json"
        self.state = self.root / "state.json"
        self.runs = self.root / "runs"

    def ensure(self):
        for d in (self.cache_sources, self.cache_tools, self.cache_osm, self.cache_dump, self.cache_index,
                  self.slots, self.lanes, self.ptr_public.parent, self.ptr_verify.parent, self.runs):
            d.mkdir(parents=True, exist_ok=True)
        for d in (self.root, self.slots, self.ptr_public.parent, self.ptr_verify.parent, self.root / "pointer"):
            os.chmod(d, 0o755)   # the unprivileged gateway (uid 101) reads pointer and slots

    def slot(self, slot_id):
        return self.slots / slot_id

    def complete(self, slot_id):
        return bool(slot_id) and SLOT_RE.match(slot_id) and (self.slot(slot_id) / ".slot-complete").is_file()

    def build_info(self, slot_id):
        return read_json(self.slot(slot_id) / "build-info.json", {}) if slot_id else {}

    def pointer(self, which="public"):
        p = read_json(self.ptr_public if which == "public" else self.ptr_verify)
        if not isinstance(p, dict) or not SLOT_RE.match(str(p.get("slot", ""))) or p.get("lane") not in LANES:
            return None
        return p


def pointer_doc(slot_id, lane):
    return {"slot": slot_id, "lane": lane, "valhalla": f"valhalla-{lane}:8002", "photon": f"photon-{lane}:2322",
            "tiles": f"/srv/slots/{slot_id}/tiles/basemap.pmtiles", "switched_at": iso()}


def write_pointer(path, slot_id, lane):
    atomic_write(path, json.dumps(pointer_doc(slot_id, lane), separators=(",", ":")) + "\n")


def load_state(lay):
    st = read_json(lay.state, {}) or {}
    st.setdefault("version", 1)
    for k in ("active", "previous", "last_download", "photon_dump", "last_run", "last_success"):
        st.setdefault(k, None)
    st.setdefault("rolled_back", [])
    st.setdefault("rolled_back_sha256", [])
    st.setdefault("lanes", {})
    return st


def save_state(lay, st):
    atomic_write(lay.state, json.dumps(st, ensure_ascii=False, indent=2) + "\n")


def osm_facts(info):
    o = (info or {}).get("osm") or {}
    date = o.get("data_date") or o.get("replication_timestamp") or http_date_to_iso(o.get("http_last_modified"))
    src = o.get("data_date_source") or ("replication_timestamp" if o.get("replication_timestamp") else
                                        ("last_modified" if o.get("http_last_modified") else None))
    return {"sha256": o.get("sha256"), "md5": o.get("md5"), "bytes": o.get("bytes"), "date": date,
            "date_source": src, "source": o.get("source")}


# ====================================================================== outcome, logging, processes
class Outcome(Exception):
    def __init__(self, code, result, step=None, reason=None):
        super().__init__(reason or result)
        self.code, self.result, self.step, self.reason = code, result, step, reason


class Interrupted(Outcome):
    def __init__(self, signum):
        name = signal.Signals(signum).name
        super().__init__(40, "interrupted", None, f"{name} received; cleanup done")


class RunLog:
    def __init__(self, lay, run_id, kind):
        self.run_id, self.kind = run_id, kind
        self.path = lay.runs / f"{run_id}.jsonl" if run_id else None
        self.f = open(self.path, "a", encoding="utf-8") if self.path else None

    def emit(self, rec):
        line = json.dumps(rec, ensure_ascii=False)
        print(line, flush=True)
        if self.f:
            self.f.write(line + "\n")
            self.f.flush()

    def close(self):
        if self.f:
            self.f.close()
            self.f = None

    def event(self, level, msg, **kv):
        rec = {"ts": iso(), "job": "nav-pipeline", "run_id": self.run_id, "kind": self.kind, "level": level, "msg": msg}
        rec.update(kv)
        self.emit(rec)

    def step(self, step, result, duration_s, **kv):
        rec = {"ts": iso(), "job": "nav-pipeline", "run_id": self.run_id, "kind": self.kind, "step": step,
               "result": result, "duration_s": round(duration_s, 1)}
        rec.update(kv)
        self.emit(rec)


class Proc:
    """Child processes in their own session, so a terminal Ctrl-C reaches only the pipeline, which then
    cleans up deliberately. Every child is killed (process group) if the pipeline is interrupted."""
    live = set()

    @classmethod
    def run(cls, cmd, timeout=None, out=None, env=None, check=False, cwd=None):
        stdout = subprocess.PIPE if out is None else out
        p = subprocess.Popen([str(c) for c in cmd], stdout=stdout, stderr=subprocess.STDOUT if out is not None else subprocess.PIPE,
                             env=env, cwd=cwd, start_new_session=True, text=True)
        cls.live.add(p)
        try:
            so, se = p.communicate(timeout=timeout)
        except BaseException:
            cls.kill(p)
            raise
        finally:
            cls.live.discard(p)
        res = subprocess.CompletedProcess(p.args, p.returncode, so or "", se or "")
        if check and p.returncode != 0:
            raise subprocess.CalledProcessError(p.returncode, p.args, so, se)
        return res

    @classmethod
    def start(cls, cmd, out):
        p = subprocess.Popen([str(c) for c in cmd], stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
        cls.live.add(p)
        return p

    @staticmethod
    def kill(p):
        with contextlib.suppress(ProcessLookupError, PermissionError):
            os.killpg(p.pid, signal.SIGTERM)
        try:
            p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            with contextlib.suppress(ProcessLookupError, PermissionError):
                os.killpg(p.pid, signal.SIGKILL)
            p.wait()

    @classmethod
    def kill_all(cls):
        for p in list(cls.live):
            cls.kill(p)
            cls.live.discard(p)


# ====================================================================== the pipeline
class Pipeline:
    def __init__(self, cfg, kind):
        self.cfg = cfg
        self.lay = Layout(cfg.root)
        self.kind = kind
        self.run_id = None
        self.log = None
        self.lock_fd = None
        self.in_critical = False
        self.pending_signal = None
        self.phase = "init"
        self.candidate = None          # slot id being built/verified
        self.free_lane = None
        self.builders = []             # container names of running one-shot builders
        self.disk_low = threading.Event()
        self.watch_stop = threading.Event()
        self.step_name = None
        self.facts = {}                # summary facts
        self.aux_aside = False

    # ------------------------------------------------------------------ infrastructure
    def compose(self, *args, timeout=600, out=None, check=False):
        cmd = ["docker", "compose", "-p", self.cfg.project, "--project-directory", BACKEND, "-f", COMPOSE_FILE]
        for o in self.cfg.overlays:
            cmd += ["-f", o]
        cmd += ["--env-file", self.cfg.env_file, *args]
        return Proc.run(cmd, timeout=timeout, out=out, check=check)

    def docker(self, *args, timeout=60):
        return Proc.run(["docker", *args], timeout=timeout)

    def acquire_lock(self, assume_locked=False):
        self.cfg.lock_file.parent.mkdir(parents=True, exist_ok=True)
        fd = open(self.cfg.lock_file, "a")  # Python opens with O_CLOEXEC: children never inherit the lock
        try:
            fcntl.flock(fd.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as e:
            fd.close()
            if e.errno not in (errno.EWOULDBLOCK, errno.EAGAIN):
                raise
            if assume_locked:
                return  # the caller (deploy.sh) holds it and runs us as its child
            raise Outcome(10, "failed", "lock", f"another rebuild, rollback, deploy or certificate dry run holds "
                                                f"the lock {self.cfg.lock_file}; not starting") from None
        self.lock_fd = fd

    def on_signal(self, signum, _frame):
        if self.in_critical:
            self.pending_signal = signum
            return
        raise Interrupted(signum)

    @contextlib.contextmanager
    def critical(self):
        """Pointer + state writes are never split by SIGTERM/SIGINT."""
        self.in_critical = True
        try:
            yield
        finally:
            self.in_critical = False
            if self.pending_signal:
                s, self.pending_signal = self.pending_signal, None
                raise Interrupted(s)

    @contextlib.contextmanager
    def step(self, name, **kv):
        self.step_name = name
        t0 = time.monotonic()
        extra = dict(kv)
        try:
            yield extra
        except Outcome as o:
            res = "interrupted" if isinstance(o, Interrupted) else ("skipped" if o.result.startswith("skipped") else "failed")
            self.log.step(name, res, time.monotonic() - t0, reason=o.reason, **extra)
            if o.step is None:
                o.step = name
            raise
        except Exception as e:  # noqa: BLE001 - every unexpected error ends the step as failed
            self.log.step(name, "failed", time.monotonic() - t0, reason=f"{type(e).__name__}: {e}", **extra)
            raise
        else:
            result = extra.pop("result", "ok")
            self.log.step(name, result, time.monotonic() - t0, **extra)

    def sleep(self, seconds):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            time.sleep(min(1.0, end - time.monotonic()))

    def isolation_guard(self):
        """Refuse a project whose containers were created from backend/compose.yaml (the dev stack)."""
        r = self.docker("ps", "-a", "--filter", f"label=com.docker.compose.project={self.cfg.project}",
                        "--format", '{{.Names}}\t{{.Label "com.docker.compose.project.config_files"}}')
        if r.returncode != 0:
            raise Outcome(2, "failed", "config", f"docker ps failed: {r.stderr.strip()[:200]}")
        foreign = [ln.split("\t")[0] for ln in r.stdout.splitlines()
                   if ln.strip() and "compose.slots.yaml" not in ln.split("\t", 1)[-1]]
        if foreign and not self.cfg.allow_legacy:
            raise Outcome(2, "failed", "config",
                          f"project {self.cfg.project!r} has containers not created from compose.slots.yaml "
                          f"({', '.join(foreign[:5])}); refusing to touch it (protects the dev stack; staging "
                          f"migration only: NAV_ALLOW_LEGACY_PROJECT=1, RUNBOOK NAV-006 checklist)")

    def service_states(self):
        """{service: state} of this project's service containers (docker labels; independent of profiles)."""
        r = self.docker("ps", "-a", "--filter", f"label=com.docker.compose.project={self.cfg.project}",
                        "--filter", "label=com.docker.compose.oneoff=False",
                        "--format", '{{.Label "com.docker.compose.service"}}\t{{.State}}')
        out = {}
        for ln in r.stdout.splitlines():
            parts = ln.split("\t")
            if len(parts) == 2:
                out[parts[0]] = parts[1]
        return out

    def container_state(self, service):
        return self.service_states().get(service, "absent")

    def lanes_running(self):
        states = self.service_states()
        return {lane for lane in LANES
                if "running" in (states.get(f"valhalla-{lane}"), states.get(f"photon-{lane}"))}

    def stop_lane(self, lane, timeout=60):
        r = self.compose("stop", f"valhalla-{lane}", f"photon-{lane}", timeout=timeout)
        return r.returncode == 0

    def start_lane(self, lane, slot_id, recreate, timeout):
        """Point lanes/<lane> at the slot and bring the lane up healthy. recreate=False only starts a stopped
        container that already has this slot mounted (rollback)."""
        if recreate:
            symlink_atomic(self.lay.lanes / lane, f"../slots/{slot_id}")
            args = ["up", "-d", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", str(timeout)]
        else:
            args = ["up", "-d", "--no-deps", "--no-recreate", "--wait", "--wait-timeout", str(timeout)]
        r = self.compose(*args, f"valhalla-{lane}", f"photon-{lane}", timeout=timeout + 60)
        if r.returncode == 0:
            st = load_state(self.lay)
            st["lanes"][lane] = {"slot": slot_id, "since": iso()}
            save_state(self.lay, st)
        return r.returncode == 0, (r.stderr or r.stdout).strip()[-300:]

    def lane_mounted_slot(self, lane):
        """Slot the lane's (possibly stopped) Valhalla container has mounted, or None."""
        r = self.docker("ps", "-aq", "--filter", f"label=com.docker.compose.project={self.cfg.project}",
                        "--filter", f"label=com.docker.compose.service=valhalla-{lane}",
                        "--filter", "label=com.docker.compose.oneoff=False")
        cid = r.stdout.strip().splitlines()[0] if r.stdout.strip() else ""
        if not cid:
            return None
        # Docker reports the bind source as given (the lanes/<lane> symlink), not the directory it resolved at
        # creation. The pipeline records the slot whenever it (re)creates a lane; trust that record only while
        # the symlink still points at it (a failed recreate leaves them different -> the caller recreates).
        r = self.docker("inspect", "-f", '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}', cid)
        src = r.stdout.strip()
        current = os.path.basename(os.path.realpath(src)) if src else ""
        recorded = (load_state(self.lay)["lanes"].get(lane) or {}).get("slot")
        return recorded if recorded and recorded == current else None

    def ensure_gateway(self):
        """Create/start the public gateway if absent or stopped. Never recreates a running one (AC 14)."""
        state = self.container_state("gateway")
        if state == "running":
            return
        r = self.compose("up", "-d", "--no-deps", "--no-recreate", "--wait", "--wait-timeout", "60", "gateway", timeout=120)
        self.log.event("info" if r.returncode == 0 else "error", "public gateway was not running; started",
                       previous_state=state, exit_code=r.returncode)

    @staticmethod
    def result_line(path, pattern):
        """The summary line of a check's output (e.g. '42 passed, 0 failed in 1.0s'), else its last line."""
        lines = path.read_text(encoding="utf-8", errors="replace").strip().splitlines()
        hits = [ln.strip() for ln in lines if re.search(pattern, ln)]
        return (hits or lines or [""])[-1].strip()

    def smoke(self, base_url, label):
        out = self.lay.runs / f"{self.run_id}.{label}.txt"
        with open(out, "w", encoding="utf-8") as f:
            r = Proc.run([sys.executable, SMOKE, "--base-url", base_url], timeout=600, out=f)
        return r.returncode, self.result_line(out, r"\d+ passed, \d+ failed"), out

    def contract(self, base_url, label):
        out = self.lay.runs / f"{self.run_id}.{label}.txt"
        with open(out, "w", encoding="utf-8") as f:
            r = Proc.run([self.cfg.contract_python, CONTRACT, "--base-url", base_url, "--spec", SPEC], timeout=600, out=f)
        return r.returncode, self.result_line(out, r"\d+ conform"), out

    def alert(self, code, result, step, reason):
        if not self.cfg.alert_cmd:
            self.log.event("info", "alert hook not configured (REBUILD_ALERT_CMD empty)", result=result)
            return
        env = dict(os.environ, NAV_RUN_ID=self.run_id or "", NAV_RUN_RESULT=result, NAV_RUN_STEP=step or "",
                   NAV_RUN_REASON=reason or "", NAV_RUN_EXIT_CODE=str(code))
        try:
            r = subprocess.run(["sh", "-c", self.cfg.alert_cmd], env=env, timeout=self.cfg.alert_timeout,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            rc = r.returncode
        except subprocess.TimeoutExpired:
            rc = "timeout"
        except OSError as e:
            rc = f"error {e.errno}"
        self.log.event("info", "alert hook called", result=result, hook_exit_code=rc)

    # ------------------------------------------------------------------ reconcile (every run)
    def reconcile(self):
        st = load_state(self.lay)
        ptr = self.lay.pointer()
        # 1. leftover builders and the verify gateway of this project
        r = self.docker("ps", "-aq", "--filter", f"label=com.docker.compose.project={self.cfg.project}",
                        "--filter", "label=com.docker.compose.oneoff=True")
        ids = r.stdout.split()
        if ids:
            self.docker("rm", "-f", *ids, timeout=120)
            self.log.event("warn", "removed leftover builder containers", count=len(ids))
        if self.container_state("gateway-verify") != "absent":
            self.compose("rm", "-s", "-f", "gateway-verify", timeout=60)
            self.log.event("warn", "removed leftover gateway-verify")
        # 2. correct state.json from the pointer (the single source of truth)
        active = ptr["slot"] if ptr else None
        if st.get("active") != active:
            if active and active == st.get("previous"):
                old = st.get("active")
                if old:
                    st["rolled_back"].append(old)
                    sha = osm_facts(self.lay.build_info(old)).get("sha256")
                    if sha:
                        st["rolled_back_sha256"].append(sha)
                st["previous"] = None
                how = "rollback finished before state.json was written"
            else:
                if st.get("active") and self.lay.complete(st.get("active")):
                    st["previous"] = st.get("active")
                how = "switch finished before state.json was written"
            self.log.event("warn", "state.json disagreed with the pointer; corrected from the pointer",
                           state_active=st.get("active"), pointer_active=active, cause=how)
            st["active"] = active
            save_state(self.lay, st)
        # 3. a switch whose post-switch check did not finish: check it now, while the old lane still runs, and
        #    roll back on failure (QA F5). Then stop lanes that are not the pointer's lane (e.g. a run killed in
        #    its grace period).
        st = self.check_pending_post_switch(st, ptr)
        ptr = self.lay.pointer()
        running = self.lanes_running()
        for lane in running - ({ptr["lane"]} if ptr else set()):
            self.stop_lane(lane)
            self.log.event("warn", "stopped a lane that does not serve the active slot", lane=lane)
        # 4. delete partial/failed slots and every slot that is neither active nor previous
        keep = {s for s in (st.get("active"), st.get("previous")) if s}
        for d in sorted(self.lay.slots.iterdir()) if self.lay.slots.exists() else []:
            if d.name not in keep:
                rmtree(d)
                self.log.event("info", "deleted slot", slot=d.name)
        with contextlib.suppress(FileNotFoundError):
            os.unlink(self.lay.lanes / "build")
        if any(d.with_name(d.name + ".previous").exists() for d in (self.lay.cache_sources, self.lay.cache_tools)):
            self.restore_aux_aside(keep_new=False)      # a --refresh-aux run was killed: back to the known-good cache
        for p in list(self.lay.ptr_public.parent.glob(".*.tmp-*")) + list(self.lay.ptr_verify.parent.glob(".*.tmp-*")):
            p.unlink()
        if st.get("previous") and not self.lay.complete(st["previous"]):
            st["previous"] = None
            save_state(self.lay, st)
        return st

    # ------------------------------------------------------------------ fetch + validate (section A)
    def curl_base(self):
        cmd = ["curl", "-sS", "-L", "--fail", "--connect-timeout", "30", "-A", self.cfg.user_agent]
        if self.cfg.https_proxy:
            cmd += ["--proxy", self.cfg.https_proxy]
        return cmd

    def remote_md5(self, url):
        r = Proc.run(self.curl_base() + ["-m", "30", url + ".md5"], timeout=45)
        m = re.match(r"^([0-9a-f]{32})\b", r.stdout.strip()) if r.returncode == 0 else None
        return m.group(1) if m else None

    def download(self, url, dest):
        """curl with a stall timeout (no data for >= REBUILD_SOURCE_STALL_SECONDS fails). Returns Last-Modified."""
        hdr = dest.with_suffix(".headers")
        cmd = self.curl_base() + ["--speed-time", str(self.cfg.stall_s), "--speed-limit", "1",
                                  "-o", dest, "-D", hdr, "-w", "%{http_code}", url]
        r = Proc.run(cmd, timeout=None)
        code = r.stdout.strip()[-3:]
        lm = None
        if hdr.exists():
            for line in hdr.read_text(errors="replace").splitlines():
                if line.lower().startswith("last-modified:"):
                    lm = line.split(":", 1)[1].strip()
            hdr.unlink()
        if r.returncode != 0 or code != "200":
            with contextlib.suppress(FileNotFoundError):
                dest.unlink()
            raise SourceError("download", f"curl exit {r.returncode}, HTTP {code or '000'}: {r.stderr.strip()[-160:]}")
        return lm

    def validate(self, path, meta, active, overrides):
        """Checks 3(a)-(f). Returns measured facts; raises SourceError(check, measured)."""
        size = os.path.getsize(path)
        if self.cfg.test_fault == "validate":
            raise SourceError("3(a) readable", "injected test fault (REBUILD_TEST_FAULT=validate)")
        info = meta.get("pbf")
        if info is None:
            try:
                info = pbfinfo.describe(str(path), full=True)
            except Exception as e:  # noqa: BLE001
                raise SourceError("3(a) readable to the end", str(e)[:300]) from None
        bbox = info.get("bbox")
        outside = [k for k, (lat, lon) in REF_POINTS.items()
                   if not (bbox and None not in bbox and bbox[0] <= lon <= bbox[2] and bbox[1] <= lat <= bbox[3])]
        if outside:
            raise SourceError("3(b) coverage", f"bbox {bbox} misses {','.join(outside)}")
        if size < self.cfg.min_extract_mb * 1e6:
            raise SourceError("3(c) size floor", f"{size} bytes < {self.cfg.min_extract_mb:g} MB")
        date = info.get("replication_timestamp") or http_date_to_iso(meta.get("last_modified"))
        date_src = "replication_timestamp" if info.get("replication_timestamp") else ("last_modified" if date else None)
        if not date:
            raise SourceError("3(f) fresh", "no data date (no replication timestamp, no Last-Modified)")
        facts = {"bytes": size, "data_date": date, "data_date_source": date_src, "bbox": bbox,
                 "blocks": (info.get("full_read") or {}).get("blocks")}
        if active:
            a_bytes = active.get("bytes") or 0
            ratio = size / a_bytes if a_bytes else 1.0
            if ratio < self.cfg.min_size_ratio:
                if overrides.get("accept_size_drop"):
                    facts["size_drop_accepted"] = True
                    self.log.event("warn", "override ACCEPT_SIZE_DROP: size check 3(d) skipped for this run",
                                   old_bytes=a_bytes, new_bytes=size, ratio=round(ratio, 3))
                else:
                    raise SourceError("3(d) no large shrink", f"{size} bytes = {ratio:.1%} of the active {a_bytes} "
                                                             f"(< {self.cfg.min_size_ratio:.0%})")
            a_date = parse_iso(active.get("date"))
            if self.cfg.require_not_older and a_date and parse_iso(date) < a_date:
                raise SourceError("3(e) not older", f"data date {date} < active {active.get('date')}")
        else:
            facts["first_build"] = "3(d) and 3(e) skipped: first build"
        age_h = (utc_now() - parse_iso(date)).total_seconds() / 3600
        facts["age_hours"] = round(age_h, 1)
        if self.cfg.max_age_h > 0 and age_h > self.cfg.max_age_h:
            raise SourceError("3(f) fresh", f"data date {date} is {age_h:.1f} h old (> {self.cfg.max_age_h:g} h)")
        return info, facts

    def checks_passed(self, active, facts=None):
        """The checks the 'validate' step line lists. A disabled check is shown as off, never as passed (QA F2).
        3(d)/3(e) need an active slot; 3(d) is skipped when ACCEPT_SIZE_DROP=1 let a shrink through, 3(e) is off with
        REBUILD_REQUIRE_NOT_OLDER=0 and 3(f) with REBUILD_MAX_DATA_AGE_HOURS=0 (its 'has a data date' part still runs;
        'off' refers to the age limit, as in the config step's 'relaxed' line)."""
        out = ["3(a)", "3(b)", "3(c)", "3(f)" if self.cfg.max_age_h > 0 else "3(f) off"]
        if active:
            out += ["3(d) skipped" if (facts or {}).get("size_drop_accepted") else "3(d)",
                    "3(e)" if self.cfg.require_not_older else "3(e) off"]
        return " ".join(out)

    def resolve_osm(self, st, active_slot, force, overrides):
        """Try every source (each retried once). Returns ('unchanged', facts) or ('candidate', entry)."""
        active = osm_facts(self.lay.build_info(active_slot)) if active_slot else None
        today = dt.datetime.now(UB).date().isoformat()
        last_failure = None
        validation_failed = False
        for src in self.cfg.sources:
            for attempt in (1, 2):
                t0 = time.monotonic()
                try:
                    return self.try_source(src, st, active, force, overrides, today)
                except SourceError as e:
                    validation_failed = validation_failed or e.check not in ("download", "md5")
                    last_failure = e
                    self.log.step("fetch" if e.check in ("download", "md5") else "validate", "failed", time.monotonic() - t0,
                                  source=src, attempt=attempt, check=e.check, measured=e.measured)
                    if attempt == 1:
                        self.log.event("info", "retrying the same source", source=src,
                                       delay_s=self.cfg.retry_delay_s)
                        self.sleep(self.cfg.retry_delay_s)
            self.log.event("warn", "source failed twice; trying the next one" if src != self.cfg.sources[-1]
                           else "source failed twice; no more sources", source=src)
        if validation_failed:
            raise Outcome(20, "failed", "validate", f"every source failed; last: {last_failure.check}: {last_failure.measured}")
        raise Outcome(13, "skipped: no valid source", "fetch",
                      f"every source failed; last: {last_failure.measured if last_failure else 'none configured'}")

    def try_source(self, src, st, active, force, overrides, today):
        lay = self.lay
        t0 = time.monotonic()
        part = lay.cache_osm / ".incoming.osm.pbf.part"
        with contextlib.suppress(FileNotFoundError):
            part.unlink()
        meta = {}
        if src.startswith("file:"):
            path = Path(src[5:])
            if not path.is_file() or path.stat().st_size == 0:
                raise SourceError("download", f"local file missing or empty: {path}")
            sidecar = path.with_name(path.name + ".last-modified")
            meta["last_modified"] = (sidecar.read_text().strip() if sidecar.is_file() else
                                     dt.datetime.fromtimestamp(path.stat().st_mtime, dt.timezone.utc).strftime("%a, %d %b %Y %H:%M:%S GMT"))
            sha = sha256_file(path)
            cached = lay.cache_osm / f"{sha}.osm.pbf"
            if not cached.exists():
                shutil.copyfile(path, part)
                if sha256_file(part) != sha:
                    raise SourceError("download", "local file changed while it was copied")
                os.rename(part, cached)
            meta["source_key"] = f"file:{path}|{path.stat().st_size}|{int(path.stat().st_mtime)}"
            meta["md5"] = sha256_file(cached, "md5")
            how = "local file"
        else:
            url = src
            md5_remote = self.remote_md5(url)
            if active and not force and md5_remote and md5_remote == active.get("md5"):
                self.log.step("fetch", "ok", time.monotonic() - t0, source=url, md5=md5_remote,
                              note="remote .md5 equals the active slot's extract; nothing downloaded")
                return "unchanged", {"sha256": active.get("sha256"), "source": url}
            ld = st.get("last_download") or {}
            cached = lay.cache_osm / f"{ld.get('sha256')}.osm.pbf" if ld.get("sha256") else None
            # FORCE=1 only overrides "unchanged" / "rolled back"; it never downloads twice a day (AC 1).
            if ld.get("date_ub") == today and ld.get("url") == url and cached and cached.exists():
                sha = ld["sha256"]
                meta.update(last_modified=ld.get("last_modified"), md5=ld.get("md5"), source_key=f"url:{url}")
                how = "today's download reused (at most one download per Asia/Ulaanbaatar day)"
            else:
                lm = self.download(url, part)
                md5 = sha256_file(part, "md5")
                if md5_remote and md5 != md5_remote:
                    again = self.remote_md5(url)
                    if again != md5:
                        part.unlink()
                        raise SourceError("md5", f"downloaded md5 {md5} != {url}.md5 ({md5_remote}, re-read {again})")
                sha = sha256_file(part)
                cached = lay.cache_osm / f"{sha}.osm.pbf"
                os.rename(part, cached)
                meta.update(last_modified=lm, md5=md5, source_key=f"url:{url}", downloaded=True)
                how = "downloaded"
        self.log.step("fetch", "ok", time.monotonic() - t0, source=src, how=how, sha256=sha,
                      bytes=cached.stat().st_size)
        if not force and active and sha == active.get("sha256"):
            return "unchanged", {"sha256": sha, "source": src}
        if not force and sha in st.get("rolled_back_sha256", []):
            raise Outcome(14, "skipped: source was rolled back", "validate",
                          f"extract {sha[:12]} was rolled back earlier; a newer extract or FORCE=1 is needed")
        t1 = time.monotonic()
        info_file = lay.cache_osm / f"{sha}.json"
        meta["pbf"] = read_json(info_file)      # 3(a) result cached per sha256 (same bytes, same result)
        info, facts = self.validate(cached, meta, active, overrides)
        if not info_file.exists():
            atomic_write(info_file, json.dumps(info, ensure_ascii=False) + "\n")
        entry = {"sha256": sha, "path": str(cached), "md5": meta.get("md5"), "last_modified": meta.get("last_modified"),
                 "source": src, "source_key": meta["source_key"], **facts}
        atomic_write(lay.cache_osm / f"{sha}.meta.json", json.dumps(entry, ensure_ascii=False, indent=1) + "\n")
        self.log.step("validate", "ok", time.monotonic() - t1, source=src, sha256=sha, bytes=facts["bytes"],
                      data_date=facts["data_date"], data_date_source=facts["data_date_source"],
                      age_hours=facts["age_hours"], blocks=facts.get("blocks"),
                      checks=self.checks_passed(active, facts),
                      first_build=facts.get("first_build"))
        if meta.get("downloaded"):
            st["last_download"] = {"date_ub": today, "url": src, "sha256": sha, "md5": meta.get("md5"),
                                   "last_modified": meta.get("last_modified"), "at": iso()}
            save_state(lay, st)
        if sha == (active or {}).get("sha256"):
            entry["forced_same_extract"] = True
        return "candidate", entry

    # ------------------------------------------------------------------ Photon dump (AC 8)
    def resolve_dump(self, st, active_slot):
        lay = self.lay
        today = dt.datetime.now(UB).date().isoformat()
        rec = st.get("photon_dump") or {}
        active_dump = ((self.lay.build_info(active_slot) or {}).get("photon_dump") or {}) if active_slot else {}
        t0 = time.monotonic()
        try:
            if self.cfg.dump_file:
                path = Path(self.cfg.dump_file)
                if not path.is_file() or path.stat().st_size == 0:
                    raise SourceError("download", f"PHOTON_DUMP_FILE missing or empty: {path}")
                sha = sha256_file(path)
                dest = lay.cache_dump / f"{sha}.jsonl.zst"
                if not dest.exists():
                    tmp = lay.cache_dump / ".incoming.part"
                    shutil.copyfile(path, tmp)
                    os.rename(tmp, dest)
                side = path.with_name(path.name + ".last-modified")
                lm = side.read_text().strip() if side.is_file() else None
                rec = {"source_key": f"file:{path}|{path.stat().st_size}|{int(path.stat().st_mtime)}", "sha256": sha,
                       "last_modified": lm, "checked_date_ub": today}
                how = "local file"
            else:
                url = self.cfg.dump_url
                cached = lay.cache_dump / f"{rec.get('sha256')}.jsonl.zst" if rec.get("sha256") else None
                if rec.get("url") == url and rec.get("checked_date_ub") == today and cached and cached.exists():
                    how = "checked today already; cached dump reused"
                else:
                    r = Proc.run(self.curl_base() + ["-m", "60", "-I", url], timeout=90)
                    heads = {k.strip().lower(): v.strip() for k, _, v in
                             (ln.partition(":") for ln in r.stdout.splitlines() if ":" in ln)}
                    lm, length = heads.get("last-modified"), heads.get("content-length")
                    if r.returncode == 0 and cached and cached.exists() and rec.get("url") == url and \
                            lm == rec.get("last_modified") and length == rec.get("content_length"):
                        how = "unchanged (same Last-Modified and size); no download"
                        rec["checked_date_ub"] = today
                    else:
                        tmp = lay.cache_dump / ".incoming.part"
                        lm = self.download(url, tmp) or lm
                        sha = sha256_file(tmp)
                        dest = lay.cache_dump / f"{sha}.jsonl.zst"
                        os.rename(tmp, dest)
                        rec = {"url": url, "source_key": f"url:{url}", "sha256": sha, "last_modified": lm,
                               "content_length": str(dest.stat().st_size), "checked_date_ub": today,
                               "downloaded_date_ub": today}
                        how = "downloaded"
            st["photon_dump"] = rec
            save_state(lay, st)
            self.log.step("photon_dump", "ok", time.monotonic() - t0, how=how, sha256=rec["sha256"])
            return dict(rec, reused=None)
        except SourceError as e:
            sha = active_dump.get("sha256")
            if sha and (lay.cache_dump / f"{sha}.jsonl.zst").exists():
                why = f"dump download failed ({e.measured}); the active slot's dump and index are reused " \
                      f"(data_timestamp {active_dump.get('data_timestamp')})"
                self.log.step("photon_dump", "ok", time.monotonic() - t0, how="reused active", reason=why)
                src = active_dump.get("source") or ""
                return {"sha256": sha, "source_key": src if src.startswith("file:") else f"url:{src}",
                        "last_modified": active_dump.get("http_last_modified"), "reused": why}
            raise Outcome(13, "skipped: no valid source", "photon_dump", f"no Photon dump: {e.measured}") from None

    # ------------------------------------------------------------------ build (section B)
    def set_aux_aside(self):
        """--refresh-aux (NAV-008 AC 15 'empty auxiliary cache'): download every auxiliary file and tool again.
        The old cache is renamed to *.previous (slots keep their hard links) and restored if the run fails."""
        for d in (self.lay.cache_sources, self.lay.cache_tools):
            prev = d.with_name(d.name + ".previous")
            if prev.exists():
                raise Outcome(2, "failed", "aux_cache", f"{prev} exists from an earlier run; the next run restores it")
            os.rename(d, prev)
            d.mkdir()
        self.aux_aside = True
        self.log.event("info", "auxiliary cache set aside; every auxiliary file and tool is downloaded again")

    def restore_aux_aside(self, keep_new):
        for d in (self.lay.cache_sources, self.lay.cache_tools):
            prev = d.with_name(d.name + ".previous")
            if not prev.exists():
                continue
            if keep_new:
                rmtree(prev)
            else:
                rmtree(d)
                os.rename(prev, d)
        self.log.event("info", "fresh auxiliary cache kept; old copy deleted" if keep_new else
                       "auxiliary cache restored from the copy set aside")
        self.aux_aside = False

    def ensure_aux_cache(self):
        lay = self.lay
        missing = [f for f in AUX_FILES if not (lay.cache_sources / f).is_file()]
        missing += [f for f in ("photon.jar", "aircompressor.jar") if not (lay.cache_tools / f).is_file()]
        if not list(lay.cache_tools.glob("protomaps-basemap-*.jar")) and \
                not (list(lay.cache_tools.glob("protomaps-basemaps-*.tar.gz")) and (lay.cache_tools / "maven-bin.tar.gz").is_file()):
            missing.append("protomaps jar or source+maven")
        if not missing:
            return "present"
        self.log.event("info", "auxiliary cache incomplete; fetching", missing=",".join(missing))
        with open(lay.runs / f"{self.run_id}.aux-fetch.log", "w") as f:
            r = self.compose("run", "--rm", "-T", "--no-deps", "--name", f"{self.cfg.project}-aux-fetch-{self.run_id.lower()}",
                             "aux-fetch", timeout=None, out=f)
        if r.returncode != 0:
            raise Outcome(21, "failed", "build", f"aux-fetch exited {r.returncode} (see runs/{self.run_id}.aux-fetch.log)")
        return "fetched"

    def populate_slot(self, part, osm, dump):
        lay = self.lay
        (part / "sources").mkdir(parents=True)
        (part / "tools").mkdir()
        for f in lay.cache_sources.iterdir():   # aux files + sidecars: hard links, 0 extra bytes
            if f.is_file() and not f.name.startswith(("osm.pbf", "photon-dump")):
                os.link(f, part / "sources" / f.name)
        for f in lay.cache_tools.iterdir():
            if f.is_file():
                os.link(f, part / "tools" / f.name)
        src = part / "sources"
        os.link(osm["path"], src / "osm.pbf")
        info = read_json(lay.cache_osm / f"{osm['sha256']}.json", {})
        bbox = info.get("bbox")
        side = {"osm.pbf.sha256": osm["sha256"], "osm.pbf.md5": osm.get("md5"), "osm.pbf.source": osm["source_key"],
                "osm.pbf.json": json.dumps(info, ensure_ascii=False),
                "osm.pbf.bounds": ",".join(str(x) for x in bbox) if bbox and None not in bbox else "",
                "osm.pbf.last-modified": osm.get("last_modified"),
                "photon-dump.sha256": dump["sha256"], "photon-dump.source": dump["source_key"],
                "photon-dump.last-modified": dump.get("last_modified"), "photon-dump.reused": dump.get("reused")}
        for name, val in side.items():
            if val:
                (src / name).write_text(str(val) + "\n")
        os.link(lay.cache_dump / f"{dump['sha256']}.jsonl.zst", src / "photon-dump")
        # Photon index: a copy of the pristine imported index when the dump is unchanged (no re-import)
        pristine = sorted(lay.cache_index.glob(f"{dump['sha256']}-*"), key=lambda p: p.stat().st_mtime)
        if pristine and (pristine[-1] / "photon" / ".complete").is_file():
            r = Proc.run(["cp", "-a", "--reflink=auto", pristine[-1] / "photon", part / "photon"], timeout=600)
            if r.returncode != 0:
                raise Outcome(21, "failed", "build", f"copying the pristine Photon index failed: {r.stderr[-200:]}")
            return pristine[-1].name
        return None

    def disk_watch(self):
        while not self.watch_stop.wait(5):
            if free_gib(self.lay.root) < self.cfg.floor_free_gb:
                self.disk_low.set()
                return

    def kill_builders(self):
        if self.builders:
            self.docker("rm", "-f", *self.builders, timeout=120)
        self.builders = []

    def build(self, osm, dump):
        lay = self.lay
        slot_id = self.run_id
        part = lay.slots / f"{slot_id}.partial"
        if (lay.slots / slot_id).exists() or part.exists():
            raise Outcome(21, "failed", "build", f"slot {slot_id} already exists")
        self.candidate = slot_id
        self.phase = "build"
        part.mkdir()
        os.chmod(part, 0o755)
        reused_index = self.populate_slot(part, osm, dump)
        symlink_atomic(lay.lanes / "build", f"../slots/{slot_id}.partial")
        self.log.event("info", "slot prepared", slot=f"{slot_id}.partial", photon_index="copied from cache "
                       f"{reused_index} (no re-import)" if reused_index else "import")
        self.disk_low.clear()
        self.watch_stop.clear()
        threading.Thread(target=self.disk_watch, daemon=True).start()
        try:
            procs = {}
            for svc in ("tiles-build", "valhalla-build", "photon-import"):
                name = f"{self.cfg.project}-{svc}-{slot_id.lower()}"
                cmd = ["run", "--rm", "-T", "--no-deps", "--name", name]
                if svc == "tiles-build" and self.cfg.test_fault == "build":
                    cmd += ["--entrypoint", "/bin/sh", svc, "-c",
                            "echo 'injected test fault (REBUILD_TEST_FAULT=build)' >&2; exit 1"]
                else:
                    cmd += [svc]
                log = open(lay.runs / f"{self.run_id}.{svc}.log", "w")
                base = ["docker", "compose", "-p", self.cfg.project, "--project-directory", str(BACKEND),
                        "-f", str(COMPOSE_FILE)] + sum((["-f", o] for o in self.cfg.overlays), []) + \
                       ["--env-file", str(self.cfg.env_file)]
                procs[svc] = (Proc.start(base + cmd, log), time.monotonic(), log)
                self.builders.append(name)
            durations, failed = {}, []
            while procs:
                if self.disk_low.is_set():
                    self.kill_builders()
                    for p, _, lf in procs.values():
                        Proc.kill(p)
                        lf.close()
                    raise Outcome(21, "failed", "build", f"free disk fell below the hard floor "
                                  f"{self.cfg.floor_free_gb:g} GB during the build ({free_gib(lay.root):.1f} GB)")
                for svc, (p, t0, lf) in list(procs.items()):
                    if p.poll() is not None:
                        Proc.live.discard(p)
                        lf.close()
                        durations[svc] = round(time.monotonic() - t0, 1)
                        if p.returncode != 0:
                            failed.append(f"{svc} exit {p.returncode}")
                        del procs[svc]
                        self.log.event("info" if p.returncode == 0 else "error", "builder finished", builder=svc,
                                       exit_code=p.returncode, duration_s=durations[svc])
                time.sleep(1)
            self.facts["builder_seconds"] = durations
            if failed:
                raise Outcome(21, "failed", "build", "; ".join(failed) + f" (logs: runs/{self.run_id}.<builder>.log)")
            t0 = time.monotonic()
            with open(lay.runs / f"{self.run_id}.build-info.log", "w") as lf:
                r = self.compose("run", "--rm", "-T", "--no-deps", "--name", f"{self.cfg.project}-build-info-{slot_id.lower()}",
                                 "-e", f"NAV_SLOT_ID={slot_id}", "-e", f"NAV_RUN_ID={self.run_id}", "build-info",
                                 timeout=300, out=lf)
            if r.returncode != 0 or not (part / "build-info.json").is_file():
                raise Outcome(21, "failed", "build", f"build-info exited {r.returncode}")
            durations["build-info"] = round(time.monotonic() - t0, 1)
            self.builders = []
        finally:
            self.watch_stop.set()
        # harvest: tools built in the slot (Protomaps jar) and the pristine Photon index go to the cache
        for f in (part / "tools").iterdir():
            if f.is_file() and not (lay.cache_tools / f.name).exists():
                os.link(f, lay.cache_tools / f.name)
                self.log.event("info", "tool added to the cache", tool=f.name)
        marker = (part / "photon" / ".complete").read_text().splitlines()[0]
        key = f"{dump['sha256']}-{hashlib.sha256(marker.encode()).hexdigest()[:12]}"
        if not (lay.cache_index / key).exists():
            tmp = lay.cache_index / f".{key}.tmp"
            rmtree(tmp)
            tmp.mkdir()
            r = Proc.run(["cp", "-a", "--reflink=auto", part / "photon", tmp / "photon"], timeout=600)
            if r.returncode != 0:
                raise Outcome(21, "failed", "build", f"saving the pristine Photon index failed: {r.stderr[-200:]}")
            os.rename(tmp, lay.cache_index / key)
        # ETag continuity (ADR-0014 §5 rule 2): a different archive must never reuse the active (mtime, size)
        active = self.lay.pointer()
        new_tiles = part / "tiles" / "basemap.pmtiles"
        if active:
            old_tiles = lay.slot(active["slot"]) / "tiles" / "basemap.pmtiles"
            with contextlib.suppress(FileNotFoundError):
                a, b = old_tiles.stat(), new_tiles.stat()
                if int(a.st_mtime) == int(b.st_mtime) and a.st_size == b.st_size:
                    os.utime(new_tiles, (time.time() + 2, time.time() + 2))
                    self.log.event("info", "new archive touched so that its ETag differs from the active one")
        complete = json.dumps({"slot": slot_id, "run_id": self.run_id, "completed_at": iso()}) + "\n"
        atomic_write(part / ".slot-complete", complete)
        with contextlib.suppress(FileNotFoundError):
            os.unlink(lay.lanes / "build")
        os.rename(part, lay.slot(slot_id))
        fsync_dir(lay.slots)
        self.phase = "built"
        return durations, reused_index

    # ------------------------------------------------------------------ verify (section C)
    def http_json(self, base, method, path, body=None, timeout=15):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(base + path, data=data, method=method,
                                     headers={"Content-Type": "application/json"} if data else {})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, json.loads(r.read() or b"null")
        except urllib.error.HTTPError as e:
            return e.code, None
        except (urllib.error.URLError, OSError, ValueError):
            return 0, None

    def route_distance(self, base, a, b, costing, extra=None):
        body = {"locations": [{"lat": REF_POINTS[a][0], "lon": REF_POINTS[a][1]},
                              {"lat": REF_POINTS[b][0], "lon": REF_POINTS[b][1]}],
                "costing": costing, "format": "osrm", "language": "mn-MN", "units": "kilometers"}
        body.update(extra or {})
        code, doc = self.http_json(base, "POST", "/v1/route", body)
        try:
            return code, float(doc["routes"][0]["distance"])
        except (TypeError, KeyError, IndexError, ValueError):
            return code, None

    def reference_routes(self, overrides):
        checks = [("P1->P3 auto mn-MN", "P1", "P3", "auto", None, True),
                  ("P1->P2 pedestrian", "P1", "P2", "pedestrian", None, True),
                  ("P1->P6 auto exclude_unpaved", "P1", "P6", "auto",
                   {"costing_options": {"auto": {"exclude_unpaved": True}}}, False)]
        out, bad = [], []
        for label, a, b, costing, extra, required in checks:
            cn, dn = self.route_distance(self.cfg.verify_url, a, b, costing, extra)
            co, do = self.route_distance(self.cfg.public_url, a, b, costing, extra)
            rec = {"route": label, "new_m": dn, "active_m": do, "new_status": cn, "active_status": co}
            if dn is None or do is None:
                if required and dn is None:
                    bad.append(f"{label}: no route on the new slot (HTTP {cn})")
                if required and do is None:
                    # The live (active) gateway gave an error or no route: AC 12 cannot compare this route, so a
                    # damaged new slot could pass it on the smoke alone. Not a failure of the new slot (QA F4).
                    self.log.event("warn", "reference route not compared: the live gateway gave no route",
                                   route=label, live_status=co, new_status=cn)
                rec["compared"] = False
            else:
                dev = abs(dn - do) / do if do else 0.0
                rec.update(compared=True, deviation=round(dev, 3))
                if dev > self.cfg.route_dev:
                    bad.append(f"{label}: {dn:.0f} m vs active {do:.0f} m ({dev:.0%} > {self.cfg.route_dev:.0%})")
            out.append(rec)
        if bad and overrides.get("accept_route_change"):
            self.log.event("warn", "override ACCEPT_ROUTE_CHANGE: reference route deviation accepted", problems=bad)
            bad = []
        return out, bad

    @staticmethod
    def artefact_sizes(slot_dir):
        tiles = slot_dir / "tiles" / "basemap.pmtiles"
        return {"pmtiles": tiles.stat().st_size if tiles.exists() else 0,
                "valhalla": dir_usage(slot_dir / "valhalla")[0],
                "photon": dir_usage(slot_dir / "photon", exclude=(os.path.join("photon_data", "node_1", "logs"),))[0]}

    def verify(self, slot_id, active_ptr, overrides):
        lay = self.lay
        self.phase = "verify"
        self.free_lane = lane = ("green" if active_ptr["lane"] == "blue" else "blue") if active_ptr else "blue"
        first = active_ptr is None
        new_sizes = self.artefact_sizes(lay.slot(slot_id))
        t0 = time.monotonic()
        ok, msg = self.start_lane(lane, slot_id, recreate=True, timeout=self.cfg.lane_timeout_s)
        lane_s = round(time.monotonic() - t0, 1)
        if not ok:
            raise Outcome(22, "failed", "verify", f"lane {lane} did not become healthy on the new slot: {msg}")
        self.log.event("info", "candidate lane healthy", lane=lane, slot=slot_id, start_s=lane_s)
        if self.cfg.test_fault == "verify":
            self.compose("stop", f"valhalla-{lane}", timeout=60)
            self.log.event("warn", "test fault: candidate Valhalla stopped before the checks (REBUILD_TEST_FAULT=verify)")
        write_pointer(lay.ptr_verify, slot_id, lane)
        r = self.compose("up", "-d", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "60",
                         "gateway-verify", timeout=120)
        if r.returncode != 0:
            raise Outcome(22, "failed", "verify", f"gateway-verify did not start: {(r.stderr or '').strip()[-200:]}")
        problems, details = [], {"lane": lane, "lane_start_s": lane_s}
        rc, tail, _ = self.smoke(self.cfg.verify_url, "verify-smoke")
        details["smoke"] = f"exit {rc}: {tail}"
        if rc != 0:
            problems.append(f"smoke.py exit {rc} ({tail}; runs/{self.run_id}.verify-smoke.txt)")
        rc, tail, _ = self.contract(self.cfg.verify_url, "verify-contract")
        details["contract"] = f"exit {rc}: {tail}"
        if rc != 0:
            problems.append(f"contract_check.py exit {rc} ({tail}; runs/{self.run_id}.verify-contract.txt)")
        if first:
            details["reference_routes"] = details["artefact_sizes"] = "skipped: first build"
        else:
            routes, bad = self.reference_routes(overrides)
            details["reference_routes"] = routes
            problems += bad
            old_sizes = self.artefact_sizes(lay.slot(active_ptr["slot"]))
            ratios = {k: round(new_sizes[k] / old_sizes[k], 3) if old_sizes[k] else None for k in new_sizes}
            details["artefact_sizes"] = {"new": new_sizes, "active": old_sizes, "ratio": ratios}
            small = [f"{k} {new_sizes[k]} B = {v:.0%} of active {old_sizes[k]} B" for k, v in ratios.items()
                     if v is not None and v < self.cfg.artefact_ratio]
            if small and overrides.get("accept_size_drop"):
                self.log.event("warn", "override ACCEPT_SIZE_DROP: artefact size check (AC 13) accepted", problems=small)
            elif small:
                problems += small
        self.compose("rm", "-s", "-f", "gateway-verify", timeout=60)
        with contextlib.suppress(FileNotFoundError):
            lay.ptr_verify.unlink()
        if problems:
            raise Outcome(22, "failed", "verify", " | ".join(problems))
        return details

    def fail_candidate(self):
        """After a failed build/verification or an interrupt: the candidate never becomes a target."""
        lay = self.lay
        if self.free_lane and self.phase in ("verify",):
            self.stop_lane(self.free_lane, timeout=40)
        if self.container_state("gateway-verify") != "absent":
            self.compose("rm", "-s", "-f", "gateway-verify", timeout=40)
        with contextlib.suppress(FileNotFoundError):
            lay.ptr_verify.unlink()
        with contextlib.suppress(FileNotFoundError):
            os.unlink(lay.lanes / "build")
        if not self.candidate:
            return
        part = lay.slots / f"{self.candidate}.partial"
        if part.exists():
            rmtree(part)
            self.log.event("info", "partial slot deleted", slot=part.name)
        full = lay.slot(self.candidate)
        if full.exists():
            os.rename(full, lay.slots / f"{self.candidate}.failed")
            self.log.event("info", "slot marked failed (kept for diagnosis until the next run)",
                           slot=f"{self.candidate}.failed")

    # ------------------------------------------------------------------ switch, post-switch, grace, cleanup
    def switch(self, slot_id, lane, old_ptr):
        with self.critical():
            write_pointer(self.lay.ptr_public, slot_id, lane)
            switched_at = iso()
            st = load_state(self.lay)
            st["previous"] = old_ptr["slot"] if old_ptr else None
            st["active"] = slot_id
            if old_ptr:
                # Cleared when the post-switch smoke passes or the switch is rolled back. If the run ends before
                # that (SIGTERM, kill -9), the old lane keeps running and the next run's reconciliation runs the
                # check before it stops that lane, or rolls back (QA F5).
                st["post_switch_pending"] = {"slot": slot_id, "lane": lane, "previous_slot": old_ptr["slot"],
                                             "previous_lane": old_ptr["lane"], "switched_at": switched_at,
                                             "run_id": self.run_id}
            save_state(self.lay, st)
            self.phase = "switched"
        return switched_at

    def clear_post_switch_pending(self):
        with self.critical():
            st = load_state(self.lay)
            if st.pop("post_switch_pending", None) is not None:
                save_state(self.lay, st)

    def check_pending_post_switch(self, st, ptr):
        """Reconciliation: a switch whose post-switch check never finished (interrupted during the smoke). Runs
        smoke.py through the public URL while the old lane still runs; on failure rolls back to that lane."""
        pend = st.get("post_switch_pending")
        if not pend:
            return st
        if not ptr or (ptr["slot"], ptr["lane"]) != (pend.get("slot"), pend.get("lane")):
            self.log.event("warn", "post-switch check pending for a slot that no longer serves; dropped",
                           pending_slot=pend.get("slot"), active_slot=ptr["slot"] if ptr else None)
            st.pop("post_switch_pending", None)
            save_state(self.lay, st)
            return st
        rc, tail, _ = self.smoke(self.cfg.smoke_url, "pending-post-switch-smoke")
        if rc == 0:
            self.log.event("info", "pending post-switch check passed; the old lane may stop", slot=pend["slot"],
                           smoke=tail, base_url=self.cfg.smoke_url)
            self.clear_post_switch_pending()
            return load_state(self.lay)
        reason = (f"pending post-switch check of {pend['slot']}: smoke.py against {self.cfg.smoke_url} "
                  f"exit {rc}: {tail}")
        prev, prev_lane = pend.get("previous_slot"), pend.get("previous_lane")
        if not prev or prev != st.get("previous") or not self.lay.complete(prev) or prev_lane not in LANES:
            self.clear_post_switch_pending()
            raise Outcome(23, "failed", "reconcile", reason + f"; previous slot {prev} is not available, "
                                                              f"nothing to roll back to")
        mounted = self.lane_mounted_slot(prev_lane)
        ok, msg = self.start_lane(prev_lane, prev, recreate=mounted != prev, timeout=self.cfg.rollback_timeout_s)
        if not ok:
            self.stop_lane(prev_lane)
            self.clear_post_switch_pending()
            raise Outcome(23, "failed", "reconcile", reason + f"; lane {prev_lane} did not become healthy on {prev}: "
                                                              f"{msg}; the new slot keeps serving")
        self.phase = "rolling_back"
        self.rollback_after_switch(pend["slot"], pend["lane"], {"slot": prev, "lane": prev_lane}, reason)
        raise Outcome(24, "rolled back", "reconcile", reason)

    def rollback_after_switch(self, new_slot, new_lane, old_ptr, reason):
        with self.critical():
            write_pointer(self.lay.ptr_public, old_ptr["slot"], old_ptr["lane"])
            st = load_state(self.lay)
            st["active"], st["previous"] = old_ptr["slot"], None
            st.pop("post_switch_pending", None)
            st["rolled_back"].append(new_slot)
            sha = osm_facts(self.lay.build_info(new_slot)).get("sha256")
            if sha:
                st["rolled_back_sha256"].append(sha)
            save_state(self.lay, st)
        self.log.event("error", "post-switch check failed; pointer renamed back to the previous slot",
                       slot=old_ptr["slot"], rolled_back=new_slot, reason=reason)
        self.sleep(self.cfg.grace_s)
        self.stop_lane(new_lane)

    def cleanup(self):
        lay = self.lay
        st = load_state(lay)
        keep = [s for s in (st.get("active"), st.get("previous")) if s]
        for d in sorted(lay.slots.iterdir()):
            if d.name not in keep:
                rmtree(d)
                self.log.event("info", "deleted slot", slot=d.name)
        infos = [lay.build_info(s) for s in keep]
        osm_keep = {osm_facts(i).get("sha256") for i in infos} | {(st.get("last_download") or {}).get("sha256")}
        dump_keep = {((i or {}).get("photon_dump") or {}).get("sha256") for i in infos} | \
                    {(st.get("photon_dump") or {}).get("sha256")}
        for f in lay.cache_osm.iterdir():
            if f.name.split(".", 1)[0] not in osm_keep:
                f.unlink()
        for f in lay.cache_dump.iterdir():
            if f.name.split(".", 1)[0] not in dump_keep:
                f.unlink()
        for d in lay.cache_index.iterdir():
            if d.name.split("-", 1)[0] not in dump_keep:
                rmtree(d)
        usage = {}
        for s in keep:
            total, unique = dir_usage(lay.slot(s))
            usage[s] = {"apparent_bytes": total, "own_disk_bytes": unique}
        cache_total, _ = dir_usage(lay.cache)
        return {"slots_kept": keep, "slot_usage": usage, "cache_apparent_bytes": cache_total,
                "free_disk_gb": round(free_gib(lay.root), 2)}

    def prune_logs(self):
        cutoff = time.time() - self.cfg.retention_days * 86400
        for f in self.lay.runs.iterdir():
            with contextlib.suppress(FileNotFoundError):
                if f.stat().st_mtime < cutoff:
                    f.unlink()

    def data_stale(self, slot_id):
        d = parse_iso(osm_facts(self.lay.build_info(slot_id)).get("date")) if slot_id else None
        if not d:
            return bool(slot_id)
        return (utc_now() - d).total_seconds() / 3600 > self.cfg.stale_h

    # ------------------------------------------------------------------ commands
    def begin(self, assume_locked=False):
        self.lay.root.mkdir(parents=True, exist_ok=True)
        self.lay.ensure()
        self.acquire_lock(assume_locked)
        self.run_id = utc_now().strftime("%Y%m%dT%H%M%SZ")
        while (self.lay.runs / f"{self.run_id}.jsonl").exists():   # two commands within one second
            time.sleep(1)
            self.run_id = utc_now().strftime("%Y%m%dT%H%M%SZ")
        self.log = RunLog(self.lay, self.run_id, self.kind)
        signal.signal(signal.SIGTERM, self.on_signal)
        signal.signal(signal.SIGINT, self.on_signal)
        self.started_at = iso()
        self.t_start = time.monotonic()

    def finish(self, code, result, step=None, reason=None):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        st = load_state(self.lay)
        rec = {"run_id": self.run_id, "kind": self.kind, "started_at": self.started_at, "ended_at": iso(),
               "result": result, "step": step, "reason": reason, "exit_code": code}
        st["last_run"] = rec
        if code == 0 and result in ("success", "rolled back"):
            st["last_success"] = rec
        save_state(self.lay, st)
        ptr = self.lay.pointer()
        stale = self.data_stale(ptr["slot"]) if ptr else None
        self.log.emit({"ts": iso(), "job": "nav-pipeline", "run_id": self.run_id, "kind": self.kind,
                       "step": "summary", "result": result, "exit_code": code, "failed_step": step, "reason": reason,
                       "duration_s": round(time.monotonic() - self.t_start, 1),
                       "active_slot": ptr["slot"] if ptr else None, "stale": stale, **self.facts})
        if code in ALERT_CODES or (stale and self.kind == "rebuild"):
            self.alert(code, result if not (stale and code not in ALERT_CODES) else f"{result} (active data stale)",
                       step, reason or ("active OSM data older than the stale limit" if stale else None))
        self.prune_logs()
        self.log.close()
        return code

    def rebuild(self, force=False, accept_size_drop=False, accept_route_change=False, scheduled=False,
                assume_locked=False, refresh_aux=False):
        if scheduled and (force or accept_size_drop or accept_route_change or refresh_aux):
            raise Outcome(2, "failed", "config", "overrides (FORCE, ACCEPT_SIZE_DROP, ACCEPT_ROUTE_CHANGE, REFRESH_AUX) "
                                                 "are refused on the scheduled entry point; use make rebuild")
        self.begin(assume_locked)
        overrides = {"force": force or refresh_aux, "accept_size_drop": accept_size_drop,
                     "accept_route_change": accept_route_change, "refresh_aux": refresh_aux}
        try:
            return self._rebuild(overrides)
        except Outcome as o:
            return self.handle_failure(o)
        except Exception as e:  # noqa: BLE001 - a bug or an OS error still cleans up and records the run
            step = self.step_name or "unknown"
            code = {"fetch": 20, "validate": 20, "photon_dump": 21, "aux_cache": 21, "build": 21, "verify": 22,
                    "switch": 23, "post_switch": 23, "grace": 23, "cleanup": 23}.get(step, 21)
            return self.handle_failure(Outcome(code, "failed", step, f"unexpected {type(e).__name__}: {e}"))

    def handle_failure(self, o):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        Proc.kill_all()
        if self.aux_aside:
            with contextlib.suppress(Exception):
                self.restore_aux_aside(keep_new=False)
        if self.phase in ("build", "built", "verify"):
            with contextlib.suppress(Exception):
                self.kill_builders()
            try:
                self.fail_candidate()
            except Exception as e:  # noqa: BLE001
                self.log.event("error", "cleanup after the failure was incomplete; the next run reconciles",
                               error=f"{type(e).__name__}: {e}")
        pend = load_state(self.lay).get("post_switch_pending") if self.phase == "switched" else None
        if pend and pend.get("slot") == self.run_id:
            # Ended between the switch and the end of the post-switch smoke: the old lane is NOT stopped (QA F5).
            o.reason = (f"{o.reason or o.result}; post-switch check pending: lane {pend['previous_lane']} "
                        f"({pend['previous_slot']}) keeps running, the next run checks {pend['slot']} with smoke.py "
                        f"before it stops that lane and rolls back on failure")
        self.log.event("error" if o.code not in (0, 10) else "info", o.reason or o.result, result=o.result,
                       step=o.step, exit_code=o.code)
        return self.finish(o.code, o.result, o.step, o.reason)

    def _rebuild(self, ov):
        cfg, lay = self.cfg, self.lay
        with self.step("config") as s:
            s.update(project=cfg.project, data_root=str(cfg.root), sources=cfg.sources,
                     overrides=[k for k, v in ov.items() if v] or None, relaxed=cfg.relaxations() or None,
                     gateway_port=cfg.gateway_port, verify_port=cfg.verify_port)
            self.isolation_guard()
        with self.step("reconcile"):
            st = self.reconcile()
            self.ensure_gateway()
        with self.step("guards") as s:
            free, mem = free_gib(lay.root), mem_available_gib()
            s.update(free_disk_gb=round(free, 2), min_free_gb=cfg.min_free_gb,
                     mem_available_gb=round(mem, 2), min_mem_available_gb=cfg.min_mem_gb)
            if free < cfg.min_free_gb:
                raise Outcome(11, "skipped: low disk", "guards",
                              f"free disk {free:.1f} GB < required {cfg.min_free_gb:g} GB on {cfg.root}")
            if mem < cfg.min_mem_gb:
                raise Outcome(12, "skipped: low memory", "guards",
                              f"MemAvailable {mem:.1f} GB < required {cfg.min_mem_gb:g} GB")
        active_ptr = lay.pointer()
        if active_ptr and not lay.complete(active_ptr["slot"]):
            self.log.event("error", "the active pointer names a slot that is not complete", slot=active_ptr["slot"])
        active_slot = active_ptr["slot"] if active_ptr else None
        kind, osm = self.resolve_osm(st, active_slot, ov["force"], ov)
        if kind == "unchanged":
            return self.unchanged(active_slot, osm)
        self.facts["osm"] = {"sha256": osm["sha256"], "bytes": osm["bytes"], "data_date": osm["data_date"],
                             "source": osm["source"]}
        dump = self.resolve_dump(st, active_slot)
        with self.step("aux_cache") as s:
            if ov.get("refresh_aux"):
                self.set_aux_aside()
            s["cache"] = self.ensure_aux_cache()
        with self.step("build") as s:
            durations, reused = self.build(osm, dump)
            s.update(slot=self.run_id, builder_seconds=durations,
                     photon="index copied from the cache (dump unchanged, no re-import)" if reused else "imported",
                     free_disk_gb=round(free_gib(lay.root), 2))
        with self.step("verify") as s:
            s.update(self.verify(self.run_id, active_ptr, ov))
        new_lane = self.free_lane
        if cfg.test_fault == "post_switch" and not active_ptr:
            raise Outcome(2, "failed", "config", "REBUILD_TEST_FAULT=post_switch needs an active slot to roll back to")
        with self.step("switch") as s:
            s["switched_at"] = self.switch(self.run_id, new_lane, active_ptr)
            s.update(slot=self.run_id, lane=new_lane, previous=active_slot,
                     mechanism="rename(2) of pointer/public/active.json")
        self.facts["switched_slot"] = self.run_id
        t0 = time.monotonic()
        rc, tail, _ = self.smoke(cfg.smoke_url, "post-switch-smoke")
        if cfg.test_fault == "post_switch":
            rc, tail = 1, f"injected test fault (REBUILD_TEST_FAULT=post_switch); real result: {tail}"
        if rc != 0:
            reason = f"smoke.py against {cfg.smoke_url} exit {rc}: {tail}"
            self.log.step("post_switch", "failed", time.monotonic() - t0, reason=reason)
            if not active_ptr:
                raise Outcome(23, "failed", "post_switch", reason + " (first build: nothing to roll back to)")
            self.phase = "rolling_back"
            self.rollback_after_switch(self.run_id, new_lane, active_ptr, reason)
            raise Outcome(24, "rolled back", "post_switch", reason)
        self.clear_post_switch_pending()
        self.log.step("post_switch", "ok", time.monotonic() - t0, smoke=tail, base_url=cfg.smoke_url)
        self.phase = "grace"
        with self.step("grace") as s:
            s["seconds"] = cfg.grace_s
            if active_ptr:
                self.sleep(cfg.grace_s)
                self.stop_lane(active_ptr["lane"])
                s["stopped_lane"] = active_ptr["lane"]
            else:
                s["result"] = "skipped: first build (no old lane)"
        with self.step("cleanup") as s:
            if self.aux_aside:
                self.restore_aux_aside(keep_new=True)
            s.update(self.cleanup())
        self.phase = "done"
        return self.finish(0, "success")

    def unchanged(self, active_slot, osm):
        with self.step("check_active") as s:
            rc, tail, _ = self.smoke(self.cfg.smoke_url, "unchanged-smoke")
            stale = self.data_stale(active_slot)
            date = osm_facts(self.lay.build_info(active_slot)).get("date")
            s.update(slot=active_slot, smoke=f"exit {rc}: {tail}", osm_data_date=date, stale=stale)
            if rc != 0 or stale:
                why = []
                if rc != 0:
                    why.append(f"smoke.py exit {rc} ({tail})")
                if stale:
                    why.append(f"active OSM data date {date} older than {self.cfg.stale_h:g} h")
                raise Outcome(15, "skipped: unchanged", "check_active", "unchanged extract, but " + "; ".join(why))
        self.facts["unchanged_sha256"] = osm.get("sha256")
        return self.finish(0, "skipped: unchanged", None, "extract checksum equals the active slot's")

    def rollback(self):
        self.begin()
        t0 = time.monotonic()
        try:
            st = load_state(self.lay)
            ptr = self.lay.pointer()
            prev = st.get("previous")
            if ptr and st.get("active") != ptr["slot"]:
                st = self.reconcile_state_only(st, ptr)
                prev = st.get("previous")
            if not ptr or not prev or not self.lay.complete(prev) or prev in st.get("rolled_back", []):
                raise Outcome(30, "refused: no previous good slot", "rollback", "no previous good slot to roll back to "
                              f"(active {ptr['slot'] if ptr else None}, previous {prev}); nothing changed")
            self.isolation_guard()
            cur, cur_lane = ptr["slot"], ptr["lane"]
            lane = "green" if cur_lane == "blue" else "blue"
            mounted = self.lane_mounted_slot(lane)
            with self.step("start_previous_lane") as s:
                recreate = mounted != prev
                ok, msg = self.start_lane(lane, prev, recreate=recreate, timeout=self.cfg.rollback_timeout_s)
                s.update(lane=lane, slot=prev, how="recreated (lane held another slot)" if recreate else "started")
                if not ok:
                    self.stop_lane(lane)
                    raise Outcome(31, "failed", "rollback", f"lane {lane} did not become healthy on {prev} "
                                  f"within {self.cfg.rollback_timeout_s} s: {msg}; the active slot keeps serving")
            with self.step("switch") as s:
                with self.critical():
                    write_pointer(self.lay.ptr_public, prev, lane)
                    st = load_state(self.lay)
                    st["active"], st["previous"] = prev, None
                    st.pop("post_switch_pending", None)
                    st["rolled_back"].append(cur)
                    sha = osm_facts(self.lay.build_info(cur)).get("sha256")
                    if sha:
                        st["rolled_back_sha256"].append(sha)
                    save_state(self.lay, st)
                s.update(slot=prev, lane=lane, rolled_back=cur, seconds_since_start=round(time.monotonic() - t0, 1))
            self.facts["rollback_seconds_to_switch"] = round(time.monotonic() - t0, 1)
            with self.step("grace") as s:
                self.sleep(self.cfg.grace_s)
                self.stop_lane(cur_lane)
                s.update(seconds=self.cfg.grace_s, stopped_lane=cur_lane)
            return self.finish(0, "rolled back", None, f"active {prev}, rolled back from {cur}")
        except Outcome as o:
            Proc.kill_all()
            self.log.event("error", o.reason or o.result, result=o.result, exit_code=o.code)
            return self.finish(o.code, o.result if o.code != 40 else "interrupted", o.step, o.reason)

    def reconcile_state_only(self, st, ptr):
        if ptr["slot"] == st.get("previous"):
            old = st.get("active")
            if old:
                st["rolled_back"].append(old)
            st["previous"] = None
        elif st.get("active") and self.lay.complete(st["active"]):
            st["previous"] = st["active"]
        st["active"] = ptr["slot"]
        save_state(self.lay, st)
        return st


class SourceError(Exception):
    def __init__(self, check, measured):
        super().__init__(f"{check}: {measured}")
        self.check, self.measured = check, measured


# ====================================================================== status and checksums (read-only)
def status(cfg):
    lay = Layout(cfg.root)
    st = load_state(lay)
    ptr = lay.pointer()

    def slot_doc(slot_id):
        if not slot_id:
            return None
        info = lay.build_info(slot_id)
        o = osm_facts(info)
        return {"slot": slot_id, "built_at": info.get("built_at"), "osm_data_date": o["date"],
                "osm_data_date_source": o["date_source"], "osm_source": o["source"], "osm_sha256": o["sha256"],
                "photon_data_timestamp": (info.get("photon_dump") or {}).get("data_timestamp"),
                "tiles_maxzoom": (info.get("settings") or {}).get("tiles_maxzoom")}

    active = slot_doc(ptr["slot"]) if ptr else None
    if active:
        active["lane"] = ptr["lane"]
        active["switched_at"] = ptr.get("switched_at")
    previous = st.get("previous") if (not ptr or st.get("previous") != ptr["slot"]) else None
    stale = None
    if active:
        d = parse_iso(active["osm_data_date"])
        stale = (not d) or (utc_now() - d).total_seconds() / 3600 > cfg.stale_h
    nxt = None
    if shutil.which("systemctl"):
        with contextlib.suppress(Exception):
            r = subprocess.run(["systemctl", "show", cfg.timer_unit, "-p", "NextElapseUSecRealtime", "--value"],
                               capture_output=True, text=True, timeout=1)
            nxt = r.stdout.strip() or None
    doc = {"active": active, "previous": slot_doc(previous) if previous and lay.complete(previous) else None,
           "last_run": st.get("last_run"), "stale": stale, "stale_after_hours": cfg.stale_h,
           "next_scheduled_run": nxt, "rolled_back_slots": st.get("rolled_back", [])[-5:],
           "state_matches_pointer": (st.get("active") == (ptr["slot"] if ptr else None)),
           "post_switch_pending": st.get("post_switch_pending")}
    print(json.dumps(doc, ensure_ascii=False, indent=2))
    return 0


def checksums(cfg, slot_id):
    """sha256 listing of a slot for AC 7. photon/photon_data is the running Photon's working copy (OpenSearch
    logs, translog and _state change while it serves) and is listed separately as excluded."""
    lay = Layout(cfg.root)
    base = lay.slot(slot_id)
    if not base.is_dir():
        print(f"no such slot: {slot_id}", file=sys.stderr)
        return 2
    excluded = 0
    for root, dirs, files in sorted(os.walk(base)):
        dirs.sort()
        rel = os.path.relpath(root, base)
        if rel == os.path.join("photon", "photon_data") or rel.startswith(os.path.join("photon", "photon_data") + os.sep):
            excluded += len(files)
            continue
        for name in sorted(files):
            p = os.path.join(root, name)
            print(f"{sha256_file(p)}  {os.path.relpath(p, base)}")
    print(f"# excluded {excluded} files under photon/photon_data (runtime state of the serving Photon)")
    return 0


# ====================================================================== CLI
def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--env-file", required=True, help="pipeline config (staging: infra/staging/.env)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    rb = sub.add_parser("rebuild")
    rb.add_argument("--force", action="store_true", help="build even if the extract is unchanged or was rolled back")
    rb.add_argument("--accept-size-drop", action="store_true", help="one-time: skip check 3(d) and AC 13")
    rb.add_argument("--accept-route-change", action="store_true", help="one-time: accept AC 12 deviations")
    rb.add_argument("--refresh-aux", action="store_true",
                    help="download every auxiliary file and tool again (implies --force; NAV-008 AC 15 measurement)")
    rb.add_argument("--scheduled", action="store_true", help="timer entry point: overrides are refused")
    rb.add_argument("--assume-locked", action="store_true", help="the caller (deploy.sh) holds the lock")
    sub.add_parser("rollback")
    sub.add_parser("status")
    ck = sub.add_parser("checksums")
    ck.add_argument("slot")
    args = ap.parse_args(argv)
    try:
        cfg = Config(args.env_file).load()
    except ConfigError as e:
        print(json.dumps({"ts": iso(), "job": "nav-pipeline", "level": "error", "msg": str(e), "exit_code": 2}), flush=True)
        return 2
    if args.cmd == "status":
        return status(cfg)
    if args.cmd == "checksums":
        return checksums(cfg, args.slot)
    p = Pipeline(cfg, args.cmd)
    try:
        if args.cmd == "rebuild":
            return p.rebuild(args.force, args.accept_size_drop, args.accept_route_change, args.scheduled,
                             args.assume_locked, args.refresh_aux)
        return p.rollback()
    except Outcome as o:   # before a run ID exists: config refusals and the lock
        print(json.dumps({"ts": iso(), "job": "nav-pipeline", "kind": args.cmd, "level": "error", "step": o.step,
                          "result": o.result, "msg": o.reason, "exit_code": o.code}, ensure_ascii=False), flush=True)
        return o.code


if __name__ == "__main__":
    sys.exit(main())

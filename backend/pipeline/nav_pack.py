#!/usr/bin/env python3
"""NAV-020 offline pack publication (ADR-0017 §1, §4-§6 and Amendment A1; task file
docs/architecture/tasks/NAV-020-offline-pack-build-publication.md). Used by nav_pipeline.py only:

    nav_pipeline.py --env-file FILE rebuild ...          # after a NAV-006 `success`: the pack step (PACK_ENABLED=1)
    nav_pipeline.py --env-file FILE pack-publish [--force] [--tiles]   # manual, same lock (make pack-publish)
    nav_pipeline.py --env-file FILE pack-status          # the `pack` object of `make status` (make pack-status)
    nav_pipeline.py --env-file FILE rollback             # AC 28 hook: republish the newest clean retained manifest

Pack step (inside the NAV-006 lock, after the switch, the grace period, the old lane stop and slot cleanup):
  reconcile (leftovers) -> guard (PACK_MIN_FREE_GB) -> eligibility -> engine version check (AC 13) -> select
  (weekly part due? tiles due only with a weekly cut, A1 item 6; write-once version directories, A1 item 7) ->
  copy (hard links of the slot files into packs/.work/<run>/src) -> search build (pinned builder image, network
  none) -> gzip (deterministic: no name, mtime 0, PACK_GZIP_LEVEL) into packs/mn/<slot>.partial -> checksum (from
  disk; decompressed copies for the tests) -> self-test (tiles header, search DB, gzip round trip) -> gate 2 (Gate 2
  engine on the decompressed candidate vs the active server Valhalla through the public gateway; strict, A1 item 13)
  -> manifest (schema-validated; version dir renamed, manifest copy, rename(2) of manifest.json, history) ->
  cleanup (retention: files of the last PACK_RETAIN_MANIFESTS manifests).

Results (task file §1.3): published | not due | skipped (low disk) | skipped (not eligible) | failed (gate 2) |
failed (self-test) | failed (engine version mismatch) | failed (build) | failed (interrupted). Failed and low-disk
results call the NAV-006 alert hook exactly once. On the timer path the NAV-006 result and exit code never change
(A1 item 12); `pack-publish` exits with the result's code (0, 50-55, 40; 10 = lock held, 2 = configuration).

Logs: one JSON line per sub-step (pack.select, pack.copy, pack.search_build, pack.gzip, pack.checksum, pack.gate2,
pack.self_test, pack.manifest, pack.cleanup) with run_id, result, duration_s, in the NAV-006 run log. Only slot IDs,
sizes, checksums, durations, request IDs of the golden set and the reference points P1-P6/X1/golden-set points.
"""
import contextlib
import datetime as dt
import gzip
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import nav_pipeline as base

HERE = Path(__file__).resolve().parent
BACKEND = HERE.parent
REPO = BACKEND.parent
PACK_DIR = BACKEND / "pack"
SEARCH_BUILDER = PACK_DIR / "search_builder.py"
EVIDENCE_DRIVER = PACK_DIR / "gate2_evidence_driver.py"
GATE2_DEFAULT_JSON = BACKEND / "gate2" / "default.json"
GATE2_DEFAULT_JSON_SHA256 = "e7a841f697ccdada70de68aa0bbea665040052bfbefffe003d8a4f55169515a9"
MANIFEST_VALIDATOR = BACKEND / "scripts" / "validate_manifest.py"
APP_VERSIONS_TOML = REPO / "mobile" / "android" / "gradle" / "libs.versions.toml"
DEFAULT_GOLDEN = "tests/offline/nav020-golden-routes.json"     # QA-owned (AC 9); path proposed to QA
DEFAULT_SEARCH_IMAGE = "python:3.14-slim@sha256:c3e521df8b2b498a7a682e7e18676771cb80c6b75b8699af886b2d554ce40151"
DEFAULT_VALHALLA_IMAGE = ("ghcr.io/valhalla/valhalla:3.9.0@sha256:"
                          "511c095b8caf393dccceb8b519ec96b6f85a0166b2288ba014a8a748acc5a63c")
WRAPPER_COMMIT = "b47ad5a9aa5d907df329bd2a0bfcc9080220c9d8"
VALHALLA_COMMIT = "e2f017b16080f49203de245a211b09efab09cf72"
ODBL_URL = "https://opendatacommons.org/licenses/odbl/1-0/"
ODBL_NAME = "ODbL-1.0"
DEFAULT_ATTRIBUTION = "© OpenStreetMap contributors"     # also in pack/search_builder.py meta (LICENCE_META)
KINDS = ("tiles", "routing", "search")
FILE_NAME = {"tiles": "basemap.pmtiles", "routing": "routing.tar", "search": "search.sqlite"}
SLOT_SOURCE = {"tiles": Path("tiles") / "basemap.pmtiles", "routing": Path("valhalla") / "valhalla_tiles.tar",
               "search": Path("sources") / "photon-dump"}
VERSION_RE = re.compile(r"^[0-9]{8}T[0-9]{6}Z$")
PATH_RE = re.compile(r"^[0-9]{8}T[0-9]{6}Z/(basemap\.pmtiles|routing\.tar|search\.sqlite)\.gz$")
# Bounds check of the tiles header (AC 12): P1-P6 and X1 (Erdenet).
CHECK_POINTS = dict(base.REF_POINTS, X1=(49.0270, 104.0440))
SELF_TEST_ID = "__self_test_route__"

RESULT_CODES = {"published": 0, "not due": 0, "skipped (low disk)": 54, "skipped (not eligible)": 55,
                "failed (gate 2)": 50, "failed (self-test)": 51, "failed (engine version mismatch)": 52,
                "failed (build)": 53, "failed (interrupted)": 40}
TEST_FAULTS = ("gate2_diff", "self_test_tiles", "self_test_search", "build")
PAUSE_POINTS = ("copy", "search_build", "gzip", "gate2", "after_dir_rename", "before_manifest_rename")
# Valhalla error code -> OSRM code, from valhalla/src/exceptions.cc at e2f017b1 (3.6.3). The phone engine returns
# {"code": <int>, "message": ...} (valhalla-mobile error envelope); the server returns the OSRM code string.
_OSRM = {"InvalidUrl": [100, 101, 103, 155, 156, 199, 200, 201, 202, 210, 211, 213, 220, 230, 231, 232, 299, 313, 401,
                        430, 440, 441, 445, 446, 499, 503],
         "ServiceUnavailable": [102, 203, 402], "InvalidService": [106, 107, 400],
         "InvalidOptions": [110, 111, 112, 113, 114, 115, 120, 121, 122, 123, 124, 125, 126, 127, 128, 160, 161, 165,
                            168, 212, 312],
         "InvalidValue": [130, 131, 132, 133, 134, 135, 136, 137, 140, 141, 142, 143, 144, 150, 151, 152, 153, 157, 158,
                          159, 162, 163, 164, 166, 173, 174, 175, 233, 314, 420, 421, 422, 423, 424, 504, 599],
         "DistanceExceeded": [154], "PerimeterExceeded": [167, 234], "NoRoute": [170, 442],
         "NoSegment": [171, 443, 444], "BreakageDistanceExceeded": [172]}
VALHALLA_TO_OSRM = {c: name for name, codes in _OSRM.items() for c in codes}


def utc_now():
    return base.utc_now()


def slot_time(version):
    return dt.datetime.strptime(version, "%Y%m%dT%H%M%SZ").replace(tzinfo=dt.timezone.utc)


def norm_ts(s):
    """Any ISO date-time with a UTC offset -> 'YYYY-MM-DDTHH:MM:SSZ' (B8)."""
    t = base.parse_iso(s) if s else None
    return t.strftime("%Y-%m-%dT%H:%M:%SZ") if t else None


# ====================================================================== configuration (task file §1.4)
class PackConfig:
    def __init__(self, cfg):
        g, num = cfg.get, cfg.num
        test_project = cfg.project != "navmn"
        self.enabled = g("PACK_ENABLED", "0") == "1"
        self.region = g("PACK_REGION", "mn")
        if self.region != "mn":
            raise base.ConfigError("PACK_REGION must be mn (the only region, ADR-0017 §1)")
        self.weekly_min_days = num("PACK_WEEKLY_MIN_AGE_DAYS", 7)
        self.tiles_min_days = num("PACK_TILES_MIN_AGE_DAYS", 28)
        for key, v in (("PACK_WEEKLY_MIN_AGE_DAYS", self.weekly_min_days), ("PACK_TILES_MIN_AGE_DAYS", self.tiles_min_days)):
            if v < 0 or (v < 1 and not test_project):
                raise base.ConfigError(f"{key} must be >= 1 (values below 1 only in test projects, not navmn)")
        wd = g("PACK_WEEKDAY", "")
        if wd and not re.match(r"^[1-7]$", wd):
            raise base.ConfigError("PACK_WEEKDAY must be empty or an ISO weekday 1-7 (Asia/Ulaanbaatar)")
        self.weekday = int(wd) if wd else None
        self.min_free_gb = num("PACK_MIN_FREE_GB", 2)
        self.retain = int(num("PACK_RETAIN_MANIFESTS", 3))
        if self.retain < 1 or (self.retain < 3 and not test_project):
            raise base.ConfigError("PACK_RETAIN_MANIFESTS must be >= 3 (AC 16)")
        self.gzip_level = int(num("PACK_GZIP_LEVEL", 6))
        if not 1 <= self.gzip_level <= 9:
            raise base.ConfigError("PACK_GZIP_LEVEL must be 1-9")
        self.search_image = g("PACK_SEARCH_BUILDER_IMAGE", DEFAULT_SEARCH_IMAGE)
        if "@sha256:" not in self.search_image:
            raise base.ConfigError("PACK_SEARCH_BUILDER_IMAGE must be pinned by digest (image@sha256:...)")
        self.search_builder_version = int(num("PACK_SEARCH_BUILDER_VERSION", 1))
        self.search_schema = int(num("PACK_SEARCH_SCHEMA", 1))
        self.gate2_mode = g("PACK_GATE2_MODE", "engine")
        if self.gate2_mode not in ("engine", "evidence"):
            raise base.ConfigError("PACK_GATE2_MODE must be engine or evidence")
        if self.gate2_mode == "evidence" and (not cfg.allow_faults or not test_project):
            raise base.ConfigError("PACK_GATE2_MODE=evidence is honoured only with REBUILD_ALLOW_TEST_FAULTS=1 and a "
                                   "Compose project other than navmn (it is NOT the Gate 2 engine, AC 35)")
        self.gate2_image = g("PACK_GATE2_IMAGE", "navmn-gate2:0.6.3")
        if self.gate2_image.startswith(("ghcr.io/valhalla/valhalla", "valhalla/valhalla")):
            raise base.ConfigError("PACK_GATE2_IMAGE must be the image built from backend/gate2 (never the upstream "
                                   "Valhalla image, NAV-020 AC 35)")
        self.evidence_image = g("VALHALLA_IMAGE", DEFAULT_VALHALLA_IMAGE)
        self.mobile_version = g("PACK_GATE2_VALHALLA_MOBILE_VERSION", "0.6.3")
        self.wrapper_commit = g("PACK_GATE2_WRAPPER_COMMIT", WRAPPER_COMMIT)
        self.valhalla_commit = g("PACK_GATE2_VALHALLA_COMMIT", VALHALLA_COMMIT)
        gs = g("PACK_GATE2_GOLDEN_SET", DEFAULT_GOLDEN)
        self.golden_set = Path(gs) if os.path.isabs(gs) else REPO / gs
        self.gate2_rate = num("PACK_GATE2_RATE", 5)
        if not 0 < self.gate2_rate <= 5:
            raise base.ConfigError("PACK_GATE2_RATE must be > 0 and <= 5 requests/s (ADR-0017 A1 item 10)")
        self.self_test_route = parse_route(g("PACK_SELF_TEST_ROUTE", "47.9189,106.9176;49.4867,105.9228;auto"))
        self.self_test_query = g("PACK_SELF_TEST_QUERY", "Сүхбаатар")
        self.attribution = g("PACK_ATTRIBUTION", DEFAULT_ATTRIBUTION)
        if "OpenStreetMap" not in self.attribution:
            raise base.ConfigError("PACK_ATTRIBUTION must contain OpenStreetMap (AC 7)")
        self.method_url = g("PACK_METHOD_URL", "")
        if self.method_url and not re.match(r"^https://[^\s/]+\.[^\s]+$", self.method_url):
            raise base.ConfigError("PACK_METHOD_URL must be an https:// URL (public pipeline repository, ODbL §4.6)")
        why = placeholder_url(self.method_url, allow_test_tld=test_project)
        if self.method_url and why:
            raise base.ConfigError(f"PACK_METHOD_URL is a placeholder ({why}); set the public pipeline repository "
                                   f"(ODbL §4.6, NAV-020 AC 7)")
        self.test_fault = g("PACK_TEST_FAULT", "")
        self.pause_at = g("PACK_TEST_PAUSE_AT", "")
        self.pause_s = num("PACK_TEST_PAUSE_SECONDS", 20)
        if self.test_fault or self.pause_at:
            if self.test_fault and self.test_fault not in TEST_FAULTS:
                raise base.ConfigError(f"PACK_TEST_FAULT must be one of {', '.join(TEST_FAULTS)}")
            if self.pause_at and self.pause_at not in PAUSE_POINTS:
                raise base.ConfigError(f"PACK_TEST_PAUSE_AT must be one of {', '.join(PAUSE_POINTS)}")
            if not cfg.allow_faults or not test_project:
                raise base.ConfigError("PACK_TEST_FAULT / PACK_TEST_PAUSE_AT are honoured only with "
                                       "REBUILD_ALLOW_TEST_FAULTS=1 and a Compose project other than navmn")

    def relaxations(self):
        out = []
        if self.weekly_min_days < 7:
            out.append(f"PACK_WEEKLY_MIN_AGE_DAYS={self.weekly_min_days:g} (staging 7)")
        if self.tiles_min_days < 28:
            out.append(f"PACK_TILES_MIN_AGE_DAYS={self.tiles_min_days:g} (staging 28)")
        if self.min_free_gb < 2:
            out.append(f"PACK_MIN_FREE_GB={self.min_free_gb:g} (staging 2)")
        if self.retain < 3:
            out.append(f"PACK_RETAIN_MANIFESTS={self.retain} (staging 3)")
        if self.gate2_mode == "evidence":
            out.append("PACK_GATE2_MODE=evidence: NOT the Gate 2 engine (test only, AC 35)")
        if self.test_fault:
            out.append(f"PACK_TEST_FAULT={self.test_fault}")
        if self.pause_at:
            out.append(f"PACK_TEST_PAUSE_AT={self.pause_at}")
        return out


# Names that can never be a public repository (RFC 2606 / RFC 6761): the documentation domains and the reserved
# TLDs. `.test` is accepted only outside navmn, so test projects can publish a recognisable non-resolvable value.
PLACEHOLDER_DOMAINS = ("example.com", "example.net", "example.org")
PLACEHOLDER_TLDS = ("example", "invalid", "localhost")


def placeholder_url(url, allow_test_tld=False):
    """'' when the host of `url` may be a real public host, else why it is a placeholder (NAV-020 review minor)."""
    m = re.match(r"^[a-z][a-z0-9+.-]*://(?:[^@/\s]*@)?([^/:?#\s]+)", url or "", re.I)
    if not m:
        return ""
    host = m.group(1).lower().rstrip(".")
    labels = host.split(".")
    if any(host == d or host.endswith("." + d) for d in PLACEHOLDER_DOMAINS):
        return f"{host} is a documentation domain"
    if labels[-1] in PLACEHOLDER_TLDS or host == "localhost":
        return f"{host} uses the reserved .{labels[-1]} name"
    if labels[-1] == "test" and not allow_test_tld:
        return f"{host} uses the reserved .test TLD (accepted only in test projects, not navmn)"
    return ""


def parse_route(s):
    m = re.match(r"^\s*(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?);(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?);(auto|pedestrian|bicycle)\s*$", s)
    if not m:
        raise base.ConfigError("PACK_SELF_TEST_ROUTE must be 'lat,lon;lat,lon;auto|pedestrian|bicycle'")
    return {"from": {"lat": float(m.group(1)), "lon": float(m.group(2))},
            "to": {"lat": float(m.group(3)), "lon": float(m.group(4))}, "costing": m.group(5)}


# ====================================================================== layout
class PackLayout:
    def __init__(self, root, region="mn"):
        self.packs = Path(root) / "packs"
        self.region = self.packs / region
        self.manifest = self.region / "manifest.json"
        self.copies = self.region / ".manifests"
        self.history = self.region / ".history.json"
        self.work_root = self.packs / ".work"

    def ensure(self):
        for d in (self.packs, self.region, self.copies, self.work_root):
            d.mkdir(parents=True, exist_ok=True)
        for d in (self.packs, self.region):
            os.chmod(d, 0o755)          # the unprivileged gateway (uid 101) reads packs/mn

    def version_dirs(self):
        if not self.region.is_dir():
            return []
        return sorted(d for d in self.region.iterdir() if d.is_dir() and VERSION_RE.match(d.name))

    def read_manifest(self):
        return base.read_json(self.manifest)

    def read_history(self):
        h = base.read_json(self.history, []) or []
        return h if isinstance(h, list) else []


def manifest_files(m):
    return {f["kind"]: f for f in (m or {}).get("files", []) if isinstance(f, dict) and f.get("kind") in KINDS}


def manifest_paths(m):
    return {f["path"] for f in manifest_files(m).values() if isinstance(f.get("path"), str)}


# ====================================================================== deterministic gzip, hashing, PMTiles header
def gzip_file(src, dst, level, stop=None, chunk=1 << 20):
    """Deterministic gzip (no file name, mtime 0, fixed level, ADR-0017 A1 item 9). Returns (sha256, bytes) of the
    SOURCE as read. fsyncs the file. `stop` is called between chunks (SIGTERM handling)."""
    h, n = hashlib.sha256(), 0
    with open(src, "rb") as fi, open(dst, "wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", compresslevel=level, fileobj=raw, mtime=0) as gz:
            for block in iter(lambda: fi.read(chunk), b""):
                if stop:
                    stop()
                h.update(block)
                n += len(block)
                gz.write(block)
        raw.flush()
        os.fsync(raw.fileno())
    os.chmod(dst, 0o644)
    return h.hexdigest(), n


def gunzip_digest(gz_path, out_path=None, head=127, chunk=1 << 20, stop=None):
    """Streaming decompression of a .gz from disk: (sha256, bytes, first `head` bytes). Optionally writes the
    decompressed bytes to out_path (the copy Gate 2 and the self-tests run on, A1 item 8)."""
    h, n, first = hashlib.sha256(), 0, b""
    out = open(out_path, "wb") if out_path else None
    try:
        with gzip.open(gz_path, "rb") as f:
            for block in iter(lambda: f.read(chunk), b""):
                if stop:
                    stop()
                if len(first) < head:
                    first += block[:head - len(first)]
                h.update(block)
                n += len(block)
                if out:
                    out.write(block)
    finally:
        if out:
            out.close()
    return h.hexdigest(), n, first


def pmtiles_header(b):
    """PMTiles v3 header (127 bytes): magic, version, min/max zoom, bounds (E7 integers)."""
    if len(b) < 127 or b[:7] != b"PMTiles":
        return None
    import struct
    return {"version": b[7], "min_zoom": b[100], "max_zoom": b[101],
            "min_lon": struct.unpack_from("<i", b, 102)[0] / 1e7, "min_lat": struct.unpack_from("<i", b, 106)[0] / 1e7,
            "max_lon": struct.unpack_from("<i", b, 110)[0] / 1e7, "max_lat": struct.unpack_from("<i", b, 114)[0] / 1e7}


def tiles_problems(hdr):
    if not hdr:
        return ["not a PMTiles archive (magic missing)"]
    out = []
    if hdr["version"] != 3:
        out.append(f"PMTiles version {hdr['version']} != 3")
    if hdr["min_zoom"] != 0 or hdr["max_zoom"] != 14:
        out.append(f"zoom {hdr['min_zoom']}-{hdr['max_zoom']} != 0-14 (D170)")
    miss = [k for k, (lat, lon) in CHECK_POINTS.items()
            if not (hdr["min_lon"] <= lon <= hdr["max_lon"] and hdr["min_lat"] <= lat <= hdr["max_lat"])]
    if miss:
        out.append(f"bounds miss {','.join(miss)}")
    return out


# ====================================================================== Gate 2 comparison (AC 10, A1 item 13)
def decode_polyline6(s):
    """Decoded polyline6 as integer pairs (exact: equality of every point at 6 decimals)."""
    pts, idx, lat, lon = [], 0, 0, 0
    while idx < len(s):
        vals = []
        for _ in range(2):
            shift = result = 0
            while True:
                b = ord(s[idx]) - 63
                idx += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            vals.append(~(result >> 1) if result & 1 else result >> 1)
        lat += vals[0]
        lon += vals[1]
        pts.append((lat, lon))
    return pts


def normalise(raw):
    """Comparable view of a route response (server or engine). Narrative and voice text are NOT compared: the
    clients replace them (ADR-0009 §3.1)."""
    try:
        d = json.loads(raw) if isinstance(raw, (str, bytes)) else raw
    except ValueError:
        return {"code": "<unparsable response>"}
    if not isinstance(d, dict):
        return {"code": "<unexpected response>"}
    code = d.get("code")
    if isinstance(code, int) and not isinstance(code, bool):          # valhalla-mobile error envelope
        code = VALHALLA_TO_OSRM.get(code, f"valhalla error {code}")
    elif code is None and isinstance(d.get("error_code"), int):       # Valhalla JSON error (non-OSRM)
        code = VALHALLA_TO_OSRM.get(d["error_code"], f"valhalla error {d['error_code']}")
    out = {"code": code}
    if "routes" in d:
        routes = []
        for r in d.get("routes") or []:
            geom = r.get("geometry")
            steps = []
            for leg in r.get("legs") or []:
                for st in leg.get("steps") or []:
                    m = st.get("maneuver") or {}
                    steps.append({"type": m.get("type"), "modifier": m.get("modifier"), "exit": m.get("exit"),
                                  "name": st.get("name")})
            routes.append({"distance_m": round(float(r.get("distance") or 0)),
                           "duration_s": round(float(r.get("duration") or 0)),
                           "geometry": decode_polyline6(geom) if isinstance(geom, str) else geom,
                           "steps": steps})
        out["routes"] = routes
    return out


def first_difference(a, b, path=""):
    """(path, value_a, value_b) of the first difference, or None."""
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b), key=lambda k: (k != "code", k != "routes", k)):
            if k not in a or k not in b:
                return (f"{path}.{k}".lstrip("."), a.get(k, "<absent>"), b.get(k, "<absent>"))
            d = first_difference(a[k], b[k], f"{path}.{k}")
            if d:
                return d
        return None
    if isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            return (f"{path} (count)".lstrip("."), len(a), len(b))
        for i, (x, y) in enumerate(zip(a, b)):
            d = first_difference(x, y, f"{path}[{i}]")
            if d:
                return d
        return None
    if isinstance(a, tuple) and isinstance(b, tuple):
        return None if a == b else (path.lstrip("."), [round(v / 1e6, 6) for v in a], [round(v / 1e6, 6) for v in b])
    return None if a == b else (path.lstrip("."), a, b)


def load_golden(path):
    doc = base.read_json(path)
    reqs = doc.get("requests") if isinstance(doc, dict) else doc
    if not isinstance(reqs, list) or not reqs:
        return None, {}
    out = []
    for r in reqs:
        if not isinstance(r, dict) or not isinstance(r.get("id"), str) or not isinstance(r.get("body"), dict):
            return None, {}
        out.append({"id": r["id"], "body": r["body"]})
    expect = (doc.get("expect") or {}) if isinstance(doc, dict) else {}
    return out, expect


# ====================================================================== app pin check (AC 13, B15)
def app_valhalla_mobile_version(toml_path=APP_VERSIONS_TOML):
    """valhalla-mobile version pinned in mobile/android/gradle/libs.versions.toml (read-only), or None."""
    try:
        text = Path(toml_path).read_text(encoding="utf-8")
    except (FileNotFoundError, NotADirectoryError, PermissionError):
        return None
    versions = {}
    section = None
    lib_line = None
    for line in text.splitlines():
        s = line.split("#", 1)[0].strip()
        if s.startswith("["):
            section = s.strip("[]").strip()
            continue
        m = re.match(r'^([A-Za-z0-9_.-]+)\s*=\s*"([^"]*)"\s*$', s)
        if section == "versions" and m:
            versions[m.group(1)] = m.group(2)
        if "io.github.rallista:valhalla-mobile" in s:
            lib_line = s
    if not lib_line:
        return None
    m = re.search(r'"io\.github\.rallista:valhalla-mobile:([^"]+)"', lib_line)
    if m:
        return m.group(1)
    m = re.search(r'version\s*=\s*"([^"]+)"', lib_line)
    if m:
        return m.group(1)
    m = re.search(r'version\.ref\s*=\s*"([^"]+)"', lib_line)
    if m:
        return versions.get(m.group(1))
    return None


def engine_version_check(pc, toml_path=APP_VERSIONS_TOML):
    """(ok, message) for AC 13."""
    app = app_valhalla_mobile_version(toml_path)
    if app is None:
        return True, (f"valhalla-mobile version from configuration ({pc.mobile_version}), app pin not present in "
                      f"{os.path.relpath(toml_path, REPO) if str(toml_path).startswith(str(REPO)) else toml_path}")
    if app != pc.mobile_version:
        return False, (f"app pins valhalla-mobile {app}, Gate 2 engine is configured for {pc.mobile_version} "
                       f"(PACK_GATE2_VALHALLA_MOBILE_VERSION)")
    return True, f"valhalla-mobile {app} pinned by the app equals the Gate 2 engine's"


# ====================================================================== the pack step
class PackOutcome(base.Outcome):
    def __init__(self, result, step, reason):
        super().__init__(RESULT_CODES[result], result, step, reason)


class PackStep:
    """One pack step inside a Pipeline that holds the lock and has a run log (p.begin() done)."""

    def __init__(self, p, trigger):
        self.p, self.cfg, self.trigger = p, p.cfg, trigger
        self.pc = PackConfig(p.cfg)
        self.pl = PackLayout(p.cfg.root, self.pc.region)
        self.work = None
        self.partial = None
        self.containers = []
        self.facts = {}

    # ------------------------------------------------------------------ helpers
    @property
    def log(self):
        return self.p.log

    def step(self, name, **kv):
        return self.p.step(f"pack.{name}", **kv)

    def stop_check(self):
        """Hook between gzip/decompress chunks. SIGTERM/SIGINT already raise Interrupted asynchronously outside
        shielded sections, so nothing to do here; kept for tests."""
        return None

    @contextlib.contextmanager
    def shielded(self):
        """Like Pipeline.critical(), but a signal that arrives inside is NOT raised at the end: the publication
        (directory rename, manifest rename, history) always completes and is recorded as what it is. run() logs a
        deferred signal once the step has ended."""
        p = self.p
        prev = p.in_critical
        p.in_critical = True
        try:
            yield
        finally:
            p.in_critical = prev

    def pause(self, point):
        if self.pc.pause_at == point:
            self.log.event("warn", "test pause (PACK_TEST_PAUSE_AT)", point=point, seconds=self.pc.pause_s)
            self.p.sleep(self.pc.pause_s)

    def docker_run(self, name, args, timeout, input_text=None):
        cname = f"{self.cfg.project}-pack-{name}-{self.p.run_id.lower()}"
        self.containers.append(cname)
        cmd = ["docker", "run", "--rm", "--name", cname, *args]
        try:
            if input_text is None:
                return base.Proc.run(cmd, timeout=timeout)
            proc = subprocess.Popen([str(c) for c in cmd], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, text=True, start_new_session=True)
            base.Proc.live.add(proc)
            try:
                so, se = proc.communicate(input_text, timeout=timeout)
            except BaseException:
                base.Proc.kill(proc)
                raise
            finally:
                base.Proc.live.discard(proc)
            return subprocess.CompletedProcess(proc.args, proc.returncode, so or "", se or "")
        finally:
            self.containers.remove(cname)

    def kill_containers(self):
        r = base.Proc.run(["docker", "ps", "-aq", "--filter", f"name=^{self.cfg.project}-pack-"], timeout=60)
        ids = r.stdout.split()
        if ids:
            base.Proc.run(["docker", "rm", "-f", *ids], timeout=120)
        return len(ids)

    def record(self, rec):
        with self.shielded():
            st = base.load_state(self.p.lay)
            pack = st.get("pack") or {}
            pack["last_result"] = rec
            pack["engine"] = self.engine_facts()
            st["pack"] = pack
            base.save_state(self.p.lay, st)

    def engine_facts(self):
        f = {"mode": self.pc.gate2_mode, "valhalla_mobile_version": self.pc.mobile_version,
             "wrapper_commit": self.pc.wrapper_commit, "valhalla_commit": self.pc.valhalla_commit}
        if self.pc.gate2_mode == "engine":
            f["image"] = self.pc.gate2_image
            f["image_id"] = self.facts.get("gate2_image_id")
            f["variant"] = self.facts.get("gate2_variant")
        else:
            f["image"] = self.pc.evidence_image
            f["note"] = "evidence only: NOT the Gate 2 engine (test project)"
        return f

    # ------------------------------------------------------------------ reconcile (AC 15, B9)
    def reconcile(self):
        pl = self.pl
        pl.ensure()
        removed = []
        n = self.kill_containers()
        if n:
            removed.append(f"{n} pack containers")
        for d in list(pl.work_root.iterdir()):
            base.rmtree(d)
            removed.append(f".work/{d.name}")
        for d in list(pl.region.iterdir()):
            if d.name.endswith(".partial") or d.name.startswith(".manifest.json.tmp") or \
                    d.name.startswith(".tmp-") or re.match(r"^\.[^/]+\.tmp-\d+$", d.name):
                base.rmtree(d) if d.is_dir() else d.unlink()
                removed.append(d.name)
        history = pl.read_history()
        served = pl.read_manifest()
        # A run that died between the manifest rename and the history write: record the served manifest.
        if served is not None:
            sha = base.sha256_file(pl.manifest)
            if not history or history[-1].get("sha256") != sha:
                seq = (history[-1].get("seq", len(history)) if history else 0) + 1
                copy = self.write_copy(served, seq, sha)
                history.append({"seq": seq, "pack_version": served.get("pack_version"),
                                "published_at": served.get("published_at"), "sha256": sha, "copy": copy,
                                "reason": "recovered (history written by reconcile)", "run_id": self.p.run_id})
                base.atomic_write(pl.history, json.dumps(history, indent=1) + "\n")
                removed.append("history entry recovered")
        # Version directories no retained manifest refers to (an interrupted run renamed it, then died).
        keep = self.referenced_paths(history, served)
        keep_dirs = {p.split("/")[0] for p in keep}
        for d in pl.version_dirs():
            if d.name not in keep_dirs:
                base.rmtree(d)
                removed.append(f"unreferenced {d.name}/")
        for c in list(pl.copies.iterdir()):
            if c.name not in {Path(h.get("copy") or "").name for h in history}:
                c.unlink()
                removed.append(f".manifests/{c.name}")
        if removed:
            self.log.event("warn", "pack: leftovers of an interrupted step removed", removed=removed)
        problems = self.verify_served(served) if served else []
        return served, problems

    def referenced_paths(self, history, served):
        keep = set(manifest_paths(served))
        for h in history[-self.pc.retain:]:
            m = base.read_json(self.pl.region / (h.get("copy") or "-"))
            keep |= manifest_paths(m)
        return keep

    def verify_served(self, m):
        """Every file the manifest refers to exists with its recorded size and SHA-256 (AC 15; about 1 s)."""
        out = []
        for f in manifest_files(m).values():
            p = self.pl.region / f["path"]
            if not p.is_file():
                out.append(f"{f['path']} missing")
            elif p.stat().st_size != f.get("download_bytes"):
                out.append(f"{f['path']} size {p.stat().st_size} != {f.get('download_bytes')}")
            elif base.sha256_file(p) != f.get("download_sha256"):
                out.append(f"{f['path']} SHA-256 differs from download_sha256")
        return out

    # ------------------------------------------------------------------ the run
    def run(self, force=False, tiles=False):
        p, pc = self.p, self.pc
        t0 = time.monotonic()
        force = force or tiles                     # TILES=1 implies FORCE=1 (A1 item 6)
        result, step, reason, published = None, None, None, None
        try:
            self.log.event("info", "pack step", trigger=self.trigger, force=force, tiles=tiles,
                           relaxed=pc.relaxations() or None, gate2_mode=pc.gate2_mode)
            published = self._run(force, tiles)
            result = published.pop("_result")
            reason = published.pop("_reason", None)
        except PackOutcome as o:
            result, step, reason = o.result, o.step, o.reason
        except base.Interrupted as o:
            result, step, reason = "failed (interrupted)", p.step_name, o.reason
        except base.Outcome as o:          # should not happen; classified as a build failure
            result, step, reason = "failed (build)", o.step, o.reason or o.result
        except Exception as e:  # noqa: BLE001 - a bug or an OS error still ends the step cleanly
            result, step, reason = "failed (build)", (p.step_name or "pack").replace("pack.", ""), \
                f"unexpected {type(e).__name__}: {e}"
        finally:
            self.cleanup_failed_run(result)
        step = (step or "").replace("pack.", "") or None
        rec = {"run_id": p.run_id, "trigger": self.trigger, "result": result, "step": step, "reason": reason,
               "at": base.iso(), "duration_s": round(time.monotonic() - t0, 1)}
        if published:
            rec.update(published)
        try:
            self.record(rec)
        except Exception as e:  # noqa: BLE001
            self.log.event("error", "pack: could not record the result in state.json", error=str(e)[:200])
        self.log.event("info" if not result.startswith(("failed", "skipped (low")) else "error",
                       f"pack: {result}", step=step, reason=reason, exit_code=RESULT_CODES[result])
        if result.startswith("failed") or result == "skipped (low disk)":
            p.alert(RESULT_CODES[result], f"pack: {result}", step and f"pack.{step}", reason)
        if p.pending_signal:
            self.log.event("warn", "pack: a stop signal arrived during the publication rename; the step completed "
                                   "first", signal=int(p.pending_signal))
            p.pending_signal = None
        return rec

    def cleanup_failed_run(self, result):
        with contextlib.suppress(Exception):
            if self.containers:
                base.Proc.run(["docker", "rm", "-f", *self.containers], timeout=60)
        if self.partial is not None and self.partial.exists():
            base.rmtree(self.partial)
            self.log.event("info", "pack: partial version directory deleted", directory=self.partial.name)
        if self.work is not None and self.work.exists():
            base.rmtree(self.work)

    def _run(self, force, tiles):
        p, pc, pl = self.p, self.pc, self.pl
        served, problems = self.reconcile()
        if problems:
            self.log.event("error", "pack: the served manifest refers to missing or changed files", problems=problems)
            repaired = self.republish_clean(lambda m: not self.verify_served(m), "repair")
            if not repaired:
                p.alert(RESULT_CODES["failed (build)"], "pack: served manifest damaged", "pack.reconcile",
                        "; ".join(problems)[:300])
            served = pl.read_manifest()
        # ---- guard (AC 18)
        free = base.free_gib(pl.packs)
        if free < pc.min_free_gb:
            raise PackOutcome("skipped (low disk)", "pack.guard",
                              f"free disk {free:.2f} GB < required {pc.min_free_gb:g} GB on {self.cfg.root}")
        # ---- eligibility
        st = base.load_state(p.lay)
        ptr = p.lay.pointer()
        slot = ptr["slot"] if ptr else None
        why = None
        if not slot or not p.lay.complete(slot):
            why = f"no complete active slot (pointer: {slot})"
        elif slot in (st.get("rolled_back") or []):
            why = f"active slot {slot} was rolled back"
        elif st.get("post_switch_pending"):
            why = "a post-switch check is pending (NAV-006); the next rebuild runs it first"
        if why:
            raise PackOutcome("skipped (not eligible)", "pack.select", why)
        # ---- engine version (AC 13)
        ok, msg = engine_version_check(pc)
        self.log.event("info" if ok else "error", msg, check="engine version (AC 13)")
        if not ok:
            raise PackOutcome("failed (engine version mismatch)", "pack.select", msg)
        # ---- select (AC 2, 3; A1 items 6, 7)
        with self.step("select") as s:
            cut, why = self.select(served, slot, force, tiles)
            s.update(active_slot=slot, cut=sorted(cut) or None, decision=why, free_disk_gb=round(free, 2))
        if not cut:
            return {"_result": "not due", "_reason": why, "active_slot": slot}
        self.work = pl.work_root / p.run_id
        self.work.mkdir(parents=True)
        (self.work / "src").mkdir()
        (self.work / "check").mkdir()
        self.partial = pl.region / f"{slot}.partial"
        self.partial.mkdir()
        os.chmod(self.partial, 0o755)
        sdir = p.lay.slot(slot)
        info = p.lay.build_info(slot)
        src = self.copy_sources(sdir, cut)
        if "search" in cut:
            src["search"] = self.search_build(sdir)
        files = self.gzip_all(src, cut)
        self.checksum(files, src)
        self.self_tests(files)
        gate = self.gate2(files)
        manifest = self.build_manifest(served, slot, info, files, cut)
        self.publish(manifest, slot)
        retained = self.retention()
        return {"_result": "published", "pack_version": manifest["pack_version"], "published_at": manifest["published_at"],
                "cut": sorted(cut), "files": {f["kind"]: {"version": f["version"], "download_bytes": f["download_bytes"]}
                                              for f in manifest["files"]},
                "gate2": gate, "packs_disk_bytes": retained.get("packs_disk_bytes")}

    def select(self, served, slot, force, tiles):
        pc = self.pc
        now = utc_now()
        if served is None:
            return {"tiles", "routing", "search"}, "no manifest published yet: all three files from the active slot"
        if (self.pl.region / slot).exists():
            return set(), f"already published: version directory {slot}/ exists (write-once, A1 item 7)"
        files = manifest_files(served)
        r_ver, t_ver = files["routing"]["version"], files["tiles"]["version"]
        r_age = (now - slot_time(r_ver)).total_seconds() / 86400
        t_age = (now - slot_time(t_ver)).total_seconds() / 86400
        weekday_ok = pc.weekday is None or now.astimezone(base.UB).isoweekday() == pc.weekday
        if force:
            weekly, why = True, f"FORCE=1{' TILES=1' if tiles else ''} (routing age {r_age:.1f} d)"
        elif r_age >= pc.weekly_min_days and weekday_ok:
            weekly, why = True, f"routing {r_ver} is {r_age:.1f} d old (>= {pc.weekly_min_days:g})"
        else:
            reason = (f"routing {r_ver} is {r_age:.1f} d old (< {pc.weekly_min_days:g})" if r_age < pc.weekly_min_days
                      else f"weekday {now.astimezone(base.UB).isoweekday()} != PACK_WEEKDAY {pc.weekday}")
            return set(), reason + (f"; tiles {t_ver} is {t_age:.1f} d old" +
                                    (" (due, waits for the next weekly cut, A1 item 6)" if t_age >= pc.tiles_min_days else ""))
        cut = {"routing", "search"}
        if t_ver > slot:
            cut.add("tiles")
            why += f"; tiles {t_ver} is newer than the active slot (after a rollback): cut too (tiles <= routing)"
        elif tiles or t_age >= pc.tiles_min_days:
            cut.add("tiles")
            why += f"; tiles {'TILES=1' if tiles else f'{t_ver} is {t_age:.1f} d old (>= {pc.tiles_min_days:g})'}"
        else:
            why += f"; tiles {t_ver} kept ({t_age:.1f} d < {pc.tiles_min_days:g})"
        return cut, why

    def copy_sources(self, sdir, cut):
        """Hard links of the slot's (immutable) files into .work/src: 0 extra bytes, and the run keeps reading the
        same inode if NAV-006 cleanup deletes the slot meanwhile (ADR-0014 single filesystem)."""
        out = {}
        with self.step("copy") as s:
            linked = []
            for kind in ("tiles", "routing", "search"):
                if kind not in cut:
                    continue
                srcf = sdir / SLOT_SOURCE[kind]
                if not srcf.is_file() or srcf.stat().st_size == 0:
                    raise PackOutcome("failed (build)", "pack.copy", f"slot file missing or empty: {srcf}")
                dst = self.work / "src" / (FILE_NAME[kind] if kind != "search" else "photon-dump")
                try:
                    os.link(srcf, dst)
                    linked.append(kind)
                except OSError:
                    shutil.copyfile(srcf, dst)
                out[kind] = dst
            s.update(files={k: os.path.getsize(v) for k, v in out.items()}, hard_linked=linked)
            self.pause("copy")
        out["dump"] = out.pop("search", None)
        return out

    def search_build(self, sdir):
        pc = self.pc
        out = self.work / "src" / "search.sqlite"
        with self.step("search_build") as s:
            self.pause("search_build")
            if pc.test_fault == "build":
                raise PackOutcome("failed (build)", "pack.search_build", "injected test fault (PACK_TEST_FAULT=build)")
            dump = self.work / "src" / "photon-dump"
            args = ["--network", "none", "--user", f"{os.getuid()}:{os.getgid()}", "--cpus", "2", "--memory", "2g",
                    "-v", f"{dump}:/in/photon-dump:ro", "-v", f"{SEARCH_BUILDER}:/builder/search_builder.py:ro",
                    "-v", f"{self.work / 'src'}:/out", pc.search_image, "python3", "/builder/search_builder.py",
                    "build", "--dump", "/in/photon-dump", "--out", "/out/search.sqlite",
                    "--builder-version", str(pc.search_builder_version), "--schema", str(pc.search_schema)]
            r = self.docker_run("search", args, timeout=900)
            facts = {}
            for line in (r.stdout or "").splitlines():
                with contextlib.suppress(ValueError):
                    facts = json.loads(line)
            if r.returncode != 0 or facts.get("result") != "ok" or not out.is_file():
                raise PackOutcome("failed (build)", "pack.search_build",
                                  f"search builder exit {r.returncode}: {(facts.get('reason') or r.stderr or r.stdout).strip()[-300:]}")
            self.facts["search"] = facts
            s.update(builder_version=facts.get("builder_version"), search_schema=facts.get("search_schema"),
                     image=pc.search_image, rows=facts.get("rows"), bytes=facts.get("bytes"),
                     builder_seconds=facts.get("seconds"), sqlite_version=facts.get("sqlite_version"),
                     dump_data_timestamp=facts.get("data_timestamp"))
        return out

    def gzip_all(self, src, cut):
        files = {}
        with self.step("gzip") as s:
            self.pause("gzip")
            sizes = {}
            for kind in KINDS:
                if kind not in cut:
                    continue
                dst = self.partial / f"{FILE_NAME[kind]}.gz"
                sha, n = gzip_file(src[kind], dst, self.pc.gzip_level, stop=self.stop_check)
                files[kind] = {"kind": kind, "gz": dst, "src_sha256": sha, "src_bytes": n}
                sizes[kind] = {"raw": n, "gz": dst.stat().st_size}
            base.fsync_dir(self.partial)
            s.update(level=self.pc.gzip_level, sizes=sizes)
        return files

    def checksum(self, files, src):
        with self.step("checksum") as s:
            out = {}
            for kind, f in files.items():
                f["download_sha256"] = base.sha256_file(f["gz"])
                f["download_bytes"] = f["gz"].stat().st_size
                keep = self.work / "check" / FILE_NAME[kind] if kind in ("routing", "search") else None
                sha, n, head = gunzip_digest(f["gz"], keep, stop=self.stop_check)
                f.update(sha256=sha, bytes=n, head=head, check=keep)
                out[kind] = {"download_sha256": f["download_sha256"], "download_bytes": f["download_bytes"],
                             "sha256": sha, "bytes": n}
            s.update(files=out, source="bytes on disk after writing (AC 5)")

    def self_tests(self, files):
        pc = self.pc
        with self.step("self_test") as s:
            problems, done = [], []
            for kind, f in files.items():                       # each .gz decompresses to exactly bytes/sha256
                if (f["sha256"], f["bytes"]) != (f["src_sha256"], f["src_bytes"]):
                    problems.append(f"{kind}: gzip round trip {f['bytes']} B {f['sha256'][:12]} != source "
                                    f"{f['src_bytes']} B {f['src_sha256'][:12]}")
            done.append("gzip round trip")
            if "tiles" in files:
                hdr = pmtiles_header(files["tiles"]["head"])
                if pc.test_fault == "self_test_tiles" and hdr:
                    hdr["max_zoom"] = 13
                problems += [f"tiles: {x}" for x in tiles_problems(hdr)]
                done.append("tiles header")
                s["tiles_header"] = hdr
            if "search" in files:
                db = files["search"]["check"]
                if pc.test_fault == "self_test_search":
                    with open(db, "r+b") as fh:
                        fh.seek(4096 * 3)
                        fh.write(b"\xff" * 4096)
                rc, res, tail = self.search_selftest(db)
                if rc != 0 or res.get("result") != "ok":
                    problems.append(f"search: {'; '.join(res.get('problems') or []) or tail}")
                done.append("search DB")
                s["search"] = {k: res.get(k) for k in ("quick_check", "hits", "search_schema", "trad_rows")}
            s["checks"] = done
            if problems:
                raise PackOutcome("failed (self-test)", "pack.self_test", " | ".join(problems))

    def search_selftest(self, db):
        """AC 12 search: quick_check, schema, self_test.search >= 1 row, no Traditional Mongolian, in the pinned
        builder image (same SQLite as the build). Returns (exit code, result dict, output tail)."""
        pc = self.pc
        args = ["--network", "none", "--user", f"{os.getuid()}:{os.getgid()}",
                "-v", f"{db}:/in/search.sqlite:ro", "-v", f"{SEARCH_BUILDER}:/builder/search_builder.py:ro",
                pc.search_image, "python3", "/builder/search_builder.py", "selftest", "--db",
                "/in/search.sqlite", "--query", pc.self_test_query, "--schema", str(pc.search_schema)]
        r = self.docker_run("search-selftest", args, timeout=300)
        res = {}
        for line in (r.stdout or "").splitlines():
            with contextlib.suppress(ValueError):
                res = json.loads(line)
        return r.returncode, res if isinstance(res, dict) else {}, (r.stderr or r.stdout or "").strip()[-200:]

    # ------------------------------------------------------------------ Gate 2 (AC 9-12, B5, B6)
    def engine_config(self):
        """The pinned AAR default.json + the ADR-0017 A1 item 5 overrides (service_limits NOT overridden)."""
        raw = GATE2_DEFAULT_JSON.read_bytes()
        if hashlib.sha256(raw).hexdigest() != GATE2_DEFAULT_JSON_SHA256:
            raise PackOutcome("failed (gate 2)", "pack.gate2", "backend/gate2/default.json does not match the pinned "
                                                              "AAR copy (SHA-256)")
        cfg = json.loads(raw)
        m = cfg["mjolnir"]
        m["tile_extract"] = "/data/routing.tar"
        m["max_cache_size"] = 33554432
        for k in ("tile_dir", "traffic_extract", "admin", "timezone", "landmarks"):
            m[k] = ""
        return cfg

    def engine_image(self):
        pc = self.pc
        if pc.gate2_mode == "evidence":
            return pc.evidence_image, {"mode": "evidence", "image": pc.evidence_image}
        r = base.Proc.run(["docker", "image", "inspect", pc.gate2_image, "--format",
                           "{{.Id}}\t{{json .Config.Labels}}"], timeout=30)
        if r.returncode != 0:
            raise PackOutcome("failed (gate 2)", "pack.gate2",
                              f"Gate 2 engine image {pc.gate2_image} is not present: build it with make gate2-image "
                              f"on a host with >= 20 GB free (RUNBOOK NAV-020), or docker load it")
        image_id, labels = r.stdout.strip().split("\t", 1)
        labels = json.loads(labels or "{}") or {}
        want = {"nav.gate2.wrapper_commit": pc.wrapper_commit, "nav.gate2.valhalla_commit": pc.valhalla_commit,
                "nav.gate2.valhalla_mobile_version": pc.mobile_version}
        bad = [f"{k}={labels.get(k)!r} (want {v})" for k, v in want.items() if labels.get(k) != v]
        if bad:
            raise PackOutcome("failed (gate 2)", "pack.gate2", f"Gate 2 image labels do not match the pins: {'; '.join(bad)}")
        self.facts["gate2_image_id"] = image_id
        self.facts["gate2_variant"] = labels.get("nav.gate2.variant")
        return pc.gate2_image, {"mode": "engine", "image": pc.gate2_image, "image_id": image_id,
                                "variant": labels.get("nav.gate2.variant"), "wrapper_commit": pc.wrapper_commit,
                                "valhalla_commit": pc.valhalla_commit, "valhalla_mobile_version": pc.mobile_version}

    def run_engine(self, image, requests):
        cfg_path = self.work / "gate2-config.json"
        cfg_path.write_text(json.dumps(self.engine_config()))
        os.chmod(cfg_path, 0o644)
        os.chmod(self.work / "check" / "routing.tar", 0o644)
        args = ["-i", "--network", "none", "--cpus", "1", "--memory", "512m",
                "-v", f"{self.work / 'check' / 'routing.tar'}:/data/routing.tar:ro", "-v", f"{cfg_path}:/data/config.json:ro"]
        if self.pc.gate2_mode == "evidence":
            args += ["-v", f"{EVIDENCE_DRIVER}:/driver.py:ro", "--entrypoint", "python3", image, "/driver.py",
                     "/data/config.json"]
        else:
            args += [image, "/data/config.json"]
        stdin = "".join(json.dumps({"id": r["id"], "body": r["body"]}) + "\n" for r in requests)
        r = self.docker_run("gate2", args, timeout=900, input_text=stdin)
        out = {}
        for line in (r.stdout or "").splitlines():
            with contextlib.suppress(ValueError):
                d = json.loads(line)
                if isinstance(d, dict) and "id" in d:
                    out[d["id"]] = d
        if r.returncode != 0 and len(out) < len(requests):
            raise PackOutcome("failed (gate 2)", "pack.gate2",
                              f"Gate 2 engine exit {r.returncode} after {len(out)}/{len(requests)} answers: "
                              f"{(r.stderr or '').strip()[-200:]}")
        return out

    def reference(self, requests):
        """The same bodies to the active server Valhalla through the public gateway (A1 item 10), <= rate r/s."""
        out, gap = {}, 1.0 / self.pc.gate2_rate
        last = 0.0
        for r in requests:
            wait = last + gap - time.monotonic()
            if wait > 0:
                time.sleep(wait)
            last = time.monotonic()
            req = urllib.request.Request(self.cfg.public_url + "/v1/route", data=json.dumps(r["body"]).encode(),
                                         method="POST", headers={"Content-Type": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=30) as resp:
                    out[r["id"]] = (resp.status, resp.read().decode("utf-8", "replace"))
            except urllib.error.HTTPError as e:
                out[r["id"]] = (e.code, e.read().decode("utf-8", "replace"))
            except (urllib.error.URLError, OSError) as e:
                out[r["id"]] = (0, json.dumps({"code": f"<no answer: {type(e).__name__}>"}))
        return out

    def gate2(self, files):
        pc = self.pc
        with self.step("gate2") as s:
            self.pause("gate2")
            golden, expect = load_golden(pc.golden_set)
            if golden is None:
                raise PackOutcome("failed (gate 2)", "pack.gate2",
                                  f"golden route set missing or invalid: {pc.golden_set} (QA fixture, AC 9; "
                                  f"PACK_GATE2_GOLDEN_SET)")
            image, info = self.engine_image()
            st = pc.self_test_route
            st_body = {"locations": [st["from"], st["to"]], "costing": st["costing"], "alternates": 0,
                       "format": "osrm", "banner_instructions": True, "voice_instructions": True,
                       "units": "kilometers", "language": "mn-MN"}
            t_eng = time.monotonic()
            eng = self.run_engine(image, golden + [{"id": SELF_TEST_ID, "body": st_body}])
            eng_s = round(time.monotonic() - t_eng, 1)
            t_ref = time.monotonic()
            ref = self.reference(golden)
            ref_s = round(time.monotonic() - t_ref, 1)
            s.update(engine=info, golden_set=os.path.relpath(pc.golden_set, REPO) if str(pc.golden_set).startswith(str(REPO))
                     else str(pc.golden_set), requests=len(golden), engine_seconds=eng_s, reference_seconds=ref_s,
                     reference=self.cfg.public_url + "/v1/route")
            if pc.gate2_mode == "evidence":
                self.log.event("warn", "gate 2: evidence only, NOT the Gate 2 engine (PACK_GATE2_MODE=evidence; the "
                                       "server's own Valhalla library on both sides). AC 10/35 not met by this run",
                               image=image)
            # routing self-test (AC 12) on the decompressed candidate tar
            st_resp = eng.get(SELF_TEST_ID, {})
            st_code = normalise(st_resp.get("response", "")).get("code") if "response" in st_resp else st_resp.get("error")
            s["self_test_route"] = st_code
            if st_code != "Ok":
                raise PackOutcome("failed (self-test)", "pack.gate2",
                                  f"routing: self_test.route on the candidate routing.tar gave {st_code!r} (want Ok)")
            diffs, codes = [], {}
            for r in golden:
                rid = r["id"]
                e = eng.get(rid)
                if not e or "response" not in e:
                    diffs.append({"request": rid, "field": "engine", "engine": (e or {}).get("error", "<no answer>"),
                                  "server": "<answer>"})
                    continue
                a = normalise(e["response"])
                status, text = ref.get(rid, (0, ""))
                b = normalise(text)
                if pc.test_fault == "gate2_diff" and rid == golden[0]["id"] and a.get("routes"):
                    a["routes"][0]["distance_m"] += 5
                codes[rid] = b.get("code")
                d = first_difference(a, b)
                if d:
                    diffs.append({"request": rid, "field": d[0], "engine": d[1], "server": d[2], "http_status": status})
                elif rid in expect and b.get("code") != expect[rid]:
                    diffs.append({"request": rid, "field": "code (expected)", "engine": a.get("code"),
                                  "server": b.get("code"), "expected": expect[rid]})
            s.update(compared=len(golden), different=len(diffs), codes=codes)
            if diffs:
                for d in diffs:
                    self.log.event("error", "gate 2 difference", **d)
                raise PackOutcome("failed (gate 2)", "pack.gate2",
                                  f"{len(diffs)}/{len(golden)} requests differ; first: {diffs[0]['request']} "
                                  f"{diffs[0]['field']}: engine {str(diffs[0]['engine'])[:80]} != server "
                                  f"{str(diffs[0]['server'])[:80]}")
            s["result"] = "ok" if pc.gate2_mode == "engine" else "ok (evidence only, not the gate)"
            return {"mode": pc.gate2_mode, "requests": len(golden), "different": 0,
                    "image_id": self.facts.get("gate2_image_id")}

    # ------------------------------------------------------------------ manifest and atomic publication (AC 7, 8, 14)
    def build_manifest(self, served, slot, info, files, cut):
        pc = self.pc
        prev = manifest_files(served)
        osm_date = norm_ts(base.osm_facts(info).get("date"))
        dump_ts = norm_ts(((info.get("photon_dump") or {}).get("data_timestamp")) or (self.facts.get("search") or {}).get("data_timestamp"))
        graph = (info.get("versions") or {}).get("valhalla")
        entries = []
        for kind in KINDS:
            if kind in cut:
                f = files[kind]
                fmt = {"tiles": {"pmtiles": 3, "minzoom": 0, "maxzoom": 14},
                       "routing": {"graph_builder": f"valhalla {graph}"},
                       "search": {"search_schema": pc.search_schema}}[kind]
                entries.append({"kind": kind, "version": slot, "path": f"{slot}/{FILE_NAME[kind]}.gz", "encoding": "gzip",
                                "download_bytes": f["download_bytes"], "download_sha256": f["download_sha256"],
                                "bytes": f["bytes"], "sha256": f["sha256"],
                                "data_timestamp": dump_ts if kind == "search" else osm_date, "format": fmt})
            else:
                entries.append(dict(prev[kind]))
        if not pc.method_url:
            raise PackOutcome("failed (build)", "pack.manifest", "PACK_METHOD_URL is empty (public pipeline repository, "
                                                                 "ODbL §4.6, AC 7); set it in the .env")
        m = {"pack_schema": 1, "region": pc.region, "pack_version": slot, "published_at": base.iso(),
             "attribution": pc.attribution,
             "licence": {"name": ODBL_NAME, "url": ODBL_URL, "method_url": pc.method_url},
             "total_bytes": sum(e["bytes"] for e in entries),
             "total_download_bytes": sum(e["download_bytes"] for e in entries),
             "files": entries,
             "self_test": {"route": pc.self_test_route, "search": {"q": pc.self_test_query}}}
        problems = manifest_problems(m, self.pl.region, self.partial)
        if problems:
            raise PackOutcome("failed (build)", "pack.manifest", "manifest rules (AC 7): " + "; ".join(problems))
        return m

    def write_copy(self, manifest, seq, sha=None):
        name = f"{seq:06d}-{manifest.get('pack_version')}.json"
        base.atomic_write(self.pl.copies / name, json.dumps(manifest, ensure_ascii=False, indent=1) + "\n")
        return f".manifests/{name}"

    def install_manifest(self, manifest, reason):
        """rename(2) of a complete temp file over packs/mn/manifest.json (AC 14) + history entry. The new file's
        mtime always differs from the old one (A1 item 11: nginx ETag = mtime-size)."""
        pl = self.pl
        data = json.dumps(manifest, ensure_ascii=False, indent=1) + "\n"
        history = pl.read_history()
        seq = (history[-1].get("seq", len(history)) if history else 0) + 1
        copy = self.write_copy(manifest, seq)
        tmp = pl.region / f".manifest.json.tmp-{os.getpid()}"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, 0o644)
        with contextlib.suppress(FileNotFoundError):
            old = pl.manifest.stat().st_mtime
            new = os.stat(tmp).st_mtime
            if int(new) <= int(old):
                os.utime(tmp, (int(old) + 1, int(old) + 1))
        self.pause("before_manifest_rename")
        with self.shielded():
            os.rename(tmp, pl.manifest)
            base.fsync_dir(pl.region)
            sha = hashlib.sha256(data.encode("utf-8")).hexdigest()
            history.append({"seq": seq, "pack_version": manifest["pack_version"], "published_at": manifest["published_at"],
                            "sha256": sha, "copy": copy, "reason": reason, "run_id": self.p.run_id, "at": base.iso()})
            base.atomic_write(pl.history, json.dumps(history[-200:], indent=1) + "\n")
        return sha

    def publish(self, manifest, slot):
        with self.step("manifest") as s:
            r = validate_with_schema(self.cfg.contract_python, manifest)
            if r:
                raise PackOutcome("failed (build)", "pack.manifest", f"manifest does not validate against "
                                                                     f"OfflinePackManifest: {r}")
            final = self.pl.region / slot
            with self.shielded():
                os.rename(self.partial, final)
                base.fsync_dir(self.pl.region)
                self.partial = None
            self.pause("after_dir_rename")
            sha = self.install_manifest(manifest, "publish")
            s.update(pack_version=manifest["pack_version"], files={f["kind"]: f["version"] for f in manifest["files"]},
                     total_download_bytes=manifest["total_download_bytes"], manifest_sha256=sha,
                     mechanism="rename(2) of packs/mn/manifest.json")

    # ------------------------------------------------------------------ retention (AC 16, 17, 18)
    def retention(self):
        pl = self.pl
        with self.step("cleanup") as s:
            history = pl.read_history()
            served = pl.read_manifest()
            keep = self.referenced_paths(history, served)
            deleted = []
            for d in pl.version_dirs():
                for f in sorted(d.iterdir()):
                    rel = f"{d.name}/{f.name}"
                    if rel not in keep:
                        f.unlink()
                        deleted.append(rel)
                if not any(d.iterdir()):
                    d.rmdir()
                    deleted.append(f"{d.name}/")
            keep_copies = {Path(h.get("copy") or "").name for h in history[-self.pc.retain:]}
            for c in sorted(pl.copies.iterdir()):
                if c.name not in keep_copies:
                    c.unlink()
            used, _ = base.dir_usage(pl.packs, exclude=(".work",))
            s.update(retained_manifests=min(len(history), self.pc.retain), kept_files=sorted(keep), deleted=deleted or None,
                     packs_disk_bytes=used, free_disk_gb=round(base.free_gib(pl.packs), 2))
        return {"packs_disk_bytes": used}

    # ------------------------------------------------------------------ republish an older retained manifest
    def republish_clean(self, ok, reason):
        """Install a fresh copy of the newest retained manifest (other than the served one) for which ok(m) is true
        and whose files all exist. Returns the manifest or None."""
        pl = self.pl
        history = pl.read_history()
        served_sha = base.sha256_file(pl.manifest) if pl.manifest.exists() else None
        for h in reversed(history[-self.pc.retain:]):
            if h.get("sha256") == served_sha:
                continue
            m = base.read_json(pl.region / (h.get("copy") or "-"))
            if not m or not ok(m) or self.verify_served(m):
                continue
            self.install_manifest(m, reason)
            self.log.event("warn", f"pack: republished retained manifest ({reason})", pack_version=m.get("pack_version"),
                           published_at=m.get("published_at"))
            return m
        return None


def manifest_problems(m, region_dir, partial=None):
    """AC 7 rules beyond the JSON schema."""
    out = []
    files = m.get("files") or []
    kinds = [f.get("kind") for f in files]
    if sorted(kinds) != sorted(KINDS):
        out.append(f"kinds {kinds} (exactly one of each)")
        return out
    by = manifest_files(m)
    if by["routing"]["version"] != by["search"]["version"]:
        out.append("routing.version != search.version")
    if by["routing"]["version"] != m.get("pack_version"):
        out.append("routing.version != pack_version")
    if by["tiles"]["version"] > by["routing"]["version"]:
        out.append("tiles.version > routing.version")
    for f in files:
        if f.get("path") != f"{f.get('version')}/{FILE_NAME[f['kind']]}.gz" or not PATH_RE.match(f.get("path") or ""):
            out.append(f"{f['kind']}: path {f.get('path')}")
            continue
        p = region_dir / f["path"]
        if not p.is_file() and partial is not None and f["version"] == partial.name.replace(".partial", ""):
            p = partial / Path(f["path"]).name
        if not p.is_file():
            out.append(f"{f['kind']}: {f['path']} does not exist")
        elif p.stat().st_size != f.get("download_bytes"):
            out.append(f"{f['kind']}: size on disk {p.stat().st_size} != download_bytes {f.get('download_bytes')}")
        if not f.get("data_timestamp"):
            out.append(f"{f['kind']}: no data_timestamp")
    if m.get("total_bytes") != sum(f["bytes"] for f in files):
        out.append("total_bytes != sum(bytes)")
    if m.get("total_download_bytes") != sum(f["download_bytes"] for f in files):
        out.append("total_download_bytes != sum(download_bytes)")
    if "OpenStreetMap" not in (m.get("attribution") or ""):
        out.append("attribution lacks OpenStreetMap")
    if by["routing"].get("format", {}).get("graph_builder", "") in ("", "valhalla None"):
        out.append("routing.format.graph_builder unknown (build-info.json versions.valhalla)")
    return out


def validate_with_schema(python, manifest):
    """Validate against OfflinePackManifest in openapi.yaml with backend/scripts/validate_manifest.py (needs jsonschema
    + PyYAML: CONTRACT_PYTHON / backend/.venv). Returns '' when valid, else the problems."""
    r = subprocess.run([python, str(MANIFEST_VALIDATOR), "--spec", str(base.SPEC), "-"], input=json.dumps(manifest),
                       capture_output=True, text=True, timeout=60)
    if r.returncode == 0:
        return ""
    return (r.stdout or r.stderr).strip()[-400:] or f"validator exit {r.returncode}"


# ====================================================================== entry points used by nav_pipeline.py
def after_rebuild(p):
    """NAV-006 hook (A1 item 12): never raises, never changes the NAV-006 result."""
    try:
        pc = PackConfig(p.cfg)
    except base.ConfigError as e:
        p.log.event("error", "pack: configuration error; pack step not run", error=str(e))
        p.alert(RESULT_CODES["failed (build)"], "pack: failed (build)", "pack.config", f"configuration: {e}")
        return {"result": "failed (build)", "reason": f"configuration: {e}"}
    if not pc.enabled:
        p.log.event("info", "pack step disabled (PACK_ENABLED=0)")
        return {"result": "disabled"}
    rec = PackStep(p, "rebuild").run()
    return {k: rec.get(k) for k in ("result", "step", "reason", "pack_version")}


def publish_command(p, force=False, tiles=False):
    """`make pack-publish [FORCE=1] [TILES=1]`: same lock as make rebuild; reads only the active slot."""
    try:
        PackConfig(p.cfg)
    except base.ConfigError as e:
        print(json.dumps({"ts": base.iso(), "job": "nav-pipeline", "kind": "pack-publish", "level": "error",
                          "msg": str(e), "exit_code": 2}, ensure_ascii=False), flush=True)
        return 2
    p.begin()
    t0 = time.monotonic()
    rec = PackStep(p, "pack-publish").run(force=force, tiles=tiles)
    import signal
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    code = RESULT_CODES[rec["result"]]
    p.log.emit({"ts": base.iso(), "job": "nav-pipeline", "run_id": p.run_id, "kind": "pack-publish", "step": "summary",
                "result": f"pack: {rec['result']}", "exit_code": code, "failed_step": rec.get("step"),
                "reason": rec.get("reason"), "duration_s": round(time.monotonic() - t0, 1),
                "pack_version": rec.get("pack_version")})
    p.prune_logs()
    p.log.close()
    return code


def rollback_hook(p, rolled_back_slot):
    """AC 28 (Open question 1, working assumption (a)): after `make rollback` switched the pointer, replace the
    manifest when it refers to a file cut from the rolled-back slot. Never raises; the rollback result is unchanged."""
    try:
        pc = PackConfig(p.cfg)
        step = PackStep(p, "rollback")
        pl = step.pl
        served = pl.read_manifest()
        if served is None:
            return {"result": "rollback: no manifest published"}
        bad = sorted({f["path"] for f in manifest_files(served).values() if f.get("version") == rolled_back_slot})
        if not bad:
            p.log.event("info", "pack: rollback does not affect the published manifest", rolled_back=rolled_back_slot)
            return {"result": "rollback: unaffected"}
        t0 = time.monotonic()
        m = step.republish_clean(lambda m: all(f.get("version") != rolled_back_slot for f in manifest_files(m).values()),
                                 "rollback")
        if m:
            rec = {"run_id": p.run_id, "trigger": "rollback", "result": "rollback: republished", "step": None,
                   "reason": f"manifest referred to {', '.join(bad)} from rolled-back slot {rolled_back_slot}",
                   "pack_version": m.get("pack_version"), "at": base.iso(), "duration_s": round(time.monotonic() - t0, 1)}
            step.record(rec)
            p.log.step("pack.rollback", "ok", time.monotonic() - t0, republished=m.get("pack_version"),
                       rolled_back=rolled_back_slot)
            return {"result": "rollback: republished", "pack_version": m.get("pack_version")}
        reason = (f"no retained manifest without files from {rolled_back_slot} (last {pc.retain}); the manifest "
                  f"is unchanged")
        rec = {"run_id": p.run_id, "trigger": "rollback", "result": "rollback: no clean manifest", "step": "rollback",
               "reason": reason, "at": base.iso(), "duration_s": round(time.monotonic() - t0, 1)}
        step.record(rec)
        p.log.step("pack.rollback", "failed", time.monotonic() - t0, reason=reason)
        p.alert(RESULT_CODES["failed (build)"], "pack: rollback: no clean manifest", "pack.rollback", reason)
        return {"result": "rollback: no clean manifest"}
    except Exception as e:  # noqa: BLE001
        p.log.event("error", "pack: rollback hook failed; rollback result unchanged", error=f"{type(e).__name__}: {e}")
        return {"result": "rollback: hook error"}


def pack_status(cfg):
    """The `pack` object of `make status` (AC 24): reads only the manifest and state.json."""
    try:
        pc = PackConfig(cfg)
        region = pc.region
    except base.ConfigError as e:
        return {"config_error": str(e)}
    pl = PackLayout(cfg.root, region)
    m = pl.read_manifest()
    st = base.load_state(base.Layout(cfg.root))
    pack = st.get("pack") or {}
    doc = {"enabled": pc.enabled, "pack_version": (m or {}).get("pack_version"),
           "published_at": (m or {}).get("published_at"),
           "files": {f["kind"]: {"version": f.get("version"), "data_timestamp": f.get("data_timestamp"),
                                 "download_bytes": f.get("download_bytes")} for f in manifest_files(m).values()} or None,
           "total_download_bytes": (m or {}).get("total_download_bytes"),
           "last_result": pack.get("last_result"),
           "gate2_engine": pack.get("engine") or {"mode": pc.gate2_mode, "image": pc.gate2_image,
                                                  "valhalla_mobile_version": pc.mobile_version,
                                                  "wrapper_commit": pc.wrapper_commit,
                                                  "valhalla_commit": pc.valhalla_commit},
           "retained_manifests": len([h for h in pl.read_history()[-pc.retain:]])}
    return doc

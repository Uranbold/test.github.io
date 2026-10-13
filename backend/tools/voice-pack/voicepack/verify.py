"""Pack validation: schema + checks V1-V10 of pack-format §8, first failure wins (tasks B7, B9).

Fault ownership follows pack-format §8.1 (V2/V3/V4/V8, the integer and control-character reading rules) and the file
rules of §8.2 (no-follow checks, real path inside the pack directory, size caps: V2 MANIFEST_UNREADABLE, V9
FILE_INVALID). Policies: release and demo apply V5/V6 (no stub, voice in the D226 set); debug skips them. After V10
the generator-only check G1 UNLISTED_FILE refuses anything in the pack directory that the manifest does not list
(pack-format §1.2), links included; it is not a client reason code.
"""

from __future__ import annotations

import errno
import hashlib
import json
import os
import re
import stat
from pathlib import Path

from .audio import duration_ms
from .errors import PackInvalid
from .inventory import MANIFEST_SCHEMA, Entry, canonical_bytes, repo_root, sha256_hex

SUPPORTED_SCHEMA_VERSIONS = (1,)
POLICIES = ("release", "demo", "debug")
ALLOWED_VOICES = ("FEMALE5v2", "MALE4v2")
FILE_RE = re.compile(r"\Aclips/(dist|cont|instr|tail|join|fixed)(\.[A-Za-z0-9_]+)+\.(final|nonFinal)\.aac\Z")
# pack-format §8.2 size caps (bytes)
MANIFEST_CAP = 1_048_576
CLIP_CAP = 262_144
PACK_CAP = 6_291_456
# pack-format §8.1 V4: the codec members whose value, enum or type is a CODEC_UNSUPPORTED fault
CODEC_V4_MEMBERS = ("name", "container", "fileExtension", "channels", "sampleRateHz")
# schema "number" members (the only places a JSON number with a fraction or exponent may appear)
NUMBER_PATHS = {("processing", "silenceThresholdDbfs"), ("processing", "targetRmsDbfs"), ("processing", "peakCeilingDbfs")}
_CONTROL = re.compile(r"[\x00-\x1f]")


def load_schema(repo: Path | None = None) -> dict:
    return json.loads(((repo or repo_root()) / MANIFEST_SCHEMA).read_text(encoding="utf-8"))


def _validator_class():
    try:
        import jsonschema
    except ImportError:  # pragma: no cover - the reference image and backend/.venv have it
        raise SystemExit("jsonschema is required (pip install -r backend/tools/voice-pack/requirements.txt)")
    base = jsonschema.Draft202012Validator
    # pack-format §8.1: "integer" means a JSON number without fraction or exponent. json.loads gives float for 1.0 and
    # 1e3, so a strict checker (int, not bool) is exactly that rule; jsonschema's default would accept 1.0.
    checker = base.TYPE_CHECKER.redefine("integer", lambda _c, x: type(x) is int)
    return jsonschema.validators.extend(base, type_checker=checker)


def schema_errors(manifest, schema: dict) -> list:
    v = _validator_class()(schema)
    return sorted(v.iter_errors(manifest), key=lambda e: (list(map(str, e.absolute_path)), e.message))


def _fail(reason: str, detail: str):
    raise PackInvalid(reason, detail)


def _walk(value, path=()):
    yield path, value
    if isinstance(value, dict):
        for k, v in value.items():
            yield from _walk(v, (*path, k))
    elif isinstance(value, list):
        for i, v in enumerate(value):
            yield from _walk(v, (*path, i))


def _is_v4_path(path) -> bool:
    return len(path) == 2 and path[0] == "codec" and path[1] in CODEC_V4_MEMBERS


def _reading_rule_faults(m: dict) -> str | None:
    """pack-format §8.1 reading rules owned by V3: no control character in any string (keys included), and no JSON
    number with a fraction or exponent where the schema has an integer or an integer enum (only the three processing
    members are 'number'). A wrong type of the five V4 codec members is left to V4, schemaVersion to V2."""
    for path, v in _walk(m):
        where = "/".join(map(str, path)) or "(root)"
        if isinstance(v, dict):
            for k in v:
                if _CONTROL.search(k):
                    return f"{where}: member name with a control character"
        if isinstance(v, str) and _CONTROL.search(v):
            return f"{where}: string with a control character"  # every string, codec members too (§8.1)
        if isinstance(v, float) and tuple(path) not in NUMBER_PATHS and not _is_v4_path(path) and path != ("schemaVersion",):
            return f"{where}: number with a fraction or exponent where an integer is required"
    return None


def _read_capped(path: Path, cap: int) -> bytes | None:
    """Read a regular file without following a link, at most cap + 1 bytes. None if it is over the cap."""
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        chunks, n = [], 0
        while n <= cap:
            b = os.read(fd, min(1 << 16, cap + 1 - n))
            if not b:
                break
            chunks.append(b)
            n += len(b)
    finally:
        os.close(fd)
    data = b"".join(chunks)
    return None if len(data) > cap else data


def _lstat(p: Path):
    try:
        return os.lstat(p)
    except FileNotFoundError:
        return None
    except NotADirectoryError:
        return None


def _inside(real: str, root: str) -> bool:
    return real.startswith(root + os.sep)


def verify_pack(pack_dir: Path, *, policy: str = "release", expected_inventory_sha256: str | None = None,
                schema: dict | None = None) -> dict:
    """Return the manifest dict if the pack passes; raise PackInvalid(reason, detail) on the first failure."""
    if policy not in POLICIES:
        raise ValueError(f"policy must be one of {POLICIES}")
    # §8.2: the directory given may be reached through a link; nothing below it may be.
    root = Path(os.path.realpath(pack_dir))
    root_s = str(root)
    mpath = root / "manifest.json"
    # V1 (no-follow: a dangling link is "present")
    mst = _lstat(mpath)
    if mst is None:
        _fail("NO_PACK", f"{mpath.name} not found in the pack directory")
    # V2: a regular file within the cap, before it is parsed
    if not stat.S_ISREG(mst.st_mode):
        _fail("MANIFEST_UNREADABLE", "manifest.json is not a regular file (link or special file)")
    if mst.st_size > MANIFEST_CAP:
        _fail("MANIFEST_UNREADABLE", f"manifest.json is {mst.st_size} bytes (cap {MANIFEST_CAP})")
    try:
        raw = _read_capped(mpath, MANIFEST_CAP)
    except OSError as e:
        reason = "a link" if e.errno == errno.ELOOP else (e.strerror or type(e).__name__)
        _fail("MANIFEST_UNREADABLE", f"manifest.json cannot be read ({reason})")
    if raw is None:
        _fail("MANIFEST_UNREADABLE", f"manifest.json is over the cap of {MANIFEST_CAP} bytes")
    try:
        m = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as e:
        _fail("MANIFEST_UNREADABLE", f"manifest.json is not JSON ({type(e).__name__})")
    if not isinstance(m, dict):
        _fail("MANIFEST_UNREADABLE", f"the top-level JSON value is not an object ({type(m).__name__})")
    if "schemaVersion" not in m:
        _fail("SCHEMA_UNSUPPORTED", "schemaVersion missing")
    sv = m["schemaVersion"]
    if type(sv) is not int or sv not in SUPPORTED_SCHEMA_VERSIONS:
        _fail("SCHEMA_UNSUPPORTED", f"schemaVersion {json.dumps(sv)[:20]} not in {list(SUPPORTED_SCHEMA_VERSIONS)}")
    # V3: the reading rules, then every schema rule except the V4 codec values and the V8 file pattern.
    fault = _reading_rule_faults(m)
    if fault:
        _fail("MANIFEST_UNREADABLE", fault)
    errs = schema_errors(m, schema if schema is not None else load_schema())

    def deferred(e) -> bool:
        path = list(e.absolute_path)
        if _is_v4_path(path):
            return True  # wrong const, enum value or type of the five V4 members
        return len(path) == 3 and path[0] == "entries" and path[2] == "file" and e.validator == "pattern"

    errs = [e for e in errs if not deferred(e)]
    if errs:
        e = errs[0]
        where = "/".join(map(str, e.absolute_path)) or "(root)"
        _fail("MANIFEST_UNREADABLE", f"schema: {where}: {e.message[:200]}")
    # V4 (strict types: 1.0 or true is not 1)
    c = m["codec"]
    if not (c["name"] == "aac-lc" and c["container"] == "adts" and c["fileExtension"] == ".aac"
            and type(c["channels"]) is int and c["channels"] == 1
            and type(c["sampleRateHz"]) is int and c["sampleRateHz"] in (16000, 22050)):
        _fail("CODEC_UNSUPPORTED", "codec " + "/".join(json.dumps(c[k], ensure_ascii=True)[:24] for k in CODEC_V4_MEMBERS))
    # V5, V6
    if policy in ("release", "demo"):
        if m["source"]["vendor"] == "stub":
            _fail("STUB_NOT_ALLOWED", f"source.vendor is stub (policy {policy})")
        if m["voiceId"] not in ALLOWED_VOICES:
            _fail("VOICE_NOT_ALLOWED", f"voiceId {m['voiceId']} not in {list(ALLOWED_VOICES)} (policy {policy})")
    # V7
    entries = [Entry(e["key"], e["take"], e["spokenText"]) for e in m["entries"]]
    ordered = sorted(entries, key=lambda e: e.sort_key)
    if entries != ordered:
        _fail("INVENTORY_MISMATCH", "entries are not sorted by key then take")
    if len({(e.key, e.take) for e in entries}) != len(entries):
        _fail("INVENTORY_MISMATCH", "duplicate (key, take) in entries")
    recomputed = sha256_hex(canonical_bytes(entries))
    if recomputed != m["inventorySha256"]:
        _fail("INVENTORY_MISMATCH", f"entries hash to {recomputed[:12]}..., manifest says {m['inventorySha256'][:12]}...")
    if expected_inventory_sha256 is not None and m["inventorySha256"] != expected_inventory_sha256:
        _fail("INVENTORY_MISMATCH", f"inventorySha256 {m['inventorySha256'][:12]}... differs from the expected {expected_inventory_sha256[:12]}...")
    # V8
    rate = c["sampleRateHz"]
    delay = c["encoderDelayFrames"]
    for e in m["entries"]:
        f = e["file"]
        if not FILE_RE.match(f) or ".." in f or f.startswith("/"):
            _fail("ENTRY_INVALID", f"{e['key']} {e['take']}: file {json.dumps(f)[:80]}")
        if e["durationMs"] != duration_ms(e["frames"], rate):
            _fail("ENTRY_INVALID", f"{e['key']} {e['take']}: durationMs {e['durationMs']} != round-half-up(frames x 1000 / rate)")
        if e["frames"] > e["encodedFrames"] - delay:
            _fail("ENTRY_INVALID", f"{e['key']} {e['take']}: frames {e['frames']} > encodedFrames - encoderDelayFrames")
    # V9 (§8.2): clips/ not a link; every listed file present (no-follow), a regular file, real path inside the pack,
    # within the per-clip cap; then the total.
    cst = _lstat(root / "clips")
    if cst is not None and not stat.S_ISDIR(cst.st_mode):
        _fail("FILE_INVALID", "clips is not a directory (link or file)")
    sizes: dict[str, int] = {}
    for e in m["entries"]:
        f = e["file"]
        p = root / f
        st = _lstat(p)
        if st is None:
            _fail("FILE_MISSING", f)
        if not stat.S_ISREG(st.st_mode):
            _fail("FILE_INVALID", f"{f}: not a regular file (link or special file)")
        if not _inside(os.path.realpath(p), root_s):
            _fail("FILE_INVALID", f"{f}: real path outside the pack directory")
        if st.st_size > CLIP_CAP:
            _fail("FILE_INVALID", f"{f}: {st.st_size} bytes (cap {CLIP_CAP})")
        sizes[f] = st.st_size
    total = len(raw) + sum(sizes.values())
    if total > PACK_CAP:
        _fail("FILE_INVALID", f"pack total {total} bytes (cap {PACK_CAP})")
    # V10 (bounded, no-follow reads; a size change since V9 is FILE_INVALID)
    for e in m["entries"]:
        f = e["file"]
        try:
            data = _read_capped(root / f, CLIP_CAP)
        except OSError:
            _fail("FILE_INVALID", f"{f}: cannot be read without following a link")
        if data is None or len(data) != sizes[f]:
            _fail("FILE_INVALID", f"{f}: size changed while verifying")
        if hashlib.sha256(data).hexdigest() != e["sha256"]:
            _fail("FILE_HASH_MISMATCH", f)
    # G1 (generator only): nothing else in the pack directory, links included (never followed)
    listed = {"manifest.json"} | {e["file"] for e in m["entries"]}
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        rel_dir = Path(dirpath).relative_to(root).as_posix()
        for d in sorted(dirnames):
            rel = d if rel_dir == "." else f"{rel_dir}/{d}"
            if rel != "clips":
                _fail("UNLISTED_FILE", f"directory or link {rel}")
        for fn in sorted(filenames):
            rel = fn if rel_dir == "." else f"{rel_dir}/{fn}"
            if rel not in listed:
                _fail("UNLISTED_FILE", rel)
    return m

"""Pack validation: schema + checks V1-V10 of pack-format §8, first failure wins (tasks B7, B9).

Policies: release and demo apply V5/V6 (no stub, voice in the D226 set); debug skips them. The generator also refuses
files in the pack that the manifest does not list (pack-format §1.2: "only manifest.json and the clips/ files listed
in it"); that generator-side check runs after V10 and reports UNLISTED_FILE (not a client reason code).
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

from .audio import duration_ms
from .errors import PackInvalid
from .inventory import MANIFEST_SCHEMA, Entry, canonical_bytes, repo_root, sha256_hex

SUPPORTED_SCHEMA_VERSIONS = (1,)
POLICIES = ("release", "demo", "debug")
ALLOWED_VOICES = ("FEMALE5v2", "MALE4v2")
FILE_RE = re.compile(r"^clips/(dist|cont|instr|tail|join|fixed)(\.[A-Za-z0-9_]+)+\.(final|nonFinal)\.aac$")


def load_schema(repo: Path | None = None) -> dict:
    return json.loads(((repo or repo_root()) / MANIFEST_SCHEMA).read_text(encoding="utf-8"))


def schema_errors(manifest, schema: dict) -> list:
    try:
        import jsonschema
    except ImportError:  # pragma: no cover - the reference image and backend/.venv have it
        raise SystemExit("jsonschema is required (pip install -r backend/tools/voice-pack/requirements.txt)")
    v = jsonschema.Draft202012Validator(schema)
    return sorted(v.iter_errors(manifest), key=lambda e: (list(map(str, e.absolute_path)), e.message))


def _fail(reason: str, detail: str):
    raise PackInvalid(reason, detail)


def verify_pack(pack_dir: Path, *, policy: str = "release", expected_inventory_sha256: str | None = None,
                schema: dict | None = None) -> dict:
    """Return the manifest dict if the pack passes; raise PackInvalid(reason, detail) on the first failure."""
    if policy not in POLICIES:
        raise ValueError(f"policy must be one of {POLICIES}")
    pack_dir = Path(pack_dir)
    mpath = pack_dir / "manifest.json"
    # V1
    if not mpath.is_file():
        _fail("NO_PACK", f"{mpath.name} not found in the pack directory")
    raw = mpath.read_bytes()
    # V2
    try:
        m = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        _fail("MANIFEST_UNREADABLE", f"manifest.json is not JSON ({type(e).__name__})")
    if not isinstance(m, dict) or "schemaVersion" not in m:
        _fail("SCHEMA_UNSUPPORTED", "schemaVersion missing")
    sv = m.get("schemaVersion")
    if not isinstance(sv, int) or isinstance(sv, bool) or sv not in SUPPORTED_SCHEMA_VERSIONS:
        _fail("SCHEMA_UNSUPPORTED", f"schemaVersion {sv!r} not in {list(SUPPORTED_SCHEMA_VERSIONS)}")
    # V3. Two kinds of schema error are left to the later check that names them precisely: codec const/enum (V4)
    # and the entry file pattern (V8).
    errs = schema_errors(m, schema if schema is not None else load_schema())

    def deferred(e) -> bool:
        path = list(e.absolute_path)
        if path[:1] == ["codec"] and e.validator in ("const", "enum"):
            return True
        return len(path) == 3 and path[0] == "entries" and path[2] == "file" and e.validator == "pattern"

    errs = [e for e in errs if not deferred(e)]
    if errs:
        e = errs[0]
        where = "/".join(map(str, e.absolute_path)) or "(root)"
        _fail("MANIFEST_UNREADABLE", f"schema: {where}: {e.message[:200]}")
    # V4
    c = m["codec"]
    if not (c.get("name") == "aac-lc" and c.get("container") == "adts" and c.get("fileExtension") == ".aac"
            and c.get("channels") == 1 and c.get("sampleRateHz") in (16000, 22050)):
        _fail("CODEC_UNSUPPORTED", f"codec {c.get('name')}/{c.get('container')}/{c.get('channels')} ch/{c.get('sampleRateHz')} Hz")
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
            _fail("ENTRY_INVALID", f"{e['key']} {e['take']}: file {f!r}")
        if e["durationMs"] != duration_ms(e["frames"], rate):
            _fail("ENTRY_INVALID", f"{e['key']} {e['take']}: durationMs {e['durationMs']} != round-half-up(frames x 1000 / rate)")
        if e["frames"] > e["encodedFrames"] - delay:
            _fail("ENTRY_INVALID", f"{e['key']} {e['take']}: frames {e['frames']} > encodedFrames - encoderDelayFrames")
    # V9
    for e in m["entries"]:
        if not (pack_dir / e["file"]).is_file():
            _fail("FILE_MISSING", e["file"])
    # V10
    for e in m["entries"]:
        if hashlib.sha256((pack_dir / e["file"]).read_bytes()).hexdigest() != e["sha256"]:
            _fail("FILE_HASH_MISMATCH", e["file"])
    # generator-side: nothing else in the pack
    listed = {"manifest.json"} | {e["file"] for e in m["entries"]}
    for p in sorted(pack_dir.rglob("*")):
        rel = p.relative_to(pack_dir).as_posix()
        if p.is_dir():
            if rel != "clips":
                _fail("UNLISTED_FILE", f"directory {rel}")
        elif rel not in listed:
            _fail("UNLISTED_FILE", rel)
    return m

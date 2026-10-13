"""Shared test helpers: temporary inventory checkouts, the CLI in-process, ffmpeg discovery, one shared stub pack."""

from __future__ import annotations

import atexit
import contextlib
import io
import shutil
import tempfile
import unittest
from pathlib import Path

from voicepack import redact
from voicepack.__main__ import main
from voicepack.codec import PINNED_FFMPEG_VERSION, Ffmpeg
from voicepack.errors import ConfigError
from voicepack.inventory import DISTANCE_TSV, GLOSSARY_MD, INVENTORY_TSV, MANIFEST_SCHEMA, repo_root

REPO = repo_root()
SMALL_DIST = ["dist.m.30", "dist.m.200", "dist.m.300", "dist.km.1_5", "cont.km.2"]
SMALL_INV = ["instr.turn.right", "instr.uturn", "join.then", "tail.approach", "fixed.offRoute"]
SMALL_ENTRIES = 12  # 5 distance + 5 inventory finals + 2 nonFinal


def ffmpeg_args() -> list[str]:
    """--ffmpeg arguments for the local ffmpeg, or SkipTest when there is none."""
    try:
        ff = Ffmpeg(allow_any_version=True)
    except ConfigError as e:
        raise unittest.SkipTest(f"no ffmpeg: {e}")
    args = ["--ffmpeg", ff.exe]
    if ff.version != PINNED_FFMPEG_VERSION:
        args.append("--ffmpeg-any-version")
    return args


def ffmpeg() -> Ffmpeg:
    try:
        return Ffmpeg(allow_any_version=True)
    except ConfigError as e:
        raise unittest.SkipTest(f"no ffmpeg: {e}")


def _filter_tsv(data: bytes, keep: list[str] | None) -> bytes:
    if keep is None:
        return data
    lines = data.decode("utf-8").split("\n")
    out = [lines[0]] + [ln for ln in lines[1:] if ln and ln.split("\t", 1)[0] in keep]
    return ("\n".join(out) + "\n").encode("utf-8")


def make_repo(root: Path, *, dist: list[str] | None = SMALL_DIST, inv: list[str] | None = SMALL_INV,
              edit_dist=None, edit_inv=None) -> Path:
    """A minimal checkout with the four files the generator reads. dist/inv = keys to keep (None = all rows);
    edit_* = callables str -> str applied to the file text."""
    root.mkdir(parents=True, exist_ok=True)
    for rel in (GLOSSARY_MD, MANIFEST_SCHEMA):
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(REPO / rel, root / rel)
    for rel, keep, edit in ((DISTANCE_TSV, dist, edit_dist), (INVENTORY_TSV, inv, edit_inv)):
        data = _filter_tsv((REPO / rel).read_bytes(), keep)
        if edit is not None:
            data = edit(data.decode("utf-8")).encode("utf-8")
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_bytes(data)
    return root


def run_cli(argv: list[str], synth_factory=None) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        rc = main(argv, synth_factory=synth_factory)
    return rc, out.getvalue(), err.getvalue()


def build_argv(repo: Path, out: Path, *, synth: str = "stub", voice: str = "FEMALE5v2", version: int = 1,
               extra: list[str] | None = None, jobs: int = 1) -> list[str]:
    return ["build", "--voice", voice, "--pack-version", str(version), "--synth", synth, "--out", str(out),
            "--repo", str(repo), "--jobs", str(jobs), *ffmpeg_args(), *(extra or [])]


def tree_hashes(root: Path) -> dict[str, str]:
    import hashlib

    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(root.rglob("*")) if p.is_file()}


# ----------------------------------------------------------------------------------------------- shared full pack
_shared: dict = {}


def _tmp_root() -> Path:
    if "tmp" not in _shared:
        _shared["tmp"] = tempfile.mkdtemp(prefix="voicepack-shared-")
        atexit.register(shutil.rmtree, _shared["tmp"], True)
    return Path(_shared["tmp"])


def shared_full_pack(index: int = 0) -> Path:
    """The run directory (<out>/FEMALE5v2-v1) of a full 304-entry stub build from the real inputs; built once per
    test process. index 1 is a second, independent build into another directory (determinism check)."""
    key = f"full{index}"
    if key not in _shared:
        out = _tmp_root() / f"out{index}"
        rc, so, se = run_cli(build_argv(REPO, out, jobs=4))
        if rc != 0:
            raise AssertionError(f"shared stub build failed rc={rc}\n{so}\n{se}")
        _shared[key] = out / "FEMALE5v2-v1"
        _shared[key + "-stdout"] = so
    return _shared[key]


def scratch() -> Path:
    return Path(tempfile.mkdtemp(prefix="voicepack-test-", dir=_tmp_root()))


def clear_secrets():
    redact.clear()

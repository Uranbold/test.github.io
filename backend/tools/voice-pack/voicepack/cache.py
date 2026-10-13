"""Vendor WAV cache (NAV-016 AC 10, ADR-0010 §4.2; task B4) and the output/cache path rule (pack-format §1.2).

Key: SHA-256 of the canonical JSON of {vendor, apiVersion, voice, sampleRateHz, speed, pitch, takeMethod,
requestText}. takeMethod is the method id for nonFinal takes and "none" for final takes (whose request text is the
spoken text alone), so switching the take method never re-requests the final takes. Value: the raw WAV, written to a
temporary file and renamed. index.jsonl holds the key fields per entry and never the token.
"""

from __future__ import annotations

import hashlib
import json
import os
import secrets
import subprocess
import threading
from pathlib import Path

from .errors import ConfigError

CACHE_ENV = "NAV_VOICE_PACK_CACHE"
OUT_ENV = "NAV_VOICE_PACK_OUT"
NO_TAKE_METHOD = "none"


def default_cache_dir() -> Path:
    env = os.environ.get(CACHE_ENV)
    if env:
        return Path(env)
    return Path.home() / ".cache" / "navmn" / "voice-pack"


def cache_fields(*, vendor: str, api_version: str, voice: str, sample_rate: int, speed: str, pitch: str,
                 take_method: str, request_text: str) -> dict:
    return {
        "vendor": vendor,
        "apiVersion": api_version,
        "voice": voice,
        "sampleRateHz": sample_rate,
        "speed": speed,
        "pitch": pitch,
        "takeMethod": take_method,
        "requestText": request_text,
    }


def cache_key(fields: dict) -> str:
    canon = json.dumps(fields, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(canon).hexdigest()


class Cache:
    """root=None keeps everything in memory (stub runs without --cache)."""

    def __init__(self, root: Path | None):
        self.root = Path(root) if root is not None else None
        self._mem: dict[str, bytes] = {}
        self._lock = threading.Lock()
        if self.root is not None:
            (self.root / "wav").mkdir(parents=True, exist_ok=True)

    def _path(self, key: str) -> Path:
        assert self.root is not None
        return self.root / "wav" / f"{key}.wav"

    def get(self, key: str) -> bytes | None:
        if self.root is None:
            return self._mem.get(key)
        p = self._path(key)
        try:
            return p.read_bytes()
        except FileNotFoundError:
            return None

    def put(self, key: str, fields: dict, data: bytes) -> None:
        if self.root is None:
            self._mem[key] = data
            return
        final = self._path(key)
        tmp = final.with_name(f".{key}.tmp-{secrets.token_hex(6)}")
        with open(tmp, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, final)
        rec = {"cacheKey": key, **fields, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        line = json.dumps(rec, ensure_ascii=False, sort_keys=True) + "\n"
        with self._lock, open(self.root / "index.jsonl", "a", encoding="utf-8") as f:
            f.write(line)


def check_path_allowed(path: Path, repo: Path, label: str) -> Path:
    """Refuse a path inside the repository unless `git check-ignore` reports it ignored (checked on a probe child,
    so directory patterns such as `out/` match a directory that does not exist yet)."""
    p = Path(os.path.abspath(os.path.expanduser(str(path))))
    try:
        real = p.resolve()
    except OSError:
        real = p
    repo_real = repo.resolve()
    inside = real == repo_real or repo_real in real.parents
    if not inside:
        return p
    probe = real / ".voicepack-probe"
    try:
        r = subprocess.run(["git", "-C", str(repo_real), "check-ignore", "-q", "--", str(probe)],
                           capture_output=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        raise ConfigError(f"{label} is inside the repository and git is not available to confirm it is ignored") from None
    if r.returncode != 0:
        raise ConfigError(f"{label} {p} is inside the repository and not git-ignored (pack-format §1.2); use a path outside it")
    return p

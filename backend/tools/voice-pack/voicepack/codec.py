"""AAC-LC in raw ADTS with the pinned ffmpeg; encoder-delay measurement; decode check; the trim rule.

pack-format §4 (codec), §5 (trim rule); ADR-0010 §3.3; task B6. ffmpeg is the only external binary.
"""

from __future__ import annotations

import math
import operator
import os
import re
import shutil
import subprocess
import sys
from array import array
from dataclasses import dataclass

from .audio import check_failed
from .errors import AudioCheckError, ConfigError

PINNED_FFMPEG_VERSION = "7.0.2"
EXPECTED_ENCODER_DELAY = 1024
AAC_FRAME = 1024
BITRATES_KBPS = (24, 28, 32)
CLIP_RATES = (22050, 16000)
FFMPEG_ENV = "NAV_VOICE_PACK_FFMPEG"
ALIGN_TOLERANCE = 2  # frames (task B6: lag 0 +- 2)
ALIGN_SEARCH = 32  # lags searched on each side
ADTS_RATES = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350]


class DecodeFailed(Exception):
    """pack-format §5 DECODE_FAILED."""


def _le(a: array) -> bytes:
    b = array("h", a)
    if sys.byteorder == "big":
        b.byteswap()
    return b.tobytes()


def _from_le(data: bytes) -> array:
    a = array("h")
    a.frombytes(data[: len(data) - (len(data) % 2)])
    if sys.byteorder == "big":
        a.byteswap()
    return a


@dataclass(frozen=True)
class AdtsInfo:
    access_units: int
    sample_rate: int
    channels: int
    profile: int  # audio object type - 1 (1 = AAC LC)

    @property
    def encoded_frames(self) -> int:
        return self.access_units * AAC_FRAME


def parse_adts(data: bytes) -> AdtsInfo:
    pos = 0
    units = 0
    rate = ch = prof = None
    n = len(data)
    while pos < n:
        if pos + 7 > n or data[pos] != 0xFF or (data[pos + 1] & 0xF6) != 0xF0:
            raise ValueError(f"no ADTS sync at byte {pos}")
        p = (data[pos + 2] >> 6) & 0x3
        sfi = (data[pos + 2] >> 2) & 0xF
        c = ((data[pos + 2] & 0x1) << 2) | (data[pos + 3] >> 6)
        flen = ((data[pos + 3] & 0x3) << 11) | (data[pos + 4] << 3) | (data[pos + 5] >> 5)
        blocks = (data[pos + 6] & 0x3) + 1
        if flen < 7 or pos + flen > n:
            raise ValueError(f"bad ADTS frame length at byte {pos}")
        if sfi >= len(ADTS_RATES):
            raise ValueError("bad ADTS sampling frequency index")
        if rate is None:
            rate, ch, prof = ADTS_RATES[sfi], c, p
        elif (ADTS_RATES[sfi], c, p) != (rate, ch, prof):
            raise ValueError("ADTS parameters change inside the stream")
        units += blocks
        pos += flen
    if not units:
        raise ValueError("empty ADTS stream")
    return AdtsInfo(units, rate, ch, prof)


def trim_rule(decoded: array, r_out: int, clip_rate: int, delay: int, frames: int, encoded_frames: int) -> array:
    """pack-format §5 for one entry. `decoded` is channel 0 at r_out. Returns exactly frames x k samples."""
    if r_out == clip_rate:
        k = 1
    elif r_out == 2 * clip_rate:
        k = 2
    else:
        raise DecodeFailed(f"decoder rate {r_out} is not 1x or 2x {clip_rate}")
    p, f, r = delay * k, frames * k, encoded_frames * k
    d = len(decoded)
    missing = r - d
    if -2 <= missing < 0:
        missing = 0
    if not (0 <= missing <= p):
        raise DecodeFailed(f"decoded {d} frames, expected {r} (missing {missing} outside 0..{p})")
    start = p - missing
    out = array("h", decoded[start : start + f])
    short = f - len(out)
    if short > 0:
        if short > r_out * 10 // 1000:
            raise DecodeFailed(f"{short} frames short (more than 10 ms)")
        out.extend([0] * short)
    return out


def best_lag(reference, other, max_lag: int = ALIGN_SEARCH, window: int = 4096) -> int:
    """Lag (frames) at which `other` best matches `reference` (other[i + lag] ~ reference[i]), searched in
    -max_lag..max_lag over the loudest window of the reference."""
    n = min(len(reference), len(other))
    length = min(window, n - 2 * max_lag)
    if length <= 64:
        raise ValueError("clip too short for the alignment check")
    best_s, best_e = max_lag, -1.0
    step = 256
    for s in range(max_lag, n - max_lag - length + 1, step):
        seg = reference[s : s + length]
        e = float(sum(map(operator.mul, seg, seg)))
        if e > best_e:
            best_s, best_e = s, e
    ref = reference[best_s : best_s + length]
    best_lag_v, best_c = 0, -math.inf
    for lag in sorted(range(-max_lag, max_lag + 1), key=abs):
        c = float(sum(map(operator.mul, ref, other[best_s + lag : best_s + lag + length])))
        if c > best_c:
            best_lag_v, best_c = lag, c
    return best_lag_v


class Ffmpeg:
    def __init__(self, path: str | None = None, *, allow_any_version: bool = False):
        exe = path or os.environ.get(FFMPEG_ENV) or shutil.which("ffmpeg")
        if not exe:
            raise ConfigError(f"ffmpeg not found (set --ffmpeg or {FFMPEG_ENV}, or use the reference image: make voice-pack-image)")
        self.exe = exe
        try:
            out = subprocess.run([exe, "-hide_banner", "-version"], capture_output=True, check=True, timeout=30).stdout
        except (OSError, subprocess.SubprocessError) as e:
            raise ConfigError(f"ffmpeg at {exe!r} does not run ({type(e).__name__})") from None
        m = re.search(rb"ffmpeg version (\S+)", out)
        raw = m.group(1).decode("ascii", "replace") if m else "unknown"
        self.version = re.sub(r"[^A-Za-z0-9._+-]", "", raw)[:32] or "unknown"
        if self.version != PINNED_FFMPEG_VERSION and not allow_any_version:
            raise ConfigError(f"ffmpeg {self.version} is not the pinned {PINNED_FFMPEG_VERSION} (use the reference image, "
                              "or --ffmpeg-any-version for a development run without byte identity)")
        enc = subprocess.run([exe, "-hide_banner", "-encoders"], capture_output=True, timeout=30).stdout
        if not re.search(rb"^\s*A\S*\s+aac\s", enc, re.MULTILINE):
            raise ConfigError("ffmpeg has no native aac encoder")

    @classmethod
    def trusted(cls, exe: str, version: str) -> "Ffmpeg":
        """An instance for worker processes, after the parent has checked the binary."""
        obj = cls.__new__(cls)
        obj.exe = exe
        obj.version = version
        return obj

    @property
    def encoder_name(self) -> str:
        return f"ffmpeg-{self.version}-aac"[:48]

    def _run(self, args: list[str], data: bytes) -> bytes:
        cmd = [self.exe, "-hide_banner", "-nostats", "-loglevel", "error", *args]
        p = subprocess.run(cmd, input=data, capture_output=True, timeout=120)
        if p.returncode != 0:
            msg = p.stderr.decode("utf-8", "replace").strip().splitlines()
            raise AudioCheckError(f"ffmpeg failed ({p.returncode}): {msg[-1] if msg else ''}")
        return p.stdout

    def encode(self, samples: array, rate: int, bitrate_kbps: int) -> bytes:
        return self._run(
            ["-f", "s16le", "-ar", str(rate), "-ac", "1", "-i", "pipe:0",
             "-c:a", "aac", "-b:a", f"{bitrate_kbps}k", "-ac", "1", "-ar", str(rate),
             "-flags", "+bitexact", "-fflags", "+bitexact", "-map_metadata", "-1", "-f", "adts", "pipe:1"],
            _le(samples),
        )

    def decode(self, adts: bytes) -> tuple[int, array]:
        """Decode the whole stream with ffmpeg's native decoder (no trimming for ADTS). Returns (rate, channel 0)."""
        info = parse_adts(adts)
        raw = self._run(["-f", "aac", "-i", "pipe:0", "-flags", "+bitexact", "-c:a", "pcm_s16le", "-f", "s16le", "pipe:1"], adts)
        pcm = _from_le(raw)
        ch = max(1, info.channels)
        if ch > 1:
            pcm = pcm[0::ch]
        return info.sample_rate, pcm

    def resample(self, samples: array, src: int, dst: int) -> array:
        if src == dst:
            return array("h", samples)
        raw = self._run(["-f", "s16le", "-ar", str(src), "-ac", "1", "-i", "pipe:0", "-flags", "+bitexact",
                         "-ar", str(dst), "-f", "s16le", "pipe:1"], _le(samples))
        return _from_le(raw)

    def measure_encoder_delay(self, rate: int, bitrate_kbps: int) -> int:
        """Encode a Hann-windowed chirp at a known position, decode, find the shift (task B6)."""
        n, pos, blen = 8192, 3000, 512
        sig = [0.0] * n
        for i in range(blen):
            t = i / rate
            f0, f1 = 300.0, 3000.0
            ph = 2 * math.pi * (f0 * t + (f1 - f0) * t * t * rate / (2 * blen))
            sig[pos + i] = 16000 * (0.5 - 0.5 * math.cos(2 * math.pi * i / (blen - 1))) * math.sin(ph)
        src = array("h", (round(v) for v in sig))
        _, dec = self.decode(self.encode(src, rate, bitrate_kbps))
        burst = src[pos : pos + blen]
        best, best_c = 0, -math.inf
        for lag in range(0, min(4096, len(dec) - pos - blen)):
            c = float(sum(map(operator.mul, burst, dec[pos + lag : pos + lag + blen])))
            if c > best_c:
                best, best_c = lag, c
        return best


@dataclass(frozen=True)
class Encoded:
    data: bytes
    frames: int
    encoded_frames: int


def encode_and_check(ff: Ffmpeg, key: str, pcm: array, rate: int, bitrate_kbps: int, delay: int) -> Encoded:
    """Encode one clip and run the decode check: ADTS AAC-LC mono at `rate`; D == encodedFrames; trim rule gives
    exactly `frames`; onset lag 0 +- 2 frames against the pre-encode PCM."""
    data = ff.encode(pcm, rate, bitrate_kbps)
    return decode_check(ff, key, data, pcm, rate, delay)


def decode_check(ff: Ffmpeg, key: str, data: bytes, pcm: array, rate: int, delay: int) -> Encoded:
    try:
        info = parse_adts(data)
    except ValueError as e:
        raise check_failed(key, "decode-adts", str(e)) from None
    if info.profile != 1 or info.channels != 1 or info.sample_rate != rate:
        raise check_failed(key, "decode-adts", f"profile {info.profile}, {info.channels} ch, {info.sample_rate} Hz (want AAC-LC mono {rate} Hz)")
    r = info.encoded_frames
    r_out, dec = ff.decode(data)
    if len(dec) != r:
        raise check_failed(key, "decode-length", f"decoded {len(dec)} frames, encodedFrames {r}")
    try:
        trimmed = trim_rule(dec, r_out, rate, delay, len(pcm), r)
    except DecodeFailed as e:
        raise check_failed(key, "decode-trim", str(e)) from None
    if len(trimmed) != len(pcm):
        raise check_failed(key, "decode-trim", f"trim rule gave {len(trimmed)} frames, frames {len(pcm)}")
    try:
        lag = best_lag(pcm, trimmed)
    except ValueError as e:
        raise check_failed(key, "decode-alignment", str(e)) from None
    if abs(lag) > ALIGN_TOLERANCE:
        raise check_failed(key, "decode-alignment", f"onset lag {lag} frames after the trim rule (allowed 0 +- {ALIGN_TOLERANCE})")
    return Encoded(data=data, frames=len(pcm), encoded_frames=r)

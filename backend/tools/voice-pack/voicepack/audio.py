"""WAV I/O, 10 ms window analysis, clip processing and checks, and the carrier-then-v1 cut.

pack-format §4 (processing order and checks), ADR-0010 §4.3 (nonFinal cut); task B5.

Windows: W = floor(sampleRate / 100) samples (220 at 22,050 Hz, 160 at 16,000 Hz), tiled from the first sample; a
shorter last window counts as a window. dBFS is relative to 32768 (a full-scale square wave is 0 dBFS).
"""

from __future__ import annotations

import math
import operator
import struct
import sys
from array import array
from dataclasses import dataclass

from .errors import AudioCheckError

# Literal constants written to manifest.processing (pack-format §3: the generator's literal constants).
SILENCE_THRESHOLD_DBFS = -45
EDGE_MARGIN_MS = 5
FADE_MS = 10
TARGET_RMS_DBFS = -20
PEAK_CEILING_DBFS = -1
PROCESSING = {
    "silenceThresholdDbfs": SILENCE_THRESHOLD_DBFS,
    "edgeMarginMs": EDGE_MARGIN_MS,
    "fadeMs": FADE_MS,
    "targetRmsDbfs": TARGET_RMS_DBFS,
    "peakCeilingDbfs": PEAK_CEILING_DBFS,
}

# Checks (pack-format §4; NAV-016 AC 14).
MAX_EDGE_SILENCE_MS = 40
MIN_DURATION_MS = 150
MAX_DURATION_MS = 4000
MAX_INTERNAL_SILENCE_MS = 500
RMS_TOLERANCE_DB = 2.0

# carrier-then-v1 (ADR-0010 §4.3).
CUT_MIN_GAP_MS = 60
CUT_MIN_BEFORE_END_MS = 250
CUT_TAIL_RATIO = (0.5, 1.6)
CUT_MAX_EXTRA_MS = 300

FULL_SCALE = 32768.0
PEAK_CEILING = int(FULL_SCALE * 10 ** (PEAK_CEILING_DBFS / 20))  # 29204


def check_failed(key: str, rule: str, detail: str) -> AudioCheckError:
    return AudioCheckError(f"{key}: rule {rule}: {detail}")


# ----------------------------------------------------------------------------------------------- WAV
@dataclass(frozen=True)
class Wav:
    rate: int
    channels: int
    bits: int
    format_tag: int
    samples: array  # interleaved int16 when bits == 16


_PCM_GUID_TAIL = b"\x00\x00\x00\x00\x10\x00\x80\x00\x00\xaa\x00\x38\x9b\x71"


def read_wav(data: bytes) -> Wav:
    """Parse a RIFF/WAVE file. Raises ValueError with a short reason on anything that is not one."""
    if len(data) < 12 or data[0:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError("not RIFF/WAVE")
    pos = 12
    fmt = None
    pcm = None
    while pos + 8 <= len(data):
        cid = data[pos : pos + 4]
        size = struct.unpack_from("<I", data, pos + 4)[0]
        body_start = pos + 8
        body_end = min(len(data), body_start + size)
        if cid == b"fmt ":
            if body_end - body_start < 16:
                raise ValueError("short fmt chunk")
            tag, ch, rate, _brate, _align, bits = struct.unpack_from("<HHIIHH", data, body_start)
            if tag == 0xFFFE and body_end - body_start >= 40:
                sub = data[body_start + 24 : body_start + 40]
                tag = struct.unpack_from("<H", sub, 0)[0] if sub[2:] == _PCM_GUID_TAIL else 0xFFFE
            fmt = (tag, ch, rate, bits)
        elif cid == b"data":
            # size 0 or 0xFFFFFFFF: a streamed header; take what is there.
            end = len(data) if size in (0, 0xFFFFFFFF) else body_end
            pcm = data[body_start:end]
            if fmt is not None:
                break
        pos = body_start + size + (size & 1)
    if fmt is None:
        raise ValueError("no fmt chunk")
    if pcm is None:
        raise ValueError("no data chunk")
    tag, ch, rate, bits = fmt
    samples = array("h")
    if bits == 16:
        usable = len(pcm) - (len(pcm) % (2 * max(ch, 1)))
        samples.frombytes(pcm[:usable])
        if sys.byteorder == "big":
            samples.byteswap()
    return Wav(rate=rate, channels=ch, bits=bits, format_tag=tag, samples=samples)


def require_pcm16_mono(w: Wav, rate: int) -> array:
    """The vendor contract for a 200 response (ADR-0010 §4.2): PCM 16-bit, mono, at the requested rate."""
    problems = []
    if w.format_tag != 1:
        problems.append(f"format tag {w.format_tag} (want 1 = PCM)")
    if w.bits != 16:
        problems.append(f"{w.bits}-bit (want 16)")
    if w.channels != 1:
        problems.append(f"{w.channels} channels (want mono)")
    if w.rate != rate:
        problems.append(f"{w.rate} Hz (want {rate})")
    if not w.samples:
        problems.append("no samples")
    if problems:
        raise ValueError(", ".join(problems))
    return w.samples


def write_wav(samples, rate: int) -> bytes:
    """16-bit PCM mono WAV, canonical 44-byte header, no other chunks."""
    a = samples if isinstance(samples, array) and samples.typecode == "h" else array("h", samples)
    if sys.byteorder == "big":
        a = array("h", a)
        a.byteswap()
    body = a.tobytes()
    header = b"RIFF" + struct.pack("<I", 36 + len(body)) + b"WAVE"
    header += b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
    header += b"data" + struct.pack("<I", len(body))
    return header + body


# ----------------------------------------------------------------------------------------------- analysis
def window_len(rate: int) -> int:
    return rate // 100


def window_ms(rate: int) -> float:
    return window_len(rate) * 1000.0 / rate


def ms_to_frames(ms: float, rate: int) -> int:
    return int(rate * ms // 1000)


def round_half_up_div(num: int, den: int) -> int:
    return (2 * num + den) // (2 * den)


def duration_ms(frames: int, rate: int) -> int:
    """pack-format §4: round-half-up(frames x 1000 / rate)."""
    return round_half_up_div(frames * 1000, rate)


def _threshold_sq(db: float) -> float:
    lin = FULL_SCALE * 10 ** (db / 20)
    return lin * lin


def window_sums(samples, rate: int) -> list[tuple[float, int]]:
    """(sum of squares, length) per window."""
    w = window_len(rate)
    out = []
    n = len(samples)
    for s in range(0, n, w):
        seg = samples[s : s + w]
        out.append((float(sum(map(operator.mul, seg, seg))), len(seg)))
    return out


def active_flags(samples, rate: int, threshold_db: float = SILENCE_THRESHOLD_DBFS) -> list[bool]:
    thr = _threshold_sq(threshold_db)
    return [ss > thr * n for ss, n in window_sums(samples, rate)]


def to_dbfs(value: float) -> float:
    return 20 * math.log10(value / FULL_SCALE) if value > 0 else float("-inf")


def active_rms_dbfs(samples, rate: int, threshold_db: float = SILENCE_THRESHOLD_DBFS) -> float:
    thr = _threshold_sq(threshold_db)
    tot = 0.0
    cnt = 0
    for ss, n in window_sums(samples, rate):
        if ss > thr * n:
            tot += ss
            cnt += n
    return to_dbfs(math.sqrt(tot / cnt)) if cnt else float("-inf")


def peak_abs(samples) -> int:
    return max((abs(v) for v in samples), default=0)


def active_ms(samples, rate: int) -> float:
    return sum(active_flags(samples, rate)) * window_ms(rate)


def speech_span_ms(samples, rate: int) -> float:
    f = active_flags(samples, rate)
    if not any(f):
        return 0.0
    a = f.index(True)
    b = len(f) - 1 - f[::-1].index(True)
    return (b - a + 1) * window_ms(rate)


def edge_silence_ms(samples, rate: int) -> tuple[float, float]:
    f = active_flags(samples, rate)
    if not any(f):
        total = len(samples) * 1000.0 / rate
        return total, total
    lead = f.index(True)
    trail = f[::-1].index(True)
    return lead * window_ms(rate), trail * window_ms(rate)


def longest_internal_silence_ms(samples, rate: int) -> float:
    f = active_flags(samples, rate)
    if not any(f):
        return 0.0
    a = f.index(True)
    b = len(f) - 1 - f[::-1].index(True)
    best = run = 0
    for v in f[a : b + 1]:
        run = 0 if v else run + 1
        best = max(best, run)
    return best * window_ms(rate)


# ----------------------------------------------------------------------------------------------- processing
@dataclass(frozen=True)
class Processed:
    samples: array
    rate: int
    gain_db: float
    active_rms_db: float
    peak_db: float

    @property
    def frames(self) -> int:
        return len(self.samples)

    @property
    def duration_ms(self) -> int:
        return duration_ms(len(self.samples), self.rate)


MAX_LEVEL_ITERATIONS = 8


def _trim_and_fade(samples, rate: int, threshold_db: float) -> tuple[int, int, list[float]] | None:
    """Speech region (first to last 10 ms window above threshold_db) + the edge margin, then the linear fades."""
    flags = active_flags(samples, rate, threshold_db)
    if not any(flags):
        return None
    w = window_len(rate)
    a = flags.index(True)
    b = len(flags) - 1 - flags[::-1].index(True)
    margin = ms_to_frames(EDGE_MARGIN_MS, rate)
    start = max(0, a * w - margin)
    end = min(len(samples), (b + 1) * w + margin)
    y = [float(v) for v in samples[start:end]]
    n = len(y)
    fade = ms_to_frames(FADE_MS, rate)
    for i in range(min(fade, n)):
        g = i / fade
        y[i] *= g
        y[n - 1 - i] *= g
    return start, end, y


def process(samples, rate: int, key: str) -> Processed:
    """trim (5 ms margin) -> 10 ms linear fades -> one gain to -20 dBFS active RMS, peak <= -1 dBFS -> round half even.

    The −45 dBFS threshold (trim region and active-RMS windows) is the level of the DELIVERED clip, i.e. after the
    gain, because that is where the checks (edge silence <= 40 ms, internal silence) and the join measurement (AC 33)
    look. Applying it to the raw vendor level breaks on loud sources: Chimege delivers about −17 to −10 dBFS active
    RMS, so the gain is negative (−1 to −10 dB) and the soft decay at the end of a word, above −45 dBFS raw, falls
    below it after the gain (50–70 ms of "trailing silence"). The gain depends on the region and the region on the
    gain, so both are iterated to a fixed point (in practice 2–3 rounds); the sample operations keep the
    pack-format §4 order: trim, fades, gain."""
    if not any(active_flags(samples, rate)):
        raise check_failed(key, "silent", f"no 10 ms window above {SILENCE_THRESHOLD_DBFS} dBFS")
    gain_db = 0.0
    region = None
    for _ in range(MAX_LEVEL_ITERATIONS):
        thr_in = SILENCE_THRESHOLD_DBFS - gain_db  # input level that becomes −45 dBFS after the gain
        r = _trim_and_fade(samples, rate, thr_in)
        if r is None:
            raise check_failed(key, "silent", f"no 10 ms window above {SILENCE_THRESHOLD_DBFS} dBFS after the gain")
        start, end, y = r
        rms_db = active_rms_dbfs(y, rate, thr_in)
        if rms_db == float("-inf"):
            raise check_failed(key, "silent", "no active window after trimming and fades")
        gain = 10 ** ((TARGET_RMS_DBFS - rms_db) / 20)
        pk = max(abs(v) for v in y)
        if pk * gain > PEAK_CEILING:
            gain = PEAK_CEILING / pk
        new_db = 20 * math.log10(gain)
        done = region == (start, end) and abs(new_db - gain_db) < 0.01
        region, gain_db = (start, end), new_db
        if done:
            break
    out = array("h", (max(-32768, min(32767, round(v * gain))) for v in y))
    return Processed(
        samples=out,
        rate=rate,
        gain_db=20 * math.log10(gain),
        active_rms_db=active_rms_dbfs(out, rate),
        peak_db=to_dbfs(peak_abs(out)),
    )


def check_clip(p: Processed, key: str) -> None:
    """Per-clip checks of pack-format §4 (the pack-median RMS check is `check_rms_outliers`)."""
    lead, trail = edge_silence_ms(p.samples, p.rate)
    if lead > MAX_EDGE_SILENCE_MS or trail > MAX_EDGE_SILENCE_MS:
        raise check_failed(key, "edge-silence", f"leading {lead:.0f} ms, trailing {trail:.0f} ms (max {MAX_EDGE_SILENCE_MS} ms)")
    if peak_abs(p.samples) > FULL_SCALE * 10 ** (PEAK_CEILING_DBFS / 20):
        raise check_failed(key, "peak", f"peak {p.peak_db:.2f} dBFS > {PEAK_CEILING_DBFS} dBFS")
    d = p.duration_ms
    if not (MIN_DURATION_MS <= d <= MAX_DURATION_MS):
        raise check_failed(key, "duration", f"{d} ms (allowed {MIN_DURATION_MS}-{MAX_DURATION_MS} ms)")
    gap = longest_internal_silence_ms(p.samples, p.rate)
    if gap > MAX_INTERNAL_SILENCE_MS:
        raise check_failed(key, "internal-silence", f"{gap:.0f} ms below {SILENCE_THRESHOLD_DBFS} dBFS (max {MAX_INTERNAL_SILENCE_MS} ms)")


def check_rms_outliers(items: list[tuple[str, float]]) -> None:
    """Active RMS within +-2 dB of the pack median. items: (label, active RMS dBFS)."""
    if not items:
        return
    vals = sorted(v for _, v in items)
    m = len(vals)
    median = vals[m // 2] if m % 2 else (vals[m // 2 - 1] + vals[m // 2]) / 2
    bad = [(k, v) for k, v in items if abs(v - median) > RMS_TOLERANCE_DB]
    if bad:
        first = ", ".join(f"{k} {v - median:+.2f} dB" for k, v in bad[:10])
        raise check_failed(bad[0][0], "rms-outlier", f"{len(bad)} clip(s) off the pack median {median:.2f} dBFS by more than {RMS_TOLERANCE_DB} dB: {first}")


# ----------------------------------------------------------------------------------------------- nonFinal cut
def cut_carrier(samples, rate: int, key: str, then_active_ms: float, final_span_ms: float) -> array:
    """carrier-then-v1 (ADR-0010 §4.3) on the raw '<spokenText>, дараа нь' WAV: cut at the start of the LAST silence
    run of >= 60 ms that lies between speech and ends >= 250 ms before the end of speech; then self-check."""
    flags = active_flags(samples, rate)
    if not any(flags):
        raise check_failed(key, "nonFinal-boundary", "carrier take is silent")
    w = window_len(rate)
    wms = window_ms(rate)
    a = flags.index(True)
    b = len(flags) - 1 - flags[::-1].index(True)
    end_w = b + 1
    cut_w = None
    i = a
    while i <= b:
        if flags[i]:
            i += 1
            continue
        rs = i
        while i <= b and not flags[i]:
            i += 1
        re_ = i  # first active window after the run
        if (re_ - rs) * wms >= CUT_MIN_GAP_MS and (end_w - re_) * wms >= CUT_MIN_BEFORE_END_MS:
            cut_w = rs
    if cut_w is None:
        raise check_failed(key, "nonFinal-boundary", f"no pause of >= {CUT_MIN_GAP_MS} ms ending >= {CUT_MIN_BEFORE_END_MS} ms before the end of speech")
    cut = cut_w * w
    kept = samples[:cut]
    tail = samples[cut:]
    tail_ms = sum(flags[cut_w:]) * wms
    lo, hi = CUT_TAIL_RATIO
    if then_active_ms <= 0 or not (lo <= tail_ms / then_active_ms <= hi):
        raise check_failed(key, "nonFinal-boundary", f"removed tail is {tail_ms:.0f} ms active, join.then is {then_active_ms:.0f} ms (ratio must be {lo}-{hi})")
    span = speech_span_ms(kept, rate)
    if span > final_span_ms + CUT_MAX_EXTRA_MS:
        raise check_failed(key, "nonFinal-boundary", f"kept part spans {span:.0f} ms, final take {final_span_ms:.0f} ms (+{CUT_MAX_EXTRA_MS} ms max)")
    del tail
    return array("h", kept)

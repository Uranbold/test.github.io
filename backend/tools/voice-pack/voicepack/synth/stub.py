"""Deterministic stub synthesiser (ADR-0010 §4.4, NAV-016 AC 16; task B2). No network, no token.

- Each comma-separated phrase becomes a tone of 70 ms per character (spaces count).
- The base frequency comes from the SHA-256 of the phrase; a second harmonic and a syllable-rate amplitude envelope
  make it speech-like (the envelope never drops below the -45 dBFS threshold inside a phrase).
- Intonation: a phrase followed by a comma rises in pitch over its last 30 % (continuing), the last phrase falls
  (final). So '<text>, дараа нь' cut at the comma differs from '<text>' alone, as a real continuing take does.
- Peak -12 dBFS. 100 ms of silence at each end, 250 ms at each comma (exercises the carrier-then-v1 cut).
"""

from __future__ import annotations

import hashlib
import math
from array import array

from ..audio import write_wav

MS_PER_CHAR = 70
EDGE_SILENCE_MS = 100
COMMA_SILENCE_MS = 250
PEAK_DBFS = -12
RAMP_MS = 20


def _tone(phrase: str, rate: int, final: bool) -> list[float]:
    n = round(len(phrase) * MS_PER_CHAR * rate / 1000)
    if n <= 0:
        return []
    h = hashlib.sha256(phrase.encode("utf-8")).digest()
    f0 = 140.0 + (int.from_bytes(h[0:2], "big") % 160)  # 140-299 Hz
    syl = 3.5 + (h[2] % 16) / 10.0  # 3.5-5.0 Hz syllable rate
    ramp = max(1, round(RAMP_MS * rate / 1000))
    contour_start = int(n * 0.7)
    out = []
    phase = 0.0
    two_pi = 2 * math.pi
    for i in range(n):
        if i >= contour_start:
            x = (i - contour_start) / max(1, n - contour_start)
            f = f0 * (1.0 - 0.10 * x) if final else f0 * (1.0 + 0.15 * x)
        else:
            f = f0
        phase += two_pi * f / rate
        env = 0.55 + 0.45 * (0.5 - 0.5 * math.cos(two_pi * syl * i / rate))
        if i < ramp:
            env *= 0.5 - 0.5 * math.cos(math.pi * i / ramp)
        elif i >= n - ramp:
            env *= 0.5 - 0.5 * math.cos(math.pi * (n - 1 - i) / ramp)
        out.append(env * (math.sin(phase) + 0.35 * math.sin(2 * phase)))
    return out


class StubSynthesiser:
    vendor = "stub"
    api_version = "stub"
    speed = "1"
    pitch = "1"
    max_in_flight = 1

    def __init__(self):
        self.calls = 0

    def render(self, request_text: str, sample_rate: int) -> array:
        phrases = [p.strip() for p in request_text.split(",")]
        edge = [0.0] * round(EDGE_SILENCE_MS * sample_rate / 1000)
        comma = [0.0] * round(COMMA_SILENCE_MS * sample_rate / 1000)
        sig: list[float] = list(edge)
        for i, p in enumerate(phrases):
            if i:
                sig.extend(comma)
            sig.extend(_tone(p, sample_rate, final=(i == len(phrases) - 1)))
        sig.extend(edge)
        peak = max((abs(v) for v in sig), default=0.0)
        scale = 32768.0 * 10 ** (PEAK_DBFS / 20) / peak if peak else 0.0
        return array("h", (round(v * scale) for v in sig))

    def synthesize(self, request_text: str, voice: str, sample_rate: int, *, label: str = "") -> bytes:
        self.calls += 1
        return write_wav(self.render(request_text, sample_rate), sample_rate)

"""Synthesiser interface (task B2): request text in, WAV bytes out."""

from __future__ import annotations

from typing import Protocol


class Synthesiser(Protocol):
    vendor: str  # manifest source.vendor: chimege | stub
    api_version: str  # manifest source.apiVersion
    speed: str  # part of the cache key
    pitch: str  # part of the cache key
    max_in_flight: int  # requests that may run at the same time (Chimege: 2, ADR-0010 §4.1)

    def synthesize(self, request_text: str, voice: str, sample_rate: int, *, label: str = "") -> bytes:
        """Return a RIFF/WAVE file (PCM 16-bit mono at sample_rate). `label` names the entry in error messages."""
        ...

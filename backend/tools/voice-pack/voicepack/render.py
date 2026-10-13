"""Prompt list parsing (pack-format §7), join rules and assembly (§6), and the renderer that is also the NAV-007
AC 10 harness: `python -m voicepack render --pack DIR --prompts FILE --out DIR` (task B8).

Decoding uses the pinned ffmpeg and the manifest trim rule (§5), the same rule every client applies.
"""

from __future__ import annotations

import re
from array import array
from dataclasses import dataclass, field
from pathlib import Path

from .audio import round_half_up_div, write_wav
from .codec import DecodeFailed, Ffmpeg, trim_rule
from .errors import AudioCheckError, ConfigError

ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
THEN_KEY = "join.then"


@dataclass
class Prompt:
    id: str
    tokens: list[str]  # "<key>:<take>"
    extra: dict = field(default_factory=dict)

    @property
    def keys(self) -> list[str]:
        return [t.split(":", 1)[0] for t in self.tokens]

    @property
    def chained(self) -> bool:
        return THEN_KEY in self.keys

    @property
    def segments(self) -> str:
        return " ".join(self.tokens) if self.tokens else "-"


def parse_prompt_list(text: str, source: str = "prompt list") -> list[Prompt]:
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    if not lines:
        raise ConfigError(f"{source}: empty")
    header = lines[0].rstrip("\r").split("\t")
    if "id" not in header or "segments" not in header:
        raise ConfigError(f"{source}: header needs the columns id and segments")
    out = []
    seen = set()
    for n, line in enumerate(lines[1:], start=2):
        cells = line.rstrip("\r").split("\t")
        if len(cells) != len(header):
            raise ConfigError(f"{source} line {n}: {len(cells)} columns, header has {len(header)}")
        row = dict(zip(header, cells))
        pid = row["id"]
        if not ID_RE.match(pid):
            raise ConfigError(f"{source} line {n}: id {pid!r} does not match {ID_RE.pattern}")
        if pid in seen:
            raise ConfigError(f"{source} line {n}: duplicate id {pid}")
        seen.add(pid)
        seg = row["segments"].strip()
        tokens = [] if seg == "-" else seg.split(" ")
        for t in tokens:
            if not re.match(r"^[A-Za-z0-9._]+:(final|nonFinal)$", t):
                raise ConfigError(f"{source} line {n}: bad segment token {t!r}")
        out.append(Prompt(pid, tokens, {k: v for k, v in row.items() if k not in ("id", "segments")}))
    return out


def pause_ms(a_key: str, b_key: str, manifest: dict) -> int:
    """§6: thenPauseMs if a or b is join.then, otherwise joinPauseMs."""
    return manifest["thenPauseMs"] if THEN_KEY in (a_key, b_key) else manifest["joinPauseMs"]


def pause_frames(ms: int, rate: int) -> int:
    return round_half_up_div(ms * rate, 1000)


def prompt_ms(prompt: Prompt, manifest: dict) -> int:
    """§6 / NAV-016 AC 29: sum of durationMs + sum of pause ms."""
    by = {f"{e['key']}:{e['take']}": e for e in manifest["entries"]}
    total = sum(by[t]["durationMs"] for t in prompt.tokens)
    keys = prompt.keys
    total += sum(pause_ms(a, b, manifest) for a, b in zip(keys, keys[1:]))
    return total


def spoken_text(prompt: Prompt, manifest: dict) -> str:
    """§6 AC 24: spokenText values joined with one space, a comma directly before join.then."""
    by = {f"{e['key']}:{e['take']}": e for e in manifest["entries"]}
    out = ""
    for i, t in enumerate(prompt.tokens):
        s = by[t]["spokenText"]
        if i == 0:
            out = s
        elif t.split(":", 1)[0] == THEN_KEY:
            out += ", " + s
        else:
            out += " " + s
    return out


class PackAudio:
    """Decoded, trimmed clips of one pack (decoded once each)."""

    def __init__(self, pack_dir: Path, manifest: dict, ff: Ffmpeg):
        self.pack_dir = Path(pack_dir)
        self.m = manifest
        self.ff = ff
        self.rate = manifest["codec"]["sampleRateHz"]
        self.by = {f"{e['key']}:{e['take']}": e for e in manifest["entries"]}
        self._pcm: dict[str, array] = {}
        self.r_out: int | None = None

    def clip(self, token: str) -> array:
        if token not in self._pcm:
            e = self.by.get(token)
            if e is None:
                raise ConfigError(f"segment {token} is not in the pack")
            r_out, dec = self.ff.decode((self.pack_dir / e["file"]).read_bytes())
            try:
                pcm = trim_rule(dec, r_out, self.rate, self.m["codec"]["encoderDelayFrames"], e["frames"], e["encodedFrames"])
            except DecodeFailed as x:
                raise AudioCheckError(f"{token}: DECODE_FAILED: {x}") from None
            if self.r_out is None:
                self.r_out = r_out
            self._pcm[token] = pcm
        return self._pcm[token]

    def assemble(self, prompt: Prompt) -> tuple[array, int]:
        """One PCM buffer: clip 1, pause, clip 2, ... at the decoder's output rate. Returns (pcm, rate)."""
        out = array("h")
        keys = prompt.keys
        for i, t in enumerate(prompt.tokens):
            if i:
                out.extend(array("h", [0]) * pause_frames(pause_ms(keys[i - 1], keys[i], self.m), self.r_out or self.rate))
            out.extend(self.clip(t))
        return out, (self.r_out or self.rate)


def render_prompts(pack_dir: Path, manifest: dict, prompts: list[Prompt], out_dir: Path, ff: Ffmpeg,
                   wav_subdir: str = "") -> list[dict]:
    """Write <out>/<wav_subdir>/<id>.wav for every non-empty prompt and return rows for prompts.tsv."""
    audio = PackAudio(pack_dir, manifest, ff)
    wav_dir = Path(out_dir) / wav_subdir if wav_subdir else Path(out_dir)
    wav_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for p in prompts:
        missing = [t for t in p.tokens if t not in audio.by]
        if missing:
            raise ConfigError(f"prompt {p.id}: segment(s) not in the pack: {' '.join(missing)}")
        if not p.tokens:
            rows.append({"id": p.id, "segments": "-", "durationMs": 0, "frames": 0, "spoken": "", "file": "", **p.extra})
            continue
        pcm, rate = audio.assemble(p)
        (wav_dir / f"{p.id}.wav").write_bytes(write_wav(pcm, rate))
        rows.append({
            "id": p.id,
            "segments": p.segments,
            "durationMs": prompt_ms(p, manifest),
            "frames": len(pcm),
            "spoken": spoken_text(p, manifest),
            "file": (f"{wav_subdir}/" if wav_subdir else "") + f"{p.id}.wav",
            **p.extra,
        })
    return rows


def write_prompts_tsv(path: Path, rows: list[dict], columns: list[str]) -> None:
    def clean(v) -> str:
        return str(v).replace("\t", " ").replace("\n", " ").replace("\r", " ")

    lines = ["\t".join(columns)]
    for r in rows:
        lines.append("\t".join(clean(r.get(c, "")) for c in columns))
    Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")

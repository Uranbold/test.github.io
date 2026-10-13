"""Canonical inventory, inventorySha256 and glossaryRevision (pack-format §1.1, §2.3, §2.4; task B1)."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path

from .errors import InventoryError

DISTANCE_TSV = "docs/requirements/glossary-c3-spoken.tsv"
INVENTORY_TSV = "docs/architecture/voice-pack/inventory.tsv"
GLOSSARY_MD = "docs/requirements/glossary.md"
MANIFEST_SCHEMA = "docs/architecture/voice-pack/manifest.schema.json"

FINAL = "final"
NON_FINAL = "nonFinal"
TAKES = (FINAL, NON_FINAL)
KEY_RE = re.compile(r"^(dist|cont|instr|tail|join|fixed)(\.[A-Za-z0-9_]+)+$")


def repo_root() -> Path:
    """The repository that holds this tool: backend/tools/voice-pack/voicepack/inventory.py -> 4 levels up."""
    return Path(__file__).resolve().parents[4]


@dataclass(frozen=True, order=True)
class Entry:
    key: str
    take: str
    spoken: str

    @property
    def sort_key(self) -> tuple[bytes, bytes]:
        return (self.key.encode("utf-8"), self.take.encode("utf-8"))

    @property
    def file(self) -> str:
        return f"clips/{self.key}.{self.take}.aac"

    @property
    def token(self) -> str:
        """Prompt list token (pack-format §7)."""
        return f"{self.key}:{self.take}"


@dataclass(frozen=True)
class Inventory:
    entries: tuple[Entry, ...]
    canonical: bytes
    sha256: str
    glossary_revision: str

    @property
    def n_final(self) -> int:
        return sum(1 for e in self.entries if e.take == FINAL)

    @property
    def n_non_final(self) -> int:
        return sum(1 for e in self.entries if e.take == NON_FINAL)

    def get(self, key: str, take: str) -> Entry | None:
        for e in self.entries:
            if e.key == key and e.take == take:
                return e
        return None

    def by_token(self) -> dict[str, Entry]:
        return {e.token: e for e in self.entries}


def _rows(data: bytes, name: str, required: list[str]) -> list[dict[str, str]]:
    """UTF-8, tab-separated, header line first. Columns are found by name; a row with a different column count is an
    error. Line ends: LF; a trailing CR stays in the last column (programs ignore that column in both inputs)."""
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as e:
        raise InventoryError(f"{name}: not UTF-8 ({e.reason} at byte {e.start}) [rule: format]") from None
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    if not lines:
        raise InventoryError(f"{name}: empty file [rule: format]")
    header = lines[0].split("\t")
    missing = [c for c in required if c not in header]
    if missing:
        raise InventoryError(f"{name}: header lacks column(s) {', '.join(missing)} [rule: format]")
    out = []
    for n, line in enumerate(lines[1:], start=2):
        cells = line.split("\t")
        if len(cells) != len(header):
            raise InventoryError(f"{name} line {n}: {len(cells)} columns, header has {len(header)} [rule: format]")
        out.append(dict(zip(header, cells)))
    return out


def entries_from_inputs(distance_tsv: bytes, inventory_tsv: bytes) -> list[Entry]:
    """Entries exactly as pack-format §1.1: every TSV row -> one final entry; every inventory row -> one final entry,
    plus one nonFinal entry when nonfinal = yes."""
    entries: list[Entry] = []
    for r in _rows(distance_tsv, DISTANCE_TSV, ["key", "spoken_mn"]):
        entries.append(Entry(r["key"], FINAL, r["spoken_mn"]))
    for r in _rows(inventory_tsv, INVENTORY_TSV, ["key", "nonfinal", "spoken_mn", "source"]):
        if r["nonfinal"] not in ("yes", "no"):
            raise InventoryError(f"{r['key']}: nonfinal must be yes or no, got {r['nonfinal']!r} [rule: format]")
        entries.append(Entry(r["key"], FINAL, r["spoken_mn"]))
        if r["nonfinal"] == "yes":
            entries.append(Entry(r["key"], NON_FINAL, r["spoken_mn"]))
    for e in entries:
        if not KEY_RE.match(e.key):
            raise InventoryError(f"{e.key!r}: key does not match {KEY_RE.pattern} [rule: key]")
    return entries


def canonical_bytes(entries: list[Entry] | tuple[Entry, ...], *, require_sorted: bool = False) -> bytes:
    """pack-format §2.3: sort by key then take (UTF-8 byte order), no duplicates, `<key>\\t<take>\\t<spokenText>\\n`."""
    ordered = sorted(entries, key=lambda e: e.sort_key)
    if require_sorted and list(entries) != ordered:
        raise ValueError("entries are not sorted by key then take")
    seen = set()
    for e in ordered:
        if (e.key, e.take) in seen:
            raise InventoryError(f"{e.key} {e.take}: duplicate (key, take) [rule: duplicate]")
        seen.add((e.key, e.take))
    return "".join(f"{e.key}\t{e.take}\t{e.spoken}\n" for e in ordered).encode("utf-8")


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def build(distance_tsv: bytes, inventory_tsv: bytes) -> Inventory:
    entries = entries_from_inputs(distance_tsv, inventory_tsv)
    canon = canonical_bytes(entries)
    ordered = tuple(sorted(entries, key=lambda e: e.sort_key))
    return Inventory(
        entries=ordered,
        canonical=canon,
        sha256=sha256_hex(canon),
        glossary_revision=sha256_hex(distance_tsv + inventory_tsv),
    )


def load(repo: Path | None = None) -> Inventory:
    repo = repo or repo_root()
    try:
        tsv = (repo / DISTANCE_TSV).read_bytes()
        inv = (repo / INVENTORY_TSV).read_bytes()
    except OSError as e:
        raise InventoryError(f"cannot read an inventory input: {e.filename}: {e.strerror} [rule: format]") from None
    return build(tsv, inv)

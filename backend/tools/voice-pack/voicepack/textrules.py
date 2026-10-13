"""Text rules checked before any request (pack-format §2.2; NAV-016 AC 3, AC 7; task B1).

Rule names: digit, latin, zero-width, charset, length, nfc, avoid.

The Avoid check follows NAV-007 AC 16: every «…» term the glossary marks "Avoid" (read from
docs/requirements/glossary.md with the same extraction rules as the NAV-002 AC 33 test,
tests/e2e/nav002/static.test.mjs), matched case-insensitively as a substring, except «км/ц», which is matched only
as `км/ц(?!аг)`; plus the D22 patterns: the stem «навигаци», the word «Төвлөрүүлэх», `км/ц(?!аг)` and the old
ordinal `(\\d+|нэг|хоёр|…|арав)\\s(дахь|дэх)` (NAV-007 AC 13).
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path

from .inventory import GLOSSARY_MD, NON_FINAL, Entry

SPOKEN_RE = re.compile(r"^[а-яёөү]+( [а-яёөү]+)*$")
DIGIT_RE = re.compile(r"[0-9]")
LATIN_RE = re.compile(r"[A-Za-z]")
ZERO_WIDTH_RE = re.compile("[​‌‍﻿]")
MIN_LEN = 2
MAX_LEN = 300

# Take-method request texts (ADR-0010 §4.3). The carrier is the only text added to an inventory text (AC 54).
CARRIER_THEN = ", дараа нь"
TAKE_METHODS = ("carrier-then-v1", "comma-v1")


def request_text(entry: Entry, take_method: str) -> str:
    if entry.take != NON_FINAL:
        return entry.spoken
    if take_method == "carrier-then-v1":
        return entry.spoken + CARRIER_THEN
    if take_method == "comma-v1":
        return entry.spoken + ","
    raise ValueError(f"unknown take method {take_method}")


# ----------------------------------------------------------------------------------------------- Avoid terms
D22_PATTERNS = [
    ("навигаци", re.compile(r"навигаци", re.IGNORECASE)),
    ("Төвлөрүүлэх", re.compile(r"(?<![а-яёөүА-ЯЁӨҮ])төвлөрүүлэх(?![а-яёөүА-ЯЁӨҮ])", re.IGNORECASE)),
    ("км/ц", re.compile(r"км/ц(?!аг)")),
    ("<number> дахь/дэх", re.compile(r"(\d+|нэг|хоёр|гурав|дөрөв|тав|зургаа|долоо|найм|ес|арав)\s(дахь|дэх)")),
]

_QUOTED = re.compile(r"«([^»]+)»")


def _norm(x: str) -> str:
    return re.sub(r"\s+", " ", x.replace("**", "").replace("`", "")).strip()


def glossary_avoid_terms(md: str) -> list[str]:
    """«…» terms in any sentence that says Avoid, in the term and convention tables of sections 1–9 (cells after
    the first), except sentences saying "not marked Avoid" and terms holding a placeholder ("<"). English term names
    that contain "Avoid" (e.g. "Avoid unpaved roads") and English labels in quotes are not markers."""
    avoid_names: list[str] = []
    in_terms = False
    for line in md.split("\n"):
        if re.match(r"^##\s", line):
            in_terms = False
            continue
        if not line.startswith("|") or re.match(r"^\|\s*-", line):
            continue
        if "Approved Mongolian" in line:
            in_terms = True
            continue
        if not in_terms:
            continue
        name = re.sub(r"\s*\([^)]*\)\s*$", "", _norm(line.split("|")[1] if "|" in line else ""))
        if re.search(r"\bAvoid\b", name) and name not in avoid_names:
            avoid_names.append(name)
    avoid_names.sort(key=len, reverse=True)
    names_re = re.compile(r"\b(?:" + "|".join(re.escape(n) for n in avoid_names) + r")\b") if avoid_names else None

    def plain(x: str) -> str:
        t = re.sub(r"“[^”]*”|\"[^\"]*\"", "", _norm(x))
        return names_re.sub("", t) if names_re else t

    def sentences(x: str) -> list[str]:
        return re.split(r"(?<=[.;])\s+|\s+e\.g\.\s+", x)

    banned: list[str] = []
    section_num = 0
    kind = None
    for line in md.split("\n"):
        h = re.match(r"^##\s+(.*)$", line)
        if h:
            m = re.match(r"^(\d+)\.", h.group(1))
            section_num = int(m.group(1)) if m else 0
            kind = None
            continue
        if not line.startswith("|") or re.match(r"^\|\s*-", line):
            continue
        cells = [c.strip() for c in line.split("|")[1:-1]]
        if "Approved Mongolian" in line:
            kind = "terms"
            continue
        if re.match(r"^\|\s*#\s*\|\s*Convention", line):
            kind = "conventions"
            continue
        if not kind or not (1 <= section_num <= 9):
            continue
        for c in cells[1:]:
            for snt in sentences(c):
                if re.search(r"\bAvoid\b", plain(snt)) and "not marked Avoid" not in snt:
                    for q in _QUOTED.findall(snt):
                        q = _norm(q)
                        if "<" not in q and q not in banned:
                            banned.append(q)
    return banned


@dataclass(frozen=True)
class AvoidRules:
    terms: tuple[str, ...]

    @classmethod
    def from_glossary(cls, path: Path) -> "AvoidRules":
        return cls(tuple(glossary_avoid_terms(path.read_text(encoding="utf-8"))))

    @classmethod
    def from_repo(cls, repo: Path) -> "AvoidRules":
        return cls.from_glossary(repo / GLOSSARY_MD)

    def hits(self, text: str) -> list[str]:
        out = []
        low = text.lower()
        for t in self.terms:
            if t == "км/ц":
                if re.search(r"км/ц(?!аг)", text):
                    out.append(t)
            elif t.lower() in low:
                out.append(t)
        for name, rx in D22_PATTERNS:
            if rx.search(text) and name not in out:
                out.append(name)
        return out


# ----------------------------------------------------------------------------------------------- the check
@dataclass(frozen=True)
class Violation:
    key: str
    take: str
    rule: str
    detail: str

    def __str__(self) -> str:
        return f"{self.key} ({self.take}): rule {self.rule}: {self.detail}"


def check_text(text: str) -> list[tuple[str, str]]:
    """Rules on one spoken text, without the Avoid rule. Returns (rule, detail) pairs."""
    out = []
    if DIGIT_RE.search(text):
        out.append(("digit", "contains a digit [0-9]"))
    if LATIN_RE.search(text):
        out.append(("latin", "contains a Latin letter [A-Za-z]"))
    if ZERO_WIDTH_RE.search(text):
        out.append(("zero-width", "contains U+200B-U+200D or U+FEFF"))
    if unicodedata.normalize("NFC", text) != text:
        out.append(("nfc", "not in Unicode NFC"))
    if not (MIN_LEN <= len(text) <= MAX_LEN):
        out.append(("length", f"{len(text)} characters, allowed {MIN_LEN}-{MAX_LEN}"))
    if not SPOKEN_RE.match(text):
        bad = sorted({c for c in text if not re.match(r"[а-яёөү ]", c)})
        detail = "only lower-case Mongolian Cyrillic letters and single spaces are allowed"
        if bad:
            detail += "; offending: " + " ".join(f"U+{ord(c):04X}" for c in bad)
        out.append(("charset", detail))
    return out


def check_entries(entries, avoid: AvoidRules, take_method: str = "carrier-then-v1") -> list[Violation]:
    out: list[Violation] = []
    for e in entries:
        for rule, detail in check_text(e.spoken):
            out.append(Violation(e.key, e.take, rule, detail))
        req = request_text(e, take_method)
        if req != e.spoken and len(req) > MAX_LEN:
            out.append(Violation(e.key, e.take, "length", f"request text with the take method is {len(req)} > {MAX_LEN}"))
        for term in avoid.hits(e.spoken):
            out.append(Violation(e.key, e.take, "avoid", f"contains the Avoid term «{term}»"))
    return out

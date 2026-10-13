"""Repository scans for NAV-016 (task B10 AC 8; AC 19 / D79, security VP-3). Standard library only and free of
package imports, so CI can run it as a plain script:

    python3 -I backend/tools/voice-pack/voicepack/reposcan.py <repo root>      exit 1 on any finding

AC 8, token values (definition: task file B10, clarified 2026-10-13 incl. QA Q-2): `git grep --untracked -n -E` for
the variable name followed by optional spaces and `=` or `:`. For each hit the value is the text after `=`/`:` and
optional spaces, with one leading quote removed, up to the next whitespace, quote or backtick. Not a value: empty,
`.`, `<placeholder>`, anything containing `…`, a reference `$NAME` / `${NAME}`. A hit whose name sits directly after
`${` is a parameter expansion (a reference); for the default operators `:-` `-` `:=` `=` the word up to `}` must be
empty or a reference, while the words of `:?` and `:+` are not values. Everything else is a finding.

AC 19, audio custody (repo-wide): no tracked or untracked-and-not-ignored audio file anywhere (the app chime under
`res/raw/chime*` is the one allowed exception), no pack `manifest.json` (one holding `inventorySha256`), and no
generator review page (`review/index.html` or `review/review.tsv`).
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

TOKEN_VAR = "CHIMEGE_API_" + "TOKEN"  # split so this file is not a hit of its own scan
AUDIO_RE = re.compile(r"\.(aac|m4a|wav|mp3|ogg|opus|flac|amr|3gp|webm)$", re.I)
CHIME_ALLOWED_RE = re.compile(r"(^|/)res/raw/chime[^/]*$")
_REF_RE = re.compile(r"\$[A-Za-z_][A-Za-z0-9_]*|\$\{[A-Za-z_][A-Za-z0-9_]*\}")
_DEFAULT_OPS = (":-", ":=", "-", "=")
_NONVALUE_OPS = (":?", ":+", "?", "+")


def _is_reference(word: str) -> bool:
    return bool(_REF_RE.fullmatch(word))


def _expansion_finding(rest: str) -> bool:
    """`rest` is the text right after `${NAME`. True if it hides a value (a non-empty, non-reference default)."""
    end = rest.find("}")
    body = rest if end < 0 else rest[:end]
    for op in _NONVALUE_OPS + _DEFAULT_OPS:  # longest operators first within each group (":-" before "-")
        if body.startswith(op):
            if op in _NONVALUE_OPS:
                return False
            word = body[len(op):].strip("\"'")
            # the general non-value forms of B10 (placeholder, ellipsis) also hold for a default word
            return not (word == "" or _is_reference(word) or word.startswith("<") or "…" in word)
    return False  # ${NAME} or ${NAME:offset...}: a reference


def token_findings(line: str) -> list[str]:
    """Values of TOKEN_VAR found in one line (empty list = the line is clean)."""
    out = []
    for m in re.finditer(r"(\$\{)?" + re.escape(TOKEN_VAR) + r"(?:[ \t]*([=:])|(?=[-?+]))", line):
        if m.group(1):  # ${NAME<op>...}: a parameter expansion, i.e. a reference, unless a default hides a value
            if _expansion_finding(line[m.start() + 2 + len(TOKEN_VAR):]):
                out.append(line[m.start():m.start() + 80])
            continue
        if m.group(2) is None:
            continue  # NAME-... outside an expansion: not a hit of the scan pattern
        rest = line[m.end():].lstrip(" \t")
        if rest[:1] in ("\"", "'"):
            rest = rest[1:]
        value = re.match(r"[^\s\"'`]*", rest).group(0)
        if value in ("", ".") or value.startswith("<") or "…" in value or _is_reference(value):
            continue
        inner = re.match(r"\$\{[A-Za-z_][A-Za-z0-9_]*", value)
        if inner and not _expansion_finding(value[inner.end():]):
            continue  # NAME=${OTHER:-} and similar: a reference
        out.append(line[m.start():m.start() + 80])
    return out


def _git(repo: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, timeout=120)


def scan_token(repo: Path) -> list[str]:
    r = _git(repo, "grep", "--untracked", "-n", "-I", "-E", "-e", TOKEN_VAR + "[[:space:]]*[=:]",
             "-e", "[$][{]" + TOKEN_VAR + "[-?+]")
    if r.returncode not in (0, 1):
        raise RuntimeError("git grep failed: " + r.stderr.strip()[:200])
    hits = []
    for line in r.stdout.splitlines():
        path, _, rest = line.partition(":")
        lineno, _, text = rest.partition(":")
        for v in token_findings(text):
            hits.append(f"{path}:{lineno}")  # never the text: a real value must not reach a CI log
    return hits


def scan_audio(repo: Path) -> list[str]:
    files = _git(repo, "ls-files", "-z", "--cached", "--others", "--exclude-standard").stdout.split("\0")
    hits = []
    for f in filter(None, files):
        if AUDIO_RE.search(f) and not CHIME_ALLOWED_RE.search(f):
            hits.append(f"audio file: {f}")
        elif re.search(r"(^|/)review/(index\.html|review\.tsv)$", f):
            hits.append(f"review page: {f}")
        elif f.endswith("manifest.json"):
            try:
                text = (repo / f).read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if "inventorySha256" in text:
                hits.append(f"voice pack manifest: {f}")
    return hits


def main(argv: list[str]) -> int:
    repo = Path(argv[1] if len(argv) > 1 else ".").resolve()
    token = scan_token(repo)
    audio = scan_audio(repo)
    for h in token:
        print(f"AC 8: non-empty {TOKEN_VAR} value: {h}")
    for h in audio:
        print(f"AC 19: {h}")
    print(f"reposcan: {len(token)} token value(s), {len(audio)} audio/pack/review file(s)")
    return 1 if token or audio else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

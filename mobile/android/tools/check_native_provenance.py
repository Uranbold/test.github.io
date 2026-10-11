#!/usr/bin/env python3
"""SEC-4B AC 31 (ADR-0018 §7): the native libraries shipped in an APK equal the README's provenance table.

Reads the file names in the first column of the table under the README heading "Where the native libraries come from"
(rows whose first cell is a `lib….so` name) and compares them, as a set, with the file names under `lib/<abi>/` in the
APK. Exit 0 when the sets are equal, 1 with the differences otherwise, 2 on a usage error.

    python3 tools/check_native_provenance.py --apk app/build/outputs/apk/release/app-release-unsigned.apk

Run it with the README §3 checks after a dependency change, so a new `.so` cannot leave the table stale.
"""
import argparse
import os
import re
import sys
import zipfile

ANDROID = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
README = os.path.join(ANDROID, "README.md")
HEADING = "Where the native libraries come from"


def table_names(readme_text: str) -> set:
    """`.so` names in the first column of the provenance table (up to the next heading of the same or higher level)."""
    m = re.search(r"^(#+) [^\n]*" + re.escape(HEADING) + r"[^\n]*$", readme_text, re.M)
    if not m:
        raise ValueError(f'README has no heading "{HEADING}"')
    level = len(m.group(1))
    rest = readme_text[m.end():]
    end = re.search(r"^#{1,%d} " % level, rest, re.M)
    section = rest[: end.start()] if end else rest
    names = set()
    in_table = False
    for line in section.splitlines():
        if line.startswith("|"):
            cell = line.split("|")[1].strip()
            if not in_table:
                in_table = True  # header row
                continue
            hit = re.fullmatch(r"`(lib[^`/]+\.so)`", cell)
            if hit:
                names.add(hit.group(1))
        elif in_table and line.strip() == "":
            break  # first table only; the "test only, not shipped" list comes after it
    return names


def apk_names(apk: str) -> set:
    with zipfile.ZipFile(apk) as z:
        return {n.split("/")[-1] for n in z.namelist() if re.fullmatch(r"lib/[^/]+/[^/]+\.so", n)}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--apk", required=True)
    ap.add_argument("--readme", default=README)
    a = ap.parse_args()
    if not os.path.isfile(a.apk):
        print(f"no such APK: {a.apk}", file=sys.stderr)
        return 2
    with open(a.readme, encoding="utf-8") as f:
        table = table_names(f.read())
    shipped = apk_names(a.apk)
    if table == shipped:
        print(f"OK: {len(shipped)} native libraries in {os.path.basename(a.apk)} equal the README table: {', '.join(sorted(shipped))}")
        return 0
    for n in sorted(shipped - table):
        print(f"in the APK but not in the README table: {n}")
    for n in sorted(table - shipped):
        print(f"in the README table but not in the APK: {n}")
    return 1


if __name__ == "__main__":
    sys.exit(main())

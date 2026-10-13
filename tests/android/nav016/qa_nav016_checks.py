#!/usr/bin/env python3
"""NAV-016 QA oracle (independent of the app and generator code; stdlib only).

Traces to docs/qa/test-plans/NAV-016.md. Every check names the NAV-016 AC it covers.

  inventory [--manifest M] [--asset A]   AC 1, 4, 5: canonical inventory (pack-format §2.3) from the two input files,
                                         304 entries, inventorySha256; optionally equal to a pack manifest (V7) and to
                                         the Android generated asset bytes (M1).
  golden FILE [--baseline B]             AC 24, 25, 26 on the voice golden set: old 5 columns byte-identical to the
                                         baseline (mn and en), en speaks its display text with no segments, every mn
                                         segment is an inventory entry, spoken = segments joined (pack-format §6),
                                         display -> spoken through the TSV only, 0 digits / 0 Latin in mn spoken text,
                                         nonFinal only directly before join.then.
  scan [--apk APK ...]                   AC 6, 8, 19, 52: repository scans (tracked files) and optional APK scans.
  pack DIR                               AC 18, 51 support: prints (voiceId, packVersion, manifest SHA-256) and size.

Exit status 0 = every check passed, 1 = at least one failure (each printed as FAIL <AC>: ...).
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import unicodedata
import zipfile

REPO = pathlib.Path(__file__).resolve().parents[3]
TSV = REPO / "docs/requirements/glossary-c3-spoken.tsv"
INV = REPO / "docs/architecture/voice-pack/inventory.tsv"
TOKEN_VAR = "CHIMEGE_API_TOKEN"
AUDIO_EXT = re.compile(r"\.(wav|m4a|aac|mp3|ogg|opus|flac)$", re.I)
SPOKEN_RE = re.compile(r"^[а-яёөү]+( [а-яёөү]+)*$")

failures = []


def fail(ac, msg):
    failures.append(f"FAIL {ac}: {msg}")
    print(failures[-1])


def ok(ac, msg):
    print(f"ok   {ac}: {msg}")


def read_tsv(path):
    lines = path.read_text(encoding="utf-8").split("\n")
    head = lines[0].split("\t")
    return [dict(zip(head, ln.split("\t"))) for ln in lines[1:] if ln]


def inventory():
    """pack-format §1.1 / §2.3. Returns (entries {(key, take): text}, canonical bytes, tsv rows)."""
    entries = {}
    tsv = read_tsv(TSV)
    for r in tsv:
        k = (r["key"], "final")
        if k in entries:
            fail("AC 1", f"duplicate {k}")
        entries[k] = r["spoken_mn"]
    for r in read_tsv(INV):
        for take in (["final", "nonFinal"] if r["nonfinal"] == "yes" else ["final"]):
            k = (r["key"], take)
            if k in entries:
                fail("AC 1", f"duplicate {k}")
            entries[k] = r["spoken_mn"]
    order = sorted(entries, key=lambda kt: (kt[0].encode(), kt[1].encode()))
    canon = "".join(f"{k}\t{t}\t{entries[(k, t)]}\n" for k, t in order).encode("utf-8")
    return entries, canon, tsv


def cmd_inventory(a):
    entries, canon, _ = inventory()
    sha = hashlib.sha256(canon).hexdigest()
    n_final = sum(1 for _, t in entries if t == "final")
    n_nf = len(entries) - n_final
    (ok if (len(entries), n_final, n_nf) == (304, 265, 39) else fail)(
        "AC 4", f"{len(entries)} entries ({n_final} final + {n_nf} nonFinal), expected 304 (265 + 39)")
    if entries.get(("fixed.gpsLost", "final")) != "жи пи эс дохио тасарлаа":
        fail("AC 4", f"fixed.gpsLost = {entries.get(('fixed.gpsLost', 'final'))!r}")
    elif entries.get(("fixed.gpsRestored", "final")) != "жи пи эс дохио сэргэлээ":
        fail("AC 4", f"fixed.gpsRestored = {entries.get(('fixed.gpsRestored', 'final'))!r}")
    else:
        ok("AC 4", "fixed.gpsLost / fixed.gpsRestored spoken «жи пи эс …»")
    bad = []
    for (k, t), s in entries.items():
        if not SPOKEN_RE.match(s) or unicodedata.normalize("NFC", s) != s or not 2 <= len(s) <= 300:
            bad.append(f"{k}:{t} «{s}»")
    (fail if bad else ok)("AC 3", f"text rules (pack-format §2.2) broken by {len(bad)} entries {bad[:5]}")
    print(f"     inventorySha256 = {sha}")
    if a.manifest:
        m = json.loads(pathlib.Path(a.manifest).read_text(encoding="utf-8"))
        (ok if m.get("inventorySha256") == sha else fail)(
            "AC 5/V7", f"manifest inventorySha256 {m.get('inventorySha256')} vs repo {sha}")
        ment = {(e["key"], e["take"]): e["spokenText"] for e in m.get("entries", [])}
        diff = sorted(set(ment.items()) ^ set(entries.items()))
        (fail if diff else ok)("AC 18", f"manifest entries vs repo inventory: {len(diff)} differences {diff[:4]}")
    if a.asset:
        b = pathlib.Path(a.asset).read_bytes()
        (ok if b == canon else fail)("AC 1/M1", f"Android asset {a.asset} byte-identical to the canonical inventory")


def compose(tokens, entries):
    """pack-format §6 spoken text: one space between entries, a comma directly before join.then."""
    out = ""
    for i, (k, t) in enumerate(tokens):
        s = entries[(k, t)]
        if i == 0:
            out = s
        elif k == "join.then":
            out += ", " + s
        else:
            out += " " + s
    return out


def lower_first(s):
    return s[:1].lower() + s[1:]


def cmd_golden(a):
    entries, _, tsv = inventory()
    disp = {r["key"]: (r["display_mn"], r["spoken_mn"]) for r in tsv}
    text = pathlib.Path(a.file).read_text(encoding="utf-8")
    rows = [ln.split("\t") for ln in text.split("\n") if ln and not ln.startswith("#")]
    if a.baseline:
        base = pathlib.Path(a.baseline).read_text(encoding="utf-8")
        brows = ["\t".join(ln.split("\t")[:5]) for ln in base.split("\n") if ln and not ln.startswith("#")]
        for lang in ("mn", "en"):
            x = [b for b in brows if b.split("\t")[1] == lang]
            y = ["\t".join(r[:5]) for r in rows if r[1] == lang]
            (ok if x == y else fail)("AC 26", f"{lang}: old 5 columns of {len(y)} rows byte-identical to the baseline ({len(x)} rows)")
    if any(len(r) != 7 for r in rows):
        fail("AC 26", "every row needs 7 columns (spoken, segments added)")
        return
    n_mn = n_en = 0
    for r in rows:
        tid, lang, t_s, man, display, spoken, segs = r
        where = f"{tid} {lang} t={t_s}"
        if lang == "en":
            n_en += 1
            if spoken != display or segs != "-":
                fail("AC 22/26 (D96)", f"{where}: en spoken must equal display, segments '-': {r}")
            continue
        n_mn += 1
        if re.search(r"[0-9A-Za-z]", spoken):
            fail("AC 25", f"{where}: digit or Latin letter in «{spoken}»")
        if segs == "-":
            fail("AC 22", f"{where}: mn prompt without segments")
            continue
        toks = [tuple(x.split(":", 1)) for x in segs.split(" ")]
        if not 1 <= len(toks) <= 4:
            fail("AC 22", f"{where}: {len(toks)} segments")
        missing = [k for k in toks if k not in entries]
        if missing:
            fail("AC 27", f"{where}: segments not in the inventory {missing}")
            continue
        for i, (k, t) in enumerate(toks):
            nxt = toks[i + 1][0] if i + 1 < len(toks) else None
            if (t == "nonFinal") != (nxt == "join.then"):
                fail("AC 23", f"{where}: take of {k}:{t} (nonFinal exactly before join.then)")
        if compose(toks, entries) != lower_first(spoken):
            fail("AC 24", f"{where}: spoken «{spoken}» != segments «{compose(toks, entries)}»")
        # display -> spoken through the TSV only: replace the display distance phrase by its spoken_mn, lower-case
        # the first letter, GPS -> «жи пи эс». A digit left over means a distance phrase that is not a TSV value.
        expect = display
        head = toks[0][0]
        if head.startswith(("dist.", "cont.km.")):
            d, s = disp[head]
            if not expect.startswith(d + " "):
                fail("AC 25", f"{where}: display «{display}» does not start with the TSV display «{d}» of {head}")
                continue
            expect = s + expect[len(d):]
        expect = lower_first(expect).replace("GPS", "жи пи эс")
        if expect != lower_first(spoken):
            fail("AC 23/25", f"{where}: display «{display}» -> «{expect}» but spoken «{spoken}»")
    print(f"     rows: {n_mn} mn, {n_en} en")
    if not [f for f in failures if f.startswith(("FAIL AC 2", "FAIL AC 3"))]:
        ok("AC 22-26", "golden spoken/segment columns consistent with the TSV and the inventory")


def git(*args):
    return subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True, check=False)


def cmd_scan(a):
    tracked = git("ls-files").stdout.split("\n")
    audio = [f for f in tracked if AUDIO_EXT.search(f)]
    (fail if audio else ok)("AC 19", f"tracked audio files: {audio}")
    packs = []
    for f in tracked:
        if f.endswith("manifest.json"):
            t = (REPO / f).read_text(encoding="utf-8", errors="replace")
            if "inventorySha256" in t or "schemaVersion" in t:
                packs.append(f)
    (fail if packs else ok)("AC 19", f"tracked pack manifest.json files: {packs}")
    review = [f for f in tracked if re.search(r"(^|/)review/", f) and "voice" in f.lower()]
    review += [f for f in tracked if re.search(r"(^|/)review/(index\.html|review\.tsv|prompts/)", f)]
    (fail if review else ok)("AC 19", f"tracked review/* files: {sorted(set(review))}")
    for probe in ("backend/tools/voice-pack/out/FEMALE5v2-v1/pack/manifest.json",
                  "backend/tools/voice-pack/out/FEMALE5v2-v1/review/index.html",
                  "mobile/android/voicepack-local/pack/manifest.json"):
        (ok if git("check-ignore", "-q", probe).returncode == 0 else fail)("AC 19", f"git-ignored: {probe}")
    # AC 8: no non-empty token value in tracked files. Non-values: the empty key, the scan pattern "=." itself,
    # placeholders (…, <value>, SENTINEL-*) as in backend/tools/voice-pack/tests/test_repo.py.
    r = git("grep", "-n", "-E", TOKEN_VAR + "=.")
    values = []
    for line in r.stdout.splitlines():
        for v in re.findall(re.escape(TOKEN_VAR) + r"=([^\s`'\"]*)", line):
            if v in ("", ".") or "…" in v or v.startswith("<") or v.startswith("SENTINEL"):
                continue
            values.append(line[:140])
    (fail if values else ok)("AC 8", f"non-empty {TOKEN_VAR} values in tracked files: {values}")
    env = [p for p in ("backend/.env.example", ".env.example") if (REPO / p).exists()]
    has = [p for p in env if TOKEN_VAR + "=" in (REPO / p).read_text(encoding="utf-8").split("\n")]
    (ok if has else fail)("AC 8", f".env.example with an empty `{TOKEN_VAR}=` line: {has}")
    for area in ("mobile", "web"):
        hits = [ln for ln in git("grep", "-n", "-i", "chimege", "--", area).stdout.splitlines()]
        (fail if hits else ok)("AC 6", f"'chimege' in tracked {area}/ files: {hits[:5]}")
    hits = git("grep", "-n", TOKEN_VAR, "--", "mobile").stdout.splitlines()
    (fail if hits else ok)("AC 52", f"{TOKEN_VAR} in tracked mobile/ files (incl. Gradle configuration): {hits[:5]}")
    for apk in a.apk or []:
        scan_apk(pathlib.Path(apk))


def scan_apk(apk):
    with zipfile.ZipFile(apk) as z:
        tok = chim = 0
        vp = []
        for i in z.infolist():
            b = z.read(i)
            tok += b.count(TOKEN_VAR.encode())
            chim += len(re.findall(rb"(?i)chimege", b))
            if i.filename.startswith("assets/voicepack/"):
                vp.append(i)
        (fail if tok else ok)("AC 52", f"{apk.name}: {tok} occurrences of {TOKEN_VAR}")
        (fail if chim else ok)("AC 6", f"{apk.name}: {chim} occurrences of 'chimege'")
        aac = [i for i in vp if i.filename.endswith(".aac")]
        stored = [i for i in aac if i.compress_type == zipfile.ZIP_STORED]
        print(f"     {apk.name}: {apk.stat().st_size} bytes, assets/voicepack: {len(vp)} entries "
              f"({len(aac)} .aac, {len(stored)} stored uncompressed, {sum(i.file_size for i in vp)} bytes)")


def cmd_pack(a):
    d = pathlib.Path(a.dir)
    mb = (d / "manifest.json").read_bytes()
    m = json.loads(mb)
    size = sum(p.stat().st_size for p in d.rglob("*") if p.is_file())
    print(f"     voiceId={m['voiceId']} packVersion={m['packVersion']} manifest sha256={hashlib.sha256(mb).hexdigest()}")
    print(f"     vendor={m['source']['vendor']} entries={len(m['entries'])} pack bytes={size}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    i = s.add_parser("inventory")
    i.add_argument("--manifest")
    i.add_argument("--asset")
    g = s.add_parser("golden")
    g.add_argument("file")
    g.add_argument("--baseline")
    sc = s.add_parser("scan")
    sc.add_argument("--apk", nargs="*")
    pk = s.add_parser("pack")
    pk.add_argument("dir")
    a = p.parse_args()
    {"inventory": cmd_inventory, "golden": cmd_golden, "scan": cmd_scan, "pack": cmd_pack}[a.cmd](a)
    print(f"\n{len(failures)} failure(s)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""NAV-016 QA: reason-code parity between the generator `voicepack verify` and the Gradle guard (task breakdown §3.2,
pack-format §8 / §8.1, NAV-016 AC 20, AC 44). Stdlib only. Never calls any network service.

  python3 -I tests/android/nav016/reason_code_parity.py <valid pack dir> <empty work dir>

Builds one corrupted copy per case under <work dir>, then runs:
  - generator: make -C backend voice-pack-verify DIR=<case> POLICY=debug   (reference image; VOICE_PACK_RUNNER honoured)
  - Gradle guard: java -cp mobile/android/buildSrc/build/libs/buildSrc.jar tests/android/nav016/GuardProbe.java
and compares each with the code pack-format §8.1 expects. Exit 1 on any mismatch.
"""
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from qa_nav016_checks import inventory  # noqa: E402  (QA's own canonical inventory)

GEN_ONLY = "UNLISTED_FILE"


def dump(m):
    return json.dumps(m, ensure_ascii=False, indent=2) + "\n"


def edit(fn):
    def apply(d):
        p = d / "manifest.json"
        m = json.loads(p.read_text(encoding="utf-8"))
        r = fn(m)
        p.write_text(r if isinstance(r, str) else dump(m), encoding="utf-8")
    return apply


def raw(fn):
    def apply(d):
        p = d / "manifest.json"
        p.write_text(fn(p.read_text(encoding="utf-8")), encoding="utf-8")
    return apply


def flip_byte(d):
    p = d / "clips/instr.turn.right.final.aac"
    b = bytearray(p.read_bytes())
    b[200] ^= 1
    p.write_bytes(bytes(b))


def swap_entries(m):
    m["entries"][0], m["entries"][1] = m["entries"][1], m["entries"][0]


def two_fault(m):
    m["codec"]["bitrateKbps"] = 999
    m["codec"]["name"] = "mp3"


def self_consistent_old_wording(m):
    # A pack "from older wording": one spoken text differs, inventorySha256 recomputed from the entries, so V7's own
    # consistency holds and only the comparison with the compiled value fails (pack-format §8, debug rule).
    e = next(x for x in m["entries"] if x["key"] == "join.then")
    e["spokenText"] = "дараа нь бас"
    canon = "".join(f"{x['key']}\t{x['take']}\t{x['spokenText']}\n" for x in m["entries"]).encode()
    m["inventorySha256"] = hashlib.sha256(canon).hexdigest()


# (id, mutation, expected code per pack-format §8 / §8.1 for both implementations; generator-only codes noted)
CASES = [
    ("c01-byte-changed", flip_byte, "FILE_HASH_MISMATCH"),
    ("c02-file-deleted", lambda d: (d / "clips/dist.m.300.final.aac").unlink(), "FILE_MISSING"),
    ("c03-manifest-truncated", raw(lambda t: t[: len(t) // 2]), "MANIFEST_UNREADABLE"),
    ("c04-inventory-sha-changed", edit(lambda m: m.__setitem__("inventorySha256", "0" * 64)), "INVENTORY_MISMATCH"),
    ("c05-schema-99", edit(lambda m: m.__setitem__("schemaVersion", 99)), "SCHEMA_UNSUPPORTED"),
    ("c06-top-level-array", raw(lambda t: "[" + t.rstrip("\n") + "]\n"), "MANIFEST_UNREADABLE"),
    ("c07-schema-string", edit(lambda m: m.__setitem__("schemaVersion", "1")), "SCHEMA_UNSUPPORTED"),
    ("c08-schema-1.0", raw(lambda t: t.replace('"schemaVersion": 1,', '"schemaVersion": 1.0,', 1)), "SCHEMA_UNSUPPORTED"),
    ("c09-packversion-1.0", raw(lambda t: t.replace('"packVersion": 1,', '"packVersion": 1.0,', 1)), "MANIFEST_UNREADABLE"),
    ("c10-bitrate-out-of-range", edit(lambda m: m["codec"].__setitem__("bitrateKbps", 999)), "MANIFEST_UNREADABLE"),
    ("c11-codec-name", edit(lambda m: m["codec"].__setitem__("name", "mp3")), "CODEC_UNSUPPORTED"),
    ("c12-two-fault-bitrate-and-name", edit(two_fault), "MANIFEST_UNREADABLE"),
    ("c13-file-escape", edit(lambda m: m["entries"][0].__setitem__("file", "../x.aac")), "ENTRY_INVALID"),
    ("c14-voiceid-trailing-newline", edit(lambda m: m.__setitem__("voiceId", m["voiceId"] + "\n")), "MANIFEST_UNREADABLE"),
    ("c15-extra-top-level-member", edit(lambda m: m.__setitem__("builtAt", "x")), "MANIFEST_UNREADABLE"),
    ("c16-unlisted-extra-file", lambda d: (d / "clips/extra.final.aac").write_bytes(b"x"), "OK|" + GEN_ONLY),
    ("c17-entries-unsorted", edit(swap_entries), "INVENTORY_MISMATCH"),
    ("c18-duration-not-frames", edit(lambda m: m["entries"][0].__setitem__("durationMs", m["entries"][0]["durationMs"] + 50)), "ENTRY_INVALID"),
    ("c19-codec-member-missing", edit(lambda m: m["codec"].pop("encoder")), "MANIFEST_UNREADABLE"),
    ("c20-old-wording-self-consistent", edit(self_consistent_old_wording), "INVENTORY_MISMATCH"),
    ("c21-control-char-in-spoken", edit(lambda m: m["entries"][0].__setitem__("spokenText", m["entries"][0]["spokenText"] + "\u0007")), "MANIFEST_UNREADABLE"),
]


def gen_code(case_dir):
    r = subprocess.run(["make", "-s", "-C", str(REPO / "backend"), "voice-pack-verify", f"DIR={case_dir}", "POLICY=debug"],
                       capture_output=True, text=True, check=False)
    out = r.stdout + r.stderr
    if r.returncode == 0 and re.search(r"^OK \(debug\)", out, re.M):
        return "OK"
    m = re.search(r"FAIL ([A-Z_]+)", out)
    return m.group(1) if m else "UNPARSED:" + out.strip().splitlines()[-1][:80] if out.strip() else "UNPARSED"


def main():
    src, work = pathlib.Path(sys.argv[1]).resolve(), pathlib.Path(sys.argv[2]).resolve()
    work.mkdir(parents=True, exist_ok=True)
    if any(work.iterdir()):
        sys.exit(f"{work} must be empty")
    dirs = []
    for cid, mutate, _ in CASES:
        d = work / cid
        shutil.copytree(src, d)
        mutate(d)
        dirs.append(d)
    compiled = hashlib.sha256(inventory()[1]).hexdigest()
    jar = REPO / "mobile/android/buildSrc/build/libs/buildSrc.jar"
    probe = subprocess.run(["java", "-cp", str(jar), str(pathlib.Path(__file__).with_name("GuardProbe.java")), compiled, *map(str, dirs)],
                           capture_output=True, text=True, check=False)
    guard = dict(ln.split("\t") for ln in probe.stdout.splitlines() if "\t" in ln)
    if not guard:
        sys.exit("GuardProbe produced no output:\n" + probe.stderr[-2000:])
    bad = 0
    print(f"{'case':36} {'expected':28} {'generator':22} {'gradle guard':22} result")
    for (cid, _, exp), d in zip(CASES, dirs):
        g = gen_code(d)
        j = guard.get(cid, "MISSING")
        if "|" in exp:  # expected difference: guard OK, generator-only code
            want_j, want_g = exp.split("|")
        else:
            want_j = want_g = exp
        ok = g == want_g and j == want_j
        bad += not ok
        print(f"{cid:36} {exp:28} {g:22} {j:22} {'ok' if ok else 'MISMATCH'}")
    print(f"\n{bad} mismatch(es) of {len(CASES)} cases")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()

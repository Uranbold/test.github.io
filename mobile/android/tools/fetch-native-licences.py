#!/usr/bin/env python3
"""Fetches the third-party licence lists of the shipped native libraries (NAV-005 AC 90, 96, 98; ADR-0017 A5 §1–2).

Run by the mobile engineer, with network, when a native library's version changes (A5 §2). The output is committed under
mobile/android/licenses/ and read offline by tools/gen-third-party-notices.py, which pins each list to its library version
(a version bump without a new list fails `checkThirdPartyNotices`). Nothing here runs during a Gradle build.

    python3 tools/fetch-native-licences.py maplibre            # version from gradle/libs.versions.toml (maplibre)
    python3 tools/fetch-native-licences.py ferrostar --apk app/build/outputs/apk/debug/app-debug.apk
    python3 tools/fetch-native-licences.py ferrostar --rustc 1.98.1

maplibre  → licenses/maplibre-native-android-v<version>/
    LICENSES.core.md            upstream's third-party list at tag android-v<version>, verbatim
    core/<slug>.txt             each component's licence block of that list, cut out verbatim (fence contents)
    extra/<name>                verbatim licence files of vendored code that libmaplibre.so links but upstream's list
                                omits (ICU, SQLite source id, PMTiles, maplibre-tile-spec, jni.hpp); see EXTRA below
ferrostar → licenses/ferrostar-<version>/
    crates.json                 the crates compiled into libferrostar.so: `cargo tree -e normal,no-proc-macro --target
                                aarch64-linux-android` of the published ferrostar crate with its Cargo.lock (Ferrostar's
                                Android AAR builds `-p ferrostar` with default features), plus the Rust standard library
                                of the rustc that built the AAR (its `.comment` section) with std's registry dependencies
                                from rust-lang/rust library/Cargo.lock at that tag
    <crate>-<version>/<file>    the elected licence file of each crate, verbatim from the .crate archive (SHA-256 checked
                                against the Cargo.lock checksum)

Licence election for "A OR B" expressions follows ELECTION_ORDER (MIT first, then Apache-2.0, …), the same way JNA is used
under its Apache-2.0 option (ADR-0009 §11). Copyright lines come from the elected file; when it has none, from the crate's
`authors` (the crate metadata, as AC 91 allows for POMs); when that is empty, "The <crate> developers".
"""
import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import tomllib
import urllib.request
import zipfile

ANDROID = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(os.path.dirname(ANDROID))
LICENSES = os.path.join(ANDROID, "licenses")
RAW = "https://raw.githubusercontent.com"
CRATES = "https://static.crates.io/crates"
TARGET = "aarch64-linux-android"

# Vendored code linked into libmaplibre.so (platform/android/android.cmake: mbgl-vendor-icu, mbgl-vendor-sqlite; root
# CMakeLists.txt: mbgl-vendor-pmtiles, mlt-cpp, unordered_dense; maplibre-native-base: jni.hpp) that upstream's
# LICENSES.core.md does not list. (name, repo, ref or submodule path, file in that repo).
EXTRA = [
    ("icu-LICENSE", "maplibre/maplibre-native", None, "vendor/icu/LICENSE"),
    ("icu-version.txt", "maplibre/maplibre-native", None, "vendor/icu/version.txt"),
    ("sqlite-version.txt", "maplibre/maplibre-native", None, "vendor/sqlite/version.txt"),
    ("PMTiles-LICENSE", "protomaps/PMTiles", "vendor/PMTiles", "LICENSE"),
    ("maplibre-tile-spec-LICENSE-MIT", "maplibre/maplibre-tile-spec", "vendor/maplibre-tile-spec", "LICENSE-MIT"),
    ("unordered_dense-LICENSE", "martinus/unordered_dense", "vendor/unordered_dense", "LICENSE"),
    ("jni.hpp-LICENSE.txt", "mapbox/jni.hpp", "vendor/maplibre-native-base/deps/jni.hpp", "LICENSE.txt"),
]

ELECTION_ORDER = ["MIT", "Apache-2.0", "BSD-3-Clause", "BSD-2-Clause", "BSL-1.0", "ISC", "Zlib", "Unicode-3.0", "MPL-2.0"]
# Rust standard library crates of the sysroot that ship in an Android cdylib (std's dependencies for target_os="android":
# backtrace support via addr2line/gimli/object/miniz_oxide, hashbrown, libc, …). In-tree crates are covered by the Rust
# project licence; registry crates are listed one by one with their own licence files.
STD_IN_TREE = ["std", "core", "alloc", "panic_unwind", "panic_abort", "unwind", "std_detect", "rustc-std-workspace-core",
               "rustc-std-workspace-alloc"]
STD_REGISTRY = ["addr2line", "gimli", "object", "memchr", "miniz_oxide", "adler2", "hashbrown", "cfg-if", "libc", "rustc-demangle"]


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def versions_toml():
    with open(os.path.join(ANDROID, "gradle/libs.versions.toml"), "rb") as f:
        return tomllib.load(f)["versions"]


def slug(name):
    return re.sub(r"[^A-Za-z0-9.+-]+", "-", name).strip("-")


def core_sections(text):
    """Upstream LICENSES.core.md → [(heading name, fence contents)]. Contents are cut out byte-for-byte (+ final newline)."""
    out = []
    for block in re.split(r"^### ", text, flags=re.M)[1:]:
        head = block.split("\n", 1)[0]
        name = re.match(r"\[([^\]]+)\]", head).group(1)
        m = re.search(r"\n```\n(.*?\n)```\n", block, re.S)
        if not m:
            raise SystemExit(f"LICENSES.core.md: section {name} has no fenced licence block")
        out.append((name, m.group(1)))
    return out


def submodule_commit(repo, tag, path):
    """Commit a submodule path points at in repo@tag (a blobless, depth-1 fetch of the tag; only trees are read)."""
    with tempfile.TemporaryDirectory() as d:
        subprocess.run(["git", "init", "-q", d], check=True)
        subprocess.run(["git", "-C", d, "fetch", "-q", "--depth", "1", "--filter=blob:none", f"https://github.com/{repo}.git", f"refs/tags/{tag}"], check=True)
        line = subprocess.run(["git", "-C", d, "ls-tree", "FETCH_HEAD", path], capture_output=True, text=True, check=True).stdout.split()
    if len(line) < 3 or line[1] != "commit":
        raise SystemExit(f"{path} is not a submodule at {repo}@{tag}")
    return line[2]


def cmd_maplibre(version):
    tag = f"android-v{version}"
    out = os.path.join(LICENSES, f"maplibre-native-{tag}")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(os.path.join(out, "core"))
    os.makedirs(os.path.join(out, "extra"))
    core = fetch(f"{RAW}/maplibre/maplibre-native/{tag}/LICENSES.core.md")
    with open(os.path.join(out, "LICENSES.core.md"), "wb") as f:
        f.write(core)
    for name, body in core_sections(core.decode("utf-8")):
        with open(os.path.join(out, "core", slug(name) + ".txt"), "w", encoding="utf-8", newline="") as f:
            f.write(body)
    sources = {"tag": tag, "LICENSES.core.md": f"{RAW}/maplibre/maplibre-native/{tag}/LICENSES.core.md", "extra": {}}
    for name, repo, sub, path in EXTRA:
        ref = tag if sub is None else submodule_commit("maplibre/maplibre-native", tag, sub)
        url = f"{RAW}/{repo}/{ref}/{path}"
        with open(os.path.join(out, "extra", name), "wb") as f:
            f.write(fetch(url))
        sources["extra"][name] = url
    with open(os.path.join(out, "SOURCES.json"), "w", encoding="utf-8") as f:
        json.dump(sources, f, indent=1)
        f.write("\n")
    print(f"wrote {os.path.relpath(out, REPO)}: {len(core_sections(core.decode('utf-8')))} upstream sections, {len(EXTRA)} extra files")


def alternatives(expr):
    """SPDX expression → list of alternatives, each a list of licences that all apply (AND). Handles "/" (old OR)."""
    expr = expr.replace("/", " OR ")
    alts = []
    for alt in re.split(r"\s+OR\s+", re.sub(r"[()]", "", expr)):
        alts.append([x.strip() for x in re.split(r"\s+AND\s+", alt)])
    return alts


def elect(expr):
    if " AND " in expr and "(" in expr:
        return expr  # conjunctive expressions (compiler_builtins) apply as a whole
    alts = alternatives(expr)
    for want in ELECTION_ORDER:
        for a in alts:
            if a == [want]:
                return want
    if len(alts) == 1:
        return " AND ".join(alts[0])
    raise SystemExit(f"cannot elect a licence from {expr!r}")


LICENCE_FILE = re.compile(r"^(LICEN[CS]E|COPYING|UNLICENSE)([-_.].*)?$", re.I)


def pick_file(files, elected):
    """files: {name: bytes} of the crate root. The licence file for the elected licence, or None."""
    cands = {n: b for n, b in files.items() if LICENCE_FILE.match(n)}
    key = {"MIT": "MIT", "Apache-2.0": "APACHE", "BSD-3-Clause": "BSD", "BSD-2-Clause": "BSD", "Zlib": "ZLIB", "ISC": "ISC",
           "MPL-2.0": "MPL"}.get(elected)
    if key:
        named = sorted(n for n in cands if key in n.upper())
        if named:
            return named[0]
    marker = {"MIT": b"Permission is hereby granted", "Apache-2.0": b"Apache License", "MPL-2.0": b"Mozilla Public License",
              "ISC": b"Permission to use, copy, modify, and/or distribute", "Zlib": b"This software is provided 'as-is'",
              "BSD-3-Clause": b"Redistribution and use", "BSD-2-Clause": b"Redistribution and use"}.get(elected)
    plain = sorted(n for n in cands if not re.search(r"(MIT|APACHE|UNLICENSE|ZLIB|BSD|ISC|MPL)", n.upper()))
    for n in plain:
        if marker is None or marker in cands[n]:
            return n
    return None


COPYRIGHT = re.compile(r"^\s*Copyright\s*(?:\(c\)|\(C\)|©)?\s*(.+?)\s*$")


def copyright_lines(text):
    out = []
    for line in text.decode("utf-8", "replace").splitlines():
        m = COPYRIGHT.match(line)
        if m and not re.search(r"[\[\]{}<>]|notice|owner|holder", m.group(1), re.I):
            h = re.sub(r"\s+All rights reserved\.?$", "", m.group(1)).strip()
            if h and h not in out:
                out.append(h)
    return out


def crate_files(name, version, checksum):
    data = fetch(f"{CRATES}/{name}/{name}-{version}.crate")
    sha = hashlib.sha256(data).hexdigest()
    if checksum and sha != checksum:
        raise SystemExit(f"{name}-{version}.crate: checksum {sha} != Cargo.lock {checksum}")
    files, manifest = {}, None
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as t:
        for m in t.getmembers():
            parts = m.name.split("/")
            if m.isfile() and len(parts) == 2:
                files[parts[1]] = t.extractfile(m).read()
    manifest = tomllib.loads(files["Cargo.toml"].decode("utf-8"))
    return files, manifest, sha


REPO_NAMES = ["LICENSE-MIT", "LICENSE-APACHE", "LICENSE", "LICENSE.md", "LICENSE.txt", "LICENSE-MIT.md", "LICENSE-APACHE.md",
              "LICENSE-MIT.txt", "LICENSE-APACHE.txt", "COPYING"]


def repo_licence(name, version, pkg, files, elected):
    """A crate published without a licence file: the file of its repository at the commit the crate was published from
    (.cargo_vcs_info.json), in the crate's directory first and then at the repository root."""
    repo = pkg.get("repository", "")
    m = re.match(r"https://github\.com/([^/]+/[^/#?]+?)(?:\.git)?/?$", repo)
    vcs = json.loads(files.get(".cargo_vcs_info.json", b"{}"))
    sha = vcs.get("git", {}).get("sha1")
    if not m or not sha:
        raise SystemExit(f"{name}-{version}: no licence file in the .crate and no GitHub repository/commit to take it from")
    dirs = [vcs.get("path_in_vcs", "").strip("/"), ""]
    found = {}
    for d in dict.fromkeys(dirs):
        for n in REPO_NAMES:
            url = f"{RAW}/{m.group(1)}/{sha}/{(d + '/') if d else ''}{n}"
            try:
                found[n] = (fetch(url), url)
            except Exception:
                continue
        if found:
            break
    fname = pick_file({n: b for n, (b, _) in found.items()}, elected)
    if fname is None:
        raise SystemExit(f"{name}-{version}: no licence file for {elected} in the .crate or at {repo}@{sha}")
    return fname, found[fname][0], found[fname][1]


def write_crate(out, name, version, checksum, expr=None, kind="crate"):
    files, manifest, sha = crate_files(name, version, checksum)
    pkg = manifest["package"]
    expr = expr or pkg.get("license")
    if not expr:
        raise SystemExit(f"{name}-{version}: no licence expression in Cargo.toml")
    elected = elect(expr)
    fname, source = pick_file(files, elected), "crate"
    if fname is None:
        fname, data, source = repo_licence(name, version, pkg, files, elected)
        files[fname] = data
    rel = os.path.join(f"{name}-{version}", fname)
    os.makedirs(os.path.join(out, f"{name}-{version}"), exist_ok=True)
    with open(os.path.join(out, rel), "wb") as f:
        f.write(files[fname])
    authors = [re.sub(r"\s*<[^>]*>", "", a).strip() for a in pkg.get("authors", []) if a.strip()]
    holder = ", ".join(authors) or f"the {name} developers"
    # A line that names only years ("Copyright (c) 2015") gets the crate's authors as the holder.
    cr = [c if re.search(r"[^\W\d_]", c) else f"{c} {holder}" for c in copyright_lines(files[fname])]
    if not cr:
        cr = authors or [f"The {name} developers"]
    return {"name": name, "version": version, "kind": kind, "license": expr, "elected": elected, "file": rel.replace(os.sep, "/"),
            "copyright": cr, "sha256": sha, "text_source": source, "repository": pkg.get("repository")}


def rustc_from_apk(apk):
    with zipfile.ZipFile(apk) as z:
        so = z.read("lib/arm64-v8a/libferrostar.so")
    m = re.search(rb"rustc version (\d+\.\d+\.\d+)", so)
    if not m:
        raise SystemExit("no rustc version in libferrostar.so .comment")
    return m.group(1).decode()


def cmd_ferrostar(version, rustc):
    out = os.path.join(LICENSES, f"ferrostar-{version}")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, f"ferrostar-{version}")
        with tarfile.open(fileobj=io.BytesIO(fetch(f"{CRATES}/ferrostar/ferrostar-{version}.crate")), mode="r:gz") as t:
            t.extractall(d, filter="data")
        tree = subprocess.run(["cargo", "tree", "--locked", "--target", TARGET, "-e", "normal,no-proc-macro", "--prefix", "none",
                               "--no-dedupe", "--format", "{p}"], cwd=src, capture_output=True, text=True, check=True).stdout
        with open(os.path.join(src, "Cargo.lock"), "rb") as f:
            lock = {(p["name"], p["version"]): p.get("checksum") for p in tomllib.load(f)["package"]}
    shipped = sorted({tuple(l.split(" ")[0:2]) for l in tree.splitlines() if l.strip()})
    crates = []
    for name, v in shipped:
        v = v.lstrip("v")
        if name == "ferrostar":
            continue
        crates.append(write_crate(out, name, v, lock[(name, v)]))
    # Rust standard library of the rustc that built the AAR.
    rlock = tomllib.loads(fetch(f"{RAW}/rust-lang/rust/{rustc}/library/Cargo.lock").decode())
    rpk = {p["name"]: p for p in rlock["package"]}
    std_dir = f"rust-{rustc}"
    os.makedirs(os.path.join(out, std_dir), exist_ok=True)
    for fn in ("LICENSE-MIT", "COPYRIGHT"):
        with open(os.path.join(out, std_dir, fn), "wb") as f:
            f.write(fetch(f"{RAW}/rust-lang/rust/{rustc}/{fn}"))
    with open(os.path.join(out, std_dir, "compiler-builtins-LICENSE.txt"), "wb") as f:
        f.write(fetch(f"{RAW}/rust-lang/rust/{rustc}/library/compiler-builtins/LICENSE.txt"))
    cb = tomllib.loads(fetch(f"{RAW}/rust-lang/rust/{rustc}/library/compiler-builtins/compiler-builtins/Cargo.toml").decode())
    std = [{"name": "Rust standard library (" + ", ".join(STD_IN_TREE) + ")", "version": rustc, "kind": "std",
            "license": "MIT OR Apache-2.0", "elected": "MIT", "file": f"{std_dir}/LICENSE-MIT",
            "copyright": ["The Rust Project Contributors"]},
           {"name": "compiler_builtins", "version": rpk["compiler_builtins"]["version"], "kind": "std",
            "license": cb["package"]["license"], "elected": "MIT AND Apache-2.0 WITH LLVM-exception",
            "file": f"{std_dir}/compiler-builtins-LICENSE.txt", "copyright": ["The Rust Project Contributors", "LLVM Project contributors"]}]
    for name in STD_REGISTRY:
        p = rpk[name]
        std.append(write_crate(out, name, p["version"], p.get("checksum"), kind="std"))
    manifest = {"schema": 1, "library": "libferrostar.so", "ferrostar": version, "rustc": rustc, "target": TARGET,
                "method": "cargo tree --locked --target aarch64-linux-android -e normal,no-proc-macro (published crate + Cargo.lock); "
                          "Rust std from rust-lang/rust library/Cargo.lock at the rustc tag",
                "crates": crates, "std": std}
    with open(os.path.join(out, "crates.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"wrote {os.path.relpath(out, REPO)}: {len(crates)} crates, {len(std)} std parts (rustc {rustc})")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("maplibre")
    m.add_argument("--version", default=None)
    f = sub.add_parser("ferrostar")
    f.add_argument("--version", default=None)
    g = f.add_mutually_exclusive_group(required=True)
    g.add_argument("--rustc")
    g.add_argument("--apk", help="read the rustc version from the APK's lib/arm64-v8a/libferrostar.so")
    a = ap.parse_args()
    v = versions_toml()
    if a.cmd == "maplibre":
        cmd_maplibre(a.version or v["maplibre"])
    else:
        cmd_ferrostar(a.version or v["ferrostar"], a.rustc or rustc_from_apk(a.apk))
    return 0


if __name__ == "__main__":
    sys.exit(main())

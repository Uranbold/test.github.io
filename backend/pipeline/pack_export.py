#!/usr/bin/env python3
"""NAV-022 static pack export: the latest published offline pack as a plain static tree for a static web host.

    make pack-export DEST=<dir> [NAV_ENV_FILE=<file> | PACKS_DIR=<NAV_DATA_ROOT>/packs] [HTACCESS=0] [LINK=1]
    python3 pipeline/pack_export.py export --dest DIR (--env-file FILE | --packs-dir DIR) [--no-htaccess] [--link]

DEST becomes the directory behind the app's pack base URL (`nav.packBaseUrl`, which ends in `/packs/`), with the same
names and paths as the gateway's `/packs/` location (openapi 0.6.x `getOfflinePackManifest`, `getOfflinePackFile`):

    DEST/.htaccess                          optional (LiteSpeed / Apache): types, no re-compression, cache headers
    DEST/mn/manifest.json                   byte-for-byte copy of the published manifest (upload LAST)
    DEST/mn/NOTICE.txt                      ODbL notice for the publication (attribution, licence, method, files)
    DEST/mn/<fileVersion>/<file>.gz         the files the manifest lists (basemap.pmtiles.gz, routing.tar.gz,
                                            search.sqlite.gz), exactly `download_bytes` / `download_sha256`
    DEST/mn/<fileVersion>/NOTICE.txt        ODbL notice for the files of that version

Only the files of the CURRENT manifest are exported (not the retained older ones). DEST must be empty, missing, or
an earlier export (only the names above); files that are already there with the right SHA-256 are kept, version
directories and files the manifest no longer lists are removed from DEST (keep them on the host until the next upload, see
backend/README.md "Static pack hosting"). DEST must be outside the git checkout and outside the packs directory.

Before anything is written, every source .gz is checked against download_sha256 (a mismatch exports nothing).
Order (mirrors the upload order): data files (temp name + rename), notices, .htaccess, manifest.json last. Then the
whole tree is verified from disk: size and SHA-256 of every .gz, its decompressed size and SHA-256, the manifest bytes,
the NAV-020 manifest rules (nav_pack.manifest_problems) and, when backend/.venv exists, the OfflinePackManifest schema.
If that verification fails, DEST/mn/manifest.json is deleted again so the tree cannot be uploaded by mistake.

Refused: a manifest whose licence.method_url is a documentation / reserved name (example.org, *.invalid, ...; NAV-020
review minor). A `.test` name (test projects) is exported with a warning: such a pack is for testing only.

Output: JSON lines (no hostnames, no coordinates). Exit codes: 0 ok, 2 usage/configuration, 3 refused, 4 verify failed.
"""
import argparse
import json
import os
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import nav_pipeline as base  # noqa: E402
import nav_pack  # noqa: E402

BACKEND = HERE.parent
REPO = BACKEND.parent
HTACCESS_TEMPLATE = BACKEND / "pack" / "static-host.htaccess"
NOTICE = "NOTICE.txt"
DATA_NAMES = {f"{n}.gz" for n in nav_pack.FILE_NAME.values()}
KIND_TEXT = {
    "tiles": "basemap (PMTiles v3, z0-14): a Produced Work made from OpenStreetMap data",
    "routing": "routing graph (Valhalla tiles tar): a Derivative Database of OpenStreetMap data",
    "search": "search database (SQLite): a Derivative Database of OpenStreetMap data",
}
ESA_CREDIT = ("The basemap's landcover layer (z0-7) is derived from Daylight landcover (CC BY 4.0): © ESA WorldCover "
              "project / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium.")


class Refused(Exception):
    code = 3


class Usage(Exception):
    code = 2


def emit(**kv):
    print(json.dumps({"job": "pack-export", **kv}, ensure_ascii=False), flush=True)


# ====================================================================== source
def packs_dir_from_env(env_file):
    try:
        cfg = base.Config(Path(env_file)).load()
    except base.ConfigError as e:
        raise Usage(f"{env_file}: {e}") from None
    return Path(cfg.root) / "packs"


def read_source(packs_dir, region):
    region_dir = Path(packs_dir) / region
    mpath = region_dir / "manifest.json"
    if not mpath.is_file():
        raise Refused(f"no published pack: {mpath} does not exist (run make pack-publish first)")
    raw = mpath.read_bytes()
    try:
        m = json.loads(raw.decode("utf-8"))
    except ValueError as e:
        raise Refused(f"{mpath} is not JSON: {e}") from None
    if m.get("region") != region:
        raise Refused(f"manifest region {m.get('region')!r} != {region!r}")
    problems = nav_pack.manifest_problems(m, region_dir)
    if problems:
        raise Refused("published manifest breaks the NAV-020 rules: " + "; ".join(problems))
    bad = [f["path"] for f in nav_pack.manifest_files(m).values()
           if base.sha256_file(region_dir / f["path"]) != f["download_sha256"]]
    if bad:
        raise Refused(f"published file(s) {', '.join(bad)}: SHA-256 differs from download_sha256 (nothing exported)")
    return region_dir, raw, m


def method_url_check(m):
    url = ((m.get("licence") or {}).get("method_url")) or ""
    if not url:
        raise Refused("manifest has no licence.method_url (ODbL §4.6)")
    why = nav_pack.placeholder_url(url, allow_test_tld=True)
    if why:
        raise Refused(f"manifest licence.method_url is a placeholder ({why}); republish with a real PACK_METHOD_URL")
    if nav_pack.placeholder_url(url, allow_test_tld=False):
        return "test-only: licence.method_url uses the reserved .test TLD; do not distribute this pack"
    return None


# ====================================================================== destination
def check_dest(dest, packs_dir, region):
    dest = Path(os.path.abspath(dest))
    real, repo, packs = (Path(os.path.realpath(p)) for p in (dest, REPO, packs_dir))
    for inside, what in ((repo, "the git checkout"), (packs, "the packs directory")):
        if real == inside or inside in real.parents:
            raise Usage(f"DEST must be outside {what}: {dest}")
    if real in packs.parents:
        raise Usage(f"DEST must not contain the packs directory: {dest}")
    if not dest.exists():
        return dest
    if not dest.is_dir():
        raise Usage(f"DEST is not a directory: {dest}")
    unexpected = []
    for e in dest.iterdir():
        if e.name == ".htaccess" and e.is_file():
            continue
        if e.name == region and e.is_dir():
            for f in e.iterdir():
                if f.is_file() and f.name in ("manifest.json", NOTICE):
                    continue
                if f.is_dir() and nav_pack.VERSION_RE.match(f.name):
                    unexpected += [str(x.relative_to(dest)) for x in f.iterdir()
                                   if not (x.is_file() and x.name in DATA_NAMES | {NOTICE})]
                    continue
                unexpected.append(str(f.relative_to(dest)))
            continue
        unexpected.append(e.name)
    if unexpected:
        raise Usage(f"DEST is not empty and not an earlier pack export (unexpected: {', '.join(sorted(unexpected)[:5])}); "
                    f"use an empty or new directory")
    return dest


def same_file(path, size, sha):
    return path.is_file() and path.stat().st_size == size and base.sha256_file(path) == sha


def place(src, dst, link):
    """Copy (or hard-link) src to dst through a temp name in the same directory, then rename(2)."""
    tmp = dst.with_name(f".{dst.name}.tmp-{os.getpid()}")
    if tmp.exists():
        tmp.unlink()
    if link:
        try:
            os.link(src, tmp)
        except OSError as e:
            raise Usage(f"LINK=1: cannot hard-link across filesystems or here ({e.strerror}); drop LINK=1") from None
    else:
        with open(src, "rb") as fi, open(tmp, "wb") as fo:
            shutil.copyfileobj(fi, fo, 1 << 20)
            fo.flush()
            os.fsync(fo.fileno())
        os.chmod(tmp, 0o644)
    os.rename(tmp, dst)


def write_text(path, text):
    tmp = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    tmp.write_text(text, encoding="utf-8")
    os.chmod(tmp, 0o644)
    os.rename(tmp, path)


# ====================================================================== notices
def notice_text(m, files, scope):
    lic = m.get("licence") or {}
    lines = [
        f"OSM Navigation Mongolia - offline pack, region {m.get('region')} ({scope})",
        "",
        "Contains information from OpenStreetMap, which is made available here under the",
        "Open Database License (ODbL) 1.0.",
        f"Attribution: {m.get('attribution')}",
        f"Licence: {lic.get('name')} {lic.get('url')}",
        f"How these files were produced (ODbL 4.6): {lic.get('method_url')}",
        "",
        "Files (gzip; after decompression each file has the listed SHA-256):",
    ]
    for f in files:
        lines.append(f"  {f['path']}  {f['kind']}  data {f.get('data_timestamp')}  sha256 {f.get('sha256')}")
        lines.append(f"      {KIND_TEXT[f['kind']]}")
    if any(f["kind"] == "tiles" for f in files):
        lines += ["", ESA_CREDIT]
    if scope.startswith("publication"):
        lines += ["", f"Publication {m.get('pack_version')}, published {m.get('published_at')}.",
                  "Clients read manifest.json; they never build file URLs from pack_version."]
    return "\n".join(lines) + "\n"


# ====================================================================== verify
def schema_python():
    for p in (os.environ.get("CONTRACT_PYTHON"), str(BACKEND / ".venv" / "bin" / "python")):
        if p and Path(p).exists():
            return p
    return None


def verify_tree(dest, region, raw, m, deep=True):
    rdir = dest / region
    problems = []
    if (rdir / "manifest.json").read_bytes() != raw:
        problems.append("manifest.json differs from the published manifest")
    problems += [f"rules: {x}" for x in nav_pack.manifest_problems(m, rdir)]
    checked = []
    for f in nav_pack.manifest_files(m).values():
        p = rdir / f["path"]
        if not p.is_file():
            problems.append(f"{f['path']}: missing")
            continue
        if p.stat().st_size != f["download_bytes"] or base.sha256_file(p) != f["download_sha256"]:
            problems.append(f"{f['path']}: size or SHA-256 differs from download_bytes / download_sha256")
            continue
        if deep:
            sha, n, _ = nav_pack.gunzip_digest(p)
            if (sha, n) != (f["sha256"], f["bytes"]):
                problems.append(f"{f['path']}: decompressed {n} B / {sha[:12]} != bytes / sha256 of the manifest")
                continue
        checked.append(f["path"])
    for d in [rdir] + sorted({rdir / f["version"] for f in m["files"]}):
        if not (d / NOTICE).is_file():
            problems.append(f"{(d / NOTICE).relative_to(dest)}: missing")
    schema = "not checked (no backend/.venv; make contract creates it)"
    py = schema_python()
    if py:
        r = nav_pack.validate_with_schema(py, m)
        schema = "ok" if not r else "failed"
        if r:
            problems.append(f"OfflinePackManifest schema: {r}")
    return problems, checked, schema


# ====================================================================== export
def export(packs_dir, dest, region="mn", htaccess=True, link=False, deep=True):
    region_dir, raw, m = read_source(packs_dir, region)
    warning = method_url_check(m)
    dest = check_dest(dest, packs_dir, region)
    rdir = dest / region
    rdir.mkdir(parents=True, exist_ok=True)
    for d in (dest, rdir):
        os.chmod(d, 0o755)
    files = sorted(nav_pack.manifest_files(m).values(), key=lambda f: nav_pack.KINDS.index(f["kind"]))
    copied, kept = [], []
    for f in files:                                   # 1. data files (immutable, versioned)
        vdir = rdir / f["version"]
        vdir.mkdir(exist_ok=True)
        os.chmod(vdir, 0o755)
        dst = rdir / f["path"]
        if same_file(dst, f["download_bytes"], f["download_sha256"]):
            kept.append(f["path"])
            continue
        place(region_dir / f["path"], dst, link)
        copied.append(f["path"])
    for v in sorted({f["version"] for f in files}):  # 2. notices
        write_text(rdir / v / NOTICE, notice_text(m, [f for f in files if f["version"] == v], f"files of version {v}"))
    write_text(rdir / NOTICE, notice_text(m, files, f"publication {m['pack_version']}"))
    ht = dest / ".htaccess"                           # 3. optional .htaccess
    if htaccess:
        write_text(ht, HTACCESS_TEMPLATE.read_text(encoding="utf-8"))
    elif ht.exists():
        ht.unlink()
    tmp = rdir / f".manifest.json.tmp-{os.getpid()}"  # 4. manifest last
    tmp.write_bytes(raw)
    os.chmod(tmp, 0o644)
    os.rename(tmp, rdir / "manifest.json")
    keep = {f["version"] for f in files}             # 5. versions / files the manifest no longer lists
    listed = {f["path"] for f in files}
    removed = []
    for d in sorted(rdir.iterdir()):
        if not (d.is_dir() and nav_pack.VERSION_RE.match(d.name)):
            continue
        if d.name not in keep:
            shutil.rmtree(d)
            removed.append(d.name + "/")
            continue
        for x in sorted(d.iterdir()):
            if x.name in DATA_NAMES and f"{d.name}/{x.name}" not in listed:
                x.unlink()
                removed.append(f"{d.name}/{x.name}")
    problems, checked, schema = verify_tree(dest, region, raw, m, deep)
    if problems:                                      # never leave an uploadable manifest over a bad tree
        (rdir / "manifest.json").unlink(missing_ok=True)
        problems.append("mn/manifest.json removed from DEST (do not upload this tree)")
    upload = []
    for p in sorted(dest.rglob("*")):
        if p.is_file():
            rel = p.relative_to(dest).as_posix()
            upload.append({"path": rel, "bytes": p.stat().st_size})
    order = ([u for u in upload if u["path"].endswith(".gz")] + [u for u in upload if u["path"].endswith(NOTICE)]
             + [u for u in upload if u["path"] == ".htaccess"] + [u for u in upload if u["path"].endswith("manifest.json")])
    result = "ok" if not problems else "verify failed"
    emit(level="info" if not problems else "error", result=result, dest=str(dest), region=region,
         pack_version=m["pack_version"], files={f["kind"]: f["path"] for f in files},
         total_download_bytes=m["total_download_bytes"], copied=copied, kept=kept, removed=removed or None,
         mode="hard links" if link else "copies", htaccess=htaccess, verified=checked,
         decompressed_check=deep, schema=schema, problems=problems or None, warning=warning)
    emit(level="info", msg="upload in this order (paths relative to the directory behind the pack base URL)",
         upload_order=[u["path"] for u in order], upload_bytes=sum(u["bytes"] for u in order))
    return 0 if not problems else 4


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("export", help="write the static tree of the latest published pack to --dest")
    src = e.add_mutually_exclusive_group(required=True)
    src.add_argument("--env-file", help="pipeline config (NAV_DATA_ROOT/packs is the source)")
    src.add_argument("--packs-dir", help="the packs directory itself (NAV_DATA_ROOT/packs)")
    e.add_argument("--dest", required=True)
    e.add_argument("--region", default="mn")
    e.add_argument("--no-htaccess", action="store_true", help="do not write DEST/.htaccess")
    e.add_argument("--link", action="store_true", help="hard-link the .gz files instead of copying (same filesystem)")
    e.add_argument("--quick", action="store_true", help="skip the decompression check of the exported files")
    a = ap.parse_args(argv)
    try:
        if a.region != "mn":
            raise Usage("region must be mn (the only region, ADR-0017 §1)")
        packs = packs_dir_from_env(a.env_file) if a.env_file else Path(os.path.abspath(a.packs_dir))
        return export(packs, a.dest, a.region, htaccess=not a.no_htaccess, link=a.link, deep=not a.quick)
    except (Usage, Refused) as ex:
        emit(level="error", result="refused" if isinstance(ex, Refused) else "usage", msg=str(ex), exit_code=ex.code)
        return ex.code


if __name__ == "__main__":
    sys.exit(main())

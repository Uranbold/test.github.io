#!/usr/bin/env python3
"""NAV-001 repository/configuration checks: AC 3, 6, 7, 8 (no running stack needed).

    python3 tests/api/nav001/static_checks.py [--repo PATH]

Python 3.9+ stdlib; uses `git` and (optionally) `docker compose config` from PATH.
Exit 0 only if every check passed.
"""
import argparse
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import Report  # noqa: E402

BBBIKE = "https://download.bbbike.org/osm/bbbike/UlanBator/UlanBator.osm.pbf"
GEOFABRIK = "https://download.geofabrik.de/asia/mongolia-latest.osm.pbf"
DATA_EXT = re.compile(r"\.(osm\.pbf|pbf|pmtiles|mbtiles)$", re.I)
ARTEFACT = re.compile(r"(valhalla_tiles(\.tar)?|photon_data|\.pmtiles|\.mbtiles|\.pbf)(/|$|\s)", re.I)


def run(cmd, cwd):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)


def ac03(r, be):
    path = os.path.join(be, ".env.example")
    lines = open(path, encoding="utf-8").read().splitlines()
    kv = {}
    uncommented, shared = [], []
    key_re = re.compile(r"^([A-Z][A-Z0-9_]*)=(.*)$")
    for i, ln in enumerate(lines):
        m = key_re.match(ln)
        if m:
            kv[m.group(1)] = m.group(2)
            if i > 0 and lines[i - 1].lstrip().startswith("#"):
                continue
            # A pair such as "URL + its sha256" under one comment that names both is accepted (recorded as info).
            if i > 1 and key_re.match(lines[i - 1]) and lines[i - 2].lstrip().startswith("#"):
                shared.append(m.group(1))
                continue
            uncommented.append(m.group(1))
    if shared:
        r.note("AC03.keys_sharing_comment_with_previous_key", shared)
    r.check("AC03.osm_url_default_bbbike", kv.get("OSM_PBF_URL") == BBBIKE, f"OSM_PBF_URL={BBBIKE}", kv.get("OSM_PBF_URL"))
    r.check("AC03.geofabrik_commented", any(re.match(r"^#\s*OSM_PBF_URL=" + re.escape(GEOFABRIK) + r"\s*$", ln) for ln in lines),
            f"# OSM_PBF_URL={GEOFABRIK}", "not found")
    r.check("AC03.local_file_key", "OSM_PBF_FILE" in kv, "OSM_PBF_FILE key present", sorted(k for k in kv if "FILE" in k))
    r.check("AC03.gateway_port_8080", kv.get("GATEWAY_PORT") == "8080", "GATEWAY_PORT=8080", kv.get("GATEWAY_PORT"))
    r.check("AC03.cors_default_any", kv.get("CORS_ALLOWED_ORIGINS") == "*", "CORS_ALLOWED_ORIGINS=*", kv.get("CORS_ALLOWED_ORIGINS"))
    r.check("AC03.every_key_commented", not uncommented, "a comment line directly above every key", uncommented)
    secretish = {k: v for k, v in kv.items()
                 if re.search(r"(SECRET|PASSWORD|PASSWD|TOKEN|API_KEY|ACCESS_KEY|PRIVATE)", k) and v.strip()}
    creds_in_urls = [k for k, v in kv.items() if re.search(r"://[^/@\s]+:[^/@\s]+@", v)]
    r.check("AC03.no_secrets", not secretish and not creds_in_urls, "no secret-like keys with values, no user:pass@ in URLs",
            {"keys": secretish, "urls_with_credentials": creds_in_urls})
    r.note("AC03.key_count", len(kv))


def ac06(r, repo, be):
    st = run(["git", "status", "--porcelain", "--untracked-files=all"], repo).stdout.splitlines()
    bad_st = [ln for ln in st if DATA_EXT.search(ln.split()[-1]) or ARTEFACT.search(ln)]
    r.check("AC06.git_status_no_data_files", not bad_st, "no .pbf/.pmtiles/.mbtiles/graph/index in git status", bad_st[:5])
    ls = run(["git", "ls-files"], repo).stdout.splitlines()
    bad_ls = [f for f in ls if DATA_EXT.search(f) or ARTEFACT.search(f + "\n")]
    r.check("AC06.git_ls_files_no_data_files", not bad_ls, "none tracked", bad_ls[:5])
    gi = open(os.path.join(be, ".gitignore"), encoding="utf-8").read().splitlines()
    r.check("AC06.data_dir_ignored", any(ln.strip() in ("data/", "/data/", "data", "/data") for ln in gi), "data/ in backend/.gitignore", gi)
    chk = run(["git", "check-ignore", "-q", "backend/data/tiles/basemap.pmtiles"], repo)
    r.check("AC06.check_ignore_pmtiles", chk.returncode == 0, "backend/data/... ignored by git", f"git check-ignore rc={chk.returncode}")
    chk = run(["git", "check-ignore", "-q", "backend/.env"], repo)
    r.check("AC06.env_file_ignored", chk.returncode == 0, "backend/.env ignored (holds local paths)", f"rc={chk.returncode}")


def ac07(r, be):
    text = open(os.path.join(be, "compose.yaml"), encoding="utf-8").read()
    images = re.findall(r"^\s*image:\s*(\S+)\s*$", text, re.M)
    r.check("AC07.images_found", bool(images), ">= 1 image", images)
    not_overridable, unpinned = [], []
    for img in images:
        m = re.fullmatch(r"\$\{([A-Z0-9_]+):-([^}]+)\}", img)
        if not m:
            not_overridable.append(img)
            default = img
        else:
            default = m.group(2)
        ref = default.split("@")[0]
        name_tag = ref.rsplit("/", 1)[-1]
        if ":" not in name_tag and "@sha256:" not in default:
            unpinned.append(default)
        elif name_tag.endswith(":latest"):
            unpinned.append(default)
    r.check("AC07.every_image_overridable_from_env", not not_overridable, "image: ${VAR:-default} everywhere", not_overridable)
    r.check("AC07.no_latest_or_untagged", not unpinned, "explicit tag or digest", unpinned)
    env_keys = set(re.findall(r"\$\{([A-Z0-9_]+):-", " ".join(images)))
    example = open(os.path.join(be, ".env.example"), encoding="utf-8").read()
    missing = [k for k in sorted(env_keys) if not re.search(rf"^{k}=", example, re.M)]
    r.check("AC07.image_keys_in_env_example", not missing, "every image key listed in .env.example", missing)
    cfg = run(["docker", "compose", "--env-file", "/dev/null", "config", "--images"], be)
    if cfg.returncode == 0:
        resolved = cfg.stdout.split()
        bad = [i for i in resolved if i.endswith(":latest") or ":" not in i.rsplit("/", 1)[-1]]
        r.check("AC07.resolved_images_pinned", not bad, "docker compose config --images: no :latest/untagged", bad or resolved)
    else:
        r.skip("AC07.resolved_images_pinned", f"docker compose config failed: {cfg.stderr[-200:]}")


def ac08(r, be):
    readme = open(os.path.join(be, "README.md"), encoding="utf-8").read()
    for cid, pats in {
        "AC08.cmd_start": [r"docker compose up", r"make up"],
        "AC08.cmd_stop": [r"docker compose down", r"make down"],
        "AC08.cmd_rebuild": [r"make rebuild-data", r"docker compose run --rm"],
        "AC08.cmd_smoke": [r"make smoke", r"tests/smoke/run\.sh"],
    }.items():
        r.check(cid, any(re.search(p, readme) for p in pats), f"one of {pats}", "not found")
    r.check("AC08.gateway_port", "8080" in readme and "GATEWAY_PORT" in readme, "port 8080 and GATEWAY_PORT", "missing")
    sym = {"HEALTH": "/health", "TILES": "/tiles/basemap.pmtiles", "ROUTE": "/v1/route", "SEARCH": "/v1/search", "REVERSE": "/v1/reverse"}
    missing = [f"{s}->{p}" for s, p in sym.items() if not re.search(rf"`{s}`.*{re.escape(p)}", readme)]
    r.check("AC08.symbolic_endpoints_mapped", not missing, "every symbol mapped to its path on one line", missing)
    example = open(os.path.join(be, ".env.example"), encoding="utf-8").read()
    url_keys = re.findall(r"^([A-Z0-9_]+_URL)=", example, re.M)
    missing = [k for k in url_keys if k not in readme]
    r.check("AC08.every_download_key_documented", url_keys and not missing, f"all *_URL keys in README ({len(url_keys)})", missing)
    r.check("AC08.not_run_section", re.search(r"(?i)could not be run|not (be )?verified", readme) is not None,
            "a section listing what could not run", "missing")
    r.check("AC08.extract_bbox_recorded", re.search(r"106\.8392.*47\.8995.*107\.0167.*47\.9337", readme) is not None,
            "dev extract bbox in README (story: reference points section)", "missing")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..")))
    a = ap.parse_args()
    be = os.path.join(a.repo, "backend")
    rep = Report()
    print(f"NAV-001 static checks: repo={a.repo}")
    for fn, args in ((ac03, (rep, be)), (ac06, (rep, a.repo, be)), (ac07, (rep, be)), (ac08, (rep, be))):
        try:
            fn(*args)
        except Exception as e:  # noqa: BLE001
            rep.check(f"{fn.__name__.upper()}.crashed", False, "no exception", f"{type(e).__name__}: {e}")
    print("\n" + rep.summary())
    sys.exit(1 if rep.failed else 0)


if __name__ == "__main__":
    main()

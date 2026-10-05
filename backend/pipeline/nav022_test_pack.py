#!/usr/bin/env python3
"""NAV-022 TEST ONLY: publish a small synthetic offline pack (a few hundred KB) into a scratch packs directory, in the
exact layout the NAV-020 pack step writes (NAV_DATA_ROOT/packs/mn/<version>/<file>.gz + manifest.json), so
`pack_export.py` and a static host can be tested without the 6-12 GB Gate 2 engine or real data.

    python3 pipeline/nav022_test_pack.py --packs-dir DIR --version 20261001T000000Z            # all three files
    python3 pipeline/nav022_test_pack.py --packs-dir DIR --version 20261008T000000Z --keep-tiles  # weekly cut

Files: tiles = a PMTiles v3 header (z0-14, Mongolia bounds) + incompressible filler, routing = a real tar with one
filler member, search = a real search.sqlite from pack/search_builder.py (licence meta included) built from a
three-place dump. Gzip = nav_pack.gzip_file (deterministic). The manifest passes nav_pack.manifest_problems and, when
backend/.venv exists, the OfflinePackManifest schema. NOT a routable pack: the app's self-tests would reject it.
"""
import argparse
import hashlib
import io
import json
import os
import struct
import sys
import tarfile
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "pack"))
import nav_pipeline as base  # noqa: E402
import nav_pack  # noqa: E402
import search_builder  # noqa: E402

METHOD_URL = "https://pipeline.navmn.test/osm-navigation"     # .test: test packs only


def filler(seed, n):
    out, block = bytearray(), seed.encode()
    while len(out) < n:
        block = hashlib.sha256(block).digest()
        out += block
    return bytes(out[:n])


def tiles_bytes(version, n=256 * 1024):
    h = bytearray(127)
    h[:7] = b"PMTiles"
    h[7] = 3
    h[100], h[101] = 0, 14
    struct.pack_into("<iiii", h, 102, *(int(round(v * 1e7)) for v in (87.7, 41.5, 119.95, 52.2)))
    return bytes(h) + filler("tiles" + version, n)


def routing_bytes(version, n=192 * 1024):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as t:
        data = filler("routing" + version, n)
        info = tarfile.TarInfo("valhalla_tiles/2/000/000/000.gph")
        info.size, info.mtime, info.mode = len(data), 0, 0o644
        t.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def search_db(out):
    def p(pid, oid, names, lon, lat):
        return {"place_id": str(pid), "osm_key": "place", "osm_value": "square", "categories": [], "address_type": "other",
                "importance": 0.5, "country_code": "mn", "centroid": [lon, lat], "bbox": [lon, lat, lon, lat],
                "address": {"city": "Улаанбаатар"}, "object_type": "N", "object_id": oid, "name": names}
    dump = [{"type": "NominatimDumpFile", "content": {"version": "0.1.0", "data_timestamp": "2026-09-26T22:59:05.000+00:00",
                                                       "database_version": "test"}},
            {"type": "Place", "content": [p(1, 1, {"name": "Сүхбаатарын талбай", "name:en": "Sukhbaatar Square"},
                                            106.9176, 47.9189)]},
            {"type": "Place", "content": [p(2, 2, {"name": "Гандан хийд"}, 106.894, 47.921)]},
            {"type": "Place", "content": [p(3, 3, {"name": "Чингис хаан олон улсын нисэх буудал"}, 106.8197, 47.6469)]}]
    src = out.with_suffix(".jsonl")
    src.write_text("".join(json.dumps(x, ensure_ascii=False) + "\n" for x in dump), encoding="utf-8")
    search_builder.build(str(src), str(out))
    src.unlink()
    r = search_builder.selftest(str(out), "Сүхбаатар")
    if r["result"] != "ok":
        raise SystemExit(f"test search DB self-test failed: {r['problems']}")


def publish(packs_dir, version, keep_tiles=False):
    if not nav_pack.VERSION_RE.match(version):
        raise SystemExit("--version must be a slot ID like 20261001T000000Z")
    pl = nav_pack.PackLayout(Path(packs_dir).parent)
    pl.ensure()
    served = pl.read_manifest()
    prev = nav_pack.manifest_files(served)
    if keep_tiles and "tiles" not in prev:
        raise SystemExit("--keep-tiles needs an earlier publication")
    final = pl.region / version
    if final.exists():
        raise SystemExit(f"{final} exists (write-once version directories)")
    cut = ("routing", "search") if keep_tiles else nav_pack.KINDS
    entries = []
    with tempfile.TemporaryDirectory(prefix="nav022-pack-", dir=pl.work_root) as work:
        work = Path(work)
        part = pl.region / f"{version}.partial"
        part.mkdir()
        src = {"tiles": work / "basemap.pmtiles", "routing": work / "routing.tar", "search": work / "search.sqlite"}
        src["tiles"].write_bytes(tiles_bytes(version))
        src["routing"].write_bytes(routing_bytes(version))
        search_db(src["search"])
        for kind in nav_pack.KINDS:
            if kind not in cut:
                entries.append(dict(prev[kind]))
                continue
            gz = part / f"{nav_pack.FILE_NAME[kind]}.gz"
            sha, n = nav_pack.gzip_file(src[kind], gz, 6)
            fmt = {"tiles": {"pmtiles": 3, "minzoom": 0, "maxzoom": 14}, "routing": {"graph_builder": "valhalla 3.9.0"},
                   "search": {"search_schema": 1}}[kind]
            entries.append({"kind": kind, "version": version, "path": f"{version}/{gz.name}", "encoding": "gzip",
                            "download_bytes": gz.stat().st_size, "download_sha256": base.sha256_file(gz),
                            "bytes": n, "sha256": sha,
                            "data_timestamp": "2026-09-26T22:59:05Z" if kind == "search" else "2026-09-29T08:02:53Z",
                            "format": fmt})
        os.rename(part, final)
    m = {"pack_schema": 1, "region": "mn", "pack_version": version, "published_at": base.iso(),
         "attribution": nav_pack.DEFAULT_ATTRIBUTION,
         "licence": {"name": nav_pack.ODBL_NAME, "url": nav_pack.ODBL_URL, "method_url": METHOD_URL},
         "total_bytes": sum(e["bytes"] for e in entries), "total_download_bytes": sum(e["download_bytes"] for e in entries),
         "files": entries,
         "self_test": {"route": {"from": {"lat": 47.9189, "lon": 106.9176}, "to": {"lat": 47.6469, "lon": 106.8197},
                                 "costing": "auto"}, "search": {"q": "Сүхбаатар"}}}
    problems = nav_pack.manifest_problems(m, pl.region)
    py = os.environ.get("CONTRACT_PYTHON") or str(HERE.parent / ".venv" / "bin" / "python")
    schema = "not checked"
    if Path(py).exists():
        r = nav_pack.validate_with_schema(py, m)
        schema = r or "ok"
        if r:
            problems.append(r)
    if problems:
        raise SystemExit("test manifest invalid: " + "; ".join(problems))
    base.atomic_write(pl.manifest, json.dumps(m, ensure_ascii=False, indent=1) + "\n")
    print(json.dumps({"job": "nav022-test-pack", "result": "published", "pack_version": version,
                      "files": {e["kind"]: e["path"] for e in entries}, "total_download_bytes": m["total_download_bytes"],
                      "schema": schema}), flush=True)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--packs-dir", required=True, help="scratch NAV_DATA_ROOT/packs (created)")
    ap.add_argument("--version", required=True)
    ap.add_argument("--keep-tiles", action="store_true", help="weekly cut: keep the previous tiles file")
    a = ap.parse_args(argv)
    if Path(a.packs_dir).name != "packs":
        raise SystemExit("--packs-dir must end in /packs (the NAV-020 layout)")
    publish(os.path.abspath(a.packs_dir), a.version, a.keep_tiles)
    return 0


if __name__ == "__main__":
    sys.exit(main())

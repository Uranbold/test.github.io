#!/usr/bin/env python3
"""NAV-001 build-info (ADR-0002 §4, AC 5): write data/build-info.json from the source metadata
and artefact markers. Also records which story reference points fall inside the OSM extract's
bbox, because points outside it cannot pass the routing/tiles checks.
"""
import json
import os
import sys
from datetime import datetime, timezone

DATA = "/data"
SRC = f"{DATA}/sources"

# NAV-001 reference points (lat, lon)
REF_POINTS = {
    "P1": (47.9189, 106.9176), "P2": (47.9139, 106.9044), "P3": (47.8858, 106.9173),
    "P4": (47.9215, 106.8950), "P5": (47.9095, 106.8835), "P6": (47.9600, 106.9000),
}


def log(level, msg, **kv):
    rec = {"ts": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "service": "build-info",
           "level": level, "msg": msg}
    rec.update({k: str(v) for k, v in kv.items()})
    print(json.dumps(rec, ensure_ascii=False), file=sys.stderr)


def read(path, default=None):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read().strip()
    except FileNotFoundError:
        return default


def source_label(key):
    """'url:https://…' -> the URL; 'file:/path|size|mtime' -> 'file:/path'."""
    if not key:
        return None
    if key.startswith("url:"):
        return key[4:]
    return key.split("|", 1)[0]


def marker(path):
    raw = read(path)
    if raw is None:
        return None
    out = {}
    for line in raw.splitlines():
        k, _, v = line.partition("=")
        if k == "header":
            try:
                v = json.loads(v)
            except ValueError:
                pass
        out[k] = v
    return out


def main():
    osm_meta = json.loads(read(f"{SRC}/osm.pbf.json", "{}") or "{}")
    bbox = osm_meta.get("bbox")
    coverage = {}
    if bbox and None not in bbox:
        for name, (lat, lon) in REF_POINTS.items():
            coverage[name] = bbox[0] <= lon <= bbox[2] and bbox[1] <= lat <= bbox[3]
    outside = [k for k, v in coverage.items() if not v]

    dump_header = {}
    raw_header = read(f"{DATA}/photon/dump-header.json")
    if raw_header:
        try:
            dump_header = json.loads(raw_header).get("content", {})
        except ValueError:
            pass

    aux = {}
    for f in sorted(os.listdir(SRC)) if os.path.isdir(SRC) else []:
        if f.startswith(("osm.pbf", "photon-dump")) or f.endswith((".source", ".part", ".sha256", ".last-modified")):
            continue
        aux[f] = {"source": source_label(read(f"{SRC}/{f}.source")) or "pre-seeded file",
                  "bytes": os.path.getsize(f"{SRC}/{f}")}

    info = {
        "built_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "osm": {
            "source": source_label(read(f"{SRC}/osm.pbf.source")),
            "sha256": read(f"{SRC}/osm.pbf.sha256"),
            "bytes": os.path.getsize(f"{SRC}/osm.pbf") if os.path.exists(f"{SRC}/osm.pbf") else None,
            "replication_timestamp": osm_meta.get("replication_timestamp"),
            "http_last_modified": read(f"{SRC}/osm.pbf.last-modified"),
            "writing_program": osm_meta.get("writing_program"),
            "bbox": bbox,
            "bbox_source": osm_meta.get("bbox_source"),
            "reference_points_inside_bbox": coverage,
        },
        "photon_dump": {
            "source": source_label(read(f"{SRC}/photon-dump.source")),
            "sha256": read(f"{SRC}/photon-dump.sha256"),
            "http_last_modified": read(f"{SRC}/photon-dump.last-modified"),
            "data_timestamp": dump_header.get("data_timestamp"),
            "database_version": dump_header.get("database_version"),
            "generator": dump_header.get("generator"),
            "languages": os.environ.get("PHOTON_LANGUAGES"),
        },
        "planetiler_sources": aux,
        "artefacts": {
            "tiles": marker(f"{DATA}/tiles/.complete"),
            "valhalla": marker(f"{DATA}/valhalla/.complete"),
            "photon": marker(f"{DATA}/photon/.complete"),
        },
        "versions": {
            "valhalla": os.environ.get("VALHALLA_VERSION"),
            "photon": os.environ.get("PHOTON_VERSION"),
            "protomaps_commit": os.environ.get("PROTOMAPS_COMMIT"),
            "planetiler": os.environ.get("PLANETILER_VERSION"),
        },
    }
    missing = [k for k, v in info["artefacts"].items() if v is None]
    if missing:
        log("error", "artefact markers missing", artefacts=",".join(missing))
        sys.exit(1)

    tmp = f"{DATA}/build-info.json.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(info, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.replace(tmp, f"{DATA}/build-info.json")
    log("info", "build-info written", osm_source=info["osm"]["source"],
        osm_replication_timestamp=info["osm"]["replication_timestamp"],
        photon_dump_data_timestamp=info["photon_dump"]["data_timestamp"])
    if outside:
        log("warn", "reference points outside the OSM extract bbox; routes/tiles there will not pass NAV-001 checks",
            points=",".join(outside), bbox=bbox)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Read the OSM PBF header block (bbox, replication timestamp, writing program).

Stdlib only, so it runs in the Valhalla image (python3, no osmium). Equivalent to the
header part of `osmium fileinfo`. Usage: pbfinfo.py <file.osm.pbf>  -> JSON on stdout.
Story: NAV-001 (AC 5, README bbox).
"""
import json
import struct
import sys
import zlib
from datetime import datetime, timezone


def varint(buf, pos):
    shift = result = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7


def fields(buf):
    """Yield (field_number, wire_type, value) for a protobuf message."""
    pos = 0
    while pos < len(buf):
        key, pos = varint(buf, pos)
        num, wt = key >> 3, key & 7
        if wt == 0:
            val, pos = varint(buf, pos)
        elif wt == 2:
            ln, pos = varint(buf, pos)
            val = buf[pos:pos + ln]
            pos += ln
        elif wt == 1:
            val = buf[pos:pos + 8]
            pos += 8
        elif wt == 5:
            val = buf[pos:pos + 4]
            pos += 4
        else:
            raise ValueError(f"unsupported wire type {wt}")
        yield num, wt, val


def zigzag(n):
    return (n >> 1) ^ -(n & 1)


def read_header(path):
    with open(path, "rb") as f:
        (hlen,) = struct.unpack(">I", f.read(4))
        blob_header = f.read(hlen)
        btype, dsize = None, None
        for num, _, val in fields(blob_header):
            if num == 1:
                btype = val.decode()
            elif num == 3:
                dsize = val
        if btype != "OSMHeader":
            raise ValueError(f"first blob is {btype!r}, not OSMHeader")
        blob = f.read(dsize)
    data = None
    for num, _, val in fields(blob):
        if num == 1:
            data = val
        elif num == 3:
            data = zlib.decompress(val)
    if data is None:
        raise ValueError("unsupported blob compression (only raw/zlib)")

    info = {"bbox": None, "replication_timestamp": None, "replication_sequence": None,
            "replication_base_url": None, "writing_program": None, "source": None,
            "required_features": [], "optional_features": []}
    for num, _, val in fields(data):
        if num == 1:
            bb = {}
            for n2, _, v2 in fields(val):
                bb[n2] = zigzag(v2) / 1e9
            # HeaderBBox: left=1 right=2 top=3 bottom=4 -> [minLon, minLat, maxLon, maxLat]
            info["bbox"] = [bb.get(1), bb.get(4), bb.get(2), bb.get(3)]
        elif num == 4:
            info["required_features"].append(val.decode())
        elif num == 5:
            info["optional_features"].append(val.decode())
        elif num == 16:
            info["writing_program"] = val.decode(errors="replace")
        elif num == 17:
            info["source"] = val.decode(errors="replace")
        elif num == 32:
            info["replication_timestamp"] = datetime.fromtimestamp(val, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        elif num == 33:
            info["replication_sequence"] = val
        elif num == 34:
            info["replication_base_url"] = val.decode(errors="replace")
    return info


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: pbfinfo.py <file.osm.pbf>")
    try:
        print(json.dumps(read_header(sys.argv[1]), ensure_ascii=False))
    except Exception as e:  # noqa: BLE001 - report any parse failure as a clean error
        sys.exit(f"pbfinfo: cannot read PBF header of {sys.argv[1]}: {e}")

#!/usr/bin/env python3
"""Read the OSM PBF header block (bbox, replication timestamp, writing program).

Stdlib only, so it runs in the Valhalla image (python3, no osmium). Equivalent to the
header part of `osmium fileinfo`. Usage: pbfinfo.py <file.osm.pbf>  -> JSON on stdout.
Story: NAV-001 (AC 5, README bbox).

    pbfinfo.py --full <file.osm.pbf>
NAV-006 check 3(a) (ADR-0014 §8): additionally reads EVERY blob to the end of the file (BlobHeader and
datasize consistency, zlib decompression and raw_size of each blob) and adds "full_read" to the JSON.
A truncated file, an HTML error page or a corrupt blob exits non-zero with the byte offset of the problem.
The node-scan bbox (header without bbox) is computed in the same pass.
"""
import json
import os
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
        head = f.read(4)
        if len(head) < 4:
            raise ValueError(f"file too short ({len(head)} bytes)")
        (hlen,) = struct.unpack(">I", head)
        if hlen == 0 or hlen > 64 * 1024:
            start = head.decode("ascii", "replace")
            raise ValueError(f"not an OSM PBF: first BlobHeader length {hlen} (file starts with {start!r}; HTML error page?)")
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


def packed_sint(buf):
    """Decode a packed sint64 field into a list (zigzag)."""
    out = []
    append = out.append
    pos, n = 0, len(buf)
    while pos < n:
        shift = result = 0
        while True:
            b = buf[pos]
            pos += 1
            result |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        append((result >> 1) ^ -(result & 1))
    return out


def scan_bbox(path):
    """Bounding box of all nodes, for PBFs whose header has no bbox (e.g. some third-party extracts).
    Reads every OSMData blob; dense nodes only need min/max of the delta-decoded lat/lon arrays."""
    min_lat = min_lon = float("inf")
    max_lat = max_lon = float("-inf")
    with open(path, "rb") as f:
        while True:
            h = f.read(4)
            if len(h) < 4:
                break
            (hlen,) = struct.unpack(">I", h)
            btype, dsize = None, 0
            for num, _, val in fields(f.read(hlen)):
                if num == 1:
                    btype = val.decode()
                elif num == 3:
                    dsize = val
            blob = f.read(dsize)
            if btype != "OSMData":
                continue
            data = None
            for num, _, val in fields(blob):
                if num == 1:
                    data = val
                elif num == 3:
                    data = zlib.decompress(val)
            if data is None:
                raise ValueError("unsupported blob compression (only raw/zlib)")
            gran, lat_off, lon_off, groups = 100, 0, 0, []
            for num, _, val in fields(data):
                if num == 2:
                    groups.append(val)
                elif num == 17:
                    gran = val
                elif num == 19:
                    lat_off = val
                elif num == 20:
                    lon_off = val
            lats, lons = [], []
            for g in groups:
                for num, _, val in fields(g):
                    if num == 2:  # DenseNodes
                        dl = dn = None
                        for n2, _, v2 in fields(val):
                            if n2 == 8:
                                dl = packed_sint(v2)
                            elif n2 == 9:
                                dn = packed_sint(v2)
                        if dl:
                            acc, vals = 0, []
                            for d in dl:
                                acc += d
                                vals.append(acc)
                            lats += (min(vals), max(vals))
                            acc, vals = 0, []
                            for d in dn:
                                acc += d
                                vals.append(acc)
                            lons += (min(vals), max(vals))
                    elif num == 1:  # plain Node: lat=8, lon=9 (sint64)
                        for n2, _, v2 in fields(val):
                            if n2 == 8:
                                lats.append(zigzag(v2))
                            elif n2 == 9:
                                lons.append(zigzag(v2))
            if lats:
                min_lat = min(min_lat, (lat_off + gran * min(lats)) / 1e9)
                max_lat = max(max_lat, (lat_off + gran * max(lats)) / 1e9)
                min_lon = min(min_lon, (lon_off + gran * min(lons)) / 1e9)
                max_lon = max(max_lon, (lon_off + gran * max(lons)) / 1e9)
    if min_lat == float("inf"):
        return None
    return [round(min_lon, 7), round(min_lat, 7), round(max_lon, 7), round(max_lat, 7)]


MAX_BLOB_HEADER = 64 * 1024          # PBF spec: BlobHeader < 64 KiB
MAX_BLOB = 32 * 1024 * 1024          # PBF spec: Blob <= 32 MiB


class PbfError(ValueError):
    """Structural problem, with the byte offset where it was found."""


def iter_blobs(path):
    """Yield (offset, type, decompressed data) for every blob, checking the framing to the last byte."""
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        offset = 0
        while offset < size:
            h = f.read(4)
            if len(h) < 4:
                raise PbfError(f"truncated: {len(h)} stray byte(s) at offset {offset} of {size}")
            (hlen,) = struct.unpack(">I", h)
            if hlen == 0 or hlen > MAX_BLOB_HEADER:
                raise PbfError(f"invalid BlobHeader length {hlen} at offset {offset} (not a PBF, or corrupt)")
            raw_header = f.read(hlen)
            if len(raw_header) < hlen:
                raise PbfError(f"truncated: BlobHeader at offset {offset} needs {hlen} bytes, file ends after {len(raw_header)}")
            btype, dsize = None, None
            try:
                for num, _, val in fields(raw_header):
                    if num == 1:
                        btype = val.decode()
                    elif num == 3:
                        dsize = val
            except (IndexError, UnicodeDecodeError, ValueError) as e:
                raise PbfError(f"unreadable BlobHeader at offset {offset}: {e}") from None
            if btype is None or dsize is None or dsize > MAX_BLOB:
                raise PbfError(f"invalid BlobHeader at offset {offset} (type={btype!r}, datasize={dsize})")
            if offset == 0 and btype != "OSMHeader":
                raise PbfError(f"first blob is {btype!r}, not OSMHeader")
            blob = f.read(dsize)
            if len(blob) < dsize:
                raise PbfError(f"truncated: blob at offset {offset} needs {dsize} bytes, file ends after {len(blob)}")
            data, raw_size = None, None
            try:
                for num, _, val in fields(blob):
                    if num == 1:
                        data = val
                    elif num == 2:
                        raw_size = val
                    elif num == 3:
                        data = zlib.decompress(val)
            except (IndexError, ValueError, zlib.error) as e:
                raise PbfError(f"corrupt blob at offset {offset}: {e}") from None
            if data is None:
                raise PbfError(f"blob at offset {offset}: unsupported compression (only raw/zlib)")
            if raw_size is not None and raw_size != len(data):
                raise PbfError(f"blob at offset {offset}: raw_size {raw_size} != decompressed {len(data)}")
            yield offset, btype, data
            offset += 4 + hlen + dsize


def dense_minmax(data):
    """(min_lat, max_lat, min_lon, max_lon) in degrees of the nodes of one OSMData block, or None."""
    gran, lat_off, lon_off, groups = 100, 0, 0, []
    for num, _, val in fields(data):
        if num == 2:
            groups.append(val)
        elif num == 17:
            gran = val
        elif num == 19:
            lat_off = val
        elif num == 20:
            lon_off = val
    lats, lons = [], []
    for g in groups:
        for num, _, val in fields(g):
            if num == 2:  # DenseNodes
                dl = dn = None
                for n2, _, v2 in fields(val):
                    if n2 == 8:
                        dl = packed_sint(v2)
                    elif n2 == 9:
                        dn = packed_sint(v2)
                if dl:
                    acc, vals = 0, []
                    for d in dl:
                        acc += d
                        vals.append(acc)
                    lats += (min(vals), max(vals))
                    acc, vals = 0, []
                    for d in dn:
                        acc += d
                        vals.append(acc)
                    lons += (min(vals), max(vals))
            elif num == 1:  # plain Node
                for n2, _, v2 in fields(val):
                    if n2 == 8:
                        lats.append(zigzag(v2))
                    elif n2 == 9:
                        lons.append(zigzag(v2))
    if not lats:
        return None
    return ((lat_off + gran * min(lats)) / 1e9, (lat_off + gran * max(lats)) / 1e9,
            (lon_off + gran * min(lons)) / 1e9, (lon_off + gran * max(lons)) / 1e9)


def full_read(path, want_bbox):
    """Read every blob (check 3(a)). Returns {"blocks", "bytes"} and, if want_bbox, "bbox" from a node scan."""
    blocks = 0
    box = [float("inf"), float("-inf"), float("inf"), float("-inf")]
    for _, btype, data in iter_blobs(path):
        blocks += 1
        if want_bbox and btype == "OSMData":
            mm = dense_minmax(data)
            if mm:
                box = [min(box[0], mm[0]), max(box[1], mm[1]), min(box[2], mm[2]), max(box[3], mm[3])]
    out = {"blocks": blocks, "bytes": os.path.getsize(path)}
    if want_bbox:
        out["bbox"] = None if box[0] == float("inf") else [round(box[2], 7), round(box[0], 7),
                                                           round(box[3], 7), round(box[1], 7)]
    return out


def describe(path, full=False):
    """The JSON object printed by the CLI."""
    info = read_header(path)
    info["bbox_source"] = "header"
    no_bbox = not info["bbox"] or None in info["bbox"]
    if full:
        fr = full_read(path, want_bbox=no_bbox)
        if no_bbox:
            info["bbox"] = fr.pop("bbox")
            info["bbox_source"] = "node scan (header has no bbox)"
        info["full_read"] = fr
    elif no_bbox:
        info["bbox"] = scan_bbox(path)
        info["bbox_source"] = "node scan (header has no bbox)"
    return info


if __name__ == "__main__":
    args = sys.argv[1:]
    full = bool(args) and args[0] == "--full"
    if full:
        args = args[1:]
    if len(args) != 1:
        sys.exit("usage: pbfinfo.py [--full] <file.osm.pbf>")
    try:
        print(json.dumps(describe(args[0], full), ensure_ascii=False))
    except Exception as e:  # noqa: BLE001 - report any parse failure as a clean error
        sys.exit(f"pbfinfo: cannot read PBF {args[0]}: {e}")

"""Shared helpers for the NAV-001 QA suite (Python 3.9+ stdlib only).

Story: docs/requirements/stories/NAV-001-backend-stack-docker-compose.md
Contract: docs/architecture/api/openapi.yaml

Everything here talks to the stack through the gateway only (BASE_URL), never to Valhalla or
Photon directly. Nothing here imports backend code, so the QA suite stays independent of the
implementation it checks.
"""
import gzip
import json
import math
import struct
import time
import urllib.error
import urllib.parse
import urllib.request

# NAV-001 "Reference test locations" (lat, lon). QA may move a point by up to 50 m to the nearest
# routable road; this suite does NOT move any point, it uses the story values as written.
P = {
    "P1": (47.9189, 106.9176),  # Sükhbaatar Square
    "P2": (47.9139, 106.9044),  # State Department Store
    "P3": (47.8858, 106.9173),  # Zaisan Memorial
    "P4": (47.9215, 106.8950),  # Gandan Monastery
    "P5": (47.9095, 106.8835),  # UB railway station
    "P6": (47.9600, 106.9000),  # Chingeltei ger district
    "X1": (49.0270, 104.0440),  # Erdenet (outside dev extract)
    "X2": (39.9042, 116.4074),  # Beijing (outside any Mongolia coverage)
}
ORIGIN = "http://localhost:5173"
EVIL_ORIGIN = "http://evil.example"
MN_SPECIFIC = set("өүӨҮ")


def is_cyrillic(ch):
    return "Ѐ" <= ch <= "ӿ"


# ------------------------------------------------------------------------------------ reporting
class Report:
    """PASS / FAIL / INFO / SKIP lines. Every FAIL prints expected and actual (AC 41)."""

    def __init__(self):
        self.results = []  # (status, check_id, detail)
        self.info = {}

    def check(self, cid, ok, expected="", actual=""):
        if ok:
            self.results.append(("PASS", cid, ""))
            print(f"PASS  {cid}", flush=True)
        else:
            detail = f"expected: {expected} | actual: {actual}"
            self.results.append(("FAIL", cid, detail))
            print(f"FAIL  {cid}\n        expected: {expected}\n        actual:   {actual}", flush=True)
        return ok

    def note(self, cid, value):
        self.info[cid] = value
        self.results.append(("INFO", cid, str(value)))
        print(f"INFO  {cid}: {value}", flush=True)

    def skip(self, cid, why):
        self.results.append(("SKIP", cid, why))
        print(f"SKIP  {cid}: {why}", flush=True)

    @property
    def failed(self):
        return [r for r in self.results if r[0] == "FAIL"]

    def summary(self):
        c = {k: sum(1 for r in self.results if r[0] == k) for k in ("PASS", "FAIL", "INFO", "SKIP")}
        return f"{c['PASS']} passed, {c['FAIL']} failed, {c['SKIP']} skipped, {c['INFO']} info"


# ------------------------------------------------------------------------------------ HTTP
class Resp:
    def __init__(self, status, headers, body, elapsed, error=None):
        self.status, self.headers, self.body, self.elapsed, self.error = status, headers, body, elapsed, error

    def header(self, name, default=None):
        return self.headers.get(name.lower(), default)

    def json(self):
        try:
            return json.loads(self.body)
        except (ValueError, TypeError):
            return None

    def __repr__(self):
        return f"<HTTP {self.status} {self.elapsed * 1000:.0f} ms {self.body[:120]!r}>"


class Client:
    def __init__(self, base_url, timeout=15):
        self.base = base_url.rstrip("/")
        self.timeout = timeout

    def request(self, method, path, body=None, headers=None, timeout=None):
        url = path if path.startswith("http") else self.base + path
        hdrs = dict(headers or {})
        data = None
        if body is not None:
            data = body if isinstance(body, (bytes, bytearray)) else json.dumps(body).encode()
            hdrs.setdefault("Content-Type", "application/json")
        req = urllib.request.Request(url, data=data, method=method, headers=hdrs)
        t0 = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
                raw, status, h = r.read(), r.status, r.headers
        except urllib.error.HTTPError as e:
            raw, status, h = e.read(), e.code, e.headers
        except Exception as e:  # connection refused, timeout, reset
            return Resp(0, {}, b"", time.perf_counter() - t0, error=f"{type(e).__name__}: {e}")
        return Resp(status, {k.lower(): v for k, v in h.items()}, raw, time.perf_counter() - t0)

    def get(self, path, params=None, **kw):
        if params:
            path = path + "?" + urllib.parse.urlencode(params)
        return self.request("GET", path, **kw)

    def route_body(self, a, b, costing="auto", language="mn-MN", **extra):
        la, lb = (P[a] if isinstance(a, str) else a), (P[b] if isinstance(b, str) else b)
        body = {
            "locations": [{"lat": la[0], "lon": la[1]}, {"lat": lb[0], "lon": lb[1]}],
            "costing": costing, "format": "osrm", "banner_instructions": True,
            "voice_instructions": True, "language": language, "units": "kilometers",
        }
        body.update(extra)
        return body

    def route(self, a, b, **kw):
        return self.request("POST", "/v1/route", self.route_body(a, b, **kw))


# ------------------------------------------------------------------------------------ geo
def haversine_m(a, b):
    (lat1, lon1), (lat2, lon2) = a, b
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def decode_polyline(s, precision=6):
    """Google encoded polyline -> [(lat, lon)]."""
    idx = lat = lon = 0
    out, factor = [], 10 ** precision
    while idx < len(s):
        deltas = []
        for _ in range(2):
            shift = result = 0
            while True:
                b = ord(s[idx]) - 63
                idx += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            deltas.append(~(result >> 1) if result & 1 else result >> 1)
        lat += deltas[0]
        lon += deltas[1]
        out.append((lat / factor, lon / factor))
    return out


def nearest_feature_m(fc, point):
    ds = [haversine_m(point, (f["geometry"]["coordinates"][1], f["geometry"]["coordinates"][0]))
          for f in (fc or {}).get("features", [])]
    return min(ds) if ds else None


def p95(samples):
    s = sorted(samples)
    return s[max(0, math.ceil(0.95 * len(s)) - 1)]


def lonlat_to_tile(lat, lon, z):
    n = 1 << z
    x = int((lon + 180.0) / 360.0 * n)
    y = int((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)
    return x, y


# ------------------------------------------------------------------------------------ PMTiles v3
def _varint(buf, pos):
    shift = result = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7


def zxy_to_tileid(z, x, y):
    """PMTiles v3 Hilbert tile id (spec section 'Tile IDs')."""
    acc = sum(4 ** i for i in range(z))  # ids of all lower zoom levels
    n = 1 << z
    s = n // 2
    while s > 0:
        rx = 1 if x & s else 0
        ry = 1 if y & s else 0
        acc += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                x, y = n - 1 - x, n - 1 - y
            x, y = y, x
        s //= 2
    return acc


def _decompress(data, compression):
    if compression in (0, 1):  # unknown / none
        return data
    if compression == 2:
        return gzip.decompress(data)
    raise RuntimeError(f"unsupported PMTiles compression {compression} (stdlib reader handles none/gzip)")


class PMTilesRemote:
    """Minimal read-only PMTiles v3 reader over HTTP Range requests through the gateway."""

    COMPRESSION = {0: "unknown", 1: "none", 2: "gzip", 3: "brotli", 4: "zstd"}

    def __init__(self, client, path="/tiles/basemap.pmtiles"):
        self.c, self.path = client, path
        r = client.request("GET", path, headers={"Range": "bytes=0-16383"})
        if r.status != 206:
            raise RuntimeError(f"initial range request returned {r.status}")
        b = r.body
        if b[:7] != b"PMTiles" or b[7] != 3:
            raise RuntimeError(f"bad magic {b[:8]!r}")
        (self.root_off, self.root_len, self.meta_off, self.meta_len, self.leaf_off, self.leaf_len,
         self.data_off, self.data_len, self.addressed, self.entries, self.contents) = struct.unpack_from("<11Q", b, 8)
        self.clustered, self.internal_comp, self.tile_comp, self.tile_type, self.min_zoom, self.max_zoom = \
            struct.unpack_from("<6B", b, 96)
        mnlon, mnlat, mxlon, mxlat = struct.unpack_from("<4i", b, 102)
        self.bounds = (mnlon / 1e7, mnlat / 1e7, mxlon / 1e7, mxlat / 1e7)  # minLon, minLat, maxLon, maxLat
        self._head = b

    def _range(self, off, length):
        if off + length <= len(self._head):
            return self._head[off:off + length]
        r = self.c.request("GET", self.path, headers={"Range": f"bytes={off}-{off + length - 1}"})
        if r.status != 206:
            raise RuntimeError(f"range {off}+{length} returned {r.status}")
        return r.body

    def metadata(self):
        return json.loads(_decompress(self._range(self.meta_off, self.meta_len), self.internal_comp))

    def _dir(self, off, length):
        buf = _decompress(self._range(off, length), self.internal_comp)
        n, pos = _varint(buf, 0)
        ids, runs, lens, offs = [], [], [], []
        last = 0
        for _ in range(n):
            v, pos = _varint(buf, pos)
            last += v
            ids.append(last)
        for _ in range(n):
            v, pos = _varint(buf, pos)
            runs.append(v)
        for _ in range(n):
            v, pos = _varint(buf, pos)
            lens.append(v)
        for i in range(n):
            v, pos = _varint(buf, pos)
            offs.append(offs[i - 1] + lens[i - 1] if (v == 0 and i > 0) else v - 1)
        return list(zip(ids, runs, lens, offs))

    def tile(self, z, x, y):
        loc = self.tile_location(z, x, y)
        if loc is None:
            return None
        return _decompress(self._range(*loc), self.tile_comp)

    def tile_location(self, z, x, y):
        """-> (absolute byte offset, length) of the tile data, or None if the tile is absent."""
        tid = zxy_to_tileid(z, x, y)
        off, length = self.root_off, self.root_len
        for _depth in range(4):
            entries = self._dir(off, length)
            found = None
            for e in entries:
                if e[0] <= tid:
                    found = e
                else:
                    break
            if found is None:
                return None
            eid, run, ln, eo = found
            if run == 0:  # leaf directory pointer
                off, length = self.leaf_off + eo, ln
                continue
            if tid < eid + run:
                return self.data_off + eo, ln
            return None
        return None


# ------------------------------------------------------------------------------------ MVT
def _pb(buf):
    pos = 0
    while pos < len(buf):
        key, pos = _varint(buf, pos)
        num, wt = key >> 3, key & 7
        if wt == 0:
            val, pos = _varint(buf, pos)
        elif wt == 2:
            ln, pos = _varint(buf, pos)
            val = buf[pos:pos + ln]
            pos += ln
        elif wt == 1:
            val, pos = buf[pos:pos + 8], pos + 8
        elif wt == 5:
            val, pos = buf[pos:pos + 4], pos + 4
        else:
            raise ValueError(f"unsupported wire type {wt}")
        yield num, wt, val


def _mvt_value(buf):
    for num, _wt, val in _pb(buf):
        if num == 1:
            return val.decode("utf-8", "replace")
        if num == 2:
            return struct.unpack("<f", val)[0]
        if num == 3:
            return struct.unpack("<d", val)[0]
        if num in (4, 5):
            return val
        if num == 6:
            return (val >> 1) ^ -(val & 1)
        if num == 7:
            return bool(val)
    return None


def decode_mvt(buf):
    """-> {layer_name: [ {attr: value} per feature ]} (geometry is not decoded)."""
    layers = {}
    for num, _wt, lbuf in _pb(buf):
        if num != 3:
            continue
        name, keys, values, feats = None, [], [], []
        for n2, _w2, v2 in _pb(lbuf):
            if n2 == 1:
                name = v2.decode()
            elif n2 == 3:
                keys.append(v2.decode())
            elif n2 == 4:
                values.append(_mvt_value(v2))
            elif n2 == 2:
                feats.append(v2)
        out = []
        for fb in feats:
            tags = []
            for n3, w3, v3 in _pb(fb):
                if n3 == 2 and w3 == 2:
                    p = 0
                    while p < len(v3):
                        t, p = _varint(v3, p)
                        tags.append(t)
            out.append({keys[tags[i]]: values[tags[i + 1]] for i in range(0, len(tags) - 1, 2)})
        layers[name] = out
    return layers

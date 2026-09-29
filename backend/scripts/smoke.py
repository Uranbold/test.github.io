#!/usr/bin/env python3
"""NAV-001 backend smoke suite: gateway only, Python stdlib only.

    python3 scripts/smoke.py [--base-url http://localhost:8080] [--perf] [--cors-allowlist ORIGIN]

Runs the story's smoke set (AC 9, 13-16, 18, 21-23, 25, 28-30, 32 per AC 40) plus cheap extras
(AC 10, 11, 17, 19, 20, 24, 26, 27, 33, 43, gateway 404/405). Prints PASS/FAIL/INFO lines, and each
FAIL shows expected and actual values. Exit code 0 only if no check failed (AC 41).
--perf adds the AC 35-38 latency baselines (20 sequential requests each, p95).
--cors-allowlist ORIGIN checks AC 31 against a gateway started with CORS_ALLOWED_ORIGINS=ORIGIN.

QA's suite (tests/smoke/run.sh) is the story's official one; this is the backend's own check.
"""
import argparse
import gzip
import json
import math
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

P = {  # NAV-001 reference points (lat, lon)
    "P1": (47.9189, 106.9176), "P2": (47.9139, 106.9044), "P3": (47.8858, 106.9173),
    "P4": (47.9215, 106.8950), "P5": (47.9095, 106.8835), "P6": (47.9600, 106.9000),
    "X1": (49.0270, 104.0440), "X2": (39.9042, 116.4074),
}
ORIGIN = "http://localhost:5173"
MN_LETTERS = set("өүӨҮ")


class Suite:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.failed = []
        self.passed = 0
        self.info = {}
        self.last_raw_headers = []  # [(name_lower, value)] of the last response, repeats kept

    # ---------------------------------------------------------------- reporting
    def check(self, name, ok, expected="", actual=""):
        if ok:
            self.passed += 1
            print(f"PASS  {name}")
        else:
            self.failed.append(name)
            print(f"FAIL  {name}\n        expected: {expected}\n        actual:   {actual}")
        return ok

    def note(self, name, value):
        self.info[name] = value
        print(f"INFO  {name}: {value}")

    # ---------------------------------------------------------------- HTTP
    def http(self, method, path, body=None, headers=None, timeout=15):
        url = path if path.startswith("http") else self.base + path
        data = None
        hdrs = dict(headers or {})
        if body is not None:
            data = body if isinstance(body, bytes) else json.dumps(body).encode()
            hdrs.setdefault("Content-Type", "application/json")
        req = urllib.request.Request(url, data=data, method=method, headers=hdrs)
        t0 = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                raw = r.read()
                status, h = r.status, r.headers
        except urllib.error.HTTPError as e:
            raw = e.read()
            status, h = e.code, e.headers
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            self.last_raw_headers = []
            return 0, {}, str(e).encode(), time.perf_counter() - t0
        elapsed = time.perf_counter() - t0
        self.last_raw_headers = [(k.lower(), v) for k, v in h.items()]
        headers_l = {k.lower(): v for k, v in h.items()}
        return status, headers_l, raw, elapsed

    def json_of(self, raw):
        try:
            return json.loads(raw)
        except ValueError:
            return None

    def route(self, a, b, costing="auto", language="mn-MN", **extra):
        body = {"locations": [{"lat": P[a][0], "lon": P[a][1]}, {"lat": P[b][0], "lon": P[b][1]}],
                "costing": costing, "format": "osrm", "banner_instructions": True,
                "voice_instructions": True, "language": language, "units": "kilometers"}
        body.update(extra)
        return self.http("POST", "/v1/route", body)

    def get(self, path, params, **kw):
        return self.http("GET", path + "?" + urllib.parse.urlencode(params), **kw)


# -------------------------------------------------------------------- geo helpers
def haversine(a, b):
    (lat1, lon1), (lat2, lon2) = a, b
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def polyline_decode(s, precision=6):
    idx = lat = lon = 0
    out = []
    while idx < len(s):
        vals = []
        for _ in range(2):
            shift = result = 0
            while True:
                b = ord(s[idx]) - 63
                idx += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            vals.append(~(result >> 1) if result & 1 else result >> 1)
        lat += vals[0]
        lon += vals[1]
        out.append((lat / 10 ** precision, lon / 10 ** precision))
    return out


def repeated_cors_headers(raw_headers):
    """Access-Control-* header names that occur more than once (browsers reject a repeated ACAO).
    Vary is not checked: it is list-valued and may legally repeat (gzip's Accept-Encoding + Origin)."""
    names = [k for k, _ in raw_headers if k.startswith("access-control-")]
    return sorted({k for k in names if names.count(k) > 1})


def feature_min_dist(fc, point):
    ds = [haversine(point, (f["geometry"]["coordinates"][1], f["geometry"]["coordinates"][0]))
          for f in fc.get("features", [])]
    return min(ds) if ds else None


def p95(samples):
    s = sorted(samples)
    return s[max(0, math.ceil(0.95 * len(s)) - 1)]


# -------------------------------------------------------------------- PMTiles / MVT (read-only helpers)
def varint(buf, pos):
    shift = result = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7


def pb_fields(buf):
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
            val, pos = buf[pos:pos + 8], pos + 8
        elif wt == 5:
            val, pos = buf[pos:pos + 4], pos + 4
        else:
            raise ValueError(f"wire type {wt}")
        yield num, wt, val


def zxy_to_tileid(z, x, y):
    acc = ((1 << (2 * z)) - 1) // 3
    n = z - 1
    s = 1 << n if z > 0 else 0
    while s > 0:
        rx, ry = s & x, s & y
        acc += ((3 * rx) ^ ry) << n
        if ry == 0:
            if rx != 0:
                x, y = s - 1 - x, s - 1 - y
            x, y = y, x
        s >>= 1
        n -= 1
    return acc


def lonlat_to_tile(lat, lon, z):
    n = 1 << z
    x = int((lon + 180.0) / 360.0 * n)
    y = int((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)
    return x, y


class PMTiles:
    def __init__(self, suite, path="/tiles/basemap.pmtiles"):
        self.s, self.path = suite, path
        st, h, raw, _ = suite.http("GET", path, headers={"Range": "bytes=0-16383"})
        if st != 206:
            raise RuntimeError(f"range request returned {st}")
        self.head = raw
        b = raw
        (self.root_off, self.root_len, self.meta_off, self.meta_len, self.leaf_off, self.leaf_len,
         self.data_off, self.data_len) = struct.unpack_from("<8Q", b, 8)
        self.internal_comp, self.tile_comp, self.tile_type = b[97], b[98], b[99]
        self.min_zoom, self.max_zoom = b[100], b[101]
        self.bounds = [v / 1e7 for v in struct.unpack_from("<4i", b, 102)]  # minLon minLat maxLon maxLat
        self.dir_cache = {}

    def _range(self, off, length):
        st, _, raw, el = self.s.http("GET", self.path, headers={"Range": f"bytes={off}-{off + length - 1}"})
        if st != 206:
            raise RuntimeError(f"range {off}+{length} returned {st}")
        return raw, el

    def _decomp(self, raw, comp):
        return gzip.decompress(raw) if comp == 2 else raw

    def metadata(self):
        raw, _ = self._range(self.meta_off, self.meta_len)
        return json.loads(self._decomp(raw, self.internal_comp))

    def _directory(self, off, length):
        key = (off, length)
        if key not in self.dir_cache:
            raw, _ = self._range(off, length)
            buf = self._decomp(raw, self.internal_comp)
            n, pos = varint(buf, 0)
            ids, last = [], 0
            for _ in range(n):
                v, pos = varint(buf, pos)
                last += v
                ids.append(last)
            runs, lens, offs = [], [], []
            for _ in range(n):
                v, pos = varint(buf, pos)
                runs.append(v)
            for _ in range(n):
                v, pos = varint(buf, pos)
                lens.append(v)
            for i in range(n):
                v, pos = varint(buf, pos)
                offs.append(offs[i - 1] + lens[i - 1] if v == 0 and i > 0 else v - 1)
            self.dir_cache[key] = list(zip(ids, runs, lens, offs))
        return self.dir_cache[key]

    def locate(self, z, x, y):
        """Return (absolute_offset, length) of the tile, or None."""
        tid = zxy_to_tileid(z, x, y)
        off, length = self.root_off, self.root_len
        for _ in range(4):
            entries = self._directory(off, length)
            best = None
            for e in entries:
                if e[0] <= tid:
                    best = e
                else:
                    break
            if best is None:
                return None
            eid, run, ln, o = best
            if run == 0:
                off, length = self.leaf_off + o, ln
                continue
            if tid < eid + run:
                return self.data_off + o, ln
            return None
        return None

    def tile(self, z, x, y):
        loc = self.locate(z, x, y)
        if loc is None:
            return None, 0.0
        raw, el = self._range(*loc)
        return self._decomp(raw, self.tile_comp), el


def mvt_layers(tile):
    """{layer_name: {"keys": [...], "features": [ {key: value} ... ]}}"""
    layers = {}
    for num, _, lay in pb_fields(tile):
        if num != 3:
            continue
        name, keys, values, feats = None, [], [], []
        for n2, _, v2 in pb_fields(lay):
            if n2 == 1:
                name = v2.decode()
            elif n2 == 3:
                keys.append(v2.decode())
            elif n2 == 4:
                val = None
                for n3, _, v3 in pb_fields(v2):
                    val = v3.decode(errors="replace") if n3 == 1 else v3
                values.append(val)
            elif n2 == 2:
                feats.append(v2)
        decoded = []
        for f in feats:
            tags = {}
            for n3, wt, v3 in pb_fields(f):
                if n3 == 2 and wt == 2:
                    ints, pos = [], 0
                    while pos < len(v3):
                        i, pos = varint(v3, pos)
                        ints.append(i)
                    for k, v in zip(ints[::2], ints[1::2]):
                        tags[keys[k]] = values[v]
            decoded.append(tags)
        layers[name] = {"keys": keys, "features": decoded}
    return layers


# -------------------------------------------------------------------- checks
def run_checks(s):
    # AC 28 HEALTH
    st, h, raw, el = s.http("GET", "/health")
    s.check("AC28 GET /health -> 200 within 1 s", st == 200 and el < 1.0 and (s.json_of(raw) or {}).get("status") == "ok",
            "200, <1 s, {status: ok}", f"{st}, {el:.3f}s, {raw[:80]!r}")

    # AC 9 / 30 TILES range + CORS
    st, h, raw, el = s.http("GET", "/tiles/basemap.pmtiles", headers={"Range": "bytes=0-126", "Origin": ORIGIN})
    s.check("AC9 TILES Range bytes=0-126 -> 206 + Content-Range + PMTiles v3 magic",
            st == 206 and "content-range" in h and raw[:7] == b"PMTiles" and raw[7:8] == b"\x03",
            "206, Content-Range, b'PMTiles\\x03'", f"{st}, content-range={h.get('content-range')}, {raw[:8]!r}")
    expose = {x.strip().lower() for x in h.get("access-control-expose-headers", "").split(",")}
    s.check("AC30 TILES CORS: Allow-Origin + Expose-Headers Content-Range, Content-Length, ETag",
            h.get("access-control-allow-origin") in (ORIGIN, "*") and {"content-range", "content-length", "etag"} <= expose,
            f"ACAO in ({ORIGIN}, *), expose >= content-range,content-length,etag",
            f"ACAO={h.get('access-control-allow-origin')}, expose={h.get('access-control-expose-headers')}")

    # AC 43 unsatisfiable Range: 416, single CORS headers, JSON GatewayError. The range start is the
    # archive size from HEAD, so the check works for any extract size (bytes=99999999- is inside a
    # 243 MB Mongolia archive and would return 206).
    st, h, raw, el = s.http("HEAD", "/tiles/basemap.pmtiles", headers={"Origin": ORIGIN})
    size = int(h.get("content-length") or 0)
    for method in ("GET", "HEAD"):
        st, h, raw, el = s.http(method, "/tiles/basemap.pmtiles", headers={"Range": f"bytes={size}-", "Origin": ORIGIN})
        dup = repeated_cors_headers(s.last_raw_headers)
        body = s.json_of(raw) if method == "GET" else {"code": "RangeNotSatisfiable"}
        s.check(f"AC43 TILES {method} Range bytes={size}- -> 416, Content-Range bytes */{size}, "
                "each CORS header once" + (", JSON RangeNotSatisfiable" if method == "GET" else ""),
                size > 0 and st == 416 and h.get("content-range") == f"bytes */{size}" and not dup
                and h.get("access-control-allow-origin") in (ORIGIN, "*")
                and (body or {}).get("code") == "RangeNotSatisfiable"
                and (method == "HEAD" or h.get("content-type", "").startswith("application/json")),
                f"416, bytes */{size}, no repeated headers, ACAO, application/json RangeNotSatisfiable",
                f"{st}, content-range={h.get('content-range')}, repeated={dup}, ACAO={h.get('access-control-allow-origin')}, "
                f"type={h.get('content-type')}, body={raw[:80]!r}")

    # AC 10 / 11 PMTiles header, metadata, z14 tile at P1
    try:
        pm = PMTiles(s)
        b = pm.bounds
        inside = {k: b[0] <= P[k][1] <= b[2] and b[1] <= P[k][0] <= b[3] for k in ("P1", "P2", "P3", "P4", "P5", "P6")}
        s.note("AC10 tile bounds [minLon,minLat,maxLon,maxLat]", b)
        s.check("AC10 tile bounds cover P1-P6", all(inside.values()), "all of P1-P6 inside",
                "outside: " + ",".join(k for k, v in inside.items() if not v))
        s.check("AC10 tile max zoom >= 14", pm.max_zoom >= 14, ">= 14", pm.max_zoom)
        meta = pm.metadata()
        s.check("AC10 metadata attribution contains OpenStreetMap", "OpenStreetMap" in str(meta.get("attribution", "")),
                "contains OpenStreetMap", str(meta.get("attribution"))[:120])
        x, y = lonlat_to_tile(*P["P1"], 14)
        tile, _ = pm.tile(14, x, y)
        if tile is None:
            s.check(f"AC11 z14 tile {x}/{y} at P1 exists", False, "non-empty tile", "tile not in archive")
        else:
            layers = mvt_layers(tile)
            roads = layers.get("roads", {"keys": [], "features": []})
            named = [f for f in roads["features"] if str(f.get("name", "")).strip()]
            s.check(f"AC11 z14 tile {x}/{y} at P1 has a road with non-empty name", len(named) > 0,
                    ">= 1 named road feature", f"{len(roads['features'])} road features, {len(named)} named")
            all_keys = set().union(*(set(v["keys"]) for v in layers.values())) if layers else set()
            s.note("AC11 name:mn present in z14 P1 tile", "name:mn" in all_keys)
            s.note("AC11 name:en present in z14 P1 tile", "name:en" in all_keys)
            if named:
                s.note("AC11 sample road name", named[0].get("name"))
    except Exception as e:  # noqa: BLE001
        s.check("AC10/11 PMTiles readable through the gateway", False, "parseable header/directory/tile", repr(e))

    # AC 13-16 ROUTE P1 -> P3 mn-MN
    st, h, raw, el = s.route("P1", "P3")
    body = s.json_of(raw) or {}
    straight = haversine(P["P1"], P["P3"])
    r0 = (body.get("routes") or [{}])[0]
    s.check("AC13 ROUTE P1->P3 auto mn-MN -> 200, code Ok, >=1 route", st == 200 and body.get("code") == "Ok" and bool(body.get("routes")),
            "200, code=Ok, routes>=1", f"{st}, code={body.get('code')}, message={body.get('message')}")
    dist = r0.get("distance", 0) or 0
    s.check("AC13 route distance within [straight, 2.5 x straight]", straight <= dist <= 2.5 * straight,
            f"{straight:.0f} m .. {2.5 * straight:.0f} m", f"{dist:.0f} m")
    s.check("AC13 route duration > 0", (r0.get("duration") or 0) > 0, "> 0", r0.get("duration"))
    if r0.get("geometry"):
        coords = polyline_decode(r0["geometry"])
        # The story lets QA move a reference point up to 50 m to the nearest routable road, so a
        # snapped end point may be up to 100 m + 50 m from the listed coordinate.
        d0, d1 = haversine(coords[0], P["P1"]), haversine(coords[-1], P["P3"])
        s.check("AC14 polyline6 first point <= 100 m (+50 m point move) from P1", d0 <= 150, "<= 150 m", f"{d0:.0f} m")
        s.check("AC14 polyline6 last point <= 100 m (+50 m point move) from P3", d1 <= 150, "<= 150 m",
                f"{d1:.0f} m (ends at {coords[-1]})")
    else:
        s.check("AC14 route geometry present", False, "polyline6 string", "missing")
    steps = ((r0.get("legs") or [{}])[0]).get("steps") or []
    all_maneuver = bool(steps) and all(isinstance(st_.get("maneuver"), dict) for st_ in steps)

    def good_voice(stp):
        return any(v.get("announcement") and isinstance(v.get("distanceAlongGeometry"), (int, float))
                   for v in stp.get("voiceInstructions") or [])

    def good_banner(stp):
        return any((b.get("primary") or {}).get("text") and isinstance(b.get("distanceAlongGeometry"), (int, float))
                   for b in stp.get("bannerInstructions") or [])

    s.check("AC15 every step has maneuver; a step has voiceInstructions + bannerInstructions",
            all_maneuver and any(good_voice(x) and good_banner(x) for x in steps),
            "maneuver on all steps; >=1 step with announcement/primary.text + distanceAlongGeometry",
            f"{len(steps)} steps, all_maneuver={all_maneuver}, voice+banner steps={sum(1 for x in steps if good_voice(x) and good_banner(x))}")
    mn_text = " ".join(v.get("announcement", "") for x in steps for v in x.get("voiceInstructions") or [])
    has_cyr = any("Ѐ" <= c <= "ӿ" for c in mn_text)
    has_mn = any(c in MN_LETTERS for c in mn_text)
    s.check("AC16 announcements contain Cyrillic and a Mongolian-specific letter (ө/ү)", has_cyr and has_mn,
            "Cyrillic + one of ө ү Ө Ү", f"cyrillic={has_cyr}, mongolian_letter={has_mn}, sample={mn_text[:80]!r}")

    # AC 17 en-US differs, no Cyrillic outside street names
    st, h, raw, el = s.route("P1", "P3", language="en-US")
    en = s.json_of(raw) or {}
    en_steps = (((en.get("routes") or [{}])[0].get("legs") or [{}])[0]).get("steps") or []
    names = {x.get("name", "") for x in en_steps} | {x.get("ref", "") for x in en_steps}
    en_text = " ".join(v.get("announcement", "") for x in en_steps for v in x.get("voiceInstructions") or [])
    stripped = en_text
    for n in sorted((n for n in names if n), key=len, reverse=True):
        stripped = stripped.replace(n, "")
    s.check("AC17 en-US announcements differ from mn-MN and have no Cyrillic besides street names",
            st == 200 and en_text != mn_text and not any("Ѐ" <= c <= "ӿ" for c in stripped),
            "200, differs, no Cyrillic outside OSM names", f"{st}, differs={en_text != mn_text}, sample={en_text[:80]!r}")

    # AC 18 pedestrian P1 -> P2
    st, h, raw, el = s.route("P1", "P2", costing="pedestrian")
    body = s.json_of(raw) or {}
    straight = haversine(P["P1"], P["P2"])
    dist = ((body.get("routes") or [{}])[0]).get("distance", 0) or 0
    s.check("AC18 ROUTE P1->P2 pedestrian -> 200 Ok, distance in [straight, 2.5x]",
            st == 200 and body.get("code") == "Ok" and straight <= dist <= 2.5 * straight,
            f"200 Ok, {straight:.0f}..{2.5 * straight:.0f} m", f"{st} {body.get('code')}, {dist:.0f} m")

    # AC 19 exclude_unpaved P1 -> P6 (informational result, never 5xx)
    st, h, raw, el = s.route("P1", "P6", costing_options={"auto": {"exclude_unpaved": True}})
    body = s.json_of(raw)
    s.check("AC19 ROUTE P1->P6 exclude_unpaved -> 200 or 4xx JSON, never 5xx/timeout",
            (st == 200 or 400 <= st < 500) and body is not None, "200 or 4xx with JSON", f"{st}, json={body is not None}")
    if st == 200 and body:
        c = polyline_decode(body["routes"][0]["geometry"])
        s.note("AC19 unpaved result", f"200 route {body['routes'][0]['distance']:.0f} m, ends {haversine(c[-1], P['P6']):.0f} m from P6")
    else:
        s.note("AC19 unpaved result", f"{st} {(body or {}).get('code') or (body or {}).get('error')}")

    # AC 20 alternates
    st, h, raw, el = s.route("P1", "P4", alternates=2)
    body = s.json_of(raw) or {}
    n = len(body.get("routes") or [])
    s.check("AC20 ROUTE P1->P4 alternates=2 -> 200 with 1-3 routes", st == 200 and 1 <= n <= 3, "200, 1..3 routes", f"{st}, {n}")

    # AC 32 out of coverage, AC 33 malformed. X1 (Erdenet) is out of coverage only on the UB dev
    # extract; when the tiles show a wider extract covering it, Beijing (X2) is used instead.
    out = "X1"
    try:
        tb = PMTiles(s).bounds
        if tb[0] <= P["X1"][1] <= tb[2] and tb[1] <= P["X1"][0] <= tb[3]:
            out = "X2"
            s.note("AC32 out-of-coverage point", "X1 is inside the extract bounds, using X2 (Beijing)")
    except Exception:  # noqa: BLE001 - tiles problems are reported by the AC 9/10 checks
        pass
    st, h, raw, el = s.route("P1", out)
    body = s.json_of(raw)
    s.check(f"AC32 ROUTE P1->{out} (out of coverage) -> 4xx JSON with message within 3 s",
            400 <= st < 500 and isinstance(body, dict) and bool(body.get("message") or body.get("error")) and el < 3,
            "4xx, JSON message, <3 s", f"{st}, {el:.2f}s, {raw[:100]!r}")
    st, h, raw, el = s.http("POST", "/v1/route", {"costing": "auto", "format": "osrm"})
    s.check("AC33 ROUTE without locations -> 400 JSON within 1 s", st == 400 and s.json_of(raw) is not None and el < 1,
            "400 JSON, <1 s", f"{st}, {el:.2f}s, {raw[:100]!r}")
    st, h, raw, el = s.http("POST", "/v1/route", b"this is not json")
    s.check("AC33 ROUTE with non-JSON body -> 400 JSON within 1 s", st == 400 and s.json_of(raw) is not None and el < 1,
            "400 JSON, <1 s", f"{st}, {el:.2f}s, {raw[:100]!r}")

    # AC 21-27 SEARCH / REVERSE
    bias = {"lat": P["P1"][0], "lon": P["P1"][1], "limit": 5}
    st, h, raw, el = s.get("/v1/search", {"q": "Сүхбаатар", "lang": "mn", **bias})
    fc = s.json_of(raw) or {}
    feats = fc.get("features") or []
    dmin = feature_min_dist(fc, P["P1"])
    s.check("AC21 SEARCH q=Сүхбаатар -> 200 FeatureCollection, 1-5 features, one <= 5 km from P1",
            st == 200 and fc.get("type") == "FeatureCollection" and 1 <= len(feats) <= 5 and dmin is not None and dmin <= 5000,
            "200, 1..5 features, nearest <= 5000 m", f"{st}, {len(feats)} features, nearest={dmin and round(dmin)} m")
    st, h, raw, el = s.get("/v1/search", {"q": "Сүхб", "lang": "mn", **bias})
    n = len((s.json_of(raw) or {}).get("features") or [])
    s.check("AC22 SEARCH prefix q=Сүхб -> 200 with >= 1 feature", st == 200 and n >= 1, "200, >= 1", f"{st}, {n}")
    st, h, raw, el = s.get("/v1/search", {"q": "xqzjwvk", "lang": "mn", **bias})
    fc = s.json_of(raw) or {}
    s.check("AC23 SEARCH nonsense q=xqzjwvk -> 200 with empty features", st == 200 and fc.get("features") == [],
            "200, []", f"{st}, {str(fc.get('features'))[:80]}")
    st, h, raw, el = s.get("/v1/search", {"q": "Sukhbaatar", "lang": "en", **bias})
    fc = s.json_of(raw) or {}
    dmin = feature_min_dist(fc, P["P1"])
    s.check("AC24 SEARCH Latin q=Sukhbaatar -> 200", st == 200, "200", st)
    s.note("AC24 Latin search baseline", f"{len(fc.get('features') or [])} results, nearest {dmin and round(dmin)} m from P1")
    for lang in ("mn", "en"):
        st, h, raw, el = s.get("/v1/search", {"q": "Улаанбаатар", "lang": lang, "limit": 1})
        s.check(f"AC27 SEARCH lang={lang} accepted -> 200", st == 200, "200", f"{st} {raw[:80]!r}")
    st, h, raw, el = s.get("/v1/reverse", {"lat": P["P1"][0], "lon": P["P1"][1], "lang": "mn"})
    fc = s.json_of(raw) or {}
    dmin = feature_min_dist(fc, P["P1"])
    s.check("AC25 REVERSE at P1 -> 200 with a feature <= 300 m", st == 200 and dmin is not None and dmin <= 300,
            "200, nearest <= 300 m", f"{st}, nearest={dmin and round(dmin)} m")
    st, h, raw, el = s.get("/v1/reverse", {"lat": P["X2"][0], "lon": P["X2"][1], "lang": "mn"})
    fc = s.json_of(raw)
    s.check("AC26 REVERSE at Beijing -> 200 empty or 4xx JSON, never 5xx",
            (st == 200 and (fc or {}).get("features") == []) or (400 <= st < 500 and fc is not None),
            "200 [] or 4xx JSON", f"{st}, {raw[:80]!r}")

    # AC 29 preflight
    for path in ("/v1/route", "/v1/search", "/tiles/basemap.pmtiles"):
        st, h, raw, el = s.http("OPTIONS", path, headers={"Origin": ORIGIN, "Access-Control-Request-Method": "GET",
                                                          "Access-Control-Request-Headers": "Range, Content-Type"})
        methods = {m.strip().upper() for m in h.get("access-control-allow-methods", "").split(",")}
        allow_h = {m.strip().lower() for m in h.get("access-control-allow-headers", "").split(",")}
        s.check(f"AC29 preflight OPTIONS {path} -> 200/204 with CORS headers",
                st in (200, 204) and h.get("access-control-allow-origin") in (ORIGIN, "*")
                and {"GET", "POST", "OPTIONS"} <= methods and {"range", "content-type"} <= allow_h,
                "200/204, ACAO, methods GET/POST/OPTIONS, headers Range/Content-Type",
                f"{st}, ACAO={h.get('access-control-allow-origin')}, methods={methods}, headers={allow_h}")

    # Gateway's own JSON errors (openapi GatewayError)
    st, h, raw, el = s.http("GET", "/no/such/path")
    s.check("GW unknown path -> 404 GatewayError NotFound", st == 404 and (s.json_of(raw) or {}).get("code") == "NotFound",
            "404 {code: NotFound}", f"{st} {raw[:80]!r}")
    st, h, raw, el = s.http("DELETE", "/v1/search")
    s.check("GW wrong method -> 405 GatewayError MethodNotAllowed",
            st == 405 and (s.json_of(raw) or {}).get("code") == "MethodNotAllowed", "405 {code: MethodNotAllowed}", f"{st} {raw[:80]!r}")
    body = {"locations": [{"lat": P["P1"][0], "lon": P["P1"][1]}, {"lat": P["P2"][0], "lon": P["P2"][1]}],
            "costing": "auto", "format": "osrm", "language": "mn-MN", "units": "kilometers"}
    st, h, raw, el = s.http("POST", "/v1/route", body, headers={"Origin": ORIGIN})
    dup = repeated_cors_headers(s.last_raw_headers)
    s.check("GW upstream CORS stripped (each Access-Control-* header once on a routed response)",
            st == 200 and not dup, "200, no repeated headers", f"{st}, repeated={dup}")


def run_cors_allowlist(s, allowed):
    st, h, raw, el = s.http("OPTIONS", "/v1/route", headers={"Origin": "http://evil.example", "Access-Control-Request-Method": "GET"})
    s.check("AC31 disallowed origin gets no Access-Control-Allow-Origin", "access-control-allow-origin" not in h,
            "header absent", h.get("access-control-allow-origin"))
    st, h, raw, el = s.http("OPTIONS", "/v1/route", headers={"Origin": allowed, "Access-Control-Request-Method": "GET"})
    s.check("AC31 allowed origin is echoed", h.get("access-control-allow-origin") == allowed, allowed, h.get("access-control-allow-origin"))
    st, h, raw, el = s.http("HEAD", "/tiles/basemap.pmtiles")
    size = int(h.get("content-length") or 0)
    st, h, raw, el = s.http("GET", "/tiles/basemap.pmtiles", headers={"Range": f"bytes={size}-", "Origin": "http://evil.example"})
    s.check("AC43/AC31 416 for a disallowed origin has no Access-Control-Allow-Origin",
            st == 416 and "access-control-allow-origin" not in h, "416, header absent", f"{st}, {h.get('access-control-allow-origin')}")
    st, h, raw, el = s.http("GET", "/tiles/basemap.pmtiles", headers={"Range": f"bytes={size}-", "Origin": allowed})
    dup = repeated_cors_headers(s.last_raw_headers)
    s.check("AC43/AC31 416 for the allowed origin echoes it exactly once",
            st == 416 and h.get("access-control-allow-origin") == allowed and not dup,
            f"416, ACAO={allowed}, no repeated headers", f"{st}, {h.get('access-control-allow-origin')}, repeated={dup}")
    st, h, raw, el = s.route("P1", "P2", language="mn-MN")
    s.check("AC31 Valhalla's own '*' does not leak for requests without an allowed Origin",
            "access-control-allow-origin" not in h, "header absent", h.get("access-control-allow-origin"))


def run_perf(s):
    pts = ["P1", "P2", "P3", "P4", "P5"]
    pairs = [(a, b) for a in pts for b in pts if a != b][:20]
    lat = []
    for a, b in pairs:
        st, _, _, el = s.route(a, b)
        lat.append(el)
    v = p95(lat)
    s.note("AC35 p95 / max", f"{v * 1000:.0f} ms / {max(lat) * 1000:.0f} ms")
    s.check("AC35 ROUTE p95 (20 sequential, auto mn-MN osrm) <= 500 ms", v <= 0.5, "<= 500 ms", f"{v * 1000:.0f} ms")
    prefixes = ["Сү", "Сүх", "Сүхб", "Сүхбаа", "Сүхбаатар", "Ул", "Улаан", "Улаанбаа", "Га", "Ганд",
                "Гандан", "За", "Зайс", "Зайсан", "Их", "Их дэл", "Их дэлгүүр", "Чин", "Чингэл", "Чингэлтэй"]
    lat = []
    for q in prefixes:
        st, _, _, el = s.get("/v1/search", {"q": q, "lang": "mn", "lat": P["P1"][0], "lon": P["P1"][1], "limit": 5})
        lat.append(el)
    v = p95(lat)
    s.note("AC36 p95 / max", f"{v * 1000:.0f} ms / {max(lat) * 1000:.0f} ms")
    s.check("AC36 SEARCH p95 (20 sequential prefixes) <= 300 ms", v <= 0.3, "<= 300 ms", f"{v * 1000:.0f} ms")
    lat = []
    for i in range(20):
        p = P[pts[i % 5]]
        st, _, _, el = s.get("/v1/reverse", {"lat": p[0], "lon": p[1], "lang": "mn"})
        lat.append(el)
    v = p95(lat)
    s.note("AC37 p95 / max", f"{v * 1000:.0f} ms / {max(lat) * 1000:.0f} ms")
    s.check("AC37 REVERSE p95 (20 sequential) <= 300 ms", v <= 0.3, "<= 300 ms", f"{v * 1000:.0f} ms")
    pm = PMTiles(s)
    locs = []
    for z in range(10, 15):
        x, y = lonlat_to_tile(*P["P1"], z)
        for dx, dy in ((0, 0), (1, 0), (0, 1), (-1, 0)):
            loc = pm.locate(z, x + dx, y + dy)
            if loc:
                locs.append(loc)
    lat = []
    for i in range(20):
        off, ln = locs[i % len(locs)]
        _, el = pm._range(off, ln)
        lat.append(el)
    v = p95(lat)
    s.note("AC38 p95 / max", f"{v * 1000:.1f} ms / {max(lat) * 1000:.1f} ms")
    s.check("AC38 TILES p95 (20 sequential z10-z14 range reads near P1) <= 100 ms", v <= 0.1, "<= 100 ms", f"{v * 1000:.1f} ms")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--perf", action="store_true", help="also run AC 35-38 latency baselines")
    ap.add_argument("--cors-allowlist", metavar="ORIGIN", help="only run AC 31 against a gateway restricted to ORIGIN")
    ap.add_argument("--report", metavar="FILE", help="write a JSON report")
    args = ap.parse_args()
    s = Suite(args.base_url)
    t0 = time.perf_counter()
    print(f"NAV-001 backend smoke against {s.base}")
    if args.cors_allowlist:
        run_cors_allowlist(s, args.cors_allowlist)
    else:
        run_checks(s)
        if args.perf:
            run_perf(s)
    secs = time.perf_counter() - t0
    print(f"\n{s.passed} passed, {len(s.failed)} failed in {secs:.1f}s")
    if s.failed:
        print("FAILED CHECKS:\n  " + "\n  ".join(s.failed))
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump({"base_url": s.base, "passed": s.passed, "failed": s.failed, "info": s.info,
                       "seconds": round(secs, 1)}, f, ensure_ascii=False, indent=2)
    sys.exit(1 if s.failed else 0)


if __name__ == "__main__":
    main()

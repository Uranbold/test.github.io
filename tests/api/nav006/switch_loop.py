#!/usr/bin/env python3
"""NAV-006 QA request loop (AC 15, AC 18, AC 33; light-QA set items 1-3). Independent of backend's own loop.

    switch_loop.py run --base-url http://127.0.0.1:18080 --out loop.jsonl [--rate 24] [--clients 6]
                       [--duration 1800] [--stop-file FILE]
    switch_loop.py summarize loop.jsonl [--switch-at 2026-10-04T03:10:00Z] [--json out.json]

Traceability: NAV-006 AC 15 (loop definition, 0 failures, p95 ratio), AC 18 (rollback loop), AC 33 (failure loop),
AC 16 support (tiles ETag per response = the switch moment as clients see it).

Rules taken from the story (AC 15):
- total rate >= 10 requests/s (default 24) over GET /health, POST /v1/route P1->P2 auto, GET /v1/search
  «Сүхбаатар» and "Sukhbaatar", GET /v1/reverse at P1, and PMTiles Range requests at z14 over Ulaanbaatar
- half of the clients reuse one keep-alive connection, half open a new connection per request
- NO client-side retries: a request that fails on a stale keep-alive connection is a failure
- failure = connection error, no response within 5 s, or a status other than 200 (206 for Range requests)

The tiles client behaves like the pmtiles JS library: it caches the header + directories with the ETag of the
response that delivered them; a Range response with a different ETag makes it re-read the header (that read is
a logged request too) and re-locate the tile. A tile whose bytes were served under the SAME ETag as the cached
header must gunzip, else it is a decode error. P1/P2 are the NAV-001 reference points, no user data is logged.
"""
import argparse
import calendar
import gzip
import http.client
import json
import os
import sys
import threading
import time
import urllib.parse

# NAV-001 reference points (lat, lon), as in backend/scripts/smoke.py and the NAV-001 test plan.
P1 = (47.9189, 106.9176)   # NAV-001 P1 (Sükhbaatar Square), same values as backend/scripts/smoke.py
P2 = (47.9139, 106.9044)   # NAV-001 P2
TILE_PATH = "/tiles/basemap.pmtiles"
TIMEOUT_S = 5.0

ROUTE_BODY = json.dumps({"locations": [{"lat": P1[0], "lon": P1[1]}, {"lat": P2[0], "lon": P2[1]}],
                         "costing": "auto", "language": "mn-MN"}).encode()
ENDPOINTS = [
    ("health", "GET", "/health", None),
    ("route", "POST", "/v1/route", ROUTE_BODY),
    ("tiles", "GET", TILE_PATH, None),
    ("search_mn", "GET", "/v1/search?" + urllib.parse.urlencode({"q": "Сүхбаатар", "lang": "mn", "limit": 5}), None),
    ("reverse", "GET", "/v1/reverse?" + urllib.parse.urlencode({"lat": P1[0], "lon": P1[1], "lang": "mn"}), None),
    ("tiles", "GET", TILE_PATH, None),
    ("search_en", "GET", "/v1/search?" + urllib.parse.urlencode({"q": "Sukhbaatar", "lang": "en", "limit": 5}), None),
]


# ---------------------------------------------------------------- minimal PMTiles v3 reader (spec v3)
def _varint(b, p):
    r = s = 0
    while True:
        x = b[p]
        p += 1
        r |= (x & 0x7F) << s
        s += 7
        if x < 0x80:
            return r, p


def _decomp(raw, comp):
    if comp in (0, 1):
        return raw
    if comp == 2:
        return gzip.decompress(raw)
    raise ValueError(f"unsupported compression {comp}")


def _directory(buf):
    n, p = _varint(buf, 0)
    ents, last = [], 0
    for _ in range(n):
        d, p = _varint(buf, p)
        last += d
        ents.append([last, 0, 0, 0])            # tile_id, offset, length, run_length
    for e in ents:
        e[3], p = _varint(buf, p)
    for e in ents:
        e[2], p = _varint(buf, p)
    for i, e in enumerate(ents):
        v, p = _varint(buf, p)
        e[1] = ents[i - 1][1] + ents[i - 1][2] if (v == 0 and i > 0) else v - 1
    return ents


def _find(ents, tid):
    lo, hi = 0, len(ents) - 1
    while lo <= hi:
        m = (lo + hi) // 2
        if tid > ents[m][0]:
            lo = m + 1
        elif tid < ents[m][0]:
            hi = m - 1
        else:
            return ents[m]
    if hi >= 0 and (ents[hi][3] == 0 or tid - ents[hi][0] < ents[hi][3]):
        return ents[hi]
    return None


def zxy_to_tileid(z, x, y):
    acc = sum(4 ** i for i in range(z))
    n = 1 << z
    d, s, tx, ty = 0, n // 2, x, y
    while s > 0:
        rx = 1 if tx & s else 0
        ry = 1 if ty & s else 0
        d += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                tx, ty = s - 1 - tx, s - 1 - ty
            tx, ty = ty, tx
        s //= 2
    return acc + d


def lonlat_to_tile(lat, lon, z):
    import math
    n = 1 << z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return x, y


def _range_get(base, off, length, conn=None):
    u = urllib.parse.urlparse(base)
    c = conn or http.client.HTTPConnection(u.hostname, u.port, timeout=TIMEOUT_S)
    c.request("GET", TILE_PATH, headers={"Range": f"bytes={off}-{off + length - 1}"})
    r = c.getresponse()
    data = r.read()
    if conn is None:
        c.close()
    return r.status, r.getheader("ETag"), data


class Archive:
    """Header + z14 tile ranges near P1, cached with the ETag they were read under (pmtiles-like)."""

    def __init__(self, base, z=14):
        self.base, self.z, self.lock = base, z, threading.Lock()
        self.etag = None
        self.tiles = []          # (offset, length)
        self.comp = None
        self.reloads = []        # {"t", "from", "to"}
        self.load()

    def load(self):
        st, etag, data = _range_get(self.base, 0, 16384)
        if st != 206 or data[:2] != b"PM" or data[7] != 3:
            raise RuntimeError(f"PMTiles header read failed: status {st}")
        g = lambda o: int.from_bytes(data[o:o + 8], "little")  # noqa: E731
        root_off, root_len, leaf_off, tile_off = g(8), g(16), g(40), g(56)
        icomp, tcomp = data[97], data[98]
        root = _directory(_decomp(data[root_off:root_off + root_len], icomp))
        x0, y0 = lonlat_to_tile(P1[0], P1[1], self.z)
        tiles = []
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                tid = zxy_to_tileid(self.z, x0 + dx, y0 + dy)
                ents, hit = root, None
                for _ in range(4):
                    e = _find(ents, tid)
                    if not e:
                        break
                    if e[3] > 0:
                        hit = (tile_off + e[1], e[2])
                        break
                    st2, et2, raw = _range_get(self.base, leaf_off + e[1], e[2])
                    if st2 != 206 or et2 != etag:
                        raise RuntimeError(f"leaf directory read failed: status {st2}, etag {et2} vs {etag}")
                    ents = _directory(_decomp(raw, icomp))
                if hit:
                    tiles.append(hit)
        if not tiles:
            raise RuntimeError("no z14 tile near P1 in the archive")
        self.etag, self.tiles, self.comp = etag, tiles, tcomp

    def pick(self, i):
        with self.lock:
            return self.etag, self.tiles[i % len(self.tiles)], self.comp

    def saw(self, etag, t):
        """Called with every tiles response ETag; re-reads the header once per new ETag."""
        with self.lock:
            if etag and etag != self.etag:
                old = self.etag
                self.load()
                self.reloads.append({"t": t, "from": old, "to": self.etag})
                return old
        return None


# ---------------------------------------------------------------- loop
def worker(idx, keepalive, base, stop, period, archive, emit):
    u = urllib.parse.urlparse(base)
    conn, i, nxt = None, idx, time.monotonic()
    while not stop():
        name, method, path, body = ENDPOINTS[i % len(ENDPOINTS)]
        headers = {"Content-Type": "application/json"} if body else {}
        etag0 = rng = comp = None
        if name == "tiles":
            etag0, rng, comp = archive.pick(i // len(ENDPOINTS))
            headers["Range"] = f"bytes={rng[0]}-{rng[0] + rng[1] - 1}"
        rec = {"t": round(time.time(), 3), "client": "keepalive" if keepalive else "new", "w": idx, "ep": name}
        t0 = time.monotonic()
        try:
            if conn is None:
                conn = http.client.HTTPConnection(u.hostname, u.port, timeout=TIMEOUT_S)
            conn.request(method, path, body=body, headers=headers)
            r = conn.getresponse()
            data = r.read()
            rec["st"] = r.status
            rec["ok"] = r.status == (206 if name == "tiles" else 200)
            if name == "tiles":
                et = r.getheader("ETag")
                rec["etag"] = et
                if r.status == 206 and et == etag0 and comp == 2:
                    try:
                        gzip.decompress(data)
                    except (OSError, EOFError):
                        rec["decode_error"] = True
                        rec["ok"] = False
                try:
                    old = archive.saw(et, rec["t"])
                except Exception as e:  # noqa: BLE001 - a failed header re-read is a failed client
                    rec.update(ok=False, err=f"header re-read: {type(e).__name__}: {e}"[:200])
                    old = None
                if old:
                    rec["etag_change"] = {"from": old, "to": et}
            if not keepalive:
                conn.close()
                conn = None
        except Exception as e:  # noqa: BLE001 - every client-side error is a failed request (no retries)
            rec.update(st=0, ok=False, err=f"{type(e).__name__}: {e}"[:200])
            if conn is not None:
                conn.close()
            conn = None
        rec["ms"] = round((time.monotonic() - t0) * 1000, 1)
        if rec["ms"] > TIMEOUT_S * 1000:
            rec["ok"] = False
        emit(rec)
        i += 1
        nxt += period
        d = nxt - time.monotonic()
        if d > 0:
            time.sleep(d)
        else:
            nxt = time.monotonic()
    if conn is not None:
        conn.close()


def p95(vals):
    if not vals:
        return None
    v = sorted(vals)
    return v[min(len(v) - 1, int(round(0.95 * (len(v) - 1))))]


def parse_utc(s):
    s = s.rstrip("Z").split(".")[0]
    return float(calendar.timegm(time.strptime(s, "%Y-%m-%dT%H:%M:%S")))


def fmt(t):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))


def summarize(path, switch_at=None):
    recs = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                recs.append(json.loads(line))
    if not recs:
        return {"requests": 0, "pass": False}
    recs.sort(key=lambda r: r["t"])
    t0, t1 = recs[0]["t"], recs[-1]["t"]
    per = {}
    for r in recs:
        e = per.setdefault(r["ep"], {"requests": 0, "failures": 0, "ms": []})
        e["requests"] += 1
        e["failures"] += 0 if r["ok"] else 1
        e["ms"].append(r["ms"])
    for e in per.values():
        e["p95_ms"] = p95(e.pop("ms"))
    changes = [{"t": fmt(r["t"]), "t_epoch": r["t"], **r["etag_change"]} for r in recs if r.get("etag_change")]
    etag_first_seen = {}
    for r in recs:
        if r.get("etag") and r["etag"] not in etag_first_seen:
            etag_first_seen[r["etag"]] = fmt(r["t"])
    out = {
        "file": os.path.basename(path), "first": fmt(t0), "last": fmt(t1), "seconds": round(t1 - t0, 1),
        "requests": len(recs), "rate_per_s": round(len(recs) / max(t1 - t0, 1e-9), 1),
        "failures": sum(1 for r in recs if not r["ok"]),
        "by_client": {c: {"requests": sum(1 for r in recs if r["client"] == c),
                          "failures": sum(1 for r in recs if r["client"] == c and not r["ok"])}
                      for c in ("keepalive", "new")},
        "per_endpoint": per, "p95_ms_all": p95([r["ms"] for r in recs]),
        "tile_decode_errors": sum(1 for r in recs if r.get("decode_error")),
        "etag_changes": [{k: v for k, v in c.items() if k != "t_epoch"} for c in changes],
        "etags_first_seen": etag_first_seen,
        "failed_examples": [r for r in recs if not r["ok"]][:10],
    }
    s = parse_utc(switch_at) if switch_at else (changes[0]["t_epoch"] if changes else None)
    if s:
        before = [r["ms"] for r in recs if s - 60 <= r["t"] < s]
        window = [r["ms"] for r in recs if s - 10 <= r["t"] <= s + 30]
        out["switch_at"] = fmt(s)
        out["switch_at_source"] = "argument (pipeline run log)" if switch_at else "first tiles ETag change"
        out["seconds_before_switch"] = round(s - t0, 1)
        out["seconds_after_switch"] = round(t1 - s, 1)
        out["p95_ms_60s_before"] = p95(before)
        out["p95_ms_window_-10s_+30s"] = p95(window)
        out["p95_ratio"] = round(out["p95_ms_window_-10s_+30s"] / out["p95_ms_60s_before"], 2) if before else None
        if changes:
            out["first_etag_change_minus_switch_s"] = round(changes[0]["t_epoch"] - s, 2)
    out["pass"] = bool(out["failures"] == 0 and out["rate_per_s"] >= 10 and out["tile_decode_errors"] == 0 and
                       (not s or (out["seconds_before_switch"] >= 60 and out["seconds_after_switch"] >= 60 and
                                  (out["p95_ratio"] is None or out["p95_ratio"] <= 2))))
    return out


def cmd_run(a):
    base = a.base_url.rstrip("/")
    archive = Archive(base)
    end = time.monotonic() + a.duration
    stop = lambda: time.monotonic() >= end or (a.stop_file and os.path.exists(a.stop_file))  # noqa: E731
    period = a.clients / a.rate
    lock = threading.Lock()
    with open(a.out, "w", encoding="utf-8") as out:
        def emit(rec):
            with lock:
                out.write(json.dumps(rec, ensure_ascii=False) + "\n")
                out.flush()
        ths = [threading.Thread(target=worker, args=(i, i % 2 == 0, base, stop, period, archive, emit), daemon=True)
               for i in range(a.clients)]
        for t in ths:
            t.start()
        for t in ths:
            t.join()
    s = summarize(a.out)
    s["archive_header_reloads"] = len(archive.reloads)
    print(json.dumps(s, ensure_ascii=False, indent=1))
    return 0 if s["failures"] == 0 else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--base-url", required=True)
    r.add_argument("--out", required=True)
    r.add_argument("--rate", type=float, default=24.0)
    r.add_argument("--clients", type=int, default=6)
    r.add_argument("--duration", type=float, default=1800.0, help="hard upper bound in seconds")
    r.add_argument("--stop-file", help="stop as soon as this file exists")
    s = sub.add_parser("summarize")
    s.add_argument("file")
    s.add_argument("--switch-at", help="UTC time of the pointer rename (from the run log)")
    s.add_argument("--json", help="also write the summary here")
    a = ap.parse_args()
    if a.cmd == "run":
        if a.rate < 10:
            ap.error("AC 15 needs >= 10 requests/s")
        return cmd_run(a)
    out = summarize(a.file, a.switch_at)
    txt = json.dumps(out, ensure_ascii=False, indent=1)
    print(txt)
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            f.write(txt + "\n")
    return 0 if out["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())

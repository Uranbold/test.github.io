#!/usr/bin/env python3
"""NAV-006 AC 15 request loop (backend's own check; QA's official loop lives under tests/).

    request_loop.py --base-url http://127.0.0.1:18080 --duration 300 --out loop.jsonl [--rate 24]
    request_loop.py summarize loop.jsonl --switch-at 2026-10-04T03:10:00Z

Clients: half keep-alive (one persistent http.client connection each), half a new connection per request.
No client-side retries: a request on a stale keep-alive connection that fails is counted as a failure.
Endpoints in rotation: GET /health, POST /v1/route P1->P2 auto, GET /v1/search «Сүхбаатар» and "Sukhbaatar",
GET /v1/reverse P1, PMTiles Range reads of z14 tiles around P1 (206 expected).
Failure = connection error, no response within 5 s, or a status other than 200 (206 for ranges).
Tiles behave like the pmtiles JS client: when a range response carries a different ETag than the header the
client cached, the header and directories are read again (ETag transitions are logged = switch moments);
a tile whose bytes do not gunzip with the cached header of the SAME ETag counts as a decode error.
Every request is one JSON line in --out (no client data; P1/P2 are the NAV-001 reference points).
"""
import argparse
import gzip
import http.client
import json
import sys
import threading
import time
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import smoke  # noqa: E402  (PMTiles directory reader, reference points)

P1, P2 = smoke.P["P1"], smoke.P["P2"]
ROUTE = json.dumps({"locations": [{"lat": P1[0], "lon": P1[1]}, {"lat": P2[0], "lon": P2[1]}], "costing": "auto",
                    "format": "osrm", "language": "mn-MN", "banner_instructions": True,
                    "voice_instructions": True}).encode()
ENDPOINTS = [
    ("health", "GET", "/health", None),
    ("route", "POST", "/v1/route", ROUTE),
    ("search_mn", "GET", "/v1/search?" + urllib.parse.urlencode({"q": "Сүхбаатар", "lang": "mn", "limit": 5}), None),
    ("search_en", "GET", "/v1/search?" + urllib.parse.urlencode({"q": "Sukhbaatar", "lang": "en", "limit": 5}), None),
    ("reverse", "GET", "/v1/reverse?" + urllib.parse.urlencode({"lat": P1[0], "lon": P1[1], "lang": "mn"}), None),
    ("tiles", "GET", "/tiles/basemap.pmtiles", None),
]


class Archive:
    """Cached PMTiles header + z14 tile ranges near P1, re-read when the ETag changes."""

    def __init__(self, base):
        self.base, self.lock = base, threading.Lock()
        self.etag, self.ranges, self.tile_comp, self.reloads = None, [], None, 0
        self.load()

    def load(self):
        s = smoke.Suite(self.base)
        st, h, _, _ = s.http("HEAD", "/tiles/basemap.pmtiles")
        pm = smoke.PMTiles(s)
        x, y = smoke.lonlat_to_tile(*P1, 14)
        ranges = [pm.locate(14, x + dx, y + dy) for dx in (-1, 0, 1) for dy in (-1, 0, 1)]
        self.ranges = [r for r in ranges if r]
        self.tile_comp, self.etag = pm.tile_comp, h.get("etag")

    def next_range(self, i):
        with self.lock:
            return self.etag, self.ranges[i % len(self.ranges)], self.tile_comp

    def seen(self, etag):
        with self.lock:
            if etag and etag != self.etag:
                old = self.etag
                self.load()
                self.reloads += 1
                return old
        return None


def worker(idx, keepalive, base, deadline, period, archive, out, lock, counter):
    u = urllib.parse.urlparse(base)
    conn = None
    i = idx
    nxt = time.monotonic()
    while time.monotonic() < deadline:
        name, method, path, body = ENDPOINTS[i % len(ENDPOINTS)]
        headers = {"Content-Type": "application/json"} if body else {}
        etag0 = rng = comp = None
        if name == "tiles":
            etag0, rng, comp = archive.next_range(i)
            headers["Range"] = f"bytes={rng[0]}-{rng[0] + rng[1] - 1}"
        rec = {"t": time.time(), "client": "keepalive" if keepalive else "new", "worker": idx, "endpoint": name}
        t0 = time.monotonic()
        try:
            if conn is None or not keepalive:
                if conn is not None:
                    conn.close()
                conn = http.client.HTTPConnection(u.hostname, u.port, timeout=5)
            conn.request(method, path, body=body, headers=headers)
            r = conn.getresponse()
            data = r.read()
            rec["status"] = r.status
            want = 206 if name == "tiles" else 200
            rec["ok"] = r.status == want
            if name == "tiles":
                et = r.getheader("ETag")
                rec["etag"] = et
                if et == etag0 and r.status == 206 and comp == 2:
                    try:
                        gzip.decompress(data)
                    except OSError:
                        rec["decode_error"] = True
                old = archive.seen(et)
                if old:
                    rec["etag_change"] = {"from": old, "to": et}
            if not keepalive:
                conn.close()
                conn = None
        except Exception as e:  # noqa: BLE001 - every client-side error is a failed request
            rec.update(status=0, ok=False, error=f"{type(e).__name__}: {e}"[:160])
            if conn is not None:
                conn.close()
            conn = None
        rec["ms"] = round((time.monotonic() - t0) * 1000, 1)
        if rec["ms"] > 5000:
            rec["ok"] = False
        with lock:
            out.write(json.dumps(rec, ensure_ascii=False) + "\n")
            counter[0] += 1
        i += 1
        nxt += period
        delay = nxt - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        else:
            nxt = time.monotonic()


def p95(vals):
    if not vals:
        return None
    v = sorted(vals)
    return v[min(len(v) - 1, int(round(0.95 * (len(v) - 1))))]


def summarize(path, switch_at=None):
    recs = [json.loads(x) for x in Path(path).read_text().splitlines() if x.strip()]
    if not recs:
        return {"requests": 0}
    t0, t1 = recs[0]["t"], recs[-1]["t"]
    per = {}
    for r in recs:
        e = per.setdefault(r["endpoint"], {"requests": 0, "failures": 0})
        e["requests"] += 1
        e["failures"] += 0 if r["ok"] else 1
    out = {"requests": len(recs), "seconds": round(t1 - t0, 1), "rate_per_s": round(len(recs) / max(t1 - t0, 1), 1),
           "failures": sum(1 for r in recs if not r["ok"]),
           "failed_examples": [r for r in recs if not r["ok"]][:10],
           "by_client": {c: sum(1 for r in recs if r["client"] == c) for c in ("keepalive", "new")},
           "per_endpoint": per, "p95_ms_all": p95([r["ms"] for r in recs]),
           "tile_decode_errors": sum(1 for r in recs if r.get("decode_error")),
           "etag_changes": [{"t": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(r["t"])), **r["etag_change"]}
                            for r in recs if r.get("etag_change")],
           "first": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t0)),
           "last": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t1))}
    if switch_at:
        s = switch_at if isinstance(switch_at, float) else time.mktime(time.strptime(switch_at, "%Y-%m-%dT%H:%M:%SZ")) - time.timezone
        before = [r["ms"] for r in recs if s - 60 <= r["t"] < s]
        window = [r["ms"] for r in recs if s - 10 <= r["t"] <= s + 30]
        out["switch_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(s))
        out["seconds_before_switch"] = round(s - t0, 1)
        out["seconds_after_switch"] = round(t1 - s, 1)
        out["p95_ms_60s_before"] = p95(before)
        out["p95_ms_window_-10s_+30s"] = p95(window)
        if out["p95_ms_60s_before"]:
            out["p95_ratio"] = round(out["p95_ms_window_-10s_+30s"] / out["p95_ms_60s_before"], 2)
    return out


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "summarize":
        ap = argparse.ArgumentParser()
        ap.add_argument("cmd")
        ap.add_argument("file")
        ap.add_argument("--switch-at", help="UTC ISO time of the switch (pointer rename)")
        a = ap.parse_args()
        print(json.dumps(summarize(a.file, a.switch_at), ensure_ascii=False, indent=2))
        return 0
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--duration", type=float, required=True, help="seconds")
    ap.add_argument("--rate", type=float, default=24, help="total requests per second (default 24)")
    ap.add_argument("--clients", type=int, default=6, help="half keep-alive, half new connection (default 6)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    archive = Archive(a.base_url.rstrip("/"))
    deadline = time.monotonic() + a.duration
    period = a.clients / a.rate
    lock, counter = threading.Lock(), [0]
    with open(a.out, "w", encoding="utf-8") as out:
        ths = [threading.Thread(target=worker, args=(i, i % 2 == 0, a.base_url, deadline, period, archive, out, lock,
                                                      counter), daemon=True) for i in range(a.clients)]
        for t in ths:
            t.start()
        for t in ths:
            t.join()
    s = summarize(a.out)
    s["archive_reloads"] = archive.reloads
    print(json.dumps(s, ensure_ascii=False, indent=2))
    return 1 if s.get("failures") else 0


if __name__ == "__main__":
    sys.exit(main())

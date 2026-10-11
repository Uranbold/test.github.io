#!/usr/bin/env python3
"""SEC-4A final-QA checks through the gateway (Python 3.9+ stdlib only). Owner: qa-engineer.

Traces: SEC-4A (backlog SEC-4 split, D215; no story file yet) audit findings BE-3 (download connection and bandwidth
limits) and BE-12/BE-13 (X-Content-Type-Options on every gateway response); NAV-001 AC 43/49 and openapi 0.7.0
(CORS headers exactly once); NAV-020 / openapi getOfflinePackManifest + getOfflinePackFile (manifest re-readable,
Range resume 206). BE-2 (IPv6 /64 keying) is backend/scripts/rate_limit_check.py --only ipv6-prefix.

    python3 tests/api/sec4a/checks_sec4a.py --base-url URL --group G [--group G ...]

Groups
  headers-dev   any gateway with Valhalla + Photon (the dev stack): /health 200, /v1/route POST 200 and 400
                (DistanceExceeded), /v1/route GET 200, /v1/search 200, /v1/reverse 200, /tiles Range 206, /tiles 416,
                OPTIONS preflight 204, unknown path 404, PUT /v1/route 405. Each response: Access-Control-Allow-Origin
                and Access-Control-Expose-Headers exactly once, no Access-Control-* repeated, exactly one
                "X-Content-Type-Options: nosniff"
  headers-cap   isolated gateway (tests/api/nav001/isolated-gateway.sh) in front of the QA stub
                (tests/api/nav001/sec2-harness.sh stub-up), default cap 4 per IP: /v1/route 503 RoutingBusy (4 held) and
                504 UpstreamTimeout (stub mode timeoutnow), same header rules
  packs         isolated gateway with GATEWAY_RATE_LIMIT=on (download limits default 2 per client) and QA_PACKS_DIR (a
                pack tree mn/manifest.json + mn/<slot-id>/basemap.pmtiles.gz bigger than GATEWAY_PACKS_LIMIT_RATE_AFTER):
                2 pack-file downloads held open + a 3rd from the same client -> 429 RateLimited (GatewayError,
                Retry-After >= 1, no-store, CORS once, nosniff once) while manifest.json stays 200; after the 2 are
                closed, a Range resume -> 206 with Content-Range, and a plain GET -> 200
                Pass --pack-path /packs/mn/<slot-id>/basemap.pmtiles.gz.

Exit code 0 only if no check failed. Every FAIL line prints expected and actual.
"""
import argparse
import http.client
import json
import os
import sys
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "nav001"))
from lib import ORIGIN, Client, Report, routing_busy_errors  # noqa: E402

H = {"Origin": ORIGIN}


class Sec4a:
    def __init__(self, base, rep, pack_path=None):
        self.c, self.r, self.base, self.pack_path = Client(base), rep, base.rstrip("/"), pack_path

    def headers_once(self, cid, r, expect_status):
        """CORS headers exactly once (AC 43 rule) and X-Content-Type-Options: nosniff exactly once (SEC-4A)."""
        vals = lambda n: [v for k, v in r.raw_headers if k.lower() == n]  # noqa: E731
        st = expect_status if isinstance(expect_status, tuple) else (expect_status,)
        self.r.check(cid + ".status", r.status in st, st, f"{r.status} {r.body[:80]!r}")
        self.r.check(cid + ".acao_once", len(vals("access-control-allow-origin")) == 1
                     and vals("access-control-allow-origin")[0] in (ORIGIN, "*"),
                     f"exactly 1 Access-Control-Allow-Origin ({ORIGIN} or *)", vals("access-control-allow-origin"))
        self.r.check(cid + ".aceh_once", len(vals("access-control-expose-headers")) == 1,
                     "exactly 1 Access-Control-Expose-Headers", len(vals("access-control-expose-headers")))
        rep = r.repeated_headers()
        self.r.check(cid + ".no_repeated_access_control", not rep, "each Access-Control-* at most once", rep)
        ns = vals("x-content-type-options")
        self.r.check(cid + ".nosniff_once", ns == ["nosniff"], "exactly ['nosniff']", ns)

    def _route_body(self, a, b, **kw):
        return self.c.route_body(a, b, **kw)

    # ------------------------------------------------------------------ headers on the dev stack
    def headers_dev(self):
        c = self.c
        self.headers_once("HDR.health_200", c.request("GET", "/health", headers=H), 200)
        self.headers_once("HDR.route_POST_200", c.request("POST", "/v1/route", c.route_body("P1", "P3"), headers=H), 200)
        self.headers_once("HDR.route_GET_200", c.route_get(c.route_body("P1", "P3"), headers=H), 200)
        r = c.request("POST", "/v1/route", c.route_body("P1", "X3"), headers=H)
        self.headers_once("HDR.route_POST_400", r, 400)
        self.r.note("HDR.route_POST_400.code", (r.json() or {}).get("code") if isinstance(r.json(), dict) else None)
        self.headers_once("HDR.search_200", c.get("/v1/search", {"q": "Сүхбаатар", "lang": "mn", "limit": 1}, headers=H), 200)
        self.headers_once("HDR.reverse_200", c.get("/v1/reverse", {"lat": 47.9189, "lon": 106.9176, "lang": "mn"}, headers=H), 200)
        self.headers_once("HDR.tiles_range_206", c.request("GET", "/tiles/basemap.pmtiles", headers={**H, "Range": "bytes=0-126"}), 206)
        size = int(c.request("HEAD", "/tiles/basemap.pmtiles", headers=H).header("content-length") or 0)
        self.headers_once("HDR.tiles_416", c.request("GET", "/tiles/basemap.pmtiles", headers={**H, "Range": f"bytes={size}-"}), 416)
        pre = {**H, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type"}
        self.headers_once("HDR.route_preflight_204", c.request("OPTIONS", "/v1/route", headers=pre), 204)
        self.headers_once("HDR.unknown_404", c.request("GET", "/no-such-path", headers=H), 404)
        self.headers_once("HDR.route_PUT_405", c.request("PUT", "/v1/route", c.route_body("P1", "P3"), headers=H), 405)
        self.headers_once("HDR.packs_404_dev", c.request("GET", "/packs/mn/manifest.json", headers=H), (404, 200))

    # ------------------------------------------------------------------ headers on the cap / timeout answers
    def _stub(self, mode, timeout=20):
        body = self.c.route_body("P1", "P3")
        body["qa_stub"] = mode
        return self.c.request("POST", "/v1/route", body, headers=H, timeout=timeout)

    def headers_cap(self):
        with ThreadPoolExecutor(max_workers=4) as pool:
            futs = [pool.submit(self._stub, "slow") for _ in range(4)]
            time.sleep(0.8)
            r = self._stub("slow")
            held = [f.result().status for f in futs]
        self.headers_once("HDR.route_503", r, 503)
        errs = routing_busy_errors(r, origin=ORIGIN)
        self.r.check("HDR.route_503.RoutingBusy_shape", not errs, "openapi RoutingBusy", "; ".join(errs) or "ok")
        self.r.note("HDR.route_503.held", held)
        self.headers_once("HDR.route_504", self._stub("timeoutnow"), 504)

    # ------------------------------------------------------------------ packs download limits (BE-3)
    def _open_download(self, path, extra=None):
        """GET that reads only the status line and headers, then leaves the body unread so the download stays in
        flight at the gateway (TCP back-pressure plus the bandwidth limit)."""
        u = urllib.parse.urlsplit(self.base)
        conn = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=20)
        conn.request("GET", path, headers={**H, **(extra or {})})
        resp = conn.getresponse()
        return conn, resp

    def packs(self):
        if not self.pack_path:
            return self.r.check("PACK.precondition_pack_path", False, "--pack-path /packs/mn/<slot-id>/<file>.gz", None)
        c = self.c
        m = c.request("GET", "/packs/mn/manifest.json", headers=H)
        self.r.check("PACK.precondition_manifest_200", m.status == 200, 200, f"{m.status} {m.body[:80]!r}")
        h = c.request("HEAD", self.pack_path, headers=H)
        size = int(h.header("content-length") or 0)
        self.r.note("PACK.file", f"{self.pack_path}: HEAD {h.status}, {size} bytes")
        held = []
        try:
            for i in range(2):
                conn, resp = self._open_download(self.pack_path)
                held.append(conn)
                self.r.check(f"PACK.download_{i + 1}_200", resp.status == 200, 200, resp.status)
            time.sleep(0.3)
            r3 = c.request("GET", self.pack_path, headers=H, timeout=10)
            self.r.check("PACK.download_3_429", r3.status == 429, "429 (3rd parallel pack-file download, same client)",
                         f"{r3.status} {len(r3.body)} bytes in {r3.elapsed * 1000:.0f} ms")
            j = r3.json() if r3.status == 429 else None
            ra = [v for k, v in r3.raw_headers if k.lower() == "retry-after"]
            cc = [v for k, v in r3.raw_headers if k.lower() == "cache-control"]
            ct = (r3.header("content-type") or "").split(";")[0]
            self.r.check("PACK.download_3_429.RateLimited_shape",
                         isinstance(j, dict) and j.get("code") == "RateLimited" and ct == "application/json"
                         and len(ra) == 1 and ra[0].isdigit() and int(ra[0]) >= 1 and cc == ["no-store"],
                         "GatewayError RateLimited JSON, one Retry-After >= 1, Cache-Control exactly ['no-store']",
                         f"{ct!r} {r3.body[:80]!r} Retry-After={ra} Cache-Control={cc}")
            self.r.check("PACK.download_3_429.within_1s", r3.elapsed <= 1, "<= 1 s", f"{r3.elapsed * 1000:.0f} ms")
            self.headers_once("PACK.download_3_429", r3, 429)
            m2 = c.request("GET", "/packs/mn/manifest.json", headers=H)
            self.headers_once("PACK.manifest_while_capped", m2, 200)
            m3 = c.request("HEAD", "/packs/mn/manifest.json", headers=H)
            self.r.check("PACK.manifest_HEAD_while_capped_200", m3.status == 200, 200, m3.status)
        finally:
            for conn in held:
                conn.close()
        time.sleep(1.0)  # the gateway notices the closed connections; also drains the packs request bucket a little
        start = max(size - 1_000_000, 0) if size else 1_000_000
        conn, resp = self._open_download(self.pack_path, {"Range": f"bytes={start}-"})
        try:
            body = resp.read(1_000_001)
            hdrs = resp.getheaders()
        finally:
            conn.close()
        cr = [v for k, v in hdrs if k.lower() == "content-range"]
        self.r.check("PACK.range_resume_206", resp.status == 206 and cr == [f"bytes {start}-{size - 1}/{size}"]
                     and len(body) == size - start, f"206, Content-Range bytes {start}-{size - 1}/{size}, {size - start} bytes",
                     f"{resp.status} {cr} {len(body)} bytes")
        ns = [v for k, v in hdrs if k.lower() == "x-content-type-options"]
        acao = [v for k, v in hdrs if k.lower() == "access-control-allow-origin"]
        self.r.check("PACK.range_resume_206.headers_once", ns == ["nosniff"] and len(acao) == 1,
                     "nosniff once, ACAO once", f"nosniff={ns} acao={acao}")
        conn, resp = self._open_download(self.pack_path)
        conn.close()
        self.r.check("PACK.slot_free_after_close_200", resp.status == 200, "200 once the held downloads are closed", resp.status)

    def run(self, groups):
        if "headers-dev" in groups:
            self.headers_dev()
        if "headers-cap" in groups:
            self.headers_cap()
        if "packs" in groups:
            self.packs()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--group", action="append", required=True, choices=["headers-dev", "headers-cap", "packs"])
    ap.add_argument("--pack-path")
    ap.add_argument("--json-out")
    a = ap.parse_args()
    rep = Report()
    print(f"SEC-4A QA checks: base={a.base_url} groups={','.join(a.group)}")
    try:
        Sec4a(a.base_url, rep, a.pack_path).run(a.group)
    except Exception as e:  # never exit 0 on a crash
        rep.check("PRE.suite_crashed", False, "no exception", f"{type(e).__name__}: {e}")
    print("\n" + rep.summary())
    if rep.failed:
        print("Failed checks:\n  " + "\n  ".join(f"{cid}: {d}" for _s, cid, d in rep.failed))
    if a.json_out:
        with open(a.json_out, "w") as f:
            json.dump({"summary": rep.summary(), "results": [{"status": s, "id": c, "detail": d} for s, c, d in rep.results]},
                      f, ensure_ascii=False, indent=1, default=str)
    sys.exit(1 if rep.failed else 0)


if __name__ == "__main__":
    main()

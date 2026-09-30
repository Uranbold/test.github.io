#!/usr/bin/env python3
"""Test double of the NAV-008 staging gateway, for SELF-TESTING the NAV-008 QA scripts only.

Owner: qa-engineer. Test plan: docs/qa/test-plans/NAV-008.md section 6 (self-test of the tooling).
This is NOT the product. It emulates what openapi.yaml 0.4.0 and deployment-staging.md section 7.1 promise
(CORS allowlist, per-IP token bucket on /v1/route and on /v1/search + /v1/reverse, 429 RateLimited), so the
checks in staging_checks.py / contract_nav008.py can be shown to PASS on a conforming server and to FAIL on
each seeded fault, without bursting the shared dev gateway (NAV-003 runs on it in parallel).

    python3 tests/api/nav008/mock_gateway.py --port 18480 [--origins http://localhost:5173] [--fault NAME ...]

Faults (each one breaks exactly one promise):
  star               Access-Control-Allow-Origin: * on every response (allowlist broken, AC 11)
  echo-any-origin    echoes any Origin, including http://evil.example (AC 11)
  suffix-match       allows any origin that starts with an allowlisted origin (http://localhost:5173.evil.example)
  dup-acao-429       429 carries Access-Control-Allow-Origin twice (NFR-C3)
  retry-after-date   Retry-After as an HTTP date (contract: integer seconds only)
  retry-after-zero   Retry-After: 0 (contract: >= 1)
  no-retry-after     429 without Retry-After
  expose-no-retry    Access-Control-Expose-Headers without Retry-After (0.4.0)
  cache-429          429 with Cache-Control: max-age=60 instead of no-store
  html-429           429 with an HTML body (nginx default page)
  limit-tiles        tile range requests are rate limited too (AC 13: never)
  limit-health       /health is rate limited (uptime checker must never be limited)
  tight-limit        route/search limited at 5 r/s burst 5 (a 10 r/s session gets 429, AC 13)
  no-limit           no rate limiting at all (no 429 can be provoked -> checks must SKIP, not FAIL)
"""
import argparse
import json
import threading
import time
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

EXPOSE = "Content-Range, Content-Length, ETag, Accept-Ranges, Retry-After"
TILE_SIZE = 200_000
TILE_BYTES = bytes((i * 7) & 0xFF for i in range(TILE_SIZE))
ROUTE_OK = {"code": "Ok", "routes": [{"distance": 1234.5, "duration": 300.0, "geometry": "_p~iF~ps|U_ulLnnqC",
                                     "legs": [{"steps": [], "distance": 1234.5, "duration": 300.0, "summary": ""}],
                                     "weight": 300.0, "weight_name": "auto", "voiceLocale": "mn-MN"}],
            "waypoints": [{"location": [106.9176, 47.9189], "name": ""}, {"location": [106.9044, 47.9139], "name": ""}]}
FC = {"type": "FeatureCollection", "features": [{"type": "Feature", "geometry": {"type": "Point", "coordinates": [106.9176, 47.9189]},
                                                 "properties": {"name": "Сүхбаатарын талбай", "osm_id": 1, "osm_type": "W",
                                                                "osm_key": "place", "osm_value": "square"}}]}


class Bucket:
    """nginx limit_req-like leaky bucket: `rate` r/s, `burst` extra requests, nodelay."""

    def __init__(self, rate, burst):
        self.rate, self.burst = rate, burst
        self.state = {}
        self.lock = threading.Lock()

    def allow(self, key):
        now = time.monotonic()
        with self.lock:
            excess, last = self.state.get(key, (0.0, now))
            excess = max(0.0, excess - (now - last) * self.rate)
            if excess + 1 > self.burst + 1:
                self.state[key] = (excess, now)
                return False
            self.state[key] = (excess + 1, now)
            return True


def make_handler(cfg):
    faults = set(cfg.fault or [])
    origins = [o.strip() for o in cfg.origins.split(",") if o.strip()]
    rate, burst = (5, 5) if "tight-limit" in faults else (cfg.rate, cfg.burst)
    zones = {"route": Bucket(rate, burst), "search": Bucket(rate, burst), "tiles": Bucket(5, 5), "health": Bucket(5, 5)}

    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "nav008-mock"

        def log_message(self, *a):  # quiet
            pass

        # ---------------------------------------------------------------- helpers
        def allowed_origin(self):
            o = self.headers.get("Origin")
            if o is None:
                return None
            if "star" in faults:
                return "*"
            if "echo-any-origin" in faults:
                return o
            if "suffix-match" in faults and any(o.startswith(a) for a in origins):
                return o
            return o if o in origins else None

        def cors(self, status):
            acao = self.allowed_origin()
            if acao:
                self.send_header("Access-Control-Allow-Origin", acao)
                if status == 429 and "dup-acao-429" in faults:
                    self.send_header("Access-Control-Allow-Origin", acao)
            self.send_header("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Range, Content-Type, If-None-Match, If-Match, Accept-Language")
            expose = EXPOSE.replace(", Retry-After", "") if "expose-no-retry" in faults else EXPOSE
            self.send_header("Access-Control-Expose-Headers", expose)
            self.send_header("Access-Control-Max-Age", "600")
            self.send_header("Vary", "Origin")

        def send(self, status, body=b"", ctype="application/json", extra=(), head=False):
            if isinstance(body, (dict, list)):
                body = json.dumps(body, ensure_ascii=False).encode()
            self.send_response(status)
            self.cors(status)
            if body or ctype:
                self.send_header("Content-Type", ctype)
            for k, v in extra:
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if not head:
                self.wfile.write(body)

        def limited(self, zone):
            if "no-limit" in faults:
                return False
            if zone in ("tiles", "health") and f"limit-{zone}" not in faults:
                return False
            return not zones[zone].allow(self.client_address[0])

        def send_429(self, head=False):
            extra = []
            if "no-retry-after" not in faults:
                ra = formatdate(time.time() + 1, usegmt=True) if "retry-after-date" in faults else ("0" if "retry-after-zero" in faults else "1")
                extra.append(("Retry-After", ra))
            extra.append(("Cache-Control", "max-age=60" if "cache-429" in faults else "no-store"))
            if "html-429" in faults:
                return self.send(429, b"<html><body><h1>429 Too Many Requests</h1></body></html>", "text/html", extra, head)
            return self.send(429, {"code": "RateLimited", "message": "Too many requests"}, "application/json", extra, head)

        def body(self):
            n = int(self.headers.get("Content-Length") or 0)
            return self.rfile.read(n) if n else b""

        # ---------------------------------------------------------------- routes
        def do_OPTIONS(self):
            self.send_response(204)
            self.cors(204)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_HEAD(self):
            self.do_GET(head=True)

        def do_POST(self):
            path = urlparse(self.path).path
            self.body()
            if path != "/v1/route":
                return self.send(405, {"code": "MethodNotAllowed", "message": "Method not allowed"})
            if self.limited("route"):
                return self.send_429()
            return self.send(200, ROUTE_OK)

        def do_GET(self, head=False):
            u = urlparse(self.path)
            q = parse_qs(u.query)
            if u.path == "/health":
                if self.limited("health"):
                    return self.send_429(head)
                return self.send(200, {"status": "ok"}, head=head)
            if u.path == "/tiles/basemap.pmtiles":
                if self.limited("tiles"):
                    return self.send_429(head)
                etag = '"mock-etag"'
                rng = self.headers.get("Range")
                if not rng:
                    return self.send(200, TILE_BYTES, "application/octet-stream",
                                     [("ETag", etag), ("Accept-Ranges", "bytes"), ("Cache-Control", "public, max-age=300")], head)
                a, _, b = rng.replace("bytes=", "").partition("-")
                start = int(a)
                if start >= TILE_SIZE:
                    return self.send(416, {"code": "RangeNotSatisfiable", "message": "Requested range not satisfiable"},
                                     "application/json", [("Content-Range", f"bytes */{TILE_SIZE}"), ("ETag", etag),
                                                          ("Cache-Control", "no-store")], head)
                end = min(int(b) if b else TILE_SIZE - 1, TILE_SIZE - 1)
                return self.send(206, TILE_BYTES[start:end + 1], "application/octet-stream",
                                 [("Content-Range", f"bytes {start}-{end}/{TILE_SIZE}"), ("ETag", etag),
                                  ("Accept-Ranges", "bytes"), ("Cache-Control", "public, max-age=300")], head)
            if u.path == "/v1/route":
                if self.limited("route"):
                    return self.send_429(head)
                return self.send(200, ROUTE_OK, head=head)
            if u.path in ("/v1/search", "/v1/reverse"):
                if self.limited("search"):
                    return self.send_429(head)
                if u.path == "/v1/search" and not q.get("q"):
                    return self.send(400, {"message": "missing search term 'q'"}, head=head)
                return self.send(200, FC, head=head)
            return self.send(404, {"code": "NotFound", "message": "Not found"}, head=head)

    return H


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=18480)
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--origins", default="https://demo-staging.nav.test,http://localhost:5173")
    ap.add_argument("--rate", type=float, default=30.0, help="r/s per client IP per zone (staging proposal: 30)")
    ap.add_argument("--burst", type=int, default=60, help="burst (staging proposal: 60)")
    ap.add_argument("--fault", action="append", choices=["star", "echo-any-origin", "suffix-match", "dup-acao-429",
                                                         "retry-after-date", "retry-after-zero", "no-retry-after",
                                                         "expose-no-retry", "cache-429", "html-429", "limit-tiles",
                                                         "limit-health", "tight-limit", "no-limit"])
    a = ap.parse_args()
    srv = ThreadingHTTPServer((a.bind, a.port), make_handler(a))
    srv.daemon_threads = True
    srv.request_queue_size = 256
    print(f"nav008 mock gateway on http://{a.bind}:{a.port} faults={a.fault or []}", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

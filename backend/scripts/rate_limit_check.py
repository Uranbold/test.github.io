#!/usr/bin/env python3
"""NAV-008 AC 13 probe: per-client-IP rate limits of the gateway (deployment-staging.md §7.1).

    python3 scripts/rate_limit_check.py --base-url http://127.0.0.1:18391            # all scenarios
    python3 scripts/rate_limit_check.py --base-url https://<staging-host> --only burst-search

Scenarios (stdlib only, one client IP = this machine):
  burst-route / burst-search  100 requests/s for 10 s to /v1/route or /v1/search. Expect >= 1 x 429, and
                              every 429 in the contract shape: GatewayError {code: RateLimited}, exactly one
                              Retry-After (integer >= 1), exactly one Cache-Control: no-store, each
                              Access-Control-* header once, Retry-After listed in Access-Control-Expose-Headers.
                              /health must stay 200 right after the burst.
                              SEC-2 (ADR-0019 §3): burst-route also meets the always-on /v1/route concurrency cap, so
                              a 503 is accepted next to 429 when it is in the contract shape: GatewayError {code:
                              RoutingBusy} with the same header rules. At least one 429 or 503 is required.
  normal                      10 requests/s for 10 s, alternating route/search/reverse. Expect no 429 and no 503.
  tiles                       200 requests/s for 5 s of tile range requests plus preflights and /health. Expect no 429.
  ipv6-prefix (opt-in)        SEC-4A BE-2: 100 requests/s for 3 s to /v1/search, each with a different X-Forwarded-For
                              address inside ONE IPv6 /64 -> expect >= 1 x 429 (one client); then the same with a
                              different /64 per request -> expect no 429. Only against a TEST gateway whose
                              GATEWAY_REAL_IP_FROM covers this machine (on staging Caddy replaces X-Forwarded-For, so the
                              scenario cannot work there; the real IPv6 check is RUNBOOK section 17). Never in the default
                              list.

Wait RETRY seconds (default 2) between scenarios so the buckets drain. Upstream status (200, 400, 502...) does
not matter here: limit_req runs before the proxy, so this works against a gateway with no upstreams too.
Exit 0 only if every selected scenario passes. Output contains counts only (no request data).
"""
import argparse
import collections
import json
import ssl
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ORIGIN = "http://localhost:5173"
SSL_CONTEXT = None  # --insecure: local tests against Caddy's internal CA only
ROUTE_BODY = json.dumps({
    "locations": [{"lat": 47.9189, "lon": 106.9176}, {"lat": 47.6469, "lon": 106.8197}],
    "costing": "auto", "format": "osrm", "language": "mn-MN", "units": "kilometers",
}).encode()
SEARCH = "/v1/search?q=%D0%A1%D2%AF%D1%85%D0%B1%D0%B0%D0%B0%D1%82%D0%B0%D1%80&lang=mn&limit=1"
REVERSE = "/v1/reverse?lat=47.9189&lon=106.9176&lang=mn"


def request(base, method, path, body=None, headers=None, timeout=10):
    hdrs = {"Origin": ORIGIN, **(headers or {})}
    if body is not None:
        hdrs["Content-Type"] = "application/json"
    req = urllib.request.Request(base + path, data=body, method=method, headers=hdrs)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=SSL_CONTEXT) as r:
            return r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()
    except Exception as e:  # connection errors count as status 0
        return 0, None, str(e).encode()


def check_429(status, headers, raw, code="RateLimited"):
    """Contract checks for one 429 (openapi.yaml 0.4.0 components/responses/RateLimited) or, with
    code="RoutingBusy", one 503 (0.7.0 components/responses/RoutingBusy): same header rules."""
    errors = []
    names = [k.lower() for k in headers.keys()]
    for n in sorted(set(names)):
        if (n.startswith("access-control-") or n in ("retry-after", "cache-control")) and names.count(n) > 1:
            errors.append(f"{n} sent {names.count(n)} times")
    ra = headers.get_all("Retry-After") or []
    if len(ra) != 1 or not ra[0].strip().isdigit() or int(ra[0]) < 1:
        errors.append(f"Retry-After must be one integer >= 1, got {ra}")
    cc = headers.get_all("Cache-Control") or []
    if cc != ["no-store"]:
        errors.append(f"Cache-Control must be exactly 'no-store', got {cc}")
    if not (headers.get("Content-Type") or "").startswith("application/json"):
        errors.append(f"Content-Type {headers.get('Content-Type')!r}")
    try:
        body = json.loads(raw)
        if body.get("code") != code or not isinstance(body.get("message"), str):
            errors.append(f"body {body}")
    except ValueError:
        errors.append(f"body is not JSON: {raw[:80]!r}")
    if headers.get("Access-Control-Allow-Origin") not in (ORIGIN, "*"):
        errors.append(f"Access-Control-Allow-Origin {headers.get('Access-Control-Allow-Origin')!r}")
    expose = {x.strip().lower() for x in (headers.get("Access-Control-Expose-Headers") or "").split(",")}
    if "retry-after" not in expose:
        errors.append("Retry-After not in Access-Control-Expose-Headers")
    return errors


def paced(base, rps, seconds, make_request, workers=64):
    """Send rps requests/s for `seconds`; returns Counter of statuses and the first 429 and 503 responses."""
    counts = collections.Counter()
    first_429, first_503 = {}, {}
    lock = threading.Lock()
    interval = 1.0 / rps

    def one(i):
        status, headers, raw = make_request(i)
        with lock:
            counts[status] += 1
            if status == 429 and not first_429:
                first_429.update(status=status, headers=headers, raw=raw)
            if status == 503 and not first_503:
                first_503.update(status=status, headers=headers, raw=raw)

    start = time.monotonic()
    with ThreadPoolExecutor(max_workers=workers) as ex:
        for i in range(int(rps * seconds)):
            delay = start + i * interval - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            ex.submit(one, i)
    return counts, first_429, first_503, time.monotonic() - start


class Runner:
    def __init__(self, base, retry):
        self.base, self.retry, self.failed = base.rstrip("/"), retry, []

    def report(self, name, ok, detail):
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}")
        if not ok:
            self.failed.append(name)

    def burst(self, name, make_request, rps=100, seconds=10, allow_busy=False):
        counts, r429, r503, took = paced(self.base, rps, seconds, make_request)
        detail = f"{sum(counts.values())} requests in {took:.1f} s, statuses {dict(sorted(counts.items()))}"
        errs, seen = [], []
        if r429:
            seen.append("429")
            errs += [f"429: {e}" for e in check_429(r429["status"], r429["headers"], r429["raw"])]
        if r503 and allow_busy:  # SEC-2: the /v1/route concurrency cap may answer before the rate limit
            seen.append("503 RoutingBusy")
            errs += [f"503: {e}" for e in check_429(r503["status"], r503["headers"], r503["raw"], code="RoutingBusy")]
        if not seen:
            self.report(name, False, detail + (" -> no 429 or 503" if allow_busy else " -> no 429"))
        else:
            self.report(name, not errs, detail + (f" -> {' and '.join(seen)} conform" if not errs else f" -> errors: {errs}"))
        s, _, _ = request(self.base, "GET", "/health")
        self.report(f"{name}: /health right after the burst", s == 200, f"status {s}")
        time.sleep(self.retry)

    def normal(self):
        reqs = [lambda i: request(self.base, "POST", "/v1/route", ROUTE_BODY),
                lambda i: request(self.base, "GET", SEARCH),
                lambda i: request(self.base, "GET", REVERSE)]
        counts, _, _, took = paced(self.base, 10, 10, lambda i: reqs[i % 3](i), workers=16)
        self.report("normal session 10 requests/s x 10 s (route/search/reverse)", counts[429] == 0 and counts[503] == 0,
                    f"{sum(counts.values())} requests in {took:.1f} s, statuses {dict(sorted(counts.items()))}")
        time.sleep(self.retry)

    def ipv6_prefix(self):
        one = lambda i: request(self.base, "GET", SEARCH, headers={"X-Forwarded-For": f"2001:db8:4a:1:{i:x}::{i % 7 + 1:x}"})
        counts, r429, _, took = paced(self.base, 100, 3, one)
        errs = check_429(r429["status"], r429["headers"], r429["raw"]) if r429 else ["no 429"]
        self.report("ipv6-prefix: addresses rotating inside one /64 share one limit", not errs,
                    f"{sum(counts.values())} requests in {took:.1f} s, statuses {dict(sorted(counts.items()))}"
                    + (f" -> {errs}" if errs else " -> 429 conform"))
        time.sleep(max(self.retry, 3))
        many = lambda i: request(self.base, "GET", SEARCH, headers={"X-Forwarded-For": f"2001:db8:{i // 65536:x}:{i % 65536:x}::1"})
        counts, _, _, took = paced(self.base, 100, 3, many)
        self.report("ipv6-prefix: a different /64 per request is never limited", counts[429] == 0,
                    f"{sum(counts.values())} requests in {took:.1f} s, statuses {dict(sorted(counts.items()))}")
        time.sleep(self.retry)

    def tiles(self):
        def mixed(i):
            k = i % 10
            if k == 8:
                return request(self.base, "OPTIONS", "/v1/route", headers={"Access-Control-Request-Method": "POST"})
            if k == 9:
                return request(self.base, "GET", "/health")
            return request(self.base, "GET", "/tiles/basemap.pmtiles", headers={"Range": f"bytes={k * 16}-{k * 16 + 15}"})
        counts, _, _, took = paced(self.base, 200, 5, mixed)
        self.report("tiles ranges + OPTIONS + /health at 200 requests/s x 5 s", counts[429] == 0 and counts[0] == 0,
                    f"{sum(counts.values())} requests in {took:.1f} s, statuses {dict(sorted(counts.items()))}")
        time.sleep(self.retry)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--only", choices=["burst-route", "burst-search", "normal", "tiles", "ipv6-prefix"], action="append")
    ap.add_argument("--retry", type=float, default=2.0, help="pause between scenarios (s)")
    ap.add_argument("--insecure", action="store_true", help="skip TLS verification (local Caddy test with an internal CA only)")
    args = ap.parse_args()
    if args.insecure:
        global SSL_CONTEXT
        SSL_CONTEXT = ssl._create_unverified_context()
    r = Runner(args.base_url, args.retry)
    todo = args.only or ["normal", "tiles", "burst-route", "burst-search"]
    print(f"NAV-008 rate-limit check against {r.base}: {', '.join(todo)}")
    for t in todo:
        if t == "burst-route":
            r.burst("burst /v1/route 100 requests/s x 10 s", lambda i: request(r.base, "POST", "/v1/route", ROUTE_BODY),
                    allow_busy=True)
        elif t == "burst-search":
            r.burst("burst /v1/search 100 requests/s x 10 s", lambda i: request(r.base, "GET", SEARCH))
        elif t == "normal":
            r.normal()
        elif t == "tiles":
            r.tiles()
        elif t == "ipv6-prefix":
            r.ipv6_prefix()
    print(f"\n{len(todo)} scenario(s), {len(r.failed)} failed check(s)")
    sys.exit(1 if r.failed else 0)


if __name__ == "__main__":
    main()

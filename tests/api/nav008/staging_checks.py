#!/usr/bin/env python3
"""NAV-008 staging checks, run from OUTSIDE the staging host against BASE_URL (Python 3.9+, stdlib only).

Owner: qa-engineer. Story: docs/requirements/stories/NAV-008-backend-hosting-staging.md
Test plan: docs/qa/test-plans/NAV-008.md (every check id below is a test case id there).
Design: docs/architecture/deployment-staging.md sections 7, 7.1, 8. Contract: docs/architecture/api/openapi.yaml 0.4.0.

    python3 tests/api/nav008/staging_checks.py --base-url https://staging.<domain> --group cors \
        --origin https://demo-staging.<domain> --origin http://localhost:5173
    python3 tests/api/nav008/staging_checks.py --base-url https://staging.<domain> --group ratelimit
    python3 tests/api/nav008/staging_checks.py --base-url https://staging.<domain> --group log-markers

BASE_URL may also come from the environment or from tests/staging/nav008/staging.local.env (untracked).

Groups:
  health       AC 6 (machine part), AC 10 precondition: GET /health -> 200 {"status":"ok"}.
  cors         AC 11: preflights on every contract path for each allowlisted origin (ACAO = origin, exactly once,
               never '*', Vary: Origin, Expose-Headers has every openapi token); http://evil.example and look-alike
               origins get NO Access-Control-Allow-Origin on preflights and on real responses.
  ratelimit    AC 13: slow session (10 r/s per path group) never limited; tile ranges, /health and OPTIONS at high
               rate never limited; sustained 100 r/s for 10 s to /v1/route and to /v1/search gives >= 1 429 that
               matches openapi 0.4.0 RateLimited (JSON GatewayError code RateLimited, exactly one integer
               Retry-After >= 1, exactly one Cache-Control: no-store, each Access-Control-* once, ACAO = allowed
               origin and absent for evil, Retry-After in Expose-Headers); stack healthy afterwards.
               REFUSES to run against the shared dev gateway (localhost:8080) unless NAV008_ALLOW_BURST=1.
  log-markers  AC 14 helper: sends requests carrying unique marker coordinates and search text, and writes the
               needles for tests/staging/nav008/log-privacy-scan.sh (operator runs it on the host a day later).

Exit code 0 only if no check failed. Every FAIL prints expected and actual. No client IP is ever printed.
"""
import argparse
import json
import os
import random
import re
import statistics
import sys
import threading
import time
import urllib.parse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "nav001"))
from lib import EVIL_ORIGIN, ORIGIN, P, Client, Report  # noqa: E402

SPEC = os.path.normpath(os.path.join(HERE, "..", "..", "..", "docs", "architecture", "api", "openapi.yaml"))
LOCAL_ENV = os.path.normpath(os.path.join(HERE, "..", "..", "staging", "nav008", "staging.local.env"))
FALLBACK_PATHS = ["/health", "/tiles/basemap.pmtiles", "/v1/route", "/v1/search", "/v1/reverse"]
FALLBACK_EXPOSE = ["Content-Range", "Content-Length", "ETag", "Accept-Ranges", "Retry-After"]
RETRY_AFTER_RE = re.compile(r"^[1-9][0-9]*$")  # RFC 9110 delay-seconds, >= 1 (openapi RetryAfter: integer, minimum 1)
GATEWAY_ERROR_CODES = {"NotFound", "MethodNotAllowed", "PayloadTooLarge", "RangeNotSatisfiable", "RateLimited",
                       "UpstreamUnavailable", "UpstreamTimeout"}


# ------------------------------------------------------------------------------------ spec helpers (no YAML lib)
def spec_text(path=SPEC):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return ""


def spec_paths(text):
    """Top-level keys under `paths:` (2-space indent), e.g. /health."""
    m = re.search(r"^paths:\n(.*?)^\S", text, re.S | re.M)
    found = re.findall(r"^  (/[^:\s]*):\s*$", m.group(1), re.M) if m else []
    return found or FALLBACK_PATHS


def spec_expose_tokens(text):
    """Tokens of components.headers.AccessControlExposeHeaders `example` (the list openapi says is always included)."""
    m = re.search(r"^    AccessControlExposeHeaders:\n(?:      .*\n)*?.*example:\s*\"([^\"]+)\"", text, re.M)
    toks = [t.strip() for t in m.group(1).split(",")] if m else FALLBACK_EXPOSE
    return [t for t in toks if t]


def spec_version(text):
    m = re.search(r"^  version:\s*(\S+)", text, re.M)
    return m.group(1) if m else "?"


def tokens(v):
    return {t.strip().lower() for t in (v or "").split(",") if t.strip()}


def values(resp, name):
    return [v for k, v in resp.raw_headers if k.lower() == name.lower()]


def load_local_env():
    """KEY=VALUE lines from the untracked staging.local.env; the environment wins."""
    env = {}
    if os.path.exists(LOCAL_ENV):
        with open(LOCAL_ENV, encoding="utf-8") as f:
            for ln in f:
                ln = ln.strip()
                if ln and not ln.startswith("#") and "=" in ln:
                    k, v = ln.split("=", 1)
                    env[k.strip()] = v.strip().strip('"').strip("'")
    env.update({k: v for k, v in os.environ.items() if k in ("BASE_URL", "ALLOWED_ORIGINS", "STAGING_HOST")})
    return env


def is_shared_dev(base_url):
    u = urllib.parse.urlparse(base_url)
    return u.hostname in ("localhost", "127.0.0.1", "::1") and (u.port or (443 if u.scheme == "https" else 80)) == 8080


# ------------------------------------------------------------------------------------ 429 shape (stdlib)
def rate_limited_errors(resp, origin_allowed, origin, expose_tokens, allow_star=False):
    """Every deviation of `resp` from openapi 0.4.0 components/responses/RateLimited. Empty list = conforms."""
    errs = []
    if resp.status != 429:
        return [f"status {resp.status}, expected 429"]
    ctype = values(resp, "content-type")
    if len(ctype) != 1 or not ctype[0].lower().startswith("application/json"):
        errs.append(f"Content-Type {ctype}, expected one application/json")
    body = resp.json()
    if not isinstance(body, dict):
        errs.append(f"body not a JSON object: {resp.body[:60]!r}")
    else:
        if body.get("code") != "RateLimited":
            errs.append(f"body.code {body.get('code')!r}, expected 'RateLimited'")
        if not isinstance(body.get("message"), str):
            errs.append("body.message missing or not a string")
        if body.get("code") not in GATEWAY_ERROR_CODES:
            errs.append("body.code not in GatewayError enum")
    ra = values(resp, "retry-after")
    if len(ra) != 1:
        errs.append(f"Retry-After: expected exactly 1 header, got {ra}")
    elif not RETRY_AFTER_RE.match(ra[0].strip()):
        errs.append(f"Retry-After {ra[0]!r} is not an integer >= 1 (delay-seconds)")
    cc = values(resp, "cache-control")
    if cc != ["no-store"]:
        errs.append(f"Cache-Control {cc}, expected exactly ['no-store']")
    repeated = resp.repeated_headers()
    if repeated:
        errs.append(f"repeated Access-Control-* headers {repeated}")
    acao = values(resp, "access-control-allow-origin")
    if origin_allowed:
        if acao != [origin] and not (allow_star and acao == ["*"]):
            errs.append(f"Access-Control-Allow-Origin {acao}, expected exactly [{origin!r}]")
        exp = values(resp, "access-control-expose-headers")
        if len(exp) != 1:
            errs.append(f"Access-Control-Expose-Headers: expected exactly 1 header, got {exp}")
        else:
            missing = [t for t in expose_tokens if t.lower() not in tokens(exp[0])]
            if missing:
                errs.append(f"Access-Control-Expose-Headers lacks {missing}")
    elif acao:
        errs.append(f"disallowed origin {origin!r} got Access-Control-Allow-Origin {acao}")
    return errs


# ------------------------------------------------------------------------------------ load generator
class Fired:
    def __init__(self):
        self.lock = threading.Lock()
        self.items = []  # (t_start, tag, resp)

    def add(self, item):
        with self.lock:
            self.items.append(item)

    def statuses(self, tag=None):
        return Counter(r.status for _t, g, r in self.items if tag is None or g == tag)

    def achieved_rate(self):
        ts = sorted(t for t, _g, _r in self.items)
        return (len(ts) - 1) / (ts[-1] - ts[0]) if len(ts) > 1 and ts[-1] > ts[0] else float("inf")


def fire(make, n, rate=None, workers=64, stop_on_429=False):
    """Send n requests built by make(i) -> (tag, fn). Paced at `rate` r/s if given, else as fast as `workers` allow.
    The start time of every request is recorded inside the worker, so a starved pool shows as a lower rate."""
    out, stop = Fired(), threading.Event()

    def run(i):
        if stop.is_set():
            return
        tag, fn = make(i)
        t = time.perf_counter()
        r = fn()
        out.add((t, tag, r))
        if stop_on_429 and r.status == 429:
            stop.set()

    with ThreadPoolExecutor(max_workers=workers) as ex:
        t0 = time.perf_counter() + 0.05
        for i in range(n):
            if stop.is_set():
                break
            if rate:
                d = t0 + i / rate - time.perf_counter()
                if d > 0:
                    time.sleep(d)
            ex.submit(run, i)
    return out


def provoke_429(client, kind, origin, max_requests=400, workers=48):
    """Burst until the first 429 (or max_requests). kind: route | search | reverse. Returns (resp429 or None, Fired)."""
    make = request_maker(client, kind, origin)
    f = fire(make, max_requests, rate=None, workers=workers, stop_on_429=True)
    hits = [r for _t, _g, r in f.items if r.status == 429]
    return (hits[0] if hits else None), f


def request_maker(client, kind, origin, evil_every=0):
    body = client.route_body("P1", "P2")
    s_params = {"q": "Сүхбаатар", "lat": P["P1"][0], "lon": P["P1"][1], "lang": "mn", "limit": 5}
    r_params = {"lat": P["P1"][0], "lon": P["P1"][1]}

    def make(i):
        tag, o = ("evil", EVIL_ORIGIN) if evil_every and i % evil_every == evil_every - 1 else ("allowed", origin)
        h = {"Origin": o}
        if kind == "route":
            return tag, lambda: client.request("POST", "/v1/route", body, headers=h)
        if kind == "search":
            return tag, lambda: client.get("/v1/search", s_params, headers=h)
        if kind == "reverse":
            return tag, lambda: client.get("/v1/reverse", r_params, headers=h)
        raise ValueError(kind)
    return make


# ------------------------------------------------------------------------------------ checks
class Nav008:
    def __init__(self, a, report):
        self.a = a
        self.c = Client(a.base_url, timeout=a.timeout)
        self.r = report
        self.spec = spec_text()
        self.paths = spec_paths(self.spec)
        self.expose = spec_expose_tokens(self.spec)
        self.origins = a.origins
        self.origin = a.origins[0]
        self.staging = not a.allow_star
        # --upstream-less: isolated gateway without Valhalla/Photon; a request that passes the limiter gets 502/503/504.
        self.ok_api = {200, 502, 503, 504} if a.upstream_less else {200}

    # --------------------------------------------------------------- health
    def health(self, cid="H01"):
        r = self.c.get("/health", timeout=10)
        ok = r.status == 200 and (r.json() or {}).get("status") == "ok"
        return self.r.check(f"{cid}.health_200_ok", ok, '200 {"status":"ok"}', f"{r.status} {r.body[:80]!r} {r.error or ''}")

    # --------------------------------------------------------------- AC 11
    def _preflight(self, path, origin):
        method = "POST" if path == "/v1/route" else "GET"
        return self.c.request("OPTIONS", path, headers={"Origin": origin, "Access-Control-Request-Method": method,
                                                        "Access-Control-Request-Headers": "Range, Content-Type"})

    def _real(self, path, origin):
        h = {"Origin": origin}
        if path == "/v1/route":
            return self.c.request("POST", "/v1/route", self.c.route_body("P1", "P2"), headers=h)
        if path == "/v1/search":
            return self.c.get(path, {"q": "Сүхб", "lang": "mn"}, headers=h)
        if path == "/v1/reverse":
            return self.c.get(path, {"lat": P["P1"][0], "lon": P["P1"][1]}, headers=h)
        if path == "/tiles/basemap.pmtiles":
            return self.c.request("GET", path, headers={**h, "Range": "bytes=0-126"})
        return self.c.request("GET", path, headers=h)

    def cors(self):
        self.r.note("CORS.paths_from_spec", ", ".join(self.paths))
        self.r.note("CORS.expose_tokens_from_spec", f"{', '.join(self.expose)} (openapi {spec_version(self.spec)})")
        if "*" in self.origins:
            self.r.check("CORS00.allowlist_has_no_star", False, "allowlist without '*' (AC 11)", self.origins)
        for o in self.origins:
            tag = urllib.parse.urlparse(o).netloc or o
            for path in self.paths:
                r = self._preflight(path, o)
                acao = values(r, "access-control-allow-origin")
                self.r.check(f"CORS01.preflight_204.{tag}{path}", r.status == 204, 204, f"{r.status} {r.error or ''}")
                self.r.check(f"CORS02.preflight_acao_is_origin.{tag}{path}", acao == [o], f"exactly [{o!r}]", acao)
                self.r.check(f"CORS03.preflight_vary_origin.{tag}{path}", "origin" in tokens(", ".join(values(r, "vary"))),
                             "Vary contains Origin", values(r, "vary"))
                meth, hdrs = tokens(r.header("access-control-allow-methods")), tokens(r.header("access-control-allow-headers"))
                self.r.check(f"CORS04.preflight_methods_headers.{tag}{path}",
                             {"get", "post", "options"} <= meth and {"range", "content-type"} <= hdrs,
                             "Allow-Methods >= GET, POST, OPTIONS and Allow-Headers >= Range, Content-Type",
                             f"{r.header('access-control-allow-methods')} | {r.header('access-control-allow-headers')}")
                exp = values(r, "access-control-expose-headers")
                missing = [t for t in self.expose if t.lower() not in tokens(exp[0] if exp else "")]
                self.r.check(f"CORS05.preflight_expose_all_spec_tokens.{tag}{path}", len(exp) == 1 and not missing,
                             f"one header listing {self.expose}", f"{exp} missing {missing}")
                self.r.check(f"CORS06.preflight_no_repeated_ac.{tag}{path}", not r.repeated_headers(),
                             "each Access-Control-* at most once", r.repeated_headers())
                rr = self._real(path, o)
                acao = values(rr, "access-control-allow-origin")
                self.r.check(f"CORS07.response_acao_is_origin.{tag}{path}", acao == [o] and not rr.repeated_headers(),
                             f"exactly [{o!r}], no repeated Access-Control-*", f"{rr.status} {acao} rep={rr.repeated_headers()}")
        for path in self.paths:
            for kind, r in (("preflight", self._preflight(path, EVIL_ORIGIN)), ("response", self._real(path, EVIL_ORIGIN))):
                acao = values(r, "access-control-allow-origin")
                self.r.check(f"CORS08.evil_{kind}_no_acao{path}", not acao, "no Access-Control-Allow-Origin",
                             f"{r.status} {acao}")
        looks = ["null", "http://evil.example.localhost:5173"]
        for o in self.origins:
            u = urllib.parse.urlparse(o)
            flipped = ("http" if u.scheme == "https" else "https") + "://" + u.netloc
            looks += [o + ".evil.example", flipped, f"{u.scheme}://x{u.netloc}", o + "0" if u.port else o + ":8443"]
        for lo in dict.fromkeys(looks):
            if lo in self.origins:
                continue
            r = self._preflight("/v1/route", lo)
            acao = values(r, "access-control-allow-origin")
            self.r.check(f"CORS09.lookalike_no_acao[{lo}]", not acao, "no Access-Control-Allow-Origin", acao)
        r = self.c.get("/health")
        acao = values(r, "access-control-allow-origin")
        self.r.check("CORS10.no_origin_never_star", not (self.staging and "*" in acao), "no '*' on staging", acao)
        cred = r.header("access-control-allow-credentials")
        if cred:
            self.r.note("CORS11.allow_credentials_present", f"{cred!r} (not in the contract; review with architect)")

    # --------------------------------------------------------------- AC 13
    def _guard(self):
        if is_shared_dev(self.a.base_url) and os.environ.get("NAV008_ALLOW_BURST") != "1":
            self.r.skip("RL00.guard", f"{self.a.base_url} is the shared dev gateway (NAV-003 runs on it). Bursts refused. "
                                      "Use staging, the mock (mock_gateway.py) or an isolated gateway on another port")
            return False
        return True

    def _unexpected(self, fired, allowed):
        bad = Counter({s: n for s, n in fired.statuses().items() if s not in allowed})
        return dict(bad)

    def _slow(self, cid, kind_cycle, seconds):
        rate = self.a.slow_rate
        n = int(rate * seconds)
        makers = [request_maker(self.c, k, self.origin) for k in kind_cycle]
        f = fire(lambda i: makers[i % len(makers)](i), n, rate=rate, workers=16)
        st = f.statuses()
        self.r.check(f"{cid}.never_429", st.get(429, 0) == 0, f"0 x 429 in {n} requests at {rate:g} r/s", dict(st))
        self.r.check(f"{cid}.all_accepted", sum(st.get(x, 0) for x in self.ok_api) == n, f"{n} x {sorted(self.ok_api)}", dict(st))
        self.r.note(f"{cid}.achieved_rate", f"{f.achieved_rate():.1f} r/s (target {rate:g}, {'+'.join(kind_cycle)})")

    def _blast(self, cid, make, n, ok_status, workers=64):
        f = fire(make, n, rate=None, workers=workers)
        st = f.statuses()
        self.r.check(f"{cid}.never_429", st.get(429, 0) == 0, f"0 x 429 in {n} requests", dict(st))
        self.r.check(f"{cid}.all_{ok_status}", st.get(ok_status, 0) == n, f"{n} x {ok_status}", dict(st))
        self.r.note(f"{cid}.achieved_rate", f"{f.achieved_rate():.0f} r/s with {workers} workers")

    def _burst(self, cid, kind):
        rate, secs = self.a.burst_rate, self.a.burst_seconds
        n = int(rate * secs)
        f = fire(request_maker(self.c, kind, self.origin, evil_every=10), n, rate=rate, workers=self.a.workers)
        st = f.statuses()
        achieved = f.achieved_rate()
        self.r.check(f"{cid}.rate_achieved", achieved >= 0.9 * rate,
                     f">= {0.9 * rate:.0f} r/s sent (AC 13: {rate:g} r/s for {secs:g} s)",
                     f"{achieved:.1f} r/s; raise --workers if the client was the bottleneck")
        self.r.check(f"{cid}.at_least_one_429", st.get(429, 0) >= 1, ">= 1 x 429", dict(st))
        bad = self._unexpected(f, self.ok_api | {429})
        self.r.check(f"{cid}.only_accepted_or_429", not bad, f"every response in {sorted(self.ok_api | {429})} (stack healthy under burst)", bad)
        accepted = sum(st.get(x, 0) for x in self.ok_api)
        self.r.note(f"{cid}.accepted_vs_design", f"{accepted} accepted of {n}; design estimate 60 + 30 x {secs:g} = {60 + 30 * secs:.0f}")
        ok429 = [r for _t, g, r in f.items if g == "allowed" and r.status == 429]
        evil429 = [r for _t, g, r in f.items if g == "evil" and r.status == 429]
        if ok429:
            errs = rate_limited_errors(ok429[0], True, self.origin, self.expose, allow_star=not self.staging)
            self.r.check(f"{cid}.first_429_matches_RateLimited", not errs, "openapi 0.4.0 RateLimited", "; ".join(errs))
            sigs = Counter("; ".join(rate_limited_errors(x, True, self.origin, self.expose, not self.staging)) or "ok" for x in ok429)
            self.r.check(f"{cid}.all_429_match_RateLimited", set(sigs) == {"ok"}, f"all {len(ok429)} conform", dict(sigs))
            ra = values(ok429[0], "retry-after")
            self.r.note(f"{cid}.retry_after", ra[0] if ra else None)
            ms = statistics.median(x.elapsed for x in ok429) * 1000
            ms200 = [r.elapsed for _t, _g, r in f.items if r.status in self.ok_api]
            self.r.note(f"{cid}.median_ms_429_vs_200", f"{ms:.0f} ms vs {statistics.median(ms200) * 1000:.0f} ms" if ms200 else f"{ms:.0f} ms")
        else:
            self.r.skip(f"{cid}.first_429_matches_RateLimited", "no 429 for the allowed origin")
        if evil429:
            sigs = Counter("; ".join(rate_limited_errors(x, False, EVIL_ORIGIN, self.expose)) or "ok" for x in evil429)
            self.r.check(f"{cid}.evil_429_no_acao", set(sigs) == {"ok"}, "429 for http://evil.example conforms and has no ACAO", dict(sigs))
        else:
            self.r.note(f"{cid}.evil_429_no_acao", "no 429 among the evil-origin requests (every 10th request)")
        return f, ok429

    def _after_burst(self, cid):
        """Right after a burst (bucket empty): never-limited paths still answer."""
        h = self.c.get("/health", headers={"Origin": self.origin})
        self.r.check(f"{cid}.health_during_limit", h.status == 200, 200, h.status)
        t = self.c.request("GET", "/tiles/basemap.pmtiles", headers={"Origin": self.origin, "Range": "bytes=0-1023"})
        self.r.check(f"{cid}.tiles_during_limit", t.status == 206, 206, t.status)
        o = self._preflight("/v1/route", self.origin)
        self.r.check(f"{cid}.preflight_during_limit", o.status == 204, 204, o.status)

    def _recover(self, cid, kind, ok429):
        ra = 1
        if ok429:
            v = values(ok429[-1], "retry-after")
            ra = int(v[0]) if v and v[0].strip().isdigit() else 1
        time.sleep(min(ra, 30) + self.a.cooldown)
        mk = request_maker(self.c, kind, self.origin)
        r = mk(0)[1]()
        self.r.check(f"{cid}.recovers_after_retry_after", r.status in self.ok_api,
                     f"{sorted(self.ok_api)} after Retry-After ({ra} s) + {self.a.cooldown:g} s", r.status)

    def ratelimit(self):
        if not self._guard():
            return
        self.r.note("RL.params", f"burst {self.a.burst_rate:g} r/s x {self.a.burst_seconds:g} s, slow {self.a.slow_rate:g} r/s x "
                                 f"{self.a.slow_seconds:g} s, workers {self.a.workers}, origin {self.origin}")
        if not self.health("RL01"):
            return
        # 1. Normal sessions first, on fresh buckets (AC 13: <= 10 r/s never limited), per path group.
        self._slow("RL02.slow_route", ["route"], self.a.slow_seconds)
        self._slow("RL03.slow_search_reverse", ["search", "reverse"], self.a.slow_seconds)
        # 2. Paths that are never limited, at a high rate.
        head = self.c.request("HEAD", "/tiles/basemap.pmtiles")
        size = int(head.header("content-length") or 0)
        if size <= 0:
            self.r.check("RL04.tiles_size", False, "Content-Length from HEAD", f"{head.status} {head.header('content-length')}")
        else:
            rnd = random.Random(8)

            def tile(i):
                s = rnd.randrange(0, max(1, size - 2048))
                return "tiles", lambda: self.c.request("GET", "/tiles/basemap.pmtiles",
                                                       headers={"Origin": self.origin, "Range": f"bytes={s}-{s + 2047}"})
            self._blast("RL04.tiles_ranges", tile, self.a.tile_requests, 206)
        self._blast("RL05.health", lambda i: ("h", lambda: self.c.get("/health")), self.a.misc_requests, 200)
        self._blast("RL06.preflights", lambda i: ("o", lambda: self._preflight(self.paths[i % len(self.paths)], self.origin)),
                    self.a.misc_requests, 204)
        # 3. Bursts (AC 13 procedure), each followed by the never-limited probes and a recovery check.
        _f, ok429 = self._burst("RL07.burst_route", "route")
        self._after_burst("RL08.route_limited")
        self._recover("RL09.route", "route", ok429)
        time.sleep(self.a.cooldown)
        _f, ok429 = self._burst("RL10.burst_search", "search")
        self._after_burst("RL11.search_limited")
        rev = request_maker(self.c, "reverse", self.origin)(0)[1]()
        self.r.note("RL12.reverse_right_after_search_burst",
                    f"HTTP {rev.status} (shared zone per design; 429 only if the bucket has not refilled yet, so timing-dependent; not an AC)")
        self._recover("RL13.search", "search", ok429)
        # 4. Stack healthy afterwards.
        time.sleep(self.a.cooldown)
        self.health("RL14")
        if self.a.upstream_less:
            return self.r.skip("RL14.route_search_ok_after", "--upstream-less (no Valhalla/Photon behind this gateway)")
        r = self.c.route("P1", "P2")
        self.r.check("RL14.route_ok_after", r.status == 200 and (r.json() or {}).get("code") == "Ok", '200 code "Ok"',
                     f"{r.status} {r.body[:80]!r}")
        s = self.c.get("/v1/search", {"q": "Сүхбаатар", "lang": "mn"})
        self.r.check("RL14.search_ok_after", s.status == 200 and bool((s.json() or {}).get("features")), "200 with features",
                     f"{s.status} {s.body[:80]!r}")

    # --------------------------------------------------------------- AC 14 helper
    def log_markers(self):
        rnd = random.SystemRandom()
        lat = round(47.90 + rnd.random() * 0.03, 6)
        lon = round(106.88 + rnd.random() * 0.05, 6)
        tag = "".join(rnd.choice("бвгджзклмнпрстфхцчшщ") for _ in range(6))
        text_mn = f"Маркер{tag}"
        text_lat = f"nav008marker{rnd.randrange(10 ** 5, 10 ** 6)}"
        c = self.c
        sent = [
            c.route((lat, lon), "P2"),
            c.request("GET", "/v1/route?json=" + urllib.parse.quote(json.dumps(c.route_body((lat, lon), "P2")))),
            c.get("/v1/search", {"q": text_mn, "lat": lat, "lon": lon}),
            c.get("/v1/search", {"q": text_lat}),
            c.get("/v1/reverse", {"lat": lat, "lon": lon}),
            c.request("POST", "/v1/route", {"costing": "auto", "locations": [{"lat": lat, "lon": lon}]}),  # error path
            c.get("/nav008-unknown-path", {"lat": lat, "q": text_lat}),  # gateway 404 path with a query string
        ]
        self.r.note("LM.sent", ", ".join(str(x.status) for x in sent))
        needles = [f"{lat:.6f}", f"{lon:.6f}", f"{lat:.4f}", f"{lon:.4f}", text_mn, urllib.parse.quote(text_mn), text_lat]
        os.makedirs(self.a.out_dir, exist_ok=True)
        fn = os.path.join(self.a.out_dir, time.strftime("log-markers-%Y%m%dT%H%M%SZ.txt", time.gmtime()))
        with open(fn, "w", encoding="utf-8") as f:
            f.write(f"# NAV-008 AC 14 markers sent {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())} (no personal data)\n")
            f.write("\n".join(needles) + "\n")
        self.r.note("LM.needles_file", f"{fn}  (give this file to the operator for log-privacy-scan.sh --markers)")
        self.r.check("LM.requests_answered", all(x.status for x in sent), "every marker request got an HTTP answer",
                     [x.status for x in sent])

    def run(self, groups):
        for g in groups:
            {"health": self.health, "cors": self.cors, "ratelimit": self.ratelimit, "log-markers": self.log_markers}[g]()


def main():
    env = load_local_env()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default=env.get("BASE_URL") or (f"https://{env['STAGING_HOST']}" if env.get("STAGING_HOST") else None))
    ap.add_argument("--group", action="append", choices=["health", "cors", "ratelimit", "log-markers"])
    ap.add_argument("--origin", action="append", dest="origins",
                    help="allowlisted origin (repeat). Default: ALLOWED_ORIGINS (comma list) or http://localhost:5173")
    ap.add_argument("--allow-star", action="store_true", help="dev/mock only: accept ACAO '*' where the origin is allowed")
    ap.add_argument("--burst-rate", type=float, default=100.0)
    ap.add_argument("--burst-seconds", type=float, default=10.0)
    ap.add_argument("--slow-rate", type=float, default=10.0)
    ap.add_argument("--slow-seconds", type=float, default=20.0)
    ap.add_argument("--tile-requests", type=int, default=1500)
    ap.add_argument("--misc-requests", type=int, default=300)
    ap.add_argument("--workers", type=int, default=96)
    ap.add_argument("--cooldown", type=float, default=3.0, help="seconds between phases (bucket drains 60 at 30 r/s in 2 s)")
    ap.add_argument("--timeout", type=float, default=15.0)
    ap.add_argument("--out-dir", default=os.path.normpath(os.path.join(HERE, "..", "..", "staging", "nav008", "results")))
    ap.add_argument("--upstream-less", action="store_true",
                    help="isolated gateway without Valhalla/Photon (isolated-gateway-rl.sh): 502/503/504 count as 'not limited'")
    ap.add_argument("--json-out")
    a = ap.parse_args()
    if not a.base_url:
        ap.error("no BASE_URL: pass --base-url, set BASE_URL, or put it in tests/staging/nav008/staging.local.env")
    a.origins = a.origins or [o.strip() for o in (env.get("ALLOWED_ORIGINS") or ORIGIN).split(",") if o.strip()]
    groups = a.group or ["health"]
    rep = Report()
    print(f"NAV-008 staging checks: base={a.base_url} groups={','.join(groups)} origins={a.origins}")
    if a.base_url.startswith("http://") and not a.allow_star and not a.base_url.startswith(("http://127.", "http://localhost")):
        rep.note("PRE.scheme", "BASE_URL is http://; staging must be https:// (AC 6, AC 7)")
    try:
        Nav008(a, rep).run(groups)
    except Exception as e:  # noqa: BLE001  never exit 0 on a crash
        rep.check("PRE.suite_crashed", False, "no exception", f"{type(e).__name__}: {e}")
    print("\n" + rep.summary())
    if rep.failed:
        print("Failed checks:\n  " + "\n  ".join(f"{cid}: {d}" for _s, cid, d in rep.failed))
    if a.json_out:
        with open(a.json_out, "w", encoding="utf-8") as f:
            json.dump({"base_url": a.base_url, "groups": groups, "summary": rep.summary(), "info": rep.info,
                       "results": [{"status": s, "id": c, "detail": d} for s, c, d in rep.results]}, f, ensure_ascii=False, indent=1)
    sys.exit(1 if rep.failed else 0)


if __name__ == "__main__":
    main()

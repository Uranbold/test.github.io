#!/usr/bin/env python3
"""NAV-008 contract checks for openapi.yaml 0.4.0 additions, runnable against ANY BASE_URL.

Owner: qa-engineer. Test plan: docs/qa/test-plans/NAV-008.md (CT8-*). Fixes-round minor (QA, 2026-09-30):
  * CT8-E  Access-Control-Expose-Headers contains EVERY token openapi lists (components.headers.
           AccessControlExposeHeaders), on every response whose definition documents that header:
           CorsPreflight on every path, tiles GET 206, tiles HEAD 200, tiles GET/HEAD 416, and 429 RateLimited.
           Responses that do not document the header but carry it are reported as INFO.
  * CT8-R  429 RateLimited on postRoute, search (and reverse, which shares the search zone): status documented,
           body validates against GatewayError, Retry-After exactly once and valid against the RetryAfter schema
           (integer >= 1, delay-seconds only), Cache-Control exactly `no-store`, each Access-Control-* once,
           ACAO = the allowed origin. SKIPPED (not failed) when no 429 can be provoked within the request budget,
           e.g. on the dev stack where limits are off. REFUSES to burst the shared dev gateway (localhost:8080,
           NAV-003 uses it) unless NAV008_ALLOW_BURST=1; the CT8-E part still runs there.
  * CT8-S  the spec itself: version >= 0.4.0, 429 documented on the four operations, Retry-After in the token list.

    tests/.venv/bin/python tests/api/nav008/contract_nav008.py --base-url https://staging.<domain> [--origin O]
    tests/.venv/bin/python tests/api/nav008/contract_nav008.py --base-url http://localhost:8080 --no-429

Needs tests/api/requirements.txt (jsonschema, PyYAML, openapi-spec-validator). Exit 0 only if nothing failed.
"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "nav001"))
sys.path.insert(0, HERE)
from contract import DEFAULT_SPEC, Contract  # noqa: E402
from lib import ORIGIN, Client, Report  # noqa: E402
from staging_checks import is_shared_dev, load_local_env, provoke_429, tokens, values  # noqa: E402

RL_OPS = [("/v1/route", "post"), ("/v1/route", "get"), ("/v1/search", "get"), ("/v1/reverse", "get")]


class Contract8(Contract):
    def __init__(self, spec_path, base_url, report, origin, allow_star):
        super().__init__(spec_path, base_url, report)
        self.origin, self.allow_star = origin, allow_star
        hdr = self.deref(self.spec["components"]["headers"]["AccessControlExposeHeaders"])
        self.expose = [t.strip() for t in str(hdr.get("schema", {}).get("example", "")).split(",") if t.strip()]
        self.expose_desc = hdr.get("description", "")

    # ------------------------------------------------------------------ helpers
    def documents_expose(self, rd):
        return "Access-Control-Expose-Headers" in (rd or {}).get("headers", {})

    def expose_check(self, cid, resp, rd, strict=True):
        exp = values(resp, "access-control-expose-headers")
        if not self.documents_expose(rd) and strict:
            return self.r.check(cid, False, "response definition documents Access-Control-Expose-Headers", "not documented")
        missing = [t for t in self.expose if t.lower() not in tokens(exp[0] if exp else "")]
        ok = len(exp) == 1 and not missing
        if strict:
            return self.r.check(cid, ok, f"exactly one header containing {self.expose}", f"{resp.status} {exp} missing {missing}")
        if exp:
            self.r.note(cid, "all spec tokens" if ok else f"{resp.status}: {exp} missing {missing} (header not documented here; INFO)")
        return True

    # ------------------------------------------------------------------ CT8-S
    def spec_checks(self):
        v = tuple(int(x) for x in str(self.spec["info"]["version"]).split(".")[:3])
        self.r.check("CT8-S01.spec_version_ge_0_4_0", v >= (0, 4, 0), ">= 0.4.0", self.spec["info"]["version"])
        for path, m in RL_OPS:
            rd = self.response_def(path, m, 429)
            ok = rd is not None and "RateLimited" in str(rd.get("content", {}).get("application/json", {}).get("example", ""))
            self.r.check(f"CT8-S02.429_documented.{m}{path}", ok, "429 -> components/responses/RateLimited", bool(rd))
        self.r.check("CT8-S03.expose_tokens_include_retry_after", "retry-after" in {t.lower() for t in self.expose},
                     "Retry-After in AccessControlExposeHeaders example", self.expose)
        missing_in_desc = [t for t in self.expose if t not in self.expose_desc]
        self.r.check("CT8-S04.expose_example_matches_description", not missing_in_desc,
                     "every example token also named in the description", missing_in_desc)
        ra = self.deref(self.spec["components"]["headers"]["RetryAfter"])
        self.r.check("CT8-S05.retry_after_schema", ra.get("required") is True and ra.get("schema", {}).get("type") == "integer"
                     and ra.get("schema", {}).get("minimum") == 1, "required, integer, minimum 1", ra.get("schema"))

    # ------------------------------------------------------------------ CT8-E
    def expose_checks(self):
        c, o = self.c, self.origin
        pre = {"Origin": o, "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "Range, Content-Type"}
        for path, item in self.spec["paths"].items():
            if "options" not in item:
                continue
            resp = c.request("OPTIONS", path, headers=pre)
            rd = self.response_def(path, "options", resp.status)
            self.case(f"CT8-E01.preflight_conforms{path}", path, "options", resp)
            self.expose_check(f"CT8-E02.preflight_expose{path}", resp, rd)
        head = c.request("HEAD", "/tiles/basemap.pmtiles", headers={"Origin": o})
        self.expose_check("CT8-E03.tiles_head_200_expose", head, self.response_def("/tiles/basemap.pmtiles", "head", head.status))
        rng = c.request("GET", "/tiles/basemap.pmtiles", headers={"Origin": o, "Range": "bytes=0-126"})
        self.expose_check("CT8-E04.tiles_get_206_expose", rng, self.response_def("/tiles/basemap.pmtiles", "get", rng.status))
        size = int(head.header("content-length") or 0)
        for m in ("get", "head"):
            r416 = c.request(m.upper(), "/tiles/basemap.pmtiles", headers={"Origin": o, "Range": f"bytes={size}-"})
            self.expose_check(f"CT8-E05.tiles_{m}_416_expose", r416, self.response_def("/tiles/basemap.pmtiles", m, r416.status))
        # Not documented on these responses; INFO only if the header is present with fewer tokens.
        for cid, path, m, resp in (
            ("CT8-E06.route_200_info", "/v1/route", "post", c.request("POST", "/v1/route", c.route_body("P1", "P2"), headers={"Origin": o})),
            ("CT8-E06.search_200_info", "/v1/search", "get", c.get("/v1/search", {"q": "Сүхб", "lang": "mn"}, headers={"Origin": o})),
            ("CT8-E06.health_200_info", "/health", "get", c.get("/health", headers={"Origin": o})),
            ("CT8-E06.unknown_404_info", "/health", "get", c.get("/nav008-nope", headers={"Origin": o})),
        ):
            self.expose_check(cid, resp, None, strict=False)

    # ------------------------------------------------------------------ CT8-R
    def ratelimit_cases(self, budget, workers):
        if is_shared_dev(self.c.base) and os.environ.get("NAV008_ALLOW_BURST") != "1":
            self.r.skip("CT8-R.all", "shared dev gateway (NAV-003 runs on it): no burst sent. Set NAV008_ALLOW_BURST=1 only on a private stack")
            return
        rd429 = self.deref({"$ref": "#/components/responses/RateLimited"})
        ra_schema = self.deref(self.spec["components"]["headers"]["RetryAfter"])["schema"]
        for kind, path, method in (("route", "/v1/route", "post"), ("search", "/v1/search", "get")):
            resp, fired = provoke_429(self.c, kind, self.origin, max_requests=budget, workers=workers)
            if resp is None:
                self.r.skip(f"CT8-R01.{kind}_429", f"no 429 within {len(fired.items)} requests (statuses {dict(fired.statuses())}); "
                                                   "limits off or higher than the budget")
                continue
            self.r.note(f"CT8-R01.{kind}_429_after", f"{len(fired.items)} requests")
            self.case(f"CT8-R02.{kind}_429_conforms", path, method, resp, required_headers=("retry-after", "cache-control"))
            ra = values(resp, "retry-after")
            ok = len(ra) == 1 and ra[0].strip().isdigit() and not list(self.validator(ra_schema).iter_errors(int(ra[0].strip())))
            self.r.check(f"CT8-R03.{kind}_retry_after_integer_ge_1", ok, "one Retry-After, integer >= 1 per RetryAfter schema", ra)
            self.r.check(f"CT8-R04.{kind}_429_code", (resp.json() or {}).get("code") == "RateLimited", "code RateLimited", resp.body[:80])
            ct = values(resp, "content-type")
            self.r.check(f"CT8-R05.{kind}_429_json", len(ct) == 1 and ct[0].lower().startswith("application/json"),
                         "one Content-Type application/json", ct)
            acao = values(resp, "access-control-allow-origin")
            self.r.check(f"CT8-R06.{kind}_429_acao", acao == [self.origin] or (self.allow_star and acao == ["*"]),
                         f"exactly [{self.origin!r}]", acao)
            self.expose_check(f"CT8-R07.{kind}_429_expose", resp, rd429)
            if kind == "search":
                rev = self.c.get("/v1/reverse", {"lat": 47.9189, "lon": 106.9176}, headers={"Origin": self.origin})
                if rev.status == 429:
                    self.case("CT8-R08.reverse_429_conforms", "/v1/reverse", "get", rev, required_headers=("retry-after", "cache-control"))
                else:
                    self.r.note("CT8-R08.reverse_429_conforms", f"reverse answered {rev.status} right after the search burst")


def main():
    env = load_local_env()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default=env.get("BASE_URL") or (f"https://{env['STAGING_HOST']}" if env.get("STAGING_HOST") else "http://localhost:8080"))
    ap.add_argument("--spec", default=DEFAULT_SPEC)
    ap.add_argument("--origin", default=(env.get("ALLOWED_ORIGINS") or ORIGIN).split(",")[0].strip())
    ap.add_argument("--allow-star", action="store_true", help="dev only: ACAO '*' accepted where the origin is allowed")
    ap.add_argument("--no-429", action="store_true", help="skip CT8-R (no burst at all)")
    ap.add_argument("--budget", type=int, default=400, help="max requests per burst while trying to provoke a 429")
    ap.add_argument("--workers", type=int, default=48)
    a = ap.parse_args()
    rep = Report()
    print(f"NAV-008 contract checks: base={a.base_url} spec={a.spec} origin={a.origin}")
    h = Client(a.base_url).get("/health", timeout=10)
    if h.status != 200:
        rep.check("PRE.gateway_reachable", False, f"200 from {a.base_url}/health", f"{h.status} {h.error or ''}")
    else:
        try:
            ct = Contract8(a.spec, a.base_url, rep, a.origin, a.allow_star)
            ct.spec_checks()
            ct.expose_checks()
            if a.no_429:
                rep.skip("CT8-R.all", "--no-429")
            else:
                ct.ratelimit_cases(a.budget, a.workers)
        except Exception as e:  # noqa: BLE001
            rep.check("CT8.crashed", False, "no exception", f"{type(e).__name__}: {e}")
    print("\n" + rep.summary())
    if rep.failed:
        print("Failed checks:\n  " + "\n  ".join(f"{cid}: {d}" for _s, cid, d in rep.failed))
    sys.exit(1 if rep.failed else 0)


if __name__ == "__main__":
    main()

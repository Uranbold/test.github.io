#!/usr/bin/env python3
"""NAV-001 contract check: live gateway responses vs docs/architecture/api/openapi.yaml.

    pip install -r scripts/requirements-dev.txt     # jsonschema, PyYAML (make contract does this)
    python3 scripts/contract_check.py [--base-url http://localhost:8080] [--spec ../docs/architecture/api/openapi.yaml]

Every request example in the spec (route bodies, search/reverse parameter examples) plus the documented
error cases is sent through the gateway. Each response status must be documented for that operation,
and JSON bodies must validate (JSON Schema 2020-12) against the documented response schema. Response
headers listed in the spec for CORS preflight and tiles are checked for presence, and no Access-Control-*
header may appear more than once on any response (NAV-001 AC 43), and every Access-Control-Expose-Headers must
carry all tokens of the spec's AccessControlExposeHeaders (Retry-After since 0.4.0).
--rate-limit (NAV-008 AC 13): floods route, search and reverse and validates one 429 per group against
components/responses/RateLimited (Retry-After integer >= 1 once, Cache-Control: no-store once). Use it only
against a gateway started with GATEWAY_RATE_LIMIT=on (staging or an isolated test gateway).
Exit code 0 only if every case conforms.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

try:
    import yaml
    from jsonschema import Draft202012Validator
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT202012
except ImportError as e:  # pragma: no cover
    sys.exit(f"contract_check: missing dependency ({e}); run: pip install -r scripts/requirements-dev.txt")

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SPEC = os.path.normpath(os.path.join(HERE, "..", "..", "docs", "architecture", "api", "openapi.yaml"))
ORIGIN = "http://localhost:5173"
SPEC_URI = "urn:nav:openapi"


def http(base, method, path, body=None, headers=None):
    data = None
    hdrs = dict(headers or {})
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        hdrs.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(base + path, data=data, method=method, headers=hdrs)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            status, h, raw = r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        status, h, raw = e.code, e.headers, e.read()
    return status, {k.lower(): v for k, v in h.items()}, raw, [k.lower() for k in h.keys()]


def repeated_cors(names):
    """openapi.yaml: every response carries each CORS header at most once (NAV-001 AC 43)."""
    cors = [n for n in names if n.startswith("access-control-")]  # Vary is list-valued, may repeat
    return sorted({n for n in cors if cors.count(n) > 1})


class Checker:
    def __init__(self, spec, base):
        self.spec, self.base = spec, base.rstrip("/")
        self.registry = Registry().with_resource(SPEC_URI, Resource.from_contents(spec, default_specification=DRAFT202012))
        self.failures, self.passes = [], 0

    def deref(self, node):
        while isinstance(node, dict) and "$ref" in node:
            ptr = node["$ref"].lstrip("#/").split("/")
            node = self.spec
            for p in ptr:
                node = node[p.replace("~1", "/").replace("~0", "~")]
        return node

    @staticmethod
    def absolutize(node):
        """Point local refs ('#/components/...') of an inline schema at the registered spec."""
        if isinstance(node, dict):
            return {k: (f"{SPEC_URI}{v}" if k == "$ref" and isinstance(v, str) and v.startswith("#")
                        else Checker.absolutize(v)) for k, v in node.items()}
        if isinstance(node, list):
            return [Checker.absolutize(v) for v in node]
        return node

    def validate(self, schema_node, instance):
        schema = self.absolutize(schema_node)
        v = Draft202012Validator(schema, registry=self.registry)
        return [f"{'/'.join(map(str, e.absolute_path)) or '<root>'}: {e.message[:160]}" for e in v.iter_errors(instance)]

    def result(self, name, errors):
        if errors:
            self.failures.append(name)
            print(f"FAIL  {name}")
            for e in errors[:6]:
                print(f"        {e}")
        else:
            self.passes += 1
            print(f"PASS  {name}")

    def expose_tokens(self):
        """Tokens every Access-Control-Expose-Headers must contain (components/headers example, openapi 0.4.0+)."""
        ex = self.spec.get("components", {}).get("headers", {}).get("AccessControlExposeHeaders", {}).get("schema", {}).get("example", "")
        return {t.strip().lower() for t in ex.split(",") if t.strip()}

    def case(self, name, method, path, spec_path, body=None, headers=None, response_ref=None):
        """response_ref: validate against this components/responses entry (for paths/methods not in the spec)."""
        status, hdrs, raw, names = http(self.base, method, path, body, headers)
        self.evaluate(name, method, spec_path, status, hdrs, raw, names, headers, response_ref)

    def evaluate(self, name, method, spec_path, status, hdrs, raw, names, headers=None, response_ref=None):
        errors = [f"response header {n} sent more than once" for n in repeated_cors(names)]
        if "access-control-expose-headers" in hdrs:
            have = {t.strip().lower() for t in hdrs["access-control-expose-headers"].split(",")}
            missing = sorted(self.expose_tokens() - have)
            if missing:
                errors.append(f"Access-Control-Expose-Headers lacks {missing} (spec AccessControlExposeHeaders)")
        if status == 429:
            for single in ("retry-after", "cache-control"):
                if names.count(single) != 1:
                    errors.append(f"429: {single} must appear exactly once (got {names.count(single)})")
            ra = hdrs.get("retry-after", "")
            if not (ra.isdigit() and int(ra) >= 1):
                errors.append(f"429: Retry-After must be an integer >= 1, got {ra!r}")
            if hdrs.get("cache-control") != "no-store":
                errors.append(f"429: Cache-Control must be no-store, got {hdrs.get('cache-control')!r}")
        if response_ref:
            resp = self.deref({"$ref": response_ref})
        else:
            op = self.spec["paths"].get(spec_path, {}).get(method.lower())
            if op is None:
                self.result(f"{name} [{method} {spec_path}]", [f"operation not in spec"])
                return
            resp = op["responses"].get(str(status))
            if resp is None:
                self.result(f"{name} [{method} {spec_path} -> {status}]",
                            [f"status {status} not documented (documented: {sorted(op['responses'])}); body={raw[:120]!r}"])
                return
            resp = self.deref(resp)
        for hname in (resp.get("headers") or {}):
            if hname.lower() in ("content-length",) and status == 204:
                continue
            if hname.lower() == "access-control-allow-origin" and "origin" not in {k.lower() for k in (headers or {})}:
                continue
            if hname.lower() not in hdrs:
                errors.append(f"response header {hname} missing")
        content = (resp.get("content") or {})
        if "application/json" in content and method != "HEAD":
            try:
                instance = json.loads(raw)
            except ValueError:
                errors.append(f"body is not JSON: {raw[:120]!r}")
            else:
                errors += self.validate(content["application/json"]["schema"], instance)
        self.result(f"{name} [{method} {spec_path} -> {status}]", errors)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--spec", default=DEFAULT_SPEC)
    ap.add_argument("--rate-limit", action="store_true",
                    help="NAV-008: also provoke and validate 429 RateLimited (gateway with GATEWAY_RATE_LIMIT=on only)")
    ap.add_argument("--rate-limit-flood", type=int, default=150, help="requests per flood (default 150)")
    args = ap.parse_args()
    with open(args.spec, encoding="utf-8") as f:
        spec = yaml.safe_load(f)
    c = Checker(spec, args.base_url)
    print(f"NAV-001 contract check: {args.spec} against {c.base}")
    cors = {"Origin": ORIGIN}

    c.case("health", "GET", "/health", "/health", headers=cors)
    for p in spec["paths"]:
        c.case("preflight", "OPTIONS", p, p, headers={**cors, "Access-Control-Request-Method": "GET",
                                                     "Access-Control-Request-Headers": "Range, Content-Type"})

    c.case("tiles range", "GET", "/tiles/basemap.pmtiles", "/tiles/basemap.pmtiles", headers={**cors, "Range": "bytes=0-126"})
    c.case("tiles head", "HEAD", "/tiles/basemap.pmtiles", "/tiles/basemap.pmtiles")
    # AC 43: a Range starting at the archive size (read from HEAD) is unsatisfiable on any extract.
    size = int(http(c.base, "HEAD", "/tiles/basemap.pmtiles")[1].get("content-length") or 0)
    for m in ("GET", "HEAD"):
        c.case(f"tiles range beyond end bytes={size}-", m, "/tiles/basemap.pmtiles", "/tiles/basemap.pmtiles",
               headers={**cors, "Range": f"bytes={size}-"})

    # Route: every requestBody example from the spec, POST and GET ?json=
    route_op = spec["paths"]["/v1/route"]["post"]
    for ex_name, ex in route_op["requestBody"]["content"]["application/json"]["examples"].items():
        body = ex["value"]
        c.case(f"route example {ex_name}", "POST", "/v1/route", "/v1/route", body=body, headers=cors)
    carmn = route_op["requestBody"]["content"]["application/json"]["examples"]["carMn"]["value"]
    c.case("route GET ?json= carMn", "GET", "/v1/route?json=" + urllib.parse.quote(json.dumps(carmn)), "/v1/route")
    # X2 Beijing: outside the default (full Mongolia) dev extract. X1 Erdenet is routable on it.
    c.case("route out of coverage (X2)", "POST", "/v1/route", "/v1/route",
           body={**carmn, "locations": [carmn["locations"][0], {"lat": 39.9042, "lon": 116.4074}]})
    c.case("route missing locations", "POST", "/v1/route", "/v1/route", body={"costing": "auto", "format": "osrm"})
    c.case("route not JSON", "POST", "/v1/route", "/v1/route", body=b"not json")
    c.case("route body > 256 KB", "POST", "/v1/route", "/v1/route", body=b"{" + b" " * 300_000 + b"}")

    # Search: every example of q, with the documented bias and lang
    q_param = next(p for p in spec["paths"]["/v1/search"]["get"]["parameters"] if p.get("name") == "q")
    for ex_name, ex in q_param["examples"].items():
        lang = "en" if ex_name == "latin" else "mn"
        qs = urllib.parse.urlencode({"q": ex["value"], "lat": 47.9189, "lon": 106.9176, "limit": 5, "lang": lang})
        c.case(f"search example {ex_name}", "GET", f"/v1/search?{qs}", "/v1/search", headers=cors)
    c.case("search no result", "GET", "/v1/search?q=xqzjwvk&lang=mn", "/v1/search")
    c.case("search unsupported lang", "GET", "/v1/search?q=abc&lang=de", "/v1/search")
    c.case("search missing q", "GET", "/v1/search?lang=mn", "/v1/search")

    c.case("reverse P1", "GET", "/v1/reverse?lat=47.9189&lon=106.9176&lang=mn", "/v1/reverse", headers=cors)
    c.case("reverse Beijing", "GET", "/v1/reverse?lat=39.9042&lon=116.4074&lang=mn", "/v1/reverse")

    # Gateway errors on paths/methods the spec does not list
    c.case("unknown path", "GET", "/no/such/path", "-", response_ref="#/components/responses/GatewayNotFound")
    c.case("wrong method", "DELETE", "/v1/route", "-", response_ref="#/components/responses/GatewayMethodNotAllowed")

    if args.rate_limit:
        # NAV-008 AC 13 (openapi 0.4.0 RateLimited): flood one path group from this client, then validate a 429.
        # Only against a gateway with GATEWAY_RATE_LIMIT=on; never against the shared dev stack.
        from concurrent.futures import ThreadPoolExecutor
        carmn_body = json.dumps(carmn).encode()
        groups = [("route 429", "POST", "/v1/route", carmn_body), ("search 429", "GET", "/v1/search?q=ulaan&lang=mn", None),
                  ("reverse 429", "GET", "/v1/reverse?lat=47.9189&lon=106.9176&lang=mn", None)]
        for name, method, path, body in groups:
            spec_path = path.split("?")[0]
            got = None
            for _ in range(5):
                with ThreadPoolExecutor(max_workers=48) as ex:
                    list(ex.map(lambda _i: http(c.base, method, path, body, cors), range(args.rate_limit_flood)))
                got = http(c.base, method, path, body, cors)
                if got[0] == 429:
                    break
            status, hdrs, raw, names = got
            if status != 429:
                c.result(f"{name} [{method} {spec_path}]", [f"no 429 after flooding ({status}); is GATEWAY_RATE_LIMIT=on?"])
            else:
                c.evaluate(name, method, spec_path, status, hdrs, raw, names, cors)
            time.sleep(3)  # let the bucket drain before the next group (burst 60 at 30 r/s)

    print(f"\n{c.passes} conform, {len(c.failures)} do not")
    sys.exit(1 if c.failures else 0)


if __name__ == "__main__":
    main()

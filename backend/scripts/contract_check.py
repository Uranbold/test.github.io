#!/usr/bin/env python3
"""NAV-001 contract check: live gateway responses vs docs/architecture/api/openapi.yaml.

    pip install -r scripts/requirements-dev.txt     # jsonschema, PyYAML (make contract does this)
    python3 scripts/contract_check.py [--base-url http://localhost:8080] [--spec ../docs/architecture/api/openapi.yaml]

Every request example in the spec (route bodies, search/reverse parameter examples) plus the documented
error cases is sent through the gateway. Each response status must be documented for that operation,
and JSON bodies must validate (JSON Schema 2020-12) against the documented response schema. Response
headers listed in the spec for CORS preflight and tiles are checked for presence.
Exit code 0 only if every case conforms.
"""
import argparse
import json
import os
import sys
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
            return r.status, {k.lower(): v for k, v in r.headers.items()}, r.read()
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read()


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

    def case(self, name, method, path, spec_path, body=None, headers=None, response_ref=None):
        """response_ref: validate against this components/responses entry (for paths/methods not in the spec)."""
        status, hdrs, raw = http(self.base, method, path, body, headers)
        errors = []
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
        if "application/json" in content:
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

    # Route: every requestBody example from the spec, POST and GET ?json=
    route_op = spec["paths"]["/v1/route"]["post"]
    for ex_name, ex in route_op["requestBody"]["content"]["application/json"]["examples"].items():
        body = ex["value"]
        c.case(f"route example {ex_name}", "POST", "/v1/route", "/v1/route", body=body, headers=cors)
    carmn = route_op["requestBody"]["content"]["application/json"]["examples"]["carMn"]["value"]
    c.case("route GET ?json= carMn", "GET", "/v1/route?json=" + urllib.parse.quote(json.dumps(carmn)), "/v1/route")
    c.case("route out of coverage (X1)", "POST", "/v1/route", "/v1/route",
           body={**carmn, "locations": [carmn["locations"][0], {"lat": 49.0270, "lon": 104.0440}]})
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

    print(f"\n{c.passes} conform, {len(c.failures)} do not")
    sys.exit(1 if c.failures else 0)


if __name__ == "__main__":
    main()

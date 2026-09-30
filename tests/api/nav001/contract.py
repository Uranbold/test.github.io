#!/usr/bin/env python3
"""NAV-001 contract test: live gateway responses vs docs/architecture/api/openapi.yaml.

    tests/.venv/bin/python tests/api/nav001/contract.py [--base-url URL] [--spec PATH]

Needs: jsonschema>=4.18, PyYAML, openapi-spec-validator (tests/api/requirements.txt).
For each case: the HTTP status must be documented for that operation, the JSON body must validate
against the documented schema, and documented headers that the contract marks as always present
must be there, and no Access-Control-* header may appear more than once (openapi.yaml 0.2.0 CORS rule,
NAV-001 AC 43). Request examples from the spec are sent as-is, so the examples themselves are tested.
Test plan ids: CT-S (spec valid), CT-R* (responses). Exit 0 only if every case conforms.
"""
import argparse
import json
import os
import sys
import urllib.parse

import yaml
from jsonschema import Draft202012Validator
from openapi_spec_validator import validate as validate_spec
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import ORIGIN, P, Client, Report  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SPEC = os.path.normpath(os.path.join(HERE, "..", "..", "..", "docs", "architecture", "api", "openapi.yaml"))


class Contract:
    def __init__(self, spec_path, base_url, report):
        with open(spec_path, encoding="utf-8") as f:
            self.spec = yaml.safe_load(f)
        self.registry = Registry().with_resource("urn:spec", Resource.from_contents(self.spec, default_specification=DRAFT202012))
        self.c = Client(base_url)
        self.r = report

    def deref(self, node):
        while isinstance(node, dict) and "$ref" in node:
            cur = self.spec
            for part in node["$ref"].lstrip("#/").split("/"):
                cur = cur[part]
            node = cur
        return node

    def validator(self, schema):
        schema = json.loads(json.dumps(schema).replace('"#/components/', '"urn:spec#/components/'))
        return Draft202012Validator(schema, registry=self.registry)

    def response_def(self, path, method, status):
        op = self.spec["paths"][path][method]
        resp = op["responses"].get(str(status))
        return self.deref(resp) if resp is not None else None

    def case(self, cid, path, method, resp, response_def=None, required_headers=()):
        rd = response_def if response_def is not None else self.response_def(path, method, resp.status)
        if rd is None:
            documented = sorted(self.spec["paths"][path][method]["responses"].keys())
            return self.r.check(cid, False, f"status in {documented}", f"{resp.status} {resp.body[:100]!r}")
        errors = []
        content = (rd.get("content") or {}).get("application/json")
        if content and "schema" in content:
            body = resp.json()
            if body is None:
                errors.append(f"body is not JSON: {resp.body[:80]!r}")
            else:
                errs = sorted(self.validator(content["schema"]).iter_errors(body), key=lambda e: list(e.path))
                errors += [f"{'/'.join(map(str, e.path)) or '<root>'}: {e.message[:140]}" for e in errs[:3]]
        for h in required_headers:
            if resp.header(h) is None:
                errors.append(f"missing header {h}")
        repeated = resp.repeated_headers()
        if repeated:
            errors.append(f"repeated headers {repeated} (each Access-Control-* at most once)")
        return self.r.check(cid, not errors, f"{resp.status} conforms to openapi.yaml", "; ".join(errors))

    def run(self):
        try:
            validate_spec(self.spec)
            self.r.check("CT-S.openapi_spec_valid", True)
        except Exception as e:  # noqa: BLE001
            self.r.check("CT-S.openapi_spec_valid", False, "valid OpenAPI 3.1", str(e)[:200])

        # request examples must themselves conform to the request schema
        req_schema = self.spec["paths"]["/v1/route"]["post"]["requestBody"]["content"]["application/json"]
        v = self.validator(req_schema["schema"])
        for name, ex in req_schema["examples"].items():
            errs = list(v.iter_errors(ex["value"]))
            self.r.check(f"CT-S.route_example_{name}_valid", not errs, "example matches ValhallaRouteRequest", [e.message for e in errs[:2]])

        c = self.c
        self.case("CT-R01.health", "/health", "get", c.get("/health"))
        rng = c.request("GET", "/tiles/basemap.pmtiles", headers={"Range": "bytes=0-126", "Origin": ORIGIN})
        self.case("CT-R02.tiles_range_206", "/tiles/basemap.pmtiles", "get", rng,
                  required_headers=("content-range", "content-length", "etag", "accept-ranges", "cache-control",
                                    "access-control-allow-origin", "access-control-expose-headers"))
        full = c.request("HEAD", "/tiles/basemap.pmtiles")
        self.case("CT-R03.tiles_head", "/tiles/basemap.pmtiles", "head", full, required_headers=("content-length", "etag", "accept-ranges"))
        # NAV-001 AC 43: unsatisfiable range, start = archive size S from HEAD (bytes=99999999- is satisfiable on Mongolia).
        size = int(full.header("content-length") or 0)
        for method in ("get", "head"):
            r416 = c.request(method.upper(), "/tiles/basemap.pmtiles", headers={"Range": f"bytes={size}-", "Origin": ORIGIN})
            ok = self.case(f"CT-R20.tiles_{method}_416", "/tiles/basemap.pmtiles", method, r416,
                           required_headers=("content-range", "access-control-allow-origin", "access-control-expose-headers"))
            if ok and r416.status == 416:
                cr_schema = self.deref(self.deref(self.response_def("/tiles/basemap.pmtiles", method, 416))["headers"]["Content-Range"])["schema"]
                errs = list(self.validator(cr_schema).iter_errors(r416.header("content-range")))
                self.r.check(f"CT-R20.tiles_{method}_416_content_range", not errs and r416.header("content-range") == f"bytes */{size}",
                             f"bytes */{size} matching ContentRangeUnsatisfied", r416.header("content-range"))
            elif r416.status != 416:
                self.r.check(f"CT-R20.tiles_{method}_416_status", False, 416, r416.status)

        for name, ex in req_schema["examples"].items():
            resp = c.request("POST", "/v1/route", ex["value"])
            self.case(f"CT-R04.route_example_{name}", "/v1/route", "post", resp)
        self.case("CT-R05.route_missing_locations", "/v1/route", "post", c.request("POST", "/v1/route", {"costing": "auto", "format": "osrm"}))
        self.case("CT-R06.route_not_json", "/v1/route", "post", c.request("POST", "/v1/route", b"{bad", headers={"Content-Type": "application/json"}))
        self.case("CT-R07.route_out_of_coverage", "/v1/route", "post", c.route("P1", "X2"))
        self.case("CT-R08.route_get_json", "/v1/route", "get",
                  c.request("GET", "/v1/route?json=" + urllib.parse.quote(json.dumps(c.route_body("P1", "P2")))))
        self.case("CT-R09.route_native_json_error", "/v1/route", "post",
                  c.request("POST", "/v1/route", {"locations": [{"lat": P["X2"][0], "lon": P["X2"][1]}, {"lat": P["P1"][0], "lon": P["P1"][1]}], "costing": "auto"}))

        bias = {"lat": P["P1"][0], "lon": P["P1"][1]}
        for q in ("Сүхбаатар", "Сүхб", "Sukhbaatar", "xqzjwvk"):
            self.case(f"CT-R10.search_{q}", "/v1/search", "get", c.get("/v1/search", {"q": q, **bias, "lang": "mn"}))
        self.case("CT-R11.search_lang_de", "/v1/search", "get", c.get("/v1/search", {"q": "Сүхб", "lang": "de"}))
        self.case("CT-R12.search_missing_q", "/v1/search", "get", c.get("/v1/search", {"lang": "mn"}))
        self.case("CT-R13.reverse_P1", "/v1/reverse", "get", c.get("/v1/reverse", {"lat": P["P1"][0], "lon": P["P1"][1]}))
        self.case("CT-R14.reverse_X2", "/v1/reverse", "get", c.get("/v1/reverse", {"lat": P["X2"][0], "lon": P["X2"][1]}))
        self.case("CT-R15.reverse_bad_lat", "/v1/reverse", "get", c.get("/v1/reverse", {"lat": "abc", "lon": 106.9}))

        self.case("CT-R16.unknown_path_404", "/health", "get", c.get("/nope"),
                  response_def=self.deref({"$ref": "#/components/responses/GatewayNotFound"}))
        self.case("CT-R17.route_delete_405", "/v1/route", "post", c.request("DELETE", "/v1/route"),
                  response_def=self.deref({"$ref": "#/components/responses/GatewayMethodNotAllowed"}))
        self.case("CT-R18.route_413", "/v1/route", "post", c.request("POST", "/v1/route", b'{"a":"' + b"x" * 300000 + b'"}'))

        pre = {"Origin": ORIGIN, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "Range, Content-Type"}
        for path in self.spec["paths"]:
            resp = c.request("OPTIONS", path, headers=pre)
            self.case(f"CT-R19.preflight{path}", path, "options", resp,
                      required_headers=("access-control-allow-origin", "access-control-allow-methods",
                                        "access-control-allow-headers", "access-control-expose-headers", "vary"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"))
    ap.add_argument("--spec", default=DEFAULT_SPEC)
    a = ap.parse_args()
    rep = Report()
    print(f"NAV-001 contract test: base={a.base_url} spec={a.spec}")
    try:
        Contract(a.spec, a.base_url, rep).run()
    except Exception as e:  # noqa: BLE001
        rep.check("CT.crashed", False, "no exception", f"{type(e).__name__}: {e}")
    print("\n" + rep.summary())
    sys.exit(1 if rep.failed else 0)


if __name__ == "__main__":
    main()

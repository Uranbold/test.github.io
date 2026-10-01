#!/usr/bin/env python3
"""NAV-004 API contract test: the web route-preview request profile vs docs/architecture/api/openapi.yaml 0.5.0.

    tests/.venv/bin/python tests/api/nav004/contract_nav004.py [--base-url URL] [--spec PATH]

Story: docs/requirements/stories/NAV-004-route-preview-web.md (AC 10, 12, 15, 16, 21, 27, 33-35); ADR-0008 §4.
Test plan: docs/qa/test-plans/NAV-004.md › API (ids CT4-*).

Sends the exact body the web client builds (AC 10: POST, 6-decimal coordinates, alternates 2, format osrm,
banner_instructions true, units kilometers, language mn-MN/en-US, no voice_instructions, costing_options only for
auto + avoid) for the reference route set RS1-RS8 and checks:
  - the request bodies validate against ValhallaRouteRequest (0.5.0: bicycle is contracted);
  - each status is documented for postRoute and the body validates against its schema;
  - NAV-004 facts the client relies on: 1-3 routes, every roundabout/rotary step has `exit` >= 1, bearing_after in
    0..360, waypoints[].distance present (AC 21), RS7 -> NoSegment or error 171 (AC 34), RS8 (P1 -> X2 on foot,
    ADR-0008 §4) -> 400 DistanceExceeded (AC 35);
  - every manoeuvre type/modifier Valhalla returned is one ADR-0008 §2 maps explicitly (INFO list of anything new);
  - gateway latency per request (INFO, AC 15 is measured in the browser by tests/e2e/nav004/live.test.mjs).
Pacing: at most 2 requests per second (story Test approach). Never restarts anything. Exit 0 only if every check passes.
"""
import argparse
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "nav001"))
from contract import DEFAULT_SPEC, Contract  # noqa: E402
from lib import Report  # noqa: E402

P = {
    "P1": (47.9189, 106.9176), "P2": (47.9139, 106.9044), "P3": (47.8858, 106.9173), "P4": (47.9215, 106.8950),
    "P5": (47.9095, 106.8835), "P6": (47.9600, 106.9000), "X1": (49.0270, 104.0440), "X2": (39.9042, 116.4074),
}
COSTING = {"car": "auto", "walk": "pedestrian", "bike": "bicycle"}
RS = [
    ("RS1", "P1", "P3", "car", False), ("RS2", "P1", "P2", "walk", False), ("RS3", "P1", "P4", "car", False),
    ("RS4", "P4", "P5", "bike", False), ("RS5", "P1", "P6", "car", True), ("RS6", "P1", "X1", "car", False),
    ("RS7", "P1", "X2", "car", False), ("RS8", "P1", "X2", "walk", False), ("RS8a", "P1", "X1", "walk", False),
]
KNOWN_TYPES = {"depart", "arrive", "turn", "continue", "new name", "end of road", "fork", "merge", "on ramp", "off ramp",
               "roundabout", "rotary", "exit roundabout", "exit rotary", "notification"}
KNOWN_MODIFIERS = {"left", "right", "slight left", "slight right", "sharp left", "sharp right", "uturn", "straight"}


def client_body(o, d, mode, avoid, language="mn-MN"):
    """Mirror of the web client's body (web/README.md › Route preview › Requests), as JSON text with 6 decimals."""
    body = {
        "locations": [{"lat": float(f"{P[o][0]:.6f}"), "lon": float(f"{P[o][1]:.6f}")}, {"lat": float(f"{P[d][0]:.6f}"), "lon": float(f"{P[d][1]:.6f}")}],
        "costing": COSTING[mode], "alternates": 2, "format": "osrm", "banner_instructions": True,
        "language": language, "units": "kilometers",
    }
    if mode == "car" and avoid:
        body["costing_options"] = {"auto": {"exclude_unpaved": True}}
    return body


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"))
    ap.add_argument("--spec", default=DEFAULT_SPEC)
    args = ap.parse_args()
    rep = Report()
    ct = Contract(args.spec, args.base_url, rep)
    version = ct.spec.get("info", {}).get("version")
    rep.check("CT4-S.spec_version", version is not None and tuple(int(x) for x in version.split(".")) >= (0, 5, 0), ">= 0.5.0", version)
    req_schema = ct.spec["paths"]["/v1/route"]["post"]["requestBody"]["content"]["application/json"]["schema"]
    v = ct.validator(req_schema)
    health = ct.c.get("/health")
    if health.status != 200:
        rep.check("CT4-H.health", False, 200, health.status)
        print(rep.summary())
        return 2
    types, mods, lat = set(), set(), []
    last = 0.0
    for rid, o, d, mode, avoid in RS:
        body = client_body(o, d, mode, avoid)
        errs = list(v.iter_errors(body))
        rep.check(f"CT4-Q.{rid}_request_schema", not errs, "client body matches ValhallaRouteRequest", [e.message for e in errs[:2]])
        wait = 0.55 - (time.monotonic() - last)
        if wait > 0:
            time.sleep(wait)  # <= 2 requests per second
        last = time.monotonic()
        r = ct.c.request("POST", "/v1/route", body, headers={"Origin": "http://localhost:5173"}, timeout=15)
        lat.append((rid, round(r.elapsed * 1000)))
        ct.case(f"CT4-R.{rid}_conforms", "/v1/route", "post", r)
        j = r.json() or {}
        if rid == "RS7":
            ok = r.status == 400 and (j.get("code") == "NoSegment" or j.get("error_code") == 171)
            rep.check("CT4-F.RS7_out_of_coverage", ok, "400 NoSegment or error_code 171 (AC 34)", f"{r.status} {json.dumps(j)[:120]}")
            continue
        if rid == "RS8":
            rep.check("CT4-F.RS8_walk_too_far", r.status == 400 and j.get("code") == "DistanceExceeded", "400 DistanceExceeded (AC 35, ADR-0008 §4)", f"{r.status} {json.dumps(j)[:120]}")
            continue
        if rid == "RS8a":
            rep.note("CT4-I.RS8a_P1_X1_walk", f"{r.status} {j.get('code')} routes={len(j.get('routes') or [])} (story: QA records; ADR-0008 M: 200 on dev)")
            continue
        if rid == "RS5" and r.status == 400:
            rep.check("CT4-F.RS5_no_route_allowed", j.get("code") == "NoRoute", "200 or 400 NoRoute (AC 33)", json.dumps(j)[:120])
            continue
        if not rep.check(f"CT4-F.{rid}_200", r.status == 200, 200, f"{r.status} {r.body[:100]!r}"):
            continue
        routes = j.get("routes") or []
        rep.check(f"CT4-F.{rid}_routes_1_to_3", 1 <= len(routes) <= 3, "1..3 routes (AC 16)", len(routes))
        if rid == "RS3":
            rep.note("CT4-I.RS3_k", len(routes))
        wps = j.get("waypoints") or []
        rep.check(f"CT4-F.{rid}_snap_distances", len(wps) == 2 and all(isinstance(w.get("distance"), (int, float)) for w in wps), "2 waypoints with distance (AC 21)", [w.get("distance") for w in wps])
        bad_exit, bad_bearing = [], []
        for ri, rt in enumerate(routes):
            for leg in rt.get("legs", []):
                for s in leg.get("steps", []):
                    m = s.get("maneuver", {})
                    types.add(m.get("type"))
                    if m.get("modifier"):
                        mods.add(m.get("modifier"))
                    if m.get("type") in ("roundabout", "rotary") and not (isinstance(m.get("exit"), int) and m["exit"] >= 1):
                        bad_exit.append((ri, m))
                    b = m.get("bearing_after")
                    if not (isinstance(b, (int, float)) and 0 <= b <= 360):
                        bad_bearing.append((ri, b))
        rep.check(f"CT4-F.{rid}_roundabout_exit", not bad_exit, "every roundabout/rotary step has exit >= 1 (ADR-0008 M3)", bad_exit[:2])
        rep.check(f"CT4-F.{rid}_bearing_range", not bad_bearing, "bearing_after in 0..360", bad_bearing[:3])
    rep.note("CT4-I.maneuver_types", sorted(t for t in types if t))
    rep.note("CT4-I.modifiers", sorted(mods))
    rep.check("CT4-F.types_mapped", not (types - KNOWN_TYPES - {None}), "only types ADR-0008 §2 names", sorted(types - KNOWN_TYPES - {None}))
    rep.check("CT4-F.modifiers_mapped", not (mods - KNOWN_MODIFIERS), "only modifiers ADR-0008 §2 names", sorted(mods - KNOWN_MODIFIERS))
    rep.note("CT4-I.gateway_latency_ms", lat)
    print(rep.summary())
    return 0 if not rep.failed else 1


if __name__ == "__main__":
    sys.exit(main())

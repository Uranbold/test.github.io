#!/usr/bin/env python3
"""NAV-005 API contract test: the Android guidance profile vs docs/architecture/api/openapi.yaml 0.5.1.

    tests/.venv/bin/python tests/api/nav005/contract_nav005.py [--base-url URL] [--spec PATH] [--gateway-container NAME]

Story: docs/requirements/stories/NAV-005-active-navigation-android.md (AC 5, 7, 43, 45, 49, 65, 68); ADR-0009 §2.
Test plan: docs/qa/test-plans/NAV-005.md › API (ids CT5-*). Covers the architect's BE-1 / BE-2 checks from the QA side
(the backend lane produced no result in this delivery).

  - the 0.5.1 `postRoute` examples guidanceCarMn, guidanceRerouteHeading, guidanceWalkEn and the app's exact bodies
    (QaRerouteTest.tcP08 strings) validate against ValhallaRouteRequest;
  - each is sent live; status documented, body conforms; 200 with code Ok and exactly 1 route (alternates 0); at least
    one step with non-empty voiceInstructions and bannerInstructions; every step has maneuver.type and bearing_after
    (0..360); every manoeuvre type/modifier is one ADR-0008 §2 maps; roundabout/rotary steps carry exit >= 1;
  - reroute errors the client classifies (AC 49): out-of-coverage origin -> 400 NoSegment or error_code 171, conforms;
  - BE-2 / NFR-L1: 20 sequential warm guidanceCarMn requests, p95 <= 500 ms, median response size recorded;
  - NFR-P1 / AC 68: the gateway access-log lines written during this run (read-only `docker logs --since`) contain no
    coordinates, no request body and no query string for /v1/route.
Pacing: at most 2 requests per second. Never starts, stops or restarts anything. Exit 0 only if every check passes.
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "nav001"))
from contract import DEFAULT_SPEC, Contract  # noqa: E402
from lib import Report, p95  # noqa: E402

KNOWN_TYPES = {"depart", "arrive", "turn", "continue", "new name", "end of road", "fork", "merge", "on ramp", "off ramp",
               "roundabout", "rotary", "exit roundabout", "exit rotary", "notification"}
KNOWN_MODIFIERS = {"left", "right", "slight left", "slight right", "sharp left", "sharp right", "uturn", "straight"}
# The exact bodies the app sends (NAV-005 AC 5 / AC 43; same strings as QaRerouteTest.tcP08).
APP_BODIES = {
    "app_car_mn": '{"locations":[{"lat":47.918900,"lon":106.917600},{"lat":47.885800,"lon":106.917300}],"costing":"auto","alternates":0,"format":"osrm","banner_instructions":true,"voice_instructions":true,"units":"kilometers","language":"mn-MN"}',
    "app_car_avoid_mn": '{"locations":[{"lat":47.918900,"lon":106.917600},{"lat":47.885800,"lon":106.917300}],"costing":"auto","costing_options":{"auto":{"exclude_unpaved":true}},"alternates":0,"format":"osrm","banner_instructions":true,"voice_instructions":true,"units":"kilometers","language":"mn-MN"}',
    "app_walk_en": '{"locations":[{"lat":47.918900,"lon":106.917600},{"lat":47.913900,"lon":106.904400}],"costing":"pedestrian","alternates":0,"format":"osrm","banner_instructions":true,"voice_instructions":true,"units":"kilometers","language":"en-US"}',
    "app_reroute_heading_en": '{"locations":[{"lat":47.918900,"lon":106.917600,"heading":165},{"lat":47.885800,"lon":106.917300}],"costing":"auto","alternates":0,"format":"osrm","banner_instructions":true,"voice_instructions":true,"units":"kilometers","language":"en-US"}',
}
OUT_OF_COVERAGE = '{"locations":[{"lat":39.904200,"lon":116.407400},{"lat":47.885800,"lon":106.917300}],"costing":"auto","alternates":0,"format":"osrm","banner_instructions":true,"voice_instructions":true,"units":"kilometers","language":"mn-MN"}'
COORD = re.compile(r"-?\d{1,3}\.\d{4,}")


class Pacer:
    def __init__(self, gap=0.55):
        self.gap, self.last = gap, 0.0

    def wait(self):
        w = self.gap - (time.monotonic() - self.last)
        if w > 0:
            time.sleep(w)  # <= 2 requests per second
        self.last = time.monotonic()


def step_checks(rep, cid, j):
    routes = j.get("routes") or []
    rep.check(f"{cid}_code_ok", j.get("code") == "Ok", "code Ok", j.get("code"))
    rep.check(f"{cid}_one_route", len(routes) == 1, "exactly 1 route (alternates 0)", len(routes))
    if not routes:
        return set(), set()
    steps = [s for leg in routes[0].get("legs", []) for s in leg.get("steps", [])]
    rep.check(f"{cid}_voice_present", any(s.get("voiceInstructions") for s in steps), ">= 1 step with voiceInstructions", None)
    rep.check(f"{cid}_banner_present", any(s.get("bannerInstructions") for s in steps), ">= 1 step with bannerInstructions", None)
    bad = [i for i, s in enumerate(steps) if not s.get("maneuver", {}).get("type") or not isinstance(s["maneuver"].get("bearing_after"), (int, float)) or not 0 <= s["maneuver"]["bearing_after"] <= 360]
    rep.check(f"{cid}_maneuver_fields", not bad, "every step has maneuver.type and bearing_after 0..360", bad[:3])
    bad_exit = [i for i, s in enumerate(steps) if s["maneuver"].get("type") in ("roundabout", "rotary") and not (isinstance(s["maneuver"].get("exit"), int) and s["maneuver"]["exit"] >= 1)]
    rep.check(f"{cid}_roundabout_exit", not bad_exit, "roundabout/rotary steps have exit >= 1", bad_exit)
    return {s["maneuver"].get("type") for s in steps}, {s["maneuver"].get("modifier") for s in steps if s["maneuver"].get("modifier")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://127.0.0.1:8080"))
    ap.add_argument("--spec", default=DEFAULT_SPEC)
    ap.add_argument("--gateway-container", default=os.environ.get("GATEWAY_CONTAINER", "navmn-gateway-1"))
    args = ap.parse_args()
    rep = Report()
    ct = Contract(args.spec, args.base_url, rep)
    version = ct.spec.get("info", {}).get("version")
    rep.check("CT5-S.spec_version", version is not None and tuple(int(x) for x in version.split(".")) >= (0, 5, 1), ">= 0.5.1", version)
    op = ct.spec["paths"]["/v1/route"]["post"]
    v = ct.validator(op["requestBody"]["content"]["application/json"]["schema"])
    examples = op["requestBody"]["content"]["application/json"].get("examples", {})
    started = datetime.now(timezone.utc)
    health = ct.c.get("/health")
    if health.status != 200:
        rep.check("CT5-H.health", False, 200, health.status)
        print(rep.summary())
        return 2
    pacer = Pacer()
    bodies = {}
    for name in ("guidanceCarMn", "guidanceRerouteHeading", "guidanceWalkEn"):
        ok = rep.check(f"CT5-E.{name}_present", name in examples, "example in openapi 0.5.1", sorted(examples))
        if ok:
            bodies[name] = examples[name]["value"]
    for name, text in APP_BODIES.items():
        bodies[name] = json.loads(text)
    types, mods = set(), set()
    for name, body in bodies.items():
        errs = list(v.iter_errors(body))
        rep.check(f"CT5-Q.{name}_request_schema", not errs, "matches ValhallaRouteRequest", [e.message for e in errs[:2]])
        pacer.wait()
        r = ct.c.request("POST", "/v1/route", body, timeout=15)
        ct.case(f"CT5-R.{name}_conforms", "/v1/route", "post", r)
        if not rep.check(f"CT5-F.{name}_200", r.status == 200, 200, f"{r.status} {r.body[:120]!r}"):
            continue
        t, m = step_checks(rep, f"CT5-F.{name}", r.json() or {})
        types |= t
        mods |= m
    rep.check("CT5-F.types_mapped", not (types - KNOWN_TYPES - {None}), "only types ADR-0008 §2 maps", sorted(t for t in types - KNOWN_TYPES if t))
    rep.check("CT5-F.modifiers_mapped", not (mods - KNOWN_MODIFIERS), "only modifiers ADR-0008 §2 maps", sorted(mods - KNOWN_MODIFIERS))
    # AC 49: an out-of-coverage reroute origin is classified as NoSegment / 171 by the client.
    pacer.wait()
    r = ct.c.request("POST", "/v1/route", json.loads(OUT_OF_COVERAGE), timeout=15)
    ct.case("CT5-R.out_of_coverage_conforms", "/v1/route", "post", r)
    j = r.json() or {}
    rep.check("CT5-F.out_of_coverage", r.status == 400 and (j.get("code") == "NoSegment" or j.get("error_code") == 171), "400 NoSegment or error_code 171", f"{r.status} {json.dumps(j)[:120]}")
    # BE-2 / NFR-L1: 20 sequential warm guidanceCarMn requests.
    lat, sizes = [], []
    body = bodies.get("guidanceCarMn") or json.loads(APP_BODIES["app_car_avoid_mn"])
    for _ in range(20):
        pacer.wait()
        r = ct.c.request("POST", "/v1/route", body, timeout=15)
        if r.status == 200:
            lat.append(r.elapsed * 1000)
            sizes.append(len(r.body))
    rep.check("CT5-L.guidance_20_ok", len(lat) == 20, "20 x 200", len(lat))
    if lat:
        rep.check("CT5-L.p95_le_500ms", p95(lat) <= 500, "p95 <= 500 ms (NFR-L1)", f"p95 {p95(lat):.0f} ms, median {statistics.median(lat):.0f} ms, max {max(lat):.0f} ms")
        rep.note("CT5-I.response_size_median_bytes", int(statistics.median(sizes)))
    # NFR-P1 / AC 68: access-log lines of this run (read-only).
    try:
        out = subprocess.run(["docker", "logs", "--since", started.strftime("%Y-%m-%dT%H:%M:%SZ"), args.gateway_container],
                             capture_output=True, text=True, timeout=30)
        lines = [ln for ln in (out.stdout + out.stderr).splitlines() if "/v1/route" in ln]
        leaks = [ln for ln in lines if COORD.search(ln) or '"locations"' in ln or "/v1/route?" in ln]
        rep.check("CT5-P.access_log_lines_seen", len(lines) >= 20, ">= 20 /v1/route log lines", len(lines))
        rep.check("CT5-P.no_coordinates_in_access_log", not leaks, "0 lines with coordinates, body or query", leaks[:2])
        if lines:
            rep.note("CT5-I.access_log_sample", lines[-1][:300])
    except Exception as e:  # noqa: BLE001
        rep.skip("CT5-P.no_coordinates_in_access_log", f"docker logs not readable: {e}")
    print(rep.summary())
    return 0 if not rep.failed else 1


if __name__ == "__main__":
    sys.exit(main())

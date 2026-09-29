#!/usr/bin/env python3
"""NAV-001 QA acceptance checks through the gateway (Python 3.9+ stdlib only).

Test plan: docs/qa/test-plans/NAV-001.md (each check id below is a test case id there).

    python3 tests/api/nav001/checks.py [--base-url URL] [--group G ...] [--json-out FILE]

Groups (default: smoke):
  smoke      AC 40 set: AC 9, 13-16, 18, 21-23, 25, 28-30, 32 + AC 42 info (11, 19, 24) + AC 40 runtime
  full       smoke + AC 10, 11, 12, 17, 19, 20, 24, 26, 27, 33, 43 + contract extras (404/405/413/HEAD/304/416/GET ?json=,
             no repeated Access-Control-* header on any response)
  perf       AC 35-38 (p95 of 20 sequential requests each)
  ac43       AC 43 only (416 on TILES: single CORS headers, JSON body); also part of full. Runs against a
             gateway-only container too (isolated-gateway.sh), which is how the negative control is done
  cors-allowlist  AC 31 (+ AC 43 per origin). The gateway must already run with CORS_ALLOWED_ORIGINS=http://localhost:5173
  tiles-missing   CT17: gateway started WITHOUT data/tiles/basemap.pmtiles (tests/api/nav001/isolated-gateway.sh):
             the TILES 404 must still be JSON with single CORS headers (error_page inheritance regression, AC 43 fix)
  outage     AC 34. Stops and restarts valhalla and photon with docker compose (needs --compose-dir)
  logs       No PII in logs: sends requests with marker coordinates/text, then greps service logs (needs --compose-dir)
  stats      AC 39 steady-state memory and data/ size (needs --compose-dir)

Exit code 0 only if no check failed (AC 41). Every FAIL line prints expected and actual.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import (EVIL_ORIGIN, MN_SPECIFIC, ORIGIN, P, Client, PMTilesRemote, Report, decode_mvt,  # noqa: E402
                 decode_polyline, haversine_m, is_cyrillic, lonlat_to_tile, nearest_feature_m, p95)

MOVE_ALLOWANCE_M = 50  # story: "QA may move any point by up to 50 m to the nearest routable road"


class Nav001:
    def __init__(self, base_url, report, compose_dir=None, compose_project=None):
        self.c = Client(base_url)
        self.r = report
        self.compose_dir = compose_dir
        self.compose_project = compose_project
        self._pm = None
        self._route13 = None

    # ------------------------------------------------------------------ helpers
    def pm(self):
        if self._pm is None:
            self._pm = PMTilesRemote(self.c)
        return self._pm

    def covers(self, key):
        lat, lon = P[key]
        b = self.pm().bounds
        return b[0] <= lon <= b[2] and b[1] <= lat <= b[3]

    def route13(self):
        if self._route13 is None:
            self._route13 = self.c.route("P1", "P3")
        return self._route13

    def within(self, cid, dist, target_key, limit=100):
        """AC 14 endpoint check (story CR 2026-09-29): hard limit = 100 m route-end limit + 50 m allowed
        reference-point move = 150 m. 100-150 m passes and prints the measured distance as INFO."""
        if dist <= limit:
            return self.r.check(cid, True, actual=f"{dist:.0f} m")
        if dist <= limit + MOVE_ALLOWANCE_M:
            self.r.note(cid + ".move", f"route endpoint is {dist:.0f} m from {target_key} (100-150 m band: passes with the "
                                        f"story's {MOVE_ALLOWANCE_M} m point-move allowance)")
            return self.r.check(cid, True, actual=f"{dist:.0f} m")
        return self.r.check(cid, False, f"<= {limit} m from {target_key} (<= {limit + MOVE_ALLOWANCE_M} m with the 50 m move)",
                            f"{dist:.0f} m")

    def compose(self, *args, timeout=180):
        cmd = ["docker", "compose"]
        if self.compose_project:
            cmd += ["-p", self.compose_project]
        cmd += list(args)
        return subprocess.run(cmd, cwd=self.compose_dir, capture_output=True, text=True, timeout=timeout)

    def is_json(self, resp):
        return resp.json() is not None and "json" in (resp.header("content-type") or "")

    # ------------------------------------------------------------------ B. tiles
    def ac09(self):
        r = self.c.request("GET", "/tiles/basemap.pmtiles", headers={"Range": "bytes=0-126"})
        self.r.check("AC09.status_206", r.status == 206, 206, r.status)
        cr = r.header("content-range") or ""
        self.r.check("AC09.content_range", re.match(r"^bytes 0-126/\d+$", cr) is not None, "bytes 0-126/<total>", cr or None)
        self.r.check("AC09.magic_v3", r.body[:7] == b"PMTiles" and len(r.body) > 7 and r.body[7] == 3,
                     "b'PMTiles' + 0x03", r.body[:8])
        self.r.check("AC09.length_127", len(r.body) == 127, 127, len(r.body))

    def ac10(self):
        pm = self.pm()
        outside = [k for k in ("P1", "P2", "P3", "P4", "P5", "P6") if not self.covers(k)]
        self.r.check("AC10.bounds_cover_P1_P6", not outside, "P1-P6 inside bounds",
                     f"bounds={pm.bounds}; outside: {', '.join(outside)}")
        self.r.check("AC10.maxzoom_ge_14", pm.max_zoom >= 14, ">= 14", pm.max_zoom)
        attr = pm.metadata().get("attribution", "")
        self.r.check("AC10.attribution_osm", "OpenStreetMap" in attr, "contains 'OpenStreetMap'", attr)
        self.r.note("AC10.attribution_full_string", "contains 'OpenStreetMap contributors'"
                    if "OpenStreetMap contributors" in attr else f"short form only: {attr!r} (clients must render the full string)")

    def ac11(self, info_only=False):
        pm = self.pm()
        x, y = lonlat_to_tile(*P["P1"], 14)
        data = pm.tile(14, x, y)
        layers = decode_mvt(data) if data else {}
        roads = layers.get("roads", [])
        named = [f for f in roads if isinstance(f.get("name"), str) and f["name"].strip()]
        keys = {k for feats in layers.values() for f in feats for k in f}
        if not info_only:
            self.r.check("AC11.tile_nonempty", bool(data), "non-empty z14 tile", f"z14/{x}/{y}: {len(data or b'')} bytes")
            self.r.check("AC11.named_road", bool(named), ">= 1 road feature with non-empty name",
                         f"{len(roads)} road features, {len(named)} named")
        self.r.note("AC11.name_mn_present", "name:mn" in keys)
        self.r.note("AC11.name_en_present", "name:en" in keys)
        if named:
            self.r.note("AC11.sample_road_names", sorted({f["name"] for f in named})[:5])

    def ac12(self):
        r = self.c.request("HEAD", "/tiles/basemap.pmtiles")
        size = int(r.header("content-length") or -1)
        self.r.check("AC12.size_le_200MB", 0 < size <= 200 * 1024 * 1024, "<= 200 MB (209715200 bytes)",
                     f"{size} bytes ({size / 1048576:.1f} MiB)")

    # ------------------------------------------------------------------ C. routing
    def ac13(self):
        r = self.route13()
        j = r.json() or {}
        self.r.check("AC13.status_200_ok", r.status == 200 and j.get("code") == "Ok", "200 / code=Ok",
                     f"{r.status} / {j.get('code')} {j.get('message', '')}")
        routes = j.get("routes") or []
        self.r.check("AC13.routes_ge_1", len(routes) >= 1, ">= 1", len(routes))
        if not routes:
            return
        straight = haversine_m(P["P1"], P["P3"])
        d = routes[0].get("distance", 0)
        self.r.check("AC13.distance_bounds", straight <= d <= 2.5 * straight,
                     f"{straight:.0f} m <= distance <= {2.5 * straight:.0f} m", f"{d:.0f} m")
        self.r.check("AC13.duration_gt_0", routes[0].get("duration", 0) > 0, "> 0", routes[0].get("duration"))

    def ac14(self):
        j = self.route13().json() or {}
        routes = j.get("routes") or []
        if not routes:
            return self.r.check("AC14.geometry", False, "a route", "no route (see AC13)")
        pts = decode_polyline(routes[0]["geometry"], 6)
        self.within("AC14.first_point_near_P1", haversine_m(pts[0], P["P1"]), "P1")
        self.within("AC14.last_point_near_P3", haversine_m(pts[-1], P["P3"]), "P3")

    def ac15(self):
        j = self.route13().json() or {}
        steps = ((j.get("routes") or [{}])[0].get("legs") or [{}])[0].get("steps") or []
        no_man = [i for i, s in enumerate(steps) if not isinstance(s.get("maneuver"), dict)]
        self.r.check("AC15.every_step_has_maneuver", steps and not no_man, "all steps have maneuver",
                     f"{len(steps)} steps, missing at {no_man}")

        def voice_ok(s):
            return any(isinstance(v.get("announcement"), str) and v["announcement"].strip()
                       and isinstance(v.get("distanceAlongGeometry"), (int, float)) for v in s.get("voiceInstructions") or [])

        def banner_ok(s):
            return any(isinstance((b.get("primary") or {}).get("text"), str) and b["primary"]["text"].strip()
                       and isinstance(b.get("distanceAlongGeometry"), (int, float)) for b in s.get("bannerInstructions") or [])

        both = [i for i, s in enumerate(steps) if voice_ok(s) and banner_ok(s)]
        self.r.check("AC15.step_with_voice_and_banner", bool(both), ">= 1 step with valid voice + banner",
                     f"steps with both: {both}")

    @staticmethod
    def _names(route):
        names = set()
        for leg in route.get("legs", []):
            for s in leg.get("steps", []):
                for k in ("name", "ref", "destinations", "exits", "rotary_name"):
                    v = s.get(k)
                    if isinstance(v, str) and v:
                        names.add(v)
                        names.update(p.strip() for p in re.split(r"[;,/]", v) if p.strip())
                for b in s.get("bannerInstructions") or []:
                    for part in ("primary", "secondary", "sub"):
                        for comp in ((b.get(part) or {}).get("components") or []):
                            if comp.get("type") == "text" and comp.get("text"):
                                names.add(comp["text"])
        return sorted(names, key=len, reverse=True)

    @staticmethod
    def _announcements(route):
        return [v["announcement"] for leg in route.get("legs", []) for s in leg.get("steps", [])
                for v in s.get("voiceInstructions") or [] if v.get("announcement")]

    def _strip_names(self, text, names):
        for n in names:
            text = text.replace(n, " ")
        return text

    def ac16(self):
        j = self.route13().json() or {}
        route = (j.get("routes") or [{}])[0]
        text = "".join(self._announcements(route))
        self.r.check("AC16.cyrillic_and_mn_letter",
                     any(is_cyrillic(ch) for ch in text) and any(ch in MN_SPECIFIC for ch in text),
                     "Cyrillic + one of ө ү Ө Ү", text[:120] or "(no announcements)")
        stripped = self._strip_names(text, self._names(route))
        self.r.note("AC16.mn_letters_outside_street_names",
                    any(ch in MN_SPECIFIC for ch in stripped))
        self.r.note("AC16.voiceLocale", route.get("voiceLocale"))
        zw = [a for a in self._announcements(route) if "​" in a]
        if zw:
            self.r.note("AC16.zero_width_space_in_announcements", len(zw))

    def ac17(self):
        mn = self.route13().json() or {}
        r = self.c.route("P1", "P3", language="en-US")
        en = r.json() or {}
        if r.status != 200 or not en.get("routes") or not mn.get("routes"):
            return self.r.check("AC17.en_route", False, "200 with a route for mn-MN and en-US", f"en-US {r.status}")
        a_mn, a_en = self._announcements(mn["routes"][0]), self._announcements(en["routes"][0])
        self.r.check("AC17.differs_from_mn", a_en and a_en != a_mn, "en-US announcements != mn-MN", a_en[:2])
        stripped = self._strip_names(" ".join(a_en), self._names(en["routes"][0]))
        cyr = sorted({ch for ch in stripped if is_cyrillic(ch)})
        self.r.check("AC17.no_cyrillic_except_names", not cyr, "no Cyrillic outside OSM street names",
                     f"Cyrillic left: {''.join(cyr)} in {stripped[:160]!r}")
        self.r.note("AC17.voiceLocale", en["routes"][0].get("voiceLocale"))

    def ac18(self):
        r = self.c.route("P1", "P2", costing="pedestrian")
        j = r.json() or {}
        self.r.check("AC18.status_200_ok", r.status == 200 and j.get("code") == "Ok", "200 / Ok", f"{r.status} / {j.get('code')}")
        if j.get("routes"):
            straight = haversine_m(P["P1"], P["P2"])
            d = j["routes"][0]["distance"]
            self.r.check("AC18.distance_bounds", straight <= d <= 2.5 * straight,
                         f"{straight:.0f} m <= distance <= {2.5 * straight:.0f} m", f"{d:.0f} m")

    def ac19(self, info_only=False):
        body = self.c.route_body("P1", "P6", costing_options={"auto": {"exclude_unpaved": True}})
        r = self.c.request("POST", "/v1/route", body, timeout=15)
        j = r.json()
        ok = (r.status == 200 and j and j.get("routes")) or (400 <= r.status < 500 and j is not None)
        if not info_only:
            self.r.check("AC19.200_or_4xx_json_never_5xx", bool(ok), "200 with route, or 4xx JSON; no 5xx / timeout",
                         f"{r.status} {r.error or ''} {r.body[:100]!r}")
        if r.status == 200 and j and j.get("routes"):
            pts = decode_polyline(j["routes"][0]["geometry"])
            end = haversine_m(pts[-1], P["P6"])
            self.r.note("AC19.unpaved_result", f"200, distance {j['routes'][0]['distance']:.0f} m, route ends {end:.0f} m from P6"
                        + (" (P6 outside the tile/extract bounds: the destination snapped to the extract edge)" if not self.covers("P6") else ""))
        else:
            self.r.note("AC19.unpaved_result", f"{r.status} {(j or {}).get('code')} {(j or {}).get('message', r.error)}")

    def ac20(self):
        r = self.c.route("P1", "P4", alternates=2)
        j = r.json() or {}
        n = len(j.get("routes") or [])
        self.r.check("AC20.200_with_1_to_3_routes", r.status == 200 and 1 <= n <= 3, "200 with 1-3 routes", f"{r.status}, {n} routes")

    # ------------------------------------------------------------------ D. search
    BIAS = {"lat": P["P1"][0], "lon": P["P1"][1]}

    def _fc(self, cid, r):
        j = r.json()
        ok = r.status == 200 and isinstance(j, dict) and j.get("type") == "FeatureCollection" and isinstance(j.get("features"), list)
        self.r.check(cid, ok, "200 GeoJSON FeatureCollection", f"{r.status} {r.body[:100]!r}")
        return j if ok else {"features": []}

    def ac21(self):
        j = self._fc("AC21.status_fc", self.c.get("/v1/search", {"q": "Сүхбаатар", **self.BIAS, "limit": 5}))
        n = len(j["features"])
        self.r.check("AC21.features_1_to_5", 1 <= n <= 5, "1-5 features", n)
        d = nearest_feature_m(j, P["P1"])
        self.r.check("AC21.one_within_5km", d is not None and d <= 5000, "nearest <= 5000 m from P1",
                     None if d is None else f"{d:.0f} m")

    def ac22(self):
        j = self._fc("AC22.status_fc", self.c.get("/v1/search", {"q": "Сүхб", **self.BIAS}))
        self.r.check("AC22.prefix_ge_1", len(j["features"]) >= 1, ">= 1 feature", len(j["features"]))
        if j["features"]:
            self.r.note("AC22.top_result", j["features"][0]["properties"].get("name"))

    def ac23(self):
        r = self.c.get("/v1/search", {"q": "xqzjwvk", **self.BIAS})
        j = self._fc("AC23.status_fc", r)
        self.r.check("AC23.empty_features", r.status == 200 and j["features"] == [], "[]", len(j["features"]))

    def ac24(self, info_only=False):
        r = self.c.get("/v1/search", {"q": "Sukhbaatar", **self.BIAS})
        j = r.json() or {}
        if not info_only:
            self.r.check("AC24.status_200", r.status == 200, 200, r.status)
        d = nearest_feature_m(j, P["P1"])
        self.r.note("AC24.latin_baseline_no_lang", f"{len(j.get('features', []))} results, nearest "
                    + ("n/a" if d is None else f"{d:.0f} m") + " from P1")
        r2 = self.c.get("/v1/search", {"q": "Sukhbaatar", **self.BIAS, "lang": "en"})
        j2 = r2.json() or {}
        d2 = nearest_feature_m(j2, P["P1"])
        self.r.note("AC24.latin_baseline_lang_en", f"HTTP {r2.status}, {len(j2.get('features', []))} results, nearest "
                    + ("n/a" if d2 is None else f"{d2:.0f} m"))
        r3 = self.c.get("/v1/search", {"q": "suhbaatar", **self.BIAS})
        j3 = r3.json() or {}
        self.r.note("AC24.typo_suhbaatar_baseline", f"HTTP {r3.status}, {len(j3.get('features', []))} results")

    def ac25(self):
        j = self._fc("AC25.status_fc", self.c.get("/v1/reverse", {"lat": P["P1"][0], "lon": P["P1"][1]}))
        d = nearest_feature_m(j, P["P1"])
        self.r.check("AC25.feature_within_300m", d is not None and d <= 300, ">= 1 feature <= 300 m from P1",
                     None if d is None else f"{d:.0f} m ({len(j['features'])} features)")

    def ac26(self):
        r = self.c.get("/v1/reverse", {"lat": P["X2"][0], "lon": P["X2"][1]})
        j = r.json()
        ok = (r.status == 200 and isinstance(j, dict) and j.get("features") == []) or (400 <= r.status < 500 and j is not None)
        self.r.check("AC26.beijing_empty_or_4xx", ok, "200 [] or 4xx JSON, never 5xx", f"{r.status} {r.body[:100]!r}")

    def ac27(self):
        for lang in ("mn", "en"):
            r = self.c.get("/v1/search", {"q": "Улаанбаатар", "lang": lang, **self.BIAS})
            self.r.check(f"AC27.lang_{lang}_200", r.status == 200, 200, f"{r.status} {r.body[:100]!r}")

    # ------------------------------------------------------------------ E. gateway
    def ac28(self):
        r = self.c.get("/health", timeout=5)
        self.r.check("AC28.health_200_within_1s", r.status == 200 and r.elapsed <= 1.0, "200 in <= 1 s",
                     f"{r.status} in {r.elapsed * 1000:.0f} ms")

    PREFLIGHT = {"Origin": ORIGIN, "Access-Control-Request-Method": "GET",
                 "Access-Control-Request-Headers": "Range, Content-Type"}

    @staticmethod
    def _tokens(v):
        return {t.strip().lower() for t in (v or "").split(",") if t.strip()}

    def ac29(self):
        for name, path in (("ROUTE", "/v1/route"), ("SEARCH", "/v1/search"), ("TILES", "/tiles/basemap.pmtiles")):
            r = self.c.request("OPTIONS", path, headers=self.PREFLIGHT)
            acao = r.header("access-control-allow-origin")
            meth, hdrs = self._tokens(r.header("access-control-allow-methods")), self._tokens(r.header("access-control-allow-headers"))
            self.r.check(f"AC29.{name}.status", r.status in (200, 204), "200 or 204", r.status)
            self.r.check(f"AC29.{name}.allow_origin", acao in (ORIGIN, "*"), f"{ORIGIN} or *", acao)
            self.r.check(f"AC29.{name}.allow_methods", {"get", "post", "options"} <= meth, "GET, POST, OPTIONS", r.header("access-control-allow-methods"))
            self.r.check(f"AC29.{name}.allow_headers", {"range", "content-type"} <= hdrs, "Range, Content-Type", r.header("access-control-allow-headers"))

    def ac30(self):
        r = self.c.request("GET", "/tiles/basemap.pmtiles", headers={"Origin": ORIGIN, "Range": "bytes=0-126"})
        self.r.check("AC30.allow_origin", r.header("access-control-allow-origin") in (ORIGIN, "*"), f"{ORIGIN} or *",
                     r.header("access-control-allow-origin"))
        exp = self._tokens(r.header("access-control-expose-headers"))
        self.r.check("AC30.expose_headers", {"content-range", "content-length", "etag"} <= exp,
                     "Content-Range, Content-Length, ETag", r.header("access-control-expose-headers"))

    def ac31(self):
        for path in ("/v1/route", "/v1/search", "/tiles/basemap.pmtiles", "/health"):
            r = self.c.request("OPTIONS", path, headers={**self.PREFLIGHT, "Origin": EVIL_ORIGIN})
            self.r.check(f"AC31.evil_preflight_no_acao{path}", r.header("access-control-allow-origin") is None,
                         "no Access-Control-Allow-Origin", r.header("access-control-allow-origin"))
        r = self.c.request("OPTIONS", "/v1/route", headers=self.PREFLIGHT)
        self.r.check("AC31.allowed_origin_echoed", r.header("access-control-allow-origin") == ORIGIN, ORIGIN,
                     r.header("access-control-allow-origin"))
        # Valhalla adds its own "Access-Control-Allow-Origin: *"; it must not leak through on real requests.
        body = self.c.route_body("P1", "P2")
        r = self.c.request("POST", "/v1/route", body, headers={"Origin": EVIL_ORIGIN})
        self.r.check("AC31.evil_route_response_no_acao", r.header("access-control-allow-origin") is None,
                     "no Access-Control-Allow-Origin on POST /v1/route", r.header("access-control-allow-origin"))
        r = self.c.get("/v1/search", {"q": "Сүхб"}, headers={"Origin": EVIL_ORIGIN})
        self.r.check("AC31.evil_search_response_no_acao", r.header("access-control-allow-origin") is None,
                     "no Access-Control-Allow-Origin on GET /v1/search", r.header("access-control-allow-origin"))
        # AC 43 in allowlist mode: the 416 echoes the allowed origin once, and a disallowed origin gets none.
        self.ac43(ORIGIN, True, prefix="AC43.allowlist_allowed")
        self.ac43(EVIL_ORIGIN, False, prefix="AC43.allowlist_evil")

    def ac32(self):
        key = "X1"
        if self.covers("X1"):
            key = "X2"
            self.r.note("AC32.point_used", "X2 (Beijing): the tile bounds cover X1 Erdenet, so X1 is not out of coverage")
        else:
            self.r.note("AC32.point_used", "X1 (Erdenet), outside the dev extract bounds")
        if key == "X2":
            x1 = self.c.route("P1", "X1", timeout=15)
            self.r.note("AC32.x1_erdenet_on_this_build", f"HTTP {x1.status} {(x1.json() or {}).get('code')}"
                        + (f", {x1.json()['routes'][0]['distance'] / 1000:.0f} km" if x1.status == 200 and (x1.json() or {}).get('routes') else ""))
        r = self.c.route("P1", key)
        j = r.json()
        self.r.check("AC32.4xx_json_within_3s",
                     400 <= r.status < 500 and isinstance(j, dict) and bool(j.get("message") or j.get("error")) and r.elapsed <= 3,
                     "4xx JSON with message in <= 3 s", f"{r.status} in {r.elapsed * 1000:.0f} ms: {r.body[:100]!r}")

    def ac33(self):
        r = self.c.request("POST", "/v1/route", {"costing": "auto", "format": "osrm"})
        self.r.check("AC33.missing_locations_400_json_1s", r.status == 400 and r.json() is not None and r.elapsed <= 1,
                     "400 JSON in <= 1 s", f"{r.status} in {r.elapsed * 1000:.0f} ms: {r.body[:100]!r}")
        r = self.c.request("POST", "/v1/route", b"{not json", headers={"Content-Type": "application/json"})
        self.r.check("AC33.not_json_400_json_1s", r.status == 400 and r.json() is not None and r.elapsed <= 1,
                     "400 JSON in <= 1 s", f"{r.status} in {r.elapsed * 1000:.0f} ms: {r.body[:100]!r}")

    def contract_extras(self):
        """Gateway behaviour promised by openapi.yaml beyond the story ACs (CT-* test cases)."""
        r = self.c.get("/nope")
        j = r.json() or {}
        self.r.check("CT01.unknown_path_404_json", r.status == 404 and j.get("code") == "NotFound", "404 {code: NotFound}", f"{r.status} {r.body[:80]!r}")
        r = self.c.request("DELETE", "/v1/route")
        j = r.json() or {}
        self.r.check("CT02.delete_route_405_json", r.status == 405 and j.get("code") == "MethodNotAllowed", "405 {code: MethodNotAllowed}", f"{r.status} {r.body[:80]!r}")
        r = self.c.request("POST", "/v1/route", b'{"x":"' + b"a" * (300 * 1024) + b'"}')
        j = r.json() or {}
        self.r.check("CT03.body_over_256k_413_json", r.status == 413 and j.get("code") == "PayloadTooLarge", "413 {code: PayloadTooLarge}", f"{r.status} {r.body[:80]!r}")
        r = self.c.request("HEAD", "/tiles/basemap.pmtiles")
        self.r.check("CT04.head_tiles", r.status == 200 and r.header("content-length") and r.header("etag") and r.header("accept-ranges") == "bytes",
                     "200 + Content-Length, ETag, Accept-Ranges: bytes", f"{r.status} {dict((k, r.header(k)) for k in ('content-length', 'etag', 'accept-ranges'))}")
        etag = r.header("etag")
        r = self.c.request("GET", "/tiles/basemap.pmtiles", headers={"If-None-Match": etag or '"x"'})
        self.r.check("CT05.if_none_match_304", r.status == 304, 304, r.status)
        r = self.c.request("GET", "/tiles/basemap.pmtiles", headers={"Range": "bytes=0-126"})
        self.r.check("CT06.accept_ranges_on_206", r.header("accept-ranges") == "bytes", "Accept-Ranges: bytes", r.header("accept-ranges"))
        self.r.check("CT07.no_gzip_on_tiles", r.header("content-encoding") in (None, "identity"), "no Content-Encoding", r.header("content-encoding"))
        size = self.archive_size()
        r = self.c.request("GET", "/tiles/basemap.pmtiles", headers={"Range": f"bytes={size}-"})
        self.r.check("CT08.range_beyond_416", r.status == 416, f"416 for bytes={size}- (S from HEAD)", r.status)
        r = self.c.request("GET", "/tiles/basemap.pmtiles", headers={"Range": "bytes=999999999999-"})
        self.r.check("CT08.range_far_beyond_416", r.status == 416, 416, r.status)
        self.no_repeated_cors_sweep()
        q = urllib.parse.quote(json.dumps(self.c.route_body("P1", "P2")))
        r = self.c.request("GET", "/v1/route?json=" + q)
        self.r.check("CT09.get_route_json_param", r.status == 200 and (r.json() or {}).get("code") == "Ok", "200 Ok", r.status)
        r = self.c.get("/v1/search", {"q": "Сүхб", "lang": "de"})
        self.r.check("CT10.unsupported_lang_400_json", r.status == 400 and r.json() is not None, "400 JSON", f"{r.status} {r.body[:80]!r}")
        r = self.c.get("/v1/search", {"q": "Сүхбаатар", "limit": 50})
        n = len((r.json() or {}).get("features", []))
        self.r.check("CT11.limit_capped_at_20", r.status in (200, 400) and n <= 20, "<= 20 features (or 400)", f"{r.status}, {n}")
        for path in ("/v1/reverse", "/health"):
            r = self.c.request("OPTIONS", path, headers=self.PREFLIGHT)
            self.r.check(f"CT12.preflight_204{path}", r.status == 204, 204, r.status)
        r = self.c.request("OPTIONS", "/v1/route", headers=self.PREFLIGHT)
        exp = self._tokens(r.header("access-control-expose-headers"))
        self.r.check("CT13.preflight_expose_headers", {"content-range", "content-length", "etag", "accept-ranges"} <= exp,
                     "Content-Range, Content-Length, ETag, Accept-Ranges", r.header("access-control-expose-headers"))
        self.r.check("CT14.vary_origin", "origin" in self._tokens(r.header("vary")), "Vary: Origin", r.header("vary"))
        r = self.c.get("/v1/search", {"q": "Сүхб"})
        self.r.check("CT15.search_default_lang_mn", r.status == 200, 200, r.status)

    # ------------------------------------------------------------------ AC 43 (CR 2026-09-29)
    def archive_size(self):
        r = self.c.request("HEAD", "/tiles/basemap.pmtiles")
        try:
            return int(r.header("content-length"))
        except (TypeError, ValueError):
            raise RuntimeError(f"HEAD /tiles/basemap.pmtiles gave no Content-Length ({r.status})")

    def _cors_once(self, cid, r, origin=ORIGIN, expect_acao=True):
        """Access-Control-Allow-Origin / -Expose-Headers exactly once, no Access-Control-* repeated."""
        rep = r.repeated_headers()
        self.r.check(cid + ".no_repeated_access_control_header", not rep, "each Access-Control-* header at most once",
                     {h: [v for k, v in r.raw_headers if k.lower() == h] for h in rep})
        n_acao, n_aceh = r.header_count("access-control-allow-origin"), r.header_count("access-control-expose-headers")
        if expect_acao:
            ok = n_acao == 1 and r.header("access-control-allow-origin") in (origin, "*")
            self.r.check(cid + ".allow_origin_once", ok, f"exactly 1 Access-Control-Allow-Origin ({origin} or *)",
                         f"{n_acao}x {r.header('access-control-allow-origin')!r}")
        else:
            self.r.check(cid + ".no_allow_origin", n_acao == 0, "no Access-Control-Allow-Origin for a disallowed origin",
                         f"{n_acao}x {r.header('access-control-allow-origin')!r}")
        self.r.check(cid + ".expose_headers_once", n_aceh == 1, "exactly 1 Access-Control-Expose-Headers", n_aceh)

    def ac43(self, origin=ORIGIN, expect_acao=True, prefix="AC43"):
        size = self.archive_size()
        self.r.note(prefix + ".archive_size_S", size)
        hdrs = {"Origin": origin, "Range": f"bytes={size}-"}
        for method in ("GET", "HEAD"):
            cid = f"{prefix}.{method}"
            r = self.c.request(method, "/tiles/basemap.pmtiles", headers=hdrs)
            self.r.check(cid + ".status_416", r.status == 416, f"416 for Range: bytes={size}-", r.status)
            self.r.check(cid + ".content_range_unsatisfied", r.header("content-range") == f"bytes */{size}",
                         f"bytes */{size}", r.header("content-range"))
            self._cors_once(cid, r, origin, expect_acao)
            ctype = (r.header("content-type") or "").split(";")[0].strip()
            self.r.check(cid + ".content_type_json", ctype == "application/json", "application/json", r.header("content-type"))
            if method == "GET":
                j = r.json()
                ok = isinstance(j, dict) and j.get("code") == "RangeNotSatisfiable" and isinstance(j.get("message"), str) and j["message"]
                self.r.check(cid + ".json_gateway_error", bool(ok), '{"code":"RangeNotSatisfiable","message":"..."} (GatewayError)',
                             r.body[:120])
        # CR text used bytes=99999999-: satisfiable on the Mongolia archive, so it is not an AC 43 probe (recorded).
        r = self.c.request("GET", "/tiles/basemap.pmtiles", headers={"Origin": origin, "Range": "bytes=99999999-99999999"})
        self.r.note(prefix + ".cr_probe_99999999", f"HTTP {r.status} ({'satisfiable, archive > 95 MB' if r.status == 206 else 'unsatisfiable'})")

    def no_repeated_cors_sweep(self):
        """openapi.yaml 0.2.0: every Access-Control-* header at most once on every response (CT16)."""
        o = {"Origin": ORIGIN}
        cases = [
            ("tiles_206", lambda: self.c.request("GET", "/tiles/basemap.pmtiles", headers={**o, "Range": "bytes=0-126"})),
            ("tiles_head_200", lambda: self.c.request("HEAD", "/tiles/basemap.pmtiles", headers=o)),
            ("tiles_304", lambda: self.c.request("GET", "/tiles/basemap.pmtiles", headers={**o, "If-None-Match": self.c.request(
                "HEAD", "/tiles/basemap.pmtiles").header("etag") or '"x"'})),
            ("tiles_post_405", lambda: self.c.request("POST", "/tiles/basemap.pmtiles", b"x", headers=o)),
            ("tiles_preflight_204", lambda: self.c.request("OPTIONS", "/tiles/basemap.pmtiles", headers=self.PREFLIGHT)),
            ("health_200", lambda: self.c.get("/health", headers=o)),
            ("unknown_404", lambda: self.c.get("/nope", headers=o)),
            ("route_200", lambda: self.c.request("POST", "/v1/route", self.c.route_body("P1", "P2"), headers=o)),
            ("route_400", lambda: self.c.request("POST", "/v1/route", {"costing": "auto"}, headers=o)),
            ("route_delete_405", lambda: self.c.request("DELETE", "/v1/route", headers=o)),
            ("route_413", lambda: self.c.request("POST", "/v1/route", b'{"x":"' + b"a" * (300 * 1024) + b'"}', headers=o)),
            ("search_200", lambda: self.c.get("/v1/search", {"q": "Сүхб"}, headers=o)),
            ("search_400", lambda: self.c.get("/v1/search", {"q": "Сүхб", "lang": "de"}, headers=o)),
            ("reverse_200", lambda: self.c.get("/v1/reverse", {"lat": P["P1"][0], "lon": P["P1"][1]}, headers=o)),
        ]
        for name, fn in cases:
            r = fn()
            rep = r.repeated_headers()
            self.r.check(f"CT16.no_repeated_cors.{name}", r.status != 0 and not rep, "each Access-Control-* header at most once",
                         f"HTTP {r.status}; repeated: {rep}")

    def tiles_missing(self):
        """CT17: run against a gateway whose data/ has no tiles archive (isolated-gateway.sh ... empty dir).
        Guards the AC 43 fix: a location with its own error_page stops inheriting the server-level 404 page."""
        h = self.c.request("HEAD", "/tiles/basemap.pmtiles")
        if h.status != 404:
            return self.r.check("CT17.precondition_archive_missing", False, "HEAD 404 (gateway started without the archive)", h.status)
        for method in ("GET", "HEAD"):
            r = self.c.request(method, "/tiles/basemap.pmtiles", headers={"Origin": ORIGIN, "Range": "bytes=0-126"})
            cid = f"CT17.{method}"
            self.r.check(cid + ".status_404", r.status == 404, 404, r.status)
            self._cors_once(cid, r)
            ctype = (r.header("content-type") or "").split(";")[0].strip()
            self.r.check(cid + ".content_type_json", ctype == "application/json", "application/json", r.header("content-type"))
            if method == "GET":
                j = r.json() or {}
                self.r.check(cid + ".json_not_found", j.get("code") == "NotFound", '{"code":"NotFound",...}', r.body[:120])

    # ------------------------------------------------------------------ F. performance
    def _perf(self, cid, fn, limit_ms, n=20):
        times, bad = [], []
        fn(0)  # warm-up, not timed
        for i in range(n):
            resp = fn(i)
            times.append(resp.elapsed * 1000)
            if resp.status not in (200, 206):
                bad.append(resp.status)
        v = p95(times)
        self.r.check(cid, v <= limit_ms and not bad, f"p95 <= {limit_ms} ms, all 2xx",
                     f"p95 {v:.1f} ms (min {min(times):.1f}, max {max(times):.1f}); non-2xx: {bad}")
        self.r.note(cid + ".p95_ms", round(v, 1))

    def perf(self):
        pairs = [(a, b) for a in ("P1", "P2", "P3", "P4", "P5") for b in ("P1", "P2", "P3", "P4", "P5") if a != b]
        self._perf("AC35.route_p95", lambda i: self.c.route(*pairs[i % len(pairs)]), 500)
        prefixes = ["Сү", "Сүх", "Сүхб", "Сүхба", "Сүхбаа", "Сүхбаат", "Сүхбаата", "Сүхбаатар", "Ул", "Улаан",
                    "Улаанба", "Улаанбаат", "Га", "Ганд", "Гандан", "Зай", "Зайсан", "Их дэлгүү", "Их", "Төмөр зам"]
        self._perf("AC36.search_p95", lambda i: self.c.get("/v1/search", {"q": prefixes[i], **self.BIAS, "lang": "mn"}), 300)
        pts = ["P1", "P2", "P3", "P4", "P5"]
        self._perf("AC37.reverse_p95", lambda i: self.c.get("/v1/reverse", {"lat": P[pts[i % 5]][0], "lon": P[pts[i % 5]][1]}), 300)
        pm = self.pm()
        locs = []
        for z in (10, 11, 12, 13, 14):
            x, y = lonlat_to_tile(*P["P1"], z)
            for dx, dy in ((0, 0), (1, 0), (0, 1), (-1, 0), (0, -1), (1, 1), (-1, -1)):
                loc = pm.tile_location(z, x + dx, y + dy)
                if loc and sum(1 for L in locs if L[0] == z) < 4:
                    locs.append((z, loc))
        self.r.note("AC38.tiles_used", f"{len(locs)} tiles, zooms {sorted({z for z, _ in locs})}")
        self._perf("AC38.tiles_p95", lambda i: self.c.request(
            "GET", "/tiles/basemap.pmtiles", headers={"Range": "bytes=%d-%d" % (locs[i % len(locs)][1][0], sum(locs[i % len(locs)][1]) - 1)}), 100)

    # ------------------------------------------------------------------ docker-backed groups
    def outage(self):
        if not self.compose_dir:
            return self.r.skip("AC34", "needs --compose-dir")
        for svc, broken, others in (("valhalla", ("route", lambda: self.c.route("P1", "P2", timeout=10)),
                                     [("search", lambda: self.c.get("/v1/search", {"q": "Сүхб"})),
                                      ("reverse", lambda: self.c.get("/v1/reverse", {"lat": P["P1"][0], "lon": P["P1"][1]})),
                                      ("tiles", lambda: self.c.request("GET", "/tiles/basemap.pmtiles", headers={"Range": "bytes=0-126"}))]),
                                    ("photon", ("search", lambda: self.c.get("/v1/search", {"q": "Сүхб"}, timeout=10)),
                                     [("route", lambda: self.c.route("P1", "P2")),
                                      ("tiles", lambda: self.c.request("GET", "/tiles/basemap.pmtiles", headers={"Range": "bytes=0-126"}))])):
            st = self.compose("stop", svc)
            if st.returncode != 0:
                self.r.check(f"AC34.{svc}.stop", False, "docker compose stop ok", st.stderr[-200:])
                continue
            try:
                for attempt in ("first", "second"):  # first call after stop, then again (resolver cache)
                    resp = broken[1]()
                    j = resp.json() or {}
                    self.r.check(f"AC34.{svc}_stopped.{broken[0]}_502_503_within_5s.{attempt}",
                                 resp.status in (502, 503) and resp.elapsed <= 5 and j.get("code") == "UpstreamUnavailable",
                                 "502/503 {code: UpstreamUnavailable} in <= 5 s",
                                 f"{resp.status} in {resp.elapsed * 1000:.0f} ms {resp.body[:80]!r}")
                if svc == "photon":
                    resp = self.c.get("/v1/reverse", {"lat": P["P1"][0], "lon": P["P1"][1]}, timeout=10)
                    self.r.check("AC34.photon_stopped.reverse_502_503_within_5s", resp.status in (502, 503) and resp.elapsed <= 5,
                                 "502/503 in <= 5 s", f"{resp.status} in {resp.elapsed * 1000:.0f} ms")
                for name, fn in others:
                    resp = fn()
                    self.r.check(f"AC34.{svc}_stopped.{name}_still_ok", resp.status in (200, 206), "200/206",
                                 f"{resp.status} {resp.body[:60]!r}")
                h = self.c.get("/health")
                self.r.check(f"AC34.{svc}_stopped.health_ok", h.status == 200, 200, h.status)
            finally:
                self.compose("start", svc)
                self._wait_healthy(svc)
            resp = broken[1]()
            self.r.check(f"AC34.{svc}_restarted.{broken[0]}_recovers", resp.status == 200, 200, resp.status)

    def _wait_healthy(self, svc, limit=180):
        t0 = time.time()
        while time.time() - t0 < limit:
            out = self.compose("ps", "--format", "{{.Service}} {{.Health}}", svc).stdout
            if f"{svc} healthy" in out:
                return time.time() - t0
            time.sleep(2)
        return None

    def logs(self):
        """NFR: no PII (coordinates, search text) in service logs. Uses unusual marker values."""
        if not self.compose_dir:
            return self.r.skip("LOG01", "needs --compose-dir")
        since = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 2))
        mlat, mlon = 47.917313, 106.912377  # marker point near P1
        marker_q = "Зайсанмаркер"
        self.c.route((mlat, mlon), "P2")
        q = urllib.parse.quote(json.dumps(self.c.route_body((mlat, mlon), "P2")))
        self.c.request("GET", "/v1/route?json=" + q)
        self.c.get("/v1/search", {"q": marker_q, "lat": mlat, "lon": mlon})
        self.c.get("/v1/reverse", {"lat": mlat, "lon": mlon})
        self.c.request("POST", "/v1/route", {"costing": "auto", "locations": [{"lat": mlat, "lon": mlon}]})  # error path
        time.sleep(1.5)
        out = self.compose("logs", "--no-color", "--since", since, "gateway", "valhalla", "photon").stdout
        needles = ["47.917313", "106.912377", "47.91731", "106.91237", marker_q, urllib.parse.quote(marker_q)]
        hits = [ln[:160] for ln in out.splitlines() if any(n in ln for n in needles)]
        self.r.check("LOG01.no_coordinates_or_query_text_in_logs", not hits, "0 log lines with marker coordinates/text",
                     f"{len(hits)} lines: {hits[:3]}")
        self.r.note("LOG01.log_lines_scanned", len(out.splitlines()))

    def stats(self):
        if not self.compose_dir:
            return self.r.skip("AC39", "needs --compose-dir")
        ids = self.compose("ps", "-q").stdout.split()
        out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}} {{.MemUsage}}"] + ids,
                             capture_output=True, text=True).stdout
        units = {"B": 1, "KiB": 1024, "MiB": 1024 ** 2, "GiB": 1024 ** 3, "kB": 1e3, "MB": 1e6, "GB": 1e9}
        total, parts = 0, []
        for ln in out.splitlines():
            name, mem = ln.split()[0], ln.split()[1]
            m = re.match(r"([\d.]+)(\w+)", mem)
            b = float(m.group(1)) * units[m.group(2)]
            total += b
            parts.append(f"{name}={b / 2 ** 20:.0f} MiB")
        self.r.check("AC39.steady_memory_le_6GB", 0 < total <= 6 * 1024 ** 3, "<= 6 GiB", f"{total / 2 ** 30:.2f} GiB ({', '.join(parts)})")
        du = subprocess.run(["du", "-sb", os.path.join(self.compose_dir, "data")], capture_output=True, text=True).stdout.split()
        size = int(du[0]) if du else -1
        self.r.check("AC39.data_dir_le_10GB", 0 < size <= 10 * 1024 ** 3, "<= 10 GiB", f"{size / 2 ** 30:.2f} GiB")

    # ------------------------------------------------------------------ groups
    def run(self, groups):
        t0 = time.perf_counter()
        if "smoke" in groups or "full" in groups:
            full = "full" in groups
            self.ac28()
            self.ac09()
            if full:
                self.ac10()
                self.ac12()
            self.ac11(info_only=not full)
            self.ac13()
            self.ac14()
            self.ac15()
            self.ac16()
            if full:
                self.ac17()
            self.ac18()
            self.ac19(info_only=not full)
            if full:
                self.ac20()
            self.ac21()
            self.ac22()
            self.ac23()
            self.ac24(info_only=not full)
            self.ac25()
            if full:
                self.ac26()
                self.ac27()
            self.ac29()
            self.ac30()
            self.ac32()
            if full:
                self.ac33()
                self.ac43()
                self.contract_extras()
            elapsed = time.perf_counter() - t0
            self.r.check("AC40.smoke_runtime_le_60s", elapsed <= 60, "<= 60 s", f"{elapsed:.1f} s")
        if "perf" in groups:
            self.perf()
        if "cors-allowlist" in groups:
            self.ac31()
        if "ac43" in groups and "full" not in groups:
            self.ac43()
        if "tiles-missing" in groups:
            self.tiles_missing()
        if "logs" in groups:
            self.logs()
        if "stats" in groups:
            self.stats()
        if "outage" in groups:
            self.outage()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"))
    ap.add_argument("--group", action="append", choices=["smoke", "full", "perf", "ac43", "cors-allowlist", "tiles-missing", "outage", "logs", "stats"])
    ap.add_argument("--compose-dir", help="backend/ directory (for outage, logs, stats)")
    ap.add_argument("--compose-project", help="docker compose project name if not the default")
    ap.add_argument("--json-out", help="write results as JSON here")
    a = ap.parse_args()
    groups = a.group or ["smoke"]
    rep = Report()
    print(f"NAV-001 QA checks: base={a.base_url} groups={','.join(groups)}")
    h = Client(a.base_url).get("/health", timeout=5)
    if h.status != 200:
        rep.check("PRE.gateway_reachable", False, f"200 from {a.base_url}/health", f"{h.status} {h.error or ''}")
    else:
        try:
            Nav001(a.base_url, rep, a.compose_dir, a.compose_project).run(groups)
        except Exception as e:  # never exit 0 on a crash
            rep.check("PRE.suite_crashed", False, "no exception", f"{type(e).__name__}: {e}")
    print("\n" + rep.summary())
    if rep.failed:
        print("Failed checks:\n  " + "\n  ".join(f"{cid}: {d}" for _s, cid, d in rep.failed))
    if a.json_out:
        with open(a.json_out, "w") as f:
            json.dump({"base_url": a.base_url, "groups": groups, "summary": rep.summary(), "info": rep.info,
                       "results": [{"status": s, "id": c, "detail": d} for s, c, d in rep.results]}, f, ensure_ascii=False, indent=1, default=str)
    sys.exit(1 if rep.failed else 0)


if __name__ == "__main__":
    main()

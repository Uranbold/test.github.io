#!/usr/bin/env python3
"""Record the postRoute responses NAV-011 QA needs (story AC 15, 19, 26; ADR-0012 §5; architect request).

    python3 tests/gpx/nav011/record_routes.py [--base-url http://127.0.0.1:8080] [--only nav011-rs1-car-alternates-mn]

  nav011-rs1-car-alternates-mn  openapi 0.5.3 example `androidPreviewCarAlternates` (P1 -> P3, auto, exclude_unpaved,
                                alternates 2, mn-MN): the real k-route response for the AC 19 replay and the
                                single-route-slice test; prints k (AC 15 asks QA to record k).
  nav011-rs3-car-alternates-mn  RS3 is recorded for k only (AC 15); same body shape, P1 -> X1.
  nav011-p4-p5-bike-mn          P4 Gandan -> P5 railway station, bicycle, alternates 2, mn-MN, no costing_options: the
                                route for the story's bicycle track (G10 in the story, renamed G11 on the architect's
                                request; the stand-in G11s is used until this exists).

Checks /health first (must be 200) and never starts, stops or restarts anything. Pacing: 1 request per second
(story limit 2/s). Writes routes/<name>.json (response as received) and <name>.request.json (body sent, no hostnames).
"""
import argparse
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "routes")
P = {"P1": (47.9189, 106.9176), "P3": (47.8858, 106.9173), "P4": (47.9215, 106.8950), "P5": (47.9095, 106.8835),
     "X1": (49.0270, 104.0440)}
ROUTES = {
    # name: (origin, destination, costing, language, avoid_unpaved)
    "nav011-rs1-car-alternates-mn": ("P1", "P3", "auto", "mn-MN", True),
    "nav011-rs3-car-alternates-mn": ("P1", "X1", "auto", "mn-MN", False),
    "nav011-p4-p5-bike-mn": ("P4", "P5", "bicycle", "mn-MN", False),
}


def body(o, d, costing, language, avoid):
    b = {"locations": [{"lat": round(P[o][0], 6), "lon": round(P[o][1], 6)}, {"lat": round(P[d][0], 6), "lon": round(P[d][1], 6)}],
         "costing": costing}
    if costing == "auto" and avoid:
        b["costing_options"] = {"auto": {"exclude_unpaved": True}}
    b.update({"alternates": 2, "format": "osrm", "banner_instructions": True, "voice_instructions": True,
              "units": "kilometers", "language": language})
    return b


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://127.0.0.1:8080"))
    ap.add_argument("--only")
    a = ap.parse_args()
    try:
        with urllib.request.urlopen(a.base_url + "/health", timeout=10) as r:
            if r.status != 200:
                print("health", r.status, "- not recording; wait and retry")
                return 2
    except OSError as e:
        print("health unreachable:", e, "- not recording; wait and retry")
        return 2
    os.makedirs(OUT, exist_ok=True)
    for name, (o, d, costing, lang, avoid) in ROUTES.items():
        if a.only and name != a.only:
            continue
        time.sleep(1.0)
        b = body(o, d, costing, lang, avoid)
        req = urllib.request.Request(a.base_url + "/v1/route", data=json.dumps(b).encode(), method="POST",
                                     headers={"Content-Type": "application/json"})
        t0 = time.monotonic()
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read()
            status = r.status
        ms = round((time.monotonic() - t0) * 1000)
        j = json.loads(raw)
        with open(os.path.join(OUT, name + ".json"), "wb") as f:
            f.write(raw)
        with open(os.path.join(OUT, name + ".request.json"), "w", encoding="utf-8") as f:
            json.dump(b, f, ensure_ascii=False, indent=1)
        routes = j.get("routes") or []
        print(f"{name}: {status} {len(raw)} B {ms} ms k={len(routes)} "
              f"distances={[round(rt.get('distance', 0)) for rt in routes]} "
              f"steps={[sum(len(l['steps']) for l in rt.get('legs', [])) for rt in routes]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Record the postRoute responses QA's NAV-005 GPX replays need (story "GPX replay set"; AC 72).

    python3 tests/gpx/nav005/record_routes.py [--base-url http://127.0.0.1:8080] [--only g9-p1-x1-car-mn]

Sends the exact NAV-005 AC 5 request body (the Android guidance profile, openapi 0.5.1 `postRoute`):
locations (6 decimals), costing, alternates 0, format osrm, banner_instructions, voice_instructions, units kilometers,
language mn-MN / en-US; costing_options only for car + avoid. Pacing: at most 1 request per second (story limit 2/s).
Checks /health first and never starts, stops or restarts anything. Writes tests/gpx/nav005/routes/<name>.json
(the response body as received) and <name>.request.json (the body sent; no hostnames are written).

The routes recorded by the mobile engineer stay where they are (mobile/android/app/src/test/resources/routes/) and
are referenced from tests/gpx/nav005/manifest.json; this script only records what QA added.
"""
import argparse
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "routes")
P = {"P1": (47.9189, 106.9176), "P2": (47.9139, 106.9044), "P3": (47.8858, 106.9173), "X1": (49.0270, 104.0440),
     # G8 English variant: the snapped origin/destination of the mobile engineer's g8-roundabout-car-mn.json
     "G8A": (47.898108, 106.95068), "G8B": (47.930156, 106.90019)}
ROUTES = {
    # name: (origin, destination, costing, language, avoid_unpaved)
    "g9-p1-x1-car-mn": ("P1", "X1", "auto", "mn-MN", False),
    "g8-roundabout-car-en": ("G8A", "G8B", "auto", "en-US", False),
}


def body(o, d, costing, language, avoid):
    b = {"locations": [{"lat": round(P[o][0], 6), "lon": round(P[o][1], 6)}, {"lat": round(P[d][0], 6), "lon": round(P[d][1], 6)}],
         "costing": costing}
    if costing == "auto" and avoid:
        b["costing_options"] = {"auto": {"exclude_unpaved": True}}
    b.update({"alternates": 0, "format": "osrm", "banner_instructions": True, "voice_instructions": True,
              "units": "kilometers", "language": language})
    return b


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://127.0.0.1:8080"))
    ap.add_argument("--only")
    a = ap.parse_args()
    with urllib.request.urlopen(a.base_url + "/health", timeout=10) as r:
        if r.status != 200:
            print("health", r.status)
            return 2
    os.makedirs(OUT, exist_ok=True)
    for name, (o, d, costing, lang, avoid) in ROUTES.items():
        if a.only and name != a.only:
            continue
        time.sleep(1.0)  # <= 1 request per second
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
        with open(os.path.join(OUT, name + ".request.json"), "w") as f:
            json.dump(b, f, ensure_ascii=False, indent=1)
        rt = (j.get("routes") or [{}])[0]
        print(f"{name}: {status} {len(raw)} B {ms} ms distance={rt.get('distance')} steps={sum(len(l['steps']) for l in rt.get('legs', []))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

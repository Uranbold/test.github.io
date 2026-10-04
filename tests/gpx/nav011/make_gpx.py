#!/usr/bin/env python3
"""Generate the NAV-011 QA GPX tracks (story AC 25-26, «Дугуй» guidance) from recorded postRoute responses.

    python3 tests/gpx/nav011/make_gpx.py          # (re)writes tests/gpx/nav011/*.gpx and manifest.json
    python3 tests/gpx/nav011/make_gpx.py --check  # exits 1 if a committed file differs from what would be generated

Same format and helpers as tests/gpx/nav005/make_gpx.py (1 Hz GPX 1.1, real <time> stamps, nav:acc/speed/course).

Track IDs: the story's bicycle track "G10" collides with tests/gpx/nav005/G10.gpx (NAV-005 U-turn track); the architect
asked the BA to rename it (proposal G11). Until the BA confirms and a recorded P4 -> P5 bicycle response exists
(record_routes.py, needs the dev stack at /health 200), QA uses STAND-IN tracks, marked "s":

  G11s  bicycle at 4.5 m/s (16.2 km/h), 1 Hz, accuracy 5 m, deterministic position jitter (sd 2 m, capped 4 m),
        along the recorded P1 -> P2 WALK route geometry (5 manoeuvres incl. two turns 11 m apart -> bike chaining
        <= 60 m, and an arrive 64 m after the last turn). Checks the navigation-ux §4.2 rule 8 band (15-150 m).
  G11sb same start, but at the third turn (manoeuvre 3) the rider goes straight on for 250 m (off-route): the
        reroute request must carry costing bicycle, alternates 0 and no costing_options (AC 25, AC 14, AC 19).

The real G11 (P4 -> P5 by bicycle, story Terms) replaces G11s once recorded; the oracle in
mobile/android/app/src/test/java/mn/navmn/app/qa/QaNav011Test.kt reads this manifest and does not change.
"""
import importlib.util
import json
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
_spec = importlib.util.spec_from_file_location("nav005gpx", os.path.join(REPO, "tests", "gpx", "nav005", "make_gpx.py"))
g = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(g)

WALK_MN = "mobile/android/app/src/test/resources/routes/p1-p2-walk-mn.json"
V = 4.5


def jitter(pts, rnd, sd=2.0, cap=4.0):
    out = []
    for t, p, acc, spd, crs in pts:
        d = max(-cap, min(cap, rnd.gauss(0.0, sd)))
        b = rnd.uniform(0.0, 360.0)
        out.append([t, g.offset(p, b, abs(d)), acc, spd, crs])
    return out


def build():
    files = {}
    manifest = {"description": "NAV-011 QA GPX tracks («Дугуй», AC 25-26). Stand-ins (suffix s) until the BA renames the story's G10 "
                               "(proposal G11) and QA records the P4 -> P5 bicycle response. Paths are relative to the repo root.",
                "generator": "tests/gpx/nav011/make_gpx.py", "tracks": []}
    rnd = random.Random(11011)
    line, steps, along = g.load(WALK_MN)

    def add(tid, desc, pts, extra=None):
        fname = f"{tid}.gpx"
        files[fname] = g.gpx(tid, desc, pts).replace("NAV-005 QA make_gpx.py", "NAV-011 QA make_gpx.py")
        e = {"id": tid, "file": f"tests/gpx/nav011/{fname}", "description": desc, "route": WALK_MN, "mode": "bicycle",
             "lang": "mn", "reroutes": [], "points": len(pts), "duration_s": pts[-1][0] - pts[0][0], "speed_mps": V,
             "stand_in": True}
        if extra:
            e.update(extra)
        manifest["tracks"].append(e)

    pts = jitter(g.drive(line, lambda d: V), rnd)
    add("G11s", "STAND-IN for the story's bicycle track: 4.5 m/s, 1 Hz, acc 5 m, jitter sd 2 m, along the recorded P1 -> P2 walk geometry", pts)

    turn_d = along[3]
    first = jitter(g.drive(line, lambda d: V, 0, 0.0, turn_d - 1), rnd)
    last_p = line.at(turn_d - 1)
    brg = g.bearing(line.at(turn_d - 10), line.at(turn_d - 1))
    off = jitter(g.straight(last_p, brg, 250, V, first[-1][0] + 1), rnd)
    add("G11sb", "STAND-IN off-route by bicycle: as G11s, straight on for 250 m at manoeuvre 3 instead of turning", first + off,
        extra={"wrong_turn_at": [round(last_p[0], 6), round(last_p[1], 6)], "wrong_turn_bearing": round(brg, 1)})
    files["manifest.json"] = json.dumps(manifest, ensure_ascii=False, indent=1) + "\n"
    return files


def main():
    files = build()
    if "--check" in sys.argv:
        stale = [n for n, c in files.items()
                 if not os.path.exists(os.path.join(HERE, n)) or open(os.path.join(HERE, n), encoding="utf-8").read() != c]
        print("stale: " + ", ".join(stale) if stale else "ok")
        return 1 if stale else 0
    for n, c in files.items():
        with open(os.path.join(HERE, n), "w", encoding="utf-8") as f:
            f.write(c)
        print("wrote", n)
    return 0


if __name__ == "__main__":
    sys.exit(main())

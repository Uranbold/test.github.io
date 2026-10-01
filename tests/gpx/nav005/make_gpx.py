#!/usr/bin/env python3
"""Generate the NAV-005 GPX replay set G1-G9 (story "GPX replay set", AC 72) from recorded postRoute responses.

    python3 tests/gpx/nav005/make_gpx.py          # (re)writes tests/gpx/nav005/G*.gpx and manifest.json
    python3 tests/gpx/nav005/make_gpx.py --check  # exits 1 if a committed file differs from what would be generated

Deterministic (fixed seed, fixed start time). Tracks are 1 Hz GPX 1.1 with real <time> stamps (so the 30 s gap of G3
is a real gap) and, per point, the values a phone's GNSS provider would report in an extension block:
  <extensions><nav:acc>5.0</nav:acc><nav:speed>13.9</nav:speed><nav:course>164.8</nav:course></extensions>
acc = horizontal accuracy (m), speed = Doppler speed over ground (m/s), course = bearing (deg). An outlier fix keeps
the true speed and course (that is what a GNSS receiver reports for a position jump). Readers that ignore the
extension see a plain GPX track.

Route sources (the recorded responses; nothing is fetched here):
  mobile/android/app/src/test/resources/routes/*.json   (recorded by the mobile engineer, live dev gateway)
  tests/gpx/nav005/routes/*.json                        (recorded by QA with record_routes.py)
"""
import json
import math
import os
import random
import sys
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
MOBILE_ROUTES = "mobile/android/app/src/test/resources/routes"
QA_ROUTES = "tests/gpx/nav005/routes"
T0 = datetime(2026, 10, 1, 0, 0, 0, tzinfo=timezone.utc)
R = 6371008.8


def decode_polyline(s, precision=6):
    coords, idx, lat, lon, f = [], 0, 0, 0, 10 ** precision
    while idx < len(s):
        for which in (0, 1):
            shift = result = 0
            while True:
                b = ord(s[idx]) - 63
                idx += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            d = ~(result >> 1) if result & 1 else result >> 1
            if which == 0:
                lat += d
            else:
                lon += d
        coords.append((lat / f, lon / f))
    return coords


def dist(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(min(1.0, math.sqrt(h)))


def bearing(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dl = math.radians(b[1] - a[1])
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def offset(p, brg, m):
    d = m / R
    t = math.radians(brg)
    p1, l1 = math.radians(p[0]), math.radians(p[1])
    p2 = math.asin(math.sin(p1) * math.cos(d) + math.cos(p1) * math.sin(d) * math.cos(t))
    l2 = l1 + math.atan2(math.sin(t) * math.sin(d) * math.cos(p1), math.cos(d) - math.sin(p1) * math.sin(p2))
    return (math.degrees(p2), math.degrees(l2))


class Line:
    def __init__(self, pts):
        self.pts = pts
        self.cum = [0.0]
        for a, b in zip(pts, pts[1:]):
            self.cum.append(self.cum[-1] + dist(a, b))
        self.length = self.cum[-1]

    def at(self, d):
        d = max(0.0, min(d, self.length))
        lo, hi = 0, len(self.cum) - 1
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if self.cum[mid] <= d:
                lo = mid
            else:
                hi = mid
        seg = self.cum[hi] - self.cum[lo]
        f = 0.0 if seg == 0 else (d - self.cum[lo]) / seg
        a, b = self.pts[lo], self.pts[hi]
        return (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)

    def course(self, d):
        a = self.at(max(0.0, d - 3))
        b = self.at(min(self.length, d + 3))
        return bearing(a, b) if dist(a, b) > 0.5 else None


def load(rel):
    with open(os.path.join(REPO, rel), encoding="utf-8") as f:
        j = json.load(f)
    rt = j["routes"][0]
    line = Line(decode_polyline(rt["geometry"]))
    steps = [s for leg in rt["legs"] for s in leg["steps"]]
    # Manoeuvre positions along the geometry: cumulative step distances, scaled to the decoded length.
    total = sum(s["distance"] for s in steps) or 1.0
    k = line.length / total
    along, c = [], 0.0
    for s in steps:
        along.append(c * k)
        c += s["distance"]
    return line, steps, along


def speed_profile(cruise, slow, ramp, maneuvers):
    """Speed (m/s) at distance d: cruise, slowing to `slow` near manoeuvres (turns), linear ramp of `ramp` m/s per m."""
    turns = [a for a in maneuvers[1:]]

    def v(d):
        near = min((abs(d - a) for a in turns), default=1e9)
        return min(cruise, slow + ramp * near)
    return v


def drive(line, v, t0=0, d0=0.0, d1=None, acc=5.0):
    """1 Hz points along `line` from d0 to d1 with speed v(d). Returns [(t, (lat, lon), acc, speed, course)]."""
    d1 = line.length if d1 is None else d1
    out, d, t = [], d0, t0
    while d <= d1 + 1e-6:
        s = v(d)
        out.append([t, line.at(d), acc, s, line.course(d)])
        d += s
        t += 1
    return out


def straight(p, brg, meters, speed, t0):
    out, d, t = [], speed, t0
    while d <= meters + 1e-6:
        out.append([t, offset(p, brg, d), 5.0, speed, brg])
        d += speed
        t += 1
    return out


def gpx(name, desc, pts):
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<gpx version="1.1" creator="NAV-005 QA make_gpx.py" xmlns="http://www.topografix.com/GPX/1/1" '
             'xmlns:nav="urn:navmn:qa:gpx-ext:1">',
             f'  <metadata><name>{name}</name><desc>{desc}</desc><time>{T0.strftime("%Y-%m-%dT%H:%M:%SZ")}</time></metadata>',
             f'  <trk><name>{name}</name><trkseg>']
    for t, p, acc, spd, crs in pts:
        ts = (T0 + timedelta(seconds=t)).strftime("%Y-%m-%dT%H:%M:%SZ")
        ext = f"<nav:acc>{acc:.1f}</nav:acc><nav:speed>{spd:.2f}</nav:speed>"
        if crs is not None:
            ext += f"<nav:course>{crs:.1f}</nav:course>"
        lines.append(f'    <trkpt lat="{p[0]:.7f}" lon="{p[1]:.7f}"><time>{ts}</time><extensions>{ext}</extensions></trkpt>')
    lines += ["  </trkseg></trk>", "</gpx>", ""]
    return "\n".join(lines)


def build():
    files, manifest = {}, {"description": "NAV-005 GPX replay set (story G1-G9) and QA variants. Paths are relative to the repo root. "
                                          "route = the previewed route the replay starts with; reroutes = recorded responses served in order to reroute requests.",
                           "generator": "tests/gpx/nav005/make_gpx.py", "tracks": []}
    rnd = random.Random(5005)
    car_mn = f"{MOBILE_ROUTES}/p1-p3-car-mn.json"
    line, steps, along = load(car_mn)
    car_v = speed_profile(cruise=60 / 3.6 - 2.0, slow=6.0, ramp=0.08, maneuvers=along)  # <= 60 km/h (≈ 52 km/h cruise)

    def add(tid, desc, pts, route, mode="car", lang="mn", reroutes=(), extra=None):
        fname = f"{tid}.gpx"
        files[fname] = gpx(tid, desc, pts)
        entry = {"id": tid, "file": f"tests/gpx/nav005/{fname}", "description": desc, "route": route, "mode": mode,
                 "lang": lang, "reroutes": list(reroutes), "points": len(pts), "duration_s": pts[-1][0] - pts[0][0]}
        if extra:
            entry.update(extra)
        manifest["tracks"].append(entry)

    # G1: P1 -> P3 by car on the route, <= 60 km/h.
    g1 = drive(line, car_v)
    add("G1", "P1 -> P3 by car on the route, <= 60 km/h, slowing to ~22 km/h at turns", g1, car_mn,
        extra={"also_en": f"{MOBILE_ROUTES}/p1-p3-car-en.json"})

    # G2: wrong turn at the first turn (straight on instead of left), >= 300 m on the other road, then the new route.
    with open(os.path.join(REPO, MOBILE_ROUTES, "g2-offroute-point.json")) as f:
        meta = json.load(f)
    turn_d = along[1]
    first = drive(line, car_v, 0, 0.0, turn_d - 1)
    turn = tuple(meta["turn"])
    brg = meta["bearing"]
    rr_rel = f"{MOBILE_ROUTES}/g2-reroute-car-mn.json"
    rline, rsteps, ralong = load(rr_rel)
    # Drive straight on (the road the new route also uses) to the reroute route's start, then follow the new route.
    start_new = rline.pts[0]
    wrong = straight(turn, brg, dist(turn, start_new), 10.0, first[-1][0] + 1)
    rv = speed_profile(cruise=13.0, slow=6.0, ramp=0.08, maneuvers=ralong)
    rest = drive(rline, rv, wrong[-1][0] + 1 if wrong else first[-1][0] + 1, 0.0)
    g2 = first + wrong + rest
    add("G2", "P1 -> P3 by car; at the first turn (left onto Dunjingarav st.) goes straight on Zaisan st.; the reroute route "
              "follows Zaisan st. for 541 m (>= 300 m on another road) and then reaches P3", g2, car_mn, reroutes=[rr_rel],
        extra={"wrong_turn_at": list(turn), "wrong_turn_bearing": brg})

    # G3: 30 s gap in fixes mid-route (on the first long step), resuming 400 m further along.
    v3 = 400.0 / 30.0
    all3 = drive(line, lambda d: v3)
    gap_from = 100
    g3 = all3[:gap_from] + all3[gap_from + 30:]
    add("G3", "P1 -> P3 by car at 48 km/h; no fixes for 30 s mid-route (tunnel/underpass); fixes resume 400 m further along",
        g3, car_mn, extra={"gap_start_s": all3[gap_from][0], "gap_s": 30, "gap_m": 400})

    # G3b (QA variant): the 30 s gap covers the left turn, so a manoeuvre is passed during the loss (AC 52).
    gap_b = math.ceil((along[1] - 250.0) / v3)  # 250 m before the turn; resumes 150 m after it
    g3b = all3[:gap_b] + all3[gap_b + 30:]
    add("G3b", "QA variant of G3: the 30 s gap covers the left turn (manoeuvre passed while GPS is lost, AC 52)", g3b, car_mn,
        extra={"gap_start_s": all3[gap_b][0], "gap_s": 30, "gap_m": 400})

    # G4: last 500 m to P3, then stationary ~10 m from the route end for 30 s (with +-2 m jitter).
    L = line.length
    g4a = drive(line, lambda d: 8.0, 0, L - 500, L - 10)
    stop = line.at(L - 10)
    g4b = []
    for i in range(1, 31):
        j = offset(stop, rnd.uniform(0, 360), rnd.uniform(0, 2.0))
        g4b.append([g4a[-1][0] + i, j, 5.0, 0.0, None])
    add("G4", "Last 500 m to P3 at 29 km/h, then stationary 10 m before the route end for 30 s (+-2 m jitter)", g4a + g4b, car_mn)

    # G5: P1 -> P2 on foot at 1.4 m/s.
    walk = f"{MOBILE_ROUTES}/p1-p2-walk-mn.json"
    wl, _, _ = load(walk)
    add("G5", "P1 -> P2 on foot at 1.4 m/s", drive(wl, lambda d: 1.4), walk, mode="walk",
        extra={"also_en": f"{MOBILE_ROUTES}/p1-p2-walk-en.json"})

    # G6: G1 with one outlier fix 80 m off the route (perpendicular), on the long straight.
    g6 = [list(p) for p in g1]
    i6 = 60
    g6[i6][1] = offset(g1[i6][1], (g1[i6][4] or 0) + 90, 80.0)
    add("G6", "G1 with one outlier fix 80 m off the route (true speed/course kept)", g6, car_mn, extra={"outlier_index": i6})

    # G7: G1 with 20 s of 60 m-accuracy fixes drifting from 4 m to 80 m off the route.
    g7 = [list(p) for p in g1]
    for i in range(20):
        k7 = 60 + i
        g7[k7][1] = offset(g1[k7][1], (g1[k7][4] or 0) + 90, 4.0 * (i + 1))
        g7[k7][2] = 60.0
    add("G7", "G1 with 20 s of fixes with accuracy 60 m drifting up to 80 m off the route", g7, car_mn,
        extra={"poor_from_index": 60, "poor_count": 20})

    # G8: UB car route with rotary steps exit 2 (Ikh Toiruu), recorded live.
    g8 = f"{MOBILE_ROUTES}/g8-roundabout-car-mn.json"
    l8, _, a8 = load(g8)
    add("G8", "UB car route over Ikh Toiruu with two rotary steps (exit 2) at <= 50 km/h", drive(l8, speed_profile(13.0, 6.0, 0.08, a8)), g8,
        extra={"also_en": f"{QA_ROUTES}/g8-roundabout-car-en.json"})

    # G9: the first 20 km of P1 -> X1 at 80 km/h (slower only at the first turn and the roundabout).
    g9 = f"{QA_ROUTES}/g9-p1-x1-car-mn.json"
    l9, _, a9 = load(g9)
    add("G9", "First 20 km of P1 -> X1 (Peace Ave west, roundabout exit 2 onto the Darkhan road) at 80 km/h",
        drive(l9, speed_profile(80 / 3.6, 8.0, 0.1, a9), 0, 0.0, 20_000.0), g9, extra={"partial": True})

    files["manifest.json"] = json.dumps(manifest, ensure_ascii=False, indent=1) + "\n"
    return files


def main():
    check = "--check" in sys.argv
    files = build()
    bad = 0
    for name, text in files.items():
        path = os.path.join(HERE, name)
        if check:
            cur = open(path, encoding="utf-8").read() if os.path.exists(path) else None
            if cur != text:
                print("stale:", name)
                bad += 1
        else:
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
    if not check:
        for t in json.loads(files["manifest.json"])["tracks"]:
            print(f'{t["id"]:4} {t["points"]:5} pts {t["duration_s"]:5} s  {t["description"][:90]}')
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

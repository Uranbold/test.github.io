#!/usr/bin/env python3
"""NAV-020 dev-container test helper (test projects only; never staging). Seeds NAV-006-shaped slots from the test
root's input files instead of running a full rebuild (the dev container has too little disk for Planetiler), switches
them in like NAV-006 does, and provides the AC 14 manifest read loop.

    nav020_test.py --env-file ROOT/nav020.env seed SLOT_ID        # complete slot from ROOT/input (Photon imported once)
    nav020_test.py --env-file ROOT/nav020.env activate SLOT_ID    # free lane on the slot, pointer rename, old lane stop
    ... seed --light SLOT_ID / activate --keep-lane SLOT_ID       # low-disk variant: no Photon index copy, and the
        pointer names the new slot while the running lane keeps serving the previous (byte-identical) slot data.
        Enough for pack tests that need new slot IDs (AC 14 read loop); not a NAV-006 switch.
    nav020_test.py --env-file ROOT/nav020.env delete-slot SLOT_ID # what NAV-006 cleanup does to an old slot (AC 17)
    nav020_test.py --env-file ROOT/nav020.env read-loop --seconds N [--rate 12] [--out FILE]   # AC 14 (stop: SIGTERM)

Refuses the project navmn. Takes the pipeline lock for seed/activate/delete-slot (like the pipeline does).
"""
import argparse
import hashlib
import json
import os
import shutil
import signal
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import nav_pipeline as base  # noqa: E402


def pipeline(env):
    cfg = base.Config(env).load()
    if cfg.project in ("navmn", ""):
        sys.exit("refusing: project navmn is the shared dev stack")
    p = base.Pipeline(cfg, "nav020-test")
    p.lay.ensure()
    return cfg, p


def seed(cfg, p, slot_id, light=False):
    if not base.SLOT_RE.match(slot_id):
        sys.exit("SLOT_ID must look like 20261004T193412Z")
    lay = p.lay
    root = Path(cfg.root).parent
    inp = root / "input"
    part = lay.slots / f"{slot_id}.partial"
    if lay.slot(slot_id).exists() or part.exists():
        sys.exit(f"slot {slot_id} exists")
    t0 = time.monotonic()
    for d in ("tiles", "valhalla", "sources", "tools"):
        (part / d).mkdir(parents=True)
    os.chmod(part, 0o755)
    os.link(inp / "basemap.pmtiles", part / "tiles" / "basemap.pmtiles")
    shutil.copyfile(inp / "tiles.complete", part / "tiles" / ".complete")
    os.link(inp / "valhalla_tiles.tar", part / "valhalla" / "valhalla_tiles.tar")
    shutil.copyfile(inp / "valhalla.json", part / "valhalla" / "valhalla.json")
    shutil.copyfile(inp / "valhalla.complete", part / "valhalla" / ".complete")
    shutil.copyfile(inp / "photon-dump.jsonl.zst", part / "sources" / "photon-dump")
    for s in ("photon-dump.sha256", "photon-dump.source", "photon-dump.last-modified"):
        if (inp / s).exists():
            shutil.copyfile(inp / s, part / "sources" / s)
    for j in ("photon.jar", "aircompressor.jar"):
        os.link(inp / "tools" / j, part / "tools" / j)
    info = json.loads((inp / "build-info.json").read_text())
    info["built_at"] = base.iso()
    info["nav020_test_seed"] = f"seeded from the dev stack artefacts by nav020_test.py (slot {slot_id})"
    (part / "build-info.json").write_text(json.dumps(info, indent=2) + "\n")
    # Photon index: copy a complete one from another seeded slot, else import (photon-import builder, ~1 min).
    donors = [d for d in sorted(lay.slots.iterdir()) if base.SLOT_RE.match(d.name) and (d / "photon" / ".complete").is_file()]
    how = None
    if light:
        how = "none (--light: not servable by a lane)"
    elif donors:
        r = base.Proc.run(["cp", "-a", donors[-1] / "photon", part / "photon"], timeout=600)
        if r.returncode != 0:
            sys.exit(f"copying the Photon index failed: {r.stderr[-200:]}")
        how = f"copied from {donors[-1].name}"
    else:
        base.symlink_atomic(lay.lanes / "build", f"../slots/{slot_id}.partial")
        r = p.compose("run", "--rm", "-T", "--no-deps", "--name", f"{cfg.project}-photon-import-{slot_id.lower()}",
                      "photon-import", timeout=1800)
        os.unlink(lay.lanes / "build")
        if r.returncode != 0 or not (part / "photon" / ".complete").is_file():
            sys.exit(f"photon-import failed: {(r.stderr or r.stdout)[-400:]}")
        how = "imported"
    base.atomic_write(part / ".slot-complete", json.dumps({"slot": slot_id, "run_id": "nav020-test-seed",
                                                           "completed_at": base.iso()}) + "\n")
    os.rename(part, lay.slot(slot_id))
    base.fsync_dir(lay.slots)
    print(json.dumps({"job": "nav020-test", "cmd": "seed", "slot": slot_id, "photon": how,
                      "seconds": round(time.monotonic() - t0, 1)}))


def activate(cfg, p, slot_id, keep_lane=False):
    lay = p.lay
    if not lay.complete(slot_id):
        sys.exit(f"slot {slot_id} is not complete")
    old = lay.pointer()
    t0 = time.monotonic()
    if keep_lane:
        if not old:
            sys.exit("--keep-lane needs an active slot with a running lane")
        lane = old["lane"]
        with p.critical():
            base.write_pointer(lay.ptr_public, slot_id, lane)
            st = base.load_state(lay)
            st["previous"], st["active"] = old["slot"], slot_id
            base.save_state(lay, st)
        print(json.dumps({"job": "nav020-test", "cmd": "activate", "slot": slot_id, "lane": lane, "keep_lane": True,
                          "previous": old["slot"], "seconds": round(time.monotonic() - t0, 1)}))
        return
    lane = ("green" if old["lane"] == "blue" else "blue") if old else "blue"
    ok, msg = p.start_lane(lane, slot_id, recreate=True, timeout=300)
    if not ok:
        sys.exit(f"lane {lane} did not become healthy: {msg}")
    with p.critical():
        base.write_pointer(lay.ptr_public, slot_id, lane)
        st = base.load_state(lay)
        st["previous"] = old["slot"] if old else None
        st["active"] = slot_id
        st.pop("post_switch_pending", None)
        base.save_state(lay, st)
    if p.container_state("gateway") != "running":
        p.compose("up", "-d", "--no-deps", "--wait", "--wait-timeout", "90", "gateway", timeout=150)
    if old:
        p.stop_lane(old["lane"])
    print(json.dumps({"job": "nav020-test", "cmd": "activate", "slot": slot_id, "lane": lane,
                      "previous": old["slot"] if old else None, "seconds": round(time.monotonic() - t0, 1)}))


def delete_slot(cfg, p, slot_id):
    lay = p.lay
    ptr = lay.pointer()
    if ptr and ptr["slot"] == slot_id:
        sys.exit("refusing: that slot is active")
    base.rmtree(lay.slot(slot_id))
    st = base.load_state(lay)
    if st.get("previous") == slot_id:
        st["previous"] = None
        base.save_state(lay, st)
    print(json.dumps({"job": "nav020-test", "cmd": "delete-slot", "slot": slot_id}))


def read_loop(cfg, seconds, rate, out):
    """AC 14: GET /packs/mn/manifest.json at >= 10 r/s; every response must be complete, valid JSON."""
    url = cfg.public_url + "/packs/mn/manifest.json"
    gap = 1.0 / rate
    end = time.monotonic() + seconds
    statuses, seen = {}, {}
    first = time.monotonic()

    def stop(_signum, _frame):
        raise KeyboardInterrupt
    # A background job of a non-interactive shell starts with SIGINT ignored: stop on SIGTERM or SIGINT.
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        _loop(url, gap, end, statuses, seen)
    except KeyboardInterrupt:    # SIGINT ends the loop early and still writes the result
        pass
    bad = statuses.pop("invalid", 0)
    n = sum(statuses.values())
    dur = time.monotonic() - first
    res = {"job": "nav020-test", "cmd": "read-loop", "requests": n, "seconds": round(dur, 1),
           "rate_rps": round(n / dur, 1) if dur else None, "statuses": statuses, "invalid_or_partial": bad,
           "distinct_manifests": len(seen), "manifest_sha256": sorted(seen)}
    text = json.dumps(res)
    if out:
        Path(out).write_text(text + "\n")
    print(text)
    return 0 if bad == 0 else 1


def _loop(url, gap, end, statuses, seen):
    while time.monotonic() < end:
        t = time.monotonic()
        try:
            with urllib.request.urlopen(url, timeout=5) as r:
                body = r.read()
                st = r.status
                clen = r.headers.get("Content-Length")
        except urllib.error.HTTPError as e:
            body, st, clen = e.read(), e.code, None
        except (urllib.error.URLError, OSError):
            body, st, clen = b"", 0, None
        statuses[st] = statuses.get(st, 0) + 1
        if st == 200:
            ok = clen is None or int(clen) == len(body)
            try:
                json.loads(body)
            except ValueError:
                ok = False
            if not ok:
                statuses["invalid"] = statuses.get("invalid", 0) + 1
            h = hashlib.sha256(body).hexdigest()
            seen[h] = seen.get(h, 0) + 1
        time.sleep(max(0.0, gap - (time.monotonic() - t)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--env-file", required=True)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for c in ("seed", "activate", "delete-slot"):
        sp = sub.add_parser(c)
        sp.add_argument("slot")
        if c == "seed":
            sp.add_argument("--light", action="store_true", help="no Photon index (pack tests only)")
        if c == "activate":
            sp.add_argument("--keep-lane", action="store_true", help="pointer only; the running lane keeps serving")
    rl = sub.add_parser("read-loop")
    rl.add_argument("--seconds", type=float, default=60)
    rl.add_argument("--rate", type=float, default=12)
    rl.add_argument("--out")
    a = ap.parse_args()
    cfg, p = pipeline(a.env_file)
    if a.cmd == "read-loop":
        return read_loop(cfg, a.seconds, a.rate, a.out)
    p.acquire_lock()
    if a.cmd == "seed":
        seed(cfg, p, a.slot, light=a.light)
    elif a.cmd == "activate":
        activate(cfg, p, a.slot, keep_lane=a.keep_lane)
    else:
        delete_slot(cfg, p, a.slot)
    return 0


if __name__ == "__main__":
    sys.exit(main())

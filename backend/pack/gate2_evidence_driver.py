#!/usr/bin/env python3
"""NAV-020 Gate 2 EVIDENCE driver (test projects only; never the gate).

Runs inside the server's pinned Valhalla image (VALHALLA_IMAGE, 3.9.0) with `--network none`, reads
{"id": ..., "body": {...}} lines on stdin and writes {"id": ..., "response": "<raw JSON string>"} lines on stdout,
exactly like the Gate 2 engine driver (backend/gate2/driver.cpp). Errors are returned in the valhalla-mobile
wrapper's envelope {"code": <valhalla error code>, "message": ...} (src/wrapper/main.cpp error_json at 0.6.3).

Why it exists: the Gate 2 engine image (backend/gate2/Dockerfile, valhalla-mobile 0.6.3 = Valhalla 3.6.3) needs a
6-12 GB build that this dev container cannot hold (task file §4.3). This driver lets the pack step, the runner and
the comparator run end to end in the NAV-020 test project. Because it uses the SAME library as the server, a pass
says nothing about the phone engine (ADR-0017 §4, NAV-020 AC 35). nav_pack.py honours it only with
PACK_GATE2_MODE=evidence, REBUILD_ALLOW_TEST_FAULTS=1 and a Compose project other than navmn, and logs every
such run as "gate 2: evidence only, NOT the Gate 2 engine".

    docker run --rm -i --network none -v <routing.tar>:/data/routing.tar:ro -v <config>:/data/config.json:ro \
        -v backend/pack/gate2_evidence_driver.py:/driver.py:ro --entrypoint python3 <VALHALLA_IMAGE> /driver.py
"""
import json
import sys

from valhalla import Actor, ValhallaError  # noqa: E402  (present in the pinned Valhalla image)


def main():
    actor = Actor(sys.argv[1] if len(sys.argv) > 1 else "/data/config.json")
    out = sys.stdout
    for line in sys.stdin:
        if not line.strip():
            continue
        req = json.loads(line)
        rid = req.get("id")
        try:
            resp = actor.route(json.dumps(req["body"]))
            out.write(json.dumps({"id": rid, "response": resp}) + "\n")
        except ValhallaError as e:
            env = json.dumps({"code": int(getattr(e, "code", -1)), "message": str(getattr(e, "message", e))})
            out.write(json.dumps({"id": rid, "response": env}) + "\n")
        except Exception as e:  # noqa: BLE001 - one bad request must not stop the set
            out.write(json.dumps({"id": rid, "error": f"{type(e).__name__}: {e}"}) + "\n")
        out.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())

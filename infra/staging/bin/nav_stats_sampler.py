#!/usr/bin/env python3
"""NAV-008 AC 16 memory sampler (the `make stats` equivalent for staging). Owner: backend-engineer.

Called by nav-stats-sampler.sh (which resolves the compose project, state dir and data dir). Stdlib only.

Every --interval seconds (default 2, AC 16 needs <= 3) it writes one line per container of the compose project
plus one host line to a TSV file:
    epoch_s  kind  service  mem_mb        kind = host | build | service
Container memory = cgroup usage minus inactive file cache, the same number `docker stats` / `make stats` show.
Only service names and byte counts are recorded: no request data, no addresses.

Summary (printed as JSON and written to <out>.summary.json):
    peak_build_mb      max over samples of the summed memory of the one-shot builders running at that moment
    steady_mb          summed memory of the long-running services after the build (the samples taken after
                       COMMAND ended; otherwise the last samples in which no builder ran)
    ac16_ratio         (peak_build_mb + steady_mb) / host RAM; AC 16 passes at <= 0.70
    free_disk_gb       free space on the data filesystem when the summary is made; AC 16 passes at >= 50
    max_gap_s          largest gap between two samples (must stay <= 3 s)
"""
import argparse
import datetime
import json
import os
import shutil
import signal
import subprocess
import sys
import time

BUILDERS = {"data-fetch", "tiles-build", "valhalla-build", "photon-import", "build-info"}
LIMIT_RATIO = 0.70
LIMIT_FREE_GB = 50
LIMIT_GAP_S = 3.0


def log(level, msg, **kv):
    rec = {"ts": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
           "job": "nav-stats-sampler", "level": level, "msg": msg}
    rec.update({k: str(v) for k, v in kv.items()})
    print(json.dumps(rec), file=sys.stderr, flush=True)


def meminfo():
    vals = {}
    with open("/proc/meminfo") as f:
        for line in f:
            k, v = line.split(":", 1)
            vals[k] = int(v.split()[0]) * 1024
    return vals["MemTotal"], vals["MemTotal"] - vals.get("MemAvailable", vals.get("MemFree", 0))


def _read_int(path):
    with open(path) as f:
        return int(f.read().strip())


def _stat_value(path, key):
    with open(path) as f:
        for line in f:
            k, _, v = line.partition(" ")
            if k == key:
                return int(v)
    return 0


def cgroup_mem(cid):
    """Container memory like `docker stats`: usage - inactive_file (cgroup v2) / total_inactive_file (v1)."""
    v2 = [f"/sys/fs/cgroup/system.slice/docker-{cid}.scope", f"/sys/fs/cgroup/docker/{cid}"]
    for d in v2:
        if os.path.exists(f"{d}/memory.current"):
            return max(0, _read_int(f"{d}/memory.current") - _stat_value(f"{d}/memory.stat", "inactive_file"))
    v1 = [f"/sys/fs/cgroup/memory/system.slice/docker-{cid}.scope", f"/sys/fs/cgroup/memory/docker/{cid}"]
    for d in v1:
        if os.path.exists(f"{d}/memory.usage_in_bytes"):
            return max(0, _read_int(f"{d}/memory.usage_in_bytes") - _stat_value(f"{d}/memory.stat", "total_inactive_file"))
    return None


_UNITS = {"b": 1, "kib": 1024, "mib": 1024 ** 2, "gib": 1024 ** 3, "tib": 1024 ** 4,
          "kb": 1000, "mb": 1000 ** 2, "gb": 1000 ** 3, "tb": 1000 ** 4}


def docker_stats_mem(cids):
    """Fallback when the cgroup files are not where we expect them (slower: about 1-2 s per call)."""
    out = subprocess.run(["docker", "stats", "--no-stream", "--no-trunc", "--format", "{{.ID}} {{.MemUsage}}", *cids],
                         capture_output=True, text=True, timeout=30).stdout
    res = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 2:
            continue
        num = parts[1]
        i = len(num)
        while i and not (num[i - 1].isdigit() or num[i - 1] == "."):
            i -= 1
        try:
            res[parts[0]] = int(float(num[:i]) * _UNITS.get(num[i:].lower(), 1))
        except ValueError:
            pass
    return res


def containers(project):
    out = subprocess.run(["docker", "ps", "--no-trunc", "--filter", f"label=com.docker.compose.project={project}",
                          "--format", '{{.ID}}\t{{.Label "com.docker.compose.service"}}'],
                         capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip()[:200])
    return [tuple(line.split("\t", 1)) for line in out.stdout.splitlines() if "\t" in line]


def sample(project, fh):
    now = time.time()
    total, used = meminfo()
    rows = [(now, "host", "host", used), (now, "host_total", "host", total)]
    ctrs = containers(project)
    mem = {cid: cgroup_mem(cid) for cid, _ in ctrs}
    missing = [cid for cid, m in mem.items() if m is None]
    if missing:
        mem.update(docker_stats_mem(missing))
    for cid, svc in ctrs:
        if mem.get(cid) is not None:
            rows.append((now, "build" if svc in BUILDERS else "service", svc, mem[cid]))
    for t, kind, svc, b in rows:
        fh.write(f"{t:.3f}\t{kind}\t{svc}\t{b / 1048576:.1f}\n")
    fh.flush()
    return bool(missing)


def summarize(path, data_dir, command_end=None):
    samples = {}
    with open(path) as f:
        for line in f:
            if line.startswith("#"):
                if line.startswith("# command_end="):
                    command_end = float(line.split("=", 1)[1].split()[0])
                continue
            t, kind, svc, mb = line.rstrip("\n").split("\t")
            s = samples.setdefault(t, {"t": float(t), "host": 0.0, "total": 0.0, "build": 0.0, "service": 0.0,
                                       "builders": set()})
            mb = float(mb)
            if kind == "host":
                s["host"] = mb
            elif kind == "host_total":
                s["total"] = mb
            else:
                s[kind] += mb
                if kind == "build":
                    s["builders"].add(svc)
    ordered = sorted(samples.values(), key=lambda s: s["t"])
    if not ordered:
        raise SystemExit(f"no samples in {path}")
    gaps = [b["t"] - a["t"] for a, b in zip(ordered, ordered[1:])]
    peak = max(ordered, key=lambda s: s["build"])
    after, steady_basis = [], "max over the run (no sample without builders)"
    if command_end is not None:
        after = [s for s in ordered if s["t"] > command_end and not s["builders"]]
        steady_basis = "samples after the command"
    if not after:
        after = [s for s in ordered if not s["builders"]][-5:]
        steady_basis = "last samples without builders" if after else "max over the run (no sample without builders)"
    steady = max((s["service"] for s in after), default=max(s["service"] for s in ordered))
    mem_total = max(s["total"] for s in ordered)
    ratio = (peak["build"] + steady) / mem_total if mem_total else None
    free_gb = shutil.disk_usage(data_dir).free / 1024 ** 3 if os.path.isdir(data_dir) else None
    res = {
        "file": path,
        "samples": len(ordered),
        "duration_s": round(ordered[-1]["t"] - ordered[0]["t"], 1),
        "max_gap_s": round(max(gaps), 2) if gaps else None,
        "host_ram_mb": round(mem_total),
        "peak_build_mb": round(peak["build"]),
        "peak_build_at": datetime.datetime.fromtimestamp(peak["t"], datetime.timezone.utc).strftime("%H:%M:%SZ"),
        "peak_build_services": sorted(peak["builders"]),
        "steady_mb": round(steady),
        "steady_basis": steady_basis,
        "peak_containers_total_mb": round(max(s["build"] + s["service"] for s in ordered)),
        "peak_host_used_mb": round(max(s["host"] for s in ordered)),
        "ac16_ratio": round(ratio, 3) if ratio is not None else None,
        "ac16_memory_pass": ratio is not None and ratio <= LIMIT_RATIO,
        "free_disk_gb": round(free_gb, 1) if free_gb is not None else None,
        "ac16_disk_pass": free_gb is not None and free_gb >= LIMIT_FREE_GB,
        "interval_pass": bool(gaps) and max(gaps) <= LIMIT_GAP_S,
    }
    with open(path + ".summary.json", "w") as f:
        json.dump(res, f, indent=2)
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--project", required=True)
    ap.add_argument("--data-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--interval", type=float, default=2.0)
    ap.add_argument("--after", type=int, default=5, help="steady-state samples after COMMAND ends")
    ap.add_argument("--summary", action="store_true", help="only summarise an existing --out file")
    ap.add_argument("command", nargs=argparse.REMAINDER)
    a = ap.parse_args()
    cmd = a.command[1:] if a.command[:1] == ["--"] else a.command

    if a.summary:
        print(json.dumps(summarize(a.out, a.data_dir), indent=2))
        return 0
    if not 0.5 <= a.interval <= LIMIT_GAP_S:
        ap.error(f"--interval must be between 0.5 and {LIMIT_GAP_S} s (AC 16: sampled every <= 3 s)")

    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    stop = {"flag": False}

    def on_signal(signum, _frame):
        stop["flag"] = True
        if child is not None and child.poll() is None:
            child.send_signal(signum)

    child = None
    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)
    fallback_warned = False
    command_end = None
    rc = 0
    with open(a.out, "a") as fh:
        fh.write(f"# nav-stats-sampler project={a.project} interval={a.interval} started="
                 f"{datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds')}"
                 f" command={' '.join(cmd) or '-'}\n")
        log("info", "sampling started", project=a.project, interval_s=a.interval, out=a.out,
            mode="command" if cmd else "until Ctrl-C")
        if cmd:
            child = subprocess.Popen(cmd)
        after_left = a.after
        nxt = time.monotonic()
        while True:
            try:
                if sample(a.project, fh) and not fallback_warned:
                    log("warn", "cgroup memory files not found; using docker stats (slower)")
                    fallback_warned = True
            except Exception as e:  # docker briefly unavailable: keep going, the gap shows in max_gap_s
                log("warn", "sample failed", error=str(e)[:200])
            if cmd and command_end is None and child.poll() is not None:
                rc = child.returncode
                command_end = time.time()
                fh.write(f"# command_end={command_end:.3f} exit={rc}\n")
                log("info", "command finished; taking steady-state samples", exit_code=rc, samples=a.after)
            if stop["flag"] and (not cmd or command_end is not None):
                break
            if command_end is not None:
                if after_left <= 0:
                    break
                after_left -= 1
            nxt += a.interval
            time.sleep(max(0.0, nxt - time.monotonic()))
    res = summarize(a.out, a.data_dir, command_end)
    print(json.dumps(res, indent=2))
    log("info", "summary written", file=a.out + ".summary.json", ac16_memory_pass=res["ac16_memory_pass"],
        ac16_disk_pass=res["ac16_disk_pass"], interval_pass=res["interval_pass"])
    return rc


if __name__ == "__main__":
    sys.exit(main())

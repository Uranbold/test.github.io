#!/usr/bin/env python3
"""NAV-022 static pack host check: does a static web host serve an exported pack the way the app needs it?

    make pack-static-check PACK_BASE_URL=https://<host>/<path>/packs/ [PACK_LOCAL=<export dir>] [FULL=1]
    python3 scripts/pack_static_check.py --base-url URL [--local DIR] [--full] [--region mn]

URL is the app's pack base URL (`nav.packBaseUrl`, ending in /packs/). Never commit a real one. Python stdlib only.

REQUIRED (FAIL; the app's download, resume or update check breaks without them), openapi 0.6.x getOfflinePackManifest /
getOfflinePackFile, static-host profile:
  manifest   200, parses, region matches; strong or weak ETag present; If-None-Match: <ETag> -> 304;
             no Content-Encoding even when the request offers gzip/br
  every file 200 with Content-Length = download_bytes and no Content-Encoding (request offers gzip/br);
             Range bytes=<half>- -> 206, Content-Range bytes <half>-<size-1>/<size>, Content-Length = size-half;
             the same with If-Range: <ETag> -> 206; If-Range: <other ETag> -> 200 whole file;
             bytes match the local export at three offsets (--local), or the whole body hashes to download_sha256 (--full)
  missing    a retired version path and a missing file name -> 404 (never a 200 page)
RECOMMENDED (WARN; works, but slower or riskier): Cache-Control "no-cache" on the manifest and a long max-age on the
files, Accept-Ranges: bytes, strong (not W/) ETags, 416 for a range beyond the end, 304 on a file's ETag.

Without --local and --full only the headers and sizes are compared (no body checksum). --full downloads every file
completely (the whole pack, about 120 MB for Mongolia). Exit code 0 = no FAIL, 1 = at least one FAIL, 2 = usage.
Output: one line per check; it never prints the base URL's host.
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

OFFER = "gzip, deflate, br"
UA = "navmn-pack-static-check/1"


class Http:
    def __init__(self, base, timeout=30):
        self.base = base if base.endswith("/") else base + "/"
        host = urllib.parse.urlsplit(self.base).hostname or ""
        handlers = []
        if host in ("localhost", "127.0.0.1", "::1"):
            handlers.append(urllib.request.ProxyHandler({}))       # never send loopback through a proxy
        self.opener = urllib.request.build_opener(*handlers)
        self.timeout = timeout
        self.requests = 0

    def get(self, rel, headers=None, method="GET", limit=None):
        hdrs = {"User-Agent": UA, **(headers or {})}
        req = urllib.request.Request(self.base + rel, headers=hdrs, method=method)
        self.requests += 1
        try:
            r = self.opener.open(req, timeout=self.timeout)
        except urllib.error.HTTPError as e:
            r = e
        with r:
            status = r.status if hasattr(r, "status") else r.code
            h = {k.lower(): v for k, v in r.headers.items()}
            if method == "HEAD":
                return status, h, b""
            if limit is None:
                body = r.read()
            else:
                body = r.read(limit)
        return status, h, body

    def digest(self, rel, headers=None):
        hdrs = {"User-Agent": UA, **(headers or {})}
        req = urllib.request.Request(self.base + rel, headers=hdrs)
        self.requests += 1
        h, n = hashlib.sha256(), 0
        with self.opener.open(req, timeout=self.timeout) as r:
            for block in iter(lambda: r.read(1 << 20), b""):
                h.update(block)
                n += len(block)
        return h.hexdigest(), n


class Report:
    def __init__(self):
        self.fails, self.warns, self.oks = 0, 0, 0

    def line(self, level, name, detail=""):
        if level == "FAIL":
            self.fails += 1
        elif level == "WARN":
            self.warns += 1
        else:
            self.oks += 1
        print(f"{level:4}  {name}" + (f": {detail}" if detail else ""), flush=True)

    def need(self, ok, name, detail=""):
        self.line("ok" if ok else "FAIL", name, "" if ok else detail)
        return ok

    def want(self, ok, name, detail=""):
        self.line("ok" if ok else "WARN", name, "" if ok else detail)
        return ok


def no_coding(h):
    return h.get("content-encoding", "identity").strip().lower() in ("", "identity")


def max_age(cc):
    for part in (cc or "").replace(" ", "").lower().split(","):
        if part.startswith("max-age="):
            try:
                return int(part.split("=", 1)[1])
            except ValueError:
                return None
    return None


def local_bytes(path, start, n):
    with open(path, "rb") as f:
        f.seek(start)
        return f.read(n)


def check_manifest(http, rep, region):
    rel = f"{region}/manifest.json"
    status, h, body = http.get(rel, {"Accept-Encoding": OFFER})
    if not rep.need(status == 200, f"manifest GET -> 200", f"got {status}"):
        return None
    if not rep.need(no_coding(h), "manifest has no Content-Encoding (request offered gzip/br)",
                    f"Content-Encoding: {h.get('content-encoding')} (the host compresses it: disable compression, "
                    f".htaccess)"):
        status, h, body = http.get(rel, {"Accept-Encoding": "identity"})     # go on, to report every problem at once
    try:
        m = json.loads(body.decode("utf-8"))
    except ValueError as e:
        rep.need(False, "manifest parses as JSON", str(e)[:120])
        return None
    rep.need(m.get("region") == region and isinstance(m.get("files"), list) and m.get("pack_schema") == 1,
             "manifest has pack_schema 1, the region and files", f"region={m.get('region')} pack_schema={m.get('pack_schema')}")
    etag = h.get("etag")
    if rep.need(bool(etag), "manifest has an ETag", "no ETag header (the app cannot revalidate with If-None-Match)"):
        rep.want(not etag.startswith("W/"), "manifest ETag is strong", f"weak ETag {etag}")
        s304, _, _ = http.get(rel, {"If-None-Match": etag, "Accept-Encoding": OFFER})
        rep.need(s304 == 304, "manifest If-None-Match: <ETag> -> 304", f"got {s304}")
    cc = h.get("cache-control", "")
    rep.want("no-cache" in cc.lower() or max_age(cc) == 0, "manifest Cache-Control no-cache",
             f"Cache-Control: {cc or '(none)'} (upload .htaccess, or the app may see a new manifest late)")
    ct = h.get("content-type", "")
    rep.want(ct.split(";")[0].strip().lower() == "application/json", "manifest Content-Type application/json",
             f"Content-Type: {ct or '(none)'}")
    return m


def check_file(http, rep, region, f, local, full):
    rel = f"{region}/{f['path']}"
    size, name = f["download_bytes"], f["path"]
    status, h, _ = http.get(rel, {"Accept-Encoding": OFFER}, limit=0)
    if not rep.need(status == 200, f"{name} GET -> 200", f"got {status} (not uploaded, or wrong directory)"):
        return
    rep.need(no_coding(h), f"{name} has no Content-Encoding (request offered gzip/br)",
             f"Content-Encoding: {h.get('content-encoding')} (the app would get different bytes: .htaccess)")
    rep.need(h.get("content-length") == str(size), f"{name} Content-Length = download_bytes {size}",
             f"Content-Length: {h.get('content-length')} (incomplete upload or compression)")
    etag = h.get("etag")
    rep.need(bool(etag), f"{name} has an ETag", "no ETag (resume with If-Range impossible)")
    if etag:
        rep.want(not etag.startswith("W/"), f"{name} ETag is strong", f"weak ETag {etag} (If-Range needs a strong one)")
    rep.want(h.get("accept-ranges", "").lower() == "bytes", f"{name} Accept-Ranges: bytes",
             f"Accept-Ranges: {h.get('accept-ranges') or '(none)'}")
    cc = h.get("cache-control", "")
    rep.want((max_age(cc) or 0) >= 86400, f"{name} long Cache-Control max-age", f"Cache-Control: {cc or '(none)'}")
    half = size // 2
    hdr = {"Range": f"bytes={half}-", "Accept-Encoding": OFFER}
    s206, h206, part = http.get(rel, hdr)
    ok = rep.need(s206 == 206, f"{name} Range bytes={half}- -> 206", f"got {s206} (no Range support: no resume)")
    if ok:
        rep.need(h206.get("content-range") == f"bytes {half}-{size - 1}/{size}", f"{name} Content-Range",
                 f"Content-Range: {h206.get('content-range')}")
        rep.need(len(part) == size - half and no_coding(h206), f"{name} 206 body length {size - half}, no Content-Encoding",
                 f"{len(part)} bytes, Content-Encoding {h206.get('content-encoding')}")
        if local:
            rep.need(part[:65536] == local_bytes(local, half, 65536) and part[-4096:] == local_bytes(local, size - 4096, 4096)
                     if size - half >= 4096 else part == local_bytes(local, half, size - half),
                     f"{name} 206 bytes match the local export", "byte mismatch (stale or corrupted upload)")
    if etag:
        s, hh, body = http.get(rel, {"Range": f"bytes={half}-", "If-Range": etag})
        rep.need(s == 206 and len(body) == size - half, f"{name} If-Range: <ETag> -> 206",
                 f"got {s}" + (" with a weak ETag: Apache marks files written less than 1 s ago weak, so re-run after a "
                               "few seconds; if it stays weak the host cannot resume (every resume restarts the file)"
                               if etag.startswith("W/") else ""))
        s, hh, body = http.get(rel, {"Range": f"bytes={half}-", "If-Range": '"navmn-not-this-etag"'}, limit=0)
        rep.need(s == 200 and hh.get("content-length") == str(size), f"{name} If-Range: <other ETag> -> 200 whole file",
                 f"got {s} Content-Length {hh.get('content-length')} (a resume could splice two versions)")
        s304, _, _ = http.get(rel, {"If-None-Match": etag}, limit=0)
        rep.want(s304 == 304, f"{name} If-None-Match: <ETag> -> 304", f"got {s304}")
    s416, _, _ = http.get(rel, {"Range": f"bytes={size}-"}, limit=0)
    rep.want(s416 == 416, f"{name} Range beyond the end -> 416", f"got {s416}")
    if local:
        s, _, head = http.get(rel, {"Range": "bytes=0-65535"})
        rep.need(s == 206 and head == local_bytes(local, 0, min(65536, size)), f"{name} first 64 KiB match the local export",
                 f"status {s}, byte mismatch" if s == 206 else f"status {s}")
    if full:
        sha, n = http.digest(rel)
        rep.need((sha, n) == (f["download_sha256"], size), f"{name} whole body SHA-256 = download_sha256",
                 f"{n} bytes, sha256 {sha[:16]}...")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", required=True, help="the pack base URL, ending in /packs/")
    ap.add_argument("--local", help="the pack-export directory that was uploaded (byte comparison at three offsets)")
    ap.add_argument("--full", action="store_true", help="download every file completely and compare its SHA-256")
    ap.add_argument("--region", default="mn")
    ap.add_argument("--timeout", type=float, default=30)
    a = ap.parse_args(argv)
    if not a.base_url.startswith(("https://", "http://")):
        print("usage: --base-url must be an http(s) URL ending in /packs/", file=sys.stderr)
        return 2
    http, rep = Http(a.base_url, a.timeout), Report()
    scheme = urllib.parse.urlsplit(a.base_url).scheme
    rep.want(scheme == "https" or (urllib.parse.urlsplit(a.base_url).hostname in ("localhost", "127.0.0.1")),
             "base URL uses https", "plain http (release builds normally refuse cleartext)")
    m = check_manifest(http, rep, a.region)
    if m:
        local_dir = os.path.join(a.local, a.region) if a.local else None
        if local_dir:
            with open(os.path.join(local_dir, "manifest.json"), "rb") as fh:
                lm = json.loads(fh.read().decode("utf-8"))
            rep.need(lm == m, "served manifest = the local export's manifest", "different manifest (upload it last, again)")
        for f in m.get("files", []):
            check_file(http, rep, a.region, f, os.path.join(local_dir, f["path"]) if local_dir else None, a.full)
        name = (m["files"][0]["path"].split("/", 1)[1]) if m.get("files") else "routing.tar.gz"
        for label, rel in (("retired version", f"{a.region}/19990101T000000Z/{name}"),
                           ("missing file name", f"{a.region}/{m['files'][0]['version']}/nothing-here.gz" if m.get("files")
                            else f"{a.region}/nothing-here.gz")):
            s, h, _ = http.get(rel, limit=0)
            rep.need(s == 404, f"{label} -> 404", f"got {s} {h.get('content-type', '')} (the app treats 404 as 'retired: "
                                                     f"re-read the manifest')")
    print(f"summary: {rep.oks} ok, {rep.warns} warn, {rep.fails} fail, {http.requests} requests", flush=True)
    return 1 if rep.fails else 0


if __name__ == "__main__":
    sys.exit(main())

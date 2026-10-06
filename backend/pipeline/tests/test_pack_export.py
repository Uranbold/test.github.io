"""NAV-022 static pack export unit tests (stdlib unittest, no Docker, about a second).

    make -C backend pack-test        # or: python3 -m unittest discover -s backend/pipeline/tests -p 'test_pack_export*.py' -v

Covers: the exported tree (exactly the manifest's files under the gateway's /packs/ paths, the manifest byte for byte,
the ODbL notices, the .htaccess template), re-export into the same DEST (unchanged files kept, files of an older
publication removed, the weekly cut keeps the older tiles), HTACCESS=0, LINK=1, the refusals (foreign DEST, DEST in the
checkout or the packs directory, no published pack, placeholder licence.method_url, a corrupted source), the .test
warning, a bad copy removing DEST's manifest, and the helpers of scripts/pack_static_check.py.
"""
import contextlib
import gzip
import hashlib
import io
import json
import os
import re
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE.parents[1] / "scripts"))
import nav_pack  # noqa: E402
import nav022_test_pack  # noqa: E402
import pack_export  # noqa: E402
import pack_static_check  # noqa: E402

BACKEND = HERE.parents[1]
V1, V2 = "20261001T000000Z", "20261008T000000Z"


def gateway_file_regex():
    """The getOfflinePackFile location regex of the gateway template (the paths a static host must mirror)."""
    t = (BACKEND / "gateway" / "templates" / "default.conf.template").read_text()
    m = re.search(r'location ~ "(\^/packs/mn/[^"]+)"', t)
    return re.compile(re.sub(r"\(\?<[a-z_]+>", "(", m.group(1)))


class ExportBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="nav022-unit-")
        self.dir = Path(self.tmp.name)
        self.packs = self.dir / "data" / "packs"
        self.dest = self.dir / "www" / "packs"
        with contextlib.redirect_stdout(io.StringIO()):
            nav022_test_pack.publish(str(self.packs), V1)

    def tearDown(self):
        self.tmp.cleanup()

    def run_export(self, *extra, dest=None, packs=None):
        out = io.StringIO()
        argv = ["export", "--packs-dir", str(packs or self.packs), "--dest", str(dest or self.dest), *extra]
        with contextlib.redirect_stdout(out):
            rc = pack_export.main(argv)
        lines = [json.loads(x) for x in out.getvalue().splitlines() if x.strip()]
        return rc, lines[0], lines

    def tree(self, root=None):
        root = root or self.dest
        return sorted(p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file())

    def manifest(self):
        return json.loads((self.packs / "mn" / "manifest.json").read_text())


class TreeTests(ExportBase):
    def test_exact_tree_paths_and_bytes(self):
        rc, res, lines = self.run_export()
        self.assertEqual(rc, 0, res)
        self.assertEqual(res["result"], "ok")
        self.assertEqual(self.tree(), [".htaccess", f"mn/{V1}/NOTICE.txt", f"mn/{V1}/basemap.pmtiles.gz",
                                       f"mn/{V1}/routing.tar.gz", f"mn/{V1}/search.sqlite.gz", "mn/NOTICE.txt",
                                       "mn/manifest.json"])
        self.assertEqual((self.dest / "mn" / "manifest.json").read_bytes(),
                         (self.packs / "mn" / "manifest.json").read_bytes())
        rx = gateway_file_regex()
        for f in self.manifest()["files"]:
            self.assertRegex("/packs/mn/" + f["path"], rx)                 # same URL path as the gateway serves
            body = (self.dest / "mn" / f["path"]).read_bytes()
            self.assertEqual((len(body), hashlib.sha256(body).hexdigest()), (f["download_bytes"], f["download_sha256"]))
            self.assertEqual(hashlib.sha256(gzip.decompress(body)).hexdigest(), f["sha256"])
        self.assertEqual((self.dest / ".htaccess").read_text(), pack_export.HTACCESS_TEMPLATE.read_text())
        self.assertIn("test-only", res["warning"])
        order = lines[1]["upload_order"]
        self.assertEqual(order[-1], "mn/manifest.json")                    # manifest last
        self.assertTrue(all(p.endswith(".gz") for p in order[:3]))
        self.assertEqual(oct((self.dest / "mn" / "manifest.json").stat().st_mode & 0o777), "0o644")

    def test_notices(self):
        self.run_export()
        n = (self.dest / "mn" / "NOTICE.txt").read_text()
        for s in ("© OpenStreetMap contributors", "Open Database License (ODbL) 1.0", nav_pack.ODBL_URL,
                  nav022_test_pack.METHOD_URL, f"{V1}/routing.tar.gz", "ESA WorldCover", "Derivative Database"):
            self.assertIn(s, n)
        with contextlib.redirect_stdout(io.StringIO()):
            nav022_test_pack.publish(str(self.packs), V2, keep_tiles=True)
        self.run_export()
        v2 = (self.dest / "mn" / V2 / "NOTICE.txt").read_text()
        self.assertIn("routing.tar.gz", v2)
        self.assertNotIn("ESA WorldCover", v2)                              # no tiles in the weekly version
        self.assertIn("ESA WorldCover", (self.dest / "mn" / V1 / "NOTICE.txt").read_text())

    def test_reexport_and_weekly_cut(self):
        self.run_export()
        rc, res, _ = self.run_export()
        self.assertEqual((rc, res["copied"], len(res["kept"])), (0, [], 3))
        with contextlib.redirect_stdout(io.StringIO()):
            nav022_test_pack.publish(str(self.packs), V2, keep_tiles=True)
        rc, res, _ = self.run_export()
        self.assertEqual(rc, 0, res)
        self.assertEqual(res["kept"], [f"{V1}/basemap.pmtiles.gz"])
        self.assertEqual(sorted(res["removed"]), [f"{V1}/routing.tar.gz", f"{V1}/search.sqlite.gz"])
        self.assertEqual(self.tree(), [".htaccess", f"mn/{V1}/NOTICE.txt", f"mn/{V1}/basemap.pmtiles.gz",
                                       f"mn/{V2}/NOTICE.txt", f"mn/{V2}/routing.tar.gz", f"mn/{V2}/search.sqlite.gz",
                                       "mn/NOTICE.txt", "mn/manifest.json"])

    def test_version_dir_dropped_when_unlisted(self):
        self.run_export()
        (self.dest / "mn" / "19990101T000000Z").mkdir()
        (self.dest / "mn" / "19990101T000000Z" / "routing.tar.gz").write_bytes(b"old")
        rc, res, _ = self.run_export()
        self.assertEqual((rc, res["removed"]), (0, ["19990101T000000Z/"]))

    def test_no_htaccess_and_link(self):
        self.run_export()
        rc, res, _ = self.run_export("--no-htaccess")
        self.assertEqual((rc, res["htaccess"]), (0, False))
        self.assertFalse((self.dest / ".htaccess").exists())
        d2 = self.dir / "www" / "linked"
        rc, res, _ = self.run_export("--link", dest=d2)
        self.assertEqual((rc, res["mode"]), (0, "hard links"))
        f = self.manifest()["files"][1]["path"]
        self.assertEqual((d2 / "mn" / f).stat().st_ino, (self.packs / "mn" / f).stat().st_ino)


class RefusalTests(ExportBase):
    def test_foreign_dest(self):
        self.dest.mkdir(parents=True)
        (self.dest / "index.html").write_text("x")
        rc, res, _ = self.run_export()
        self.assertEqual((rc, res["result"]), (2, "usage"))
        self.assertIn("index.html", res["msg"])
        self.assertEqual(self.tree(), ["index.html"])                       # nothing written

    def test_dest_inside_checkout_or_packs(self):
        for d in (BACKEND / "nav022-export-should-not-exist", self.packs / "export", self.dir / "data"):
            with self.subTest(dest=d):
                rc, res, _ = self.run_export(dest=d)
                self.assertEqual(rc, 2, res)
                self.assertFalse((BACKEND / "nav022-export-should-not-exist").exists())

    def test_no_published_pack(self):
        rc, res, _ = self.run_export(packs=self.dir / "nothing" / "packs")
        self.assertEqual((rc, res["result"]), (3, "refused"))

    def test_placeholder_method_url(self):
        mp = self.packs / "mn" / "manifest.json"
        for url in ("https://example.org/osm-navigation/pipeline", "https://pipeline.example.invalid/x"):
            with self.subTest(url=url):
                m = json.loads(mp.read_text())
                m["licence"]["method_url"] = url
                mp.write_text(json.dumps(m))
                rc, res, _ = self.run_export()
                self.assertEqual((rc, res["result"]), (3, "refused"))
                self.assertIn("placeholder", res["msg"])
                self.assertFalse(self.dest.exists())

    def test_corrupted_source_is_caught(self):
        f = self.manifest()["files"][1]
        p = self.packs / "mn" / f["path"]
        b = bytearray(p.read_bytes())
        b[len(b) // 2] ^= 0xFF                      # same size: passes the size rule, fails the checksum
        p.write_bytes(bytes(b))
        rc, res, _ = self.run_export()
        self.assertEqual((rc, res["result"]), (3, "refused"))
        self.assertIn("SHA-256 differs", res["msg"])
        self.assertFalse(self.dest.exists())                                # nothing exported

    def test_bad_copy_removes_the_manifest(self):
        real = pack_export.place

        def corrupting_place(src, dst, link):
            real(src, dst, link)
            if dst.name == "routing.tar.gz":
                b = bytearray(dst.read_bytes())
                b[100] ^= 0xFF
                dst.write_bytes(bytes(b))
        pack_export.place = corrupting_place
        try:
            rc, res, _ = self.run_export()
        finally:
            pack_export.place = real
        self.assertEqual((rc, res["result"]), (4, "verify failed"))
        self.assertTrue(any("SHA-256" in x for x in res["problems"]), res["problems"])
        self.assertFalse((self.dest / "mn" / "manifest.json").exists())


class HtaccessTests(unittest.TestCase):
    def test_template_rules(self):
        t = pack_export.HTACCESS_TEMPLATE.read_text()
        active = "\n".join(l.strip() for l in t.splitlines() if l.strip() and not l.strip().startswith("#"))
        for s in ("SetEnv no-gzip 1", "RemoveEncoding .gz", "AddType application/gzip .gz", "AddType application/json .json",
                  "AddType application/vnd.pmtiles .pmtiles", "AddType application/x-tar .tar",
                  "AddType application/vnd.sqlite3 .sqlite", 'Header set Cache-Control "public, max-age=31536000, immutable"',
                  'Header set Cache-Control "no-cache"', "ExpiresActive Off", "RewriteEngine On"):
            self.assertIn(s, active)
        self.assertNotIn("Header always", active)        # a 404 must never get the one-year cache
        self.assertNotRegex(active, r"(?m)^Options")      # Options needs AllowOverride Options: a 500 on many hosts
        self.assertNotRegex(t, r"https?://")              # no hostnames
        # the gz-before-json order of AddType matters only per extension; the last extension of *.pmtiles.gz is gz
        self.assertLess(active.index("AddType application/vnd.pmtiles"), active.index("AddType application/gzip"))


class StaticCheckHelperTests(unittest.TestCase):
    def test_helpers(self):
        self.assertEqual(pack_static_check.max_age("public, max-age=31536000, immutable"), 31536000)
        self.assertIsNone(pack_static_check.max_age("no-cache"))
        self.assertTrue(pack_static_check.no_coding({}))
        self.assertTrue(pack_static_check.no_coding({"content-encoding": "identity"}))
        self.assertFalse(pack_static_check.no_coding({"content-encoding": "x-gzip"}))
        self.assertEqual(pack_static_check.main(["--base-url", "ftp://x"]), 2)


if __name__ == "__main__":
    unittest.main()

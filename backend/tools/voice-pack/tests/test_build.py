"""B7 manifest + atomic output, B8 review page / renderer / AC 10 harness, B11 duration early warning
(NAV-016 AC 11, 13, 17, 18, 19, 21)."""

from __future__ import annotations

import getpass
import json
import os
import re
import shutil
import socket
import unittest
from pathlib import Path
from unittest import mock

from tests.helpers import (REPO, build_argv, make_repo, run_cli, scratch, shared_full_pack, tree_hashes)
from voicepack import audio
from voicepack.render import pause_frames
from voicepack.synth.stub import StubSynthesiser
from voicepack.verify import load_schema, schema_errors


def manifest_of(run: Path) -> dict:
    return json.loads((run / "pack" / "manifest.json").read_text(encoding="utf-8"))


def read_tsv(path: Path) -> list[dict]:
    lines = path.read_text(encoding="utf-8").split("\n")
    assert lines[-1] == ""
    header = lines[0].split("\t")
    return [dict(zip(header, ln.split("\t"))) for ln in lines[1:-1]]


class DeterminismTest(unittest.TestCase):
    def test_two_stub_runs_byte_identical_trees(self):
        a, b = tree_hashes(shared_full_pack(0)), tree_hashes(shared_full_pack(1))
        self.assertEqual(len([k for k in a if k.startswith("pack/clips/")]), 304)
        self.assertEqual(a, b)

    def test_pack_holds_only_manifest_and_listed_clips(self):
        run = shared_full_pack()
        m = manifest_of(run)
        files = {p.relative_to(run / "pack").as_posix() for p in (run / "pack").rglob("*") if p.is_file()}
        self.assertEqual(files, {"manifest.json"} | {e["file"] for e in m["entries"]})
        self.assertEqual(sorted(p.name for p in run.iterdir()), ["pack", "review"])

    def test_manifest_layout_and_no_clock_host_user(self):
        run = shared_full_pack()
        raw = (run / "pack" / "manifest.json").read_bytes()
        text = raw.decode("utf-8")
        self.assertFalse(raw.startswith(b"\xef\xbb\xbf"))
        self.assertTrue(raw.endswith(b"}\n") and not raw.endswith(b"\n\n"))
        self.assertNotIn("\\u04", text, "Cyrillic is written as is")
        self.assertNotIn("\r", text)
        self.assertIn('\n  "schemaVersion": 1,\n', text)
        m = json.loads(text)
        self.assertEqual(list(m), load_schema()["required"])
        self.assertEqual(list(m["entries"][0]), load_schema()["$defs"]["entry"]["required"])
        self.assertEqual(m["processing"], {"silenceThresholdDbfs": -45, "edgeMarginMs": 5, "fadeMs": 10,
                                           "targetRmsDbfs": -20, "peakCeilingDbfs": -1})
        self.assertIn('"silenceThresholdDbfs": -45,', text)
        self.assertNotRegex(text, r"T\d\d:\d\d")
        self.assertNotRegex(text, r"20\d\d-\d\d-\d\d")
        outputs = [text] + [p.read_text(encoding="utf-8") for p in (run / "review").glob("*.*")]
        host = socket.gethostname()
        home = os.environ.get("HOME", "")
        try:
            user = getpass.getuser()
        except Exception:  # noqa: BLE001
            user = os.environ.get("USER", "")
        for t in outputs:
            if len(host) >= 3:
                self.assertNotIn(host, t)
            if len(home) > 1:
                self.assertNotIn(home, t)
            if user:
                # a user name leaks through a path or an address; ":root" in the page CSS is not one
                self.assertNotRegex(t, rf"[/\\@~]{re.escape(user)}\b")
            self.assertNotIn(str(run), t, "no absolute path")
        if user:
            self.assertNotRegex(text, rf"\b{re.escape(user)}\b")

    def test_schema_validates_produced_manifests(self):
        schema = load_schema()
        self.assertEqual(schema_errors(manifest_of(shared_full_pack()), schema), [])
        run = scratch() / "o"
        rc, so, se = run_cli(build_argv(make_repo(scratch() / "repo"), run, extra=["--bitrate-kbps", "28", "--clip-rate", "16000"]))
        self.assertEqual(rc, 0, se)
        m = manifest_of(run / "FEMALE5v2-v1")
        self.assertEqual((m["codec"]["sampleRateHz"], m["codec"]["bitrateKbps"]), (16000, 28))
        self.assertEqual(schema_errors(m, schema), [])

    def test_identity_line_printed(self):
        run = shared_full_pack()  # first: builds the pack (or skips the test when there is no ffmpeg)
        so = shared_full_pack.__globals__["_shared"]["full0-stdout"]
        sha = __import__("hashlib").sha256((run / "pack" / "manifest.json").read_bytes()).hexdigest()
        self.assertIn(f"voiceId FEMALE5v2, packVersion 1, manifest SHA-256 {sha}", so)
        self.assertRegex(so, r"304 clips, pack size \d+ bytes")


class AtomicOutputTest(unittest.TestCase):
    def test_failure_at_entry_200_leaves_no_pack_and_earlier_pack_unchanged(self):
        out = scratch() / "out"
        shutil.copytree(shared_full_pack(), out / "FEMALE5v2-v1")
        before = tree_hashes(out)

        class FailAt200(StubSynthesiser):
            def synthesize(self, text, voice, rate, *, label=""):
                if self.calls == 199:
                    from voicepack.errors import VendorError
                    raise VendorError(f"{label}: injected failure at entry 200")
                return super().synthesize(text, voice, rate, label=label)

        rc, so, se = run_cli(build_argv(REPO, out, extra=["--replace"]), synth_factory=lambda o: FailAt200())
        self.assertEqual(rc, 4, se)
        self.assertIn("injected failure at entry 200", se)
        self.assertEqual(sorted(p.name for p in out.iterdir()), ["FEMALE5v2-v1"], "no temporary or second directory")
        self.assertEqual(tree_hashes(out), before)

    def test_failure_in_processing_leaves_no_pack(self):
        from unittest import mock

        from voicepack import build as build_mod

        repo = make_repo(scratch() / "repo")
        out = scratch() / "out"
        real = build_mod._process_job

        def flaky(args):
            if args[0] == "fixed.offRoute":
                raise audio.check_failed("fixed.offRoute (final)", "peak", "injected")
            return real(args)

        with mock.patch.object(build_mod, "_process_job", flaky):
            rc, so, se = run_cli(build_argv(repo, out))
        self.assertEqual(rc, 5)
        self.assertIn("fixed.offRoute (final): rule peak", se)
        self.assertEqual(list(out.iterdir()) if out.exists() else [], [])

    def test_existing_target_exit_6_and_replace(self):
        repo = make_repo(scratch() / "repo")
        out = scratch() / "out"
        self.assertEqual(run_cli(build_argv(repo, out))[0], 0)
        first = tree_hashes(out)
        rc, so, se = run_cli(build_argv(repo, out, extra=["--join-pause-ms", "120"]))
        self.assertEqual(rc, 6)
        self.assertIn("--replace", se)
        self.assertEqual(tree_hashes(out), first)
        rc, so, se = run_cli(build_argv(repo, out, extra=["--join-pause-ms", "120", "--replace"]))
        self.assertEqual(rc, 0, se)
        self.assertEqual(manifest_of(out / "FEMALE5v2-v1")["joinPauseMs"], 120)
        self.assertEqual(sorted(p.name for p in out.iterdir()), ["FEMALE5v2-v1"])

    def test_pack_version_required_and_used(self):
        repo = make_repo(scratch() / "repo")
        out = scratch() / "out"
        with self.assertRaises(SystemExit):
            run_cli(["build", "--voice", "FEMALE5v2", "--synth", "stub", "--out", str(out), "--repo", str(repo)])
        rc, so, se = run_cli(build_argv(repo, out, version=7, voice="MALE4v2"))
        self.assertEqual(rc, 0, se)
        m = manifest_of(out / "MALE4v2-v7")
        self.assertEqual((m["packVersion"], m["voiceId"], m["source"]["voice"]), (7, "MALE4v2", "MALE4v2"))


class ReviewTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.run_dir = shared_full_pack()
        cls.m = manifest_of(cls.run_dir)
        cls.rows = read_tsv(cls.run_dir / "review" / "prompts.tsv")

    def test_review_tsv_304_rows(self):
        lines = (self.run_dir / "review" / "review.tsv").read_text(encoding="utf-8").split("\n")
        self.assertEqual(lines[0], "key\ttake\tspoken_mn\tresult\tnote")
        self.assertEqual(lines[-1], "")
        body = lines[1:-1]
        self.assertEqual(len(body), 304)
        for ln, e in zip(body, self.m["entries"]):
            self.assertEqual(ln, f"{e['key']}\t{e['take']}\t{e['spokenText']}\t\t")

    def test_index_html_offline_and_complete(self):
        page = (self.run_dir / "review" / "index.html").read_text(encoding="utf-8")
        self.assertNotRegex(page, r"https?:", "no external URL")
        self.assertNotRegex(page, r"<(link|img|iframe)\b|<script[^>]+src=", "no external resources")
        data = json.loads(re.search(r'<script type="application/json" id="data">(.*?)</script>', page, re.S).group(1))
        self.assertEqual([(e["key"], e["take"], e["spoken"]) for e in data["entries"]],
                         [(e["key"], e["take"], e["spokenText"]) for e in self.m["entries"]])
        for e in data["entries"]:
            self.assertTrue(e["src"].startswith("../pack/clips/") and (self.run_dir / "review" / e["src"]).is_file())
        for p in data["prompts"]:
            self.assertTrue((self.run_dir / "review" / p["src"]).is_file(), p["id"])
        self.assertEqual(data["meta"]["vendor"], "stub")  # -> the stub banner and disabled marks
        self.assertEqual(data["labels"]["title"], "Voice pack review")
        self.assertFalse(data["meta"]["gpsMissing"])
        for label in ("Play from here", "Download review.tsv", "Stub pack: synthetic tones, not for listening."):
            self.assertIn(label, page)

    def test_index_html_content_security_policy(self):
        """Security VP-10: a CSP meta that allows only the page's own inline script and style (by hash)."""
        import base64
        import hashlib

        page = (self.run_dir / "review" / "index.html").read_text(encoding="utf-8")
        csp = re.search(r'<meta http-equiv="Content-Security-Policy" content="([^"]+)">', page).group(1)
        self.assertLess(page.index("Content-Security-Policy"), page.index("<script"))
        self.assertTrue(csp.startswith("default-src 'none'; "))
        for tag in ("script", "style"):
            bodies = re.findall(rf"<{tag}>(.*?)</{tag}>", page, re.S)
            self.assertEqual(len(bodies), 1, tag)
            h = base64.b64encode(hashlib.sha256(bodies[0].encode("utf-8")).digest()).decode()
            self.assertIn(f"{tag}-src 'sha256-{h}'", csp)
        self.assertNotIn("unsafe-inline", csp)
        self.assertNotIn("unsafe-eval", csp)
        self.assertIn("media-src 'self' file:", csp)

    def test_prompt_sets(self):
        kinds = [r["kind"] for r in self.rows]
        self.assertEqual(kinds.count("coverage"), 20)
        self.assertEqual(kinds.count("measured"), 2)
        self.assertEqual(kinds.count("superset-longest-single"), 10)
        self.assertEqual(kinds.count("superset-longest-chained"), 10)
        self.assertEqual([r["mark"] for r in self.rows[:2]], ["single", "chained"])
        self.assertEqual([r["kind"] for r in self.rows[2:4]], ["measured", "measured"])
        for r in self.rows:
            self.assertTrue((self.run_dir / "review" / r["file"]).is_file())
            self.assertEqual(int(r["limitMs"]), 8000 if "join.then:" in r["segments"] else 5000)

    def test_b11_superset_longest_sorted_with_durations(self):
        for kind in ("superset-longest-single", "superset-longest-chained"):
            ds = [int(r["durationMs"]) for r in self.rows if r["kind"] == kind]
            self.assertEqual(len(ds), 10)
            self.assertEqual(ds, sorted(ds, reverse=True))
        by = {f"{e['key']}:{e['take']}": e for e in self.m["entries"]}
        singles = []
        for a in by.values():
            if a["take"] == "final" and a["key"].startswith("instr."):
                singles.append(a["durationMs"])
                for p in by.values():
                    if p["take"] == "final" and p["key"].startswith("dist."):
                        singles.append(p["durationMs"] + 80 + a["durationMs"])
        top = max(int(r["durationMs"]) for r in self.rows if r["kind"] == "superset-longest-single")
        self.assertEqual(top, max(singles))

    def test_rendered_length_and_join_silence(self):
        by = {f"{e['key']}:{e['take']}": e for e in self.m["entries"]}
        thr = audio.FULL_SCALE * 10 ** (audio.SILENCE_THRESHOLD_DBFS / 20)
        win = 22  # 1 ms
        checked = 0
        for r in self.rows:
            toks = r["segments"].split(" ")
            w = audio.read_wav((self.run_dir / "review" / r["file"]).read_bytes())
            self.assertEqual((w.rate, w.channels, w.bits), (22050, 1, 16))
            keys = [t.split(":")[0] for t in toks]
            pauses = [80 for _ in zip(keys, keys[1:])]
            frames = [by[t]["frames"] for t in toks]
            self.assertEqual(len(w.samples), sum(frames) + sum(pause_frames(p, 22050) for p in pauses), r["id"])
            self.assertEqual(int(r["durationMs"]), sum(by[t]["durationMs"] for t in toks) + sum(pauses))
            x = w.samples
            pos = 0
            for i, p in enumerate(pauses):
                pos += frames[i]
                start, end = pos, pos + pause_frames(p, 22050)

                def quiet(a, b):
                    seg = x[a:b]
                    return not seg or (sum(v * v for v in seg) / len(seg)) ** 0.5 < thr

                left = start
                while left - win >= 0 and quiet(left - win, left):
                    left -= win
                right = end
                while right + win <= len(x) and quiet(right, right + win):
                    right += win
                silence_ms = (right - left) * 1000 / 22050
                self.assertGreaterEqual(silence_ms, p, r["id"])
                self.assertLessEqual(silence_ms, p + 2 * (audio.EDGE_MARGIN_MS + audio.FADE_MS), f"{r['id']} join {i}: {silence_ms:.1f} ms")
                pos = end
                checked += 1
        self.assertGreater(checked, 50)

    def test_render_cli_is_the_ac10_harness(self):
        lst = scratch() / "list.tsv"
        marked = next(r for r in self.rows if r["mark"] == "chained")
        lst.write_text("id\tsegments\tkind\n"
                       "s1\tdist.m.250:final instr.roundabout.exit.6:final\tsingle\n"
                       "c1\tdist.m.250:final instr.roundabout.exit.6:nonFinal join.then:final instr.roundabout.exit.6:final\tchained\n"
                       "e\t-\tempty\n"
                       f"m1\t{marked['segments']}\tchained\n", encoding="utf-8")
        out = scratch() / "render"
        rc, so, se = run_cli(["render", "--pack", str(self.run_dir / "pack"), "--prompts", str(lst), "--out", str(out),
                              *__import__("tests.helpers", fromlist=["ffmpeg_args"]).ffmpeg_args()])
        self.assertEqual(rc, 0, se)
        rows = read_tsv(out / "prompts.tsv")
        self.assertEqual([r["id"] for r in rows], ["s1", "c1", "e", "m1"])
        by = {f"{e['key']}:{e['take']}": e for e in self.m["entries"]}
        self.assertEqual(int(rows[1]["durationMs"]), sum(by[t]["durationMs"] for t in rows[1]["segments"].split(" ")) + 240)
        self.assertEqual(rows[1]["spoken"], "хоёр зуун тавин метрт тойрогт ороод зургаадугаар гарцаар гарна уу, дараа нь "
                                            "тойрогт ороод зургаадугаар гарцаар гарна уу")
        self.assertTrue((out / "s1.wav").is_file() and (out / "c1.wav").is_file())
        self.assertFalse((out / "e.wav").exists())
        # the same prompt rendered by the build is byte-identical
        self.assertEqual((out / "m1.wav").read_bytes(), (self.run_dir / "review" / marked["file"]).read_bytes())

    def test_build_with_prompt_list(self):
        repo = make_repo(scratch() / "repo")
        lst = scratch() / "longest-prompts.tsv"
        lst.write_text("id\tsegments\tdurationMs\tkind\n"
                       "android-single-01\tdist.m.200:final instr.turn.right:final\t0\tsingle\n"
                       "android-chained-01\tdist.m.300:final instr.uturn:nonFinal join.then:final instr.turn.right:final\t0\tchained\n",
                       encoding="utf-8")
        out = scratch() / "o"
        rc, so, se = run_cli(build_argv(repo, out, extra=["--prompts", str(lst)]))
        self.assertEqual(rc, 0, se)
        rows = read_tsv(out / "FEMALE5v2-v1" / "review" / "prompts.tsv")
        self.assertEqual([(r["id"], r["mark"]) for r in rows[:2]], [("android-single-01", "single"), ("android-chained-01", "chained")])
        self.assertTrue(any(r["kind"].startswith("superset-longest") for r in rows))
        bad = scratch() / "bad.tsv"
        bad.write_text("id\tsegments\nx\tinstr.nothing:final\n", encoding="utf-8")
        rc, so, se = run_cli(build_argv(repo, scratch() / "o2", extra=["--prompts", str(bad)]))
        self.assertEqual(rc, 2)
        self.assertIn("instr.nothing:final", se)


class CacheOnlyTest(unittest.TestCase):
    """--cache-only: a real (chimege) pack from a complete cache, with no token and no network; any missing entry
    fails before processing; the token stays required for every real request."""

    def setUp(self):
        from voicepack.cache import NO_TAKE_METHOD, Cache, cache_fields, cache_key
        from voicepack.inventory import NON_FINAL, load
        from voicepack.textrules import request_text

        self.repo = make_repo(scratch() / "repo")
        self.cache = scratch() / "cache"
        c = Cache(self.cache)
        stub = StubSynthesiser()
        self.keys = []
        for e in load(self.repo).entries:
            text = request_text(e, "carrier-then-v1")
            f = cache_fields(vendor="chimege", api_version="v1.2", voice="FEMALE5v2", sample_rate=22050, speed="1",
                             pitch="1", take_method="carrier-then-v1" if e.take == NON_FINAL else NO_TAKE_METHOD,
                             request_text=text)
            k = cache_key(f)
            c.put(k, f, stub.synthesize(text, "FEMALE5v2", 22050))
            self.keys.append(k)

    def run_offline(self, argv):
        """No token in the environment and every socket refused: the run must not even try."""
        def refuse(*a, **k):
            raise AssertionError("cache-only must not open a socket")

        env = {k: v for k, v in os.environ.items() if k != "CHIMEGE_API_TOKEN"}
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(socket, "socket", refuse), \
                mock.patch.object(socket, "create_connection", refuse):
            return run_cli(argv)

    def test_complete_cache_builds_a_real_pack_without_token_or_network(self):
        before = tree_hashes(self.cache)
        out = scratch() / "out"
        rc, so, se = self.run_offline(build_argv(self.repo, out, synth="chimege", extra=["--cache", str(self.cache), "--cache-only"]))
        self.assertEqual(rc, 0, so + se)
        m = manifest_of(out / "FEMALE5v2-v1")
        self.assertEqual(m["source"], {"vendor": "chimege", "apiVersion": "v1.2", "voice": "FEMALE5v2"})
        self.assertIn("HTTP requests sent 0 (cache-only)", so)
        self.assertIn("cache-only: all 12 entries are in the cache", so)
        self.assertEqual(tree_hashes(self.cache), before, "the cache is only read")

    def test_missing_entry_fails_before_processing(self):
        (self.cache / "wav" / f"{self.keys[3]}.wav").unlink()
        out = scratch() / "out"
        rc, so, se = self.run_offline(build_argv(self.repo, out, synth="chimege", extra=["--cache", str(self.cache), "--cache-only"]))
        self.assertEqual(rc, 2, so + se)
        self.assertIn("--cache-only: 1 of 12 entries are not in the cache", se)
        self.assertIn("0 requests sent", se)
        self.assertFalse(list(out.rglob("manifest.json")) if out.exists() else [])

    def test_option_conflicts(self):
        out = scratch() / "out"
        rc, so, se = self.run_offline(build_argv(self.repo, out, synth="stub", extra=["--cache", str(self.cache), "--cache-only"]))
        self.assertEqual(rc, 2)
        self.assertIn("--cache-only needs --synth chimege", se)
        rc, so, se = self.run_offline(build_argv(self.repo, out, synth="chimege", extra=[
            "--cache", str(self.cache), "--cache-only", "--api-base", "http://127.0.0.1:9/v1.2"]))
        self.assertEqual(rc, 2)
        self.assertIn("exclude each other", se)

    def test_without_cache_only_the_token_is_still_required(self):
        rc, so, se = self.run_offline(build_argv(self.repo, scratch() / "out", synth="chimege", extra=["--cache", str(self.cache)]))
        self.assertEqual(rc, 2)
        self.assertIn("CHIMEGE_API_TOKEN is not set", se)


if __name__ == "__main__":
    unittest.main()

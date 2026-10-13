"""B4: the vendor WAV cache and the output/cache path rule (NAV-016 AC 10, pack-format §1.2)."""

from __future__ import annotations

import json
import shutil
import unittest
from unittest import mock

from tests.fake_chimege import FakeChimege, Reply, tiny_wav
from tests.helpers import REPO, build_argv, make_repo, run_cli, scratch, tree_hashes
from tests.test_chimege import SENTINEL, factory
from voicepack import build as build_mod
from voicepack.cache import cache_fields, cache_key, check_path_allowed
from voicepack.errors import ConfigError


def _edit(key, new_text):
    def edit(s):
        out = []
        for ln in s.split("\n"):
            c = ln.split("\t")
            if c and c[0] == key:
                col = 2 if len(c) == 4 else 5  # inventory.tsv: spoken_mn is column 3; the TSV: column 6
                c[col] = new_text
                ln = "\t".join(c)
            out.append(ln)
        return "\n".join(out)
    return edit


class CacheTest(unittest.TestCase):
    def setUp(self):
        self.cache = scratch() / "cache"

    def run_build(self, repo, out, voice="FEMALE5v2"):
        with FakeChimege() as fake:
            rc, so, se = run_cli(build_argv(repo, out, synth="chimege", voice=voice,
                                            extra=["--api-base", fake.base, "--cache", str(self.cache)]),
                                 synth_factory=factory())
        self.assertEqual(rc, 0, so + se)
        return fake

    def test_second_run_zero_requests_byte_identical(self):
        repo = make_repo(scratch() / "repo")
        out1, out2 = scratch() / "o1", scratch() / "o2"
        f1 = self.run_build(repo, out1)
        f2 = self.run_build(repo, out2)
        self.assertEqual(len(f1.requests), 12)
        self.assertEqual(len(f2.requests), 0)
        self.assertEqual(tree_hashes(out1 / "FEMALE5v2-v1"), tree_hashes(out2 / "FEMALE5v2-v1"))

    def test_changed_text_requests_only_that_key(self):
        repo = make_repo(scratch() / "repo")
        self.run_build(repo, scratch() / "o1")
        repo2 = make_repo(scratch() / "repo2", edit_inv=_edit("instr.turn.right", "баруун тийш эргэнэ үү дээ"))
        f = self.run_build(repo2, scratch() / "o2")
        self.assertEqual(sorted(f.texts()), ["баруун тийш эргэнэ үү дээ", "баруун тийш эргэнэ үү дээ, дараа нь"])
        repo3 = make_repo(scratch() / "repo3", edit_dist=_edit("dist.m.300", "гурван зуун метрийн дараа"))
        f = self.run_build(repo3, scratch() / "o3")
        self.assertEqual(f.texts(), ["гурван зуун метрийн дараа"])

    def test_changed_voice_requests_all_304(self):
        """Full inventory; the run stops after synthesis (processing is not under test here)."""

        class Stop(Exception):
            pass

        def stop(*a, **k):
            raise Stop()

        counts = []
        for voice in ("FEMALE5v2", "FEMALE5v2", "MALE4v2"):
            with FakeChimege(lambda n, t, h: Reply(200, body=tiny_wav())) as fake, mock.patch.object(build_mod, "_run_jobs", stop):
                rc, so, se = run_cli(build_argv(REPO, scratch() / "o", synth="chimege", voice=voice,
                                                extra=["--api-base", fake.base, "--cache", str(self.cache)]),
                                     synth_factory=factory())
            self.assertEqual(rc, 1, se)  # Stop -> internal error after synthesis
            counts.append(len(fake.requests))
            if voice == "MALE4v2":
                self.assertTrue(all(r.headers["voice-id"] == "MALE4v2" for r in fake.requests))
        self.assertEqual(counts, [304, 0, 304])

    def test_index_has_key_fields_and_no_token(self):
        repo = make_repo(scratch() / "repo")
        self.run_build(repo, scratch() / "o1")
        lines = (self.cache / "index.jsonl").read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(lines), 12)
        files = sorted(p.name for p in (self.cache / "wav").iterdir())
        self.assertEqual(len(files), 12)
        for ln in lines:
            rec = json.loads(ln)
            self.assertEqual(set(rec), {"cacheKey", "vendor", "apiVersion", "voice", "sampleRateHz", "speed", "pitch",
                                        "takeMethod", "requestText", "bytes", "sha256"})
            fields = {k: rec[k] for k in ("vendor", "apiVersion", "voice", "sampleRateHz", "speed", "pitch", "takeMethod", "requestText")}
            self.assertEqual(cache_key(fields), rec["cacheKey"])
            self.assertIn(rec["cacheKey"] + ".wav", files)
            self.assertEqual(rec["takeMethod"], "carrier-then-v1" if rec["requestText"].endswith(", дараа нь") else "none")
        self.assertNotIn(SENTINEL, (self.cache / "index.jsonl").read_text(encoding="utf-8"))
        self.assertFalse(list(self.cache.rglob("*.tmp-*")), "no temporary files left")

    def test_key_covers_every_field(self):
        base = dict(vendor="chimege", api_version="v1.2", voice="FEMALE5v2", sample_rate=22050, speed="1", pitch="1",
                    take_method="none", request_text="дараа нь")
        k0 = cache_key(cache_fields(**base))
        for f, v in (("vendor", "stub"), ("api_version", "v1.3"), ("voice", "MALE4v2"), ("sample_rate", 16000),
                     ("speed", "1.1"), ("pitch", "0.9"), ("take_method", "carrier-then-v1"), ("request_text", "дараа")):
            self.assertNotEqual(cache_key(cache_fields(**{**base, f: v})), k0, f)


@unittest.skipUnless(shutil.which("git") and (REPO / ".git").exists(), "needs git and the checkout")
class PathRuleTest(unittest.TestCase):
    def test_inside_repo_not_ignored_refused(self):
        for rel in ("docs/voice-out", "backend/tools/voice-pack/tests/tmp-cache"):
            with self.assertRaises(ConfigError):
                check_path_allowed(REPO / rel, REPO, "--out")

    def test_ignored_paths_allowed(self):
        for rel in ("backend/tools/voice-pack/out", "backend/tools/voice-pack/cache", "backend/tools/voice-pack/out/x/y"):
            self.assertEqual(check_path_allowed(REPO / rel, REPO, "--out"), REPO / rel)

    def test_outside_repo_allowed(self):
        p = scratch() / "anything"
        self.assertEqual(check_path_allowed(p, REPO, "--out"), p)

    def test_build_refuses_out_or_cache_in_repo_before_any_request(self):
        for out, cache in ((REPO / "docs" / "voice-out", scratch() / "c"), (scratch() / "o", REPO / "docs" / "voice-cache")):
            with self.subTest(out=out, cache=cache), FakeChimege() as fake:
                # no --ffmpeg: the path rule runs before ffmpeg is looked at (this test also runs on a host without it)
                rc, so, se = run_cli(["build", "--voice", "FEMALE5v2", "--pack-version", "1", "--synth", "chimege",
                                      "--out", str(out), "--repo", str(REPO), "--api-base", fake.base, "--cache", str(cache)],
                                     synth_factory=factory())
                self.assertEqual(rc, 2, se)
                self.assertIn("not git-ignored", se)
                self.assertEqual(len(fake.requests), 0)
            self.assertFalse((REPO / "docs" / "voice-out").exists())
            self.assertFalse((REPO / "docs" / "voice-cache").exists())
        rc, so, se = run_cli(["render", "--pack", str(scratch()), "--prompts", str(scratch() / "p.tsv"),
                              "--out", str(REPO / "docs" / "voice-render")])
        self.assertEqual(rc, 2)
        self.assertIn("not git-ignored", se)
        self.assertFalse((REPO / "docs" / "voice-render").exists())


if __name__ == "__main__":
    unittest.main()

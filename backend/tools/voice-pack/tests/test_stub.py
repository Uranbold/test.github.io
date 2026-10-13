"""B2: synthesiser interface and the deterministic stub (NAV-016 AC 16)."""

from __future__ import annotations

import json
import os
import socket
import unittest
from unittest import mock

from tests.helpers import SMALL_ENTRIES, build_argv, make_repo, run_cli, scratch
from voicepack import audio
from voicepack.synth.stub import StubSynthesiser


class StubTest(unittest.TestCase):
    def test_same_text_same_bytes(self):
        s = StubSynthesiser()
        a = s.synthesize("баруун тийш эргэнэ үү", "FEMALE5v2", 22050)
        b = StubSynthesiser().synthesize("баруун тийш эргэнэ үү", "MALE4v2", 22050)
        self.assertEqual(a, b)
        self.assertNotEqual(a, s.synthesize("зүүн тийш эргэнэ үү", "FEMALE5v2", 22050))

    def test_shape(self):
        s = StubSynthesiser()
        text = "баруун тийш эргэнэ үү"  # 21 characters
        w = audio.read_wav(s.synthesize(text, "FEMALE5v2", 22050))
        self.assertEqual((w.rate, w.channels, w.bits, w.format_tag), (22050, 1, 16, 1))
        x = w.samples
        n_tone = round(21 * 70 * 22050 / 1000)
        edge = round(0.1 * 22050)
        self.assertEqual(len(x), edge + n_tone + edge)
        self.assertTrue(all(v == 0 for v in x[:edge]) and all(v == 0 for v in x[-edge:]))
        peak_db = audio.to_dbfs(audio.peak_abs(x))
        self.assertAlmostEqual(peak_db, -12, delta=0.01)
        # speech-like envelope never falls below the threshold inside the phrase
        self.assertEqual(audio.longest_internal_silence_ms(x, 22050), 0)

    def test_comma_gap_and_intonation(self):
        s = StubSynthesiser()
        carrier = audio.read_wav(s.synthesize("баруун тийш эргэнэ үү, дараа нь", "FEMALE5v2", 22050)).samples
        n1 = round(21 * 70 * 22050 / 1000)
        edge, gap = round(0.1 * 22050), round(0.25 * 22050)
        seg = carrier[edge + n1 : edge + n1 + gap]
        self.assertEqual(len(seg), gap)
        self.assertTrue(all(v == 0 for v in seg))
        final = audio.read_wav(s.synthesize("баруун тийш эргэнэ үү", "FEMALE5v2", 22050)).samples
        # the continuing phrase differs from the final one (rising vs falling end)
        self.assertNotEqual(list(carrier[: edge + n1]), list(final[: edge + n1]))


class StubRunTest(unittest.TestCase):
    def test_stub_run_without_token_and_without_network(self):
        repo = make_repo(scratch() / "repo")
        out = scratch() / "out"
        env = {k: v for k, v in os.environ.items() if k != "CHIMEGE_API_TOKEN"}

        def no_network(*a, **k):
            raise AssertionError("network access attempted")

        with mock.patch.dict(os.environ, env, clear=True), \
                mock.patch.object(socket.socket, "connect", no_network), \
                mock.patch.object(socket.socket, "connect_ex", no_network), \
                mock.patch.object(socket, "create_connection", no_network), \
                mock.patch.object(socket, "getaddrinfo", no_network):
            rc, so, se = run_cli(build_argv(repo, out))
        self.assertEqual(rc, 0, so + se)
        m = json.loads((out / "FEMALE5v2-v1" / "pack" / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(m["source"], {"vendor": "stub", "apiVersion": "stub", "voice": "FEMALE5v2"})
        self.assertEqual(len(m["entries"]), SMALL_ENTRIES)
        self.assertIn("HTTP requests sent n/a (stub)", so)
        # clip length is proportional to the text length (70 ms per character, plus the 5 ms edge margins)
        for e in m["entries"]:
            if e["take"] == "final":
                expect = len(e["spokenText"]) * 70 + 10
                self.assertLess(abs(e["durationMs"] - expect), 25, e["key"])


if __name__ == "__main__":
    unittest.main()

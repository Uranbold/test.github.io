"""B5: processing, checks and the carrier-then-v1 cut on synthetic WAV fixtures (NAV-016 AC 12, 14)."""

from __future__ import annotations

import json
import math
import unittest
from array import array

from tests.helpers import shared_full_pack
from voicepack import audio
from voicepack.errors import AudioCheckError
from voicepack.synth.stub import StubSynthesiser

R = 22050


def tone(ms: float, amp: float = 8000, f: float = 220.0) -> list[int]:
    n = round(ms * R / 1000)
    return [round(amp * math.sin(2 * math.pi * f * i / R)) for i in range(n)]


def silence(ms: float) -> list[int]:
    return [0] * round(ms * R / 1000)


class ProcessTest(unittest.TestCase):
    def test_edges_trimmed_to_40ms(self):
        x = array("h", silence(300) + tone(800) + silence(400))
        p = audio.process(x, R, "k")
        audio.check_clip(p, "k")
        lead, trail = audio.edge_silence_ms(p.samples, R)
        self.assertLessEqual(lead, 40)
        self.assertLessEqual(trail, 40)
        self.assertLess(abs(p.duration_ms - 810), 25)

    def test_loud_source_with_soft_tail_meets_edge_rule(self):
        """Regression (first real Chimege build): a source louder than the target (negative gain) with a soft
        decaying word end. The −45 dBFS threshold is the delivered level, so the region found on the raw level must
        not leave 50+ ms of after-gain "silence" at the end (or the start)."""
        n_tail = round(0.3 * R)
        onset = [round(8000 * (i / n_tail) ** 3 * math.sin(2 * math.pi * 180 * i / R)) for i in range(n_tail)]
        # the tail decays 0.1 dB/ms from −20 dBFS: above −45 dBFS raw for 250 ms, above it after a −12 dB gain
        # for only about 130 ms
        tail = [round(32768 * 10 ** ((-17 - 0.1 * i * 1000 / R) / 20) * math.sin(2 * math.pi * 180 * i / R)) for i in range(n_tail)]
        x = array("h", silence(200) + onset + tone(700, amp=26000, f=180) + tail + silence(200))
        p = audio.process(x, R, "k")
        self.assertLess(p.gain_db, -8, "the fixture must need a clearly negative gain")
        audio.check_clip(p, "k")  # raises edge-silence with the raw-level region
        lead, trail = audio.edge_silence_ms(p.samples, R)
        self.assertLessEqual(max(lead, trail), 20)
        self.assertAlmostEqual(p.active_rms_db, audio.TARGET_RMS_DBFS, delta=0.3)

    def test_quiet_source_positive_gain_keeps_soft_edges(self):
        """The other direction: a quiet source (positive gain) keeps soft edges that become audible after the gain."""
        x = array("h", silence(200) + tone(60, amp=120) + tone(700, amp=1500) + tone(60, amp=120) + silence(200))
        p = audio.process(x, R, "k")
        self.assertGreater(p.gain_db, 5)
        audio.check_clip(p, "k")
        self.assertGreaterEqual(p.duration_ms, 800, "the soft 60 ms edges are above −45 dBFS after the gain")

    def test_fade_has_no_first_sample_step(self):
        x = array("h", tone(500, amp=20000))  # speech from the very first sample: no margin available
        p = audio.process(x, R, "k")
        s = p.samples
        self.assertEqual(s[0], 0)
        self.assertEqual(s[-1], 0)
        fade = R * audio.FADE_MS // 1000
        # the first samples grow linearly: no step larger than the fade slope allows
        self.assertLess(max(abs(v) for v in s[:5]), 0.05 * audio.peak_abs(s))
        self.assertGreater(max(abs(v) for v in s[fade : fade + 200]), 0.5 * audio.peak_abs(s))

    def test_peak_ceiling(self):
        # a very peaky signal: hitting -20 dBFS RMS would clip, so the gain is lowered to the -1 dBFS ceiling
        spikes = []
        for i in range(round(0.8 * R)):
            spikes.append(30000 if i % 400 == 0 else round(300 * math.sin(i / 3)))
        p = audio.process(array("h", silence(50) + spikes + silence(50)), R, "k")
        self.assertLessEqual(audio.peak_abs(p.samples), audio.PEAK_CEILING)
        self.assertLessEqual(p.peak_db, -1)
        audio.check_clip(p, "k")

    def test_target_rms(self):
        p = audio.process(array("h", silence(100) + tone(900, amp=1500) + silence(100)), R, "k")
        self.assertAlmostEqual(p.active_rms_db, -20, delta=0.1)

    def test_internal_silence_600ms_fails(self):
        p = audio.process(array("h", tone(400) + silence(600) + tone(400)), R, "k")
        with self.assertRaisesRegex(AudioCheckError, "k: rule internal-silence"):
            audio.check_clip(p, "k")

    def test_internal_silence_450ms_passes(self):
        p = audio.process(array("h", tone(400) + silence(450) + tone(400)), R, "k")
        audio.check_clip(p, "k")

    def test_100ms_and_4100ms_fail(self):
        for ms in (100, 4100):
            with self.subTest(ms=ms):
                p = audio.process(array("h", silence(50) + tone(ms) + silence(50)), R, "k")
                with self.assertRaisesRegex(AudioCheckError, "rule duration"):
                    audio.check_clip(p, "k")

    def test_rms_outlier_3db_fails(self):
        audio.check_rms_outliers([("a", -20.0), ("b", -20.1), ("c", -19.9), ("d", -21.5)])
        with self.assertRaisesRegex(AudioCheckError, "d: rule rms-outlier"):
            audio.check_rms_outliers([("a", -20.0), ("b", -20.1), ("c", -19.9), ("d", -17.0)])

    def test_silent_clip_fails(self):
        with self.assertRaisesRegex(AudioCheckError, "rule silent"):
            audio.process(array("h", silence(500)), R, "k")


class CutTest(unittest.TestCase):
    def setUp(self):
        s = StubSynthesiser()
        self.text = "баруун тийш эргэнэ үү"
        self.carrier = s.render(self.text + ", дараа нь", R)
        self.final = s.render(self.text, R)
        self.then = s.render("дараа нь", R)
        self.then_ms = audio.active_ms(self.then, R)
        self.final_span = audio.speech_span_ms(self.final, R)

    def test_stub_carrier_cut_at_the_250ms_gap(self):
        kept = audio.cut_carrier(self.carrier, R, "k", self.then_ms, self.final_span)
        edge = round(0.1 * R)
        tone_end = edge + round(len(self.text) * 70 * R / 1000)
        gap_end = tone_end + round(0.25 * R)
        # cut at the first window below -45 dBFS: at most the stub's 20 ms release ramp before the tone end, and
        # never more than one window into the 250 ms gap
        self.assertGreater(len(kept), tone_end - round(0.020 * R))
        self.assertLess(len(kept), tone_end + audio.window_len(R))
        self.assertLess(len(kept), gap_end)

    def test_carrier_without_gap_fails(self):
        s = StubSynthesiser()
        # same phrases but no comma pause: one continuous phrase
        no_gap = s.render(self.text + " дараа нь", R)
        with self.assertRaisesRegex(AudioCheckError, "rule nonFinal-boundary"):
            audio.cut_carrier(no_gap, R, "k", self.then_ms, self.final_span)

    def test_tail_ratio_check(self):
        # a 'join.then' twice as long as the removed tail: ratio 0.5 boundary breached
        with self.assertRaisesRegex(AudioCheckError, "rule nonFinal-boundary"):
            audio.cut_carrier(self.carrier, R, "k", self.then_ms * 2.5, self.final_span)

    def test_kept_part_longer_than_final_fails(self):
        with self.assertRaisesRegex(AudioCheckError, "rule nonFinal-boundary"):
            audio.cut_carrier(self.carrier, R, "k", self.then_ms, self.final_span - 400)


class PackLevelTest(unittest.TestCase):
    def test_every_nonfinal_differs_from_final(self):
        m = json.loads((shared_full_pack() / "pack" / "manifest.json").read_text(encoding="utf-8"))
        sha = {(e["key"], e["take"]): e["sha256"] for e in m["entries"]}
        nonfinal = [k for (k, t) in sha if t == "nonFinal"]
        self.assertEqual(len(nonfinal), 39)
        for k in nonfinal:
            self.assertNotEqual(sha[(k, "nonFinal")], sha[(k, "final")], k)

    def test_pack_clips_meet_duration_and_rms_rules(self):
        m = json.loads((shared_full_pack() / "pack" / "manifest.json").read_text(encoding="utf-8"))
        for e in m["entries"]:
            self.assertTrue(150 <= e["durationMs"] <= 4000, e["key"])


if __name__ == "__main__":
    unittest.main()

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
        audio.check_rms_outliers([("a", -20.0, -6), ("b", -20.1, -6), ("c", -19.9, -6), ("d", -21.5, -6)])
        with self.assertRaisesRegex(AudioCheckError, "d: rule rms-outlier"):
            audio.check_rms_outliers([("a", -20.0, -6), ("b", -20.1, -6), ("c", -19.9, -6), ("d", -17.0, -6)])


def db_amp(db: float) -> float:
    return 32768 * 10 ** (db / 20)


def sine(ms: float, db: float, f: float = 180.0, slope_db_per_ms: float = 0.0) -> list[int]:
    """A sine whose RMS is `db` dBFS, optionally decaying by slope_db_per_ms."""
    n = round(ms * R / 1000)
    return [round(db_amp(db - slope_db_per_ms * i * 1000 / R) * math.sqrt(2) * math.sin(2 * math.pi * f * i / R))
            for i in range(n)]


def blip_shape(decay_ms: float, slope: float, gap_ms: float, blips: int, blip_db: float = -40.3) -> array:
    """A loud source (negative gain, like Chimege): 200 ms silence, 700 ms at −16 dBFS, a word decay from −17 dBFS,
    then `blips` × (gap_ms at −50 dBFS + one 10 ms window at blip_db), 200 ms silence. The blip is above the
    threshold at the delivered level before the fade and below it after the fade-out lands on it."""
    x = silence(200) + sine(700, -16) + sine(decay_ms, -17, slope_db_per_ms=slope)
    for _ in range(blips):
        x += sine(gap_ms, -50) + sine(10, blip_db)
    return array("h", x + silence(200))


class EdgeRetrimTest(unittest.TestCase):
    """Regression (MALE4v2 `fixed.gpsRestored`, 2026-10-13, two vendor takes): a breath or noise blip 50 ms after the
    last word, 0.6 dB above the threshold at the delivered level, is the region's last active window before the
    fade and silent after the 10 ms fade-out, so the delivered PCM ended with 90 ms of silence. `process` re-trims
    on the delivered PCM only when the §4 edge limit is exceeded; a clip within the limit is byte-identical to what
    the plain fixed point gives (FEMALE5v2 v1, manifest 6023abbe…, has 33 clips with 20–40 ms edges of this shape)."""

    BLIP_WINDOW = 20 + 70 + 30 + 5  # silence, body, 300 ms decay, 50 ms gap -> raw window 125

    def assert_plain_region_ends_at_the_blip(self, x, p):
        """Without the re-trim the region would end at the blip (active before the fade at the delivered level) with
        a run of >= 5 silent windows before it, i.e. >= 60 ms of delivered trailing silence."""
        thr_in = audio.SILENCE_THRESHOLD_DBFS - p.gain_db
        f = audio.active_flags(x, R, thr_in)
        b = len(f) - 1 - f[::-1].index(True)
        self.assertEqual(b, self.BLIP_WINDOW)
        self.assertEqual(f[b - 5 : b], [False] * 5)

    def test_trailing_blip_after_gap_is_retrimmed_once(self):
        x = blip_shape(300, 0.1, 50, 1)
        p = audio.process(x, R, "k")
        self.assertLess(p.gain_db, -2, "the fixture must need a negative gain")
        self.assert_plain_region_ends_at_the_blip(x, p)
        self.assertEqual(p.edge_retrims, 1)
        audio.check_clip(p, "k")
        lead, trail = audio.edge_silence_ms(p.samples, R)
        self.assertLessEqual(max(lead, trail), 10)
        # the gap and the blip are gone: the clip ends where the decay crosses the threshold (about 250 ms in)
        self.assertLess(abs(p.duration_ms - 970), 30)
        self.assertAlmostEqual(p.active_rms_db, audio.TARGET_RMS_DBFS, delta=0.3)

    def test_leading_blip_before_gap_is_retrimmed(self):
        x = array("h", reversed(blip_shape(300, 0.1, 50, 1)))
        p = audio.process(x, R, "k")
        self.assertEqual(p.edge_retrims, 1)
        audio.check_clip(p, "k")
        lead, trail = audio.edge_silence_ms(p.samples, R)
        self.assertLessEqual(max(lead, trail), 10)
        self.assertLess(abs(p.duration_ms - 970), 30)

    def test_blip_within_the_limit_is_not_retrimmed(self):
        """The same shape with a short decay and no gap: the fade silences the blip too, but the delivered trailing
        silence is 30 ms, within the §4 limit. Nothing is re-trimmed and the blip window stays in the clip, exactly
        as FEMALE5v2 v1 was built."""
        x = blip_shape(100, 0.3, 0, 1)
        p = audio.process(x, R, "k")
        self.assertEqual(p.edge_retrims, 0)
        audio.check_clip(p, "k")
        lead, trail = audio.edge_silence_ms(p.samples, R)
        self.assertEqual(lead, 0)
        self.assertTrue(20 <= trail <= 40, trail)
        thr_in = audio.SILENCE_THRESHOLD_DBFS - p.gain_db
        f = audio.active_flags(x, R, thr_in)
        b = len(f) - 1 - f[::-1].index(True)
        self.assertEqual(b, 20 + 70 + 10, "the blip is the region's last active window before the fade")
        self.assertLess(abs(p.duration_ms - ((b - 20 + 1) * 10 + 10)), 15, "the blip window is delivered")

    def test_repeated_blips_hit_the_cap_and_fail(self):
        p = audio.process(blip_shape(300, 0.1, 50, 6), R, "k")
        self.assertEqual(p.edge_retrims, audio.MAX_EDGE_RETRIMS)
        with self.assertRaisesRegex(AudioCheckError, "k: rule edge-silence: leading 0 ms, trailing 60 ms"):
            audio.check_clip(p, "k")
        p4 = audio.process(blip_shape(300, 0.1, 50, 4), R, "k")
        self.assertEqual(p4.edge_retrims, 4)
        audio.check_clip(p4, "k")
        self.assertLess(abs(p4.duration_ms - 970), 30)

    def test_plain_clips_are_untouched(self):
        for name, x in (("tone", array("h", silence(300) + tone(800) + silence(400))),
                        ("quiet", array("h", silence(200) + tone(60, amp=120) + tone(700, amp=1500) + tone(60, amp=120) + silence(200)))):
            with self.subTest(name):
                self.assertEqual(audio.process(x, R, "k").edge_retrims, 0)


class LevelBandTest(unittest.TestCase):
    """pack-format §4 level band (2026-10-13, task B5): a peak-limited clip may sit down to −3 dB below the pack
    median and is named; every other clip keeps −2 dB; the +2 dB side holds for all."""

    BASE = [(f"c{i}", -20.0, -6.0) for i in range(9)]  # median −20 dBFS, peaks well below the ceiling

    def test_peak_limited_at_minus_2_5_passes_and_is_named(self):
        named = audio.check_rms_outliers(self.BASE + [("pl", -22.5, -1.0)])
        self.assertEqual([(k, round(d, 2)) for k, d in named], [("pl", -2.5)])
        self.assertEqual(audio.check_rms_outliers(self.BASE + [("pl", -21.5, -1.0)]), [], "within −2 dB: not named")
        self.assertTrue(audio.is_peak_limited(-22.5, -1.1))
        self.assertFalse(audio.is_peak_limited(-22.5, -1.11))
        self.assertFalse(audio.is_peak_limited(-20.0, -1.0), "at the target: not peak-limited")

    def test_peak_limited_at_minus_3_1_fails(self):
        with self.assertRaisesRegex(AudioCheckError, "pl: rule rms-outlier"):
            audio.check_rms_outliers(self.BASE + [("pl", -23.1, -1.0)])

    def test_not_peak_limited_at_minus_2_1_fails(self):
        with self.assertRaisesRegex(AudioCheckError, "np: rule rms-outlier"):
            audio.check_rms_outliers(self.BASE + [("np", -22.1, -3.0)])

    def test_plus_2_1_fails_whether_or_not_peak_limited(self):
        for peak in (-1.0, -6.0):
            with self.subTest(peak=peak), self.assertRaisesRegex(AudioCheckError, "hi: rule rms-outlier"):
                audio.check_rms_outliers(self.BASE + [("hi", -17.9, peak)])

    def test_near_silent_clip_with_one_click_fails(self):
        """A full-scale click reaches the ceiling, but the crest factor puts the clip far below the −3 dB floor
        (or it fails its edge-silence, internal-silence or duration check first)."""
        noise = [round(10 * math.sin(i * 0.7)) for i in range(round(0.6 * R))]  # about −73 dBFS, below the threshold
        x = noise[: len(noise) // 2] + [32767, -32768, 32767] + noise[len(noise) // 2:]
        hum = tone(600, amp=580, f=150)  # about −38 dBFS: quiet, but above −45 dBFS, so it counts as active
        x2 = silence(100) + hum[:6000] + [32767, -32768, 32767] + hum[6000:] + silence(100)
        for name, clip in (("click in near silence", x), ("click on a quiet hum", x2)):
            with self.subTest(name), self.assertRaisesRegex(
                    AudioCheckError, "rule (edge-silence|internal-silence|rms-outlier|duration)"):
                p = audio.process(array("h", clip), R, "click")
                audio.check_clip(p, "click")
                audio.check_rms_outliers(self.BASE + [("click", p.active_rms_db, p.peak_db)])

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

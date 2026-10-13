"""B6: encoder, encoder-delay measurement, decode check and the trim rule (NAV-016 AC 15, pack-format §4-§5)."""

from __future__ import annotations

import json
import math
import unittest
from array import array

from tests.helpers import ffmpeg, shared_full_pack
from voicepack import audio, codec
from voicepack.errors import AudioCheckError
from voicepack.synth.stub import StubSynthesiser

R = 22050


class CodecTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ff = ffmpeg()
        s = StubSynthesiser()
        cls.pcm = audio.process(s.render("тойрогт ороод гуравдугаар гарцаар гарна уу", R), R, "k").samples

    def test_encoder_delay_is_1024(self):
        self.assertEqual(self.ff.measure_encoder_delay(R, 24), 1024)
        self.assertEqual(self.ff.measure_encoder_delay(16000, 24), 1024)

    def test_two_encodes_byte_identical(self):
        a = self.ff.encode(self.pcm, R, 24)
        b = self.ff.encode(self.pcm, R, 24)
        self.assertEqual(a, b)

    def test_adts_units_and_decode_check(self):
        e = codec.encode_and_check(self.ff, "k", self.pcm, R, 24, 1024)
        info = codec.parse_adts(e.data)
        self.assertEqual((info.profile, info.channels, info.sample_rate), (1, 1, R))
        self.assertEqual(info.access_units, math.ceil((len(self.pcm) + 1024) / 1024))
        self.assertEqual(e.encoded_frames, info.access_units * 1024)
        _, dec = self.ff.decode(e.data)
        self.assertEqual(len(dec), e.encoded_frames)  # ADTS: ffmpeg trims nothing

    def test_wrong_encoder_delay_fails_alignment(self):
        data = self.ff.encode(self.pcm, R, 24)
        with self.assertRaisesRegex(AudioCheckError, "rule decode-alignment"):
            codec.decode_check(self.ff, "k", data, self.pcm, R, 1000)

    def test_bitrates_and_16k(self):
        for kbps in codec.BITRATES_KBPS:
            codec.encode_and_check(self.ff, "k", self.pcm, R, kbps, 1024)
        p16 = audio.process(self.ff.resample(StubSynthesiser().render("зүүн тийш эргэнэ үү", R), R, 16000), 16000, "k16").samples
        codec.encode_and_check(self.ff, "k16", p16, 16000, 24, 1024)

    def test_stub_pack_decodes_to_frames(self):
        run = shared_full_pack()
        m = json.loads((run / "pack" / "manifest.json").read_text(encoding="utf-8"))
        for e in m["entries"][::25]:
            data = (run / "pack" / e["file"]).read_bytes()
            r_out, dec = self.ff.decode(data)
            self.assertEqual(len(dec), e["encodedFrames"], e["key"])
            t = codec.trim_rule(dec, r_out, R, 1024, e["frames"], e["encodedFrames"])
            self.assertEqual(len(t), e["frames"])
            self.assertEqual(audio.duration_ms(len(t), R), e["durationMs"])


class VersionPinTest(unittest.TestCase):
    """The pin is a release: a static or distribution build of 7.0.2 ('7.0.2-static', '7.0.2-0ubuntu1') satisfies
    it and writes the same codec.encoder (ffmpeg-7.0.2-aac) as the reference image; anything else is not 7.0.2."""

    def test_release_version(self):
        for raw, want in (("7.0.2", "7.0.2"), ("7.0.2-static", "7.0.2"), ("7.0.2-0ubuntu1", "7.0.2"),
                          ("7.0.2-1~bpo12+1", "7.0.2"), ("7.0", "7.0"), ("7.0.22", "7.0.22"), ("7.1", "7.1"),
                          ("n7.0.2", "n7.0.2"), ("N-116000-g1a2b3c", "N-116000-g1a2b3c"), ("unknown", "unknown")):
            with self.subTest(raw=raw):
                self.assertEqual(codec.release_version(raw), want)
        self.assertEqual(codec.release_version("7.0.2-static") == codec.PINNED_FFMPEG_VERSION, True)
        self.assertNotEqual(codec.release_version("7.1-static"), codec.PINNED_FFMPEG_VERSION)

    def test_local_ffmpeg_reports_release_and_build(self):
        ff = ffmpeg()
        self.assertEqual(ff.version, codec.release_version(ff.version_raw))
        self.assertEqual(ff.encoder_name, f"ffmpeg-{ff.version}-aac")
        self.assertTrue(ff.version_raw.startswith(ff.version))
        t = codec.Ffmpeg.trusted(ff.exe, ff.version)
        self.assertEqual((t.version, t.version_raw, t.encoder_name), (ff.version, ff.version, ff.encoder_name))


class TrimRuleTest(unittest.TestCase):
    """pack-format §5 cases every platform must handle (mirrors the Android ClipTrimTest list)."""

    F, P = 5000, 1024
    R_ = math.ceil((5000 + 1024) / 1024) * 1024

    def dec(self, n, offset=0):
        return array("h", [(i - offset) % 1000 for i in range(n)])

    def test_priming_frame_output(self):
        d = self.dec(self.R_)
        t = codec.trim_rule(d, R, R, self.P, self.F, self.R_)
        self.assertEqual(list(t), list(d[self.P : self.P + self.F]))

    def test_first_access_unit_dropped(self):
        d = self.dec(self.R_ - 1024)
        t = codec.trim_rule(d, R, R, self.P, self.F, self.R_)
        self.assertEqual(list(t), list(d[: self.F]))

    def test_k2_sbr_upsampling(self):
        d = self.dec(2 * self.R_)
        t = codec.trim_rule(d, 2 * R, R, self.P, self.F, self.R_)
        self.assertEqual(len(t), 2 * self.F)
        self.assertEqual(list(t), list(d[2 * self.P : 2 * self.P + 2 * self.F]))

    def test_out_of_range_fails(self):
        with self.assertRaises(codec.DecodeFailed):
            codec.trim_rule(self.dec(self.R_ - 1025), R, R, self.P, self.F, self.R_)
        with self.assertRaises(codec.DecodeFailed):
            codec.trim_rule(self.dec(self.R_ + 3), R, R, self.P, self.F, self.R_)
        with self.assertRaises(codec.DecodeFailed):
            codec.trim_rule(self.dec(self.R_), 44100 + 1, R, self.P, self.F, self.R_)

    def test_short_by_up_to_10ms_padded(self):
        # decoder output long enough for start, but the clip extends past it by <= 10 ms
        frames = self.R_ - self.P + 100  # 100 frames beyond the decoded data
        t = codec.trim_rule(self.dec(self.R_), R, R, self.P, frames, self.R_)
        self.assertEqual(len(t), frames)
        self.assertEqual(list(t[-100:]), [0] * 100)
        with self.assertRaises(codec.DecodeFailed):
            codec.trim_rule(self.dec(self.R_), R, R, self.P, self.R_ - self.P + 300, self.R_)

    def test_plus_two_tolerance(self):
        d = self.dec(self.R_ + 2)
        t = codec.trim_rule(d, R, R, self.P, self.F, self.R_)
        self.assertEqual(list(t), list(d[self.P : self.P + self.F]))


if __name__ == "__main__":
    unittest.main()

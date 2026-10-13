package mn.navmn.app.voice.pack

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * NAV-016 M7 (AC 15): the pack-format §5 trim rule on synthetic decoder outputs. The "clip" is a ramp 1, 2, …, F at
 * the clip rate; the encoder put P = 1,024 priming frames before it and padded to whole access units (R frames).
 */
class ClipTrimTest {
    private val codec = PackCodec(sampleRateHz = 22_050, encoderDelayFrames = 1_024, bitrateKbps = 24)
    private val f = 5_000
    private val r = ((f + 1_024 + 1_023) / 1_024) * 1_024 // 6,144
    private val entry = PackEntry("instr.turn.right", Take.FINAL, "баруун тийш эргэнэ үү", "clips/instr.turn.right.final.aac", f, r, 227, "0".repeat(64))
    private val ramp = ShortArray(f) { (it + 1).toShort() }

    /** The full ADTS decode: priming (noise 7), the clip, padding (noise 9) to R frames. */
    private fun full(k: Int = 1, channels: Int = 1): ShortArray {
        val mono = ShortArray(1_024 * k) { 7 } + ShortArray(f * k) { (it / k + 1).toShort() } + ShortArray((r - f - 1_024) * k) { 9 }
        return if (channels == 1) mono else ShortArray(mono.size * channels) { if (it % channels == 0) mono[it / channels] else -1 }
    }

    @Test
    fun decoderThatOutputsThePrimingFrame() {
        val out = ClipTrim.trim(DecodedPcm(22_050, 1, full()), entry, codec)
        assertArrayEquals(ramp, out)
    }

    @Test
    fun decoderThatDropsTheFirstAccessUnit() {
        val pcm = full().copyOfRange(1_024, full().size) // D = R − 1,024 → missing = P → start 0
        assertArrayEquals(ramp, ClipTrim.trim(DecodedPcm(22_050, 1, pcm), entry, codec))
    }

    @Test
    fun stereoUsesChannelZeroAndTwoExtraFramesAreTolerated() {
        assertArrayEquals(ramp, ClipTrim.trim(DecodedPcm(22_050, 2, full(channels = 2)), entry, codec))
        val plus2 = full() + shortArrayOf(9, 9)
        assertArrayEquals(ramp, ClipTrim.trim(DecodedPcm(22_050, 1, plus2), entry, codec))
    }

    @Test
    fun implicitSbrAtTwiceTheRate() {
        val out = ClipTrim.trim(DecodedPcm(44_100, 1, full(k = 2)), entry, codec)
        assertEquals(2 * f, out.size)
        assertEquals(1, out[0].toInt())
        assertEquals(f, out.last().toInt())
    }

    @Test
    fun outOfRangeFails() {
        assertThrows(ClipDecodeException::class.java) { ClipTrim.trim(DecodedPcm(48_000, 1, full()), entry, codec) } // k not 1 or 2
        assertThrows(ClipDecodeException::class.java) { ClipTrim.trim(DecodedPcm(22_050, 1, full() + ShortArray(10)), entry, codec) } // longer than R + 2
        assertThrows(ClipDecodeException::class.java) { ClipTrim.trim(DecodedPcm(22_050, 1, full().copyOf(r - 2_000)), entry, codec) } // missing > P
    }

    @Test
    fun shortByAtMostTenMillisecondsIsPadded() {
        // With V8 (frames ≤ encodedFrames − encoderDelayFrames) at least F frames always remain after `start`; step 4
        // pads only entries whose encodedFrames is short of P + F, by at most 10 ms (220 frames at 22,050 Hz).
        val e200 = entry.copy(encodedFrames = 1_024 + f - 200)
        val out = ClipTrim.trim(DecodedPcm(22_050, 1, ShortArray(1_024) { 7 } + ramp.copyOf(f - 200)), e200, codec)
        assertEquals(f, out.size)
        assertArrayEquals(ramp.copyOf(f - 200), out.copyOf(f - 200))
        assertArrayEquals(ShortArray(200), out.copyOfRange(f - 200, f))
        val e300 = entry.copy(encodedFrames = 1_024 + f - 300)
        assertThrows(ClipDecodeException::class.java) { ClipTrim.trim(DecodedPcm(22_050, 1, ShortArray(1_024) { 7 } + ramp.copyOf(f - 300)), e300, codec) }
    }
}

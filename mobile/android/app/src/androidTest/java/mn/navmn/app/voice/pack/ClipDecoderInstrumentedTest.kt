package mn.navmn.app.voice.pack

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * NAV-016 M7 (AC 15, ADR-0010 §7 "Device"): the real framework decoder (MediaExtractor + MediaCodec) on every clip of
 * the pack bundled in the app under test, followed by the pack-format §5 trim rule.
 *
 * How to run (QA interop step, task breakdown §3.1; never with committed clips):
 *   make -C backend voice-pack-stub OUT=$TMP
 *   ./gradlew :app:connectedDebugAndroidTest -Pnav.voicePackDir=$TMP/<voice>-v<n>/pack
 * Skipped when the app under test has no pack.
 *
 * Checks per clip: the decoder output length is one of the two §5 shapes (all access units, or the first one dropped,
 * ±2 frames); the trimmed clip has exactly `frames` × k frames; its first edgeMarginMs − 2 frames are near silence and the
 * signal starts within edgeMarginMs + fadeMs (an onset shifted by an access unit or by encoder padding fails). The ±2-frame
 * onset comparison against the pre-encode PCM is the generator's decode check (B6), which has that PCM.
 */
@RunWith(AndroidJUnit4::class)
class ClipDecoderInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyClipDecodesToExactlyItsFramesWithTheOnsetInPlace() {
        val files = AssetPackFiles(context.assets)
        val manifest = files.read(VoicePackValidator.MANIFEST)
        Assume.assumeTrue("no voice pack in the app under test (build it with -Pnav.voicePackDir)", manifest != null)
        val state = VoicePackValidator.validate(files, VoiceInventory.fromAssets(context), PackPolicy.OPTIONAL)
        assertTrue("the bundled pack is not valid: $state", state is PackState.Valid)
        val pack = (state as PackState.Valid).pack
        val processing = Json.parseToJsonElement(String(manifest!!, Charsets.UTF_8)).jsonObject.getValue("processing").jsonObject
        val marginMs = processing.getValue("edgeMarginMs").jsonPrimitive.int
        val fadeMs = processing.getValue("fadeMs").jsonPrimitive.int
        val decoder = MediaCodecClipDecoder()
        val problems = ArrayList<String>()
        for (e in pack.entries) {
            val pcm = decoder.decode(files.read(e.file)!!)
            val k = pcm.sampleRateHz / pack.codec.sampleRateHz
            val missing = e.encodedFrames * k - pcm.frames
            if (!(abs(missing) <= 2 || abs(missing - pack.codec.encoderDelayFrames * k) <= 2)) {
                problems += "${e.file}: decoded ${pcm.frames} frames, encodedFrames ${e.encodedFrames} × $k"
                continue
            }
            val out = ClipTrim.trim(pcm, e, pack.codec)
            assertEquals(e.file, e.frames * k, out.size)
            val rate = pcm.sampleRateHz
            val margin = marginMs * rate / 1000
            val silence = 583 // −35 dBFS: above AAC pre-echo in the silent margin, below the faded onset
            val onset = out.indexOfFirst { abs(it.toInt()) > silence }
            if (onset < 0 || onset < margin - 2 || onset > margin + fadeMs * rate / 1000) {
                problems += "${e.file}: onset at frame $onset, expected within ${margin - 2}..${margin + fadeMs * rate / 1000}"
            }
        }
        assertEquals(problems.take(20).joinToString("\n"), 0, problems.size)
    }
}

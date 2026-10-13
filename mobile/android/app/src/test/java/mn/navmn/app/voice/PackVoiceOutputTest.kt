package mn.navmn.app.voice

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.i18n.Lang
import mn.navmn.app.instructions.ManeuverInput
import mn.navmn.app.instructions.ManeuverRules
import mn.navmn.app.instructions.VoiceContent
import mn.navmn.app.instructions.VoiceText
import mn.navmn.app.log.DebugLog
import mn.navmn.app.support.TestStrings
import mn.navmn.app.support.TestVoice
import mn.navmn.app.voice.pack.ClipDecodeException
import mn.navmn.app.voice.pack.ClipDecoder
import mn.navmn.app.voice.pack.DecodedPcm
import mn.navmn.app.voice.pack.PackEntry
import mn.navmn.app.voice.pack.PackFiles
import mn.navmn.app.voice.pack.PackPolicy
import mn.navmn.app.voice.pack.PackState
import mn.navmn.app.voice.pack.PcmSink
import mn.navmn.app.voice.pack.PromptAssembler
import mn.navmn.app.voice.pack.TestPacks
import mn.navmn.app.voice.pack.VoicePack
import mn.navmn.app.voice.pack.VoicePackSource
import mn.navmn.app.voice.pack.VoicePackValidator
import mn.navmn.app.voiceplan.PromptClass
import mn.navmn.app.voiceplan.SpokenPrompt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Executor

/**
 * NAV-016 M8 (AC 31, 32, 34, 36, 37, 38, 41–43) on Android framework shadows with a fake decoder (synthetic PCM that
 * follows the encoder layout: priming, clip, padding), a recording PCM sink and a decode thread that takes 150 ms.
 * Real MediaCodec decoding is the instrumented ClipDecoderInstrumentedTest; real audio is a device check (AC 33, 56).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PackVoiceOutputTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private val built = TestPacks.build()
    private val pack: VoicePack = (VoicePackValidator.validate(built.packFiles(), TestVoice.inventory, PackPolicy.OPTIONAL) as PackState.Valid).pack
    private val byFile = pack.entries.associateBy { String(TestPacks.clipBytes(it.key, it.take.id), Charsets.US_ASCII) }

    /** The clip of [e] as the reference decoder outputs it: 1,024 priming frames, the clip (value = index + 1), padding. */
    private fun decoded(e: PackEntry): ShortArray =
        ShortArray(1_024) { 7 } + ShortArray(e.frames) { (e.key.hashCode() % 1000 + 1).toShort() } + ShortArray(e.encodedFrames - e.frames - 1_024) { 9 }

    private inner class FakeDecoder(private val failOn: String? = null) : ClipDecoder {
        val decodedFiles = ArrayList<String>()
        override fun decode(bytes: ByteArray): DecodedPcm {
            val e = byFile.getValue(String(bytes, Charsets.US_ASCII))
            decodedFiles += e.file
            if (e.key == failOn) throw ClipDecodeException("broken clip")
            return DecodedPcm(22_050, 1, decoded(e))
        }
    }

    private class Started(val pcm: ShortArray, val rate: Int, val atMs: Long, val focus: Any?)

    private inner class Sinks(private val startOk: Boolean = true) : mn.navmn.app.voice.pack.PcmSinkFactory {
        val started = ArrayList<Started>()
        var healthy = true
        var stopped = 0
        var attributes: AudioAttributes? = null
        override fun create(attributes: AudioAttributes): PcmSink {
            this.attributes = attributes
            return object : PcmSink {
                override fun start(pcm: ShortArray, sampleRateHz: Int): Boolean {
                    if (!startOk) return false
                    started += Started(pcm, sampleRateHz, SystemClock.uptimeMillis(), shadowOf(audio).lastAudioFocusRequest)
                    return true
                }
                override fun healthy(): Boolean = healthy
                override fun stop() { stopped++ }
            }
        }
    }

    /** A decode thread that needs 150 ms per prompt (on the virtual clock). */
    private val slowDecode = Executor { r -> main.postDelayed(r, 150) }

    private class Built(val voice: VoiceOutput, val done: ArrayList<Long>, val fallbacks: IntArray)

    private fun output(files: PackFiles = built.packFiles(), decoder: ClipDecoder = FakeDecoder(), sinks: Sinks = Sinks(), policy: PackPolicy = PackPolicy.OPTIONAL): Built {
        val source = VoicePackSource(files, { TestVoice.inventory }, policy, DebugLog.NONE, Executor { it.run() })
        val v = VoiceOutput(ctx, source, decoder, sinks, slowDecode, DebugLog.NONE)
        val done = ArrayList<Long>()
        val fallbacks = intArrayOf(0)
        v.onDone = { done += it }
        v.onFallback = { fallbacks[0]++ }
        v.prepare()
        idle()
        ShadowTextToSpeech.getLastTextToSpeechInstance()?.let { shadowOf(it).onInitListener?.onInit(TextToSpeech.SUCCESS) }
        idle()
        return Built(v, done, fallbacks)
    }

    private fun prompt(id: Long, c: VoiceContent, lang: Lang = Lang.MN): SpokenPrompt {
        val p = VoiceText.prompt(c, lang, TestStrings.of(lang), TestVoice.inventory)!!
        return SpokenPrompt(id, p.display, lang, PromptClass.MANEUVER, 0 to 1, 0, p.spoken, p.segments)
    }

    private val chained = VoiceContent.Maneuver(
        ManeuverRules.key(ManeuverInput("roundabout", "right", 2.0)),
        200.0,
        ManeuverRules.key(ManeuverInput("turn", "slight right")),
    )

    private fun expectedPcm(p: SpokenPrompt): ShortArray =
        PromptAssembler.assemble(p.segments.map { s -> ShortArray(pack.entry(s)!!.frames) { (s.key.hashCode() % 1000 + 1).toShort() } }, p.segments, pack, 22_050)

    @Test
    fun ac31SegmentsInOrderWithThePausesWithinOneSecondInOneSession() {
        val sinks = Sinks()
        val b = output(sinks = sinks)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val p = prompt(1, chained)
        val t0 = SystemClock.uptimeMillis()
        b.voice.play(p)
        idle(1_000)
        assertEquals("one audio session for the whole prompt", 1, sinks.started.size)
        val s = sinks.started.single()
        assertTrue("AC 31: first audio ${s.atMs - t0} ms after the trigger", s.atMs - t0 <= 1_000)
        assertEquals(22_050, s.rate)
        assertArrayEquals("AC 21/31: the trimmed clips in order with joinPauseMs/thenPauseMs between them", expectedPcm(p), s.pcm)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, sinks.attributes!!.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, sinks.attributes!!.contentType)
        assertEquals("A1 never shows with a valid pack (AC 42)", 0, b.fallbacks[0])
        // ends at its duration (+ 50 ms), focus released ≤ 1 s after the last segment (AC 34)
        val durationMs = s.pcm.size * 1000L / 22_050
        idle(durationMs)
        assertEquals(listOf(1L), b.done)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertSame("AC 34: one focus request per prompt, not per segment", s.focus, shadowOf(audio).lastAudioFocusRequest)
    }

    @Test
    fun ac32AMongolianDeviceVoiceGetsNoUtterance() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("mn-MN"))
        ShadowTextToSpeech.addVoice(Voice("mn-voice", Locale.forLanguageTag("mn-MN"), Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
        val sinks = Sinks()
        val b = output(sinks = sinks)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        b.voice.play(prompt(2, VoiceContent.GpsLost))
        idle(5_000)
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertTrue("AC 32: 0 Mongolian utterances to TextToSpeech", tts == null || shadowOf(tts).spokenTextList.isEmpty())
        assertEquals("the pack played", 1, sinks.started.size)
        assertEquals(listOf(2L), b.done)
    }

    @Test
    fun ac34FocusDeniedSkipsTheWholePrompt() {
        val sinks = Sinks()
        val b = output(sinks = sinks)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        b.voice.play(prompt(3, chained))
        idle(1_000)
        assertEquals("nothing plays", 0, sinks.started.size)
        assertEquals("skipped, reported done, not queued", listOf(3L), b.done)
        assertEquals(0, b.fallbacks[0])
    }

    @Test
    fun ac36And43ADecodeFailureBeforeTheStartChimesAndFallsBackForTheSession() {
        val sinks = Sinks()
        val decoder = FakeDecoder(failOn = "join.then")
        val b = output(decoder = decoder, sinks = sinks)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        b.voice.play(prompt(4, chained))
        idle(1_000)
        assertEquals("AC 36: never a partial prompt", 0, sinks.started.size)
        assertEquals("AC 43: A1 shows", 1, b.fallbacks[0])
        assertEquals("the chime ended within 1 s", listOf(4L), b.done)
        // the rest of the session is the chime: no decode, no pack audio
        val decodedBefore = decoder.decodedFiles.size
        b.voice.play(prompt(5, VoiceContent.OffRoute))
        idle(1_000)
        assertEquals(0, sinks.started.size)
        assertEquals(decodedBefore, decoder.decodedFiles.size)
        assertEquals(listOf(4L, 5L), b.done)
        // a new session uses the pack again
        b.voice.newSession()
        idle()
        b.voice.play(prompt(6, VoiceContent.OffRoute))
        idle(1_000)
        assertEquals(1, sinks.started.size)
    }

    @Test
    fun ac36And43AnOutputErrorDuringThePromptStopsWithinOneSecond() {
        val sinks = Sinks()
        val b = output(sinks = sinks)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        b.voice.play(prompt(7, chained))
        idle(300)
        assertEquals(1, sinks.started.size)
        sinks.healthy = false
        idle(1_000)
        assertEquals("stopped and reported done within 1 s", listOf(7L), b.done)
        assertTrue(sinks.stopped >= 1)
        assertEquals("AC 43: A1", 1, b.fallbacks[0])
        b.voice.play(prompt(8, VoiceContent.OffRoute))
        idle(1_000)
        assertEquals("the rest of the session is the chime", 1, sinks.started.size)
    }

    @Test
    fun ac41NoPackOrAnInvalidPackIsTheChimeWithA1() {
        for (files in listOf(PackFiles { null }, PackFiles { path -> if (path == "manifest.json") "{".toByteArray() else null })) {
            val sinks = Sinks()
            val b = output(files = files, sinks = sinks)
            shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            b.voice.play(prompt(9, VoiceContent.GpsRestored))
            idle(1_000)
            assertEquals(0, sinks.started.size)
            assertEquals(listOf(9L), b.done)
            assertEquals(1, b.fallbacks[0])
            val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
            assertNull("AC 41: device mn TTS is not used", tts?.let { shadowOf(it).lastSpokenText })
        }
    }

    @Test
    fun aStubPackInAReleaseBuildFallsBack() {
        val sinks = Sinks()
        val b = output(sinks = sinks, policy = PackPolicy.REQUIRED_REAL)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        b.voice.play(prompt(10, VoiceContent.OffRoute))
        idle(1_000)
        assertEquals("AC 44: STUB_NOT_ALLOWED → fallback", 0, sinks.started.size)
        assertEquals(1, b.fallbacks[0])
    }

    @Test
    fun aMissingSegmentIsTheChimeNeverAPartialPrompt() {
        val sinks = Sinks()
        val b = output(sinks = sinks)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        // 3 km ahead (a walking catch-up outside the AC 27 domain): dist.km.3 is not in the pack
        b.voice.play(prompt(11, VoiceContent.Maneuver(ManeuverRules.key(ManeuverInput("turn", "right")), 3_000.0)))
        idle(1_000)
        assertEquals(0, sinks.started.size)
        assertEquals(listOf(11L), b.done)
        assertEquals("the pack is valid: no A1", 0, b.fallbacks[0])
    }

    @Test
    fun stopEndsTheWholePromptAndReleasesFocus() {
        val sinks = Sinks()
        val b = output(sinks = sinks)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        b.voice.play(prompt(12, chained))
        idle(300)
        b.voice.stop() // mute, off-route, language switch, «Дуусгах»
        idle()
        assertEquals(listOf(12L), b.done)
        assertTrue(sinks.stopped >= 1)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        idle(10_000)
        assertEquals("no second completion", listOf(12L), b.done)
    }

    @Test
    fun ac38EnglishUsesTtsAndNeverShowsA1() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        ShadowTextToSpeech.addVoice(Voice("en-voice", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
        val sinks = Sinks()
        val b = output(sinks = sinks)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        b.voice.play(prompt(13, VoiceContent.OffRoute, Lang.EN))
        idle(100)
        assertEquals("the pack is not used for English", 0, sinks.started.size)
        assertEquals(0, b.fallbacks[0])
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()!!
        assertEquals("You have left the route", shadowOf(tts).lastSpokenText)
    }

    @Test
    fun ac37NoNetworkTypesAreUsedByThePackPath() {
        // AC 37: the pack path reads only APK assets (AssetPackFiles) and decodes locally; its sources reference no
        // network API. (NAV-005 AC 65's session-wide request count is covered by the replay tests.)
        val dir = java.io.File(TestStrings.repoRoot, "mobile/android/app/src/main/java/mn/navmn/app/voice")
        val net = Regex("okhttp|java\\.net\\.|HttpURLConnection|URL\\(")
        val hits = dir.walkTopDown().filter { it.extension == "kt" }.filter { net.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(emptyList<String>(), hits)
    }
}

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
import mn.navmn.app.voice.pack.AudioTrackSink
import mn.navmn.app.voice.pack.ClipDecodeException
import mn.navmn.app.voice.pack.ClipDecoder
import mn.navmn.app.voice.pack.DecodedPcm
import mn.navmn.app.voice.pack.FrameworkLikeTrack
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
import mn.navmn.app.voice.pack.Segment
import mn.navmn.app.voice.pack.Take
import mn.navmn.app.voiceplan.PlaybackListener
import mn.navmn.app.voiceplan.PlaybackQueue
import mn.navmn.app.voiceplan.Speaker
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

    private fun output(
        files: PackFiles = built.packFiles(),
        decoder: ClipDecoder = FakeDecoder(),
        sinks: Sinks = Sinks(),
        policy: PackPolicy = PackPolicy.OPTIONAL,
        validation: Executor = Executor { it.run() },
    ): Built {
        val source = VoicePackSource(files, { TestVoice.inventory }, policy, DebugLog.NONE, validation)
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

    private fun prompt(id: Long, c: VoiceContent, lang: Lang = Lang.MN, triggerAtMs: Long = 0, cls: PromptClass = PromptClass.MANEUVER): SpokenPrompt {
        val p = VoiceText.prompt(c, lang, TestStrings.of(lang), TestVoice.inventory)!!
        return SpokenPrompt(id, p.display, lang, cls, 0 to id.toInt(), triggerAtMs, p.spoken, p.segments)
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

    /**
     * Regression, PO phone test 2026-10-13 ("only the chime sounds", FEMALE5v2 and MALE4v2 demo APKs): the production
     * sink [AudioTrackSink] over a track that follows the framework MODE_STATIC state machine (STATE_NO_STATIC_DATA until
     * the write). Every Mongolian prompt of a session plays from the pack; no PLAYBACK_FAILED, no A1, no chime.
     */
    @Test
    fun theProductionSinkPlaysEveryPromptOfTheSessionFromThePack() {
        val tracks = ArrayList<FrameworkLikeTrack>()
        val sinks = mn.navmn.app.voice.pack.PcmSinkFactory { AudioTrackSink { _, _ -> FrameworkLikeTrack().also { tracks += it } } }
        val source = VoicePackSource(built.packFiles(), { TestVoice.inventory }, PackPolicy.OPTIONAL, DebugLog.NONE, Executor { it.run() })
        val v = VoiceOutput(ctx, source, FakeDecoder(), sinks, slowDecode, DebugLog.NONE)
        val done = ArrayList<Long>()
        var fallbacks = 0
        v.onDone = { done += it }
        v.onFallback = { fallbacks++ }
        v.prepare()
        idle()
        val prompts = listOf(prompt(21, chained), prompt(22, VoiceContent.OffRoute), prompt(23, VoiceContent.GpsLost))
        for (p in prompts) {
            shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            v.play(p)
            idle(10_000)
        }
        assertEquals("no PLAYBACK_FAILED: A1 never shows", 0, fallbacks)
        assertEquals(prompts.map { it.id }, done)
        assertEquals("one static track per prompt, each written then played", 3, tracks.size)
        for ((p, t) in prompts.zip(tracks)) {
            assertEquals(1, t.plays)
            assertArrayEquals("the prompt's pack PCM", expectedPcm(p), t.written)
            assertTrue("released after the prompt", t.released)
        }
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

    // ------------------------------------------------------------------------------------------------ G1 (AC 35)

    /**
     * The production wiring of GuidanceCore: its PlaybackQueue drives VoiceOutput as the Speaker, and each playback rule
     * reaches the queue as one call (mute, language switch, phone call: clear(); off-route: clear() + the off-route
     * prompt; «Дуусгах»: close(); the 3 s drop and arrival: tick()). Which input triggers which call is covered on the
     * GuidanceCore level by the NAV-005/NAV-012 tests; these tests check each call while a pack prompt is playing.
     */
    private inner class Wired(val b: Built, val sinks: Sinks) {
        val dropped = ArrayList<Long>()
        val queue = PlaybackQueue(
            object : Speaker {
                override fun play(prompt: SpokenPrompt) = b.voice.play(prompt)
                override fun stop() = b.voice.stop()
            },
            object : PlaybackListener {
                override fun onDropped(prompt: SpokenPrompt) { dropped += prompt.id }
            },
        )

        init {
            b.voice.onDone = { id -> b.done += id; queue.onDone(id, now()) }
        }

        fun now() = SystemClock.uptimeMillis()

        fun enqueue(p: SpokenPrompt) {
            shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            queue.enqueue(p, now())
        }

        /** The chained roundabout prompt playing from the pack (one AudioTrack session started). */
        fun playingChained(id: Long = 1): SpokenPrompt {
            val p = prompt(id, chained, triggerAtMs = now())
            enqueue(p)
            idle(300)
            assertEquals("the pack prompt plays", 1, sinks.started.size)
            assertTrue("long enough for the rules below", sinks.started[0].pcm.size * 1000L / 22_050 > 4_000)
            return p
        }
    }

    private fun wired(): Wired {
        val sinks = Sinks()
        return Wired(output(sinks = sinks), sinks)
    }

    /** The stop of a pack prompt: AudioTrack stopped, focus released, the prompt reported done once, within [boundMs]. */
    private fun assertStopped(w: Wired, id: Long, boundMs: Long, rule: String) {
        val t0 = SystemClock.uptimeMillis()
        val stoppedBefore = w.sinks.stopped
        idle()
        assertTrue("$rule: the AudioTrack stopped", w.sinks.stopped > stoppedBefore)
        assertTrue("$rule: within $boundMs ms", SystemClock.uptimeMillis() - t0 <= boundMs)
        assertEquals("$rule: done once", 1, w.b.done.count { it == id })
        assertNotNull("$rule: focus released", shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun g1MuteStopsAPlayingPackPromptWithinOneSecond() {
        val w = wired()
        w.playingChained()
        w.queue.clear() // GuidanceCore.setMuted(true)
        assertStopped(w, 1, 1_000, "mute")
        idle(10_000)
        assertEquals("nothing resumes", 1, w.sinks.started.size)
    }

    @Test
    fun g1APromptThatCannotStartWithin3sIsDroppedWhileAPackPromptPlays() {
        val w = wired()
        w.playingChained()
        w.enqueue(prompt(2, VoiceContent.GpsLost, triggerAtMs = w.now()))
        idle(3_100)
        w.queue.tick(w.now())
        assertEquals("one prompt at a time; the waiting one is dropped after 3 s", listOf(2L), w.dropped)
        assertEquals("the playing pack prompt is not interrupted", 0, w.sinks.stopped)
        idle(10_000)
        assertEquals(1, w.sinks.started.size)
        assertEquals(listOf(1L), w.b.done)
    }

    @Test
    fun g1OffRouteStopsThePackPromptAndPlaysFixedOffRouteFromThePack() {
        val w = wired()
        w.playingChained()
        // GuidanceCore on an off-route detection: queue.clear(), then the off-route prompt
        w.queue.clear()
        val off = prompt(2, VoiceContent.OffRoute, triggerAtMs = w.now())
        w.enqueue(off)
        assertStopped(w, 1, 1_000, "off-route")
        idle(1_000)
        assertEquals("fixed.offRoute played from the pack", 2, w.sinks.started.size)
        assertEquals(listOf(Segment("fixed.offRoute", Take.FINAL)), off.segments)
        assertArrayEquals(expectedPcm(off), w.sinks.started[1].pcm)
        // the catch-up prompt after the reroute comes from the pack too
        idle(w.sinks.started[1].pcm.size * 1000L / 22_050 + 100)
        val catchUp = prompt(3, VoiceContent.Maneuver(ManeuverRules.key(ManeuverInput("turn", "left")), 400.0), triggerAtMs = w.now())
        w.enqueue(catchUp)
        idle(1_000)
        assertEquals(3, w.sinks.started.size)
        assertArrayEquals(expectedPcm(catchUp), w.sinks.started[2].pcm)
        assertEquals(0, w.b.fallbacks[0])
    }

    @Test
    fun g1LanguageSwitchStopsThePackPromptAndTheNextPromptUsesEnglishTts() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        ShadowTextToSpeech.addVoice(Voice("en-voice", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
        val w = wired()
        w.playingChained()
        w.b.voice.onLanguageChanged() // GuidanceEngine, then GuidanceCore.setLanguage → queue.clear()
        w.queue.clear()
        assertStopped(w, 1, 1_000, "language switch")
        w.enqueue(prompt(2, VoiceContent.OffRoute, Lang.EN, triggerAtMs = w.now()))
        idle(100)
        assertEquals("English is not played from the pack", 1, w.sinks.started.size)
        assertEquals("You have left the route", shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()!!).lastSpokenText)
        assertEquals(0, w.b.fallbacks[0])
    }

    @Test
    fun g1EndStopsAnyAudioWithinTwoSecondsAndNothingPlaysAfterIt() {
        val w = wired()
        w.playingChained()
        w.queue.close() // «Дуусгах»: GuidanceCore.end()
        assertStopped(w, 1, 2_000, "«Дуусгах»")
        w.enqueue(prompt(2, VoiceContent.OffRoute, triggerAtMs = w.now()))
        idle(5_000)
        assertEquals("nothing after the end", 1, w.sinks.started.size)
        assertEquals(listOf(2L), w.dropped)
    }

    @Test
    fun g1APhoneCallStopsThePackPromptAndOneCatchUpFollowsFromThePack() {
        val w = wired()
        w.playingChained()
        w.queue.clear() // GuidanceCore.onCallSignal(true) → CallGate STARTED
        assertStopped(w, 1, 500, "phone call")
        idle(20_000) // the call
        // GuidanceCore.onCallEnded: exactly one catch-up prompt for the current next manoeuvre
        val catchUp = prompt(2, VoiceContent.Maneuver(ManeuverRules.key(ManeuverInput("roundabout", "right", 2.0)), 150.0), triggerAtMs = w.now())
        w.enqueue(catchUp)
        idle(1_000)
        assertEquals(2, w.sinks.started.size)
        assertArrayEquals("the catch-up comes from the pack", expectedPcm(catchUp), w.sinks.started[1].pcm)
        assertEquals(0, w.b.fallbacks[0])
    }

    @Test
    fun g1ArrivalWaitsAtMost3sForThePlayingPackPrompt() {
        val w = wired()
        w.playingChained()
        val arrival = prompt(2, VoiceContent.Arrival(ManeuverRules.key(ManeuverInput("arrive", null))), triggerAtMs = w.now(), cls = PromptClass.ARRIVAL)
        w.enqueue(arrival)
        idle(2_900)
        w.queue.tick(w.now())
        assertEquals("still waiting", 1, w.sinks.started.size)
        idle(200)
        w.queue.tick(w.now())
        idle(1_000)
        assertTrue("the pack prompt was stopped", w.sinks.stopped >= 1)
        assertEquals("the arrival plays from the pack", 2, w.sinks.started.size)
        assertArrayEquals(expectedPcm(arrival), w.sinks.started[1].pcm)
    }

    // ------------------------------------------------------------------------------------------------ G2 (AC 39)

    @Test
    fun g2APendingValidationAtStartWaitsAbout2sThenChimesWithoutA1ThenThePackPlays() {
        val tasks = ArrayList<Runnable>()
        val sinks = Sinks()
        val b = output(sinks = sinks, validation = Executor { tasks += it })
        assertEquals("validation queued, not finished", 1, tasks.size)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val depart = prompt(1, VoiceContent.Depart(ManeuverRules.key(ManeuverInput("depart", null)), null))
        val t0 = SystemClock.uptimeMillis()
        b.voice.play(depart)
        idle(1_900)
        assertTrue("still waiting for the decision", b.done.isEmpty())
        assertEquals(0, sinks.started.size)
        idle(300)
        assertEquals("then the chime (pack audio never started)", 0, sinks.started.size)
        idle(1_000)
        assertEquals(listOf(1L), b.done)
        assertTrue("the wait is about 2 s", SystemClock.uptimeMillis() - t0 in VoiceOutput.DECISION_WAIT_MS..VoiceOutput.DECISION_WAIT_MS + 1_500)
        assertEquals("a pending decision is not a fallback: no A1", 0, b.fallbacks[0])
        // the validation finishes; the next prompt plays from the pack
        tasks.removeAt(0).run()
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        b.voice.play(prompt(2, VoiceContent.OffRoute))
        idle(1_000)
        assertEquals(1, sinks.started.size)
        assertEquals(0, b.fallbacks[0])
    }

    // ------------------------------------------------------------------------------------------------ G3 (AC 40)

    @Test
    fun g3ARestoreInANewProcessValidatesAgainOfflineAndPlaysFromThePack() {
        val reads = ArrayList<String>()
        val files = object : PackFiles {
            override fun read(path: String): ByteArray? = read(path, Int.MAX_VALUE)
            override fun read(path: String, limit: Int): ByteArray? {
                reads += path
                return built.packFiles().read(path, limit)
            }
        }
        // the first process validated and played
        output(files = files)
        val firstProcessReads = reads.count { it == VoicePackValidator.MANIFEST }
        assertEquals(1, firstProcessReads)
        // a new process (NAV-012 restore after process death): a new source over the same bundled files starts at Pending
        val tasks = ArrayList<Runnable>()
        val source = VoicePackSource(files, { TestVoice.inventory }, PackPolicy.OPTIONAL, DebugLog.NONE, Executor { tasks += it })
        assertSame(PackState.Pending, source.state)
        val sinks = Sinks()
        val v = VoiceOutput(ctx, source, FakeDecoder(), sinks, slowDecode, DebugLog.NONE)
        val done = ArrayList<Long>()
        var fallbacks = 0
        v.onDone = { done += it }
        v.onFallback = { fallbacks++ }
        v.newSession() // GuidanceEngine init on the restored session → packs.prepare()
        idle()
        assertEquals("validation started once on restore", 1, tasks.size)
        tasks.removeAt(0).run()
        assertTrue("Pending → Valid", source.state is PackState.Valid)
        assertEquals("the manifest was read again in the new process", 2, reads.count { it == VoicePackValidator.MANIFEST })
        assertTrue("only bundled pack paths were read (no network)", reads.all { it == VoicePackValidator.MANIFEST || it.startsWith("clips/") })
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        v.play(prompt(1, VoiceContent.GpsRestored))
        idle(1_000)
        assertEquals("the next prompt plays from the pack", 1, sinks.started.size)
        assertEquals(0, fallbacks)
    }
}

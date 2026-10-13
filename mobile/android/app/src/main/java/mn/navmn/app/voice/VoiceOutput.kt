package mn.navmn.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import mn.navmn.app.BuildConfig
import mn.navmn.app.audio.calls.AudioFocusSignals
import mn.navmn.app.audio.output.AudioOutputMonitor
import mn.navmn.app.i18n.Lang
import mn.navmn.app.log.DebugLog
import mn.navmn.app.voice.pack.AudioTrackSink
import mn.navmn.app.voice.pack.ClipDecodeException
import mn.navmn.app.voice.pack.ClipDecoder
import mn.navmn.app.voice.pack.ClipTrim
import mn.navmn.app.voice.pack.MediaCodecClipDecoder
import mn.navmn.app.voice.pack.PackEntry
import mn.navmn.app.voice.pack.PackReason
import mn.navmn.app.voice.pack.PackState
import mn.navmn.app.voice.pack.PcmSink
import mn.navmn.app.voice.pack.PcmSinkFactory
import mn.navmn.app.voice.pack.PromptAssembler
import mn.navmn.app.voice.pack.VoicePack
import mn.navmn.app.voice.pack.VoicePackSource
import mn.navmn.app.voiceplan.SpokenPrompt
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private fun voiceLog(): DebugLog = if (BuildConfig.DEBUG_LOGS) DebugLog { android.util.Log.d("navmn.voice", it) } else DebugLog.NONE

/**
 * Voice output (ADR-0009 §3.4, amended by ADR-0010 decision 1 for NAV-016):
 *  - **Mongolian** = the bundled clip pack (NAV-016): every segment of the prompt is decoded on one background thread
 *    (framework MediaCodec, the pack-format §5 trim rule, an LRU cache of about 2 MB of PCM), joined with the manifest
 *    pauses (pack-format §6) into one buffer, and played by one `AudioTrack` after one focus request. No pack, an
 *    invalid pack, or a decode or playback error → the D23 chime and the A1 notice ([onFallback]); after an error the
 *    rest of the session is the chime (AC 41–43). A prompt is all or nothing (AC 36). Device `mn` TTS is never used
 *    (D80, AC 32).
 *  - **English** = Android TextToSpeech when [VoiceSelector] finds a usable English voice, otherwise the chime
 *    (unchanged, D96). A TTS error switches to the chime for the rest of the session (AC 39).
 * Audio attributes USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, transient focus with ducking once per prompt, released after it;
 * focus denied → the prompt is skipped (AC 36 / NAV-016 AC 34). Initialisation (TTS, pack validation) starts when the
 * route preview opens ([prepare]). Engine names, voices and pack reason codes go only to the debug log (never
 * coordinates).
 */
@Singleton
class VoiceOutput internal constructor(
    private val context: Context,
    private val packs: VoicePackSource,
    private val decoder: ClipDecoder,
    private val sinks: PcmSinkFactory,
    private val decodeExecutor: Executor,
    private val log: DebugLog,
) : GuidanceVoice {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context = context,
        packs = VoicePackSource.app(context, voiceLog()),
        decoder = MediaCodecClipDecoder(),
        sinks = AudioTrackSink.FACTORY,
        decodeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "navmn-voice-decode").apply { isDaemon = true } },
        log = voiceLog(),
    )

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)

    private var tts: TextToSpeech? = null
    private var initStartedAt = 0L
    private var initDoneAt = -1L
    private var initOk = false
    private val selected = HashMap<Lang, Locale?>()
    private var sessionFallback = false
    private var focusRequest: AudioFocusRequest? = null
    private var track: AudioTrack? = null
    private var sink: PcmSink? = null
    private var current: SpokenPrompt? = null

    /** Trimmed clips by file, at the decoder's output rate (≈ 2 MB of PCM; touched only on the decode thread). */
    private class Clip(val rate: Int, val samples: ShortArray)

    private val clips = object : LruCache<String, Clip>(CLIP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Clip): Int = value.samples.size * 2
    }

    /** Called on the main thread with the id of the prompt that ended (spoken, chimed, skipped or failed). */
    @Volatile override var onDone: ((Long) -> Unit)? = null

    /** Called when a Mongolian prompt is played as a chime because there is no usable voice (navigation-ux §4.6, A1). */
    @Volatile override var onFallback: (() -> Unit)? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** NAV-012 AC 33: Bluetooth media output → a short silent lead-in before each prompt (ADR-0013 §6.4). */
    private val output = AudioOutputMonitor(audio, attributes)

    private val probe = object : TtsProbe {
        override fun setLanguage(locale: Locale): Int = tts?.setLanguage(locale) ?: -2
        override fun currentVoiceLanguage(): String? = tts?.voice?.locale?.language
    }

    /** Starts the TextToSpeech initialisation and the pack validation (route preview open, language switch). Idempotent. */
    override fun prepare() {
        packs.prepare()
        main.post { initTts() }
    }

    private fun initTts() {
        if (tts != null) return
        initStartedAt = SystemClock.elapsedRealtime()
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                initDoneAt = SystemClock.elapsedRealtime()
                initOk = status == TextToSpeech.SUCCESS
                selected.clear()
                log.d("tts init ok=$initOk engine=${tts?.defaultEngine} in ${initDoneAt - initStartedAt} ms")
                tts?.setAudioAttributes(attributes)
                tts?.setOnUtteranceProgressListener(listener)
            }
        }
    }

    /** New guidance session: the error fallback is per session (AC 39, NAV-016 AC 43). */
    override fun newSession() {
        packs.prepare() // NAV-016 AC 40: a restored session in a new process validates again
        main.post { sessionFallback = false }
    }

    private fun localeFor(lang: Lang): Locale? {
        if (sessionFallback) return null
        return selected.getOrPut(lang) {
            VoiceSelector.select(if (tts != null) probe else null, initOk, initDoneAt - initStartedAt, lang).also {
                log.d("voice for ${lang.tag}: ${it ?: "none (chime)"} voice=${tts?.voice?.name}")
            }
        }
    }

    /** false while the engine is still initialising (≤ 3 s). */
    private fun ttsDecisionReady(): Boolean = tts == null || initDoneAt >= 0 ||
        SystemClock.elapsedRealtime() - initStartedAt > VoiceSelector.INIT_TIMEOUT_MS

    override fun play(prompt: SpokenPrompt) {
        main.post { playOnMain(prompt, SystemClock.elapsedRealtime()) }
    }

    private fun playOnMain(prompt: SpokenPrompt, firstTry: Long) {
        if (tts == null) prepare()
        // navigation-ux §4.6: if the decision (pack validation, or TTS for English) is still pending, wait up to 2 s.
        val pending = if (prompt.lang == Lang.MN) packs.state is PackState.Pending && !sessionFallback else !ttsDecisionReady()
        if (pending && SystemClock.elapsedRealtime() - firstTry < DECISION_WAIT_MS) {
            main.postDelayed({ playOnMain(prompt, firstTry) }, 100)
            return
        }
        current = prompt
        val pack = packs.state
        val locale = if (prompt.lang == Lang.EN && initDoneAt >= 0) localeFor(Lang.EN) else null
        when (VoiceRouting.route(prompt.lang, pack, locale, sessionFallback)) {
            VoiceRouting.Route.PACK -> playPack(prompt, (pack as PackState.Valid).pack)
            VoiceRouting.Route.TTS -> speak(prompt, locale!!)
            VoiceRouting.Route.CHIME -> {
                if (!requestFocus()) { // AC 36: focus denied (phone call) → skipped, not queued
                    finish(prompt.id)
                    return
                }
                // A1 (Mongolian only): no pack, an invalid pack or an error this session. A still-pending decision is
                // a chime for this prompt only (navigation-ux §4.6), not a fallback.
                if (prompt.lang == Lang.MN && pack !is PackState.Pending) onFallback?.invoke()
                chime(prompt.id)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ English TTS

    private fun speak(prompt: SpokenPrompt, locale: Locale) {
        if (!requestFocus()) {
            finish(prompt.id)
            return
        }
        val leadIn = output.leadInMs()
        tts?.language = locale
        // The focus request comes first, so ducking starts during the lead-in; audio still starts ≤ 1 s (AC 33).
        val mode = if (leadIn > 0 && tts?.playSilentUtterance(leadIn.toLong(), TextToSpeech.QUEUE_FLUSH, LEAD_IN_ID) == TextToSpeech.SUCCESS) {
            TextToSpeech.QUEUE_ADD
        } else {
            TextToSpeech.QUEUE_FLUSH
        }
        val r = tts?.speak(prompt.spoken, mode, Bundle(), prompt.id.toString())
        if (r == TextToSpeech.SUCCESS) return
        sessionFallback = true
        chime(prompt.id, leadIn)
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) { main.post { utteranceId?.toLongOrNull()?.let { finish(it) } } }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { onError(utteranceId, TextToSpeech.ERROR) }
        override fun onError(utteranceId: String?, errorCode: Int) {
            main.post {
                // AC 39: a TTS error switches to the chime for the rest of the session, within 1 s.
                sessionFallback = true
                log.d("tts error $errorCode: chime for the rest of the session")
                val id = utteranceId?.toLongOrNull() ?: return@post
                chime(id)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ Mongolian pack

    private fun playPack(prompt: SpokenPrompt, pack: VoicePack) {
        val id = prompt.id
        val entries = prompt.segments.map { pack.entry(it) }
        if (entries.isEmpty() || entries.any { it == null }) {
            // A segment without a pack entry (impossible by the NAV-016 AC 27 domain test): the chime, never a partial
            // prompt (AC 36). The pack itself is valid, so this is not a session fallback.
            log.d("voice pack: a segment has no entry, chime for this prompt")
            if (!requestFocus()) finish(id) else chime(id)
            return
        }
        val leadInMs = output.leadInMs()
        decodeExecutor.execute {
            val result = runCatching { assemble(prompt, entries.map { it!! }, pack, leadInMs) }
            main.post { onAssembled(prompt, result) }
        }
    }

    /** Decode thread: all segments decoded and trimmed before anything plays (AC 36), then one buffer (pack-format §6). */
    private fun assemble(prompt: SpokenPrompt, entries: List<PackEntry>, pack: VoicePack, leadInMs: Int): Pair<ShortArray, Int> {
        val decoded = entries.map { e ->
            clips.get(e.file) ?: run {
                val bytes = packs.files.read(e.file) ?: throw ClipDecodeException("clip missing")
                val pcm = decoder.decode(bytes)
                Clip(pcm.sampleRateHz, ClipTrim.trim(pcm, e, pack.codec)).also { clips.put(e.file, it) }
            }
        }
        val rate = decoded.first().rate
        if (decoded.any { it.rate != rate }) throw ClipDecodeException("clips decoded at different rates")
        val pcm = PromptAssembler.assemble(decoded.map { it.samples }, prompt.segments, pack, rate, leadInFrames = (rate.toLong() * leadInMs / 1000).toInt())
        return pcm to rate
    }

    private fun onAssembled(prompt: SpokenPrompt, result: Result<Pair<ShortArray, Int>>) {
        val id = prompt.id
        if (current?.id != id) return // stopped while decoding
        val (pcm, rate) = result.getOrElse {
            packFallback(id, PackReason.DECODE_FAILED, chime = true)
            return
        }
        if (!requestFocus()) { // NAV-016 AC 34: focus denied → the whole prompt is skipped
            finish(id)
            return
        }
        val s = sinks.create(attributes)
        if (!s.start(pcm, rate)) {
            packFallback(id, PackReason.PLAYBACK_FAILED, chime = true, focusHeld = true)
            return
        }
        sink = s
        val durationMs = pcm.size * 1000L / rate
        main.postDelayed({ if (current?.id == id) finish(id) }, durationMs + END_MARGIN_MS)
        watch(id, s)
    }

    /** NAV-016 AC 36, 43: an output error during the prompt stops it within 1 s and the session falls back. */
    private fun watch(id: Long, s: PcmSink) {
        main.postDelayed({
            if (current?.id != id || sink !== s) return@postDelayed
            if (s.healthy()) watch(id, s) else packFallback(id, PackReason.PLAYBACK_FAILED, chime = false, focusHeld = true)
        }, WATCH_MS)
    }

    /** The rest of the session uses the chime and A1 shows once (AC 41, 43). [chime]: play it for this prompt. */
    private fun packFallback(id: Long, reason: PackReason, chime: Boolean, focusHeld: Boolean = false) {
        log.d("voice pack fallback: $reason")
        sessionFallback = true
        releaseSink()
        if (!chime) {
            onFallback?.invoke()
            finish(id)
            return
        }
        if (!focusHeld && !requestFocus()) {
            finish(id)
            return
        }
        onFallback?.invoke()
        chime(id)
    }

    private fun releaseSink() {
        sink?.stop()
        sink = null
    }

    // ------------------------------------------------------------------------------------------------ chime, focus

    private fun chime(id: Long, leadInMs: Int = output.leadInMs()) {
        val tone = ChimePcm.samples()
        // NAV-012 AC 33: prepended PCM silence on Bluetooth outputs (same lead-in as the voice).
        val pcm = if (leadInMs > 0) ShortArray(ChimePcm.SAMPLE_RATE * leadInMs / 1000) + tone else tone
        runCatching {
            track?.release()
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(ChimePcm.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            t.write(pcm, 0, pcm.size)
            t.play()
            track = t
        }
        main.postDelayed({ if (current?.id == id) finish(id) }, ChimePcm.durationMs + leadInMs + 50)
    }

    private fun finish(id: Long) {
        if (current?.id != id) return
        current = null
        releaseSink()
        abandonFocus() // AC 36: focus released within 1 s after the prompt
        onDone?.invoke(id)
    }

    override fun stop() {
        main.post {
            tts?.stop()
            runCatching { track?.stop() }
            releaseSink() // the whole pack prompt stops, never only one segment (navigation-ux §4.5 rule 8)
            val c = current
            current = null
            abandonFocus()
            if (c != null) onDone?.invoke(c.id)
        }
    }

    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            // NAV-012 AC 35: a transient loss while a prompt plays counts as a call (ADR-0013 §6.1).
            .setOnAudioFocusChangeListener { change -> AudioFocusSignals.onFocusChange(change) }
            .build()
        focusRequest = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
        AudioFocusSignals.onAbandoned()
    }

    /** Language switch (AC 60): selection re-evaluated for the next prompt. */
    override fun onLanguageChanged() {
        main.post { selected.clear() }
    }

    internal companion object {
        const val LEAD_IN_ID = "navmn-lead-in"
        /** navigation-ux §4.6: a prompt waits at most 2 s for a pending voice decision, then it is a chime. */
        const val DECISION_WAIT_MS = 2_000L
        const val CLIP_CACHE_BYTES = 2 * 1024 * 1024
        const val END_MARGIN_MS = 50L
        const val WATCH_MS = 250L
    }
}

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
import dagger.hilt.android.qualifiers.ApplicationContext
import mn.navmn.app.BuildConfig
import mn.navmn.app.i18n.Lang
import mn.navmn.app.log.DebugLog
import mn.navmn.app.voiceplan.SpokenPrompt
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADR-0009 §3.4 voice output: Android TextToSpeech (default engine) when [VoiceSelector] finds a usable voice for the
 * prompt's language, otherwise the D23 chime. Audio attributes USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, transient focus
 * with ducking, released after each prompt; focus denied → the prompt is skipped (AC 36). A TTS error during a
 * session switches to the chime for the rest of the session (AC 39). Initialisation starts when the route preview
 * opens ([prepare]). Engine and voice names go only to the debug log (never coordinates).
 */
@Singleton
class VoiceOutput @Inject constructor(@ApplicationContext private val context: Context) : GuidanceVoice {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val log: DebugLog = if (BuildConfig.DEBUG_LOGS) DebugLog { android.util.Log.d("navmn.voice", it) } else DebugLog.NONE

    private var tts: TextToSpeech? = null
    private var initStartedAt = 0L
    private var initDoneAt = -1L
    private var initOk = false
    private val selected = HashMap<Lang, Locale?>()
    private var sessionFallback = false
    private var focusRequest: AudioFocusRequest? = null
    private var track: AudioTrack? = null
    private var current: SpokenPrompt? = null

    /** Called on the main thread with the id of the prompt that ended (spoken, chimed, skipped or failed). */
    @Volatile override var onDone: ((Long) -> Unit)? = null

    /** Called when a prompt is played as a chime because there is no usable voice (navigation-ux §4.6 notice). */
    @Volatile override var onFallback: (() -> Unit)? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val probe = object : TtsProbe {
        override fun setLanguage(locale: Locale): Int = tts?.setLanguage(locale) ?: -2
        override fun currentVoiceLanguage(): String? = tts?.voice?.locale?.language
    }

    /** Starts TextToSpeech initialisation (route preview open, language switch). Idempotent. */
    override fun prepare() {
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

    /** New guidance session: the TTS-error fallback is per session (AC 39). */
    override fun newSession() {
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

    /** null while the engine is still initialising (≤ 3 s). */
    private fun decisionReady(): Boolean = tts == null || initDoneAt >= 0 ||
        SystemClock.elapsedRealtime() - initStartedAt > VoiceSelector.INIT_TIMEOUT_MS

    override fun play(prompt: SpokenPrompt) {
        main.post { playOnMain(prompt, SystemClock.elapsedRealtime()) }
    }

    private fun playOnMain(prompt: SpokenPrompt, firstTry: Long) {
        if (tts == null) prepare()
        // navigation-ux §4.6: if the decision is still pending, wait up to 2 s, then chime.
        if (!decisionReady() && SystemClock.elapsedRealtime() - firstTry < 2_000) {
            main.postDelayed({ playOnMain(prompt, firstTry) }, 100)
            return
        }
        current = prompt
        if (!requestFocus()) { // AC 36: focus denied (phone call) → skipped, not queued
            finish(prompt.id)
            return
        }
        val locale = if (initDoneAt >= 0) localeFor(prompt.lang) else null
        if (locale != null) {
            tts?.language = locale
            val r = tts?.speak(prompt.text, TextToSpeech.QUEUE_FLUSH, Bundle(), prompt.id.toString())
            if (r == TextToSpeech.SUCCESS) return
            sessionFallback = true
        }
        onFallback?.invoke()
        chime(prompt.id)
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
                onFallback?.invoke()
                chime(id)
            }
        }
    }

    private fun chime(id: Long) {
        val pcm = ChimePcm.samples()
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
        main.postDelayed({ if (current?.id == id) finish(id) }, ChimePcm.durationMs + 50)
    }

    private fun finish(id: Long) {
        if (current?.id != id) return
        current = null
        abandonFocus() // AC 36: focus released within 1 s after the prompt
        onDone?.invoke(id)
    }

    override fun stop() {
        main.post {
            tts?.stop()
            runCatching { track?.stop() }
            val c = current
            current = null
            abandonFocus()
            if (c != null) onDone?.invoke(c.id)
        }
    }

    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Language switch (AC 60): selection re-evaluated for the next prompt. */
    override fun onLanguageChanged() {
        main.post { selected.clear() }
    }
}

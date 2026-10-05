package mn.navmn.app.demo.replay

import mn.navmn.app.voice.GuidanceVoice
import mn.navmn.app.voiceplan.SpokenPrompt

/**
 * ADR-0016 §11: the real voice output (TTS or chime, unchanged) behind a pause gate. While [paused], a prompt is
 * answered with an immediate `onDone` and no sound, so the playback queue drains and nothing plays (NAV-019 AC 22);
 * [onPaused] stops the current utterance or chime. Everything else passes straight through.
 */
class PauseGatedVoice(private val real: GuidanceVoice, private val paused: () -> Boolean) : GuidanceVoice {
    override var onDone: ((Long) -> Unit)?
        get() = real.onDone
        set(value) {
            real.onDone = value
        }

    override var onFallback: (() -> Unit)?
        get() = real.onFallback
        set(value) {
            real.onFallback = value
        }

    /** Prompts answered silently while paused (tests). */
    @Volatile var silenced = 0
        private set

    override fun play(prompt: SpokenPrompt) {
        if (paused()) {
            silenced++
            real.onDone?.invoke(prompt.id)
        } else {
            real.play(prompt)
        }
    }

    override fun stop() = real.stop()

    override fun prepare() = real.prepare()

    override fun newSession() = real.newSession()

    override fun onLanguageChanged() = real.onLanguageChanged()

    /** «Түр зогсоох»: any utterance or chime stops within 1 s. */
    fun onPaused() = real.stop()
}

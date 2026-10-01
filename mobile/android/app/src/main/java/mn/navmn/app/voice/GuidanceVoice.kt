package mn.navmn.app.voice

import mn.navmn.app.voiceplan.Speaker

/**
 * What the screen and the guidance engine need from the voice output (ADR-0009 §3.4 / §10 test seam). The app binds
 * [VoiceOutput] (TextToSpeech, chime, audio focus); Robolectric Activity tests bind a recording fake (AC 64, 71).
 */
interface GuidanceVoice : Speaker {
    /** Called on the main thread with the id of the prompt that ended (spoken, chimed, skipped or failed). */
    var onDone: ((Long) -> Unit)?

    /** Called when a prompt is played as a chime because there is no usable voice (navigation-ux §4.6 notice). */
    var onFallback: (() -> Unit)?

    /** Starts the voice initialisation (route preview open). Idempotent. */
    fun prepare()

    /** New guidance session: the TTS-error fallback is per session (AC 39). */
    fun newSession()

    /** Language switch (AC 60): the voice selection is re-evaluated for the next prompt. */
    fun onLanguageChanged()
}

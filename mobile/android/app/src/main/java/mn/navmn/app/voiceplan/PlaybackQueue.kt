package mn.navmn.app.voiceplan

import mn.navmn.app.i18n.Lang

enum class PromptClass { MANEUVER, CLIENT, ARRIVAL }

/** A rendered prompt ready to be spoken (text already in the UI language at trigger time). */
data class SpokenPrompt(
    val id: Long,
    val text: String,
    val lang: Lang,
    val cls: PromptClass,
    val maneuver: Pair<Int, Int>?,
    val triggerAtMs: Long,
)

/** Plays one prompt (TextToSpeech or the chime, with audio focus). Calls back through the engine when it ends. */
interface Speaker {
    fun play(prompt: SpokenPrompt)
    fun stop()
}

/**
 * What happened to an enqueued prompt (NAV-005-D2): the voice schedule measures navigation-ux §4.2 rule 2 (8 s between
 * two prompts for the same manoeuvre) from the playback START, which can be up to 3 s after the trigger (§4.5 rule 1).
 */
interface PlaybackListener {
    /** [prompt] started playing at [atMs]. */
    fun onStarted(prompt: SpokenPrompt, atMs: Long) {}

    /** [prompt] was enqueued but will never play (replaced, timed out, cleared or refused). */
    fun onDropped(prompt: SpokenPrompt) {}

    companion object {
        val NONE = object : PlaybackListener {}
    }
}

/**
 * navigation-ux §4.5 playback rules (pure; times from the caller):
 *  1. one utterance at a time; at most one waits; a waiting prompt that cannot start within 3 s of its trigger is
 *     dropped (AC 34); a newer prompt replaces a waiting older one;
 *  2. off-route stops the current manoeuvre utterance and clears the queue ([clear]);
 *  3. arrival waits for the current utterance (max 3 s), then plays; nothing plays after it (AC 55);
 *  4./7. mute, «Дуусгах», language switch: [clear].
 */
class PlaybackQueue(private val speaker: Speaker, private val listener: PlaybackListener = PlaybackListener.NONE) {
    var current: SpokenPrompt? = null
        private set
    private var currentStartedAt = 0L
    var waiting: SpokenPrompt? = null
        private set
    /** After the arrival prompt nothing plays (AC 55). */
    var closed: Boolean = false
        private set

    /** Everything that was started, in order (debug/test visibility; never persisted). */
    val started = ArrayList<SpokenPrompt>()

    fun enqueue(p: SpokenPrompt, nowMs: Long) {
        if (closed || waiting?.cls == PromptClass.ARRIVAL) {
            listener.onDropped(p)
            return
        }
        if (current == null) {
            start(p, nowMs)
        } else {
            waiting?.let { listener.onDropped(it) }
            waiting = p
        }
    }

    fun onDone(id: Long, nowMs: Long) {
        if (current?.id != id) return
        val wasArrival = current?.cls == PromptClass.ARRIVAL
        current = null
        if (wasArrival) {
            closed = true
            waiting?.let { listener.onDropped(it) }
            waiting = null
            return
        }
        promote(nowMs)
    }

    private fun promote(nowMs: Long) {
        val w = waiting ?: return
        waiting = null
        if (w.cls == PromptClass.ARRIVAL || nowMs - w.triggerAtMs <= VoiceConstants.MAX_WAIT_MS) start(w, nowMs) else listener.onDropped(w)
    }

    fun tick(nowMs: Long) {
        val w = waiting
        if (w != null) {
            if (w.cls == PromptClass.ARRIVAL && nowMs - w.triggerAtMs >= VoiceConstants.MAX_WAIT_MS) {
                speaker.stop()
                current = null
                waiting = null
                start(w, nowMs)
            } else if (w.cls != PromptClass.ARRIVAL && nowMs - w.triggerAtMs > VoiceConstants.MAX_WAIT_MS) {
                waiting = null
                listener.onDropped(w)
            }
        }
        // Safety net: a speaker that never reports the end must not block the queue for good.
        val c = current
        if (c != null && nowMs - currentStartedAt > STUCK_MS) onDone(c.id, nowMs)
    }

    fun clear() {
        if (current != null) speaker.stop()
        current = null
        waiting?.let { listener.onDropped(it) }
        waiting = null
    }

    /** Ends playback for good (guidance ended). */
    fun close() {
        clear()
        closed = true
    }

    private fun start(p: SpokenPrompt, nowMs: Long) {
        current = p
        currentStartedAt = nowMs
        started += p
        listener.onStarted(p, nowMs)
        speaker.play(p)
    }

    companion object {
        const val STUCK_MS = 15_000L
    }
}

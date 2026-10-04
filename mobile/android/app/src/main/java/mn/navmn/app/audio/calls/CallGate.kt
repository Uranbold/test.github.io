package mn.navmn.app.audio.calls

/**
 * NAV-012 AC 35: a call is in progress while the audio mode is a call mode or the app's audio focus is lost
 * transiently. Mode values are the `AudioManager.MODE_*` constants (literal so the rule is JVM-testable):
 * RINGTONE 1, IN_CALL 2, IN_COMMUNICATION 3 (VoIP: Viber, WhatsApp, Messenger, Telegram), CALL_SCREENING 4 (API 30),
 * CALL_REDIRECT 5 and COMMUNICATION_REDIRECT 6 (API 33; call states by definition, ADR-0013 §6.1). Only NORMAL (0)
 * and unknown values (INVALID −2, CURRENT −1) are not a call. No phone-state permission is involved.
 */
object CallModes {
    const val MODE_NORMAL = 0
    private val CALL_MODES = setOf(1, 2, 3, 4, 5, 6)

    fun isCall(mode: Int): Boolean = mode in CALL_MODES
}

/**
 * ADR-0013 §6.2 call gate (pure; times from the engine's monotonic clock):
 *  - a call **starts** with the first call signal (the engine then stops a playing prompt at once, AC 36);
 *  - a call **ends** when the signal has been clear for [endDebounceMs] without interruption, so two calls back to
 *    back produce one end (AC 38);
 *  - during the call every voice trigger is skipped, not queued (AC 37). A skipped (or stopped) manoeuvre prompt for
 *    the current next manoeuvre is remembered as `(generation, step)`; client prompts (off-route, GPS, arrival) are
 *    never remembered;
 *  - at the end the engine asks [takeSkipped] and, if it still names the current next manoeuvre and the guidance
 *    conditions hold, plays exactly one catch-up prompt; a regular trigger for that manoeuvre within [suppressMs]
 *    after it is skipped ([isSuppressed]).
 * Banner, notification, off-route detection, reroute and GPS-loss handling never consult the gate.
 */
class CallGate(
    private val endDebounceMs: Long = END_DEBOUNCE_MS,
    private val suppressMs: Long = SUPPRESS_MS,
) {
    enum class Event { STARTED, ENDED }

    var inCall: Boolean = false
        private set
    private var raw = false
    private var clearSince: Long? = null
    private var skipped: Pair<Int, Int>? = null
    private var suppressed: Pair<Int, Int>? = null
    private var suppressedUntil = Long.MIN_VALUE

    /** The raw call signal changed (mode listener / poll, focus listener). */
    fun onSignal(callNow: Boolean, nowMs: Long): Event? {
        raw = callNow
        if (callNow) {
            clearSince = null
            if (!inCall) {
                inCall = true
                return Event.STARTED
            }
            return null
        }
        if (inCall && clearSince == null) clearSince = nowMs
        return tick(nowMs)
    }

    /** Engine ticker (500 ms): ends a call once the signal has been clear for the debounce time. */
    fun tick(nowMs: Long): Event? {
        val since = clearSince ?: return null
        if (inCall && !raw && nowMs - since >= endDebounceMs) {
            inCall = false
            clearSince = null
            return Event.ENDED
        }
        return null
    }

    /** A prompt for [maneuver] (the current next manoeuvre) was skipped or stopped because of the call. */
    fun recordSkipped(maneuver: Pair<Int, Int>) {
        skipped = maneuver
    }

    /** The remembered skip, cleared in every case (one catch-up at most). */
    fun takeSkipped(): Pair<Int, Int>? = skipped.also { skipped = null }

    /** The catch-up for [maneuver] was played at [nowMs]: suppress a regular trigger for it for [suppressMs]. */
    fun suppress(maneuver: Pair<Int, Int>, nowMs: Long) {
        suppressed = maneuver
        suppressedUntil = nowMs + suppressMs
    }

    fun isSuppressed(maneuver: Pair<Int, Int>?, nowMs: Long): Boolean =
        maneuver != null && maneuver == suppressed && nowMs < suppressedUntil

    companion object {
        /** Call end = the signal is clear for 1 s (ADR-0013 §6.2). */
        const val END_DEBOUNCE_MS = 1_000L

        /** AC 38: a regular trigger within 5 s after the catch-up is skipped. */
        const val SUPPRESS_MS = 5_000L
    }
}

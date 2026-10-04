package mn.navmn.app.demo.replay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mn.navmn.app.engine.Clock

/**
 * ADR-0016 §4.4: the engine clock of a replay. `elapsedMs` = the monotonic time since «Эхлэх» excluding paused time,
 * times [speed], on the same base as `SystemClock.elapsedRealtime()` at the start (so the NAV-005 age checks at
 * «Эхлэх» stay valid). While paused it stands still: no GPS-loss timer, no queue timeout, «Хүрэх цаг» frozen (§11).
 * Before the first [start] it is the real monotonic time. The wall clock is real (Ferrostar timestamps, «Хүрэх цаг»).
 *
 * [speed] is 1 by default (story Open question 4: 1× only in the UI); 2 and 4 are supported for tests and a later
 * speed choice (which would need a new glossary label through triage).
 */
class ReplayClock(
    private val realNow: () -> Long,
    private val wallNow: () -> Long = { System.currentTimeMillis() },
) : Clock {
    private val lock = Any()
    private var started = false
    private var originMs = 0L
    private var pausedTotalMs = 0L
    private var pausedSinceMs = 0L
    private val _paused = MutableStateFlow(false)

    /** True while the replay is paused («Түр зогсоох»). */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    @Volatile var speed: Int = 1
        private set

    /** A new replay («Эхлэх»): replay time = real time now, nothing paused. */
    fun start(speed: Int = 1) {
        require(speed in SPEEDS) { "unsupported replay speed $speed" }
        synchronized(lock) {
            originMs = realNow()
            pausedTotalMs = 0
            started = true
            this.speed = speed
            _paused.value = false
        }
    }

    /** «Түр зогсоох»: the replay time stops. No effect before [start] or when already paused. */
    fun pause(): Boolean = synchronized(lock) {
        if (!started || _paused.value) return false
        pausedSinceMs = realNow()
        _paused.value = true
        true
    }

    /** «Үргэлжлүүлэх»: the replay time continues from where it stopped. */
    fun resume(): Boolean = synchronized(lock) {
        if (!_paused.value) return false
        pausedTotalMs += realNow() - pausedSinceMs
        _paused.value = false
        true
    }

    override fun elapsedMs(): Long = synchronized(lock) {
        if (!started) return realNow()
        val now = if (_paused.value) pausedSinceMs else realNow()
        originMs + speed * (now - originMs - pausedTotalMs)
    }

    override fun wallMs(): Long = wallNow()

    companion object {
        val SPEEDS = setOf(1, 2, 4)
    }
}

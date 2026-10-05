package mn.navmn.app.demo.replay

import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mn.navmn.app.location.Fix
import mn.navmn.app.location.LocationSource

/** ADR-0016 §4.5: keeps the CPU awake while a screen-off replay runs (the demo build's partial wake lock). */
interface ReplayWakeLock {
    fun acquire(timeoutMs: Long)
    fun release()

    object None : ReplayWakeLock {
        override fun acquire(timeoutMs: Long) = Unit
        override fun release() = Unit
    }
}

/**
 * ADR-0016 §4 (DM-1): the simulated location of a replay build. It implements the same [LocationSource] the production
 * provider implements, so the guidance engine, Ferrostar and every ADR-0009 rule run unchanged; only the source of the
 * fixes differs. It never touches a platform location API (NAV-019 AC 13): there is no "my location" flow, and
 * [servicesEnabled] only reads the system setting (the Android 14+ FGS prerequisite, §5).
 *
 *  - [arm] (picker entry selected) loads the track; nothing runs yet.
 *  - [freshGoodFix] («Эхлэх») starts the [clock] and returns track point 0 (AC 12: the first simulated fix is the first
 *    track point). Idempotent until the next [arm], so a double tap starts one replay only.
 *  - [guidanceUpdates] emits point i ≥ 1 when the replay clock reaches its track time (± 200 ms at 1×, AC 12); while
 *    paused nothing is emitted and the wake lock is released (§11); a late wake-up delivers only the latest due point.
 *    [END_GRACE_MS] after the last point it calls [onTrackEnd] (AC 25, the «Дуусгах» path). Arrival cancels the
 *    collection (the engine stops location updates at arrival), so [onTrackEnd] is not called then.
 */
class ReplayLocationSource(
    private val clock: ReplayClock,
    private val servicesEnabled: () -> Boolean,
    private val wakeLock: ReplayWakeLock = ReplayWakeLock.None,
    private val wallNow: () -> Long = { System.currentTimeMillis() },
) : LocationSource {
    /** Called when the track ends without arrival (set by the demo variant: ends guidance). */
    @Volatile var onTrackEnd: () -> Unit = {}

    /** Replay speed for the next «Эхлэх» (1×; 2× and 4× are supported, story Open question 4). */
    @Volatile var speed: Int = 1

    @Volatile private var track: ReplayTrack? = null
    @Volatile private var startFix: Fix? = null
    @Volatile private var anchorMs = 0L

    /** Points delivered and points skipped by late wake-ups in the current replay (tests, debug log; no coordinates). */
    @Volatile var delivered = 0
        private set
    @Volatile var skipped = 0
        private set

    val armed: Boolean get() = track != null

    /** A picker entry was selected: the next «Эхлэх» replays [t]. */
    fun arm(t: ReplayTrack) {
        track = t
        startFix = null
    }

    override fun servicesEnabled(): Boolean = servicesEnabled.invoke()

    override suspend fun freshGoodFix(timeoutMs: Long): Fix? {
        val t = track ?: return null
        startFix?.let { return it }
        clock.start(speed)
        anchorMs = clock.elapsedMs()
        delivered = 1
        skipped = 0
        return t.fix(0, anchorMs, wallNow()).also { startFix = it }
    }

    /** No device position is ever shown (AC 13). */
    override fun mapUpdates(): Flow<Fix> = emptyFlow()

    override fun guidanceUpdates(): Flow<Fix> {
        val t = track ?: return emptyFlow()
        if (startFix == null) return emptyFlow()
        val anchor = anchorMs
        val schedule = ReplaySchedule(t.offsets)
        return channelFlow {
            val lockJob = launch {
                clock.paused.collect { p ->
                    if (p) {
                        wakeLock.release()
                    } else {
                        val since = clock.elapsedMs() - anchor
                        wakeLock.acquire(schedule.realWaitMs(since, t.durationMs + END_GRACE_MS, clock.speed) + WAKE_MARGIN_MS)
                    }
                }
            }
            try {
                var next = 1
                while (next < t.size) {
                    awaitReplayTime(anchor, schedule.offset(next), schedule)
                    val due = maxOf(schedule.latestDue(clock.elapsedMs() - anchor), next)
                    skipped += due - next
                    delivered++
                    send(t.fix(due, anchor, wallNow()))
                    next = due + 1
                }
                awaitReplayTime(anchor, t.durationMs + END_GRACE_MS, schedule)
                lockJob.cancel()
                wakeLock.release()
                onTrackEnd()
            } finally {
                lockJob.cancel()
                wakeLock.release()
            }
        }
    }

    /** Suspends until the replay time since «Эхлэх» reaches [targetMs]; waits out pauses (frozen clock). */
    private suspend fun ProducerScope<Fix>.awaitReplayTime(anchor: Long, targetMs: Long, schedule: ReplaySchedule) {
        while (true) {
            if (clock.paused.value) {
                clock.paused.first { !it }
                continue
            }
            val wait = schedule.realWaitMs(clock.elapsedMs() - anchor, targetMs, clock.speed)
            if (wait <= 0) return
            // Wakes early when a pause starts, so emission stops at once (§11).
            withTimeoutOrNull(wait) { clock.paused.first { it } }
        }
    }

    companion object {
        /** AC 25: the track ended without arrival → the «Дуусгах» path after this much replay time. */
        const val END_GRACE_MS = 2_000L

        /** ADR-0016 §4.5: wake-lock timeout = remaining track time + this margin. */
        const val WAKE_MARGIN_MS = 60_000L
    }
}

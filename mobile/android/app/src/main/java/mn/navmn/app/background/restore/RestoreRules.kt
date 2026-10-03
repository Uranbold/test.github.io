package mn.navmn.app.background.restore

/**
 * ADR-0013 §3.5: the pure restore decision, checked in this order (AC 18, 21, 23, 24). The window and the loop limit
 * are BA defaults (story Open question 5); change them only through the BA.
 */
object RestoreRules {
    /** Restore window: 30 min after the last heartbeat (story "Terms"). */
    const val WINDOW_MS = 30 * 60_000L

    /** A heartbeat this far in the future (clock changed) counts as expired (ADR-0013 §3.2). */
    const val FUTURE_TOLERANCE_MS = 5 * 60_000L

    /** AC 24: at most [MAX_RESTORES] restores within [LOOP_WINDOW_MS]; a third interruption drops the record. */
    const val MAX_RESTORES = 2
    const val LOOP_WINDOW_MS = 10 * 60_000L

    /** ADR-0013 §3.2: heartbeat every 30 s of guidance (AC 16 needs ≤ 60 s). */
    const val HEARTBEAT_MS = 30_000L

    sealed interface Decision {
        /** No record, or a session is alive in this process. */
        data object None : Decision

        data class DeleteSilently(val reason: Reason) : Decision

        /** Location permission missing or location services off: NAV-005 AC 8–12 flow, record kept (AC 23). */
        data class NeedLocation(val meta: RestoreMeta) : Decision

        data class Restore(val meta: RestoreMeta) : Decision
    }

    enum class Reason { UNREADABLE, EXPIRED, CLOCK_IN_FUTURE, LOOP_LIMIT }

    fun decide(stored: StoredRecord, nowWallMs: Long, sessionAlive: Boolean, locationOk: Boolean): Decision {
        if (sessionAlive) return Decision.None
        val meta = when (stored) {
            StoredRecord.Absent -> return Decision.None
            StoredRecord.Unreadable -> return Decision.DeleteSilently(Reason.UNREADABLE)
            is StoredRecord.Present -> stored.meta
        }
        val age = nowWallMs - meta.heartbeatWallMs
        if (age < -FUTURE_TOLERANCE_MS) return Decision.DeleteSilently(Reason.CLOCK_IN_FUTURE)
        if (age > WINDOW_MS) return Decision.DeleteSilently(Reason.EXPIRED)
        val recent = meta.restoresWallMs.count { nowWallMs - it in 0..LOOP_WINDOW_MS }
        if (recent >= MAX_RESTORES) return Decision.DeleteSilently(Reason.LOOP_LIMIT)
        if (!locationOk) return Decision.NeedLocation(meta)
        return Decision.Restore(meta)
    }

    /** When the AC 22 notification disappears by itself (`setTimeoutAfter`). */
    fun windowEnd(meta: RestoreMeta): Long = meta.heartbeatWallMs + WINDOW_MS

    /** The record after a restore at [nowWallMs]: restore time appended (only the loop window is kept), heartbeat now. */
    fun afterRestore(meta: RestoreMeta, nowWallMs: Long): RestoreMeta = meta.copy(
        heartbeatWallMs = nowWallMs,
        restoresWallMs = (meta.restoresWallMs.filter { nowWallMs - it in 0..LOOP_WINDOW_MS } + nowWallMs),
    )
}

/**
 * ADR-0013 §2 null-intent restart table, a pure function of the API level, the decision and whether notifications
 * may be posted. API 26–29: a location foreground service started from the background may run, so guidance resumes
 * silently. API 30+: never try a location foreground service from a background restart (no while-in-use access
 * without `ACCESS_BACKGROUND_LOCATION`, which the app never requests; `ForegroundServiceStartNotAllowedException` on
 * 31+, `SecurityException` on 34+); post the «Замчлал тасарлаа» notification instead.
 */
object RestartPlan {
    enum class Action {
        /** Nothing to restore: stop. */
        STOP,

        /** Expired, loop limit or unreadable: delete the record silently and stop. */
        DELETE_AND_STOP,

        /** API 26–29 with location access: resume as AC 19 without opening the app. */
        RESUME_SILENTLY,

        /** Post the AC 22 notification («Замчлал тасарлаа»), 0 location requests, stop. */
        POST_INTERRUPTED,

        /** Notifications denied (AC 7): keep the record, stop; the restore happens when the app is opened. */
        KEEP_AND_STOP,
    }

    const val LAST_SILENT_SDK = 29

    fun action(sdkInt: Int, decision: RestoreRules.Decision, notificationsAllowed: Boolean): Action = when (decision) {
        RestoreRules.Decision.None -> Action.STOP
        is RestoreRules.Decision.DeleteSilently -> Action.DELETE_AND_STOP
        is RestoreRules.Decision.Restore -> when {
            sdkInt <= LAST_SILENT_SDK -> Action.RESUME_SILENTLY
            notificationsAllowed -> Action.POST_INTERRUPTED
            else -> Action.KEEP_AND_STOP
        }
        is RestoreRules.Decision.NeedLocation -> if (notificationsAllowed) Action.POST_INTERRUPTED else Action.KEEP_AND_STOP
    }
}

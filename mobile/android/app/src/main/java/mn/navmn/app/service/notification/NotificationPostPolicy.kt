package mn.navmn.app.service.notification

/**
 * ADR-0013 §4 / navigation-ux §12.1 posting rules (pure, monotonic times from the caller):
 *  - a state change (new manoeuvre, reroute start, new route, GPS lost or restored, arrival, voice toggle) is posted
 *    within 1 s: at once if the last post is ≥ 1 s old, otherwise on the next tick when it is;
 *  - while only the distance changes, a post at least every 2 s (and not more often);
 *  - never more than one post per second;
 *  - Android 14+ dismissal: after the user swiped the notification away nothing is posted until the next
 *    **instruction change** ([RichNotification.instructionKey]); that change is posted once and clears the flag.
 *    Distance-only and voice-label updates never re-post a dismissed notification (AC 5).
 * The service calls [decide] for every new content and on a ticker, and posts when it returns true.
 */
class NotificationPostPolicy(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val distanceRefreshMs: Long = DISTANCE_REFRESH_MS,
) {
    private var last: RichNotification? = null
    private var lastAt = Long.MIN_VALUE / 2

    /** The instruction the user dismissed (null = not dismissed). */
    private var dismissedAt: String? = null

    val dismissed: Boolean get() = dismissedAt != null

    fun decide(content: RichNotification, nowMs: Long): Boolean {
        val prev = last
        dismissedAt?.let { gone ->
            if (content.instructionKey == gone) return false
        }
        if (content == prev && dismissedAt == null) return false
        if (nowMs - lastAt < minIntervalMs) return false
        val distanceOnly = prev != null && dismissedAt == null && prev.instructionKey == content.instructionKey &&
            prev.copy(distanceText = content.distanceText, title = content.title) == content
        if (distanceOnly && nowMs - lastAt < distanceRefreshMs) return false
        last = content
        lastAt = nowMs
        dismissedAt = null
        return true
    }

    /** The notification's delete intent fired (Android 14+ swipe). */
    fun onDismissed() {
        dismissedAt = last?.instructionKey ?: ""
    }

    /** Language switch (AC 4): the next content is posted even if equal (new texts and channel name). */
    fun invalidate() {
        last = null
    }

    companion object {
        const val MIN_INTERVAL_MS = 1_000L
        const val DISTANCE_REFRESH_MS = 2_000L
    }
}

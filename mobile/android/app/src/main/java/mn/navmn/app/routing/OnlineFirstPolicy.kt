package mn.navmn.app.routing

import mn.navmn.app.route.Cancelable

/**
 * NAV-021 R7 (ADR-0017 §5 "Fallback rule", D163, D199): which source answers a request when a routing file is
 * installed. Pure: every time comes from the caller (fake-clock tested). Shared later by NAV-023 (search, reverse).
 *
 * - No validated network → on-device at once, 0 requests (AC 8).
 * - After a fallback caused by the 3.0 s header budget or a connection failure → on-device directly for 60 s, or until
 *   the network callback reports a **newly** validated network (AC 11).
 * - After a 429 → on-device during `Retry-After` (5 s if missing or unreadable), then online first again (AC 12). A
 *   newly validated network does not end this wait (the server asked).
 * - Otherwise online first with a 3.0 s budget to response headers (AC 9).
 */
class OnlineFirstPolicy(
    val headerBudgetMs: Long = HEADER_BUDGET_MS,
    private val stickyMs: Long = STICKY_MS,
) {
    enum class Source { ONLINE_FIRST, ON_DEVICE }

    private var stickyUntil = Long.MIN_VALUE
    private var rateLimitUntil = Long.MIN_VALUE

    @Synchronized
    fun decide(now: Long, validated: Boolean): Source = when {
        !validated -> Source.ON_DEVICE
        now < stickyUntil -> Source.ON_DEVICE
        now < rateLimitUntil -> Source.ON_DEVICE
        else -> Source.ONLINE_FIRST
    }

    /** The online try timed out before headers or its connection failed: stick to the device for 60 s (AC 11). */
    @Synchronized
    fun onTimeoutOrOffline(now: Long) {
        stickyUntil = now + stickyMs
    }

    /** 429 with `Retry-After` [retryAfterS] (already defaulted to 5 s by [mn.navmn.app.route.RouteClassifier.retryAfter]). */
    @Synchronized
    fun onRateLimited(now: Long, retryAfterS: Int) {
        rateLimitUntil = maxOf(rateLimitUntil, now + retryAfterS * 1000L)
    }

    /** The network callback reported a newly validated network: online first again at once (AC 11). */
    @Synchronized
    fun onNewlyValidated() {
        stickyUntil = Long.MIN_VALUE
    }

    companion object {
        /** D199: the on-device fallback starts ≤ 3.0 s after the online request was sent. */
        const val HEADER_BUDGET_MS = 3_000L
        const val STICKY_MS = 60_000L
    }
}

/** Time and timers for the fallback (elapsed real time on the device; a virtual clock in tests). */
interface RoutingClock {
    fun now(): Long

    /** Runs [block] once after [delayMs] on some thread unless cancelled. */
    fun schedule(delayMs: Long, block: () -> Unit): Cancelable
}

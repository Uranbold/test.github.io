package mn.navmn.app.reroute

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.route.RouteOutcome

/** Secondary banner line of the recalculating variant (navigation-ux §5). */
enum class RerouteSecondary { UNAVAILABLE, NO_ROUTE, ERROR, OFFLINE }

/**
 * ADR-0009 §4 request rules P1–P10 for reroutes. Pure: every time comes from the caller (fake-clock tested, AC 44,
 * 47–50). The engine asks [mayStart] on every fix and ticker step and reports [onStarted] / [onOutcome].
 */
class ReroutePolicy(
    private val minGapMs: Long = 5_000,
    private val windowMs: Long = 60_000,
    private val maxPerWindow: Int = 6,
    private val backoffMs: List<Long> = listOf(5_000, 10_000, 20_000, 30_000),
    private val failedMoveM: Double = 200.0,
    private val failedWaitMs: Long = 30_000,
) {
    data class Context(
        val now: Long,
        val inEpisode: Boolean,
        val online: Boolean,
        /** Not lost and a good fix in the last 10 s (P9). */
        val gpsOk: Boolean,
        /** Guidance ended or arrived (P10). */
        val finished: Boolean,
        val position: LatLon?,
    )

    var inFlight: Boolean = false
        private set
    private val starts = ArrayDeque<Long>()
    private var backoffUntil = 0L
    private var backoffIndex = 0
    private var rateLimitUntil = 0L
    private var failedOrigin: LatLon? = null
    private var failedAt = 0L
    private var pendingOrigin: LatLon? = null

    /** Secondary line from the last outcome; cleared when a new request starts. */
    var secondary: RerouteSecondary? = null
        private set

    /** Starts in the closed window [now − 60 s, now] (P3). */
    fun startsInWindow(now: Long): Int = starts.count { now - it <= windowMs }

    fun mayStart(ctx: Context): Boolean {
        if (ctx.finished || !ctx.inEpisode || inFlight || !ctx.gpsOk || ctx.position == null) return false
        if (!ctx.online) {
            secondary = RerouteSecondary.OFFLINE // P8: 0 requests, the banner says why (AC 50)
            return false
        }
        if (secondary == RerouteSecondary.OFFLINE) secondary = null
        val now = ctx.now
        if (now < rateLimitUntil || now < backoffUntil) return false // P5, P6
        starts.lastOrNull()?.let { if (now - it < minGapMs) return false } // P2
        while (starts.isNotEmpty() && now - starts.first() > windowMs) starts.removeFirst()
        if (startsInWindow(now) >= maxPerWindow) return false // P3
        val origin = failedOrigin
        if (origin != null && (now - failedAt < failedWaitMs || Geo.distance(origin, ctx.position) < failedMoveM)) return false // P7
        return true
    }

    fun onStarted(now: Long, origin: LatLon) {
        inFlight = true
        starts.addLast(now)
        pendingOrigin = origin
        secondary = null
    }

    fun onOutcome(now: Long, outcome: RouteOutcome) {
        inFlight = false
        when (outcome) {
            is RouteOutcome.Ok -> {
                backoffIndex = 0
                backoffUntil = 0
                failedOrigin = null
                secondary = null
            }
            RouteOutcome.Unavailable -> {
                backoffUntil = now + backoffMs[minOf(backoffIndex, backoffMs.size - 1)]
                backoffIndex++
                secondary = RerouteSecondary.UNAVAILABLE
            }
            is RouteOutcome.RateLimited -> {
                rateLimitUntil = now + outcome.retryAfterS * 1000L
                secondary = null // AC 47: no secondary line during the wait
            }
            RouteOutcome.NoRoute, RouteOutcome.OutOfCoverage, RouteOutcome.TooFar -> {
                failedOrigin = pendingOrigin
                failedAt = now
                secondary = RerouteSecondary.NO_ROUTE
            }
            RouteOutcome.BadRequest, RouteOutcome.BadResponse -> {
                failedOrigin = pendingOrigin
                failedAt = now
                secondary = RerouteSecondary.ERROR
            }
            RouteOutcome.Offline -> secondary = RerouteSecondary.OFFLINE
            RouteOutcome.Cancelled -> Unit
        }
        pendingOrigin = null
    }

    /** P5: the back-off index resets at episode end; P7 is per episode. A 429 wait stays (the server asked). */
    fun onEpisodeEnd() {
        backoffIndex = 0
        backoffUntil = 0
        failedOrigin = null
        secondary = null
    }

    /** P8: a validated network returned during an episode: one request as soon as P1–P3 (and a 429 wait) allow. */
    fun onNetworkRestored() {
        backoffUntil = 0
        failedOrigin = null
        if (secondary == RerouteSecondary.OFFLINE) secondary = null
    }

    /** Earliest time a start could be allowed by the timing rules alone (for precise wake-ups); null if unknown. */
    fun nextTimedStart(now: Long): Long {
        var t = maxOf(now, rateLimitUntil, backoffUntil)
        starts.lastOrNull()?.let { t = maxOf(t, it + minGapMs) }
        if (startsInWindow(now) >= maxPerWindow) {
            val firstInWindow = starts.firstOrNull { now - it <= windowMs }
            if (firstInWindow != null) t = maxOf(t, firstInWindow + windowMs + 1)
        }
        if (failedOrigin != null) t = maxOf(t, failedAt + failedWaitMs)
        return t
    }
}

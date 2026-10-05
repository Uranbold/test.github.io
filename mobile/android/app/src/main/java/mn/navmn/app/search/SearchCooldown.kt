package mn.navmn.app.search

/**
 * NAV-018 (ADR-0015 §5): the `search` 429 cooldown is per operation for the device (ADR-0006 §3, openapi `search`), not
 * per [SearchController] instance. The map-screen search and the route-preview point fields share one holder, so a
 * `Retry-After` received by either blocks both until it ends. Times are on the controllers' monotonic clock.
 */
class SearchCooldown {
    @Volatile var until: Long = 0L
        private set

    fun active(now: Long): Boolean = now < until

    /** Starts (or extends) the wait to [untilMs]; a shorter later value never shortens a running wait. */
    fun start(untilMs: Long) {
        if (untilMs > until) until = untilMs
    }
}

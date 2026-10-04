package mn.navmn.app.routing

/**
 * NAV-021 AC 24 / R9 (ADR-0017 §2 "Crash handling"): after **3** deaths of the `:routing` process within **10 minutes**
 * on-device routing is disabled until the next app start (a new instance) or the next routing-file install (a new
 * [InstalledRouting.key]). Pure; times are elapsed milliseconds from the caller. Keeps only timestamps (no coordinates,
 * nothing about the requests).
 */
class RoutingHealth(
    private val maxDeaths: Int = MAX_DEATHS,
    private val windowMs: Long = WINDOW_MS,
) {
    private val deaths = ArrayDeque<Long>()
    private var disabledFor: String? = null

    /** Total deaths seen by this app process (the local crash counter, AC 24). */
    var deathCount: Int = 0
        private set

    @Synchronized
    fun onDeath(now: Long, routingKey: String?) {
        deathCount++
        deaths.addLast(now)
        while (deaths.isNotEmpty() && now - deaths.first() > windowMs) deaths.removeFirst()
        if (deaths.size >= maxDeaths) disabledFor = routingKey ?: ANY
    }

    /** False after 3 deaths in 10 min, until a different routing file is installed. */
    @Synchronized
    fun enabled(routingKey: String?): Boolean {
        val d = disabledFor ?: return true
        if (d == ANY || d == routingKey) return false
        // A new routing-file install re-enables on-device routing with a fresh window.
        disabledFor = null
        deaths.clear()
        return true
    }

    companion object {
        const val MAX_DEATHS = 3
        const val WINDOW_MS = 10 * 60_000L
        private const val ANY = "*"
    }
}

package mn.navmn.app.location

import mn.navmn.app.geo.LatLon

/**
 * One platform location update (story "Terms": fix). Times: [elapsedMs] is the monotonic fix time
 * (Location.elapsedRealtimeNanos / 1e6) used for every age and timing rule (ADR-0009 §5, never wall clock);
 * [wallTimeMs] is Location.time, only passed on to Ferrostar's timestamp.
 */
data class Fix(
    val lat: Double,
    val lon: Double,
    /** Horizontal accuracy in metres; NaN when the provider reports none (treated as poor). */
    val accuracyM: Double,
    val bearingDeg: Double? = null,
    val bearingAccuracyDeg: Double? = null,
    val speedMps: Double? = null,
    val elapsedMs: Long,
    val wallTimeMs: Long,
) {
    val latLon: LatLon get() = LatLon(lat, lon)

    companion object {
        /** Story "Terms": good fix ≤ 25 m accuracy. */
        const val GOOD_ACCURACY_M = 25.0

        /** Story "Terms": fresh fix ≤ 10 s old (guidance). */
        const val FRESH_MS = 10_000L

        /** Route-preview origin: fresh = ≤ 60 s (as NAV-004). */
        const val PREVIEW_FRESH_MS = 60_000L
    }

    fun isGood(nowElapsedMs: Long, maxAgeMs: Long = FRESH_MS): Boolean =
        accuracyM.isFinite() && accuracyM <= GOOD_ACCURACY_M && nowElapsedMs - elapsedMs <= maxAgeMs
}

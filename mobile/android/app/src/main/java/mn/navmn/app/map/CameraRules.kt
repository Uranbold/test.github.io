package mn.navmn.app.map

/**
 * navigation-ux §8 camera rules (pure). Distances in px of the map view.
 */
object CameraRules {
    const val PITCH_HEADING_UP = 45.0
    const val PITCH_NORTH_UP = 0.0
    const val RECENTER_TIMEOUT_MS = 15_000L
    const val FOLLOW_MS = 1_000
    const val PUCK_FRACTION = 0.70
    private const val HYSTERESIS_KMH = 5.0

    /** Zoom by speed: car < 20 km/h 17.5, 20–50 17, 50–80 16, ≥ 80 15; walk 17.5; hysteresis 5 km/h. */
    fun zoom(speedKmh: Double, walk: Boolean, previous: Double?): Double {
        if (walk) return 17.5
        val bands = listOf(0.0 to 17.5, 20.0 to 17.0, 50.0 to 16.0, 80.0 to 15.0)
        fun band(v: Double) = bands.indexOfLast { v >= it.first }.coerceAtLeast(0)
        val target = band(speedKmh)
        val prevBand = bands.indexOfFirst { it.second == previous }
        if (prevBand < 0 || target == prevBand) return bands[target].second
        // Only leave the current band when the speed is clearly (≥ 2.5 km/h) past its edge: 5 km/h dead band.
        val half = HYSTERESIS_KMH / 2
        return if (target > prevBand) {
            if (speedKmh >= bands[prevBand + 1].first + half) bands[target].second else bands[prevBand].second
        } else {
            if (speedKmh < bands[prevBand].first - half) bands[target].second else bands[prevBand].second
        }
    }

    data class Padding(val left: Double, val top: Double, val right: Double, val bottom: Double)

    /**
     * Camera padding so that the target (the puck) sits at 70 % of the uncovered map height in heading-up, centred in
     * north-up (Layout rule 2). [coveredTop]/[coveredBottom]/[coveredLeft] include the 16 dp margins.
     */
    fun padding(viewHeight: Double, coveredTop: Double, coveredBottom: Double, coveredLeft: Double, coveredRight: Double, headingUp: Boolean): Padding {
        val t = coveredTop.coerceIn(0.0, viewHeight)
        val b = (viewHeight - coveredBottom).coerceIn(t, viewHeight)
        return if (headingUp) {
            Padding(coveredLeft, 0.6 * t + 0.4 * b, coveredRight, viewHeight - b)
        } else {
            Padding(coveredLeft, t, coveredRight, viewHeight - b)
        }
    }
}

package mn.navmn.app.map

/**
 * NAV-011 camera rule C1 (screen spec › Layout rules; AC 7a, D142; ADR-0012 Amendment A4), pure. Selecting the
 * typed-coordinate option moves the camera once: the point at zoom max(current, 16), inside the map area left free by
 * the top group and the coordinate card, with room for the 40 dp pin above the point. Distances are px relative to
 * the map view's top-left corner. A long-press never uses this rule (the camera stays still, AC 9).
 */
object CoordinateCamera {
    const val MIN_ZOOM = 16.0

    /** `motion.route-camera`, the NAV-018 Q8 "focus a point" move; 0 (a jump) with animations off. */
    const val EASE_MS = 700
    const val MARGIN_DP = 16
    const val PIN_DP = 40

    /** ADR-0006 §5 fallback when even the unpadded free area leaves no room. */
    const val FALLBACK_DP = 40

    fun zoom(current: Double): Double = maxOf(MIN_ZOOM, current)

    data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** The card's box in map px. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Camera padding (px) for C1.
     *  - Narrow windows: full map width, from [topGroupBottom] to the card's top.
     *  - Wide windows ([wide], P8: the card is a start-edge column): from the card's end edge to the control lane's start
     *    ([laneStart], null when there is no lane), from [topGroupBottom] to the bottom of the map (R5 is below it).
     * Inside that area: top margin + pin, 16 dp on the other sides. If that leaves no room, the margins are dropped;
     * if there is still no room, [FALLBACK_DP] on every side.
     */
    fun padding(
        mapWidth: Int,
        mapHeight: Int,
        topGroupBottom: Int,
        card: Box?,
        laneStart: Int?,
        wide: Boolean,
        density: Float,
    ): Padding {
        fun dp(v: Int) = Math.round(v * density)
        val margin = dp(MARGIN_DP)
        val pin = dp(PIN_DP)
        val top0 = topGroupBottom.coerceIn(0, mapHeight)
        val left0: Int
        val right0: Int
        val bottom0: Int
        if (wide && card != null) {
            left0 = card.right.coerceIn(0, mapWidth)
            right0 = if (laneStart != null) (mapWidth - laneStart).coerceIn(0, mapWidth) else 0
            bottom0 = 0
        } else {
            left0 = 0
            right0 = 0
            bottom0 = if (card != null) (mapHeight - card.top).coerceIn(0, mapHeight) else 0
        }
        fun fits(p: Padding) = mapWidth - p.left - p.right > 0 && mapHeight - p.top - p.bottom > 0
        val full = Padding(left0 + margin, top0 + margin + pin, right0 + margin, bottom0 + margin)
        if (fits(full)) return full
        val bare = Padding(left0, top0 + pin, right0, bottom0)
        if (fits(bare)) return bare
        val f = dp(FALLBACK_DP)
        return Padding(f, f, f, f)
    }
}

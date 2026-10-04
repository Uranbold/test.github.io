package mn.navmn.app.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import mn.navmn.app.geo.LatLon
import mn.navmn.app.ui.theme.TokenColours

/** The camera and style operations the screen uses on the map (implemented by [NavMapController]). */
interface MapCamera {
    fun setStyle(night: Boolean, colours: TokenColours, pmtilesUrl: String)
    fun moveTo(p: LatLon, zoom: Double)
    fun easeTo(p: LatLon, zoom: Double? = null, durationMs: Int = 300)
    fun fit(points: List<LatLon>, left: Int, top: Int, right: Int, bottom: Int)
    fun follow(target: LatLon, bearing: Double, tilt: Double, zoom: Double, padding: CameraRules.Padding, animate: Boolean)
    fun resetNorth()
    fun zoomBy(delta: Double)
    val bearing: Double
    val center: LatLon?
    val zoom: Double
    /** Height of the map view in px (camera padding, navigation-ux §8). */
    val heightPx: Int
    /** Width of the map view in px (NAV-011 camera fit: padding that leaves no room falls back to 40 dp). */
    val widthPx: Int get() = 0
}

/**
 * The map view behind an injectable seam (ADR-0009 §10, Amendment 1): MapLibre Native in the app
 * ([mn.navmn.app.ui.components.MapLibreSurface]); a recording surface in Robolectric tests, where MapLibre's native
 * library cannot load, so that MainActivity can be launched for the permission flow (AC 8–13) and configuration
 * changes (AC 64).
 */
interface MapSurface {
    @Composable
    fun Map(
        modifier: Modifier,
        description: String,
        night: Boolean,
        colours: TokenColours,
        pmtilesUrl: String,
        content: MapContent,
        onReady: (MapCamera) -> Unit,
        onLongPress: ((LatLon) -> Unit)?,
        onGesture: () -> Unit,
        onCameraIdle: (center: LatLon, bearing: Double, zoom: Double) -> Unit,
        onFailed: () -> Unit,
        /**
         * NAV-011 AC 17: a map tap (not a pan's end, not a long-press) with the Valhalla indices of the preview routes
         * inside the 48 × 48 dp box around it (map-style §7.6). null = taps are not needed.
         */
        onTap: ((LatLon, List<Int>) -> Unit)? = null,
    )
}

package mn.navmn.app.map

import android.content.Context
import mn.navmn.app.geo.LatLon
import mn.navmn.app.ui.theme.TokenColours
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** What the map shows; set by the UI, re-applied after every style load (day/night switch, AC 59). */
data class MapContent(
    val route: List<LatLon> = emptyList(),
    /** Guidance widths (map-style §7.4) vs preview widths (§7.2). */
    val guidance: Boolean = false,
    /** Off-route episode: the old route drawn dimmed (§7.4). */
    val routeDimmed: Boolean = false,
    val destination: LatLon? = null,
    val candidate: LatLon? = null,
    val puck: LatLon? = null,
    val puckBearing: Double = 0.0,
    val puckStale: Boolean = false,
    /** S1 location dot (§7), not during guidance. */
    val myLocation: LatLon? = null,
    val myLocationStale: Boolean = false,
    /**
     * NAV-011 preview (map-style §7.6): position of [route] (the selected line) in the parsed routes list
     * (`PreviewResult.Route.routes`), and the unselected routes as (position in that list, geometry). Not the Valhalla
     * response index: the parser may drop routes, so the two can differ.
     */
    val routeIndex: Int = 0,
    val alternatives: List<Pair<Int, List<LatLon>>> = emptyList(),
    /** NAV-018 map-style §7.7: the start marker for a chosen start (null with a device start: the location dot). */
    val origin: LatLon? = null,
    /** NAV-018 map-style §7.7: the manoeuvre point after a turn-list row tap (`nav-route-step`). */
    val step: LatLon? = null,
)

/**
 * Imperative MapLibre Native wrapper (ADR-0009 §6: MapLibre used directly through an AndroidView). Owns our sources,
 * layers and images (map-style §7.1–§7.4). MapLibre's attribution and logo are off; the app draws its own strip.
 */
class NavMapController(private val context: Context, val mapView: MapView) : MapCamera {
    var map: MapLibreMap? = null
        private set
    private var style: Style? = null
    private var night = false
    private var colours: TokenColours? = null
    private var content = MapContent()
    private var loadedKey: String? = null

    fun attach(map: MapLibreMap) {
        this.map = map
        map.uiSettings.apply {
            isAttributionEnabled = false
            isLogoEnabled = false
            isCompassEnabled = false
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
        }
        map.setMinZoomPreference(2.0)
        map.setMaxZoomPreference(19.0)
    }

    /** Loads the day or night style (no-op if already loaded) and re-adds our layers with the current data. */
    override fun setStyle(night: Boolean, colours: TokenColours, pmtilesUrl: String) {
        val m = map ?: return
        val key = "$night|$pmtilesUrl"
        this.colours = colours
        if (key == loadedKey) return
        loadedKey = key
        this.night = night
        style = null
        m.setStyle(Style.Builder().fromJson(MapStyle.load(context, night, pmtilesUrl))) { s ->
            style = s
            addOverlay(s)
            apply(content)
        }
    }

    fun apply(c: MapContent) {
        content = c
        val s = style ?: return
        val routeFc = if (c.route.size >= 2) {
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(c.route.map { Point.fromLngLat(it.lon, it.lat) })))
        } else {
            FeatureCollection.fromFeatures(emptyList())
        }
        routeFc.features()?.firstOrNull()?.addNumberProperty(PROP_INDEX, c.routeIndex)
        (s.getSourceAs<GeoJsonSource>(SRC_ROUTE))?.setGeoJson(routeFc)
        // NAV-011 §7.6: unselected preview routes below the selected line; selecting only rewrites the sources.
        val alts = if (c.guidance) emptyList() else c.alternatives.filter { it.second.size >= 2 }
        s.getSourceAs<GeoJsonSource>(SRC_ALT)?.setGeoJson(
            FeatureCollection.fromFeatures(
                alts.map { (i, line) ->
                    Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it.lon, it.lat) })).apply { addNumberProperty(PROP_INDEX, i) }
                },
            ),
        )
        val showSel = !c.routeDimmed
        visible(s, L_SEL_CASING, showSel && !c.guidance)
        visible(s, L_SEL, showSel && !c.guidance)
        visible(s, L_GUIDE_CASING, showSel && c.guidance)
        visible(s, L_GUIDE, showSel && c.guidance)
        visible(s, L_OLD_CASING, c.routeDimmed)
        visible(s, L_OLD, c.routeDimmed)
        s.getSourceAs<GeoJsonSource>(SRC_PIN)?.setGeoJson(points(listOfNotNull(c.destination)))
        s.getSourceAs<GeoJsonSource>(SRC_CANDIDATE)?.setGeoJson(points(listOfNotNull(c.candidate)))
        s.getSourceAs<GeoJsonSource>(SRC_DOT)?.setGeoJson(points(listOfNotNull(c.myLocation)))
        // NAV-018 §7.7: chosen-start marker and manoeuvre point (preview only).
        s.getSourceAs<GeoJsonSource>(SRC_ORIGIN)?.setGeoJson(points(listOfNotNull(c.origin.takeIf { !c.guidance })))
        s.getSourceAs<GeoJsonSource>(SRC_STEP)?.setGeoJson(points(listOfNotNull(c.step.takeIf { !c.guidance })))
        s.getLayer(L_DOT)?.setProperties(
            PropertyFactory.circleColor(argb(if (c.myLocationStale) colours?.locationStaleDot else colours?.locationDot)),
        )
        val puck = c.puck
        s.getSourceAs<GeoJsonSource>(SRC_PUCK)?.setGeoJson(
            if (puck == null) {
                FeatureCollection.fromFeatures(emptyList())
            } else {
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(Point.fromLngLat(puck.lon, puck.lat)).apply { addNumberProperty("bearing", c.puckBearing) },
                )
            },
        )
        s.getLayer(L_PUCK)?.setProperties(PropertyFactory.iconImage(if (c.puckStale) IMG_PUCK_STALE else IMG_PUCK))
    }

    private fun points(list: List<LatLon>) = FeatureCollection.fromFeatures(list.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })

    private fun visible(s: Style, id: String, on: Boolean) {
        s.getLayer(id)?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
    }

    private fun argb(v: Long?): Int = (v ?: 0xFF000000).toInt()

    private fun widths(vararg zw: Pair<Float, Float>): Expression =
        Expression.interpolate(Expression.linear(), Expression.zoom(), *zw.map { Expression.stop(it.first, it.second) }.toTypedArray())

    private fun line(id: String, colour: Long, width: Expression): LineLayer = LineLayer(id, SRC_ROUTE).withProperties(
        PropertyFactory.lineColor(argb(colour)),
        PropertyFactory.lineWidth(width),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.visibility(Property.NONE),
    )

    private fun altLine(id: String, colour: Long, width: Expression): LineLayer = LineLayer(id, SRC_ALT).withProperties(
        PropertyFactory.lineColor(argb(colour)),
        PropertyFactory.lineWidth(width),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )

    /**
     * NAV-011 AC 17 / map-style §7.6: the [PROP_INDEX] values (positions in the parsed routes list, not Valhalla
     * indices) of the routes drawn inside a [boxPx] × [boxPx] box centred on
     * the screen point ([x], [y]) (alternative and selected preview layers). Empty when nothing is hit.
     */
    fun routeIndicesAt(x: Float, y: Float, boxPx: Float): List<Int> {
        val m = map ?: return emptyList()
        if (style == null) return emptyList()
        val h = boxPx / 2
        return runCatching {
            m.queryRenderedFeatures(android.graphics.RectF(x - h, y - h, x + h, y + h), L_ALT, L_ALT_CASING, L_SEL, L_SEL_CASING)
                .mapNotNull { f -> if (f.hasProperty(PROP_INDEX)) f.getNumberProperty(PROP_INDEX)?.toInt() else null }
                .distinct()
        }.getOrDefault(emptyList())
    }

    /** Our sources and layers, below the first symbol layer (street names stay readable, §7.2/§7.4). */
    private fun addOverlay(s: Style) {
        val c = colours ?: return
        val d = context.resources.displayMetrics.density
        listOf(SRC_ROUTE, SRC_ALT, SRC_PIN, SRC_CANDIDATE, SRC_DOT, SRC_PUCK, SRC_ORIGIN, SRC_STEP).forEach { s.addSource(GeoJsonSource(it)) }
        s.addImage(IMG_PUCK, MapBitmaps.puck(d, c, stale = false))
        s.addImage(IMG_PUCK_STALE, MapBitmaps.puck(d, c, stale = true))
        s.addImage(IMG_PIN, MapBitmaps.pin(d, c, candidate = false))
        s.addImage(IMG_CANDIDATE, MapBitmaps.pin(d, c, candidate = true))
        val firstSymbol: String? = s.layers.firstOrNull { it is SymbolLayer }?.id
        fun below(layer: Layer) = if (firstSymbol != null) s.addLayerBelow(layer, firstSymbol) else s.addLayer(layer)
        // §7.2 preview widths (z5/10/14/18) and §7.4 guidance widths (z12/15/18).
        below(line(L_OLD_CASING, c.routeAlternativeCasing, widths(12f to 6f, 15f to 9f, 18f to 12f)))
        below(line(L_OLD, c.routeAlternative, widths(12f to 4f, 15f to 6f, 18f to 9f)))
        // NAV-011 §7.6: alternatives (§7.2 widths in dp, ≥ 2 dp narrower than the selected line), always visible.
        below(altLine(L_ALT_CASING, c.routeAlternativeCasing, widths(5f to 4f, 10f to 6f, 14f to 8f, 18f to 11f)))
        below(altLine(L_ALT, c.routeAlternative, widths(5f to 2f, 10f to 4f, 14f to 6f, 18f to 9f)))
        below(line(L_SEL_CASING, c.routeSelectedCasing, widths(5f to 8f, 10f to 10f, 14f to 12f, 18f to 16f)))
        below(line(L_SEL, c.routeSelected, widths(5f to 4f, 10f to 6f, 14f to 8f, 18f to 12f)))
        below(line(L_GUIDE_CASING, c.routeSelectedCasing, widths(12f to 10f, 15f to 14f, 18f to 18f)))
        below(line(L_GUIDE, c.routeSelected, widths(12f to 6f, 15f to 9f, 18f to 13f)))
        // NAV-018 §7.7: manoeuvre point above the selected line, below the first symbol layer (radius 6 dp, 3 dp ring).
        below(
            CircleLayer(L_STEP, SRC_STEP).withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor(argb(c.routeStepFill)),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor(argb(c.routeStepStroke)),
            ),
        )
        // Markers and puck above everything (map-style §7.1, §7.4).
        s.addLayer(
            CircleLayer(L_DOT, SRC_DOT).withProperties(
                PropertyFactory.circleRadius(7.5f),
                PropertyFactory.circleColor(argb(c.locationDot)),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor(argb(c.locationDotStroke)),
            ),
        )
        // NAV-018 §7.7: chosen-start marker, 18 dp incl. a 4 dp ring (radius 5 + stroke 4), below the pins and the dot.
        s.addLayerBelow(
            CircleLayer(L_ORIGIN, SRC_ORIGIN).withProperties(
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleColor(argb(c.routeOriginFill)),
                PropertyFactory.circleStrokeWidth(4f),
                PropertyFactory.circleStrokeColor(argb(c.routeOriginStroke)),
            ),
            L_DOT,
        )
        for ((id, src, img) in listOf(Triple(L_CANDIDATE, SRC_CANDIDATE, IMG_CANDIDATE), Triple(L_PIN, SRC_PIN, IMG_PIN))) {
            s.addLayer(
                SymbolLayer(id, src).withProperties(
                    PropertyFactory.iconImage(img),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            )
        }
        s.addLayer(
            SymbolLayer(L_PUCK, SRC_PUCK).withProperties(
                PropertyFactory.iconImage(IMG_PUCK),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }

    // ------------------------------------------------------------------------------------------- camera

    override fun moveTo(p: LatLon, zoom: Double) {
        map?.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(zoom).bearing(0.0).tilt(0.0).build()))
    }

    override fun easeTo(p: LatLon, zoom: Double?, durationMs: Int) {
        val m = map ?: return
        val b = CameraPosition.Builder(m.cameraPosition).target(LatLng(p.lat, p.lon))
        if (zoom != null) b.zoom(zoom)
        m.easeCamera(CameraUpdateFactory.newCameraPosition(b.build()), durationMs)
    }

    /**
     * NAV-004 camera rule in the preview: fit the route above the sheet (padding = sheet + 40 dp). NAV-011 AC 16 /
     * ADR-0012 §5.4: zoom at most [MAX_FIT_ZOOM]; when the padding leaves no room, the caller passes 40 dp all round.
     */
    override fun fit(points: List<LatLon>, left: Int, top: Int, right: Int, bottom: Int) {
        val m = map ?: return
        if (points.size < 2) return
        val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
        val target = runCatching { m.getCameraForLatLngBounds(bounds, intArrayOf(left, top, right, bottom)) }.getOrNull()
        if (target != null && target.zoom > MAX_FIT_ZOOM) {
            m.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(target).zoom(MAX_FIT_ZOOM).build()), 300)
        } else {
            m.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, left, top, right, bottom), 300)
        }
    }

    /** NAV-018 AC 22 (screen spec Q8): centre [p] at [zoom] inside the padded area; [durationMs] 0 = jump. */
    override fun focus(p: LatLon, zoom: Double, left: Int, top: Int, right: Int, bottom: Int, durationMs: Int) {
        val m = map ?: return
        val pos = CameraPosition.Builder(m.cameraPosition)
            .target(LatLng(p.lat, p.lon))
            .zoom(zoom)
            .padding(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble())
            .build()
        if (durationMs > 0) m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), durationMs) else m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
    }

    /** navigation-ux §8 follow camera; [animate] false with reduced motion. */
    override fun follow(target: LatLon, bearing: Double, tilt: Double, zoom: Double, padding: CameraRules.Padding, animate: Boolean) {
        val m = map ?: return
        val pos = CameraPosition.Builder()
            .target(LatLng(target.lat, target.lon))
            .bearing(bearing)
            .tilt(tilt)
            .zoom(zoom)
            .padding(padding.left, padding.top, padding.right, padding.bottom)
            .build()
        if (animate) m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), CameraRules.FOLLOW_MS, false) else m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
    }

    override fun resetNorth() {
        val m = map ?: return
        m.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).bearing(0.0).tilt(0.0).build()), 300)
    }

    override fun zoomBy(delta: Double) {
        map?.easeCamera(CameraUpdateFactory.zoomBy(delta), 200)
    }

    override val bearing: Double get() = map?.cameraPosition?.bearing ?: 0.0
    override val center: LatLon? get() = map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) }
    override val zoom: Double get() = map?.cameraPosition?.zoom ?: 12.0
    override val heightPx: Int get() = mapView.height
    override val widthPx: Int get() = mapView.width

    companion object {
        const val SRC_ROUTE = "nav-route"
        /** NAV-011 §7.6: unselected preview routes (property [PROP_INDEX] = position in the parsed routes list). */
        const val SRC_ALT = "nav-route-alt"
        const val L_ALT_CASING = "nav-route-alt-casing"
        const val L_ALT = "nav-route-alt"
        /** Route feature property: the route's position in the parsed routes list (`PreviewResult.Route.routes`), not the Valhalla index. */
        const val PROP_INDEX = "index"
        const val MAX_FIT_ZOOM = 17.0
        const val SRC_PIN = "nav-pin"
        const val SRC_CANDIDATE = "nav-candidate"
        const val SRC_DOT = "nav-location"
        const val SRC_PUCK = "nav-puck"
        /** NAV-018 map-style §7.7. */
        const val SRC_ORIGIN = "nav-origin"
        const val L_ORIGIN = "nav-origin"
        const val SRC_STEP = "nav-route-step"
        const val L_STEP = "nav-route-step"
        const val L_SEL_CASING = "nav-route-sel-casing-preview"
        const val L_SEL = "nav-route-sel-preview"
        const val L_GUIDE_CASING = "nav-route-sel-casing"
        const val L_GUIDE = "nav-route-sel"
        const val L_OLD_CASING = "nav-route-old-casing"
        const val L_OLD = "nav-route-old"
        const val L_DOT = "nav-location-dot"
        const val L_PIN = "nav-pin"
        const val L_CANDIDATE = "nav-candidate"
        const val L_PUCK = "nav-puck"
        const val IMG_PUCK = "nav-puck"
        const val IMG_PUCK_STALE = "nav-puck-stale"
        const val IMG_PIN = "nav-pin"
        const val IMG_CANDIDATE = "nav-candidate"
    }
}

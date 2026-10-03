package mn.navmn.app.ui.components

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import mn.navmn.app.geo.LatLon
import mn.navmn.app.map.MapCamera
import mn.navmn.app.map.MapContent
import mn.navmn.app.map.MapSurface
import mn.navmn.app.map.NavMapController
import mn.navmn.app.ui.theme.TokenColours
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import javax.inject.Inject

/** ADR-0009 §6 / §10: the app's [MapSurface] is MapLibre Native through [NavMap]. */
class MapLibreSurface @Inject constructor() : MapSurface {
    @Composable
    override fun Map(
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
        onTap: ((LatLon, List<Int>) -> Unit)?,
    ) = NavMap(modifier, description, night, colours, pmtilesUrl, content, onReady, onLongPress, onGesture, onCameraIdle, onFailed, onTap)
}

/**
 * MapLibre Native in Compose (ADR-0009 §6: AndroidView wrapper). The style flavor follows the theme; our layers are
 * re-added on every style load with the current [content]. One accessibility node («Газрын зураг»).
 */
@Composable
fun NavMap(
    modifier: Modifier,
    description: String,
    night: Boolean,
    colours: TokenColours,
    pmtilesUrl: String,
    content: MapContent,
    onReady: (NavMapController) -> Unit,
    onLongPress: ((LatLon) -> Unit)?,
    onGesture: () -> Unit,
    onCameraIdle: (center: LatLon, bearing: Double, zoom: Double) -> Unit,
    onFailed: () -> Unit,
    onTap: ((LatLon, List<Int>) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val longPress = rememberUpdatedState(onLongPress)
    val gesture = rememberUpdatedState(onGesture)
    val idle = rememberUpdatedState(onCameraIdle)
    val failed = rememberUpdatedState(onFailed)
    val tap = rememberUpdatedState(onTap)
    val controller = remember {
        MapLibre.getInstance(context)
        val options = MapLibreMapOptions.createFromAttributes(context).attributionEnabled(false).logoEnabled(false).compassEnabled(false)
        val view = MapView(context, options)
        view.onCreate(Bundle())
        NavMapController(context, view)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> controller.mapView.onStart()
                Lifecycle.Event.ON_RESUME -> controller.mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.mapView.onPause()
                Lifecycle.Event.ON_STOP -> controller.mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.mapView.onPause()
            controller.mapView.onStop()
            controller.mapView.onDestroy()
        }
    }
    LaunchedEffect(controller) {
        controller.mapView.addOnDidFailLoadingMapListener { failed.value() }
        controller.mapView.getMapAsync { map ->
            controller.attach(map)
            map.addOnMapLongClickListener { p ->
                val cb = longPress.value
                if (cb != null) cb(LatLon(p.latitude, p.longitude))
                cb != null
            }
            // NAV-011 AC 17 / map-style §7.6: 48 × 48 dp query box around the tap; the tap never moves the camera.
            map.addOnMapClickListener { p ->
                val cb = tap.value ?: return@addOnMapClickListener false
                val px = map.projection.toScreenLocation(p)
                val box = TAP_BOX_DP * context.resources.displayMetrics.density
                cb(LatLon(p.latitude, p.longitude), controller.routeIndicesAt(px.x, px.y, box))
                false
            }
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) gesture.value()
            }
            map.addOnCameraIdleListener {
                val c = map.cameraPosition
                c.target?.let { idle.value(LatLon(it.latitude, it.longitude), c.bearing, c.zoom) }
            }
            controller.setStyle(night, colours, pmtilesUrl)
            controller.apply(content)
            onReady(controller)
        }
    }
    LaunchedEffect(night, colours, pmtilesUrl) { controller.setStyle(night, colours, pmtilesUrl) }
    LaunchedEffect(content) { controller.apply(content) }
    AndroidView(factory = { controller.mapView }, modifier = modifier.semantics { contentDescription = description })
}

/** map-style §7.6: the tap box is 48 × 48 dp (≥ 24 dp on each side of a line centre, NAV-011 AC 17). */
private const val TAP_BOX_DP = 48f

package mn.navmn.app.ui

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mn.navmn.app.R
import mn.navmn.app.config.AppConfig
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.map.CameraRules
import mn.navmn.app.map.MapCamera
import mn.navmn.app.map.MapContent
import mn.navmn.app.map.MapSurface
import mn.navmn.app.route.TravelMode
import mn.navmn.app.search.SearchView
import mn.navmn.app.settings.ThemeChoice
import mn.navmn.app.ui.components.AttributionStrip
import mn.navmn.app.ui.components.rememberStrings
import mn.navmn.app.ui.screens.BrowseActions
import mn.navmn.app.ui.screens.BrowseModel
import mn.navmn.app.ui.screens.BrowseOverlay
import mn.navmn.app.ui.screens.Covered
import mn.navmn.app.ui.screens.GuidanceOverlay
import mn.navmn.app.ui.screens.SettingsSheet
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavTheme

/** Platform intents the root needs from the Activity (settings pages, background). */
class PlatformActions(
    val openAppSettings: () -> Unit,
    val openLocationSettings: () -> Unit,
    val moveToBack: () -> Unit,
)

@Composable
fun NavRoot(vm: AppViewModel, mapSurface: MapSurface, platform: PlatformActions) {
    val themeChoice by vm.theme.collectAsState()
    val night = when (themeChoice) {
        ThemeChoice.DAY -> false
        ThemeChoice.NIGHT -> true
        ThemeChoice.AUTO -> isSystemInDarkTheme()
    }
    NavTheme(night) { NavScreen(vm, mapSurface, platform, night) }
}

@Composable
private fun NavScreen(vm: AppViewModel, mapSurface: MapSurface, platform: PlatformActions, night: Boolean) {
    val ui by vm.ui.collectAsState()
    val lang by vm.lang.collectAsState()
    val muted by vm.muted.collectAsState()
    val online by vm.online.collectAsState()
    val preview by vm.preview.state.collectAsState()
    val searchView by vm.search.view.collectAsState()
    val guidance by vm.guidance.collectAsState()
    val me by vm.myLocation.collectAsState()
    val strings = rememberStrings(lang)
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val density = LocalDensity.current

    var controller by remember { mutableStateOf<MapCamera?>(null) }
    var bearing by remember { mutableDoubleStateOf(0.0) }
    var zoom by remember { mutableDoubleStateOf(AppViewModel.DEFAULT_ZOOM) }
    var covered by remember { mutableStateOf(Covered()) }
    var sheetPx by remember { mutableIntStateOf(0) }
    var mapHeightPx by remember { mutableIntStateOf(0) }
    var followZoom by remember { mutableStateOf<Double?>(null) }

    val g: GuidanceState? = guidance
    val guiding = g != null && g.phase != GuidancePhase.ENDED

    // AC 18: the screen stays on while guiding; cleared when guidance ends (also at arrival).
    KeepScreenOn(guiding && g?.phase != GuidancePhase.ARRIVED)

    // System Back (screen spec › Interactions).
    BackHandler(enabled = guiding || preview != null || ui.card != null || searchView !is SearchView.Closed || ui.settingsOpen) {
        when {
            ui.settingsOpen -> vm.openSettings(false)
            guiding && g?.phase == GuidancePhase.ARRIVED -> vm.onArrivalClose()
            guiding -> platform.moveToBack()
            preview != null -> vm.closePreview()
            ui.card != null -> vm.closeCard()
            else -> vm.clearSearch()
        }
    }

    val content = when {
        guiding -> MapContent(
            route = g!!.route,
            guidance = true,
            routeDimmed = g.phase == GuidancePhase.OFF_ROUTE,
            destination = g.trip.destination,
            puck = g.puck?.position,
            puckBearing = g.puck?.bearingDeg ?: 0.0,
            puckStale = g.puck?.stale ?: false,
        )
        preview != null -> MapContent(
            route = (preview!!.result as? mn.navmn.app.preview.PreviewResult.Route)?.route?.plan?.geometry ?: emptyList(),
            destination = preview!!.destination.point,
            myLocation = me?.latLon,
        )
        else -> MapContent(candidate = ui.card, myLocation = if (ui.followingMe || me != null) me?.latLon else null)
    }

    // Camera: S1 follow, S3 fit, S5 follow per navigation-ux §8 (reduced motion → no easing).
    val reducedMotion = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    LaunchedEffect(controller, g?.puck, ui.orientation, ui.cameraFollowing, covered, guiding, mapHeightPx) {
        val c = controller ?: return@LaunchedEffect
        val puck = g?.puck ?: return@LaunchedEffect
        if (!guiding || !ui.cameraFollowing) return@LaunchedEffect
        val headingUp = ui.orientation == Orientation.HEADING_UP
        val z = CameraRules.zoom(g.speedMps * 3.6, g.trip.mode == TravelMode.WALK, followZoom)
        followZoom = z
        val pad = CameraRules.padding(mapHeightPx.toDouble(), covered.top.toDouble(), covered.bottom.toDouble(), covered.left.toDouble(), 0.0, headingUp)
        c.follow(
            puck.position,
            if (headingUp) (puck.bearingDeg ?: c.bearing) else 0.0,
            if (headingUp) CameraRules.PITCH_HEADING_UP else CameraRules.PITCH_NORTH_UP,
            z,
            pad,
            animate = !reducedMotion,
        )
    }
    // AC 25: following resumes 15 s after the last map gesture.
    LaunchedEffect(ui.cameraFollowing, ui.lastGestureAt) {
        if (guiding && !ui.cameraFollowing) {
            delay(CameraRules.RECENTER_TIMEOUT_MS)
            vm.recenter()
        }
    }
    LaunchedEffect(controller, preview?.result, sheetPx) {
        val c = controller ?: return@LaunchedEffect
        val r = preview?.result as? mn.navmn.app.preview.PreviewResult.Route ?: return@LaunchedEffect
        val pad = with(density) { 40.dp.roundToPx() }
        c.fit(r.route.plan.geometry, pad, pad * 3, pad, sheetPx + pad)
    }
    LaunchedEffect(controller, me, ui.followingMe) {
        val c = controller ?: return@LaunchedEffect
        val f = me ?: return@LaunchedEffect
        if (ui.followingMe && !guiding) c.easeTo(f.latLon, maxOf(c.zoom, 15.0))
    }
    var wasGuiding by remember { mutableStateOf(false) }
    LaunchedEffect(guiding) {
        // navigation-ux §7: after «Хаах» the bearing animates to north, the camera stays on the position.
        if (wasGuiding && !guiding) controller?.resetNorth()
        wasGuiding = guiding
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            mapSurface.Map(
                modifier = Modifier.fillMaxSize().testTag("map"),
                description = stringResource(R.string.map_content_description),
                night = night,
                colours = tokens,
                pmtilesUrl = AppConfig.pmtilesUrl(),
                content = content,
                onReady = { c ->
                    controller = c
                    if (c.center == null || c.zoom < 3) c.moveTo(AppViewModel.DEFAULT_CENTER, AppViewModel.DEFAULT_ZOOM)
                    mapHeightPx = c.heightPx
                },
                onLongPress = if (!guiding && preview == null) ({ p -> vm.onLongPress(p) }) else null,
                onGesture = { if (guiding) vm.onMapGesture() else vm.stopFollowingMe() },
                onCameraIdle = { center, b, z ->
                    vm.mapCenter = center
                    bearing = b
                    zoom = z
                    controller?.let { mapHeightPx = it.heightPx }
                },
                onFailed = { vm.setTilesFailed(true) },
            )
            if (guiding) {
                GuidanceOverlay(
                    state = g!!,
                    lang = lang,
                    strings = strings,
                    orientation = ui.orientation,
                    following = ui.cameraFollowing,
                    onEnd = vm::onEnd,
                    onSettings = { vm.openSettings(true) },
                    onToggleMute = vm::toggleMute,
                    onToggleOrientation = vm::toggleOrientation,
                    onRecenter = vm::recenter,
                    onDismissNotice = vm::dismissVoiceNotice,
                    onArrivalClose = vm::onArrivalClose,
                    onCovered = { covered = it },
                )
            } else {
                BrowseOverlay(
                    m = BrowseModel(lang, ui.query, searchView, ui.card, preview, ui.mapProblem, ui.tilesFailed, !online, bearing, ui.followingMe),
                    strings = strings,
                    a = BrowseActions(
                        onQuery = vm::onQuery,
                        onClearSearch = vm::clearSearch,
                        onResult = vm::onResult,
                        onRetrySearch = vm.search::retry,
                        onSettings = { vm.openSettings(true) },
                        onZoomIn = { controller?.zoomBy(1.0) },
                        onZoomOut = { controller?.zoomBy(-1.0) },
                        onNorthUp = { controller?.resetNorth() },
                        onMyLocation = vm::onMyLocation,
                        onCardClose = vm::closeCard,
                        onCardDirections = vm::onCardDirections,
                        onPreviewClose = vm::closePreview,
                        onMode = vm::setMode,
                        onAvoid = vm::setAvoid,
                        onPreviewRetry = vm::retryPreview,
                        onStart = vm::onStart,
                        onOpenAppSettings = platform.openAppSettings,
                        onOpenLocationSettings = platform.openLocationSettings,
                        onDismissMapProblem = vm::dismissMapProblem,
                        onRetryTiles = {
                            vm.setTilesFailed(false)
                            controller?.let { c -> c.setStyle(night, tokens, AppConfig.pmtilesUrl() + "") }
                        },
                        onSheetHeight = { sheetPx = it },
                    ),
                )
            }
        }
        AttributionStrip(showEsa = !guiding && zoom < 8, modifier = Modifier.navigationBarsPadding())
    }

    if (ui.rationale) {
        AlertDialog(
            onDismissRequest = vm::onRationaleClose,
            icon = { Icon(painterResource(R.drawable.ic_place), contentDescription = null, modifier = Modifier.size(24.dp)) },
            title = { Text(stringResource(R.string.location_rationale)) },
            confirmButton = { TextButton(onClick = vm::onRationaleContinue) { Text(stringResource(R.string.action_continue)) } },
            dismissButton = { TextButton(onClick = vm::onRationaleClose) { Text(stringResource(R.string.action_close)) } },
            modifier = Modifier.testTag("location-rationale"),
        )
    }
    if (ui.settingsOpen) {
        SettingsSheet(
            theme = vm.theme.collectAsState().value,
            lang = lang,
            voiceOn = !muted,
            onTheme = vm::setTheme,
            onLang = vm::setLanguage,
            onVoice = vm::setVoice,
            onDismiss = { vm.openSettings(false) },
        )
    }
}

/** AC 18: FLAG_KEEP_SCREEN_ON equivalent on the Compose view while [enabled]; cleared at once otherwise. */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

package mn.navmn.app.ui

import android.provider.Settings
import android.view.accessibility.AccessibilityManager
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import mn.navmn.app.R
import mn.navmn.app.background.BackgroundUi
import mn.navmn.app.background.LocalBackgroundUi
import mn.navmn.app.background.ProvideBatteryHint
import mn.navmn.app.theme.sun.ThemeResolver
import mn.navmn.app.config.AppConfig
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.map.CameraRules
import mn.navmn.app.map.CoordinateCamera
import mn.navmn.app.map.MapCamera
import mn.navmn.app.map.MapContent
import mn.navmn.app.map.MapSurface
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.points.PointRules
import mn.navmn.app.preview.points.PointSide
import mn.navmn.app.preview.turnlist.StepCamera
import mn.navmn.app.route.TravelMode
import mn.navmn.app.route.alternatives.AlternativeHitTest
import mn.navmn.app.search.SearchView
import mn.navmn.app.ui.components.AttributionStrip
import mn.navmn.app.ui.components.rememberStrings
import mn.navmn.app.ui.screens.BrowseActions
import mn.navmn.app.ui.screens.BrowseModel
import mn.navmn.app.ui.screens.BrowseOverlay
import mn.navmn.app.ui.screens.Covered
import mn.navmn.app.ui.screens.GuidanceOverlay
import mn.navmn.app.ui.screens.SettingsSheet
import mn.navmn.app.ui.screens.preview.PointActions
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavTheme

/** Platform intents the root needs from the Activity (settings pages, background). */
class PlatformActions(
    val openAppSettings: () -> Unit,
    val openLocationSettings: () -> Unit,
    val moveToBack: () -> Unit,
    /** NAV-012 AC 9: actions that leave the guidance screen ask for the OS unlock prompt first (LockScreenGate). */
    val requireUnlocked: (() -> Unit) -> Unit = { it() },
    /** NAV-012 AC 27: system battery-optimisation settings (fallback: the app's details page). */
    val openBatterySettings: () -> Unit = {},
)

@Composable
fun NavRoot(vm: AppViewModel, mapSurface: MapSurface, platform: PlatformActions, background: BackgroundUi? = null) {
    val themeChoice by vm.theme.collectAsState()
    // NAV-012 AC 41: «Автомат» follows sunrise/sunset at the current position (system dark theme only without NAV-012 wiring).
    val systemDark = isSystemInDarkTheme()
    val sunNight = background?.autoNight?.collectAsState()?.value ?: systemDark
    val night = ThemeResolver.night(themeChoice, sunNight)
    NavTheme(night) {
        CompositionLocalProvider(LocalBackgroundUi provides background) {
            ProvideBatteryHint(background?.battery, vm.preview.state, vm.guidance, { vm.setSheetExpanded(true) }, platform.openBatterySettings) {
                NavScreen(vm, mapSurface, platform, night)
            }
        }
    }
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
    val reverseView by vm.reverse.view.collectAsState()
    val lock by vm.typingLock.state.collectAsState()
    val points by vm.points.collectAsState() // NAV-018
    val fieldView by vm.fieldSearch.view.collectAsState()
    val strings = rememberStrings(lang)
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val density = LocalDensity.current

    var controller by remember { mutableStateOf<MapCamera?>(null) }
    var bearing by remember { mutableDoubleStateOf(0.0) }
    var zoom by remember { mutableDoubleStateOf(AppViewModel.DEFAULT_ZOOM) }
    var covered by remember { mutableStateOf(Covered()) }
    var sheetPx by remember { mutableIntStateOf(0) }
    var sheetStartPx by remember { mutableIntStateOf(0) }
    var topBarPx by remember { mutableIntStateOf(0) }
    var mapHeightPx by remember { mutableIntStateOf(0) }
    var followZoom by remember { mutableStateOf<Double?>(null) }
    // NAV-011 C1 (typed-coordinate camera): overlay geometry in root px.
    var mapRect by remember { mutableStateOf(Rect.Zero) }
    var topGroupBottom by remember { mutableFloatStateOf(0f) }
    var cardRect by remember { mutableStateOf<Rect?>(null) }
    var cardWide by remember { mutableStateOf(false) }
    var laneStart by remember { mutableStateOf<Float?>(null) }

    val g: GuidanceState? = guidance
    val guiding = g != null && g.phase != GuidancePhase.ENDED

    // AC 18: the screen stays on while guiding; cleared when guidance ends (also at arrival).
    KeepScreenOn(guiding && g?.phase != GuidancePhase.ARRIVED)

    // NAV-011 AC 27 (ADR-0012 §7): the typing lock reads 1 Hz fixes only while S1/S3 are visible (Activity STARTED,
    // no guidance); leaving them cancels the collection, which removes the platform listener.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, guiding) {
        if (guiding) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.collectTypingLock() }
    }
    // NAV-011 P4: with TalkBack touch exploration on, the preview sheet opens expanded.
    LaunchedEffect(Unit) {
        val am = context.getSystemService(AccessibilityManager::class.java)
        vm.touchExploration = { am?.isTouchExplorationEnabled == true }
    }

    // System Back (screen spec › Interactions).
    BackHandler(enabled = guiding || preview != null || ui.card != null || searchView !is SearchView.Closed || ui.settingsOpen || lock.cardVisible) {
        when {
            ui.settingsOpen -> vm.openSettings(false)
            guiding && g?.phase == GuidancePhase.ARRIVED -> vm.onArrivalClose()
            guiding -> platform.moveToBack()
            lock.cardVisible && lock.locked -> vm.dismissTypingLock() // NAV-011: Back closes the lock card
            points.editor != null -> vm.closePointEditor() // NAV-018: Back leaves the point editor, nothing changes (AC 7)
            preview != null && ui.card != null -> vm.closeCard() // NAV-018: the card returns to the preview, 0 requests
            preview != null && ui.sheetExpanded -> vm.setSheetExpanded(false) // NAV-011: an expanded sheet collapses first
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
        preview != null -> {
            // NAV-011 map-style §7.6: the selected route on top, the other routes of the response below it.
            val r = preview!!.result as? PreviewResult.Route
            MapContent(
                route = r?.route?.plan?.geometry ?: emptyList(),
                destination = preview!!.destination.point,
                myLocation = me?.latLon,
                routeIndex = r?.selected ?: 0,
                alternatives = r?.routes?.mapIndexedNotNull { i, x -> if (i == r.selected) null else i to x.plan.geometry } ?: emptyList(),
                // NAV-018 map-style §7.7: chosen-start marker, candidate pin while the card is open, manoeuvre point.
                origin = preview!!.origin?.takeIf { PointRules.isChosenStart(it) }?.point,
                candidate = ui.card,
                step = points.step?.takeIf { r != null && it.route === r.route }?.location,
            )
        }
        else -> MapContent(candidate = ui.card, myLocation = if (ui.followingMe || me != null) me?.latLon else null)
    }

    // Camera: S1 follow, S3 fit, S5 follow per navigation-ux §8 (reduced motion → no easing).
    val reducedMotion = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    LaunchedEffect(controller, g?.puck, ui.orientation, ui.cameraFollowing, covered, guiding, mapHeightPx) {
        val c = controller ?: return@LaunchedEffect
        val puck = g?.puck ?: return@LaunchedEffect
        if (!guiding || !ui.cameraFollowing) return@LaunchedEffect
        val headingUp = ui.orientation == Orientation.HEADING_UP
        val z = CameraRules.zoom(g.speedMps * 3.6, g.trip.mode != TravelMode.CAR, followZoom)
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
    // NAV-011 AC 16 (ADR-0012 §5.4, P3): one fit per response (not per selection, not when the sheet is dragged) to
    // every drawn route and both markers, above the sheet and below the top bar, 40 dp padding, zoom ≤ 17.
    val currentSheetPx by rememberUpdatedState(sheetPx)
    val currentSheetStartPx by rememberUpdatedState(sheetStartPx)
    val currentTopBarPx by rememberUpdatedState(topBarPx)
    val fitRoutes = (preview?.result as? PreviewResult.Route)?.routes
    LaunchedEffect(controller, fitRoutes) {
        val c = controller ?: return@LaunchedEffect
        val routes = fitRoutes ?: return@LaunchedEffect
        delay(FIT_SETTLE_MS) // the sheet re-measures for the new result first
        val pad = with(density) { 40.dp.roundToPx() }
        // NAV-018 PO answer 7: with a chosen start the live device position is not part of the fit.
        val markers = preview?.let { PointRules.fitMarkers(it.origin, it.destination, me?.latLon) }.orEmpty()
        val points = routes.flatMap { it.plan.geometry } + markers
        val left = pad + currentSheetStartPx
        val top = pad + currentTopBarPx
        val bottom = pad + currentSheetPx
        val roomy = (c.widthPx <= 0 || left + pad < c.widthPx - 1) && (c.heightPx <= 0 || top + bottom < c.heightPx - 1)
        if (roomy) c.fit(points, left, top, pad, bottom) else c.fit(points, pad, pad, pad, pad)
    }
    // NAV-018 AC 22 (Q8): a turn-row tap centres the step at zoom max(17, current) in the uncovered map area, 40 dp
    // padding; after a collapse the sheet re-measures first. Theme, language and rotation do not repeat it.
    val stepFocus = points.step
    var handledStep by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(controller, stepFocus?.seq) {
        val c = controller ?: return@LaunchedEffect
        val f = stepFocus ?: return@LaunchedEffect
        if (f.seq == handledStep) return@LaunchedEffect
        handledStep = f.seq
        if (f.collapse) delay(STEP_COLLAPSE_SETTLE_MS)
        val pad = StepCamera.padding(with(density) { StepCamera.PADDING_DP.dp.roundToPx() }, currentSheetStartPx, currentTopBarPx, currentSheetPx)
        c.focus(f.location, StepCamera.zoom(c.zoom), pad.left, pad.top, pad.right, pad.bottom, if (reducedMotion) 0 else StepCamera.EASE_MS)
    }
    LaunchedEffect(controller, me, ui.followingMe) {
        val c = controller ?: return@LaunchedEffect
        val f = me ?: return@LaunchedEffect
        // Zero padding: MapLibre keeps the padding of an earlier focus move (C1, NAV-018 step focus) on the camera, which
        // would otherwise offset «Миний байршил» (ADR-0012 Amendment A4).
        if (ui.followingMe && !guiding) c.focus(f.latLon, maxOf(c.zoom, 15.0), 0, 0, 0, 0, 300)
    }
    // NAV-011 AC 7a, C1 (D142): the typed-coordinate option centres the point once, after the card's first layout, at
    // zoom max(current, 16), clear of the top group and the card. One-shot: handled in the ViewModel, so a rotation does
    // not repeat it; a long-press never sets it (AC 9).
    val coordinateFocus = ui.coordinateFocus
    LaunchedEffect(controller, coordinateFocus?.seq) {
        val c = controller ?: return@LaunchedEffect
        val f = coordinateFocus ?: return@LaunchedEffect
        val card = withTimeoutOrNull(COORDINATE_CARD_LAYOUT_MS) { snapshotFlow { cardRect }.filterNotNull().first() }
        vm.onCoordinateFocusHandled(f.seq)
        val m = mapRect
        // The map view fills the measured map box; the camera's own size is the fallback before the first layout.
        val w = m.width.toInt().takeIf { it > 0 } ?: c.widthPx
        val h = m.height.toInt().takeIf { it > 0 } ?: c.heightPx
        val box = card?.let { CoordinateCamera.Box((it.left - m.left).toInt(), (it.top - m.top).toInt(), (it.right - m.left).toInt(), (it.bottom - m.top).toInt()) }
        val pad = CoordinateCamera.padding(
            mapWidth = w, mapHeight = h,
            topGroupBottom = (topGroupBottom - m.top).toInt(),
            card = box,
            laneStart = laneStart?.let { (it - m.left).toInt() },
            wide = cardWide,
            density = density.density,
        )
        c.focus(f.point, CoordinateCamera.zoom(c.zoom), pad.left, pad.top, pad.right, pad.bottom, if (reducedMotion) 0 else CoordinateCamera.EASE_MS)
    }
    // NAV-012 Layout rule 9: while restoring (no position yet) the camera fits the stored route; no puck, no recenter.
    LaunchedEffect(controller, g?.restoring) {
        val c = controller ?: return@LaunchedEffect
        val r = g ?: return@LaunchedEffect
        if (!r.restoring || r.route.isEmpty()) return@LaunchedEffect
        val pad = with(density) { 40.dp.roundToPx() }
        c.fit(r.route, pad, pad + covered.top, pad, pad + covered.bottom)
    }
    var wasGuiding by remember { mutableStateOf(false) }
    LaunchedEffect(guiding) {
        // navigation-ux §7: after «Хаах» the bearing animates to north, the camera stays on the position.
        if (wasGuiding && !guiding) controller?.resetNorth()
        wasGuiding = guiding
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { mapRect = it.boundsInRoot() }) {
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
                // NAV-018 AC 6: long-press also works during the preview (the card then sets the start or destination).
                onLongPress = if (!guiding && points.editor == null) ({ p -> vm.onLongPress(p) }) else null,
                // NAV-011 AC 17: a tap on an unselected line selects it (0 requests, no camera move).
                onTap = if (!guiding && preview != null) ({ p, hit ->
                    // NAV-018 AC 7: a map tap leaves the point editor without changes.
                    val editing = vm.points.value.editor != null
                    if (editing) vm.closePointEditor()
                    val r = vm.preview.state.value?.result as? PreviewResult.Route
                    if (!editing && r != null && r.k >= 2) {
                        AlternativeHitTest.pick(p, hit, r.selected, r.routes.map { it.plan.geometry })?.let(vm::selectRoute)
                    }
                }) else null,
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
                    onSettings = { platform.requireUnlocked { vm.openSettings(true) } }, // NAV-012 AC 9
                    onToggleMute = vm::toggleMute,
                    onToggleOrientation = vm::toggleOrientation,
                    onRecenter = vm::recenter,
                    onDismissNotice = vm::dismissVoiceNotice,
                    onArrivalClose = vm::onArrivalClose,
                    onCovered = { covered = it },
                )
            } else {
                BrowseOverlay(
                    m = BrowseModel(
                        lang, ui.query, searchView, ui.card, preview, ui.mapProblem, ui.tilesFailed, !online, bearing, ui.followingMe,
                        reverse = reverseView, lock = lock, sheetExpanded = ui.sheetExpanded, focusSearch = ui.focusSearch,
                        points = points, fieldView = fieldView, cardTitleFocus = ui.cardTitleFocus,
                    ),
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
                        onSelectRoute = vm::selectRoute,
                        onSheetExpanded = vm::setSheetExpanded,
                        onReverseRetry = vm.reverse::retry,
                        onSearchFieldTap = vm::onSearchFieldTap,
                        onSearchFocused = vm::onSearchFocused,
                        onLockedWhileTyping = vm::onLockedWhileTyping,
                        onDismissLock = {
                            vm.dismissTypingLock()
                            vm.closePointEditor() // NAV-018: «Хаах» on the lock card in the point editor closes the editor
                        },
                        onPassenger = vm::onPassenger,
                        onSheetStart = { sheetStartPx = it },
                        onTopBar = { topBarPx = it },
                        onCoordinateOption = vm::onCoordinateOption,
                        onTopGroup = { topGroupBottom = it },
                        onCardBounds = { r, wide ->
                            cardRect = r
                            cardWide = wide
                        },
                        onControlLane = { laneStart = it },
                        onCardTitleFocused = vm::onCardTitleFocused,
                        points = PointActions(
                            onField = vm::openPointEditor,
                            onSwap = vm::swapPoints,
                            onEditorQuery = vm::onPointQuery,
                            onEditorResult = vm::onPointResult,
                            onEditorRetry = vm.fieldSearch::retry,
                            onEditorCoordinate = vm::onPointTypedCoordinate,
                            onEditorMyLocation = vm::onPointMyLocation,
                            onEditorClose = vm::closePointEditor,
                            onEditorFieldTap = vm::onSearchFieldTap,
                            onCardSetOrigin = { vm.onCardSetPoint(PointSide.ORIGIN) },
                            onCardSetDestination = { vm.onCardSetPoint(PointSide.DESTINATION) },
                            // Q8: portrait touch use collapses the sheet; TalkBack and wide windows keep it as it is.
                            onTurnRow = { i -> vm.focusStep(i, collapse = sheetStartPx == 0 && !vm.touchExploration()) },
                        ),
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
            batteryRestricted = LocalBackgroundUi.current?.battery?.restricted?.collectAsState()?.value, // NAV-012 H2
            onOpenBatterySettings = platform.openBatterySettings,
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

/** NAV-011 AC 16: wait for the sheet to re-measure for a new response before the one camera fit (well inside 1 s). */
private const val FIT_SETTLE_MS = 150L

/** NAV-011 C1: the longest wait for the coordinate card's first layout before the camera moves anyway. */
private const val COORDINATE_CARD_LAYOUT_MS = 500L

/** NAV-018 Q8: the collapse (250 ms) settles before the step camera move; both finish within 1 s. */
private const val STEP_COLLAPSE_SETTLE_MS = 260L

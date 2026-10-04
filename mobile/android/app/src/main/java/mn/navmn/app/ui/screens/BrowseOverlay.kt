package mn.navmn.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mn.navmn.app.R
import mn.navmn.app.format.Formatters
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Strings
import mn.navmn.app.i18n.Templates
import mn.navmn.app.preview.LocationProblem
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.PreviewState
import mn.navmn.app.route.TravelMode
import mn.navmn.app.search.PlaceDisplay
import mn.navmn.app.search.SearchView
import mn.navmn.app.search.reverse.ReverseView
import mn.navmn.app.typinglock.TypingLockState
import mn.navmn.app.ui.components.MapIconButton
import mn.navmn.app.ui.components.MessageCard
import mn.navmn.app.preview.points.PointsUi
import mn.navmn.app.ui.screens.preview.CoordinateCardPointButtons
import mn.navmn.app.ui.screens.preview.MarkerDescriptions
import mn.navmn.app.ui.screens.preview.PointActions
import mn.navmn.app.ui.screens.preview.PointEditor
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c

/** Actions of the S1–S4 overlay (unidirectional: the overlay only calls these). */
class BrowseActions(
    val onQuery: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onResult: (PlaceDisplay.Info, String) -> Unit,
    val onRetrySearch: () -> Unit,
    val onSettings: () -> Unit,
    val onZoomIn: () -> Unit,
    val onZoomOut: () -> Unit,
    val onNorthUp: () -> Unit,
    val onMyLocation: () -> Unit,
    val onCardClose: () -> Unit,
    val onCardDirections: () -> Unit,
    val onPreviewClose: () -> Unit,
    val onMode: (TravelMode) -> Unit,
    val onAvoid: (Boolean) -> Unit,
    val onPreviewRetry: () -> Unit,
    val onStart: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onOpenLocationSettings: () -> Unit,
    val onDismissMapProblem: () -> Unit,
    val onRetryTiles: () -> Unit,
    val onSheetHeight: (Int) -> Unit,
    // NAV-011 (defaults keep the NAV-005 call sites and tests unchanged)
    val onSelectRoute: (Int) -> Unit = {},
    val onSheetExpanded: (Boolean) -> Unit = {},
    val onReverseRetry: () -> Unit = {},
    /** A tap on the search field: true → typing allowed; false → the lock card is shown (AC 31). */
    val onSearchFieldTap: () -> Boolean = { true },
    val onLockedWhileTyping: () -> Unit = {},
    val onDismissLock: () -> Unit = {},
    val onPassenger: () -> Unit = {},
    /** Side sheet width in px (wide windows; 0 otherwise) for the camera padding (P5). */
    val onSheetStart: (Int) -> Unit = {},
    /** Height of the top bar (search row) in px, for the camera padding (AC 16). */
    val onTopBar: (Int) -> Unit = {},
    /** NAV-011 AC 7a (D140; ADR-0012 Amendment A3): the typed-coordinate option was selected. */
    val onCoordinateOption: (LatLon) -> Unit = {},
    /** NAV-011 C1: bottom edge (root px) of the top group (search row, lock card, results, S1 messages). */
    val onTopGroup: (Float) -> Unit = {},
    /** NAV-011 C1: the coordinate card's bounds (root px) and whether it is the wide-window column (P8); null when gone. */
    val onCardBounds: (Rect?, Boolean) -> Unit = { _, _ -> },
    /** NAV-011 C1: start edge (root px) of the map-control lane on wide windows; null otherwise. */
    val onControlLane: (Float?) -> Unit = {},
    /** NAV-011 AC 7a: the card title took focus after the typed option (one-shot). */
    val onCardTitleFocused: () -> Unit = {},
    /** NAV-018: start/destination fields, swap, point editor, coordinate-card buttons, turn list. */
    val points: PointActions = PointActions(),
)

data class BrowseModel(
    val lang: Lang,
    val query: String,
    val searchView: SearchView,
    val card: LatLon?,
    val preview: PreviewState?,
    val mapProblem: LocationProblem?,
    val tilesFailed: Boolean,
    val offline: Boolean,
    val bearing: Double,
    val followingMe: Boolean,
    // NAV-011
    val reverse: ReverseView? = null,
    val lock: TypingLockState = TypingLockState(),
    val sheetExpanded: Boolean = false,
    val focusSearch: Int = 0,
    // NAV-018
    val points: PointsUi = PointsUi(),
    /** The point editor's results (the preview fields' own SearchController, ADR-0015 §5). */
    val fieldView: SearchView = SearchView.Closed,
    /** NAV-011 AC 7a: move focus to the coordinate card title once (the card opened from the typed option). */
    val cardTitleFocus: Boolean = false,
)

@Composable
fun LocationMessage(p: LocationProblem, actions: BrowseActions, onRetry: () -> Unit, onClose: () -> Unit, onMessageSurface: Boolean, modifier: Modifier = Modifier) {
    val openSettings = stringResource(R.string.action_open_settings) to actions.onOpenAppSettings
    val close = stringResource(R.string.action_close) to onClose
    when (p) {
        LocationProblem.NEEDS_PERMISSION, LocationProblem.DENIED -> MessageCard(
            R.drawable.ic_gps_off, stringResource(R.string.location_denied_title), stringResource(R.string.location_denied_hint),
            listOf(openSettings, close), modifier, onMessageSurface,
        )
        LocationProblem.APPROXIMATE -> MessageCard(
            R.drawable.ic_gps_off, stringResource(R.string.location_precise), null, listOf(openSettings, close), modifier, onMessageSurface,
        )
        LocationProblem.SERVICES_OFF -> MessageCard(
            R.drawable.ic_gps_off, stringResource(R.string.location_services_off), null,
            listOf(stringResource(R.string.action_open_settings) to actions.onOpenLocationSettings, close), modifier, onMessageSurface,
        )
        LocationProblem.UNAVAILABLE -> MessageCard(
            R.drawable.ic_gps_off, stringResource(R.string.location_unavailable), null,
            listOf(stringResource(R.string.action_retry) to onRetry, close), modifier, onMessageSurface,
        )
    }
}

/**
 * S1 search bar (docked) with the settings button. NAV-011 typing lock (AC 31, 34; screen spec › Search bar): no visual
 * change; while locked the field is read-only, a tap (or TalkBack focus) shows the lock card instead of the keyboard,
 * the lock engaging while typing hides the keyboard and keeps the text, and «Би зорчигч» focuses the field.
 */
@Composable
private fun SearchRow(m: BrowseModel, a: BrowseActions) {
    val t = LocalTokens.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val locked = m.lock.locked
    LaunchedEffect(locked) {
        if (locked && focused) {
            keyboard?.hide()
            focusManager.clearFocus()
            a.onLockedWhileTyping()
        }
    }
    // «Би зорчигч» bumps focusSearch; handled once (not again after a rotation recreates the composition).
    var handledFocus by rememberSaveable { mutableIntStateOf(m.focusSearch) }
    LaunchedEffect(m.focusSearch) {
        if (m.focusSearch > handledFocus) {
            handledFocus = m.focusSearch
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).onGloballyPositioned { a.onTopBar(it.size.height) }, verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(28.dp), color = t.uiSurface.c(), shadowElevation = 2.dp, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("search-bar")) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = t.uiOnSurfaceVariant.c())
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f).padding(vertical = 16.dp)) {
                    val placeholder = stringResource(R.string.search_placeholder)
                    if (m.query.isEmpty()) Text(placeholder, style = NavType.bodyLarge, color = t.uiOnSurfaceVariant.c())
                    BasicTextField(
                        value = m.query,
                        onValueChange = { if (!locked) a.onQuery(it.take(200)) },
                        singleLine = true,
                        readOnly = locked,
                        textStyle = NavType.bodyLarge.copy(color = t.uiOnSurface.c()),
                        cursorBrush = SolidColor(t.uiPrimary.c()),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { fs ->
                                val gained = fs.isFocused && !focused
                                focused = fs.isFocused
                                if (gained && !a.onSearchFieldTap()) {
                                    keyboard?.hide()
                                    focusManager.clearFocus()
                                }
                            }
                            .semantics { contentDescription = placeholder },
                    )
                }
                if (m.query.isNotEmpty()) {
                    IconButton(onClick = a.onClearSearch, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_clear))
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        MapIconButton(R.drawable.ic_settings, stringResource(R.string.settings_title), a.onSettings, Modifier.testTag("settings"))
    }
}

/**
 * S2 results list under the search bar (up to 360 dp). [modifier] may bound it further: on short screens the list
 * shrinks (and scrolls) so it never runs into the map controls (NAV-005-D5).
 */
@Composable
internal fun SearchResults(
    m: BrowseModel?,
    strings: Strings,
    a: BrowseActions,
    modifier: Modifier = Modifier,
    // NAV-018 (AC 4): the point editor reuses this card with its own SearchController's view and selection.
    view: SearchView = m?.searchView ?: SearchView.Closed,
    onResult: (PlaceDisplay.Info, String) -> Unit = a.onResult,
    onRetry: () -> Unit = a.onRetrySearch,
    tag: String = "search-results",
    sidePadding: androidx.compose.ui.unit.Dp = 16.dp,
    // NAV-011 D140 (ADR-0012 Amendment A3): selecting the coordinate option. null → the row shows but is not
    // selectable (transitional, until NAV-018's point editor passes its own handler, D145).
    onCoordinate: ((LatLon) -> Unit)? = null,
) {
    val t = LocalTokens.current
    val v = view
    if (v is SearchView.Closed) return
    Surface(shape = RoundedCornerShape(16.dp), color = t.uiSurface.c(), shadowElevation = 2.dp, modifier = modifier.padding(start = sidePadding, end = sidePadding, bottom = 8.dp)) {
        val listName = stringResource(R.string.search_results)
        Column(Modifier.heightIn(max = 360.dp).semantics { contentDescription = listName }.testTag(tag)) {
            when (v) {
                SearchView.Loading -> StateRow(stringResource(R.string.status_loading), progress = true)
                SearchView.NoResults -> StateRow(stringResource(R.string.search_no_results))
                SearchView.Unavailable -> StateRow(stringResource(R.string.search_unavailable), retry = onRetry)
                SearchView.Error -> StateRow(stringResource(R.string.status_generic_error))
                SearchView.Offline -> StateRow(stringResource(R.string.status_offline))
                is SearchView.RateLimited -> StateRow(stringResource(R.string.search_rate_limited), retry = onRetry, retryEnabled = v.retryEnabled)
                is SearchView.Results -> LazyColumn {
                    items(v.items) { info ->
                        val name = info.name ?: strings[info.type]
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onResult(info, name) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(name, style = NavType.bodyLarge, color = t.uiOnSurface.c(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val second = listOfNotNull(strings[info.type].takeIf { info.name != null }, info.context).joinToString(" · ")
                            if (second.isNotEmpty()) Text(second, style = NavType.body, color = t.uiOnSurfaceVariant.c(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider(color = t.uiOutlineVariant.c())
                    }
                }
                is SearchView.Coordinate -> CoordinateOptionRow(v.point, strings, onCoordinate)
                SearchView.Closed -> Unit
            }
        }
    }
}

/**
 * NAV-011 AC 7 (D140, D146; screen spec › Components › Coordinate option): the only row for a recognised coordinate
 * pair: `ic_location_searching`, «Сонгосон цэг» and the coordinates (5 decimals). One merged button node for TalkBack
 * («Сонгосон цэг, 47.91890, 106.91760», existing strings only). Selectable while the typing lock is engaged (AC 33):
 * it is a list item, not typing. Selecting hides the keyboard and clears the field focus (AC 7a).
 */
@Composable
private fun CoordinateOptionRow(p: LatLon, strings: Strings, onSelect: ((LatLon) -> Unit)?) {
    val t = LocalTokens.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // `place_selected_point` through the app's language strings, so a language switch relabels at once (AC 13).
    val label = strings[StringKey.PLACE_SELECTED_POINT]
    val coords = Formatters.coordinates(p.lat, p.lon)
    val select = if (onSelect == null) Modifier else Modifier.clickable(role = Role.Button) {
        keyboard?.hide()
        focusManager.clearFocus()
        onSelect(p)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(select)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $coords" }
            .testTag("search-coordinate-option")
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_location_searching), contentDescription = null, tint = t.uiOnSurfaceVariant.c(), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = NavType.bodyLarge, color = t.uiOnSurface.c())
            Text(coords, style = NavType.body.copy(fontFeatureSettings = "tnum"), color = t.uiOnSurfaceVariant.c())
        }
    }
}

/** S1 status messages under the search area (location problem, tiles failed, offline); scrolls when space is short. */
@Composable
private fun StatusMessages(m: BrowseModel, a: BrowseActions, modifier: Modifier = Modifier) {
    val problem = m.mapProblem
    val showProblem = problem != null && m.preview == null
    if (!showProblem && !m.tilesFailed && !m.offline) return
    Column(modifier.verticalScroll(rememberScrollState())) {
        if (showProblem) {
            LocationMessage(problem, a, a.onMyLocation, a.onDismissMapProblem, onMessageSurface = true, modifier = Modifier.padding(horizontal = 16.dp))
        } else if (m.tilesFailed) {
            MessageCard(R.drawable.ic_warning, stringResource(R.string.status_tiles_unavailable), null, listOf(stringResource(R.string.action_retry) to a.onRetryTiles), Modifier.padding(horizontal = 16.dp))
        } else {
            MessageCard(R.drawable.ic_cloud_off, stringResource(R.string.status_offline), null, emptyList(), Modifier.padding(horizontal = 16.dp))
        }
    }
}

/** S1 map controls (screen spec › Map controls): zoom in, zoom out, north-up (bearing ≠ 0), my location. */
@Composable
private fun MapControls(m: BrowseModel, a: BrowseActions, modifier: Modifier = Modifier, lane: Boolean = false) {
    // NAV-011 C1: on wide windows the controls are a lane at the end edge; the typed-coordinate camera keeps clear of it.
    DisposableEffect(lane) { onDispose { if (lane) a.onControlLane(null) } }
    val report = if (lane) Modifier.onGloballyPositioned { a.onControlLane(it.boundsInRoot().left) } else Modifier
    // Scrolls only if even the controls lane is shorter than the column (never overlaps other controls, D5).
    Column(modifier.then(report).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MapIconButton(R.drawable.ic_add, stringResource(R.string.control_zoom_in), a.onZoomIn)
        MapIconButton(R.drawable.ic_remove, stringResource(R.string.control_zoom_out), a.onZoomOut)
        if (Math.abs(m.bearing) > 0.5) MapIconButton(R.drawable.ic_compass_north, stringResource(R.string.control_north_up), a.onNorthUp)
        MapIconButton(R.drawable.ic_my_location, stringResource(R.string.marker_my_location), a.onMyLocation, highlighted = m.followingMe)
    }
}

@Composable
internal fun StateRow(text: String, progress: Boolean = false, retry: (() -> Unit)? = null, retryEnabled: Boolean = true) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (progress) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = NavType.body, color = t.uiOnSurface.c(), modifier = Modifier.weight(1f))
        if (retry != null) TextButton(onClick = retry, enabled = retryEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
    }
}

/**
 * S2 coordinate card «Сонгосон цэг» (AC 4): 5 decimals, «Маршрут гаргах». NAV-011 AC 8–13: the nearest-place area
 * (one `reverse` per card) sits between the coordinates and «Маршрут гаргах», which is usable at once (P6).
 */
@Composable
private fun CoordinateCard(p: LatLon, m: BrowseModel, strings: Strings, a: BrowseActions, modifier: Modifier, previewMode: Boolean = false, wide: Boolean = false) {
    val t = LocalTokens.current
    // NAV-011 C1: the camera move for the typed option waits for the card's layout and keeps the point clear of it.
    DisposableEffect(Unit) { onDispose { a.onCardBounds(null, false) } }
    // NAV-011 AC 7a (screen spec › Accessibility): after the typed option, focus moves to the title (heading) once.
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(m.cardTitleFocus) {
        if (m.cardTitleFocus) {
            runCatching { titleFocus.requestFocus() }
            a.onCardTitleFocused()
        }
    }
    Surface(
        shape = RoundedCornerShape(16.dp), color = t.uiSurface.c(), shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth().testTag("coordinate-card").onGloballyPositioned { a.onCardBounds(it.boundsInRoot(), wide) },
    ) {
        // Scrolls instead of squeezing «Маршрут гаргах» when the card gets less than its height (NAV-005-D5).
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.place_selected_point), style = NavType.title, color = t.uiOnSurface.c(),
                    modifier = Modifier.weight(1f).focusRequester(titleFocus).focusable().semantics { heading() },
                )
                IconButton(onClick = a.onCardClose, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close))
                }
            }
            Text(Formatters.coordinates(p.lat, p.lon), style = NavType.body.copy(fontFeatureSettings = "tnum"), color = t.uiOnSurface.c())
            NearestPlaceArea(p, m.reverse, strings, a)
            Spacer(Modifier.height(12.dp))
            if (previewMode) {
                // NAV-018 AC 6 (Q6): «Эхлэх цэг болгох» / «Очих газар болгох» instead of «Маршрут гаргах».
                CoordinateCardPointButtons(a.points)
            } else {
                Button(onClick = a.onCardDirections, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = t.uiPrimary.c(), contentColor = t.uiOnPrimary.c())) {
                    Icon(painterResource(R.drawable.ic_directions), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.route_get_directions), style = NavType.label)
                }
            }
        }
    }
}

@Composable
internal fun PointRow(icon: Int, text: String, a11y: String) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = a11y }, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = t.uiOnSurfaceVariant.c(), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = NavType.bodyLarge, color = t.uiOnSurface.c(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun PreviewResultRegion(s: PreviewState, lang: Lang, strings: Strings, a: BrowseActions) {
    val t = LocalTokens.current
    when (val r = s.result) {
        PreviewResult.WaitingForLocation, PreviewResult.Loading -> StateRow(stringResource(R.string.status_loading), progress = true)
        PreviewResult.Pending -> Spacer(Modifier.height(56.dp))
        is PreviewResult.Location -> LocationMessage(r.problem, a, a.onPreviewRetry, a.onPreviewClose, onMessageSurface = false)
        is PreviewResult.Route -> {
            val plan = r.route.plan // NAV-011: the selected route (AC 17)
            Column(Modifier.testTag("preview-summary")) {
                RouteNumberLine(r, strings)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Formatters.duration(plan.duration, strings), style = NavType.titleLarge, color = t.uiOnSurface.c())
                    Text(" · " + Formatters.distance(plan.distance, lang, strings), style = NavType.bodyLarge, color = t.uiOnSurfaceVariant.c())
                }
                // NAV-004 AC 25 (NAV-005 AC 6, D6): from the response time, then recomputed every 60 s from the
                // current clock while the preview stays open (0 requests). Anchored to the response time, so a
                // recomposition (rotation, returning to the screen) never shows a stale time.
                var etaFromMs by remember(r) { mutableLongStateOf(PreviewEtaClock.base(r.receivedWallMs, System.currentTimeMillis())) }
                LaunchedEffect(r) {
                    while (true) {
                        delay(PreviewEtaClock.untilNextTick(r.receivedWallMs, System.currentTimeMillis()))
                        etaFromMs = PreviewEtaClock.base(r.receivedWallMs, System.currentTimeMillis())
                    }
                }
                val eta = Formatters.eta(etaFromMs, plan.duration, java.time.ZoneId.systemDefault())
                Text(Formatters.etaText(eta, strings), style = NavType.bodyLarge, color = t.uiOnSurface.c())
                val snap = plan.snapDistances.maxOrNull() ?: 0.0 // NAV-018 AC 14: the larger of start and destination
                if (snap > 500.0) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_info), contentDescription = null, modifier = Modifier.size(20.dp), tint = t.uiOnSurfaceVariant.c())
                        Spacer(Modifier.width(8.dp))
                        Text(Templates.fill(strings[StringKey.ROUTE_SNAP_NOTICE], "distance" to Formatters.distance(snap, lang, strings)), style = NavType.body, color = t.uiOnSurfaceVariant.c())
                    }
                }
            }
        }
        PreviewResult.SamePoint -> StateRow(stringResource(R.string.route_same_point))
        is PreviewResult.NoRoute -> Column {
            StateRow(stringResource(R.string.route_no_route))
            if (r.avoidHint) Text(stringResource(R.string.route_no_route_avoid_hint), style = NavType.body, color = t.uiOnSurfaceVariant.c(), modifier = Modifier.padding(horizontal = 16.dp))
        }
        PreviewResult.OutOfArea -> StateRow(stringResource(R.string.route_out_of_area))
        PreviewResult.TooFar -> StateRow(stringResource(R.string.route_too_far))
        PreviewResult.Unavailable -> StateRow(stringResource(R.string.route_unavailable), retry = a.onPreviewRetry)
        PreviewResult.Offline -> StateRow(stringResource(R.string.status_offline))
        is PreviewResult.RateLimited -> StateRow(stringResource(R.string.route_rate_limited), retry = a.onPreviewRetry, retryEnabled = r.retryEnabled)
        PreviewResult.Error -> StateRow(stringResource(R.string.status_generic_error))
    }
}

/**
 * NAV-004 AC 25 (NAV-005 AC 6, D6): the clock the preview «Хүрэх цаг» is computed from. The response time for the
 * first 60 s, then the current clock, refreshed every [REFRESH_MS] counted from the response.
 */
object PreviewEtaClock {
    const val REFRESH_MS = 60_000L

    /** The time the ETA is computed from at [nowMs] for a response received at [receivedMs]. */
    fun base(receivedMs: Long, nowMs: Long): Long = if (nowMs - receivedMs >= REFRESH_MS) nowMs else receivedMs

    /** Milliseconds from [nowMs] to the next 60 s mark after the response (1..60 000). */
    fun untilNextTick(receivedMs: Long, nowMs: Long): Long {
        val age = (nowMs - receivedMs).coerceAtLeast(0L)
        return REFRESH_MS - age % REFRESH_MS
    }
}

/**
 * S1–S4 overlay over the map. Without a preview the map controls get their own space (NAV-005-D5):
 *  - narrow (portrait phones): the controls and the coordinate card sit at the bottom; the search results and status
 *    messages above them shrink and scroll instead of running into the controls;
 *  - wide (landscape phones, tablets: width ≥ [WIDE_MIN_WIDTH], or landscape and ≥ [WIDE_LANDSCAPE_MIN_WIDTH]):
 *    below the search row the controls keep a lane on the right edge (as in portrait), and the results, messages and
 *    card use the width left of it, so nothing stacks over the controls on a 360 dp high screen.
 */
@Composable
fun BrowseOverlay(m: BrowseModel, strings: Strings, a: BrowseActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxH = maxHeight
        val maxW = maxWidth
        val preview = m.preview
        val wide = maxWidth >= WIDE_MIN_WIDTH || (maxWidth > maxHeight && maxWidth >= WIDE_LANDSCAPE_MIN_WIDTH)
        val sheetW = (maxWidth * 0.4f).coerceIn(320.dp, 400.dp)
        when {
            // NAV-011 P5: wide windows get a side sheet at the start edge, always expanded.
            // NAV-018: the point editor (Q5) and the coordinate card (Q6) take the sheet's place (hidden, not closed).
            preview != null && wide -> Row(Modifier.fillMaxSize()) {
                val column = Modifier.width(sheetW).fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                val editor = m.points.editor
                when {
                    editor != null -> PointEditor(preview, editor, m.fieldView, m.lock, m.focusSearch, strings, a, column.statusBarsPadding().imePadding())
                    m.card != null -> Column(column, verticalArrangement = Arrangement.Bottom) {
                        CoordinateCard(m.card, m, strings, a, Modifier, previewMode = true, wide = true)
                    }
                    else -> RoutePreviewSheet(
                        preview, m, strings, a, maxH, wide = true,
                        column.onGloballyPositioned {
                            a.onSheetStart(it.size.width)
                            a.onSheetHeight(0)
                        },
                        availableWidth = maxW,
                    )
                }
                Column(Modifier.weight(1f).reportTopGroup(a)) {
                    if (editor == null) {
                        SearchRow(m, a)
                        TypingLockCard(m.lock, a, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                        SearchResults(m, strings, a, onCoordinate = a.onCoordinateOption)
                        StatusMessages(m, a)
                    }
                }
            }
            preview != null -> {
                val editor = m.points.editor
                if (editor != null) {
                    PointEditor(preview, editor, m.fieldView, m.lock, m.focusSearch, strings, a, Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().imePadding())
                } else {
                    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().reportTopGroup(a)) {
                        SearchRow(m, a)
                        TypingLockCard(m.lock, a, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                        SearchResults(m, strings, a, onCoordinate = a.onCoordinateOption)
                        StatusMessages(m, a)
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        if (m.card != null) {
                            CoordinateCard(m.card, m, strings, a, Modifier.padding(horizontal = 8.dp, vertical = 8.dp), previewMode = true)
                        } else {
                            RoutePreviewSheet(
                                preview, m, strings, a, maxH, wide = false,
                                Modifier.fillMaxWidth().onGloballyPositioned {
                                    a.onSheetHeight(it.size.height)
                                    a.onSheetStart(0)
                                },
                                availableWidth = maxW,
                            )
                        }
                    }
                }
            }
            wide -> Column(Modifier.fillMaxSize()) {
                SearchRow(m, a)
                TypingLockCard(m.lock, a, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f, fill = false).reportTopGroup(a)) {
                            SearchResults(m, strings, a, Modifier.weight(1f, fill = false), onCoordinate = a.onCoordinateOption)
                            StatusMessages(m, a, Modifier.weight(1f, fill = false))
                        }
                        // NAV-011 P8 (D140): a start-edge column of the P5 side-sheet width, for both entry points, so
                        // the typed-coordinate camera (C1) has free map beside it.
                        m.card?.let { CoordinateCard(it, m, strings, a, Modifier.width(sheetW).padding(start = 8.dp, top = 8.dp, bottom = 8.dp), wide = true) }
                    }
                    MapControls(m, a, Modifier.align(Alignment.Bottom).padding(start = 8.dp, end = 16.dp, bottom = 16.dp), lane = true)
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f, fill = false).reportTopGroup(a)) {
                    SearchRow(m, a)
                    TypingLockCard(m.lock, a, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                    SearchResults(m, strings, a, Modifier.weight(1f, fill = false), onCoordinate = a.onCoordinateOption)
                    StatusMessages(m, a, Modifier.weight(1f, fill = false))
                }
                Column(Modifier.fillMaxWidth()) {
                    MapControls(m, a, Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 16.dp))
                    m.card?.let { CoordinateCard(it, m, strings, a, Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) }
                }
            }
        }
        // NAV-018 AC 8: the markers' TalkBack descriptions (MapLibre symbols are not accessible).
        if (preview != null) MarkerDescriptions(preview, strings, Modifier.align(Alignment.TopStart))
    }
}

/** NAV-011 C1: reports the bottom edge of the top group (root px) for the typed-coordinate camera. */
private fun Modifier.reportTopGroup(a: BrowseActions): Modifier = onGloballyPositioned { a.onTopGroup(it.boundsInRoot().bottom) }

/** Window width from which the browse overlay uses the side lane for the map controls. */
private val WIDE_MIN_WIDTH = 600.dp

/** A landscape window at least this wide also uses the side lane (the left column keeps ≥ 400 dp). */
private val WIDE_LANDSCAPE_MIN_WIDTH = 480.dp

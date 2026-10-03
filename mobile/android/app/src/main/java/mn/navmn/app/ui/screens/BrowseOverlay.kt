package mn.navmn.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
private fun SearchResults(m: BrowseModel, strings: Strings, a: BrowseActions, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val v = m.searchView
    if (v is SearchView.Closed) return
    Surface(shape = RoundedCornerShape(16.dp), color = t.uiSurface.c(), shadowElevation = 2.dp, modifier = modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        val listName = stringResource(R.string.search_results)
        Column(Modifier.heightIn(max = 360.dp).semantics { contentDescription = listName }.testTag("search-results")) {
            when (v) {
                SearchView.Loading -> StateRow(stringResource(R.string.status_loading), progress = true)
                SearchView.NoResults -> StateRow(stringResource(R.string.search_no_results))
                SearchView.Unavailable -> StateRow(stringResource(R.string.search_unavailable), retry = a.onRetrySearch)
                SearchView.Error -> StateRow(stringResource(R.string.status_generic_error))
                SearchView.Offline -> StateRow(stringResource(R.string.status_offline))
                is SearchView.RateLimited -> StateRow(stringResource(R.string.search_rate_limited), retry = a.onRetrySearch, retryEnabled = v.retryEnabled)
                is SearchView.Results -> LazyColumn {
                    items(v.items) { info ->
                        val name = info.name ?: strings[info.type]
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { a.onResult(info, name) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(name, style = NavType.bodyLarge, color = t.uiOnSurface.c(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val second = listOfNotNull(strings[info.type].takeIf { info.name != null }, info.context).joinToString(" · ")
                            if (second.isNotEmpty()) Text(second, style = NavType.body, color = t.uiOnSurfaceVariant.c(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider(color = t.uiOutlineVariant.c())
                    }
                }
                SearchView.Closed -> Unit
            }
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
private fun MapControls(m: BrowseModel, a: BrowseActions, modifier: Modifier = Modifier) {
    // Scrolls only if even the controls lane is shorter than the column (never overlaps other controls, D5).
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun CoordinateCard(p: LatLon, m: BrowseModel, strings: Strings, a: BrowseActions, modifier: Modifier) {
    val t = LocalTokens.current
    Surface(shape = RoundedCornerShape(16.dp), color = t.uiSurface.c(), shadowElevation = 2.dp, modifier = modifier.fillMaxWidth().testTag("coordinate-card")) {
        // Scrolls instead of squeezing «Маршрут гаргах» when the card gets less than its height (NAV-005-D5).
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.place_selected_point), style = NavType.title, color = t.uiOnSurface.c(), modifier = Modifier.weight(1f))
                IconButton(onClick = a.onCardClose, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close))
                }
            }
            Text(Formatters.coordinates(p.lat, p.lon), style = NavType.body.copy(fontFeatureSettings = "tnum"), color = t.uiOnSurface.c())
            NearestPlaceArea(p, m.reverse, strings, a)
            Spacer(Modifier.height(12.dp))
            Button(onClick = a.onCardDirections, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = t.uiPrimary.c(), contentColor = t.uiOnPrimary.c())) {
                Icon(painterResource(R.drawable.ic_directions), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.route_get_directions), style = NavType.label)
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
                val snap = plan.snapDistances.lastOrNull() ?: 0.0
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
        val preview = m.preview
        val wide = maxWidth >= WIDE_MIN_WIDTH || (maxWidth > maxHeight && maxWidth >= WIDE_LANDSCAPE_MIN_WIDTH)
        val sheetW = (maxWidth * 0.4f).coerceIn(320.dp, 400.dp)
        when {
            // NAV-011 P5: wide windows get a side sheet at the start edge, always expanded.
            preview != null && wide -> Row(Modifier.fillMaxSize()) {
                RoutePreviewSheet(
                    preview, m, strings, a, maxH, wide = true,
                    Modifier.width(sheetW).fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp).onGloballyPositioned {
                        a.onSheetStart(it.size.width)
                        a.onSheetHeight(0)
                    },
                )
                Column(Modifier.weight(1f)) {
                    SearchRow(m, a)
                    TypingLockCard(m.lock, a, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                    SearchResults(m, strings, a)
                    StatusMessages(m, a)
                }
            }
            preview != null -> {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                    SearchRow(m, a)
                    TypingLockCard(m.lock, a, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                    SearchResults(m, strings, a)
                    StatusMessages(m, a)
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    RoutePreviewSheet(
                        preview, m, strings, a, maxH, wide = false,
                        Modifier.fillMaxWidth().onGloballyPositioned {
                            a.onSheetHeight(it.size.height)
                            a.onSheetStart(0)
                        },
                    )
                }
            }
            wide -> Column(Modifier.fillMaxSize()) {
                SearchRow(m, a)
                TypingLockCard(m.lock, a, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f, fill = false)) {
                            SearchResults(m, strings, a, Modifier.weight(1f, fill = false))
                            StatusMessages(m, a, Modifier.weight(1f, fill = false))
                        }
                        m.card?.let { CoordinateCard(it, m, strings, a, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) }
                    }
                    MapControls(m, a, Modifier.align(Alignment.Bottom).padding(start = 8.dp, end = 16.dp, bottom = 16.dp))
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f, fill = false)) {
                    SearchRow(m, a)
                    TypingLockCard(m.lock, a, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                    SearchResults(m, strings, a, Modifier.weight(1f, fill = false))
                    StatusMessages(m, a, Modifier.weight(1f, fill = false))
                }
                Column(Modifier.fillMaxWidth()) {
                    MapControls(m, a, Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 16.dp))
                    m.card?.let { CoordinateCard(it, m, strings, a, Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) }
                }
            }
        }
    }
}

/** Window width from which the browse overlay uses the side lane for the map controls. */
private val WIDE_MIN_WIDTH = 600.dp

/** A landscape window at least this wide also uses the side lane (the left column keeps ≥ 400 dp). */
private val WIDE_LANDSCAPE_MIN_WIDTH = 480.dp

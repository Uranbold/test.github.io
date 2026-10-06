package mn.navmn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mn.navmn.app.R
import mn.navmn.app.background.battery.BatteryPreviewEntry
import mn.navmn.app.background.battery.BatteryPreviewHint
import mn.navmn.app.format.Formatters
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Strings
import mn.navmn.app.i18n.Templates
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.PreviewState
import mn.navmn.app.preview.turnlist.TurnListModel
import mn.navmn.app.route.TravelMode
import mn.navmn.app.search.PlaceDisplay
import mn.navmn.app.search.reverse.ReverseView
import mn.navmn.app.typinglock.TypingLockState
import mn.navmn.app.ui.components.OfflineIndicator
import mn.navmn.app.ui.screens.preview.PointsBlock
import mn.navmn.app.ui.screens.preview.StartHint
import mn.navmn.app.ui.screens.preview.turnListItem
import mn.navmn.app.ui.theme.LocalNight
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c

// NAV-011 UI (screen spec docs/design/screens/NAV-011-android-route-preview-search-parity.md): the draggable route
// preview sheet with alternatives and «Дугуй», the typing-lock card and the nearest-place area of the coordinate card.

/** QA hook: `collapsed` | `expanded` on the preview sheet (screen spec › Components, test tag `route-preview`). */
val SheetStateKey = SemanticsPropertyKey<String>("sheetState")
var SemanticsPropertyReceiver.sheetState by SheetStateKey

/** QA hook: the nearest-place state on the coordinate card (`pending` … `error`, screen spec › States). */
val ReverseStateKey = SemanticsPropertyKey<String>("reverseState")
var SemanticsPropertyReceiver.reverseState by ReverseStateKey

/** Live regions announce once (per card / per engagement), not on every recomposition or language switch. */
private const val ANNOUNCE_HOLD_MS = 1_500L

// ------------------------------------------------------------------------------------------------ S3 preview sheet

/**
 * S3 route preview (Layout rules P1–P5): portrait → a two-state draggable bottom sheet with «Эхлэх» pinned at the
 * bottom (collapsed up to the NAV-018 Q3 cap with the top part giving way first, expanded ≤ 80 %); wide windows → a side
 * sheet at the start edge, always expanded. The sheet state lives in the ViewModel (kept across rotation, theme and
 * language). NAV-018: the points block replaces the header row and «Маршрутын заавар» ends the expanded body.
 */
@Composable
internal fun RoutePreviewSheet(
    s: PreviewState,
    m: BrowseModel,
    strings: Strings,
    a: BrowseActions,
    availableHeight: Dp,
    wide: Boolean,
    modifier: Modifier = Modifier,
    /** NAV-018 Q3: width of the overlay (the 75 % cap applies from 360 dp wide). */
    availableWidth: Dp = 360.dp,
) {
    val t = LocalTokens.current
    val night = LocalNight.current
    val title = stringResource(R.string.route_title)
    val expanded = wide || m.sheetExpanded
    // P4: «Маршрут олдсонгүй» with the avoid hint expands the sheet once so the switch it names is visible.
    val result = s.result
    LaunchedEffect(result) {
        if (result is PreviewResult.NoRoute && result.avoidHint && !m.sheetExpanded) a.onSheetExpanded(true)
    }
    val shape = if (wide) RoundedCornerShape(16.dp) else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    // NAV-018: one list state for the expanded body, kept while the sheet collapses and expands (Q8) and on rotation.
    val listState = rememberLazyListState()
    // NAV-018 AC 23: the rows are their own lazy list (the «Маршрутын заавар» collection), with their own state.
    val turnState = rememberLazyListState()
    val route = (result as? PreviewResult.Route)?.route
    LaunchedEffect(route) {
        // AC 21: another route or a new response shows its list from the first row (heading at the top).
        if (turnState.firstVisibleItemIndex > 0 || turnState.firstVisibleItemScrollOffset > 0) {
            if (listState.firstVisibleItemIndex >= 1) listState.scrollToItem(1)
            turnState.scrollToItem(0)
        }
    }
    Surface(
        shape = shape,
        color = t.uiSurface.c(),
        shadowElevation = if (night) 0.dp else 2.dp,
        modifier = modifier
            .then(if (night) Modifier.border(1.dp, t.uiOutlineVariant.c(), shape) else Modifier)
            .testTag("route-preview")
            .semantics {
                paneTitle = title
                sheetState = if (expanded) "expanded" else "collapsed"
            },
    ) {
        if (wide) {
            Column(Modifier.fillMaxHeight()) {
                ExpandedBody(s, m, strings, a, listState, turnState, Modifier.weight(1f))
                StartFooter(s, a, divider = true)
            }
        } else if (expanded) {
            Column(Modifier.heightIn(max = availableHeight * 0.8f)) {
                HandleZone(expanded = true, a)
                ExpandedBody(s, m, strings, a, listState, turnState, Modifier.weight(1f, fill = false))
                StartFooter(s, a, divider = true)
            }
        } else {
            CollapsedLayout(
                cap = collapsedCap(availableHeight, availableWidth),
                hardCap = availableHeight * 0.8f,
                joinBattery = LocalDensity.current.fontScale > 1f,
                handle = { HandleZone(expanded = false, a) },
                top = { joined ->
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        PointsBlock(s, strings, a.points, a.onPreviewClose) // NAV-018 Q4: replaces the NAV-011 header row
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            ModeTabs(s.mode, a)
                            // NAV-018 Q3: above 100 % the entry row may join the top part (after the points and the tabs).
                            if (joined) BatteryPreviewEntry(Modifier.padding(vertical = 8.dp))
                        }
                    }
                },
                summary = {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(8.dp))
                        SummaryRegion(s, m.lang, strings, a)
                    }
                },
                battery = {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        BatteryPreviewEntry(Modifier.padding(vertical = 8.dp)) // NAV-012 H1 entry row (Layout rule 10)
                    }
                },
                footer = { StartFooter(s, a, divider = false) },
            )
        }
    }
}

/**
 * NAV-018 Q3 collapsed height cap (replaces NAV-011 P3's 60 %): on ≥ 360×640 windows min(75 % of the area above R5,
 * area − band − 8 dp), band = 160 dp at font scale 100 % and 96 dp above (AC 27); smaller windows keep 60 %. The area
 * above R5 is 544 dp at 360×640 with system bars, so ≥ 500 dp marks that class.
 */
@Composable
private fun collapsedCap(availableHeight: Dp, availableWidth: Dp): Dp {
    val band = if (LocalDensity.current.fontScale <= 1f) 160.dp else 96.dp
    val large = availableWidth >= 360.dp && availableHeight >= 500.dp
    return if (large) minOf(availableHeight * 0.75f, availableHeight - band - 8.dp) else availableHeight * 0.6f
}

/**
 * P2/Q1–Q3 collapsed sheet: handle, summary, battery entry row and footer are measured first and never scroll; the top
 * part (points block and tabs) gets what is left of [cap] and scrolls inside (it may disappear entirely at large font
 * scales). When [joinBattery] (font scale > 100 %) and the fixed parts alone exceed [cap], the battery entry row joins
 * the top part instead (NAV-018 Q3). The total never exceeds max([cap], fixed parts), and never [hardCap].
 */
@Composable
private fun CollapsedLayout(
    cap: Dp,
    hardCap: Dp,
    joinBattery: Boolean,
    handle: @Composable () -> Unit,
    top: @Composable (joined: Boolean) -> Unit,
    summary: @Composable () -> Unit,
    battery: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val capPx = with(density) { cap.roundToPx() }
    val hardCapPx = with(density) { hardCap.roundToPx() }
    SubcomposeLayout(Modifier.clip(RectangleShape)) { constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val handleP = subcompose("handle", handle).map { it.measure(loose) }
        val summaryP = subcompose("summary", summary).map { it.measure(loose) }
        val footerP = subcompose("footer", footer).map { it.measure(loose) }
        val base = handleP.sumOf { it.height } + summaryP.sumOf { it.height } + footerP.sumOf { it.height }
        val batteryP = subcompose("battery", battery).map { it.measure(loose) }
        val joined = joinBattery && batteryP.isNotEmpty() && base + batteryP.sumOf { it.height } > capPx
        val fixedP = if (joined) emptyList() else batteryP
        val fixed = base + fixedP.sumOf { it.height }
        val topMax = (capPx - fixed).coerceAtLeast(0)
        val topP = subcompose("top") { top(joined) }.map { it.measure(constraints.copy(minHeight = 0, maxHeight = topMax)) }
        val total = minOf(fixed + topP.sumOf { it.height }, maxOf(capPx, minOf(fixed, hardCapPx)))
        layout(constraints.maxWidth, total) {
            var y = 0
            for (p in handleP + topP + summaryP + fixedP + footerP) {
                p.placeRelative(0, y)
                y += p.height
            }
        }
    }
}

/**
 * Handle zone with a 32 × 4 dp handle: drag/fling between the two states, tap toggles (P4). The zone is 48 dp high
 * (not the spec's 24 dp) because it is a tap target: AC 44 needs ≥ 48 × 48 dp for every control.
 */
@Composable
private fun HandleZone(expanded: Boolean, a: BrowseActions) {
    var drag by remember { mutableFloatStateOf(0f) }
    val state = rememberDraggableState { delta -> drag += delta }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .draggable(
                state = state,
                orientation = Orientation.Vertical,
                onDragStarted = { drag = 0f },
                onDragStopped = { velocity ->
                    val up = drag < -24f || velocity < -300f
                    val down = drag > 24f || velocity > 300f
                    if (up && !expanded) a.onSheetExpanded(true)
                    if (down && expanded) a.onSheetExpanded(false)
                },
            )
            .clickable(onClickLabel = null, role = Role.Button) { a.onSheetExpanded(!expanded) }
            .semantics {
                // Standard expand/collapse actions: TalkBack supplies the localised labels (no app string).
                if (expanded) collapse { a.onSheetExpanded(false); true } else expand { a.onSheetExpanded(true); true }
            }
            .testTag("preview-sheet-handle"),
        contentAlignment = Alignment.Center,
    ) {
        // 32 × 4 dp handle in ui.outline (M3 standard bottom sheet look, without the library's own description).
        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(LocalTokens.current.uiOutline.c()))
    }
}

/**
 * NAV-018 Q1: the expanded body (and the wide side sheet) is one lazy list: the points block, tabs, summary region and
 * lower part as the first item, then «Маршрутын заавар» (heading and one lazy row per step, AC 18, 25). The rows sit in
 * their own lazy list inside that last item, so TalkBack gets one collection node named «Маршрутын заавар» (AC 23).
 */
@Composable
private fun ExpandedBody(s: PreviewState, m: BrowseModel, strings: Strings, a: BrowseActions, listState: LazyListState, turnState: LazyListState, modifier: Modifier) {
    val r = s.result as? PreviewResult.Route
    val rows = remember(r?.route) { r?.let { TurnListModel.build(it.route.plan) }.orEmpty() }
    val active = m.points.step?.takeIf { r != null && it.route === r.route }?.index
    BoxWithConstraints(modifier) {
        // The turn-list section is at most as tall as the body, so its rows can be a lazy list of their own (AC 23, 25).
        val viewport = if (constraints.hasBoundedHeight) maxHeight else TURN_LIST_FALLBACK_HEIGHT
        LazyColumn(Modifier.fillMaxWidth().testTag("route-preview-body"), state = listState) {
            item(key = "preview-top") {
                Column {
                    PointsBlock(s, strings, a.points, a.onPreviewClose) // NAV-018 Q4: replaces the NAV-011 header row
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        ModeTabs(s.mode, a)
                        Spacer(Modifier.height(8.dp))
                        SummaryRegion(s, m.lang, strings, a)
                        LowerPart(s, strings, m.lang, a)
                    }
                }
            }
            if (r != null) turnListItem(rows, r.selected, active, m.lang, strings, a.points.onTurnRow, turnState, listState, viewport)
        }
    }
}

/** Turn-list section height when the body is measured without a height bound (not the case in the app's layouts). */
private val TURN_LIST_FALLBACK_HEIGHT = 480.dp

/** «Зорчих хэлбэр»: «Машин» / «Явган» / «Дугуй»; icon beside the label, stacked from font scale 1.5 (AC 22, 44). */
@Composable
private fun ModeTabs(mode: TravelMode, a: BrowseActions) {
    val t = LocalTokens.current
    val stacked = LocalDensity.current.fontScale >= 1.5f
    val modes = stringResource(R.string.route_modes)
    val tabs = listOf(
        Triple(TravelMode.CAR, R.string.route_mode_car, R.drawable.ic_car) to "mode-car",
        Triple(TravelMode.WALK, R.string.route_mode_walk, R.drawable.ic_walk) to "mode-walk",
        Triple(TravelMode.BICYCLE, R.string.route_mode_bike, R.drawable.ic_bike) to "mode-bike",
    )
    PrimaryTabRow(
        selectedTabIndex = tabs.indexOfFirst { it.first.first == mode }.coerceAtLeast(0),
        containerColor = t.uiSurface.c(),
        contentColor = t.uiPrimary.c(),
        modifier = Modifier.semantics { contentDescription = modes },
    ) {
        for ((spec, tag) in tabs) {
            val (m, label, icon) = spec
            val text: @Composable () -> Unit = { Text(stringResource(label), style = NavType.label, maxLines = 1, softWrap = false) }
            val iconC: @Composable () -> Unit = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp)) }
            val selected = m == mode
            val colour = if (selected) t.uiPrimary.c() else t.uiOnSurfaceVariant.c()
            if (stacked) {
                Tab(selected = selected, onClick = { a.onMode(m) }, text = text, icon = iconC, selectedContentColor = t.uiPrimary.c(), unselectedContentColor = colour, modifier = Modifier.heightIn(min = 64.dp).testTag(tag))
            } else {
                LeadingIconTab(selected = selected, onClick = { a.onMode(m) }, text = text, icon = iconC, selectedContentColor = t.uiPrimary.c(), unselectedContentColor = colour, modifier = Modifier.heightIn(min = 48.dp).testTag(tag))
            }
        }
    }
}

/** The summary region (one of: summary, state row, location message). The «Маршрут {n}» line names the selection. */
@Composable
private fun SummaryRegion(s: PreviewState, lang: Lang, strings: Strings, a: BrowseActions) {
    Box(Modifier.testTag("preview-result")) { PreviewResultRegion(s, lang, strings, a) }
    Spacer(Modifier.height(8.dp))
}

/**
 * Expanded only: «Маршрут сонгох» (k ≥ 2) and the avoid switch («Машин» only, AC 23). NAV-018 Q1: the display-only
 * points block is removed (the fields replace it); «Маршрутын заавар» follows as lazy items.
 */
@Composable
private fun LowerPart(s: PreviewState, strings: Strings, lang: Lang, a: BrowseActions) {
    val t = LocalTokens.current
    BatteryPreviewHint(Modifier.padding(vertical = 8.dp)) // NAV-012 H1: the full hint is the first item (Layout rule 10)
    val r = s.result as? PreviewResult.Route
    if (r != null && r.k >= 2) RouteOptions(r, strings, lang, a)
    if (s.mode == TravelMode.CAR) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.route_avoid_unpaved), style = NavType.bodyLarge, color = t.uiOnSurface.c(), modifier = Modifier.weight(1f))
            Switch(checked = s.avoidUnpaved, onCheckedChange = a.onAvoid)
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** «Маршрут сонгох» radio group (AC 18): one ≥ 56 dp row per route, the selection exposed to TalkBack. */
@Composable
private fun RouteOptions(r: PreviewResult.Route, strings: Strings, lang: Lang, a: BrowseActions) {
    val t = LocalTokens.current
    val groupName = stringResource(R.string.route_options)
    val alternative = stringResource(R.string.route_alternative)
    if (r.route.onDevice) {
        // NAV-021 AC 27 (screen spec F8): once after the group heading when every option came from the device.
        FlowRow(
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics(mergeDescendants = true) { heading() },
        ) {
            Text(groupName, style = NavType.label, color = t.uiOnSurfaceVariant.c())
            OfflineIndicator(strings, Modifier.padding(start = 8.dp))
        }
    } else {
        Text(groupName, style = NavType.label, color = t.uiOnSurfaceVariant.c(), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() })
    }
    Column(Modifier.selectableGroup().semantics { contentDescription = groupName }.testTag("route-options")) {
        r.routes.forEachIndexed { i, route ->
            val selected = i == r.selected
            val label = Templates.fill(strings[StringKey.ROUTE_OPTION], "n" to (i + 1).toString())
            val meta = Formatters.duration(route.plan.duration, strings) + " · " + Formatters.distance(route.plan.distance, lang, strings)
            val a11y = label + ", " + meta.replace(" · ", ", ") + if (selected) "" else ", $alternative"
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { a.onSelectRoute(i) })
                    .semantics(mergeDescendants = true) { contentDescription = a11y }
                    .padding(vertical = 4.dp)
                    .testTag("route-option"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = t.uiPrimary.c()))
                Box(
                    Modifier
                        .size(width = 24.dp, height = 6.dp)
                        .border(1.dp, (if (selected) t.routeSelectedCasing else t.routeAlternativeCasing).c(), RoundedCornerShape(3.dp))
                        .padding(1.dp)
                        .clip(RoundedCornerShape(3.dp)),
                ) {
                    Surface(color = (if (selected) t.routeSelected else t.routeAlternative).c(), modifier = Modifier.fillMaxWidth().height(4.dp)) {}
                }
                Column(Modifier.weight(1f)) {
                    Text(label, style = NavType.bodyLarge, color = t.uiOnSurface.c())
                    Text(meta, style = NavType.body.copy(fontFeatureSettings = "tnum"), color = t.uiOnSurfaceVariant.c())
                }
            }
        }
    }
}

/**
 * «Эхлэх», pinned at the bottom in both states (P1); starts the selected route (AC 19). NAV-018 Q7: with a chosen start
 * O1 sits one line above the disabled button; TalkBack reads «Эхлэх» first, then O1 (one traversal group).
 */
@Composable
private fun StartFooter(s: PreviewState, a: BrowseActions, divider: Boolean) {
    val t = LocalTokens.current
    Column(Modifier.semantics { isTraversalGroup = true }) {
        if (divider) HorizontalDivider(color = t.uiOutlineVariant.c())
        if (s.showStartHint) StartHint(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).semantics { traversalIndex = 1f })
        Button(
            onClick = a.onStart,
            enabled = s.canStart,
            colors = ButtonDefaults.buttonColors(containerColor = t.uiPrimary.c(), contentColor = t.uiOnPrimary.c()),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp).heightIn(min = 56.dp).semantics { traversalIndex = 0f }.testTag("nav-start"),
        ) {
            Icon(painterResource(R.drawable.ic_play), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.nav_start), style = NavType.label)
        }
    }
}

/** The «Маршрут {n}» first line of the summary when k ≥ 2 (screen spec › Summary). */
@Composable
internal fun RouteNumberLine(r: PreviewResult.Route, strings: Strings) {
    if (r.k < 2) return
    val t = LocalTokens.current
    Text(Templates.fill(strings[StringKey.ROUTE_OPTION], "n" to (r.selected + 1).toString()), style = NavType.label, color = t.uiPrimary.c())
}

// ------------------------------------------------------------------------------------------------ S1 typing lock

/**
 * Lock card (AC 31, screen spec › Lock card): K1, K3, «Хаах» and the filled tonal «Би зорчигч», 8 dp apart. K1 is a
 * polite live region, announced once per engagement.
 */
@Composable
internal fun TypingLockCard(lock: TypingLockState, a: BrowseActions, modifier: Modifier = Modifier) {
    if (!lock.locked || !lock.cardVisible) return
    val t = LocalTokens.current
    var announced by rememberSaveable { mutableIntStateOf(0) }
    val live = announced != lock.engagement
    LaunchedEffect(lock.engagement) {
        delay(ANNOUNCE_HOLD_MS)
        announced = lock.engagement
    }
    Surface(
        color = t.uiMessageSurface.c(),
        contentColor = t.uiOnMessageSurface.c(),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 2.dp,
        modifier = modifier.border(1.dp, t.uiMessageOutline.c(), RoundedCornerShape(12.dp)).testTag("typing-lock"),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.typing_lock_title),
                        style = NavType.bodyLarge,
                        color = t.uiOnMessageSurface.c(),
                        modifier = if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                    )
                    Text(stringResource(R.string.typing_lock_hint), style = NavType.body, color = t.uiOnMessageSurface.c().copy(alpha = 0.87f))
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = a.onDismissLock, modifier = Modifier.heightIn(min = 48.dp).testTag("typing-lock-close")) {
                    Text(stringResource(R.string.action_close), style = NavType.label, color = t.uiMessageAction.c())
                }
                FilledTonalButton(
                    onClick = a.onPassenger,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = t.uiPrimaryContainer.c(), contentColor = t.uiOnPrimaryContainer.c()),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("typing-lock-passenger"),
                ) {
                    Text(stringResource(R.string.typing_lock_passenger), style = NavType.label)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ S2 nearest place

/**
 * Nearest-place area of the coordinate card (AC 8–13, P6): between the coordinates and «Маршрут гаргах», with
 * `64 dp × font scale` reserved so the common states do not change the card's height. Never the destination text.
 */
@Composable
internal fun NearestPlaceArea(card: LatLon, v: ReverseView?, strings: Strings, a: BrowseActions) {
    val t = LocalTokens.current
    val density = LocalDensity.current
    val state = v ?: ReverseView.Pending
    var announcedKey by remember { mutableStateOf<Any?>(null) }
    val key = card to state.id
    val live = announcedKey != key && state != ReverseView.Pending
    LaunchedEffect(key) {
        delay(ANNOUNCE_HOLD_MS)
        announcedKey = key
    }
    HorizontalDivider(color = t.uiOutlineVariant.c(), modifier = Modifier.padding(vertical = 8.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = (64 * density.fontScale).dp)
            .testTag("coord-nearest")
            .semantics(mergeDescendants = true) {
                reverseState = state.id
                if (live) liveRegion = LiveRegionMode.Polite
            },
    ) {
        when (state) {
            ReverseView.Pending -> Unit
            ReverseView.Loading -> Text(stringResource(R.string.status_loading), style = NavType.body, color = t.uiOnSurfaceVariant.c())
            is ReverseView.Place -> {
                val info = PlaceDisplay.info(state.feature)
                NearestLabel(state.onDevice, strings)
                Text(info.name ?: strings[info.type], style = NavType.bodyLarge, color = t.uiOnSurface.c(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val second = listOfNotNull(strings[info.type].takeIf { info.name != null }, info.context).joinToString(" · ")
                if (second.isNotEmpty()) Text(second, style = NavType.body, color = t.uiOnSurfaceVariant.c())
            }
            ReverseView.Empty, ReverseView.EmptyOnDevice -> {
                NearestLabel(state == ReverseView.EmptyOnDevice, strings)
                Text(stringResource(R.string.search_no_results), style = NavType.body, color = t.uiOnSurface.c())
            }
            ReverseView.Offline -> NearestStateRow(R.drawable.ic_cloud_off, stringResource(R.string.status_offline), null)
            ReverseView.Unavailable -> NearestStateRow(R.drawable.ic_info, stringResource(R.string.search_unavailable), a.onReverseRetry to true)
            is ReverseView.RateLimited -> NearestStateRow(R.drawable.ic_info, stringResource(R.string.search_rate_limited), a.onReverseRetry to state.retryEnabled)
            ReverseView.Error -> NearestStateRow(R.drawable.ic_info, stringResource(R.string.status_generic_error), null)
        }
    }
}

/**
 * «Ойролцоох газар»; for an answer from the device (NAV-023 AC 23, D208; screen spec O7 `ind-card`) the OF24 chip
 * follows on the same line and wraps as a whole. Its name OF25 is read after the label (the area is one merged node).
 */
@Composable
private fun NearestLabel(onDevice: Boolean, strings: Strings) {
    val t = LocalTokens.current
    if (!onDevice) {
        Text(stringResource(R.string.place_nearest), style = NavType.caption, color = t.uiOnSurfaceVariant.c())
        return
    }
    FlowRow(verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.place_nearest), style = NavType.caption, color = t.uiOnSurfaceVariant.c())
        OfflineIndicator(strings, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun NearestStateRow(icon: Int, text: String, retry: Pair<() -> Unit, Boolean>?) {
    val t = LocalTokens.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = t.uiOnSurfaceVariant.c(), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = NavType.body, color = t.uiOnSurface.c(), modifier = Modifier.weight(1f))
        }
        if (retry != null) {
            TextButton(onClick = retry.first, enabled = retry.second, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("coord-nearest-retry")) {
                Text(stringResource(R.string.action_retry), style = NavType.label)
            }
        }
    }
}

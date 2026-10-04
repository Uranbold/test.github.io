package mn.navmn.app.demo

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mn.navmn.app.R
import mn.navmn.app.demo.replay.DemoEntry
import mn.navmn.app.format.Formatters
import mn.navmn.app.route.TravelMode
import mn.navmn.app.ui.components.CappedFontScale
import mn.navmn.app.ui.components.MessageCard
import mn.navmn.app.ui.theme.LocalNight
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c
import mn.navmn.app.variant.ReplayHost
import mn.navmn.app.variant.TilesState

/** QA hook: `entry` = r1 | r2 | r3 on a picker entry (UX spec › Entry row). */
val DemoEntryId = SemanticsPropertyKey<String>("entry")
var SemanticsPropertyReceiver.demoEntryId by DemoEntryId

/** QA hook: `paused` on the pause / resume button (UX spec › Pause / resume button). */
val DemoPaused = SemanticsPropertyKey<Boolean>("paused")
var SemanticsPropertyReceiver.demoPaused by DemoPaused

/** UX P1 PM loading pill shows only when the tile copy takes longer than this (NAV-002 rule). */
private const val LOADING_PILL_DELAY_MS = 300L

/** Place names are map data in Mongolian Cyrillic in both UI languages (D11). */
private val NAME_LOCALE = LocaleList("mn")

/**
 * UX Badge (P2 pill, RD in P3/P4): the NAV-017 badge in dp. Not interactive; plain text for TalkBack; text wraps inside
 * the pill (≤ 2 lines), never truncated.
 */
@Composable
fun DemoBadge(modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Surface(
        color = t.demoBadge.c(),
        contentColor = t.demoOnBadge.c(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, t.demoBadgeOutline.c()),
        shadowElevation = 1.dp,
        modifier = modifier.heightIn(min = 32.dp).testTag("demo-badge"),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_flask), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.demo_mode), style = NavType.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
        }
    }
}

/**
 * UX RD demo row (P3, P4): badge at the start, «Түр зогсоох» (filled tonal) / «Үргэлжлүүлэх» (filled) at the end; one
 * row, the badge gives way (rule P7), the button label is never truncated. Badge only on arrival. Overlay font cap 1.3.
 */
@Composable
fun DemoRow(arrived: Boolean, paused: Boolean, onPause: () -> Unit, onResume: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    CappedFontScale(1.3f) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { DemoBadge() }
            if (!arrived) {
                Spacer(Modifier.width(8.dp))
                val label = stringResource(if (paused) R.string.action_continue else R.string.demo_pause)
                val content: @Composable () -> Unit = {
                    Icon(painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = NavType.label, softWrap = false)
                }
                val m = Modifier.heightIn(min = 48.dp).testTag("demo-pause").semantics { demoPaused = paused }
                if (paused) {
                    Button(
                        onClick = onResume,
                        colors = ButtonDefaults.buttonColors(containerColor = t.uiPrimary.c(), contentColor = t.uiOnPrimary.c()),
                        shape = CircleShape,
                        modifier = m,
                    ) { content() }
                } else {
                    FilledTonalButton(
                        onClick = onPause,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = t.uiPrimaryContainer.c(), contentColor = t.uiOnPrimaryContainer.c()),
                        shape = CircleShape,
                        modifier = m,
                    ) { content() }
                }
            }
        }
    }
}

/**
 * UX P1 route picker (the demo build's home): map backdrop with the NAV-002 tile message (PM) at the top and the picker
 * sheet at the bottom (portrait) or the start edge (landscape). Tap an entry → the normal preview (AC 6–9).
 */
@Composable
fun DemoPickerScreen(
    host: ReplayHost,
    list: PickerState,
    failedId: String?,
    openingId: String?,
    tiles: TilesState,
    onSelect: (DemoEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val tilesFailed = tiles is TilesState.Failed || host.mapFailed
        val copying = tiles is TilesState.Loading
        var pmPx by remember { mutableIntStateOf(0) }
        val message: @Composable (Modifier) -> Unit = { m ->
            when {
                tilesFailed -> MessageCard(
                    R.drawable.ic_warning,
                    stringResource(R.string.status_tiles_unavailable),
                    null,
                    listOf(stringResource(R.string.action_retry) to host.retryTiles),
                    m.testTag("demo-tiles-failed"),
                )
                copying -> LoadingPill(m)
            }
        }
        if (!landscape) {
            message(
                Modifier.align(Alignment.TopCenter).onGloballyPositioned { pmPx = it.size.height }
                    .statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            )
            val pm = if (tilesFailed || copying) with(density) { pmPx.toDp() } else 0.dp
            // UX rule P2: content height, at most min(75 % of the area, area − PM − 8 dp − 48 dp of visible map).
            val cap = minOf(maxHeight * 0.75f, maxHeight - pm - 56.dp).coerceAtLeast(160.dp)
            PickerSheet(
                host, list, failedId, openingId, onSelect, wholeScroll = false,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).heightIn(max = cap),
            )
        } else {
            val sheetW = (maxWidth * 0.4f).coerceIn(320.dp, 400.dp)
            Row(Modifier.fillMaxSize()) {
                PickerSheet(
                    host, list, failedId, openingId, onSelect, wholeScroll = true,
                    modifier = Modifier.width(sheetW).fillMaxHeight().statusBarsPadding().padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    message(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun LoadingPill(modifier: Modifier) {
    val t = LocalTokens.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_PILL_DELAY_MS)
        visible = true
    }
    if (!visible) return
    Surface(
        color = t.uiSurface.c(),
        contentColor = t.uiOnSurface.c(),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 2.dp,
        modifier = modifier.testTag("demo-tiles-loading").semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.status_loading), style = NavType.body)
        }
    }
}

@Composable
private fun PickerSheet(
    host: ReplayHost,
    list: PickerState,
    failedId: String?,
    openingId: String?,
    onSelect: (DemoEntry) -> Unit,
    wholeScroll: Boolean,
    modifier: Modifier,
) {
    val t = LocalTokens.current
    val night = LocalNight.current
    val heading = stringResource(R.string.demo_mode)
    // UX rule P3: at large font scales the pinned part would leave no room for an entry, so the whole sheet scrolls.
    val scrollAll = wholeScroll || LocalDensity.current.fontScale >= 1.5f
    Surface(
        color = t.uiSurface.c(),
        contentColor = t.uiOnSurface.c(),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 2.dp,
        border = if (night) BorderStroke(1.dp, t.uiOutlineVariant.c()) else null,
        modifier = modifier.testTag("demo-picker").semantics { paneTitle = heading },
    ) {
        Column(if (scrollAll) Modifier.verticalScroll(rememberScrollState()) else Modifier) {
            Row(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    heading,
                    style = NavType.title,
                    color = t.uiOnSurface.c(),
                    modifier = Modifier.weight(1f).testTag("demo-heading").semantics { heading() },
                )
                IconButton(onClick = host.openSettings, modifier = Modifier.size(48.dp).testTag("demo-settings")) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings_title))
                }
            }
            Text(
                stringResource(R.string.route_options),
                style = NavType.label,
                color = t.uiOnSurfaceVariant.c(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            when (list) {
                PickerState.Loading -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.status_loading), style = NavType.body)
                }
                PickerState.Failed -> ErrorRow(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp))
                is PickerState.Ready -> Column(
                    if (scrollAll) Modifier else Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                ) {
                    for (entry in list.entries) {
                        HorizontalDivider(color = t.uiOutlineVariant.c())
                        EntryRow(host, entry, enabled = openingId == null, onSelect = onSelect)
                        if (failedId == entry.id) ErrorRow(Modifier.padding(start = 44.dp, end = 16.dp, top = 8.dp, bottom = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(host: ReplayHost, entry: DemoEntry, enabled: Boolean, onSelect: (DemoEntry) -> Unit) {
    val t = LocalTokens.current
    val selected = stringResource(R.string.place_selected_point)
    val origin = entry.originName ?: selected
    val destination = entry.destinationName ?: selected
    val mode = stringResource(
        when (entry.mode) {
            TravelMode.CAR -> R.string.route_mode_car
            TravelMode.WALK -> R.string.route_mode_walk
            TravelMode.BICYCLE -> R.string.route_mode_bike
        },
    )
    val distance = entry.distanceM?.let { Formatters.distance(it, host.lang, host.strings) }
    val duration = entry.durationS?.let { Formatters.duration(it, host.strings) }
    val meta = listOfNotNull(mode, distance, duration).joinToString(" · ")
    val a11y = listOfNotNull(stringResource(R.string.route_origin), origin, stringResource(R.string.route_destination), destination, mode, distance, duration)
        .joinToString(", ")
    val select = { onSelect(entry) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = select)
            .clearAndSetSemantics {
                contentDescription = a11y
                role = Role.Button
                demoEntryId = entry.id
                onClick { if (enabled) select(); enabled }
            }
            .testTag("demo-entry")
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_ring), contentDescription = null, tint = t.uiOnSurface.c(), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(12.dp))
                Text(origin, style = NavType.bodyLarge.copy(localeList = NAME_LOCALE.takeIf { entry.originName != null }), color = t.uiOnSurface.c())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_place), contentDescription = null, tint = t.pinFill.c(), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(12.dp))
                Text(destination, style = NavType.bodyLarge.copy(localeList = NAME_LOCALE.takeIf { entry.destinationName != null }), color = t.uiOnSurface.c())
            }
            Text(meta, style = NavType.body.copy(fontFeatureSettings = "tnum"), color = t.uiOnSurfaceVariant.c(), modifier = Modifier.padding(start = 24.dp))
        }
    }
}

@Composable
private fun ErrorRow(modifier: Modifier) {
    val t = LocalTokens.current
    Row(
        modifier.fillMaxWidth().testTag("demo-entry-error").semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = t.uiError.c(), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.status_generic_error), style = NavType.body, color = t.uiOnSurface.c())
    }
}

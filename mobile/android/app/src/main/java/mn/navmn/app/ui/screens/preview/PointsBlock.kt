package mn.navmn.app.ui.screens.preview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mn.navmn.app.R
import mn.navmn.app.i18n.Strings
import mn.navmn.app.preview.PreviewState
import mn.navmn.app.preview.points.PointRules
import mn.navmn.app.preview.points.PointSide
import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.search.PlaceDisplay
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c

// NAV-018 UI (screen spec docs/design/screens/NAV-018-android-origin-turn-list.md): the points block that replaces the
// NAV-011 header row, the O1 hint, the coordinate-card buttons in preview context and the markers' accessibility nodes.

/** NAV-018 actions (unidirectional; the defaults keep the NAV-005 / NAV-011 call sites and tests unchanged). */
class PointActions(
    /** A tap on the start or destination field: opens the point editor (Q5). */
    val onField: (PointSide) -> Unit = {},
    val onSwap: () -> Unit = {},
    val onEditorQuery: (String) -> Unit = {},
    val onEditorResult: (PlaceDisplay.Info, String) -> Unit = { _, _ -> },
    val onEditorRetry: () -> Unit = {},
    /** «Миний байршил» option (AC 3). */
    val onEditorMyLocation: () -> Unit = {},
    /** Back / map tap / lock-card «Хаах» path: the editor closes, nothing changes (AC 7). */
    val onEditorClose: () -> Unit = {},
    /** Focus on the editor field: true → typing allowed; false → the lock card is shown (AC 34). */
    val onEditorFieldTap: () -> Boolean = { true },
    val onCardSetOrigin: () -> Unit = {},
    val onCardSetDestination: () -> Unit = {},
    /** A turn-list row tap (AC 22): the index of the step in the selected route. */
    val onTurnRow: (Int) -> Unit = {},
)

/** The text a field shows: the point's label in the UI language, «Миний байршил» while it is resolved, "" when empty. */
internal fun fieldText(p: RoutePoint?, pendingDevice: Boolean, strings: Strings, myLocation: String): String = when {
    p != null -> PointRules.label(p, strings)
    pendingDevice -> myLocation
    else -> ""
}

/**
 * Q4 points block: grid `48 dp | 1fr | 48 dp`. ✕ «Хаах» aligned with the start field, one surface-container holding the
 * start and destination fields (48 dp each, hairline between them inset by 48 dp), swap ⇅ centred at the end.
 */
@Composable
internal fun PointsBlock(s: PreviewState, strings: Strings, pa: PointActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val myLocation = stringResource(R.string.marker_my_location)
    val originText = fieldText(s.origin, !s.originEmpty, strings, myLocation)
    val destText = PointRules.label(s.destination, strings)
    val deviceStart = s.origin is RoutePoint.MyLocation || (s.origin == null && !s.originEmpty)
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp).testTag("route-points"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp).testTag("route-close")) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close), tint = t.uiOnSurface.c())
        }
        Surface(color = t.uiSurfaceContainer.c(), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
            Column {
                PointField(
                    name = stringResource(R.string.route_origin),
                    text = originText,
                    placeholder = stringResource(R.string.route_origin_placeholder),
                    icon = if (deviceStart) R.drawable.ic_my_location else R.drawable.ic_ring,
                    iconTint = if (deviceStart) t.uiPrimary.c() else t.uiOnSurfaceVariant.c(),
                    textColour = if (deviceStart) t.uiPrimary.c() else t.uiOnSurface.c(),
                    tag = "route-origin",
                    onClick = { pa.onField(PointSide.ORIGIN) },
                )
                HorizontalDivider(color = t.uiOutlineVariant.c(), modifier = Modifier.padding(start = 48.dp))
                PointField(
                    name = stringResource(R.string.route_destination),
                    text = destText,
                    placeholder = stringResource(R.string.route_destination_placeholder),
                    icon = R.drawable.ic_place,
                    iconTint = t.pinFill.c(),
                    textColour = t.uiOnSurface.c(),
                    tag = "route-destination",
                    onClick = { pa.onField(PointSide.DESTINATION) },
                )
            }
        }
        Box(Modifier.height(97.dp), contentAlignment = Alignment.Center) { SwapButton(enabled = s.origin != null, onSwap = pa.onSwap) }
    }
}

/** Read-only "button field" (Components › Start field / destination field): role button, name + state description. */
@Composable
private fun PointField(
    name: String,
    text: String,
    placeholder: String,
    icon: Int,
    iconTint: Color,
    textColour: Color,
    tag: String,
    onClick: () -> Unit,
) {
    val t = LocalTokens.current
    val shown = text.ifEmpty { placeholder }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = name
                stateDescription = shown
            }
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        Text(
            shown,
            style = NavType.bodyLarge,
            color = if (text.isEmpty()) t.uiOnSurfaceVariant.c() else textColour,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Swap ⇅ (AC 11): 48 × 48 dp, disabled (still read by TalkBack) while a point is empty; M3 plain tooltip on long-press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwapButton(enabled: Boolean, onSwap: () -> Unit) {
    val t = LocalTokens.current
    val label = stringResource(R.string.route_swap)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onSwap,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(contentColor = t.uiOnSurfaceVariant.c(), disabledContentColor = t.uiDisabledContent.c()),
            modifier = Modifier.size(48.dp).testTag("route-swap"),
        ) {
            Icon(painterResource(R.drawable.ic_swap_vert), contentDescription = label, modifier = Modifier.size(24.dp))
        }
    }
}

/** Q7 O1 hint above «Эхлэх»: 20 dp info icon + text, `ui.on-surface-variant`, wraps, never truncated. */
@Composable
internal fun StartHint(modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Row(modifier.fillMaxWidth().testTag("route-origin-hint"), verticalAlignment = Alignment.Top) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = t.uiOnSurfaceVariant.c(), modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.route_start_only_from_location), style = NavType.body, color = t.uiOnSurfaceVariant.c())
    }
}

/**
 * Q6 / AC 6: in preview context the coordinate card's «Маршрут гаргах» is replaced by two stacked, full-width, filled
 * tonal buttons «Эхлэх цэг болгох» / «Очих газар болгох» (≥ 48 dp, 8 dp apart), usable before `reverse` answers.
 */
@Composable
internal fun CoordinateCardPointButtons(pa: PointActions) {
    val t = LocalTokens.current
    val colours = ButtonDefaults.filledTonalButtonColors(containerColor = t.uiPrimaryContainer.c(), contentColor = t.uiOnPrimaryContainer.c())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = pa.onCardSetOrigin, colors = colours, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("coord-set-origin")) {
            Icon(painterResource(R.drawable.ic_ring), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.route_set_origin), style = NavType.label)
        }
        FilledTonalButton(onClick = pa.onCardSetDestination, colors = colours, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("coord-set-destination")) {
            Icon(painterResource(R.drawable.ic_place), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.route_set_destination), style = NavType.label)
        }
    }
}

/**
 * AC 8 / map-style §7.7: MapLibre symbols are not exposed to TalkBack, so the markers' descriptions «Эхлэх цэг: …» and
 * «Очих газар: …» live on app-owned nodes (no start node while the start is empty).
 */
@Composable
internal fun MarkerDescriptions(s: PreviewState, strings: Strings, modifier: Modifier = Modifier) {
    val myLocation = stringResource(R.string.marker_my_location)
    val origin = fieldText(s.origin, !s.originEmpty, strings, myLocation)
    val originName = stringResource(R.string.route_origin)
    val destName = stringResource(R.string.route_destination)
    val dest = PointRules.label(s.destination, strings)
    Column(modifier) {
        if (origin.isNotEmpty()) Box(Modifier.size(1.dp).semantics { contentDescription = "$originName: $origin" }.testTag("marker-origin"))
        Box(Modifier.size(1.dp).semantics { contentDescription = "$destName: $dest" }.testTag("marker-destination"))
    }
}

package mn.navmn.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mn.navmn.app.R
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Strings
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c

/**
 * NAV-021 AC 27 / NAV-023 AC 21–23, screen spec android-offline-pack F7 (O7): the non-interactive "offline" label —
 * icon 16 dp + OF24 «Офлайн» (caption 12/16, weight 500), fill `ui.surface-container`, 1 dp `ui.outline-variant`,
 * text and icon `ui.on-surface-variant` (8.44:1 day, 7.27:1 night), min height 24 dp, radius 8 dp, padding 2/8/2/6.
 * Never a touch target, never truncated. Its TalkBack name is OF25 «Офлайн газрын зургаас» (not the visible word); the
 * caller merges it into the anchor's node (`mergeDescendants`), so it is never focusable on its own.
 */
@Composable
fun OfflineIndicator(strings: Strings, modifier: Modifier = Modifier) {
    val name = strings[StringKey.OFFLINE_INDICATOR_A11Y]
    val t = LocalTokens.current
    Surface(
        color = t.uiSurfaceContainer.c(),
        contentColor = t.uiOnSurfaceVariant.c(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, t.uiOutlineVariant.c()),
        modifier = modifier.testTag("offline-indicator").heightIn(min = 24.dp).clearAndSetSemantics { contentDescription = name },
    ) {
        Row(
            Modifier.padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(painterResource(R.drawable.ic_offline_pin), contentDescription = null, modifier = Modifier.size(16.dp), tint = t.uiOnSurfaceVariant.c())
            Text(strings[StringKey.OFFLINE_INDICATOR], style = NavType.caption.copy(fontWeight = FontWeight.Medium), color = t.uiOnSurfaceVariant.c(), maxLines = 1, softWrap = false)
        }
    }
}

/**
 * Screen spec F9: in the S5 progress panel the indicator is the 20 dp glyph only (no height cost at 360 dp); its name
 * OF25 is appended to the progress node's description by [mn.navmn.app.ui.screens.TripProgressPanel].
 */
@Composable
fun OfflineIndicatorIcon(modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Icon(
        painterResource(R.drawable.ic_offline_pin),
        contentDescription = null,
        tint = t.uiOnSurfaceVariant.c(),
        modifier = modifier.testTag("offline-indicator-icon").size(20.dp).clearAndSetSemantics { },
    )
}

package mn.navmn.app.background.battery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mn.navmn.app.R
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c

/** What the preview sheet needs to place H1 (screen spec Layout rule 10). */
class BatteryHintSlot(
    /** True while the hint applies (restricted, a route is shown, not guiding, not dismissed). */
    val visible: Boolean,
    val onExpand: () -> Unit,
    val onDismiss: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/**
 * NAV-012 H1 slot for the NAV-011 route-preview sheet: the sheet calls [BatteryPreviewEntry] at the end of its summary
 * region (collapsed; before the pinned «Эхлэх») and [BatteryPreviewHint] as the first item of its expanded lower part
 * (and after the summary on wide windows). Provided by the root; null = nothing to show.
 */
val LocalBatteryHint = compositionLocalOf<BatteryHintSlot?> { null }

/** Collapsed sheet: the one-line entry row, only while the hint applies (the sheet supplies the 16 dp side margins). */
@Composable
fun BatteryPreviewEntry(modifier: Modifier = Modifier) {
    val slot = LocalBatteryHint.current ?: return
    if (slot.visible) BatteryEntryRow(slot.onExpand, modifier)
}

/** Expanded sheet / wide windows: the full hint card, only while the hint applies. */
@Composable
fun BatteryPreviewHint(modifier: Modifier = Modifier) {
    val slot = LocalBatteryHint.current ?: return
    if (slot.visible) BatteryHintCard(slot.onDismiss, slot.onOpenSettings, modifier)
}

/**
 * H1 entry row (collapsed sheet): 48 dp, `ui.surface-container`, 12 dp radius; `battery_alert` in `ui.error`,
 * the B2 hint text on one line cut with «…» (`maxLines = 1`, never wraps; AC 26 of 2026-10-04, screen spec Design
 * note 9), trailing `expand_less` (decorative), no buttons. Role button; the merged semantics carry the full B2 text
 * (Compose keeps the untruncated string), so TalkBack reads all of B2 although the screen shows it cut (QA D4).
 * A tap expands the sheet with the full hint in view. Test tag `battery-entry`.
 */
@Composable
fun BatteryEntryRow(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(t.uiSurfaceContainer.c())
            .clickable(role = Role.Button, onClick = onExpand)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("battery-entry"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_battery_alert), contentDescription = null, tint = t.uiError.c(), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.battery_hint),
            style = NavType.body,
            color = t.uiOnSurface.c(),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(painterResource(R.drawable.ic_expand_less), contentDescription = null, tint = t.uiOnSurfaceVariant.c(), modifier = Modifier.size(24.dp))
    }
}

/**
 * H1 full hint (expanded sheet / wide): text B2 full width (never truncated), bottom row with the decorative battery
 * icon, then «Хаах» and «Тохиргоо нээх» (48 dp, wrap under each other when they do not fit). Not a live region.
 * Test tag `battery-hint`.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BatteryHintCard(onDismiss: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.uiSurfaceContainer.c())
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 4.dp)
            .testTag("battery-hint"),
    ) {
        Text(stringResource(R.string.battery_hint), style = NavType.body, color = t.uiOnSurface.c(), modifier = Modifier.semantics(mergeDescendants = true) {})
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_battery_alert), contentDescription = null, tint = t.uiOnSurfaceVariant.c(), modifier = Modifier.size(24.dp))
            Spacer(Modifier.weight(1f))
            // The two buttons wrap under each other when they do not fit (200 % font scale, 320 dp).
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("battery-hint-close")) {
                    Text(stringResource(R.string.action_close), style = NavType.label, color = t.uiPrimary.c())
                }
                TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp).testTag("battery-hint-settings")) {
                    Text(stringResource(R.string.action_open_settings), style = NavType.label, color = t.uiPrimary.c())
                }
            }
        }
    }
}

/**
 * H2 «Батарейн хязгаарлалт» row in S7, after «Дуут заавар» (AC 28): divider above, leading icon (`battery_alert` in
 * `ui.error` while restricted, neutral battery when exempt), headline B1, supporting text B2 only while restricted,
 * «Тохиргоо нээх» below, aligned with the text. Shown whatever the battery state. Test tag `settings-battery`.
 */
@Composable
fun BatterySettingsRow(restricted: Boolean, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Column(modifier.fillMaxWidth().testTag("settings-battery")) {
        HorizontalDivider(color = t.uiOutlineVariant.c())
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                painterResource(if (restricted) R.drawable.ic_battery_alert else R.drawable.ic_battery_full),
                contentDescription = null,
                tint = if (restricted) t.uiError.c() else t.uiOnSurfaceVariant.c(),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.battery_restrictions), style = NavType.bodyLarge, color = t.uiOnSurface.c())
                if (restricted) {
                    Text(stringResource(R.string.battery_hint), style = NavType.body, color = t.uiOnSurfaceVariant.c(), modifier = Modifier.testTag("settings-battery-hint"))
                }
                TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp).testTag("settings-battery-open")) {
                    Text(stringResource(R.string.action_open_settings), style = NavType.label, color = t.uiPrimary.c())
                }
            }
        }
    }
}

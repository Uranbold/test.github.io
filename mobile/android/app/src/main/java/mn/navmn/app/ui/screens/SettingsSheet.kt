package mn.navmn.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import mn.navmn.app.R
import mn.navmn.app.background.battery.BatterySettingsRow
import mn.navmn.app.i18n.Lang
import mn.navmn.app.settings.ThemeChoice
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c

/**
 * S7 «Тохиргоо» (AC 58, 60): theme, «Хэл», «Дуут заавар». Changes apply at once. The theme group has no visible
 * heading until the BA adds the glossary term (screen spec Known limitations 6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    theme: ThemeChoice,
    lang: Lang,
    voiceOn: Boolean,
    onTheme: (ThemeChoice) -> Unit,
    onLang: (Lang) -> Unit,
    onVoice: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** NAV-012 H2 (AC 28): the battery row, shown whatever the state; null = not wired (previews, older callers). */
    batteryRestricted: Boolean? = null,
    onOpenBatterySettings: () -> Unit = {},
    /** NAV-022 O2: the «Офлайн газрын зураг» section, the last one (F2); null in the demo build (B7) and old callers. */
    offlineSection: (@Composable () -> Unit)? = null,
    /** NAV-022 B4: open scrolled to the pack section (from a pack notification or message). */
    scrollToOffline: Boolean = false,
    onScrolledToOffline: () -> Unit = {},
    /** NAV-005 section P (AC 88, UX L1): the «Лиценз» row, the last item; null = not wired (previews, older callers). */
    onOpenLicences: (() -> Unit)? = null,
    /** Back from the licences page: reopen scrolled to the end, so the «Лиценз» row is in view again (UX P6). */
    scrollToEnd: Boolean = false,
) {
    val t = LocalTokens.current
    val scroll = rememberScrollState()
    if ((offlineSection != null && scrollToOffline) || scrollToEnd) {
        LaunchedEffect(Unit) {
            // The section is last: once the sheet's content is measured, scroll to its end.
            withTimeoutOrNull(2_000) { snapshotFlow { scroll.maxValue }.first { it in 1 until Int.MAX_VALUE } }
            if (scroll.maxValue in 1 until Int.MAX_VALUE) {
                if (scrollToEnd) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
            }
            if (scrollToOffline) onScrolledToOffline()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.uiSurface.c(), modifier = Modifier.testTag("settings-sheet")) {
        // NAV-012: the battery row is the last item and needs scrolling on 360×640 (screen spec Known limitations 5).
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.settings_title), style = NavType.title, color = t.uiOnSurface.c(), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            // NAV-012 T1: «Автомат» = sunrise/sunset at the current position (no supporting line until a glossary term exists).
            for ((choice, label) in listOf(ThemeChoice.DAY to R.string.theme_day, ThemeChoice.NIGHT to R.string.theme_night, ThemeChoice.AUTO to R.string.theme_auto)) {
                RadioRow(stringResource(label), theme == choice) { onTheme(choice) }
            }
            HorizontalDivider(color = t.uiOutlineVariant.c())
            Text(stringResource(R.string.language_label), style = NavType.label, color = t.uiOnSurfaceVariant.c(), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            RadioRow(stringResource(R.string.language_mn), lang == Lang.MN) { onLang(Lang.MN) }
            RadioRow(stringResource(R.string.language_en), lang == Lang.EN) { onLang(Lang.EN) }
            HorizontalDivider(color = t.uiOutlineVariant.c())
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = voiceOn, role = Role.Switch, onValueChange = onVoice).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.voice_guidance), style = NavType.bodyLarge, color = t.uiOnSurface.c(), modifier = Modifier.weight(1f))
                Switch(checked = voiceOn, onCheckedChange = null)
            }
            if (batteryRestricted != null) BatterySettingsRow(batteryRestricted, onOpenBatterySettings)
            offlineSection?.invoke()
            if (onOpenLicences != null) LicencesRow(onOpenLicences)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = NavType.bodyLarge, color = t.uiOnSurface.c(), modifier = Modifier.padding(start = 16.dp))
    }
}

/** NAV-005 AC 88 / UX L1: «Лиценз», last in S7 below a divider, ≥ 56 dp, the whole row is the target. */
@Composable
private fun LicencesRow(onClick: () -> Unit) {
    val t = LocalTokens.current
    HorizontalDivider(color = t.uiOutlineVariant.c())
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp).testTag("licences-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = t.uiOnSurfaceVariant.c(), modifier = Modifier.size(24.dp))
        Text(stringResource(R.string.licences_title), style = NavType.bodyLarge, color = t.uiOnSurface.c(), modifier = Modifier.weight(1f).padding(start = 16.dp))
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = t.uiOutline.c(), modifier = Modifier.size(24.dp))
    }
}

package mn.navmn.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import mn.navmn.app.R
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
) {
    val t = LocalTokens.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.uiSurface.c(), modifier = Modifier.testTag("settings-sheet")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.settings_title), style = NavType.title, color = t.uiOnSurface.c(), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
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

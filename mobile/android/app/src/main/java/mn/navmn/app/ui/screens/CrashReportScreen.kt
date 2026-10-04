package mn.navmn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mn.navmn.app.R

/**
 * Bug B-NAV019-01 diagnostic, debug and demo builds only: the report of the last crash ([mn.navmn.app.log.CrashLog]),
 * shown on the next launch instead of the map until it is closed, so a crash at map start cannot hide it.
 *
 * Text: `status_generic_error` and `action_close` (glossary rows Generic error and Close (dismiss)). The glossary has no
 * term for Copy or Share yet (requested from the BA): Copy uses the platform's own translated label
 * (`android.R.string.copy`) and Share is the platform share icon. The report itself is technical data (selectable).
 */
@Composable
fun CrashReportScreen(
    report: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize().testTag("crash-report"), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            Text(stringResource(R.string.status_generic_error), style = MaterialTheme.typography.titleLarge)
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp)) {
                Text(
                    report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()).testTag("crash-report-text"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onCopy, modifier = Modifier.testTag("crash-report-copy")) {
                    Text(stringResource(android.R.string.copy))
                }
                IconButton(onClick = onShare, modifier = Modifier.testTag("crash-report-share")) {
                    // No glossary term for "Share" yet (BA request); the platform icon is used without a label.
                    Icon(painterResource(android.R.drawable.ic_menu_share), contentDescription = null)
                }
                Button(onClick = onClose, modifier = Modifier.weight(1f).testTag("crash-report-close")) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}

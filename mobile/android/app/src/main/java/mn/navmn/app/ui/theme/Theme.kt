package mn.navmn.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LocalTokens = staticCompositionLocalOf { Tokens.Day }
val LocalNight = staticCompositionLocalOf { false }

fun Long.c(): Color = Color(this.toInt())

/** tokens.json typography (sp = token px), screen spec › Components. */
object NavType {
    val navDistance = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val navInstruction = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
    val navStreet = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    val title = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
}

/** Compose colours from docs/design/tokens.json (generated TokenColours; no copied hex values). */
@Composable
fun NavTheme(night: Boolean, content: @Composable () -> Unit) {
    val t = if (night) Tokens.Night else Tokens.Day
    val scheme = if (night) {
        darkColorScheme(
            primary = t.uiPrimary.c(), onPrimary = t.uiOnPrimary.c(), primaryContainer = t.uiPrimaryContainer.c(),
            onPrimaryContainer = t.uiOnPrimaryContainer.c(), surface = t.uiSurface.c(), onSurface = t.uiOnSurface.c(),
            onSurfaceVariant = t.uiOnSurfaceVariant.c(), surfaceContainer = t.uiSurfaceContainer.c(),
            outline = t.uiOutline.c(), outlineVariant = t.uiOutlineVariant.c(), error = t.uiError.c(),
            background = t.uiSurface.c(), onBackground = t.uiOnSurface.c(), scrim = t.uiScrim.c(),
            surfaceContainerLow = t.uiSurface.c(), surfaceContainerHigh = t.uiSurfaceContainer.c(),
            secondaryContainer = t.uiPrimaryContainer.c(), onSecondaryContainer = t.uiOnPrimaryContainer.c(),
        )
    } else {
        lightColorScheme(
            primary = t.uiPrimary.c(), onPrimary = t.uiOnPrimary.c(), primaryContainer = t.uiPrimaryContainer.c(),
            onPrimaryContainer = t.uiOnPrimaryContainer.c(), surface = t.uiSurface.c(), onSurface = t.uiOnSurface.c(),
            onSurfaceVariant = t.uiOnSurfaceVariant.c(), surfaceContainer = t.uiSurfaceContainer.c(),
            outline = t.uiOutline.c(), outlineVariant = t.uiOutlineVariant.c(), error = t.uiError.c(),
            background = t.uiSurface.c(), onBackground = t.uiOnSurface.c(), scrim = t.uiScrim.c(),
            surfaceContainerLow = t.uiSurface.c(), surfaceContainerHigh = t.uiSurfaceContainer.c(),
            secondaryContainer = t.uiPrimaryContainer.c(), onSecondaryContainer = t.uiOnPrimaryContainer.c(),
        )
    }
    CompositionLocalProvider(LocalTokens provides t, LocalNight provides night) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
